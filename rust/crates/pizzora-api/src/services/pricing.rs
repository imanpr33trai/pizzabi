use std::sync::Arc;

use pizzora_core::{
    CartValidation, EvaluatedCartItem, FulfillmentType, PricedLineInput, PricingEngine,
};
use pizzora_db::{AppliedCouponSummaryDto, CouponService};
use sqlx::{PgPool, Row as _};
use uuid::Uuid;

use crate::dto::{
    DeliveryZoneQuoteDto, PriceBreakdownDto, PricingQuoteRequest, PricingQuoteResponse,
};
use crate::error::ApiError;
use crate::extractors::parse_uuid_param;
use crate::services::cart::{
    ensure_half_and_half_config_matches, ensure_modifier_features_enabled, evaluate_cart_for_api,
    evaluated_summary_to_item_dtos, load_extended_catalog_ref_lookup, to_core_cart_inputs,
};
use crate::storage::ObjectStorageClient;

fn sum_extended_modifier_paise(
    items: &[EvaluatedCartItem],
    extract: impl Fn(&EvaluatedCartItem) -> i64,
) -> Result<i64, ApiError> {
    items.iter().try_fold(0_i64, |total, item| {
        let line_extra = extract(item)
            .checked_mul(i64::from(item.quantity))
            .ok_or_else(|| {
                ApiError::validation(
                    "ARITHMETIC_OVERFLOW",
                    "Extended modifier total exceeds the supported range.",
                )
            })?;
        total.checked_add(line_extra).ok_or_else(|| {
            ApiError::validation(
                "ARITHMETIC_OVERFLOW",
                "Extended modifier total exceeds the supported range.",
            )
        })
    })
}

#[derive(Clone)]
pub struct PricingService {
    pool: PgPool,
    storage: Arc<dyn ObjectStorageClient>,
}

impl PricingService {
    pub fn new(pool: PgPool, storage: Arc<dyn ObjectStorageClient>) -> Self {
        Self { pool, storage }
    }

    pub async fn quote_price(
        &self,
        user_id: Uuid,
        request: &PricingQuoteRequest,
    ) -> Result<PricingQuoteResponse, ApiError> {
        ensure_modifier_features_enabled(&request.items)?;
        let core_inputs = to_core_cart_inputs(&request.items);
        let parsed = CartValidation::validate_and_parse_inputs(&core_inputs)?;
        let merged = CartValidation::merge_duplicate_configurations(parsed);

        let mut conn = self.pool.acquire().await?;
        let lookup = load_extended_catalog_ref_lookup(&mut conn, &*self.storage).await?;
        CartValidation::ensure_catalog_fk_references(&merged, &lookup)?;
        ensure_half_and_half_config_matches(&merged, &lookup)?;

        // Evaluate through the shared pizzora-core engine (linear + half-and-half pricing).
        let evaluated = evaluate_cart_for_api(&merged, &lookup)?;
        if !evaluated.all_available {
            return Err(ApiError::unprocessable(
                "CART_ITEM_UNAVAILABLE",
                "One or more items in your cart are currently unavailable.",
            ));
        }

        let settings_row = sqlx::query(
            "SELECT gst_bps, is_accepting_orders FROM restaurant_settings WHERE id = 1",
        )
        .fetch_optional(&mut *conn)
        .await?;
        let (gst_bps, is_accepting_orders) = match settings_row {
            Some(r) => (r.try_get("gst_bps")?, r.try_get("is_accepting_orders")?),
            None => (500_i32, true),
        };

        let mut resolved_zone: Option<DeliveryZoneQuoteDto> = None;
        let mut delivery_fee_paise: i64 = 0;

        if request.fulfillment_type == FulfillmentType::Delivery {
            let pincode_from_addr = if let Some(addr_raw) = request
                .address_id
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
                let address_id = parse_uuid_param(addr_raw, "address_id")?;
                let addr_row = sqlx::query("SELECT user_id, pincode FROM addresses WHERE id = $1")
                    .bind(address_id)
                    .fetch_optional(&mut *conn)
                    .await?
                    .ok_or_else(|| {
                        ApiError::not_found(
                            "ADDRESS_NOT_FOUND",
                            format!("Address {address_id} was not found."),
                        )
                    })?;
                let owner_id: Uuid = addr_row.try_get("user_id")?;
                if owner_id != user_id {
                    return Err(ApiError::forbidden(
                        "FORBIDDEN",
                        "You do not have access to this delivery address.",
                    ));
                }
                Some(addr_row.try_get::<String, _>("pincode")?)
            } else {
                None
            };

            let target_pincode = request
                .pincode
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(ToOwned::to_owned)
                .or(pincode_from_addr);

            if let Some(pin) = target_pincode {
                let zone_row = sqlx::query(
                    r#"
                    SELECT pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins
                    FROM delivery_zones
                    WHERE pincode = $1 AND is_active = TRUE
                    "#,
                )
                .bind(&pin)
                .fetch_optional(&mut *conn)
                .await?
                .ok_or_else(|| {
                    ApiError::unprocessable(
                        "ZONE_UNSERVICEABLE",
                        format!("Pincode {pin} is currently outside our delivery zone."),
                    )
                    .with_detail("pincode", &pin)
                })?;

                let z = DeliveryZoneQuoteDto {
                    pincode: zone_row.try_get("pincode")?,
                    area_name: zone_row.try_get("area_name")?,
                    min_order_paise: zone_row.try_get("min_order_paise")?,
                    delivery_fee_paise: zone_row.try_get("delivery_fee_paise")?,
                    estimated_mins: zone_row.try_get("estimated_mins")?,
                };
                delivery_fee_paise = z.delivery_fee_paise;
                resolved_zone = Some(z);
            }
        }

        let validated_coupon = match request
            .coupon_code
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
        {
            Some(raw_code) => Some(
                CouponService::validate_coupon_for_quote(
                    &mut conn,
                    raw_code,
                    user_id,
                    evaluated.subtotal_paise,
                )
                .await?,
            ),
            None => None,
        };

        // Unit prices already include Phase 8 modifiers via `CartValidation::evaluate_cart`.
        let priced_lines: Vec<PricedLineInput> = evaluated
            .items
            .iter()
            .map(|i| PricedLineInput {
                unit_price_paise: i.unit_price_paise,
                quantity: i.quantity,
                is_taxable: i.is_taxable,
            })
            .collect();

        let breakdown = PricingEngine::calculate_quote(
            &priced_lines,
            gst_bps,
            delivery_fee_paise,
            validated_coupon.as_ref().map(|c| &c.spec),
        )?;

        let cheese_extra_paise = sum_extended_modifier_paise(&evaluated.items, |item| {
            item.cheese
                .as_ref()
                .map_or(0, |view| view.extra_price_paise)
        })?;
        let sauce_extra_paise = sum_extended_modifier_paise(&evaluated.items, |item| {
            item.sauce.as_ref().map_or(0, |view| view.extra_price_paise)
        })?;
        let standard_toppings_extra_paise =
            sum_extended_modifier_paise(&evaluated.items, |item| {
                item.standard_toppings
                    .iter()
                    .filter(|view| !view.is_removed)
                    .fold(0_i64, |total, view| {
                        total.saturating_add(view.extra_price_paise)
                    })
            })?;
        let half_and_half_surcharge_paise =
            sum_extended_modifier_paise(&evaluated.items, |item| {
                item.half_and_half
                    .as_ref()
                    .map_or(0, |view| view.surcharge_paise)
            })?;
        let modifier_extra_paise = cheese_extra_paise
            .checked_add(sauce_extra_paise)
            .and_then(|total| total.checked_add(standard_toppings_extra_paise))
            .and_then(|total| total.checked_add(half_and_half_surcharge_paise))
            .ok_or_else(|| {
                ApiError::validation(
                    "ARITHMETIC_OVERFLOW",
                    "Extended modifier total exceeds the supported range.",
                )
            })?;

        Ok(PricingQuoteResponse {
            items: evaluated_summary_to_item_dtos(&evaluated, &lookup),
            fulfillment_type: request.fulfillment_type,
            breakdown: PriceBreakdownDto {
                subtotal_paise: breakdown.subtotal_paise,
                taxable_subtotal_paise: breakdown.taxable_subtotal_paise,
                discount_paise: breakdown.discount_paise,
                taxable_amount_paise: breakdown.taxable_amount_paise,
                delivery_fee_paise: breakdown.delivery_fee_paise,
                gst_bps: breakdown.gst_bps,
                tax_paise: breakdown.tax_paise,
                cgst_paise: breakdown.cgst_paise,
                sgst_paise: breakdown.sgst_paise,
                total_paise: breakdown.total_paise,
                cheese_extra_paise,
                sauce_extra_paise,
                standard_toppings_extra_paise,
                half_and_half_surcharge_paise,
                modifier_extra_paise,
            },
            applied_coupon: validated_coupon.map(|c| AppliedCouponSummaryDto {
                id: c.id.to_string(),
                code: c.code,
                discount_type: c.spec.discount_type,
                discount_value: c.spec.discount_value,
                discount_paise: c.discount_paise,
            }),
            delivery_zone: resolved_zone,
            is_accepting_orders,
            gst_bps,
        })
    }
}

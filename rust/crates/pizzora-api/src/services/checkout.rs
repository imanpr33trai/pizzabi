use std::sync::Arc;

use chrono::{Duration, Utc};
use pizzora_core::{
    CartValidation, CouponEvaluator, FulfillmentType, OrderStatus, PaymentMethod, PricedLineInput,
    PricingEngine, ScheduleType,
};
use pizzora_db::{
    new_uuid_v4, record_audit_log, AuditContext, CouponService, DeliveryAddressSnapshotDto,
    InventoryService, OrderService, OutboxRepository, EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED,
};
use pizzora_payments::format_razorpay_receipt;
use serde_json::json;
use sha2::{Digest, Sha256};
use sqlx::{PgPool, Row as _};
use uuid::Uuid;

use crate::dto::{CheckoutRequest, CheckoutResponse};
use crate::error::ApiError;
use crate::extractors::parse_uuid_param;
use crate::services::cart::{load_catalog_ref_lookup, to_core_cart_inputs};
use crate::services::recipes::{CheckoutRecipeLineDemandInput, RecipeService};
use crate::services::slots::fulfillment_type_as_str;
use crate::storage::ObjectStorageClient;

const PAYMENT_WINDOW_SECONDS: i64 = 15 * 60;

#[derive(Clone)]
pub struct CheckoutService {
    pool: PgPool,
    storage: Arc<dyn ObjectStorageClient>,
}

impl CheckoutService {
    pub fn new(pool: PgPool, storage: Arc<dyn ObjectStorageClient>) -> Self {
        Self { pool, storage }
    }

    pub async fn checkout(
        &self,
        customer_id: Uuid,
        raw_idempotency_key: Option<&str>,
        request: &CheckoutRequest,
        audit: &AuditContext,
    ) -> Result<CheckoutResponse, ApiError> {
        let idempotency_key = raw_idempotency_key
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .ok_or_else(|| {
                ApiError::validation(
                    "MISSING_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required for checkout.",
                )
            })?;
        if idempotency_key.len() > 128 {
            return Err(ApiError::validation(
                "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key must not exceed 128 characters.",
            ));
        }

        let core_inputs = to_core_cart_inputs(&request.items);
        let parsed_items = CartValidation::validate_and_parse_inputs(&core_inputs)?;
        let merged_items = CartValidation::merge_duplicate_configurations(parsed_items);
        if merged_items.is_empty() {
            return Err(ApiError::validation(
                "EMPTY_CART",
                "At least one item is required for checkout.",
            ));
        }

        let request_hash = compute_request_hash(request, &merged_items)?;

        let mut tx = self.pool.begin().await?;
        let cust_str = customer_id.to_string();
        sqlx::query("SELECT pg_advisory_xact_lock(hashtext($1), hashtext($2))")
            .bind(&cust_str)
            .bind(idempotency_key)
            .execute(&mut *tx)
            .await?;

        if let Some(existing) = sqlx::query(
            "SELECT id, request_hash FROM orders WHERE customer_id = $1 AND idempotency_key = $2",
        )
        .bind(customer_id)
        .bind(idempotency_key)
        .fetch_optional(&mut *tx)
        .await?
        {
            let existing_id: Uuid = existing.try_get("id")?;
            let existing_hash: String = existing.try_get("request_hash")?;
            if existing_hash != request_hash {
                return Err(ApiError::conflict(
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key was already used with a different checkout payload.",
                ));
            }
            let detail = OrderService::load_order_detail_in_tx(&mut tx, existing_id)
                .await?
                .ok_or_else(ApiError::internal)?;
            tx.commit().await?;
            return Ok(CheckoutResponse {
                order: detail,
                idempotent_replay: true,
            });
        }

        let settings_row = sqlx::query(
            "SELECT is_accepting_orders, gst_bps FROM restaurant_settings WHERE id = 1",
        )
        .fetch_optional(&mut *tx)
        .await?;
        let (is_accepting_orders, gst_bps) = match settings_row {
            Some(r) => (r.try_get("is_accepting_orders")?, r.try_get("gst_bps")?),
            None => (true, 500_i32),
        };
        if !is_accepting_orders {
            return Err(ApiError::conflict(
                "STORE_CLOSED",
                "Store is temporarily paused and not accepting new orders right now.",
            ));
        }

        let lookup = load_catalog_ref_lookup(&mut tx, &*self.storage).await?;
        CartValidation::ensure_catalog_fk_references(&merged_items, &lookup)?;
        let evaluated = CartValidation::evaluate_cart(&merged_items, &lookup)?;
        if !evaluated.all_available {
            return Err(ApiError::unprocessable(
                "CART_ITEM_UNAVAILABLE",
                "One or more items in your cart are currently unavailable.",
            ));
        }

        let mut delivery_fee_paise: i64 = 0;
        let mut delivery_snapshot: Option<DeliveryAddressSnapshotDto> = None;

        if request.fulfillment_type == FulfillmentType::Delivery {
            let addr_raw = request
                .address_id
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .ok_or_else(|| {
                    ApiError::validation(
                        "ADDRESS_REQUIRED",
                        "address_id is required for DELIVERY orders.",
                    )
                })?;
            let address_id = parse_uuid_param(addr_raw, "address_id")?;
            let addr_row = sqlx::query(
                r#"
                SELECT id, user_id, label, line1, line2, landmark, pincode, city
                FROM addresses
                WHERE id = $1
                "#,
            )
            .bind(address_id)
            .fetch_optional(&mut *tx)
            .await?
            .ok_or_else(|| {
                ApiError::not_found(
                    "ADDRESS_NOT_FOUND",
                    format!("Address {address_id} was not found."),
                )
            })?;
            let owner_id: Uuid = addr_row.try_get("user_id")?;
            if owner_id != customer_id {
                return Err(ApiError::forbidden(
                    "FORBIDDEN",
                    "You do not have access to this delivery address.",
                ));
            }
            let pincode: String = addr_row.try_get("pincode")?;
            let zone_row = sqlx::query(
                r#"
                SELECT min_order_paise, delivery_fee_paise
                FROM delivery_zones
                WHERE pincode = $1 AND is_active = TRUE
                "#,
            )
            .bind(&pincode)
            .fetch_optional(&mut *tx)
            .await?
            .ok_or_else(|| {
                ApiError::unprocessable(
                    "ZONE_UNSERVICEABLE",
                    format!("Pincode {pincode} is currently outside our delivery zone."),
                )
                .with_detail("pincode", &pincode)
            })?;

            let min_order_paise: i64 = zone_row.try_get("min_order_paise")?;
            if evaluated.subtotal_paise < min_order_paise {
                return Err(ApiError::unprocessable(
                    "DELIVERY_MIN_ORDER_NOT_MET",
                    format!(
                        "Minimum order amount for delivery to {pincode} is Rs {}.",
                        min_order_paise / 100
                    ),
                )
                .with_detail("min_order_paise", min_order_paise.to_string()));
            }
            delivery_fee_paise = zone_row.try_get("delivery_fee_paise")?;
            delivery_snapshot = Some(DeliveryAddressSnapshotDto {
                address_id: address_id.to_string(),
                label: addr_row.try_get("label")?,
                line1: addr_row.try_get("line1")?,
                line2: addr_row.try_get("line2")?,
                landmark: addr_row.try_get("landmark")?,
                pincode,
                city: addr_row.try_get("city")?,
            });
        }

        let now = Utc::now();
        let is_razorpay = request.payment_method == PaymentMethod::Razorpay;
        let expires_at = if is_razorpay {
            Some(now + Duration::seconds(PAYMENT_WINDOW_SECONDS))
        } else {
            None
        };
        let initial_order_status = if is_razorpay {
            OrderStatus::PendingPayment
        } else {
            OrderStatus::Placed
        };
        let immediate_commit = !is_razorpay;

        let scheduled_slot_id: Option<Uuid> = if request.schedule_type == ScheduleType::Scheduled {
            let raw_slot = request
                .scheduled_slot_id
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .ok_or_else(|| {
                    ApiError::validation(
                        "SCHEDULED_SLOT_REQUIRED",
                        "scheduled_slot_id is required when schedule_type is SCHEDULED.",
                    )
                })?;
            let slot_id = parse_uuid_param(raw_slot, "scheduled_slot_id")?;
            let slot_row = sqlx::query(
                r#"
                SELECT fulfillment_type, cutoff_at, max_capacity, reserved_count, is_active
                FROM scheduled_slots
                WHERE id = $1
                FOR UPDATE
                "#,
            )
            .bind(slot_id)
            .fetch_optional(&mut *tx)
            .await?
            .ok_or_else(|| {
                ApiError::not_found(
                    "SLOT_NOT_FOUND",
                    format!("Scheduled slot {slot_id} was not found."),
                )
            })?;

            let slot_ft: String = slot_row.try_get("fulfillment_type")?;
            let slot_active: bool = slot_row.try_get("is_active")?;
            let cutoff_at: chrono::DateTime<Utc> = slot_row.try_get("cutoff_at")?;
            let max_capacity: i32 = slot_row.try_get("max_capacity")?;
            let reserved_count: i32 = slot_row.try_get("reserved_count")?;

            if !slot_active || slot_ft != fulfillment_type_as_str(request.fulfillment_type) {
                return Err(ApiError::unprocessable(
                    "SLOT_UNAVAILABLE",
                    "Selected scheduled slot is inactive or does not match fulfillment type.",
                ));
            }
            if cutoff_at <= now {
                return Err(ApiError::unprocessable(
                    "SLOT_CUTOFF_PASSED",
                    "Booking cutoff time has already passed for this slot.",
                ));
            }
            if reserved_count >= max_capacity {
                return Err(ApiError::conflict(
                    "SLOT_FULL",
                    "Selected scheduled slot has reached maximum capacity.",
                ));
            }

            sqlx::query(
                "UPDATE scheduled_slots SET reserved_count = reserved_count + 1 WHERE id = $1",
            )
            .bind(slot_id)
            .execute(&mut *tx)
            .await?;

            Some(slot_id)
        } else {
            if request
                .scheduled_slot_id
                .as_deref()
                .map(str::trim)
                .is_some_and(|s| !s.is_empty())
            {
                return Err(ApiError::validation(
                    "UNEXPECTED_SCHEDULED_SLOT",
                    "scheduled_slot_id must be omitted for ASAP orders.",
                ));
            }
            None
        };

        let coupon_raw = request
            .coupon_code
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty());
        let validated_coupon = match coupon_raw {
            Some(code) => Some(
                CouponService::validate_coupon_for_quote(
                    &mut tx,
                    code,
                    customer_id,
                    evaluated.subtotal_paise,
                )
                .await?,
            ),
            None => None,
        };

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
        if breakdown.total_paise <= 0 {
            return Err(ApiError::unprocessable(
                "ZERO_ORDER_TOTAL",
                "Order total must be greater than zero.",
            ));
        }

        let order_id = new_uuid_v4();
        let demand_inputs: Vec<CheckoutRecipeLineDemandInput> = merged_items
            .iter()
            .map(|m| CheckoutRecipeLineDemandInput {
                variant_id: m.variant_id,
                crust_id: m.crust_id,
                topping_ids: m.topping_ids.clone(),
                quantity: m.quantity,
            })
            .collect();
        let required_ingredients =
            RecipeService::compute_required_ingredients_for_items(&mut tx, &demand_inputs).await?;
        InventoryService::reserve_for_order(
            &mut tx,
            order_id,
            &required_ingredients,
            expires_at,
            immediate_commit,
            Some(customer_id),
        )
        .await?;

        let order_number = generate_order_number(order_id);
        let delivery_json = delivery_snapshot
            .as_ref()
            .map(serde_json::to_value)
            .transpose()
            .map_err(|_| ApiError::internal())?;
        let coupon_snapshot = validated_coupon.as_ref().map(|c| c.code.as_str());
        let schedule_str = match request.schedule_type {
            ScheduleType::Asap => "ASAP",
            ScheduleType::Scheduled => "SCHEDULED",
        };

        sqlx::query(
            r#"
            INSERT INTO orders (
                id, order_number, customer_id, idempotency_key, request_hash,
                fulfillment_type, schedule_type, scheduled_slot_id, delivery_address_snapshot,
                status, version, subtotal_paise, discount_paise, delivery_fee_paise,
                tax_paise, total_paise, gst_bps_snapshot, coupon_code_snapshot,
                is_priority, expires_at
            ) VALUES (
                $1, $2, $3, $4, $5,
                $6, $7, $8, $9,
                $10, 1, $11, $12, $13,
                $14, $15, $16, $17,
                FALSE, $18
            )
            "#,
        )
        .bind(order_id)
        .bind(&order_number)
        .bind(customer_id)
        .bind(idempotency_key)
        .bind(&request_hash)
        .bind(fulfillment_type_as_str(request.fulfillment_type))
        .bind(schedule_str)
        .bind(scheduled_slot_id)
        .bind(delivery_json)
        .bind(order_status_as_str(initial_order_status))
        .bind(breakdown.subtotal_paise)
        .bind(breakdown.discount_paise)
        .bind(breakdown.delivery_fee_paise)
        .bind(breakdown.tax_paise)
        .bind(breakdown.total_paise)
        .bind(breakdown.gst_bps)
        .bind(coupon_snapshot)
        .bind(expires_at)
        .execute(&mut *tx)
        .await?;

        if let Some(code) = coupon_raw {
            CouponService::reserve_coupon_for_order(
                &mut tx,
                code,
                customer_id,
                order_id,
                breakdown.subtotal_paise,
                expires_at,
                immediate_commit,
            )
            .await?;
        }

        if let Some(slot_id) = scheduled_slot_id {
            let slot_res_status = if immediate_commit {
                "COMMITTED"
            } else {
                "ACTIVE"
            };
            let slot_res_exp = if immediate_commit { None } else { expires_at };
            sqlx::query(
                r#"
                INSERT INTO scheduled_slot_reservations (id, slot_id, order_id, status, expires_at)
                VALUES ($1, $2, $3, $4, $5)
                "#,
            )
            .bind(new_uuid_v4())
            .bind(slot_id)
            .bind(order_id)
            .bind(slot_res_status)
            .bind(slot_res_exp)
            .execute(&mut *tx)
            .await?;
        }

        for item in &evaluated.items {
            let pid = parse_uuid_param(&item.product_id, "product_id")?;
            let size_label = item
                .size_code
                .map(|s| match s {
                    pizzora_core::SizeCode::Regular => "REGULAR",
                    pizzora_core::SizeCode::Medium => "MEDIUM",
                    pizzora_core::SizeCode::Large => "LARGE",
                })
                .unwrap_or("REGULAR");
            let topping_names: Vec<String> = item.toppings.iter().map(|t| t.name.clone()).collect();
            let toppings_json =
                serde_json::to_value(&topping_names).map_err(|_| ApiError::internal())?;
            sqlx::query(
                r#"
                INSERT INTO order_items (
                    id, order_id, product_id, product_name_snapshot, variant_size_snapshot,
                    crust_name_snapshot, toppings_snapshot, unit_price_paise, quantity, line_total_paise
                ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
                "#,
            )
            .bind(new_uuid_v4())
            .bind(order_id)
            .bind(pid)
            .bind(&item.product_name)
            .bind(size_label)
            .bind(&item.crust_name)
            .bind(toppings_json)
            .bind(item.unit_price_paise)
            .bind(item.quantity)
            .bind(item.line_total_paise)
            .execute(&mut *tx)
            .await?;
        }

        sqlx::query(
            r#"
            INSERT INTO order_status_history (id, order_id, from_status, to_status, changed_by, reason)
            VALUES ($1, $2, NULL, $3, $4, $5)
            "#,
        )
        .bind(new_uuid_v4())
        .bind(order_id)
        .bind(order_status_as_str(initial_order_status))
        .bind(customer_id)
        .bind(Some("Customer checkout created"))
        .execute(&mut *tx)
        .await?;

        let payment_id = new_uuid_v4();
        let attempt_id = new_uuid_v4();
        let payment_method_str = if is_razorpay { "RAZORPAY" } else { "COD" };
        let payment_status_str = if is_razorpay { "UNPAID" } else { "COD_PENDING" };
        let attempt_status_str = if is_razorpay {
            "INITIATING"
        } else {
            "AWAITING_CUSTOMER_PAYMENT"
        };
        let receipt = if is_razorpay {
            format_razorpay_receipt(order_id, &attempt_id.to_string())?
        } else {
            format!("cod:{attempt_id}")
        };

        sqlx::query(
            r#"
            INSERT INTO payments (id, order_id, method, status, amount_paise, captured_paise, refunded_paise, currency)
            VALUES ($1, $2, $3, $4, $5, 0, 0, 'INR')
            "#,
        )
        .bind(payment_id)
        .bind(order_id)
        .bind(payment_method_str)
        .bind(payment_status_str)
        .bind(breakdown.total_paise)
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            r#"
            INSERT INTO payment_attempts (
                id, payment_id, attempt_number, method, status, amount_paise,
                idempotency_receipt, expires_at
            ) VALUES ($1, $2, 1, $3, $4, $5, $6, $7)
            "#,
        )
        .bind(attempt_id)
        .bind(payment_id)
        .bind(payment_method_str)
        .bind(attempt_status_str)
        .bind(breakdown.total_paise)
        .bind(&receipt)
        .bind(expires_at)
        .execute(&mut *tx)
        .await?;

        if is_razorpay {
            let gw_payload = json!({
                "order_id": order_id.to_string(),
                "order_number": order_number,
                "customer_id": customer_id.to_string(),
                "payment_id": payment_id.to_string(),
                "payment_attempt_id": attempt_id.to_string(),
                "amount_paise": breakdown.total_paise,
                "currency": "INR",
                "receipt": receipt,
            });
            OutboxRepository::insert_outbox_event(
                &mut tx,
                "PAYMENT",
                payment_id,
                1,
                EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED,
                &gw_payload,
            )
            .await?;
        } else {
            let order_payload = json!({
                "order_id": order_id.to_string(),
                "order_number": order_number,
                "customer_id": customer_id.to_string(),
                "status": "PLACED",
                "version": 1,
                "payment_method": "COD",
                "total_paise": breakdown.total_paise,
            });
            OutboxRepository::insert_outbox_event(
                &mut tx,
                "ORDER",
                order_id,
                1,
                "ORDER_PLACED",
                &order_payload,
            )
            .await?;
        }

        if request.clear_cart {
            sqlx::query(
                r#"
                DELETE FROM cart_items
                WHERE cart_id IN (SELECT id FROM carts WHERE user_id = $1)
                "#,
            )
            .bind(customer_id)
            .execute(&mut *tx)
            .await?;
        }

        let audit_after = json!({
            "order_number": order_number,
            "payment_method": payment_method_str,
            "total_paise": breakdown.total_paise,
            "status": order_status_as_str(initial_order_status),
        });
        let entity_id = order_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "ORDER_CHECKOUT_CREATED",
            "order",
            &entity_id,
            None,
            Some(&audit_after),
        )
        .await?;

        let detail = OrderService::load_order_detail_in_tx(&mut tx, order_id)
            .await?
            .ok_or_else(ApiError::internal)?;
        tx.commit().await?;

        Ok(CheckoutResponse {
            order: detail,
            idempotent_replay: false,
        })
    }
}

fn order_status_as_str(status: OrderStatus) -> &'static str {
    match status {
        OrderStatus::PendingPayment => "PENDING_PAYMENT",
        OrderStatus::Placed => "PLACED",
        OrderStatus::Accepted => "ACCEPTED",
        OrderStatus::Preparing => "PREPARING",
        OrderStatus::Ready => "READY",
        OrderStatus::OutForDelivery => "OUT_FOR_DELIVERY",
        OrderStatus::Completed => "COMPLETED",
        OrderStatus::Cancelled => "CANCELLED",
        OrderStatus::Expired => "EXPIRED",
    }
}

fn generate_order_number(order_id: Uuid) -> String {
    let ts_part = Utc::now().timestamp() % 1_000_000;
    let id_hex = order_id.simple().to_string();
    format!(
        "PZ-{:06}-{}",
        ts_part.abs(),
        &id_hex[..4].to_ascii_uppercase()
    )
}

fn compute_request_hash(
    request: &CheckoutRequest,
    merged: &[pizzora_core::ParsedCartItem],
) -> Result<String, ApiError> {
    let normalized_coupon = match request
        .coupon_code
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        Some(c) => CouponEvaluator::normalize_code(c).unwrap_or_else(|_| c.to_ascii_uppercase()),
        None => String::new(),
    };
    let mut item_parts: Vec<String> = merged
        .iter()
        .map(|i| {
            let mut tids: Vec<String> = i.topping_ids.iter().map(ToString::to_string).collect();
            tids.sort();
            format!(
                "{}:{}:{}:{}:{}",
                i.product_id,
                i.variant_id,
                i.crust_id,
                tids.join(","),
                i.quantity
            )
        })
        .collect();
    item_parts.sort();

    let canonical = format!(
        "{}|{}|{}|{}|{}|{}|{}",
        fulfillment_type_as_str(request.fulfillment_type),
        match request.schedule_type {
            ScheduleType::Asap => "ASAP",
            ScheduleType::Scheduled => "SCHEDULED",
        },
        request
            .scheduled_slot_id
            .as_deref()
            .map(str::trim)
            .unwrap_or(""),
        request.address_id.as_deref().map(str::trim).unwrap_or(""),
        match request.payment_method {
            PaymentMethod::Razorpay => "RAZORPAY",
            PaymentMethod::Cod => "COD",
        },
        normalized_coupon,
        item_parts.join(";")
    );
    Ok(hex::encode(Sha256::digest(canonical.as_bytes())))
}

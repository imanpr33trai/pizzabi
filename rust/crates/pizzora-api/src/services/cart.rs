use std::collections::HashMap;
use std::sync::Arc;

use chrono::{DateTime, Utc};
use pizzora_core::cart_validation::CatalogProductToppingRef;
use pizzora_core::{
    CartItemInput, CartValidation, CatalogCheeseRef, CatalogCrustRef, CatalogHalfAndHalfConfigRef,
    CatalogProductRef, CatalogRefLookup, CatalogSauceRef, CatalogStandardToppingRef,
    CatalogToppingRef, CatalogVariantRef, EvaluatedCartSummary, ParsedCartItem,
};
use pizzora_db::new_uuid_v4;
use sqlx::{PgConnection, PgPool, Row as _};
use uuid::Uuid;

use crate::dto::{
    CartCheeseViewDto, CartHalfAndHalfViewDto, CartItemInputDto, CartItemViewDto, CartResponseDto,
    CartSauceViewDto, CartStandardToppingViewDto, CartToppingViewDto, CartValidationIssueDto,
    ReplaceCartRequest, ValidateCartRequest,
};
use crate::error::{format_iso8601, now_iso8601, ApiError};
use crate::services::catalog::{parse_size_code, Phase8FeatureFlags};
use crate::storage::ObjectStorageClient;

pub fn to_core_cart_inputs(items: &[CartItemInputDto]) -> Vec<CartItemInput> {
    items
        .iter()
        .map(|i| CartItemInput {
            id: i.id.clone(),
            product_id: i.product_id.clone(),
            variant_id: i.variant_id.clone(),
            crust_id: i.crust_id.clone(),
            topping_ids: i.topping_ids.clone(),
            quantity: i.quantity,
            cheese_id: i.cheese_id.clone(),
            sauce_id: i.sauce_id.clone(),
            standard_topping_ids: i.standard_topping_ids.clone(),
            half_and_half_config_id: i.half_and_half_config_id.clone(),
            half_and_half_left_topping_ids: i.half_and_half_left_topping_ids.clone(),
            half_and_half_right_topping_ids: i.half_and_half_right_topping_ids.clone(),
        })
        .collect()
}

/// Reject Phase 8 modifier writes when the corresponding feature flag is disabled.
pub fn ensure_modifier_features_enabled(items: &[CartItemInputDto]) -> Result<(), ApiError> {
    let flags = Phase8FeatureFlags::from_env();
    for item in items {
        if !flags.cheese && item.cheese_id.is_some() {
            return Err(ApiError::validation(
                "FEATURE_DISABLED",
                "Cheese selection is currently disabled.",
            ));
        }
        if !flags.sauce && item.sauce_id.is_some() {
            return Err(ApiError::validation(
                "FEATURE_DISABLED",
                "Sauce selection is currently disabled.",
            ));
        }
        if !flags.standard_toppings && !item.standard_topping_ids.is_empty() {
            return Err(ApiError::validation(
                "FEATURE_DISABLED",
                "Standard topping selection is currently disabled.",
            ));
        }
        let has_half_and_half = item.half_and_half_config_id.is_some()
            || !item.half_and_half_left_topping_ids.is_empty()
            || !item.half_and_half_right_topping_ids.is_empty();
        if !flags.half_and_half && has_half_and_half {
            return Err(ApiError::validation(
                "FEATURE_DISABLED",
                "Half-and-half selection is currently disabled.",
            ));
        }
    }
    Ok(())
}

pub async fn load_catalog_ref_lookup(
    conn: &mut PgConnection,
    storage: &dyn ObjectStorageClient,
) -> Result<CatalogRefLookup, ApiError> {
    let img_rows = sqlx::query(
        r#"
        SELECT product_id, object_key
        FROM (
            SELECT product_id, object_key,
                   ROW_NUMBER() OVER (PARTITION BY product_id ORDER BY sort_order ASC, created_at ASC) AS rn
            FROM product_images
            WHERE status = 'ACTIVE'
        ) ranked
        WHERE rn = 1
        "#,
    )
    .fetch_all(&mut *conn)
    .await?;

    let mut image_url_by_product: HashMap<Uuid, String> = HashMap::new();
    for r in img_rows {
        let pid: Uuid = r.try_get("product_id")?;
        let key: String = r.try_get("object_key")?;
        if let Some(url) = storage.resolve_public_url(&key) {
            if !url.trim().is_empty() {
                image_url_by_product.insert(pid, url);
            }
        }
    }

    let prod_rows = sqlx::query(
        r#"
        SELECT p.id, p.name, p.is_veg, p.is_taxable, p.is_active AS product_active, c.is_active AS category_active
        FROM products p
        JOIN categories c ON c.id = p.category_id
        "#,
    )
    .fetch_all(&mut *conn)
    .await?;
    let mut products = HashMap::with_capacity(prod_rows.len());
    for r in prod_rows {
        let id: Uuid = r.try_get("id")?;
        let p_act: bool = r.try_get("product_active")?;
        let c_act: bool = r.try_get("category_active")?;
        products.insert(
            id,
            CatalogProductRef {
                id,
                name: r.try_get("name")?,
                is_veg: r.try_get("is_veg")?,
                is_taxable: r.try_get("is_taxable")?,
                is_effective_active: p_act && c_act,
                image_url: image_url_by_product.remove(&id),
            },
        );
    }

    let var_rows = sqlx::query(
        "SELECT id, product_id, size_code, price_paise, is_active FROM product_variants",
    )
    .fetch_all(&mut *conn)
    .await?;
    let mut variants = HashMap::with_capacity(var_rows.len());
    for r in var_rows {
        let id: Uuid = r.try_get("id")?;
        let size_raw: String = r.try_get("size_code")?;
        variants.insert(
            id,
            CatalogVariantRef {
                id,
                product_id: r.try_get("product_id")?,
                size_code: parse_size_code(&size_raw)?,
                price_paise: r.try_get("price_paise")?,
                is_active: r.try_get("is_active")?,
            },
        );
    }

    let crust_rows =
        sqlx::query("SELECT id, name, extra_price_paise, is_active FROM crust_options")
            .fetch_all(&mut *conn)
            .await?;
    let mut crusts = HashMap::with_capacity(crust_rows.len());
    for r in crust_rows {
        let id: Uuid = r.try_get("id")?;
        crusts.insert(
            id,
            CatalogCrustRef {
                id,
                name: r.try_get("name")?,
                extra_price_paise: r.try_get("extra_price_paise")?,
                is_active: r.try_get("is_active")?,
            },
        );
    }

    let topping_rows =
        sqlx::query("SELECT id, name, is_veg, extra_price_paise, is_active FROM toppings")
            .fetch_all(&mut *conn)
            .await?;
    let mut toppings = HashMap::with_capacity(topping_rows.len());
    for r in topping_rows {
        let id: Uuid = r.try_get("id")?;
        toppings.insert(
            id,
            CatalogToppingRef {
                id,
                name: r.try_get("name")?,
                is_veg: r.try_get("is_veg")?,
                extra_price_paise: r.try_get("extra_price_paise")?,
                is_active: r.try_get("is_active")?,
            },
        );
    }

    Ok(CatalogRefLookup {
        products,
        variants,
        crusts,
        toppings,
        ..Default::default()
    })
}

/// Phase 8: Load extended catalog ref lookup with all modifiers
pub async fn load_extended_catalog_ref_lookup(
    conn: &mut PgConnection,
    storage: &dyn ObjectStorageClient,
) -> Result<CatalogRefLookup, ApiError> {
    let base = load_catalog_ref_lookup(conn, storage).await?;

    // Load cheeses
    let cheese_rows = sqlx::query(
        r#"
        SELECT id, product_id, name, extra_price_paise, is_active, sort_order
        FROM cheeses
        WHERE is_active = TRUE
        ORDER BY sort_order ASC, name ASC
        "#,
    )
    .fetch_all(&mut *conn)
    .await?;
    let mut cheeses = HashMap::with_capacity(cheese_rows.len());
    for r in cheese_rows {
        let id: Uuid = r.try_get("id")?;
        cheeses.insert(
            id,
            CatalogCheeseRef {
                id,
                product_id: r.try_get("product_id")?,
                name: r.try_get("name")?,
                extra_price_paise: r.try_get("extra_price_paise")?,
                is_active: r.try_get("is_active")?,
            },
        );
    }

    // Load sauces
    let sauce_rows = sqlx::query(
        r#"
        SELECT id, product_id, name, extra_price_paise, is_active, sort_order
        FROM sauces
        WHERE is_active = TRUE
        ORDER BY sort_order ASC, name ASC
        "#,
    )
    .fetch_all(&mut *conn)
    .await?;
    let mut sauces = HashMap::with_capacity(sauce_rows.len());
    for r in sauce_rows {
        let id: Uuid = r.try_get("id")?;
        sauces.insert(
            id,
            CatalogSauceRef {
                id,
                product_id: r.try_get("product_id")?,
                name: r.try_get("name")?,
                extra_price_paise: r.try_get("extra_price_paise")?,
                is_active: r.try_get("is_active")?,
            },
        );
    }

    // Load standard toppings
    let std_topping_rows = sqlx::query(
        r#"
        SELECT id, product_id, name, is_veg, extra_price_paise, is_default, is_active, sort_order
        FROM standard_toppings
        WHERE is_active = TRUE
        ORDER BY sort_order ASC, name ASC
        "#,
    )
    .fetch_all(&mut *conn)
    .await?;
    let mut standard_toppings = HashMap::with_capacity(std_topping_rows.len());
    for r in std_topping_rows {
        let id: Uuid = r.try_get("id")?;
        standard_toppings.insert(
            id,
            CatalogStandardToppingRef {
                id,
                product_id: r.try_get("product_id")?,
                name: r.try_get("name")?,
                is_veg: r.try_get("is_veg")?,
                extra_price_paise: r.try_get("extra_price_paise")?,
                is_default: r.try_get("is_default")?,
                is_active: r.try_get("is_active")?,
            },
        );
    }

    // Load half-and-half configs
    let hah_rows = sqlx::query(
        r#"
        SELECT id, product_id, name, left_topping_ids, right_topping_ids,
               half_and_half_surcharge_paise, is_active, sort_order
        FROM half_and_half_configs
        WHERE is_active = TRUE
        ORDER BY sort_order ASC, name ASC
        "#,
    )
    .fetch_all(&mut *conn)
    .await?;
    let mut half_and_half_configs = HashMap::with_capacity(hah_rows.len());
    for r in hah_rows {
        let id: Uuid = r.try_get("id")?;
        let left_ids: serde_json::Value = r.try_get("left_topping_ids")?;
        let right_ids: serde_json::Value = r.try_get("right_topping_ids")?;
        let left_topping_ids: Vec<Uuid> = serde_json::from_value(left_ids).unwrap_or_default();
        let right_topping_ids: Vec<Uuid> = serde_json::from_value(right_ids).unwrap_or_default();
        half_and_half_configs.insert(
            id,
            CatalogHalfAndHalfConfigRef {
                id,
                product_id: r.try_get("product_id")?,
                name: r.try_get("name")?,
                left_topping_ids,
                right_topping_ids,
                half_and_half_surcharge_paise: r.try_get("half_and_half_surcharge_paise")?,
                is_active: r.try_get("is_active")?,
            },
        );
    }

    // Load product_toppings for half-and-half subset validation
    let pt_rows = sqlx::query(
        "SELECT product_id, topping_id FROM product_toppings ORDER BY product_id, topping_id",
    )
    .fetch_all(&mut *conn)
    .await?;
    let mut product_toppings: HashMap<Uuid, Vec<CatalogProductToppingRef>> = HashMap::new();
    for r in pt_rows {
        let product_id: Uuid = r.try_get("product_id")?;
        let topping_id: Uuid = r.try_get("topping_id")?;
        product_toppings
            .entry(product_id)
            .or_default()
            .push(CatalogProductToppingRef {
                product_id,
                topping_id,
            });
    }

    Ok(CatalogRefLookup {
        products: base.products,
        variants: base.variants,
        crusts: base.crusts,
        toppings: base.toppings,
        cheeses,
        sauces,
        standard_toppings,
        half_and_half_configs,
        product_toppings,
    })
}

#[derive(Clone)]
pub struct CartService {
    pool: PgPool,
    storage: Arc<dyn ObjectStorageClient>,
}

impl CartService {
    pub fn new(pool: PgPool, storage: Arc<dyn ObjectStorageClient>) -> Self {
        Self { pool, storage }
    }

    pub async fn get_cart(&self, user_id: Uuid) -> Result<CartResponseDto, ApiError> {
        let mut conn = self.pool.acquire().await?;
        load_persisted_cart(&mut conn, user_id, &*self.storage).await
    }

    pub async fn replace_cart(
        &self,
        user_id: Uuid,
        request: &ReplaceCartRequest,
    ) -> Result<CartResponseDto, ApiError> {
        ensure_modifier_features_enabled(&request.items)?;
        let core_inputs = to_core_cart_inputs(&request.items);
        let parsed = CartValidation::validate_and_parse_inputs(&core_inputs)?;
        let merged = CartValidation::merge_duplicate_configurations(parsed);

        let mut tx = self.pool.begin().await?;
        let lookup = load_extended_catalog_ref_lookup(&mut tx, &*self.storage).await?;
        // Core validates base and Phase 8 modifier references against the full lookup.
        CartValidation::ensure_catalog_fk_references(&merged, &lookup)?;
        ensure_half_and_half_config_matches(&merged, &lookup)?;

        let existing_cart_id: Option<Uuid> =
            sqlx::query_scalar("SELECT id FROM carts WHERE user_id = $1 FOR UPDATE")
                .bind(user_id)
                .fetch_optional(&mut *tx)
                .await?;

        let cart_id = match existing_cart_id {
            Some(cid) => {
                sqlx::query("UPDATE carts SET updated_at = NOW() WHERE id = $1")
                    .bind(cid)
                    .execute(&mut *tx)
                    .await?;
                sqlx::query("DELETE FROM cart_items WHERE cart_id = $1")
                    .bind(cid)
                    .execute(&mut *tx)
                    .await?;
                cid
            }
            None => {
                let cid = new_uuid_v4();
                sqlx::query("INSERT INTO carts (id, user_id, updated_at) VALUES ($1, $2, NOW())")
                    .bind(cid)
                    .bind(user_id)
                    .execute(&mut *tx)
                    .await?;
                cid
            }
        };

        for item in &merged {
            let line_id = new_uuid_v4();
            // Phase 8: Insert with new modifier columns
            sqlx::query(
                r#"
                INSERT INTO cart_items (
                    id, cart_id, product_id, variant_id, crust_id, quantity,
                    cheese_id, sauce_id, standard_topping_ids,
                    half_and_half_config_id, half_and_half_left_topping_ids, half_and_half_right_topping_ids
                )
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
                "#,
            )
            .bind(line_id)
            .bind(cart_id)
            .bind(item.product_id)
            .bind(item.variant_id)
            .bind(item.crust_id)
            .bind(item.quantity)
            .bind(item.cheese_id)
            .bind(item.sauce_id)
            .bind(serde_json::to_value(&item.standard_topping_ids).unwrap_or(serde_json::Value::Array(vec![])))
            .bind(item.half_and_half_config_id)
            .bind(serde_json::to_value(&item.half_and_half_left_topping_ids).unwrap_or(serde_json::Value::Array(vec![])))
            .bind(serde_json::to_value(&item.half_and_half_right_topping_ids).unwrap_or(serde_json::Value::Array(vec![])))
            .execute(&mut *tx)
            .await?;

            // Insert extra toppings (existing cart_item_toppings table)
            for topping_id in &item.topping_ids {
                sqlx::query(
                    "INSERT INTO cart_item_toppings (cart_item_id, topping_id) VALUES ($1, $2)",
                )
                .bind(line_id)
                .bind(*topping_id)
                .execute(&mut *tx)
                .await?;
            }
        }

        let response = load_persisted_cart(&mut tx, user_id, &*self.storage).await?;
        tx.commit().await?;
        Ok(response)
    }

    pub async fn validate_cart(
        &self,
        request: &ValidateCartRequest,
    ) -> Result<CartResponseDto, ApiError> {
        ensure_modifier_features_enabled(&request.items)?;
        let core_inputs = to_core_cart_inputs(&request.items);
        let parsed = CartValidation::validate_and_parse_inputs(&core_inputs)?;
        let merged = CartValidation::merge_duplicate_configurations(parsed);

        let mut conn = self.pool.acquire().await?;
        let lookup = load_extended_catalog_ref_lookup(&mut conn, &*self.storage).await?;
        ensure_half_and_half_config_matches(&merged, &lookup)?;
        let summary = evaluate_cart_for_api(&merged, &lookup)?;

        Ok(CartResponseDto {
            cart_id: None,
            items: evaluated_summary_to_item_dtos(&summary, &lookup),
            subtotal_paise: summary.subtotal_paise,
            total_item_count: summary.total_item_count,
            all_available: summary.all_available,
            unavailable_count: summary.unavailable_count as i32,
            updated_at: now_iso8601(),
        })
    }
}

/// Validate that a half-and-half selection matches the configured halves.
///
/// Product ownership, active state, product_toppings membership, per-half limits
/// and left/right overlap are enforced by `CartValidation::ensure_catalog_fk_references`
/// in pizzora-core; this check preserves the API's exact-config requirement.
pub fn ensure_half_and_half_config_matches(
    items: &[ParsedCartItem],
    lookup: &CatalogRefLookup,
) -> Result<(), ApiError> {
    for item in items {
        let Some(config_id) = item.half_and_half_config_id else {
            continue;
        };
        // Missing/inactive configs are reported as availability issues by evaluation
        // (or rejected by core FK validation on writes).
        let Some(config) = lookup.half_and_half_configs.get(&config_id) else {
            continue;
        };
        if config.product_id != item.product_id {
            return Err(ApiError::validation(
                "HALF_AND_HALF_CONFIG_NOT_IN_PRODUCT",
                "Half-and-half config does not belong to this product.",
            ));
        }
        if !config.is_active {
            return Err(ApiError::validation(
                "HALF_AND_HALF_UNAVAILABLE",
                "Selected half-and-half config is currently unavailable.",
            ));
        }
        if item.half_and_half_left_topping_ids != config.left_topping_ids
            || item.half_and_half_right_topping_ids != config.right_topping_ids
        {
            return Err(ApiError::validation(
                "HALF_AND_HALF_TOPPING_MISMATCH",
                "Half-and-half topping IDs must match the selected config.",
            ));
        }
    }
    Ok(())
}

/// Evaluate a cart through the shared pricing/validation engine in pizzora-core.
pub fn evaluate_cart_for_api(
    items: &[ParsedCartItem],
    lookup: &CatalogRefLookup,
) -> Result<EvaluatedCartSummary, ApiError> {
    Ok(CartValidation::evaluate_cart(items, lookup)?)
}

async fn load_persisted_cart(
    conn: &mut PgConnection,
    user_id: Uuid,
    storage: &dyn ObjectStorageClient,
) -> Result<CartResponseDto, ApiError> {
    let Some(cart_row) = sqlx::query("SELECT id, updated_at FROM carts WHERE user_id = $1")
        .bind(user_id)
        .fetch_optional(&mut *conn)
        .await?
    else {
        return Ok(CartResponseDto {
            cart_id: None,
            items: Vec::new(),
            subtotal_paise: 0,
            total_item_count: 0,
            all_available: true,
            unavailable_count: 0,
            updated_at: now_iso8601(),
        });
    };

    let cart_id: Uuid = cart_row.try_get("id")?;
    let updated_at: DateTime<Utc> = cart_row.try_get("updated_at")?;

    // Phase 8: Read new cart_items columns
    let item_rows = sqlx::query(
        r#"
        SELECT id, product_id, variant_id, crust_id, quantity,
               cheese_id, sauce_id, standard_topping_ids,
               half_and_half_config_id, half_and_half_left_topping_ids, half_and_half_right_topping_ids
        FROM cart_items
        WHERE cart_id = $1
        ORDER BY created_at ASC, id ASC
        "#,
    )
    .bind(cart_id)
    .fetch_all(&mut *conn)
    .await?;

    let topping_rows = sqlx::query(
        r#"
        SELECT cit.cart_item_id, cit.topping_id
        FROM cart_item_toppings cit
        JOIN cart_items ci ON ci.id = cit.cart_item_id
        WHERE ci.cart_id = $1
        "#,
    )
    .bind(cart_id)
    .fetch_all(&mut *conn)
    .await?;

    let mut toppings_by_item: HashMap<Uuid, Vec<Uuid>> = HashMap::new();
    for tr in topping_rows {
        let item_id: Uuid = tr.try_get("cart_item_id")?;
        let tid: Uuid = tr.try_get("topping_id")?;
        toppings_by_item.entry(item_id).or_default().push(tid);
    }

    // Reconstruct core evaluation inputs, including Phase 8 modifier columns.
    let parsed: Vec<ParsedCartItem> = item_rows
        .into_iter()
        .map(|row| -> Result<ParsedCartItem, ApiError> {
            let line_id: Uuid = row.try_get("id")?;
            let mut topping_ids = toppings_by_item.remove(&line_id).unwrap_or_default();
            topping_ids.sort();

            let cheese_id: Option<Uuid> = row.try_get("cheese_id")?;
            let sauce_id: Option<Uuid> = row.try_get("sauce_id")?;
            let standard_topping_ids: serde_json::Value = row.try_get("standard_topping_ids")?;
            let half_and_half_config_id: Option<Uuid> = row.try_get("half_and_half_config_id")?;
            let half_and_half_left_topping_ids: serde_json::Value =
                row.try_get("half_and_half_left_topping_ids")?;
            let half_and_half_right_topping_ids: serde_json::Value =
                row.try_get("half_and_half_right_topping_ids")?;

            Ok(ParsedCartItem {
                item_id: Some(line_id.to_string()),
                product_id: row.try_get("product_id")?,
                variant_id: row.try_get("variant_id")?,
                crust_id: row.try_get("crust_id")?,
                topping_ids,
                quantity: row.try_get("quantity")?,
                cheese_id,
                sauce_id,
                standard_topping_ids: serde_json::from_value(standard_topping_ids)
                    .unwrap_or_default(),
                half_and_half_config_id,
                half_and_half_left_topping_ids: serde_json::from_value(
                    half_and_half_left_topping_ids,
                )
                .unwrap_or_default(),
                half_and_half_right_topping_ids: serde_json::from_value(
                    half_and_half_right_topping_ids,
                )
                .unwrap_or_default(),
            })
        })
        .collect::<Result<Vec<_>, _>>()?;

    let lookup = load_extended_catalog_ref_lookup(&mut *conn, storage).await?;
    let summary = evaluate_cart_for_api(&parsed, &lookup)?;

    Ok(CartResponseDto {
        cart_id: Some(cart_id.to_string()),
        items: evaluated_summary_to_item_dtos(&summary, &lookup),
        subtotal_paise: summary.subtotal_paise,
        total_item_count: summary.total_item_count,
        all_available: summary.all_available,
        unavailable_count: summary.unavailable_count as i32,
        updated_at: format_iso8601(updated_at),
    })
}

/// Map the shared core cart evaluation to the API view DTOs, including resolved
/// Phase 8 modifier names and prices.
pub fn evaluated_summary_to_item_dtos(
    summary: &EvaluatedCartSummary,
    lookup: &CatalogRefLookup,
) -> Vec<CartItemViewDto> {
    summary
        .items
        .iter()
        .map(|item| CartItemViewDto {
            id: item.item_id.clone(),
            product_id: item.product_id.clone(),
            product_name: item.product_name.clone(),
            is_veg: item.is_veg,
            image_url: item.image_url.clone(),
            variant_id: item.variant_id.clone(),
            size_code: item.size_code,
            base_price_paise: item.base_price_paise,
            crust_id: item.crust_id.clone(),
            crust_name: item.crust_name.clone(),
            crust_extra_price_paise: item.crust_extra_price_paise,
            toppings: item.toppings.iter().map(topping_view_to_dto).collect(),
            quantity: item.quantity,
            unit_price_paise: item.unit_price_paise,
            line_total_paise: item.line_total_paise,
            is_available: item.is_available,
            issues: item
                .issues
                .iter()
                .map(|iss| CartValidationIssueDto {
                    code: iss.code,
                    message: iss.message.clone(),
                })
                .collect(),
            cheese: item.cheese.as_ref().map(|c| CartCheeseViewDto {
                cheese_id: c.cheese_id.clone(),
                name: c.name.clone(),
                extra_price_paise: c.extra_price_paise,
                is_available: c.is_available,
            }),
            sauce: item.sauce.as_ref().map(|s| CartSauceViewDto {
                sauce_id: s.sauce_id.clone(),
                name: s.name.clone(),
                extra_price_paise: s.extra_price_paise,
                is_available: s.is_available,
            }),
            standard_toppings: item
                .standard_toppings
                .iter()
                .map(|t| CartStandardToppingViewDto {
                    standard_topping_id: t.standard_topping_id.clone(),
                    name: t.name.clone(),
                    is_veg: t.is_veg,
                    extra_price_paise: t.extra_price_paise,
                    is_default: t.is_default,
                    is_removed: t.is_removed,
                    is_available: t.is_available,
                })
                .collect(),
            half_and_half: item.half_and_half.as_ref().map(|h| CartHalfAndHalfViewDto {
                half_and_half_config_id: h.config_id.clone(),
                name: h.config_name.clone(),
                left_topping_ids: h.left_topping_ids.clone(),
                right_topping_ids: h.right_topping_ids.clone(),
                left_toppings: h
                    .left_topping_ids
                    .iter()
                    .map(|id| topping_view_from_lookup(id, lookup))
                    .collect(),
                right_toppings: h
                    .right_topping_ids
                    .iter()
                    .map(|id| topping_view_from_lookup(id, lookup))
                    .collect(),
                half_and_half_surcharge_paise: h.surcharge_paise,
                is_available: h.is_available,
            }),
        })
        .collect()
}

fn topping_view_to_dto(t: &pizzora_core::EvaluatedToppingView) -> CartToppingViewDto {
    CartToppingViewDto {
        topping_id: t.topping_id.clone(),
        name: t.name.clone(),
        is_veg: t.is_veg,
        extra_price_paise: t.extra_price_paise,
        is_available: t.is_available,
    }
}

fn topping_view_from_lookup(id: &str, lookup: &CatalogRefLookup) -> CartToppingViewDto {
    let resolved = Uuid::parse_str(id)
        .ok()
        .and_then(|topping_id| lookup.toppings.get(&topping_id));
    CartToppingViewDto {
        topping_id: id.to_owned(),
        name: resolved.map_or_else(|| "Unavailable Topping".to_owned(), |t| t.name.clone()),
        is_veg: resolved.is_none_or(|t| t.is_veg),
        extra_price_paise: resolved.map_or(0, |t| t.extra_price_paise),
        is_available: resolved.is_some_and(|t| t.is_active),
    }
}

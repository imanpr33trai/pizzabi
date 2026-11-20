use std::collections::{BTreeSet, HashMap};
use std::sync::Arc;

use chrono::{DateTime, Utc};
use pizzora_core::{
    compute_configuration_signature, evaluate_single_item, CartItemInput, CartToppingInput,
    CartValidation, CatalogCheeseRef, CatalogCrustRef, CatalogProductRef, CatalogRefLookup,
    CatalogSauceRef, CatalogSizeRuleRef, CatalogToppingRef, CatalogVariantRef, EvaluatedCartItem,
    EvaluatedCartSummary, ParsedCartItem, ParsedCartToppingSelection, ToppingPlacement,
};
use pizzora_db::new_uuid_v4;
use sqlx::{PgConnection, PgPool, Row as _};
use uuid::Uuid;

use crate::dto::{
    CartItemInputDto, CartItemViewDto, CartResponseDto, CartToppingViewDto, CartValidationIssueDto,
    PizzaBuilderQuoteRequest, PizzaBuilderQuoteResponse, ReplaceCartRequest, ValidateCartRequest,
};
use crate::error::{format_iso8601, now_iso8601, ApiError};
use crate::services::catalog::parse_size_code;
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
            extra_cheese: i.extra_cheese,
            sauce_id: i.sauce_id.clone(),
            extra_sauce: i.extra_sauce,
            is_half_and_half: i.is_half_and_half,
            right_product_id: i.right_product_id.clone(),
            right_variant_id: i.right_variant_id.clone(),
            right_sauce_id: i.right_sauce_id.clone(),
            removed_included_topping_ids: i.removed_included_topping_ids.clone(),
            topping_selections: i
                .topping_selections
                .iter()
                .map(|s| CartToppingInput {
                    topping_id: s.topping_id.clone(),
                    placement: s.placement,
                    quantity: s.quantity,
                })
                .collect(),
        })
        .collect()
}

pub fn evaluated_item_to_view_dto(item: &EvaluatedCartItem) -> CartItemViewDto {
    CartItemViewDto {
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
        toppings: item
            .toppings
            .iter()
            .map(|t| CartToppingViewDto {
                topping_id: t.topping_id.clone(),
                name: t.name.clone(),
                is_veg: t.is_veg,
                extra_price_paise: t.extra_price_paise,
                is_available: t.is_available,
                placement: t.placement,
                quantity: t.quantity,
                is_included: t.is_included,
                unit_price_paise: t.unit_price_paise,
                total_price_paise: t.total_price_paise,
            })
            .collect(),
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
        customization: Some(item.customization.clone()),
        price_breakdown: Some(item.price_breakdown.clone()),
    }
}

pub fn evaluated_summary_to_item_dtos(summary: &EvaluatedCartSummary) -> Vec<CartItemViewDto> {
    summary
        .items
        .iter()
        .map(evaluated_item_to_view_dto)
        .collect()
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

    let included_rows = sqlx::query(
        "SELECT product_id, topping_id FROM product_included_toppings ORDER BY topping_id ASC",
    )
    .fetch_all(&mut *conn)
    .await?;
    let mut included_by_product: HashMap<Uuid, Vec<Uuid>> = HashMap::new();
    for r in included_rows {
        let pid: Uuid = r.try_get("product_id")?;
        let tid: Uuid = r.try_get("topping_id")?;
        included_by_product.entry(pid).or_default().push(tid);
    }

    let prod_rows = sqlx::query(
        r#"
        SELECT p.id, p.name, p.is_veg, p.is_taxable,
               p.is_active AS product_active, c.is_active AS category_active,
               p.is_customizable, p.supports_half_and_half, p.default_cheese_id,
               p.default_sauce_id, p.max_toppings_override
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
                is_customizable: r.try_get("is_customizable")?,
                supports_half_and_half: r.try_get("supports_half_and_half")?,
                default_cheese_id: r.try_get("default_cheese_id")?,
                default_sauce_id: r.try_get("default_sauce_id")?,
                included_topping_ids: included_by_product.remove(&id).unwrap_or_default(),
                max_toppings_override: r.try_get("max_toppings_override")?,
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

    let crust_rows = sqlx::query(
        "SELECT id, name, extra_price_paise, is_active, supports_half_and_half FROM crust_options",
    )
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
                supports_half_and_half: r.try_get("supports_half_and_half")?,
            },
        );
    }

    let topping_rows = sqlx::query(
        "SELECT id, name, is_veg, extra_price_paise, is_active, max_quantity, supports_half_and_half FROM toppings",
    )
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
                max_quantity: r.try_get("max_quantity")?,
                supports_half_and_half: r.try_get("supports_half_and_half")?,
            },
        );
    }

    let size_rule_rows = sqlx::query(
        r#"
        SELECT size_code, display_name, max_toppings, max_extra_toppings,
               supports_half_and_half, ingredient_multiplier_bps, is_active
        FROM pizza_size_rules
        "#,
    )
    .fetch_all(&mut *conn)
    .await?;
    let mut size_rules = HashMap::with_capacity(size_rule_rows.len());
    for r in size_rule_rows {
        let size_raw: String = r.try_get("size_code")?;
        let size_code = parse_size_code(&size_raw)?;
        size_rules.insert(
            size_code,
            CatalogSizeRuleRef {
                size_code,
                display_name: r.try_get("display_name")?,
                max_toppings: r.try_get("max_toppings")?,
                max_extra_toppings: r.try_get("max_extra_toppings")?,
                supports_half_and_half: r.try_get("supports_half_and_half")?,
                ingredient_multiplier_bps: r.try_get("ingredient_multiplier_bps")?,
                is_active: r.try_get("is_active")?,
            },
        );
    }

    let cheese_rows = sqlx::query(
        "SELECT id, name, extra_price_paise, extra_cheese_price_paise, supports_extra, is_vegan, is_active FROM cheese_options",
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
                name: r.try_get("name")?,
                extra_price_paise: r.try_get("extra_price_paise")?,
                extra_cheese_price_paise: r.try_get("extra_cheese_price_paise")?,
                supports_extra: r.try_get("supports_extra")?,
                is_vegan: r.try_get("is_vegan")?,
                is_active: r.try_get("is_active")?,
            },
        );
    }

    let sauce_rows = sqlx::query(
        "SELECT id, name, extra_price_paise, extra_sauce_price_paise, supports_extra, is_vegan, is_active FROM sauce_options",
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
                name: r.try_get("name")?,
                extra_price_paise: r.try_get("extra_price_paise")?,
                extra_sauce_price_paise: r.try_get("extra_sauce_price_paise")?,
                supports_extra: r.try_get("supports_extra")?,
                is_vegan: r.try_get("is_vegan")?,
                is_active: r.try_get("is_active")?,
            },
        );
    }

    Ok(CatalogRefLookup {
        products,
        variants,
        crusts,
        toppings,
        size_rules,
        cheeses,
        sauces,
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
        let core_inputs = to_core_cart_inputs(&request.items);
        let parsed = CartValidation::validate_and_parse_inputs(&core_inputs)?;

        let mut tx = self.pool.begin().await?;
        let lookup = load_catalog_ref_lookup(&mut tx, &*self.storage).await?;
        let merged =
            CartValidation::merge_duplicate_configurations_with_lookup(parsed, Some(&lookup));
        CartValidation::ensure_catalog_fk_references(&merged, &lookup)?;

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
            let signature = compute_configuration_signature(item, Some(&lookup));
            let removed_strings: Vec<String> = item
                .removed_included_topping_ids
                .iter()
                .map(ToString::to_string)
                .collect();
            let removed_json =
                serde_json::to_value(&removed_strings).map_err(|_| ApiError::internal())?;
            sqlx::query(
                r#"
                INSERT INTO cart_items (
                    id, cart_id, product_id, variant_id, crust_id, quantity,
                    cheese_id, extra_cheese, sauce_id, extra_sauce, is_half_and_half,
                    right_product_id, right_variant_id, right_sauce_id,
                    removed_included_topping_ids, configuration_signature
                )
                VALUES (
                    $1, $2, $3, $4, $5, $6,
                    $7, $8, $9, $10, $11,
                    $12, $13, $14,
                    $15, $16
                )
                "#,
            )
            .bind(line_id)
            .bind(cart_id)
            .bind(item.product_id)
            .bind(item.variant_id)
            .bind(item.crust_id)
            .bind(item.quantity)
            .bind(item.cheese_id)
            .bind(item.extra_cheese)
            .bind(item.sauce_id)
            .bind(item.extra_sauce)
            .bind(item.is_half_and_half)
            .bind(item.right_product_id)
            .bind(item.right_variant_id)
            .bind(item.right_sauce_id)
            .bind(removed_json)
            .bind(signature)
            .execute(&mut *tx)
            .await?;

            for sel in &item.topping_selections {
                sqlx::query(
                    r#"
                    INSERT INTO cart_item_toppings (cart_item_id, topping_id, placement, quantity)
                    VALUES ($1, $2, $3, $4)
                    "#,
                )
                .bind(line_id)
                .bind(sel.topping_id)
                .bind(sel.placement.as_str())
                .bind(sel.quantity)
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
        let core_inputs = to_core_cart_inputs(&request.items);
        let parsed = CartValidation::validate_and_parse_inputs(&core_inputs)?;

        let mut conn = self.pool.acquire().await?;
        let lookup = load_catalog_ref_lookup(&mut conn, &*self.storage).await?;
        let merged =
            CartValidation::merge_duplicate_configurations_with_lookup(parsed, Some(&lookup));
        let summary = CartValidation::evaluate_cart(&merged, &lookup)?;

        Ok(CartResponseDto {
            cart_id: None,
            items: evaluated_summary_to_item_dtos(&summary),
            subtotal_paise: summary.subtotal_paise,
            total_item_count: summary.total_item_count,
            all_available: summary.all_available,
            unavailable_count: summary.unavailable_count as i32,
            updated_at: now_iso8601(),
        })
    }

    pub async fn quote_custom_pizza(
        &self,
        request: &PizzaBuilderQuoteRequest,
    ) -> Result<PizzaBuilderQuoteResponse, ApiError> {
        let core_inputs = to_core_cart_inputs(std::slice::from_ref(&request.item));
        let parsed = CartValidation::validate_and_parse_inputs(&core_inputs)?;

        let mut conn = self.pool.acquire().await?;
        let lookup = load_catalog_ref_lookup(&mut conn, &*self.storage).await?;
        let evaluated = evaluate_single_item(0, &parsed[0], &lookup)?;
        let view = evaluated_item_to_view_dto(&evaluated);

        Ok(PizzaBuilderQuoteResponse {
            is_valid: evaluated.is_available,
            unit_price_paise: evaluated.unit_price_paise,
            line_total_paise: evaluated.line_total_paise,
            price_breakdown: evaluated.price_breakdown,
            configuration_signature: evaluated.customization.configuration_signature.clone(),
            customization: evaluated.customization,
            toppings: view.toppings,
            issues: view.issues,
        })
    }
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

    let item_rows = sqlx::query(
        r#"
        SELECT id, product_id, variant_id, crust_id, quantity,
               cheese_id, extra_cheese, sauce_id, extra_sauce, is_half_and_half,
               right_product_id, right_variant_id, right_sauce_id,
               removed_included_topping_ids
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
        SELECT cit.cart_item_id, cit.topping_id, cit.placement, cit.quantity
        FROM cart_item_toppings cit
        JOIN cart_items ci ON ci.id = cit.cart_item_id
        WHERE ci.cart_id = $1
        "#,
    )
    .bind(cart_id)
    .fetch_all(&mut *conn)
    .await?;

    let mut toppings_by_item: HashMap<Uuid, Vec<ParsedCartToppingSelection>> = HashMap::new();
    for tr in topping_rows {
        let item_id: Uuid = tr.try_get("cart_item_id")?;
        let tid: Uuid = tr.try_get("topping_id")?;
        let placement_raw: String = tr.try_get("placement")?;
        let placement = ToppingPlacement::from_db_str(&placement_raw)?;
        let qty: i32 = tr.try_get("quantity")?;
        toppings_by_item
            .entry(item_id)
            .or_default()
            .push(ParsedCartToppingSelection {
                topping_id: tid,
                placement,
                quantity: qty.max(1),
            });
    }

    let parsed: Vec<ParsedCartItem> = item_rows
        .into_iter()
        .map(|row| -> Result<ParsedCartItem, ApiError> {
            let line_id: Uuid = row.try_get("id")?;
            let mut selections = toppings_by_item.remove(&line_id).unwrap_or_default();
            selections.sort_by(|a, b| {
                a.topping_id
                    .to_string()
                    .cmp(&b.topping_id.to_string())
                    .then_with(|| a.placement.as_str().cmp(b.placement.as_str()))
            });
            let unique_tids: BTreeSet<Uuid> = selections.iter().map(|s| s.topping_id).collect();
            let mut tids: Vec<Uuid> = unique_tids.into_iter().collect();
            tids.sort();

            let removed_json: serde_json::Value = row.try_get("removed_included_topping_ids")?;
            let removed_strs: Vec<String> =
                serde_json::from_value(removed_json).unwrap_or_default();
            let mut removed_ids = Vec::with_capacity(removed_strs.len());
            for rs in removed_strs {
                if let Ok(u) = Uuid::parse_str(rs.trim()) {
                    removed_ids.push(u);
                }
            }
            removed_ids.sort();
            removed_ids.dedup();

            Ok(ParsedCartItem {
                item_id: Some(line_id.to_string()),
                product_id: row.try_get("product_id")?,
                variant_id: row.try_get("variant_id")?,
                crust_id: row.try_get("crust_id")?,
                topping_ids: tids,
                quantity: row.try_get("quantity")?,
                cheese_id: row.try_get("cheese_id")?,
                extra_cheese: row.try_get("extra_cheese")?,
                sauce_id: row.try_get("sauce_id")?,
                extra_sauce: row.try_get("extra_sauce")?,
                is_half_and_half: row.try_get("is_half_and_half")?,
                right_product_id: row.try_get("right_product_id")?,
                right_variant_id: row.try_get("right_variant_id")?,
                right_sauce_id: row.try_get("right_sauce_id")?,
                removed_included_topping_ids: removed_ids,
                topping_selections: selections,
            })
        })
        .collect::<Result<Vec<_>, _>>()?;

    let lookup = load_catalog_ref_lookup(&mut *conn, storage).await?;
    let summary = CartValidation::evaluate_cart(&parsed, &lookup)?;

    Ok(CartResponseDto {
        cart_id: Some(cart_id.to_string()),
        items: evaluated_summary_to_item_dtos(&summary),
        subtotal_paise: summary.subtotal_paise,
        total_item_count: summary.total_item_count,
        all_available: summary.all_available,
        unavailable_count: summary.unavailable_count as i32,
        updated_at: format_iso8601(updated_at),
    })
}

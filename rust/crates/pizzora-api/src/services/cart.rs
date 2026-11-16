use std::collections::HashMap;
use std::sync::Arc;

use chrono::{DateTime, Utc};
use pizzora_core::{
    CartItemInput, CartValidation, CatalogCrustRef, CatalogProductRef, CatalogRefLookup,
    CatalogToppingRef, CatalogVariantRef, EvaluatedCartSummary, ParsedCartItem,
};
use pizzora_db::new_uuid_v4;
use sqlx::{PgConnection, PgPool, Row as _};
use uuid::Uuid;

use crate::dto::{
    CartItemInputDto, CartItemViewDto, CartResponseDto, CartToppingViewDto,
    CartValidationIssueDto, ReplaceCartRequest, ValidateCartRequest,
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
        })
        .collect()
}

pub fn evaluated_summary_to_item_dtos(summary: &EvaluatedCartSummary) -> Vec<CartItemViewDto> {
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
            toppings: item
                .toppings
                .iter()
                .map(|t| CartToppingViewDto {
                    topping_id: t.topping_id.clone(),
                    name: t.name.clone(),
                    is_veg: t.is_veg,
                    extra_price_paise: t.extra_price_paise,
                    is_available: t.is_available,
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
        })
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

    let var_rows =
        sqlx::query("SELECT id, product_id, size_code, price_paise, is_active FROM product_variants")
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
        let merged = CartValidation::merge_duplicate_configurations(parsed);

        let mut tx = self.pool.begin().await?;
        let lookup = load_catalog_ref_lookup(&mut tx, &*self.storage).await?;
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
            sqlx::query(
                r#"
                INSERT INTO cart_items (id, cart_id, product_id, variant_id, crust_id, quantity)
                VALUES ($1, $2, $3, $4, $5, $6)
                "#,
            )
            .bind(line_id)
            .bind(cart_id)
            .bind(item.product_id)
            .bind(item.variant_id)
            .bind(item.crust_id)
            .bind(item.quantity)
            .execute(&mut *tx)
            .await?;

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
        let core_inputs = to_core_cart_inputs(&request.items);
        let parsed = CartValidation::validate_and_parse_inputs(&core_inputs)?;
        let merged = CartValidation::merge_duplicate_configurations(parsed);

        let mut conn = self.pool.acquire().await?;
        let lookup = load_catalog_ref_lookup(&mut conn, &*self.storage).await?;
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
        SELECT id, product_id, variant_id, crust_id, quantity
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

    let parsed: Vec<ParsedCartItem> = item_rows
        .into_iter()
        .map(|row| -> Result<ParsedCartItem, ApiError> {
            let line_id: Uuid = row.try_get("id")?;
            let mut tids = toppings_by_item.remove(&line_id).unwrap_or_default();
            tids.sort();
            Ok(ParsedCartItem {
                item_id: Some(line_id.to_string()),
                product_id: row.try_get("product_id")?,
                variant_id: row.try_get("variant_id")?,
                crust_id: row.try_get("crust_id")?,
                topping_ids: tids,
                quantity: row.try_get("quantity")?,
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

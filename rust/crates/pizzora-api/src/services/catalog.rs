use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;

use chrono::{DateTime, Utc};
use pizzora_core::SizeCode;
use pizzora_db::{new_uuid_v4, record_audit_log, AuditContext, DbError, EphemeralCacheStore};
use sqlx::{PgPool, Row as _};
use uuid::Uuid;

use crate::dto::{
    CategoryDto, CheeseOptionDto, CreateCheeseRequest, CreateCrustRequest, CreateProductRequest,
    CreateSauceRequest, CreateToppingRequest, CreateVariantRequest, CrustOptionDto,
    PizzaSizeRuleDto, ProductDto, ProductImageDto, ProductImageStatus, ProductVariantDto,
    PublicMenuResponse, SauceOptionDto, ToppingDto, UpdateCheeseRequest, UpdateCrustRequest,
    UpdatePizzaSizeRuleRequest, UpdateProductRequest, UpdateSauceRequest, UpdateToppingRequest,
    UpdateVariantRequest,
};
use crate::error::{format_iso8601, ApiError};
use crate::extractors::parse_uuid_param;
use crate::services::catalog_cache::{invalidate_catalog_cache, CATALOG_TTL_SECS, MENU_CACHE_KEY};
use crate::storage::ObjectStorageClient;

pub fn size_code_as_str(size: SizeCode) -> &'static str {
    match size {
        SizeCode::Regular => "REGULAR",
        SizeCode::Medium => "MEDIUM",
        SizeCode::Large => "LARGE",
        SizeCode::ExtraLarge => "EXTRA_LARGE",
    }
}

pub fn parse_size_code(raw: &str) -> Result<SizeCode, ApiError> {
    match raw.trim().to_ascii_uppercase().as_str() {
        "REGULAR" => Ok(SizeCode::Regular),
        "MEDIUM" => Ok(SizeCode::Medium),
        "LARGE" => Ok(SizeCode::Large),
        "EXTRA_LARGE" => Ok(SizeCode::ExtraLarge),
        other => Err(ApiError::validation(
            "INVALID_SIZE_CODE",
            format!("Unsupported variant size code '{other}'."),
        )),
    }
}

pub fn parse_image_status(raw: &str) -> ProductImageStatus {
    match raw.trim().to_ascii_uppercase().as_str() {
        "ACTIVE" => ProductImageStatus::Active,
        "DELETED" => ProductImageStatus::Deleted,
        _ => ProductImageStatus::Pending,
    }
}

#[derive(Clone)]
pub struct CatalogService {
    pool: PgPool,
    cache: Arc<dyn EphemeralCacheStore>,
    storage: Arc<dyn ObjectStorageClient>,
}

impl CatalogService {
    pub fn new(
        pool: PgPool,
        cache: Arc<dyn EphemeralCacheStore>,
        storage: Arc<dyn ObjectStorageClient>,
    ) -> Self {
        Self {
            pool,
            cache,
            storage,
        }
    }

    pub async fn get_public_menu(&self) -> Result<PublicMenuResponse, ApiError> {
        if let Some(cached) = self.cache.get(MENU_CACHE_KEY).await {
            if let Ok(parsed) = serde_json::from_str::<PublicMenuResponse>(&cached) {
                return Ok(parsed);
            }
        }

        let categories = self.load_categories(true).await?;
        let products = self.load_products(true).await?;
        let crusts = self.load_crusts(true).await?;
        let toppings = self.load_toppings(true).await?;
        let size_rules = self.load_size_rules(true).await?;
        let cheeses = self.load_cheeses(true).await?;
        let sauces = self.load_sauces(true).await?;
        let (store_name, is_accepting_orders, gst_bps) = self.load_store_summary().await?;

        let response = PublicMenuResponse {
            categories,
            products,
            crusts,
            toppings,
            gst_bps,
            store_name,
            is_accepting_orders,
            size_rules,
            cheeses,
            sauces,
        };

        if let Ok(encoded) = serde_json::to_string(&response) {
            self.cache
                .set_ex(MENU_CACHE_KEY, CATALOG_TTL_SECS, &encoded)
                .await;
        }
        Ok(response)
    }

    pub async fn list_all_products(&self) -> Result<Vec<ProductDto>, ApiError> {
        self.load_products(false).await
    }

    pub async fn create_product(
        &self,
        request: &CreateProductRequest,
        audit: &AuditContext,
    ) -> Result<ProductDto, ApiError> {
        let name = request.name.trim();
        if name.is_empty() || name.len() > 120 {
            return Err(ApiError::validation(
                "INVALID_PRODUCT_NAME",
                "Product name must be 1..120 characters.",
            ));
        }
        let category_id = parse_uuid_param(&request.category_id, "category_id")?;
        if request.variants.is_empty() {
            return Err(ApiError::validation(
                "MISSING_VARIANTS",
                "A product must define at least one size variant.",
            ));
        }
        if let Some(max_ov) = request.max_toppings_override {
            if max_ov < 0 {
                return Err(ApiError::validation(
                    "INVALID_TOPPING_LIMIT",
                    "max_toppings_override must be non-negative.",
                ));
            }
        }
        let default_cheese_id = request
            .default_cheese_id
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(|s| parse_uuid_param(s, "default_cheese_id"))
            .transpose()?;
        let default_sauce_id = request
            .default_sauce_id
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(|s| parse_uuid_param(s, "default_sauce_id"))
            .transpose()?;
        let mut included_topping_ids = Vec::with_capacity(request.included_topping_ids.len());
        let mut seen_included = std::collections::HashSet::new();
        for tid_raw in &request.included_topping_ids {
            let tid = parse_uuid_param(tid_raw, "included_topping_id")?;
            if seen_included.insert(tid) {
                included_topping_ids.push(tid);
            }
        }

        let mut seen_sizes = std::collections::HashSet::new();
        for v in &request.variants {
            if v.price_paise < 0 {
                return Err(ApiError::validation(
                    "INVALID_PRICE",
                    "Variant price_paise must be non-negative.",
                ));
            }
            if !seen_sizes.insert(size_code_as_str(v.size_code)) {
                return Err(ApiError::validation(
                    "DUPLICATE_VARIANT_SIZE",
                    "Duplicate variant size_code in product creation request.",
                ));
            }
        }

        let product_id = new_uuid_v4();
        let mut tx = self.pool.begin().await?;
        let cat_exists: Option<i32> = sqlx::query_scalar("SELECT 1 FROM categories WHERE id = $1")
            .bind(category_id)
            .fetch_optional(&mut *tx)
            .await?;
        if cat_exists.is_none() {
            return Err(ApiError::not_found(
                "CATEGORY_NOT_FOUND",
                format!("Category {category_id} was not found."),
            ));
        }
        if let Some(cid) = default_cheese_id {
            let exists: Option<i32> =
                sqlx::query_scalar("SELECT 1 FROM cheese_options WHERE id = $1")
                    .bind(cid)
                    .fetch_optional(&mut *tx)
                    .await?;
            if exists.is_none() {
                return Err(ApiError::not_found(
                    "CHEESE_NOT_FOUND",
                    format!("Cheese option {cid} was not found."),
                ));
            }
        }
        if let Some(sid) = default_sauce_id {
            let exists: Option<i32> =
                sqlx::query_scalar("SELECT 1 FROM sauce_options WHERE id = $1")
                    .bind(sid)
                    .fetch_optional(&mut *tx)
                    .await?;
            if exists.is_none() {
                return Err(ApiError::not_found(
                    "SAUCE_NOT_FOUND",
                    format!("Sauce option {sid} was not found."),
                ));
            }
        }
        for tid in &included_topping_ids {
            let exists: Option<i32> = sqlx::query_scalar("SELECT 1 FROM toppings WHERE id = $1")
                .bind(*tid)
                .fetch_optional(&mut *tx)
                .await?;
            if exists.is_none() {
                return Err(ApiError::not_found(
                    "TOPPING_NOT_FOUND",
                    format!("Included topping {tid} was not found."),
                ));
            }
        }

        let description = request.description.trim();
        sqlx::query(
            r#"
            INSERT INTO products (
                id, category_id, name, description, is_veg, is_active, is_taxable,
                is_customizable, supports_half_and_half, default_cheese_id, default_sauce_id,
                max_toppings_override
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
            "#,
        )
        .bind(product_id)
        .bind(category_id)
        .bind(name)
        .bind(description)
        .bind(request.is_veg)
        .bind(request.is_active)
        .bind(request.is_taxable)
        .bind(request.is_customizable)
        .bind(request.supports_half_and_half)
        .bind(default_cheese_id)
        .bind(default_sauce_id)
        .bind(request.max_toppings_override)
        .execute(&mut *tx)
        .await?;

        for tid in &included_topping_ids {
            sqlx::query(
                "INSERT INTO product_included_toppings (product_id, topping_id) VALUES ($1, $2) ON CONFLICT DO NOTHING",
            )
            .bind(product_id)
            .bind(*tid)
            .execute(&mut *tx)
            .await?;
        }

        for v in &request.variants {
            sqlx::query(
                r#"
                INSERT INTO product_variants (id, product_id, size_code, price_paise, is_active)
                VALUES ($1, $2, $3, $4, $5)
                "#,
            )
            .bind(new_uuid_v4())
            .bind(product_id)
            .bind(size_code_as_str(v.size_code))
            .bind(v.price_paise)
            .bind(v.is_active)
            .execute(&mut *tx)
            .await?;
        }

        let created = self
            .load_single_product_in_tx(&mut tx, product_id)
            .await?
            .ok_or_else(ApiError::internal)?;

        let after_val = serde_json::to_value(&created).ok();
        let entity_id = product_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "PRODUCT_CREATED",
            "product",
            &entity_id,
            None,
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(created)
    }

    pub async fn update_product(
        &self,
        product_id: Uuid,
        request: &UpdateProductRequest,
        audit: &AuditContext,
    ) -> Result<ProductDto, ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing = self
            .load_single_product_in_tx(&mut tx, product_id)
            .await?
            .ok_or_else(|| {
                ApiError::not_found(
                    "PRODUCT_NOT_FOUND",
                    format!("Product {product_id} was not found."),
                )
            })?;

        let next_category_id = match request.category_id.as_deref() {
            Some(raw) => {
                let cid = parse_uuid_param(raw, "category_id")?;
                let exists: Option<i32> =
                    sqlx::query_scalar("SELECT 1 FROM categories WHERE id = $1")
                        .bind(cid)
                        .fetch_optional(&mut *tx)
                        .await?;
                if exists.is_none() {
                    return Err(ApiError::not_found(
                        "CATEGORY_NOT_FOUND",
                        format!("Category {cid} was not found."),
                    ));
                }
                cid
            }
            None => parse_uuid_param(&existing.category_id, "category_id")?,
        };
        let next_name = match request.name.as_deref() {
            Some(raw) => {
                let trimmed = raw.trim();
                if trimmed.is_empty() || trimmed.len() > 120 {
                    return Err(ApiError::validation(
                        "INVALID_PRODUCT_NAME",
                        "Product name must be 1..120 characters.",
                    ));
                }
                trimmed.to_owned()
            }
            None => existing.name.clone(),
        };
        let next_desc = request
            .description
            .as_deref()
            .map(|s| s.trim().to_owned())
            .unwrap_or_else(|| existing.description.clone());
        let next_veg = request.is_veg.unwrap_or(existing.is_veg);
        let next_active = request.is_active.unwrap_or(existing.is_active);
        let next_taxable = request.is_taxable.unwrap_or(existing.is_taxable);
        let next_customizable = request.is_customizable.unwrap_or(existing.is_customizable);
        let next_supports_half = request
            .supports_half_and_half
            .unwrap_or(existing.supports_half_and_half);
        let next_default_cheese_id = match request.default_cheese_id.as_deref() {
            Some(raw) if raw.trim().is_empty() => None,
            Some(raw) => {
                let cid = parse_uuid_param(raw, "default_cheese_id")?;
                let exists: Option<i32> =
                    sqlx::query_scalar("SELECT 1 FROM cheese_options WHERE id = $1")
                        .bind(cid)
                        .fetch_optional(&mut *tx)
                        .await?;
                if exists.is_none() {
                    return Err(ApiError::not_found(
                        "CHEESE_NOT_FOUND",
                        format!("Cheese option {cid} was not found."),
                    ));
                }
                Some(cid)
            }
            None => existing
                .default_cheese_id
                .as_deref()
                .map(|s| parse_uuid_param(s, "default_cheese_id"))
                .transpose()?,
        };
        let next_default_sauce_id = match request.default_sauce_id.as_deref() {
            Some(raw) if raw.trim().is_empty() => None,
            Some(raw) => {
                let sid = parse_uuid_param(raw, "default_sauce_id")?;
                let exists: Option<i32> =
                    sqlx::query_scalar("SELECT 1 FROM sauce_options WHERE id = $1")
                        .bind(sid)
                        .fetch_optional(&mut *tx)
                        .await?;
                if exists.is_none() {
                    return Err(ApiError::not_found(
                        "SAUCE_NOT_FOUND",
                        format!("Sauce option {sid} was not found."),
                    ));
                }
                Some(sid)
            }
            None => existing
                .default_sauce_id
                .as_deref()
                .map(|s| parse_uuid_param(s, "default_sauce_id"))
                .transpose()?,
        };
        let next_max_toppings_override = match request.max_toppings_override {
            Some(v) if v < 0 => {
                return Err(ApiError::validation(
                    "INVALID_TOPPING_LIMIT",
                    "max_toppings_override must be non-negative.",
                ));
            }
            Some(v) => Some(v),
            None => existing.max_toppings_override,
        };

        sqlx::query(
            r#"
            UPDATE products
            SET category_id = $1, name = $2, description = $3,
                is_veg = $4, is_active = $5, is_taxable = $6,
                is_customizable = $7, supports_half_and_half = $8,
                default_cheese_id = $9, default_sauce_id = $10,
                max_toppings_override = $11, updated_at = NOW()
            WHERE id = $12
            "#,
        )
        .bind(next_category_id)
        .bind(&next_name)
        .bind(&next_desc)
        .bind(next_veg)
        .bind(next_active)
        .bind(next_taxable)
        .bind(next_customizable)
        .bind(next_supports_half)
        .bind(next_default_cheese_id)
        .bind(next_default_sauce_id)
        .bind(next_max_toppings_override)
        .bind(product_id)
        .execute(&mut *tx)
        .await?;

        if let Some(ref raw_included) = request.included_topping_ids {
            let mut next_included = Vec::with_capacity(raw_included.len());
            let mut seen = std::collections::HashSet::new();
            for tid_raw in raw_included {
                let tid = parse_uuid_param(tid_raw, "included_topping_id")?;
                if seen.insert(tid) {
                    let exists: Option<i32> =
                        sqlx::query_scalar("SELECT 1 FROM toppings WHERE id = $1")
                            .bind(tid)
                            .fetch_optional(&mut *tx)
                            .await?;
                    if exists.is_none() {
                        return Err(ApiError::not_found(
                            "TOPPING_NOT_FOUND",
                            format!("Included topping {tid} was not found."),
                        ));
                    }
                    next_included.push(tid);
                }
            }
            sqlx::query("DELETE FROM product_included_toppings WHERE product_id = $1")
                .bind(product_id)
                .execute(&mut *tx)
                .await?;
            for tid in next_included {
                sqlx::query(
                    "INSERT INTO product_included_toppings (product_id, topping_id) VALUES ($1, $2) ON CONFLICT DO NOTHING",
                )
                .bind(product_id)
                .bind(tid)
                .execute(&mut *tx)
                .await?;
            }
        }

        let updated = self
            .load_single_product_in_tx(&mut tx, product_id)
            .await?
            .ok_or_else(ApiError::internal)?;

        let before_val = serde_json::to_value(&existing).ok();
        let after_val = serde_json::to_value(&updated).ok();
        let entity_id = product_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "PRODUCT_UPDATED",
            "product",
            &entity_id,
            before_val.as_ref(),
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(updated)
    }

    pub async fn delete_product(
        &self,
        product_id: Uuid,
        audit: &AuditContext,
    ) -> Result<(), ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing = self
            .load_single_product_in_tx(&mut tx, product_id)
            .await?
            .ok_or_else(|| {
                ApiError::not_found(
                    "PRODUCT_NOT_FOUND",
                    format!("Product {product_id} was not found."),
                )
            })?;

        let ref_count: i64 = sqlx::query_scalar(
            r#"
            SELECT (SELECT COUNT(*) FROM cart_items WHERE product_id = $1 OR right_product_id = $1)
                 + (SELECT COUNT(*) FROM order_items WHERE product_id = $1)
            "#,
        )
        .bind(product_id)
        .fetch_one(&mut *tx)
        .await?;

        let action = if ref_count > 0 {
            sqlx::query("UPDATE products SET is_active = FALSE, updated_at = NOW() WHERE id = $1")
                .bind(product_id)
                .execute(&mut *tx)
                .await?;
            "PRODUCT_DEACTIVATED"
        } else {
            sqlx::query("DELETE FROM products WHERE id = $1")
                .bind(product_id)
                .execute(&mut *tx)
                .await?;
            "PRODUCT_DELETED"
        };

        let before_val = serde_json::to_value(&existing).ok();
        let entity_id = product_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            action,
            "product",
            &entity_id,
            before_val.as_ref(),
            None,
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(())
    }

    pub async fn add_variant(
        &self,
        product_id: Uuid,
        request: &CreateVariantRequest,
        audit: &AuditContext,
    ) -> Result<ProductVariantDto, ApiError> {
        if request.price_paise < 0 {
            return Err(ApiError::validation(
                "INVALID_PRICE",
                "Variant price_paise must be non-negative.",
            ));
        }

        let variant_id = new_uuid_v4();
        let mut tx = self.pool.begin().await?;
        let exists: Option<i32> = sqlx::query_scalar("SELECT 1 FROM products WHERE id = $1")
            .bind(product_id)
            .fetch_optional(&mut *tx)
            .await?;
        if exists.is_none() {
            return Err(ApiError::not_found(
                "PRODUCT_NOT_FOUND",
                format!("Product {product_id} was not found."),
            ));
        }

        let row = sqlx::query(
            r#"
            INSERT INTO product_variants (id, product_id, size_code, price_paise, is_active)
            VALUES ($1, $2, $3, $4, $5)
            RETURNING id, product_id, size_code, price_paise, is_active
            "#,
        )
        .bind(variant_id)
        .bind(product_id)
        .bind(size_code_as_str(request.size_code))
        .bind(request.price_paise)
        .bind(request.is_active)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                ApiError::conflict(
                    "VARIANT_EXISTS",
                    "Variant size already exists for this product.",
                )
            } else {
                ApiError::from(DbError::Sqlx(err))
            }
        })?;

        let dto = row_to_variant(&row)?;
        let after_val = serde_json::to_value(&dto).ok();
        let entity_id = variant_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "VARIANT_CREATED",
            "product_variant",
            &entity_id,
            None,
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(dto)
    }

    pub async fn update_variant(
        &self,
        variant_id: Uuid,
        request: &UpdateVariantRequest,
        audit: &AuditContext,
    ) -> Result<ProductVariantDto, ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing_row = sqlx::query(
            "SELECT id, product_id, size_code, price_paise, is_active FROM product_variants WHERE id = $1 FOR UPDATE",
        )
        .bind(variant_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "VARIANT_NOT_FOUND",
                format!("Product variant {variant_id} was not found."),
            )
        })?;
        let existing = row_to_variant(&existing_row)?;

        let next_price = match request.price_paise {
            Some(p) if p < 0 => {
                return Err(ApiError::validation(
                    "INVALID_PRICE",
                    "Variant price_paise must be non-negative.",
                ));
            }
            Some(p) => p,
            None => existing.price_paise,
        };
        let next_active = request.is_active.unwrap_or(existing.is_active);

        let updated_row = sqlx::query(
            r#"
            UPDATE product_variants
            SET price_paise = $1, is_active = $2
            WHERE id = $3
            RETURNING id, product_id, size_code, price_paise, is_active
            "#,
        )
        .bind(next_price)
        .bind(next_active)
        .bind(variant_id)
        .fetch_one(&mut *tx)
        .await?;

        let updated = row_to_variant(&updated_row)?;
        let before_val = serde_json::to_value(&existing).ok();
        let after_val = serde_json::to_value(&updated).ok();
        let entity_id = variant_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "VARIANT_UPDATED",
            "product_variant",
            &entity_id,
            before_val.as_ref(),
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(updated)
    }

    pub async fn list_all_crusts(&self) -> Result<Vec<CrustOptionDto>, ApiError> {
        self.load_crusts(false).await
    }

    pub async fn create_crust(
        &self,
        request: &CreateCrustRequest,
        audit: &AuditContext,
    ) -> Result<CrustOptionDto, ApiError> {
        let name = request.name.trim();
        if name.is_empty() || name.len() > 80 {
            return Err(ApiError::validation(
                "INVALID_CRUST_NAME",
                "Crust name must be 1..80 characters.",
            ));
        }
        if request.extra_price_paise < 0 {
            return Err(ApiError::validation(
                "INVALID_PRICE",
                "extra_price_paise must be non-negative.",
            ));
        }

        let crust_id = new_uuid_v4();
        let mut tx = self.pool.begin().await?;
        let row = sqlx::query(
            r#"
            INSERT INTO crust_options (id, name, extra_price_paise, is_active, supports_half_and_half)
            VALUES ($1, $2, $3, $4, $5)
            RETURNING id, name, extra_price_paise, is_active, supports_half_and_half
            "#,
        )
        .bind(crust_id)
        .bind(name)
        .bind(request.extra_price_paise)
        .bind(request.is_active)
        .bind(request.supports_half_and_half)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                ApiError::conflict("CRUST_EXISTS", format!("Crust '{name}' already exists."))
            } else {
                ApiError::from(DbError::Sqlx(err))
            }
        })?;

        let dto = row_to_crust(&row)?;
        let after_val = serde_json::to_value(&dto).ok();
        let entity_id = crust_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "CRUST_CREATED",
            "crust_option",
            &entity_id,
            None,
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(dto)
    }

    pub async fn update_crust(
        &self,
        crust_id: Uuid,
        request: &UpdateCrustRequest,
        audit: &AuditContext,
    ) -> Result<CrustOptionDto, ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing_row = sqlx::query(
            "SELECT id, name, extra_price_paise, is_active, supports_half_and_half FROM crust_options WHERE id = $1 FOR UPDATE",
        )
        .bind(crust_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "CRUST_NOT_FOUND",
                format!("Crust option {crust_id} was not found."),
            )
        })?;
        let existing = row_to_crust(&existing_row)?;

        let next_name = match request.name.as_deref() {
            Some(raw) => {
                let trimmed = raw.trim();
                if trimmed.is_empty() || trimmed.len() > 80 {
                    return Err(ApiError::validation(
                        "INVALID_CRUST_NAME",
                        "Crust name must be 1..80 characters.",
                    ));
                }
                trimmed.to_owned()
            }
            None => existing.name.clone(),
        };
        let next_extra = match request.extra_price_paise {
            Some(p) if p < 0 => {
                return Err(ApiError::validation(
                    "INVALID_PRICE",
                    "extra_price_paise must be non-negative.",
                ));
            }
            Some(p) => p,
            None => existing.extra_price_paise,
        };
        let next_active = request.is_active.unwrap_or(existing.is_active);
        let next_half = request
            .supports_half_and_half
            .unwrap_or(existing.supports_half_and_half);

        let updated_row = sqlx::query(
            r#"
            UPDATE crust_options
            SET name = $1, extra_price_paise = $2, is_active = $3, supports_half_and_half = $4
            WHERE id = $5
            RETURNING id, name, extra_price_paise, is_active, supports_half_and_half
            "#,
        )
        .bind(&next_name)
        .bind(next_extra)
        .bind(next_active)
        .bind(next_half)
        .bind(crust_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                ApiError::conflict(
                    "CRUST_EXISTS",
                    format!("Crust '{next_name}' already exists."),
                )
            } else {
                ApiError::from(DbError::Sqlx(err))
            }
        })?;

        let updated = row_to_crust(&updated_row)?;
        let before_val = serde_json::to_value(&existing).ok();
        let after_val = serde_json::to_value(&updated).ok();
        let entity_id = crust_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "CRUST_UPDATED",
            "crust_option",
            &entity_id,
            before_val.as_ref(),
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(updated)
    }

    pub async fn list_all_toppings(&self) -> Result<Vec<ToppingDto>, ApiError> {
        self.load_toppings(false).await
    }

    pub async fn create_topping(
        &self,
        request: &CreateToppingRequest,
        audit: &AuditContext,
    ) -> Result<ToppingDto, ApiError> {
        let name = request.name.trim();
        if name.is_empty() || name.len() > 80 {
            return Err(ApiError::validation(
                "INVALID_TOPPING_NAME",
                "Topping name must be 1..80 characters.",
            ));
        }
        if request.extra_price_paise < 0 {
            return Err(ApiError::validation(
                "INVALID_PRICE",
                "extra_price_paise must be non-negative.",
            ));
        }
        if request.max_quantity < 1 {
            return Err(ApiError::validation(
                "INVALID_QUANTITY",
                "max_quantity must be at least 1.",
            ));
        }

        let topping_id = new_uuid_v4();
        let mut tx = self.pool.begin().await?;
        let row = sqlx::query(
            r#"
            INSERT INTO toppings (id, name, is_veg, extra_price_paise, is_active, max_quantity, supports_half_and_half)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            RETURNING id, name, is_veg, extra_price_paise, is_active, max_quantity, supports_half_and_half
            "#,
        )
        .bind(topping_id)
        .bind(name)
        .bind(request.is_veg)
        .bind(request.extra_price_paise)
        .bind(request.is_active)
        .bind(request.max_quantity)
        .bind(request.supports_half_and_half)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                ApiError::conflict(
                    "TOPPING_EXISTS",
                    format!("Topping '{name}' already exists."),
                )
            } else {
                ApiError::from(DbError::Sqlx(err))
            }
        })?;

        let dto = row_to_topping(&row)?;
        let after_val = serde_json::to_value(&dto).ok();
        let entity_id = topping_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "TOPPING_CREATED",
            "topping",
            &entity_id,
            None,
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(dto)
    }

    pub async fn update_topping(
        &self,
        topping_id: Uuid,
        request: &UpdateToppingRequest,
        audit: &AuditContext,
    ) -> Result<ToppingDto, ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing_row = sqlx::query(
            "SELECT id, name, is_veg, extra_price_paise, is_active, max_quantity, supports_half_and_half FROM toppings WHERE id = $1 FOR UPDATE",
        )
        .bind(topping_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "TOPPING_NOT_FOUND",
                format!("Topping {topping_id} was not found."),
            )
        })?;
        let existing = row_to_topping(&existing_row)?;

        let next_name = match request.name.as_deref() {
            Some(raw) => {
                let trimmed = raw.trim();
                if trimmed.is_empty() || trimmed.len() > 80 {
                    return Err(ApiError::validation(
                        "INVALID_TOPPING_NAME",
                        "Topping name must be 1..80 characters.",
                    ));
                }
                trimmed.to_owned()
            }
            None => existing.name.clone(),
        };
        let next_veg = request.is_veg.unwrap_or(existing.is_veg);
        let next_extra = match request.extra_price_paise {
            Some(p) if p < 0 => {
                return Err(ApiError::validation(
                    "INVALID_PRICE",
                    "extra_price_paise must be non-negative.",
                ));
            }
            Some(p) => p,
            None => existing.extra_price_paise,
        };
        let next_active = request.is_active.unwrap_or(existing.is_active);
        let next_max_qty = match request.max_quantity {
            Some(q) if q < 1 => {
                return Err(ApiError::validation(
                    "INVALID_QUANTITY",
                    "max_quantity must be at least 1.",
                ));
            }
            Some(q) => q,
            None => existing.max_quantity,
        };
        let next_half = request
            .supports_half_and_half
            .unwrap_or(existing.supports_half_and_half);

        let updated_row = sqlx::query(
            r#"
            UPDATE toppings
            SET name = $1, is_veg = $2, extra_price_paise = $3, is_active = $4,
                max_quantity = $5, supports_half_and_half = $6
            WHERE id = $7
            RETURNING id, name, is_veg, extra_price_paise, is_active, max_quantity, supports_half_and_half
            "#,
        )
        .bind(&next_name)
        .bind(next_veg)
        .bind(next_extra)
        .bind(next_active)
        .bind(next_max_qty)
        .bind(next_half)
        .bind(topping_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                ApiError::conflict(
                    "TOPPING_EXISTS",
                    format!("Topping '{next_name}' already exists."),
                )
            } else {
                ApiError::from(DbError::Sqlx(err))
            }
        })?;

        let updated = row_to_topping(&updated_row)?;
        let before_val = serde_json::to_value(&existing).ok();
        let after_val = serde_json::to_value(&updated).ok();
        let entity_id = topping_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "TOPPING_UPDATED",
            "topping",
            &entity_id,
            before_val.as_ref(),
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(updated)
    }

    pub async fn list_all_cheeses(&self) -> Result<Vec<CheeseOptionDto>, ApiError> {
        self.load_cheeses(false).await
    }

    pub async fn create_cheese(
        &self,
        request: &CreateCheeseRequest,
        audit: &AuditContext,
    ) -> Result<CheeseOptionDto, ApiError> {
        let name = request.name.trim();
        if name.is_empty() || name.len() > 80 {
            return Err(ApiError::validation(
                "INVALID_CHEESE_NAME",
                "Cheese name must be 1..80 characters.",
            ));
        }
        if request.extra_price_paise < 0 || request.extra_cheese_price_paise < 0 {
            return Err(ApiError::validation(
                "INVALID_PRICE",
                "Cheese prices must be non-negative.",
            ));
        }

        let cheese_id = new_uuid_v4();
        let mut tx = self.pool.begin().await?;
        let row = sqlx::query(
            r#"
            INSERT INTO cheese_options (
                id, name, extra_price_paise, extra_cheese_price_paise, supports_extra, is_vegan, is_active
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            RETURNING id, name, extra_price_paise, extra_cheese_price_paise, supports_extra, is_vegan, is_active
            "#,
        )
        .bind(cheese_id)
        .bind(name)
        .bind(request.extra_price_paise)
        .bind(request.extra_cheese_price_paise)
        .bind(request.supports_extra)
        .bind(request.is_vegan)
        .bind(request.is_active)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                ApiError::conflict("CHEESE_EXISTS", format!("Cheese '{name}' already exists."))
            } else {
                ApiError::from(DbError::Sqlx(err))
            }
        })?;

        let dto = row_to_cheese(&row)?;
        let after_val = serde_json::to_value(&dto).ok();
        let entity_id = cheese_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "CHEESE_CREATED",
            "cheese_option",
            &entity_id,
            None,
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(dto)
    }

    pub async fn update_cheese(
        &self,
        cheese_id: Uuid,
        request: &UpdateCheeseRequest,
        audit: &AuditContext,
    ) -> Result<CheeseOptionDto, ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing_row = sqlx::query(
            "SELECT id, name, extra_price_paise, extra_cheese_price_paise, supports_extra, is_vegan, is_active FROM cheese_options WHERE id = $1 FOR UPDATE",
        )
        .bind(cheese_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "CHEESE_NOT_FOUND",
                format!("Cheese option {cheese_id} was not found."),
            )
        })?;
        let existing = row_to_cheese(&existing_row)?;

        let next_name = match request.name.as_deref() {
            Some(raw) => {
                let trimmed = raw.trim();
                if trimmed.is_empty() || trimmed.len() > 80 {
                    return Err(ApiError::validation(
                        "INVALID_CHEESE_NAME",
                        "Cheese name must be 1..80 characters.",
                    ));
                }
                trimmed.to_owned()
            }
            None => existing.name.clone(),
        };
        let next_extra = match request.extra_price_paise {
            Some(p) if p < 0 => {
                return Err(ApiError::validation(
                    "INVALID_PRICE",
                    "extra_price_paise must be non-negative.",
                ));
            }
            Some(p) => p,
            None => existing.extra_price_paise,
        };
        let next_extra_cheese = match request.extra_cheese_price_paise {
            Some(p) if p < 0 => {
                return Err(ApiError::validation(
                    "INVALID_PRICE",
                    "extra_cheese_price_paise must be non-negative.",
                ));
            }
            Some(p) => p,
            None => existing.extra_cheese_price_paise,
        };
        let next_supports_extra = request.supports_extra.unwrap_or(existing.supports_extra);
        let next_vegan = request.is_vegan.unwrap_or(existing.is_vegan);
        let next_active = request.is_active.unwrap_or(existing.is_active);

        let updated_row = sqlx::query(
            r#"
            UPDATE cheese_options
            SET name = $1, extra_price_paise = $2, extra_cheese_price_paise = $3,
                supports_extra = $4, is_vegan = $5, is_active = $6
            WHERE id = $7
            RETURNING id, name, extra_price_paise, extra_cheese_price_paise, supports_extra, is_vegan, is_active
            "#,
        )
        .bind(&next_name)
        .bind(next_extra)
        .bind(next_extra_cheese)
        .bind(next_supports_extra)
        .bind(next_vegan)
        .bind(next_active)
        .bind(cheese_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                ApiError::conflict(
                    "CHEESE_EXISTS",
                    format!("Cheese '{next_name}' already exists."),
                )
            } else {
                ApiError::from(DbError::Sqlx(err))
            }
        })?;

        let updated = row_to_cheese(&updated_row)?;
        let before_val = serde_json::to_value(&existing).ok();
        let after_val = serde_json::to_value(&updated).ok();
        let entity_id = cheese_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "CHEESE_UPDATED",
            "cheese_option",
            &entity_id,
            before_val.as_ref(),
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(updated)
    }

    pub async fn list_all_sauces(&self) -> Result<Vec<SauceOptionDto>, ApiError> {
        self.load_sauces(false).await
    }

    pub async fn create_sauce(
        &self,
        request: &CreateSauceRequest,
        audit: &AuditContext,
    ) -> Result<SauceOptionDto, ApiError> {
        let name = request.name.trim();
        if name.is_empty() || name.len() > 80 {
            return Err(ApiError::validation(
                "INVALID_SAUCE_NAME",
                "Sauce name must be 1..80 characters.",
            ));
        }
        if request.extra_price_paise < 0 || request.extra_sauce_price_paise < 0 {
            return Err(ApiError::validation(
                "INVALID_PRICE",
                "Sauce prices must be non-negative.",
            ));
        }

        let sauce_id = new_uuid_v4();
        let mut tx = self.pool.begin().await?;
        let row = sqlx::query(
            r#"
            INSERT INTO sauce_options (
                id, name, extra_price_paise, extra_sauce_price_paise, supports_extra, is_vegan, is_active
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            RETURNING id, name, extra_price_paise, extra_sauce_price_paise, supports_extra, is_vegan, is_active
            "#,
        )
        .bind(sauce_id)
        .bind(name)
        .bind(request.extra_price_paise)
        .bind(request.extra_sauce_price_paise)
        .bind(request.supports_extra)
        .bind(request.is_vegan)
        .bind(request.is_active)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                ApiError::conflict("SAUCE_EXISTS", format!("Sauce '{name}' already exists."))
            } else {
                ApiError::from(DbError::Sqlx(err))
            }
        })?;

        let dto = row_to_sauce(&row)?;
        let after_val = serde_json::to_value(&dto).ok();
        let entity_id = sauce_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "SAUCE_CREATED",
            "sauce_option",
            &entity_id,
            None,
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(dto)
    }

    pub async fn update_sauce(
        &self,
        sauce_id: Uuid,
        request: &UpdateSauceRequest,
        audit: &AuditContext,
    ) -> Result<SauceOptionDto, ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing_row = sqlx::query(
            "SELECT id, name, extra_price_paise, extra_sauce_price_paise, supports_extra, is_vegan, is_active FROM sauce_options WHERE id = $1 FOR UPDATE",
        )
        .bind(sauce_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "SAUCE_NOT_FOUND",
                format!("Sauce option {sauce_id} was not found."),
            )
        })?;
        let existing = row_to_sauce(&existing_row)?;

        let next_name = match request.name.as_deref() {
            Some(raw) => {
                let trimmed = raw.trim();
                if trimmed.is_empty() || trimmed.len() > 80 {
                    return Err(ApiError::validation(
                        "INVALID_SAUCE_NAME",
                        "Sauce name must be 1..80 characters.",
                    ));
                }
                trimmed.to_owned()
            }
            None => existing.name.clone(),
        };
        let next_extra = match request.extra_price_paise {
            Some(p) if p < 0 => {
                return Err(ApiError::validation(
                    "INVALID_PRICE",
                    "extra_price_paise must be non-negative.",
                ));
            }
            Some(p) => p,
            None => existing.extra_price_paise,
        };
        let next_extra_sauce = match request.extra_sauce_price_paise {
            Some(p) if p < 0 => {
                return Err(ApiError::validation(
                    "INVALID_PRICE",
                    "extra_sauce_price_paise must be non-negative.",
                ));
            }
            Some(p) => p,
            None => existing.extra_sauce_price_paise,
        };
        let next_supports_extra = request.supports_extra.unwrap_or(existing.supports_extra);
        let next_vegan = request.is_vegan.unwrap_or(existing.is_vegan);
        let next_active = request.is_active.unwrap_or(existing.is_active);

        let updated_row = sqlx::query(
            r#"
            UPDATE sauce_options
            SET name = $1, extra_price_paise = $2, extra_sauce_price_paise = $3,
                supports_extra = $4, is_vegan = $5, is_active = $6
            WHERE id = $7
            RETURNING id, name, extra_price_paise, extra_sauce_price_paise, supports_extra, is_vegan, is_active
            "#,
        )
        .bind(&next_name)
        .bind(next_extra)
        .bind(next_extra_sauce)
        .bind(next_supports_extra)
        .bind(next_vegan)
        .bind(next_active)
        .bind(sauce_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                ApiError::conflict(
                    "SAUCE_EXISTS",
                    format!("Sauce '{next_name}' already exists."),
                )
            } else {
                ApiError::from(DbError::Sqlx(err))
            }
        })?;

        let updated = row_to_sauce(&updated_row)?;
        let before_val = serde_json::to_value(&existing).ok();
        let after_val = serde_json::to_value(&updated).ok();
        let entity_id = sauce_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "SAUCE_UPDATED",
            "sauce_option",
            &entity_id,
            before_val.as_ref(),
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(updated)
    }

    pub async fn list_all_size_rules(&self) -> Result<Vec<PizzaSizeRuleDto>, ApiError> {
        self.load_size_rules(false).await
    }

    pub async fn update_size_rule(
        &self,
        size_code: SizeCode,
        request: &UpdatePizzaSizeRuleRequest,
        audit: &AuditContext,
    ) -> Result<PizzaSizeRuleDto, ApiError> {
        let code_str = size_code_as_str(size_code);
        let mut tx = self.pool.begin().await?;
        let existing_row = sqlx::query(
            r#"
            SELECT size_code, display_name, max_toppings, max_extra_toppings,
                   supports_half_and_half, ingredient_multiplier_bps, is_active
            FROM pizza_size_rules
            WHERE size_code = $1
            FOR UPDATE
            "#,
        )
        .bind(code_str)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "SIZE_RULE_NOT_FOUND",
                format!("Size rule '{code_str}' was not found."),
            )
        })?;
        let existing = row_to_size_rule(&existing_row)?;

        let next_display = match request.display_name.as_deref() {
            Some(raw) => {
                let trimmed = raw.trim();
                if trimmed.is_empty() || trimmed.len() > 60 {
                    return Err(ApiError::validation(
                        "INVALID_SIZE_DISPLAY_NAME",
                        "Size display_name must be 1..60 characters.",
                    ));
                }
                trimmed.to_owned()
            }
            None => existing.display_name.clone(),
        };
        let next_max_toppings = match request.max_toppings {
            Some(v) if v < 0 => {
                return Err(ApiError::validation(
                    "INVALID_TOPPING_LIMIT",
                    "max_toppings must be non-negative.",
                ));
            }
            Some(v) => v,
            None => existing.max_toppings,
        };
        let next_max_extra = match request.max_extra_toppings {
            Some(v) if v < 0 => {
                return Err(ApiError::validation(
                    "INVALID_TOPPING_LIMIT",
                    "max_extra_toppings must be non-negative.",
                ));
            }
            Some(v) => v,
            None => existing.max_extra_toppings,
        };
        let next_half = request
            .supports_half_and_half
            .unwrap_or(existing.supports_half_and_half);
        let next_multiplier = match request.ingredient_multiplier_bps {
            Some(v) if v <= 0 => {
                return Err(ApiError::validation(
                    "INVALID_MULTIPLIER",
                    "ingredient_multiplier_bps must be positive.",
                ));
            }
            Some(v) => v,
            None => existing.ingredient_multiplier_bps,
        };
        let next_active = request.is_active.unwrap_or(existing.is_active);

        let updated_row = sqlx::query(
            r#"
            UPDATE pizza_size_rules
            SET display_name = $1, max_toppings = $2, max_extra_toppings = $3,
                supports_half_and_half = $4, ingredient_multiplier_bps = $5, is_active = $6
            WHERE size_code = $7
            RETURNING size_code, display_name, max_toppings, max_extra_toppings,
                      supports_half_and_half, ingredient_multiplier_bps, is_active
            "#,
        )
        .bind(&next_display)
        .bind(next_max_toppings)
        .bind(next_max_extra)
        .bind(next_half)
        .bind(next_multiplier)
        .bind(next_active)
        .bind(code_str)
        .fetch_one(&mut *tx)
        .await?;

        let updated = row_to_size_rule(&updated_row)?;
        let before_val = serde_json::to_value(&existing).ok();
        let after_val = serde_json::to_value(&updated).ok();
        record_audit_log(
            &mut tx,
            audit,
            "SIZE_RULE_UPDATED",
            "pizza_size_rule",
            code_str,
            before_val.as_ref(),
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(updated)
    }

    async fn load_categories(&self, active_only: bool) -> Result<Vec<CategoryDto>, ApiError> {
        let rows = if active_only {
            sqlx::query(
                "SELECT id, name, sort_order, is_active, created_at FROM categories WHERE is_active = TRUE ORDER BY sort_order ASC, name ASC",
            )
            .fetch_all(&self.pool)
            .await?
        } else {
            sqlx::query(
                "SELECT id, name, sort_order, is_active, created_at FROM categories ORDER BY sort_order ASC, name ASC",
            )
            .fetch_all(&self.pool)
            .await?
        };
        rows.iter()
            .map(|row| {
                let id: Uuid = row.try_get("id")?;
                let created_at: DateTime<Utc> = row.try_get("created_at")?;
                Ok(CategoryDto {
                    id: id.to_string(),
                    name: row.try_get("name")?,
                    sort_order: row.try_get("sort_order")?,
                    is_active: row.try_get("is_active")?,
                    created_at: format_iso8601(created_at),
                })
            })
            .collect()
    }

    async fn load_products(&self, active_only: bool) -> Result<Vec<ProductDto>, ApiError> {
        let prod_rows = if active_only {
            sqlx::query(
                r#"
                SELECT p.id, p.category_id, c.name AS category_name, p.name, p.description,
                       p.is_veg, p.is_active, p.is_taxable,
                       p.is_customizable, p.supports_half_and_half, p.default_cheese_id,
                       p.default_sauce_id, p.max_toppings_override
                FROM products p
                JOIN categories c ON c.id = p.category_id
                WHERE p.is_active = TRUE AND c.is_active = TRUE
                ORDER BY c.sort_order ASC, p.name ASC
                "#,
            )
            .fetch_all(&self.pool)
            .await?
        } else {
            sqlx::query(
                r#"
                SELECT p.id, p.category_id, c.name AS category_name, p.name, p.description,
                       p.is_veg, p.is_active, p.is_taxable,
                       p.is_customizable, p.supports_half_and_half, p.default_cheese_id,
                       p.default_sauce_id, p.max_toppings_override
                FROM products p
                JOIN categories c ON c.id = p.category_id
                ORDER BY c.sort_order ASC, p.name ASC
                "#,
            )
            .fetch_all(&self.pool)
            .await?
        };

        let var_rows = if active_only {
            sqlx::query(
                r#"
                SELECT id, product_id, size_code, price_paise, is_active
                FROM product_variants
                WHERE is_active = TRUE
                ORDER BY CASE size_code WHEN 'REGULAR' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LARGE' THEN 3 WHEN 'EXTRA_LARGE' THEN 4 ELSE 5 END ASC
                "#,
            )
            .fetch_all(&self.pool)
            .await?
        } else {
            sqlx::query(
                r#"
                SELECT id, product_id, size_code, price_paise, is_active
                FROM product_variants
                ORDER BY CASE size_code WHEN 'REGULAR' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LARGE' THEN 3 WHEN 'EXTRA_LARGE' THEN 4 ELSE 5 END ASC
                "#,
            )
            .fetch_all(&self.pool)
            .await?
        };

        let mut variants_by_prod: HashMap<Uuid, Vec<ProductVariantDto>> = HashMap::new();
        for vr in var_rows {
            let dto = row_to_variant(&vr)?;
            let pid: Uuid = vr.try_get("product_id")?;
            variants_by_prod.entry(pid).or_default().push(dto);
        }

        let included_rows = sqlx::query(
            "SELECT product_id, topping_id FROM product_included_toppings ORDER BY topping_id ASC",
        )
        .fetch_all(&self.pool)
        .await?;
        let mut included_by_prod: HashMap<Uuid, Vec<String>> = HashMap::new();
        for ir in included_rows {
            let pid: Uuid = ir.try_get("product_id")?;
            let tid: Uuid = ir.try_get("topping_id")?;
            included_by_prod
                .entry(pid)
                .or_default()
                .push(tid.to_string());
        }

        let img_rows = if active_only {
            sqlx::query(
                r#"
                SELECT id, product_id, object_key, mime_type, size_bytes, status, sort_order, created_at
                FROM product_images
                WHERE status = 'ACTIVE'
                ORDER BY sort_order ASC, created_at ASC
                "#,
            )
            .fetch_all(&self.pool)
            .await?
        } else {
            sqlx::query(
                r#"
                SELECT id, product_id, object_key, mime_type, size_bytes, status, sort_order, created_at
                FROM product_images
                WHERE status <> 'DELETED'
                ORDER BY sort_order ASC, created_at ASC
                "#,
            )
            .fetch_all(&self.pool)
            .await?
        };

        let mut images_by_prod: BTreeMap<Uuid, Vec<ProductImageDto>> = BTreeMap::new();
        for ir in img_rows {
            let object_key: String = ir.try_get("object_key")?;
            let Some(public_url) = self.storage.resolve_public_url(&object_key) else {
                continue;
            };
            if public_url.trim().is_empty() {
                continue;
            }
            let id: Uuid = ir.try_get("id")?;
            let product_id: Uuid = ir.try_get("product_id")?;
            let status_raw: String = ir.try_get("status")?;
            let created_at: DateTime<Utc> = ir.try_get("created_at")?;
            images_by_prod
                .entry(product_id)
                .or_default()
                .push(ProductImageDto {
                    id: id.to_string(),
                    product_id: product_id.to_string(),
                    object_key,
                    public_url,
                    mime_type: ir.try_get("mime_type")?,
                    size_bytes: ir.try_get("size_bytes")?,
                    status: parse_image_status(&status_raw),
                    sort_order: ir.try_get("sort_order")?,
                    created_at: format_iso8601(created_at),
                });
        }

        let mut products = Vec::with_capacity(prod_rows.len());
        for pr in prod_rows {
            let pid: Uuid = pr.try_get("id")?;
            let cid: Uuid = pr.try_get("category_id")?;
            let default_cheese_id: Option<Uuid> = pr.try_get("default_cheese_id")?;
            let default_sauce_id: Option<Uuid> = pr.try_get("default_sauce_id")?;
            products.push(ProductDto {
                id: pid.to_string(),
                category_id: cid.to_string(),
                category_name: pr.try_get("category_name")?,
                name: pr.try_get("name")?,
                description: pr.try_get("description")?,
                is_veg: pr.try_get("is_veg")?,
                is_active: pr.try_get("is_active")?,
                is_taxable: pr.try_get("is_taxable")?,
                variants: variants_by_prod.remove(&pid).unwrap_or_default(),
                images: images_by_prod.remove(&pid).unwrap_or_default(),
                is_customizable: pr.try_get("is_customizable")?,
                supports_half_and_half: pr.try_get("supports_half_and_half")?,
                default_cheese_id: default_cheese_id.map(|u| u.to_string()),
                default_sauce_id: default_sauce_id.map(|u| u.to_string()),
                included_topping_ids: included_by_prod.remove(&pid).unwrap_or_default(),
                max_toppings_override: pr.try_get("max_toppings_override")?,
            });
        }

        Ok(products)
    }

    async fn load_single_product_in_tx(
        &self,
        tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        product_id: Uuid,
    ) -> Result<Option<ProductDto>, ApiError> {
        let Some(pr) = sqlx::query(
            r#"
            SELECT p.id, p.category_id, c.name AS category_name, p.name, p.description,
                   p.is_veg, p.is_active, p.is_taxable,
                   p.is_customizable, p.supports_half_and_half, p.default_cheese_id,
                   p.default_sauce_id, p.max_toppings_override
            FROM products p
            JOIN categories c ON c.id = p.category_id
            WHERE p.id = $1
            "#,
        )
        .bind(product_id)
        .fetch_optional(&mut **tx)
        .await?
        else {
            return Ok(None);
        };

        let var_rows = sqlx::query(
            r#"
            SELECT id, product_id, size_code, price_paise, is_active
            FROM product_variants
            WHERE product_id = $1
            ORDER BY CASE size_code WHEN 'REGULAR' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LARGE' THEN 3 WHEN 'EXTRA_LARGE' THEN 4 ELSE 5 END ASC
            "#,
        )
        .bind(product_id)
        .fetch_all(&mut **tx)
        .await?;
        let variants = var_rows
            .iter()
            .map(row_to_variant)
            .collect::<Result<Vec<_>, _>>()?;

        let included_rows = sqlx::query(
            "SELECT topping_id FROM product_included_toppings WHERE product_id = $1 ORDER BY topping_id ASC",
        )
        .bind(product_id)
        .fetch_all(&mut **tx)
        .await?;
        let mut included_topping_ids = Vec::with_capacity(included_rows.len());
        for ir in included_rows {
            let tid: Uuid = ir.try_get("topping_id")?;
            included_topping_ids.push(tid.to_string());
        }

        let img_rows = sqlx::query(
            r#"
            SELECT id, product_id, object_key, mime_type, size_bytes, status, sort_order, created_at
            FROM product_images
            WHERE product_id = $1 AND status <> 'DELETED'
            ORDER BY sort_order ASC, created_at ASC
            "#,
        )
        .bind(product_id)
        .fetch_all(&mut **tx)
        .await?;
        let mut images = Vec::new();
        for ir in img_rows {
            let object_key: String = ir.try_get("object_key")?;
            let Some(public_url) = self.storage.resolve_public_url(&object_key) else {
                continue;
            };
            if public_url.trim().is_empty() {
                continue;
            }
            let id: Uuid = ir.try_get("id")?;
            let status_raw: String = ir.try_get("status")?;
            let created_at: DateTime<Utc> = ir.try_get("created_at")?;
            images.push(ProductImageDto {
                id: id.to_string(),
                product_id: product_id.to_string(),
                object_key,
                public_url,
                mime_type: ir.try_get("mime_type")?,
                size_bytes: ir.try_get("size_bytes")?,
                status: parse_image_status(&status_raw),
                sort_order: ir.try_get("sort_order")?,
                created_at: format_iso8601(created_at),
            });
        }

        let cid: Uuid = pr.try_get("category_id")?;
        let default_cheese_id: Option<Uuid> = pr.try_get("default_cheese_id")?;
        let default_sauce_id: Option<Uuid> = pr.try_get("default_sauce_id")?;
        Ok(Some(ProductDto {
            id: product_id.to_string(),
            category_id: cid.to_string(),
            category_name: pr.try_get("category_name")?,
            name: pr.try_get("name")?,
            description: pr.try_get("description")?,
            is_veg: pr.try_get("is_veg")?,
            is_active: pr.try_get("is_active")?,
            is_taxable: pr.try_get("is_taxable")?,
            variants,
            images,
            is_customizable: pr.try_get("is_customizable")?,
            supports_half_and_half: pr.try_get("supports_half_and_half")?,
            default_cheese_id: default_cheese_id.map(|u| u.to_string()),
            default_sauce_id: default_sauce_id.map(|u| u.to_string()),
            included_topping_ids,
            max_toppings_override: pr.try_get("max_toppings_override")?,
        }))
    }

    async fn load_crusts(&self, active_only: bool) -> Result<Vec<CrustOptionDto>, ApiError> {
        let rows = if active_only {
            sqlx::query("SELECT id, name, extra_price_paise, is_active, supports_half_and_half FROM crust_options WHERE is_active = TRUE ORDER BY extra_price_paise ASC, name ASC")
                .fetch_all(&self.pool)
                .await?
        } else {
            sqlx::query("SELECT id, name, extra_price_paise, is_active, supports_half_and_half FROM crust_options ORDER BY extra_price_paise ASC, name ASC")
                .fetch_all(&self.pool)
                .await?
        };
        rows.iter().map(row_to_crust).collect()
    }

    async fn load_toppings(&self, active_only: bool) -> Result<Vec<ToppingDto>, ApiError> {
        let rows = if active_only {
            sqlx::query("SELECT id, name, is_veg, extra_price_paise, is_active, max_quantity, supports_half_and_half FROM toppings WHERE is_active = TRUE ORDER BY name ASC")
                .fetch_all(&self.pool)
                .await?
        } else {
            sqlx::query("SELECT id, name, is_veg, extra_price_paise, is_active, max_quantity, supports_half_and_half FROM toppings ORDER BY name ASC")
                .fetch_all(&self.pool)
                .await?
        };
        rows.iter().map(row_to_topping).collect()
    }

    async fn load_cheeses(&self, active_only: bool) -> Result<Vec<CheeseOptionDto>, ApiError> {
        let rows = if active_only {
            sqlx::query("SELECT id, name, extra_price_paise, extra_cheese_price_paise, supports_extra, is_vegan, is_active FROM cheese_options WHERE is_active = TRUE ORDER BY extra_price_paise ASC, name ASC")
                .fetch_all(&self.pool)
                .await?
        } else {
            sqlx::query("SELECT id, name, extra_price_paise, extra_cheese_price_paise, supports_extra, is_vegan, is_active FROM cheese_options ORDER BY extra_price_paise ASC, name ASC")
                .fetch_all(&self.pool)
                .await?
        };
        rows.iter().map(row_to_cheese).collect()
    }

    async fn load_sauces(&self, active_only: bool) -> Result<Vec<SauceOptionDto>, ApiError> {
        let rows = if active_only {
            sqlx::query("SELECT id, name, extra_price_paise, extra_sauce_price_paise, supports_extra, is_vegan, is_active FROM sauce_options WHERE is_active = TRUE ORDER BY extra_price_paise ASC, name ASC")
                .fetch_all(&self.pool)
                .await?
        } else {
            sqlx::query("SELECT id, name, extra_price_paise, extra_sauce_price_paise, supports_extra, is_vegan, is_active FROM sauce_options ORDER BY extra_price_paise ASC, name ASC")
                .fetch_all(&self.pool)
                .await?
        };
        rows.iter().map(row_to_sauce).collect()
    }

    async fn load_size_rules(&self, active_only: bool) -> Result<Vec<PizzaSizeRuleDto>, ApiError> {
        let rows = if active_only {
            sqlx::query(
                r#"
                SELECT size_code, display_name, max_toppings, max_extra_toppings,
                       supports_half_and_half, ingredient_multiplier_bps, is_active
                FROM pizza_size_rules
                WHERE is_active = TRUE
                ORDER BY CASE size_code WHEN 'REGULAR' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LARGE' THEN 3 WHEN 'EXTRA_LARGE' THEN 4 ELSE 5 END ASC
                "#,
            )
            .fetch_all(&self.pool)
            .await?
        } else {
            sqlx::query(
                r#"
                SELECT size_code, display_name, max_toppings, max_extra_toppings,
                       supports_half_and_half, ingredient_multiplier_bps, is_active
                FROM pizza_size_rules
                ORDER BY CASE size_code WHEN 'REGULAR' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LARGE' THEN 3 WHEN 'EXTRA_LARGE' THEN 4 ELSE 5 END ASC
                "#,
            )
            .fetch_all(&self.pool)
            .await?
        };
        rows.iter().map(row_to_size_rule).collect()
    }

    async fn load_store_summary(&self) -> Result<(String, bool, i32), ApiError> {
        let row = sqlx::query(
            "SELECT store_name, is_accepting_orders, gst_bps FROM restaurant_settings WHERE id = 1",
        )
        .fetch_optional(&self.pool)
        .await?;
        match row {
            Some(r) => Ok((
                r.try_get("store_name")?,
                r.try_get("is_accepting_orders")?,
                r.try_get("gst_bps")?,
            )),
            None => Ok(("PIZZORA".to_owned(), true, 500)),
        }
    }
}

fn row_to_variant(row: &sqlx::postgres::PgRow) -> Result<ProductVariantDto, ApiError> {
    let id: Uuid = row.try_get("id")?;
    let product_id: Uuid = row.try_get("product_id")?;
    let size_raw: String = row.try_get("size_code")?;
    Ok(ProductVariantDto {
        id: id.to_string(),
        product_id: product_id.to_string(),
        size_code: parse_size_code(&size_raw)?,
        price_paise: row.try_get("price_paise")?,
        is_active: row.try_get("is_active")?,
    })
}

fn row_to_crust(row: &sqlx::postgres::PgRow) -> Result<CrustOptionDto, ApiError> {
    let id: Uuid = row.try_get("id")?;
    Ok(CrustOptionDto {
        id: id.to_string(),
        name: row.try_get("name")?,
        extra_price_paise: row.try_get("extra_price_paise")?,
        is_active: row.try_get("is_active")?,
        supports_half_and_half: row.try_get("supports_half_and_half")?,
    })
}

fn row_to_topping(row: &sqlx::postgres::PgRow) -> Result<ToppingDto, ApiError> {
    let id: Uuid = row.try_get("id")?;
    Ok(ToppingDto {
        id: id.to_string(),
        name: row.try_get("name")?,
        is_veg: row.try_get("is_veg")?,
        extra_price_paise: row.try_get("extra_price_paise")?,
        is_active: row.try_get("is_active")?,
        max_quantity: row.try_get("max_quantity")?,
        supports_half_and_half: row.try_get("supports_half_and_half")?,
    })
}

fn row_to_cheese(row: &sqlx::postgres::PgRow) -> Result<CheeseOptionDto, ApiError> {
    let id: Uuid = row.try_get("id")?;
    Ok(CheeseOptionDto {
        id: id.to_string(),
        name: row.try_get("name")?,
        extra_price_paise: row.try_get("extra_price_paise")?,
        extra_cheese_price_paise: row.try_get("extra_cheese_price_paise")?,
        supports_extra: row.try_get("supports_extra")?,
        is_vegan: row.try_get("is_vegan")?,
        is_active: row.try_get("is_active")?,
    })
}

fn row_to_sauce(row: &sqlx::postgres::PgRow) -> Result<SauceOptionDto, ApiError> {
    let id: Uuid = row.try_get("id")?;
    Ok(SauceOptionDto {
        id: id.to_string(),
        name: row.try_get("name")?,
        extra_price_paise: row.try_get("extra_price_paise")?,
        extra_sauce_price_paise: row.try_get("extra_sauce_price_paise")?,
        supports_extra: row.try_get("supports_extra")?,
        is_vegan: row.try_get("is_vegan")?,
        is_active: row.try_get("is_active")?,
    })
}

fn row_to_size_rule(row: &sqlx::postgres::PgRow) -> Result<PizzaSizeRuleDto, ApiError> {
    let size_raw: String = row.try_get("size_code")?;
    Ok(PizzaSizeRuleDto {
        size_code: parse_size_code(&size_raw)?,
        display_name: row.try_get("display_name")?,
        max_toppings: row.try_get("max_toppings")?,
        max_extra_toppings: row.try_get("max_extra_toppings")?,
        supports_half_and_half: row.try_get("supports_half_and_half")?,
        ingredient_multiplier_bps: row.try_get("ingredient_multiplier_bps")?,
        is_active: row.try_get("is_active")?,
    })
}

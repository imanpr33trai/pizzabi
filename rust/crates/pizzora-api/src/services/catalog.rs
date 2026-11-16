use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;

use chrono::{DateTime, Utc};
use pizzora_core::SizeCode;
use pizzora_db::{
    new_uuid_v4, record_audit_log, AuditContext, DbError, EphemeralCacheStore,
};
use sqlx::{PgPool, Row as _};
use uuid::Uuid;

use crate::dto::{
    CategoryDto, CreateCrustRequest, CreateProductRequest, CreateToppingRequest,
    CreateVariantRequest, CrustOptionDto, ProductDto, ProductImageDto, ProductImageStatus,
    ProductVariantDto, PublicMenuResponse, ToppingDto, UpdateCrustRequest, UpdateProductRequest,
    UpdateToppingRequest, UpdateVariantRequest,
};
use crate::error::{format_iso8601, ApiError};
use crate::extractors::parse_uuid_param;
use crate::services::catalog_cache::{
    invalidate_catalog_cache, CATALOG_TTL_SECS, MENU_CACHE_KEY,
};
use crate::storage::ObjectStorageClient;

pub fn size_code_as_str(size: SizeCode) -> &'static str {
    match size {
        SizeCode::Regular => "REGULAR",
        SizeCode::Medium => "MEDIUM",
        SizeCode::Large => "LARGE",
    }
}

pub fn parse_size_code(raw: &str) -> Result<SizeCode, ApiError> {
    match raw.trim().to_ascii_uppercase().as_str() {
        "REGULAR" => Ok(SizeCode::Regular),
        "MEDIUM" => Ok(SizeCode::Medium),
        "LARGE" => Ok(SizeCode::Large),
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
        let (store_name, is_accepting_orders, gst_bps) = self.load_store_summary().await?;

        let response = PublicMenuResponse {
            categories,
            products,
            crusts,
            toppings,
            gst_bps,
            store_name,
            is_accepting_orders,
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

        let description = request.description.trim();
        sqlx::query(
            r#"
            INSERT INTO products (id, category_id, name, description, is_veg, is_active, is_taxable)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            "#,
        )
        .bind(product_id)
        .bind(category_id)
        .bind(name)
        .bind(description)
        .bind(request.is_veg)
        .bind(request.is_active)
        .bind(request.is_taxable)
        .execute(&mut *tx)
        .await?;

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
            .ok_or_else(|| ApiError::internal())?;

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
                let exists: Option<i32> = sqlx::query_scalar("SELECT 1 FROM categories WHERE id = $1")
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

        sqlx::query(
            r#"
            UPDATE products
            SET category_id = $1, name = $2, description = $3,
                is_veg = $4, is_active = $5, is_taxable = $6, updated_at = NOW()
            WHERE id = $7
            "#,
        )
        .bind(next_category_id)
        .bind(&next_name)
        .bind(&next_desc)
        .bind(next_veg)
        .bind(next_active)
        .bind(next_taxable)
        .bind(product_id)
        .execute(&mut *tx)
        .await?;

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
            SELECT (SELECT COUNT(*) FROM cart_items WHERE product_id = $1)
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
            INSERT INTO crust_options (id, name, extra_price_paise, is_active)
            VALUES ($1, $2, $3, $4)
            RETURNING id, name, extra_price_paise, is_active
            "#,
        )
        .bind(crust_id)
        .bind(name)
        .bind(request.extra_price_paise)
        .bind(request.is_active)
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
            "SELECT id, name, extra_price_paise, is_active FROM crust_options WHERE id = $1 FOR UPDATE",
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

        let updated_row = sqlx::query(
            r#"
            UPDATE crust_options
            SET name = $1, extra_price_paise = $2, is_active = $3
            WHERE id = $4
            RETURNING id, name, extra_price_paise, is_active
            "#,
        )
        .bind(&next_name)
        .bind(next_extra)
        .bind(next_active)
        .bind(crust_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                ApiError::conflict("CRUST_EXISTS", format!("Crust '{next_name}' already exists."))
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

        let topping_id = new_uuid_v4();
        let mut tx = self.pool.begin().await?;
        let row = sqlx::query(
            r#"
            INSERT INTO toppings (id, name, is_veg, extra_price_paise, is_active)
            VALUES ($1, $2, $3, $4, $5)
            RETURNING id, name, is_veg, extra_price_paise, is_active
            "#,
        )
        .bind(topping_id)
        .bind(name)
        .bind(request.is_veg)
        .bind(request.extra_price_paise)
        .bind(request.is_active)
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
            "SELECT id, name, is_veg, extra_price_paise, is_active FROM toppings WHERE id = $1 FOR UPDATE",
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

        let updated_row = sqlx::query(
            r#"
            UPDATE toppings
            SET name = $1, is_veg = $2, extra_price_paise = $3, is_active = $4
            WHERE id = $5
            RETURNING id, name, is_veg, extra_price_paise, is_active
            "#,
        )
        .bind(&next_name)
        .bind(next_veg)
        .bind(next_extra)
        .bind(next_active)
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
                       p.is_veg, p.is_active, p.is_taxable
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
                       p.is_veg, p.is_active, p.is_taxable
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
                ORDER BY CASE size_code WHEN 'REGULAR' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LARGE' THEN 3 ELSE 4 END ASC
                "#,
            )
            .fetch_all(&self.pool)
            .await?
        } else {
            sqlx::query(
                r#"
                SELECT id, product_id, size_code, price_paise, is_active
                FROM product_variants
                ORDER BY CASE size_code WHEN 'REGULAR' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LARGE' THEN 3 ELSE 4 END ASC
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
            images_by_prod.entry(product_id).or_default().push(ProductImageDto {
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
                   p.is_veg, p.is_active, p.is_taxable
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
            ORDER BY CASE size_code WHEN 'REGULAR' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LARGE' THEN 3 ELSE 4 END ASC
            "#,
        )
        .bind(product_id)
        .fetch_all(&mut **tx)
        .await?;
        let variants = var_rows
            .iter()
            .map(row_to_variant)
            .collect::<Result<Vec<_>, _>>()?;

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
        }))
    }

    async fn load_crusts(&self, active_only: bool) -> Result<Vec<CrustOptionDto>, ApiError> {
        let rows = if active_only {
            sqlx::query("SELECT id, name, extra_price_paise, is_active FROM crust_options WHERE is_active = TRUE ORDER BY extra_price_paise ASC, name ASC")
                .fetch_all(&self.pool)
                .await?
        } else {
            sqlx::query("SELECT id, name, extra_price_paise, is_active FROM crust_options ORDER BY extra_price_paise ASC, name ASC")
                .fetch_all(&self.pool)
                .await?
        };
        rows.iter().map(row_to_crust).collect()
    }

    async fn load_toppings(&self, active_only: bool) -> Result<Vec<ToppingDto>, ApiError> {
        let rows = if active_only {
            sqlx::query("SELECT id, name, is_veg, extra_price_paise, is_active FROM toppings WHERE is_active = TRUE ORDER BY name ASC")
                .fetch_all(&self.pool)
                .await?
        } else {
            sqlx::query("SELECT id, name, is_veg, extra_price_paise, is_active FROM toppings ORDER BY name ASC")
                .fetch_all(&self.pool)
                .await?
        };
        rows.iter().map(row_to_topping).collect()
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
    })
}

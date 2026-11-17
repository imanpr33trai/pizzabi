use std::sync::Arc;

use chrono::{DateTime, Utc};
use pizzora_db::{new_uuid_v4, record_audit_log, AuditContext, DbError, EphemeralCacheStore};
use sqlx::{PgPool, Row as _};
use uuid::Uuid;

use crate::dto::{CategoryDto, CreateCategoryRequest, UpdateCategoryRequest};
use crate::error::{format_iso8601, ApiError};
use crate::services::catalog_cache::{
    invalidate_catalog_cache, CATALOG_TTL_SECS, CATEGORIES_CACHE_KEY,
};

#[derive(Clone)]
pub struct CategoriesService {
    pool: PgPool,
    cache: Arc<dyn EphemeralCacheStore>,
}

impl CategoriesService {
    pub fn new(pool: PgPool, cache: Arc<dyn EphemeralCacheStore>) -> Self {
        Self { pool, cache }
    }

    pub async fn list_active(&self) -> Result<Vec<CategoryDto>, ApiError> {
        if let Some(cached) = self.cache.get(CATEGORIES_CACHE_KEY).await {
            if let Ok(parsed) = serde_json::from_str::<Vec<CategoryDto>>(&cached) {
                return Ok(parsed);
            }
        }
        let items = self.query_categories(true).await?;
        if let Ok(encoded) = serde_json::to_string(&items) {
            self.cache
                .set_ex(CATEGORIES_CACHE_KEY, CATALOG_TTL_SECS, &encoded)
                .await;
        }
        Ok(items)
    }

    pub async fn list_all(&self) -> Result<Vec<CategoryDto>, ApiError> {
        self.query_categories(false).await
    }

    async fn query_categories(&self, active_only: bool) -> Result<Vec<CategoryDto>, ApiError> {
        let rows = if active_only {
            sqlx::query(
                r#"
                SELECT id, name, sort_order, is_active, created_at
                FROM categories
                WHERE is_active = TRUE
                ORDER BY sort_order ASC, name ASC
                "#,
            )
            .fetch_all(&self.pool)
            .await?
        } else {
            sqlx::query(
                r#"
                SELECT id, name, sort_order, is_active, created_at
                FROM categories
                ORDER BY sort_order ASC, name ASC
                "#,
            )
            .fetch_all(&self.pool)
            .await?
        };

        rows.iter().map(row_to_category).collect()
    }

    pub async fn create_category(
        &self,
        request: &CreateCategoryRequest,
        audit: &AuditContext,
    ) -> Result<CategoryDto, ApiError> {
        let name = request.name.trim();
        if name.is_empty() || name.len() > 80 {
            return Err(ApiError::validation(
                "INVALID_CATEGORY_NAME",
                "Category name must be 1..80 characters.",
            ));
        }
        if request.sort_order < 0 {
            return Err(ApiError::validation(
                "INVALID_SORT_ORDER",
                "sort_order must be non-negative.",
            ));
        }

        let category_id = new_uuid_v4();
        let mut tx = self.pool.begin().await?;
        let row = sqlx::query(
            r#"
            INSERT INTO categories (id, name, sort_order, is_active)
            VALUES ($1, $2, $3, $4)
            RETURNING id, name, sort_order, is_active, created_at
            "#,
        )
        .bind(category_id)
        .bind(name)
        .bind(request.sort_order)
        .bind(request.is_active)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                ApiError::conflict(
                    "CATEGORY_EXISTS",
                    format!("Category '{name}' already exists."),
                )
            } else {
                ApiError::from(DbError::Sqlx(err))
            }
        })?;

        let dto = row_to_category(&row)?;
        let after_val = serde_json::to_value(&dto).ok();
        let entity_id = category_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "CATEGORY_CREATED",
            "category",
            &entity_id,
            None,
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(dto)
    }

    pub async fn update_category(
        &self,
        category_id: Uuid,
        request: &UpdateCategoryRequest,
        audit: &AuditContext,
    ) -> Result<CategoryDto, ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing_row = sqlx::query(
            "SELECT id, name, sort_order, is_active, created_at FROM categories WHERE id = $1 FOR UPDATE",
        )
        .bind(category_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "CATEGORY_NOT_FOUND",
                format!("Category {category_id} was not found."),
            )
        })?;
        let existing = row_to_category(&existing_row)?;

        let next_name = match request.name.as_deref() {
            Some(raw) => {
                let trimmed = raw.trim();
                if trimmed.is_empty() || trimmed.len() > 80 {
                    return Err(ApiError::validation(
                        "INVALID_CATEGORY_NAME",
                        "Category name must be 1..80 characters.",
                    ));
                }
                trimmed.to_owned()
            }
            None => existing.name.clone(),
        };
        let next_sort = match request.sort_order {
            Some(s) if s < 0 => {
                return Err(ApiError::validation(
                    "INVALID_SORT_ORDER",
                    "sort_order must be non-negative.",
                ));
            }
            Some(s) => s,
            None => existing.sort_order,
        };
        let next_active = request.is_active.unwrap_or(existing.is_active);

        let updated_row = sqlx::query(
            r#"
            UPDATE categories
            SET name = $1, sort_order = $2, is_active = $3
            WHERE id = $4
            RETURNING id, name, sort_order, is_active, created_at
            "#,
        )
        .bind(&next_name)
        .bind(next_sort)
        .bind(next_active)
        .bind(category_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                ApiError::conflict(
                    "CATEGORY_EXISTS",
                    format!("Category '{next_name}' already exists."),
                )
            } else {
                ApiError::from(DbError::Sqlx(err))
            }
        })?;

        let updated = row_to_category(&updated_row)?;
        let before_val = serde_json::to_value(&existing).ok();
        let after_val = serde_json::to_value(&updated).ok();
        let entity_id = category_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "CATEGORY_UPDATED",
            "category",
            &entity_id,
            before_val.as_ref(),
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(updated)
    }

    pub async fn delete_category(
        &self,
        category_id: Uuid,
        audit: &AuditContext,
    ) -> Result<(), ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing_row = sqlx::query(
            "SELECT id, name, sort_order, is_active, created_at FROM categories WHERE id = $1 FOR UPDATE",
        )
        .bind(category_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "CATEGORY_NOT_FOUND",
                format!("Category {category_id} was not found."),
            )
        })?;
        let existing = row_to_category(&existing_row)?;

        let product_count: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM products WHERE category_id = $1")
                .bind(category_id)
                .fetch_one(&mut *tx)
                .await?;

        let action = if product_count > 0 {
            sqlx::query("UPDATE categories SET is_active = FALSE WHERE id = $1")
                .bind(category_id)
                .execute(&mut *tx)
                .await?;
            "CATEGORY_DEACTIVATED"
        } else {
            sqlx::query("DELETE FROM categories WHERE id = $1")
                .bind(category_id)
                .execute(&mut *tx)
                .await?;
            "CATEGORY_DELETED"
        };

        let before_val = serde_json::to_value(&existing).ok();
        let entity_id = category_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            action,
            "category",
            &entity_id,
            before_val.as_ref(),
            None,
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(())
    }
}

fn row_to_category(row: &sqlx::postgres::PgRow) -> Result<CategoryDto, ApiError> {
    let id: Uuid = row.try_get("id")?;
    let created_at: DateTime<Utc> = row.try_get("created_at")?;
    Ok(CategoryDto {
        id: id.to_string(),
        name: row.try_get("name")?,
        sort_order: row.try_get("sort_order")?,
        is_active: row.try_get("is_active")?,
        created_at: format_iso8601(created_at),
    })
}

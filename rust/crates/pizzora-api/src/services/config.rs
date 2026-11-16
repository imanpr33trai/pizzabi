use std::sync::Arc;

use chrono::{DateTime, Utc};
use pizzora_db::{record_audit_log, AuditContext, EphemeralCacheStore};
use sqlx::{PgPool, Row as _};

use crate::dto::{PublicConfigResponse, RestaurantSettingsDto, UpdateRestaurantSettingsRequest};
use crate::error::ApiError;
use crate::services::catalog_cache::{
    invalidate_all_public_caches, CONFIG_TTL_SECS, SETTINGS_PUBLIC_CACHE_KEY,
};

#[derive(Clone)]
pub struct RestaurantConfigService {
    pool: PgPool,
    cache: Arc<dyn EphemeralCacheStore>,
}

impl RestaurantConfigService {
    pub fn new(pool: PgPool, cache: Arc<dyn EphemeralCacheStore>) -> Self {
        Self { pool, cache }
    }

    pub async fn get_public_config(&self) -> Result<PublicConfigResponse, ApiError> {
        if let Some(cached) = self.cache.get(SETTINGS_PUBLIC_CACHE_KEY).await {
            if let Ok(parsed) = serde_json::from_str::<PublicConfigResponse>(&cached) {
                return Ok(parsed);
            }
        }
        let settings = self.get_owner_settings().await?;
        let public = PublicConfigResponse {
            store_name: settings.store_name,
            is_accepting_orders: settings.is_accepting_orders,
            gst_bps: settings.gst_bps,
            default_prep_mins: settings.default_prep_mins,
            timezone: settings.timezone,
        };
        if let Ok(encoded) = serde_json::to_string(&public) {
            self.cache
                .set_ex(SETTINGS_PUBLIC_CACHE_KEY, CONFIG_TTL_SECS, &encoded)
                .await;
        }
        Ok(public)
    }

    pub async fn get_owner_settings(&self) -> Result<RestaurantSettingsDto, ApiError> {
        let row = sqlx::query(
            r#"
            SELECT store_name, is_accepting_orders, gst_bps, default_prep_mins, timezone, updated_at
            FROM restaurant_settings
            WHERE id = 1
            "#,
        )
        .fetch_optional(&self.pool)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "RESTAURANT_SETTINGS_MISSING",
                "restaurant_settings row (id=1) is missing.",
            )
        })?;

        row_to_settings(&row)
    }

    pub async fn update_owner_settings(
        &self,
        request: &UpdateRestaurantSettingsRequest,
        audit: &AuditContext,
    ) -> Result<RestaurantSettingsDto, ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing_row = sqlx::query(
            r#"
            SELECT store_name, is_accepting_orders, gst_bps, default_prep_mins, timezone, updated_at
            FROM restaurant_settings
            WHERE id = 1
            FOR UPDATE
            "#,
        )
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "RESTAURANT_SETTINGS_MISSING",
                "restaurant_settings row (id=1) is missing.",
            )
        })?;
        let existing = row_to_settings(&existing_row)?;

        let next_name = match request.store_name.as_deref() {
            Some(raw) => {
                let trimmed = raw.trim();
                if trimmed.is_empty() || trimmed.len() > 100 {
                    return Err(ApiError::validation(
                        "INVALID_STORE_NAME",
                        "store_name must be 1..100 characters.",
                    ));
                }
                trimmed.to_owned()
            }
            None => existing.store_name.clone(),
        };
        let next_accepting = request
            .is_accepting_orders
            .unwrap_or(existing.is_accepting_orders);
        let next_gst_bps = match request.gst_bps {
            Some(bps) if !(0..=2800).contains(&bps) => {
                return Err(ApiError::validation(
                    "INVALID_GST_BPS",
                    "gst_bps must be between 0 and 2800.",
                ));
            }
            Some(bps) => bps,
            None => existing.gst_bps,
        };
        let next_prep_mins = match request.default_prep_mins {
            Some(mins) if !(1..=240).contains(&mins) => {
                return Err(ApiError::validation(
                    "INVALID_PREP_MINS",
                    "default_prep_mins must be between 1 and 240.",
                ));
            }
            Some(mins) => mins,
            None => existing.default_prep_mins,
        };

        let updated_row = sqlx::query(
            r#"
            UPDATE restaurant_settings
            SET store_name = $1,
                is_accepting_orders = $2,
                gst_bps = $3,
                default_prep_mins = $4,
                updated_at = NOW()
            WHERE id = 1
            RETURNING store_name, is_accepting_orders, gst_bps, default_prep_mins, timezone, updated_at
            "#,
        )
        .bind(&next_name)
        .bind(next_accepting)
        .bind(next_gst_bps)
        .bind(next_prep_mins)
        .fetch_one(&mut *tx)
        .await?;

        let updated = row_to_settings(&updated_row)?;
        let before_val = serde_json::to_value(&existing).ok();
        let after_val = serde_json::to_value(&updated).ok();
        record_audit_log(
            &mut tx,
            audit,
            "RESTAURANT_SETTINGS_UPDATED",
            "restaurant_settings",
            "1",
            before_val.as_ref(),
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_all_public_caches(&*self.cache).await;
        Ok(updated)
    }
}

fn row_to_settings(row: &sqlx::postgres::PgRow) -> Result<RestaurantSettingsDto, ApiError> {
    let updated_at: DateTime<Utc> = row.try_get("updated_at")?;
    Ok(RestaurantSettingsDto {
        store_name: row.try_get("store_name")?,
        is_accepting_orders: row.try_get("is_accepting_orders")?,
        gst_bps: row.try_get("gst_bps")?,
        default_prep_mins: row.try_get("default_prep_mins")?,
        timezone: row.try_get("timezone")?,
        updated_at: crate::error::format_iso8601(updated_at),
    })
}

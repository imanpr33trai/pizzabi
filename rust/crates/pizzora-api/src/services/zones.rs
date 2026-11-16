use pizzora_db::{new_uuid_v4, record_audit_log, AuditContext, DbError};
use sqlx::{PgPool, Row as _};
use uuid::Uuid;

use crate::dto::{
    CreateDeliveryZoneRequest, DeliveryZoneCheckResponse, DeliveryZoneDto,
    UpdateDeliveryZoneRequest,
};
use crate::error::ApiError;

#[derive(Clone)]
pub struct DeliveryZoneService {
    pool: PgPool,
}

impl DeliveryZoneService {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub async fn check_pincode(&self, raw_pincode: &str) -> Result<DeliveryZoneCheckResponse, ApiError> {
        let pincode = validate_pincode(raw_pincode)?;
        let row = sqlx::query(
            r#"
            SELECT id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active
            FROM delivery_zones
            WHERE pincode = $1 AND is_active = TRUE
            "#,
        )
        .bind(&pincode)
        .fetch_optional(&self.pool)
        .await?;

        match row {
            Some(r) => {
                let zone = row_to_zone(&r)?;
                let message = format!(
                    "Delivery available in {} (ETA ~{} mins).",
                    zone.area_name, zone.estimated_mins
                );
                Ok(DeliveryZoneCheckResponse {
                    pincode,
                    is_serviceable: true,
                    zone: Some(zone),
                    message,
                })
            }
            None => Ok(DeliveryZoneCheckResponse {
                pincode: pincode.clone(),
                is_serviceable: false,
                zone: None,
                message: format!("Sorry, pincode {pincode} is currently outside our delivery area."),
            }),
        }
    }

    pub async fn list_all_zones(&self) -> Result<Vec<DeliveryZoneDto>, ApiError> {
        let rows = sqlx::query(
            r#"
            SELECT id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active
            FROM delivery_zones
            ORDER BY is_active DESC, pincode ASC
            "#,
        )
        .fetch_all(&self.pool)
        .await?;

        rows.iter().map(row_to_zone).collect()
    }

    pub async fn create_zone(
        &self,
        request: &CreateDeliveryZoneRequest,
        audit: &AuditContext,
    ) -> Result<DeliveryZoneDto, ApiError> {
        let pincode = validate_pincode(&request.pincode)?;
        let area_name = request.area_name.trim();
        if area_name.is_empty() || area_name.len() > 100 {
            return Err(ApiError::validation(
                "INVALID_AREA_NAME",
                "area_name must be 1..100 characters.",
            ));
        }
        if request.min_order_paise < 0 {
            return Err(ApiError::validation(
                "INVALID_MIN_ORDER",
                "min_order_paise must be non-negative.",
            ));
        }
        if request.delivery_fee_paise < 0 {
            return Err(ApiError::validation(
                "INVALID_DELIVERY_FEE",
                "delivery_fee_paise must be non-negative.",
            ));
        }
        if !(1..=180).contains(&request.estimated_mins) {
            return Err(ApiError::validation(
                "INVALID_ETA",
                "estimated_mins must be between 1 and 180.",
            ));
        }

        let zone_id = new_uuid_v4();
        let mut tx = self.pool.begin().await?;
        let row = sqlx::query(
            r#"
            INSERT INTO delivery_zones (
                id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active
            ) VALUES ($1, $2, $3, $4, $5, $6, $7)
            RETURNING id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active
            "#,
        )
        .bind(zone_id)
        .bind(&pincode)
        .bind(area_name)
        .bind(request.min_order_paise)
        .bind(request.delivery_fee_paise)
        .bind(request.estimated_mins)
        .bind(request.is_active)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                ApiError::conflict(
                    "DELIVERY_ZONE_EXISTS",
                    format!("Delivery zone for pincode {pincode} already exists."),
                )
            } else {
                ApiError::from(DbError::Sqlx(err))
            }
        })?;

        let dto = row_to_zone(&row)?;
        let after_val = serde_json::to_value(&dto).ok();
        let entity_id = zone_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "DELIVERY_ZONE_CREATED",
            "delivery_zone",
            &entity_id,
            None,
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        Ok(dto)
    }

    pub async fn update_zone(
        &self,
        zone_id: Uuid,
        request: &UpdateDeliveryZoneRequest,
        audit: &AuditContext,
    ) -> Result<DeliveryZoneDto, ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing_row = sqlx::query(
            r#"
            SELECT id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active
            FROM delivery_zones
            WHERE id = $1
            FOR UPDATE
            "#,
        )
        .bind(zone_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "ZONE_NOT_FOUND",
                format!("Delivery zone {zone_id} was not found."),
            )
        })?;
        let existing = row_to_zone(&existing_row)?;

        let next_area = match request.area_name.as_deref() {
            Some(raw) => {
                let trimmed = raw.trim();
                if trimmed.is_empty() || trimmed.len() > 100 {
                    return Err(ApiError::validation(
                        "INVALID_AREA_NAME",
                        "area_name must be 1..100 characters.",
                    ));
                }
                trimmed.to_owned()
            }
            None => existing.area_name.clone(),
        };
        let next_min = match request.min_order_paise {
            Some(v) if v < 0 => {
                return Err(ApiError::validation(
                    "INVALID_MIN_ORDER",
                    "min_order_paise must be non-negative.",
                ));
            }
            Some(v) => v,
            None => existing.min_order_paise,
        };
        let next_fee = match request.delivery_fee_paise {
            Some(v) if v < 0 => {
                return Err(ApiError::validation(
                    "INVALID_DELIVERY_FEE",
                    "delivery_fee_paise must be non-negative.",
                ));
            }
            Some(v) => v,
            None => existing.delivery_fee_paise,
        };
        let next_eta = match request.estimated_mins {
            Some(v) if !(1..=180).contains(&v) => {
                return Err(ApiError::validation(
                    "INVALID_ETA",
                    "estimated_mins must be between 1 and 180.",
                ));
            }
            Some(v) => v,
            None => existing.estimated_mins,
        };
        let next_active = request.is_active.unwrap_or(existing.is_active);

        let updated_row = sqlx::query(
            r#"
            UPDATE delivery_zones
            SET area_name = $1, min_order_paise = $2, delivery_fee_paise = $3,
                estimated_mins = $4, is_active = $5
            WHERE id = $6
            RETURNING id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active
            "#,
        )
        .bind(&next_area)
        .bind(next_min)
        .bind(next_fee)
        .bind(next_eta)
        .bind(next_active)
        .bind(zone_id)
        .fetch_one(&mut *tx)
        .await?;

        let updated = row_to_zone(&updated_row)?;
        let before_val = serde_json::to_value(&existing).ok();
        let after_val = serde_json::to_value(&updated).ok();
        let entity_id = zone_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "DELIVERY_ZONE_UPDATED",
            "delivery_zone",
            &entity_id,
            before_val.as_ref(),
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        Ok(updated)
    }

    pub async fn delete_zone(&self, zone_id: Uuid, audit: &AuditContext) -> Result<(), ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing_row = sqlx::query(
            r#"
            SELECT id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active
            FROM delivery_zones
            WHERE id = $1
            FOR UPDATE
            "#,
        )
        .bind(zone_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "ZONE_NOT_FOUND",
                format!("Delivery zone {zone_id} was not found."),
            )
        })?;
        let existing = row_to_zone(&existing_row)?;

        sqlx::query("DELETE FROM delivery_zones WHERE id = $1")
            .bind(zone_id)
            .execute(&mut *tx)
            .await?;

        let before_val = serde_json::to_value(&existing).ok();
        let entity_id = zone_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "DELIVERY_ZONE_DELETED",
            "delivery_zone",
            &entity_id,
            before_val.as_ref(),
            None,
        )
        .await?;

        tx.commit().await?;
        Ok(())
    }
}

pub fn validate_pincode(raw: &str) -> Result<String, ApiError> {
    let trimmed = raw.trim();
    if trimmed.len() == 6 && trimmed.bytes().all(|b| b.is_ascii_digit()) {
        Ok(trimmed.to_owned())
    } else {
        Err(ApiError::validation(
            "INVALID_PINCODE",
            "Pincode must be a 6-digit Indian postal code.",
        ))
    }
}

fn row_to_zone(row: &sqlx::postgres::PgRow) -> Result<DeliveryZoneDto, ApiError> {
    let id: Uuid = row.try_get("id")?;
    Ok(DeliveryZoneDto {
        id: id.to_string(),
        pincode: row.try_get("pincode")?,
        area_name: row.try_get("area_name")?,
        min_order_paise: row.try_get("min_order_paise")?,
        delivery_fee_paise: row.try_get("delivery_fee_paise")?,
        estimated_mins: row.try_get("estimated_mins")?,
        is_active: row.try_get("is_active")?,
    })
}

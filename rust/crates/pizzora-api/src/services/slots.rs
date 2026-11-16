use chrono::{DateTime, Duration, FixedOffset, NaiveDate, NaiveTime, Utc};
use pizzora_core::FulfillmentType;
use pizzora_db::{new_uuid_v4, record_audit_log, AuditContext, DbError};
use sqlx::{PgPool, Row as _};
use uuid::Uuid;

use crate::dto::{
    AvailableSlotsResponse, CreateScheduledSlotRequest, ScheduledSlotDto, UpdateScheduledSlotRequest,
};
use crate::error::{format_iso8601, ApiError};

pub fn ist_offset() -> FixedOffset {
    FixedOffset::east_opt(5 * 3600 + 30 * 60).expect("valid IST +05:30 offset")
}

pub fn today_ist_date() -> NaiveDate {
    Utc::now().with_timezone(&ist_offset()).date_naive()
}

pub fn parse_fulfillment_type(raw: &str) -> Result<FulfillmentType, ApiError> {
    match raw.trim().to_ascii_uppercase().as_str() {
        "DELIVERY" => Ok(FulfillmentType::Delivery),
        "PICKUP" => Ok(FulfillmentType::Pickup),
        other => Err(ApiError::validation(
            "INVALID_FULFILLMENT_TYPE",
            format!("Unsupported fulfillment type '{other}'."),
        )),
    }
}

pub fn fulfillment_type_as_str(ft: FulfillmentType) -> &'static str {
    match ft {
        FulfillmentType::Delivery => "DELIVERY",
        FulfillmentType::Pickup => "PICKUP",
    }
}

#[derive(Clone)]
pub struct ScheduledSlotService {
    pool: PgPool,
}

impl ScheduledSlotService {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub async fn list_available_slots(
        &self,
        fulfillment_type: FulfillmentType,
        date_str: Option<&str>,
    ) -> Result<AvailableSlotsResponse, ApiError> {
        let target_date = parse_optional_date(date_str)?;
        let now = Utc::now();
        let rows = sqlx::query(
            r#"
            SELECT id, slot_date, fulfillment_type, start_time_ist, end_time_ist,
                   cutoff_at, max_capacity, reserved_count, is_active
            FROM scheduled_slots
            WHERE slot_date = $1 AND fulfillment_type = $2 AND is_active = TRUE
            ORDER BY start_time_ist ASC
            "#,
        )
        .bind(target_date)
        .bind(fulfillment_type_as_str(fulfillment_type))
        .fetch_all(&self.pool)
        .await?;

        let slots = rows
            .iter()
            .map(|r| row_to_slot_dto(r, now))
            .collect::<Result<Vec<_>, _>>()?;

        Ok(AvailableSlotsResponse {
            date: target_date.format("%Y-%m-%d").to_string(),
            fulfillment_type,
            timezone: "Asia/Kolkata".to_owned(),
            server_now: format_iso8601(now),
            slots,
        })
    }

    pub async fn list_owner_slots(
        &self,
        fulfillment_type: Option<FulfillmentType>,
        date_str: Option<&str>,
    ) -> Result<Vec<ScheduledSlotDto>, ApiError> {
        let now = Utc::now();
        let parsed_date = match date_str.map(str::trim).filter(|s| !s.is_empty()) {
            Some(d) => Some(parse_date(d)?),
            None => None,
        };
        let ft_str = fulfillment_type.map(fulfillment_type_as_str);

        let rows = sqlx::query(
            r#"
            SELECT id, slot_date, fulfillment_type, start_time_ist, end_time_ist,
                   cutoff_at, max_capacity, reserved_count, is_active
            FROM scheduled_slots
            WHERE ($1::date IS NULL OR slot_date = $1)
              AND ($2::text IS NULL OR fulfillment_type = $2)
            ORDER BY slot_date ASC, fulfillment_type ASC, start_time_ist ASC
            "#,
        )
        .bind(parsed_date)
        .bind(ft_str)
        .fetch_all(&self.pool)
        .await?;

        rows.iter().map(|r| row_to_slot_dto(r, now)).collect()
    }

    pub async fn create_slot(
        &self,
        request: &CreateScheduledSlotRequest,
        audit: &AuditContext,
    ) -> Result<ScheduledSlotDto, ApiError> {
        let slot_date = parse_date(&request.slot_date)?;
        let start_time = parse_ist_time(&request.start_time_ist, "start_time_ist")?;
        let end_time = parse_ist_time(&request.end_time_ist, "end_time_ist")?;
        if end_time <= start_time {
            return Err(ApiError::validation(
                "INVALID_SLOT_WINDOW",
                "end_time_ist must be strictly after start_time_ist.",
            ));
        }
        if request.max_capacity <= 0 {
            return Err(ApiError::validation(
                "INVALID_SLOT_CAPACITY",
                "max_capacity must be positive.",
            ));
        }

        let cutoff_at = resolve_cutoff_at(
            slot_date,
            start_time,
            request.cutoff_at.as_deref(),
            request.cutoff_minutes_before_start,
        )?;

        let slot_id = new_uuid_v4();
        let mut tx = self.pool.begin().await?;
        let row = sqlx::query(
            r#"
            INSERT INTO scheduled_slots (
                id, slot_date, fulfillment_type, start_time_ist, end_time_ist,
                cutoff_at, max_capacity, reserved_count, is_active
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, 0, $8)
            RETURNING id, slot_date, fulfillment_type, start_time_ist, end_time_ist,
                      cutoff_at, max_capacity, reserved_count, is_active
            "#,
        )
        .bind(slot_id)
        .bind(slot_date)
        .bind(fulfillment_type_as_str(request.fulfillment_type))
        .bind(start_time)
        .bind(end_time)
        .bind(cutoff_at)
        .bind(request.max_capacity)
        .bind(request.is_active)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                ApiError::conflict(
                    "SLOT_ALREADY_EXISTS",
                    "A scheduled slot already exists for that date, fulfillment type, and start time.",
                )
            } else {
                ApiError::from(DbError::Sqlx(err))
            }
        })?;

        let dto = row_to_slot_dto(&row, Utc::now())?;
        let after_val = serde_json::to_value(&dto).ok();
        let entity_id = slot_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "SCHEDULED_SLOT_CREATED",
            "scheduled_slot",
            &entity_id,
            None,
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        Ok(dto)
    }

    pub async fn update_slot(
        &self,
        slot_id: Uuid,
        request: &UpdateScheduledSlotRequest,
        audit: &AuditContext,
    ) -> Result<ScheduledSlotDto, ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing_row = sqlx::query(
            r#"
            SELECT id, slot_date, fulfillment_type, start_time_ist, end_time_ist,
                   cutoff_at, max_capacity, reserved_count, is_active
            FROM scheduled_slots
            WHERE id = $1
            FOR UPDATE
            "#,
        )
        .bind(slot_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found("SLOT_NOT_FOUND", format!("Scheduled slot {slot_id} not found."))
        })?;

        let slot_date: NaiveDate = existing_row.try_get("slot_date")?;
        let start_time: NaiveTime = existing_row.try_get("start_time_ist")?;
        let existing_cutoff: DateTime<Utc> = existing_row.try_get("cutoff_at")?;
        let existing_cap: i32 = existing_row.try_get("max_capacity")?;
        let reserved_count: i32 = existing_row.try_get("reserved_count")?;
        let existing_active: bool = existing_row.try_get("is_active")?;
        let existing_dto = row_to_slot_dto(&existing_row, Utc::now())?;

        let next_cap = match request.max_capacity {
            Some(c) if c <= 0 => {
                return Err(ApiError::validation(
                    "INVALID_SLOT_CAPACITY",
                    "max_capacity must be positive.",
                ));
            }
            Some(c) if c < reserved_count => {
                return Err(ApiError::conflict(
                    "SLOT_CAPACITY_BELOW_RESERVED",
                    format!(
                        "Cannot set max_capacity ({c}) below active reserved_count ({reserved_count})."
                    ),
                ));
            }
            Some(c) => c,
            None => existing_cap,
        };

        let next_cutoff = if request.cutoff_at.is_some()
            || request.cutoff_minutes_before_start.is_some()
        {
            resolve_cutoff_at(
                slot_date,
                start_time,
                request.cutoff_at.as_deref(),
                request.cutoff_minutes_before_start,
            )?
        } else {
            existing_cutoff
        };
        let next_active = request.is_active.unwrap_or(existing_active);

        let updated_row = sqlx::query(
            r#"
            UPDATE scheduled_slots
            SET cutoff_at = $1, max_capacity = $2, is_active = $3
            WHERE id = $4
            RETURNING id, slot_date, fulfillment_type, start_time_ist, end_time_ist,
                      cutoff_at, max_capacity, reserved_count, is_active
            "#,
        )
        .bind(next_cutoff)
        .bind(next_cap)
        .bind(next_active)
        .bind(slot_id)
        .fetch_one(&mut *tx)
        .await?;

        let updated = row_to_slot_dto(&updated_row, Utc::now())?;
        let before_val = serde_json::to_value(&existing_dto).ok();
        let after_val = serde_json::to_value(&updated).ok();
        let entity_id = slot_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "SCHEDULED_SLOT_UPDATED",
            "scheduled_slot",
            &entity_id,
            before_val.as_ref(),
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        Ok(updated)
    }

    pub async fn delete_slot(&self, slot_id: Uuid, audit: &AuditContext) -> Result<(), ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing_row = sqlx::query(
            r#"
            SELECT id, slot_date, fulfillment_type, start_time_ist, end_time_ist,
                   cutoff_at, max_capacity, reserved_count, is_active
            FROM scheduled_slots
            WHERE id = $1
            FOR UPDATE
            "#,
        )
        .bind(slot_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found("SLOT_NOT_FOUND", format!("Scheduled slot {slot_id} not found."))
        })?;
        let existing_dto = row_to_slot_dto(&existing_row, Utc::now())?;

        let in_use: bool = sqlx::query_scalar(
            r#"
            SELECT EXISTS(SELECT 1 FROM scheduled_slot_reservations WHERE slot_id = $1)
                OR EXISTS(SELECT 1 FROM orders WHERE scheduled_slot_id = $1)
            "#,
        )
        .bind(slot_id)
        .fetch_one(&mut *tx)
        .await?;

        if in_use {
            return Err(ApiError::conflict(
                "SLOT_IN_USE",
                "Scheduled slot already has orders or reservations; deactivate it instead.",
            ));
        }

        sqlx::query("DELETE FROM scheduled_slots WHERE id = $1")
            .bind(slot_id)
            .execute(&mut *tx)
            .await?;
        let action = "SCHEDULED_SLOT_DELETED";

        let before_val = serde_json::to_value(&existing_dto).ok();
        let entity_id = slot_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            action,
            "scheduled_slot",
            &entity_id,
            before_val.as_ref(),
            None,
        )
        .await?;

        tx.commit().await?;
        Ok(())
    }
}

fn parse_optional_date(raw: Option<&str>) -> Result<NaiveDate, ApiError> {
    match raw.map(str::trim).filter(|s| !s.is_empty()) {
        None => Ok(today_ist_date()),
        Some(s) => parse_date(s),
    }
}

fn parse_date(raw: &str) -> Result<NaiveDate, ApiError> {
    NaiveDate::parse_from_str(raw.trim(), "%Y-%m-%d").map_err(|_| {
        ApiError::validation(
            "INVALID_DATE",
            format!("Invalid YYYY-MM-DD date '{raw}'."),
        )
    })
}

fn parse_ist_time(raw: &str, field: &str) -> Result<NaiveTime, ApiError> {
    let trimmed = raw.trim();
    NaiveTime::parse_from_str(trimmed, "%H:%M:%S")
        .or_else(|_| NaiveTime::parse_from_str(trimmed, "%H:%M"))
        .map_err(|_| {
            ApiError::validation(
                "INVALID_TIME",
                format!("{field} must be HH:MM or HH:MM:SS in IST."),
            )
        })
}

fn resolve_cutoff_at(
    slot_date: NaiveDate,
    start_time: NaiveTime,
    raw_cutoff_at: Option<&str>,
    cutoff_minutes_before_start: Option<i32>,
) -> Result<DateTime<Utc>, ApiError> {
    if let Some(raw) = raw_cutoff_at.map(str::trim).filter(|s| !s.is_empty()) {
        return DateTime::parse_from_rfc3339(raw)
            .map(|dt| dt.with_timezone(&Utc))
            .map_err(|_| {
                ApiError::validation(
                    "INVALID_CUTOFF_AT",
                    "cutoff_at must be a valid ISO-8601 timestamp.",
                )
            });
    }
    let raw_minutes = cutoff_minutes_before_start.unwrap_or(30);
    if raw_minutes < 0 {
        return Err(ApiError::validation(
            "INVALID_CUTOFF_MINUTES",
            "cutoff_minutes_before_start must be non-negative.",
        ));
    }
    let minutes_before = i64::from(raw_minutes.min(1_440));
    let start_naive = slot_date.and_time(start_time);
    let start_ist = start_naive
        .and_local_timezone(ist_offset())
        .single()
        .ok_or_else(|| ApiError::validation("INVALID_TIME", "Unable to resolve IST timestamp."))?;
    Ok((start_ist - Duration::minutes(minutes_before)).with_timezone(&Utc))
}

fn row_to_slot_dto(
    row: &sqlx::postgres::PgRow,
    now: DateTime<Utc>,
) -> Result<ScheduledSlotDto, ApiError> {
    let id: Uuid = row.try_get("id")?;
    let slot_date: NaiveDate = row.try_get("slot_date")?;
    let ft_raw: String = row.try_get("fulfillment_type")?;
    let fulfillment_type = parse_fulfillment_type(&ft_raw)?;
    let start_time: NaiveTime = row.try_get("start_time_ist")?;
    let end_time: NaiveTime = row.try_get("end_time_ist")?;
    let cutoff_at: DateTime<Utc> = row.try_get("cutoff_at")?;
    let max_capacity: i32 = row.try_get("max_capacity")?;
    let reserved_count: i32 = row.try_get("reserved_count")?;
    let is_active: bool = row.try_get("is_active")?;

    let remaining_capacity = (max_capacity - reserved_count).max(0);
    let (is_bookable, unavailable_reason) = if !is_active {
        (false, Some("Slot deactivated".to_owned()))
    } else if cutoff_at <= now {
        (false, Some("Booking cutoff passed".to_owned()))
    } else if remaining_capacity <= 0 {
        (false, Some("Slot full".to_owned()))
    } else {
        (true, None)
    };

    Ok(ScheduledSlotDto {
        id: id.to_string(),
        slot_date: slot_date.format("%Y-%m-%d").to_string(),
        fulfillment_type,
        start_time_ist: start_time.format("%H:%M").to_string(),
        end_time_ist: end_time.format("%H:%M").to_string(),
        cutoff_at: format_iso8601(cutoff_at),
        max_capacity,
        reserved_count,
        remaining_capacity,
        is_active,
        is_bookable,
        unavailable_reason,
    })
}

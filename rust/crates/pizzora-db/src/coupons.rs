use std::collections::HashMap;

use chrono::{DateTime, Utc};
use pizzora_core::{
    CouponCalculationSpec, CouponEvaluator, CouponRuleInput, DiscountType, DomainError,
    PricingEngine,
};
use serde::{Deserialize, Serialize};
use sqlx::{PgConnection, PgPool, Row as _};
use uuid::Uuid;

use crate::audit::{record_audit_log, AuditContext};
use crate::error::{new_uuid_v4, DbError};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CouponDto {
    pub id: String,
    pub code: String,
    pub discount_type: DiscountType,
    pub discount_value: i64,
    #[serde(default)]
    pub max_discount_paise: Option<i64>,
    pub min_order_paise: i64,
    #[serde(default)]
    pub max_uses: Option<i32>,
    pub max_uses_per_user: i32,
    pub usage_count: i32,
    pub starts_at: String,
    pub ends_at: String,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CustomerCouponDto {
    pub id: String,
    pub code: String,
    pub discount_type: DiscountType,
    pub discount_value: i64,
    #[serde(default)]
    pub max_discount_paise: Option<i64>,
    pub min_order_paise: i64,
    #[serde(default)]
    pub max_uses: Option<i32>,
    pub max_uses_per_user: i32,
    pub user_usage_count: i32,
    #[serde(default)]
    pub remaining_global_uses: Option<i32>,
    pub remaining_user_uses: i32,
    pub starts_at: String,
    pub ends_at: String,
    pub is_eligible: bool,
    #[serde(default)]
    pub ineligible_reason: Option<String>,
    pub estimated_discount_paise: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AvailableCouponsResponse {
    pub coupons: Vec<CustomerCouponDto>,
    #[serde(default)]
    pub subtotal_paise: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AppliedCouponSummaryDto {
    pub id: String,
    pub code: String,
    pub discount_type: DiscountType,
    pub discount_value: i64,
    pub discount_paise: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CreateCouponRequest {
    pub code: String,
    pub discount_type: DiscountType,
    pub discount_value: i64,
    #[serde(default)]
    pub max_discount_paise: Option<i64>,
    pub min_order_paise: i64,
    #[serde(default)]
    pub max_uses: Option<i32>,
    #[serde(default = "default_one")]
    pub max_uses_per_user: i32,
    pub starts_at: String,
    pub ends_at: String,
    #[serde(default = "default_true")]
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct UpdateCouponRequest {
    pub discount_type: Option<DiscountType>,
    pub discount_value: Option<i64>,
    pub max_discount_paise: Option<i64>,
    #[serde(default)]
    pub clear_max_discount: bool,
    pub min_order_paise: Option<i64>,
    pub max_uses: Option<i32>,
    #[serde(default)]
    pub clear_max_uses: bool,
    pub max_uses_per_user: Option<i32>,
    pub starts_at: Option<String>,
    pub ends_at: Option<String>,
    pub is_active: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ValidatedCoupon {
    pub id: Uuid,
    pub code: String,
    pub spec: CouponCalculationSpec,
    pub discount_paise: i64,
}

fn default_one() -> i32 {
    1
}

fn default_true() -> bool {
    true
}

#[derive(Clone)]
pub struct CouponService {
    pool: PgPool,
}

impl CouponService {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub fn pool(&self) -> &PgPool {
        &self.pool
    }

    pub async fn list_all_coupons(&self) -> Result<Vec<CouponDto>, DbError> {
        let rows = sqlx::query(
            r#"
            SELECT id, code, discount_type, discount_value, max_discount_paise,
                   min_order_paise, max_uses, max_uses_per_user, usage_count,
                   starts_at, ends_at, is_active
            FROM coupons
            ORDER BY starts_at DESC, code ASC
            "#,
        )
        .fetch_all(&self.pool)
        .await?;

        rows.iter().map(row_to_coupon_dto).collect()
    }

    pub async fn create_coupon(
        &self,
        request: &CreateCouponRequest,
        audit: &AuditContext,
    ) -> Result<CouponDto, DbError> {
        let normalized_code = CouponEvaluator::normalize_code(&request.code)?;
        let starts_at = parse_rfc3339(&request.starts_at, "starts_at")?;
        let ends_at = parse_rfc3339(&request.ends_at, "ends_at")?;

        CouponEvaluator::validate_coupon_params(
            request.discount_type,
            request.discount_value,
            request.max_discount_paise,
            request.min_order_paise,
            request.max_uses,
            request.max_uses_per_user,
            starts_at.timestamp_millis(),
            ends_at.timestamp_millis(),
        )?;

        let id = new_uuid_v4();
        let mut tx = self.pool.begin().await?;
        let inserted = sqlx::query(
            r#"
            INSERT INTO coupons (
                id, code, discount_type, discount_value, max_discount_paise,
                min_order_paise, max_uses, max_uses_per_user, usage_count,
                starts_at, ends_at, is_active
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, 0, $9, $10, $11)
            RETURNING id, code, discount_type, discount_value, max_discount_paise,
                      min_order_paise, max_uses, max_uses_per_user, usage_count,
                      starts_at, ends_at, is_active
            "#,
        )
        .bind(id)
        .bind(&normalized_code)
        .bind(discount_type_as_str(request.discount_type))
        .bind(request.discount_value)
        .bind(request.max_discount_paise)
        .bind(request.min_order_paise)
        .bind(request.max_uses)
        .bind(request.max_uses_per_user)
        .bind(starts_at)
        .bind(ends_at)
        .bind(request.is_active)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                DbError::Conflict {
                    code: "COUPON_CODE_EXISTS",
                    message: format!("Coupon code '{normalized_code}' already exists."),
                }
            } else {
                DbError::Sqlx(err)
            }
        })?;

        let dto = row_to_coupon_dto(&inserted)?;
        let after_val = serde_json::to_value(&dto).ok();
        record_audit_log(
            &mut tx,
            audit,
            "COUPON_CREATED",
            "coupon",
            &dto.id,
            None,
            after_val.as_ref(),
        )
        .await?;
        tx.commit().await?;
        Ok(dto)
    }

    pub async fn update_coupon(
        &self,
        coupon_id: Uuid,
        request: &UpdateCouponRequest,
        audit: &AuditContext,
    ) -> Result<CouponDto, DbError> {
        let mut tx = self.pool.begin().await?;
        let current_row = sqlx::query(
            r#"
            SELECT id, code, discount_type, discount_value, max_discount_paise,
                   min_order_paise, max_uses, max_uses_per_user, usage_count,
                   starts_at, ends_at, is_active
            FROM coupons
            WHERE id = $1
            FOR UPDATE
            "#,
        )
        .bind(coupon_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| DbError::NotFound {
            code: "COUPON_NOT_FOUND",
            message: format!("Coupon {coupon_id} not found."),
        })?;

        let current = row_to_coupon_dto(&current_row)?;
        let discount_type = request.discount_type.unwrap_or(current.discount_type);
        let discount_value = request.discount_value.unwrap_or(current.discount_value);
        let max_discount_paise = if request.clear_max_discount {
            None
        } else {
            request.max_discount_paise.or(current.max_discount_paise)
        };
        let min_order_paise = request.min_order_paise.unwrap_or(current.min_order_paise);
        let max_uses = if request.clear_max_uses {
            None
        } else {
            request.max_uses.or(current.max_uses)
        };
        let max_uses_per_user = request
            .max_uses_per_user
            .unwrap_or(current.max_uses_per_user);
        let starts_at = match &request.starts_at {
            Some(s) => parse_rfc3339(s, "starts_at")?,
            None => current_row.try_get::<DateTime<Utc>, _>("starts_at")?,
        };
        let ends_at = match &request.ends_at {
            Some(s) => parse_rfc3339(s, "ends_at")?,
            None => current_row.try_get::<DateTime<Utc>, _>("ends_at")?,
        };
        let is_active = request.is_active.unwrap_or(current.is_active);

        CouponEvaluator::validate_coupon_params(
            discount_type,
            discount_value,
            max_discount_paise,
            min_order_paise,
            max_uses,
            max_uses_per_user,
            starts_at.timestamp_millis(),
            ends_at.timestamp_millis(),
        )?;

        if let Some(m) = max_uses {
            if m < current.usage_count {
                return Err(DbError::Validation {
                    code: "INVALID_MAX_USES",
                    message: format!(
                        "max_uses ({m}) cannot be less than current usage_count ({}).",
                        current.usage_count
                    ),
                });
            }
        }

        let updated_row = sqlx::query(
            r#"
            UPDATE coupons
            SET discount_type = $1,
                discount_value = $2,
                max_discount_paise = $3,
                min_order_paise = $4,
                max_uses = $5,
                max_uses_per_user = $6,
                starts_at = $7,
                ends_at = $8,
                is_active = $9
            WHERE id = $10
            RETURNING id, code, discount_type, discount_value, max_discount_paise,
                      min_order_paise, max_uses, max_uses_per_user, usage_count,
                      starts_at, ends_at, is_active
            "#,
        )
        .bind(discount_type_as_str(discount_type))
        .bind(discount_value)
        .bind(max_discount_paise)
        .bind(min_order_paise)
        .bind(max_uses)
        .bind(max_uses_per_user)
        .bind(starts_at)
        .bind(ends_at)
        .bind(is_active)
        .bind(coupon_id)
        .fetch_one(&mut *tx)
        .await?;

        let updated = row_to_coupon_dto(&updated_row)?;
        let before_val = serde_json::to_value(&current).ok();
        let after_val = serde_json::to_value(&updated).ok();
        let entity_id = coupon_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "COUPON_UPDATED",
            "coupon",
            &entity_id,
            before_val.as_ref(),
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        Ok(updated)
    }

    pub async fn delete_or_deactivate_coupon(
        &self,
        coupon_id: Uuid,
        audit: &AuditContext,
    ) -> Result<(), DbError> {
        let mut tx = self.pool.begin().await?;
        let current_row = sqlx::query(
            r#"
            SELECT id, code, discount_type, discount_value, max_discount_paise,
                   min_order_paise, max_uses, max_uses_per_user, usage_count,
                   starts_at, ends_at, is_active
            FROM coupons
            WHERE id = $1
            FOR UPDATE
            "#,
        )
        .bind(coupon_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| DbError::NotFound {
            code: "COUPON_NOT_FOUND",
            message: format!("Coupon {coupon_id} not found."),
        })?;

        let current = row_to_coupon_dto(&current_row)?;
        let has_refs: bool = sqlx::query_scalar(
            r#"
            SELECT EXISTS (
                SELECT 1 FROM coupon_reservations WHERE coupon_id = $1
                UNION ALL
                SELECT 1 FROM coupon_redemptions WHERE coupon_id = $1
            )
            "#,
        )
        .bind(coupon_id)
        .fetch_one(&mut *tx)
        .await?;

        let before_val = serde_json::to_value(&current).ok();
        let entity_id = coupon_id.to_string();

        if has_refs {
            sqlx::query("UPDATE coupons SET is_active = FALSE WHERE id = $1")
                .bind(coupon_id)
                .execute(&mut *tx)
                .await?;
            let mut deactivated = current.clone();
            deactivated.is_active = false;
            let after_val = serde_json::to_value(&deactivated).ok();
            record_audit_log(
                &mut tx,
                audit,
                "COUPON_DEACTIVATED",
                "coupon",
                &entity_id,
                before_val.as_ref(),
                after_val.as_ref(),
            )
            .await?;
        } else {
            sqlx::query("DELETE FROM coupons WHERE id = $1")
                .bind(coupon_id)
                .execute(&mut *tx)
                .await?;
            record_audit_log(
                &mut tx,
                audit,
                "COUPON_DELETED",
                "coupon",
                &entity_id,
                before_val.as_ref(),
                None,
            )
            .await?;
        }

        tx.commit().await?;
        Ok(())
    }

    pub async fn list_available_coupons(
        &self,
        user_id: Uuid,
        subtotal_paise: Option<i64>,
    ) -> Result<AvailableCouponsResponse, DbError> {
        if let Some(sub) = subtotal_paise {
            if sub < 0 {
                return Err(DbError::Validation {
                    code: "INVALID_SUBTOTAL",
                    message: "subtotal_paise must be non-negative.".to_owned(),
                });
            }
        }

        let mut tx = self.pool.begin().await?;
        let rows = sqlx::query(
            r#"
            SELECT id, code, discount_type, discount_value, max_discount_paise,
                   min_order_paise, max_uses, max_uses_per_user, usage_count,
                   starts_at, ends_at, is_active
            FROM coupons
            WHERE is_active = TRUE
              AND starts_at <= NOW()
              AND ends_at > NOW()
            ORDER BY min_order_paise ASC, code ASC
            "#,
        )
        .fetch_all(&mut *tx)
        .await?;

        let global_res_rows = sqlx::query(
            r#"
            SELECT coupon_id, COUNT(*)::int AS cnt
            FROM coupon_reservations
            WHERE status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > NOW())
            GROUP BY coupon_id
            "#,
        )
        .fetch_all(&mut *tx)
        .await?;
        let mut active_global = HashMap::with_capacity(global_res_rows.len());
        for r in global_res_rows {
            active_global.insert(r.try_get::<Uuid, _>("coupon_id")?, r.try_get::<i32, _>("cnt")?);
        }

        let user_usage_rows = sqlx::query(
            r#"
            SELECT coupon_id, COUNT(*)::int AS cnt
            FROM (
                SELECT coupon_id, order_id FROM coupon_redemptions WHERE user_id = $1
                UNION
                SELECT coupon_id, order_id FROM coupon_reservations
                WHERE user_id = $1 AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > NOW())
            ) combined
            GROUP BY coupon_id
            "#,
        )
        .bind(user_id)
        .fetch_all(&mut *tx)
        .await?;
        let mut user_usage = HashMap::with_capacity(user_usage_rows.len());
        for r in user_usage_rows {
            user_usage.insert(r.try_get::<Uuid, _>("coupon_id")?, r.try_get::<i32, _>("cnt")?);
        }

        let mut items = Vec::with_capacity(rows.len());
        for r in &rows {
            let parsed = row_to_coupon_row(r)?;
            let global_active = *active_global.get(&parsed.id).unwrap_or(&0);
            let user_used = *user_usage.get(&parsed.id).unwrap_or(&0);

            let input = CouponRuleInput {
                code: parsed.code.clone(),
                discount_type: parsed.discount_type,
                discount_value: parsed.discount_value,
                max_discount_paise: parsed.max_discount_paise,
                min_order_paise: parsed.min_order_paise,
                max_uses: parsed.max_uses,
                max_uses_per_user: parsed.max_uses_per_user,
                usage_count: parsed.usage_count,
                active_global_reservations: global_active,
                user_used_count: user_used,
                starts_at_epoch_ms: parsed.starts_at.timestamp_millis(),
                ends_at_epoch_ms: parsed.ends_at.timestamp_millis(),
                is_active: parsed.is_active,
            };
            let eval = CouponEvaluator::evaluate_listing(&input, subtotal_paise)?;
            items.push(CustomerCouponDto {
                id: parsed.id.to_string(),
                code: parsed.code,
                discount_type: parsed.discount_type,
                discount_value: parsed.discount_value,
                max_discount_paise: parsed.max_discount_paise,
                min_order_paise: parsed.min_order_paise,
                max_uses: parsed.max_uses,
                max_uses_per_user: parsed.max_uses_per_user,
                user_usage_count: user_used,
                remaining_global_uses: eval.remaining_global_uses,
                remaining_user_uses: eval.remaining_user_uses,
                starts_at: parsed.starts_at.to_rfc3339(),
                ends_at: parsed.ends_at.to_rfc3339(),
                is_eligible: eval.is_eligible,
                ineligible_reason: eval.ineligible_reason,
                estimated_discount_paise: eval.estimated_discount_paise,
            });
        }

        tx.commit().await?;
        Ok(AvailableCouponsResponse {
            coupons: items,
            subtotal_paise,
        })
    }

    pub async fn validate_coupon_for_quote(
        conn: &mut PgConnection,
        raw_code: &str,
        user_id: Uuid,
        subtotal_paise: i64,
    ) -> Result<ValidatedCoupon, DbError> {
        let row = load_coupon_by_code(&mut *conn, raw_code, false).await?;
        evaluate_coupon_in_tx(&mut *conn, &row, user_id, subtotal_paise, false).await
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn reserve_coupon_for_order(
        conn: &mut PgConnection,
        raw_code: &str,
        user_id: Uuid,
        order_id: Uuid,
        subtotal_paise: i64,
        expires_at: Option<DateTime<Utc>>,
        immediate_commit: bool,
    ) -> Result<AppliedCouponSummaryDto, DbError> {
        let row = load_coupon_by_code(&mut *conn, raw_code, true).await?;
        let validated = evaluate_coupon_in_tx(&mut *conn, &row, user_id, subtotal_paise, true).await?;

        let status = if immediate_commit { "COMMITTED" } else { "ACTIVE" };
        let effective_expires = if immediate_commit { None } else { expires_at };

        sqlx::query(
            r#"
            INSERT INTO coupon_reservations (
                id, coupon_id, user_id, order_id, discount_paise, status, expires_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7)
            "#,
        )
        .bind(new_uuid_v4())
        .bind(validated.id)
        .bind(user_id)
        .bind(order_id)
        .bind(validated.discount_paise)
        .bind(status)
        .bind(effective_expires)
        .execute(&mut *conn)
        .await?;

        if immediate_commit {
            record_redemption_and_increment_usage(
                &mut *conn,
                validated.id,
                user_id,
                order_id,
                validated.discount_paise,
            )
            .await?;
        }

        Ok(AppliedCouponSummaryDto {
            id: validated.id.to_string(),
            code: validated.code,
            discount_type: validated.spec.discount_type,
            discount_value: validated.spec.discount_value,
            discount_paise: validated.discount_paise,
        })
    }

    pub async fn commit_reserved_coupon(
        conn: &mut PgConnection,
        order_id: Uuid,
    ) -> Result<(), DbError> {
        let row = sqlx::query(
            r#"
            UPDATE coupon_reservations
            SET status = 'COMMITTED', expires_at = NULL
            WHERE order_id = $1 AND status = 'ACTIVE'
            RETURNING coupon_id, user_id, discount_paise
            "#,
        )
        .bind(order_id)
        .fetch_optional(&mut *conn)
        .await?;

        if let Some(r) = row {
            record_redemption_and_increment_usage(
                &mut *conn,
                r.try_get("coupon_id")?,
                r.try_get("user_id")?,
                order_id,
                r.try_get("discount_paise")?,
            )
            .await?;
        }

        Ok(())
    }

    pub async fn release_reserved_coupon(
        conn: &mut PgConnection,
        order_id: Uuid,
        expired: bool,
    ) -> Result<(), DbError> {
        let target_status = if expired { "EXPIRED" } else { "RELEASED" };
        sqlx::query(
            r#"
            UPDATE coupon_reservations
            SET status = $1, expires_at = NULL
            WHERE order_id = $2 AND status = 'ACTIVE'
            "#,
        )
        .bind(target_status)
        .bind(order_id)
        .execute(&mut *conn)
        .await?;
        Ok(())
    }
}

async fn load_coupon_by_code(
    conn: &mut PgConnection,
    raw_code: &str,
    lock_for_update: bool,
) -> Result<CouponRow, DbError> {
    let normalized = raw_code.trim().to_ascii_uppercase();
    if normalized.is_empty() {
        return Err(DbError::Validation {
            code: "INVALID_COUPON_CODE",
            message: "Coupon code cannot be blank.".to_owned(),
        });
    }

    let sql = if lock_for_update {
        r#"
        SELECT id, code, discount_type, discount_value, max_discount_paise,
               min_order_paise, max_uses, max_uses_per_user, usage_count,
               starts_at, ends_at, is_active
        FROM coupons
        WHERE UPPER(code) = $1
        FOR UPDATE
        "#
    } else {
        r#"
        SELECT id, code, discount_type, discount_value, max_discount_paise,
               min_order_paise, max_uses, max_uses_per_user, usage_count,
               starts_at, ends_at, is_active
        FROM coupons
        WHERE UPPER(code) = $1
        "#
    };

    let row = sqlx::query(sql)
        .bind(&normalized)
        .fetch_optional(&mut *conn)
        .await?
        .ok_or_else(|| DbError::Unprocessable {
            code: "COUPON_NOT_FOUND",
            message: format!("Coupon code '{normalized}' was not found."),
        })?;

    row_to_coupon_row(&row)
}

async fn evaluate_coupon_in_tx(
    conn: &mut PgConnection,
    row: &CouponRow,
    user_id: Uuid,
    subtotal_paise: i64,
    use_conflict_on_limit: bool,
) -> Result<ValidatedCoupon, DbError> {
    let active_reservations: i32 = sqlx::query_scalar(
        r#"
        SELECT COUNT(*)::int
        FROM coupon_reservations
        WHERE coupon_id = $1 AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > NOW())
        "#,
    )
    .bind(row.id)
    .fetch_one(&mut *conn)
    .await?;

    let user_used: i32 = sqlx::query_scalar(
        r#"
        SELECT COUNT(*)::int
        FROM (
            SELECT order_id FROM coupon_redemptions WHERE coupon_id = $1 AND user_id = $2
            UNION
            SELECT order_id FROM coupon_reservations
            WHERE coupon_id = $1 AND user_id = $2 AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > NOW())
        ) u
        "#,
    )
    .bind(row.id)
    .bind(user_id)
    .fetch_one(&mut *conn)
    .await?;

    let rule = CouponRuleInput {
        code: row.code.clone(),
        discount_type: row.discount_type,
        discount_value: row.discount_value,
        max_discount_paise: row.max_discount_paise,
        min_order_paise: row.min_order_paise,
        max_uses: row.max_uses,
        max_uses_per_user: row.max_uses_per_user,
        usage_count: row.usage_count,
        active_global_reservations: active_reservations,
        user_used_count: user_used,
        starts_at_epoch_ms: row.starts_at.timestamp_millis(),
        ends_at_epoch_ms: row.ends_at.timestamp_millis(),
        is_active: row.is_active,
    };

    let now_ms = Utc::now().timestamp_millis();
    CouponEvaluator::evaluate_eligibility(&rule, subtotal_paise, now_ms, use_conflict_on_limit)?;

    let spec = CouponCalculationSpec {
        discount_type: row.discount_type,
        discount_value: row.discount_value,
        max_discount_paise: row.max_discount_paise,
        min_order_paise: row.min_order_paise,
        is_active: row.is_active,
    };
    let discount_paise = PricingEngine::calculate_discount_paise(subtotal_paise, Some(&spec))?;

    Ok(ValidatedCoupon {
        id: row.id,
        code: row.code.clone(),
        spec,
        discount_paise,
    })
}

async fn record_redemption_and_increment_usage(
    conn: &mut PgConnection,
    coupon_id: Uuid,
    user_id: Uuid,
    order_id: Uuid,
    discount_paise: i64,
) -> Result<(), DbError> {
    let res = sqlx::query(
        r#"
        INSERT INTO coupon_redemptions (id, coupon_id, user_id, order_id, discount_paise)
        VALUES ($1, $2, $3, $4, $5)
        ON CONFLICT (order_id) DO NOTHING
        "#,
    )
    .bind(new_uuid_v4())
    .bind(coupon_id)
    .bind(user_id)
    .bind(order_id)
    .bind(discount_paise)
    .execute(&mut *conn)
    .await?;

    if res.rows_affected() > 0 {
        sqlx::query("UPDATE coupons SET usage_count = usage_count + 1 WHERE id = $1")
            .bind(coupon_id)
            .execute(&mut *conn)
            .await?;
    }
    Ok(())
}

#[derive(Debug, Clone)]
struct CouponRow {
    id: Uuid,
    code: String,
    discount_type: DiscountType,
    discount_value: i64,
    max_discount_paise: Option<i64>,
    min_order_paise: i64,
    max_uses: Option<i32>,
    max_uses_per_user: i32,
    usage_count: i32,
    starts_at: DateTime<Utc>,
    ends_at: DateTime<Utc>,
    is_active: bool,
}

fn row_to_coupon_row(row: &sqlx::postgres::PgRow) -> Result<CouponRow, DbError> {
    let discount_type_raw: String = row.try_get("discount_type")?;
    Ok(CouponRow {
        id: row.try_get("id")?,
        code: row.try_get("code")?,
        discount_type: parse_discount_type(&discount_type_raw)?,
        discount_value: row.try_get("discount_value")?,
        max_discount_paise: row.try_get("max_discount_paise")?,
        min_order_paise: row.try_get("min_order_paise")?,
        max_uses: row.try_get("max_uses")?,
        max_uses_per_user: row.try_get("max_uses_per_user")?,
        usage_count: row.try_get("usage_count")?,
        starts_at: row.try_get("starts_at")?,
        ends_at: row.try_get("ends_at")?,
        is_active: row.try_get("is_active")?,
    })
}

fn row_to_coupon_dto(row: &sqlx::postgres::PgRow) -> Result<CouponDto, DbError> {
    let parsed = row_to_coupon_row(row)?;
    Ok(CouponDto {
        id: parsed.id.to_string(),
        code: parsed.code,
        discount_type: parsed.discount_type,
        discount_value: parsed.discount_value,
        max_discount_paise: parsed.max_discount_paise,
        min_order_paise: parsed.min_order_paise,
        max_uses: parsed.max_uses,
        max_uses_per_user: parsed.max_uses_per_user,
        usage_count: parsed.usage_count,
        starts_at: parsed.starts_at.to_rfc3339(),
        ends_at: parsed.ends_at.to_rfc3339(),
        is_active: parsed.is_active,
    })
}

fn parse_rfc3339(raw: &str, field: &str) -> Result<DateTime<Utc>, DbError> {
    DateTime::parse_from_rfc3339(raw.trim())
        .map(|dt| dt.with_timezone(&Utc))
        .map_err(|_| DbError::Validation {
            code: "INVALID_TIMESTAMP",
            message: format!("Invalid ISO-8601 timestamp for {field}: {raw}"),
        })
}

const fn discount_type_as_str(t: DiscountType) -> &'static str {
    match t {
        DiscountType::PercentBps => "PERCENT_BPS",
        DiscountType::FlatPaise => "FLAT_PAISE",
    }
}

fn parse_discount_type(raw: &str) -> Result<DiscountType, DbError> {
    match raw {
        "PERCENT_BPS" => Ok(DiscountType::PercentBps),
        "FLAT_PAISE" => Ok(DiscountType::FlatPaise),
        other => Err(DbError::Domain(DomainError::Validation {
            code: "INVALID_DISCOUNT_TYPE",
            message: format!("Unsupported discount type '{other}'"),
        })),
    }
}

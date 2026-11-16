use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{PgConnection, PgPool, Row as _};
use uuid::Uuid;

use crate::error::{new_uuid_v4, DbError};

pub const CATEGORY_ORDER_UPDATE: &str = "ORDER_UPDATE";
pub const CATEGORY_PROMO_ALERT: &str = "PROMO_ALERT";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum NotificationDeliveryStatus {
    Pending,
    SentWs,
    Synced,
    Failed,
}

impl NotificationDeliveryStatus {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Pending => "PENDING",
            Self::SentWs => "SENT_WS",
            Self::Synced => "SYNCED",
            Self::Failed => "FAILED",
        }
    }

    pub fn from_db_str(raw: &str) -> Result<Self, DbError> {
        match raw {
            "PENDING" => Ok(Self::Pending),
            "SENT_WS" => Ok(Self::SentWs),
            "SYNCED" => Ok(Self::Synced),
            "FAILED" => Ok(Self::Failed),
            other => Err(DbError::CatalogInvariantViolation(format!(
                "Unknown notification delivery_status '{other}'"
            ))),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NotificationInboxItemDto {
    pub id: String,
    pub event_id: String,
    pub title: String,
    pub body: String,
    pub category: String,
    #[serde(default)]
    pub reference_id: Option<String>,
    pub is_read: bool,
    pub delivery_status: NotificationDeliveryStatus,
    pub created_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NotificationInboxResponse {
    pub notifications: Vec<NotificationInboxItemDto>,
    pub unread_count: i64,
    pub server_now: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MarkNotificationReadResponse {
    pub notification: NotificationInboxItemDto,
    pub unread_count: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MarkAllNotificationsReadResponse {
    pub marked_count: i64,
    pub unread_count: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NotificationPreferencesDto {
    pub user_id: String,
    pub order_updates: bool,
    pub promo_alerts: bool,
    pub kds_sound_enabled: bool,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct UpdateNotificationPreferencesRequest {
    pub order_updates: Option<bool>,
    pub promo_alerts: Option<bool>,
    pub kds_sound_enabled: Option<bool>,
}

#[derive(Clone)]
pub struct NotificationService {
    pool: PgPool,
}

impl NotificationService {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub fn pool(&self) -> &PgPool {
        &self.pool
    }

    pub async fn list_notifications(
        &self,
        user_id: Uuid,
        unread_only: bool,
        limit: i64,
    ) -> Result<NotificationInboxResponse, DbError> {
        let clamped_limit = limit.clamp(1, 100);
        let mut tx = self.pool.begin().await?;

        let sql = if unread_only {
            r#"
            SELECT id, event_id, title, body, category, reference_id, is_read, delivery_status, created_at
            FROM notification_inbox
            WHERE user_id = $1 AND is_read = FALSE
            ORDER BY created_at DESC
            LIMIT $2
            "#
        } else {
            r#"
            SELECT id, event_id, title, body, category, reference_id, is_read, delivery_status, created_at
            FROM notification_inbox
            WHERE user_id = $1
            ORDER BY created_at DESC
            LIMIT $2
            "#
        };

        let rows = sqlx::query(sql)
            .bind(user_id)
            .bind(clamped_limit)
            .fetch_all(&mut *tx)
            .await?;

        let mut items = Vec::with_capacity(rows.len());
        let mut pending_ids = Vec::new();
        for row in rows {
            let dto = row_to_notification_dto(&row)?;
            if dto.delivery_status == NotificationDeliveryStatus::Pending {
                if let Ok(parsed_id) = Uuid::parse_str(&dto.id) {
                    pending_ids.push(parsed_id);
                }
            }
            items.push(dto);
        }

        if !pending_ids.is_empty() {
            sqlx::query(
                r#"
                UPDATE notification_inbox
                SET delivery_status = 'SYNCED'
                WHERE user_id = $1 AND delivery_status = 'PENDING' AND id = ANY($2)
                "#,
            )
            .bind(user_id)
            .bind(&pending_ids)
            .execute(&mut *tx)
            .await?;

            for item in &mut items {
                if item.delivery_status == NotificationDeliveryStatus::Pending {
                    item.delivery_status = NotificationDeliveryStatus::Synced;
                }
            }
        }

        let unread_count = count_unread_in_tx(&mut tx, user_id).await?;
        tx.commit().await?;

        Ok(NotificationInboxResponse {
            notifications: items,
            unread_count,
            server_now: Utc::now().to_rfc3339(),
        })
    }

    pub async fn mark_as_read(
        &self,
        user_id: Uuid,
        notification_id: Uuid,
    ) -> Result<MarkNotificationReadResponse, DbError> {
        let mut tx = self.pool.begin().await?;
        let updated = sqlx::query(
            r#"
            UPDATE notification_inbox
            SET is_read = TRUE
            WHERE id = $1 AND user_id = $2
            RETURNING id, event_id, title, body, category, reference_id, is_read, delivery_status, created_at
            "#,
        )
        .bind(notification_id)
        .bind(user_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| DbError::NotFound {
            code: "NOTIFICATION_NOT_FOUND",
            message: format!("Notification {notification_id} was not found."),
        })?;

        let notification = row_to_notification_dto(&updated)?;
        let unread_count = count_unread_in_tx(&mut tx, user_id).await?;
        tx.commit().await?;

        Ok(MarkNotificationReadResponse {
            notification,
            unread_count,
        })
    }

    pub async fn mark_all_as_read(
        &self,
        user_id: Uuid,
    ) -> Result<MarkAllNotificationsReadResponse, DbError> {
        let mut tx = self.pool.begin().await?;
        let res = sqlx::query(
            r#"
            UPDATE notification_inbox
            SET is_read = TRUE
            WHERE user_id = $1 AND is_read = FALSE
            "#,
        )
        .bind(user_id)
        .execute(&mut *tx)
        .await?;

        let unread_count = count_unread_in_tx(&mut tx, user_id).await?;
        tx.commit().await?;

        Ok(MarkAllNotificationsReadResponse {
            marked_count: res.rows_affected() as i64,
            unread_count,
        })
    }

    pub async fn get_preferences(
        &self,
        user_id: Uuid,
    ) -> Result<NotificationPreferencesDto, DbError> {
        let mut tx = self.pool.begin().await?;
        ensure_preferences_row_in_tx(&mut tx, user_id).await?;
        let prefs = select_preferences_in_tx(&mut tx, user_id).await?;
        tx.commit().await?;
        Ok(prefs)
    }

    pub async fn update_preferences(
        &self,
        user_id: Uuid,
        request: &UpdateNotificationPreferencesRequest,
    ) -> Result<NotificationPreferencesDto, DbError> {
        let mut tx = self.pool.begin().await?;
        ensure_preferences_row_in_tx(&mut tx, user_id).await?;
        let current = select_preferences_in_tx(&mut tx, user_id).await?;

        let next_order_updates = request.order_updates.unwrap_or(current.order_updates);
        let next_promo_alerts = request.promo_alerts.unwrap_or(current.promo_alerts);
        let next_kds_sound = request
            .kds_sound_enabled
            .unwrap_or(current.kds_sound_enabled);

        let row = sqlx::query(
            r#"
            UPDATE notification_preferences
            SET order_updates = $1,
                promo_alerts = $2,
                kds_sound_enabled = $3,
                updated_at = NOW()
            WHERE user_id = $4
            RETURNING user_id, order_updates, promo_alerts, kds_sound_enabled, updated_at
            "#,
        )
        .bind(next_order_updates)
        .bind(next_promo_alerts)
        .bind(next_kds_sound)
        .bind(user_id)
        .fetch_one(&mut *tx)
        .await?;

        let dto = row_to_preferences_dto(&row)?;
        tx.commit().await?;
        Ok(dto)
    }

    pub async fn is_category_allowed_in_tx(
        conn: &mut PgConnection,
        user_id: Uuid,
        category: &str,
    ) -> Result<bool, DbError> {
        let row = sqlx::query(
            "SELECT order_updates, promo_alerts FROM notification_preferences WHERE user_id = $1",
        )
        .bind(user_id)
        .fetch_optional(&mut *conn)
        .await?;

        let (order_updates, promo_alerts) = match row {
            Some(r) => (r.try_get("order_updates")?, r.try_get("promo_alerts")?),
            None => (true, true),
        };

        let upper = category.to_ascii_uppercase();
        Ok(match upper.as_str() {
            CATEGORY_ORDER_UPDATE => order_updates,
            CATEGORY_PROMO_ALERT => promo_alerts,
            _ => true,
        })
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn insert_inbox_notification_if_allowed_in_tx(
        conn: &mut PgConnection,
        user_id: Uuid,
        event_id: Uuid,
        title: &str,
        body: &str,
        category: &str,
        reference_id: Option<Uuid>,
        delivery_status: NotificationDeliveryStatus,
    ) -> Result<bool, DbError> {
        if !Self::is_category_allowed_in_tx(&mut *conn, user_id, category).await? {
            return Ok(false);
        }

        let res = sqlx::query(
            r#"
            INSERT INTO notification_inbox (
                id, user_id, event_id, title, body, category, reference_id, delivery_status
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
            ON CONFLICT (user_id, event_id) DO NOTHING
            "#,
        )
        .bind(new_uuid_v4())
        .bind(user_id)
        .bind(event_id)
        .bind(title)
        .bind(body)
        .bind(category)
        .bind(reference_id)
        .bind(delivery_status.as_str())
        .execute(&mut *conn)
        .await?;

        Ok(res.rows_affected() > 0)
    }
}

async fn ensure_preferences_row_in_tx(
    conn: &mut PgConnection,
    user_id: Uuid,
) -> Result<(), DbError> {
    sqlx::query("INSERT INTO notification_preferences (user_id) VALUES ($1) ON CONFLICT (user_id) DO NOTHING")
        .bind(user_id)
        .execute(&mut *conn)
        .await?;
    Ok(())
}

async fn select_preferences_in_tx(
    conn: &mut PgConnection,
    user_id: Uuid,
) -> Result<NotificationPreferencesDto, DbError> {
    let row = sqlx::query(
        r#"
        SELECT user_id, order_updates, promo_alerts, kds_sound_enabled, updated_at
        FROM notification_preferences
        WHERE user_id = $1
        "#,
    )
    .bind(user_id)
    .fetch_optional(&mut *conn)
    .await?
    .ok_or_else(|| DbError::NotFound {
        code: "USER_NOT_FOUND",
        message: format!("Notification preferences not found for user {user_id}."),
    })?;
    row_to_preferences_dto(&row)
}

async fn count_unread_in_tx(conn: &mut PgConnection, user_id: Uuid) -> Result<i64, DbError> {
    let count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM notification_inbox WHERE user_id = $1 AND is_read = FALSE",
    )
    .bind(user_id)
    .fetch_one(&mut *conn)
    .await?;
    Ok(count)
}

fn row_to_notification_dto(
    row: &sqlx::postgres::PgRow,
) -> Result<NotificationInboxItemDto, DbError> {
    let id: Uuid = row.try_get("id")?;
    let event_id: Uuid = row.try_get("event_id")?;
    let reference_id: Option<Uuid> = row.try_get("reference_id")?;
    let status_raw: String = row.try_get("delivery_status")?;
    let created_at: DateTime<Utc> = row.try_get("created_at")?;
    Ok(NotificationInboxItemDto {
        id: id.to_string(),
        event_id: event_id.to_string(),
        title: row.try_get("title")?,
        body: row.try_get("body")?,
        category: row.try_get("category")?,
        reference_id: reference_id.map(|u| u.to_string()),
        is_read: row.try_get("is_read")?,
        delivery_status: NotificationDeliveryStatus::from_db_str(&status_raw)?,
        created_at: created_at.to_rfc3339(),
    })
}

fn row_to_preferences_dto(
    row: &sqlx::postgres::PgRow,
) -> Result<NotificationPreferencesDto, DbError> {
    let user_id: Uuid = row.try_get("user_id")?;
    let updated_at: DateTime<Utc> = row.try_get("updated_at")?;
    Ok(NotificationPreferencesDto {
        user_id: user_id.to_string(),
        order_updates: row.try_get("order_updates")?,
        promo_alerts: row.try_get("promo_alerts")?,
        kds_sound_enabled: row.try_get("kds_sound_enabled")?,
        updated_at: updated_at.to_rfc3339(),
    })
}

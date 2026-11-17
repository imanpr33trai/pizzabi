use std::sync::Arc;

use chrono::{Duration, Utc};
use pizzora_core::UserRole;
use sqlx::{PgPool, Row as _};
use tracing::warn;
use uuid::Uuid;

use crate::error::DbError;
use crate::redis::EphemeralCacheStore;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WsTicketPrincipal {
    pub user_id: Uuid,
    pub session_id: Uuid,
    pub role: UserRole,
}

#[derive(Clone)]
pub struct WsTicketRepository {
    pool: PgPool,
    cache: Arc<dyn EphemeralCacheStore>,
    ttl_seconds: i64,
}

impl WsTicketRepository {
    pub fn new(pool: PgPool, cache: Arc<dyn EphemeralCacheStore>, ttl_seconds: i64) -> Self {
        Self {
            pool,
            cache,
            ttl_seconds: ttl_seconds.max(1),
        }
    }

    pub fn ttl_seconds(&self) -> i64 {
        self.ttl_seconds
    }

    pub async fn store_hashed_ticket(
        &self,
        ticket_hash: &str,
        principal: &WsTicketPrincipal,
    ) -> Result<(), DbError> {
        let key = format!("ws_ticket:{ticket_hash}");
        let payload = format!(
            "{}|{}|{}",
            principal.user_id,
            principal.session_id,
            user_role_as_str(principal.role)
        );
        let stored_in_redis = self
            .cache
            .set_ex(&key, self.ttl_seconds as u64, &payload)
            .await;

        if !stored_in_redis {
            warn!("Redis unavailable for ws-ticket; falling back to PostgreSQL ws_tickets table");
            let expires_at = Utc::now() + Duration::seconds(self.ttl_seconds);
            sqlx::query(
                r#"
                INSERT INTO ws_tickets (ticket_hash, user_id, role_code, session_id, expires_at)
                VALUES ($1, $2, $3, $4, $5)
                "#,
            )
            .bind(ticket_hash)
            .bind(principal.user_id)
            .bind(user_role_as_str(principal.role))
            .bind(principal.session_id)
            .bind(expires_at)
            .execute(&self.pool)
            .await?;
        }
        Ok(())
    }

    pub async fn consume_hashed_ticket(
        &self,
        ticket_hash: &str,
    ) -> Result<Option<WsTicketPrincipal>, DbError> {
        if ticket_hash.is_empty() {
            return Ok(None);
        }

        let key = format!("ws_ticket:{ticket_hash}");
        if let Some(redis_payload) = self.cache.get_del(&key).await {
            let mut parts = redis_payload.split('|');
            let Some(user_str) = parts.next() else {
                return Ok(None);
            };
            let Some(session_str) = parts.next() else {
                return Ok(None);
            };
            let Some(role_str) = parts.next() else {
                return Ok(None);
            };
            if parts.next().is_some() {
                return Ok(None);
            }

            let Ok(user_id) = Uuid::parse_str(user_str) else {
                return Ok(None);
            };
            let Ok(session_id) = Uuid::parse_str(session_str) else {
                return Ok(None);
            };
            let Some(role) = parse_user_role(role_str) else {
                return Ok(None);
            };

            let active: Option<i32> = sqlx::query_scalar(
                r#"
                SELECT 1
                FROM user_sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.id = $1 AND s.user_id = $2 AND s.revoked_at IS NULL AND u.is_active = TRUE
                "#,
            )
            .bind(session_id)
            .bind(user_id)
            .fetch_optional(&self.pool)
            .await?;

            return Ok(active.map(|_| WsTicketPrincipal {
                user_id,
                session_id,
                role,
            }));
        }

        let row = sqlx::query(
            r#"
            UPDATE ws_tickets t
            SET consumed_at = NOW()
            FROM user_sessions s, users u
            WHERE t.ticket_hash = $1
              AND t.consumed_at IS NULL
              AND t.expires_at > NOW()
              AND s.id = t.session_id
              AND s.user_id = t.user_id
              AND s.revoked_at IS NULL
              AND u.id = t.user_id
              AND u.is_active = TRUE
            RETURNING t.user_id, t.session_id, t.role_code
            "#,
        )
        .bind(ticket_hash)
        .fetch_optional(&self.pool)
        .await?;

        let Some(r) = row else {
            return Ok(None);
        };
        let role_raw: String = r.try_get("role_code")?;
        let Some(role) = parse_user_role(&role_raw) else {
            return Ok(None);
        };

        Ok(Some(WsTicketPrincipal {
            user_id: r.try_get("user_id")?,
            session_id: r.try_get("session_id")?,
            role,
        }))
    }
}

pub const fn user_role_as_str(role: UserRole) -> &'static str {
    match role {
        UserRole::Customer => "CUSTOMER",
        UserRole::KitchenStaff => "KITCHEN_STAFF",
        UserRole::Owner => "OWNER",
    }
}

pub fn parse_user_role(raw: &str) -> Option<UserRole> {
    match raw {
        "CUSTOMER" => Some(UserRole::Customer),
        "KITCHEN_STAFF" => Some(UserRole::KitchenStaff),
        "OWNER" => Some(UserRole::Owner),
        _ => None,
    }
}

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine as _;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{PgConnection, PgPool, Postgres, QueryBuilder, Row as _};
use uuid::Uuid;

use crate::error::{new_uuid_v4, DbError};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuditContext {
    pub actor_user_id: Option<Uuid>,
    pub actor_role: String,
    pub correlation_id: String,
    pub ip_address: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuditLogEntryDto {
    pub id: String,
    #[serde(default)]
    pub actor_user_id: Option<String>,
    #[serde(default)]
    pub actor_email: Option<String>,
    #[serde(default)]
    pub actor_name: Option<String>,
    pub actor_role: String,
    pub action: String,
    pub entity_type: String,
    pub entity_id: String,
    #[serde(default)]
    pub before_json: Option<String>,
    #[serde(default)]
    pub after_json: Option<String>,
    pub correlation_id: String,
    #[serde(default)]
    pub ip_address: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuditLogsResponse {
    pub entries: Vec<AuditLogEntryDto>,
    #[serde(default)]
    pub next_cursor: Option<String>,
    pub server_now: String,
}

pub async fn record_audit_log(
    conn: &mut PgConnection,
    context: &AuditContext,
    action: &str,
    entity_type: &str,
    entity_id: &str,
    before_json: Option<&serde_json::Value>,
    after_json: Option<&serde_json::Value>,
) -> Result<(), DbError> {
    sqlx::query(
        r#"
        INSERT INTO audit_logs (
            id, actor_user_id, actor_role, action, entity_type, entity_id,
            before_json, after_json, correlation_id, ip_address
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
        "#,
    )
    .bind(new_uuid_v4())
    .bind(context.actor_user_id)
    .bind(&context.actor_role)
    .bind(action)
    .bind(entity_type)
    .bind(entity_id)
    .bind(before_json)
    .bind(after_json)
    .bind(&context.correlation_id)
    .bind(context.ip_address.as_deref())
    .execute(&mut *conn)
    .await?;

    Ok(())
}

#[derive(Clone)]
pub struct AuditService {
    pool: PgPool,
}

impl AuditService {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub fn pool(&self) -> &PgPool {
        &self.pool
    }

    pub async fn list_audit_logs(
        &self,
        entity_type: Option<&str>,
        action: Option<&str>,
        entity_id: Option<&str>,
        cursor: Option<&str>,
        limit: i64,
    ) -> Result<AuditLogsResponse, DbError> {
        let clamped_limit = limit.clamp(1, 100);
        let clean_entity_type = entity_type.map(str::trim).filter(|s| !s.is_empty());
        let clean_action = action.map(str::trim).filter(|s| !s.is_empty());
        let clean_entity_id = entity_id.map(str::trim).filter(|s| !s.is_empty());
        let decoded_cursor = match cursor.map(str::trim).filter(|s| !s.is_empty()) {
            Some(c) => Some(decode_cursor(c)?),
            None => None,
        };

        let mut qb: QueryBuilder<'_, Postgres> = QueryBuilder::new(
            "SELECT a.id, a.actor_user_id, u.email AS actor_email, u.full_name AS actor_name, \
             a.actor_role, a.action, a.entity_type, a.entity_id, \
             a.before_json::text AS before_json, a.after_json::text AS after_json, \
             a.correlation_id, a.ip_address, a.created_at \
             FROM audit_logs a LEFT JOIN users u ON u.id = a.actor_user_id",
        );

        let mut has_where = false;
        let mut push_cond = |qb: &mut QueryBuilder<'_, Postgres>| {
            if has_where {
                qb.push(" AND ");
            } else {
                qb.push(" WHERE ");
                has_where = true;
            }
        };

        if let Some(et) = clean_entity_type {
            push_cond(&mut qb);
            qb.push("LOWER(a.entity_type) = LOWER(")
                .push_bind(et)
                .push(")");
        }
        if let Some(act) = clean_action {
            push_cond(&mut qb);
            qb.push("LOWER(a.action) = LOWER(")
                .push_bind(act)
                .push(")");
        }
        if let Some(eid) = clean_entity_id {
            push_cond(&mut qb);
            qb.push("a.entity_id = ").push_bind(eid);
        }
        if let Some(cur) = &decoded_cursor {
            push_cond(&mut qb);
            qb.push("(a.created_at, a.id) < (")
                .push_bind(cur.created_at)
                .push(", ")
                .push_bind(cur.id)
                .push(")");
        }

        qb.push(" ORDER BY a.created_at DESC, a.id DESC LIMIT ")
            .push_bind(clamped_limit + 1);

        let rows = qb.build().fetch_all(&self.pool).await?;
        let mut parsed = Vec::with_capacity(rows.len());
        for row in rows {
            let id: Uuid = row.try_get("id")?;
            let actor_user_id: Option<Uuid> = row.try_get("actor_user_id")?;
            let created_at: DateTime<Utc> = row.try_get("created_at")?;
            parsed.push((
                created_at,
                id,
                AuditLogEntryDto {
                    id: id.to_string(),
                    actor_user_id: actor_user_id.map(|u| u.to_string()),
                    actor_email: row.try_get("actor_email")?,
                    actor_name: row.try_get("actor_name")?,
                    actor_role: row.try_get("actor_role")?,
                    action: row.try_get("action")?,
                    entity_type: row.try_get("entity_type")?,
                    entity_id: row.try_get("entity_id")?,
                    before_json: row.try_get("before_json")?,
                    after_json: row.try_get("after_json")?,
                    correlation_id: row.try_get("correlation_id")?,
                    ip_address: row.try_get("ip_address")?,
                    created_at: created_at.to_rfc3339(),
                },
            ));
        }

        let has_more = parsed.len() > clamped_limit as usize;
        if has_more {
            parsed.truncate(clamped_limit as usize);
        }
        let next_cursor = if has_more {
            parsed
                .last()
                .map(|(ts, id, _)| encode_cursor(*ts, *id))
        } else {
            None
        };

        let entries = parsed.into_iter().map(|(_, _, dto)| dto).collect();
        Ok(AuditLogsResponse {
            entries,
            next_cursor,
            server_now: Utc::now().to_rfc3339(),
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuditCursor {
    pub created_at: DateTime<Utc>,
    pub id: Uuid,
}

pub fn encode_cursor(created_at: DateTime<Utc>, id: Uuid) -> String {
    let raw = format!("{}|{}", created_at.to_rfc3339(), id);
    URL_SAFE_NO_PAD.encode(raw.as_bytes())
}

pub fn decode_cursor(encoded: &str) -> Result<AuditCursor, DbError> {
    let bytes = URL_SAFE_NO_PAD.decode(encoded).map_err(|_| DbError::Validation {
        code: "INVALID_CURSOR",
        message: "Invalid pagination cursor for audit logs.".to_owned(),
    })?;
    let text = std::str::from_utf8(&bytes).map_err(|_| DbError::Validation {
        code: "INVALID_CURSOR",
        message: "Invalid pagination cursor for audit logs.".to_owned(),
    })?;
    let (ts_raw, id_raw) = text.split_once('|').ok_or_else(|| DbError::Validation {
        code: "INVALID_CURSOR",
        message: "Invalid pagination cursor for audit logs.".to_owned(),
    })?;
    let created_at = DateTime::parse_from_rfc3339(ts_raw)
        .map_err(|_| DbError::Validation {
            code: "INVALID_CURSOR",
            message: "Invalid pagination cursor for audit logs.".to_owned(),
        })?
        .with_timezone(&Utc);
    let id = Uuid::parse_str(id_raw).map_err(|_| DbError::Validation {
        code: "INVALID_CURSOR",
        message: "Invalid pagination cursor for audit logs.".to_owned(),
    })?;
    Ok(AuditCursor { created_at, id })
}

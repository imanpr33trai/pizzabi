use std::collections::BTreeSet;
use std::sync::Arc;

use pizzora_core::UserRole;
use pizzora_db::redis::EphemeralCacheStore;
use pizzora_db::user_role_as_str;
use sqlx::{PgPool, Row as _};
use tracing::warn;
use uuid::Uuid;

use crate::error::AuthError;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PizzoraPrincipal {
    pub user_id: Uuid,
    pub session_id: Uuid,
    pub role: UserRole,
}

#[derive(Clone)]
pub struct AuthorizationService {
    pool: PgPool,
    cache: Arc<dyn EphemeralCacheStore>,
}

impl AuthorizationService {
    pub fn new(pool: PgPool, cache: Arc<dyn EphemeralCacheStore>) -> Self {
        Self { pool, cache }
    }

    pub async fn get_permissions(&self, role: UserRole) -> Result<BTreeSet<String>, AuthError> {
        let role_name = user_role_as_str(role);
        let cache_key = format!("rbac:perms:{role_name}");

        if let Some(cached) = self.cache.get(&cache_key).await {
            let parsed: BTreeSet<String> = cached
                .split(',')
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_owned)
                .collect();
            if !parsed.is_empty() {
                return Ok(parsed);
            }
        }

        let rows = sqlx::query(
            r#"
            SELECT p.code
            FROM role_permissions rp
            JOIN roles r ON r.id = rp.role_id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE r.code = $1
            ORDER BY p.code ASC
            "#,
        )
        .bind(role_name)
        .fetch_all(&self.pool)
        .await?;

        let mut permissions = BTreeSet::new();
        for row in rows {
            let code: String = row.try_get("code")?;
            permissions.insert(code);
        }

        if !permissions.is_empty() {
            let joined = permissions
                .iter()
                .map(String::as_str)
                .collect::<Vec<_>>()
                .join(",");
            if !self.cache.set_ex(&cache_key, 300, &joined).await {
                warn!(
                    role = role_name,
                    "Redis unavailable while caching RBAC permissions"
                );
            }
        }

        Ok(permissions)
    }

    pub async fn has_permission(
        &self,
        role: UserRole,
        permission: &str,
    ) -> Result<bool, AuthError> {
        let perms = self.get_permissions(role).await?;
        Ok(perms.contains(permission))
    }

    pub async fn require_permission(
        &self,
        role: UserRole,
        permission: &str,
    ) -> Result<(), AuthError> {
        if !self.has_permission(role, permission).await? {
            return Err(AuthError::Forbidden {
                code: "MISSING_PERMISSION",
                message: format!(
                    "Role {} lacks required permission {permission}.",
                    user_role_as_str(role)
                ),
            });
        }
        Ok(())
    }

    pub async fn is_session_active(
        &self,
        user_id: Uuid,
        session_id: Uuid,
    ) -> Result<bool, AuthError> {
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

        Ok(active.is_some())
    }
}

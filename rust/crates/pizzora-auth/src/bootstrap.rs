use pizzora_db::new_uuid_v4;
use sqlx::PgPool;
use tracing::info;
use uuid::Uuid;

use crate::error::AuthError;
use crate::password::PasswordHasher;

pub struct BootstrapOwner;

impl BootstrapOwner {
    pub async fn bootstrap(
        pool: &PgPool,
        password_hasher: &PasswordHasher,
        email: &str,
        password: &str,
        full_name: &str,
        phone: &str,
        employee_code: Option<&str>,
    ) -> Result<Uuid, AuthError> {
        let normalized_email = email.trim().to_ascii_lowercase();
        if !normalized_email.contains('@')
            || !normalized_email
                .split_once('@')
                .is_some_and(|(_, domain)| domain.contains('.'))
        {
            return Err(AuthError::Validation {
                code: "INVALID_OWNER_EMAIL",
                message: "Valid OWNER_EMAIL is required.".to_owned(),
            });
        }
        if password.chars().count() < 8 {
            return Err(AuthError::Validation {
                code: "WEAK_OWNER_PASSWORD",
                message: "OWNER_PASSWORD must be at least 8 characters.".to_owned(),
            });
        }
        let clean_name = full_name.trim();
        if clean_name.is_empty() {
            return Err(AuthError::Validation {
                code: "INVALID_OWNER_NAME",
                message: "OWNER_NAME is required.".to_owned(),
            });
        }
        let clean_phone = phone.trim();
        let clean_code = employee_code.unwrap_or("OWN-001").trim();
        let password_hash = password_hasher.hash(password)?;

        let mut tx = pool.begin().await?;

        let owner_role_id: Uuid = sqlx::query_scalar("SELECT id FROM roles WHERE code = 'OWNER'")
            .fetch_optional(&mut *tx)
            .await?
            .ok_or_else(|| {
                AuthError::Config("OWNER role not seeded. Run migrations first.".to_owned())
            })?;

        let existing_user_id: Option<Uuid> =
            sqlx::query_scalar("SELECT id FROM users WHERE email = $1")
                .bind(&normalized_email)
                .fetch_optional(&mut *tx)
                .await?;

        let user_id = existing_user_id.unwrap_or_else(new_uuid_v4);
        if existing_user_id.is_none() {
            sqlx::query(
                r#"
                INSERT INTO users (id, email, email_verified_at, password_hash, full_name, phone, is_active)
                VALUES ($1, $2, NOW(), $3, $4, $5, TRUE)
                "#,
            )
            .bind(user_id)
            .bind(&normalized_email)
            .bind(&password_hash)
            .bind(clean_name)
            .bind(clean_phone)
            .execute(&mut *tx)
            .await?;
        } else {
            sqlx::query(
                r#"
                UPDATE users
                SET email_verified_at = COALESCE(email_verified_at, NOW()),
                    password_hash = $1,
                    full_name = $2,
                    phone = $3,
                    is_active = TRUE,
                    updated_at = NOW()
                WHERE id = $4
                "#,
            )
            .bind(&password_hash)
            .bind(clean_name)
            .bind(clean_phone)
            .bind(user_id)
            .execute(&mut *tx)
            .await?;
        }

        sqlx::query(
            r#"
            INSERT INTO staff (user_id, role_id, employee_code, is_active, created_by)
            VALUES ($1, $2, $3, TRUE, $4)
            ON CONFLICT (user_id) DO UPDATE
            SET role_id = EXCLUDED.role_id,
                is_active = TRUE
            "#,
        )
        .bind(user_id)
        .bind(owner_role_id)
        .bind(clean_code)
        .bind(user_id)
        .execute(&mut *tx)
        .await?;

        tx.commit().await?;
        info!(user_id = %user_id, "Bootstrapped OWNER account");
        Ok(user_id)
    }
}

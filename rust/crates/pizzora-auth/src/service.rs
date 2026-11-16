use chrono::{DateTime, Duration, Utc};
use pizzora_config::{AuthConfig, OutboxEncryptionConfig};
use pizzora_core::UserRole;
use pizzora_db::{
    new_uuid_v4, parse_user_role, AuditContext, DbError, OutboxRepository, StaffMemberDto,
    UserProfileDto, UsersService, EVENT_EMAIL_DELIVERY_REQUESTED,
};
use serde::{Deserialize, Serialize};
use sqlx::{PgPool, Row as _};
use tracing::{info, warn};
use uuid::Uuid;

use crate::error::AuthError;
use crate::jwt::JwtTokenProvider;
use crate::outbox_crypto::{seal_outbox_email_payload, EmailTemplateKind};
use crate::password::{CryptoTokens, PasswordHasher};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RegisterRequest {
    pub email: String,
    pub password: String,
    pub full_name: String,
    pub phone: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VerifyEmailRequest {
    pub token: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ResendVerificationRequest {
    pub email: String,
}

fn default_device_name() -> String {
    "Android".to_owned()
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LoginRequest {
    pub email: String,
    pub password: String,
    #[serde(default = "default_device_name")]
    pub device_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RefreshTokenRequest {
    pub refresh_token: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ForgotPasswordRequest {
    pub email: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ResetPasswordRequest {
    pub token: String,
    pub new_password: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CreateStaffRequest {
    pub email: String,
    pub password: String,
    pub full_name: String,
    pub phone: String,
    pub role: UserRole,
    pub employee_code: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuthTokensResponse {
    pub access_token: String,
    pub refresh_token: String,
    pub expires_in_seconds: i64,
    pub user: UserProfileDto,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MessageResponse {
    pub message: String,
}

#[derive(Clone)]
pub struct AuthService {
    pool: PgPool,
    auth_config: AuthConfig,
    outbox_crypto: OutboxEncryptionConfig,
    password_hasher: PasswordHasher,
    jwt_provider: JwtTokenProvider,
}

impl AuthService {
    pub fn new(
        pool: PgPool,
        auth_config: AuthConfig,
        outbox_crypto: OutboxEncryptionConfig,
        password_hasher: PasswordHasher,
    ) -> Self {
        let jwt_provider = JwtTokenProvider::new(&auth_config);
        Self {
            pool,
            auth_config,
            outbox_crypto,
            password_hasher,
            jwt_provider,
        }
    }

    pub fn jwt_provider(&self) -> &JwtTokenProvider {
        &self.jwt_provider
    }

    pub fn password_hasher(&self) -> &PasswordHasher {
        &self.password_hasher
    }

    pub async fn register(&self, request: &RegisterRequest) -> Result<MessageResponse, AuthError> {
        let email = normalize_email(&request.email);
        validate_email(&email)?;
        validate_password(&request.password)?;
        let full_name = request.full_name.trim();
        if full_name.is_empty() {
            return Err(AuthError::Validation {
                code: "INVALID_NAME",
                message: "Full name is required.".to_owned(),
            });
        }
        let phone = request.phone.trim();
        if phone.chars().count() < 7 {
            return Err(AuthError::Validation {
                code: "INVALID_PHONE",
                message: "A valid phone number is required.".to_owned(),
            });
        }

        let password_hash = self.password_hasher.hash(&request.password)?;
        let raw_verify_token = CryptoTokens::generate_opaque_token(32);
        let token_hash = CryptoTokens::sha256_hex(&raw_verify_token);
        let token_expires_at = Utc::now() + Duration::hours(24);
        let user_id = new_uuid_v4();
        let token_record_id = new_uuid_v4();
        let outbox_event_id = new_uuid_v4();

        let sealed_payload = seal_outbox_email_payload(
            &self.outbox_crypto,
            outbox_event_id,
            user_id,
            EVENT_EMAIL_DELIVERY_REQUESTED,
            EmailTemplateKind::Verification,
            token_record_id,
            token_expires_at,
            &email,
            full_name,
            &raw_verify_token,
        )?;
        let payload_json = serde_json::to_value(&sealed_payload).map_err(|err| {
            AuthError::Crypto(format!("failed to encode sealed email payload: {err}"))
        })?;

        let mut tx = self.pool.begin().await?;
        let exists: Option<i32> = sqlx::query_scalar("SELECT 1 FROM users WHERE email = $1")
            .bind(&email)
            .fetch_optional(&mut *tx)
            .await?;
        if exists.is_some() {
            return Err(AuthError::Conflict {
                code: "EMAIL_ALREADY_EXISTS",
                message: "An account with this email already exists.".to_owned(),
            });
        }

        let insert_res = sqlx::query(
            r#"
            INSERT INTO users (id, email, password_hash, full_name, phone)
            VALUES ($1, $2, $3, $4, $5)
            "#,
        )
        .bind(user_id)
        .bind(&email)
        .bind(&password_hash)
        .bind(full_name)
        .bind(phone)
        .execute(&mut *tx)
        .await;

        if let Err(err) = insert_res {
            if DbError::is_unique_violation(&err) {
                return Err(AuthError::Conflict {
                    code: "EMAIL_ALREADY_EXISTS",
                    message: "An account with this email already exists.".to_owned(),
                });
            }
            return Err(AuthError::Sqlx(err));
        }

        sqlx::query(
            r#"
            INSERT INTO email_verification_tokens (id, user_id, token_hash, expires_at)
            VALUES ($1, $2, $3, $4)
            "#,
        )
        .bind(token_record_id)
        .bind(user_id)
        .bind(&token_hash)
        .bind(token_expires_at)
        .execute(&mut *tx)
        .await?;

        OutboxRepository::insert_outbox_event_with_id(
            &mut tx,
            outbox_event_id,
            "user",
            user_id,
            1,
            EVENT_EMAIL_DELIVERY_REQUESTED,
            &payload_json,
        )
        .await?;

        tx.commit().await?;
        info!(
            user_id = %user_id,
            outbox_event_id = %outbox_event_id,
            token_record_id = %token_record_id,
            "Registered user and queued sealed verification email outbox event"
        );
        Ok(MessageResponse {
            message: "Registration successful. Please verify your email.".to_owned(),
        })
    }

    pub async fn verify_email(
        &self,
        request: &VerifyEmailRequest,
    ) -> Result<MessageResponse, AuthError> {
        let trimmed = request.token.trim();
        if trimmed.is_empty() {
            return Err(AuthError::Validation {
                code: "INVALID_TOKEN",
                message: "Verification token is required.".to_owned(),
            });
        }
        let token_hash = CryptoTokens::sha256_hex(trimmed);

        let mut tx = self.pool.begin().await?;
        let user_id: Option<Uuid> = sqlx::query_scalar(
            r#"
            UPDATE email_verification_tokens
            SET used_at = NOW()
            WHERE token_hash = $1 AND used_at IS NULL AND expires_at > NOW()
            RETURNING user_id
            "#,
        )
        .bind(&token_hash)
        .fetch_optional(&mut *tx)
        .await?;

        let Some(uid) = user_id else {
            warn!("Blocked invalid, expired, or already-used email verification token");
            return Err(AuthError::Validation {
                code: "INVALID_OR_EXPIRED_TOKEN",
                message: "Verification token is invalid, expired, or already used.".to_owned(),
            });
        };

        sqlx::query(
            r#"
            UPDATE users
            SET email_verified_at = COALESCE(email_verified_at, NOW()), updated_at = NOW()
            WHERE id = $1
            "#,
        )
        .bind(uid)
        .execute(&mut *tx)
        .await?;

        tx.commit().await?;
        Ok(MessageResponse {
            message: "Email verified successfully.".to_owned(),
        })
    }

    pub async fn resend_verification(
        &self,
        request: &ResendVerificationRequest,
    ) -> Result<MessageResponse, AuthError> {
        let email = normalize_email(&request.email);
        let mut tx = self.pool.begin().await?;

        let row = sqlx::query(
            "SELECT id, full_name, email_verified_at, is_active FROM users WHERE email = $1",
        )
        .bind(&email)
        .fetch_optional(&mut *tx)
        .await?;

        if let Some(user_row) = row {
            let email_verified_at: Option<DateTime<Utc>> =
                user_row.try_get("email_verified_at")?;
            let is_active: bool = user_row.try_get("is_active")?;
            if email_verified_at.is_none() && is_active {
                let user_id: Uuid = user_row.try_get("id")?;
                let full_name: String = user_row.try_get("full_name")?;
                let raw_token = CryptoTokens::generate_opaque_token(32);
                let token_hash = CryptoTokens::sha256_hex(&raw_token);
                let token_expires_at = Utc::now() + Duration::hours(24);
                let token_record_id = new_uuid_v4();
                let outbox_event_id = new_uuid_v4();

                let sealed_payload = seal_outbox_email_payload(
                    &self.outbox_crypto,
                    outbox_event_id,
                    user_id,
                    EVENT_EMAIL_DELIVERY_REQUESTED,
                    EmailTemplateKind::Verification,
                    token_record_id,
                    token_expires_at,
                    &email,
                    &full_name,
                    &raw_token,
                )?;
                let payload_json = serde_json::to_value(&sealed_payload).map_err(|err| {
                    AuthError::Crypto(format!("failed to encode sealed email payload: {err}"))
                })?;

                let next_version = next_user_outbox_version(&mut tx, user_id).await?;

                sqlx::query(
                    r#"
                    INSERT INTO email_verification_tokens (id, user_id, token_hash, expires_at)
                    VALUES ($1, $2, $3, $4)
                    "#,
                )
                .bind(token_record_id)
                .bind(user_id)
                .bind(&token_hash)
                .bind(token_expires_at)
                .execute(&mut *tx)
                .await?;

                OutboxRepository::insert_outbox_event_with_id(
                    &mut tx,
                    outbox_event_id,
                    "user",
                    user_id,
                    next_version,
                    EVENT_EMAIL_DELIVERY_REQUESTED,
                    &payload_json,
                )
                .await?;

                tx.commit().await?;
                info!(
                    user_id = %user_id,
                    outbox_event_id = %outbox_event_id,
                    token_record_id = %token_record_id,
                    "Queued sealed verification resend outbox event"
                );
                return Ok(MessageResponse {
                    message: "If the account exists and is unverified, a verification email has been sent.".to_owned(),
                });
            }
        }

        tx.commit().await?;
        Ok(MessageResponse {
            message: "If the account exists and is unverified, a verification email has been sent."
                .to_owned(),
        })
    }

    pub async fn login(
        &self,
        request: &LoginRequest,
        ip_address: Option<&str>,
    ) -> Result<AuthTokensResponse, AuthError> {
        let email = normalize_email(&request.email);
        let row = sqlx::query(
            r#"
            SELECT u.id, u.email, u.email_verified_at, u.password_hash, u.full_name, u.phone, u.is_active,
                   r.code AS staff_role
            FROM users u
            LEFT JOIN staff s ON s.user_id = u.id AND s.is_active = TRUE
            LEFT JOIN roles r ON r.id = s.role_id
            WHERE u.email = $1
            "#,
        )
        .bind(&email)
        .fetch_optional(&self.pool)
        .await?;

        let Some(user_row) = row else {
            warn!(ip = ?ip_address, "Blocked login for unknown account");
            return Err(AuthError::Unauthorized {
                code: "INVALID_CREDENTIALS",
                message: "Invalid email or password.".to_owned(),
            });
        };

        let user_id: Uuid = user_row.try_get("id")?;
        let user_email: String = user_row.try_get("email")?;
        let email_verified = user_row
            .try_get::<Option<DateTime<Utc>>, _>("email_verified_at")?
            .is_some();
        let password_hash: String = user_row.try_get("password_hash")?;
        let full_name: String = user_row.try_get("full_name")?;
        let phone: String = user_row.try_get("phone")?;
        let is_active: bool = user_row.try_get("is_active")?;
        let staff_role_raw: Option<String> = user_row.try_get("staff_role")?;
        let role = match staff_role_raw.as_deref() {
            Some(code) => parse_user_role(code).ok_or_else(|| {
                AuthError::Config(format!("Unsupported role '{code}' stored in DB"))
            })?,
            None => UserRole::Customer,
        };

        if !self
            .password_hasher
            .verify(&request.password, &password_hash)
        {
            warn!(user_id = %user_id, ip = ?ip_address, "Blocked login with invalid password");
            return Err(AuthError::Unauthorized {
                code: "INVALID_CREDENTIALS",
                message: "Invalid email or password.".to_owned(),
            });
        }

        if !is_active {
            warn!(user_id = %user_id, ip = ?ip_address, "Blocked login for deactivated account");
            return Err(AuthError::Forbidden {
                code: "ACCOUNT_DISABLED",
                message: "This account has been deactivated.".to_owned(),
            });
        }

        if !email_verified {
            warn!(user_id = %user_id, ip = ?ip_address, "Blocked login for unverified email");
            return Err(AuthError::Forbidden {
                code: "EMAIL_NOT_VERIFIED",
                message: "Please verify your email address before signing in.".to_owned(),
            });
        }

        let session_id = new_uuid_v4();
        let family_id = new_uuid_v4();
        let raw_refresh_token = CryptoTokens::generate_opaque_token(32);
        let refresh_hash = CryptoTokens::sha256_hex(&raw_refresh_token);
        let now = Utc::now();
        let refresh_expires_at = now + Duration::days(self.auth_config.refresh_token_ttl_days);
        let device_name: String = request.device_name.chars().take(100).collect();

        let mut tx = self.pool.begin().await?;
        sqlx::query(
            r#"
            INSERT INTO user_sessions (id, user_id, device_name, ip_address)
            VALUES ($1, $2, $3, $4)
            "#,
        )
        .bind(session_id)
        .bind(user_id)
        .bind(&device_name)
        .bind(ip_address)
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            r#"
            INSERT INTO refresh_tokens (id, session_id, family_id, token_hash, expires_at)
            VALUES ($1, $2, $3, $4, $5)
            "#,
        )
        .bind(new_uuid_v4())
        .bind(session_id)
        .bind(family_id)
        .bind(&refresh_hash)
        .bind(refresh_expires_at)
        .execute(&mut *tx)
        .await?;

        tx.commit().await?;

        let access_token = self
            .jwt_provider
            .create_access_token_at(user_id, session_id, role, now)?;

        Ok(AuthTokensResponse {
            access_token,
            refresh_token: raw_refresh_token,
            expires_in_seconds: self.auth_config.access_token_ttl_seconds,
            user: UserProfileDto {
                id: user_id.to_string(),
                email: user_email,
                email_verified: true,
                full_name,
                phone,
                role,
                is_active,
            },
        })
    }

    pub async fn refresh(
        &self,
        request: &RefreshTokenRequest,
    ) -> Result<AuthTokensResponse, AuthError> {
        let trimmed = request.refresh_token.trim();
        if trimmed.is_empty() {
            return Err(AuthError::Unauthorized {
                code: "INVALID_REFRESH_TOKEN",
                message: "Refresh token is required.".to_owned(),
            });
        }
        let incoming_hash = CryptoTokens::sha256_hex(trimmed);
        let now = Utc::now();

        enum RefreshOutcome {
            NotFound,
            Reused,
            Expired,
            Success(AuthTokensResponse),
        }

        let mut tx = self.pool.begin().await?;
        let row = sqlx::query(
            r#"
            SELECT rt.id, rt.session_id, rt.family_id, rt.expires_at, rt.rotated_at, rt.revoked_at AS token_revoked_at,
                   us.revoked_at AS session_revoked_at,
                   u.id AS user_id, u.email, u.email_verified_at, u.full_name, u.phone, u.is_active,
                   r.code AS staff_role
            FROM refresh_tokens rt
            JOIN user_sessions us ON us.id = rt.session_id
            JOIN users u ON u.id = us.user_id
            LEFT JOIN staff st ON st.user_id = u.id AND st.is_active = TRUE
            LEFT JOIN roles r ON r.id = st.role_id
            WHERE rt.token_hash = $1
            FOR UPDATE OF rt, us
            "#,
        )
        .bind(&incoming_hash)
        .fetch_optional(&mut *tx)
        .await?;

        let outcome = match row {
            None => RefreshOutcome::NotFound,
            Some(r) => {
                let token_id: Uuid = r.try_get("id")?;
                let session_id: Uuid = r.try_get("session_id")?;
                let family_id: Uuid = r.try_get("family_id")?;
                let expires_at: DateTime<Utc> = r.try_get("expires_at")?;
                let rotated_at: Option<DateTime<Utc>> = r.try_get("rotated_at")?;
                let token_revoked_at: Option<DateTime<Utc>> = r.try_get("token_revoked_at")?;
                let session_revoked_at: Option<DateTime<Utc>> = r.try_get("session_revoked_at")?;
                let user_id: Uuid = r.try_get("user_id")?;
                let email: String = r.try_get("email")?;
                let email_verified = r
                    .try_get::<Option<DateTime<Utc>>, _>("email_verified_at")?
                    .is_some();
                let full_name: String = r.try_get("full_name")?;
                let phone: String = r.try_get("phone")?;
                let is_active: bool = r.try_get("is_active")?;
                let staff_role_raw: Option<String> = r.try_get("staff_role")?;
                let role = match staff_role_raw.as_deref() {
                    Some(code) => parse_user_role(code).ok_or_else(|| {
                        AuthError::Config(format!("Unsupported role '{code}' stored in DB"))
                    })?,
                    None => UserRole::Customer,
                };

                if rotated_at.is_some() || token_revoked_at.is_some() {
                    warn!(
                        family_id = %family_id,
                        session_id = %session_id,
                        user_id = %user_id,
                        "Refresh token reuse detected! Revoking token family and session"
                    );
                    sqlx::query(
                        "UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, NOW()) WHERE family_id = $1",
                    )
                    .bind(family_id)
                    .execute(&mut *tx)
                    .await?;
                    sqlx::query(
                        "UPDATE user_sessions SET revoked_at = COALESCE(revoked_at, NOW()) WHERE id = $1",
                    )
                    .bind(session_id)
                    .execute(&mut *tx)
                    .await?;
                    RefreshOutcome::Reused
                } else if session_revoked_at.is_some() || !is_active || !email_verified {
                    RefreshOutcome::NotFound
                } else if expires_at <= now {
                    RefreshOutcome::Expired
                } else {
                    sqlx::query("UPDATE refresh_tokens SET rotated_at = NOW() WHERE id = $1")
                        .bind(token_id)
                        .execute(&mut *tx)
                        .await?;

                    let next_raw_refresh = CryptoTokens::generate_opaque_token(32);
                    let next_refresh_hash = CryptoTokens::sha256_hex(&next_raw_refresh);
                    let next_expires_at =
                        now + Duration::days(self.auth_config.refresh_token_ttl_days);

                    sqlx::query(
                        r#"
                        INSERT INTO refresh_tokens (id, session_id, family_id, token_hash, expires_at)
                        VALUES ($1, $2, $3, $4, $5)
                        "#,
                    )
                    .bind(new_uuid_v4())
                    .bind(session_id)
                    .bind(family_id)
                    .bind(&next_refresh_hash)
                    .bind(next_expires_at)
                    .execute(&mut *tx)
                    .await?;

                    sqlx::query("UPDATE user_sessions SET last_seen_at = NOW() WHERE id = $1")
                        .bind(session_id)
                        .execute(&mut *tx)
                        .await?;

                    let access_token =
                        self.jwt_provider
                            .create_access_token_at(user_id, session_id, role, now)?;
                    RefreshOutcome::Success(AuthTokensResponse {
                        access_token,
                        refresh_token: next_raw_refresh,
                        expires_in_seconds: self.auth_config.access_token_ttl_seconds,
                        user: UserProfileDto {
                            id: user_id.to_string(),
                            email,
                            email_verified,
                            full_name,
                            phone,
                            role,
                            is_active,
                        },
                    })
                }
            }
        };

        tx.commit().await?;

        match outcome {
            RefreshOutcome::NotFound => Err(AuthError::Unauthorized {
                code: "INVALID_REFRESH_TOKEN",
                message: "Invalid or revoked refresh token.".to_owned(),
            }),
            RefreshOutcome::Reused => Err(AuthError::Unauthorized {
                code: "REFRESH_TOKEN_REUSED",
                message: "Refresh token reuse detected. Session revoked.".to_owned(),
            }),
            RefreshOutcome::Expired => Err(AuthError::Unauthorized {
                code: "REFRESH_TOKEN_EXPIRED",
                message: "Refresh token has expired.".to_owned(),
            }),
            RefreshOutcome::Success(resp) => Ok(resp),
        }
    }

    pub async fn logout(&self, session_id: Uuid) -> Result<MessageResponse, AuthError> {
        let mut tx = self.pool.begin().await?;
        sqlx::query(
            "UPDATE user_sessions SET revoked_at = COALESCE(revoked_at, NOW()) WHERE id = $1",
        )
        .bind(session_id)
        .execute(&mut *tx)
        .await?;
        sqlx::query(
            "UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, NOW()) WHERE session_id = $1",
        )
        .bind(session_id)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;

        Ok(MessageResponse {
            message: "Signed out successfully.".to_owned(),
        })
    }

    pub async fn forgot_password(
        &self,
        request: &ForgotPasswordRequest,
    ) -> Result<MessageResponse, AuthError> {
        let email = normalize_email(&request.email);
        let mut tx = self.pool.begin().await?;

        let user_row = sqlx::query(
            "SELECT id, full_name FROM users WHERE email = $1 AND is_active = TRUE",
        )
        .bind(&email)
        .fetch_optional(&mut *tx)
        .await?;

        if let Some(row) = user_row {
            let user_id: Uuid = row.try_get("id")?;
            let full_name: String = row.try_get("full_name")?;
            let raw_reset_token = CryptoTokens::generate_opaque_token(32);
            let token_hash = CryptoTokens::sha256_hex(&raw_reset_token);
            let expires_at = Utc::now() + Duration::minutes(15);
            let token_record_id = new_uuid_v4();
            let outbox_event_id = new_uuid_v4();

            let sealed_payload = seal_outbox_email_payload(
                &self.outbox_crypto,
                outbox_event_id,
                user_id,
                EVENT_EMAIL_DELIVERY_REQUESTED,
                EmailTemplateKind::PasswordReset,
                token_record_id,
                expires_at,
                &email,
                &full_name,
                &raw_reset_token,
            )?;
            let payload_json = serde_json::to_value(&sealed_payload).map_err(|err| {
                AuthError::Crypto(format!("failed to encode sealed email payload: {err}"))
            })?;

            let next_version = next_user_outbox_version(&mut tx, user_id).await?;

            sqlx::query(
                r#"
                INSERT INTO password_reset_tokens (id, user_id, token_hash, expires_at)
                VALUES ($1, $2, $3, $4)
                "#,
            )
            .bind(token_record_id)
            .bind(user_id)
            .bind(&token_hash)
            .bind(expires_at)
            .execute(&mut *tx)
            .await?;

            OutboxRepository::insert_outbox_event_with_id(
                &mut tx,
                outbox_event_id,
                "user",
                user_id,
                next_version,
                EVENT_EMAIL_DELIVERY_REQUESTED,
                &payload_json,
            )
            .await?;

            tx.commit().await?;
            info!(
                user_id = %user_id,
                outbox_event_id = %outbox_event_id,
                token_record_id = %token_record_id,
                "Queued sealed password-reset email outbox event"
            );
            return Ok(MessageResponse {
                message: "If an account matches that email, a password reset link has been sent."
                    .to_owned(),
            });
        }

        tx.commit().await?;
        Ok(MessageResponse {
            message: "If an account matches that email, a password reset link has been sent."
                .to_owned(),
        })
    }

    pub async fn reset_password(
        &self,
        request: &ResetPasswordRequest,
    ) -> Result<MessageResponse, AuthError> {
        let trimmed = request.token.trim();
        if trimmed.is_empty() {
            return Err(AuthError::Validation {
                code: "INVALID_TOKEN",
                message: "Password reset token is required.".to_owned(),
            });
        }
        validate_password(&request.new_password)?;
        let token_hash = CryptoTokens::sha256_hex(trimmed);
        let next_hash = self.password_hasher.hash(&request.new_password)?;

        let mut tx = self.pool.begin().await?;
        let user_id: Option<Uuid> = sqlx::query_scalar(
            r#"
            UPDATE password_reset_tokens
            SET used_at = NOW()
            WHERE token_hash = $1 AND used_at IS NULL AND expires_at > NOW()
            RETURNING user_id
            "#,
        )
        .bind(&token_hash)
        .fetch_optional(&mut *tx)
        .await?;

        let Some(uid) = user_id else {
            warn!("Blocked invalid, expired, or replayed password reset token");
            return Err(AuthError::Validation {
                code: "INVALID_OR_EXPIRED_TOKEN",
                message: "Password reset token is invalid, expired, or already used.".to_owned(),
            });
        };

        sqlx::query("UPDATE users SET password_hash = $1, updated_at = NOW() WHERE id = $2")
            .bind(&next_hash)
            .bind(uid)
            .execute(&mut *tx)
            .await?;

        sqlx::query(
            "UPDATE user_sessions SET revoked_at = COALESCE(revoked_at, NOW()) WHERE user_id = $1",
        )
        .bind(uid)
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            r#"
            UPDATE refresh_tokens rt
            SET revoked_at = COALESCE(rt.revoked_at, NOW())
            FROM user_sessions us
            WHERE rt.session_id = us.id AND us.user_id = $1
            "#,
        )
        .bind(uid)
        .execute(&mut *tx)
        .await?;

        tx.commit().await?;
        Ok(MessageResponse {
            message: "Password reset successful. Please sign in with your new password.".to_owned(),
        })
    }

    pub async fn create_staff(
        &self,
        actor_owner_id: Uuid,
        request: &CreateStaffRequest,
        audit: &AuditContext,
    ) -> Result<StaffMemberDto, AuthError> {
        let email = normalize_email(&request.email);
        validate_email(&email)?;
        validate_password(&request.password)?;
        if request.full_name.trim().is_empty() {
            return Err(AuthError::Validation {
                code: "INVALID_NAME",
                message: "Full name is required.".to_owned(),
            });
        }
        if request.phone.trim().chars().count() < 7 {
            return Err(AuthError::Validation {
                code: "INVALID_PHONE",
                message: "A valid phone number is required.".to_owned(),
            });
        }
        if request.employee_code.trim().is_empty() {
            return Err(AuthError::Validation {
                code: "INVALID_EMPLOYEE_CODE",
                message: "Employee code is required.".to_owned(),
            });
        }

        let password_hash = self.password_hasher.hash(&request.password)?;
        let staff = UsersService::new(self.pool.clone())
            .create_staff_with_password_hash(
                actor_owner_id,
                &email,
                &password_hash,
                &request.full_name,
                &request.phone,
                request.role,
                &request.employee_code,
                audit,
            )
            .await?;
        Ok(staff)
    }
}

async fn next_user_outbox_version(
    conn: &mut sqlx::PgConnection,
    user_id: Uuid,
) -> Result<i64, sqlx::Error> {
    let version: i64 = sqlx::query_scalar(
        r#"
        SELECT COALESCE(MAX(aggregate_version), 0) + 1
        FROM outbox_events
        WHERE aggregate_type = 'user' AND aggregate_id = $1
        "#,
    )
    .bind(user_id)
    .fetch_one(&mut *conn)
    .await?;
    Ok(version)
}

fn normalize_email(raw: &str) -> String {
    raw.trim().to_ascii_lowercase()
}

fn validate_email(email: &str) -> Result<(), AuthError> {
    let valid_domain = email
        .split_once('@')
        .is_some_and(|(local, domain)| !local.is_empty() && domain.contains('.'));
    if !valid_domain || email.chars().count() < 5 {
        return Err(AuthError::Validation {
            code: "INVALID_EMAIL",
            message: "Please provide a valid email address.".to_owned(),
        });
    }
    Ok(())
}

fn validate_password(password: &str) -> Result<(), AuthError> {
    if password.chars().count() < 8 {
        return Err(AuthError::Validation {
            code: "WEAK_PASSWORD",
            message: "Password must be at least 8 characters long.".to_owned(),
        });
    }
    Ok(())
}

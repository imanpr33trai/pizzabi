use chrono::{DateTime, Utc};
use pizzora_core::UserRole;
use serde::{Deserialize, Serialize};
use sqlx::{PgConnection, PgPool, Row as _};
use uuid::Uuid;

use crate::audit::{record_audit_log, AuditContext};
use crate::error::{new_uuid_v4, DbError};
use crate::ws_tickets::{parse_user_role, user_role_as_str};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UserProfileDto {
    pub id: String,
    pub email: String,
    pub email_verified: bool,
    pub full_name: String,
    pub phone: String,
    pub role: UserRole,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UpdateProfileRequest {
    pub full_name: String,
    pub phone: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AddressDto {
    pub id: String,
    pub label: String,
    pub line1: String,
    #[serde(default)]
    pub line2: Option<String>,
    #[serde(default)]
    pub landmark: Option<String>,
    pub pincode: String,
    pub city: String,
    pub is_default: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UpsertAddressRequest {
    pub label: String,
    pub line1: String,
    #[serde(default)]
    pub line2: Option<String>,
    #[serde(default)]
    pub landmark: Option<String>,
    pub pincode: String,
    pub city: String,
    #[serde(default)]
    pub is_default: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StaffMemberDto {
    pub user_id: String,
    pub email: String,
    pub full_name: String,
    pub phone: String,
    pub role: UserRole,
    pub employee_code: String,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct UpdateStaffRequest {
    pub role: Option<UserRole>,
    pub full_name: Option<String>,
    pub phone: Option<String>,
    pub is_active: Option<bool>,
}

#[derive(Clone)]
pub struct UsersService {
    pool: PgPool,
}

impl UsersService {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub fn pool(&self) -> &PgPool {
        &self.pool
    }

    pub async fn get_profile(&self, user_id: Uuid) -> Result<UserProfileDto, DbError> {
        let mut tx = self.pool.begin().await?;
        let profile = Self::get_profile_in_tx(&mut tx, user_id).await?;
        tx.commit().await?;
        Ok(profile)
    }

    pub async fn get_profile_in_tx(
        conn: &mut PgConnection,
        user_id: Uuid,
    ) -> Result<UserProfileDto, DbError> {
        let row = sqlx::query(
            r#"
            SELECT u.id, u.email, u.email_verified_at, u.full_name, u.phone, u.is_active,
                   r.code AS staff_role
            FROM users u
            LEFT JOIN staff s ON s.user_id = u.id AND s.is_active = TRUE
            LEFT JOIN roles r ON r.id = s.role_id
            WHERE u.id = $1
            "#,
        )
        .bind(user_id)
        .fetch_optional(&mut *conn)
        .await?
        .ok_or_else(|| DbError::NotFound {
            code: "USER_NOT_FOUND",
            message: "User profile not found.".to_owned(),
        })?;

        let email_verified_at: Option<DateTime<Utc>> = row.try_get("email_verified_at")?;
        let staff_role_raw: Option<String> = row.try_get("staff_role")?;
        let role = match staff_role_raw.as_deref() {
            Some(code) => parse_user_role(code).ok_or_else(|| {
                DbError::CatalogInvariantViolation(format!("Unsupported role '{code}'"))
            })?,
            None => UserRole::Customer,
        };

        Ok(UserProfileDto {
            id: row.try_get::<Uuid, _>("id")?.to_string(),
            email: row.try_get("email")?,
            email_verified: email_verified_at.is_some(),
            full_name: row.try_get("full_name")?,
            phone: row.try_get("phone")?,
            role,
            is_active: row.try_get("is_active")?,
        })
    }

    pub async fn update_profile(
        &self,
        user_id: Uuid,
        request: &UpdateProfileRequest,
    ) -> Result<UserProfileDto, DbError> {
        let clean_name = request.full_name.trim();
        let clean_phone = request.phone.trim();
        if clean_name.is_empty() {
            return Err(DbError::Validation {
                code: "INVALID_NAME",
                message: "Full name cannot be blank.".to_owned(),
            });
        }
        if clean_phone.len() < 7 {
            return Err(DbError::Validation {
                code: "INVALID_PHONE",
                message: "Phone number is invalid.".to_owned(),
            });
        }

        let mut tx = self.pool.begin().await?;
        let res = sqlx::query(
            "UPDATE users SET full_name = $1, phone = $2, updated_at = NOW() WHERE id = $3",
        )
        .bind(clean_name)
        .bind(clean_phone)
        .bind(user_id)
        .execute(&mut *tx)
        .await?;

        if res.rows_affected() == 0 {
            return Err(DbError::NotFound {
                code: "USER_NOT_FOUND",
                message: "User profile not found.".to_owned(),
            });
        }

        let profile = Self::get_profile_in_tx(&mut tx, user_id).await?;
        tx.commit().await?;
        Ok(profile)
    }

    pub async fn list_addresses(&self, user_id: Uuid) -> Result<Vec<AddressDto>, DbError> {
        let rows = sqlx::query(
            r#"
            SELECT id, label, line1, line2, landmark, pincode, city, is_default
            FROM addresses
            WHERE user_id = $1
            ORDER BY is_default DESC, created_at DESC
            "#,
        )
        .bind(user_id)
        .fetch_all(&self.pool)
        .await?;

        let mut out = Vec::with_capacity(rows.len());
        for row in rows {
            out.push(AddressDto {
                id: row.try_get::<Uuid, _>("id")?.to_string(),
                label: row.try_get("label")?,
                line1: row.try_get("line1")?,
                line2: row.try_get("line2")?,
                landmark: row.try_get("landmark")?,
                pincode: row.try_get("pincode")?,
                city: row.try_get("city")?,
                is_default: row.try_get("is_default")?,
            });
        }
        Ok(out)
    }

    pub async fn create_address(
        &self,
        user_id: Uuid,
        request: &UpsertAddressRequest,
    ) -> Result<AddressDto, DbError> {
        validate_address(request)?;
        let address_id = new_uuid_v4();
        let mut tx = self.pool.begin().await?;

        if request.is_default {
            sqlx::query("UPDATE addresses SET is_default = FALSE WHERE user_id = $1")
                .bind(user_id)
                .execute(&mut *tx)
                .await?;
        }

        let clean_label = request.label.trim().to_owned();
        let clean_line1 = request.line1.trim().to_owned();
        let clean_line2 = request.line2.as_deref().map(str::trim).map(str::to_owned);
        let clean_landmark = request
            .landmark
            .as_deref()
            .map(str::trim)
            .map(str::to_owned);
        let clean_pincode = request.pincode.trim().to_owned();
        let clean_city = request.city.trim().to_owned();

        sqlx::query(
            r#"
            INSERT INTO addresses (id, user_id, label, line1, line2, landmark, pincode, city, is_default)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
            "#,
        )
        .bind(address_id)
        .bind(user_id)
        .bind(&clean_label)
        .bind(&clean_line1)
        .bind(clean_line2.as_deref())
        .bind(clean_landmark.as_deref())
        .bind(&clean_pincode)
        .bind(&clean_city)
        .bind(request.is_default)
        .execute(&mut *tx)
        .await?;

        tx.commit().await?;
        Ok(AddressDto {
            id: address_id.to_string(),
            label: clean_label,
            line1: clean_line1,
            line2: clean_line2,
            landmark: clean_landmark,
            pincode: clean_pincode,
            city: clean_city,
            is_default: request.is_default,
        })
    }

    pub async fn update_address(
        &self,
        user_id: Uuid,
        address_id: Uuid,
        request: &UpsertAddressRequest,
    ) -> Result<AddressDto, DbError> {
        validate_address(request)?;
        let mut tx = self.pool.begin().await?;

        if request.is_default {
            sqlx::query("UPDATE addresses SET is_default = FALSE WHERE user_id = $1")
                .bind(user_id)
                .execute(&mut *tx)
                .await?;
        }

        let clean_label = request.label.trim().to_owned();
        let clean_line1 = request.line1.trim().to_owned();
        let clean_line2 = request.line2.as_deref().map(str::trim).map(str::to_owned);
        let clean_landmark = request
            .landmark
            .as_deref()
            .map(str::trim)
            .map(str::to_owned);
        let clean_pincode = request.pincode.trim().to_owned();
        let clean_city = request.city.trim().to_owned();

        let res = sqlx::query(
            r#"
            UPDATE addresses
            SET label = $1, line1 = $2, line2 = $3, landmark = $4, pincode = $5, city = $6, is_default = $7
            WHERE id = $8 AND user_id = $9
            "#,
        )
        .bind(&clean_label)
        .bind(&clean_line1)
        .bind(clean_line2.as_deref())
        .bind(clean_landmark.as_deref())
        .bind(&clean_pincode)
        .bind(&clean_city)
        .bind(request.is_default)
        .bind(address_id)
        .bind(user_id)
        .execute(&mut *tx)
        .await?;

        if res.rows_affected() == 0 {
            return Err(DbError::NotFound {
                code: "ADDRESS_NOT_FOUND",
                message: "Address not found for current user.".to_owned(),
            });
        }

        tx.commit().await?;
        Ok(AddressDto {
            id: address_id.to_string(),
            label: clean_label,
            line1: clean_line1,
            line2: clean_line2,
            landmark: clean_landmark,
            pincode: clean_pincode,
            city: clean_city,
            is_default: request.is_default,
        })
    }

    pub async fn delete_address(&self, user_id: Uuid, address_id: Uuid) -> Result<(), DbError> {
        let mut tx = self.pool.begin().await?;
        let deleted_is_default: Option<bool> = sqlx::query_scalar(
            "DELETE FROM addresses WHERE id = $1 AND user_id = $2 RETURNING is_default",
        )
        .bind(address_id)
        .bind(user_id)
        .fetch_optional(&mut *tx)
        .await?;

        let Some(was_default) = deleted_is_default else {
            return Err(DbError::NotFound {
                code: "ADDRESS_NOT_FOUND",
                message: "Address not found for current user.".to_owned(),
            });
        };

        if was_default {
            sqlx::query(
                r#"
                UPDATE addresses
                SET is_default = TRUE
                WHERE id = (
                    SELECT id FROM addresses WHERE user_id = $1 ORDER BY created_at DESC LIMIT 1
                )
                "#,
            )
            .bind(user_id)
            .execute(&mut *tx)
            .await?;
        }

        tx.commit().await?;
        Ok(())
    }

    pub async fn list_staff(&self) -> Result<Vec<StaffMemberDto>, DbError> {
        let rows = sqlx::query(
            r#"
            SELECT u.id, u.email, u.full_name, u.phone, r.code AS role_code, s.employee_code, s.is_active
            FROM staff s
            JOIN users u ON u.id = s.user_id
            JOIN roles r ON r.id = s.role_id
            ORDER BY s.created_at DESC
            "#,
        )
        .fetch_all(&self.pool)
        .await?;

        rows.iter().map(row_to_staff_dto).collect()
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn create_staff_with_password_hash(
        &self,
        actor_owner_id: Uuid,
        email: &str,
        password_hash: &str,
        full_name: &str,
        phone: &str,
        role: UserRole,
        employee_code: &str,
        audit: &AuditContext,
    ) -> Result<StaffMemberDto, DbError> {
        if role != UserRole::KitchenStaff && role != UserRole::Owner {
            return Err(DbError::Validation {
                code: "INVALID_STAFF_ROLE",
                message: "Staff accounts can only have role KITCHEN_STAFF or OWNER.".to_owned(),
            });
        }
        let clean_email = email.trim().to_ascii_lowercase();
        let clean_name = full_name.trim();
        let clean_phone = phone.trim();
        let clean_code = employee_code.trim();

        let mut tx = self.pool.begin().await?;
        let email_exists: Option<i32> = sqlx::query_scalar("SELECT 1 FROM users WHERE email = $1")
            .bind(&clean_email)
            .fetch_optional(&mut *tx)
            .await?;
        if email_exists.is_some() {
            return Err(DbError::Conflict {
                code: "EMAIL_ALREADY_EXISTS",
                message: "A user with this email already exists.".to_owned(),
            });
        }

        let code_exists: Option<i32> =
            sqlx::query_scalar("SELECT 1 FROM staff WHERE employee_code = $1")
                .bind(clean_code)
                .fetch_optional(&mut *tx)
                .await?;
        if code_exists.is_some() {
            return Err(DbError::Conflict {
                code: "EMPLOYEE_CODE_EXISTS",
                message: "Employee code is already assigned.".to_owned(),
            });
        }

        let role_id: Uuid = sqlx::query_scalar("SELECT id FROM roles WHERE code = $1")
            .bind(user_role_as_str(role))
            .fetch_optional(&mut *tx)
            .await?
            .ok_or_else(|| {
                DbError::CatalogInvariantViolation(format!(
                    "Missing seeded role {}",
                    user_role_as_str(role)
                ))
            })?;

        let new_user_id = new_uuid_v4();
        sqlx::query(
            r#"
            INSERT INTO users (id, email, email_verified_at, password_hash, full_name, phone, is_active)
            VALUES ($1, $2, NOW(), $3, $4, $5, TRUE)
            "#,
        )
        .bind(new_user_id)
        .bind(&clean_email)
        .bind(password_hash)
        .bind(clean_name)
        .bind(clean_phone)
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            r#"
            INSERT INTO staff (user_id, role_id, employee_code, is_active, created_by)
            VALUES ($1, $2, $3, TRUE, $4)
            "#,
        )
        .bind(new_user_id)
        .bind(role_id)
        .bind(clean_code)
        .bind(actor_owner_id)
        .execute(&mut *tx)
        .await?;

        let dto = StaffMemberDto {
            user_id: new_user_id.to_string(),
            email: clean_email,
            full_name: clean_name.to_owned(),
            phone: clean_phone.to_owned(),
            role,
            employee_code: clean_code.to_owned(),
            is_active: true,
        };
        let after_val = serde_json::to_value(&dto).ok();
        let entity_id = new_user_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "STAFF_CREATED",
            "staff",
            &entity_id,
            None,
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        Ok(dto)
    }

    pub async fn update_staff(
        &self,
        target_user_id: Uuid,
        request: &UpdateStaffRequest,
        audit: &AuditContext,
    ) -> Result<StaffMemberDto, DbError> {
        if let Some(role) = request.role {
            if role != UserRole::KitchenStaff && role != UserRole::Owner {
                return Err(DbError::Validation {
                    code: "INVALID_STAFF_ROLE",
                    message: "Staff role must be KITCHEN_STAFF or OWNER.".to_owned(),
                });
            }
        }

        let mut tx = self.pool.begin().await?;
        let before_row = sqlx::query(
            r#"
            SELECT u.id, u.email, u.full_name, u.phone, r.code AS role_code, s.employee_code, s.is_active
            FROM staff s
            JOIN users u ON u.id = s.user_id
            JOIN roles r ON r.id = s.role_id
            WHERE s.user_id = $1
            FOR UPDATE OF s, u
            "#,
        )
        .bind(target_user_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| DbError::NotFound {
            code: "STAFF_NOT_FOUND",
            message: "Staff member not found.".to_owned(),
        })?;
        let before = row_to_staff_dto(&before_row)?;

        if let Some(next_role) = request.role {
            let role_id: Uuid = sqlx::query_scalar("SELECT id FROM roles WHERE code = $1")
                .bind(user_role_as_str(next_role))
                .fetch_one(&mut *tx)
                .await?;
            sqlx::query("UPDATE staff SET role_id = $1 WHERE user_id = $2")
                .bind(role_id)
                .bind(target_user_id)
                .execute(&mut *tx)
                .await?;
        }

        if request.full_name.is_some() || request.phone.is_some() {
            sqlx::query(
                r#"
                UPDATE users
                SET full_name = COALESCE($1, full_name),
                    phone = COALESCE($2, phone),
                    updated_at = NOW()
                WHERE id = $3
                "#,
            )
            .bind(request.full_name.as_deref().map(str::trim))
            .bind(request.phone.as_deref().map(str::trim))
            .bind(target_user_id)
            .execute(&mut *tx)
            .await?;
        }

        if let Some(next_active) = request.is_active {
            sqlx::query("UPDATE staff SET is_active = $1 WHERE user_id = $2")
                .bind(next_active)
                .bind(target_user_id)
                .execute(&mut *tx)
                .await?;
            sqlx::query("UPDATE users SET is_active = $1, updated_at = NOW() WHERE id = $2")
                .bind(next_active)
                .bind(target_user_id)
                .execute(&mut *tx)
                .await?;
            if !next_active {
                sqlx::query(
                    "UPDATE user_sessions SET revoked_at = COALESCE(revoked_at, NOW()) WHERE user_id = $1",
                )
                .bind(target_user_id)
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
                .bind(target_user_id)
                .execute(&mut *tx)
                .await?;
            }
        }

        let after_row = sqlx::query(
            r#"
            SELECT u.id, u.email, u.full_name, u.phone, r.code AS role_code, s.employee_code, s.is_active
            FROM staff s
            JOIN users u ON u.id = s.user_id
            JOIN roles r ON r.id = s.role_id
            WHERE s.user_id = $1
            "#,
        )
        .bind(target_user_id)
        .fetch_one(&mut *tx)
        .await?;
        let after = row_to_staff_dto(&after_row)?;

        let before_val = serde_json::to_value(&before).ok();
        let after_val = serde_json::to_value(&after).ok();
        let entity_id = target_user_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "STAFF_UPDATED",
            "staff",
            &entity_id,
            before_val.as_ref(),
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        Ok(after)
    }
}

fn validate_address(request: &UpsertAddressRequest) -> Result<(), DbError> {
    if request.label.trim().is_empty()
        || request.line1.trim().is_empty()
        || request.city.trim().is_empty()
    {
        return Err(DbError::Validation {
            code: "INVALID_ADDRESS",
            message: "Label, line1, and city are required.".to_owned(),
        });
    }
    let pin = request.pincode.trim().as_bytes();
    let valid_pin = pin.len() == 6
        && matches!(pin[0], b'1'..=b'9')
        && pin[1..].iter().all(|b| b.is_ascii_digit());
    if !valid_pin {
        return Err(DbError::Validation {
            code: "INVALID_PINCODE",
            message: "Pincode must be a valid 6-digit postal code.".to_owned(),
        });
    }
    Ok(())
}

fn row_to_staff_dto(row: &sqlx::postgres::PgRow) -> Result<StaffMemberDto, DbError> {
    let role_raw: String = row.try_get("role_code")?;
    let role = parse_user_role(&role_raw).ok_or_else(|| {
        DbError::CatalogInvariantViolation(format!("Unsupported staff role '{role_raw}'"))
    })?;
    Ok(StaffMemberDto {
        user_id: row.try_get::<Uuid, _>("id")?.to_string(),
        email: row.try_get("email")?,
        full_name: row.try_get("full_name")?,
        phone: row.try_get("phone")?,
        role,
        employee_code: row.try_get("employee_code")?,
        is_active: row.try_get("is_active")?,
    })
}

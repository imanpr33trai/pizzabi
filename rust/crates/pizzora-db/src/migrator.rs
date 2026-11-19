use std::collections::{BTreeMap, HashSet};
use std::future::Future;

use crc32fast::Hasher;
use sqlx::migrate::Migrator;
use sqlx::postgres::PgConnection;
use sqlx::{Connection as _, PgPool, Row as _};
use tracing::{error, info, warn};

use crate::error::DbError;

pub const MIGRATION_ADVISORY_LOCK_KEY: i64 = 884_210_001;

pub static MIGRATOR: Migrator = sqlx::migrate!("./migrations");

pub const CANONICAL_V1_BYTES: &[u8] = include_bytes!("../migrations/0001_core_schema.sql");
pub const CANONICAL_V2_BYTES: &[u8] =
    include_bytes!("../migrations/0002_seed_rbac_and_settings.sql");
pub const CANONICAL_V3_BYTES: &[u8] = include_bytes!("../migrations/0003_hot_path_indexes.sql");

pub const EXPECTED_TABLES: &[&str] = &[
    "roles",
    "permissions",
    "role_permissions",
    "users",
    "staff",
    "user_sessions",
    "refresh_tokens",
    "email_verification_tokens",
    "password_reset_tokens",
    "ws_tickets",
    "addresses",
    "categories",
    "products",
    "product_variants",
    "crust_options",
    "toppings",
    "product_images",
    "ingredients",
    "inventory",
    "variant_recipes",
    "crust_recipes",
    "topping_recipes",
    "carts",
    "cart_items",
    "cart_item_toppings",
    "coupons",
    "delivery_zones",
    "scheduled_slots",
    "restaurant_settings",
    "orders",
    "order_items",
    "order_status_history",
    "inventory_reservations",
    "inventory_ledger",
    "coupon_reservations",
    "coupon_redemptions",
    "scheduled_slot_reservations",
    "payments",
    "payment_attempts",
    "payment_events",
    "refunds",
    "event_stream_cursor",
    "outbox_events",
    "notification_inbox",
    "notification_preferences",
    "audit_logs",
];

pub const EXPECTED_V3_INDEXES: &[&str] = &[
    "idx_order_items_order_id",
    "idx_orders_created_at",
    "idx_orders_pending_payment_expires",
    "idx_orders_kds_active",
    "idx_refunds_order_id",
    "idx_refunds_payment_status",
    "idx_inventory_ledger_created",
    "idx_outbox_broadcast_queue",
    "idx_outbox_aggregate_id",
    "idx_product_variants_active_product",
    "idx_product_images_active_product",
    "idx_cart_items_cart_id",
    "idx_scheduled_slot_res_slot_status",
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HandoffMode {
    AdoptedExistingFlyway,
    ResumedSqlx,
    FreshBootstrap,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HandoffOutcome {
    pub mode: HandoffMode,
    pub backend_pid: i32,
    pub applied_or_verified_versions: Vec<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct MigrationPreflightReport {
    pub flyway_table_exists: bool,
    pub sqlx_table_exists: bool,
    pub flyway_verified: bool,
    pub sqlx_verified: bool,
    pub catalog_invariants_ok: bool,
    pub flyway_versions: Vec<String>,
    pub sqlx_versions: Vec<i64>,
    pub outbox_dead_letter_count: i64,
    pub outbox_pending_count: i64,
    pub stream_high_water_seq: i64,
    pub active_worker_sessions: i64,
}

/// Computes the exact signed 32-bit CRC checksum matching `org.flywaydb:flyway-core:11.3.4`:
/// - Reads the input as UTF-8 text line-by-line (splitting on `\r\n`, `\n`, or `\r`)
/// - Strips a leading UTF-8 BOM (`0xEF 0xBB 0xBF`) from the first line if present
/// - Updates IEEE CRC-32 with each line's UTF-8 bytes without line terminator characters
/// - Returns `checksum as i32`
pub fn flyway_crc32(raw_bytes: &[u8]) -> Result<i32, DbError> {
    let without_bom = raw_bytes
        .strip_prefix(&[0xEF, 0xBB, 0xBF])
        .unwrap_or(raw_bytes);
    let text = std::str::from_utf8(without_bom).map_err(|err| {
        DbError::FlywayHistoryInvalid(format!("migration SQL is not valid UTF-8: {err}"))
    })?;

    let mut hasher = Hasher::new();
    let bytes = text.as_bytes();
    let mut start = 0usize;
    let mut i = 0usize;

    while i < bytes.len() {
        match bytes[i] {
            b'\r' => {
                hasher.update(&bytes[start..i]);
                if i + 1 < bytes.len() && bytes[i + 1] == b'\n' {
                    i += 2;
                } else {
                    i += 1;
                }
                start = i;
            }
            b'\n' => {
                hasher.update(&bytes[start..i]);
                i += 1;
                start = i;
            }
            _ => {
                i += 1;
            }
        }
    }

    if start < bytes.len() {
        hasher.update(&bytes[start..]);
    }

    Ok(hasher.finalize() as i32)
}

pub fn canonical_v1_v3_specs() -> Result<[(&'static str, &'static str, i32); 3], DbError> {
    Ok([
        (
            "1",
            "V1__core_schema.sql",
            flyway_crc32(CANONICAL_V1_BYTES)?,
        ),
        (
            "2",
            "V2__seed_rbac_and_settings.sql",
            flyway_crc32(CANONICAL_V2_BYTES)?,
        ),
        (
            "3",
            "V3__hot_path_indexes.sql",
            flyway_crc32(CANONICAL_V3_BYTES)?,
        ),
    ])
}

/// Executes Flyway -> SQLx adoption or fresh DB bootstrap under a single dedicated-connection
/// PostgreSQL advisory lock (`pg_advisory_lock(884210001)`).
pub async fn run_locked_handoff(pool: &PgPool) -> Result<HandoffOutcome, DbError> {
    run_locked_handoff_with_hook(pool, |_| async { Ok(()) }).await
}

/// Same as `run_locked_handoff` with a synchronization/inspection hook invoked while the
/// single dedicated `PgConnection` exclusively holds `pg_advisory_lock(884210001)`.
pub async fn run_locked_handoff_with_hook<F, Fut>(
    pool: &PgPool,
    on_lock_acquired: F,
) -> Result<HandoffOutcome, DbError>
where
    F: FnOnce(i32) -> Fut + Send,
    Fut: Future<Output = Result<(), DbError>> + Send,
{
    let mut conn = pool.acquire().await?;

    sqlx::query("SELECT pg_advisory_lock($1)")
        .bind(MIGRATION_ADVISORY_LOCK_KEY)
        .execute(&mut *conn)
        .await
        .map_err(|err| DbError::AdvisoryLock(format!("failed to acquire lock: {err}")))?;

    let backend_pid: i32 = sqlx::query_scalar("SELECT pg_backend_pid()")
        .fetch_one(&mut *conn)
        .await
        .map_err(|err| DbError::AdvisoryLock(format!("failed to read backend pid: {err}")))?;

    let work_result = async {
        on_lock_acquired(backend_pid).await?;
        execute_locked_handoff(&mut conn, backend_pid).await
    }
    .await;

    let unlock_res = sqlx::query("SELECT pg_advisory_unlock($1)")
        .bind(MIGRATION_ADVISORY_LOCK_KEY)
        .fetch_optional(&mut *conn)
        .await;

    match (work_result, unlock_res) {
        (Ok(outcome), Ok(_)) => Ok(outcome),
        (Ok(_), Err(unlock_err)) => {
            warn!("Failed to unlock advisory lock cleanly; closing dedicated connection");
            let _ = conn.close().await;
            Err(DbError::AdvisoryLock(format!(
                "failed to unlock session advisory lock: {unlock_err}"
            )))
        }
        (Err(work_err), Ok(_)) => {
            // Never return a potentially dirty/aborted transaction session to PgPool on failure
            let _ = conn.close().await;
            Err(work_err)
        }
        (Err(work_err), Err(unlock_err)) => {
            error!("Migration handoff failed and advisory unlock also failed: {unlock_err}");
            let _ = conn.close().await;
            Err(work_err)
        }
    }
}

async fn execute_locked_handoff(
    conn: &mut PgConnection,
    backend_pid: i32,
) -> Result<HandoffOutcome, DbError> {
    let flyway_exists: bool =
        sqlx::query_scalar("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")
            .fetch_one(&mut *conn)
            .await?;

    let sqlx_exists: bool =
        sqlx::query_scalar("SELECT to_regclass('public._sqlx_migrations') IS NOT NULL")
            .fetch_one(&mut *conn)
            .await?;

    if flyway_exists {
        verify_flyway_history(conn).await?;
        verify_catalog_invariants(conn).await?;
        ensure_sqlx_baseline_1_to_3(conn).await?;
        verify_existing_sqlx_migrations(conn).await?;
        MIGRATOR.run_direct(&mut *conn).await?;
        let versions = collect_applied_versions(conn).await?;
        info!(
            backend_pid,
            versions = ?versions,
            "Completed advisory-locked Flyway V1-V3 to SQLx handoff"
        );
        Ok(HandoffOutcome {
            mode: HandoffMode::AdoptedExistingFlyway,
            backend_pid,
            applied_or_verified_versions: versions,
        })
    } else if sqlx_exists {
        verify_existing_sqlx_migrations(conn).await?;
        MIGRATOR.run_direct(&mut *conn).await?;
        verify_catalog_invariants(conn).await?;
        let versions = collect_applied_versions(conn).await?;
        info!(
            backend_pid,
            versions = ?versions,
            "Verified existing SQLx schema under advisory lock"
        );
        Ok(HandoffOutcome {
            mode: HandoffMode::ResumedSqlx,
            backend_pid,
            applied_or_verified_versions: versions,
        })
    } else {
        let existing_table_count: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
        )
        .fetch_one(&mut *conn)
        .await?;

        if existing_table_count > 0 {
            return Err(DbError::UnsafeDatabaseState(format!(
                "found {existing_table_count} existing table(s) without flyway_schema_history or _sqlx_migrations; refusing automatic migration"
            )));
        }

        MIGRATOR.run_direct(&mut *conn).await?;
        verify_catalog_invariants(conn).await?;
        let versions = collect_applied_versions(conn).await?;
        info!(
            backend_pid,
            versions = ?versions,
            "Completed fresh PostgreSQL SQLx bootstrap under advisory lock"
        );
        Ok(HandoffOutcome {
            mode: HandoffMode::FreshBootstrap,
            backend_pid,
            applied_or_verified_versions: versions,
        })
    }
}

/// Strictly read-only preflight verification for Phase 9 production cutover (Step 3).
/// Performs zero DDL or table mutations: verifies `flyway_schema_history` (and `_sqlx_migrations`
/// if present), checks all 46 tables / 13 V3 indexes / constraints / V2 seeds, and reports
/// outbox dead-letter backlog and active worker sessions from `pg_stat_activity`.
pub async fn verify_migration_readiness_readonly(
    pool: &PgPool,
) -> Result<MigrationPreflightReport, DbError> {
    let mut conn = pool.acquire().await?;

    let flyway_table_exists: bool =
        sqlx::query_scalar("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")
            .fetch_one(&mut *conn)
            .await?;

    let sqlx_table_exists: bool =
        sqlx::query_scalar("SELECT to_regclass('public._sqlx_migrations') IS NOT NULL")
            .fetch_one(&mut *conn)
            .await?;

    if !flyway_table_exists && !sqlx_table_exists {
        return Err(DbError::UnsafeDatabaseState(
            "neither flyway_schema_history nor _sqlx_migrations exists; database is not ready for cutover"
                .to_owned(),
        ));
    }

    let mut flyway_verified = false;
    let mut flyway_versions = Vec::new();
    if flyway_table_exists {
        verify_flyway_history(&mut conn).await?;
        flyway_verified = true;
        flyway_versions = sqlx::query_scalar(
            "SELECT version FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL ORDER BY installed_rank ASC",
        )
        .fetch_all(&mut *conn)
        .await?;
    }

    let mut sqlx_verified = false;
    let mut sqlx_versions = Vec::new();
    if sqlx_table_exists {
        verify_existing_sqlx_migrations(&mut conn).await?;
        sqlx_verified = true;
        sqlx_versions = collect_applied_versions(&mut conn).await?;
    }

    verify_catalog_invariants(&mut conn).await?;

    let outbox_dead_letter_count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM outbox_events WHERE dispatch_status = 'DEAD_LETTER'",
    )
    .fetch_one(&mut *conn)
    .await?;

    let outbox_pending_count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM outbox_events WHERE dispatch_status IN ('PENDING', 'PROCESSING')",
    )
    .fetch_one(&mut *conn)
    .await?;

    let stream_high_water_seq: i64 = sqlx::query_scalar(
        "SELECT COALESCE((SELECT last_seq FROM event_stream_cursor WHERE id = 1), 0)",
    )
    .fetch_one(&mut *conn)
    .await?;

    let active_worker_sessions: i64 = sqlx::query_scalar(
        r#"
        SELECT COUNT(*)
        FROM pg_stat_activity
        WHERE datname = current_database()
          AND pid <> pg_backend_pid()
          AND state IS NOT NULL
          AND (
            application_name ILIKE '%worker%'
            OR query ILIKE '%outbox_events%FOR UPDATE SKIP LOCKED%'
          )
        "#,
    )
    .fetch_one(&mut *conn)
    .await?;

    Ok(MigrationPreflightReport {
        flyway_table_exists,
        sqlx_table_exists,
        flyway_verified,
        sqlx_verified,
        catalog_invariants_ok: true,
        flyway_versions,
        sqlx_versions,
        outbox_dead_letter_count,
        outbox_pending_count,
        stream_high_water_seq,
        active_worker_sessions,
    })
}

async fn verify_flyway_history(conn: &mut PgConnection) -> Result<(), DbError> {
    let rows = sqlx::query(
        r#"
        SELECT installed_rank, version, description, type, script, checksum, success
        FROM flyway_schema_history
        ORDER BY installed_rank ASC
        "#,
    )
    .fetch_all(&mut *conn)
    .await?;

    if rows.is_empty() {
        return Err(DbError::FlywayHistoryInvalid(
            "flyway_schema_history is empty".to_owned(),
        ));
    }

    let expected_specs = canonical_v1_v3_specs()?;
    let mut seen_versions: BTreeMap<String, (String, Option<i32>)> = BTreeMap::new();

    for row in rows {
        let installed_rank: i32 = row.try_get("installed_rank")?;
        let version: Option<String> = row.try_get("version")?;
        let row_type: String = row.try_get("type")?;
        let script: String = row.try_get("script")?;
        let checksum: Option<i32> = row.try_get("checksum")?;
        let success: bool = row.try_get("success")?;

        if !success {
            return Err(DbError::FlywayHistoryInvalid(format!(
                "failed Flyway history row at installed_rank={installed_rank} (version={version:?}, script={script}); manual DBA intervention required — auto-repair is forbidden"
            )));
        }

        let Some(ver) = version else {
            // Flyway creates an initial version=NULL row of type 'SCHEMA' (`<< Flyway Schema Creation >>`).
            if row_type.eq_ignore_ascii_case("SCHEMA") {
                continue;
            }
            return Err(DbError::FlywayHistoryInvalid(format!(
                "unexpected non-SCHEMA NULL-version Flyway history row at installed_rank={installed_rank} (type={row_type}, script={script})"
            )));
        };

        if !matches!(ver.as_str(), "1" | "2" | "3") {
            return Err(DbError::FlywayHistoryInvalid(format!(
                "unexpected Flyway migration version '{ver}' ({script}); only V1..V3 are adoptable"
            )));
        }

        if seen_versions.contains_key(&ver) {
            return Err(DbError::FlywayHistoryInvalid(format!(
                "duplicate Flyway history entry for version '{ver}'"
            )));
        }
        seen_versions.insert(ver, (script, checksum));
    }

    for (ver, expected_script, expected_checksum) in expected_specs {
        let Some((actual_script, actual_checksum)) = seen_versions.get(ver) else {
            return Err(DbError::FlywayHistoryInvalid(format!(
                "missing required Flyway version '{ver}' ({expected_script}) in flyway_schema_history"
            )));
        };
        let actual_val = actual_checksum.ok_or_else(|| {
            DbError::FlywayHistoryInvalid(format!(
                "Flyway version '{ver}' ({actual_script}) has NULL checksum"
            ))
        })?;
        if actual_val != expected_checksum {
            return Err(DbError::MigrationChecksumMismatch {
                version: ver.to_owned(),
                script: actual_script.clone(),
                expected: expected_checksum,
                actual: actual_val,
            });
        }
    }

    Ok(())
}

pub async fn verify_catalog_invariants(conn: &mut PgConnection) -> Result<(), DbError> {
    let tables: HashSet<String> = sqlx::query_scalar(
        "SELECT table_name::text FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
    )
    .fetch_all(&mut *conn)
    .await?
    .into_iter()
    .collect();

    let missing_tables: Vec<&str> = EXPECTED_TABLES
        .iter()
        .copied()
        .filter(|name| !tables.contains(*name))
        .collect();
    if !missing_tables.is_empty() {
        return Err(DbError::CatalogInvariantViolation(format!(
            "missing required core schema table(s): {}",
            missing_tables.join(", ")
        )));
    }

    let indexes: HashSet<String> =
        sqlx::query_scalar("SELECT indexname::text FROM pg_indexes WHERE schemaname = 'public'")
            .fetch_all(&mut *conn)
            .await?
            .into_iter()
            .collect();

    let missing_indexes: Vec<&str> = EXPECTED_V3_INDEXES
        .iter()
        .copied()
        .filter(|name| !indexes.contains(*name))
        .collect();
    if !missing_indexes.is_empty() {
        return Err(DbError::CatalogInvariantViolation(format!(
            "missing required V3 hot-path index(es): {}",
            missing_indexes.join(", ")
        )));
    }

    // Verify essential constraints exist on high-integrity tables (UNIQUE/PK/FK checks)
    let required_constraints: &[(&str, &str)] = &[
        ("users", "u"),
        ("orders", "u"),
        ("payment_attempts", "u"),
        ("notification_inbox", "u"),
        ("inventory", "c"),
    ];
    for &(table_name, contype) in required_constraints {
        let count: i64 = sqlx::query_scalar(
            r#"
            SELECT COUNT(*)
            FROM pg_constraint c
            JOIN pg_class rel ON rel.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = rel.relnamespace
            WHERE n.nspname = 'public' AND rel.relname = $1 AND c.contype::text = $2
            "#,
        )
        .bind(table_name)
        .bind(contype)
        .fetch_one(&mut *conn)
        .await?;
        if count == 0 {
            return Err(DbError::CatalogInvariantViolation(format!(
                "missing expected '{contype}' constraint on table '{table_name}'"
            )));
        }
    }

    // Verify V2 seeds
    let role_count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM roles WHERE code IN ('CUSTOMER', 'KITCHEN_STAFF', 'OWNER')",
    )
    .fetch_one(&mut *conn)
    .await?;
    if role_count != 3 {
        return Err(DbError::CatalogInvariantViolation(format!(
            "expected 3 seeded roles, found {role_count}"
        )));
    }

    let perm_count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM permissions")
        .fetch_one(&mut *conn)
        .await?;
    if perm_count < 22 {
        return Err(DbError::CatalogInvariantViolation(format!(
            "expected >= 22 seeded permissions, found {perm_count}"
        )));
    }

    let settings_exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM restaurant_settings WHERE id = 1)")
            .fetch_one(&mut *conn)
            .await?;
    if !settings_exists {
        return Err(DbError::CatalogInvariantViolation(
            "missing seeded restaurant_settings row id=1".to_owned(),
        ));
    }

    let cursor_exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM event_stream_cursor WHERE id = 1)")
            .fetch_one(&mut *conn)
            .await?;
    if !cursor_exists {
        return Err(DbError::CatalogInvariantViolation(
            "missing seeded event_stream_cursor row id=1".to_owned(),
        ));
    }

    Ok(())
}

async fn ensure_sqlx_baseline_1_to_3(conn: &mut PgConnection) -> Result<(), DbError> {
    let mut tx = conn.begin().await?;

    sqlx::query(
        r#"
        CREATE TABLE IF NOT EXISTS _sqlx_migrations (
            version BIGINT PRIMARY KEY,
            description TEXT NOT NULL,
            installed_on TIMESTAMPTZ NOT NULL DEFAULT now(),
            success BOOLEAN NOT NULL,
            checksum BYTEA NOT NULL,
            execution_time BIGINT NOT NULL
        )
        "#,
    )
    .execute(&mut *tx)
    .await?;

    for migration in MIGRATOR.iter().filter(|m| m.version >= 1 && m.version <= 3) {
        let existing = sqlx::query(
            "SELECT success, checksum FROM _sqlx_migrations WHERE version = $1 FOR UPDATE",
        )
        .bind(migration.version)
        .fetch_optional(&mut *tx)
        .await?;

        if let Some(row) = existing {
            let success: bool = row.try_get("success")?;
            let existing_checksum: Vec<u8> = row.try_get("checksum")?;
            if !success {
                return Err(DbError::UnsafeDatabaseState(format!(
                    "_sqlx_migrations version {} is marked failed",
                    migration.version
                )));
            }
            if existing_checksum.as_slice() != migration.checksum.as_ref() {
                return Err(DbError::SqlxChecksumMismatch {
                    version: migration.version,
                });
            }
        } else {
            sqlx::query(
                r#"
                INSERT INTO _sqlx_migrations (version, description, success, checksum, execution_time)
                VALUES ($1, $2, TRUE, $3, 0)
                ON CONFLICT (version) DO NOTHING
                "#,
            )
            .bind(migration.version)
            .bind(migration.description.as_ref())
            .bind(migration.checksum.as_ref())
            .execute(&mut *tx)
            .await?;
        }
    }

    tx.commit().await?;
    Ok(())
}

async fn verify_existing_sqlx_migrations(conn: &mut PgConnection) -> Result<(), DbError> {
    let rows =
        sqlx::query("SELECT version, success, checksum FROM _sqlx_migrations ORDER BY version ASC")
            .fetch_all(&mut *conn)
            .await?;

    for row in rows {
        let version: i64 = row.try_get("version")?;
        let success: bool = row.try_get("success")?;
        let existing_checksum: Vec<u8> = row.try_get("checksum")?;
        if !success {
            return Err(DbError::UnsafeDatabaseState(format!(
                "existing _sqlx_migrations version {version} has success = FALSE"
            )));
        }
        if let Some(expected) = MIGRATOR.iter().find(|m| m.version == version) {
            if existing_checksum.as_slice() != expected.checksum.as_ref() {
                return Err(DbError::SqlxChecksumMismatch { version });
            }
        } else {
            return Err(DbError::UnsafeDatabaseState(format!(
                "unknown migration version {version} present in _sqlx_migrations; manual DBA intervention required"
            )));
        }
    }
    Ok(())
}

async fn collect_applied_versions(conn: &mut PgConnection) -> Result<Vec<i64>, DbError> {
    let versions: Vec<i64> = sqlx::query_scalar(
        "SELECT version FROM _sqlx_migrations WHERE success = TRUE ORDER BY version ASC",
    )
    .fetch_all(&mut *conn)
    .await?;
    Ok(versions)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn decode_b64_fixture(b64: &str) -> Vec<u8> {
        const TABLE: [i8; 256] = {
            let mut t = [-1i8; 256];
            let mut i = 0;
            while i < 26 {
                t[(b'A' + i) as usize] = i as i8;
                t[(b'a' + i) as usize] = (i + 26) as i8;
                i += 1;
            }
            let mut d = 0;
            while d < 10 {
                t[(b'0' + d) as usize] = (d + 52) as i8;
                d += 1;
            }
            t[b'+' as usize] = 62;
            t[b'/' as usize] = 63;
            t
        };
        let mut out = Vec::new();
        let bytes = b64.as_bytes();
        let mut i = 0;
        while i + 3 < bytes.len() {
            let a = TABLE[bytes[i] as usize] as u32;
            let b = TABLE[bytes[i + 1] as usize] as u32;
            let c = if bytes[i + 2] == b'=' {
                0
            } else {
                TABLE[bytes[i + 2] as usize] as u32
            };
            let d = if bytes[i + 3] == b'=' {
                0
            } else {
                TABLE[bytes[i + 3] as usize] as u32
            };
            let triple = (a << 18) | (b << 12) | (c << 6) | d;
            out.push(((triple >> 16) & 0xFF) as u8);
            if bytes[i + 2] != b'=' {
                out.push(((triple >> 8) & 0xFF) as u8);
            }
            if bytes[i + 3] != b'=' {
                out.push((triple & 0xFF) as u8);
            }
            i += 4;
        }
        out
    }

    #[test]
    fn flyway_crc32_matches_real_flyway_11_3_4_oracle_vectors() {
        // Golden vectors produced by org.flywaydb:flyway-core:11.3.4 in tools/golden-vectors/flyway_11_3_4_checksums.json
        assert_eq!(flyway_crc32(CANONICAL_V1_BYTES).unwrap(), -1_549_990_012);
        assert_eq!(flyway_crc32(CANONICAL_V2_BYTES).unwrap(), -1_701_246_598);
        assert_eq!(flyway_crc32(CANONICAL_V3_BYTES).unwrap(), -1_177_665_950);

        let lf = decode_b64_fixture("Q1JFQVRFIFRBQkxFIG9yYWNsZV9sZiAoaWQgSU5UIFBSSU1BUlkgS0VZLCBub3RlIFRFWFQpOwpJTlNFUlQgSU5UTyBvcmFjbGVfbGYgVkFMVUVTICgxLCAnaGVsbG8nKTsK");
        assert_eq!(flyway_crc32(&lf).unwrap(), 2_027_193_767);

        let crlf = decode_b64_fixture("Q1JFQVRFIFRBQkxFIG9yYWNsZV9jcmxmIChpZCBJTlQgUFJJTUFSWSBLRVksIG5vdGUgVEVYVCk7DQpJTlNFUlQgSU5UTyBvcmFjbGVfY3JsZiBWQUxVRVMgKDEsICdoZWxsbyBjcmxmJyk7DQo=");
        assert_eq!(flyway_crc32(&crlf).unwrap(), 166_026_996);

        let utf8 = decode_b64_fixture("LS0gUElaWk9SQSDigrkyOTkuMDAg4KSq4KS/4KSc4KS84KWN4KSc4KS84KS+CkNSRUFURSBUQUJMRSBvcmFjbGVfdXRmOCAoaWQgSU5UIFBSSU1BUlkgS0VZLCBsYWJlbCBURVhUIERFRkFVTFQgJ+KCuTI5OS4wMCcpOwo=");
        assert_eq!(flyway_crc32(&utf8).unwrap(), -683_103_230);

        let bom = decode_b64_fixture("77u/LS0gQk9NIHRlc3Qg4oK5CkNSRUFURSBUQUJMRSBvcmFjbGVfYm9tIChpZCBJTlQgUFJJTUFSWSBLRVkpOwo=");
        assert_eq!(flyway_crc32(&bom).unwrap(), 1_730_716_617);

        let no_nl =
            decode_b64_fixture("Q1JFQVRFIFRBQkxFIG9yYWNsZV9ub19ubCAoaWQgSU5UIFBSSU1BUlkgS0VZKTs=");
        assert_eq!(flyway_crc32(&no_nl).unwrap(), -1_061_589_932);
    }

    #[test]
    fn expected_table_and_index_counts_match_canonical_schema() {
        assert_eq!(EXPECTED_TABLES.len(), 46);
        assert_eq!(EXPECTED_V3_INDEXES.len(), 13);
        let unique_tables: HashSet<&str> = EXPECTED_TABLES.iter().copied().collect();
        assert_eq!(unique_tables.len(), 46);
    }

    #[test]
    fn canonical_v1_v3_specs_and_invalid_utf8_check() {
        let specs = canonical_v1_v3_specs().expect("canonical V1..V3 specs");
        assert_eq!(specs[0].0, "1");
        assert_eq!(specs[0].1, "V1__core_schema.sql");
        assert_eq!(specs[0].2, -1_549_990_012);
        assert_eq!(specs[1].0, "2");
        assert_eq!(specs[1].1, "V2__seed_rbac_and_settings.sql");
        assert_eq!(specs[1].2, -1_701_246_598);
        assert_eq!(specs[2].0, "3");
        assert_eq!(specs[2].1, "V3__hot_path_indexes.sql");
        assert_eq!(specs[2].2, -1_177_665_950);

        let err = flyway_crc32(&[0xFF, 0xFE, 0xFD]).expect_err("invalid UTF-8 must fail");
        assert!(matches!(err, DbError::FlywayHistoryInvalid(_)));
    }

    async fn count_active_migration_advisory_locks(pool: &PgPool) -> i64 {
        sqlx::query_scalar(
            "SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND objid::bigint = $1",
        )
        .bind(MIGRATION_ADVISORY_LOCK_KEY)
        .fetch_one(pool)
        .await
        .expect("query pg_locks")
    }

    async fn create_isolated_test_db(
        admin_pool: &PgPool,
        base_opts: &sqlx::postgres::PgConnectOptions,
        prefix: &str,
    ) -> (String, PgPool) {
        let suffix = crate::error::new_uuid_v4().simple().to_string();
        let db_name = format!("{prefix}_{}", &suffix[..12]);
        let create_sql = format!("CREATE DATABASE \"{db_name}\"");
        sqlx::query(&create_sql)
            .execute(admin_pool)
            .await
            .expect("create isolated test database");
        let opts = base_opts.clone().database(&db_name);
        let pool = sqlx::postgres::PgPoolOptions::new()
            .max_connections(4)
            .connect_with(opts)
            .await
            .expect("connect isolated test database");
        (db_name, pool)
    }

    async fn drop_isolated_test_db(admin_pool: &PgPool, db_name: &str, pool: PgPool) {
        pool.close().await;
        let _ = sqlx::query(
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = $1 AND pid <> pg_backend_pid()",
        )
        .bind(db_name)
        .execute(admin_pool)
        .await;
        let drop_sql = format!("DROP DATABASE IF EXISTS \"{db_name}\"");
        let _ = sqlx::query(&drop_sql).execute(admin_pool).await;
    }

    #[tokio::test]
    #[ignore = "requires live PostgreSQL 18.6 (TEST_POSTGRES_URL / DATABASE_URL with CREATEDB)"]
    async fn pg18_locked_handoff_fresh_adopt_tamper_and_concurrency() {
        use pizzora_config::{AppConfig, DatabaseConfig, NormalizedDatabaseUrl};
        use sqlx::postgres::PgPoolOptions;

        let base_url = match std::env::var("TEST_POSTGRES_URL") {
            Ok(v) if !v.trim().is_empty() => zeroize::Zeroizing::new(v),
            _ => AppConfig::from_env()
                .expect("config from env")
                .database
                .url
                .normalized_postgres_url(),
        };
        let normalized =
            NormalizedDatabaseUrl::parse(&base_url, None, None).expect("valid PostgreSQL URL");
        let db_cfg = DatabaseConfig {
            url: normalized,
            max_pool_size: 4,
            min_connections: 1,
            connect_timeout_ms: 5_000,
            run_migrations_on_startup: true,
        };
        let base_opts = crate::pool::build_pg_connect_options(&db_cfg).expect("pg connect opts");
        let admin_pool = PgPoolOptions::new()
            .max_connections(4)
            .connect_with(base_opts.clone())
            .await
            .expect("connect PostgreSQL admin pool");

        // 1. Fresh empty DB bootstrap -> idempotent ResumedSqlx
        let (fresh_db, fresh_pool) =
            create_isolated_test_db(&admin_pool, &base_opts, "pizzora_it_fresh").await;
        let fresh_outcome = run_locked_handoff(&fresh_pool)
            .await
            .expect("fresh bootstrap succeeds");
        assert_eq!(fresh_outcome.mode, HandoffMode::FreshBootstrap);
        assert_eq!(fresh_outcome.applied_or_verified_versions, vec![1, 2, 3, 4]);
        assert_eq!(count_active_migration_advisory_locks(&admin_pool).await, 0);

        let resumed_outcome = run_locked_handoff(&fresh_pool)
            .await
            .expect("subsequent run resumes cleanly");
        assert_eq!(resumed_outcome.mode, HandoffMode::ResumedSqlx);
        assert_eq!(
            resumed_outcome.applied_or_verified_versions,
            vec![1, 2, 3, 4]
        );
        assert_eq!(count_active_migration_advisory_locks(&admin_pool).await, 0);
        drop_isolated_test_db(&admin_pool, &fresh_db, fresh_pool).await;

        // 2. Existing Flyway V1..V3 adoption, partial baseline recovery, tamper & invariant checks
        let (adopt_db, adopt_pool) =
            create_isolated_test_db(&admin_pool, &base_opts, "pizzora_it_adopt").await;
        for bytes in [CANONICAL_V1_BYTES, CANONICAL_V2_BYTES, CANONICAL_V3_BYTES] {
            let sql_text = std::str::from_utf8(bytes).expect("UTF-8 DDL");
            sqlx::raw_sql(sql_text)
                .execute(&adopt_pool)
                .await
                .expect("apply Flyway V1-V3 SQL");
        }
        sqlx::query(
            r#"
            CREATE TABLE flyway_schema_history (
                installed_rank INT NOT NULL PRIMARY KEY,
                version VARCHAR(50),
                description VARCHAR(200) NOT NULL,
                type VARCHAR(20) NOT NULL,
                script VARCHAR(1000) NOT NULL,
                checksum INT,
                installed_by VARCHAR(100) NOT NULL,
                installed_on TIMESTAMPTZ NOT NULL DEFAULT now(),
                execution_time INT NOT NULL,
                success BOOLEAN NOT NULL
            )
            "#,
        )
        .execute(&adopt_pool)
        .await
        .expect("create flyway_schema_history");

        sqlx::query(
            r#"
            INSERT INTO flyway_schema_history
                (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
            VALUES (0, NULL, '<< Flyway Schema Creation >>', 'SCHEMA', '"public"', NULL, 'pizzora', 0, TRUE)
            "#,
        )
        .execute(&adopt_pool)
        .await
        .expect("insert Flyway schema creation row");

        for (idx, (ver, script, crc)) in canonical_v1_v3_specs()
            .expect("specs")
            .into_iter()
            .enumerate()
        {
            sqlx::query(
                r#"
                INSERT INTO flyway_schema_history
                    (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                VALUES ($1, $2, $3, 'SQL', $4, $5, 'pizzora', 12, TRUE)
                "#,
            )
            .bind((idx + 1) as i32)
            .bind(ver)
            .bind(script)
            .bind(script)
            .bind(crc)
            .execute(&adopt_pool)
            .await
            .expect("insert Flyway V1..V3 row");
        }

        let preflight_before = verify_migration_readiness_readonly(&adopt_pool)
            .await
            .expect("read-only preflight succeeds on valid Flyway V1-V3 DB");
        assert!(preflight_before.flyway_table_exists);
        assert!(!preflight_before.sqlx_table_exists);
        assert!(preflight_before.flyway_verified);
        assert!(!preflight_before.sqlx_verified);
        assert!(preflight_before.catalog_invariants_ok);
        assert_eq!(preflight_before.flyway_versions, vec!["1", "2", "3"]);
        assert!(preflight_before.sqlx_versions.is_empty());

        let adopt_outcome = run_locked_handoff(&adopt_pool)
            .await
            .expect("adopt Flyway V1..V3 without re-running DDL");
        assert_eq!(adopt_outcome.mode, HandoffMode::AdoptedExistingFlyway);
        assert_eq!(adopt_outcome.applied_or_verified_versions, vec![1, 2, 3, 4]);
        let preflight_after = verify_migration_readiness_readonly(&adopt_pool)
            .await
            .expect("read-only preflight succeeds after SQLx adoption");
        assert!(preflight_after.sqlx_table_exists);
        assert!(preflight_after.sqlx_verified);
        assert_eq!(preflight_after.sqlx_versions, vec![1, 2, 3, 4]);
        let zero_exec_rows: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM _sqlx_migrations WHERE version BETWEEN 1 AND 3 AND execution_time = 0",
        )
        .fetch_one(&adopt_pool)
        .await
        .expect("count adopted baseline rows");
        assert_eq!(zero_exec_rows, 3);
        assert_eq!(count_active_migration_advisory_locks(&admin_pool).await, 0);

        // Partial _sqlx_migrations baseline recovery (simulate crash after inserting 1..2)
        sqlx::query("DELETE FROM _sqlx_migrations WHERE version = 3")
            .execute(&adopt_pool)
            .await
            .expect("delete baseline v3");
        let recovered = run_locked_handoff(&adopt_pool)
            .await
            .expect("partial baseline recovers cleanly");
        assert_eq!(recovered.mode, HandoffMode::AdoptedExistingFlyway);
        assert_eq!(recovered.applied_or_verified_versions, vec![1, 2, 3, 4]);

        // Tampered Flyway checksum -> MigrationChecksumMismatch & lock released
        let original_v2_crc = canonical_v1_v3_specs().unwrap()[1].2;
        sqlx::query("UPDATE flyway_schema_history SET checksum = 123456 WHERE version = '2'")
            .execute(&adopt_pool)
            .await
            .expect("tamper checksum");
        let err = run_locked_handoff(&adopt_pool)
            .await
            .expect_err("tampered checksum must fail");
        assert!(matches!(err, DbError::MigrationChecksumMismatch { .. }));
        assert_eq!(count_active_migration_advisory_locks(&admin_pool).await, 0);
        sqlx::query("UPDATE flyway_schema_history SET checksum = $1 WHERE version = '2'")
            .bind(original_v2_crc)
            .execute(&adopt_pool)
            .await
            .expect("restore checksum");

        // Failed / unexpected Flyway row -> FlywayHistoryInvalid & lock released
        sqlx::query(
            r#"
            INSERT INTO flyway_schema_history
                (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
            VALUES (99, '99', 'rogue', 'SQL', 'V99__rogue.sql', 0, 'pizzora', 1, FALSE)
            "#,
        )
        .execute(&adopt_pool)
        .await
        .expect("insert rogue row");
        let err = run_locked_handoff(&adopt_pool)
            .await
            .expect_err("failed/unexpected Flyway row must fail");
        assert!(matches!(err, DbError::FlywayHistoryInvalid(_)));
        assert_eq!(count_active_migration_advisory_locks(&admin_pool).await, 0);
        sqlx::query("DELETE FROM flyway_schema_history WHERE installed_rank = 99")
            .execute(&adopt_pool)
            .await
            .expect("cleanup rogue row");

        // Missing V3 hot-path index -> CatalogInvariantViolation & lock released
        sqlx::query("DROP INDEX idx_orders_created_at")
            .execute(&adopt_pool)
            .await
            .expect("drop index to trigger catalog check");
        let err = run_locked_handoff(&adopt_pool)
            .await
            .expect_err("missing index must fail catalog invariant check");
        assert!(matches!(err, DbError::CatalogInvariantViolation(_)));
        assert_eq!(count_active_migration_advisory_locks(&admin_pool).await, 0);
        drop_isolated_test_db(&admin_pool, &adopt_db, adopt_pool).await;

        // 3. Existing table(s) without history -> UnsafeDatabaseState & lock released
        let (dirty_db, dirty_pool) =
            create_isolated_test_db(&admin_pool, &base_opts, "pizzora_it_dirty").await;
        sqlx::query("CREATE TABLE rogue_table (id INT PRIMARY KEY)")
            .execute(&dirty_pool)
            .await
            .expect("create orphan table");
        let err = run_locked_handoff(&dirty_pool)
            .await
            .expect_err("tables without history must abort");
        assert!(matches!(err, DbError::UnsafeDatabaseState(_)));
        assert_eq!(count_active_migration_advisory_locks(&admin_pool).await, 0);
        drop_isolated_test_db(&admin_pool, &dirty_db, dirty_pool).await;
    }
}

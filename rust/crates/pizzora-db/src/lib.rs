//! Shared persistence, Redis, advisory-locked Flyway->SQLx migrator, and transactional services (Phase 5).
//! Note: `pizzora-db` intentionally has ZERO outbox encryption keys or AEAD decryption logic.

pub mod audit;
pub mod coupons;
pub mod error;
pub mod inventory;
pub mod migrator;
pub mod notifications;
pub mod orders;
pub mod outbox;
pub mod pool;
pub mod redis;
pub mod users;
pub mod ws_tickets;

pub use audit::{
    record_audit_log, AuditContext, AuditLogEntryDto, AuditLogsResponse, AuditService,
};
pub use coupons::{
    AppliedCouponSummaryDto, AvailableCouponsResponse, CouponDto, CouponService,
    CreateCouponRequest, CustomerCouponDto, UpdateCouponRequest, ValidatedCoupon,
};
pub use error::{new_uuid_v4, DbError};
pub use inventory::{
    AdjustInventoryRequest, CreateIngredientRequest, IngredientUnit, InventoryItemDto,
    InventoryLedgerEntryDto, InventoryLedgerReason, InventoryService, UpdateIngredientRequest,
};
pub use migrator::{
    flyway_crc32, run_locked_handoff, run_locked_handoff_with_hook, verify_baseline_invariants,
    verify_catalog_invariants, verify_migration_readiness_readonly, HandoffMode, HandoffOutcome,
    MigrationPreflightReport, CANONICAL_V1_BYTES, CANONICAL_V2_BYTES, CANONICAL_V3_BYTES,
    EXPECTED_BASELINE_TABLES, EXPECTED_PHASE8_INDEXES, EXPECTED_TABLES, EXPECTED_V3_INDEXES,
    MIGRATION_ADVISORY_LOCK_KEY, MIGRATOR,
};
pub use notifications::{
    MarkAllNotificationsReadResponse, MarkNotificationReadResponse, NotificationDeliveryStatus,
    NotificationInboxItemDto, NotificationInboxResponse, NotificationPreferencesDto,
    NotificationService, UpdateNotificationPreferencesRequest, CATEGORY_ORDER_UPDATE,
    CATEGORY_PROMO_ALERT,
};
pub use orders::{
    DeliveryAddressSnapshotDto, OrderDetailDto, OrderItemDto, OrderListResponse, OrderService,
    OrderStatusHistoryDto, OrderSummaryDto, PaymentAttemptSummaryDto, PaymentSummaryDto,
};
pub use outbox::{
    ClaimedOutboxEvent, OutboxRepository, EVENT_EMAIL_DELIVERY_REQUESTED,
    EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED, EVENT_REFUND_REQUESTED,
};
pub use pool::{
    build_pg_connect_options, connect_pg_pool, connect_pg_pool_lazy, create_pg_pool_options,
    probe_postgres, DependencyHealthStatus,
};
pub use redis::{EphemeralCacheStore, InMemoryCacheStore, RedisStore};
pub use users::{
    AddressDto, StaffMemberDto, UpdateProfileRequest, UpdateStaffRequest, UpsertAddressRequest,
    UserProfileDto, UsersService,
};
pub use ws_tickets::{parse_user_role, user_role_as_str, WsTicketPrincipal, WsTicketRepository};

pub fn crate_name() -> &'static str {
    env!("CARGO_PKG_NAME")
}

#[cfg(test)]
mod tests {
    use super::migrator::{
        flyway_crc32, run_locked_handoff_with_hook, CANONICAL_V1_BYTES, CANONICAL_V2_BYTES,
        CANONICAL_V3_BYTES, MIGRATOR,
    };
    use super::*;
    use std::path::PathBuf;
    use std::sync::Arc;

    #[derive(serde::Deserialize)]
    struct FlywayChecksumsBundle {
        canonical_migrations: Vec<FlywayVector>,
        synthetic_fixtures: Vec<FlywaySyntheticVector>,
    }

    #[derive(serde::Deserialize)]
    struct FlywayVector {
        version: String,
        checksum: i32,
    }

    #[derive(serde::Deserialize)]
    struct FlywaySyntheticVector {
        name: String,
        raw_bytes_base64: String,
        checksum: i32,
    }

    fn golden_dir() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("..")
            .join("..")
            .join("tools")
            .join("golden-vectors")
    }

    #[test]
    fn flyway_crc32_matches_oracle_across_v1_to_v3_and_synthetic_vectors() {
        use base64::engine::general_purpose::STANDARD;
        use base64::Engine as _;

        let raw = std::fs::read_to_string(golden_dir().join("flyway_11_3_4_checksums.json"))
            .expect("flyway_11_3_4_checksums.json should exist");
        let bundle: FlywayChecksumsBundle =
            serde_json::from_str(&raw).expect("valid flyway checksum JSON");

        for entry in bundle.canonical_migrations {
            let bytes = match entry.version.as_str() {
                "1" => CANONICAL_V1_BYTES,
                "2" => CANONICAL_V2_BYTES,
                "3" => CANONICAL_V3_BYTES,
                other => panic!("Unexpected canonical version {other}"),
            };
            let actual = flyway_crc32(bytes).expect("checksum computes");
            assert_eq!(actual, entry.checksum, "Mismatch for V{}", entry.version);
        }

        for synth in bundle.synthetic_fixtures {
            let bytes = STANDARD
                .decode(synth.raw_bytes_base64.as_bytes())
                .expect("valid base64 fixture");
            let actual = flyway_crc32(&bytes).expect("synthetic checksum");
            assert_eq!(actual, synth.checksum, "Mismatch for {}", synth.name);
        }
    }

    #[test]
    fn sql_migrations_0001_to_0003_are_embedded_in_migrator() {
        let versions: Vec<i64> = MIGRATOR.iter().map(|m| m.version).collect();
        assert!(versions.starts_with(&[1, 2, 3]));
    }

    #[test]
    fn audit_cursor_round_trips() {
        let now = chrono::Utc::now();
        let id = new_uuid_v4();
        let encoded = audit::encode_cursor(now, id);
        let decoded = audit::decode_cursor(&encoded).expect("cursor decodes");
        assert_eq!(decoded.id, id);
        assert_eq!(decoded.created_at.timestamp(), now.timestamp());
    }

    #[test]
    fn in_memory_cache_supports_ws_ticket_set_get_del_semantics() {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            let cache = Arc::new(InMemoryCacheStore::new());
            assert!(cache.set_ex("ws_ticket:abc", 30, "u1|s1|OWNER").await);
            assert_eq!(
                cache.get_del("ws_ticket:abc").await.as_deref(),
                Some("u1|s1|OWNER")
            );
            assert_eq!(cache.get_del("ws_ticket:abc").await, None);
        });
    }

    #[tokio::test]
    #[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
    async fn pg18_fresh_and_concurrent_handoff_under_advisory_lock() {
        let cfg = pizzora_config::AppConfig::from_env().expect("env config");
        let pool = connect_pg_pool(&cfg.database).await.expect("connect pg");
        let outcome1 = run_locked_handoff(&pool).await.expect("first handoff");
        assert!(outcome1.backend_pid > 0);

        let barrier = Arc::new(tokio::sync::Barrier::new(2));
        let p1 = pool.clone();
        let b1 = barrier.clone();
        let t1 = tokio::spawn(async move {
            run_locked_handoff_with_hook(&p1, |_pid| {
                let b = b1.clone();
                async move {
                    b.wait().await;
                    tokio::time::sleep(std::time::Duration::from_millis(200)).await;
                    Ok(())
                }
            })
            .await
        });

        let p2 = pool.clone();
        let b2 = barrier.clone();
        let t2 = tokio::spawn(async move {
            b2.wait().await;
            run_locked_handoff(&p2).await
        });

        let (r1, r2) = tokio::join!(t1, t2);
        r1.unwrap().expect("runner 1 succeeds");
        let o2 = r2.unwrap().expect("runner 2 blocks and succeeds cleanly");
        assert_eq!(o2.mode, HandoffMode::ResumedSqlx);
    }
}

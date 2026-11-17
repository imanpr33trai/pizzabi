# PIZZORA — Kotlin-to-Rust Architecture Migration Plan (Rev. 12)

An incremental, production-safe migration blueprint to fix immediate client cart-isolation and payment defects first, establish a Rust-first shared domain (`pizzora-core` + `Mozilla UniFFI`) and backend (`Axum` + `Tokio` + `SQLx` on **PostgreSQL 18.6**), and execute a staging-validated Railway cutover with strict rollback guarantees.

---

## User Review & Confirmed Architecture Decisions

> [!IMPORTANT]
> All previously approved architecture decisions (8-crate Cargo workspace, Flyway `V1`–`V3` historical + SQLx `V4+`, `arm64-v8a` + `x86_64` via `cargo-ndk 4.1.2` + `uniffi = "=0.29.3"`, `Rust 1.88.0`, Gradle Wrapper `9.3.1`, Razorpay dependency locking, separate Railway staging DB/service) remain preserved. Revision 11 resolves the final 6 audit & operational safety items.

- **Workspace Modularity**: **Full 8-crate Cargo workspace** (`pizzora-core`, `pizzora-uniffi`, `pizzora-config`, `pizzora-db`, `pizzora-auth`, `pizzora-payments`, `pizzora-workers`, `pizzora-api`) with separate API and Worker runtime binaries and a strictly acyclic dependency DAG.
- **PostgreSQL & Migration Ownership**: **PostgreSQL 18.6** everywhere; keep **Flyway `11.3.4` `V1`–`V3` historical**; adopt **`sqlx = "=0.8.6"`** for `V4+` under a **single dedicated connection advisory lock** (`pg_advisory_lock`) verified against **Flyway `11.3.4` itself as checksum oracle**.
- **Android ABIs & Build Workflow**: Build **`arm64-v8a` and `x86_64`** Android `.so` binaries via pinned **`cargo-ndk 4.1.2`**, strictly separated from the **host cdylib** used by pinned **`uniffi = "=0.29.3"`** bindgen and Robolectric JVM tests, under pinned **Rust `1.88.0`**.
- **Build & CI Invocation**: Commit the complete **Gradle Wrapper (`9.3.1`)** verified by distribution SHA-256 checksum and invoke `./gradlew` everywhere (no reliance on system-installed `gradle`).
- **Pre-Implementation Git Branch Gate**: All modifications MUST occur on a dedicated branch **`feat/pizzora-rust-migration`** created after inspecting working-tree cleanliness (never on `main`, `master`, or the original branch).

---

## Revision Change Log (From Rev. 10 -> Rev. 11)

1. **Email Delivery Semantics (Issue 1)**: Removed the inaccurate exactly-once email claim; explicitly documented **at-least-once** delivery semantics, the **SMTP-accepted / worker-crash / before-`DISPATCHED`-DB-update** failure window, and possible duplicate email delivery on lease expiry/retry. Added stable `outbox_events.id` correlation, deterministic RFC 5322 `Message-ID` headers (`<outbox-{id}@{domain}>`), and fault-injection crash tests.
2. **Authentication Token Security (Issue 2)**: Forbade plaintext password-reset or email-verification tokens inside `outbox_events.payload_json`. Preserved SHA-256 **`token_hash`** storage in `email_verification_tokens` / `password_reset_tokens` while adding **AEAD (`AES-256-GCM`) envelope encryption** bound with outbox AAD inside `pizzora-auth`, defining keyring ownership (`OutboxEncryptionConfig`), `kid` rotation, post-dispatch/post-TTL token scrubbing, `DEAD_LETTER`/log redaction, and security tests.
3. **Flyway `11.3.4` Checksum Oracle & BOM Verification (Issue 3)**: Removed the assumption that `BufferedReader.readLine()` strips BOMs. Designated **Flyway `11.3.4` itself** (`org.flywaydb.core` executed in `:backend:test`) as the authoritative checksum oracle across `V1`–`V3` plus synthetic **LF, CRLF, UTF-8 multibyte, and UTF-8 BOM** fixtures, aligning Rust `pizzora_db::migrator::flyway_crc32` to the empirically observed Flyway `11.3.4` output with regression tests.
4. **PostgreSQL Dedicated-Connection Advisory Lock (Issue 4)**: Mandated acquiring and holding `pg_advisory_lock(884210001)` on a **single dedicated `PgConnection` (`pool.acquire()`)** across all verification queries, `_sqlx_migrations` baseline insertion, `Migrator::run`, and explicit `pg_advisory_unlock` (never releasing the connection back to `PgPool` mid-handoff), plus a two-runner concurrency test.
5. **Payment Receipt Idempotency & Conflict Semantics (Issue 5)**: Defined exact behavior under `orders -> payments` `FOR UPDATE` locking + `UNIQUE(idempotency_receipt)`: same `(order_id, idempotency_key)` with compatible parameters **idempotently reuses** the existing `payment_attempt`; mismatched parameters or cross-order receipt collisions **reject safely with `409 Conflict` (`IDEMPOTENCY_KEY_CONFLICT`)**; added concurrent retry tests.
6. **Pre-Implementation Git Branch Safety (Issue 6)**: Added a mandatory non-negotiable pre-implementation Git inspection and branch-creation gate (`feat/pizzora-rust-migration`), stopping immediately if uncommitted changes exist and prohibiting commits/pushes/tags/PRs/deploys without approval.

---

## Revision Change Log (From Rev. 11 -> Rev. 12)

1. **Restored Full API Compatibility Inventory**: Reintroduced the route-group compatibility matrix and explicit route-by-route parity gate, with Rev. 11 receipt, auth, ownership and migration decisions taking precedence over historical examples.
2. **Restored Detailed Phase Execution Cards**: Expanded Phases 0–9 with purpose, preconditions, likely files, implementation requirements, tests, acceptance criteria and rollback boundaries.
3. **Restored Railway Operations Runbook**: Added separate staging/production topology, environment-variable classification, URL normalization, worker topology, health, backup, cutover and rollback checks.
4. **Restored Risk & Verification Matrices**: Added cross-stack evidence requirements, release gates, and an expanded risk register.
5. **Restored Exact First Task & Agent Workflow**: Added phase-scoped file inspection, implementation boundaries, test reporting and approval requirements.
6. **Cumulative Revision Policy**: Added a policy prohibiting silent condensation or removal of previously approved implementation detail.
7. **Git Branch Gate Clarification**: Added safe handling for an already-existing migration branch; no reset, stash, overwrite or automatic dirty-tree cleanup.

## 1. Current Architecture Assessment _(Verified Existing Functionality)_

- **Android App (`app/`)**: Kotlin + Jetpack Compose M3, manual DI (`AppContainer`), Ktor `HttpClient` (`OkHttp`), `AndroidKeyStore` AES-256-GCM + `DataStore` (`TokenManager`), `Room` `2.7.0` v2 (`PizzoraDatabase` with `exportSchema = false` and `fallbackToDestructiveMigration(dropAllTables = true)`), `WorkManager` (`OrderSyncWorker`), `WebSocketEventClient`, and placeholder manual-entry `RazorpayPaymentSheetDialog`.
- **Verified Critical Bugs**:
  1. **P0 Cart & Inbox Leakage**: `CartItemEntity` lacks `user_id`; `NotificationInboxEntity` lacks `user_id` and enforces a global unique index on `event_id`; `NetworkAuthRepository.logout()` and `PizzoraApiClient.refreshTokens` fail-path clear tokens without clearing Room; `NetworkCartRepository.syncAndValidateCartInternal()` pushes stale Room items via `PUT /api/v1/cart`.
  2. **Payment Retry Receipt Overflow & Race Window**: `CheckoutService` uses `attemptId.toString()` (36 chars), whereas `PaymentService.retryPayment()` uses `"retry:$orderId:$cleanIdempKey"` (42+ chars), violating Razorpay's 40-char `receipt` cap and lacking full cross-order/payload conflict semantics under concurrency.
- **Missing Wrapper Scripts**: `gradle/wrapper/gradle-wrapper.properties` specifies `gradle-9.3.1-bin.zip`, while `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are absent.
- **Backend (`backend/`) & Shared (`shared/contracts/`)**: Ktor 3.2.3 + Netty (`:8080`), `WorkerMainKt` (`:8081`), raw JDBC + **Flyway `11.3.4`** `V1`–`V3` (46 tables, 3 roles, 22 permissions, 13 indexes), SHA-256 hashed verification/reset tokens (`email_verification_tokens`, `password_reset_tokens`), Argon2id (`m=65536,t=3,p=1`), HS256 JWT + rotated refresh tokens, `LettuceRedisStore` w/ fallbacks, optional `S3_ENABLED`, SMTP (`angus-mail`), and Prometheus `/metrics`.
- **Railway (`.railway/railway.ts`)**: Deploys API service `pizzabi` in project `bubbly-peace` against **PostgreSQL 18.6** in production.

---

## 2. Eight-Crate Rust Workspace & Complete Dependency DAG

### 2.1 Crate Ownership & Cargo Target Definitions

Every crate lives under `/rust/crates/<name>/` and inherits pinned versions from `/rust/Cargo.toml`:

| Crate                  | Cargo Targets                                                 | Pinned Dependencies                                                                                                                                                                               | Explicit Ownership                                                                                                                                                                                                                                                                                                                                                                                 |
| :--------------------- | :------------------------------------------------------------ | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`pizzora-core`**     | `[lib]`                                                       | `serde`, `thiserror`, `uuid`                                                                                                                                                                      | **Pure domain (zero I/O, zero crypto)**: `Money`, `PricingEngine`, `CouponEvaluator`, `OrderStateMachine`, `PaymentStateMachine`, `OrderCausalTracker`, `CartValidation`, `CartSyncPlanner`, `StorageKeyValidator`, `DomainError`.                                                                                                                                                                 |
| **`pizzora-uniffi`**   | `[lib]` (`lib`, `cdylib`) + `[[bin]] name = "uniffi-bindgen"` | `pizzora-core`, `uniffi = { version = "=0.29.3", features = ["cli"] }`, `thiserror`                                                                                                               | **Android FFI boundary only**: UniFFI exports + workspace-locked `uniffi-bindgen` binary. Never imported by backend crates.                                                                                                                                                                                                                                                                        |
| **`pizzora-config`**   | `[lib]`                                                       | `serde`, `thiserror`, `url`                                                                                                                                                                       | **Env & validation parity**: Normalizes DB URLs, `S3_ENABLED`, `PIZZORA_ENV=production` guards (rejecting missing JWT/Razorpay/outbox encryption keys), `WorkerConfig`, `SmtpConfig`, `OutboxEncryptionConfig`, `RazorpayConfig`, `SecurityConfig`.                                                                                                                                                |
| **`pizzora-db`**       | `[lib]`                                                       | `pizzora-core`, `pizzora-config`, `sqlx = "=0.8.6"`, `crc32fast`, `redis`, `chrono`, `uuid`, `tokio`, `tracing`, `serde_json`                                                                     | **Shared Persistence, Redis & Transactional Services**: PostgreSQL 18.6 `PgPool`, dedicated-connection advisory-locked Flyway->SQLx migrator (`0001..0003` + `V4+`), `flyway_crc32` (oracle-matched to Flyway `11.3.4`), `RedisStore`, `Outbox` SQL, `OrderService`, `InventoryService`, `CouponService`, `NotificationService`, `AuditService`, domain repositories. _(Zero outbox crypto keys.)_ |
| **`pizzora-auth`**     | `[lib]`                                                       | `pizzora-core`, `pizzora-config`, `pizzora-db`, `argon2`, `jsonwebtoken`, `sha2`, `aes-gcm`, `zeroize`, `base64`, `hex`, `rand`, `lettre` (`tokio1-rustls-tls`)                                   | **Identity, RBAC, Outbox Token Envelope Crypto & SMTP**: Argon2id, HS256 JWT, SHA-256 one-time DB tokens, **AEAD `AES-256-GCM` keyring sealing/unsealing for sensitive outbox email payloads**, and **`EmailDeliveryProvider` (`lettre` SMTP with deterministic `Message-ID`)**.                                                                                                                   |
| **`pizzora-payments`** | `[lib]`                                                       | `pizzora-core`, `pizzora-config`, `pizzora-db`, `reqwest` (`rustls-tls`), `hmac`, `sha2`, `hex`, `subtle`, `serde_json`, `tracing`                                                                | **Payments, Receipts, Gateway & Webhooks**: Owns canonical **`format_razorpay_receipt`** (`sha2` + `hex`), strict `(order_id, idempotency_key)` receipt reuse vs `409 Conflict` detection, `RazorpayGateway`, constant-time HMAC verification, `PaymentService`, `WebhookService`, refunds & reconciliation.                                                                                       |
| **`pizzora-workers`**  | `[lib]` + `[[bin]] name = "pizzora-workers"`                  | `pizzora-core`, `pizzora-config`, `pizzora-db`, **`pizzora-auth`**, `pizzora-payments`, `axum`, `tokio`, `tokio-util`, `metrics`, `serde_json`, `tracing-subscriber`                              | **Standalone Worker Runtime (`:8081`)**: `EventSequencerWorker`, `OutboxDispatcherWorker` (payment/refund gateway calls, Redis Pub/Sub broadcast, inbox notifications, and **at-least-once `EMAIL_DELIVERY_REQUESTED` dispatch via `pizzora-auth`**), `ExpirySweepWorker` (including sensitive outbox payload scrubbing), `RazorpayReconciliationWorker`, `/health/worker`, `/metrics`.            |
| **`pizzora-api`**      | `[lib]` + `[[bin]] name = "pizzora-api"`                      | `pizzora-core`, `pizzora-config`, `pizzora-db`, `pizzora-auth`, `pizzora-payments`, `axum`, `tower`, `tower-http`, `reqwest`, `hmac`, `sha2`, `metrics-exporter-prometheus`, `tracing-subscriber` | **Standalone API Runtime (`:8080`)**: Axum HTTP/WS router, Tower security/body-limit/rate-limit middleware, `RealtimeHub`, **S3 SigV4 (`S3_ENABLED` optional)**, API services, `--migrate-only` & `--bootstrap-owner` CLI modes.                                                                                                                                                                   |

### 2.2 Complete Acyclic Dependency DAG

```
                 ┌───────────────────────────────────────────┐
                 │               pizzora-core                │
                 │         (Pure domain; zero crypto)        │
                 └──────┬─────────────────────────────┬──────┘
                        │                             │
                        ▼                             ▼
             ┌────────────────────┐        ┌────────────────────┐
             │   pizzora-uniffi   │        │   pizzora-config   │
             │  (cdylib + bin)    │        │                    │
             └────────────────────┘        └──────────┬─────────┘
                                                      │
                        ┌─────────────────────────────┘
                        ▼
             ┌──────────────────────────────────────────────────┐
             │                   pizzora-db                     │
             │ SQLx =0.8.6 + Locked Migrator + Redis + Outbox   │
             │ Shared Order/Inventory/Coupon/Notify/Audit       │
             └──────────┬───────────────────────────┬───────────┘
                        │                           │
                        ▼                           ▼
             ┌────────────────────┐      ┌──────────────────────┐
             │    pizzora-auth    │      │   pizzora-payments   │
             │ Auth/RBAC/AEAD     │      │ Receipt Generator &  │
             │ Envelope/SMTP      │      │ Idempotency/Gateway  │
             └──────┬─────────┬───┘      └──────┬──────────┬────┘
                    │         │                 │          │
                    │         └────────┬────────┼──────────┼───┐
                    ▼                  │        ▼          │   │
         ┌──────────────────────┐      │   ┌───────────────▼───▼───────┐
         │     pizzora-api      │◄─────┘   │      pizzora-workers      │
         │    ([[bin]] :8080)   │          │      ([[bin]] :8081)      │
         │ + S3 SigV4 & WS Hub  │          │ + At-Least-Once Email/    │
         └──────────────────────┘          │   Payment Outbox & Sweep  │
                                           └───────────────────────────┘
```

---

## 3. Gradle Wrapper & Pinned Build Toolchain (`Rust 1.88.0`)

- **Gradle Wrapper (`9.3.1`)**: Commit `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `distributionSha256Sum` + `validateDistributionUrl=true`; switch all CI & local commands to **`./gradlew`**.
- **JDKs & Android NDK**: Temurin JDK 17 (`:shared:contracts`, `:backend`), JDK 21 (AGP `9.1.1`, Robolectric SDK 36), `compileSdk 36`, `ndkVersion = "27.2.12479018"`.
- **Concrete Rust Toolchain (`rust/rust-toolchain.toml`)**: `channel = "1.88.0"`, `components = ["rustfmt", "clippy"]`, `targets = ["aarch64-linux-android", "x86_64-linux-android", "x86_64-unknown-linux-gnu"]`, `profile = "minimal"`, verified compatible with `uniffi = "=0.29.3"`, `sqlx = "=0.8.6"`, and `cargo-ndk = "4.1.2"`.
- **PostgreSQL Engine**: **PostgreSQL 18.6** across local Docker, CI, Railway staging, and production.

---

## 4. Email Delivery Semantics & Authentication Token Security

### 4.1 At-Least-Once Email Delivery & Crash-Window Tolerance

```
  1. Worker claims outbox_events (id = <event_uuid>, attempts = attempts + 1,
     dispatch_status = 'PROCESSING', lease = 30s) in TX1 -> COMMIT
        │
        ▼ (Outside DB transaction)
  2. Decrypt sensitive payload fields in memory -> build lettre Message with:
       - Header Message-ID: <outbox-{outbox_event_id}@{from_domain}>
       - Header X-Pizzora-Outbox-Id: {outbox_event_id}
     -> Transport::send() -> SMTP server returns 250 OK
        │
  ┌─────┴──────────────────────────────────────────────────────────────────┐
  │ FAILURE WINDOW: Worker crashes (SIGKILL/OOM) or DB connection drops    │
  │ AFTER SMTP 250 OK acceptance and BEFORE TX2 updates dispatch_status!   │
  │ -> Lease expires after 30s; another worker re-claims the row and may   │
  │    re-send the email -> RECIPIENT CAN RECEIVE DUPLICATE EMAIL.         │
  └─────┬──────────────────────────────────────────────────────────────────┘
        ▼ (Normal path)
  3. Zeroize plaintext secret buffer -> scrub sealed payload -> TX2 marks
     dispatch_status = 'DISPATCHED', published_at = NOW(), last_error = NULL
```

- **Documented Semantics**: Outbox email delivery is strictly **at-least-once** (NOT exactly-once), because SMTP has no distributed two-phase commit with PostgreSQL.
- **Deduplication Aids & Operator Visibility**: Every attempt reuses the exact same **stable `outbox_events.id`** and **deterministic RFC 5322 `Message-ID: <outbox-{outbox_event_id}@{from_domain}>`**, enabling downstream MTAs/inboxes to thread/deduplicate re-transmissions while auth endpoints enforce server-side single-use consumption (`used_at IS NULL` atomic `UPDATE ... RETURNING`).
- **Retry & Dead-Letter Behavior**: Transient errors (TCP timeout / SMTP 4xx) reschedule with exponential backoff `min(2^(attempts-1) * 2s, 300s)` up to `max_attempts = 5`; permanent SMTP 5xx errors or exhausted attempts transition the row to **`DEAD_LETTER`** (with **scrubbed payload** and `last_error` sanitized of any token material) and emit an `AUDIT_LOG` alert.
- **Fault-Injection Test**: `pizzora-workers` integration test injects a failure immediately after mock SMTP `250 OK` acceptance and before `DISPATCHED` DB commit, proving lease expiry triggers safe at-least-once re-delivery with identical `Message-ID` and `outbox_events.id`, and verifying token single-use remains intact.

### 4.2 Authentication Token Security in Outbox & Logs

1. **Hashed One-Time Tokens in Auth DB**: `email_verification_tokens` and `password_reset_tokens` continue storing **only cryptographic SHA-256 `token_hash`** (never plaintext tokens).
2. **AEAD Encryption of Outbox Email Secrets (`pizzora-auth`)**:
   - When `EMAIL_DELIVERY_REQUESTED` is enqueued inside the auth DB transaction, the raw token is **never** written in plaintext to `outbox_events.payload_json`.
   - Instead, `pizzora-auth` seals the sensitive token payload using **`AES-256-GCM`** with a random 96-bit nonce and **Associated Authenticated Data (AAD)** = `"{outbox_event_id}:{user_id}:{event_type}"`:
     ```json
     {
       "template": "PASSWORD_RESET",
       "user_id": "...",
       "token_record_id": "...",
       "token_expires_at": "...",
       "recipient_masked": "u***@example.com",
       "sealed_envelope": {
         "kid": "k2026_01",
         "alg": "AES-256-GCM",
         "nonce_b64": "...",
         "ciphertext_b64": "..."
       }
     }
     ```
     _(Where `ciphertext_b64` holds the encrypted JSON `{ "to_email", "full_name", "raw_token" }`, or encrypted token/address envelope, decrypted strictly inside worker RAM via `pizzora-workers -> pizzora-auth` and wrapped in `Zeroizing<String>` immediately dropped after SMTP send.)_
3. **Key Ownership, Rotation, Retention & Cleanup**:
   - **Ownership**: Keys are loaded by `pizzora-config::OutboxEncryptionConfig` solely from environment secrets (`OUTBOX_EMAIL_KEY_CURRENT_ID` + `OUTBOX_EMAIL_KEYS` base64-encoded 256-bit keys mapped by `kid`), injected into Railway/CI runtime secrets—never committed to Git or stored in PostgreSQL. `PIZZORA_ENV=production` fails fast if the current key is missing, < 32 bytes, or matches dev defaults.
   - **Rotation**: Multiple `kid` entries remain active in `OUTBOX_EMAIL_KEYS` during rotation: new outbox events encrypt under `OUTBOX_EMAIL_KEY_CURRENT_ID`, while workers decrypt any in-flight event by looking up `sealed_envelope.kid`. Once > 24 hours (maximum verification token lifetime) have elapsed and `SELECT COUNT(*) FROM outbox_events WHERE event_type='EMAIL_DELIVERY_REQUESTED' AND dispatch_status IN ('PENDING','PROCESSING')` reaches 0 for the old `kid`, the retired `kid` is removed cleanly.
   - **Retention & Cleanup**:
     - Upon transition to `DISPATCHED` or **`DEAD_LETTER`**, `OutboxDispatcherWorker` atomically replaces `sealed_envelope` with `null` (`"redacted": true`) in the same DB update!
     - `ExpirySweepWorker` periodically scrubs any leftover `sealed_envelope` whose `token_expires_at <= NOW()` (15 min for password reset, 24 h for verification) and purges historical `EMAIL_DELIVERY_REQUESTED` rows older than the configured retention window (e.g., 7 days).
4. **Zero Exposure in Dead-Letter & Logs**:
   - `outbox_events.last_error`, `audit_logs`, Prometheus metrics, and `tracing` structured logs **NEVER** log `raw_token`, SMTP MIME bodies, decoded envelopes, or full email addresses/secrets—recording only `outbox_event_id`, `template`, `token_record_id`, `kid`, and sanitized SMTP error codes.
   - **Security Tests**: Verify `outbox_events.payload_json` in Postgres never contains `rawVerifyToken` or `rawResetToken`; verify tampered AAD/ciphertext or unknown `kid` fails decryption without crashing unrelated lanes; verify rotation decrypts `k1` while encrypting `k2`; verify `DISPATCHED`, `DEAD_LETTER`, `last_error`, and captured `tracing` logs contain zero plaintext token leakage.

---

## 5. Razorpay Reproducibility (`checkout:1.6.41`) & Payment Receipt Idempotency

### 5.1 Gradle Dependency Locking & Verification

- Because `com.razorpay:checkout:1.6.41` dynamically resolves `com.razorpay:standard-core` (`1.6.x`) and `com.razorpay:core` (`1.0.x`), Phase 2 enables Gradle **`dependencyLocking`** on debug/release compile & runtime classpaths (`app/gradle.lockfile`) plus **`gradle/verification-metadata.xml`** (SHA-256 verification of resolved `checkout`, `standard-core`, and `core` artifacts—without `force(...)`-ing old transitive versions).
- Gate Phase 2 on recording the exact locked trio versions, `./gradlew :app:assembleDebug`, **`./gradlew :app:assembleRelease`** (R8/ProGuard & manifest merge), and `RazorpayCheckoutIntegrationTest` (launch, success, error, cancellation, config change).

### 5.2 Receipt Generator Ownership & Conflict-Safe Idempotency

- **Owner**: **`pizzora-payments`** (`receipts::format_razorpay_receipt`) — `pizzora-core` has zero crypto crates.
- **Canonical Formula (Kotlin Backend Phase 2 & Rust `pizzora-payments` Phase 3/6)**:
  `rcpt_` + lowercase hex `SHA256("pizzora:attempt:" + lowercase_uuid(order_id) + ":" + trimmed_key_or_attempt_id)[0..32]` (**37 ASCII chars <= 40**, **collision-resistant**, enforced structurally by `payment_attempts.idempotency_receipt UNIQUE` and tested via `tools/golden-vectors/razorpay_receipts.json`).
- **Exact Idempotency & Conflict Semantics (`CheckoutService` & `PaymentService.retryPayment`)**:
  1. Acquire PostgreSQL row locks in fixed deadlock-free order: `SELECT ... FROM orders WHERE id = ? FOR UPDATE`, then `SELECT ... FROM payments WHERE order_id = ? FOR UPDATE`.
  2. Derive canonical `receipt = format_razorpay_receipt(order_id, idempotency_key_or_attempt_id)` and compute a deterministic **`retry_fingerprint`** (e.g., canonical `order_id | payment_id | method | amount_paise | normalized_reason`).
  3. Lookup existing `payment_attempts` by `idempotency_receipt` (plus SQLSTATE `23505` safety net):
     - **If receipt already exists for the SAME `payment_id`/`order_id` AND matches compatible parameters (`method`, `amount_paise`, and request fingerprint)**: **idempotently reuse** the existing `payment_attempt`! Do **NOT** supersede existing attempts, do **NOT** insert a duplicate outbox row, and return the current `OrderPaymentStatusResponse`.
     - **If receipt already exists with conflicting parameters** (different `order_id`/`payment_id`, mismatched `amount_paise`/`method`, or conflicting retry parameters on a superseded/incompatible attempt): **reject safely with `409 Conflict` (`IDEMPOTENCY_KEY_CONFLICT`)** without mutating existing `payment_attempts`, outbox rows, or leaking raw SQL `23505` errors as HTTP `500`.
  4. **Concurrent Retry Tests**: Both `:backend:test` (Phase 2) and `cargo test -p pizzora-payments` (Phase 6) spawn parallel threads firing identical `(order_id, Idempotency-Key)` retries -> assert exact **single** `payment_attempt` and single `PAYMENT_GATEWAY_ORDER_REQUESTED` outbox event created; plus test conflicting retry parameters / cross-order key replay returning `409 Conflict`.

---

## 6. Flyway `11.3.4` to SQLx `=0.8.6` Handoff, Oracle Proof & Dedicated Advisory Lock

> [!CAUTION]
> **Zero Automatic Repair Rule**: `pizzora-db::migrator` NEVER auto-repairs `flyway_schema_history`, NEVER overwrites mismatched checksums, and NEVER drops dirty rows in an uncertain DB.

1. **Flyway `11.3.4` Authoritative Checksum Oracle (No Unverified Reader Assumptions)**:
   - Mirror `V1__core_schema.sql`, `V2__seed_rbac_and_settings.sql`, and `V3__hot_path_indexes.sql` into `rust/crates/pizzora-db/migrations/0001..0003_*.sql` (`.gitattributes` `*.sql text eol=lf` + CI byte-diff test).
   - **Do NOT assume how Java `BufferedReader.readLine()` treats BOMs or line endings.** Execute real **`org.flywaydb:flyway-core:11.3.4`** checksum resolution inside `:backend:test` as the authoritative oracle to generate/verify `tools/golden-vectors/flyway_11_3_4_checksums.json` across:
     - Actual `V1__core_schema.sql`, `V2__seed_rbac_and_settings.sql`, `V3__hot_path_indexes.sql`.
     - Synthetic regression fixtures with **LF (`\n`)**, **CRLF (`\r\n`)**, **UTF-8 multibyte text**, and **UTF-8 with leading BOM (`0xEF 0xBB 0xBF`)**.
   - Implement Rust `pizzora_db::migrator::flyway_crc32(&[u8]) -> i32` to reproduce the **exact checksums empirically returned by Flyway `11.3.4`** on every fixture, verified continuously by `cargo test -p pizzora-db`.
2. **Session-Level PostgreSQL Advisory Lock on a Single Dedicated Connection**:
   - Because `pg_advisory_lock(884210001)` is bound to a **single PostgreSQL backend session**, running it on a `PgPool` directly risks pool checkout hopping and lock leaks.
   - `pizzora-db::migrator::run_locked_handoff(pool: &PgPool)` **MUST**:
     1. Acquire one dedicated **`mut conn = pool.acquire().await?`** and wrap it in a session-lock guard.
     2. Execute `SELECT pg_advisory_lock(884210001)` on **`&mut *conn`**.
     3. Run **ALL** protected operations exclusively on that **exact same `&mut *conn`**:
        - Check `flyway_schema_history` presence, `success = TRUE`, exact `'1','2','3'` coverage, and `flyway_crc32` equality against canonical `V1`–`V3`.
        - Verify PostgreSQL catalog invariants (**46 tables**, critical `pg_constraint` unique/FK constraints, **13 `V3` indexes**, seed invariants).
        - Create `_sqlx_migrations`, verify/insert baseline rows `1..=3` (`execution_time = 0`, `ON CONFLICT (version) DO NOTHING`) inside a single transaction on **`&mut *conn`**.
        - Execute `sqlx::migrate!("./migrations").run(&mut *conn)` for `V4+` (or fresh `0001+`) using that exact locked session.
     4. Execute `SELECT pg_advisory_unlock(884210001)` on **`&mut *conn`** before releasing `conn` (and `conn.close()` / detach without returning to pool if unlock or mid-session connection state fails).
   - **Concurrent Migration-Runner Test**: Spawn two parallel migration runners against live **PostgreSQL 18.6** synchronized via a test hook while Runner A holds the advisory lock during baseline checks; prove Runner B **blocks cleanly** until Runner A finishes and unlocks, then observes `_sqlx_migrations` `1..=3` already adopted and succeeds with zero duplicate DDL and zero conflicts.

---

## 7. UniFFI Build Workflow & Room `v2 -> v3` FK-Safe Migration _(Preserved)_

- **UniFFI (`uniffi = "=0.29.3"` + `cargo-ndk 4.1.2` + `Rust 1.88.0`)**: Strict separation between host cdylib (`generateUniFfiKotlinBindings` + Robolectric `-Djava.library.path=...`) and Android NDK `.so` (`buildAndroidRustLibs` for `arm64-v8a` & `x86_64`), using JNA `5.15.0@aar` / `5.15.0`.
- **Session Isolation (`SessionTransitionCoordinator`)**: Single `TransitionMutex` + monotonic `sessionEpoch` across logout, refresh failure, and account switch; session-derived `user_id` only; `(user_id, event_id)` on `NotificationInboxEntity`; no `'__legacy__'` runtime usage.
- **Room `v2 -> v3` Under `PRAGMA foreign_keys = ON`**: Export `2.json` & `3.json`, remove `fallbackToDestructiveMigration`, enable `setForeignKeyConstraintsEnabled(true)` (`PRAGMA foreign_keys = ON`), drop/rebuild `cart_item_toppings` before `cart_items` inside `MIGRATION_2_3`, run `PRAGMA foreign_key_check`, and test cascade delete, orphan rejection (`SQLiteConstraintException`), and `cached_products`/`cached_menu_snapshot` survival.

---

## 8. Phased Execution Plan (Phases 0–9) & Railway Cutover

- **Pre-Phase 0 Gate**: Inspect Git state cleanly; stop if dirty; checkout & verify **`feat/pizzora-rust-migration`**.
- **Phase 0 / Phase 1 — Gradle Wrapper (`9.3.1`) + Cart/Inbox Isolation**: Commit `./gradlew` wrapper w/ SHA-256, update CI to `./gradlew`, ship `MIGRATION_2_3` (`PRAGMA foreign_keys = ON`), `SessionTransitionCoordinator`, and regression/migration tests.
- **Phase 2 — Razorpay SDK (`1.6.41`) + Locking + Idempotent Canonical Receipt**: Enable Gradle locking + SHA-256 verification metadata, wire `PaymentResultWithDataListener`, adopt canonical 37-char `rcpt_<32_hex>` with `FOR UPDATE` reuse vs `409 Conflict` rejection & concurrent retry tests; verify debug, release R8, and checkout runtime tests.
- **Phase 3 — Rust 8-Crate Workspace (`1.88.0`) & `pizzora-core` Parity**: Create `rust-toolchain.toml` (`1.88.0`) and all 8 crates; emit golden parity vectors.
- **Phase 4 — UniFFI Android Build (`cargo-ndk 4.1.2`, `uniffi =0.29.3`)**: Separated host bindgen + Android NDK tasks, JNA `5.15.0`, Robolectric bridge tests.
- **Phase 5 — `pizzora-db` (PG 18.6) & `pizzora-auth` (Encrypted Outbox Tokens)**: Implement Flyway `11.3.4` oracle-verified `flyway_crc32`, single-connection advisory-locked handoff w/ concurrent runner test, `AES-256-GCM` outbox keyring sealing/rotation, hashed auth DB tokens, and `lettre` SMTP (`Message-ID`).
- **Phase 6 — `pizzora-payments`**: Implement canonical receipts, idempotent/conflict-safe retries + concurrent retry tests, Razorpay gateway, constant-time HMAC verification, webhooks, refunds.
- **Phase 7 — `pizzora-api` (`[[bin]] :8080`)**: Full Axum HTTP/WS parity, S3 SigV4 (`S3_ENABLED`), `--migrate-only`, `--bootstrap-owner`.
- **Phase 8 — `pizzora-workers` (`[[bin]] :8081`) & Railway Staging (PG 18.6)**: At-least-once `EMAIL_DELIVERY_REQUESTED` dispatch (`-> pizzora-auth`) with post-SMTP crash test, payload scrubbing on `DISPATCHED`/`DEAD_LETTER`/TTL expiry, Docker `cargo-chef`, and staging Railway PG 18.6 + Redis validation.
- **Phase 9 — Railway Production Cutover & Expand-and-Contract Rollback**: Take `pg_dump`, stop Ktor worker, run `pizzora-api --migrate-only` (`_sqlx_migrations` `1..3`), launch `pizzabi` + `pizzabi-worker`, keeping `V1`–`V3` parity (or additive _expand_ `V4+`) until Ktor retirement.

---

## 9. Remaining Unresolved Risks

1. **At-Least-Once Duplicate Email Delivery During Worker Crashes**: While deterministic `Message-ID` headers (`<outbox-{id}@{domain}>`) enable relay/MUA correlation, an abrupt worker crash between SMTP `250 OK` and PostgreSQL `DISPATCHED` update can still deliver a duplicate email to users; downstream customer-facing templates and ops alerts must tolerate occasional duplicate delivery gracefully.
2. **Exact Maven Resolution Snapshot for `checkout:1.6.41`**: Exact patch versions and SHA-256 checksums for `standard-core` and `core` in `app/gradle.lockfile` and `gradle/verification-metadata.xml` require online resolution via `./gradlew :app:dependencies --write-locks` during Phase 2.
3. **Host OS Variance (`x86_64` Linux CI vs `aarch64` macOS)**: Both share `channel = "1.88.0"`, while Gradle UniFFI host tasks must map host OS/arch triples dynamically for bindgen and Robolectric `-Djava.library.path`.
4. **Keyring Provisioning Operational Discipline**: Production startup intentionally fails fast if `OUTBOX_EMAIL_KEY_CURRENT_ID` / `OUTBOX_EMAIL_KEYS` are absent in Railway secrets; ops runbooks must verify secret rotation (>24h grace window) before retiring prior `kid` entries.

---

## 10. Exact Acceptance Tests per Corrected Issue

| Issue                          | Exact Verification & Automated Acceptance Tests                                                                                                                                                                                                                                                                                                                                                      |
| :----------------------------- | :--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **1. At-Least-Once Email**     | `pizzora-workers` fault-injection test crashes/aborts DB update immediately after mock SMTP `250 OK` acceptance -> proves lease expiry recovers, re-delivers with identical `outbox_events.id` and `Message-ID: <outbox-{id}@{domain}>`, marks `DISPATCHED`, and proves verification/reset endpoints remain single-use.                                                                              |
| **2. Auth Token Security**     | Assert `email_verification_tokens`/`password_reset_tokens` contain only SHA-256 `token_hash`, `outbox_events.payload_json` contains zero substring of raw token, AAD tamper/wrong-`kid` fails cleanly, `kid` rotation decrypts `v1` while encrypting `v2`, `DISPATCHED`/`DEAD_LETTER`/TTL expiry scrubs `sealed_envelope`, and `last_error` + `tracing` logs contain zero token leakage.             |
| **3. Flyway `11.3.4` Oracle**  | `:backend:test` invokes real Flyway `11.3.4` checksum calculator across `V1`–`V3` + LF, CRLF, UTF-8 multibyte, and UTF-8 BOM fixtures into `tools/golden-vectors/flyway_11_3_4_checksums.json`; `cargo test -p pizzora-db` proves exact signed `i32` match across all fixtures + tampered `FLYWAY_CHECKSUM_MISMATCH` abort.                                                                          |
| **4. Dedicated Advisory Lock** | `pizzora-db` test acquires `pg_advisory_lock(884210001)` on a single `PgConnection`; spawns two parallel migration-runner tasks synchronized at a baseline barrier against live PG 18.6 -> proves Runner B blocks until Runner A completes and unlocks, executes zero duplicate DDL, and drops/closes poisoned sessions safely on failure.                                                           |
| **5. Receipt Idempotency**     | Both `:backend:test` and `cargo test -p pizzora-payments` test concurrent `retryPayment` with identical `(order_id, Idempotency-Key)` -> assert only 1 `payment_attempt` + 1 outbox row created; test conflicting parameters / cross-order key replay -> assert `409 Conflict` (`IDEMPOTENCY_KEY_CONFLICT`) with zero side effects and canonical 37-char `rcpt_<32_hex>` format.                     |
| **6. Razorpay, Room & Branch** | Before Phase 0 edits, `git status` & `git branch --show-current == feat/pizzora-rust-migration`; `./gradlew --version` passes; `app/gradle.lockfile` + `verification-metadata.xml` + `assembleDebug`/`assembleRelease` + Checkout runtime tests pass; `RoomMigrationTest` passes with `PRAGMA foreign_keys = ON`, `PRAGMA foreign_key_check == 0`, cascade + orphan rejection, and catalog survival. |

---

# Rev. 12 — Restored Implementation Detail & Operational Runbooks

> [!IMPORTANT]
> This section is additive to Rev. 11. It restores implementation-level material that existed in earlier plan revisions but was shortened or omitted. It does not replace the Rev. 11 architecture decisions or its six corrected safety requirements. Where older material conflicts with Rev. 11, the Rev. 11 decision is authoritative.

## 11. API Compatibility Matrix & Backend Parity Contract

The Rust API is a replacement implementation, not an API redesign. Preserve the existing Android-facing contract unless a deliberate contract change is separately approved and versioned.

### 11.1 Global HTTP and Serialization Requirements

- Preserve existing route paths, HTTP methods, request/response JSON field names, `snake_case` naming, nullable-versus-omitted behavior, timestamp representation, pagination/cursor semantics, and status-code behavior.
- Preserve the existing `ApiErrorEnvelope` shape and error-code strings. Do not expose SQLx errors, SQLSTATE values, stack traces, internal paths, or provider secrets to clients.
- Preserve authentication and authorization semantics, including role/permission checks and ownership checks. Every customer-owned resource must be scoped by the authenticated principal derived from the validated session/token, never by trusting a client-supplied `user_id`.
- Preserve rate-limit behavior and `Retry-After` headers where currently implemented. Preserve security headers, body-size limits, CORS policy, and request timeouts.
- Use explicit Rust DTOs and serde attributes for compatibility. Do not rely on incidental Rust field naming or default serialization behavior to reproduce Kotlin `kotlinx.serialization` output.
- Add response snapshot/golden tests for representative success, validation failure, unauthenticated, forbidden, not-found, conflict, rate-limited, and unavailable responses.

### 11.2 Route-Group Compatibility Matrix

The matrix below is the migration inventory from the earlier architecture audit. Before implementation, the agent must verify each route against the current repository's `Application.kt` and route files; if a route differs, record the observed route and update this matrix rather than silently omitting it.

| Route Group                             | Existing Route Inventory                                                                                                                                                   | Auth / Permission Contract                                                                                   | Rust Implementation Requirements                                                                                                                                                                                                           |
| :-------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :----------------------------------------------------------------------------------------------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Ops**                                 | `GET /api/v1/health/live`, `GET /api/v1/health/ready`, `GET /metrics`, `GET /api/v1/openapi.json`                                                                          | Public; health endpoints must not require a customer JWT                                                     | `/ready` checks PostgreSQL and relevant optional dependencies. If `S3_ENABLED=false`, MinIO/object storage is omitted from required checks. Preserve documented `HEALTHY`, `DEGRADED`, and `503` semantics.                                |
| **Auth**                                | `POST /api/v1/auth/register`, `verify-email`, `resend-verification`, `login`, `refresh`, `forgot-password`, `reset-password`, `logout`, `ws-ticket`                        | Public IP/group rate limits as applicable; logout and WS-ticket require authenticated session                | Verify existing Argon2id hashes; preserve refresh-token rotation and token-family reuse revocation; preserve single-use verification/reset tokens; use hashed token storage and encrypted outbox payload envelope as specified in Rev. 11. |
| **Users & Staff**                       | `GET/PATCH /api/v1/me`; CRUD `/api/v1/me/addresses`; `GET/POST/PATCH /api/v1/owner/staff`                                                                                  | Authenticated user; owner staff routes require `owner:staff_manage` or exact existing permission             | Address ownership is derived from session. Default-address change must be atomic. Preserve existing staff status and role constraints.                                                                                                     |
| **Catalog & Images**                    | `GET /api/v1/catalog/menu`, `GET /api/v1/catalog/categories`; owner CRUD for categories, products, variants, crusts, toppings; product image presign/confirm/delete routes | Public catalog rate limits; owner routes require `owner:catalog_manage`                                      | Preserve catalog cache invalidation and audit-log writes. With `S3_ENABLED=false`, image operations return the established unavailable error and do not fabricate public URLs.                                                             |
| **Cart, Coupons & Pricing**             | `GET/PUT /api/v1/cart`, `POST /api/v1/cart/validate`, `GET /api/v1/coupons/available`, owner coupon CRUD, `POST /api/v1/pricing/quote`                                     | Customer cart access is tied to authenticated user; owner coupon routes require `owner:coupons_manage`       | `pizzora-core` owns deterministic line math, pricing, coupon eligibility, tax split and cart validation. Server derives cart owner from auth context. Never accept a foreign `user_id` from payload.                                       |
| **Config, Zones & Slots**               | `GET /api/v1/config/public`, `GET /api/v1/delivery-zones/check`, `GET /api/v1/slots`; owner settings, delivery-zone and slot management routes                             | Public read routes; owner mutations require exact existing settings/zones permissions                        | Preserve `Asia/Kolkata` business-date and slot calculations, minimum-order rules, delivery-zone boundaries and slot capacity semantics.                                                                                                    |
| **Checkout, Orders & KDS**              | `POST /api/v1/checkout`, customer order list/detail/cancel routes; owner order routes; KDS ticket list and acknowledge/start-prep/ready/dispatch/complete/priority routes  | Preserve `checkout:create`, own-order read/cancel permissions, owner order permissions and KDS permissions   | Checkout transaction includes idempotency, inventory, coupon usage, slot capacity, order/payment rows and outbox events. Preserve lock ordering and `OrderStateMachine` behavior.                                                          |
| **Payments, Refunds & Webhooks**        | Customer order payment status/retry/fail; Razorpay verify; owner refund; Razorpay webhook                                                                                  | Own-order payment permissions; owner refund permission; webhook uses raw request body and provider signature | Use Rev. 11 canonical 37-character receipt and idempotency/conflict rules. Verify HMAC in constant time; webhook processing is idempotent by provider event ID; refund calls/reconciliation must not double-execute.                       |
| **Notifications, Realtime & Owner Ops** | Notification list/read/delete routes; event replay; `GET/WS /ws/v1/events`; owner dashboard/reports/audit logs/inventory/recipes                                           | Authenticated customer or owner permissions; WS ticket/Bearer behavior must match existing client            | Preserve user-scoped inbox, replay cursor semantics, WS ping/timeout/frame-size limits, Redis Pub/Sub with database replay fallback, and owner audit visibility.                                                                           |

### 11.3 API Contract Acceptance Gate

Before a route group is considered complete:

1. Produce a route inventory from the current Ktor source and compare it with the matrix.
2. For each route, record method, path, auth requirement, permission, request DTO, response DTO, status codes and known error codes.
3. Add a Ktor-vs-Axum contract test or golden fixture for every route family; include null/omitted fields, empty arrays, timestamps and decimal/money edge cases.
4. Verify the Android app can use the Rust API without a Kotlin DTO or endpoint change unless explicitly approved.
5. Mark routes as implemented only after integration tests run against PostgreSQL 18.6 and the relevant Redis/object-storage configuration.

---

## 12. Detailed Phase Execution Cards

> [!NOTE]
> The concise phase list in Section 8 remains the authoritative phase order. These cards restore the execution scope, files, dependencies, risks, tests, acceptance gates and rollback boundaries. Paths are expected locations; the implementation agent must inspect the repository and adapt to the actual package layout instead of creating duplicate or guessed files.

### 12.1 Phase 0 — Repository Baseline, Toolchain & Workspace Preparation

**Purpose:** Establish a reproducible baseline before changing application behavior.

**Preconditions:** User has approved implementation. Git branch gate in Section 0 has passed. The agent is on `feat/pizzora-rust-migration` and has confirmed a clean working tree.

**Scope:**

- Record current commit SHA, current branch, `git status`, project tree, available JDKs, Android SDK/NDK, Rust toolchain and Docker versions in a baseline report.
- Restore the complete Gradle Wrapper `9.3.1` (`gradlew`, `gradlew.bat`, wrapper JAR and distribution checksum). Verify wrapper files against the official Gradle distribution and use `./gradlew` for all Gradle tasks.
- Keep JDK 17 and JDK 21 roles explicit as specified in Section 3; do not silently switch the project's AGP/JDK combination.
- Confirm current Kotlin backend tests and Android test discovery baseline before modifications. Record failures that already existed.
- Create or update CI incrementally. Do not remove working Kotlin jobs while the Rust migration is in progress.
- Create the Rust workspace manifest and all eight crate manifests only when the plan's Phase 3 begins; Phase 0 must not accidentally become an unbounded rewrite.

**Files likely involved:** `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `.github/workflows/*`, `.gitignore`, `.gitattributes`, toolchain documentation and baseline test report.

**Tests / acceptance:**

- `./gradlew --version` reports Gradle 9.3.1.
- Existing baseline Kotlin backend tests pass or pre-existing failures are documented separately.
- `git status` shows only intended Phase 0 changes; no production configuration or database is changed.

**Rollback:** Revert only Phase 0 changes on the feature branch. Do not delete user files or reset the branch.

### 12.2 Phase 1 — Immediate Kotlin Cart & Notification Isolation

**Purpose:** Eliminate the P0 cross-account data leak before any Rust backend cutover.

**Files likely involved:**

- `app/build.gradle.kts` for Room schema export configuration.
- Room entities and DAOs for `CartItemEntity`, `NotificationInboxEntity`, and related child entities.
- `PizzoraDatabase.kt`, Room migration source and `app/schemas/` JSON exports.
- Auth/session repository, API refresh client, cart repository, notification repository, app dependency container and navigation/session observer.
- Tests for cart isolation, session transition and Room migration.

**Implementation requirements:**

1. Add non-null `user_id` ownership to cart and inbox rows and appropriate user-scoped indexes/uniqueness constraints.
2. Scope every DAO read, update, delete and aggregate query by the active user. Search the repository for all DAO call sites; no unscoped query may remain on a customer-owned table.
3. Centralize logout, refresh-token failure and account-switch cleanup in `SessionTransitionCoordinator` / `SessionScopeCleaner`.
4. Serialize transitions with one mutex and monotonic session epoch. Cancel or reject stale jobs whose captured epoch/user no longer matches the current session.
5. On login/account switch, fetch `GET /api/v1/cart` first and treat server state as authoritative. Never upload old local rows as part of session bootstrap.
6. Only an explicit action by the currently authenticated user may intentionally replace the server cart with an empty cart. Passive login, logout, refresh failure or local cleanup must never issue an empty-cart replacement request.
7. Existing v2 cart rows have no reliable owner. Quarantine or delete them during migration; never attribute them to the next logged-in user. Preserve unrelated cached catalog/menu data.
8. Apply SQLite foreign-key enforcement during the table rebuild. Rebuild child tables in safe order, validate with `PRAGMA foreign_key_check`, and test cascade and orphan behavior.
9. Remove destructive Room fallback migration. Export schema snapshots and make future migrations explicit.

**Required tests:** User A -> logout offline -> User B login; refresh 401; rapid account switching; in-flight User A write after User B session; User B server cart preserved; notification isolation; v2-to-v3 migration; legacy cart quarantine; child-row cascade; orphan rejection; cached catalog survival.

**Acceptance:** No request containing User A's cart can be sent while User B is active; no notification from A is visible to B; migration does not drop unrelated Room data; all Android unit/Robolectric tests pass.

**Rollback:** Do not roll back by restoring destructive migration behavior. If a shipped Room schema requires correction, create a forward migration. Keep the server-side ownership checks regardless of client rollback.

### 12.3 Phase 2 — Razorpay Checkout & Backend Receipt Stabilization

**Purpose:** Replace the manual payment-entry placeholder with the official Android Checkout SDK and fix receipt/idempotency behavior before Rust payment migration.

**Files likely involved:** `gradle/libs.versions.toml`, `app/build.gradle.kts`, `MainActivity.kt` or the current Activity result owner, payment sheet/launcher, order ViewModel/repository, Ktor `CheckoutService` and `PaymentService`, backend payment tests, Gradle lock and verification metadata.

**Implementation requirements:**

- Integrate `com.razorpay:checkout:1.6.41` through the official callback/listener flow. Do not treat manually entered payment ID/signature text as a production checkout implementation.
- Preserve a test seam so checkout success, error, cancellation and activity recreation can be tested without a live charge.
- Lock exact transitive versions and verify artifact checksums. Do not force an arbitrary transitive version to hide a build error.
- Implement the canonical receipt formula and exact idempotency/conflict behavior in Rev. 11 Section 5. This supersedes older `r_<32hex>` or `retry_<...>` examples in historical plan revisions.
- Verify the order/payment lock order, request fingerprint, unique constraint and side-effect-free `409 IDEMPOTENCY_KEY_CONFLICT` behavior.
- Keep secret key material on the backend; Android must not contain Razorpay secret credentials.

**Required tests:** SDK launch payload; success callback -> backend verify; error callback -> fail endpoint; cancellation; configuration change; debug and minified release build; receipt length <=40; deterministic golden vectors; duplicate retry reuse; conflicting and cross-order idempotency-key replay; concurrent retry creates one attempt and one outbox event; webhook HMAC and replay protection.

**Acceptance:** Real SDK launches and returns through the existing order flow; backend receipt is always within Razorpay's limit; duplicate retries cannot create duplicate attempts/outbox effects; debug and release builds pass.

**Rollback:** Keep the previous payment UI behind a controlled adapter only as a temporary rollback path. Do not roll back the canonical receipt database uniqueness or weaken server-side idempotency.

### 12.4 Phase 3 — Rust Workspace & Domain Golden Parity

**Purpose:** Establish the eight-crate workspace and move pure business rules into `pizzora-core` without introducing database or network I/O into the domain crate.

**Files likely involved:** `rust/Cargo.toml`, `rust/rust-toolchain.toml`, all eight crate manifests, `pizzora-core/src/*`, golden-vector fixtures and Rust CI job.

**Implementation requirements:**

- Pin Rust 1.88.0 and required targets/components. Keep dependency versions centralized in workspace dependencies.
- Create the full eight-crate DAG from Section 2. No crate may depend on `pizzora-api` or `pizzora-workers` in a way that creates cycles; API and worker runtimes remain independent.
- Keep `pizzora-core` pure: no Tokio, SQLx, Reqwest, filesystem, environment, network, crypto or UniFFI dependencies.
- Port exact domain behavior for money, pricing, coupon evaluation, order/payment state machines, causal tracking, cart validation/sync planning and storage-key validation.
- Use integer/fixed-point arithmetic and explicitly documented rounding rules; do not introduce floating-point drift.
- Generate golden vectors from the existing Kotlin shared contracts. Do not hand-author expected values based on the Rust implementation.

**Required tests:** Unit tests, property tests, edge/boundary tests and Kotlin-exported golden parity fixtures. `cargo tree -p pizzora-core` must show no I/O, runtime, crypto or FFI dependencies. `cargo check --workspace` must confirm the DAG compiles.

**Acceptance:** Rust outputs match the existing Kotlin domain contract for all golden vectors; `pizzora-core` remains pure; all eight crates compile in the pinned toolchain.

**Rollback:** Rust workspace is additive at this stage. Disable its CI job or revert the isolated workspace commit without changing production behavior.

### 12.5 Phase 4 — UniFFI, Android Native Build & Core Adoption

**Purpose:** Expose a narrow stable Rust domain API to Kotlin while maintaining separate host and Android binaries.

**Implementation requirements:**

- `pizzora-uniffi` is a thin adapter over `pizzora-core`; it must not become a second business-logic implementation.
- Build the host cdylib for binding generation and Robolectric separately from Android `.so` targets built with cargo-ndk.
- Generate Kotlin bindings from the pinned UniFFI version; use pinned JNA runtime/test dependencies.
- Package `arm64-v8a` and `x86_64` native libraries and verify both are present in the APK.
- Do not load Android ELF libraries in host JVM tests. Host tests must load the host-native library only.
- Introduce `RustDomainBridge` behind a Kotlin interface/adapter. Migrate one domain operation at a time, retaining a safe fallback to existing Kotlin contracts until parity tests pass.
- Route cart reconciliation through Rust `CartSyncPlanner` only after Phase 1's Kotlin ownership invariant is already enforced.

**Required tests:** Bindgen generation; host JVM native load; Robolectric Rust calls; Android ABI packaging inspection; Kotlin-vs-Rust parity; fallback behavior if native library initialization fails.

**Acceptance:** Both ABIs are packaged; host bindgen/Robolectric use host library; Kotlin can call Rust through generated bindings; no duplicate domain rules emerge.

**Rollback:** Switch the bridge adapter back to Kotlin shared contracts. Keep the Room/session isolation fixes.

### 12.6 Phase 5 — Configuration, Database, Migration Handoff & Authentication

**Purpose:** Implement persistence and identity parity without altering the existing production schema unexpectedly.

**Implementation requirements:**

- Implement `pizzora-config` validation and normalization, including `jdbc:postgresql://`, `postgres://` and `postgresql://` URL forms and explicit user/password overrides where the existing deployment uses separate variables.
- Implement SQLx pool sizing, connection lifecycle, tracing and health checks against PostgreSQL 18.6.
- Preserve Flyway V1-V3 as historical migrations and byte-match their SQL files. Use the real Flyway 11.3.4 checksum oracle and dedicated connection advisory lock exactly as Rev. 11 Section 6 specifies.
- Implement safe fresh-database bootstrap and existing-Flyway-database adoption. Unknown versions, failed migrations, missing history rows, checksum mismatch, catalog mismatch or unexpected constraints must abort before schema mutation.
- Never auto-repair or delete Flyway history. Never rerun V1-V3 DDL against an already migrated production database.
- Implement Argon2id PHC verification against real Ktor-generated fixtures; JWT, refresh rotation/family reuse, RBAC, WS tickets, verification/reset tokens and SMTP provider.
- Keep email secret envelope encryption in `pizzora-auth`; `pizzora-db` must not own encryption keys or decrypt token payloads.

**Required tests:** Fresh PostgreSQL 18.6 bootstrap; Flyway V1-V3 handoff; checksum mismatch abort; interrupted handoff; two concurrent migration runners; catalog invariants; Ktor Argon2id hash verification; login/refresh/reuse revocation; RBAC; token single-use; key rotation and AAD tamper; SMTP transient/permanent failures; no token leakage in logs/dead-letter rows.

**Acceptance:** Existing staging database can be adopted without rerunning V1-V3 DDL; fresh database can be initialized; auth compatibility tests pass; no production database is touched during development.

**Rollback:** Before any V4+ schema changes, remove only the staging SQLx metadata if the staging experiment is discarded. Never delete production migration metadata. After V4+, follow expand-and-contract rules and do not assume Ktor compatibility without explicit schema review.

### 12.7 Phase 6 — Payments, Razorpay Gateway, Webhooks & Refunds

**Purpose:** Port payment operations with provider-safe idempotency and exact compatibility.

**Implementation requirements:**

- Implement gateway HTTP client, sandbox/test double, canonical receipt formatter, payment service, webhook service, refund service and reconciliation support in `pizzora-payments`.
- Verify checkout signatures and webhook signatures over the exact raw bytes required by Razorpay; compare MACs in constant time.
- Persist provider event IDs and enforce idempotent webhook handling.
- Handle late/duplicate webhook events, expired orders, payment retry and refund state transitions consistently with the existing backend.
- Reuse the Rev. 11 idempotency contract; never translate a uniqueness conflict into a generic 500.
- Do not log API secrets, signatures, full payment payloads or sensitive customer information.

**Required tests:** Valid/invalid checkout HMAC; raw-body webhook HMAC; duplicate event; late webhook; amount/order mismatch; retry receipt reuse; concurrent retry; cross-order collision; refund idempotency; provider timeout and retry; reconciliation of uncertain gateway outcomes.

**Acceptance:** All current Ktor payment scenarios have Rust integration equivalents and pass against PostgreSQL 18.6 with provider calls mocked or sandboxed.

**Rollback:** Keep payment routing on Ktor until the API cutover. No live payment traffic is switched during crate-only development.

### 12.8 Phase 7 — Axum API & Full HTTP/WebSocket Parity

**Purpose:** Implement the API as a separate runtime while preserving all Android-facing behavior.

**Implementation requirements:**

- Build Axum router, shared application state, structured error mapping, middleware, rate limits, request body limits, auth extractors, DTOs and route modules.
- Port all route groups in Section 11; keep a route-by-route completion checklist.
- Preserve `:8080`, health endpoints, metrics and readiness behavior.
- Implement S3 SigV4 only when `S3_ENABLED=true`; do not make object storage mandatory when disabled.
- Implement WebSocket auth using the existing ticket/Bearer contract, replay, ping/pong, timeout and maximum frame size.
- Keep transactional business operations in shared services/repositories rather than embedding SQL in route handlers.
- Keep API and worker binaries independent. API must not spawn a second worker fleet implicitly.

**Required tests:** Route contract suite; permission/ownership tests; serialization golden tests; health/readiness under PostgreSQL/Redis/S3 availability combinations; WebSocket auth/ticket expiry/replay; request size and rate-limit tests; checkout concurrency; error-envelope parity.

**Acceptance:** All current client-used endpoints pass contract and integration tests; no Android changes are required to use staging Rust API except explicit, separately approved configuration changes.

**Rollback:** Rust API remains staging-only until Phase 9 approval. Ktor remains the production API.

### 12.9 Phase 8 — Workers, Containers, CI & Isolated Railway Staging

**Purpose:** Prove the complete Rust service topology away from production.

**Implementation requirements:**

- Build independent `pizzora-workers` runtime on `:8081` with health and metrics endpoints, graceful shutdown, bounded concurrency and worker leases.
- Implement event sequencing, outbox dispatcher, expiry sweep and Razorpay reconciliation. Use `FOR UPDATE SKIP LOCKED` and explicit leases.
- Email delivery follows at-least-once semantics and encrypted token envelope handling from Rev. 11. Do not claim exactly-once SMTP delivery.
- Build a multi-stage Rust image using cargo-chef (or equivalent pinned dependency caching) and run as a non-root user with only required runtime files.
- Keep existing Ktor build/publish/deploy pipeline available until Rust production cutover is approved and stable.
- Provision a distinct Railway staging API service, worker service, PostgreSQL database and Redis instance. Never point staging workers at production DB/Redis.
- Run a staging Android build configured for staging endpoints. Exercise customer, owner, KDS, checkout, payment webhook, notifications and WebSocket paths end-to-end.

**Required tests:** Worker lease recovery; duplicate/out-of-order event handling; SMTP crash window; retries/dead-letter; sensitive payload scrubbing; graceful SIGTERM; Redis restart; PostgreSQL reconnect; staging API/worker health; full customer-to-KDS workflow.

**Acceptance:** Staging runs independently and reliably with all integration/E2E gates green. Production Ktor services and production data remain unchanged.

**Rollback:** Stop staging Rust services or revert staging deployment. No production action is required.

### 12.10 Phase 9 — Production Cutover & Ktor Retirement

**Purpose:** Switch production only after explicit approval and completed staging sign-off.

**Pre-cutover gates:**

- Written user approval for the cutover window and exact Railway services to change.
- Verified recent PostgreSQL backup and tested restore procedure.
- Confirmed Rust API and worker image digests, environment-variable inventory, health checks, pool limits and rollback image digest.
- Confirmed schema compatibility and migration state. Initial cutover must preserve V1-V3 compatibility; any V4+ must be additive and reviewed.
- Confirmed no unresolved P0/P1 defects, no unexplained test failures, and no pending dead-letter backlog in staging.
- Confirmed Ktor worker shutdown procedure and how to prove it has no active worker DB sessions.

**Cutover sequence:**

1. Freeze schema changes and record current production deploy identifiers.
2. Take and verify a production `pg_dump`; store backup outside the app container and verify restoreability in a separate environment.
3. Confirm the production DB migration history and SQLx baseline preconditions read-only.
4. Stop Ktor workers and verify no duplicate worker process remains. Do not run Ktor and Rust workers concurrently against the same production queue.
5. Run the approved Rust migration/handoff command once under the dedicated advisory lock. Capture logs and verify no V1-V3 DDL was re-executed.
6. Deploy Rust API and Rust worker using the approved Railway configuration and pinned image digests.
7. Run smoke tests: readiness, catalog, login, refresh, cart, checkout in safe/test mode, webhook signature path, notifications, WebSocket, owner/KDS and worker heartbeat.
8. Monitor 5xx rate, latency, DB pool saturation, outbox backlog, dead-letter count, payment reconciliation and worker restarts.
9. Keep Ktor rollback image and configuration available until the agreed stability window is complete.

**Rollback decision triggers:** Sustained elevated 5xx, auth/login failures, cart ownership regression, checkout/payment inconsistency, duplicate refunds, worker backlog growth, schema incompatibility or inability to observe critical metrics.

**Rollback sequence:** Stop Rust worker first; stop or route away Rust API; restore the pinned Ktor API image/configuration with automatic Flyway startup disabled where appropriate; verify Ktor readiness and smoke tests. Do not restore the database unless data/schema analysis proves it is necessary. If V4+ schema changes have occurred, Ktor rollback is permitted only if every change remains backward-compatible under the expand-and-contract policy.

**Ktor retirement:** Retire Ktor code, images, workers and old deployment configuration only after the explicitly agreed production stability window, data/metrics review and user approval. Do not delete historical Flyway migrations or audit evidence.

---

## 13. Railway Environment, Secrets & Service Topology Runbook

### 13.1 Service Topology

- **Production:** Existing `pizzabi` API service and its production PostgreSQL/Redis dependencies remain unchanged until approved cutover. Rust API and Rust worker must be deployable as separate processes/services.
- **Staging:** Separate Rust API service and Rust worker service, separate PostgreSQL 18.6 database and separate Redis instance. Staging must not reuse production DB credentials, Redis URL, payment secrets or email credentials unless a specifically approved sandbox credential is used.
- **Worker separation:** API and worker use the same versioned Rust image where practical but different start commands. API listens on `:8080`; worker health server listens on `:8081`. Do not enable embedded workers and a separate worker service simultaneously.
- **Connection budgets:** Set explicit API and worker SQL pool limits based on Railway's actual database connection allowance and all other consumers. The previously proposed 15 API + 5 worker values are starting estimates only; verify the actual connection budget before deployment.

### 13.2 Environment Variable Inventory

The implementation agent must generate a complete variable inventory from current Ktor config and deployment files. At minimum classify:

| Category          | Variables / Configuration                                                       | Handling                                                                                                          |
| :---------------- | :------------------------------------------------------------------------------ | :---------------------------------------------------------------------------------------------------------------- |
| Runtime           | `PIZZORA_ENV`, `PORT`, log level, shutdown timeout                              | Non-secret unless deployment policy says otherwise.                                                               |
| PostgreSQL        | `DATABASE_URL` or host/port/database/user/password, pool size, connect timeout  | Secret credentials; normalize JDBC and Railway URL forms without logging them.                                    |
| Redis             | `REDIS_URL`, timeout, fallback policy                                           | Treat URL as secret if it contains credentials.                                                                   |
| Auth              | `JWT_SECRET`, issuer, access TTL, refresh TTL, WS ticket TTL                    | Secrets must be generated and stored in Railway secret variables. Never reuse development defaults in production. |
| Payments          | Razorpay key ID, key secret, webhook secret                                     | Secret values; separate sandbox and production credentials.                                                       |
| Email             | SMTP host, port, username, password, sender/from domain                         | Credentials are secrets; sender metadata may be non-secret.                                                       |
| Outbox encryption | `OUTBOX_EMAIL_KEY_CURRENT_ID`, `OUTBOX_EMAIL_KEYS`                              | High-sensitivity secrets; rotation and grace-window runbook required.                                             |
| Object storage    | `S3_ENABLED`, endpoint, bucket, region, access key, secret key, public base URL | Secret keys remain secrets; omit S3 readiness dependency when disabled.                                           |
| Worker            | poll intervals, batch size, lease duration, max attempts, concurrency           | Tune independently; do not allow API and worker to both run duplicate worker loops.                               |
| Limits            | max body sizes, rate-limit toggles/thresholds, timeouts                         | Validate at startup and expose safe effective values in diagnostics.                                              |

**Secret handling rules:** Never commit `.env` files or actual production values. Do not paste secrets into agent prompts, logs, issue descriptions or generated reports. Use placeholder names in documentation. Startup must fail fast for missing mandatory production secrets and invalid key lengths; error output must name the missing variable without printing its value.

### 13.3 Database URL Normalization

`pizzora-config` must parse the URL formats actually supplied by local Docker, current Ktor config and Railway. If the current deployment uses separate `DATABASE_USER`/`DATABASE_PASSWORD`, document precedence explicitly and test percent-encoded credentials, query parameters, TLS settings and IPv6 hosts. Do not print normalized URLs containing credentials.

### 13.4 Railway Operational Checks

- Confirm service start command, healthcheck path, healthcheck timeout, restart policy, region and deploy source.
- Confirm the Rust API binds to `0.0.0.0:$PORT` where Railway requires dynamic port binding; do not hard-code a private interface.
- Confirm worker health endpoint is reachable through the intended service healthcheck and does not expose internal diagnostics publicly.
- Confirm SIGTERM handling drains in-flight requests/jobs within the configured shutdown timeout.
- Confirm logs are structured, redact credentials/tokens, and include correlation IDs without exposing PII.
- Confirm backup/restore, migration and rollback procedures are documented in `infra/RUNBOOK.md` before production approval.

---

## 14. Risk Register & Mitigation Gates

| ID  | Risk                                                                        | Severity    | Detection / Mitigation                                                                     | Release Gate                                                      |
| :-- | :-------------------------------------------------------------------------- | :---------- | :----------------------------------------------------------------------------------------- | :---------------------------------------------------------------- |
| R1  | Cross-account cart or notification leakage                                  | Critical    | User-scoped DAO/server queries, serialized session transition, regression tests            | Must pass before any client release containing Phase 1            |
| R2  | Room migration destroys or corrupts local cached data                       | High        | Export schemas, explicit v2-v3 migration, FK checks, migration fixtures                    | Must pass before Phase 1 release                                  |
| R3  | Razorpay receipt exceeds 40 characters or retry duplicates payment attempts | Critical    | Canonical receipt, DB unique constraint, row locks, concurrent idempotency tests           | Must pass before payment SDK rollout                              |
| R4  | Android SDK/AGP/Gradle/JDK or native ABI incompatibility                    | High        | Pinned toolchain, wrapper checksum, dependency lock/verification, debug and release builds | Must pass before Phase 2/4 sign-off                               |
| R5  | UniFFI host/Android binary confusion                                        | High        | Separate host and NDK builds, host Robolectric tests, APK ABI inspection                   | Must pass before Phase 4 sign-off                                 |
| R6  | Flyway-to-SQLx checksum or baseline mismatch                                | Critical    | Real Flyway 11.3.4 oracle, exact SQL parity, read-only verification, no auto-repair        | Must pass before staging DB handoff                               |
| R7  | Migration lock acquired on one connection but work runs on another          | Critical    | Dedicated `PgConnection`, same-session assertions and two-runner concurrency test          | Must pass before any existing DB adoption                         |
| R8  | Auth password hashes fail after migration                                   | High        | Real Ktor PHC fixtures and cross-language verification                                     | Must pass before auth cutover                                     |
| R9  | Reset/verification token exposed in outbox, logs or dead-letter queue       | Critical    | AES-256-GCM envelope, key rotation, scrubbing, redaction and leakage tests                 | Must pass before email outbox is enabled                          |
| R10 | Duplicate SMTP email after worker crash                                     | Medium      | Explicit at-least-once semantics, stable Message-ID, single-use token consumption          | Must be accepted and documented; exactly-once must not be claimed |
| R11 | Duplicate payment/refund from dual workers                                  | Critical    | Separate worker topology, stop Ktor worker, verify no active worker DB sessions            | Must pass before production worker start                          |
| R12 | Ktor rollback blocked by destructive SQLx migration                         | Critical    | Initial V1-V3 parity, expand-and-contract, compatibility review                            | Must pass before each V4+ production migration                    |
| R13 | Railway staging accidentally connects to production data                    | Critical    | Separate DB/Redis/credentials, startup environment checks                                  | Must pass before staging E2E                                      |
| R14 | Secrets leak through config errors, logs or agent output                    | Critical    | Redaction tests, no secret values in docs, secret manager use                              | Must pass before any deployment                                   |
| R15 | API wire-format drift breaks Android clients                                | High        | Route inventory, DTO snapshots, Ktor-vs-Axum contract suite                                | Must pass before Rust API staging sign-off                        |
| R16 | Redis/S3 outage changes behavior unexpectedly                               | Medium/High | Explicit degraded readiness and fallback tests for each optional dependency                | Must pass before staging sign-off                                 |
| R17 | Worker shutdown/restart loses leased jobs                                   | High        | Lease expiry, idempotent handlers, SIGTERM tests and crash injection                       | Must pass before worker production use                            |
| R18 | Scope creep or agent rewrites unrelated app modules                         | High        | Phase-scoped file allowlist, small commits, review diffs, stop on unexpected changes       | Every implementation task                                         |

---

## 15. Cross-Stack Verification Matrix & Release Evidence

Every phase must produce a concise evidence report containing exact commands, exit codes, test counts, skipped tests and known failures. “Tests pass” without commands and output summary is not sufficient.

| Layer                | Required Verification                                                                                      | Evidence to Retain                                                          |
| :------------------- | :--------------------------------------------------------------------------------------------------------- | :-------------------------------------------------------------------------- |
| Git safety           | `git status --short`, `git branch --show-current`, diff review                                             | Branch name, base commit, clean/dirty state and changed-file list           |
| Gradle               | `./gradlew --version`; relevant module tests; debug and release builds when required                       | Gradle/JDK versions, test counts, build outputs and dependency lock changes |
| Android Room         | Room migration tests from exported v2 schema; FK and cascade/orphan checks                                 | Schema JSONs, migration test report, `PRAGMA foreign_key_check` result      |
| Android session/cart | logout offline, refresh failure, account switch, stale coroutine, empty-cart semantics                     | Regression test report and network request assertions                       |
| Razorpay Android     | SDK launch, success/error/cancel, lifecycle/configuration change                                           | Integration test output and release/R8 build result                         |
| Kotlin backend       | Existing contract/backend tests and security script                                                        | Baseline vs post-change test results; no silent removal of old tests        |
| Rust domain          | `cargo fmt --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test -p pizzora-core` | Toolchain version, golden parity count, property-test cases                 |
| Rust workspace       | `cargo check --workspace`, `cargo test --workspace`                                                        | Crate graph, test counts and dependency audit                               |
| UniFFI               | Host bindgen, Robolectric host native loading, Android ABI packaging                                       | Generated binding diff and ABI listing                                      |
| Database             | PostgreSQL 18.6 migration tests, Flyway oracle, SQLx handoff, advisory-lock concurrency                    | Migration history before/after, checksums, catalog invariant report         |
| Auth/security        | Argon2id fixture, JWT/refresh reuse, RBAC, token single-use, AEAD/key rotation                             | Sanitized security test report; never include token/key values              |
| Payments             | Receipt golden vectors, retry concurrency, raw HMAC, webhook replay, refund idempotency                    | Provider-mocked integration test report                                     |
| API                  | Route inventory and Ktor/Axum contract suite                                                               | Per-route coverage checklist and DTO/status snapshots                       |
| Workers              | Lease recovery, retries, dead-letter, duplicate SMTP window, graceful shutdown                             | Fault-injection output and queue-state assertions                           |
| Containers           | Multi-stage image build, non-root runtime, health checks, SIGTERM                                          | Image digest, vulnerability scan and runtime smoke output                   |
| Railway staging      | Isolated DB/Redis, API/worker health, full E2E, restart recovery                                           | Staging deploy IDs, environment inventory (names only), smoke/E2E report    |
| Production           | Backup/restore proof, approved change window, cutover and rollback rehearsal                               | User approval, backup verification, image digests, monitoring snapshots     |

**CI policy:** Keep existing Kotlin jobs active throughout the migration. Add Rust jobs incrementally. Do not mark a job optional or remove a failing test simply to make the pipeline green. Any quarantined/flaky test needs an owner, reason, issue reference and expiry/review date.

---

## 16. Exact First Implementation Task & Agent Operating Contract

### 16.1 First Task Scope

The first implementation task after plan approval is **Phase 0 baseline/tooling and Phase 1 cart/inbox isolation**, not a full Rust backend rewrite. The exact file paths must be verified from the repository before editing. Likely areas include:

- Gradle wrapper files and wrapper properties/checksum.
- Room entities, DAO queries, database version/migrations and exported schemas.
- Session/auth transition and cleanup coordinator.
- Cart and notification repositories and their dependency wiring.
- Cart isolation, account-switch and Room migration tests.
- CI invocation changes limited to using `./gradlew`.

Do not create the Rust workspace as part of the first task unless the user separately approves expanding the scope to Phase 3.

### 16.2 Mandatory Agent Workflow for Every Phase

1. Read the current plan and inspect the repository before proposing edits.
2. Verify branch and clean working tree. If branch does not exist, create it only after confirming clean state and approved base. If it already exists, inspect it and confirm it is the intended migration branch; do not overwrite or reset it. If working tree is dirty, stop and report.
3. Produce a phase-specific implementation checklist and exact file allowlist before modifying files.
4. Identify current behavior and tests first. Do not infer package paths, schemas, endpoint names or configuration from this plan alone when the repository can verify them.
5. Implement only the approved phase. No opportunistic refactors, unrelated formatting sweeps, dependency upgrades or architecture changes.
6. Add/update tests with each behavior change. Preserve all existing tests unless removal is explicitly justified and approved.
7. Run the required tests and report exact commands, exit codes, counts, failures and skipped tests.
8. Review `git diff --check`, `git diff --stat`, full diff and `git status`. Confirm no secrets, generated binaries, production data or unrelated files were added.
9. Stop at the phase acceptance gate and provide a summary: files changed, behavior changed, tests, known limitations, rollback, and next proposed phase.
10. Do not automatically proceed to the next phase without user approval.

### 16.3 Git, Commit & Deployment Boundaries

- All implementation work remains on `feat/pizzora-rust-migration`.
- Do not `git reset --hard`, `git clean`, force checkout, overwrite user changes, auto-stash, or amend unrelated commits.
- Do not commit unless the user has approved the commit for the current task. If commit approval is granted, create small phase-scoped commits with descriptive messages.
- Never push, open a PR, merge, tag a release, change Railway production services, run production migrations, or deploy to production without explicit approval for that exact action.
- Staging deployment also requires explicit approval unless the user has already authorized the specific staging action.
- Do not run `--bootstrap-owner` against production or rerun owner bootstrap as a routine deployment step. Treat it as a sensitive one-time operational command requiring explicit approval.

### 16.4 Phase Completion Report Template

For each phase, report:

- Phase and objective.
- Starting commit and branch.
- Files created/modified/deleted.
- Important implementation decisions and deviations from the plan.
- Tests run, exact commands, pass/fail/skip counts.
- Security and data-safety checks.
- Remaining risks and known limitations.
- Rollback procedure.
- Whether acceptance criteria are met.
- Next proposed phase (do not start it automatically).

---

## 17. Revision Integrity & Change-Control Policy

This plan is cumulative. Future revisions must not become shorter by silently deleting implementation detail.

1. Start each revision from the immediately preceding complete plan, not from a summary or a newly generated outline.
2. Preserve every section, table, phase card, acceptance criterion, test requirement, runbook, risk and unresolved question unless it is explicitly superseded.
3. When a requirement changes, retain the old decision in the revision changelog as “superseded,” explain why, and identify the new authoritative section.
4. Do not silently condense detailed phase cards into one-line phase summaries. The concise roadmap may coexist with the detailed execution cards.
5. Before delivering a revision, compare it with the prior revision and report: sections added, changed, removed, moved, and any line/word-count changes. Every removed section requires an explicit reason.
6. Keep unresolved risks visible until they are actually resolved and supported by evidence. Do not relabel a risk as resolved merely because a mitigation was written into the plan.
7. Keep user-approved architecture decisions separate from agent recommendations and unverified assumptions.
8. Never claim repository inspection, tests, build success, Railway state or branch creation unless those actions were actually performed and evidence is available.
9. The plan is a blueprint, not permission to execute every phase. Each phase and each production-impacting operation still requires the applicable user approval.

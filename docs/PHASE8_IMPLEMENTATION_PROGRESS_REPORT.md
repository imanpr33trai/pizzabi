# PIZZORA Phase 8 — Implementation Progress Report

**Phase:** 8 — Extended Pizza Modifiers & Reorder\
**Branch:** `ui-ux-changes`\
**Baseline commit:** `db41169bf6d0852feae3dbc4ada93e0b50f85c53`\
**Report updated:** 2026-11-27\
**Current verdict:** **PHASE 8 COMPLETE**

---

## 1. Authoritative inputs

| Document | Status |
|----------|--------|
| `public/implementation_plan.md` (Phase 8, L903–L922) | Canonical scope, unchanged |
| `docs/PHASE8_BACKEND_CONTRACT.md` | Pre-review draft (historical) |
| `docs/PHASE8_REVIEWED_CONTRACT_RESOLUTIONS.md` | **Binding addendum** for implementation |
| Review session `ses_deea9b4e0ffe1h1FWMfAle9H5y` | Recovered; 3 reviewer findings resolved in addendum |
| Phase 6 / Phase 7 | CLOSED; must not regress |

### 1.1 Baseline verification

| Check | Result |
|-------|--------|
| Branch | `ui-ux-changes` |
| HEAD | `db41169bf6d0852feae3dbc4ada93e0b50f85c53` (matches expected Phase 7 commit) |
| `origin/ui-ux-changes` | `0/0` ahead/behind at start |
| Worktrees | 3 external worktrees inspected, untouched |
| Existing user changes | `.opencode/opencode.jsonc` modified (preserved); Phase 8 docs untracked (preserved) |
| PostgreSQL | `postgres:18-alpine` container `pizzora-pg18`, PostgreSQL 18.7, accepting connections |
| Rust | `cargo 1.88.0`, `rustc 1.88.0` (matches `rust-toolchain.toml`) |
| Java | OpenJDK 21.0.13 |
| Android SDK | Present at `~/Android/Sdk`; `/dev/kvm` present |

---

## 2. Contract reconciliation note

The on-disk contract and readiness assessment are the **pre-review** versions. The independent
review rejected the draft (3 reviewers: backend, test-engineer, reviewer) over six open U8
decisions, a missing `product_toppings` table, a 50-vs-51 table mismatch, missing reorder/UniFFI
surface, and incomplete acceptance criteria. The user authorization confirms the reviewed contract
resolved all six U8 decisions and all seven backend blockers, with a 51-table inventory, 33 golden
vectors, 11 acceptance criteria, and a five-service Kotlin audit.

`docs/PHASE8_REVIEWED_CONTRACT_RESOLUTIONS.md` records the binding resolutions. No implementation
work was started against the stale draft.

Wire-case decision: §6.3's reorder JSON example is treated as authoritative for the new reorder
response only, so `ReorderResponseDto`, `ReorderItemDto`, and `ReorderUnavailableDto` serialize in
camelCase. Existing Option A endpoints retain their established snake_case wire names.

---

## 3. HerdR workstream layout

HerdR is running (`HERDR_ENV=1`, workspace `w1`). Panes are workstream terminals; OpenCode
subagents perform the specialized implementation/review work. The coordinator retains Git and
integration ownership.

| Pane | Tab | Responsibility | Status |
|------|-----|----------------|--------|
| `w1:p1` | Coordinator | Orchestration, Git, integration, final acceptance | ACTIVE |
| `w1:p6` | P8 Backend | SQLx migrations, Kotlin compatibility | READY |
| `w1:p8` | P8 QA | Regression, migration and parity evidence | READY |
| `w1:p9` | P8 Rust Core | Pricing engine, validation, golden vectors | READY |
| `w1:pA` | P8 API / UniFFI | DTOs, endpoints, FFI exports | READY |
| `w1:pB` | P8 Android Builder | Cheese/sauce/standard/half-and-half UI | READY |
| `w1:pC` | P8 Android Orders | Previews, reorder, reconciliation | READY |
| `w1:pD` | P8 Reviewer | Independent read-only review | READY |

Limitation recorded: HerdR 0.9.5 creates tabs/panes and can start interactive agents, but the
coordinator uses OpenCode's native subagent sessions for isolated implementation context. Pane
commands are used for build/test execution and evidence capture.

Subagent model policy (user instruction, 2026-11-27): all **new** subagents and all **failed-agent
retries** use `opencode/big-pickle`. Already-running healthy agents are left undisturbed.

Formatter policy (user instruction, 2026-11-27): run `cargo fmt --all` (and `cargo fmt --all -- --check`
before staging) before every further commit/push. Kotlin has no configured formatter plugin in this
repository; Kotlin changes must follow existing style and be reviewed in the staged diff.

---

## 4. Workstream status

| Workstream | State | Owner | Notes |
|------------|-------|-------|-------|
| W1 SQLx V4+ schema + invariants | **IMPLEMENTED / VERIFIED** | Coordinator / `p8-backend` | 8 migrations (0004–0011), 51 tables, two profiles, rollback/reapply — Checkpoint A PASS |
| W2 Rust pricing + 33 vectors | **IMPLEMENTED / VERIFIED / REVIEW PASS** | `general` subagent (`ses_de343b2a7ffez42D4TrxHUvV6d`) | Initial audit REJECTED (3 MAJOR); all fixed; independent big-pickle re-audit PASS. 21 core tests PASS; 33/33 vectors match contract |
| W3 DTO/API + reorder endpoint | **IMPLEMENTED / VERIFIED** | `general` subagent (`ses_de3265e86ffe22X5qe8D2MaULu`, `opencode/big-pickle`) | Committed at `a332540`; API build + 7 tests PASS, shared contracts 40 tests PASS; coordinator follow-ups fixed UUID binding and checkout modifier persistence |
| W4 UniFFI | **IMPLEMENTED / VERIFIED / REVIEW PASS** | `general` subagent (`ses_de31be575ffeJFJhKdoHPdiJ1I`, `opencode/big-pickle`) | Initial audit REJECTED; all findings repaired; independent re-audit PASS. 11 tests, all-features build, rustfmt, direct Kotlin bindgen evidence PASS |
| W5 Kotlin backend five-service audit | **IMPLEMENTED / VERIFIED** | `general` subagent (`ses_de321c174ffemMqJrNxjY5Tmsg`, `opencode/big-pickle`) | Committed at `bb63746`; five services + reorder route + global menu arrays + explicit modifier breakdown; 84 backend tests + 40 shared-contract tests PASS |
| W6 Android builder | **IMPLEMENTED / VERIFIED** | `general` subagent (`ses_de31f0427ffeWCvzsyjxOW0YjM`, `opencode/big-pickle`) | Modifier UI, server quote, zero-credit defaults, edit hydration, and cart/nav/API wiring complete; full app suite 230/0/0 PASS |
| W7 Android orders/reorder | **IMPLEMENTED / VERIFIED** | `general` subagent (`ses_de31edc10ffea4fG4y0LE3xTLU`, `opencode/big-pickle`) | Committed at `946d900`; preview, extended modifier rendering, reconciliation dialog, confirmed cart population; 7 new + 20 order tests PASS |
| W8 Regression/QA | **IMPLEMENTED / VERIFIED** | `test-engineer` (`ses_de30bc263ffei7DIuSl5ErNyZY`, `opencode/big-pickle`) + `android-qa` | DB evidence + emulator readiness captured; live reorder API suite 5/5 PASS; full Rust workspace + fresh-DB live suite PASS after fixes; local API smoke PASS; full Android unit suite 230/0/0 PASS; Kotlin 33-vector parity PASS; final server-backed runtime/accessibility report and corrected duplicate-merge probe complete |
| W9 Independent review | **PASS — unconditional (gate closed)** | `reviewer` (`ses_de29e025bffeF5ui3d1rI8VHMo`, `opencode/big-pickle`) | Initial committed-range review FAIL; all BLOCKERs/MAJORs remediated. Final re-review PASS with no BLOCKER/MAJOR; MINOR idempotency-hash finding closed and re-verified; §10 addendum closes the runtime gate with all 11 criteria satisfied |

---

## 5. Completed work

| Date | Item | Evidence |
|------|------|----------|
| 2026-11-27 | Baseline, contract review recovery, HerdR panes, Rust/Postgres toolchain | `git status`, `herdr pane list`, `cargo --version`, `pg_isready` |
| 2026-11-27 | Binding reviewed-contract addendum created | `docs/PHASE8_REVIEWED_CONTRACT_RESOLUTIONS.md` |
| 2026-11-27 | Four read-only codebase mapping subagents completed (backend, contracts/Kotlin, builder, orders) | Subagent reports in session |
| 2026-11-27 | SQLx V4–V11 migrations created (5 tables, modifier columns, 12 indexes) | `rust/crates/pizzora-db/migrations/0004_*.sql`–`0011_*.sql` |
| 2026-11-27 | `migrator.rs` updated: 51 tables, baseline/full invariant profiles, Phase 8 indexes/constraints, preflight profile detection | `rust/crates/pizzora-db/src/migrator.rs` |
| 2026-11-27 | Checkpoint A tests: 7 unit + 3 live PostgreSQL 18 integration tests, `cargo build --workspace`, fresh schema = 51 tables | `qa-artifacts/phase8/migrations/` |
| 2026-11-27 | Checkpoint B Rust pricing + 33 vectors committed and pushed | `2d4a682`, `qa-artifacts/phase8/parity/` |
| 2026-11-27 | Android API 36 emulator booted for deferred runtime QA | `qa-artifacts/phase8/visual/ANDROID_QA_READINESS.md` |
| 2026-11-27 | Coordinator fix: API `can_reorder` now requires at least one available item | `rust/crates/pizzora-db/src/orders.rs`; API 7 tests + rustfmt PASS |
| 2026-11-27 | Coordinator fix: reorder binds stored IDs as UUIDs; checkout persists all Phase 8 modifier IDs and snapshots | `rust/crates/pizzora-db/src/orders.rs`, `rust/crates/pizzora-api/src/services/checkout.rs`; rustfmt + API build + 7 tests PASS |
| 2026-11-27 | Coordinator fix: live reorder 200/403/404/unavailable/historical suite 4/4 PASS; reorder wire shape aligned to §6.3 camelCase | `rust/crates/pizzora-api/tests/pg18_phase8_reorder.rs`, `qa-artifacts/phase8/api-contracts/w3_reorder_integration.log`, `w3_contract_casing.log` |
| 2026-11-27 | Coordinator closed §6.1/§6.2 gaps: global modifier arrays on `PublicMenuResponse` and explicit modifier breakdown on `PriceBreakdownDto`; Rust API side verified | shared contract DTOs, `rust/crates/pizzora-api/src/services/{catalog,pricing}.rs`; API 7 tests + rustfmt PASS |
| 2026-11-27 | Full Rust workspace regression PASS after adding the six Phase 8 optional fields to two legacy HTTP/WS test fixtures | `qa-artifacts/phase8/regression/w8_rust_workspace_tests.log` — `cargo fmt --all -- --check` + `cargo test --workspace` exit 0 |
| 2026-11-27 | Full ignored live PostgreSQL 18 suite PASS with single-threaded execution | `qa-artifacts/phase8/regression/w8_live_postgres_tests.log` — `cargo test --workspace -- --ignored --nocapture --test-threads=1` exit 0 |
| 2026-11-27 | DEFECT FIX: checkout now loads the extended modifier catalog and enforces feature flags + half-and-half config matching; new live regression proves modifier IDs and pricing persist | `rust/crates/pizzora-api/src/services/checkout.rs`, `rust/crates/pizzora-api/tests/pg18_phase8_reorder.rs`; `qa-artifacts/phase8/regression/checkout_modifier_regression.log` — 5/5 live tests PASS |
| 2026-11-27 | Local non-production API smoke PASS: quote → cart resolution → checkout → reorder with full modifiers and camelCase wire shape | `qa-artifacts/phase8/regression/local_api_smoke.py`, `local_api_smoke.log` |
| 2026-11-27 | Post-fix full Rust workspace + fresh-database live PostgreSQL regressions PASS | `qa-artifacts/phase8/regression/w8_rust_workspace_tests_post_fix.log`, `w8_live_postgres_tests_post_fix.log` |
| 2026-11-27 | W9 advisory closed: half-and-half display breakdown and cart view now use checked arithmetic (no saturation/fallback-to-`i64::MAX`) | `rust/crates/pizzora-core/src/{pricing.rs,cart_validation.rs}`; `qa-artifacts/phase8/regression/checked_breakdown_arithmetic.log` — rustfmt + all `pizzora-core` tests PASS |
| 2026-11-27 | Real migration CLI evidence for fresh + Flyway V1–V3 profiles: all four `--preflight-only` / `--migrate-only` invocations exit 0 | `qa-artifacts/phase8/migrations/PHASE8_MIGRATION_CLI_EVIDENCE.md` |
| 2026-11-27 | Final post-advisory Rust workspace + fresh-database live PostgreSQL regressions PASS; final local API smoke PASS on rebuilt binary | `qa-artifacts/phase8/regression/w8_rust_workspace_tests_final.log`, `w8_live_postgres_tests_final.log`, `local_api_smoke_final.log` |
| 2026-11-27 | Final Kotlin backend + shared-contract suite re-run PASS after all Checkpoint H changes | `qa-artifacts/phase8/backend/w8_final_kotlin_backend.log` — 86 backend + 40 shared-contract tests, 0 failures/errors |
| 2026-11-27 | Final unit-test XML evidence copied for app/backend/shared-contract suites | `qa-artifacts/phase8/unit-test-xml/README.md` — 230 + 86 + 40 tests, 0 failures/errors/skipped |
| 2026-11-27 | W9 MINOR closed: checkout idempotency hash now includes all Phase 8 modifier IDs; same key with different modifiers returns `IDEMPOTENCY_CONFLICT` | `rust/crates/pizzora-api/src/services/checkout.rs`, `rust/crates/pizzora-api/tests/pg18_phase8_reorder.rs`; `qa-artifacts/phase8/regression/idempotency_hash_unit.log`, `checkout_modifier_regression_final.log` — API 7/7, live 5/5 PASS |
| 2026-11-27 | Post-MINOR Rust workspace and fresh-database live PostgreSQL regressions PASS | `qa-artifacts/phase8/regression/w8_rust_workspace_tests_checkpoint_h.log`, `w8_live_postgres_tests_checkpoint_h.log` — fmt + workspace exit 0; all ignored live tests PASS |
| 2026-11-27 | Final server-backed Android runtime QA complete on `emulator-5554`: builder, cart modifiers, reorder reconciliation, TalkBack/font-scale/large-display and quantity/undo/coupon/clear flows | `qa-artifacts/phase8/visual/runtime/ANDROID_RUNTIME_QA.md` with screenshots, UIAutomator XML, command log, logcat, and restoration evidence |
| 2026-11-27 | Runtime FAIL triage closed: duplicate lines were different modifier configs; corrected same-config probe merges to one qty-25 line; 126px is exactly 48dp at 420dpi; IME dock hide is specified behavior; focus restoration is unit-tested | `qa-artifacts/phase8/visual/runtime/129_merge_same_modifier_config_request.json`, `129_merge_same_modifier_config.json`, `ANDROID_RUNTIME_QA.md` §7 |
| 2026-11-27 | Local QA credentials and captured tokens redacted before publication | `qa-artifacts/phase8/visual/seed_local_qa_catalog.sh`, `local_qa_seed.log`, `runtime/ANDROID_RUNTIME_QA.md`, `runtime/command_log.txt`, `runtime/{20,86,119}_*login.json` |

---

## 6. Tests executed

| Suite | Command | Result |
|-------|---------|--------|
| Baseline inspection | `git status --short`, `git rev-parse HEAD` | PASS |
| Toolchain | `cargo --version`, `pg_isready` | PASS |
| DB unit tests | `cargo test -p pizzora-db --lib` | PASS — 7 passed, 2 ignored |
| DB live integration | `TEST_POSTGRES_URL=... cargo test -p pizzora-db --lib -- --ignored --test-threads=1` | PASS — 3 passed |
| Workspace build | `cargo build --workspace` | PASS |
| Fresh schema table count | `SELECT COUNT(*) FROM information_schema.tables ...` | PASS — 51 tables |
| Rust formatter | `cargo fmt --all && cargo fmt --all -- --check` | PASS |
| API unit tests | `cargo test -p pizzora-api --lib` | PASS — 7 passed |
| Live reorder API tests | `cargo test -p pizzora-api --test pg18_phase8_reorder -- --ignored --nocapture --test-threads=1` | PASS — 4 passed (200/403/404, reconciliation, historical warning) |
| Shared contract tests | `./gradlew -I /tmp/opencode/pizzora-gradle-init.gradle --dependency-verification=off :shared:contracts:test --no-daemon` | PASS — 40 passed |
| UniFFI tests | `cargo test -p pizzora-uniffi` | PASS — 11 passed |
| UniFFI all-features build | `cargo build -p pizzora-uniffi --all-features` | PASS |
| Full Rust workspace | `cargo fmt --all -- --check && cargo test --workspace` | PASS — exit 0; `qa-artifacts/phase8/regression/w8_rust_workspace_tests.log` |
| Full live PostgreSQL suite | `DATABASE_URL=... TEST_POSTGRES_URL=... cargo test --workspace -- --ignored --test-threads=1` | PASS — exit 0; `qa-artifacts/phase8/regression/w8_live_postgres_tests.log` |
| Kotlin backend + shared contracts | `./gradlew --init-script /tmp/opencode/pizzora-final-init.gradle --dependency-verification=off -PbackendOnly=true :shared:contracts:test :backend:test --no-daemon --rerun-tasks` | PASS — 86 backend + 40 shared-contract tests, 0 failures/errors; `qa-artifacts/phase8/backend/w5_kotlin_backend.log`, `w8_final_kotlin_backend.log` |
| Android builder (W6) | `:app:testDebugUnitTest` with environment-only init wrapper | PASS — 28 tests (9 new Phase 8 + 19 existing), 0 failures; `qa-artifacts/phase8/visual/builder/w6_builder_tests.log` |
| Full Android unit suite (final wiring) | `./gradlew --init-script /tmp/opencode/pizzora-final-init.gradle clean :app:testDebugUnitTest --dependency-verification=off -PpizzoraRequireRust=true --no-daemon` | PASS — 230 tests, 0 failures, 0 errors, 0 skipped; `qa-artifacts/phase8/visual/builder/w6_modifier_wiring.log` |
| Kotlin 33-vector parity | `./gradlew -I /tmp/opencode/pizzora-gradle-init.gradle --dependency-verification=off -PbackendOnly=true :backend:test --tests '*Phase8*' --no-daemon --rerun-tasks` | PASS — 13 Phase 8 tests, 0 failures; all 33 vectors verified through `PricingService`; `qa-artifacts/phase8/parity/kotlin_33_vector_parity.log` |
| Live checkout modifier regression | `cargo test -p pizzora-api --test pg18_phase8_reorder -- --ignored --nocapture --test-threads=1` | PASS — 5 tests, 0 failed; `qa-artifacts/phase8/regression/checkout_modifier_regression.log` |
| Local API end-to-end smoke | `python3 qa-artifacts/phase8/regression/local_api_smoke.py` against `127.0.0.1:8080` | PASS — quote, cart resolution, checkout, reorder; `qa-artifacts/phase8/regression/local_api_smoke.log` |
| Post-fix Rust workspace | `cargo fmt --all -- --check && cargo test --workspace` | PASS — exit 0; `qa-artifacts/phase8/regression/w8_rust_workspace_tests_post_fix.log` |
| Post-fix live PostgreSQL suite | `DATABASE_URL=pizzora_w8_postfix TEST_POSTGRES_URL=pizzora_w8_postfix cargo test --workspace -- --ignored --nocapture --test-threads=1` | PASS — exit 0; `qa-artifacts/phase8/regression/w8_live_postgres_tests_post_fix.log` |
| Checked breakdown arithmetic | `cargo fmt --all -- --check && cargo test -p pizzora-core` | PASS — all core tests; `qa-artifacts/phase8/regression/checked_breakdown_arithmetic.log` |
| Real migration CLI | `--preflight-only` / `--migrate-only` on fresh + Flyway V1–V3 profiles | PASS — four exit codes 0; `qa-artifacts/phase8/migrations/PHASE8_MIGRATION_CLI_EVIDENCE.md` |
| Final Rust workspace (post-advisory) | `cargo fmt --all -- --check && cargo test --workspace` | PASS — exit 0; `qa-artifacts/phase8/regression/w8_rust_workspace_tests_final.log` |
| Final live PostgreSQL suite | fresh `pizzora_w8_final`, `cargo test --workspace -- --ignored --nocapture --test-threads=1` | PASS — exit 0; `qa-artifacts/phase8/regression/w8_live_postgres_tests_final.log` |
| Final local API smoke | `python3 qa-artifacts/phase8/regression/local_api_smoke.py` against rebuilt binary | PASS — quote/cart/checkout/reorder; `qa-artifacts/phase8/regression/local_api_smoke_final.log` |
| Final Kotlin backend + shared contracts (all H changes) | `./gradlew --init-script /tmp/opencode/pizzora-final-init.gradle --dependency-verification=off -PbackendOnly=true :shared:contracts:test :backend:test --no-daemon --rerun-tasks` | PASS — 86 + 40 tests; `qa-artifacts/phase8/backend/w8_final_kotlin_backend.log` |
| Final unit-test XML capture | Copy final `TEST-*.xml` files and derive counts | PASS — 230 app + 86 backend + 40 shared-contract tests, 0 failures/errors/skipped; `qa-artifacts/phase8/unit-test-xml/README.md` |
| Checkout idempotency hash MINOR fix | `cargo fmt --all -- --check && cargo test -p pizzora-api --lib` | PASS — 7 API unit tests; `qa-artifacts/phase8/regression/idempotency_hash_unit.log` |
| Checkout modifier + idempotency conflict live regression | `DATABASE_URL=... TEST_POSTGRES_URL=... cargo test -p pizzora-api --test pg18_phase8_reorder -- --ignored --nocapture --test-threads=1` | PASS — 5 live tests including `IDEMPOTENCY_CONFLICT`; `qa-artifacts/phase8/regression/checkout_modifier_regression_final.log` |
| Checkpoint H Rust workspace | `cargo fmt --all -- --check && cargo test --workspace` | PASS — exit 0; `qa-artifacts/phase8/regression/w8_rust_workspace_tests_checkpoint_h.log` |
| Checkpoint H fresh live PostgreSQL suite | fresh `pizzora_w8_checkpoint_h`, `cargo test --workspace -- --ignored --nocapture --test-threads=1` | PASS — exit 0, all ignored live tests PASS; `qa-artifacts/phase8/regression/w8_live_postgres_tests_checkpoint_h.log` |
| Final Android runtime QA | real `emulator-5554`, local API quote/cart/reorder, screenshot + UIAutomator XML capture | PASS — `qa-artifacts/phase8/visual/runtime/ANDROID_RUNTIME_QA.md`; all observed FAIL rows closed by corrected analysis/probe |
| Modifier-aware duplicate merge probe | authenticated `PUT /api/v1/cart` with two identical Basil-bearing lines, then `GET /api/v1/cart` | PASS — one line, qty 25, Basil preserved; `qa-artifacts/phase8/visual/runtime/129_merge_same_modifier_config*.json` |

---

## 7. Blockers

| ID | Blocker | Impact | Mitigation |
|----|---------|--------|------------|
| B8-01 | Reviewed contract not present as a standalone file | Agents could implement against stale draft | Binding addendum created; all agents must read it |
| B8-02 | Intermediate Phase 8 work not all committed | W3/W4/W5/W7 committed; W6/W8/H work ready for the final scoped Checkpoint H commit | Stage scoped files after each workstream verifies; do not stage `.opencode/opencode.jsonc` |
| B8-03 | Runtime backend API not listening | Android runtime QA cannot exercise server-backed flows | RESOLVED — local non-production Axum API running on `8080` against `pizzora-pg18`, migrations disabled on prepared 51-table schema; QA catalog seeded |
| B8-04 | Gradle foojay plugin resolution / Maven Central HTTP 429 | Gradle tasks can fail before compilation in this environment | Environment-only init scripts (`pizzora-gradle-init.gradle`, W7 variant) + `--dependency-verification=off`; `:app:generateUniFfiKotlinBindings` now succeeds through the wrapper; no repository build files changed |

The initial W9 independent review of committed A–G found 2 BLOCKERs and 3 MAJOR evidence gaps
(`qa-artifacts/phase8/review/W9_INITIAL_INDEPENDENT_REVIEW.md`). Both blockers were remediated, both
code/evidence MAJORs were closed, and the final independent re-review returned **PASS** with no
BLOCKER or MAJOR (`qa-artifacts/phase8/review/W9_FINAL_INDEPENDENT_REVIEW.md`). The one MINOR it
found (checkout idempotency hash omitted Phase 8 modifier IDs) was also closed and covered by a live
conflict regression. Section 10 of `W9_FINAL_INDEPENDENT_REVIEW.md` closes the conditional runtime
gate unconditionally: all 11 criteria are satisfied, and the three initially observed FAIL rows were
a QA probe/setup error, a density-conversion error, and specified pre-existing IME behavior,
respectively. No Phase 8 blocker remains.

---

## 8. Commit and push log

| Checkpoint | Commit | Push | Status |
|------------|--------|------|--------|
| Baseline | `db41169` | `origin/ui-ux-changes` | VERIFIED |
| A — Schema | `897b8f9` | `origin/ui-ux-changes` | VERIFIED — 51 tables, 2 profiles + rollback, 10 tests PASS |
| B — Rust core pricing | `2d4a682` | `origin/ui-ux-changes` | VERIFIED — independent re-audit PASS; 21 tests + 33 vectors |
| C — API/DTO/reorder | `a332540` | `origin/ui-ux-changes` | VERIFIED — fmt, API 7 tests, shared contracts 40 tests PASS |
| D — UniFFI | `c9f10ab` | `origin/ui-ux-changes` | VERIFIED — independent re-audit PASS; 11 tests, bindgen, rustfmt |
| E — Reorder hardening/wire shape | `ea9bfd1` | `origin/ui-ux-changes` | VERIFIED — rustfmt, API 7 tests, shared contracts 40 tests, live reorder 4/4 PASS |
| F — Android orders/reorder | `946d900` | `origin/ui-ux-changes` | VERIFIED — 7 new + 20 total order tests PASS |
| G — Kotlin backend/contract parity | `bb63746` | `origin/ui-ux-changes` | VERIFIED — 84 backend + 40 shared-contract tests, full Rust workspace + full live PostgreSQL PASS |
| H — Final integration/runtime/review | `(this commit)` | `origin/ui-ux-changes` | VERIFIED — 230 app + 86 backend + 40 shared-contract tests, 33 vectors, full Rust/live-PG suites, server-backed runtime QA, unconditional independent PASS; final verdict `PHASE 8 COMPLETE` |

---

## 9. Remaining acceptance criteria

The final independent review is **PASS** with no BLOCKER or MAJOR
(`qa-artifacts/phase8/review/W9_FINAL_INDEPENDENT_REVIEW.md`, §10). All 11 acceptance criteria are
verified. The conditional runtime gate was closed by
`qa-artifacts/phase8/visual/runtime/ANDROID_RUNTIME_QA.md`; the observed FAIL rows were reclassified
with corrected evidence and none requires a product change. The phase verdict is **PHASE 8 COMPLETE**.

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | Fresh SQLx V4+ migration | **VERIFIED** | `qa-artifacts/phase8/migrations/FRESH_SCHEMA_REPORT.md`, `PHASE8_MIGRATION_CLI_EVIDENCE.md`, fresh CLI logs exit 0 |
| 2 | Flyway V1–V3 adoption | **VERIFIED** | `PHASE8_MIGRATION_CLI_EVIDENCE.md`, `p8cli_adopt_*.log`, both flags exit 0 |
| 3 | 51 tables + invariants on both profiles | **VERIFIED** | `migrator.rs` 51-table inventory; CLI verify logs; `w8_live_postgres_tests_checkpoint_h.log` |
| 4 | Rollback/reapply | **VERIFIED** | `pg18_phase8_rollback_and_reapply_succeeds ... ok` in `w8_live_postgres_tests_checkpoint_h.log` |
| 5 | 33 golden vectors, Rust + Kotlin | **VERIFIED** | `qa-artifacts/phase8/parity/w2_rust_pricing.log`, `kotlin_33_vector_parity.log`, digest `a1e6f596...` |
| 6 | Pricing rules/overflow | **VERIFIED** | `rust/crates/pizzora-core/src/pricing.rs`, `qa-artifacts/phase8/regression/checked_breakdown_arithmetic.log` |
| 7 | Cart validation | **VERIFIED** | Rust validation tests, API 5/5 live, `checkout_modifier_regression_final.log` |
| 8 | DTO/API backward compatibility | **VERIFIED** | shared serialization tests (40), historical reorder live test, additive defaults |
| 9 | Reorder ownership/IDs/reconciliation | **VERIFIED** | `pg18_phase8_reorder.rs` 5/5 live, `qa-artifacts/phase8/api-contracts/` |
| 10 | Android builder/reorder UI + accessibility | **VERIFIED** | Robolectric 230/230 PASS; final emulator screenshots/UIAutomator XML/TalkBack/font-scale/large-display evidence; `ANDROID_RUNTIME_QA.md` §7 triage; corrected duplicate-merge probe |
| 11 | Phase 3/4/6/7 regression + review | **VERIFIED** | `qa-artifacts/phase8/unit-test-xml/`, final Rust/Kotlin suites, `W9_FINAL_INDEPENDENT_REVIEW.md` PASS with runtime gate closed |

**Final verdict rationale:** the exact allowed verdict string is **PHASE 8 COMPLETE** because all 11
criteria are verified, the independent review has no BLOCKER/MAJOR, its MINOR was fixed and
re-reviewed, the conditional runtime evidence gate is closed, and no production deployment or
release action remains in scope.

---

## 10. Evidence index

| Evidence | Path | Status |
|----------|------|--------|
| Reviewed resolutions | `docs/PHASE8_REVIEWED_CONTRACT_RESOLUTIONS.md` | CREATED |
| Migration reports | `qa-artifacts/phase8/migrations/` | CREATED — Checkpoint A PASS; real CLI `--preflight-only` / `--migrate-only` exit logs present for fresh and Flyway V1–V3 adoption profiles (`PHASE8_MIGRATION_CLI_EVIDENCE.md`) |
| Golden parity reports | `qa-artifacts/phase8/parity/` | CREATED — W2 PASS (21 tests, 33/33 vectors) |
| API/contract evidence | `qa-artifacts/phase8/api-contracts/` | CREATED — W3 PASS (API 7 tests, contracts 40 tests) |
| UniFFI evidence | `qa-artifacts/phase8/uniffi/` | CREATED — W4 re-audit PASS (11 tests, direct bindgen, rustfmt) |
| Kotlin backend evidence | `qa-artifacts/phase8/backend/` | CREATED — W5 PASS (84 backend + 40 shared-contract tests) |
| Rust regression evidence | `qa-artifacts/phase8/regression/` | CREATED — full workspace + live PostgreSQL PASS before and after fixes; local API smoke PASS; final Checkpoint H logs `w8_rust_workspace_tests_checkpoint_h.log`, `w8_live_postgres_tests_checkpoint_h.log`, `checkout_modifier_regression_final.log`, `idempotency_hash_unit.log` |
| Unit test XML | `qa-artifacts/phase8/unit-test-xml/` | CREATED — final copied XML: 230 app + 86 backend + 40 shared-contract tests, 0 failures/errors/skipped; `README.md` records derived counts |
| Visual QA | `qa-artifacts/phase8/visual/` | VERIFIED — API 36 `emulator-5554` server-backed runtime report, screenshots, UIAutomator XML, command log, logcat, accessibility settings and restoration evidence |
| Android builder evidence | `qa-artifacts/phase8/visual/builder/` | CREATED — W6 28 app tests PASS; final wiring suite 230/230 PASS |
| Android orders evidence | `qa-artifacts/phase8/visual/orders/` | CREATED — W7 7 new + 20 order tests PASS |
| Local API runtime | `qa-artifacts/phase8/regression/local_api.log`, `visual/seed_local_qa_catalog.sh` | CREATED — non-production API on `8080`; 2 products, 3 crusts, 3 toppings, 4 cheeses, 4 sauces, 4 standard toppings, 2 half-and-half configs; passwords now env-sourced |
| Android runtime QA | `qa-artifacts/phase8/visual/runtime/ANDROID_RUNTIME_QA.md` | VERIFIED — builder/cart/reorder, TalkBack, 1.5x/2.0x fonts, large display, quantity/undo/coupon/clear; observed FAIL rows closed in §7; tokens/passwords redacted |
| Independent review | `qa-artifacts/phase8/review/` | FINAL REVIEW PASS UNCONDITIONAL — `W9_FINAL_INDEPENDENT_REVIEW.md` §10 closes runtime gate; initial FAIL and remediation trail in `W9_INITIAL_INDEPENDENT_REVIEW.md`; no BLOCKER/MAJOR remains |

### 10.1 Acceptance-criteria command map

| # | Criterion | Command / artifact |
|---|-----------|--------------------|
| 1 | Fresh migration | `qa-artifacts/phase8/migrations/FRESH_SCHEMA_REPORT.md` (51 tables); `PHASE8_MIGRATION_CLI_EVIDENCE.md` — `--migrate-only` + `--preflight-only` exit 0 |
| 2 | Flyway V1–V3 adoption | `pg18_integration_tests.log` — adoption profile PASS; `PHASE8_MIGRATION_CLI_EVIDENCE.md` — both CLI flags exit 0 |
| 3 | 51-table inventory + invariants | `migrator.rs` unit test + integration `verify_catalog_invariants` |
| 4 | Rollback/reapply | `pg18_integration_tests.log` — `pg18_phase8_rollback_and_reapply_succeeds` |
| 5 | 33 golden vectors | `cargo test -p pizzora-core --test phase8_golden_parity_tests` + Kotlin parity |
| 6 | Pricing rules | Rust core unit tests + UniFFI parity exports |
| 7 | Cart validation | Rust validation tests + API validation tests |
| 8 | DTO/API backward compatibility | Kotlin serialization tests + historical reorder test |
| 9 | Reorder endpoint | API tests (200/403/404 + unavailable reconciliation) |
| 10 | Android builder/reorder UI | Robolectric 230/230 + API 36 emulator (`pizzora_api36_qa`) `ANDROID_RUNTIME_QA.md`, screenshots/XML, `129_merge_same_modifier_config*.json` |
| 11 | Regression + independent review | Full Gradle/Rust test XML + `qa-artifacts/phase8/review/` |

# PIZZORA Phase 8 — Implementation Progress Report

**Phase:** 8 — Extended Pizza Modifiers & Reorder\
**Branch:** `ui-ux-changes`\
**Baseline commit:** `db41169bf6d0852feae3dbc4ada93e0b50f85c53`\
**Report updated:** 2026-11-27\
**Current verdict:** **PHASE 8 IN PROGRESS**

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
| W3 DTO/API + reorder endpoint | **IMPLEMENTED / VERIFIED** | `general` subagent (`ses_de3265e86ffe22X5qe8D2MaULu`, `opencode/big-pickle`) | Initial HTTP 429 retry preserved partial work; API build + 7 tests PASS; shared contracts 40 tests PASS; reorder route registered; await scoped commit |
| W4 UniFFI | **IMPLEMENTED / VERIFIED / REVIEW PASS** | `general` subagent (`ses_de31be575ffeJFJhKdoHPdiJ1I`, `opencode/big-pickle`) | Initial audit REJECTED; all findings repaired; independent re-audit PASS. 11 tests, all-features build, rustfmt, direct Kotlin bindgen evidence PASS |
| W5 Kotlin backend five-service audit | **IN PROGRESS** | `general` subagent (`ses_de321c174ffemMqJrNxjY5Tmsg`, `opencode/big-pickle`) | Audit complete; implementing five services + reorder route + new tests |
| W6 Android builder | **IN PROGRESS** | `general` subagent (`ses_de31f0427ffeWCvzsyjxOW0YjM`, `opencode/big-pickle`) | DTO/FFI shapes frozen; builder modifier UI + Robolectric tests |
| W7 Android orders/reorder | **IN PROGRESS** | `general` subagent (`ses_de31edc10ffea4fG4y0LE3xTLU`, `opencode/big-pickle`) | Preview, extended modifier rendering, reconciliation dialog; API client method added by coordinator |
| W8 Regression/QA | IN PROGRESS (DB evidence) | `p8-test-engineer`, `android-qa` | Checkpoint A logs captured |
| W9 Independent review | NOT STARTED | `p8-reviewer` | After W8 |

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
| Shared contract tests | `./gradlew -I /tmp/opencode/pizzora-gradle-init.gradle --dependency-verification=off :shared:contracts:test --no-daemon` | PASS — 40 passed |
| UniFFI tests | `cargo test -p pizzora-uniffi` | PASS — 11 passed |
| UniFFI all-features build | `cargo build -p pizzora-uniffi --all-features` | PASS |

---

## 7. Blockers

| ID | Blocker | Impact | Mitigation |
|----|---------|--------|------------|
| B8-01 | Reviewed contract not present as a standalone file | Agents could implement against stale draft | Binding addendum created; all agents must read it |
| B8-02 | Intermediate Phase 8 work not all committed | W3/W4/W5/W6/W7 still in progress | Stage scoped files after each workstream verifies; do not stage `.opencode/opencode.jsonc` |
| B8-03 | Runtime backend API not listening | Android runtime QA cannot exercise server-backed flows | Emulator is READY; start the local API against `pizzora-pg18` during W8, no production deployment |
| B8-04 | Gradle foojay plugin resolution / Maven Central HTTP 429 | Gradle tasks can fail before compilation in this environment | Environment-only init script `/tmp/opencode/pizzora-gradle-init.gradle` + `--dependency-verification=off`; no repository build files changed |

No open BLOCKER or MAJOR design findings remain after the addendum. W2 completion is pending
scoped commit; W3/W4/W5 are still in progress.

---

## 8. Commit and push log

| Checkpoint | Commit | Push | Status |
|------------|--------|------|--------|
| Baseline | `db41169` | `origin/ui-ux-changes` | VERIFIED |
| A — Schema | `897b8f9` | `origin/ui-ux-changes` | VERIFIED — 51 tables, 2 profiles + rollback, 10 tests PASS |
| B — Rust core pricing | `2d4a682` | `origin/ui-ux-changes` | VERIFIED — independent re-audit PASS; 21 tests + 33 vectors |
| C — API/DTO/reorder | `a332540` | `origin/ui-ux-changes` | VERIFIED — fmt, API 7 tests, shared contracts 40 tests PASS |
| D — UniFFI | pending | pending | VERIFIED locally; commit in progress |

---

## 9. Remaining acceptance criteria

All 11 criteria from the reviewed addendum remain open until their evidence is recorded in Section
10. The phase verdict stays **PHASE 8 IN PROGRESS** until the independent reviewer signs off.

---

## 10. Evidence index

| Evidence | Path | Status |
|----------|------|--------|
| Reviewed resolutions | `docs/PHASE8_REVIEWED_CONTRACT_RESOLUTIONS.md` | CREATED |
| Migration reports | `qa-artifacts/phase8/migrations/` | CREATED — Checkpoint A PASS |
| Golden parity reports | `qa-artifacts/phase8/parity/` | CREATED — W2 PASS (21 tests, 33/33 vectors) |
| API/contract evidence | `qa-artifacts/phase8/api-contracts/` | CREATED — W3 PASS (API 7 tests, contracts 40 tests) |
| UniFFI evidence | `qa-artifacts/phase8/uniffi/` | CREATED — W4 repair PASS pending re-audit (11 tests, direct bindgen) |
| Kotlin backend evidence | `qa-artifacts/phase8/backend/` | PENDING (W5) |
| Unit test XML | `qa-artifacts/phase8/unit-test-xml/` | PENDING |
| Visual QA | `qa-artifacts/phase8/visual/` | READY — API 36 `emulator-5554` booted; final screens deferred until W6/W7 |
| Independent review | `qa-artifacts/phase8/review/` | PENDING |

### 10.1 Acceptance-criteria command map

| # | Criterion | Command / artifact |
|---|-----------|--------------------|
| 1 | Fresh migration | `qa-artifacts/phase8/migrations/FRESH_SCHEMA_REPORT.md` (51 tables) |
| 2 | Flyway V1–V3 adoption | `pg18_integration_tests.log` — adoption profile PASS |
| 3 | 51-table inventory + invariants | `migrator.rs` unit test + integration `verify_catalog_invariants` |
| 4 | Rollback/reapply | `pg18_integration_tests.log` — `pg18_phase8_rollback_and_reapply_succeeds` |
| 5 | 33 golden vectors | `cargo test -p pizzora-core --test phase8_golden_parity_tests` + Kotlin parity |
| 6 | Pricing rules | Rust core unit tests + UniFFI parity exports |
| 7 | Cart validation | Rust validation tests + API validation tests |
| 8 | DTO/API backward compatibility | Kotlin serialization tests + historical reorder test |
| 9 | Reorder endpoint | API tests (200/403/404 + unavailable reconciliation) |
| 10 | Android builder/reorder UI | Robolectric + API 36 emulator (`pizzora_api36_qa`) captures |
| 11 | Regression + independent review | Full Gradle/Rust test XML + `qa-artifacts/phase8/review/` |

# PIZZORA Phase 9 — Readiness Assessment

**Date:** 2026-11-27
**Branch:** `ui-ux-changes`
**Assessed HEAD:** `9a9cf3810d212c44977ed7d39cb5feb0b19862f1`
**Phase 8 checkpoint:** `5c21fa4` present and ancestor of HEAD
**Assessment type:** Read-only repository, plan, code, evidence, CI, and documentation audit. No Phase 9 implementation, plan edit, commit, push, merge, deployment, production change, migration execution, or infrastructure change was made.

---

## 1. Executive summary

This assessment treats `public/implementation_plan.md` as the sole canonical source of phase scope. The current canonical plan is V2, explicitly labelled “Planning & Specification Only — Zero Source Code Modified” (`public/implementation_plan.md:L1-L5`), and ends with Phase 8 — “Extended Pizza Modifiers & Reorder” (`public/implementation_plan.md:L903-L922`). **It contains no Phase 9 section, no Phase 9 objective, no Phase 9 acceptance criteria, no Phase 9 exit gate, and no Phase 9 approval.** The mandatory phase exit gate in Section 12 applies only to Phases 1–8 (`public/implementation_plan.md:L761-L766`).

The repository does contain non-canonical Phase 9-labelled evidence for two mutually different meanings:

1. **Production cutover / Ktor retirement** — archived migration plan `public/assets/aistudio/implementation_plan_12__a6986755.md:L524-L553`, operational runbook `infra/RUNBOOK.md:L238-L320`, smoke script `infra/scripts/verify-production-cutover.sh`, rollback assets, and commit `7d1607e` (“migrate backend to Rust services phase 9”), which is an ancestor of the assessed HEAD.
2. **Ingredient inventory and relational recipe management** — archived plan `public/assets/aistudio/implementation_plan_6__ab381b99.md:L507-L516`, Kotlin backend `InventoryService`/`RecipeService`, shared inventory/recipe DTOs, SQL schema in Flyway `V1`, and `backend/src/test/kotlin/com/pizzora/backend/Phase9InventoryAndRecipesTest.kt`.

Neither source can override the current canonical plan. The two meanings imply different workstreams, owners, gates, and risks. Proceeding without a written scope decision would require inventing phase objectives and acceptance criteria.

Phase 8 closure is supported by substantial evidence: checkpoint `5c21fa4`, final independent PASS, 230 app + 86 backend + 40 shared-contract tests in copied XML evidence, 33 pricing vectors, migration CLI evidence, server-backed Android runtime QA, and Rust/live PostgreSQL regressions recorded before the Clippy cleanup. The one later commit, `9a9cf38`, is a behavior-preserving Clippy cleanup; the associated Clippy-fix verification workspace log shows live-PG suites as ignored, but the log contains no commit SHA, so execution at exact final HEAD is independently unverified. Phases 6 and 7 remain closed in the canonical plan; Phase 8 is recorded as complete in its implementation report. No evidence was found that this assessment reopened any earlier phase.

**Readiness conclusion:** the blocking issue is not a known code defect in Phase 8. It is the absence of a canonical Phase 9 definition and the presence of conflicting non-canonical Phase 9 definitions. Readiness cannot be honestly certified until the stakeholder selects the Phase 9 scope, records it in the canonical plan or an equally authoritative approved artifact, and defines the applicable gates. The document below supplies provisional workstreams, dependency graph, milestones, gates, acceptance criteria, risk register, and out-of-scope boundary for both candidate interpretations, but none of that is authorization to implement. The authoritative final verdict is recorded in Section 15.3.

---

## 2. Assessment method and evidence sources

The assessment used direct, read-only inspection of:

- **Git state:** `pwd`, `git rev-parse`, `git status`, `git log`, `git worktree list`, `git cat-file`, `git merge-base`, `git diff --check`, and inspected commits `5c21fa4`, `9a9cf38`, `7d1607e`, and `066942c`.
- **Canonical scope:** the complete current `public/implementation_plan.md` (all 1,148 lines), including the introduction (`L1-L5`), Phase 6 status (`L886`), Phase 7 scope (`L888-L901`), Phase 8 scope (`L903-L922`), mandatory exit gate (`L761-L766`), and revision history (`L1033-L1148`).
- **Prior phase closure evidence:** `docs/PHASE7_READINESS_ASSESSMENT.md`, `docs/PHASE8_READINESS_ASSESSMENT.md`, `docs/PHASE8_IMPLEMENTATION_PROGRESS_REPORT.md`, `docs/PHASE8_BACKEND_CONTRACT.md`, `docs/PHASE8_REVIEWED_CONTRACT_RESOLUTIONS.md`, and `qa-artifacts/phase8/review/W9_FINAL_INDEPENDENT_REVIEW.md`.
- **Phase 8 QA evidence:** `qa-artifacts/phase8/unit-test-xml/README.md`, migration CLI evidence, parity logs, Rust regression logs, Kotlin backend logs, local API smoke logs, and `qa-artifacts/phase8/visual/runtime/ANDROID_RUNTIME_QA.md`.
- **Non-canonical Phase 9 evidence:** archived plans under `public/assets/aistudio/`, `infra/RUNBOOK.md`, `infra/scripts/verify-production-cutover.sh`, `infra/docker-compose.ktor-rollback.yml`, `.railway/railway.ktor-rollback.ts`, `backend/src/main/kotlin/com/pizzora/backend/inventory/InventoryService.kt`, `backend/src/main/kotlin/com/pizzora/backend/recipes/RecipeService.kt`, `shared/contracts/src/main/kotlin/com/pizzora/contracts/InventoryDto.kt`, Flyway `V1__core_schema.sql`, and `Phase9InventoryAndRecipesTest.kt`.
- **CI/deployment boundary:** `.github/workflows/security-and-ci.yml`, `.github/workflows/publish-backend.yml`, `.github/workflows/publish-rust-backend.yml`, `.github/workflows/phase4-android-emulator-qa.yml`, `.railway/railway.ts`, and related rollback assets.
- **Independent reviewers:** six read-only OpenCode reviewers were launched: architecture, backend/data, Rust core, Android, QA/test, and independent critical review. Initial backend and critical-review invocations failed with a `DEGRADED function cannot be invoked` tooling error; both were retried, with the backend role reissued through a general read-only reviewer. Per user instruction during this assessment, all new subagents and all failed-agent retries from this point use `opencode/big-pickle`; already-running healthy agents were left undisturbed. A seventh, Big Pickle (`opencode/big-pickle`) read-only document-consistency reviewer was run after the first three domain reports. The original QA/test, Android, and critical reviewers later timed out waiting for response headers; all three roles were retried with `opencode/big-pickle` (critical after an initial `DEGRADED` failure). Reviewer outcomes are consolidated in Section 15.

No Gradle, Cargo, database, emulator, Railway, or production test was executed by this assessment. Test results cited below are **evidence inspected**, not tests executed in this session.

---

## 3. Repository state and change control

| Item | Verified state |
|------|----------------|
| Working directory / repo root | `/workspace/pizzabi` |
| Branch | `ui-ux-changes` |
| HEAD | `9a9cf3810d212c44977ed7d39cb5feb0b19862f1` |
| `origin/ui-ux-changes` | `9a9cf3810d212c44977ed7d39cb5feb0b19862f1` |
| Ahead/behind vs origin | `0 0` |
| `5c21fa4` | `git cat-file -t` = `commit`; `git merge-base --is-ancestor 5c21fa4 HEAD` = `0` |
| Recent history | `9a9cf38 chore(phase8): resolve clippy warnings` → `5c21fa4 feat(phase8): complete modifiers, reorder, runtime QA and review` → `bb63746` → `946d900` … |
| Working tree | Initial pre-creation snapshot: only ` M .opencode/opencode.jsonc`, no untracked files. After this assessment: plus untracked `docs/PHASE9_READINESS_ASSESSMENT.md`. |
| `git diff --check` | Exit `0`, no whitespace errors |
| Other worktrees | `main` at `415c17b`; `mattpocock` at `6544440`; prunable detached worktree at `4752c67`. All left untouched. |
| Assessment document before this run | `docs/PHASE9_READINESS_ASSESSMENT.md` absent |

The modified `.opencode/opencode.jsonc` predates this assessment and was preserved. It was not staged, edited, reverted, or included in any Git operation. No branch, ref, worktree, lockfile, source file, test, schema, migration, contract, CI file, or canonical plan was modified.

---

## 4. Phase 6/7/8 closure baseline preservation

| Phase | Canonical / documented status | Evidence inspected in this assessment | Phase 9 implication |
|-------|-------------------------------|----------------------------------------|---------------------|
| Phase 6 | Exit Gate satisfied (`public/implementation_plan.md:L886`) | `docs/PHASE7_READINESS_ASSESSMENT.md:L49-L61` records 191/191 tests and 7/7 visual captures; Phase 6 scope remains payment/tracking/history presentation. | Must not be reopened. Any Phase 9 work must preserve payment, WebSocket, IST formatting, and Razorpay process-death restoration behavior. |
| Phase 7 | Exit Gate satisfied; final commit `db41169` in prior report | `docs/PHASE7_READINESS_ASSESSMENT.md:L295-L346` gives final verdict `PHASE 7 READY WITH CONDITIONS`; implementation report cited 207 tests and visual/accessibility evidence. | Must not be reopened. Profile, notification deep-link, and update UI behavior remain regression surface only. |
| Phase 8 | `PHASE 8 COMPLETE` in `docs/PHASE8_IMPLEMENTATION_PROGRESS_REPORT.md:L1-L8`; checkpoint `5c21fa4` at `L210` | `qa-artifacts/phase8/unit-test-xml/README.md:L6-L10` records 230 app + 86 backend + 40 shared-contract tests, all zero failures/errors/skipped. `W9_FINAL_INDEPENDENT_REVIEW.md:L115-L130` closes the runtime gate unconditionally. `9a9cf38` adds only reviewed Clippy cleanup. | Phase 8 is the latest closed baseline. Phase 9 may not silently alter extended-modifier pricing, reorder persistence, DTO compatibility, or SQLx V4–V11 invariants. |

**Phase 8 closure limitation and open W4 defect set:** the accepted Phase 8 closure covers the server-pricing path actually used by Android, Rust core arithmetic, API/DTO behavior, migrations, and runtime QA. The claimed W4 UniFFI workstream was reported `IMPLEMENTED / VERIFIED / REVIEW PASS` (`docs/PHASE8_IMPLEMENTATION_PROGRESS_REPORT.md:L92,L97`), and the final review declared unconditional PASS (`qa-artifacts/phase8/review/W9_FINAL_INDEPENDENT_REVIEW.md:L117,L136,L145`). Rust and critical reviewers found confirmed defects in that FFI surface: half-and-half validation uses prices instead of topping IDs (`rust/crates/pizzora-uniffi/src/lib.rs:424–435,882–904`), issue codes use Rust debug names instead of contract `SCREAMING_SNAKE_CASE` (`:1042–1048`), and reorder precheck diverges from server historical fallback semantics (`:1487–1543,1589–1606,1629–1646` vs `rust/crates/pizzora-db/src/orders.rs:316–318,359–378,393–409`). `DomainBridge` also does not wrap the new exports (`app/src/main/java/com/example/core/domain/DomainBridge.kt:53–89`; `app/src/uniffiBridge/.../UniffiRustDomainBridge.kt:14–45,75–106`). These are open Phase 8 W4 defects requiring an explicit remediation-or-waiver decision. They are not evidence that the server-backed runtime gate failed, but they contradict the unconditional closure claim for the UniFFI workstream and become Phase 9 blocking conditions if the selected scope relies on UniFFI validation/reorder/catalog behavior.

**Clippy-fix verification evidence limitation:** `qa-artifacts/phase8/regression/w9_clippy_fix_rust_workspace_tests.log:L31-L36,L38-L47,L49-L56,L127-L136,L158-L167,L211-L216` marks all 21 `pg18_*`/migrator tests `ignored`; it records only 67 non-live tests as passed. HEAD `9a9cf38` was committed at `2026-11-27T15:16:56Z`, while the latest live-PG log `qa-artifacts/phase8/regression/w8_live_postgres_tests_checkpoint_h.log:L1,L17-L142,L192` is timestamped `2026-11-27T14:30:12Z` and predates the HEAD changes to `rust/crates/pizzora-core/src/cart_validation.rs` and `rust/crates/pizzora-uniffi/src/lib.rs`. The Clippy-fix logs contain no commit SHA, command header, timestamp, or exit code (`w9_clippy_fix_clippy.log` only records `Finished dev profile ... in 0.19s`), so provenance is “associated with Clippy-fix verification,” not independently verified at exact `9a9cf38`. This is an unverified-at-final-HEAD evidence gap, not a demonstrated regression.

**Documentation debt, not a new blocker:** the canonical revision history still states that the Phase 4 exit gate remained open pending live emulator QA (`public/implementation_plan.md:L1115-L1143`). Later Phase 6/7/8 reports and Phase 8 runtime evidence use real emulator QA, but the canonical plan was not edited here. This is recorded as historical inconsistency; it does not reopen Phase 4 and does not substitute for Phase 9 scope approval. The canonical plan also still says the migrator validates 46 tables and 13 V3 indexes (`public/implementation_plan.md:L911`), while the current code validates 51 tables and 25 total indexes (`rust/crates/pizzora-db/src/migrator.rs:21–74,128–157,916–921`).

---

## 5. Canonical Phase 9 scope — absent

### 5.1 What the canonical plan actually defines

- The canonical plan is the customer-facing UI/UX implementation plan and ends at Phase 8 (`public/implementation_plan.md:L903-L922`).
- Section 12’s mandatory phase exit gate is explicitly limited to “Phases 1–8” (`public/implementation_plan.md:L761-L766`).
- Section 14 decisions and Section 15 scorecard close with Phase 1–8-era status; there is no Phase 9 decision entry or scorecard row.
- Revision history ends with Phase 4 execution/CI updates (`public/implementation_plan.md:L1107-L1148`); no Phase 9 execution update exists in that canonical history.
- Before this assessment, searches for `Phase 9` in `docs/` returned no matches; the only current match in `docs/` is this assessment file. Other matches occur in archived assets, infrastructure files, rollback assets, a backend test, and Git history.

### 5.2 Non-canonical Phase 9 candidates

| Candidate | Evidence | Why it cannot be treated as canonical |
|-----------|----------|----------------------------------------|
| Production cutover / Ktor retirement | `public/assets/aistudio/implementation_plan_12__a6986755.md:L524-L553`; `infra/RUNBOOK.md:L238-L320`; `infra/scripts/verify-production-cutover.sh:L1-L162`; `.railway/railway.ktor-rollback.ts:L1-L24`; commit `7d1607e`. | Archived plan is under `public/assets/aistudio/`, not `public/implementation_plan.md`. The runbook has operational guardrails but no approved cutover window, exact service change authorization, or evidence that gates were executed. |
| Inventory / relational recipes | `public/assets/aistudio/implementation_plan_6__ab381b99.md:L507-L516`; Kotlin backend services and DTOs; `Phase9InventoryAndRecipesTest.kt`; schema in `V1__core_schema.sql`. | Different archived plan, older Kotlin-stack roadmap. It conflicts with the production-cutover meaning and is not reflected in the current canonical plan. |

### 5.3 Scope conclusion

There is no authoritative answer to the following Phase 9-defining questions:

- What is the objective: production cutover, inventory/recipes, or something else?
- Which files/modules/screens are in scope?
- Which stage owns acceptance and which gates apply?
- Is production action authorized, and if so during which window and on which exact Railway services?
- What is the rollback point and stability window?
- What counts as Phase 9 completion?

Because these answers are missing or contradictory, implementation readiness cannot be certified. This is the primary blocking condition.

---

## 6. Exact ambiguities and documentation debt

| ID | Ambiguity / debt | Evidence | Effect |
|----|------------------|----------|--------|
| D9-01 | Two unrelated Phase 9 definitions | Archived plans `implementation_plan_12__a6986755.md:L524-L553` and `implementation_plan_6__ab381b99.md:L507-L516` | Must be resolved by stakeholder before workstream selection. |
| D9-02 | Commit history labels work “phase 9” while canonical plan ends at Phase 8 | `7d1607e` is an ancestor of HEAD; canonical plan `L903-L922` is final phase | Commit messages are not scope authority. Historical implementation does not supply missing gates. |
| D9-03 | No canonical Phase 9 exit gate | Canonical gate limited to Phases 1–8 (`L761-L766`) | Existing 4-pillar gate cannot be mechanically reused without approval; cutover needs operational gates instead. |
| D9-04 | No Phase 9 production approval recorded | `infra/RUNBOOK.md:L240`, `L244-L252` require written approval and exact service names | No production Railway action is authorized. |
| D9-05 | No cutover evidence artifact | No `qa-artifacts/**/*cutover*` files found; no CI workflow matching production cutover found | Runbook/script existence is not proof of rehearsal or readiness. |
| D9-06 | Historical Phase 4 gate wording remains open in canonical plan | `public/implementation_plan.md:L1115-L1143` | Documentation debt; not a basis to reopen Phase 4 or block Phase 9 by itself. |
| D9-07 | Phase 8 progress report is an implementation report, not canonical scope | `docs/PHASE8_IMPLEMENTATION_PROGRESS_REPORT.md:L1-L8`, `L198-L212` | It records server-backed closure, not Phase 9 scope; it does not settle the open W4 UniFFI defect set. |

None of these ambiguities was resolved by editing documentation. They are reported for explicit decision.

---

## 7. Candidate A — production cutover / Ktor retirement audit

### 7.1 Existing artifacts

- `infra/RUNBOOK.md:L238-L252` defines six pre-cutover gates: written change-window approval, verified backup/restore, artifact/environment inventory, schema compatibility, staging sign-off/backlog check, and worker drain proof.
- `infra/RUNBOOK.md:L254-L299` defines the cutover sequence: freeze schema, backup, read-only preflight, stop/drain Ktor workers, run locked migration handoff once, deploy Rust API/worker, smoke test, monitor SLOs, and hold Ktor rollback assets warm.
- `infra/RUNBOOK.md:L301-L320` defines rollback triggers, rollback order, expand-and-contract policy, and Ktor retirement criteria requiring explicit approval after a stability window.
- `infra/scripts/verify-production-cutover.sh:L31-L162` checks health/security headers, readiness, public catalog/OpenAPI, external `/metrics` restriction, auth/webhook error envelopes, validation, optional authenticated flows, and optional worker health/metrics.
- `.railway/railway.ktor-rollback.ts:L3-L18` and `infra/docker-compose.ktor-rollback.yml:L1-L7,L77-L126` preserve Ktor rollback configuration with migrations disabled.
- CLI implementation exists for `--preflight-only`, `--require-zero-workers`, `--require-zero-dead-letters`, and `--migrate-only` (`rust/crates/pizzora-api/src/main.rs:27–71`). The locked migrator verifies Flyway V1–V3 checksums, baselines `_sqlx_migrations`, applies SQLx `0004–0011`, and verifies 51-table/Phase 8 invariants (`rust/crates/pizzora-db/src/migrator.rs:12,159–171,317–393,500–580,843,1120–1133`).
- Security/config and monitoring artifacts exist: production JWT/Razorpay fail-fast checks (`rust/crates/pizzora-config/src/lib.rs:20–22,149–169,204–226`), security headers and tests (`rust/crates/pizzora-api/src/middleware.rs:55–68`, `unit_tests.rs:665–828`), and all seven alerts named by the runbook (`infra/monitoring/alerts.yml:13–67`).

### 7.2 Verified gaps

- No repository evidence of a written cutover approval, exact change window, or named Railway services was found.
- No repository evidence of a fresh production `pg_dump`, off-host storage, or verified restore proof was found.
- No repository evidence of pinned pre-cutover Ktor rollback digest, approved Rust release digest, production variable inventory, dead-letter backlog check, or worker drain proof was found. The rollback digest is not actually encoded: `.railway/railway.ktor-rollback.ts:12–16` builds from `infra/Dockerfile.backend` at apply time, and `infra/docker-compose.ktor-rollback.yml:78,105` defaults to `:latest`, despite `infra/RUNBOOK.md:L247,L255,L311` requiring `...@sha256:...`.
- A concrete runbook/migrator contradiction remains: `infra/RUNBOOK.md:L250,L280` says production remains V1–V3 parity and expects `applied_or_verified_versions: [1, 2, 3]`, while `.railway/railway.ts:L27–30` preDeploy applies SQLx `0004–0011` and `rust/crates/pizzora-db/src/migrator.rs:843,1120–1133` reports all versions `1..11`. This must be reconciled before cutover.
- Smoke coverage is weaker than documented. `infra/RUNBOOK.md:L291` claims cart/notifications/orders/ws-ticket checks, invalid webhook rejection, KDS WebSocket sequence progression, and worker health across all loops; checkout is named only in the rollback smoke at `L313`. But `infra/scripts/verify-production-cutover.sh:L31–160` contains no KDS WebSocket, checkout, or owner/KDS smoke path.
- Readiness accepts `DEGRADED` status when PostgreSQL is `UP` (`infra/scripts/verify-production-cutover.sh:L49–57`); tolerating degraded dependencies requires explicit sign-off.
- No production cutover CI workflow or run artifact was found. `.github/workflows/security-and-ci.yml` covers build/test/image validation; publish workflows cover container publication; none authorizes or executes production cutover.
- The smoke script is useful but incomplete as a full cutover acceptance harness: authenticated and worker probes are optional (`infra/scripts/verify-production-cutover.sh:L106-L160`), and no run log for that script exists in the repository.

### 7.3 Candidate A judgment

**Implementation artifacts are substantially in place, but cutover is No-Go as written.** Operational approvals, backup/restore proof, deterministic image pinning, runbook/migrator reconciliation, corrected smoke coverage, staging sign-off, dead-letter/drain evidence, and a rehearsed smoke run are not present. Execution should remain blocked until those gates are satisfied and explicitly recorded.

---

## 8. Candidate B — inventory / relational recipes audit

### 8.1 Existing artifacts

- The archived Kotlin-stack plan defines Phase 9 as ingredient inventory and relational recipe management with reservation/commit/release/restore operations, `inventory_ledger`, deterministic `operation_id`, sorted-ID row locking, and concurrency tests (`public/assets/aistudio/implementation_plan_6__ab381b99.md:L507-L516`).
- Kotlin backend services exist and are wired in `backend/src/main/kotlin/com/pizzora/backend/Application.kt:L49-L70,L386-L405,L465-L466`.
- Shared DTOs exist in `shared/contracts/src/main/kotlin/com/pizzora/contracts/InventoryDto.kt:L7-L94`, with related enums in `CoreEnums.kt:L169-L211`.
- Flyway `V1__core_schema.sql:L188-L194,L377-L390` contains inventory, recipe, and ledger tables/constraints.
- `qa-artifacts/phase8/unit-test-xml/backend/TEST-com.pizzora.backend.Phase9InventoryAndRecipesTest.xml:L2-L7` records 4 tests, zero failures/errors/skipped, covering lifecycle atomicity/idempotency, reversed lock-set deadlock stress, owner CRUD/RBAC/recipe aggregation/FK behavior, and a 20-thread oversell race.
- Rust parity is present: `rust/crates/pizzora-db/src/inventory.rs:11–140,156–902` implements DTO/enum parity, CRUD, and sorted-lock reserve/commit/release/restore; `rust/crates/pizzora-api/src/routes/owner_ops.rs:245–351` and `rust/crates/pizzora-api/src/router.rs:276–293` expose owner inventory/recipe routes with the same permission strings; `rust/crates/pizzora-api/src/dto.rs:647–696` mirrors shared DTOs; OpenAPI parity is recorded in `rust/crates/pizzora-api/src/openapi.rs:127–139`.
- Lifecycle integration exists in Kotlin and Rust checkout/payment/order paths (`backend/.../CheckoutService.kt:223–226,361`, `backend/.../PaymentService.kt:551`, `backend/.../OrderService.kt:194–203`; `rust/crates/pizzora-api/src/services/checkout.rs:420`, `rust/crates/pizzora-payments/src/capture.rs:249`, `rust/crates/pizzora-db/src/orders.rs:655–665`).

### 8.2 Verified gaps

- There is no canonical Phase 9 acceptance criterion for inventory/recipes.
- The backend review found no dedicated Rust equivalent of the Kotlin idempotency/oversell/deadlock/FK assertions. Rust API tests seed inventory/recipes via owner APIs (`rust/crates/pizzora-api/tests/common/mod.rs:534–677`), but no Rust test was found asserting `OPERATION_ID_CONFLICT`, 20-thread oversell prevention, reversed lock-order stress, or cascade/restrict behavior.
- Contract asymmetry: Kotlin `AdjustInventoryRequest.reason` defaults to `MANUAL_ADJUST` (`shared/contracts/src/main/kotlin/com/pizzora/contracts/InventoryDto.kt:38–43`), while Rust `AdjustInventoryRequest.reason` is required (`rust/crates/pizzora-db/src/inventory.rs:130–136`). A legacy client omitting `reason` would succeed on Ktor but fail Rust validation.
- Ktor retirement would remove the only dedicated stress-test suite unless Rust parity tests are added first (`infra/RUNBOOK.md:L318`).
- There is no evidence that the four Kotlin tests correspond to the full archived plan’s manual verification, owner console UI, Rust parity, or production rollout requirements.
- The SQLx V4–V11 Phase 8 lineage and Kotlin `V1`–`V3` baseline are now shared production-schema territory; any new inventory work would require explicit migration ownership and backward-compatibility rules.
- No Android owner-console Phase 9 scope is defined in the current canonical plan.

### 8.3 Candidate B judgment

**Ktor implementation/test-complete; Rust implementation present but parity unverified; Android owner surfaces code/unit-ready but not runtime-verified.** It is not ready as a canonical new Phase 9. Conditions: canonical scope decision; Rust PG18 parity tests for idempotency/oversell/deadlock/FK behavior before Ktor retirement; reconciliation of the `reason` default and other behavioral divergences; recorded test-run evidence; and Android runtime captures if an owner console is in scope. This candidate remains blocked pending scope approval.

---

## 9. Architecture, dependency, migration, and rollback seam audit

- **Canonical architecture authority:** customer-facing architecture remains anchored in `public/implementation_plan.md`; Rust migration/infrastructure artifacts are historical implementation evidence but not phase-scope authority.
- **Phase 8 seams that must not regress:** shared DTO additive defaults, Rust `pizzora-core` pricing/validation, SQLx V4–V11 migrations, `migrator.rs` Flyway V1–V3 checksum adoption, API/UniFFI exports, Android builder/reorder flows, and Kotlin backend parity. Phase 8 closure evidence is in `docs/PHASE8_IMPLEMENTATION_PROGRESS_REPORT.md:L85-L131,L215-L278` and `W9_FINAL_INDEPENDENT_REVIEW.md:L13-L61,L115-L147`.
- **Migration ownership:** canonical Phase 8 rules require immutable Flyway/SQLx V1–V3 mirrors, additive SQLx V4+ only, no competing Flyway V4+, and coordinated DTO/core/API/golden-test changes (`public/implementation_plan.md:L910-L922`). The same rules should be treated as a minimum constraint for any future schema-bearing Phase 9, but they do not themselves define Phase 9.
- **Single migration runner / worker ownership:** `infra/RUNBOOK.md:L359-L364` records one API pre-deploy migrator and one worker owner. Rollback must stop workers before restoring Ktor (`infra/RUNBOOK.md:L307-L314`).
- **Expand-and-contract:** `infra/RUNBOOK.md:L316-L319` requires all V4+ changes to remain backward-compatible until Ktor retirement approval and forbids deleting historical Flyway migrations/evidence.
- **New abstractions:** none are proven necessary for either candidate. The architecture review confirmed a clean 8-crate acyclic Rust workspace (`rust/Cargo.toml:3–12`), pure `pizzora-core`, hand-mirrored Rust/Kotlin DTOs, server-owned inventory/recipes (no Android `DomainBridge` surface needed), existing CLI/migrator/IaC/rollback tokens, and existing Android owner UI wiring (`OwnerCatalogScreen.kt:245–304`, `OwnerOperationsScreen.kt:151–184`, `PizzoraNavHost.kt:1492–1519`). The minimal useful additions would be parity tests and operational hardening, not a new module.
- **Known cross-backend divergences:** Kotlin rejects duplicate recipe ingredients while Rust merges them (`RecipeService.kt:47–52`; `rust/crates/pizzora-api/src/services/recipes.rs:77–88`); Kotlin `INSUFFICIENT_INVENTORY` includes a `details` map while Rust error envelopes do not (`InventoryService.kt:367–377`; `rust/crates/pizzora-api/src/error.rs:157,175`); recipe target-not-found codes differ (`RECIPE_TARGET_NOT_FOUND` vs `VARIANT_NOT_FOUND`/`CRUST_NOT_FOUND`/`TOPPING_NOT_FOUND`); Kotlin records recipe audit before/after JSON while Rust writes `before_json: None` (`RecipeService.kt:97–106`; `services/recipes.rs:192–203`).
- **Latent FFI catalog/default risks:** `FfiCheeseOption`, `FfiSauceOption`, `FfiStandardTopping`, and `FfiHalfAndHalfConfig` hardcode `sort_order: 0` (`rust/crates/pizzora-uniffi/src/lib.rs:286–295,309–318,334–345,361–372`); these conversions are currently unused by exports. FFI catalog parsing also defaults product `is_veg`/`is_taxable` and topping `is_veg` to `true`, and variant `price_paise` to `0` when omitted (`:1164–1173,1200–1204,1237–1241`). If Phase 9 wires these into Android catalog/quote paths, ordering must be carried through and defaults made explicit or fail-closed first.
- **Operational architecture risks:** Railway Ktor rollback IaC restores only `pizzabi`, while Compose rollback includes API and worker (`.railway/railway.ktor-rollback.ts:11–23`; `infra/docker-compose.ktor-rollback.yml:77–115`). Worker-drain proof is heuristic (`migrator.rs:469–480`), and the Android backend switch is build-time only (`app/build.gradle.kts:29–34,60–62`), so cutover/rollback must preserve the public hostname/routes.
- **Integration risk:** the highest-risk seam is production data/schema state and cross-backend behavioral parity, not compilation. Any Phase 9 that touches cutover must be treated as operational change control; any Phase 9 that touches inventory must be treated as shared-schema and dual-backend contract work.

---

## 10. Testing and QA evidence audit

### 10.1 Evidence inspected for the closed baseline

| Evidence | Result recorded in artifact | Assessment use |
|----------|-----------------------------|----------------|
| `qa-artifacts/phase8/unit-test-xml/README.md:L6-L10` | 230 app, 86 backend, 40 shared-contract tests; all zero failures/errors/skipped | Big Pickle QA reviewer independently recomputed counts from the copied XML (41/29/8 files); confirms Phase 8 closure baseline; not re-executed here. |
| `qa-artifacts/phase8/regression/w8_live_postgres_tests_checkpoint_h.log:L1,L17-L142,L192` | 21 normally-ignored live-PG tests executed, exit 0, timestamp `2026-11-27T14:30:12Z` | Confirms pre-HEAD live-PG evidence only; predates the `9a9cf38` changes. |
| `W9_FINAL_INDEPENDENT_REVIEW.md:L139-L145` | Narrative claim of post-fix Clippy/test re-run | Not independently reproducible from undated copied logs; treated as unsupported provenance, not fresh execution. |
| `W9_FINAL_INDEPENDENT_REVIEW.md:L34-L48,L115-L130` | 11 Phase 8 criteria PASS; runtime gate closed | Confirms independent acceptance for the server-backed path; does not settle the W4 UniFFI defect set. |
| `qa-artifacts/phase8/migrations/PHASE8_MIGRATION_CLI_EVIDENCE.md` (referenced at `docs/PHASE8_IMPLEMENTATION_PROGRESS_REPORT.md:L123,L162`) | Fresh and Flyway-adoption `--preflight-only` / `--migrate-only` exit 0 | Confirms migration evidence exists; not re-executed here. |
| `qa-artifacts/phase8/parity/` and `kotlin_33_vector_parity.log` (referenced at `W9_FINAL_INDEPENDENT_REVIEW.md:L30`) | 33-vector Rust/Kotlin parity with fixture digest | Confirms pricing parity baseline; not re-executed here. |
| `qa-artifacts/phase8/regression/` logs (referenced at `docs/PHASE8_IMPLEMENTATION_PROGRESS_REPORT.md:L117-L128,L151-L173`) | Full Rust workspace, earlier live PostgreSQL, local API smoke, idempotency conflict, Clippy; Clippy-fix verification log marks live-PG suites ignored and has no SHA provenance | Confirms earlier regression evidence exists; exact-HEAD live-PG execution is unverified. |
| `qa-artifacts/phase8/visual/runtime/ANDROID_RUNTIME_QA.md:L1-L45` | Real emulator, server-backed quote/cart/reorder flows, screenshots/XML, redaction | Confirms Phase 8 runtime QA; not re-executed here. |

### 10.2 Phase 9 verification requirements that cannot yet be frozen

- **Candidate A:** production preflight log, backup/restore proof, deploy IDs and pinned digests, dead-letter count, worker drain proof, smoke run log with authenticated and worker checks enabled or justified, SLO monitoring window, rollback drill evidence, and explicit go/no-go signature.
- **Candidate B:** canonical acceptance criteria, owner/API authorization matrix, Rust/Kotlin/shared DTO parity decision, concurrency/idempotency tests at production-representative scale, schema migration profile tests, and Android/owner-console scope if intended.
- **Both:** execute the ignored `pg18_*` API/worker/migrator suites on exact HEAD `9a9cf38` with `DATABASE_URL`/`TEST_POSTGRES_URL` and attach logs; earlier live-PG evidence must not be treated as a final-HEAD gate. UniFFI contract tests and Android bridge tests for the new Phase 8 exports must also be executed where the selected scope depends on them.
- **Both:** require command lines, timestamps, exit codes, and commit SHA in all attached logs; the existing Clippy-fix logs lack them. Ordinary feature-branch pushes skip parity, PostgreSQL/Redis integration, Android unit/instrumented, and Docker validation (`.github/workflows/security-and-ci.yml:L303,L397,L459,L520,L655,L672`), so a Phase 9 PR/full-tier run or explicit attached local evidence is mandatory.
- **Both:** a Phase 9-specific exit gate must be approved before test execution begins; the Phase 1–8 gate cannot be assumed to cover operational or inventory work.

No tests were executed by this assessment. All passing claims above are evidence inspection results.

---

## 11. Visual, accessibility, and live-evidence readiness

- Phase 8 visual/accessibility closure is supported by `ANDROID_RUNTIME_QA.md:L40-L45` and `W9_FINAL_INDEPENDENT_REVIEW.md:L121-L130`, including modifier sections, server-quoted price equality, authenticated cart persistence, reorder reconciliation, touch-target analysis, large-display checks, IME behavior classification, focus-restoration test coverage, redaction, and APK provenance.
- **Runtime bridge activation is not proven by the captured evidence:** `RustDomainBridge.kt:19-24` selects `createOrNull() ?: fallbackBridge`, the real provider swallows all `Throwable` (`UniffiRustDomainBridge.kt:47-54`), and no `isNativeRustActive` or `bridgeImplementationName` value appears in `qa-artifacts/phase8` or `qa-artifacts/phase8/visual/runtime/124_logcat_filtered.txt:L1-L57`. APK packaging of Rust/JNI libraries (`qa-artifacts/phase8/visual/runtime/apk_build.log:L47-L48,L106-L107,L166-L179`) proves inclusion, not runtime activation.
- **Runtime QA is debug/local-only:** the debug API defaults to `http://10.0.2.2:8080` (`app/build.gradle.kts:L29-L34`), and the emulator workflow builds/tests debug only (`.github/workflows/phase4-android-emulator-qa.yml:L123-L126,L188-L226`). The signed-release workflow verifies ABI packaging (`.github/workflows/security-and-ci.yml:L937-L968`) but not release-runtime smoke behavior. Focus restoration is unit-test-only because UIAutomator exposed no `focused="true"` node (`ANDROID_RUNTIME_QA.md:L60,L224`), and the Phase 4 screenshot workflow intentionally skips 3 of 8 captures without seeded backend (`.github/workflows/phase4-android-emulator-qa.yml:L706-L712`).
- Candidate A (production cutover) has no new customer UI surface defined. Its “visual” gate should be replaced by operational readiness/smoke evidence; no screenshot matrix exists or is implied by the runbook.
- Candidate B (inventory/recipes) has no current canonical Android scope. Owner `ViewModel`/navigation/API wiring and `Phase14OwnerRobolectricTest` exist, but runtime evidence contains no owner inventory/recipe captures (`ANDROID_RUNTIME_QA.md:L69-L100`). If an owner console is intended, visual/accessibility criteria must be newly specified and runtime-verified; the archived plan alone is insufficient.
- No live emulator, device, staging, or production evidence was produced in this assessment.

---

## 12. CI/CD, backend, and deployment boundary

- `.github/workflows/security-and-ci.yml` covers security preflight, Rust formatting/Clippy/workspace checks, Kotlin shared/backend tests, golden parity, PostgreSQL/Redis integration, Android unit/Robolectric tests, instrumentation, Docker validation, and release APK workflow. It does not authorize production cutover.
- `.github/workflows/publish-backend.yml` and `.github/workflows/publish-rust-backend.yml` build/validate/publish container images; publication is not deployment.
- `.github/workflows/phase4-android-emulator-qa.yml` remains a QA workflow with seeded-backend limitations documented; it is not a Phase 9 gate.
- `.railway/railway.ts` is scoped to `pizzabi` API/worker services; `.railway/railway.ktor-rollback.ts` is a rollback artifact, not an execution authorization.
- `infra/RUNBOOK.md:L240` explicitly forbids altering production Railway services, running production migrations, or retiring Ktor without written approval for the exact window and services.
- `.github/workflows/security-and-ci.yml:L303,L397,L459,L520,L655,L672` skips parity, PostgreSQL/Redis integration, Android unit/instrumented, and Docker validation on ordinary feature-branch pushes; a Phase 9 branch could pass weak CI without running the ignored live-PG suites. Phase 9 acceptance must require a PR/full-tier run or explicitly attached local evidence.
- No Railway apply, Docker deploy, production migration, secret access, backup/restore, or image promotion was performed or authorized in this assessment.

---

## 13. Provisional workstreams, dependency graph, milestones, gates, acceptance criteria, and out-of-scope boundary

These workstreams are **provisional planning inputs only** because the verdict is `BLOCKED`. They become executable only after canonical scope approval.

### 13.1 Workstreams

1. **W0 — Scope authority and approval:** choose Phase 9 meaning, record in canonical plan or equivalent approved artifact, define owner, exit gate, and explicit production/non-production boundary.
2. **W1 — Candidate A cutover readiness:** collect approvals, backup/restore proof, image digests, env inventory, dead-letter/backlog check, worker drain proof, and rehearsed smoke evidence.
3. **W2 — Candidate B inventory/recipes specification:** if selected, define exact backend/Rust/DTO/migration/API/owner-UI scope, authorization matrix, and acceptance tests.
4. **W3 — Migration/contract safety:** validate any schema changes against Flyway V1–V3 immutable baseline, SQLx V4+ ownership, additive/expand-and-contract rules, and golden parity.
5. **W4 — Test and QA harness:** build Phase 9-specific executed evidence for the selected candidate; keep inspected-vs-executed provenance explicit.
6. **W5 — Operational rehearsal / staged validation:** staging sign-off, rollback drill, smoke script run with all intended credentials/probes, SLO dashboard/alert confirmation.
7. **W6 — Independent review:** read-only critical review of final evidence and go/no-go recommendation before any implementation or deployment.
8. **W7 — Documentation hygiene:** separately approved reconciliation of stale plan-history and artifact naming; no canonical plan edit without authorization.

### 13.2 Dependency graph

```text
W0 Scope approval
├── A-selected ─> W1 Cutover evidence ─> W5 Rehearsal ─> W6 Review ─> Go/No-Go
└── B-selected ─> W2 Spec ─> W3 Migration/contract safety ─> W4 Tests ─> W6 Review ─> Implementation gate

Phase 8 closure evidence (5c21fa4 + 9a9cf38) ─> regression constraint for both branches
```

W0 blocks every other workstream. W6 cannot replace W0; review cannot invent scope.

### 13.3 Milestones and verification gates

| Milestone | Gate | Required evidence |
|-----------|------|-------------------|
| M0 — Scope locked | Canonical scope gate | Approved Phase 9 definition, owner, scope boundaries, exit gate, and explicit implementation/deployment authorization. |
| M1 — Baseline frozen | Regression gate | `9a9cf38` baseline recorded; Phase 8 evidence re-validated or explicitly accepted; `.opencode/opencode.jsonc` excluded. |
| M2A — Cutover ready (Candidate A) | Operational readiness gate | All six `infra/RUNBOOK.md:L242-L252` gates satisfied; no P0/P1; dead-letter zero; worker drain proof; pinned digests. |
| M2B — Inventory ready (Candidate B) | Product/spec gate | Canonical acceptance criteria, auth matrix, migration ownership, DTO/Rust/Kotlin parity scope, and test plan approved. |
| M3 — Rehearsal | Evidence gate | Staging/staged logs, smoke results, rollback drill, and independent review with no BLOCKER/MAJOR. |
| M4 — Go/No-Go | Authorization gate | Written approval naming exact window, services, and rollback path (production candidate only). |
| M5 — Completion | Phase exit gate | Phase-9-specific criteria approved at M0 all satisfied; stability window and Ktor retirement only if explicitly selected and approved. |

### 13.4 Provisional acceptance criteria

**Common Phase 8 FFI/bridge conditions (required if the selected scope relies on UniFFI):**

1. Half-and-half validation is ID-based and delegates distinct/overlap rules to `pizzora-core`; contract tests cover equal-price different-ID toppings.
2. FFI cart-validation issue codes use canonical `SCREAMING_SNAKE_CASE` strings from the shared contract, not Rust debug names.
3. `reorder_validate_items` matches server fallback behavior for malformed historical IDs and historical variant/crust reconstruction, emitting warnings instead of failing whole calls.
4. `DomainBridge`/`UniffiRustDomainBridge` wrap the new Phase 8 exports, bindings are regenerated, and bridge/Robolectric tests are attached.
5. FFI catalog `sort_order` is carried through and omitted-field defaults are explicit or fail-closed.
6. Ignored live-PG suites are executed at exact HEAD `9a9cf38`, or their omission is explicitly accepted as an unverified gate.
7. Attached logs carry command lines, timestamps, exit codes, and commit SHA; a PR/full-tier CI run or explicit local-equivalent evidence is attached.

**Candidate A — minimum measurable criteria before go/no-go:**

1. Written approval identifies project `bubbly-peace`, services `pizzabi` and `pizzabi-worker`, window, owners, and rollback image/digest.
2. Fresh production backup exists off-container and restore was verified in an isolated target.
3. Preflight is read-only and confirms Flyway V1–V3 integrity, `catalog_invariants_ok`, zero dead letters, zero workers, and known high-water sequence.
4. Pinned Ktor rollback image and approved Rust image digests are recorded.
5. Cutover smoke script is executed with intended authenticated and worker probes, or omissions are explicitly justified and separately verified; KDS WebSocket/checkout/owner paths are either added or separately evidenced.
6. `infra/RUNBOOK.md:L250,L280` is reconciled with the actual `1..11` migration profile, 51-table/25-index invariants, and `.railway/railway.ts:L27–30` preDeploy behavior.
7. Ktor rollback topology is confirmed for API and worker, and the SQLx-bootstrap-only rollback path is rehearsed.
8. Rollback triggers, order, and Ktor-retirement deferral are accepted in writing.
9. Zero unresolved P0/P1 defects, staging-suite sign-off, and explicit post-stability-window Ktor-retirement approval are recorded (`infra/RUNBOOK.md:L251,L318`).

**Candidate B — minimum measurable criteria before implementation:**

1. Canonical scope names modules, endpoints, schema, client scope, and non-goals.
2. Authorization tests cover OWNER/KITCHEN_STAFF/CUSTOMER boundaries.
3. Concurrency tests prove no oversell, no deadlock, and idempotent ledger operations under approved load profile.
4. Migration tests cover fresh DB, Flyway V1–V3 adoption, additive SQLx V4+ rules, rollback/reapply, and invariant checks.
5. Cross-language DTO/core parity is explicit for Kotlin and Rust if both remain active, including duplicate-ingredient semantics, error codes/details, audit payloads, and the `AdjustInventoryRequest.reason` default.
6. Rust PG18 tests mirror the Kotlin idempotency, oversell, lock-order, and FK assertions before Ktor retirement.
7. Extended-modifier stock demand is explicitly defined and implemented if inventory scope includes Phase 8 cheese/sauce/standard-topping/half-and-half modifiers.
8. Any Android/owner-console work has visual, accessibility, and regression gates under the canonical plan, including runtime captures; code/unit-only evidence is insufficient.

### 13.5 Out of scope for this assessment

- Phase 9 implementation of either candidate.
- Edits to `public/implementation_plan.md`, source, tests, schemas, migrations, contracts, CI, infrastructure, or docs beyond this assessment file.
- Executing Gradle/Cargo builds, tests, migrations, emulators, Railway apply, backup/restore, deploy, rollback, or production smoke.
- Committing, pushing, merging, tagging, releasing, or changing Git history/worktrees.
- Reopening Phases 1–8, modifying `.opencode/opencode.jsonc`, or staging the user’s local modification.

---

## 14. Risk register and blockers

| ID | Risk / issue | Severity | Status | Mitigation / condition |
|----|--------------|----------|--------|------------------------|
| B9-01 | No canonical Phase 9 definition or exit gate | **BLOCKING** | Open | W0 scope approval required before any implementation. |
| B9-02 | Conflicting Phase 9 meanings in archived assets and history | **BLOCKING** | Open | Stakeholder must select cutover vs inventory/recipes (or a new definition). |
| A9-03 | No written production cutover approval | High | Open for Candidate A | Do not touch Railway production services; approval must name window and services. |
| A9-04 | No verified production backup/restore proof in repository | High | Open for Candidate A | Run and attach backup/restore evidence before migration/deploy. |
| A9-05 | No pinned image digests or production env inventory evidence | High | Open for Candidate A | Record Ktor rollback and Rust release digests; verify variables/secrets without exposing values. |
| A9-06 | No dead-letter/worker-drain proof | High | Open for Candidate A | Execute read-only preflight gates from `infra/RUNBOOK.md:L262-L274`. |
| A9-07 | No production cutover CI/run artifact | Medium | Open for Candidate A | Execute and archive smoke evidence; keep authenticated/worker probes explicit. |
| A9-08 | Inventory/recipes scope undefined across Kotlin/Rust/Android | High | Open for Candidate B | Define migration ownership, parity scope, and UI scope before code changes. |
| A9-09 | Phase 8 regressions if Phase 9 changes pricing/modifiers/reorder | High | Conditional | Freeze `9a9cf38`; re-run accepted Phase 8 evidence before merge/deploy. |
| A9-10 | Historical Phase 4 gate wording conflict | Low | Open documentation debt | Reconcile separately; do not reopen Phase 4 or edit canonical plan here. |
| A9-11 | User-modified `.opencode/opencode.jsonc` could be accidentally staged | Medium | Controlled | Preserve and exclude from all future scoped commits; verified untouched here. |
| A9-12 | Reviewer tooling failures/partial coverage | Low–Medium | See Section 15 | Retried with read-only replacements; any unreconciled reviewer gap must be listed as incomplete. |
| A9-13 | Ktor rollback assets are not pinned to a digest | High | Open for Candidate A | Substitute an explicit `ghcr.io/...@sha256:` digest at execution time and record it in the cutover artifact. |
| A9-14 | Runbook says V1–V3 parity / `[1,2,3]`, migrator applies `1..11` | High | Open for Candidate A | Reconcile `infra/RUNBOOK.md:L250,L280` with `rust/crates/pizzora-db/src/migrator.rs:843,1120–1133` and `.railway/railway.ts:L27–30` before deploy. |
| A9-15 | Smoke script does not cover documented KDS WebSocket/checkout/owner paths | Medium–High | Open for Candidate A | Extend the script or attach separately executed evidence for each claimed smoke path. |
| A9-16 | Rust inventory/recipes lacks dedicated stress/idempotency/FK parity tests | High | Open for Candidate B | Port Kotlin assertions to Rust PG18 integration tests before Ktor retirement. |
| A9-17 | `AdjustInventoryRequest.reason` default differs Kotlin vs Rust | Medium | Open for Candidate B | Reconcile shared contract/Rust DTO default before cutover or legacy-client reliance. |
| A9-18 | Inventory/recipe behavioral parity gaps (duplicate ingredients, error codes/details, audit `before_json`) | High | Open for Candidate B | Define one authoritative behavior and add cross-language DTO/wire parity tests. |
| A9-19 | Railway Ktor rollback IaC omits a Ktor worker service | High | Open for Candidate A | Confirm pre-cutover worker topology and add an explicit rollback procedure/service if needed. |
| A9-20 | Worker-drain proof is heuristic | Medium–High | Open for Candidate A | Rehearse authoritative drain verification or document manual approval at the exact gate. |
| A9-21 | Fresh SQLx-only DB rollback to Ktor not rehearsed | Medium | Open for Candidate A | Rehearse `verifyMigrated = true` against a SQLx-bootstrap-only database before claiming universal rollback. |
| A9-22 | Open W4 UniFFI defect set: price-based half-and-half, debug-name issue codes, reorder fallback divergence | High | Open Phase 8 defect / acceptance decision | Record remediation or explicit waiver before Phase 9; fix in `pizzora-uniffi` and add contract tests against `pizzora-core` and shared contract codes. |
| A9-23 | Android bridge does not wrap the new Phase 8 FFI exports | High | Open W4 integration defect | Wire `DomainBridge`/`UniffiRustDomainBridge`, regenerate bindings, and add bridge/Robolectric tests; generated bindings in `qa-artifacts/phase8/uniffi/w4_bindgen.log:L159-L165` do not prove integration. |
| A9-24 | Latent FFI catalog ordering and fail-open defaults | Medium | Conditional if FFI catalog/quote path is used | Carry `sort_order`; make `is_veg`/`is_taxable`/`price_paise` defaults explicit or fail-closed. |
| A9-25 | Canonical plan still records 46 tables / 13 V3 indexes | Medium | Open documentation debt | Reconcile `public/implementation_plan.md:L911` with 51 tables / 25 total indexes in `migrator.rs` under an approved doc change. |
| A9-26 | Live-PG suites ignored in Clippy-fix verification log with no SHA provenance | High | Open verification gap | Execute all 21 ignored `pg18_*`/migrator tests at exact HEAD `9a9cf38` with timestamped logs, command, exit code, and SHA. |
| A9-27 | Phase 8 modifier demand not represented in inventory recipes | High | Open for Candidate B | Define and implement stock consumption for cheese/sauce/standard/half-and-half modifiers if inventory scope includes them. |
| A9-28 | No standalone P0/P1, staging sign-off, or retirement-stability approval risk | High | Open for Candidate A | Record zero unresolved P0/P1, staging-suite sign-off, and explicit post-stability Ktor-retirement approval (`infra/RUNBOOK.md:L251,L318`). |
| A9-29 | Feature-branch CI skips most validation | High | Open | Require a PR/full-tier CI run or explicit attached local-equivalent evidence for Phase 9; do not rely on ordinary feature-push results. |
| A9-30 | Clippy-fix logs lack command, timestamp, exit code, and commit SHA | Medium–High | Open evidence-quality debt | Require provenance in all attached logs; do not treat the W9 narrative re-run as independently reproducible evidence. |
| A9-31 | Native Rust bridge activation not proven in runtime logs | High | Conditional if FFI/cutover claims depend on it | Capture `isNativeRustActive`/`bridgeImplementationName` evidence or drop the native-runtime claim; test the real provider failure path. |
| A9-32 | Owner inventory/recipes not runtime-verified; debug/local-only runtime QA and screenshot/focus caveats | Medium–High | Candidate B / visual QA | Add owner runtime captures and release-runtime smoke; record focus/screenshot limitations explicitly. |
| A9-33 | Progress report says 84 backend tests vs verified 86 | Low | Open documentation debt | Reconcile `docs/PHASE8_IMPLEMENTATION_PROGRESS_REPORT.md:L93` under an approved doc change. |

Blocking implementation-start issue: **B9-01 and B9-02**. All other items are candidate-specific conditions, risks, or documentation debt.

---

## 15. Independent reviewers, approvals, final verdict, and incomplete checks

### 15.1 Independent reviewer results

| Reviewer | Session | Result summary |
|----------|---------|----------------|
| Architecture | `ses_de23d0c4dffds9fazxwDl7Wfmn` | Canonical Phase 9 **BLOCKED** on scope. No new module abstraction required for either candidate; Rust/Kotlin/Android boundaries are clean and cutover primitives are reusable. Conditional architecture verdict: **CONDITIONS / not ready to execute** due to governance and operational hardening, not structural boundaries. |
| Backend/data | Initial `ses_de23d0c4cffevkSLwr2TGHWffr` failed twice with `DEGRADED function cannot be invoked`; replacement general reviewer `ses_de23c3647fferDrDWvd3bIZ2Av` | Canonical Phase 9 absent. Inventory/recipes are Ktor implementation/test-complete, while Rust implementation is present but parity-unverified (no equivalent Rust stress tests and a `reason` default mismatch). Cutover artifacts are substantially in place but **No-Go as written**: scope/approval, digest pinning, runbook/migrator version contradiction, smoke coverage, backup/drain/backlog, and retirement sign-off remain open. |
| Rust core | `ses_de23d0c4bffeEKbWDQc3d2MII7` | Canonical Phase 9 **BLOCKED / NO-GO**. Phase 8 Rust core/pricing is a sound closed baseline, but the review found confirmed FFI defects and bridge gaps not exercised by the server-pricing Android path: price-based half-and-half validation, debug-name issue codes, reorder fallback divergence, Android bridge not wiring new exports, plus latent catalog ordering/default fail-open issues. Cutover runbook/migrator profile contradiction also confirmed. |
| Document consistency (Big Pickle) | `ses_de2327898ffeffAusZj1Hq0Sz7` | Read-only consistency review using `opencode/big-pickle`. No domain verdict; it identified and caused correction of non-final placeholders, the unsupported exact-HEAD live-PG claim, ID collisions, missing FFI/latent-catalog risks, the stale `docs/` search statement, and the overstated runbook smoke citation. |
| Android | Original `ses_de23d0c45ffewBqjAjwg1FaB42` timed out; Big Pickle retry `ses_de22a5258ffelMJ5e0vjDSROtz` | Big Pickle result: **BLOCKED** for cutover readiness. Customer runtime QA is real but debug/local-only; native Rust bridge activation is not proven in logs; six new Phase 8 UniFFI exports are generated but not wired into Android; owner inventory/recipes are code/unit-ready but not runtime-verified. |
| QA/test | Original `ses_de23d0c44ffeln9m6NXK2Pa8E7` timed out; Big Pickle retry `ses_de22a6a44ffeC5PnzSZRqdv857` | Big Pickle result: **BLOCKER** that no Phase 9 QA gate can be frozen before scope approval; **MAJOR** gaps are no live-PG verification at final HEAD, undated Clippy evidence, weak feature-branch CI, missing cutover smoke/backup/digest evidence, no Rust inventory parity tests, and conditional FFI test coverage. XML counts were independently recomputed as 230/86/40 with zero failures/errors/skips. |
| Independent critical | Initial `ses_de23d0c43ffef47jwn64ZkNx6r` failed with `DEGRADED`, same-session retry timed out; Big Pickle retry `ses_de229e81fffe6pSMT5PBzWmSIK` | Big Pickle recommendation: keep **BLOCKED**. Required corrections were applied: W4 UniFFI defects recorded as open Phase 8 defects, working-tree statement time-qualified, staging/retirement-signoff risks added, and exact-HEAD claims for undated Clippy logs removed. |

All reviewers were explicitly instructed to be read-only: no file edits, no created files, no builds/tests, and no Git changes.

### 15.2 Required approvals before any Phase 9 implementation

1. Written selection of the canonical Phase 9 scope and rejection/acceptance of the other candidate meanings.
2. Written baseline approval at `9a9cf3810d212c44977ed7d39cb5feb0b19862f1`, with explicit exclusion of `.opencode/opencode.jsonc`.
3. Written authorization for any commit/push; none was made here.
4. For Candidate A: written production cutover approval naming exact window, Railway project/services, image digests, rollback path, and stability window.
5. For Candidate B: written approval of module scope, schema ownership, DTO/Rust/Kotlin parity, owner/client scope, and exit gate.
6. Confirmation that Phases 6/7 remain closed, that Phase 8 server-backed closure is accepted, and that the open W4 UniFFI defect set is explicitly waived or scheduled for remediation rather than silently treated as closed.
7. Explicit authorization before any canonical plan edit; this assessment did not edit `public/implementation_plan.md`.

### 15.3 Final verdict

**BLOCKED**

Rationale: Phase 9 has no canonical scope in `public/implementation_plan.md`; the repository contains two conflicting non-canonical Phase 9 definitions; the canonical phase exit gate stops at Phase 8; and no Phase 9-specific approvals or gates exist. Phase 8 closure evidence is strong for the server-backed path, but the W4 UniFFI workstream has an open defect set and the evidence is not proven at exact HEAD. In any case, Phase 8 closure does not close Phase 9.

Conditions to convert `BLOCKED` into `READY WITH CONDITIONS` or `READY`:

1. Complete W0 scope authority and define a Phase 9-specific exit gate.
2. For cutover, satisfy all six pre-cutover gates in `infra/RUNBOOK.md:L242-L252`, reconcile the migrator/runbook contradiction, pin digests, record zero P0/P1 and staging/retirement sign-off, and attach executed evidence.
3. For inventory/recipes, define canonical acceptance criteria and cross-module ownership, close Kotlin/Rust behavioral parity gaps, and produce executed Phase 9 evidence plus Android owner runtime captures if that UI is in scope.
4. Record a remediation-or-waiver decision for the open W4 UniFFI defect set (A9-22–A9-24); if the selected scope depends on UniFFI, fix the defects and attach UniFFI plus Android bridge contract tests.
5. Execute the ignored live-PG suites at exact HEAD `9a9cf38` with provenance-bearing logs, or explicitly accept them as unverified.
6. Require full-tier CI or equivalent attached evidence, and prove native bridge activation where FFI/runtime claims depend on it (A9-29–A9-31).
7. Re-run independent critical review after the above evidence is produced with no unresolved BLOCKER/MAJOR; the current Big Pickle critical review confirms `BLOCKED` and its corrections are applied.
8. Obtain explicit written authorization for the exact implementation/deployment boundary.

### 15.4 Incomplete checks and honesty statement

- No Gradle, Cargo, database, emulator, staging, Railway, backup/restore, or production command was executed by this assessment. All cited passing tests are inspected evidence, not fresh execution.
- No live production state, Railway variables, secrets, image digests, backup artifacts, dead-letter backlog, or worker sessions were accessed.
- No visual QA screenshots were captured in this assessment; Phase 8 visual evidence was inspected only.
- The Clippy-fix verification workspace log marks all 21 live-PG suites ignored and contains no commit SHA; the latest live-PG log predates `9a9cf38`. No exact-HEAD live-PG evidence was executed or found. UniFFI contract tests and Android bridge tests for the new Phase 8 exports were not executed or found.
- Native Rust bridge activation, owner inventory/recipe runtime screens, release-runtime smoke, cutover smoke, backup/restore proof, pinned digests, worker drain, and dead-letter backlog were not executed or verified in this assessment.
- All reviewer results in Section 15.1 have been consolidated. Tooling failures and Big Pickle retries are recorded; reviewer differences are candidate-specific (No-Go for cutover, Not Ready for inventory/recipes) and do not change the single canonical `BLOCKED` verdict.
- No implementation occurred. No source, test, schema, migration, contract, CI, canonical plan, Git history, branch, ref, worktree, or production system was modified by this assessment.
- The only working-tree change produced by this assessment is untracked `docs/PHASE9_READINESS_ASSESSMENT.md`; `M .opencode/opencode.jsonc` predates the assessment and was preserved untouched.

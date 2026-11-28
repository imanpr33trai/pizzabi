# PIZZORA Phase 9 — Scope Decision

**Date:** 2026-11-27
**Branch:** `ui-ux-changes`
**Verified HEAD:** `9a9cf3810d212c44977ed7d39cb5feb0b19862f1`
**Phase 8 checkpoint:** `5c21fa4` (present and an ancestor of HEAD; HEAD is one commit later)
**Decision type:** Read-only scope decision. No Phase 9 implementation, canonical-plan edit, source/test/schema/migration/DTO/API/CI change, commit, push, merge, deployment, migration execution, backup/restore, or production/staging access was performed.

**Final recommendation:** `INSUFFICIENT EVIDENCE — PRODUCT DECISION REQUIRED`

---

## 1. Executive summary

`public/implementation_plan.md` is the sole canonical scope authority. It is explicitly a planning/specification document (`public/implementation_plan.md:L1-L5`), it ends at Phase 8 — “Extended Pizza Modifiers & Reorder” (`public/implementation_plan.md:L903-L922`) — and its mandatory exit gate applies only to Phases 1–8 (`public/implementation_plan.md:L761-L766`). **It contains no Phase 9 objective, scope, acceptance criteria, exit gate, or approval.** The prior readiness assessment correctly recorded the resulting verdict as `BLOCKED` (`docs/PHASE9_READINESS_ASSESSMENT.md:L402-L406`).

Two conflicting non-canonical Phase 9 definitions exist:

- **Candidate A — production cutover / Ktor retirement:** archived Rust-migration plan (`public/assets/aistudio/implementation_plan_12__a6986755.md:L524-L553`) and operational runbook (`infra/RUNBOOK.md:L238-L319`).
- **Candidate B — ingredient inventory / relational recipes:** older Kotlin-stack plan (`public/assets/aistudio/implementation_plan_6__ab381b99.md:L507-L516`), with a substantial Kotlin implementation, shared DTOs, Rust implementation, Android owner UI, and Phase 9-named Kotlin tests already in the repository.

The evidence supports three firm conclusions:

1. **Candidate A is No-Go as written.** The implementation artifacts (Railway IaC, rollback compose/IaC, backup/restore scripts, smoke script, preflight/migrator code, monitoring) are substantially present, but the mandatory operational gates are not: no written cutover approval, no backup/restore proof, no pinned digests, no runbook/migrator reconciliation, incomplete smoke coverage, no dead-letter/worker-drain proof, no zero-P0/P1/staging sign-off, no Ktor rollback worker topology, and no retirement approval (`infra/RUNBOOK.md:L240-L252,L254-L319`; `docs/PHASE9_READINESS_ASSESSMENT.md:L142-L153`).
2. **Candidate B is Kotlin-ready but not cross-stack-ready.** The Kotlin services, shared contracts, four live-PostgreSQL stress/idempotency tests, Rust DB/API implementation, and Android owner code/unit tests exist. But Rust has no dedicated inventory/recipe parity or stress tests, the Rust `AdjustInventoryRequest.reason` is required while Kotlin defaults it, duplicate-recipe semantics diverge, Phase 8 modifier demand is absent from recipe depletion, and owner inventory/recipes lack runtime captures (`shared/contracts/src/main/kotlin/com/pizzora/contracts/InventoryDto.kt:L38-L43`; `rust/crates/pizzora-db/src/inventory.rs:L130-L136`; `backend/src/main/kotlin/com/pizzora/backend/recipes/RecipeService.kt:L50-L51`; `rust/crates/pizzora-api/src/services/recipes.rs:L77-L86,L209-L257`).
3. **Repository evidence cannot establish product priority between A and B.** A’s value is platform simplification and operational-risk reduction; B’s value is owner stock/recipe capability and checkout accuracy. Neither value is quantified, prioritized, or approved in the repository. Choosing either as Phase 9 would invent a business decision.

The least-risk conditional path, if the product owner wants both, is: **shared remediation and Phase 8 evidence closure first; then Candidate B implemented Rust-first; then Candidate A as a separate, later cutover phase after every operational gate is satisfied.** That is a sequencing recommendation, not the final scope decision, because the product priority and the authority to expand the canonical plan are still missing.

### Decision at a glance

| Question | Answer | Confidence |
|---|---|---|
| Is there canonical Phase 9 scope? | **No** — plan ends at Phase 8 | High |
| Is Candidate A ready to execute? | **No** — No-Go as written | High |
| Is Candidate B ready to execute? | **Kotlin yes; Rust/Android no** | High on facts; medium on effort |
| Can repository evidence choose A vs B? | **No** — product priority absent | High |
| Is shared remediation a standalone Phase 9? | **No** — necessary prerequisite, not a scope | High |
| Least-risk sequence if both are approved | Shared remediation → B Rust-first → A separate | Medium |
| Final recommendation | `INSUFFICIENT EVIDENCE — PRODUCT DECISION REQUIRED` | High |

---

## 2. Task scope, constraints, and non-goals

### 2.1 In scope

- Verify the repository baseline, branch, HEAD, checkpoint, sync state, worktrees, and existing Phase 9 assessment.
- Read the canonical plan, Phase 8 closure/contract evidence, cutover runbook/scripts, archived Phase 9 definitions, inventory/recipe code and tests, Android integration, CI, and QA evidence.
- Compare Candidate A and Candidate B on product value, technical value, readiness, dependencies, risk, effort, testability, operational impact, reversibility, and strategic fit.
- Analyse five sequencing options: cutover first, inventory first, shared remediation first, separate phases, and an evidence-supported alternative.
- Classify shared prerequisites as shared blocker, candidate-specific blocker, accepted limitation, optional improvement, or unverified.
- Obtain independent read-only reviews and record their verdicts.
- Create exactly one new document: this file.

### 2.2 Out of scope / prohibited

- No implementation of either candidate.
- No edit to `public/implementation_plan.md`, `docs/PHASE9_READINESS_ASSESSMENT.md`, source, tests, schemas, migrations, DTOs, APIs, CI, or infrastructure.
- No Gradle/Cargo build, test execution, migration, emulator, backup/restore, Railway apply, deployment, rollback, or production smoke.
- No secret access, no production/staging state change.
- No commit, push, merge, tag, release, destructive Git operation, or worktree change.
- No invented business priorities, deadlines, customer requirements, revenue estimates, or approvals.
- The pre-existing ` M .opencode/opencode.jsonc` must remain untouched, unstaged, and undiscarded.

### 2.3 Evidence labels

- `[VERIFIED]` — inspected directly at the verified HEAD.
- `[PRIOR EVIDENCE]` — a committed or working-tree artifact inspected but not re-executed here.
- `[ASSUMPTION]` — an explicit estimate or inference, not a repository fact.
- `[UNVERIFIED]` — a claim or gate for which no executed evidence was found.
- `[PRODUCT INPUT REQUIRED]` — a business decision the repository cannot supply.

---

## 3. Baseline verification

| Check | Result | Evidence |
|---|---|---|
| Repository root | `/workspace/pizzabi` | `pwd`, Git worktree list |
| Branch | `ui-ux-changes` | `git rev-parse --abbrev-ref HEAD` |
| HEAD | `9a9cf3810d212c44977ed7d39cb5feb0b19862f1` | `git rev-parse HEAD` |
| Remote sync | `0 0` ahead/behind `origin/ui-ux-changes` | `git rev-list --left-right --count` |
| Phase 8 checkpoint | `5c21fa4` present and ancestor; HEAD is one commit later (`9a9cf38 chore(phase8): resolve clippy warnings`) | `git merge-base --is-ancestor`, `git rev-list --count` |
| Working tree | ` M .opencode/opencode.jsonc` (pre-existing) and `?? docs/PHASE9_READINESS_ASSESSMENT.md` (prior assessment) | `git status --short` |
| Decision document | Absent before this task; created only here | `ls docs/PHASE9_SCOPE_DECISION.md` |
| Worktrees | Main workspace; `steel-bell` at `415c17b` on `main`; `vivid-gate` at `6544440` on `mattpocock`; one prunable detached OpenCode worktree at `4752c67` | `git worktree list --porcelain` |
| Pre-existing config | `M .opencode/opencode.jsonc` predates this task and was not read into, staged, or changed by it | `git diff -- .opencode/opencode.jsonc` inspected only for preservation |

No source, test, schema, migration, contract, CI, canonical plan, Git history, branch, ref, worktree, or production system was modified.

---

## 4. Evidence hierarchy and source inventory

The decision uses the following hierarchy. Higher tiers constrain lower tiers; a lower-tier document cannot create Phase 9 scope.

| Tier | Source | Role in this decision | Status |
|---|---|---|---|
| 1 | `public/implementation_plan.md` | Sole canonical scope authority; Phase 8 endpoint and exit gate | `[VERIFIED]` |
| 2 | `docs/PHASE8_REVIEWED_CONTRACT_RESOLUTIONS.md`, `docs/PHASE8_BACKEND_CONTRACT.md`, `docs/PHASE8_IMPLEMENTATION_PROGRESS_REPORT.md`, `qa-artifacts/phase8/review/W9_FINAL_INDEPENDENT_REVIEW.md` | Phase 8 binding contract and closure evidence; regression baseline for both candidates | `[PRIOR EVIDENCE]` |
| 3 | `infra/RUNBOOK.md`, `.railway/*.ts`, `infra/scripts/*`, `.github/workflows/*` | Candidate A operational definition and CI evidence | `[VERIFIED]` / `[PRIOR EVIDENCE]` |
| 3 | `public/assets/aistudio/implementation_plan_12__a6986755.md`, `public/assets/aistudio/implementation_plan_6__ab381b99.md` | Archived, non-canonical Candidate A/B definitions | `[VERIFIED]` (non-canonical) |
| 3 | Kotlin/Rust/Android inventory, recipe, cutover, and FFI code | Implementation facts and parity gaps | `[VERIFIED]` |
| 3 | `qa-artifacts/phase8/**` | Executed test/QA evidence, inspected but not re-executed | `[PRIOR EVIDENCE]` |
| 4 | `docs/PHASE9_READINESS_ASSESSMENT.md` | Prior independent readiness verdict (`BLOCKED`) | `[PRIOR EVIDENCE]` |
| 5 | This document’s effort/risk ratings | Planning estimates, explicitly labelled | `[ASSUMPTION]` |

Key searches performed: all `Phase 9` / `phase9` references outside build/target directories; all inventory/recipe references across Kotlin, Rust, shared contracts, Android, tests, CI, and QA; all cutover/Ktor-retirement references in infra and archived plans. The only Phase 9 definitions found were the two archived plans, the runbook, code comments, and Phase 9-named inventory tests — none canonical.

---

## 5. Canonical Phase 9 scope status

### 5.1 The canonical plan stops at Phase 8

- The document is V2 and “Planning & Specification Only — Zero Source Code Modified” (`public/implementation_plan.md:L1-L5`).
- The implementation-phase section ends with Phase 8 (`public/implementation_plan.md:L903-L922`), followed immediately by Section 13, “Testing, Visual QA & Acceptance Criteria” (`public/implementation_plan.md:L926`).
- The mandatory exit gate says “Applies to Every Phase 1–8” and lists four criteria (`public/implementation_plan.md:L761-L766`).
- There is no Phase 9 heading, objective, screen list, backend dependency, acceptance criterion, test requirement, rollback strategy, or approval.

### 5.2 Consequence

No candidate is authorized by the canonical plan. A Phase 9 scope decision therefore requires an explicit plan amendment or an equally authoritative approved artifact, plus a Phase 9-specific exit gate. This is a governance blocker (`B9-01`) and a scope-conflict blocker (`B9-02`) in the prior assessment (`docs/PHASE9_READINESS_ASSESSMENT.md:L338-L339`).

### 5.3 Non-canonical references do not create scope

The runbook, archived plans, code comments, and test class names are implementation/planning history. They can inform a decision, but they cannot substitute for canonical approval. In particular:

- `infra/RUNBOOK.md:L238` labels a Phase 9 cutover section, but the runbook is an operational document, not the canonical plan.
- `public/assets/aistudio/implementation_plan_12__a6986755.md:L524` and `public/assets/aistudio/implementation_plan_6__ab381b99.md:L507` are archived assets, not the current plan.
- `backend/src/test/kotlin/com/pizzora/backend/Phase9InventoryAndRecipesTest.kt` and `rust/crates/pizzora-db/src/migrator.rs:L395` use “Phase 9” names/comments, but naming is not scope authority.

---

## 6. Candidate A — production cutover / Ktor retirement

### 6.1 Definition

Candidate A switches production from the Ktor API/worker to the Rust API/worker, keeps Ktor rollback assets warm through a stability window, and retires Ktor only after explicit approval. The archived definition is `public/assets/aistudio/implementation_plan_12__a6986755.md:L524-L553`; the operational definition is `infra/RUNBOOK.md:L238-L319`.

The mandatory gates are:

1. Written change-window approval naming project `bubbly-peace` and services `pizzabi` / `pizzabi-worker`.
2. Fresh production `pg_dump` stored off-container and a verified restore.
3. Pinned Ktor rollback digest and approved Rust release digest; production variable inventory.
4. Schema compatibility (the runbook says V1–V3 parity; the actual migrator profile is V1–V11).
5. Zero unresolved P0/P1, staging suites green, zero unacknowledged dead letters.
6. Worker-drain proof before starting Rust workers.

(`infra/RUNBOOK.md:L242-L252`.)

### 6.2 Artifacts that are present

| Artifact | Evidence | Assessment |
|---|---|---|
| Railway production IaC for Rust API/worker | `.railway/railway.ts:L19-L70` | `[VERIFIED]` present; scoped to `pizzabi`/`pizzabi-worker` |
| Ktor rollback Compose stack (API + worker) | `infra/docker-compose.ktor-rollback.yml:L1-L122` | `[VERIFIED]` present |
| Ktor rollback Railway IaC | `.railway/railway.ktor-rollback.ts:L10-L23` | `[VERIFIED]` present, **API only** — no Ktor worker service |
| Backup/restore scripts | `infra/scripts/backup-postgres.sh`, `restore-postgres.sh`, `verify-backup-restore.sh` | `[VERIFIED]` present; no executed production proof |
| Cutover smoke script | `infra/scripts/verify-production-cutover.sh:L1-L162` | `[VERIFIED]` present; authenticated and worker probes are optional |
| Read-only preflight/migrator | `rust/crates/pizzora-db/src/migrator.rs:L395`, `--preflight-only` / `--migrate-only` | `[VERIFIED]` present; Phase 8 CLI evidence exists |
| Monitoring/alerts | `infra/monitoring/grafana-dashboard.json`, `alerts.yml` | `[VERIFIED]` present; no post-cutover SLO evidence |
| Rust image/publish workflow | `infra/Dockerfile.rust`, `.github/workflows/publish-rust-backend.yml` | `[VERIFIED]` present; publication is not deployment |

### 6.3 Blocking gaps

**A9-A. No written cutover approval.** No repository artifact names an approved window, owners, project, services, or rollback path. The runbook explicitly forbids action without it (`infra/RUNBOOK.md:L240`).

**A9-B. No backup/restore proof.** The scripts exist, but no executed production `pg_dump`, off-host checksum, isolated restore, or row/cursor parity evidence is attached. The runbook requires it (`infra/RUNBOOK.md:L245`; `docs/PHASE9_READINESS_ASSESSMENT.md:L143`).

**A9-C. No pinned digests.** The rollback IaC builds from `infra/Dockerfile.backend` at apply time; Compose defaults to `:latest`; the runbook requires `@sha256:` digests (`infra/RUNBOOK.md:L247-L248,L255,L311`; `docs/PHASE9_READINESS_ASSESSMENT.md:L144`). This is not deterministic rollback.

**A9-D. Runbook/migrator contradiction.** The runbook says production remains V1–V3 and expects `applied_or_verified_versions: [1, 2, 3]` (`infra/RUNBOOK.md:L250,L280`), while the Phase 8 migrator applies versions 1–11 and verifies 51 tables/25 indexes (`rust/crates/pizzora-db/src/migrator.rs:L843,L918-L929`). A cutover operator following the runbook would misread a correct Phase 8 database as a failure. This must be reconciled before execution.

**A9-E. Incomplete smoke coverage.** The runbook claims KDS WebSocket sequence progression, checkout, and owner paths (`infra/RUNBOOK.md:L291`), but the script only performs readiness/catalog/OpenAPI/security-header/auth-envelope checks and optional read-only authenticated calls; it does not execute checkout, KDS WebSocket progression, or owner mutations (`infra/scripts/verify-production-cutover.sh:L59-L160`). The script also accepts `DEGRADED` readiness as long as PostgreSQL is UP (`verify-production-cutover.sh:L44-L57`), which can mask Redis/worker degradation. No executed cutover smoke log was found, and the omissions are not separately evidenced. More seriously, the script's authenticated block parses `accessToken` / `refreshToken` (`verify-production-cutover.sh:L120-L121,L129-L132`), while the Rust contract emits `access_token` / `refresh_token` (`rust/crates/pizzora-auth/src/service.rs:L74-L80`) and the shared Kotlin contract agrees (`shared/contracts/src/main/kotlin/com/pizzora/contracts/AuthDto.kt:L70-L75`). The authenticated smoke path would fail on a missing key before it could certify `/me`, cart, notifications, orders, ws-ticket, refresh, or logout. A separate assertion in the same script requires top-level `code` and `message` on an error body (`verify-production-cutover.sh:L79-L84`), but both backends emit `{error:{code,message},correlation_id,timestamp}` (`shared/contracts/src/main/kotlin/com/pizzora/contracts/ApiEnvelope.kt:L6-L18`). The unauthenticated-negative smoke check therefore fails on a valid envelope too.

**A9-K. Railway observability is not provisioned and `/metrics` may be public.** The Railway IaC defines only API and worker services (`.railway/railway.ts:L19-L70`); there is no Caddy/proxy, Prometheus, Alertmanager, or dashboard deployment. The Rust router registers `/metrics` publicly (`rust/crates/pizzora-api/src/router.rs:L58`; `routes/ops.rs:L26-L37`), and blocking exists only in the Compose Caddy config (`infra/caddy/Caddyfile:L23-L27`), while Prometheus scrapes Compose-only targets `backend:8080` / `worker:8081` (`infra/monitoring/prometheus.yml:L8-L21`). The incident-response gates in the runbook (`infra/RUNBOOK.md:L47-L57,L292-L299`) are therefore unverified on Railway.

**A9-L. No pinned, matching staging artifact.** Compose production defaults to mutable `:latest` (`infra/docker-compose.prod.yml:L71,L87,L123`), while the runbook requires pinned digests. The reusable Railway staging template builds from `main` (`.railway/railway.template.ts:L86,L107`), which currently points at `415c17b` — a commit that predates the Phase 8 V4–V11 migrations. A template-based staging rehearsal would not validate this candidate.

**A9-M. Backup evidence is synthetic and off-host upload is warn-only.** `backup-postgres.sh` skips remote upload with a warning when the `aws` CLI is absent (`infra/scripts/backup-postgres.sh:L46-L53`), and the restore drill uses V1–V3 plus synthetic rows (`infra/scripts/verify-backup-restore.sh:L54-L70,L91-L100`), not a real production dump with V4–V11 state. Railway IaC defines no backup service.

**A9-N. Auth bootstrap/SMTP hazards.** The Rust API auto-bootstraps an owner whenever `BOOTSTRAP_OWNER_*` is present and overwrites password/role (`rust/crates/pizzora-api/src/main.rs:L122-L146`; `rust/crates/pizzora-auth/src/bootstrap.rs:L79-L98`), despite the runbook requiring these never be persistent (`infra/RUNBOOK.md:L179`). Production config does not require SMTP values (`rust/crates/pizzora-config/src/lib.rs:L121-L145`) although the runbook calls them critical (`infra/RUNBOOK.md:L174-L176`).

**A9-O. Per-route parity is not certified and responsibility transfer is unmapped.** Rust implements the broad route surface including Phase 8 reorder (`rust/crates/pizzora-api/src/router.rs:L53-L303`) and CI has golden-fixture parity gates, but the archived cutover plan requires per-route Ktor-vs-Axum inventories/goldens (`public/assets/aistudio/implementation_plan_12__a6986755.md:L311-L320`); none were found. Ktor retirement would also remove the Kotlin fixture generator that CI still uses, and no sign-off maps every Ktor responsibility (routes, Flyway V1–V3, standalone workers) to a Rust owner.

**A9-F. No dead-letter/worker-drain proof.** The preflight supports `--require-zero-dead-letters` and `--require-zero-workers`, but no production run is attached. The drain check is heuristic (`docs/PHASE9_READINESS_ASSESSMENT.md:L357`).

**A9-G. No zero-P0/P1 or staging sign-off.** The runbook requires both (`infra/RUNBOOK.md:L251`); no artifact records either.

**A9-H. Ktor rollback topology is incomplete for Railway.** Compose restores API and worker (`infra/docker-compose.ktor-rollback.yml:L104-L122`), but Railway rollback restores only `pizzabi` (` .railway/railway.ktor-rollback.ts:L10-L23`). If the production worker is a separate Ktor service, the rollback procedure is not equivalent across topologies (`docs/PHASE9_READINESS_ASSESSMENT.md:L195,L356`).

**A9-I. No retirement approval or stability-window evidence.** Ktor retirement requires clean SLOs, an agreed stability window, and explicit written sign-off (`infra/RUNBOOK.md:L318`); none exists.

**A9-J. Client migration is build-time only.** The Android release API base URL defaults to `https://api.pizzora.app` and is overridable only at build time via `PIZZORA_API_BASE_URL` (`app/build.gradle.kts:L56-L62`); the debug URL is likewise build-time (`L29-L34`). Cutover must preserve that public hostname and its routes or ship a new APK. No executed client-switch or rollback evidence is attached.

### 6.4 Candidate A judgment

**NOT READY / No-Go as written.** The code and operational scaffolding are substantially present, but Candidate A is an authorization-and-evidence problem, not a coding problem. It cannot be recommended until the gates above are satisfied and recorded. Confidence: **high** (direct runbook/script/IaC inspection and the prior assessment agree).

---

## 7. Candidate B — ingredient inventory / relational recipes

### 7.1 Definition

Candidate B implements ingredient stock (`on_hand_qty`, `reserved_qty`, generated `available_qty`), relational recipes (`variant_recipes`, `crust_recipes`, `topping_recipes`), atomic reserve/commit/release/restore, an idempotent `inventory_ledger`, and owner APIs/UI. The archived definition is `public/assets/aistudio/implementation_plan_6__ab381b99.md:L507-L516`.

Acceptance criteria in that definition:

- Concurrent reservations never produce `reserved_qty > on_hand_qty` or deadlocks.
- Duplicate `operation_id` never creates duplicate ledger rows.
- Deleting a variant/crust/topping cascades cleanly without dangling recipes.
- Multi-thread oversell, deadlock, idempotent replay, and FK cascade/restrict tests pass.
- A Margherita recipe across variant + crust + topping records every stock delta.

### 7.2 Artifacts that are present

| Layer | Evidence | Assessment |
|---|---|---|
| Schema | `ingredients`, `inventory`, `variant_recipes`, `crust_recipes`, `topping_recipes`, `inventory_reservations`, `inventory_ledger` in `V1__core_schema.sql:L177-L217,L364-L390` and the SQLx mirror `0001_core_schema.sql` | `[VERIFIED]` core schema already exists; no new migration is needed for the base feature |
| Kotlin services/routes | `backend/src/main/kotlin/com/pizzora/backend/inventory/InventoryService.kt` (775 lines), `InventoryRoutes.kt`; `recipes/RecipeService.kt` (313 lines), `RecipeRoutes.kt` | `[VERIFIED]` implemented |
| Kotlin tests | `Phase9InventoryAndRecipesTest.kt`: owner CRUD/RBAC/ledger/recipe/FK; 20-thread oversell; 16-thread × 3-round deadlock stress; reserve/commit/cancel/expire lifecycle (`L128-L129,L430-L431,L540-L541,L656-L657`) | `[PRIOR EVIDENCE]` 4 tests, 0 failures/skips (`qa-artifacts/phase8/unit-test-xml/backend/TEST-com.pizzora.backend.Phase9InventoryAndRecipesTest.xml:L2`) |
| Shared DTOs | `shared/contracts/.../InventoryDto.kt:L7-L94` | `[VERIFIED]` present |
| Rust DB/API | `rust/crates/pizzora-db/src/inventory.rs` (1,067 lines); `rust/crates/pizzora-api/src/services/recipes.rs` (372 lines); routes at `router.rs:L276-L292`; owner handlers in `owner_ops.rs` | `[VERIFIED]` implemented, including sorted row locking (`inventory.rs:L560,L569-L580,L884-L919`) and idempotent ledger (`L950+`) |
| Android owner UI | `OwnerCatalogScreen.kt` (recipes, `L249-L304,L658-L692`); `OwnerViewModel.kt` (save/adjust, `L438-L470,L512+`); `OwnerOperationsScreen.kt`; API client `PizzoraApiClient.kt:L507+` | `[VERIFIED]` code present |
| Android unit tests | `Phase14OwnerRobolectricTest.kt` (restock, recipe edit); `Phase19EndToEndRobolectricTest.kt` | `[PRIOR EVIDENCE]` code/unit coverage only |

Positive parity evidence also exists: owner route paths and permission strings match across Ktor and Rust (`owner:inventory_manage`, `owner:recipes_manage`; `backend/src/main/kotlin/com/pizzora/backend/inventory/InventoryRoutes.kt:L31-L55`; `backend/src/main/kotlin/com/pizzora/backend/recipes/RecipeRoutes.kt:L27-L38`; `rust/crates/pizzora-api/src/routes/owner_ops.rs:L253-L350`), and error-envelope wire casing is compatible. Rust also wires inventory into checkout, payment capture, cancellation, and expiry (`rust/crates/pizzora-api/src/services/checkout.rs:L416-L428`; `rust/crates/pizzora-payments/src/capture.rs:L249-L250`; `rust/crates/pizzora-db/src/orders.rs:L653-L666`; `rust/crates/pizzora-workers/src/expiry_sweep.rs:L153-L154`). The gap is executed Rust evidence, not total absence of integration.

### 7.3 Blocking gaps

**B9-A. No canonical scope or exit gate.** The archived plan is not canonical; no approved Phase 9 inventory/recipe objective, non-goals, authorization matrix, schema ownership, or exit criteria exist.

**B9-B. Rust has no dedicated inventory/recipe parity or stress tests.** The implementation exists, but the Kotlin oversell/deadlock/idempotency/FK assertions were not ported to Rust PG18 tests. Rust API fixtures seed inventory/recipes (`rust/crates/pizzora-api/tests/common/mod.rs:L534-L677`), but they do not assert those properties; only Kotlin has the stress suite. The Rust call sites (checkout, payment capture, cancel, expiry) exist but are unverified under concurrency/idempotency/FK scenarios. This is the highest-risk B gap because the Rust path is the cutover target.

**B9-C. `AdjustInventoryRequest.reason` diverges.** Kotlin/shared defaults it to `MANUAL_ADJUST` (`shared/contracts/.../InventoryDto.kt:L38-L43`); Rust requires it with no serde default (`rust/crates/pizzora-db/src/inventory.rs:L130-L136`). A legacy client that omits `reason` succeeds on Ktor and fails on Rust.

**B9-D. Duplicate-recipe semantics diverge.** Kotlin rejects duplicate ingredient IDs with `DUPLICATE_RECIPE_INGREDIENT` (`backend/.../RecipeService.kt:L50-L51`); Rust merges duplicates by summing quantities (`rust/crates/pizzora-api/src/services/recipes.rs:L77-L86`). The same request produces different data on the two backends.

**B9-E. Phase 8 modifier demand is absent.** Recipe depletion covers variants, crusts, and toppings only (`rust/crates/pizzora-api/src/services/recipes.rs:L218-L253`); cheeses, sauces, standard toppings, and half-and-half are not represented. If B’s scope includes Phase 8 modifiers, new recipe tables/columns and checkout demand logic are required. If it excludes them, that exclusion must be explicit.

**B9-F. Error/audit parity is unverified.** Kotlin and Rust use different error codes/details and audit payload construction in several places; no cross-language wire/DTO parity suite covers inventory/recipes. One concrete wire divergence: Kotlin `INSUFFICIENT_INVENTORY` includes `ingredient_id`, `ingredient_name`, `required`, and `available` in `details` (`backend/src/main/kotlin/com/pizzora/backend/inventory/InventoryService.kt:L367-L377`), while the Rust inventory path defines the code without attaching details (`rust/crates/pizzora-db/src/inventory.rs:L598`; the envelope supports details via `rust/crates/pizzora-api/src/error.rs:L52`, but the inventory error does not use it). Recipe target/ingredient failures also use different codes and statuses (`RecipeService.kt:L54-L72` vs `rust/crates/pizzora-api/src/services/recipes.rs:L92-L126`), and demand expansion is checked and deterministically sorted in Kotlin but unchecked and insertion-ordered in Rust (`RecipeService.kt:L133-L152` vs `recipes.rs:L235-L257`). These are contract risks, not proven runtime failures.

**B9-G. Android owner runtime evidence is absent.** The owner inventory/recipe UI is Robolectric-tested, but the Phase 8 runtime QA report covers customer builder/cart/reorder, not owner inventory/recipes (`qa-artifacts/phase8/visual/runtime/ANDROID_RUNTIME_QA.md`). Robolectric coverage is narrow: recipe save and quick restock are asserted (`Phase14OwnerRobolectricTest.kt:L609-L623`), but ingredient add/adjust and ledger presentation are not. Visual, TalkBack/accessibility, and release-runtime gates are unverified for B.

**B9-H. No canonical load profile.** The Kotlin tests use 20 threads and 16×3 rounds; whether that matches the approved production load profile is undefined. The archived acceptance criterion says “under approved load profile,” which does not exist yet.

**B9-I. Schema is sound but not independently reversible.** Inventory/recipe/ledger tables and their generated availability, checks, cascades, and `RESTRICT` FKs are already embedded in V1 (`backend/src/main/resources/db/migration/V1__core_schema.sql:L177-L192,L194-L215,L377-L389`), so the base feature needs no new migration to roll back. Reversibility therefore depends on feature/UI gating and forward-compatible binaries, not on a Phase 9 revert migration. Any new B schema work must be additive SQLx-only (`V12+`); V1–V3 are immutable mirrors and competing Flyway V4+ definitions are prohibited (`public/implementation_plan.md:L912-L915`; `rust/crates/pizzora-db/src/migrator.rs:L500-L580,L741-L827`).

### 7.4 Candidate B judgment

**READY WITH CONDITIONS in Kotlin; NOT READY cross-stack.** The base schema is already in V1, the Kotlin implementation and stress tests are strong, and the Android owner code exists. The missing work is concentrated in Rust parity tests, DTO/behavior reconciliation, modifier-demand scope, and Android runtime evidence. Confidence: **high** on code facts; **medium** on effort because the Rust parity work is not estimated by execution.

---

## 8. Comparison method and criteria

Each candidate is assessed against ten criteria. Ratings are `Strong`, `Moderate`, `Weak`, or `Unknown`, with the evidence basis stated. No numeric weighting is applied because the business weights are not approved; a weighted score would create false precision.

| # | Criterion | Definition |
|---|---|---|
| 1 | Product value | New or preserved customer/owner capability; business outcome; not revenue estimates |
| 2 | Technical value | Architectural simplification, debt reduction, parity, maintainability |
| 3 | Readiness | Implemented code, tests, schema, API, UI, and executed evidence |
| 4 | Dependencies | Prerequisites, approvals, sequencing, and cross-team inputs |
| 5 | Risk | Probability/impact of data loss, outage, security, correctness, or rollback failure |
| 6 | Effort | Development, operational, review, and evidence effort (T-shirt estimate, no timeline) |
| 7 | Testability | Ability to prove correctness repeatably before and after release |
| 8 | Operational impact | Production/staging disruption, on-call load, monitoring, rollback |
| 9 | Reversibility | Ability to pause/undo without data loss or dual-stack divergence |
| 10 | Strategic fit | Alignment with the repository’s evidenced architecture trajectory and canonical plan |

---

## 9. Product value comparison

### 9.1 Candidate A

Candidate A adds **no new customer-facing capability**. Its product value is indirect: it reduces the operational cost and risk of running two backends, removes the Ktor/Rust parity surface, and lets future features be built once in Rust. The repository evidences the dual-stack cost (20,267 lines of Kotlin backend source, 46,523 lines of Rust source, separate Docker/publish/rollback paths) but does **not** quantify the cost saving, outage risk, or business priority.

**Rating: Moderate/indirect.** Confidence: **medium** on the capability description; **low** on business value.

### 9.2 Candidate B

Candidate B adds an **owner-facing capability**: ingredient stock, low-stock visibility, inventory ledger/audit, relational recipes, and recipe-driven checkout depletion. The customer-facing benefit is indirect (better availability/stock accuracy). The archived plan defines the capability, and the Kotlin/Android code demonstrates it; there is no repository evidence of customer demand, revenue impact, wastage reduction, or owner priority.

**Rating: Moderate/direct for owner operations.** Confidence: **medium** on capability; **low** on business value.

### 9.3 Product-value conclusion

Neither candidate has a documented business case, success metric, customer requirement, or priority. The repository can show what each candidate **does**, not which one the business **wants first**. Therefore, on product value, the comparison is `[PRODUCT INPUT REQUIRED]`, not a ranking.

---

## 10. Technical value comparison

| Dimension | Candidate A | Candidate B |
|---|---|---|
| Architectural simplification | **Strong.** Retires the Ktor runtime, one Docker/publish/rollback path, one migration owner, one worker topology. | **Moderate.** Completes a domain but does not reduce the dual-stack surface; if Kotlin-only, it increases divergence. |
| Debt reduction | **Strong** if cutover is safe: removes legacy backend source and parity obligations. | **Moderate:** resolves inventory/recipe completeness but adds checkout demand complexity and cross-language parity work. |
| Correctness leverage | **Moderate:** reuses the Phase 8 Rust baseline; cutover does not itself add domain correctness. | **Strong:** inventory/recipe depletion improves checkout accuracy and stock integrity, provided parity is proven. |
| Migration/schema impact | **High risk:** production schema handoff, V1–V11 profile, rollback compatibility. | **Low/medium:** base tables already in V1; additive modifier-recipe tables only if modifier demand is in scope. |
| Strategic fit with current plan | **High:** the canonical Phase 8 components are Rust-first; archived plan 12 is the Rust-migration lineage. | **Medium:** archived plan 6 is an older Kotlin-stack lineage; it must be re-based onto the current Phase 8 modifier model and Rust target. |
| Strategic fit with product depth | **Low/indirect:** platform work, no new feature. | **High/direct:** owner operations, stock control, recipes. |

**Conclusion:** A has the stronger long-term architectural value and the stronger fit with the repository’s Rust-consolidation trajectory. B has the stronger immediate domain value and a much smaller operational blast radius. The two values are not substitutes; they answer different questions (“simplify the platform” vs “deepen owner operations”). Confidence: **medium-high** on the technical facts; **medium** on the strategic weighting.

---

## 11. Readiness and dependencies

### 11.1 Readiness matrix

| Layer | Candidate A | Candidate B |
|---|---|---|
| Schema | Phase 8 schema at V1–V11 is implemented and migration-tested; cutover handoff is not production-proven | Base inventory/recipe tables already in V1; no new base migration needed; modifier demand would need additive tables |
| Backend code | Rust API/worker/migrator implemented | Kotlin complete; Rust implemented but untested for inventory/recipe parity |
| Contracts/DTOs | Rust/Kotlin Phase 8 parity is strong for modifiers/reorder; inventory/recipe parity is not the cutover’s focus | Shared DTOs exist; `reason` default and duplicate-recipe semantics diverge |
| Android | Cutover is backend-only; Android API base URL is build-time (`app/build.gradle.kts:L29-L34,L60-L62`), so hostname/route preservation is required | Owner UI/ViewModel/API exist; no owner runtime captures |
| Tests | Phase 8 Rust/Kotlin/Android evidence is strong at `5c21fa4`; exact-HEAD live-PG is unverified | Kotlin stress suite is strong; Rust has no equivalent; Android is unit-only |
| Operations | Approval, backup/restore, digests, drain, dead-letter, staging, rollback drill all missing | Low operational change; feature flags can gate |
| Overall | **Not ready / No-Go** | **Kotlin ready; cross-stack not ready** |

### 11.2 Shared prerequisites

| ID | Prerequisite | Classification | Why |
|---|---|---|---|
| S1 | Canonical Phase 9 scope and exit gate | **Shared blocker** | No candidate is authorized; the plan stops at Phase 8 |
| S2 | Product priority / candidate selection | **Shared blocker (product input)** | Repository cannot choose A vs B |
| S3 | Baseline approval at `9a9cf38` and W4 remediation-or-waiver decision | **Conditional prerequisite** | W4 defects are FFI-surface defects, not shared blockers for the server-backed path; a fix-or-waiver is required only if the selected scope relies on UniFFI/native bridge behavior or claims W4 closure |
| S4 | Exact-HEAD live-PG and full-tier CI evidence | **Shared evidence-gate blocker; waivable only by explicit acceptance** | Last live-PG log is 14:30Z, before HEAD’s 15:16Z Clippy commit; Clippy workspace log marks 21 live-PG tests ignored; feature-branch CI skips parity/integration/Android/Docker. Two reviewers treat this as a blocker; the critical review treats it as provenance-only with low regression risk. Either way it must be rerun or explicitly waived before Phase 9 |
| S5 | Android native bridge activation proof | **Conditional / unverified** | Not required for the server-priced Android path; required if a native FFI claim is made |
| S6 | Phase 8 modifier demand in inventory recipes | **Candidate B-specific blocker** | Recipes cover variant/crust/topping only; Phase 8 modifiers are absent |
| S7 | Rust inventory/recipe parity/stress tests | **Candidate B-specific blocker** | Kotlin tests are strong; Rust has no equivalent |
| S8 | Cutover operational gates | **Candidate A-specific blocker** | Approval, backup/restore, digests, runbook reconciliation, smoke coverage, drain/dead-letter, staging/P0/P1, rollback worker topology |
| S9 | Debug/local-only runtime QA | **Accepted limitation** | Adequate for Phase 8 code closure; not adequate for production cutover |
| S10 | At-least-once email, Redis degraded mode, S3 disabled | **Accepted limitation** | Explicitly documented operational semantics |
| S11 | 46-vs-51 table and stale-progress-report documentation debt | **Optional improvement** | Does not block execution, but should be reconciled before an operational runbook is used |
| S12 | Ktor retirement approval | **Candidate A-specific blocker** | Requires stability window, clean SLOs, and written sign-off |
| S13 | Android release-quality MAJORs (2.0× menu price clipping, version-name/code mismatch) | **Optional improvement / release-quality gate** | Not a Phase 9 scope blocker for either candidate, but should be fixed or waived before any release sign-off; coupon-sheet and cart-scroll concerns were not confirmed defects |

### 11.3 Candidate-specific blocker summary

**Candidate A:** written approval; real backup/restore proof (not the V1–V3 synthetic drill); pinned digests and a staging artifact at the exact commit; runbook/migrator reconciliation; corrected and expanded smoke coverage; dead-letter/drain proof; zero P0/P1/staging sign-off; Ktor rollback worker topology and worker-stop rehearsal; Railway observability and `/metrics` protection; owner-bootstrap/SMTP configuration review; per-route parity inventory; retirement approval.

**Candidate B:** canonical scope/acceptance criteria; Rust parity/stress tests; `reason` default reconciliation; duplicate-recipe semantics; modifier-demand decision and implementation; error/audit parity; Android owner runtime evidence; approved load profile.

---

## 12. Risk, effort, testability, operational impact, reversibility, strategic fit

| Criterion | Candidate A | Candidate B | Edge |
|---|---|---|---|
| Risk | **High:** production cutover, auth/payments/WS, schema handoff, dual-worker risk, rollback complexity | **Medium:** stock/checkout correctness, oversell/deadlock, data integrity, but no production cutover | B |
| Effort | **High operational / low-medium code:** approvals, backup/restore, staging, rehearsal, monitoring, rollback; plus runbook/script/worker-topology fixes | **Medium-high development / low operational:** Rust parity tests, DTO fixes, modifier demand, Android runtime QA | Mixed: A less code, more ops; B more code, less ops |
| Testability | **Low-medium:** production-like smoke/rollback drills; credentials and state are hard to reproduce | **High:** unit, integration, concurrency, FK, idempotency, Android Robolectric; Rust PG18 port needed | B |
| Operational impact | **High:** production cutover, possible downtime, on-call, rollback window | **Low-medium:** owner console and checkout depletion; feature flags can disable | B |
| Reversibility | **Moderate/weak in current state:** Ktor rollback if expand-and-contract holds, but rollback IaC omits the worker and is not digest-pinned; Ktor retirement is irreversible; DB restore only for proven corruption | **High:** additive schema, feature flags, no production cutover; inventory consumption can be disabled | B |
| Strategic fit | **High** for Rust consolidation and dual-stack retirement | **High** for owner operations; **medium** for the Rust trajectory unless implemented Rust-first | Context-dependent |

### 12.1 Risk detail

**Candidate A’s risk is concentrated in operations, not code.** The Phase 8 Rust baseline is strong, but a production cutover can fail through missing approval, stale backup, wrong digest, schema misread, dual workers, incomplete smoke, or an unproven rollback. These are exactly the gates the runbook defines. The risk is not theoretical: the runbook and migrator disagree on the migration profile, and the Railway rollback omits a Ktor worker.

**Candidate B’s risk is concentrated in correctness and parity.** The Kotlin implementation is well tested, but the Rust implementation is untested for the same concurrency/idempotency/FK properties. If B is implemented Kotlin-only, it increases the eventual cutover surface. If it is implemented Rust-first, it adds upfront work but reduces that surface.

### 12.2 Effort detail

These are T-shirt estimates, not schedules:

- **Candidate A:** operational effort **high** (approval, backup/restore drill, staging sign-off, cutover rehearsal, monitoring window, rollback drill); code effort **low-medium** (runbook/script reconciliation, rollback worker topology, W4 waiver/fix, exact-HEAD evidence).
- **Candidate B:** development effort **medium-high** (Rust parity tests, DTO/behavior reconciliation, modifier-demand schema/service, Android runtime QA); operational effort **low** (feature flags, no cutover).
- **Both/separate phases:** additive effort; B Rust-first can reduce A’s post-cutover work, but increases B’s upfront scope.

No timeline, staffing, or cost estimate is made because the repository does not contain them.

---

## 13. Sequencing options analysis

### Option 1 — Cutover first (A, then B)

**Pros:** retires Ktor first; B can then be implemented Rust-only; avoids implementing B twice; removes the dual-stack parity burden before new domain work.

**Cons:** A is No-Go as written; B is delayed; a failed cutover blocks all feature work; B’s modifier-demand schema would be designed post-cutover without the current Kotlin test harness; the operational risk is highest at the point of least evidence.

**Verdict:** **Not recommended now.** Viable only after every A gate is satisfied and if platform consolidation is the explicit business priority.

### Option 2 — Inventory first (B, then A)

**Pros:** B has the strongest implementation/test base; lower operational risk; delivers owner capability sooner; can be built Rust-first to prepare for A; keeps the production cutover separate.

**Cons:** B’s canonical scope is undefined; Rust parity and modifier-demand gaps remain; if B is Kotlin-only, it increases A’s eventual work and dual-stack divergence; B’s checkout changes add a dependency to A.

**Verdict:** **Conditional — the best feature-first path if the product owner prioritizes owner operations and B is implemented Rust-first.** Not executable until scope, parity, and runtime evidence are approved.

### Option 3 — Shared remediation first

**Pros:** addresses the shared evidence/quality gaps (W4 FFI defects/waiver, exact-HEAD live-PG, full-tier CI, Rust/Kotlin parity, Android bridge decision); reduces risk for either candidate; does not require choosing A or B immediately.

**Cons:** it is **not a Phase 9 scope**; it does not resolve the canonical-scope or product-priority blockers; some W4 defects are latent/unused, so remediation could be disproportionate; it can delay product value.

**Verdict:** **Necessary prerequisite, not a standalone Phase 9.** Scope it as a bounded “Phase 8 closure/remediation” workstream, with an explicit waiver where the FFI surface is not used.

### Option 4 — Separate phases (A and B, in a defined order)

**Pros:** avoids conflating a feature with a production cutover; each phase gets its own gate, evidence, and rollback; allows B to be Rust-first and A to follow; resolves the “either/or” conflict without pretending they are the same kind of work.

**Cons:** expands the canonical plan beyond Phase 8; requires product approval for both; sequencing matters (B before A is lower risk if B is Rust-first; A before B avoids double implementation only if B is deferred); coordination and review overhead.

**Verdict:** **The recommended structure if the product owner wants both.** The least-risk order is **B (Rust-first) → A**, with shared remediation first. If platform consolidation is urgent and A’s gates are met, **A → B (Rust-only)** is viable. Do not run A and B concurrently because both touch checkout/inventory and schema.

### Option 5 — Defer to product decision (current recommendation)

**Pros:** honest about the missing canonical scope and business priority; avoids inventing a Phase 9; allows shared remediation and Phase 8 evidence closure to proceed without a premature commitment; preserves both options.

**Cons:** leaves Phase 9 undefined; may delay value; requires a follow-up decision.

**Verdict:** **Current final recommendation**, because the repository cannot supply the product priority and the canonical plan has no Phase 9. This is not “do nothing”: it includes a bounded shared-remediation path and a decision framework.

### 13.1 Dependency graph

```text
W0 Canonical Phase 9 scope + product priority (BLOCKING)
├── A selected
│   └── A gates: approval → backup/restore → digests → runbook/migrator
│       → smoke → drain/dead-letter → staging/P0/P1 → rollback drill
│       → cutover → stability window → Ktor retirement
└── B selected
    └── B spec/acceptance → Rust parity tests → DTO/behavior reconciliation
        → modifier-demand decision → Android runtime QA → B exit gate
            └── if A follows: B must be Rust-first or ported before cutover

Shared remediation (W4 waiver/fix, exact-HEAD PG, full-tier CI, parity)
└── runs before either implementation gate; does not choose A or B
```

### 13.2 Sequencing conclusion

- **Cutover first:** reject now; A is No-Go.
- **Inventory first:** conditionally viable; B must be Rust-first and scoped.
- **Shared remediation first:** necessary but insufficient; not a Phase 9 substitute.
- **Separate phases:** the best structure if both are approved; B → A is lower risk.
- **Defer/product decision:** the only defensible final recommendation given the missing canonical scope and business priority.

---

## 14. Independent review results

Seven independent read-only reviews were launched on `opencode/big-pickle`. Each was instructed not to edit/create/delete files, run builds/tests/migrations/deploys, or perform Git writes. Results are recorded below; where a reviewer identified a finding, it is incorporated into the relevant section.

| # | Review | Session | Verdict | Key findings |
|---|---|---|---|---|
| 1 | Production cutover architecture | `ses_de21e64acffeFgtOA5R2qMq2ci` | **NOT READY** | Smoke script's auth JSON keys (`accessToken`/`refreshToken`) do not match the Rust or shared contract (`access_token`/`refresh_token`), so the authenticated path fails before certifying any endpoint; Railway IaC has no proxy/Prometheus/Alertmanager while `/metrics` is publicly registered and Caddy blocks only in Compose; Railway rollback does not stop `pizzabi-worker` and rebuilds from source; staging template builds `main` at `415c17b`, which predates V4–V11; backup drill is synthetic and S3 upload is warn-only; owner bootstrap/SMTP hazards; per-route parity not certified. High confidence on repo facts, medium on live Railway behavior. |
| 2 | Inventory/recipes architecture | `ses_de21e64abffeyGqOdIk5daPjjG` | **NOT READY** | Kotlin path is substantially implemented with strong concurrency evidence; Rust mirrors sorted locking, ledger idempotency, and lifecycle integration (checkout/payment/cancel/expiry) but has no Rust PG18 oversell/deadlock/replay/FK tests; contract divergences include `reason`, duplicate ingredients, target-not-found codes, dropped stock details, and checked/deterministic vs unchecked/insertion-order demand expansion; V1-embedded schema is sound but reversibility depends on feature/UI gating; Android Robolectric evidence covers recipe save/restock but not add/adjust/ledger presentation or visual/accessibility. |
| 3 | Backend/database parity | `ses_de21e64a9ffe50Lv2A6C1n6Gc1` | **NO-GO / INSUFFICIENT EVIDENCE** | Independently confirms no canonical scope; A's migration profile conflict, missing operational gates, and a second smoke-script defect (asserts top-level `code`/`message` instead of the nested `error` object); B's `reason`/duplicate/details/target-code/audit divergences, missing Rust PG18 stress tests, and absent Phase 8 modifier demand. Verifies OK: permission strings match across Ktor/Rust and error-envelope casing is compatible. Recommends shared remediation → B Rust-first → A separately. |
| 4 | Rust/UniFFI | `ses_de21e64a1ffeRkmQUwZ3WHm56k` | **NOT READY** | W4 defects are real but latent/unused: the **pricing helper** compares price extras for half-and-half distinct/overlap (`pizzora-uniffi/src/lib.rs:L861-L904`), while cart validation itself uses topping IDs (`pizzora-core/src/cart_validation.rs:L604-L653`), so this is a conditional FFI-helper defect; debug-name issue codes (`L1042-L1048`), strict reorder parsing vs server fallback (`L1487-L1543`), dead `sort_order` conversions, fail-open defaults; Android bridge only implements legacy methods (`DomainBridge.kt:L53-L89`); exact-HEAD live-PG unverified (last log 14:30Z vs HEAD 15:16Z; Clippy log marks 21 tests ignored). |
| 5 | Android integration | `ses_de21e649fffe0X27Eb9AOFZTwk` | **NO RELEASE BLOCKER; two MAJOR + one process MAJOR** | Menu “From” price is visually clipped at 2.0× font (`CustomerMenuScreen.kt:L665-L676`; runtime XML `60_menu_font_2_0.xml`); update dialog can show `v1.0 → v0.6.1` due to default `versionName=1.0`/`versionCode=1` vs CI's `v0.6.1`/code `601` (`app/build.gradle.kts:L20-L27`; `.github/workflows/security-and-ci.yml:L830-L831`); Android prep/unit/instrumented jobs skip plain branch pushes. Coupon-sheet and cart-scroll concerns were **not confirmed** as defects. |
| 6 | QA/operational risk | `ses_de21e649dffekySmm1T6VGxBiD` | **NO-GO / BLOCKED** | Independently recomputed Phase 8 XML counts (230 app / 86 backend / 40 shared, zero failures); exact-HEAD live-PG and CI tiering are shared evidence-gate blockers; A gates absent (approval, backup/restore, digests, drain/dead-letter, smoke, migration profile, rollback drill); B is Ktor-test-complete but Rust-parity-unverified, with a new wire divergence: Kotlin `INSUFFICIENT_INVENTORY` carries `details` (`InventoryService.kt:L367-L377`) while Rust inventory errors do not attach them (`pizzora-db/src/inventory.rs:L598`; `pizzora-api/src/error.rs:L52`). |
| 7 | Critical review | `ses_de21e649bffekskFot4wS4tGoD` | **NEEDS MORE EVIDENCE** | No repository evidence to choose A/B on product priority; W4 defects are conditional FFI-surface issues, not shared blockers; A is No-Go, B is Not Ready; least-risk defensible choice is “insufficient evidence,” with separate phases only after scope approval. |

### 14.1 Review synthesis

The completed Rust/UniFFI review confirms the prior assessment’s W4 findings and adds an important qualification: the defects are **latent/unused by the current Android server-priced path**, not active runtime failures. That makes them a **waiver-or-fix** decision, not an automatic blocker for either candidate. The critical review independently qualifies the half-and-half finding further: only the **pricing helper** compares price extras; `cart_validate_with_modifiers` uses topping IDs (`rust/crates/pizzora-core/src/cart_validation.rs:L604-L653`). W4 remediation is therefore conditional on a scope that relies on the FFI/native bridge, not a blanket shared blocker. The critical review also confirms the exact-HEAD live-PG gap, the weak Clippy-log provenance, the runbook/migrator contradiction, the rollback topology/pinning gaps, the smoke-coverage gap, and the Rust inventory/recipe parity gap. It explicitly answers that repository evidence **cannot** choose A or B on product/business priority, and that the least-risk defensible choice among the five allowed is **insufficient evidence**.

The QA/operational review adds three independent checks: it recomputed the Phase 8 XML counts directly (230 app / 86 backend / 40 shared tests, all with zero failures/errors/skips), classified exact-HEAD proof and CI tiering as shared evidence-gate blockers, and identified a further candidate-B wire divergence — Kotlin's `INSUFFICIENT_INVENTORY` response carries `details`, while the Rust inventory error does not attach them. QA also confirms that any approval found outside the repository cannot be verified here, so the "no evidence found" findings are medium-high, not absolute, confidence.

The production cutover review independently verifies the operational No-Go and adds a concrete correctness defect: the smoke script parses camelCase token fields that the Rust API and shared Kotlin contract do not emit, so even the optional authenticated smoke path would fail. It also confirms the rollback worker/pinning gaps, identifies missing Railway observability plus a public `/metrics` route, and notes that the reusable staging template builds a pre-Phase-8 `main` commit. These are incorporated as A9-E and A9-K through A9-O.

The backend/database review independently confirms both candidate verdicts and adds the nested-error-envelope defect to the smoke-script failures; it also verifies positive parity (matching permission strings and wire casing) and warns that any new B schema work must be additive SQLx-only `V12+`, never edits to immutable V1–V3 or competing Flyway V4+. The inventory/recipes review confirms that Rust calls inventory from checkout, payment, cancel, and expiry, but has no Rust PG18 stress evidence; it adds demand-expansion arithmetic/ordering divergence and narrows the Android unit evidence to recipe save/restock.

The Android integration review found no release BLOCKER and explicitly did **not** confirm the coupon-sheet and cart-scroll concerns. It did raise two MAJOR quality findings — menu price clipping at 2.0× font and an update dialog that can suggest `v1.0 → v0.6.1` because build defaults disagree with CI version metadata — plus a MAJOR process risk that Android jobs skip plain branch pushes. These are release-quality/CI findings, not Phase 9 scope blockers; they are classified as optional improvements/evidence gates below.

### 14.2 Consolidated review outcome

All seven reviews are complete. Their verdicts converge:

| Review | Verdict | Implication for this decision |
|---|---|---|
| Production cutover architecture | NOT READY | Candidate A No-Go; concrete smoke-script and rollback/observability defects |
| Inventory/recipes architecture | NOT READY | Candidate B not cross-stack ready; parity and evidence gaps |
| Backend/database parity | NO-GO / INSUFFICIENT EVIDENCE | Both candidates blocked on scope; A gates and B divergences confirmed |
| Rust/UniFFI | NOT READY | W4 defects conditional to FFI/native surface; exact-HEAD gap |
| Android integration | No release BLOCKER; MAJOR quality/process findings | No candidate-specific blocker; release/CI hygiene needed |
| QA/operational risk | NO-GO / BLOCKED | Governance blocker; A gates and B Rust parity absent |
| Critical review | NEEDS MORE EVIDENCE | Repository cannot choose A/B; insufficient evidence is the defensible choice |

**No review recommends starting either candidate.** Six reviews independently treat missing canonical scope and evidence as blocking; the seventh scope-relevant review concurs while adding that W4 defects are conditional rather than blanket blockers. This strengthens the final recommendation in Section 15.1.

---

## 15. Recommendation and conditions

### 15.1 Final recommendation

**`INSUFFICIENT EVIDENCE — PRODUCT DECISION REQUIRED`**

Rationale:

1. The canonical plan has no Phase 9 scope or exit gate; any candidate is unauthorized.
2. Candidate A is No-Go as written; recommending it would require inventing approvals and operational evidence.
3. Candidate B is Kotlin-ready but not cross-stack-ready; recommending it would require inventing a canonical scope and accepting unproven Rust parity/runtime evidence.
4. Repository evidence cannot establish whether platform consolidation or owner operations is the business priority.
5. Shared remediation is necessary but is not a Phase 9 scope and does not resolve the choice.

### 15.2 Conditional recommendations

These are **not** the final recommendation; they are the evidence-supported path for each product decision.

| If the product owner decides… | Then the evidence-supported path is… |
|---|---|
| Platform consolidation / Ktor retirement is the priority | **Candidate A as a separate operational phase**, only after all `infra/RUNBOOK.md:L242-L252` gates, runbook/migrator reconciliation, pinned digests, smoke coverage, rollback worker topology, and retirement approval are satisfied. Do not start B in parallel. |
| Owner inventory/recipes is the priority | **Candidate B as Phase 9, Rust-first**, with shared remediation first, Rust parity/stress tests, `reason`/duplicate/error reconciliation, modifier-demand scope, and Android owner runtime evidence. Keep A as a later separate phase. |
| Both are required | **Separate phases: B (Rust-first) → A**, with shared remediation first. A → B is viable only if A’s gates are met and B is implemented Rust-only after cutover. Never combine them into one phase. |
| Neither is urgent | **Shared remediation + Phase 8 evidence closure only**; do not open Phase 9 implementation. Re-decide when product priority is available. |
| The product owner cannot decide | Keep Phase 9 **unopened**; use the decision inputs in Section 16 to resolve the priority. |

### 15.3 Conditions before any Phase 9 implementation

1. **W0 — Scope authority:** approve a canonical Phase 9 definition (or an approved equivalent) with objective, non-goals, owner, acceptance criteria, exit gate, and explicit implementation/deployment boundary.
2. **Baseline approval:** approve `9a9cf3810d212c44977ed7d39cb5feb0b19862f1` as the baseline and explicitly exclude `.opencode/opencode.jsonc`.
3. **W4 decision:** fix or explicitly waive the latent UniFFI defects; record the decision and the affected surface. This is conditional: if the selected scope uses only the server-backed path, a documented waiver is sufficient; if it relies on native bridge behavior, fix and test it.
4. **Exact-HEAD evidence:** re-run the ignored live-PG/migrator suites at `9a9cf38`, or explicitly accept the formatting-only Clippy commit as low-risk and record the waiver; attach command, timestamp, exit code, and SHA.
5. **Full-tier CI:** run a PR/full-tier CI pipeline (parity, PostgreSQL/Redis, Android unit/instrumented, Docker) or attach equivalent local evidence; do not rely on ordinary feature-branch pushes.
6. **Candidate A:** satisfy all six runbook gates, reconcile the V1–V3/[1,2,3] text with the 1–11/51-table reality, pin digests, fix the smoke script's token-key mismatch and add KDS/checkout/owner coverage or separate evidence, stage the exact commit, restore a Ktor worker in the Railway rollback topology and prove the Rust worker is stopped before rollback, provision Railway observability and block public `/metrics`, review bootstrap/SMTP configuration, certify per-route parity, and record zero P0/P1/staging/retirement sign-off.
7. **Candidate B:** approve the module/schema/API/UI scope; port the Kotlin stress/idempotency/FK assertions to Rust PG18, including checkout/payment/cancel/expiry outcomes; reconcile `reason`, duplicate-recipe, target-error, stock-details, demand-ordering/overflow, error-code, and audit behavior; decide whether Phase 8 modifier demand is in scope; keep V1–V3 immutable and use additive SQLx-only `V12+` for new schema; define feature/UI gating and binary compatibility as the rollback strategy; capture owner add/adjust/ledger runtime and accessibility evidence; approve a load profile.
8. **Independent review:** re-run the critical review after the above evidence exists, with no unresolved BLOCKER/MAJOR.
9. **Authorization:** obtain explicit written authorization for the exact implementation/deployment boundary. No production action is authorized by this document.

### 15.4 Decision triggers that would change this recommendation

- A written product-priority statement selecting platform consolidation or owner operations.
- Satisfied and recorded Candidate A gates (approval, backup/restore, digests, runbook reconciliation, smoke, drain/dead-letter, staging/P0/P1, rollback drill).
- Completed Candidate B Rust parity tests, DTO/behavior reconciliation, modifier-demand decision, and Android owner runtime evidence.
- A W4 fix-or-waiver decision.
- Exact-HEAD/full-tier evidence attached.
- A canonical plan amendment defining Phase 9 (or separate Phase 9/10) with an exit gate.

---

## 16. Confidence, assumptions, and missing evidence

### 16.1 Confidence levels

| Conclusion | Confidence | Basis |
|---|---|---|
| Canonical Phase 9 scope is absent | **High** | Direct plan inspection; gate says 1–8; no Phase 9 heading |
| Candidate A is No-Go as written | **High** | Runbook gates, script coverage, IaC topology, prior assessment |
| Candidate B’s Kotlin implementation/tests are strong | **High** | Code and 4-test XML inspection |
| Candidate B’s Rust parity is unproven | **High** | No Rust inventory/recipe test suite; code/behavior divergences verified |
| W4 defects are real but latent | **High** on facts; **medium** on severity | Source inspection; no Android caller found |
| Exact-HEAD live-PG is unverified | **High** | Timestamps and ignored-test log inspection |
| Product priority between A and B | **Unknown / low** | No business case, metric, or approval in repo |
| Effort/risk ratings | **Medium** | T-shirt estimates from code/ops surface; no execution |
| Least-risk sequence (B Rust-first → A) | **Medium** | Readiness/risk comparison; depends on product scope and Rust-first commitment |

### 16.2 Assumptions

- `[ASSUMPTION]` The archived plans remain non-canonical until explicitly approved.
- `[ASSUMPTION]` The Phase 8 server-backed path is the intended production path; the native FFI path is not currently required by Android.
- `[ASSUMPTION]` The Rust implementation is the cutover target, so Rust parity is a prerequisite for a durable B.
- `[ASSUMPTION]` T-shirt effort/risk ratings are relative, not schedules or cost estimates.
- `[ASSUMPTION]` No production state, secrets, approvals, or business priorities are available to this read-only review.

### 16.3 Missing evidence

| Missing evidence | Needed for | Current status |
|---|---|---|
| Canonical Phase 9 scope/exit gate | Both | Absent |
| Product priority/business case | Both | Absent (`[PRODUCT INPUT REQUIRED]`) |
| Written cutover approval/window/services | A | Absent |
| Production backup/restore proof | A | Absent |
| Pinned Ktor/Rust image digests | A | Absent |
| Runbook/migrator reconciliation | A | Contradiction present |
| KDS/checkout/owner smoke coverage | A | Incomplete |
| Dead-letter/worker-drain proof | A | Absent |
| Zero P0/P1/staging/retirement sign-off | A | Absent |
| Smoke script correctness and executed log | A | Script parses `accessToken`/`refreshToken` and asserts top-level `code`; contracts emit snake_case tokens and a nested `error` object; no log found |
| Railway observability, `/metrics` protection, scrape/alert provisioning | A | No Railway proxy/Prometheus/Alertmanager; Caddy block is Compose-only |
| Exact-commit staging artifact and pinned digests | A | Template builds `main` at `415c17b`; Compose uses `:latest` |
| Real production backup/restore proof with V4–V11 state | A | Drill uses V1–V3 plus synthetic rows; S3 upload warn-only |
| Bootstrap/SMTP production configuration review | A | Auto-bootstrap can overwrite owner; SMTP not config-required |
| Per-route Ktor-vs-Axum parity inventory and responsibility sign-off | A | Absent |
| Ktor rollback worker topology and worker-stop rehearsal | A | Railway IaC omits worker |
| Rust inventory/recipe stress/parity tests | B | Absent |
| `reason` default/duplicate/target-code/stock-details/demand-ordering/error/audit parity | B | Divergences present |
| Phase 8 modifier demand in recipes | B | Absent |
| Rust PG18 oversell/deadlock/replay/FK and checkout/payment/cancel/expiry tests | B | Absent; Kotlin-only evidence |
| Feature/UI gating and forward-compatible binary rollback plan | B | Absent; base schema already in V1 |
| Android owner add/adjust/ledger unit, runtime, and accessibility captures | B | Absent |
| Approved load profile | B | Absent |
| W4 fix-or-waiver record | Both (conditional) | Absent |
| Exact-HEAD live-PG/full-tier CI | Both (waivable) | Unverified |
| Native Rust bridge activation proof | Conditional | Unverified |
| 2.0× font price-visibility evidence and aligned version metadata | Release quality (both) | Price clipping reported; version mismatch reported for default builds |

---

## 17. Verification and no-change statement

### 17.1 Final baseline re-verification

| Check | Result |
|---|---|
| Branch | `ui-ux-changes` |
| HEAD | `9a9cf3810d212c44977ed7d39cb5feb0b19862f1` |
| Remote sync | `0 0` with `origin/ui-ux-changes` |
| Phase 8 checkpoint | `5c21fa4` present and ancestor; HEAD one commit later |
| Working tree | ` M .opencode/opencode.jsonc` (pre-existing) and `?? docs/PHASE9_READINESS_ASSESSMENT.md` (prior), plus this new document |
| Worktrees | Main, `steel-bell` (`415c17b`, `main`), `vivid-gate` (`6544440`, `mattpocock`), prunable detached `4752c67` |
| `docs/PHASE9_READINESS_ASSESSMENT.md` | Unmodified |
| `public/implementation_plan.md` | Unmodified |
| Source/tests/schemas/migrations/DTOs/APIs/CI | Unmodified |
| Production/staging | Not accessed or changed |

### 17.2 Exact document created

- `docs/PHASE9_SCOPE_DECISION.md` — this file. No other repository file was created or modified.

### 17.3 No-change confirmation

No Phase 9 implementation, canonical-plan edit, source/test/schema/migration/DTO/API/CI change, build, test execution, migration, emulator, backup/restore, deployment, rollback, secret access, commit, push, merge, tag, release, or destructive Git operation was performed. The pre-existing `.opencode/opencode.jsonc` modification was preserved untouched. The prior `docs/PHASE9_READINESS_ASSESSMENT.md` was not modified.

**Stop condition:** no Phase 9 work should begin until the product owner selects a scope and the conditions in Section 15.3 are satisfied.

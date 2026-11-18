# Nightly CI Foundation — Audit and Design

**Status:** Foundation only. The `nightly` branch has **not** been created, branch protection
has **not** been applied, and no nightly-specific workflow has been implemented.

**Audit date:** 2026-11-18

**Audited workflow:** `.github/workflows/security-and-ci.yml`, plus the reusable
`.github/workflows/publish-backend.yml` and `.github/workflows/publish-rust-backend.yml`.

**Repository state at audit start:** `main` = `4a62003b7d95148fd4c67894a6295ff22e53f88d`
(green run `44126759857`). PR #10 merged during the audit, moving `main` to
`8e0451404f0829d8d83c70616fed2928bcdfd3fa`; its post-merge push run `44290391455` completed
successfully, so `8e04514` is the current validated `main` commit.

**Audited PR head:** `d2d2bc2ddcfadb92900dda955228a023028275e2` (PR #10, run `44285772088`).

---

## 1. Existing CI architecture

### 1.1 Triggers and execution tiers

The main workflow is a single staged pipeline with tier gates. GitHub evaluates the job-level
`if:` conditions; the jobs that do not apply to a tier still appear as `skipped` checks.

| Event | Scope | Execution tier | What actually runs |
|---|---|---|---|
| `push` | Any branch (`branches: ['**']`) | Fast feature CI | Preflight, Rust quality, Kotlin contracts/backend tests, Rust unit tests. Android, parity, integration, Docker and APK jobs are skipped. |
| `pull_request` | Any PR, any target branch | Full validation | Preflight, Rust quality, Kotlin tests, Android preparation, parity gate, Rust unit + PostgreSQL/Redis integration, Android unit/Robolectric, API 36 instrumentation, both Docker validations, debug preview APK. Nothing is published. |
| `push` | `main` | Post-merge validation + preview publishing | Same as full validation except all Android jobs are skipped (the merged PR already validated them). Both Docker images are built, smoke-tested and pushed with preview tags. No APK artifact and no GitHub Release. |
| `push` | Tags matching `v[0-9]*.[0-9]*.[0-9]*` | Production release | Full validation plus signed release APK, both production Docker images and GitHub Release publication. |
| `schedule` | Default branch (`main`), `17 2 * * *` UTC | Nightly full regression | Full validation with no publishing and no APK artifacts. It does **not** run on `nightly` (see §6.3). |
| `workflow_dispatch` | Any selected ref | Manual full validation | Full validation plus debug preview APK. Docker is validate-only. Release jobs stay skipped. |

Two important findings for the nightly foundation:

- The existing `push` trigger already includes every branch, so a future `nightly` branch will
  trigger the workflow without a trigger edit. Today it would run the **fast** tier because the
  tier gates only special-case `main` and tags.
- `pull_request` has no branch filter, so PRs targeting `nightly` already receive the **full**
  validation tier as soon as the branch exists. No trigger edit is required for nightly PR
  validation.

### 1.2 Jobs, timeouts and dependencies

| Job ID | Check name | Timeout | Depends on | Tier gate |
|---|---|---|---|---|
| `preflight` | `Stage 0 — Security & Repository Preflight` | 15 min | — | Always |
| `rust-quality` | `Stage 1 — Rust Formatting, Clippy & Workspace Check` | 40 min | `preflight` | Always |
| `kotlin-contracts-and-backend` | `Stage 1 — Kotlin Shared Contracts & Backend Tests` | 30 min | `preflight` | Always |
| `android-prep` | `Stage 1 — Android Build Preparation (JDK, Rust, UniFFI Bindgen)` | 30 min | `preflight` | PR, tag, schedule, dispatch; skipped on branch pushes |
| `test-rust-parity` | `Stage 2 — Rust/Kotlin Parity Gate (Golden Vectors & Wire Contracts)` | 30 min | `preflight`, `rust-quality` | PR, `main`, tag, schedule, dispatch; skipped on feature pushes |
| `test-rust-unit` | `Stage 3 — Rust Workspace Unit Tests` | 40 min | `preflight`, `rust-quality` | Always (deliberately not gated on parity) |
| `test-rust-integration` | `Stage 4 — PostgreSQL 18.6 & Redis Integration` | 60 min | `preflight`, `rust-quality`, `test-rust-parity`, `test-rust-unit` | PR, `main`, tag, schedule, dispatch; skipped on feature pushes |
| `test-android` | `Stage 5 — Android Unit & Robolectric Tests` | 45 min | `preflight`, `rust-quality`, `test-rust-parity`, `android-prep` | PR, tag, schedule, dispatch; skipped on branch pushes |
| `test-android-instrumented` | `Stage 5 — Android Instrumentation Tests (API 36 Emulator)` | 60 min | `preflight`, `rust-quality`, `test-rust-parity`, `test-android` | PR, tag, schedule, dispatch; skipped on branch pushes |
| `backend-docker` | `Stage 6 — Kotlin Backend Docker (<mode>) / Build, Validate & Publish Backend Docker Image` | reusable: 60 min | `preflight`, `kotlin-contracts-and-backend` | PR, `main`, tag, schedule, dispatch; skipped on feature pushes |
| `rust-docker` | `Stage 6 — Rust Backend Docker (<mode>) / Build, Validate & Publish Rust Backend Docker Image` | reusable: 60 min | `preflight`, `rust-quality`, `test-rust-parity`, `test-rust-unit`, `test-rust-integration` | PR, `main`, tag, schedule, dispatch; skipped on feature pushes |
| `build-preview-apk` | `Stage 7 — Build Android Debug Preview APK` | 60 min | `preflight`, `kotlin-contracts-and-backend`, `android-prep`, `test-android`, `test-android-instrumented` | PR and dispatch only |
| `build-release-apk` | `Stage 7 — Build Signed Production Release APK` | 60 min | `preflight`, `kotlin-contracts-and-backend`, `android-prep`, `test-android`, `test-android-instrumented`, `test-rust-parity`, `test-rust-unit` | `v*` tags only |
| `publish-github-release` | `Stage 7 — Create GitHub Release` | 15 min | `build-release-apk`, `backend-docker`, `rust-docker` | `v*` tags only |
| `ci-summary` | `CI Summary` | 5 min | Every job above | Always (`if: always()`) |

The `<mode>` suffix in the Docker check names is event-dependent: `Validate Only` on PRs,
schedule and dispatch; `Preview Push` on `main`; `Release` on tags.

Dependency shape:

```text
preflight
├─ rust-quality
│  ├─ test-rust-parity ── test-rust-integration ── rust-docker
│  └─ test-rust-unit ─────┘
├─ kotlin-contracts-and-backend ── backend-docker
├─ android-prep ── test-android ── test-android-instrumented ── build-preview-apk
└─ build-release-apk (all test gates) ── publish-github-release (plus both Docker jobs)
```

### 1.3 Concurrency policies

- Main workflow group:
  `pizzora-ci-${github.workflow}-${pull_request number or github.ref}`.
  `cancel-in-progress` is true for PRs and ordinary non-`main` branch pushes, and false for
  `main` pushes, tags, schedule and dispatch. Production tags are never cancelled by
  development pushes.
- Kotlin Docker workflow group:
  `backend-image-${github.workflow}-${event name}-${ref}`; cancels only when the ref is not a tag.
- Rust Docker workflow group:
  `rust-backend-image-${github.workflow}-${event name}-${ref}`; cancels only when the ref is not
  a tag.
- Because a PR branch receives both a `push` run and a `pull_request` run, both runs appear in
  the PR check list with the same job names. The PR run is the full-validation run; the push
  run is the fast tier and reports the expensive jobs as skipped. PR #10 merged with this
  behaviour, so it does not block the current merge flow.

### 1.4 Current GitHub enforcement

This is an audit finding, not a recommendation:

- `GET /repos/imanpr33trai/pizzabi/branches/main/protection` returns **404 Branch not protected**.
- `GET /repos/imanpr33trai/pizzabi/rulesets` returns an **empty list**.
- Therefore **no CI check is currently a required check**. Every check above is advisory.
- The default branch is `main`.
- `nightly` does not exist locally or on `origin` (verified with `git branch -a` and
  `git ls-remote --heads origin nightly`).
- Repository merge settings: merge commits, squash merges and rebases are all allowed;
  `delete_branch_on_merge` is false.

### 1.5 Validation coverage

- **Android unit/Robolectric:** `./gradlew :app:generateUniFfiKotlinBindings
  :app:testDebugUnitTest -PpizzoraRequireRust=true --no-daemon` with JDK 21, Robolectric SDK 36,
  host UniFFI bindgen and a cached Robolectric `android-all` directory. All test classes use
  `@Config(sdk = [36])`.
- **API 36 instrumentation:** `reactivecircus/android-emulator-runner@v2` with API 36,
  `google_apis`, `x86_64`, `pixel_6`. The job enables KVM through a udev rule, then runs the
  bounded `.github/scripts/run-android-instrumentation.sh` wrapper. Diagnostics are collected
  while the emulator is alive.
- **PostgreSQL/Redis integration:** `postgres:18-alpine` and `redis:7-alpine` service
  containers, then `cargo test --workspace --all-features --locked -- --ignored
  --test-threads=1` with `DATABASE_URL`, `TEST_POSTGRES_URL` and `REDIS_URL` set.
- **Docker validation:** both reusable workflows build locally first, start the image against
  PostgreSQL and Redis, probe `/api/v1/health/live` and `/api/v1/health/ready`, verify the
  non-root runtime user and required binaries/jars, and only then push. PRs and validate mode
  cannot push; preview pushes are restricted to `main`; release pushes are restricted to
  semantic-version tags.
- **Production release:** tag-only signed APK build with monotonic `versionCode`, APK signature
  and ABI checks, checksums, `update.json`, and a post-publication GitHub Release asset check.
- **Security/policy:** `infra/security-check.sh` runs in Stage 0 and checks secrets, Play policy,
  manifests, backup rules, network security config and release endpoint defaults.

---

## 2. Robolectric timeout diagnosis

### 2.1 Observed incident

Run `44247696051` (`workflow_dispatch`, `feat/self-update` at `110b671`) was cancelled after
the 45-minute job timeout. Its `test-android` job (`134613443415`) showed:

| Time (UTC) | Event |
|---|---|
| 06:03:57 | `Stage 5 — Android Unit & Robolectric Tests` starts |
| 06:07:14 | `> Task :app:testDebugUnitTest` starts |
| 06:07:14–06:49:10 | No test output; the Gradle test worker is alive but silent |
| 06:49:13 | Job cancelled by the 45-minute timeout |

Because the report upload used `if: failure()`, a cancelled job produced no Android test
reports. That is why the incident was difficult to diagnose from CI artifacts alone.

### 2.2 Root cause

The failure was in the new self-update test, not in Robolectric SDK 36 or resource loading:

- `Phase20UpdateRobolectricTest` created a collector with
  `async { UpdateInstallStatusBus.events.first() }` inside `runBlocking`.
- `async` with the default dispatcher does not start the collector immediately. The broadcast
  receiver then emitted into a `MutableSharedFlow` with `replay = 0`, so the event was dropped
  before the collector subscribed.
- `first()` then waited forever. The test had no timeout, so the Gradle worker hung until the
  job timeout.
- A second view-model test idled the main looper once while DataStore I/O was still resuming,
  which was racy but secondary to the deadlock.

This was a test synchronization bug, not a need to increase the Robolectric timeout or skip
tests.

### 2.3 Fix and verification

Commit `d2d2bc2` (`test(update): make install-status Robolectric tests deterministic`) fixed it:

- Start collectors with `CoroutineStart.UNDISPATCHED`.
- Bound every wait with `withTimeout(5_000)`.
- Replace the single `idle()` with `idleUntil { ... }`, which retries looper idling for up to
  5 seconds.

Verification:

- Local `:app:testDebugUnitTest`: 23 test classes, **64 tests, 0 failures, 0 errors** in 2m27s.
- `Phase20UpdateUnitTest`: 6 tests, 0 failures.
- `Phase20UpdateRobolectricTest`: 4 tests, 0 failures, 0.289s.
- CI PR run `44285772088`: `Stage 5 — Android Unit & Robolectric Tests` passed in 5m17s.

Residual hardening opportunity (not applied in this foundation): change the Android and Kotlin
report upload steps from `if: failure()` to `if: always()` so a future cancellation still
uploads diagnostics, and consider a bounded per-test timeout. Neither is required to unblock CI
today.

---

## 3. Current failures and required fixes

### 3.1 Current state

At the audited heads there is **no failing validation gate**:

- PR #10 head `d2d2bc2`: run `44285772088` completed with every PR job successful, including
  the API 36 emulator job and the debug preview APK.
- Push run `44276189554` (`feat/self-update`): success (fast tier).
- Schedule run `44282665628`: success (full regression on `main` content).
- `main` at `4a62003`: run `44126759857` success; tag `v0.6.0` release run `44130113924`
  success.
- `main` at `8e04514` (post-PR-#10 merge): run `44290391455` success, including both preview
  Docker pushes.

### 3.2 Historical failures, all resolved

| Run | Failure | Resolution |
|---|---|---|
| `44245426254` | Stage 0 rejected `gradlew` Git mode (not `100755`) | Fixed by `110b671`; Stage 0 now verifies `100755` and wrapper execution. |
| `44247696051` | Android unit/Robolectric job hit the 45-minute timeout | Fixed by `d2d2bc2`; see §2. |
| `44104002649` | Tag `v0.6.0` at `bb9f30c` failed `:app:lintVitalAnalyzeRelease` because dependency verification did not cover the lint/Groovy toolchain | Toolchain coverage fixed by `fd596e2`; tag was moved to `4a62003` and release run `44130113924` succeeded. |

### 3.3 Required fixes

No additional fix is required to make the current pipeline reliable. Do **not** skip tests or
raise timeouts to hide the Robolectric issue; the deterministic fix is already on `main`.

---

## 4. Nightly branch creation

`nightly` does **not** exist. This foundation intentionally does not create or push it.

### 4.1 Exact administrator commands

Run after confirming that the selected `main` commit has a green post-merge run:

```bash
git fetch origin
gh run list --branch main --limit 1 --json databaseId,headSha,conclusion,status
```

`8e0451404f0829d8d83c70616fed2928bcdfd3fa` (current `main` after PR #10) is green, so create
the branch from that exact commit:

```bash
git switch --create nightly 8e0451404f0829d8d83c70616fed2928bcdfd3fa
git push --set-upstream origin nightly
```

If the current `main` run is not green, use the last green `main` commit instead. The last green
`main` before PR #10 is:

```bash
git switch --create nightly 4a62003b7d95148fd4c67894a6295ff22e53f88d
git push --set-upstream origin nightly
```

Do not run an automatic `git merge origin/main` into `nightly` at any point. Nightly is
manually maintained; main-to-nightly synchronization is a deliberate human action.

### 4.2 Ordering

1. Create and push `nightly` from the validated commit.
2. Apply the branch ruleset from §5.
3. Open PRs against `nightly`; do not push directly.

A GitHub ruleset can target a branch that does not exist yet, so applying this payload first is
technically possible. Creating the branch first is still the recommended order: the payload sets
`do_not_enforce_on_create: false` and has an empty bypass list, so a ruleset that is already
active would enforce the required checks on the initial seed push. Create and push `nightly`
first, then apply the ruleset.

---

## 5. Branch protection recommendation

**Not applied.** This section is a ready-to-apply configuration, not a claim that protection is
active.

The recommended payload is
[`.github/rulesets/nightly-branch-protection.json`](../.github/rulesets/nightly-branch-protection.json).
It uses a repository ruleset rather than the legacy branch-protection API because rulesets
support a zero-approval PR rule and an explicit empty bypass list.

### 5.1 Requested policy mapping

| Requirement | Ruleset setting |
|---|---|
| PR required | `pull_request` rule on `refs/heads/nightly` |
| Zero approvals | `required_approving_review_count: 0` |
| Required CI checks | `required_status_checks` with the exact names in §5.3 |
| Direct pushes restricted | `pull_request` rule plus `bypass_actors: []` |
| Force pushes/deletion blocked | `non_fast_forward` and `deletion` rules |
| Administrator-only merge permissions | Keep repository write access limited to administrators; an empty bypass list prevents even admins from bypassing the PR/check requirements |

GitHub does not expose a per-branch "only administrators may click Merge" switch on a personal
repository. Merge permission follows repository role. The repository currently has exactly one
collaborator, the owner with the `admin` role, so keeping write access with administrators is
the supported way to achieve this. If the repository moves to an organization, use a ruleset
bypass list containing only the administrator team and grant write access only to that team.

### 5.2 Apply and verify

First-time creation after `nightly` exists:

```bash
gh api --method POST repos/imanpr33trai/pizzabi/rulesets \
  --input .github/rulesets/nightly-branch-protection.json
```

Verify the ruleset exists and is active:

```bash
gh api repos/imanpr33trai/pizzabi/rulesets \
  --jq '.[] | select(.name == "nightly-branch-protection") | {id, name, enforcement, target}'

gh api repos/imanpr33trai/pizzabi/rules/branches/nightly
```

The second command lists the rules that actually apply to the branch. If it does not show the
pull-request, required-status-checks, non-fast-forward and deletion rules, protection is not
active.

### 5.3 Exact required check names

These names were taken from the actual check runs for PR #10 head `d2d2bc2` (run
`44285772088`, app `github-actions`) and are the names a PR targeting `nightly` will produce:

```text
Stage 0 — Security & Repository Preflight
Stage 1 — Rust Formatting, Clippy & Workspace Check
Stage 1 — Kotlin Shared Contracts & Backend Tests
Stage 1 — Android Build Preparation (JDK, Rust, UniFFI Bindgen)
Stage 2 — Rust/Kotlin Parity Gate (Golden Vectors & Wire Contracts)
Stage 3 — Rust Workspace Unit Tests
Stage 4 — PostgreSQL 18.6 & Redis Integration
Stage 5 — Android Unit & Robolectric Tests
Stage 5 — Android Instrumentation Tests (API 36 Emulator)
Stage 6 — Kotlin Backend Docker (Validate Only) / Build, Validate & Publish Backend Docker Image
Stage 6 — Rust Backend Docker (Validate Only) / Build, Validate & Publish Rust Backend Docker Image
Stage 7 — Build Android Debug Preview APK
```

Deliberately excluded:

- `CI Summary` — it uses `if: always()` and only reports job results; it can succeed even when
  a gate fails, so requiring it would not protect the branch.
- `Stage 6 — ... / Security Audit & ... (Manual Dispatch)` — these jobs are skipped on pull
  requests and only run for direct `workflow_dispatch` of the reusable Docker workflows.
- `Stage 7 — Build Signed Production Release APK` and `Stage 7 — Create GitHub Release` — they
  only run for `v*` tags and must never be required for PRs.

The Docker contexts include `(Validate Only)` because the mode expression resolves to
`validate` for pull requests and for pushes to any branch other than `main`. This is stable for
nightly PRs and nightly pushes. Do not copy the `(Preview Push)` or `(Release)` variants into
the nightly ruleset.

### 5.4 Legacy branch protection alternative

If the ruleset API is unavailable, the equivalent legacy protection is:

- Require a pull request before merging, with **0** required approvals.
- Require the status checks listed in §5.3 and require branches to be up to date.
- Enable `enforce_admins`.
- Disallow force pushes and deletions.
- Do not grant bypass to any user or team.

The legacy API's `required_approving_review_count` accepts `0`; the important part is that the
PR requirement remains enabled.

---

## 6. Nightly workflow extension design

This section is design only. No nightly workflow change is implemented in this foundation.

### 6.1 What already works without changes

- `push.branches: ['**']` already matches `nightly`.
- `pull_request:` already matches PRs targeting `nightly` and runs the full validation tier.
- `workflow_dispatch:` already exists and is branch-selectable.
- Production release jobs are already restricted to `v*` tags.
- Docker push guards already prevent PRs and non-`main` branches from publishing.
- The reusable Docker workflows already accept `mode` and `push` inputs for future nightly
  previews if that is desired later.

### 6.2 Tier-gate extension for nightly pushes

If the nightly branch will accept direct pushes (it should not, once protected) or if a future
nightly job needs a full validation push run, extend the existing full-tier predicates. The
minimal change is:

```yaml
# android-prep, test-android, test-android-instrumented
if: ${{ github.event_name != 'push' || github.ref_type == 'tag' || github.ref == 'refs/heads/nightly' }}

# test-rust-parity, test-rust-integration, backend-docker, rust-docker
if: ${{ github.event_name != 'push' || github.ref_type == 'tag' || github.ref == 'refs/heads/main' || github.ref == 'refs/heads/nightly' }}
```

Keep the publish expressions unchanged:

```yaml
push: ${{ github.event_name == 'push' && (github.ref == 'refs/heads/main' || github.ref_type == 'tag') }}
```

That makes a nightly push run the full validation suite while both Docker workflows remain in
`Validate Only` mode. Do not add `nightly` to the `push` expression.

`build-preview-apk` currently runs only for `pull_request` and `workflow_dispatch`. If a nightly
APK artifact is wanted later, add a `nightly` branch case and a `nightly-${SHORT_SHA}` artifact
ID; that belongs to the full nightly implementation, not this foundation. `build-release-apk`
and `publish-github-release` must remain tag-only.

`ci-summary` is cosmetic, but its tier label should eventually recognize a nightly push as
"nightly branch full validation (no publishing)" rather than "fast CI (feature branch push)".

### 6.3 Scheduled runs cannot target `nightly` directly

GitHub scheduled workflows run on the repository's default branch (`main`). The current
`schedule` entry therefore validates `main`, not `nightly`. A future nightly schedule must do
one of the following:

1. Keep a scheduler workflow on `main` that explicitly checks out `refs/heads/nightly` and
   invokes the shared validation jobs; or
2. Trigger the nightly workflow from `main` with `workflow_dispatch`/`repository_dispatch` and
   select `nightly`; or
3. Make `nightly` the default branch, which is not recommended because it would change release
   and PR defaults.

Do not assume that adding `nightly` to the `push` trigger makes the cron run on `nightly`; it
does not.

### 6.4 Manual dispatch

The existing `workflow_dispatch` can be run with `nightly` selected in the GitHub UI. It will
use the workflow file at that ref and run the full validation tier with no release. If future
nightly operations need inputs (for example, "validate only" vs "publish preview"), add
explicit `workflow_dispatch.inputs` in the nightly implementation stage rather than overloading
the current behaviour.

### 6.5 Boundaries preserved

- No change to the application ID `com.aistudio.pizzora.xkqzmw`.
- No change to release signing or keystore handling.
- No change to production Docker publishing or GitHub Release creation.
- No Railway deployment.
- No nightly updater or nightly release channel.
- No reduction in the current test matrix.

---

## 7. Validation performed

### 7.1 Local checks

| Check | Result |
|---|---|
| `bash infra/security-check.sh` | PASS (8/8 checks) |
| Workflow YAML parse and reusable-workflow reference check | PASS: 3 workflow files, 15/2/2 jobs |
| `generate-release-metadata.py --validate-policy-only` | PASS |
| `git ls-files --stage gradlew` | `100755` |
| `./gradlew --version` | Gradle 9.3.1 |
| `cargo metadata --locked --no-deps` + `git diff --exit-code -- rust/Cargo.lock` | PASS, lockfile unchanged |
| `cargo fmt --all -- --check` | PASS |
| `cargo test --workspace --all-features --locked` | PASS |
| `:shared:contracts:test :backend:test` | PASS (99 tests from reports: 23 contracts + 76 backend) |
| `:app:generateUniFfiKotlinBindings :app:testDebugUnitTest -PpizzoraRequireRust=true` | PASS: 64 tests, 0 failures, 0 errors in 2m27s |
| `:app:assembleDebug -PpizzoraRequireRust=true` with a CI-equivalent temporary debug keystore | PASS, debug APK produced (26,110,248 bytes) |
| Docker build/smoke and API 36 emulator locally | Not run locally; verified by CI run `44285772088` |

### 7.2 CI evidence

| Ref | Run | Result |
|---|---|---|
| PR #10 head `d2d2bc2` | `44285772088` | Success: all PR jobs, including API 36 instrumentation and debug preview APK |
| `feat/self-update` push `d2d2bc2` | `44276189554` | Success (fast tier) |
| Scheduled `main` content `d2d2bc2` | `44282665628` | Success (full regression, no publishing) |
| `main` `4a62003` | `44126759857` | Success |
| Tag `v0.6.0` → `4a62003` | `44130113924` | Success (signed release + GitHub Release) |
| `main` `8e04514` | `44290391455` | Success (post-merge validation + both preview Docker pushes) |

The `nightly` branch protection payload has not been applied. No branch-protection state was
changed during this audit.

---

## 8. Remaining blockers and follow-ups

1. **Create `nightly`.** Requires an administrator; use §4. The branch does not exist.
2. **Apply and verify the ruleset.** Recommended after the branch exists (§4.2); use §5. Until
   then, there are no required checks on `nightly`.
3. **Decide the nightly schedule mechanism.** The current cron runs on `main`; it cannot run on
   `nightly` without a dispatcher or an explicit checkout design (§6.3).
4. **Protect `main` separately.** `main` is currently unprotected and has no required checks.
   This is a pre-existing repository-wide risk, not part of the nightly branch foundation.
5. **Optional diagnostics hardening.** Upload Android/Kotlin test reports on cancellation
   (`if: always()`) and add bounded per-test timeouts. This is a follow-up, not a current
   blocker.
6. **Full nightly implementation is intentionally deferred.** Nightly releases, nightly
   updater, artifact publication and scheduled regressions are not implemented here.

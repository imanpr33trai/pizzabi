# Nightly Validation and Promotion Verification

## What `Nightly Validation — Complete` proves

`Nightly Validation — Complete` is the terminal gate in
`.github/workflows/security-and-ci.yml`. It runs with `if: always()` on every
workflow run so its check is never `skipped` (GitHub treats a skipped required
check as satisfied).

It claims and enforces nightly validation only for:

- `push` to `refs/heads/nightly` — post-merge nightly evidence.
- `pull_request` targeting `nightly` — pre-merge full validation.

In those contexts it requires all 11 validation jobs to conclude `success`.
`failure`, `cancelled`, `skipped`, `neutral`, or an empty result fails the gate.
Outside those contexts it reports `not applicable` and claims nothing.

The reporting-only `CI Summary` job never owns the pass/fail decision.

## Exact-SHA rule

A green gate on a `pull_request` validates the merge-preview commit
(`github.sha`), not the eventual merged nightly commit. Promotion evidence must
come from a completed `push` run on `refs/heads/nightly` whose `head_sha` equals
the current nightly tip.

Nightly branch pushes use `cancel-in-progress: true`, so an older run can be
cancelled. Never use an older successful run as proof that a newer tip passed.

## Pre-promotion verification

The manual promotion workflow
[`.github/workflows/promote-nightly-to-main.yml`](../../.github/workflows/promote-nightly-to-main.yml)
performs these same checks itself and refuses to create or update the promotion
PR unless every one passes. It is `workflow_dispatch`-only, must be dispatched
from `refs/heads/main`, and requires the exact nightly tip as `expected_sha`.
It never merges, pushes, tags, deploys, or publishes. Procedure and failure
recovery: `infra/RUNBOOK.md` section 15.

Run these read-only queries immediately before dispatching it (or before opening
a promotion PR by hand):

```bash
REPO=imanpr33trai/pizzabi
TIP="$(gh api "repos/$REPO/git/ref/heads/nightly" --jq '.object.sha')"
RUN_ID="$(gh api \
  "repos/$REPO/actions/workflows/security-and-ci.yml/runs?branch=nightly&event=push&head_sha=$TIP&per_page=100" \
  --jq '[.workflow_runs[] | select(.status=="completed" and .conclusion=="success")] | sort_by(.created_at) | last | .id // empty')"
test -n "$RUN_ID"
SUITE_ID="$(gh api "repos/$REPO/actions/runs/$RUN_ID" --jq '.check_suite_id')"
gh api "repos/$REPO/check-suites/$SUITE_ID/check-runs?per_page=100" \
  --jq '.check_runs[] | select(.name=="Nightly Validation — Complete") | "\(.conclusion)\t\(.head_sha)"'
```

Accept only `success` for the exact `$TIP`, and record the run ID, attempt, and
timestamp in the promotion PR body. A `skipped`, `cancelled`, or missing gate is
not evidence.

## Ruleset ordering

`Nightly Validation — Complete` is required by the active `nightly` ruleset.
The workflow change must land on `nightly` before any unrelated PR targets
`nightly`; otherwise the required check is missing and that PR stays blocked.
The implementation PR carries the workflow change itself, so it is not affected.

## Human control

No workflow merges `nightly` into `main`. Promotion remains a manually
initiated, reviewed PR: the promotion workflow may open or update it after
exact-SHA verification, but a human approves and merges. Main branch
protection, merge-commit-only policy, and release-tag protection are separate
controls and must not be bypassed by automation.

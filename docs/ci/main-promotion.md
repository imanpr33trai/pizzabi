# Main Branch Protection and Promotion Gate

## What `Main PR Validation — Complete` proves

`Main PR Validation — Complete` is the terminal gate in
`.github/workflows/security-and-ci.yml` for pull requests into `main`.

It runs with `if: always()` on every workflow run so its check is never
`skipped` (GitHub treats a skipped required check as satisfied). It claims and
enforces main-promotion validation only for `pull_request` events whose base is
`main`. On every other tier it reports `not applicable` and claims nothing.

In its enforcing context it requires all 11 validation jobs to conclude
`success`:

1. `preflight`
2. `rust-quality`
3. `kotlin-contracts-and-backend`
4. `android-prep`
5. `test-rust-parity`
6. `test-rust-unit`
7. `test-rust-integration`
8. `test-android`
9. `test-android-instrumented`
10. `backend-docker`
11. `rust-docker`

`failure`, `cancelled`, `skipped`, `neutral`, or an empty result fails the gate.
The reporting-only `CI Summary` job never owns the pass/fail decision.

The nightly gate (`Nightly Validation — Complete`) is unchanged and still owns
nightly push and PR-to-nightly validation.

## Exact SHA rules

On a `pull_request` event there are two commits:

- `github.event.pull_request.head.sha` — the PR head, i.e. the promotion
  candidate. For a `nightly` → `main` PR this is the validated nightly tip.
- `github.sha` — the merge-preview commit that the checks actually ran against.

The gate summary prints both and labels the PR head as the promotion candidate.
Promotion evidence must reference the **PR head SHA**, and that SHA must still
equal the current `nightly` ref immediately before merging. Never promote using
an older nightly run.

## Main ruleset

Active ruleset `main-branch-protection` targets `refs/heads/main`:

| Rule | Setting |
|---|---|
| Pull request required | yes; merge commits only |
| Required approvals | 0 (single-maintainer repository) |
| Required status checks | 12 stage contexts + `Main PR Validation — Complete` |
| Strict / up-to-date branches | yes |
| Force pushes | blocked |
| Branch deletion | blocked |
| Bypass actors | none |

The 12 stage contexts are the 11 validation jobs listed above plus
`Stage 7 — Build Android Debug Preview APK`. Requiring the individual contexts
as well as the terminal gate is defense in depth: a bug in the gate script
cannot silently drop a stage.

### Single-maintainer limitation

The repository currently has exactly one collaborator with admin rights. GitHub
does not count a PR author's own approval, so any `required_approving_review_count`
above zero would deadlock every PR into `main`. For that reason approvals are
set to zero and `require_extra_approval_for_unattributed_changes` is disabled.

This is a documented accommodation, not an oversight. Add a second maintainer
with write access, then:

1. Set `required_approving_review_count` to `1`.
2. Set `require_extra_approval_for_unattributed_changes` to `true`.
3. Keep the terminal gate required.

## Ordering constraint

The ruleset is active while `main` does not yet contain the new workflow job.
A same-repository PR runs the workflow from its merge ref, so a branch that
contains this change works. A PR whose head does **not** contain the job (for
example a hotfix branched from the old `main`, or a fork PR before the change
lands) will wait forever on `Main PR Validation — Complete`.

Land this change on `main` before opening unrelated PRs against it. The
nightly-to-main promotion PR carries the job itself, so it is not affected.

## Promotion procedure

1. Confirm the nightly push run is green and its `head_sha` is the nightly tip.
2. Open a manual PR from `nightly` to `main`.
3. Wait for all required checks, including `Main PR Validation — Complete`.
4. Verify the gate summary's PR head SHA still equals the current nightly tip.
5. Merge with a merge commit (the ruleset rejects squash and rebase).
6. The post-merge `main` push keeps its existing behavior: full Rust/Kotlin and
   Docker preview validation; Android and release jobs stay skipped.

No workflow opens, updates, or merges a promotion PR automatically.

## Read-only verification

```bash
REPO=imanpr33trai/pizzabi
PR=NNN
TIP="$(gh api "repos/$REPO/git/ref/heads/nightly" --jq '.object.sha')"
HEAD="$(gh pr view "$PR" --repo "$REPO" --json headRefOid --jq '.headRefOid')"
test "$TIP" = "$HEAD" && echo "promotion candidate is the current nightly tip"
gh pr checks "$PR" --repo "$REPO"
```

A green gate on the PR merge ref is **not** evidence that the PR head itself was
validated; it is evidence that the merge result was. Promotion records both
SHAs and the run ID in the PR body.

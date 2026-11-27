# PIZZORA Phase 8 — W9 initial independent review

Reviewer: `reviewer` subagent (`ses_de2ddc375ffeLMsivEr0D2WM9B`, model `opencode/big-pickle`)\nScope: committed range `db41169..bb63746` (HEAD `bb63746`); uncommitted work inspected but not credited.\nVerdict: **FAIL — do not publish committed range as Phase 8 complete.**

The reviewer found two BLOCKER defects and three MAJOR evidence gaps. This file records the
findings and the coordinator's remediation trail. A final independent re-review of the completed
working tree/commits is required before the Phase 8 verdict can be finalized.

## BLOCKER findings and remediation

| ID | Finding | Remediation |
|----|---------|-------------|
| BLOCKER-1 | Committed checkout used the base catalog lookup, so legitimate Phase 8 modifiers failed validation and could not be persisted | FIXED in working tree: `rust/crates/pizzora-api/src/services/checkout.rs` now uses `load_extended_catalog_ref_lookup`, enforces feature flags and half-and-half config matching; new live regression `pg18_checkout_accepts_and_persists_phase8_modifiers` passes; local API smoke passes end-to-end |
| BLOCKER-2 | Committed Android builder treated all Phase 8 modifier slots as unsupported | FIXED in working tree: W6 builder implements cheese/sauce/standard-topping/half-and-half selection, server-quote pricing, edit restoration, and zero-credit default handling; 28 app tests pass; cart/nav modifier wiring follow-up in progress |

## MAJOR findings and remediation

| ID | Finding | Remediation |
|----|---------|-------------|
| MAJOR-1 | No committed Android emulator screenshots, UIAutomator XML, TalkBack/font-scale/touch-target evidence | PENDING — final runtime QA after builder wiring lands and debug APK is installed on `emulator-5554` |
| MAJOR-2 | Kotlin backend parity covered only 13 of 33 golden vectors; no fixture digest | RESOLVED — `Phase8GoldenVectorFullParityTest` executes all 33 vectors through production `PricingService`; fixture SHA-256 `a1e6f596d14c9a74ea820d20fbe7a349bbbcfab29bb02cf7464598ea9fc61623`; `qa-artifacts/phase8/parity/kotlin_33_vector_parity.log` |
| MAJOR-3 | No real CLI `--preflight-only` / `--migrate-only` exit logs for both migration profiles | RESOLVED — `qa-artifacts/phase8/migrations/PHASE8_MIGRATION_CLI_EVIDENCE.md` records four successful invocations (fresh + Flyway adoption), 51-table verification, and the intended empty-DB guard |

## Minors/advisories

- Reorder confirm-button copy changed from “Add available items to cart” to the contract’s exact
  “Add available items only” in `OrdersScreen.kt` and its Robolectric assertion.
- Stale migrator preflight comment corrected to describe the 51-table Phase 8 inventory.
- Reviewer advisories: display-only saturating arithmetic was closed by switching all half-and-half
  breakdown/view sums to checked arithmetic with `ArithmeticOverflow` errors
  (`qa-artifacts/phase8/regression/checked_breakdown_arithmetic.log`). `"Unknown"` names in
  unavailable reconciliation entries and the Gradle bindgen fallback (direct bindgen succeeded)
  remain advisories only. None is a BLOCKER or MAJOR.

## Reviewer areas verified as sound

SQLx V4–V11 DDL, 51-table inventory, two profile invariants, rollback/reapply, Rust pricing formula,
zero-credit defaults, checked authoritative arithmetic, HALF_UP, Rust/UniFFI 33-vector parity,
additive DTO defaults, camelCase reorder wire shape, reorder ownership/no-substitution/no-cart-mutation,
Kotlin backend five-service changes, and UniFFI exports/error mapping.

# Phase 6 findings list

Maintained by the Phase 6 coordinator. Status values: OPEN, FIXEDPendingVerification, VERIFIED, WONTFIX, BLOCKED.

## P6-001

- **Severity:** High (blocking visual acceptance gate)
- **Requirement:** Section 13.2 Phase 6 capture 1 — Razorpay payment sheet at `<=3m` warning state, light `320dp` / `1.5x`; authoritative `MM:SS` countdown must stay visible with `warningContainer` escalation.
- **Affected file(s):**
  - `app/src/main/java/com/example/orders/RazorpayPaymentSheet.kt` (countdown row)
  - `app/src/test/java/com/example/Phase6PaymentSheetRobolectricTest.kt` (new regression test)
- **Evidence:** `qa-artifacts/phase6/evidence/visual_qa_summary.txt` §7 / `p6_payment_warning_light_320dp_1.5x.png` and same-screen UI XML. At `320dp` / `1.5x`, the prefix `"Expires in — complete payment now"` consumed the `SpaceBetween` row; the `MM:SS` node was absent from the screenshot and UI dump. At `1.0x` the diagnostic capture showed `"02:32"`.
- **Reproduction steps:**
  1. Open an unpaid `PENDING_PAYMENT` Razorpay order with `expiresAt` inside the 3-minute warning band.
  2. Set light theme, `320dp`, `fontScale = 1.5`.
  3. Open `RazorpayPaymentSheetDialog` and inspect the amber expiry bar.
  4. Observe the warning prefix and milestone text, but no live `MM:SS` value.
- **Required correction:** Reserve space for the timer so the long warning prefix wraps instead of pushing the timer out of the row; add a narrow/large-font regression test; recapture the live warning screenshot and verify the countdown value.
- **Assigned agent:** coordinator for implementation; `p6-visual-qa` for live recapture/verification.
- **Verification result:** VERIFIED. Coordinator changed the countdown row to `Arrangement.spacedBy(8.dp)` with the prefix at `Modifier.weight(1f)` and added `warningCountdownRemainsVisibleAt320dpWith15xFontScale`. Focused `:app:testDebugUnitTest --tests "com.example.Phase6PaymentSheetRobolectricTest"` passed 10/10. `p6-visual-qa` rebuilt the APK, recaptured the live matrix, and verified `01:57` inside the amber bar at light `320dp`/`1.5x` with 8.15:1 text contrast and an on-screen CTA. Old evidence is preserved in `qa-artifacts/phase6/superseded/`. See `qa-artifacts/phase6/evidence/visual_qa_summary.txt` §5 and `screenshot_requirement_mapping.md` R2.

## P6-002

- **Severity:** Blocking acceptance gate (process, not a source defect)
- **Requirement:** Independent Phase 6 reviews by two separate `p6`-prefixed agents with concrete evidence and clear PASS/FAIL verdicts.
- **Affected file(s):** `qa-artifacts/phase6/evidence/` review artifacts; `docs/PHASE6_IMPLEMENTATION_PROGRESS_REPORT.md`; `public/implementation_plan.md`.
- **Evidence:** `qa-artifacts/phase6/evidence/final_unit_suite_summary.txt` records earlier p6 test work; `visual_qa_summary.txt` records visual QA but no independent implementation review artifact exists yet.
- **Reproduction steps:** Inspect evidence directory for independent review findings and reconciled verdict before marking Phase 6 complete.
- **Required correction:** Obtain a fresh visual QA verdict after P6-001 and an independent implementation review from a separate p6 agent; reconcile all blocking/high findings.
- **Assigned agent:** `p6-visual-qa` (visual), `p6-regression-tests` (independent implementation review), coordinator (reconciliation).
- **Verification result:** VERIFIED. `p6-visual-qa` delivered a live post-fix matrix verdict (7/7 canonical PASS + conditional expired PASS) in `qa-artifacts/phase6/evidence/visual_qa_summary.txt` and `screenshot_requirement_mapping.md`. A separate `p6-regression-tests` session delivered `qa-artifacts/phase6/evidence/independent_implementation_review.md` with implementation verdict PASS and no BLOCKER/MAJOR findings. Its two MINOR observations are reconciled in P6-003 and in the progress report.

## P6-003

- **Severity:** Minor (accessibility wording, independently observed)
- **Requirement:** Stepper semantics must describe the step state accurately; active steps must not announce "Completed at ...".
- **Affected file(s):**
  - `app/src/main/java/com/example/orders/OrdersScreen.kt` (`OrderTrackingStepperCard` step description)
  - `app/src/main/res/values/strings.xml` (new `order_step_time_*` resources)
  - `app/src/test/java/com/example/Phase6OrderTrackingRobolectricTest.kt` (new `active step semantics say started at instead of completed at`)
- **Evidence:** Independent review finding 8 at `qa-artifacts/phase6/evidence/independent_implementation_review.md:19` reproduced `PREPARING` with `prepStartedAt` and observed generic `"Completed at %4$s"` wording for an active step.
- **Required correction:** Use state-specific time phrases: `Started at` for active, `Stopped at` for failed, `Completed at` for completed, `Recorded at` otherwise; preserve step index, label, and time.
- **Assigned agent:** coordinator.
- **Verification result:** VERIFIED. The state-specific phrases and a Robolectric regression test are present in the working tree. The first focused run exposed the one existing assertion that still expected `"Completed at"` for the active `ACCEPTED` step; that assertion was intentionally updated to `"Started at"` alongside the new `PREPARING` regression test. The second focused `:app:testDebugUnitTest --tests "com.example.Phase6OrderTrackingRobolectricTest"` run passed 5/5 with 0 failures/errors/skips. Evidence: `qa-artifacts/phase6/evidence/a11y_focused_test_summary.txt` and `unit-test-xml-a11y-focus/TEST-com.example.Phase6OrderTrackingRobolectricTest.xml`.

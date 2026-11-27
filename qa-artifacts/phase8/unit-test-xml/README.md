# Phase 8 unit-test XML evidence

Captured from the final local verification runs on 2026-11-27.
Counts are derived directly from the copied `TEST-*.xml` files.

| Module | XML files | Tests | Failures | Errors | Skipped | Destination |
|---|---:|---:|---:|---:|---:|---|
| app | 41 | 230 | 0 | 0 | 0 | `qa-artifacts/phase8/unit-test-xml/app` |
| backend | 29 | 86 | 0 | 0 | 0 | `qa-artifacts/phase8/unit-test-xml/backend` |
| shared/contracts | 8 | 40 | 0 | 0 | 0 | `qa-artifacts/phase8/unit-test-xml/shared__contracts` |

Android: `qa-artifacts/phase8/visual/builder/w6_modifier_wiring.log` records the final
`clean :app:testDebugUnitTest` run (230 tests, 0 failures/errors/skipped).

Backend/shared: `qa-artifacts/phase8/backend/w8_final_kotlin_backend.log` records the final
:backend:test + :shared:contracts:test run (86 + 40 tests, 0 failures/errors).

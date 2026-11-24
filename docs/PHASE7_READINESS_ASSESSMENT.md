# PIZZORA Phase 7 — Readiness Assessment

**Date:** 2026-11-24\
**Branch:** `ui-ux-changes`\
**Assessed HEAD:** `61914d962cbf5a2badf44d95a3e555e980ee73dd`\
**Assessment type:** Read-only repository and evidence audit. No Phase 7 implementation, plan edit, commit, push, merge, deployment, or infrastructure change was made.

---

## 1. Executive summary

This assessment treats `public/implementation_plan.md` as the sole source of Phase 7 scope and the accepted Phase 6 closure evidence as the baseline. The canonical Phase 7 work is bounded to four files — `CustomerProfileScreen.kt`, `NotificationsScreen.kt`, `UpdateScreens.kt`, and `PizzoraNavHost.kt` — with no backend, Room, API, or migration changes (`public/implementation_plan.md:L888-L901`).

The repository is clean, synchronized with `origin/ui-ux-changes`, and contains no source changes after the final Phase 6 implementation commit `c1f9f39`. The only commits after that point add the Phase 6 report, one plan status line, and final QA evidence. Phase 6 is closed with 191/191 tests green and 7/7 canonical visual captures passing.

The implementation seams required by Phase 7 already exist: `checkPincodeServiceability(pincodeOverride)`, `NotificationInboxEntity.referenceId`, `OrdersViewModel.openOrderDetail(orderId)`, the shell overlay `SnackbarHost`, existing sign-out confirmation copy, and all preservation-critical test tags. The remaining work is UI/navigation composition, state restoration, test coordination, and live visual/accessibility evidence.

No product-scope ambiguity was found that prevents work from starting. The plan does contain stale historical lines, one state-symbol naming mismatch, and a test-update gap; all are explicitly reconciled in Section 6 and are handled as execution conditions rather than new scope.

The single conclusion is recorded in Section 15.

---

## 2. Assessment method and evidence sources

The assessment used direct, read-only inspection of:

- Git state: `git status`, `git diff --quiet HEAD`, `git rev-parse`, `git log`, `git worktree list`, and `git diff --stat c1f9f39..HEAD`.
- Canonical scope: `public/implementation_plan.md`, including Phase 7 `L888-L901`, Sections 5.9–5.11 `L370-L399`, the visual blueprint `L417`, tag map `L698-L713`, exit gate `L761-L766`, visual matrix `L956-L960`, decision table `L991-L1003`, and revision history `L1033-L1143`.
- Phase 6 baseline: `docs/PHASE6_IMPLEMENTATION_PROGRESS_REPORT.md`, `qa-artifacts/phase6/evidence/`, and the Phase 6 status line at plan `L886`.
- Source seams: the four affected files plus `ProfileViewModel.kt`, `SessionTransitionCoordinator.kt`, `Daos.kt`, `NotificationDto.kt`, `Entities.kt`, `NotificationRepository.kt`, `NotificationHelper.kt`, `OrdersViewModel.kt`, `OrdersScreen.kt`, and `strings.xml`.
- Regression suites: `Phase15ZonesAddressesSlotsRobolectricTest.kt`, `Phase16NotificationsRobolectricTest.kt`, and `app/src/test/java/com/example/update/`.
- Build/CI/deployment: `app/build.gradle.kts`, `.github/workflows/security-and-ci.yml`, `.github/workflows/phase4-android-emulator-qa.yml`, `.railway/railway.ts`, `release-policy.json`, `metadata.json`, `VERSION`, and `.env.example`.
- Environment check: `adb devices -l`, installed package check, SDK/display query, and local health check at `http://127.0.0.1:8080/api/v1/health/ready`.

Final Phase 6 JUnit XML was re-aggregated read-only: `qa-artifacts/phase6/evidence/unit-test-xml-final-verified/` contains 34 XML files with 191 tests, 0 failures, 0 errors, and 0 skipped. No Gradle build or test task was executed during this assessment; no Phase 7 code exists to execute.

---

## 3. Repository state and change control

| Item | Verified state |
|---|---|
| Current branch | `ui-ux-changes` |
| HEAD | `61914d962cbf5a2badf44d95a3e555e980ee73dd` |
| Working tree | Clean; `git diff --quiet HEAD` exited `0` |
| Remote sync | HEAD equals `origin/ui-ux-changes`; `origin/main` is `415c17bbb38d80abfbf4ed1e22e5d17b2ca44191` |
| Divergence from `main` | `origin/main...HEAD` = `18 23` |
| Post-Phase-6 source changes | None after `c1f9f39` |
| `c1f9f39..HEAD` diff | Only `docs/PHASE6_IMPLEMENTATION_PROGRESS_REPORT.md`, one plan status line, and final evidence logs/XML |
| `git diff --check` | Exit `0`, empty output |

Other worktrees were inspected and left untouched:

- `/workspace/worktrees/pizzabi/steel-bell/pizzabi` — `main` at `415c17b`.
- `/workspace/worktrees/pizzabi/vivid-gate/pizzabi` — `mattpocock` at `9442701`, one behind origin.
- `/home/remote/.local/share/opencode/worktree/ea3529/mattpocock` — prunable detached worktree at `4752c67`.
- Local `feat/composite-components-20` at `7d2574f`, five ahead of origin.

No branch, worktree, ref, lockfile, or build file was modified.

---

## 4. Phase 6 baseline preservation

Phase 6 remains the accepted baseline and was not reopened.

Evidence accepted from `docs/PHASE6_IMPLEMENTATION_PROGRESS_REPORT.md`:

- Final forced full suite: 191 tests, 0 failures, 0 errors, 0 skipped across 34 XML files.
- Focused payment sheet: 10/10; focused tracking/accessibility: 5/5.
- `:app:compileDebugUnitTestKotlin` and `:app:assembleDebug`: BUILD SUCCESSFUL.
- Live emulator QA: 7/7 canonical Section 13.2 captures PASS, plus conditional expired-state and supplemental diagnostic PASS.
- Independent review: no BLOCKER or MAJOR findings; two MINOR findings closed.
- P6-001 warning countdown fix verified live at `320dp`/`1.5x`.
- Plan status line `L886` records **Phase 6 Exit Gate: SATISFIED**.

Phase 6 limitations remain explicitly accepted and are not Phase 7 blockers: exact TalkBack audio is not capturable by `uiautomator`; fixture proxy was used instead of live Razorpay; no real payment/refund, physical-device haptics, real airplane-mode WebSocket reconnection, or live `am kill` restoration was performed.

---

## 5. Canonical Phase 7 scope

Only `public/implementation_plan.md` was used to extract scope.

### 5.1 Files and dependencies

- Screens affected: `CustomerProfileScreen.kt`, `NotificationsScreen.kt`, `UpdateScreens.kt`, `PizzoraNavHost.kt` (`L890`).
- Components affected additionally define the notification-to-order callback in `CustomerRoleShell` (`L893-L894`).
- Dependencies: Phases 1, 2, and 6 (`L899`), all satisfied by the current baseline.
- Backend dependencies: none (`L895`); rollback is single-PR `git revert` with zero Room or API changes (`L901`).

### 5.2 Profile and saved addresses (`L370-L379`, `L891-L892`)

- Monogram avatar derived from `profile.fullName` initials.
- Localized phone label via `strings.xml`.
- Move Sign Out to a clearly separated bottom Account Actions section with accurate `SessionScopeCleaner` copy.
- Confirm Delete Address, highlighting the default-address case.
- Demote Pincode `Check` to secondary visual weight; keep Add Address primary.
- Add one-tap `Check delivery` on saved addresses using the existing `pincodeOverride` seam.
- Replace trapped `ProfileModalOverlay` with full-window Material 3 `ModalBottomSheet` or `Dialog`; scrim top and bottom bars; use `rememberSaveable`, phone/number keyboard types, IME actions, and inline validation.
- Preserve `edit_profile_button` and all `profile_*` tags reachable in Robolectric trees.

### 5.3 Notifications (`L381-L391`, `L891-L892`, `L894-L897`)

- Make `ORDER_UPDATE`, `PAYMENT_ALERT`, and `REFUND_ALERT` cards clickable; mark read and open the linked order by `referenceId`.
- Add `onOpenOrderFromNotification(orderId: String)` in `CustomerRoleShell`: mark read, close notifications, switch to Orders, call `ordersViewModel.openOrderDetail(orderId)`, and show a snackbar fallback if unavailable.
- Move `NotificationPreferencesCard` below the inbox feed as `"Alert Settings"`, inside `notifications_list`; add a TopAppBar settings jump icon.
- Humanize categories: Order/Payment/Refund/Offer/Notice with semantic, non-veg-green hues.
- Replace raw UTC timestamps with relative/local IST copy.
- Replace translucent unread fill with solid `selectedSurface` plus coral left bar/dot.
- Differentiate empty states for All versus Unread.
- Center content with `widthIn(max = 720.dp)` on tablets.
- Preserve `notifications_list`, filters, permission tags, `notification_card_<id>`, `notification_mark_read_<id>`, `pref_order_updates_switch`, and `pref_promo_alerts_switch`.

### 5.4 Updates (`L393-L399`, `L892`, `L956`)

- Use basil `secondary` for `UpToDate` and `Completed` icons/text.
- Make optional and mandatory update surfaces vertically scrollable at large font scales so release notes and buttons are reachable.
- Preserve all `update_*` tags asserted by `Phase20UpdateRobolectricTest`.

### 5.5 Exit gate

The mandatory four-part Phase Exit Gate at `L761-L766` applies in full: functional tests, visual sign-off, accessibility verification, and regression verification. Compile-only or test-only completion is not sufficient.

---

## 6. Exact ambiguities and documentation debt

No unresolved product-scope ambiguity was found. The following exact plan/source mismatches must be handled during implementation; none requires a new stakeholder decision.

| # | Exact issue | Resolution used for readiness |
|---|---|---|
| 1 | `L1068` revision history still mentions Phase 7 `"collapsible/sheet Notification Preferences"`; `L1003` and `L1071` settle the bottom `"Alert Settings"` footer plus TopAppBar jump icon. | Treat `L1068` as historical text describing an already-corrected contradiction. Use Decision #4. Propose a separate plan cleanup later; do not edit the plan now. |
| 2 | `L1109-L1143` execution history states manual QA pending and Exit Gate OPEN for earlier phases. | Superseded by Phase 5/6 completion evidence and `L886`. Documentation debt only; do not reopen Phase 6. |
| 3 | `L710` still labels Decision #5 as pending before Phase 2; `L1000` records it approved and implemented in Phase 2. | Stale table text; no action for Phase 7 beyond noting it in the separate plan cleanup. |
| 4 | `L794` lists Decision #5 as a Phase 2 dependency gate; `L1000` resolves it. | Historical phase-entry text; already satisfied. |
| 5 | `L894` names `CustomerTab.ORDERS`, but `PizzoraNavHost.kt:L410` uses `selectedIndex: Int`; no `CustomerTab` enum exists. | Semantic target is the Orders tab. Use `selectedIndex = 2`, matching existing checkout handoff at `PizzoraNavHost.kt:L535-L543`. |
| 6 | `L897` says preserve Phase 15 with zero breakage while `L891`/`L374-L375` require confirmed address deletion. Current `Phase15...L415-L417` clicks delete and immediately asserts removal. | Preserve tags and assertions, but intentionally insert the new confirmation click in that test. This is a coordinated test update, not a new requirement. |
| 7 | `L898` requires update modals at `1.5x–2.0x`; visual matrix `L956` names only `1.5x`. | Validate both `1.5x` and `2.0x`; the stricter acceptance text governs. Record `2.0x` as a supplemental capture if tooling allows. |
| 8 | `L371` says Sign Out has no confirmation, but current `CustomerProfileScreen.kt:L101,L261-L274` already has `PizzoraConfirmationDialog`. | Preserve the existing confirmation and accurate copy; move it to the bottom Account Actions section and keep `profile_sign_out_button` / `confirm_sign_out_button`. |

Proposed plan changes, for a later separately authorized commit only: mark stale history lines as superseded; align Decision #5 rows; rename `CustomerTab.ORDERS` to “Orders tab (`selectedIndex = 2`)”; add the Phase 15 confirmation-click row to Section 10.3; and add `2.0x` to the Phase 7 visual matrix. No plan file was changed in this assessment.

---

## 7. Profile and saved-address seam audit

| Requirement | Current evidence | Gap / readiness impact |
|---|---|---|
| Monogram avatar | `CustomerProfileScreen.kt:L304-L314` uses `Icons.Filled.Person`. | Replace with initials-derived monogram; no data/model change needed. |
| Localized phone label | Hardcoded `"Phone: ..."` at `L344-L349`; `strings.xml:L14` already has `label_phone = "Phone Number"`. | Use the existing string resource. |
| Sign-out confirmation | Present at `L101,L261-L274`; accurate body at `strings.xml:L246`; `DefaultSessionScopeCleaner` clears carts and inbox at `SessionTransitionCoordinator.kt:L28-L34`. | Preserve; relocate to bottom Account Actions. |
| Confirmed address delete | `SavedAddressCard` calls immediate `onDeleteAddress` at `L217-L220`; `ProfileViewModel.kt:L268-L291` deletes immediately. | Add confirmation state/dialog and update Phase 15 interaction. |
| Default-address warning in dialog | Not present. | Derive from `address.isDefault`; no backend change. |
| Pincode button hierarchy | Filled `Button` at `CustomerProfileScreen.kt:L391-L399`; Add Address is also a filled `Button` at `L182-L189`. | Demote Pincode `Check` to outlined/tonal. |
| Check delivery shortcut | `SavedAddressCard` at `L463-L553` has edit/default/delete only. `ProfileViewModel.kt:L303-L305` already accepts `pincodeOverride`. | Add one icon/text action that calls the existing method. |
| Full-window modal | `ProfileModalOverlay` at `L736-L785` is a custom scrim `Surface` inside the profile screen; not Material 3 `ModalBottomSheet`/`Dialog`. | Replace or host at root so top/bottom bars are scrimmed and focus is trapped/restored. |
| `rememberSaveable` | Edit fields use `remember` at `L563-L564`; address fields use `remember` at `L630-L636`. | Switch all fields to `rememberSaveable`; preserve rotation state. |
| Inline validation / keyboard | `ProfileViewModel.kt:L164-L170` validates on save; text fields at `L596-L609` and `L675-L717` have no `KeyboardType` or `ImeAction`. | Add composable-level field validation, phone/number keyboard, and IME actions without weakening ViewModel validation. |
| Tablet width cap | Already present at `CustomerProfileScreen.kt:L112-L117` (`widthIn(max = 720.dp)`). | Preserve. |

Preservation-critical tags in this surface: `customer_profile_screen`, `profile_lazy_column`, `profile_account_card`, `profile_full_name_text`, `edit_profile_button`, `profile_sign_out_button`, `confirm_sign_out_button`, `profile_phone_text`, `profile_pincode_input`, `profile_check_pincode_button`, `profile_pincode_result_banner`, `profile_add_address_button`, `profile_address_card_<id>`, `profile_edit_address_<id>`, `profile_delete_address_<id>`, `profile_set_default_<id>`, `profile_edit_name_input`, `profile_edit_phone_input`, `profile_edit_save_button`, `profile_addr_*_input`, `profile_addr_save_button`, and `profile_bottom_spacer`.

---

## 8. Notifications seam audit

| Requirement | Current evidence | Gap / readiness impact |
|---|---|---|
| Preferences below inbox | `NotificationsScreen.kt:L225-L232` places `NotificationPreferencesCard` before filters and cards. | Move to footer item after inbox/empty state while keeping it inside `notifications_list`. |
| TopAppBar settings jump | Actions at `L108-L142` contain only Mark all read and Refresh. | Add a settings icon that scrolls to the footer tag. |
| Clickable linked notifications | `NotificationInboxCard` signature at `L396-L400` only receives `onMarkRead`; card at `L407-L410` is not clickable. | Add deep-link callback and trailing chevron for eligible categories. |
| `referenceId` source | `NotificationDto.kt:L36`, `Entities.kt:L96`, `NotificationRepository.kt:L228`, and `NotificationHelper.kt:L81,L125` already carry/expose it. | No backend or Room work; UI plumbing only. |
| Humanized category chips | `L443` renders `item.category.replace('_', ' ')`. | Add explicit category-to-label/color mapping; avoid veg-green for non-veg alerts. |
| Relative/IST timestamps | `L463` renders `createdAtIso.take(19).replace('T', ' ')`. | Add API 24-safe relative/local IST formatter; follow Phase 6 pattern. |
| Unread visual | `L401-L405` uses `primaryContainer.copy(alpha = 0.45f)`. | Use solid `selectedSurface` plus coral left bar; preserve unread dot tag. |
| Context-aware empty state | `L259-L288` uses one generic message. | Branch on `uiState.unreadOnlyFilter`. |
| Tablet width cap | `LazyColumn` at `L145-L151` has no `widthIn`. | Center content with `widthIn(max = 720.dp)`; keep `notifications_list` as the scroll container. |
| Snackbar hosting | `NotificationsScreen.kt:L88-L107` defines a local `SnackbarHost`; shell overlay also defines one at `PizzoraNavHost.kt:L948-L954`. | Use the shell overlay host for deep-link fallback to avoid duplicate presentation; do not add a third host. |

Preservation-critical tags in this surface: `notifications_screen`, `notifications_list`, `notifications_back_button`, `notifications_mark_all_read_button`, `notifications_refresh_button`, `notifications_permission_card`, `notifications_request_permission_button`, `notifications_error_card`, `notifications_filter_all`, `notifications_filter_unread`, `notifications_empty_state`, `notification_card_<id>`, `notification_unread_dot_<id>`, `notification_mark_read_<id>`, `notifications_preferences_card`, `pref_order_updates_switch`, and `pref_promo_alerts_switch`.

---

## 9. Navigation and deep-link seam audit

- `PizzoraNavHost.kt:L410` stores the selected tab as `selectedIndex: Int`; `L412` stores notification visibility as `isNotificationsOpen: Boolean`. No Orders enum exists, so the canonical `CustomerTab.ORDERS` handoff maps to `selectedIndex = 2`.
- Existing checkout handoff at `L535-L543` already sets `selectedIndex = 2`, calls `ordersViewModel?.showCreatedOrder(...)`, and triggers sync. This is the correct precedent for the notification handoff.
- Notification overlay invocation at `L924-L947` passes refresh/filter/read/preference callbacks but no `onOpenOrderFromNotification`.
- `OrdersScreen` already exposes `onOpenOrder` and `OrdersViewModel.openOrderDetail(orderId)` at `OrdersViewModel.kt:L147-L184`; order detail renders whenever `selectedOrder != null` (`OrdersScreen.kt:L115-L129`). No new ViewModel method is required.
- Existing overlay `SnackbarHostState` and host are available at `PizzoraNavHost.kt:L419-L420,L455-L457,L948-L954` for unavailable-order fallback.
- `NotificationHelper.EXTRA_REFERENCE_ID` at `L125` proves the Android notification path already carries the same identity; Phase 7 only needs in-app card and shell wiring.
- Deep-link accessibility label required by `L896`: use a semantic description such as `"Order, unread, Double-tap to view order"`, preserving visible title/body text.

Required callback sequence: mark notification read → close `isNotificationsOpen` → set `selectedIndex = 2` → call `ordersViewModel.openOrderDetail(referenceId)` → if fetch fails, show overlay snackbar and remain on Orders list.

---

## 10. Update UI seam audit

| Requirement | Current evidence | Gap / readiness impact |
|---|---|---|
| Basil `UpToDate` icon | `UpdateScreens.kt:L143-L160` uses `MaterialTheme.colorScheme.primary` at `L151`. | Change icon tint to `secondary`. |
| Basil `Completed` text | `L220-L226` uses `primary` at `L224`. | Change to `secondary`. |
| Optional modal scrolling | `L476-L569` uses custom scrim `Box`/`Surface`; only `ReleaseNotesSection` text scrolls at `L595-L610`. | Make the whole modal vertically scrollable so buttons remain reachable at `1.5x–2.0x`. |
| Mandatory screen scrolling | `L376-L474` centers a fixed `Column`; no whole-screen scroll modifier. | Add vertical scroll while preserving mandatory `BackHandler` block and `update_mandatory_screen`. |
| Tag preservation | Tags include `update_settings_card`, `update_version_text`, `update_status_text`, `update_check_button`, `update_dialog`, `update_release_notes_text`, `update_apk_size_text`, `update_progress_bar`, `update_later_button`, `update_cancel_button`, `update_mandatory_screen`, `update_error_banner`, `update_retry_button`, and more. | Keep every existing tag; add new tags only if needed for scroll assertions. |
| Current test coverage | `Phase20UpdateRobolectricTest.kt:L347-L392` asserts card, dialog, progress, dismiss, mandatory blocker, error banner, and retry. `Phase20UpdateUnitTest.kt` covers checker/metadata/repository/downloader/verifier. | No color or large-font scroll test exists; add focused UI tests. |

The update state machine itself is outside Phase 7 scope and must remain unchanged.

---

## 11. Test and tag preservation readiness

Baseline: 191/191 tests green in 34 XML files. Phase 7 must keep that number green after intentional, documented updates.

### 11.1 Phase 15

`Phase15ZonesAddressesSlotsRobolectricTest.profileAddressBookCrudAndStandalonePincodeChecker` verifies:

- `profile_full_name_text` and edit profile flow (`L376-L385`).
- `profile_pincode_input`, `profile_check_pincode_button`, and result banner (`L388-L397`).
- Add address, set default, delete first address (`L400-L418`).

The delete step at `L415-L417` must gain a confirmation click. Preserve all tags and assertions; do not remove or weaken the test.

### 11.2 Phase 16

`Phase16NotificationsRobolectricTest` verifies:

- Permission card and grant callback (`L344-L351`).
- Unread filter hides read cards (`L353-L361`).
- `notifications_list.performScrollToNode(hasTestTag("pref_order_updates_switch"))` and toggle (`L363-L371`).
- Mark read and unread count (`L373-L388`).

The preference switch must remain inside `notifications_list` at the bottom so `L365-L366` continues to work unchanged. If a settings icon is added, it must be an additional affordance, not the only path.

### 11.3 Update suite

`Phase20UpdateRobolectricTest` must remain green with all existing tags. Add tests for:

- `UpToDate` and `Completed` using `secondary` semantics.
- Optional modal scroll reachability at `1.5x` and `2.0x`.
- Mandatory screen scroll reachability without allowing dismissal.

### 11.4 New Phase 7 tests required

- Notification-to-order handoff: correct `referenceId`, mark read, close overlay, Orders tab selected, exact order detail opened.
- 404/offline deep-link fallback: snackbar shown, no crash, no stale detail.
- Confirmed address delete, including default-address copy and cancel path.
- Unread-empty versus all-empty messaging.
- `rememberSaveable` profile/address field restoration.
- Settings jump icon reaches bottom `"Alert Settings"` while `pref_order_updates_switch` remains scrollable.

---

## 12. Visual, accessibility, and exit-gate readiness

Canonical Phase 7 visual matrix `L956` requires six representative captures:

1. Profile monogram + addresses — dark `412dp/1.0x` and light `320dp/1.5x`.
2. Notifications inbox unread/read + semantic tags — dark `412dp/1.0x`.
3. Notifications bottom `"Alert Settings"` — light `840dp/1.0x`, centered within `720dp`.
4. `AppUpdatesSettingsCard` green `UpToDate` — dark `360dp/1.0x`.
5. `OptionalUpdateModal` scrollable — light `320dp/1.5x`.

Conditional captures `L960` include Profile Delete Address and Sign Out confirmations, Notifications unread-empty state, and Updates landscape/download-error retry. Add `2.0x` update-modal evidence per the stricter acceptance text.

Accessibility requirements:

- Focus trapping and restoration for profile/address dialogs.
- `>=48dp` touch targets for settings jump, deep-link rows, confirmation buttons, and update actions.
- Deep-link rows expose `"Order, unread, Double-tap to view order"` semantics.
- WCAG 2.2 AA contrast for semantic category chips and solid unread surfaces.
- Verify no clipped update buttons at `320dp/1.5x` or `2.0x`.
- Exact spoken TalkBack audio remains a documented observation limit; use `uiautomator` traversal and semantic dumps as evidence.

Environment: `emulator-5554` is available, package `com.aistudio.pizzora.xkqzmw` is installed, SDK 36, display `1080x2400`. The local API at `127.0.0.1:8080` is not running (`HTTP 000`), so live visual QA must use a deterministic fixture proxy or a separately supplied backend. No production infrastructure may be touched.

---

## 13. CI/CD, backend, and deployment boundary

- `app/build.gradle.kts:L303-L313` activates dependency locking for Android classpaths. No lockfile change is needed for Phase 7.
- `.github/workflows/security-and-ci.yml` Stage 5 runs `:app:generateUniFfiKotlinBindings :app:testDebugUnitTest -PpizzoraRequireRust=true` (`L505`) and debug assembly at `L736`.
- `.github/workflows/phase4-android-emulator-qa.yml` runs unit tests (`L126`), debug APK (`L191`), KVM-gated emulator stages, install/launch, instrumentation, and screenshot capture (`L519-L728`). It documents that full screenshots require seeded backend data (`L654-L766`).
- `.railway/railway.ts:L3-L19` explicitly scopes this repository to API/worker services with `partial = "pizzabi"`; PostgreSQL, Redis, volumes, domains, and dashboard secrets are outside the partial. Phase 7 must not invoke Railway apply, template provisioning, or deployment.
- `release-policy.json`, `metadata.json`, and `VERSION` are release metadata, not Phase 7 scope. No version bump, release tag, signed release, or GitHub Release publication is authorized here.
- No Kotlin backend, Rust backend, migration, contract, Room schema, or API change is required or permitted by the canonical plan (`L895`, `L901`).

---

## 14. Risk register and blockers

| ID | Risk / issue | Severity | Mitigation | Status |
|---|---|---|---|---|
| R1 | Phase 15 immediate-delete assertion conflicts with confirmed delete. | Medium | Update that test intentionally to click the confirmation tag; preserve all other assertions and tags. | Condition; not a product-scope blocker. |
| R2 | Replacing `ProfileModalOverlay` can hide `edit_profile_button` or `profile_*` tags. | High | Host at root/full-window; add Robolectric reachability test before removing old overlay. | Condition; must be verified before merge. |
| R3 | `pref_order_updates_switch` could become unreachable after moving preferences. | High | Keep footer inside `notifications_list`; leave Phase 16 L365-L366 unchanged. | Condition; must be verified. |
| R4 | Deep-link fallback snackbar has ambiguous host choice. | Low | Reuse existing shell overlay `SnackbarHostState`; do not add another host. | Resolved by engineering default. |
| R5 | Large-font update buttons may clip because only release notes scroll today. | Medium | Scroll the whole modal/screen; capture at `320dp/1.5x` and `2.0x`. | Condition; part of exit gate. |
| R6 | Raw category/timestamp formatting may be missed. | Medium | Central formatter with unit tests; verify captured UI XML contains no raw UTC/category tokens. | Condition; part of implementation. |
| R7 | Local API unavailable for live QA. | Medium | Use the same fixture-proxy pattern accepted for Phase 6; no production endpoint. | Condition; environment ready with emulator. |
| R8 | Stale plan history may be mistaken for current gate status. | Low | Use Section 6 reconciliation; propose separate plan cleanup only after approval. | Non-blocking documentation debt. |
| R9 | Accidental Phase 6 reopening or destructive git operation. | High | Freeze baseline at `61914d9`; no revert/reset/rewrite/force-push/merge/deploy. | Condition; strict execution boundary. |

Blocking implementation-start issue: none found. All items above are either execution conditions, planned test updates, or documentation debt.

---

## 15. Workstreams, agent recommendation, approvals, and final verdict

### 15.1 Recommended workstreams

1. **Profile/address UI** — monogram, localized phone, bottom Account Actions, confirmed delete, secondary Pincode `Check`, `Check delivery`, full-window saveable dialogs, inline validation.
2. **Notifications inbox** — deep-link cards, humanized chips, relative/IST time, unread surface, context-aware empty state, `720dp` cap, bottom `"Alert Settings"` footer, TopAppBar jump icon.
3. **Navigation handoff** — add `onOpenOrderFromNotification` to `CustomerRoleShell`, close overlay, switch to Orders (`selectedIndex = 2`), call `openOrderDetail`, and use the overlay snackbar for failure.
4. **Update polish** — basil `secondary` states and fully scrollable optional/mandatory surfaces at large font scales.
5. **Regression and QA** — coordinated Phase 15/16/update test updates, new Phase 7 tests, full suite, debug APK, six mandatory captures, conditional captures, focus/TAB/TalkBack sanity evidence.
6. **Documentation hygiene** — separately proposed plan corrections from Section 6; no plan edit in the Phase 7 implementation PR unless explicitly authorized.

### 15.2 Agent recommendation

No agent was spawned or prompted during this assessment. For implementation, recommend this division:

- **Primary coordinator:** owns git state, scope decisions, final evidence review, and no-destructive-operation boundary.
- **`test-engineer`:** builds/executes the Phase 7 test matrix and preserves Phase 15/16/update tags; report executed versus inspected separately.
- **`android-ui`:** read-only Compose/visual audit of the four affected files and Section 5.12 blueprint adherence.
- **`android-qa`:** live emulator fixture-proxy QA, screenshot/XML capture, and focus traversal after implementation.
- **`backend`:** read-only confirmation that `referenceId` contracts remain unchanged; no backend edits.
- **`reviewer`:** independent Phase Exit Gate review before completion.
- **`git-docs`:** only if the separately proposed plan cleanup is approved.

### 15.3 Required approvals before any implementation

1. Explicit approval to begin Phase 7 on `ui-ux-changes` at `61914d9`.
2. Explicit approval for any commit/push; none was made here.
3. Explicit, separate approval if stale plan-history corrections should be committed; `public/implementation_plan.md` was not modified here.
4. Explicit approval for fixture-proxy QA setup; no production endpoint, credentials, Railway apply, deployment, merge into `main`, or release tag is authorized.
5. Confirmation that Phase 6 remains closed and is not to be reopened.

### 15.4 Final verdict

**PHASE 7 READY WITH CONDITIONS**

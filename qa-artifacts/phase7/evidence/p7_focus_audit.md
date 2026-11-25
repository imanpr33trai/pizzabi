# Phase 7 focus traversal evidence

Captured with `input keyevent 61` (TAB) and `uiautomator dump` on the fixture-backed emulator at 412dp / 1.0x. Compose dialog focus was traversed cyclically; each focused node was compared with the dialog content container bounds from the same dump.

| Step | Focused node | Bounds (px) | Within dialog |
|---|---|---:|---|
| p7_profile_edit_focus_01_ui | Priya Customer | `[115,671][964,881]` | YES |
| p7_profile_edit_focus_02_ui | +919876543210 | `[115,960][964,1170]` | YES |
| p7_profile_edit_focus_03_ui | android.view.View | `[507,1564][733,1689]` | YES |
| p7_profile_edit_focus_04_ui | android.view.View | `[754,1564][964,1689]` | YES |
| p7_profile_edit_focus_05_ui | Priya Customer | `[115,671][964,881]` | YES |
| p7_profile_edit_focus_06_ui | +919876543210 | `[115,960][964,1170]` | YES |

Traversal stayed inside the dialog container: **YES**.

After dismissing the dialog via `Close`, the host-level `uiautomator` focus attribute showed: android.view.View `[945,150][1070,275]`. This does not expose the Compose `FocusRequester` restoration reliably after the dialog window is disposed.

Compose-level restoration is verified by the passing Robolectric test `dismissingEditProfileDialogRestoresFocusToEditButton`, which asserts that `edit_profile_button` is focused after dismissal. Exact TalkBack speech audio is not capturable through `uiautomator`; semantics are additionally evidenced by content descriptions/state descriptions in the saved XML and by Robolectric semantics assertions.

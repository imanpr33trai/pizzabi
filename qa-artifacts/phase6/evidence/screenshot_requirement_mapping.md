# Phase 6 screenshot → requirement mapping

Source of truth: `public/implementation_plan.md` L954 (Section 13.2 Phase 6, 7 captures) and L959 (conditional payment-expired capture).

Verdicts are live-emulator verdicts from the rebuilt APK (`sha256 889997752cd7698fffb797cd3b43b8f8b790e16be14e00e6bf2a72646bf5feb1`). Every PNG below has a same-screen `evidence/<basename>_ui.xml` dump. Payment captures were recaptured after the P6-001 layout fix; non-payment captures were unaffected and retained.

| # | Requirement / plan text | Screenshot | Same-screen XML | Live observed evidence | Verdict |
|---|---|---|---|---|---|
| R1 | `RazorpayPaymentSheet` normal, dark `412dp/1.0x`; humanized copy, no default raw key/enum, CTA reachable | `p6_payment_normal_dark_412dp_1.0x.png` (sha256 `30f3b8b5…c898c3`) | `evidence/p6_payment_normal_dark_412dp_1.0x_ui.xml` | `Razorpay Payment Checkout`; `Paying for Order PZ-P6-1001`; `₹562.90`; `Complete payment within`; `11:57`; `Pay ₹562.90 Securely`; `Payment Reference`; Pay container `[40,1510][784,1614]` enabled=true | **PASS** |
| R2 | `<=3m` amber countdown, light `320dp/1.5x`; warningContainer contrast >=4.5:1; authoritative MM:SS visible; CTA not clipped | `p6_payment_warning_light_320dp_1.5x.png` (sha256 `e6db3c3d…002342`) | `evidence/p6_payment_warning_light_320dp_1.5x_ui.xml` | `Expires in — complete payment now` `[64,115][430,268]`; `01:57` `[446,163][576,221]`; `2 minutes remaining`; amber RGB `(254,243,199)` with text RGB `(120,53,15)` = 8.15:1; CTA `[40,977][600,1081]` = 280x52dp, on-screen; no `rzp_` or raw enum; P6-001 resolved | **PASS** |
| R3 | Orders Active/Past tabs, dark `412dp/1.0x`; humanized statuses/payment labels | `p6_orders_active_past_tabs_dark_412dp_1.0x.png` (sha256 `caf138a3…69a`) | `evidence/p6_orders_active_past_tabs_dark_412dp_1.0x_ui.xml` | `Your Orders`, `Active (4)`, `All (5)`, `Past (1)`, `Payment Pending`, `Order Placed`, `Out for Delivery`, `Paid in Cash`; no raw enums | **PASS** |
| R4 | Orders Active/Past tabs, light `320dp/1.0x`; labels fit narrow width | `p6_orders_active_past_tabs_light_320dp_1.0x.png` (sha256 `6ab5fe67…403c019`) | `evidence/p6_orders_active_past_tabs_light_320dp_1.0x_ui.xml` | Tabs and humanized labels visible at 640x1280; no truncation or raw enum | **PASS** |
| R5 | OrderDetail connected vertical stepper + celebratory banner, dark `412dp/1.0x` | `p6_order_detail_stepper_celebration_dark_412dp_1.0x.png` (sha256 `04cac2d1…8238`) | `evidence/p6_order_detail_stepper_celebration_dark_412dp_1.0x_ui.xml` | `PZ-P6-1006`; `Order confirmed! The kitchen has received your order.`; full stepper; `Current Status: Order Placed`; IST `3:11 PM`; continuous rail pixels | **PASS** |
| R6 | OrderDetail connected vertical stepper + celebratory banner, light `412dp/1.5x` | `p6_order_detail_stepper_celebration_light_412dp_1.5x.png` (sha256 `c98c9dde…6feb`) | `evidence/p6_order_detail_stepper_celebration_light_412dp_1.5x_ui.xml` | Same celebration + seven stepper labels at large font; IST timestamp; continuous rail pixels RGB `(45,106,79)` | **PASS** |
| R7 | `CancelOrderDialog` with refund disclosure, dark `360dp/1.0x` | `p6_cancel_dialog_refund_disclosure_dark_360dp_1.0x.png` (sha256 `0c449c2d…497f3b`) | `evidence/p6_cancel_dialog_refund_disclosure_dark_360dp_1.0x_ui.xml` | `Cancel This Order?`; `Paid via Razorpay — refund will be processed to your original payment method after cancellation.`; reasons/input/confirm/close; all focus stops >=48dp in `talkback_cancel_dialog_traversal.txt` | **PASS** |

Conditional and supplemental captures:

| # | Requirement | Screenshot | Same-screen XML | Live observed evidence | Verdict |
|---|---|---|---|---|---|
| C1 | Payment expired (`00:00`), dark `412dp/1.0x` (plan L959) | `p6_payment_expired_dark_412dp_1.0x.png` (sha256 `cdda12a4…e133`) | `evidence/p6_payment_expired_dark_412dp_1.0x_ui.xml` | `Payment window expired`; `00:00`; Pay CTA container `[40,1510][784,1614]` enabled=false; no real payment/refund | **PASS** |
| D1 | Diagnostic: warning timer at light `320dp/1.0x` (not canonical) | `p6_payment_warning_light_320dp_1.0x.png` (sha256 `722776be…427a5`) | `evidence/p6_payment_warning_light_320dp_1.0x_ui.xml` | `Expires in — complete payment now`; `02:32`; `5 minutes remaining`; CTA on-screen | **PASS** (supplemental) |

Cross-cutting plan criteria:

| Criterion | Evidence | Verdict |
|---|---|---|
| Zero default raw enums (`OUT_FOR_DELIVERY`, `COD_PENDING`, `AWAITING_CUSTOMER_PAYMENT`) | `grep -lE … qa-artifacts/phase6/evidence/*_ui.xml` returned `NO_RAW_ENUMS_OR_GATEWAY_IDS_IN_ANY_UI_XML` | **PASS** |
| Zero default `rzp_` gateway/attempt identifiers; `Payment Reference` collapsed | Same grep plus payment XML text inventory | **PASS** |
| All times IST | UI labels such as `3:11 PM`, `Today, 4:10 PM`, `22 Nov, 3:10 PM`; checkout `Schedule Slot (IST)`; formatter uses `Asia/Kolkata` | **PASS** |
| Continuous vertical stepper rails | Pixel scan at x=84px on both stepper PNGs; unbroken colored rail between step circles | **PASS** |
| Warning container contrast >=4.5:1 | RGB `(120,53,15)` on `(254,243,199)` = 8.15:1 | **PASS** |
| Payment CTA not clipped at `320dp/1.5x` | CTA `[40,977][600,1081]` = 280x52dp inside 640x1280; pixel fill RGB `(200,62,43)` + white glyphs | **PASS** |
| Refund-aware cancel modal | CAPTURED order PZ-P6-1002 produced exact refund disclosure | **PASS** |
| Focus targets >=48dp | `evidence/talkback_payment_sheet_rerun_traversal.txt`, `evidence/talkback_cancel_dialog_traversal.txt` | **PASS** |
| Exact TalkBack spoken utterances | Not exposed by `uiautomator`; focus/bounds/labels only | **UNVERIFIED** |
| Real Razorpay checkout / real payment or refund | Deterministic fixture proxy used by design | **UNVERIFIED** (intentional limitation) |

P6-001 resolution: `evidence/visual_qa_summary.txt` §5 records the live post-fix verification. Previous failing warning PNG/XML are preserved in `qa-artifacts/phase6/superseded/`.

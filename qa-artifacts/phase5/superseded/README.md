# Superseded Phase 5 screenshots

These six screenshots were captured before the two live-QA fixes and are kept for audit trail only.

- `p5_checkout_order_review_fresh_quote_light_412dp_1.0x.png` is **not a Checkout
  screen**; it shows the Menu screen.
- All five prior Checkout captures expose the Cart header (`Order Items`, `Sync`,
  `Clear`) through the status-bar inset because `statusBarsPadding()` was applied
  to the overlay `Surface` instead of its content `Box`.
- The prior `p5_add_address_dialog_inline_errors_light_360dp_1.5x.png` is a
  scrolled/clipped variant.

Replacement captures are in `qa-artifacts/phase5/`.

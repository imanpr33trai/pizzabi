# Phase 7 notification hue evidence

Sampled from the saved dark `412dp / 1.0x` fixture capture. The sampled point is the chip background just inside the left edge at the label's vertical center. A chip is classified non-green when red > green; basil-green tokens have green >= red.

| Category chip | Bounds (px) | Sampled RGB | Red > green (non-green) |
|---|---:|---:|---|
| Order | `[829,1045][913,1081]` | `(91, 22, 12)` | PASS |
| Payment | `[790,1352][913,1388]` | `(121, 54, 16)` | PASS |
| Refund | `[812,1737][913,1773]` | `(121, 54, 16)` | PASS |
| Offer | `[899,2044][975,2080]` | `(120, 53, 15)` | PASS |

All sampled category chips are non-green: **YES**.

The `Refund` chip uses the amber tertiary container rather than the basil-green secondary container; this is also asserted in Robolectric for both light and dark color schemes via the same `notificationCategoryColors` function used by the composable.

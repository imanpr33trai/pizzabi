# Phase 7 accessibility touch-target evidence

Derived from fixture-backed `uiautomator` XML captures. Density is the value set by the QA helper (`wm density = round(1080*160/width_dp)`); a 47.5dp lower bound allows device-density rounding of Material 3's 48dp minimum interactive component size.

| Target | Density | Bounds (px) | Measured touch target | >=48dp |
|---|---:|---:|---:|---|
| Notifications settings jump | 412dp / 1.0x | `[819,150][944,275]` | 47.7 × 47.7 dp | PASS |
| Linked order notification row | 412dp / 1.0x | `[42,993][1038,1269]` | 380.3 × 105.4 dp | PASS |
| Notification Mark read action | 412dp / 1.0x | `[714,1487][996,1612]` | 107.7 × 47.7 dp | PASS |
| Order Updates switch | 412dp / 1.0x | `[860,1939][996,2065]` | 51.9 × 48.1 dp | PASS |
| Promo & Offer Alerts switch | 412dp / 1.0x | `[860,2127][996,2253]` | 51.9 × 48.1 dp | PASS |
| Delete address Cancel | 412dp / 1.0x | `[386,1334][634,1459]` | 94.7 × 47.7 dp | PASS |
| Delete address confirm | 412dp / 1.0x | `[655,1334][896,1459]` | 92.0 × 47.7 dp | PASS |
| Sign out Cancel | 412dp / 1.0x | `[352,1334][600,1459]` | 94.7 × 47.7 dp | PASS |
| Sign out confirm | 412dp / 1.0x | `[621,1334][896,1459]` | 105.0 × 47.7 dp | PASS |
| Optional update Later (1.5x) | 320dp / 1.5x | `[142,1615][938,1777]` | 235.9 × 48.0 dp | PASS |
| Optional update Download & Install (1.5x) | 320dp / 1.5x | `[142,1803][938,2071]` | 235.9 × 79.4 dp | PASS |
| Optional update Later (2.0x) | 320dp / 2.0x | `[142,1701][938,1881]` | 235.9 × 53.3 dp | PASS |
| Optional update Download & Install (2.0x) | 320dp / 2.0x | `[142,1908][938,2214]` | 235.9 × 90.7 dp | PASS |
| Download-error Close | 320dp / 1.5x | `[142,1810][938,1972]` | 235.9 × 48.0 dp | PASS |
| Download-error Retry | 320dp / 1.5x | `[142,1999][938,2161]` | 235.9 × 48.0 dp | PASS |
| Landscape update Later | 720x380dp / 1.0x | `[63,392][1017,464]` | 636.0 × 48.0 dp | PASS |
| Landscape update Download & Install | 720x380dp / 1.0x | `[63,476][1017,548]` | 636.0 × 48.0 dp | PASS |

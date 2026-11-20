# PIZZORA — Custom Pizza Builder UI/UX Design Audit & Implementation Plan (V1)

> **Status:** V1 Design Proposal — Pending User Review & Approval  
> **Scope Boundary:** Reconnaissance and design specification only. No application source code, database schemas, API contracts, or backend pricing logic have been modified.  
> **Evidence Discipline:** Throughout this document, verified repository facts are labeled **[Fact]**, design proposals are labeled **[Proposal]**, and open assumptions/gaps are labeled **[Assumption / Gap]**.

---

## 1. Repository Findings

### 1.1 Platform & Application Architecture **[Fact]**
- **UI Framework & Language:** Native Android application written in **Kotlin 2.2.10** using **Jetpack Compose** (`composeBom = 2024.09.00`), **Material 3** (`androidx.compose.material3`), **Navigation Compose 2.8.9**, and **Coil Compose 2.7.0** for network image loading.
- **Target & Min SDK:** `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36.1`, edge-to-edge enabled via `enableEdgeToEdge()` in `MainActivity.kt`.
- **Desktop / Multiplatform Support:** **None.** The client is Android-only (`com.android.application`). Responsive tablet adaptation exists via `BoxWithConstraints` (`maxWidth >= 600.dp` switches `NavigationBar` to `NavigationRail` in `CustomerRoleShell` and `OwnerRoleShell`).
- **State, Persistence & Networking:**
  - Unidirectional state via `StateFlow` in Android `ViewModel`s (`CustomerCatalogViewModel`, `CartViewModel`, `CheckoutQuoteViewModel`) injected via manual DI (`AppContainer.kt`).
  - Local persistence via **Room 2.7.0** (`PizzoraDatabase`: `cart_items`, `cart_item_toppings`, `cached_products`, `cached_menu_snapshot`, `notification_inbox`).
  - Networking via **Ktor Client 3.2.3** (`PizzoraApiClient.kt`) talking to a dual-engine backend (Kotlin/Ktor in `/backend` and Rust/Axum in `/rust`), plus a shared Rust/Kotlin domain bridge (`pizzora-core` via UniFFI with Kotlin fallback).
- **Testing & Screenshot Capabilities:**
  - Unit & Compose UI tests run on JVM via **Robolectric 4.16.1** + `androidx.compose.ui:ui-test-junit4` (`Phase7CartAndMenuRobolectricTest.kt`, `Phase19EndToEndRobolectricTest.kt`, etc.), plus Android instrumentation tests (`androidTest`).
  - **No screenshot testing library** (such as Paparazzi, Roborazzi, or Compose Preview Screenshot Testing) is currently configured in `gradle/libs.versions.toml` or `app/build.gradle.kts`.

### 1.2 Exact Files Responsible for the Customization Experience **[Fact]**
| Layer | File Path | Responsibility |
|---|---|---|
| Customizer UI | `app/src/main/java/com/example/catalog/PizzaCustomizerSheet.kt` | Renders the modal overlay, size/crust/topping selectors, quantity stepper, client-side total preview, and Add CTA. |
| Host Screen & Shared Indicator | `app/src/main/java/com/example/catalog/CustomerMenuScreen.kt` | Hosts `PizzaCustomizerSheet` inside an `AnimatedVisibility` block overlaid on the menu; defines reusable `VegIndicator`. |
| Menu & Active Product State | `app/src/main/java/com/example/catalog/CustomerCatalogViewModel.kt` | Holds `CustomerMenuUiState` (`allProducts`, `crusts`, `toppings`, `isAcceptingOrders`, `isFromOfflineCache`, `customizingProduct`). |
| Offline Catalog Cache | `app/src/main/java/com/example/catalog/CatalogRepository.kt` | Fetches `/api/v1/catalog/menu` and caches full `PublicMenuResponse` snapshot (including crusts and toppings) in Room. |
| Navigation & Shell | `app/src/main/java/com/example/navigation/PizzoraNavHost.kt` | Wires `CustomerMenuScreen` to `CartViewModel.addCustomizedPizza`, hosts `SnackbarHost`, `NavigationBar` / `NavigationRail`, and `PizzoraTheme`. |
| Cart Mutation & Merging | `app/src/main/java/com/example/cart/CartViewModel.kt` & `CartRepository.kt` | Persists customized item to Room, merges duplicate `(productId, variantId, crustId, sorted toppingIds)` up to qty `25`, syncs to `PUT /api/v1/cart`, emits snackbar event. |
| Cart Display | `app/src/main/java/com/example/cart/CartScreen.kt` | Displays customized line items (size, crust, topping names, unit price, line total) with +/- and remove controls, but **no edit action**. |
| Theme & Tokens | `app/src/main/java/com/example/ui/theme/Color.kt`, `Theme.kt`, `Type.kt` | Defines warm light & dark color schemes, `LocalPizzoraSemanticColors`, `PizzoraTypography` (`Fraunces`, `Plus Jakarta Sans`, `JetBrains Mono`), and `PizzoraShapes`. |
| Shared Contracts | `shared/contracts/.../CatalogDto.kt`, `CartDto.kt`, `PricingEngine.kt`, `Money.kt` | Defines `ProductDto`, `ProductVariantDto`, `SizeCode`, `CrustOptionDto`, `ToppingDto`, `CartItemInputDto`, `CartItemViewDto`, INR paise formatting. |
| Authoritative Backend Rules | `backend/.../CartService.kt`, `CatalogService.kt`, `V1__core_schema.sql`, `rust/crates/pizzora-core/src/cart_validation.rs` | Enforces `MAX_ITEM_QUANTITY = 25`, `MAX_CART_LINE_ITEMS = 50`, server-authoritative unit price (`variant.pricePaise + crust.extraPricePaise + sum(toppings.extraPricePaise)`), and availability validation. |
| UI Tests | `app/src/test/java/com/example/Phase7CartAndMenuRobolectricTest.kt`, `Phase19EndToEndRobolectricTest.kt` | Assert `PizzaCustomizerSheet` behavior via explicit `testTag`s. |

### 1.3 Data Model & Business Rules Verification **[Fact]**
- **Implemented in Contracts & Database (`V1__core_schema.sql`, `CatalogDto.kt`, `CartDto.kt`):**
  - **Sizes (`ProductVariantDto`):** `SizeCode` enum (`REGULAR`, `MEDIUM`, `LARGE`), `pricePaise: Long`, `isActive: Boolean`. Unique `(product_id, size_code)` per product.
  - **Crusts (`CrustOptionDto`):** Global catalog list; `id`, `name`, `extraPricePaise: Long`, `isActive: Boolean`. Required on every `CartItemInputDto` (`crust_id` is non-null FK).
  - **Toppings (`ToppingDto`):** Global catalog list; `id`, `name`, `isVeg: Boolean`, `extraPricePaise: Long`, `isActive: Boolean`. Multi-select, deduplicated by UUID, sorted lexicographically on merge (`cart_item_toppings` PK is `(cart_item_id, topping_id)` — so each topping can only be selected once per line item, no 2x quantity per topping).
  - **Quantity:** Minimum `1`, maximum `25` (`MAX_ITEM_QUANTITY = 25` in both Kotlin `CartService`, Rust `cart_validation.rs`, and Android `CartRepository`/`PizzaCustomizerSheet`).
  - **Pricing Formula:** `unitPricePaise = variant.pricePaise + crust.extraPricePaise + sum(distinctToppings.extraPricePaise)`; `lineTotalPaise = unitPricePaise * quantity`. Backend recalculates all prices on `PUT /api/v1/cart` and `POST /api/v1/pricing/quote`; `clientUnitPricePaise` is advisory only.
  - **Public Menu Filtering:** `CatalogService.getPublicMenu()` queries with `activeOnly = true` on the server, though cached offline snapshots or direct fixture payloads may contain items with `isActive = false`.
- **NOT Implemented in Contracts, Database, or Backend **[Gap]**:**
  - **Cheese options** (Scope 6.3): No `cheese_options` table, DTO, or cart column exists.
  - **Sauces** (Scope 6.4): No `sauces` table, DTO, or cart column exists.
  - **Standard vs. Extra Toppings** (Scope 6.5 vs. 6.6): `ProductDto` has only free-text `description`; there is no `includedToppings` list or `isRemovable` flag to distinguish standard recipe toppings from paid extras.
  - **Half-and-Half Customization** (Scope 6.7): No left/right variant, half-topping mapping, or half-and-half pricing rule (e.g., max-of-two vs. average) exists anywhere in SQL, Rust, Ktor, or shared contracts.
  - **Max Toppings Limit:** No server-enforced maximum topping count exists today (only deduplication per topping ID and max 50 distinct cart lines).

---

## 2. Existing Implementation Analysis (`PizzaCustomizerSheet.kt`)

### 2.1 How It Works Today **[Fact]**
1. Tapping a `ProductMenuCard` or its `Customize` button calls `CustomerCatalogViewModel.openCustomizer(product)`, setting `uiState.customizingProduct`.
2. `CustomerMenuScreen` reveals `PizzaCustomizerSheet` inside an `AnimatedVisibility(visible = activeProduct != null)` overlay layered inside the screen's root `Box` (i.e., **inside** `Scaffold`'s `innerPadding`, meaning the app's `TopAppBar` and `NavigationBar` remain visible above/below the sheet!).
3. `PizzaCustomizerSheet` manually draws a `Color.Black.copy(alpha = 0.55f)` scrim and a bottom-aligned `Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), tonalElevation = 6.dp)` capped at `widthIn(max = 640.dp)`.
4. Inside the sheet:
   - **Header:** `VegIndicator`, `product.name` (`headlineSmall` Fraunces), Close `IconButton`, optional `product.description`, and a `HorizontalDivider`. **No product image.**
   - **Scrollable Body (`verticalScroll`):** Three identical full-width vertical stacks of rectangular cards:
     - *Choose Size:* Full-width bordered rows with Material `RadioButton` + size name + `Money.formatInr(variant.pricePaise)`.
     - *Choose Crust:* Full-width bordered rows with `RadioButton` + crust name + `"Included"` or `"+ ₹..."`.
     - *Extra Toppings:* Full-width bordered rows with `Checkbox` + `VegIndicator` + topping name + `"+ ₹..."`.
   - **Sticky Footer:** `HorizontalDivider`, bordered quantity stepper (`- 1 +`), and a single coral `Button` showing `"Add • ₹{total}"`.
5. Tapping `Add` synchronously calls `onAddCustomizedToCart(...)` followed immediately by `onDismissCustomizer()`. `CartViewModel.addCustomizedPizza` launches a coroutine to write to Room and sync with the server, then emits a snackbar (`"${product.name} added to cart"`).

---

## 3. Current UI Strengths **[Fact]**

1. **Strong Brand Foundation in Theme Tokens:** `Fraunces` for product names, `Plus Jakarta Sans` for UI copy, and `JetBrains Mono` with tabular figures (`fontFeatureSettings = "tnum"`) for prices establish a genuinely warm, artisanal visual identity when rendered in dark mode (`#121110` / `#1D1A18` / `#EF644F` / `#52B788`).
2. **Reliable Tabular Price Alignment:** Every price uses `Money.formatInr(...)` + `PizzoraMonoBody` / `PizzoraMonoTitle`, preventing jitter during quantity or option updates.
3. **Predictable Default Selection:** Pre-selects the first available variant and first crust so the customer can immediately see a valid price and add with minimal friction.
4. **Sticky Bottom Action Bar & Back Handling:** `BackHandler(onBack = onDismiss)` works properly, and the quantity stepper + primary CTA stay anchored below the scroll area with `navigationBarsPadding()`.
5. **Offline-Resilient Cart Integration:** Adding to cart writes immediately to Room and syncs cleanly with server reconciliation when online.
6. **Existing Testability:** Stable `testTag` hooks (`pizza_customizer_sheet`, `size_option_*`, `crust_option_*`, `topping_option_*`, `customizer_qty_*`, `add_to_cart_button`) already exist and are covered by Robolectric tests.

---

## 4. Confirmed Weaknesses **[Fact]**

### 4.1 Visual & Product Presentation
1. **Zero Pizza Imagery:** `PizzaCustomizerSheet` never displays `product.images` (even though `ProductMenuCard` loads `product.images.firstOrNull()?.publicUrl` via Coil). The experience immediately drops from food-first to a text-only form.
2. **Generic Form Aesthetic:** Size, Crust, and Toppings all use near-identical full-width stacked radio/checkbox list boxes. There is no visual rhythm differentiating a 3-choice core decision (Size) from a dough style (Crust) or multi-pick garnishes (Toppings).
3. **Overlay Confinement Inside `Scaffold` Content:** Because `PizzaCustomizerSheet` is rendered inside `CustomerMenuScreen`'s `Box` (which receives `Scaffold`'s `innerPadding`), the `TopAppBar` and bottom `NavigationBar` stay uncovered outside the scrim, creating an awkward "trapped drawer between two bars" layout and wasting ~140dp of vertical space on small phones.
4. **Redundant `navigationBarsPadding()` Inside `Scaffold`:** Since `Scaffold` already consumes `WindowInsets.safeDrawing` and bottom `NavigationBar`, adding `navigationBarsPadding()` inside the sheet produces inconsistent bottom padding across gesture-nav vs. 3-button-nav devices (and none if moved outside `Scaffold` without care).

### 4.2 Customization Clarity & Business Rules
5. **No Configuration Summary Before Adding:** Once scrolled down into toppings, the customer cannot see what Size, Crust, or how many Toppings they selected without scrolling all the way back up.
6. **Opaque Pricing Calculation:** The CTA shows only `"Add • ₹1248.00"`. Customers cannot see the breakdown (`Base Size + Crust + N Toppings × Qty`), nor a reminder that GST/delivery are calculated in Cart/Checkout by the server.
7. **Inactive (`isActive == false`) Options Are Selectable:** `PizzaCustomizerSheet` does not inspect `variant.isActive`, `crust.isActive`, or `topping.isActive`. If stale/offline data or future menu responses include unavailable items, users can select them and only discover errors later in Cart. Also, `firstOrNull()` can auto-select an inactive variant/crust.
8. **Store Closed (`!isAcceptingOrders`) Ignored Inside Builder:** `CustomerMenuScreen` shows a "Kitchen is currently paused. Browsing only." banner, yet `ProductMenuCard` still opens `PizzaCustomizerSheet` with an active `Add` button and no store-paused warning inside the builder.
9. **No Duplicate-Submission Guard or Error/Offline Feedback:** Tapping `Add` fires `onAddCustomizedToCart` and dismisses immediately without disabling the button during submission or surfacing `CartSyncResult.errorMessage` (e.g., `"Authentication required"` or offline confirmation inline). Rapid double-taps during the `AnimatedVisibility` exit animation can invoke `onConfirmAdd` twice.
10. **No Topping Filtering or Dietary Scanning Aid:** Toppings render as one flat unsorted list. A vegetarian customer cannot quickly filter toppings to Veg Only, and long topping lists become tedious to scan.
11. **Missing Extensible Structure for Future Modifiers:** Hardcoded sequential blocks for Size, Crust, and Toppings have no clean section contract to plug in Cheese, Sauces, Standard Toppings, or Half-and-Half when contracts expand.

### 4.3 Design System & Accessibility Defects
12. **Alpha-Surface Contrast & Content-Color Mismatch:** `surfaceVariant.copy(alpha = 0.45f)` and `secondaryContainer.copy(alpha = 0.55f)` break Material 3 `contentColorFor()` automatic pairing, and `outline.copy(alpha = 0.4f)` (`#3F3732` at 40% on `#1D1A18`) drops border contrast well below WCAG 2.2 AA's 3:1 non-text contrast threshold.
13. **Missing Semantics on Custom Interactive Rows:** `RadioButton(onClick = null)` and `Checkbox(onClick = null)` sit inside generic `.clickable {}` modifiers instead of `Modifier.selectable(role = Role.RadioButton)` / `Modifier.toggleable(role = Role.Checkbox)`. TalkBack announces them as generic double-tap items rather than radio/checkbox controls with explicit checked/selected state.
14. **`VegIndicator` Invisible to Screen Readers:** `VegIndicator` draws two nested `Box`es with zero `contentDescription` or `semantics`, leaving dietary status completely conveyed by color/shape only.
15. **No `maxLines` / Font-Scaling Protection:** At 150%–200% system font scale, long option titles + `PizzoraMonoBody` prices in a single `Row` can squeeze or clip without `Modifier.weight(1f)` on the label column.
16. **Light vs. Dark Mode Alignment:** `PizzoraTheme` defaults to `isSystemInDarkTheme()`. While the dark palette matches the warm espresso/cream/coral identity described in the brief, the light palette is also active on light-mode Android devices, so any new semantic tokens must pair cleanly across both schemes without breaking contrast.

---

## 5. Potential Weaknesses Requiring Validation **[Assumption / Gap]**

1. **Real Production Catalog Density:** Test fixtures contain 2 sizes, 2 crusts, and 2 toppings, whereas real pizzerias often have 3 sizes, 4–6 crusts, and 15–25 toppings. Vertical scrolling ergonomics, section jump navigation, and topping Veg/Non-Veg filtering must be validated against a 20+ topping catalog.
2. **Product Image Aspect Ratios & Missing Images:** `ProductDto.images` may be empty (as in `Phase7CartAndMenuRobolectricTest` fixtures) or contain varied photo crops. Need to validate graceful fallback visuals (warm oven/placeholder framing) when `imageUrl == null`.
3. **Cart Merge Behavior When Quantity Exceeds 25:** `CartRepository.addCustomizedItem` silently clamps `(existingQty + addedQty).coerceAtMost(25)`. If a customer already has 24 of a pie in Cart and adds 3 more, no feedback explains the cap.
4. **Device Font Scaling & Smallest Viewport (320dp–360dp):** The quantity stepper + `"Add • ₹12,480.00"` button in a single horizontal row has not been verified at 360dp width with 200% font scale — it likely wraps or clips the CTA text.
5. **Motion Sensitivity:** `AnimatedVisibility` uses default fade+expand transitions without checking Android's `ANIMATOR_DURATION_SCALE` / reduced-motion preference.

---

## 6. Existing Design-System Analysis

### 6.1 Audited Theme Tokens **[Fact]**
| Token Role | Dark Scheme (`PizzoraDarkColorScheme`) | Light Scheme (`PizzoraLightColorScheme`) | Usage & Contrast Assessment |
|---|---|---|---|
| Canvas (`background`) | `EspressoBackground` `#121110` | `CreamBackground` `#FFF9F3` | Strong warm anchor; preserve verbatim. |
| Primary Surface (`surface`) | `DarkRoastSurface` `#1D1A18` | `PureWhiteSurface` `#FFFFFF` | ~15.4:1 contrast with `onSurface` (`#F7F2EC`) in dark mode (AAA). |
| Elevated / Control Surface (`surfaceVariant`) | `SmokedSlateSurfaceVariant` `#2A2522` | `OvenStoneSurfaceVariant` `#F5EBE0` | Clear warm step above `#1D1A18`; **do not dilute with `.copy(alpha = 0.35f)`**. |
| Primary Action (`primary` / `onPrimary`) | `EmberTerracotta` `#EF644F` / `#280804` | `SanMarzanoTerracotta` `#C83E2B` / `#FFFFFF` | ~5.96:1 on dark primary CTA (AA); ~5.5:1 on light CTA (AA). |
| Selected Container (`primaryContainer` / `onPrimaryContainer`) | `#5B1E14` / `#FFDAD3` | `#FCE8E4` / `#5C150C` | High contrast (~10.3:1 dark), **but heavy when used as full-bleed fill on every selected option**. Better suited for subtle warm tint or selected state accents paired with crisp `1.5dp`–`2dp` border. |
| Positive / Veg (`secondary` / `vegBadgeGreen`) | `MintBasil` `#52B788` | `FreshBasilGreen` `#2D6A4F` | 7.4:1 on `#1D1A18` (AAA). Keep strictly for Veg indicators, "Included" / savings / positive confirmations. |
| Warning / Highlight (`tertiary`) | `BrightAmber` `#FBBF24` | `GoldenCrustAmber` `#D97706` | Good for "Required" / offline / unavailable notices (paired with dark text on container). |
| Primary / Secondary Ink (`onSurface` / `onSurfaceVariant`) | `WarmParchmentText` `#F7F2EC` / `MutedStoneText` `#C4B7AA` | `CharredInk` `#1E1916` / `WarmTaupe` `#6B5E54` | Excellent hierarchy (15.4:1 and 8.7:1 on `#1D1A18`). |
| Hairline / Border (`outline`) | `DarkOutline` `#3F3732` | `DoughOutline` `#DFD3C5` | At 100% opacity, subtle divider; needs a dedicated higher-contrast interactive border token for WCAG 1.4.11 where borders define selectable boundaries. |

### 6.2 Typography & Shapes **[Fact]**
- **Display / Headings:** `FrauncesFamily` (`displaySmall` 32/38 Bold, `headlineMedium` 26/32 SemiBold, `headlineSmall` 22/28 SemiBold). Use for the pizza name and hero title.
- **UI Body & Labels:** `PlusJakartaSansFamily` (`titleLarge` 20/26, `titleMedium` 16/22, `bodyLarge` 16/24, `bodyMedium` 14/20, `labelLarge` 14/20, `labelMedium` 12/16). Note: `bodySmall` and `labelSmall` are **not overridden** in `PizzoraTypography` (they currently fall back to default Material Roboto!).
- **Prices & Quantities:** `JetBrainsMonoFamily` (`PizzoraMonoBody` 14/20 Medium `tnum`, `PizzoraMonoTitle` 18/24 Bold `tnum`).
- **Shapes (`PizzoraShapes`):** `small = 10.dp`, `medium = 16.dp`, `large = 22.dp`.

### 6.3 Minimal Proposed Design-System Additions **[Proposal]**
Avoid creating an oversized design system. Add only the smallest cohesive extensions to `Type.kt` / `Color.kt` / `Theme.kt`:
1. **`bodySmall` (12/16sp) and `labelSmall` (11/14sp)** in `PizzoraTypography` using `PlusJakartaSansFamily` so helper captions and metadata never silently fall back to Roboto.
2. **`PizzoraMonoCaption` (12/16sp Medium `tnum`)** in `Type.kt` for per-option price deltas and itemized breakdown sub-rows.
3. **Two semantic tokens in `PizzoraSemanticColors`:**
   - `selectedSurfaceTint`: Warm low-glare fill for selected customization tiles (`Color(0xFF2E1B16)` dark / `Color(0xFFFDF0ED)` light) so selected rows feel refined rather than loud.
   - `disabledSurface` / `unavailableText`: Consistent muted surface and high-legibility muted label for inactive/sold-out modifiers.
4. **Spacing & Radius Convention (documented constants, not a heavy framework):**
   - Outer horizontal inset: `20.dp` (compact) / `24.dp` (medium/expanded).
   - Section vertical gap: `24.dp`; intra-section item gap: `10.dp`.
   - Option card radius: `14.dp` (or `MaterialTheme.shapes.medium` `16.dp` for outer groups and `10.dp` `shapes.small` for nested controls to respect concentric radius math).
   - Minimum interactive touch target: `48.dp × 48.dp` on Android.

---

## 7. Layout Concepts Evaluated (3 Materially Different Approaches)

Per user alignment, we evaluate both **modal sheet** and **dedicated full-screen** concepts, plus a **guided step-by-step** alternative, across phones and tablets.

### Concept A — Full-Height Immersive Bottom Sheet with Adaptive Tablet Dialog *(Sheet-First)*
- **Layout Structure:** Full-window modal overlay above `CustomerRoleShell` (covering `TopAppBar` and `NavigationBar` with scrim, reaching ~92% screen height with `24.dp` top corners and drag handle on phones; centered `720.dp`–`840.dp` two-column modal dialog on tablets `>= 600.dp`).
  - Top: Collapsible hero banner (pizza photo with bottom contrast scrim + Veg/Non-Veg indicator + Fraunces title + 2-line description + floating circular Close button).
  - Sticky Compact Header on scroll: collapses to single-line pizza name + live selected summary (`Large · Sourdough · +2 toppings`).
  - Body: Single smooth vertical scroll with **visual differentiation per section**:
    - **Size:** Horizontal 3-up segmented cards (showing size code, optional visual scale cue, and tabular price) — wraps cleanly to vertical list if `> 3` variants or font scale `>= 1.5x`.
    - **Crust:** Structured 1-column tactile cards with radio semantics, `"Included"` in basil green or `"+ ₹50.00"` in mono.
    - **Extensible Modifier Slots** *(Cheese, Sauces, Half-and-Half — rendered only when contract data exists)*: Uniform section container pattern.
    - **Extra Toppings:** Section header with selected counter + inline `All / Veg Only` toggle + structured rows (or 2-column grid on tablets) with explicit Veg/Non-Veg indicator, checkbox semantics, and clear `+ ₹` delta.
  - Bottom Dock: Sticky elevated bar with:
    1. **Expandable Price Breakdown Drawer** (collapsed by default: shows compact live config summary + `"View breakdown"` chevron + `₹total`; tapping expands an inline itemized receipt showing Base Size, Crust, Toppings subtotal/list, Qty multiplier, and `"GST & delivery calculated in Cart"` note).
    2. **Action Row:** Tactile `48.dp`-height quantity stepper (`- 1 +`) + full-height coral CTA button (`"Add to Cart · ₹1,248.00"` with loading spinner state during submit).
- **Visual Hierarchy:** Hero photo + Fraunces title anchors attention -> bold horizontal Size cards -> clean Crust list -> filterable Toppings -> anchored coral CTA.
- **Scrolling Behavior:** Single uninterrupted vertical scroll (`LazyColumn` or `verticalScroll`) with generous bottom clearance so zero options are ever obscured by the sticky bar.
- **Advantages:**
  - Keeps menu context spatially anchored (swipe-down or scrim tap returns instantly to exact menu scroll position).
  - Zero `NavHost` route churn; matches existing `CustomerCatalogViewModel.customizingProduct` architecture while fixing the `Scaffold` clipping bug by hoisting the overlay to `CustomerRoleShell` root.
  - Fast, low-friction for standard 10-second pizza ordering.
  - Adapts naturally into a balanced 2-column split modal on tablets (`>= 600.dp`).
- **Disadvantages:**
  - Slightly less vertical room than a true edge-to-edge screen (loses ~48dp at top for status bar + sheet corner reveal).
  - Accidental swipe-to-dismiss while scrolling down if drag gestures aren't restricted to the top drag handle / collapsed top scroll position.
- **Implementation Complexity:** **Low–Medium.** Reuses existing state flow, preserves all existing `testTag`s, requires hoisting overlay above `CustomerRoleShell` `Scaffold` (clean surgical change).
- **PIZZORA Identity Fit:** **High (9.5/10).** Warm dark surface `#1D1A18` rising over dimmed `#121110` feels cohesive with `CouponPickerSheet` and premium food-delivery ergonomics.

---

### Concept B — Dedicated Full-Screen Custom Pizza Builder Route *(Screen-First)*
- **Layout Structure:** Full-screen destination (`PizzaBuilderRoute(productId)` or full-screen overlay replacing shell content) with edge-to-edge top image header (`220.dp` height) and Back arrow (`ArrowBack`) top-left, collapsing into a standard `TopAppBar` with pizza title + sticky **Section Jump Bar** (`Size`, `Crust`, `Toppings`, plus future `Cheese`, `Sauce`, `Half & Half` tabs).
  - On tablets (`>= 600.dp`): Full-screen 2-pane workspace — Left sticky pane (40% width: large pizza photo, Fraunces title, description, live visual configuration summary, and always-visible itemized breakdown); Right scrollable pane (60% width: customization sections + sticky bottom CTA).
  - On phones: Full-height scrollable body + sticky bottom bar with expandable price breakdown drawer, quantity stepper, and coral `Add to Cart` button.
- **Visual Hierarchy:** Maximum photography presence (edge-to-edge hero) + explicit section tabs for rapid jumping when catalogs have 4–6 modifier groups (Size, Crust, Cheese, Sauce, Standard Toppings, Extra Toppings, Half & Half).
- **Scrolling Behavior:** `LazyColumn` with `stickyHeader` section jump bar; tapping a jump tab smoothly scrolls to that section (`animateScrollToItem`). No accidental sheet-dismiss gestures.
- **Advantages:**
  - Best scalability when Cheese, Sauces, Standard Toppings, and Half-and-Half are added in future releases.
  - Zero risk of accidental swipe-to-dismiss; full viewport height on small phones (320–360dp).
  - Superior tablet experience (true 2-pane configurator without wasted margin around a modal).
  - Cleanly supports deep-linking or launching from **Cart** ("Edit item") in Phase 2 without coupling to `CustomerMenuScreen` state.
- **Disadvantages:**
  - Requires either a full-screen overlay state at `CustomerRoleShell` level or a new `NavHost` route (note Section 13 restriction: do not replace existing navigation; a `CustomerRoleShell`-hosted full-screen screen like `isCheckoutOpen` / `isNotificationsOpen` already used in `PizzoraNavHost.kt` avoids altering `NavHost`!).
  - Slightly heavier transition for quick single-tap orders compared to a bottom sheet.
  - Section jump bar adds ~44dp of sticky height when only 3 sections exist today (only worthwhile once 4+ sections or 10+ toppings exist).
- **Implementation Complexity:** **Medium.** Fits cleanly into `CustomerRoleShell`'s existing full-screen overlay pattern (identical to how `CheckoutScreen` and `NotificationsScreen` are hosted in `PizzoraNavHost.kt` lines 459 and 564!).
- **PIZZORA Identity Fit:** **High (9/10).** Mirrors PIZZORA's existing `CheckoutScreen` and `OrderDetail` full-screen pattern while giving pizzas a true flagship editorial stage.

---

### Concept C — Guided Step-by-Step Wizard (`1. Base -> 2. Toppings -> 3. Review`)
- **Layout Structure:** Horizontal multi-step flow with progress bar (`Step 1 of 3`), "Next" / "Back" buttons, and a final review card showing the full configuration and itemized price before adding to cart.
- **Visual Hierarchy:** Focuses on one decision group per viewport; eliminates vertical scroll fatigue for complex menus.
- **Advantages:** Enforces sequential completion (useful if Half-and-Half + 6 modifier categories are all required); clear final review step.
- **Disadvantages:**
  - **High friction for today's 3-option catalog** (forces 3 taps just to add a default Large Margherita).
  - Hides downstream options (e.g., extra toppings) behind extra taps; users cannot scan total options at a glance.
  - Breaks `Phase7CartAndMenuRobolectricTest` and `Phase19EndToEndRobolectricTest`, which expect `size_option_*`, `crust_option_*`, `topping_option_*`, and `add_to_cart_button` to be accessible within a single scrollable builder view.
- **Implementation Complexity:** **Medium–High.** Requires multi-step state machine, back-stack step handling, and test rewrites.
- **PIZZORA Identity Fit:** **Low–Medium (5.5/10).** Feels bureaucratic and slows down repeat customers who want effortless warm ordering.

---

## 8. Recommended Layout & Rationale **[Proposal]**

### Recommendation: **Hybrid Adaptive Full-Screen / Sheet-Ready Builder (Concept B's Full-Screen Stage on Phones & 2-Pane Workspace on Tablets, Hosted Like `CheckoutScreen` in `CustomerRoleShell`, with Conditional Section Jump Bar)**

#### Why This Beats Pure Concept A and Concept C:
1. **Directly Solves the Biggest Confirmed Layout Bug:** Today's sheet sits trapped between `TopAppBar` and `NavigationBar`. Hosting the builder at `CustomerRoleShell` level (matching the proven `isCheckoutOpen` / `isNotificationsOpen` pattern in `PizzoraNavHost.kt`) gives 100% of viewport height on compact phones without replacing `PizzoraNavHost` routes.
2. **Future-Proof for Extensible Modifier Slots & Phase 2 Cart Edit:** The user explicitly confirmed:
   - Extensible slots for Cheese, Sauces, Standard Toppings, and Half-and-Half.
   - Phase 2 Cart re-edit flow.
   A shell-level full-screen builder can be launched identically from **Menu** (`mode = Add`) AND from **Cart** (`mode = Edit(cartItemId)`), whereas a sheet embedded inside `CustomerMenuScreen` cannot be opened from `CartScreen` without duplication.
3. **Eliminates Swipe-Dismiss Conflicts & Maximizes Food Presence:** Full edge-to-edge warm pizza hero imagery (with graceful artisanal placeholder when `imageUrl == null`) establishes immediate appetite appeal without risking accidental sheet dismissal while scrolling toppings.
4. **Adaptive Without Wasting Space:**
   - **Phones (`< 600.dp`):** Full-screen edge-to-edge builder with compact collapsing top bar + single-column scroll + sticky bottom bar with **expandable price breakdown drawer**. Section jump chips appear *only* when `visibleSectionCount >= 4` or `toppings.size >= 8`, preventing sticky header bloat today while scaling effortlessly tomorrow.
   - **Tablets (`>= 600.dp`):** Clean 2-pane layout (`maxWidth = 1040.dp` centered): Left sticky product & live summary/receipt column (`38%`), Right scrollable customization & sticky CTA column (`62%`).
5. **100% Backward-Compatible with Existing Tests:** Keeps all required `testTag`s (`pizza_customizer_sheet`, `customizer_close_button`, `size_option_*`, `crust_option_*`, `topping_option_*`, `customizer_qty_*`, `add_to_cart_button`) in a single scrollable container (`LazyColumn`) so `Phase7CartAndMenuRobolectricTest` and `Phase19EndToEndRobolectricTest` stay green.

> *Alternative Note for User Review:* If you strongly prefer preserving the dimmed-menu bottom-sheet feel on phones (Concept A) over a full-screen builder (Concept B), all internal sections (hero, Size segmented cards, Crust rows, Toppings with Veg toggle, and sticky bottom bar with expandable price breakdown drawer) are **100% identical** and can sit inside a hoisted 92%-height sheet instead. This is listed as Decision Gate #1 in Section 18.

---

## 9. Complete Screen Structure

### 9.1 Phone Layout (`width < 600.dp`)
```
┌──────────────────────────────────────────────┐
│ [Status Bar Safe Inset]                      │
│ ┌──────────────────────────────────────────┐ │
│ │  Pizza Photo (16:9 / 190dp) or Artisanal │ │
│ │  Fallback Frame + Bottom Dark Scrim      │ │
│ │  [<- Close]                   [Veg Icon] │ │
│ │                                          │ │
│ │  Margherita Verace          (Fraunces)   │ │
│ │  San Marzano DOP tomato & fior di latte  │ │
│ └──────────────────────────────────────────┘ │
│ (On scroll: collapses to 56dp sticky top bar │
│  "[<-] Margherita Verace · Large · ₹624")    │
│                                              │
│ [Conditional Banner: Store Paused / Offline] │
│ [Conditional Jump Bar: Size | Crust | ...]   │  <- Only when >= 4 sections or >= 8 toppings
│                                              │
│ ── 1. CHOOSE SIZE · Required ──────────────  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ Regular  │  │  Medium  │  │  Large ✓ │   │
│  │ ₹299.00  │  │ ₹429.00  │  │ ₹549.00  │   │
│  └──────────┘  └──────────┘  └──────────┘   │
│                                              │
│ ── 2. CHOOSE CRUST · Required ─────────────  │
│  ○ Hand-Tossed                     Included  │
│  ● Sourdough Pan                  + ₹50.00   │
│  ○ Gluten-Free (Unavailable)      Sold out   │
│                                              │
│ ── [EXTENSIBLE SLOTS: Cheese / Sauce /       │
│     Half & Half — rendered only when API     │
│     populates those groups] ───────────────  │
│                                              │
│ ── 3. EXTRA TOPPINGS · Optional (1 selected) │
│  [All] [Veg Only]        [Clear selections]  │
│  ☑ [Veg] Extra Basil              + ₹25.00   │
│  ☐ [Veg] Burrata                  + ₹95.00   │
│  ☐ [NV]  Calabrian Salami         + ₹85.00   │
│                                              │
│  [Scroll bottom padding >= 140dp clearance]  │
├══════════════════════════════════════════════┤  <- Sticky Bottom Dock (Surface #1D1A18 + 1dp hairline)
│ [Expandable Breakdown Drawer — Collapsed:]   │
│  Large · Sourdough Pan · +1 topping    [▲]   │
│                                              │
│ [When Expanded:]                             │
│  Base (Large)                      ₹549.00   │
│  Crust (Sourdough Pan)             + ₹50.00  │
│  Extra Toppings (1)                + ₹25.00  │
│    · Extra Basil (+₹25.00)                   │
│  Unit Price                        ₹624.00   │
│  Quantity                             × 2    │
│  ─────────────────────────────────────────   │
│  Estimated Item Total            ₹1,248.00   │
│  GST & delivery calculated in Cart           │
├──────────────────────────────────────────────┤
│  ┌─────────────┐  ┌────────────────────────┐ │
│  │  −   2   +  │  │ Add to Cart · ₹1,248.00│ │
│  └─────────────┘  └────────────────────────┘ │
│  [Navigation Bar Safe Inset]                 │
└──────────────────────────────────────────────┘
```

### 9.2 Tablet Layout (`width >= 600.dp`)
```
┌──────────────────────────────────────────────────────────────────────┐
│ [<- Back]  Customize Pizza                               [Close ×]   │
├──────────────────────────────┬───────────────────────────────────────┤
│ LEFT PANE (38%, Sticky)      │ RIGHT PANE (62%, Scrollable)          │
│ ┌──────────────────────────┐ │ 1. Choose Size · Required             │
│ │   Pizza Photo (4:3)      │ │ [Regular]   [Medium]   [Large ✓]      │
│ └──────────────────────────┘ │                                       │
│ [Veg] Margherita Verace      │ 2. Choose Crust · Required            │
│ San Marzano DOP tomato &...  │ ( ) Hand-Tossed             Included  │
│                              │ (*) Sourdough Pan          + ₹50.00   │
│ ── Live Summary & Price ──   │                                       │
│ Base (Large)        ₹549.00  │ 3. Extra Toppings · Optional (1)      │
│ Crust (Sourdough)  + ₹50.00  │ [All] [Veg Only]                      │
│ Toppings (1)       + ₹25.00  │ [2-column grid of topping rows]       │
│ ──────────────────────────   │                                       │
│ Unit × 2          ₹1,248.00  ├───────────────────────────────────────┤
│ GST calculated in Cart       │ [ − 2 + ]  [ Add to Cart · ₹1,248.00] │
└──────────────────────────────┴───────────────────────────────────────┘
```

---

## 10. Customization Interaction Specifications

### 10.1 Currently Supported Options (Contract-Backed Today **[Fact]**)

| Section | Control Pattern | Selection Rule | Availability Behavior | Pricing Display |
|---|---|---|---|---|
| **6.1 Pizza Size** | Horizontal 3-column selectable cards (stacks vertically if `variants.size > 3` or font scale `>= 1.5f`). Sorted deterministically: `REGULAR -> MEDIUM -> LARGE`. | Single-select, **required**. Default = first **active** variant (prefer `MEDIUM` only if product team approves; currently defaults to first variant — see Section 18). | If `variant.isActive == false`, card is disabled (`alpha = 0.45f`), shows `"Unavailable"` text label, cannot be tapped, skipped during auto-default. | Full base price in `PizzoraMonoBody` (`₹299.00`, `₹549.00`). |
| **6.2 Crust** | Full-width tactile selectable rows (`minHeight = 56.dp`) with radio indicator + checkmark/ring contrast. | Single-select, **required** (`CartItemInputDto.crustId` is non-null). Default = first **active** crust (prefer `extraPricePaise == 0L` if available, else first active). | Inactive crusts disabled with `"Unavailable"` label and skipped by auto-default. If all crusts are empty/inactive, CTA disables with inline explanation. | `"Included"` in `secondary` (MintBasil / FreshBasilGreen) when `0L`; `"+ ₹50.00"` in `PizzoraMonoBody` otherwise. |
| **6.6 Extra Toppings** | Structured selectable rows (`minHeight = 56.dp`) with checkbox semantics, `VegIndicator` (with semantic text), and price delta. Header includes `All / Veg Only` segmented filter (only shown when both veg and non-veg toppings exist) + `"Clear"` text button when `>= 1` selected. | Multi-select, **optional** (0 to N). Distinct per topping ID (matching `cart_item_toppings` PK). | Inactive toppings disabled with `"Sold out"` label; cannot be toggled on. | `"+ ₹25.00"` (or `"Included"` if `0L`) in `PizzoraMonoBody`. |
| **6.8 Quantity** | Compact `48.dp`-height pill/rounded stepper (`-`, tabular mono count, `+`). | Min `1`, Max `25` (`MAX_ITEM_QUANTITY`). | `-` disabled at `1`; `+` disabled at `25` with helper note `"Max 25 per item"` when cap reached. | Immediately multiplies unit price and updates sticky breakdown + CTA total. |

### 10.2 Extensible Section Slots & Documented Contract Gaps **[Gap + Proposal]**
Per your clarification, **do not fabricate mock pricing or fake business rules** for options absent from the backend. Instead, build the screen around a typed `CustomizationSectionModel` slot list so future contract additions drop directly into the layout without redesign:

| Future Capability | Current Contract / Schema Status **[Fact]** | UI Slot Design (Ready for Future Contract) **[Proposal]** | Unresolved Business Rules Required Before Enabling |
|---|---|---|---|
| **6.3 Cheese** | **Not in `CatalogDto`, `CartDto`, or `V1__core_schema.sql`.** | Slot positioned after Crust (`Choose Cheese`). Uses single-select radio cards (if exclusive) or multi-select rows (if add-on), following the exact `ModifierOptionRow` component with `"Included"` / `"+ ₹..."`. | Is Cheese required or optional? Single-select (e.g., Regular / Extra / No Cheese / Vegan) or multi-select? Does it vary by pizza size? |
| **6.4 Sauces** | **Not in `CatalogDto`, `CartDto`, or `V1__core_schema.sql`.** | Slot positioned after Cheese (`Choose Sauce`). Single-select or max-N selectable rows using `ModifierOptionRow`. | Required base sauce swap or optional drizzle add-ons? Single vs. multi-select limit? Size-dependent pricing? |
| **6.5 Standard Toppings (Included vs. Extra)** | **Not in `ProductDto`.** Only free-text `description` exists; no `includedToppings` list. | When `product.standardToppings` exists, render a compact `"Included on this pizza"` section before Extra Toppings showing standard ingredients with optional `"Remove"` toggle (no discount unless backend specifies), clearly separated from `"Add Extra Toppings"`. | Can standard toppings be removed? Does removing a standard topping reduce price or allow free swaps? |
| **6.7 Half-and-Half Customization** | **Not in `CatalogDto`, `CartDto`, `OrderDto`, or DB schema.** | Dedicated toggle (`Whole Pizza` / `Half & Half`) placed immediately below **Size & Crust** (since Size & Crust apply to the whole pie). When `Half & Half` is active, display a clean visual split-circle diagram (Left Half / Right Half selector tabs with distinct warm accent vs. subtle sage/cream border and explicit `"Left Half"` / `"Right Half"` text labels — never color-only) showing each half's chosen flavor/toppings and combined pricing rule transparently in the breakdown drawer. | Pricing rule (highest half, 50/50 average, or surcharge)? Allowed only on `MEDIUM`/`LARGE`? Can toppings be placed on Left, Right, or Whole? How does KDS print half-and-half tickets? |

### 10.3 Edge Cases, Loading, Offline & Submission States
- **Store Paused (`!isAcceptingOrders`):** Display a compact warm-error inline banner at the top of the builder (`"Kitchen is currently paused. You can preview prices, but ordering is unavailable."`) and disable the primary CTA (`"Kitchen Paused"`).
- **Offline Cache Active (`isFromOfflineCache`):** Display a subtle inline note inside the breakdown drawer (`"Offline menu — final prices & stock will sync when online."`). Allow adding to local Room cart (preserving offline-first architecture tested in `Phase7CartAndMenuRobolectricTest`).
- **Preventing Duplicate Submissions:** Track local `isSubmitting: Boolean` state when `Add to Cart` is tapped; immediately disable CTA and show a compact `18.dp` `CircularProgressIndicator`, invoke `onConfirmAdd`, and reset/dismiss cleanly once accepted (or re-enable and show an inline error banner if repository returns a non-offline hard error).
- **Empty Variants or Empty Crusts:** If a product has no active variants or no active crusts, show a clear inline unavailability callout and disable the CTA instead of allowing an invalid submission.

### 10.4 Phase 2 Flow — Editing an Existing Cart Item **[Proposal]**
- **Trigger:** Add an `"Edit"` (`Icons.Filled.Edit` / text button) action on each `CartItemCard` in `CartScreen.kt` (Phase 2).
- **Behavior:** Opens the exact same Custom Pizza Builder pre-populated with the existing item's `variantId`, `crustId`, `toppingIds`, and `quantity`, matched against the cached/live `ProductDto` from `CustomerCatalogViewModel`.
- **CTA Copy:** Changes from `"Add to Cart · ₹..."` to `"Update Item · ₹..."`.
- **Persistence:** Replaces the existing cart item (or merges if the edited configuration now matches another cart line) via a new `CartRepository.updateCustomizedItem(...)` method in Phase 2, keeping `PUT /api/v1/cart` contract unchanged.

---

## 11. Pricing Presentation

### 11.1 Expandable Breakdown Drawer (User-Confirmed Choice)
- **Default Collapsed State (Always Visible Above Action Row):**
  - Left: 1-line truncated configuration summary (`"Large · Sourdough Pan · +2 toppings"` or `"Regular · Hand-Tossed"`).
  - Right: Interactive toggle `"Price details"` + animated chevron (`KeyboardArrowUp` / `KeyboardArrowDown`, `minHeight = 40.dp` tap target across the full summary strip).
- **Expanded State (Smoothly Animates Upward Within Bottom Dock, Capped at 40% Screen Height With Internal Scroll if Many Toppings):**
  - **Base Pizza (`{Size}`):** `Money.formatInr(variant.pricePaise)`
  - **Crust (`{Crust Name}`):** `"Included"` (green) or `"+ ₹..."`
  - *(Future slots: Cheese, Sauce, Half-and-Half adjustment rows appear automatically only when non-null)*
  - **Extra Toppings (`N`):** `"+ ₹..."` with compact indented sub-list of selected toppings & individual prices (when `N > 0`).
  - **Hairline Divider**
  - **Item Subtotal (`× {qty}`):** `Money.formatInr(totalPricePaise)` in `PizzoraMonoTitle` (coral accent).
  - **Transparency Footnote:** `"Estimated item total · GST & delivery calculated at Cart & Checkout"` in `bodySmall` (`onSurfaceVariant`).
- **Zero Duplication / Zero Misleading Totals:**
  - Never show fake pre-applied coupon discounts or guessed delivery fees inside the pizza builder.
  - Clearly communicate that backend pricing (`PUT /api/v1/cart` & `/api/v1/pricing/quote`) is authoritative.
- **Sticky CTA Button:**
  - Single clear label: `"Add to Cart · ₹1,248.00"` (or `"Update Item · ₹1,248.00"` in Phase 2 edit mode), keeping the exact currency substring `₹1248.00` (via `Money.formatInr`) compatible with existing UI tests!
  - *(Note: Verify `Money.formatInr(124_800L)` format — `Phase7CartAndMenuRobolectricTest` asserts `assertTextContains("₹1248.00", substring = true)`. Keep using `Money.formatInr(...)` verbatim so formatting remains 100% consistent!)*

---

## 12. Responsive Behavior

| Breakpoint / Device Class | Container & Layout | Size Selector | Toppings Layout | Bottom Dock & Safe Areas |
|---|---|---|---|---|
| **Small Android Phones (`320dp–359dp`, e.g., 360dp compact or 200% font scale)** | Full-width edge-to-edge screen, `16.dp` horizontal padding, compact `150.dp` hero image. | Automatically stacks vertically if width `< 360.dp` or `fontScale >= 1.5f` so `"Regular / ₹299.00"` never wraps awkwardly. | Single-column rows (`minHeight = 56.dp`), topping name uses `weight(1f)` with `maxLines = 2`. | If `width < 360.dp` or `fontScale >= 1.5f`, quantity stepper and CTA stack cleanly or stepper compacts to `44dp–48dp` icon buttons so CTA text never clips. `statusBarsPadding()` + `navigationBarsPadding()` applied once at builder root. |
| **Standard Android Phones (`360dp–411dp`)** | Full-screen builder, `20.dp` horizontal padding, `190.dp` hero image. | 3-column equal-width segmented cards (`Arrangement.spacedBy(10.dp)`). | Single-column rows with `VegIndicator` + label + right-aligned mono price. | Sticky bottom bar (summary strip + stepper + coral CTA). Total sticky height kept compact (`<= 124.dp` collapsed). |
| **Large Android Phones (`412dp–599dp`)** | Full-screen builder, `20.dp` horizontal padding, `210.dp` hero image. | 3-column segmented cards with generous breathing room. | Single-column rows (max readability, easy right-thumb reach). | Same sticky bottom bar, spacious CTA. |
| **Android Tablets (`>= 600dp`, matching `CustomerRoleShell`'s `useRail` breakpoint)** | Centered max-width `1040.dp` **2-pane split layout**: Left `38%` sticky product hero + live itemized receipt; Right `62%` scrollable options + sticky bottom action row. | 3-column segmented cards in Right pane. | 2-column grid in Right pane when Right pane width `>= 520.dp`, else 1-column. | Breakdown is always visible in Left pane (no cramped mobile drawer needed); bottom action row anchors to Right pane bottom with `navigationBarsPadding()`. |
| **Desktop** | **Not supported** by the current Android-only repository. No desktop-specific hover/windowing work should be invented. | — | — | — |

---

## 13. Accessibility Considerations (WCAG 2.2 AA Target)

1. **Contrast Compliance (Dark & Light Modes):**
   - Replace `outline.copy(alpha = 0.4f)` with full-opacity visible interactive borders (`outline` `#3F3732` boosted or dedicated `interactiveBorder` `#5A4F48` in dark mode for `>= 3:1` non-text contrast against `#1D1A18`; `#8C7E73` in light mode against `#FFFFFF`).
   - Eliminate alpha-diluted `Surface` containers (`copy(alpha = 0.35f / 0.55f)`) so `MaterialTheme` content colors always match container luminance (`>= 4.5:1` for normal text, `>= 3:1` for large text and UI components).
2. **Touch Targets (`>= 48.dp`):**
   - Every selectable card/row, Close/Back button, Veg filter chip, breakdown expand strip, quantity `-` / `+` button, and primary CTA enforces `minimumInteractiveComponentSize()` (`>= 48.dp`).
3. **Selected State Beyond Color Alone:**
   - Selected Size card: `2.dp` coral border + warm tinted fill + explicit check icon (`Icons.Filled.CheckCircle`) + Bold label.
   - Selected Crust row: `2.dp` coral border + filled radio icon + SemiBold label.
   - Selected Topping row: `2.dp` basil/coral border + checked checkbox icon + SemiBold label.
   - Unavailable options: explicit `"Unavailable"` / `"Sold out"` text badge (never grayed-out opacity alone).
4. **Screen Reader (TalkBack) Semantics:**
   - Use `Modifier.selectable(selected = isSelected, enabled = isAvailable, role = Role.RadioButton, onClick = ...)` on Size and Crust rows (inside `Modifier.selectableGroup()`).
   - Use `Modifier.toggleable(value = checked, enabled = isAvailable, role = Role.Checkbox, onValueChange = ...)` on Topping rows.
   - Add explicit `Modifier.semantics { contentDescription = if (isVeg) "Vegetarian" else "Non-vegetarian" }` to `VegIndicator` (or merge into parent row's `contentDescription` so TalkBack reads: *"Extra Basil, Vegetarian, plus 25 rupees, unchecked, checkbox"*).
   - Mark the live total text with `liveRegion = LiveRegionMode.Polite` (debounced on user interaction) and give `-`/`+` buttons contextual descriptions (`"Decrease quantity, current quantity 2"`).
   - Mark the breakdown toggle strip with `stateDescription = if (expanded) "Expanded" else "Collapsed"`.
5. **Dynamic Text Sizing (Up to 200% Font Scale):**
   - Never hardcode fixed `height(...)` on text-bearing rows; use `heightIn(min = 56.dp)` and `Modifier.weight(1f)` on text columns so text wraps cleanly without clipping.
   - Switch 3-column Size row to stacked vertical cards when `LocalDensity.current.fontScale >= 1.4f`.
6. **Reduced Motion:**
   - Check `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` (via a tiny Compose helper) and snap `AnimatedVisibility` / breakdown expand transitions immediately when animations are disabled on the device.
7. **Hardware Keyboard / D-Pad Focus:**
   - Standard Compose `selectable`/`toggleable`/`clickable` + `focusable` ordering ensures logical top-to-bottom tab order: Close -> Size -> Crust -> Toppings -> Breakdown Toggle -> Quantity `-`/`+` -> Add to Cart; `Esc` / system Back dismisses cleanly via `BackHandler`.

---

## 14. Component Reuse Strategy

### Reuse Verbatim **[Fact]**
- `PizzoraTheme`, `MaterialTheme.colorScheme`, `LocalPizzoraSemanticColors`, `PizzoraShapes`
- `PizzoraTypography`, `PizzoraMonoBody`, `PizzoraMonoTitle`
- `Money.formatInr(...)` from `:shared:contracts`
- Coil `AsyncImage` pattern and `R.drawable.img_pizzora_hero_oven_1794579613265` (warm fallback backdrop when a pizza has no uploaded photo)
- `VegIndicator` (enhanced cleanly with optional semantic description without breaking `CustomerMenuScreen`)
- `CustomerCatalogViewModel` & `CartViewModel` state architecture

### Minimal New Reusable Builder Components **[Proposal]**
Keep all new components focused in `com.example.catalog.builder` (or co-located under `com.example.catalog`):
1. `PizzaBuilderHeader`: Hero image + gradient scrim + collapsing compact top bar with live configuration subtitle.
2. `CustomizationSectionHeader`: Reusable section header with title, `"Required"` / `"Optional"` / `"(N selected)"` unboxed text metadata, and optional trailing action (`Clear`).
3. `SizeSelectorSection`: Adaptive 3-up card strip (vertical fallback on narrow/large-font viewports) with `selectableGroup` semantics and unavailable handling.
4. `ModifierOptionRow`: Single shared tactile row component powering **Crust**, **Toppings**, and future **Cheese / Sauce** slots (parameterized by single-select radio vs. multi-select checkbox, optional `VegIndicator`, price text, and availability state).
5. `BuilderBottomBar`: Sticky dock combining the **Expandable Price Breakdown Drawer**, `QuantityStepper` (shared pattern with `CartScreen` in Phase 2), and loading-aware coral CTA.

---

## 15. Proposed Implementation Stages

### Stage 1 — Foundation & Core Customizer V1 Overhaul (No API/Schema Changes)
- Add `bodySmall`, `labelSmall`, `PizzoraMonoCaption`, and 2 semantic surface/border tokens to `ui/theme/`.
- Add semantic `contentDescription` support to `VegIndicator`.
- Hoist customizer presentation from inside `CustomerMenuScreen` to `CustomerRoleShell` (eliminating `TopAppBar`/`NavigationBar` clipping and fixing safe-area insets).
- Rebuild `PizzaCustomizerSheet` into the new adaptive Custom Pizza Builder (`PizzaBuilderHeader`, `SizeSelectorSection`, `ModifierOptionRow` for Crust & Toppings with `All / Veg Only` toggle, `BuilderBottomBar` with expandable itemized price breakdown drawer, duplicate-submit guard, store-closed banner, and tablet 2-pane layout).
- Define extensible `CustomizationSectionSlot` model (non-rendering when empty, ready for Cheese/Sauce/Standard/Half-and-Half).
- Preserve all existing `testTag`s and pass full Robolectric & Compose UI test suite.

### Stage 2 — Cart Re-Edit Flow & Visual Polish
- Add `"Edit"` action to `CartItemCard` in `CartScreen.kt`.
- Add `editingCartItemId` support to builder state and `CartRepository.updateCustomizedItem(...)` (updating/merging in Room + `PUT /api/v1/cart` with zero API contract changes).
- Unify `QuantityStepper` component between Builder and `CartScreen`.
- Polish reduced-motion support, large font-scale layout checks, and inline error/offline messaging.

### Stage 3 — Future Contract-Backed Modifiers *(Blocked on Backend & Shared Contracts)*
- Only after backend/contracts approve schemas & pricing rules for **Cheese**, **Sauces**, **Standard vs. Extra Toppings**, and **Half-and-Half**:
  - Wire new DTO fields into `CustomizationSectionSlot` renderers and `BuilderBottomBar` breakdown rows.
  - Enable conditional Section Jump Bar when `visibleSectionCount >= 4`.

---

## 16. Testing Strategy

1. **Regression Safety on Existing Tests:**
   - Preserve exact `testTag`s used by `Phase7CartAndMenuRobolectricTest` and `Phase19EndToEndRobolectricTest` (`pizza_customizer_sheet`, `customizer_close_button`, `size_option_${sizeCode.name}`, `crust_option_${crust.id}`, `topping_option_${topping.id}`, `customizer_qty_minus`, `customizer_qty_value`, `customizer_qty_plus`, `add_to_cart_button`).
2. **New Compose + Robolectric Unit UI Tests (`Phase21PizzaBuilderRobolectricTest.kt`):**
   - **Pricing & Breakdown:** Verify collapsed summary text, expanding the breakdown drawer shows exact Base + Crust + Toppings + Qty + Total rows, and updates live on every tap.
   - **Availability & Edge Cases:** Verify `isActive == false` variant/crust/topping cannot be selected and is skipped by default selection; verify store-paused (`isAcceptingOrders == false`) disables CTA; verify max quantity `25` disables `+` and min `1` disables `-`.
   - **Duplicate-Submit Guard:** Verify rapid double-click on `add_to_cart_button` only triggers `onConfirmAdd` once.
   - **Topping Veg Filter:** Verify toggling `Veg Only` hides non-veg toppings without clearing already-selected veg toppings.
   - **Accessibility Semantics:** Assert `Role.RadioButton` / `Role.Checkbox`, `Selected` / `ToggleableState`, and `VegIndicator` content descriptions via `ComposeTestRule` semantics matchers.
   - **Responsive & Font Scale Matrix:** Run Robolectric `@Config(qualifiers = ...)` tests across:
     - Small phone (`w320dp-h640dp`) + `fontScale = 1.5f`
     - Standard phone (`w411dp-h891dp`)
     - Tablet (`w840dp-h900dp`, verifying 2-pane layout renders simultaneously without overflow)
3. **Visual / Screenshot Testing Gap:**
   - **Fact:** Neither Paparazzi nor Roborazzi is currently in `libs.versions.toml` or `app/build.gradle.kts` (which also uses Gradle dependency locking via `app/gradle.lockfile`).
   - **Recommendation:** Add `@Preview` functions (Dark & Light, Phone & Tablet, Collapsed & Expanded breakdown, 1.5x font scale) in Stage 1 for instant visual inspection; optionally evaluate adding Roborazzi in a separate dependency-lock update if CI golden screenshot diffs are desired.

---

## 17. Design Evaluation Scorecard (V1 Proposal — Honest Critical Audit)

| Category | Weight | V1 Score | Reasoning | Identified Weaknesses | Concrete Improvements for V2 / Implementation |
|---|---:|---:|---|---|---|
| **Visual identity and consistency** | 15 | **13 / 15** | Preserves PIZZORA's warm espresso dark palette (`#121110` / `#1D1A18`), `Fraunces` + `Plus Jakarta Sans` + `JetBrains Mono` typography, coral CTAs, and green veg markers while fixing alpha-washed surfaces and Roboto fallbacks. | Light mode still exists via `isSystemInDarkTheme()`; subtle warm-tint selected cards must be visually verified in *both* dark and light modes so light mode doesn't look washed out. | Validate exact hex contrast ratios on both themes via dual-theme Compose `@Preview`s before finalizing token values. |
| **Visual hierarchy** | 15 | **13 / 15** | Hero pizza image with scrim, Fraunces title, horizontal 3-up Size cards, structured Crust/Topping rows, and anchored coral CTA create clear primary-to-secondary scanning order. | When a product has no uploaded photo (`imageUrl == null`), falling back to the generic oven hero image could look repetitive across all pizzas. | Design a clean, restrained typographic/warm-stone fallback header (compact height, no repetitive full-height stock banner) when `product.images` is empty. |
| **Customization usability** | 20 | **15 / 20** | Clear required vs. optional labeling, Veg filter on toppings, unavailable states, live configuration summary, and extensible slots significantly improve over today's flat form. | 4 of the 8 functional scope items (Cheese, Sauces, Standard vs. Extra Toppings, Half-and-Half) cannot function live until backend/contracts add schemas & pricing rules; Half-and-Half interaction UX is still conceptual. | Produce detailed interaction wireframes for Half-and-Half and Standard Toppings once product rules (pricing formula, size eligibility) are confirmed. |
| **Pricing clarity** | 15 | **13 / 15** | Expandable breakdown drawer shows Base + Crust + Toppings × Qty with tabular mono figures and explicit `"GST & delivery calculated in Cart"` notice; eliminates misleading totals. | Expandable drawer requires 1 tap to inspect line-by-line math on phones, and users who already have the same pizza in Cart aren't warned if adding will hit the 25-unit merge cap. | Add subtle helper note if `existingCartQty + quantity > 25` (Phase 2) and ensure the collapsed summary always displays selected option count + live total clearly. |
| **Responsive layout** | 10 | **8.5 / 10** | Covers small (320dp), standard (360–412dp), large phones, and tablet 2-pane (`>= 600dp`), with automatic vertical size stacking at narrow widths / high font scale. | Landscape phone orientation (wide width `~800dp`, short height `~360dp`) could trigger `>= 600dp` tablet 2-pane mode with very cramped vertical space if width-only breakpoint is used! | Gate 2-pane layout on **`maxWidth >= 600.dp && maxHeight >= 480.dp`** (WindowSizeClass-style check) rather than width alone. |
| **Accessibility** | 10 | **8.5 / 10** | Upgrades to proper `Role.RadioButton`/`Role.Checkbox`, `selectableGroup`, `VegIndicator` semantics, non-color selected states, >=3:1 interactive borders, 48dp touch targets, font scaling, and reduced-motion check. | `LiveRegionMode.Polite` on fast quantity tapping can queue verbose TalkBack announcements if not throttled; custom collapsing hero scroll must preserve clean TalkBack focus order. | Keep `liveRegion` strictly on explicit feedback/errors or announce debounced totals; verify focus order on TalkBack with collapsing header pinned outside scroll tree. |
| **Interaction feedback** | 10 | **8 / 10** | Adds duplicate-submit lock, inline spinner, smooth breakdown expand/collapse, clear disabled states, and offline/paused banners. | `CartViewModel.addCustomizedPizza` currently fires asynchronously and emits a snackbar string without returning a completion/failure callback directly to the builder UI. | Return a lightweight `AddToCartResult` or expose `isAddingToCart` / `addError` on `CartViewModel` (minimal ViewModel enhancement, no API change) so the builder only dismisses after local Room persistence succeeds. |
| **Technical feasibility** | 5 | **4.5 / 5** | 100% achievable in pure Jetpack Compose with zero new dependencies, zero API/DB changes for Stage 1–2, and full backward compatibility with existing Robolectric tests. | Hoisting the builder to `CustomerRoleShell` touches `PizzoraNavHost.kt` wiring slightly and requires ensuring `Phase7` / `Phase19` tests still locate `pizza_customizer_sheet` seamlessly. | Keep `CustomerMenuScreen`'s `onOpenCustomizer` callback signature clean and run all Robolectric tests immediately after Stage 1 wiring. |
| **Total** | **100** | **83.5 / 100** | **Solid, realistic V1 baseline** — major leap over the current form while honestly reflecting unimplemented backend modifier contracts and responsive landscape/TalkBack nuances to resolve before implementation. | Biggest gaps: backend contract absence for Cheese/Sauces/Half-and-Half, landscape phone breakpoint guard, and empty-image fallback polish. | Resolve Decision Gates below, tighten V2 spec if needed, then execute Stage 1. |

---

## 18. Unresolved Decisions Requiring User Approval

Please review and confirm the following decisions before any implementation begins:

1. **Presentation Surface on Phones (Concept B vs. Concept A):**
   - **Recommended:** **Full-screen builder** hosted at `CustomerRoleShell` (Concept B on phones + 2-pane split on tablets `>= 600dp × 480dp`), giving maximum food photography space, zero `TopAppBar`/`NavigationBar` overlap, and clean reuse for Phase 2 Cart editing.
   - **Alternative:** **92%-height modal bottom sheet** hosted at `CustomerRoleShell` (Concept A on phones + centered 2-pane modal dialog on tablets) if you want the dimmed menu peeking behind the top edge.
2. **Default Size Selection Rule:**
   - Currently `PizzaCustomizerSheet` defaults to `product.variants.firstOrNull()` (usually `REGULAR`). Should V1 keep defaulting to the **first active variant (lowest price)**, or default to **`MEDIUM`** when available?
3. **Fallback Header When a Pizza Has No Uploaded Image:**
   - **Recommended:** Compact warm dark surface header (no full-height generic oven photo) so pizzas without photos don't waste vertical space or repeat the menu banner.
   - **Alternative:** Show the wood-fired oven drawable (`img_pizzora_hero_oven_1794579613265`) at a compact `120dp` height with dark scrim.
4. **Toppings Dietary Filter Visibility:**
   - **Recommended:** Show a compact `All / Veg Only` segmented toggle in the Extra Toppings header whenever the catalog contains both veg and non-veg toppings (and auto-filter or highlight veg toppings when the pizza itself is Veg). Confirm if you want `All` by default even on Veg pizzas.
5. **Future Modifier Business Rules (For Stage 3 Backlog — Non-Blocking for Stage 1–2):**
   - Confirm whether Stage 1–2 should proceed strictly with **Size, Crust, Extra Toppings, and Quantity** (plus extensible UI slots) while logging **Cheese, Sauces, Standard Toppings, and Half-and-Half** as separate backend/contract tasks.
6. **Theme Posture Confirmation:**
   - Confirm whether `PizzoraTheme` should continue honoring Android system light/dark mode (`isSystemInDarkTheme()`) with both schemes verified, or if customer ordering is intended to default to the dark warm palette.

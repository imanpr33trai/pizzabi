# PIZZORA — Firebase-Free Architecture & Implementation Plan

PIZZORA is a production-oriented pizza ordering, Kitchen Display System (KDS), and restaurant operations platform built on a 100% self-hosted Kotlin stack: an Android Jetpack Compose application communicating with an independently deployable Ktor modular monolith backed by PostgreSQL, Redis, S3-compatible object storage (MinIO in development), SMTP email delivery, and Razorpay. Every Firebase product, Google Services Gradle plugin, and FCM-dependent push service is completely excluded.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> **Workspace Inspection Findings**: No pre-existing `implementation_plan.md` was present in the repository or artifact directory. The repository is currently an uncustomized Android starter template whose Gradle configuration (`app/build.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`) contains template Firebase BOM/SDKs, Google Services plugin, Credential Manager/Google ID, and Retrofit + Moshi dependencies, plus placeholder `""` / `"My Application"` metadata. Phase 1 strips all Firebase/Google Services/Moshi plumbing and restructures the workspace into a multi-module monorepo.

### Confirmed Decisions (From Clarification & Revision)
- **Android HTTP & WebSocket Stack**: **Ktor Client with Kotlin Serialization** (`kotlinx.serialization`), replacing Retrofit + Moshi for unified HTTP, WebSocket, and shared DTO serialization across Android and backend.
- **Background Notifications (Zero-Firebase)**: **WebSockets + Best-Effort WorkManager Inbox Sync + KDS Live Sync** (with pre-implementation verification of Android 14–16 `dataSync` foreground-service eligibility and Play Policy compliance), backed by a persistent PostgreSQL `notification_inbox` and local Android notifications.
- **Repository Organization**: **Multi-module monorepo** with `:shared:contracts` (pure Kotlin Serialization DTOs, enums, state machines, money helpers), `:backend` (Ktor server + background worker), `:app` (Android Compose client), and deployment/Docker Compose infrastructure.
- **Visual Aesthetic**: **Warm Artisanal Pizzeria** (terracotta, wood-fired cream, basil accents) for Customer & Owner flows paired with a **High-Contrast Dark KDS** mode for kitchen tablets.
- **Initial Role Scope**: Exactly **three roles** (`CUSTOMER`, `KITCHEN_STAFF`, `OWNER`) matching the three Android experiences. `DELIVERY_STAFF` driver tracking is explicitly deferred to an optional post-v1 enhancement; `KITCHEN_STAFF` and `OWNER` manage dispatch transitions (`READY -> OUT_FOR_DELIVERY -> COMPLETED`).

### Remaining Technical Trade-Offs Requiring Awareness
1. **Customer Background Push Latency**: Without FCM (or a UnifiedPush distributor backed by FCM), Android Doze mode and App Standby Buckets restrict background execution. Customers receive sub-second updates while the app is active via WebSocket; when backgrounded/closed, `WorkManager` `PeriodicWorkRequest` (minimum 15-minute OS interval) is **best-effort only** and not guaranteed every 15 minutes. If sub-minute closed-app push becomes mandatory later, a self-hosted UnifiedPush distributor (`ntfy`) can be plugged into the replaceable `NotificationDeliveryProvider` interface.
2. **KDS Foreground Service vs. Play Store Policy**: On Android 14+ (`targetSdk = 36`), a persistent `foregroundServiceType="dataSync"` service is **not automatically Play Store compliant** and faces OS runtime limits (e.g., Android 15+ 6-hour/24h `dataSync` quota) and Play Console policy justification checks. Before Phase 13 implementation, we must verify target SDK constraints and either use an active-screen KDS tablet mode (`FLAG_KEEP_SCREEN_ON` + foreground WebSocket) or satisfy Play Console `dataSync` / enterprise kiosk requirements.
3. **Monorepo Gradle Sync Overhead**: Hosting `:app`, `:backend`, and `:shared:contracts` in one Gradle build enables compile-time API contract verification, while requiring isolated version catalog entries so Android-only plugins never leak into JVM backend modules.

---

## 1. Overview & Core Concept

- **What It Does**: Unifies three role-separated experiences in one Android application backed by a single self-hosted server platform:
  1. **Customer Ordering App**: Registration/email verification/password recovery, menu discovery, custom pizza builder (size, crust, extra toppings), offline-capable cart, coupon application, delivery-zone / `Asia/Kolkata` scheduled slot selection, Razorpay (UPI/Cards) & Cash on Delivery (COD) checkout with payment retries, live order tracking, cancellation, and notification inbox.
  2. **Kitchen Display System (KDS)**: Real-time incoming ticket queue, one-tap order acknowledgment, prep stage transitions, server-timestamped prep timers, priority/expedite sorting, audible chimes with mute toggle, persistent WebSocket connection (subject to Play/OS policy verification), and cursor-safe missed-event replay on reconnect.
  3. **Owner Operations Console**: Real-time KPI dashboard, catalog & pricing CRUD, image upload via zero-permission Android Photo Picker, ingredient inventory & recipe management, coupon campaigns, delivery-zone & slot capacity configuration, GST/restaurant settings, order/refund intervention, dispatch completion, staff management, sales/operational reports, and immutable audit logs.
- **Target Personas**: Customers on mobile phones; kitchen line staff on dedicated tablets; restaurant owners/managers monitoring operations and configuring inventory, taxes, and pricing.
- **Key Value**: Full ownership of customer PII, financial ledgers, inventory integrity, and deployment infrastructure with zero vendor lock-in or per-MAU SaaS fees.

---

## 2. User Experience & Visual Design

### Visual Identity & Theme
- **Aesthetic Direction**:
  - **Customer & Owner Experience**: *Warm Artisanal Pizzeria* — tactile, appetizing, editorial warmth. Warm oven-stone cream canvas, charred-crust charcoal ink, terracotta-tomato primary actions, and fresh-basil status indicators.
  - **Kitchen Display System (KDS)**: *High-Contrast Dark Operations* — deep espresso-slate background (`#121110`) to reduce kitchen glare, high-legibility tabular numbers for timers, and unambiguous semantic color-coding (amber = new/pending, sky = prepping, emerald = ready, crimson = SLA overdue).
- **Color Palette & Tokens (`Theme.kt` / `Color.kt`)**:
  - *Light (Customer / Owner)*: `background = #FFF9F3`, `surface = #FFFFFF`, `surfaceVariant = #F5EBE0`, `primary = #C83E2B` (San Marzano Terracotta), `onPrimary = #FFFFFF`, `secondary = #2D6A4F` (Fresh Basil), `tertiary = #D97706` (Golden Crust), `onBackground = #1E1916`, `outline = #DFD3C5`.
  - *Dark (KDS & Optional Dark Mode)*: `background = #121110`, `surface = #1D1A18`, `surfaceVariant = #2A2522`, `primary = #EF644F`, `secondary = #52B788`, `tertiary = #FBBF24`, `error = #F87171`, `onBackground = #F7F2EC`.
- **Typography & Hierarchy**:
  - Bundled locally as `.ttf` assets in `res/font/` via `font-util` (never Downloadable Fonts):
    - **Display / Headings**: `Fraunces` (editorial warmth for pizza titles and hero headers).
    - **Body / UI Controls**: `Plus Jakarta Sans` (crisp legibility across dense menus, forms, and checkout).
    - **Tabular Timers / Money / KDS Tickets**: `JetBrains Mono` (tabular alignment for paise/rupee totals, GST breakdowns, order codes, and countdowns).
- **Component Styling & Adaptive Layout**:
  - Material 3 components with `enableEdgeToEdge()`, `WindowInsets.safeDrawing` / `navigationBarsPadding()` protection, and minimum `48.dp` touch targets on every interactive element.
  - Adaptive Window Size Classes (`Compact`, `Medium`, `Expanded`): Customer uses bottom `NavigationBar` on phones and `NavigationRail` + two-pane menu/cart layout on tablets; KDS renders a responsive multi-column ticket board (`BoxWithConstraints`) on tablets and a single-column prioritized queue on phones.
- **Interactive Feedback & Motion**:
  - Spring transitions (`spring()`) under `300ms` for pizza customizer sheets and cart pill expansions; Material ripples on all clickable cards; dedicated skeleton loaders, illustrated empty states (empty cart, no active orders, zero KDS tickets), and actionable error banners with retry triggers.

### Key User Flows
1. **Customer Ordering & Retry Flow**: Register & verify email -> Browse Categories -> Customize Size (Regular/Medium/Large), Crust, Extra Toppings -> Add to Offline Room Cart -> Choose Delivery (saved/new address + pincode validation) or Pickup -> Choose ASAP or Scheduled Slot (`Asia/Kolkata`) -> Apply Coupon -> Review Server-Computed Pricing & GST -> Submit `POST /checkout` (with `Idempotency-Key`) -> For Razorpay: wait for `payment.gateway_ready` WebSocket event or short poll on `GET /orders/{id}/payment` until `gateway_order_id` is ready -> Complete Razorpay Sheet (UPI/Card) -> If a payment attempt fails or is abandoned, retry via `POST /orders/{id}/payments/retry` while order remains `PENDING_PAYMENT` and unexpired -> Track live status via WebSocket -> Cancel (if allowed) or View Receipt/History.
2. **Kitchen Display Flow**: Staff Login -> Role Gate routes to KDS -> Establish authenticated WebSocket (using `POST /auth/ws-ticket` or Bearer header) + play chime on `order.created` -> Tap *Acknowledge* -> Tap *Start Prep* (server timestamp starts SLA timer) -> Tap *Mark Ready* -> Dispatch (`OUT_FOR_DELIVERY` / `COMPLETED`) -> Reconnect automatically replays missed `stream_seq` events without gaps.
3. **Owner Operations Flow**: Owner Login -> Dashboard (today's revenue, active orders, low-stock alerts) -> Manage Categories/Products/Variants/Recipes -> Pick Product Photo via zero-permission Android Photo Picker -> Client compresses JPEG/WebP -> Upload via S3/MinIO presigned PUT -> Configure Inventory Stock, Coupons, Delivery Zones, Slots, GST bps, Staff Accounts, Reports, and Audit Logs.

---

## 3. Key Product Decisions & Trade-Offs

1. **Modular Monolith Backend (Ktor + Kotlin) over Microservices**
   - *Chosen Approach*: Single deployable Ktor API server plus a separate background worker entrypoint sharing the same core domain, repository, and database modules.
   - *Why*: Eliminates distributed-transaction complexity across Checkout, Inventory Reservation, Coupon Reservation, Slot Reservation, and Order Creation—all execute atomically inside one PostgreSQL ACID transaction.
   - *Alternatives Considered*: Microservices (rejected due to distributed transaction overhead and operational complexity).
2. **PostgreSQL as Single Source of Truth; Redis Strictly Ephemeral**
   - *Chosen Approach*: All financial, order, payment, payment attempt, coupon, slot, inventory ledger, and event-stream state resides in PostgreSQL with row-level locks and `CHECK` constraints. Redis handles short-TTL menu/settings caching, sliding-window rate limits, short-lived `ws-ticket` tokens, and optional worker lock optimization.
   - *Why*: Complete Redis outage, restart, or eviction cannot cause overselling, double coupon redemptions, slot overbooking, lost payments, or broken sequence replay.
3. **Authoritative Outbox-Driven Razorpay Initialization (Non-Blocking)**
   - *Chosen Approach*: `POST /checkout` commits a local PostgreSQL transaction creating `orders` (`PENDING_PAYMENT`), `payments` (`UNPAID`), `payment_attempts` (`attempt_number = 1`, `INITIATING`), reservations, and `outbox_events` (`PAYMENT_GATEWAY_ORDER_REQUESTED`). Outside any database transaction, `OutboxDispatcherWorker` calls Razorpay Orders API (`receipt = attempt.id`), persists `gateway_order_id`, transitions `payment_attempts -> AWAITING_CUSTOMER_PAYMENT`, and emits `payment.gateway_ready`. Android receives the `gateway_order_id` via WebSocket push or polling `GET /orders/{id}/payment`.
   - *Why*: Zero external HTTP calls ever run inside PostgreSQL transactions, preventing connection-pool exhaustion during Razorpay latency spikes while giving durable, idempotent retryability.
   - *Trade-Off*: Adds ~100–400ms asynchronous worker hop before the Razorpay sheet opens, mitigated by immediate WebSocket notification + fast exponential polling fallback on Android.
4. **Integer Paise (`Long` / `BIGINT`) + `BigInteger` Intermediate Math**
   - *Chosen Approach*: All money stored as paise (`1 INR = 100 paise`). GST and percentage coupon calculations promote `(amount * bps)` to `BigInteger` with deterministic `HALF_UP` rounding (`((amount * bps) + 5000) / 10000`) before returning `Long`.
   - *Why*: Zero floating-point drift and overflow safety.
5. **Separated Outbox Sequencing & At-Least-Once WebSocket Dispatch (No `BIGSERIAL` Gaps)**
   - *Chosen Approach*: Checkout and domain transactions insert `outbox_events` with `stream_seq = NULL`, `dispatch_status = 'PENDING'`, `published_at = NULL`, and `aggregate_version` (matching `orders.version`). Stage 1 (`EventSequencerWorker`, holding `pg_advisory_xact_lock`) polls committed `WHERE stream_seq IS NULL` rows, assigns strictly monotonic gap-free `stream_seq` values from `event_stream_cursor`, and sets `sequenced_at` while keeping `dispatch_status = 'PENDING'` and `published_at = NULL`. Stage 2 (`OutboxDispatcherWorker`) claims sequenced `PENDING` rows via `FOR UPDATE SKIP LOCKED`, broadcasts over WebSockets / writes `notification_inbox`, and marks `dispatch_status = 'DISPATCHED'` with `published_at = NOW()` **only after successful delivery**.
   - *Why*: Raw `BIGSERIAL` allocates numbers before commit, causing slower transactions to commit after faster ones and silently vanish past a naive `seq > cursor` query; separating sequencing from delivery ensures a crash after sequence assignment never sets `published_at` or skips undelivered events. Clients deduplicate redeliveries by `event_id`.

---

## 4. Technical Architecture & Data Strategy

### System Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────────┐
│                           ANDROID APPLICATION (:app)                       │
│  ┌────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐  │
│  │ Customer Feature   │  │ KDS Feature         │  │ Owner Feature       │  │
│  │ Browse, Cart, Pay  │  │ Live Board, Timers  │  │ Menu, Stock, Reports│  │
│  └─────────┬──────────┘  └──────────┬──────────┘  └──────────┬──────────┘  │
│            └────────────────────────┼─────────────────────────┘            │
│                                     ▼                                      │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ ViewModels -> UseCases -> Repositories (Manual DI: AppContainer)     │  │
│  ├─────────────────────────┬──────────────────────┬─────────────────────┤  │
│  │ Room DB (Cart, Cache,   │ Ktor Client          │ WorkManager Sync +  │  │
│  │ Notification Inbox)     │ (REST + WebSockets)  │ KDS Active Sync     │  │
│  └─────────────────────────┴──────────┬───────────┴──────────┬──────────┘  │
└───────────────────────────────────────┼──────────────────────┼─────────────┘
                                        │ HTTPS / WSS          │
                                        ▼                      ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                      REVERSE PROXY (Caddy / Nginx + TLS)                   │
└───────────────────────────────────────┬────────────────────────────────────┘
                                        │
                                        ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                 KTOR MODULAR MONOLITH (:backend + :shared:contracts)       │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ HTTP API (/api/v1/*) + Auth/RBAC + Rate Limit + WebSocket Hub        │  │
│  ├──────────────────────────────────────────────────────────────────────┤  │
│  │ Domain Modules: Auth | Catalog | Pricing | Coupons | Checkout |      │  │
│  │ Orders | Payments/Razorpay | Inventory/Recipes | Zones/Slots |       │  │
│  │ Kitchen | Notifications | Audit | Reporting                          │  │
│  └──────┬───────────────────────────┬────────────────────────┬──────────┘  │
│         │                           │                        │             │
│         ▼                           ▼                        ▼             │
│  ┌──────────────┐          ┌─────────────────┐      ┌───────────────────┐  │
│  │ PostgreSQL   │          │ Redis           │      │ S3 / MinIO        │  │
│  │ (Primary DB, │◄────────►│ (Ephemeral Cache│      │ (Product Images,  │  │
│  │ Outbox, ACID)│          │ Rate Limit, WS) │      │ Presigned URLs)   │  │
│  └──────▲───────┘          └─────────────────┘      └───────────────────┘  │
│         │                                                                  │
│  ┌──────┴───────────────────────────────────────────────────────────────┐  │
│  │ Background Worker Process (Event Sequencer, Razorpay Order/Refund    │  │
│  │ Worker, Expiry Sweeps, Razorpay Reconciler, Notification Dispatcher) │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
```

### Order, Payment & PaymentAttempt State Machines

**1. Order State Machine (`OrderStatus`)**
- **States**: `PENDING_PAYMENT`, `PLACED`, `ACCEPTED`, `PREPARING`, `READY`, `OUT_FOR_DELIVERY`, `COMPLETED`, `CANCELLED`, `EXPIRED`.
- **Terminal States**: `COMPLETED`, `CANCELLED`, `EXPIRED`.
- **Key Rules & Authorization**:
  - `PENDING_PAYMENT`: Initial state for online (Razorpay) orders. **A failed `PaymentAttempt` does NOT terminate the order.** The order stays `PENDING_PAYMENT` until `expires_at` (15 minutes from checkout), allowing the customer to initiate retry `PaymentAttempt`s.
  - `PENDING_PAYMENT -> PLACED`: System/Worker only, upon a `PaymentAttempt` transitioning to `CAPTURED`. (COD orders are created directly in `PLACED` inside the checkout transaction.)
  - `PENDING_PAYMENT -> EXPIRED`: Background Expiry Worker only, when `NOW() >= expires_at` and no attempt is `CAPTURED` (releases `inventory_reservations`, `coupon_reservations`, and `scheduled_slot_reservations`, and marks open attempts `EXPIRED`).
  - `PENDING_PAYMENT -> CANCELLED`: `CUSTOMER` or `OWNER` (releases all active reservations immediately).
  - `PLACED -> ACCEPTED`: `KITCHEN_STAFF` or `OWNER` (records `accepted_at`).
  - `ACCEPTED -> PREPARING`: `KITCHEN_STAFF` or `OWNER` (records `prep_started_at`).
  - `PREPARING -> READY`: `KITCHEN_STAFF` or `OWNER` (records `ready_at`).
  - `READY -> OUT_FOR_DELIVERY`: `KITCHEN_STAFF` or `OWNER` (DELIVERY orders only).
  - `READY -> COMPLETED`: `KITCHEN_STAFF` or `OWNER` (PICKUP orders only).
  - `OUT_FOR_DELIVERY -> COMPLETED`: `KITCHEN_STAFF` or `OWNER` (marks COD `COD_COLLECTED` if COD).
  - `PLACED | ACCEPTED -> CANCELLED`: `CUSTOMER` (strictly before `PREPARING` begins) or `OWNER` (allowed up to `READY` with mandatory reason + audit log). Triggers inventory restoration (before prep), coupon/slot release, and automatic refund initiation if `payments.status == CAPTURED`.

**2. Aggregate Payment State Machine (`PaymentStatus` on `payments`)**
- **States**: `UNPAID`, `COD_PENDING`, `COD_COLLECTED`, `CAPTURED`, `EXPIRED`, `CANCELLED`, `REFUND_PENDING`, `PARTIALLY_REFUNDED`, `REFUNDED`.
- **Terminal States**: `COD_COLLECTED`, `REFUNDED`, and `EXPIRED` / `CANCELLED` (terminal for normal unpaid flows, subject only to the single exceptional late-capture transition below).
- **Tracks**: `captured_paise` and `refunded_paise` on `payments`.
- **Transitions**:
  - Razorpay normal flow: `UNPAID -> CAPTURED` (when a child `PaymentAttempt` captures while order is `PENDING_PAYMENT`, setting `captured_paise = amount_paise`) -> `REFUND_PENDING -> PARTIALLY_REFUNDED | REFUNDED`. Or `UNPAID -> EXPIRED | CANCELLED` when parent order expires/cancels before capture.
  - Razorpay exceptional late-capture flow: If a capture arrives when `orders.status IN ('EXPIRED', 'CANCELLED')`, `payments.status` executes an explicit exceptional transition `EXPIRED | CANCELLED -> REFUND_PENDING` (persisting `captured_paise = amount_paise` and recording the capture event), never reopening or fulfilling the order. Subsequent refund completion transitions `REFUND_PENDING -> REFUNDED`; if refund attempts fail, `refunds.status` moves to `FAILED` while `payments.status` stays `REFUND_PENDING` until idempotent reconciliation/manual retry completes the refund.
  - COD flow: `COD_PENDING` (created atomically at checkout with `orders.status = PLACED`) -> `COD_COLLECTED` (upon `orders.status -> COMPLETED`, setting `captured_paise = amount_paise`) or `COD_PENDING -> CANCELLED` (upon order cancellation).

**3. Payment Attempt State Machine (`PaymentAttemptStatus` on `payment_attempts`)**
- **States**: `INITIATING`, `AWAITING_CUSTOMER_PAYMENT`, `AUTHORIZED`, `CAPTURED`, `FAILED`, `EXPIRED`, `CANCELLED`.
- **Terminal States**: `CAPTURED`, `FAILED`, `EXPIRED`, `CANCELLED`.
- **Transitions**:
  - Razorpay: `INITIATING` (created in checkout/retry DB tx) -> `AWAITING_CUSTOMER_PAYMENT` (outbox worker persists `gateway_order_id` from Razorpay) -> `AUTHORIZED` (optional webhook) -> `CAPTURED` (verified client signature or `payment.captured` webhook).
  - Failure / Retry: `INITIATING | AWAITING_CUSTOMER_PAYMENT -> FAILED` (gateway creation failure after retries, `payment.failed` webhook, or customer-reported cancellation/failure; parent `payments` remains `UNPAID` and `orders` remains `PENDING_PAYMENT` until `order.expires_at`).
  - Expiry / Order Cancel: `INITIATING | AWAITING_CUSTOMER_PAYMENT -> EXPIRED | CANCELLED`.
  - COD: Single `PaymentAttempt` created in `AWAITING_CUSTOMER_PAYMENT` (cash due upon delivery/pickup) -> `CAPTURED` (when `COD_COLLECTED`, recording `collected_by_user_id`) or `CANCELLED`.
  - **Late-Capture, Idempotent Refunds & Reconciliation**: If `payment.captured` webhook or delayed reconciliation detects a `CAPTURED` attempt after `orders.status` is already `EXPIRED` or `CANCELLED`, the order remains terminal (`EXPIRED`/`CANCELLED`), is never sent to KDS, and reservations stay released. Inside one DB transaction, the system transitions `payment_attempts.status -> CAPTURED`, records `payments.captured_paise = amount_paise`, executes the exceptional `payments.status` transition `EXPIRED | CANCELLED -> REFUND_PENDING`, creates an idempotent `refunds` row (`idempotency_key = "late_capture:${paymentAttemptId}"` UNIQUE, `status = 'PENDING'`, `reason = 'LATE_CAPTURE_AFTER_ORDER_TERMINAL'`, `initiated_by = NULL`), and enqueues `REFUND_REQUESTED` in `outbox_events`. `RefundWorker` calls Razorpay Refunds API outside DB transactions passing deterministic refund idempotency (`refunds.idempotency_key`), retrying transient failures with exponential backoff. If retries exhaust (`outbox_events.dispatch_status = 'DEAD_LETTER'`) or gateway rejects permanently, `refunds.status -> FAILED` while `payments.status` stays `REFUND_PENDING`, an `OWNER` alert is written to `notification_inbox` + `audit_logs`, and `RazorpayReconciliationWorker` idempotently polls `/payments/{id}/refunds` (alongside Owner retry `POST /owner/orders/{id}/refund`) until reconciled to `PROCESSED` / `REFUNDED` without ever issuing duplicate refunds.

### Authoritative Transactional Checkout & Outbox Pipeline

Steps 1–8 execute inside **one atomic PostgreSQL transaction**, ordered strictly so validation, calculations, and parent `orders` creation occur before any child FK row is inserted:

1. **Idempotency-Key Validation**: Check `Idempotency-Key` header against `orders (customer_id, idempotency_key)`. Matching `request_hash` returns the existing order + latest `payment_attempt` details (`gateway_order_id` if ready); mismatched hash returns `409 Conflict`.
2. **Catalog, Zone, Slot, Inventory & Coupon Validation + Calculation**:
   - Load `products`, `product_variants`, `crust_options`, `toppings`, and `restaurant_settings` (GST bps); reject inactive items (`422`) and compute subtotal in integer paise (`Long`).
   - For `DELIVERY`, verify `address_id` ownership and active `delivery_zones` (`subtotal >= min_order_paise`, add `delivery_fee_paise`).
   - For `SCHEDULED`, lock `scheduled_slots` (`FOR UPDATE`), verify `is_active`, `cutoff_at > NOW()` (`Asia/Kolkata`), and `reserved_count < max_capacity`, then increment `reserved_count += 1`.
   - Expand items via `variant_recipes`, `crust_recipes`, and `topping_recipes` into an `ingredient_id -> required_qty` map sorted ascending, lock `inventory` (`FOR UPDATE`), verify `on_hand_qty - reserved_qty >= required_qty`, and update `reserved_qty += required_qty` (Razorpay) or `on_hand_qty -= required_qty` (COD).
   - If `coupon_code` provided, lock `coupons` (`FOR UPDATE`), validate window, min order, global/per-user caps, compute discount (`BigInteger` `HALF_UP`), and for COD increment `coupons.usage_count += 1`.
   - Compute final taxable amount, CGST/SGST (`BigInteger` `HALF_UP`), and `total_paise`, and generate `order_id` (`UUIDv7`) and `expires_at` (`NOW() + 15 minutes` for Razorpay; `NULL` for COD).
3. **Insert Parent Order First (`orders`)**: Insert the parent `orders` row (`id = order_id`, `version = 1`, `expires_at`, `status = PENDING_PAYMENT` for Razorpay or `PLACED` for COD), guaranteeing the referenced parent key exists before any dependent row is written.
4. **Insert Dependent Order Items & History (`order_items`, `order_status_history`)**: Insert all `order_items` snapshots and the initial `order_status_history` entry referencing `orders.id`.
5. **Insert Dependent Reservations (`scheduled_slot_reservations`, `inventory_reservations`, `coupon_reservations`/`coupon_redemptions`)**: Insert `scheduled_slot_reservations` (`ACTIVE` for Razorpay; `COMMITTED` for COD), `inventory_reservations` (`ACTIVE` / `COMMITTED`), and `coupon_reservations` (plus `coupon_redemptions` for COD) referencing `orders.id`.
6. **Insert Inventory Ledger Entries (`inventory_ledger`)**: Append `RESERVE` (`operation_id = "reserve:${orderId}:${ingredientId}"`) or `COMMIT` (`operation_id = "commit:${orderId}:${ingredientId}"`) rows referencing `orders.id`.
7. **Insert Payment Records (`payments`, `payment_attempts`)**: Insert `payments` (`UNPAID` or `COD_PENDING`) and initial `payment_attempts` (`attempt_number = 1`, `INITIATING` for Razorpay or `AWAITING_CUSTOMER_PAYMENT` for COD) referencing `orders.id`.
8. **Insert Outbox Event (`outbox_events`) & Commit**: Insert `outbox_events` (`aggregate_version = 1`, `stream_seq = NULL`, `sequenced_at = NULL`, `dispatch_status = 'PENDING'`, `published_at = NULL`, event `PAYMENT_GATEWAY_ORDER_REQUESTED` for Razorpay or `ORDER_PLACED` for COD) and commit the PostgreSQL transaction.
9. **Outbox-Driven Razorpay Order Creation (Strictly Outside DB Transaction)**: `OutboxDispatcherWorker` claims `PAYMENT_GATEWAY_ORDER_REQUESTED`, calls Razorpay Orders API (`amount`, `INR`, `receipt = attempt_id`), then in a short DB transaction sets `payment_attempts.gateway_order_id`, transitions `INITIATING -> AWAITING_CUSTOMER_PAYMENT`, inserts `PAYMENT_GATEWAY_READY` in `outbox_events`, and marks the original outbox row `dispatch_status = 'DISPATCHED'` (`published_at = NOW()`). Android receives `gateway_order_id` via WebSocket or polling `GET /orders/{id}/payment`.
10. **Payment Capture, Commit & Expiry/Reconciliation**: On Razorpay `POST /payments/razorpay/verify` or `payment.captured` webhook (HMAC-SHA256 verified + `gateway_event_id` deduplicated), a PostgreSQL transaction locks `payments` & `orders`, transitions `payment_attempts -> CAPTURED`, `payments -> CAPTURED` (`captured_paise = amount_paise`), increments `orders.version` & sets `orders.status -> PLACED`, commits `inventory_reservations` (`on_hand_qty -= qty, reserved_qty -= qty` + `COMMIT` ledger), commits `coupon_reservations -> coupon_redemptions` (`usage_count += 1`), commits `scheduled_slot_reservations -> COMMITTED`, and inserts `ORDER_PLACED` outbox event. Expiry worker sweeps `orders WHERE status = 'PENDING_PAYMENT' AND expires_at <= NOW()` to expire orders/attempts and release inventory, coupon, and `scheduled_slot_reservations`.

---

## 5. Backend Architecture & Modules (Ktor Modular Monolith)

| Module | Responsibility | Domain Entities & DB Tables | Services & Repository Interfaces | API Endpoints & AuthZ | Tx Boundaries, Failure Scenarios & Tests |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1. Authentication** | Registration, email verification, login, Argon2id, JWT access tokens (15m), rotating hashed refresh tokens (30d), password recovery, single-use WS ticket minting. | `UserSession`, `RefreshToken`, `EmailVerificationToken`, `PasswordResetToken`, `WsTicket`; `users`, `user_sessions`, `refresh_tokens`, `email_verification_tokens`, `password_reset_tokens`, `ws_tickets`. | `AuthService`, `JwtTokenProvider`, `PasswordHasher`, `EmailDeliveryProvider`; `SessionRepository`, `RefreshTokenRepository`, `AuthTokenRepository`, `WsTicketRepository`. | `POST /auth/register`, `/verify-email`, `/resend-verification`, `/login`, `/refresh`, `/logout`, `/forgot-password`, `/reset-password`, `/ws-ticket`. | Refresh rotation & token reuse family revocation in single DB tx; reset/verification tokens stored as SHA-256 hashes, single-use, 15m–24h TTL; `ws-ticket` stored in Redis (`GETDEL`) with PG `ws_tickets` single-use fallback. Tests: rotation replay, password reset session purge, single-use `ws-ticket` under Redis outage. |
| **2. Users & Profiles** | Customer profile management, saved delivery addresses, owner-provisioned staff accounts. | `User`, `Address`, `StaffProfile`; `users`, `addresses`, `staff`. | `UserService`, `AddressService`, `StaffService`; `UserRepository`, `AddressRepository`, `StaffRepository`. | `GET/PATCH /me`, `CRUD /me/addresses`, `GET/POST/PATCH /owner/staff` (Self / Owner). | Object-level check `address.userId == currentUserId`. Staff creation limited to `KITCHEN_STAFF` or `OWNER`. Tests: IDOR prevention test, staff deactivation session purge. |
| **3. Authorization** | 3-role RBAC (`CUSTOMER`, `KITCHEN_STAFF`, `OWNER`), granular permissions, object-level ownership guards. | `Role`, `Permission`, `RolePermission`; `roles`, `permissions`, `role_permissions`. | `AuthorizationService`, `PermissionEvaluator`; `RoleRepository`. | Enforced via Ktor route plugin `requirePermission(...)` & service guards. | Read-only cached in Redis (`5m` TTL) with direct DB fallback if Redis unavailable. Missing claim -> `403`. Tests: full 3-role permission matrix test. |
| **4. Catalog** | Products, variants (`REGULAR`/`MEDIUM`/`LARGE`), crust options, extra toppings, availability toggles. | `Product`, `ProductVariant`, `CrustOption`, `Topping`; `products`, `product_variants`, `crust_options`, `toppings`. | `CatalogService`; `ProductRepository`, `ModifierRepository`. | `GET /catalog/menu` (Public), `POST/PATCH/DELETE /owner/products`, `/variants`, `/crusts`, `/toppings` (Owner). | Product + variants created atomically. Redis menu cache invalidated post-commit (fallback to DB on Redis outage). Tests: active-only customer visibility, price integrity checks. |
| **5. Categories** | Menu category grouping, display ordering, active visibility. | `Category`; `categories`. | `CategoryService`; `CategoryRepository`. | `GET /catalog/categories` (Public), `POST/PATCH/DELETE /owner/categories` (Owner). | Prevent delete if active products reference category (`409 CATEGORY_IN_USE`). Tests: sort order & FK constraint tests. |
| **6. Product Images** | S3/MinIO presigned upload URLs, MIME/size/magic-byte validation, image metadata binding, orphan cleanup. | `ProductImage`; `product_images`. | `ProductImageService`, `ObjectStorageClient`; `ProductImageRepository`. | `POST /owner/products/{id}/images/presign`, `POST .../confirm`, `DELETE .../images/{imgId}` (Owner). | Presign outside tx; confirm validates S3 `HEAD` + magic bytes (`JPEG`/`PNG`/`WebP`, <= 5MB) before committing DB row. MinIO outage returns `503 OBJECT_STORAGE_UNAVAILABLE` only on image upload/confirm while core ordering & browsing continue with cached/placeholder images. Tests: MinIO Testcontainer upload/reject oversized/spoofed MIME. |
| **7. Cart Validation** | Server-side validation of client cart items against current catalog, availability, and zone constraints. | `Cart`, `CartItem`, `CartItemTopping`; `carts`, `cart_items`, `cart_item_toppings`. | `CartService`; `CartRepository`. | `GET/PUT /cart`, `POST /cart/validate` (Customer). | Replaces cart items + relational `cart_item_toppings` inside tx; flags unavailable modifiers cleanly. Tests: stale local price ignored, deactivated topping flagged. |
| **8. Pricing** | Deterministic integer-paise calculation (`Long` + `BigInteger` `HALF_UP`), discounts, delivery fee, configurable CGST/SGST. | `PriceBreakdown`, `TaxCalculation`; reads `restaurant_settings`, catalog, coupons, zones. | `PricingEngine` (pure domain), `PricingService`. | `POST /pricing/quote` (Customer). | Pure deterministic logic. Overflow/negative guard assertions. Tests: parameterized paise rounding & GST split tests. |
| **9. Coupons** | Promo rules, eligibility, expiry, global & per-user limits, atomic reservation, commit, and release. | `Coupon`, `CouponReservation`, `CouponRedemption`; `coupons`, `coupon_reservations`, `coupon_redemptions`. | `CouponService`; `CouponRepository`. | `GET /coupons/available` (Customer), `CRUD /owner/coupons` (Owner). | Row lock `FOR UPDATE` on `coupons` during checkout reservation; unique `(coupon_id, order_id)` prevents double commit. Tests: 20-thread concurrent last-use coupon race test. |
| **10. Checkout** | Orchestrates transactional checkout (idempotency, catalog, zone, slot, inventory, coupon, order/payment/attempt, outbox). | `CheckoutRequest`, `CheckoutResult`; touches `orders`, `payments`, `payment_attempts`, `*_reservations`, `outbox_events`. | `CheckoutService`; composes domain repositories. | `POST /checkout` (Customer, requires `Idempotency-Key`). | Single PostgreSQL tx commits `PENDING_PAYMENT` (Razorpay) or `PLACED` (COD); zero external HTTP calls inside tx. Failure rolls back all reservations atomically. Tests: idempotency replay, mid-flight stock failure rollback. |
| **11. Orders** | Order lifecycle, state machine enforcement, order history, customer cancellation, owner intervention, dispatch updates. | `Order`, `OrderItem`, `OrderStatusHistory`; `orders`, `order_items`, `order_status_history`. | `OrderService`, `OrderStateMachine`; `OrderRepository`. | `GET /orders`, `GET /orders/{id}`, `POST /orders/{id}/cancel`, `PATCH /owner/orders/{id}/status`. | Row lock on `orders` during transition; invalid transition -> `409 INVALID_ORDER_TRANSITION`. Cancel restores stock (pre-prep) and enqueues refund if captured. Tests: full state matrix & cancel-after-prep rejection. |
| **12. Payments** | Aggregate `Payment` + `PaymentAttempt` lifecycle, payment retries, late-capture refunding, timeout/abandonment handling, refunds, reconciliation. | `Payment`, `PaymentAttempt`, `PaymentEvent`, `Refund`; `payments`, `payment_attempts`, `payment_events`, `refunds`. | `PaymentService`, `RefundService`; `PaymentRepository`, `PaymentAttemptRepository`, `RefundRepository`. | `GET /orders/{id}/payment`, `POST /orders/{id}/payments/retry`, `POST /payments/razorpay/verify`, `POST /owner/orders/{id}/refund`. | `payment_events.gateway_event_id` unique constraint deduplicates verify + webhook races; `PaymentAttempt` failure leaves order `PENDING_PAYMENT` for retry; late capture records `captured_paise` and sets `REFUND_PENDING` without reopening terminal orders. Tests: retry, concurrent verify+webhook, late-capture refund failure & reconciliation. |
| **13. Razorpay Integration** | Razorpay REST client (Orders, Payments fetch, Refunds) and HMAC-SHA256 signature verification. | `GatewayOrder`, `GatewayWebhookPayload`; backend-only secrets. | `RazorpayClient`, `RazorpaySignatureVerifier`, `WebhookService`. | `POST /webhooks/razorpay` (Public, HMAC-SHA256 verified on raw body). | Never runs inside DB tx. Invalid signature -> `401` + security audit log; duplicate event -> `200 OK` no-op. Tests: official Razorpay test vector verification + tampered payload rejection. |
| **14. Inventory** | Ingredient stock (`on_hand_qty`, `reserved_qty`, generated `available_qty`), atomic reserve/commit/release/restore, append-only ledger. | `Ingredient`, `InventoryStock`, `InventoryReservation`, `InventoryLedgerEntry`; `ingredients`, `inventory`, `inventory_reservations`, `inventory_ledger`. | `InventoryService`; `InventoryRepository`. | `GET /owner/inventory`, `POST /owner/inventory/adjust`, `GET /owner/inventory/ledger` (Owner). | Sorted `ingredient_id` locking prevents deadlocks; deterministic `operation_id` unique key prevents duplicate ledger entries. Tests: concurrent oversell, deadlock, and cancel-restore tests. |
| **15. Recipes** | Enforceable relational mappings from variants, crusts, and toppings to ingredient quantities. | `VariantRecipeItem`, `CrustRecipeItem`, `ToppingRecipeItem`; `variant_recipes`, `crust_recipes`, `topping_recipes`. | `RecipeService`; `RecipeRepository`. | `GET/PUT /owner/recipes` (Owner). | Pure FK-enforced recipe tables (`ON DELETE CASCADE` from target, `RESTRICT` on ingredient). Existing `order_items` remain snapshot-isolated. Tests: multi-modifier ingredient aggregation & FK enforcement tests. |
| **16. Delivery Zones** | Pincode serviceability, minimum order thresholds, delivery fee calculation, active toggle. | `DeliveryZone`; `delivery_zones`. | `DeliveryZoneService`; `DeliveryZoneRepository`. | `GET /delivery-zones/check?pincode=...` (Public), `CRUD /owner/delivery-zones` (Owner). | Unique normalized pincode; inactive zone rejects delivery checkout (`422 ZONE_UNSERVICEABLE`). Tests: pincode validation & min-order boundary tests. |
| **17. Scheduled Slots** | `Asia/Kolkata` delivery/pickup windows, capacity limits, cutoff enforcement, `scheduled_slot_reservations` lifecycle. | `ScheduledSlot`, `ScheduledSlotReservation`; `scheduled_slots`, `scheduled_slot_reservations`. | `ScheduledSlotService`; `ScheduledSlotRepository`. | `GET /slots?type=DELIVERY&date=YYYY-MM-DD` (Public), `CRUD /owner/slots` (Owner). | Guarded `scheduled_slot_reservations` transition ensures `reserved_count` never drifts or double-decrements. Tests: concurrent last-slot booking, expiry release, IST midnight rollover. |
| **18. Restaurant Config** | Store open/closed switch, prep SLA minutes, GST rate (bps), operating hours (`Asia/Kolkata`). | `RestaurantSettings`; `restaurant_settings`. | `RestaurantConfigService`; `RestaurantConfigRepository`. | `GET /config/public` (Public), `GET/PUT /owner/settings` (Owner). | Singleton config row (`id = 1`); cached in Redis (`1m` TTL, DB fallback on Redis outage). Store closed blocks checkout (`409 STORE_CLOSED`). Tests: GST change audit & store-closed checkout rejection. |
| **19. Kitchen Operations** | KDS live ticket projection, priority/expedite flags, acknowledgment, server-timestamp prep stages, dispatch handoff. | `KdsTicket`, `KdsEvent`; views over `orders` + `order_items` + `outbox_events`. | `KitchenService`; `KitchenRepository`. | `GET /kds/tickets`, `POST /kds/orders/{id}/ack|start-prep|ready|dispatch|complete`, `PATCH .../priority` (Kitchen/Owner). | Transitions `PLACED -> ACCEPTED -> PREPARING -> READY -> OUT_FOR_DELIVERY -> COMPLETED`. Returns `server_now` for skew-free timers. Tests: transition authZ & `stream_seq` replay test. |
| **20. Notifications** | Persistent inbox, WebSocket push when connected, notification preferences, replaceable delivery provider. | `NotificationInboxItem`, `NotificationPreference`; `notification_inbox`, `notification_preferences`. | `NotificationService`, `NotificationDeliveryProvider`; `NotificationRepository`. | `GET /notifications`, `POST /notifications/{id}/read`, `PUT /notifications/preferences` (Authenticated). | Created by `OutboxDispatcherWorker` idempotently per `(event_id, user_id)`. Delivery failures retry with backoff up to DLQ. Tests: outbox-to-inbox idempotency & preference opt-out test. |
| **21. Audit Logging** | Immutable record of owner/staff sensitive actions (price, GST, stock, staff, order overrides, refunds). | `AuditLog`; `audit_logs`. | `AuditService`; `AuditRepository`. | `GET /owner/audit-logs` (Owner). | Written inside the **same DB transaction** as the mutated entity. Append-only (no update/delete). Tests: rollback removes audit log; commit guarantees it. |
| **22. Reporting** | Daily/range revenue, GST summary, top-selling pizzas, coupon ROI, prep-time SLA metrics, stock wastage. | `SalesReport`, `OperationalReport`; read-only SQL aggregations. | `ReportingService`; `ReportingRepository`. | `GET /owner/reports/sales`, `GET /owner/reports/operations` (Owner). | Read-only queries using `Asia/Kolkata` day boundaries (`AT TIME ZONE 'Asia/Kolkata'`). Tests: IST timezone boundary & refund-adjusted net revenue test. |
| **23. Background Workers** | Separated Stage-1 `EventSequencerWorker` (`stream_seq` assignment) and Stage-2 `OutboxDispatcherWorker` (WS broadcast, inbox, Razorpay/Refund jobs), plus Expiry & Reconciler sweeps. | `OutboxEvent`, `EventStreamCursor`; `outbox_events`, `event_stream_cursor`. | `EventSequencerWorker`, `OutboxDispatcherWorker`, `ExpirySweepWorker`, `RazorpayReconciliationWorker`, `RefundWorker`. | Internal `/health/worker` liveness/metrics only. | Sequencer uses `pg_advisory_xact_lock`; Dispatcher claims sequenced rows via `FOR UPDATE SKIP LOCKED` with visibility lease and sets `dispatch_status = 'DISPATCHED'` only after delivery succeeds. Tests: crash-after-sequence recovery & out-of-order commit replay tests. |

---

## 6. Database Architecture (PostgreSQL + Redis)

### Topological Schema Order (Foreign Keys Always Follow Referenced Tables)
All primary keys use `UUID` (UUIDv7). All money columns use `BIGINT` (paise). All timestamps use `TIMESTAMPTZ` (UTC). Tables below are listed in strict DDL creation order so every `FOREIGN KEY` references an already-created table.

#### Group A: Identity, Verification, RBAC & Addresses
1. `roles (id PK, code UNIQUE)` (`CUSTOMER`, `KITCHEN_STAFF`, `OWNER`)
2. `permissions (id PK, code UNIQUE)`
3. `role_permissions (role_id FK->roles, permission_id FK->permissions, PK(role_id, permission_id))`
4. `users (id PK, email UNIQUE, email_verified_at TIMESTAMPTZ NULLABLE, password_hash, full_name, phone, is_active, created_at, updated_at)`
5. `staff (user_id PK FK->users, role_id FK->roles, employee_code UNIQUE, is_active, created_by FK->users, created_at)`
6. `user_sessions (id PK, user_id FK->users, device_name, ip_address, revoked_at, created_at, last_seen_at)` — index `(user_id, revoked_at)`
7. `refresh_tokens (id PK, session_id FK->user_sessions, family_id, token_hash UNIQUE, expires_at, rotated_at, revoked_at, created_at)` — indexes `(family_id)`, `(expires_at)`
8. `email_verification_tokens (id PK, user_id FK->users ON DELETE CASCADE, token_hash UNIQUE, expires_at, used_at, created_at)`
9. `password_reset_tokens (id PK, user_id FK->users ON DELETE CASCADE, token_hash UNIQUE, expires_at, used_at, created_at)`
10. `ws_tickets (ticket_hash PK, user_id FK->users ON DELETE CASCADE, role_code, session_id FK->user_sessions ON DELETE CASCADE, expires_at TIMESTAMPTZ NOT NULL, consumed_at TIMESTAMPTZ NULLABLE, created_at)` — partial index `(expires_at) WHERE consumed_at IS NULL`
11. `addresses (id PK, user_id FK->users, label, line1, line2, landmark, pincode, city, is_default, created_at)` — index `(user_id)`

#### Group B: Catalog, Modifiers & Images
12. `categories (id PK, name UNIQUE, sort_order, is_active, created_at)`
13. `products (id PK, category_id FK->categories, name, description, is_veg, is_active, is_taxable, created_at, updated_at)` — index `(category_id, is_active)`
14. `product_variants (id PK, product_id FK->products, size_code CHECK IN ('REGULAR','MEDIUM','LARGE'), price_paise CHECK >= 0, is_active, UNIQUE(product_id, size_code))`
15. `crust_options (id PK, name UNIQUE, extra_price_paise CHECK >= 0, is_active)`
16. `toppings (id PK, name UNIQUE, is_veg, extra_price_paise CHECK >= 0, is_active)`
17. `product_images (id PK, product_id FK->products, object_key UNIQUE, mime_type, size_bytes CHECK <= 5242880, status CHECK IN ('PENDING','ACTIVE','DELETED'), sort_order, uploaded_by FK->users, created_at)`

#### Group C: Ingredients, Inventory & Enforceable Relational Recipes
18. `ingredients (id PK, name UNIQUE, unit CHECK IN ('GRAMS','ML','UNITS'), low_stock_threshold CHECK >= 0, is_active)`
19. `inventory (ingredient_id PK FK->ingredients, on_hand_qty BIGINT CHECK >= 0, reserved_qty BIGINT CHECK >= 0, available_qty BIGINT GENERATED ALWAYS AS (on_hand_qty - reserved_qty) STORED, updated_at, CHECK (reserved_qty <= on_hand_qty))`
20. `variant_recipes (id PK, variant_id FK->product_variants ON DELETE CASCADE, ingredient_id FK->ingredients ON DELETE RESTRICT, quantity INT CHECK > 0, UNIQUE(variant_id, ingredient_id))`
21. `crust_recipes (id PK, crust_id FK->crust_options ON DELETE CASCADE, ingredient_id FK->ingredients ON DELETE RESTRICT, quantity INT CHECK > 0, UNIQUE(crust_id, ingredient_id))`
22. `topping_recipes (id PK, topping_id FK->toppings ON DELETE CASCADE, ingredient_id FK->ingredients ON DELETE RESTRICT, quantity INT CHECK > 0, UNIQUE(topping_id, ingredient_id))`

#### Group D: Carts (Normalized Relational Toppings)
23. `carts (id PK, user_id UNIQUE FK->users, updated_at)`
24. `cart_items (id PK, cart_id FK->carts ON DELETE CASCADE, product_id FK->products, variant_id FK->product_variants, crust_id FK->crust_options, quantity CHECK > 0, created_at, updated_at)`
25. `cart_item_toppings (cart_item_id FK->cart_items ON DELETE CASCADE, topping_id FK->toppings ON DELETE RESTRICT, PK(cart_item_id, topping_id))`

#### Group E: Coupons, Zones, Slots & Settings
26. `coupons (id PK, code UNIQUE, discount_type CHECK IN ('PERCENT_BPS','FLAT_PAISE'), discount_value CHECK > 0, max_discount_paise NULLABLE, min_order_paise CHECK >= 0, max_uses NULLABLE, max_uses_per_user CHECK >= 1, usage_count CHECK >= 0, starts_at, ends_at, is_active)`
27. `delivery_zones (id PK, pincode UNIQUE, area_name, min_order_paise CHECK >= 0, delivery_fee_paise CHECK >= 0, estimated_mins CHECK > 0, is_active)`
28. `scheduled_slots (id PK, slot_date DATE, fulfillment_type CHECK IN ('DELIVERY','PICKUP'), start_time_ist TIME, end_time_ist TIME, cutoff_at TIMESTAMPTZ, max_capacity INT CHECK > 0, reserved_count INT CHECK >= 0, is_active, CHECK (reserved_count <= max_capacity), UNIQUE(slot_date, fulfillment_type, start_time_ist))`
29. `restaurant_settings (id INT PK CHECK (id = 1), store_name, is_accepting_orders, gst_bps CHECK BETWEEN 0 AND 2800, default_prep_mins, timezone DEFAULT 'Asia/Kolkata', updated_at)`

#### Group F: Orders (Created Before Reservation, Ledger & Payment Tables)
30. `orders (id PK, order_number UNIQUE, customer_id FK->users, idempotency_key, request_hash, fulfillment_type CHECK IN ('DELIVERY','PICKUP'), schedule_type CHECK IN ('ASAP','SCHEDULED'), scheduled_slot_id NULLABLE FK->scheduled_slots, delivery_address_snapshot JSONB, status, version BIGINT NOT NULL DEFAULT 1, subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise, gst_bps_snapshot, coupon_code_snapshot, is_priority BOOLEAN DEFAULT FALSE, accepted_at, prep_started_at, ready_at, completed_at, expires_at, created_at, UNIQUE(customer_id, idempotency_key))` — indexes `(status, created_at)`, `(customer_id, created_at DESC)`
31. `order_items (id PK, order_id FK->orders ON DELETE CASCADE, product_id, product_name_snapshot, variant_size_snapshot, crust_name_snapshot, toppings_snapshot JSONB, unit_price_paise, quantity CHECK > 0, line_total_paise)`
32. `order_status_history (id PK, order_id FK->orders, from_status NULLABLE, to_status, changed_by NULLABLE FK->users, reason, created_at)` — index `(order_id, created_at)`

#### Group G: Reservations & Inventory Ledger
33. `inventory_reservations (id PK, order_id FK->orders, ingredient_id FK->ingredients, quantity BIGINT CHECK > 0, status CHECK IN ('ACTIVE','COMMITTED','RELEASED','EXPIRED','RESTORED'), expires_at TIMESTAMPTZ NULLABLE, created_at, updated_at, UNIQUE(order_id, ingredient_id))` — partial index `(expires_at) WHERE status = 'ACTIVE'`
34. `inventory_ledger (id PK, ingredient_id FK->ingredients, order_id NULLABLE FK->orders, operation_id UNIQUE, delta_on_hand BIGINT, delta_reserved BIGINT, reason CHECK IN ('RESTOCK','RESERVE','COMMIT','RELEASE','EXPIRE_RELEASE','CANCEL_RESTORE','MANUAL_ADJUST'), actor_user_id NULLABLE FK->users, created_at)` — index `(ingredient_id, created_at DESC)`
35. `coupon_reservations (id PK, coupon_id FK->coupons, user_id FK->users, order_id UNIQUE FK->orders, discount_paise CHECK >= 0, status CHECK IN ('ACTIVE','COMMITTED','RELEASED','EXPIRED'), expires_at, created_at)` — index `(coupon_id, user_id, status)`
36. `coupon_redemptions (id PK, coupon_id FK->coupons, user_id FK->users, order_id UNIQUE FK->orders, discount_paise CHECK >= 0, redeemed_at)`
37. `scheduled_slot_reservations (id PK, slot_id FK->scheduled_slots, order_id UNIQUE FK->orders, status CHECK IN ('ACTIVE','COMMITTED','RELEASED','EXPIRED'), expires_at TIMESTAMPTZ NULLABLE, created_at, updated_at)` — partial index `(expires_at) WHERE status = 'ACTIVE'`

#### Group H: Payments, Payment Attempts, Events & Refunds
38. `payments (id PK, order_id UNIQUE FK->orders, method CHECK IN ('RAZORPAY','COD'), status, amount_paise CHECK > 0, captured_paise BIGINT NOT NULL DEFAULT 0 CHECK >= 0, refunded_paise BIGINT NOT NULL DEFAULT 0 CHECK (refunded_paise >= 0 AND refunded_paise <= captured_paise), currency DEFAULT 'INR', created_at, updated_at)`
39. `payment_attempts (id PK, payment_id FK->payments, attempt_number INT CHECK > 0, method CHECK IN ('RAZORPAY','COD'), status, amount_paise CHECK > 0, idempotency_receipt UNIQUE, gateway_order_id UNIQUE NULLABLE, gateway_payment_id UNIQUE NULLABLE, failure_code NULLABLE, failure_reason NULLABLE, collected_by_user_id NULLABLE FK->users, expires_at TIMESTAMPTZ NULLABLE, created_at, updated_at, UNIQUE(payment_id, attempt_number))` — partial index `(expires_at) WHERE status IN ('INITIATING','AWAITING_CUSTOMER_PAYMENT')`
40. `payment_events (id PK, payment_id NULLABLE FK->payments, payment_attempt_id NULLABLE FK->payment_attempts, source CHECK IN ('CLIENT_VERIFY','WEBHOOK','RECONCILER','OUTBOX_WORKER'), gateway_event_id UNIQUE, event_type, payload_json JSONB, signature_valid BOOLEAN, processed_at)`
41. `refunds (id PK, payment_id FK->payments, payment_attempt_id NULLABLE FK->payment_attempts, order_id FK->orders, idempotency_key UNIQUE, amount_paise CHECK > 0, status CHECK IN ('PENDING','PROCESSING','PROCESSED','FAILED'), gateway_refund_id UNIQUE NULLABLE, reason, initiated_by NULLABLE FK->users, created_at, updated_at)`

#### Group I: Commit-Ordered Event Stream, Notifications & Audit
42. `event_stream_cursor (id INT PK CHECK (id = 1), last_seq BIGINT NOT NULL DEFAULT 0)`
43. `outbox_events (id PK, aggregate_type, aggregate_id, aggregate_version BIGINT NOT NULL DEFAULT 1, event_type, payload_json JSONB, stream_seq BIGINT UNIQUE NULLABLE, sequenced_at TIMESTAMPTZ NULLABLE, dispatch_status CHECK IN ('PENDING','PROCESSING','DISPATCHED','DEAD_LETTER'), published_at TIMESTAMPTZ NULLABLE, attempts INT DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), last_error, created_at, CHECK ((dispatch_status = 'DISPATCHED' AND published_at IS NOT NULL) OR (dispatch_status <> 'DISPATCHED' AND published_at IS NULL)))` — partial indexes `(created_at, id) WHERE stream_seq IS NULL`, `(stream_seq) WHERE stream_seq IS NOT NULL`, and `(dispatch_status, next_attempt_at) WHERE dispatch_status IN ('PENDING','PROCESSING')`
44. `notification_inbox (id PK, user_id FK->users, event_id UUID, title, body, category, reference_id, is_read BOOLEAN DEFAULT FALSE, delivery_status CHECK IN ('PENDING','SENT_WS','SYNCED','FAILED'), created_at, UNIQUE(user_id, event_id))` — index `(user_id, is_read, created_at DESC)`
45. `notification_preferences (user_id PK FK->users, order_updates BOOLEAN DEFAULT TRUE, promo_alerts BOOLEAN DEFAULT TRUE, kds_sound_enabled BOOLEAN DEFAULT TRUE, updated_at)`
46. `audit_logs (id PK, actor_user_id FK->users, actor_role, action, entity_type, entity_id, before_json JSONB, after_json JSONB, correlation_id, ip_address, created_at)` — indexes `(created_at DESC)`, `(entity_type, entity_id)`

### Inventory & Slot Invariants
- **Inventory Semantics**: `on_hand_qty` = physical stock; `reserved_qty` = unpaid active checkout holds; `available_qty = on_hand_qty - reserved_qty` (PostgreSQL `GENERATED ALWAYS ... STORED` column). `RESERVE` adds to `reserved_qty`; `COMMIT` subtracts from both `on_hand_qty` and `reserved_qty` (or directly from `on_hand_qty` for COD); `RELEASE`/`EXPIRE_RELEASE` subtracts from `reserved_qty`; `CANCEL_RESTORE` (for pre-prep cancellation of placed orders) adds back to `on_hand_qty`.
- **Scheduled Slot Consistency**: `scheduled_slots.reserved_count` is mutated strictly alongside a guarded state transition on `scheduled_slot_reservations` (`WHERE status IN ('ACTIVE','COMMITTED')`), guaranteeing `reserved_count == COUNT(*) FROM scheduled_slot_reservations WHERE slot_id = s.id AND status IN ('ACTIVE','COMMITTED')` with zero double-decrements.

### Isolation, Migrations, Retention & Redis Outage Safety
- **Isolation Level**: PostgreSQL `READ COMMITTED` with explicit `SELECT ... FOR UPDATE` row locking in deterministic ascending ID order + strict `CHECK` constraints.
- **Migrations**: Versioned Flyway SQL scripts (`V1__...`, `V2__...`) matching the topological table order above.
- **Retention & Archival**: `audit_logs`, `inventory_ledger`, `orders`, `payments`, `payment_attempts`, and `refunds` retained >= 8 years (archived yearly after 24 months); `outbox_events` (`dispatch_status = 'DISPATCHED'`) and expired `refresh_tokens` / `ws_tickets` pruned after 30 days.
- **Redis Failure Resilience Guarantee**:
  - **Orders, Payments, Coupons, Inventory, Slots**: Zero dependency on Redis—all state, locks, and invariants live exclusively in PostgreSQL.
  - **Menu/Settings Cache & RBAC Cache**: On Redis timeout/connection error, services catch the exception, log a structured warning, and query PostgreSQL directly.
  - **Rate Limiter**: Fails open with an in-memory per-instance token-bucket fallback so legitimate checkouts are not blocked if Redis restarts.
  - **WebSocket Tickets (`ws-ticket`)**: Primary store in Redis (`30s` TTL, atomic `GETDEL`). If Redis is unavailable, `POST /auth/ws-ticket` stores a SHA-256 hashed 256-bit nonce in PostgreSQL `ws_tickets` (`30s` TTL) and `/ws/v1/events` consumes it atomically via `UPDATE ... WHERE consumed_at IS NULL AND expires_at > NOW()`, preserving strict single-use semantics without a replay window.
  - **Background Workers**: `EventSequencerWorker` uses PostgreSQL `pg_advisory_xact_lock`; `OutboxDispatcherWorker` uses PostgreSQL `FOR UPDATE SKIP LOCKED`; cron sweepers use PostgreSQL `pg_try_advisory_lock` as primary/fallback lock even if Redis is down.

---

## 6. API Design (REST & WebSockets)

### REST Conventions
- **Versioning**: Base path `/api/v1/...`.
- **Serialization**: `kotlinx.serialization` JSON (`snake_case`, `Long` paise, ISO-8601 UTC timestamps).
- **Headers**: `Authorization: Bearer <access_jwt>`, `X-Request-ID` (correlation ID), `Idempotency-Key` (UUID required on `POST /checkout`, `POST /orders/{id}/payments/retry`, `POST /payments/razorpay/verify`, `POST /owner/orders/{id}/refund`).
- **Pagination**: Cursor-based (`?cursor=<opaque>&limit=20`, max `100`).
- **Consistent Error Envelope**: `{ "error": { "code", "message", "details" }, "correlation_id", "timestamp" }`.

### Endpoint Inventory (Grouped by Role/Module)
- **Public & Auth**: `POST /auth/register`, `POST /auth/verify-email`, `POST /auth/resend-verification`, `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, `POST /auth/forgot-password`, `POST /auth/reset-password`, `POST /auth/ws-ticket` (Authenticated), `GET /config/public`, `GET /catalog/categories`, `GET /catalog/menu`, `GET /delivery-zones/check`, `GET /slots`, `POST /webhooks/razorpay` (HMAC-verified), `GET /health/live`, `GET /health/ready`.
- **Customer**: `GET/PATCH /me`, `GET/POST/PATCH/DELETE /me/addresses`, `GET/PUT /cart`, `POST /cart/validate`, `GET /coupons/available`, `POST /pricing/quote`, `POST /checkout`, `GET /orders/{id}/payment`, `POST /orders/{id}/payments/retry`, `POST /payments/razorpay/verify`, `GET /orders`, `GET /orders/{id}`, `POST /orders/{id}/cancel`, `GET /notifications`, `POST /notifications/{id}/read`, `PUT /notifications/preferences`.
- **Kitchen (`KITCHEN_STAFF` / `OWNER`)**: `GET /kds/tickets`, `POST /kds/orders/{id}/ack`, `POST /kds/orders/{id}/start-prep`, `POST /kds/orders/{id}/ready`, `POST /kds/orders/{id}/dispatch`, `POST /kds/orders/{id}/complete`, `PATCH /kds/orders/{id}/priority`, `GET /events/replay?since_seq=...`.
- **Owner Console (`OWNER`)**: `GET /owner/dashboard`, `CRUD /owner/categories`, `CRUD /owner/products`, `POST/DELETE /owner/products/{id}/images/presign|confirm|{imgId}`, `PUT /owner/recipes`, `GET/POST /owner/inventory`, `GET /owner/inventory/ledger`, `CRUD /owner/coupons`, `GET /owner/orders`, `PATCH /owner/orders/{id}/status`, `POST /owner/orders/{id}/refund`, `CRUD /owner/staff`, `CRUD /owner/delivery-zones`, `CRUD /owner/slots`, `GET/PUT /owner/settings`, `GET /owner/reports/sales`, `GET /owner/reports/operations`, `GET /owner/audit-logs`.

### WebSocket Architecture (`/ws/v1/events`) & Cursor-Safe Replay
- **Authentication**: Handshake via single-use `POST /auth/ws-ticket` (`?ticket=<30s_single_use_ticket>`, consumed atomically in Redis via `GETDEL` or PostgreSQL `ws_tickets` fallback) or `Authorization: Bearer <access_jwt>` header. Server verifies role (`CUSTOMER`, `KITCHEN_STAFF`, `OWNER`) and isolates rooms (`user:{id}`, `kds:kitchen`, `owner:dashboard`).
- **Separated Sequencing vs. Dispatch Pipeline**:
  - Domain transactions insert `outbox_events` with `stream_seq = NULL`, `sequenced_at = NULL`, `dispatch_status = 'PENDING'`, and `published_at = NULL`.
  - **Stage 1 (`EventSequencerWorker`)**: Holding `pg_advisory_xact_lock(EVENT_SEQ_LOCK)`, reads **already-committed** rows `WHERE stream_seq IS NULL ORDER BY created_at ASC, id ASC LIMIT 100`, assigns strictly monotonic gap-free `stream_seq` from `event_stream_cursor`, and sets `sequenced_at = NOW()` while preserving `dispatch_status = 'PENDING'` and `published_at = NULL`.
  - **Stage 2 (`OutboxDispatcherWorker`)**: Claims rows `WHERE stream_seq IS NOT NULL AND dispatch_status IN ('PENDING','PROCESSING') AND next_attempt_at <= NOW()` using `FOR UPDATE SKIP LOCKED` and a short `30s` `PROCESSING` visibility lease (`next_attempt_at`), broadcasts to authorized WebSocket rooms / writes `notification_inbox`, and updates `dispatch_status = 'DISPATCHED'` + `published_at = NOW()` **only after successful dispatch** (`published_at` is strictly `NULL` until `dispatch_status = 'DISPATCHED'`). Any crash after sequencing or mid-dispatch leaves the row reclaimable for exponential-backoff retry (`DEAD_LETTER` after max attempts); clients apply at-least-once deduplication by `event_id`.
- **Filtered Global Cursor, High-Water Mark & Per-Order Causal Versioning**:
  - Because `stream_seq` is global while rooms are authorization-filtered, clients **MUST NOT** treat gaps in received `stream_seq` values as missing events.
  - Every WebSocket batch/heartbeat and `GET /events/replay?since_seq=...` response (`{ events, server_high_water_seq, has_more }`) includes `server_high_water_seq` (the latest committed `stream_seq` evaluated by the server). Clients advance `last_seen_stream_seq = max(last_seen_stream_seq, server_high_water_seq)` and resume with `since_seq = last_seen_stream_seq`.
  - Every order event also carries `aggregate_version` (`orders.version`). Android `OrderTracker` and `KdsBoard` drop stale events (`aggregate_version <= local.version`) and trigger an immediate `GET /orders/{id}` or `GET /kds/tickets` snapshot resync if `aggregate_version > local.version + 1`. Events include `payment.gateway_ready`, `order.created`, `order.status_changed`, `kitchen.acknowledged`, `inventory.low_stock`, `owner.dashboard_updated`, and `notification.created`.

---

## 7. Notifications (Zero-Firebase Subsystem)

- **Foreground Real-Time Delivery**: Active Customer, KDS, and Owner apps receive events over `/ws/v1/events`, display in-app banners, trigger KDS audio chimes (with mute toggle), and sync `notification_inbox`.
- **Customer Background Delivery (Best-Effort WorkManager)**:
  - `OrderSyncWorker` (`WorkManager` `PeriodicWorkRequest`, 15-minute minimum interval) polls `GET /notifications?unread=true` when active orders exist and posts local `NotificationCompat` alerts.
  - *Explicit Limitation*: `WorkManager` periodic timing is **best-effort**, not guaranteed every 15 minutes due to Android Doze, App Standby Buckets, and OEM battery savers. Expedited one-off sync runs upon app resume and post-checkout.
- **KDS Continuous Delivery & Play Store Verification Gate**:
  - Dedicated kitchen tablets prioritize an active foreground session (`FLAG_KEEP_SCREEN_ON` + foreground WebSocket). If a background `KdsSyncForegroundService` (`android:foregroundServiceType="dataSync"`) is used, **target SDK (`targetSdk = 36`), Android 14/15/16 `dataSync` runtime quotas (6h/24h limit), and Google Play Foreground Service Policy eligibility MUST be verified before Phase 13 implementation**.
- **Replaceable Provider**: Backend dispatches through `NotificationDeliveryProvider` (`WebSocketInboxProvider` initially; future `UnifiedPushNtfyProvider` or `EmailDeliveryProvider` pluggable without altering Order/Outbox logic).

---

## 8. Security Architecture

- **Password Hashing & Recovery**: `Argon2id` (`m=65536`, `t=3`, `p=1`). Email verification & password reset tokens generated via 256-bit CSPRNG, stored as SHA-256 hashes with short TTL (15m reset / 24h verification), single-use enforced; successful password reset revokes all active `user_sessions` and `refresh_tokens`.
- **JWT & Refresh Tokens**: 15m JWTs (`HMAC-SHA256` or `Ed25519`). 256-bit CSPRNG opaque refresh tokens stored as SHA-256 hashes; rotation revokes old token and detects reuse to revoke the entire token family.
- **Authorization**: RBAC middleware for `CUSTOMER`, `KITCHEN_STAFF`, `OWNER` + mandatory object-level ownership checks (`order.customerId == callerId`, `address.userId == callerId`).
- **Owner & Staff Provisioning**: Zero hardcoded credentials. Initial owner provisioned via CLI `--bootstrap-owner` command; staff accounts created strictly by Owner API.
- **Input, SQLi & Upload Security**: Parameterized Exposed/JDBC queries; `kotlinx.serialization` strict decoding; zero-permission `PickVisualMedia` + client compression + backend S3 `HEAD` & magic-byte validation (`<= 5MB`).
- **Webhook, Transport & Secrets**: Raw body `HmacSHA256(RAZORPAY_WEBHOOK_SECRET)` verification; `gateway_event_id` deduplication; TLS 1.2+ (`HSTS`, Android `network_security_config.xml` blocks cleartext in release); secrets exclusively via environment variables / secret manager.

---

## 9. Infrastructure & Deployment

### Local Development (Docker Compose)
Single `docker-compose.yml` running `postgres` (16), `redis` (7), `minio` + `minio-init`, `mailpit` (local SMTP testing for email verification & password reset), `backend` (Ktor REST + WS), `worker` (Event Sequencer, Razorpay/Refund/Expiry/Reconciler), and optional `adminer`.

### Production Self-Hosted Deployment (Linux VPS / Managed DB)
- **Reverse Proxy & TLS**: `Caddy` or `Nginx` + Certbot terminating HTTPS/WSS to localhost Ktor API.
- **Process Supervision**: `systemd` or rootless containers with memory limits and graceful `SIGTERM` drain hooks.
- **PostgreSQL & Backups**: Dedicated NVMe/managed PostgreSQL, `WAL` archiving / nightly encrypted `pg_dump` + `pgBackRest` to off-site S3 with weekly restore drills.
- **Redis & S3**: Redis `AOF` + `allkeys-lru` (non-authoritative); production S3 / R2 / Spaces for images.
- **Observability & Readiness**: JSON logs with `correlation_id`, Micrometer `/metrics`, `/health/live` (JVM/Ktor loop alive -> `200`), `/health/ready` (PostgreSQL required -> `503 UNHEALTHY` if down; Redis and S3/MinIO optional -> `200 DEGRADED` with per-dependency status when down while documented DB/cache/placeholder fallbacks keep core ordering, checkout, KDS, and payments operational), and worker liveness monitoring.

---

## 10. Android Architecture (`:app` + `:shared:contracts`)

- **Clean Architecture & Feature Modularity**:
  - `:shared:contracts`: `@Serializable` DTOs, API envelopes, `OrderStatus` / `PaymentStatus` / `PaymentAttemptStatus`, `Money` paise formatting/rounding utilities.
  - `:app`: Feature packages (`auth`, `catalog`, `cart`, `checkout`, `orders`, `kds`, `owner`, `notifications`, `profile`) + `core` (`network`, `database`, `di`, `ui`, `media`, `sync`).
- **Data & Persistence**:
  - `PizzoraApiClient`: Ktor Client (`OkHttp` engine, `ContentNegotiation`, `WebSockets`, `Auth` bearer + single-flight refresh mutex).
  - `PizzoraDatabase`: Room with KSP for offline cart (`CartItemEntity` + `CartItemToppingEntity`), cached menu, and `notification_inbox`.
  - `PreferencesDataStore` + Android Keystore AES-GCM wrapper for tokens + UI prefs (KDS mute).
- **Manual DI (`AppContainer`) & Role Navigation**:
  - `AppContainer` provides lazy singletons and `ViewModelProvider.Factory` helpers.
  - Type-safe Navigation Compose (`@Serializable` route keys) routes strictly across the **3 roles**: `CUSTOMER` (Menu, Cart, Orders, Profile), `KITCHEN_STAFF` (KdsBoard), `OWNER` (Dashboard, Catalog, Operations, Reports, More).

---

## 11. Testing Strategy

1. **Backend Unit Tests**: `PricingEngine` (`BigInteger` paise/GST rounding), `OrderStateMachine`, `PaymentStateMachine`, `PaymentAttempt` transitions, `RazorpaySignatureVerifier`.
2. **Backend Integration Tests (Testcontainers: PostgreSQL + Redis + MinIO + Mailpit)**:
   - Auth: Argon2id, email verification, password reset (hash TTL + session purge), refresh rotation & family revocation, `ws-ticket` minting, 3-role RBAC `403` & IDOR checks.
   - Schema & Constraints: Topological Flyway migration on empty DB, `inventory.available_qty` generated check constraint, `scheduled_slot_reservations` invariant, `variant/crust/topping_recipes` FK enforcement, `cart_item_toppings` cascade.
   - Upload Pipeline: MinIO presigned upload, spoofed MIME/oversize rejection.
3. **Concurrency, Outbox & Failure-Injection Tests**:
   - **Out-of-order Commit, Crash Recovery & Filtered Cursor Replay**: Slow uncommitted tx vs. fast committed tx verifies `EventSequencerWorker` assigns gap-free `stream_seq`; simulated worker crash after `sequenced_at` (`published_at IS NULL`) verifies redelivery via `OutboxDispatcherWorker` + client `event_id` dedup; cross-room filtering verifies `server_high_water_seq` advancement without false gap errors and `aggregate_version` causal ordering.
   - **Inventory Contention & Restoration**: 20 concurrent checkouts competing for 3 pizzas -> 3 succeed, 17 fail cleanly (`INSUFFICIENT_INVENTORY`), zero deadlocks; pre-prep cancel restores `on_hand_qty` via `CANCEL_RESTORE`.
   - **Coupon & Slot Concurrency**: 20 threads on 1-use coupon -> 1 succeeds; concurrent booking on `max_capacity = 1` -> 1 succeeds; expiry sweep releases `scheduled_slot_reservations` and decrements `reserved_count` cleanly without double-decrement.
   - **Payment Retry, Late Capture & Webhook Race**: Failed `PaymentAttempt` leaves order `PENDING_PAYMENT` and retry succeeds; simultaneous `verify` + `webhook` produces 1 `CAPTURED`/`PLACED` transition; late capture after `EXPIRED` leaves order terminal, sets `payments.status = REFUND_PENDING`, and handles refund failure + reconciliation.
   - **Redis Outage Injection**: Stop Redis container during checkout, menu fetch, `ws-ticket` minting, and worker runs -> verify zero correctness failures, zero oversells, and graceful fallback.
4. **Android Local JVM & Robolectric Tests (`src/test`)**:
   - Pure JVM / `MockEngine` tests: Ktor Client token-refresh mutex, `gateway_order_id` polling, ViewModel `StateFlow` states, `OrderSyncWorker` logic.
   - Robolectric (`@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [36])`): Room `CartDao` + `CartToppingCrossRef` queries, Compose component semantics/`testTag` verification for Auth, Customizer, Cart, KDS, and Owner screens.
5. **Android Instrumented Compose UI & E2E Tests (`src/androidTest` — Emulator / Device CI)**:
   - Dedicated `createAndroidComposeRule<MainActivity>()` suite verifying real WindowInsets, system back navigation, `PickVisualMedia` result handling, Razorpay checkout launch contract, KDS live WebSocket rendering, and full E2E ordering against local Docker Compose backend (executed in CI emulator / physical device pipeline, separate from container-side Robolectric JVM tests).

---

## 12. Implementation Phases (20 Verifiable Phases)

> [!NOTE]
> No phase is complete upon file creation alone. Every phase requires clean compilation, passing automated tests, and functional verification against its Definition of Done before advancing.

### Phase 1: Project Inspection, Firebase Removal & Monorepo Foundation
- **Objectives**: Purge all Firebase/Google Services/Moshi dependencies from the starter template and establish the multi-module monorepo (`:shared:contracts`, `:backend`, `:app`).
- **Deliverables**: Updated root `settings.gradle.kts`, `gradle/libs.versions.toml`, cleaned `:app` build script, `:shared:contracts` module, updated `metadata.json` (`"PIZZORA"`) & `strings.xml`.
- **Expected Modules**: `:app`, `:shared:contracts`, `:backend` skeleton.
- **Dependencies**: None.
- **Database / API Changes**: None.
- **Acceptance Criteria**: Zero occurrences of `firebase`, `google-services`, or `gms` in Gradle configs/code; `:shared:contracts` builds cleanly for both `:app` and `:backend`.
- **Tests**: Gradle build & `:app:compileDebugKotlin` + `:shared:contracts:test`.
- **Manual Verification**: `grep` confirms zero Firebase dependencies; `metadata.json` and `strings.xml` both display `PIZZORA`.
- **Definition of Done**: All 3 modules compile cleanly together.

### Phase 2: Backend Project Setup & Local Infrastructure
- **Objectives**: Stand up Ktor modular monolith, worker entrypoint, structured logging, centralized error handling, OpenAPI spec endpoint, and Docker Compose stack (including Mailpit for SMTP dev testing).
- **Deliverables**: Docker Compose (`postgres`, `redis`, `minio`, `mailpit`), Ktor server bootstrap, `StatusPages` error envelope, `X-Request-ID` MDC logging, `/health/live`, `/health/ready`, `/openapi.json`, `worker` main entrypoint.
- **Expected Modules**: `:backend` (`core/config`, `core/logging`, `core/error`, `core/health`, `worker`).
- **Dependencies**: Phase 1.
- **Database / API Changes**: Health & OpenAPI endpoints.
- **Acceptance Criteria**: `docker compose up` starts Postgres, Redis, MinIO, Mailpit, Ktor API, and worker cleanly; `/health/ready` returns `503 UNHEALTHY` when Postgres is down, and `200 DEGRADED` (with dependency status breakdown) when Redis or MinIO is down while Postgres is up.
- **Tests**: Ktor `testApplication` tests for error envelope, correlation ID propagation, Postgres-down `503`, and Redis/MinIO-down `200 DEGRADED` readiness responses.
- **Manual Verification**: `curl /health/ready` and `/openapi.json` return valid JSON.
- **Definition of Done**: Reproducible local stack boots cleanly and passes health/error tests.

### Phase 3: PostgreSQL Schema & Flyway Migrations
- **Objectives**: Implement complete normalized PostgreSQL schema in topological dependency order with all constraints, generated columns, indexes, and initial seed data (`CUSTOMER`, `KITCHEN_STAFF`, `OWNER`).
- **Deliverables**: Flyway migration scripts (`V1__core_schema.sql`, `V2__seed_rbac_and_settings.sql`), HikariCP + Exposed/JDBC wiring.
- **Expected Modules**: `:backend` (`core/db`, Flyway migrations).
- **Dependencies**: Phase 2.
- **Database / API Changes**: All 46 tables in strict FK dependency order, including `ws_tickets`, `payment_attempts`, `scheduled_slot_reservations`, `variant_recipes`/`crust_recipes`/`topping_recipes`, `cart_item_toppings`, `event_stream_cursor`, `email_verification_tokens`, `password_reset_tokens`, `orders.version`, `payments.captured_paise/refunded_paise`, `refunds.idempotency_key`, `outbox_events` (`sequenced_at`, `dispatch_status`, `published_at` invariant), and `inventory.available_qty` generated column.
- **Acceptance Criteria**: Fresh PostgreSQL container migrates cleanly with zero FK ordering errors; check constraints reject `reserved_qty > on_hand_qty`, `reserved_count > max_capacity`, `refunded_paise > captured_paise`, or duplicate `idempotency_key`.
- **Tests**: Testcontainers PostgreSQL clean-migration test + constraint violation tests.
- **Manual Verification**: Inspect migrated schema via `psql` / Adminer; verify 3 seeded roles and `restaurant_settings`.
- **Definition of Done**: Automated migration and constraint suite passes on clean Postgres.

### Phase 4: Authentication, Account Verification, Password Recovery, RBAC & Owner Bootstrap
- **Objectives**: Implement Argon2id auth, email verification, password reset, JWT access tokens, rotating hashed refresh tokens, single-use WS ticket minting (Redis + PG `ws_tickets` fallback), 3-role RBAC middleware, and CLI owner bootstrap.
- **Deliverables**: `AuthModule`, `UsersModule`, `AuthorizationModule`, `EmailDeliveryProvider` (SMTP/Mailpit), CLI `--bootstrap-owner` command, Redis rate limiter with in-memory fallback.
- **Expected Modules**: `:backend` (`auth`, `users`, `authorization`), `:shared:contracts` (`AuthDto`).
- **Dependencies**: Phase 3.
- **Database / API Changes**: `POST /auth/register|verify-email|resend-verification|login|refresh|logout|forgot-password|reset-password|ws-ticket`, `GET/PATCH /me`, `CRUD /owner/staff`.
- **Acceptance Criteria**: Refresh rotation detects reuse and revokes token family; password reset purges all active sessions; `ws-ticket` enforces single-use consumption in both Redis (`GETDEL`) and PostgreSQL `ws_tickets` fallback; `CUSTOMER` receives `403` on `/owner/staff`.
- **Tests**: Integration tests (Postgres + Redis + Mailpit) for verify/reset flows, refresh replay, 3-role RBAC matrix, Redis-down `ws_tickets` single-use replay rejection, and CLI owner bootstrap.
- **Manual Verification**: Bootstrap owner via CLI, register customer, verify via Mailpit link, test password reset, mint WS ticket.
- **Definition of Done**: All auth, recovery, rotation, and RBAC tests pass.

### Phase 5: Android Foundation, Theme & Ktor API Integration
- **Objectives**: Configure `Theme.kt` (Warm Artisanal Pizzeria + Dark KDS), local `font-util` typography, `AppContainer` DI, Ktor HTTP Client with auto token-refresh mutex, Room DB, DataStore, and 3-role Navigation Compose.
- **Deliverables**: `PizzoraTheme`, local `.ttf` fonts (`Fraunces`, `Plus Jakarta Sans`, `JetBrains Mono`), `AppContainer`, `PizzoraApiClient`, `TokenManager`, `PizzoraDatabase`, `AuthScreens` (Login/Register/Verify/Forgot Password), 3-role router (`CUSTOMER`, `KITCHEN_STAFF`, `OWNER`).
- **Expected Modules**: `:app` (`core/ui`, `core/network`, `core/database`, `core/di`, `auth`).
- **Dependencies**: Phase 1, Phase 4.
- **Database / API Changes**: Android Room v1 (`cart_items`, `cart_item_toppings`, `cached_products`, `notification_inbox`).
- **Acceptance Criteria**: Auth flows persist tokens securely, auto-refresh expired 15m JWT on `401`, and route strictly to Customer, KDS, or Owner shell.
- **Tests**: JVM `MockEngine` single-flight refresh test + `AuthViewModel` tests; Robolectric Login/Register/Reset UI tests; Instrumented `androidTest` auth navigation skeleton.
- **Manual Verification**: Compile debug APK; run unit + Robolectric suite.
- **Definition of Done**: Android app compiles, authenticates against local Ktor backend, and passes unit & Robolectric tests.

### Phase 6: Catalog, Categories & S3/MinIO Product Image Pipeline
- **Objectives**: Implement backend catalog/category CRUD and S3/MinIO presigned image upload + inspection, plus Android Photo Picker compression & upload foundation.
- **Deliverables**: `CatalogModule`, `CategoriesModule`, `ProductImagesModule`, S3/MinIO client, Redis menu cache with DB fallback, Android Photo Picker + compressor helper.
- **Expected Modules**: `:backend` (`catalog`, `categories`, `images`), `:shared:contracts` (`CatalogDto`), `:app` (`core/media`).
- **Dependencies**: Phase 4, Phase 5.
- **Database / API Changes**: `GET /catalog/*`, `CRUD /owner/categories|products|variants|crusts|toppings`, image presign/confirm/delete endpoints.
- **Acceptance Criteria**: Only valid JPEG/PNG/WebP <= 5MB confirmed via S3 `HEAD`/magic bytes; zero binary blobs in Postgres; menu cache gracefully falls back to DB if Redis is down.
- **Tests**: Testcontainers MinIO presigned upload + invalid MIME rejection + Redis cache invalidation/outage fallback tests.
- **Manual Verification**: Upload compressed pizza image to MinIO via presigned URL and render via Coil URL.
- **Definition of Done**: Catalog CRUD and S3 image lifecycle pass integration tests.

### Phase 7: Customer Browsing, Pizza Customizer & Offline Cart
- **Objectives**: Build Customer Menu screen, Pizza Customizer (Regular/Medium/Large, crust, toppings), Room relational offline cart persistence, and backend `CartService` validation.
- **Deliverables**: `CartModule` (`cart_item_toppings` relational persistence); Customer Menu, Customizer, and Cart screens + ViewModels + Room `CartDao` on Android.
- **Expected Modules**: `:backend` (`cart`), `:app` (`catalog`, `cart`).
- **Dependencies**: Phase 5, Phase 6.
- **Database / API Changes**: `GET/PUT /cart`, `POST /cart/validate`.
- **Acceptance Criteria**: Cart works offline in Room with relational toppings, syncs/validates against server catalog on reconnect, and flags unavailable modifiers cleanly.
- **Tests**: Room `CartDao` relational Robolectric tests, `CartService` stale-item validation tests, Robolectric Customizer -> Cart UI test, Instrumented E2E cart test spec.
- **Manual Verification**: Add customized pizza offline, reconnect, verify server validation updates state.
- **Definition of Done**: Menu browsing, customizer, and offline-synced cart compile and pass all tests.

### Phase 8: Pricing Engine & Coupon Management
- **Objectives**: Implement integer-paise `PricingEngine` (`BigInteger` GST/discount math) and full Coupon lifecycle (eligibility, expiry, usage & per-user caps, reservation/redemption).
- **Deliverables**: `PricingModule`, `CouponsModule`, shared `Money` formatting utilities, Android coupon selector & price breakdown UI.
- **Expected Modules**: `:shared:contracts` (`pricing`), `:backend` (`pricing`, `coupons`), `:app` (`cart`, `checkout`).
- **Dependencies**: Phase 7.
- **Database / API Changes**: `GET /coupons/available`, `POST /pricing/quote`, `CRUD /owner/coupons`.
- **Acceptance Criteria**: Client totals ignored; server computes subtotal, discount, delivery fee, CGST/SGST identically in paise; coupon limits enforced atomically in Postgres.
- **Tests**: Parameterized `PricingEngine` paise/rounding tests, coupon eligibility & 20-thread concurrent last-use race test.
- **Manual Verification**: Apply valid/expired/min-order coupons and verify exact GST & discount breakdown.
- **Definition of Done**: Zero floating-point math; pricing and coupon concurrency tests pass.

### Phase 9: Ingredient Inventory & Relational Recipe Management
- **Objectives**: Implement ingredient stock (`on_hand_qty`, `reserved_qty`, generated `available_qty`), relational recipe mappings (`variant_recipes`, `crust_recipes`, `topping_recipes`), atomic reserve/commit/release/restore operations, and `inventory_ledger`.
- **Deliverables**: `InventoryModule`, `RecipesModule`, deterministic `operation_id` ledger guard, sorted-ID row locking.
- **Expected Modules**: `:backend` (`inventory`, `recipes`), `:shared:contracts` (`InventoryDto`).
- **Dependencies**: Phase 6.
- **Database / API Changes**: `GET/POST /owner/inventory`, `GET /owner/inventory/ledger`, `GET/PUT /owner/recipes`.
- **Acceptance Criteria**: Concurrent reservations never produce `reserved_qty > on_hand_qty` or deadlocks; duplicate `operation_id` never creates duplicate ledger rows; deleting a variant/crust/topping cascades cleanly without dangling recipes.
- **Tests**: Multi-thread oversell prevention test, deadlock stress test, idempotent operation replay test, FK cascade/restrict tests.
- **Manual Verification**: Define Margherita recipe across variant + crust + topping, restock cheese, verify ledger records every delta.
- **Definition of Done**: Inventory ledger, relational recipes, and concurrency tests pass 100%.

### Phase 10: Transactional Checkout & Order Creation
- **Objectives**: Build transactional `CheckoutModule` (with `scheduled_slot_reservations`, `inventory_reservations`, `coupon_reservations`, `payments`, initial `payment_attempts`, and `outbox_events`), `OrdersModule` state machine, customer order history/details, and cancellation/restoration rules.
- **Deliverables**: `CheckoutService`, `OrderService`, `OrderStateMachine`, Android Checkout, Order History, and Order Detail screens.
- **Expected Modules**: `:backend` (`checkout`, `orders`), `:app` (`checkout`, `orders`).
- **Dependencies**: Phase 7, Phase 8, Phase 9.
- **Database / API Changes**: `POST /checkout`, `GET /orders`, `GET /orders/{id}`, `POST /orders/{id}/cancel`.
- **Acceptance Criteria**: Checkout validates, computes totals, generates `order_id` + `expires_at`, inserts parent `orders` first, and then inserts `order_items`, `order_status_history`, `scheduled_slot_reservations`, `inventory_reservations`, `inventory_ledger`, `coupon_reservations`, `payments`, `payment_attempts`, and `outbox_events` inside one atomic PostgreSQL tx (zero HTTP calls in tx); Razorpay checkout starts in `PENDING_PAYMENT` + `INITIATING`; COD checkout creates `PLACED` + `COD_PENDING` and commits inventory/coupon/slot immediately; pre-prep cancel restores `on_hand_qty` (`CANCEL_RESTORE`) and releases slot (`reserved_count`).
- **Tests**: Idempotency replay test, parent-first FK insertion order test, rollback-on-stock-failure test, COD commit & cancel-restore test, cancel-after-`PREPARING` `409` rejection test, Android checkout Robolectric + Instrumented CUJ tests.
- **Manual Verification**: Place COD order twice with same `Idempotency-Key` -> returns single order and decrements stock once.
- **Definition of Done**: Transactional checkout, COD lifecycle, and cancellation/restoration suite passes.

### Phase 11: Outbox-Driven Razorpay & COD Payment Lifecycle
- **Objectives**: Implement outbox-driven Razorpay Orders/Verify/Webhooks/Refunds strictly outside DB transactions, `PaymentAttempt` retry flow, late-capture auto-refund + failure reconciliation, and abandoned payment expiry cleanup.
- **Deliverables**: `PaymentsModule`, `RazorpayIntegrationModule`, `OutboxDispatcherWorker` (Razorpay gateway handler), `ExpirySweepWorker`, `RazorpayReconciliationWorker`, `RefundWorker`, Android `gateway_order_id` listener/poller + Razorpay Checkout launcher + Retry Payment UI.
- **Expected Modules**: `:backend` (`payments`, `razorpay`, `worker`), `:app` (`checkout`, `orders`).
- **Dependencies**: Phase 10.
- **Database / API Changes**: `GET /orders/{id}/payment`, `POST /orders/{id}/payments/retry`, `POST /payments/razorpay/verify`, `POST /webhooks/razorpay`, `POST /owner/orders/{id}/refund`.
- **Acceptance Criteria**: Zero Razorpay HTTP calls inside DB transactions; failed `PaymentAttempt` leaves order `PENDING_PAYMENT` for retry until `expires_at`; HMAC-SHA256 verified on raw webhook bytes; verify+webhook race produces exactly one `CAPTURED`/`PLACED` transition; late capture after `EXPIRED`/`CANCELLED` keeps order terminal, records `captured_paise`, performs exceptional transition to `payments.status = REFUND_PENDING`, creates idempotent `refunds` row, and reconciles refund failures via `RazorpayReconciliationWorker` / Owner retry without duplicate refunds.
- **Tests**: Signature verification tests, failed-attempt retry test, verify+webhook concurrency race test, simulated timeout expiry cleanup test, late-capture auto-refund & idempotent refund-failure reconciliation tests.
- **Manual Verification**: Fail first Razorpay test payment -> retry via `POST /orders/{id}/payments/retry` -> succeed and verify `PLACED`.
- **Definition of Done**: Outbox-driven Razorpay initialization, retry, webhook dedup, late-capture refund, reconciliation, and expiry tests pass.

### Phase 12: Order Tracking & Commit-Safe WebSocket Infrastructure
- **Objectives**: Build two-stage `EventSequencerWorker` (`stream_seq` + `sequenced_at`) and `OutboxDispatcherWorker` (`dispatch_status = 'DISPATCHED'` + `published_at`), `/ws/v1/events` WebSocket hub with `server_high_water_seq`, `/events/replay?since_seq=...`, and Android Ktor WebSocket manager with `aggregate_version` ordering.
- **Deliverables**: `EventSequencerWorker` (`pg_advisory_xact_lock`), `OutboxDispatcherWorker`, WebSocket hub (`ws-ticket` + Bearer auth), Android `WebSocketEventClient` with exponential backoff + live order stepper UI.
- **Expected Modules**: `:backend` (`realtime`, `worker`), `:app` (`orders`, `core/network`).
- **Dependencies**: Phase 10, Phase 11.
- **Database / API Changes**: `WS /ws/v1/events`, `GET /events/replay?since_seq=...`.
- **Acceptance Criteria**: Slow uncommitted tx concurrent with fast committed tx never skips events; crash after `sequenced_at` (`published_at IS NULL`) redelivers without dropping; filtered room replay advances `server_high_water_seq` cleanly without false gap errors; client deduplicates by `event_id` and enforces per-order `aggregate_version` causal order.
- **Tests**: Out-of-order concurrent tx replay test, crash-before-dispatch recovery test (`published_at IS NULL` invariant), cross-room `server_high_water_seq` replay test, WebSocket auth/room isolation test, Android `aggregate_version` resync ViewModel tests.
- **Manual Verification**: Disconnect client, emit 3 order transitions + `payment.gateway_ready`, reconnect -> all missed events arrive in exact `stream_seq` order.
- **Definition of Done**: Commit-safe sequencing, crash-safe dispatch, filtered cursor recovery, and causal ordering verified.

### Phase 13: Kitchen Display System (KDS)
- **Objectives**: Implement KDS backend endpoints (including dispatch/completion transitions), Android High-Contrast Dark KDS UI, server-timestamped prep timers, priority sorting, and audible chimes with mute toggle.
- **Deliverables**: `KitchenModule`, `KdsBoardScreen`, chime audio player + mute DataStore pref, and **Play Policy / Android 14–16 `dataSync` verification gate** determining whether KDS uses foreground active-screen tablet sync (`FLAG_KEEP_SCREEN_ON`) or a Play-compliant `dataSync` foreground service.
- **Expected Modules**: `:backend` (`kitchen`), `:app` (`kds`).
- **Dependencies**: Phase 12.
- **Database / API Changes**: `GET /kds/tickets`, `POST /kds/orders/{id}/ack|start-prep|ready|dispatch|complete`, `PATCH .../priority`.
- **Acceptance Criteria**: `KITCHEN_STAFF` can acknowledge, start prep, mark ready, dispatch (`OUT_FOR_DELIVERY`), complete (`COMPLETED`, marking COD collected), and toggle priority; timers adjust for clock skew via `server_now`; Android `targetSdk` & Play foreground-service policy verified prior to enabling background FGS.
- **Tests**: KDS transition & 3-role authZ tests, COD `COMPLETED -> COD_COLLECTED` test, Robolectric KDS board & mute toggle UI tests, Instrumented `androidTest` KDS WebSocket render test.
- **Manual Verification**: Place customer order -> KDS tablet chimes & displays ticket -> advance through `COMPLETED`.
- **Definition of Done**: Full KDS workflow, Play Policy verification, and recovery tests pass.

### Phase 14: Owner Operations Console
- **Objectives**: Build Owner operational dashboard and management screens (products, categories, images, relational recipes, inventory restock/adjust, coupons, orders/refunds, staff, GST/restaurant config).
- **Deliverables**: `GET /owner/dashboard`, `RestaurantConfigModule`, Android Owner Console navigation shell and management screens using zero-permission Photo Picker + M3 adaptive layouts.
- **Expected Modules**: `:backend` (`config`, `owner`), `:app` (`owner`).
- **Dependencies**: Phase 6, Phase 8, Phase 9, Phase 11.
- **Database / API Changes**: `GET /owner/dashboard`, `GET/PUT /owner/settings`.
- **Acceptance Criteria**: Owner can manage catalog with uploaded images, edit `variant/crust/topping_recipes`, adjust stock with reason, toggle store open/closed, update GST bps, and manage staff/orders; `CUSTOMER`/`KITCHEN_STAFF` receive `403`.
- **Tests**: Owner API authorization & validation tests, Robolectric Owner dashboard/catalog/inventory UI tests, Instrumented `androidTest` Photo Picker launch & form flows.
- **Manual Verification**: Toggle store closed as Owner -> Customer checkout rejects cleanly; reopen -> checkout succeeds.
- **Definition of Done**: All Owner CRUD and operational overrides verified end-to-end.

### Phase 15: Delivery Zones, Customer Addresses & Scheduled Orders
- **Objectives**: Implement pincode delivery zones, customer address book CRUD, and `Asia/Kolkata` scheduled delivery/pickup slots backed by `scheduled_slot_reservations`.
- **Deliverables**: `DeliveryZonesModule`, `ScheduledSlotsModule`, Android Address Manager, Pincode Checker, and Scheduled Slot Picker.
- **Expected Modules**: `:backend` (`zones`, `slots`), `:app` (`profile`, `checkout`, `owner`).
- **Dependencies**: Phase 10, Phase 14.
- **Database / API Changes**: `GET/POST/PATCH/DELETE /me/addresses`, `GET /delivery-zones/check`, `GET /slots`, `CRUD /owner/delivery-zones|slots`.
- **Acceptance Criteria**: Unserviceable pincodes block delivery checkout; concurrent booking on last slot prevents overbooking; slots past `Asia/Kolkata` cutoff cannot be booked; `reserved_count` always matches `ACTIVE + COMMITTED` `scheduled_slot_reservations`.
- **Tests**: `Asia/Kolkata` timezone/cutoff tests, concurrent last-slot booking test, expiry release double-decrement guard test, address IDOR test.
- **Manual Verification**: Configure slot capacity = 1, reserve via Razorpay, let it expire -> verify `reserved_count` returns to 0 and slot is bookable again.
- **Definition of Done**: Zone validation and atomic `scheduled_slot_reservations` lifecycle pass.

### Phase 16: Firebase-Free Notification Subsystem
- **Objectives**: Complete persistent `notification_inbox`, notification preferences, `NotificationDeliveryProvider` abstraction, Android In-App Notification Center, and best-effort `WorkManager` `OrderSyncWorker` + local Android notifications.
- **Deliverables**: `NotificationsModule`, `OrderSyncWorker`, Android `NotificationHelper` (`POST_NOTIFICATIONS` runtime flow on API 33+), Notification Inbox screen.
- **Expected Modules**: `:backend` (`notifications`, `worker`), `:app` (`notifications`).
- **Dependencies**: Phase 12, Phase 13.
- **Database / API Changes**: `GET /notifications`, `POST /notifications/{id}/read`, `PUT /notifications/preferences`.
- **Acceptance Criteria**: Zero FCM/Firebase references; outbox populates inbox idempotently; foreground clients receive WS alerts while backgrounded customer apps use best-effort `WorkManager` periodic sync (15m minimum, subject to OS Doze) + expedited on-resume sync.
- **Tests**: Inbox deduplication & preference tests, Android `OrderSyncWorker` Robolectric test.
- **Manual Verification**: Background customer app, advance order on KDS, trigger expedited/WorkManager sync -> local notification appears and inbox updates.
- **Definition of Done**: Foreground and best-effort background Firebase-free notification flows verified.

### Phase 17: Reporting & Audit Logs
- **Objectives**: Build immutable transactional `AuditLoggingModule` across all owner/staff mutations and `ReportingModule` for IST sales, GST, top products, coupon ROI, and prep-SLA metrics.
- **Deliverables**: `AuditModule`, `ReportingModule`, Android Owner Reports & Audit Log Viewer screens.
- **Expected Modules**: `:backend` (`audit`, `reporting`), `:app` (`owner`).
- **Dependencies**: Phase 14, Phase 15.
- **Database / API Changes**: `GET /owner/reports/sales`, `GET /owner/reports/operations`, `GET /owner/audit-logs`.
- **Acceptance Criteria**: Every price, GST, stock, staff, coupon, refund, and order override writes an `audit_logs` entry inside the same DB transaction; reports net out refunds in `Asia/Kolkata` date windows.
- **Tests**: Transactional audit rollback/commit tests, IST midnight boundary & refund-netting report tests.
- **Manual Verification**: Change pizza price + issue partial refund -> verify audit trail and net sales report match to the exact paisa.
- **Definition of Done**: Audit immutability and financial reporting accuracy verified.

### Phase 18: Security Hardening
- **Objectives**: Conduct full security audit and harden transport, headers, rate limits, upload validation, secret hygiene, and Android network/storage protections.
- **Deliverables**: Android `network_security_config.xml` (no cleartext in release), Keystore-backed token encryption, Ktor security headers & strict body-size limits, dependency/secret scan CI check.
- **Expected Modules**: `:backend` (`core/security`), `:app` (`core/security`, manifest).
- **Dependencies**: Phases 4–17.
- **Database / API Changes**: Rate-limit policies tuned per route group.
- **Acceptance Criteria**: Zero secrets in repo or APK `BuildConfig`; invalid signatures, oversized bodies, path traversal keys, expired reset tokens, and cross-user IDOR attempts all blocked and logged.
- **Tests**: Security regression suite (IDOR, JWT tampering, refresh reuse, reset token replay, webhook replay, malformed upload, brute-force 429).
- **Manual Verification**: Inspect debug/release APK `BuildConfig` & manifest to confirm zero backend secrets and zero prohibited Play permissions.
- **Definition of Done**: Security checklist and negative test suite pass 100%.

### Phase 19: End-to-End, Concurrency, Redis-Outage & UI Validation
- **Objectives**: Execute full concurrency, failure-injection (including Redis outage), load, Robolectric component, and Instrumented Compose UI (`androidTest`) acceptance suites.
- **Deliverables**: Concurrency/load harness, Redis-outage fault-injection tests, Robolectric suite (`src/test`), and Instrumented Compose UI E2E suite (`src/androidTest` for CI emulator/device pipeline).
- **Expected Modules**: `:backend` (`test`), `:app` (`src/test`, `src/androidTest`).
- **Dependencies**: Phase 18.
- **Database / API Changes**: `EXPLAIN ANALYZE` index verification; zero unindexed hot-path seq scans.
- **Acceptance Criteria**: 50 concurrent checkouts complete with zero deadlocks/oversells; Redis shutdown causes zero correctness failures in orders/payments/inventory/coupons/slots/replay; all JVM/Robolectric tests pass locally and instrumented Compose UI tests pass on emulator/device CI.
- **Tests**: `gradle :shared:contracts:test :backend:test :app:testDebugUnitTest` + CI emulator `connectedDebugAndroidTest`.
- **Manual Verification**: Review `EXPLAIN ANALYZE` on catalog, KDS board, sequencer poll, and sales report queries.
- **Definition of Done**: All backend concurrency/fault-injection, Android Robolectric, and instrumented UI acceptance gates verified.

### Phase 20: Production Deployment, Monitoring & Backup/Recovery
- **Objectives**: Prepare production deployment manifests, reverse proxy TLS config, systemd/container units, automated PostgreSQL backups, monitoring dashboards, and disaster recovery runbook.
- **Deliverables**: Production deployment template (Caddy/Nginx TLS + Ktor API + Worker + Postgres + Redis + S3 + SMTP), `.env.production.example`, backup/restore scripts, operational runbook.
- **Expected Modules**: Production infrastructure config & runbooks.
- **Dependencies**: Phase 19.
- **Database / API Changes**: Flyway pre-deploy migration step separated from API runtime startup.
- **Acceptance Criteria**: Staging VPS deployment passes TLS check, health/readiness probes, backup creation, and clean database restore drill onto a fresh instance with zero data loss.
- **Tests**: Automated backup-and-restore verification script against ephemeral Postgres container.
- **Manual Verification**: Simulate server & Redis restart during active KDS session and verify WebSocket `stream_seq` recovery + worker resumption.
- **Definition of Done**: Production environment, monitoring alerts, and tested backup/recovery runbook signed off.

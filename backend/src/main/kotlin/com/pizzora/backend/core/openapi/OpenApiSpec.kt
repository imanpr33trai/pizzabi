package com.pizzora.backend.core.openapi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object OpenApiSpec {
  fun buildDocument(serverUrl: String = "/"): JsonObject = buildJsonObject {
    put("openapi", "3.1.0")
    putJsonObject("info") {
      put("title", "PIZZORA Platform API")
      put("version", "1.0.0")
      put(
        "description",
        "FireBas-free self-hosted REST & WebSocket API for PIZZORA customer ordering, Kitchen Display System (KDS), and Owner operations.",
      )
    }
    put(
      "servers",
      buildJsonArray {
        add(buildJsonObject { put("url", serverUrl) })
      },
    )
    putJsonObject("components") {
      putJsonObject("securitySchemes") {
        putJsonObject("BearerAuth") {
          put("type", "http")
          put("scheme", "bearer")
          put("bearerFormat", "JWT")
        }
      }
      putJsonObject("schemas") {
        putJsonObject("ApiErrorEnvelope") {
          put("type", "object")
          put(
            "required",
            JsonArray(listOf(JsonPrimitive("error"), JsonPrimitive("correlation_id"), JsonPrimitive("timestamp"))),
          )
          putJsonObject("properties") {
            putJsonObject("error") {
              put("type", "object")
              putJsonObject("properties") {
                putJsonObject("code") { put("type", "string") }
                putJsonObject("message") { put("type", "string") }
                putJsonObject("details") { put("type", "object") }
              }
            }
            putJsonObject("correlation_id") { put("type", "string") }
            putJsonObject("timestamp") { put("type", "string") }
          }
        }
        putJsonObject("HealthReadinessResponse") {
          put("type", "object")
          put(
            "required",
            JsonArray(listOf(JsonPrimitive("status"), JsonPrimitive("dependencies"), JsonPrimitive("service"))),
          )
          putJsonObject("properties") {
            putJsonObject("status") {
              put("type", "string")
              put("enum", JsonArray(listOf(JsonPrimitive("HEALTHY"), JsonPrimitive("DEGRADED"), JsonPrimitive("UNHEALTHY"))))
            }
            putJsonObject("dependencies") { put("type", "object") }
            putJsonObject("service") { put("type", "string") }
          }
        }
      }
    }
    putJsonObject("paths") {
      putJsonObject("/api/v1/health/live") {
        putJsonObject("get") {
          put("summary", "API process liveness probe")
          put("tags", JsonArray(listOf(JsonPrimitive("Health"))))
          putJsonObject("responses") {
            putJsonObject("200") {
              put("description", "API JVM and Ktor event loop alive")
            }
          }
        }
      }
      putJsonObject("/api/v1/health/ready") {
        putJsonObject("get") {
          put("summary", "Dependency readiness probe (Postgres required; Redis & MinIO optional)")
          put("tags", JsonArray(listOf(JsonPrimitive("Health"))))
          putJsonObject("responses") {
            putJsonObject("200") {
              put("description", "HEALTHY or DEGRADED (Postgres UP; Redis/MinIO may be DOWN with fallback)")
            }
            putJsonObject("503") {
              put("description", "UNHEALTHY (PostgreSQL unavailable)")
            }
          }
        }
      }
      putJsonObject("/api/v1/openapi.json") {
        putJsonObject("get") {
          put("summary", "OpenAPI 3.1 specification document")
          put("tags", JsonArray(listOf(JsonPrimitive("Docs"))))
          putJsonObject("responses") {
            putJsonObject("200") {
              put("description", "OpenAPI JSON document")
            }
          }
        }
      }
      putJsonObject("/api/v1/catalog/categories") {
        putJsonObject("get") {
          put("summary", "List active menu categories")
          put("tags", JsonArray(listOf(JsonPrimitive("Catalog"))))
        }
      }
      putJsonObject("/api/v1/catalog/menu") {
        putJsonObject("get") {
          put("summary", "Fetch full active public pizza menu, variants, crusts, toppings, and active images")
          put("tags", JsonArray(listOf(JsonPrimitive("Catalog"))))
        }
      }
      putJsonObject("/api/v1/owner/categories") {
        putJsonObject("get") { put("summary", "List all categories (Owner)") }
        putJsonObject("post") { put("summary", "Create category (Owner)") }
      }
      putJsonObject("/api/v1/owner/products") {
        putJsonObject("get") { put("summary", "List all products and variants (Owner)") }
        putJsonObject("post") { put("summary", "Create product and size variants atomically (Owner)") }
      }
      putJsonObject("/api/v1/owner/products/{id}/images/presign") {
        putJsonObject("post") { put("summary", "Generate S3/MinIO presigned PUT URL for product image upload (Owner)") }
      }
      putJsonObject("/api/v1/owner/products/{id}/images/confirm") {
        putJsonObject("post") { put("summary", "Verify uploaded object via S3 HEAD + magic bytes and activate image (Owner)") }
      }
      putJsonObject("/api/v1/cart") {
        putJsonObject("get") { put("summary", "Fetch authenticated customer relational cart with live catalog validation") }
        putJsonObject("put") { put("summary", "Replace authenticated customer cart items and toppings") }
      }
      putJsonObject("/api/v1/cart/validate") {
        putJsonObject("post") { put("summary", "Validate local offline cart items against server catalog and prices") }
      }
      putJsonObject("/api/v1/coupons/available") {
        putJsonObject("get") { put("summary", "List active non-expired coupons with customer usage limits and eligibility") }
      }
      putJsonObject("/api/v1/pricing/quote") {
        putJsonObject("post") { put("summary", "Compute authoritative integer-paise price breakdown (subtotal, discount, delivery fee, CGST/SGST, total)") }
      }
      putJsonObject("/api/v1/owner/coupons") {
        putJsonObject("get") { put("summary", "List all coupons (Owner)") }
        putJsonObject("post") { put("summary", "Create promotional coupon (Owner)") }
      }
      putJsonObject("/api/v1/owner/inventory") {
        putJsonObject("get") { put("summary", "List all ingredients with on-hand, reserved, and generated available stock (Owner)") }
        putJsonObject("post") { put("summary", "Create ingredient with initial stock and ledger entry (Owner)") }
      }
      putJsonObject("/api/v1/owner/inventory/adjust") {
        putJsonObject("post") { put("summary", "Idempotently restock or adjust ingredient stock guarded by unique operation_id (Owner)") }
      }
      putJsonObject("/api/v1/owner/inventory/ledger") {
        putJsonObject("get") { put("summary", "List immutable append-only inventory ledger movements (Owner)") }
      }
      putJsonObject("/api/v1/owner/recipes") {
        putJsonObject("get") { put("summary", "List relational ingredient recipes for variants, crusts, and toppings (Owner)") }
        putJsonObject("put") { put("summary", "Replace relational ingredient recipe for a variant, crust, or topping (Owner)") }
      }
      putJsonObject("/api/v1/checkout") {
        putJsonObject("post") { put("summary", "Create transactional order, reservations, payment attempt, and outbox event (requires Idempotency-Key)") }
      }
      putJsonObject("/api/v1/orders") {
        putJsonObject("get") { put("summary", "List customer order history") }
      }
      putJsonObject("/api/v1/orders/{id}") {
        putJsonObject("get") { put("summary", "Get detailed order breakdown, items, payment summary, and status history") }
      }
      putJsonObject("/api/v1/orders/{id}/cancel") {
        putJsonObject("post") { put("summary", "Cancel order if allowed by OrderStateMachine with atomic stock/slot restoration and refund enqueueing") }
      }
      putJsonObject("/api/v1/orders/{id}/payment") {
        putJsonObject("get") { put("summary", "Poll order payment status, active gateway_order_id, retry eligibility, and refunds") }
      }
      putJsonObject("/api/v1/orders/{id}/payments/retry") {
        putJsonObject("post") { put("summary", "Create a new payment attempt and enqueue PAYMENT_GATEWAY_ORDER_REQUESTED outbox event") }
      }
      putJsonObject("/api/v1/orders/{id}/payments/fail") {
        putJsonObject("post") { put("summary", "Report failed Razorpay attempt while keeping PENDING_PAYMENT order retryable") }
      }
      putJsonObject("/api/v1/payments/razorpay/verify") {
        putJsonObject("post") { put("summary", "Verify Razorpay HMAC-SHA256 checkout signature and capture payment atomically") }
      }
      putJsonObject("/api/v1/webhooks/razorpay") {
        putJsonObject("post") { put("summary", "Handle Razorpay signed webhooks (payment.captured, payment.failed, refund.processed, refund.failed)") }
      }
      putJsonObject("/api/v1/owner/orders/{id}/refund") {
        putJsonObject("post") { put("summary", "Initiate or idempotently retry full/partial Razorpay refund (Owner)") }
      }
      putJsonObject("/api/v1/owner/orders/{id}/status") {
        putJsonObject("patch") { put("summary", "Transition order lifecycle status with version increment and ORDER_STATUS_CHANGED outbox event (Owner)") }
      }
      putJsonObject("/api/v1/kds/tickets") {
        putJsonObject("get") { put("summary", "List active kitchen display tickets sorted by priority and FIFO with server_now clock sync (KITCHEN_STAFF / OWNER)") }
      }
      putJsonObject("/api/v1/kds/orders/{id}/ack") {
        putJsonObject("post") { put("summary", "Acknowledge placed order (PLACED -> ACCEPTED) and emit KITCHEN_ACKNOWLEDGED outbox event") }
      }
      putJsonObject("/api/v1/kds/orders/{id}/start-prep") {
        putJsonObject("post") { put("summary", "Start oven preparation (ACCEPTED -> PREPARING)") }
      }
      putJsonObject("/api/v1/kds/orders/{id}/ready") {
        putJsonObject("post") { put("summary", "Mark order ready for pickup or dispatch (PREPARING -> READY)") }
      }
      putJsonObject("/api/v1/kds/orders/{id}/dispatch") {
        putJsonObject("post") { put("summary", "Dispatch delivery order out for delivery (READY -> OUT_FOR_DELIVERY)") }
      }
      putJsonObject("/api/v1/kds/orders/{id}/complete") {
        putJsonObject("post") { put("summary", "Complete pickup/delivery order (READY/OUT_FOR_DELIVERY -> COMPLETED) and settle COD collection") }
      }
      putJsonObject("/api/v1/kds/orders/{id}/priority") {
        putJsonObject("patch") { put("summary", "Toggle expedited kitchen priority flag with version bump and ORDER_PRIORITY_CHANGED event") }
      }
      putJsonObject("/api/v1/config/public") {
        putJsonObject("get") { put("summary", "Public store status, name, GST bps, and default prep SLA") }
      }
      putJsonObject("/api/v1/owner/dashboard") {
        putJsonObject("get") { put("summary", "Owner KPI dashboard (today revenue, active orders, low-stock alerts, store settings)") }
      }
      putJsonObject("/api/v1/owner/settings") {
        putJsonObject("get") { put("summary", "Get store & GST settings (Owner)") }
        putJsonObject("put") { put("summary", "Update store open/closed switch, GST bps, or prep SLA with audit logging (Owner)") }
      }
      putJsonObject("/api/v1/owner/orders") {
        putJsonObject("get") { put("summary", "List all orders across customers with optional status filter (Owner)") }
      }
      putJsonObject("/api/v1/events/replay") {
        putJsonObject("get") { put("summary", "Replay room-authorized sequenced realtime events since stream_seq with server_high_water_seq cursor") }
      }
      putJsonObject("/ws/v1/events") {
        putJsonObject("get") { put("summary", "WebSocket event stream authenticated via single-use ws-ticket or Bearer JWT with room isolation and replay") }
      }
      putJsonObject("/api/v1/me/addresses") {
        putJsonObject("get") { put("summary", "List authenticated customer delivery addresses") }
        putJsonObject("post") { put("summary", "Create delivery address with 6-digit Indian pincode validation") }
      }
      putJsonObject("/api/v1/delivery-zones/check") {
        putJsonObject("get") { put("summary", "Check pincode delivery serviceability, min order, fee, and ETA") }
      }
      putJsonObject("/api/v1/slots") {
        putJsonObject("get") { put("summary", "List active delivery or pickup time slots for an Asia/Kolkata (IST) date with bookability and remaining capacity") }
      }
      putJsonObject("/api/v1/owner/delivery-zones") {
        putJsonObject("get") { put("summary", "List all configured delivery zones (Owner)") }
        putJsonObject("post") { put("summary", "Create delivery zone for a pincode (Owner)") }
      }
      putJsonObject("/api/v1/owner/slots") {
        putJsonObject("get") { put("summary", "List scheduled slots with capacity usage (Owner)") }
        putJsonObject("post") { put("summary", "Create Asia/Kolkata scheduled order time slot (Owner)") }
      }
      putJsonObject("/api/v1/notifications") {
        putJsonObject("get") { put("summary", "List user notification inbox with optional unread=true filter and mark PENDING->SYNCED") }
      }
      putJsonObject("/api/v1/notifications/{id}/read") {
        putJsonObject("post") { put("summary", "Mark a user notification as read (IDOR-enforced)") }
      }
      putJsonObject("/api/v1/notifications/read-all") {
        putJsonObject("post") { put("summary", "Mark all unread notifications for the authenticated user as read") }
      }
      putJsonObject("/api/v1/notifications/preferences") {
        putJsonObject("get") { put("summary", "Fetch notification preferences (order_updates, promo_alerts, kds_sound_enabled)") }
        putJsonObject("put") { put("summary", "Update notification preferences for authenticated user") }
      }
      putJsonObject("/api/v1/owner/reports/sales") {
        putJsonObject("get") { put("summary", "Asia/Kolkata range sales, GST breakdown, refund-netted revenue, top products, and coupon ROI report (Owner)") }
      }
      putJsonObject("/api/v1/owner/reports/operations") {
        putJsonObject("get") { put("summary", "Asia/Kolkata range operational prep-time SLA, fulfillment split, and ingredient wastage/stock movement report (Owner)") }
      }
      putJsonObject("/api/v1/owner/audit-logs") {
        putJsonObject("get") { put("summary", "Paginated immutable audit trail of owner/staff financial, catalog, inventory, coupon, and order actions (Owner)") }
      }
    }
  }
}

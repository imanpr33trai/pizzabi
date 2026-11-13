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
        "Firebase-free self-hosted REST & WebSocket API for PIZZORA customer ordering, Kitchen Display System (KDS), and Owner operations.",
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
    }
  }
}

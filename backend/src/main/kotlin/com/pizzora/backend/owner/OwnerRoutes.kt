package com.pizzora.backend.owner

import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.OrderStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.ownerRoutes(
  ownerService: OwnerService,
  authorizationService: AuthorizationService,
) {
  authenticate("auth-jwt") {
    requirePermission("owner:dashboard", authorizationService) {
      get("/api/v1/owner/dashboard") {
        call.respond(HttpStatusCode.OK, ownerService.getDashboard())
      }
    }

    requirePermission("owner:orders_manage", authorizationService) {
      get("/api/v1/owner/orders") {
        val rawStatus = call.request.queryParameters["status"]?.trim()?.takeIf { it.isNotEmpty() }
        val statusFilter = rawStatus?.let {
          runCatching { OrderStatus.valueOf(it.uppercase()) }.getOrElse {
            throw ValidationException("INVALID_STATUS", "Invalid order status filter '$rawStatus'.")
          }
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        call.respond(HttpStatusCode.OK, ownerService.listOrders(statusFilter, limit))
      }
    }
  }
}

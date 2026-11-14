package com.pizzora.backend.orders

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.CancelOrderRequest
import com.pizzora.contracts.TransitionOrderStatusRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.ordersRoutes(
  orderService: OrderService,
  authorizationService: AuthorizationService,
) {
  authenticate("auth-jwt") {
    route("/api/v1/orders") {
      requirePermission("orders:read_own", authorizationService) {
        get {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
          call.respond(HttpStatusCode.OK, orderService.listCustomerOrders(principal.userId, limit))
        }

        get("/{id}") {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          val orderId = parseUuid(call.parameters["id"], "order_id")
          call.respond(HttpStatusCode.OK, orderService.getOrderDetail(orderId, principal.userId, principal.role))
        }
      }

      requirePermission("orders:cancel_own", authorizationService) {
        post("/{id}/cancel") {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          val orderId = parseUuid(call.parameters["id"], "order_id")
          val request = runCatching { call.receiveNullable<CancelOrderRequest>() }.getOrNull()
          val audit = AuditContext(
            actorUserId = principal.userId,
            actorRole = principal.role.name,
            correlationId = call.callId ?: UUID.randomUUID().toString(),
            ipAddress = call.request.local.remoteAddress,
          )
          val updated = orderService.cancelOrder(
            orderId = orderId,
            actorUserId = principal.userId,
            actorRole = principal.role,
            reason = request?.reason,
            audit = audit,
          )
          call.respond(HttpStatusCode.OK, updated)
        }
      }
    }

    route("/api/v1/owner/orders") {
      requirePermission("owner:orders_manage", authorizationService) {
        patch("/{id}/status") {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          val orderId = parseUuid(call.parameters["id"], "order_id")
          val body = call.receive<TransitionOrderStatusRequest>()
          val audit = AuditContext(
            actorUserId = principal.userId,
            actorRole = principal.role.name,
            correlationId = call.callId ?: UUID.randomUUID().toString(),
            ipAddress = call.request.local.remoteAddress,
          )
          val updated = orderService.transitionOrderStatus(
            orderId = orderId,
            toStatus = body.toStatus,
            actorUserId = principal.userId,
            actorRole = principal.role,
            reason = body.reason,
            audit = audit,
          )
          call.respond(HttpStatusCode.OK, updated)
        }
      }
    }
  }
}

private fun parseUuid(raw: String?, field: String): UUID =
  runCatching { UUID.fromString(raw) }
    .getOrElse { throw ValidationException("INVALID_UUID", "Invalid UUID parameter for $field.") }

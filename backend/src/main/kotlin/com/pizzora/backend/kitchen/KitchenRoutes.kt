package com.pizzora.backend.kitchen

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.KdsActionRequest
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.UpdateOrderPriorityRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.kitchenRoutes(
  kitchenService: KitchenService,
  authorizationService: AuthorizationService,
) {
  authenticate("auth-jwt") {
    route("/api/v1/kds") {
      requirePermission("kds:read", authorizationService) {
        get("/tickets") {
          val includeCompleted = call.request.queryParameters["include_completed"]
            ?.equals("true", ignoreCase = true) == true
          val statusFilter = call.request.queryParameters["status"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { raw ->
              runCatching { OrderStatus.valueOf(raw.uppercase()) }
                .getOrElse { throw ValidationException("INVALID_STATUS_FILTER", "Unsupported order status filter: $raw") }
            }
          val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
          call.respond(
            HttpStatusCode.OK,
            kitchenService.listTickets(
              includeCompleted = includeCompleted,
              statusFilter = statusFilter,
              limit = limit,
            ),
          )
        }
      }

      requirePermission("kds:update_status", authorizationService) {
        post("/orders/{id}/ack") {
          handleKdsTransition(
            call = call,
            kitchenService = kitchenService,
            targetStatus = OrderStatus.ACCEPTED,
            outboxEventType = "KITCHEN_ACKNOWLEDGED",
          )
        }

        post("/orders/{id}/start-prep") {
          handleKdsTransition(
            call = call,
            kitchenService = kitchenService,
            targetStatus = OrderStatus.PREPARING,
          )
        }

        post("/orders/{id}/ready") {
          handleKdsTransition(
            call = call,
            kitchenService = kitchenService,
            targetStatus = OrderStatus.READY,
          )
        }

        post("/orders/{id}/dispatch") {
          handleKdsTransition(
            call = call,
            kitchenService = kitchenService,
            targetStatus = OrderStatus.OUT_FOR_DELIVERY,
          )
        }

        post("/orders/{id}/complete") {
          handleKdsTransition(
            call = call,
            kitchenService = kitchenService,
            targetStatus = OrderStatus.COMPLETED,
          )
        }
      }

      requirePermission("kds:prioritize", authorizationService) {
        patch("/orders/{id}/priority") {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          val orderId = parseUuid(call.parameters["id"], "order_id")
          val body = call.receive<UpdateOrderPriorityRequest>()
          val audit = AuditContext(
            actorUserId = principal.userId,
            actorRole = principal.role.name,
            correlationId = call.callId ?: UUID.randomUUID().toString(),
            ipAddress = call.request.origin.remoteAddress,
          )
          val updated = kitchenService.updatePriority(
            orderId = orderId,
            isPriority = body.isPriority,
            actorUserId = principal.userId,
            reason = body.reason,
            audit = audit,
          )
          call.respond(HttpStatusCode.OK, updated)
        }
      }
    }
  }
}

private suspend fun handleKdsTransition(
  call: ApplicationCall,
  kitchenService: KitchenService,
  targetStatus: OrderStatus,
  outboxEventType: String = "ORDER_STATUS_CHANGED",
) {
  val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
  val orderId = parseUuid(call.parameters["id"], "order_id")
  val request = runCatching { call.receiveNullable<KdsActionRequest>() }.getOrNull()
  val audit = AuditContext(
    actorUserId = principal.userId,
    actorRole = principal.role.name,
    correlationId = call.callId ?: UUID.randomUUID().toString(),
    ipAddress = call.request.origin.remoteAddress,
  )
  val updated = kitchenService.transitionTicket(
    orderId = orderId,
    toStatus = targetStatus,
    actorUserId = principal.userId,
    actorRole = principal.role,
    reason = request?.reason,
    audit = audit,
    outboxEventType = outboxEventType,
  )
  call.respond(HttpStatusCode.OK, updated)
}

private fun parseUuid(raw: String?, field: String): UUID =
  runCatching { UUID.fromString(raw) }
    .getOrElse { throw ValidationException("INVALID_UUID", "Invalid UUID parameter for $field.") }

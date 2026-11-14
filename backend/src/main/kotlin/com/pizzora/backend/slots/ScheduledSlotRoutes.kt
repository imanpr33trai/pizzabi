package com.pizzora.backend.slots

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.CreateScheduledSlotRequest
import com.pizzora.contracts.UpdateScheduledSlotRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.scheduledSlotRoutes(
  scheduledSlotService: ScheduledSlotService,
  authorizationService: AuthorizationService,
) {
  get("/api/v1/slots") {
    val type = call.request.queryParameters["type"]
    val date = call.request.queryParameters["date"]
    call.respond(HttpStatusCode.OK, scheduledSlotService.listAvailableSlots(type, date))
  }

  authenticate("auth-jwt") {
    route("/api/v1/owner/slots") {
      requirePermission("owner:zones_slots_manage", authorizationService) {
        get {
          val type = call.request.queryParameters["type"]
          val date = call.request.queryParameters["date"]
          call.respond(HttpStatusCode.OK, scheduledSlotService.listOwnerSlots(type, date))
        }

        post {
          val ctx = call.auditContext()
          val body = call.receive<CreateScheduledSlotRequest>()
          val created = scheduledSlotService.createSlot(
            request = body,
            audit = ctx,
          )
          call.respond(HttpStatusCode.Created, created)
        }

        patch("/{id}") {
          val ctx = call.auditContext()
          val slotId = parseUuid(call.parameters["id"], "slot_id")
          val body = call.receive<UpdateScheduledSlotRequest>()
          val updated = scheduledSlotService.updateSlot(
            slotId = slotId,
            request = body,
            audit = ctx,
          )
          call.respond(HttpStatusCode.OK, updated)
        }

        delete("/{id}") {
          val ctx = call.auditContext()
          val slotId = parseUuid(call.parameters["id"], "slot_id")
          scheduledSlotService.deleteSlot(
            slotId = slotId,
            audit = ctx,
          )
          call.respond(HttpStatusCode.NoContent)
        }
      }
    }
  }
}

private fun ApplicationCall.auditContext(): AuditContext {
  val principal = principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
  return AuditContext(
    actorUserId = principal.userId,
    actorRole = principal.role.name,
    correlationId = callId ?: UUID.randomUUID().toString(),
    ipAddress = request.origin.remoteAddress,
  )
}

private fun parseUuid(raw: String?, field: String): UUID =
  runCatching { UUID.fromString(raw) }
    .getOrElse { throw ValidationException("INVALID_UUID", "Invalid UUID parameter for $field.") }

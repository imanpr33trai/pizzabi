package com.pizzora.backend.zones

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.CreateDeliveryZoneRequest
import com.pizzora.contracts.UpdateDeliveryZoneRequest
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

fun Route.deliveryZoneRoutes(
  deliveryZoneService: DeliveryZoneService,
  authorizationService: AuthorizationService,
) {
  get("/api/v1/delivery-zones/check") {
    val pincode = call.request.queryParameters["pincode"]
    call.respond(HttpStatusCode.OK, deliveryZoneService.checkPincode(pincode))
  }

  authenticate("auth-jwt") {
    route("/api/v1/owner/delivery-zones") {
      requirePermission("owner:zones_slots_manage", authorizationService) {
        get {
          call.respond(HttpStatusCode.OK, deliveryZoneService.listAllZones())
        }

        post {
          val ctx = call.auditContext()
          val body = call.receive<CreateDeliveryZoneRequest>()
          val created = deliveryZoneService.createZone(
            request = body,
            audit = ctx,
          )
          call.respond(HttpStatusCode.Created, created)
        }

        patch("/{id}") {
          val ctx = call.auditContext()
          val zoneId = parseUuid(call.parameters["id"], "delivery_zone_id")
          val body = call.receive<UpdateDeliveryZoneRequest>()
          val updated = deliveryZoneService.updateZone(
            zoneId = zoneId,
            request = body,
            audit = ctx,
          )
          call.respond(HttpStatusCode.OK, updated)
        }

        delete("/{id}") {
          val ctx = call.auditContext()
          val zoneId = parseUuid(call.parameters["id"], "delivery_zone_id")
          deliveryZoneService.deleteZone(
            zoneId = zoneId,
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

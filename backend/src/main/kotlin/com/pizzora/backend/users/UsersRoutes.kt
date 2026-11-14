package com.pizzora.backend.users

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.UpdateProfileRequest
import com.pizzora.contracts.UpdateStaffRequest
import com.pizzora.contracts.UpsertAddressRequest
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

fun Route.usersRoutes(
  usersService: UsersService,
  authorizationService: AuthorizationService,
) {
  authenticate("auth-jwt") {
    route("/api/v1/me") {
      get {
        val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
        call.respond(HttpStatusCode.OK, usersService.getProfile(principal.userId))
      }

      patch {
        val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
        val body = call.receive<UpdateProfileRequest>()
        call.respond(HttpStatusCode.OK, usersService.updateProfile(principal.userId, body))
      }

      route("/addresses") {
        get {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          call.respond(HttpStatusCode.OK, usersService.listAddresses(principal.userId))
        }

        post {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          val body = call.receive<UpsertAddressRequest>()
          call.respond(HttpStatusCode.Created, usersService.createAddress(principal.userId, body))
        }

        patch("/{id}") {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          val addressId = parseUuid(call.parameters["id"], "address_id")
          val body = call.receive<UpsertAddressRequest>()
          call.respond(HttpStatusCode.OK, usersService.updateAddress(principal.userId, addressId, body))
        }

        delete("/{id}") {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          val addressId = parseUuid(call.parameters["id"], "address_id")
          usersService.deleteAddress(principal.userId, addressId)
          call.respond(HttpStatusCode.NoContent)
        }
      }
    }

    route("/api/v1/owner/staff") {
      requirePermission("owner:staff_manage", authorizationService) {
        get {
          call.respond(HttpStatusCode.OK, usersService.listStaff())
        }

        post {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          val ctx = call.auditContext()
          val body = call.receive<CreateStaffRequest>()
          call.respond(HttpStatusCode.Created, usersService.createStaff(principal.userId, body, ctx))
        }

        patch("/{userId}") {
          val targetUserId = parseUuid(call.parameters["userId"], "user_id")
          val ctx = call.auditContext()
          val body = call.receive<UpdateStaffRequest>()
          call.respond(HttpStatusCode.OK, usersService.updateStaff(targetUserId, body, ctx))
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

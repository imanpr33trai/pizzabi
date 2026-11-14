package com.pizzora.backend.config

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.contracts.UpdateRestaurantSettingsRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

fun Route.restaurantConfigRoutes(
  configService: RestaurantConfigService,
  authorizationService: AuthorizationService,
) {
  get("/api/v1/config/public") {
    call.respond(HttpStatusCode.OK, configService.getPublicConfig())
  }

  authenticate("auth-jwt") {
    route("/api/v1/owner/settings") {
      requirePermission("owner:settings_manage", authorizationService) {
        get {
          call.respond(HttpStatusCode.OK, configService.getOwnerSettings())
        }

        put {
          val ctx = call.auditContext()
          val body = call.receive<UpdateRestaurantSettingsRequest>()
          call.respond(HttpStatusCode.OK, configService.updateOwnerSettings(body, ctx))
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

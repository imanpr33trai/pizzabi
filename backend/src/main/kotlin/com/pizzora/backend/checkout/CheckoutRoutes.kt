package com.pizzora.backend.checkout

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.contracts.CheckoutRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.UUID

fun Route.checkoutRoutes(
  checkoutService: CheckoutService,
  authorizationService: AuthorizationService,
) {
  authenticate("auth-jwt") {
    requirePermission("checkout:create", authorizationService) {
      post("/api/v1/checkout") {
        val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
        val idempotencyKey = call.request.headers["Idempotency-Key"]
        val request = call.receive<CheckoutRequest>()
        val audit = AuditContext(
          actorUserId = principal.userId,
          actorRole = principal.role.name,
          correlationId = call.callId ?: UUID.randomUUID().toString(),
          ipAddress = call.request.origin.remoteAddress,
        )
        val result = checkoutService.checkout(
          customerId = principal.userId,
          rawIdempotencyKey = idempotencyKey,
          request = request,
          audit = audit,
        )
        val status = if (result.idempotentReplay) HttpStatusCode.OK else HttpStatusCode.Created
        call.respond(status, result)
      }
    }
  }
}

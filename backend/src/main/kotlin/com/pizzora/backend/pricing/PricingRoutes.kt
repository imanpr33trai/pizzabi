package com.pizzora.backend.pricing

import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.contracts.PricingQuoteRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.pricingRoutes(
  pricingService: PricingService,
  authorizationService: AuthorizationService,
) {
  authenticate("auth-jwt") {
    route("/api/v1/pricing") {
      requirePermission("cart:manage", authorizationService) {
        post("/quote") {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          val body = call.receive<PricingQuoteRequest>()
          call.respond(HttpStatusCode.OK, pricingService.quotePrice(principal.userId, body))
        }
      }
    }
  }
}

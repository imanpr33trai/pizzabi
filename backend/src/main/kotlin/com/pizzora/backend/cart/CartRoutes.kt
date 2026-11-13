package com.pizzora.backend.cart

import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.contracts.ReplaceCartRequest
import com.pizzora.contracts.ValidateCartRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.cartRoutes(
  cartService: CartService,
  authorizationService: AuthorizationService,
) {
  authenticate("auth-jwt") {
    route("/api/v1/cart") {
      requirePermission("cart:manage", authorizationService) {
        get {
          val principal = call.principal<PizzoraPrincipal>()!!
          call.respond(HttpStatusCode.OK, cartService.getCart(principal.userId))
        }

        put {
          val principal = call.principal<PizzoraPrincipal>()!!
          val request = call.receive<ReplaceCartRequest>()
          call.respond(HttpStatusCode.OK, cartService.replaceCart(principal.userId, request))
        }

        post("/validate") {
          val request = call.receive<ValidateCartRequest>()
          call.respond(HttpStatusCode.OK, cartService.validateCart(request))
        }
      }
    }
  }
}

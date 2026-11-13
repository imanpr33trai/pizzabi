package com.pizzora.backend.catalog

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.UpdateCrustRequest
import com.pizzora.contracts.UpdateProductRequest
import com.pizzora.contracts.UpdateToppingRequest
import com.pizzora.contracts.UpdateVariantRequest
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

fun Route.catalogRoutes(
  catalogService: CatalogService,
  authorizationService: AuthorizationService,
) {
  get("/api/v1/catalog/menu") {
    call.respond(HttpStatusCode.OK, catalogService.getPublicMenu())
  }

  authenticate("auth-jwt") {
    requirePermission("owner:catalog_manage", authorizationService) {
      route("/api/v1/owner/products") {
        get {
          call.respond(HttpStatusCode.OK, catalogService.listAllProducts())
        }

        post {
          val ctx = call.auditContext()
          val body = call.receive<CreateProductRequest>()
          call.respond(HttpStatusCode.Created, catalogService.createProduct(body, ctx))
        }

        patch("/{id}") {
          val ctx = call.auditContext()
          val productId = parseUuid(call.parameters["id"], "product_id")
          val body = call.receive<UpdateProductRequest>()
          call.respond(HttpStatusCode.OK, catalogService.updateProduct(productId, body, ctx))
        }

        delete("/{id}") {
          val ctx = call.auditContext()
          val productId = parseUuid(call.parameters["id"], "product_id")
          catalogService.deleteProduct(productId, ctx)
          call.respond(HttpStatusCode.NoContent)
        }

        post("/{id}/variants") {
          val ctx = call.auditContext()
          val productId = parseUuid(call.parameters["id"], "product_id")
          val body = call.receive<CreateVariantRequest>()
          call.respond(HttpStatusCode.Created, catalogService.addVariant(productId, body, ctx))
        }
      }

      patch("/api/v1/owner/variants/{id}") {
        val ctx = call.auditContext()
        val variantId = parseUuid(call.parameters["id"], "variant_id")
        val body = call.receive<UpdateVariantRequest>()
        call.respond(HttpStatusCode.OK, catalogService.updateVariant(variantId, body, ctx))
      }

      route("/api/v1/owner/crusts") {
        get {
          call.respond(HttpStatusCode.OK, catalogService.listAllCrusts())
        }

        post {
          val ctx = call.auditContext()
          val body = call.receive<CreateCrustRequest>()
          call.respond(HttpStatusCode.Created, catalogService.createCrust(body, ctx))
        }

        patch("/{id}") {
          val ctx = call.auditContext()
          val crustId = parseUuid(call.parameters["id"], "crust_id")
          val body = call.receive<UpdateCrustRequest>()
          call.respond(HttpStatusCode.OK, catalogService.updateCrust(crustId, body, ctx))
        }
      }

      route("/api/v1/owner/toppings") {
        get {
          call.respond(HttpStatusCode.OK, catalogService.listAllToppings())
        }

        post {
          val ctx = call.auditContext()
          val body = call.receive<CreateToppingRequest>()
          call.respond(HttpStatusCode.Created, catalogService.createTopping(body, ctx))
        }

        patch("/{id}") {
          val ctx = call.auditContext()
          val toppingId = parseUuid(call.parameters["id"], "topping_id")
          val body = call.receive<UpdateToppingRequest>()
          call.respond(HttpStatusCode.OK, catalogService.updateTopping(toppingId, body, ctx))
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

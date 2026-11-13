package com.pizzora.backend.inventory

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.AdjustInventoryRequest
import com.pizzora.contracts.CreateIngredientRequest
import com.pizzora.contracts.UpdateIngredientRequest
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
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.inventoryRoutes(
  inventoryService: InventoryService,
  authorizationService: AuthorizationService,
) {
  authenticate("auth-jwt") {
    route("/api/v1/owner/inventory") {
      requirePermission("owner:inventory_manage", authorizationService) {
        get {
          call.respond(HttpStatusCode.OK, inventoryService.listInventory())
        }

        post {
          val ctx = call.auditContext()
          val body = call.receive<CreateIngredientRequest>()
          call.respond(HttpStatusCode.Created, inventoryService.createIngredient(body, ctx))
        }

        patch("/{id}") {
          val ctx = call.auditContext()
          val ingredientId = parseUuid(call.parameters["id"], "ingredient_id")
          val body = call.receive<UpdateIngredientRequest>()
          call.respond(HttpStatusCode.OK, inventoryService.updateIngredient(ingredientId, body, ctx))
        }

        post("/adjust") {
          val ctx = call.auditContext()
          val body = call.receive<AdjustInventoryRequest>()
          call.respond(HttpStatusCode.OK, inventoryService.adjustInventory(body, ctx))
        }

        get("/ledger") {
          val ingredientId = call.request.queryParameters["ingredient_id"]
            ?.takeIf { it.isNotBlank() }
            ?.let { parseUuid(it, "ingredient_id") }
          val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
          call.respond(HttpStatusCode.OK, inventoryService.listLedger(ingredientId, limit))
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

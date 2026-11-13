package com.pizzora.backend.categories

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.UpdateCategoryRequest
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

fun Route.categoriesRoutes(
  categoriesService: CategoriesService,
  authorizationService: AuthorizationService,
) {
  get("/api/v1/catalog/categories") {
    call.respond(HttpStatusCode.OK, categoriesService.listPublicCategories())
  }

  authenticate("auth-jwt") {
    route("/api/v1/owner/categories") {
      requirePermission("owner:catalog_manage", authorizationService) {
        get {
          call.respond(HttpStatusCode.OK, categoriesService.listAllCategories())
        }

        post {
          val ctx = call.auditContext()
          val body = call.receive<CreateCategoryRequest>()
          call.respond(HttpStatusCode.Created, categoriesService.createCategory(body, ctx))
        }

        patch("/{id}") {
          val ctx = call.auditContext()
          val categoryId = parseUuid(call.parameters["id"], "category_id")
          val body = call.receive<UpdateCategoryRequest>()
          call.respond(HttpStatusCode.OK, categoriesService.updateCategory(categoryId, body, ctx))
        }

        delete("/{id}") {
          val ctx = call.auditContext()
          val categoryId = parseUuid(call.parameters["id"], "category_id")
          categoriesService.deleteCategory(categoryId, ctx)
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

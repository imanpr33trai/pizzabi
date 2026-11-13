package com.pizzora.backend.images

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.ConfirmProductImageRequest
import com.pizzora.contracts.PresignProductImageRequest
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
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.productImagesRoutes(
  productImagesService: ProductImagesService,
  authorizationService: AuthorizationService,
) {
  authenticate("auth-jwt") {
    route("/api/v1/owner/products/{id}/images") {
      requirePermission("owner:catalog_manage", authorizationService) {
        post("/presign") {
          val (principal, ctx) = call.principalAndAuditContext()
          val productId = parseUuid(call.parameters["id"], "product_id")
          val body = call.receive<PresignProductImageRequest>()
          call.respond(
            HttpStatusCode.Created,
            productImagesService.presignUpload(
              productId = productId,
              uploadedBy = principal.userId,
              request = body,
              auditContext = ctx,
            ),
          )
        }

        post("/confirm") {
          val (_, ctx) = call.principalAndAuditContext()
          val productId = parseUuid(call.parameters["id"], "product_id")
          val body = call.receive<ConfirmProductImageRequest>()
          val imageId = parseUuid(body.imageId, "image_id")
          call.respond(
            HttpStatusCode.OK,
            productImagesService.confirmUpload(
              productId = productId,
              imageId = imageId,
              auditContext = ctx,
            ),
          )
        }

        delete("/{imgId}") {
          val (_, ctx) = call.principalAndAuditContext()
          val productId = parseUuid(call.parameters["id"], "product_id")
          val imageId = parseUuid(call.parameters["imgId"], "image_id")
          productImagesService.deleteImage(
            productId = productId,
            imageId = imageId,
            auditContext = ctx,
          )
          call.respond(HttpStatusCode.NoContent)
        }
      }
    }
  }
}

private fun ApplicationCall.principalAndAuditContext(): Pair<PizzoraPrincipal, AuditContext> {
  val principal = principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
  val ctx = AuditContext(
    actorUserId = principal.userId,
    actorRole = principal.role.name,
    correlationId = callId ?: UUID.randomUUID().toString(),
    ipAddress = request.origin.remoteAddress,
  )
  return principal to ctx
}

private fun parseUuid(raw: String?, field: String): UUID =
  runCatching { UUID.fromString(raw) }
    .getOrElse { throw ValidationException("INVALID_UUID", "Invalid UUID parameter for $field.") }

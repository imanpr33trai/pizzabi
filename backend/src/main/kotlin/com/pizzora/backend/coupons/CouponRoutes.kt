package com.pizzora.backend.coupons

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.CreateCouponRequest
import com.pizzora.contracts.UpdateCouponRequest
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

fun Route.couponsRoutes(
  couponService: CouponService,
  authorizationService: AuthorizationService,
) {
  authenticate("auth-jwt") {
    route("/api/v1/coupons") {
      requirePermission("cart:manage", authorizationService) {
        get("/available") {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          val rawSubtotal = call.request.queryParameters["subtotal_paise"]
          val subtotalPaise = if (rawSubtotal.isNullOrBlank()) {
            null
          } else {
            rawSubtotal.toLongOrNull()
              ?: throw ValidationException("INVALID_SUBTOTAL", "subtotal_paise must be a valid integer.")
          }
          call.respond(HttpStatusCode.OK, couponService.listAvailableCoupons(principal.userId, subtotalPaise))
        }
      }
    }

    route("/api/v1/owner/coupons") {
      requirePermission("owner:coupons_manage", authorizationService) {
        get {
          call.respond(HttpStatusCode.OK, couponService.listAllCoupons())
        }

        post {
          val ctx = call.auditContext()
          val body = call.receive<CreateCouponRequest>()
          call.respond(HttpStatusCode.Created, couponService.createCoupon(body, ctx))
        }

        patch("/{id}") {
          val ctx = call.auditContext()
          val couponId = parseUuid(call.parameters["id"], "coupon_id")
          val body = call.receive<UpdateCouponRequest>()
          call.respond(HttpStatusCode.OK, couponService.updateCoupon(couponId, body, ctx))
        }

        delete("/{id}") {
          val ctx = call.auditContext()
          val couponId = parseUuid(call.parameters["id"], "coupon_id")
          couponService.deleteOrDeactivateCoupon(couponId, ctx)
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

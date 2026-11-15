package com.pizzora.backend.payments

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.authorization.requirePermission
import com.pizzora.backend.core.error.AppException
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.razorpay.WebhookService
import com.pizzora.contracts.FailPaymentAttemptRequest
import com.pizzora.contracts.MessageResponse
import com.pizzora.contracts.OwnerRefundRequest
import com.pizzora.contracts.RetryPaymentRequest
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.paymentsRoutes(
  paymentService: PaymentService,
  webhookService: WebhookService,
  authorizationService: AuthorizationService,
) {
  // Public Razorpay Webhook endpoint verified via HMAC-SHA256 on raw body bytes
  post("/api/v1/webhooks/razorpay") {
    val rawBody = call.receive<ByteArray>()
    val signature = call.request.header("X-Razorpay-Signature")
    val eventId = call.request.header("X-Razorpay-Event-Id")
    val audit = AuditContext(
      actorUserId = null,
      actorRole = "SYSTEM",
      correlationId = call.callId ?: UUID.randomUUID().toString(),
      ipAddress = call.request.origin.remoteAddress,
    )
    webhookService.handleRazorpayWebhook(
      rawBody = rawBody,
      signatureHeader = signature,
      eventIdHeader = eventId,
      audit = audit,
    )
    call.respond(HttpStatusCode.OK, MessageResponse("Webhook processed."))
  }

  authenticate("auth-jwt") {
    route("/api/v1/orders/{id}") {
      requirePermission("orders:read_own", authorizationService) {
        get("/payment") {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          val orderId = parseUuid(call.parameters["id"], "order_id")
          call.respond(
            HttpStatusCode.OK,
            paymentService.getOrderPaymentStatus(
              orderId = orderId,
              actorUserId = principal.userId,
              actorRole = principal.role,
            ),
          )
        }
      }

      requirePermission("payments:retry_own", authorizationService) {
        post("/payments/retry") {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          val orderId = parseUuid(call.parameters["id"], "order_id")
          val idempotencyKey = call.request.header("Idempotency-Key")
          val request = try {
            call.receiveNullable<RetryPaymentRequest>()
          } catch (ex: AppException) {
            throw ex
          } catch (_: Throwable) {
            null
          }
          val audit = AuditContext(
            actorUserId = principal.userId,
            actorRole = principal.role.name,
            correlationId = call.callId ?: UUID.randomUUID().toString(),
            ipAddress = call.request.origin.remoteAddress,
          )
          call.respond(
            HttpStatusCode.OK,
            paymentService.retryPayment(
              orderId = orderId,
              customerId = principal.userId,
              rawIdempotencyKey = idempotencyKey,
              request = request,
              audit = audit,
            ),
          )
        }

        post("/payments/fail") {
          val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
          val orderId = parseUuid(call.parameters["id"], "order_id")
          val request = try {
            call.receiveNullable<FailPaymentAttemptRequest>()
          } catch (ex: AppException) {
            throw ex
          } catch (_: Throwable) {
            null
          } ?: FailPaymentAttemptRequest()
          val audit = AuditContext(
            actorUserId = principal.userId,
            actorRole = principal.role.name,
            correlationId = call.callId ?: UUID.randomUUID().toString(),
            ipAddress = call.request.origin.remoteAddress,
          )
          call.respond(
            HttpStatusCode.OK,
            paymentService.reportAttemptFailure(
              orderId = orderId,
              customerId = principal.userId,
              request = request,
              audit = audit,
            ),
          )
        }
      }
    }

    requirePermission("payments:retry_own", authorizationService) {
      post("/api/v1/payments/razorpay/verify") {
        val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
        val request = call.receive<VerifyRazorpayPaymentRequest>()
        val audit = AuditContext(
          actorUserId = principal.userId,
          actorRole = principal.role.name,
          correlationId = call.callId ?: UUID.randomUUID().toString(),
          ipAddress = call.request.origin.remoteAddress,
        )
        call.respond(
          HttpStatusCode.OK,
          paymentService.verifyClientPayment(
            customerId = principal.userId,
            request = request,
            audit = audit,
          ),
        )
      }
    }

    requirePermission("owner:refund_execute", authorizationService) {
      post("/api/v1/owner/orders/{id}/refund") {
        val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
        val orderId = parseUuid(call.parameters["id"], "order_id")
        val idempotencyKey = call.request.header("Idempotency-Key")
        val request = call.receive<OwnerRefundRequest>()
        val audit = AuditContext(
          actorUserId = principal.userId,
          actorRole = principal.role.name,
          correlationId = call.callId ?: UUID.randomUUID().toString(),
          ipAddress = call.request.origin.remoteAddress,
        )
        call.respond(
          HttpStatusCode.Accepted,
          paymentService.initiateOwnerRefund(
            orderId = orderId,
            ownerId = principal.userId,
            rawIdempotencyKey = idempotencyKey,
            request = request,
            audit = audit,
          ),
        )
      }
    }
  }
}

private fun parseUuid(raw: String?, field: String): UUID =
  runCatching { UUID.fromString(raw) }
    .getOrElse { throw ValidationException("INVALID_UUID", "Invalid UUID parameter for $field.") }

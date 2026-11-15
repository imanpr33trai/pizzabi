package com.pizzora.backend.razorpay

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.core.config.RazorpayConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.payments.PaymentService
import java.nio.charset.StandardCharsets
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

import org.slf4j.LoggerFactory

class WebhookService(
  private val dataSource: DataSource,
  private val paymentService: PaymentService,
  private val razorpayConfig: RazorpayConfig,
) {
  private val logger = LoggerFactory.getLogger(WebhookService::class.java)
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun handleRazorpayWebhook(
    rawBody: ByteArray,
    signatureHeader: String?,
    eventIdHeader: String?,
    audit: AuditContext,
  ) {
    // Verify HMAC-SHA256 on raw request bytes BEFORE any JSON parsing or business DB mutations
    val validSignature = RazorpaySignatureVerifier.verifyWebhookSignature(
      rawBody = rawBody,
      signatureHeader = signatureHeader,
      webhookSecret = razorpayConfig.webhookSecret,
    )

    if (!validSignature) {
      logger.warn(
        "Blocked Razorpay webhook with invalid HMAC signature eventId={} hasSignature={} ip={}",
        (eventIdHeader ?: "unknown").take(128),
        !signatureHeader.isNullOrBlank(),
        audit.ipAddress,
      )
      DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
        AuditLogger.record(
          conn = conn,
          context = audit.copy(actorRole = "SYSTEM"),
          action = "RAZORPAY_WEBHOOK_INVALID_SIGNATURE",
          entityType = "webhook",
          entityId = (eventIdHeader ?: "unknown").take(128),
          afterJson = buildJsonObject {
            put("has_signature", !signatureHeader.isNullOrBlank())
          }.toString(),
        )
      }
      throw UnauthorizedException("INVALID_WEBHOOK_SIGNATURE", "Invalid Razorpay webhook signature.")
    }

    val rawString = rawBody.toString(StandardCharsets.UTF_8)
    val root = runCatching { json.parseToJsonElement(rawString).jsonObject }.getOrElse {
      throw ValidationException("MALFORMED_WEBHOOK_JSON", "Invalid JSON payload in Razorpay webhook.")
    }

    val eventType = root["event"]?.jsonPrimitive?.contentOrNull?.trim()
      ?: throw ValidationException("MISSING_WEBHOOK_EVENT", "Webhook payload missing 'event' field.")
    val payloadObj = root["payload"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
    val createdAt = root["created_at"]?.jsonPrimitive?.longOrNull

    when (eventType) {
      "payment.captured" -> {
        val entity = payloadObj["payment"]?.jsonObject?.get("entity")?.jsonObject
          ?: throw ValidationException("MISSING_PAYMENT_ENTITY", "payment.captured webhook missing payload.payment.entity.")
        val gatewayPaymentId = entity["id"]?.jsonPrimitive?.contentOrNull
          ?: throw ValidationException("MISSING_GATEWAY_PAYMENT_ID", "payment.captured missing payment entity id.")
        val gatewayOrderId = entity["order_id"]?.jsonPrimitive?.contentOrNull
          ?: throw ValidationException("MISSING_GATEWAY_ORDER_ID", "payment.captured missing payment entity order_id.")
        val amountPaise = entity["amount"]?.jsonPrimitive?.longOrNull
        val eventId = resolveGatewayEventId(eventIdHeader, root, eventType, gatewayPaymentId, createdAt)

        paymentService.processPaymentCaptured(
          source = "WEBHOOK",
          gatewayEventId = eventId,
          gatewayOrderId = gatewayOrderId,
          gatewayPaymentId = gatewayPaymentId,
          amountPaise = amountPaise,
          orderIdHint = null,
          actorCustomerId = null,
          rawPayloadJson = rawString,
          audit = audit.copy(actorRole = "SYSTEM"),
        )
      }

      "payment.failed" -> {
        val entity = payloadObj["payment"]?.jsonObject?.get("entity")?.jsonObject ?: return
        val gatewayPaymentId = entity["id"]?.jsonPrimitive?.contentOrNull
        val gatewayOrderId = entity["order_id"]?.jsonPrimitive?.contentOrNull ?: return
        val failureCode = entity["error_code"]?.jsonPrimitive?.contentOrNull
        val failureReason = entity["error_description"]?.jsonPrimitive?.contentOrNull
          ?: entity["error_reason"]?.jsonPrimitive?.contentOrNull
        val eventId = resolveGatewayEventId(
          eventIdHeader,
          root,
          eventType,
          gatewayPaymentId ?: gatewayOrderId,
          createdAt,
        )

        paymentService.processPaymentFailedWebhook(
          gatewayEventId = eventId,
          gatewayOrderId = gatewayOrderId,
          gatewayPaymentId = gatewayPaymentId,
          failureCode = failureCode,
          failureReason = failureReason,
          rawPayloadJson = rawString,
          audit = audit.copy(actorRole = "SYSTEM"),
        )
      }

      "refund.processed", "refund.failed" -> {
        val entity = payloadObj["refund"]?.jsonObject?.get("entity")?.jsonObject
          ?: throw ValidationException("MISSING_REFUND_ENTITY", "$eventType webhook missing payload.refund.entity.")
        val gatewayRefundId = entity["id"]?.jsonPrimitive?.contentOrNull
          ?: throw ValidationException("MISSING_GATEWAY_REFUND_ID", "$eventType missing refund entity id.")
        val notesObj = entity["notes"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val idempNote = notesObj?.get("idempotency_key")?.jsonPrimitive?.contentOrNull
        val eventId = resolveGatewayEventId(eventIdHeader, root, eventType, gatewayRefundId, createdAt)

        paymentService.processRefundWebhook(
          gatewayEventId = eventId,
          gatewayRefundId = gatewayRefundId,
          idempotencyKeyNote = idempNote,
          processed = eventType == "refund.processed",
          rawPayloadJson = rawString,
          audit = audit.copy(actorRole = "SYSTEM"),
        )
      }

      else -> {
        val eventId = resolveGatewayEventId(eventIdHeader, root, eventType, "generic", createdAt)
        DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
          PaymentService.insertPaymentEventIdempotent(
            conn = conn,
            paymentId = null,
            attemptId = null,
            source = "WEBHOOK",
            gatewayEventId = eventId,
            eventType = eventType,
            payloadJson = rawString,
            signatureValid = true,
          )
        }
      }
    }
  }

  private fun resolveGatewayEventId(
    eventIdHeader: String?,
    root: JsonObject,
    eventType: String,
    entityId: String,
    createdAt: Long?,
  ): String {
    val fromHeader = eventIdHeader?.trim()?.takeIf { it.isNotEmpty() }
    if (fromHeader != null) return fromHeader
    val fromBody = root["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    if (fromBody != null) return fromBody
    return "webhook:$eventType:$entityId:${createdAt ?: 0L}"
  }
}

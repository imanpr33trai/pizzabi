package com.pizzora.backend.payments

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.core.config.RazorpayConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ConflictException
import com.pizzora.backend.core.error.ForbiddenException
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.UnprocessableEntityException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.inventory.InventoryService
import com.pizzora.backend.notifications.NotificationService
import com.pizzora.backend.orders.OrderService
import com.pizzora.backend.razorpay.RazorpaySignatureVerifier
import com.pizzora.contracts.FailPaymentAttemptRequest
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.NotificationDeliveryStatus
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderPaymentStatusResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OwnerRefundRequest
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStateMachine
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.RefundDto
import com.pizzora.contracts.RefundStatus
import com.pizzora.contracts.RetryPaymentRequest
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
import com.pizzora.contracts.VerifyRazorpayPaymentResponse
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PaymentService(
  private val dataSource: DataSource,
  private val inventoryService: InventoryService,
  private val couponService: CouponService,
  private val orderService: OrderService,
  private val razorpayConfig: RazorpayConfig,
  private val publicKeyIdProvider: () -> String = { razorpayConfig.keyId },
) {

  suspend fun getOrderPaymentStatus(
    orderId: UUID,
    actorUserId: UUID,
    actorRole: UserRole,
  ): OrderPaymentStatusResponse =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val detail = orderService.loadOrderDetail(conn, orderId)
        ?: throw NotFoundException("ORDER_NOT_FOUND", "Order $orderId was not found.")
      if (actorRole == UserRole.CUSTOMER && detail.customerId != actorUserId.toString()) {
        throw ForbiddenException("FORBIDDEN", "You do not have access to this order's payment.")
      }
      buildOrderPaymentStatusResponse(conn, detail)
    }

  suspend fun retryPayment(
    orderId: UUID,
    customerId: UUID,
    rawIdempotencyKey: String?,
    request: RetryPaymentRequest?,
    audit: AuditContext,
  ): OrderPaymentStatusResponse {
    val cleanIdempKey = rawIdempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanIdempKey != null && cleanIdempKey.length > 100) {
      throw ValidationException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key cannot exceed 100 characters.")
    }

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val order = lockOrder(conn, orderId)
      if (order.customerId != customerId) {
        throw ForbiddenException("FORBIDDEN", "You cannot retry payment for another customer's order.")
      }
      if (order.status != OrderStatus.PENDING_PAYMENT) {
        throw ConflictException(
          "ORDER_NOT_RETRYABLE",
          "Order ${order.orderNumber} in status ${order.status} does not accept payment retries.",
        )
      }
      val now = Instant.now()
      if (order.expiresAt != null && !order.expiresAt.isAfter(now)) {
        throw ConflictException(
          "PAYMENT_WINDOW_EXPIRED",
          "Payment window for order ${order.orderNumber} has expired.",
        )
      }

      val payment = lockPaymentByOrder(conn, orderId)
      if (payment.method != PaymentMethod.RAZORPAY) {
        throw ConflictException("PAYMENT_METHOD_NOT_RETRYABLE", "COD orders do not support online payment retry.")
      }
      if (payment.status != PaymentStatus.UNPAID) {
        throw ConflictException("PAYMENT_ALREADY_SETTLED", "Payment is already in status ${payment.status}.")
      }

      val receipt = cleanIdempKey?.let { "retry:$orderId:$it" }
      if (receipt != null) {
        val existingReceipt = conn.prepareStatement(
          "SELECT id FROM payment_attempts WHERE payment_id = ? AND idempotency_receipt = ?",
        ).use { ps ->
          ps.setObject(1, payment.id)
          ps.setString(2, receipt)
          ps.executeQuery().use { rs -> rs.next() }
        }
        if (existingReceipt) {
          val detail = orderService.loadOrderDetail(conn, orderId)!!
          return@withJdbcTransaction buildOrderPaymentStatusResponse(conn, detail)
        }
      }

      val maxAttemptNum = conn.prepareStatement(
        "SELECT COALESCE(MAX(attempt_number), 0) FROM payment_attempts WHERE payment_id = ?",
      ).use { ps ->
        ps.setObject(1, payment.id)
        ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
      }

      conn.prepareStatement(
        """
        UPDATE payment_attempts
        SET status = 'FAILED',
            failure_code = COALESCE(failure_code, 'SUPERSEDED'),
            failure_reason = COALESCE(failure_reason, 'Superseded by customer payment retry'),
            updated_at = NOW()
        WHERE payment_id = ? AND status IN ('INITIATING', 'AWAITING_CUSTOMER_PAYMENT')
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, payment.id)
        ps.executeUpdate()
      }

      val attemptId = UUID.randomUUID()
      val nextNumber = maxAttemptNum + 1
      val effectiveReceipt = receipt ?: attemptId.toString()

      conn.prepareStatement(
        """
        INSERT INTO payment_attempts (
          id, payment_id, attempt_number, method, status, amount_paise, idempotency_receipt, expires_at
        ) VALUES (?, ?, ?, 'RAZORPAY', 'INITIATING', ?, ?, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, attemptId)
        ps.setObject(2, payment.id)
        ps.setInt(3, nextNumber)
        ps.setLong(4, payment.amountPaise)
        ps.setString(5, effectiveReceipt)
        if (order.expiresAt != null) ps.setTimestamp(6, Timestamp.from(order.expiresAt)) else ps.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE)
        ps.executeUpdate()
      }

      val cleanReason = request?.reason?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
      val payload = buildJsonObject {
        put("order_id", orderId.toString())
        put("order_number", order.orderNumber)
        put("customer_id", order.customerId.toString())
        put("payment_id", payment.id.toString())
        put("payment_attempt_id", attemptId.toString())
        put("attempt_number", nextNumber)
        put("payment_method", PaymentMethod.RAZORPAY.name)
        put("amount_paise", payment.amountPaise)
        put("receipt", effectiveReceipt)
        if (cleanReason != null) put("reason", cleanReason)
      }.toString()

      orderService.insertOutboxEvent(
        conn = conn,
        aggregateType = "ORDER",
        aggregateId = orderId,
        aggregateVersion = order.version,
        eventType = "PAYMENT_GATEWAY_ORDER_REQUESTED",
        payloadJson = payload,
      )

      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "PAYMENT_RETRY_INITIATED",
        entityType = "payment_attempt",
        entityId = attemptId.toString(),
        afterJson = "{\"order_id\":\"$orderId\",\"attempt_number\":$nextNumber}",
      )

      val detail = orderService.loadOrderDetail(conn, orderId)!!
      buildOrderPaymentStatusResponse(conn, detail)
    }
  }

  suspend fun reportAttemptFailure(
    orderId: UUID,
    customerId: UUID,
    request: FailPaymentAttemptRequest,
    audit: AuditContext,
  ): OrderPaymentStatusResponse =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val order = lockOrder(conn, orderId)
      if (order.customerId != customerId) {
        throw ForbiddenException("FORBIDDEN", "You cannot modify another customer's payment attempt.")
      }
      val payment = lockPaymentByOrder(conn, orderId)

      if (order.status == OrderStatus.PENDING_PAYMENT && payment.status == PaymentStatus.UNPAID) {
        val targetAttempt = findTargetAttemptForFailure(conn, payment.id, request)
        if (targetAttempt != null && PaymentStateMachine.canTransitionAttempt(targetAttempt.status, PaymentAttemptStatus.FAILED)) {
          val cleanCode = request.failureCode?.trim()?.take(64)?.ifEmpty { "GATEWAY_ERROR" } ?: "GATEWAY_ERROR"
          val cleanReason = request.failureReason?.trim()?.take(250)?.ifEmpty { "Payment cancelled or failed" }
            ?: "Payment cancelled or failed"

          conn.prepareStatement(
            """
            UPDATE payment_attempts
            SET status = 'FAILED', failure_code = ?, failure_reason = ?, updated_at = NOW()
            WHERE id = ?
            """.trimIndent(),
          ).use { ps ->
            ps.setString(1, cleanCode)
            ps.setString(2, cleanReason)
            ps.setObject(3, targetAttempt.id)
            ps.executeUpdate()
          }

          insertPaymentEventIdempotent(
            conn = conn,
            paymentId = payment.id,
            attemptId = targetAttempt.id,
            source = "CLIENT_VERIFY",
            gatewayEventId = "client_fail:${targetAttempt.id}",
            eventType = "payment.failed",
            payloadJson = buildJsonObject {
              put("order_id", orderId.toString())
              put("attempt_id", targetAttempt.id.toString())
              put("failure_code", cleanCode)
              put("failure_reason", cleanReason)
            }.toString(),
            signatureValid = true,
          )

          AuditLogger.record(
            conn = conn,
            context = audit,
            action = "PAYMENT_ATTEMPT_FAILED",
            entityType = "payment_attempt",
            entityId = targetAttempt.id.toString(),
            afterJson = buildJsonObject {
              put("order_id", orderId.toString())
              put("failure_code", cleanCode)
            }.toString(),
          )
        }
      }

      val detail = orderService.loadOrderDetail(conn, orderId)!!
      buildOrderPaymentStatusResponse(conn, detail)
    }

  suspend fun verifyClientPayment(
    customerId: UUID,
    request: VerifyRazorpayPaymentRequest,
    audit: AuditContext,
  ): VerifyRazorpayPaymentResponse {
    val orderId = parseUuid(request.orderId, "order_id")
    val gatewayOrderId = request.razorpayOrderId.trim()
    val gatewayPaymentId = request.razorpayPaymentId.trim()
    val signature = request.razorpaySignature.trim()

    if (gatewayOrderId.isEmpty() || gatewayPaymentId.isEmpty() || signature.isEmpty()) {
      throw ValidationException(
        "INVALID_VERIFY_PAYLOAD",
        "razorpay_order_id, razorpay_payment_id, and razorpay_signature are required.",
      )
    }

    // Verify HMAC-SHA256 signature strictly OUTSIDE any database transaction
    val signatureValid = RazorpaySignatureVerifier.verifyCheckoutSignature(
      gatewayOrderId = gatewayOrderId,
      gatewayPaymentId = gatewayPaymentId,
      signature = signature,
      keySecret = razorpayConfig.keySecret,
    )

    if (!signatureValid) {
      DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
        AuditLogger.record(
          conn = conn,
          context = audit,
          action = "PAYMENT_SIGNATURE_INVALID",
          entityType = "order",
          entityId = orderId.toString(),
          afterJson = buildJsonObject {
            put("gateway_order_id", gatewayOrderId.take(128))
            put("gateway_payment_id", gatewayPaymentId.take(128))
          }.toString(),
        )
      }
      throw UnauthorizedException(
        "INVALID_PAYMENT_SIGNATURE",
        "Razorpay checkout signature verification failed.",
      )
    }

    val payloadJson = buildJsonObject {
      put("order_id", orderId.toString())
      put("razorpay_order_id", gatewayOrderId)
      put("razorpay_payment_id", gatewayPaymentId)
    }.toString()

    return processPaymentCaptured(
      source = "CLIENT_VERIFY",
      gatewayEventId = "client_verify:$gatewayPaymentId",
      gatewayOrderId = gatewayOrderId,
      gatewayPaymentId = gatewayPaymentId,
      amountPaise = null,
      orderIdHint = orderId,
      actorCustomerId = customerId,
      rawPayloadJson = payloadJson,
      audit = audit,
    )
  }

  suspend fun processPaymentCaptured(
    source: String,
    gatewayEventId: String,
    gatewayOrderId: String,
    gatewayPaymentId: String,
    amountPaise: Long?,
    orderIdHint: UUID?,
    actorCustomerId: UUID?,
    rawPayloadJson: String,
    audit: AuditContext,
  ): VerifyRazorpayPaymentResponse =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val lookup = conn.prepareStatement(
        """
        SELECT p.order_id, pa.payment_id, pa.id AS attempt_id
        FROM payment_attempts pa
        JOIN payments p ON p.id = pa.payment_id
        WHERE pa.gateway_order_id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, gatewayOrderId)
        ps.executeQuery().use { rs ->
          if (!rs.next()) null
          else Triple(
            rs.getObject("order_id", UUID::class.java),
            rs.getObject("payment_id", UUID::class.java),
            rs.getObject("attempt_id", UUID::class.java),
          )
        }
      } ?: throw NotFoundException("PAYMENT_ATTEMPT_NOT_FOUND", "No payment attempt found for gateway order $gatewayOrderId.")

      val resolvedOrderId = lookup.first
      if (orderIdHint != null && resolvedOrderId != orderIdHint) {
        throw ConflictException(
          "GATEWAY_ORDER_MISMATCH",
          "Gateway order $gatewayOrderId does not belong to order $orderIdHint.",
        )
      }

      // Consistent lock hierarchy: orders -> payments -> payment_attempts
      val order = lockOrder(conn, resolvedOrderId)
      if (actorCustomerId != null && order.customerId != actorCustomerId) {
        throw ForbiddenException("FORBIDDEN", "You cannot verify payment for another customer's order.")
      }
      val payment = lockPaymentByOrder(conn, resolvedOrderId)
      val attempt = lockAttemptById(conn, lookup.third)

      val eventInserted = insertPaymentEventIdempotent(
        conn = conn,
        paymentId = payment.id,
        attemptId = attempt.id,
        source = source,
        gatewayEventId = gatewayEventId,
        eventType = "payment.captured",
        payloadJson = rawPayloadJson,
        signatureValid = true,
      )
      if (!eventInserted) {
        val detail = orderService.loadOrderDetail(conn, resolvedOrderId)!!
        return@withJdbcTransaction VerifyRazorpayPaymentResponse(
          order = detail,
          alreadyCaptured = true,
          lateCaptureRefundTriggered = false,
        )
      }

      val effectiveCapturedAmount = amountPaise ?: payment.amountPaise

      when {
        order.status == OrderStatus.PENDING_PAYMENT && payment.status == PaymentStatus.UNPAID -> {
          if (amountPaise != null && amountPaise != payment.amountPaise) {
            AuditLogger.record(
              conn = conn,
              context = audit,
              action = "PAYMENT_AMOUNT_MISMATCH",
              entityType = "payment",
              entityId = payment.id.toString(),
              afterJson = "{\"expected_paise\":${payment.amountPaise},\"received_paise\":$amountPaise}",
            )
            throw ConflictException(
              "PAYMENT_AMOUNT_MISMATCH",
              "Captured amount $amountPaise does not match expected ${payment.amountPaise}.",
            )
          }

          conn.prepareStatement(
            """
            UPDATE payment_attempts
            SET status = 'CAPTURED', gateway_payment_id = ?, updated_at = NOW()
            WHERE id = ?
            """.trimIndent(),
          ).use { ps ->
            ps.setString(1, gatewayPaymentId)
            ps.setObject(2, attempt.id)
            ps.executeUpdate()
          }

          conn.prepareStatement(
            """
            UPDATE payment_attempts
            SET status = 'FAILED',
                failure_code = COALESCE(failure_code, 'SUPERSEDED'),
                failure_reason = COALESCE(failure_reason, 'Another attempt captured'),
                updated_at = NOW()
            WHERE payment_id = ? AND id <> ? AND status IN ('INITIATING', 'AWAITING_CUSTOMER_PAYMENT')
            """.trimIndent(),
          ).use { ps ->
            ps.setObject(1, payment.id)
            ps.setObject(2, attempt.id)
            ps.executeUpdate()
          }

          conn.prepareStatement(
            """
            UPDATE payments
            SET status = 'CAPTURED', captured_paise = ?, updated_at = NOW()
            WHERE id = ?
            """.trimIndent(),
          ).use { ps ->
            ps.setLong(1, effectiveCapturedAmount)
            ps.setObject(2, payment.id)
            ps.executeUpdate()
          }

          val newVersion = order.version + 1L
          conn.prepareStatement(
            "UPDATE orders SET status = 'PLACED', version = ?, expires_at = NULL WHERE id = ?",
          ).use { ps ->
            ps.setLong(1, newVersion)
            ps.setObject(2, resolvedOrderId)
            ps.executeUpdate()
          }

          conn.prepareStatement(
            """
            INSERT INTO order_status_history (id, order_id, from_status, to_status, changed_by, reason)
            VALUES (?, ?, 'PENDING_PAYMENT', 'PLACED', ?, ?)
            """.trimIndent(),
          ).use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setObject(2, resolvedOrderId)
            if (actorCustomerId != null) ps.setObject(3, actorCustomerId) else ps.setNull(3, Types.OTHER)
            ps.setString(4, "Payment captured ($source)")
            ps.executeUpdate()
          }

          inventoryService.commitReservedInventory(conn, resolvedOrderId, actorUserId = order.customerId)
          couponService.commitReservedCoupon(conn, resolvedOrderId)
          conn.prepareStatement(
            """
            UPDATE scheduled_slot_reservations
            SET status = 'COMMITTED', expires_at = NULL, updated_at = NOW()
            WHERE order_id = ? AND status = 'ACTIVE'
            """.trimIndent(),
          ).use { ps ->
            ps.setObject(1, resolvedOrderId)
            ps.executeUpdate()
          }

          val placedPayload = buildJsonObject {
            put("order_id", resolvedOrderId.toString())
            put("order_number", order.orderNumber)
            put("customer_id", order.customerId.toString())
            put("payment_id", payment.id.toString())
            put("payment_attempt_id", attempt.id.toString())
            put("payment_method", payment.method.name)
            put("gateway_payment_id", gatewayPaymentId)
            put("amount_paise", effectiveCapturedAmount)
            put("fulfillment_type", order.fulfillmentType.name)
            put("status", OrderStatus.PLACED.name)
          }.toString()

          orderService.insertOutboxEvent(
            conn = conn,
            aggregateType = "ORDER",
            aggregateId = resolvedOrderId,
            aggregateVersion = newVersion,
            eventType = "ORDER_PLACED",
            payloadJson = placedPayload,
          )

          AuditLogger.record(
            conn = conn,
            context = audit,
            action = "PAYMENT_CAPTURED",
            entityType = "payment",
            entityId = payment.id.toString(),
            afterJson = "{\"order_id\":\"$resolvedOrderId\",\"source\":\"$source\",\"gateway_payment_id\":\"$gatewayPaymentId\"}",
          )

          VerifyRazorpayPaymentResponse(
            order = orderService.loadOrderDetail(conn, resolvedOrderId)!!,
            alreadyCaptured = false,
            lateCaptureRefundTriggered = false,
          )
        }

        order.status == OrderStatus.EXPIRED || order.status == OrderStatus.CANCELLED -> {
          // Exceptional late-capture path: NEVER resurrect a terminal order!
          conn.prepareStatement(
            """
            UPDATE payment_attempts
            SET status = 'CAPTURED', gateway_payment_id = COALESCE(gateway_payment_id, ?), updated_at = NOW()
            WHERE id = ?
            """.trimIndent(),
          ).use { ps ->
            ps.setString(1, gatewayPaymentId)
            ps.setObject(2, attempt.id)
            ps.executeUpdate()
          }

          val needsTransition = PaymentStateMachine.canTransitionPayment(payment.status, PaymentStatus.REFUND_PENDING)
          if (needsTransition || payment.capturedPaise < effectiveCapturedAmount) {
            conn.prepareStatement(
              """
              UPDATE payments
              SET captured_paise = GREATEST(captured_paise, ?),
                  status = CASE WHEN status IN ('EXPIRED', 'CANCELLED', 'UNPAID') THEN 'REFUND_PENDING' ELSE status END,
                  updated_at = NOW()
              WHERE id = ?
              """.trimIndent(),
            ).use { ps ->
              ps.setLong(1, effectiveCapturedAmount)
              ps.setObject(2, payment.id)
              ps.executeUpdate()
            }
          }

          val refundId = UUID.randomUUID()
          val idempotencyKey = "late_capture:${attempt.id}"
          val reason = "LATE_CAPTURE_AFTER_ORDER_${order.status.name}"
          val insertedRefundId = insertRefundAndOutboxIfAbsent(
            conn = conn,
            refundId = refundId,
            paymentId = payment.id,
            attemptId = attempt.id,
            orderId = resolvedOrderId,
            orderVersion = order.version,
            idempotencyKey = idempotencyKey,
            amountPaise = effectiveCapturedAmount,
            reason = reason,
            initiatedBy = null,
            gatewayPaymentId = gatewayPaymentId,
          )

          if (insertedRefundId != null) {
            AuditLogger.record(
              conn = conn,
              context = audit,
              action = "LATE_CAPTURE_REFUND_QUEUED",
              entityType = "refund",
              entityId = insertedRefundId.toString(),
              afterJson = buildJsonObject {
                put("order_id", resolvedOrderId.toString())
                put("terminal_status", order.status.name)
                put("gateway_payment_id", gatewayPaymentId)
                put("amount_paise", effectiveCapturedAmount)
              }.toString(),
            )
          }

          VerifyRazorpayPaymentResponse(
            order = orderService.loadOrderDetail(conn, resolvedOrderId)!!,
            alreadyCaptured = insertedRefundId == null,
            lateCaptureRefundTriggered = insertedRefundId != null,
          )
        }

        else -> {
          // Order already placed/progressed and payment already captured
          val existingWithSameGatewayPayment = conn.prepareStatement(
            "SELECT id FROM payment_attempts WHERE payment_id = ? AND gateway_payment_id = ?",
          ).use { ps ->
            ps.setObject(1, payment.id)
            ps.setString(2, gatewayPaymentId)
            ps.executeQuery().use { rs -> rs.next() }
          }

          if (existingWithSameGatewayPayment || attempt.status == PaymentAttemptStatus.CAPTURED) {
            VerifyRazorpayPaymentResponse(
              order = orderService.loadOrderDetail(conn, resolvedOrderId)!!,
              alreadyCaptured = true,
              lateCaptureRefundTriggered = false,
            )
          } else {
            // Duplicate distinct payment capture on an already-settled order -> trigger duplicate refund
            conn.prepareStatement(
              """
              UPDATE payment_attempts
              SET status = 'CAPTURED', gateway_payment_id = ?, updated_at = NOW()
              WHERE id = ?
              """.trimIndent(),
            ).use { ps ->
              ps.setString(1, gatewayPaymentId)
              ps.setObject(2, attempt.id)
              ps.executeUpdate()
            }
            conn.prepareStatement(
              """
              UPDATE payments
              SET captured_paise = captured_paise + ?,
                  status = CASE WHEN status = 'CAPTURED' THEN 'REFUND_PENDING' ELSE status END,
                  updated_at = NOW()
              WHERE id = ?
              """.trimIndent(),
            ).use { ps ->
              ps.setLong(1, effectiveCapturedAmount)
              ps.setObject(2, payment.id)
              ps.executeUpdate()
            }

            val refundId = UUID.randomUUID()
            val idempotencyKey = "duplicate_capture:${attempt.id}"
            insertRefundAndOutboxIfAbsent(
              conn = conn,
              refundId = refundId,
              paymentId = payment.id,
              attemptId = attempt.id,
              orderId = resolvedOrderId,
              orderVersion = order.version,
              idempotencyKey = idempotencyKey,
              amountPaise = effectiveCapturedAmount,
              reason = "DUPLICATE_GATEWAY_CAPTURE",
              initiatedBy = null,
              gatewayPaymentId = gatewayPaymentId,
            )

            VerifyRazorpayPaymentResponse(
              order = orderService.loadOrderDetail(conn, resolvedOrderId)!!,
              alreadyCaptured = true,
              lateCaptureRefundTriggered = true,
            )
          }
        }
      }
    }

  suspend fun processPaymentFailedWebhook(
    gatewayEventId: String,
    gatewayOrderId: String,
    gatewayPaymentId: String?,
    failureCode: String?,
    failureReason: String?,
    rawPayloadJson: String,
    audit: AuditContext,
  ) {
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val lookup = conn.prepareStatement(
        """
        SELECT p.order_id, pa.payment_id, pa.id AS attempt_id
        FROM payment_attempts pa
        JOIN payments p ON p.id = pa.payment_id
        WHERE pa.gateway_order_id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, gatewayOrderId)
        ps.executeQuery().use { rs ->
          if (!rs.next()) null
          else Triple(
            rs.getObject("order_id", UUID::class.java),
            rs.getObject("payment_id", UUID::class.java),
            rs.getObject("attempt_id", UUID::class.java),
          )
        }
      }

      if (lookup == null) {
        insertPaymentEventIdempotent(
          conn = conn,
          paymentId = null,
          attemptId = null,
          source = "WEBHOOK",
          gatewayEventId = gatewayEventId,
          eventType = "payment.failed",
          payloadJson = rawPayloadJson,
          signatureValid = true,
        )
        return@withJdbcTransaction
      }

      lockOrder(conn, lookup.first)
      lockPaymentByOrder(conn, lookup.first)
      val attempt = lockAttemptById(conn, lookup.third)

      val inserted = insertPaymentEventIdempotent(
        conn = conn,
        paymentId = lookup.second,
        attemptId = attempt.id,
        source = "WEBHOOK",
        gatewayEventId = gatewayEventId,
        eventType = "payment.failed",
        payloadJson = rawPayloadJson,
        signatureValid = true,
      )
      if (!inserted) return@withJdbcTransaction

      if (PaymentStateMachine.canTransitionAttempt(attempt.status, PaymentAttemptStatus.FAILED)) {
        conn.prepareStatement(
          """
          UPDATE payment_attempts
          SET status = 'FAILED',
              gateway_payment_id = COALESCE(gateway_payment_id, ?),
              failure_code = ?,
              failure_reason = ?,
              updated_at = NOW()
          WHERE id = ?
          """.trimIndent(),
        ).use { ps ->
          ps.setString(1, gatewayPaymentId)
          ps.setString(2, failureCode?.take(64) ?: "PAYMENT_FAILED")
          ps.setString(3, failureReason?.take(250) ?: "Gateway reported payment failure")
          ps.setObject(4, attempt.id)
          ps.executeUpdate()
        }

        AuditLogger.record(
          conn = conn,
          context = audit,
          action = "PAYMENT_WEBHOOK_FAILED",
          entityType = "payment_attempt",
          entityId = attempt.id.toString(),
          afterJson = buildJsonObject {
            put("order_id", lookup.first.toString())
            put("failure_code", failureCode ?: "PAYMENT_FAILED")
          }.toString(),
        )
      }
    }
  }

  suspend fun processRefundWebhook(
    gatewayEventId: String,
    gatewayRefundId: String,
    idempotencyKeyNote: String?,
    processed: Boolean,
    rawPayloadJson: String,
    audit: AuditContext,
  ) {
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val refundLookup = conn.prepareStatement(
        """
        SELECT id, payment_id, order_id, status, amount_paise
        FROM refunds
        WHERE gateway_refund_id = ? OR (? IS NOT NULL AND idempotency_key = ?)
        LIMIT 1
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, gatewayRefundId)
        ps.setString(2, idempotencyKeyNote)
        ps.setString(3, idempotencyKeyNote)
        ps.executeQuery().use { rs ->
          if (!rs.next()) null else rs.getObject("id", UUID::class.java) to rs.getObject("order_id", UUID::class.java)
        }
      }

      if (refundLookup == null) {
        insertPaymentEventIdempotent(
          conn = conn,
          paymentId = null,
          attemptId = null,
          source = "WEBHOOK",
          gatewayEventId = gatewayEventId,
          eventType = if (processed) "refund.processed" else "refund.failed",
          payloadJson = rawPayloadJson,
          signatureValid = true,
        )
        return@withJdbcTransaction
      }

      val order = lockOrder(conn, refundLookup.second)
      val payment = lockPaymentByOrder(conn, order.id)
      val refund = lockRefundById(conn, refundLookup.first)!!

      val inserted = insertPaymentEventIdempotent(
        conn = conn,
        paymentId = payment.id,
        attemptId = refund.paymentAttemptId?.let { UUID.fromString(it) },
        source = "WEBHOOK",
        gatewayEventId = gatewayEventId,
        eventType = if (processed) "refund.processed" else "refund.failed",
        payloadJson = rawPayloadJson,
        signatureValid = true,
      )
      if (!inserted) return@withJdbcTransaction

      val refundUuid = refundLookup.first
      if (processed) {
        markRefundProcessedAndUpdatePayment(conn, refundUuid, payment.id, gatewayRefundId)
        AuditLogger.record(
          conn = conn,
          context = audit,
          action = "REFUND_WEBHOOK_PROCESSED",
          entityType = "refund",
          entityId = refund.id,
          afterJson = buildJsonObject { put("gateway_refund_id", gatewayRefundId) }.toString(),
        )
      } else if (refund.status == RefundStatus.PENDING || refund.status == RefundStatus.PROCESSING) {
        conn.prepareStatement(
          "UPDATE refunds SET status = 'FAILED', updated_at = NOW() WHERE id = ?",
        ).use { ps ->
          ps.setObject(1, refundUuid)
          ps.executeUpdate()
        }
        notifyOwners(
          conn = conn,
          eventId = UUID.randomUUID(),
          title = "Refund Failed: ${order.orderNumber}",
          body = "Gateway reported refund failure for order ${order.orderNumber}. Manual retry required.",
          category = "REFUND_ALERT",
          referenceId = order.id,
        )
        AuditLogger.record(
          conn = conn,
          context = audit,
          action = "REFUND_WEBHOOK_FAILED",
          entityType = "refund",
          entityId = refund.id.toString(),
          afterJson = buildJsonObject { put("gateway_refund_id", gatewayRefundId) }.toString(),
        )
      }
    }
  }

  suspend fun initiateOwnerRefund(
    orderId: UUID,
    ownerId: UUID,
    rawIdempotencyKey: String?,
    request: OwnerRefundRequest,
    audit: AuditContext,
  ): RefundDto {
    val cleanReason = request.reason.trim()
    if (cleanReason.isEmpty() || cleanReason.length > 250) {
      throw ValidationException("INVALID_REFUND_REASON", "Refund reason (1..250 chars) is required.")
    }
    val cleanIdempKey = rawIdempotencyKey?.trim()?.takeIf { it.isNotEmpty() }

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val order = lockOrder(conn, orderId)
      val payment = lockPaymentByOrder(conn, orderId)

      if (payment.method != PaymentMethod.RAZORPAY) {
        throw UnprocessableEntityException("REFUND_UNSUPPORTED_METHOD", "Only Razorpay orders support gateway refunds.")
      }
      if (payment.capturedPaise <= 0L) {
        throw ConflictException("NO_CAPTURED_FUNDS", "Order ${order.orderNumber} has no captured funds to refund.")
      }

      // 1. Idempotency key replay check
      if (cleanIdempKey != null) {
        val scopedKey = "owner_refund:$orderId:$cleanIdempKey"
        val existingByKey = conn.prepareStatement(
          """
          SELECT id, payment_id, payment_attempt_id, order_id, idempotency_key, amount_paise,
                 status, gateway_refund_id, reason, initiated_by, created_at, updated_at
          FROM refunds
          WHERE idempotency_key = ?
          """.trimIndent(),
        ).use { ps ->
          ps.setString(1, scopedKey)
          ps.executeQuery().use { rs -> if (rs.next()) rs.toRefundDto() else null }
        }
        if (existingByKey != null && existingByKey.status != RefundStatus.FAILED) {
          return@withJdbcTransaction existingByKey
        }
      }

      // 2. Check if there is an existing FAILED refund on this order (e.g. from late capture or order cancel) to safely retry without duplicating
      val failedRefund = conn.prepareStatement(
        """
        SELECT id, payment_id, payment_attempt_id, order_id, idempotency_key, amount_paise,
               status, gateway_refund_id, reason, initiated_by, created_at, updated_at
        FROM refunds
        WHERE order_id = ? AND status = 'FAILED'
        ORDER BY created_at ASC
        LIMIT 1
        FOR UPDATE
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, orderId)
        ps.executeQuery().use { rs -> if (rs.next()) rs.toRefundDto() else null }
      }

      if (failedRefund != null && (request.amountPaise == null || request.amountPaise == failedRefund.amountPaise)) {
        val requeued = conn.prepareStatement(
          """
          UPDATE refunds
          SET status = 'PENDING', reason = ?, initiated_by = COALESCE(initiated_by, ?), updated_at = NOW()
          WHERE id = ?
          RETURNING id, payment_id, payment_attempt_id, order_id, idempotency_key, amount_paise,
                    status, gateway_refund_id, reason, initiated_by, created_at, updated_at
          """.trimIndent(),
        ).use { ps ->
          ps.setString(1, cleanReason)
          ps.setObject(2, ownerId)
          ps.setObject(3, UUID.fromString(failedRefund.id))
          ps.executeQuery().use { rs -> rs.next(); rs.toRefundDto() }
        }

        val gatewayPaymentId = findCapturedGatewayPaymentId(conn, payment.id, failedRefund.paymentAttemptId?.let(UUID::fromString))
        orderService.insertOutboxEvent(
          conn = conn,
          aggregateType = "ORDER",
          aggregateId = orderId,
          aggregateVersion = order.version,
          eventType = "REFUND_REQUESTED",
          payloadJson = buildJsonObject {
            put("refund_id", requeued.id)
            put("order_id", orderId.toString())
            put("payment_id", payment.id.toString())
            if (failedRefund.paymentAttemptId != null) put("payment_attempt_id", failedRefund.paymentAttemptId)
            if (gatewayPaymentId != null) put("gateway_payment_id", gatewayPaymentId)
            put("amount_paise", requeued.amountPaise)
            put("idempotency_key", requeued.idempotencyKey)
          }.toString(),
        )

        AuditLogger.record(
          conn = conn,
          context = audit,
          action = "OWNER_REFUND_RETRIED",
          entityType = "refund",
          entityId = requeued.id,
          afterJson = "{\"order_id\":\"$orderId\",\"amount_paise\":${requeued.amountPaise}}",
        )
        return@withJdbcTransaction requeued
      }

      // 3. Check committed/active refund total to guard against over-refund
      val committedRefundTotal = conn.prepareStatement(
        """
        SELECT COALESCE(SUM(amount_paise), 0)::bigint
        FROM refunds
        WHERE payment_id = ? AND status IN ('PENDING', 'PROCESSING', 'PROCESSED')
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, payment.id)
        ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
      }

      val remainingRefundable = payment.capturedPaise - committedRefundTotal
      if (remainingRefundable <= 0L) {
        throw ConflictException("ALREADY_REFUNDED", "No remaining refundable balance on order ${order.orderNumber}.")
      }

      val requestedAmount = request.amountPaise ?: remainingRefundable
      if (requestedAmount <= 0L || requestedAmount > remainingRefundable) {
        throw UnprocessableEntityException(
          "INVALID_REFUND_AMOUNT",
          "Refund amount ($requestedAmount) must be between 1 and remaining refundable ($remainingRefundable) paise.",
        )
      }

      val refundId = UUID.randomUUID()
      val effectiveIdempKey = cleanIdempKey?.let { "owner_refund:$orderId:$it" } ?: "owner_refund:$refundId"
      val attemptId = findCapturedAttemptId(conn, payment.id)
      val gatewayPaymentId = findCapturedGatewayPaymentId(conn, payment.id, attemptId)

      val insertedId = insertRefundAndOutboxIfAbsent(
        conn = conn,
        refundId = refundId,
        paymentId = payment.id,
        attemptId = attemptId,
        orderId = orderId,
        orderVersion = order.version,
        idempotencyKey = effectiveIdempKey,
        amountPaise = requestedAmount,
        reason = cleanReason,
        initiatedBy = ownerId,
        gatewayPaymentId = gatewayPaymentId,
      ) ?: throw ConflictException("REFUND_CONFLICT", "Duplicate refund request detected.")

      if (payment.status != PaymentStatus.REFUND_PENDING && PaymentStateMachine.canTransitionPayment(payment.status, PaymentStatus.REFUND_PENDING)) {
        conn.prepareStatement(
          "UPDATE payments SET status = 'REFUND_PENDING', updated_at = NOW() WHERE id = ?",
        ).use { ps ->
          ps.setObject(1, payment.id)
          ps.executeUpdate()
        }
      }

      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "OWNER_REFUND_INITIATED",
        entityType = "refund",
        entityId = insertedId.toString(),
        afterJson = "{\"order_id\":\"$orderId\",\"amount_paise\":$requestedAmount}",
      )

      lockRefundById(conn, insertedId)!!
    }
  }

  private fun buildOrderPaymentStatusResponse(
    conn: Connection,
    detail: OrderDetailDto,
  ): OrderPaymentStatusResponse {
    val refunds = conn.prepareStatement(
      """
      SELECT id, payment_id, payment_attempt_id, order_id, idempotency_key, amount_paise,
             status, gateway_refund_id, reason, initiated_by, created_at, updated_at
      FROM refunds
      WHERE order_id = ?
      ORDER BY created_at DESC
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, UUID.fromString(detail.id))
      ps.executeQuery().use { rs ->
        val list = mutableListOf<RefundDto>()
        while (rs.next()) list += rs.toRefundDto()
        list
      }
    }

    val expiresAtInstant = detail.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val canRetry = detail.status == OrderStatus.PENDING_PAYMENT &&
      detail.payment.method == PaymentMethod.RAZORPAY &&
      detail.payment.status == PaymentStatus.UNPAID &&
      (expiresAtInstant == null || expiresAtInstant.isAfter(Instant.now()))

    return OrderPaymentStatusResponse(
      orderId = detail.id,
      orderNumber = detail.orderNumber,
      orderStatus = detail.status,
      payment = detail.payment,
      razorpayKeyId = publicKeyIdProvider().takeIf { it.isNotBlank() },
      canRetry = canRetry,
      expiresAt = detail.expiresAt,
      refunds = refunds,
    )
  }

  private fun insertRefundAndOutboxIfAbsent(
    conn: Connection,
    refundId: UUID,
    paymentId: UUID,
    attemptId: UUID?,
    orderId: UUID,
    orderVersion: Long,
    idempotencyKey: String,
    amountPaise: Long,
    reason: String,
    initiatedBy: UUID?,
    gatewayPaymentId: String?,
  ): UUID? {
    val inserted = conn.prepareStatement(
      """
      INSERT INTO refunds (
        id, payment_id, payment_attempt_id, order_id, idempotency_key, amount_paise, status, reason, initiated_by
      ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
      ON CONFLICT (idempotency_key) DO NOTHING
      RETURNING id
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, refundId)
      ps.setObject(2, paymentId)
      if (attemptId != null) ps.setObject(3, attemptId) else ps.setNull(3, Types.OTHER)
      ps.setObject(4, orderId)
      ps.setString(5, idempotencyKey)
      ps.setLong(6, amountPaise)
      ps.setString(7, reason)
      if (initiatedBy != null) ps.setObject(8, initiatedBy) else ps.setNull(8, Types.OTHER)
      ps.executeQuery().use { rs -> if (rs.next()) rs.getObject("id", UUID::class.java) else null }
    } ?: return null

    orderService.insertOutboxEvent(
      conn = conn,
      aggregateType = "ORDER",
      aggregateId = orderId,
      aggregateVersion = orderVersion,
      eventType = "REFUND_REQUESTED",
      payloadJson = buildJsonObject {
        put("refund_id", inserted.toString())
        put("order_id", orderId.toString())
        put("payment_id", paymentId.toString())
        if (attemptId != null) put("payment_attempt_id", attemptId.toString())
        if (gatewayPaymentId != null) put("gateway_payment_id", gatewayPaymentId)
        put("amount_paise", amountPaise)
        put("idempotency_key", idempotencyKey)
      }.toString(),
    )
    return inserted
  }

  private fun markRefundProcessedAndUpdatePayment(
    conn: Connection,
    refundId: UUID,
    paymentId: UUID,
    gatewayRefundId: String,
  ) {
    conn.prepareStatement(
      """
      UPDATE refunds
      SET status = 'PROCESSED', gateway_refund_id = COALESCE(gateway_refund_id, ?), updated_at = NOW()
      WHERE id = ?
      """.trimIndent(),
    ).use { ps ->
      ps.setString(1, gatewayRefundId)
      ps.setObject(2, refundId)
      ps.executeUpdate()
    }

    val totals = conn.prepareStatement(
      """
      SELECT p.captured_paise,
             COALESCE((SELECT SUM(r.amount_paise)::bigint FROM refunds r WHERE r.payment_id = p.id AND r.status = 'PROCESSED'), 0) AS processed_sum
      FROM payments p
      WHERE p.id = ?
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, paymentId)
      ps.executeQuery().use { rs ->
        rs.next()
        rs.getLong("captured_paise") to rs.getLong("processed_sum")
      }
    }

    val cappedRefunded = totals.second.coerceAtMost(totals.first)
    val nextPaymentStatus = if (cappedRefunded >= totals.first && totals.first > 0L) {
      PaymentStatus.REFUNDED
    } else {
      PaymentStatus.PARTIALLY_REFUNDED
    }

    conn.prepareStatement(
      "UPDATE payments SET refunded_paise = ?, status = ?, updated_at = NOW() WHERE id = ?",
    ).use { ps ->
      ps.setLong(1, cappedRefunded)
      ps.setString(2, nextPaymentStatus.name)
      ps.setObject(3, paymentId)
      ps.executeUpdate()
    }
  }

  private fun findTargetAttemptForFailure(
    conn: Connection,
    paymentId: UUID,
    request: FailPaymentAttemptRequest,
  ): LockedAttempt? {
    val parsedAttemptId = request.attemptId?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "attempt_id") }
    val gatewayOrderId = request.gatewayOrderId?.trim()?.takeIf { it.isNotEmpty() }

    val sql = when {
      parsedAttemptId != null -> "SELECT id, status FROM payment_attempts WHERE id = ? AND payment_id = ? FOR UPDATE"
      gatewayOrderId != null -> "SELECT id, status FROM payment_attempts WHERE gateway_order_id = ? AND payment_id = ? FOR UPDATE"
      else -> "SELECT id, status FROM payment_attempts WHERE payment_id = ? ORDER BY attempt_number DESC LIMIT 1 FOR UPDATE"
    }
    return conn.prepareStatement(sql).use { ps ->
      when {
        parsedAttemptId != null -> {
          ps.setObject(1, parsedAttemptId)
          ps.setObject(2, paymentId)
        }
        gatewayOrderId != null -> {
          ps.setString(1, gatewayOrderId)
          ps.setObject(2, paymentId)
        }
        else -> ps.setObject(1, paymentId)
      }
      ps.executeQuery().use { rs ->
        if (!rs.next()) null
        else LockedAttempt(
          id = rs.getObject("id", UUID::class.java),
          status = PaymentAttemptStatus.valueOf(rs.getString("status")),
        )
      }
    }
  }

  private fun findCapturedAttemptId(conn: Connection, paymentId: UUID): UUID? =
    conn.prepareStatement(
      """
      SELECT id FROM payment_attempts
      WHERE payment_id = ? AND status = 'CAPTURED'
      ORDER BY attempt_number DESC LIMIT 1
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, paymentId)
      ps.executeQuery().use { rs -> if (rs.next()) rs.getObject("id", UUID::class.java) else null }
    }

  private fun findCapturedGatewayPaymentId(conn: Connection, paymentId: UUID, attemptId: UUID?): String? {
    if (attemptId != null) {
      val byAttempt = conn.prepareStatement("SELECT gateway_payment_id FROM payment_attempts WHERE id = ?").use { ps ->
        ps.setObject(1, attemptId)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString("gateway_payment_id") else null }
      }
      if (!byAttempt.isNullOrBlank()) return byAttempt
    }
    return conn.prepareStatement(
      """
      SELECT gateway_payment_id FROM payment_attempts
      WHERE payment_id = ? AND gateway_payment_id IS NOT NULL
      ORDER BY attempt_number DESC LIMIT 1
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, paymentId)
      ps.executeQuery().use { rs -> if (rs.next()) rs.getString("gateway_payment_id") else null }
    }
  }

  private fun lockOrder(conn: Connection, orderId: UUID): LockedOrder =
    conn.prepareStatement(
      """
      SELECT id, customer_id, order_number, status, version, fulfillment_type, expires_at
      FROM orders
      WHERE id = ?
      FOR UPDATE
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, orderId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) throw NotFoundException("ORDER_NOT_FOUND", "Order $orderId was not found.")
        LockedOrder(
          id = rs.getObject("id", UUID::class.java),
          customerId = rs.getObject("customer_id", UUID::class.java),
          orderNumber = rs.getString("order_number"),
          status = OrderStatus.valueOf(rs.getString("status")),
          version = rs.getLong("version"),
          fulfillmentType = FulfillmentType.valueOf(rs.getString("fulfillment_type")),
          expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java)?.toInstant(),
        )
      }
    }

  private fun lockPaymentByOrder(conn: Connection, orderId: UUID): LockedPayment =
    conn.prepareStatement(
      """
      SELECT id, method, status, amount_paise, captured_paise, refunded_paise
      FROM payments
      WHERE order_id = ?
      FOR UPDATE
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, orderId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) throw NotFoundException("PAYMENT_NOT_FOUND", "Payment record for order $orderId was not found.")
        LockedPayment(
          id = rs.getObject("id", UUID::class.java),
          method = PaymentMethod.valueOf(rs.getString("method")),
          status = PaymentStatus.valueOf(rs.getString("status")),
          amountPaise = rs.getLong("amount_paise"),
          capturedPaise = rs.getLong("captured_paise"),
          refundedPaise = rs.getLong("refunded_paise"),
        )
      }
    }

  private fun lockAttemptById(conn: Connection, attemptId: UUID): LockedAttempt =
    conn.prepareStatement(
      "SELECT id, status FROM payment_attempts WHERE id = ? FOR UPDATE",
    ).use { ps ->
      ps.setObject(1, attemptId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) throw NotFoundException("PAYMENT_ATTEMPT_NOT_FOUND", "Payment attempt $attemptId not found.")
        LockedAttempt(
          id = rs.getObject("id", UUID::class.java),
          status = PaymentAttemptStatus.valueOf(rs.getString("status")),
        )
      }
    }

  private fun lockRefundById(conn: Connection, refundId: UUID): RefundDto? =
    conn.prepareStatement(
      """
      SELECT id, payment_id, payment_attempt_id, order_id, idempotency_key, amount_paise,
             status, gateway_refund_id, reason, initiated_by, created_at, updated_at
      FROM refunds
      WHERE id = ?
      FOR UPDATE
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, refundId)
      ps.executeQuery().use { rs -> if (rs.next()) rs.toRefundDto() else null }
    }

  private fun parseUuid(raw: String, field: String): UUID =
    runCatching { UUID.fromString(raw.trim()) }
      .getOrElse { throw ValidationException("INVALID_UUID", "Invalid UUID for $field.") }

  private fun ResultSet.toRefundDto(): RefundDto =
    RefundDto(
      id = getObject("id", UUID::class.java).toString(),
      paymentId = getObject("payment_id", UUID::class.java).toString(),
      paymentAttemptId = getObject("payment_attempt_id", UUID::class.java)?.toString(),
      orderId = getObject("order_id", UUID::class.java).toString(),
      idempotencyKey = getString("idempotency_key"),
      amountPaise = getLong("amount_paise"),
      status = RefundStatus.valueOf(getString("status")),
      gatewayRefundId = getString("gateway_refund_id"),
      reason = getString("reason"),
      initiatedBy = getObject("initiated_by", UUID::class.java)?.toString(),
      createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
      updatedAt = getObject("updated_at", OffsetDateTime::class.java).toInstant().toString(),
    )

  private data class LockedOrder(
    val id: UUID,
    val customerId: UUID,
    val orderNumber: String,
    val status: OrderStatus,
    val version: Long,
    val fulfillmentType: FulfillmentType,
    val expiresAt: Instant?,
  )

  private data class LockedPayment(
    val id: UUID,
    val method: PaymentMethod,
    val status: PaymentStatus,
    val amountPaise: Long,
    val capturedPaise: Long,
    val refundedPaise: Long,
  )

  private data class LockedAttempt(
    val id: UUID,
    val status: PaymentAttemptStatus,
  )

  companion object {
    fun insertPaymentEventIdempotent(
      conn: Connection,
      paymentId: UUID?,
      attemptId: UUID?,
      source: String,
      gatewayEventId: String,
      eventType: String,
      payloadJson: String,
      signatureValid: Boolean,
    ): Boolean =
      conn.prepareStatement(
        """
        INSERT INTO payment_events (
          id, payment_id, payment_attempt_id, source, gateway_event_id, event_type, payload_json, signature_valid
        ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        ON CONFLICT (gateway_event_id) DO NOTHING
        RETURNING id
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        if (paymentId != null) ps.setObject(2, paymentId) else ps.setNull(2, Types.OTHER)
        if (attemptId != null) ps.setObject(3, attemptId) else ps.setNull(3, Types.OTHER)
        ps.setString(4, source)
        ps.setString(5, gatewayEventId)
        ps.setString(6, eventType)
        ps.setString(7, payloadJson)
        ps.setBoolean(8, signatureValid)
        ps.executeQuery().use { rs -> rs.next() }
      }

    fun notifyOwners(
      conn: Connection,
      eventId: UUID,
      title: String,
      body: String,
      category: String,
      referenceId: UUID?,
    ) {
      val ownerIds = conn.prepareStatement(
        """
        SELECT s.user_id
        FROM staff s
        JOIN roles r ON r.id = s.role_id
        JOIN users u ON u.id = s.user_id
        WHERE r.code = 'OWNER' AND s.is_active = TRUE AND u.is_active = TRUE
        """.trimIndent(),
      ).use { ps ->
        ps.executeQuery().use { rs ->
          val ids = mutableListOf<UUID>()
          while (rs.next()) ids += rs.getObject("user_id", UUID::class.java)
          ids
        }
      }
      if (ownerIds.isEmpty()) return

      for (ownerId in ownerIds) {
        NotificationService.insertInboxNotificationIfAllowed(
          conn = conn,
          userId = ownerId,
          eventId = eventId,
          title = title,
          body = body,
          category = category,
          referenceId = referenceId,
          deliveryStatus = NotificationDeliveryStatus.PENDING,
        )
      }
    }
  }
}

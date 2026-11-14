package com.pizzora.backend.worker

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.payments.PaymentService
import com.pizzora.backend.razorpay.RazorpayGateway
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.RefundStatus
import java.util.UUID
import javax.sql.DataSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

class RazorpayReconciliationWorker(
  private val dataSource: DataSource,
  private val razorpayGateway: RazorpayGateway,
  private val paymentService: PaymentService,
  private val batchSize: Int = 20,
) {
  private val logger = LoggerFactory.getLogger(RazorpayReconciliationWorker::class.java)

  suspend fun runLoop(pollIntervalMs: Long) {
    while (true) {
      delay(pollIntervalMs)
      try {
        runOnce()
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (t: Throwable) {
        logger.error("RazorpayReconciliationWorker loop iteration failed", t)
      }
    }
  }

  suspend fun runOnce(): Int {
    var actions = 0
    actions += reconcileRefunds()
    actions += reconcileOpenPaymentAttempts()
    return actions
  }

  private suspend fun reconcileRefunds(): Int {
    val candidates = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        SELECT r.id, r.payment_id, r.payment_attempt_id, r.order_id, r.idempotency_key,
               r.amount_paise, r.status, r.gateway_refund_id,
               COALESCE(
                 (SELECT pa.gateway_payment_id FROM payment_attempts pa WHERE pa.id = r.payment_attempt_id),
                 (SELECT pa2.gateway_payment_id FROM payment_attempts pa2
                  WHERE pa2.payment_id = r.payment_id AND pa2.gateway_payment_id IS NOT NULL
                  ORDER BY pa2.attempt_number DESC LIMIT 1)
               ) AS gateway_payment_id
        FROM refunds r
        WHERE r.status IN ('FAILED', 'PROCESSING')
        ORDER BY r.updated_at ASC
        LIMIT ?
        """.trimIndent(),
      ).use { ps ->
        ps.setInt(1, batchSize)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<RefundReconcileCandidate>()
          while (rs.next()) {
            val gwPayId = rs.getString("gateway_payment_id") ?: continue
            list += RefundReconcileCandidate(
              refundId = rs.getObject("id", UUID::class.java),
              paymentId = rs.getObject("payment_id", UUID::class.java),
              attemptId = rs.getObject("payment_attempt_id", UUID::class.java),
              orderId = rs.getObject("order_id", UUID::class.java),
              idempotencyKey = rs.getString("idempotency_key"),
              amountPaise = rs.getLong("amount_paise"),
              status = RefundStatus.valueOf(rs.getString("status")),
              gatewayRefundId = rs.getString("gateway_refund_id"),
              gatewayPaymentId = gwPayId,
            )
          }
          list
        }
      }
    }

    var reconciled = 0
    for (candidate in candidates) {
      try {
        val remoteRefunds = razorpayGateway.fetchRefundsForPayment(candidate.gatewayPaymentId)
        val matched = remoteRefunds.firstOrNull { remote ->
          (candidate.gatewayRefundId != null && remote.gatewayRefundId == candidate.gatewayRefundId) ||
            remote.notes["idempotency_key"] == candidate.idempotencyKey
        }

        if (matched != null) {
          if (matched.status.equals("processed", ignoreCase = true) ||
            matched.status.equals("succeeded", ignoreCase = true)
          ) {
            markRefundReconciledProcessed(candidate, matched.gatewayRefundId)
            reconciled++
          }
        } else if (candidate.status == RefundStatus.FAILED) {
          // Gateway confirms no refund was ever created for this idempotency_key -> safely retry creation
          val created = razorpayGateway.createRefund(
            gatewayPaymentId = candidate.gatewayPaymentId,
            amountPaise = candidate.amountPaise,
            idempotencyKey = candidate.idempotencyKey,
            notes = mapOf(
              "idempotency_key" to candidate.idempotencyKey,
              "order_id" to candidate.orderId.toString(),
              "refund_id" to candidate.refundId.toString(),
            ),
          )
          markRefundReconciledProcessed(candidate, created.gatewayRefundId)
          reconciled++
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (t: Throwable) {
        logger.warn("Reconciliation failed for refund {}: {}", candidate.refundId, t.message)
      }
    }
    return reconciled
  }

  private suspend fun reconcileOpenPaymentAttempts(): Int {
    val openAttempts = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        SELECT pa.id, pa.gateway_order_id, p.order_id
        FROM payment_attempts pa
        JOIN payments p ON p.id = pa.payment_id
        WHERE pa.method = 'RAZORPAY'
          AND pa.status = 'AWAITING_CUSTOMER_PAYMENT'
          AND pa.gateway_order_id IS NOT NULL
        ORDER BY pa.updated_at ASC
        LIMIT ?
        """.trimIndent(),
      ).use { ps ->
        ps.setInt(1, batchSize)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<OpenAttemptCandidate>()
          while (rs.next()) {
            list += OpenAttemptCandidate(
              attemptId = rs.getObject("id", UUID::class.java),
              gatewayOrderId = rs.getString("gateway_order_id"),
              orderId = rs.getObject("order_id", UUID::class.java),
            )
          }
          list
        }
      }
    }

    var reconciled = 0
    for (attempt in openAttempts) {
      try {
        val gatewayPayments = razorpayGateway.fetchPaymentsForOrder(attempt.gatewayOrderId)
        val captured = gatewayPayments.firstOrNull { it.status.equals("captured", ignoreCase = true) }
        if (captured != null) {
          paymentService.processPaymentCaptured(
            source = "RECONCILER",
            gatewayEventId = "reconciler_capture:${captured.gatewayPaymentId}",
            gatewayOrderId = attempt.gatewayOrderId,
            gatewayPaymentId = captured.gatewayPaymentId,
            amountPaise = captured.amountPaise.takeIf { it > 0L },
            orderIdHint = attempt.orderId,
            actorCustomerId = null,
            rawPayloadJson = buildJsonObject {
              put("gateway_order_id", attempt.gatewayOrderId)
              put("gateway_payment_id", captured.gatewayPaymentId)
              put("amount_paise", captured.amountPaise)
              put("status", captured.status)
            }.toString(),
            audit = AuditContext(null, "SYSTEM", attempt.attemptId.toString()),
          )
          reconciled++
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (t: Throwable) {
        logger.warn("Reconciliation failed for attempt {}: {}", attempt.attemptId, t.message)
      }
    }
    return reconciled
  }

  private suspend fun markRefundReconciledProcessed(
    candidate: RefundReconcileCandidate,
    gatewayRefundId: String,
  ) {
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        UPDATE refunds
        SET status = 'PROCESSED', gateway_refund_id = COALESCE(gateway_refund_id, ?), updated_at = NOW()
        WHERE id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, gatewayRefundId)
        ps.setObject(2, candidate.refundId)
        ps.executeUpdate()
      }

      val sums = conn.prepareStatement(
        """
        SELECT p.captured_paise,
               COALESCE((SELECT SUM(r.amount_paise)::bigint FROM refunds r WHERE r.payment_id = p.id AND r.status = 'PROCESSED'), 0) AS processed_sum
        FROM payments p
        WHERE p.id = ?
        FOR UPDATE
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, candidate.paymentId)
        ps.executeQuery().use { rs ->
          rs.next()
          rs.getLong("captured_paise") to rs.getLong("processed_sum")
        }
      }

      val capped = sums.second.coerceAtMost(sums.first)
      val nextStatus = if (capped >= sums.first && sums.first > 0L) {
        PaymentStatus.REFUNDED
      } else {
        PaymentStatus.PARTIALLY_REFUNDED
      }

      conn.prepareStatement(
        "UPDATE payments SET refunded_paise = ?, status = ?, updated_at = NOW() WHERE id = ?",
      ).use { ps ->
        ps.setLong(1, capped)
        ps.setString(2, nextStatus.name)
        ps.setObject(3, candidate.paymentId)
        ps.executeUpdate()
      }

      PaymentService.insertPaymentEventIdempotent(
        conn = conn,
        paymentId = candidate.paymentId,
        attemptId = candidate.attemptId,
        source = "RECONCILER",
        gatewayEventId = "reconciler_refund:$gatewayRefundId",
        eventType = "refund.processed",
        payloadJson = buildJsonObject {
          put("refund_id", candidate.refundId.toString())
          put("gateway_refund_id", gatewayRefundId)
        }.toString(),
        signatureValid = true,
      )

      AuditLogger.record(
        conn = conn,
        context = AuditContext(null, "SYSTEM", candidate.refundId.toString()),
        action = "REFUND_RECONCILED_PROCESSED",
        entityType = "refund",
        entityId = candidate.refundId.toString(),
        afterJson = "{\"gateway_refund_id\":\"$gatewayRefundId\"}",
      )
    }
  }

  private data class RefundReconcileCandidate(
    val refundId: UUID,
    val paymentId: UUID,
    val attemptId: UUID?,
    val orderId: UUID,
    val idempotencyKey: String,
    val amountPaise: Long,
    val status: RefundStatus,
    val gatewayRefundId: String?,
    val gatewayPaymentId: String,
  )

  private data class OpenAttemptCandidate(
    val attemptId: UUID,
    val gatewayOrderId: String,
    val orderId: UUID,
  )
}

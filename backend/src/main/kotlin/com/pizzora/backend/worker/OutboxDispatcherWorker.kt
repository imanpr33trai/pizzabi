package com.pizzora.backend.worker

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.notifications.NotificationDeliveryException
import com.pizzora.backend.notifications.NotificationDeliveryProvider
import com.pizzora.backend.notifications.NotificationDispatchRequest
import com.pizzora.backend.notifications.NotificationService
import com.pizzora.backend.notifications.WebSocketInboxNotificationDeliveryProvider
import com.pizzora.backend.payments.PaymentService
import com.pizzora.backend.razorpay.RazorpayGateway
import com.pizzora.backend.razorpay.RazorpayGatewayException
import com.pizzora.backend.realtime.DispatchedRealtimeEvent
import com.pizzora.backend.realtime.RealtimeEventDispatcher
import com.pizzora.backend.realtime.RealtimeEventMapper
import com.pizzora.contracts.NotificationDeliveryStatus
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.RefundStatus
import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

class OutboxDispatcherWorker(
  private val dataSource: DataSource,
  private val razorpayGateway: RazorpayGateway,
  private val maxAttempts: Int = 5,
  private val batchSize: Int = 10,
  private val realtimeDispatcher: RealtimeEventDispatcher = RealtimeEventDispatcher { false },
  private val notificationDeliveryProvider: NotificationDeliveryProvider = WebSocketInboxNotificationDeliveryProvider(),
) {
  private val logger = LoggerFactory.getLogger(OutboxDispatcherWorker::class.java)
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun runLoop(pollIntervalMs: Long) {
    while (true) {
      try {
        val processed = runOnce()
        if (processed == 0) {
          delay(pollIntervalMs)
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (t: Throwable) {
        logger.error("OutboxDispatcherWorker loop iteration failed", t)
        delay(pollIntervalMs)
      }
    }
  }

  suspend fun runOnce(): Int {
    var totalProcessed = 0
    val claimedJobs = claimPendingPaymentEvents()
    for (event in claimedJobs) {
      when (event.eventType) {
        "PAYMENT_GATEWAY_ORDER_REQUESTED" -> dispatchGatewayOrder(event)
        "REFUND_REQUESTED" -> dispatchRefund(event)
      }
      totalProcessed++
    }
    totalProcessed += dispatchBroadcastEventsOnce()
    return totalProcessed
  }

  private suspend fun claimPendingPaymentEvents(): List<ClaimedOutboxEvent> =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val rows = conn.prepareStatement(
        """
        SELECT id, aggregate_id, aggregate_version, event_type, payload_json::text AS payload_text, attempts
        FROM outbox_events
        WHERE event_type IN ('PAYMENT_GATEWAY_ORDER_REQUESTED', 'REFUND_REQUESTED')
          AND dispatch_status IN ('PENDING', 'PROCESSING')
          AND next_attempt_at <= NOW()
        ORDER BY created_at ASC, id ASC
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """.trimIndent(),
      ).use { ps ->
        ps.setInt(1, batchSize)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<ClaimedOutboxEvent>()
          while (rs.next()) {
            list += ClaimedOutboxEvent(
              id = rs.getObject("id", UUID::class.java),
              aggregateId = rs.getObject("aggregate_id", UUID::class.java),
              aggregateVersion = rs.getLong("aggregate_version"),
              eventType = rs.getString("event_type"),
              payloadJson = rs.getString("payload_text"),
              attempts = rs.getInt("attempts") + 1,
            )
          }
          list
        }
      }

      if (rows.isNotEmpty()) {
        val leaseUntil = Timestamp.from(Instant.now().plusSeconds(30))
        conn.prepareStatement(
          """
          UPDATE outbox_events
          SET dispatch_status = 'PROCESSING', attempts = ?, next_attempt_at = ?
          WHERE id = ?
          """.trimIndent(),
        ).use { ps ->
          for (row in rows) {
            ps.setInt(1, row.attempts)
            ps.setTimestamp(2, leaseUntil)
            ps.setObject(3, row.id)
            ps.addBatch()
          }
          ps.executeBatch()
        }
      }
      rows
    }

  private suspend fun dispatchGatewayOrder(event: ClaimedOutboxEvent) {
    val payload = runCatching { json.parseToJsonElement(event.payloadJson).jsonObject }.getOrElse {
      markDeadLetter(event, null, "Malformed PAYMENT_GATEWAY_ORDER_REQUESTED JSON payload")
      return
    }
    val attemptIdStr = payload["payment_attempt_id"]?.jsonPrimitive?.contentOrNull
    val orderIdStr = payload["order_id"]?.jsonPrimitive?.contentOrNull ?: event.aggregateId.toString()
    val amountPaise = payload["amount_paise"]?.jsonPrimitive?.longOrNull
    val receipt = payload["receipt"]?.jsonPrimitive?.contentOrNull ?: attemptIdStr ?: event.id.toString()
    val attemptUuid = attemptIdStr?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    if (attemptUuid == null || amountPaise == null || amountPaise <= 0L) {
      markDeadLetter(event, null, "Invalid gateway order payload fields")
      return
    }

    val stillInitiating = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement("SELECT status FROM payment_attempts WHERE id = ?").use { ps ->
        ps.setObject(1, attemptUuid)
        ps.executeQuery().use { rs ->
          rs.next() && rs.getString("status") == PaymentAttemptStatus.INITIATING.name
        }
      }
    }
    if (!stillInitiating) {
      markDispatched(event.id)
      return
    }

    // Call Razorpay HTTP API strictly OUTSIDE any PostgreSQL transaction
    val result = try {
      razorpayGateway.createOrder(
        amountPaise = amountPaise,
        currency = "INR",
        receipt = receipt,
        notes = mapOf("order_id" to orderIdStr, "payment_attempt_id" to attemptIdStr),
      )
    } catch (t: Throwable) {
      if (t is CancellationException) throw t
      val transient = (t as? RazorpayGatewayException)?.isTransient ?: true
      if (transient && event.attempts < maxAttempts) {
        scheduleRetry(event, t.message ?: "Gateway order creation failed")
      } else {
        markGatewayOrderDeadLetter(event, attemptUuid, UUID.fromString(orderIdStr), t.message ?: "Gateway order creation permanently failed")
      }
      return
    }

    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val attemptRow = conn.prepareStatement(
        "SELECT id, payment_id, status FROM payment_attempts WHERE id = ? FOR UPDATE",
      ).use { ps ->
        ps.setObject(1, attemptUuid)
        ps.executeQuery().use { rs ->
          if (!rs.next()) null
          else Triple(
            rs.getObject("id", UUID::class.java),
            rs.getObject("payment_id", UUID::class.java),
            PaymentAttemptStatus.valueOf(rs.getString("status")),
          )
        }
      }

      if (attemptRow != null && attemptRow.third == PaymentAttemptStatus.INITIATING) {
        conn.prepareStatement(
          """
          UPDATE payment_attempts
          SET status = 'AWAITING_CUSTOMER_PAYMENT', gateway_order_id = ?, updated_at = NOW()
          WHERE id = ?
          """.trimIndent(),
        ).use { ps ->
          ps.setString(1, result.gatewayOrderId)
          ps.setObject(2, attemptUuid)
          ps.executeUpdate()
        }

        PaymentService.insertPaymentEventIdempotent(
          conn = conn,
          paymentId = attemptRow.second,
          attemptId = attemptUuid,
          source = "OUTBOX_WORKER",
          gatewayEventId = "outbox_order:${result.gatewayOrderId}",
          eventType = "order.created",
          payloadJson = buildJsonObject {
            put("gateway_order_id", result.gatewayOrderId)
            put("receipt", result.receipt)
            put("amount_paise", result.amountPaise)
          }.toString(),
          signatureValid = true,
        )

        insertFollowUpOutboxEvent(
          conn = conn,
          aggregateType = "ORDER",
          aggregateId = UUID.fromString(orderIdStr),
          aggregateVersion = event.aggregateVersion,
          eventType = "PAYMENT_GATEWAY_READY",
          payloadJson = buildJsonObject {
            put("order_id", orderIdStr)
            put("payment_attempt_id", attemptIdStr)
            put("gateway_order_id", result.gatewayOrderId)
          }.toString(),
        )
      }

      markDispatchedInTx(conn, event.id)
    }
  }

  private suspend fun dispatchRefund(event: ClaimedOutboxEvent) {
    val payload = runCatching { json.parseToJsonElement(event.payloadJson).jsonObject }.getOrElse {
      markDeadLetter(event, null, "Malformed REFUND_REQUESTED JSON payload")
      return
    }
    val refundUuid = payload["refund_id"]?.jsonPrimitive?.contentOrNull?.let {
      runCatching { UUID.fromString(it) }.getOrNull()
    }
    if (refundUuid == null) {
      markDeadLetter(event, null, "Missing refund_id in REFUND_REQUESTED payload")
      return
    }

    val prep = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val refund = conn.prepareStatement(
        """
        SELECT r.id, r.payment_id, r.payment_attempt_id, r.order_id, r.idempotency_key,
               r.amount_paise, r.status, o.order_number
        FROM refunds r
        JOIN orders o ON o.id = r.order_id
        WHERE r.id = ?
        FOR UPDATE OF r
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, refundUuid)
        ps.executeQuery().use { rs ->
          if (!rs.next()) null
          else RefundDispatchPrep(
            refundId = rs.getObject("id", UUID::class.java),
            paymentId = rs.getObject("payment_id", UUID::class.java),
            attemptId = rs.getObject("payment_attempt_id", UUID::class.java),
            orderId = rs.getObject("order_id", UUID::class.java),
            orderNumber = rs.getString("order_number"),
            idempotencyKey = rs.getString("idempotency_key"),
            amountPaise = rs.getLong("amount_paise"),
            status = RefundStatus.valueOf(rs.getString("status")),
            gatewayPaymentId = null,
          )
        }
      } ?: return@withJdbcTransaction null

      if (refund.status == RefundStatus.PROCESSED) {
        markDispatchedInTx(conn, event.id)
        return@withJdbcTransaction refund
      }

      val gatewayPaymentId = resolveGatewayPaymentId(conn, refund.paymentId, refund.attemptId)
        ?: payload["gateway_payment_id"]?.jsonPrimitive?.contentOrNull

      if (refund.status == RefundStatus.PENDING || refund.status == RefundStatus.FAILED) {
        conn.prepareStatement(
          "UPDATE refunds SET status = 'PROCESSING', updated_at = NOW() WHERE id = ?",
        ).use { ps ->
          ps.setObject(1, refundUuid)
          ps.executeUpdate()
        }
      }

      refund.copy(gatewayPaymentId = gatewayPaymentId)
    }

    if (prep == null) {
      markDeadLetter(event, null, "Refund $refundUuid not found")
      return
    }
    if (prep.status == RefundStatus.PROCESSED) return

    val gatewayPaymentId = prep.gatewayPaymentId
    if (gatewayPaymentId.isNullOrBlank()) {
      markRefundPermanentFailure(
        event = event,
        prep = prep,
        errorMessage = "No captured gateway_payment_id available for refund ${prep.refundId}",
      )
      return
    }

    // Call Razorpay refund API strictly OUTSIDE any PostgreSQL transaction
    val refundResult = try {
      razorpayGateway.createRefund(
        gatewayPaymentId = gatewayPaymentId,
        amountPaise = prep.amountPaise,
        idempotencyKey = prep.idempotencyKey,
        notes = mapOf(
          "idempotency_key" to prep.idempotencyKey,
          "order_id" to prep.orderId.toString(),
          "refund_id" to prep.refundId.toString(),
        ),
      )
    } catch (t: Throwable) {
      if (t is CancellationException) throw t
      val transient = (t as? RazorpayGatewayException)?.isTransient ?: true
      if (transient && event.attempts < maxAttempts) {
        scheduleRefundRetry(event, prep.refundId, t.message ?: "Transient refund error")
      } else {
        markRefundPermanentFailure(event, prep, t.message ?: "Refund execution failed")
      }
      return
    }

    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        UPDATE refunds
        SET status = 'PROCESSED', gateway_refund_id = COALESCE(gateway_refund_id, ?), updated_at = NOW()
        WHERE id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, refundResult.gatewayRefundId)
        ps.setObject(2, prep.refundId)
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
        ps.setObject(1, prep.paymentId)
        ps.executeQuery().use { rs ->
          rs.next()
          rs.getLong("captured_paise") to rs.getLong("processed_sum")
        }
      }

      val capped = sums.second.coerceAtMost(sums.first)
      val nextPaymentStatus = if (capped >= sums.first && sums.first > 0L) {
        PaymentStatus.REFUNDED
      } else {
        PaymentStatus.PARTIALLY_REFUNDED
      }

      conn.prepareStatement(
        "UPDATE payments SET refunded_paise = ?, status = ?, updated_at = NOW() WHERE id = ?",
      ).use { ps ->
        ps.setLong(1, capped)
        ps.setString(2, nextPaymentStatus.name)
        ps.setObject(3, prep.paymentId)
        ps.executeUpdate()
      }

      PaymentService.insertPaymentEventIdempotent(
        conn = conn,
        paymentId = prep.paymentId,
        attemptId = prep.attemptId,
        source = "OUTBOX_WORKER",
        gatewayEventId = "outbox_refund:${refundResult.gatewayRefundId}",
        eventType = "refund.processed",
        payloadJson = buildJsonObject {
          put("refund_id", prep.refundId.toString())
          put("gateway_refund_id", refundResult.gatewayRefundId)
          put("amount_paise", prep.amountPaise)
        }.toString(),
        signatureValid = true,
      )

      AuditLogger.record(
        conn = conn,
        context = AuditContext(null, "SYSTEM", event.id.toString()),
        action = "REFUND_PROCESSED",
        entityType = "refund",
        entityId = prep.refundId.toString(),
        afterJson = "{\"gateway_refund_id\":\"${refundResult.gatewayRefundId}\",\"amount_paise\":${prep.amountPaise}}",
      )

      markDispatchedInTx(conn, event.id)
    }
  }

  private fun resolveGatewayPaymentId(conn: Connection, paymentId: UUID, attemptId: UUID?): String? {
    if (attemptId != null) {
      val fromAttempt = conn.prepareStatement("SELECT gateway_payment_id FROM payment_attempts WHERE id = ?").use { ps ->
        ps.setObject(1, attemptId)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString("gateway_payment_id") else null }
      }
      if (!fromAttempt.isNullOrBlank()) return fromAttempt
    }
    return conn.prepareStatement(
      """
      SELECT gateway_payment_id
      FROM payment_attempts
      WHERE payment_id = ? AND gateway_payment_id IS NOT NULL
      ORDER BY attempt_number DESC
      LIMIT 1
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, paymentId)
      ps.executeQuery().use { rs -> if (rs.next()) rs.getString("gateway_payment_id") else null }
    }
  }

  private suspend fun scheduleRetry(event: ClaimedOutboxEvent, errorMessage: String) {
    val backoffSec = (2L shl (event.attempts - 1).coerceAtMost(6)).coerceAtMost(300L)
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        UPDATE outbox_events
        SET dispatch_status = 'PENDING',
            next_attempt_at = ?,
            last_error = ?
        WHERE id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setTimestamp(1, Timestamp.from(Instant.now().plusSeconds(backoffSec)))
        ps.setString(2, errorMessage.take(500))
        ps.setObject(3, event.id)
        ps.executeUpdate()
      }
    }
  }

  private suspend fun scheduleRefundRetry(event: ClaimedOutboxEvent, refundId: UUID, errorMessage: String) {
    val backoffSec = (2L shl (event.attempts - 1).coerceAtMost(6)).coerceAtMost(300L)
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        "UPDATE refunds SET status = 'PENDING', updated_at = NOW() WHERE id = ? AND status = 'PROCESSING'",
      ).use { ps ->
        ps.setObject(1, refundId)
        ps.executeUpdate()
      }
      conn.prepareStatement(
        """
        UPDATE outbox_events
        SET dispatch_status = 'PENDING',
            next_attempt_at = ?,
            last_error = ?
        WHERE id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setTimestamp(1, Timestamp.from(Instant.now().plusSeconds(backoffSec)))
        ps.setString(2, errorMessage.take(500))
        ps.setObject(3, event.id)
        ps.executeUpdate()
      }
    }
  }

  private suspend fun markGatewayOrderDeadLetter(
    event: ClaimedOutboxEvent,
    attemptId: UUID,
    orderId: UUID,
    errorMessage: String,
  ) {
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        UPDATE payment_attempts
        SET status = 'FAILED',
            failure_code = 'GATEWAY_ORDER_FAILED',
            failure_reason = ?,
            updated_at = NOW()
        WHERE id = ? AND status = 'INITIATING'
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, errorMessage.take(250))
        ps.setObject(2, attemptId)
        ps.executeUpdate()
      }

      conn.prepareStatement(
        """
        UPDATE outbox_events
        SET dispatch_status = 'DEAD_LETTER', last_error = ?
        WHERE id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, errorMessage.take(500))
        ps.setObject(2, event.id)
        ps.executeUpdate()
      }

      PaymentService.notifyOwners(
        conn = conn,
        eventId = event.id,
        title = "Payment Gateway Init Failed",
        body = "Razorpay order creation failed for order $orderId: ${errorMessage.take(180)}",
        category = "PAYMENT_ALERT",
        referenceId = orderId,
      )
    }
  }

  private suspend fun markRefundPermanentFailure(
    event: ClaimedOutboxEvent,
    prep: RefundDispatchPrep,
    errorMessage: String,
  ) {
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        "UPDATE refunds SET status = 'FAILED', updated_at = NOW() WHERE id = ? AND status IN ('PENDING', 'PROCESSING')",
      ).use { ps ->
        ps.setObject(1, prep.refundId)
        ps.executeUpdate()
      }
      conn.prepareStatement(
        "UPDATE outbox_events SET dispatch_status = 'DEAD_LETTER', last_error = ? WHERE id = ?",
      ).use { ps ->
        ps.setString(1, errorMessage.take(500))
        ps.setObject(2, event.id)
        ps.executeUpdate()
      }
      PaymentService.notifyOwners(
        conn = conn,
        eventId = event.id,
        title = "Refund Failed: ${prep.orderNumber}",
        body = "Refund of ${prep.amountPaise} paise failed after ${event.attempts} attempt(s): ${errorMessage.take(160)}",
        category = "REFUND_ALERT",
        referenceId = prep.orderId,
      )
      AuditLogger.record(
        conn = conn,
        context = AuditContext(null, "SYSTEM", event.id.toString()),
        action = "REFUND_FAILED_REQUIRES_ACTION",
        entityType = "refund",
        entityId = prep.refundId.toString(),
        afterJson = "{\"error\":\"${errorMessage.take(200).replace("\"", "\\\"")}\"}",
      )
    }
  }

  private suspend fun markDispatched(outboxEventId: UUID) {
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      markDispatchedInTx(conn, outboxEventId)
    }
  }

  private suspend fun markDeadLetter(event: ClaimedOutboxEvent, attemptId: UUID?, errorMessage: String) {
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      if (attemptId != null) {
        conn.prepareStatement(
          "UPDATE payment_attempts SET status = 'FAILED', failure_reason = ?, updated_at = NOW() WHERE id = ? AND status = 'INITIATING'",
        ).use { ps ->
          ps.setString(1, errorMessage.take(250))
          ps.setObject(2, attemptId)
          ps.executeUpdate()
        }
      }
      conn.prepareStatement(
        "UPDATE outbox_events SET dispatch_status = 'DEAD_LETTER', last_error = ? WHERE id = ?",
      ).use { ps ->
        ps.setString(1, errorMessage.take(500))
        ps.setObject(2, event.id)
        ps.executeUpdate()
      }
    }
  }

  private fun markDispatchedInTx(conn: Connection, outboxEventId: UUID) {
    conn.prepareStatement(
      """
      UPDATE outbox_events
      SET dispatch_status = 'DISPATCHED', published_at = NOW(), last_error = NULL
      WHERE id = ?
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, outboxEventId)
      ps.executeUpdate()
    }
  }

  private fun insertFollowUpOutboxEvent(
    conn: Connection,
    aggregateType: String,
    aggregateId: UUID,
    aggregateVersion: Long,
    eventType: String,
    payloadJson: String,
  ) {
    conn.prepareStatement(
      """
      INSERT INTO outbox_events (
        id, aggregate_type, aggregate_id, aggregate_version, event_type,
        payload_json, stream_seq, sequenced_at, dispatch_status, published_at
      ) VALUES (?, ?, ?, ?, ?, ?::jsonb, NULL, NULL, 'PENDING', NULL)
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, UUID.randomUUID())
      ps.setString(2, aggregateType)
      ps.setObject(3, aggregateId)
      ps.setLong(4, aggregateVersion)
      ps.setString(5, eventType)
      ps.setString(6, payloadJson)
      ps.executeUpdate()
    }
  }

  suspend fun dispatchBroadcastEventsOnce(): Int {
    val claimed = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val customerCache = mutableMapOf<UUID, UUID?>()
      val rows = conn.prepareStatement(
        """
        SELECT id, aggregate_type, aggregate_id, aggregate_version, event_type,
               payload_json::text AS payload_text, stream_seq, sequenced_at, created_at, attempts
        FROM outbox_events
        WHERE stream_seq IS NOT NULL
          AND event_type NOT IN ('PAYMENT_GATEWAY_ORDER_REQUESTED', 'REFUND_REQUESTED')
          AND dispatch_status IN ('PENDING', 'PROCESSING')
          AND next_attempt_at <= NOW()
        ORDER BY stream_seq ASC
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """.trimIndent(),
      ).use { ps ->
        ps.setInt(1, batchSize)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<ClaimedBroadcastEvent>()
          while (rs.next()) {
            val id = rs.getObject("id", UUID::class.java)
            val aggregateId = rs.getObject("aggregate_id", UUID::class.java)
            val aggregateVersion = rs.getLong("aggregate_version")
            val eventType = rs.getString("event_type")
            val payloadText = rs.getString("payload_text")
            val streamSeq = rs.getLong("stream_seq")
            val mapped = RealtimeEventMapper.mapOutboxRow(
              conn = conn,
              id = id,
              aggregateType = rs.getString("aggregate_type"),
              aggregateId = aggregateId,
              aggregateVersion = aggregateVersion,
              rawEventType = eventType,
              payloadJson = payloadText,
              streamSeq = streamSeq,
              sequencedAt = rs.getObject("sequenced_at", OffsetDateTime::class.java),
              createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
              orderCustomerCache = customerCache,
            )
            list += ClaimedBroadcastEvent(
              base = ClaimedOutboxEvent(
                id = id,
                aggregateId = aggregateId,
                aggregateVersion = aggregateVersion,
                eventType = eventType,
                payloadJson = payloadText,
                attempts = rs.getInt("attempts") + 1,
              ),
              dispatchedEvent = mapped,
            )
          }
          list
        }
      }

      if (rows.isNotEmpty()) {
        val leaseUntil = Timestamp.from(Instant.now().plusSeconds(30))
        conn.prepareStatement(
          """
          UPDATE outbox_events
          SET dispatch_status = 'PROCESSING', attempts = ?, next_attempt_at = ?
          WHERE id = ?
          """.trimIndent(),
        ).use { ps ->
          for (row in rows) {
            ps.setInt(1, row.base.attempts)
            ps.setTimestamp(2, leaseUntil)
            ps.setObject(3, row.base.id)
            ps.addBatch()
          }
          ps.executeBatch()
        }
      }
      rows
    }

    if (claimed.isEmpty()) return 0

    for (entry in claimed) {
      val mapped = entry.dispatchedEvent
      if (mapped == null) {
        markDispatched(entry.base.id)
        continue
      }

      val deliveredLive = try {
        realtimeDispatcher.dispatch(mapped)
      } catch (t: Throwable) {
        if (t is CancellationException) throw t
        val msg = t.message ?: "Realtime broadcast dispatch failed"
        if (entry.base.attempts < maxAttempts) {
          scheduleRetry(entry.base, msg)
        } else {
          markDeadLetter(entry.base, null, msg)
        }
        continue
      }

      val allowedInbox = if (mapped.inboxNotifications.isEmpty()) {
        emptyList()
      } else {
        DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
          mapped.inboxNotifications.filter { notif ->
            NotificationService.isCategoryAllowed(conn, notif.userId, notif.category)
          }
        }
      }

      val deliveredStatuses = mutableListOf<Pair<com.pizzora.backend.realtime.PendingInboxNotification, NotificationDeliveryStatus>>()
      var deliveryFailure: Throwable? = null
      for (notif in allowedInbox) {
        try {
          val deliveryResult = notificationDeliveryProvider.deliver(
            NotificationDispatchRequest(
              userId = notif.userId,
              eventId = notif.eventId,
              title = notif.title,
              body = notif.body,
              category = notif.category,
              referenceId = notif.referenceId,
              deliveredViaWebSocket = deliveredLive,
            ),
          )
          deliveredStatuses += notif to deliveryResult.status
        } catch (t: Throwable) {
          if (t is CancellationException) throw t
          deliveryFailure = t
          break
        }
      }

      if (deliveryFailure != null) {
        val msg = deliveryFailure.message ?: "Notification provider delivery failed"
        val isTransient = (deliveryFailure as? NotificationDeliveryException)?.isTransient ?: true
        if (isTransient && entry.base.attempts < maxAttempts) {
          scheduleRetry(entry.base, msg)
        } else {
          DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
            for (notif in allowedInbox) {
              NotificationService.insertInboxNotificationIfAllowed(
                conn = conn,
                userId = notif.userId,
                eventId = notif.eventId,
                title = notif.title,
                body = notif.body,
                category = notif.category,
                referenceId = notif.referenceId,
                deliveryStatus = NotificationDeliveryStatus.FAILED,
              )
            }
          }
          markDeadLetter(entry.base, null, msg)
        }
        continue
      }

      DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
        for ((notif, deliveryStatus) in deliveredStatuses) {
          NotificationService.insertInboxNotificationIfAllowed(
            conn = conn,
            userId = notif.userId,
            eventId = notif.eventId,
            title = notif.title,
            body = notif.body,
            category = notif.category,
            referenceId = notif.referenceId,
            deliveryStatus = deliveryStatus,
          )
        }
        markDispatchedInTx(conn, entry.base.id)
      }
    }
    return claimed.size
  }

  private data class ClaimedOutboxEvent(
    val id: UUID,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val eventType: String,
    val payloadJson: String,
    val attempts: Int,
  )

  private data class ClaimedBroadcastEvent(
    val base: ClaimedOutboxEvent,
    val dispatchedEvent: DispatchedRealtimeEvent?,
  )

  private data class RefundDispatchPrep(
    val refundId: UUID,
    val paymentId: UUID,
    val attemptId: UUID?,
    val orderId: UUID,
    val orderNumber: String,
    val idempotencyKey: String,
    val amountPaise: Long,
    val status: RefundStatus,
    val gatewayPaymentId: String?,
  )
}

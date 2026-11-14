package com.pizzora.backend.worker

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.inventory.InventoryService
import com.pizzora.backend.orders.OrderService
import java.util.UUID
import javax.sql.DataSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

class ExpirySweepWorker(
  private val dataSource: DataSource,
  private val inventoryService: InventoryService,
  private val couponService: CouponService,
  private val orderService: OrderService,
  private val batchSize: Int = 25,
) {
  private val logger = LoggerFactory.getLogger(ExpirySweepWorker::class.java)

  suspend fun runLoop(sweepIntervalMs: Long) {
    while (true) {
      try {
        runOnce()
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (t: Throwable) {
        logger.error("ExpirySweepWorker loop iteration failed", t)
      }
      delay(sweepIntervalMs)
    }
  }

  suspend fun runOnce(): Int {
    val candidateIds = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        SELECT id
        FROM orders
        WHERE status = 'PENDING_PAYMENT'
          AND expires_at IS NOT NULL
          AND expires_at <= NOW()
        ORDER BY expires_at ASC
        LIMIT ?
        """.trimIndent(),
      ).use { ps ->
        ps.setInt(1, batchSize)
        ps.executeQuery().use { rs ->
          val ids = mutableListOf<UUID>()
          while (rs.next()) ids += rs.getObject("id", UUID::class.java)
          ids
        }
      }
    }

    var expiredCount = 0
    for (orderId in candidateIds) {
      val didExpire = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
        val lockedOrder = conn.prepareStatement(
          """
          SELECT id, customer_id, order_number, version
          FROM orders
          WHERE id = ? AND status = 'PENDING_PAYMENT' AND expires_at IS NOT NULL AND expires_at <= NOW()
          FOR UPDATE SKIP LOCKED
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, orderId)
          ps.executeQuery().use { rs ->
            if (!rs.next()) null
            else Triple(
              rs.getObject("customer_id", UUID::class.java),
              rs.getString("order_number"),
              rs.getLong("version"),
            )
          }
        } ?: return@withJdbcTransaction false

        val paymentId = conn.prepareStatement(
          "SELECT id FROM payments WHERE order_id = ? FOR UPDATE",
        ).use { ps ->
          ps.setObject(1, orderId)
          ps.executeQuery().use { rs -> if (rs.next()) rs.getObject("id", UUID::class.java) else null }
        }

        val newVersion = lockedOrder.third + 1L
        conn.prepareStatement(
          "UPDATE orders SET status = 'EXPIRED', version = ? WHERE id = ?",
        ).use { ps ->
          ps.setLong(1, newVersion)
          ps.setObject(2, orderId)
          ps.executeUpdate()
        }

        conn.prepareStatement(
          """
          INSERT INTO order_status_history (id, order_id, from_status, to_status, changed_by, reason)
          VALUES (?, ?, 'PENDING_PAYMENT', 'EXPIRED', NULL, 'Payment window expired')
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, UUID.randomUUID())
          ps.setObject(2, orderId)
          ps.executeUpdate()
        }

        if (paymentId != null) {
          conn.prepareStatement(
            """
            UPDATE payment_attempts
            SET status = 'EXPIRED', updated_at = NOW()
            WHERE payment_id = ? AND status IN ('INITIATING', 'AWAITING_CUSTOMER_PAYMENT', 'AUTHORIZED')
            """.trimIndent(),
          ).use { ps ->
            ps.setObject(1, paymentId)
            ps.executeUpdate()
          }

          conn.prepareStatement(
            """
            UPDATE payments
            SET status = 'EXPIRED', updated_at = NOW()
            WHERE id = ? AND status = 'UNPAID'
            """.trimIndent(),
          ).use { ps ->
            ps.setObject(1, paymentId)
            ps.executeUpdate()
          }
        }

        inventoryService.releaseReservedInventory(
          conn = conn,
          orderId = orderId,
          expired = true,
          actorUserId = null,
        )
        couponService.releaseReservedCoupon(
          conn = conn,
          orderId = orderId,
          expired = true,
        )

        // Guarded slot release: decrement only when reservation transitions ACTIVE -> EXPIRED
        val releasedSlotId = conn.prepareStatement(
          """
          UPDATE scheduled_slot_reservations
          SET status = 'EXPIRED', expires_at = NULL, updated_at = NOW()
          WHERE order_id = ? AND status = 'ACTIVE'
          RETURNING slot_id
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, orderId)
          ps.executeQuery().use { rs -> if (rs.next()) rs.getObject("slot_id", UUID::class.java) else null }
        }
        if (releasedSlotId != null) {
          conn.prepareStatement(
            "UPDATE scheduled_slots SET reserved_count = GREATEST(0, reserved_count - 1) WHERE id = ?",
          ).use { ps ->
            ps.setObject(1, releasedSlotId)
            ps.executeUpdate()
          }
        }

        conn.prepareStatement(
          """
          UPDATE outbox_events
          SET dispatch_status = 'DISPATCHED',
              published_at = NOW(),
              last_error = 'Order expired prior to gateway dispatch'
          WHERE aggregate_id = ?
            AND event_type = 'PAYMENT_GATEWAY_ORDER_REQUESTED'
            AND dispatch_status IN ('PENDING', 'PROCESSING')
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, orderId)
          ps.executeUpdate()
        }

        orderService.insertOutboxEvent(
          conn = conn,
          aggregateType = "ORDER",
          aggregateId = orderId,
          aggregateVersion = newVersion,
          eventType = "ORDER_EXPIRED",
          payloadJson = buildJsonObject {
            put("order_id", orderId.toString())
            put("order_number", lockedOrder.second)
            put("customer_id", lockedOrder.first.toString())
            put("from_status", "PENDING_PAYMENT")
            put("to_status", "EXPIRED")
          }.toString(),
        )

        AuditLogger.record(
          conn = conn,
          context = AuditContext(null, "SYSTEM", orderId.toString()),
          action = "ORDER_EXPIRED",
          entityType = "order",
          entityId = orderId.toString(),
          beforeJson = "{\"status\":\"PENDING_PAYMENT\",\"version\":${lockedOrder.third}}",
          afterJson = "{\"status\":\"EXPIRED\",\"version\":$newVersion}",
        )
        true
      }
      if (didExpire) expiredCount++
    }
    return expiredCount
  }
}

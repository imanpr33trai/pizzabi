package com.pizzora.backend.orders

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ConflictException
import com.pizzora.backend.core.error.ForbiddenException
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.inventory.InventoryService
import com.pizzora.contracts.DeliveryAddressSnapshotDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderItemDto
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderStateMachine
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderStatusHistoryDto
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentAttemptSummaryDto
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PaymentSummaryDto
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.UserRole
import java.sql.Connection
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OrderService(
  private val dataSource: DataSource,
  private val inventoryService: InventoryService,
  private val couponService: CouponService,
) {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  suspend fun listCustomerOrders(customerId: UUID, limit: Int = 50): OrderListResponse {
    val clampedLimit = limit.coerceIn(1, 100)
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val orders = conn.prepareStatement(
        """
        SELECT o.id, o.order_number, o.fulfillment_type, o.schedule_type, o.status, o.version,
               o.total_paise, o.is_priority, o.created_at, o.expires_at,
               p.method AS payment_method, p.status AS payment_status,
               COALESCE((SELECT SUM(oi.quantity)::int FROM order_items oi WHERE oi.order_id = o.id), 0) AS item_count
        FROM orders o
        JOIN payments p ON p.order_id = o.id
        WHERE o.customer_id = ?
        ORDER BY o.created_at DESC, o.id DESC
        LIMIT ?
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, customerId)
        ps.setInt(2, clampedLimit)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<OrderSummaryDto>()
          while (rs.next()) {
            list += OrderSummaryDto(
              id = rs.getObject("id", UUID::class.java).toString(),
              orderNumber = rs.getString("order_number"),
              fulfillmentType = FulfillmentType.valueOf(rs.getString("fulfillment_type")),
              scheduleType = ScheduleType.valueOf(rs.getString("schedule_type")),
              status = OrderStatus.valueOf(rs.getString("status")),
              version = rs.getLong("version"),
              totalPaise = rs.getLong("total_paise"),
              itemCount = rs.getInt("item_count"),
              paymentMethod = PaymentMethod.valueOf(rs.getString("payment_method")),
              paymentStatus = PaymentStatus.valueOf(rs.getString("payment_status")),
              isPriority = rs.getBoolean("is_priority"),
              createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
              expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java)?.toInstant()?.toString(),
            )
          }
          list
        }
      }
      OrderListResponse(orders = orders)
    }
  }

  suspend fun getOrderDetail(
    orderId: UUID,
    actorUserId: UUID,
    actorRole: UserRole,
  ): OrderDetailDto =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val detail = loadOrderDetail(conn, orderId)
        ?: throw NotFoundException("ORDER_NOT_FOUND", "Order $orderId was not found.")
      if (actorRole == UserRole.CUSTOMER && detail.customerId != actorUserId.toString()) {
        throw ForbiddenException("FORBIDDEN", "You do not have access to this order.")
      }
      detail
    }

  suspend fun cancelOrder(
    orderId: UUID,
    actorUserId: UUID,
    actorRole: UserRole,
    reason: String?,
    audit: AuditContext,
  ): OrderDetailDto {
    val cleanReason = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(250)
    if (actorRole == UserRole.OWNER && cleanReason == null) {
      throw ValidationException("CANCEL_REASON_REQUIRED", "Owner order cancellation requires a reason.")
    }

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val lockedOrder = conn.prepareStatement(
        """
        SELECT id, customer_id, order_number, status, version, fulfillment_type
        FROM orders
        WHERE id = ?
        FOR UPDATE
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, orderId)
        ps.executeQuery().use { rs ->
          if (!rs.next()) {
            throw NotFoundException("ORDER_NOT_FOUND", "Order $orderId was not found.")
          }
          LockedOrderHeader(
            id = rs.getObject("id", UUID::class.java),
            customerId = rs.getObject("customer_id", UUID::class.java),
            orderNumber = rs.getString("order_number"),
            status = OrderStatus.valueOf(rs.getString("status")),
            version = rs.getLong("version"),
            fulfillmentType = FulfillmentType.valueOf(rs.getString("fulfillment_type")),
          )
        }
      }

      if (actorRole == UserRole.CUSTOMER && lockedOrder.customerId != actorUserId) {
        throw ForbiddenException("FORBIDDEN", "You cannot cancel another customer's order.")
      }

      if (!OrderStateMachine.canCancel(lockedOrder.status, actorRole)) {
        throw ConflictException(
          "INVALID_ORDER_TRANSITION",
          "Order ${lockedOrder.orderNumber} in status ${lockedOrder.status} cannot be cancelled by $actorRole.",
        )
      }

      val newVersion = lockedOrder.version + 1L
      conn.prepareStatement(
        "UPDATE orders SET status = 'CANCELLED', version = ? WHERE id = ?",
      ).use { ps ->
        ps.setLong(1, newVersion)
        ps.setObject(2, orderId)
        ps.executeUpdate()
      }

      val effectiveReason = cleanReason ?: "Cancelled by ${actorRole.name.lowercase()}"
      conn.prepareStatement(
        """
        INSERT INTO order_status_history (id, order_id, from_status, to_status, changed_by, reason)
        VALUES (?, ?, ?, 'CANCELLED', ?, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setObject(2, orderId)
        ps.setString(3, lockedOrder.status.name)
        ps.setObject(4, actorUserId)
        ps.setString(5, effectiveReason)
        ps.executeUpdate()
      }

      when (lockedOrder.status) {
        OrderStatus.PENDING_PAYMENT -> {
          inventoryService.releaseReservedInventory(
            conn = conn,
            orderId = orderId,
            expired = false,
            actorUserId = actorUserId,
          )
          couponService.releaseReservedCoupon(conn = conn, orderId = orderId, expired = false)
        }
        OrderStatus.PLACED, OrderStatus.ACCEPTED -> {
          inventoryService.restoreCommittedInventory(
            conn = conn,
            orderId = orderId,
            actorUserId = actorUserId,
          )
        }
        else -> Unit
      }

      releaseScheduledSlotIfReserved(conn, orderId)

      handlePaymentOnCancel(
        conn = conn,
        orderId = orderId,
        orderVersion = newVersion,
        actorUserId = actorUserId,
        reason = effectiveReason,
      )

      val cancelledPayload = buildJsonObject {
        put("order_id", orderId.toString())
        put("order_number", lockedOrder.orderNumber)
        put("customer_id", lockedOrder.customerId.toString())
        put("from_status", lockedOrder.status.name)
        put("to_status", OrderStatus.CANCELLED.name)
        put("reason", effectiveReason)
      }.toString()

      insertOutboxEvent(
        conn = conn,
        aggregateType = "ORDER",
        aggregateId = orderId,
        aggregateVersion = newVersion,
        eventType = "ORDER_CANCELLED",
        payloadJson = cancelledPayload,
      )

      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "ORDER_CANCELLED",
        entityType = "order",
        entityId = orderId.toString(),
        beforeJson = "{\"status\":\"${lockedOrder.status.name}\",\"version\":${lockedOrder.version}}",
        afterJson = "{\"status\":\"CANCELLED\",\"version\":$newVersion,\"reason\":\"${effectiveReason.replace("\"", "\\\"")}\"}",
      )

      loadOrderDetail(conn, orderId)!!
    }
  }

  suspend fun transitionOrderStatus(
    orderId: UUID,
    toStatus: OrderStatus,
    actorUserId: UUID,
    actorRole: UserRole,
    reason: String? = null,
    audit: AuditContext,
    outboxEventType: String = "ORDER_STATUS_CHANGED",
  ): OrderDetailDto {
    if (toStatus == OrderStatus.CANCELLED) {
      return cancelOrder(
        orderId = orderId,
        actorUserId = actorUserId,
        actorRole = actorRole,
        reason = reason,
        audit = audit,
      )
    }

    val cleanReason = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(250)
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val lockedOrder = conn.prepareStatement(
        """
        SELECT id, customer_id, order_number, status, version, fulfillment_type
        FROM orders
        WHERE id = ?
        FOR UPDATE
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, orderId)
        ps.executeQuery().use { rs ->
          if (!rs.next()) {
            throw NotFoundException("ORDER_NOT_FOUND", "Order $orderId was not found.")
          }
          LockedOrderHeader(
            id = rs.getObject("id", UUID::class.java),
            customerId = rs.getObject("customer_id", UUID::class.java),
            orderNumber = rs.getString("order_number"),
            status = OrderStatus.valueOf(rs.getString("status")),
            version = rs.getLong("version"),
            fulfillmentType = FulfillmentType.valueOf(rs.getString("fulfillment_type")),
          )
        }
      }

      if (!OrderStateMachine.canTransition(lockedOrder.status, toStatus, lockedOrder.fulfillmentType, actorRole)) {
        throw ConflictException(
          "INVALID_ORDER_TRANSITION",
          "Order ${lockedOrder.orderNumber} cannot transition from ${lockedOrder.status} to $toStatus (${lockedOrder.fulfillmentType}) by $actorRole.",
        )
      }

      val newVersion = lockedOrder.version + 1L
      val timestampSetClause = when (toStatus) {
        OrderStatus.ACCEPTED -> ", accepted_at = COALESCE(accepted_at, NOW())"
        OrderStatus.PREPARING -> ", prep_started_at = COALESCE(prep_started_at, NOW())"
        OrderStatus.READY -> ", ready_at = COALESCE(ready_at, NOW())"
        OrderStatus.COMPLETED -> ", completed_at = COALESCE(completed_at, NOW())"
        else -> ""
      }

      conn.prepareStatement(
        "UPDATE orders SET status = ?, version = ?$timestampSetClause WHERE id = ?",
      ).use { ps ->
        ps.setString(1, toStatus.name)
        ps.setLong(2, newVersion)
        ps.setObject(3, orderId)
        ps.executeUpdate()
      }

      if (toStatus == OrderStatus.COMPLETED) {
        val paymentRow = conn.prepareStatement(
          "SELECT id, method, status, amount_paise FROM payments WHERE order_id = ? FOR UPDATE",
        ).use { ps ->
          ps.setObject(1, orderId)
          ps.executeQuery().use { rs ->
            if (!rs.next()) null
            else Triple(
              rs.getObject("id", UUID::class.java),
              PaymentMethod.valueOf(rs.getString("method")),
              PaymentStatus.valueOf(rs.getString("status")) to rs.getLong("amount_paise"),
            )
          }
        }
        if (paymentRow != null && paymentRow.second == PaymentMethod.COD && paymentRow.third.first == PaymentStatus.COD_PENDING) {
          conn.prepareStatement(
            "UPDATE payments SET status = 'COD_COLLECTED', captured_paise = ?, updated_at = NOW() WHERE id = ?",
          ).use { ps ->
            ps.setLong(1, paymentRow.third.second)
            ps.setObject(2, paymentRow.first)
            ps.executeUpdate()
          }
          conn.prepareStatement(
            """
            UPDATE payment_attempts
            SET status = 'CAPTURED', collected_by_user_id = ?, updated_at = NOW()
            WHERE payment_id = ? AND status = 'AWAITING_CUSTOMER_PAYMENT'
            """.trimIndent(),
          ).use { ps ->
            ps.setObject(1, actorUserId)
            ps.setObject(2, paymentRow.first)
            ps.executeUpdate()
          }
        }
      }

      conn.prepareStatement(
        """
        INSERT INTO order_status_history (id, order_id, from_status, to_status, changed_by, reason)
        VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setObject(2, orderId)
        ps.setString(3, lockedOrder.status.name)
        ps.setString(4, toStatus.name)
        ps.setObject(5, actorUserId)
        if (cleanReason != null) ps.setString(6, cleanReason) else ps.setNull(6, Types.VARCHAR)
        ps.executeUpdate()
      }

      val detail = loadOrderDetail(conn, orderId)!!
      val statusPayload = buildJsonObject {
        put("order_id", orderId.toString())
        put("order_number", lockedOrder.orderNumber)
        put("customer_id", lockedOrder.customerId.toString())
        put("fulfillment_type", lockedOrder.fulfillmentType.name)
        put("from_status", lockedOrder.status.name)
        put("to_status", toStatus.name)
        put("status", toStatus.name)
        put("is_priority", detail.isPriority)
        put("changed_by", actorUserId.toString())
        if (cleanReason != null) put("reason", cleanReason)
        detail.acceptedAt?.let { put("accepted_at", it) }
        detail.prepStartedAt?.let { put("prep_started_at", it) }
        detail.readyAt?.let { put("ready_at", it) }
        detail.completedAt?.let { put("completed_at", it) }
      }.toString()

      insertOutboxEvent(
        conn = conn,
        aggregateType = "ORDER",
        aggregateId = orderId,
        aggregateVersion = newVersion,
        eventType = outboxEventType,
        payloadJson = statusPayload,
      )

      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "ORDER_STATUS_CHANGED",
        entityType = "order",
        entityId = orderId.toString(),
        beforeJson = "{\"status\":\"${lockedOrder.status.name}\",\"version\":${lockedOrder.version}}",
        afterJson = "{\"status\":\"${toStatus.name}\",\"version\":$newVersion}",
      )

      detail
    }
  }

  private fun releaseScheduledSlotIfReserved(conn: Connection, orderId: UUID) {
    val slotRes = conn.prepareStatement(
      """
      SELECT id, slot_id
      FROM scheduled_slot_reservations
      WHERE order_id = ? AND status IN ('ACTIVE', 'COMMITTED')
      FOR UPDATE
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, orderId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) null
        else rs.getObject("id", UUID::class.java) to rs.getObject("slot_id", UUID::class.java)
      }
    } ?: return

    val updated = conn.prepareStatement(
      """
      UPDATE scheduled_slot_reservations
      SET status = 'RELEASED', expires_at = NULL, updated_at = NOW()
      WHERE id = ? AND status IN ('ACTIVE', 'COMMITTED')
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, slotRes.first)
      ps.executeUpdate()
    }

    if (updated > 0) {
      conn.prepareStatement(
        "UPDATE scheduled_slots SET reserved_count = GREATEST(0, reserved_count - 1) WHERE id = ?",
      ).use { ps ->
        ps.setObject(1, slotRes.second)
        ps.executeUpdate()
      }
    }
  }

  private fun handlePaymentOnCancel(
    conn: Connection,
    orderId: UUID,
    orderVersion: Long,
    actorUserId: UUID,
    reason: String,
  ) {
    val paymentRow = conn.prepareStatement(
      """
      SELECT id, method, status, captured_paise
      FROM payments
      WHERE order_id = ?
      FOR UPDATE
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, orderId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) null
        else LockedPaymentRow(
          id = rs.getObject("id", UUID::class.java),
          method = PaymentMethod.valueOf(rs.getString("method")),
          status = PaymentStatus.valueOf(rs.getString("status")),
          capturedPaise = rs.getLong("captured_paise"),
        )
      }
    } ?: return

    conn.prepareStatement(
      """
      UPDATE payment_attempts
      SET status = 'CANCELLED', updated_at = NOW()
      WHERE payment_id = ? AND status IN ('INITIATING', 'AWAITING_CUSTOMER_PAYMENT')
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, paymentRow.id)
      ps.executeUpdate()
    }

    when (paymentRow.status) {
      PaymentStatus.UNPAID, PaymentStatus.COD_PENDING -> {
        conn.prepareStatement(
          "UPDATE payments SET status = 'CANCELLED', updated_at = NOW() WHERE id = ?",
        ).use { ps ->
          ps.setObject(1, paymentRow.id)
          ps.executeUpdate()
        }
      }
      PaymentStatus.CAPTURED -> {
        conn.prepareStatement(
          "UPDATE payments SET status = 'REFUND_PENDING', updated_at = NOW() WHERE id = ?",
        ).use { ps ->
          ps.setObject(1, paymentRow.id)
          ps.executeUpdate()
        }
        val refundAmount = paymentRow.capturedPaise
        if (refundAmount > 0L) {
          val refundId = UUID.randomUUID()
          val idempotencyKey = "order_cancel:$orderId"
          val insertedRefundId = conn.prepareStatement(
            """
            INSERT INTO refunds (
              id, payment_id, order_id, idempotency_key, amount_paise, status, reason, initiated_by
            ) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            RETURNING id
            """.trimIndent(),
          ).use { ps ->
            ps.setObject(1, refundId)
            ps.setObject(2, paymentRow.id)
            ps.setObject(3, orderId)
            ps.setString(4, idempotencyKey)
            ps.setLong(5, refundAmount)
            ps.setString(6, reason)
            ps.setObject(7, actorUserId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getObject("id", UUID::class.java) else null }
          }
          if (insertedRefundId != null) {
            val refundPayload = buildJsonObject {
              put("refund_id", insertedRefundId.toString())
              put("order_id", orderId.toString())
              put("payment_id", paymentRow.id.toString())
              put("amount_paise", refundAmount)
              put("idempotency_key", idempotencyKey)
            }.toString()
            insertOutboxEvent(
              conn = conn,
              aggregateType = "ORDER",
              aggregateId = orderId,
              aggregateVersion = orderVersion,
              eventType = "REFUND_REQUESTED",
              payloadJson = refundPayload,
            )
          }
        }
      }
      else -> Unit
    }
  }

  fun insertOutboxEvent(
    conn: Connection,
    aggregateType: String,
    aggregateId: UUID,
    aggregateVersion: Long,
    eventType: String,
    payloadJson: String,
  ): UUID {
    val eventId = UUID.randomUUID()
    conn.prepareStatement(
      """
      INSERT INTO outbox_events (
        id, aggregate_type, aggregate_id, aggregate_version, event_type,
        payload_json, stream_seq, sequenced_at, dispatch_status, published_at
      ) VALUES (?, ?, ?, ?, ?, ?::jsonb, NULL, NULL, 'PENDING', NULL)
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, eventId)
      ps.setString(2, aggregateType)
      ps.setObject(3, aggregateId)
      ps.setLong(4, aggregateVersion)
      ps.setString(5, eventType)
      ps.setString(6, payloadJson)
      ps.executeUpdate()
    }
    return eventId
  }

  fun loadOrderDetail(conn: Connection, orderId: UUID): OrderDetailDto? {
    val header = conn.prepareStatement(
      """
      SELECT id, order_number, customer_id, fulfillment_type, schedule_type, scheduled_slot_id,
             delivery_address_snapshot::text AS delivery_address_json, status, version,
             subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise,
             gst_bps_snapshot, coupon_code_snapshot, is_priority,
             accepted_at, prep_started_at, ready_at, completed_at, expires_at, created_at
      FROM orders
      WHERE id = ?
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, orderId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) null
        else {
          val taxPaise = rs.getLong("tax_paise")
          val cgstPaise = taxPaise / 2L
          val sgstPaise = taxPaise - cgstPaise
          val addressJson = rs.getString("delivery_address_json")
          val addressDto = addressJson?.let { raw ->
            runCatching { json.decodeFromString<DeliveryAddressSnapshotDto>(raw) }.getOrNull()
          }
          OrderDetailHeader(
            id = rs.getObject("id", UUID::class.java).toString(),
            orderNumber = rs.getString("order_number"),
            customerId = rs.getObject("customer_id", UUID::class.java).toString(),
            fulfillmentType = FulfillmentType.valueOf(rs.getString("fulfillment_type")),
            scheduleType = ScheduleType.valueOf(rs.getString("schedule_type")),
            scheduledSlotId = rs.getObject("scheduled_slot_id", UUID::class.java)?.toString(),
            deliveryAddress = addressDto,
            status = OrderStatus.valueOf(rs.getString("status")),
            version = rs.getLong("version"),
            subtotalPaise = rs.getLong("subtotal_paise"),
            discountPaise = rs.getLong("discount_paise"),
            deliveryFeePaise = rs.getLong("delivery_fee_paise"),
            taxPaise = taxPaise,
            cgstPaise = cgstPaise,
            sgstPaise = sgstPaise,
            totalPaise = rs.getLong("total_paise"),
            gstBpsSnapshot = rs.getInt("gst_bps_snapshot"),
            couponCodeSnapshot = rs.getString("coupon_code_snapshot"),
            isPriority = rs.getBoolean("is_priority"),
            acceptedAt = rs.getObject("accepted_at", OffsetDateTime::class.java)?.toInstant()?.toString(),
            prepStartedAt = rs.getObject("prep_started_at", OffsetDateTime::class.java)?.toInstant()?.toString(),
            readyAt = rs.getObject("ready_at", OffsetDateTime::class.java)?.toInstant()?.toString(),
            completedAt = rs.getObject("completed_at", OffsetDateTime::class.java)?.toInstant()?.toString(),
            expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java)?.toInstant()?.toString(),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
          )
        }
      }
    } ?: return null

    val items = conn.prepareStatement(
      """
      SELECT id, product_id, product_name_snapshot, variant_size_snapshot, crust_name_snapshot,
             toppings_snapshot::text AS toppings_json, unit_price_paise, quantity, line_total_paise
      FROM order_items
      WHERE order_id = ?
      ORDER BY id ASC
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, orderId)
      ps.executeQuery().use { rs ->
        val list = mutableListOf<OrderItemDto>()
        while (rs.next()) {
          val toppingsJson = rs.getString("toppings_json") ?: "[]"
          val toppingsList = runCatching { json.decodeFromString<List<String>>(toppingsJson) }.getOrDefault(emptyList())
          list += OrderItemDto(
            id = rs.getObject("id", UUID::class.java).toString(),
            productId = rs.getObject("product_id", UUID::class.java).toString(),
            productName = rs.getString("product_name_snapshot"),
            variantSize = rs.getString("variant_size_snapshot"),
            crustName = rs.getString("crust_name_snapshot"),
            toppings = toppingsList,
            unitPricePaise = rs.getLong("unit_price_paise"),
            quantity = rs.getInt("quantity"),
            lineTotalPaise = rs.getLong("line_total_paise"),
          )
        }
        list
      }
    }

    val history = conn.prepareStatement(
      """
      SELECT id, from_status, to_status, changed_by, reason, created_at
      FROM order_status_history
      WHERE order_id = ?
      ORDER BY created_at ASC, id ASC
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, orderId)
      ps.executeQuery().use { rs ->
        val list = mutableListOf<OrderStatusHistoryDto>()
        while (rs.next()) {
          val fromRaw = rs.getString("from_status")
          list += OrderStatusHistoryDto(
            id = rs.getObject("id", UUID::class.java).toString(),
            fromStatus = fromRaw?.let { OrderStatus.valueOf(it) },
            toStatus = OrderStatus.valueOf(rs.getString("to_status")),
            changedBy = rs.getObject("changed_by", UUID::class.java)?.toString(),
            reason = rs.getString("reason"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
          )
        }
        list
      }
    }

    val payment = conn.prepareStatement(
      """
      SELECT id, method, status, amount_paise, captured_paise, refunded_paise, currency
      FROM payments
      WHERE order_id = ?
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, orderId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) null
        else {
          val paymentId = rs.getObject("id", UUID::class.java)
          val latestAttempt = loadLatestAttempt(conn, paymentId)
          PaymentSummaryDto(
            id = paymentId.toString(),
            method = PaymentMethod.valueOf(rs.getString("method")),
            status = PaymentStatus.valueOf(rs.getString("status")),
            amountPaise = rs.getLong("amount_paise"),
            capturedPaise = rs.getLong("captured_paise"),
            refundedPaise = rs.getLong("refunded_paise"),
            currency = rs.getString("currency"),
            latestAttempt = latestAttempt,
          )
        }
      }
    } ?: error("Missing payments row for order $orderId")

    return OrderDetailDto(
      id = header.id,
      orderNumber = header.orderNumber,
      customerId = header.customerId,
      fulfillmentType = header.fulfillmentType,
      scheduleType = header.scheduleType,
      scheduledSlotId = header.scheduledSlotId,
      deliveryAddress = header.deliveryAddress,
      status = header.status,
      version = header.version,
      subtotalPaise = header.subtotalPaise,
      discountPaise = header.discountPaise,
      deliveryFeePaise = header.deliveryFeePaise,
      taxPaise = header.taxPaise,
      cgstPaise = header.cgstPaise,
      sgstPaise = header.sgstPaise,
      totalPaise = header.totalPaise,
      gstBpsSnapshot = header.gstBpsSnapshot,
      couponCodeSnapshot = header.couponCodeSnapshot,
      isPriority = header.isPriority,
      acceptedAt = header.acceptedAt,
      prepStartedAt = header.prepStartedAt,
      readyAt = header.readyAt,
      completedAt = header.completedAt,
      expiresAt = header.expiresAt,
      createdAt = header.createdAt,
      items = items,
      statusHistory = history,
      payment = payment,
    )
  }

  private fun loadLatestAttempt(conn: Connection, paymentId: UUID): PaymentAttemptSummaryDto? =
    conn.prepareStatement(
      """
      SELECT id, attempt_number, method, status, amount_paise, idempotency_receipt,
             gateway_order_id, gateway_payment_id, failure_code, failure_reason, expires_at, created_at
      FROM payment_attempts
      WHERE payment_id = ?
      ORDER BY attempt_number DESC
      LIMIT 1
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, paymentId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) null
        else PaymentAttemptSummaryDto(
          id = rs.getObject("id", UUID::class.java).toString(),
          attemptNumber = rs.getInt("attempt_number"),
          method = PaymentMethod.valueOf(rs.getString("method")),
          status = PaymentAttemptStatus.valueOf(rs.getString("status")),
          amountPaise = rs.getLong("amount_paise"),
          idempotencyReceipt = rs.getString("idempotency_receipt"),
          gatewayOrderId = rs.getString("gateway_order_id"),
          gatewayPaymentId = rs.getString("gateway_payment_id"),
          failureCode = rs.getString("failure_code"),
          failureReason = rs.getString("failure_reason"),
          expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java)?.toInstant()?.toString(),
          createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
        )
      }
    }

  private data class LockedOrderHeader(
    val id: UUID,
    val customerId: UUID,
    val orderNumber: String,
    val status: OrderStatus,
    val version: Long,
    val fulfillmentType: FulfillmentType,
  )

  private data class LockedPaymentRow(
    val id: UUID,
    val method: PaymentMethod,
    val status: PaymentStatus,
    val capturedPaise: Long,
  )

  private data class OrderDetailHeader(
    val id: String,
    val orderNumber: String,
    val customerId: String,
    val fulfillmentType: FulfillmentType,
    val scheduleType: ScheduleType,
    val scheduledSlotId: String?,
    val deliveryAddress: DeliveryAddressSnapshotDto?,
    val status: OrderStatus,
    val version: Long,
    val subtotalPaise: Long,
    val discountPaise: Long,
    val deliveryFeePaise: Long,
    val taxPaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val totalPaise: Long,
    val gstBpsSnapshot: Int,
    val couponCodeSnapshot: String?,
    val isPriority: Boolean,
    val acceptedAt: String?,
    val prepStartedAt: String?,
    val readyAt: String?,
    val completedAt: String?,
    val expiresAt: String?,
    val createdAt: String,
  )
}

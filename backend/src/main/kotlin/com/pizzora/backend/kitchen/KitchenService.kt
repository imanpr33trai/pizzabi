package com.pizzora.backend.kitchen

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ConflictException
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.orders.OrderService
import com.pizzora.contracts.DeliveryAddressSnapshotDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.KdsTicketActionResponse
import com.pizzora.contracts.KdsTicketDto
import com.pizzora.contracts.KdsTicketItemDto
import com.pizzora.contracts.KdsTicketsResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.UserRole
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KitchenService(
  private val dataSource: DataSource,
  private val orderService: OrderService,
) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun listTickets(
    includeCompleted: Boolean = false,
    statusFilter: OrderStatus? = null,
    limit: Int = 100,
  ): KdsTicketsResponse = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
    val prepSlaMinutes = loadDefaultPrepMins(conn)
    val safeLimit = limit.coerceIn(1, 200)

    val statuses = when {
      statusFilter != null -> listOf(statusFilter.name)
      includeCompleted -> listOf(
        OrderStatus.PLACED.name,
        OrderStatus.ACCEPTED.name,
        OrderStatus.PREPARING.name,
        OrderStatus.READY.name,
        OrderStatus.OUT_FOR_DELIVERY.name,
        OrderStatus.COMPLETED.name,
      )
      else -> listOf(
        OrderStatus.PLACED.name,
        OrderStatus.ACCEPTED.name,
        OrderStatus.PREPARING.name,
        OrderStatus.READY.name,
        OrderStatus.OUT_FOR_DELIVERY.name,
      )
    }

    val headers = loadTicketHeaders(
      conn = conn,
      statuses = statuses,
      includeRecentCompletedOnly = includeCompleted && statusFilter == null,
      limit = safeLimit,
    )
    val itemsByOrder = loadTicketItemsMap(conn, headers.map { it.uuid })
    val tickets = headers.map { it.toDto(itemsByOrder[it.uuid].orEmpty()) }
    val serverNow = Instant.now().toString()
    KdsTicketsResponse(
      tickets = tickets,
      serverNow = serverNow,
      prepSlaMinutes = prepSlaMinutes,
      priorityCount = tickets.count { it.isPriority && !it.status.isTerminal },
    )
  }

  suspend fun transitionTicket(
    orderId: UUID,
    toStatus: OrderStatus,
    actorUserId: UUID,
    actorRole: UserRole,
    reason: String? = null,
    audit: AuditContext,
    outboxEventType: String = "ORDER_STATUS_CHANGED",
  ): KdsTicketActionResponse {
    orderService.transitionOrderStatus(
      orderId = orderId,
      toStatus = toStatus,
      actorUserId = actorUserId,
      actorRole = actorRole,
      reason = reason,
      audit = audit,
      outboxEventType = outboxEventType,
    )
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val ticket = loadSingleTicket(conn, orderId)
        ?: throw NotFoundException("ORDER_NOT_FOUND", "Order $orderId was not found.")
      KdsTicketActionResponse(
        ticket = ticket,
        serverNow = Instant.now().toString(),
        prepSlaMinutes = loadDefaultPrepMins(conn),
      )
    }
  }

  suspend fun updatePriority(
    orderId: UUID,
    isPriority: Boolean,
    actorUserId: UUID,
    reason: String? = null,
    audit: AuditContext,
  ): KdsTicketActionResponse = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
    val locked = conn.prepareStatement(
      """
      SELECT id, customer_id, order_number, status, version, is_priority
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
        LockedPriorityRow(
          id = rs.getObject("id", UUID::class.java),
          customerId = rs.getObject("customer_id", UUID::class.java),
          orderNumber = rs.getString("order_number"),
          status = OrderStatus.valueOf(rs.getString("status")),
          version = rs.getLong("version"),
          isPriority = rs.getBoolean("is_priority"),
        )
      }
    }

    if (locked.status.isTerminal || locked.status == OrderStatus.PENDING_PAYMENT) {
      throw ConflictException(
        "INVALID_PRIORITY_STATE",
        "Cannot change priority for order ${locked.orderNumber} while in status ${locked.status}.",
      )
    }

    if (locked.isPriority != isPriority) {
      val newVersion = locked.version + 1L
      conn.prepareStatement(
        "UPDATE orders SET is_priority = ?, version = ? WHERE id = ?",
      ).use { ps ->
        ps.setBoolean(1, isPriority)
        ps.setLong(2, newVersion)
        ps.setObject(3, orderId)
        ps.executeUpdate()
      }

      val cleanReason = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(250)
      val payload = buildJsonObject {
        put("order_id", orderId.toString())
        put("order_number", locked.orderNumber)
        put("customer_id", locked.customerId.toString())
        put("status", locked.status.name)
        put("is_priority", isPriority)
        put("changed_by", actorUserId.toString())
        if (cleanReason != null) put("reason", cleanReason)
      }.toString()

      orderService.insertOutboxEvent(
        conn = conn,
        aggregateType = "ORDER",
        aggregateId = orderId,
        aggregateVersion = newVersion,
        eventType = "ORDER_PRIORITY_CHANGED",
        payloadJson = payload,
      )

      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "ORDER_PRIORITY_UPDATED",
        entityType = "order",
        entityId = orderId.toString(),
        beforeJson = "{\"is_priority\":${locked.isPriority},\"version\":${locked.version}}",
        afterJson = "{\"is_priority\":$isPriority,\"version\":$newVersion}",
      )
    }

    val ticket = loadSingleTicket(conn, orderId)
      ?: throw NotFoundException("ORDER_NOT_FOUND", "Order $orderId was not found.")
    KdsTicketActionResponse(
      ticket = ticket,
      serverNow = Instant.now().toString(),
      prepSlaMinutes = loadDefaultPrepMins(conn),
    )
  }

  private fun loadSingleTicket(conn: Connection, orderId: UUID): KdsTicketDto? {
    val header = conn.prepareStatement(
      """
      SELECT o.id, o.order_number, u.full_name AS customer_name, u.phone AS customer_phone,
             o.fulfillment_type, o.schedule_type, o.status, o.version, o.is_priority,
             p.method AS payment_method, p.status AS payment_status, o.total_paise,
             o.delivery_address_snapshot::text AS delivery_address_json,
             o.accepted_at, o.prep_started_at, o.ready_at, o.completed_at, o.created_at
      FROM orders o
      JOIN users u ON u.id = o.customer_id
      JOIN payments p ON p.order_id = o.id
      WHERE o.id = ?
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, orderId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) null else readTicketHeader(rs)
      }
    } ?: return null

    val items = loadTicketItemsMap(conn, listOf(orderId))[orderId].orEmpty()
    return header.toDto(items)
  }

  private fun loadTicketHeaders(
    conn: Connection,
    statuses: List<String>,
    includeRecentCompletedOnly: Boolean,
    limit: Int,
  ): List<TicketHeaderRow> {
    val statusArray = conn.createArrayOf("text", statuses.toTypedArray())
    val recentCompletedClause = if (includeRecentCompletedOnly) {
      "AND (o.status <> 'COMPLETED' OR o.completed_at >= NOW() - INTERVAL '2 hours')"
    } else {
      ""
    }
    return conn.prepareStatement(
      """
      SELECT o.id, o.order_number, u.full_name AS customer_name, u.phone AS customer_phone,
             o.fulfillment_type, o.schedule_type, o.status, o.version, o.is_priority,
             p.method AS payment_method, p.status AS payment_status, o.total_paise,
             o.delivery_address_snapshot::text AS delivery_address_json,
             o.accepted_at, o.prep_started_at, o.ready_at, o.completed_at, o.created_at
      FROM orders o
      JOIN users u ON u.id = o.customer_id
      JOIN payments p ON p.order_id = o.id
      WHERE o.status = ANY(?)
        $recentCompletedClause
      ORDER BY CASE WHEN o.status = 'COMPLETED' THEN 1 ELSE 0 END ASC,
               o.is_priority DESC,
               o.created_at ASC,
               o.id ASC
      LIMIT ?
      """.trimIndent(),
    ).use { ps ->
      ps.setArray(1, statusArray)
      ps.setInt(2, limit)
      ps.executeQuery().use { rs ->
        val list = mutableListOf<TicketHeaderRow>()
        while (rs.next()) {
          list += readTicketHeader(rs)
        }
        list
      }
    }
  }

  private fun readTicketHeader(rs: java.sql.ResultSet): TicketHeaderRow {
    val addressJson = rs.getString("delivery_address_json")
    val deliveryArea = addressJson?.let { raw ->
      runCatching { json.decodeFromString<DeliveryAddressSnapshotDto>(raw) }.getOrNull()?.let { addr ->
        listOfNotNull(
          addr.line1.takeIf { it.isNotBlank() },
          addr.pincode.takeIf { it.isNotBlank() },
        ).joinToString(" • ").takeIf { it.isNotBlank() }
      }
    }
    return TicketHeaderRow(
      uuid = rs.getObject("id", UUID::class.java),
      orderNumber = rs.getString("order_number"),
      customerName = rs.getString("customer_name"),
      customerPhone = rs.getString("customer_phone"),
      fulfillmentType = FulfillmentType.valueOf(rs.getString("fulfillment_type")),
      scheduleType = ScheduleType.valueOf(rs.getString("schedule_type")),
      status = OrderStatus.valueOf(rs.getString("status")),
      version = rs.getLong("version"),
      isPriority = rs.getBoolean("is_priority"),
      paymentMethod = PaymentMethod.valueOf(rs.getString("payment_method")),
      paymentStatus = PaymentStatus.valueOf(rs.getString("payment_status")),
      totalPaise = rs.getLong("total_paise"),
      deliveryArea = deliveryArea,
      acceptedAt = rs.getObject("accepted_at", OffsetDateTime::class.java)?.toInstant()?.toString(),
      prepStartedAt = rs.getObject("prep_started_at", OffsetDateTime::class.java)?.toInstant()?.toString(),
      readyAt = rs.getObject("ready_at", OffsetDateTime::class.java)?.toInstant()?.toString(),
      completedAt = rs.getObject("completed_at", OffsetDateTime::class.java)?.toInstant()?.toString(),
      createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
    )
  }

  private fun loadTicketItemsMap(conn: Connection, orderIds: List<UUID>): Map<UUID, List<KdsTicketItemDto>> {
    if (orderIds.isEmpty()) return emptyMap()
    val uuidArray = conn.createArrayOf("uuid", orderIds.toTypedArray())
    return conn.prepareStatement(
      """
      SELECT id, order_id, product_id, product_name_snapshot, variant_size_snapshot,
             crust_name_snapshot, toppings_snapshot::text AS toppings_json, quantity
      FROM order_items
      WHERE order_id = ANY(?)
      ORDER BY order_id ASC, id ASC
      """.trimIndent(),
    ).use { ps ->
      ps.setArray(1, uuidArray)
      ps.executeQuery().use { rs ->
        val map = LinkedHashMap<UUID, MutableList<KdsTicketItemDto>>()
        while (rs.next()) {
          val orderId = rs.getObject("order_id", UUID::class.java)
          val toppingsJson = rs.getString("toppings_json") ?: "[]"
          val toppings = runCatching { json.decodeFromString<List<String>>(toppingsJson) }.getOrDefault(emptyList())
          map.getOrPut(orderId) { mutableListOf() } += KdsTicketItemDto(
            id = rs.getObject("id", UUID::class.java).toString(),
            productId = rs.getObject("product_id", UUID::class.java).toString(),
            productName = rs.getString("product_name_snapshot"),
            variantSize = rs.getString("variant_size_snapshot"),
            crustName = rs.getString("crust_name_snapshot"),
            toppings = toppings,
            quantity = rs.getInt("quantity"),
          )
        }
        map
      }
    }
  }

  private fun loadDefaultPrepMins(conn: Connection): Int =
    conn.prepareStatement("SELECT default_prep_mins FROM restaurant_settings WHERE id = 1").use { ps ->
      ps.executeQuery().use { rs ->
        if (rs.next()) rs.getInt("default_prep_mins") else 20
      }
    }

  private data class LockedPriorityRow(
    val id: UUID,
    val customerId: UUID,
    val orderNumber: String,
    val status: OrderStatus,
    val version: Long,
    val isPriority: Boolean,
  )

  private data class TicketHeaderRow(
    val uuid: UUID,
    val orderNumber: String,
    val customerName: String,
    val customerPhone: String?,
    val fulfillmentType: FulfillmentType,
    val scheduleType: ScheduleType,
    val status: OrderStatus,
    val version: Long,
    val isPriority: Boolean,
    val paymentMethod: PaymentMethod,
    val paymentStatus: PaymentStatus,
    val totalPaise: Long,
    val deliveryArea: String?,
    val acceptedAt: String?,
    val prepStartedAt: String?,
    val readyAt: String?,
    val completedAt: String?,
    val createdAt: String,
  ) {
    fun toDto(items: List<KdsTicketItemDto>): KdsTicketDto =
      KdsTicketDto(
        id = uuid.toString(),
        orderNumber = orderNumber,
        customerName = customerName,
        customerPhone = customerPhone,
        fulfillmentType = fulfillmentType,
        scheduleType = scheduleType,
        status = status,
        version = version,
        isPriority = isPriority,
        paymentMethod = paymentMethod,
        paymentStatus = paymentStatus,
        totalPaise = totalPaise,
        deliveryArea = deliveryArea,
        acceptedAt = acceptedAt,
        prepStartedAt = prepStartedAt,
        readyAt = readyAt,
        completedAt = completedAt,
        createdAt = createdAt,
        items = items,
      )
  }
}

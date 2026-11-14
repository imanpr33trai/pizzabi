package com.pizzora.backend.realtime

import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.RealtimeEventDto
import com.pizzora.contracts.UserRole
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object RealtimeEventMapper {
  private val json = Json { ignoreUnknownKeys = true }

  val INTERNAL_WORKER_EVENT_TYPES: Set<String> = setOf(
    "PAYMENT_GATEWAY_ORDER_REQUESTED",
    "REFUND_REQUESTED",
  )

  fun authorizedRooms(principal: PizzoraPrincipal): Set<String> =
    when (principal.role) {
      UserRole.CUSTOMER -> setOf(RealtimeRooms.userRoom(principal.userId))
      UserRole.KITCHEN_STAFF -> setOf(
        RealtimeRooms.userRoom(principal.userId),
        RealtimeRooms.KDS_KITCHEN,
      )
      UserRole.OWNER -> setOf(
        RealtimeRooms.userRoom(principal.userId),
        RealtimeRooms.KDS_KITCHEN,
        RealtimeRooms.OWNER_DASHBOARD,
      )
    }

  fun isInternalWorkerCommand(eventType: String): Boolean =
    eventType in INTERNAL_WORKER_EVENT_TYPES

  fun mapOutboxRow(
    conn: Connection,
    id: UUID,
    aggregateType: String,
    aggregateId: UUID,
    aggregateVersion: Long,
    rawEventType: String,
    payloadJson: String,
    streamSeq: Long,
    sequencedAt: OffsetDateTime?,
    createdAt: OffsetDateTime,
    orderCustomerCache: MutableMap<UUID, UUID?> = mutableMapOf(),
  ): DispatchedRealtimeEvent? {
    if (isInternalWorkerCommand(rawEventType)) return null

    val parsedPayload = runCatching { json.parseToJsonElement(payloadJson).jsonObject }
      .getOrElse { JsonObject(emptyMap()) }

    val customerId: UUID? = parsedPayload["customer_id"]?.jsonPrimitive?.contentOrNull
      ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
      ?: if (aggregateType == "ORDER") {
        orderCustomerCache.getOrPut(aggregateId) {
          lookupOrderCustomerId(conn, aggregateId)
        }
      } else {
        null
      }

    val normalizedPayload = if (customerId != null && !parsedPayload.containsKey("customer_id")) {
      buildJsonObject {
        for ((k, v) in parsedPayload) put(k, v)
        put("customer_id", customerId.toString())
      }
    } else {
      parsedPayload
    }

    val wireType = toWireEventType(rawEventType)
    val orderNumber = normalizedPayload["order_number"]?.jsonPrimitive?.contentOrNull ?: aggregateId.toString().take(8)
    val toStatus = normalizedPayload["to_status"]?.jsonPrimitive?.contentOrNull
      ?: normalizedPayload["status"]?.jsonPrimitive?.contentOrNull
    val fromStatus = normalizedPayload["from_status"]?.jsonPrimitive?.contentOrNull

    val rooms = resolveTargetRooms(
      wireType = wireType,
      customerId = customerId,
      fromStatus = fromStatus,
      payload = normalizedPayload,
    )

    val inbox = buildInboxNotifications(
      eventId = id,
      aggregateId = aggregateId,
      wireType = wireType,
      customerId = customerId,
      orderNumber = orderNumber,
      toStatus = toStatus,
      payload = normalizedPayload,
    )

    return DispatchedRealtimeEvent(
      wireEvent = RealtimeEventDto(
        eventId = id.toString(),
        streamSeq = streamSeq,
        eventType = wireType,
        aggregateType = aggregateType,
        aggregateId = aggregateId.toString(),
        aggregateVersion = aggregateVersion,
        payload = normalizedPayload,
        sequencedAt = sequencedAt?.toInstant()?.toString(),
        createdAt = createdAt.toInstant().toString(),
      ),
      rooms = rooms,
      inboxNotifications = inbox,
    )
  }

  private fun toWireEventType(rawEventType: String): String =
    when (rawEventType) {
      "PAYMENT_GATEWAY_READY" -> "payment.gateway_ready"
      "ORDER_PLACED" -> "order.created"
      "ORDER_STATUS_CHANGED", "ORDER_CANCELLED", "ORDER_EXPIRED" -> "order.status_changed"
      "ORDER_PRIORITY_CHANGED" -> "order.priority_changed"
      "KITCHEN_ACKNOWLEDGED" -> "kitchen.acknowledged"
      "INVENTORY_LOW_STOCK" -> "inventory.low_stock"
      "OWNER_DASHBOARD_UPDATED" -> "owner.dashboard_updated"
      "NOTIFICATION_CREATED" -> "notification.created"
      else -> rawEventType.lowercase()
    }

  private fun resolveTargetRooms(
    wireType: String,
    customerId: UUID?,
    fromStatus: String?,
    payload: JsonObject,
  ): Set<String> {
    val rooms = LinkedHashSet<String>()
    if (customerId != null) {
      rooms += RealtimeRooms.userRoom(customerId)
    }
    payload["user_id"]?.jsonPrimitive?.contentOrNull?.let { targetUserId ->
      rooms += RealtimeRooms.userRoom(targetUserId)
    }

    when (wireType) {
      "payment.gateway_ready" -> {
        rooms += RealtimeRooms.OWNER_DASHBOARD
      }
      "order.created", "kitchen.acknowledged", "order.priority_changed" -> {
        rooms += RealtimeRooms.KDS_KITCHEN
        rooms += RealtimeRooms.OWNER_DASHBOARD
      }
      "order.status_changed" -> {
        rooms += RealtimeRooms.OWNER_DASHBOARD
        val toStatus = payload["to_status"]?.jsonPrimitive?.contentOrNull
        if (toStatus != OrderStatus.EXPIRED.name && fromStatus != OrderStatus.PENDING_PAYMENT.name) {
          rooms += RealtimeRooms.KDS_KITCHEN
        } else if (toStatus == OrderStatus.PLACED.name) {
          rooms += RealtimeRooms.KDS_KITCHEN
        }
      }
      "inventory.low_stock", "owner.dashboard_updated" -> {
        rooms += RealtimeRooms.OWNER_DASHBOARD
      }
    }
    return rooms
  }

  private fun buildInboxNotifications(
    eventId: UUID,
    aggregateId: UUID,
    wireType: String,
    customerId: UUID?,
    orderNumber: String,
    toStatus: String?,
    payload: JsonObject,
  ): List<PendingInboxNotification> {
    if (customerId == null) return emptyList()
    return when (wireType) {
      "order.created" -> listOf(
        PendingInboxNotification(
          userId = customerId,
          eventId = eventId,
          title = "Order Placed",
          body = "Order $orderNumber has been received by our kitchen.",
          category = "ORDER_UPDATE",
          referenceId = aggregateId,
        ),
      )
      "kitchen.acknowledged" -> listOf(
        PendingInboxNotification(
          userId = customerId,
          eventId = eventId,
          title = "Order Update",
          body = payload["message"]?.jsonPrimitive?.contentOrNull ?: "Order $orderNumber accepted by the kitchen.",
          category = "ORDER_UPDATE",
          referenceId = aggregateId,
        ),
      )
      "order.status_changed" -> {
        val statusLabel = when (toStatus) {
          OrderStatus.ACCEPTED.name -> "Order $orderNumber accepted by the kitchen."
          OrderStatus.PREPARING.name -> "Order $orderNumber is now baking in our wood-fired oven."
          OrderStatus.READY.name -> "Order $orderNumber is fresh and ready!"
          OrderStatus.OUT_FOR_DELIVERY.name -> "Order $orderNumber is out for delivery."
          OrderStatus.COMPLETED.name -> "Order $orderNumber is completed. Enjoy your pizza!"
          OrderStatus.CANCELLED.name -> "Order $orderNumber has been cancelled."
          OrderStatus.EXPIRED.name -> "Payment window expired for order $orderNumber."
          else -> "Order $orderNumber status updated to ${toStatus ?: "UPDATED"}."
        }
        val title = when (toStatus) {
          OrderStatus.CANCELLED.name -> "Order Cancelled"
          OrderStatus.EXPIRED.name -> "Order Expired"
          OrderStatus.READY.name -> "Order Ready"
          OrderStatus.OUT_FOR_DELIVERY.name -> "Out for Delivery"
          OrderStatus.COMPLETED.name -> "Order Completed"
          else -> "Order Update"
        }
        listOf(
          PendingInboxNotification(
            userId = customerId,
            eventId = eventId,
            title = title,
            body = payload["message"]?.jsonPrimitive?.contentOrNull ?: statusLabel,
            category = "ORDER_UPDATE",
            referenceId = aggregateId,
          ),
        )
      }
      else -> emptyList()
    }
  }

  private fun lookupOrderCustomerId(conn: Connection, orderId: UUID): UUID? =
    conn.prepareStatement("SELECT customer_id FROM orders WHERE id = ?").use { ps ->
      ps.setObject(1, orderId)
      ps.executeQuery().use { rs ->
        if (rs.next()) rs.getObject("customer_id", UUID::class.java) else null
      }
    }
}

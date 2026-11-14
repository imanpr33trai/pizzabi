package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class NotificationDeliveryStatus {
  @SerialName("PENDING")
  PENDING,

  @SerialName("SENT_WS")
  SENT_WS,

  @SerialName("SYNCED")
  SYNCED,

  @SerialName("FAILED")
  FAILED,
}

object NotificationCategory {
  const val ORDER_UPDATE: String = "ORDER_UPDATE"
  const val PROMO_ALERT: String = "PROMO_ALERT"
  const val PAYMENT_ALERT: String = "PAYMENT_ALERT"
  const val REFUND_ALERT: String = "REFUND_ALERT"
  const val SYSTEM_ALERT: String = "SYSTEM_ALERT"
}

@Serializable
data class NotificationInboxItemDto(
  val id: String,
  @SerialName("event_id") val eventId: String,
  val title: String,
  val body: String,
  val category: String,
  @SerialName("reference_id") val referenceId: String? = null,
  @SerialName("is_read") val isRead: Boolean,
  @SerialName("delivery_status") val deliveryStatus: NotificationDeliveryStatus,
  @SerialName("created_at") val createdAt: String,
)

@Serializable
data class NotificationInboxResponse(
  val notifications: List<NotificationInboxItemDto>,
  @SerialName("unread_count") val unreadCount: Int,
  @SerialName("server_now") val serverNow: String,
)

@Serializable
data class MarkNotificationReadResponse(
  val notification: NotificationInboxItemDto,
  @SerialName("unread_count") val unreadCount: Int,
)

@Serializable
data class MarkAllNotificationsReadResponse(
  @SerialName("marked_count") val markedCount: Int,
  @SerialName("unread_count") val unreadCount: Int,
)

@Serializable
data class NotificationPreferencesDto(
  @SerialName("user_id") val userId: String,
  @SerialName("order_updates") val orderUpdates: Boolean,
  @SerialName("promo_alerts") val promoAlerts: Boolean,
  @SerialName("kds_sound_enabled") val kdsSoundEnabled: Boolean,
  @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class UpdateNotificationPreferencesRequest(
  @SerialName("order_updates") val orderUpdates: Boolean? = null,
  @SerialName("promo_alerts") val promoAlerts: Boolean? = null,
  @SerialName("kds_sound_enabled") val kdsSoundEnabled: Boolean? = null,
)

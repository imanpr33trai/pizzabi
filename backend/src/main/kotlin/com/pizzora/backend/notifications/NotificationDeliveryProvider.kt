package com.pizzora.backend.notifications

import com.pizzora.contracts.NotificationDeliveryStatus
import java.util.UUID

data class NotificationDispatchRequest(
  val userId: UUID,
  val eventId: UUID,
  val title: String,
  val body: String,
  val category: String,
  val referenceId: UUID? = null,
  val deliveredViaWebSocket: Boolean = false,
)

data class NotificationDeliveryResult(
  val status: NotificationDeliveryStatus,
  val providerName: String,
)

class NotificationDeliveryException(
  message: String,
  val isTransient: Boolean = true,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

interface NotificationDeliveryProvider {
  val providerName: String
  suspend fun deliver(request: NotificationDispatchRequest): NotificationDeliveryResult
}

/**
 * Default Firebase-free notification delivery provider.
 * When an active WebSocket session received the corresponding realtime event,
 * inbox rows are marked SENT_WS; otherwise they remain PENDING until WorkManager
 * or foreground client sync pulls /api/v1/notifications and marks them SYNCED.
 */
class WebSocketInboxNotificationDeliveryProvider : NotificationDeliveryProvider {
  override val providerName: String = "WebSocketInboxProvider"

  override suspend fun deliver(request: NotificationDispatchRequest): NotificationDeliveryResult {
    val status = if (request.deliveredViaWebSocket) {
      NotificationDeliveryStatus.SENT_WS
    } else {
      NotificationDeliveryStatus.PENDING
    }
    return NotificationDeliveryResult(status = status, providerName = providerName)
  }
}

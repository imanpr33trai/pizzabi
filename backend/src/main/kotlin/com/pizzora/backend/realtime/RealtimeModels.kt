package com.pizzora.backend.realtime

import com.pizzora.contracts.RealtimeEventDto
import java.util.UUID
import kotlinx.serialization.Serializable

object RealtimeRooms {
  const val KDS_KITCHEN: String = "kds:kitchen"
  const val OWNER_DASHBOARD: String = "owner:dashboard"

  fun userRoom(userId: UUID): String = "user:$userId"
  fun userRoom(userId: String): String = "user:$userId"
}

data class PendingInboxNotification(
  val userId: UUID,
  val eventId: UUID,
  val title: String,
  val body: String,
  val category: String,
  val referenceId: UUID?,
)

data class DispatchedRealtimeEvent(
  val wireEvent: RealtimeEventDto,
  val rooms: Set<String>,
  val inboxNotifications: List<PendingInboxNotification> = emptyList(),
)

@Serializable
data class RedisRealtimePubSubMessage(
  val wireEvent: RealtimeEventDto,
  val rooms: Set<String>,
)

fun interface RealtimeEventDispatcher {
  suspend fun dispatch(event: DispatchedRealtimeEvent): Boolean
}

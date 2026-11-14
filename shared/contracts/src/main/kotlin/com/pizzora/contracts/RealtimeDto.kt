package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class RealtimeConnectionState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  RECONNECTING,
}

@Serializable
data class RealtimeEventDto(
  @SerialName("event_id") val eventId: String,
  @SerialName("stream_seq") val streamSeq: Long,
  @SerialName("event_type") val eventType: String,
  @SerialName("aggregate_type") val aggregateType: String,
  @SerialName("aggregate_id") val aggregateId: String,
  @SerialName("aggregate_version") val aggregateVersion: Long,
  val payload: JsonObject,
  @SerialName("sequenced_at") val sequencedAt: String? = null,
  @SerialName("created_at") val createdAt: String,
) {
  fun stringField(key: String): String? = payload[key]?.jsonPrimitive?.contentOrNull
}

@Serializable
data class EventsReplayResponse(
  val events: List<RealtimeEventDto>,
  @SerialName("server_high_water_seq") val serverHighWaterSeq: Long,
  @SerialName("has_more") val hasMore: Boolean = false,
  @SerialName("server_now") val serverNow: String,
)

@Serializable
data class WsEventsFrame(
  val type: String,
  val events: List<RealtimeEventDto> = emptyList(),
  @SerialName("server_high_water_seq") val serverHighWaterSeq: Long,
  @SerialName("server_now") val serverNow: String,
  @SerialName("resumed_from_seq") val resumedFromSeq: Long? = null,
)

@Serializable
data class TransitionOrderStatusRequest(
  @SerialName("to_status") val toStatus: OrderStatus,
  val reason: String? = null,
)

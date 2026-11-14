package com.pizzora.contracts

sealed interface CausalDecision {
  data object IgnoreDuplicate : CausalDecision
  data object IgnoreStale : CausalDecision
  data class ApplyOrderEvent(val event: RealtimeEventDto) : CausalDecision
  data class TriggerResync(val orderId: String, val event: RealtimeEventDto) : CausalDecision
  data class ApplyNonOrderEvent(val event: RealtimeEventDto) : CausalDecision
}

/**
 * Pure deterministic client tracker enforcing:
 * 1) At-least-once event_id deduplication.
 * 2) Filtered global cursor advancement via server_high_water_seq without false gap errors.
 * 3) Per-order aggregate_version causal ordering (drops stale versions, triggers REST snapshot resync on gaps).
 */
class OrderCausalTracker(
  private val maxTrackedEventIds: Int = 2_000,
) {
  private val seenEventIds = LinkedHashSet<String>()
  private val orderVersions = HashMap<String, Long>()

  var lastSeenStreamSeq: Long = 0L
    private set

  fun advanceCursor(serverHighWaterSeq: Long) {
    if (serverHighWaterSeq > lastSeenStreamSeq) {
      lastSeenStreamSeq = serverHighWaterSeq
    }
  }

  fun recordSnapshot(orderId: String, version: Long) {
    val existing = orderVersions[orderId] ?: 0L
    if (version > existing) {
      orderVersions[orderId] = version
    }
  }

  fun localVersionFor(orderId: String): Long? = orderVersions[orderId]

  fun evaluate(event: RealtimeEventDto): CausalDecision {
    if (!markEventSeen(event.eventId)) {
      return CausalDecision.IgnoreDuplicate
    }

    if (event.streamSeq > lastSeenStreamSeq) {
      lastSeenStreamSeq = event.streamSeq
    }

    if (event.aggregateType != "ORDER") {
      return CausalDecision.ApplyNonOrderEvent(event)
    }

    val orderId = event.stringField("order_id") ?: event.aggregateId
    val localVersion = orderVersions[orderId]

    if (event.eventType == "payment.gateway_ready") {
      if (localVersion == null) {
        orderVersions[orderId] = event.aggregateVersion
        return CausalDecision.ApplyOrderEvent(event)
      }
      if (event.aggregateVersion < localVersion) {
        return CausalDecision.IgnoreStale
      }
      if (event.aggregateVersion > localVersion + 1L) {
        return CausalDecision.TriggerResync(orderId, event)
      }
      if (event.aggregateVersion > localVersion) {
        orderVersions[orderId] = event.aggregateVersion
      }
      return CausalDecision.ApplyOrderEvent(event)
    }

    if (localVersion == null) {
      if (event.eventType == "order.created" || event.aggregateVersion <= 1L) {
        orderVersions[orderId] = event.aggregateVersion
        return CausalDecision.ApplyOrderEvent(event)
      }
      return CausalDecision.TriggerResync(orderId, event)
    }

    return when {
      event.aggregateVersion <= localVersion -> CausalDecision.IgnoreStale
      event.aggregateVersion == localVersion + 1L -> {
        orderVersions[orderId] = event.aggregateVersion
        CausalDecision.ApplyOrderEvent(event)
      }
      else -> CausalDecision.TriggerResync(orderId, event)
    }
  }

  fun clear() {
    seenEventIds.clear()
    orderVersions.clear()
    lastSeenStreamSeq = 0L
  }

  private fun markEventSeen(eventId: String): Boolean {
    if (!seenEventIds.add(eventId)) return false
    while (seenEventIds.size > maxTrackedEventIds) {
      val oldest = seenEventIds.iterator().next()
      seenEventIds.remove(oldest)
    }
    return true
  }
}

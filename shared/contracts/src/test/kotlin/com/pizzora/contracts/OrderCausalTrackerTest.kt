package com.pizzora.contracts

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderCausalTrackerTest {

  private fun orderEvent(
    eventId: String,
    streamSeq: Long,
    eventType: String,
    orderId: String = "ord-1",
    aggregateVersion: Long,
    toStatus: String = "PLACED",
  ): RealtimeEventDto = RealtimeEventDto(
    eventId = eventId,
    streamSeq = streamSeq,
    eventType = eventType,
    aggregateType = "ORDER",
    aggregateId = orderId,
    aggregateVersion = aggregateVersion,
    payload = buildJsonObject {
      put("order_id", orderId)
      put("to_status", toStatus)
      put("status", toStatus)
    },
    sequencedAt = "2026-11-13T10:00:00Z",
    createdAt = "2026-11-13T10:00:00Z",
  )

  @Test
  fun `deduplicates redelivered events by event_id`() {
    val tracker = OrderCausalTracker()
    val ev = orderEvent("ev-1", streamSeq = 10L, eventType = "order.created", aggregateVersion = 1L)

    assertTrue(tracker.evaluate(ev) is CausalDecision.ApplyOrderEvent)
    assertEquals(CausalDecision.IgnoreDuplicate, tracker.evaluate(ev))
    assertEquals(10L, tracker.lastSeenStreamSeq)
  }

  @Test
  fun `accepts payment gateway ready at current order version without false resync`() {
    val tracker = OrderCausalTracker()
    tracker.recordSnapshot("ord-1", 1L)

    val ready = orderEvent(
      eventId = "ev-gw-ready",
      streamSeq = 12L,
      eventType = "payment.gateway_ready",
      aggregateVersion = 1L,
    )
    assertTrue(tracker.evaluate(ready) is CausalDecision.ApplyOrderEvent)
    assertEquals(1L, tracker.localVersionFor("ord-1"))
  }

  @Test
  fun `drops stale aggregate versions and triggers resync when gap exceeds 1`() {
    val tracker = OrderCausalTracker()
    tracker.recordSnapshot("ord-1", 2L)

    val stale = orderEvent("ev-stale", streamSeq = 15L, eventType = "order.status_changed", aggregateVersion = 2L, toStatus = "ACCEPTED")
    assertEquals(CausalDecision.IgnoreStale, tracker.evaluate(stale))

    val gap = orderEvent("ev-gap", streamSeq = 20L, eventType = "order.status_changed", aggregateVersion = 4L, toStatus = "READY")
    val decision = tracker.evaluate(gap)
    assertTrue(decision is CausalDecision.TriggerResync)
    assertEquals("ord-1", (decision as CausalDecision.TriggerResync).orderId)

    // Snapshot reload brings local version up to 4
    tracker.recordSnapshot("ord-1", 4L)
    val next = orderEvent("ev-next", streamSeq = 25L, eventType = "order.status_changed", aggregateVersion = 5L, toStatus = "COMPLETED")
    assertTrue(tracker.evaluate(next) is CausalDecision.ApplyOrderEvent)
    assertEquals(5L, tracker.localVersionFor("ord-1"))
  }

  @Test
  fun `advances stream cursor across filtered cross-room gaps via server_high_water_seq`() {
    val tracker = OrderCausalTracker()
    tracker.recordSnapshot("ord-1", 1L)

    // Stream seq jumps 5 -> 38 because other customers' events were filtered out
    val ev = orderEvent("ev-38", streamSeq = 38L, eventType = "order.status_changed", aggregateVersion = 2L)
    assertTrue(tracker.evaluate(ev) is CausalDecision.ApplyOrderEvent)
    tracker.advanceCursor(50L)
    assertEquals(50L, tracker.lastSeenStreamSeq)
  }
}

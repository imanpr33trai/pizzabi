package com.pizzora.backend.realtime

import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.contracts.RealtimeEventDto
import com.pizzora.contracts.WsEventsFrame
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class RealtimeHub(
  private val cacheStore: EphemeralCacheStore? = null,
) : RealtimeEventDispatcher, AutoCloseable {
  private val logger = LoggerFactory.getLogger(RealtimeHub::class.java)
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  data class SessionHandle(
    val sessionId: UUID,
    val principal: PizzoraPrincipal,
    val authorizedRooms: Set<String>,
    val outbound: Channel<WsEventsFrame>,
    val lastSentSeq: AtomicLong = AtomicLong(0L),
  )

  private val sessions = ConcurrentHashMap<UUID, SessionHandle>()
  private var pubSubHandle: AutoCloseable? = null

  fun startPubSubListenerIfConfigured() {
    if (cacheStore == null || pubSubHandle != null) return
    pubSubHandle = cacheStore.subscribe(PUBSUB_CHANNEL) { raw ->
      val msg = runCatching { json.decodeFromString<RedisRealtimePubSubMessage>(raw) }.getOrNull()
        ?: return@subscribe
      fanOutLocal(msg.wireEvent, msg.rooms)
    }
  }

  fun registerSession(
    principal: PizzoraPrincipal,
    initialSentSeq: Long = 0L,
  ): SessionHandle {
    val handle = SessionHandle(
      sessionId = UUID.randomUUID(),
      principal = principal,
      authorizedRooms = RealtimeEventMapper.authorizedRooms(principal),
      outbound = Channel(capacity = 128),
      lastSentSeq = AtomicLong(initialSentSeq),
    )
    sessions[handle.sessionId] = handle
    return handle
  }

  fun unregisterSession(sessionId: UUID) {
    sessions.remove(sessionId)?.outbound?.close()
  }

  fun connectedSessionCount(): Int = sessions.size

  override suspend fun dispatch(event: DispatchedRealtimeEvent): Boolean {
    val deliveredLocal = fanOutLocal(event.wireEvent, event.rooms)
    val store = cacheStore
    if (store != null) {
      val payload = json.encodeToString(
        RedisRealtimePubSubMessage(
          wireEvent = event.wireEvent,
          rooms = event.rooms,
        ),
      )
      runCatching { store.publish(PUBSUB_CHANNEL, payload) }
        .onFailure { err -> logger.warn("Redis PubSub publish failed for stream_seq={}", event.wireEvent.streamSeq, err) }
    }
    return deliveredLocal
  }

  fun fanOutLocal(wireEvent: RealtimeEventDto, rooms: Set<String>): Boolean {
    var delivered = false
    for (handle in sessions.values) {
      if (handle.authorizedRooms.none { it in rooms }) continue
      val prev = handle.lastSentSeq.get()
      if (wireEvent.streamSeq <= prev) continue
      val frame = WsEventsFrame(
        type = "EVENTS",
        events = listOf(wireEvent),
        serverHighWaterSeq = wireEvent.streamSeq,
        serverNow = Instant.now().toString(),
      )
      val offered = handle.outbound.trySend(frame).isSuccess
      if (offered) {
        handle.lastSentSeq.updateAndGet { current -> maxOf(current, wireEvent.streamSeq) }
        delivered = true
      }
    }
    return delivered
  }

  override fun close() {
    runCatching { pubSubHandle?.close() }
    pubSubHandle = null
    sessions.values.forEach { it.outbound.close() }
    sessions.clear()
  }

  companion object {
    const val PUBSUB_CHANNEL: String = "pizzora:realtime:events"
  }
}

class RedisRealtimeEventDispatcher(
  private val cacheStore: EphemeralCacheStore,
) : RealtimeEventDispatcher {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  override suspend fun dispatch(event: DispatchedRealtimeEvent): Boolean {
    val payload = json.encodeToString(
      RedisRealtimePubSubMessage(
        wireEvent = event.wireEvent,
        rooms = event.rooms,
      ),
    )
    return cacheStore.publish(RealtimeHub.PUBSUB_CHANNEL, payload)
  }
}

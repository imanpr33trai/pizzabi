package com.example.core.network

import com.pizzora.contracts.RealtimeConnectionState
import com.pizzora.contracts.WsEventsFrame
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface RealtimeEventStream {
  val connectionState: StateFlow<RealtimeConnectionState>
  val frames: SharedFlow<WsEventsFrame>
  val lastHighWaterSeq: StateFlow<Long>
  fun start(sessionUserId: String, initialSinceSeq: Long = 0L)
  fun stop()
  fun resetForSessionChange() {
    stop()
  }
  fun updateHighWaterSeq(seq: Long)
}

class WebSocketEventClient(
  private val apiClient: PizzoraApiClient,
  private val initialBackoffMs: Long = 500L,
  private val maxBackoffMs: Long = 15_000L,
  ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RealtimeEventStream {

  private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

  private val _connectionState = MutableStateFlow(RealtimeConnectionState.DISCONNECTED)
  override val connectionState: StateFlow<RealtimeConnectionState> = _connectionState.asStateFlow()

  private val _frames = MutableSharedFlow<WsEventsFrame>(extraBufferCapacity = 64)
  override val frames: SharedFlow<WsEventsFrame> = _frames.asSharedFlow()

  private val _lastHighWaterSeq = MutableStateFlow(0L)
  override val lastHighWaterSeq: StateFlow<Long> = _lastHighWaterSeq.asStateFlow()

  private var activeUserId: String? = null
  private var connectionJob: Job? = null

  @Synchronized
  override fun start(sessionUserId: String, initialSinceSeq: Long) {
    if (activeUserId != null && activeUserId != sessionUserId) {
      _lastHighWaterSeq.value = 0L
    }
    if (initialSinceSeq > _lastHighWaterSeq.value) {
      _lastHighWaterSeq.value = initialSinceSeq
    }
    if (activeUserId == sessionUserId && connectionJob?.isActive == true) {
      return
    }
    connectionJob?.cancel()
    activeUserId = sessionUserId
    connectionJob = scope.launch {
      runReconnectLoop()
    }
  }

  @Synchronized
  override fun stop() {
    activeUserId = null
    connectionJob?.cancel()
    connectionJob = null
    _connectionState.value = RealtimeConnectionState.DISCONNECTED
  }

  @Synchronized
  override fun resetForSessionChange() {
    stop()
    _lastHighWaterSeq.value = 0L
  }

  override fun updateHighWaterSeq(seq: Long) {
    if (seq > _lastHighWaterSeq.value) {
      _lastHighWaterSeq.value = seq
    }
  }

  private suspend fun runReconnectLoop() {
    var backoffMs = initialBackoffMs
    var hasConnectedOnce = false

    while (scope.isActive && activeUserId != null) {
      _connectionState.value = if (hasConnectedOnce) {
        RealtimeConnectionState.RECONNECTING
      } else {
        RealtimeConnectionState.CONNECTING
      }

      try {
        val ticket = apiClient.mintWsTicket().ticket
        val wsUrl = apiClient.wsEventsUrl(ticket, _lastHighWaterSeq.value)
        apiClient.httpClient.webSocket(urlString = wsUrl) {
          _connectionState.value = RealtimeConnectionState.CONNECTED
          hasConnectedOnce = true
          backoffMs = initialBackoffMs

          for (frame in incoming) {
            if (frame is Frame.Text) {
              val decoded = runCatching {
                apiClient.wireJson.decodeFromString<WsEventsFrame>(frame.readText())
              }.getOrNull() ?: continue
              updateHighWaterSeq(decoded.serverHighWaterSeq)
              _frames.tryEmit(decoded)
            }
          }
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (_: Throwable) {
        // Fallback catch-up via HTTP replay if WebSocket handshake/socket failed
        runCatching {
          val replay = apiClient.replayEvents(sinceSeq = _lastHighWaterSeq.value, limit = 100)
          updateHighWaterSeq(replay.serverHighWaterSeq)
          if (replay.events.isNotEmpty()) {
            _frames.tryEmit(
              WsEventsFrame(
                type = "EVENTS",
                events = replay.events,
                serverHighWaterSeq = replay.serverHighWaterSeq,
                serverNow = replay.serverNow,
              ),
            )
          }
        }
      }

      if (activeUserId == null) break
      _connectionState.value = RealtimeConnectionState.RECONNECTING
      delay(backoffMs)
      backoffMs = (backoffMs * 2L).coerceAtMost(maxBackoffMs)
    }

    _connectionState.value = RealtimeConnectionState.DISCONNECTED
  }
}

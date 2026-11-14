package com.pizzora.backend.realtime

import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.contracts.WsEventsFrame
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.realtimeRoutes(
  realtimeHub: RealtimeHub,
  realtimeEventService: RealtimeEventService,
  heartbeatIntervalMs: Long = 20_000L,
) {
  val wireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  authenticate("auth-jwt") {
    get("/api/v1/events/replay") {
      val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
      val sinceSeq = call.request.queryParameters["since_seq"]?.toLongOrNull() ?: 0L
      val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
      call.respond(HttpStatusCode.OK, realtimeEventService.replayEvents(principal, sinceSeq, limit))
    }
  }

  authenticate("auth-ws") {
    get("/ws/v1/events") {
      call.respond(HttpStatusCode.UpgradeRequired, "WebSocket upgrade headers are required.")
    }

    webSocket("/ws/v1/events") {
      val principal = call.principal<PizzoraPrincipal>() ?: run {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
        return@webSocket
      }
      val sinceSeqParam = call.request.queryParameters["since_seq"]?.toLongOrNull()

      var cursorSeq = 0L
      if (sinceSeqParam != null && sinceSeqParam >= 0L) {
        var pageSince: Long = sinceSeqParam
        var sentAnyReplayFrame = false
        for (pageIdx in 0 until 10) {
          val replay = realtimeEventService.replayEvents(principal, sinceSeq = pageSince, limit = 100)
          cursorSeq = maxOf(cursorSeq, replay.serverHighWaterSeq)
          if (replay.events.isNotEmpty() || !sentAnyReplayFrame) {
            val frame = WsEventsFrame(
              type = if (replay.events.isNotEmpty()) "EVENTS" else "HEARTBEAT",
              events = replay.events,
              serverHighWaterSeq = replay.serverHighWaterSeq,
              serverNow = replay.serverNow,
              resumedFromSeq = if (pageIdx == 0) sinceSeqParam else null,
            )
            outgoing.send(Frame.Text(wireJson.encodeToString(frame)))
            sentAnyReplayFrame = true
          }
          pageSince = replay.serverHighWaterSeq
          if (!replay.hasMore) break
        }
      } else {
        cursorSeq = realtimeEventService.currentHighWaterSeq()
        val initialHeartbeat = WsEventsFrame(
          type = "HEARTBEAT",
          events = emptyList(),
          serverHighWaterSeq = cursorSeq,
          serverNow = Instant.now().toString(),
        )
        outgoing.send(Frame.Text(wireJson.encodeToString(initialHeartbeat)))
      }

      val handle = realtimeHub.registerSession(principal = principal, initialSentSeq = cursorSeq)

      val writerJob = launch {
        for (frame in handle.outbound) {
          outgoing.send(Frame.Text(wireJson.encodeToString(frame)))
        }
      }

      val heartbeatJob = launch {
        while (true) {
          delay(heartbeatIntervalMs)
          val seq = maxOf(handle.lastSentSeq.get(), realtimeEventService.currentHighWaterSeq())
          val heartbeat = WsEventsFrame(
            type = "HEARTBEAT",
            events = emptyList(),
            serverHighWaterSeq = seq,
            serverNow = Instant.now().toString(),
          )
          handle.outbound.trySend(heartbeat)
        }
      }

      try {
        for (frame in incoming) {
          // Client frames (e.g. ping/text acks) keep connection alive; ignore non-fatal payloads cleanly
          if (frame is Frame.Close) {
            break
          }
        }
      } catch (_: CancellationException) {
        // Normal close / cancellation
      } catch (_: Throwable) {
        close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Stream closed"))
      } finally {
        heartbeatJob.cancel()
        writerJob.cancel()
        realtimeHub.unregisterSession(handle.sessionId)
      }
    }
  }
}

package com.pizzora.backend.realtime

import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.contracts.EventsReplayResponse
import com.pizzora.contracts.RealtimeEventDto
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

class RealtimeEventService(
  private val dataSource: DataSource,
) {
  suspend fun currentHighWaterSeq(): Long = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
    conn.prepareStatement("SELECT last_seq FROM event_stream_cursor WHERE id = 1").use { ps ->
      ps.executeQuery().use { rs ->
        if (rs.next()) rs.getLong("last_seq") else 0L
      }
    }
  }

  suspend fun replayEvents(
    principal: PizzoraPrincipal,
    sinceSeq: Long,
    limit: Int = 100,
  ): EventsReplayResponse {
    val safeSinceSeq = sinceSeq.coerceAtLeast(0L)
    val clampedLimit = limit.coerceIn(1, 200)
    val callerRooms = RealtimeEventMapper.authorizedRooms(principal)

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val globalHighWater = conn.prepareStatement(
        "SELECT last_seq FROM event_stream_cursor WHERE id = 1",
      ).use { ps ->
        ps.executeQuery().use { rs ->
          if (rs.next()) rs.getLong("last_seq") else 0L
        }
      }

      if (safeSinceSeq >= globalHighWater) {
        return@withJdbcTransaction EventsReplayResponse(
          events = emptyList(),
          serverHighWaterSeq = globalHighWater,
          hasMore = false,
          serverNow = Instant.now().toString(),
        )
      }

      val matched = mutableListOf<RealtimeEventDto>()
      val customerCache = mutableMapOf<UUID, UUID?>()
      var cursorSeq = safeSinceSeq
      var scannedRows = 0
      val maxScanRows = 1_000
      val chunkLimit = 100

      while (cursorSeq < globalHighWater && matched.size < clampedLimit && scannedRows < maxScanRows) {
        val rawRows = conn.prepareStatement(
          """
          SELECT id, aggregate_type, aggregate_id, aggregate_version, event_type,
                 payload_json::text AS payload_text, stream_seq, sequenced_at, created_at
          FROM outbox_events
          WHERE stream_seq IS NOT NULL
            AND stream_seq > ?
            AND stream_seq <= ?
          ORDER BY stream_seq ASC
          LIMIT ?
          """.trimIndent(),
        ).use { ps ->
          ps.setLong(1, cursorSeq)
          ps.setLong(2, globalHighWater)
          ps.setInt(3, chunkLimit)
          ps.executeQuery().use { rs ->
            val rows = mutableListOf<RawOutboxReplayRow>()
            while (rs.next()) {
              rows += RawOutboxReplayRow(
                id = rs.getObject("id", UUID::class.java),
                aggregateType = rs.getString("aggregate_type"),
                aggregateId = rs.getObject("aggregate_id", UUID::class.java),
                aggregateVersion = rs.getLong("aggregate_version"),
                eventType = rs.getString("event_type"),
                payloadJson = rs.getString("payload_text"),
                streamSeq = rs.getLong("stream_seq"),
                sequencedAt = rs.getObject("sequenced_at", OffsetDateTime::class.java),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
              )
            }
            rows
          }
        }

        if (rawRows.isEmpty()) {
          cursorSeq = globalHighWater
          break
        }

        for (row in rawRows) {
          cursorSeq = row.streamSeq
          scannedRows += 1
          val mapped = RealtimeEventMapper.mapOutboxRow(
            conn = conn,
            id = row.id,
            aggregateType = row.aggregateType,
            aggregateId = row.aggregateId,
            aggregateVersion = row.aggregateVersion,
            rawEventType = row.eventType,
            payloadJson = row.payloadJson,
            streamSeq = row.streamSeq,
            sequencedAt = row.sequencedAt,
            createdAt = row.createdAt,
            orderCustomerCache = customerCache,
          ) ?: continue

          if (mapped.rooms.any { it in callerRooms }) {
            matched += mapped.wireEvent
            if (matched.size >= clampedLimit) {
              break
            }
          }
        }
      }

      val hasMore = cursorSeq < globalHighWater
      val highWaterOut = if (hasMore) cursorSeq else globalHighWater
      EventsReplayResponse(
        events = matched,
        serverHighWaterSeq = highWaterOut,
        hasMore = hasMore,
        serverNow = Instant.now().toString(),
      )
    }
  }

  private data class RawOutboxReplayRow(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val eventType: String,
    val payloadJson: String,
    val streamSeq: Long,
    val sequencedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
  )
}

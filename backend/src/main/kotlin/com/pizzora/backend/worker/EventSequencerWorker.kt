package com.pizzora.backend.worker

import com.pizzora.backend.core.db.DatabaseFactory
import java.util.UUID
import javax.sql.DataSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

/**
 * Stage 1 of the commit-safe outbox pipeline.
 * Holds a PostgreSQL transaction-scoped advisory lock (`pg_advisory_xact_lock`),
 * reads ONLY already-committed `outbox_events WHERE stream_seq IS NULL`,
 * assigns strictly monotonic gap-free `stream_seq` values from `event_stream_cursor`,
 * and sets `sequenced_at = NOW()` while leaving `dispatch_status = 'PENDING'` and `published_at = NULL`.
 */
class EventSequencerWorker(
  private val dataSource: DataSource,
  private val batchSize: Int = 100,
) {
  private val logger = LoggerFactory.getLogger(EventSequencerWorker::class.java)

  suspend fun runLoop(pollIntervalMs: Long) {
    while (true) {
      try {
        val sequenced = runOnce()
        if (sequenced == 0) {
          delay(pollIntervalMs)
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (t: Throwable) {
        logger.error("EventSequencerWorker loop iteration failed", t)
        delay(pollIntervalMs)
      }
    }
  }

  suspend fun runOnce(): Int = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
    conn.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { ps ->
      ps.setLong(1, EVENT_SEQUENCER_LOCK_KEY)
      ps.executeQuery().use { rs -> rs.next() }
    }

    conn.prepareStatement(
      "INSERT INTO event_stream_cursor (id, last_seq) VALUES (1, 0) ON CONFLICT (id) DO NOTHING",
    ).use { ps -> ps.executeUpdate() }

    val currentCursor = conn.prepareStatement(
      "SELECT last_seq FROM event_stream_cursor WHERE id = 1 FOR UPDATE",
    ).use { ps ->
      ps.executeQuery().use { rs ->
        if (rs.next()) rs.getLong("last_seq") else 0L
      }
    }

    val unsequencedIds = conn.prepareStatement(
      """
      SELECT id
      FROM outbox_events
      WHERE stream_seq IS NULL
      ORDER BY created_at ASC, id ASC
      LIMIT ?
      FOR UPDATE
      """.trimIndent(),
    ).use { ps ->
      ps.setInt(1, batchSize)
      ps.executeQuery().use { rs ->
        val ids = mutableListOf<UUID>()
        while (rs.next()) {
          ids += rs.getObject("id", UUID::class.java)
        }
        ids
      }
    }

    if (unsequencedIds.isEmpty()) {
      return@withJdbcTransaction 0
    }

    var nextSeq = currentCursor
    conn.prepareStatement(
      """
      UPDATE outbox_events
      SET stream_seq = ?, sequenced_at = NOW()
      WHERE id = ? AND stream_seq IS NULL
      """.trimIndent(),
    ).use { ps ->
      for (eventId in unsequencedIds) {
        nextSeq += 1L
        ps.setLong(1, nextSeq)
        ps.setObject(2, eventId)
        ps.addBatch()
      }
      ps.executeBatch()
    }

    conn.prepareStatement(
      "UPDATE event_stream_cursor SET last_seq = ? WHERE id = 1",
    ).use { ps ->
      ps.setLong(1, nextSeq)
      ps.executeUpdate()
    }

    unsequencedIds.size
  }

  companion object {
    const val EVENT_SEQUENCER_LOCK_KEY: Long = 748291041L
  }
}

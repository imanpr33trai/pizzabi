package com.pizzora.backend.core.metrics

import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import javax.sql.DataSource

class PizzoraMetricsRegistry {
  private val startEpochMs = System.currentTimeMillis()
  private val requestCounts = ConcurrentHashMap<String, LongAdder>()
  private val requestDurationNanos = ConcurrentHashMap<String, LongAdder>()
  private val error5xxCounts = ConcurrentHashMap<String, LongAdder>()
  private val activeRequests = AtomicLong(0)

  fun onRequestStart() {
    activeRequests.incrementAndGet()
  }

  fun onRequestComplete(routeGroup: String, statusCode: Int, durationNanos: Long) {
    activeRequests.decrementAndGet()
    val statusClass = "${statusCode / 100}xx"
    val key = "$routeGroup|$statusClass"
    requestCounts.computeIfAbsent(key) { LongAdder() }.increment()
    requestDurationNanos.computeIfAbsent(routeGroup) { LongAdder() }.add(durationNanos.coerceAtLeast(0L))
    if (statusCode >= 500) {
      error5xxCounts.computeIfAbsent(routeGroup) { LongAdder() }.increment()
    }
  }

  fun renderPrometheus(dataSource: DataSource? = null): String {
    val sb = StringBuilder()
    val runtimeMx = ManagementFactory.getRuntimeMXBean()
    val memMx = ManagementFactory.getMemoryMXBean()
    val threadMx = ManagementFactory.getThreadMXBean()
    val heap = memMx.heapMemoryUsage
    val nonHeap = memMx.nonHeapMemoryUsage

    sb.appendLine("# HELP pizzora_process_uptime_seconds Process uptime in seconds")
    sb.appendLine("# TYPE pizzora_process_uptime_seconds gauge")
    sb.appendLine("pizzora_process_uptime_seconds ${(System.currentTimeMillis() - startEpochMs) / 1000.0}")
    sb.appendLine("pizzora_jvm_uptime_seconds ${runtimeMx.uptime / 1000.0}")

    sb.appendLine("# HELP pizzora_jvm_heap_used_bytes JVM heap memory used in bytes")
    sb.appendLine("# TYPE pizzora_jvm_heap_used_bytes gauge")
    sb.appendLine("pizzora_jvm_heap_used_bytes ${heap.used}")
    sb.appendLine("pizzora_jvm_heap_max_bytes ${heap.max.coerceAtLeast(0L)}")
    sb.appendLine("pizzora_jvm_nonheap_used_bytes ${nonHeap.used}")
    sb.appendLine("pizzora_jvm_threads_live ${threadMx.threadCount}")

    sb.appendLine("# HELP pizzora_http_requests_in_flight Current in-flight HTTP requests")
    sb.appendLine("# TYPE pizzora_http_requests_in_flight gauge")
    sb.appendLine("pizzora_http_requests_in_flight ${activeRequests.get()}")

    sb.appendLine("# HELP pizzora_http_requests_total Total HTTP requests by route group and status class")
    sb.appendLine("# TYPE pizzora_http_requests_total counter")
    requestCounts.entries.sortedBy { it.key }.forEach { (key, adder) ->
      val parts = key.split('|')
      val group = parts[0]
      val statusClass = parts[1]
      sb.appendLine("""pizzora_http_requests_total{group="$group",status_class="$statusClass"} ${adder.sum()}""")
    }

    sb.appendLine("# HELP pizzora_http_request_duration_seconds_sum Cumulative HTTP request duration in seconds")
    sb.appendLine("# TYPE pizzora_http_request_duration_seconds_sum counter")
    requestDurationNanos.entries.sortedBy { it.key }.forEach { (group, nanos) ->
      sb.appendLine("""pizzora_http_request_duration_seconds_sum{group="$group"} ${nanos.sum() / 1_000_000_000.0}""")
    }

    sb.appendLine("# HELP pizzora_http_errors_5xx_total Total HTTP 5xx responses by route group")
    sb.appendLine("# TYPE pizzora_http_errors_5xx_total counter")
    error5xxCounts.entries.sortedBy { it.key }.forEach { (group, adder) ->
      sb.appendLine("""pizzora_http_errors_5xx_total{group="$group"} ${adder.sum()}""")
    }

    if (dataSource is HikariDataSource) {
      val mx = dataSource.hikariPoolMXBean
      if (mx != null) {
        sb.appendLine("# HELP pizzora_hikari_connections_active Active HikariCP connections")
        sb.appendLine("# TYPE pizzora_hikari_connections_active gauge")
        sb.appendLine("pizzora_hikari_connections_active ${mx.activeConnections}")
        sb.appendLine("pizzora_hikari_connections_idle ${mx.idleConnections}")
        sb.appendLine("pizzora_hikari_connections_total ${mx.totalConnections}")
        sb.appendLine("pizzora_hikari_threads_awaiting ${mx.threadsAwaitingConnection}")
      }
    }

    if (dataSource != null) {
      val dbStats = queryOutboxAndOrderMetrics(dataSource)
      sb.appendLine("# HELP pizzora_outbox_unsequenced_total Outbox rows waiting for EventSequencerWorker")
      sb.appendLine("# TYPE pizzora_outbox_unsequenced_total gauge")
      sb.appendLine("pizzora_outbox_unsequenced_total ${dbStats.unsequencedOutbox}")
      sb.appendLine("# HELP pizzora_outbox_pending_dispatch_total Sequenced outbox rows awaiting dispatch")
      sb.appendLine("# TYPE pizzora_outbox_pending_dispatch_total gauge")
      sb.appendLine("pizzora_outbox_pending_dispatch_total ${dbStats.pendingDispatchOutbox}")
      sb.appendLine("# HELP pizzora_outbox_dead_letter_total Dead-lettered outbox events requiring attention")
      sb.appendLine("# TYPE pizzora_outbox_dead_letter_total gauge")
      sb.appendLine("pizzora_outbox_dead_letter_total ${dbStats.deadLetterOutbox}")
      sb.appendLine("# HELP pizzora_stream_last_seq Latest committed global event stream sequence")
      sb.appendLine("# TYPE pizzora_stream_last_seq gauge")
      sb.appendLine("pizzora_stream_last_seq ${dbStats.lastStreamSeq}")
      sb.appendLine("# HELP pizzora_refunds_failed_total Failed refund rows needing reconciliation")
      sb.appendLine("# TYPE pizzora_refunds_failed_total gauge")
      sb.appendLine("pizzora_refunds_failed_total ${dbStats.failedRefunds}")
    }

    return sb.toString()
  }

  private data class DbMetricSnapshot(
    val unsequencedOutbox: Long = 0L,
    val pendingDispatchOutbox: Long = 0L,
    val deadLetterOutbox: Long = 0L,
    val lastStreamSeq: Long = 0L,
    val failedRefunds: Long = 0L,
  )

  private fun queryOutboxAndOrderMetrics(dataSource: DataSource): DbMetricSnapshot {
    return runCatching {
      dataSource.connection.use { conn ->
        conn.createStatement().use { st ->
          st.queryTimeout = 2
          var unseq = 0L
          var pending = 0L
          var dlq = 0L
          var lastSeq = 0L
          var failedRefunds = 0L
          st.executeQuery(
            """
            SELECT
              (SELECT COUNT(*) FROM outbox_events WHERE stream_seq IS NULL) AS unseq_cnt,
              (SELECT COUNT(*) FROM outbox_events WHERE stream_seq IS NOT NULL AND dispatch_status IN ('PENDING', 'PROCESSING')) AS pend_cnt,
              (SELECT COUNT(*) FROM outbox_events WHERE dispatch_status = 'DEAD_LETTER') AS dlq_cnt,
              COALESCE((SELECT last_seq FROM event_stream_cursor WHERE id = 1), 0) AS last_seq,
              (SELECT COUNT(*) FROM refunds WHERE status = 'FAILED') AS failed_refunds
            """.trimIndent(),
          ).use { rs ->
            if (rs.next()) {
              unseq = rs.getLong("unseq_cnt")
              pending = rs.getLong("pend_cnt")
              dlq = rs.getLong("dlq_cnt")
              lastSeq = rs.getLong("last_seq")
              failedRefunds = rs.getLong("failed_refunds")
            }
          }
          DbMetricSnapshot(
            unsequencedOutbox = unseq,
            pendingDispatchOutbox = pending,
            deadLetterOutbox = dlq,
            lastStreamSeq = lastSeq,
            failedRefunds = failedRefunds,
          )
        }
      }
    }.getOrElse { DbMetricSnapshot() }
  }
}

class WorkerMetricsRegistry {
  private val startEpochMs = System.currentTimeMillis()
  private val loopCycles = ConcurrentHashMap<String, LongAdder>()
  private val loopErrors = ConcurrentHashMap<String, LongAdder>()
  private val lastBeatEpochSeconds = ConcurrentHashMap<String, AtomicLong>()

  fun recordLoopSuccess(loopName: String) {
    loopCycles.computeIfAbsent(loopName) { LongAdder() }.increment()
    lastBeatEpochSeconds.computeIfAbsent(loopName) { AtomicLong(0L) }.set(System.currentTimeMillis() / 1000L)
  }

  fun recordLoopError(loopName: String) {
    loopErrors.computeIfAbsent(loopName) { LongAdder() }.increment()
  }

  fun renderPrometheus(): String {
    val sb = StringBuilder()
    val mem = ManagementFactory.getMemoryMXBean().heapMemoryUsage
    sb.appendLine("# HELP pizzora_worker_uptime_seconds Worker process uptime in seconds")
    sb.appendLine("# TYPE pizzora_worker_uptime_seconds gauge")
    sb.appendLine("pizzora_worker_uptime_seconds ${(System.currentTimeMillis() - startEpochMs) / 1000.0}")
    sb.appendLine("pizzora_worker_jvm_heap_used_bytes ${mem.used}")

    sb.appendLine("# HELP pizzora_worker_loop_cycles_total Total successful worker loop cycles")
    sb.appendLine("# TYPE pizzora_worker_loop_cycles_total counter")
    loopCycles.entries.sortedBy { it.key }.forEach { (loop, adder) ->
      sb.appendLine("""pizzora_worker_loop_cycles_total{loop="$loop"} ${adder.sum()}""")
    }

    sb.appendLine("# HELP pizzora_worker_loop_errors_total Total worker loop exceptions")
    sb.appendLine("# TYPE pizzora_worker_loop_errors_total counter")
    loopErrors.entries.sortedBy { it.key }.forEach { (loop, adder) ->
      sb.appendLine("""pizzora_worker_loop_errors_total{loop="$loop"} ${adder.sum()}""")
    }

    sb.appendLine("# HELP pizzora_worker_last_heartbeat_epoch_seconds Unix timestamp of last loop heartbeat")
    sb.appendLine("# TYPE pizzora_worker_last_heartbeat_epoch_seconds gauge")
    lastBeatEpochSeconds.entries.sortedBy { it.key }.forEach { (loop, ts) ->
      sb.appendLine("""pizzora_worker_last_heartbeat_epoch_seconds{loop="$loop"} ${ts.get()}""")
    }
    return sb.toString()
  }
}

private val METRICS_START_TIME_KEY = AttributeKey<Long>("PizzoraMetricsStartNanos")

fun Application.installHttpMetrics(registry: PizzoraMetricsRegistry) {
  val plugin = createApplicationPlugin("PizzoraHttpMetrics") {
    onCall { call ->
      call.attributes.put(METRICS_START_TIME_KEY, System.nanoTime())
      registry.onRequestStart()
    }
    onCallRespond { call ->
      val start = call.attributes.getOrNull(METRICS_START_TIME_KEY) ?: return@onCallRespond
      val duration = System.nanoTime() - start
      val status = call.response.status()?.value ?: 200
      val group = classifyRouteGroup(call.request.path())
      registry.onRequestComplete(group, status, duration)
    }
  }
  install(plugin)
}

private fun classifyRouteGroup(path: String): String = when {
  path == "/metrics" -> "metrics"
  path.startsWith("/api/v1/health/") -> "health"
  path.startsWith("/api/v1/auth/") -> "auth"
  path.startsWith("/api/v1/catalog/") -> "catalog"
  path.startsWith("/api/v1/checkout") || path.startsWith("/api/v1/payments/") -> "checkout_payments"
  path.startsWith("/api/v1/orders") -> "orders"
  path.startsWith("/api/v1/kds/") -> "kds"
  path.startsWith("/api/v1/owner/") -> "owner"
  path.startsWith("/api/v1/webhooks/") -> "webhooks"
  path.startsWith("/ws/") -> "websocket"
  else -> "other"
}

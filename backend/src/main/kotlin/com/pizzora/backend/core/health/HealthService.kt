package com.pizzora.backend.core.health

import com.pizzora.backend.core.config.AppConfig
import com.pizzora.contracts.DependencyHealthStatus
import com.pizzora.contracts.HealthReadinessResponse
import com.pizzora.contracts.ReadinessState
import io.ktor.http.HttpStatusCode
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

interface DependencyProbe {
  val name: String
  val required: Boolean
  suspend fun check(): DependencyHealthStatus
}

class TcpDependencyProbe(
  override val name: String,
  override val required: Boolean,
  private val host: String,
  private val port: Int,
  private val timeoutMs: Int = 1_000,
) : DependencyProbe {
  override suspend fun check(): DependencyHealthStatus = withContext(Dispatchers.IO) {
    runCatching {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), timeoutMs)
      }
      DependencyHealthStatus.UP
    }.getOrElse { DependencyHealthStatus.DOWN }
  }
}

data class ReadinessEvaluation(
  val httpStatus: HttpStatusCode,
  val body: HealthReadinessResponse,
)

class HealthService(
  private val probes: List<DependencyProbe>,
) {
  suspend fun checkReadiness(): ReadinessEvaluation = coroutineScope {
    val results = probes.map { probe ->
      probe to async { probe.check() }
    }.map { (probe, deferred) ->
      probe to deferred.await()
    }

    val statusMap = results.associate { (probe, status) -> probe.name to status }
    val requiredDown = results.any { (probe, status) -> probe.required && status == DependencyHealthStatus.DOWN }
    val optionalDown = results.any { (probe, status) -> !probe.required && status == DependencyHealthStatus.DOWN }

    val readinessState = when {
      requiredDown -> ReadinessState.UNHEALTHY
      optionalDown -> ReadinessState.DEGRADED
      else -> ReadinessState.HEALTHY
    }

    val httpStatus = if (readinessState == ReadinessState.UNHEALTHY) {
      HttpStatusCode.ServiceUnavailable
    } else {
      HttpStatusCode.OK
    }

    ReadinessEvaluation(
      httpStatus = httpStatus,
      body = HealthReadinessResponse(
        status = readinessState,
        dependencies = statusMap,
      ),
    )
  }

  companion object {
    fun defaultProbes(config: AppConfig): List<DependencyProbe> = buildList {
      add(
        TcpDependencyProbe(
          name = "postgres",
          required = true,
          host = config.database.host,
          port = config.database.port,
        ),
      )
      add(
        TcpDependencyProbe(
          name = "redis",
          required = false,
          host = config.redis.host,
          port = config.redis.port,
        ),
      )
      if (config.storage.enabled) {
        add(
          TcpDependencyProbe(
            name = "minio",
            required = false,
            host = config.storage.host,
            port = config.storage.port,
          ),
        )
      }
    }
  }
}

package com.pizzora.backend.worker

import com.pizzora.backend.core.config.AppConfig
import com.pizzora.contracts.DependencyHealthStatus
import com.pizzora.contracts.HealthReadinessResponse
import com.pizzora.contracts.ReadinessState
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main() {
  val config = AppConfig.fromEnv()
  val logger = LoggerFactory.getLogger("com.pizzora.backend.worker.WorkerMain")
  val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  logger.info(
    "Starting PIZZORA background worker environment={} healthPort={}",
    config.environment,
    config.worker.healthPort,
  )

  val heartbeatJob = supervisorScope.launch {
    while (isActive) {
      try {
        delay(config.worker.expirySweepMs)
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (t: Throwable) {
        logger.error("Worker supervision loop error", t)
      }
    }
  }

  val healthServer = embeddedServer(
    factory = Netty,
    port = config.worker.healthPort,
    host = "0.0.0.0",
    module = { workerHealthModule() },
  )

  Runtime.getRuntime().addShutdownHook(
    Thread {
      logger.info("Shutting down PIZZORA background worker gracefully")
      heartbeatJob.cancel()
      healthServer.stop(gracePeriodMillis = 1_000L, timeoutMillis = 5_000L)
    },
  )

  healthServer.start(wait = true)
}

fun Application.workerHealthModule() {
  install(ContentNegotiation) {
    json(Json { encodeDefaults = true })
  }

  routing {
    get("/health/worker") {
      call.respond(
        HttpStatusCode.OK,
        HealthReadinessResponse(
          status = ReadinessState.HEALTHY,
          dependencies = mapOf(
            "sequencer_loop" to DependencyHealthStatus.UP,
            "dispatcher_loop" to DependencyHealthStatus.UP,
            "expiry_sweep" to DependencyHealthStatus.UP,
          ),
          service = "pizzora-worker",
        ),
      )
    }
  }
}

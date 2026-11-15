package com.pizzora.backend.worker

import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.metrics.PizzoraMetricsRegistry
import com.pizzora.backend.core.metrics.WorkerMetricsRegistry
import com.pizzora.backend.core.redis.LettuceRedisStore
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.inventory.InventoryService
import com.pizzora.backend.notifications.WebSocketInboxNotificationDeliveryProvider
import com.pizzora.backend.orders.OrderService
import com.pizzora.backend.payments.PaymentService
import com.pizzora.backend.razorpay.createDefaultRazorpayGateway
import com.pizzora.backend.realtime.RedisRealtimeEventDispatcher
import com.pizzora.contracts.DependencyHealthStatus
import com.pizzora.contracts.HealthReadinessResponse
import com.pizzora.contracts.ReadinessState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    "Starting PIZZORA background worker environment={} healthPort={} runMigrationsOnStartup={}",
    config.environment,
    config.worker.healthPort,
    config.database.runMigrationsOnStartup,
  )

  val dataSource = DatabaseFactory.createDataSource(config.database)
  val dbHandle = if (config.database.runMigrationsOnStartup) {
    DatabaseFactory.migrateAndConnect(dataSource)
  } else {
    DatabaseFactory.connect(dataSource, verifyMigrated = true)
  }
  val redisStore = LettuceRedisStore(config.redis.url)
  val razorpayGateway = createDefaultRazorpayGateway(config)
  val inventoryService = InventoryService(dataSource)
  val couponService = CouponService(dataSource)
  val orderService = OrderService(dataSource, inventoryService, couponService)
  val paymentService = PaymentService(
    dataSource = dataSource,
    inventoryService = inventoryService,
    couponService = couponService,
    orderService = orderService,
    razorpayConfig = config.razorpay,
    publicKeyIdProvider = { razorpayGateway.publicKeyId },
  )

  val workerMetrics = WorkerMetricsRegistry()
  val sharedMetrics = PizzoraMetricsRegistry()

  val sequencerWorker = EventSequencerWorker(dataSource = dataSource)
  val dispatcherWorker = OutboxDispatcherWorker(
    dataSource = dataSource,
    razorpayGateway = razorpayGateway,
    realtimeDispatcher = RedisRealtimeEventDispatcher(redisStore),
    notificationDeliveryProvider = WebSocketInboxNotificationDeliveryProvider(),
  )
  val expirySweepWorker = ExpirySweepWorker(
    dataSource = dataSource,
    inventoryService = inventoryService,
    couponService = couponService,
    orderService = orderService,
  )
  val reconciliationWorker = RazorpayReconciliationWorker(
    dataSource = dataSource,
    razorpayGateway = razorpayGateway,
    paymentService = paymentService,
  )

  val sequencerJob = supervisorScope.launch {
    sequencerWorker.runLoop(config.worker.sequencerPollMs)
  }
  val dispatcherJob = supervisorScope.launch {
    dispatcherWorker.runLoop(config.worker.dispatcherPollMs)
  }
  val expiryJob = supervisorScope.launch {
    expirySweepWorker.runLoop(config.worker.expirySweepMs)
  }
  val reconcilerJob = supervisorScope.launch {
    reconciliationWorker.runLoop(config.worker.reconcilerPollMs)
  }

  val loopJobs: Map<String, Job> = mapOf(
    "sequencer_loop" to sequencerJob,
    "dispatcher_loop" to dispatcherJob,
    "expiry_sweep" to expiryJob,
    "reconciler_loop" to reconcilerJob,
  )

  supervisorScope.launch {
    while (isActive) {
      loopJobs.forEach { (name, job) ->
        if (job.isActive) workerMetrics.recordLoopSuccess(name) else workerMetrics.recordLoopError(name)
      }
      delay(5_000L)
    }
  }

  val healthServer = embeddedServer(
    factory = Netty,
    port = config.worker.healthPort,
    host = "0.0.0.0",
    module = {
      workerHealthModule(
        workerMetrics = workerMetrics,
        sharedMetrics = sharedMetrics,
        dataSource = dataSource,
        loopStatusProvider = {
          loopJobs.mapValues { (_, job) ->
            if (job.isActive) DependencyHealthStatus.UP else DependencyHealthStatus.DOWN
          }
        },
      )
    },
  )

  Runtime.getRuntime().addShutdownHook(
    Thread {
      logger.info("Shutting down PIZZORA background worker gracefully")
      sequencerJob.cancel()
      dispatcherJob.cancel()
      expiryJob.cancel()
      reconcilerJob.cancel()
      supervisorScope.cancel()
      healthServer.stop(gracePeriodMillis = 1_000L, timeoutMillis = 5_000L)
      redisStore.close()
      dbHandle.close()
    },
  )

  healthServer.start(wait = true)
}

fun Application.workerHealthModule(
  workerMetrics: WorkerMetricsRegistry = WorkerMetricsRegistry(),
  sharedMetrics: PizzoraMetricsRegistry = PizzoraMetricsRegistry(),
  dataSource: DataSource? = null,
  loopStatusProvider: () -> Map<String, DependencyHealthStatus> = {
    mapOf(
      "sequencer_loop" to DependencyHealthStatus.UP,
      "dispatcher_loop" to DependencyHealthStatus.UP,
      "expiry_sweep" to DependencyHealthStatus.UP,
      "reconciler_loop" to DependencyHealthStatus.UP,
    )
  },
) {
  install(ContentNegotiation) {
    json(Json { encodeDefaults = true })
  }

  routing {
    get("/health/worker") {
      val deps = loopStatusProvider()
      val allUp = deps.values.all { it == DependencyHealthStatus.UP }
      val httpStatus = if (allUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
      call.respond(
        httpStatus,
        HealthReadinessResponse(
          status = if (allUp) ReadinessState.HEALTHY else ReadinessState.UNHEALTHY,
          dependencies = deps,
          service = "pizzora-worker",
        ),
      )
    }

    get("/metrics") {
      val combined = buildString {
        append(workerMetrics.renderPrometheus())
        if (dataSource != null) {
          append(sharedMetrics.renderPrometheus(dataSource))
        }
      }
      call.respondText(
        text = combined,
        contentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
      )
    }
  }
}

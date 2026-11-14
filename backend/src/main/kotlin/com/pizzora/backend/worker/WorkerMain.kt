package com.pizzora.backend.worker

import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.db.DatabaseFactory
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

  val dataSource = DatabaseFactory.createDataSource(config.database)
  val dbHandle = DatabaseFactory.migrateAndConnect(dataSource)
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

  val healthServer = embeddedServer(
    factory = Netty,
    port = config.worker.healthPort,
    host = "0.0.0.0",
    module = { workerHealthModule() },
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

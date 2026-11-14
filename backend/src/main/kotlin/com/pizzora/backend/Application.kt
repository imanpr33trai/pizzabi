package com.pizzora.backend

import com.pizzora.backend.auth.AuthService
import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.auth.WsTicketService
import com.pizzora.backend.auth.authRoutes
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.cart.CartService
import com.pizzora.backend.cart.cartRoutes
import com.pizzora.backend.catalog.CatalogService
import com.pizzora.backend.catalog.catalogRoutes
import com.pizzora.backend.categories.CategoriesService
import com.pizzora.backend.categories.categoriesRoutes
import com.pizzora.backend.checkout.CheckoutService
import com.pizzora.backend.checkout.checkoutRoutes
import com.pizzora.backend.config.RestaurantConfigService
import com.pizzora.backend.config.restaurantConfigRoutes
import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.db.PostgresJdbcProbe
import com.pizzora.backend.core.email.EmailDeliveryProvider
import com.pizzora.backend.core.email.SmtpEmailDeliveryProvider
import com.pizzora.backend.core.error.installErrorHandling
import com.pizzora.backend.core.health.DependencyProbe
import com.pizzora.backend.core.health.HealthService
import com.pizzora.backend.core.health.TcpDependencyProbe
import com.pizzora.backend.core.logging.installStructuredLogging
import com.pizzora.backend.core.openapi.OpenApiSpec
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.core.redis.LettuceRedisStore
import com.pizzora.backend.core.redis.ResilientRateLimiter
import com.pizzora.backend.core.security.JwtTokenProvider
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.coupons.couponsRoutes
import com.pizzora.backend.images.ObjectStorageClient
import com.pizzora.backend.images.ProductImagesService
import com.pizzora.backend.images.S3ObjectStorageClient
import com.pizzora.backend.images.productImagesRoutes
import com.pizzora.backend.inventory.InventoryService
import com.pizzora.backend.inventory.inventoryRoutes
import com.pizzora.backend.kitchen.KitchenService
import com.pizzora.backend.kitchen.kitchenRoutes
import com.pizzora.backend.notifications.NotificationService
import com.pizzora.backend.notifications.notificationsRoutes
import com.pizzora.backend.orders.OrderService
import com.pizzora.backend.orders.ordersRoutes
import com.pizzora.backend.owner.OwnerService
import com.pizzora.backend.owner.ownerRoutes
import com.pizzora.backend.payments.PaymentService
import com.pizzora.backend.payments.paymentsRoutes
import com.pizzora.backend.pricing.PricingService
import com.pizzora.backend.pricing.pricingRoutes
import com.pizzora.backend.razorpay.RazorpayGateway
import com.pizzora.backend.razorpay.WebhookService
import com.pizzora.backend.razorpay.createDefaultRazorpayGateway
import com.pizzora.backend.realtime.RealtimeEventService
import com.pizzora.backend.realtime.RealtimeHub
import com.pizzora.backend.realtime.realtimeRoutes
import com.pizzora.backend.recipes.RecipeService
import com.pizzora.backend.recipes.recipesRoutes
import com.pizzora.backend.slots.ScheduledSlotService
import com.pizzora.backend.slots.scheduledSlotRoutes
import com.pizzora.backend.users.UsersService
import com.pizzora.backend.users.usersRoutes
import com.pizzora.backend.zones.DeliveryZoneService
import com.pizzora.backend.zones.deliveryZoneRoutes
import com.pizzora.contracts.ApiErrorDetail
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.DependencyHealthStatus
import com.pizzora.contracts.HealthReadinessResponse
import com.pizzora.contracts.ReadinessState
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
  val config = AppConfig.fromEnv()
  val logger = LoggerFactory.getLogger("com.pizzora.backend.Application")

  val dataSource = DatabaseFactory.createDataSource(config.database)
  val dbHandle = DatabaseFactory.migrateAndConnect(dataSource)

  if ("--bootstrap-owner" in args) {
    val env = System.getenv()
    val email = env["OWNER_EMAIL"] ?: error("OWNER_EMAIL env variable is required for --bootstrap-owner")
    val password = env["OWNER_PASSWORD"] ?: error("OWNER_PASSWORD env variable is required for --bootstrap-owner")
    val fullName = env["OWNER_NAME"] ?: "PIZZORA Owner"
    val phone = env["OWNER_PHONE"] ?: "9999999999"
    runBlocking {
      BootstrapOwner.bootstrap(
        dataSource = dataSource,
        passwordHasher = PasswordHasher(),
        email = email,
        password = password,
        fullName = fullName,
        phone = phone,
      )
    }
    dbHandle.close()
    return
  }

  val redisStore = LettuceRedisStore(config.redis.url)
  Runtime.getRuntime().addShutdownHook(
    Thread {
      redisStore.close()
      dbHandle.close()
    },
  )

  val runtimeProbes = listOf(
    PostgresJdbcProbe(dataSource),
    TcpDependencyProbe(name = "redis", required = false, host = config.redis.host, port = config.redis.port),
    TcpDependencyProbe(name = "minio", required = false, host = config.storage.host, port = config.storage.port),
  )

  logger.info("Starting PIZZORA backend API environment={} port={}", config.environment, config.httpPort)
  embeddedServer(
    factory = Netty,
    port = config.httpPort,
    host = "0.0.0.0",
    module = {
      pizzoraModule(
        config = config,
        probes = runtimeProbes,
        dataSource = dataSource,
        cacheStore = redisStore,
        emailDeliveryProvider = SmtpEmailDeliveryProvider(config.smtp),
      )
    },
  ).start(wait = true)
}

fun Application.pizzoraModule(
  config: AppConfig = AppConfig.fromEnv(),
  probes: List<DependencyProbe> = HealthService.defaultProbes(config),
  dataSource: DataSource? = null,
  cacheStore: EphemeralCacheStore? = null,
  emailDeliveryProvider: EmailDeliveryProvider? = null,
  objectStorageClient: ObjectStorageClient? = null,
  razorpayGateway: RazorpayGateway? = null,
  realtimeHub: RealtimeHub? = null,
  passwordHasher: PasswordHasher = PasswordHasher(),
  extraRoutes: Routing.() -> Unit = {},
) {
  val healthService = HealthService(probes)
  val effectiveCacheStore = cacheStore ?: NoOpCacheStore
  val jwtTokenProvider = JwtTokenProvider(config.auth)
  val authorizationService = dataSource?.let { AuthorizationService(it, effectiveCacheStore) }
  val wsTicketService = dataSource?.let { WsTicketService(it, effectiveCacheStore, config.auth) }

  install(DefaultHeaders) {
    header(HttpHeaders.CacheControl, "no-store")
    header("X-Content-Type-Options", "nosniff")
  }

  install(ContentNegotiation) {
    json(
      Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
      },
    )
  }

  install(WebSockets) {
    pingPeriod = 15.seconds
    timeout = 30.seconds
    maxFrameSize = Long.MAX_VALUE
  }

  installStructuredLogging()
  installErrorHandling()

  if (authorizationService != null) {
    install(Authentication) {
      bearer("auth-jwt") {
        authenticate { credential ->
          val claims = jwtTokenProvider.verifyAccessToken(credential.token) ?: return@authenticate null
          val active = authorizationService.isSessionActive(claims.userId, claims.sessionId)
          if (!active) return@authenticate null
          PizzoraPrincipal(
            userId = claims.userId,
            sessionId = claims.sessionId,
            role = claims.role,
          )
        }
      }

      register(
        object : AuthenticationProvider(object : Config("auth-ws") {}) {
          override suspend fun onAuthenticate(context: AuthenticationContext) {
            val call = context.call
            val ticket = call.request.queryParameters["ticket"]?.trim()?.takeIf { it.isNotEmpty() }
            val bearer = call.request.headers[HttpHeaders.Authorization]
              ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
              ?.substring(7)
              ?.trim()
              ?.takeIf { it.isNotEmpty() }

            val principal: PizzoraPrincipal? = when {
              ticket != null -> wsTicketService?.consumeTicket(ticket)
              bearer != null -> {
                val claims = jwtTokenProvider.verifyAccessToken(bearer)
                if (claims != null && authorizationService.isSessionActive(claims.userId, claims.sessionId)) {
                  PizzoraPrincipal(claims.userId, claims.sessionId, claims.role)
                } else {
                  null
                }
              }
              else -> null
            }

            if (principal != null) {
              context.principal(principal)
            } else {
              context.challenge("WsAuth", AuthenticationFailedCause.InvalidCredentials) { challenge, challengeCall ->
                challengeCall.respond(
                  HttpStatusCode.Unauthorized,
                  ApiErrorEnvelope(
                    error = ApiErrorDetail(
                      code = "UNAUTHORIZED",
                      message = "Valid ws-ticket or Bearer access token is required to connect to /ws/v1/events.",
                    ),
                    correlationId = challengeCall.callId ?: UUID.randomUUID().toString(),
                    timestamp = Instant.now().toString(),
                  ),
                )
                challenge.complete()
              }
            }
          }
        },
      )
    }
  }

  routing {
    get("/api/v1/health/live") {
      call.respond(
        HttpStatusCode.OK,
        HealthReadinessResponse(
          status = ReadinessState.HEALTHY,
          dependencies = mapOf("server" to DependencyHealthStatus.UP),
        ),
      )
    }

    get("/api/v1/health/ready") {
      val evaluation = healthService.checkReadiness()
      call.respond(evaluation.httpStatus, evaluation.body)
    }

    get("/api/v1/openapi.json") {
      call.respond(HttpStatusCode.OK, OpenApiSpec.buildDocument())
    }

    if (dataSource != null && authorizationService != null) {
      val emailProvider = emailDeliveryProvider ?: SmtpEmailDeliveryProvider(config.smtp)
      val rateLimiter = ResilientRateLimiter(effectiveCacheStore)
      val authService = AuthService(
        dataSource = dataSource,
        passwordHasher = passwordHasher,
        jwtTokenProvider = jwtTokenProvider,
        emailDeliveryProvider = emailProvider,
        authConfig = config.auth,
      )
      val resolvedWsTicketService = wsTicketService ?: WsTicketService(
        dataSource = dataSource,
        cacheStore = effectiveCacheStore,
        authConfig = config.auth,
      )
      val usersService = UsersService(
        dataSource = dataSource,
        passwordHasher = passwordHasher,
      )

      val storageClient = objectStorageClient ?: S3ObjectStorageClient(config.storage)
      val categoriesService = CategoriesService(
        dataSource = dataSource,
        cacheStore = effectiveCacheStore,
      )
      val catalogService = CatalogService(
        dataSource = dataSource,
        cacheStore = effectiveCacheStore,
        publicUrlResolver = storageClient::publicUrlFor,
      )
      val productImagesService = ProductImagesService(
        dataSource = dataSource,
        objectStorageClient = storageClient,
        cacheStore = effectiveCacheStore,
      )
      val cartService = CartService(
        dataSource = dataSource,
        publicUrlResolver = storageClient::publicUrlFor,
      )
      val couponService = CouponService(
        dataSource = dataSource,
      )
      val pricingService = PricingService(
        dataSource = dataSource,
        couponService = couponService,
        publicUrlResolver = storageClient::publicUrlFor,
      )
      val inventoryService = InventoryService(
        dataSource = dataSource,
      )
      val recipeService = RecipeService(
        dataSource = dataSource,
      )
      val orderService = OrderService(
        dataSource = dataSource,
        inventoryService = inventoryService,
        couponService = couponService,
      )
      val kitchenService = KitchenService(
        dataSource = dataSource,
        orderService = orderService,
      )
      val checkoutService = CheckoutService(
        dataSource = dataSource,
        couponService = couponService,
        inventoryService = inventoryService,
        recipeService = recipeService,
        orderService = orderService,
      )
      val effectiveRazorpayGateway = razorpayGateway ?: createDefaultRazorpayGateway(config)
      val paymentService = PaymentService(
        dataSource = dataSource,
        inventoryService = inventoryService,
        couponService = couponService,
        orderService = orderService,
        razorpayConfig = config.razorpay,
        publicKeyIdProvider = { effectiveRazorpayGateway.publicKeyId },
      )
      val webhookService = WebhookService(
        dataSource = dataSource,
        paymentService = paymentService,
        razorpayConfig = config.razorpay,
      )
      val effectiveRealtimeHub = (realtimeHub ?: RealtimeHub(effectiveCacheStore)).also {
        it.startPubSubListenerIfConfigured()
      }
      monitor.subscribe(ApplicationStopped) {
        effectiveRealtimeHub.close()
      }
      val realtimeEventService = RealtimeEventService(dataSource)
      val restaurantConfigService = RestaurantConfigService(
        dataSource = dataSource,
        cacheStore = effectiveCacheStore,
      )
      val ownerService = OwnerService(
        dataSource = dataSource,
        configService = restaurantConfigService,
        inventoryService = inventoryService,
      )
      val deliveryZoneService = DeliveryZoneService(dataSource = dataSource)
      val scheduledSlotService = ScheduledSlotService(dataSource = dataSource)
      val notificationService = NotificationService(dataSource = dataSource)

      authRoutes(authService, resolvedWsTicketService, rateLimiter)
      usersRoutes(usersService, authorizationService)
      restaurantConfigRoutes(restaurantConfigService, authorizationService)
      ownerRoutes(ownerService, authorizationService)
      deliveryZoneRoutes(deliveryZoneService, authorizationService)
      scheduledSlotRoutes(scheduledSlotService, authorizationService)
      notificationsRoutes(notificationService)
      categoriesRoutes(categoriesService, authorizationService)
      catalogRoutes(catalogService, authorizationService)
      productImagesRoutes(productImagesService, authorizationService)
      cartRoutes(cartService, authorizationService)
      couponsRoutes(couponService, authorizationService)
      pricingRoutes(pricingService, authorizationService)
      inventoryRoutes(inventoryService, authorizationService)
      recipesRoutes(recipeService, authorizationService)
      checkoutRoutes(checkoutService, authorizationService)
      ordersRoutes(orderService, authorizationService)
      kitchenRoutes(kitchenService, authorizationService)
      paymentsRoutes(paymentService, webhookService, authorizationService)
      realtimeRoutes(
        realtimeHub = effectiveRealtimeHub,
        realtimeEventService = realtimeEventService,
      )
    }

    extraRoutes()
  }
}

private object NoOpCacheStore : EphemeralCacheStore {
  override suspend fun setEx(key: String, ttlSeconds: Long, value: String): Boolean = false
  override suspend fun get(key: String): String? = null
  override suspend fun getDel(key: String): String? = null
  override suspend fun del(key: String): Boolean = false
  override suspend fun incrementWithinWindow(key: String, windowSeconds: Long): Long? = null
}

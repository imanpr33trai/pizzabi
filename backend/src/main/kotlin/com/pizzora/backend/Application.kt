package com.pizzora.backend

import com.pizzora.backend.auth.AuthService
import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.auth.WsTicketService
import com.pizzora.backend.auth.authRoutes
import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.catalog.CatalogService
import com.pizzora.backend.catalog.catalogRoutes
import com.pizzora.backend.categories.CategoriesService
import com.pizzora.backend.categories.categoriesRoutes
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
import com.pizzora.backend.images.ObjectStorageClient
import com.pizzora.backend.images.ProductImagesService
import com.pizzora.backend.images.S3ObjectStorageClient
import com.pizzora.backend.images.productImagesRoutes
import com.pizzora.backend.users.UsersService
import com.pizzora.backend.users.usersRoutes
import com.pizzora.contracts.ApiErrorDetail
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.DependencyHealthStatus
import com.pizzora.contracts.HealthReadinessResponse
import com.pizzora.contracts.ReadinessState
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
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
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
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
  passwordHasher: PasswordHasher = PasswordHasher(),
  extraRoutes: Routing.() -> Unit = {},
) {
  val healthService = HealthService(probes)
  val effectiveCacheStore = cacheStore ?: NoOpCacheStore
  val jwtTokenProvider = JwtTokenProvider(config.auth)
  val authorizationService = dataSource?.let { AuthorizationService(it, effectiveCacheStore) }

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
      val wsTicketService = WsTicketService(
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

      authRoutes(authService, wsTicketService, rateLimiter)
      usersRoutes(usersService, authorizationService)
      categoriesRoutes(categoriesService, authorizationService)
      catalogRoutes(catalogService, authorizationService)
      productImagesRoutes(productImagesService, authorizationService)
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

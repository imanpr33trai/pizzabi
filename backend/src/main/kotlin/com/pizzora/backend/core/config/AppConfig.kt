package com.pizzora.backend.core.config

import java.net.URI

data class AppConfig(
  val environment: String,
  val httpPort: Int,
  val database: DatabaseConfig,
  val redis: RedisConfig,
  val storage: StorageConfig,
  val smtp: SmtpConfig,
  val auth: AuthConfig,
  val worker: WorkerConfig,
  val razorpay: RazorpayConfig = RazorpayConfig(),
  val security: SecurityConfig = SecurityConfig(),
) {
  companion object {
    fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
      val dbUrl = env["DATABASE_URL"] ?: "jdbc:postgresql://localhost:5432/pizzora"
      val (pgHost, pgPort) = parseJdbcHostPort(dbUrl, defaultHost = "localhost", defaultPort = 5432)
      val redisUrl = env["REDIS_URL"] ?: "redis://localhost:6379"
      val (redisHost, redisPort) = parseUriHostPort(redisUrl, defaultHost = "localhost", defaultPort = 6379)
      val s3Endpoint = env["S3_ENDPOINT"] ?: "http://localhost:9000"
      val (s3Host, s3Port) = parseUriHostPort(s3Endpoint, defaultHost = "localhost", defaultPort = 9000)

      val environment = env["PIZZORA_ENV"] ?: "development"
      val isProduction = environment.equals("production", ignoreCase = true)
      val jwtSecret = env["JWT_SECRET"]?.takeIf { it.isNotBlank() }
        ?: if (isProduction) {
          error("JWT_SECRET must be set in production")
        } else {
          DEV_DEFAULT_JWT_SECRET
        }

      val dbPassword = env["DATABASE_PASSWORD"] ?: "pizzora_dev_password"
      val s3SecretKey = env["S3_SECRET_KEY"] ?: "pizzora_minio_dev_secret"
      if (isProduction) {
        require(jwtSecret.length >= 32 && jwtSecret !in DISALLOWED_PRODUCTION_SECRETS) {
          "JWT_SECRET must be at least 32 characters and not a development placeholder in production"
        }
        require(dbPassword.isNotBlank() && dbPassword != "pizzora_dev_password") {
          "DATABASE_PASSWORD must not use the development default in production"
        }
        require(s3SecretKey.isNotBlank() && s3SecretKey != "pizzora_minio_dev_secret") {
          "S3_SECRET_KEY must not use the development default in production"
        }
      }

      return AppConfig(
        environment = environment,
        httpPort = env["PORT"]?.toIntOrNull() ?: 8080,
        database = DatabaseConfig(
          jdbcUrl = dbUrl,
          username = env["DATABASE_USER"] ?: "pizzora",
          password = dbPassword,
          host = pgHost,
          port = pgPort,
          maxPoolSize = env["DATABASE_MAX_POOL_SIZE"]?.toIntOrNull() ?: 20,
        ),
        redis = RedisConfig(
          url = redisUrl,
          host = redisHost,
          port = redisPort,
        ),
        storage = StorageConfig(
          endpoint = s3Endpoint,
          host = s3Host,
          port = s3Port,
          bucket = env["S3_BUCKET"] ?: "pizzora-product-images",
          region = env["S3_REGION"] ?: "ap-south-1",
          accessKey = env["S3_ACCESS_KEY"] ?: "pizzora_minio_admin",
          secretKey = s3SecretKey,
          publicBaseUrl = env["S3_PUBLIC_URL"]?.trimEnd('/')
            ?: "${s3Endpoint.trimEnd('/')}/${env["S3_BUCKET"] ?: "pizzora-product-images"}",
        ),
        smtp = SmtpConfig(
          host = env["SMTP_HOST"] ?: "localhost",
          port = env["SMTP_PORT"]?.toIntOrNull() ?: 1025,
          fromAddress = env["SMTP_FROM"] ?: "no-reply@pizzora.local",
        ),
        auth = AuthConfig(
          jwtSecret = jwtSecret,
          jwtIssuer = env["JWT_ISSUER"] ?: "pizzora-api",
          accessTokenTtlSeconds = env["JWT_ACCESS_TTL_SECONDS"]?.toLongOrNull() ?: 900L,
          refreshTokenTtlDays = env["REFRESH_TOKEN_TTL_DAYS"]?.toLongOrNull() ?: 30L,
          wsTicketTtlSeconds = env["WS_TICKET_TTL_SECONDS"]?.toLongOrNull() ?: 30L,
        ),
        worker = WorkerConfig(
          healthPort = env["WORKER_HEALTH_PORT"]?.toIntOrNull() ?: 8081,
          sequencerPollMs = env["WORKER_SEQUENCER_POLL_MS"]?.toLongOrNull() ?: 150L,
          dispatcherPollMs = env["WORKER_DISPATCHER_POLL_MS"]?.toLongOrNull() ?: 150L,
          expirySweepMs = env["WORKER_EXPIRY_SWEEP_MS"]?.toLongOrNull() ?: 15_000L,
          reconcilerPollMs = env["WORKER_RECONCILER_POLL_MS"]?.toLongOrNull() ?: 30_000L,
        ),
        razorpay = RazorpayConfig(
          keyId = env["RAZORPAY_KEY_ID"] ?: "",
          keySecret = env["RAZORPAY_KEY_SECRET"] ?: "",
          webhookSecret = env["RAZORPAY_WEBHOOK_SECRET"] ?: "",
          baseUrl = (env["RAZORPAY_BASE_URL"] ?: "https://api.razorpay.com/v1").trimEnd('/'),
        ),
        security = SecurityConfig(
          maxJsonBodyBytes = env["MAX_JSON_BODY_BYTES"]?.toLongOrNull() ?: 262_144L,
          maxWebhookBodyBytes = env["MAX_WEBHOOK_BODY_BYTES"]?.toLongOrNull() ?: 524_288L,
          maxWsFrameBytes = env["MAX_WS_FRAME_BYTES"]?.toLongOrNull() ?: 65_536L,
          rateLimitEnabled = env["RATE_LIMIT_ENABLED"]?.toBooleanStrictOrNull() ?: true,
        ),
      )
    }

    private const val DEV_DEFAULT_JWT_SECRET = "pizzora-dev-only-hmac-secret-minimum-32-bytes"
    private val DISALLOWED_PRODUCTION_SECRETS = setOf(
      DEV_DEFAULT_JWT_SECRET,
      "dev-only-change-before-production-32bytes",
      "changeme",
      "secret",
    )

    private fun parseJdbcHostPort(jdbcUrl: String, defaultHost: String, defaultPort: Int): Pair<String, Int> {
      val stripped = jdbcUrl.removePrefix("jdbc:")
      return parseUriHostPort(stripped, defaultHost, defaultPort)
    }

    private fun parseUriHostPort(rawUri: String, defaultHost: String, defaultPort: Int): Pair<String, Int> {
      return runCatching {
        val uri = URI(rawUri)
        val host = uri.host ?: defaultHost
        val port = if (uri.port > 0) uri.port else defaultPort
        host to port
      }.getOrElse { defaultHost to defaultPort }
    }
  }
}

data class DatabaseConfig(
  val jdbcUrl: String,
  val username: String,
  val password: String,
  val host: String,
  val port: Int,
  val maxPoolSize: Int,
)

data class RedisConfig(
  val url: String,
  val host: String,
  val port: Int,
)

data class StorageConfig(
  val endpoint: String,
  val host: String,
  val port: Int,
  val bucket: String,
  val region: String,
  val accessKey: String = "pizzora_minio_admin",
  val secretKey: String = "pizzora_minio_dev_secret",
  val publicBaseUrl: String = "${endpoint.trimEnd('/')}/$bucket",
)

data class SmtpConfig(
  val host: String,
  val port: Int,
  val fromAddress: String,
)

data class AuthConfig(
  val jwtSecret: String,
  val jwtIssuer: String,
  val accessTokenTtlSeconds: Long,
  val refreshTokenTtlDays: Long,
  val wsTicketTtlSeconds: Long,
)

data class WorkerConfig(
  val healthPort: Int,
  val sequencerPollMs: Long,
  val dispatcherPollMs: Long,
  val expirySweepMs: Long,
  val reconcilerPollMs: Long = 30_000L,
)

data class RazorpayConfig(
  val keyId: String = "",
  val keySecret: String = "",
  val webhookSecret: String = "",
  val baseUrl: String = "https://api.razorpay.com/v1",
)

data class SecurityConfig(
  val maxJsonBodyBytes: Long = 262_144L,
  val maxWebhookBodyBytes: Long = 524_288L,
  val maxWsFrameBytes: Long = 65_536L,
  val rateLimitEnabled: Boolean = true,
  val authVerifyPerMinute: Int = 15,
  val authResetPerMinute: Int = 10,
  val authRefreshPerMinute: Int = 30,
  val authWsTicketPerMinute: Int = 30,
  val checkoutPaymentPerMinute: Int = 80,
  val webhooksPerMinute: Int = 120,
  val opsPerMinute: Int = 240,
  val publicPerMinute: Int = 300,
  val apiDefaultPerMinute: Int = 240,
)

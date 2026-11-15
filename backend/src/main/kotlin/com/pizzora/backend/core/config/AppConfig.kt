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
      val environment = env["PIZZORA_ENV"] ?: "development"
      val isProduction = environment.equals("production", ignoreCase = true)
      val jwtSecret = env["JWT_SECRET"]?.takeIf { it.isNotBlank() }
        ?: if (isProduction) {
          error("JWT_SECRET must be set in production")
        } else {
          DEV_DEFAULT_JWT_SECRET
        }

      val dbPassword = env["DATABASE_PASSWORD"] ?: "pizzora_dev_password"
      if (isProduction) {
        require(jwtSecret.length >= 32 && jwtSecret !in DISALLOWED_PRODUCTION_SECRETS) {
          "JWT_SECRET must be at least 32 characters and not a development placeholder in production"
        }
        require(dbPassword.isNotBlank() && dbPassword != "pizzora_dev_password") {
          "DATABASE_PASSWORD must not use the development default in production"
        }
      }

      val storage = resolveStorageConfig(env = env, isProduction = isProduction)
      val runMigrationsOnStartup = env["RUN_MIGRATIONS_ON_STARTUP"]?.toBooleanStrictOrNull() ?: !isProduction

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
          runMigrationsOnStartup = runMigrationsOnStartup,
        ),
        redis = RedisConfig(
          url = redisUrl,
          host = redisHost,
          port = redisPort,
        ),
        storage = storage,
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

    private fun resolveStorageConfig(env: Map<String, String>, isProduction: Boolean): StorageConfig {
      val rawS3Enabled = env["S3_ENABLED"]?.trim()?.takeIf { it.isNotEmpty() }
      val explicitS3Enabled = rawS3Enabled?.let { raw ->
        when (raw.lowercase()) {
          "true" -> true
          "false" -> false
          else -> throw IllegalArgumentException("S3_ENABLED must be 'true' or 'false', got '$raw'")
        }
      }

      if (explicitS3Enabled == false || (explicitS3Enabled == null && isProduction)) {
        return StorageConfig(enabled = false)
      }

      if (explicitS3Enabled == true) {
        return buildValidatedStorageConfig(
          endpoint = env["S3_ENDPOINT"].orEmpty(),
          bucket = env["S3_BUCKET"].orEmpty(),
          region = env["S3_REGION"].orEmpty(),
          accessKey = env["S3_ACCESS_KEY"].orEmpty(),
          secretKey = env["S3_SECRET_KEY"].orEmpty(),
          publicUrl = env["S3_PUBLIC_URL"],
          rejectDevDefaults = isProduction,
        )
      }

      // Development mode with S3_ENABLED not specified: enabled by default only if local S3 config is valid.
      return runCatching {
        buildValidatedStorageConfig(
          endpoint = env["S3_ENDPOINT"] ?: "http://localhost:9000",
          bucket = env["S3_BUCKET"] ?: "pizzora-product-images",
          region = env["S3_REGION"] ?: "ap-south-1",
          accessKey = env["S3_ACCESS_KEY"] ?: "pizzora_minio_admin",
          secretKey = env["S3_SECRET_KEY"] ?: "pizzora_minio_dev_secret",
          publicUrl = env["S3_PUBLIC_URL"],
          rejectDevDefaults = false,
        )
      }.getOrElse {
        StorageConfig(enabled = false)
      }
    }

    private fun buildValidatedStorageConfig(
      endpoint: String,
      bucket: String,
      region: String,
      accessKey: String,
      secretKey: String,
      publicUrl: String?,
      rejectDevDefaults: Boolean,
    ): StorageConfig {
      val cleanEndpoint = endpoint.trim().trimEnd('/')
      val endpointUri = parseValidatedHttpUri("S3_ENDPOINT", cleanEndpoint)

      val cleanBucket = bucket.trim()
      require(cleanBucket.isNotEmpty() && !containsPlaceholder(cleanBucket)) {
        "S3_BUCKET must be configured with a valid non-placeholder bucket name when S3_ENABLED=true"
      }

      val cleanRegion = region.trim()
      require(cleanRegion.isNotEmpty() && !containsPlaceholder(cleanRegion)) {
        "S3_REGION must be configured with a valid non-placeholder region when S3_ENABLED=true"
      }

      val cleanAccessKey = accessKey.trim()
      require(cleanAccessKey.isNotEmpty() && !containsPlaceholder(cleanAccessKey)) {
        "S3_ACCESS_KEY must be configured when S3_ENABLED=true"
      }

      val cleanSecretKey = secretKey.trim()
      require(cleanSecretKey.isNotEmpty() && !containsPlaceholder(cleanSecretKey)) {
        "S3_SECRET_KEY must be configured when S3_ENABLED=true"
      }

      if (rejectDevDefaults) {
        require(cleanAccessKey != "pizzora_minio_admin") {
          "S3_ACCESS_KEY must not use the development default in production when S3_ENABLED=true"
        }
        require(cleanSecretKey != "pizzora_minio_dev_secret") {
          "S3_SECRET_KEY must not use the development default in production when S3_ENABLED=true"
        }
      }

      val resolvedPublicUrl = publicUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { rawPublic ->
        parseValidatedHttpUri("S3_PUBLIC_URL", rawPublic.trimEnd('/'))
        rawPublic.trimEnd('/')
      } ?: "$cleanEndpoint/$cleanBucket"

      val host = endpointUri.host
      val port = if (endpointUri.port > 0) {
        endpointUri.port
      } else if (endpointUri.scheme.equals("https", ignoreCase = true)) {
        443
      } else {
        80
      }

      return StorageConfig(
        endpoint = cleanEndpoint,
        host = host,
        port = port,
        bucket = cleanBucket,
        region = cleanRegion,
        accessKey = cleanAccessKey,
        secretKey = cleanSecretKey,
        publicBaseUrl = resolvedPublicUrl,
        enabled = true,
      )
    }

    private fun parseValidatedHttpUri(varName: String, raw: String): URI {
      require(raw.isNotEmpty() && !containsPlaceholder(raw)) {
        "$varName must be configured with a valid http/https URL when S3_ENABLED=true"
      }
      val uri = runCatching { URI(raw) }.getOrElse { ex ->
        throw IllegalArgumentException("$varName is not a valid URI: ${ex.message}", ex)
      }
      val scheme = uri.scheme?.lowercase()
      require(scheme == "http" || scheme == "https") {
        "$varName must use http or https scheme when S3_ENABLED=true"
      }
      require(!uri.host.isNullOrBlank()) {
        "$varName must include a valid host when S3_ENABLED=true"
      }
      return uri
    }

    private fun containsPlaceholder(value: String): Boolean =
      '<' in value || '>' in value

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
  val runMigrationsOnStartup: Boolean = true,
)

data class RedisConfig(
  val url: String,
  val host: String,
  val port: Int,
)

data class StorageConfig(
  val endpoint: String = "",
  val host: String = "",
  val port: Int = 0,
  val bucket: String = "",
  val region: String = "",
  val accessKey: String = "pizzora_minio_admin",
  val secretKey: String = "pizzora_minio_dev_secret",
  val publicBaseUrl: String = "${endpoint.trimEnd('/')}/$bucket",
  val enabled: Boolean = true,
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

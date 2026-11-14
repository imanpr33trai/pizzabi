package com.pizzora.backend.core.security

import com.pizzora.backend.core.config.SecurityConfig
import com.pizzora.backend.core.error.PayloadTooLargeException
import com.pizzora.backend.core.error.TooManyRequestsException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.core.redis.ResilientRateLimiter
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory

private val SAFE_OBJECT_KEY_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9/._-]{0,254}$")

object StorageKeyValidator {
  fun validateObjectKey(rawKey: String): String {
    if (rawKey.isBlank() || rawKey != rawKey.trim()) {
      throw ValidationException("INVALID_OBJECT_KEY", "Object key cannot be empty or contain surrounding whitespace.")
    }
    val lower = rawKey.lowercase()
    if (rawKey.startsWith('/') ||
      rawKey.contains('\\') ||
      rawKey.contains('\u0000') ||
      rawKey.contains("//") ||
      rawKey.contains("..") ||
      lower.contains("%2e") ||
      lower.contains("%2f") ||
      lower.contains("%5c") ||
      !SAFE_OBJECT_KEY_REGEX.matches(rawKey)
    ) {
      throw ValidationException("INVALID_OBJECT_KEY", "Object key contains prohibited path traversal or unsafe characters.")
    }
    return rawKey
  }
}

fun Application.installSecurityHeaders(isProduction: Boolean) {
  install(DefaultHeaders) {
    header(HttpHeaders.CacheControl, "no-store")
    header(HttpHeaders.Pragma, "no-cache")
    header("X-Content-Type-Options", "nosniff")
    header("X-Frame-Options", "DENY")
    header("Referrer-Policy", "no-referrer")
    header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
    header("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
    header("Cross-Origin-Resource-Policy", "same-origin")
    if (isProduction) {
      header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
    }
  }
}

fun Application.installBodySizeLimits(securityConfig: SecurityConfig) {
  val logger = LoggerFactory.getLogger("com.pizzora.backend.core.security.BodySizeLimit")
  val plugin = createApplicationPlugin("PizzoraBodySizeLimit") {
    onCall { call ->
      val limit = maxBodyBytesForPath(call.request.path(), securityConfig)
      val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
      if (contentLength != null && contentLength > limit) {
        logger.warn(
          "Rejected oversized request Content-Length={} limit={} path={} ip={}",
          contentLength,
          limit,
          call.request.path(),
          call.request.origin.remoteAddress,
        )
        throw PayloadTooLargeException(
          message = "Request body ($contentLength bytes) exceeds limit of $limit bytes.",
          details = mapOf("max_bytes" to limit.toString()),
        )
      }
    }

    onCallReceive { call ->
      val limit = maxBodyBytesForPath(call.request.path(), securityConfig)
      transformBody { data ->
        if (data !is ByteReadChannel) {
          return@transformBody data
        }
        val packet = data.readRemaining(limit + 1L)
        val bytes = packet.readByteArray()
        if (bytes.size.toLong() > limit) {
          logger.warn(
            "Rejected streamed oversized request readBytes>{} path={} ip={}",
            limit,
            call.request.path(),
            call.request.origin.remoteAddress,
          )
          throw PayloadTooLargeException(
            message = "Request body exceeds maximum allowed size of $limit bytes.",
            details = mapOf("max_bytes" to limit.toString()),
          )
        }
        ByteReadChannel(bytes)
      }
    }
  }
  install(plugin)
}

fun Application.installRouteGroupRateLimiting(
  rateLimiter: ResilientRateLimiter,
  securityConfig: SecurityConfig,
) {
  if (!securityConfig.rateLimitEnabled) return
  val logger = LoggerFactory.getLogger("com.pizzora.backend.core.security.RouteRateLimit")
  val plugin = createApplicationPlugin("PizzoraRouteGroupRateLimit") {
    onCall { call ->
      val path = call.request.path()
      val policy = resolveRateLimitPolicy(path, securityConfig) ?: return@onCall
      val ip = call.request.origin.remoteAddress
      val key = "group:${policy.group}:$ip"
      if (!rateLimiter.isAllowed(key, maxRequests = policy.maxRequestsPerMinute, windowSeconds = 60L)) {
        logger.warn(
          "Rate limit exceeded group={} path={} ip={} limitPerMin={}",
          policy.group,
          path,
          ip,
          policy.maxRequestsPerMinute,
        )
        throw TooManyRequestsException(
          message = "Too many requests for ${policy.group}. Please retry shortly.",
          details = mapOf("group" to policy.group, "retry_after_seconds" to "60"),
        )
      }
    }
  }
  install(plugin)
}

private data class RouteRateLimitPolicy(
  val group: String,
  val maxRequestsPerMinute: Int,
)

private fun maxBodyBytesForPath(path: String, config: SecurityConfig): Long =
  if (path.startsWith("/api/v1/webhooks/")) config.maxWebhookBodyBytes else config.maxJsonBodyBytes

private fun resolveRateLimitPolicy(path: String, config: SecurityConfig): RouteRateLimitPolicy? =
  when {
    path == "/api/v1/health/live" || path == "/api/v1/health/ready" || path == "/api/v1/openapi.json" -> null
    // Existing fine-grained limits enforced directly inside AuthRoutes.kt
    path == "/api/v1/auth/register" ||
      path == "/api/v1/auth/resend-verification" ||
      path == "/api/v1/auth/login" ||
      path == "/api/v1/auth/forgot-password" -> null
    path == "/api/v1/auth/verify-email" -> RouteRateLimitPolicy("auth_verify", config.authVerifyPerMinute)
    path == "/api/v1/auth/reset-password" -> RouteRateLimitPolicy("auth_reset", config.authResetPerMinute)
    path == "/api/v1/auth/refresh" -> RouteRateLimitPolicy("auth_refresh", config.authRefreshPerMinute)
    path == "/api/v1/auth/ws-ticket" -> RouteRateLimitPolicy("auth_ws_ticket", config.authWsTicketPerMinute)
    path.startsWith("/api/v1/webhooks/") -> RouteRateLimitPolicy("webhooks", config.webhooksPerMinute)
    path == "/api/v1/checkout" ||
      path.startsWith("/api/v1/payments/") ||
      (path.startsWith("/api/v1/orders/") && path.contains("/payments/")) ->
      RouteRateLimitPolicy("checkout_payments", config.checkoutPaymentPerMinute)
    path.startsWith("/api/v1/owner/") || path.startsWith("/api/v1/kds/") ->
      RouteRateLimitPolicy("ops", config.opsPerMinute)
    path.startsWith("/api/v1/catalog/") ||
      path == "/api/v1/config/public" ||
      path == "/api/v1/delivery-zones/check" ||
      path.startsWith("/api/v1/slots") ->
      RouteRateLimitPolicy("public", config.publicPerMinute)
    path.startsWith("/api/v1/") -> RouteRateLimitPolicy("api", config.apiDefaultPerMinute)
    else -> null
  }

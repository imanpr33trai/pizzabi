package com.pizzora.backend.core.error

import com.pizzora.contracts.ApiErrorDetail
import com.pizzora.contracts.ApiErrorEnvelope
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory

sealed class AppException(
  val statusCode: HttpStatusCode,
  val code: String,
  override val message: String,
  val details: Map<String, String> = emptyMap(),
  cause: Throwable? = null,
) : RuntimeException(message, cause)

class ValidationException(
  code: String = "VALIDATION_ERROR",
  message: String,
  details: Map<String, String> = emptyMap(),
) : AppException(HttpStatusCode.BadRequest, code, message, details)

class UnauthorizedException(
  code: String = "UNAUTHORIZED",
  message: String = "Authentication is required.",
  details: Map<String, String> = emptyMap(),
) : AppException(HttpStatusCode.Unauthorized, code, message, details)

class ForbiddenException(
  code: String = "FORBIDDEN",
  message: String = "You do not have permission to perform this action.",
  details: Map<String, String> = emptyMap(),
) : AppException(HttpStatusCode.Forbidden, code, message, details)

class NotFoundException(
  code: String = "NOT_FOUND",
  message: String,
  details: Map<String, String> = emptyMap(),
) : AppException(HttpStatusCode.NotFound, code, message, details)

class ConflictException(
  code: String = "CONFLICT",
  message: String,
  details: Map<String, String> = emptyMap(),
) : AppException(HttpStatusCode.Conflict, code, message, details)

class UnprocessableEntityException(
  code: String = "UNPROCESSABLE_ENTITY",
  message: String,
  details: Map<String, String> = emptyMap(),
) : AppException(HttpStatusCode.UnprocessableEntity, code, message, details)

class TooManyRequestsException(
  code: String = "RATE_LIMITED",
  message: String = "Too many requests. Please try again later.",
  details: Map<String, String> = emptyMap(),
) : AppException(HttpStatusCode.TooManyRequests, code, message, details)

class ServiceUnavailableException(
  code: String = "SERVICE_UNAVAILABLE",
  message: String,
  details: Map<String, String> = emptyMap(),
) : AppException(HttpStatusCode.ServiceUnavailable, code, message, details)

fun Application.installErrorHandling() {
  val logger = LoggerFactory.getLogger("com.pizzora.backend.core.error.StatusPages")

  install(StatusPages) {
    exception<AppException> { call, cause ->
      val correlationId = call.callId ?: UUID.randomUUID().toString()
      logger.warn(
        "Handled domain exception status={} code={} correlationId={} message={}",
        cause.statusCode.value,
        cause.code,
        correlationId,
        cause.message,
      )
      call.respond(
        cause.statusCode,
        ApiErrorEnvelope(
          error = ApiErrorDetail(
            code = cause.code,
            message = cause.message,
            details = cause.details,
          ),
          correlationId = correlationId,
          timestamp = Instant.now().toString(),
        ),
      )
    }

    exception<BadRequestException> { call, cause ->
      val correlationId = call.callId ?: UUID.randomUUID().toString()
      logger.warn("Bad request payload correlationId={}", correlationId, cause)
      call.respond(
        HttpStatusCode.BadRequest,
        ApiErrorEnvelope(
          error = ApiErrorDetail(
            code = "MALFORMED_REQUEST",
            message = "Request payload is missing or malformed.",
          ),
          correlationId = correlationId,
          timestamp = Instant.now().toString(),
        ),
      )
    }

    exception<Throwable> { call, cause ->
      val correlationId = call.callId ?: UUID.randomUUID().toString()
      logger.error("Unhandled internal exception correlationId={}", correlationId, cause)
      call.respond(
        HttpStatusCode.InternalServerError,
        ApiErrorEnvelope(
          error = ApiErrorDetail(
            code = "INTERNAL_ERROR",
            message = "An unexpected internal error occurred.",
          ),
          correlationId = correlationId,
          timestamp = Instant.now().toString(),
        ),
      )
    }

    status(HttpStatusCode.Unauthorized) { call, status ->
      val correlationId = call.callId ?: UUID.randomUUID().toString()
      call.respond(
        status,
        ApiErrorEnvelope(
          error = ApiErrorDetail(
            code = "UNAUTHORIZED",
            message = "Valid bearer authentication is required.",
          ),
          correlationId = correlationId,
          timestamp = Instant.now().toString(),
        ),
      )
    }
  }
}

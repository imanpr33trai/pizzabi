package com.pizzora.backend.core.logging

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import java.util.UUID
import org.slf4j.event.Level

private val SAFE_CORRELATION_ID_REGEX = Regex("^[A-Za-z0-9._-]{8,64}$")

fun Application.installStructuredLogging() {
  install(CallId) {
    retrieveFromHeader(HttpHeaders.XRequestId)
    generate { UUID.randomUUID().toString() }
    verify { value -> SAFE_CORRELATION_ID_REGEX.matches(value) }
    replyToHeader(HttpHeaders.XRequestId)
  }

  install(CallLogging) {
    level = Level.INFO
    callIdMdc("correlation_id")
    filter { call -> !call.request.path().startsWith("/api/v1/health/live") }
    format { call ->
      val status = call.response.status()
      val method = call.request.httpMethod.value
      val path = call.request.path()
      "$method $path -> ${status?.value ?: "unknown"}"
    }
  }
}

package com.example.core.security

import com.example.BuildConfig
import java.net.URI

object TransportSecurityPolicy {
  fun requireValidEndpointUrl(
    rawUrl: String,
    isDebugBuild: Boolean = BuildConfig.DEBUG,
    allowHttpInTest: Boolean = false,
  ): String {
    val trimmed = rawUrl.trim().trimEnd('/')
    val uri = runCatching { URI(trimmed) }.getOrNull()
      ?: throw IllegalArgumentException("Invalid endpoint URL.")
    val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("Endpoint URL must include a scheme.")
    val host = uri.host?.lowercase().orEmpty()

    when (scheme) {
      "https", "wss" -> return trimmed
      "http", "ws" -> {
        if (allowHttpInTest) return trimmed
        val isLocalLoopback =
          host == "10.0.2.2" ||
            host == "localhost" ||
            host == "127.0.0.1" ||
            host.endsWith(".local")
        if (isDebugBuild && isLocalLoopback) {
          return trimmed
        }
        throw SecurityException("Cleartext transport '$scheme://' to '$host' is prohibited.")
      }
      else -> throw SecurityException("Unsupported transport scheme '$scheme'.")
    }
  }
}

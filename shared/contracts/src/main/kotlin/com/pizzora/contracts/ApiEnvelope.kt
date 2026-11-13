package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorDetail(
  val code: String,
  val message: String,
  val details: Map<String, String> = emptyMap(),
)

@Serializable
data class ApiErrorEnvelope(
  val error: ApiErrorDetail,
  @SerialName("correlation_id") val correlationId: String,
  val timestamp: String,
)

@Serializable
enum class ReadinessState {
  @SerialName("HEALTHY")
  HEALTHY,

  @SerialName("DEGRADED")
  DEGRADED,

  @SerialName("UNHEALTHY")
  UNHEALTHY,
}

@Serializable
enum class DependencyHealthStatus {
  @SerialName("UP")
  UP,

  @SerialName("DOWN")
  DOWN,
}

@Serializable
data class HealthReadinessResponse(
  val status: ReadinessState,
  val dependencies: Map<String, DependencyHealthStatus>,
  val service: String = "pizzora-backend",
)

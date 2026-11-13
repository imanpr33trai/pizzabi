package com.pizzora.backend

import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.error.ConflictException
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.core.health.DependencyProbe
import com.pizzora.backend.worker.workerHealthModule
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.DependencyHealthStatus
import com.pizzora.contracts.HealthReadinessResponse
import com.pizzora.contracts.ReadinessState
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2InfrastructureTest {
  private val json = Json { ignoreUnknownKeys = true }

  private fun stubProbes(
    postgres: DependencyHealthStatus = DependencyHealthStatus.UP,
    redis: DependencyHealthStatus = DependencyHealthStatus.UP,
    minio: DependencyHealthStatus = DependencyHealthStatus.UP,
  ): List<DependencyProbe> = listOf(
    StaticProbe("postgres", required = true, status = postgres),
    StaticProbe("redis", required = false, status = redis),
    StaticProbe("minio", required = false, status = minio),
  )

  @Test
  fun `health ready returns 200 HEALTHY when all dependencies are UP`() = testApplication {
    application { pizzoraModule(probes = stubProbes()) }

    val response = client.get("/api/v1/health/ready")
    assertEquals(HttpStatusCode.OK, response.status)
    val body = json.decodeFromString<HealthReadinessResponse>(response.bodyAsText())
    assertEquals(ReadinessState.HEALTHY, body.status)
    assertEquals(DependencyHealthStatus.UP, body.dependencies["postgres"])
    assertEquals(DependencyHealthStatus.UP, body.dependencies["redis"])
    assertEquals(DependencyHealthStatus.UP, body.dependencies["minio"])
  }

  @Test
  fun `health ready returns 503 UNHEALTHY when postgres is DOWN`() = testApplication {
    application {
      pizzoraModule(probes = stubProbes(postgres = DependencyHealthStatus.DOWN))
    }

    val response = client.get("/api/v1/health/ready")
    assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    val body = json.decodeFromString<HealthReadinessResponse>(response.bodyAsText())
    assertEquals(ReadinessState.UNHEALTHY, body.status)
    assertEquals(DependencyHealthStatus.DOWN, body.dependencies["postgres"])
  }

  @Test
  fun `health ready returns 200 DEGRADED when redis or minio is DOWN while postgres is UP`() = testApplication {
    application {
      pizzoraModule(
        probes = stubProbes(
          postgres = DependencyHealthStatus.UP,
          redis = DependencyHealthStatus.DOWN,
          minio = DependencyHealthStatus.DOWN,
        ),
      )
    }

    val response = client.get("/api/v1/health/ready")
    assertEquals(HttpStatusCode.OK, response.status)
    val body = json.decodeFromString<HealthReadinessResponse>(response.bodyAsText())
    assertEquals(ReadinessState.DEGRADED, body.status)
    assertEquals(DependencyHealthStatus.UP, body.dependencies["postgres"])
    assertEquals(DependencyHealthStatus.DOWN, body.dependencies["redis"])
    assertEquals(DependencyHealthStatus.DOWN, body.dependencies["minio"])
  }

  @Test
  fun `correlation id propagates and StatusPages emits unified error envelope`() = testApplication {
    application {
      pizzoraModule(
        probes = stubProbes(),
        extraRoutes = {
          get("/test/validation") {
            throw ValidationException(
              code = "INVALID_PINCODE",
              message = "Pincode must be 6 digits.",
              details = mapOf("field" to "pincode"),
            )
          }
          get("/test/not-found") {
            throw NotFoundException(code = "ORDER_NOT_FOUND", message = "Order missing")
          }
          get("/test/conflict") {
            throw ConflictException(code = "INVALID_ORDER_TRANSITION", message = "Cannot cancel")
          }
          get("/test/crash") {
            error("sensitive-db-connection-string-never-expose")
          }
        },
      )
    }

    val requestId = "req-phase2-abc123"
    val validationRes = client.get("/test/validation") {
      header(HttpHeaders.XRequestId, requestId)
    }
    assertEquals(HttpStatusCode.BadRequest, validationRes.status)
    assertEquals(requestId, validationRes.headers[HttpHeaders.XRequestId])
    val validationErr = json.decodeFromString<ApiErrorEnvelope>(validationRes.bodyAsText())
    assertEquals("INVALID_PINCODE", validationErr.error.code)
    assertEquals("pincode", validationErr.error.details["field"])
    assertEquals(requestId, validationErr.correlationId)

    val notFoundRes = client.get("/test/not-found")
    assertEquals(HttpStatusCode.NotFound, notFoundRes.status)
    assertNotNull(notFoundRes.headers[HttpHeaders.XRequestId])

    val conflictRes = client.get("/test/conflict")
    assertEquals(HttpStatusCode.Conflict, conflictRes.status)

    val crashRes = client.get("/test/crash")
    assertEquals(HttpStatusCode.InternalServerError, crashRes.status)
    val crashBody = crashRes.bodyAsText()
    assertFalse(crashBody.contains("sensitive-db-connection-string"))
    val crashErr = json.decodeFromString<ApiErrorEnvelope>(crashBody)
    assertEquals("INTERNAL_ERROR", crashErr.error.code)
  }

  @Test
  fun `openapi endpoint returns valid OpenAPI 3_1 JSON`() = testApplication {
    application { pizzoraModule(probes = stubProbes()) }

    val response = client.get("/api/v1/openapi.json")
    assertEquals(HttpStatusCode.OK, response.status)
    val doc = json.parseToJsonElement(response.bodyAsText()).jsonObject
    assertEquals("3.1.0", doc["openapi"]?.jsonPrimitive?.content)
    assertTrue(doc.containsKey("paths"))
    assertTrue(doc["paths"]!!.jsonObject.containsKey("/api/v1/health/ready"))
  }

  @Test
  fun `worker health endpoint reports HEALTHY`() = testApplication {
    application { workerHealthModule() }

    val response = client.get("/health/worker")
    assertEquals(HttpStatusCode.OK, response.status)
    val body = json.decodeFromString<HealthReadinessResponse>(response.bodyAsText())
    assertEquals(ReadinessState.HEALTHY, body.status)
    assertEquals("pizzora-worker", body.service)
  }

  @Test
  fun `AppConfig parses jdbc, redis, and s3 endpoints from env`() {
    val cfg = AppConfig.fromEnv(
      mapOf(
        "PIZZORA_ENV" to "staging",
        "PORT" to "9090",
        "DATABASE_URL" to "jdbc:postgresql://pg-internal:5433/pizzora_prod",
        "REDIS_URL" to "redis://redis-internal:6380",
        "S3_ENDPOINT" to "http://minio-internal:9005",
      ),
    )
    assertEquals("staging", cfg.environment)
    assertEquals(9090, cfg.httpPort)
    assertEquals("pg-internal", cfg.database.host)
    assertEquals(5433, cfg.database.port)
    assertEquals("redis-internal", cfg.redis.host)
    assertEquals(6380, cfg.redis.port)
    assertEquals("minio-internal", cfg.storage.host)
    assertEquals(9005, cfg.storage.port)
  }

  private class StaticProbe(
    override val name: String,
    override val required: Boolean,
    private val status: DependencyHealthStatus,
  ) : DependencyProbe {
    override suspend fun check(): DependencyHealthStatus = status
  }
}

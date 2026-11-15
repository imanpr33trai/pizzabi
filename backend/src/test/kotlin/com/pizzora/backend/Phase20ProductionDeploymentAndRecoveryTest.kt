package com.pizzora.backend

import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.db.PostgresJdbcProbe
import com.pizzora.backend.core.email.InMemoryEmailDeliveryProvider
import com.pizzora.backend.core.metrics.PizzoraMetricsRegistry
import com.pizzora.backend.core.metrics.WorkerMetricsRegistry
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.backend.images.InspectedObject
import com.pizzora.backend.images.ObjectStorageClient
import com.pizzora.backend.razorpay.GatewayOrderResult
import com.pizzora.backend.razorpay.GatewayPaymentResult
import com.pizzora.backend.razorpay.GatewayRefundResult
import com.pizzora.backend.razorpay.RazorpayGateway
import com.pizzora.backend.realtime.RealtimeHub
import com.pizzora.backend.worker.EventSequencerWorker
import com.pizzora.backend.worker.OutboxDispatcherWorker
import com.pizzora.backend.worker.workerHealthModule
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateIngredientRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.DependencyHealthStatus
import com.pizzora.contracts.EventsReplayResponse
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.HealthReadinessResponse
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.PutRecipeRequest
import com.pizzora.contracts.ReadinessState
import com.pizzora.contracts.RecipeIngredientLineRequest
import com.pizzora.contracts.RecipeTargetType
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.WsEventsFrame
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.Types
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase20ProductionDeploymentAndRecoveryTest {
  private val json = Json { ignoreUnknownKeys = true }
  private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)

  private object DummyStorageClient : ObjectStorageClient {
    override fun presignPut(objectKey: String, contentType: String, expiresInSeconds: Long): String =
      "http://localhost:9000/pizzora/$objectKey"
    override fun publicUrlFor(objectKey: String): String =
      "http://localhost:9000/pizzora/$objectKey"
    override suspend fun inspectObject(objectKey: String): InspectedObject? = null
    override suspend fun deleteObject(objectKey: String): Boolean = true
  }

  private object DummyRazorpayGateway : RazorpayGateway {
    override val publicKeyId: String = "rzp_test_p20"
    override suspend fun createOrder(amountPaise: Long, currency: String, receipt: String, notes: Map<String, String>) =
      GatewayOrderResult("ord_p20", receipt, amountPaise, currency, "created")
    override suspend fun fetchPaymentsForOrder(gatewayOrderId: String): List<GatewayPaymentResult> = emptyList()
    override suspend fun createRefund(gatewayPaymentId: String, amountPaise: Long, idempotencyKey: String, notes: Map<String, String>) =
      GatewayRefundResult("rfnd_p20", gatewayPaymentId, amountPaise, "processed")
    override suspend fun fetchRefundsForPayment(gatewayPaymentId: String): List<GatewayRefundResult> = emptyList()
  }

  private object ThrowingCacheStore : EphemeralCacheStore {
    override suspend fun setEx(key: String, ttlSeconds: Long, value: String): Boolean =
      throw IllegalStateException("Redis down")
    override suspend fun get(key: String): String? =
      throw IllegalStateException("Redis down")
    override suspend fun getDel(key: String): String? =
      throw IllegalStateException("Redis down")
    override suspend fun del(key: String): Boolean =
      throw IllegalStateException("Redis down")
    override suspend fun incrementWithinWindow(key: String, windowSeconds: Long): Long? =
      throw IllegalStateException("Redis down")
  }

  @Test
  fun `pre-deploy Flyway migration step is separated from API runtime startup`() {
    EmbeddedPostgres.builder().start().use { pg ->
      // 1) Starting API/Worker with verifyMigrated=true BEFORE pre-deploy --migrate-only MUST fail fast
      val failure = runCatching {
        DatabaseFactory.connect(pg.postgresDatabase, verifyMigrated = true)
      }.exceptionOrNull()
      assertNotNull("Expected startup schema verification to fail on unmigrated DB", failure)
      assertTrue(failure!!.message!!.contains("Pending Flyway migrations detected"))

      // 2) Pre-deploy migration (--migrate-only) applies V1, V2, V3 cleanly
      val executed = DatabaseFactory.migrate(pg.postgresDatabase)
      assertTrue("Expected at least 3 Flyway migrations, executed=$executed", executed >= 3)

      // 3) API/Worker startup with verifyMigrated=true now succeeds without executing DDL
      val handle = DatabaseFactory.connect(pg.postgresDatabase, verifyMigrated = true)
      handle.close()
    }
  }

  @Test
  fun `production AppConfig enforces strong secrets and disables startup migrations by default`() {
    val missingSecretErr = runCatching {
      AppConfig.fromEnv(
        mapOf(
          "PIZZORA_ENV" to "production",
          "DATABASE_PASSWORD" to "prod-strong-db-pass-1234567890",
          "S3_SECRET_KEY" to "prod-strong-s3-secret-1234567890",
        ),
      )
    }.exceptionOrNull()
    assertNotNull(missingSecretErr)

    val placeholderDbPassErr = runCatching {
      AppConfig.fromEnv(
        mapOf(
          "PIZZORA_ENV" to "production",
          "JWT_SECRET" to "prod-jwt-secret-minimum-32-bytes-long-value",
          "DATABASE_PASSWORD" to "pizzora_dev_password",
          "S3_SECRET_KEY" to "prod-strong-s3-secret-1234567890",
        ),
      )
    }.exceptionOrNull()
    assertNotNull(placeholderDbPassErr)

    val prodConfig = AppConfig.fromEnv(
      mapOf(
        "PIZZORA_ENV" to "production",
        "JWT_SECRET" to "prod-jwt-secret-minimum-32-bytes-long-value",
        "DATABASE_PASSWORD" to "prod-strong-db-pass-1234567890",
        "S3_SECRET_KEY" to "prod-strong-s3-secret-1234567890",
      ),
    )
    assertFalse("Production must disable startup Flyway migration by default", prodConfig.database.runMigrationsOnStartup)

    val devConfig = AppConfig.fromEnv(emptyMap())
    assertTrue(devConfig.database.runMigrationsOnStartup)
  }

  @Test
  fun `API and worker Prometheus metrics and worker health probes reflect runtime state`() =
    testApplication {
      EmbeddedPostgres.builder().start().use { pg ->
        DatabaseFactory.migrate(pg.postgresDatabase)
        val apiMetrics = PizzoraMetricsRegistry()
        val workerMetrics = WorkerMetricsRegistry().apply {
          recordLoopSuccess("sequencer_loop")
          recordLoopSuccess("dispatcher_loop")
        }
        var reconcilerAlive = true

        application {
          pizzoraModule(
            probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
            dataSource = pg.postgresDatabase,
            metricsRegistry = apiMetrics,
          )
        }

        // Trigger a couple of API calls so HTTP counters are populated
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/health/live").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/health/ready").status)

        val metricsResp = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, metricsResp.status)
        val body = metricsResp.bodyAsText()
        assertTrue(body.contains("pizzora_jvm_heap_used_bytes"))
        assertTrue(body.contains("""pizzora_http_requests_total{group="health",status_class="2xx"}"""))
        assertTrue(body.contains("pizzora_outbox_unsequenced_total 0"))
        assertTrue(body.contains("pizzora_outbox_pending_dispatch_total 0"))
        assertTrue(body.contains("pizzora_outbox_dead_letter_total 0"))
        assertTrue(body.contains("pizzora_stream_last_seq 0"))
        assertTrue(body.contains("pizzora_refunds_failed_total 0"))

        // Worker health module reports HEALTHY when loops are active, and 503 UNHEALTHY when a loop stops
        testApplication {
          application {
            workerHealthModule(
              workerMetrics = workerMetrics,
              sharedMetrics = apiMetrics,
              dataSource = pg.postgresDatabase,
              loopStatusProvider = {
                mapOf(
                  "sequencer_loop" to DependencyHealthStatus.UP,
                  "dispatcher_loop" to DependencyHealthStatus.UP,
                  "expiry_sweep" to DependencyHealthStatus.UP,
                  "reconciler_loop" to if (reconcilerAlive) DependencyHealthStatus.UP else DependencyHealthStatus.DOWN,
                )
              },
            )
          }

          val healthy = client.get("/health/worker")
          assertEquals(HttpStatusCode.OK, healthy.status)
          assertEquals(ReadinessState.HEALTHY, json.decodeFromString<HealthReadinessResponse>(healthy.bodyAsText()).status)

          val workerMetricsBody = client.get("/metrics").bodyAsText()
          assertTrue(workerMetricsBody.contains("""pizzora_worker_loop_cycles_total{loop="sequencer_loop"} 1"""))
          assertTrue(workerMetricsBody.contains("pizzora_stream_last_seq"))

          reconcilerAlive = false
          val unhealthy = client.get("/health/worker")
          assertEquals(HttpStatusCode.ServiceUnavailable, unhealthy.status)
          assertEquals(ReadinessState.UNHEALTHY, json.decodeFromString<HealthReadinessResponse>(unhealthy.bodyAsText()).status)
        }
      }
    }

  @Test
  fun `automated backup and restore drill onto fresh Postgres preserves all data with zero loss`() {
    val rootDir = locateRepoRoot()
    val backupScript = File(rootDir, "infra/scripts/backup-postgres.sh")
    val restoreScript = File(rootDir, "infra/scripts/restore-postgres.sh")
    assertTrue("Missing backup script at ${backupScript.absolutePath}", backupScript.exists())
    assertTrue("Missing restore script at ${restoreScript.absolutePath}", restoreScript.exists())

    val backupDir = Files.createTempDirectory("pizzora-pg-backup-drill").toFile()
    try {
      EmbeddedPostgres.builder().start().use { srcPg ->
        DatabaseFactory.migrate(srcPg.postgresDatabase)
        val ownerEmail = "owner-drill-${UUID.randomUUID()}@pizzora.test"
        runBlocking {
          BootstrapOwner.bootstrap(
            dataSource = srcPg.postgresDatabase,
            passwordHasher = fastHasher,
            email = ownerEmail,
            password = "OwnerDrillPass123!",
            fullName = "Drill Owner",
            phone = "9900112233",
          )
        }

        // Seed order + payment + audit log + outbox + cursor state on source instance
        val orderId = UUID.randomUUID()
        val paymentId = UUID.randomUUID()
        srcPg.postgresDatabase.connection.use { conn ->
          val ownerId = conn.createStatement().use { st ->
            st.executeQuery("SELECT id FROM users WHERE email = '$ownerEmail'").use { rs ->
              rs.next()
              rs.getObject("id", UUID::class.java)
            }
          }
          conn.prepareStatement(
            """
            INSERT INTO orders (
              id, order_number, customer_id, idempotency_key, request_hash, fulfillment_type,
              schedule_type, status, version, subtotal_paise, discount_paise, delivery_fee_paise,
              tax_paise, total_paise, gst_bps_snapshot
            ) VALUES (?, 'PZ-BACKUP-2001', ?, 'idem-bkp-1', 'hash-bkp', 'PICKUP', 'ASAP', 'COMPLETED', 3, 45000, 0, 0, 2250, 47250, 500)
            """.trimIndent(),
          ).use { ps ->
            ps.setObject(1, orderId)
            ps.setObject(2, ownerId)
            ps.executeUpdate()
          }
          conn.prepareStatement(
            "INSERT INTO payments (id, order_id, method, status, amount_paise, captured_paise, refunded_paise) VALUES (?, ?, 'COD', 'COD_COLLECTED', 47250, 47250, 0)",
          ).use { ps ->
            ps.setObject(1, paymentId)
            ps.setObject(2, orderId)
            ps.executeUpdate()
          }
          conn.createStatement().use { st ->
            st.executeUpdate("UPDATE event_stream_cursor SET last_seq = 77 WHERE id = 1")
          }
        }

        val nativeDump = findPostgresBinaryOrNull("pg_dump")
        val nativeRestore = findPostgresBinaryOrNull("pg_restore")
        val nativePsql = findPostgresBinaryOrNull("psql")
        val (pgDumpBin, pgRestoreBin, psqlBin) = if (nativeDump != null && nativeRestore != null && nativePsql != null) {
          Triple(nativeDump, nativeRestore, nativePsql)
        } else {
          createPostgresToolShims(backupDir)
        }
        val pgLibEnv = buildPgLibraryEnv(pgDumpBin, pgRestoreBin, psqlBin)

        // Run backup-postgres.sh against source EmbeddedPostgres
        val backupResult = runProcess(
          command = listOf("bash", backupScript.absolutePath),
          env = pgLibEnv + mapOf(
            "BACKUP_DIR" to backupDir.absolutePath,
            "PGHOST" to "127.0.0.1",
            "PGPORT" to srcPg.port.toString(),
            "PGUSER" to "postgres",
            "PGDATABASE" to "postgres",
            "PG_DUMP_BIN" to pgDumpBin,
          ),
          workingDir = rootDir,
        )
        assertEquals("backup-postgres.sh failed:\n${backupResult.output}", 0, backupResult.exitCode)
        val archiveFile = File(backupResult.output.trim().lines().last())
        assertTrue("Expected backup dump file at ${archiveFile.absolutePath}", archiveFile.exists())
        assertTrue("Expected SHA-256 sidecar file", File("${archiveFile.absolutePath}.sha256").exists())

        // Restore onto a brand-new, empty EmbeddedPostgres instance
        EmbeddedPostgres.builder().start().use { dstPg ->
          val restoreResult = runProcess(
            command = listOf("bash", restoreScript.absolutePath, archiveFile.absolutePath),
            env = pgLibEnv + mapOf(
              "PGHOST" to "127.0.0.1",
              "PGPORT" to dstPg.port.toString(),
              "PGUSER" to "postgres",
              "PGDATABASE" to "postgres",
              "PG_RESTORE_BIN" to pgRestoreBin,
              "PSQL_BIN" to psqlBin,
            ),
            workingDir = rootDir,
          )
          assertEquals("restore-postgres.sh failed:\n${restoreResult.output}", 0, restoreResult.exitCode)

          // Verify zero data loss & Flyway schema parity on the restored target instance
          DatabaseFactory.connect(dstPg.postgresDatabase, verifyMigrated = true).use {
            dstPg.postgresDatabase.connection.use { conn ->
              conn.createStatement().use { st ->
                st.executeQuery("SELECT total_paise, status, version FROM orders WHERE order_number = 'PZ-BACKUP-2001'").use { rs ->
                  assertTrue(rs.next())
                  assertEquals(47250L, rs.getLong("total_paise"))
                  assertEquals("COMPLETED", rs.getString("status"))
                  assertEquals(3L, rs.getLong("version"))
                }
                st.executeQuery("SELECT captured_paise, status FROM payments WHERE id = '$paymentId'::uuid").use { rs ->
                  assertTrue(rs.next())
                  assertEquals(47250L, rs.getLong("captured_paise"))
                  assertEquals("COD_COLLECTED", rs.getString("status"))
                }
                st.executeQuery("SELECT last_seq FROM event_stream_cursor WHERE id = 1").use { rs ->
                  assertTrue(rs.next())
                  assertEquals(77L, rs.getLong("last_seq"))
                }
              }
            }
          }
        }
      }
    } finally {
      backupDir.deleteRecursively()
    }
  }

  @Test
  fun `server and Redis restart during active KDS session recovers exact stream_seq continuity`() {
    EmbeddedPostgres.builder().start().use { pg ->
      DatabaseFactory.migrate(pg.postgresDatabase)
      val ownerEmail = "owner-rec-${UUID.randomUUID()}@pizzora.test"
      val ownerPass = "OwnerRecPass123!"
      runBlocking {
        BootstrapOwner.bootstrap(
          dataSource = pg.postgresDatabase,
          passwordHasher = fastHasher,
          email = ownerEmail,
          password = ownerPass,
          fullName = "Recovery Owner",
          phone = "9820000001",
        )
      }

      val config = AppConfig.fromEnv(mapOf("JWT_SECRET" to "test-jwt-secret-minimum-32-bytes-long-key")).let {
        it.copy(security = it.security.copy(rateLimitEnabled = false))
      }
      val sequencer = EventSequencerWorker(pg.postgresDatabase)

      var orderId = ""
      var lastSeenSeq = 0L

      // Session 1: Server #1 + Hub #1 places order and dispatches initial ORDER_PLACED to WebSocket
      testApplication {
        val hub1 = RealtimeHub()
        val dispatcher1 = OutboxDispatcherWorker(pg.postgresDatabase, razorpayGateway = DummyRazorpayGateway, realtimeDispatcher = hub1)
        application {
          pizzoraModule(
            config = config,
            probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
            dataSource = pg.postgresDatabase,
            cacheStore = ThrowingCacheStore,
            emailDeliveryProvider = InMemoryEmailDeliveryProvider(),
            objectStorageClient = DummyStorageClient,
            realtimeHub = hub1,
            passwordHasher = fastHasher,
          )
        }

        val ownerToken = json.decodeFromString<AuthTokensResponse>(
          client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(LoginRequest(ownerEmail, ownerPass)))
          }.bodyAsText(),
        ).accessToken

        val cat = json.decodeFromString<CategoryDto>(
          client.post("/api/v1/owner/categories") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateCategoryRequest("RecCat", 1, true)))
          }.bodyAsText(),
        )
        val prod = json.decodeFromString<ProductDto>(
          client.post("/api/v1/owner/products") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody(
              json.encodeToString(
                CreateProductRequest(
                  categoryId = cat.id,
                  name = "Recovery Pizza",
                  description = "Woodfired",
                  isVeg = true,
                  variants = listOf(CreateVariantRequest(SizeCode.MEDIUM, 40_000L, true)),
                ),
              ),
            )
          }.bodyAsText(),
        )
        val crust = json.decodeFromString<CrustOptionDto>(
          client.post("/api/v1/owner/crusts") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateCrustRequest("RecCrust", 2_000L, true)))
          }.bodyAsText(),
        )
        val ing = json.decodeFromString<InventoryItemDto>(
          client.post("/api/v1/owner/inventory") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateIngredientRequest("RecDough", IngredientUnit.GRAMS, 50L, 2_000L)))
          }.bodyAsText(),
        )
        client.put("/api/v1/owner/recipes") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              PutRecipeRequest(RecipeTargetType.VARIANT, prod.variants.first().id, listOf(RecipeIngredientLineRequest(ing.ingredientId, 200))),
            ),
          )
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        val checkout = client.post("/api/v1/checkout") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          header("Idempotency-Key", "rec-order-1")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              CheckoutRequest(
                items = listOf(
                  CartItemInputDto(
                    productId = prod.id,
                    variantId = prod.variants.first().id,
                    crustId = crust.id,
                    toppingIds = emptyList(),
                    quantity = 1,
                  ),
                ),
                fulfillmentType = FulfillmentType.PICKUP,
                scheduleType = ScheduleType.ASAP,
                paymentMethod = PaymentMethod.COD,
              ),
            ),
          )
        }
        assertEquals(HttpStatusCode.Created, checkout.status)
        orderId = json.decodeFromString<CheckoutResponse>(checkout.bodyAsText()).order.id

        sequencer.runOnce()
        dispatcher1.runOnce()

        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket(
          urlString = "/ws/v1/events?since_seq=0",
          request = {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
          },
        ) {
          val initialFrame = json.decodeFromString<WsEventsFrame>((incoming.receive() as Frame.Text).readText())
          assertTrue(initialFrame.events.any { it.aggregateId == orderId })
          lastSeenSeq = initialFrame.serverHighWaterSeq
        }
        assertTrue(lastSeenSeq >= 1L)
      }

      // Session 2: Fresh Server #2 + Hub #2 + Worker after simulated server & Redis restart
      testApplication {
        val hub2 = RealtimeHub()
        val dispatcher2 = OutboxDispatcherWorker(pg.postgresDatabase, razorpayGateway = DummyRazorpayGateway, realtimeDispatcher = hub2)
        application {
          pizzoraModule(
            config = config,
            probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
            dataSource = pg.postgresDatabase,
            cacheStore = ThrowingCacheStore,
            emailDeliveryProvider = InMemoryEmailDeliveryProvider(),
            objectStorageClient = DummyStorageClient,
            realtimeHub = hub2,
            passwordHasher = fastHasher,
          )
        }

        val ownerToken2 = json.decodeFromString<AuthTokensResponse>(
          client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(LoginRequest(ownerEmail, ownerPass)))
          }.bodyAsText(),
        ).accessToken

        // KDS transitions occur while client was disconnected across server restart
        for (action in listOf("ack", "start-prep", "ready")) {
          val resp = client.post("/api/v1/kds/orders/$orderId/$action") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken2")
            contentType(ContentType.Application.Json)
            setBody("{}")
          }
          assertEquals(HttpStatusCode.OK, resp.status)
        }

        // Resumed workers sequence & dispatch all missed events cleanly
        sequencer.runOnce()
        dispatcher2.runOnce()

        val wsClient2 = createClient { install(ClientWebSockets) }
        wsClient2.webSocket(
          urlString = "/ws/v1/events?since_seq=$lastSeenSeq",
          request = {
            header(HttpHeaders.Authorization, "Bearer $ownerToken2")
          },
        ) {
          val frame = json.decodeFromString<WsEventsFrame>((incoming.receive() as Frame.Text).readText())
          val orderEvents = frame.events.filter { it.aggregateId == orderId }
          assertEquals(3, orderEvents.size)
          assertEquals(listOf(2L, 3L, 4L), orderEvents.map { it.aggregateVersion })
          assertTrue(orderEvents.zipWithNext().all { (a, b) -> a.streamSeq < b.streamSeq })
          assertTrue(frame.serverHighWaterSeq > lastSeenSeq)
        }
      }
    }
  }

  private fun locateRepoRoot(): File {
    var dir = File(System.getProperty("user.dir")).canonicalFile
    while (dir.parentFile != null) {
      if (File(dir, "settings.gradle.kts").exists()) return dir
      dir = dir.parentFile
    }
    error("Could not locate repository root from ${System.getProperty("user.dir")}")
  }

  private fun findPostgresBinaryOrNull(name: String): String? {
    val searchRoots = linkedSetOf<File>().apply {
      add(File("/tmp/embedded-pg"))
      add(File("/tmp"))
      System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
      System.getProperty("ot.epg.working-dir")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
      add(File("/usr/lib/postgresql"))
    }.filter { it.exists() && it.isDirectory }

    for (root in searchRoots) {
      val match = root.walkTopDown()
        .maxDepth(6)
        .firstOrNull { it.isFile && it.name == name && it.canExecute() }
      if (match != null) return match.absolutePath
    }

    return System.getenv("PATH")
      ?.split(File.pathSeparator)
      ?.map { File(it, name) }
      ?.firstOrNull { it.isFile && it.canExecute() }
      ?.absolutePath
  }

  private fun createPostgresToolShims(workDir: File): Triple<String, String, String> {
    val binDir = File(workDir, "pg-shims").apply { mkdirs() }
    val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
    val classpath = System.getProperty("java.class.path").replace("'", "'\"'\"'")

    fun writeWrapper(toolName: String): String {
      val script = File(binDir, toolName)
      script.writeText(
        """
        #!/usr/bin/env bash
        set -euo pipefail
        exec "$javaBin" -cp '$classpath' com.pizzora.backend.PostgresToolShim "$toolName" "${'$'}@"
        """.trimIndent() + "\n",
      )
      script.setExecutable(true)
      return script.absolutePath
    }

    return Triple(writeWrapper("pg_dump"), writeWrapper("pg_restore"), writeWrapper("psql"))
  }

  private fun buildPgLibraryEnv(vararg binaryPaths: String): Map<String, String> {
    val libDirs = binaryPaths
      .mapNotNull { path -> File(path).parentFile?.parentFile?.resolve("lib")?.takeIf { it.isDirectory }?.absolutePath }
      .distinct()
    if (libDirs.isEmpty()) return emptyMap()
    val existing = System.getenv("LD_LIBRARY_PATH")?.takeIf { it.isNotBlank() }
    val combined = (libDirs + listOfNotNull(existing)).joinToString(File.pathSeparator)
    return mapOf("LD_LIBRARY_PATH" to combined)
  }

  private data class ProcessOutput(val exitCode: Int, val output: String)

  private fun runProcess(
    command: List<String>,
    env: Map<String, String>,
    workingDir: File,
  ): ProcessOutput {
    val pb = ProcessBuilder(command)
      .directory(workingDir)
      .redirectErrorStream(true)
    pb.environment().putAll(env)
    val proc = pb.start()
    val output = proc.inputStream.bufferedReader().readText()
    val finished = proc.waitFor(30, TimeUnit.SECONDS)
    if (!finished) {
      proc.destroyForcibly()
      error("Process timed out: $command")
    }
    return ProcessOutput(proc.exitValue(), output)
  }
}

@Serializable
internal data class ShimColumnDump(
  val name: String,
  val sqlType: String,
)

@Serializable
internal data class ShimTableDump(
  val tableName: String,
  val columns: List<ShimColumnDump>,
  val rows: List<List<String?>>,
)

@Serializable
internal data class ShimDatabaseDump(
  val tables: List<ShimTableDump>,
)

object PostgresToolShim {
  private val json = Json { ignoreUnknownKeys = true }

  @JvmStatic
  fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Missing subcommand" }
    val tool = args[0]
    val flags = mutableMapOf<String, String>()
    val positional = mutableListOf<String>()
    for (i in 1 until args.size) {
      val arg = args[i]
      if (arg.startsWith("--") && '=' in arg) {
        val eqIdx = arg.indexOf('=')
        flags[arg.substring(2, eqIdx)] = arg.substring(eqIdx + 1)
      } else if (!arg.startsWith("-")) {
        positional += arg
      }
    }

    val host = flags["host"] ?: System.getenv("PGHOST") ?: "127.0.0.1"
    val port = flags["port"] ?: System.getenv("PGPORT") ?: "5432"
    val user = flags["username"] ?: System.getenv("PGUSER") ?: "postgres"
    val db = flags["dbname"] ?: System.getenv("PGDATABASE") ?: "postgres"
    val pass = System.getenv("PGPASSWORD") ?: ""
    val jdbcUrl = "jdbc:postgresql://$host:$port/$db"

    when (tool) {
      "pg_dump" -> {
        val outputFile = File(flags["file"] ?: error("Missing --file argument for pg_dump shim"))
        DriverManager.getConnection(jdbcUrl, user, pass).use { conn ->
          val tableNames = mutableListOf<String>()
          conn.prepareStatement(
            """
            SELECT tablename
            FROM pg_tables
            WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
            ORDER BY tablename ASC
            """.trimIndent(),
          ).use { ps ->
            ps.executeQuery().use { rs ->
              while (rs.next()) tableNames += rs.getString(1)
            }
          }

          val tableDumps = tableNames.map { tableName ->
            val columns = mutableListOf<ShimColumnDump>()
            conn.prepareStatement(
              """
              SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS sql_type
              FROM pg_attribute a
              JOIN pg_class c ON c.oid = a.attrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname = 'public'
                AND c.relname = ?
                AND a.attnum > 0
                AND NOT a.attisdropped
                AND a.attgenerated = ''
              ORDER BY a.attnum ASC
              """.trimIndent(),
            ).use { ps ->
              ps.setString(1, tableName)
              ps.executeQuery().use { rs ->
                while (rs.next()) {
                  columns += ShimColumnDump(rs.getString("attname"), rs.getString("sql_type"))
                }
              }
            }

            val rows = mutableListOf<List<String?>>()
            if (columns.isNotEmpty()) {
              val colList = columns.joinToString(", ") { "\"${it.name}\"::text" }
              conn.createStatement().use { st ->
                st.executeQuery("SELECT $colList FROM \"$tableName\"").use { rs ->
                  while (rs.next()) {
                    rows += (1..columns.size).map { idx -> rs.getString(idx) }
                  }
                }
              }
            }
            ShimTableDump(tableName, columns, rows)
          }

          val dumpJson = json.encodeToString(ShimDatabaseDump(tableDumps))
          GZIPOutputStream(outputFile.outputStream()).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(dumpJson)
          }
        }
      }

      "pg_restore" -> {
        val archiveFile = File(positional.lastOrNull() ?: error("Missing archive path for pg_restore shim"))
        val dump = GZIPInputStream(archiveFile.inputStream()).bufferedReader(Charsets.UTF_8).use { reader ->
          json.decodeFromString<ShimDatabaseDump>(reader.readText())
        }

        val ds = com.zaxxer.hikari.HikariDataSource(
          com.zaxxer.hikari.HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = pass
            this.maximumPoolSize = 2
          },
        )
        try {
          DatabaseFactory.migrate(ds)
          ds.connection.use { conn ->
            conn.autoCommit = false
            try {
              conn.createStatement().use { st ->
                st.execute("SET LOCAL session_replication_role = replica")
                val tableList = dump.tables.joinToString(", ") { "\"${it.tableName}\"" }
                if (tableList.isNotBlank()) {
                  st.execute("TRUNCATE TABLE $tableList RESTART IDENTITY CASCADE")
                }
              }
              for (table in dump.tables) {
                if (table.rows.isEmpty() || table.columns.isEmpty()) continue
                val colNames = table.columns.joinToString(", ") { "\"${it.name}\"" }
                val placeholders = table.columns.joinToString(", ") { "CAST(? AS ${it.sqlType})" }
                val sql = "INSERT INTO \"${table.tableName}\" ($colNames) VALUES ($placeholders)"
                conn.prepareStatement(sql).use { ps ->
                  for (row in table.rows) {
                    row.forEachIndexed { idx, value ->
                      if (value == null) ps.setNull(idx + 1, Types.NULL) else ps.setString(idx + 1, value)
                    }
                    ps.addBatch()
                  }
                  ps.executeBatch()
                }
              }
              conn.commit()
            } catch (e: Exception) {
              conn.rollback()
              throw e
            } finally {
              conn.autoCommit = true
            }
          }
        } finally {
          ds.close()
        }
      }

      "psql" -> {
        val cmd = flags["command"] ?: error("Missing --command for psql shim")
        DriverManager.getConnection(jdbcUrl, user, pass).use { conn ->
          conn.createStatement().use { st ->
            st.executeQuery(cmd).use { rs ->
              val colCount = rs.metaData.columnCount
              while (rs.next()) {
                val line = (1..colCount).joinToString("|") { idx -> rs.getString(idx).orEmpty() }
                println(line)
              }
            }
          }
        }
      }

      else -> error("Unsupported shim tool: $tool")
    }
  }
}

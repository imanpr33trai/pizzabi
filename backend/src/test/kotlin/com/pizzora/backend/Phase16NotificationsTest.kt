package com.pizzora.backend

import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.db.PostgresJdbcProbe
import com.pizzora.backend.core.email.InMemoryEmailDeliveryProvider
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.backend.notifications.NotificationDeliveryException
import com.pizzora.backend.notifications.NotificationDeliveryProvider
import com.pizzora.backend.notifications.NotificationDeliveryResult
import com.pizzora.backend.notifications.NotificationDispatchRequest
import com.pizzora.backend.notifications.NotificationService
import com.pizzora.backend.notifications.WebSocketInboxNotificationDeliveryProvider
import com.pizzora.backend.razorpay.SandboxRazorpayClient
import com.pizzora.backend.worker.EventSequencerWorker
import com.pizzora.backend.worker.OutboxDispatcherWorker
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.MarkAllNotificationsReadResponse
import com.pizzora.contracts.MarkNotificationReadResponse
import com.pizzora.contracts.NotificationCategory
import com.pizzora.contracts.NotificationDeliveryStatus
import com.pizzora.contracts.NotificationInboxResponse
import com.pizzora.contracts.NotificationPreferencesDto
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.UpdateNotificationPreferencesRequest
import com.pizzora.contracts.VerifyEmailRequest
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase16NotificationsTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }

    private val ownerEmail = "owner-p16-${UUID.randomUUID()}@pizzora.test"
    private val ownerPass = "OwnerPass123!"

    @JvmStatic
    @BeforeClass
    fun startInfra() {
      pg = EmbeddedPostgres.builder().start()
      DatabaseFactory.migrateAndConnect(pg.postgresDatabase)
      runBlocking {
        BootstrapOwner.bootstrap(
          dataSource = pg.postgresDatabase,
          passwordHasher = fastHasher,
          email = ownerEmail,
          password = ownerPass,
          fullName = "Owner P16",
          phone = "9876543210",
        )
      }
    }

    @JvmStatic
    @AfterClass
    fun stopInfra() {
      pg.close()
    }
  }

  private fun withP16App(block: suspend ApplicationTestBuilder.(InMemoryEmailDeliveryProvider) -> Unit) = testApplication {
    val emailProvider = InMemoryEmailDeliveryProvider()
    val cache = InMemoryCacheStoreP16()
    application {
      pizzoraModule(
        config = AppConfig.fromEnv().copy(environment = "test"),
        probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
        dataSource = pg.postgresDatabase,
        cacheStore = cache,
        emailDeliveryProvider = emailProvider,
        passwordHasher = fastHasher,
      )
    }
    block(emailProvider)
  }

  private suspend fun ApplicationTestBuilder.registerAndLoginCustomer(
    emailProvider: InMemoryEmailDeliveryProvider,
    prefix: String,
  ): Pair<String, UUID> {
    val email = "$prefix-${UUID.randomUUID()}@pizzora.test"
    val regResp = client.post("/api/v1/auth/register") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RegisterRequest(email, "CustomerPass123!", "Customer $prefix", "9876500001")))
    }
    assertEquals(HttpStatusCode.Created, regResp.status)
    val token = emailProvider.outbox.lastOrNull { it.toEmail == email }?.token
      ?: error("Verification token missing for $email")
    val verifyResp = client.post("/api/v1/auth/verify-email") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(VerifyEmailRequest(token)))
    }
    assertEquals(HttpStatusCode.OK, verifyResp.status)
    val loginResp = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(email, "CustomerPass123!")))
    }
    assertEquals(HttpStatusCode.OK, loginResp.status)
    val tokens = json.decodeFromString<AuthTokensResponse>(loginResp.bodyAsText())
    return tokens.accessToken to UUID.fromString(tokens.user.id)
  }

  private fun insertTestOrderAndOutboxEvent(
    customerId: UUID,
    eventType: String = "ORDER_STATUS_CHANGED",
    toStatus: String = "PREPARING",
    orderNumber: String = "PZ-NTF-${UUID.randomUUID().toString().take(6).uppercase()}",
  ): Pair<UUID, UUID> {
    val orderId = UUID.randomUUID()
    val outboxId = UUID.randomUUID()
    pg.postgresDatabase.connection.use { conn ->
      conn.autoCommit = false
      try {
        conn.prepareStatement(
          """
          INSERT INTO orders (
            id, order_number, customer_id, idempotency_key, request_hash,
            fulfillment_type, schedule_type, status, version,
            subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise, gst_bps_snapshot
          ) VALUES (?, ?, ?, ?, 'hash-p16', 'PICKUP', 'ASAP', ?, 2, 39900, 0, 0, 1995, 41895, 500)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, orderId)
          ps.setString(2, orderNumber)
          ps.setObject(3, customerId)
          ps.setString(4, "idem-$orderId")
          ps.setString(5, toStatus)
          ps.executeUpdate()
        }

        val payload = """{"order_number":"$orderNumber","to_status":"$toStatus","customer_id":"$customerId"}"""
        conn.prepareStatement(
          """
          INSERT INTO outbox_events (
            id, aggregate_type, aggregate_id, aggregate_version, event_type, payload_json, dispatch_status
          ) VALUES (?, 'ORDER', ?, 2, ?, ?::jsonb, 'PENDING')
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, outboxId)
          ps.setObject(2, orderId)
          ps.setString(3, eventType)
          ps.setString(4, payload)
          ps.executeUpdate()
        }
        conn.commit()
      } catch (t: Throwable) {
        conn.rollback()
        throw t
      }
    }
    return orderId to outboxId
  }

  @Test
  fun `outbox dispatch populates inbox idempotently, transitions PENDING to SYNCED on pull, and blocks cross-user IDOR on read`() = withP16App { emailProvider ->
    val (tokenA, userAId) = registerAndLoginCustomer(emailProvider, "notif-a")
    val (tokenB, _) = registerAndLoginCustomer(emailProvider, "notif-b")

    val (_, outboxId) = insertTestOrderAndOutboxEvent(userAId, eventType = "ORDER_STATUS_CHANGED", toStatus = "PREPARING")

    val sequencer = EventSequencerWorker(pg.postgresDatabase)
    val dispatcher = OutboxDispatcherWorker(
      dataSource = pg.postgresDatabase,
      razorpayGateway = SandboxRazorpayClient(),
      notificationDeliveryProvider = WebSocketInboxNotificationDeliveryProvider(),
    )

    sequencer.runOnce()
    dispatcher.runOnce()

    // Re-queue same outbox event to verify UNIQUE(user_id, event_id) prevents duplicate inbox rows
    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        "UPDATE outbox_events SET dispatch_status = 'PENDING', published_at = NULL WHERE id = ?",
      ).use { ps ->
        ps.setObject(1, outboxId)
        ps.executeUpdate()
      }
    }
    dispatcher.runOnce()

    val unreadResp = client.get("/api/v1/notifications?unread=true") {
      header(HttpHeaders.Authorization, "Bearer $tokenA")
    }
    assertEquals(HttpStatusCode.OK, unreadResp.status)
    val inbox = json.decodeFromString<NotificationInboxResponse>(unreadResp.bodyAsText())
    assertEquals(1, inbox.notifications.size)
    assertEquals(1, inbox.unreadCount)
    val notif = inbox.notifications.single()
    assertEquals(outboxId.toString(), notif.eventId)
    assertEquals(NotificationDeliveryStatus.SYNCED, notif.deliveryStatus)
    assertFalse(notif.isRead)

    // User B cannot mark User A's notification read (IDOR protection)
    val idorResp = client.post("/api/v1/notifications/${notif.id}/read") {
      header(HttpHeaders.Authorization, "Bearer $tokenB")
    }
    assertEquals(HttpStatusCode.NotFound, idorResp.status)
    val errEnvelope = json.decodeFromString<ApiErrorEnvelope>(idorResp.bodyAsText())
    assertEquals("NOTIFICATION_NOT_FOUND", errEnvelope.error.code)

    // User A marks own notification read
    val readResp = client.post("/api/v1/notifications/${notif.id}/read") {
      header(HttpHeaders.Authorization, "Bearer $tokenA")
    }
    assertEquals(HttpStatusCode.OK, readResp.status)
    val marked = json.decodeFromString<MarkNotificationReadResponse>(readResp.bodyAsText())
    assertTrue(marked.notification.isRead)
    assertEquals(0, marked.unreadCount)

    // Unread filter now returns empty
    val afterReadResp = client.get("/api/v1/notifications?unread=true") {
      header(HttpHeaders.Authorization, "Bearer $tokenA")
    }
    assertEquals(HttpStatusCode.OK, afterReadResp.status)
    val afterInbox = json.decodeFromString<NotificationInboxResponse>(afterReadResp.bodyAsText())
    assertTrue(afterInbox.notifications.isEmpty())
    assertEquals(0, afterInbox.unreadCount)
  }

  @Test
  fun `notification preferences opt-out suppresses ORDER_UPDATE and PROMO_ALERT delivery while read-all clears remaining items`() = withP16App { emailProvider ->
    val (token, userId) = registerAndLoginCustomer(emailProvider, "notif-pref")

    val defaultPrefResp = client.get("/api/v1/notifications/preferences") {
      header(HttpHeaders.Authorization, "Bearer $token")
    }
    assertEquals(HttpStatusCode.OK, defaultPrefResp.status)
    val defaultPrefs = json.decodeFromString<NotificationPreferencesDto>(defaultPrefResp.bodyAsText())
    assertTrue(defaultPrefs.orderUpdates)
    assertTrue(defaultPrefs.promoAlerts)
    assertTrue(defaultPrefs.kdsSoundEnabled)

    // Disable order_updates and promo_alerts
    val updatePrefResp = client.put("/api/v1/notifications/preferences") {
      header(HttpHeaders.Authorization, "Bearer $token")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          UpdateNotificationPreferencesRequest(
            orderUpdates = false,
            promoAlerts = false,
            kdsSoundEnabled = false,
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.OK, updatePrefResp.status)
    val updatedPrefs = json.decodeFromString<NotificationPreferencesDto>(updatePrefResp.bodyAsText())
    assertFalse(updatedPrefs.orderUpdates)
    assertFalse(updatedPrefs.promoAlerts)
    assertFalse(updatedPrefs.kdsSoundEnabled)

    // Enqueue ORDER_STATUS_CHANGED while orderUpdates=false
    val (_, suppressedOutboxId) = insertTestOrderAndOutboxEvent(userId, toStatus = "READY")
    val deliveredCalls = CopyOnWriteArrayList<NotificationDispatchRequest>()
    val recordingProvider = object : NotificationDeliveryProvider {
      override val providerName: String = "RecordingProvider"
      override suspend fun deliver(request: NotificationDispatchRequest): NotificationDeliveryResult {
        deliveredCalls += request
        return NotificationDeliveryResult(NotificationDeliveryStatus.PENDING, providerName)
      }
    }

    EventSequencerWorker(pg.postgresDatabase).runOnce()
    OutboxDispatcherWorker(
      dataSource = pg.postgresDatabase,
      razorpayGateway = SandboxRazorpayClient(),
      notificationDeliveryProvider = recordingProvider,
    ).runOnce()

    // Provider was NOT called for user's opted-out event
    assertTrue(deliveredCalls.none { it.eventId == suppressedOutboxId })

    // Direct promo insert is also suppressed while promoAlerts = false
    pg.postgresDatabase.connection.use { conn ->
      conn.autoCommit = true
      val promoInserted = NotificationService.insertInboxNotificationIfAllowed(
        conn = conn,
        userId = userId,
        eventId = UUID.randomUUID(),
        title = "Weekend Offer",
        body = "20% off",
        category = NotificationCategory.PROMO_ALERT,
        referenceId = null,
      )
      assertFalse(promoInserted)
    }

    // Re-enable orderUpdates and insert 2 notifications, then markAllRead
    client.put("/api/v1/notifications/preferences") {
      header(HttpHeaders.Authorization, "Bearer $token")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpdateNotificationPreferencesRequest(orderUpdates = true)))
    }

    insertTestOrderAndOutboxEvent(userId, toStatus = "OUT_FOR_DELIVERY")
    insertTestOrderAndOutboxEvent(userId, toStatus = "COMPLETED")
    EventSequencerWorker(pg.postgresDatabase).runOnce()
    OutboxDispatcherWorker(pg.postgresDatabase, SandboxRazorpayClient()).runOnce()

    val beforeMarkAll = json.decodeFromString<NotificationInboxResponse>(
      client.get("/api/v1/notifications?unread=true") {
        header(HttpHeaders.Authorization, "Bearer $token")
      }.bodyAsText(),
    )
    assertEquals(2, beforeMarkAll.unreadCount)

    val markAllResp = client.post("/api/v1/notifications/read-all") {
      header(HttpHeaders.Authorization, "Bearer $token")
    }
    assertEquals(HttpStatusCode.OK, markAllResp.status)
    val markAllBody = json.decodeFromString<MarkAllNotificationsReadResponse>(markAllResp.bodyAsText())
    assertEquals(2, markAllBody.markedCount)
    assertEquals(0, markAllBody.unreadCount)
  }

  @Test
  fun `delivery provider failure triggers exponential backoff retry then DEAD_LETTER with FAILED inbox status`() = withP16App { emailProvider ->
    val (token, userId) = registerAndLoginCustomer(emailProvider, "notif-fail")
    val (_, outboxId) = insertTestOrderAndOutboxEvent(userId, toStatus = "ACCEPTED")

    val failingProvider = object : NotificationDeliveryProvider {
      override val providerName: String = "AlwaysFailProvider"
      override suspend fun deliver(request: NotificationDispatchRequest): NotificationDeliveryResult {
        throw NotificationDeliveryException("Downstream notification relay unavailable", isTransient = true)
      }
    }

    EventSequencerWorker(pg.postgresDatabase).runOnce()
    val dispatcher = OutboxDispatcherWorker(
      dataSource = pg.postgresDatabase,
      razorpayGateway = SandboxRazorpayClient(),
      maxAttempts = 2,
      notificationDeliveryProvider = failingProvider,
    )

    // Attempt 1 -> scheduled retry (PENDING, attempts = 1)
    dispatcher.runOnce()
    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement("SELECT dispatch_status, attempts FROM outbox_events WHERE id = ?").use { ps ->
        ps.setObject(1, outboxId)
        ps.executeQuery().use { rs ->
          assertTrue(rs.next())
          assertEquals("PENDING", rs.getString("dispatch_status"))
          assertEquals(1, rs.getInt("attempts"))
        }
      }
      // Make eligible for immediate attempt 2
      conn.prepareStatement("UPDATE outbox_events SET next_attempt_at = NOW() - INTERVAL '1 second' WHERE id = ?").use { ps ->
        ps.setObject(1, outboxId)
        ps.executeUpdate()
      }
    }

    // Attempt 2 reaches maxAttempts -> DEAD_LETTER + FAILED inbox entry
    dispatcher.runOnce()
    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement("SELECT dispatch_status, attempts FROM outbox_events WHERE id = ?").use { ps ->
        ps.setObject(1, outboxId)
        ps.executeQuery().use { rs ->
          assertTrue(rs.next())
          assertEquals("DEAD_LETTER", rs.getString("dispatch_status"))
          assertEquals(2, rs.getInt("attempts"))
        }
      }
    }

    val inboxResp = json.decodeFromString<NotificationInboxResponse>(
      client.get("/api/v1/notifications") {
        header(HttpHeaders.Authorization, "Bearer $token")
      }.bodyAsText(),
    )
    val failedItem = inboxResp.notifications.single { it.eventId == outboxId.toString() }
    assertEquals(NotificationDeliveryStatus.FAILED, failedItem.deliveryStatus)
  }

  private class InMemoryCacheStoreP16 : EphemeralCacheStore {
    private val map = ConcurrentHashMap<String, String>()
    override suspend fun setEx(key: String, ttlSeconds: Long, value: String): Boolean {
      map[key] = value
      return true
    }
    override suspend fun get(key: String): String? = map[key]
    override suspend fun getDel(key: String): String? = map.remove(key)
    override suspend fun del(key: String): Boolean = map.remove(key) != null
    override suspend fun incrementWithinWindow(key: String, windowSeconds: Long): Long? = 1L
  }
}

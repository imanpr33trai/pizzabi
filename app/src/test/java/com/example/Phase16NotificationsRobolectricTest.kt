package com.example

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.core.database.PizzoraDatabase
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.notifications.NetworkNotificationRepository
import com.example.notifications.NotificationHelper
import com.example.notifications.NotificationsScreen
import com.example.notifications.NotificationsViewModel
import com.example.notifications.OrderSyncWorker
import com.example.notifications.PizzoraWorkerFactory
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.MarkAllNotificationsReadResponse
import com.pizzora.contracts.MarkNotificationReadResponse
import com.pizzora.contracts.NotificationCategory
import com.pizzora.contracts.NotificationDeliveryStatus
import com.pizzora.contracts.NotificationInboxItemDto
import com.pizzora.contracts.NotificationInboxResponse
import com.pizzora.contracts.NotificationPreferencesDto
import com.pizzora.contracts.UpdateNotificationPreferencesRequest
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class Phase16NotificationsRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private lateinit var context: Context
  private lateinit var db: PizzoraDatabase

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, PizzoraDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() {
    PizzoraWorkerFactory.testOverride = null
    db.close()
  }

  @Test
  fun orderSyncWorker_fetchesUnreadNotifications_deduplicatesInRoom_andPostsLocalNotifications() {
    val application = context.applicationContext as Application
    Shadows.shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    val notifications = mutableListOf(
      NotificationInboxItemDto(
        id = "notif-1",
        eventId = "evt-1",
        title = "Order Baking",
        body = "Order PZ-101 is now baking in our wood-fired oven.",
        category = NotificationCategory.ORDER_UPDATE,
        referenceId = "order-101",
        isRead = false,
        deliveryStatus = NotificationDeliveryStatus.PENDING,
        createdAt = "2026-05-01T12:00:00Z",
      ),
      NotificationInboxItemDto(
        id = "notif-2",
        eventId = "evt-2",
        title = "Order Ready",
        body = "Order PZ-101 is fresh and ready!",
        category = NotificationCategory.ORDER_UPDATE,
        referenceId = "order-101",
        isRead = false,
        deliveryStatus = NotificationDeliveryStatus.PENDING,
        createdAt = "2026-05-01T12:15:00Z",
      ),
    )

    val apiClient = createMockApiClient(
      notificationsProvider = { unreadOnly ->
        val items = if (unreadOnly) notifications.filter { !it.isRead } else notifications.toList()
        NotificationInboxResponse(
          notifications = items,
          unreadCount = notifications.count { !it.isRead },
          serverNow = "2026-05-01T12:16:00Z",
        )
      },
      preferencesHolder = { null },
    )
    val repo = NetworkNotificationRepository(apiClient, db.notificationInboxDao())
    val tokenStorage = FakeTokenStorageP16(sampleCustomerSession())
    val helper = NotificationHelper(context)

    val workerFactory = PizzoraWorkerFactory(
      notificationRepositoryProvider = { repo },
      tokenStorageProvider = { tokenStorage },
      notificationPosterProvider = { helper },
    )
    PizzoraWorkerFactory.testOverride = workerFactory

    val workManager = WorkManager.getInstance(context)
    val req1 = OneTimeWorkRequestBuilder<OrderSyncWorker>().build()
    workManager.enqueue(req1).result.get()
    val info1 = awaitWorkFinished(workManager, req1.id)
    assertEquals("error=${info1?.outputData?.getString("error")}", WorkInfo.State.SUCCEEDED, info1?.state)

    val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val shadowNotifManager = Shadows.shadowOf(notifManager)
    assertEquals(2, shadowNotifManager.allNotifications.size)

    // Add 1 brand-new notification while keeping evt-1 & evt-2 unread in server response
    notifications += NotificationInboxItemDto(
      id = "notif-3",
      eventId = "evt-3",
      title = "Out for Delivery",
      body = "Order PZ-101 is out for delivery.",
      category = NotificationCategory.ORDER_UPDATE,
      referenceId = "order-101",
      isRead = false,
      deliveryStatus = NotificationDeliveryStatus.PENDING,
      createdAt = "2026-05-01T12:20:00Z",
    )

    val req2 = OneTimeWorkRequestBuilder<OrderSyncWorker>().build()
    workManager.enqueue(req2).result.get()
    val info2 = awaitWorkFinished(workManager, req2.id)
    assertEquals("error=${info2?.outputData?.getString("error")}", WorkInfo.State.SUCCEEDED, info2?.state)

    // Total unique posted notifications = 3 (no duplicates for evt-1/evt-2)
    assertEquals(3, shadowNotifManager.allNotifications.size)
  }

  private fun awaitWorkFinished(workManager: WorkManager, workId: java.util.UUID): WorkInfo? {
    val deadline = System.currentTimeMillis() + 5_000L
    while (System.currentTimeMillis() < deadline) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      val info = workManager.getWorkInfoById(workId).get()
      if (info != null && info.state.isFinished) {
        return info
      }
      Thread.sleep(25)
    }
    return workManager.getWorkInfoById(workId).get()
  }

  @Test
  fun notificationsScreen_showsPermissionPromptOnApi33Plus_filtersUnread_marksRead_andUpdatesPreferences() {
    val application = context.applicationContext as Application
    Shadows.shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

    val notifications = mutableListOf(
      NotificationInboxItemDto(
        id = "n-unread",
        eventId = "e-unread",
        title = "Kitchen Accepted",
        body = "Order PZ-202 accepted.",
        category = NotificationCategory.ORDER_UPDATE,
        referenceId = "order-202",
        isRead = false,
        deliveryStatus = NotificationDeliveryStatus.SENT_WS,
        createdAt = "2026-05-01T13:00:00Z",
      ),
      NotificationInboxItemDto(
        id = "n-read",
        eventId = "e-read",
        title = "Order Placed",
        body = "Order PZ-201 placed.",
        category = NotificationCategory.ORDER_UPDATE,
        referenceId = "order-201",
        isRead = true,
        deliveryStatus = NotificationDeliveryStatus.SYNCED,
        createdAt = "2026-05-01T11:00:00Z",
      ),
    )
    var prefs = NotificationPreferencesDto(
      userId = "cust-1",
      orderUpdates = true,
      promoAlerts = true,
      kdsSoundEnabled = true,
      updatedAt = "2026-05-01T10:00:00Z",
    )

    val apiClient = createMockApiClient(
      notificationsProvider = { unreadOnly ->
        val items = if (unreadOnly) notifications.filter { !it.isRead } else notifications.toList()
        NotificationInboxResponse(
          notifications = items,
          unreadCount = notifications.count { !it.isRead },
          serverNow = "2026-05-01T13:01:00Z",
        )
      },
      preferencesHolder = { req ->
        if (req != null) {
          prefs = prefs.copy(
            orderUpdates = req.orderUpdates ?: prefs.orderUpdates,
            promoAlerts = req.promoAlerts ?: prefs.promoAlerts,
            kdsSoundEnabled = req.kdsSoundEnabled ?: prefs.kdsSoundEnabled,
          )
        }
        prefs
      },
      onMarkRead = { id ->
        val idx = notifications.indexOfFirst { it.id == id }
        if (idx >= 0) {
          notifications[idx] = notifications[idx].copy(isRead = true)
        }
        MarkNotificationReadResponse(
          notification = notifications[idx],
          unreadCount = notifications.count { !it.isRead },
        )
      },
      onMarkAllRead = {
        var count = 0
        for (i in notifications.indices) {
          if (!notifications[i].isRead) {
            notifications[i] = notifications[i].copy(isRead = true)
            count++
          }
        }
        MarkAllNotificationsReadResponse(markedCount = count, unreadCount = 0)
      },
    )

    val repo = NetworkNotificationRepository(apiClient, db.notificationInboxDao())
    val helper = NotificationHelper(context)
    val vm = NotificationsViewModel(
      repository = repo,
      tokenStorage = FakeTokenStorageP16(sampleCustomerSession()),
      notificationPoster = helper,
    )

    composeTestRule.setContent {
      val liveState = androidx.compose.runtime.produceState(initialValue = vm.uiState.value, vm.uiState) {
        vm.uiState.collect { value = it }
      }.value
      PizzoraTheme {
        NotificationsScreen(
          uiState = liveState,
          onRefresh = vm::refreshAll,
          onSelectUnreadOnly = vm::setUnreadOnlyFilter,
          onMarkRead = vm::markAsRead,
          onMarkAllRead = vm::markAllAsRead,
          onToggleOrderUpdates = { vm.updatePreferences(orderUpdates = it) },
          onTogglePromoAlerts = { vm.updatePreferences(promoAlerts = it) },
          onNotificationPermissionResult = vm::onPermissionResult,
        )
      }
    }

    vm.refreshAll()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      vm.uiState.value.notifications.size == 2 && vm.uiState.value.preferences != null
    }

    // API 33+ permission prompt card is displayed when permission is denied
    composeTestRule.onNodeWithTag("notifications_permission_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("notifications_request_permission_button").assertIsDisplayed()

    // Grant simulated permission callback -> card disappears
    vm.onPermissionResult(true)
    composeTestRule.waitForIdle()
    assertTrue(composeTestRule.onAllNodesWithTag("notifications_permission_card").fetchSemanticsNodes().isEmpty())

    // Filter Unread hides n-read
    composeTestRule.onNodeWithTag("notifications_list")
      .performScrollToNode(hasTestTag("notifications_filter_unread"))
    composeTestRule.onNodeWithTag("notifications_filter_unread").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("notifications_list")
      .performScrollToNode(hasTestTag("notification_card_n-unread"))
    composeTestRule.onNodeWithTag("notification_card_n-unread").assertIsDisplayed()
    assertTrue(composeTestRule.onAllNodesWithTag("notification_card_n-read").fetchSemanticsNodes().isEmpty())

    // Toggle Order Updates preference off
    composeTestRule.onNodeWithTag("notifications_list")
      .performScrollToNode(hasTestTag("pref_order_updates_switch"))
    composeTestRule.onNodeWithTag("pref_order_updates_switch").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      !prefs.orderUpdates
    }
    assertFalse(prefs.orderUpdates)

    // Switch back to All and mark n-unread read
    composeTestRule.onNodeWithTag("notifications_list")
      .performScrollToNode(hasTestTag("notifications_filter_all"))
    composeTestRule.onNodeWithTag("notifications_filter_all").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("notifications_list")
      .performScrollToNode(hasTestTag("notification_mark_read_n-unread"))
    composeTestRule.onNodeWithTag("notification_mark_read_n-unread").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      vm.uiState.value.unreadCount == 0
    }
    assertEquals(0, vm.uiState.value.unreadCount)
    composeTestRule.onNodeWithTag("notifications_list")
      .performScrollToNode(hasTestTag("notification_card_n-unread"))
    composeTestRule.onNodeWithText("Kitchen Accepted").assertIsDisplayed()
  }

  private fun createMockApiClient(
    notificationsProvider: (Boolean) -> NotificationInboxResponse,
    preferencesHolder: (UpdateNotificationPreferencesRequest?) -> NotificationPreferencesDto?,
    onMarkRead: (String) -> MarkNotificationReadResponse? = { null },
    onMarkAllRead: () -> MarkAllNotificationsReadResponse = { MarkAllNotificationsReadResponse(0, 0) },
  ): PizzoraApiClient {
    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      val headers = headersOf(HttpHeaders.ContentType, "application/json")
      when {
        request.method == HttpMethod.Get && path == "/api/v1/notifications" -> {
          val unreadOnly = request.url.parameters["unread"] == "true"
          respond(
            content = json.encodeToString(notificationsProvider(unreadOnly)),
            status = HttpStatusCode.OK,
            headers = headers,
          )
        }
        request.method == HttpMethod.Post && path == "/api/v1/notifications/read-all" -> {
          respond(
            content = json.encodeToString(onMarkAllRead()),
            status = HttpStatusCode.OK,
            headers = headers,
          )
        }
        request.method == HttpMethod.Post && path.startsWith("/api/v1/notifications/") && path.endsWith("/read") -> {
          val id = path.removePrefix("/api/v1/notifications/").removeSuffix("/read")
          val body = onMarkRead(id)
          if (body != null) {
            respond(json.encodeToString(body), HttpStatusCode.OK, headers)
          } else {
            respond("{}", HttpStatusCode.NotFound, headers)
          }
        }
        request.method == HttpMethod.Get && path == "/api/v1/notifications/preferences" -> {
          val prefs = preferencesHolder(null)
            ?: NotificationPreferencesDto("cust-1", true, true, true, "2026-05-01T10:00:00Z")
          respond(json.encodeToString(prefs), HttpStatusCode.OK, headers)
        }
        request.method == HttpMethod.Put && path == "/api/v1/notifications/preferences" -> {
          val reqText = (request.body as TextContent).text
          val req = json.decodeFromString<UpdateNotificationPreferencesRequest>(reqText)
          val prefs = preferencesHolder(req)
            ?: NotificationPreferencesDto("cust-1", true, true, true, "2026-05-01T10:00:00Z")
          respond(json.encodeToString(prefs), HttpStatusCode.OK, headers)
        }
        else -> respond("{}", HttpStatusCode.NotFound, headers)
      }
    }
    return PizzoraApiClient(
      baseUrl = "http://localhost:8080",
      tokenStorage = FakeTokenStorageP16(sampleCustomerSession()),
      engine = engine,
    )
  }

  private fun sampleCustomerSession() = AuthSession(
    accessToken = "test-jwt",
    refreshToken = "test-refresh",
    user = UserProfileDto(
      id = "cust-1",
      email = "cust@pizzora.test",
      fullName = "Customer One",
      phone = "9876543210",
      role = UserRole.CUSTOMER,
      isActive = true,
      emailVerified = true,
    ),
  )

  private class FakeTokenStorageP16(initial: AuthSession?) : TokenStorage {
    private val _sessionFlow = MutableStateFlow(initial)
    private val _kdsMuted = MutableStateFlow(false)
    override val sessionFlow: StateFlow<AuthSession?> = _sessionFlow.asStateFlow()
    override fun currentSession(): AuthSession? = _sessionFlow.value
    override suspend fun saveSession(session: AuthSession) { _sessionFlow.value = session }
    override suspend fun clearSession() { _sessionFlow.value = null }
    override fun observeKdsSoundMuted(): Flow<Boolean> = _kdsMuted.asStateFlow()
    override suspend fun setKdsSoundMuted(muted: Boolean) { _kdsMuted.value = muted }
  }
}

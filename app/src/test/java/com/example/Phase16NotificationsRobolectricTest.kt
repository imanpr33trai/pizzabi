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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.core.database.NotificationInboxEntity
import com.example.core.database.PizzoraDatabase
import com.example.core.network.PizzoraApiClient
import com.example.core.network.RealtimeEventStream
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
import com.pizzora.contracts.RealtimeConnectionState
import com.pizzora.contracts.RealtimeEventDto
import com.pizzora.contracts.UpdateNotificationPreferencesRequest
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.WsEventsFrame
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
  private val viewModelStore = ViewModelStore()

  private fun createNotificationsVm(
    repo: NetworkNotificationRepository,
    tokenStorage: TokenStorage? = null,
    realtimeEventStream: RealtimeEventStream? = null,
    notificationPoster: com.example.notifications.LocalNotificationPoster? = null,
  ): NotificationsViewModel {
    val factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T = NotificationsViewModel(
        repository = repo,
        tokenStorage = tokenStorage,
        realtimeEventStream = realtimeEventStream,
        notificationPoster = notificationPoster,
      ) as T
    }
    return ViewModelProvider(viewModelStore, factory)["notif_vm_${System.nanoTime()}", NotificationsViewModel::class.java]
  }

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, PizzoraDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() {
    viewModelStore.clear()
    Shadows.shadowOf(Looper.getMainLooper()).idle()
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
    val vm = createNotificationsVm(
      repo = repo,
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

  @Test
  fun notificationsViewModel_handlesRealtimeFrame_syncsInbox_andEmitsAlertEvent() {
    val serverItems = mutableListOf<NotificationInboxItemDto>()
    val apiClient = createMockApiClient(
      notificationsProvider = { unreadOnly ->
        val list = if (unreadOnly) serverItems.filter { !it.isRead } else serverItems.toList()
        NotificationInboxResponse(
          notifications = list,
          unreadCount = serverItems.count { !it.isRead },
          serverNow = "2026-05-01T14:00:00Z",
        )
      },
      preferencesHolder = { null },
    )
    val repo = NetworkNotificationRepository(apiClient, db.notificationInboxDao())
    val stream = FakeRealtimeEventStream()
    val vm = createNotificationsVm(
      repo = repo,
      tokenStorage = FakeTokenStorageP16(sampleCustomerSession()),
      realtimeEventStream = stream,
      notificationPoster = NotificationHelper(context),
    )

    val emittedEvents = mutableListOf<String>()
    val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
      vm.events.collect { emittedEvents += it }
    }
    try {
      vm.startSessionSync("cust-1")
      assertEquals("cust-1", stream.startedUserId)

      // Push a new order status event via WebSocket frame
      serverItems += NotificationInboxItemDto(
        id = "ws-notif-1",
        eventId = "ws-evt-1",
        title = "Out for Delivery",
        body = "Rider picked up order PZ-305.",
        category = NotificationCategory.ORDER_UPDATE,
        referenceId = "order-305",
        isRead = false,
        deliveryStatus = NotificationDeliveryStatus.SENT_WS,
        createdAt = "2026-05-01T14:05:00Z",
      )
      stream.emitFrame(
        WsEventsFrame(
          type = "EVENTS",
          events = listOf(
            RealtimeEventDto(
              eventId = "ws-evt-1",
              streamSeq = 42L,
              eventType = "order.status_changed",
              aggregateType = "order",
              aggregateId = "order-305",
              aggregateVersion = 3L,
              payload = buildJsonObject { put("status", "OUT_FOR_DELIVERY") },
              createdAt = "2026-05-01T14:05:00Z",
            ),
          ),
          serverHighWaterSeq = 42L,
          serverNow = "2026-05-01T14:05:00Z",
        ),
      )

      composeTestRule.waitUntil(timeoutMillis = 5_000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        vm.uiState.value.notifications.any { it.id == "ws-notif-1" } &&
          vm.uiState.value.unreadCount == 1 &&
          emittedEvents.any { it.contains("Out for Delivery") }
      }

      assertEquals(1, vm.uiState.value.unreadCount)
      assertTrue(emittedEvents.any { it == "Out for Delivery: Rider picked up order PZ-305." })
    } finally {
      job.cancel()
    }
  }

  @Test
  fun notificationsViewModel_markAllAsRead_andClearSessionState_wipesRoomInbox() {
    val serverItems = mutableListOf(
      NotificationInboxItemDto(
        id = "n-1",
        eventId = "e-1",
        title = "Oven Lit",
        body = "Order PZ-401 started.",
        category = NotificationCategory.ORDER_UPDATE,
        referenceId = "order-401",
        isRead = false,
        deliveryStatus = NotificationDeliveryStatus.SENT_WS,
        createdAt = "2026-05-01T15:00:00Z",
      ),
      NotificationInboxItemDto(
        id = "n-2",
        eventId = "e-2",
        title = "Weekend Offer",
        body = "Flat 20% off Margherita.",
        category = NotificationCategory.PROMO_ALERT,
        referenceId = null,
        isRead = false,
        deliveryStatus = NotificationDeliveryStatus.PENDING,
        createdAt = "2026-05-01T15:02:00Z",
      ),
    )

    val apiClient = createMockApiClient(
      notificationsProvider = { unreadOnly ->
        val list = if (unreadOnly) serverItems.filter { !it.isRead } else serverItems.toList()
        NotificationInboxResponse(
          notifications = list,
          unreadCount = serverItems.count { !it.isRead },
          serverNow = "2026-05-01T15:03:00Z",
        )
      },
      preferencesHolder = { null },
      onMarkAllRead = {
        var count = 0
        for (i in serverItems.indices) {
          if (!serverItems[i].isRead) {
            serverItems[i] = serverItems[i].copy(isRead = true)
            count++
          }
        }
        MarkAllNotificationsReadResponse(markedCount = count, unreadCount = 0)
      },
    )

    val repo = NetworkNotificationRepository(apiClient, db.notificationInboxDao())
    val stream = FakeRealtimeEventStream()
    val vm = createNotificationsVm(
      repo = repo,
      tokenStorage = FakeTokenStorageP16(sampleCustomerSession()),
      realtimeEventStream = stream,
      notificationPoster = NotificationHelper(context),
    )

    vm.refreshAll()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      vm.uiState.value.unreadCount == 2 && vm.uiState.value.preferences != null
    }

    vm.markAllAsRead()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      vm.uiState.value.unreadCount == 0 && vm.uiState.value.notifications.all { it.isRead }
    }
    assertEquals(0, vm.uiState.value.unreadCount)

    // Signing out clears local Room inbox and stops realtime stream
    vm.clearSessionState()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      vm.uiState.value.notifications.isEmpty() && vm.uiState.value.preferences == null
    }
    assertTrue(stream.stopped)
    assertNull(vm.uiState.value.preferences)
    assertTrue(vm.uiState.value.notifications.isEmpty())
  }

  @Test
  fun notificationHelper_createsChannels_routesCategory_andRespectsPermission() {
    val application = context.applicationContext as Application
    val shadowApp = Shadows.shadowOf(application)
    val helper = NotificationHelper(context)
    val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val shadowNotifManager = Shadows.shadowOf(notifManager)

    val orderItem = NotificationInboxEntity(
      id = "helper-order-1",
      userId = "cust-1",
      eventId = "helper-evt-1",
      title = "Order Baking",
      body = "Your sourdough pizza is in the oven.",
      category = NotificationCategory.ORDER_UPDATE,
      referenceId = "order-99",
      isRead = false,
      createdAtIso = "2026-05-01T16:00:00Z",
    )
    val promoItem = NotificationInboxEntity(
      id = "helper-promo-1",
      userId = "cust-1",
      eventId = "helper-evt-2",
      title = "Garlic Bread Special",
      body = "Free dip on orders above Rs 499.",
      category = NotificationCategory.PROMO_ALERT,
      referenceId = null,
      isRead = false,
      createdAtIso = "2026-05-01T16:05:00Z",
    )

    // Denied permission on API 33+ prevents posting
    shadowApp.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    assertFalse(helper.hasPermission())
    assertFalse(helper.showNotification(orderItem))
    assertEquals(0, shadowNotifManager.allNotifications.size)

    // Granted permission creates both channels and routes by category
    shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    assertTrue(helper.hasPermission())
    assertTrue(helper.showNotification(orderItem))
    assertTrue(helper.showNotification(promoItem))

    val orderChannel = notifManager.getNotificationChannel(NotificationHelper.CHANNEL_ORDER_UPDATES)
    val promoChannel = notifManager.getNotificationChannel(NotificationHelper.CHANNEL_PROMO_ALERTS)
    assertNotNull(orderChannel)
    assertNotNull(promoChannel)
    assertEquals(NotificationManager.IMPORTANCE_HIGH, orderChannel.importance)
    assertEquals(NotificationManager.IMPORTANCE_DEFAULT, promoChannel.importance)

    val posted = shadowNotifManager.allNotifications
    assertEquals(2, posted.size)
    val orderNotif = shadowNotifManager.getNotification(orderItem.id.hashCode())
    val promoNotif = shadowNotifManager.getNotification(promoItem.id.hashCode())
    assertNotNull(orderNotif)
    assertNotNull(promoNotif)
    assertEquals(NotificationHelper.CHANNEL_ORDER_UPDATES, orderNotif.channelId)
    assertEquals(NotificationHelper.CHANNEL_PROMO_ALERTS, promoNotif.channelId)

    val launchIntent = Shadows.shadowOf(orderNotif.contentIntent).savedIntent
    assertTrue(launchIntent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_NOTIFICATIONS, false))
    assertEquals("order-99", launchIntent.getStringExtra(NotificationHelper.EXTRA_REFERENCE_ID))
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

  private class FakeRealtimeEventStream : RealtimeEventStream {
    private val _connectionState = MutableStateFlow(RealtimeConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<RealtimeConnectionState> = _connectionState.asStateFlow()
    private val _frames = MutableSharedFlow<WsEventsFrame>(extraBufferCapacity = 16)
    override val frames: SharedFlow<WsEventsFrame> = _frames.asSharedFlow()
    private val _lastHighWaterSeq = MutableStateFlow(0L)
    override val lastHighWaterSeq: StateFlow<Long> = _lastHighWaterSeq.asStateFlow()

    var startedUserId: String? = null
    var stopped: Boolean = false

    override fun start(sessionUserId: String, initialSinceSeq: Long) {
      startedUserId = sessionUserId
      stopped = false
      _connectionState.value = RealtimeConnectionState.CONNECTED
    }

    override fun stop() {
      stopped = true
      _connectionState.value = RealtimeConnectionState.DISCONNECTED
    }

    override fun updateHighWaterSeq(seq: Long) {
      _lastHighWaterSeq.value = seq
    }

    fun emitFrame(frame: WsEventsFrame) {
      _frames.tryEmit(frame)
    }
  }

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

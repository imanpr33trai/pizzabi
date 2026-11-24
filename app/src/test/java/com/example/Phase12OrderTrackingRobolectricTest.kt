package com.example

import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsProperties
import com.example.core.network.PizzoraApiClient
import com.example.core.network.RealtimeEventStream
import com.example.core.network.WebSocketEventClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.orders.NetworkOrderRepository
import com.example.orders.OrdersScreen
import com.example.orders.OrdersViewModel
import com.pizzora.contracts.EventsReplayResponse
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderItemDto
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderPaymentStatusResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderStatusHistoryDto
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentAttemptSummaryDto
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PaymentSummaryDto
import com.pizzora.contracts.RealtimeConnectionState
import com.pizzora.contracts.RealtimeEventDto
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.WsEventsFrame
import com.pizzora.contracts.WsTicketResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase12OrderTrackingRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private class InMemoryTokenStorage(initial: AuthSession? = null) : TokenStorage {
    private val _session = MutableStateFlow(initial)
    private val _muted = MutableStateFlow(false)
    override val sessionFlow: StateFlow<AuthSession?> = _session.asStateFlow()
    override fun currentSession(): AuthSession? = _session.value
    override suspend fun saveSession(session: AuthSession) {
      _session.value = session
    }
    override suspend fun clearSession() {
      _session.value = null
    }
    override fun observeKdsSoundMuted(): Flow<Boolean> = _muted
    override suspend fun setKdsSoundMuted(muted: Boolean) {
      _muted.value = muted
    }
  }

  private class FakeRealtimeEventStream : RealtimeEventStream {
    private val _connectionState = MutableStateFlow(RealtimeConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<RealtimeConnectionState> = _connectionState.asStateFlow()

    private val _frames = MutableSharedFlow<WsEventsFrame>(extraBufferCapacity = 32)
    override val frames: SharedFlow<WsEventsFrame> = _frames.asSharedFlow()

    private val _lastHighWaterSeq = MutableStateFlow(0L)
    override val lastHighWaterSeq: StateFlow<Long> = _lastHighWaterSeq.asStateFlow()

    var startedUserId: String? = null
    var startedSinceSeq: Long = -1L
    var stopped: Boolean = false

    override fun start(sessionUserId: String, initialSinceSeq: Long) {
      startedUserId = sessionUserId
      startedSinceSeq = initialSinceSeq
      stopped = false
      _connectionState.value = RealtimeConnectionState.CONNECTED
    }

    override fun stop() {
      stopped = true
      _connectionState.value = RealtimeConnectionState.DISCONNECTED
    }

    override fun updateHighWaterSeq(seq: Long) {
      if (seq > _lastHighWaterSeq.value) {
        _lastHighWaterSeq.value = seq
      }
    }

    fun emitConnectionState(state: RealtimeConnectionState) {
      _connectionState.value = state
    }

    fun emitFrame(frame: WsEventsFrame) {
      _frames.tryEmit(frame)
    }
  }

  private fun buildSampleOrderDetail(
    id: String = "ord-p12-1",
    orderNumber: String = "PZ-20261113-1201",
    status: OrderStatus = OrderStatus.PLACED,
    version: Long = 1L,
    paymentMethod: PaymentMethod = PaymentMethod.COD,
    paymentStatus: PaymentStatus = PaymentStatus.COD_PENDING,
    attemptStatus: PaymentAttemptStatus? = null,
    gatewayOrderId: String? = null,
    acceptedAt: String? = null,
    prepStartedAt: String? = null,
    readyAt: String? = null,
    history: List<OrderStatusHistoryDto> = listOf(
      OrderStatusHistoryDto(
        id = "hist-1",
        fromStatus = null,
        toStatus = OrderStatus.PLACED,
        changedBy = "user-p12",
        reason = "Order placed",
        createdAt = "2026-11-13T18:00:00Z",
      ),
    ),
  ): OrderDetailDto = OrderDetailDto(
    id = id,
    orderNumber = orderNumber,
    customerId = "user-p12",
    fulfillmentType = FulfillmentType.DELIVERY,
    scheduleType = ScheduleType.ASAP,
    scheduledSlotId = null,
    deliveryAddress = null,
    status = status,
    version = version,
    subtotalPaise = 49_900L,
    discountPaise = 0L,
    deliveryFeePaise = 4_000L,
    taxPaise = 2_495L,
    cgstPaise = 1_248L,
    sgstPaise = 1_247L,
    totalPaise = 56_395L,
    gstBpsSnapshot = 500,
    couponCodeSnapshot = null,
    isPriority = false,
    acceptedAt = acceptedAt,
    prepStartedAt = prepStartedAt,
    readyAt = readyAt,
    completedAt = null,
    expiresAt = null,
    createdAt = "2026-11-13T18:00:00Z",
    items = listOf(
      OrderItemDto(
        id = "item-p12-1",
        productId = "prod-1",
        productName = "Margherita Classico",
        variantSize = SizeCode.MEDIUM.name,
        crustName = "Sourdough Neapolitan",
        toppings = listOf("Fresh Basil"),
        unitPricePaise = 49_900L,
        quantity = 1,
        lineTotalPaise = 49_900L,
      ),
    ),
    statusHistory = history,
    payment = PaymentSummaryDto(
      id = "pay-p12-1",
      method = paymentMethod,
      status = paymentStatus,
      amountPaise = 56_395L,
      capturedPaise = 0L,
      refundedPaise = 0L,
      currency = "INR",
      latestAttempt = if (attemptStatus != null) {
        PaymentAttemptSummaryDto(
          id = "att-p12-1",
          attemptNumber = 1,
          method = paymentMethod,
          status = attemptStatus,
          amountPaise = 56_395L,
          idempotencyReceipt = "rcpt-p12-1",
          gatewayOrderId = gatewayOrderId,
          gatewayPaymentId = null,
          failureCode = null,
          failureReason = null,
          expiresAt = null,
          createdAt = "2026-11-13T18:00:00Z",
        )
      } else {
        null
      },
    ),
  )

  private fun statusEvent(
    eventId: String,
    streamSeq: Long,
    orderId: String,
    version: Long,
    fromStatus: OrderStatus,
    toStatus: OrderStatus,
    reason: String? = null,
    extra: Map<String, String> = emptyMap(),
  ): RealtimeEventDto {
    val fields = buildMap {
      put("order_id", JsonPrimitive(orderId))
      put("from_status", JsonPrimitive(fromStatus.name))
      put("to_status", JsonPrimitive(toStatus.name))
      if (reason != null) put("reason", JsonPrimitive(reason))
      extra.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }
    return RealtimeEventDto(
      eventId = eventId,
      streamSeq = streamSeq,
      eventType = "order.status_changed",
      aggregateType = "ORDER",
      aggregateId = orderId,
      aggregateVersion = version,
      payload = JsonObject(fields),
      sequencedAt = "2026-11-13T18:0$streamSeq:00Z",
      createdAt = "2026-11-13T18:0$streamSeq:00Z",
    )
  }

  @Test
  fun `orders tracking applies live steps, deduplicates, ignores stale versions, and resyncs causal gaps`() {
    val orderId = "ord-p12-1"
    var serverOrder = buildSampleOrderDetail(id = orderId, status = OrderStatus.PLACED, version = 1L)
    val detailFetchCount = AtomicInteger(0)

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      when {
        path == "/api/v1/orders" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(
            OrderListResponse(
              orders = listOf(
                OrderSummaryDto(
                  id = serverOrder.id,
                  orderNumber = serverOrder.orderNumber,
                  fulfillmentType = serverOrder.fulfillmentType,
                  scheduleType = serverOrder.scheduleType,
                  status = serverOrder.status,
                  version = serverOrder.version,
                  totalPaise = serverOrder.totalPaise,
                  itemCount = 1,
                  paymentMethod = serverOrder.payment.method,
                  paymentStatus = serverOrder.payment.status,
                  isPriority = false,
                  createdAt = serverOrder.createdAt,
                ),
              ),
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/orders/$orderId" && request.method == HttpMethod.Get -> {
          detailFetchCount.incrementAndGet()
          respond(
            content = json.encodeToString(serverOrder),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected API call: ${request.method.value} $path")
      }
    }

    val apiClient = PizzoraApiClient(
      baseUrl = "http://localhost:8080",
      tokenStorage = InMemoryTokenStorage(),
      engine = engine,
    )
    val fakeStream = FakeRealtimeEventStream()
    val ordersVm = OrdersViewModel(
      orderRepository = NetworkOrderRepository(apiClient),
      realtimeEventStream = fakeStream,
    )

    ordersVm.startLiveTracking("user-p12")
    ordersVm.loadOrders()
    ordersVm.openOrderDetail(orderId)

    composeTestRule.setContent {
      MaterialTheme {
        val state by ordersVm.uiState.collectAsState()
        OrdersScreen(
          uiState = state,
          onRefreshOrders = { ordersVm.loadOrders() },
          onOpenOrder = { ordersVm.openOrderDetail(it) },
          onBackToList = { ordersVm.clearSelectedOrder() },
          onCancelOrder = { ordersVm.cancelSelectedOrder(it) },
          onBrowseMenu = {},
        )
      }
    }

    waitUntilMainLooper {
      ordersVm.uiState.value.selectedOrder?.id == orderId &&
        ordersVm.uiState.value.selectedOrder?.version == 1L &&
        composeTestRule.onAllNodesWithTag("order_tracking_stepper_card").fetchSemanticsNodes().isNotEmpty()
    }

    composeTestRule.onNodeWithTag("realtime_connection_badge").assertIsDisplayed().assertTextContains("Live Updates")
    assertOrderVersion(expectedVersion = 1L)
    composeTestRule.onNodeWithTag("order_tracking_version_text").assertTextContains("Live updates active")
    composeTestRule.onNodeWithTag("tracking_step_placed").assertIsDisplayed()

    // 1) Normal sequential event: PLACED (v1) -> ACCEPTED (v2)
    val acceptedEvent = statusEvent(
      eventId = "evt-accept-2",
      streamSeq = 10L,
      orderId = orderId,
      version = 2L,
      fromStatus = OrderStatus.PLACED,
      toStatus = OrderStatus.ACCEPTED,
      reason = "Kitchen confirmed",
      extra = mapOf("accepted_at" to "2026-11-13T18:02:00Z"),
    )
    fakeStream.emitFrame(
      WsEventsFrame(
        type = "EVENTS",
        events = listOf(acceptedEvent),
        serverHighWaterSeq = 10L,
        serverNow = "2026-11-13T18:02:00Z",
      ),
    )

    waitUntilMainLooper {
      ordersVm.uiState.value.selectedOrder?.status == OrderStatus.ACCEPTED &&
        ordersVm.uiState.value.selectedOrder?.version == 2L
    }
    assertOrderVersion(expectedVersion = 2L)
    assertEquals(10L, fakeStream.lastHighWaterSeq.value)
    assertEquals(1, detailFetchCount.get())

    // 2) Duplicate event_id and stale version (<= 2) must be dropped cleanly
    val staleEvent = statusEvent(
      eventId = "evt-stale-2",
      streamSeq = 11L,
      orderId = orderId,
      version = 2L,
      fromStatus = OrderStatus.ACCEPTED,
      toStatus = OrderStatus.CANCELLED,
      reason = "Should be ignored",
    )
    fakeStream.emitFrame(
      WsEventsFrame(
        type = "EVENTS",
        events = listOf(acceptedEvent, staleEvent),
        serverHighWaterSeq = 11L,
        serverNow = "2026-11-13T18:02:30Z",
      ),
    )
    waitUntilMainLooper { ordersVm.uiState.value.lastHighWaterSeq == 11L }
    assertEquals(OrderStatus.ACCEPTED, ordersVm.uiState.value.selectedOrder?.status)
    assertEquals(2L, ordersVm.uiState.value.selectedOrder?.version)
    assertEquals(2, ordersVm.uiState.value.selectedOrder?.statusHistory?.size)
    assertEquals(0, ordersVm.uiState.value.resyncCount)

    // 3) Causal gap: jump from v2 -> v4 (skipping PREPARING v3) triggers immediate snapshot resync to READY v4
    serverOrder = buildSampleOrderDetail(
      id = orderId,
      status = OrderStatus.READY,
      version = 4L,
      acceptedAt = "2026-11-13T18:02:00Z",
      prepStartedAt = "2026-11-13T18:03:00Z",
      readyAt = "2026-11-13T18:08:00Z",
      history = listOf(
        OrderStatusHistoryDto("hist-1", null, OrderStatus.PLACED, "user-p12", "Order placed", "2026-11-13T18:00:00Z"),
        OrderStatusHistoryDto("hist-2", OrderStatus.PLACED, OrderStatus.ACCEPTED, "staff-1", "Kitchen confirmed", "2026-11-13T18:02:00Z"),
        OrderStatusHistoryDto("hist-3", OrderStatus.ACCEPTED, OrderStatus.PREPARING, "staff-1", "In oven", "2026-11-13T18:03:00Z"),
        OrderStatusHistoryDto("hist-4", OrderStatus.PREPARING, OrderStatus.READY, "staff-1", "Fresh out of oven", "2026-11-13T18:08:00Z"),
      ),
    )

    val readyEventWithGap = statusEvent(
      eventId = "evt-ready-4",
      streamSeq = 13L,
      orderId = orderId,
      version = 4L,
      fromStatus = OrderStatus.PREPARING,
      toStatus = OrderStatus.READY,
      reason = "Fresh out of oven",
      extra = mapOf("ready_at" to "2026-11-13T18:08:00Z"),
    )
    fakeStream.emitFrame(
      WsEventsFrame(
        type = "EVENTS",
        events = listOf(readyEventWithGap),
        serverHighWaterSeq = 13L,
        serverNow = "2026-11-13T18:08:00Z",
      ),
    )

    waitUntilMainLooper {
      ordersVm.uiState.value.resyncCount == 1 &&
        ordersVm.uiState.value.selectedOrder?.status == OrderStatus.READY &&
        ordersVm.uiState.value.selectedOrder?.version == 4L
    }

    assertEquals(2, detailFetchCount.get())
    assertOrderVersion(expectedVersion = 4L)
    composeTestRule.onNodeWithTag("tracking_step_ready").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("order_detail_lazy_column").performScrollToNode(hasTestTag("order_detail_status_badge"))
    composeTestRule.onNodeWithTag("order_detail_status_badge").assertTextContains("Ready for Delivery")

    // Verify reconnecting indicator updates reactively
    fakeStream.emitConnectionState(RealtimeConnectionState.RECONNECTING)
    waitUntilMainLooper {
      ordersVm.uiState.value.connectionState == RealtimeConnectionState.RECONNECTING
    }
    composeTestRule.onNodeWithTag("realtime_connection_badge").assertTextContains("Reconnecting", substring = true)
  }

  @Test
  fun `payment gateway_ready frame at same aggregate_version updates gatewayOrderId without resync`() {
    val orderId = "ord-p12-pay"
    val pendingOrder = buildSampleOrderDetail(
      id = orderId,
      status = OrderStatus.PENDING_PAYMENT,
      version = 1L,
      paymentMethod = PaymentMethod.RAZORPAY,
      paymentStatus = PaymentStatus.UNPAID,
      attemptStatus = PaymentAttemptStatus.INITIATING,
      gatewayOrderId = null,
    )
    val detailFetchCount = AtomicInteger(0)

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      when {
        path == "/api/v1/orders/$orderId" -> {
          detailFetchCount.incrementAndGet()
          respond(json.encodeToString(pendingOrder), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        path == "/api/v1/orders/$orderId/payment" -> respond(
          content = json.encodeToString(
            OrderPaymentStatusResponse(
              orderId = orderId,
              orderNumber = pendingOrder.orderNumber,
              orderStatus = OrderStatus.PENDING_PAYMENT,
              payment = pendingOrder.payment,
              expiresAt = "2026-11-13T18:15:00Z",
              refunds = emptyList(),
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        else -> error("Unexpected API call: ${request.method.value} $path")
      }
    }

    val apiClient = PizzoraApiClient(
      baseUrl = "http://localhost:8080",
      tokenStorage = InMemoryTokenStorage(),
      engine = engine,
    )
    val fakeStream = FakeRealtimeEventStream()
    val ordersVm = OrdersViewModel(
      orderRepository = NetworkOrderRepository(apiClient),
      initialPollDelayMs = 2_000L,
      maxPollAttempts = 1,
      realtimeEventStream = fakeStream,
    )

    ordersVm.openOrderDetail(orderId)
    waitUntilMainLooper { ordersVm.uiState.value.paymentStatus != null }
    assertEquals(1, detailFetchCount.get())

    fakeStream.emitFrame(
      WsEventsFrame(
        type = "EVENTS",
        events = listOf(
          RealtimeEventDto(
            eventId = "evt-gw-ready-1",
            streamSeq = 21L,
            eventType = "payment.gateway_ready",
            aggregateType = "ORDER",
            aggregateId = orderId,
            aggregateVersion = 1L,
            payload = JsonObject(
              mapOf(
                "order_id" to JsonPrimitive(orderId),
                "payment_attempt_id" to JsonPrimitive("att-p12-1"),
                "gateway_order_id" to JsonPrimitive("order_rzp_ws_999"),
              ),
            ),
            sequencedAt = "2026-11-13T18:00:05Z",
            createdAt = "2026-11-13T18:00:05Z",
          ),
        ),
        serverHighWaterSeq = 21L,
        serverNow = "2026-11-13T18:00:05Z",
      ),
    )

    waitUntilMainLooper {
      ordersVm.uiState.value.paymentStatus?.payment?.latestAttempt?.gatewayOrderId == "order_rzp_ws_999"
    }
    assertEquals(0, ordersVm.uiState.value.resyncCount)
    assertEquals(1, detailFetchCount.get())
    assertEquals(
      PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT,
      ordersVm.uiState.value.selectedOrder?.payment?.latestAttempt?.status,
    )
  }

  @Test
  fun `WebSocketEventClient falls back to HTTP replay when socket fails and advances cursor`() = runBlocking {
    val replayEvent = statusEvent(
      eventId = "evt-replay-1",
      streamSeq = 42L,
      orderId = "ord-p12-replay",
      version = 2L,
      fromStatus = OrderStatus.PLACED,
      toStatus = OrderStatus.ACCEPTED,
    )
    val replayCalled = AtomicInteger(0)

    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/api/v1/auth/ws-ticket" -> respond(
          content = json.encodeToString(WsTicketResponse(ticket = "wst_test", expiresInSeconds = 45)),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        "/api/v1/events/replay" -> {
          replayCalled.incrementAndGet()
          respond(
            content = json.encodeToString(
              EventsReplayResponse(
                events = listOf(replayEvent),
                serverHighWaterSeq = 45L,
                hasMore = false,
                serverNow = "2026-11-13T18:10:00Z",
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> respond("Upgrade not supported in MockEngine", HttpStatusCode.BadRequest)
      }
    }

    val apiClient = PizzoraApiClient(
      baseUrl = "http://localhost:8080",
      tokenStorage = InMemoryTokenStorage(),
      engine = engine,
    )
    val wsClient = WebSocketEventClient(
      apiClient = apiClient,
      initialBackoffMs = 5_000L,
      ioDispatcher = Dispatchers.Unconfined,
    )

    wsClient.start("user-p12", initialSinceSeq = 10L)
    val frame = withTimeout(5_000L) {
      wsClient.frames.first()
    }
    wsClient.stop()

    assertTrue(replayCalled.get() >= 1)
    assertEquals(45L, wsClient.lastHighWaterSeq.value)
    assertEquals(1, frame.events.size)
    assertEquals("evt-replay-1", frame.events.single().eventId)
  }

  /** Raw version is semantics-only; the visible copy is friendly freshness text. */
  private fun assertOrderVersion(expectedVersion: Long) {
    val actual = composeTestRule.onNodeWithTag("order_tracking_version_text")
      .fetchSemanticsNode()
      .config[SemanticsProperties.ContentDescription]
      .joinToString()
    assertEquals("Order version v$expectedVersion", actual)
  }

  private fun waitUntilMainLooper(
    timeoutMs: Long = 5_000,
    condition: () -> Boolean,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.waitForIdle()
      if (condition()) return
      if (System.currentTimeMillis() > deadline) {
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for Phase 12 Robolectric/Compose state")
      }
      Thread.sleep(20)
    }
  }
}

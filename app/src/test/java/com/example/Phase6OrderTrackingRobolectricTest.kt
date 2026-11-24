package com.example

import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.core.network.RealtimeEventStream
import com.example.orders.OrderRepository
import com.example.orders.OrdersScreen
import com.example.orders.OrdersViewModel
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.AvailableSlotsResponse
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.FailPaymentAttemptRequest
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderItemDto
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderPaymentStatusResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderStatusHistoryDto
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PaymentSummaryDto
import com.pizzora.contracts.RealtimeConnectionState
import com.pizzora.contracts.RetryPaymentRequest
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
import com.pizzora.contracts.VerifyRazorpayPaymentResponse
import com.pizzora.contracts.WsEventsFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase6OrderTrackingRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private class FakeRealtimeEventStream : RealtimeEventStream {
    private val _connectionState = MutableStateFlow(RealtimeConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<RealtimeConnectionState> = _connectionState.asStateFlow()
    private val _frames = MutableSharedFlow<WsEventsFrame>(extraBufferCapacity = 16)
    override val frames: SharedFlow<WsEventsFrame> = _frames.asSharedFlow()
    private val _lastHighWaterSeq = MutableStateFlow(0L)
    override val lastHighWaterSeq: StateFlow<Long> = _lastHighWaterSeq.asStateFlow()

    override fun start(sessionUserId: String, initialSinceSeq: Long) {
      _connectionState.value = RealtimeConnectionState.CONNECTED
    }

    override fun stop() {
      _connectionState.value = RealtimeConnectionState.DISCONNECTED
    }

    override fun updateHighWaterSeq(seq: Long) {
      if (seq > _lastHighWaterSeq.value) _lastHighWaterSeq.value = seq
    }

    fun emitConnectionState(state: RealtimeConnectionState) {
      _connectionState.value = state
    }
  }

  private class FakeOrderRepository(
    private val details: MutableMap<String, OrderDetailDto>,
  ) : OrderRepository {
    var capturedCancelReason: String? = null
      private set
    var cancelDelayMs: Long = 0L

    override suspend fun fetchOrders(): Result<OrderListResponse> = Result.success(OrderListResponse(emptyList()))

    override suspend fun fetchOrderDetail(orderId: String): Result<OrderDetailDto> =
      details[orderId]?.let { Result.success(it) }
        ?: Result.failure(IllegalArgumentException("Unknown order $orderId"))

    override suspend fun cancelOrder(orderId: String, reason: String?): Result<OrderDetailDto> {
      if (cancelDelayMs > 0L) delay(cancelDelayMs)
      capturedCancelReason = reason
      val updated = details.getValue(orderId).copy(
        status = OrderStatus.CANCELLED,
        version = details.getValue(orderId).version + 1,
        payment = details.getValue(orderId).payment.copy(status = PaymentStatus.CANCELLED),
      )
      details[orderId] = updated
      return Result.success(updated)
    }

    override suspend fun fetchOrderPaymentStatus(orderId: String): Result<OrderPaymentStatusResponse> {
      val order = details[orderId] ?: return Result.failure(IllegalArgumentException("Unknown order $orderId"))
      return Result.success(
        OrderPaymentStatusResponse(
          orderId = order.id,
          orderNumber = order.orderNumber,
          orderStatus = order.status,
          payment = order.payment,
        ),
      )
    }

    override suspend fun getAddresses(): Result<List<AddressDto>> = unused()
    override suspend fun createAddress(request: UpsertAddressRequest): Result<AddressDto> = unused()
    override suspend fun updateAddress(addressId: String, request: UpsertAddressRequest): Result<AddressDto> = unused()
    override suspend fun deleteAddress(addressId: String): Result<Unit> = unused()
    override suspend fun checkDeliveryZone(pincode: String): Result<DeliveryZoneCheckResponse> = unused()
    override suspend fun getAvailableSlots(type: FulfillmentType, date: String?): Result<AvailableSlotsResponse> = unused()
    override suspend fun placeOrder(idempotencyKey: String, request: CheckoutRequest): Result<CheckoutResponse> = unused()
    override suspend fun retryOrderPayment(
      orderId: String,
      idempotencyKey: String,
      reason: String?,
    ): Result<OrderPaymentStatusResponse> = unused()
    override suspend fun reportPaymentFailure(
      orderId: String,
      request: FailPaymentAttemptRequest,
    ): Result<OrderPaymentStatusResponse> = unused()
    override suspend fun verifyRazorpayPayment(
      request: VerifyRazorpayPaymentRequest,
    ): Result<VerifyRazorpayPaymentResponse> = unused()

    private fun <T> unused(): Result<T> = Result.failure(IllegalStateException("unused in tracking tests"))
  }

  private fun detail(
    id: String,
    status: OrderStatus = OrderStatus.PLACED,
    version: Long = 1L,
    method: PaymentMethod = PaymentMethod.COD,
    paymentStatus: PaymentStatus = PaymentStatus.COD_PENDING,
    fulfillmentType: FulfillmentType = FulfillmentType.PICKUP,
    acceptedAt: String? = null,
    prepStartedAt: String? = null,
  ): OrderDetailDto = OrderDetailDto(
    id = id,
    orderNumber = "PZ-2026-$id",
    customerId = "user-p6",
    fulfillmentType = fulfillmentType,
    scheduleType = ScheduleType.ASAP,
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
    readyAt = null,
    completedAt = null,
    expiresAt = null,
    createdAt = "2026-11-19T14:00:00Z",
    items = listOf(
      OrderItemDto(
        id = "item-$id",
        productId = "prod-1",
        productName = "Margherita Classico",
        variantSize = SizeCode.MEDIUM.name,
        crustName = "Sourdough Neapolitan",
        toppings = emptyList(),
        unitPricePaise = 49_900L,
        quantity = 1,
        lineTotalPaise = 49_900L,
      ),
    ),
    statusHistory = listOf(
      OrderStatusHistoryDto("h1", null, OrderStatus.PLACED, "user-p6", "Placed", "2026-11-19T14:00:00Z"),
      OrderStatusHistoryDto(
        "h2",
        OrderStatus.PLACED,
        OrderStatus.ACCEPTED,
        "kitchen-1",
        "Confirmed",
        "2026-11-19T14:15:00Z",
      ),
    ),
    payment = PaymentSummaryDto(
      id = "pay-$id",
      method = method,
      status = paymentStatus,
      amountPaise = 56_395L,
      capturedPaise = if (paymentStatus == PaymentStatus.CAPTURED) 56_395L else 0L,
      refundedPaise = 0L,
      currency = "INR",
      latestAttempt = null,
    ),
  )

  private fun render(vm: OrdersViewModel) {
    composeTestRule.setContent {
      MaterialTheme {
        val state by vm.uiState.collectAsState()
        OrdersScreen(
          uiState = state,
          onRefreshOrders = { vm.loadOrders() },
          onOpenOrder = { vm.openOrderDetail(it) },
          onBackToList = { vm.clearSelectedOrder() },
          onCancelOrder = { vm.cancelSelectedOrder(it) },
          onBrowseMenu = {},
          onSetOrderListFilter = { vm.setOrderListFilter(it) },
          onDismissOrderPlacedCelebration = { vm.dismissOrderPlacedCelebration() },
        )
      }
    }
  }

  @Test
  fun `stepper uses friendly labels IST times and version semantics reflect connection state`() {
    val stream = FakeRealtimeEventStream()
    val order = detail(
      id = "ord-track-1",
      status = OrderStatus.ACCEPTED,
      version = 4L,
      acceptedAt = "2026-11-19T14:15:00Z",
    )
    val vm = OrdersViewModel(
      FakeOrderRepository(mutableMapOf(order.id to order)),
      realtimeEventStream = stream,
    )
    vm.startLiveTracking("user-p6")
    vm.openOrderDetail(order.id)
    render(vm)
    waitUntilMainLooper {
      vm.uiState.value.selectedOrder?.id == order.id &&
        composeTestRule.onAllNodesWithTag("order_tracking_stepper_card").fetchSemanticsNodes().isNotEmpty()
    }

    val versionNode = composeTestRule.onNodeWithTag("order_tracking_version_text")
    versionNode.assertIsDisplayed().assertTextContains("Live updates active")
    assertEquals(
      "Order version v4",
      versionNode.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(),
    )
    assertEquals(
      LiveRegionMode.Polite,
      composeTestRule.onNodeWithTag("order_status_live_region")
        .fetchSemanticsNode()
        .config[SemanticsProperties.LiveRegion],
    )
    assertEquals(
      "Step 2 of 5: Confirmed by Kitchen, Started at 7:45 PM",
      composeTestRule.onNodeWithTag("tracking_step_accepted")
        .fetchSemanticsNode()
        .config[SemanticsProperties.ContentDescription]
        .joinToString(),
    )
    composeTestRule.onNodeWithTag("tracking_step_placed").assertIsDisplayed()
      .assertTextContains("7:30 PM")

    composeTestRule.onNodeWithTag("order_detail_lazy_column")
      .performScrollToNode(hasTestTag("order_detail_status_badge"))
    composeTestRule.onNodeWithTag("order_detail_status_badge").assertTextContains("Confirmed by Kitchen")
    composeTestRule.onNodeWithTag("order_detail_lazy_column")
      .performScrollToNode(hasTestTag("order_detail_payment_status"))
    composeTestRule.onNodeWithTag("order_detail_payment_status")
      .assertTextContains("Pickup · Cash on Delivery (Pay at Pickup)")

    stream.emitConnectionState(RealtimeConnectionState.RECONNECTING)
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("order_tracking_version_text")
        .fetchSemanticsNodes()
        .firstOrNull()
        ?.config?.get(SemanticsProperties.Text)
        ?.joinToString()
        ?.contains("Reconnecting") == true
    }
    assertEquals(
      "Order version v4",
      composeTestRule.onNodeWithTag("order_tracking_version_text")
        .fetchSemanticsNode()
        .config[SemanticsProperties.ContentDescription]
        .joinToString(),
    )
  }

  @Test
  fun `active step semantics say started at instead of completed at`() {
    val order = detail(
      id = "ord-track-active",
      status = OrderStatus.PREPARING,
      acceptedAt = "2026-11-19T14:15:00Z",
      prepStartedAt = "2026-11-19T14:20:00Z",
    )
    val vm = OrdersViewModel(
      FakeOrderRepository(mutableMapOf(order.id to order)),
      realtimeEventStream = FakeRealtimeEventStream(),
    )
    vm.startLiveTracking("user-p6")
    vm.openOrderDetail(order.id)
    render(vm)
    waitUntilMainLooper {
      vm.uiState.value.selectedOrder?.id == order.id &&
        composeTestRule.onAllNodesWithTag("order_tracking_stepper_card").fetchSemanticsNodes().isNotEmpty()
    }

    val description = composeTestRule.onNodeWithTag("tracking_step_preparing")
      .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
    assertTrue(description, description.contains("In the Oven"))
    assertTrue(description, description.contains("Started at 7:50 PM"))
    assertTrue(description, !description.contains("Completed at"))
  }

  @Test
  fun `cancel dialog discloses captured refund offers quick reasons and disables confirm while cancelling`() {
    val order = detail(
      id = "ord-cancel-1",
      status = OrderStatus.PLACED,
      version = 1L,
      method = PaymentMethod.RAZORPAY,
      paymentStatus = PaymentStatus.CAPTURED,
    )
    val repository = FakeOrderRepository(mutableMapOf(order.id to order)).apply { cancelDelayMs = 200L }
    val vm = OrdersViewModel(repository)
    vm.openOrderDetail(order.id)
    render(vm)
    waitUntilMainLooper { vm.uiState.value.selectedOrder?.id == order.id }

    composeTestRule.onNodeWithTag("order_detail_lazy_column")
      .performScrollToNode(hasTestTag("cancel_order_button"))
    composeTestRule.onNodeWithTag("cancel_order_button").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("cancel_reason_input").fetchSemanticsNodes().isNotEmpty()
    }

    composeTestRule.onNodeWithTag("cancel_dialog_title").assertIsDisplayed()
    assertTrue(
      composeTestRule.onAllNodes(hasText("refund will be processed to your original payment method", substring = true))
        .fetchSemanticsNodes()
        .isNotEmpty(),
    )
    listOf(
      "cancel_reason_chip_changed_mind",
      "cancel_reason_chip_ordered_wrong_item",
      "cancel_reason_chip_taking_too_long",
    ).forEach { tag -> composeTestRule.onNodeWithTag(tag).assertIsDisplayed() }

    composeTestRule.onNodeWithTag("cancel_reason_chip_taking_too_long").performClick()
    composeTestRule.onNodeWithTag("cancel_reason_input").assertTextContains("Taking too long")
    composeTestRule.onNodeWithTag("confirm_cancel_order_button").performClick()
    waitUntilMainLooper { vm.uiState.value.isCancelling }
    composeTestRule.onNodeWithTag("confirm_cancel_order_button")
      .assertIsNotEnabled()
      .assertTextContains("Cancelling…")
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(300L, TimeUnit.MILLISECONDS)

    waitUntilMainLooper {
      vm.uiState.value.selectedOrder?.status == OrderStatus.CANCELLED &&
        !vm.uiState.value.isCancelling
    }
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("confirm_cancel_order_button").fetchSemanticsNodes().isEmpty()
    }
    assertEquals("Taking too long", repository.capturedCancelReason)
    assertTrue(composeTestRule.onAllNodesWithTag("cancel_order_button").fetchSemanticsNodes().isEmpty())
    composeTestRule.onNodeWithTag("order_detail_lazy_column")
      .performScrollToNode(hasTestTag("tracking_terminal_banner"))
    composeTestRule.onNodeWithTag("tracking_terminal_banner").assertExists()
      .assert(hasAnyDescendant(hasText("This order has been cancelled.")))
  }

  @Test
  fun `celebration banner appears only for the newly created order and clears on navigation and dismiss`() {
    val order = detail(id = "ord-new-1", status = OrderStatus.PLACED)
    val vm = OrdersViewModel(FakeOrderRepository(mutableMapOf(order.id to order)))
    vm.showCreatedOrder(order, openPaymentFlowIfPending = false)
    render(vm)
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("order_placed_celebration_banner").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("order_placed_celebration_banner")
      .assert(hasAnyDescendant(hasText("Order confirmed! The kitchen has received your order.")))
    composeTestRule.onNodeWithTag("dismiss_order_placed_celebration").performClick()
    waitUntilMainLooper {
      vm.uiState.value.celebrationOrderId == null &&
        composeTestRule.onAllNodesWithTag("order_placed_celebration_banner").fetchSemanticsNodes().isEmpty()
    }

    vm.showCreatedOrder(order.copy(version = 2L), openPaymentFlowIfPending = false)
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("order_placed_celebration_banner").fetchSemanticsNodes().isNotEmpty()
    }
    vm.openOrderDetail(order.id)
    waitUntilMainLooper {
      vm.uiState.value.selectedOrder?.id == order.id &&
        vm.uiState.value.celebrationOrderId == null &&
        composeTestRule.onAllNodesWithTag("order_placed_celebration_banner").fetchSemanticsNodes().isEmpty()
    }

    vm.showCreatedOrder(order.copy(version = 3L), openPaymentFlowIfPending = false)
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("order_placed_celebration_banner").fetchSemanticsNodes().isNotEmpty()
    }
    vm.clearSelectedOrder()
    waitUntilMainLooper { composeTestRule.onAllNodesWithTag("order_detail_screen").fetchSemanticsNodes().isEmpty() }
    assertTrue(composeTestRule.onAllNodesWithTag("order_placed_celebration_banner").fetchSemanticsNodes().isEmpty())
  }

  @Test
  fun `celebration does not disturb pending Razorpay sheet state or launch restoration`() {
    val pending = detail(
      id = "ord-pending-1",
      status = OrderStatus.PENDING_PAYMENT,
      method = PaymentMethod.RAZORPAY,
      paymentStatus = PaymentStatus.UNPAID,
    )
    val vm = OrdersViewModel(FakeOrderRepository(mutableMapOf(pending.id to pending)))

    vm.showCreatedOrder(pending, openPaymentFlowIfPending = true)
    assertTrue(vm.uiState.value.isPaymentSheetOpen)
    assertEquals(pending.id, vm.uiState.value.celebrationOrderId)
    assertNull(vm.uiState.value.pendingRazorpayLaunch)

    vm.restorePendingRazorpayLaunch(pending.id, "order_gw_1")
    assertEquals("order_gw_1", vm.uiState.value.pendingRazorpayLaunch?.gatewayOrderId)
    assertEquals(pending.id, vm.uiState.value.celebrationOrderId)

    vm.clearSelectedOrder()
    assertNull(vm.uiState.value.pendingRazorpayLaunch)
    assertNull(vm.uiState.value.celebrationOrderId)
    assertTrue(!vm.uiState.value.isPaymentSheetOpen)
  }

  private fun waitUntilMainLooper(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.waitForIdle()
      if (condition()) return
      if (System.currentTimeMillis() > deadline) {
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for Phase 6 tracking state")
      }
      Thread.sleep(20)
    }
  }
}

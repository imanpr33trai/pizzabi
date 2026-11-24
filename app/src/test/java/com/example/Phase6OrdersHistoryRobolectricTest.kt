package com.example

import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assert
import com.example.orders.OrderListFilter
import com.example.orders.OrderRepository
import com.example.orders.OrdersScreen
import com.example.orders.OrdersViewModel
import com.example.orders.filterOrders
import com.example.orders.isPastOrder
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.AvailableSlotsResponse
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.FailPaymentAttemptRequest
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.RetryPaymentRequest
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
import com.pizzora.contracts.VerifyRazorpayPaymentResponse
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
class Phase6OrdersHistoryRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private class FakeOrderRepository(
    private var summaries: List<OrderSummaryDto>,
  ) : OrderRepository {
    override suspend fun fetchOrders(): Result<OrderListResponse> = Result.success(OrderListResponse(summaries))

    override suspend fun fetchOrderDetail(orderId: String): Result<OrderDetailDto> =
      Result.failure(IllegalStateException("detail fetch is not used by history tests"))

    override suspend fun cancelOrder(orderId: String, reason: String?): Result<OrderDetailDto> =
      Result.failure(IllegalStateException("cancel is not used by history tests"))

    override suspend fun fetchOrderPaymentStatus(orderId: String) =
      Result.failure<com.pizzora.contracts.OrderPaymentStatusResponse>(
        IllegalStateException("payment status is not used by history tests"),
      )

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
    ): Result<com.pizzora.contracts.OrderPaymentStatusResponse> = unused()
    override suspend fun reportPaymentFailure(
      orderId: String,
      request: FailPaymentAttemptRequest,
    ): Result<com.pizzora.contracts.OrderPaymentStatusResponse> = unused()
    override suspend fun verifyRazorpayPayment(
      request: VerifyRazorpayPaymentRequest,
    ): Result<VerifyRazorpayPaymentResponse> = unused()

    private fun <T> unused(): Result<T> = Result.failure(IllegalStateException("unused in history tests"))
  }

  private fun summary(
    id: String,
    status: OrderStatus,
    method: PaymentMethod = PaymentMethod.COD,
    paymentStatus: PaymentStatus = PaymentStatus.COD_PENDING,
    fulfillmentType: FulfillmentType = FulfillmentType.PICKUP,
  ) = OrderSummaryDto(
    id = id,
    orderNumber = "PZ-$id",
    fulfillmentType = fulfillmentType,
    scheduleType = ScheduleType.ASAP,
    status = status,
    version = 1L,
    totalPaise = 56_395L,
    itemCount = 2,
    paymentMethod = method,
    paymentStatus = paymentStatus,
    isPriority = false,
    createdAt = "2026-11-19T14:12:00Z",
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
  fun `pure filter classifies active past and all from real order status`() {
    val active = summary("active", OrderStatus.PENDING_PAYMENT)
    val past = summary("past", OrderStatus.COMPLETED)
    val cancelled = summary("cancelled", OrderStatus.CANCELLED)
    val expired = summary("expired", OrderStatus.EXPIRED)
    val preparing = summary("preparing", OrderStatus.PREPARING)
    val orders = listOf(active, past, cancelled, expired, preparing)

    assertTrue(!isPastOrder(active.status) && !isPastOrder(preparing.status))
    assertTrue(isPastOrder(past.status) && isPastOrder(cancelled.status) && isPastOrder(expired.status))
    assertEquals(listOf(active, preparing), filterOrders(orders, OrderListFilter.ACTIVE))
    assertEquals(listOf(past, cancelled, expired), filterOrders(orders, OrderListFilter.PAST))
    assertEquals(orders, filterOrders(orders, OrderListFilter.ALL))
  }

  @Test
  fun `filter tabs show counts and only the selected segment across list refreshes`() {
    val orders = listOf(
      summary("active-1", OrderStatus.PLACED),
      summary("active-2", OrderStatus.PREPARING),
      summary("past-1", OrderStatus.COMPLETED),
      summary("past-2", OrderStatus.CANCELLED),
    )
    val vm = OrdersViewModel(FakeOrderRepository(orders))
    vm.loadOrders()
    render(vm)
    waitUntilMainLooper { vm.uiState.value.orders.size == 4 }

    composeTestRule.onNodeWithTag("orders_filter_active").assertIsDisplayed().assertTextContains("Active (2)")
    composeTestRule.onNodeWithTag("orders_filter_past").assertIsDisplayed().assertTextContains("Past (2)")
    composeTestRule.onNodeWithTag("orders_filter_all").assertIsDisplayed().assertTextContains("All (4)")
    composeTestRule.onNodeWithTag("order_summary_card_active-1").assertIsDisplayed()
    assertTrue(composeTestRule.onAllNodesWithTag("order_summary_card_past-1").fetchSemanticsNodes().isEmpty())

    composeTestRule.onNodeWithTag("orders_filter_past").performClick()
    waitUntilMainLooper { vm.uiState.value.orderListFilter == OrderListFilter.PAST }
    composeTestRule.onNodeWithTag("order_summary_card_past-1").assertIsDisplayed()
    assertTrue(composeTestRule.onAllNodesWithTag("order_summary_card_active-1").fetchSemanticsNodes().isEmpty())

    // A refresh that changes the list must not reset the chosen segment.
    vm.loadOrders()
    waitUntilMainLooper { !vm.uiState.value.isLoadingList }
    assertEquals(OrderListFilter.PAST, vm.uiState.value.orderListFilter)
    composeTestRule.onNodeWithTag("order_summary_card_past-2").assertIsDisplayed()

    composeTestRule.onNodeWithTag("orders_filter_all").performClick()
    waitUntilMainLooper { vm.uiState.value.orderListFilter == OrderListFilter.ALL }
    composeTestRule.onNodeWithTag("order_summary_card_active-2").assertExists()
    composeTestRule.onNodeWithTag("order_summary_card_past-2").assertExists()
  }

  @Test
  fun `filter-specific empty state stays reachable and summary card is human readable`() {
    val active = summary(
      id = "active-1",
      status = OrderStatus.PLACED,
      paymentStatus = PaymentStatus.COD_PENDING,
      fulfillmentType = FulfillmentType.PICKUP,
    )
    val vm = OrdersViewModel(FakeOrderRepository(listOf(active)))
    vm.setOrderListFilter(OrderListFilter.PAST)
    vm.loadOrders()
    render(vm)
    waitUntilMainLooper { vm.uiState.value.orders.size == 1 }

    composeTestRule.onNodeWithTag("orders_empty_state").assertIsDisplayed()
      .assert(hasAnyDescendant(hasText("No past orders")))
    composeTestRule.onNodeWithTag("orders_browse_menu_button").assertIsDisplayed()

    vm.setOrderListFilter(OrderListFilter.ACTIVE)
    waitUntilMainLooper { vm.uiState.value.orderListFilter == OrderListFilter.ACTIVE }
    val card = composeTestRule.onNodeWithTag("order_summary_card_active-1")
    card.assertIsDisplayed()
    card.assertTextContains("19 Nov, 7:42 PM", substring = true)
    assertTrue(
      composeTestRule.onAllNodesWithText("Order Placed", substring = true).fetchSemanticsNodes().isNotEmpty(),
    )
    card.assertTextContains("2 items", substring = true)
    card.assertTextContains("Pickup · Cash on Delivery (Pay at Pickup)", substring = true)
    card.assertTextContains("₹563.95", substring = true)

    vm.setOrderListFilter(OrderListFilter.ALL)
    composeTestRule.onNodeWithTag("orders_filter_all").assertIsDisplayed().assertTextContains("All (1)")
  }

  private fun waitUntilMainLooper(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.waitForIdle()
      if (condition()) return
      if (System.currentTimeMillis() > deadline) {
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for Phase 6 history state")
      }
      Thread.sleep(20)
    }
  }
}

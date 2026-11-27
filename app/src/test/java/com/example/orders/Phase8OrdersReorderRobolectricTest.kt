package com.example.orders

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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.core.network.PizzoraApiException
import com.example.cart.CartSyncResult
import com.example.cart.ReorderCartSink
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.DeliveryAddressSnapshotDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderItemDto
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderStatusHistoryDto
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PaymentSummaryDto
import com.pizzora.contracts.ReorderItemDto
import com.pizzora.contracts.ReorderResponseDto
import com.pizzora.contracts.ReorderUnavailableDto
import com.pizzora.contracts.ReorderUnavailableReason
import com.pizzora.contracts.ScheduleType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase8OrdersReorderRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private fun summary(
    id: String = "order-1",
    status: OrderStatus = OrderStatus.COMPLETED,
    preview: String? = null,
  ) = OrderSummaryDto(
    id = id,
    orderNumber = "PZ-$id",
    fulfillmentType = FulfillmentType.PICKUP,
    scheduleType = ScheduleType.ASAP,
    status = status,
    version = 1L,
    totalPaise = 56_395L,
    itemCount = 2,
    paymentMethod = PaymentMethod.COD,
    paymentStatus = PaymentStatus.COD_PENDING,
    isPriority = false,
    createdAt = "2026-11-19T14:12:00Z",
    itemPreviewSummary = preview,
  )

  private fun detail(
    items: List<OrderItemDto>,
    status: OrderStatus = OrderStatus.COMPLETED,
    id: String = "order-1",
  ) = OrderDetailDto(
    id = id,
    orderNumber = "PZ-$id",
    customerId = "customer-1",
    fulfillmentType = FulfillmentType.PICKUP,
    scheduleType = ScheduleType.ASAP,
    deliveryAddress = DeliveryAddressSnapshotDto(
      addressId = "addr-1",
      label = "Home",
      line1 = "1 MG Road",
      pincode = "560001",
      city = "Bengaluru",
    ),
    status = status,
    version = 1L,
    subtotalPaise = 56_395L,
    discountPaise = 0L,
    deliveryFeePaise = 0L,
    taxPaise = 2_805L,
    cgstPaise = 1_402L,
    sgstPaise = 1_403L,
    totalPaise = 56_395L,
    gstBpsSnapshot = 500,
    isPriority = false,
    createdAt = "2026-11-19T14:12:00Z",
    items = items,
    statusHistory = emptyList(),
    payment = PaymentSummaryDto(
      id = "payment-1",
      method = PaymentMethod.COD,
      status = PaymentStatus.COD_PENDING,
      amountPaise = 56_395L,
      capturedPaise = 0L,
      refundedPaise = 0L,
    ),
  )

  private fun orderItem(
    id: String,
    name: String,
    snapshot: Map<String, String> = emptyMap(),
    toppings: List<String> = emptyList(),
    cheeseId: String? = null,
    sauceId: String? = null,
    toppingIds: List<String> = emptyList(),
    standardToppingIds: List<String> = emptyList(),
    standardToppingRemovedIds: List<String> = emptyList(),
    halfAndHalfConfigId: String? = null,
    halfAndHalfLeftToppingIds: List<String> = emptyList(),
    halfAndHalfRightToppingIds: List<String> = emptyList(),
  ) = OrderItemDto(
    id = id,
    productId = "product-$id",
    productName = name,
    variantSize = "MEDIUM",
    crustName = "Hand Tossed",
    toppings = toppings,
    unitPricePaise = 50_000L,
    quantity = 1,
    lineTotalPaise = 50_000L,
    cheeseId = cheeseId,
    sauceId = sauceId,
    toppingIds = toppingIds,
    standardToppingIds = standardToppingIds,
    standardToppingRemovedIds = standardToppingRemovedIds,
    halfAndHalfConfigId = halfAndHalfConfigId,
    halfAndHalfLeftToppingIds = halfAndHalfLeftToppingIds,
    halfAndHalfRightToppingIds = halfAndHalfRightToppingIds,
    modifiersSnapshot = snapshot,
  )

  private fun render(
    state: OrdersUiState,
    onReorder: (String) -> Unit = {},
    onDismiss: () -> Unit = {},
    onConfirm: () -> Unit = {},
  ) {
    composeTestRule.setContent {
      MaterialTheme {
        OrdersScreen(
          uiState = state,
          onRefreshOrders = {},
          onOpenOrder = {},
          onBackToList = {},
          onCancelOrder = {},
          onBrowseMenu = {},
          onReorderOrder = onReorder,
          onDismissReorderDialog = onDismiss,
          onConfirmReorder = onConfirm,
        )
      }
    }
  }

  private fun renderStates(
    initialState: OrdersUiState,
    onReorder: (String) -> Unit = {},
  ): MutableStateFlow<OrdersUiState> {
    val stateFlow = MutableStateFlow(initialState)
    composeTestRule.setContent {
      MaterialTheme {
        val state by stateFlow.collectAsState()
        OrdersScreen(
          uiState = state,
          onRefreshOrders = {},
          onOpenOrder = {},
          onBackToList = {},
          onCancelOrder = {},
          onBrowseMenu = {},
          onReorderOrder = onReorder,
        )
      }
    }
    return stateFlow
  }

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
          onReorderOrder = { vm.requestReorder(it) },
          onDismissReorderDialog = { vm.dismissReorderDialog() },
          onConfirmReorder = { vm.confirmReorder() },
        )
      }
    }
  }

  @Test
  fun `order list cards show compact item preview summary`() {
    render(
      OrdersUiState(
        orders = listOf(summary(preview = "2x Margherita • 1x Garlic Bread")),
        orderListFilter = OrderListFilter.ALL,
      ),
    )

    composeTestRule.onNodeWithTag("order_summary_card_order-1")
      .assertTextContains("2x Margherita • 1x Garlic Bread", substring = true)
  }

  @Test
  fun `detail renders snapshot names for new orders and ids for historical orders`() {
    val modern = orderItem(
      id = "item-new",
      name = "Modern Margherita",
      snapshot = mapOf(
        "cheese" to "Fior di Latte",
        "sauce" to "San Marzano",
        "standard_toppings" to "[\"Basil\",\"Tomato\"]",
        "standard_toppings_removed" to "{\"onion\":\"Red Onion\"}",
        "half_and_half_left" to "[\"Basil\"]",
        "half_and_half_right" to "[\"Tomato\"]",
      ),
      toppings = listOf("Extra Chilli"),
    )
    val historical = orderItem(
      id = "item-old",
      name = "Historic Margherita",
      toppings = listOf("Old Mushroom"),
      cheeseId = "cheese-old",
      sauceId = "sauce-old",
      standardToppingIds = listOf("std-old"),
      halfAndHalfConfigId = "hah-old",
      halfAndHalfLeftToppingIds = listOf("left-old"),
      halfAndHalfRightToppingIds = listOf("right-old"),
    )

    val modernSummary = formatExtendedModifierSummary(modern)!!
    assertTrue(modernSummary.contains("Cheese: Fior di Latte"))
    assertTrue(modernSummary.contains("Sauce: San Marzano"))
    assertTrue(modernSummary.contains("Standard toppings: Basil, Tomato"))
    assertTrue(modernSummary.contains("Removed defaults: Red Onion"))
    assertTrue(modernSummary.contains("Half & half: left Basil / right Tomato"))
    assertTrue(modernSummary.contains("Extra toppings: Extra Chilli"))

    val historicalSummary = formatExtendedModifierSummary(historical)!!
    assertTrue(historicalSummary.contains("Cheese: name not recorded (cheese-old)"))
    assertTrue(historicalSummary.contains("Standard toppings: name not recorded (std-old)"))
    assertTrue(historicalSummary.contains("Half & half: left name not recorded (left-old)"))
    assertTrue(historicalSummary.contains("Extra toppings: Old Mushroom"))

    render(OrdersUiState(selectedOrder = detail(listOf(modern, historical))))
    composeTestRule.onNodeWithTag("order_detail_lazy_column")
      .performScrollToNode(hasTestTag("order_detail_item_modifiers_item-new"))
    composeTestRule.onNodeWithTag("order_detail_item_modifiers_item-new")
      .assertTextContains("Fior di Latte", substring = true)
    composeTestRule.onNodeWithTag("order_detail_lazy_column")
      .performScrollToNode(hasTestTag("order_detail_item_modifiers_item-old"))
    composeTestRule.onNodeWithTag("order_detail_item_modifiers_item-old")
      .assertTextContains("name not recorded (cheese-old)", substring = true)
  }

  @Test
  fun `reorder action is only offered for completed orders`() {
    var requestedId: String? = null
    val states = renderStates(
      initialState = OrdersUiState(selectedOrder = detail(listOf(orderItem("item-1", "Margherita")))),
      onReorder = { requestedId = it },
    )
    composeTestRule.onNodeWithTag("order_detail_lazy_column")
      .performScrollToNode(hasTestTag("reorder_order_button"))
    composeTestRule.onNodeWithTag("reorder_order_button").assertIsDisplayed().performClick()
    assertEquals("order-1", requestedId)

    states.value = OrdersUiState(
      selectedOrder = detail(
        items = listOf(orderItem("item-1", "Margherita")),
        status = OrderStatus.PLACED,
      ),
    )
    composeTestRule.waitForIdle()
    assertTrue(
      composeTestRule.onAllNodesWithTag("reorder_order_button").fetchSemanticsNodes().isEmpty(),
    )
  }

  @Test
  fun `reconciliation dialog shows available lines caveat unavailable modifiers and verbatim warnings`() {
    val response = ReorderResponseDto(
      orderId = "order-1",
      canReorder = true,
      items = listOf(
        ReorderItemDto(
          orderItemId = "item-1",
          productId = "p-1",
          productName = "Margherita",
          quantity = 2,
          isAvailable = true,
          issues = listOf("Cheese substituted is not applied automatically"),
        ),
        ReorderItemDto(
          orderItemId = "item-2",
          productId = "p-2",
          productName = "Truffle Pizza",
          quantity = 1,
          isAvailable = false,
        ),
      ),
      unavailable = listOf(
        ReorderUnavailableDto(
          orderItemId = "item-1",
          productName = "Margherita",
          modifierType = "cheese",
          modifierId = "cheese-old",
          modifierName = "Smoked Scamorza",
          reason = ReorderUnavailableReason.CHEESE_UNAVAILABLE,
        ),
      ),
      warnings = listOf("Seasonal toppings may differ from the original order."),
    )
    render(OrdersUiState(selectedOrder = detail(listOf(orderItem("item-1", "Margherita"))), reorderResponse = response))

    composeTestRule.onNodeWithTag("reorder_dialog_title").assertIsDisplayed()
    composeTestRule.onNodeWithTag("reorder_available_item_item-1")
      .assertTextContains("2x Margherita", substring = true)
    composeTestRule.onNodeWithTag("reorder_price_caveat")
      .assertTextContains("current prices", substring = true)
    composeTestRule.onNodeWithTag("reorder_unavailable_item-1_cheese-old")
      .assertTextContains("Smoked Scamorza", substring = true)
      .assertTextContains("cheese unavailable", substring = true)
    composeTestRule.onNodeWithTag("reorder_unavailable_item_item-2").assertTextContains("not available", substring = true)
    composeTestRule.onNodeWithTag("reorder_warning_0")
      .assertTextContains("Seasonal toppings may differ from the original order.")
    composeTestRule.onNodeWithTag("reorder_confirm_button")
      .assertTextContains("Add available items only")
    composeTestRule.onNodeWithTag("reorder_cancel_button").assertTextContains("Cancel")
  }

  @Test
  fun `403 and 404 reorder failures show friendly messages`() {
    val source = MutableReorderSource(
      Result.failure(PizzoraApiException(403, "FORBIDDEN", "raw forbidden")),
    )
    val vm = OrdersViewModel(
      orderRepository = FakeOrderRepository(detail(listOf(orderItem("item-1", "Margherita")))),
      reorderSource = source,
    )
    vm.openOrderDetail("order-1")
    waitUntilMainLooper { vm.uiState.value.selectedOrder != null }
    render(vm)

    vm.requestReorder("order-1")
    waitUntilMainLooper { vm.uiState.value.reorderError != null }
    assertEquals("You don't have permission to reorder this order.", vm.uiState.value.reorderError)
    composeTestRule.onNodeWithTag("reorder_error_text")
      .assertTextContains("don't have permission", substring = true)

    source.result = Result.failure(PizzoraApiException(404, "NOT_FOUND", "raw missing"))
    vm.dismissReorderDialog()
    vm.requestReorder("order-1")
    waitUntilMainLooper { vm.uiState.value.reorderError?.contains("no longer available") == true }
    assertEquals("This order is no longer available for reordering.", vm.uiState.value.reorderError)
    composeTestRule.onNodeWithTag("reorder_error_text")
      .assertTextContains("no longer available", substring = true)
  }

  @Test
  fun `confirmation adds only available items and keeps all phase 8 ids`() {
    val sink = CapturingReorderSink()
    val response = ReorderResponseDto(
      orderId = "order-1",
      canReorder = true,
      items = listOf(
        ReorderItemDto(
          orderItemId = "item-1",
          productId = "p-1",
          productName = "Margherita",
          variantId = "variant-1",
          crustId = "crust-1",
          cheeseId = "cheese-1",
          sauceId = "sauce-1",
          toppingIds = listOf("extra-1", "extra-1"),
          standardToppingIds = listOf("std-keep-1", "std-removed-1"),
          standardToppingRemovedIds = listOf("std-removed-1"),
          halfAndHalfConfigId = "hah-1",
          halfAndHalfLeftToppingIds = listOf("hah-left-1"),
          halfAndHalfRightToppingIds = listOf("hah-right-1"),
          quantity = 2,
          isAvailable = true,
        ),
        ReorderItemDto(
          orderItemId = "item-2",
          productId = "p-2",
          productName = "Truffle Pizza",
          quantity = 1,
          isAvailable = false,
        ),
      ),
      unavailable = listOf(
        ReorderUnavailableDto(
          orderItemId = "item-1",
          productName = "Margherita",
          modifierType = "cheese",
          modifierId = "cheese-old",
          modifierName = "Smoked Scamorza",
          reason = ReorderUnavailableReason.CHEESE_UNAVAILABLE,
        ),
      ),
      warnings = listOf("Prices reflect today's menu."),
    )
    val vm = OrdersViewModel(
      orderRepository = FakeOrderRepository(detail(listOf(orderItem("item-1", "Margherita")))),
      reorderSource = FakeReorderSource(Result.success(response)),
      reorderCartSink = sink,
    )
    vm.openOrderDetail("order-1")
    waitUntilMainLooper { vm.uiState.value.selectedOrder != null }
    vm.requestReorder("order-1")
    waitUntilMainLooper { vm.uiState.value.reorderResponse != null }
    assertFalse(vm.uiState.value.reorderResponse!!.items[1].isAvailable)

    vm.confirmReorder()
    waitUntilMainLooper { sink.items.size == 1 }

    val input: CartItemInputDto = sink.items.single()
    assertEquals("p-1", input.productId)
    assertEquals("variant-1", input.variantId)
    assertEquals("crust-1", input.crustId)
    assertEquals("cheese-1", input.cheeseId)
    assertEquals("sauce-1", input.sauceId)
    assertEquals(listOf("extra-1"), input.toppingIds)
    assertEquals(listOf("std-keep-1"), input.standardToppingIds)
    assertEquals("hah-1", input.halfAndHalfConfigId)
    assertEquals(listOf("hah-left-1"), input.halfAndHalfLeftToppingIds)
    assertEquals(listOf("hah-right-1"), input.halfAndHalfRightToppingIds)
    assertEquals(2, input.quantity)

    val message = vm.uiState.value.actionMessage.orEmpty()
    assertTrue(message.contains("Added 1 available item(s)"))
    assertTrue(message.contains("Skipped (not available): Truffle Pizza"))
    assertEquals(null, vm.uiState.value.reorderResponse)
  }

  private class FakeReorderSource(
    private val result: Result<ReorderResponseDto>,
  ) : ReorderSource {
    override suspend fun reorderOrder(orderId: String): Result<ReorderResponseDto> = result
  }

  private class MutableReorderSource(
    @Volatile var result: Result<ReorderResponseDto>,
  ) : ReorderSource {
    override suspend fun reorderOrder(orderId: String): Result<ReorderResponseDto> = result
  }

  private class CapturingReorderSink(
    val items: MutableList<CartItemInputDto> = mutableListOf(),
  ) : ReorderCartSink {
    override suspend fun addAvailableReorderItems(items: List<CartItemInputDto>): CartSyncResult {
      this.items.addAll(items)
      return CartSyncResult(isOffline = false, unavailableCount = 0)
    }
  }

  private class FakeOrderRepository(
    private val orderDetail: OrderDetailDto,
  ) : OrderRepository {
    override suspend fun fetchOrders() = Result.success(com.pizzora.contracts.OrderListResponse(emptyList()))
    override suspend fun fetchOrderDetail(orderId: String) = Result.success(orderDetail)

    override suspend fun getAddresses(): Result<List<com.pizzora.contracts.AddressDto>> = unused()
    override suspend fun createAddress(request: com.pizzora.contracts.UpsertAddressRequest) = unused<com.pizzora.contracts.AddressDto>()
    override suspend fun updateAddress(addressId: String, request: com.pizzora.contracts.UpsertAddressRequest) = unused<com.pizzora.contracts.AddressDto>()
    override suspend fun deleteAddress(addressId: String) = unused<Unit>()
    override suspend fun checkDeliveryZone(pincode: String) = unused<com.pizzora.contracts.DeliveryZoneCheckResponse>()
    override suspend fun getAvailableSlots(type: FulfillmentType, date: String?) = unused<com.pizzora.contracts.AvailableSlotsResponse>()
    override suspend fun placeOrder(idempotencyKey: String, request: com.pizzora.contracts.CheckoutRequest) = unused<com.pizzora.contracts.CheckoutResponse>()
    override suspend fun cancelOrder(orderId: String, reason: String?) = unused<OrderDetailDto>()
    override suspend fun fetchOrderPaymentStatus(orderId: String) = unused<com.pizzora.contracts.OrderPaymentStatusResponse>()
    override suspend fun retryOrderPayment(
      orderId: String,
      idempotencyKey: String,
      reason: String?,
    ) = unused<com.pizzora.contracts.OrderPaymentStatusResponse>()
    override suspend fun reportPaymentFailure(
      orderId: String,
      request: com.pizzora.contracts.FailPaymentAttemptRequest,
    ) = unused<com.pizzora.contracts.OrderPaymentStatusResponse>()
    override suspend fun verifyRazorpayPayment(
      request: com.pizzora.contracts.VerifyRazorpayPaymentRequest,
    ) = unused<com.pizzora.contracts.VerifyRazorpayPaymentResponse>()

    private fun <T> unused(): Result<T> = Result.failure(IllegalStateException("unused in Phase 8 order tests"))
  }

  private fun waitUntilMainLooper(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.waitForIdle()
      if (condition()) return
      if (System.currentTimeMillis() > deadline) {
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for Phase 8 reorder state")
      }
      Thread.sleep(20)
    }
  }
}

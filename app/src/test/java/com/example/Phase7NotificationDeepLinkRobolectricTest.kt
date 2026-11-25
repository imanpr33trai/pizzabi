package com.example

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.core.database.NotificationInboxEntity
import com.example.core.security.AuthSession
import com.example.notifications.NotificationRepository
import com.example.notifications.NotificationSyncOutcome
import com.example.notifications.NotificationsViewModel
import com.example.navigation.CustomerRoleShell
import com.example.orders.OrderRepository
import com.example.orders.OrdersViewModel
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.AvailableSlotsResponse
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.FailPaymentAttemptRequest
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.MarkAllNotificationsReadResponse
import com.pizzora.contracts.MarkNotificationReadResponse
import com.pizzora.contracts.NotificationCategory
import com.pizzora.contracts.NotificationDeliveryStatus
import com.pizzora.contracts.NotificationInboxItemDto
import com.pizzora.contracts.NotificationPreferencesDto
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderItemDto
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderPaymentStatusResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderStatusHistoryDto
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PaymentSummaryDto
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.UpdateNotificationPreferencesRequest
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
import com.pizzora.contracts.VerifyRazorpayPaymentResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class Phase7NotificationDeepLinkRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val session = AuthSession(
    accessToken = "test-jwt",
    refreshToken = "test-refresh",
    user = UserProfileDto(
      id = "cust-7",
      email = "priya@pizzora.test",
      emailVerified = true,
      fullName = "Priya Customer",
      phone = "+919876543210",
      role = UserRole.CUSTOMER,
      isActive = true,
    ),
  )

  private fun notificationsVm(repository: FakeNotificationRepository): NotificationsViewModel {
    val store = ViewModelStore()
    val factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T =
        NotificationsViewModel(repository) as T
    }
    return ViewModelProvider(store, factory)["notifications", NotificationsViewModel::class.java]
  }

  private fun ordersVm(repository: FakeOrderRepository): OrdersViewModel {
    val store = ViewModelStore()
    val factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T =
        OrdersViewModel(repository) as T
    }
    return ViewModelProvider(store, factory)["orders", OrdersViewModel::class.java]
  }

  @Test
  fun orderNotificationMarksReadClosesInboxSelectsOrdersAndOpensExactDetail() {
    val notificationRepository = FakeNotificationRepository(notification("order-77"))
    val orderRepository = FakeOrderRepository(mapOf("order-77" to sampleOrder("order-77")))
    val notifications = notificationsVm(notificationRepository)
    val orders = ordersVm(orderRepository)

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerRoleShell(
          session = session,
          ordersViewModel = orders,
          notificationsViewModel = notifications,
          onSignOut = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("customer_notifications_button").performClick()
    composeTestRule.onNodeWithTag("notifications_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("notification_card_n-order").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("order_detail_screen").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("notifications_screen").assertDoesNotExist()
    composeTestRule.onNodeWithTag("customer_tab_orders").assertIsSelected()
    composeTestRule.onNodeWithTag("order_detail_number_text").assertIsDisplayed()
    composeTestRule.onNodeWithText("PZ-2026-order-77").assertIsDisplayed()
    assertEquals("n-order", notificationRepository.lastMarkedReadId)
  }

  @Test
  fun unavailableNotificationOrderShowsFallbackAndNoStaleDetail() {
    val notificationRepository = FakeNotificationRepository(notification("missing-order"))
    val orderRepository = FakeOrderRepository(emptyMap())
    val notifications = notificationsVm(notificationRepository)
    val orders = ordersVm(orderRepository)

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerRoleShell(
          session = session,
          ordersViewModel = orders,
          notificationsViewModel = notifications,
          onSignOut = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("customer_notifications_button").performClick()
    composeTestRule.onNodeWithTag("notification_card_n-order").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("orders_history_screen").fetchSemanticsNodes().isNotEmpty() &&
        composeTestRule.onAllNodesWithTag("notifications_screen").fetchSemanticsNodes().isEmpty()
    }
    composeTestRule.onNodeWithTag("customer_tab_orders").assertIsSelected()
    composeTestRule.onNodeWithTag("order_detail_screen").assertDoesNotExist()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithText(
        "That order is no longer available. Showing your orders.",
      ).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText(
      "That order is no longer available. Showing your orders.",
    ).assertIsDisplayed()
  }

  private fun notification(orderId: String): NotificationInboxItemDto = NotificationInboxItemDto(
    id = "n-order",
    eventId = "evt-order",
    title = "Order Baking",
    body = "Your pizza is in the oven.",
    category = NotificationCategory.ORDER_UPDATE,
    referenceId = orderId,
    isRead = false,
    deliveryStatus = NotificationDeliveryStatus.SYNCED,
    createdAt = "2026-11-24T13:55:00Z",
  )

  private fun sampleOrder(id: String): OrderDetailDto = OrderDetailDto(
    id = id,
    orderNumber = "PZ-2026-$id",
    customerId = session.user.id,
    fulfillmentType = FulfillmentType.PICKUP,
    scheduleType = ScheduleType.ASAP,
    deliveryAddress = null,
    status = OrderStatus.PLACED,
    version = 1L,
    subtotalPaise = 49_900L,
    discountPaise = 0L,
    deliveryFeePaise = 0L,
    taxPaise = 2_495L,
    cgstPaise = 1_248L,
    sgstPaise = 1_247L,
    totalPaise = 52_395L,
    gstBpsSnapshot = 500,
    couponCodeSnapshot = null,
    isPriority = false,
    acceptedAt = null,
    prepStartedAt = null,
    readyAt = null,
    completedAt = null,
    expiresAt = null,
    createdAt = "2026-11-24T13:50:00Z",
    items = listOf(
      OrderItemDto(
        id = "item-$id",
        productId = "prod-1",
        productName = "Margherita Classico",
        variantSize = SizeCode.MEDIUM.name,
        crustName = "Neapolitan Sourdough",
        toppings = emptyList(),
        unitPricePaise = 49_900L,
        quantity = 1,
        lineTotalPaise = 49_900L,
      ),
    ),
    statusHistory = listOf(
      OrderStatusHistoryDto(
        id = "history-$id",
        fromStatus = null,
        toStatus = OrderStatus.PLACED,
        changedBy = session.user.id,
        reason = "Placed",
        createdAt = "2026-11-24T13:50:00Z",
      ),
    ),
    payment = PaymentSummaryDto(
      id = "pay-$id",
      method = PaymentMethod.COD,
      status = PaymentStatus.COD_PENDING,
      amountPaise = 52_395L,
      capturedPaise = 0L,
      refundedPaise = 0L,
      currency = "INR",
      latestAttempt = null,
    ),
  )

  private class FakeNotificationRepository(
    initial: NotificationInboxItemDto,
  ) : NotificationRepository {
    private val items = MutableStateFlow(listOf(initial))
    var lastMarkedReadId: String? = null
      private set
    private var preferences = NotificationPreferencesDto(
      userId = "cust-7",
      orderUpdates = true,
      promoAlerts = true,
      kdsSoundEnabled = true,
      updatedAt = "2026-11-24T13:00:00Z",
    )

    override fun observeInbox(): Flow<List<NotificationInboxEntity>> = items.mapEntities()

    override fun observeUnreadCount(): Flow<Int> = items.mapUnread()

    override suspend fun syncInbox(unreadOnly: Boolean, limit: Int): Result<NotificationSyncOutcome> =
      Result.success(
        NotificationSyncOutcome(
          fetched = emptyList(),
          newlyInserted = emptyList(),
          unreadCount = items.value.count { !it.isRead },
        ),
      )

    override suspend fun markAsRead(notificationId: String): Result<MarkNotificationReadResponse> {
      lastMarkedReadId = notificationId
      val updated = items.value.map { if (it.id == notificationId) it.copy(isRead = true) else it }
      items.value = updated
      val notification = updated.first { it.id == notificationId }
      return Result.success(
        MarkNotificationReadResponse(
          notification = notification,
          unreadCount = updated.count { !it.isRead },
        ),
      )
    }

    override suspend fun markAllAsRead(): Result<MarkAllNotificationsReadResponse> {
      items.value = items.value.map { it.copy(isRead = true) }
      return Result.success(MarkAllNotificationsReadResponse(markedCount = 1, unreadCount = 0))
    }

    override suspend fun getPreferences(): Result<NotificationPreferencesDto> = Result.success(preferences)

    override suspend fun updatePreferences(
      request: UpdateNotificationPreferencesRequest,
    ): Result<NotificationPreferencesDto> {
      preferences = preferences.copy(
        orderUpdates = request.orderUpdates ?: preferences.orderUpdates,
        promoAlerts = request.promoAlerts ?: preferences.promoAlerts,
        kdsSoundEnabled = request.kdsSoundEnabled ?: preferences.kdsSoundEnabled,
      )
      return Result.success(preferences)
    }

    override suspend fun clearLocalInbox() {
      items.value = emptyList()
    }

    private fun MutableStateFlow<List<NotificationInboxItemDto>>.mapEntities(): Flow<List<NotificationInboxEntity>> =
      map { list ->
        list.map { item ->
          NotificationInboxEntity(
            id = item.id,
            userId = "cust-7",
            eventId = item.eventId,
            title = item.title,
            body = item.body,
            category = item.category,
            referenceId = item.referenceId,
            isRead = item.isRead,
            createdAtIso = item.createdAt,
          )
        }
      }

    private fun MutableStateFlow<List<NotificationInboxItemDto>>.mapUnread(): Flow<Int> =
      map { list -> list.count { !it.isRead } }
  }

  private class FakeOrderRepository(
    private val details: Map<String, OrderDetailDto>,
  ) : OrderRepository {
    override suspend fun fetchOrders(): Result<OrderListResponse> = Result.success(OrderListResponse(emptyList()))

    override suspend fun fetchOrderDetail(orderId: String): Result<OrderDetailDto> =
      details[orderId]?.let { Result.success(it) }
        ?: Result.failure(IllegalArgumentException("Unknown order $orderId"))

    override suspend fun getAddresses(): Result<List<AddressDto>> = unused()
    override suspend fun createAddress(request: UpsertAddressRequest): Result<AddressDto> = unused()
    override suspend fun updateAddress(addressId: String, request: UpsertAddressRequest): Result<AddressDto> = unused()
    override suspend fun deleteAddress(addressId: String): Result<Unit> = unused()
    override suspend fun checkDeliveryZone(pincode: String): Result<DeliveryZoneCheckResponse> = unused()
    override suspend fun getAvailableSlots(type: FulfillmentType, date: String?): Result<AvailableSlotsResponse> = unused()
    override suspend fun placeOrder(idempotencyKey: String, request: CheckoutRequest): Result<CheckoutResponse> = unused()
    override suspend fun cancelOrder(orderId: String, reason: String?): Result<OrderDetailDto> = unused()
    override suspend fun fetchOrderPaymentStatus(orderId: String): Result<OrderPaymentStatusResponse> = unused()
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

    private fun <T> unused(): Result<T> = Result.failure(IllegalStateException("unused in deep-link tests"))
  }
}

package com.example

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.core.di.AppContainer
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.orders.NetworkOrderRepository
import com.example.orders.OrdersScreen
import com.example.orders.OrdersViewModel
import com.example.orders.PendingRazorpayLaunch
import com.example.orders.RazorpayCheckoutLauncher
import com.example.orders.RazorpayCheckoutPayloadBuilder
import com.example.orders.RazorpayCheckoutRequest
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.FailPaymentAttemptRequest
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
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
import com.pizzora.contracts.VerifyRazorpayPaymentResponse
import com.razorpay.Checkout
import com.razorpay.TestPaymentDataFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RazorpayCheckoutIntegrationTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private val customerSession = AuthSession(
    accessToken = "cust-jwt-rzp-int",
    refreshToken = "cust-ref-rzp-int",
    user = UserProfileDto(
      id = "user-rzp-int",
      email = "customer@pizzora.test",
      emailVerified = true,
      fullName = "Giulia Bianchi",
      phone = "9876543210",
      role = UserRole.CUSTOMER,
      isActive = true,
    ),
  )

  private class InMemoryTokenStorage(initial: AuthSession?) : TokenStorage {
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

  private class RecordingCheckoutLauncher : RazorpayCheckoutLauncher {
    val requests = mutableListOf<RazorpayCheckoutRequest>()
    override fun launch(activity: Activity, request: RazorpayCheckoutRequest) {
      requests += request
    }
  }

  @After
  fun tearDown() {
    val app = ApplicationProvider.getApplicationContext<PizzoraApplication>()
    app.appContainerOverride = null
  }

  @Test
  fun `payload builder produces official Razorpay Checkout options JSON`() {
    val request = RazorpayCheckoutRequest(
      orderId = "ord-payload-1",
      orderNumber = "PZ-20261116-0001",
      gatewayOrderId = "order_RzpPayload123",
      razorpayKeyId = "rzp_test_payload",
      amountPaise = 52_500L,
      customerName = "Giulia Bianchi",
      customerEmail = "customer@pizzora.test",
      customerPhone = "9876543210",
    )

    val options = RazorpayCheckoutPayloadBuilder.buildOptionsJson(request)

    assertEquals("PIZZORA", options.getString("name"))
    assertEquals("Order PZ-20261116-0001", options.getString("description"))
    assertEquals("order_RzpPayload123", options.getString("order_id"))
    assertEquals("INR", options.getString("currency"))
    assertEquals(52_500L, options.getLong("amount"))
    assertFalse(options.getJSONObject("retry").getBoolean("enabled"))
    assertEquals("Giulia Bianchi", options.getJSONObject("prefill").getString("name"))
    assertEquals("customer@pizzora.test", options.getJSONObject("prefill").getString("email"))
    assertEquals("9876543210", options.getJSONObject("prefill").getString("contact"))
    assertEquals("ord-payload-1", options.getJSONObject("notes").getString("order_id"))
    assertEquals("PZ-20261116-0001", options.getJSONObject("notes").getString("order_number"))
  }

  @Test
  fun `official SDK launch cancellation preserves attempt without fail call and allows immediate retry then verify`() {
    var orderState = pendingOnlineOrder(orderId = "ord-cancel-1", gatewayOrderId = "order_gw_cancel_1")
    val failCallCount = AtomicInteger(0)
    var verifyRequest: VerifyRazorpayPaymentRequest? = null

    val engine = MockEngine { request ->
      when {
        request.url.encodedPath == "/api/v1/orders/ord-cancel-1/payment" && request.method == HttpMethod.Get -> {
          respond(
            content = json.encodeToString(paymentStatusResponse(orderState)),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        request.url.encodedPath == "/api/v1/orders/ord-cancel-1/payments/fail" && request.method == HttpMethod.Post -> {
          failCallCount.incrementAndGet()
          respond(
            content = json.encodeToString(paymentStatusResponse(orderState)),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        request.url.encodedPath == "/api/v1/payments/razorpay/verify" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<VerifyRazorpayPaymentRequest>(bodyAsString(request.body))
          verifyRequest = req
          orderState = orderState.copy(
            status = OrderStatus.PLACED,
            version = 2L,
            expiresAt = null,
            payment = orderState.payment.copy(
              status = PaymentStatus.CAPTURED,
              capturedPaise = orderState.totalPaise,
              latestAttempt = orderState.payment.latestAttempt!!.copy(
                status = PaymentAttemptStatus.CAPTURED,
                gatewayOrderId = req.razorpayOrderId,
                gatewayPaymentId = req.razorpayPaymentId,
              ),
            ),
          )
          respond(
            content = json.encodeToString(VerifyRazorpayPaymentResponse(orderState, alreadyCaptured = false, lateCaptureRefundTriggered = false)),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected request ${request.method.value} ${request.url.encodedPath}")
      }
    }

    val apiClient = PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(customerSession), engine)
    val ordersVm = OrdersViewModel(NetworkOrderRepository(apiClient), initialPollDelayMs = 20L)
    val launcher = RecordingCheckoutLauncher()

    composeTestRule.setContent {
      PizzoraTheme {
        val state = ordersVm.uiState.collectAsStateWithLifecycle().value
        OrdersScreen(
          uiState = state,
          onRefreshOrders = ordersVm::loadOrders,
          onOpenOrder = ordersVm::openOrderDetail,
          onBackToList = ordersVm::clearSelectedOrder,
          onCancelOrder = ordersVm::cancelSelectedOrder,
          onBrowseMenu = {},
          onOpenPaymentSheet = ordersVm::openPaymentSheet,
          onDismissPaymentSheet = ordersVm::dismissPaymentSheet,
          onRetryPayment = { ordersVm.retryPayment() },
          customerName = customerSession.user.fullName,
          customerEmail = customerSession.user.email,
          customerPhone = customerSession.user.phone,
          razorpayCheckoutLauncher = launcher,
          onRazorpayLaunchStarted = ordersVm::markRazorpayLaunchStarted,
          onRazorpaySdkError = { code, desc -> ordersVm.onRazorpaySdkError(code, desc, null) },
        )
      }
    }

    ordersVm.showCreatedOrder(orderState, openPaymentFlowIfPending = true)
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("launch_razorpay_checkout_button").fetchSemanticsNodes().isNotEmpty()
    }

    // Customer launches Razorpay SDK
    composeTestRule.onNodeWithTag("launch_razorpay_checkout_button").performClick()
    assertEquals(1, launcher.requests.size)
    assertEquals(
      PendingRazorpayLaunch("ord-cancel-1", "order_gw_cancel_1"),
      ordersVm.uiState.value.pendingRazorpayLaunch,
    )

    // Customer dismisses Razorpay sheet -> PAYMENT_CANCELED must NOT fail the server attempt
    ordersVm.onRazorpaySdkError(
      code = Checkout.PAYMENT_CANCELED,
      description = "Cancelled by user",
      paymentData = TestPaymentDataFactory.create(orderId = "order_gw_cancel_1"),
    )
    waitUntilMainLooper { !ordersVm.uiState.value.isPaymentSheetOpen }
    assertEquals(0, failCallCount.get())
    assertNull(ordersVm.uiState.value.pendingRazorpayLaunch)
    assertEquals(
      PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT,
      ordersVm.uiState.value.paymentStatus?.payment?.latestAttempt?.status,
    )

    // Customer reopens payment sheet on the same attempt and completes payment
    composeTestRule.onNodeWithTag("order_detail_lazy_column").performScrollToNode(hasTestTag("open_razorpay_sheet_button"))
    composeTestRule.onNodeWithTag("open_razorpay_sheet_button").assertIsDisplayed().performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("launch_razorpay_checkout_button").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("launch_razorpay_checkout_button").performClick()
    assertEquals(2, launcher.requests.size)

    ordersVm.onRazorpaySdkSuccess(
      razorpayPaymentId = "pay_rzp_after_cancel",
      paymentData = TestPaymentDataFactory.create(
        orderId = "order_gw_cancel_1",
        paymentId = "pay_rzp_after_cancel",
        signature = "sig_after_cancel",
      ),
    )

    waitUntilMainLooper {
      ordersVm.uiState.value.selectedOrder?.status == OrderStatus.PLACED &&
        !ordersVm.uiState.value.isPaymentSheetOpen
    }
    assertNotNull(verifyRequest)
    assertEquals("ord-cancel-1", verifyRequest?.orderId)
    assertEquals("order_gw_cancel_1", verifyRequest?.razorpayOrderId)
    assertEquals("pay_rzp_after_cancel", verifyRequest?.razorpayPaymentId)
    assertEquals("sig_after_cancel", verifyRequest?.razorpaySignature)
    composeTestRule.onNodeWithTag("order_detail_lazy_column").performScrollToNode(hasTestTag("order_detail_status_badge"))
    composeTestRule.onNodeWithTag("order_detail_status_badge").assertTextContains("PLACED")
  }

  @Test
  fun `MainActivity forwards onPaymentError and survives recreate configuration change for onPaymentSuccess`() {
    var orderState = pendingOnlineOrder(orderId = "ord-act-1", gatewayOrderId = "order_gw_act_1")
    var capturedFailReq: FailPaymentAttemptRequest? = null
    var capturedVerifyReq: VerifyRazorpayPaymentRequest? = null

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      when {
        path == "/api/v1/catalog/menu" -> respond(
          content = """{"categories":[],"products":[],"crusts":[],"toppings":[],"gstBps":500,"storeName":"PIZZORA","isAcceptingOrders":true}""",
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/cart" -> respond(
          content = """{"cartId":"c1","items":[],"subtotalPaise":0,"totalItemCount":0,"allAvailable":true,"unavailableCount":0,"updatedAt":"2026-11-16T08:00:00Z"}""",
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/orders" -> respond(
          content = json.encodeToString(
            OrderListResponse(
              listOf(
                OrderSummaryDto(
                  id = orderState.id,
                  orderNumber = orderState.orderNumber,
                  fulfillmentType = orderState.fulfillmentType,
                  scheduleType = orderState.scheduleType,
                  status = orderState.status,
                  version = orderState.version,
                  totalPaise = orderState.totalPaise,
                  itemCount = 1,
                  paymentMethod = orderState.payment.method,
                  paymentStatus = orderState.payment.status,
                  isPriority = false,
                  createdAt = orderState.createdAt,
                  expiresAt = orderState.expiresAt,
                ),
              ),
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/orders/ord-act-1/payment" -> respond(
          content = json.encodeToString(paymentStatusResponse(orderState)),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/orders/ord-act-1/payments/fail" -> {
          val req = json.decodeFromString<FailPaymentAttemptRequest>(bodyAsString(request.body))
          capturedFailReq = req
          orderState = orderState.copy(
            payment = orderState.payment.copy(
              latestAttempt = orderState.payment.latestAttempt!!.copy(
                status = PaymentAttemptStatus.FAILED,
                failureCode = req.failureCode,
                failureReason = req.failureReason,
              ),
            ),
          )
          respond(
            content = json.encodeToString(paymentStatusResponse(orderState)),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/payments/razorpay/verify" -> {
          val req = json.decodeFromString<VerifyRazorpayPaymentRequest>(bodyAsString(request.body))
          capturedVerifyReq = req
          orderState = orderState.copy(
            status = OrderStatus.PLACED,
            version = 2L,
            expiresAt = null,
            payment = orderState.payment.copy(
              status = PaymentStatus.CAPTURED,
              capturedPaise = orderState.totalPaise,
              latestAttempt = orderState.payment.latestAttempt!!.copy(
                status = PaymentAttemptStatus.CAPTURED,
                gatewayOrderId = req.razorpayOrderId,
                gatewayPaymentId = req.razorpayPaymentId,
              ),
            ),
          )
          respond(
            content = json.encodeToString(VerifyRazorpayPaymentResponse(orderState, alreadyCaptured = false, lateCaptureRefundTriggered = false)),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path.startsWith("/api/v1/notifications") -> respond(
          content = """{"notifications":[],"unreadCount":0,"preferences":{"userId":"user-rzp-int","orderUpdates":true,"promoAlerts":true,"kdsSoundEnabled":false}}""",
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        else -> respond(
          content = "{}",
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
      }
    }

    val app = ApplicationProvider.getApplicationContext<PizzoraApplication>()
    app.appContainerOverride = AppContainer(
      context = app,
      httpClientEngineOverride = engine,
      razorpayCheckoutLauncherOverride = RecordingCheckoutLauncher(),
      tokenStorageOverride = InMemoryTokenStorage(customerSession),
    )

    // 1. Verify Activity onPaymentError -> /payments/fail
    val controller1 = Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity1 = controller1.get()
    activity1.ordersViewModel.showCreatedOrder(orderState, openPaymentFlowIfPending = true)
    activity1.ordersViewModel.markRazorpayLaunchStarted("ord-act-1", "order_gw_act_1")
    activity1.onPaymentError(
      Checkout.TLS_ERROR,
      "TLS handshake interrupted",
      TestPaymentDataFactory.create(orderId = "order_gw_act_1"),
    )
    waitUntilMainLooper { capturedFailReq != null }
    assertEquals("RAZORPAY_SDK_${Checkout.TLS_ERROR}", capturedFailReq?.failureCode)
    assertEquals("order_gw_act_1", capturedFailReq?.gatewayOrderId)

    // 2. Verify configuration change (recreate) preserves ViewModel state for onPaymentSuccess
    activity1.ordersViewModel.markRazorpayLaunchStarted("ord-act-1", "order_gw_act_1")
    val savedBundle = Bundle()
    controller1.saveInstanceState(savedBundle)
    controller1.recreate()
    val recreatedActivity = controller1.get()
    assertEquals(
      PendingRazorpayLaunch("ord-act-1", "order_gw_act_1"),
      recreatedActivity.ordersViewModel.uiState.value.pendingRazorpayLaunch,
    )

    // 3. Verify fresh process/Activity restoration from Bundle (even when selectedOrder is null and paymentData omits orderId)
    val controller2 = Robolectric.buildActivity(MainActivity::class.java).create(savedBundle).start().resume()
    val freshRestoredActivity = controller2.get()
    assertEquals(
      PendingRazorpayLaunch("ord-act-1", "order_gw_act_1"),
      freshRestoredActivity.ordersViewModel.uiState.value.pendingRazorpayLaunch,
    )

    freshRestoredActivity.onPaymentSuccess(
      "pay_rzp_recreated_99",
      TestPaymentDataFactory.create(
        orderId = "",
        paymentId = "pay_rzp_recreated_99",
        signature = "sig_recreated_99",
      ),
    )

    waitUntilMainLooper { capturedVerifyReq != null && freshRestoredActivity.ordersViewModel.uiState.value.selectedOrder?.status == OrderStatus.PLACED }
    assertEquals("ord-act-1", capturedVerifyReq?.orderId)
    assertEquals("order_gw_act_1", capturedVerifyReq?.razorpayOrderId)
    assertEquals("pay_rzp_recreated_99", capturedVerifyReq?.razorpayPaymentId)
    assertEquals("sig_recreated_99", capturedVerifyReq?.razorpaySignature)
    assertNull(freshRestoredActivity.ordersViewModel.uiState.value.pendingRazorpayLaunch)

    controller1.pause().stop().destroy()
    controller2.pause().stop().destroy()
  }

  private fun pendingOnlineOrder(orderId: String, gatewayOrderId: String): OrderDetailDto =
    OrderDetailDto(
      id = orderId,
      orderNumber = "PZ-20261116-8801",
      customerId = customerSession.user.id,
      fulfillmentType = FulfillmentType.PICKUP,
      scheduleType = ScheduleType.ASAP,
      status = OrderStatus.PENDING_PAYMENT,
      version = 1L,
      subtotalPaise = 50_000L,
      discountPaise = 0L,
      deliveryFeePaise = 0L,
      taxPaise = 2_500L,
      cgstPaise = 1_250L,
      sgstPaise = 1_250L,
      totalPaise = 52_500L,
      gstBpsSnapshot = 500,
      isPriority = false,
      expiresAt = "2026-11-16T08:15:00Z",
      createdAt = "2026-11-16T08:00:00Z",
      items = listOf(
        OrderItemDto("oi-1", "prod-1", "Margherita", "MEDIUM", "Hand-Tossed", emptyList(), 50_000L, 1, 50_000L),
      ),
      statusHistory = listOf(
        OrderStatusHistoryDto("osh-1", null, OrderStatus.PENDING_PAYMENT, customerSession.user.id, "Awaiting payment", "2026-11-16T08:00:00Z"),
      ),
      payment = PaymentSummaryDto(
        id = "pay-1",
        method = PaymentMethod.RAZORPAY,
        status = PaymentStatus.UNPAID,
        amountPaise = 52_500L,
        capturedPaise = 0L,
        refundedPaise = 0L,
        latestAttempt = PaymentAttemptSummaryDto(
          id = "pa-1",
          attemptNumber = 1,
          method = PaymentMethod.RAZORPAY,
          status = PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT,
          amountPaise = 52_500L,
          gatewayOrderId = gatewayOrderId,
          idempotencyReceipt = "rcpt_0123456789abcdef0123456789abcdef",
          createdAt = "2026-11-16T08:00:00Z",
        ),
      ),
    )

  private fun paymentStatusResponse(order: OrderDetailDto): OrderPaymentStatusResponse =
    OrderPaymentStatusResponse(
      orderId = order.id,
      orderNumber = order.orderNumber,
      orderStatus = order.status,
      payment = order.payment,
      razorpayKeyId = "rzp_test_integration",
      canRetry = order.status == OrderStatus.PENDING_PAYMENT,
      expiresAt = order.expiresAt,
    )

  private fun bodyAsString(body: OutgoingContent): String = when (body) {
    is TextContent -> body.text
    is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
    else -> error("Unexpected body type: ${body::class.simpleName}")
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
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for async condition")
      }
      Thread.sleep(20)
    }
  }
}

package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.auth.AuthRepository
import com.example.auth.AuthViewModel
import com.example.cart.CartViewModel
import com.example.cart.NetworkCartRepository
import com.example.catalog.CustomerCatalogViewModel
import com.example.catalog.NetworkCatalogRepository
import com.example.checkout.CheckoutQuoteViewModel
import com.example.checkout.CheckoutViewModel
import com.example.checkout.NetworkPricingRepository
import com.example.core.database.PizzoraDatabase
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.navigation.PizzoraApp
import com.example.orders.NetworkOrderRepository
import com.example.orders.OrdersViewModel
import com.example.orders.RazorpayCheckoutLauncher
import com.example.orders.RazorpayCheckoutRequest
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CartToppingViewDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.CrustOptionDto
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
import com.pizzora.contracts.PricedLineInput
import com.pizzora.contracts.PricingEngine
import com.pizzora.contracts.PricingQuoteRequest
import com.pizzora.contracts.PricingQuoteResponse
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase11PaymentsRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var db: PizzoraDatabase
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private val sampleMenu = PublicMenuResponse(
    categories = listOf(CategoryDto("cat-11", "Classics", 1, true, "2026-11-13T10:00:00Z")),
    products = listOf(
      ProductDto(
        id = "11111111-1111-7111-8111-111111111111",
        categoryId = "cat-11",
        categoryName = "Classics",
        name = "Truffle Burrata Pizza",
        description = "Fresh burrata and roasted garlic",
        isVeg = true,
        isActive = true,
        isTaxable = true,
        variants = listOf(
          ProductVariantDto("22222222-2222-7222-8222-222222222221", "11111111-1111-7111-8111-111111111111", SizeCode.MEDIUM, 44_900L, true),
        ),
      ),
    ),
    crusts = listOf(CrustOptionDto("33333333-3333-7333-8333-333333333331", "Hand-Tossed", 5_100L, true)),
    toppings = listOf(ToppingDto("44444444-4444-7444-8444-444444444441", "Basil", true, 0L, true)),
    gstBps = 500,
    storeName = "PIZZORA Artisanal Kitchen",
    isAcceptingOrders = true,
  )

  private val customerSession = AuthSession(
    accessToken = "cust-jwt-p11",
    refreshToken = "cust-ref-p11",
    user = UserProfileDto(
      id = "user-p11",
      email = "custp11@pizzora.test",
      emailVerified = true,
      fullName = "Lorenzo Conti",
      phone = "9822221199",
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

  private class SignedInAuthRepository(session: AuthSession) : AuthRepository {
    private val _session = MutableStateFlow<AuthSession?>(session)
    private val _muted = MutableStateFlow(false)
    override val sessionFlow: StateFlow<AuthSession?> = _session.asStateFlow()
    override fun observeKdsSoundMuted(): Flow<Boolean> = _muted
    override suspend fun setKdsSoundMuted(muted: Boolean) {}
    override suspend fun login(email: String, password: String) = _session.value!!
    override suspend fun register(email: String, password: String, fullName: String, phone: String) = ""
    override suspend fun verifyEmail(token: String) = ""
    override suspend fun resendVerification(email: String) = ""
    override suspend fun forgotPassword(email: String) = ""
    override suspend fun resetPassword(token: String, newPassword: String) = ""
    override suspend fun logout() {
      _session.value = null
    }
  }

  private class FakeRazorpayCheckoutLauncher : RazorpayCheckoutLauncher {
    val launchedRequests = mutableListOf<RazorpayCheckoutRequest>()
    override fun launch(activity: android.app.Activity, request: RazorpayCheckoutRequest) {
      launchedRequests += request
    }
  }

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, PizzoraDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `razorpay checkout polls gateway_order_id, supports failure + retry, and verifies into PLACED CAPTURED`() {
    val cartItem = CartItemViewDto(
      id = "cart-item-p11",
      productId = sampleMenu.products[0].id,
      productName = "Truffle Burrata Pizza",
      isVeg = true,
      variantId = sampleMenu.products[0].variants[0].id,
      sizeCode = SizeCode.MEDIUM,
      basePricePaise = 44_900L,
      crustId = sampleMenu.crusts[0].id,
      crustName = "Hand-Tossed",
      crustExtraPricePaise = 5_100L,
      toppings = listOf(CartToppingViewDto(sampleMenu.toppings[0].id, "Basil", true, 0L, true)),
      quantity = 1,
      unitPricePaise = 50_000L,
      lineTotalPaise = 50_000L,
      isAvailable = true,
      issues = emptyList(),
    )

    val breakdown = PricingEngine.calculateQuote(
      lines = listOf(PricedLineInput(50_000L, 1, isTaxable = true)),
      gstBps = 500,
      deliveryFeePaise = 0L,
      coupon = null,
    )

    var currentOrder: OrderDetailDto? = null
    val pollCounter = AtomicInteger(0)
    var retryCount = 0
    var capturedVerifyReq: VerifyRazorpayPaymentRequest? = null
    var capturedFailReq: FailPaymentAttemptRequest? = null
    var capturedRetryIdempotencyKey: String? = null
    val fakeLauncher = FakeRazorpayCheckoutLauncher()

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      when {
        path == "/api/v1/catalog/menu" -> respond(
          content = json.encodeToString(sampleMenu),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/cart" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(
            CartResponseDto(
              cartId = "server-cart-p11",
              items = if (currentOrder == null) listOf(cartItem) else emptyList(),
              subtotalPaise = if (currentOrder == null) 50_000L else 0L,
              totalItemCount = if (currentOrder == null) 1 else 0,
              allAvailable = true,
              unavailableCount = 0,
              updatedAt = "2026-11-13T16:00:00Z",
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/cart" && request.method == HttpMethod.Put -> respond(
          content = json.encodeToString(
            CartResponseDto("server-cart-p11", emptyList(), 0L, 0, true, 0, "2026-11-13T16:02:00Z"),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/pricing/quote" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<PricingQuoteRequest>(bodyAsString(request.body))
          respond(
            content = json.encodeToString(
              PricingQuoteResponse(
                items = listOf(cartItem),
                fulfillmentType = req.fulfillmentType,
                breakdown = breakdown,
                appliedCoupon = null,
                deliveryZone = null,
                isAcceptingOrders = true,
                gstBps = 500,
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/me/addresses" && request.method == HttpMethod.Get -> respond(
          content = "[]",
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/checkout" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<CheckoutRequest>(bodyAsString(request.body))
          val created = OrderDetailDto(
            id = "ord-p11-1",
            orderNumber = "PZ-20261113-1101",
            customerId = customerSession.user.id,
            fulfillmentType = req.fulfillmentType,
            scheduleType = req.scheduleType,
            status = OrderStatus.PENDING_PAYMENT,
            version = 1L,
            subtotalPaise = breakdown.subtotalPaise,
            discountPaise = 0L,
            deliveryFeePaise = 0L,
            taxPaise = breakdown.taxPaise,
            cgstPaise = breakdown.cgstPaise,
            sgstPaise = breakdown.sgstPaise,
            totalPaise = breakdown.totalPaise,
            gstBpsSnapshot = 500,
            isPriority = false,
            expiresAt = "2026-11-13T16:15:00Z",
            createdAt = "2026-11-13T16:00:00Z",
            items = listOf(
              OrderItemDto("oi-p11-1", cartItem.productId, cartItem.productName, "MEDIUM", "Hand-Tossed", listOf("Basil"), 50_000L, 1, 50_000L),
            ),
            statusHistory = listOf(
              OrderStatusHistoryDto("osh-11-1", null, OrderStatus.PENDING_PAYMENT, customerSession.user.id, "Awaiting online payment", "2026-11-13T16:00:00Z"),
            ),
            payment = PaymentSummaryDto(
              id = "pay-p11-1",
              method = PaymentMethod.RAZORPAY,
              status = PaymentStatus.UNPAID,
              amountPaise = breakdown.totalPaise,
              capturedPaise = 0L,
              refundedPaise = 0L,
              latestAttempt = PaymentAttemptSummaryDto(
                id = "pa-p11-1",
                attemptNumber = 1,
                method = PaymentMethod.RAZORPAY,
                status = PaymentAttemptStatus.INITIATING,
                amountPaise = breakdown.totalPaise,
                idempotencyReceipt = "rcpt-p11-1",
                createdAt = "2026-11-13T16:00:00Z",
              ),
            ),
          )
          currentOrder = created
          respond(
            content = json.encodeToString(CheckoutResponse(created, false)),
            status = HttpStatusCode.Created,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/orders" && request.method == HttpMethod.Get -> {
          val list = currentOrder?.let { d ->
            listOf(
              OrderSummaryDto(
                d.id, d.orderNumber, d.fulfillmentType, d.scheduleType, d.status, d.version,
                d.totalPaise, 1, d.payment.method, d.payment.status, false, d.createdAt, d.expiresAt,
              ),
            )
          }.orEmpty()
          respond(
            content = json.encodeToString(OrderListResponse(list)),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/orders/ord-p11-1/payment" && request.method == HttpMethod.Get -> {
          val existing = currentOrder!!
          val callNum = pollCounter.incrementAndGet()
          // 1st poll returns INITIATING; 2nd+ polls transition to AWAITING_CUSTOMER_PAYMENT with gatewayOrderId!
          val attempt = existing.payment.latestAttempt!!
          val updatedAttempt = if (existing.status == OrderStatus.PENDING_PAYMENT &&
            attempt.status == PaymentAttemptStatus.INITIATING &&
            callNum >= 2
          ) {
            val gwId = if (attempt.attemptNumber == 1) "order_gw_1" else "order_gw_2"
            attempt.copy(status = PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT, gatewayOrderId = gwId)
          } else {
            attempt
          }
          val updatedOrder = existing.copy(payment = existing.payment.copy(latestAttempt = updatedAttempt))
          currentOrder = updatedOrder
          respond(
            content = json.encodeToString(
              OrderPaymentStatusResponse(
                orderId = updatedOrder.id,
                orderNumber = updatedOrder.orderNumber,
                orderStatus = updatedOrder.status,
                payment = updatedOrder.payment,
                razorpayKeyId = "rzp_test_pizzora",
                canRetry = updatedOrder.status == OrderStatus.PENDING_PAYMENT,
                expiresAt = updatedOrder.expiresAt,
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/orders/ord-p11-1/payments/fail" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<FailPaymentAttemptRequest>(bodyAsString(request.body))
          capturedFailReq = req
          val existing = currentOrder!!
          val failedAttempt = existing.payment.latestAttempt!!.copy(
            status = PaymentAttemptStatus.FAILED,
            failureCode = req.failureCode ?: "PAYMENT_FAILED",
            failureReason = req.failureReason ?: "Payment failed",
          )
          val updatedOrder = existing.copy(payment = existing.payment.copy(latestAttempt = failedAttempt))
          currentOrder = updatedOrder
          respond(
            content = json.encodeToString(
              OrderPaymentStatusResponse(
                orderId = updatedOrder.id,
                orderNumber = updatedOrder.orderNumber,
                orderStatus = updatedOrder.status,
                payment = updatedOrder.payment,
                razorpayKeyId = "rzp_test_pizzora",
                canRetry = true,
                expiresAt = updatedOrder.expiresAt,
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/orders/ord-p11-1/payments/retry" && request.method == HttpMethod.Post -> {
          capturedRetryIdempotencyKey = request.headers["Idempotency-Key"]
          retryCount++
          pollCounter.set(0) // reset so next poll shows INITIATING -> AWAITING_CUSTOMER_PAYMENT for attempt #2
          val existing = currentOrder!!
          val retryAttempt = PaymentAttemptSummaryDto(
            id = "pa-p11-2",
            attemptNumber = 2,
            method = PaymentMethod.RAZORPAY,
            status = PaymentAttemptStatus.INITIATING,
            amountPaise = existing.totalPaise,
            idempotencyReceipt = "retry:ord-p11-1",
            createdAt = "2026-11-13T16:01:00Z",
          )
          val updatedOrder = existing.copy(payment = existing.payment.copy(latestAttempt = retryAttempt))
          currentOrder = updatedOrder
          respond(
            content = json.encodeToString(
              OrderPaymentStatusResponse(
                orderId = updatedOrder.id,
                orderNumber = updatedOrder.orderNumber,
                orderStatus = updatedOrder.status,
                payment = updatedOrder.payment,
                razorpayKeyId = "rzp_test_pizzora",
                canRetry = true,
                expiresAt = updatedOrder.expiresAt,
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/payments/razorpay/verify" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<VerifyRazorpayPaymentRequest>(bodyAsString(request.body))
          capturedVerifyReq = req
          val existing = currentOrder!!
          val capturedAttempt = existing.payment.latestAttempt!!.copy(
            status = PaymentAttemptStatus.CAPTURED,
            gatewayOrderId = req.razorpayOrderId,
            gatewayPaymentId = req.razorpayPaymentId,
          )
          val placedOrder = existing.copy(
            status = OrderStatus.PLACED,
            version = existing.version + 1L,
            expiresAt = null,
            payment = existing.payment.copy(
              status = PaymentStatus.CAPTURED,
              capturedPaise = existing.totalPaise,
              latestAttempt = capturedAttempt,
            ),
            statusHistory = existing.statusHistory + OrderStatusHistoryDto(
              id = "osh-11-2",
              fromStatus = OrderStatus.PENDING_PAYMENT,
              toStatus = OrderStatus.PLACED,
              changedBy = customerSession.user.id,
              reason = "Payment captured (CLIENT_VERIFY)",
              createdAt = "2026-11-13T16:02:00Z",
            ),
          )
          currentOrder = placedOrder
          respond(
            content = json.encodeToString(VerifyRazorpayPaymentResponse(placedOrder, alreadyCaptured = false, lateCaptureRefundTriggered = false)),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected ${request.method.value} $path")
      }
    }

    val apiClient = PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(customerSession), engine)
    val authVm = AuthViewModel(SignedInAuthRepository(customerSession))
    val catalogVm = CustomerCatalogViewModel(NetworkCatalogRepository(apiClient, db.catalogCacheDao()))
    val cartVm = CartViewModel(NetworkCartRepository(apiClient, db.cartDao()))
    val quoteVm = CheckoutQuoteViewModel(NetworkPricingRepository(apiClient))
    val orderRepo = NetworkOrderRepository(apiClient)
    val checkoutVm = CheckoutViewModel(orderRepo)
    val ordersVm = OrdersViewModel(orderRepo, initialPollDelayMs = 30L)

    composeTestRule.setContent {
      PizzoraApp(
        authViewModel = authVm,
        catalogViewModel = catalogVm,
        cartViewModel = cartVm,
        checkoutQuoteViewModel = quoteVm,
        checkoutViewModel = checkoutVm,
        ordersViewModel = ordersVm,
        razorpayCheckoutLauncher = fakeLauncher,
      )
    }

    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("cart_tab_badge", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("proceed_to_checkout_button").fetchSemanticsNodes().isNotEmpty()
    }

    // Switch to PICKUP & proceed to Checkout
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("fulfillment_pickup_chip"))
    composeTestRule.onNodeWithTag("fulfillment_pickup_chip").performClick()
    waitUntilMainLooper {
      quoteVm.uiState.value.fulfillmentType == FulfillmentType.PICKUP && !cartVm.uiState.value.isSyncing
    }
    composeTestRule.onNodeWithTag("proceed_to_checkout_button").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("checkout_screen").fetchSemanticsNodes().isNotEmpty()
    }

    // Select Razorpay and place order -> transitions to OrderDetail and auto-polls until order_gw_1 arrives
    composeTestRule.onNodeWithTag("checkout_lazy_column").performScrollToNode(hasTestTag("payment_method_razorpay"))
    composeTestRule.onNodeWithTag("payment_method_razorpay").performClick()
    composeTestRule.onNodeWithTag("place_order_button").performClick()

    waitUntilMainLooper(timeoutMs = 8_000) {
      composeTestRule.onAllNodesWithTag("razorpay_gateway_order_id_text").fetchSemanticsNodes().isNotEmpty() &&
        composeTestRule.onAllNodesWithTag("launch_razorpay_checkout_button").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("razorpay_gateway_order_id_text").assertTextContains("order_gw_1", substring = true)
    composeTestRule.onNodeWithTag("razorpay_key_id_text").assertTextContains("rzp_test_pizzora", substring = true)

    // Placeholder manual fields must NOT exist
    assertTrue(composeTestRule.onAllNodesWithTag("razorpay_payment_id_input").fetchSemanticsNodes().isEmpty())
    assertTrue(composeTestRule.onAllNodesWithTag("razorpay_signature_input").fetchSemanticsNodes().isEmpty())

    // Launch official Razorpay Checkout for attempt #1
    composeTestRule.onNodeWithTag("launch_razorpay_checkout_button").performClick()
    assertEquals(1, fakeLauncher.launchedRequests.size)
    val req1 = fakeLauncher.launchedRequests[0]
    assertEquals("ord-p11-1", req1.orderId)
    assertEquals("order_gw_1", req1.gatewayOrderId)
    assertEquals("rzp_test_pizzora", req1.razorpayKeyId)
    assertEquals(breakdown.totalPaise, req1.amountPaise)
    assertEquals(customerSession.user.email, req1.customerEmail)

    // Simulate SDK payment error callback -> calls /payments/fail, dismisses sheet, enables retry
    ordersVm.onRazorpaySdkError(
      code = Checkout.NETWORK_ERROR,
      description = "Bank declined transaction",
      paymentData = TestPaymentDataFactory.create(orderId = "order_gw_1"),
    )
    waitUntilMainLooper(
      debugMessage = {
        "attempt=${ordersVm.uiState.value.paymentStatus?.payment?.latestAttempt?.status}, " +
          "sheetOpen=${ordersVm.uiState.value.isPaymentSheetOpen}, " +
          "reporting=${ordersVm.uiState.value.isReportingFailure}, " +
          "error=${ordersVm.uiState.value.paymentError}"
      },
    ) {
      ordersVm.uiState.value.paymentStatus?.payment?.latestAttempt?.status == PaymentAttemptStatus.FAILED &&
        !ordersVm.uiState.value.isPaymentSheetOpen
    }
    assertNotNull(capturedFailReq)
    assertEquals("order_gw_1", capturedFailReq?.gatewayOrderId)
    assertEquals("RAZORPAY_SDK_${Checkout.NETWORK_ERROR}", capturedFailReq?.failureCode)

    composeTestRule.mainClock.advanceTimeBy(5_000)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("order_detail_lazy_column").performScrollToNode(hasTestTag("retry_payment_button"))
    composeTestRule.onNodeWithTag("retry_payment_button").assertIsDisplayed().performClick()

    // Wait for retry #2 gateway order polling to emit order_gw_2
    waitUntilMainLooper(
      timeoutMs = 8_000,
      debugMessage = {
        val st = ordersVm.uiState.value
        "retryCount=$retryCount, pollCount=${pollCounter.get()}, isRetrying=${st.isRetryingPayment}, " +
          "isPolling=${st.isPollingGatewayOrder}, sheetOpen=${st.isPaymentSheetOpen}, " +
          "attemptStatus=${st.paymentStatus?.payment?.latestAttempt?.status}, " +
          "gwOrderId=${st.paymentStatus?.payment?.latestAttempt?.gatewayOrderId}, " +
          "error=${st.paymentError}"
      },
    ) {
      ordersVm.uiState.value.paymentStatus?.payment?.latestAttempt?.gatewayOrderId == "order_gw_2" &&
        composeTestRule.onAllNodesWithTag("launch_razorpay_checkout_button").fetchSemanticsNodes().isNotEmpty()
    }
    assertEquals(1, retryCount)
    assertFalse(capturedRetryIdempotencyKey.isNullOrBlank())

    composeTestRule.onNodeWithTag("razorpay_gateway_order_id_text").performScrollTo().assertTextContains("order_gw_2", substring = true)
    composeTestRule.onNodeWithTag("launch_razorpay_checkout_button").performClick()
    assertEquals(2, fakeLauncher.launchedRequests.size)
    assertEquals("order_gw_2", fakeLauncher.launchedRequests[1].gatewayOrderId)

    // Simulate official SDK payment success callback -> calls /payments/razorpay/verify into PLACED + CAPTURED
    ordersVm.onRazorpaySdkSuccess(
      razorpayPaymentId = "pay_rzp_ok_2",
      paymentData = TestPaymentDataFactory.create(
        orderId = "order_gw_2",
        paymentId = "pay_rzp_ok_2",
        signature = "sig_valid_p11",
      ),
    )

    waitUntilMainLooper {
      ordersVm.uiState.value.selectedOrder?.status == OrderStatus.PLACED &&
        !ordersVm.uiState.value.isPaymentSheetOpen
    }

    assertEquals("ord-p11-1", capturedVerifyReq?.orderId)
    assertEquals("order_gw_2", capturedVerifyReq?.razorpayOrderId)
    assertEquals("pay_rzp_ok_2", capturedVerifyReq?.razorpayPaymentId)
    assertEquals("sig_valid_p11", capturedVerifyReq?.razorpaySignature)

    composeTestRule.onNodeWithTag("order_detail_lazy_column").performScrollToNode(hasTestTag("order_detail_status_badge"))
    composeTestRule.onNodeWithTag("order_detail_status_badge").assertTextContains("PLACED")
    composeTestRule.onNodeWithTag("order_detail_lazy_column").performScrollToNode(hasTestTag("order_detail_payment_status"))
    composeTestRule.onNodeWithTag("order_detail_payment_status").assertTextContains("CAPTURED", substring = true)
    assertTrue(composeTestRule.onAllNodesWithTag("razorpay_payment_action_card").fetchSemanticsNodes().isEmpty())
  }

  private fun bodyAsString(body: OutgoingContent): String = when (body) {
    is TextContent -> body.text
    is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
    else -> error("Unexpected body type: ${body::class.simpleName}")
  }

  private fun waitUntilMainLooper(
    timeoutMs: Long = 5_000,
    debugMessage: () -> String = { "" },
    condition: () -> Boolean,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.waitForIdle()
      if (condition()) return
      if (System.currentTimeMillis() > deadline) {
        val extra = debugMessage().takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for async Robolectric/Compose state$extra")
      }
      Thread.sleep(20)
    }
  }
}

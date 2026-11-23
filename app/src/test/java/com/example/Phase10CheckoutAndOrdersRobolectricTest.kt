package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.CancelOrderRequest
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CartToppingViewDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.DeliveryAddressSnapshotDto
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.DeliveryZoneDto
import com.pizzora.contracts.DeliveryZoneQuoteDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderItemDto
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderStatusHistoryDto
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.PaymentAttemptSummaryDto
import com.pizzora.contracts.PaymentAttemptStatus
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
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
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
class Phase10CheckoutAndOrdersRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var db: PizzoraDatabase
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private val sampleMenu = PublicMenuResponse(
    categories = listOf(
      CategoryDto("cat-1", "Classics", 1, true, "2026-11-13T10:00:00Z"),
    ),
    products = listOf(
      ProductDto(
        id = "11111111-1111-7111-8111-111111111111",
        categoryId = "cat-1",
        categoryName = "Classics",
        name = "Margherita Verace",
        description = "San Marzano DOP & Fior di Latte",
        isVeg = true,
        isActive = true,
        isTaxable = true,
        variants = listOf(
          ProductVariantDto("22222222-2222-7222-8222-222222222221", "11111111-1111-7111-8111-111111111111", SizeCode.MEDIUM, 44_900L, true),
        ),
      ),
    ),
    crusts = listOf(
      CrustOptionDto("33333333-3333-7333-8333-333333333331", "Hand-Tossed", 5_100L, true),
    ),
    toppings = listOf(
      ToppingDto("44444444-4444-7444-8444-444444444441", "Extra Basil", true, 0L, true),
    ),
    gstBps = 500,
    storeName = "PIZZORA Artisanal Kitchen",
    isAcceptingOrders = true,
  )

  private val customerSession = AuthSession(
    accessToken = "cust-jwt-p10",
    refreshToken = "cust-ref-p10",
    user = UserProfileDto(
      id = "user-p10",
      email = "custp10@pizzora.test",
      emailVerified = true,
      fullName = "Elena Rossi",
      phone = "9811111199",
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
  fun `customer adds delivery address, places COD order with Idempotency-Key, views order detail, and cancels`() {
    val cartItem = CartItemViewDto(
      id = "cart-item-p10",
      productId = sampleMenu.products[0].id,
      productName = "Margherita Verace",
      isVeg = true,
      variantId = sampleMenu.products[0].variants[0].id,
      sizeCode = SizeCode.MEDIUM,
      basePricePaise = 44_900L,
      crustId = sampleMenu.crusts[0].id,
      crustName = "Hand-Tossed",
      crustExtraPricePaise = 5_100L,
      toppings = listOf(
        CartToppingViewDto(sampleMenu.toppings[0].id, "Extra Basil", true, 0L, true),
      ),
      quantity = 1,
      unitPricePaise = 50_000L,
      lineTotalPaise = 50_000L,
      isAvailable = true,
      issues = emptyList(),
    )

    val addresses = mutableListOf<AddressDto>()
    val orders = mutableMapOf<String, OrderDetailDto>()
    var capturedIdempotencyKey: String? = null
    var capturedCheckoutRequest: CheckoutRequest? = null
    var capturedCancelReason: String? = null

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
              cartId = "server-cart-p10",
              items = if (orders.isEmpty()) listOf(cartItem) else emptyList(),
              subtotalPaise = if (orders.isEmpty()) 50_000L else 0L,
              totalItemCount = if (orders.isEmpty()) 1 else 0,
              allAvailable = true,
              unavailableCount = 0,
              updatedAt = "2026-11-13T15:00:00Z",
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/cart" && request.method == HttpMethod.Put -> respond(
          content = json.encodeToString(
            CartResponseDto(
              cartId = "server-cart-p10",
              items = emptyList(),
              subtotalPaise = 0L,
              totalItemCount = 0,
              allAvailable = true,
              unavailableCount = 0,
              updatedAt = "2026-11-13T15:02:00Z",
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/pricing/quote" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<PricingQuoteRequest>(bodyAsString(request.body))
          val deliveryFee = if (req.fulfillmentType == FulfillmentType.DELIVERY) 4_000L else 0L
          val breakdown = PricingEngine.calculateQuote(
            lines = listOf(PricedLineInput(50_000L, 1, isTaxable = true)),
            gstBps = 500,
            deliveryFeePaise = deliveryFee,
            coupon = null,
          )
          respond(
            content = json.encodeToString(
              PricingQuoteResponse(
                items = listOf(cartItem),
                fulfillmentType = req.fulfillmentType,
                breakdown = breakdown,
                appliedCoupon = null,
                deliveryZone = if (req.fulfillmentType == FulfillmentType.DELIVERY) {
                  DeliveryZoneQuoteDto("560001", "MG Road", 30_000L, 4_000L, 30)
                } else {
                  null
                },
                isAcceptingOrders = true,
                gstBps = 500,
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/delivery-zones/check" && request.method == HttpMethod.Get -> {
          val pin = request.url.parameters["pincode"].orEmpty()
          respond(
            content = json.encodeToString(
              DeliveryZoneCheckResponse(
                pincode = pin,
                isServiceable = true,
                zone = DeliveryZoneDto(
                  id = "zone-p10",
                  pincode = pin,
                  areaName = "MG Road",
                  minOrderPaise = 30_000L,
                  deliveryFeePaise = 4_000L,
                  estimatedMins = 30,
                  isActive = true,
                ),
                message = "Delivers to $pin in ~30 mins.",
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/me/addresses" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(addresses.toList()),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/me/addresses" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<UpsertAddressRequest>(bodyAsString(request.body))
          val created = AddressDto(
            id = "addr-p10-1",
            label = req.label,
            line1 = req.line1,
            line2 = req.line2,
            landmark = req.landmark,
            pincode = req.pincode,
            city = req.city,
            isDefault = req.isDefault,
          )
          addresses.add(created)
          respond(
            content = json.encodeToString(created),
            status = HttpStatusCode.Created,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/checkout" && request.method == HttpMethod.Post -> {
          capturedIdempotencyKey = request.headers["Idempotency-Key"]
          val req = json.decodeFromString<CheckoutRequest>(bodyAsString(request.body))
          capturedCheckoutRequest = req
          val addr = addresses.first { it.id == req.addressId }
          val breakdown = PricingEngine.calculateQuote(
            lines = listOf(PricedLineInput(50_000L, 1, isTaxable = true)),
            gstBps = 500,
            deliveryFeePaise = 4_000L,
            coupon = null,
          )
          val created = OrderDetailDto(
            id = "ord-p10-1",
            orderNumber = "PZ-20261113-0042",
            customerId = customerSession.user.id,
            fulfillmentType = req.fulfillmentType,
            scheduleType = req.scheduleType,
            scheduledSlotId = null,
            deliveryAddress = DeliveryAddressSnapshotDto(
              addressId = addr.id,
              label = addr.label,
              line1 = addr.line1,
              pincode = addr.pincode,
              city = addr.city,
            ),
            status = OrderStatus.PLACED,
            version = 1L,
            subtotalPaise = breakdown.subtotalPaise,
            discountPaise = breakdown.discountPaise,
            deliveryFeePaise = breakdown.deliveryFeePaise,
            taxPaise = breakdown.taxPaise,
            cgstPaise = breakdown.cgstPaise,
            sgstPaise = breakdown.sgstPaise,
            totalPaise = breakdown.totalPaise,
            gstBpsSnapshot = 500,
            couponCodeSnapshot = null,
            isPriority = false,
            createdAt = "2026-11-13T15:01:00Z",
            items = listOf(
              OrderItemDto(
                id = "oi-p10-1",
                productId = cartItem.productId,
                productName = cartItem.productName,
                variantSize = "MEDIUM",
                crustName = "Hand-Tossed",
                toppings = listOf("Extra Basil"),
                unitPricePaise = 50_000L,
                quantity = 1,
                lineTotalPaise = 50_000L,
              ),
            ),
            statusHistory = listOf(
              OrderStatusHistoryDto(
                id = "osh-1",
                fromStatus = null,
                toStatus = OrderStatus.PLACED,
                changedBy = customerSession.user.id,
                reason = "Order placed via COD",
                createdAt = "2026-11-13T15:01:00Z",
              ),
            ),
            payment = PaymentSummaryDto(
              id = "pay-p10-1",
              method = PaymentMethod.COD,
              status = PaymentStatus.COD_PENDING,
              amountPaise = breakdown.totalPaise,
              capturedPaise = 0L,
              refundedPaise = 0L,
              latestAttempt = PaymentAttemptSummaryDto(
                id = "pa-p10-1",
                attemptNumber = 1,
                method = PaymentMethod.COD,
                status = PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT,
                amountPaise = breakdown.totalPaise,
                idempotencyReceipt = "rcpt-p10-1",
                createdAt = "2026-11-13T15:01:00Z",
              ),
            ),
          )
          orders[created.id] = created
          respond(
            content = json.encodeToString(CheckoutResponse(order = created, idempotentReplay = false)),
            status = HttpStatusCode.Created,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/orders" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(
            OrderListResponse(
              orders = orders.values.map { detail ->
                OrderSummaryDto(
                  id = detail.id,
                  orderNumber = detail.orderNumber,
                  fulfillmentType = detail.fulfillmentType,
                  scheduleType = detail.scheduleType,
                  status = detail.status,
                  version = detail.version,
                  totalPaise = detail.totalPaise,
                  itemCount = detail.items.sumOf { it.quantity },
                  paymentMethod = detail.payment.method,
                  paymentStatus = detail.payment.status,
                  isPriority = detail.isPriority,
                  createdAt = detail.createdAt,
                  expiresAt = detail.expiresAt,
                )
              },
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/orders/ord-p10-1/cancel" && request.method == HttpMethod.Post -> {
          val cancelReq = json.decodeFromString<CancelOrderRequest>(bodyAsString(request.body))
          capturedCancelReason = cancelReq.reason
          val existing = orders.getValue("ord-p10-1")
          val updated = existing.copy(
            status = OrderStatus.CANCELLED,
            version = existing.version + 1L,
            statusHistory = existing.statusHistory + OrderStatusHistoryDto(
              id = "osh-2",
              fromStatus = OrderStatus.PLACED,
              toStatus = OrderStatus.CANCELLED,
              changedBy = customerSession.user.id,
              reason = cancelReq.reason,
              createdAt = "2026-11-13T15:02:00Z",
            ),
            payment = existing.payment.copy(status = PaymentStatus.CANCELLED),
          )
          orders[updated.id] = updated
          respond(
            content = json.encodeToString(updated),
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
    val ordersVm = OrdersViewModel(orderRepo)

    composeTestRule.setContent {
      PizzoraApp(
        authViewModel = authVm,
        catalogViewModel = catalogVm,
        cartViewModel = cartVm,
        checkoutQuoteViewModel = quoteVm,
        checkoutViewModel = checkoutVm,
        ordersViewModel = ordersVm,
      )
    }

    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("cart_tab_badge", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("proceed_to_checkout_button").fetchSemanticsNodes().isNotEmpty()
    }

    // Proceed from Cart to Checkout
    composeTestRule.onNodeWithTag("proceed_to_checkout_button").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("checkout_screen").fetchSemanticsNodes().isNotEmpty() &&
        !checkoutVm.uiState.value.isLoadingAddresses
    }

    // Add delivery address via modal dialog
    composeTestRule.onNodeWithTag("checkout_lazy_column").performScrollToNode(hasTestTag("no_addresses_text"))
    composeTestRule.onNodeWithTag("no_addresses_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("checkout_lazy_column").performScrollToNode(hasTestTag("add_address_button"))
    composeTestRule.onNodeWithTag("add_address_button").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("address_line1_input").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("address_line1_input").performTextInput("42 Indiranagar 100ft Rd")
    composeTestRule.onNodeWithTag("address_pincode_input").performTextInput("560001")
    composeTestRule.onNodeWithTag("save_address_button").performClick()
    waitUntilMainLooper {
      checkoutVm.uiState.value.addresses.size == 1 && !checkoutVm.uiState.value.isAddAddressDialogOpen
    }
    composeTestRule.onNodeWithTag("checkout_lazy_column").performScrollToNode(hasTestTag("address_card_addr-p10-1"))
    composeTestRule.onNodeWithTag("address_card_addr-p10-1").assertIsDisplayed()

    // Select COD payment method and place order
    composeTestRule.onNodeWithTag("checkout_lazy_column").performScrollToNode(hasTestTag("payment_method_cod"))
    composeTestRule.onNodeWithTag("payment_method_cod").performClick()
    waitUntilMainLooper {
      quoteVm.uiState.value.isQuoteFresh
    }
    composeTestRule.onNodeWithTag("place_order_button").performClick()

    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("order_detail_screen").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.mainClock.advanceTimeBy(5_000)
    composeTestRule.waitForIdle()

    // Verify Idempotency-Key header & checkout payload
    assertFalse(capturedIdempotencyKey.isNullOrBlank())
    assertNotNull(capturedCheckoutRequest)
    assertEquals(PaymentMethod.COD, capturedCheckoutRequest?.paymentMethod)
    assertEquals(FulfillmentType.DELIVERY, capturedCheckoutRequest?.fulfillmentType)
    assertEquals("addr-p10-1", capturedCheckoutRequest?.addressId)

    // Verify OrderDetailScreen content & cart cleared
    assertTrue(cartVm.uiState.value.items.isEmpty())
    composeTestRule.onNodeWithTag("order_detail_number_text").assertTextContains("PZ-20261113-0042")
    composeTestRule.onNodeWithTag("order_detail_lazy_column").performScrollToNode(hasTestTag("order_detail_status_badge"))
    composeTestRule.onNodeWithTag("order_detail_status_badge").assertTextContains("PLACED")
    composeTestRule.onNodeWithTag("order_detail_lazy_column").performScrollToNode(hasTestTag("order_detail_payment_status"))
    composeTestRule.onNodeWithTag("order_detail_payment_status").assertTextContains("COD", substring = true)
    composeTestRule.onNodeWithTag("order_detail_lazy_column").performScrollToNode(hasTestTag("order_detail_item_oi-p10-1"))
    composeTestRule.onNodeWithTag("order_detail_item_oi-p10-1").assertIsDisplayed()

    // Cancel order with reason
    composeTestRule.onNodeWithTag("order_detail_lazy_column").performScrollToNode(hasTestTag("order_detail_status_badge"))
    composeTestRule.onNodeWithTag("order_detail_lazy_column").performScrollToNode(hasTestTag("cancel_order_button"))
    composeTestRule.onNodeWithTag("cancel_order_button").assertIsDisplayed().performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("cancel_reason_input").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("cancel_reason_input").performTextInput("Changed my mind")
    composeTestRule.onNodeWithTag("confirm_cancel_order_button").performClick()

    waitUntilMainLooper {
      ordersVm.uiState.value.selectedOrder?.status == OrderStatus.CANCELLED && !ordersVm.uiState.value.isCancelling
    }

    assertEquals("Changed my mind", capturedCancelReason)
    composeTestRule.onNodeWithTag("order_detail_lazy_column").performScrollToNode(hasTestTag("order_detail_status_badge"))
    composeTestRule.onNodeWithTag("order_detail_status_badge").assertTextContains("CANCELLED")
    assertTrue(composeTestRule.onAllNodesWithTag("cancel_order_button").fetchSemanticsNodes().isEmpty())
    composeTestRule.onNodeWithTag("order_detail_lazy_column").performScrollToNode(hasTestTag("order_status_history_card"))
    composeTestRule.onNodeWithTag("order_status_history_card")
      .assert(hasAnyDescendant(hasText("Changed my mind", substring = true)))
  }

  private fun bodyAsString(body: OutgoingContent): String = when (body) {
    is TextContent -> body.text
    is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
    else -> error("Unexpected request body type: ${body::class.simpleName}")
  }

  private fun waitUntilMainLooper(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.waitForIdle()
      if (condition()) return
      if (System.currentTimeMillis() > deadline) {
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for async Robolectric/Compose state")
      }
      Thread.sleep(20)
    }
  }
}

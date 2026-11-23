package com.example

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import com.example.checkout.AddAddressDialog
import com.example.checkout.CheckoutQuoteUiState
import com.example.checkout.CheckoutQuoteViewModel
import com.example.checkout.CheckoutScreen
import com.example.checkout.CheckoutViewModel
import com.example.checkout.NetworkPricingRepository
import com.example.core.database.CartItemEntity
import com.example.core.database.CartItemToppingEntity
import com.example.core.database.CartItemWithToppings
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.orders.NetworkOrderRepository
import com.example.ui.components.OrderDisplayFormatters
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.ApiErrorDetail
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AppliedCouponSummaryDto
import com.pizzora.contracts.AvailableCouponsResponse
import com.pizzora.contracts.AvailableSlotsResponse
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartToppingViewDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.CouponCalculationSpec
import com.pizzora.contracts.DeliveryAddressSnapshotDto
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.DeliveryZoneDto
import com.pizzora.contracts.DeliveryZoneQuoteDto
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.Money
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderItemDto
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderStatusHistoryDto
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentAttemptSummaryDto
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PaymentSummaryDto
import com.pizzora.contracts.PricedLineInput
import com.pizzora.contracts.PricingEngine
import com.pizzora.contracts.PricingQuoteRequest
import com.pizzora.contracts.PricingQuoteResponse
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTrace

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class Phase5CheckoutQuoteIntegrityRobolectricTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private val customerSession = AuthSession(
    accessToken = "cust-jwt-p5",
    refreshToken = "cust-ref-p5",
    user = UserProfileDto(
      id = "user-p5",
      email = "custp5@pizzora.test",
      emailVerified = true,
      fullName = "Test User",
      phone = "9811111199",
      role = UserRole.CUSTOMER,
      isActive = true,
    ),
  )

  private fun bodyAsString(body: OutgoingContent): String = when (body) {
    is TextContent -> body.text
    is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
    else -> error("Unexpected request body type: ${body::class.simpleName}")
  }

  /** Gradle test workers do not run from the repository root; resolve source files upward. */
  private fun sourceFile(relativePath: String): java.io.File {
    var dir = java.io.File(System.getProperty("user.dir") ?: ".")
    repeat(6) {
      val candidate = java.io.File(dir, relativePath)
      if (candidate.isFile) return candidate
      dir = dir.parentFile ?: return error("Cannot locate $relativePath from $dir")
    }
    error("Cannot locate $relativePath from ${System.getProperty("user.dir")}")
  }

  /**
   * Commits text through the field's SetText semantics action, the same state update an IME
   * performs. Robolectric cannot drive the focus/IME path for a windowed dialog reliably.
   */
  private fun enterText(tag: String, text: String) {
    composeTestRule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetText) {
      it(AnnotatedString(text))
    }
  }

  /**
   * Robolectric never runs the draw pass for a dialog window, so Compose's idling resource keeps
   * reporting a pending measure/layout pass forever. Marking the registered compose views GONE
   * makes that check skip them while leaving the semantics tree available to the assertions.
   */
  private fun ignorePendingDialogLayout() {
    fun fieldsOf(value: Any?): List<java.lang.reflect.Field> {
      val fields = mutableListOf<java.lang.reflect.Field>()
      var type: Class<*>? = value?.javaClass
      while (type != null) {
        fields.addAll(type.declaredFields)
        type = type.superclass
      }
      return fields
    }

    // Give the dialog window's compose view a chance to register before marking it GONE.
    repeat(3) {
      composeTestRule.mainClock.advanceTimeByFrame()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    val environment = composeTestRule.javaClass.getDeclaredField("environment")
      .apply { isAccessible = true }
      .get(composeTestRule)
    val registry = fieldsOf(environment).first { it.type.name.contains("ComposeRootRegistry") }
      .apply { isAccessible = true }
      .get(environment)
    @Suppress("UNCHECKED_CAST")
    val roots = registry.javaClass.methods.first { it.name == "getRegisteredComposeRoots" }
      .invoke(registry) as Collection<Any>

    composeTestRule.activity.runOnUiThread {
      roots.filterIsInstance<View>().forEach { it.visibility = View.GONE }
    }
    composeTestRule.waitForIdle()
  }

  /** Robolectric trace bookkeeping grows unbounded during Compose text layout; disable it for this test. */
  private fun withRobolectricTracingDisabled(block: () -> Unit) {
    val tagsField = ShadowTrace::class.java.getDeclaredField("tags")
    tagsField.isAccessible = true
    val originalTags = tagsField.getLong(null)
    tagsField.setLong(null, 0L)
    try {
      block()
    } finally {
      tagsField.setLong(null, originalTags)
    }
  }

  private fun waitForState(
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
        val detail = debugMessage().takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for Phase 5 state$detail")
      }
      Thread.sleep(20)
    }
  }

  private fun settleAsyncWork(timeoutMs: Long = 800L) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.waitForIdle()
      Thread.sleep(20)
    }
  }

  private fun waitForFreshQuote(quoteVm: CheckoutQuoteViewModel) {
    waitForState(debugMessage = { "quoteState=${quoteVm.uiState.value}" }) { quoteVm.uiState.value.isQuoteFresh }
  }

  private fun cartItem(
    id: String = "cart-1",
    productId: String = "prod-p5",
    quantity: Int = 1,
    unitPricePaise: Long = 50_000L,
    isAvailable: Boolean = true,
    toppingIds: List<String> = emptyList(),
  ) = CartItemWithToppings(
    item = CartItemEntity(
      id = id,
      userId = customerSession.user.id,
      productId = productId,
      productName = "Test Pizza",
      variantId = "var-p5",
      sizeCode = "MEDIUM",
      crustId = "crust-p5",
      crustName = "Hand-Tossed",
      unitPricePaise = unitPricePaise,
      quantity = quantity,
      isAvailable = isAvailable,
    ),
    toppings = toppingIds.map { CartItemToppingEntity(id, it, "Extra Cheese", 1_000L) },
  )

  private fun address(id: String, pincode: String = "560038") = AddressDto(
    id = id,
    label = if (id == "addr-home") "Home" else "Office",
    line1 = "123 Test St",
    line2 = null,
    landmark = null,
    pincode = pincode,
    city = "Bengaluru",
    isDefault = id == "addr-home",
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

  /** Deterministic fake backend shared by the quote, zone and checkout flows under test. */
  private inner class Phase5Server {
    val quoteCalls = AtomicInteger(0)
    val zoneCalls = AtomicInteger(0)
    val checkoutCalls = AtomicInteger(0)
    val quoteRequests = Collections.synchronizedList(mutableListOf<PricingQuoteRequest>())
    val checkoutRequests = Collections.synchronizedList(mutableListOf<CheckoutRequest>())
    val idempotencyKeys = Collections.synchronizedList(mutableListOf<String>())

    var failQuoteCallNumber: Int? = null
    var delayFirstQuoteMs = 0L
    var delayQuoteCallNumber: Int? = null
    var delayCheckoutCallNumber: Int? = null
    var serverUnitPricePaise = 50_000L
    var zoneMinOrderPaise = 30_000L
    var deliveryFeePaise = 4_000L
    var kotlin422Pins = setOf<String>()
    var failCheckoutWithMinOrder422 = false
    var checkoutMinOrderPaise: Long? = null
    var checkoutTotalOverride: Long? = null
    var acceptOrders = true
    var addresses: List<AddressDto> = listOf(address("addr-home"))

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    fun MockRequestHandleScope.errorResponse(
      status: HttpStatusCode,
      code: String,
      message: String,
      details: Map<String, String> = emptyMap(),
    ) = respond(
      content = json.encodeToString(
        ApiErrorEnvelope(
          error = ApiErrorDetail(code, message, details),
          correlationId = "p5-correlation",
          timestamp = "2026-11-22T10:00:00Z",
        ),
      ),
      status = status,
      headers = jsonHeaders,
    )

    fun MockRequestHandleScope.quoteResponse(req: PricingQuoteRequest) = run {
      val pin = req.pincode
      if (pin != null && pin in kotlin422Pins) {
        return@run errorResponse(
          status = HttpStatusCode.UnprocessableEntity,
          code = "DELIVERY_MIN_ORDER_NOT_MET",
          message = "Minimum order not met for $pin.",
          details = mapOf("min_order_paise" to zoneMinOrderPaise.toString()),
        )
      }
      if (req.couponCode == "BAD_COUPON") {
        return@run errorResponse(
          status = HttpStatusCode.UnprocessableEntity,
          code = "COUPON_INVALID",
          message = "Coupon code BAD_COUPON is invalid.",
        )
      }

      val fee = if (req.fulfillmentType == FulfillmentType.DELIVERY) deliveryFeePaise else 0L
      val coupon = if (req.couponCode == "GOOD_COUPON") {
        CouponCalculationSpec(DiscountType.PERCENT_BPS, 1_000L)
      } else null
      val breakdown = PricingEngine.calculateQuote(
        req.items.map { PricedLineInput(serverUnitPricePaise, it.quantity, isTaxable = true) },
        500,
        fee,
        coupon,
      )
      val response = PricingQuoteResponse(
        items = req.items.map { item ->
          CartItemViewDto(
            id = item.id ?: "cart-line",
            productId = item.productId,
            productName = "Test Pizza",
            isVeg = true,
            variantId = item.variantId,
            sizeCode = SizeCode.MEDIUM,
            basePricePaise = serverUnitPricePaise,
            crustId = item.crustId,
            crustName = "Hand-Tossed",
            crustExtraPricePaise = 0L,
            toppings = item.toppingIds.map { CartToppingViewDto(it, "Extra Cheese", true, 1_000L, true) },
            quantity = item.quantity,
            unitPricePaise = serverUnitPricePaise,
            lineTotalPaise = serverUnitPricePaise * item.quantity,
            isAvailable = true,
            issues = emptyList(),
          )
        },
        fulfillmentType = req.fulfillmentType,
        breakdown = breakdown,
        appliedCoupon = if (req.couponCode == "GOOD_COUPON") {
          AppliedCouponSummaryDto(
            id = "coupon-1",
            code = "GOOD_COUPON",
            discountType = DiscountType.PERCENT_BPS,
            discountValue = 1_000L,
            discountPaise = breakdown.discountPaise,
          )
        } else null,
        deliveryZone = if (req.fulfillmentType == FulfillmentType.DELIVERY && pin != null) {
          DeliveryZoneQuoteDto(pin, "Test Area", zoneMinOrderPaise, fee, 30)
        } else null,
        isAcceptingOrders = acceptOrders,
        gstBps = 500,
      )
      respond(content = json.encodeToString(response), status = HttpStatusCode.OK, headers = jsonHeaders)
    }

    fun MockRequestHandleScope.checkoutResponse(req: CheckoutRequest) = run {
      if (failCheckoutWithMinOrder422) {
        return@run errorResponse(
          status = HttpStatusCode.UnprocessableEntity,
          code = "DELIVERY_MIN_ORDER_NOT_MET",
          message = "Minimum order not met.",
          details = mapOf("min_order_paise" to (checkoutMinOrderPaise ?: zoneMinOrderPaise).toString()),
        )
      }
      val fee = if (req.fulfillmentType == FulfillmentType.DELIVERY) deliveryFeePaise else 0L
      val breakdown = PricingEngine.calculateQuote(
        req.items.map { PricedLineInput(serverUnitPricePaise, it.quantity, isTaxable = true) },
        500,
        fee,
        null,
      )
      val total = checkoutTotalOverride ?: breakdown.totalPaise
      val isRazorpay = req.paymentMethod == PaymentMethod.RAZORPAY
      val order = OrderDetailDto(
        id = "ord-p5-${checkoutCalls.get()}",
        orderNumber = "PZ-P5-${checkoutCalls.get()}",
        customerId = customerSession.user.id,
        fulfillmentType = req.fulfillmentType,
        scheduleType = req.scheduleType,
        scheduledSlotId = req.scheduledSlotId,
        deliveryAddress = req.addressId?.let {
          DeliveryAddressSnapshotDto(
            addressId = it,
            label = "Home",
            line1 = "123 Test St",
            pincode = "560038",
            city = "Bengaluru",
          )
        },
        status = if (isRazorpay) OrderStatus.PENDING_PAYMENT else OrderStatus.PLACED,
        version = 1L,
        subtotalPaise = breakdown.subtotalPaise,
        discountPaise = breakdown.discountPaise,
        deliveryFeePaise = breakdown.deliveryFeePaise,
        taxPaise = breakdown.taxPaise,
        cgstPaise = breakdown.cgstPaise,
        sgstPaise = breakdown.sgstPaise,
        totalPaise = total,
        gstBpsSnapshot = 500,
        couponCodeSnapshot = req.couponCode,
        isPriority = false,
        createdAt = "2026-11-22T10:00:00Z",
        items = req.items.map { item ->
          OrderItemDto(
            id = "oi-${item.id}",
            productId = item.productId,
            productName = "Test Pizza",
            variantSize = "MEDIUM",
            crustName = "Hand-Tossed",
            toppings = item.toppingIds,
            unitPricePaise = serverUnitPricePaise,
            quantity = item.quantity,
            lineTotalPaise = serverUnitPricePaise * item.quantity,
          )
        },
        statusHistory = listOf(
          OrderStatusHistoryDto(
            id = "osh-1",
            fromStatus = null,
            toStatus = OrderStatus.PLACED,
            changedBy = customerSession.user.id,
            reason = "Order placed",
            createdAt = "2026-11-22T10:00:00Z",
          ),
        ),
        payment = PaymentSummaryDto(
          id = "pay-p5-${checkoutCalls.get()}",
          method = req.paymentMethod,
          status = if (isRazorpay) PaymentStatus.UNPAID else PaymentStatus.COD_PENDING,
          amountPaise = total,
          capturedPaise = 0L,
          refundedPaise = 0L,
          latestAttempt = if (isRazorpay) {
            PaymentAttemptSummaryDto(
              id = "attempt-1",
              attemptNumber = 1,
              method = PaymentMethod.RAZORPAY,
              status = PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT,
              amountPaise = total,
              idempotencyReceipt = "receipt-p5-1",
              gatewayOrderId = "order_gw_p5",
              createdAt = "2026-11-22T10:00:00Z",
            )
          } else null,
        ),
      )
      respond(content = json.encodeToString(CheckoutResponse(order, false)), status = HttpStatusCode.Created, headers = jsonHeaders)
    }

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      when {
        path == "/api/v1/pricing/quote" && request.method == HttpMethod.Post -> {
          val callNumber = quoteCalls.incrementAndGet()
          val req = json.decodeFromString<PricingQuoteRequest>(bodyAsString(request.body))
          quoteRequests.add(req)
          if (callNumber == 1 && delayFirstQuoteMs > 0L) delay(delayFirstQuoteMs)
          if (callNumber == delayQuoteCallNumber) delay(250L)
          if (failQuoteCallNumber == callNumber) {
            throw IOException("Simulated network failure on quote #$callNumber")
          }
          quoteResponse(req)
        }
        path == "/api/v1/delivery-zones/check" && request.method == HttpMethod.Get -> {
          zoneCalls.incrementAndGet()
          val pin = request.url.parameters["pincode"].orEmpty()
          respond(
            content = json.encodeToString(
              DeliveryZoneCheckResponse(
                pincode = pin,
                isServiceable = true,
                zone = DeliveryZoneDto("zone-p5", pin, "Test Area", zoneMinOrderPaise, deliveryFeePaise, 30, true),
                message = "Delivers to $pin.",
              ),
            ),
            status = HttpStatusCode.OK,
            headers = jsonHeaders,
          )
        }
        path == "/api/v1/me/addresses" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(addresses),
          status = HttpStatusCode.OK,
          headers = jsonHeaders,
        )
        path == "/api/v1/coupons/available" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(AvailableCouponsResponse(emptyList())),
          status = HttpStatusCode.OK,
          headers = jsonHeaders,
        )
        path == "/api/v1/slots" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(
            AvailableSlotsResponse(
              date = request.url.parameters["date"].orEmpty().ifBlank { "2026-11-23" },
              fulfillmentType = FulfillmentType.DELIVERY,
              timezone = "Asia/Kolkata",
              serverNow = "2026-11-23T10:00:00Z",
              slots = emptyList(),
            ),
          ),
          status = HttpStatusCode.OK,
          headers = jsonHeaders,
        )
        path == "/api/v1/checkout" && request.method == HttpMethod.Post -> {
          val checkoutCallNumber = checkoutCalls.incrementAndGet()
          if (checkoutCallNumber == delayCheckoutCallNumber) delay(250L)
          val req = json.decodeFromString<CheckoutRequest>(bodyAsString(request.body))
          checkoutRequests.add(req)
          request.headers["Idempotency-Key"]?.let { idempotencyKeys.add(it) }
          checkoutResponse(req)
        }
        else -> error("Unmocked endpoint: ${request.method.value} $path")
      }
    }

    val apiClient = PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(customerSession), engine)

    fun quoteViewModel(): CheckoutQuoteViewModel = CheckoutQuoteViewModel(NetworkPricingRepository(apiClient))

    fun checkoutViewModel(): CheckoutViewModel = CheckoutViewModel(NetworkOrderRepository(apiClient))
  }

  private fun seedCart(quoteVm: CheckoutQuoteViewModel, item: CartItemWithToppings = cartItem()) {
    quoteVm.onCartChanged(listOf(item), item.item.unitPricePaise * item.item.quantity)
  }

  private fun deliveryCheckoutVm(server: Phase5Server): CheckoutViewModel {
    val checkoutVm = server.checkoutViewModel()
    checkoutVm.loadAddresses()
    waitForState { checkoutVm.uiState.value.addresses.isNotEmpty() && !checkoutVm.uiState.value.isLoadingAddresses }
    return checkoutVm
  }

  private fun renderCheckout(
    quoteState: CheckoutQuoteUiState,
    checkoutVm: CheckoutViewModel,
    cartSubtotalPaise: Long = 50_000L,
  ) {
    composeTestRule.setContent {
      PizzoraTheme {
        CheckoutScreen(
          uiState = checkoutVm.uiState.value.copy(
            addresses = checkoutVm.uiState.value.addresses.ifEmpty { listOf(address("addr-home")) },
            selectedAddressId = checkoutVm.uiState.value.selectedAddressId ?: "addr-home",
          ),
          quoteState = quoteState,
          cartItems = listOf(cartItem()),
          cartSubtotalPaise = cartSubtotalPaise,
          onFulfillmentTypeChange = {},
          onSelectAddress = {},
          onOpenAddAddress = {},
          onDismissAddAddress = {},
          onSaveAddress = { _, _, _, _ -> },
          onSelectPaymentMethod = {},
          onPlaceOrder = {},
          onBackToCart = {},
        )
      }
    }
    composeTestRule.waitForIdle()
  }

  @Test
  fun initialQuoteLoadProducesFreshAuthoritativeTotal() {
    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)

    val state = quoteVm.uiState.value
    assertNotNull(state.quote)
    assertEquals(50_000L, state.quote?.breakdown?.subtotalPaise)
    assertEquals(56_500L, state.quote?.breakdown?.totalPaise)
    assertEquals("560038", state.quote?.deliveryZone?.pincode)
    assertEquals("560038|", state.quotedSignature?.deliveryContext)
    assertEquals(FulfillmentType.DELIVERY, state.quotedSignature?.fulfillmentType)
    assertEquals(1, state.quotedSignature?.itemsConfig?.first()?.quantity)
    assertEquals("prod-p5", state.quotedSignature?.itemsConfig?.first()?.productId)
    quoteVm.clearSessionState()
  }

  @Test
  fun cartQuantityChangeInvalidatesQuoteAndTracksSignature() {
    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    val first = cartItem(quantity = 1)
    seedCart(quoteVm, first)
    waitForFreshQuote(quoteVm)
    val firstSignature = quoteVm.uiState.value.quotedSignature

    server.delayQuoteCallNumber = server.quoteCalls.get() + 1
    quoteVm.onCartChanged(listOf(first.copy(item = first.item.copy(quantity = 2))), 100_000L)
    assertFalse(quoteVm.uiState.value.isQuoteFresh)
    assertTrue(quoteVm.uiState.value.isQuoteStale)
    assertEquals(firstSignature, quoteVm.uiState.value.quotedSignature)
    waitForFreshQuote(quoteVm)

    val secondState = quoteVm.uiState.value
    assertEquals(2, secondState.quotedSignature?.itemsConfig?.first()?.quantity)
    assertEquals(100_000L, secondState.quote?.breakdown?.subtotalPaise)
    assertNotEquals(firstSignature, secondState.quotedSignature)
    quoteVm.clearSessionState()
  }

  @Test
  fun cartItemEditAndRemovalInvalidateQuoteAndEmptyCartClearsQuote() {
    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    val original = cartItem(toppingIds = listOf("top-old"))
    seedCart(quoteVm, original)
    waitForFreshQuote(quoteVm)
    val originalSignature = quoteVm.uiState.value.quotedSignature

    val edited = original.copy(
      item = original.item.copy(variantId = "var-large", productId = "prod-large"),
      toppings = listOf(CartItemToppingEntity(original.item.id, "top-new", "Olive", 2_000L)),
    )
    server.delayQuoteCallNumber = server.quoteCalls.get() + 1
    quoteVm.onCartChanged(listOf(edited), 52_000L)
    assertFalse(quoteVm.uiState.value.isQuoteFresh)
    waitForFreshQuote(quoteVm)

    val editedSignature = quoteVm.uiState.value.quotedSignature
    assertNotEquals(originalSignature, editedSignature)
    assertEquals(listOf("top-new"), editedSignature?.itemsConfig?.first()?.toppingIds)

    quoteVm.onCartChanged(emptyList(), 0L)
    assertNull(quoteVm.uiState.value.quote)
    assertNull(quoteVm.uiState.value.quotedSignature)
    assertNull(quoteVm.uiState.value.currentSignature)
    assertFalse(quoteVm.uiState.value.isQuoteFresh)
    quoteVm.clearSessionState()
  }

  @Test
  fun couponApplyAndRemoveRepriceWithoutStaleTotal() {
    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    waitForFreshQuote(quoteVm)
    val noCouponTotal = quoteVm.uiState.value.quote?.breakdown?.totalPaise

    server.delayQuoteCallNumber = 2
    quoteVm.applyCouponCode("good_coupon")
    assertFalse(quoteVm.uiState.value.isQuoteFresh)
    waitForFreshQuote(quoteVm)

    val discounted = quoteVm.uiState.value
    assertEquals("GOOD_COUPON", discounted.appliedCouponCode)
    assertEquals("GOOD_COUPON", discounted.quotedSignature?.normalizedCouponCode)
    assertTrue(discounted.quote!!.breakdown.discountPaise > 0L)
    assertTrue(discounted.quote!!.breakdown.totalPaise < noCouponTotal!!)

    server.delayQuoteCallNumber = 3
    quoteVm.removeCoupon()
    assertFalse(quoteVm.uiState.value.isQuoteFresh)
    waitForFreshQuote(quoteVm)

    val afterRemoval = quoteVm.uiState.value
    assertNull(afterRemoval.appliedCouponCode)
    assertNull(afterRemoval.quotedSignature?.normalizedCouponCode)
    assertEquals(0L, afterRemoval.quote?.breakdown?.discountPaise)
    assertEquals(noCouponTotal, afterRemoval.quote?.breakdown?.totalPaise)
    quoteVm.clearSessionState()
  }

  @Test
  fun couponRejectionFallsBackToUncouponedQuoteUnderNewGeneration() {
    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.applyCouponCode("BAD_COUPON")

    waitForState(debugMessage = { "state=${quoteVm.uiState.value}" }) {
      quoteVm.uiState.value.isQuoteFresh && quoteVm.uiState.value.couponError != null
    }
    val state = quoteVm.uiState.value
    assertTrue(state.couponError!!.contains("BAD_COUPON"))
    assertNull(state.appliedCouponCode)
    assertNull(state.quotedSignature?.normalizedCouponCode)
    assertNotNull(state.quote)
    assertTrue(server.quoteCalls.get() >= 2)
    assertNull(server.quoteRequests.last().couponCode)
    quoteVm.clearSessionState()
  }

  @Test
  fun deliveryPickupSwitchRepricesAndClearsZoneFee() {
    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)
    val deliveryTotal = quoteVm.uiState.value.quote?.breakdown?.totalPaise
    assertEquals(4_000L, quoteVm.uiState.value.quote?.breakdown?.deliveryFeePaise)

    server.delayQuoteCallNumber = server.quoteCalls.get() + 1
    quoteVm.onFulfillmentTypeChange(FulfillmentType.PICKUP)
    assertFalse(quoteVm.uiState.value.isQuoteFresh)
    waitForFreshQuote(quoteVm)

    val pickup = quoteVm.uiState.value
    assertEquals(FulfillmentType.PICKUP, pickup.quote?.fulfillmentType)
    assertEquals(0L, pickup.quote?.breakdown?.deliveryFeePaise)
    assertNull(pickup.quote?.deliveryZone)
    assertEquals("PICKUP", pickup.quotedSignature?.deliveryContext)
    assertTrue(pickup.quote!!.breakdown.totalPaise < deliveryTotal!!)
    assertNull(server.quoteRequests.last().pincode)

    quoteVm.onFulfillmentTypeChange(FulfillmentType.DELIVERY)
    waitForFreshQuote(quoteVm)
    assertEquals("560038|", quoteVm.uiState.value.quotedSignature?.deliveryContext)
    assertEquals(4_000L, quoteVm.uiState.value.quote?.breakdown?.deliveryFeePaise)
    quoteVm.clearSessionState()
  }

  @Test
  fun differentPincodeSwitchFiresExactlyOneQuoteAndUpdatesFee() {
    val server = Phase5Server()
    server.deliveryFeePaise = 7_000L
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)
    val firstCount = server.quoteCalls.get()
    assertEquals(7_000L, quoteVm.uiState.value.quote?.breakdown?.deliveryFeePaise)

    server.delayQuoteCallNumber = server.quoteCalls.get() + 1
    quoteVm.syncCheckoutAddressPincode("560001")
    assertFalse(quoteVm.uiState.value.isQuoteFresh)
    waitForFreshQuote(quoteVm)

    assertEquals(firstCount + 1, server.quoteCalls.get())
    assertEquals("560001", quoteVm.uiState.value.quote?.deliveryZone?.pincode)
    assertEquals("560001|", quoteVm.uiState.value.quotedSignature?.deliveryContext)
    quoteVm.clearSessionState()
  }

  @Test
  fun samePincodeAddressSwitchReusesFreshQuote() {
    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)
    val signature = quoteVm.uiState.value.quotedSignature
    val count = server.quoteCalls.get()

    quoteVm.syncCheckoutAddressPincode("560038")
    settleAsyncWork(150L)

    assertEquals(count, server.quoteCalls.get())
    assertEquals(signature, quoteVm.uiState.value.quotedSignature)
    assertTrue(quoteVm.uiState.value.isQuoteFresh)
    quoteVm.clearSessionState()
  }

  @Test
  fun duplicateInFlightQuoteRequestsAreSuppressed() {
    val server = Phase5Server()
    server.delayFirstQuoteMs = 250L
    val quoteVm = server.quoteViewModel()
    val item = cartItem()

    quoteVm.onCartChanged(listOf(item), 50_000L)
    quoteVm.onCartChanged(listOf(item), 50_000L)
    quoteVm.onCartChanged(listOf(item), 50_000L)

    waitForState { server.quoteCalls.get() == 1 }
    waitForFreshQuote(quoteVm)
    assertEquals(1, server.quoteCalls.get())

    quoteVm.onCartChanged(listOf(item), 50_000L)
    quoteVm.onCartChanged(listOf(item), 50_000L)
    settleAsyncWork(150L)

    assertEquals(1, server.quoteCalls.get())
    assertTrue(quoteVm.uiState.value.isQuoteFresh)
    quoteVm.clearSessionState()
  }

  @Test
  fun networkFailureKeepsOldQuoteMarksStaleAndRetryRecovers() {
    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)
    val oldQuote = quoteVm.uiState.value.quote
    server.failQuoteCallNumber = server.quoteCalls.get() + 1

    quoteVm.syncCheckoutAddressPincode("560001")
    waitForState(debugMessage = { "state=${quoteVm.uiState.value}" }) {
      quoteVm.uiState.value.quoteError != null && !quoteVm.uiState.value.isLoadingQuote
    }

    val failed = quoteVm.uiState.value
    assertEquals(oldQuote, failed.quote)
    assertNull(failed.quotedSignature)
    assertTrue(failed.isQuoteStale)
    assertFalse(failed.isQuoteFresh)
    assertNotNull(failed.quoteError)

    val checkoutVm = deliveryCheckoutVm(server)
    checkoutVm.placeOrder(listOf(cartItem()), FulfillmentType.DELIVERY, null, {}, failed)
    assertEquals("Price quote is not ready. Please wait for the total to update.", checkoutVm.uiState.value.checkoutError)
    assertEquals(0, server.checkoutCalls.get())

    server.failQuoteCallNumber = null
    quoteVm.refreshQuote()
    waitForFreshQuote(quoteVm)
    assertNull(quoteVm.uiState.value.quoteError)
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun lateOutOfOrderResponseIsRejectedByGenerationGuard() {
    val server = Phase5Server()
    server.delayFirstQuoteMs = 500L
    val quoteVm = server.quoteViewModel()
    val first = cartItem(quantity = 1)

    quoteVm.onCartChanged(listOf(first), 50_000L)
    waitForState { server.quoteCalls.get() == 1 }
    quoteVm.onCartChanged(listOf(first.copy(item = first.item.copy(quantity = 3))), 150_000L)
    waitForState { server.quoteCalls.get() == 2 }
    waitForFreshQuote(quoteVm)
    settleAsyncWork(600L)

    val state = quoteVm.uiState.value
    assertEquals(3, state.quotedSignature?.itemsConfig?.first()?.quantity)
    assertEquals(150_000L, state.quote?.breakdown?.subtotalPaise)
    assertEquals(2, server.quoteCalls.get())
    quoteVm.clearSessionState()
  }

  @Test
  fun cancelledRequestResponseCannotOverwriteClearedState() {
    val server = Phase5Server()
    server.delayFirstQuoteMs = 400L
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    waitForState { server.quoteCalls.get() == 1 }

    quoteVm.clearSessionState()
    settleAsyncWork(600L)

    assertNull(quoteVm.uiState.value.quote)
    assertNull(quoteVm.uiState.value.quotedSignature)
    assertNull(quoteVm.uiState.value.inFlightSignature)
    assertFalse(quoteVm.uiState.value.isQuoteFresh)
  }

  @Test
  fun localEstimateOnlyDisablesSubmissionUntilFreshQuote() {
    val server = Phase5Server()
    server.delayFirstQuoteMs = 200L
    val quoteVm = server.quoteViewModel()
    val checkoutVm = server.checkoutViewModel()
    val item = cartItem()
    quoteVm.onCartChanged(listOf(item), 50_000L)

    assertFalse(quoteVm.uiState.value.isQuoteFresh)
    checkoutVm.placeOrder(listOf(item), FulfillmentType.DELIVERY, null, {}, quoteVm.uiState.value)

    assertEquals("Price quote is not ready. Please wait for the total to update.", checkoutVm.uiState.value.checkoutError)
    assertEquals(0, server.checkoutCalls.get())
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun freshServerQuoteBelowMinimumBlocksOrderWithShortfall() {
    val server = Phase5Server()
    server.zoneMinOrderPaise = 60_000L
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)

    val state = quoteVm.uiState.value
    assertTrue(state.isQuoteFresh)
    assertEquals(50_000L, state.quote?.breakdown?.subtotalPaise)
    assertEquals(60_000L, state.quote?.deliveryZone?.minOrderPaise)

    val checkoutVm = deliveryCheckoutVm(server)
    checkoutVm.placeOrder(listOf(cartItem()), FulfillmentType.DELIVERY, null, {}, state)
    assertTrue(checkoutVm.uiState.value.checkoutError!!.contains("₹100.00 more for delivery to 560038"))
    assertEquals(0, server.checkoutCalls.get())
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun kotlin422MinimumOrderClearsQuoteAndDisablesSubmission() {
    val server = Phase5Server()
    server.kotlin422Pins = setOf("560038")
    server.zoneMinOrderPaise = 60_000L
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")

    waitForState(debugMessage = { "state=${quoteVm.uiState.value}" }) {
      quoteVm.uiState.value.quoteError != null && !quoteVm.uiState.value.isLoadingQuote
    }
    val state = quoteVm.uiState.value
    assertNull(state.quote)
    assertNull(state.quotedSignature)
    assertNull(state.inFlightSignature)
    assertFalse(state.isQuoteFresh)
    assertTrue(state.quoteError!!.contains("Add ₹100.00 more for delivery to 560038"))
    quoteVm.clearSessionState()
  }

  @Test
  fun rust200QuoteWithOrder422BlocksSubmissionAndRequestsRefresh() {
    val server = Phase5Server()
    server.failCheckoutWithMinOrder422 = true
    server.checkoutMinOrderPaise = 60_000L
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)

    var refreshRequested = false
    val checkoutVm = deliveryCheckoutVm(server)
    checkoutVm.placeOrder(
      cartItems = listOf(cartItem()),
      fulfillmentType = FulfillmentType.DELIVERY,
      couponCode = null,
      onCartCleared = {},
      quoteState = quoteVm.uiState.value,
      onQuoteRefreshNeeded = { refreshRequested = true },
    )
    waitForState { checkoutVm.uiState.value.checkoutError != null }

    assertTrue(checkoutVm.uiState.value.checkoutError!!.contains("Add ₹100.00 more for delivery to 560038"))
    assertEquals(1, server.checkoutCalls.get())
    assertFalse(checkoutVm.uiState.value.isPlacingOrder)
    assertTrue(refreshRequested)
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun staleQuoteDisablesSubmissionUntilRefreshCompletes() {
    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    val item = cartItem()
    seedCart(quoteVm)
    waitForFreshQuote(quoteVm)

    quoteVm.onCartChanged(listOf(item.copy(item = item.item.copy(quantity = 2))), 100_000L)
    val stale = quoteVm.uiState.value
    assertTrue(stale.isQuoteStale)
    assertFalse(stale.isQuoteFresh)

    val checkoutVm = deliveryCheckoutVm(server)
    checkoutVm.placeOrder(listOf(item), FulfillmentType.DELIVERY, null, {}, stale)
    assertEquals("Price quote is not ready. Please wait for the total to update.", checkoutVm.uiState.value.checkoutError)
    assertEquals(0, server.checkoutCalls.get())

    waitForFreshQuote(quoteVm)
    assertFalse(quoteVm.uiState.value.isQuoteStale)
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun priceDriftBetweenLocalCartAndQuoteSurfacesNoticeAndBanner() {
    val server = Phase5Server()
    server.serverUnitPricePaise = 55_000L
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    waitForFreshQuote(quoteVm)

    val state = quoteVm.uiState.value
    assertEquals(55_000L, state.quote?.breakdown?.subtotalPaise)
    assertNotNull(state.priceDriftNotice)
    assertTrue(state.priceDriftNotice!!.contains("Menu prices updated"))
    assertTrue(state.priceDriftNotice!!.contains(Money.formatInr(state.quote!!.breakdown.totalPaise)))

    val checkoutVm = deliveryCheckoutVm(server)
    renderCheckout(state, checkoutVm)
    composeTestRule.onNodeWithTag("checkout_price_drift_notice").assertIsDisplayed()
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun localSubtotalIsWarningOnlyAndServerQuoteAuthorizesSubmission() {
    val server = Phase5Server()
    server.serverUnitPricePaise = 70_000L
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)

    val expectedTotal = PricingEngine.calculateQuote(listOf(PricedLineInput(70_000L, 1)), 500, 4_000L).totalPaise
    assertEquals(expectedTotal, quoteVm.uiState.value.quote?.breakdown?.totalPaise)

    val checkoutVm = deliveryCheckoutVm(server)
    checkoutVm.placeOrder(listOf(cartItem()), FulfillmentType.DELIVERY, null, {}, quoteVm.uiState.value)
    waitForState { !checkoutVm.uiState.value.isPlacingOrder && server.checkoutCalls.get() == 1 }

    assertNull(checkoutVm.uiState.value.checkoutError)
    assertNull(checkoutVm.uiState.value.priceDriftNotice)
    val quotedUnitPrice = quoteVm.uiState.value.quote!!.items.first().unitPricePaise
    assertEquals(quotedUnitPrice, server.checkoutRequests.first().items.first().clientUnitPricePaise)
    assertEquals(expectedTotal, server.checkoutRequests.first().let {
      PricingEngine.calculateQuote(
        listOf(PricedLineInput(quotedUnitPrice, it.items.first().quantity)),
        500,
        4_000L,
      ).totalPaise
    })
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun checkoutOrderTotalDriftFromQuoteSurfacesNotice() {
    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)
    val quotedTotal = quoteVm.uiState.value.quote?.breakdown?.totalPaise
    assertNotEquals(61_000L, quotedTotal)

    server.checkoutTotalOverride = 61_000L
    val checkoutVm = deliveryCheckoutVm(server)
    checkoutVm.placeOrder(listOf(cartItem()), FulfillmentType.DELIVERY, null, {}, quoteVm.uiState.value)
    waitForState { !checkoutVm.uiState.value.isPlacingOrder && server.checkoutCalls.get() == 1 }

    assertNotNull(checkoutVm.uiState.value.priceDriftNotice)
    assertTrue(checkoutVm.uiState.value.priceDriftNotice!!.contains(Money.formatInr(61_000L)))
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun duplicateCheckoutSubmissionSendsExactlyOneOrderRequest() {
    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)

    val checkoutVm = deliveryCheckoutVm(server)
    val quoteState = quoteVm.uiState.value
    // Keep the first request in flight so all three taps exercise the synchronous guard.
    server.delayCheckoutCallNumber = 1
    checkoutVm.placeOrder(listOf(cartItem()), FulfillmentType.DELIVERY, null, {}, quoteState)
    checkoutVm.placeOrder(listOf(cartItem()), FulfillmentType.DELIVERY, null, {}, quoteState)
    checkoutVm.placeOrder(listOf(cartItem()), FulfillmentType.DELIVERY, null, {}, quoteState)
    waitForState(debugMessage = { "checkoutState=${checkoutVm.uiState.value}; calls=${server.checkoutCalls.get()}" }) {
      !checkoutVm.uiState.value.isPlacingOrder && server.checkoutCalls.get() == 1
    }

    assertEquals(1, server.checkoutCalls.get())
    assertEquals(1, server.idempotencyKeys.size)
    assertNull(checkoutVm.uiState.value.checkoutError)
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun serverAuthoritativeAmountIsUsedForRazorpayOrder() {
    val server = Phase5Server()
    server.serverUnitPricePaise = 80_000L
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)

    val expectedTotal = PricingEngine.calculateQuote(listOf(PricedLineInput(80_000L, 1)), 500, 4_000L).totalPaise
    assertEquals(expectedTotal, quoteVm.uiState.value.quote?.breakdown?.totalPaise)

    val checkoutVm = deliveryCheckoutVm(server)
    checkoutVm.selectPaymentMethod(PaymentMethod.RAZORPAY)
    val capturedOrders = Collections.synchronizedList(mutableListOf<OrderDetailDto>())
    val collector = CoroutineScope(Dispatchers.Default).launch {
      checkoutVm.orderCreatedEvents.collect { capturedOrders.add(it) }
    }

    checkoutVm.placeOrder(
      cartItems = listOf(cartItem()),
      fulfillmentType = FulfillmentType.DELIVERY,
      couponCode = "GOOD_COUPON",
      onCartCleared = {},
      quoteState = quoteVm.uiState.value,
    )
    waitForState { !checkoutVm.uiState.value.isPlacingOrder && capturedOrders.isNotEmpty() }

    val request = server.checkoutRequests.first()
    assertEquals(PaymentMethod.RAZORPAY, request.paymentMethod)
    assertEquals("GOOD_COUPON", request.couponCode)

    val order = capturedOrders.first()
    assertEquals(OrderStatus.PENDING_PAYMENT, order.status)
    assertEquals(expectedTotal, order.totalPaise)
    assertEquals(expectedTotal, order.payment.amountPaise)
    assertEquals(expectedTotal, order.payment.latestAttempt?.amountPaise)
    assertNull(checkoutVm.uiState.value.priceDriftNotice)
    collector.cancel()
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun unavailableCartItemBlocksSubmission() {
    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    waitForFreshQuote(quoteVm)

    val unavailable = cartItem(isAvailable = false)
    quoteVm.onCartChanged(listOf(unavailable), 50_000L)
    assertTrue(quoteVm.uiState.value.hasUnavailableItems)
    assertFalse(quoteVm.uiState.value.isQuoteFresh)

    val checkoutVm = deliveryCheckoutVm(server)
    checkoutVm.placeOrder(listOf(unavailable), FulfillmentType.DELIVERY, null, {}, quoteVm.uiState.value)
    assertEquals("Price quote is not ready. Please wait for the total to update.", checkoutVm.uiState.value.checkoutError)
    assertEquals(0, server.checkoutCalls.get())
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun scheduledModeWithoutSlotBlocksSubmission() {
    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)

    val checkoutVm = deliveryCheckoutVm(server)
    checkoutVm.onScheduleTypeChange(ScheduleType.SCHEDULED, FulfillmentType.DELIVERY)
    checkoutVm.placeOrder(listOf(cartItem()), FulfillmentType.DELIVERY, null, {}, quoteVm.uiState.value)

    assertTrue(checkoutVm.uiState.value.checkoutError!!.contains("time slot"))
    assertEquals(0, server.checkoutCalls.get())
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun storePausedBlocksFreshQuoteSubmission() {
    val server = Phase5Server()
    server.acceptOrders = false
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)
    assertFalse(quoteVm.uiState.value.quote!!.isAcceptingOrders)

    val checkoutVm = deliveryCheckoutVm(server)
    checkoutVm.placeOrder(listOf(cartItem()), FulfillmentType.DELIVERY, null, {}, quoteVm.uiState.value)

    assertEquals("Store is not accepting orders right now.", checkoutVm.uiState.value.checkoutError)
    assertEquals(0, server.checkoutCalls.get())
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun istDateIsoIsApi24SafeAndUsesKolkataCalendar() {
    val checkoutVmSource = sourceFile("app/src/main/java/com/example/checkout/CheckoutViewModel.kt").readText()
    val checkoutScreenSource = sourceFile("app/src/main/java/com/example/checkout/CheckoutScreen.kt").readText()
    assertFalse("CheckoutViewModel.kt must not use java.time", checkoutVmSource.contains("java.time"))
    assertFalse("CheckoutScreen.kt must not use java.time", checkoutScreenSource.contains("java.time"))

    val fixedNow = 1_700_000_000_000L
    val ist = TimeZone.getTimeZone("Asia/Kolkata")
    val expectedFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply { timeZone = ist }
    assertEquals(expectedFormat.format(java.util.Date(fixedNow)), OrderDisplayFormatters.istDateIso(0, fixedNow))
    assertEquals(
      expectedFormat.format(java.util.Date(fixedNow + 86_400_000L)),
      OrderDisplayFormatters.istDateIso(1, fixedNow),
    )
    assertTrue(OrderDisplayFormatters.istDateIso(7, fixedNow).matches("""\d{4}-\d{2}-\d{2}""".toRegex()))
  }

  @Test
  fun addAddressDialogRetainsTypedValuesAcrossRotation() {
    withRobolectricTracingDisabled {
      val restoreKey = mutableStateOf("before-restore")

      composeTestRule.activity.setContent {
        val holder = rememberSaveableStateHolder()
        PizzoraTheme {
          Box(Modifier.fillMaxSize()) {
            holder.SaveableStateProvider(restoreKey.value) {
              AddAddressDialog(
                isSaving = false,
                error = null,
                onSave = { _, _, _, _, _, _, _ -> },
                onDismiss = {},
              )
            }
          }
        }
      }

      ignorePendingDialogLayout()

      enterText("address_line1_input", "12 MG Road")
      enterText("address_pincode_input", "560001")
      enterText("address_city_input", "Mysuru")

      // Disposing the provider first lets the holder save the field state, then composing with
      // the same key again exercises the rememberSaveable restore contract of Activity rotation.
      composeTestRule.activity.runOnUiThread { restoreKey.value = "disposed" }
      ignorePendingDialogLayout()
      composeTestRule.activity.runOnUiThread { restoreKey.value = "before-restore" }
      ignorePendingDialogLayout()

      composeTestRule.onNodeWithTag("address_line1_input").assertTextContains("12 MG Road", substring = true)
      composeTestRule.onNodeWithTag("address_pincode_input").assertTextContains("560001", substring = true)
      composeTestRule.onNodeWithTag("address_city_input").assertTextContains("Mysuru", substring = true)
    }
  }

  @Test
  fun checkoutScreenPreservesRequiredTestTags() {
    val screenSource = sourceFile("app/src/main/java/com/example/checkout/CheckoutScreen.kt").readText()
    listOf(
      "checkout_screen",
      "checkout_readiness_notice",
      "checkout_error_banner",
      "checkout_quote_error_banner",
      "checkout_retry_quote_button",
      "checkout_price_drift_notice",
      "checkout_delivery_chip",
      "checkout_pickup_chip",
      "add_address_button",
      "check_selected_address_zone_button",
      "checkout_order_review_card",
      "checkout_order_review_toggle",
      "checkout_updating_total_text",
      "address_dialog_error",
      "address_label_input",
      "address_line1_input",
      "address_line2_input",
      "address_landmark_input",
      "address_pincode_input",
      "address_city_input",
      "address_default_switch",
      "save_address_button",
      "cancel_address_dialog_button",
      "checkout_est_subtotal_text",
      "checkout_payable_total_text",
      "place_order_button",
    ).forEach { tag -> assertTrue("Missing test tag: $tag", screenSource.contains("\"$tag\"")) }

    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)
    val checkoutVm = deliveryCheckoutVm(server)
    renderCheckout(quoteVm.uiState.value, checkoutVm)

    composeTestRule.onNodeWithTag("checkout_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("checkout_lazy_column").assertIsDisplayed()
    composeTestRule.onNodeWithTag("checkout_delivery_chip").assertIsDisplayed()
    composeTestRule.onNodeWithTag("checkout_pickup_chip").assertIsDisplayed()
    composeTestRule.onNodeWithTag("place_order_button").assertIsDisplayed()
    composeTestRule.onAllNodesWithTag("checkout_payable_total_text").fetchSemanticsNodes().isNotEmpty()
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun checkoutReadinessNoticeAndRetryTagAppearAfterQuoteFailure() {
    val server = Phase5Server()
    server.failQuoteCallNumber = 1
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    waitForState { quoteVm.uiState.value.quoteError != null }

    val checkoutVm = server.checkoutViewModel()
    renderCheckout(quoteVm.uiState.value, checkoutVm)

    composeTestRule.onNodeWithTag("checkout_quote_error_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("checkout_retry_quote_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("checkout_readiness_notice").assertIsDisplayed()
    assertFalse(composeTestRule.onAllNodesWithTag("checkout_payable_total_text").fetchSemanticsNodes().isNotEmpty())
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun samePincodeAddressChangeRotatesCheckoutIdempotencyKey() {
    val server = Phase5Server()
    server.addresses = listOf(address("addr-home"), address("addr-office"))
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    quoteVm.syncCheckoutAddressPincode("560038")
    waitForFreshQuote(quoteVm)
    val quoteCount = server.quoteCalls.get()

    val checkoutVm = deliveryCheckoutVm(server)
    checkoutVm.placeOrder(listOf(cartItem()), FulfillmentType.DELIVERY, null, {}, quoteVm.uiState.value)
    waitForState { server.checkoutCalls.get() == 1 && !checkoutVm.uiState.value.isPlacingOrder }

    checkoutVm.selectAddress("addr-office")
    checkoutVm.placeOrder(listOf(cartItem()), FulfillmentType.DELIVERY, null, {}, quoteVm.uiState.value)
    waitForState { server.checkoutCalls.get() == 2 && !checkoutVm.uiState.value.isPlacingOrder }

    assertEquals(2, server.idempotencyKeys.size)
    assertNotEquals(server.idempotencyKeys[0], server.idempotencyKeys[1])
    assertEquals(quoteCount, server.quoteCalls.get())
    assertEquals("addr-office", server.checkoutRequests.last().addressId)
    quoteVm.clearSessionState()
    checkoutVm.clearSessionState()
  }

  @Test
  fun identicalCartChangeReusesFreshQuoteWithoutNewRequest() {
    val server = Phase5Server()
    val quoteVm = server.quoteViewModel()
    seedCart(quoteVm)
    waitForFreshQuote(quoteVm)
    val signature = quoteVm.uiState.value.quotedSignature
    val count = server.quoteCalls.get()

    quoteVm.onCartChanged(listOf(cartItem()), 50_000L)
    settleAsyncWork(150L)

    assertEquals(signature, quoteVm.uiState.value.quotedSignature)
    assertTrue(quoteVm.uiState.value.isQuoteFresh)
    assertEquals(count, server.quoteCalls.get())
    quoteVm.clearSessionState()
  }
}

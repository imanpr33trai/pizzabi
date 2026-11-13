package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
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
import com.example.checkout.NetworkPricingRepository
import com.example.core.database.PizzoraDatabase
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.navigation.PizzoraApp
import com.pizzora.contracts.ApiErrorDetail
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AppliedCouponSummaryDto
import com.pizzora.contracts.AvailableCouponsResponse
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CartToppingViewDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CouponCalculationSpec
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.CustomerCouponDto
import com.pizzora.contracts.DeliveryZoneQuoteDto
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FulfillmentType
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase8PricingAndCouponsRobolectricTest {

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
    accessToken = "cust-jwt",
    refreshToken = "cust-ref",
    user = UserProfileDto(
      id = "user-p8",
      email = "custp8@pizzora.test",
      emailVerified = true,
      fullName = "Marco Polo",
      phone = "9811111188",
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
  fun `customer opens offers sheet, sees eligible and ineligible coupons, applies coupon, and verifies GST breakdown`() {
    val itemId = "cart-item-p8"
    val cartViewItem = CartItemViewDto(
      id = itemId,
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
              cartId = "server-cart-p8",
              items = listOf(cartViewItem),
              subtotalPaise = 50_000L,
              totalItemCount = 1,
              allAvailable = true,
              unavailableCount = 0,
              updatedAt = "2026-11-13T14:00:00Z",
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/coupons/available" -> respond(
          content = json.encodeToString(
            AvailableCouponsResponse(
              subtotalPaise = 50_000L,
              coupons = listOf(
                CustomerCouponDto(
                  id = "cpn-1",
                  code = "PIZZA20",
                  discountType = DiscountType.PERCENT_BPS,
                  discountValue = 2000L,
                  maxDiscountPaise = 10_000L,
                  minOrderPaise = 40_000L,
                  maxUses = 100,
                  maxUsesPerUser = 2,
                  userUsageCount = 0,
                  remainingGlobalUses = 95,
                  remainingUserUses = 2,
                  startsAt = "2026-11-13T00:00:00Z",
                  endsAt = "2026-11-20T00:00:00Z",
                  isEligible = true,
                  ineligibleReason = null,
                  estimatedDiscountPaise = 10_000L,
                ),
                CustomerCouponDto(
                  id = "cpn-2",
                  code = "FEAST1000",
                  discountType = DiscountType.FLAT_PAISE,
                  discountValue = 20_000L,
                  maxDiscountPaise = null,
                  minOrderPaise = 100_000L,
                  maxUses = null,
                  maxUsesPerUser = 1,
                  userUsageCount = 0,
                  remainingGlobalUses = null,
                  remainingUserUses = 1,
                  startsAt = "2026-11-13T00:00:00Z",
                  endsAt = "2026-11-20T00:00:00Z",
                  isEligible = false,
                  ineligibleReason = "Add ₹500.00 more (min order ₹1000.00).",
                  estimatedDiscountPaise = 0L,
                ),
              ),
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/pricing/quote" && request.method == HttpMethod.Post -> {
          val bodyText = when (val body = request.body) {
            is TextContent -> body.text
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            else -> error("Unexpected body")
          }
          val quoteReq = json.decodeFromString<PricingQuoteRequest>(bodyText)
          if (quoteReq.couponCode == "BADCODE") {
            respond(
              content = json.encodeToString(
                ApiErrorEnvelope(
                  error = ApiErrorDetail("COUPON_NOT_FOUND", "Coupon code 'BADCODE' was not found."),
                  correlationId = "corr-1",
                  timestamp = "2026-11-13T14:01:00Z",
                ),
              ),
              status = HttpStatusCode.UnprocessableEntity,
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
          } else {
            val couponSpec = if (quoteReq.couponCode == "PIZZA20") {
              CouponCalculationSpec(DiscountType.PERCENT_BPS, 2000L, 10_000L, 40_000L, true)
            } else {
              null
            }
            val zone = if (quoteReq.fulfillmentType == FulfillmentType.DELIVERY && quoteReq.pincode == "560001") {
              DeliveryZoneQuoteDto("560001", "MG Road", 30_000L, 4_000L, 30)
            } else {
              null
            }
            val deliveryFee = zone?.deliveryFeePaise ?: 0L
            val breakdown = PricingEngine.calculateQuote(
              lines = listOf(PricedLineInput(50_000L, 1, isTaxable = true)),
              gstBps = 500,
              deliveryFeePaise = deliveryFee,
              coupon = couponSpec,
            )
            respond(
              content = json.encodeToString(
                PricingQuoteResponse(
                  items = listOf(cartViewItem),
                  fulfillmentType = quoteReq.fulfillmentType,
                  breakdown = breakdown,
                  appliedCoupon = if (quoteReq.couponCode == "PIZZA20") {
                    AppliedCouponSummaryDto("cpn-1", "PIZZA20", DiscountType.PERCENT_BPS, 2000L, breakdown.discountPaise)
                  } else {
                    null
                  },
                  deliveryZone = zone,
                  isAcceptingOrders = true,
                  gstBps = 500,
                ),
              ),
              status = HttpStatusCode.OK,
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
          }
        }
        else -> error("Unexpected ${request.method.value} $path")
      }
    }

    val apiClient = PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(customerSession), engine)
    val authVm = AuthViewModel(SignedInAuthRepository(customerSession))
    val catalogVm = CustomerCatalogViewModel(NetworkCatalogRepository(apiClient, db.catalogCacheDao()))
    val cartVm = CartViewModel(NetworkCartRepository(apiClient, db.cartDao()))
    val quoteVm = CheckoutQuoteViewModel(NetworkPricingRepository(apiClient))

    composeTestRule.setContent {
      PizzoraApp(
        authViewModel = authVm,
        catalogViewModel = catalogVm,
        cartViewModel = cartVm,
        checkoutQuoteViewModel = quoteVm,
      )
    }

    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("cart_tab_badge", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    waitUntilMainLooper {
      quoteVm.uiState.value.quote != null && !quoteVm.uiState.value.isLoadingQuote
    }

    // Check pincode 560001 -> Delivery Fee ₹40.00, Subtotal ₹500.00, CGST ₹12.50, SGST ₹12.50, Total ₹565.00
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("delivery_pincode_input"))
    composeTestRule.onNodeWithTag("delivery_pincode_input").performTextInput("560001")
    composeTestRule.onNodeWithTag("check_pincode_button").performClick()
    waitUntilMainLooper {
      quoteVm.uiState.value.quote?.deliveryZone?.pincode == "560001"
    }

    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("price_breakdown_card"))
    composeTestRule.onNodeWithTag("breakdown_subtotal_text").assertTextContains("₹500.00")
    composeTestRule.onNodeWithTag("breakdown_delivery_fee_text").assertTextContains("₹40.00")
    composeTestRule.onNodeWithTag("breakdown_cgst_text").assertTextContains("₹12.50")
    composeTestRule.onNodeWithTag("breakdown_sgst_text").assertTextContains("₹12.50")
    composeTestRule.onNodeWithTag("breakdown_total_text").assertTextContains("₹565.00")

    // Entering invalid coupon code shows inline coupon error without dropping quote
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("coupon_code_input"))
    composeTestRule.onNodeWithTag("coupon_code_input").performTextInput("BADCODE")
    composeTestRule.onNodeWithTag("apply_coupon_button").performClick()
    waitUntilMainLooper {
      quoteVm.uiState.value.couponError != null
    }
    composeTestRule.onNodeWithTag("coupon_error_text").assertTextContains("Coupon code 'BADCODE' was not found.")
    assertNull(quoteVm.uiState.value.appliedCouponCode)

    // Open Coupon Picker sheet -> verify FEAST1000 is disabled & PIZZA20 can be applied
    composeTestRule.onNodeWithTag("open_coupon_picker_button").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("coupon_card_PIZZA20").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("apply_coupon_btn_FEAST1000").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("apply_coupon_btn_PIZZA20").performClick()
    waitUntilMainLooper {
      quoteVm.uiState.value.appliedCouponCode == "PIZZA20" && !quoteVm.uiState.value.isLoadingQuote
    }

    // With PIZZA20 (-₹100.00): verify applied coupon chip first while promo_coupon_card is in view
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("applied_coupon_banner"))
    composeTestRule.onNodeWithTag("applied_coupon_code_text").assertTextContains("PIZZA20")

    // Now scroll to breakdown card: taxable = ₹400.00 -> CGST ₹10.00 + SGST ₹10.00 + Delivery ₹40.00 -> Total ₹460.00
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("price_breakdown_card"))
    composeTestRule.onNodeWithTag("breakdown_discount_text").assertTextContains("-₹100.00")
    composeTestRule.onNodeWithTag("breakdown_cgst_text").assertTextContains("₹10.00")
    composeTestRule.onNodeWithTag("breakdown_sgst_text").assertTextContains("₹10.00")
    composeTestRule.onNodeWithTag("breakdown_total_text").assertTextContains("₹460.00")

    // Switch to Pickup -> delivery fee becomes FREE, Total becomes ₹420.00
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("fulfillment_pickup_chip"))
    composeTestRule.onNodeWithTag("fulfillment_pickup_chip").performClick()
    waitUntilMainLooper {
      quoteVm.uiState.value.quote?.fulfillmentType == FulfillmentType.PICKUP && !quoteVm.uiState.value.isLoadingQuote
    }
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("price_breakdown_card"))
    composeTestRule.onNodeWithTag("breakdown_delivery_fee_text").assertTextContains("FREE")
    composeTestRule.onNodeWithTag("breakdown_total_text").assertTextContains("₹420.00")
    assertEquals(42_000L, quoteVm.uiState.value.quote?.breakdown?.totalPaise)

    // Remove coupon -> total returns to ₹525.00 (₹500 subtotal + ₹25 GST)
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("remove_coupon_button"))
    composeTestRule.onNodeWithTag("remove_coupon_button").performClick()
    waitUntilMainLooper {
      quoteVm.uiState.value.appliedCouponCode == null && !quoteVm.uiState.value.isLoadingQuote
    }
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("price_breakdown_card"))
    composeTestRule.onNodeWithTag("breakdown_total_text").assertTextContains("₹525.00")
    assertEquals(52_500L, quoteVm.uiState.value.quote?.breakdown?.totalPaise)
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

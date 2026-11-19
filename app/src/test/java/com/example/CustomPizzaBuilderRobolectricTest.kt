package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.auth.AuthRepository
import com.example.auth.AuthViewModel
import com.example.cart.CartViewModel
import com.example.cart.NetworkCartRepository
import com.example.cart.toCartItemInputDto
import com.example.catalog.CustomerCatalogViewModel
import com.example.catalog.NetworkCatalogRepository
import com.example.checkout.CheckoutViewModel
import com.example.checkout.NetworkPricingRepository
import com.example.core.database.PizzoraDatabase
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.kds.KdsFilterTab
import com.example.kds.KdsScreen
import com.example.kds.KdsUiState
import com.example.navigation.PizzoraApp
import com.example.orders.NetworkOrderRepository
import com.example.orders.OrdersScreen
import com.example.orders.OrdersUiState
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CartToppingInputDto
import com.pizzora.contracts.CartToppingViewDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheeseOptionDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.KdsTicketDto
import com.pizzora.contracts.KdsTicketItemDto
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderItemCustomizationSnapshotDto
import com.pizzora.contracts.OrderItemDto
import com.pizzora.contracts.OrderItemToppingSnapshotDto
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PaymentSummaryDto
import com.pizzora.contracts.PizzaBuilderEngine
import com.pizzora.contracts.PizzaBuilderQuoteRequest
import com.pizzora.contracts.PizzaSizeRuleDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.SauceOptionDto
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.ToppingPlacement
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CustomPizzaBuilderRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var db: PizzoraDatabase
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val builderMenu = PublicMenuResponse(
    categories = listOf(
      CategoryDto("cat-1", "Artisanal Pies", 1, true, "2026-11-16T00:00:00Z"),
    ),
    products = listOf(
      ProductDto(
        id = "11111111-1111-4111-8111-111111111111",
        categoryId = "cat-1",
        categoryName = "Artisanal Pies",
        name = "Margherita Verace",
        description = "San Marzano tomato & basil",
        isVeg = true,
        isActive = true,
        isTaxable = true,
        variants = listOf(
          ProductVariantDto("33333333-3333-4333-8333-333333333331", "11111111-1111-4111-8111-111111111111", SizeCode.REGULAR, 29_900L, true),
          ProductVariantDto("33333333-3333-4333-8333-333333333332", "11111111-1111-4111-8111-111111111111", SizeCode.LARGE, 54_900L, true),
        ),
        supportsHalfAndHalf = true,
        defaultCheeseId = "44444444-4444-4444-8444-444444444441",
        defaultSauceId = "55555555-5555-4555-8555-555555555551",
        includedToppingIds = listOf("66666666-6666-4666-8666-666666666661"),
      ),
      ProductDto(
        id = "22222222-2222-4222-8222-222222222222",
        categoryId = "cat-1",
        categoryName = "Artisanal Pies",
        name = "Farmhouse Garden",
        description = "Crisp peppers, mushrooms & olives",
        isVeg = true,
        isActive = true,
        isTaxable = true,
        variants = listOf(
          ProductVariantDto("33333333-3333-4333-8333-333333333333", "22222222-2222-4222-8222-222222222222", SizeCode.LARGE, 64_900L, true),
        ),
        supportsHalfAndHalf = true,
        defaultCheeseId = "44444444-4444-4444-8444-444444444441",
        defaultSauceId = "55555555-5555-4555-8555-555555555552",
        includedToppingIds = listOf("66666666-6666-4666-8666-666666666662"),
      ),
    ),
    crusts = listOf(
      CrustOptionDto("77777777-7777-4777-8777-777777777771", "Hand-Tossed", 0L, true, supportsHalfAndHalf = true),
      CrustOptionDto("77777777-7777-4777-8777-777777777772", "Cheese Burst", 9_900L, true, supportsHalfAndHalf = false),
    ),
    toppings = listOf(
      ToppingDto("66666666-6666-4666-8666-666666666661", "Fresh Basil", true, 2_500L, true, maxQuantity = 2, supportsHalfAndHalf = true),
      ToppingDto("66666666-6666-4666-8666-666666666662", "Black Olives", true, 4_000L, true, maxQuantity = 2, supportsHalfAndHalf = true),
      ToppingDto("66666666-6666-4666-8666-666666666663", "Jalapenos", true, 3_500L, true, maxQuantity = 2, supportsHalfAndHalf = true),
    ),
    gstBps = 500,
    storeName = "PIZZORA Wood-Fired",
    isAcceptingOrders = true,
    sizeRules = listOf(
      PizzaSizeRuleDto(SizeCode.REGULAR, "Regular", maxToppings = 4, maxExtraToppings = 2, supportsHalfAndHalf = false),
      PizzaSizeRuleDto(SizeCode.LARGE, "Large", maxToppings = 8, maxExtraToppings = 2, supportsHalfAndHalf = true),
    ),
    cheeses = listOf(
      CheeseOptionDto("44444444-4444-4444-8444-444444444441", "Fior di Latte", 0L, 5_000L, supportsExtra = true, isVegan = false, isActive = true),
      CheeseOptionDto("44444444-4444-4444-8444-444444444442", "Vegan Mozzarella", 4_000L, 6_000L, supportsExtra = true, isVegan = true, isActive = true),
    ),
    sauces = listOf(
      SauceOptionDto("55555555-5555-4555-8555-555555555551", "San Marzano", 0L, 2_000L, supportsExtra = true, isVegan = true, isActive = true),
      SauceOptionDto("55555555-5555-4555-8555-555555555552", "Basil Pesto", 3_000L, 3_000L, supportsExtra = true, isVegan = true, isActive = true),
    ),
  )

  private val customerSession = AuthSession(
    accessToken = "jwt-builder",
    refreshToken = "ref-builder",
    user = UserProfileDto(
      id = "cust-builder-1",
      email = "builder@pizzora.test",
      emailVerified = true,
      fullName = "Aarav Mehta",
      phone = "9811112233",
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
  fun `customer builds half-and-half pizza with cheese, sauces, removed topping, placed extra topping, and views breakdown in CartScreen`() {
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/api/v1/catalog/menu" -> respond(
          content = json.encodeToString(builderMenu),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        else -> respond(content = "Offline", status = HttpStatusCode.ServiceUnavailable)
      }
    }

    val apiClient = PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(customerSession), engine)
    val catalogRepo = NetworkCatalogRepository(apiClient, db.catalogCacheDao())
    val cartRepo = NetworkCartRepository(apiClient, db.cartDao())

    val authVm = AuthViewModel(SignedInAuthRepository(customerSession))
    val catalogVm = CustomerCatalogViewModel(catalogRepo)
    val cartVm = CartViewModel(cartRepo)

    composeTestRule.setContent {
      PizzoraApp(
        authViewModel = authVm,
        catalogViewModel = catalogVm,
        cartViewModel = cartVm,
      )
    }
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("customize_product_button_11111111-1111-4111-8111-111111111111").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("pizza_customizer_sheet").assertIsDisplayed()

    // Select LARGE -> enables Half & Half
    composeTestRule.onNodeWithTag("size_option_LARGE").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("half_and_half_toggle").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("right_half_product_22222222-2222-4222-8222-222222222222").performScrollTo().performClick()

    // Vegan cheese (+40.00) + Extra Cheese (+60.00)
    composeTestRule.onNodeWithTag("cheese_option_44444444-4444-4444-8444-444444444442").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("extra_cheese_toggle").performScrollTo().performClick()

    // Right sauce = Basil Pesto (+15.00 half), Extra Sauce = on (+10.00 L + 15.00 R = 25.00)
    composeTestRule.onNodeWithTag("right_sauce_option_55555555-5555-4555-8555-555555555552").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("extra_sauce_toggle").performScrollTo().performClick()

    // Remove included Fresh Basil
    composeTestRule.onNodeWithTag("included_topping_toggle_66666666-6666-4666-8666-666666666661").performScrollTo().performClick()

    // Add Black Olives on RIGHT half x2 (included on Farmhouse right -> 1 free + 1 * 20.00 = +20.00)
    composeTestRule.onNodeWithTag("topping_option_66666666-6666-4666-8666-666666666662").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("topping_placement_66666666-6666-4666-8666-666666666662_RIGHT").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("topping_qty_plus_66666666-6666-4666-8666-666666666662").performScrollTo().performClick()

    // Expand live price breakdown
    composeTestRule.onNodeWithTag("customizer_price_breakdown").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    // Expected unit price:
    // Base Large Margherita 54900 + Half surcharge (64900-54900=10000) + Cheese 4000 + Extra Cheese 6000
    // + Sauce Pesto half 1500 + Extra Sauce (1000+1500=2500) + Olives right qty 2 (2000)
    // = 54900 + 10000 + 4000 + 6000 + 1500 + 2500 + 2000 = 80900 paise = ₹809.00
    composeTestRule.onNodeWithTag("add_to_cart_button")
      .assertIsEnabled()
      .assertTextContains("₹809.00", substring = true)
      .performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodesWithTag("cart_tab_badge", useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("cart_lazy_column")
      .performScrollToNode(hasText("Half & Half: Margherita Verace / Farmhouse Garden"))
    composeTestRule.onNodeWithText("Half & Half: Margherita Verace / Farmhouse Garden").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Cheese: Vegan Mozzarella (+ Extra Cheese)").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Sauces: L: San Marzano / R: Basil Pesto (+ Extra Sauce)").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Removed: Fresh Basil").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Toppings: Black Olives (Right) x2").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("cart_subtotal_text").assertTextContains("₹809.00")
  }

  @Test
  fun `PizzaCustomizerSheet displays live validation banner and blocks add when extra topping limit is exceeded`() {
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/api/v1/catalog/menu" -> respond(
          content = json.encodeToString(builderMenu),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        else -> respond(content = "Offline", status = HttpStatusCode.ServiceUnavailable)
      }
    }
    val apiClient = PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(customerSession), engine)
    val catalogVm = CustomerCatalogViewModel(NetworkCatalogRepository(apiClient, db.catalogCacheDao()))
    val cartVm = CartViewModel(NetworkCartRepository(apiClient, db.cartDao()))
    val authVm = AuthViewModel(SignedInAuthRepository(customerSession))

    composeTestRule.setContent {
      PizzoraApp(authViewModel = authVm, catalogViewModel = catalogVm, cartViewModel = cartVm)
    }
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("customize_product_button_11111111-1111-4111-8111-111111111111").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    // Regular allows maxExtraToppings = 2. Selecting Black Olives x2 + Jalapenos x1 (WHOLE = 3 extra) triggers error.
    composeTestRule.onNodeWithTag("topping_option_66666666-6666-4666-8666-666666666662").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("topping_qty_plus_66666666-6666-4666-8666-666666666662").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("topping_option_66666666-6666-4666-8666-666666666663").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("customizer_validation_issues").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("add_to_cart_button").assertIsNotEnabled()
  }

  @Test
  fun `NetworkCartRepository deduplicates by full configurationSignature, preserves removedIncludedToppingIds across sync, and CheckoutViewModel submits full payload`() = runBlocking {
    val capturedCheckout = AtomicReference<CheckoutRequest?>()
    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      when {
        path == "/api/v1/cart" && request.method == HttpMethod.Put -> {
          respond(
            content = json.encodeToString(CartResponseDto(updatedAt = "2026-11-16T00:00:00Z")),
            status = HttpStatusCode.ServiceUnavailable,
          )
        }
        path == "/api/v1/pizza-builder/quote" && request.method == HttpMethod.Post -> {
          val bodyText = when (val b = request.body) {
            is TextContent -> b.text
            is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
            else -> error("Unexpected body")
          }
          val req = json.decodeFromString<PizzaBuilderQuoteRequest>(bodyText)
          val eval = PizzaBuilderEngine.evaluate(req.item, builderMenu)
          respond(
            content = json.encodeToString(eval.toQuoteResponse()),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/checkout" && request.method == HttpMethod.Post -> {
          val bodyText = when (val b = request.body) {
            is TextContent -> b.text
            is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
            else -> error("Unexpected body")
          }
          val checkoutReq = json.decodeFromString<CheckoutRequest>(bodyText)
          capturedCheckout.set(checkoutReq)
          respond(
            content = json.encodeToString(
              CheckoutResponse(
                order = OrderDetailDto(
                  id = "ord-custom-1",
                  orderNumber = "PZZ-9001",
                  customerId = customerSession.user.id,
                  fulfillmentType = FulfillmentType.PICKUP,
                  scheduleType = ScheduleType.ASAP,
                  status = OrderStatus.PLACED,
                  version = 1L,
                  subtotalPaise = 80_900L,
                  discountPaise = 0L,
                  deliveryFeePaise = 0L,
                  taxPaise = 4_045L,
                  cgstPaise = 2_022L,
                  sgstPaise = 2_023L,
                  totalPaise = 84_945L,
                  gstBpsSnapshot = 500,
                  isPriority = false,
                  createdAt = "2026-11-16T00:00:00Z",
                  items = emptyList(),
                  statusHistory = emptyList(),
                  payment = PaymentSummaryDto(
                    id = "pay-9001",
                    method = PaymentMethod.COD,
                    status = PaymentStatus.COD_PENDING,
                    amountPaise = 84_945L,
                    capturedPaise = 0L,
                    refundedPaise = 0L,
                  ),
                ),
              ),
            ),
            status = HttpStatusCode.Created,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected ${request.method} $path")
      }
    }

    val apiClient = PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(customerSession), engine)
    val pricingRepo = NetworkPricingRepository(apiClient)
    val cartRepo = NetworkCartRepository(apiClient, db.cartDao())

    val inputA = CartItemInputDto(
      productId = "11111111-1111-4111-8111-111111111111",
      variantId = "33333333-3333-4333-8333-333333333332",
      crustId = "77777777-7777-4777-8777-777777777771",
      quantity = 1,
      cheeseId = "44444444-4444-4444-8444-444444444442",
      extraCheese = true,
      sauceId = "55555555-5555-4555-8555-555555555551",
      extraSauce = true,
      isHalfAndHalf = true,
      rightProductId = "22222222-2222-4222-8222-222222222222",
      rightVariantId = "33333333-3333-4333-8333-333333333333",
      rightSauceId = "55555555-5555-4555-8555-555555555552",
      removedIncludedToppingIds = listOf("66666666-6666-4666-8666-666666666661"),
      toppingSelections = listOf(
        CartToppingInputDto("66666666-6666-4666-8666-666666666662", ToppingPlacement.RIGHT, quantity = 2),
      ),
    )

    val quoteRes = pricingRepo.previewCustomPizza(PizzaBuilderQuoteRequest(inputA))
    assertTrue(quoteRes.isSuccess)
    assertEquals(80_900L, quoteRes.getOrThrow().unitPricePaise)

    // Same configuration added twice -> merges into 1 cart line (qty 2)
    cartRepo.addConfiguredPizza(inputA, builderMenu)
    cartRepo.addConfiguredPizza(inputA, builderMenu)
    // Different topping placement -> separate cart line
    val inputB = inputA.copy(
      toppingSelections = listOf(CartToppingInputDto("66666666-6666-4666-8666-666666666662", ToppingPlacement.LEFT, quantity = 2)),
    )
    cartRepo.addConfiguredPizza(inputB, builderMenu)

    val cartEntries = cartRepo.observeCart().first()
    assertEquals(2, cartEntries.size)
    assertNotEquals(
      cartEntries[0].item.configurationSignature,
      cartEntries[1].item.configurationSignature,
    )
    assertEquals(2, cartEntries.first { it.toppings.single().placement == "RIGHT" }.item.quantity)

    val dtoA = cartEntries.first { it.toppings.single().placement == "RIGHT" }.toCartItemInputDto()
    assertEquals(listOf("66666666-6666-4666-8666-666666666661"), dtoA.removedIncludedToppingIds)
    assertEquals(ToppingPlacement.RIGHT, dtoA.toppingSelections.single().placement)
    assertEquals(2, dtoA.toppingSelections.single().quantity)

    // Submit checkout through CheckoutViewModel
    val orderRepo = NetworkOrderRepository(apiClient)
    val checkoutVm = CheckoutViewModel(orderRepo)
    checkoutVm.placeOrder(
      cartItems = cartEntries,
      fulfillmentType = FulfillmentType.PICKUP,
      couponCode = null,
      onCartCleared = {},
    )

    val deadline = System.currentTimeMillis() + 5_000
    while (capturedCheckout.get() == null && System.currentTimeMillis() < deadline) {
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(20)
    }
    org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertEquals(null, checkoutVm.uiState.value.checkoutError)
    val req = capturedCheckout.get()
    assertNotNull(req)
    assertEquals(2, req!!.items.size)
    assertTrue(req.items.all { it.isHalfAndHalf && it.extraCheese && it.extraSauce })
    assertTrue(req.items.all { it.removedIncludedToppingIds == listOf("66666666-6666-4666-8666-666666666661") })
  }

  @Test
  fun `OrdersScreen renders immutable customizationSnapshot details`() {
    val snapshot = sampleSnapshot()
    val sampleOrder = sampleSnapshotOrder(snapshot)

    composeTestRule.setContent {
      OrdersScreen(
        uiState = OrdersUiState(selectedOrder = sampleOrder, isLoadingList = false, isLoadingDetail = false),
        onRefreshOrders = {},
        onOpenOrder = {},
        onBackToList = {},
        onCancelOrder = {},
        onBrowseMenu = {},
      )
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("order_detail_lazy_column")
      .performScrollToNode(hasTestTag("order_detail_item_oi-1"))
    composeTestRule.onNodeWithText("Cheese: Vegan Mozzarella (+ Extra Cheese)").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Removed: Fresh Basil").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Toppings: Black Olives (Right) x2").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun `KdsScreen renders immutable customizationSnapshot prep callouts`() {
    val snapshot = sampleSnapshot()
    val kdsTicket = KdsTicketDto(
      id = "ord-snap-1",
      orderNumber = "PZZ-SNAP-1",
      customerName = "Aarav Mehta",
      fulfillmentType = FulfillmentType.PICKUP,
      scheduleType = ScheduleType.ASAP,
      status = OrderStatus.PREPARING,
      version = 1L,
      isPriority = false,
      paymentMethod = PaymentMethod.COD,
      paymentStatus = PaymentStatus.COD_PENDING,
      totalPaise = 84_945L,
      createdAt = "2026-11-16T00:00:00Z",
      items = listOf(
        KdsTicketItemDto(
          id = "oi-1",
          productId = "11111111-1111-4111-8111-111111111111",
          productName = "Half & Half: Margherita Verace / Farmhouse Garden",
          variantSize = "LARGE",
          crustName = "Hand-Tossed",
          toppings = listOf("Black Olives"),
          quantity = 1,
          customizationSnapshot = snapshot,
        ),
      ),
    )

    composeTestRule.setContent {
      KdsScreen(
        uiState = KdsUiState(
          tickets = listOf(kdsTicket),
          selectedFilter = KdsFilterTab.ALL,
          isLoading = false,
        ),
        operatorName = "Chef Marco",
        onSelectFilter = {},
        onToggleIncludeCompleted = {},
        onToggleMute = {},
        onRefresh = {},
        onAdvanceStage = {},
        onTogglePriority = {},
      )
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("HALF & HALF • L: Margherita Verace | R: Farmhouse Garden").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Cheese: Vegan Mozzarella [EXTRA]").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("NO: Fresh Basil").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("+ Black Olives (RIGHT) x2").performScrollTo().assertIsDisplayed()
  }

  private fun sampleSnapshot() = OrderItemCustomizationSnapshotDto(
    isHalfAndHalf = true,
    leftProductName = "Margherita Verace",
    rightProductId = "22222222-2222-4222-8222-222222222222",
    rightProductName = "Farmhouse Garden",
    cheeseName = "Vegan Mozzarella",
    extraCheese = true,
    sauceName = "San Marzano",
    rightSauceName = "Basil Pesto",
    extraSauce = true,
    removedIncludedToppings = listOf("Fresh Basil"),
    toppingDetails = listOf(
      OrderItemToppingSnapshotDto(
        toppingId = "66666666-6666-4666-8666-666666666662",
        name = "Black Olives",
        placement = ToppingPlacement.RIGHT,
        quantity = 2,
        isIncluded = true,
        unitPricePaise = 2_000L,
        totalPricePaise = 2_000L,
      ),
    ),
  )

  private fun sampleSnapshotOrder(snapshot: OrderItemCustomizationSnapshotDto) = OrderDetailDto(
    id = "ord-snap-1",
    orderNumber = "PZZ-SNAP-1",
    customerId = "cust-1",
    fulfillmentType = FulfillmentType.PICKUP,
    scheduleType = ScheduleType.ASAP,
    status = OrderStatus.PREPARING,
    version = 1L,
    subtotalPaise = 80_900L,
    discountPaise = 0L,
    deliveryFeePaise = 0L,
    taxPaise = 4_045L,
    cgstPaise = 2_022L,
    sgstPaise = 2_023L,
    totalPaise = 84_945L,
    gstBpsSnapshot = 500,
    isPriority = false,
    createdAt = "2026-11-16T00:00:00Z",
    items = listOf(
      OrderItemDto(
        id = "oi-1",
        productId = "11111111-1111-4111-8111-111111111111",
        productName = "Half & Half: Margherita Verace / Farmhouse Garden",
        variantSize = "LARGE",
        crustName = "Hand-Tossed",
        toppings = listOf("Black Olives"),
        unitPricePaise = 80_900L,
        quantity = 1,
        lineTotalPaise = 80_900L,
        customizationSnapshot = snapshot,
        priceBreakdown = PizzaBuilderEngine.evaluate(
          CartItemInputDto(
            productId = "11111111-1111-4111-8111-111111111111",
            variantId = "33333333-3333-4333-8333-333333333332",
            crustId = "77777777-7777-4777-8777-777777777771",
            quantity = 1,
            cheeseId = "44444444-4444-4444-8444-444444444442",
            extraCheese = true,
            sauceId = "55555555-5555-4555-8555-555555555551",
            extraSauce = true,
            isHalfAndHalf = true,
            rightProductId = "22222222-2222-4222-8222-222222222222",
            rightVariantId = "33333333-3333-4333-8333-333333333333",
            rightSauceId = "55555555-5555-4555-8555-555555555552",
            removedIncludedToppingIds = listOf("66666666-6666-4666-8666-666666666661"),
            toppingSelections = listOf(CartToppingInputDto("66666666-6666-4666-8666-666666666662", ToppingPlacement.RIGHT, 2)),
          ),
          builderMenu,
        ).priceBreakdown,
      ),
    ),
    statusHistory = emptyList(),
    payment = PaymentSummaryDto(
      id = "pay-1",
      method = PaymentMethod.COD,
      status = PaymentStatus.COD_PENDING,
      amountPaise = 84_945L,
      capturedPaise = 0L,
      refundedPaise = 0L,
    ),
  )
}

package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.auth.AuthRepository
import com.example.auth.AuthViewModel
import com.example.cart.CartViewModel
import com.example.cart.NetworkCartRepository
import com.example.catalog.CustomerCatalogViewModel
import com.example.catalog.NetworkCatalogRepository
import com.example.core.database.PizzoraDatabase
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenCipher
import com.example.core.security.TokenManager
import com.example.core.security.TokenStorage
import com.example.navigation.PizzoraApp
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CartToppingViewDto
import com.pizzora.contracts.CartValidationIssueCode
import com.pizzora.contracts.CartValidationIssueDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.ReplaceCartRequest
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase7CartAndMenuRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var db: PizzoraDatabase
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private val sampleMenu = PublicMenuResponse(
    categories = listOf(
      CategoryDto("cat-1", "Classics", 1, true, "2026-11-13T10:00:00Z"),
      CategoryDto("cat-2", "Specials", 2, true, "2026-11-13T10:00:00Z"),
    ),
    products = listOf(
      ProductDto(
        id = "11111111-1111-7111-8111-111111111111",
        categoryId = "cat-1",
        categoryName = "Classics",
        name = "Margherita Verace",
        description = "San Marzano DOP tomato and fior di latte",
        isVeg = true,
        isActive = true,
        isTaxable = true,
        variants = listOf(
          ProductVariantDto("22222222-2222-7222-8222-222222222221", "11111111-1111-7111-8111-111111111111", SizeCode.REGULAR, 29_900L, true),
          ProductVariantDto("22222222-2222-7222-8222-222222222222", "11111111-1111-7111-8111-111111111111", SizeCode.LARGE, 54_900L, true),
        ),
      ),
      ProductDto(
        id = "11111111-1111-7111-8111-111111111112",
        categoryId = "cat-2",
        categoryName = "Specials",
        name = "Diavola Calabrese",
        description = "Spicy Calabrian salami and hot honey",
        isVeg = false,
        isActive = true,
        isTaxable = true,
        variants = listOf(
          ProductVariantDto("22222222-2222-7222-8222-222222222223", "11111111-1111-7111-8111-111111111112", SizeCode.MEDIUM, 48_900L, true),
        ),
      ),
    ),
    crusts = listOf(
      CrustOptionDto("33333333-3333-7333-8333-333333333331", "Hand-Tossed", 0L, true),
      CrustOptionDto("33333333-3333-7333-8333-333333333332", "Sourdough Pan", 5_000L, true),
    ),
    toppings = listOf(
      ToppingDto("44444444-4444-7444-8444-444444444441", "Extra Basil", true, 2_500L, true),
      ToppingDto("44444444-4444-7444-8444-444444444442", "Burrata", true, 9_500L, true),
    ),
    gstBps = 500,
    storeName = "PIZZORA Artisanal Kitchen",
    isAcceptingOrders = true,
  )

  private val customerSession = AuthSession(
    accessToken = "cust-jwt",
    refreshToken = "cust-ref",
    user = UserProfileDto(
      id = "user-1",
      email = "customer@pizzora.test",
      emailVerified = true,
      fullName = "Giulia Bianchi",
      phone = "9811111111",
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
  fun `offline cart merges identical customizations and flags unavailable modifiers upon reconnect sync`() = runBlocking {
    var offline = true
    val engine = MockEngine { request ->
      if (offline) {
        respond(content = "Offline", status = HttpStatusCode.ServiceUnavailable)
      } else if (request.url.encodedPath == "/api/v1/cart" && request.method == HttpMethod.Put) {
        val bodyText = when (val body = request.body) {
          is TextContent -> body.text
          is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
          else -> error("Unexpected body type")
        }
        val req = json.decodeFromString<ReplaceCartRequest>(bodyText)
        val first = req.items.first()
        respond(
          content = json.encodeToString(
            CartResponseDto(
              cartId = "server-cart-1",
              items = listOf(
                CartItemViewDto(
                  id = "server-item-1",
                  productId = first.productId,
                  productName = "Margherita Verace",
                  isVeg = true,
                  variantId = first.variantId,
                  sizeCode = SizeCode.LARGE,
                  basePricePaise = 59_900L, // Updated server price
                  crustId = first.crustId,
                  crustName = "Sourdough Pan",
                  crustExtraPricePaise = 5_000L,
                  toppings = listOf(
                    CartToppingViewDto(
                      toppingId = first.toppingIds.first(),
                      name = "Extra Basil",
                      isVeg = true,
                      extraPricePaise = 2_500L,
                      isAvailable = false,
                    ),
                  ),
                  quantity = first.quantity,
                  unitPricePaise = 67_400L,
                  lineTotalPaise = 67_400L * first.quantity,
                  isAvailable = false,
                  issues = listOf(
                    CartValidationIssueDto(
                      code = CartValidationIssueCode.TOPPING_UNAVAILABLE,
                      message = "Extra Basil is no longer available.",
                    ),
                  ),
                ),
              ),
              subtotalPaise = 0L,
              totalItemCount = first.quantity,
              allAvailable = false,
              unavailableCount = 1,
              updatedAt = "2026-11-13T12:15:00Z",
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
      } else {
        error("Unexpected ${request.method.value} ${request.url}")
      }
    }

    val apiClient = PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(customerSession), engine)
    val repo = NetworkCartRepository(apiClient, db.cartDao())

    val margherita = sampleMenu.products[0]
    val large = margherita.variants[1]
    val sourdough = sampleMenu.crusts[1]
    val basil = sampleMenu.toppings[0]

    // Add twice while offline -> merges into single item qty = 2 cleanly in Room
    val r1 = repo.addCustomizedItem(margherita, large, sourdough, listOf(basil), 1)
    assertTrue(r1.isOffline)
    val r2 = repo.addCustomizedItem(margherita, large, sourdough, listOf(basil), 1)
    assertTrue(r2.isOffline)

    val offlineItems = repo.observeCart().first()
    assertEquals(1, offlineItems.size)
    assertEquals(2, offlineItems.first().item.quantity)
    assertEquals(1, offlineItems.first().toppings.size)
    assertTrue(offlineItems.first().item.isAvailable)

    // Reconnect and sync -> flags unavailable topping and updates server unitPricePaise
    offline = false
    val synced = repo.syncAndValidateCart(persistToServer = true)
    assertFalse(synced.isOffline)
    assertEquals(1, synced.unavailableCount)

    val syncedItems = repo.observeCart().first()
    assertEquals(1, syncedItems.size)
    assertFalse(syncedItems.first().item.isAvailable)
    assertEquals(67_400L, syncedItems.first().item.unitPricePaise)
    assertEquals("Extra Basil is no longer available.", syncedItems.first().item.unavailableReason)
  }

  @Test
  fun `CatalogRepository restores full PublicMenuResponse snapshot when offline`() = runBlocking {
    var offline = false
    val engine = MockEngine { request ->
      if (offline) {
        respond(content = "Down", status = HttpStatusCode.ServiceUnavailable)
      } else {
        respond(
          content = json.encodeToString(sampleMenu),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
      }
    }

    val repo = NetworkCatalogRepository(
      apiClient = PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(customerSession), engine),
      catalogCacheDao = db.catalogCacheDao(),
    )

    val online = repo.refreshPublicMenu()
    assertFalse(online.isFromOfflineCache)
    assertEquals(2, online.menu?.crusts?.size)

    offline = true
    val offlineSnap = repo.refreshPublicMenu()
    assertTrue(offlineSnap.isFromOfflineCache)
    assertNotNull(offlineSnap.menu)
    assertEquals(2, offlineSnap.menu?.categories?.size)
    assertEquals(2, offlineSnap.menu?.crusts?.size)
    assertEquals(2, offlineSnap.menu?.toppings?.size)
  }

  @Test
  fun `Customer browses menu, filters veg, customizes pizza, adds to offline cart, and views CartScreen`() {
    val failCartSync = true
    val engine = MockEngine { request ->
      when {
        request.url.encodedPath == "/api/v1/catalog/menu" -> {
          respond(
            content = json.encodeToString(sampleMenu),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        failCartSync -> respond(content = "Offline", status = HttpStatusCode.ServiceUnavailable)
        else -> respond(
          content = json.encodeToString(
            CartResponseDto(
              cartId = "cart-1",
              items = emptyList(),
              subtotalPaise = 0L,
              totalItemCount = 0,
              allAvailable = true,
              unavailableCount = 0,
              updatedAt = "2026-11-13T12:20:00Z",
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
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

    composeTestRule.onNodeWithTag("customer_menu_screen").assertIsDisplayed()
    val margheritaId = sampleMenu.products[0].id
    val diavolaId = sampleMenu.products[1].id

    composeTestRule.onNodeWithTag("menu_lazy_column")
      .performScrollToNode(hasTestTag("product_card_$margheritaId"))
    composeTestRule.onNodeWithTag("product_card_$margheritaId").assertIsDisplayed()

    composeTestRule.onNodeWithTag("menu_lazy_column")
      .performScrollToNode(hasTestTag("product_card_$diavolaId"))
    composeTestRule.onNodeWithTag("product_card_$diavolaId").assertIsDisplayed()

    // Scroll back up to Veg Only filter chip and toggle -> Diavola disappears
    composeTestRule.onNodeWithTag("menu_lazy_column").performScrollToIndex(0)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("veg_only_filter_chip").performClick()
    composeTestRule.waitForIdle()
    assertEquals(0, composeTestRule.onAllNodesWithTag("product_card_$diavolaId").fetchSemanticsNodes().size)

    composeTestRule.onNodeWithTag("menu_lazy_column")
      .performScrollToNode(hasTestTag("product_card_$margheritaId"))
    composeTestRule.onNodeWithTag("product_card_$margheritaId").assertIsDisplayed()

    // Open Customizer -> Select Large + Sourdough + Extra Basil + Qty 2
    composeTestRule.onNodeWithTag("customize_product_button_$margheritaId").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("pizza_customizer_sheet").assertIsDisplayed()

    composeTestRule.onNodeWithTag("size_option_LARGE").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("crust_option_33333333-3333-7333-8333-333333333332").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("topping_option_44444444-4444-7444-8444-444444444441").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("customizer_qty_plus").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("customizer_qty_value").assertTextContains("2")
    // (54900 + 5000 + 2500) * 2 = 62400 * 2 = 124800 paise = ₹1248.00
    composeTestRule.onNodeWithTag("add_to_cart_button").assertTextContains("₹1248.00", substring = true).performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.waitForIdle()

    // Badge shows 2 (Room Flow updates asynchronously from IO executor; NavigationBarItem merges icon children)
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodesWithTag("cart_tab_badge", useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("cart_tab_badge", useUnmergedTree = true).assertTextContains("2")
    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("cart_screen").assertIsDisplayed()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodesWithTag("cart_offline_banner").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("cart_offline_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasText("Margherita Verace"))
    composeTestRule.onNodeWithText("Margherita Verace").assertIsDisplayed()
    composeTestRule.onNodeWithText("Toppings: Extra Basil").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cart_subtotal_text").assertTextContains("₹1248.00")
  }

  @Test
  fun `Customer sign-in hydrates server cart into Room automatically and supports quantity, sync, and clear actions`() {
    val serverItemId = "server-cart-item-1"
    val serverQty = java.util.concurrent.atomic.AtomicInteger(1)
    val putCount = java.util.concurrent.atomic.AtomicInteger(0)
    var markUnavailableOnValidate = false

    val engine = MockEngine { request ->
      when {
        request.url.encodedPath == "/api/v1/catalog/menu" -> {
          respond(
            content = json.encodeToString(sampleMenu),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        request.url.encodedPath == "/api/v1/cart" && request.method == HttpMethod.Get -> {
          val qty = serverQty.get()
          respond(
            content = json.encodeToString(
              CartResponseDto(
                cartId = "server-cart-1",
                items = listOf(
                  CartItemViewDto(
                    id = serverItemId,
                    productId = sampleMenu.products[0].id,
                    productName = "Margherita Verace",
                    isVeg = true,
                    variantId = sampleMenu.products[0].variants[0].id,
                    sizeCode = SizeCode.REGULAR,
                    basePricePaise = 29_900L,
                    crustId = sampleMenu.crusts[0].id,
                    crustName = "Hand-Tossed",
                    crustExtraPricePaise = 0L,
                    toppings = listOf(
                      CartToppingViewDto(
                        toppingId = sampleMenu.toppings[0].id,
                        name = "Extra Basil",
                        isVeg = true,
                        extraPricePaise = 2_500L,
                        isAvailable = true,
                      ),
                    ),
                    quantity = qty,
                    unitPricePaise = 32_400L,
                    lineTotalPaise = 32_400L * qty,
                    isAvailable = true,
                    issues = emptyList(),
                  ),
                ),
                subtotalPaise = 32_400L * qty,
                totalItemCount = qty,
                allAvailable = true,
                unavailableCount = 0,
                updatedAt = "2026-11-13T12:25:00Z",
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        request.url.encodedPath == "/api/v1/cart" && request.method == HttpMethod.Put -> {
          putCount.incrementAndGet()
          val bodyText = when (val body = request.body) {
            is TextContent -> body.text
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            else -> error("Unexpected body type")
          }
          val req = json.decodeFromString<ReplaceCartRequest>(bodyText)
          if (req.items.isEmpty()) {
            serverQty.set(0)
            respond(
              content = json.encodeToString(
                CartResponseDto(
                  cartId = "server-cart-1",
                  items = emptyList(),
                  subtotalPaise = 0L,
                  totalItemCount = 0,
                  allAvailable = true,
                  unavailableCount = 0,
                  updatedAt = "2026-11-13T12:26:00Z",
                ),
              ),
              status = HttpStatusCode.OK,
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
          } else {
            val qty = req.items.first().quantity
            serverQty.set(qty)
            val available = !markUnavailableOnValidate
            respond(
              content = json.encodeToString(
                CartResponseDto(
                  cartId = "server-cart-1",
                  items = listOf(
                    CartItemViewDto(
                      id = serverItemId,
                      productId = sampleMenu.products[0].id,
                      productName = "Margherita Verace",
                      isVeg = true,
                      variantId = sampleMenu.products[0].variants[0].id,
                      sizeCode = SizeCode.REGULAR,
                      basePricePaise = 29_900L,
                      crustId = sampleMenu.crusts[0].id,
                      crustName = "Hand-Tossed",
                      crustExtraPricePaise = 0L,
                      toppings = listOf(
                        CartToppingViewDto(
                          toppingId = sampleMenu.toppings[0].id,
                          name = "Extra Basil",
                          isVeg = true,
                          extraPricePaise = 2_500L,
                          isAvailable = available,
                        ),
                      ),
                      quantity = qty,
                      unitPricePaise = 32_400L,
                      lineTotalPaise = 32_400L * qty,
                      isAvailable = available,
                      issues = if (available) emptyList() else listOf(
                        CartValidationIssueDto(
                          code = CartValidationIssueCode.TOPPING_UNAVAILABLE,
                          message = "Extra Basil ran out of stock.",
                        ),
                      ),
                    ),
                  ),
                  subtotalPaise = if (available) 32_400L * qty else 0L,
                  totalItemCount = qty,
                  allAvailable = available,
                  unavailableCount = if (available) 0 else 1,
                  updatedAt = "2026-11-13T12:26:00Z",
                ),
              ),
              status = HttpStatusCode.OK,
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
          }
        }
        else -> error("Unexpected ${request.method.value} ${request.url}")
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

    // Automatic hydration on entering CustomerRoleShell populates badge = 1 from GET /cart
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("cart_tab_badge", useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("cart_tab_badge", useUnmergedTree = true).assertTextContains("1")

    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("cart_screen").assertIsDisplayed()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("cart_qty_value_$serverItemId").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("cart_qty_value_$serverItemId").assertTextContains("1")
    composeTestRule.onNodeWithTag("cart_subtotal_text").assertTextContains("₹324.00")

    // Increment quantity to 2 -> subtotal updates to ₹648.00
    composeTestRule.onNodeWithTag("cart_lazy_column")
      .performScrollToNode(hasTestTag("cart_qty_plus_$serverItemId"))
    composeTestRule.onNodeWithTag("cart_qty_plus_$serverItemId").performClick()
    waitUntilMainLooper {
      serverQty.get() == 2 && cartVm.uiState.value.subtotalPaise == 64_800L && !cartVm.uiState.value.isSyncing
    }
    assertFalse(
      "Expected online sync after qty increment (putCount=${putCount.get()}, msg=${cartVm.uiState.value.statusMessage}, qty=${cartVm.uiState.value.totalItemCount}, sub=${cartVm.uiState.value.subtotalPaise})",
      cartVm.uiState.value.isOffline,
    )
    assertEquals(2, serverQty.get())
    assertEquals(64_800L, cartVm.uiState.value.subtotalPaise)
    composeTestRule.onNodeWithTag("cart_qty_value_$serverItemId").assertTextContains("2")
    composeTestRule.onNodeWithTag("cart_subtotal_text").assertTextContains("₹648.00")

    // Re-validate when topping goes out of stock -> unavailable banner + item issue appear
    markUnavailableOnValidate = true
    composeTestRule.onNodeWithTag("validate_cart_button").performClick()
    waitUntilMainLooper {
      cartVm.uiState.value.hasUnavailableItems && !cartVm.uiState.value.isSyncing
    }
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToIndex(0)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("cart_unavailable_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cart_lazy_column")
      .performScrollToNode(hasTestTag("cart_item_issue_$serverItemId"))
    composeTestRule.onNodeWithTag("cart_item_issue_$serverItemId").assertTextContains("Extra Basil ran out of stock.")

    // Clear cart -> confirm modal -> empty state displayed
    composeTestRule.onNodeWithTag("clear_cart_button").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("confirm_clear_cart_button").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("confirm_clear_cart_button").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("cart_empty_state").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("cart_empty_state").assertIsDisplayed()
  }

  private fun waitUntilMainLooper(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
      composeTestRule.waitForIdle()
      if (condition()) return
      if (System.currentTimeMillis() > deadline) {
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for async Robolectric/Compose state")
      }
      Thread.sleep(20)
    }
  }

  @Test
  fun `TokenManager awaitSession waits for cold-start DataStore hydration`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val passThroughCipher = object : TokenCipher {
      override fun encrypt(plainText: String): String = "test:$plainText"
      override fun decrypt(cipherText: String): String? = cipherText.removePrefix("test:")
    }

    val firstInstance = TokenManager(context, cipher = passThroughCipher)
    firstInstance.saveSession(customerSession)

    val coldStartInstance = TokenManager(context, cipher = passThroughCipher)
    val hydrated = coldStartInstance.awaitSession()
    assertNotNull(hydrated)
    assertEquals(customerSession.accessToken, hydrated?.accessToken)
    assertEquals(customerSession.user.id, hydrated?.user?.id)

    coldStartInstance.clearSession()
  }
}

package com.example.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.cart.CartRepository
import com.example.cart.CartSyncResult
import com.example.cart.CartViewModel
import com.example.catalog.CatalogRepository
import com.example.catalog.CatalogSnapshot
import com.example.catalog.CustomerCatalogViewModel
import com.example.core.database.CartItemWithToppings
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartResponseDto
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
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import android.os.Looper
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class Phase8BuilderNavWiringRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private val session = AuthSession(
    accessToken = "customer-token",
    refreshToken = "customer-refresh",
    user = UserProfileDto(
      id = "customer-1",
      email = "customer@pizzora.test",
      emailVerified = true,
      fullName = "Phase 8 Customer",
      phone = "9811111199",
      role = UserRole.CUSTOMER,
      isActive = true,
    ),
  )

  private val product = ProductDto(
    id = "prod-nav",
    categoryId = "cat-nav",
    categoryName = "Classics",
    name = "Nav Builder Pizza",
    description = "Wiring fixture",
    isVeg = true,
    isActive = true,
    isTaxable = true,
    variants = listOf(
      ProductVariantDto("var-nav", "prod-nav", SizeCode.REGULAR, pricePaise = 10_000L, isActive = true),
    ),
    cheeses = emptyList(),
    sauces = emptyList(),
    standardToppings = emptyList(),
    halfAndHalfConfigs = emptyList(),
  )

  private val crusts = listOf(CrustOptionDto("crust-nav", "Classic", 0L, isActive = true))

  @Test
  fun `nav add builder call site forwards the full cart input through server replace`() {
    val captured = mutableListOf<ReplaceCartRequest>()
    val engine = MockEngine { request ->
      when {
        request.method == HttpMethod.Get && request.url.encodedPath == "/api/v1/cart" -> respond(
          content = json.encodeToString(
            CartResponseDto("cart-1", emptyList<CartItemViewDto>(), 0L, 0, true, 0, "2026-11-27T12:00:00Z"),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        request.method == HttpMethod.Put && request.url.encodedPath == "/api/v1/cart" -> {
          captured.add(json.decodeFromString(bodyAsString(request.body)))
          respond(
            content = json.encodeToString(
              CartResponseDto("cart-1", emptyList<CartItemViewDto>(), 0L, 0, true, 0, "2026-11-27T12:00:00Z"),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
      }
    }
    val api = PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(session), engine)
    val repository = RecordingCartRepository()
    val cartViewModel = CartViewModel(repository, { api })
    val catalogViewModel = CustomerCatalogViewModel(FakeCatalogRepository(product, crusts))
    catalogViewModel.openCustomizer(product)

    try {
      composeTestRule.setContent {
        PizzoraTheme {
          CustomerRoleShell(
            session = session,
            catalogViewModel = catalogViewModel,
            cartViewModel = cartViewModel,
            onSignOut = {},
          )
        }
      }

      composeTestRule.waitUntil(timeoutMillis = 5_000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        composeTestRule.onAllNodesWithTag("pizza_customizer_sheet").fetchSemanticsNodes().isNotEmpty()
      }
      composeTestRule.onNodeWithTag("pizza_customizer_sheet").assertIsDisplayed()
      composeTestRule.onNodeWithTag("add_to_cart_button").assertIsEnabled().performClick()

      composeTestRule.waitUntil(timeoutMillis = 5_000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        captured.isNotEmpty()
      }

      val request = captured.single()
      assertEquals(1, request.items.size)
      val input = request.items.single()
      assertEquals("prod-nav", input.productId)
      assertEquals("var-nav", input.variantId)
      assertEquals("crust-nav", input.crustId)
      assertEquals(1, input.quantity)
      // Legacy offline-first path must not be used by the main nav builder flow.
      assertEquals(0, repository.addCalls)
      // One session bootstrap when the shell opens, plus the post-replace refresh.
      assertEquals(2, repository.bootstrapCalls)
      assertTrue(captured.size == 1)
    } finally {
      com.example.cart.ReorderCartBridge.unregister(cartViewModel)
    }
  }

  private fun bodyAsString(body: OutgoingContent): String = when (body) {
    is TextContent -> body.text
    is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
    else -> error("Unexpected request body type: ${body::class.simpleName}")
  }

  private class InMemoryTokenStorage(initial: AuthSession?) : TokenStorage {
    private val session = MutableStateFlow(initial)
    private val muted = MutableStateFlow(false)
    override val sessionFlow: StateFlow<AuthSession?> = session.asStateFlow()
    override fun currentSession(): AuthSession? = session.value
    override suspend fun saveSession(value: AuthSession) {
      session.value = value
    }
    override suspend fun clearSession() {
      session.value = null
    }
    override fun observeKdsSoundMuted(): Flow<Boolean> = muted
    override suspend fun setKdsSoundMuted(value: Boolean) {
      muted.value = value
    }
  }

  private class FakeCatalogRepository(
    private val product: ProductDto,
    private val crusts: List<CrustOptionDto>,
  ) : CatalogRepository {
    override fun observeCachedProducts(): Flow<List<ProductDto>> = flowOf(listOf(product))
    override suspend fun refreshPublicMenu(): CatalogSnapshot = CatalogSnapshot(
      menu = PublicMenuResponse(
        categories = emptyList(),
        products = listOf(product),
        crusts = crusts,
        toppings = emptyList<ToppingDto>(),
        gstBps = 500,
        storeName = "Test Kitchen",
        isAcceptingOrders = true,
      ),
      cachedProducts = listOf(product),
      isFromOfflineCache = false,
    )
  }

  private class RecordingCartRepository : CartRepository {
    var addCalls: Int = 0
      private set
    var bootstrapCalls: Int = 0
      private set

    override fun observeCart(): Flow<List<CartItemWithToppings>> = flowOf(emptyList())
    override fun observeCartBadgeCount(): Flow<Int> = flowOf(0)
    override suspend fun addCustomizedItem(
      product: ProductDto,
      variant: ProductVariantDto,
      crust: CrustOptionDto,
      toppings: List<ToppingDto>,
      quantity: Int,
    ): CartSyncResult {
      addCalls++
      return CartSyncResult(false, 0)
    }
    override suspend fun updateCustomizedItem(
      existingItemId: String,
      product: ProductDto,
      variant: ProductVariantDto,
      crust: CrustOptionDto,
      toppings: List<ToppingDto>,
      quantity: Int,
    ) = error("Legacy edit path must not be used")
    override suspend fun updateQuantity(itemId: String, newQuantity: Int): CartSyncResult = error("unused")
    override suspend fun removeItem(itemId: String): CartSyncResult = error("unused")
    override suspend fun removeItemWithSnapshot(itemId: String) =
      unused<Pair<com.example.cart.RemovedCartItemSnapshot?, CartSyncResult>>()
    override suspend fun restoreRemovedItem(snapshot: com.example.cart.RemovedCartItemSnapshot): CartSyncResult =
      error("unused")
    override suspend fun clearCart(): CartSyncResult = error("unused")
    override suspend fun bootstrapFromServer(): CartSyncResult {
      bootstrapCalls++
      return CartSyncResult(false, 0)
    }
    override suspend fun syncAndValidateCart(persistToServer: Boolean): CartSyncResult = error("unused")

    private fun <T> unused(): T = error("unused")
  }
}

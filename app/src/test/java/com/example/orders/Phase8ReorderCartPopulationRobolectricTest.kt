package com.example.orders

import com.example.cart.CartRepository
import com.example.cart.CartSyncResult
import com.example.cart.CartViewModel
import com.example.cart.ReorderCartBridge
import com.example.core.database.CartItemWithToppings
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.pizzora.contracts.CartCheeseViewDto
import com.pizzora.contracts.CartHalfAndHalfViewDto
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CartSauceViewDto
import com.pizzora.contracts.CartStandardToppingViewDto
import com.pizzora.contracts.CartToppingViewDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.ReplaceCartRequest
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase8ReorderCartPopulationRobolectricTest {

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

  @Test
  fun `reorder confirmation preserves all phase 8 ids through cart replace and repository refresh`() {
    var capturedRequest: ReplaceCartRequest? = null
    val existingItem = CartItemViewDto(
      id = "existing-1",
      productId = "existing-product",
      productName = "Existing Pizza",
      isVeg = true,
      variantId = "existing-variant",
      basePricePaise = 40_000L,
      crustId = "existing-crust",
      crustName = "Thin",
      crustExtraPricePaise = 0L,
      toppings = listOf(
        CartToppingViewDto("existing-extra", "Existing Extra", true, 0L, true),
      ),
      quantity = 1,
      unitPricePaise = 40_000L,
      lineTotalPaise = 40_000L,
      isAvailable = true,
      cheese = CartCheeseViewDto("existing-cheese", "Existing Cheese", 0L, true),
      sauce = CartSauceViewDto("existing-sauce", "Existing Sauce", 0L, true),
      standardToppings = listOf(
        CartStandardToppingViewDto("existing-std", "Existing Standard", true, 0L, true, false, true),
      ),
      halfAndHalf = CartHalfAndHalfViewDto(
        halfAndHalfConfigId = "existing-hah",
        name = "Existing Half & Half",
        leftToppingIds = listOf("existing-left"),
        rightToppingIds = listOf("existing-right"),
        leftToppings = emptyList(),
        rightToppings = emptyList(),
        halfAndHalfSurchargePaise = 0L,
        isAvailable = true,
      ),
    )
    val engine = MockEngine { request ->
      when {
        request.method == HttpMethod.Get && request.url.encodedPath == "/api/v1/cart" -> respond(
          content = json.encodeToString(
            CartResponseDto(
              cartId = "cart-1",
              items = listOf(existingItem),
              subtotalPaise = 40_000L,
              totalItemCount = 1,
              allAvailable = true,
              unavailableCount = 0,
              updatedAt = "2026-11-27T12:00:00Z",
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        request.method == HttpMethod.Put && request.url.encodedPath == "/api/v1/cart" -> {
          capturedRequest = json.decodeFromString(bodyAsString(request.body))
          respond(
            content = json.encodeToString(
              CartResponseDto(
                cartId = "cart-1",
                items = emptyList(),
                subtotalPaise = 0L,
                totalItemCount = 0,
                allAvailable = true,
                unavailableCount = 0,
                updatedAt = "2026-11-27T12:01:00Z",
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
      }
    }
    val api = PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(session), engine)
    val repository = CountingCartRepository()
    val cartVm = CartViewModel(repository, reorderApiClientProvider = { api })

    val result = runBlocking {
      cartVm.addAvailableReorderItems(
        listOf(
          CartItemInputDto(
            productId = "reordered-product",
            variantId = "reordered-variant",
            crustId = "reordered-crust",
            toppingIds = listOf("reordered-extra"),
            quantity = 2,
            cheeseId = "reordered-cheese",
            sauceId = "reordered-sauce",
            standardToppingIds = listOf("reordered-std"),
            halfAndHalfConfigId = "reordered-hah",
            halfAndHalfLeftToppingIds = listOf("reordered-left"),
            halfAndHalfRightToppingIds = listOf("reordered-right"),
          ),
        ),
      )
    }

    assertFalse(result.isOffline)
    assertEquals(0, result.unavailableCount)
    assertNull(result.errorMessage)
    assertEquals(1, repository.bootstrapCalls)

    val request = capturedRequest
    assertNotNull(request)
    assertEquals(2, request!!.items.size)

    val preserved = request.items.single { it.id == "existing-1" }
    assertEquals("existing-variant", preserved.variantId)
    assertEquals("existing-crust", preserved.crustId)
    assertEquals("existing-cheese", preserved.cheeseId)
    assertEquals("existing-sauce", preserved.sauceId)
    assertEquals(listOf("existing-std"), preserved.standardToppingIds)
    assertEquals("existing-hah", preserved.halfAndHalfConfigId)
    assertEquals(listOf("existing-left"), preserved.halfAndHalfLeftToppingIds)
    assertEquals(listOf("existing-right"), preserved.halfAndHalfRightToppingIds)
    assertEquals(listOf("existing-extra"), preserved.toppingIds)

    val reordered = request.items.single { it.productId == "reordered-product" }
    assertEquals("reordered-variant", reordered.variantId)
    assertEquals("reordered-crust", reordered.crustId)
    assertEquals("reordered-cheese", reordered.cheeseId)
    assertEquals("reordered-sauce", reordered.sauceId)
    assertEquals(listOf("reordered-std"), reordered.standardToppingIds)
    assertEquals("reordered-hah", reordered.halfAndHalfConfigId)
    assertEquals(listOf("reordered-left"), reordered.halfAndHalfLeftToppingIds)
    assertEquals(listOf("reordered-right"), reordered.halfAndHalfRightToppingIds)
    assertEquals(listOf("reordered-extra"), reordered.toppingIds)
    assertEquals(2, reordered.quantity)

    ReorderCartBridge.unregister(cartVm)
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

  private class CountingCartRepository : CartRepository {
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
    ): CartSyncResult = unused()

    override suspend fun updateCustomizedItem(
      existingItemId: String,
      product: ProductDto,
      variant: ProductVariantDto,
      crust: CrustOptionDto,
      toppings: List<ToppingDto>,
      quantity: Int,
    ): com.example.cart.CartEditResult = unused()

    override suspend fun updateQuantity(itemId: String, newQuantity: Int): CartSyncResult = unused()
    override suspend fun removeItem(itemId: String): CartSyncResult = unused()
    override suspend fun removeItemWithSnapshot(itemId: String) =
      unused<Pair<com.example.cart.RemovedCartItemSnapshot?, CartSyncResult>>()

    override suspend fun restoreRemovedItem(snapshot: com.example.cart.RemovedCartItemSnapshot): CartSyncResult = unused()
    override suspend fun clearCart(): CartSyncResult = unused()

    override suspend fun bootstrapFromServer(): CartSyncResult {
      bootstrapCalls++
      return CartSyncResult(isOffline = false, unavailableCount = 0)
    }

    override suspend fun syncAndValidateCart(persistToServer: Boolean): CartSyncResult = unused()

    private fun <T> unused(): T = error("unused in Phase 8 cart population test")
  }
}

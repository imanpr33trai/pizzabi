package com.example.cart

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.CartItemWithToppings
import com.example.core.di.AppContainer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class Phase8BuilderCartViewModelRobolectricTest {

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

  private fun serverLine(
    id: String,
    productId: String,
    cheeseId: String,
    sauceId: String,
    standardId: String,
    halfId: String,
    extraId: String,
    halfLeft: String,
    halfRight: String,
  ) = CartItemViewDto(
    id = id,
    productId = productId,
    productName = "Saved Pizza $id",
    isVeg = true,
    variantId = "${id}-variant",
    sizeCode = SizeCode.REGULAR,
    basePricePaise = 10_000L,
    crustId = "${id}-crust",
    crustName = "Classic",
    crustExtraPricePaise = 0L,
    toppings = listOf(CartToppingViewDto(extraId, "Saved Extra", true, 0L, true)),
    quantity = 1,
    unitPricePaise = 15_000L,
    lineTotalPaise = 15_000L,
    isAvailable = true,
    cheese = CartCheeseViewDto(cheeseId, "Saved Cheese", 0L, true),
    sauce = CartSauceViewDto(sauceId, "Saved Sauce", 0L, true),
    standardToppings = listOf(
      CartStandardToppingViewDto(standardId, "Saved Standard", true, 0L, true, false, true),
    ),
    halfAndHalf = CartHalfAndHalfViewDto(
      halfAndHalfConfigId = halfId,
      name = "Saved Half & Half",
      leftToppingIds = listOf(halfLeft),
      rightToppingIds = listOf(halfRight),
      leftToppings = emptyList(),
      rightToppings = emptyList(),
      halfAndHalfSurchargePaise = 0L,
      isAvailable = true,
    ),
  )

  private fun builderInput(
    id: String? = null,
    productId: String = "prod-8",
    variantId: String = "var-regular",
    crustId: String = "crust-classic",
    quantity: Int = 2,
  ) = CartItemInputDto(
    id = id,
    productId = productId,
    variantId = variantId,
    crustId = crustId,
    toppingIds = listOf("top-2"),
    quantity = quantity,
    clientUnitPricePaise = 15_000L,
    cheeseId = "cheese-moz",
    sauceId = "sauce-tomato",
    standardToppingIds = listOf("std-default", "std-extra"),
    halfAndHalfConfigId = "hh-one",
    halfAndHalfLeftToppingIds = listOf("top-1"),
    halfAndHalfRightToppingIds = listOf("top-3"),
  )

  private fun cartResponse(items: List<CartItemViewDto>) = CartResponseDto(
    cartId = "cart-1",
    items = items,
    subtotalPaise = items.sumOf { it.lineTotalPaise },
    totalItemCount = items.sumOf { it.quantity },
    allAvailable = true,
    unavailableCount = 0,
    updatedAt = "2026-11-27T12:00:00Z",
  )

  private fun recordingApi(
    getItems: List<CartItemViewDto>,
    capturedRequests: MutableList<ReplaceCartRequest>,
    replaceCalls: () -> Unit = {},
    failGet: Boolean = false,
  ): PizzoraApiClient {
    val engine = MockEngine { request ->
      when {
        request.method == HttpMethod.Get && request.url.encodedPath == "/api/v1/cart" -> {
          if (failGet) {
            respond(
              content = "{\"error\":\"network down\"}",
              status = HttpStatusCode.InternalServerError,
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
          } else {
            respond(
              content = json.encodeToString(cartResponse(getItems)),
              status = HttpStatusCode.OK,
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
          }
        }
        request.method == HttpMethod.Put && request.url.encodedPath == "/api/v1/cart" -> {
          replaceCalls()
          capturedRequests.add(json.decodeFromString(bodyAsString(request.body)))
          respond(
            content = json.encodeToString(cartResponse(getItems)),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
      }
    }
    return PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(session), engine)
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
    var addCalls: Int = 0
      private set
    var updateCalls: Int = 0
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
      return unused()
    }

    override suspend fun updateCustomizedItem(
      existingItemId: String,
      product: ProductDto,
      variant: ProductVariantDto,
      crust: CrustOptionDto,
      toppings: List<ToppingDto>,
      quantity: Int,
    ): CartEditResult {
      updateCalls++
      return unused()
    }

    override suspend fun updateQuantity(itemId: String, newQuantity: Int): CartSyncResult = unused()
    override suspend fun removeItem(itemId: String): CartSyncResult = unused()
    override suspend fun removeItemWithSnapshot(itemId: String) =
      unused<Pair<RemovedCartItemSnapshot?, CartSyncResult>>()

    override suspend fun restoreRemovedItem(snapshot: RemovedCartItemSnapshot): CartSyncResult = unused()
    override suspend fun clearCart(): CartSyncResult = unused()

    override suspend fun bootstrapFromServer(): CartSyncResult {
      bootstrapCalls++
      return CartSyncResult(isOffline = false, unavailableCount = 0)
    }

    override suspend fun syncAndValidateCart(persistToServer: Boolean): CartSyncResult = unused()

    private fun <T> unused(): T = error("Legacy repository path must not be used by Phase 8 builder wiring")
  }

  private fun waitForCondition(testScope: TestScope, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000
    while (true) {
      testScope.advanceUntilIdle()
      if (condition()) return
      if (System.currentTimeMillis() > deadline) {
        throw AssertionError("Timed out waiting for async CartViewModel completion")
      }
      Thread.sleep(10)
    }
  }

  @Test
  fun `add with modifiers preserves every phase 8 id in replace request`() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    val repository = CountingCartRepository()
    val existing = serverLine(
      id = "existing-1", productId = "existing-product", cheeseId = "existing-cheese",
      sauceId = "existing-sauce", standardId = "existing-std", halfId = "existing-half",
      extraId = "existing-extra", halfLeft = "existing-left", halfRight = "existing-right",
    )
    val captured = mutableListOf<ReplaceCartRequest>()
    var putCalls = 0
    val api = recordingApi(listOf(existing), captured, { putCalls++ })
    val vm = CartViewModel(repository, { api })
    var result: Pair<Boolean, String?>? = null

    try {
      vm.addCartItemWithModifiers(builderInput()) { ok, error -> result = ok to error }
      waitForCondition(this) { result != null }

      assertEquals(true to null, result)
      assertEquals(1, putCalls)
      assertEquals(1, repository.bootstrapCalls)
      assertEquals(0, repository.addCalls)
      assertNull(vm.uiState.value.statusMessage)

      val request = captured.single()
      assertEquals(2, request.items.size)
      val preserved = request.items.single { it.id == "existing-1" }
      assertEquals("existing-1-variant", preserved.variantId)
      assertEquals("existing-1-crust", preserved.crustId)
      assertEquals("existing-cheese", preserved.cheeseId)
      assertEquals("existing-sauce", preserved.sauceId)
      assertEquals(listOf("existing-std"), preserved.standardToppingIds)
      assertEquals("existing-half", preserved.halfAndHalfConfigId)
      assertEquals(listOf("existing-left"), preserved.halfAndHalfLeftToppingIds)
      assertEquals(listOf("existing-right"), preserved.halfAndHalfRightToppingIds)
      assertEquals(listOf("existing-extra"), preserved.toppingIds)

      val added = request.items.single { it.productId == "prod-8" }
      assertNull(added.id)
      assertEquals("var-regular", added.variantId)
      assertEquals("crust-classic", added.crustId)
      assertEquals(2, added.quantity)
      assertEquals(15_000L, added.clientUnitPricePaise)
      assertEquals("cheese-moz", added.cheeseId)
      assertEquals("sauce-tomato", added.sauceId)
      assertEquals(listOf("std-default", "std-extra"), added.standardToppingIds)
      assertEquals("hh-one", added.halfAndHalfConfigId)
      assertEquals(listOf("top-1"), added.halfAndHalfLeftToppingIds)
      assertEquals(listOf("top-3"), added.halfAndHalfRightToppingIds)
      assertEquals(listOf("top-2"), added.toppingIds)
    } finally {
      ReorderCartBridge.unregister(vm)
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `edit with modifiers replaces only the matching server line`() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    val repository = CountingCartRepository()
    val first = serverLine(
      id = "line-1", productId = "existing-product", cheeseId = "existing-cheese",
      sauceId = "existing-sauce", standardId = "existing-std", halfId = "existing-half",
      extraId = "existing-extra", halfLeft = "existing-left", halfRight = "existing-right",
    )
    val second = serverLine(
      id = "line-2", productId = "second-product", cheeseId = "second-cheese",
      sauceId = "second-sauce", standardId = "second-std", halfId = "second-half",
      extraId = "second-extra", halfLeft = "second-left", halfRight = "second-right",
    )
    val captured = mutableListOf<ReplaceCartRequest>()
    val api = recordingApi(listOf(first, second), captured)
    val vm = CartViewModel(repository, { api })
    var result: Pair<Boolean, String?>? = null
    var committed: CartEditResult? = null
    val edited = builderInput(id = "line-1", quantity = 3)

    try {
      vm.updateCartItemWithModifiers(
        input = edited,
        onResult = { ok, error -> result = ok to error },
        onEditCommitted = { committed = it },
      )
      waitForCondition(this) { result != null }

      assertEquals(true to null, result)
      assertEquals(1, repository.bootstrapCalls)
      assertEquals(0, repository.updateCalls)
      assertNotNull(committed)
      assertEquals("line-1", committed!!.resultingItemId)
      assertEquals(3, committed!!.finalQuantity)
      assertEquals(0, committed!!.unavailableCount)

      val request = captured.single()
      assertEquals(2, request.items.size)
      assertEquals(edited, request.items.single { it.id == "line-1" })
      val untouched = request.items.single { it.id == "line-2" }
      assertEquals("second-cheese", untouched.cheeseId)
      assertEquals("second-sauce", untouched.sauceId)
      assertEquals(listOf("second-std"), untouched.standardToppingIds)
      assertEquals("second-half", untouched.halfAndHalfConfigId)
      assertEquals(listOf("second-left"), untouched.halfAndHalfLeftToppingIds)
      assertEquals(listOf("second-right"), untouched.halfAndHalfRightToppingIds)
      assertEquals(listOf("second-extra"), untouched.toppingIds)
    } finally {
      ReorderCartBridge.unregister(vm)
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `missing api client fails clearly without touching the cart repository`() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    val repository = CountingCartRepository()
    val vm = CartViewModel(repository, { null })
    var result: Pair<Boolean, String?>? = null

    try {
      vm.addCartItemWithModifiers(builderInput()) { ok, error -> result = ok to error }
      waitForCondition(this) { result != null }

      assertEquals(false, result!!.first)
      assertEquals("Your cart is unavailable right now. Please try again.", result!!.second)
      assertEquals(result!!.second, vm.uiState.value.statusMessage)
      assertEquals(0, repository.bootstrapCalls)
      assertEquals(0, repository.addCalls)
    } finally {
      ReorderCartBridge.unregister(vm)
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `get cart failure reports an error and never mutates the server cart`() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    val repository = CountingCartRepository()
    val captured = mutableListOf<ReplaceCartRequest>()
    var putCalls = 0
    val api = recordingApi(emptyList(), captured, { putCalls++ }, failGet = true)
    val vm = CartViewModel(repository, { api })
    var result: Pair<Boolean, String?>? = null

    try {
      vm.addCartItemWithModifiers(builderInput()) { ok, error -> result = ok to error }
      waitForCondition(this) { result != null }

      assertFalse(result!!.first)
      assertNotNull(result!!.second)
      assertTrue(result!!.second!!.isNotBlank())
      assertEquals(result!!.second, vm.uiState.value.statusMessage)
      assertEquals(0, putCalls)
      assertTrue(captured.isEmpty())
      assertEquals(0, repository.bootstrapCalls)
    } finally {
      ReorderCartBridge.unregister(vm)
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `edit without an existing item id fails before any network call`() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    val repository = CountingCartRepository()
    val captured = mutableListOf<ReplaceCartRequest>()
    val api = recordingApi(emptyList(), captured)
    val vm = CartViewModel(repository, { api })
    var result: Pair<Boolean, String?>? = null

    try {
      vm.updateCartItemWithModifiers(
        input = builderInput(id = null),
        onResult = { ok, error -> result = ok to error },
      )
      waitForCondition(this) { vm.uiState.value.statusMessage != null }

      assertEquals(false, result!!.first)
      assertEquals("Unable to update this item right now.", result!!.second)
      assertEquals(result!!.second, vm.uiState.value.statusMessage)
      assertTrue(captured.isEmpty())
      assertEquals(0, repository.bootstrapCalls)
    } finally {
      ReorderCartBridge.unregister(vm)
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `app container publishes authenticated api client on startup`() {
    val previous = Phase8ApiLocator.current
    try {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val container = AppContainer(context)
      assertSame(container.apiClient, Phase8ApiLocator.current)
    } finally {
      Phase8ApiLocator.current = previous
    }
  }
}

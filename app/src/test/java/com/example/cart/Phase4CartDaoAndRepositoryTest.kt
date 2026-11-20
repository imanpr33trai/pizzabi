package com.example.cart

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.CartEditMergeAction
import com.example.core.database.CartItemEntity
import com.example.core.database.CartItemToppingEntity
import com.example.core.database.PizzoraDatabase
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CartToppingViewDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductImageDto
import com.pizzora.contracts.ProductImageStatus
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.ReplaceCartRequest
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.ValidateCartRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class Phase4CartDaoAndRepositoryTest {

  private lateinit var db: PizzoraDatabase
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private val user1Session = AuthSession(
    accessToken = "token-user-1",
    refreshToken = "refresh-user-1",
    user = UserProfileDto(
      id = "user-1",
      email = "one@pizzora.test",
      emailVerified = true,
      fullName = "Customer One",
      phone = "9876543210",
      role = UserRole.CUSTOMER,
      isActive = true,
    ),
  )

  private val user2Session = AuthSession(
    accessToken = "token-user-2",
    refreshToken = "refresh-user-2",
    user = user1Session.user.copy(id = "user-2", email = "two@pizzora.test"),
  )

  private val margherita = ProductDto(
    id = "prod-marg",
    categoryId = "cat-classics",
    categoryName = "Classics",
    name = "Margherita Verace",
    description = "Fresh mozzarella & basil",
    isVeg = true,
    isActive = true,
    isTaxable = true,
    variants = listOf(
      ProductVariantDto("var-reg", "prod-marg", SizeCode.REGULAR, 29_900L, true),
      ProductVariantDto("var-med", "prod-marg", SizeCode.MEDIUM, 42_900L, true),
      ProductVariantDto("var-lrg", "prod-marg", SizeCode.LARGE, 54_900L, true),
    ),
    images = listOf(
      ProductImageDto(
        id = "img-1",
        productId = "prod-marg",
        objectKey = "products/marg.jpg",
        publicUrl = "https://cdn.example.com/marg.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 12_000L,
        status = ProductImageStatus.ACTIVE,
        sortOrder = 0,
        createdAt = "2026-11-10T00:00:00Z",
      ),
    ),
  )

  private val handCrust = CrustOptionDto("crust-hand", "Hand-Tossed", 0L, true)
  private val panCrust = CrustOptionDto("crust-pan", "Sourdough Pan", 5_000L, true)
  private val basilTopping = ToppingDto("top-basil", "Extra Basil", true, 2_500L, true)
  private val oliveTopping = ToppingDto("top-olive", "Black Olives", true, 3_500L, true)

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

  private fun createMirroringApiClient(
    tokenStorage: TokenStorage,
    failNetwork: Boolean = false,
  ): PizzoraApiClient {
    var serverItems = mutableListOf<CartItemViewDto>()
    val engine = MockEngine { req ->
      if (failNetwork) {
        respond("offline", HttpStatusCode.ServiceUnavailable)
      } else {
        when {
          req.url.encodedPath == "/v1/cart" && req.method == HttpMethod.Get -> {
            respond(
              content = json.encodeToString(
                CartResponseDto(
                  items = serverItems,
                  unavailableCount = 0,
                  updatedAt = "2026-11-10T00:00:00Z",
                ),
              ),
              status = HttpStatusCode.OK,
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
          }
          req.url.encodedPath == "/v1/cart" && req.method == HttpMethod.Put -> {
            val bodyStr = (req.body as? TextContent)?.text ?: "{}"
            val replaceReq = json.decodeFromString<ReplaceCartRequest>(bodyStr)
            serverItems = replaceReq.items.mapIndexed { idx, input ->
              val resolvedUnit = input.clientUnitPricePaise ?: 29_900L
              CartItemViewDto(
                id = input.id ?: "srv-${idx + 1}",
                productId = input.productId,
                productName = "Margherita Verace",
                isVeg = true,
                imageUrl = "https://cdn.example.com/marg.jpg",
                variantId = input.variantId,
                sizeCode = SizeCode.REGULAR,
                basePricePaise = 29_900L,
                crustId = input.crustId,
                crustName = "Hand-Tossed",
                crustExtraPricePaise = 0L,
                unitPricePaise = resolvedUnit,
                quantity = input.quantity,
                lineTotalPaise = resolvedUnit * input.quantity,
                isAvailable = true,
                toppings = input.toppingIds.map { tid ->
                  CartToppingViewDto(
                    toppingId = tid,
                    name = tid,
                    isVeg = true,
                    extraPricePaise = 2_500L,
                    isAvailable = true,
                  )
                },
              )
            }.toMutableList()
            respond(
              content = json.encodeToString(
                CartResponseDto(
                  items = serverItems,
                  unavailableCount = 0,
                  updatedAt = "2026-11-10T00:00:00Z",
                ),
              ),
              status = HttpStatusCode.OK,
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
          }
          req.url.encodedPath == "/v1/cart/validate" && req.method == HttpMethod.Post -> {
            val bodyStr = (req.body as? TextContent)?.text ?: "{}"
            val validateReq = json.decodeFromString<ValidateCartRequest>(bodyStr)
            val validated = validateReq.items.mapIndexed { idx, input ->
              val resolvedUnit = input.clientUnitPricePaise ?: 29_900L
              CartItemViewDto(
                id = input.id ?: "srv-${idx + 1}",
                productId = input.productId,
                productName = "Margherita Verace",
                isVeg = true,
                imageUrl = "https://cdn.example.com/marg.jpg",
                variantId = input.variantId,
                sizeCode = SizeCode.REGULAR,
                basePricePaise = 29_900L,
                crustId = input.crustId,
                crustName = "Hand-Tossed",
                crustExtraPricePaise = 0L,
                unitPricePaise = resolvedUnit,
                quantity = input.quantity,
                lineTotalPaise = resolvedUnit * input.quantity,
                isAvailable = true,
                toppings = input.toppingIds.map { tid ->
                  CartToppingViewDto(
                    toppingId = tid,
                    name = tid,
                    isVeg = true,
                    extraPricePaise = 2_500L,
                    isAvailable = true,
                  )
                },
              )
            }
            respond(
              content = json.encodeToString(
                CartResponseDto(
                  items = validated,
                  unavailableCount = 0,
                  updatedAt = "2026-11-10T00:00:00Z",
                ),
              ),
              status = HttpStatusCode.OK,
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
          }
          req.url.encodedPath == "/v1/cart" && req.method == HttpMethod.Delete -> {
            serverItems.clear()
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
          }
          else -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
      }
    }
    return PizzoraApiClient(
      baseUrl = "http://localhost:8080",
      tokenStorage = tokenStorage,
      engine = engine,
    )
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
  fun daoScenarioA_sameConfigEditUpdatesQuantityInPlaceWithoutDuplicate() = runBlocking {
    val dao = db.cartDao()
    val item = CartItemEntity(
      id = "item-a",
      userId = "user-1",
      productId = "prod-marg",
      productName = "Margherita Verace",
      imageUrl = "https://cdn.example.com/marg.jpg",
      variantId = "var-reg",
      sizeCode = SizeCode.REGULAR.name,
      crustId = "crust-hand",
      crustName = "Hand-Tossed",
      unitPricePaise = 32_400L,
      quantity = 1,
      updatedAtMs = 1_000L,
    )
    val topping = CartItemToppingEntity("item-a", "top-basil", "Extra Basil", 2_500L)
    dao.upsertItemWithToppings(item, listOf(topping))

    val result = dao.replaceOrMergeItemWithToppings(
      userId = "user-1",
      existingLocalId = "item-a",
      updatedItem = item.copy(quantity = 4, updatedAtMs = 2_000L),
      updatedToppings = listOf(topping),
    )

    assertEquals(CartEditMergeAction.UPDATED_IN_PLACE, result.action)
    assertEquals("item-a", result.resultingItemId)
    assertEquals(4, result.finalQuantity)
    assertFalse(result.wasQuantityCapped)

    val rows = dao.getCartItemsWithToppingsOnce("user-1")
    assertEquals(1, rows.size)
    assertEquals(4, rows.single().item.quantity)
    assertEquals(listOf("top-basil"), rows.single().toppings.map { it.toppingId })
  }

  @Test
  fun daoScenarioB_configMatchMergesIntoExistingAndCapsAt25DeletingOldItem() = runBlocking {
    val dao = db.cartDao()
    val itemA = CartItemEntity(
      id = "item-a",
      userId = "user-1",
      productId = "prod-marg",
      productName = "Margherita Verace",
      imageUrl = null,
      variantId = "var-reg",
      sizeCode = SizeCode.REGULAR.name,
      crustId = "crust-hand",
      crustName = "Hand-Tossed",
      unitPricePaise = 29_900L,
      quantity = 8,
      updatedAtMs = 1_000L,
    )
    val itemB = itemA.copy(
      id = "item-b",
      variantId = "var-med",
      sizeCode = SizeCode.MEDIUM.name,
      crustId = "crust-pan",
      crustName = "Sourdough Pan",
      unitPricePaise = 50_400L,
      quantity = 20,
      updatedAtMs = 1_500L,
    )
    val toppingB = CartItemToppingEntity("item-b", "top-basil", "Extra Basil", 2_500L)
    dao.upsertItemWithToppings(itemA, emptyList())
    dao.upsertItemWithToppings(itemB, listOf(toppingB))

    // Edit itemA to match itemB's configuration + topping with quantity 8 (20 + 8 = 28 -> capped 25)
    val result = dao.replaceOrMergeItemWithToppings(
      userId = "user-1",
      existingLocalId = "item-a",
      updatedItem = itemA.copy(
        variantId = "var-med",
        sizeCode = SizeCode.MEDIUM.name,
        crustId = "crust-pan",
        crustName = "Sourdough Pan",
        unitPricePaise = 50_400L,
        quantity = 8,
        updatedAtMs = 3_000L,
      ),
      updatedToppings = listOf(toppingB.copy(cartItemId = "item-a")),
    )

    assertEquals(CartEditMergeAction.MERGED_INTO_EXISTING, result.action)
    assertEquals("item-b", result.resultingItemId)
    assertEquals(25, result.finalQuantity)
    assertTrue(result.wasQuantityCapped)

    val rows = dao.getCartItemsWithToppingsOnce("user-1")
    assertEquals(1, rows.size)
    assertEquals("item-b", rows.single().item.id)
    assertEquals(25, rows.single().item.quantity)
    assertEquals(listOf("top-basil"), rows.single().toppings.map { it.toppingId })
  }

  @Test
  fun daoScenarioC_uniqueConfigReplacementZeroOrphanToppingsAndUnrelatedIntact() = runBlocking {
    val dao = db.cartDao()
    val itemA = CartItemEntity(
      id = "item-a",
      userId = "user-1",
      productId = "prod-marg",
      productName = "Margherita Verace",
      imageUrl = null,
      variantId = "var-reg",
      sizeCode = SizeCode.REGULAR.name,
      crustId = "crust-hand",
      crustName = "Hand-Tossed",
      unitPricePaise = 32_400L,
      quantity = 2,
      updatedAtMs = 1_000L,
    )
    val unrelated = CartItemEntity(
      id = "item-other",
      userId = "user-1",
      productId = "prod-other",
      productName = "Marinara Antica",
      imageUrl = null,
      variantId = "var-reg-2",
      sizeCode = SizeCode.REGULAR.name,
      crustId = "crust-hand",
      crustName = "Hand-Tossed",
      unitPricePaise = 24_900L,
      quantity = 1,
      updatedAtMs = 1_100L,
    )
    dao.upsertItemWithToppings(itemA, listOf(CartItemToppingEntity("item-a", "top-basil", "Extra Basil", 2_500L)))
    dao.upsertItemWithToppings(unrelated, emptyList())

    val result = dao.replaceOrMergeItemWithToppings(
      userId = "user-1",
      existingLocalId = "item-a",
      updatedItem = itemA.copy(
        variantId = "var-lrg",
        sizeCode = SizeCode.LARGE.name,
        crustId = "crust-pan",
        crustName = "Sourdough Pan",
        unitPricePaise = 63_400L,
        quantity = 3,
        updatedAtMs = 2_000L,
      ),
      updatedToppings = listOf(CartItemToppingEntity("item-a", "top-olive", "Black Olives", 3_500L)),
    )

    assertEquals(CartEditMergeAction.UPDATED_IN_PLACE, result.action)
    val rows = dao.getCartItemsWithToppingsOnce("user-1")
    assertEquals(2, rows.size)

    val updatedA = rows.first { it.item.id == "item-a" }
    assertEquals(SizeCode.LARGE.name, updatedA.item.sizeCode)
    assertEquals("Sourdough Pan", updatedA.item.crustName)
    assertEquals(listOf("top-olive"), updatedA.toppings.map { it.toppingId })

    // Verify no orphan top-basil row remains in cart_item_toppings
    val cursor = db.openHelper.writableDatabase.query(
      "SELECT COUNT(*) FROM cart_item_toppings WHERE cart_item_id = 'item-a' AND topping_id = 'top-basil'",
    )
    cursor.use {
      assertTrue(it.moveToFirst())
      assertEquals(0, it.getInt(0))
    }

    val untouched = rows.first { it.item.id == "item-other" }
    assertEquals("Marinara Antica", untouched.item.productName)
    assertEquals(1, untouched.item.quantity)
  }

  @Test
  fun daoScenarioD_missingItemReturnsNotFoundWithoutReviving() = runBlocking {
    val dao = db.cartDao()
    val storage = InMemoryTokenStorage(user1Session)
    val repo = NetworkCartRepository(
      apiClient = createMirroringApiClient(storage),
      cartDao = dao,
      tokenStorage = storage,
    )

    val missing = CartItemEntity(
      id = "missing-id",
      userId = "user-1",
      productId = "prod-marg",
      productName = "Margherita Verace",
      imageUrl = null,
      variantId = "var-reg",
      sizeCode = SizeCode.REGULAR.name,
      crustId = "crust-hand",
      crustName = "Hand-Tossed",
      unitPricePaise = 29_900L,
      quantity = 1,
      updatedAtMs = 1_000L,
    )
    val daoRes = dao.replaceOrMergeItemWithToppings("user-1", "missing-id", missing, emptyList())
    assertEquals(CartEditMergeAction.NOT_FOUND, daoRes.action)
    assertTrue(dao.getCartItemsWithToppingsOnce("user-1").isEmpty())

    val repoRes = repo.updateCustomizedItem(
      existingItemId = "missing-id",
      product = margherita,
      variant = margherita.variants.first(),
      crust = handCrust,
      toppings = listOf(basilTopping),
      quantity = 2,
    )
    assertNotNull(repoRes.errorMessage)
    assertTrue(dao.getCartItemsWithToppingsOnce("user-1").isEmpty())
  }

  @Test
  fun undoViewModelTokenGuardsPreventDuplicateExpiredWrongItemAndRestoreConcurrentMerge() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      val storage = InMemoryTokenStorage(user1Session)
      val dao = db.cartDao()
      val repo = NetworkCartRepository(
        apiClient = createMirroringApiClient(storage),
        cartDao = dao,
        tokenStorage = storage,
      )
      val vm = CartViewModel(repo)

      repo.addCustomizedItem(margherita, margherita.variants[0], handCrust, listOf(basilTopping), 1)
      repo.addCustomizedItem(margherita, margherita.variants[1], panCrust, listOf(oliveTopping), 2)
      advanceUntilIdle()

      val prompts = mutableListOf<CartUndoPrompt>()
      val job = backgroundScope.launch { vm.undoPrompts.collect { prompts.add(it) } }
      advanceUntilIdle()

      val items = dao.getCartItemsWithToppingsOnce("user-1")
      val itemReg = items.first { it.item.variantId == "var-reg" }
      val itemMed = items.first { it.item.variantId == "var-med" }

      // Repeated removal: remove reg, then remove med -> only latest token (med) can undo
      vm.removeItem(itemReg.item.id)
      advanceUntilIdle()
      vm.removeItem(itemMed.item.id)
      advanceUntilIdle()

      assertEquals(2, prompts.size)
      val tokenA = prompts[0].token
      val tokenB = prompts[1].token

      // Expired tokenA does NOT restore itemReg
      vm.undoRemoval(tokenA)
      advanceUntilIdle()
      assertTrue(dao.getCartItemsWithToppingsOnce("user-1").isEmpty())

      // Re-add an identical itemMed concurrently (qty 3) before undoing tokenB (qty 2) -> merges cleanly to qty 5!
      repo.addCustomizedItem(margherita, margherita.variants[1], panCrust, listOf(oliveTopping), 3)
      advanceUntilIdle()

      vm.undoRemoval(tokenB)
      advanceUntilIdle()
      val afterRestore = dao.getCartItemsWithToppingsOnce("user-1")
      assertEquals(1, afterRestore.size)
      assertEquals(5, afterRestore.single().item.quantity)

      // Duplicate invocation of same tokenB is ignored (stays 5, not 7)
      vm.undoRemoval(tokenB)
      advanceUntilIdle()
      assertEquals(5, dao.getCartItemsWithToppingsOnce("user-1").single().item.quantity)

      // Dismissing an undo token invalidates it
      val currentId = dao.getCartItemsWithToppingsOnce("user-1").single().item.id
      vm.removeItem(currentId)
      advanceUntilIdle()
      val tokenC = prompts.last().token
      vm.dismissUndo(tokenC)
      vm.undoRemoval(tokenC)
      advanceUntilIdle()
      assertTrue(dao.getCartItemsWithToppingsOnce("user-1").isEmpty())

      // Session isolation: snapshot captured under user1 cannot be restored under user2
      val foreignResult = run {
        storage.saveSession(user2Session)
        repo.restoreRemovedItem(prompts.last().snapshot)
      }
      assertEquals("Stale session discarded", foreignResult.errorMessage)
      assertTrue(dao.getCartItemsWithToppingsOnce("user-2").isEmpty())

      job.cancel()
    } finally {
      Dispatchers.resetMain()
    }
  }
}

package com.example.cart

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.SuccessResult
import com.example.catalog.CustomerCatalogViewModel
import com.example.catalog.NetworkCatalogRepository
import com.example.checkout.CheckoutQuoteUiState
import com.example.checkout.CheckoutQuoteViewModel
import com.example.checkout.CheckoutViewModel
import com.example.checkout.CouponPickerSheet
import com.example.checkout.NetworkPricingRepository
import com.example.core.database.CartItemEntity
import com.example.core.database.CartItemToppingEntity
import com.example.core.database.CartItemWithToppings
import com.example.core.database.PizzoraDatabase
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.navigation.CustomerRoleShell
import com.example.orders.NetworkOrderRepository
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.AvailableCouponsResponse
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CartToppingViewDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.CustomerCouponDto
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.PriceBreakdownDto
import com.pizzora.contracts.PricingQuoteResponse
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductImageDto
import com.pizzora.contracts.ProductImageStatus
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.PublicMenuResponse
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase4CartAndReEditRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var db: PizzoraDatabase
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private val customerSession = AuthSession(
    accessToken = "tok-cust",
    refreshToken = "ref-cust",
    user = UserProfileDto(
      id = "cust-1",
      email = "cust@pizzora.test",
      emailVerified = true,
      fullName = "Aarav Mehta",
      phone = "9876543210",
      role = UserRole.CUSTOMER,
      isActive = true,
    ),
  )

  private val margherita = ProductDto(
    id = "prod-marg",
    categoryId = "cat-classics",
    categoryName = "Classics",
    name = "Margherita Verace",
    description = "San Marzano tomatoes, fior di latte & sweet basil",
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
        id = "img-marg",
        productId = "prod-marg",
        objectKey = "products/marg.jpg",
        publicUrl = "https://cdn.example.com/marg.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 10_000L,
        status = ProductImageStatus.ACTIVE,
        sortOrder = 0,
        createdAt = "2026-11-10T00:00:00Z",
      ),
    ),
  )

  private val marinaraNoImg = ProductDto(
    id = "prod-mari",
    categoryId = "cat-classics",
    categoryName = "Classics",
    name = "Marinara Antica",
    description = "Garlic, oregano & cherry tomatoes",
    isVeg = true,
    isActive = true,
    isTaxable = true,
    variants = listOf(
      ProductVariantDto("var-mari-reg", "prod-mari", SizeCode.REGULAR, 24_900L, true),
      ProductVariantDto("var-mari-med", "prod-mari", SizeCode.MEDIUM, 36_900L, true),
    ),
    images = emptyList(),
  )

  private val crusts = listOf(
    CrustOptionDto("crust-hand", "Hand-Tossed", 0L, true),
    CrustOptionDto("crust-pan", "Sourdough Pan", 5_000L, true),
  )

  private val toppings = listOf(
    ToppingDto("top-basil", "Extra Basil", true, 2_500L, true),
    ToppingDto("top-olives", "Black Olives", true, 3_500L, true),
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

  private fun sampleQuoteResponse(): PricingQuoteResponse = PricingQuoteResponse(
    items = emptyList(),
    fulfillmentType = FulfillmentType.DELIVERY,
    breakdown = PriceBreakdownDto(
      subtotalPaise = 29_900L,
      taxableSubtotalPaise = 29_900L,
      discountPaise = 0L,
      taxableAmountPaise = 29_900L,
      deliveryFeePaise = 0L,
      gstBps = 500,
      taxPaise = 1_496L,
      cgstPaise = 748L,
      sgstPaise = 748L,
      totalPaise = 31_396L,
    ),
    gstBps = 500,
  )

  private fun buildTestShellHarness(
    initialCartItems: List<Pair<CartItemEntity, List<CartItemToppingEntity>>>,
    menuProducts: List<ProductDto> = listOf(margherita, marinaraNoImg),
    menuCrusts: List<CrustOptionDto> = crusts,
    menuToppings: List<ToppingDto> = toppings,
    couponsFailure: Boolean = false,
    onPutCart: () -> Unit = {},
  ): Triple<CustomerCatalogViewModel, CartViewModel, CheckoutQuoteViewModel> =
    buildFullTestShellHarness(
      initialCartItems = initialCartItems,
      menuProducts = menuProducts,
      menuCrusts = menuCrusts,
      menuToppings = menuToppings,
      couponsFailure = couponsFailure,
      onPutCart = onPutCart,
    ).let { Triple(it.first, it.second, it.third) }

  private data class FullTestShellHarness(
    val first: CustomerCatalogViewModel,
    val second: CartViewModel,
    val third: CheckoutQuoteViewModel,
    val checkoutVm: CheckoutViewModel,
  )

  private fun buildFullTestShellHarness(
    initialCartItems: List<Pair<CartItemEntity, List<CartItemToppingEntity>>>,
    menuProducts: List<ProductDto> = listOf(margherita, marinaraNoImg),
    menuCrusts: List<CrustOptionDto> = crusts,
    menuToppings: List<ToppingDto> = toppings,
    couponsFailure: Boolean = false,
    onPutCart: () -> Unit = {},
  ): FullTestShellHarness {
    val storage = InMemoryTokenStorage(customerSession)
    val productsById = menuProducts.associateBy { it.id }
    val variantsById = menuProducts.flatMap { it.variants }.associateBy { it.id }
    val crustsById = menuCrusts.associateBy { it.id }
    val toppingsById = menuToppings.associateBy { it.id }

    val seededDtos = initialCartItems.map { (e, tops) ->
      CartItemViewDto(
        id = e.id,
        productId = e.productId,
        productName = e.productName,
        isVeg = true,
        imageUrl = e.imageUrl,
        variantId = e.variantId,
        sizeCode = runCatching { SizeCode.valueOf(e.sizeCode) }.getOrDefault(SizeCode.REGULAR),
        basePricePaise = e.unitPricePaise,
        crustId = e.crustId,
        crustName = e.crustName,
        crustExtraPricePaise = 0L,
        unitPricePaise = e.unitPricePaise,
        quantity = e.quantity,
        lineTotalPaise = e.unitPricePaise * e.quantity,
        isAvailable = e.isAvailable,
        toppings = tops.map {
          CartToppingViewDto(
            toppingId = it.toppingId,
            name = it.toppingName,
            isVeg = true,
            extraPricePaise = it.extraPricePaise,
            isAvailable = true,
          )
        },
      )
    }
    var serverCart = seededDtos.toMutableList()

    val engine = MockEngine { req ->
      when {
        req.url.encodedPath == "/api/v1/catalog/menu" -> {
          val menu = PublicMenuResponse(
            storeName = "PIZZORA Artisanal Kitchen",
            isAcceptingOrders = true,
            gstBps = 500,
            categories = listOf(CategoryDto("cat-classics", "Classics", 0, true, "")),
            products = menuProducts,
            crusts = menuCrusts,
            toppings = menuToppings,
          )
          respond(
            json.encodeToString(menu),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        req.url.encodedPath == "/api/v1/cart" && req.method == HttpMethod.Get -> {
          respond(
            json.encodeToString(
              CartResponseDto(
                items = serverCart,
                unavailableCount = serverCart.count { !it.isAvailable },
                updatedAt = "2026-11-10T00:00:00Z",
              ),
            ),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        req.url.encodedPath == "/api/v1/cart" && req.method == HttpMethod.Put -> {
          onPutCart()
          val bodyText = (req.body as? TextContent)?.text ?: "{}"
          val replaceReq = json.decodeFromString<ReplaceCartRequest>(bodyText)
          serverCart = replaceReq.items.mapIndexed { idx, input ->
            val p = productsById[input.productId]
            val v = variantsById[input.variantId]
            val c = crustsById[input.crustId]
            val ts = input.toppingIds.mapNotNull { toppingsById[it] }
            val unit = input.clientUnitPricePaise
              ?: ((v?.pricePaise ?: 29_900L) + (c?.extraPricePaise ?: 0L) + ts.sumOf { it.extraPricePaise })
            CartItemViewDto(
              id = input.id ?: "srv-${idx + 1}",
              productId = input.productId,
              productName = p?.name ?: "Pizza",
              isVeg = p?.isVeg ?: true,
              imageUrl = p?.images?.firstOrNull()?.publicUrl,
              variantId = input.variantId,
              sizeCode = v?.sizeCode ?: SizeCode.REGULAR,
              basePricePaise = v?.pricePaise ?: 29_900L,
              crustId = input.crustId,
              crustName = c?.name ?: "Hand-Tossed",
              crustExtraPricePaise = c?.extraPricePaise ?: 0L,
              unitPricePaise = unit,
              quantity = input.quantity,
              lineTotalPaise = unit * input.quantity,
              isAvailable = true,
              toppings = ts.map { CartToppingViewDto(it.id, it.name, it.isVeg, it.extraPricePaise, true) },
            )
          }.toMutableList()
          respond(
            json.encodeToString(
              CartResponseDto(
                items = serverCart,
                unavailableCount = 0,
                updatedAt = "2026-11-10T00:00:00Z",
              ),
            ),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        req.url.encodedPath == "/api/v1/cart/validate" && req.method == HttpMethod.Post -> {
          val bodyText = (req.body as? TextContent)?.text ?: "{}"
          val valReq = json.decodeFromString<ValidateCartRequest>(bodyText)
          val validated = valReq.items.mapIndexed { idx, input ->
            val p = productsById[input.productId]
            val v = variantsById[input.variantId]
            val c = crustsById[input.crustId]
            val ts = input.toppingIds.mapNotNull { toppingsById[it] }
            val unit = input.clientUnitPricePaise
              ?: ((v?.pricePaise ?: 29_900L) + (c?.extraPricePaise ?: 0L) + ts.sumOf { it.extraPricePaise })
            CartItemViewDto(
              id = input.id ?: "srv-${idx + 1}",
              productId = input.productId,
              productName = p?.name ?: "Pizza",
              isVeg = p?.isVeg ?: true,
              imageUrl = p?.images?.firstOrNull()?.publicUrl,
              variantId = input.variantId,
              sizeCode = v?.sizeCode ?: SizeCode.REGULAR,
              basePricePaise = v?.pricePaise ?: 29_900L,
              crustId = input.crustId,
              crustName = c?.name ?: "Hand-Tossed",
              crustExtraPricePaise = c?.extraPricePaise ?: 0L,
              unitPricePaise = unit,
              quantity = input.quantity,
              lineTotalPaise = unit * input.quantity,
              isAvailable = true,
              toppings = ts.map { CartToppingViewDto(it.id, it.name, it.isVeg, it.extraPricePaise, true) },
            )
          }
          respond(
            json.encodeToString(
              CartResponseDto(
                items = validated,
                unavailableCount = 0,
                updatedAt = "2026-11-10T00:00:00Z",
              ),
            ),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        req.url.encodedPath == "/api/v1/cart" && req.method == HttpMethod.Delete -> {
          serverCart.clear()
          respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        req.url.encodedPath == "/api/v1/coupons/available" -> {
          if (couponsFailure) {
            respond("{\"message\":\"Coupon service down\"}", HttpStatusCode.InternalServerError)
          } else {
            val resp = AvailableCouponsResponse(
              coupons = listOf(
                CustomerCouponDto(
                  id = "cpn-1",
                  code = "PIZZA20",
                  discountType = DiscountType.PERCENT_BPS,
                  discountValue = 2000L,
                  maxDiscountPaise = 10_000L,
                  minOrderPaise = 20_000L,
                  maxUsesPerUser = 2,
                  userUsageCount = 0,
                  remainingUserUses = 2,
                  startsAt = "2026-11-01T00:00:00Z",
                  endsAt = "2026-12-01T00:00:00Z",
                  isEligible = true,
                  estimatedDiscountPaise = 5_980L,
                  ineligibleReason = null,
                ),
                CustomerCouponDto(
                  id = "cpn-2",
                  code = "FEAST999",
                  discountType = DiscountType.FLAT_PAISE,
                  discountValue = 15_000L,
                  minOrderPaise = 99_900L,
                  maxUsesPerUser = 1,
                  userUsageCount = 0,
                  remainingUserUses = 1,
                  startsAt = "2026-11-01T00:00:00Z",
                  endsAt = "2026-12-01T00:00:00Z",
                  isEligible = false,
                  estimatedDiscountPaise = 0L,
                  ineligibleReason = "Add ₹700.00 more to unlock",
                ),
              ),
            )
            respond(
              json.encodeToString(resp),
              HttpStatusCode.OK,
              headersOf(HttpHeaders.ContentType, "application/json"),
            )
          }
        }
        req.url.encodedPath == "/api/v1/pricing/quote" -> {
          respond(
            json.encodeToString(sampleQuoteResponse()),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
      }
    }

    val apiClient = PizzoraApiClient(
      baseUrl = "http://localhost:8080",
      tokenStorage = storage,
      engine = engine,
    )
    runBlocking {
      db.cartDao().replaceAllItemsForUser("cust-1", initialCartItems)
    }

    val catalogVm = CustomerCatalogViewModel(NetworkCatalogRepository(apiClient, db.catalogCacheDao()))
    val cartVm = CartViewModel(NetworkCartRepository(apiClient, db.cartDao(), storage))
    val quoteVm = CheckoutQuoteViewModel(NetworkPricingRepository(apiClient))
    val checkoutVm = CheckoutViewModel(NetworkOrderRepository(apiClient))
    return FullTestShellHarness(catalogVm, cartVm, quoteVm, checkoutVm)
  }

  private fun sampleItem(
    id: String = "item-1",
    productId: String = "prod-marg",
    name: String = "Margherita Verace",
    imageUrl: String? = "https://cdn.example.com/marg.jpg",
    variantId: String = "var-reg",
    sizeCode: SizeCode = SizeCode.REGULAR,
    crustId: String = "crust-hand",
    crustName: String = "Hand-Tossed",
    unitPricePaise: Long = 29_900L,
    quantity: Int = 1,
    isAvailable: Boolean = true,
  ) = CartItemEntity(
    id = id,
    userId = "cust-1",
    productId = productId,
    productName = name,
    imageUrl = imageUrl,
    variantId = variantId,
    sizeCode = sizeCode.name,
    crustId = crustId,
    crustName = crustName,
    unitPricePaise = unitPricePaise,
    quantity = quantity,
    isAvailable = isAvailable,
    updatedAtMs = 1_000L,
  )

  private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
      composeTestRule.waitForIdle()
      if (condition()) return
      if (System.currentTimeMillis() > deadline) {
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for condition")
      }
      Thread.sleep(20)
    }
  }

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    Coil.setImageLoader(
      ImageLoader.Builder(context)
        .components {
          add(
            Interceptor { chain ->
              val url = chain.request.data.toString()
              if (url.contains("broken")) {
                ErrorResult(
                  drawable = null,
                  request = chain.request,
                  throwable = IOException("Simulated image 404"),
                )
              } else {
                SuccessResult(
                  drawable = ColorDrawable(Color.rgb(200, 62, 43)),
                  request = chain.request,
                  dataSource = DataSource.MEMORY,
                )
              }
            },
          )
        }
        .build(),
    )
    db = Room.inMemoryDatabaseBuilder(context, PizzoraDatabase::class.java)
      .allowMainThreadQueries()
      .addCallback(PizzoraDatabase.FOREIGN_KEY_CALLBACK)
      .build()
  }

  @After
  fun tearDown() {
    Coil.reset()
    db.close()
  }

  @Test
  fun cartDisplaysThumbnailsForValidUrlsAndWarmFallbackWhenMissing() {
    val items = listOf(
      CartItemWithToppings(
        item = sampleItem(id = "with-img", imageUrl = "https://cdn.example.com/marg.jpg"),
        toppings = emptyList(),
      ),
      CartItemWithToppings(
        item = sampleItem(id = "broken-img", productId = "prod-marg", imageUrl = "https://cdn.example.com/broken.jpg"),
        toppings = emptyList(),
      ),
      CartItemWithToppings(
        item = sampleItem(id = "no-img", productId = "prod-mari", name = "Marinara Antica", imageUrl = null),
        toppings = emptyList(),
      ),
    )

    composeTestRule.setContent {
      PizzoraTheme {
        CartScreen(
          uiState = CartUiState(items = items, subtotalPaise = 89_700L, totalItemCount = 3),
          onUpdateQuantity = { _, _ -> },
          onRemoveItem = {},
          onClearCart = {},
          onValidateAndSync = {},
          onBrowseMenu = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("cart_item_image_with-img")
      .assertIsDisplayed()
      .assertWidthIsAtLeast(64.dp)
      .assertHeightIsAtLeast(64.dp)
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("cart_item_broken-img"))
    composeTestRule.onNodeWithTag("cart_item_image_fallback_broken-img").assertIsDisplayed()
    composeTestRule.onAllNodesWithTag("cart_item_image_broken-img").assertCountEquals(0)
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("cart_item_no-img"))
    composeTestRule.onNodeWithTag("cart_item_image_fallback_no-img").assertIsDisplayed()
  }

  @Test
  fun customPizzaReEditEndToEndInShellRestoresConfigUpdatesCartAndRestoresFocus() {
    val seedItem = sampleItem(
      id = "edit-1",
      variantId = "var-reg",
      sizeCode = SizeCode.REGULAR,
      crustId = "crust-hand",
      crustName = "Hand-Tossed",
      unitPricePaise = 32_400L,
      quantity = 2,
    )
    val seedToppings = listOf(CartItemToppingEntity("edit-1", "top-basil", "Extra Basil", 2_500L))
    val (catalogVm, cartVm, quoteVm) = buildTestShellHarness(listOf(seedItem to seedToppings))

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerRoleShell(
          session = customerSession,
          catalogViewModel = catalogVm,
          cartViewModel = cartVm,
          checkoutQuoteViewModel = quoteVm,
          onSignOut = {},
        )
      }
    }

    // Navigate to Cart tab
    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("cart_edit_edit-1").fetchSemanticsNodes().isNotEmpty()
    }

    // Open Edit Mode from Cart item
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("cart_edit_edit-1"))
    composeTestRule.onNodeWithTag("cart_edit_edit-1").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("pizza_customizer_sheet").fetchSemanticsNodes().isNotEmpty() &&
        composeTestRule.onAllNodesWithTag("builder_edit_mode_badge").fetchSemanticsNodes().isNotEmpty()
    }

    // Shell bottom bar & top bar are hidden while full-screen Edit Builder is open
    composeTestRule.onAllNodesWithTag("customer_tab_cart").assertCountEquals(0)
    composeTestRule.onNodeWithTag("builder_edit_mode_badge").assertIsDisplayed()
    composeTestRule.onNodeWithTag("size_option_REGULAR").assertIsSelected()
    composeTestRule.onNodeWithTag("customizer_qty_value").assertTextContains("2")
    composeTestRule.onNodeWithTag("add_to_cart_button").assertTextContains("Update Item · ₹648.00")

    // Change Size to Medium (429), Sourdough Pan (50), qty 3, keep Extra Basil (25) -> 504 * 3 = ₹1512.00
    composeTestRule.onNodeWithTag("size_option_MEDIUM").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("crust_option_crust-pan").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("customizer_qty_plus").performClick()
    composeTestRule.onNodeWithTag("add_to_cart_button").assertTextContains("Update Item · ₹1512.00")

    composeTestRule.onNodeWithTag("add_to_cart_button").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("pizza_customizer_sheet").fetchSemanticsNodes().isEmpty()
    }

    // Returns to Cart, shows update snackbar, updated config visible, and focus restored to originating card
    composeTestRule.onNodeWithText("Margherita Verace updated", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("cart_qty_value_edit-1").assertTextContains("3")
    composeTestRule.onNodeWithText("Medium • Sourdough Pan").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cart_item_edit-1").assertIsFocused()
  }

  @Test
  fun reEditDuplicateMergeCapsAt25AndEmitsSnackbar() {
    val itemA = sampleItem(
      id = "edit-a",
      variantId = "var-reg",
      sizeCode = SizeCode.REGULAR,
      crustId = "crust-hand",
      crustName = "Hand-Tossed",
      unitPricePaise = 29_900L,
      quantity = 10,
    )
    val itemB = sampleItem(
      id = "edit-b",
      variantId = "var-med",
      sizeCode = SizeCode.MEDIUM,
      crustId = "crust-hand",
      crustName = "Hand-Tossed",
      unitPricePaise = 42_900L,
      quantity = 20,
    )
    val (catalogVm, cartVm, quoteVm) = buildTestShellHarness(listOf(itemA to emptyList(), itemB to emptyList()))

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerRoleShell(
          session = customerSession,
          catalogViewModel = catalogVm,
          cartViewModel = cartVm,
          checkoutQuoteViewModel = quoteVm,
          onSignOut = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("cart_edit_edit-a").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("cart_edit_edit-a"))
    composeTestRule.onNodeWithTag("cart_edit_edit-a").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("pizza_customizer_sheet").fetchSemanticsNodes().isNotEmpty() &&
        composeTestRule.onAllNodesWithTag("size_option_MEDIUM").fetchSemanticsNodes().isNotEmpty()
    }

    // Change itemA to MEDIUM (matches itemB -> 20 + 10 = 30 -> capped at 25)
    composeTestRule.onNodeWithTag("size_option_MEDIUM").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("add_to_cart_button").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("pizza_customizer_sheet").fetchSemanticsNodes().isEmpty()
    }

    composeTestRule.onNodeWithText("Combined with matching item (capped at max 25)").assertIsDisplayed()
    assertEquals(1, cartVm.uiState.value.items.size)
    assertEquals(25, cartVm.uiState.value.items.single().item.quantity)
    composeTestRule.onNodeWithTag("cart_item_edit-b").assertIsFocused()
  }

  @Test
  fun discontinuedProductInEditModeGuardsUpdateAndPreservesOriginalCartItem() {
    val itemDiscontinued = sampleItem(
      id = "disc-1",
      productId = "prod-retired",
      name = "Truffle Bianca (Retired)",
      variantId = "var-retired",
      sizeCode = SizeCode.LARGE,
      crustId = "crust-hand",
      crustName = "Hand-Tossed",
      unitPricePaise = 64_900L,
      quantity = 2,
      isAvailable = false,
    )
    val (catalogVm, cartVm, quoteVm) = buildTestShellHarness(
      initialCartItems = listOf(itemDiscontinued to emptyList()),
      menuProducts = listOf(margherita),
    )

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerRoleShell(
          session = customerSession,
          catalogViewModel = catalogVm,
          cartViewModel = cartVm,
          checkoutQuoteViewModel = quoteVm,
          onSignOut = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("cart_lazy_column").fetchSemanticsNodes().isNotEmpty() &&
        cartVm.uiState.value.items.isNotEmpty()
    }
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("cart_edit_disc-1"))
    composeTestRule.onNodeWithTag("cart_edit_disc-1").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("builder_discontinued_guard").fetchSemanticsNodes().isNotEmpty()
    }

    composeTestRule.onNodeWithTag("builder_unavailable_state").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cart_item_preserved_summary").assertIsDisplayed()
    composeTestRule.onAllNodesWithTag("add_to_cart_button").assertCountEquals(0)

    composeTestRule.onNodeWithTag("builder_back_to_cart_button").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("builder_discontinued_guard").fetchSemanticsNodes().isEmpty()
    }

    // Original item preserved intact in Cart
    assertEquals(1, cartVm.uiState.value.items.size)
    assertEquals("disc-1", cartVm.uiState.value.items.single().item.id)
    assertEquals(2, cartVm.uiState.value.items.single().item.quantity)
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("cart_item_disc-1"))
    composeTestRule.onNodeWithTag("cart_item_disc-1").assertIsDisplayed()
  }

  @Test
  fun editModeDiscardConfirmationOnDirtyChangeAndInstantDismissWhenClean() {
    val seedItem = sampleItem(id = "dirty-1", quantity = 1)
    val (catalogVm, cartVm, quoteVm) = buildTestShellHarness(listOf(seedItem to emptyList()))
    var dispatcher: OnBackPressedDispatcher? = null

    composeTestRule.setContent {
      dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
      PizzoraTheme {
        CustomerRoleShell(
          session = customerSession,
          catalogViewModel = catalogVm,
          cartViewModel = cartVm,
          checkoutQuoteViewModel = quoteVm,
          onSignOut = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("cart_edit_dirty-1").fetchSemanticsNodes().isNotEmpty()
    }

    // Clean dismiss -> immediate close
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("cart_edit_dirty-1"))
    composeTestRule.onNodeWithTag("cart_edit_dirty-1").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("pizza_customizer_sheet").fetchSemanticsNodes().isNotEmpty() &&
        composeTestRule.onAllNodesWithTag("customizer_close_button").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("customizer_close_button").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("pizza_customizer_sheet").fetchSemanticsNodes().isEmpty()
    }
    composeTestRule.onAllNodesWithTag("builder_confirm_discard_button").assertCountEquals(0)

    // Re-open, dirty qty, system Back -> discard dialog -> Confirm closes cleanly
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("cart_edit_dirty-1"))
    composeTestRule.onNodeWithTag("cart_edit_dirty-1").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("customizer_qty_plus").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("customizer_qty_plus").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.runOnUiThread {
      dispatcher?.onBackPressed()
    }
    waitUntil {
      composeTestRule.onAllNodesWithTag("builder_confirm_discard_button").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("builder_confirm_discard_button").assertIsDisplayed().performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("pizza_customizer_sheet").fetchSemanticsNodes().isEmpty()
    }
    // Original qty 1 unchanged
    assertEquals(1, cartVm.uiState.value.items.single().item.quantity)
  }

  @Test
  fun undoRemovalViaTrashAndOneToZeroDecrementRestoresExactItem() {
    val itemOne = sampleItem(id = "u-1", name = "Margherita Verace", quantity = 1)
    val toppingsOne = listOf(CartItemToppingEntity("u-1", "top-basil", "Extra Basil", 2_500L))
    val itemTwo = sampleItem(
      id = "u-2",
      productId = "prod-mari",
      name = "Marinara Antica",
      imageUrl = null,
      variantId = "var-mari-reg",
      quantity = 2,
    )
    val (catalogVm, cartVm, quoteVm) = buildTestShellHarness(listOf(itemOne to toppingsOne, itemTwo to emptyList()))

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerRoleShell(
          session = customerSession,
          catalogViewModel = catalogVm,
          cartViewModel = cartVm,
          checkoutQuoteViewModel = quoteVm,
          onSignOut = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("cart_qty_minus_u-1").fetchSemanticsNodes().isNotEmpty()
    }

    // When qty == 1, minus button exposes "Remove item" accessibility description
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("cart_qty_minus_u-1"))
    composeTestRule.onNodeWithTag("cart_qty_minus_u-1").assert(hasContentDescription("Remove item"))

    // Trigger 1 -> 0 removal
    composeTestRule.onNodeWithTag("cart_qty_minus_u-1").performClick()
    waitUntil {
      cartVm.uiState.value.items.none { it.item.id == "u-1" } &&
        composeTestRule.onAllNodesWithTag("cart_item_u-2").fetchSemanticsNodes().isNotEmpty()
    }

    // Snackbar shows Undo -> click Undo -> item u-1 and its topping return
    composeTestRule.onNodeWithText("Removed Margherita Verace").assertIsDisplayed()
    composeTestRule.onNodeWithText("Undo").performClick()
    waitUntil {
      cartVm.uiState.value.items.any { it.item.id == "u-1" }
    }
    val restored = cartVm.uiState.value.items.first { it.item.id == "u-1" }
    assertEquals(listOf("top-basil"), restored.toppings.map { it.toppingId })

    // Repeated trash removals: remove u-1, then immediately remove u-2 -> Undo restores ONLY u-2
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("cart_remove_u-1"))
    composeTestRule.onNodeWithTag("cart_remove_u-1").performClick()
    waitUntil { cartVm.uiState.value.items.none { it.item.id == "u-1" } }
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("cart_remove_u-2"))
    composeTestRule.onNodeWithTag("cart_remove_u-2").performClick()
    waitUntil { cartVm.uiState.value.items.isEmpty() }

    composeTestRule.onNodeWithText("Removed Marinara Antica").assertIsDisplayed()
    composeTestRule.onNodeWithText("Undo").performClick()
    waitUntil { cartVm.uiState.value.items.size == 1 }
    assertEquals("u-2", cartVm.uiState.value.items.single().item.id)
  }

  @Test
  fun clearCartRequiresConfirmationCancelPreservesAndConfirmClearsAndBrowseReturnsToMenu() {
    val itemOne = sampleItem(id = "clr-1", quantity = 1)
    val (catalogVm, cartVm, quoteVm) = buildTestShellHarness(listOf(itemOne to emptyList()))

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerRoleShell(
          session = customerSession,
          catalogViewModel = catalogVm,
          cartViewModel = cartVm,
          checkoutQuoteViewModel = quoteVm,
          onSignOut = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("clear_cart_button").fetchSemanticsNodes().isNotEmpty()
    }

    // Click Clear -> Cancel keeps Cart intact
    composeTestRule.onNodeWithTag("clear_cart_button").performClick()
    composeTestRule.onNodeWithTag("clear_cart_confirm_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cancel_clear_cart_button").performClick()
    composeTestRule.onAllNodesWithTag("clear_cart_confirm_dialog").assertCountEquals(0)
    assertEquals(1, cartVm.uiState.value.items.size)

    // Click Clear -> Confirm empties Cart & Browse Menu switches to Menu tab
    composeTestRule.onNodeWithTag("clear_cart_button").performClick()
    composeTestRule.onNodeWithTag("confirm_clear_cart_button").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("cart_empty_state").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("cart_browse_menu_button").assertIsDisplayed().performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("customer_menu_screen").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("customer_menu_screen").assertIsDisplayed()
  }

  @Test
  fun stickyDockDisclosureVsQuotedTotalAndImeVisibilityHiding() {
    val item = sampleItem(id = "dock-1", unitPricePaise = 29_900L, quantity = 1)
    var quoteState by mutableStateOf<CheckoutQuoteUiState?>(null)
    var hostView: View? = null

    composeTestRule.setContent {
      hostView = LocalView.current
      PizzoraTheme {
        CartScreen(
          uiState = CartUiState(items = listOf(CartItemWithToppings(item, emptyList())), subtotalPaise = 29_900L, totalItemCount = 1),
          quoteState = quoteState,
          onUpdateQuantity = { _, _ -> },
          onRemoveItem = {},
          onClearCart = {},
          onValidateAndSync = {},
          onBrowseMenu = {},
          onProceedToCheckout = {},
        )
      }
    }

    // Without quote: Est. Subtotal + disclosure + single Proceed to Checkout CTA
    composeTestRule.onNodeWithTag("cart_sticky_dock").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cart_subtotal_text").assertTextContains("₹299.00")
    composeTestRule.onNodeWithText("+ tax & delivery at checkout", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("proceed_to_checkout_button").assertTextContains("Proceed to Checkout").assertIsEnabled()

    // With server quote: Total Payable + Item Subtotal shown
    quoteState = CheckoutQuoteUiState(quote = sampleQuoteResponse())
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Total Payable").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cart_payable_total_text").assertTextContains("₹313.96")
    composeTestRule.onNodeWithTag("cart_subtotal_text").assertTextContains("₹299.00", substring = true)

    // Inject active IME inset -> sticky dock auto-hides; remove IME inset -> reappears
    composeTestRule.runOnUiThread {
      val view = hostView ?: return@runOnUiThread
      val imeOpenInsets = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 600))
        .build()
      ViewCompat.dispatchApplyWindowInsets(view.rootView, imeOpenInsets)
    }
    composeTestRule.waitForIdle()
    composeTestRule.onAllNodesWithTag("proceed_to_checkout_button").assertCountEquals(0)

    composeTestRule.runOnUiThread {
      val view = hostView ?: return@runOnUiThread
      val imeClosedInsets = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
        .build()
      ViewCompat.dispatchApplyWindowInsets(view.rootView, imeClosedInsets)
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("proceed_to_checkout_button").assertIsDisplayed()
  }

  @Test
  fun couponPickerGroupsEligibleAndLockedSectionsAndShowsRetryOnError() {
    val item = sampleItem(id = "cpn-item", quantity = 1)
    val (_, cartVm, quoteVm) = buildTestShellHarness(
      initialCartItems = listOf(item to emptyList()),
      couponsFailure = true,
    )

    var showDirectSheet by mutableStateOf(false)

    composeTestRule.setContent {
      val qState by quoteVm.uiState.collectAsStateWithLifecycle()
      val cState by cartVm.uiState.collectAsStateWithLifecycle()
      PizzoraTheme {
        if (!showDirectSheet) {
          CartScreen(
            uiState = cState,
            quoteState = qState,
            onUpdateQuantity = { _, _ -> },
            onRemoveItem = {},
            onClearCart = {},
            onValidateAndSync = {},
            onBrowseMenu = {},
            onOpenCouponPicker = { quoteVm.openCouponPicker() },
            onDismissCouponPicker = { quoteVm.dismissCouponPicker() },
            onRetryCoupons = { quoteVm.openCouponPicker() },
          )
        } else {
          CouponPickerSheet(
            coupons = listOf(
              CustomerCouponDto(
                id = "c1",
                code = "PIZZA20",
                discountType = DiscountType.PERCENT_BPS,
                discountValue = 2000L,
                minOrderPaise = 20_000L,
                maxUsesPerUser = 2,
                userUsageCount = 0,
                remainingUserUses = 2,
                startsAt = "2026-11-01T00:00:00Z",
                endsAt = "2026-12-01T00:00:00Z",
                isEligible = true,
                estimatedDiscountPaise = 5_980L,
              ),
              CustomerCouponDto(
                id = "c2",
                code = "FEAST999",
                discountType = DiscountType.FLAT_PAISE,
                discountValue = 15_000L,
                minOrderPaise = 99_900L,
                maxUsesPerUser = 1,
                userUsageCount = 0,
                remainingUserUses = 1,
                startsAt = "2026-11-01T00:00:00Z",
                endsAt = "2026-12-01T00:00:00Z",
                isEligible = false,
                ineligibleReason = "Add ₹700.00 more to unlock",
              ),
            ),
            isLoading = false,
            onSelectCoupon = {},
            onDismiss = {},
          )
        }
      }
    }

    // Trigger coupon sheet open while server returns error
    composeTestRule.runOnUiThread { quoteVm.openCouponPicker() }
    waitUntil {
      composeTestRule.onAllNodesWithTag("coupon_sheet_error").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("coupon_sheet_retry_button").assertIsDisplayed()

    // Now test grouped Eligible vs Locked sheet directly
    showDirectSheet = true
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("coupon_section_eligible").assertIsDisplayed()
    composeTestRule.onNodeWithText("You save ₹59.80").assertIsDisplayed()
    composeTestRule.onNodeWithTag("apply_coupon_btn_PIZZA20").assertIsEnabled()

    composeTestRule.onNodeWithTag("coupon_section_locked").assertIsDisplayed()
    composeTestRule.onNodeWithTag("coupon_ineligible_FEAST999").assertTextContains("Add ₹700.00 more to unlock")
    composeTestRule.onNodeWithTag("apply_coupon_btn_FEAST999").assertIsNotEnabled()
  }

  @Test
  @Config(qualifiers = "w320dp-h640dp")
  fun narrowWidthWithLargeFontScaleKeepsControlsUnclippedAndSinglePane() {
    val item = sampleItem(id = "narrow-1", quantity = 1)
    composeTestRule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
        PizzoraTheme {
          CartScreen(
            uiState = CartUiState(
              items = listOf(CartItemWithToppings(item, listOf(CartItemToppingEntity("narrow-1", "t1", "Extra Basil", 2_500L)))),
              subtotalPaise = 32_400L,
              totalItemCount = 1,
            ),
            onUpdateQuantity = { _, _ -> },
            onRemoveItem = {},
            onClearCart = {},
            onValidateAndSync = {},
            onBrowseMenu = {},
            onEditItem = {},
            onProceedToCheckout = {},
          )
        }
      }
    }

    composeTestRule.onAllNodesWithTag("cart_left_pane").assertCountEquals(0)
    composeTestRule.onAllNodesWithTag("cart_right_pane").assertCountEquals(0)
    composeTestRule.onNodeWithTag("cart_edit_narrow-1")
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
      .assertWidthIsAtLeast(48.dp)
    composeTestRule.onNodeWithTag("cart_qty_minus_narrow-1")
      .assert(hasContentDescription("Remove item"))
      .assertHeightIsAtLeast(48.dp)
      .assertWidthIsAtLeast(48.dp)
    composeTestRule.onNodeWithTag("clear_cart_button")
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
    composeTestRule.onNodeWithTag("proceed_to_checkout_button")
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
  }

  @Test
  @Config(qualifiers = "w600dp-h900dp")
  fun compactTablet600dpRemainsSingleColumnEvenInsideShell() {
    val item = sampleItem(id = "tab600-1", quantity = 1)
    val (catalogVm, cartVm, quoteVm) = buildTestShellHarness(listOf(item to emptyList()))

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerRoleShell(
          session = customerSession,
          catalogViewModel = catalogVm,
          cartViewModel = cartVm,
          checkoutQuoteViewModel = quoteVm,
          onSignOut = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("cart_item_tab600-1").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onAllNodesWithTag("cart_left_pane").assertCountEquals(0)
    composeTestRule.onAllNodesWithTag("cart_right_pane").assertCountEquals(0)
  }

  @Test
  @Config(qualifiers = "w840dp-h900dp")
  fun wideTablet840dpActivatesTwoPane6040CartSplitEvenWithNavigationRail() {
    val item = sampleItem(id = "tab840-1", quantity = 1)
    val harness = buildFullTestShellHarness(listOf(item to emptyList()))

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerRoleShell(
          session = customerSession,
          catalogViewModel = harness.first,
          cartViewModel = harness.second,
          checkoutQuoteViewModel = harness.third,
          checkoutViewModel = harness.checkoutVm,
          onSignOut = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("cart_left_pane").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("cart_left_pane").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cart_right_pane").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cart_item_tab840-1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("proceed_to_checkout_button").assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w840dp-h400dp")
  fun landscapeWideShortViewportFallsBackToSingleColumnCartBecauseHeightBelow480dp() {
    val item = sampleItem(id = "land840-1", quantity = 1)
    val harness = buildFullTestShellHarness(listOf(item to emptyList()))

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerRoleShell(
          session = customerSession,
          catalogViewModel = harness.first,
          cartViewModel = harness.second,
          checkoutQuoteViewModel = harness.third,
          checkoutViewModel = harness.checkoutVm,
          onSignOut = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("cart_item_land840-1").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onAllNodesWithTag("cart_left_pane").assertCountEquals(0)
    composeTestRule.onAllNodesWithTag("cart_right_pane").assertCountEquals(0)
    composeTestRule.onNodeWithTag("proceed_to_checkout_button").assertIsDisplayed()
  }

  @Test
  fun repeatedEditSubmissionLockAndMenuAddModeContinueToWorkInSameShell() {
    val putCounter = AtomicInteger(0)
    val seedItem = sampleItem(id = "seq-1", variantId = "var-reg", quantity = 1)
    val harness = buildFullTestShellHarness(
      initialCartItems = listOf(seedItem to emptyList()),
      onPutCart = { putCounter.incrementAndGet() },
    )

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerRoleShell(
          session = customerSession,
          catalogViewModel = harness.first,
          cartViewModel = harness.second,
          checkoutQuoteViewModel = harness.third,
          checkoutViewModel = harness.checkoutVm,
          onSignOut = {},
        )
      }
    }

    // Wait for menu loaded, verify Add Mode from Menu still adds cleanly alongside Edit Mode
    waitUntil {
      composeTestRule.onAllNodesWithTag("menu_lazy_column").fetchSemanticsNodes().isNotEmpty() &&
        harness.first.uiState.value.allProducts.any { it.id == "prod-mari" }
    }
    composeTestRule.onNodeWithTag("menu_lazy_column").performScrollToNode(hasTestTag("customize_product_button_prod-mari"))
    composeTestRule.onNodeWithTag("customize_product_button_prod-mari").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("pizza_customizer_sheet").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onAllNodesWithTag("builder_edit_mode_badge").assertCountEquals(0)
    composeTestRule.onNodeWithTag("add_to_cart_button").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("pizza_customizer_sheet").fetchSemanticsNodes().isEmpty()
    }
    assertEquals(2, harness.second.uiState.value.items.size)

    // Switch to Cart, edit seq-1 and submit twice rapidly -> single PUT mutation
    val putsBeforeEdit = putCounter.get()
    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("cart_lazy_column").fetchSemanticsNodes().isNotEmpty() &&
        harness.second.uiState.value.items.any { it.item.id == "seq-1" }
    }
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("cart_edit_seq-1"))
    composeTestRule.onNodeWithTag("cart_edit_seq-1").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("size_option_LARGE").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("size_option_LARGE").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("add_to_cart_button").performClick()
    composeTestRule.onNodeWithTag("add_to_cart_button").performClick()
    waitUntil {
      composeTestRule.onAllNodesWithTag("pizza_customizer_sheet").fetchSemanticsNodes().isEmpty()
    }
    assertEquals(putsBeforeEdit + 1, putCounter.get())
    val updated = harness.second.uiState.value.items.first { it.item.id == "seq-1" }
    assertEquals("var-lrg", updated.item.variantId)
  }
}

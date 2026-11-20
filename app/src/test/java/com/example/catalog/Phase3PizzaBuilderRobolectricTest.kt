package com.example.catalog

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cart.CartViewModel
import com.example.cart.NetworkCartRepository
import com.example.core.database.PizzoraDatabase
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.navigation.CustomerRoleShell
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductImageDto
import com.pizzora.contracts.ProductImageStatus
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase3PizzaBuilderRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var db: PizzoraDatabase
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private val sampleProductWithImage = ProductDto(
    id = "prod-margherita",
    categoryId = "cat-classics",
    categoryName = "Classics",
    name = "Margherita Verace",
    description = "San Marzano DOP tomatoes, fresh fior di latte, sweet basil",
    isVeg = true,
    isActive = true,
    isTaxable = true,
    variants = listOf(
      ProductVariantDto("var-large", "prod-margherita", SizeCode.LARGE, pricePaise = 54_900L, isActive = true),
      ProductVariantDto("var-regular", "prod-margherita", SizeCode.REGULAR, pricePaise = 29_900L, isActive = true),
      ProductVariantDto("var-medium", "prod-margherita", SizeCode.MEDIUM, pricePaise = 42_900L, isActive = true),
    ),
    images = listOf(
      ProductImageDto(
        id = "img-1",
        productId = "prod-margherita",
        objectKey = "products/marg.jpg",
        publicUrl = "https://cdn.example.com/marg.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 20_000L,
        status = ProductImageStatus.ACTIVE,
        sortOrder = 0,
        createdAt = "2026-11-10T10:00:00Z",
      ),
    ),
  )

  private val sampleProductNoImage = sampleProductWithImage.copy(
    id = "prod-no-img",
    name = "Marinara Antica",
    images = emptyList(),
  )

  private val sampleCrusts = listOf(
    CrustOptionDto("crust-hand", "Hand-Tossed", extraPricePaise = 0L, isActive = true),
    CrustOptionDto("crust-sourdough", "Sourdough Pan", extraPricePaise = 5_000L, isActive = true),
  )

  private val sampleToppings = listOf(
    ToppingDto("top-basil", "Extra Basil", isVeg = true, extraPricePaise = 2_500L, isActive = true),
    ToppingDto("top-pep", "Smoked Pepperoni", isVeg = false, extraPricePaise = 7_500L, isActive = true),
  )

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
  fun firstActiveVariantIsDefaultAndBreakdownDrawerShowsExactMath() {
    val productInactiveRegular = sampleProductWithImage.copy(
      variants = listOf(
        ProductVariantDto("var-reg-off", "prod-margherita", SizeCode.REGULAR, 24_900L, isActive = false),
        ProductVariantDto("var-med-on", "prod-margherita", SizeCode.MEDIUM, 42_900L, isActive = true),
        ProductVariantDto("var-lrg-on", "prod-margherita", SizeCode.LARGE, 54_900L, isActive = true),
      ),
    )

    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = productInactiveRegular,
          crusts = sampleCrusts,
          toppings = sampleToppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
        )
      }
    }

    // Default skips inactive REGULAR -> selects MEDIUM
    composeTestRule.onNodeWithTag("size_option_REGULAR").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("size_option_MEDIUM").assertIsSelected()
    composeTestRule.onNodeWithTag("add_to_cart_button").assertTextContains("₹429.00", substring = true)

    // Select Large + Sourdough + Extra Basil + Qty 2 -> (54900 + 5000 + 2500) * 2 = ₹1248.00
    composeTestRule.onNodeWithTag("size_option_LARGE").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("crust_option_crust-sourdough").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("topping_option_top-basil").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("customizer_qty_plus").performClick()
    composeTestRule.waitForIdle()

    // Breakdown drawer starts collapsed, expands on toggle tap
    composeTestRule.onNodeWithTag("builder_breakdown_drawer").assertDoesNotExist()
    composeTestRule.onNodeWithTag("builder_breakdown_toggle").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("builder_breakdown_drawer").assertIsDisplayed()
    composeTestRule.onNodeWithTag("builder_total_price_text").assertTextContains("₹1248.00")
    composeTestRule.onNodeWithText("GST & delivery calculated in Cart", substring = true).performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("add_to_cart_button").assertTextContains("₹1248.00", substring = true)
  }

  @Test
  fun inactiveOptionsCannotBeSelectedAndAllUnavailableDisablesAdd() {
    val crustsWithInactive = listOf(
      CrustOptionDto("crust-off", "Cheese Burst", 8_000L, isActive = false),
      CrustOptionDto("crust-hand", "Hand-Tossed", 0L, isActive = true),
    )
    val toppingsWithInactive = listOf(
      ToppingDto("top-off", "Truffle Oil", isVeg = true, extraPricePaise = 9_500L, isActive = false),
      ToppingDto("top-basil", "Extra Basil", isVeg = true, extraPricePaise = 2_500L, isActive = true),
    )

    var currentProduct by mutableStateOf(sampleProductWithImage)
    var currentCrusts by mutableStateOf(crustsWithInactive)
    var currentToppings by mutableStateOf(toppingsWithInactive)

    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = currentProduct,
          crusts = currentCrusts,
          toppings = currentToppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
        )
      }
    }

    composeTestRule.onNodeWithTag("crust_option_crust-off").performScrollTo().assertIsNotEnabled()
    composeTestRule.onNodeWithTag("crust_option_crust-hand").assertIsSelected()
    composeTestRule.onNodeWithTag("topping_option_top-off").performScrollTo().assertIsNotEnabled().assertIsOff()

    // Now verify all variants inactive -> unavailable state + disabled Add button
    composeTestRule.runOnUiThread {
      currentProduct = sampleProductWithImage.copy(
        variants = sampleProductWithImage.variants.map { it.copy(isActive = false) },
      )
      currentCrusts = sampleCrusts
      currentToppings = sampleToppings
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("builder_unavailable_state").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("add_to_cart_button").assertIsNotEnabled()
  }

  @Test
  fun pausedKitchenDisplaysWarningDisablesButtonAndGuardsSubmitHandler() {
    var addInvocations = 0
    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = sampleProductWithImage,
          crusts = sampleCrusts,
          toppings = sampleToppings,
          isAcceptingOrders = false,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult ->
            addInvocations += 1
            onResult(true, null)
          },
        )
      }
    }

    composeTestRule.onNodeWithTag("builder_paused_kitchen_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("add_to_cart_button")
      .assertIsNotEnabled()
      .assertTextContains("Store Currently Paused", substring = true)

    // Attempt direct semantic click bypass -> handler guard also rejects
    composeTestRule.onNodeWithTag("add_to_cart_button").fetchSemanticsNode().let { node ->
      node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
    }
    assertEquals(0, addInvocations)
  }

  @Test
  fun duplicateTapSubmissionLockProducesSingleMutationAndHonorsSuccessFailureCallbacks() {
    var callbackHolder: ((Boolean, String?) -> Unit)? = null
    var addCount = 0
    var dismissed = false

    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = sampleProductWithImage,
          crusts = sampleCrusts,
          toppings = sampleToppings,
          onDismiss = { dismissed = true },
          onConfirmAdd = { _, _, _, _, onResult ->
            addCount += 1
            callbackHolder = onResult
          },
        )
      }
    }

    // First tap locks submission
    composeTestRule.onNodeWithTag("add_to_cart_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(1, addCount)
    composeTestRule.onNodeWithTag("add_to_cart_button").assertIsNotEnabled()

    // Second tap while locked must not invoke handler
    composeTestRule.onNodeWithTag("add_to_cart_button").performClick()
    assertEquals(1, addCount)
    assertFalse(dismissed)

    // Simulate Cart failure -> Builder stays open, shows error banner, re-enables button
    composeTestRule.runOnUiThread {
      callbackHolder?.invoke(false, "Room write failed")
    }
    composeTestRule.waitForIdle()
    assertFalse(dismissed)
    composeTestRule.onNodeWithTag("builder_submission_error").assertIsDisplayed()
    composeTestRule.onNodeWithText("Room write failed").assertIsDisplayed()
    composeTestRule.onNodeWithTag("add_to_cart_button").assertIsEnabled()

    // Retry succeeds -> Builder closes cleanly
    composeTestRule.onNodeWithTag("add_to_cart_button").performClick()
    composeTestRule.runOnUiThread {
      callbackHolder?.invoke(true, null)
    }
    composeTestRule.waitForIdle()
    assertTrue(dismissed)
  }

  @Test
  fun discardConfirmationTriggersOnlyWhenDirtyAndCollapsesBreakdownFirstOnBack() {
    var dismissCount = 0
    var backDispatcher: OnBackPressedDispatcher? = null

    composeTestRule.setContent {
      backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
      PizzoraTheme {
        PizzaBuilderScreen(
          product = sampleProductWithImage,
          crusts = sampleCrusts,
          toppings = sampleToppings,
          onDismiss = { dismissCount += 1 },
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
        )
      }
    }

    // Unmodified defaults -> close dismisses without confirmation
    composeTestRule.onNodeWithTag("customizer_close_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(1, dismissCount)
    composeTestRule.onAllNodesWithTag("builder_confirm_discard_button").assertCountEquals(0)

    // Re-open with dirty topping + expanded breakdown
    composeTestRule.onNodeWithTag("topping_option_top-basil").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("builder_breakdown_toggle").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("builder_breakdown_drawer").assertIsDisplayed()

    // System Back #1 collapses breakdown drawer first without showing discard dialog
    composeTestRule.runOnUiThread { backDispatcher?.onBackPressed() }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("builder_breakdown_drawer").assertDoesNotExist()
    composeTestRule.onAllNodesWithTag("builder_confirm_discard_button").assertCountEquals(0)

    // System Back #2 detects dirty edit -> shows Discard confirmation dialog
    composeTestRule.runOnUiThread { backDispatcher?.onBackPressed() }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("builder_keep_editing_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    assertEquals(1, dismissCount)
    // Selection preserved after Keep editing
    composeTestRule.onNodeWithTag("topping_option_top-basil").assertIsOn()

    // Close button -> Discard confirms close
    composeTestRule.onNodeWithTag("customizer_close_button").performClick()
    composeTestRule.onNodeWithTag("builder_confirm_discard_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(2, dismissCount)
  }

  @Test
  fun vegOnlyFilterPreservesNonVegSelectionsAndClearActionsWork() {
    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = sampleProductWithImage,
          crusts = sampleCrusts,
          toppings = sampleToppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
        )
      }
    }

    // Select non-veg Pepperoni (+₹75 -> 299 + 75 = ₹374)
    composeTestRule.onNodeWithTag("topping_option_top-pep").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("add_to_cart_button").assertTextContains("₹374.00", substring = true)

    // Switch filter to Veg Only -> Pepperoni hides from list, selection & price MUST persist
    composeTestRule.onNodeWithTag("builder_toppings_filter_veg").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("topping_option_top-pep").assertDoesNotExist()
    composeTestRule.onNodeWithTag("add_to_cart_button").assertTextContains("₹374.00", substring = true)

    // Clear filter -> Pepperoni returns still checked
    composeTestRule.onNodeWithTag("builder_clear_topping_filter_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("topping_option_top-pep").performScrollTo().assertIsOn()

    // Clear toppings -> resets to 0 toppings (₹299.00)
    composeTestRule.onNodeWithTag("builder_clear_toppings_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("topping_option_top-pep").assertIsOff()
    composeTestRule.onNodeWithTag("add_to_cart_button").assertTextContains("₹299.00", substring = true)
  }

  @Test
  fun quantityBoundsEnforceMinOneAndMaxTwentyFive() {
    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = sampleProductWithImage,
          crusts = sampleCrusts,
          toppings = sampleToppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
        )
      }
    }

    composeTestRule.onNodeWithTag("customizer_qty_value").assertTextContains("1")
    composeTestRule.onNodeWithTag("customizer_qty_minus").assertIsNotEnabled()

    repeat(24) {
      composeTestRule.onNodeWithTag("customizer_qty_plus").performClick()
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("customizer_qty_value").assertTextContains("25")
    composeTestRule.onNodeWithTag("customizer_qty_plus").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("builder_qty_max_note").assertIsDisplayed()
  }

  @Test
  fun missingProductImageFallsBackToCompactTypographicHeader() {
    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = sampleProductNoImage,
          crusts = sampleCrusts,
          toppings = sampleToppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
        )
      }
    }

    composeTestRule.onNodeWithTag("builder_compact_header").assertIsDisplayed()
    composeTestRule.onNodeWithTag("builder_hero_image").assertDoesNotExist()
    composeTestRule.onNodeWithText("Marinara Antica").assertIsDisplayed()
  }

  @Test
  @Config(sdk = [36], qualifiers = "w320dp-h640dp")
  fun narrow320dpWith15xFontScaleStacksSizesVerticallyAndKeepsCtaUnclipped() {
    composeTestRule.setContent {
      val baseDensity = LocalDensity.current
      CompositionLocalProvider(
        LocalDensity provides Density(baseDensity.density, fontScale = 1.5f),
      ) {
        PizzoraTheme {
          PizzaBuilderScreen(
            product = sampleProductNoImage,
            crusts = sampleCrusts,
            toppings = sampleToppings,
            onDismiss = {},
            onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
          )
        }
      }
    }

    val regBounds = composeTestRule.onNodeWithTag("size_option_REGULAR").fetchSemanticsNode().boundsInRoot
    val medBounds = composeTestRule.onNodeWithTag("size_option_MEDIUM").fetchSemanticsNode().boundsInRoot
    assertTrue("Sizes must stack vertically at 320dp + 1.5x font scale", medBounds.top >= regBounds.bottom - 1f)

    val rootBounds = composeTestRule.onNodeWithTag("pizza_customizer_sheet").fetchSemanticsNode().boundsInRoot
    val ctaBounds = composeTestRule.onNodeWithTag("add_to_cart_button").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    assertTrue("Add CTA must not clip right/bottom at 320dp + 1.5x", ctaBounds.right <= rootBounds.right + 1f && ctaBounds.bottom <= rootBounds.bottom + 1f)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w720dp-h380dp")
  fun landscapePhoneHeightBelow480dpForcesSingleColumn() {
    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = sampleProductWithImage,
          crusts = sampleCrusts,
          toppings = sampleToppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
        )
      }
    }

    composeTestRule.onNodeWithTag("builder_left_pane").assertDoesNotExist()
    composeTestRule.onNodeWithTag("builder_right_pane").assertDoesNotExist()
    composeTestRule.onNodeWithTag("add_to_cart_button").assertIsDisplayed()
  }

  @Test
  @Config(sdk = [36], qualifiers = "w600dp-h900dp")
  fun tablet600dpRenders4060TwoPaneSplitWith3UpSizes() {
    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = sampleProductWithImage,
          crusts = sampleCrusts,
          toppings = sampleToppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
        )
      }
    }

    val left = composeTestRule.onNodeWithTag("builder_left_pane").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val right = composeTestRule.onNodeWithTag("builder_right_pane").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    assertTrue("Right pane must sit to the right of Left pane", right.left >= left.right - 1f)
    val ratio = left.width / (left.width + right.width)
    assertTrue("Expected ~40/60 split at 600dp, got $ratio", ratio in 0.36f..0.44f)

    // 360dp right pane at 1.0x fits 3-up Size cards cleanly side-by-side
    val regBounds = composeTestRule.onNodeWithTag("size_option_REGULAR").fetchSemanticsNode().boundsInRoot
    val medBounds = composeTestRule.onNodeWithTag("size_option_MEDIUM").fetchSemanticsNode().boundsInRoot
    assertTrue("600dp tablet right pane fits 3-up horizontal Sizes", medBounds.left >= regBounds.right - 1f)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w840dp-h900dp")
  fun tablet840dpRenders4060TwoPaneSplitCleanly() {
    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = sampleProductWithImage,
          crusts = sampleCrusts,
          toppings = sampleToppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
        )
      }
    }

    val left = composeTestRule.onNodeWithTag("builder_left_pane").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val right = composeTestRule.onNodeWithTag("builder_right_pane").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    assertTrue(right.left >= left.right - 1f)
    val ratio = left.width / (left.width + right.width)
    assertTrue("Expected ~40/60 split at 840dp, got $ratio", ratio in 0.36f..0.44f)
    composeTestRule.onNodeWithTag("add_to_cart_button").assertIsDisplayed()
  }

  @Test
  fun shellOverlayHidesNavChromeTrapsTraversalAndRestoresOriginatingProductFocusOnClose() {
    val menuResponse = PublicMenuResponse(
      categories = listOf(CategoryDto("cat-classics", "Classics", 1, true, "2026-11-10T10:00:00Z")),
      products = listOf(sampleProductNoImage),
      crusts = sampleCrusts,
      toppings = sampleToppings,
      gstBps = 500,
      storeName = "PIZZORA Kitchen",
      isAcceptingOrders = true,
    )
    val session = AuthSession(
      accessToken = "acc",
      refreshToken = "ref",
      user = UserProfileDto("user-p3", "p3@pizzora.in", true, "Aarav", "+919876543210", UserRole.CUSTOMER, true),
    )
    val tokenStorage = object : TokenStorage {
      private val flow = MutableStateFlow<AuthSession?>(session)
      private val mutedFlow = MutableStateFlow(false)
      override val sessionFlow: StateFlow<AuthSession?> = flow.asStateFlow()
      override fun currentSession(): AuthSession? = flow.value
      override suspend fun saveSession(session: AuthSession) { flow.value = session }
      override suspend fun clearSession() { flow.value = null }
      override fun observeKdsSoundMuted(): Flow<Boolean> = mutedFlow.asStateFlow()
      override suspend fun setKdsSoundMuted(muted: Boolean) { mutedFlow.value = muted }
    }
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/api/v1/catalog/menu" -> respond(
          content = json.encodeToString(menuResponse),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        else -> respond(
          content = "{}",
          status = HttpStatusCode.ServiceUnavailable,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
      }
    }
    val apiClient = PizzoraApiClient("http://localhost:8080", tokenStorage, engine)
    val catalogVm = CustomerCatalogViewModel(NetworkCatalogRepository(apiClient, db.catalogCacheDao()))
    val cartVm = CartViewModel(NetworkCartRepository(apiClient, db.cartDao()))

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerRoleShell(
          session = session,
          catalogViewModel = catalogVm,
          cartViewModel = cartVm,
          onSignOut = {},
        )
      }
    }

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodesWithTag("product_card_${sampleProductNoImage.id}").fetchSemanticsNodes().isNotEmpty()
    }

    // Open builder from Menu
    composeTestRule.onNodeWithTag("customize_product_button_${sampleProductNoImage.id}")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    composeTestRule.mainClock.advanceTimeBy(400)
    composeTestRule.waitForIdle()

    // Shell overlay hosts Builder & marks traversal group; underlying nav chrome is hidden
    composeTestRule.onNodeWithTag("pizza_customizer_sheet").assertIsDisplayed()
    composeTestRule.onNodeWithTag("customer_shell_overlay_host")
      .assertIsDisplayed()
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true))
    composeTestRule.onNodeWithTag("customer_tab_cart").assertDoesNotExist()
    composeTestRule.onNodeWithTag("customer_topbar_title").assertDoesNotExist()

    // Close builder -> nav chrome returns & focus restores to originating product card
    composeTestRule.onNodeWithTag("customizer_close_button").performClick()
    composeTestRule.mainClock.advanceTimeBy(500)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("pizza_customizer_sheet").assertDoesNotExist()
    composeTestRule.onNodeWithTag("customer_tab_cart").assertIsDisplayed()
    composeTestRule.onNodeWithTag("product_card_${sampleProductNoImage.id}").assertIsFocused()
  }

  @Test
  fun standaloneCustomerMenuScreenFallbackHostRemainsCompatible() {
    var uiState by mutableStateOf(
      CustomerMenuUiState(
        isLoading = false,
        categories = listOf(CategoryDto("cat-classics", "Classics", 1, true, "2026-11-10T10:00:00Z")),
        allProducts = listOf(sampleProductNoImage),
        crusts = sampleCrusts,
        toppings = sampleToppings,
      ),
    )
    var addedCount = 0

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerMenuScreen(
          uiState = uiState,
          onSearchQueryChange = {},
          onCategorySelected = {},
          onVegOnlyToggle = {},
          onClearFilters = {},
          onRefreshMenu = {},
          onOpenCustomizer = { p -> uiState = uiState.copy(customizingProduct = p) },
          onDismissCustomizer = { uiState = uiState.copy(customizingProduct = null) },
          onAddCustomizedToCart = { _, _, _, _, _ -> addedCount += 1 },
        )
      }
    }

    composeTestRule.onNodeWithTag("customize_product_button_${sampleProductNoImage.id}").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("pizza_customizer_sheet").assertIsDisplayed()
    composeTestRule.onNodeWithTag("add_to_cart_button").performClick()
    composeTestRule.waitForIdle()

    assertEquals(1, addedCount)
    composeTestRule.onNodeWithTag("pizza_customizer_sheet").assertDoesNotExist()
  }
}

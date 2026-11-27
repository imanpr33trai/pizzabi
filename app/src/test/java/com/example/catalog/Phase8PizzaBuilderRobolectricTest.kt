package com.example.catalog

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.core.database.CartItemEntity
import com.example.core.database.CartItemToppingEntity
import com.example.core.database.CartItemWithToppings
import com.example.core.domain.KotlinContractsDomainBridge
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.CartCheeseViewDto
import com.pizzora.contracts.CartHalfAndHalfViewDto
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartSauceViewDto
import com.pizzora.contracts.CartStandardToppingViewDto
import com.pizzora.contracts.CheeseOptionDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.HalfAndHalfConfigDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.SauceOptionDto
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.StandardToppingDto
import com.pizzora.contracts.ToppingDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase8PizzaBuilderRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val domainBridge = KotlinContractsDomainBridge()

  private val variants = listOf(
    ProductVariantDto("var-regular", "prod-8", SizeCode.REGULAR, pricePaise = 10_000L, isActive = true),
  )
  private val crusts = listOf(CrustOptionDto("crust-classic", "Classic", 0L, isActive = true))

  private val cheeses = listOf(
    CheeseOptionDto("cheese-moz", "prod-8", "Mozzarella", 2_000L, isActive = true, sortOrder = 0),
    CheeseOptionDto("cheese-off", "prod-8", "Retired Cheese", 999L, isActive = false, sortOrder = 1),
  )
  private val sauces = listOf(
    SauceOptionDto("sauce-tomato", "prod-8", "Tomato", 1_500L, isActive = true, sortOrder = 0),
    SauceOptionDto("sauce-off", "prod-8", "Retired Sauce", 999L, isActive = false, sortOrder = 1),
  )
  private val standardToppings = listOf(
    StandardToppingDto(
      id = "std-default", productId = "prod-8", name = "Basil", isVeg = true,
      extraPricePaise = 1_000L, isDefault = true, isActive = true, sortOrder = 0,
    ),
    StandardToppingDto(
      id = "std-extra", productId = "prod-8", name = "Olive", isVeg = true,
      extraPricePaise = 1_000L, isDefault = false, isActive = true, sortOrder = 1,
    ),
    StandardToppingDto(
      id = "std-off", productId = "prod-8", name = "Retired Standard", isVeg = true,
      extraPricePaise = 999L, isDefault = false, isActive = false, sortOrder = 2,
    ),
  )
  private val halfConfigs = listOf(
    HalfAndHalfConfigDto(
      id = "hh-one", productId = "prod-8", name = "Two halves",
      halfAndHalfSurchargePaise = 1_000L, isActive = true, sortOrder = 0,
    ),
    HalfAndHalfConfigDto(
      id = "hh-off", productId = "prod-8", name = "Retired config",
      halfAndHalfSurchargePaise = 999L, isActive = false, sortOrder = 1,
    ),
  )
  private val toppings = (1..16).map { index ->
    ToppingDto("top-$index", "Topping $index", isVeg = true, extraPricePaise = 500L, isActive = true)
  }

  private fun testProduct(
    cheeses: List<CheeseOptionDto> = this.cheeses,
    sauces: List<SauceOptionDto> = this.sauces,
    standards: List<StandardToppingDto> = this.standardToppings,
    configs: List<HalfAndHalfConfigDto> = this.halfConfigs,
  ): ProductDto = ProductDto(
    id = "prod-8",
    categoryId = "cat-8",
    categoryName = "Classics",
    name = "Phase Eight Pizza",
    description = "Builder contract fixture",
    isVeg = true,
    isActive = true,
    isTaxable = true,
    variants = variants,
    cheeses = cheeses,
    sauces = sauces,
    standardToppings = standards,
    halfAndHalfConfigs = configs,
  )

  private class FakeQuoteSource(
    private val priceFor: (CartItemInputDto) -> Long,
  ) : PizzaBuilderQuoteSource {
    var lastRequest: CartItemInputDto? = null
      private set
    var callCount: Int = 0
      private set

    override suspend fun quote(item: CartItemInputDto): CartItemViewDto {
      lastRequest = item
      callCount += 1
      val price = priceFor(item)
      return CartItemViewDto(
        id = "quote-item",
        productId = item.productId,
        productName = "Phase Eight Pizza",
        isVeg = true,
        variantId = item.variantId,
        sizeCode = SizeCode.REGULAR,
        basePricePaise = 10_000L,
        crustId = item.crustId,
        crustName = "Classic",
        crustExtraPricePaise = 0L,
        quantity = item.quantity,
        unitPricePaise = price,
        lineTotalPaise = price * item.quantity,
        isAvailable = true,
        cheese = item.cheeseId?.let {
          CartCheeseViewDto(it, "Mozzarella", 2_000L, isAvailable = true)
        },
        sauce = item.sauceId?.let {
          CartSauceViewDto(it, "Tomato", 1_500L, isAvailable = true)
        },
        standardToppings = item.standardToppingIds.map { id ->
          CartStandardToppingViewDto(
            standardToppingId = id,
            name = "Standard",
            isVeg = true,
            extraPricePaise = 1_000L,
            isDefault = false,
            isRemoved = false,
            isAvailable = true,
          )
        },
        halfAndHalf = item.halfAndHalfConfigId?.let {
          CartHalfAndHalfViewDto(
            halfAndHalfConfigId = it,
            name = "Two halves",
            leftToppingIds = item.halfAndHalfLeftToppingIds,
            rightToppingIds = item.halfAndHalfRightToppingIds,
            halfAndHalfSurchargePaise = 1_000L,
            isAvailable = true,
          )
        },
      )
    }
  }

  private fun waitForServerPrice(quoteSource: FakeQuoteSource) {
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      quoteSource.callCount > 0
    }
    composeTestRule.waitForIdle()
  }

  @Test
  fun keptDefaultIsIncludedAndRemovedDefaultIsZeroCredit() {
    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = testProduct(),
          crusts = crusts,
          toppings = toppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
          domainBridge = domainBridge,
        )
      }
    }

    composeTestRule.onNodeWithTag("builder_standard_topping_std-default")
      .performScrollTo().assertIsSelected().assertTextContains("Included", substring = true)
    composeTestRule.onNodeWithTag("builder_dock_total_price_text").assertTextContains("₹100.00", substring = true)

    composeTestRule.onNodeWithTag("builder_standard_topping_std-default")
      .performScrollTo().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("builder_standard_topping_std-default")
      .assertIsNotSelected().assertTextContains("No charge", substring = true)
    composeTestRule.onNodeWithTag("builder_dock_total_price_text").assertTextContains("₹100.00", substring = true)
    composeTestRule.onNodeWithTag("builder_breakdown_toggle").performClick()
    composeTestRule.onNodeWithTag("builder_breakdown_standard_removed_std-default").assertExists()
    composeTestRule.onAllNodesWithTag("builder_total_price_text").assertCountEquals(1)
    composeTestRule.onNodeWithTag("builder_total_price_text").assertTextContains("₹100.00", substring = true)
  }

  @Test
  fun nonDefaultStandardToppingUsesServerQuoteForLivePrice() {
    val quoteSource = FakeQuoteSource { input ->
      if ("std-extra" in input.standardToppingIds) 11_000L else 10_000L
    }
    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = testProduct(),
          crusts = crusts,
          toppings = toppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
          domainBridge = domainBridge,
          quoteSource = quoteSource,
        )
      }
    }

    composeTestRule.onNodeWithTag("builder_standard_topping_std-extra")
      .performScrollTo().performClick()
    waitForServerPrice(quoteSource)

    assertEquals(listOf("std-default", "std-extra"), quoteSource.lastRequest?.standardToppingIds)
    composeTestRule.onNodeWithTag("builder_dock_total_price_text").assertTextContains("₹110.00", substring = true)
    composeTestRule.onNodeWithTag("builder_breakdown_toggle").performClick()
    composeTestRule.onNodeWithTag("builder_price_source_server")
      .performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("builder_breakdown_standard_std-extra")
      .performScrollTo().assertIsDisplayed()
      .assert(hasAnyDescendant(hasText("+ ₹10.00", substring = true)))
  }

  @Test
  fun quoteFailureShowsInlineErrorAndBlocksSubmission() {
    val quoteSource = FakeQuoteSource { error("server down") }
    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = testProduct(),
          crusts = crusts,
          toppings = toppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, _ -> error("add must be blocked while price is unavailable") },
          domainBridge = domainBridge,
          quoteSource = quoteSource,
        )
      }
    }

    composeTestRule.onNodeWithTag("builder_standard_topping_std-extra")
      .performScrollTo().performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodesWithTag("builder_quote_error").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("builder_quote_error")
      .performScrollTo().assertIsDisplayed().assertTextContains("server down", substring = true)
    composeTestRule.onNodeWithTag("add_to_cart_button").assertIsNotEnabled()
  }

  @Test
  fun inactiveModifierOptionsAndWholeEmptySectionsAreHidden() {
    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = testProduct(),
          crusts = crusts,
          toppings = toppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
          domainBridge = domainBridge,
        )
      }
    }

    composeTestRule.onNodeWithTag("builder_cheese_section").assertExists()
    composeTestRule.onNodeWithTag("builder_sauce_section").assertExists()
    composeTestRule.onNodeWithTag("builder_standard_toppings_section").assertExists()
    composeTestRule.onNodeWithTag("builder_half_and_half_section").assertExists()

    composeTestRule.onAllNodesWithTag("builder_cheese_option_cheese-off").assertCountEquals(0)
    composeTestRule.onAllNodesWithTag("builder_sauce_option_sauce-off").assertCountEquals(0)
    composeTestRule.onAllNodesWithTag("builder_standard_topping_std-off").assertCountEquals(0)
    composeTestRule.onAllNodesWithTag("builder_half_config_option_hh-off").assertCountEquals(0)
    composeTestRule.onNodeWithTag("builder_cheese_option_cheese-moz").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("builder_cheese_none").assertIsSelected()
  }

  @Test
  fun emptyModifierSectionsAreHidden() {
    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = testProduct(
            cheeses = emptyList(),
            sauces = emptyList(),
            standards = emptyList(),
            configs = emptyList(),
          ),
          crusts = crusts,
          toppings = toppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
          domainBridge = domainBridge,
        )
      }
    }
    composeTestRule.onAllNodesWithTag("builder_cheese_section").assertCountEquals(0)
    composeTestRule.onAllNodesWithTag("builder_sauce_section").assertCountEquals(0)
    composeTestRule.onAllNodesWithTag("builder_standard_toppings_section").assertCountEquals(0)
    composeTestRule.onAllNodesWithTag("builder_half_and_half_section").assertCountEquals(0)
  }

  @Test
  fun halfAndHalfEnforcesPerHalfDistinctAndOverlapLimitsInline() {
    val quoteSource = FakeQuoteSource { 15_000L }
    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = testProduct(),
          crusts = crusts,
          toppings = toppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
          domainBridge = domainBridge,
          quoteSource = quoteSource,
        )
      }
    }

    composeTestRule.onNodeWithTag("builder_half_config_option_hh-one").performScrollTo().performClick()
    (1..10).forEach { index ->
      composeTestRule.onNodeWithTag("builder_half_left_topping_top-$index")
        .performScrollTo().performClick()
    }
    // Let the valid 10-topping quote settle before checking that invalid states do not re-quote.
    waitForServerPrice(quoteSource)
    val quoteCountAtLimit = quoteSource.callCount
    composeTestRule.onNodeWithTag("builder_half_left_topping_top-11").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("builder_half_left_count").assertTextContains("11/10", substring = true)
    composeTestRule.onNodeWithTag("builder_half_and_half_validation")
      .assertTextContains("Left half cannot have more than 10 toppings.", substring = true)
    composeTestRule.onNodeWithTag("add_to_cart_button").assertIsNotEnabled()
    assertEquals(quoteCountAtLimit, quoteSource.callCount)

    // Back to 10 on the left, then 6 distinct on the right -> 16 distinct total.
    composeTestRule.onNodeWithTag("builder_half_left_topping_top-11").performScrollTo().performClick()
    (11..16).forEach { index ->
      composeTestRule.onNodeWithTag("builder_half_right_topping_top-$index")
        .performScrollTo().performClick()
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("builder_half_distinct_count").assertTextContains("16/15 distinct", substring = true)
    composeTestRule.onNodeWithTag("builder_half_and_half_validation")
      .assertTextContains("more than 15 distinct", substring = true)
    composeTestRule.onNodeWithTag("add_to_cart_button").assertIsNotEnabled()

    // Overlap is reported inline; nothing is auto-removed or substituted.
    composeTestRule.onNodeWithTag("builder_half_right_topping_top-1").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("builder_half_and_half_validation")
      .assertTextContains("Left and right halves cannot share a topping.", substring = true)
    composeTestRule.onNodeWithTag("builder_half_left_topping_top-1").assertIsSelected()
    composeTestRule.onNodeWithTag("builder_half_right_topping_top-1").assertIsSelected()
  }

  @Test
  fun emittedCartInputContainsEveryPhaseEightModifierId() {
    val quoteSource = FakeQuoteSource { 15_000L }
    var emitted: CartItemInputDto? = null
    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = testProduct(),
          crusts = crusts,
          toppings = toppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, _ -> error("legacy callback must not be used") },
          domainBridge = domainBridge,
          quoteSource = quoteSource,
          onConfirmAddWithModifiers = { input, onResult ->
            emitted = input
            onResult(true, null)
          },
        )
      }
    }

    composeTestRule.onNodeWithTag("builder_cheese_option_cheese-moz").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("builder_sauce_option_sauce-tomato").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("builder_standard_topping_std-extra").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("builder_half_config_option_hh-one").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("builder_half_left_topping_top-1").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("topping_option_top-2").performScrollTo().performClick()
    waitForServerPrice(quoteSource)
    composeTestRule.onNodeWithTag("add_to_cart_button").assertIsEnabled().performClick()
    composeTestRule.waitForIdle()

    val input = emitted ?: error("Extended add callback was not invoked")
    assertEquals("prod-8", input.productId)
    assertEquals("var-regular", input.variantId)
    assertEquals("crust-classic", input.crustId)
    assertEquals(listOf("top-2"), input.toppingIds)
    assertEquals("cheese-moz", input.cheeseId)
    assertEquals("sauce-tomato", input.sauceId)
    assertEquals(listOf("std-default", "std-extra"), input.standardToppingIds)
    assertEquals("hh-one", input.halfAndHalfConfigId)
    assertEquals(listOf("top-1"), input.halfAndHalfLeftToppingIds)
    assertEquals(emptyList<String>(), input.halfAndHalfRightToppingIds)
    assertEquals(15_000L, input.clientUnitPricePaise)
    assertNull(input.id)
  }

  @Test
  fun editModeRestoresModifiersFromResolvedCartView() {
    val cartEntry = CartItemWithToppings(
      item = CartItemEntity(
        id = "cart-item-1",
        userId = "user-1",
        productId = "prod-8",
        productName = "Phase Eight Pizza",
        variantId = "var-regular",
        sizeCode = "REGULAR",
        crustId = "crust-classic",
        crustName = "Classic",
        unitPricePaise = 15_000L,
        quantity = 2,
      ),
      toppings = listOf(
        CartItemToppingEntity("cart-item-1", "top-2", "Topping 2", 500L),
      ),
    )
    val resolved = CartItemViewDto(
      id = "cart-item-1",
      productId = "prod-8",
      productName = "Phase Eight Pizza",
      isVeg = true,
      variantId = "var-regular",
      sizeCode = SizeCode.REGULAR,
      basePricePaise = 10_000L,
      crustId = "crust-classic",
      crustName = "Classic",
      crustExtraPricePaise = 0L,
      quantity = 2,
      unitPricePaise = 15_000L,
      lineTotalPaise = 30_000L,
      isAvailable = true,
      cheese = CartCheeseViewDto("cheese-moz", "Mozzarella", 2_000L, isAvailable = true),
      sauce = CartSauceViewDto("sauce-tomato", "Tomato", 1_500L, isAvailable = true),
      standardToppings = listOf(
        CartStandardToppingViewDto(
          "std-default", "Basil", true, 1_000L,
          isDefault = true, isRemoved = false, isAvailable = true,
        ),
        CartStandardToppingViewDto(
          "std-extra", "Olive", true, 1_000L,
          isDefault = false, isRemoved = false, isAvailable = true,
        ),
        CartStandardToppingViewDto(
          "hh-removed-default", "Removed", true, 1_000L,
          isDefault = true, isRemoved = true, isAvailable = true,
        ),
      ),
      halfAndHalf = CartHalfAndHalfViewDto(
        halfAndHalfConfigId = "hh-one",
        name = "Two halves",
        leftToppingIds = listOf("top-1"),
        rightToppingIds = listOf("top-3"),
        halfAndHalfSurchargePaise = 1_000L,
        isAvailable = true,
      ),
    )

    composeTestRule.setContent {
      PizzoraTheme {
        PizzaBuilderScreen(
          product = testProduct(),
          crusts = crusts,
          toppings = toppings,
          onDismiss = {},
          onConfirmAdd = { _, _, _, _, onResult -> onResult(true, null) },
          mode = PizzaBuilderMode.Edit(cartItem = cartEntry, resolvedItem = resolved),
          domainBridge = domainBridge,
        )
      }
    }

    composeTestRule.onNodeWithTag("builder_cheese_option_cheese-moz").assertIsSelected()
    composeTestRule.onNodeWithTag("builder_sauce_option_sauce-tomato").assertIsSelected()
    composeTestRule.onNodeWithTag("builder_standard_topping_std-default").assertIsSelected()
    composeTestRule.onNodeWithTag("builder_standard_topping_std-extra").assertIsSelected()
    composeTestRule.onNodeWithTag("builder_half_config_option_hh-one").assertIsSelected()
    composeTestRule.onNodeWithTag("builder_half_left_topping_top-1").assertIsSelected()
    composeTestRule.onNodeWithTag("builder_half_right_topping_top-3").assertIsSelected()
    composeTestRule.onNodeWithTag("builder_dock_total_price_text").assertTextContains("₹300.00", substring = true)
  }

  @Test
  fun pureHelpersFollowContractConstants() {
    val defaultState = initialPizzaBuilderModifierState(testProduct(), resolvedItem = null)
    assertEquals(listOf("std-default"), defaultState.standardToppingIds)
    assertFalse(defaultState.requiresServerQuote(activeStandardToppingOptions(testProduct())))

    val withExtra = defaultState.copy(standardToppingIds = listOf("std-default", "std-extra"))
    assertTrue(withExtra.requiresServerQuote(activeStandardToppingOptions(testProduct())))

    val overLimit = withExtra.copy(
      halfAndHalfConfigId = "hh-one",
      halfAndHalfLeftToppingIds = (1..11).map { "top-$it" },
      halfAndHalfRightToppingIds = listOf("top-11"),
    )
    val validation = validateHalfAndHalfSelection(overLimit)
    assertTrue(validation.leftLimitExceeded)
    assertTrue(validation.leftRightOverlap)
    assertFalse(validation.isValid)

    val input = buildPizzaBuilderCartItemInput(
      productId = "prod-8",
      variant = variants.first(),
      crust = crusts.first(),
      extraToppings = listOf(toppings.first()),
      quantity = 2,
      modifiers = withExtra,
      itemId = "cart-item-1",
      clientUnitPricePaise = 11_000L,
    )
    assertEquals("cart-item-1", input.id)
    assertEquals(listOf("std-default", "std-extra"), input.standardToppingIds)
    assertEquals(11_000L, input.clientUnitPricePaise)

    val quote = CartItemViewDto(
      id = "quote", productId = "prod-8", productName = "Pizza", isVeg = true,
      variantId = "var-regular", sizeCode = SizeCode.REGULAR, basePricePaise = 10_000L,
      crustId = "crust-classic", crustName = "Classic", crustExtraPricePaise = 0L,
      quantity = 2, unitPricePaise = 11_000L, lineTotalPaise = 22_000L, isAvailable = true,
    )
    val breakdown = calculatePizzaBuilderBreakdown(
      sortedVariants = variants,
      crusts = crusts,
      toppings = toppings,
      selectedVariantId = "var-regular",
      selectedCrustId = "crust-classic",
      selectedToppingIds = listOf("top-1"),
      quantity = 2,
      domainBridge = domainBridge,
      standardToppings = standardToppings,
      modifiers = withExtra,
      quotedItem = quote,
    )
    assertEquals(11_000L, breakdown.unitPricePaise)
    assertEquals(PizzaBuilderPriceSource.SERVER_QUOTE, breakdown.priceSource)
    assertEquals(22_000L, breakdown.itemTotalPaise)
    assertEquals(listOf("std-default", "std-extra"), breakdown.selectedStandardToppings.map { it.id })
  }
}

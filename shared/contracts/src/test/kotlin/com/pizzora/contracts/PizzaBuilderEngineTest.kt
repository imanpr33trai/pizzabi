package com.pizzora.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PizzaBuilderEngineTest {
  private val cheeseMozzarella = CheeseOptionDto("ch-mozz", "Mozzarella", 0L, 6_000L, true, false, true)
  private val cheeseVegan = CheeseOptionDto("ch-vegan", "Vegan Cashew", 7_500L, 8_000L, true, true, true)
  private val cheeseNoExtra = CheeseOptionDto("ch-ricotta", "Whipped Ricotta", 4_000L, 0L, false, false, true)

  private val sauceMarinara = SauceOptionDto("sc-marinara", "San Marzano Marinara", 0L, 2_500L, true, true, true)
  private val saucePesto = SauceOptionDto("sc-pesto", "Genovese Pesto", 4_500L, 3_500L, true, false, true)

  private val toppingBasil = ToppingDto("tp-basil", "Fresh Basil", true, 2_500L, true, maxQuantity = 2, supportsHalfAndHalf = true)
  private val toppingMushroom = ToppingDto("tp-mush", "Wild Mushroom", true, 5_500L, true, maxQuantity = 2, supportsHalfAndHalf = true)
  private val toppingTruffle = ToppingDto("tp-truffle", "Truffle Oil", true, 9_000L, true, maxQuantity = 1, supportsHalfAndHalf = false)

  private val crustHand = CrustOptionDto("cr-hand", "Hand-Tossed", 0L, true, supportsHalfAndHalf = true)
  private val crustCheeseBurst = CrustOptionDto("cr-burst", "Cheese Burst", 8_000L, true, supportsHalfAndHalf = false)

  private val margherita =
    ProductDto(
      id = "prod-margh",
      categoryId = "cat-1",
      categoryName = "Classics",
      name = "Margherita",
      description = "Classic pizza",
      isVeg = true,
      isActive = true,
      isTaxable = true,
      variants =
        listOf(
          ProductVariantDto("var-margh-reg", "prod-margh", SizeCode.REGULAR, 29_900L, true),
          ProductVariantDto("var-margh-med", "prod-margh", SizeCode.MEDIUM, 44_900L, true),
          ProductVariantDto("var-margh-lrg", "prod-margh", SizeCode.LARGE, 59_900L, true),
          ProductVariantDto("var-margh-xl", "prod-margh", SizeCode.EXTRA_LARGE, 74_900L, true),
        ),
      isCustomizable = true,
      supportsHalfAndHalf = true,
      defaultCheeseId = cheeseMozzarella.id,
      defaultSauceId = sauceMarinara.id,
      includedToppingIds = listOf(toppingBasil.id),
      maxToppingsOverride = null,
    )

  private val farmhouse =
    ProductDto(
      id = "prod-farm",
      categoryId = "cat-1",
      categoryName = "Artisanal",
      name = "Farmhouse",
      description = "Loaded veggies",
      isVeg = true,
      isActive = true,
      isTaxable = true,
      variants =
        listOf(
          ProductVariantDto("var-farm-med", "prod-farm", SizeCode.MEDIUM, 52_900L, true),
          ProductVariantDto("var-farm-lrg", "prod-farm", SizeCode.LARGE, 69_900L, true),
        ),
      isCustomizable = true,
      supportsHalfAndHalf = true,
      defaultCheeseId = cheeseMozzarella.id,
      defaultSauceId = saucePesto.id,
      includedToppingIds = listOf(toppingMushroom.id),
      maxToppingsOverride = 6,
    )

  private val sampleMenu =
    PublicMenuResponse(
      categories = listOf(CategoryDto("cat-1", "Classics", 1, true, "2026-01-01T00:00:00Z")),
      products = listOf(margherita, farmhouse),
      crusts = listOf(crustHand, crustCheeseBurst),
      toppings = listOf(toppingBasil, toppingMushroom, toppingTruffle),
      gstBps = 500,
      storeName = "PIZZORA",
      isAcceptingOrders = true,
      sizeRules =
        listOf(
          PizzaSizeRuleDto(SizeCode.REGULAR, "Regular", maxToppings = 5, maxExtraToppings = 3, supportsHalfAndHalf = false, ingredientMultiplierBps = 7_500),
          PizzaSizeRuleDto(SizeCode.MEDIUM, "Medium", maxToppings = 7, maxExtraToppings = 4, supportsHalfAndHalf = true, ingredientMultiplierBps = 10_000),
          PizzaSizeRuleDto(SizeCode.LARGE, "Large", maxToppings = 9, maxExtraToppings = 5, supportsHalfAndHalf = true, ingredientMultiplierBps = 13_500),
          PizzaSizeRuleDto(SizeCode.EXTRA_LARGE, "Extra Large", maxToppings = 11, maxExtraToppings = 6, supportsHalfAndHalf = true, ingredientMultiplierBps = 17_000),
        ),
      cheeses = listOf(cheeseMozzarella, cheeseVegan, cheeseNoExtra),
      sauces = listOf(sauceMarinara, saucePesto),
    )

  @Test
  fun `evaluates extra large pizza with alternative cheese extra cheese included and extra toppings`() {
    val input =
      CartItemInputDto(
        productId = margherita.id,
        variantId = "var-margh-xl",
        crustId = crustHand.id,
        quantity = 2,
        cheeseId = cheeseVegan.id,
        extraCheese = true,
        sauceId = saucePesto.id,
        extraSauce = true,
        toppingSelections =
          listOf(
            // Basil is included on margherita -> qty 2 bills only 1 unit (2_500)
            CartToppingInputDto(toppingBasil.id, ToppingPlacement.WHOLE, 2),
            // Mushroom is not included -> qty 1 bills 5_500
            CartToppingInputDto(toppingMushroom.id, ToppingPlacement.WHOLE, 1),
          ),
      )

    val eval = PizzaBuilderEngine.evaluate(input, sampleMenu)
    assertTrue(eval.isValid)
    assertEquals(74_900L, eval.priceBreakdown.basePricePaise)
    assertEquals(0L, eval.priceBreakdown.halfAndHalfSurchargePaise)
    assertEquals(7_500L, eval.priceBreakdown.cheesePricePaise)
    assertEquals(8_000L, eval.priceBreakdown.extraCheesePricePaise)
    assertEquals(4_500L, eval.priceBreakdown.saucePricePaise)
    assertEquals(3_500L, eval.priceBreakdown.extraSaucePricePaise)
    assertEquals(8_000L, eval.priceBreakdown.toppingsPricePaise)
    assertEquals(106_400L, eval.unitPricePaise)
    assertEquals(212_800L, eval.lineTotalPaise)
  }

  @Test
  fun `half and half prices max variant prevents arbitrage and halves sauce and half toppings`() {
    val input =
      CartItemInputDto(
        productId = margherita.id,
        variantId = "var-margh-med", // 44_900
        crustId = crustHand.id,
        quantity = 1,
        cheeseId = cheeseMozzarella.id,
        sauceId = sauceMarinara.id, // 0
        extraSauce = true, // left extra = 2_500 * 50% = 1_250
        isHalfAndHalf = true,
        rightProductId = farmhouse.id,
        rightVariantId = "var-farm-med", // 52_900 -> surcharge 8_000
        rightSauceId = saucePesto.id, // 4_500 * 50% = 2_250, extra = 3_500 * 50% = 1_750
        toppingSelections =
          listOf(
            CartToppingInputDto(toppingMushroom.id, ToppingPlacement.LEFT, 1), // 5_500 * 50% = 2_750
          ),
      )

    val eval = PizzaBuilderEngine.evaluate(input, sampleMenu)
    assertTrue(eval.isValid)
    assertEquals(44_900L, eval.priceBreakdown.basePricePaise)
    assertEquals(8_000L, eval.priceBreakdown.halfAndHalfSurchargePaise)
    assertEquals(2_250L, eval.priceBreakdown.saucePricePaise)
    assertEquals(3_000L, eval.priceBreakdown.extraSaucePricePaise)
    assertEquals(2_750L, eval.priceBreakdown.toppingsPricePaise)
    assertEquals(60_900L, eval.unitPricePaise)
  }

  @Test
  fun `rejects invalid half and half crust size placement and limits`() {
    val badInput =
      CartItemInputDto(
        productId = margherita.id,
        variantId = "var-margh-reg", // REGULAR does not support H&H
        crustId = crustCheeseBurst.id, // CheeseBurst does not support H&H
        quantity = 1,
        cheeseId = cheeseNoExtra.id,
        extraCheese = true, // supportsExtra = false
        isHalfAndHalf = true,
        rightProductId = farmhouse.id,
        rightVariantId = "var-farm-med", // size mismatch
        removedIncludedToppingIds = listOf(toppingTruffle.id), // not included on either half
        toppingSelections =
          listOf(
            CartToppingInputDto(toppingTruffle.id, ToppingPlacement.LEFT, 2), // no H&H + qty > max(1)
            CartToppingInputDto(toppingTruffle.id, ToppingPlacement.LEFT, 1), // duplicate
          ),
      )

    val eval = PizzaBuilderEngine.evaluate(badInput, sampleMenu)
    assertFalse(eval.isValid)
    val codes = eval.issues.map { it.code }.toSet()
    assertTrue(CartValidationIssueCode.EXTRA_CHEESE_UNAVAILABLE in codes)
    assertTrue(CartValidationIssueCode.HALF_AND_HALF_UNSUPPORTED in codes)
    assertTrue(CartValidationIssueCode.RIGHT_HALF_INVALID in codes)
    assertTrue(CartValidationIssueCode.INCLUDED_TOPPING_INVALID in codes)
    assertTrue(CartValidationIssueCode.TOPPING_PLACEMENT_INVALID in codes)
    assertTrue(CartValidationIssueCode.INVALID_QUANTITY in codes)
    assertTrue(CartValidationIssueCode.DUPLICATE_SELECTION in codes)
  }

  @Test
  fun `canonical signature matches default resolved vs explicit and separates distinct customizations`() {
    val implicitDefault =
      CartItemInputDto(
        productId = margherita.id,
        variantId = "var-margh-med",
        crustId = crustHand.id,
        quantity = 1,
      )
    val explicitDefault =
      implicitDefault.copy(
        cheeseId = cheeseMozzarella.id,
        sauceId = sauceMarinara.id,
      )
    val withExtraCheese = explicitDefault.copy(extraCheese = true)

    assertEquals(
      PizzaBuilderEngine.computeConfigurationSignature(implicitDefault, sampleMenu),
      PizzaBuilderEngine.computeConfigurationSignature(explicitDefault, sampleMenu),
    )
    assertNotEquals(
      PizzaBuilderEngine.computeConfigurationSignature(explicitDefault, sampleMenu),
      PizzaBuilderEngine.computeConfigurationSignature(withExtraCheese, sampleMenu),
    )
  }
}

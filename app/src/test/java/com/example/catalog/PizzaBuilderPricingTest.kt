package com.example.catalog

import com.example.core.domain.KotlinContractsDomainBridge
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PizzaBuilderPricingTest {

  private val domainBridge = KotlinContractsDomainBridge()

  private val variants = listOf(
    ProductVariantDto("var-large", "prod-1", SizeCode.LARGE, pricePaise = 54_900L, isActive = true),
    ProductVariantDto("var-regular", "prod-1", SizeCode.REGULAR, pricePaise = 24_900L, isActive = false),
    ProductVariantDto("var-medium", "prod-1", SizeCode.MEDIUM, pricePaise = 39_900L, isActive = true),
  )

  private val crusts = listOf(
    CrustOptionDto("crust-inactive", "Stuffed Inactive", extraPricePaise = 9_000L, isActive = false),
    CrustOptionDto("crust-classic", "Classic Hand Tossed", extraPricePaise = 0L, isActive = true),
    CrustOptionDto("crust-sourdough", "Sourdough", extraPricePaise = 5_000L, isActive = true),
  )

  private val toppings = listOf(
    ToppingDto("top-basil", "Extra Basil", isVeg = true, extraPricePaise = 2_500L, isActive = true),
    ToppingDto("top-pep", "Smoked Pepperoni", isVeg = false, extraPricePaise = 7_500L, isActive = true),
    ToppingDto("top-truffle", "Truffle Oil", isVeg = true, extraPricePaise = 9_500L, isActive = false),
  )

  @Test
  fun firstActiveDefaultsSkipInactiveVariantsAndCrusts() {
    val defaultVariantId = firstActiveVariantId(variants)
    val defaultCrustId = firstActiveCrustId(crusts)

    // Sorted by SizeCode ordinal (REGULAR is inactive -> selects MEDIUM)
    assertEquals("var-medium", defaultVariantId)
    assertEquals("crust-classic", defaultCrustId)
  }

  @Test
  fun calculateBreakdownMatchesDomainBridgeAndIgnoresInactiveOptions() {
    val sorted = variants.sortedBy { it.sizeCode.ordinal }
    val breakdown = calculatePizzaBuilderBreakdown(
      sortedVariants = sorted,
      crusts = crusts,
      toppings = toppings,
      selectedVariantId = "var-large",
      selectedCrustId = "crust-sourdough",
      // Include inactive topping ID to ensure inactive options never leak into price
      selectedToppingIds = listOf("top-basil", "top-pep", "top-truffle"),
      quantity = 3,
      domainBridge = domainBridge,
    )

    assertTrue(breakdown.hasRequiredSelections)
    assertEquals(54_900L, breakdown.baseSizePaise)
    assertEquals(5_000L, breakdown.crustExtraPaise)
    // Only active toppings (2500 + 7500 = 10000); inactive truffle ignored
    assertEquals(10_000L, breakdown.toppingsExtraPaise)
    assertEquals(listOf("top-basil", "top-pep"), breakdown.selectedToppings.map { it.id })
    assertEquals(69_900L, breakdown.unitPricePaise)
    assertEquals(domainBridge.calculateLineTotalPaise(69_900L, 3), breakdown.itemTotalPaise)
    assertEquals(209_700L, breakdown.itemTotalPaise)
  }

  @Test
  fun inactiveVariantOrCrustYieldsZeroTotalAndIncompleteSelections() {
    val sorted = variants.sortedBy { it.sizeCode.ordinal }
    val inactiveVariantBreakdown = calculatePizzaBuilderBreakdown(
      sortedVariants = sorted,
      crusts = crusts,
      toppings = toppings,
      selectedVariantId = "var-regular", // inactive
      selectedCrustId = "crust-classic",
      selectedToppingIds = listOf("top-basil"),
      quantity = 2,
      domainBridge = domainBridge,
    )
    assertFalse(inactiveVariantBreakdown.hasRequiredSelections)
    assertNull(inactiveVariantBreakdown.variant)
    assertEquals(0L, inactiveVariantBreakdown.itemTotalPaise)

    val inactiveCrustBreakdown = calculatePizzaBuilderBreakdown(
      sortedVariants = sorted,
      crusts = crusts,
      toppings = toppings,
      selectedVariantId = "var-medium",
      selectedCrustId = "crust-inactive",
      selectedToppingIds = emptyList(),
      quantity = 1,
      domainBridge = domainBridge,
    )
    assertFalse(inactiveCrustBreakdown.hasRequiredSelections)
    assertNull(inactiveCrustBreakdown.crust)
    assertEquals(0L, inactiveCrustBreakdown.itemTotalPaise)
  }

  @Test
  fun quantityClampsStrictlyBetweenOneAndTwentyFive() {
    val sorted = variants.sortedBy { it.sizeCode.ordinal }
    val under = calculatePizzaBuilderBreakdown(
      sortedVariants = sorted,
      crusts = crusts,
      toppings = toppings,
      selectedVariantId = "var-medium",
      selectedCrustId = "crust-classic",
      selectedToppingIds = emptyList(),
      quantity = 0,
      domainBridge = domainBridge,
    )
    assertEquals(MIN_PIZZA_QTY, under.quantity)
    assertEquals(39_900L, under.itemTotalPaise)

    val over = calculatePizzaBuilderBreakdown(
      sortedVariants = sorted,
      crusts = crusts,
      toppings = toppings,
      selectedVariantId = "var-medium",
      selectedCrustId = "crust-classic",
      selectedToppingIds = emptyList(),
      quantity = 99,
      domainBridge = domainBridge,
    )
    assertEquals(MAX_PIZZA_QTY, over.quantity)
    assertEquals(domainBridge.calculateLineTotalPaise(39_900L, 25), over.itemTotalPaise)
  }

  @Test
  fun unsavedChangesAndUnsupportedModifierSlotsBehaveDeterministically() {
    assertFalse(
      hasBuilderUnsavedChanges(
        selectedVariantId = "var-medium",
        selectedCrustId = "crust-classic",
        selectedToppingIds = emptyList(),
        quantity = 1,
        initialVariantId = "var-medium",
        initialCrustId = "crust-classic",
      ),
    )
    assertTrue(
      hasBuilderUnsavedChanges(
        selectedVariantId = "var-large",
        selectedCrustId = "crust-classic",
        selectedToppingIds = emptyList(),
        quantity = 1,
        initialVariantId = "var-medium",
        initialCrustId = "crust-classic",
      ),
    )
    assertTrue(
      hasBuilderUnsavedChanges(
        selectedVariantId = "var-medium",
        selectedCrustId = "crust-classic",
        selectedToppingIds = listOf("top-basil"),
        quantity = 1,
        initialVariantId = "var-medium",
        initialCrustId = "crust-classic",
      ),
    )
    assertTrue(
      hasBuilderUnsavedChanges(
        selectedVariantId = "var-medium",
        selectedCrustId = "crust-classic",
        selectedToppingIds = emptyList(),
        quantity = 2,
        initialVariantId = "var-medium",
        initialCrustId = "crust-classic",
      ),
    )

    val slots = defaultUnsupportedModifierSlots()
    assertEquals(4, slots.size)
    assertTrue(slots.none { it.isSupported })
  }
}

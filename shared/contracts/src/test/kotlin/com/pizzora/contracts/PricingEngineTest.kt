package com.pizzora.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class PricingEngineTest {
  @Test
  fun `flat and percent coupons respect min order, max discount cap, and subtotal cap`() {
    val percentCoupon = CouponCalculationSpec(
      discountType = DiscountType.PERCENT_BPS,
      discountValue = 2000L, // 20%
      maxDiscountPaise = 15_000L, // max Rs 150
      minOrderPaise = 50_000L, // min Rs 500
    )

    // Below min order -> 0 discount
    assertEquals(0L, PricingEngine.calculateDiscountPaise(49_999L, percentCoupon))
    // 20% of 60,000 = 12,000 (< 15,000 cap)
    assertEquals(12_000L, PricingEngine.calculateDiscountPaise(60_000L, percentCoupon))
    // 20% of 100,000 = 20,000 -> capped at 15,000
    assertEquals(15_000L, PricingEngine.calculateDiscountPaise(100_000L, percentCoupon))

    val flatOversizedCoupon = CouponCalculationSpec(
      discountType = DiscountType.FLAT_PAISE,
      discountValue = 50_000L,
      minOrderPaise = 0L,
    )
    // Flat discount never makes subtotal negative
    assertEquals(29_900L, PricingEngine.calculateDiscountPaise(29_900L, flatOversizedCoupon))
  }

  @Test
  fun `proportional discount across mixed taxable and exempt lines computes exact GST and CGST plus SGST`() {
    val lines = listOf(
      PricedLineInput(unitPricePaise = 40_000L, quantity = 1, isTaxable = true),
      PricedLineInput(unitPricePaise = 10_000L, quantity = 1, isTaxable = false),
    )
    val coupon = CouponCalculationSpec(
      discountType = DiscountType.FLAT_PAISE,
      discountValue = 5_000L,
    )

    val breakdown = PricingEngine.calculateQuote(
      lines = lines,
      gstBps = 500, // 5%
      deliveryFeePaise = 4_000L,
      coupon = coupon,
    )

    // subtotal = 50,000, taxableSubtotal = 40,000, discount = 5,000
    // taxableDiscount = (5,000 * 40,000) / 50,000 = 4,000 -> taxableAmount = 36,000
    // 5% GST of 36,000 = 1,800 -> CGST 900 + SGST 900
    // total = (50,000 - 5,000) + 4,000 + 1,800 = 50,800
    assertEquals(50_000L, breakdown.subtotalPaise)
    assertEquals(40_000L, breakdown.taxableSubtotalPaise)
    assertEquals(5_000L, breakdown.discountPaise)
    assertEquals(36_000L, breakdown.taxableAmountPaise)
    assertEquals(1_800L, breakdown.taxPaise)
    assertEquals(900L, breakdown.cgstPaise)
    assertEquals(900L, breakdown.sgstPaise)
    assertEquals(4_000L, breakdown.deliveryFeePaise)
    assertEquals(50_800L, breakdown.totalPaise)
  }

  @Test
  fun `overflow and invalid bounds throw ArithmeticException or IllegalArgumentException`() {
    assertThrows(ArithmeticException::class.java) {
      PricingEngine.calculateQuote(
        lines = listOf(
          PricedLineInput(unitPricePaise = Long.MAX_VALUE / 2L, quantity = 3, isTaxable = true),
        ),
        gstBps = 500,
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      PricingEngine.calculateQuote(
        lines = listOf(PricedLineInput(unitPricePaise = 10_000L, quantity = 1)),
        gstBps = 2900,
      )
    }
  }
}

@RunWith(Parameterized::class)
class PricingEngineParameterizedRoundingTest(
  private val subtotalPaise: Long,
  private val percentBps: Long,
  private val gstBps: Int,
  private val deliveryFeePaise: Long,
  private val expectedDiscountPaise: Long,
  private val expectedTaxPaise: Long,
  private val expectedCgstPaise: Long,
  private val expectedSgstPaise: Long,
  private val expectedTotalPaise: Long,
) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(
      name = "sub={0}, bps={1}, gst={2}, del={3} -> disc={4}, tax={5}, cgst={6}, sgst={7}, total={8}",
    )
    fun cases(): Collection<Array<Any>> = listOf(
      // Case 1: odd tax paise (5% of 19,900 = 995 -> CGST 497, SGST 498)
      arrayOf(19_900L, 0L, 500, 0L, 0L, 995L, 497L, 498L, 20_895L),
      // Case 2: half-up rounding on discount: 105 * 250 / 10000 = 2.625 -> 3; taxable = 102; tax = 102 * 500 / 10000 = 5.1 -> 5
      arrayOf(105L, 250L, 500, 0L, 3L, 5L, 2L, 3L, 107L),
      // Case 3: exact .5 tie rounds UP: 100 * 250 / 10000 = 2.5 -> 3; taxable = 97; 97 * 500 / 10000 = 4.85 -> 5
      arrayOf(100L, 250L, 500, 2_500L, 3L, 5L, 2L, 3L, 2_602L),
      // Case 4: high subtotal safe from 32-bit overflow: 50_000_000_00L (Rs 5 crore), 1234 bps, 18% GST (1800 bps)
      // discount = 5_000_000_000 * 1234 / 10000 = 617_000_000; taxable = 4_383_000_000
      // tax = 4_383_000_000 * 1800 / 10000 = 788_940_000; cgst = 394_470_000; sgst = 394_470_000
      arrayOf(
        5_000_000_000L,
        1234L,
        1800,
        5_000L,
        617_000_000L,
        788_940_000L,
        394_470_000L,
        394_470_000L,
        5_171_945_000L,
      ),
      // Case 5: 0% GST exemption
      arrayOf(44_900L, 1000L, 0, 3_000L, 4_490L, 0L, 0L, 0L, 43_410L),
    )
  }

  @Test
  fun `verifies deterministic integer-paise breakdown and CGST plus SGST invariant`() {
    val coupon = if (percentBps > 0L) {
      CouponCalculationSpec(discountType = DiscountType.PERCENT_BPS, discountValue = percentBps)
    } else {
      null
    }
    val breakdown = PricingEngine.calculateQuote(
      lines = listOf(PricedLineInput(unitPricePaise = subtotalPaise, quantity = 1, isTaxable = true)),
      gstBps = gstBps,
      deliveryFeePaise = deliveryFeePaise,
      coupon = coupon,
    )
    assertEquals(expectedDiscountPaise, breakdown.discountPaise)
    assertEquals(expectedTaxPaise, breakdown.taxPaise)
    assertEquals(expectedCgstPaise, breakdown.cgstPaise)
    assertEquals(expectedSgstPaise, breakdown.sgstPaise)
    assertEquals(expectedTaxPaise, breakdown.cgstPaise + breakdown.sgstPaise)
    assertEquals(expectedTotalPaise, breakdown.totalPaise)
  }
}

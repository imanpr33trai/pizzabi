package com.pizzora.contracts

import java.math.BigInteger

data class PricedLineInput(
  val unitPricePaise: Long,
  val quantity: Int,
  val isTaxable: Boolean = true,
)

data class CouponCalculationSpec(
  val discountType: DiscountType,
  val discountValue: Long,
  val maxDiscountPaise: Long? = null,
  val minOrderPaise: Long = 0L,
  val isActive: Boolean = true,
)

object PricingEngine {
  private val TWO = BigInteger.valueOf(2L)
  private val BPS_DENOMINATOR = BigInteger.valueOf(10_000L)
  private val HALF_BPS = BigInteger.valueOf(5_000L)

  fun calculateLineTotalPaise(unitPricePaise: Long, quantity: Int): Long {
    require(unitPricePaise >= 0L) { "unitPricePaise must be non-negative" }
    require(quantity > 0) { "quantity must be positive" }
    return Math.multiplyExact(unitPricePaise, quantity.toLong())
  }

  fun calculateDiscountPaise(subtotalPaise: Long, coupon: CouponCalculationSpec?): Long {
    require(subtotalPaise >= 0L) { "subtotalPaise must be non-negative" }
    if (coupon == null || !coupon.isActive || subtotalPaise == 0L) return 0L
    require(coupon.discountValue > 0L) { "discountValue must be positive" }
    require(coupon.minOrderPaise >= 0L) { "minOrderPaise must be non-negative" }
    coupon.maxDiscountPaise?.let { require(it > 0L) { "maxDiscountPaise must be positive" } }

    if (subtotalPaise < coupon.minOrderPaise) return 0L

    val rawDiscount = when (coupon.discountType) {
      DiscountType.PERCENT_BPS -> {
        require(coupon.discountValue <= 10_000L) { "PERCENT_BPS discountValue cannot exceed 10000" }
        BigInteger.valueOf(subtotalPaise)
          .multiply(BigInteger.valueOf(coupon.discountValue))
          .add(HALF_BPS)
          .divide(BPS_DENOMINATOR)
          .longValueExact()
      }
      DiscountType.FLAT_PAISE -> coupon.discountValue
    }

    val afterMaxCap = coupon.maxDiscountPaise?.let { minOf(rawDiscount, it) } ?: rawDiscount
    return minOf(afterMaxCap, subtotalPaise).coerceAtLeast(0L)
  }

  fun calculateQuote(
    lines: List<PricedLineInput>,
    gstBps: Int,
    deliveryFeePaise: Long = 0L,
    coupon: CouponCalculationSpec? = null,
  ): PriceBreakdownDto {
    require(gstBps in 0..2800) { "gstBps must be between 0 and 2800" }
    require(deliveryFeePaise >= 0L) { "deliveryFeePaise must be non-negative" }

    var subtotalPaise = 0L
    var taxableSubtotalPaise = 0L

    for (line in lines) {
      val lineTotal = calculateLineTotalPaise(line.unitPricePaise, line.quantity)
      subtotalPaise = Math.addExact(subtotalPaise, lineTotal)
      if (line.isTaxable) {
        taxableSubtotalPaise = Math.addExact(taxableSubtotalPaise, lineTotal)
      }
    }

    val discountPaise = calculateDiscountPaise(subtotalPaise, coupon)

    val taxableAmountPaise = if (taxableSubtotalPaise == 0L || subtotalPaise == 0L) {
      0L
    } else if (taxableSubtotalPaise == subtotalPaise) {
      subtotalPaise - discountPaise
    } else {
      // Proportional BigInteger HALF_UP allocation of discount to taxable items
      val subtotalBi = BigInteger.valueOf(subtotalPaise)
      val taxableDiscount = BigInteger.valueOf(discountPaise)
        .multiply(BigInteger.valueOf(taxableSubtotalPaise))
        .add(subtotalBi.divide(TWO))
        .divide(subtotalBi)
        .longValueExact()
      (taxableSubtotalPaise - taxableDiscount).coerceAtLeast(0L)
    }

    val taxPaise = Money.bpsOf(taxableAmountPaise, gstBps)
    val cgstPaise = taxPaise / 2L
    val sgstPaise = taxPaise - cgstPaise

    val netItemsTotal = (subtotalPaise - discountPaise).coerceAtLeast(0L)
    val totalPaise = Math.addExact(Math.addExact(netItemsTotal, deliveryFeePaise), taxPaise)

    return PriceBreakdownDto(
      subtotalPaise = subtotalPaise,
      taxableSubtotalPaise = taxableSubtotalPaise,
      discountPaise = discountPaise,
      taxableAmountPaise = taxableAmountPaise,
      deliveryFeePaise = deliveryFeePaise,
      gstBps = gstBps,
      taxPaise = taxPaise,
      cgstPaise = cgstPaise,
      sgstPaise = sgstPaise,
      totalPaise = totalPaise,
    )
  }
}

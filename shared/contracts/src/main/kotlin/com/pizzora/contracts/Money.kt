package com.pizzora.contracts

import java.math.BigInteger
import java.util.Locale

object Money {
  private val BPS_DENOMINATOR = BigInteger.valueOf(10_000L)
  private val HALF_BPS = BigInteger.valueOf(5_000L)

  fun bpsOf(amountPaise: Long, bps: Int): Long {
    require(amountPaise >= 0L) { "amountPaise must be non-negative" }
    require(bps >= 0) { "bps must be non-negative" }
    if (amountPaise == 0L || bps == 0) return 0L
    val numerator = BigInteger.valueOf(amountPaise)
      .multiply(BigInteger.valueOf(bps.toLong()))
      .add(HALF_BPS)
    return numerator.divide(BPS_DENOMINATOR).longValueExact()
  }

  fun formatInr(paise: Long): String {
    val sign = if (paise < 0L) "-" else ""
    val absPaise = kotlin.math.abs(paise)
    val rupees = absPaise / 100L
    val remPaise = absPaise % 100L
    return String.format(Locale.US, "%s₹%d.%02d", sign, rupees, remPaise)
  }
}

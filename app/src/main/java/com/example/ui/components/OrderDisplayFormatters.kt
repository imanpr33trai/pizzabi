package com.example.ui.components

import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.NotificationCategory
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Centralized, 100% Android API 24-safe (zero java.time usage) customer-facing
 * display formatters for order statuses, payments, notification categories, and
 * IST (Asia/Kolkata) dates and timestamps.
 */
object OrderDisplayFormatters {
  private val istTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
  private val isoRegex = Regex(
    """^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$""",
  )

  fun formatOrderStatus(
    status: OrderStatus,
    fulfillmentType: FulfillmentType? = null,
  ): String = when (status) {
    OrderStatus.PENDING_PAYMENT -> "Payment Pending"
    OrderStatus.PLACED -> "Order Placed"
    OrderStatus.ACCEPTED -> "Confirmed by Kitchen"
    OrderStatus.PREPARING -> "In the Oven"
    OrderStatus.READY -> when (fulfillmentType) {
      FulfillmentType.PICKUP -> "Ready for Pickup"
      FulfillmentType.DELIVERY -> "Ready for Delivery"
      null -> "Fresh & Ready"
    }
    OrderStatus.OUT_FOR_DELIVERY -> "Out for Delivery"
    OrderStatus.COMPLETED -> when (fulfillmentType) {
      FulfillmentType.PICKUP -> "Picked Up"
      FulfillmentType.DELIVERY -> "Delivered"
      null -> "Completed"
    }
    OrderStatus.CANCELLED -> "Cancelled"
    OrderStatus.EXPIRED -> "Expired"
  }

  fun formatFulfillmentType(fulfillmentType: FulfillmentType): String = when (fulfillmentType) {
    FulfillmentType.DELIVERY -> "Delivery"
    FulfillmentType.PICKUP -> "Pickup"
  }

  fun formatPaymentMethod(method: PaymentMethod): String = when (method) {
    PaymentMethod.RAZORPAY -> "Razorpay"
    PaymentMethod.COD -> "Cash on Delivery"
  }

  fun formatPaymentStatus(
    status: PaymentStatus,
    fulfillmentType: FulfillmentType? = null,
  ): String = when (status) {
    PaymentStatus.UNPAID -> "Unpaid"
    PaymentStatus.COD_PENDING -> if (fulfillmentType == FulfillmentType.PICKUP) {
      "Pay at Pickup"
    } else {
      "Pay on Delivery"
    }
    PaymentStatus.COD_COLLECTED -> "Paid in Cash"
    PaymentStatus.CAPTURED -> "Paid"
    PaymentStatus.EXPIRED -> "Expired"
    PaymentStatus.CANCELLED -> "Cancelled"
    PaymentStatus.REFUND_PENDING -> "Refund Pending"
    PaymentStatus.PARTIALLY_REFUNDED -> "Partially Refunded"
    PaymentStatus.REFUNDED -> "Refunded"
  }

  fun formatPaymentAttemptStatus(status: PaymentAttemptStatus): String = when (status) {
    PaymentAttemptStatus.INITIATING -> "Preparing secure checkout..."
    PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT -> "Waiting for payment confirmation..."
    PaymentAttemptStatus.AUTHORIZED -> "Payment authorized"
    PaymentAttemptStatus.CAPTURED -> "Payment confirmed"
    PaymentAttemptStatus.FAILED -> "Payment wasn't completed"
    PaymentAttemptStatus.EXPIRED -> "Payment window expired"
    PaymentAttemptStatus.CANCELLED -> "Payment cancelled"
  }

  fun formatFulfillmentAndPayment(
    fulfillmentType: FulfillmentType,
    paymentMethod: PaymentMethod,
    paymentStatus: PaymentStatus? = null,
  ): String {
    val fulfillment = formatFulfillmentType(fulfillmentType)
    val method = formatPaymentMethod(paymentMethod)
    return if (paymentStatus != null) {
      "$fulfillment · $method (${formatPaymentStatus(paymentStatus, fulfillmentType)})"
    } else {
      "$fulfillment · $method"
    }
  }

  fun formatNotificationCategory(category: String?): String = when (category?.trim()?.uppercase(Locale.ENGLISH)) {
    NotificationCategory.ORDER_UPDATE -> "Order"
    NotificationCategory.PAYMENT_ALERT -> "Payment"
    NotificationCategory.REFUND_ALERT -> "Refund"
    NotificationCategory.PROMO_ALERT -> "Offer"
    NotificationCategory.SYSTEM_ALERT -> "Notice"
    else -> "Notice"
  }

  /**
   * Parses ISO-8601 UTC or offset timestamps (including fractional nanoseconds/microseconds)
   * into epoch milliseconds without using java.time (safe on Android API 24+).
   * Returns null for null, blank, or malformed strings.
   */
  fun parseIsoToEpochMillis(isoTimestamp: String?): Long? {
    val raw = isoTimestamp?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val match = isoRegex.matchEntire(raw) ?: return null
    val datePart = match.groupValues[1]
    val timePart = match.groupValues[2]
    val fractionRaw = match.groupValues[3]
    val tzRaw = match.groupValues[4]

    val millisPart = when {
      fractionRaw.isEmpty() -> "000"
      fractionRaw.length >= 3 -> fractionRaw.substring(0, 3)
      else -> fractionRaw.padEnd(3, '0')
    }
    val normalizedTz = when {
      tzRaw.isEmpty() || tzRaw == "Z" -> "+0000"
      else -> {
        val digits = tzRaw.replace(":", "")
        val offsetHours = digits.substring(1, 3).toIntOrNull()
        val offsetMinutes = digits.substring(3, 5).toIntOrNull()
        // Reject impossible offsets (e.g. +99:99) before SimpleDateFormat can normalize them.
        if (digits.length != 5 || (digits[0] != '+' && digits[0] != '-') ||
          offsetHours == null || offsetMinutes == null ||
          offsetHours > 23 || offsetMinutes > 59
        ) {
          return null
        }
        digits
      }
    }

    val normalized = "${datePart}T${timePart}.${millisPart}${normalizedTz}"
    return runCatching {
      val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ENGLISH).apply {
        isLenient = false
      }
      parser.parse(normalized)?.time
    }.getOrNull()
  }

  /**
   * Formats an ISO-8601 timestamp into IST ("Today, 7:42 PM", "Yesterday, 7:42 PM",
   * or "19 Nov, 7:42 PM"). Returns fallback on null/malformed input.
   */
  fun formatIstTimestamp(
    isoTimestamp: String?,
    nowMillis: Long = System.currentTimeMillis(),
    fallback: String = "",
  ): String {
    val epochMillis = parseIsoToEpochMillis(isoTimestamp) ?: return fallback
    val date = Date(epochMillis)
    val timeStr = createIstFormat("h:mm a").format(date)

    val dayDiff = istCalendarDayDiff(targetMillis = epochMillis, nowMillis = nowMillis)
    return when (dayDiff) {
      0 -> "Today, $timeStr"
      -1 -> "Yesterday, $timeStr"
      else -> "${createIstFormat("d MMM").format(date)}, $timeStr"
    }
  }

  /**
   * Formats an ISO-8601 timestamp for notification inbox rows ("Just now", "5m ago",
   * "2h ago", "Today, 6:15 PM", or "19 Nov, 6:15 PM") in IST.
   */
  fun formatIstRelativeOrDate(
    isoTimestamp: String?,
    nowMillis: Long = System.currentTimeMillis(),
    fallback: String = "",
  ): String {
    val epochMillis = parseIsoToEpochMillis(isoTimestamp) ?: return fallback
    val diffMs = nowMillis - epochMillis
    if (diffMs in 0L until 60_000L) {
      return "Just now"
    }
    if (diffMs in 60_000L until 3_600_000L) {
      val mins = diffMs / 60_000L
      return "${mins}m ago"
    }
    if (diffMs in 3_600_000L until 21_600_000L) {
      val hours = diffMs / 3_600_000L
      return "${hours}h ago"
    }
    return formatIstTimestamp(isoTimestamp = isoTimestamp, nowMillis = nowMillis, fallback = fallback)
  }

  /**
   * Formats an ISO-8601 timestamp into IST time-only (e.g., "7:42 PM") for stepper steps.
   */
  fun formatIstTimeOnly(
    isoTimestamp: String?,
    fallback: String = "",
  ): String {
    val epochMillis = parseIsoToEpochMillis(isoTimestamp) ?: return fallback
    return createIstFormat("h:mm a").format(Date(epochMillis))
  }

  /**
   * Returns an ISO calendar date string ("yyyy-MM-dd") in Asia/Kolkata offset by [dayOffset]
   * days from [nowMillis], safe on Android API 24+ (replaces java.time.LocalDate.now).
   */
  fun istDateIso(
    dayOffset: Int = 0,
    nowMillis: Long = System.currentTimeMillis(),
  ): String {
    val cal = Calendar.getInstance(istTimeZone, Locale.ENGLISH).apply {
      timeInMillis = nowMillis
      if (dayOffset != 0) {
        add(Calendar.DAY_OF_YEAR, dayOffset)
      }
    }
    return createIstFormat("yyyy-MM-dd").format(cal.time)
  }

  private fun createIstFormat(pattern: String): SimpleDateFormat =
    SimpleDateFormat(pattern, Locale.ENGLISH).apply {
      timeZone = istTimeZone
    }

  private fun istCalendarDayDiff(targetMillis: Long, nowMillis: Long): Int {
    val targetDayStart = istStartOfDayMillis(targetMillis)
    val nowDayStart = istStartOfDayMillis(nowMillis)
    val dayMillis = 86_400_000L
    return ((targetDayStart - nowDayStart) / dayMillis).toInt()
  }

  private fun istStartOfDayMillis(epochMillis: Long): Long {
    val cal = Calendar.getInstance(istTimeZone, Locale.ENGLISH).apply {
      timeInMillis = epochMillis
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
  }
}

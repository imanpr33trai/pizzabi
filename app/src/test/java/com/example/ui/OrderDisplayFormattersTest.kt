package com.example.ui

import com.example.ui.components.OrderDisplayFormatters
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.NotificationCategory
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderDisplayFormattersTest {

  @Test
  fun `formats every OrderStatus with human-friendly copy`() {
    assertEquals("Payment Pending", OrderDisplayFormatters.formatOrderStatus(OrderStatus.PENDING_PAYMENT))
    assertEquals("Order Placed", OrderDisplayFormatters.formatOrderStatus(OrderStatus.PLACED))
    assertEquals("Confirmed by Kitchen", OrderDisplayFormatters.formatOrderStatus(OrderStatus.ACCEPTED))
    assertEquals("In the Oven", OrderDisplayFormatters.formatOrderStatus(OrderStatus.PREPARING))
    assertEquals("Fresh & Ready", OrderDisplayFormatters.formatOrderStatus(OrderStatus.READY))
    assertEquals(
      "Ready for Delivery",
      OrderDisplayFormatters.formatOrderStatus(OrderStatus.READY, FulfillmentType.DELIVERY),
    )
    assertEquals(
      "Ready for Pickup",
      OrderDisplayFormatters.formatOrderStatus(OrderStatus.READY, FulfillmentType.PICKUP),
    )
    assertEquals("Out for Delivery", OrderDisplayFormatters.formatOrderStatus(OrderStatus.OUT_FOR_DELIVERY))
    assertEquals("Completed", OrderDisplayFormatters.formatOrderStatus(OrderStatus.COMPLETED))
    assertEquals(
      "Delivered",
      OrderDisplayFormatters.formatOrderStatus(OrderStatus.COMPLETED, FulfillmentType.DELIVERY),
    )
    assertEquals(
      "Picked Up",
      OrderDisplayFormatters.formatOrderStatus(OrderStatus.COMPLETED, FulfillmentType.PICKUP),
    )
    assertEquals("Cancelled", OrderDisplayFormatters.formatOrderStatus(OrderStatus.CANCELLED))
    assertEquals("Expired", OrderDisplayFormatters.formatOrderStatus(OrderStatus.EXPIRED))

    // Ensure every enum entry is covered and never leaks raw underscore tokens
    OrderStatus.entries.forEach { status ->
      val label = OrderDisplayFormatters.formatOrderStatus(status)
      assertTrue("Status $status should not contain underscores: $label", !label.contains('_'))
    }
  }

  @Test
  fun `formats payment methods, statuses, attempts, and combined summaries`() {
    assertEquals("Razorpay", OrderDisplayFormatters.formatPaymentMethod(PaymentMethod.RAZORPAY))
    assertEquals("Cash on Delivery", OrderDisplayFormatters.formatPaymentMethod(PaymentMethod.COD))

    assertEquals("Unpaid", OrderDisplayFormatters.formatPaymentStatus(PaymentStatus.UNPAID))
    assertEquals("Pay on Delivery", OrderDisplayFormatters.formatPaymentStatus(PaymentStatus.COD_PENDING))
    assertEquals(
      "Pay at Pickup",
      OrderDisplayFormatters.formatPaymentStatus(PaymentStatus.COD_PENDING, FulfillmentType.PICKUP),
    )
    assertEquals("Paid in Cash", OrderDisplayFormatters.formatPaymentStatus(PaymentStatus.COD_COLLECTED))
    assertEquals("Paid", OrderDisplayFormatters.formatPaymentStatus(PaymentStatus.CAPTURED))
    assertEquals("Expired", OrderDisplayFormatters.formatPaymentStatus(PaymentStatus.EXPIRED))
    assertEquals("Cancelled", OrderDisplayFormatters.formatPaymentStatus(PaymentStatus.CANCELLED))
    assertEquals("Refund Pending", OrderDisplayFormatters.formatPaymentStatus(PaymentStatus.REFUND_PENDING))
    assertEquals("Partially Refunded", OrderDisplayFormatters.formatPaymentStatus(PaymentStatus.PARTIALLY_REFUNDED))
    assertEquals("Refunded", OrderDisplayFormatters.formatPaymentStatus(PaymentStatus.REFUNDED))

    PaymentAttemptStatus.entries.forEach { attemptStatus ->
      val label = OrderDisplayFormatters.formatPaymentAttemptStatus(attemptStatus)
      assertTrue("Attempt $attemptStatus should not contain underscores: $label", !label.contains('_'))
    }

    assertEquals(
      "Delivery · Razorpay (Paid)",
      OrderDisplayFormatters.formatFulfillmentAndPayment(
        FulfillmentType.DELIVERY,
        PaymentMethod.RAZORPAY,
        PaymentStatus.CAPTURED,
      ),
    )
    assertEquals(
      "Pickup · Cash on Delivery",
      OrderDisplayFormatters.formatFulfillmentAndPayment(
        FulfillmentType.PICKUP,
        PaymentMethod.COD,
      ),
    )
  }

  @Test
  fun `formats every NotificationCategory cleanly`() {
    assertEquals("Order", OrderDisplayFormatters.formatNotificationCategory(NotificationCategory.ORDER_UPDATE))
    assertEquals("Payment", OrderDisplayFormatters.formatNotificationCategory(NotificationCategory.PAYMENT_ALERT))
    assertEquals("Refund", OrderDisplayFormatters.formatNotificationCategory(NotificationCategory.REFUND_ALERT))
    assertEquals("Offer", OrderDisplayFormatters.formatNotificationCategory(NotificationCategory.PROMO_ALERT))
    assertEquals("Notice", OrderDisplayFormatters.formatNotificationCategory(NotificationCategory.SYSTEM_ALERT))
    assertEquals("Notice", OrderDisplayFormatters.formatNotificationCategory(null))
    assertEquals("Notice", OrderDisplayFormatters.formatNotificationCategory("UNKNOWN_EVENT"))
  }

  @Test
  fun `parses ISO UTC and offset timestamps safely and converts to IST`() {
    // 2026-11-19T14:12:00Z == 2026-11-19 7:42 PM IST (+05:30)
    val baseUtc = "2026-11-19T14:12:00Z"
    val microsUtc = "2026-11-19T14:12:00.123456Z"
    val istOffset = "2026-11-19T19:42:00+05:30"

    val baseMillis = OrderDisplayFormatters.parseIsoToEpochMillis(baseUtc)
    val microsMillis = OrderDisplayFormatters.parseIsoToEpochMillis(microsUtc)
    val istOffsetMillis = OrderDisplayFormatters.parseIsoToEpochMillis(istOffset)

    assertNotNull(baseMillis)
    assertNotNull(microsMillis)
    assertEquals(baseMillis, istOffsetMillis)
    // Fractional microseconds truncate cleanly to milliseconds (123ms), NOT 123,456ms!
    assertEquals(baseMillis!! + 123L, microsMillis)

    assertEquals("7:42 PM", OrderDisplayFormatters.formatIstTimeOnly(baseUtc))
    assertEquals(
      "Today, 7:42 PM",
      OrderDisplayFormatters.formatIstTimestamp(baseUtc, nowMillis = baseMillis + 60_000L),
    )
    assertEquals(
      "Yesterday, 7:42 PM",
      OrderDisplayFormatters.formatIstTimestamp(baseUtc, nowMillis = baseMillis + 86_400_000L),
    )
    assertEquals(
      "19 Nov, 7:42 PM",
      OrderDisplayFormatters.formatIstTimestamp(baseUtc, nowMillis = baseMillis + 3 * 86_400_000L),
    )

    // IST midnight rollover: 2026-11-19T19:00:00Z is 2026-11-20 12:30 AM IST!
    val rolloverUtc = "2026-11-19T19:00:00Z"
    val rolloverMillis = OrderDisplayFormatters.parseIsoToEpochMillis(rolloverUtc)!!
    assertEquals("2026-11-20", OrderDisplayFormatters.istDateIso(dayOffset = 0, nowMillis = rolloverMillis))
    assertEquals("2026-11-21", OrderDisplayFormatters.istDateIso(dayOffset = 1, nowMillis = rolloverMillis))
    assertEquals("12:30 AM", OrderDisplayFormatters.formatIstTimeOnly(rolloverUtc))
  }

  @Test
  fun `formats relative notification timestamps and handles malformed inputs safely`() {
    val baseUtc = "2026-11-19T14:12:00Z"
    val baseMillis = OrderDisplayFormatters.parseIsoToEpochMillis(baseUtc)!!

    assertEquals("Just now", OrderDisplayFormatters.formatIstRelativeOrDate(baseUtc, nowMillis = baseMillis + 25_000L))
    assertEquals("5m ago", OrderDisplayFormatters.formatIstRelativeOrDate(baseUtc, nowMillis = baseMillis + 5 * 60_000L))
    assertEquals("2h ago", OrderDisplayFormatters.formatIstRelativeOrDate(baseUtc, nowMillis = baseMillis + 2 * 3_600_000L))
    assertEquals(
      "19 Nov, 7:42 PM",
      OrderDisplayFormatters.formatIstRelativeOrDate(baseUtc, nowMillis = baseMillis + 3 * 86_400_000L),
    )

    // Null, blank, and malformed timestamps never throw
    assertNull(OrderDisplayFormatters.parseIsoToEpochMillis(null))
    assertNull(OrderDisplayFormatters.parseIsoToEpochMillis(""))
    assertNull(OrderDisplayFormatters.parseIsoToEpochMillis("not-a-timestamp"))
    assertNull(OrderDisplayFormatters.parseIsoToEpochMillis("2026-13-45T99:99:99Z"))
    assertEquals("Unknown", OrderDisplayFormatters.formatIstTimestamp(null, fallback = "Unknown"))
    assertEquals("Unknown", OrderDisplayFormatters.formatIstTimestamp("bad-date", fallback = "Unknown"))
    assertEquals("", OrderDisplayFormatters.formatIstTimeOnly("bad-date"))
    assertEquals("Just now", OrderDisplayFormatters.formatIstRelativeOrDate("bad-date", fallback = "Just now"))
  }
}

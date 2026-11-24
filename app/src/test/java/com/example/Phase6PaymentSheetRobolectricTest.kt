package com.example

import android.app.Activity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.example.orders.PaymentCountdownTone
import com.example.orders.PaymentExpiryFormatter
import com.example.orders.PaymentExpiryMilestone
import com.example.orders.RazorpayCheckoutLauncher
import com.example.orders.RazorpayCheckoutRequest
import com.example.orders.OrderRefundsCard
import com.example.orders.RazorpayPaymentActionCard
import com.example.orders.RazorpayPaymentSheetDialog
import com.example.ui.components.OrderDisplayFormatters
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderPaymentStatusResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentAttemptSummaryDto
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PaymentSummaryDto
import com.pizzora.contracts.RefundDto
import com.pizzora.contracts.RefundStatus
import com.pizzora.contracts.ScheduleType
import java.util.concurrent.atomic.AtomicLong
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
class Phase6PaymentSheetRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private class RecordingLauncher : RazorpayCheckoutLauncher {
    val requests = mutableListOf<RazorpayCheckoutRequest>()
    override fun launch(activity: Activity, request: RazorpayCheckoutRequest) {
      requests += request
    }
  }

  private fun makeOrder(
    totalPaise: Long = 50_000L,
    attemptAmountPaise: Long? = 75_000L,
    attemptStatus: PaymentAttemptStatus = PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT,
    gatewayOrderId: String? = "order_gw_p6",
    expiryIso: String? = null,
  ): OrderDetailDto = OrderDetailDto(
    id = "ord-p6-1",
    orderNumber = "PZ-P6-1",
    customerId = "cust-p6",
    fulfillmentType = FulfillmentType.PICKUP,
    scheduleType = ScheduleType.ASAP,
    status = OrderStatus.PENDING_PAYMENT,
    version = 1L,
    subtotalPaise = totalPaise,
    discountPaise = 0L,
    deliveryFeePaise = 0L,
    taxPaise = 0L,
    cgstPaise = 0L,
    sgstPaise = 0L,
    totalPaise = totalPaise,
    gstBpsSnapshot = 0,
    isPriority = false,
    expiresAt = expiryIso,
    createdAt = "2099-01-01T00:00:00Z",
    items = emptyList(),
    statusHistory = emptyList(),
    payment = PaymentSummaryDto(
      id = "pay-p6",
      method = PaymentMethod.RAZORPAY,
      status = PaymentStatus.UNPAID,
      amountPaise = totalPaise,
      capturedPaise = 0L,
      refundedPaise = 0L,
      latestAttempt = attemptAmountPaise?.let {
        PaymentAttemptSummaryDto(
          id = "pa-p6-1",
          attemptNumber = 1,
          method = PaymentMethod.RAZORPAY,
          status = attemptStatus,
          amountPaise = it,
          gatewayOrderId = gatewayOrderId,
          idempotencyReceipt = "rcpt_p6_1",
          failureReason = if (attemptStatus == PaymentAttemptStatus.FAILED) {
            "RAW_GATEWAY_DECLINE_CODE"
          } else {
            null
          },
          createdAt = "2099-01-01T00:00:00Z",
        )
      },
    ),
  )

  private fun makeStatus(order: OrderDetailDto): OrderPaymentStatusResponse =
    OrderPaymentStatusResponse(
      orderId = order.id,
      orderNumber = order.orderNumber,
      orderStatus = order.status,
      payment = order.payment,
      razorpayKeyId = "rzp_test_p6",
      canRetry = true,
      expiresAt = order.expiresAt,
    )

  private fun setSheet(
    order: OrderDetailDto,
    launcher: RecordingLauncher = RecordingLauncher(),
    nowProvider: () -> Long = { System.currentTimeMillis() },
    onExpiryReached: () -> Unit = {},
  ): RecordingLauncher {
    composeTestRule.setContent {
      PizzoraTheme(darkTheme = false, reducedMotion = true) {
        RazorpayPaymentSheetDialog(
          order = order,
          paymentStatus = makeStatus(order),
          isPollingGatewayOrder = false,
          isVerifyingPayment = false,
          isReportingFailure = false,
          paymentError = null,
          customerName = "Asha",
          customerEmail = "asha@example.com",
          customerPhone = "9876543210",
          razorpayCheckoutLauncher = launcher,
          onDismiss = {},
          onExpiryReached = onExpiryReached,
          nowProvider = nowProvider,
        )
      }
    }
    return launcher
  }

  @Test
  fun `sheet binds authoritative attempt amount to display and Razorpay request`() {
    // order.totalPaise is deliberately stale (₹500.00); the attempt is ₹750.00.
    val order = makeOrder(totalPaise = 50_000L)
    val launcher = setSheet(order)

    composeTestRule.onNodeWithTag("razorpay_payment_sheet").assertIsDisplayed()
    composeTestRule.onNodeWithTag("launch_razorpay_checkout_button")
      .assertTextContains("Pay ₹750.00 Securely", substring = true)
    assertTrue(
      composeTestRule.onAllNodesWithText("₹500.00", substring = true).fetchSemanticsNodes().isEmpty(),
    )
    assertTrue(
      composeTestRule.onAllNodesWithText("%1\$s", substring = true).fetchSemanticsNodes().isEmpty(),
    )

    composeTestRule.onNodeWithTag("launch_razorpay_checkout_button").performScrollTo().performClick()
    assertEquals(1, launcher.requests.size)
    assertEquals(75_000L, launcher.requests[0].amountPaise)
    assertEquals("INR", launcher.requests[0].currency)
    assertEquals("order_gw_p6", launcher.requests[0].gatewayOrderId)
    assertEquals("rzp_test_p6", launcher.requests[0].razorpayKeyId)
    assertFalse(launcher.requests[0].orderNumber.isBlank())
  }

  @Test
  fun `sheet falls back to order total when there is no latest attempt`() {
    val order = makeOrder(totalPaise = 50_000L, attemptAmountPaise = null, gatewayOrderId = null)
    setSheet(order)

    composeTestRule.onNodeWithTag("launch_razorpay_checkout_button")
      .assertTextContains("Pay ₹500.00 Securely", substring = true)
    assertTrue(
      composeTestRule.onAllNodesWithText("₹750.00", substring = true).fetchSemanticsNodes().isEmpty(),
    )
    assertTrue(
      composeTestRule.onAllNodesWithTag("payment_reference_toggle").fetchSemanticsNodes().isEmpty(),
    )
  }

  @Test
  fun `action card shows authoritative attempt amount instead of stale order total`() {
    val order = makeOrder(totalPaise = 50_000L)
    composeTestRule.setContent {
      PizzoraTheme(darkTheme = false, reducedMotion = true) {
        RazorpayPaymentActionCard(
          order = order,
          paymentStatus = makeStatus(order),
          isPollingGatewayOrder = false,
          isRetryingPayment = false,
          onOpenPaymentSheet = {},
          onRetryPayment = {},
        )
      }
    }
    assertTrue(
      composeTestRule.onAllNodesWithText("₹750.00", substring = true).fetchSemanticsNodes().isNotEmpty(),
    )
    assertTrue(
      composeTestRule.onAllNodesWithText("₹500.00", substring = true).fetchSemanticsNodes().isEmpty(),
    )
    assertTrue(
      composeTestRule.onAllNodesWithText("order_gw_p6", substring = true).fetchSemanticsNodes().isEmpty(),
    )
    assertTrue(
      composeTestRule.onAllNodesWithText("AWAITING_CUSTOMER_PAYMENT", substring = true)
        .fetchSemanticsNodes().isEmpty(),
    )
  }

  @Test
  fun `payment reference is collapsed by default and reveals gateway details when expanded`() {
    val order = makeOrder()
    setSheet(order)

    composeTestRule.onNodeWithTag("payment_reference_toggle").assertExists()
    assertTrue(
      composeTestRule.onAllNodesWithTag("razorpay_gateway_order_id_text").fetchSemanticsNodes().isEmpty(),
    )
    assertTrue(
      composeTestRule.onAllNodesWithTag("razorpay_key_id_text").fetchSemanticsNodes().isEmpty(),
    )
    assertTrue(
      composeTestRule.onAllNodesWithText("rzp_test_p6", substring = true).fetchSemanticsNodes().isEmpty(),
    )
    assertTrue(
      composeTestRule.onAllNodesWithText("order_gw_p6", substring = true).fetchSemanticsNodes().isEmpty(),
    )

    composeTestRule.onNodeWithTag("payment_reference_toggle").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("razorpay_gateway_order_id_text")
      .assertTextContains("order_gw_p6", substring = true)
    composeTestRule.onNodeWithTag("razorpay_key_id_text")
      .assertTextContains("rzp_test_p6", substring = true)
  }

  @Test
  fun `retry is disabled while initiating and while polling gateway order`() {
    val initiating = makeOrder(attemptStatus = PaymentAttemptStatus.INITIATING)
    val failed = makeOrder(attemptStatus = PaymentAttemptStatus.FAILED)
    val cardOrder = mutableStateOf(initiating)
    val isPolling = mutableStateOf(false)
    val isRetrying = mutableStateOf(false)
    composeTestRule.setContent {
      PizzoraTheme(darkTheme = false, reducedMotion = true) {
        RazorpayPaymentActionCard(
          order = cardOrder.value,
          paymentStatus = makeStatus(cardOrder.value),
          isPollingGatewayOrder = isPolling.value,
          isRetryingPayment = isRetrying.value,
          onOpenPaymentSheet = {},
          onRetryPayment = {},
        )
      }
    }
    composeTestRule.onNodeWithTag("retry_payment_button").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("razorpay_polling_indicator").assertIsDisplayed()
    assertTrue(
      composeTestRule.onAllNodesWithText("Preparing secure checkout...", substring = true)
        .fetchSemanticsNodes().isNotEmpty(),
    )
    assertTrue(
      composeTestRule.onAllNodesWithText("INITIATING", substring = true).fetchSemanticsNodes().isEmpty(),
    )

    composeTestRule.runOnIdle {
      cardOrder.value = failed
      isPolling.value = true
    }
    composeTestRule.onNodeWithTag("retry_payment_button").assertIsNotEnabled()
    composeTestRule.runOnIdle {
      isPolling.value = false
      isRetrying.value = true
    }
    composeTestRule.onNodeWithTag("retry_payment_button").assertIsNotEnabled()
    composeTestRule.runOnIdle {
      isRetrying.value = false
    }
    composeTestRule.onNodeWithTag("retry_payment_button").assertIsEnabled()
    composeTestRule.onNodeWithTag("payment_attempt_failure_reason")
      .assertTextContains("you won't be charged twice", substring = true)
    assertTrue(
      composeTestRule.onAllNodesWithText("RAW_GATEWAY_DECLINE_CODE", substring = true)
        .fetchSemanticsNodes().isEmpty(),
    )
  }

  @Test
  fun `expiry formatter covers MM SS formatting warning escalation and milestone thresholds`() {
    assertEquals("14:32", PaymentExpiryFormatter.formatRemaining(872_000L))
    assertEquals("03:00", PaymentExpiryFormatter.formatRemaining(180_000L))
    assertEquals("00:01", PaymentExpiryFormatter.formatRemaining(1_000L))
    assertEquals("00:00", PaymentExpiryFormatter.formatRemaining(0L))
    assertEquals("00:00", PaymentExpiryFormatter.formatRemaining(-5_000L))

    assertEquals(PaymentCountdownTone.NEUTRAL, PaymentExpiryFormatter.toneFor(180_001L))
    assertEquals(PaymentCountdownTone.WARNING, PaymentExpiryFormatter.toneFor(180_000L))
    assertEquals(PaymentCountdownTone.WARNING, PaymentExpiryFormatter.toneFor(1L))
    assertEquals(PaymentCountdownTone.EXPIRED, PaymentExpiryFormatter.toneFor(0L))

    assertNull(PaymentExpiryFormatter.milestoneFor(300_001L))
    assertEquals(PaymentExpiryMilestone.FIVE_MINUTES, PaymentExpiryFormatter.milestoneFor(300_000L))
    assertEquals(PaymentExpiryMilestone.TWO_MINUTES, PaymentExpiryFormatter.milestoneFor(120_000L))
    assertEquals(PaymentExpiryMilestone.ONE_MINUTE, PaymentExpiryFormatter.milestoneFor(60_000L))
    assertEquals(PaymentExpiryMilestone.EXPIRED, PaymentExpiryFormatter.milestoneFor(0L))

    assertNull(PaymentExpiryFormatter.crossedMilestone(400_000L, 301_000L))
    assertEquals(
      PaymentExpiryMilestone.FIVE_MINUTES,
      PaymentExpiryFormatter.crossedMilestone(301_000L, 299_000L),
    )
    assertEquals(
      PaymentExpiryMilestone.TWO_MINUTES,
      PaymentExpiryFormatter.crossedMilestone(121_000L, 119_000L),
    )
    assertEquals(
      PaymentExpiryMilestone.ONE_MINUTE,
      PaymentExpiryFormatter.crossedMilestone(61_000L, 59_000L),
    )
    assertEquals(PaymentExpiryMilestone.EXPIRED, PaymentExpiryFormatter.crossedMilestone(1_000L, 0L))
    assertEquals("5 minutes remaining", PaymentExpiryMilestone.FIVE_MINUTES.announcement)
    assertEquals("Payment window expired", PaymentExpiryMilestone.EXPIRED.announcement)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w320dp-h640dp")
  fun warningCountdownRemainsVisibleAt320dpWith15xFontScale() {
    val expiryIso = "2099-01-01T00:15:00Z"
    val expiryEpoch = OrderDisplayFormatters.parseIsoToEpochMillis(expiryIso)!!
    val clock = AtomicLong(expiryEpoch - 125_000L)
    val order = makeOrder(expiryIso = expiryIso)

    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.setContent {
      val baseDensity = LocalDensity.current
      CompositionLocalProvider(
        LocalDensity provides Density(baseDensity.density, fontScale = 1.5f),
      ) {
        PizzoraTheme(darkTheme = false, reducedMotion = true) {
          RazorpayPaymentSheetDialog(
            order = order,
            paymentStatus = makeStatus(order),
            isPollingGatewayOrder = false,
            isVerifyingPayment = false,
            isReportingFailure = false,
            paymentError = null,
            razorpayCheckoutLauncher = RecordingLauncher(),
            onDismiss = {},
            nowProvider = { clock.get() },
          )
        }
      }
    }
    composeTestRule.mainClock.advanceTimeBy(100L)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("Expires in — complete payment now", substring = true).assertExists()
    val barBounds = composeTestRule.onNodeWithTag("payment_expiry_countdown_bar")
      .assertExists().fetchSemanticsNode().boundsInRoot
    val timerNode = composeTestRule.onNodeWithTag("payment_expiry_countdown_text").assertExists()
    val timerBounds = timerNode.fetchSemanticsNode().boundsInRoot
    assertTrue(
      "MM:SS countdown must stay inside the warning bar at 320dp / 1.5x",
      timerBounds.width > 0f && timerBounds.left >= barBounds.left - 1f && timerBounds.right <= barBounds.right + 1f,
    )
    timerNode.assertTextContains("02:05", substring = true)
    composeTestRule.mainClock.autoAdvance = true
  }

  @Test
  fun `only coarse milestones are announced politely and the ticker is not a live region`() {
    val expiryIso = "2099-01-01T00:15:00Z"
    val expiryEpoch = OrderDisplayFormatters.parseIsoToEpochMillis(expiryIso)!!
    val clock = AtomicLong(expiryEpoch - 400_000L)
    val order = makeOrder(expiryIso = expiryIso)

    composeTestRule.mainClock.autoAdvance = false
    setSheet(order, nowProvider = { clock.get() })
    composeTestRule.mainClock.advanceTimeBy(100L)
    composeTestRule.waitForIdle()

    assertTrue(
      composeTestRule.onAllNodesWithTag("payment_expiry_announcement").fetchSemanticsNodes().isEmpty(),
    )
    assertNull(
      composeTestRule.onNodeWithTag("payment_expiry_countdown_text")
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion),
    )

    clock.set(expiryEpoch - 299_000L)
    composeTestRule.mainClock.advanceTimeBy(1_000L)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("payment_expiry_announcement")
      .assertTextContains("5 minutes remaining", substring = true)
    assertEquals(
      LiveRegionMode.Polite,
      composeTestRule.onNodeWithTag("payment_expiry_announcement")
        .fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
    )
    composeTestRule.mainClock.autoAdvance = true
  }

  @Test
  fun `expiry disables pay CTA shows expired banner and fires callback exactly once`() {
    val expiryIso = "2099-01-01T00:15:00Z"
    val expiryEpoch = OrderDisplayFormatters.parseIsoToEpochMillis(expiryIso)!!
    val clock = AtomicLong(expiryEpoch - 2_000L)
    val order = makeOrder(expiryIso = expiryIso)
    var expiryCallbackCount = 0

    composeTestRule.mainClock.autoAdvance = false
    setSheet(order, nowProvider = { clock.get() }, onExpiryReached = { expiryCallbackCount++ })
    composeTestRule.mainClock.advanceTimeBy(100L)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("payment_expiry_countdown_text").assertTextContains("00:02")
    composeTestRule.onNodeWithTag("launch_razorpay_checkout_button").assertIsEnabled()

    clock.set(expiryEpoch + 1_000L)
    composeTestRule.mainClock.advanceTimeBy(1_000L)
    composeTestRule.waitForIdle()

    assertEquals(1, expiryCallbackCount)
    composeTestRule.onNodeWithTag("payment_expiry_countdown_text").assertTextContains("00:00")
    composeTestRule.onNodeWithTag("launch_razorpay_checkout_button").assertIsNotEnabled()
    assertTrue(
      composeTestRule.onAllNodesWithText("Payment window expired", substring = true)
        .fetchSemanticsNodes().isNotEmpty(),
    )

    composeTestRule.mainClock.advanceTimeBy(5_000L)
    composeTestRule.waitForIdle()
    assertEquals(1, expiryCallbackCount)
    composeTestRule.mainClock.autoAdvance = true
  }

  @Test
  fun `refund rows use human readable status without raw gateway ids`() {
    val refunds = listOf(
      RefundDto(
        id = "ref-p6-1",
        paymentId = "pay-p6",
        orderId = "ord-p6-1",
        idempotencyKey = "idem-ref-p6-1",
        amountPaise = 10_000L,
        status = RefundStatus.PROCESSED,
        gatewayRefundId = "rfnd_raw_gateway_id",
        reason = "Customer cancelled",
        createdAt = "2099-01-01T00:00:00Z",
        updatedAt = "2099-01-01T00:01:00Z",
      ),
    )
    composeTestRule.setContent {
      PizzoraTheme(darkTheme = false, reducedMotion = true) {
        OrderRefundsCard(refunds = refunds)
      }
    }

    composeTestRule.onNodeWithTag("order_refunds_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("order_refund_row_ref-p6-1").assertIsDisplayed()
    composeTestRule.onNodeWithText("Refunded", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("₹100.00", substring = true).assertIsDisplayed()
    assertTrue(
      composeTestRule.onAllNodesWithText("PROCESSED", substring = true).fetchSemanticsNodes().isEmpty(),
    )
    assertTrue(
      composeTestRule.onAllNodesWithText("rfnd_raw_gateway_id", substring = true)
        .fetchSemanticsNodes().isEmpty(),
    )
  }
}

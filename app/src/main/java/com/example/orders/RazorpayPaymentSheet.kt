package com.example.orders

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.OrderDisplayFormatters
import com.example.ui.theme.LocalPizzoraSemanticColors
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.pizzora.contracts.Money
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderPaymentStatusResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.RefundDto
import com.pizzora.contracts.RefundStatus
import java.util.Locale
import kotlinx.coroutines.delay

/** Coarse, TalkBack-friendly expiry milestones. The 1-second ticker itself is never a live region. */
enum class PaymentExpiryMilestone(val announcement: String) {
  FIVE_MINUTES("5 minutes remaining"),
  TWO_MINUTES("2 minutes remaining"),
  ONE_MINUTE("1 minute remaining"),
  EXPIRED("Payment window expired"),
}

enum class PaymentCountdownTone { NEUTRAL, WARNING, EXPIRED }

/**
 * Pure helpers for the authoritative payment-expiry countdown. Kept free of Compose/Android
 * dependencies so the formatting, escalation, and milestone thresholds can be unit tested.
 */
object PaymentExpiryFormatter {
  const val WARNING_THRESHOLD_MS: Long = 3L * 60L * 1000L
  private const val ONE_MINUTE_MS: Long = 60L * 1000L
  private const val TWO_MINUTES_MS: Long = 2L * 60L * 1000L
  private const val FIVE_MINUTES_MS: Long = 5L * 60L * 1000L

  /** Tabular `MM:SS` countdown, clamped at `00:00`. */
  fun formatRemaining(remainingMillis: Long): String {
    val clamped = remainingMillis.coerceAtLeast(0L)
    val totalSeconds = clamped / 1000L
    return "%02d:%02d".format(Locale.US, totalSeconds / 60L, totalSeconds % 60L)
  }

  /** Neutral above 3 minutes, amber up to 3 minutes, error at 00:00. */
  fun toneFor(remainingMillis: Long): PaymentCountdownTone = when {
    remainingMillis <= 0L -> PaymentCountdownTone.EXPIRED
    remainingMillis <= WARNING_THRESHOLD_MS -> PaymentCountdownTone.WARNING
    else -> PaymentCountdownTone.NEUTRAL
  }

  /** The milestone currently in effect, or null before the 5-minute mark. */
  fun milestoneFor(remainingMillis: Long): PaymentExpiryMilestone? = when {
    remainingMillis <= 0L -> PaymentExpiryMilestone.EXPIRED
    remainingMillis <= ONE_MINUTE_MS -> PaymentExpiryMilestone.ONE_MINUTE
    remainingMillis <= TWO_MINUTES_MS -> PaymentExpiryMilestone.TWO_MINUTES
    remainingMillis <= FIVE_MINUTES_MS -> PaymentExpiryMilestone.FIVE_MINUTES
    else -> null
  }

  /**
   * Returns the most urgent milestone crossed between two consecutive ticks, or null when no
   * threshold was crossed. EXPIRED is emitted only on the transition to <= 0 so a customer that
   * opens an already-expired sheet does not receive a duplicate announcement.
   */
  fun crossedMilestone(
    previousRemainingMillis: Long,
    currentRemainingMillis: Long,
  ): PaymentExpiryMilestone? {
    if (currentRemainingMillis <= 0L) return PaymentExpiryMilestone.EXPIRED
    return when {
      previousRemainingMillis > ONE_MINUTE_MS && currentRemainingMillis <= ONE_MINUTE_MS ->
        PaymentExpiryMilestone.ONE_MINUTE
      previousRemainingMillis > TWO_MINUTES_MS && currentRemainingMillis <= TWO_MINUTES_MS ->
        PaymentExpiryMilestone.TWO_MINUTES
      previousRemainingMillis > FIVE_MINUTES_MS && currentRemainingMillis <= FIVE_MINUTES_MS ->
        PaymentExpiryMilestone.FIVE_MINUTES
      else -> null
    }
  }
}

@Composable
fun RazorpayPaymentActionCard(
  order: OrderDetailDto,
  paymentStatus: OrderPaymentStatusResponse?,
  isPollingGatewayOrder: Boolean,
  isRetryingPayment: Boolean,
  onOpenPaymentSheet: () -> Unit,
  onRetryPayment: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (order.status != OrderStatus.PENDING_PAYMENT || order.payment.method != PaymentMethod.RAZORPAY) {
    return
  }

  val latestAttempt = paymentStatus?.payment?.latestAttempt ?: order.payment.latestAttempt
  // Authoritative Level 4 attempt amount; order.totalPaise is only a fallback.
  val amountPaise = latestAttempt?.amountPaise ?: order.totalPaise
  val canRetry = paymentStatus?.canRetry ?: true
  val isInitiating = latestAttempt?.status == PaymentAttemptStatus.INITIATING
  val isAwaitingCustomer = latestAttempt?.status == PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT &&
    !latestAttempt.gatewayOrderId.isNullOrBlank()
  val isFailed = latestAttempt?.status == PaymentAttemptStatus.FAILED
  val isPreparing = isPollingGatewayOrder || isInitiating || isRetryingPayment
  val retryEnabled = canRetry && !isInitiating && !isPollingGatewayOrder && !isRetryingPayment

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
    modifier = modifier.fillMaxWidth().testTag("razorpay_payment_action_card"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = if (isFailed) Icons.Filled.ErrorOutline else Icons.Filled.CreditCard,
          contentDescription = null,
          tint = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.razorpay_action_card_title),
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            text = latestAttempt?.status?.let(OrderDisplayFormatters::formatPaymentAttemptStatus)
              ?: stringResource(R.string.phase6_preparing_secure_checkout),
            style = PizzoraMonoBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          text = Money.formatInr(amountPaise),
          style = PizzoraMonoTitle,
          color = MaterialTheme.colorScheme.primary,
        )
      }

      if (isPreparing) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("razorpay_polling_indicator"))
        Text(
          text = stringResource(R.string.phase6_preparing_secure_checkout),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Raw attempt status, gateway IDs and developer keys are intentionally absent from the
      // default customer view. They live in the sheet's "Payment Reference" disclosure instead.
      if (isFailed) {
        Text(
          text = stringResource(R.string.phase6_retry_safe_message),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("payment_attempt_failure_reason"),
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (isAwaitingCustomer) {
          Button(
            onClick = onOpenPaymentSheet,
            modifier = Modifier
              .weight(1f)
              .minimumInteractiveComponentSize()
              .testTag("open_razorpay_sheet_button"),
          ) {
            Icon(Icons.Filled.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text(stringResource(R.string.action_complete_payment))
          }
        }
        if (canRetry && (isFailed || !isAwaitingCustomer)) {
          Button(
            onClick = onRetryPayment,
            enabled = retryEnabled,
            modifier = Modifier
              .weight(1f)
              .minimumInteractiveComponentSize()
              .testTag("retry_payment_button"),
          ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text(stringResource(R.string.action_retry_payment))
          }
        }
      }
    }
  }
}

@Composable
private fun formatRefundStatus(status: RefundStatus): String = when (status) {
  RefundStatus.PENDING -> stringResource(R.string.phase6_refund_pending)
  RefundStatus.PROCESSING -> stringResource(R.string.phase6_refund_processing)
  RefundStatus.PROCESSED -> stringResource(R.string.phase6_refund_processed)
  RefundStatus.FAILED -> stringResource(R.string.phase6_refund_failed)
}

@Composable
fun OrderRefundsCard(
  refunds: List<RefundDto>,
  modifier: Modifier = Modifier,
) {
  if (refunds.isEmpty()) return
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    modifier = modifier.fillMaxWidth().testTag("order_refunds_card"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(stringResource(R.string.order_refunds_section), style = MaterialTheme.typography.titleMedium)
      refunds.forEachIndexed { idx, refund ->
        if (idx > 0) HorizontalDivider()
        Row(
          modifier = Modifier.fillMaxWidth().testTag("order_refund_row_${refund.id}"),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "${formatRefundStatus(refund.status)} • ${Money.formatInr(refund.amountPaise)}",
              style = PizzoraMonoBody,
              color = if (refund.status == RefundStatus.FAILED) {
                MaterialTheme.colorScheme.error
              } else {
                MaterialTheme.colorScheme.primary
              },
            )
            Text(
              text = refund.reason,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RazorpayPaymentSheetDialog(
  order: OrderDetailDto,
  paymentStatus: OrderPaymentStatusResponse?,
  isPollingGatewayOrder: Boolean,
  isVerifyingPayment: Boolean,
  isReportingFailure: Boolean,
  paymentError: String?,
  customerName: String? = null,
  customerEmail: String? = null,
  customerPhone: String? = null,
  razorpayCheckoutLauncher: RazorpayCheckoutLauncher = AndroidRazorpayCheckoutLauncher(),
  onLaunchStarted: (orderId: String, gatewayOrderId: String) -> Unit = { _, _ -> },
  onSdkLaunchError: (code: Int, description: String?) -> Unit = { _, _ -> },
  onDismiss: () -> Unit,
  onExpiryReached: () -> Unit = {},
  nowProvider: () -> Long = { System.currentTimeMillis() },
) {
  val context = LocalContext.current
  val semanticColors = LocalPizzoraSemanticColors.current
  val latestAttempt = paymentStatus?.payment?.latestAttempt ?: order.payment.latestAttempt
  // Every customer-facing amount and the SDK request bind to the newest server attempt.
  val amountPaise = latestAttempt?.amountPaise ?: order.totalPaise
  val amountLabel = Money.formatInr(amountPaise)
  val gatewayOrderId = latestAttempt?.gatewayOrderId
  val razorpayKeyId = paymentStatus?.razorpayKeyId?.takeIf { it.isNotBlank() }
  val isFailed = latestAttempt?.status == PaymentAttemptStatus.FAILED

  val expiryEpochMillis = OrderDisplayFormatters.parseIsoToEpochMillis(
    paymentStatus?.expiresAt ?: order.expiresAt,
  )
  val effectiveOrderStatus = paymentStatus?.orderStatus ?: order.status
  val effectivePaymentStatus = paymentStatus?.payment?.status ?: order.payment.status
  val countdownActive = effectiveOrderStatus == OrderStatus.PENDING_PAYMENT &&
    effectivePaymentStatus == PaymentStatus.UNPAID &&
    expiryEpochMillis != null

  var nowMillis by remember { mutableLongStateOf(nowProvider()) }
  var expiryAnnouncement by remember { mutableStateOf<PaymentExpiryMilestone?>(null) }
  var referenceExpanded by rememberSaveable { mutableStateOf(false) }
  val onExpiryReachedNow by rememberUpdatedState(onExpiryReached)
  val nowProviderNow by rememberUpdatedState(nowProvider)

  // A 1-second ticker exists only while an unpaid PENDING_PAYMENT order has a future expiry.
  // It stops at 00:00 and fires the refresh callback once on the transition into expiry.
  LaunchedEffect(expiryEpochMillis, countdownActive) {
    expiryAnnouncement = null
    val expiresAt = expiryEpochMillis ?: return@LaunchedEffect
    if (!countdownActive || expiresAt <= nowProviderNow()) return@LaunchedEffect
    var previousRemaining = expiresAt - nowProviderNow()
    // If the sheet opens inside a warning band, announce the current milestone once.
    PaymentExpiryFormatter.milestoneFor(previousRemaining)?.let { expiryAnnouncement = it }
    var sawActiveWindow = previousRemaining > 0L
    while (true) {
      val current = nowProviderNow()
      nowMillis = current
      val remaining = expiresAt - current
      PaymentExpiryFormatter.crossedMilestone(previousRemaining, remaining)?.let {
        expiryAnnouncement = it
      }
      previousRemaining = remaining
      if (remaining <= 0L) {
        if (sawActiveWindow) onExpiryReachedNow()
        break
      }
      sawActiveWindow = true
      delay(1_000L)
    }
  }

  val remainingMillis = if (expiryEpochMillis != null) {
    (expiryEpochMillis - nowMillis).coerceAtLeast(0L)
  } else {
    null
  }
  val countdownTone = PaymentExpiryFormatter.toneFor(remainingMillis ?: Long.MAX_VALUE)
  val isExpired = countdownTone == PaymentCountdownTone.EXPIRED
  val countdownContainerColor = when (countdownTone) {
    PaymentCountdownTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    PaymentCountdownTone.WARNING -> semanticColors.warningContainer
    PaymentCountdownTone.EXPIRED -> MaterialTheme.colorScheme.errorContainer
  }
  val countdownContentColor = when (countdownTone) {
    PaymentCountdownTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    PaymentCountdownTone.WARNING -> semanticColors.onWarningContainer
    PaymentCountdownTone.EXPIRED -> MaterialTheme.colorScheme.onErrorContainer
  }
  val countdownPrefix = when (countdownTone) {
    PaymentCountdownTone.NEUTRAL -> stringResource(R.string.phase6_complete_payment_within)
    PaymentCountdownTone.WARNING -> stringResource(R.string.phase6_expires_in)
    PaymentCountdownTone.EXPIRED -> stringResource(R.string.phase6_payment_window_expired)
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    tonalElevation = 0.dp,
    modifier = Modifier.testTag("razorpay_payment_sheet"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
        .padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.CreditCard,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = stringResource(R.string.razorpay_sheet_title),
            style = MaterialTheme.typography.titleLarge,
          )
        }
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("close_razorpay_sheet_button"),
        ) {
          Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
        }
      }

      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = stringResource(R.string.phase6_pay_for_order, order.orderNumber),
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = amountLabel,
          style = PizzoraMonoTitle,
          color = MaterialTheme.colorScheme.primary,
        )
      }

      if (countdownActive && remainingMillis != null) {
        Surface(
          color = countdownContainerColor,
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier.fillMaxWidth().testTag("payment_expiry_countdown_bar"),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = countdownPrefix,
                style = MaterialTheme.typography.bodySmall,
                color = countdownContentColor,
              )
              // The ticking text is deliberately NOT a live region (TalkBack protection).
              Text(
                text = PaymentExpiryFormatter.formatRemaining(remainingMillis),
                style = PizzoraMonoBody,
                color = countdownContentColor,
                modifier = Modifier.testTag("payment_expiry_countdown_text"),
              )
            }
            expiryAnnouncement?.let { milestone ->
              Text(
                text = milestone.announcement,
                style = MaterialTheme.typography.labelSmall,
                color = countdownContentColor,
                modifier = Modifier
                  .testTag("payment_expiry_announcement")
                  .semantics { liveRegion = LiveRegionMode.Polite },
              )
            }
          }
        }
      }

      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = stringResource(R.string.phase6_secure_payment_notice),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = stringResource(R.string.phase6_accepted_methods),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = stringResource(R.string.razorpay_official_sdk_notice),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (!customerEmail.isNullOrBlank() || !customerPhone.isNullOrBlank()) {
        Text(
          text = listOfNotNull(customerName, customerEmail, customerPhone)
            .filter { it.isNotBlank() }
            .joinToString(" • "),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("razorpay_customer_summary"),
        )
      }

      if (isFailed) {
        Text(
          text = stringResource(R.string.phase6_retry_safe_message),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onErrorContainer,
          modifier = Modifier.testTag("payment_attempt_failure_reason"),
        )
      }

      if (!paymentError.isNullOrBlank()) {
        Surface(
          color = MaterialTheme.colorScheme.errorContainer,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth().testTag("razorpay_sheet_error_banner"),
        ) {
          Text(
            text = paymentError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(8.dp),
          )
        }
      }

      if (isPollingGatewayOrder || gatewayOrderId.isNullOrBlank() || isVerifyingPayment) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
          text = stringResource(R.string.phase6_preparing_secure_checkout),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Button(
        onClick = {
          val keyId = razorpayKeyId
          val gwOrderId = gatewayOrderId
          if (keyId.isNullOrBlank() || gwOrderId.isNullOrBlank()) {
            onSdkLaunchError(com.razorpay.Checkout.INVALID_OPTIONS, "Missing Razorpay key or gateway order.")
            return@Button
          }
          val activity = context.findActivity()
          if (activity == null) {
            onSdkLaunchError(com.razorpay.Checkout.INVALID_OPTIONS, "Host activity unavailable for Razorpay Checkout.")
            return@Button
          }
          onLaunchStarted(order.id, gwOrderId)
          try {
            razorpayCheckoutLauncher.launch(
              activity = activity,
              request = RazorpayCheckoutRequest(
                orderId = order.id,
                orderNumber = order.orderNumber,
                gatewayOrderId = gwOrderId,
                razorpayKeyId = keyId,
                amountPaise = amountPaise,
                customerName = customerName,
                customerEmail = customerEmail,
                customerPhone = customerPhone,
              ),
            )
          } catch (t: Throwable) {
            onSdkLaunchError(
              com.razorpay.Checkout.INVALID_OPTIONS,
              t.message ?: "Unable to launch Razorpay Checkout.",
            )
          }
        },
        enabled = !gatewayOrderId.isNullOrBlank() &&
          !razorpayKeyId.isNullOrBlank() &&
          !isVerifyingPayment &&
          !isReportingFailure &&
          !isExpired,
        modifier = Modifier
          .fillMaxWidth()
          .height(52.dp)
          .testTag("launch_razorpay_checkout_button"),
      ) {
        if (isVerifyingPayment) {
          CircularProgressIndicator(
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(8.dp))
        }
        Text(
          if (isVerifyingPayment) {
            stringResource(R.string.razorpay_verifying)
          } else {
            stringResource(R.string.phase6_pay_securely, amountLabel)
          },
        )
      }

      // Developer reference data is opt-in: shown only after an attempt exists, collapsed by default.
      if (!gatewayOrderId.isNullOrBlank()) {
        TextButton(
          onClick = { referenceExpanded = !referenceExpanded },
          modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag("payment_reference_toggle"),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(stringResource(R.string.phase6_payment_reference_label))
            Icon(
              imageVector = if (referenceExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
              contentDescription = null,
            )
          }
        }
        AnimatedVisibility(visible = referenceExpanded) {
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              if (!gatewayOrderId.isNullOrBlank()) {
                Text(
                  text = stringResource(R.string.phase6_reference_gateway_order, gatewayOrderId),
                  style = PizzoraMonoBody,
                  modifier = Modifier.testTag("razorpay_gateway_order_id_text"),
                )
              }
              if (razorpayKeyId != null) {
                Text(
                  text = stringResource(R.string.phase6_reference_key, razorpayKeyId),
                  style = PizzoraMonoBody,
                  modifier = Modifier.testTag("razorpay_key_id_text"),
                )
              }
            }
          }
        }
      }
    }
  }
}

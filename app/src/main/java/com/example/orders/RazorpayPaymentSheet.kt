package com.example.orders

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.pizzora.contracts.Money
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderPaymentStatusResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.RefundDto
import com.pizzora.contracts.RefundStatus

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
  val canRetry = paymentStatus?.canRetry ?: true
  val isInitiating = latestAttempt?.status == PaymentAttemptStatus.INITIATING
  val isAwaitingCustomer = latestAttempt?.status == PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT &&
    !latestAttempt.gatewayOrderId.isNullOrBlank()
  val isFailed = latestAttempt?.status == PaymentAttemptStatus.FAILED

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
            text = "Attempt #${latestAttempt?.attemptNumber ?: 1} • ${latestAttempt?.status?.name ?: "INITIATING"}",
            style = PizzoraMonoBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          text = Money.formatInr(order.totalPaise),
          style = PizzoraMonoTitle,
          color = MaterialTheme.colorScheme.primary,
        )
      }

      if (isPollingGatewayOrder || isInitiating || isRetryingPayment) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("razorpay_polling_indicator"))
        Text(
          text = stringResource(R.string.razorpay_preparing_order),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (!latestAttempt?.gatewayOrderId.isNullOrBlank()) {
        Text(
          text = "Gateway Order: ${latestAttempt?.gatewayOrderId}",
          style = PizzoraMonoBody,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("card_gateway_order_id_text"),
        )
      }

      if (isFailed && !latestAttempt?.failureReason.isNullOrBlank()) {
        Text(
          text = latestAttempt?.failureReason ?: "",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
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
            enabled = !isRetryingPayment,
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
              text = "${refund.status.name} • ${Money.formatInr(refund.amountPaise)}",
              style = PizzoraMonoBody,
              color = if (refund.status == RefundStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
              text = refund.reason,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          refund.gatewayRefundId?.let { gwId ->
            Text(
              text = gwId,
              style = PizzoraMonoBody,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
fun RazorpayPaymentSheetDialog(
  order: OrderDetailDto,
  paymentStatus: OrderPaymentStatusResponse?,
  isPollingGatewayOrder: Boolean,
  isVerifyingPayment: Boolean,
  isReportingFailure: Boolean,
  paymentError: String?,
  onDismiss: () -> Unit,
  onVerifyPayment: (gatewayPaymentId: String, signature: String) -> Unit,
  onReportFailure: (failureCode: String, failureReason: String) -> Unit,
) {
  val latestAttempt = paymentStatus?.payment?.latestAttempt ?: order.payment.latestAttempt
  val gatewayOrderId = latestAttempt?.gatewayOrderId
  val razorpayKeyId = paymentStatus?.razorpayKeyId ?: "rzp_test"

  var gatewayPaymentId by remember(gatewayOrderId) { mutableStateOf("") }
  var signature by remember(gatewayOrderId) { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("razorpay_payment_sheet"),
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(Icons.Filled.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.razorpay_sheet_title), style = MaterialTheme.typography.titleLarge)
      }
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = "${order.orderNumber} • ${Money.formatInr(order.totalPaise)}",
          style = PizzoraMonoTitle,
          color = MaterialTheme.colorScheme.primary,
        )
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              text = "Key: $razorpayKeyId",
              style = PizzoraMonoBody,
              modifier = Modifier.testTag("razorpay_key_id_text"),
            )
            if (!gatewayOrderId.isNullOrBlank()) {
              Text(
                text = "Gateway Order: $gatewayOrderId",
                style = PizzoraMonoBody,
                modifier = Modifier.testTag("razorpay_gateway_order_id_text"),
              )
            } else {
              Text(
                text = stringResource(R.string.razorpay_preparing_order),
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }

        if (isPollingGatewayOrder || gatewayOrderId.isNullOrBlank()) {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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

        OutlinedTextField(
          value = gatewayPaymentId,
          onValueChange = { gatewayPaymentId = it },
          label = { Text(stringResource(R.string.razorpay_payment_id_hint)) },
          singleLine = true,
          enabled = !gatewayOrderId.isNullOrBlank() && !isVerifyingPayment,
          modifier = Modifier.fillMaxWidth().testTag("razorpay_payment_id_input"),
        )
        OutlinedTextField(
          value = signature,
          onValueChange = { signature = it },
          label = { Text(stringResource(R.string.razorpay_signature_hint)) },
          singleLine = true,
          enabled = !gatewayOrderId.isNullOrBlank() && !isVerifyingPayment,
          modifier = Modifier.fillMaxWidth().testTag("razorpay_signature_input"),
        )

        Spacer(modifier = Modifier.height(2.dp))
        OutlinedButton(
          onClick = {
            onReportFailure("PAYMENT_FAILED_BY_GATEWAY", "Payment declined or cancelled at Razorpay checkout")
          },
          enabled = !isVerifyingPayment && !isReportingFailure,
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
          modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag("fail_razorpay_payment_button"),
        ) {
          Text(stringResource(R.string.action_report_payment_failure))
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onVerifyPayment(gatewayPaymentId, signature) },
        enabled = !gatewayOrderId.isNullOrBlank() && !isVerifyingPayment && !isReportingFailure,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("verify_razorpay_payment_button"),
      ) {
        Text(
          if (isVerifyingPayment) stringResource(R.string.razorpay_verifying)
          else stringResource(R.string.action_verify_payment),
        )
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("close_razorpay_sheet_button"),
      ) {
        Text(stringResource(R.string.action_close))
      }
    },
  )
}

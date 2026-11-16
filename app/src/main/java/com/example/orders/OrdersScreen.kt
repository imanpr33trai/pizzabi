package com.example.orders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.checkout.PriceBreakdownCard
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.example.core.domain.RustDomainBridge
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.Money
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PriceBreakdownDto
import com.pizzora.contracts.RealtimeConnectionState

@Composable
fun OrdersScreen(
  uiState: OrdersUiState,
  onRefreshOrders: () -> Unit,
  onOpenOrder: (String) -> Unit,
  onBackToList: () -> Unit,
  onCancelOrder: (String?) -> Unit,
  onBrowseMenu: () -> Unit,
  onOpenPaymentSheet: () -> Unit = {},
  onDismissPaymentSheet: () -> Unit = {},
  onRetryPayment: () -> Unit = {},
  customerName: String? = null,
  customerEmail: String? = null,
  customerPhone: String? = null,
  razorpayCheckoutLauncher: RazorpayCheckoutLauncher = AndroidRazorpayCheckoutLauncher(),
  onRazorpayLaunchStarted: (String, String) -> Unit = { _, _ -> },
  onRazorpaySdkError: (Int, String?) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier,
) {
  val selected = uiState.selectedOrder
  if (selected != null) {
    OrderDetailScreen(
      order = selected,
      uiState = uiState,
      onBack = onBackToList,
      onRefresh = { onOpenOrder(selected.id) },
      onCancelOrder = onCancelOrder,
      onOpenPaymentSheet = onOpenPaymentSheet,
      onDismissPaymentSheet = onDismissPaymentSheet,
      onRetryPayment = onRetryPayment,
      customerName = customerName,
      customerEmail = customerEmail,
      customerPhone = customerPhone,
      razorpayCheckoutLauncher = razorpayCheckoutLauncher,
      onRazorpayLaunchStarted = onRazorpayLaunchStarted,
      onRazorpaySdkError = onRazorpaySdkError,
      modifier = modifier,
    )
  } else {
    OrderHistoryScreen(
      uiState = uiState,
      onRefreshOrders = onRefreshOrders,
      onOpenOrder = onOpenOrder,
      onBrowseMenu = onBrowseMenu,
      modifier = modifier,
    )
  }
}

@Composable
private fun OrderHistoryScreen(
  uiState: OrdersUiState,
  onRefreshOrders: () -> Unit,
  onOpenOrder: (String) -> Unit,
  onBrowseMenu: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxSize().testTag("orders_history_screen"),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 720.dp)
        .padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(stringResource(R.string.orders_title), style = MaterialTheme.typography.headlineSmall)
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        RealtimeConnectionBadge(connectionState = uiState.connectionState)
        IconButton(
          onClick = onRefreshOrders,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("refresh_orders_button"),
        ) {
          Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
        }
      }
    }

    if (uiState.isLoadingList) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    LazyColumn(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .widthIn(max = 720.dp)
        .testTag("orders_lazy_column"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!uiState.listError.isNullOrBlank()) {
        item(key = "orders_list_error") {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag("orders_error_banner"),
          ) {
            Text(
              text = uiState.listError,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(14.dp),
            )
          }
        }
      }

      if (!uiState.isLoadingList && uiState.orders.isEmpty()) {
        item(key = "orders_empty") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().testTag("orders_empty_state"),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
              )
              Text(stringResource(R.string.orders_empty_title), style = MaterialTheme.typography.titleLarge)
              Text(
                text = stringResource(R.string.orders_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Button(
                onClick = onBrowseMenu,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("orders_browse_menu_button"),
              ) {
                Text(stringResource(R.string.cart_browse_menu))
              }
            }
          }
        }
      } else {
        items(uiState.orders, key = { it.id }) { summary ->
          OrderSummaryCard(
            order = summary,
            onClick = { onOpenOrder(summary.id) },
          )
        }
      }
    }
  }
}

@Composable
private fun OrderSummaryCard(
  order: OrderSummaryDto,
  onClick: () -> Unit,
) {
  Card(
    onClick = onClick,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    modifier = Modifier.fillMaxWidth().testTag("order_summary_card_${order.id}"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = order.orderNumber,
          style = PizzoraMonoTitle,
          color = MaterialTheme.colorScheme.onSurface,
        )
        OrderStatusPill(status = order.status)
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "${order.itemCount} item(s) • ${order.fulfillmentType.name} • ${order.paymentMethod.name}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = Money.formatInr(order.totalPaise),
          style = PizzoraMonoTitle,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
private fun OrderDetailScreen(
  order: OrderDetailDto,
  uiState: OrdersUiState,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onCancelOrder: (String?) -> Unit,
  onOpenPaymentSheet: () -> Unit,
  onDismissPaymentSheet: () -> Unit,
  onRetryPayment: () -> Unit,
  customerName: String?,
  customerEmail: String?,
  customerPhone: String?,
  razorpayCheckoutLauncher: RazorpayCheckoutLauncher,
  onRazorpayLaunchStarted: (String, String) -> Unit,
  onRazorpaySdkError: (Int, String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onBack)

  val isLoading = uiState.isLoadingDetail || uiState.isCancelling || uiState.isRetryingPayment || uiState.isVerifyingPayment
  val error = uiState.detailError ?: uiState.paymentError
  val actionMessage = uiState.actionMessage

  var showCancelDialog by remember { mutableStateOf(false) }
  var cancelReason by remember { mutableStateOf("") }

  if (uiState.isPaymentSheetOpen) {
    RazorpayPaymentSheetDialog(
      order = order,
      paymentStatus = uiState.paymentStatus,
      isPollingGatewayOrder = uiState.isPollingGatewayOrder,
      isVerifyingPayment = uiState.isVerifyingPayment,
      isReportingFailure = uiState.isReportingFailure,
      paymentError = uiState.paymentError,
      customerName = customerName,
      customerEmail = customerEmail,
      customerPhone = customerPhone,
      razorpayCheckoutLauncher = razorpayCheckoutLauncher,
      onLaunchStarted = onRazorpayLaunchStarted,
      onSdkLaunchError = onRazorpaySdkError,
      onDismiss = onDismissPaymentSheet,
    )
  }

  if (showCancelDialog) {
    AlertDialog(
      onDismissRequest = { showCancelDialog = false },
      title = { Text(stringResource(R.string.cancel_dialog_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.cancel_dialog_body))
          OutlinedTextField(
            value = cancelReason,
            onValueChange = { cancelReason = it },
            label = { Text(stringResource(R.string.cancel_reason_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("cancel_reason_input"),
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            showCancelDialog = false
            onCancelOrder(cancelReason.trim().takeIf { it.isNotEmpty() })
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          modifier = Modifier.minimumInteractiveComponentSize().testTag("confirm_cancel_order_button"),
        ) {
          Text(stringResource(R.string.action_confirm_cancel))
        }
      },
      dismissButton = {
        OutlinedButton(
          onClick = { showCancelDialog = false },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("dismiss_cancel_dialog_button"),
        ) {
          Text(stringResource(R.string.action_close))
        }
      },
    )
  }

  Column(
    modifier = modifier.fillMaxSize().testTag("order_detail_screen"),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 720.dp)
        .padding(horizontal = 8.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        IconButton(
          onClick = onBack,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("order_detail_back_button"),
        ) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Column {
          Text(order.orderNumber, style = PizzoraMonoTitle, modifier = Modifier.testTag("order_detail_number_text"))
          Text(stringResource(R.string.order_detail_title), style = MaterialTheme.typography.bodySmall)
        }
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        RealtimeConnectionBadge(connectionState = uiState.connectionState)
        IconButton(
          onClick = onRefresh,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("order_detail_refresh_button"),
        ) {
          Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
        }
      }
    }

    if (isLoading) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    LazyColumn(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .widthIn(max = 720.dp)
        .testTag("order_detail_lazy_column"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!actionMessage.isNullOrBlank()) {
        item(key = "order_action_message") {
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag("order_action_message_banner"),
          ) {
            Text(
              text = actionMessage,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.padding(12.dp),
            )
          }
        }
      }

      if (!error.isNullOrBlank()) {
        item(key = "order_detail_error") {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag("order_detail_error_banner"),
          ) {
            Text(
              text = error,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(12.dp),
            )
          }
        }
      }

      item(key = "order_tracking_stepper") {
        OrderTrackingStepperCard(order = order)
      }

      item(key = "order_status_summary") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text("Current Status", style = MaterialTheme.typography.titleMedium)
              OrderStatusPill(
                status = order.status,
                modifier = Modifier.testTag("order_detail_status_badge"),
              )
            }
            Text(
              text = "Payment: ${order.payment.method.name} (${order.payment.status.name})",
              style = PizzoraMonoBody,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.testTag("order_detail_payment_status"),
            )
            order.deliveryAddress?.let { addr ->
              Text(
                text = "Deliver to ${addr.label}: ${addr.line1}, ${addr.city} - ${addr.pincode}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            if (RustDomainBridge.canCustomerCancel(order.status)) {
              Spacer(modifier = Modifier.height(4.dp))
              OutlinedButton(
                onClick = { showCancelDialog = true },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("cancel_order_button"),
              ) {
                Icon(Icons.Filled.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text(stringResource(R.string.action_cancel_order), color = MaterialTheme.colorScheme.error)
              }
            }
          }
        }
      }

      if (order.status == OrderStatus.PENDING_PAYMENT) {
        item(key = "razorpay_payment_action_card") {
          RazorpayPaymentActionCard(
            order = order,
            paymentStatus = uiState.paymentStatus,
            isPollingGatewayOrder = uiState.isPollingGatewayOrder,
            isRetryingPayment = uiState.isRetryingPayment,
            onOpenPaymentSheet = onOpenPaymentSheet,
            onRetryPayment = onRetryPayment,
          )
        }
      }

      val refunds = uiState.paymentStatus?.refunds.orEmpty()
      if (refunds.isNotEmpty()) {
        item(key = "order_refunds_card") {
          OrderRefundsCard(refunds = refunds)
        }
      }

      item(key = "order_items_card") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(stringResource(R.string.order_items_section), style = MaterialTheme.typography.titleMedium)
            order.items.forEachIndexed { idx, item ->
              if (idx > 0) HorizontalDivider()
              Row(
                modifier = Modifier.fillMaxWidth().testTag("order_detail_item_${item.id}"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text("${item.quantity}x ${item.productName}", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "${item.variantSize} • ${item.crustName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  if (item.toppings.isNotEmpty()) {
                    Text(
                      text = "Toppings: ${item.toppings.joinToString(", ")}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
                Text(
                  text = Money.formatInr(item.lineTotalPaise),
                  style = PizzoraMonoBody,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
          }
        }
      }

      item(key = "order_breakdown_card") {
        PriceBreakdownCard(
          breakdown = PriceBreakdownDto(
            subtotalPaise = order.subtotalPaise,
            taxableSubtotalPaise = order.subtotalPaise,
            discountPaise = order.discountPaise,
            taxableAmountPaise = (order.subtotalPaise - order.discountPaise).coerceAtLeast(0L),
            deliveryFeePaise = order.deliveryFeePaise,
            gstBps = order.gstBpsSnapshot,
            taxPaise = order.taxPaise,
            cgstPaise = order.cgstPaise,
            sgstPaise = order.sgstPaise,
            totalPaise = order.totalPaise,
          ),
          appliedCouponCode = order.couponCodeSnapshot,
          isLoading = false,
        )
      }

      item(key = "order_timeline_card") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
          modifier = Modifier.fillMaxWidth().testTag("order_status_history_card"),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(stringResource(R.string.order_timeline_section), style = MaterialTheme.typography.titleMedium)
            order.statusHistory.forEach { entry ->
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  imageVector = if (entry.toStatus == OrderStatus.CANCELLED) Icons.Filled.Cancel else Icons.Filled.CheckCircle,
                  contentDescription = null,
                  tint = if (entry.toStatus == OrderStatus.CANCELLED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                  modifier = Modifier.size(16.dp),
                )
                Column {
                  Text(entry.toStatus.name, style = MaterialTheme.typography.labelLarge)
                  if (!entry.reason.isNullOrBlank()) {
                    Text(
                      text = entry.reason ?: "",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun OrderStatusPill(
  status: OrderStatus,
  modifier: Modifier = Modifier,
) {
  val (container, content) = when (status) {
    OrderStatus.CANCELLED, OrderStatus.EXPIRED ->
      MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    OrderStatus.COMPLETED, OrderStatus.READY ->
      MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    OrderStatus.PENDING_PAYMENT ->
      MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    else ->
      MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
  }

  Surface(
    color = container,
    shape = MaterialTheme.shapes.small,
    modifier = modifier.semantics(mergeDescendants = true) {},
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(Icons.Filled.Schedule, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
      Text(text = status.name, style = PizzoraMonoBody, color = content)
    }
  }
}

@Composable
private fun RealtimeConnectionBadge(
  connectionState: RealtimeConnectionState,
  modifier: Modifier = Modifier,
) {
  val (container, content, label) = when (connectionState) {
    RealtimeConnectionState.CONNECTED -> Triple(
      MaterialTheme.colorScheme.secondaryContainer,
      MaterialTheme.colorScheme.onSecondaryContainer,
      stringResource(R.string.realtime_live),
    )
    RealtimeConnectionState.CONNECTING -> Triple(
      MaterialTheme.colorScheme.tertiaryContainer,
      MaterialTheme.colorScheme.onTertiaryContainer,
      stringResource(R.string.realtime_connecting),
    )
    RealtimeConnectionState.RECONNECTING -> Triple(
      MaterialTheme.colorScheme.tertiaryContainer,
      MaterialTheme.colorScheme.onTertiaryContainer,
      stringResource(R.string.realtime_reconnecting),
    )
    RealtimeConnectionState.DISCONNECTED -> Triple(
      MaterialTheme.colorScheme.surfaceVariant,
      MaterialTheme.colorScheme.onSurfaceVariant,
      stringResource(R.string.realtime_offline),
    )
  }

  Surface(
    color = container,
    shape = MaterialTheme.shapes.small,
    modifier = modifier
      .semantics(mergeDescendants = true) {}
      .testTag("realtime_connection_badge"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      val icon = when (connectionState) {
        RealtimeConnectionState.CONNECTED -> Icons.Filled.CheckCircle
        RealtimeConnectionState.CONNECTING, RealtimeConnectionState.RECONNECTING -> Icons.Filled.Sync
        RealtimeConnectionState.DISCONNECTED -> Icons.Filled.WifiOff
      }
      Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(12.dp))
      Text(text = label, style = PizzoraMonoBody, color = content)
    }
  }
}

@Composable
private fun OrderTrackingStepperCard(
  order: OrderDetailDto,
  modifier: Modifier = Modifier,
) {
  val steps = remember(order.fulfillmentType, order.payment.method) {
    buildList {
      if (order.payment.method == PaymentMethod.RAZORPAY) {
        add(OrderStatus.PENDING_PAYMENT)
      }
      add(OrderStatus.PLACED)
      add(OrderStatus.ACCEPTED)
      add(OrderStatus.PREPARING)
      add(OrderStatus.READY)
      if (order.fulfillmentType == FulfillmentType.DELIVERY) {
        add(OrderStatus.OUT_FOR_DELIVERY)
      }
      add(OrderStatus.COMPLETED)
    }
  }

  val isTerminalAbnormal = order.status == OrderStatus.CANCELLED || order.status == OrderStatus.EXPIRED
  val currentIndex = if (isTerminalAbnormal) {
    val lastReached = order.statusHistory.mapNotNull { entry ->
      steps.indexOf(entry.toStatus).takeIf { it >= 0 }
    }.maxOrNull() ?: -1
    lastReached
  } else {
    steps.indexOf(order.status)
  }

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    modifier = modifier.fillMaxWidth().testTag("order_tracking_stepper_card"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(stringResource(R.string.tracking_stepper_title), style = MaterialTheme.typography.titleMedium)
        Text(
          text = "v${order.version}",
          style = PizzoraMonoBody,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("order_tracking_version_text"),
        )
      }

      if (isTerminalAbnormal) {
        Surface(
          color = MaterialTheme.colorScheme.errorContainer,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth().testTag("tracking_terminal_banner"),
        ) {
          Text(
            text = if (order.status == OrderStatus.CANCELLED) {
              stringResource(R.string.tracking_cancelled_note)
            } else {
              stringResource(R.string.tracking_expired_note)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(10.dp),
          )
        }
      }

      steps.forEachIndexed { index, stepStatus ->
        val isCompleted = when {
          isTerminalAbnormal -> index <= currentIndex
          order.status == OrderStatus.COMPLETED -> true
          else -> index < currentIndex
        }
        val isActive = !isTerminalAbnormal && order.status != OrderStatus.COMPLETED && index == currentIndex

        val label = when (stepStatus) {
          OrderStatus.PENDING_PAYMENT -> stringResource(R.string.tracking_step_payment)
          OrderStatus.PLACED -> stringResource(R.string.tracking_step_placed)
          OrderStatus.ACCEPTED -> stringResource(R.string.tracking_step_accepted)
          OrderStatus.PREPARING -> stringResource(R.string.tracking_step_preparing)
          OrderStatus.READY -> stringResource(R.string.tracking_step_ready)
          OrderStatus.OUT_FOR_DELIVERY -> stringResource(R.string.tracking_step_delivery)
          OrderStatus.COMPLETED -> if (order.fulfillmentType == FulfillmentType.PICKUP) {
            stringResource(R.string.tracking_step_picked_up)
          } else {
            stringResource(R.string.tracking_step_completed)
          }
          else -> stepStatus.name
        }

        val timestampRaw = when (stepStatus) {
          OrderStatus.PENDING_PAYMENT -> order.createdAt
          OrderStatus.PLACED -> order.statusHistory.firstOrNull { it.toStatus == OrderStatus.PLACED }?.createdAt ?: if (order.payment.method == PaymentMethod.COD) order.createdAt else null
          OrderStatus.ACCEPTED -> order.acceptedAt
          OrderStatus.PREPARING -> order.prepStartedAt
          OrderStatus.READY -> order.readyAt
          OrderStatus.COMPLETED -> order.completedAt
          else -> order.statusHistory.firstOrNull { it.toStatus == stepStatus }?.createdAt
        }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .testTag("tracking_step_${stepStatus.name.lowercase()}"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          val circleModifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
          when {
            isCompleted -> {
              Box(
                modifier = circleModifier.background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center,
              ) {
                Icon(
                  imageVector = Icons.Filled.Check,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSecondary,
                  modifier = Modifier.size(14.dp),
                )
              }
            }
            isActive -> {
              Box(
                modifier = circleModifier
                  .background(MaterialTheme.colorScheme.primaryContainer)
                  .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
              ) {
                Box(
                  modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                )
              }
            }
            else -> {
              Box(
                modifier = circleModifier
                  .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
              )
            }
          }

          Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isActive || isCompleted) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
              isActive -> MaterialTheme.colorScheme.primary
              isCompleted -> MaterialTheme.colorScheme.onSurface
              else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
          )

          if (!timestampRaw.isNullOrBlank()) {
            val hhmm = remember(timestampRaw) { formatShortIsoTime(timestampRaw) }
            Text(
              text = hhmm,
              style = PizzoraMonoBody,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

private fun formatShortIsoTime(iso: String): String {
  val tIdx = iso.indexOf('T')
  if (tIdx >= 0 && iso.length >= tIdx + 6) {
    return iso.substring(tIdx + 1, tIdx + 6)
  }
  return iso.take(10)
}

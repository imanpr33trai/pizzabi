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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.checkout.PriceBreakdownCard
import com.example.ui.components.OrderDisplayFormatters
import com.example.ui.theme.LocalPizzoraSemanticColors
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.example.core.domain.RustDomainBridge
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.Money
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
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
  onSetOrderListFilter: (OrderListFilter) -> Unit = {},
  onDismissOrderPlacedCelebration: () -> Unit = {},
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
      onDismissCelebration = onDismissOrderPlacedCelebration,
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
      onSetOrderListFilter = onSetOrderListFilter,
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
  onSetOrderListFilter: (OrderListFilter) -> Unit,
  modifier: Modifier = Modifier,
) {
  // Keep the chosen segment stable across WebSocket/list refreshes even when the
  // host has not supplied a ViewModel callback, while always honouring VM changes.
  var selectedFilter by rememberSaveable { mutableStateOf(uiState.orderListFilter) }
  LaunchedEffect(uiState.orderListFilter) { selectedFilter = uiState.orderListFilter }

  val activeCount = uiState.orders.count { !isPastOrder(it.status) }
  val pastCount = uiState.orders.count { isPastOrder(it.status) }
  val visibleOrders = filterOrders(uiState.orders, selectedFilter)
  val emptyTitleRes = when (selectedFilter) {
    OrderListFilter.ACTIVE -> R.string.orders_empty_active_title
    OrderListFilter.PAST -> R.string.orders_empty_past_title
    OrderListFilter.ALL -> R.string.orders_empty_all_title
  }
  val emptySubtitleRes = when (selectedFilter) {
    OrderListFilter.ACTIVE -> R.string.orders_empty_active_subtitle
    OrderListFilter.PAST -> R.string.orders_empty_past_subtitle
    OrderListFilter.ALL -> R.string.orders_empty_all_subtitle
  }

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

    SingleChoiceSegmentedButtonRow(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 720.dp)
        .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
      SegmentedButton(
        selected = selectedFilter == OrderListFilter.ACTIVE,
        onClick = {
          selectedFilter = OrderListFilter.ACTIVE
          onSetOrderListFilter(OrderListFilter.ACTIVE)
        },
        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
        modifier = Modifier
          .weight(1f)
          .minimumInteractiveComponentSize()
          .semantics { role = Role.RadioButton }
          .testTag("orders_filter_active"),
      ) {
        Text(stringResource(R.string.orders_filter_active, activeCount))
      }
      SegmentedButton(
        selected = selectedFilter == OrderListFilter.PAST,
        onClick = {
          selectedFilter = OrderListFilter.PAST
          onSetOrderListFilter(OrderListFilter.PAST)
        },
        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
        modifier = Modifier
          .weight(1f)
          .minimumInteractiveComponentSize()
          .semantics { role = Role.RadioButton }
          .testTag("orders_filter_past"),
      ) {
        Text(stringResource(R.string.orders_filter_past, pastCount))
      }
      SegmentedButton(
        selected = selectedFilter == OrderListFilter.ALL,
        onClick = {
          selectedFilter = OrderListFilter.ALL
          onSetOrderListFilter(OrderListFilter.ALL)
        },
        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
        modifier = Modifier
          .weight(1f)
          .minimumInteractiveComponentSize()
          .semantics { role = Role.RadioButton }
          .testTag("orders_filter_all"),
      ) {
        Text(stringResource(R.string.orders_filter_all, uiState.orders.size))
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

      if (!uiState.isLoadingList && visibleOrders.isEmpty()) {
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
              Text(stringResource(emptyTitleRes), style = MaterialTheme.typography.titleLarge)
              Text(
                text = stringResource(emptySubtitleRes),
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
        items(visibleOrders, key = { it.id }) { summary ->
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
        OrderStatusPill(
          status = order.status,
          fulfillmentType = order.fulfillmentType,
        )
      }
      Text(
        text = OrderDisplayFormatters.formatIstTimestamp(
          isoTimestamp = order.createdAt,
          fallback = stringResource(R.string.order_date_unknown),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.checkout_item_count, order.itemCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = OrderDisplayFormatters.formatFulfillmentAndPayment(
              fulfillmentType = order.fulfillmentType,
              paymentMethod = order.paymentMethod,
              paymentStatus = order.paymentStatus,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
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
  onDismissCelebration: () -> Unit,
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
  var dismissedCelebrationOrderId by rememberSaveable { mutableStateOf<String?>(null) }
  var statusAnnouncement by remember { mutableStateOf<String?>(null) }
  var lastStatusKey by remember { mutableStateOf<String?>(null) }

  // A fresh placement signal for this order re-enables its confirmation banner.
  LaunchedEffect(uiState.celebrationOrderId) {
    if (uiState.celebrationOrderId != null) {
      dismissedCelebrationOrderId = null
    }
  }

  // Announce only real status transitions (including WebSocket updates), never recompositions.
  LaunchedEffect(order.id, order.status) {
    val key = "${order.id}:${order.status.name}"
    if (lastStatusKey != null && lastStatusKey != key) {
      statusAnnouncement = "Order ${order.orderNumber}: ${OrderDisplayFormatters.formatOrderStatus(order.status, order.fulfillmentType)}."
    }
    lastStatusKey = key
  }

  LaunchedEffect(uiState.isCancelling, order.status) {
    if (!uiState.isCancelling && order.status == OrderStatus.CANCELLED) {
      showCancelDialog = false
    }
  }

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
      onExpiryReached = { onRefresh() },
    )
  }

  if (showCancelDialog) {
    CancelOrderDialog(
      order = order,
      isCancelling = uiState.isCancelling,
      onDismiss = { showCancelDialog = false },
      onConfirm = { reason -> onCancelOrder(reason) },
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
      if (uiState.celebrationOrderId == order.id && dismissedCelebrationOrderId != order.id) {
        item(key = "order_placed_celebration") {
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag("order_placed_celebration_banner"),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = stringResource(R.string.order_placed_celebration_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
              )
              IconButton(
                onClick = {
                  dismissedCelebrationOrderId = order.id
                  onDismissCelebration()
                },
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("dismiss_order_placed_celebration"),
              ) {
                Icon(
                  Icons.Filled.Close,
                  contentDescription = stringResource(R.string.order_placed_celebration_dismiss_cd),
                  tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
              }
            }
          }
        }
      }

      item(key = "order_status_live_region") {
        Text(
          text = statusAnnouncement.orEmpty(),
          modifier = Modifier
            .testTag("order_status_live_region")
            .semantics { liveRegion = LiveRegionMode.Polite },
        )
      }

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
        OrderTrackingStepperCard(order = order, connectionState = uiState.connectionState)
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
                fulfillmentType = order.fulfillmentType,
                modifier = Modifier.testTag("order_detail_status_badge"),
              )
            }
            Text(
              text = OrderDisplayFormatters.formatFulfillmentAndPayment(
                fulfillmentType = order.fulfillmentType,
                paymentMethod = order.payment.method,
                paymentStatus = order.payment.status,
              ),
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
                  Text(
                    text = OrderDisplayFormatters.formatOrderStatus(entry.toStatus, order.fulfillmentType),
                    style = MaterialTheme.typography.labelLarge,
                  )
                  Text(
                    text = OrderDisplayFormatters.formatIstTimestamp(
                      isoTimestamp = entry.createdAt,
                      fallback = stringResource(R.string.order_date_unknown),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
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
  fulfillmentType: FulfillmentType? = null,
  modifier: Modifier = Modifier,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val (container, content) = when (status) {
    OrderStatus.CANCELLED, OrderStatus.EXPIRED ->
      MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    OrderStatus.COMPLETED ->
      MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    OrderStatus.PENDING_PAYMENT ->
      semantic.warningContainer to semantic.onWarningContainer
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
      Text(
        text = OrderDisplayFormatters.formatOrderStatus(status, fulfillmentType),
        style = PizzoraMonoBody,
        color = content,
      )
    }
  }
}

@Composable
private fun RealtimeConnectionBadge(
  connectionState: RealtimeConnectionState,
  modifier: Modifier = Modifier,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val (container, content, label) = when (connectionState) {
    RealtimeConnectionState.CONNECTED -> Triple(
      MaterialTheme.colorScheme.secondaryContainer,
      MaterialTheme.colorScheme.onSecondaryContainer,
      stringResource(R.string.realtime_live),
    )
    RealtimeConnectionState.CONNECTING -> Triple(
      semantic.warningContainer,
      semantic.onWarningContainer,
      stringResource(R.string.realtime_connecting),
    )
    RealtimeConnectionState.RECONNECTING -> Triple(
      semantic.warningContainer,
      semantic.onWarningContainer,
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
  connectionState: RealtimeConnectionState,
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
  val semantic = LocalPizzoraSemanticColors.current

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
        val freshness = when (connectionState) {
          RealtimeConnectionState.CONNECTED -> stringResource(R.string.order_tracking_fresh_live)
          RealtimeConnectionState.CONNECTING -> stringResource(R.string.order_tracking_fresh_connecting)
          RealtimeConnectionState.RECONNECTING -> stringResource(R.string.order_tracking_fresh_reconnecting)
          RealtimeConnectionState.DISCONNECTED -> stringResource(R.string.order_tracking_fresh_offline)
        }
        val versionDescription = stringResource(R.string.order_version_cd, order.version)
        Text(
          text = freshness,
          style = MaterialTheme.typography.bodySmall,
          color = when (connectionState) {
            RealtimeConnectionState.CONNECTED -> MaterialTheme.colorScheme.secondary
            RealtimeConnectionState.CONNECTING, RealtimeConnectionState.RECONNECTING -> semantic.onWarningContainer
            RealtimeConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
          },
          modifier = Modifier
            .testTag("order_tracking_version_text")
            .semantics { contentDescription = versionDescription },
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
          isTerminalAbnormal -> index < currentIndex
          order.status == OrderStatus.COMPLETED -> true
          else -> index < currentIndex
        }
        val isFailed = isTerminalAbnormal && index == currentIndex
        val isActive = !isTerminalAbnormal && order.status != OrderStatus.COMPLETED && index == currentIndex

        val label = OrderDisplayFormatters.formatOrderStatus(stepStatus, order.fulfillmentType)
        val timestampRaw = when (stepStatus) {
          OrderStatus.PENDING_PAYMENT -> order.createdAt
          OrderStatus.PLACED -> order.statusHistory.firstOrNull { it.toStatus == OrderStatus.PLACED }?.createdAt
            ?: if (order.payment.method == PaymentMethod.COD) order.createdAt else null
          OrderStatus.ACCEPTED -> order.acceptedAt
          OrderStatus.PREPARING -> order.prepStartedAt
          OrderStatus.READY -> order.readyAt
          OrderStatus.COMPLETED -> order.completedAt
          else -> order.statusHistory.firstOrNull { it.toStatus == stepStatus }?.createdAt
        }
        val timeText = timestampRaw
          ?.takeIf { it.isNotBlank() }
          ?.let { OrderDisplayFormatters.formatIstTimeOnly(it) }
          ?.takeIf { it.isNotBlank() }
        val stepDescription = if (timeText != null) {
          val timePhrase = when {
            isCompleted -> stringResource(R.string.order_step_time_completed, timeText)
            isFailed -> stringResource(R.string.order_step_time_stopped, timeText)
            isActive -> stringResource(R.string.order_step_time_started, timeText)
            else -> stringResource(R.string.order_step_time_recorded, timeText)
          }
          stringResource(R.string.order_step_semantics_with_time, index + 1, steps.size, label, timePhrase)
        } else {
          stringResource(R.string.order_step_semantics_no_time, index + 1, steps.size, label)
        }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .testTag("tracking_step_${stepStatus.name.lowercase()}")
            .semantics(mergeDescendants = true) { contentDescription = stepDescription },
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
              isFailed -> {
                Box(
                  modifier = circleModifier.background(MaterialTheme.colorScheme.errorContainer),
                  contentAlignment = Alignment.Center,
                ) {
                  Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
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
                    .border(1.5.dp, semantic.outlineInteractive, CircleShape),
                )
              }
            }
            // Continuous vertical rail connecting adjacent step indicators.
            if (index < steps.lastIndex) {
              Box(
                modifier = Modifier
                  .width(2.dp)
                  .height(28.dp)
                  .background(
                    when {
                      isCompleted -> MaterialTheme.colorScheme.secondary
                      isFailed -> MaterialTheme.colorScheme.errorContainer
                      else -> semantic.outlineInteractive
                    },
                  ),
              )
            }
          }

          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = label,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = if (isActive || isCompleted || isFailed) FontWeight.SemiBold else FontWeight.Normal,
              color = when {
                isActive -> MaterialTheme.colorScheme.primary
                isFailed -> MaterialTheme.colorScheme.onErrorContainer
                isCompleted -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
              },
            )
            if (timeText != null) {
              Text(
                text = timeText,
                style = PizzoraMonoBody,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun CancelOrderDialog(
  order: OrderDetailDto,
  isCancelling: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (String?) -> Unit,
) {
  var reason by rememberSaveable { mutableStateOf("") }
  val quickReasons = listOf(
    stringResource(R.string.order_cancel_reason_changed_mind) to "cancel_reason_chip_changed_mind",
    stringResource(R.string.order_cancel_reason_wrong_item) to "cancel_reason_chip_ordered_wrong_item",
    stringResource(R.string.order_cancel_reason_too_long) to "cancel_reason_chip_taking_too_long",
  )

  AlertDialog(
    onDismissRequest = { if (!isCancelling) onDismiss() },
    title = {
      Text(
        text = stringResource(R.string.cancel_dialog_title),
        modifier = Modifier.testTag("cancel_dialog_title"),
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .imePadding()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(stringResource(R.string.cancel_dialog_body))
        if (order.payment.status == PaymentStatus.CAPTURED) {
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = stringResource(
                R.string.order_cancel_refund_disclosure,
                OrderDisplayFormatters.formatPaymentMethod(order.payment.method),
              ),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.padding(10.dp),
            )
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          quickReasons.forEach { (label, tag) ->
            FilterChip(
              selected = reason == label,
              onClick = { reason = label },
              label = { Text(label) },
              enabled = !isCancelling,
              modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .testTag(tag),
            )
          }
        }
        OutlinedTextField(
          value = reason,
          onValueChange = { reason = it },
          label = { Text(stringResource(R.string.cancel_reason_hint)) },
          singleLine = true,
          enabled = !isCancelling,
          modifier = Modifier.fillMaxWidth().testTag("cancel_reason_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(reason.trim().takeIf { it.isNotEmpty() }) },
        enabled = !isCancelling,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.minimumInteractiveComponentSize().testTag("confirm_cancel_order_button"),
      ) {
        if (isCancelling) {
          CircularProgressIndicator(
            color = MaterialTheme.colorScheme.onError,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(6.dp))
        }
        Text(stringResource(if (isCancelling) R.string.order_cancel_confirming else R.string.action_confirm_cancel))
      }
    },
    dismissButton = {
      OutlinedButton(
        onClick = onDismiss,
        enabled = !isCancelling,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("dismiss_cancel_dialog_button"),
      ) {
        Text(stringResource(R.string.action_close))
      }
    },
  )
}

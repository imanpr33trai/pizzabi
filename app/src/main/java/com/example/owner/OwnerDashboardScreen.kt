package com.example.owner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.InventoryLedgerReason
import com.pizzora.contracts.Money
import com.pizzora.contracts.OrderSummaryDto

@Composable
fun OwnerDashboardScreen(
  uiState: OwnerUiState,
  onRefresh: () -> Unit,
  onToggleStoreOpen: (Boolean) -> Unit,
  onQuickRestock: (ingredientId: String, deltaQty: Long, reason: InventoryLedgerReason) -> Unit,
  onOpenKds: (() -> Unit)? = null,
  onGoToOperations: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val dashboard = uiState.dashboard
  BoxWithConstraints(modifier = modifier.fillMaxSize().testTag("owner_dashboard_screen")) {
    val isWide = maxWidth >= 600.dp

    if (uiState.isLoadingDashboard && dashboard == null) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.testTag("owner_dashboard_loading"))
      }
      return@BoxWithConstraints
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      if (uiState.errorMessage != null) {
        item {
          OwnerErrorBanner(message = uiState.errorMessage, onRetry = onRefresh)
        }
      }

      item {
        val settings = uiState.settings ?: dashboard?.settings
        val isOpen = settings?.isAcceptingOrders ?: true
        Card(
          colors = CardDefaults.cardColors(
            containerColor = if (isOpen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
          ),
          modifier = Modifier.fillMaxWidth().testTag("owner_store_status_card"),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(
              modifier = Modifier.weight(1f),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Filled.Storefront,
                contentDescription = null,
                tint = if (isOpen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
              )
              Column {
                Text(
                  text = settings?.storeName ?: "PIZZORA Artisanal Kitchen",
                  style = MaterialTheme.typography.titleMedium,
                  color = if (isOpen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                  text = if (isOpen) "Accepting Orders • GST ${(settings?.gstBps ?: 500) / 100.0}%" else "Kitchen Paused • Checkout Blocked",
                  style = MaterialTheme.typography.bodyMedium,
                  color = if (isOpen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                )
              }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Switch(
                checked = isOpen,
                enabled = !uiState.isSubmitting,
                onCheckedChange = onToggleStoreOpen,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_store_open_switch"),
              )
              IconButton(
                onClick = onRefresh,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_dashboard_refresh"),
              ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh dashboard")
              }
            }
          }
        }
      }

      if (dashboard != null) {
        item {
          if (isWide) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              KpiMetricCard(
                label = "Today's Revenue",
                value = Money.formatInr(dashboard.todayRevenuePaise),
                subtitle = "${dashboard.todayOrderCount} orders today",
                icon = Icons.Filled.Payments,
                tag = "kpi_today_revenue",
                modifier = Modifier.weight(1f),
              )
              KpiMetricCard(
                label = "Active Kitchen",
                value = dashboard.activeOrderCount.toString(),
                subtitle = "${dashboard.completedTodayCount} completed",
                icon = Icons.Filled.Kitchen,
                tag = "kpi_active_orders",
                modifier = Modifier.weight(1f),
              )
              KpiMetricCard(
                label = "Pending Payment",
                value = dashboard.pendingPaymentCount.toString(),
                subtitle = "Awaiting capture",
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                tag = "kpi_pending_payments",
                modifier = Modifier.weight(1f),
              )
              KpiMetricCard(
                label = "Low Stock",
                value = dashboard.lowStockCount.toString(),
                subtitle = "Ingredients below threshold",
                icon = Icons.Filled.Inventory2,
                highlightWarning = dashboard.lowStockCount > 0,
                tag = "kpi_low_stock",
                modifier = Modifier.weight(1f),
              )
            }
          } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiMetricCard(
                  label = "Today's Revenue",
                  value = Money.formatInr(dashboard.todayRevenuePaise),
                  subtitle = "${dashboard.todayOrderCount} orders today",
                  icon = Icons.Filled.Payments,
                  tag = "kpi_today_revenue",
                  modifier = Modifier.weight(1f),
                )
                KpiMetricCard(
                  label = "Active Kitchen",
                  value = dashboard.activeOrderCount.toString(),
                  subtitle = "${dashboard.completedTodayCount} completed",
                  icon = Icons.Filled.Kitchen,
                  tag = "kpi_active_orders",
                  modifier = Modifier.weight(1f),
                )
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiMetricCard(
                  label = "Pending Payment",
                  value = dashboard.pendingPaymentCount.toString(),
                  subtitle = "Awaiting capture",
                  icon = Icons.AutoMirrored.Filled.ReceiptLong,
                  tag = "kpi_pending_payments",
                  modifier = Modifier.weight(1f),
                )
                KpiMetricCard(
                  label = "Low Stock",
                  value = dashboard.lowStockCount.toString(),
                  subtitle = "Needs restock",
                  icon = Icons.Filled.Inventory2,
                  highlightWarning = dashboard.lowStockCount > 0,
                  tag = "kpi_low_stock",
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }

        if (onOpenKds != null) {
          item {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Button(
                onClick = onOpenKds,
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("dashboard_open_kds_button"),
              ) {
                Icon(Icons.Filled.Kitchen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Kitchen Display")
              }
              OutlinedButton(
                onClick = onGoToOperations,
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("dashboard_manage_ops_button"),
              ) {
                Icon(Icons.Filled.Inventory2, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Manage Stock & Orders")
              }
            }
          }
        }

        item {
          Text(
            text = "Low-Stock Alerts",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
          )
        }

        if (dashboard.lowStockItems.isEmpty()) {
          item {
            OwnerEmptyCard(
              title = "All ingredients healthy",
              subtitle = "Every ingredient is currently above its low-stock threshold.",
              tag = "owner_low_stock_empty",
            )
          }
        } else {
          items(dashboard.lowStockItems, key = { it.ingredientId }) { ing ->
            LowStockAlertRow(
              item = ing,
              isSubmitting = uiState.isSubmitting,
              onQuickRestock = {
                val restockAmount = (ing.lowStockThreshold * 2L).coerceAtLeast(100L)
                onQuickRestock(ing.ingredientId, restockAmount, InventoryLedgerReason.RESTOCK)
              },
            )
          }
        }

        item {
          Text(
            text = "Recent Orders",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
          )
        }

        if (dashboard.recentOrders.isEmpty()) {
          item {
            OwnerEmptyCard(
              title = "No orders placed yet",
              subtitle = "New customer orders will appear here in real time.",
              tag = "owner_recent_orders_empty",
            )
          }
        } else {
          items(dashboard.recentOrders, key = { it.id }) { order ->
            DashboardRecentOrderCard(order = order)
          }
        }
      }
    }
  }
}

@Composable
private fun KpiMetricCard(
  label: String,
  value: String,
  subtitle: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  tag: String,
  modifier: Modifier = Modifier,
  highlightWarning: Boolean = false,
) {
  Card(
    colors = CardDefaults.cardColors(
      containerColor = if (highlightWarning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    modifier = modifier.testTag(tag),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = if (highlightWarning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        )
        Text(
          text = label,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = value,
        style = PizzoraMonoTitle,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun LowStockAlertRow(
  item: InventoryItemDto,
  isSubmitting: Boolean,
  onQuickRestock: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    modifier = Modifier.fillMaxWidth().testTag("low_stock_item_${item.ingredientId}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        Column {
          Text(item.name, style = MaterialTheme.typography.titleMedium)
          Text(
            text = "Available: ${item.availableQty} ${item.unit.name} (min ${item.lowStockThreshold})",
            style = PizzoraMonoBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      FilledTonalButton(
        onClick = onQuickRestock,
        enabled = !isSubmitting,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("quick_restock_${item.ingredientId}"),
      ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Restock")
      }
    }
  }
}

@Composable
private fun DashboardRecentOrderCard(order: OrderSummaryDto) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    modifier = Modifier.fillMaxWidth().testTag("dashboard_order_${order.id}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(order.orderNumber, style = PizzoraMonoTitle, fontWeight = FontWeight.Bold)
        Text(
          text = "${order.fulfillmentType.name} • ${order.itemCount} items • ${order.paymentMethod.name}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        Text(Money.formatInr(order.totalPaise), style = PizzoraMonoBody, color = MaterialTheme.colorScheme.primary)
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = MaterialTheme.shapes.small,
        ) {
          Text(
            text = order.status.name,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      }
    }
  }
}

@Composable
fun OwnerErrorBanner(
  message: String,
  onRetry: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    modifier = Modifier.fillMaxWidth().testTag("owner_error_banner"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.weight(1f),
      )
      OutlinedButton(
        onClick = onRetry,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_error_retry"),
      ) {
        Text("Retry")
      }
    }
  }
}

@Composable
fun OwnerEmptyCard(
  title: String,
  subtitle: String,
  tag: String,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    modifier = Modifier.fillMaxWidth().testTag(tag),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

package com.example.owner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.pizzora.contracts.AuditLogEntryDto
import com.pizzora.contracts.Money
import com.pizzora.contracts.OperationsReportResponse
import com.pizzora.contracts.SalesReportResponse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val istFormatter: DateTimeFormatter =
  DateTimeFormatter.ofPattern("dd MMM, HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"))

private val auditEntityFilters: List<Pair<String?, String>> = listOf(
  null to "All",
  "order" to "Orders",
  "product" to "Products",
  "product_variant" to "Variants",
  "refund" to "Refunds",
  "coupon" to "Coupons",
  "inventory" to "Inventory",
  "staff" to "Staff",
  "RESTAURANT_SETTINGS" to "Settings",
)

@Composable
fun OwnerReportsAndAuditScreen(
  uiState: OwnerUiState,
  onSelectSubTab: (OwnerReportsSubTab) -> Unit,
  onSelectDatePreset: (ReportDatePreset) -> Unit,
  onRefresh: () -> Unit,
  onSelectAuditEntityFilter: (String?) -> Unit,
  onLoadMoreAuditLogs: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxSize().testTag("owner_reports_screen")) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f),
      ) {
        Icon(Icons.Filled.Assessment, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
          text = "Reports & Audit Trail",
          style = MaterialTheme.typography.titleLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      OutlinedButton(
        onClick = onRefresh,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_reports_refresh_button"),
      ) {
        Icon(Icons.Filled.Refresh, contentDescription = null)
        Spacer(modifier = Modifier.width(6.dp))
        Text("Refresh")
      }
    }

    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(OwnerReportsSubTab.entries) { subTab ->
        FilterChip(
          selected = uiState.reportsSubTab == subTab,
          onClick = { onSelectSubTab(subTab) },
          label = { Text(subTab.label) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_reports_subtab_${subTab.name.lowercase()}"),
        )
      }
    }

    if (uiState.reportsSubTab != OwnerReportsSubTab.AUDIT_LOGS) {
      LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(ReportDatePreset.entries) { preset ->
          FilterChip(
            selected = uiState.reportDatePreset == preset,
            onClick = { onSelectDatePreset(preset) },
            label = { Text(preset.label) },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("report_preset_${preset.name.lowercase()}"),
          )
        }
      }
    }

    when (uiState.reportsSubTab) {
      OwnerReportsSubTab.SALES -> SalesReportSection(
        report = uiState.salesReport,
        isLoading = uiState.isLoadingReports,
        errorMessage = uiState.errorMessage,
        onRetry = onRefresh,
        modifier = Modifier.weight(1f),
      )
      OwnerReportsSubTab.OPERATIONS -> OperationsReportSection(
        report = uiState.operationsReport,
        isLoading = uiState.isLoadingReports,
        errorMessage = uiState.errorMessage,
        onRetry = onRefresh,
        modifier = Modifier.weight(1f),
      )
      OwnerReportsSubTab.AUDIT_LOGS -> AuditLogsSection(
        entries = uiState.auditLogs,
        selectedFilter = uiState.auditEntityFilter,
        nextCursor = uiState.auditNextCursor,
        isLoading = uiState.isLoadingAuditLogs,
        errorMessage = uiState.errorMessage,
        onSelectFilter = onSelectAuditEntityFilter,
        onLoadMore = onLoadMoreAuditLogs,
        onRetry = onRefresh,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun SalesReportSection(
  report: SalesReportResponse?,
  isLoading: Boolean,
  errorMessage: String?,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (isLoading && report == null) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(modifier = Modifier.testTag("sales_report_loading"))
    }
    return
  }

  LazyColumn(
    modifier = modifier.fillMaxSize().testTag("sales_report_list"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (errorMessage != null) {
      item { OwnerErrorBanner(message = errorMessage, onRetry = onRetry) }
    }

    if (report == null) {
      item {
        OwnerEmptyCard(
          title = "Sales data unavailable",
          subtitle = "Tap Refresh to compute IST revenue and GST totals.",
          tag = "sales_report_empty",
        )
      }
      return@LazyColumn
    }

    item {
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("sales_net_revenue_card"),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Net Sales (${report.startDate} to ${report.endDate} IST)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
          Text(
            text = Money.formatInr(report.netSalesPaise),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
          Text(
            text = "${report.orderCount} paid/active orders • ${report.cancelledOrderCount} cancelled",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
          HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
          ReportMetricRow("Gross Sales", Money.formatInr(report.grossSalesPaise))
          ReportMetricRow("Refunded (Settled)", "-${Money.formatInr(report.refundedPaise)}")
          if (report.pendingRefundsPaise > 0L) {
            ReportMetricRow("Pending Refunds", Money.formatInr(report.pendingRefundsPaise))
          }
        }
      }
    }

    item {
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().testTag("sales_gst_breakdown_card"),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("GST & Order Components", style = MaterialTheme.typography.titleMedium)
          ReportMetricRow("Items Subtotal", Money.formatInr(report.subtotalPaise))
          ReportMetricRow("Discounts Applied", "-${Money.formatInr(report.discountPaise)}")
          ReportMetricRow("Delivery Fees", Money.formatInr(report.deliveryFeePaise))
          ReportMetricRow("Total GST", Money.formatInr(report.taxPaise))
          ReportMetricRow("CGST / SGST Split", "${Money.formatInr(report.cgstPaise)} + ${Money.formatInr(report.sgstPaise)}")
        }
      }
    }

    if (report.dailyBreakdown.isNotEmpty()) {
      item {
        Text("Daily Breakdown (Asia/Kolkata)", style = MaterialTheme.typography.titleMedium)
      }
      items(report.dailyBreakdown, key = { it.date }) { bucket ->
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          modifier = Modifier.fillMaxWidth().testTag("sales_daily_card_${bucket.date}"),
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(bucket.date, style = PizzoraMonoBody, fontWeight = FontWeight.Bold)
              Text(Money.formatInr(bucket.netSalesPaise), style = PizzoraMonoTitle, color = MaterialTheme.colorScheme.primary)
            }
            Text(
              text = "${bucket.orderCount} orders • Gross ${Money.formatInr(bucket.grossSalesPaise)} • Refunded ${Money.formatInr(bucket.refundedPaise)} • GST ${Money.formatInr(bucket.taxPaise)}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    if (report.topProducts.isNotEmpty()) {
      item {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Icon(Icons.Filled.LocalPizza, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Text("Top Selling Pizzas", style = MaterialTheme.typography.titleMedium)
        }
      }
      items(report.topProducts, key = { it.productId }) { item ->
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          modifier = Modifier.fillMaxWidth().testTag("sales_top_product_${item.productId}"),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(item.productName, style = MaterialTheme.typography.titleMedium)
              Text("${item.quantitySold} sold", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(Money.formatInr(item.grossRevenuePaise), style = PizzoraMonoBody, color = MaterialTheme.colorScheme.primary)
          }
        }
      }
    }

    if (report.couponPerformance.isNotEmpty()) {
      item {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Icon(Icons.Filled.LocalOffer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Text("Coupon ROI", style = MaterialTheme.typography.titleMedium)
        }
      }
      items(report.couponPerformance, key = { it.couponId }) { c ->
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          modifier = Modifier.fillMaxWidth().testTag("sales_coupon_${c.code}"),
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(c.code, style = PizzoraMonoTitle)
              Text("Net ${Money.formatInr(c.netOrderRevenuePaise)}", style = PizzoraMonoBody, color = MaterialTheme.colorScheme.secondary)
            }
            Text(
              text = "${c.redemptionCount} uses • Discount ${Money.formatInr(c.totalDiscountPaise)} • Gross ${Money.formatInr(c.grossOrderRevenuePaise)}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun OperationsReportSection(
  report: OperationsReportResponse?,
  isLoading: Boolean,
  errorMessage: String?,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (isLoading && report == null) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(modifier = Modifier.testTag("ops_report_loading"))
    }
    return
  }

  LazyColumn(
    modifier = modifier.fillMaxSize().testTag("ops_report_list"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (errorMessage != null) {
      item { OwnerErrorBanner(message = errorMessage, onRetry = onRetry) }
    }

    if (report == null) {
      item {
        OwnerEmptyCard(
          title = "Operations report unavailable",
          subtitle = "Tap Refresh to calculate prep SLA and stock wastage metrics.",
          tag = "ops_report_empty",
        )
      }
      return@LazyColumn
    }

    item {
      val healthySla = report.slaCompliancePercent >= 85.0
      Card(
        colors = CardDefaults.cardColors(
          containerColor = if (healthySla) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().testTag("ops_sla_card"),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Icon(Icons.Filled.Speed, contentDescription = null)
              Text("Prep SLA Compliance", style = MaterialTheme.typography.titleMedium)
            }
            Text(
              text = "${report.slaCompliancePercent}%",
              style = PizzoraMonoTitle,
            )
          }
          ReportMetricRow("Target SLA", "${report.prepSlaMinutes} mins")
          ReportMetricRow("Measured Orders", "${report.measuredPrepOrders} (${report.slaBreachedCount} breached)")
          ReportMetricRow("Avg / Max Prep", "${report.avgPrepMinutes} min / ${report.maxPrepMinutes} min")
          ReportMetricRow("Avg Completion", "${report.avgCompletionMinutes} min")
        }
      }
    }

    item {
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().testTag("ops_order_split_card"),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Order Throughput (${report.startDate} to ${report.endDate})", style = MaterialTheme.typography.titleMedium)
          ReportMetricRow("Total / Completed", "${report.totalOrders} / ${report.completedOrders}")
          ReportMetricRow("Cancelled / Expired", "${report.cancelledOrders} / ${report.expiredOrders}")
          ReportMetricRow("Delivery / Pickup", "${report.deliveryOrders} / ${report.pickupOrders}")
          ReportMetricRow("Scheduled Orders", "${report.scheduledOrders}")
        }
      }
    }

    item {
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().testTag("ops_stock_summary_card"),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Inventory & Wastage Movement", style = MaterialTheme.typography.titleMedium)
          ReportMetricRow("Restocked Units", "+${report.restockedUnits}")
          ReportMetricRow("Order Consumed Units", "-${report.consumedUnits}")
          ReportMetricRow("Cancel Restored Units", "+${report.restoredUnits}")
          ReportMetricRow("Manual Wastage / Shrink", "-${report.wastageUnits}")
        }
      }
    }

    if (report.stockMovements.isNotEmpty()) {
      item {
        Text("Ingredient Movements", style = MaterialTheme.typography.titleMedium)
      }
      items(report.stockMovements, key = { it.ingredientId }) { m ->
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          modifier = Modifier.fillMaxWidth().testTag("ops_movement_${m.ingredientId}"),
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(m.ingredientName, style = MaterialTheme.typography.titleMedium)
              Text(m.unit.name, style = PizzoraMonoBody, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
              text = "Restock +${m.restockedQty} • Used -${m.consumedQty} • Restored +${m.restoredQty} • Wastage -${m.wastageQty}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun AuditLogsSection(
  entries: List<AuditLogEntryDto>,
  selectedFilter: String?,
  nextCursor: String?,
  isLoading: Boolean,
  errorMessage: String?,
  onSelectFilter: (String?) -> Unit,
  onLoadMore: () -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxSize().testTag("audit_logs_section")) {
    LazyRow(
      modifier = Modifier.testTag("audit_filters_row"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(auditEntityFilters) { (filterKey, label) ->
        FilterChip(
          selected = selectedFilter == filterKey,
          onClick = { onSelectAuditFilterSafe(filterKey, onSelectFilter) },
          label = { Text(label) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("audit_filter_${filterKey ?: "all"}"),
        )
      }
    }

    if (isLoading && entries.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.testTag("audit_logs_loading"))
      }
      return
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize().testTag("audit_logs_list"),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (errorMessage != null) {
        item { OwnerErrorBanner(message = errorMessage, onRetry = onRetry) }
      }

      if (entries.isEmpty()) {
        item {
          OwnerEmptyCard(
            title = "No audit records match filter",
            subtitle = "Try selecting All entities or trigger an owner/kitchen action.",
            tag = "audit_logs_empty",
          )
        }
      } else {
        items(entries, key = { it.id }) { entry ->
          AuditLogCard(entry = entry)
        }

        if (nextCursor != null) {
          item {
            OutlinedButton(
              onClick = onLoadMore,
              enabled = !isLoading,
              modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("audit_load_more_button"),
            ) {
              if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
              }
              Text("Load Older Entries")
            }
          }
        }
      }
    }
  }
}

private fun onSelectAuditFilterSafe(filterKey: String?, onSelectFilter: (String?) -> Unit) {
  onSelectFilter(filterKey)
}

@Composable
private fun AuditLogCard(entry: AuditLogEntryDto) {
  var expanded by remember(entry.id) { mutableStateOf(false) }
  val hasPayloads = !entry.beforeJson.isNullOrBlank() || !entry.afterJson.isNullOrBlank()
  val formattedTime = remember(entry.createdAt) {
    runCatching { "${istFormatter.format(Instant.parse(entry.createdAt))} IST" }
      .getOrDefault(entry.createdAt)
  }

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    modifier = Modifier.fillMaxWidth().testTag("audit_entry_${entry.id}"),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          color = MaterialTheme.colorScheme.secondaryContainer,
          shape = MaterialTheme.shapes.small,
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(Icons.Filled.VerifiedUser, contentDescription = null, modifier = Modifier.width(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
              text = entry.action,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
          }
        }
        Text(
          text = formattedTime,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Text(
        text = "${entry.entityType} • ${entry.entityId}",
        style = PizzoraMonoBody,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )

      val actorLabel = entry.actorName ?: entry.actorEmail ?: "System"
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(16.dp))
        Text(
          text = "${entry.actorRole}: $actorLabel",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Text(
        text = "corr: ${entry.correlationId}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )

      if (hasPayloads) {
        TextButton(
          onClick = { expanded = !expanded },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("audit_toggle_json_${entry.id}"),
        ) {
          Text(if (expanded) "Hide Before / After JSON" else "Inspect Before / After JSON")
        }
        if (expanded) {
          if (!entry.beforeJson.isNullOrBlank()) {
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = MaterialTheme.shapes.small,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("BEFORE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.beforeJson!!, style = PizzoraMonoBody)
              }
            }
          }
          if (!entry.afterJson.isNullOrBlank()) {
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = MaterialTheme.shapes.small,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("AFTER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(entry.afterJson!!, style = PizzoraMonoBody)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ReportMetricRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = PizzoraMonoBody, fontWeight = FontWeight.Medium)
  }
}

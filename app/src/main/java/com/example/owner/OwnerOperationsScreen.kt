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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.pizzora.contracts.CouponDto
import com.pizzora.contracts.DeliveryZoneDto
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.InventoryLedgerReason
import com.example.core.domain.RustDomainBridge
import com.pizzora.contracts.Money
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.ScheduledSlotDto
import com.pizzora.contracts.UpdateDeliveryZoneRequest
import com.pizzora.contracts.UserRole

@Composable
fun OwnerOperationsScreen(
  uiState: OwnerUiState,
  onSelectSection: (OwnerOperationsSection) -> Unit,
  onRefresh: (OrderStatus?) -> Unit,
  onCreateIngredient: (name: String, unit: IngredientUnit, lowThreshold: Long, initialQty: Long) -> Unit,
  onAdjustInventory: (ingredientId: String, deltaQty: Long, reason: InventoryLedgerReason) -> Unit,
  onCreateCoupon: (
    code: String,
    discountType: DiscountType,
    discountValue: Long,
    minOrderPaise: Long,
    maxDiscountPaise: Long?,
    startsAt: String,
    endsAt: String,
  ) -> Unit,
  onToggleCouponActive: (CouponDto) -> Unit,
  onSelectOrder: (String) -> Unit,
  onDismissOrderDetail: () -> Unit,
  onTransitionOrderStatus: (orderId: String, toStatus: OrderStatus, reason: String) -> Unit,
  onRefundOrder: (orderId: String, amountPaise: Long?, reason: String) -> Unit,
  modifier: Modifier = Modifier,
  onRefreshZonesAndSlots: () -> Unit = {},
  onSlotFilterChange: (FulfillmentType?, String) -> Unit = { _, _ -> },
  onCreateDeliveryZone: (pincode: String, areaName: String, minOrderPaise: Long, deliveryFeePaise: Long, estimatedMins: Int) -> Unit = { _, _, _, _, _ -> },
  onUpdateDeliveryZone: (zoneId: String, request: UpdateDeliveryZoneRequest) -> Unit = { _, _ -> },
  onToggleZoneActive: (DeliveryZoneDto) -> Unit = {},
  onDeleteDeliveryZone: (String) -> Unit = {},
  onCreateScheduledSlot: (slotDate: String, fulfillmentType: FulfillmentType, startTimeIst: String, endTimeIst: String, cutoffMins: Int, maxCapacity: Int) -> Unit = { _, _, _, _, _, _ -> },
  onToggleSlotActive: (ScheduledSlotDto) -> Unit = {},
  onUpdateSlotCapacity: (slotId: String, maxCapacity: Int) -> Unit = { _, _ -> },
  onDeleteScheduledSlot: (String) -> Unit = {},
) {
  var showCreateIngredientDialog by remember { mutableStateOf(false) }
  var adjustingIngredient by remember { mutableStateOf<InventoryItemDto?>(null) }
  var showCreateCouponDialog by remember { mutableStateOf(false) }
  var refundingOrder by remember { mutableStateOf<OrderDetailDto?>(null) }
  var showCreateZoneDialog by remember { mutableStateOf(false) }
  var editingZone by remember { mutableStateOf<DeliveryZoneDto?>(null) }
  var showCreateSlotDialog by remember { mutableStateOf(false) }
  var editingSlot by remember { mutableStateOf<ScheduledSlotDto?>(null) }

  Box(modifier = modifier.fillMaxSize()) {
  Column(modifier = Modifier.fillMaxSize().testTag("owner_operations_screen")) {
    LazyRow(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(OwnerOperationsSection.entries) { section ->
        FilterChip(
          selected = uiState.operationsSection == section,
          onClick = { onSelectSection(section) },
          label = { Text(section.label) },
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("owner_ops_tab_${section.name.lowercase()}"),
        )
      }
    }

    if (uiState.isLoadingOperations && uiState.operationsSection != OwnerOperationsSection.ZONES_SLOTS && uiState.inventory.isEmpty() && uiState.orders.isEmpty() && uiState.coupons.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (uiState.errorMessage != null) {
          item {
            OwnerErrorBanner(message = uiState.errorMessage, onRetry = { onRefresh(uiState.selectedOrderFilter) })
          }
        }

        when (uiState.operationsSection) {
          OwnerOperationsSection.INVENTORY -> {
            item {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text("Ingredient Stock (${uiState.inventory.size})", style = MaterialTheme.typography.titleMedium)
                Button(
                  onClick = { showCreateIngredientDialog = true },
                  modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_add_ingredient_button"),
                ) {
                  Icon(Icons.Filled.Add, contentDescription = null)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Add Ingredient")
                }
              }
            }

            if (uiState.inventory.isEmpty()) {
              item {
                OwnerEmptyCard(
                  title = "No ingredients tracked yet",
                  subtitle = "Add mozzarella, dough, and toppings to enable recipe deduction.",
                  tag = "owner_inventory_empty",
                )
              }
            } else {
              items(uiState.inventory, key = { it.ingredientId }) { item ->
                InventoryItemCard(
                  item = item,
                  onAdjustClick = { adjustingIngredient = item },
                )
              }
            }

            if (uiState.inventoryLedger.isNotEmpty()) {
              item {
                Text("Recent Stock Ledger", style = MaterialTheme.typography.titleMedium)
              }
              items(uiState.inventoryLedger.take(10), key = { it.id }) { entry ->
                Card(
                  colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Column(modifier = Modifier.weight(1f)) {
                      Text(entry.ingredientName, style = MaterialTheme.typography.labelLarge)
                      Text(entry.reason.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                      text = "${if (entry.deltaOnHand >= 0) "+" else ""}${entry.deltaOnHand} ${entry.unit.name}",
                      style = PizzoraMonoBody,
                      color = if (entry.deltaOnHand >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    )
                  }
                }
              }
            }
          }

          OwnerOperationsSection.ORDERS -> {
            item {
              LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                  FilterChip(
                    selected = uiState.selectedOrderFilter == null,
                    onClick = { onRefresh(null) },
                    label = { Text("All") },
                  )
                }
                items(
                  listOf(OrderStatus.PLACED, OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.COMPLETED, OrderStatus.CANCELLED),
                ) { status ->
                  FilterChip(
                    selected = uiState.selectedOrderFilter == status,
                    onClick = { onRefresh(status) },
                    label = { Text(status.name) },
                  )
                }
              }
            }

            if (uiState.orders.isEmpty()) {
              item {
                OwnerEmptyCard(
                  title = "No matching orders",
                  subtitle = "Orders placed by customers will appear here for status override or refund.",
                  tag = "owner_orders_empty",
                )
              }
            } else {
              items(uiState.orders, key = { it.id }) { order ->
                OwnerOrderSummaryCard(
                  order = order,
                  onInspect = { onSelectOrder(order.id) },
                )
              }
            }
          }

          OwnerOperationsSection.COUPONS -> {
            item {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text("Promo Coupons (${uiState.coupons.size})", style = MaterialTheme.typography.titleMedium)
                Button(
                  onClick = { showCreateCouponDialog = true },
                  modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_add_coupon_button"),
                ) {
                  Icon(Icons.Filled.Add, contentDescription = null)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("New Coupon")
                }
              }
            }

            if (uiState.coupons.isEmpty()) {
              item {
                OwnerEmptyCard(
                  title = "No promotional coupons",
                  subtitle = "Create percentage or flat discount codes for customer checkout.",
                  tag = "owner_coupons_empty",
                )
              }
            } else {
              items(uiState.coupons, key = { it.id }) { coupon ->
                Card(
                  colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                  modifier = Modifier.fillMaxWidth().testTag("owner_coupon_${coupon.code}"),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column(modifier = Modifier.weight(1f)) {
                      Text(coupon.code, style = PizzoraMonoTitle, color = MaterialTheme.colorScheme.primary)
                      val valueText = if (coupon.discountType == DiscountType.PERCENT_BPS) {
                        "${coupon.discountValue / 100.0}% OFF"
                      } else {
                        "${Money.formatInr(coupon.discountValue)} OFF"
                      }
                      Text(
                        text = "$valueText • Min ${Money.formatInr(coupon.minOrderPaise)} • Uses ${coupon.usageCount}/${coupon.maxUses?.toString() ?: "∞"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                    Switch(
                      checked = coupon.isActive,
                      enabled = !uiState.isSubmitting,
                      onCheckedChange = { onToggleCouponActive(coupon) },
                      modifier = Modifier.minimumInteractiveComponentSize().testTag("coupon_switch_${coupon.code}"),
                    )
                  }
                }
              }
            }
          }

          OwnerOperationsSection.ZONES_SLOTS -> {
            ownerZonesAndSlotsItems(
              uiState = uiState,
              onRefresh = onRefreshZonesAndSlots,
              onSlotFilterChange = onSlotFilterChange,
              onOpenCreateZone = { showCreateZoneDialog = true },
              onOpenEditZone = { editingZone = it },
              onToggleZoneActive = onToggleZoneActive,
              onDeleteZone = onDeleteDeliveryZone,
              onOpenCreateSlot = { showCreateSlotDialog = true },
              onToggleSlotActive = onToggleSlotActive,
              onOpenEditSlot = { editingSlot = it },
              onDeleteSlot = onDeleteScheduledSlot,
            )
          }
        }
      }
    }
  }

  if (showCreateIngredientDialog) {
    CreateIngredientDialog(
      onDismiss = { showCreateIngredientDialog = false },
      onCreate = { name, unit, lowThreshold, initialQty ->
        onCreateIngredient(name, unit, lowThreshold, initialQty)
        showCreateIngredientDialog = false
      },
    )
  }

  val adjTarget = adjustingIngredient
  if (adjTarget != null) {
    AdjustInventoryDialog(
      item = adjTarget,
      onDismiss = { adjustingIngredient = null },
      onSubmit = { delta, reason ->
        onAdjustInventory(adjTarget.ingredientId, delta, reason)
        adjustingIngredient = null
      },
    )
  }

  if (showCreateCouponDialog) {
    CreateCouponDialog(
      onDismiss = { showCreateCouponDialog = false },
      onCreate = { code, type, value, minOrder, maxDisc, startsAt, endsAt ->
        onCreateCoupon(code, type, value, minOrder, maxDisc, startsAt, endsAt)
        showCreateCouponDialog = false
      },
    )
  }

  val detail = uiState.selectedOrderDetail
  if (detail != null) {
    OwnerOrderDetailDialog(
      order = detail,
      isSubmitting = uiState.isSubmitting,
      onDismiss = onDismissOrderDetail,
      onTransition = { nextStatus, reason ->
        onTransitionOrderStatus(detail.id, nextStatus, reason)
      },
      onOpenRefund = { refundingOrder = detail },
    )
  }

  val refundTarget = refundingOrder
  if (refundTarget != null) {
    OwnerRefundDialog(
      order = refundTarget,
      onDismiss = { refundingOrder = null },
      onConfirmRefund = { amountPaise, reason ->
        onRefundOrder(refundTarget.id, amountPaise, reason)
        refundingOrder = null
      },
    )
  }

  if (showCreateZoneDialog || editingZone != null) {
    DeliveryZoneEditorDialog(
      editing = editingZone,
      onDismiss = {
        showCreateZoneDialog = false
        editingZone = null
      },
      onCreate = { pincode, areaName, minOrderPaise, feePaise, etaMins ->
        onCreateDeliveryZone(pincode, areaName, minOrderPaise, feePaise, etaMins)
        showCreateZoneDialog = false
      },
      onUpdate = { zoneId, request ->
        onUpdateDeliveryZone(zoneId, request)
        editingZone = null
      },
    )
  }

  if (showCreateSlotDialog) {
    CreateScheduledSlotDialog(
      defaultDate = uiState.slotFilterDate,
      onDismiss = { showCreateSlotDialog = false },
      onCreate = { slotDate, type, startTime, endTime, cutoffMins, maxCap ->
        onCreateScheduledSlot(slotDate, type, startTime, endTime, cutoffMins, maxCap)
        showCreateSlotDialog = false
      },
    )
  }

  val slotToEdit = editingSlot
  if (slotToEdit != null) {
    EditSlotCapacityDialog(
      slot = slotToEdit,
      onDismiss = { editingSlot = null },
      onSave = { newCapacity ->
        onUpdateSlotCapacity(slotToEdit.id, newCapacity)
        editingSlot = null
      },
    )
  }
  }
}

@Composable
private fun InventoryItemCard(
  item: InventoryItemDto,
  onAdjustClick: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(
      containerColor = if (item.isLowStock) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
    ),
    modifier = Modifier.fillMaxWidth().testTag("owner_inventory_card_${item.ingredientId}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(item.name, style = MaterialTheme.typography.titleMedium)
        Text(
          text = "Available: ${item.availableQty} ${item.unit.name} (On hand ${item.onHandQty}, Reserved ${item.reservedQty})",
          style = PizzoraMonoBody,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      OutlinedButton(
        onClick = onAdjustClick,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("adjust_inventory_${item.ingredientId}"),
      ) {
        Icon(Icons.Filled.Tune, contentDescription = null)
        Spacer(modifier = Modifier.width(6.dp))
        Text("Adjust")
      }
    }
  }
}

@Composable
private fun OwnerOrderSummaryCard(
  order: OrderSummaryDto,
  onInspect: () -> Unit,
) {
  Card(
    onClick = onInspect,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.fillMaxWidth().testTag("owner_order_card_${order.id}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(order.orderNumber, style = PizzoraMonoTitle)
        Text(
          text = "${order.fulfillmentType.name} • ${order.paymentMethod.name} (${order.paymentStatus.name})",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        Text(Money.formatInr(order.totalPaise), style = PizzoraMonoBody, color = MaterialTheme.colorScheme.primary)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
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
private fun CreateIngredientDialog(
  onDismiss: () -> Unit,
  onCreate: (name: String, unit: IngredientUnit, lowThreshold: Long, initialQty: Long) -> Unit,
) {
  var name by rememberSaveable { mutableStateOf("") }
  var unit by rememberSaveable { mutableStateOf(IngredientUnit.GRAMS) }
  var lowThreshold by rememberSaveable { mutableStateOf("500") }
  var initialQty by rememberSaveable { mutableStateOf("2000") }

  OwnerModalOverlay(
    title = "Add Ingredient",
    onDismiss = onDismiss,
    confirmButton = {
      Button(
        onClick = {
          onCreate(
            name,
            unit,
            lowThreshold.toLongOrNull() ?: 0L,
            initialQty.toLongOrNull() ?: 0L,
          )
        },
        modifier = Modifier.testTag("ingredient_submit_button"),
      ) {
        Text("Create")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Ingredient Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("ingredient_name_input"),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IngredientUnit.entries.forEach { u ->
          FilterChip(
            selected = unit == u,
            onClick = { unit = u },
            label = { Text(u.name) },
          )
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = initialQty,
          onValueChange = { initialQty = it.filter(Char::isDigit) },
          label = { Text("Initial Qty") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.weight(1f).testTag("ingredient_initial_input"),
        )
        OutlinedTextField(
          value = lowThreshold,
          onValueChange = { lowThreshold = it.filter(Char::isDigit) },
          label = { Text("Low Threshold") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.weight(1f).testTag("ingredient_threshold_input"),
        )
      }
    }
  }
}

@Composable
private fun AdjustInventoryDialog(
  item: InventoryItemDto,
  onDismiss: () -> Unit,
  onSubmit: (deltaQty: Long, reason: InventoryLedgerReason) -> Unit,
) {
  var deltaInput by rememberSaveable { mutableStateOf("500") }
  var reason by rememberSaveable { mutableStateOf(InventoryLedgerReason.RESTOCK) }

  OwnerModalOverlay(
    title = "Adjust ${item.name}",
    onDismiss = onDismiss,
    confirmButton = {
      Button(
        onClick = {
          val parsed = deltaInput.trim().toLongOrNull() ?: 0L
          onSubmit(parsed, reason)
        },
        modifier = Modifier.testTag("adjust_inventory_submit"),
      ) {
        Text("Apply")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Current available: ${item.availableQty} ${item.unit.name}", style = PizzoraMonoBody)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
          selected = reason == InventoryLedgerReason.RESTOCK,
          onClick = { reason = InventoryLedgerReason.RESTOCK },
          label = { Text("RESTOCK") },
        )
        FilterChip(
          selected = reason == InventoryLedgerReason.MANUAL_ADJUST,
          onClick = { reason = InventoryLedgerReason.MANUAL_ADJUST },
          label = { Text("MANUAL_ADJUST") },
        )
      }
      OutlinedTextField(
        value = deltaInput,
        onValueChange = { deltaInput = it },
        label = { Text("Delta Qty (+restock or -deduct)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("adjust_inventory_delta_input"),
      )
    }
  }
}

@Composable
private fun CreateCouponDialog(
  onDismiss: () -> Unit,
  onCreate: (
    code: String,
    discountType: DiscountType,
    discountValue: Long,
    minOrderPaise: Long,
    maxDiscountPaise: Long?,
    startsAt: String,
    endsAt: String,
  ) -> Unit,
) {
  var code by rememberSaveable { mutableStateOf("") }
  var discountType by rememberSaveable { mutableStateOf(DiscountType.PERCENT_BPS) }
  var valueInput by rememberSaveable { mutableStateOf("1500") }
  var minOrderRupees by rememberSaveable { mutableStateOf("399") }

  OwnerModalOverlay(
    title = "Create Promo Coupon",
    onDismiss = onDismiss,
    confirmButton = {
      Button(
        onClick = {
          val now = java.time.Instant.now()
          onCreate(
            code,
            discountType,
            valueInput.toLongOrNull() ?: 0L,
            (minOrderRupees.toLongOrNull() ?: 0L) * 100L,
            null,
            now.minusSeconds(60).toString(),
            now.plusSeconds(86_400L * 30).toString(),
          )
        },
        modifier = Modifier.testTag("coupon_submit_button"),
      ) {
        Text("Create")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text("Coupon Code (e.g. WOODFIRE15)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("coupon_code_input"),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = discountType == DiscountType.PERCENT_BPS,
          onClick = { discountType = DiscountType.PERCENT_BPS },
          label = { Text("PERCENT_BPS") },
        )
        FilterChip(
          selected = discountType == DiscountType.FLAT_PAISE,
          onClick = { discountType = DiscountType.FLAT_PAISE },
          label = { Text("FLAT_PAISE") },
        )
      }
      OutlinedTextField(
        value = valueInput,
        onValueChange = { valueInput = it.filter(Char::isDigit) },
        label = { Text(if (discountType == DiscountType.PERCENT_BPS) "Percent (bps, 1500=15%)" else "Flat Discount (paise)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("coupon_value_input"),
      )
      OutlinedTextField(
        value = minOrderRupees,
        onValueChange = { minOrderRupees = it.filter(Char::isDigit) },
        label = { Text("Min Order (₹)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("coupon_min_order_input"),
      )
    }
  }
}

@Composable
private fun OwnerOrderDetailDialog(
  order: OrderDetailDto,
  isSubmitting: Boolean,
  onDismiss: () -> Unit,
  onTransition: (OrderStatus, String) -> Unit,
  onOpenRefund: () -> Unit,
) {
  val nextStatus = when (order.status) {
    OrderStatus.PLACED -> OrderStatus.ACCEPTED
    OrderStatus.ACCEPTED -> OrderStatus.PREPARING
    OrderStatus.PREPARING -> OrderStatus.READY
    OrderStatus.READY -> if (order.fulfillmentType == com.pizzora.contracts.FulfillmentType.DELIVERY) {
      OrderStatus.OUT_FOR_DELIVERY
    } else {
      OrderStatus.COMPLETED
    }
    OrderStatus.OUT_FOR_DELIVERY -> OrderStatus.COMPLETED
    else -> null
  }
  val canRefund = order.payment.method == PaymentMethod.RAZORPAY &&
    (order.payment.status == PaymentStatus.CAPTURED || order.payment.status == PaymentStatus.PARTIALLY_REFUNDED) &&
    order.payment.capturedPaise > order.payment.refundedPaise

  OwnerModalOverlay(
    title = "Order ${order.orderNumber}",
    onDismiss = onDismiss,
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("Close") }
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Status: ${order.status.name} • Total: ${Money.formatInr(order.totalPaise)}", style = PizzoraMonoBody)
      Text("Payment: ${order.payment.method.name} / ${order.payment.status.name}", style = MaterialTheme.typography.bodyMedium)
      order.items.forEach { item ->
        Text("${item.quantity}x ${item.productName} (${item.variantSize})", style = MaterialTheme.typography.bodyMedium)
      }
      if (nextStatus != null && RustDomainBridge.canTransitionOrder(order.status, nextStatus, order.fulfillmentType, UserRole.OWNER)) {
        Button(
          onClick = { onTransition(nextStatus, "Owner console advance") },
          enabled = !isSubmitting,
          modifier = Modifier.fillMaxWidth().testTag("owner_advance_order_button"),
        ) {
          Text("Advance to ${nextStatus.name}")
        }
      }
      if (canRefund) {
        OutlinedButton(
          onClick = onOpenRefund,
          enabled = !isSubmitting,
          modifier = Modifier.fillMaxWidth().testTag("owner_refund_order_button"),
        ) {
          Text("Issue Razorpay Refund")
        }
      }
    }
  }
}

@Composable
private fun OwnerRefundDialog(
  order: OrderDetailDto,
  onDismiss: () -> Unit,
  onConfirmRefund: (amountPaise: Long?, reason: String) -> Unit,
) {
  val remainingPaise = (order.payment.capturedPaise - order.payment.refundedPaise).coerceAtLeast(0L)
  var amountPaiseInput by rememberSaveable { mutableStateOf(remainingPaise.toString()) }
  var reason by rememberSaveable { mutableStateOf("Owner customer service refund") }

  OwnerModalOverlay(
    title = "Refund ${order.orderNumber}",
    onDismiss = onDismiss,
    confirmButton = {
      Button(
        onClick = { onConfirmRefund(amountPaiseInput.toLongOrNull(), reason) },
        modifier = Modifier.testTag("refund_submit_button"),
      ) {
        Text("Submit Refund")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Max refundable: ${Money.formatInr(remainingPaise)} ($remainingPaise paise)", style = PizzoraMonoBody)
      OutlinedTextField(
        value = amountPaiseInput,
        onValueChange = { amountPaiseInput = it.filter(Char::isDigit) },
        label = { Text("Refund Amount (paise)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("refund_amount_input"),
      )
      OutlinedTextField(
        value = reason,
        onValueChange = { reason = it },
        label = { Text("Reason") },
        modifier = Modifier.fillMaxWidth().testTag("refund_reason_input"),
      )
    }
  }
}

package com.example.owner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.pizzora.contracts.DeliveryZoneDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.Money
import com.pizzora.contracts.ScheduledSlotDto
import com.pizzora.contracts.UpdateDeliveryZoneRequest
import java.time.LocalDate
import java.time.ZoneId

internal fun LazyListScope.ownerZonesAndSlotsItems(
  uiState: OwnerUiState,
  onRefresh: () -> Unit,
  onSlotFilterChange: (FulfillmentType?, String) -> Unit,
  onOpenCreateZone: () -> Unit,
  onOpenEditZone: (DeliveryZoneDto) -> Unit,
  onToggleZoneActive: (DeliveryZoneDto) -> Unit,
  onDeleteZone: (String) -> Unit,
  onOpenCreateSlot: () -> Unit,
  onToggleSlotActive: (ScheduledSlotDto) -> Unit,
  onOpenEditSlot: (ScheduledSlotDto) -> Unit,
  onDeleteSlot: (String) -> Unit,
) {
  if (uiState.isLoadingZonesAndSlots) {
    item(key = "zones_slots_loading") {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
  }

  item(key = "zones_header") {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Delivery Zones (${uiState.deliveryZones.size})", style = MaterialTheme.typography.titleMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
          onClick = onRefresh,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_refresh_zones_slots_button"),
        ) {
          Icon(Icons.Filled.Refresh, contentDescription = "Refresh Zones and Slots")
        }
        Button(
          onClick = onOpenCreateZone,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_add_zone_button"),
        ) {
          Icon(Icons.Filled.Add, contentDescription = null)
          Spacer(modifier = Modifier.width(4.dp))
          Text("Add Zone")
        }
      }
    }
  }

  if (uiState.deliveryZones.isEmpty()) {
    item(key = "zones_empty") {
      OwnerEmptyCard(
        title = "No delivery zones configured",
        subtitle = "Add serviceable pincodes, minimum order totals, and delivery fees.",
        tag = "owner_zones_empty",
      )
    }
  } else {
    items(uiState.deliveryZones, key = { "zone_${it.id}" }) { zone ->
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().testTag("owner_zone_card_${zone.id}"),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${zone.pincode} • ${zone.areaName}", style = PizzoraMonoTitle, color = MaterialTheme.colorScheme.primary)
            Text(
              text = "Min ${Money.formatInr(zone.minOrderPaise)} • Fee ${Money.formatInr(zone.deliveryFeePaise)} • ETA ${zone.estimatedMins}m",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
              checked = zone.isActive,
              enabled = !uiState.isSubmitting,
              onCheckedChange = { onToggleZoneActive(zone) },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_zone_switch_${zone.pincode}"),
            )
            IconButton(
              onClick = { onOpenEditZone(zone) },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_edit_zone_${zone.id}"),
            ) {
              Icon(Icons.Filled.Edit, contentDescription = "Edit zone")
            }
            IconButton(
              onClick = { onDeleteZone(zone.id) },
              enabled = !uiState.isSubmitting,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_delete_zone_${zone.id}"),
            ) {
              Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete zone", tint = MaterialTheme.colorScheme.error)
            }
          }
        }
      }
    }
  }

  item(key = "slots_header") {
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Scheduled Slots (IST) (${uiState.scheduledSlots.size})", style = MaterialTheme.typography.titleMedium)
      Button(
        onClick = onOpenCreateSlot,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_add_slot_button"),
      ) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(modifier = Modifier.width(4.dp))
        Text("New Slot")
      }
    }
  }

  item(key = "slots_filters") {
    val todayIst = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
    val tomorrowIst = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1).toString()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      item {
        FilterChip(
          selected = uiState.slotFilterType == null,
          onClick = { onSlotFilterChange(null, uiState.slotFilterDate) },
          label = { Text("All Types") },
        )
      }
      item {
        FilterChip(
          selected = uiState.slotFilterType == FulfillmentType.DELIVERY,
          onClick = { onSlotFilterChange(FulfillmentType.DELIVERY, uiState.slotFilterDate) },
          label = { Text("DELIVERY") },
        )
      }
      item {
        FilterChip(
          selected = uiState.slotFilterType == FulfillmentType.PICKUP,
          onClick = { onSlotFilterChange(FulfillmentType.PICKUP, uiState.slotFilterDate) },
          label = { Text("PICKUP") },
        )
      }
      item {
        FilterChip(
          selected = uiState.slotFilterDate == todayIst,
          onClick = { onSlotFilterChange(uiState.slotFilterType, todayIst) },
          label = { Text("Today") },
        )
      }
      item {
        FilterChip(
          selected = uiState.slotFilterDate == tomorrowIst,
          onClick = { onSlotFilterChange(uiState.slotFilterType, tomorrowIst) },
          label = { Text("Tomorrow") },
        )
      }
    }
  }

  if (uiState.scheduledSlots.isEmpty()) {
    item(key = "slots_empty") {
      OwnerEmptyCard(
        title = "No scheduled slots for selected date",
        subtitle = "Create delivery or pickup time windows in Asia/Kolkata IST.",
        tag = "owner_slots_empty",
      )
    }
  } else {
    items(uiState.scheduledSlots, key = { "slot_${it.id}" }) { slot ->
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().testTag("owner_slot_card_${slot.id}"),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
              text = "${slot.slotDate} • ${slot.startTimeIst}-${slot.endTimeIst} IST",
              style = PizzoraMonoBody,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = "${slot.fulfillmentType.name} • Booked ${slot.reservedCount}/${slot.maxCapacity} (${slot.remainingCapacity} left)",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
              checked = slot.isActive,
              enabled = !uiState.isSubmitting,
              onCheckedChange = { onToggleSlotActive(slot) },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_slot_switch_${slot.id}"),
            )
            OutlinedButton(
              onClick = { onOpenEditSlot(slot) },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_edit_slot_${slot.id}"),
            ) {
              Text("Cap")
            }
            IconButton(
              onClick = { onDeleteSlot(slot.id) },
              enabled = !uiState.isSubmitting,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_delete_slot_${slot.id}"),
            ) {
              Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete slot", tint = MaterialTheme.colorScheme.error)
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun DeliveryZoneEditorDialog(
  editing: DeliveryZoneDto?,
  onDismiss: () -> Unit,
  onCreate: (pincode: String, areaName: String, minOrderPaise: Long, deliveryFeePaise: Long, estimatedMins: Int) -> Unit,
  onUpdate: (zoneId: String, request: UpdateDeliveryZoneRequest) -> Unit,
) {
  var pincode by rememberSaveable(editing?.id) { mutableStateOf(editing?.pincode.orEmpty()) }
  var areaName by rememberSaveable(editing?.id) { mutableStateOf(editing?.areaName.orEmpty()) }
  var minOrderRupees by rememberSaveable(editing?.id) { mutableStateOf(((editing?.minOrderPaise ?: 30_000L) / 100L).toString()) }
  var feeRupees by rememberSaveable(editing?.id) { mutableStateOf(((editing?.deliveryFeePaise ?: 4_000L) / 100L).toString()) }
  var etaMins by rememberSaveable(editing?.id) { mutableStateOf((editing?.estimatedMins ?: 30).toString()) }

  OwnerModalOverlay(
    title = if (editing == null) "Add Delivery Zone" else "Edit Zone ${editing.pincode}",
    onDismiss = onDismiss,
    confirmButton = {
      Button(
        onClick = {
          val minPaise = (minOrderRupees.toLongOrNull() ?: 0L) * 100L
          val feePaise = (feeRupees.toLongOrNull() ?: 0L) * 100L
          val eta = etaMins.toIntOrNull() ?: 30
          if (editing == null) {
            onCreate(pincode, areaName, minPaise, feePaise, eta)
          } else {
            onUpdate(
              editing.id,
              UpdateDeliveryZoneRequest(
                areaName = areaName.trim(),
                minOrderPaise = minPaise,
                deliveryFeePaise = feePaise,
                estimatedMins = eta,
              ),
            )
          }
        },
        modifier = Modifier.testTag("zone_submit_button"),
      ) {
        Text(if (editing == null) "Create" else "Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (editing == null) {
        OutlinedTextField(
          value = pincode,
          onValueChange = { v -> pincode = v.filter(Char::isDigit).take(6) },
          label = { Text("6-digit Pincode") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("zone_pincode_input"),
        )
      }
      OutlinedTextField(
        value = areaName,
        onValueChange = { areaName = it },
        label = { Text("Area Name (e.g. Indiranagar)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("zone_area_input"),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = minOrderRupees,
          onValueChange = { minOrderRupees = it.filter(Char::isDigit) },
          label = { Text("Min Order (₹)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.weight(1f).testTag("zone_min_order_input"),
        )
        OutlinedTextField(
          value = feeRupees,
          onValueChange = { feeRupees = it.filter(Char::isDigit) },
          label = { Text("Fee (₹)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.weight(1f).testTag("zone_fee_input"),
        )
        OutlinedTextField(
          value = etaMins,
          onValueChange = { etaMins = it.filter(Char::isDigit) },
          label = { Text("ETA (m)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.weight(1f).testTag("zone_eta_input"),
        )
      }
    }
  }
}

@Composable
internal fun CreateScheduledSlotDialog(
  defaultDate: String,
  onDismiss: () -> Unit,
  onCreate: (
    slotDate: String,
    fulfillmentType: FulfillmentType,
    startTimeIst: String,
    endTimeIst: String,
    cutoffMinutesBeforeStart: Int,
    maxCapacity: Int,
  ) -> Unit,
) {
  var slotDate by rememberSaveable { mutableStateOf(defaultDate) }
  var fulfillmentType by rememberSaveable { mutableStateOf(FulfillmentType.DELIVERY) }
  var startTime by rememberSaveable { mutableStateOf("19:00") }
  var endTime by rememberSaveable { mutableStateOf("20:00") }
  var cutoffMins by rememberSaveable { mutableStateOf("30") }
  var capacity by rememberSaveable { mutableStateOf("10") }

  OwnerModalOverlay(
    title = "New Scheduled Slot (IST)",
    onDismiss = onDismiss,
    confirmButton = {
      Button(
        onClick = {
          onCreate(
            slotDate,
            fulfillmentType,
            startTime,
            endTime,
            cutoffMins.toIntOrNull() ?: 30,
            capacity.toIntOrNull() ?: 1,
          )
        },
        modifier = Modifier.testTag("slot_submit_button"),
      ) {
        Text("Create")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = fulfillmentType == FulfillmentType.DELIVERY,
          onClick = { fulfillmentType = FulfillmentType.DELIVERY },
          label = { Text("DELIVERY") },
          modifier = Modifier.testTag("slot_type_delivery"),
        )
        FilterChip(
          selected = fulfillmentType == FulfillmentType.PICKUP,
          onClick = { fulfillmentType = FulfillmentType.PICKUP },
          label = { Text("PICKUP") },
          modifier = Modifier.testTag("slot_type_pickup"),
        )
      }
      OutlinedTextField(
        value = slotDate,
        onValueChange = { slotDate = it },
        label = { Text("Date (YYYY-MM-DD, IST)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("slot_date_input"),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = startTime,
          onValueChange = { startTime = it },
          label = { Text("Start (HH:mm)") },
          singleLine = true,
          modifier = Modifier.weight(1f).testTag("slot_start_input"),
        )
        OutlinedTextField(
          value = endTime,
          onValueChange = { endTime = it },
          label = { Text("End (HH:mm)") },
          singleLine = true,
          modifier = Modifier.weight(1f).testTag("slot_end_input"),
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = cutoffMins,
          onValueChange = { cutoffMins = it.filter(Char::isDigit) },
          label = { Text("Cutoff (mins before)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.weight(1f).testTag("slot_cutoff_mins_input"),
        )
        OutlinedTextField(
          value = capacity,
          onValueChange = { capacity = it.filter(Char::isDigit) },
          label = { Text("Max Capacity") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.weight(1f).testTag("slot_capacity_input"),
        )
      }
    }
  }
}

@Composable
internal fun EditSlotCapacityDialog(
  slot: ScheduledSlotDto,
  onDismiss: () -> Unit,
  onSave: (Int) -> Unit,
) {
  var capacityInput by rememberSaveable(slot.id) { mutableStateOf(slot.maxCapacity.toString()) }

  OwnerModalOverlay(
    title = "Edit Capacity (${slot.startTimeIst}-${slot.endTimeIst} IST)",
    onDismiss = onDismiss,
    confirmButton = {
      Button(
        onClick = { onSave(capacityInput.toIntOrNull() ?: slot.maxCapacity) },
        modifier = Modifier.testTag("slot_edit_submit_button"),
      ) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Currently reserved: ${slot.reservedCount}", style = PizzoraMonoBody)
      OutlinedTextField(
        value = capacityInput,
        onValueChange = { capacityInput = it.filter(Char::isDigit) },
        label = { Text("Max Capacity") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("slot_edit_capacity_input"),
      )
    }
  }
}

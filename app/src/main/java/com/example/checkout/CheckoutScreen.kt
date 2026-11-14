package com.example.checkout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.Money
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.ScheduledSlotDto
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun CheckoutScreen(
  uiState: CheckoutUiState,
  quoteState: CheckoutQuoteUiState?,
  itemCount: Int,
  subtotalPaise: Long,
  onFulfillmentTypeChange: (FulfillmentType) -> Unit,
  onSelectAddress: (String) -> Unit,
  onOpenAddAddress: () -> Unit,
  onDismissAddAddress: () -> Unit,
  onSaveAddress: (label: String, line1: String, pincode: String, city: String) -> Unit,
  onSelectPaymentMethod: (PaymentMethod) -> Unit,
  onPlaceOrder: () -> Unit,
  onBackToCart: () -> Unit,
  modifier: Modifier = Modifier,
  onCheckAddressZone: () -> Unit = {},
  onScheduleTypeChange: (ScheduleType) -> Unit = {},
  onSlotDateChange: (String) -> Unit = {},
  onSelectSlot: (String) -> Unit = {},
  onRefreshSlots: () -> Unit = {},
  onSaveAddressWithDetails: ((
    label: String,
    line1: String,
    line2: String?,
    landmark: String?,
    pincode: String,
    city: String,
    isDefault: Boolean,
  ) -> Unit)? = null,
) {
  BackHandler(onBack = onBackToCart)

  val fulfillmentType = quoteState?.fulfillmentType ?: FulfillmentType.DELIVERY
  val payableTotalPaise = quoteState?.quote?.breakdown?.totalPaise ?: subtotalPaise

  if (uiState.isAddAddressDialogOpen) {
    AddAddressDialog(
      isSaving = uiState.isSavingAddress,
      error = uiState.addressError,
      onSave = { label, line1, line2, landmark, pincode, city, isDefault ->
        if (onSaveAddressWithDetails != null) {
          onSaveAddressWithDetails(label, line1, line2, landmark, pincode, city, isDefault)
        } else {
          onSaveAddress(label, line1, pincode, city)
        }
      },
      onDismiss = onDismissAddAddress,
    )
  }

  Column(
    modifier = modifier.fillMaxSize().testTag("checkout_screen"),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 720.dp)
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      IconButton(
        onClick = onBackToCart,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("checkout_back_button"),
      ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
      }
      Column {
        Text(stringResource(R.string.checkout_title), style = MaterialTheme.typography.titleLarge)
        Text(
          text = "$itemCount item(s)",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (uiState.isLoadingAddresses || uiState.isPlacingOrder || uiState.isLoadingSlots) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    LazyColumn(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .widthIn(max = 720.dp)
        .testTag("checkout_lazy_column"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!uiState.checkoutError.isNullOrBlank()) {
        item(key = "checkout_error") {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag("checkout_error_banner"),
          ) {
            Text(
              text = uiState.checkoutError,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(14.dp),
            )
          }
        }
      }

      item(key = "checkout_fulfillment") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(stringResource(R.string.checkout_fulfillment_title), style = MaterialTheme.typography.titleMedium)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              FilterChip(
                selected = fulfillmentType == FulfillmentType.DELIVERY,
                onClick = { onFulfillmentTypeChange(FulfillmentType.DELIVERY) },
                leadingIcon = { Icon(Icons.Filled.DeliveryDining, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text(stringResource(R.string.fulfillment_delivery)) },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("checkout_delivery_chip"),
              )
              FilterChip(
                selected = fulfillmentType == FulfillmentType.PICKUP,
                onClick = { onFulfillmentTypeChange(FulfillmentType.PICKUP) },
                leadingIcon = { Icon(Icons.Filled.Storefront, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text(stringResource(R.string.fulfillment_pickup)) },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("checkout_pickup_chip"),
              )
            }
          }
        }
      }

      if (fulfillmentType == FulfillmentType.DELIVERY) {
        item(key = "checkout_addresses") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().testTag("checkout_address_section"),
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
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                  Text(stringResource(R.string.checkout_delivery_address), style = MaterialTheme.typography.titleMedium)
                }
                TextButton(
                  onClick = onOpenAddAddress,
                  modifier = Modifier.minimumInteractiveComponentSize().testTag("add_address_button"),
                ) {
                  Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.size(4.dp))
                  Text(stringResource(R.string.action_add_address))
                }
              }

              if (uiState.addresses.isEmpty()) {
                Text(
                  text = stringResource(R.string.checkout_no_addresses),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.testTag("no_addresses_text"),
                )
              } else {
                uiState.addresses.forEach { address ->
                  AddressOptionRow(
                    address = address,
                    selected = uiState.selectedAddressId == address.id,
                    onSelect = { onSelectAddress(address.id) },
                  )
                }

                if (uiState.selectedAddressId != null) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    TextButton(
                      onClick = onCheckAddressZone,
                      enabled = !uiState.isCheckingAddressZone,
                      modifier = Modifier.minimumInteractiveComponentSize().testTag("check_selected_address_zone_button"),
                    ) {
                      Text(stringResource(R.string.checkout_verify_pincode_action))
                    }
                  }
                  uiState.selectedAddressZoneCheck?.let { check ->
                    Surface(
                      color = if (check.isServiceable) {
                        MaterialTheme.colorScheme.secondaryContainer
                      } else {
                        MaterialTheme.colorScheme.errorContainer
                      },
                      shape = MaterialTheme.shapes.small,
                      modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {}
                        .testTag("address_zone_status_banner"),
                    ) {
                      Text(
                        text = check.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (check.isServiceable) {
                          MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                          MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(10.dp),
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }

      item(key = "checkout_schedule") {
        ScheduleSelectionCard(
          uiState = uiState,
          onScheduleTypeChange = onScheduleTypeChange,
          onSlotDateChange = onSlotDateChange,
          onSelectSlot = onSelectSlot,
          onRefreshSlots = onRefreshSlots,
        )
      }

      item(key = "checkout_payment_method") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
          modifier = Modifier.fillMaxWidth().testTag("checkout_payment_section"),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(stringResource(R.string.checkout_payment_method), style = MaterialTheme.typography.titleMedium)
            PaymentOptionRow(
              title = stringResource(R.string.payment_cod_title),
              subtitle = stringResource(R.string.payment_cod_subtitle),
              icon = Icons.Filled.Payments,
              selected = uiState.paymentMethod == PaymentMethod.COD,
              onClick = { onSelectPaymentMethod(PaymentMethod.COD) },
              tag = "payment_method_cod",
            )
            PaymentOptionRow(
              title = stringResource(R.string.payment_razorpay_title),
              subtitle = stringResource(R.string.payment_razorpay_subtitle),
              icon = Icons.Filled.CreditCard,
              selected = uiState.paymentMethod == PaymentMethod.RAZORPAY,
              onClick = { onSelectPaymentMethod(PaymentMethod.RAZORPAY) },
              tag = "payment_method_razorpay",
            )
          }
        }
      }

      quoteState?.quote?.let { quote ->
        item(key = "checkout_breakdown") {
          PriceBreakdownCard(
            breakdown = quote.breakdown,
            appliedCouponCode = quote.appliedCoupon?.code,
            isLoading = quoteState.isLoadingQuote,
          )
        }
      }
    }

    Surface(
      tonalElevation = 4.dp,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .widthIn(max = 720.dp)
          .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text(
            text = stringResource(R.string.label_payable_total),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = Money.formatInr(payableTotalPaise),
            style = PizzoraMonoTitle,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("checkout_payable_total_text"),
          )
        }
        Button(
          onClick = onPlaceOrder,
          enabled = !uiState.isPlacingOrder,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("place_order_button"),
        ) {
          if (uiState.isPlacingOrder) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(modifier = Modifier.size(8.dp))
          }
          Text(stringResource(R.string.action_place_order))
        }
      }
    }
  }
}

@Composable
private fun ScheduleSelectionCard(
  uiState: CheckoutUiState,
  onScheduleTypeChange: (ScheduleType) -> Unit,
  onSlotDateChange: (String) -> Unit,
  onSelectSlot: (String) -> Unit,
  onRefreshSlots: () -> Unit,
) {
  val todayIst = remember { LocalDate.now(ZoneId.of("Asia/Kolkata")).toString() }
  val tomorrowIst = remember { LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1).toString() }

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    modifier = Modifier.fillMaxWidth().testTag("checkout_schedule_section"),
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
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Text(stringResource(R.string.checkout_schedule_title), style = MaterialTheme.typography.titleMedium)
        }
        if (uiState.scheduleType == ScheduleType.SCHEDULED) {
          IconButton(
            onClick = onRefreshSlots,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("refresh_slots_button"),
          ) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        FilterChip(
          selected = uiState.scheduleType == ScheduleType.ASAP,
          onClick = { onScheduleTypeChange(ScheduleType.ASAP) },
          leadingIcon = { Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp)) },
          label = { Text(stringResource(R.string.schedule_asap)) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("schedule_asap_chip"),
        )
        FilterChip(
          selected = uiState.scheduleType == ScheduleType.SCHEDULED,
          onClick = { onScheduleTypeChange(ScheduleType.SCHEDULED) },
          leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(18.dp)) },
          label = { Text(stringResource(R.string.schedule_later)) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("schedule_scheduled_chip"),
        )
      }

      if (uiState.scheduleType == ScheduleType.SCHEDULED) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilterChip(
            selected = uiState.selectedSlotDate == todayIst,
            onClick = { onSlotDateChange(todayIst) },
            label = { Text("Today (IST)") },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("slot_date_today_chip"),
          )
          FilterChip(
            selected = uiState.selectedSlotDate == tomorrowIst,
            onClick = { onSlotDateChange(tomorrowIst) },
            label = { Text("Tomorrow (IST)") },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("slot_date_tomorrow_chip"),
          )
        }

        if (!uiState.slotsError.isNullOrBlank()) {
          Text(
            text = uiState.slotsError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("slots_error_text"),
          )
        } else if (!uiState.isLoadingSlots && uiState.availableSlots.isEmpty()) {
          Text(
            text = stringResource(R.string.checkout_no_slots_for_date),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("no_slots_text"),
          )
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            uiState.availableSlots.forEach { slot ->
              ScheduledSlotRow(
                slot = slot,
                selected = uiState.selectedSlotId == slot.id,
                onSelect = { onSelectSlot(slot.id) },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ScheduledSlotRow(
  slot: ScheduledSlotDto,
  selected: Boolean,
  onSelect: () -> Unit,
) {
  val containerColor = when {
    !slot.isBookable -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
  }
  val borderColor = when {
    !slot.isBookable -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    selected -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
  }

  Surface(
    color = containerColor,
    shape = MaterialTheme.shapes.medium,
    border = BorderStroke(1.dp, borderColor),
    modifier = Modifier
      .fillMaxWidth()
      .minimumInteractiveComponentSize()
      .selectable(
        selected = selected,
        enabled = slot.isBookable,
        role = Role.RadioButton,
        onClick = onSelect,
      )
      .testTag("slot_card_${slot.id}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      RadioButton(selected = selected, onClick = null, enabled = slot.isBookable)
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "${slot.startTimeIst} - ${slot.endTimeIst} IST",
          style = PizzoraMonoBody,
          color = if (slot.isBookable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = if (slot.isBookable) {
            "${slot.remainingCapacity} of ${slot.maxCapacity} slots left"
          } else {
            slot.unavailableReason ?: "Unavailable"
          },
          style = MaterialTheme.typography.bodySmall,
          color = if (slot.isBookable) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

@Composable
private fun AddressOptionRow(
  address: AddressDto,
  selected: Boolean,
  onSelect: () -> Unit,
) {
  Surface(
    color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    shape = MaterialTheme.shapes.medium,
    border = BorderStroke(
      width = 1.dp,
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    ),
    modifier = Modifier
      .fillMaxWidth()
      .minimumInteractiveComponentSize()
      .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
      .testTag("address_card_${address.id}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      RadioButton(selected = selected, onClick = null)
      Column(modifier = Modifier.weight(1f)) {
        Text(text = "${address.label} • ${address.pincode}", style = MaterialTheme.typography.titleSmall)
        val details = buildString {
          append(address.line1)
          if (!address.line2.isNullOrBlank()) append(", ${address.line2}")
          if (!address.landmark.isNullOrBlank()) append(" (${address.landmark})")
          append(", ${address.city}")
        }
        Text(
          text = details,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun PaymentOptionRow(
  title: String,
  subtitle: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  selected: Boolean,
  onClick: () -> Unit,
  tag: String,
) {
  Surface(
    color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    shape = MaterialTheme.shapes.medium,
    border = BorderStroke(
      width = 1.dp,
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    ),
    modifier = Modifier
      .fillMaxWidth()
      .minimumInteractiveComponentSize()
      .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
      .testTag(tag),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      RadioButton(selected = selected, onClick = null)
      Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
      Column(modifier = Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun AddAddressDialog(
  isSaving: Boolean,
  error: String?,
  onSave: (
    label: String,
    line1: String,
    line2: String?,
    landmark: String?,
    pincode: String,
    city: String,
    isDefault: Boolean,
  ) -> Unit,
  onDismiss: () -> Unit,
) {
  var label by remember { mutableStateOf("Home") }
  var line1 by remember { mutableStateOf("") }
  var line2 by remember { mutableStateOf("") }
  var landmark by remember { mutableStateOf("") }
  var pincode by remember { mutableStateOf("") }
  var city by remember { mutableStateOf("Bengaluru") }
  var isDefault by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.address_dialog_title)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (!error.isNullOrBlank()) {
          Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("address_dialog_error"),
          )
        }
        OutlinedTextField(
          value = label,
          onValueChange = { label = it },
          label = { Text(stringResource(R.string.address_label_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("address_label_input"),
        )
        OutlinedTextField(
          value = line1,
          onValueChange = { line1 = it },
          label = { Text(stringResource(R.string.address_line1_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("address_line1_input"),
        )
        OutlinedTextField(
          value = line2,
          onValueChange = { line2 = it },
          label = { Text(stringResource(R.string.address_line2_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("address_line2_input"),
        )
        OutlinedTextField(
          value = landmark,
          onValueChange = { landmark = it },
          label = { Text(stringResource(R.string.address_landmark_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("address_landmark_input"),
        )
        OutlinedTextField(
          value = pincode,
          onValueChange = { v -> pincode = v.filter { it.isDigit() }.take(6) },
          label = { Text(stringResource(R.string.address_pincode_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("address_pincode_input"),
        )
        OutlinedTextField(
          value = city,
          onValueChange = { city = it },
          label = { Text(stringResource(R.string.address_city_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("address_city_input"),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(stringResource(R.string.address_set_default_label), style = MaterialTheme.typography.bodyMedium)
          Switch(
            checked = isDefault,
            onCheckedChange = { isDefault = it },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("address_default_switch"),
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(label, line1, line2, landmark, pincode, city, isDefault) },
        enabled = !isSaving,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("save_address_button"),
      ) {
        Text(stringResource(R.string.action_save_address))
      }
    },
    dismissButton = {
      OutlinedButton(
        onClick = onDismiss,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("cancel_address_dialog_button"),
      ) {
        Text(stringResource(R.string.action_close))
      }
    },
  )
}

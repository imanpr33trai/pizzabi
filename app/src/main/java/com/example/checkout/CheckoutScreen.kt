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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Payments
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
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.PizzoraMonoTitle
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.Money
import com.pizzora.contracts.PaymentMethod

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
) {
  BackHandler(onBack = onBackToCart)

  val fulfillmentType = quoteState?.fulfillmentType ?: FulfillmentType.DELIVERY
  val payableTotalPaise = quoteState?.quote?.breakdown?.totalPaise ?: subtotalPaise

  if (uiState.isAddAddressDialogOpen) {
    AddAddressDialog(
      isSaving = uiState.isSavingAddress,
      error = uiState.addressError,
      onSave = onSaveAddress,
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

    if (uiState.isLoadingAddresses || uiState.isPlacingOrder) {
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
            Text("Fulfillment", style = MaterialTheme.typography.titleMedium)
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
              }
            }
          }
        }
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
        Text(
          text = "${address.line1}, ${address.city}",
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
  onSave: (label: String, line1: String, pincode: String, city: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var label by remember { mutableStateOf("Home") }
  var line1 by remember { mutableStateOf("") }
  var pincode by remember { mutableStateOf("") }
  var city by remember { mutableStateOf("Bengaluru") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.address_dialog_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(label, line1, pincode, city) },
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

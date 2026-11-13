package com.example.cart

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.checkout.CheckoutQuoteUiState
import com.example.checkout.CouponPickerSheet
import com.example.checkout.PriceBreakdownCard
import com.example.core.database.CartItemWithToppings
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.Money

@Composable
fun CartScreen(
  uiState: CartUiState,
  onUpdateQuantity: (String, Int) -> Unit,
  onRemoveItem: (String) -> Unit,
  onClearCart: () -> Unit,
  onValidateAndSync: () -> Unit,
  onBrowseMenu: () -> Unit,
  modifier: Modifier = Modifier,
  quoteState: CheckoutQuoteUiState? = null,
  onFulfillmentTypeChange: (FulfillmentType) -> Unit = {},
  onPincodeInputChange: (String) -> Unit = {},
  onCheckPincode: () -> Unit = {},
  onCouponInputChange: (String) -> Unit = {},
  onApplyCoupon: (String) -> Unit = {},
  onRemoveCoupon: () -> Unit = {},
  onOpenCouponPicker: () -> Unit = {},
  onDismissCouponPicker: () -> Unit = {},
  onProceedToCheckout: (() -> Unit)? = null,
) {
  if (quoteState?.isCouponSheetOpen == true) {
    CouponPickerSheet(
      coupons = quoteState.availableCoupons,
      isLoading = quoteState.isLoadingCoupons,
      onSelectCoupon = onApplyCoupon,
      onDismiss = onDismissCouponPicker,
    )
  }

  Column(
    modifier = modifier.fillMaxSize().testTag("cart_screen"),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (uiState.isSyncing) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    LazyColumn(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .widthIn(max = 720.dp)
        .testTag("cart_lazy_column"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (uiState.isOffline) {
        item(key = "cart_offline_banner") {
          Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag("cart_offline_banner"),
          ) {
            Row(
              modifier = Modifier.padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
              Text(
                text = stringResource(R.string.cart_offline_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
              )
            }
          }
        }
      }

      if (uiState.hasUnavailableItems) {
        item(key = "cart_unavailable_banner") {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag("cart_unavailable_banner"),
          ) {
            Row(
              modifier = Modifier.padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
              Text(
                text = stringResource(R.string.cart_unavailable_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
            }
          }
        }
      }

      if (!uiState.statusMessage.isNullOrBlank()) {
        item(key = "cart_status_message") {
          Text(
            text = uiState.statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("cart_status_message"),
          )
        }
      }

      if (uiState.items.isEmpty()) {
        item(key = "empty_cart") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().testTag("cart_empty_state"),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.ShoppingCart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
              )
              Text(stringResource(R.string.cart_empty_title), style = MaterialTheme.typography.titleLarge)
              Text(
                text = stringResource(R.string.cart_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Button(
                onClick = onBrowseMenu,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("cart_browse_menu_button"),
              ) {
                Text(stringResource(R.string.cart_browse_menu))
              }
            }
          }
        }
      } else {
        items(uiState.items, key = { it.item.id }) { entry ->
          CartItemCard(
            entry = entry,
            onIncrement = { onUpdateQuantity(entry.item.id, entry.item.quantity + 1) },
            onDecrement = { onUpdateQuantity(entry.item.id, entry.item.quantity - 1) },
            onRemove = { onRemoveItem(entry.item.id) },
          )
        }

        if (quoteState != null) {
          item(key = "fulfillment_zone_card") {
            FulfillmentAndPincodeCard(
              quoteState = quoteState,
              onFulfillmentTypeChange = onFulfillmentTypeChange,
              onPincodeInputChange = onPincodeInputChange,
              onCheckPincode = onCheckPincode,
            )
          }

          item(key = "promo_coupon_card") {
            PromoCouponCard(
              quoteState = quoteState,
              onCouponInputChange = onCouponInputChange,
              onApplyCoupon = onApplyCoupon,
              onRemoveCoupon = onRemoveCoupon,
              onOpenCouponPicker = onOpenCouponPicker,
            )
          }

          if (!quoteState.quoteError.isNullOrBlank()) {
            item(key = "quote_error_banner") {
              Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("quote_error_text"),
              ) {
                Text(
                  text = quoteState.quoteError,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  modifier = Modifier.padding(12.dp),
                )
              }
            }
          }

          quoteState.quote?.let { quote ->
            item(key = "price_breakdown_card") {
              PriceBreakdownCard(
                breakdown = quote.breakdown,
                appliedCouponCode = quote.appliedCoupon?.code,
                isLoading = quoteState.isLoadingQuote,
              )
            }
          }
        }
      }
    }

    if (uiState.items.isNotEmpty()) {
      Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp)
            .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Item Subtotal (${uiState.totalItemCount})",
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              text = Money.formatInr(uiState.subtotalPaise),
              style = PizzoraMonoTitle,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.testTag("cart_subtotal_text"),
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            OutlinedButton(
              onClick = onClearCart,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("clear_cart_button"),
            ) {
              Text(stringResource(R.string.action_clear))
            }
            Button(
              onClick = onValidateAndSync,
              modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("validate_cart_button"),
            ) {
              Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.size(6.dp))
              Text(stringResource(R.string.cart_validate_sync))
            }
            if (onProceedToCheckout != null) {
              Button(
                onClick = onProceedToCheckout,
                enabled = !uiState.hasUnavailableItems && !uiState.isSyncing,
                modifier = Modifier
                  .weight(1f)
                  .minimumInteractiveComponentSize()
                  .testTag("proceed_to_checkout_button"),
              ) {
                Icon(Icons.Filled.ShoppingBag, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text(stringResource(R.string.action_proceed_checkout))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun FulfillmentAndPincodeCard(
  quoteState: CheckoutQuoteUiState,
  onFulfillmentTypeChange: (FulfillmentType) -> Unit,
  onPincodeInputChange: (String) -> Unit,
  onCheckPincode: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    modifier = Modifier.fillMaxWidth().testTag("fulfillment_card"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        FilterChip(
          selected = quoteState.fulfillmentType == FulfillmentType.DELIVERY,
          onClick = { onFulfillmentTypeChange(FulfillmentType.DELIVERY) },
          label = { Text(stringResource(R.string.fulfillment_delivery)) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("fulfillment_delivery_chip"),
        )
        FilterChip(
          selected = quoteState.fulfillmentType == FulfillmentType.PICKUP,
          onClick = { onFulfillmentTypeChange(FulfillmentType.PICKUP) },
          label = { Text(stringResource(R.string.fulfillment_pickup)) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("fulfillment_pickup_chip"),
        )
      }

      if (quoteState.fulfillmentType == FulfillmentType.DELIVERY) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = quoteState.pincodeInput,
            onValueChange = onPincodeInputChange,
            label = { Text(stringResource(R.string.pincode_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("delivery_pincode_input"),
          )
          OutlinedButton(
            onClick = onCheckPincode,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("check_pincode_button"),
          ) {
            Text(stringResource(R.string.action_check))
          }
        }
        quoteState.quote?.deliveryZone?.let { zone ->
          Text(
            text = "${zone.areaName} (${zone.pincode}) • ETA ${zone.estimatedMins} mins",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.testTag("delivery_zone_summary_text"),
          )
        }
      }
    }
  }
}

@Composable
private fun PromoCouponCard(
  quoteState: CheckoutQuoteUiState,
  onCouponInputChange: (String) -> Unit,
  onApplyCoupon: (String) -> Unit,
  onRemoveCoupon: () -> Unit,
  onOpenCouponPicker: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    modifier = Modifier.fillMaxWidth().testTag("promo_coupon_card"),
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
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.LocalOffer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Text(stringResource(R.string.promo_code_title), style = MaterialTheme.typography.titleMedium)
        }
        TextButton(
          onClick = onOpenCouponPicker,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("open_coupon_picker_button"),
        ) {
          Text(stringResource(R.string.action_view_offers))
        }
      }

      val applied = quoteState.appliedCouponCode
      if (!applied.isNullOrBlank()) {
        Surface(
          color = MaterialTheme.colorScheme.secondaryContainer,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth().testTag("applied_coupon_banner"),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp),
              )
              Text(
                text = applied,
                style = PizzoraMonoTitle,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.testTag("applied_coupon_code_text"),
              )
            }
            IconButton(
              onClick = onRemoveCoupon,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("remove_coupon_button"),
            ) {
              Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_remove))
            }
          }
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = quoteState.couponInput,
            onValueChange = onCouponInputChange,
            placeholder = { Text(stringResource(R.string.promo_code_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("coupon_code_input"),
          )
          Button(
            onClick = { onApplyCoupon(quoteState.couponInput) },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("apply_coupon_button"),
          ) {
            Text(stringResource(R.string.action_apply))
          }
        }
      }

      if (!quoteState.couponError.isNullOrBlank()) {
        Text(
          text = quoteState.couponError,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("coupon_error_text"),
        )
      }
    }
  }
}

@Composable
private fun CartItemCard(
  entry: CartItemWithToppings,
  onIncrement: () -> Unit,
  onDecrement: () -> Unit,
  onRemove: () -> Unit,
) {
  val item = entry.item
  val isAvailable = item.isAvailable
  Card(
    colors = CardDefaults.cardColors(
      containerColor = if (isAvailable) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
    ),
    border = BorderStroke(
      width = 1.dp,
      color = if (isAvailable) MaterialTheme.colorScheme.outline.copy(alpha = 0.45f) else MaterialTheme.colorScheme.error,
    ),
    modifier = Modifier.fillMaxWidth().testTag("cart_item_${item.id}"),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(item.productName, style = MaterialTheme.typography.titleMedium)
          Text(
            text = "${item.sizeCode.lowercase().replaceFirstChar { it.uppercase() }} • ${item.crustName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (entry.toppings.isNotEmpty()) {
            Text(
              text = "Toppings: ${entry.toppings.joinToString(", ") { it.toppingName }}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        IconButton(
          onClick = onRemove,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("cart_remove_${item.id}"),
        ) {
          Icon(
            Icons.Filled.DeleteOutline,
            contentDescription = stringResource(R.string.action_remove),
            tint = MaterialTheme.colorScheme.error,
          )
        }
      }

      if (!isAvailable) {
        Text(
          text = item.unavailableReason ?: stringResource(R.string.cart_item_unavailable_fallback),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("cart_item_issue_${item.id}"),
        )
      }

      HorizontalDivider()

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = onDecrement,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("cart_qty_minus_${item.id}"),
          ) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease quantity")
          }
          Text(
            text = item.quantity.toString(),
            style = PizzoraMonoBody,
            modifier = Modifier.padding(horizontal = 6.dp).testTag("cart_qty_value_${item.id}"),
          )
          IconButton(
            onClick = onIncrement,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("cart_qty_plus_${item.id}"),
          ) {
            Icon(Icons.Filled.Add, contentDescription = "Increase quantity")
          }
        }

        Column(horizontalAlignment = Alignment.End) {
          Text(
            text = "${Money.formatInr(item.unitPricePaise)} each",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = Money.formatInr(item.unitPricePaise * item.quantity),
            style = PizzoraMonoTitle,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
    }
  }
}

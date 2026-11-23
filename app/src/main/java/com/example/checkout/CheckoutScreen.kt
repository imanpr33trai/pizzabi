package com.example.checkout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.domain.DomainBridge
import com.example.core.domain.RustDomainBridge
import com.example.ui.theme.LocalPizzoraSemanticColors
import com.example.ui.components.OrderDisplayFormatters
import com.example.ui.components.PizzoraStickyActionDock
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.example.ui.theme.PizzoraSemanticColors
import com.pizzora.contracts.AddressDto
import com.example.core.database.CartItemWithToppings
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.Money
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.ScheduledSlotDto
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
  uiState: CheckoutUiState,
  quoteState: CheckoutQuoteUiState?,
  cartItems: List<CartItemWithToppings> = emptyList(),
  cartSubtotalPaise: Long = 0L,
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
  onRetryQuote: () -> Unit = {},
) {
  BackHandler(onBack = onBackToCart)

  val fulfillmentType = quoteState?.fulfillmentType ?: FulfillmentType.DELIVERY
  val payableTotalPaise = quoteState?.quote?.breakdown?.totalPaise
  val subtotalPaise = quoteState?.quote?.breakdown?.subtotalPaise

  val isQuoteFresh = quoteState?.isQuoteFresh == true
  val isLoadingQuote = quoteState?.isLoadingQuote == true
  val quoteError = quoteState?.quoteError
  val priceDriftNotice = quoteState?.priceDriftNotice
  val postOrderPriceDrift = uiState.priceDriftNotice
  val hasUnavailableItems = quoteState?.hasUnavailableItems == true
  val isAcceptingOrders = quoteState?.quote?.isAcceptingOrders == true
  val selectedAddress = uiState.addresses.firstOrNull { it.id == uiState.selectedAddressId }
  val zoneCheck = quoteState?.quote?.deliveryZone
  val isCheckingZone = quoteState?.isCheckingZone == true
  val isCheckingAddressZone = uiState.isCheckingAddressZone

  // Show quote error banner when quote has an error
  val showQuoteErrorBanner = quoteError != null

  // Readiness checks - computed directly in composable (avoids stringResource in derivedStateOf)
  val readinessNotice: String? = if (cartItems.isEmpty()) {
    stringResource(R.string.checkout_readiness_empty_cart)
  } else if (!isQuoteFresh) {
    if (isLoadingQuote) stringResource(R.string.checkout_readiness_waiting_quote)
    else if (quoteError != null) stringResource(R.string.checkout_readiness_quote_error)
    else stringResource(R.string.checkout_readiness_stale_quote)
  } else if (!isAcceptingOrders) {
    stringResource(R.string.checkout_readiness_store_paused)
  } else if (hasUnavailableItems) {
    stringResource(R.string.checkout_readiness_unavailable_items)
  } else if (fulfillmentType == FulfillmentType.DELIVERY) {
    if (uiState.selectedAddressId == null) {
      stringResource(R.string.checkout_readiness_no_address)
    } else if (zoneCheck == null || selectedAddress == null || selectedAddress.pincode != zoneCheck.pincode) {
      stringResource(R.string.checkout_readiness_zone_mismatch)
    } else if (subtotalPaise != null && zoneCheck.minOrderPaise > subtotalPaise) {
      val shortfall = zoneCheck.minOrderPaise - subtotalPaise
      stringResource(R.string.checkout_readiness_min_order_shortfall, Money.formatInr(shortfall), zoneCheck.pincode)
    } else if (uiState.selectedAddressZoneCheck?.isServiceable == false) {
      stringResource(R.string.checkout_readiness_unserviceable_zone)
    } else {
      null
    }
  } else {
    null
  }

  val scheduleReadinessNotice: String? = if (uiState.scheduleType == ScheduleType.SCHEDULED && uiState.selectedSlotId == null && !uiState.isLoadingSlots) {
    stringResource(R.string.checkout_readiness_no_slot)
  } else {
    null
  }

  val zoneCheckReadinessNotice = if (isCheckingZone || isCheckingAddressZone) {
    stringResource(R.string.checkout_readiness_checking_zone)
  } else {
    null
  }
  val combinedReadinessNotice = when {
    readinessNotice != null -> readinessNotice
    zoneCheckReadinessNotice != null -> zoneCheckReadinessNotice
    else -> scheduleReadinessNotice
  }

  val hasAuthoritativeTotal = isQuoteFresh && payableTotalPaise != null
  val canPlaceOrder = cartItems.isNotEmpty()
    && hasAuthoritativeTotal
    && isAcceptingOrders
    && !hasUnavailableItems
    && (fulfillmentType == FulfillmentType.PICKUP || (uiState.selectedAddressId != null && zoneCheck != null && selectedAddress != null && selectedAddress.pincode == zoneCheck.pincode && subtotalPaise != null && subtotalPaise >= zoneCheck.minOrderPaise && uiState.selectedAddressZoneCheck?.isServiceable != false))
    && (uiState.scheduleType == ScheduleType.ASAP || uiState.selectedSlotId != null)
    && !uiState.isPlacingOrder
    && !isCheckingZone
    && !isCheckingAddressZone

  // Never present a stale/local estimate as a submittable total on the CTA.
  val ctaText = when {
    !hasAuthoritativeTotal && uiState.paymentMethod == PaymentMethod.RAZORPAY ->
      stringResource(R.string.checkout_cta_continue_payment_pending)
    !hasAuthoritativeTotal -> stringResource(R.string.checkout_cta_place_order_pending)
    uiState.paymentMethod == PaymentMethod.RAZORPAY ->
      stringResource(R.string.checkout_cta_continue_payment, Money.formatInr(payableTotalPaise!!))
    else -> stringResource(R.string.checkout_cta_place_order, Money.formatInr(payableTotalPaise!!))
  }

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

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .semantics { isTraversalGroup = true }
      .testTag("checkout_screen"),
  ) {
    val tabletBreakpoint = 840.dp
    val minimumTabletHeight = 480.dp
    val isTablet = maxWidth >= tabletBreakpoint && maxHeight >= minimumTabletHeight
    val shellMaxWidth = maxWidth

    Column(
      modifier = Modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Pinned header (64dp)
      Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
          .fillMaxWidth()
          .widthIn(max = 720.dp)
          .padding(horizontal = 8.dp, vertical = 6.dp),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .widthIn(max = 720.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          IconButton(
            onClick = onBackToCart,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("checkout_back_button"),
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.checkout_title), style = MaterialTheme.typography.headlineSmall)
            Text(
              text = stringResource(R.string.checkout_item_count, cartItems.size),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      if (uiState.isLoadingAddresses || uiState.isPlacingOrder || uiState.isLoadingSlots) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp))
      }

      val contentModifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .widthIn(max = if (isTablet) shellMaxWidth else 720.dp)

      if (isTablet) {
        // Tablet: 60/40 two-pane layout
        Row(
          modifier = contentModifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          // Left pane: form (60%)
          Box(
            modifier = Modifier
              .weight(0.6f, fill = false)
              .fillMaxHeight()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 16.dp, vertical = 10.dp)
              .testTag("checkout_lazy_column"),
          ) {
            CheckoutFormContent(
              uiState = uiState,
              quoteState = quoteState,
              cartItems = cartItems,
              fulfillmentType = fulfillmentType,
              selectedAddress = selectedAddress,
              zoneCheck = zoneCheck,
              isQuoteFresh = isQuoteFresh,
              isLoadingQuote = isLoadingQuote,
              quoteError = quoteError,
              priceDriftNotice = priceDriftNotice,
              postOrderPriceDrift = postOrderPriceDrift,
              hasUnavailableItems = hasUnavailableItems,
              isAcceptingOrders = isAcceptingOrders,
              selectedAddressZoneCheck = uiState.selectedAddressZoneCheck,
              isCheckingZone = isCheckingZone,
              isCheckingAddressZone = isCheckingAddressZone,
              onFulfillmentTypeChange = onFulfillmentTypeChange,
              onSelectAddress = onSelectAddress,
              onOpenAddAddress = onOpenAddAddress,
              onCheckAddressZone = onCheckAddressZone,
              onScheduleTypeChange = onScheduleTypeChange,
              onSlotDateChange = onSlotDateChange,
              onSelectSlot = onSelectSlot,
              onRefreshSlots = onRefreshSlots,
              onRetryQuote = onRetryQuote,
              onSelectPaymentMethod = onSelectPaymentMethod,
            )
          }

          // Right pane: price breakdown + dock (40%)
          Box(
            modifier = Modifier
              .weight(0.4f, fill = false)
              .fillMaxHeight()
              .padding(vertical = 10.dp),
          ) {
            Column(
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              // Price breakdown card
              CheckoutPriceSummary(
                quoteState = quoteState,
                cartSubtotalPaise = cartSubtotalPaise,
                cartHasItems = cartItems.isNotEmpty(),
              )

              // Spacer to push dock to bottom
              Spacer(modifier = Modifier.weight(1f))

              // Sticky action dock with readiness notice in topSlot
              PizzoraStickyActionDock(
                modifier = Modifier.fillMaxWidth(),
                applyNavigationBarsPadding = true,
                verticalPadding = 16.dp,
                topSlot = {
                  if (combinedReadinessNotice != null) {
                    val semanticColors = LocalPizzoraSemanticColors.current
                    Surface(
                      color = semanticColors.warningContainer,
                      shape = RoundedCornerShape(8.dp),
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("checkout_readiness_notice"),
                    ) {
                      Text(
                        text = combinedReadinessNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = semanticColors.onWarningContainer,
                        modifier = Modifier.padding(12.dp),
                      )
                    }
                  }
                },
                content = {
                  Button(
                    onClick = onPlaceOrder,
                    enabled = canPlaceOrder && !uiState.isPlacingOrder,
                    modifier = Modifier
                      .fillMaxWidth()
                      .minimumInteractiveComponentSize()
                      .testTag("place_order_button"),
                  ) {
                    if (uiState.isPlacingOrder) {
                      CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                      )
                      Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text(ctaText)
                  }
                },
              )
            }
          }
        }
      } else {
        // Phone: single column + sticky dock
        Column(
          modifier = contentModifier,
        ) {
          LazyColumn(
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth()
              .testTag("checkout_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            item {
              CheckoutFormContent(
                uiState = uiState,
                quoteState = quoteState,
                cartItems = cartItems,
                fulfillmentType = fulfillmentType,
                selectedAddress = selectedAddress,
                zoneCheck = zoneCheck,
                isQuoteFresh = isQuoteFresh,
                isLoadingQuote = isLoadingQuote,
                quoteError = quoteError,
                priceDriftNotice = priceDriftNotice,
                postOrderPriceDrift = postOrderPriceDrift,
                hasUnavailableItems = hasUnavailableItems,
                isAcceptingOrders = isAcceptingOrders,
                selectedAddressZoneCheck = uiState.selectedAddressZoneCheck,
                isCheckingZone = isCheckingZone,
                isCheckingAddressZone = isCheckingAddressZone,
                onFulfillmentTypeChange = onFulfillmentTypeChange,
                onSelectAddress = onSelectAddress,
                onOpenAddAddress = onOpenAddAddress,
                onCheckAddressZone = onCheckAddressZone,
                onScheduleTypeChange = onScheduleTypeChange,
                onSlotDateChange = onSlotDateChange,
                onSelectSlot = onSelectSlot,
                onRefreshSlots = onRefreshSlots,
                onRetryQuote = onRetryQuote,
                onSelectPaymentMethod = onSelectPaymentMethod,
              )
            }

            // Price breakdown at bottom of list
            item(key = "checkout_breakdown") {
              CheckoutPriceSummary(
                quoteState = quoteState,
                cartSubtotalPaise = cartSubtotalPaise,
                cartHasItems = cartItems.isNotEmpty(),
              )
            }
          }

          // Readiness notice bar directly above dock
          if (combinedReadinessNotice != null) {
            val semanticColors = LocalPizzoraSemanticColors.current
            Surface(
              color = semanticColors.warningContainer,
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("checkout_readiness_notice"),
            ) {
              Text(
                text = combinedReadinessNotice,
                style = MaterialTheme.typography.bodySmall,
                color = semanticColors.onWarningContainer,
                modifier = Modifier.padding(12.dp),
              )
            }
          }

          // Sticky action dock
          PizzoraStickyActionDock(
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
            applyNavigationBarsPadding = true,
            verticalPadding = 16.dp,
            topSlot = null,
            content = {
              Button(
                onClick = onPlaceOrder,
                enabled = canPlaceOrder && !uiState.isPlacingOrder,
                modifier = Modifier
                  .fillMaxWidth()
                  .minimumInteractiveComponentSize()
                  .testTag("place_order_button"),
              ) {
                if (uiState.isPlacingOrder) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                  )
                  Spacer(modifier = Modifier.size(8.dp))
                }
                Text(ctaText)
              }
            },
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutFormContent(
  uiState: CheckoutUiState,
  quoteState: CheckoutQuoteUiState?,
  cartItems: List<CartItemWithToppings>,
  fulfillmentType: FulfillmentType,
  selectedAddress: AddressDto?,
  zoneCheck: com.pizzora.contracts.DeliveryZoneQuoteDto?,
  isQuoteFresh: Boolean,
  isLoadingQuote: Boolean,
  quoteError: String?,
  priceDriftNotice: String?,
  postOrderPriceDrift: String?,
  hasUnavailableItems: Boolean,
  isAcceptingOrders: Boolean,
  selectedAddressZoneCheck: com.pizzora.contracts.DeliveryZoneCheckResponse?,
  isCheckingZone: Boolean,
  isCheckingAddressZone: Boolean,
  onFulfillmentTypeChange: (FulfillmentType) -> Unit,
  onSelectAddress: (String) -> Unit,
  onOpenAddAddress: () -> Unit,
  onCheckAddressZone: () -> Unit,
  onScheduleTypeChange: (ScheduleType) -> Unit,
  onSlotDateChange: (String) -> Unit,
  onSelectSlot: (String) -> Unit,
  onRefreshSlots: () -> Unit,
  onRetryQuote: () -> Unit,
  onSelectPaymentMethod: (PaymentMethod) -> Unit,
) {
  // Collapsible Order Items Review card (falls back to item count in legacy hosts)
  OrderItemsReviewCard(
    cartItems = cartItems,
    modifier = Modifier.fillMaxWidth(),
  )

  // Submission / address errors preserved from the original checkout surface
  if (!uiState.checkoutError.isNullOrBlank()) {
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

  // Quote error banner with Retry
  if (quoteError != null) {
    val semanticColors = LocalPizzoraSemanticColors.current
    Surface(
      color = semanticColors.warningContainer,
      shape = MaterialTheme.shapes.medium,
      modifier = Modifier.fillMaxWidth().testTag("checkout_quote_error_banner"),
    ) {
      Row(
        modifier = Modifier.padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = quoteError,
          style = MaterialTheme.typography.bodyMedium,
          color = semanticColors.onWarningContainer,
        )
        TextButton(
          onClick = onRetryQuote,
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("checkout_retry_quote_button"),
        ) {
          Text(stringResource(R.string.action_retry_quote))
        }
      }
    }
  }

  // Price drift notices
  (priceDriftNotice ?: postOrderPriceDrift)?.let { drift ->
    val semanticColors = LocalPizzoraSemanticColors.current
    Surface(
      color = semanticColors.warningContainer,
      shape = MaterialTheme.shapes.medium,
      modifier = Modifier.fillMaxWidth().testTag("checkout_price_drift_notice"),
    ) {
      Text(
        text = drift,
        style = MaterialTheme.typography.bodySmall,
        color = semanticColors.onWarningContainer,
        modifier = Modifier.padding(14.dp),
      )
    }
  }

  // Fulfillment toggle
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

  // Delivery address section
  if (fulfillmentType == FulfillmentType.DELIVERY) {
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
                  .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
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

  // Schedule section with SegmentedButtonRow
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

      // ASAP / Scheduled segmented control (radio semantics)
      val todayIst = OrderDisplayFormatters.istDateIso(0)
      val tomorrowIst = OrderDisplayFormatters.istDateIso(1)

      SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
      ) {
        SegmentedButton(
          selected = uiState.scheduleType == ScheduleType.ASAP,
          onClick = { onScheduleTypeChange(ScheduleType.ASAP) },
          shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
          icon = { Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp)) },
          modifier = Modifier
            .weight(1f)
            .minimumInteractiveComponentSize()
            .semantics { role = Role.RadioButton }
            .testTag("schedule_asap_chip"),
        ) {
          Text(stringResource(R.string.schedule_asap))
        }
        SegmentedButton(
          selected = uiState.scheduleType == ScheduleType.SCHEDULED,
          onClick = { onScheduleTypeChange(ScheduleType.SCHEDULED) },
          shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
          icon = { Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(18.dp)) },
          modifier = Modifier
            .weight(1f)
            .minimumInteractiveComponentSize()
            .semantics { role = Role.RadioButton }
            .testTag("schedule_scheduled_chip"),
        ) {
          Text(stringResource(R.string.schedule_later))
        }
      }

      if (uiState.scheduleType == ScheduleType.SCHEDULED) {
        // Today / Tomorrow segmented control (radio semantics)
        SingleChoiceSegmentedButtonRow(
          modifier = Modifier.fillMaxWidth(),
        ) {
          SegmentedButton(
            selected = uiState.selectedSlotDate == todayIst,
            onClick = { onSlotDateChange(todayIst) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier
              .weight(1f)
              .minimumInteractiveComponentSize()
              .semantics { role = Role.RadioButton }
              .testTag("slot_date_today_chip"),
          ) {
            Text(stringResource(R.string.slot_date_today))
          }
          SegmentedButton(
            selected = uiState.selectedSlotDate == tomorrowIst,
            onClick = { onSlotDateChange(tomorrowIst) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier
              .weight(1f)
              .minimumInteractiveComponentSize()
              .semantics { role = Role.RadioButton }
              .testTag("slot_date_tomorrow_chip"),
          ) {
            Text(stringResource(R.string.slot_date_tomorrow))
          }
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

  // Payment method section
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderItemsReviewCard(
  cartItems: List<CartItemWithToppings>,
  modifier: Modifier = Modifier,
) {
  var expanded by rememberSaveable { mutableStateOf(true) }
  var showAll by rememberSaveable { mutableStateOf(false) }

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    modifier = modifier.testTag("checkout_order_review_card"),
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
        Text(
          text = stringResource(R.string.checkout_order_review_title, cartItems.size),
          style = MaterialTheme.typography.titleMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (expanded && cartItems.size > 2) {
            val toggleLabel = if (showAll) {
              stringResource(R.string.action_show_less)
            } else {
              stringResource(R.string.action_view_all_items, cartItems.size)
            }
            TextButton(
              onClick = { showAll = !showAll },
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("checkout_order_review_toggle"),
            ) {
              Icon(
                imageVector = if (showAll) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = toggleLabel,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.size(4.dp))
              Text(toggleLabel)
            }
          }
          IconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("checkout_order_review_collapse_button"),
          ) {
            Icon(
              imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
              contentDescription = stringResource(R.string.checkout_order_review_toggle_description),
            )
          }
        }
      }

      if (expanded) {
        Column(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          if (cartItems.isEmpty()) {
            Text(
              text = stringResource(R.string.checkout_order_review_empty, cartItems.size),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.testTag("checkout_order_review_empty"),
            )
          } else {
            val itemsToShow = if (showAll) cartItems else cartItems.take(2)
            itemsToShow.forEach { entry ->
              OrderReviewItemRow(
                entry = entry,
                modifier = Modifier.fillMaxWidth().testTag("checkout_order_review_item_${entry.item.id}"),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun OrderReviewItemRow(
  entry: CartItemWithToppings,
  modifier: Modifier = Modifier,
  domainBridge: DomainBridge = RustDomainBridge,
) {
  val lineTotal = domainBridge.calculateLineTotalPaise(entry.item.unitPricePaise, entry.item.quantity)
  val toppingNames = entry.toppings.map { it.toppingName }.joinToString(", ")
  val sizeCrust = "${entry.item.sizeCode} · ${entry.item.crustName}"
  val details = buildString {
    append(sizeCrust)
    if (toppingNames.isNotBlank()) {
      append(" + $toppingNames")
    }
  }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = entry.item.productName,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = details,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = stringResource(R.string.checkout_order_review_qty_price, entry.item.quantity, Money.formatInr(lineTotal)),
        style = PizzoraMonoBody,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledSlotRow(
  slot: ScheduledSlotDto,
  selected: Boolean,
  onSelect: () -> Unit,
) {
  val semanticColors = LocalPizzoraSemanticColors.current
  val containerColor = when {
    !slot.isBookable -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    selected -> semanticColors.selectedSurface
    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
  }
  val borderColor = when {
    !slot.isBookable -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    selected -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
  }
  val borderWidth = if (selected) 1.5.dp else 1.dp

  Surface(
    color = containerColor,
    shape = MaterialTheme.shapes.medium,
    border = BorderStroke(width = borderWidth, color = borderColor),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressOptionRow(
  address: AddressDto,
  selected: Boolean,
  onSelect: () -> Unit,
) {
  val semanticColors = LocalPizzoraSemanticColors.current
  Surface(
    color = if (selected) semanticColors.selectedSurface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    shape = MaterialTheme.shapes.medium,
    border = BorderStroke(
      width = if (selected) 1.5.dp else 1.dp,
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
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
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(text = "${address.label} • ${address.pincode}", style = MaterialTheme.typography.titleSmall)
          if (address.isDefault) {
            Surface(
              color = MaterialTheme.colorScheme.secondaryContainer,
              shape = MaterialTheme.shapes.small,
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp).testTag("address_default_badge"),
            ) {
              Text(
                text = stringResource(R.string.address_default_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
              )
            }
          }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentOptionRow(
  title: String,
  subtitle: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  selected: Boolean,
  onClick: () -> Unit,
  tag: String,
) {
  val semanticColors = LocalPizzoraSemanticColors.current
  Surface(
    color = if (selected) semanticColors.selectedSurface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    shape = MaterialTheme.shapes.medium,
    border = BorderStroke(
      width = if (selected) 1.5.dp else 1.dp,
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
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
fun AddAddressDialog(
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
  var label by rememberSaveable { mutableStateOf("Home") }
  var line1 by rememberSaveable { mutableStateOf("") }
  var line2 by rememberSaveable { mutableStateOf("") }
  var landmark by rememberSaveable { mutableStateOf("") }
  var pincode by rememberSaveable { mutableStateOf("") }
  var city by rememberSaveable { mutableStateOf("Bengaluru") }
  var isDefault by rememberSaveable { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current

  // Pre-fetch validation error strings for use in derivedStateOf
  val strLabelRequired = stringResource(R.string.address_error_label_required)
  val strLine1Required = stringResource(R.string.address_error_line1_required)
  val strCityRequired = stringResource(R.string.address_error_city_required)
  val strPincodeRequired = stringResource(R.string.address_error_pincode_required)
  val strPincodeInvalid = stringResource(R.string.address_error_pincode_invalid)

  val labelError = remember(strLabelRequired) { derivedStateOf { if (label.trim().isEmpty()) strLabelRequired else null } }
  val line1Error = remember(strLine1Required) { derivedStateOf { if (line1.trim().isEmpty()) strLine1Required else null } }
  val cityError = remember(strCityRequired) { derivedStateOf { if (city.trim().isEmpty()) strCityRequired else null } }
  val pincodeError = remember(strPincodeRequired, strPincodeInvalid) {
    derivedStateOf {
      val pin = pincode.trim()
      when {
        pin.isEmpty() -> strPincodeRequired
        !pin.matches(Regex("^[1-9][0-9]{5}$")) -> strPincodeInvalid
        else -> null
      }
    }
  }
  val allFieldsValid = labelError.value == null && line1Error.value == null &&
    cityError.value == null && pincodeError.value == null

  fun submitAddress() {
    if (!allFieldsValid || isSaving) return
    onSave(
      label.trim(),
      line1.trim(),
      line2.takeIf { it.isNotBlank() }?.trim(),
      landmark.takeIf { it.isNotBlank() }?.trim(),
      pincode.trim(),
      city.trim(),
      isDefault,
    )
  }

  val nextFieldActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
  val doneFieldActions = KeyboardActions(
    onDone = {
      keyboardController?.hide()
      submitAddress()
    },
  )

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.address_dialog_title)) },
    text = {
      Column(
        modifier = Modifier
          .verticalScroll(rememberScrollState())
          .padding(bottom = 8.dp)
          .imePadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (!error.isNullOrBlank()) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().testTag("address_dialog_error"),
          ) {
            Text(
              text = error,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(10.dp),
            )
          }
        }
        OutlinedTextField(
          value = label,
          onValueChange = { label = it },
          label = { Text(stringResource(R.string.address_label_hint)) },
          singleLine = true,
          isError = labelError.value != null,
          supportingText = { if (labelError.value != null) Text(labelError.value!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) },
          modifier = Modifier.fillMaxWidth().testTag("address_label_input"),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
          keyboardActions = nextFieldActions,
        )
        OutlinedTextField(
          value = line1,
          onValueChange = { line1 = it },
          label = { Text(stringResource(R.string.address_line1_hint)) },
          singleLine = true,
          isError = line1Error.value != null,
          supportingText = { if (line1Error.value != null) Text(line1Error.value!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) },
          modifier = Modifier.fillMaxWidth().testTag("address_line1_input"),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
          keyboardActions = nextFieldActions,
        )
        OutlinedTextField(
          value = line2,
          onValueChange = { line2 = it },
          label = { Text(stringResource(R.string.address_line2_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("address_line2_input"),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
          keyboardActions = nextFieldActions,
        )
        OutlinedTextField(
          value = landmark,
          onValueChange = { landmark = it },
          label = { Text(stringResource(R.string.address_landmark_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("address_landmark_input"),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
          keyboardActions = nextFieldActions,
        )
        OutlinedTextField(
          value = pincode,
          onValueChange = { pincode = it.filter { it.isDigit() }.take(6) },
          label = { Text(stringResource(R.string.address_pincode_hint)) },
          singleLine = true,
          isError = pincodeError.value != null,
          supportingText = { if (pincodeError.value != null) Text(pincodeError.value!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) },
          modifier = Modifier.fillMaxWidth().testTag("address_pincode_input"),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
          keyboardActions = nextFieldActions,
        )
        OutlinedTextField(
          value = city,
          onValueChange = { city = it },
          label = { Text(stringResource(R.string.address_city_hint)) },
          singleLine = true,
          isError = cityError.value != null,
          supportingText = { if (cityError.value != null) Text(cityError.value!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) },
          modifier = Modifier.fillMaxWidth().testTag("address_city_input"),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
          keyboardActions = doneFieldActions,
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
        onClick = ::submitAddress,
        enabled = allFieldsValid && !isSaving,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckoutPriceSummary(
  quoteState: CheckoutQuoteUiState?,
  cartSubtotalPaise: Long,
  cartHasItems: Boolean,
  modifier: Modifier = Modifier,
) {
  val quote = quoteState?.quote
  val isQuoteStale = quoteState?.isQuoteStale == true
  val isQuoteLoading = quoteState?.isLoadingQuote == true
  val totalStatusText = when {
    quoteState?.quoteError != null -> stringResource(R.string.checkout_total_unavailable)
    isQuoteLoading || isQuoteStale -> stringResource(R.string.checkout_updating_total)
    else -> null
  }

  if (quote != null) {
    Column(
      modifier = modifier
        .fillMaxWidth()
        .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
      CheckoutTotalStatusLine(totalStatusText)
      CheckoutPriceBreakdownCard(
        breakdown = quote.breakdown,
        appliedCouponCode = quote.appliedCoupon?.code,
        isLoading = false,
        modifier = Modifier
          .fillMaxWidth()
          .alpha(if (isQuoteStale || isQuoteLoading) 0.6f else 1f)
          .testTag("price_breakdown_card"),
      )
    }
  } else if (cartHasItems) {
    Column(
      modifier = modifier
        .fillMaxWidth()
        .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
      CheckoutTotalStatusLine(totalStatusText)
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().testTag("price_breakdown_card"),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stringResource(R.string.checkout_est_subtotal),
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            text = Money.formatInr(cartSubtotalPaise),
            style = PizzoraMonoTitle,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("checkout_est_subtotal_text"),
          )
        }
      }
    }
  }
}

@Composable
private fun CheckoutTotalStatusLine(totalStatusText: String?) {
  if (totalStatusText != null) {
    Text(
      text = totalStatusText,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp, vertical = 2.dp)
        .testTag("checkout_updating_total_text"),
    )
  }
}

@Composable
fun CheckoutPriceBreakdownCard(
  breakdown: com.pizzora.contracts.PriceBreakdownDto,
  appliedCouponCode: String?,
  isLoading: Boolean = false,
  modifier: Modifier = Modifier,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    modifier = modifier
      .fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(stringResource(R.string.price_breakdown_title), style = MaterialTheme.typography.titleMedium)
      }

      if (isLoading) {
        Row(
          modifier = Modifier.fillMaxWidth().height(56.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      } else {
        CheckoutBreakdownRow(
          label = stringResource(R.string.label_subtotal),
          value = Money.formatInr(breakdown.subtotalPaise),
          isTotal = false,
        )
        if (appliedCouponCode != null && breakdown.discountPaise > 0) {
          CheckoutBreakdownRow(
            label = stringResource(R.string.label_coupon_discount, appliedCouponCode),
            value = "-${Money.formatInr(breakdown.discountPaise)}",
            valueColor = MaterialTheme.colorScheme.secondary,
            isTotal = false,
          )
        }
        if (breakdown.deliveryFeePaise > 0) {
          CheckoutBreakdownRow(
            label = stringResource(R.string.label_delivery_fee),
            value = Money.formatInr(breakdown.deliveryFeePaise),
            isTotal = false,
            valueModifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
          )
        } else if (breakdown.deliveryFeePaise == 0L) {
          CheckoutBreakdownRow(
            label = stringResource(R.string.label_delivery_fee),
            value = stringResource(R.string.checkout_delivery_free),
            valueColor = MaterialTheme.colorScheme.secondary,
            isTotal = false,
            valueModifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
          )
        }
        CheckoutBreakdownRow(
          label = stringResource(R.string.checkout_tax_label, breakdown.gstBps / 100),
          value = Money.formatInr(breakdown.taxPaise),
          isTotal = false,
        )
        HorizontalDivider(
          modifier = Modifier.padding(vertical = 4.dp),
          color = MaterialTheme.colorScheme.outline,
        )
        CheckoutBreakdownRow(
          label = stringResource(R.string.label_payable_total),
          value = Money.formatInr(breakdown.totalPaise),
          isTotal = true,
          valueModifier = Modifier.testTag("checkout_payable_total_text"),
        )
      }
    }
  }
}

@Composable
fun CheckoutBreakdownRow(
  label: String,
  value: String,
  valueColor: Color = MaterialTheme.colorScheme.onSurface,
  isTotal: Boolean = false,
  valueModifier: Modifier = Modifier,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = if (isTotal) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
      color = if (isTotal) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = if (isTotal) PizzoraMonoTitle else PizzoraMonoBody,
      color = if (isTotal) MaterialTheme.colorScheme.primary else valueColor,
      modifier = valueModifier,
    )
  }
}
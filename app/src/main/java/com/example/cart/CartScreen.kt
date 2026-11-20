package com.example.cart

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.checkout.CheckoutQuoteUiState
import com.example.checkout.CouponPickerSheet
import com.example.checkout.PriceBreakdownCard
import com.example.core.database.CartItemWithToppings
import com.example.ui.components.LocalReducedMotion
import com.example.ui.components.PizzoraBannerTone
import com.example.ui.components.PizzoraConfirmationDialog
import com.example.ui.components.PizzoraEmptyStateCard
import com.example.ui.components.PizzoraStatusBanner
import com.example.ui.components.PizzoraStickyActionDock
import com.example.ui.theme.LocalPizzoraSemanticColors
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.Money

@OptIn(ExperimentalLayoutApi::class)
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
  onEditItem: ((CartItemWithToppings) -> Unit)? = null,
  focusRestoreItemId: String? = null,
  onFocusRestored: () -> Unit = {},
  onRetryCoupons: () -> Unit = onOpenCouponPicker,
  applyDockNavigationBarsPadding: Boolean = true,
  shellWindowWidth: Dp? = null,
  shellWindowHeight: Dp? = null,
) {
  val density = LocalDensity.current
  val reducedMotion = LocalReducedMotion.current
  var showClearCartConfirm by rememberSaveable { mutableStateOf(false) }
  val itemFocusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }

  LaunchedEffect(focusRestoreItemId, uiState.items) {
    if (focusRestoreItemId != null) {
      if (uiState.items.any { it.item.id == focusRestoreItemId }) {
        withFrameNanos { }
        runCatching { itemFocusRequesters[focusRestoreItemId]?.requestFocus() }
      }
      onFocusRestored()
    }
  }

  if (quoteState?.isCouponSheetOpen == true) {
    CouponPickerSheet(
      coupons = quoteState.availableCoupons,
      isLoading = quoteState.isLoadingCoupons,
      errorMessage = quoteState.couponSheetError,
      onRetry = onRetryCoupons,
      onSelectCoupon = onApplyCoupon,
      onDismiss = onDismissCouponPicker,
    )
  }

  val isImeVisible = WindowInsets.isImeVisible || WindowInsets.ime.getBottom(density) > 0

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .testTag("cart_screen"),
  ) {
    val effectiveWidth = shellWindowWidth ?: maxWidth
    val effectiveHeight = shellWindowHeight ?: maxHeight
    val isTabletTwoPane = uiState.items.isNotEmpty() &&
      effectiveWidth >= 840.dp &&
      effectiveHeight >= 480.dp
    val maxSingleColWidth = if (effectiveWidth >= 600.dp) 640.dp else 720.dp

    Column(
      modifier = Modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (uiState.isSyncing) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }

      if (uiState.items.isEmpty()) {
        LazyColumn(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .widthIn(max = maxSingleColWidth)
            .imePadding()
            .testTag("cart_lazy_column"),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (uiState.isOffline) {
            item(key = "cart_offline_banner") {
              CartOfflineBanner()
            }
          }
          if (!uiState.statusMessage.isNullOrBlank()) {
            item(key = "cart_status_message") {
              CartStatusMessage(message = uiState.statusMessage)
            }
          }
          item(key = "empty_cart") {
            PizzoraEmptyStateCard(
              title = stringResource(R.string.cart_empty_title),
              subtitle = stringResource(R.string.cart_empty_subtitle),
              icon = Icons.Filled.ShoppingCart,
              actionLabel = stringResource(R.string.cart_browse_menu),
              onAction = onBrowseMenu,
              actionTestTag = "cart_browse_menu_button",
              modifier = Modifier.testTag("cart_empty_state"),
            )
          }
        }
      } else if (isTabletTwoPane) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
          // Left 60%: Cart header + banners + item cards
          Column(
            modifier = Modifier
              .weight(0.6f)
              .fillMaxHeight()
              .testTag("cart_left_pane"),
          ) {
            CartItemsUtilityHeader(
              totalItemCount = uiState.totalItemCount,
              isSyncing = uiState.isSyncing,
              onValidateAndSync = onValidateAndSync,
              onRequestClearCart = { showClearCartConfirm = true },
            )
            LazyColumn(
              modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
                .testTag("cart_lazy_column"),
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              if (uiState.isOffline) {
                item(key = "cart_offline_banner") {
                  CartOfflineBanner()
                }
              }
              if (uiState.hasUnavailableItems) {
                item(key = "cart_unavailable_banner") {
                  CartUnavailableBanner()
                }
              }
              if (!uiState.statusMessage.isNullOrBlank()) {
                item(key = "cart_status_message") {
                  CartStatusMessage(message = uiState.statusMessage)
                }
              }
              items(uiState.items, key = { it.item.id }) { entry ->
                val focusRequester = itemFocusRequesters.getOrPut(entry.item.id) { FocusRequester() }
                CartItemCard(
                  entry = entry,
                  cardFocusRequester = focusRequester,
                  onEdit = onEditItem?.let { callback -> { callback(entry) } },
                  onIncrement = { onUpdateQuantity(entry.item.id, entry.item.quantity + 1) },
                  onDecrement = { onUpdateQuantity(entry.item.id, entry.item.quantity - 1) },
                  onRemove = { onRemoveItem(entry.item.id) },
                )
              }
            }
          }

          VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)

          // Right 40%: Fulfillment + Coupon + Breakdown + Dock
          Column(
            modifier = Modifier
              .weight(0.4f)
              .fillMaxHeight()
              .testTag("cart_right_pane"),
          ) {
            Column(
              modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              if (quoteState != null) {
                FulfillmentAndPincodeCard(
                  quoteState = quoteState,
                  onFulfillmentTypeChange = onFulfillmentTypeChange,
                  onPincodeInputChange = onPincodeInputChange,
                  onCheckPincode = onCheckPincode,
                )
                PromoCouponCard(
                  quoteState = quoteState,
                  onCouponInputChange = onCouponInputChange,
                  onApplyCoupon = onApplyCoupon,
                  onRemoveCoupon = onRemoveCoupon,
                  onOpenCouponPicker = onOpenCouponPicker,
                )
                if (!quoteState.quoteError.isNullOrBlank()) {
                  QuoteErrorBanner(message = quoteState.quoteError)
                }
                quoteState.quote?.let { quote ->
                  PriceBreakdownCard(
                    breakdown = quote.breakdown,
                    appliedCouponCode = quote.appliedCoupon?.code,
                    isLoading = quoteState.isLoadingQuote,
                  )
                }
              }
            }

            AnimatedVisibility(
              visible = !isImeVisible,
              enter = if (reducedMotion) fadeIn() else fadeIn() + expandVertically(),
              exit = if (reducedMotion) fadeOut() else fadeOut() + shrinkVertically(),
            ) {
              CartStickyBottomDock(
                uiState = uiState,
                quoteState = quoteState,
                onProceedToCheckout = onProceedToCheckout,
                applyNavigationBarsPadding = applyDockNavigationBarsPadding,
                compactWidth = this@BoxWithConstraints.maxWidth * 0.4f < 360.dp,
              )
            }
          }
        }
      } else {
        // Single-column layout (< 840dp width or < 480dp height)
        Column(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .widthIn(max = maxSingleColWidth),
        ) {
          CartItemsUtilityHeader(
            totalItemCount = uiState.totalItemCount,
            isSyncing = uiState.isSyncing,
            onValidateAndSync = onValidateAndSync,
            onRequestClearCart = { showClearCartConfirm = true },
          )

          LazyColumn(
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth()
              .imePadding()
              .testTag("cart_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            if (uiState.isOffline) {
              item(key = "cart_offline_banner") {
                CartOfflineBanner()
              }
            }
            if (uiState.hasUnavailableItems) {
              item(key = "cart_unavailable_banner") {
                CartUnavailableBanner()
              }
            }
            if (!uiState.statusMessage.isNullOrBlank()) {
              item(key = "cart_status_message") {
                CartStatusMessage(message = uiState.statusMessage)
              }
            }

            items(uiState.items, key = { it.item.id }) { entry ->
              val focusRequester = itemFocusRequesters.getOrPut(entry.item.id) { FocusRequester() }
              CartItemCard(
                entry = entry,
                cardFocusRequester = focusRequester,
                onEdit = onEditItem?.let { callback -> { callback(entry) } },
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
                  QuoteErrorBanner(message = quoteState.quoteError)
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

        AnimatedVisibility(
          visible = !isImeVisible,
          enter = if (reducedMotion) fadeIn() else fadeIn() + expandVertically(),
          exit = if (reducedMotion) fadeOut() else fadeOut() + shrinkVertically(),
        ) {
          CartStickyBottomDock(
            uiState = uiState,
            quoteState = quoteState,
            onProceedToCheckout = onProceedToCheckout,
            applyNavigationBarsPadding = applyDockNavigationBarsPadding,
            compactWidth = this@BoxWithConstraints.maxWidth < 360.dp,
            maxContentWidth = maxSingleColWidth,
          )
        }
      }
    }

    if (showClearCartConfirm) {
      PizzoraConfirmationDialog(
        title = stringResource(R.string.cart_clear_dialog_title),
        body = stringResource(R.string.cart_clear_dialog_body),
        confirmLabel = stringResource(R.string.cart_clear_dialog_confirm),
        onConfirm = {
          showClearCartConfirm = false
          onClearCart()
        },
        onDismiss = { showClearCartConfirm = false },
        isDestructive = true,
        confirmTestTag = "confirm_clear_cart_button",
        dismissTestTag = "cancel_clear_cart_button",
        modifier = Modifier.testTag("clear_cart_confirm_dialog"),
      )
    }
  }
}

@Composable
private fun CartItemsUtilityHeader(
  totalItemCount: Int,
  isSyncing: Boolean,
  onValidateAndSync: () -> Unit,
  onRequestClearCart: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.cart_items_header_fmt, totalItemCount),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          TextButton(
            onClick = onValidateAndSync,
            enabled = !isSyncing,
            modifier = Modifier
              .testTag("validate_cart_button")
              .minimumInteractiveComponentSize(),
          ) {
            Icon(
              imageVector = Icons.Filled.Sync,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = stringResource(R.string.cart_action_sync),
              style = MaterialTheme.typography.labelLarge,
            )
          }
          TextButton(
            onClick = onRequestClearCart,
            enabled = !isSyncing,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
              .testTag("clear_cart_button")
              .minimumInteractiveComponentSize(),
          ) {
            Text(
              text = stringResource(R.string.action_clear),
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
  }
}

@Composable
private fun CartOfflineBanner() {
  PizzoraStatusBanner(
    message = stringResource(R.string.cart_offline_banner),
    tone = PizzoraBannerTone.WARNING,
    icon = Icons.Filled.CloudOff,
    modifier = Modifier.testTag("cart_offline_banner"),
  )
}

@Composable
private fun CartUnavailableBanner() {
  PizzoraStatusBanner(
    message = stringResource(R.string.cart_unavailable_warning),
    tone = PizzoraBannerTone.ERROR,
    icon = Icons.Filled.WarningAmber,
    modifier = Modifier.testTag("cart_unavailable_banner"),
  )
}

@Composable
private fun CartStatusMessage(message: String) {
  PizzoraStatusBanner(
    message = message,
    tone = PizzoraBannerTone.INFO,
    modifier = Modifier.testTag("cart_status_message"),
  )
}

@Composable
private fun QuoteErrorBanner(message: String) {
  Surface(
    color = MaterialTheme.colorScheme.errorContainer,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier
      .fillMaxWidth()
      .semantics(mergeDescendants = true) {}
      .testTag("quote_error_text"),
  ) {
    Text(
      text = message,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onErrorContainer,
      modifier = Modifier.padding(12.dp),
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CartStickyBottomDock(
  uiState: CartUiState,
  quoteState: CheckoutQuoteUiState?,
  onProceedToCheckout: (() -> Unit)?,
  applyNavigationBarsPadding: Boolean,
  compactWidth: Boolean,
  maxContentWidth: Dp = Dp.Unspecified,
) {
  val density = LocalDensity.current
  val stackControls = (compactWidth && density.fontScale >= 1.2f) || density.fontScale >= 1.4f
  val quote = quoteState?.quote
  val isAcceptingOrders = quote?.isAcceptingOrders ?: true
  val checkoutEnabled = !uiState.hasUnavailableItems && !uiState.isSyncing && isAcceptingOrders

  PizzoraStickyActionDock(
    applyNavigationBarsPadding = applyNavigationBarsPadding,
    verticalPadding = if (compactWidth && !stackControls) 6.dp else 10.dp,
    modifier = Modifier.testTag("cart_sticky_dock"),
  ) {
    Box(
      modifier = Modifier.fillMaxWidth(),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .let { base -> if (maxContentWidth != Dp.Unspecified) base.widthIn(max = maxContentWidth) else base },
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (stackControls || onProceedToCheckout == null) {
          CartDockPriceSummary(uiState = uiState, quoteState = quoteState, compactWidth = compactWidth)
          if (onProceedToCheckout != null) {
            Button(
              onClick = onProceedToCheckout,
              enabled = checkoutEnabled,
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
              ),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .minimumInteractiveComponentSize()
                .testTag("proceed_to_checkout_button"),
            ) {
              Icon(Icons.Filled.ShoppingBag, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = stringResource(R.string.action_proceed_checkout),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        } else {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compactWidth) 8.dp else 12.dp),
          ) {
            Box(modifier = Modifier.weight(1f)) {
              CartDockPriceSummary(uiState = uiState, quoteState = quoteState, compactWidth = compactWidth)
            }
            Button(
              onClick = onProceedToCheckout,
              enabled = checkoutEnabled,
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
              ),
              contentPadding = if (compactWidth) {
                PaddingValues(horizontal = 10.dp, vertical = 8.dp)
              } else {
                PaddingValues(horizontal = 16.dp, vertical = 12.dp)
              },
              modifier = Modifier
                .let { base -> if (compactWidth) base.widthIn(max = 136.dp) else base }
                .heightIn(min = 48.dp)
                .minimumInteractiveComponentSize()
                .testTag("proceed_to_checkout_button"),
            ) {
              Icon(Icons.Filled.ShoppingBag, contentDescription = null, modifier = Modifier.size(if (compactWidth) 16.dp else 18.dp))
              Spacer(modifier = Modifier.width(if (compactWidth) 4.dp else 6.dp))
              Text(
                text = stringResource(R.string.action_proceed_checkout),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CartDockPriceSummary(
  uiState: CartUiState,
  quoteState: CheckoutQuoteUiState?,
  compactWidth: Boolean = false,
) {
  val breakdown = quoteState?.quote?.breakdown
  Column(
    verticalArrangement = Arrangement.spacedBy(2.dp),
    modifier = Modifier
      .fillMaxWidth()
      .semantics { liveRegion = LiveRegionMode.Polite },
  ) {
    if (breakdown != null) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.Center,
      ) {
        Text(
          text = stringResource(R.string.label_payable_total),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.align(Alignment.CenterVertically),
        )
        Text(
          text = Money.formatInr(breakdown.totalPaise),
          style = PizzoraMonoTitle,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          modifier = Modifier
            .align(Alignment.CenterVertically)
            .testTag("cart_payable_total_text"),
        )
      }
      Text(
        text = "${stringResource(R.string.label_subtotal)} (${uiState.totalItemCount}): ${Money.formatInr(uiState.subtotalPaise)}",
        style = if (compactWidth) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("cart_subtotal_text"),
      )
    } else {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.Center,
      ) {
        Text(
          text = stringResource(R.string.cart_est_subtotal_fmt, uiState.totalItemCount),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.align(Alignment.CenterVertically),
        )
        Text(
          text = Money.formatInr(uiState.subtotalPaise),
          style = PizzoraMonoTitle,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          modifier = Modifier
            .align(Alignment.CenterVertically)
            .testTag("cart_subtotal_text"),
        )
      }
      Text(
        text = stringResource(R.string.cart_tax_delivery_disclosure),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("cart_tax_delivery_disclosure"),
      )
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
  val semantic = LocalPizzoraSemanticColors.current
  val chipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = semantic.selectedSurface,
    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
  )

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier.fillMaxWidth().testTag("fulfillment_card"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        FilterChip(
          selected = quoteState.fulfillmentType == FulfillmentType.DELIVERY,
          onClick = { onFulfillmentTypeChange(FulfillmentType.DELIVERY) },
          label = { Text(stringResource(R.string.fulfillment_delivery)) },
          colors = chipColors,
          border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = quoteState.fulfillmentType == FulfillmentType.DELIVERY,
            borderColor = semantic.outlineInteractive,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            selectedBorderWidth = 1.5.dp,
          ),
          modifier = Modifier
            .selectable(
              selected = quoteState.fulfillmentType == FulfillmentType.DELIVERY,
              role = Role.RadioButton,
              onClick = { onFulfillmentTypeChange(FulfillmentType.DELIVERY) },
            )
            .minimumInteractiveComponentSize()
            .testTag("fulfillment_delivery_chip"),
        )
        FilterChip(
          selected = quoteState.fulfillmentType == FulfillmentType.PICKUP,
          onClick = { onFulfillmentTypeChange(FulfillmentType.PICKUP) },
          label = { Text(stringResource(R.string.fulfillment_pickup)) },
          colors = chipColors,
          border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = quoteState.fulfillmentType == FulfillmentType.PICKUP,
            borderColor = semantic.outlineInteractive,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            selectedBorderWidth = 1.5.dp,
          ),
          modifier = Modifier
            .selectable(
              selected = quoteState.fulfillmentType == FulfillmentType.PICKUP,
              role = Role.RadioButton,
              onClick = { onFulfillmentTypeChange(FulfillmentType.PICKUP) },
            )
            .minimumInteractiveComponentSize()
            .testTag("fulfillment_pickup_chip"),
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
            border = BorderStroke(1.dp, semantic.outlineInteractive),
            modifier = Modifier.minimumInteractiveComponentSize().testTag("check_pincode_button"),
          ) {
            Text(stringResource(R.string.action_check))
          }
        }
        val quoteZone = quoteState.quote?.deliveryZone
        val zoneCheck = quoteState.zoneCheckResult
        if (quoteZone != null) {
          Text(
            text = "${quoteZone.areaName} (${quoteZone.pincode}) • ETA ${quoteZone.estimatedMins} mins • Min ${Money.formatInr(quoteZone.minOrderPaise)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.testTag("delivery_zone_summary_text"),
          )
        } else if (zoneCheck != null) {
          Text(
            text = zoneCheck.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (zoneCheck.isServiceable) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
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
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
      val savingsPaise = quoteState.quote?.breakdown?.discountPaise ?: 0L
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
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.weight(1f),
            ) {
              Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp),
              )
              Column {
                Text(
                  text = applied,
                  style = PizzoraMonoTitle,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                  modifier = Modifier.testTag("applied_coupon_code_text"),
                )
                if (savingsPaise > 0L) {
                  Text(
                    text = stringResource(R.string.coupon_applied_savings_fmt, Money.formatInr(savingsPaise)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                  )
                }
              }
            }
            IconButton(
              onClick = onRemoveCoupon,
              modifier = Modifier.testTag("remove_coupon_button").size(48.dp),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CartItemCard(
  entry: CartItemWithToppings,
  onIncrement: () -> Unit,
  onDecrement: () -> Unit,
  onRemove: () -> Unit,
  onEdit: (() -> Unit)? = null,
  cardFocusRequester: FocusRequester? = null,
) {
  val item = entry.item
  val isAvailable = item.isAvailable
  val semantic = LocalPizzoraSemanticColors.current

  Card(
    colors = CardDefaults.cardColors(
      containerColor = if (isAvailable) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface,
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = BorderStroke(
      width = if (isAvailable) 1.dp else 1.5.dp,
      color = if (isAvailable) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error,
    ),
    modifier = Modifier
      .fillMaxWidth()
      .let { base -> if (cardFocusRequester != null) base.focusRequester(cardFocusRequester).focusable() else base }
      .testTag("cart_item_${item.id}"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
      ) {
        CartItemThumbnail(
          imageUrl = item.imageUrl,
          productName = item.productName,
          itemId = item.id,
        )

        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
          ) {
            Text(
              text = item.productName,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f).padding(end = 6.dp),
            )
            Text(
              text = Money.formatInr(item.unitPricePaise * item.quantity),
              style = PizzoraMonoTitle,
              color = MaterialTheme.colorScheme.primary,
              maxLines = 1,
            )
          }
          Text(
            text = "${item.sizeCode.lowercase().replaceFirstChar { it.uppercase() }} • ${item.crustName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (entry.toppings.isNotEmpty()) {
            Text(
              text = "Toppings: ${entry.toppings.joinToString(", ") { it.toppingName }}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      if (!isAvailable) {
        PizzoraStatusBanner(
          message = item.unavailableReason ?: stringResource(R.string.cart_item_unavailable_fallback),
          tone = PizzoraBannerTone.ERROR,
          verticalPadding = 6.dp,
          modifier = Modifier.testTag("cart_item_issue_${item.id}"),
        )
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outline)

      // Action controls row (Stepper + Edit + Remove)
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.surfaceVariant,
          tonalElevation = 0.dp,
          border = BorderStroke(1.dp, semantic.outlineInteractive),
          modifier = Modifier.align(Alignment.CenterVertically),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            val isRemoveAction = item.quantity <= 1
            val decreaseCd = if (isRemoveAction) {
              stringResource(R.string.cart_action_remove_item_cd)
            } else {
              stringResource(R.string.cart_qty_decrease_cd)
            }
            IconButton(
              onClick = onDecrement,
              modifier = Modifier
                .testTag("cart_qty_minus_${item.id}")
                .size(48.dp)
                .semantics { contentDescription = decreaseCd },
            ) {
              Icon(
                imageVector = if (isRemoveAction) Icons.Filled.DeleteOutline else Icons.Filled.Remove,
                contentDescription = decreaseCd,
                tint = if (isRemoveAction) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
              )
            }
            Text(
              text = item.quantity.toString(),
              style = PizzoraMonoBody,
              modifier = Modifier
                .padding(horizontal = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("cart_qty_value_${item.id}"),
            )
            IconButton(
              onClick = onIncrement,
              enabled = item.quantity < 25,
              modifier = Modifier
                .testTag("cart_qty_plus_${item.id}")
                .size(48.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.cart_qty_increase_cd),
              )
            }
          }
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          modifier = Modifier.align(Alignment.CenterVertically),
        ) {
          if (onEdit != null) {
            OutlinedButton(
              onClick = onEdit,
              border = BorderStroke(1.dp, semantic.outlineInteractive),
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
              modifier = Modifier
                .testTag("cart_edit_${item.id}")
                .heightIn(min = 48.dp)
                .minimumInteractiveComponentSize(),
            ) {
              Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = stringResource(R.string.cart_action_edit),
                style = MaterialTheme.typography.labelLarge,
              )
            }
          }

          IconButton(
            onClick = onRemove,
            modifier = Modifier
              .testTag("cart_remove_${item.id}")
              .size(48.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.DeleteOutline,
              contentDescription = stringResource(R.string.cart_action_remove_item_cd),
              tint = MaterialTheme.colorScheme.error,
            )
          }
        }
      }

      if (item.quantity >= 25) {
        Text(
          text = stringResource(R.string.cart_qty_max_note),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("cart_qty_max_note_${item.id}"),
        )
      }
    }
  }
}

@Composable
private fun CartItemThumbnail(
  imageUrl: String?,
  productName: String,
  itemId: String,
) {
  var imageLoadFailed by remember(imageUrl) { mutableStateOf(false) }
  val normalizedUrl = imageUrl?.trim()?.takeIf { it.isNotEmpty() }

  Box(
    modifier = Modifier
      .size(64.dp)
      .clip(RoundedCornerShape(10.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Filled.LocalPizza,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
      modifier = Modifier
        .size(24.dp)
        .testTag("cart_item_image_fallback_$itemId"),
    )
    if (normalizedUrl != null && !imageLoadFailed) {
      AsyncImage(
        model = normalizedUrl,
        contentDescription = "$productName photo",
        contentScale = ContentScale.Crop,
        onError = { imageLoadFailed = true },
        modifier = Modifier
          .fillMaxSize()
          .testTag("cart_item_image_$itemId"),
      )
    }
  }
}

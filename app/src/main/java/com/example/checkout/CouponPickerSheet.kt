package com.example.checkout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.PizzoraBannerTone
import com.example.ui.components.PizzoraStatusBanner
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.pizzora.contracts.CustomerCouponDto
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.Money
import com.pizzora.contracts.PriceBreakdownDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouponPickerSheet(
  coupons: List<CustomerCouponDto>,
  isLoading: Boolean,
  onSelectCoupon: (String) -> Unit,
  onDismiss: () -> Unit,
  errorMessage: String? = null,
  onRetry: (() -> Unit)? = null,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val eligibleCoupons = remember(coupons) { coupons.filter { it.isEligible } }
  val lockedCoupons = remember(coupons) { coupons.filterNot { it.isEligible } }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
    modifier = Modifier.testTag("coupon_picker_sheet"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.LocalOffer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = stringResource(R.string.coupon_sheet_title),
            style = MaterialTheme.typography.titleLarge,
          )
        }
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("close_coupon_sheet_button"),
        ) {
          Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
        }
      }

      if (isLoading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      } else if (!errorMessage.isNullOrBlank()) {
        PizzoraStatusBanner(
          message = errorMessage,
          tone = PizzoraBannerTone.ERROR,
          actionLabel = if (onRetry != null) stringResource(R.string.action_retry) else null,
          onAction = onRetry,
          actionTestTag = "coupon_sheet_retry_button",
          modifier = Modifier.testTag("coupon_sheet_error"),
        )
      } else if (coupons.isEmpty()) {
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier.fillMaxWidth().testTag("coupon_sheet_empty"),
        ) {
          Text(
            text = stringResource(R.string.coupon_sheet_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .testTag("coupon_sheet_list"),
          contentPadding = PaddingValues(vertical = 2.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (eligibleCoupons.isNotEmpty()) {
            item(key = "coupon_section_eligible") {
              Text(
                text = stringResource(R.string.coupon_section_eligible, eligibleCoupons.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                  .padding(top = 2.dp)
                  .testTag("coupon_section_eligible"),
              )
            }
            items(eligibleCoupons, key = { it.id }) { coupon ->
              CouponOptionCard(
                coupon = coupon,
                onApply = { onSelectCoupon(coupon.code) },
              )
            }
          }

          if (lockedCoupons.isNotEmpty()) {
            item(key = "coupon_section_locked") {
              Text(
                text = stringResource(R.string.coupon_section_locked, lockedCoupons.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                  .padding(top = if (eligibleCoupons.isNotEmpty()) 6.dp else 2.dp)
                  .testTag("coupon_section_locked"),
              )
            }
            items(lockedCoupons, key = { it.id }) { coupon ->
              CouponOptionCard(
                coupon = coupon,
                onApply = { onSelectCoupon(coupon.code) },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun CouponOptionCard(
  coupon: CustomerCouponDto,
  onApply: () -> Unit,
) {
  val headline = when (coupon.discountType) {
    DiscountType.PERCENT_BPS -> {
      val pct = coupon.discountValue / 100
      val rem = coupon.discountValue % 100
      val pctText = if (rem == 0L) "$pct% OFF" else "$pct.${rem.toString().padStart(2, '0')}% OFF"
      val capText = coupon.maxDiscountPaise?.let { " up to ${Money.formatInr(it)}" } ?: ""
      "$pctText$capText"
    }
    DiscountType.FLAT_PAISE -> "${Money.formatInr(coupon.discountValue)} OFF"
  }

  Card(
    colors = CardDefaults.cardColors(
      containerColor = if (coupon.isEligible) {
        MaterialTheme.colorScheme.surface
      } else {
        MaterialTheme.colorScheme.surfaceVariant
      },
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = BorderStroke(
      width = if (coupon.isEligible) 1.5.dp else 1.dp,
      color = if (coupon.isEligible) {
        MaterialTheme.colorScheme.secondary
      } else {
        MaterialTheme.colorScheme.outline
      },
    ),
    modifier = Modifier.fillMaxWidth().testTag("coupon_card_${coupon.code}"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.weight(1f),
        ) {
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small,
          ) {
            Text(
              text = coupon.code,
              style = PizzoraMonoTitle,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
          }
          Surface(
            color = if (coupon.isEligible) {
              MaterialTheme.colorScheme.secondaryContainer
            } else {
              MaterialTheme.colorScheme.surface
            },
            shape = MaterialTheme.shapes.small,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
              Icon(
                imageVector = if (coupon.isEligible) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                contentDescription = null,
                tint = if (coupon.isEligible) {
                  MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(12.dp),
              )
              Text(
                text = stringResource(
                  if (coupon.isEligible) R.string.coupon_badge_eligible else R.string.coupon_badge_locked,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (coupon.isEligible) {
                  MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
              )
            }
          }
        }
        Button(
          onClick = onApply,
          enabled = coupon.isEligible,
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("apply_coupon_btn_${coupon.code}"),
        ) {
          Text(stringResource(R.string.action_apply))
        }
      }

      Text(text = headline, style = MaterialTheme.typography.titleMedium)

      if (coupon.minOrderPaise > 0L) {
        Text(
          text = "Min order ${Money.formatInr(coupon.minOrderPaise)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (coupon.isEligible && coupon.estimatedDiscountPaise > 0L) {
        Text(
          text = "You save ${Money.formatInr(coupon.estimatedDiscountPaise)}",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.secondary,
        )
      } else if (!coupon.isEligible) {
        val reasonText = coupon.ineligibleReason?.takeIf { it.isNotBlank() }
          ?: if (coupon.minOrderPaise > 0L) {
            "Min order ${Money.formatInr(coupon.minOrderPaise)} required"
          } else {
            "Currently unavailable"
          }
        Text(
          text = reasonText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("coupon_ineligible_${coupon.code}"),
        )
      }
    }
  }
}

@Composable
fun PriceBreakdownCard(
  breakdown: PriceBreakdownDto,
  appliedCouponCode: String?,
  isLoading: Boolean,
  modifier: Modifier = Modifier,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
    modifier = modifier.fillMaxWidth().testTag("price_breakdown_card"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.price_breakdown_title),
          style = MaterialTheme.typography.titleMedium,
        )
        if (isLoading) {
          Text(
            text = "Updating...",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      BreakdownRow(
        label = stringResource(R.string.label_subtotal),
        amountText = Money.formatInr(breakdown.subtotalPaise),
        tag = "breakdown_subtotal_text",
      )

      if (breakdown.discountPaise > 0L) {
        val codeSuffix = appliedCouponCode?.let { " ($it)" } ?: ""
        BreakdownRow(
          label = "${stringResource(R.string.label_coupon_discount)}$codeSuffix",
          amountText = "-${Money.formatInr(breakdown.discountPaise)}",
          amountColor = MaterialTheme.colorScheme.secondary,
          tag = "breakdown_discount_text",
        )
      }

      BreakdownRow(
        label = stringResource(R.string.label_delivery_fee),
        amountText = if (breakdown.deliveryFeePaise == 0L) "FREE" else Money.formatInr(breakdown.deliveryFeePaise),
        amountColor = if (breakdown.deliveryFeePaise == 0L) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
        tag = "breakdown_delivery_fee_text",
      )

      val halfGstLabel = formatHalfBpsPercent(breakdown.gstBps)
      BreakdownRow(
        label = "CGST ($halfGstLabel)",
        amountText = Money.formatInr(breakdown.cgstPaise),
        tag = "breakdown_cgst_text",
      )
      BreakdownRow(
        label = "SGST ($halfGstLabel)",
        amountText = Money.formatInr(breakdown.sgstPaise),
        tag = "breakdown_sgst_text",
      )

      HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.label_payable_total),
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          text = Money.formatInr(breakdown.totalPaise),
          style = PizzoraMonoTitle,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.testTag("breakdown_total_text"),
        )
      }
      Spacer(modifier = Modifier.height(2.dp))
    }
  }
}

@Composable
private fun BreakdownRow(
  label: String,
  amountText: String,
  tag: String,
  amountColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = amountText,
      style = PizzoraMonoBody,
      color = amountColor,
      modifier = Modifier.testTag(tag),
    )
  }
}

private fun formatHalfBpsPercent(gstBps: Int): String {
  val halfBps = gstBps / 2
  val whole = halfBps / 100
  val rem = halfBps % 100
  return if (rem == 0) {
    "$whole%"
  } else {
    val dec = rem.toString().padStart(2, '0').trimEnd('0')
    "$whole.$dec%"
  }
}

package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.example.ui.theme.LocalPizzoraSemanticColors
import com.example.ui.theme.LocalPizzoraTokens
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentStatus

/** KDS ticket visual states derived from [OrderStatus] plus an overdue flag. */
enum class PizzoraKdsState {
  Pending,
  Prep,
  Ready,
  Overdue,
}

/** Visual tones for [PizzoraBadge]. */
enum class PizzoraBadgeTone {
  Neutral,
  Info,
  Success,
  Warning,
  Error,
}

private data class PizzoraBadgePalette(
  val container: Color,
  val content: Color,
)

@Composable
private fun badgePalette(tone: PizzoraBadgeTone): PizzoraBadgePalette {
  val colorScheme = MaterialTheme.colorScheme
  val semantic = LocalPizzoraSemanticColors.current
  return when (tone) {
    PizzoraBadgeTone.Neutral -> PizzoraBadgePalette(
      container = colorScheme.surfaceVariant,
      content = colorScheme.onSurfaceVariant,
    )
    PizzoraBadgeTone.Info -> PizzoraBadgePalette(
      container = colorScheme.primaryContainer,
      content = colorScheme.onPrimaryContainer,
    )
    PizzoraBadgeTone.Success -> PizzoraBadgePalette(
      container = colorScheme.secondaryContainer,
      content = colorScheme.onSecondaryContainer,
    )
    PizzoraBadgeTone.Warning -> PizzoraBadgePalette(
      container = colorScheme.tertiaryContainer,
      content = colorScheme.onTertiaryContainer,
    )
    PizzoraBadgeTone.Error -> PizzoraBadgePalette(
      container = colorScheme.errorContainer,
      content = colorScheme.onErrorContainer,
    )
  }
}

private fun orderStatusTone(status: OrderStatus): PizzoraBadgeTone = when (status) {
  OrderStatus.CANCELLED, OrderStatus.EXPIRED -> PizzoraBadgeTone.Error
  OrderStatus.COMPLETED, OrderStatus.READY -> PizzoraBadgeTone.Success
  OrderStatus.PENDING_PAYMENT -> PizzoraBadgeTone.Warning
  else -> PizzoraBadgeTone.Info
}

private fun paymentStatusTone(status: PaymentStatus): PizzoraBadgeTone = when (status) {
  PaymentStatus.EXPIRED, PaymentStatus.CANCELLED -> PizzoraBadgeTone.Error
  PaymentStatus.CAPTURED, PaymentStatus.COD_COLLECTED -> PizzoraBadgeTone.Success
  PaymentStatus.UNPAID, PaymentStatus.COD_PENDING, PaymentStatus.REFUND_PENDING -> PizzoraBadgeTone.Warning
  PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED -> PizzoraBadgeTone.Info
}

@Composable
private fun kdsStateColors(state: PizzoraKdsState): PizzoraBadgePalette {
  val semantic = LocalPizzoraSemanticColors.current
  return when (state) {
    PizzoraKdsState.Pending -> PizzoraBadgePalette(
      container = semantic.kdsPendingAmber.copy(alpha = 0.22f),
      content = semantic.kdsPendingAmber,
    )
    PizzoraKdsState.Prep -> PizzoraBadgePalette(
      container = semantic.kdsPrepSky.copy(alpha = 0.22f),
      content = semantic.kdsPrepSky,
    )
    PizzoraKdsState.Ready -> PizzoraBadgePalette(
      container = semantic.kdsReadyEmerald.copy(alpha = 0.22f),
      content = semantic.kdsReadyEmerald,
    )
    PizzoraKdsState.Overdue -> PizzoraBadgePalette(
      container = semantic.kdsOverdueCrimson.copy(alpha = 0.22f),
      content = semantic.kdsOverdueCrimson,
    )
  }
}

/**
 * Token-driven status badge pill with an appear animation.
 *
 * On first composition the badge scales from 0 to 1 and fades in using the fast emphasized
 * motion tokens. Supports [OrderStatus], [PaymentStatus], and KDS states. Adapts automatically
 * to light, dark, and KDS themes.
 */
@Composable
fun PizzoraBadge(
  label: String,
  modifier: Modifier = Modifier,
  tone: PizzoraBadgeTone = PizzoraBadgeTone.Neutral,
  icon: ImageVector? = null,
  testTag: String = "pizzora_badge",
) {
  val tokens = LocalPizzoraTokens.current
  val palette = badgePalette(tone)
  var appeared by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { appeared = true }

  val scale by animateFloatAsState(
    targetValue = if (appeared) 1f else 0f,
    animationSpec = tween(
      durationMillis = tokens.motion.durationFast,
      easing = tokens.motion.easingEmphasized,
    ),
    label = "PizzoraBadgeAppearScale",
  )
  val alpha by animateFloatAsState(
    targetValue = if (appeared) 1f else 0f,
    animationSpec = tween(
      durationMillis = tokens.motion.durationFast,
      easing = tokens.motion.easingEmphasized,
    ),
    label = "PizzoraBadgeAppearAlpha",
  )

  Row(
    modifier = modifier
      .scale(scale)
      .background(palette.container, RoundedCornerShape(tokens.shape.radiusPill))
      .padding(
        horizontal = tokens.spacing.space12,
        vertical = tokens.spacing.space4,
      )
      .testTag(testTag),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space4),
  ) {
    if (icon != null) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = palette.content.copy(alpha = alpha),
        modifier = Modifier
          .size(tokens.sizing.iconSmall)
          .testTag("${testTag}_icon"),
      )
    }
    Text(
      text = label,
      modifier = Modifier.testTag("${testTag}_label"),
      style = MaterialTheme.typography.labelMedium,
      color = palette.content.copy(alpha = alpha),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** Badge for an [OrderStatus] value. */
@Composable
fun PizzoraOrderStatusBadge(
  status: OrderStatus,
  modifier: Modifier = Modifier,
  testTag: String = "pizzora_order_status_badge",
) {
  PizzoraBadge(
    label = status.name.replace('_', ' '),
    modifier = modifier,
    tone = orderStatusTone(status),
    testTag = testTag,
  )
}

/** Badge for a [PaymentStatus] value. */
@Composable
fun PizzoraPaymentStatusBadge(
  status: PaymentStatus,
  modifier: Modifier = Modifier,
  testTag: String = "pizzora_payment_status_badge",
) {
  PizzoraBadge(
    label = status.name.replace('_', ' '),
    modifier = modifier,
    tone = paymentStatusTone(status),
    testTag = testTag,
  )
}

/** Badge for a KDS ticket state, using the semantic KDS colors. */
@Composable
fun PizzoraKdsStateBadge(
  state: PizzoraKdsState,
  modifier: Modifier = Modifier,
  testTag: String = "pizzora_kds_state_badge",
) {
  val tokens = LocalPizzoraTokens.current
  val palette = kdsStateColors(state)
  var appeared by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { appeared = true }

  val scale by animateFloatAsState(
    targetValue = if (appeared) 1f else 0f,
    animationSpec = tween(
      durationMillis = tokens.motion.durationFast,
      easing = tokens.motion.easingEmphasized,
    ),
    label = "PizzoraKdsBadgeAppearScale",
  )
  val alpha by animateFloatAsState(
    targetValue = if (appeared) 1f else 0f,
    animationSpec = tween(
      durationMillis = tokens.motion.durationFast,
      easing = tokens.motion.easingEmphasized,
    ),
    label = "PizzoraKdsBadgeAppearAlpha",
  )

  Row(
    modifier = modifier
      .scale(scale)
      .background(palette.container, RoundedCornerShape(tokens.shape.radiusPill))
      .padding(
        horizontal = tokens.spacing.space12,
        vertical = tokens.spacing.space4,
      )
      .testTag(testTag),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space4),
  ) {
    Text(
      text = state.name,
      modifier = Modifier.testTag("${testTag}_label"),
      style = MaterialTheme.typography.labelMedium,
      color = palette.content.copy(alpha = alpha),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

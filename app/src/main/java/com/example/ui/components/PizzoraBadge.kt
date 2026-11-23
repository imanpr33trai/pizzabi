package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.example.ui.theme.LocalPizzoraSemanticColors
import com.example.ui.theme.LocalPizzoraTokens
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentStatus

/** Visual states for a KDS ticket, derived from [OrderStatus] plus its overdue flag. */
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

/**
 * Token-driven status pill with an appear animation.
 *
 * The badge scales from 0 to 1 and fades in using the fast emphasized motion tokens on first
 * composition. Specialized helpers below map [OrderStatus], [PaymentStatus], and
 * [PizzoraKdsState] values onto the appropriate tone. Adapts to light, dark, and KDS themes.
 */
@Composable
fun PizzoraBadge(
  label: String,
  modifier: Modifier = Modifier,
  tone: PizzoraBadgeTone = PizzoraBadgeTone.Neutral,
  icon: ImageVector? = null,
  testTag: String = "pizzora_badge",
) {
  PizzoraAppearingBadge(
    label = label,
    palette = badgePalette(tone),
    icon = icon,
    modifier = modifier,
    testTag = testTag,
  )
}

/** Badge for an [OrderStatus] value. */
@Composable
fun PizzoraOrderStatusBadge(
  status: OrderStatus,
  modifier: Modifier = Modifier,
  testTag: String = "pizzora_order_status_badge",
) {
  PizzoraBadge(
    label = statusLabel(status.name),
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
    label = statusLabel(status.name),
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
  PizzoraAppearingBadge(
    label = statusLabel(state.name),
    palette = kdsStatePalette(state),
    icon = null,
    modifier = modifier,
    testTag = testTag,
  )
}

@Composable
private fun PizzoraAppearingBadge(
  label: String,
  palette: PizzoraBadgePalette,
  icon: ImageVector?,
  modifier: Modifier,
  testTag: String,
) {
  val tokens = LocalPizzoraTokens.current
  var appeared by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { appeared = true }

  AnimatedVisibility(
    visible = appeared,
    modifier = modifier.testTag(testTag),
    enter = fadeIn(
      animationSpec = tween(
        durationMillis = tokens.motion.durationFast,
        easing = tokens.motion.easingEmphasized,
      ),
    ) + scaleIn(
      initialScale = 0f,
      animationSpec = tween(
        durationMillis = tokens.motion.durationFast,
        easing = tokens.motion.easingEmphasized,
      ),
    ),
  ) {
    PizzoraBadgeContent(
      label = label,
      palette = palette,
      icon = icon,
      testTag = testTag,
    )
  }
}

@Composable
private fun PizzoraBadgeContent(
  label: String,
  palette: PizzoraBadgePalette,
  icon: ImageVector?,
  testTag: String,
) {
  val tokens = LocalPizzoraTokens.current
  Row(
    modifier = Modifier
      .background(palette.container, RoundedCornerShape(tokens.shape.radiusPill))
      .padding(
        horizontal = tokens.spacing.space12,
        vertical = tokens.spacing.space4,
      ),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space4),
  ) {
    if (icon != null) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = palette.content,
        modifier = Modifier
          .size(tokens.sizing.iconSmall)
          .testTag("${testTag}_icon"),
      )
    }
    Text(
      text = label,
      modifier = Modifier.testTag("${testTag}_label"),
      style = MaterialTheme.typography.labelMedium,
      color = palette.content,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun badgePalette(tone: PizzoraBadgeTone): PizzoraBadgePalette {
  val colorScheme = MaterialTheme.colorScheme
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

private fun statusLabel(name: String): String = name.replace('_', ' ')

private fun orderStatusTone(status: OrderStatus): PizzoraBadgeTone = when (status) {
  OrderStatus.CANCELLED, OrderStatus.EXPIRED -> PizzoraBadgeTone.Error
  OrderStatus.COMPLETED, OrderStatus.READY -> PizzoraBadgeTone.Success
  OrderStatus.PENDING_PAYMENT -> PizzoraBadgeTone.Warning
  OrderStatus.PLACED, OrderStatus.ACCEPTED, OrderStatus.PREPARING,
  OrderStatus.OUT_FOR_DELIVERY -> PizzoraBadgeTone.Info
}

private fun paymentStatusTone(status: PaymentStatus): PizzoraBadgeTone = when (status) {
  PaymentStatus.EXPIRED, PaymentStatus.CANCELLED -> PizzoraBadgeTone.Error
  PaymentStatus.CAPTURED, PaymentStatus.COD_COLLECTED -> PizzoraBadgeTone.Success
  PaymentStatus.UNPAID, PaymentStatus.COD_PENDING,
  PaymentStatus.REFUND_PENDING -> PizzoraBadgeTone.Warning
  PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED -> PizzoraBadgeTone.Info
}

@Composable
private fun kdsStatePalette(state: PizzoraKdsState): PizzoraBadgePalette {
  val semantic = LocalPizzoraSemanticColors.current
  val accent = when (state) {
    PizzoraKdsState.Pending -> semantic.kdsPendingAmber
    PizzoraKdsState.Prep -> semantic.kdsPrepSky
    PizzoraKdsState.Ready -> semantic.kdsReadyEmerald
    PizzoraKdsState.Overdue -> semantic.kdsOverdueCrimson
  }
  return PizzoraBadgePalette(
    container = accent.copy(alpha = 0.22f),
    content = accent,
  )
}

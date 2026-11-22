package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.example.ui.theme.LocalPizzoraTokens

/** Visual variants for [PizzoraButton]. */
enum class PizzoraButtonVariant {
  Primary,
  Secondary,
  Tonal,
  Outlined,
}

/**
 * Token-driven PIZZORA button.
 *
 * Every variant shares the same 48dp minimum touch target, token radius, token spacing, press
 * scale, and ripple. [loading] disables interaction and swaps the leading icon for a progress
 * indicator, while clicks emit haptic feedback by default.
 */
@Composable
fun PizzoraButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  variant: PizzoraButtonVariant = PizzoraButtonVariant.Primary,
  enabled: Boolean = true,
  loading: Boolean = false,
  leadingIcon: ImageVector? = null,
  trailingIcon: ImageVector? = null,
  hapticFeedbackEnabled: Boolean = true,
  testTag: String = "pizzora_button",
) {
  val tokens = LocalPizzoraTokens.current
  val haptics = LocalHapticFeedback.current
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val isInteractive = enabled && !loading
  val scale by animateFloatAsState(
    targetValue = if (pressed && isInteractive) tokens.motion.pressScale else 1f,
    animationSpec = tween(
      durationMillis = tokens.motion.durationFast,
      easing = tokens.motion.easingStandard,
    ),
    label = "PizzoraButtonScale",
  )
  val colors = pizzoraButtonColors(variant = variant, enabled = isInteractive)

  Surface(
    onClick = {
      if (isInteractive) {
        if (hapticFeedbackEnabled) {
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        onClick()
      }
    },
    modifier = modifier
      .defaultMinSize(minHeight = tokens.sizing.buttonHeight)
      .scale(scale)
      .testTag(testTag),
    enabled = isInteractive,
    shape = RoundedCornerShape(tokens.shape.radiusMedium),
    color = colors.container,
    contentColor = colors.content,
    tonalElevation = tokens.elevation.level0,
    shadowElevation = if (pressed && isInteractive) tokens.elevation.buttonPressed else tokens.elevation.level0,
    border = colors.border,
    interactionSource = interactionSource,
  ) {
    Row(
      modifier = Modifier.padding(
        horizontal = tokens.spacing.space24,
        vertical = tokens.spacing.space12,
      ),
      horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space8),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (loading) {
        CircularProgressIndicator(
          modifier = Modifier
            .size(tokens.sizing.loadingIndicator)
            .testTag("${testTag}_loading"),
          color = colors.content,
          strokeWidth = tokens.shape.borderThick,
        )
      } else if (leadingIcon != null) {
        Icon(
          imageVector = leadingIcon,
          contentDescription = null,
          modifier = Modifier
            .size(tokens.sizing.iconMedium)
            .testTag("${testTag}_leading_icon"),
        )
      }
      Text(
        text = text,
        modifier = Modifier.testTag("${testTag}_label"),
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!loading && trailingIcon != null) {
        Icon(
          imageVector = trailingIcon,
          contentDescription = null,
          modifier = Modifier
            .size(tokens.sizing.iconMedium)
            .testTag("${testTag}_trailing_icon"),
        )
      }
    }
  }
}

private data class PizzoraButtonColors(
  val container: Color,
  val content: Color,
  val border: BorderStroke?,
)

@Composable
private fun pizzoraButtonColors(
  variant: PizzoraButtonVariant,
  enabled: Boolean,
): PizzoraButtonColors {
  val tokens = LocalPizzoraTokens.current
  val colorScheme = MaterialTheme.colorScheme
  if (!enabled) {
    val border = if (variant == PizzoraButtonVariant.Outlined) {
      BorderStroke(tokens.shape.borderThin, colorScheme.onSurface.copy(alpha = 0.12f))
    } else {
      null
    }
    return PizzoraButtonColors(
      container = if (variant == PizzoraButtonVariant.Outlined) {
        Color.Transparent
      } else {
        colorScheme.onSurface.copy(alpha = 0.12f)
      },
      content = colorScheme.onSurface.copy(alpha = 0.38f),
      border = border,
    )
  }
  return when (variant) {
    PizzoraButtonVariant.Primary -> PizzoraButtonColors(
      container = colorScheme.primary,
      content = colorScheme.onPrimary,
      border = null,
    )
    PizzoraButtonVariant.Secondary -> PizzoraButtonColors(
      container = colorScheme.secondary,
      content = colorScheme.onSecondary,
      border = null,
    )
    PizzoraButtonVariant.Tonal -> PizzoraButtonColors(
      container = colorScheme.secondaryContainer,
      content = colorScheme.onSecondaryContainer,
      border = null,
    )
    PizzoraButtonVariant.Outlined -> PizzoraButtonColors(
      container = Color.Transparent,
      content = colorScheme.primary,
      border = BorderStroke(tokens.shape.borderThin, colorScheme.outline),
    )
  }
}

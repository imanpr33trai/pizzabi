package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import com.example.ui.theme.LocalPizzoraTokens

/** Visual variants for [PizzoraIconButton]. */
enum class PizzoraIconButtonVariant {
  Standard,
  Filled,
  Tonal,
  Outlined,
}

/**
 * Token-driven PIZZORA icon button.
 *
 * The component always renders inside a 48dp token touch target with a circular ripple. Passing a
 * [tooltip] wraps the button in a Material tooltip and exposes the same text to accessibility
 * services.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PizzoraIconButton(
  icon: ImageVector,
  contentDescription: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  variant: PizzoraIconButtonVariant = PizzoraIconButtonVariant.Standard,
  tooltip: String? = null,
  hapticFeedbackEnabled: Boolean = true,
  testTag: String = "pizzora_icon_button",
) {
  val tokens = LocalPizzoraTokens.current
  val haptics = LocalHapticFeedback.current
  val colors = pizzoraIconButtonColors(variant = variant, enabled = enabled)
  val button: @Composable () -> Unit = {
    Surface(
      onClick = {
        if (enabled) {
          if (hapticFeedbackEnabled) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
          }
          onClick()
        }
      },
      modifier = modifier
        .size(tokens.sizing.iconButtonSize)
        .testTag(testTag),
      enabled = enabled,
      shape = CircleShape,
      color = colors.container,
      contentColor = colors.content,
      tonalElevation = tokens.elevation.level0,
      border = colors.border,
    ) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
          imageVector = icon,
          contentDescription = contentDescription,
          modifier = Modifier
            .size(tokens.sizing.iconLarge)
            .testTag("${testTag}_icon"),
        )
      }
    }
  }

  if (tooltip != null) {
    TooltipBox(
      positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
      tooltip = {
        PlainTooltip {
          Text(text = tooltip)
        }
      },
      state = rememberTooltipState(),
      content = button,
    )
  } else {
    button()
  }
}

private data class PizzoraIconButtonColors(
  val container: Color,
  val content: Color,
  val border: BorderStroke?,
)

@Composable
private fun pizzoraIconButtonColors(
  variant: PizzoraIconButtonVariant,
  enabled: Boolean,
): PizzoraIconButtonColors {
  val tokens = LocalPizzoraTokens.current
  val colorScheme = MaterialTheme.colorScheme
  if (!enabled) {
    return PizzoraIconButtonColors(
      container = if (variant == PizzoraIconButtonVariant.Standard ||
        variant == PizzoraIconButtonVariant.Outlined
      ) {
        Color.Transparent
      } else {
        colorScheme.onSurface.copy(alpha = 0.12f)
      },
      content = colorScheme.onSurface.copy(alpha = 0.38f),
      border = if (variant == PizzoraIconButtonVariant.Outlined) {
        BorderStroke(tokens.shape.borderThin, colorScheme.onSurface.copy(alpha = 0.12f))
      } else {
        null
      },
    )
  }
  return when (variant) {
    PizzoraIconButtonVariant.Standard -> PizzoraIconButtonColors(
      container = Color.Transparent,
      content = colorScheme.onSurfaceVariant,
      border = null,
    )
    PizzoraIconButtonVariant.Filled -> PizzoraIconButtonColors(
      container = colorScheme.primary,
      content = colorScheme.onPrimary,
      border = null,
    )
    PizzoraIconButtonVariant.Tonal -> PizzoraIconButtonColors(
      container = colorScheme.secondaryContainer,
      content = colorScheme.onSecondaryContainer,
      border = null,
    )
    PizzoraIconButtonVariant.Outlined -> PizzoraIconButtonColors(
      container = Color.Transparent,
      content = colorScheme.onSurfaceVariant,
      border = BorderStroke(tokens.shape.borderThin, colorScheme.outline),
    )
  }
}

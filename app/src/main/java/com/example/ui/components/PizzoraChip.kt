package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.example.ui.theme.LocalPizzoraTokens

/** Visual variants for [PizzoraChip]. */
enum class PizzoraChipVariant {
  Filter,
  Input,
  Assist,
  Suggestion,
}

/**
 * Token-driven PIZZORA chip.
 *
 * Selection animates the chip to a 1.02 scale and morphs both the border width and color. Filter
 * and input chips expose selectable semantics, while assist and suggestion chips behave as
 * buttons. The visual chip is 32dp tall but always reserves the 48dp token touch target.
 */
@Composable
fun PizzoraChip(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  variant: PizzoraChipVariant = PizzoraChipVariant.Filter,
  selected: Boolean = false,
  enabled: Boolean = true,
  leadingIcon: ImageVector? = null,
  trailingIcon: ImageVector? = null,
  hapticFeedbackEnabled: Boolean = true,
  testTag: String = "pizzora_chip",
) {
  val tokens = LocalPizzoraTokens.current
  val haptics = LocalHapticFeedback.current
  val shape = RoundedCornerShape(tokens.shape.radiusPill)
  val palette = pizzoraChipPalette(variant = variant, selected = selected, enabled = enabled)
  val scale by animateFloatAsState(
    targetValue = if (selected) tokens.motion.selectedScale else 1f,
    animationSpec = tween(
      durationMillis = tokens.motion.durationMedium,
      easing = tokens.motion.easingEmphasized,
    ),
    label = "PizzoraChipScale",
  )
  val borderWidth by animateDpAsState(
    targetValue = if (selected) tokens.shape.borderThick else tokens.shape.borderThin,
    animationSpec = tween(
      durationMillis = tokens.motion.durationMedium,
      easing = tokens.motion.easingStandard,
    ),
    label = "PizzoraChipBorderWidth",
  )
  val borderColor by animateColorAsState(
    targetValue = palette.border,
    animationSpec = tween(
      durationMillis = tokens.motion.durationMedium,
      easing = tokens.motion.easingStandard,
    ),
    label = "PizzoraChipBorderColor",
  )
  val containerColor by animateColorAsState(
    targetValue = palette.container,
    animationSpec = tween(
      durationMillis = tokens.motion.durationMedium,
      easing = tokens.motion.easingStandard,
    ),
    label = "PizzoraChipContainerColor",
  )
  val click = {
    if (enabled) {
      if (hapticFeedbackEnabled) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
      }
      onClick()
    }
  }
  val selectedState = selected
  val selectionModifier = when (variant) {
    PizzoraChipVariant.Filter, PizzoraChipVariant.Input -> Modifier.selectable(
      selected = selected,
      enabled = enabled,
      role = Role.Checkbox,
      onClick = click,
    )
    PizzoraChipVariant.Assist, PizzoraChipVariant.Suggestion -> Modifier
      .semantics { this.selected = selectedState }
      .clickable(enabled = enabled, role = Role.Button, onClick = click)
  }

  Box(
    modifier = modifier
      .sizeIn(
        minWidth = tokens.sizing.touchTargetMin,
        minHeight = tokens.sizing.touchTargetMin,
      )
      .scale(scale)
      .clip(shape)
      .testTag(testTag)
      .then(selectionModifier),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier
        .defaultMinSize(minHeight = tokens.sizing.chipHeight)
        .clip(shape)
        .background(containerColor)
        .border(borderWidth, borderColor, shape)
        .padding(
          horizontal = tokens.spacing.space12,
          vertical = tokens.spacing.space4,
        ),
      horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space8),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (leadingIcon != null) {
        Icon(
          imageVector = leadingIcon,
          contentDescription = null,
          modifier = Modifier
            .size(tokens.sizing.chipIcon)
            .testTag("${testTag}_leading_icon"),
          tint = palette.content,
        )
      }
      Text(
        text = label,
        modifier = Modifier.testTag("${testTag}_label"),
        style = MaterialTheme.typography.labelLarge,
        color = palette.content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (trailingIcon != null) {
        Icon(
          imageVector = trailingIcon,
          contentDescription = null,
          modifier = Modifier
            .size(tokens.sizing.chipIcon)
            .testTag("${testTag}_trailing_icon"),
          tint = palette.content,
        )
      }
    }
  }
}

private data class PizzoraChipPalette(
  val container: Color,
  val content: Color,
  val border: Color,
)

@Composable
private fun pizzoraChipPalette(
  variant: PizzoraChipVariant,
  selected: Boolean,
  enabled: Boolean,
): PizzoraChipPalette {
  val colorScheme = MaterialTheme.colorScheme
  if (!enabled) {
    return PizzoraChipPalette(
      container = colorScheme.surface,
      content = colorScheme.onSurface.copy(alpha = 0.38f),
      border = colorScheme.onSurface.copy(alpha = 0.12f),
    )
  }
  return when (variant) {
    PizzoraChipVariant.Filter -> if (selected) {
      PizzoraChipPalette(
        container = colorScheme.secondaryContainer,
        content = colorScheme.onSecondaryContainer,
        border = colorScheme.secondary,
      )
    } else {
      PizzoraChipPalette(
        container = colorScheme.surface,
        content = colorScheme.onSurfaceVariant,
        border = colorScheme.outline,
      )
    }
    PizzoraChipVariant.Input -> if (selected) {
      PizzoraChipPalette(
        container = colorScheme.primaryContainer,
        content = colorScheme.onPrimaryContainer,
        border = colorScheme.primary,
      )
    } else {
      PizzoraChipPalette(
        container = colorScheme.surfaceVariant,
        content = colorScheme.onSurfaceVariant,
        border = colorScheme.outline,
      )
    }
    PizzoraChipVariant.Assist -> if (selected) {
      PizzoraChipPalette(
        container = colorScheme.primaryContainer,
        content = colorScheme.onPrimaryContainer,
        border = colorScheme.primary,
      )
    } else {
      PizzoraChipPalette(
        container = colorScheme.surfaceVariant,
        content = colorScheme.onSurfaceVariant,
        border = colorScheme.outline,
      )
    }
    PizzoraChipVariant.Suggestion -> if (selected) {
      PizzoraChipPalette(
        container = colorScheme.tertiaryContainer,
        content = colorScheme.onTertiaryContainer,
        border = colorScheme.tertiary,
      )
    } else {
      PizzoraChipPalette(
        container = colorScheme.surface,
        content = colorScheme.onSurfaceVariant,
        border = colorScheme.outline,
      )
    }
  }
}

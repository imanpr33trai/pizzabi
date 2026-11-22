package com.example.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.example.ui.theme.LocalPizzoraTokens

/**
 * Standardized PIZZORA card.
 *
 * Cards use the token radius, a subtle token border, token padding, and the card elevation pair.
 * When [onClick] is provided the card gains ripple feedback and animates both scale and elevation
 * while pressed.
 */
@Composable
fun PizzoraCard(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  enabled: Boolean = true,
  elevation: Dp = LocalPizzoraTokens.current.elevation.cardRest,
  shape: Shape = RoundedCornerShape(LocalPizzoraTokens.current.shape.radiusMedium),
  border: BorderStroke? = BorderStroke(
    LocalPizzoraTokens.current.shape.borderThin,
    MaterialTheme.colorScheme.outlineVariant,
  ),
  contentPadding: PaddingValues = PaddingValues(LocalPizzoraTokens.current.spacing.space16),
  testTag: String = "pizzora_card",
  content: @Composable ColumnScope.() -> Unit,
) {
  val tokens = LocalPizzoraTokens.current
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (onClick != null && pressed) tokens.motion.pressScale else 1f,
    animationSpec = tween(
      durationMillis = tokens.motion.durationFast,
      easing = tokens.motion.easingStandard,
    ),
    label = "PizzoraCardScale",
  )
  val animatedElevation by animateDpAsState(
    targetValue = if (onClick != null && pressed) tokens.elevation.cardPressed else elevation,
    animationSpec = tween(
      durationMillis = tokens.motion.durationFast,
      easing = tokens.motion.easingStandard,
    ),
    label = "PizzoraCardElevation",
  )
  val cardModifier = modifier.scale(scale).testTag(testTag)
  val cardContent: @Composable () -> Unit = {
    Column(modifier = Modifier.padding(contentPadding), content = content)
  }

  if (onClick != null) {
    Surface(
      onClick = onClick,
      modifier = cardModifier,
      enabled = enabled,
      shape = shape,
      color = MaterialTheme.colorScheme.surface,
      contentColor = MaterialTheme.colorScheme.onSurface,
      tonalElevation = tokens.elevation.level0,
      shadowElevation = animatedElevation,
      border = border,
      interactionSource = interactionSource,
      content = cardContent,
    )
  } else {
    Surface(
      modifier = cardModifier,
      shape = shape,
      color = MaterialTheme.colorScheme.surface,
      contentColor = MaterialTheme.colorScheme.onSurface,
      tonalElevation = tokens.elevation.level0,
      shadowElevation = animatedElevation,
      border = border,
      content = cardContent,
    )
  }
}

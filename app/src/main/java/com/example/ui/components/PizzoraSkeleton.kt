package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalPizzoraTokens

/** Shapes that [PizzoraSkeleton] can render as loading placeholders. */
enum class PizzoraSkeletonShape {
  Image,
  TextLine,
  Card,
}

private const val SHIMMER_DURATION_MS = 2000

/**
 * Token-driven shimmer loading placeholder.
 *
 * Renders a gradient that sweeps across the placeholder with an infinite 2s linear translateX
 * animation. Placeholder sizes come from the PIZZORA token scale so skeletons match the real
 * content layout. Adapts to light, dark, and KDS themes.
 */
@Composable
fun PizzoraSkeleton(
  modifier: Modifier = Modifier,
  shape: PizzoraSkeletonShape = PizzoraSkeletonShape.TextLine,
  width: Dp? = null,
  height: Dp? = null,
  testTag: String = "pizzora_skeleton",
) {
  val tokens = LocalPizzoraTokens.current
  val colorScheme = MaterialTheme.colorScheme

  val transition = rememberInfiniteTransition(label = "PizzoraSkeletonShimmer")
  val translateX by transition.animateFloat(
    initialValue = -1f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = SHIMMER_DURATION_MS, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "PizzoraSkeletonTranslateX",
  )

  val baseColor = colorScheme.surfaceVariant
  val highlightColor = colorScheme.onSurface.copy(alpha = 0.08f)
  val brush = Brush.linearGradient(
    colors = listOf(baseColor, highlightColor, baseColor),
    start = Offset(translateX * 2000f - 1000f, 0f),
    end = Offset(translateX * 2000f + 1000f, 0f),
  )

  val resolvedHeight = height ?: when (shape) {
    PizzoraSkeletonShape.Image -> tokens.spacing.space64
    PizzoraSkeletonShape.TextLine -> tokens.spacing.space16
    PizzoraSkeletonShape.Card -> 132.dp
  }
  val resolvedModifier = when (shape) {
    PizzoraSkeletonShape.Image -> modifier
      .size(width ?: tokens.spacing.space64, resolvedHeight)
    PizzoraSkeletonShape.TextLine -> modifier
      .fillMaxWidth(fraction = if (width != null) 1f else 0.7f)
      .height(resolvedHeight)
    PizzoraSkeletonShape.Card -> modifier
      .fillMaxWidth()
      .height(resolvedHeight)
  }

  Box(
    modifier = resolvedModifier
      .clip(RoundedCornerShape(tokens.shape.radiusMedium))
      .background(baseColor)
      .drawWithContent {
        drawContent()
        drawRect(brush = brush)
      }
      .testTag(testTag),
  )
}

/**
 * Composite skeleton that mimics a menu/catalog card: image block on top, two text lines below.
 */
@Composable
fun PizzoraSkeletonCard(
  modifier: Modifier = Modifier,
  testTag: String = "pizzora_skeleton_card",
) {
  val tokens = LocalPizzoraTokens.current
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(tokens.spacing.space16)
      .testTag(testTag),
    verticalArrangement = Arrangement.spacedBy(tokens.spacing.space8),
  ) {
    PizzoraSkeleton(
      shape = PizzoraSkeletonShape.Image,
      modifier = Modifier.fillMaxWidth(),
      height = 120.dp,
      testTag = "${testTag}_image",
    )
    PizzoraSkeleton(
      shape = PizzoraSkeletonShape.TextLine,
      modifier = Modifier.fillMaxWidth(fraction = 0.8f),
      testTag = "${testTag}_title",
    )
    PizzoraSkeleton(
      shape = PizzoraSkeletonShape.TextLine,
      modifier = Modifier.fillMaxWidth(fraction = 0.5f),
      testTag = "${testTag}_subtitle",
    )
  }
}

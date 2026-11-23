package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.example.ui.theme.LocalPizzoraTokens

/** Placeholder shapes rendered by [PizzoraSkeleton]. */
enum class PizzoraSkeletonShape {
  Image,
  TextLine,
  Card,
}

private const val SHIMMER_DURATION_MS = 2_000

private data class SkeletonDimensions(
  val width: Dp?,
  val height: Dp,
  val fillFraction: Float?,
  val cornerRadius: Dp,
)

/**
 * Token-driven shimmer loading placeholder.
 *
 * A highlight sweeps across the placeholder with an infinite 2s linear translateX animation.
 * Default placeholder sizes come from the PIZZORA token scale so skeletons match the layout of
 * the real content they stand in for. The highlight is always lighter than the base tone, so the
 * shimmer reads the same way in light, dark, and KDS themes.
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
  val highlightColor = if (baseColor.luminance() > 0.5f) {
    lerp(baseColor, Color.White, 0.7f)
  } else {
    lerp(baseColor, Color.White, 0.18f)
  }
  val dimensions = skeletonDimensions(shape = shape, width = width, height = height)
  val shapeModifier = if (dimensions.width != null) {
    Modifier.width(dimensions.width).height(dimensions.height)
  } else {
    Modifier.fillMaxWidth(fraction = dimensions.fillFraction ?: 1f).height(dimensions.height)
  }

  Box(
    modifier = modifier
      .then(shapeModifier)
      .clip(RoundedCornerShape(dimensions.cornerRadius))
      .background(baseColor)
      .drawWithContent {
        drawContent()
        val sweepWidth = size.width.coerceAtLeast(1f)
        val centerX = size.width * translateX
        drawRect(
          brush = Brush.linearGradient(
            colors = listOf(baseColor, highlightColor, baseColor),
            start = Offset(centerX - sweepWidth, 0f),
            end = Offset(centerX + sweepWidth, 0f),
          ),
        )
      }
      .testTag(testTag),
  )
}

/**
 * Composite skeleton that mimics a catalog card: an image block followed by two text lines.
 */
@Composable
fun PizzoraSkeletonCard(
  modifier: Modifier = Modifier,
  testTag: String = "pizzora_skeleton_card",
) {
  val tokens = LocalPizzoraTokens.current
  PizzoraSurface(
    modifier = modifier.fillMaxWidth(),
    tone = PizzoraSurfaceTone.Surface,
    shape = RoundedCornerShape(tokens.shape.radiusMedium),
    border = BorderStroke(tokens.shape.borderThin, MaterialTheme.colorScheme.outlineVariant),
    testTag = testTag,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(tokens.spacing.space16),
      verticalArrangement = Arrangement.spacedBy(tokens.spacing.space8),
    ) {
      PizzoraSkeleton(
        shape = PizzoraSkeletonShape.Image,
        modifier = Modifier.fillMaxWidth(),
        height = tokens.spacing.space64 * 2,
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
}

@Composable
private fun skeletonDimensions(
  shape: PizzoraSkeletonShape,
  width: Dp?,
  height: Dp?,
): SkeletonDimensions {
  val tokens = LocalPizzoraTokens.current
  return when (shape) {
    PizzoraSkeletonShape.Image -> SkeletonDimensions(
      width = width ?: tokens.spacing.space64,
      height = height ?: tokens.spacing.space64,
      fillFraction = null,
      cornerRadius = tokens.shape.radiusMedium,
    )
    PizzoraSkeletonShape.TextLine -> SkeletonDimensions(
      width = width,
      height = height ?: tokens.spacing.space16,
      fillFraction = if (width == null) 0.7f else null,
      cornerRadius = tokens.shape.radiusSmall,
    )
    PizzoraSkeletonShape.Card -> SkeletonDimensions(
      width = null,
      height = height ?: tokens.spacing.space64 * 2,
      fillFraction = 1f,
      cornerRadius = tokens.shape.radiusMedium,
    )
  }
}

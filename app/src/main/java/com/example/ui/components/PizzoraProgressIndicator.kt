package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import com.example.ui.theme.LocalPizzoraTokens
import com.example.ui.theme.PizzoraTokenSet

/** Shapes for [PizzoraProgressIndicator]. */
enum class PizzoraProgressIndicatorStyle {
  Linear,
  Circular,
}

/**
 * Token-driven loading indicator.
 *
 * Pass [progress] (0..1) for determinate mode or leave it `null` for indeterminate mode. Both
 * styles use token colors and thickness; determinate changes and the indeterminate loop use the
 * motion tokens' duration and easing. The active theme supplies the colors, so the indicator
 * adapts to light, dark, and KDS surfaces.
 */
@Composable
fun PizzoraProgressIndicator(
  modifier: Modifier = Modifier,
  progress: Float? = null,
  style: PizzoraProgressIndicatorStyle = PizzoraProgressIndicatorStyle.Linear,
  testTag: String = "pizzora_progress_indicator",
) {
  val tokens = LocalPizzoraTokens.current
  val color = MaterialTheme.colorScheme.primary
  val trackColor = MaterialTheme.colorScheme.surfaceVariant
  val baseModifier = modifier.testTag(testTag)

  if (progress == null) {
    IndeterminateProgress(baseModifier, style, tokens, color, trackColor)
  } else {
    DeterminateProgress(baseModifier, style, progress, tokens, color, trackColor)
  }
}

@Composable
private fun DeterminateProgress(
  modifier: Modifier,
  style: PizzoraProgressIndicatorStyle,
  progress: Float,
  tokens: PizzoraTokenSet,
  color: Color,
  trackColor: Color,
) {
  val animatedProgress by animateFloatAsState(
    targetValue = progress.coerceIn(0f, 1f),
    animationSpec = tween(
      durationMillis = tokens.motion.durationMedium,
      easing = tokens.motion.easingStandard,
    ),
    label = "PizzoraProgressIndicatorProgress",
  )
  val semanticsModifier = modifier.semantics {
    progressBarRangeInfo = ProgressBarRangeInfo(animatedProgress, 0f..1f, 0)
  }

  when (style) {
    PizzoraProgressIndicatorStyle.Linear -> Canvas(
      modifier = semanticsModifier
        .fillMaxWidth()
        .height(tokens.spacing.space4),
    ) {
      drawRect(color = trackColor, size = size)
      drawRect(color = color, size = Size(size.width * animatedProgress, size.height))
    }

    PizzoraProgressIndicatorStyle.Circular -> Canvas(
      modifier = semanticsModifier.size(tokens.sizing.iconLarge),
    ) {
      val stroke = Stroke(width = tokens.shape.borderThick.toPx(), cap = StrokeCap.Round)
      drawArc(trackColor, 0f, 360f, useCenter = false, style = stroke)
      drawArc(color, -90f, animatedProgress * 360f, useCenter = false, style = stroke)
    }
  }
}

@Composable
private fun IndeterminateProgress(
  modifier: Modifier,
  style: PizzoraProgressIndicatorStyle,
  tokens: PizzoraTokenSet,
  color: Color,
  trackColor: Color,
) {
  val transition = rememberInfiniteTransition(label = "PizzoraProgressIndicatorLoop")
  val cycle by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(
        durationMillis = tokens.motion.durationSlow,
        easing = tokens.motion.easingStandard,
      ),
      repeatMode = RepeatMode.Restart,
    ),
    label = "PizzoraProgressIndicatorCycle",
  )
  val semanticsModifier = modifier.semantics {
    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
  }

  when (style) {
    PizzoraProgressIndicatorStyle.Linear -> Canvas(
      modifier = semanticsModifier
        .fillMaxWidth()
        .height(tokens.spacing.space4),
    ) {
      drawRect(color = trackColor, size = size)
      val segmentWidth = size.width * 0.3f
      val startX = (size.width + segmentWidth) * cycle - segmentWidth
      drawRect(color = color, topLeft = Offset(startX, 0f), size = Size(segmentWidth, size.height))
    }

    PizzoraProgressIndicatorStyle.Circular -> Canvas(
      modifier = semanticsModifier.size(tokens.sizing.iconLarge),
    ) {
      val stroke = Stroke(width = tokens.shape.borderThick.toPx(), cap = StrokeCap.Round)
      drawArc(trackColor, 0f, 360f, useCenter = false, style = stroke)
      drawArc(color, cycle * 360f - 90f, 120f, useCenter = false, style = stroke)
    }
  }
}

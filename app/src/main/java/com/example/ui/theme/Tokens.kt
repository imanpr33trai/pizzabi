package com.example.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Motion tokens used by PIZZORA primitives.
 *
 * Durations are expressed in milliseconds so they can be passed directly to Compose animation
 * specs. Easings are shared instances to avoid re-allocating them on every recomposition.
 */
@Immutable
data class PizzoraMotionTokens(
  val durationInstant: Int = 80,
  val durationFast: Int = 150,
  val durationMedium: Int = 250,
  val durationSlow: Int = 400,
  val durationEmphasized: Int = 600,
  val easingStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
  val easingEmphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f),
  val easingDecelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f),
  val easingAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f),
  val pressScale: Float = 0.98f,
  val selectedScale: Float = 1.02f,
)

/** Elevation tokens, from flat surfaces to transient overlays. */
@Immutable
data class PizzoraElevationTokens(
  val level0: Dp = 0.dp,
  val level1: Dp = 1.dp,
  val level2: Dp = 2.dp,
  val level3: Dp = 4.dp,
  val level4: Dp = 8.dp,
  val level5: Dp = 12.dp,
  val cardRest: Dp = 1.dp,
  val cardPressed: Dp = 4.dp,
  val buttonPressed: Dp = 2.dp,
  val modal: Dp = 8.dp,
)

/** 4dp-based spacing scale. */
@Immutable
data class PizzoraSpacingTokens(
  val none: Dp = 0.dp,
  val space2: Dp = 2.dp,
  val space4: Dp = 4.dp,
  val space8: Dp = 8.dp,
  val space12: Dp = 12.dp,
  val space16: Dp = 16.dp,
  val space20: Dp = 20.dp,
  val space24: Dp = 24.dp,
  val space32: Dp = 32.dp,
  val space40: Dp = 40.dp,
  val space48: Dp = 48.dp,
  val space64: Dp = 64.dp,
)

/** Sizing tokens for controls, icons, and loading affordances. */
@Immutable
data class PizzoraSizingTokens(
  val touchTargetMin: Dp = 48.dp,
  val buttonHeight: Dp = 48.dp,
  val iconButtonSize: Dp = 48.dp,
  val iconSmall: Dp = 16.dp,
  val chipIcon: Dp = 18.dp,
  val iconMedium: Dp = 20.dp,
  val iconLarge: Dp = 24.dp,
  val chipHeight: Dp = 32.dp,
  val loadingIndicator: Dp = 18.dp,
)

/** Radius and border tokens. */
@Immutable
data class PizzoraShapeTokens(
  val radiusSmall: Dp = 10.dp,
  val radiusMedium: Dp = 16.dp,
  val radiusLarge: Dp = 22.dp,
  val radiusPill: Dp = 999.dp,
  val borderThin: Dp = 1.dp,
  val borderThick: Dp = 2.dp,
  val borderFocused: Dp = 2.dp,
)

/** Layering tokens, expressed as graphics-layer translation Z values. */
@Immutable
data class PizzoraZIndexTokens(
  val base: Float = 0f,
  val raised: Float = 1f,
  val sticky: Float = 4f,
  val overlay: Float = 8f,
  val modal: Float = 16f,
  val toast: Float = 32f,
)

/** Type scale tokens consumed by [PizzoraTypography]. */
@Immutable
data class PizzoraTypographyTokens(
  val displaySmallSize: TextUnit = 32.sp,
  val displaySmallLineHeight: TextUnit = 38.sp,
  val headlineMediumSize: TextUnit = 26.sp,
  val headlineMediumLineHeight: TextUnit = 32.sp,
  val headlineSmallSize: TextUnit = 22.sp,
  val headlineSmallLineHeight: TextUnit = 28.sp,
  val titleLargeSize: TextUnit = 20.sp,
  val titleLargeLineHeight: TextUnit = 26.sp,
  val titleMediumSize: TextUnit = 16.sp,
  val titleMediumLineHeight: TextUnit = 22.sp,
  val bodyLargeSize: TextUnit = 16.sp,
  val bodyLargeLineHeight: TextUnit = 24.sp,
  val bodyMediumSize: TextUnit = 14.sp,
  val bodyMediumLineHeight: TextUnit = 20.sp,
  val labelLargeSize: TextUnit = 14.sp,
  val labelLargeLineHeight: TextUnit = 20.sp,
  val labelMediumSize: TextUnit = 12.sp,
  val labelMediumLineHeight: TextUnit = 16.sp,
  val monoBodySize: TextUnit = 14.sp,
  val monoBodyLineHeight: TextUnit = 20.sp,
  val monoTitleSize: TextUnit = 18.sp,
  val monoTitleLineHeight: TextUnit = 24.sp,
)

/** Durations and offsets for transient feedback surfaces. Durations are in milliseconds. */
@Immutable
data class PizzoraFeedbackTokens(
  val snackbarShortMs: Int = 3_000,
  val snackbarMediumMs: Int = 5_000,
  val snackbarLongMs: Int = 10_000,
  val toastDurationMs: Int = 2_000,
  val toastVerticalOffset: Dp = 48.dp,
)

/** The token groups a PIZZORA component reads from [LocalPizzoraTokens]. */
@Immutable
interface PizzoraTokenSet {
  val motion: PizzoraMotionTokens
  val elevation: PizzoraElevationTokens
  val spacing: PizzoraSpacingTokens
  val sizing: PizzoraSizingTokens
  val shape: PizzoraShapeTokens
  val zIndex: PizzoraZIndexTokens
  val typography: PizzoraTypographyTokens
  val feedback: PizzoraFeedbackTokens
}

/** Copyable token set for previews and tests that need to override a single token group. */
@Immutable
data class PizzoraTokenValues(
  override val motion: PizzoraMotionTokens = PizzoraTokens.motion,
  override val elevation: PizzoraElevationTokens = PizzoraTokens.elevation,
  override val spacing: PizzoraSpacingTokens = PizzoraTokens.spacing,
  override val sizing: PizzoraSizingTokens = PizzoraTokens.sizing,
  override val shape: PizzoraShapeTokens = PizzoraTokens.shape,
  override val zIndex: PizzoraZIndexTokens = PizzoraTokens.zIndex,
  override val typography: PizzoraTypographyTokens = PizzoraTokens.typography,
  override val feedback: PizzoraFeedbackTokens = PizzoraTokens.feedback,
) : PizzoraTokenSet

/**
 * Canonical PIZZORA design tokens.
 *
 * Components read these through [LocalPizzoraTokens] so previews and tests can override the token
 * set with [PizzoraTokenValues], while non-composable code can access the defaults directly.
 */
object PizzoraTokens : PizzoraTokenSet {
  override val motion: PizzoraMotionTokens = PizzoraMotionTokens()
  override val elevation: PizzoraElevationTokens = PizzoraElevationTokens()
  override val spacing: PizzoraSpacingTokens = PizzoraSpacingTokens()
  override val sizing: PizzoraSizingTokens = PizzoraSizingTokens()
  override val shape: PizzoraShapeTokens = PizzoraShapeTokens()
  override val zIndex: PizzoraZIndexTokens = PizzoraZIndexTokens()
  override val typography: PizzoraTypographyTokens = PizzoraTypographyTokens()
  override val feedback: PizzoraFeedbackTokens = PizzoraFeedbackTokens()
}

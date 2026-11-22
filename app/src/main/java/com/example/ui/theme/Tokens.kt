package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class MotionTokens(
    // Durations
    val durationUltraFast: Int = 50,      // ms
    val durationFast: Int = 100,          // ms
    val durationMedium: Int = 200,        // ms
    val durationSlow: Int = 300,          // ms
    val durationUltraSlow: Int = 500,     // ms

    // Easings (using standard Material 3 easing curves)
    val easingStandard: String = "cubic-bezier(0.2, 0.0, 0.38, 0.9)",
    val easingAccelerate: String = "cubic-bezier(0.4, 0.0, 1, 1)",
    val easingDecelerate: String = "cubic-bezier(0.0, 0.0, 0.2, 1)",
    val easingEmphasized: String = "cubic-bezier(0.05, 0.7, 0.1, 1.0)",
)

@Immutable
data class ElevationTokens(
    val level0: Dp = 0.dp,
    val level1: Dp = 1.dp,
    val level2: Dp = 3.dp,
    val level3: Dp = 6.dp,
    val level4: Dp = 8.dp,
    val level5: Dp = 12.dp,
    // KDS-specific elevations
    val kdsCard: Dp = 4.dp,
    val kdsModal: Dp = 16.dp,
    val kdsToast: Dp = 8.dp,
)

@Immutable
data class SpacingTokens(
    val space0: Dp = 0.dp,
    val space1: Dp = 4.dp,
    val space2: Dp = 8.dp,
    val space3: Dp = 12.dp,
    val space4: Dp = 16.dp,
    val space5: Dp = 24.dp,
    val space6: Dp = 32.dp,
    val space7: Dp = 48.dp,
    val space8: Dp = 64.dp,
    // Semantic spacing aliases
    val spacingXxs: Dp = 4.dp,
    val spacingXs: Dp = 8.dp,
    val spacingSm: Dp = 12.dp,
    val spacingMd: Dp = 16.dp,
    val spacingLg: Dp = 24.dp,
    val spacingXl: Dp = 32.dp,
    val spacingXxl: Dp = 48.dp,
)

@Immutable
data class SizingTokens(
    // Touch target minimums
    val touchTargetMin: Dp = 48.dp,
    val touchTargetComfortable: Dp = 56.dp,

    // Icon sizes
    val iconXs: Dp = 16.dp,
    val iconSm: Dp = 20.dp,
    val iconMd: Dp = 24.dp,
    val iconLg: Dp = 32.dp,
    val iconXl: Dp = 40.dp,

    // Component heights
    val heightXs: Dp = 28.dp,
    val heightSm: Dp = 36.dp,
    val heightMd: Dp = 44.dp,
    val heightLg: Dp = 52.dp,
    val heightXl: Dp = 60.dp,

    // Width constraints
    val widthMaxContent: Dp = 480.dp,
    val widthMaxForm: Dp = 360.dp,
)

@Immutable
data class BorderTokens(
    val widthHairline: Dp = 0.5.dp,
    val widthThin: Dp = 1.dp,
    val widthMedium: Dp = 2.dp,
    val widthThick: Dp = 3.dp,
)

@Immutable
data class RadiusTokens(
    val radiusNone: Dp = 0.dp,
    val radiusXs: Dp = 4.dp,
    val radiusSm: Dp = 8.dp,
    val radiusMd: Dp = 12.dp,
    val radiusLg: Dp = 16.dp,
    val radiusXl: Dp = 24.dp,
    val radiusFull: Dp = 9999.dp,
    // Semantic radius aliases (matching PizzoraShapes)
    val radiusSmall: Dp = 10.dp,
    val radiusMedium: Dp = 16.dp,
    val radiusLarge: Dp = 22.dp,
)

@Immutable
data class ZIndexTokens(
    val base: Int = 0,
    val dropdown: Int = 100,
    val sticky: Int = 200,
    val modal: Int = 300,
    val popover: Int = 400,
    val tooltip: Int = 500,
    val toast: Int = 600,
    // KDS-specific
    val kdsOverlay: Int = 700,
    val kdsBanner: Int = 800,
)

@Immutable
data class PizzoraTokens(
    val motion: MotionTokens = MotionTokens(),
    val elevation: ElevationTokens = ElevationTokens(),
    val spacing: SpacingTokens = SpacingTokens(),
    val sizing: SizingTokens = SizingTokens(),
    val border: BorderTokens = BorderTokens(),
    val radius: RadiusTokens = RadiusTokens(),
    val zIndex: ZIndexTokens = ZIndexTokens(),
) {
    companion object {
        val Default = PizzoraTokens()
    }
}
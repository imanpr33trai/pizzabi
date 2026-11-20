package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Warm Artisanal Pizzeria (Light)
val CreamBackground = Color(0xFFFFF9F3)
val PureWhiteSurface = Color(0xFFFFFFFF)
val OvenStoneSurfaceVariant = Color(0xFFF5EBE0)
val SanMarzanoTerracotta = Color(0xFFC83E2B)
val FreshBasilGreen = Color(0xFF2D6A4F)
val GoldenCrustAmber = Color(0xFFD97706)
val CharredInk = Color(0xFF1E1916)
val WarmTaupe = Color(0xFF6B5E54)
val DoughOutline = Color(0xFFDFD3C5)
val TerracottaContainer = Color(0xFFFFDAD3)
val OnTerracottaContainerLight = Color(0xFF5B160C)
val BasilContainer = Color(0xFFD6F0E0)
val OnBasilContainerLight = Color(0xFF0E3A25)
val AmberContainer = Color(0xFFFEF3C7)
val OnAmberContainerLight = Color(0xFF78350F)
val CrimsonErrorLight = Color(0xFFB91C1C)
val LightErrorContainer = Color(0xFFFDE8E8)
val LightOnErrorContainer = Color(0xFF7F1D1D)
val LightSelectedSurface = Color(0xFFFDF0EC)
val LightOutlineInteractive = Color(0xFF85766A)

// High-Contrast Dark KDS & Dark Mode
val EspressoBackground = Color(0xFF121110)
val DarkRoastSurface = Color(0xFF1D1A18)
val SmokedSlateSurfaceVariant = Color(0xFF2A2522)
val EmberTerracotta = Color(0xFFEF644F)
val MintBasil = Color(0xFF52B788)
val BrightAmber = Color(0xFFFBBF24)
val OverdueCrimson = Color(0xFFF87171)
val WarmParchmentText = Color(0xFFF7F2EC)
val MutedStoneText = Color(0xFFC4B7AA)
val DarkOutline = Color(0xFF3F3732)
val DarkErrorContainer = Color(0xFF4C1616)
val DarkOnErrorContainer = Color(0xFFFFDAD6)
val DarkWarningContainer = Color(0xFF3E2D12)
val DarkOnWarningContainer = Color(0xFFFDE68A)
val DarkSelectedSurface = Color(0xFF311F1C)
val DarkOutlineInteractive = Color(0xFF75685F)
val KdsSkyPrep = Color(0xFF38BDF8)

@Immutable
data class PizzoraSemanticColors(
  val kdsPendingAmber: Color,
  val kdsPrepSky: Color,
  val kdsReadyEmerald: Color,
  val kdsOverdueCrimson: Color,
  val vegBadgeGreen: Color,
  val nonVegBadgeTerracotta: Color,
  val warningContainer: Color,
  val onWarningContainer: Color,
  val selectedSurface: Color,
  val outlineInteractive: Color,
)

val LightSemanticColors = PizzoraSemanticColors(
  kdsPendingAmber = GoldenCrustAmber,
  kdsPrepSky = Color(0xFF0284C7),
  kdsReadyEmerald = FreshBasilGreen,
  kdsOverdueCrimson = CrimsonErrorLight,
  vegBadgeGreen = FreshBasilGreen,
  nonVegBadgeTerracotta = SanMarzanoTerracotta,
  warningContainer = AmberContainer,
  onWarningContainer = OnAmberContainerLight,
  selectedSurface = LightSelectedSurface,
  outlineInteractive = LightOutlineInteractive,
)

val DarkSemanticColors = PizzoraSemanticColors(
  kdsPendingAmber = BrightAmber,
  kdsPrepSky = KdsSkyPrep,
  kdsReadyEmerald = MintBasil,
  kdsOverdueCrimson = OverdueCrimson,
  vegBadgeGreen = MintBasil,
  nonVegBadgeTerracotta = EmberTerracotta,
  warningContainer = DarkWarningContainer,
  onWarningContainer = DarkOnWarningContainer,
  selectedSurface = DarkSelectedSurface,
  outlineInteractive = DarkOutlineInteractive,
)

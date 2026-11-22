package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val PizzoraLightColorScheme = lightColorScheme(
  primary = SanMarzanoTerracotta,
  onPrimary = Color.White,
  primaryContainer = TerracottaContainer,
  onPrimaryContainer = Color(0xFF5C150C),
  secondary = FreshBasilGreen,
  onSecondary = Color.White,
  secondaryContainer = BasilContainer,
  onSecondaryContainer = Color(0xFF103825),
  tertiary = GoldenCrustAmber,
  onTertiary = Color.White,
  tertiaryContainer = AmberContainer,
  onTertiaryContainer = Color(0xFF6B3900),
  background = CreamBackground,
  onBackground = CharredInk,
  surface = PureWhiteSurface,
  onSurface = CharredInk,
  surfaceVariant = OvenStoneSurfaceVariant,
  onSurfaceVariant = WarmTaupe,
  outline = DoughOutline,
  outlineVariant = DoughOutline,
  error = CrimsonErrorLight,
  onError = Color.White,
)

private val PizzoraDarkColorScheme = darkColorScheme(
  primary = EmberTerracotta,
  onPrimary = Color(0xFF280804),
  primaryContainer = Color(0xFF5B1E14),
  onPrimaryContainer = Color(0xFFFFDAD3),
  secondary = MintBasil,
  onSecondary = Color(0xFF07291A),
  secondaryContainer = Color(0xFF16422D),
  onSecondaryContainer = Color(0xFFC8F3DB),
  tertiary = BrightAmber,
  onTertiary = Color(0xFF332000),
  tertiaryContainer = Color(0xFF553A00),
  onTertiaryContainer = Color(0xFFFFEEB8),
  background = EspressoBackground,
  onBackground = WarmParchmentText,
  surface = DarkRoastSurface,
  onSurface = WarmParchmentText,
  surfaceVariant = SmokedSlateSurfaceVariant,
  onSurfaceVariant = MutedStoneText,
  outline = DarkOutline,
  outlineVariant = DarkOutline,
  error = OverdueCrimson,
  onError = Color(0xFF3B0909),
)

val PizzoraShapes = Shapes(
  small = RoundedCornerShape(PizzoraTokens.shape.radiusSmall),
  medium = RoundedCornerShape(PizzoraTokens.shape.radiusMedium),
  large = RoundedCornerShape(PizzoraTokens.shape.radiusLarge),
)

val LocalPizzoraSemanticColors = staticCompositionLocalOf { LightSemanticColors }

/** Provides the active design token set to PIZZORA primitives. */
val LocalPizzoraTokens = staticCompositionLocalOf { PizzoraTokens }

@Composable
fun PizzoraTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  kdsMode: Boolean = false,
  content: @Composable () -> Unit,
) {
  val useDark = kdsMode || darkTheme
  val colorScheme = if (useDark) PizzoraDarkColorScheme else PizzoraLightColorScheme
  val semanticColors = if (useDark) DarkSemanticColors else LightSemanticColors

  CompositionLocalProvider(
    LocalPizzoraSemanticColors provides semanticColors,
    LocalPizzoraTokens provides PizzoraTokens,
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = PizzoraTypography,
      shapes = PizzoraShapes,
      content = content,
    )
  }
}

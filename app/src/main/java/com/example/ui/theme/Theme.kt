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
import androidx.compose.ui.unit.dp
import com.example.ui.components.LocalReducedMotion
import com.example.ui.components.rememberSystemReducedMotion

internal val PizzoraLightColorScheme = lightColorScheme(
  primary = SanMarzanoTerracotta,
  onPrimary = Color.White,
  primaryContainer = TerracottaContainer,
  onPrimaryContainer = OnTerracottaContainerLight,
  secondary = FreshBasilGreen,
  onSecondary = Color.White,
  secondaryContainer = BasilContainer,
  onSecondaryContainer = OnBasilContainerLight,
  tertiary = GoldenCrustAmber,
  onTertiary = Color.White,
  tertiaryContainer = AmberContainer,
  onTertiaryContainer = OnAmberContainerLight,
  background = CreamBackground,
  onBackground = CharredInk,
  surface = PureWhiteSurface,
  onSurface = CharredInk,
  surfaceVariant = OvenStoneSurfaceVariant,
  onSurfaceVariant = WarmTaupe,
  outline = DoughOutline,
  outlineVariant = LightOutlineInteractive,
  error = CrimsonErrorLight,
  onError = Color.White,
  errorContainer = LightErrorContainer,
  onErrorContainer = LightOnErrorContainer,
)

internal val PizzoraDarkColorScheme = darkColorScheme(
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
  tertiaryContainer = DarkWarningContainer,
  onTertiaryContainer = DarkOnWarningContainer,
  background = EspressoBackground,
  onBackground = WarmParchmentText,
  surface = DarkRoastSurface,
  onSurface = WarmParchmentText,
  surfaceVariant = SmokedSlateSurfaceVariant,
  onSurfaceVariant = MutedStoneText,
  outline = DarkOutline,
  outlineVariant = DarkOutlineInteractive,
  error = OverdueCrimson,
  onError = Color(0xFF3B0909),
  errorContainer = DarkErrorContainer,
  onErrorContainer = DarkOnErrorContainer,
)

val PizzoraShapes = Shapes(
  extraSmall = RoundedCornerShape(4.dp),
  small = RoundedCornerShape(8.dp),
  medium = RoundedCornerShape(12.dp),
  large = RoundedCornerShape(16.dp),
  extraLarge = RoundedCornerShape(20.dp),
)

val LocalPizzoraSemanticColors = staticCompositionLocalOf { LightSemanticColors }

@Composable
fun PizzoraTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  kdsMode: Boolean = false,
  reducedMotion: Boolean = rememberSystemReducedMotion(),
  content: @Composable () -> Unit,
) {
  val useDark = kdsMode || darkTheme
  val colorScheme = if (useDark) PizzoraDarkColorScheme else PizzoraLightColorScheme
  val semanticColors = if (useDark) DarkSemanticColors else LightSemanticColors

  CompositionLocalProvider(
    LocalPizzoraSemanticColors provides semanticColors,
    LocalReducedMotion provides reducedMotion,
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = PizzoraTypography,
      shapes = PizzoraShapes,
      content = content,
    )
  }
}

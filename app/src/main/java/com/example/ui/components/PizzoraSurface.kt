package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.example.ui.theme.LocalPizzoraTokens

/** Semantic surface tones mapped onto the active PIZZORA color scheme. */
enum class PizzoraSurfaceTone {
  Background,
  Surface,
  SurfaceVariant,
  PrimaryContainer,
  SecondaryContainer,
  TertiaryContainer,
  ErrorContainer,
  InverseSurface,
}

/**
 * Token-driven replacement for raw Material [Surface] usage.
 *
 * The tone selects a semantic container/content color pair, while [elevation] comes from the
 * PIZZORA elevation scale. The surface adapts automatically to light, dark, and KDS themes.
 */
@Composable
fun PizzoraSurface(
  modifier: Modifier = Modifier,
  tone: PizzoraSurfaceTone = PizzoraSurfaceTone.Surface,
  elevation: Dp = LocalPizzoraTokens.current.elevation.level0,
  shape: Shape = MaterialTheme.shapes.medium,
  border: BorderStroke? = null,
  contentColor: Color? = null,
  testTag: String = "pizzora_surface",
  content: @Composable ColumnScope.() -> Unit,
) {
  val tokens = LocalPizzoraTokens.current
  val colorScheme = MaterialTheme.colorScheme
  val containerColor = when (tone) {
    PizzoraSurfaceTone.Background -> colorScheme.background
    PizzoraSurfaceTone.Surface -> colorScheme.surface
    PizzoraSurfaceTone.SurfaceVariant -> colorScheme.surfaceVariant
    PizzoraSurfaceTone.PrimaryContainer -> colorScheme.primaryContainer
    PizzoraSurfaceTone.SecondaryContainer -> colorScheme.secondaryContainer
    PizzoraSurfaceTone.TertiaryContainer -> colorScheme.tertiaryContainer
    PizzoraSurfaceTone.ErrorContainer -> colorScheme.errorContainer
    PizzoraSurfaceTone.InverseSurface -> colorScheme.inverseSurface
  }
  val mappedContentColor = when (tone) {
    PizzoraSurfaceTone.Background -> colorScheme.onBackground
    PizzoraSurfaceTone.Surface -> colorScheme.onSurface
    PizzoraSurfaceTone.SurfaceVariant -> colorScheme.onSurfaceVariant
    PizzoraSurfaceTone.PrimaryContainer -> colorScheme.onPrimaryContainer
    PizzoraSurfaceTone.SecondaryContainer -> colorScheme.onSecondaryContainer
    PizzoraSurfaceTone.TertiaryContainer -> colorScheme.onTertiaryContainer
    PizzoraSurfaceTone.ErrorContainer -> colorScheme.onErrorContainer
    PizzoraSurfaceTone.InverseSurface -> colorScheme.inverseOnSurface
  }

  Surface(
    modifier = modifier.testTag(testTag),
    shape = shape,
    color = containerColor,
    contentColor = contentColor ?: mappedContentColor,
    tonalElevation = elevation,
    shadowElevation = tokens.elevation.level0,
    border = border,
  ) {
    Column(content = content)
  }
}

package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.example.ui.theme.LocalPizzoraTokens

/** Visual weight of [PizzoraDivider]. */
enum class PizzoraDividerWeight {
  Hairline,
  Thin,
}

/**
 * Token-driven horizontal divider.
 *
 * Uses the PIZZORA border tokens for thickness and theme outline colors so dividers look
 * consistent across light, dark, and KDS themes. Vertical and horizontal spacing come from the
 * token spacing scale.
 */
@Composable
fun PizzoraDivider(
  modifier: Modifier = Modifier,
  weight: PizzoraDividerWeight = PizzoraDividerWeight.Hairline,
  color: Color? = null,
  horizontalPadding: Dp? = null,
  verticalPadding: Dp? = null,
  testTag: String = "pizzora_divider",
) {
  val tokens = LocalPizzoraTokens.current
  val colorScheme = MaterialTheme.colorScheme
  val thickness = when (weight) {
    PizzoraDividerWeight.Hairline -> tokens.shape.borderThin
    PizzoraDividerWeight.Thin -> tokens.shape.borderThick
  }
  val resolvedColor = color ?: colorScheme.outlineVariant

  Box(
    modifier = modifier
      .fillMaxWidth()
      .then(
        if (horizontalPadding != null) Modifier.padding(horizontal = horizontalPadding) else Modifier,
      )
      .then(
        if (verticalPadding != null) Modifier.padding(vertical = verticalPadding) else Modifier,
      )
      .height(thickness)
      .background(resolvedColor)
      .testTag(testTag),
  )
}

/**
 * Convenience wrapper that applies the standard token vertical spacing around the divider.
 */
@Composable
fun PizzoraSpacedDivider(
  modifier: Modifier = Modifier,
  weight: PizzoraDividerWeight = PizzoraDividerWeight.Hairline,
  color: Color? = null,
  testTag: String = "pizzora_spaced_divider",
) {
  val tokens = LocalPizzoraTokens.current
  PizzoraDivider(
    modifier = modifier,
    weight = weight,
    color = color,
    verticalPadding = tokens.spacing.space8,
    testTag = testTag,
  )
}

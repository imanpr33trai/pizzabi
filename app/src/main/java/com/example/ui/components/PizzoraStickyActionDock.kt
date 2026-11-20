package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Floating sticky bottom action dock obeying Section 6.5 surface rules:
 * zero tonalElevation tint drift, crisp 1dp top outline hairline, subtle shadow,
 * and single deduplicated navigationBarsPadding().
 */
@Composable
fun PizzoraStickyActionDock(
  modifier: Modifier = Modifier,
  applyNavigationBarsPadding: Boolean = true,
  topSlot: (@Composable ColumnScope.() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val isDarkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  val shadow = if (isDarkSurface) 2.dp else 6.dp

  Surface(
    color = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 0.dp,
    shadowElevation = shadow,
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .let { base -> if (applyNavigationBarsPadding) base.navigationBarsPadding() else base },
    ) {
      HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
      if (topSlot != null) {
        topSlot()
      }
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
      )
    }
  }
}

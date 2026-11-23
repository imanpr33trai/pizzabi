package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.example.ui.theme.LocalPizzoraTokens

/**
 * Token-driven empty state with an illustration, title, subtitle, and optional action.
 *
 * Composes [PizzoraSurface] and [PizzoraButton] so spacing, typography, and colors stay
 * consistent across every screen that needs an empty state. Adapts automatically to light, dark,
 * and KDS themes.
 */
@Composable
fun PizzoraEmptyState(
  title: String,
  subtitle: String,
  modifier: Modifier = Modifier,
  illustration: ImageVector? = null,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  testTag: String = "pizzora_empty_state",
) {
  val tokens = LocalPizzoraTokens.current

  PizzoraSurface(
    modifier = modifier.fillMaxWidth(),
    tone = PizzoraSurfaceTone.SurfaceVariant,
    testTag = testTag,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(tokens.spacing.space24),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(tokens.spacing.space8),
    ) {
      if (illustration != null) {
        Icon(
          imageVector = illustration,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier
            .size(tokens.spacing.space48)
            .testTag("${testTag}_illustration"),
        )
      }
      Text(
        text = title,
        modifier = Modifier.testTag("${testTag}_title"),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
      )
      Text(
        text = subtitle,
        modifier = Modifier.testTag("${testTag}_subtitle"),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      if (actionLabel != null && onAction != null) {
        PizzoraButton(
          text = actionLabel,
          onClick = onAction,
          modifier = Modifier.padding(top = tokens.spacing.space8),
          testTag = "${testTag}_action",
        )
      }
    }
  }
}

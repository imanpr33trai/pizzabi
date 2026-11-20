package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalPizzoraSemanticColors

enum class PizzoraBannerTone {
  ERROR,
  WARNING,
  SUCCESS,
  INFO,
}

@Composable
fun PizzoraStatusBanner(
  message: String,
  tone: PizzoraBannerTone,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  actionTestTag: String? = null,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val (containerColor: Color, contentColor: Color, defaultIcon: ImageVector) = when (tone) {
    PizzoraBannerTone.ERROR -> Triple(
      MaterialTheme.colorScheme.errorContainer,
      MaterialTheme.colorScheme.onErrorContainer,
      Icons.Filled.Error,
    )
    PizzoraBannerTone.WARNING -> Triple(
      semantic.warningContainer,
      semantic.onWarningContainer,
      Icons.Filled.Warning,
    )
    PizzoraBannerTone.SUCCESS -> Triple(
      MaterialTheme.colorScheme.secondaryContainer,
      MaterialTheme.colorScheme.onSecondaryContainer,
      Icons.Filled.CheckCircle,
    )
    PizzoraBannerTone.INFO -> Triple(
      MaterialTheme.colorScheme.surfaceVariant,
      MaterialTheme.colorScheme.onSurfaceVariant,
      Icons.Filled.Info,
    )
  }

  Surface(
    color = containerColor,
    contentColor = contentColor,
    tonalElevation = 0.dp,
    shape = MaterialTheme.shapes.small,
    modifier = modifier
      .fillMaxWidth()
      .semantics(mergeDescendants = false) {},
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = icon ?: defaultIcon,
        contentDescription = null,
        tint = contentColor,
        modifier = Modifier.size(20.dp),
      )
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
        modifier = Modifier.weight(1f),
      )
      if (!actionLabel.isNullOrBlank() && onAction != null) {
        val actionModifier = Modifier
          .minimumInteractiveComponentSize()
          .let { base -> if (actionTestTag != null) base.testTag(actionTestTag) else base }
        TextButton(
          onClick = onAction,
          modifier = actionModifier,
        ) {
          Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
          )
        }
      }
    }
  }
}

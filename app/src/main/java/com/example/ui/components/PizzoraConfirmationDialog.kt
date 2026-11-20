package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.LocalPizzoraSemanticColors

@Composable
fun PizzoraConfirmationDialog(
  title: String,
  body: String,
  confirmLabel: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  dismissLabel: String = stringResource(R.string.action_cancel),
  isDestructive: Boolean = false,
  isSubmitting: Boolean = false,
  confirmTestTag: String? = null,
  dismissTestTag: String? = null,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val confirmColors = if (isDestructive) {
    ButtonDefaults.buttonColors(
      containerColor = MaterialTheme.colorScheme.error,
      contentColor = MaterialTheme.colorScheme.onError,
    )
  } else {
    ButtonDefaults.buttonColors(
      containerColor = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
    )
  }
  val spinnerTint = if (isDestructive) {
    MaterialTheme.colorScheme.onError
  } else {
    MaterialTheme.colorScheme.onPrimary
  }

  AlertDialog(
    onDismissRequest = {
      if (!isSubmitting) {
        onDismiss()
      }
    },
    modifier = modifier,
    shape = MaterialTheme.shapes.extraLarge,
    containerColor = MaterialTheme.colorScheme.surface,
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    tonalElevation = 0.dp,
    title = {
      Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
      )
    },
    text = {
      Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
      )
    },
    confirmButton = {
      val confirmModifier = Modifier
        .minimumInteractiveComponentSize()
        .let { base -> if (confirmTestTag != null) base.testTag(confirmTestTag) else base }
      Button(
        onClick = onConfirm,
        enabled = !isSubmitting,
        colors = confirmColors,
        modifier = confirmModifier,
      ) {
        if (isSubmitting) {
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = spinnerTint,
          )
        } else {
          Text(
            text = confirmLabel,
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }
    },
    dismissButton = {
      val dismissModifier = Modifier
        .minimumInteractiveComponentSize()
        .let { base -> if (dismissTestTag != null) base.testTag(dismissTestTag) else base }
      OutlinedButton(
        onClick = onDismiss,
        enabled = !isSubmitting,
        border = BorderStroke(1.dp, semantic.outlineInteractive),
        modifier = dismissModifier,
      ) {
        Text(
          text = dismissLabel,
          style = MaterialTheme.typography.labelLarge,
        )
      }
    },
  )
}

package com.example.ui.components

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.VisualTransformation
import com.example.ui.theme.LocalPizzoraTokens

/**
 * Token-driven text field for PIZZORA forms.
 *
 * The field uses the token radius, typography, semantic colors, and 48dp minimum touch target.
 * Child tags derive from [testTag] as `<testTag>_label` and `<testTag>_leading_icon`; a supplied
 * [trailingIcon] is responsible for its own tag and touch target.
 */
@Composable
fun PizzoraTextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  leadingIcon: ImageVector? = null,
  trailingIcon: @Composable (() -> Unit)? = null,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
  visualTransformation: VisualTransformation = VisualTransformation.None,
  enabled: Boolean = true,
  readOnly: Boolean = false,
  singleLine: Boolean = true,
  isError: Boolean = false,
  supportingText: @Composable (() -> Unit)? = null,
  testTag: String = "pizzora_text_field",
) {
  val tokens = LocalPizzoraTokens.current
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier
      .defaultMinSize(minHeight = tokens.sizing.touchTargetMin)
      .testTag(testTag),
    enabled = enabled,
    readOnly = readOnly,
    label = {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("${testTag}_label"),
      )
    },
    leadingIcon = if (leadingIcon == null) {
      null
    } else {
      {
        Icon(
          imageVector = leadingIcon,
          contentDescription = null,
          modifier = Modifier.testTag("${testTag}_leading_icon"),
        )
      }
    },
    trailingIcon = trailingIcon,
    supportingText = supportingText,
    isError = isError,
    visualTransformation = visualTransformation,
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
    singleLine = singleLine,
    shape = RoundedCornerShape(tokens.shape.radiusMedium),
    colors = OutlinedTextFieldDefaults.colors(),
    textStyle = MaterialTheme.typography.bodyLarge,
  )
}

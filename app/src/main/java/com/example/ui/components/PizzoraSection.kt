package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import com.example.ui.theme.LocalPizzoraTokens

/**
 * Titled group of related content with an optional text action and trailing divider.
 *
 * Header, content, and divider spacing all come from the PIZZORA spacing tokens. The action is
 * omitted unless both [actionLabel] and [onActionClick] are supplied. Child tags derive from
 * [testTag] as `<testTag>_title`, `<testTag>_action`, `<testTag>_content`, and
 * `<testTag>_divider`.
 */
@Composable
fun PizzoraSection(
  title: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onActionClick: (() -> Unit)? = null,
  showDivider: Boolean = false,
  testTag: String = "pizzora_section",
  content: @Composable ColumnScope.() -> Unit,
) {
  val tokens = LocalPizzoraTokens.current
  val action = actionLabel
  val onAction = onActionClick

  PizzoraSurface(
    modifier = modifier.fillMaxWidth(),
    tone = PizzoraSurfaceTone.Background,
    elevation = tokens.elevation.level0,
    shape = RectangleShape,
    testTag = testTag,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = tokens.spacing.space16),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
          .weight(1f)
          .testTag("${testTag}_title"),
      )
      if (action != null && onAction != null) {
        TextButton(
          onClick = onAction,
          modifier = Modifier
            .heightIn(min = tokens.sizing.touchTargetMin)
            .testTag("${testTag}_action"),
          contentPadding = PaddingValues(
            horizontal = tokens.spacing.space8,
            vertical = tokens.spacing.space4,
          ),
        ) {
          Text(text = action)
        }
      }
    }
    Spacer(modifier = Modifier.height(tokens.spacing.space8))
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = tokens.spacing.space16)
        .testTag("${testTag}_content"),
      verticalArrangement = Arrangement.spacedBy(tokens.spacing.space8),
      content = content,
    )
    if (showDivider) {
      PizzoraDivider(
        modifier = Modifier.padding(
          start = tokens.spacing.space16,
          top = tokens.spacing.space16,
          end = tokens.spacing.space16,
        ),
        testTag = "${testTag}_divider",
      )
    }
  }
}

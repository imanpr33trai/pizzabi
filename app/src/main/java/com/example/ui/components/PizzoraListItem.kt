package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.example.ui.theme.LocalPizzoraTokens

/**
 * Standard row for menus, orders, and settings lists.
 *
 * The row always reserves the minimum touch target and token padding. When [onClick] is supplied
 * the whole row becomes pressable; otherwise it stays inert. Leading and trailing content are
 * optional, and all child tags derive from [testTag] as `pizzora_list_item_<part>` equivalents.
 */
@Composable
fun PizzoraListItem(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  leadingIcon: ImageVector? = null,
  iconContentDescription: String? = null,
  trailing: @Composable (() -> Unit)? = null,
  onClick: (() -> Unit)? = null,
  enabled: Boolean = true,
  testTag: String = "pizzora_list_item",
) {
  val tokens = LocalPizzoraTokens.current
  val clickModifier = if (onClick != null) {
    Modifier.clickable(enabled = enabled, onClick = onClick)
  } else {
    Modifier
  }

  PizzoraSurface(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = tokens.sizing.touchTargetMin)
      .then(clickModifier),
    tone = PizzoraSurfaceTone.Background,
    elevation = tokens.elevation.level0,
    shape = RectangleShape,
    testTag = testTag,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = tokens.spacing.space16, vertical = tokens.spacing.space12),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (leadingIcon != null) {
        Icon(
          imageVector = leadingIcon,
          contentDescription = iconContentDescription,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier
            .size(tokens.sizing.iconLarge)
            .testTag("${testTag}_leading_icon"),
        )
      }
      Column(
        modifier = Modifier
          .weight(1f)
          .then(
            if (leadingIcon != null) {
              Modifier.padding(start = tokens.spacing.space12)
            } else {
              Modifier
            },
          ),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.testTag("${testTag}_title"),
        )
        if (subtitle != null) {
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("${testTag}_subtitle"),
          )
        }
      }
      if (trailing != null) {
        Row(
          modifier = Modifier
            .padding(start = tokens.spacing.space12)
            .testTag("${testTag}_trailing"),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          trailing()
        }
      }
    }
  }
}

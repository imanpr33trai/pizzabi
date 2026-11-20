package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.LocalPizzoraSemanticColors

/**
 * Accessible dietary badge (Indian food-standard square frame with inner circle).
 * Exposes explicit "Vegetarian" / "Non-vegetarian" semantics for TalkBack.
 */
@Composable
fun PizzoraDietaryIndicator(
  isVeg: Boolean,
  modifier: Modifier = Modifier,
  size: Dp = 16.dp,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val indicatorColor = if (isVeg) semantic.vegBadgeGreen else semantic.nonVegBadgeTerracotta
  val description = stringResource(
    if (isVeg) R.string.dietary_veg_cd else R.string.dietary_non_veg_cd,
  )

  Box(
    modifier = modifier
      .size(size)
      .semantics { contentDescription = description }
      .border(1.5.dp, indicatorColor, RoundedCornerShape(4.dp)),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .size(size * 0.5f)
        .clip(CircleShape)
        .background(indicatorColor),
    )
  }
}

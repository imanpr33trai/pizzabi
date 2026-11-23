package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.example.ui.theme.LocalPizzoraTokens
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.pizzora.contracts.Money

/**
 * Monospace INR price text with an animated count-up.
 *
 * Amounts are formatted via [Money.formatInr] using JetBrainsMono tabular figures. When [animate]
 * is enabled the displayed amount counts from zero to [paise] on first composition and from the
 * previous amount to the new one on later updates, using a medium-duration decelerate curve.
 * Negative amounts (refunds) are supported. Adapts to light, dark, and KDS themes.
 */
@Composable
fun PizzoraPriceText(
  paise: Long,
  modifier: Modifier = Modifier,
  style: TextStyle = PizzoraMonoBody,
  color: Color = Color.Unspecified,
  animate: Boolean = true,
  testTag: String = "pizzora_price_text",
) {
  val tokens = LocalPizzoraTokens.current
  var targetPaiseFloat by remember {
    mutableStateOf(if (animate) 0f else paise.toFloat())
  }
  LaunchedEffect(paise, animate) {
    targetPaiseFloat = paise.toFloat()
  }
  val animatedPaise by animateFloatAsState(
    targetValue = targetPaiseFloat,
    animationSpec = if (animate) {
      tween(
        durationMillis = tokens.motion.durationMedium,
        easing = tokens.motion.easingDecelerate,
      )
    } else {
      snap()
    },
    label = "PizzoraPriceCountUp",
  )
  val displayedPaise = when {
    !animate -> paise
    // Once the animation settles, use the exact Long rather than the Float approximation.
    animatedPaise == targetPaiseFloat -> paise
    else -> animatedPaise.toLong()
  }

  Text(
    text = Money.formatInr(displayedPaise),
    modifier = modifier.testTag(testTag),
    style = style,
    color = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/**
 * Larger [PizzoraPriceText] variant using the mono title style, suitable for totals and order
 * summaries.
 */
@Composable
fun PizzoraPriceTextLarge(
  paise: Long,
  modifier: Modifier = Modifier,
  color: Color = Color.Unspecified,
  animate: Boolean = true,
  testTag: String = "pizzora_price_text_large",
) {
  PizzoraPriceText(
    paise = paise,
    modifier = modifier,
    style = PizzoraMonoTitle,
    color = color,
    animate = animate,
    testTag = testTag,
  )
}

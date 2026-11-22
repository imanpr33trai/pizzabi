package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
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
 * Formats paise via [Money.formatInr] using the JetBrainsMono font (tabular numbers). When the
 * [paise] value changes, the displayed amount animates from the previous value to the new one
 * using [animateFloatAsState] with the medium-duration decelerate easing. Adapts to light, dark,
 * and KDS themes.
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
  var displayedPaise by remember { mutableStateOf(paise.toFloat()) }

  LaunchedEffect(paise) {
    if (!animate) {
      displayedPaise = paise.toFloat()
      return@LaunchedEffect
    }
    displayedPaise = paise.toFloat()
  }

  val animatedPaise by animateFloatAsState(
    targetValue = displayedPaise,
    animationSpec = tween(
      durationMillis = tokens.motion.durationMedium,
      easing = tokens.motion.easingDecelerate,
    ),
    label = "PizzoraPriceCountUp",
  )

  Text(
    text = Money.formatInr(animatedPaise.toLong()),
    modifier = modifier.testTag(testTag),
    style = style,
    color = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/**
 * Larger variant of [PizzoraPriceText] using the mono title style, suitable for totals and
 * order summaries.
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

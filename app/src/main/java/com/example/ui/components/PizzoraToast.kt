package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import com.example.ui.theme.LocalPizzoraTokens
import kotlinx.coroutines.delay

/** Where [PizzoraToastHost] places messages inside its bounds. */
enum class PizzoraToastPosition {
  Top,
  Bottom,
}

/** State for one lightweight, transient [PizzoraToast]. */
class PizzoraToastHostState {

  /** The visible message, or `null` when no toast is showing. */
  var currentMessage by mutableStateOf<String?>(null)
    private set

  /** Shows [message], replacing any toast already on screen. */
  fun show(message: String) {
    currentMessage = message
  }

  /** Removes the visible toast immediately. */
  fun dismiss() {
    currentMessage = null
  }
}

/** Remembers a toast host state across recompositions. */
@Composable
fun rememberPizzoraToastHostState(): PizzoraToastHostState = remember {
  PizzoraToastHostState()
}

/**
 * Hosts a transient [PizzoraToast] at [position] using token offsets and token z-index.
 *
 * The host applies its own bounds (pass `Modifier.fillMaxSize()` for a full-screen host) and keeps
 * [testTag] on the host node; the toast and its message derive `<testTag>_toast` and
 * `<testTag>_toast_message`.
 */
@Composable
fun PizzoraToastHost(
  state: PizzoraToastHostState,
  modifier: Modifier = Modifier,
  position: PizzoraToastPosition = PizzoraToastPosition.Bottom,
  testTag: String = "pizzora_toast_host",
) {
  val tokens = LocalPizzoraTokens.current
  Box(modifier = modifier.testTag(testTag)) {
    val message = state.currentMessage
    if (message != null) {
      LaunchedEffect(message) {
        delay(tokens.feedback.toastDurationMs.toLong())
        state.dismiss()
      }
      PizzoraToast(
        message = message,
        modifier = Modifier
          .align(if (position == PizzoraToastPosition.Top) Alignment.TopCenter else Alignment.BottomCenter)
          .padding(vertical = tokens.feedback.toastVerticalOffset)
          .zIndex(tokens.zIndex.toast),
        testTag = "${testTag}_toast",
      )
    }
  }
}

/**
 * Lightweight transient message styled with PIZZORA tokens and theme colors.
 *
 * Its message carries the derived tag `<testTag>_message`.
 */
@Composable
fun PizzoraToast(
  message: String,
  modifier: Modifier = Modifier,
  testTag: String = "pizzora_toast",
) {
  val tokens = LocalPizzoraTokens.current
  PizzoraSurface(
    modifier = modifier,
    tone = PizzoraSurfaceTone.InverseSurface,
    elevation = tokens.elevation.level5,
    shape = RoundedCornerShape(tokens.shape.radiusPill),
    testTag = testTag,
  ) {
    Row(
      modifier = Modifier.padding(
        horizontal = tokens.spacing.space24,
        vertical = tokens.spacing.space12,
      ),
    ) {
      Text(
        text = message,
        modifier = Modifier.testTag("${testTag}_message"),
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

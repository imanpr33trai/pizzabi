package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.ui.theme.LocalPizzoraTokens
import com.example.ui.theme.PizzoraFeedbackTokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Standardized lifetimes for a [PizzoraSnackbar], resolved from feedback tokens by its host. */
enum class PizzoraSnackbarDuration {
  Short,
  Medium,
  Long,
  Indefinite,
}

/** How a snackbar interaction finished, returned by [PizzoraSnackbarHostState.showSnackbar]. */
enum class PizzoraSnackbarResult {
  ActionPerformed,
  Dismissed,
}

/** The currently visible snackbar payload. */
data class PizzoraSnackbarData(
  val message: String,
  val actionLabel: String?,
  val withDismissAction: Boolean,
  val duration: PizzoraSnackbarDuration,
)

private fun PizzoraSnackbarDuration.durationMillis(feedback: PizzoraFeedbackTokens): Long = when (this) {
  PizzoraSnackbarDuration.Short -> feedback.snackbarShortMs.toLong()
  PizzoraSnackbarDuration.Medium -> feedback.snackbarMediumMs.toLong()
  PizzoraSnackbarDuration.Long -> feedback.snackbarLongMs.toLong()
  PizzoraSnackbarDuration.Indefinite -> 0L
}

/**
 * State for [PizzoraSnackbarHost]. Only one message is visible at a time; a new [showSnackbar]
 * waits for the current one to be actioned, swiped, or timed out.
 */
class PizzoraSnackbarHostState {

  /** The visible snackbar, or `null` when nothing is showing. */
  var currentSnackbarData by mutableStateOf<PizzoraSnackbarData?>(null)
    private set

  private var pendingResult: CompletableDeferred<PizzoraSnackbarResult>? = null
  private val mutex = Mutex()

  /**
   * Shows a message and suspends until it is actioned, dismissed, or timed out.
   *
   * Only one snackbar is visible at a time: if one is already showing, this call waits for it to
   * finish. The lifetime comes from the host's feedback tokens for [duration].
   */
  suspend fun showSnackbar(
    message: String,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: PizzoraSnackbarDuration = PizzoraSnackbarDuration.Medium,
  ): PizzoraSnackbarResult = mutex.withLock {
    try {
      pendingResult?.await()
      val deferred = CompletableDeferred<PizzoraSnackbarResult>()
      pendingResult = deferred
      currentSnackbarData = PizzoraSnackbarData(
        message = message,
        actionLabel = actionLabel,
        withDismissAction = withDismissAction,
        duration = duration,
      )
      deferred.await()
    } finally {
      pendingResult = null
      currentSnackbarData = null
    }
  }

  /** Dismisses the visible snackbar as [PizzoraSnackbarResult.Dismissed]. */
  fun dismiss() {
    pendingResult?.complete(PizzoraSnackbarResult.Dismissed)
  }

  /** Completes the visible snackbar as [PizzoraSnackbarResult.ActionPerformed]. */
  fun performAction() {
    pendingResult?.complete(PizzoraSnackbarResult.ActionPerformed)
  }
}

/** Remembers a snackbar host state across recompositions. */
@Composable
fun rememberPizzoraSnackbarHostState(): PizzoraSnackbarHostState = remember {
  PizzoraSnackbarHostState()
}

/**
 * Hosts one [PizzoraSnackbar] at a time and applies the tokenized lifetime for its duration.
 * The host node keeps [testTag] so it can be wired into [PizzoraScreenScaffold]; the snackbar and
 * its children derive `<testTag>_snackbar`, `<testTag>_snackbar_message`,
 * `<testTag>_snackbar_action`, and `<testTag>_snackbar_dismiss`.
 */
@Composable
fun PizzoraSnackbarHost(
  state: PizzoraSnackbarHostState,
  modifier: Modifier = Modifier,
  testTag: String = "pizzora_snackbar_host",
) {
  val tokens = LocalPizzoraTokens.current
  Box(modifier = modifier.testTag(testTag)) {
    val data = state.currentSnackbarData
    if (data != null) {
      val timeoutMillis = data.duration.durationMillis(tokens.feedback)
      if (timeoutMillis > 0L) {
        LaunchedEffect(data, timeoutMillis) {
          delay(timeoutMillis)
          state.dismiss()
        }
      }
      PizzoraSnackbar(
        message = data.message,
        modifier = Modifier.fillMaxWidth(),
        actionLabel = data.actionLabel,
        onActionClick = { state.performAction() },
        onDismiss = { state.dismiss() },
        withDismissAction = data.withDismissAction,
        testTag = "${testTag}_snackbar",
      )
    }
  }
}

/**
 * Token-styled snackbar with optional action and swipe-to-dismiss.
 *
 * The lifetime is owned by [PizzoraSnackbarHost] so it can come from the active feedback tokens;
 * this composable owns elevation, padding, colors, and dismissal gestures. Child tags derive from
 * [testTag] as `<testTag>_surface`, `<testTag>_message`, `<testTag>_action`, and
 * `<testTag>_dismiss`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PizzoraSnackbar(
  message: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onActionClick: () -> Unit = {},
  onDismiss: () -> Unit = {},
  withDismissAction: Boolean = false,
  testTag: String = "pizzora_snackbar",
) {
  val tokens = LocalPizzoraTokens.current
  val dismissState = rememberSwipeToDismissBoxState(
    confirmValueChange = { value ->
      if (value == SwipeToDismissBoxValue.StartToEnd || value == SwipeToDismissBoxValue.EndToStart) {
        onDismiss()
        true
      } else {
        false
      }
    },
  )

  SwipeToDismissBox(
    state = dismissState,
    modifier = modifier
      .padding(horizontal = tokens.spacing.space16, vertical = tokens.spacing.space8)
      .testTag(testTag),
    backgroundContent = {},
    enableDismissFromStartToEnd = true,
    enableDismissFromEndToStart = true,
  ) {
    PizzoraSurface(
      modifier = Modifier.fillMaxWidth(),
      tone = PizzoraSurfaceTone.InverseSurface,
      elevation = tokens.elevation.level4,
      shape = RoundedCornerShape(tokens.shape.radiusSmall),
      testTag = "${testTag}_surface",
    ) {
      Row(
        modifier = Modifier.padding(
          horizontal = tokens.spacing.space12,
          vertical = tokens.spacing.space8,
        ),
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space8),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = message,
          modifier = Modifier.weight(1f).testTag("${testTag}_message"),
          style = MaterialTheme.typography.bodyMedium,
        )
        if (actionLabel != null) {
          TextButton(
            onClick = onActionClick,
            modifier = Modifier
              .heightIn(min = tokens.sizing.touchTargetMin)
              .testTag("${testTag}_action"),
            colors = ButtonDefaults.textButtonColors(
              contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ),
          ) {
            Text(text = actionLabel)
          }
        }
        if (withDismissAction) {
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("${testTag}_dismiss"),
          ) {
            Icon(imageVector = Icons.Filled.Close, contentDescription = "Dismiss")
          }
        }
      }
    }
  }
}

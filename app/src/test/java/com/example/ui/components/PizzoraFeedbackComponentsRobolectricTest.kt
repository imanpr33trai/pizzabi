package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalPizzoraTokens
import com.example.ui.theme.PizzoraFeedbackTokens
import com.example.ui.theme.PizzoraTheme
import com.example.ui.theme.PizzoraTokenValues
import com.example.ui.theme.PizzoraTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PizzoraFeedbackComponentsRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `feedback tokens expose standardized durations and support overrides`() {
    assertEquals(3_000, PizzoraTokens.feedback.snackbarShortMs)
    assertEquals(5_000, PizzoraTokens.feedback.snackbarMediumMs)
    assertEquals(10_000, PizzoraTokens.feedback.snackbarLongMs)
    assertEquals(2_000, PizzoraTokens.feedback.toastDurationMs)

    var observed = 0
    composeTestRule.setContent {
      CompositionLocalProvider(
        LocalPizzoraTokens provides PizzoraTokenValues(
          feedback = PizzoraFeedbackTokens(snackbarMediumMs = 1_234),
        ),
      ) {
        observed = LocalPizzoraTokens.current.feedback.snackbarMediumMs
      }
    }
    assertEquals(1_234, observed)
  }

  @Test
  fun `snackbar shows a message and reports its action`() {
    var result by mutableStateOf<PizzoraSnackbarResult?>(null)
    composeTestRule.setContent {
      PizzoraTheme {
        val hostState = rememberPizzoraSnackbarHostState()
        LaunchedEffect(Unit) {
          result = hostState.showSnackbar(
            message = "Order placed",
            actionLabel = "Undo",
            duration = PizzoraSnackbarDuration.Indefinite,
          )
        }
        PizzoraSnackbarHost(
          state = hostState,
          modifier = Modifier.fillMaxWidth(),
          testTag = "host",
        )
      }
    }

    composeTestRule.onNodeWithTag("host_snackbar_surface").assertIsDisplayed()
    composeTestRule.onNodeWithTag("host_snackbar_message").assertIsDisplayed().assertTextEquals("Order placed")
    composeTestRule.onNodeWithTag("host_snackbar_action")
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
      .performClick()
    composeTestRule.waitUntil { result != null }
    assertEquals(PizzoraSnackbarResult.ActionPerformed, result)
    composeTestRule.waitUntil(timeoutMillis = 1_000) {
      runCatching { composeTestRule.onNodeWithTag("host_snackbar").assertDoesNotExist() }.isSuccess
    }
  }

  @Test
  fun `snackbar is dismissed by swiping`() {
    var result by mutableStateOf<PizzoraSnackbarResult?>(null)
    composeTestRule.setContent {
      PizzoraTheme {
        val hostState = rememberPizzoraSnackbarHostState()
        LaunchedEffect(Unit) {
          result = hostState.showSnackbar(
            message = "Swipe me",
            duration = PizzoraSnackbarDuration.Indefinite,
            withDismissAction = true,
          )
        }
        PizzoraSnackbarHost(state = hostState, modifier = Modifier.fillMaxWidth(), testTag = "host")
      }
    }

    composeTestRule.onNodeWithTag("host_snackbar_dismiss").assertIsDisplayed()
    composeTestRule.onNodeWithTag("host_snackbar").performTouchInput { swipeRight() }
    composeTestRule.waitUntil(timeoutMillis = 1_000) {
      runCatching { composeTestRule.onNodeWithTag("host_snackbar").assertDoesNotExist() }.isSuccess
    }
    assertEquals(PizzoraSnackbarResult.Dismissed, result)
  }

  @Test
  fun `snackbar dismisses itself after the overridden duration token`() {
    var result by mutableStateOf<PizzoraSnackbarResult?>(null)
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.setContent {
      PizzoraTheme {
        CompositionLocalProvider(
          LocalPizzoraTokens provides PizzoraTokenValues(
            feedback = PizzoraFeedbackTokens(snackbarShortMs = 400),
          ),
        ) {
          val hostState = rememberPizzoraSnackbarHostState()
          LaunchedEffect(Unit) {
            result = hostState.showSnackbar(
              message = "Brief message",
              duration = PizzoraSnackbarDuration.Short,
            )
          }
          PizzoraSnackbarHost(state = hostState, modifier = Modifier.fillMaxWidth(), testTag = "host")
        }
      }
    }

    composeTestRule.mainClock.advanceTimeBy(100)
    composeTestRule.onNodeWithTag("host_snackbar").assertIsDisplayed()
    composeTestRule.mainClock.advanceTimeBy(400)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("host_snackbar").assertDoesNotExist()
    assertEquals(PizzoraSnackbarResult.Dismissed, result)
  }

  @Test
  fun `snackbar uses the spacing token for its horizontal padding`() {
    composeTestRule.setContent {
      PizzoraTheme {
        CompositionLocalProvider(
          LocalPizzoraTokens provides PizzoraTokenValues(
            spacing = PizzoraTokens.spacing.copy(space16 = 40.dp),
          ),
        ) {
          val hostState = rememberPizzoraSnackbarHostState()
          LaunchedEffect(Unit) {
            hostState.showSnackbar("Padded", duration = PizzoraSnackbarDuration.Indefinite)
          }
          PizzoraSnackbarHost(state = hostState, modifier = Modifier.fillMaxWidth(), testTag = "host")
        }
      }
    }

    val messageLeft = composeTestRule.onNodeWithTag("host_snackbar_message")
      .fetchSemanticsNode()
      .boundsInRoot
      .left
    val minimumLeft = with(composeTestRule.density) { 40.dp.toPx() }
    assert(messageLeft >= minimumLeft) { "Expected at least 40dp of left padding, got $messageLeft px" }
  }

  @Test
  fun `snackbar renders in light dark and kds modes`() {
    composeTestRule.setContent {
      Column {
        PizzoraTheme(darkTheme = false) { FeedbackProbe(prefix = "light") }
        PizzoraTheme(darkTheme = true) { FeedbackProbe(prefix = "dark") }
        PizzoraTheme(kdsMode = true) { FeedbackProbe(prefix = "kds") }
      }
    }

    listOf("light", "dark", "kds").forEach { mode ->
      composeTestRule.onNodeWithTag("${mode}_host_snackbar_message").assertIsDisplayed()
    }
    fun inverseSurface(mode: String): String = composeTestRule.onNodeWithTag("${mode}_color")
      .fetchSemanticsNode()
      .textContent()
    assertNotEquals(inverseSurface("light"), inverseSurface("dark"))
    assertEquals(inverseSurface("dark"), inverseSurface("kds"))
  }

  @Test
  fun `toast shows a message and disappears after the tokenized duration`() {
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.setContent {
      PizzoraTheme {
        CompositionLocalProvider(
          LocalPizzoraTokens provides PizzoraTokenValues(
            feedback = PizzoraFeedbackTokens(toastDurationMs = 300),
          ),
        ) {
          val toastState = rememberPizzoraToastHostState()
          LaunchedEffect(Unit) { toastState.show("Added to cart") }
          PizzoraToastHost(state = toastState, modifier = Modifier.fillMaxSize(), testTag = "toast_host")
        }
      }
    }

    composeTestRule.mainClock.advanceTimeBy(100)
    composeTestRule.onNodeWithTag("toast_host_toast_message").assertIsDisplayed().assertTextEquals("Added to cart")
    composeTestRule.mainClock.advanceTimeBy(300)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("toast_host_toast").assertDoesNotExist()
  }

  @Test
  fun `toast position and offset come from the public API and tokens`() {
    val topState = PizzoraToastHostState().apply { show("Top toast") }
    val bottomState = PizzoraToastHostState().apply { show("Bottom toast") }
    composeTestRule.setContent {
      PizzoraTheme {
        CompositionLocalProvider(
          LocalPizzoraTokens provides PizzoraTokenValues(
            feedback = PizzoraFeedbackTokens(
              toastVerticalOffset = 40.dp,
              toastDurationMs = 60_000,
            ),
          ),
        ) {
          Column {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
              PizzoraToastHost(
                state = topState,
                modifier = Modifier.fillMaxSize(),
                position = PizzoraToastPosition.Top,
                testTag = "top_toast_host",
              )
            }
            Box(Modifier.fillMaxWidth().height(300.dp)) {
              PizzoraToastHost(
                state = bottomState,
                modifier = Modifier.fillMaxSize(),
                position = PizzoraToastPosition.Bottom,
                testTag = "bottom_toast_host",
              )
            }
          }
        }
      }
    }

    composeTestRule.onNodeWithTag("top_toast_host_toast").assertIsDisplayed()
    composeTestRule.onNodeWithTag("bottom_toast_host_toast").assertIsDisplayed()
    val topBounds = composeTestRule.onNodeWithTag("top_toast_host_toast").fetchSemanticsNode().boundsInRoot
    val bottomBounds = composeTestRule.onNodeWithTag("bottom_toast_host_toast").fetchSemanticsNode().boundsInRoot
    assert(topBounds.top < bottomBounds.top) { "Top toast must render above the bottom toast" }
    val expectedOffset = with(composeTestRule.density) { 40.dp.toPx() }
    assert(topBounds.top >= expectedOffset) { "Top toast must respect the 40dp token offset" }
  }

  @Test
  fun `toast renders in light dark and kds modes`() {
    val states = listOf("light", "dark", "kds").associateWith { mode ->
      PizzoraToastHostState().apply { show("$mode toast") }
    }
    composeTestRule.setContent {
      Column {
        PizzoraTheme(darkTheme = false) { ToastProbe(prefix = "light", state = states.getValue("light")) }
        PizzoraTheme(darkTheme = true) { ToastProbe(prefix = "dark", state = states.getValue("dark")) }
        PizzoraTheme(kdsMode = true) { ToastProbe(prefix = "kds", state = states.getValue("kds")) }
      }
    }

    listOf("light", "dark", "kds").forEach { mode ->
      composeTestRule.onNodeWithTag("${mode}_toast_host_toast_message").assertIsDisplayed()
    }
    fun inverseSurface(mode: String): String = composeTestRule.onNodeWithTag("${mode}_color")
      .fetchSemanticsNode()
      .textContent()
    assertNotEquals(inverseSurface("light"), inverseSurface("dark"))
    assertEquals(inverseSurface("dark"), inverseSurface("kds"))
  }

  @Test
  fun `screen scaffold hosts the tokenized snackbar`() {
    val hostState = PizzoraSnackbarHostState()
    composeTestRule.setContent {
      PizzoraTheme {
        LaunchedEffect(Unit) {
          hostState.showSnackbar("Scaffold message", duration = PizzoraSnackbarDuration.Indefinite)
        }
        PizzoraScreenScaffold(
          title = "Menu",
          snackbarHostState = hostState,
          testTag = "scaffold",
        ) {}
      }
    }

    composeTestRule.onNodeWithTag("scaffold_snackbar_host").assertExists()
    composeTestRule.onNodeWithTag("scaffold_snackbar_host_snackbar_message")
      .assertIsDisplayed()
      .assertTextEquals("Scaffold message")
  }

  @Test
  fun `progress indicator animates a determinate value over the token duration`() {
    var target by mutableStateOf(0f)
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.setContent {
      PizzoraTheme {
        CompositionLocalProvider(
          LocalPizzoraTokens provides PizzoraTokenValues(
            motion = PizzoraTokens.motion.copy(durationMedium = 400),
          ),
        ) {
          PizzoraProgressIndicator(
            progress = target,
            style = PizzoraProgressIndicatorStyle.Linear,
            testTag = "progress",
          )
        }
      }
    }

    fun currentProgress(): Float = composeTestRule.onNodeWithTag("progress")
      .fetchSemanticsNode()
      .config[SemanticsProperties.ProgressBarRangeInfo]
      .current
    assertEquals(0f, currentProgress(), 0.001f)

    composeTestRule.runOnIdle { target = 1f }
    composeTestRule.mainClock.advanceTimeByFrame()
    currentProgress() // Flush semantics so the new target is observed before timing the animation.
    composeTestRule.mainClock.advanceTimeBy(200)
    val midway = currentProgress()
    assert(midway > 0f && midway < 1f) { "Expected an in-between value, got $midway" }
    composeTestRule.mainClock.advanceTimeBy(400)
    assertEquals(1f, currentProgress(), 0.001f)
  }

  @Test
  fun `progress indicator supports both styles in determinate and indeterminate modes`() {
    composeTestRule.setContent {
      PizzoraTheme {
        Column {
          PizzoraProgressIndicator(progress = 0.5f, style = PizzoraProgressIndicatorStyle.Linear, testTag = "linear_determinate")
          PizzoraProgressIndicator(style = PizzoraProgressIndicatorStyle.Linear, testTag = "linear_indeterminate")
          PizzoraProgressIndicator(progress = 0.5f, style = PizzoraProgressIndicatorStyle.Circular, testTag = "circular_determinate")
          PizzoraProgressIndicator(style = PizzoraProgressIndicatorStyle.Circular, testTag = "circular_indeterminate")
        }
      }
    }
    composeTestRule.waitForIdle()

    fun progressInfo(tag: String): ProgressBarRangeInfo = composeTestRule.onNodeWithTag(tag)
      .fetchSemanticsNode()
      .config[SemanticsProperties.ProgressBarRangeInfo]
    listOf("linear_determinate", "circular_determinate").forEach { tag ->
      assertEquals(0.5f, progressInfo(tag).current, 0.001f)
    }
    listOf("linear_indeterminate", "circular_indeterminate").forEach { tag ->
      assertEquals(ProgressBarRangeInfo.Indeterminate, progressInfo(tag))
    }
  }

  @Test
  fun `progress indicator renders in light dark and kds modes`() {
    composeTestRule.setContent {
      Column {
        PizzoraTheme(darkTheme = false) { ProgressProbe(prefix = "light") }
        PizzoraTheme(darkTheme = true) { ProgressProbe(prefix = "dark") }
        PizzoraTheme(kdsMode = true) { ProgressProbe(prefix = "kds") }
      }
    }

    listOf("light", "dark", "kds").forEach { mode ->
      composeTestRule.onNodeWithTag("${mode}_progress").assertIsDisplayed()
    }
    fun primaryColor(mode: String): String = composeTestRule.onNodeWithTag("${mode}_color")
      .fetchSemanticsNode()
      .textContent()
    assertNotEquals(primaryColor("light"), primaryColor("dark"))
    assertEquals(primaryColor("dark"), primaryColor("kds"))
  }
}

private fun SemanticsNode.textContent(): String =
  config[SemanticsProperties.Text].first().text

@Composable
private fun ProgressProbe(prefix: String) {
  Column {
    PizzoraProgressIndicator(
      style = PizzoraProgressIndicatorStyle.Circular,
      testTag = "${prefix}_progress",
    )
    androidx.compose.material3.Text(
      text = MaterialTheme.colorScheme.primary.toArgb().toString(),
      modifier = Modifier.testTag("${prefix}_color"),
    )
  }
}

@Composable
private fun ToastProbe(prefix: String, state: PizzoraToastHostState) {
  CompositionLocalProvider(
    LocalPizzoraTokens provides PizzoraTokenValues(
      feedback = PizzoraFeedbackTokens(toastDurationMs = 60_000, toastVerticalOffset = 0.dp),
    ),
  ) {
    Column {
      PizzoraToastHost(
        state = state,
        modifier = Modifier.fillMaxWidth().height(120.dp),
        testTag = "${prefix}_toast_host",
      )
      androidx.compose.material3.Text(
        text = MaterialTheme.colorScheme.inverseSurface.toArgb().toString(),
        modifier = Modifier.testTag("${prefix}_color"),
      )
    }
  }
}

@Composable
private fun FeedbackProbe(prefix: String) {
  val hostState = rememberPizzoraSnackbarHostState()
  LaunchedEffect(Unit) {
    hostState.showSnackbar("$prefix message", duration = PizzoraSnackbarDuration.Indefinite)
  }
  Column {
    PizzoraSnackbarHost(
      state = hostState,
      modifier = Modifier.fillMaxWidth(),
      testTag = "${prefix}_host",
    )
    androidx.compose.material3.Text(
      text = MaterialTheme.colorScheme.inverseSurface.toArgb().toString(),
      modifier = Modifier.testTag("${prefix}_color"),
    )
  }
}

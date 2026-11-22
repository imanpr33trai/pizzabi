package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.Money
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PizzoraCompositeComponentsRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `empty state renders illustration title subtitle and action`() {
    var actionClicks = 0
    composeTestRule.setContent {
      PizzoraTheme {
        PizzoraEmptyState(
          title = "No orders yet",
          subtitle = "When you place an order it will show up here.",
          illustration = Icons.Filled.ShoppingCart,
          actionLabel = "Browse menu",
          onAction = { actionClicks++ },
          testTag = "empty_state",
        )
      }
    }

    composeTestRule.onNodeWithTag("empty_state").assertIsDisplayed()
    composeTestRule.onNodeWithTag("empty_state_illustration").assertIsDisplayed()
    composeTestRule.onNodeWithTag("empty_state_title").assertIsDisplayed()
    composeTestRule.onNodeWithText("No orders yet").assertIsDisplayed()
    composeTestRule.onNodeWithTag("empty_state_subtitle").assertIsDisplayed()
    composeTestRule.onNodeWithTag("empty_state_action").assertIsDisplayed().performClick()
    assertEquals(1, actionClicks)
  }

  @Test
  fun `empty state renders without action when label is null`() {
    composeTestRule.setContent {
      PizzoraTheme {
        PizzoraEmptyState(
          title = "Nothing here",
          subtitle = "Check back later.",
          testTag = "empty_no_action",
        )
      }
    }

    composeTestRule.onNodeWithTag("empty_no_action").assertIsDisplayed()
    composeTestRule.onNodeWithTag("empty_no_action_title").assertIsDisplayed()
    composeTestRule.onNodeWithTag("empty_no_action_subtitle").assertIsDisplayed()
    composeTestRule.onNodeWithTag("empty_no_action_action").assertDoesNotExist()
  }

  @Test
  fun `skeleton renders all shapes with shimmer test tags`() {
    composeTestRule.setContent {
      PizzoraTheme {
        Column {
          PizzoraSkeleton(shape = PizzoraSkeletonShape.Image, testTag = "skel_image")
          PizzoraSkeleton(shape = PizzoraSkeletonShape.TextLine, testTag = "skel_text")
          PizzoraSkeleton(shape = PizzoraSkeletonShape.Card, testTag = "skel_card")
          PizzoraSkeletonCard(testTag = "skel_composite")
        }
      }
    }

    composeTestRule.onNodeWithTag("skel_image").assertIsDisplayed()
    composeTestRule.onNodeWithTag("skel_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("skel_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("skel_composite").assertIsDisplayed()
    composeTestRule.onNodeWithTag("skel_composite_image").assertIsDisplayed()
    composeTestRule.onNodeWithTag("skel_composite_title").assertIsDisplayed()
    composeTestRule.onNodeWithTag("skel_composite_subtitle").assertIsDisplayed()

    // Advance time to ensure the infinite shimmer animation does not crash.
    composeTestRule.mainClock.advanceTimeBy(2000)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("skel_text").assertIsDisplayed()
  }

  @Test
  fun `price text formats inr in monospace`() {
    composeTestRule.setContent {
      PizzoraTheme {
        Column {
          PizzoraPriceText(paise = 12345L, animate = false, testTag = "price_small")
          PizzoraPriceTextLarge(paise = 99900L, animate = false, testTag = "price_large")
        }
      }
    }

    composeTestRule.onNodeWithTag("price_small").assertTextEquals(Money.formatInr(12345L))
    composeTestRule.onNodeWithTag("price_large").assertTextEquals(Money.formatInr(99900L))
    composeTestRule.onNodeWithTag("price_small").assertIsDisplayed()
    composeTestRule.onNodeWithTag("price_large").assertIsDisplayed()
  }

  @Test
  fun `price text animates count up when value changes`() {
    var paiseState = mutableStateOf(10000L)
    composeTestRule.setContent {
      PizzoraTheme {
        var paise by paiseState
        Column {
          PizzoraPriceText(paise = paise, animate = true, testTag = "price_animated")
        }
        LaunchedEffect(Unit) {
          paise = 25000L
        }
      }
    }

    composeTestRule.waitForIdle()
    // After animation completes, the text should show the final value.
    composeTestRule.mainClock.advanceTimeBy(1000)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("price_animated").assertTextEquals(Money.formatInr(25000L))
  }

  @Test
  fun `badge renders all tones and order status variants`() {
    composeTestRule.setContent {
      PizzoraTheme {
        Column {
          PizzoraBadgeTone.entries.forEach { tone ->
            PizzoraBadge(
              label = tone.name,
              tone = tone,
              testTag = "badge_${tone.name.lowercase()}",
            )
          }
          OrderStatus.entries.forEach { status ->
            PizzoraOrderStatusBadge(
              status = status,
              testTag = "order_badge_${status.name.lowercase()}",
            )
          }
        }
      }
    }

    // Let the appear animation settle before checking existence.
    composeTestRule.mainClock.advanceTimeBy(500)
    composeTestRule.waitForIdle()
    PizzoraBadgeTone.entries.forEach { tone ->
      composeTestRule.onNodeWithTag("badge_${tone.name.lowercase()}").assertExists()
    }
    OrderStatus.entries.forEach { status ->
      composeTestRule.onNodeWithTag("order_badge_${status.name.lowercase()}").assertExists()
    }
    // First badge should be displayed.
    composeTestRule.onNodeWithTag("badge_neutral").assertIsDisplayed()
    composeTestRule.onNodeWithTag("order_badge_pending_payment").assertIsDisplayed()
  }

  @Test
  fun `badge renders payment status and kds state variants`() {
    composeTestRule.setContent {
      PizzoraTheme {
        Column {
          PaymentStatus.entries.forEach { status ->
            PizzoraPaymentStatusBadge(
              status = status,
              testTag = "pay_badge_${status.name.lowercase()}",
            )
          }
          PizzoraKdsState.entries.forEach { state ->
            PizzoraKdsStateBadge(
              state = state,
              testTag = "kds_badge_${state.name.lowercase()}",
            )
          }
        }
      }
    }

    // Let the appear animation settle before checking existence.
    composeTestRule.mainClock.advanceTimeBy(500)
    composeTestRule.waitForIdle()
    PaymentStatus.entries.forEach { status ->
      composeTestRule.onNodeWithTag("pay_badge_${status.name.lowercase()}").assertExists()
    }
    PizzoraKdsState.entries.forEach { state ->
      composeTestRule.onNodeWithTag("kds_badge_${state.name.lowercase()}").assertExists()
    }
    // First badges should be displayed.
    composeTestRule.onNodeWithTag("pay_badge_unpaid").assertIsDisplayed()
    composeTestRule.onNodeWithTag("kds_badge_pending").assertIsDisplayed()
  }

  @Test
  fun `divider renders both weights with theme colors`() {
    composeTestRule.setContent {
      PizzoraTheme {
        Column {
          PizzoraDivider(weight = PizzoraDividerWeight.Hairline, testTag = "divider_hairline")
          PizzoraDivider(weight = PizzoraDividerWeight.Thin, testTag = "divider_thin")
          PizzoraSpacedDivider(testTag = "divider_spaced")
        }
      }
    }

    composeTestRule.onNodeWithTag("divider_hairline").assertIsDisplayed()
    composeTestRule.onNodeWithTag("divider_thin").assertIsDisplayed()
    composeTestRule.onNodeWithTag("divider_spaced").assertIsDisplayed()
  }

  @Test
  fun `composite components render in light dark and kds modes`() {
    composeTestRule.setContent {
      Column {
        PizzoraTheme(darkTheme = false) {
          PizzoraCompositeProbe(prefix = "light")
        }
        PizzoraTheme(darkTheme = true) {
          PizzoraCompositeProbe(prefix = "dark")
        }
        PizzoraTheme(kdsMode = true) {
          PizzoraCompositeProbe(prefix = "kds")
        }
      }
    }

    listOf("light", "dark", "kds").forEach { mode ->
      composeTestRule.onNodeWithTag("${mode}_empty_state").assertExists()
      composeTestRule.onNodeWithTag("${mode}_skeleton").assertExists()
      composeTestRule.onNodeWithTag("${mode}_price").assertExists()
      composeTestRule.onNodeWithTag("${mode}_badge").assertExists()
      composeTestRule.onNodeWithTag("${mode}_divider").assertExists()
    }
    composeTestRule.onNodeWithTag("light_badge").assertIsDisplayed()
  }

  @Test
  fun `money format produces expected inr strings`() {
    assertEquals("₹123.45", Money.formatInr(12345L))
    assertEquals("₹0.00", Money.formatInr(0L))
    assertEquals("₹999.00", Money.formatInr(99900L))
    assertEquals("-₹10.50", Money.formatInr(-1050L))
  }
}

@Composable
private fun PizzoraCompositeProbe(prefix: String) {
  Column {
    PizzoraEmptyState(
      title = "Empty",
      subtitle = "Nothing here",
      illustration = Icons.Filled.LocalPizza,
      testTag = "${prefix}_empty_state",
    )
    PizzoraSkeleton(shape = PizzoraSkeletonShape.TextLine, testTag = "${prefix}_skeleton")
    PizzoraPriceText(paise = 5000L, animate = false, testTag = "${prefix}_price")
    PizzoraBadge(label = "Active", tone = PizzoraBadgeTone.Success, testTag = "${prefix}_badge")
    PizzoraDivider(testTag = "${prefix}_divider")
  }
}

package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    composeTestRule.onNodeWithTag("empty_state_title")
      .assertIsDisplayed()
      .assertTextEquals("No orders yet")
    composeTestRule.onNodeWithTag("empty_state_subtitle")
      .assertIsDisplayed()
      .assertTextEquals("When you place an order it will show up here.")
    composeTestRule.onNodeWithTag("empty_state_action").assertIsDisplayed().performClick()
    assertEquals(1, actionClicks)
  }

  @Test
  fun `empty state omits optional illustration and action`() {
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
    composeTestRule.onNodeWithTag("empty_no_action_illustration").assertDoesNotExist()
    composeTestRule.onNodeWithTag("empty_no_action_action").assertDoesNotExist()
  }

  @Test
  fun `skeleton renders token-sized image text and card placeholders`() {
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

    composeTestRule.onNodeWithTag("skel_image")
      .assertIsDisplayed()
      .assertWidthIsEqualTo(64.dp)
      .assertHeightIsEqualTo(64.dp)
    composeTestRule.onNodeWithTag("skel_text")
      .assertIsDisplayed()
      .assertHeightIsEqualTo(16.dp)
    composeTestRule.onNodeWithTag("skel_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("skel_composite").assertIsDisplayed()
    composeTestRule.onNodeWithTag("skel_composite_image").assertIsDisplayed()
    composeTestRule.onNodeWithTag("skel_composite_title").assertIsDisplayed()
    composeTestRule.onNodeWithTag("skel_composite_subtitle").assertIsDisplayed()
  }

  @Test
  fun `skeleton shimmer keeps running through repeat cycles`() {
    composeTestRule.setContent {
      PizzoraTheme {
        PizzoraSkeleton(shape = PizzoraSkeletonShape.Card, testTag = "skel_shimmer")
      }
    }

    composeTestRule.onNodeWithTag("skel_shimmer").assertIsDisplayed()
    composeTestRule.mainClock.advanceTimeBy(2_000)
    composeTestRule.onNodeWithTag("skel_shimmer").assertIsDisplayed()
    composeTestRule.mainClock.advanceTimeBy(2_000)
    composeTestRule.onNodeWithTag("skel_shimmer").assertIsDisplayed()
  }

  @Test
  fun `price text formats inr amounts with monospace typography`() {
    composeTestRule.setContent {
      PizzoraTheme {
        Column {
          PizzoraPriceText(paise = 12_345L, animate = false, testTag = "price_small")
          PizzoraPriceTextLarge(paise = 99_900L, animate = false, testTag = "price_large")
          PizzoraPriceText(paise = -1_050L, animate = false, testTag = "price_negative")
          PizzoraPriceText(paise = 5L, animate = false, testTag = "price_paise")
          PizzoraPriceText(paise = 123_456_789L, animate = false, testTag = "price_large_exact")
        }
      }
    }

    composeTestRule.onNodeWithTag("price_small").assertTextEquals("₹123.45")
    composeTestRule.onNodeWithTag("price_large").assertTextEquals("₹999.00")
    composeTestRule.onNodeWithTag("price_negative").assertTextEquals("-₹10.50")
    composeTestRule.onNodeWithTag("price_paise").assertTextEquals("₹0.05")
    composeTestRule.onNodeWithTag("price_large_exact").assertTextEquals("₹1234567.89")
  }

  @Test
  fun `price text animates count up to the new amount`() {
    val paiseState = mutableStateOf(0L)
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.setContent {
      PizzoraTheme {
        PizzoraPriceText(paise = paiseState.value, animate = true, testTag = "price_animated")
      }
    }

    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.onNodeWithTag("price_animated").assertTextEquals("₹0.00")
    composeTestRule.runOnIdle { paiseState.value = 25_000L }
    composeTestRule.mainClock.advanceTimeBy(50)
    val midAnimationText = composeTestRule.onNodeWithTag("price_animated")
      .fetchSemanticsNode()
      .config[SemanticsProperties.Text]
      .first()
      .text
    assertNotEquals("₹250.00", midAnimationText)
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.onNodeWithTag("price_animated").assertTextEquals("₹250.00")
  }

  @Test
  fun `badge renders every order status variant`() {
    composeTestRule.setContent {
      PizzoraTheme {
        Column {
          OrderStatus.entries.forEach { status ->
            PizzoraOrderStatusBadge(
              status = status,
              testTag = "order_badge_${status.name.lowercase()}",
            )
          }
        }
      }
    }

    composeTestRule.mainClock.advanceTimeBy(500)
    composeTestRule.waitForIdle()
    OrderStatus.entries.forEach { status ->
      composeTestRule.onNodeWithTag("order_badge_${status.name.lowercase()}").assertIsDisplayed()
    }
    composeTestRule.onNodeWithTag("order_badge_pending_payment_label", useUnmergedTree = true)
      .assertTextEquals("PENDING PAYMENT")
    composeTestRule.onNodeWithTag("order_badge_out_for_delivery_label", useUnmergedTree = true)
      .assertTextEquals("OUT FOR DELIVERY")
  }

  @Test
  fun `badge renders every payment and kds state variant`() {
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

    composeTestRule.mainClock.advanceTimeBy(500)
    composeTestRule.waitForIdle()
    PaymentStatus.entries.forEach { status ->
      composeTestRule.onNodeWithTag("pay_badge_${status.name.lowercase()}").assertExists()
    }
    PizzoraKdsState.entries.forEach { state ->
      composeTestRule.onNodeWithTag("kds_badge_${state.name.lowercase()}").assertExists()
    }
    composeTestRule.onNodeWithTag("pay_badge_refund_pending_label", useUnmergedTree = true)
      .assertTextEquals("REFUND PENDING")
    composeTestRule.onNodeWithTag("kds_badge_pending_label", useUnmergedTree = true)
      .assertTextEquals("Pending")
  }

  @Test
  fun `badge renders all tones with optional icon`() {
    composeTestRule.setContent {
      PizzoraTheme {
        Column {
          PizzoraBadgeTone.entries.forEach { tone ->
            PizzoraBadge(
              label = tone.name,
              tone = tone,
              icon = Icons.Filled.LocalPizza,
              testTag = "badge_${tone.name.lowercase()}",
            )
          }
        }
      }
    }

    composeTestRule.mainClock.advanceTimeBy(500)
    composeTestRule.waitForIdle()
    PizzoraBadgeTone.entries.forEach { tone ->
      composeTestRule.onNodeWithTag("badge_${tone.name.lowercase()}").assertIsDisplayed()
      composeTestRule.onNodeWithTag(
        "badge_${tone.name.lowercase()}_icon",
        useUnmergedTree = true,
      ).assertIsDisplayed()
    }
  }

  @Test
  fun `badge appears with scale and fade animation`() {
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.setContent {
      PizzoraTheme {
        PizzoraBadge(label = "Ready", tone = PizzoraBadgeTone.Success, testTag = "badge_appear")
      }
    }

    composeTestRule.onNodeWithTag("badge_appear").assertDoesNotExist()
    composeTestRule.mainClock.advanceTimeBy(300)
    composeTestRule.onNodeWithTag("badge_appear").assertIsDisplayed()
  }

  @Test
  fun `divider renders hairline and thin weights with token spacing`() {
    composeTestRule.setContent {
      PizzoraTheme {
        Column {
          PizzoraDivider(weight = PizzoraDividerWeight.Hairline, testTag = "divider_hairline")
          PizzoraDivider(weight = PizzoraDividerWeight.Thin, testTag = "divider_thin")
          PizzoraSpacedDivider(testTag = "divider_spaced")
        }
      }
    }

    composeTestRule.onNodeWithTag("divider_hairline")
      .assertIsDisplayed()
      .assertHeightIsEqualTo(1.dp)
    composeTestRule.onNodeWithTag("divider_thin")
      .assertIsDisplayed()
      .assertHeightIsEqualTo(2.dp)
    composeTestRule.onNodeWithTag("divider_spaced").assertIsDisplayed()
  }

  @Test
  fun `all composite components render in light dark and kds modes`() {
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

    composeTestRule.mainClock.advanceTimeBy(500)
    composeTestRule.waitForIdle()
    listOf("light", "dark", "kds").forEach { mode ->
      composeTestRule.onNodeWithTag("${mode}_empty_state").assertExists()
      composeTestRule.onNodeWithTag("${mode}_skeleton").assertExists()
      composeTestRule.onNodeWithTag("${mode}_price").assertExists()
      composeTestRule.onNodeWithTag("${mode}_badge").assertExists()
      composeTestRule.onNodeWithTag("${mode}_divider").assertExists()
    }
    composeTestRule.onNodeWithTag("light_empty_state").assertIsDisplayed()
  }
}

@androidx.compose.runtime.Composable
private fun PizzoraCompositeProbe(prefix: String) {
  Column {
    PizzoraEmptyState(
      title = "Empty",
      subtitle = "Nothing here",
      illustration = Icons.Filled.LocalPizza,
      testTag = "${prefix}_empty_state",
    )
    PizzoraSkeleton(shape = PizzoraSkeletonShape.TextLine, testTag = "${prefix}_skeleton")
    PizzoraPriceText(paise = 5_000L, animate = false, testTag = "${prefix}_price")
    PizzoraBadge(label = "Active", tone = PizzoraBadgeTone.Success, testTag = "${prefix}_badge")
    PizzoraDivider(testTag = "${prefix}_divider")
  }
}

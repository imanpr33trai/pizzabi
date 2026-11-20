package com.example.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.ui.components.PizzoraBannerTone
import com.example.ui.components.PizzoraConfirmationDialog
import com.example.ui.components.PizzoraDietaryIndicator
import com.example.ui.components.PizzoraEmptyStateCard
import com.example.ui.components.PizzoraMenuCardSkeleton
import com.example.ui.components.PizzoraOrderCardSkeleton
import com.example.ui.components.PizzoraStatusBanner
import com.example.ui.components.PizzoraStickyActionDock
import com.example.ui.theme.DarkSemanticColors
import com.example.ui.theme.LightSemanticColors
import com.example.ui.theme.PizzoraDarkColorScheme
import com.example.ui.theme.PizzoraLightColorScheme
import com.example.ui.theme.PizzoraTheme
import com.example.ui.theme.PizzoraTypography
import com.example.ui.theme.PlusJakartaSansFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PizzoraDesignSystemTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private fun contrastRatio(fg: Color, bg: Color): Double {
    val l1 = fg.luminance().toDouble()
    val l2 = bg.luminance().toDouble()
    return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
  }

  @Test
  fun `dark and light theme tokens satisfy WCAG 2_2 AA contrast ratios`() {
    // Dark Mode text pairs (>= 4.5:1)
    assertTrue(contrastRatio(PizzoraDarkColorScheme.onBackground, PizzoraDarkColorScheme.background) >= 4.5)
    assertTrue(contrastRatio(PizzoraDarkColorScheme.onSurface, PizzoraDarkColorScheme.surface) >= 4.5)
    assertTrue(contrastRatio(PizzoraDarkColorScheme.onSurfaceVariant, PizzoraDarkColorScheme.surface) >= 4.5)
    assertTrue(contrastRatio(PizzoraDarkColorScheme.onSurfaceVariant, PizzoraDarkColorScheme.surfaceVariant) >= 4.5)
    assertTrue(contrastRatio(PizzoraDarkColorScheme.onPrimary, PizzoraDarkColorScheme.primary) >= 4.5)
    assertTrue(contrastRatio(PizzoraDarkColorScheme.primary, PizzoraDarkColorScheme.surface) >= 4.5)
    assertTrue(contrastRatio(PizzoraDarkColorScheme.onPrimaryContainer, PizzoraDarkColorScheme.primaryContainer) >= 4.5)
    assertTrue(contrastRatio(PizzoraDarkColorScheme.secondary, PizzoraDarkColorScheme.surface) >= 4.5)
    assertTrue(contrastRatio(PizzoraDarkColorScheme.onSecondaryContainer, PizzoraDarkColorScheme.secondaryContainer) >= 4.5)
    assertTrue(contrastRatio(PizzoraDarkColorScheme.onErrorContainer, PizzoraDarkColorScheme.errorContainer) >= 4.5)
    assertTrue(contrastRatio(DarkSemanticColors.onWarningContainer, DarkSemanticColors.warningContainer) >= 4.5)
    assertTrue(contrastRatio(PizzoraDarkColorScheme.onSurface, DarkSemanticColors.selectedSurface) >= 4.5)
    assertTrue(contrastRatio(PizzoraDarkColorScheme.primary, DarkSemanticColors.selectedSurface) >= 4.5)
    // Dark Mode interactive border (>= 3.0:1 WCAG 1.4.11)
    assertTrue(contrastRatio(DarkSemanticColors.outlineInteractive, PizzoraDarkColorScheme.surface) >= 3.0)

    // Light Mode text pairs (>= 4.5:1)
    assertTrue(contrastRatio(PizzoraLightColorScheme.onBackground, PizzoraLightColorScheme.background) >= 4.5)
    assertTrue(contrastRatio(PizzoraLightColorScheme.onSurface, PizzoraLightColorScheme.surface) >= 4.5)
    assertTrue(contrastRatio(PizzoraLightColorScheme.onSurfaceVariant, PizzoraLightColorScheme.surface) >= 4.5)
    assertTrue(contrastRatio(PizzoraLightColorScheme.onSurfaceVariant, PizzoraLightColorScheme.surfaceVariant) >= 4.5)
    assertTrue(contrastRatio(PizzoraLightColorScheme.onPrimary, PizzoraLightColorScheme.primary) >= 4.5)
    assertTrue(contrastRatio(PizzoraLightColorScheme.primary, PizzoraLightColorScheme.surface) >= 4.5)
    assertTrue(contrastRatio(PizzoraLightColorScheme.onPrimaryContainer, PizzoraLightColorScheme.primaryContainer) >= 4.5)
    assertTrue(contrastRatio(PizzoraLightColorScheme.secondary, PizzoraLightColorScheme.surface) >= 4.5)
    assertTrue(contrastRatio(PizzoraLightColorScheme.onSecondaryContainer, PizzoraLightColorScheme.secondaryContainer) >= 4.5)
    assertTrue(contrastRatio(PizzoraLightColorScheme.onErrorContainer, PizzoraLightColorScheme.errorContainer) >= 4.5)
    assertTrue(contrastRatio(LightSemanticColors.onWarningContainer, LightSemanticColors.warningContainer) >= 4.5)
    assertTrue(contrastRatio(PizzoraLightColorScheme.onSurface, LightSemanticColors.selectedSurface) >= 4.5)
    assertTrue(contrastRatio(PizzoraLightColorScheme.primary, LightSemanticColors.selectedSurface) >= 4.5)
    // Light Mode interactive border & warning icon (>= 3.0:1 WCAG 1.4.11)
    assertTrue(contrastRatio(LightSemanticColors.outlineInteractive, PizzoraLightColorScheme.surface) >= 3.0)
    assertTrue(contrastRatio(PizzoraLightColorScheme.tertiary, PizzoraLightColorScheme.surface) >= 3.0)
  }

  @Test
  fun `typography scale eliminates Roboto fallback on titleSmall bodySmall and labelSmall`() {
    assertEquals(PlusJakartaSansFamily, PizzoraTypography.titleSmall.fontFamily)
    assertEquals(PlusJakartaSansFamily, PizzoraTypography.bodySmall.fontFamily)
    assertEquals(PlusJakartaSansFamily, PizzoraTypography.labelSmall.fontFamily)
  }

  @Test
  fun `dietary indicator exposes accessible content descriptions`() {
    composeTestRule.setContent {
      PizzoraTheme(darkTheme = true, reducedMotion = true) {
        PizzoraDietaryIndicator(isVeg = true, modifier = Modifier.testTag("veg_badge"))
        PizzoraDietaryIndicator(isVeg = false, modifier = Modifier.testTag("non_veg_badge"))
      }
    }

    composeTestRule.onNodeWithContentDescription("Vegetarian").assertIsDisplayed()
    composeTestRule.onNodeWithContentDescription("Non-vegetarian").assertIsDisplayed()
  }

  @Test
  fun `status banner renders action with 48dp touch target and invokes callback`() {
    var retryClicked = false
    composeTestRule.setContent {
      PizzoraTheme(darkTheme = true, reducedMotion = true) {
        PizzoraStatusBanner(
          message = "Kitchen is currently paused.",
          tone = PizzoraBannerTone.WARNING,
          actionLabel = "Retry",
          onAction = { retryClicked = true },
          actionTestTag = "banner_retry_btn",
        )
      }
    }

    composeTestRule.onNodeWithText("Kitchen is currently paused.").assertIsDisplayed()
    composeTestRule.onNodeWithTag("banner_retry_btn")
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
      .performClick()
    assertTrue(retryClicked)
  }

  @Test
  fun `confirmation dialog locks actions while submitting and invokes callbacks when idle`() {
    var confirmed = false
    var dismissed = false
    composeTestRule.setContent {
      PizzoraTheme(darkTheme = false, reducedMotion = true) {
        PizzoraConfirmationDialog(
          title = "Clear cart?",
          body = "All items will be removed from your oven tray.",
          confirmLabel = "Clear",
          onConfirm = { confirmed = true },
          onDismiss = { dismissed = true },
          isDestructive = true,
          isSubmitting = false,
          confirmTestTag = "confirm_btn",
          dismissTestTag = "dismiss_btn",
        )
      }
    }

    composeTestRule.onNodeWithText("Clear cart?").assertIsDisplayed()
    composeTestRule.onNodeWithTag("confirm_btn").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
    composeTestRule.onNodeWithTag("dismiss_btn").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
    assertTrue(confirmed)
    assertTrue(dismissed)
  }

  @Test
  fun `confirmation dialog disables buttons while submitting`() {
    composeTestRule.setContent {
      PizzoraTheme(darkTheme = true, reducedMotion = true) {
        PizzoraConfirmationDialog(
          title = "Cancel order?",
          body = "Processing cancellation.",
          confirmLabel = "Confirm",
          onConfirm = {},
          onDismiss = {},
          isDestructive = true,
          isSubmitting = true,
          confirmTestTag = "confirm_busy",
          dismissTestTag = "dismiss_busy",
        )
      }
    }

    composeTestRule.onNodeWithTag("confirm_busy").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("dismiss_busy").assertIsNotEnabled()
  }

  @Test
  fun `empty state card, sticky dock, and reduced-motion skeletons compose cleanly`() {
    var browseClicked = false
    composeTestRule.setContent {
      PizzoraTheme(darkTheme = true, reducedMotion = true) {
        PizzoraEmptyStateCard(
          title = "Your oven tray is empty",
          subtitle = "Pick a wood-fired pizza.",
          actionLabel = "Browse Menu",
          onAction = { browseClicked = true },
          actionTestTag = "empty_browse_btn",
        )
        PizzoraMenuCardSkeleton(modifier = Modifier.testTag("menu_skel"))
        PizzoraOrderCardSkeleton(modifier = Modifier.testTag("order_skel"))
        PizzoraStickyActionDock(modifier = Modifier.testTag("sticky_dock")) {
          Text("Proceed to Checkout")
        }
      }
    }

    composeTestRule.onNodeWithTag("empty_browse_btn").assertHeightIsAtLeast(48.dp).performClick()
    assertTrue(browseClicked)
    composeTestRule.onNodeWithTag("menu_skel").assertIsDisplayed()
    composeTestRule.onNodeWithTag("order_skel").assertIsDisplayed()
    composeTestRule.onNodeWithTag("sticky_dock").assertIsDisplayed()
  }
}

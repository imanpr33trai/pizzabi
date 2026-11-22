package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalPizzoraTokens
import com.example.ui.theme.PizzoraTheme
import com.example.ui.theme.PizzoraTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PizzoraComponentsRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `design tokens expose the documented scales`() {
    assertEquals(48.dp, PizzoraTokens.sizing.touchTargetMin)
    assertEquals(48.dp, PizzoraTokens.sizing.buttonHeight)
    assertEquals(48.dp, PizzoraTokens.sizing.iconButtonSize)
    assertEquals(32.dp, PizzoraTokens.sizing.chipHeight)
    assertEquals(16.dp, PizzoraTokens.spacing.space16)
    assertEquals(10.dp, PizzoraTokens.shape.radiusSmall)
    assertEquals(16.dp, PizzoraTokens.shape.radiusMedium)
    assertEquals(22.dp, PizzoraTokens.shape.radiusLarge)
    assertEquals(1.dp, PizzoraTokens.shape.borderThin)
    assertEquals(2.dp, PizzoraTokens.shape.borderThick)
    assertEquals(1.dp, PizzoraTokens.elevation.cardRest)
    assertEquals(4.dp, PizzoraTokens.elevation.cardPressed)
    assertEquals(250, PizzoraTokens.motion.durationMedium)
    assertEquals(0.98f, PizzoraTokens.motion.pressScale, 0.001f)
    assertEquals(1.02f, PizzoraTokens.motion.selectedScale, 0.001f)
    assertTrue(PizzoraTokens.zIndex.toast > PizzoraTokens.zIndex.modal)
  }

  @Test
  fun `theme provides tokens through the composition local`() {
    var observed: Any? = null
    composeTestRule.setContent {
      PizzoraTheme {
        observed = LocalPizzoraTokens.current
      }
    }
    composeTestRule.waitForIdle()
    assertSame(PizzoraTokens, observed)
  }

  @Test
  fun `button renders every variant and state with a 48dp touch target`() {
    var clicks = 0
    composeTestRule.setContent {
      PizzoraTheme {
        Column {
          PizzoraButtonVariant.entries.forEach { variant ->
            PizzoraButton(
              text = variant.name,
              onClick = { clicks++ },
              variant = variant,
              testTag = "button_${variant.name.lowercase()}",
            )
          }
          PizzoraButton(
            text = "Loading",
            onClick = { clicks++ },
            loading = true,
            testTag = "button_loading",
          )
          PizzoraButton(
            text = "Disabled",
            onClick = { clicks++ },
            enabled = false,
            testTag = "button_disabled",
          )
          PizzoraButton(
            text = "With icons",
            onClick = { clicks++ },
            leadingIcon = Icons.Filled.Add,
            trailingIcon = Icons.Filled.Add,
            testTag = "button_icons",
          )
        }
      }
    }

    PizzoraButtonVariant.entries.forEach { variant ->
      composeTestRule.onNodeWithTag("button_${variant.name.lowercase()}")
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)
    }
    composeTestRule.onNodeWithTag("button_primary").performClick()
    assertEquals(1, clicks)
    composeTestRule.onNodeWithTag("button_loading").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("button_loading_loading").assertIsDisplayed()
    composeTestRule.onNodeWithTag("button_disabled").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("button_disabled_label", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("button_icons_leading_icon", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("button_icons_trailing_icon", useUnmergedTree = true).assertIsDisplayed()
  }

  @Test
  fun `chip renders every variant with selection and a 48dp touch target`() {
    composeTestRule.setContent {
      PizzoraTheme {
        Column {
          PizzoraChipVariant.entries.forEach { variant ->
            PizzoraChip(
              label = variant.name,
              onClick = {},
              variant = variant,
              selected = false,
              testTag = "chip_${variant.name.lowercase()}",
            )
            PizzoraChip(
              label = "${variant.name} selected",
              onClick = {},
              variant = variant,
              selected = true,
              testTag = "chip_${variant.name.lowercase()}_selected",
            )
          }
        }
      }
    }

    PizzoraChipVariant.entries.forEach { variant ->
      composeTestRule.onNodeWithTag("chip_${variant.name.lowercase()}")
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)
        .assertIsNotSelected()
      composeTestRule.onNodeWithTag("chip_${variant.name.lowercase()}_selected")
        .assertIsDisplayed()
        .assertIsSelected()
    }
  }

  @Test
  fun `icon button enforces touch target and exposes tooltip semantics`() {
    var clicks = 0
    composeTestRule.setContent {
      PizzoraTheme {
        Column {
          PizzoraIconButton(
            icon = Icons.Filled.Add,
            contentDescription = "Add",
            onClick = { clicks++ },
            testTag = "icon_add",
          )
          PizzoraIconButton(
            icon = Icons.Filled.Add,
            contentDescription = "Tooltip",
            onClick = { clicks++ },
            tooltip = "Add item",
            testTag = "icon_tooltip",
          )
          PizzoraIconButton(
            icon = Icons.Filled.Add,
            contentDescription = "Disabled",
            onClick = { clicks++ },
            enabled = false,
            testTag = "icon_disabled",
          )
          PizzoraIconButtonVariant.entries.forEach { variant ->
            PizzoraIconButton(
              icon = Icons.Filled.Add,
              contentDescription = variant.name,
              onClick = {},
              variant = variant,
              testTag = "icon_${variant.name.lowercase()}",
            )
          }
        }
      }
    }

    composeTestRule.onNodeWithTag("icon_add")
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
      .assertWidthIsAtLeast(48.dp)
      .performClick()
    assertEquals(1, clicks)
    composeTestRule.onNodeWithTag("icon_disabled").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("icon_tooltip").assertIsDisplayed()
    composeTestRule.onNodeWithTag("icon_tooltip_icon", useUnmergedTree = true)
      .assertContentDescriptionEquals("Tooltip")
    composeTestRule.onNodeWithTag("icon_tooltip").performTouchInput { longClick() }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Add item").assertExists()
    PizzoraIconButtonVariant.entries.forEach { variant ->
      composeTestRule.onNodeWithTag("icon_${variant.name.lowercase()}")
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)
        .assertWidthIsAtLeast(48.dp)
    }
  }

  @Test
  fun `surface renders every semantic tone`() {
    composeTestRule.setContent {
      PizzoraTheme {
        Column {
          PizzoraSurfaceTone.entries.forEach { tone ->
            PizzoraSurface(tone = tone, testTag = "surface_${tone.name.lowercase()}") {
              Text(text = tone.name)
            }
          }
        }
      }
    }

    PizzoraSurfaceTone.entries.forEach { tone ->
      composeTestRule.onNodeWithTag("surface_${tone.name.lowercase()}").assertIsDisplayed()
    }
  }

  @Test
  fun `card supports clickable press state`() {
    var clicked = false
    composeTestRule.setContent {
      PizzoraTheme {
        PizzoraCard(onClick = { clicked = true }, testTag = "card_clickable") {
          Text(text = "Clickable card")
        }
      }
    }

    composeTestRule.onNodeWithTag("card_clickable").assertIsDisplayed().performClick()
    assertTrue(clicked)
  }

  @Test
  fun `all primitives render in light dark and kds modes`() {
    composeTestRule.setContent {
      Column {
        PizzoraTheme(darkTheme = false) {
          PizzoraComponentProbe(prefix = "light")
        }
        PizzoraTheme(darkTheme = true) {
          PizzoraComponentProbe(prefix = "dark")
        }
        PizzoraTheme(kdsMode = true) {
          PizzoraComponentProbe(prefix = "kds")
        }
      }
    }

    listOf("light", "dark", "kds").forEach { mode ->
      composeTestRule.onNodeWithTag("${mode}_button").assertExists()
      composeTestRule.onNodeWithTag("${mode}_card").assertExists()
      composeTestRule.onNodeWithTag("${mode}_chip").assertExists()
      composeTestRule.onNodeWithTag("${mode}_icon").assertExists()
      composeTestRule.onNodeWithTag("${mode}_surface").assertExists()
    }
    composeTestRule.onNodeWithTag("light_button").assertIsDisplayed()
  }
}

@Composable
private fun PizzoraComponentProbe(prefix: String) {
  Column {
    PizzoraButton(text = "Button", onClick = {}, testTag = "${prefix}_button")
    PizzoraCard(testTag = "${prefix}_card") {
      Text(text = "Card")
    }
    PizzoraChip(label = "Chip", onClick = {}, testTag = "${prefix}_chip")
    PizzoraIconButton(
      icon = Icons.Filled.Add,
      contentDescription = "Add",
      onClick = {},
      testTag = "${prefix}_icon",
    )
    PizzoraSurface(testTag = "${prefix}_surface") {
      Text(text = "Surface")
    }
  }
}

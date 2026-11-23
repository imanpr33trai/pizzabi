package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.ui.theme.PizzoraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PizzoraLayoutComponentsRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `list item renders leading icon content trailing and click target`() {
    var clicks = 0
    composeTestRule.setContent {
      PizzoraTheme {
        PizzoraListItem(
          title = "Margherita",
          subtitle = "Tomato, mozzarella, basil",
          leadingIcon = Icons.Filled.LocalPizza,
          iconContentDescription = "Pizza",
          trailing = {
            Icon(Icons.Filled.Add, contentDescription = "Add pizza")
          },
          onClick = { clicks++ },
          testTag = "item",
        )
      }
    }

    composeTestRule.onNodeWithTag("item")
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
      .assertHasClickAction()
      .performClick()
    composeTestRule.onNodeWithTag("item_leading_icon", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("item_title", useUnmergedTree = true)
      .assertIsDisplayed()
      .assertTextEquals("Margherita")
    composeTestRule.onNodeWithTag("item_subtitle", useUnmergedTree = true)
      .assertIsDisplayed()
      .assertTextEquals("Tomato, mozzarella, basil")
    composeTestRule.onNodeWithTag("item_trailing", useUnmergedTree = true).assertIsDisplayed()
    assertEquals(1, clicks)
  }

  @Test
  fun `list item omits optional slots and is inert without click handler`() {
    composeTestRule.setContent {
      PizzoraTheme {
        PizzoraListItem(title = "Plain item", testTag = "plain_item")
      }
    }

    composeTestRule.onNodeWithTag("plain_item").assertIsDisplayed().assertHasNoClickAction()
    composeTestRule.onNodeWithTag("plain_item_leading_icon").assertDoesNotExist()
    composeTestRule.onNodeWithTag("plain_item_trailing").assertDoesNotExist()
    composeTestRule.onNodeWithTag("plain_item_subtitle").assertDoesNotExist()
  }

  @Test
  fun `section renders title action content and optional divider`() {
    var actionClicks = 0
    composeTestRule.setContent {
      PizzoraTheme {
        PizzoraSection(
          title = "Popular pizzas",
          actionLabel = "See all",
          onActionClick = { actionClicks++ },
          showDivider = true,
          testTag = "section",
        ) {
          PizzoraListItem(title = "Margherita", testTag = "section_item")
        }
      }
    }

    composeTestRule.onNodeWithTag("section").assertIsDisplayed()
    composeTestRule.onNodeWithTag("section_title")
      .assertIsDisplayed()
      .assertTextEquals("Popular pizzas")
    composeTestRule.onNodeWithTag("section_action")
      .assertIsDisplayed()
      .assertHasClickAction()
      .performClick()
    composeTestRule.onNodeWithTag("section_content").assertIsDisplayed()
    composeTestRule.onNodeWithTag("section_item").assertIsDisplayed()
    composeTestRule.onNodeWithTag("section_divider").assertIsDisplayed()
    assertEquals(1, actionClicks)
  }

  @Test
  fun `section omits action and divider when not requested`() {
    composeTestRule.setContent {
      PizzoraTheme {
        PizzoraSection(title = "No extras", testTag = "plain_section") {}
      }
    }

    composeTestRule.onNodeWithTag("plain_section_title").assertIsDisplayed()
    composeTestRule.onNodeWithTag("plain_section_action").assertDoesNotExist()
    composeTestRule.onNodeWithTag("plain_section_divider").assertDoesNotExist()
  }

  @Test
  fun `screen scaffold wires top bar snackbar host fab and bottom navigation`() {
    var fabClicks = 0
    var tabClicks = 0
    composeTestRule.setContent {
      Box(Modifier.width(400.dp)) {
        PizzoraTheme {
          PizzoraScreenScaffold(
            title = "Menu",
            snackbarHostState = SnackbarHostState(),
            navigationItems = listOf(
              PizzoraNavigationItem(
                label = "Orders",
                icon = Icons.Filled.LocalPizza,
                selected = true,
                onClick = { tabClicks++ },
              ),
            ),
            floatingActionButton = {
              FloatingActionButton(onClick = { fabClicks++ }) {
                Icon(Icons.Filled.Add, contentDescription = "Add order")
              }
            },
            testTag = "scaffold",
          ) {}
        }
      }
    }

    composeTestRule.onNodeWithTag("scaffold").assertIsDisplayed()
    composeTestRule.onNodeWithTag("scaffold_top_bar").assertIsDisplayed()
    composeTestRule.onNodeWithTag("scaffold_content").assertIsDisplayed()
    composeTestRule.onNodeWithTag("scaffold_snackbar_host").assertExists()
    composeTestRule.onNodeWithTag("scaffold_fab").assertIsDisplayed()
    composeTestRule.onNodeWithTag("scaffold_bottom_bar").assertIsDisplayed()
    composeTestRule.onNodeWithTag("scaffold_nav_rail").assertDoesNotExist()
    composeTestRule.onNodeWithTag("scaffold_tab_orders")
      .assertIsDisplayed()
      .performClick()
    composeTestRule.onNodeWithTag("scaffold_fab").performClick()
    assertEquals(1, tabClicks)
    assertEquals(1, fabClicks)
  }

  @Test
  fun `screen scaffold switches to navigation rail on wide screens`() {
    composeTestRule.setContent {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        Box(Modifier.requiredWidth(900.dp).height(600.dp)) {
          PizzoraTheme {
            PizzoraScreenScaffold(
              title = "Menu",
              navigationItems = listOf(
                PizzoraNavigationItem(
                  label = "Orders",
                  icon = Icons.Filled.LocalPizza,
                  onClick = {},
                ),
              ),
              testTag = "wide_scaffold",
            ) {}
          }
        }
      }
    }

    composeTestRule.onNodeWithTag("wide_scaffold_nav_rail").assertExists()
    composeTestRule.onNodeWithTag("wide_scaffold_bottom_bar").assertDoesNotExist()
    composeTestRule.onNodeWithTag("wide_scaffold_tab_orders")
      .assertExists()
      .assertHasClickAction()
  }

  @Test
  fun `layout components render in light dark and kds modes`() {
    composeTestRule.setContent {
      Column {
        PizzoraTheme(darkTheme = false) {
          PizzoraLayoutProbe(prefix = "light")
        }
        PizzoraTheme(darkTheme = true) {
          PizzoraLayoutProbe(prefix = "dark")
        }
        PizzoraTheme(kdsMode = true) {
          PizzoraLayoutProbe(prefix = "kds")
        }
      }
    }

    listOf("light", "dark", "kds").forEach { mode ->
      composeTestRule.onNodeWithTag("${mode}_scaffold").assertExists()
      composeTestRule.onNodeWithTag("${mode}_section").assertExists()
      composeTestRule.onNodeWithTag("${mode}_item").assertExists()
      composeTestRule.onNodeWithTag("${mode}_section_divider").assertExists()
    }
    composeTestRule.onNodeWithTag("light_item").assertIsDisplayed()

    fun themeColor(mode: String): String = composeTestRule.onNodeWithTag("${mode}_theme_color")
      .fetchSemanticsNode()
      .config[SemanticsProperties.Text]
      .first()
      .text
    val lightColor = themeColor("light")
    val darkColor = themeColor("dark")
    val kdsColor = themeColor("kds")
    assertNotEquals(lightColor, darkColor)
    assertEquals(darkColor, kdsColor)
  }
}

@androidx.compose.runtime.Composable
private fun PizzoraLayoutProbe(prefix: String) {
  PizzoraScreenScaffold(
    title = "$prefix screen",
    navigationItems = listOf(
      PizzoraNavigationItem(
        label = "Menu",
        icon = Icons.Filled.LocalPizza,
        onClick = {},
      ),
    ),
    testTag = "${prefix}_scaffold",
  ) {
    PizzoraSection(
      title = "$prefix section",
      showDivider = true,
      testTag = "${prefix}_section",
    ) {
      PizzoraListItem(title = "$prefix item", testTag = "${prefix}_item")
    }
    Text(
      text = MaterialTheme.colorScheme.primary.toArgb().toString(),
      modifier = Modifier.testTag("${prefix}_theme_color"),
    )
  }
}

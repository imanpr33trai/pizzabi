package com.example.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.ui.theme.PizzoraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PizzoraAuthComponentsRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `outlined button renders its label in a token-sized touch target and clicks`() {
    var clicks = 0
    composeTestRule.setContent {
      PizzoraTheme {
        PizzoraOutlinedButton(
          text = "Register",
          onClick = { clicks++ },
          testTag = "login_go_register_button",
        )
      }
    }

    composeTestRule.onNodeWithTag("login_go_register_button")
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
      .performClick()
    composeTestRule.onNodeWithTag("login_go_register_button_label", useUnmergedTree = true)
      .assertIsDisplayed()
      .assertTextEquals("Register")
    assertEquals(1, clicks)
  }

  @Test
  fun `text field binds label leading icon value and trailing action`() {
    var editedValue = ""
    var trailingClicks = 0
    composeTestRule.setContent {
      PizzoraTheme {
        PizzoraTextField(
          value = "abc",
          onValueChange = { editedValue = it },
          label = "Email",
          leadingIcon = Icons.Filled.Email,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
          trailingIcon = {
            PizzoraIconButton(
              icon = Icons.Filled.Visibility,
              contentDescription = "Show password",
              onClick = { trailingClicks++ },
              testTag = "password_toggle",
            )
          },
          testTag = "login_email_input",
        )
      }
    }

    composeTestRule.onNodeWithTag("login_email_input")
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
      .performTextReplacement("abcd")
    assertEquals("abcd", editedValue)
    composeTestRule.onNodeWithTag("login_email_input_label", useUnmergedTree = true)
      .assertIsDisplayed()
      .assertTextEquals("Email")
    composeTestRule.onNodeWithTag("login_email_input_leading_icon", useUnmergedTree = true)
      .assertIsDisplayed()
    composeTestRule.onNodeWithTag("password_toggle").assertIsDisplayed().performClick()
    assertEquals(1, trailingClicks)
  }

  @Test
  fun `text button keeps a 48dp touch target for inline auth links`() {
    var clicks = 0
    composeTestRule.setContent {
      PizzoraTheme {
        PizzoraTextButton(
          text = "Forgot Password?",
          onClick = { clicks++ },
          testTag = "login_forgot_password_button",
        )
      }
    }

    composeTestRule.onNodeWithTag("login_forgot_password_button")
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
      .performClick()
    composeTestRule.onNodeWithTag("login_forgot_password_button_label", useUnmergedTree = true)
      .assertIsDisplayed()
      .assertTextEquals("Forgot Password?")
    assertEquals(1, clicks)
  }
}

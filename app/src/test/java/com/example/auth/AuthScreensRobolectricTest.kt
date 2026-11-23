package com.example.auth

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
class AuthScreensRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `login screen routes credentials and navigation actions`() {
    var submittedEmail = ""
    var submittedPassword = ""
    var registerClicks = 0
    var forgotClicks = 0
    var verificationEmail = ""

    composeTestRule.setContent {
      PizzoraTheme {
        LoginScreen(
          uiState = AuthUiState(),
          onLoginClick = { email, password ->
            submittedEmail = email
            submittedPassword = password
          },
          onNavigateToRegister = { registerClicks++ },
          onNavigateToForgotPassword = { forgotClicks++ },
          onNavigateToVerifyEmail = { verificationEmail = it },
        )
      }
    }

    composeTestRule.onNodeWithTag("login_screen").assertIsDisplayed()
    listOf(
      "login_email_input",
      "login_password_input",
      "login_submit_button",
      "login_forgot_password_button",
      "login_verify_email_button",
      "login_go_register_button",
    ).forEach { tag ->
      composeTestRule.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
    }
    val buttonWidth = composeTestRule.onNodeWithTag("login_submit_button")
      .fetchSemanticsNode().size.width
    val inputWidth = composeTestRule.onNodeWithTag("login_email_input")
      .fetchSemanticsNode().size.width
    assertEquals(buttonWidth, inputWidth)
    composeTestRule.onNodeWithTag("login_submit_button_label", useUnmergedTree = true)
      .assertTextEquals("Sign In")
    composeTestRule.onNodeWithTag("login_go_register_button_label", useUnmergedTree = true)
      .assertTextEquals("Create Account")

    composeTestRule.onNodeWithTag("login_email_input").performScrollTo().performTextReplacement("hello@pizzora.test")
    composeTestRule.onNodeWithTag("login_password_input").performScrollTo().performTextReplacement("secret")
    composeTestRule.onNodeWithTag("login_submit_button").performScrollTo().performClick()
    assertEquals("hello@pizzora.test", submittedEmail)
    assertEquals("secret", submittedPassword)

    composeTestRule.onNodeWithTag("login_forgot_password_button").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("login_verify_email_button").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("login_go_register_button").performScrollTo().performClick()
    assertEquals(1, forgotClicks)
    assertEquals("hello@pizzora.test", verificationEmail)
    assertEquals(1, registerClicks)
  }

  @Test
  fun `register screen routes all fields and submit`() {
    var submittedEmail = ""
    var submittedPassword = ""
    var submittedName = ""
    var submittedPhone = ""
    var backClicks = 0

    composeTestRule.setContent {
      PizzoraTheme {
        RegisterScreen(
          uiState = AuthUiState(),
          onRegisterClick = { email, password, fullName, phone ->
            submittedEmail = email
            submittedPassword = password
            submittedName = fullName
            submittedPhone = phone
          },
          onBackClick = { backClicks++ },
        )
      }
    }

    listOf(
      "register_name_input",
      "register_phone_input",
      "register_email_input",
      "register_password_input",
      "register_submit_button",
    ).forEach { tag ->
      composeTestRule.onNodeWithTag(tag).performScrollTo().assertHeightIsAtLeast(48.dp)
    }

    composeTestRule.onNodeWithTag("register_name_input").performTextReplacement("Test User")
    composeTestRule.onNodeWithTag("register_phone_input").performTextReplacement("555-1234")
    composeTestRule.onNodeWithTag("register_email_input").performTextReplacement("register@test.com")
    composeTestRule.onNodeWithTag("register_password_input").performTextReplacement("pwd123")
    composeTestRule.onNodeWithTag("register_submit_button").performClick()

    assertEquals("register@test.com", submittedEmail)
    assertEquals("pwd123", submittedPassword)
    assertEquals("Test User", submittedName)
    assertEquals("555-1234", submittedPhone)

    composeTestRule.onNodeWithTag("auth_back_button").performClick()
    assertEquals(1, backClicks)
  }

  @Test
  fun `verify email screen routes token and resend`() {
    var verifyToken = ""
    var resendEmail = ""
    var backClicks = 0

    composeTestRule.setContent {
      PizzoraTheme {
        VerifyEmailScreen(
          initialEmail = "verify@test.com",
          uiState = AuthUiState(),
          onVerifyClick = { verifyToken = it },
          onResendClick = { resendEmail = it },
          onBackClick = { backClicks++ },
        )
      }
    }

    listOf(
      "verify_email_input",
      "verify_token_input",
      "verify_submit_button",
      "verify_resend_button",
    ).forEach { tag ->
      composeTestRule.onNodeWithTag(tag).performScrollTo().assertHeightIsAtLeast(48.dp)
    }

    composeTestRule.onNodeWithTag("verify_token_input").performTextReplacement("123456")
    composeTestRule.onNodeWithTag("verify_submit_button").performClick()
    assertEquals("123456", verifyToken)

    composeTestRule.onNodeWithTag("verify_resend_button").performClick()
    assertEquals("verify@test.com", resendEmail)

    composeTestRule.onNodeWithTag("auth_back_button").performClick()
    assertEquals(1, backClicks)
  }

  @Test
  fun `forgot password screen routes email token and new password`() {
    var resetEmail = ""
    var resetToken = ""
    var newPassword = ""
    var backClicks = 0

    composeTestRule.setContent {
      PizzoraTheme {
        ForgotPasswordScreen(
          uiState = AuthUiState(),
          onSendResetClick = { resetEmail = it },
          onResetPasswordClick = { token, pwd ->
            resetToken = token
            newPassword = pwd
          },
          onBackClick = { backClicks++ },
        )
      }
    }

    listOf(
      "forgot_email_input",
      "forgot_send_button",
      "reset_token_input",
      "reset_password_input",
      "reset_submit_button",
    ).forEach { tag ->
      composeTestRule.onNodeWithTag(tag).performScrollTo().assertHeightIsAtLeast(48.dp)
    }

    composeTestRule.onNodeWithTag("forgot_email_input").performTextReplacement("forgot@test.com")
    composeTestRule.onNodeWithTag("forgot_send_button").performClick()
    assertEquals("forgot@test.com", resetEmail)

    composeTestRule.onNodeWithTag("reset_token_input").performTextReplacement("abcdef")
    composeTestRule.onNodeWithTag("reset_password_input").performTextReplacement("newpwd")
    composeTestRule.onNodeWithTag("reset_submit_button").performClick()
    assertEquals("abcdef", resetToken)
    assertEquals("newpwd", newPassword)

    composeTestRule.onNodeWithTag("auth_back_button").performClick()
    assertEquals(1, backClicks)
  }

  @Test
  fun `status banner shows error message`() {
    composeTestRule.setContent {
      PizzoraTheme {
        LoginScreen(
          uiState = AuthUiState(
            isLoading = false,
            errorMessage = "Invalid credentials",
            infoMessage = null,
            pendingVerificationEmail = null,
          ),
          onLoginClick = { _, _ -> },
          onNavigateToRegister = {},
          onNavigateToForgotPassword = {},
          onNavigateToVerifyEmail = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("auth_status_banner").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Invalid credentials").assertIsDisplayed()
  }

  @Test
  fun `status banner shows info message`() {
    composeTestRule.setContent {
      PizzoraTheme {
        LoginScreen(
          uiState = AuthUiState(
            isLoading = false,
            errorMessage = null,
            infoMessage = "Check your email",
            pendingVerificationEmail = "test@test.com",
          ),
          onLoginClick = { _, _ -> },
          onNavigateToRegister = {},
          onNavigateToForgotPassword = {},
          onNavigateToVerifyEmail = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("auth_status_banner").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Check your email").assertIsDisplayed()
  }
}

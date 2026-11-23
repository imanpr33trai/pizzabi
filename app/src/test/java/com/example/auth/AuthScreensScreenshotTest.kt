package com.example.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.ui.theme.PizzoraTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthScreensScreenshotTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Composable
  private fun login() = LoginScreen(
    uiState = AuthUiState(errorMessage = "Invalid credentials"),
    onLoginClick = { _, _ -> },
    onNavigateToRegister = {},
    onNavigateToForgotPassword = {},
    onNavigateToVerifyEmail = {},
  )

  @Composable
  private fun register() = RegisterScreen(
    uiState = AuthUiState(),
    onRegisterClick = { _, _, _, _ -> },
    onBackClick = {},
  )

  @Composable
  private fun verifyEmail() = VerifyEmailScreen(
    initialEmail = "verify@test.com",
    uiState = AuthUiState(),
    onVerifyClick = {},
    onResendClick = {},
    onBackClick = {},
  )

  @Composable
  private fun forgotPassword() = ForgotPasswordScreen(
    uiState = AuthUiState(),
    onSendResetClick = {},
    onResetPasswordClick = { _, _ -> },
    onBackClick = {},
  )

  @Test
  fun `login light`() {
    composeTestRule.setContent { PizzoraTheme(darkTheme = false) { login() } }
    composeTestRule.onNodeWithTag("login_screen").captureRoboImage("auth_login_light.png")
  }

  @Test
  fun `login dark`() {
    composeTestRule.setContent { PizzoraTheme(darkTheme = true) { login() } }
    composeTestRule.onNodeWithTag("login_screen").captureRoboImage("auth_login_dark.png")
  }

  @Test
  fun `login kds`() {
    composeTestRule.setContent { PizzoraTheme(kdsMode = true) { login() } }
    composeTestRule.onNodeWithTag("login_screen").captureRoboImage("auth_login_kds.png")
  }

  @Test
  fun `register light`() {
    composeTestRule.setContent { PizzoraTheme(darkTheme = false) { register() } }
    composeTestRule.onNodeWithTag("register_screen").captureRoboImage("auth_register_light.png")
  }

  @Test
  fun `register dark`() {
    composeTestRule.setContent { PizzoraTheme(darkTheme = true) { register() } }
    composeTestRule.onNodeWithTag("register_screen").captureRoboImage("auth_register_dark.png")
  }

  @Test
  fun `register kds`() {
    composeTestRule.setContent { PizzoraTheme(kdsMode = true) { register() } }
    composeTestRule.onNodeWithTag("register_screen").captureRoboImage("auth_register_kds.png")
  }

  @Test
  fun `verify email light`() {
    composeTestRule.setContent { PizzoraTheme(darkTheme = false) { verifyEmail() } }
    composeTestRule.onNodeWithTag("verify_email_screen").captureRoboImage("auth_verify_email_light.png")
  }

  @Test
  fun `verify email dark`() {
    composeTestRule.setContent { PizzoraTheme(darkTheme = true) { verifyEmail() } }
    composeTestRule.onNodeWithTag("verify_email_screen").captureRoboImage("auth_verify_email_dark.png")
  }

  @Test
  fun `verify email kds`() {
    composeTestRule.setContent { PizzoraTheme(kdsMode = true) { verifyEmail() } }
    composeTestRule.onNodeWithTag("verify_email_screen").captureRoboImage("auth_verify_email_kds.png")
  }

  @Test
  fun `forgot password light`() {
    composeTestRule.setContent { PizzoraTheme(darkTheme = false) { forgotPassword() } }
    composeTestRule.onNodeWithTag("forgot_password_screen").captureRoboImage("auth_forgot_password_light.png")
  }

  @Test
  fun `forgot password dark`() {
    composeTestRule.setContent { PizzoraTheme(darkTheme = true) { forgotPassword() } }
    composeTestRule.onNodeWithTag("forgot_password_screen").captureRoboImage("auth_forgot_password_dark.png")
  }

  @Test
  fun `forgot password kds`() {
    composeTestRule.setContent { PizzoraTheme(kdsMode = true) { forgotPassword() } }
    composeTestRule.onNodeWithTag("forgot_password_screen").captureRoboImage("auth_forgot_password_kds.png")
  }
}

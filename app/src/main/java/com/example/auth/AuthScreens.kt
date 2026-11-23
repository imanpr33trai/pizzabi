package com.example.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.example.R
import com.example.ui.components.PizzoraButton
import com.example.ui.components.PizzoraIconButton
import com.example.ui.components.PizzoraOutlinedButton
import com.example.ui.components.PizzoraSection
import com.example.ui.components.PizzoraSurface
import com.example.ui.components.PizzoraSurfaceTone
import com.example.ui.components.PizzoraTextButton
import com.example.ui.components.PizzoraTextField
import com.example.ui.theme.CharredInk
import com.example.ui.theme.LocalPizzoraSemanticColors
import com.example.ui.theme.LocalPizzoraTokens
import com.example.ui.theme.OvenStoneSurfaceVariant
import com.example.ui.theme.PureWhiteSurface

@Composable
fun LoginScreen(
  uiState: AuthUiState,
  onLoginClick: (String, String) -> Unit,
  onNavigateToRegister: () -> Unit,
  onNavigateToForgotPassword: () -> Unit,
  onNavigateToVerifyEmail: (String) -> Unit,
) {
  val tokens = LocalPizzoraTokens.current
  var email by rememberSaveable { mutableStateOf("") }
  var password by rememberSaveable { mutableStateOf("") }

  AuthScaffoldLayout(screenTag = "login_screen") {
    AuthHeroBanner()
    Spacer(modifier = Modifier.height(tokens.spacing.space20))
    Text(
      text = stringResource(R.string.auth_sign_in_title),
      style = MaterialTheme.typography.headlineMedium,
      color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(modifier = Modifier.height(tokens.spacing.space8))
    Text(
      text = stringResource(R.string.auth_sign_in_subtitle),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(tokens.spacing.space16))

    AuthStatusBanner(uiState = uiState)

    PizzoraTextField(
      modifier = Modifier.fillMaxWidth(),
      value = email,
      onValueChange = { email = it },
      label = stringResource(R.string.label_email),
      leadingIcon = Icons.Filled.Email,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
      testTag = "login_email_input",
    )
    Spacer(modifier = Modifier.height(tokens.spacing.space12))

    AuthPasswordField(
      value = password,
      onValueChange = { password = it },
      label = stringResource(R.string.label_password),
      testTag = "login_password_input",
    )
    Spacer(modifier = Modifier.height(tokens.spacing.space16))

    PizzoraButton(
      text = stringResource(R.string.action_sign_in),
      onClick = { onLoginClick(email, password) },
      enabled = !uiState.isLoading,
      loading = uiState.isLoading,
      modifier = Modifier.fillMaxWidth(),
      testTag = "login_submit_button",
    )

    Spacer(modifier = Modifier.height(tokens.spacing.space12))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PizzoraTextButton(
        text = stringResource(R.string.action_forgot_password),
        onClick = onNavigateToForgotPassword,
        testTag = "login_forgot_password_button",
      )
      PizzoraTextButton(
        text = stringResource(R.string.action_verify_token),
        onClick = { onNavigateToVerifyEmail(email) },
        testTag = "login_verify_email_button",
      )
    }

    Spacer(modifier = Modifier.height(tokens.spacing.space8))
    PizzoraOutlinedButton(
      text = stringResource(R.string.auth_create_account),
      onClick = onNavigateToRegister,
      modifier = Modifier.fillMaxWidth(),
      testTag = "login_go_register_button",
    )
  }
}

@Composable
fun RegisterScreen(
  uiState: AuthUiState,
  onRegisterClick: (email: String, password: String, fullName: String, phone: String) -> Unit,
  onBackClick: () -> Unit,
) {
  val tokens = LocalPizzoraTokens.current
  var fullName by rememberSaveable { mutableStateOf("") }
  var phone by rememberSaveable { mutableStateOf("") }
  var email by rememberSaveable { mutableStateOf("") }
  var password by rememberSaveable { mutableStateOf("") }

  AuthScaffoldLayout(screenTag = "register_screen") {
    AuthBackHeader(
      title = stringResource(R.string.auth_create_account),
      onBackClick = onBackClick,
    )
    Spacer(modifier = Modifier.height(tokens.spacing.space12))
    AuthStatusBanner(uiState = uiState)

    PizzoraSection(
      title = stringResource(R.string.auth_register_details_title),
      testTag = "register_details_section",
    ) {
      PizzoraTextField(
        modifier = Modifier.fillMaxWidth(),
        value = fullName,
        onValueChange = { fullName = it },
        label = stringResource(R.string.label_full_name),
        leadingIcon = Icons.Filled.Person,
        testTag = "register_name_input",
      )
      PizzoraTextField(
        modifier = Modifier.fillMaxWidth(),
        value = phone,
        onValueChange = { phone = it },
        label = stringResource(R.string.label_phone),
        leadingIcon = Icons.Filled.Phone,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        testTag = "register_phone_input",
      )
      PizzoraTextField(
        modifier = Modifier.fillMaxWidth(),
        value = email,
        onValueChange = { email = it },
        label = stringResource(R.string.label_email),
        leadingIcon = Icons.Filled.Email,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        testTag = "register_email_input",
      )
      PizzoraTextField(
        modifier = Modifier.fillMaxWidth(),
        value = password,
        onValueChange = { password = it },
        label = stringResource(R.string.label_password),
        leadingIcon = Icons.Filled.Lock,
        visualTransformation = PasswordVisualTransformation(),
        testTag = "register_password_input",
      )
      PizzoraButton(
        text = stringResource(R.string.action_register),
        onClick = { onRegisterClick(email, password, fullName, phone) },
        enabled = !uiState.isLoading,
        loading = uiState.isLoading,
        modifier = Modifier.fillMaxWidth(),
        testTag = "register_submit_button",
      )
    }
  }
}

@Composable
fun VerifyEmailScreen(
  initialEmail: String,
  uiState: AuthUiState,
  onVerifyClick: (String) -> Unit,
  onResendClick: (String) -> Unit,
  onBackClick: () -> Unit,
) {
  val tokens = LocalPizzoraTokens.current
  var email by rememberSaveable { mutableStateOf(initialEmail) }
  var token by rememberSaveable { mutableStateOf("") }

  AuthScaffoldLayout(screenTag = "verify_email_screen") {
    AuthBackHeader(
      title = stringResource(R.string.auth_verify_email_title),
      onBackClick = onBackClick,
    )
    Spacer(modifier = Modifier.height(tokens.spacing.space12))
    AuthStatusBanner(uiState = uiState)

    PizzoraTextField(
      modifier = Modifier.fillMaxWidth(),
      value = email,
      onValueChange = { email = it },
      label = stringResource(R.string.label_email),
      leadingIcon = Icons.Filled.Email,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
      testTag = "verify_email_input",
    )
    Spacer(modifier = Modifier.height(tokens.spacing.space12))

    PizzoraTextField(
      modifier = Modifier.fillMaxWidth(),
      value = token,
      onValueChange = { token = it },
      label = stringResource(R.string.label_token),
      leadingIcon = Icons.Filled.VpnKey,
      testTag = "verify_token_input",
    )
    Spacer(modifier = Modifier.height(tokens.spacing.space16))

    PizzoraButton(
      text = stringResource(R.string.action_verify),
      onClick = { onVerifyClick(token) },
      enabled = !uiState.isLoading,
      loading = uiState.isLoading,
      modifier = Modifier.fillMaxWidth(),
      testTag = "verify_submit_button",
    )

    Spacer(modifier = Modifier.height(tokens.spacing.space12))
    PizzoraOutlinedButton(
      text = stringResource(R.string.action_resend_code),
      onClick = { onResendClick(email) },
      enabled = !uiState.isLoading,
      modifier = Modifier.fillMaxWidth(),
      testTag = "verify_resend_button",
    )
  }
}

@Composable
fun ForgotPasswordScreen(
  uiState: AuthUiState,
  onSendResetClick: (String) -> Unit,
  onResetPasswordClick: (token: String, newPassword: String) -> Unit,
  onBackClick: () -> Unit,
) {
  val tokens = LocalPizzoraTokens.current
  var email by rememberSaveable { mutableStateOf("") }
  var resetToken by rememberSaveable { mutableStateOf("") }
  var newPassword by rememberSaveable { mutableStateOf("") }

  AuthScaffoldLayout(screenTag = "forgot_password_screen") {
    AuthBackHeader(
      title = stringResource(R.string.auth_forgot_password_title),
      onBackClick = onBackClick,
    )
    Spacer(modifier = Modifier.height(tokens.spacing.space12))
    AuthStatusBanner(uiState = uiState)

    PizzoraTextField(
      modifier = Modifier.fillMaxWidth(),
      value = email,
      onValueChange = { email = it },
      label = stringResource(R.string.label_email),
      leadingIcon = Icons.Filled.Email,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
      testTag = "forgot_email_input",
    )
    Spacer(modifier = Modifier.height(tokens.spacing.space12))

    PizzoraOutlinedButton(
      text = stringResource(R.string.action_send_reset),
      onClick = { onSendResetClick(email) },
      enabled = !uiState.isLoading,
      modifier = Modifier.fillMaxWidth(),
      testTag = "forgot_send_button",
    )

    Spacer(modifier = Modifier.height(tokens.spacing.space20))
    PizzoraTextField(
      modifier = Modifier.fillMaxWidth(),
      value = resetToken,
      onValueChange = { resetToken = it },
      label = stringResource(R.string.label_token),
      leadingIcon = Icons.Filled.VpnKey,
      testTag = "reset_token_input",
    )
    Spacer(modifier = Modifier.height(tokens.spacing.space12))

    AuthPasswordField(
      value = newPassword,
      onValueChange = { newPassword = it },
      label = stringResource(R.string.label_new_password),
      testTag = "reset_password_input",
    )
    Spacer(modifier = Modifier.height(tokens.spacing.space16))

    PizzoraButton(
      text = stringResource(R.string.action_reset_password),
      onClick = { onResetPasswordClick(resetToken, newPassword) },
      enabled = !uiState.isLoading,
      loading = uiState.isLoading,
      modifier = Modifier.fillMaxWidth(),
      testTag = "reset_submit_button",
    )
  }
}

@Composable
private fun AuthScaffoldLayout(
  screenTag: String,
  content: @Composable () -> Unit,
) {
  val tokens = LocalPizzoraTokens.current
  PizzoraSurface(
    modifier = Modifier.fillMaxSize(),
    tone = PizzoraSurfaceTone.Background,
    testTag = screenTag,
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .verticalScroll(rememberScrollState())
        .padding(
          horizontal = tokens.spacing.space20,
          vertical = tokens.spacing.space16,
        ),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .widthIn(max = tokens.sizing.contentMaxWidth),
      ) {
        content()
      }
    }
  }
}

@Composable
private fun AuthHeroBanner() {
  val tokens = LocalPizzoraTokens.current
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(tokens.sizing.heroBannerHeight)
      .clip(RoundedCornerShape(tokens.shape.radiusLarge)),
  ) {
    Image(
      painter = painterResource(id = R.drawable.img_pizzora_hero_oven_1794579613265),
      contentDescription = stringResource(R.string.hero_oven_cd),
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxSize(),
    )
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors = listOf(
              CharredInk.copy(alpha = 0f),
              CharredInk.copy(alpha = 0.8f),
            ),
          ),
        )
        .padding(tokens.spacing.space16),
      contentAlignment = Alignment.BottomStart,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space8),
      ) {
        Icon(
          imageVector = Icons.Filled.LocalPizza,
          contentDescription = null,
          tint = LocalPizzoraSemanticColors.current.kdsPendingAmber,
        )
        Column {
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            color = PureWhiteSurface,
          )
          Text(
            text = stringResource(R.string.brand_tagline),
            style = MaterialTheme.typography.labelMedium,
            color = OvenStoneSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun AuthBackHeader(
  title: String,
  onBackClick: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth(),
  ) {
    PizzoraIconButton(
      icon = Icons.AutoMirrored.Filled.ArrowBack,
      contentDescription = stringResource(R.string.action_back),
      onClick = onBackClick,
      testTag = "auth_back_button",
    )
    Text(
      text = title,
      style = MaterialTheme.typography.headlineSmall,
    )
  }
}

@Composable
private fun AuthPasswordField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  testTag: String,
) {
  var visible by rememberSaveable { mutableStateOf(false) }
  PizzoraTextField(
    modifier = Modifier.fillMaxWidth(),
    value = value,
    onValueChange = onValueChange,
    label = label,
    leadingIcon = Icons.Filled.Lock,
    trailingIcon = {
      PizzoraIconButton(
        icon = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
        contentDescription = stringResource(
          if (visible) R.string.action_hide_password else R.string.action_show_password,
        ),
        onClick = { visible = !visible },
        testTag = "${testTag}_toggle",
      )
    },
    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
    testTag = testTag,
  )
}

@Composable
private fun AuthStatusBanner(uiState: AuthUiState) {
  val tokens = LocalPizzoraTokens.current
  AnimatedVisibility(visible = uiState.errorMessage != null || uiState.infoMessage != null) {
    val isError = uiState.errorMessage != null
    val message = uiState.errorMessage ?: uiState.infoMessage.orEmpty()

    PizzoraSurface(
      tone = if (isError) {
        PizzoraSurfaceTone.ErrorContainer
      } else {
        PizzoraSurfaceTone.SecondaryContainer
      },
      shape = RoundedCornerShape(tokens.shape.radiusMedium),
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = tokens.spacing.space16),
      testTag = "auth_status_banner",
    ) {
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(tokens.spacing.space16),
      )
    }
  }
}

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.PizzoraBannerTone
import com.example.ui.components.PizzoraStatusBanner
import com.example.ui.theme.BrightAmber
import com.example.ui.theme.WarmParchmentText

private fun isValidEmailInput(value: String): Boolean {
  val trimmed = value.trim()
  val atIdx = trimmed.indexOf('@')
  val dotIdx = trimmed.lastIndexOf('.')
  return atIdx > 0 && dotIdx > atIdx + 1 && dotIdx < trimmed.length - 1 && !trimmed.contains(' ')
}

private fun isValidPhoneInput(value: String): Boolean =
  value.count { it.isDigit() } >= 8

@Composable
fun LoginScreen(
  uiState: AuthUiState,
  onLoginClick: (String, String) -> Unit,
  onNavigateToRegister: () -> Unit,
  onNavigateToForgotPassword: () -> Unit,
  onNavigateToVerifyEmail: (String) -> Unit,
) {
  var email by rememberSaveable { mutableStateOf("") }
  var password by rememberSaveable { mutableStateOf("") }
  var passwordVisible by rememberSaveable { mutableStateOf(false) }
  var attemptedSubmit by rememberSaveable { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current

  val emailError = if (attemptedSubmit && !isValidEmailInput(email)) {
    stringResource(R.string.auth_error_invalid_email)
  } else {
    null
  }
  val passwordError = if (attemptedSubmit && password.length < 8) {
    stringResource(R.string.auth_error_short_password)
  } else {
    null
  }

  val submitLogin = {
    attemptedSubmit = true
    if (isValidEmailInput(email) && password.length >= 8) {
      focusManager.clearFocus()
      onLoginClick(email, password)
    }
  }

  AuthScaffoldLayout {
    AuthHeroBanner()
    Spacer(modifier = Modifier.height(20.dp))
    Text(
      text = stringResource(R.string.auth_sign_in_title),
      style = MaterialTheme.typography.headlineMedium,
      color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = stringResource(R.string.auth_sign_in_subtitle),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(16.dp))

    AuthStatusBanner(uiState = uiState)

    OutlinedTextField(
      value = email,
      onValueChange = { email = it },
      label = { Text(stringResource(R.string.label_email)) },
      leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
      singleLine = true,
      isError = emailError != null,
      supportingText = emailError?.let { err -> { Text(err) } },
      keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Next,
      ),
      keyboardActions = KeyboardActions(
        onNext = { focusManager.moveFocus(FocusDirection.Down) },
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("login_email_input"),
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
      value = password,
      onValueChange = { password = it },
      label = { Text(stringResource(R.string.label_password)) },
      leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
      trailingIcon = {
        PasswordVisibilityToggle(
          visible = passwordVisible,
          onToggle = { passwordVisible = !passwordVisible },
        )
      },
      visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
      singleLine = true,
      isError = passwordError != null,
      supportingText = passwordError?.let { err -> { Text(err) } },
      keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
      ),
      keyboardActions = KeyboardActions(
        onDone = { if (!uiState.isLoading) submitLogin() },
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("login_password_input"),
    )
    Spacer(modifier = Modifier.height(16.dp))

    Button(
      onClick = submitLogin,
      enabled = !uiState.isLoading,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 52.dp)
        .minimumInteractiveComponentSize()
        .testTag("login_submit_button"),
    ) {
      if (uiState.isLoading) {
        CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.onPrimary,
        )
      } else {
        Text(stringResource(R.string.action_sign_in))
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(
        onClick = onNavigateToForgotPassword,
        enabled = !uiState.isLoading,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .testTag("login_forgot_password_button"),
      ) {
        Text("Forgot Password?")
      }
      TextButton(
        onClick = { onNavigateToVerifyEmail(email) },
        enabled = !uiState.isLoading,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .testTag("login_verify_email_button"),
      ) {
        Text("Verify Token")
      }
    }

    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
      onClick = onNavigateToRegister,
      enabled = !uiState.isLoading,
      modifier = Modifier
        .fillMaxWidth()
        .minimumInteractiveComponentSize()
        .testTag("login_go_register_button"),
    ) {
      Text(stringResource(R.string.auth_create_account))
    }
  }
}

@Composable
fun RegisterScreen(
  uiState: AuthUiState,
  onRegisterClick: (email: String, password: String, fullName: String, phone: String) -> Unit,
  onBackClick: () -> Unit,
) {
  var fullName by rememberSaveable { mutableStateOf("") }
  var phone by rememberSaveable { mutableStateOf("") }
  var email by rememberSaveable { mutableStateOf("") }
  var password by rememberSaveable { mutableStateOf("") }
  var passwordVisible by rememberSaveable { mutableStateOf(false) }
  var attemptedSubmit by rememberSaveable { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current

  val nameError = if (attemptedSubmit && fullName.isBlank()) stringResource(R.string.auth_error_blank_name) else null
  val phoneError = if (attemptedSubmit && !isValidPhoneInput(phone)) stringResource(R.string.auth_error_short_phone) else null
  val emailError = if (attemptedSubmit && !isValidEmailInput(email)) stringResource(R.string.auth_error_invalid_email) else null
  val passwordError = if (attemptedSubmit && password.length < 8) stringResource(R.string.auth_error_short_password) else null

  val submitRegister = {
    attemptedSubmit = true
    if (fullName.isNotBlank() && isValidPhoneInput(phone) && isValidEmailInput(email) && password.length >= 8) {
      focusManager.clearFocus()
      onRegisterClick(email, password, fullName, phone)
    }
  }

  AuthScaffoldLayout {
    AuthBackHeader(
      title = stringResource(R.string.auth_create_account),
      onBackClick = onBackClick,
      enabled = !uiState.isLoading,
    )
    Spacer(modifier = Modifier.height(12.dp))
    AuthStatusBanner(uiState = uiState)

    OutlinedTextField(
      value = fullName,
      onValueChange = { fullName = it },
      label = { Text(stringResource(R.string.label_full_name)) },
      leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
      singleLine = true,
      isError = nameError != null,
      supportingText = nameError?.let { err -> { Text(err) } },
      keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Next,
      ),
      keyboardActions = KeyboardActions(
        onNext = { focusManager.moveFocus(FocusDirection.Down) },
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("register_name_input"),
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
      value = phone,
      onValueChange = { phone = it },
      label = { Text(stringResource(R.string.label_phone)) },
      leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
      keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Phone,
        imeAction = ImeAction.Next,
      ),
      keyboardActions = KeyboardActions(
        onNext = { focusManager.moveFocus(FocusDirection.Down) },
      ),
      singleLine = true,
      isError = phoneError != null,
      supportingText = phoneError?.let { err -> { Text(err) } },
      modifier = Modifier
        .fillMaxWidth()
        .testTag("register_phone_input"),
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
      value = email,
      onValueChange = { email = it },
      label = { Text(stringResource(R.string.label_email)) },
      leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
      keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Next,
      ),
      keyboardActions = KeyboardActions(
        onNext = { focusManager.moveFocus(FocusDirection.Down) },
      ),
      singleLine = true,
      isError = emailError != null,
      supportingText = emailError?.let { err -> { Text(err) } },
      modifier = Modifier
        .fillMaxWidth()
        .testTag("register_email_input"),
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
      value = password,
      onValueChange = { password = it },
      label = { Text(stringResource(R.string.label_password)) },
      leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
      trailingIcon = {
        PasswordVisibilityToggle(
          visible = passwordVisible,
          onToggle = { passwordVisible = !passwordVisible },
        )
      },
      visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
      singleLine = true,
      isError = passwordError != null,
      supportingText = passwordError?.let { err -> { Text(err) } },
      keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
      ),
      keyboardActions = KeyboardActions(
        onDone = { if (!uiState.isLoading) submitRegister() },
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("register_password_input"),
    )
    Spacer(modifier = Modifier.height(18.dp))

    Button(
      onClick = submitRegister,
      enabled = !uiState.isLoading,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 52.dp)
        .minimumInteractiveComponentSize()
        .testTag("register_submit_button"),
    ) {
      if (uiState.isLoading) {
        CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.onPrimary,
        )
      } else {
        Text(stringResource(R.string.action_register))
      }
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
  var email by rememberSaveable { mutableStateOf(initialEmail) }
  var token by rememberSaveable { mutableStateOf("") }
  var attemptedVerify by rememberSaveable { mutableStateOf(false) }
  var attemptedResend by rememberSaveable { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current

  val emailError = if (attemptedResend && !isValidEmailInput(email)) stringResource(R.string.auth_error_invalid_email) else null
  val tokenError = if (attemptedVerify && token.isBlank()) stringResource(R.string.auth_error_blank_token) else null

  val submitVerify = {
    attemptedVerify = true
    if (token.isNotBlank()) {
      focusManager.clearFocus()
      onVerifyClick(token)
    }
  }

  AuthScaffoldLayout {
    AuthBackHeader(
      title = stringResource(R.string.auth_verify_email_title),
      onBackClick = onBackClick,
      enabled = !uiState.isLoading,
    )
    Spacer(modifier = Modifier.height(12.dp))
    AuthStatusBanner(uiState = uiState)

    OutlinedTextField(
      value = email,
      onValueChange = { email = it },
      label = { Text(stringResource(R.string.label_email)) },
      leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
      singleLine = true,
      isError = emailError != null,
      supportingText = emailError?.let { err -> { Text(err) } },
      keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Next,
      ),
      keyboardActions = KeyboardActions(
        onNext = { focusManager.moveFocus(FocusDirection.Down) },
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("verify_email_input"),
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
      value = token,
      onValueChange = { token = it },
      label = { Text(stringResource(R.string.label_token)) },
      leadingIcon = { Icon(Icons.Filled.VpnKey, contentDescription = null) },
      trailingIcon = if (token.isNotEmpty()) {
        {
          IconButton(
            onClick = { token = "" },
            modifier = Modifier.minimumInteractiveComponentSize(),
          ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.auth_clear_field))
          }
        }
      } else {
        null
      },
      singleLine = true,
      isError = tokenError != null,
      supportingText = {
        Text(tokenError ?: stringResource(R.string.auth_token_helper))
      },
      keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Done,
      ),
      keyboardActions = KeyboardActions(
        onDone = { if (!uiState.isLoading) submitVerify() },
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("verify_token_input"),
    )
    Spacer(modifier = Modifier.height(16.dp))

    Button(
      onClick = submitVerify,
      enabled = !uiState.isLoading,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 52.dp)
        .minimumInteractiveComponentSize()
        .testTag("verify_submit_button"),
    ) {
      if (uiState.isLoading) {
        CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.onPrimary,
        )
      } else {
        Text(stringResource(R.string.action_verify))
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
    OutlinedButton(
      onClick = {
        attemptedResend = true
        if (isValidEmailInput(email)) {
          focusManager.clearFocus()
          onResendClick(email)
        }
      },
      enabled = !uiState.isLoading,
      modifier = Modifier
        .fillMaxWidth()
        .minimumInteractiveComponentSize()
        .testTag("verify_resend_button"),
    ) {
      Text(stringResource(R.string.action_resend_code))
    }
  }
}

@Composable
fun ForgotPasswordScreen(
  uiState: AuthUiState,
  onSendResetClick: (String) -> Unit,
  onResetPasswordClick: (token: String, newPassword: String) -> Unit,
  onBackClick: () -> Unit,
) {
  var email by rememberSaveable { mutableStateOf("") }
  var resetToken by rememberSaveable { mutableStateOf("") }
  var newPassword by rememberSaveable { mutableStateOf("") }
  var passwordVisible by rememberSaveable { mutableStateOf(false) }
  var attemptedSend by rememberSaveable { mutableStateOf(false) }
  var attemptedReset by rememberSaveable { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current

  val emailError = if (attemptedSend && !isValidEmailInput(email)) stringResource(R.string.auth_error_invalid_email) else null
  val tokenError = if (attemptedReset && resetToken.isBlank()) stringResource(R.string.auth_error_blank_token) else null
  val passwordError = if (attemptedReset && newPassword.length < 8) stringResource(R.string.auth_error_short_password) else null

  val submitReset = {
    attemptedReset = true
    if (resetToken.isNotBlank() && newPassword.length >= 8) {
      focusManager.clearFocus()
      onResetPasswordClick(resetToken, newPassword)
    }
  }

  AuthScaffoldLayout {
    AuthBackHeader(
      title = stringResource(R.string.auth_forgot_password_title),
      onBackClick = onBackClick,
      enabled = !uiState.isLoading,
    )
    Spacer(modifier = Modifier.height(12.dp))
    AuthStatusBanner(uiState = uiState)

    OutlinedTextField(
      value = email,
      onValueChange = { email = it },
      label = { Text(stringResource(R.string.label_email)) },
      leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
      singleLine = true,
      isError = emailError != null,
      supportingText = emailError?.let { err -> { Text(err) } },
      keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Done,
      ),
      keyboardActions = KeyboardActions(
        onDone = {
          attemptedSend = true
          if (!uiState.isLoading && isValidEmailInput(email)) {
            focusManager.clearFocus()
            onSendResetClick(email)
          }
        },
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("forgot_email_input"),
    )
    Spacer(modifier = Modifier.height(10.dp))

    OutlinedButton(
      onClick = {
        attemptedSend = true
        if (isValidEmailInput(email)) {
          focusManager.clearFocus()
          onSendResetClick(email)
        }
      },
      enabled = !uiState.isLoading,
      modifier = Modifier
        .fillMaxWidth()
        .minimumInteractiveComponentSize()
        .testTag("forgot_send_button"),
    ) {
      Text(stringResource(R.string.action_send_reset))
    }

    Spacer(modifier = Modifier.height(20.dp))
    OutlinedTextField(
      value = resetToken,
      onValueChange = { resetToken = it },
      label = { Text(stringResource(R.string.label_token)) },
      leadingIcon = { Icon(Icons.Filled.VpnKey, contentDescription = null) },
      trailingIcon = if (resetToken.isNotEmpty()) {
        {
          IconButton(
            onClick = { resetToken = "" },
            modifier = Modifier.minimumInteractiveComponentSize(),
          ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.auth_clear_field))
          }
        }
      } else {
        null
      },
      singleLine = true,
      isError = tokenError != null,
      supportingText = {
        Text(tokenError ?: stringResource(R.string.auth_token_helper))
      },
      keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Next,
      ),
      keyboardActions = KeyboardActions(
        onNext = { focusManager.moveFocus(FocusDirection.Down) },
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("reset_token_input"),
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
      value = newPassword,
      onValueChange = { newPassword = it },
      label = { Text(stringResource(R.string.label_new_password)) },
      leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
      trailingIcon = {
        PasswordVisibilityToggle(
          visible = passwordVisible,
          onToggle = { passwordVisible = !passwordVisible },
        )
      },
      visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
      singleLine = true,
      isError = passwordError != null,
      supportingText = passwordError?.let { err -> { Text(err) } },
      keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
      ),
      keyboardActions = KeyboardActions(
        onDone = { if (!uiState.isLoading) submitReset() },
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("reset_password_input"),
    )
    Spacer(modifier = Modifier.height(16.dp))

    Button(
      onClick = submitReset,
      enabled = !uiState.isLoading,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 52.dp)
        .minimumInteractiveComponentSize()
        .testTag("reset_submit_button"),
    ) {
      if (uiState.isLoading) {
        CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.onPrimary,
        )
      } else {
        Text(stringResource(R.string.action_reset_password))
      }
    }
  }
}

@Composable
private fun PasswordVisibilityToggle(
  visible: Boolean,
  onToggle: () -> Unit,
) {
  IconButton(
    onClick = onToggle,
    modifier = Modifier.minimumInteractiveComponentSize(),
  ) {
    Icon(
      imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
      contentDescription = stringResource(
        if (visible) R.string.auth_hide_password else R.string.auth_show_password,
      ),
    )
  }
}

@Composable
private fun AuthScaffoldLayout(
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .imePadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 16.dp),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .widthIn(max = 440.dp),
      ) {
        content()
      }
    }
  }
}

@Composable
private fun AuthHeroBanner() {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(180.dp)
      .clip(MaterialTheme.shapes.large),
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
            colors = listOf(Color(0x33121110), Color(0xCC121110)),
          ),
        )
        .padding(16.dp),
      contentAlignment = Alignment.BottomStart,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.LocalPizza,
          contentDescription = null,
          tint = BrightAmber,
        )
        Column {
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            color = WarmParchmentText,
          )
          Text(
            text = stringResource(R.string.brand_tagline),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFE5D8CA),
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
  enabled: Boolean = true,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth(),
  ) {
    IconButton(
      onClick = onBackClick,
      enabled = enabled,
      modifier = Modifier
        .minimumInteractiveComponentSize()
        .testTag("auth_back_button"),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = stringResource(R.string.action_back),
      )
    }
    Text(
      text = title,
      style = MaterialTheme.typography.headlineSmall,
    )
  }
}

@Composable
private fun AuthStatusBanner(uiState: AuthUiState) {
  AnimatedVisibility(visible = uiState.errorMessage != null || uiState.infoMessage != null) {
    val isError = uiState.errorMessage != null
    val message = uiState.errorMessage ?: uiState.infoMessage.orEmpty()
    PizzoraStatusBanner(
      message = message,
      tone = if (isError) PizzoraBannerTone.ERROR else PizzoraBannerTone.SUCCESS,
      modifier = Modifier
        .padding(bottom = 14.dp)
        .testTag("auth_status_banner"),
    )
  }
}

package com.example.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.network.PizzoraApiException
import com.example.core.security.AuthSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
  val isLoading: Boolean = false,
  val errorMessage: String? = null,
  val infoMessage: String? = null,
  val pendingVerificationEmail: String? = null,
)

sealed interface AuthNavigationEvent {
  data class NavigateToVerifyEmail(val email: String) : AuthNavigationEvent
  data object NavigateBackToLogin : AuthNavigationEvent
}

class AuthViewModel(
  private val repository: AuthRepository,
) : ViewModel() {
  val sessionFlow: StateFlow<AuthSession?> = repository.sessionFlow
  val kdsSoundMuted: StateFlow<Boolean> = repository.observeKdsSoundMuted()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

  private val _uiState = MutableStateFlow(AuthUiState())
  val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

  private val _events = MutableSharedFlow<AuthNavigationEvent>(extraBufferCapacity = 4)
  val events: SharedFlow<AuthNavigationEvent> = _events.asSharedFlow()

  fun clearMessages() {
    _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
  }

  fun login(email: String, password: String) {
    if (email.isBlank() || password.isBlank()) {
      _uiState.update { it.copy(errorMessage = "Please enter both email and password.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
      try {
        repository.login(email, password)
        _uiState.update { it.copy(isLoading = false) }
      } catch (apiErr: PizzoraApiException) {
        if (apiErr.code == "EMAIL_NOT_VERIFIED") {
          _uiState.update {
            it.copy(
              isLoading = false,
              pendingVerificationEmail = email.trim(),
              infoMessage = apiErr.message,
            )
          }
          _events.tryEmit(AuthNavigationEvent.NavigateToVerifyEmail(email.trim()))
        } else {
          _uiState.update { it.copy(isLoading = false, errorMessage = apiErr.message) }
        }
      } catch (t: Throwable) {
        _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "Sign in failed.") }
      }
    }
  }

  fun register(email: String, password: String, fullName: String, phone: String) {
    if (fullName.isBlank() || email.isBlank() || password.length < 8 || phone.isBlank()) {
      _uiState.update { it.copy(errorMessage = "Fill all fields. Password must be at least 8 characters.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
      try {
        val message = repository.register(email, password, fullName, phone)
        _uiState.update {
          it.copy(
            isLoading = false,
            infoMessage = message,
            pendingVerificationEmail = email.trim(),
          )
        }
        _events.tryEmit(AuthNavigationEvent.NavigateToVerifyEmail(email.trim()))
      } catch (t: Throwable) {
        _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "Registration failed.") }
      }
    }
  }

  fun verifyEmail(token: String) {
    if (token.isBlank()) {
      _uiState.update { it.copy(errorMessage = "Enter the verification token sent to your email.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
      try {
        val message = repository.verifyEmail(token)
        _uiState.update { it.copy(isLoading = false, infoMessage = message, pendingVerificationEmail = null) }
        _events.tryEmit(AuthNavigationEvent.NavigateBackToLogin)
      } catch (t: Throwable) {
        _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "Email verification failed.") }
      }
    }
  }

  fun resendVerification(email: String) {
    if (email.isBlank()) {
      _uiState.update { it.copy(errorMessage = "Enter your email address first.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
      try {
        val message = repository.resendVerification(email)
        _uiState.update { it.copy(isLoading = false, infoMessage = message) }
      } catch (t: Throwable) {
        _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "Could not resend email.") }
      }
    }
  }

  fun forgotPassword(email: String) {
    if (email.isBlank()) {
      _uiState.update { it.copy(errorMessage = "Please enter your email address.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
      try {
        val message = repository.forgotPassword(email)
        _uiState.update { it.copy(isLoading = false, infoMessage = message) }
      } catch (t: Throwable) {
        _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "Request failed.") }
      }
    }
  }

  fun resetPassword(token: String, newPassword: String) {
    if (token.isBlank() || newPassword.length < 8) {
      _uiState.update { it.copy(errorMessage = "Provide a valid reset token and an 8+ character password.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
      try {
        val message = repository.resetPassword(token, newPassword)
        _uiState.update { it.copy(isLoading = false, infoMessage = message) }
        _events.tryEmit(AuthNavigationEvent.NavigateBackToLogin)
      } catch (t: Throwable) {
        _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "Password reset failed.") }
      }
    }
  }

  fun setKdsSoundMuted(muted: Boolean) {
    viewModelScope.launch { repository.setKdsSoundMuted(muted) }
  }

  fun logout() {
    viewModelScope.launch {
      repository.logout()
      _uiState.update { AuthUiState(infoMessage = "Signed out.") }
    }
  }
}

package com.example.auth

import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.pizzora.contracts.ForgotPasswordRequest
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.ResendVerificationRequest
import com.pizzora.contracts.ResetPasswordRequest
import com.pizzora.contracts.VerifyEmailRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
  val sessionFlow: StateFlow<AuthSession?>
  fun observeKdsSoundMuted(): Flow<Boolean>
  suspend fun setKdsSoundMuted(muted: Boolean)
  suspend fun login(email: String, password: String): AuthSession
  suspend fun register(email: String, password: String, fullName: String, phone: String): String
  suspend fun verifyEmail(token: String): String
  suspend fun resendVerification(email: String): String
  suspend fun forgotPassword(email: String): String
  suspend fun resetPassword(token: String, newPassword: String): String
  suspend fun logout()
}

class NetworkAuthRepository(
  private val apiClient: PizzoraApiClient,
  private val tokenStorage: TokenStorage,
) : AuthRepository {
  override val sessionFlow: StateFlow<AuthSession?> = tokenStorage.sessionFlow

  override fun observeKdsSoundMuted(): Flow<Boolean> = tokenStorage.observeKdsSoundMuted()

  override suspend fun setKdsSoundMuted(muted: Boolean) = tokenStorage.setKdsSoundMuted(muted)

  override suspend fun login(email: String, password: String): AuthSession {
    val response = apiClient.login(LoginRequest(email = email.trim(), password = password))
    val session = AuthSession(
      accessToken = response.accessToken,
      refreshToken = response.refreshToken,
      user = response.user,
    )
    tokenStorage.saveSession(session)
    return session
  }

  override suspend fun register(email: String, password: String, fullName: String, phone: String): String {
    return apiClient.register(
      RegisterRequest(
        email = email.trim(),
        password = password,
        fullName = fullName.trim(),
        phone = phone.trim(),
      ),
    ).message
  }

  override suspend fun verifyEmail(token: String): String {
    return apiClient.verifyEmail(VerifyEmailRequest(token = token.trim())).message
  }

  override suspend fun resendVerification(email: String): String {
    return apiClient.resendVerification(ResendVerificationRequest(email = email.trim())).message
  }

  override suspend fun forgotPassword(email: String): String {
    return apiClient.forgotPassword(ForgotPasswordRequest(email = email.trim())).message
  }

  override suspend fun resetPassword(token: String, newPassword: String): String {
    return apiClient.resetPassword(ResetPasswordRequest(token = token.trim(), newPassword = newPassword)).message
  }

  override suspend fun logout() {
    runCatching { apiClient.logout() }
    tokenStorage.clearSession()
  }
}

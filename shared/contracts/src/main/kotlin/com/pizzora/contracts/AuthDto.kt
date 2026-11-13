package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
  val email: String,
  val password: String,
  @SerialName("full_name") val fullName: String,
  val phone: String,
)

@Serializable
data class VerifyEmailRequest(
  val token: String,
)

@Serializable
data class ResendVerificationRequest(
  val email: String,
)

@Serializable
data class LoginRequest(
  val email: String,
  val password: String,
  @SerialName("device_name") val deviceName: String = "Android",
)

@Serializable
data class RefreshTokenRequest(
  @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class LogoutRequest(
  @SerialName("refresh_token") val refreshToken: String? = null,
)

@Serializable
data class ForgotPasswordRequest(
  val email: String,
)

@Serializable
data class ResetPasswordRequest(
  val token: String,
  @SerialName("new_password") val newPassword: String,
)

@Serializable
data class UserProfileDto(
  val id: String,
  val email: String,
  @SerialName("email_verified") val emailVerified: Boolean,
  @SerialName("full_name") val fullName: String,
  val phone: String,
  val role: UserRole,
  @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class UpdateProfileRequest(
  @SerialName("full_name") val fullName: String,
  val phone: String,
)

@Serializable
data class AuthTokensResponse(
  @SerialName("access_token") val accessToken: String,
  @SerialName("refresh_token") val refreshToken: String,
  @SerialName("expires_in_seconds") val expiresInSeconds: Long,
  val user: UserProfileDto,
)

@Serializable
data class WsTicketResponse(
  val ticket: String,
  @SerialName("expires_in_seconds") val expiresInSeconds: Long,
)

@Serializable
data class MessageResponse(
  val message: String,
)

@Serializable
data class AddressDto(
  val id: String,
  val label: String,
  val line1: String,
  val line2: String? = null,
  val landmark: String? = null,
  val pincode: String,
  val city: String,
  @SerialName("is_default") val isDefault: Boolean,
)

@Serializable
data class UpsertAddressRequest(
  val label: String,
  val line1: String,
  val line2: String? = null,
  val landmark: String? = null,
  val pincode: String,
  val city: String,
  @SerialName("is_default") val isDefault: Boolean = false,
)

@Serializable
data class StaffMemberDto(
  @SerialName("user_id") val userId: String,
  val email: String,
  @SerialName("full_name") val fullName: String,
  val phone: String,
  val role: UserRole,
  @SerialName("employee_code") val employeeCode: String,
  @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class CreateStaffRequest(
  val email: String,
  val password: String,
  @SerialName("full_name") val fullName: String,
  val phone: String,
  val role: UserRole,
  @SerialName("employee_code") val employeeCode: String,
)

@Serializable
data class UpdateStaffRequest(
  val role: UserRole? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
  @SerialName("full_name") val fullName: String? = null,
  val phone: String? = null,
)

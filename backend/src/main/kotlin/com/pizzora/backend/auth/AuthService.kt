package com.pizzora.backend.auth

import com.pizzora.backend.core.config.AuthConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.email.EmailDeliveryProvider
import com.pizzora.backend.core.error.ConflictException
import com.pizzora.backend.core.error.ForbiddenException
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.core.security.CryptoTokens
import com.pizzora.backend.core.security.JwtTokenProvider
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.ForgotPasswordRequest
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.MessageResponse
import com.pizzora.contracts.RefreshTokenRequest
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.ResendVerificationRequest
import com.pizzora.contracts.ResetPasswordRequest
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.VerifyEmailRequest
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import org.slf4j.LoggerFactory

class AuthService(
  private val dataSource: DataSource,
  private val passwordHasher: PasswordHasher,
  private val jwtTokenProvider: JwtTokenProvider,
  private val emailDeliveryProvider: EmailDeliveryProvider,
  private val authConfig: AuthConfig,
) {
  private val logger = LoggerFactory.getLogger(AuthService::class.java)

  suspend fun register(request: RegisterRequest): MessageResponse {
    val email = normalizeEmail(request.email)
    validateEmail(email)
    validatePassword(request.password)
    if (request.fullName.isBlank()) {
      throw ValidationException("INVALID_NAME", "Full name is required.")
    }
    if (request.phone.trim().length < 7) {
      throw ValidationException("INVALID_PHONE", "A valid phone number is required.")
    }

    val passwordHash = passwordHasher.hash(request.password)
    val rawVerifyToken = CryptoTokens.generateOpaqueToken(32)
    val tokenHash = CryptoTokens.sha256Hex(rawVerifyToken)
    val expiresAt = Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS))

    val userId = UUID.randomUUID()
    val created = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val exists = conn.prepareStatement("SELECT 1 FROM users WHERE email = ?").use { ps ->
        ps.setString(1, email)
        ps.executeQuery().use { it.next() }
      }
      if (exists) return@withJdbcTransaction false

      conn.prepareStatement(
        """
        INSERT INTO users (id, email, password_hash, full_name, phone)
        VALUES (?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, userId)
        ps.setString(2, email)
        ps.setString(3, passwordHash)
        ps.setString(4, request.fullName.trim())
        ps.setString(5, request.phone.trim())
        ps.executeUpdate()
      }

      conn.prepareStatement(
        """
        INSERT INTO email_verification_tokens (id, user_id, token_hash, expires_at)
        VALUES (?, ?, ?, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setObject(2, userId)
        ps.setString(3, tokenHash)
        ps.setTimestamp(4, expiresAt)
        ps.executeUpdate()
      }
      true
    }

    if (!created) {
      throw ConflictException("EMAIL_ALREADY_EXISTS", "An account with this email already exists.")
    }

    emailDeliveryProvider.sendVerificationEmail(email, request.fullName.trim(), rawVerifyToken)
    return MessageResponse("Registration successful. Please verify your email.")
  }

  suspend fun verifyEmail(request: VerifyEmailRequest): MessageResponse {
    if (request.token.isBlank()) {
      throw ValidationException("INVALID_TOKEN", "Verification token is required.")
    }
    val tokenHash = CryptoTokens.sha256Hex(request.token.trim())

    val verified = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val userId = conn.prepareStatement(
        """
        UPDATE email_verification_tokens
        SET used_at = NOW()
        WHERE token_hash = ? AND used_at IS NULL AND expires_at > NOW()
        RETURNING user_id
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, tokenHash)
        ps.executeQuery().use { rs ->
          if (!rs.next()) null else rs.getObject("user_id", UUID::class.java)
        }
      } ?: return@withJdbcTransaction false

      conn.prepareStatement(
        """
        UPDATE users
        SET email_verified_at = COALESCE(email_verified_at, NOW()), updated_at = NOW()
        WHERE id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, userId)
        ps.executeUpdate()
      }
      true
    }

    if (!verified) {
      logger.warn("Blocked invalid, expired, or already-used email verification token")
      throw ValidationException("INVALID_OR_EXPIRED_TOKEN", "Verification token is invalid, expired, or already used.")
    }
    return MessageResponse("Email verified successfully.")
  }

  suspend fun resendVerification(request: ResendVerificationRequest): MessageResponse {
    val email = normalizeEmail(request.email)
    val rawToken = CryptoTokens.generateOpaqueToken(32)
    val tokenHash = CryptoTokens.sha256Hex(rawToken)
    val expiresAt = Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS))

    val recipientName = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val row = conn.prepareStatement(
        "SELECT id, full_name, email_verified_at, is_active FROM users WHERE email = ?",
      ).use { ps ->
        ps.setString(1, email)
        ps.executeQuery().use { rs ->
          if (!rs.next()) null
          else Triple(
            rs.getObject("id", UUID::class.java),
            rs.getString("full_name"),
            rs.getTimestamp("email_verified_at") == null && rs.getBoolean("is_active"),
          )
        }
      } ?: return@withJdbcTransaction null

      if (!row.third) return@withJdbcTransaction null

      conn.prepareStatement(
        "INSERT INTO email_verification_tokens (id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setObject(2, row.first)
        ps.setString(3, tokenHash)
        ps.setTimestamp(4, expiresAt)
        ps.executeUpdate()
      }
      row.second
    }

    if (recipientName != null) {
      emailDeliveryProvider.sendVerificationEmail(email, recipientName, rawToken)
    }
    return MessageResponse("If the account exists and is unverified, a verification email has been sent.")
  }

  suspend fun login(request: LoginRequest, ipAddress: String?): AuthTokensResponse {
    val email = normalizeEmail(request.email)

    data class UserAuthRow(
      val id: UUID,
      val email: String,
      val emailVerified: Boolean,
      val passwordHash: String,
      val fullName: String,
      val phone: String,
      val isActive: Boolean,
      val role: UserRole,
    )

    val user = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        SELECT u.id, u.email, u.email_verified_at, u.password_hash, u.full_name, u.phone, u.is_active,
               r.code AS staff_role
        FROM users u
        LEFT JOIN staff s ON s.user_id = u.id AND s.is_active = TRUE
        LEFT JOIN roles r ON r.id = s.role_id
        WHERE u.email = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, email)
        ps.executeQuery().use { rs ->
          if (!rs.next()) null
          else {
            val staffRole = rs.getString("staff_role")
            UserAuthRow(
              id = rs.getObject("id", UUID::class.java),
              email = rs.getString("email"),
              emailVerified = rs.getTimestamp("email_verified_at") != null,
              passwordHash = rs.getString("password_hash"),
              fullName = rs.getString("full_name"),
              phone = rs.getString("phone"),
              isActive = rs.getBoolean("is_active"),
              role = if (staffRole != null) UserRole.valueOf(staffRole) else UserRole.CUSTOMER,
            )
          }
        }
      }
    } ?: run {
      logger.warn("Blocked login for unknown email domain='{}' ip={}", email.substringAfter('@', "unknown"), ipAddress)
      throw UnauthorizedException("INVALID_CREDENTIALS", "Invalid email or password.")
    }

    if (!passwordHasher.verify(request.password, user.passwordHash)) {
      logger.warn("Blocked login with invalid password userId={} ip={}", user.id, ipAddress)
      throw UnauthorizedException("INVALID_CREDENTIALS", "Invalid email or password.")
    }
    if (!user.isActive) {
      logger.warn("Blocked login for deactivated account userId={} ip={}", user.id, ipAddress)
      throw ForbiddenException("ACCOUNT_DISABLED", "This account has been deactivated.")
    }
    if (!user.emailVerified) {
      logger.warn("Blocked login for unverified email userId={} ip={}", user.id, ipAddress)
      throw ForbiddenException("EMAIL_NOT_VERIFIED", "Please verify your email address before signing in.")
    }

    val sessionId = UUID.randomUUID()
    val familyId = UUID.randomUUID()
    val rawRefreshToken = CryptoTokens.generateOpaqueToken(32)
    val refreshHash = CryptoTokens.sha256Hex(rawRefreshToken)
    val now = Instant.now()
    val refreshExpiresAt = Timestamp.from(now.plus(authConfig.refreshTokenTtlDays, ChronoUnit.DAYS))

    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        INSERT INTO user_sessions (id, user_id, device_name, ip_address)
        VALUES (?, ?, ?, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, sessionId)
        ps.setObject(2, user.id)
        ps.setString(3, request.deviceName.take(100))
        ps.setString(4, ipAddress)
        ps.executeUpdate()
      }

      conn.prepareStatement(
        """
        INSERT INTO refresh_tokens (id, session_id, family_id, token_hash, expires_at)
        VALUES (?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setObject(2, sessionId)
        ps.setObject(3, familyId)
        ps.setString(4, refreshHash)
        ps.setTimestamp(5, refreshExpiresAt)
        ps.executeUpdate()
      }
    }

    val accessToken = jwtTokenProvider.createAccessToken(user.id, sessionId, user.role, now)
    return AuthTokensResponse(
      accessToken = accessToken,
      refreshToken = rawRefreshToken,
      expiresInSeconds = authConfig.accessTokenTtlSeconds,
      user = UserProfileDto(
        id = user.id.toString(),
        email = user.email,
        emailVerified = true,
        fullName = user.fullName,
        phone = user.phone,
        role = user.role,
        isActive = user.isActive,
      ),
    )
  }

  private sealed class RefreshOutcome {
    data object NotFound : RefreshOutcome()
    data object Reused : RefreshOutcome()
    data object Expired : RefreshOutcome()
    data class Success(val response: AuthTokensResponse) : RefreshOutcome()
  }

  suspend fun refresh(request: RefreshTokenRequest): AuthTokensResponse {
    if (request.refreshToken.isBlank()) {
      throw UnauthorizedException("INVALID_REFRESH_TOKEN", "Refresh token is required.")
    }
    val incomingHash = CryptoTokens.sha256Hex(request.refreshToken.trim())
    val now = Instant.now()

    val outcome = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      data class TokenRow(
        val id: UUID,
        val sessionId: UUID,
        val familyId: UUID,
        val expiresAt: Instant,
        val rotatedAt: Timestamp?,
        val tokenRevokedAt: Timestamp?,
        val sessionRevokedAt: Timestamp?,
        val userId: UUID,
        val email: String,
        val emailVerified: Boolean,
        val fullName: String,
        val phone: String,
        val isActive: Boolean,
        val role: UserRole,
      )

      val row = conn.prepareStatement(
        """
        SELECT rt.id, rt.session_id, rt.family_id, rt.expires_at, rt.rotated_at, rt.revoked_at AS token_revoked_at,
               us.revoked_at AS session_revoked_at,
               u.id AS user_id, u.email, u.email_verified_at, u.full_name, u.phone, u.is_active,
               r.code AS staff_role
        FROM refresh_tokens rt
        JOIN user_sessions us ON us.id = rt.session_id
        JOIN users u ON u.id = us.user_id
        LEFT JOIN staff st ON st.user_id = u.id AND st.is_active = TRUE
        LEFT JOIN roles r ON r.id = st.role_id
        WHERE rt.token_hash = ?
        FOR UPDATE OF rt, us
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, incomingHash)
        ps.executeQuery().use { rs ->
          if (!rs.next()) null
          else {
            val staffRole = rs.getString("staff_role")
            TokenRow(
              id = rs.getObject("id", UUID::class.java),
              sessionId = rs.getObject("session_id", UUID::class.java),
              familyId = rs.getObject("family_id", UUID::class.java),
              expiresAt = rs.getTimestamp("expires_at").toInstant(),
              rotatedAt = rs.getTimestamp("rotated_at"),
              tokenRevokedAt = rs.getTimestamp("token_revoked_at"),
              sessionRevokedAt = rs.getTimestamp("session_revoked_at"),
              userId = rs.getObject("user_id", UUID::class.java),
              email = rs.getString("email"),
              emailVerified = rs.getTimestamp("email_verified_at") != null,
              fullName = rs.getString("full_name"),
              phone = rs.getString("phone"),
              isActive = rs.getBoolean("is_active"),
              role = if (staffRole != null) UserRole.valueOf(staffRole) else UserRole.CUSTOMER,
            )
          }
        }
      } ?: return@withJdbcTransaction RefreshOutcome.NotFound

      if (row.rotatedAt != null || row.tokenRevokedAt != null) {
        logger.warn(
          "Refresh token reuse detected! Revoking token family={} sessionId={} userId={}",
          row.familyId,
          row.sessionId,
          row.userId,
        )
        conn.prepareStatement(
          "UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, NOW()) WHERE family_id = ?",
        ).use { ps ->
          ps.setObject(1, row.familyId)
          ps.executeUpdate()
        }
        conn.prepareStatement(
          "UPDATE user_sessions SET revoked_at = COALESCE(revoked_at, NOW()) WHERE id = ?",
        ).use { ps ->
          ps.setObject(1, row.sessionId)
          ps.executeUpdate()
        }
        return@withJdbcTransaction RefreshOutcome.Reused
      }

      if (row.sessionRevokedAt != null || !row.isActive || !row.emailVerified) {
        return@withJdbcTransaction RefreshOutcome.NotFound
      }

      if (!row.expiresAt.isAfter(now)) {
        return@withJdbcTransaction RefreshOutcome.Expired
      }

      conn.prepareStatement("UPDATE refresh_tokens SET rotated_at = NOW() WHERE id = ?").use { ps ->
        ps.setObject(1, row.id)
        ps.executeUpdate()
      }

      val nextRawRefresh = CryptoTokens.generateOpaqueToken(32)
      val nextRefreshHash = CryptoTokens.sha256Hex(nextRawRefresh)
      val nextExpiresAt = Timestamp.from(now.plus(authConfig.refreshTokenTtlDays, ChronoUnit.DAYS))

      conn.prepareStatement(
        """
        INSERT INTO refresh_tokens (id, session_id, family_id, token_hash, expires_at)
        VALUES (?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setObject(2, row.sessionId)
        ps.setObject(3, row.familyId)
        ps.setString(4, nextRefreshHash)
        ps.setTimestamp(5, nextExpiresAt)
        ps.executeUpdate()
      }

      conn.prepareStatement("UPDATE user_sessions SET last_seen_at = NOW() WHERE id = ?").use { ps ->
        ps.setObject(1, row.sessionId)
        ps.executeUpdate()
      }

      val accessToken = jwtTokenProvider.createAccessToken(row.userId, row.sessionId, row.role, now)
      RefreshOutcome.Success(
        AuthTokensResponse(
          accessToken = accessToken,
          refreshToken = nextRawRefresh,
          expiresInSeconds = authConfig.accessTokenTtlSeconds,
          user = UserProfileDto(
            id = row.userId.toString(),
            email = row.email,
            emailVerified = row.emailVerified,
            fullName = row.fullName,
            phone = row.phone,
            role = row.role,
            isActive = row.isActive,
          ),
        ),
      )
    }

    return when (outcome) {
      RefreshOutcome.NotFound -> throw UnauthorizedException("INVALID_REFRESH_TOKEN", "Invalid or revoked refresh token.")
      RefreshOutcome.Reused -> throw UnauthorizedException("REFRESH_TOKEN_REUSED", "Refresh token reuse detected. Session revoked.")
      RefreshOutcome.Expired -> throw UnauthorizedException("REFRESH_TOKEN_EXPIRED", "Refresh token has expired.")
      is RefreshOutcome.Success -> outcome.response
    }
  }

  suspend fun logout(sessionId: UUID): MessageResponse {
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        "UPDATE user_sessions SET revoked_at = COALESCE(revoked_at, NOW()) WHERE id = ?",
      ).use { ps ->
        ps.setObject(1, sessionId)
        ps.executeUpdate()
      }
      conn.prepareStatement(
        "UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, NOW()) WHERE session_id = ?",
      ).use { ps ->
        ps.setObject(1, sessionId)
        ps.executeUpdate()
      }
    }
    return MessageResponse("Signed out successfully.")
  }

  suspend fun forgotPassword(request: ForgotPasswordRequest): MessageResponse {
    val email = normalizeEmail(request.email)
    val rawResetToken = CryptoTokens.generateOpaqueToken(32)
    val tokenHash = CryptoTokens.sha256Hex(rawResetToken)
    val expiresAt = Timestamp.from(Instant.now().plus(15, ChronoUnit.MINUTES))

    val recipientName = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val user = conn.prepareStatement(
        "SELECT id, full_name FROM users WHERE email = ? AND is_active = TRUE",
      ).use { ps ->
        ps.setString(1, email)
        ps.executeQuery().use { rs ->
          if (!rs.next()) null
          else rs.getObject("id", UUID::class.java) to rs.getString("full_name")
        }
      } ?: return@withJdbcTransaction null

      conn.prepareStatement(
        "INSERT INTO password_reset_tokens (id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setObject(2, user.first)
        ps.setString(3, tokenHash)
        ps.setTimestamp(4, expiresAt)
        ps.executeUpdate()
      }
      user.second
    }

    if (recipientName != null) {
      emailDeliveryProvider.sendPasswordResetEmail(email, recipientName, rawResetToken)
    }
    return MessageResponse("If an account matches that email, a password reset link has been sent.")
  }

  suspend fun resetPassword(request: ResetPasswordRequest): MessageResponse {
    if (request.token.isBlank()) {
      throw ValidationException("INVALID_TOKEN", "Password reset token is required.")
    }
    validatePassword(request.newPassword)
    val tokenHash = CryptoTokens.sha256Hex(request.token.trim())
    val nextHash = passwordHasher.hash(request.newPassword)

    val resetOk = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val userId = conn.prepareStatement(
        """
        UPDATE password_reset_tokens
        SET used_at = NOW()
        WHERE token_hash = ? AND used_at IS NULL AND expires_at > NOW()
        RETURNING user_id
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, tokenHash)
        ps.executeQuery().use { rs ->
          if (!rs.next()) null else rs.getObject("user_id", UUID::class.java)
        }
      } ?: return@withJdbcTransaction false

      conn.prepareStatement(
        "UPDATE users SET password_hash = ?, updated_at = NOW() WHERE id = ?",
      ).use { ps ->
        ps.setString(1, nextHash)
        ps.setObject(2, userId)
        ps.executeUpdate()
      }

      conn.prepareStatement(
        "UPDATE user_sessions SET revoked_at = COALESCE(revoked_at, NOW()) WHERE user_id = ?",
      ).use { ps ->
        ps.setObject(1, userId)
        ps.executeUpdate()
      }

      conn.prepareStatement(
        """
        UPDATE refresh_tokens rt
        SET revoked_at = COALESCE(rt.revoked_at, NOW())
        FROM user_sessions us
        WHERE rt.session_id = us.id AND us.user_id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, userId)
        ps.executeUpdate()
      }

      true
    }

    if (!resetOk) {
      logger.warn("Blocked invalid, expired, or replayed password reset token")
      throw ValidationException("INVALID_OR_EXPIRED_TOKEN", "Password reset token is invalid, expired, or already used.")
    }
    return MessageResponse("Password reset successful. Please sign in with your new password.")
  }

  private fun normalizeEmail(raw: String): String = raw.trim().lowercase()

  private fun validateEmail(email: String) {
    if (!email.contains('@') || email.length < 5 || !email.substringAfter('@').contains('.')) {
      throw ValidationException("INVALID_EMAIL", "Please provide a valid email address.")
    }
  }

  private fun validatePassword(password: String) {
    if (password.length < 8) {
      throw ValidationException("WEAK_PASSWORD", "Password must be at least 8 characters long.")
    }
  }
}

package com.pizzora.backend.core.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.pizzora.backend.core.config.AuthConfig
import com.pizzora.contracts.UserRole
import java.time.Instant
import java.util.Date
import java.util.UUID

data class AccessTokenClaims(
  val userId: UUID,
  val sessionId: UUID,
  val role: UserRole,
  val jwtId: String,
)

class JwtTokenProvider(
  private val config: AuthConfig,
) {
  private val algorithm = Algorithm.HMAC256(config.jwtSecret)
  private val verifier = JWT.require(algorithm)
    .withIssuer(config.jwtIssuer)
    .build()

  fun createAccessToken(
    userId: UUID,
    sessionId: UUID,
    role: UserRole,
    now: Instant = Instant.now(),
  ): String {
    val expiresAt = now.plusSeconds(config.accessTokenTtlSeconds)
    return JWT.create()
      .withIssuer(config.jwtIssuer)
      .withSubject(userId.toString())
      .withJWTId(UUID.randomUUID().toString())
      .withClaim("sid", sessionId.toString())
      .withClaim("role", role.name)
      .withIssuedAt(Date.from(now))
      .withExpiresAt(Date.from(expiresAt))
      .sign(algorithm)
  }

  fun verifyAccessToken(token: String): AccessTokenClaims? = runCatching {
    val decoded = verifier.verify(token)
    val userId = UUID.fromString(decoded.subject)
    val sessionId = UUID.fromString(decoded.getClaim("sid").asString())
    val role = UserRole.valueOf(decoded.getClaim("role").asString())
    val jti = decoded.id ?: return@runCatching null
    AccessTokenClaims(
      userId = userId,
      sessionId = sessionId,
      role = role,
      jwtId = jti,
    )
  }.getOrNull()
}

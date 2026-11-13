package com.pizzora.backend.auth

import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.core.config.AuthConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.core.security.CryptoTokens
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.WsTicketResponse
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.slf4j.LoggerFactory

class WsTicketService(
  private val dataSource: DataSource,
  private val cacheStore: EphemeralCacheStore,
  private val authConfig: AuthConfig,
) {
  private val logger = LoggerFactory.getLogger(WsTicketService::class.java)

  suspend fun mintTicket(principal: PizzoraPrincipal): WsTicketResponse {
    val rawTicket = CryptoTokens.generateOpaqueToken(32)
    val ticketHash = CryptoTokens.sha256Hex(rawTicket)
    val ttl = authConfig.wsTicketTtlSeconds
    val payload = "${principal.userId}|${principal.sessionId}|${principal.role.name}"

    val storedInRedis = cacheStore.setEx("ws_ticket:$ticketHash", ttl, payload)
    if (!storedInRedis) {
      logger.warn("Redis unavailable for ws-ticket; falling back to PostgreSQL ws_tickets table")
      val expiresAt = Timestamp.from(Instant.now().plusSeconds(ttl))
      DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
        conn.prepareStatement(
          """
          INSERT INTO ws_tickets (ticket_hash, user_id, role_code, session_id, expires_at)
          VALUES (?, ?, ?, ?, ?)
          """.trimIndent(),
        ).use { ps ->
          ps.setString(1, ticketHash)
          ps.setObject(2, principal.userId)
          ps.setString(3, principal.role.name)
          ps.setObject(4, principal.sessionId)
          ps.setTimestamp(5, expiresAt)
          ps.executeUpdate()
        }
      }
    }

    return WsTicketResponse(
      ticket = rawTicket,
      expiresInSeconds = ttl,
    )
  }

  suspend fun consumeTicket(rawTicket: String): PizzoraPrincipal? {
    if (rawTicket.isBlank()) return null
    val ticketHash = CryptoTokens.sha256Hex(rawTicket)

    val redisPayload = cacheStore.getDel("ws_ticket:$ticketHash")
    if (redisPayload != null) {
      val parts = redisPayload.split('|')
      if (parts.size != 3) return null
      val userId = runCatching { UUID.fromString(parts[0]) }.getOrNull() ?: return null
      val sessionId = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return null
      val role = runCatching { UserRole.valueOf(parts[2]) }.getOrNull() ?: return null
      val active = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
        conn.prepareStatement(
          "SELECT 1 FROM user_sessions WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
        ).use { ps ->
          ps.setObject(1, sessionId)
          ps.setObject(2, userId)
          ps.executeQuery().use { it.next() }
        }
      }
      return if (active) PizzoraPrincipal(userId, sessionId, role) else null
    }

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        UPDATE ws_tickets t
        SET consumed_at = NOW()
        FROM user_sessions s, users u
        WHERE t.ticket_hash = ?
          AND t.consumed_at IS NULL
          AND t.expires_at > NOW()
          AND s.id = t.session_id
          AND s.user_id = t.user_id
          AND s.revoked_at IS NULL
          AND u.id = t.user_id
          AND u.is_active = TRUE
        RETURNING t.user_id, t.session_id, t.role_code
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, ticketHash)
        ps.executeQuery().use { rs ->
          if (!rs.next()) return@use null
          PizzoraPrincipal(
            userId = rs.getObject("user_id", UUID::class.java),
            sessionId = rs.getObject("session_id", UUID::class.java),
            role = UserRole.valueOf(rs.getString("role_code")),
          )
        }
      }
    }
  }
}

package com.pizzora.backend.audit

import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.AuditLogEntryDto
import com.pizzora.contracts.AuditLogsResponse
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

class AuditService(
  private val dataSource: DataSource,
) {
  suspend fun listAuditLogs(
    entityType: String? = null,
    action: String? = null,
    entityId: String? = null,
    cursor: String? = null,
    limit: Int = 50,
  ): AuditLogsResponse {
    val clampedLimit = limit.coerceIn(1, 100)
    val cleanEntityType = entityType?.trim()?.takeIf { it.isNotEmpty() }
    val cleanAction = action?.trim()?.takeIf { it.isNotEmpty() }
    val cleanEntityId = entityId?.trim()?.takeIf { it.isNotEmpty() }
    val decodedCursor = cursor?.trim()?.takeIf { it.isNotEmpty() }?.let { decodeCursor(it) }

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val whereClauses = mutableListOf<String>()
      val params = mutableListOf<(java.sql.PreparedStatement, Int) -> Unit>()

      if (cleanEntityType != null) {
        whereClauses += "LOWER(a.entity_type) = LOWER(?)"
        params += { ps, idx -> ps.setString(idx, cleanEntityType) }
      }
      if (cleanAction != null) {
        whereClauses += "LOWER(a.action) = LOWER(?)"
        params += { ps, idx -> ps.setString(idx, cleanAction) }
      }
      if (cleanEntityId != null) {
        whereClauses += "a.entity_id = ?"
        params += { ps, idx -> ps.setString(idx, cleanEntityId) }
      }
      if (decodedCursor != null) {
        whereClauses += "(a.created_at, a.id) < (?, ?)"
        params += { ps, idx -> ps.setTimestamp(idx, Timestamp.from(decodedCursor.createdAt)) }
        params += { ps, idx -> ps.setObject(idx, decodedCursor.id) }
      }

      val whereSql = if (whereClauses.isEmpty()) "" else "WHERE ${whereClauses.joinToString(" AND ")}"
      val sql = """
        SELECT a.id, a.actor_user_id, u.email AS actor_email, u.full_name AS actor_name,
               a.actor_role, a.action, a.entity_type, a.entity_id,
               a.before_json::text AS before_json, a.after_json::text AS after_json,
               a.correlation_id, a.ip_address, a.created_at
        FROM audit_logs a
        LEFT JOIN users u ON u.id = a.actor_user_id
        $whereSql
        ORDER BY a.created_at DESC, a.id DESC
        LIMIT ?
      """.trimIndent()

      val fetched = conn.prepareStatement(sql).use { ps ->
        var idx = 1
        for (binder in params) {
          binder(ps, idx++)
        }
        ps.setInt(idx, clampedLimit + 1)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<Pair<Instant, AuditLogEntryDto>>()
          while (rs.next()) {
            val id = rs.getObject("id", UUID::class.java)
            val createdAtInstant = rs.getObject("created_at", OffsetDateTime::class.java).toInstant()
            list += createdAtInstant to AuditLogEntryDto(
              id = id.toString(),
              actorUserId = rs.getObject("actor_user_id", UUID::class.java)?.toString(),
              actorEmail = rs.getString("actor_email"),
              actorName = rs.getString("actor_name"),
              actorRole = rs.getString("actor_role"),
              action = rs.getString("action"),
              entityType = rs.getString("entity_type"),
              entityId = rs.getString("entity_id"),
              beforeJson = rs.getString("before_json"),
              afterJson = rs.getString("after_json"),
              correlationId = rs.getString("correlation_id"),
              ipAddress = rs.getString("ip_address"),
              createdAt = createdAtInstant.toString(),
            )
          }
          list
        }
      }

      val hasMore = fetched.size > clampedLimit
      val page = if (hasMore) fetched.take(clampedLimit) else fetched
      val nextCursor = if (hasMore && page.isNotEmpty()) {
        val last = page.last()
        encodeCursor(last.first, UUID.fromString(last.second.id))
      } else {
        null
      }

      AuditLogsResponse(
        entries = page.map { it.second },
        nextCursor = nextCursor,
        serverNow = Instant.now().toString(),
      )
    }
  }

  private data class AuditCursor(val createdAt: Instant, val id: UUID)

  private fun encodeCursor(createdAt: Instant, id: UUID): String {
    val raw = "${createdAt}|$id"
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
  }

  private fun decodeCursor(encoded: String): AuditCursor =
    runCatching {
      val decoded = String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
      val parts = decoded.split("|", limit = 2)
      require(parts.size == 2) { "Invalid cursor format" }
      AuditCursor(
        createdAt = Instant.parse(parts[0]),
        id = UUID.fromString(parts[1]),
      )
    }.getOrElse {
      throw ValidationException("INVALID_CURSOR", "Invalid pagination cursor for audit logs.")
    }
}

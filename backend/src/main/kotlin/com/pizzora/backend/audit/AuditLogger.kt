package com.pizzora.backend.audit

import java.sql.Connection
import java.sql.Types
import java.util.UUID

data class AuditContext(
  val actorUserId: UUID?,
  val actorRole: String,
  val correlationId: String,
  val ipAddress: String? = null,
)

object AuditLogger {
  fun record(
    conn: Connection,
    context: AuditContext,
    action: String,
    entityType: String,
    entityId: String,
    beforeJson: String? = null,
    afterJson: String? = null,
  ) {
    conn.prepareStatement(
      """
      INSERT INTO audit_logs (
        id, actor_user_id, actor_role, action, entity_type, entity_id,
        before_json, after_json, correlation_id, ip_address
      ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, UUID.randomUUID())
      if (context.actorUserId != null) {
        ps.setObject(2, context.actorUserId)
      } else {
        ps.setNull(2, Types.OTHER)
      }
      ps.setString(3, context.actorRole)
      ps.setString(4, action)
      ps.setString(5, entityType)
      ps.setString(6, entityId)
      if (beforeJson != null) ps.setString(7, beforeJson) else ps.setNull(7, Types.VARCHAR)
      if (afterJson != null) ps.setString(8, afterJson) else ps.setNull(8, Types.VARCHAR)
      ps.setString(9, context.correlationId)
      if (context.ipAddress != null) ps.setString(10, context.ipAddress) else ps.setNull(10, Types.VARCHAR)
      ps.executeUpdate()
    }
  }
}

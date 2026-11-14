package com.pizzora.backend.audit

import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.requirePermission
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.auditRoutes(
  auditService: AuditService,
  authorizationService: AuthorizationService,
) {
  authenticate("auth-jwt") {
    requirePermission("owner:audit_read", authorizationService) {
      get("/api/v1/owner/audit-logs") {
        val entityType = call.request.queryParameters["entity_type"]
        val action = call.request.queryParameters["action"]
        val entityId = call.request.queryParameters["entity_id"]
        val cursor = call.request.queryParameters["cursor"]
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        call.respond(
          HttpStatusCode.OK,
          auditService.listAuditLogs(
            entityType = entityType,
            action = action,
            entityId = entityId,
            cursor = cursor,
            limit = limit,
          ),
        )
      }
    }
  }
}

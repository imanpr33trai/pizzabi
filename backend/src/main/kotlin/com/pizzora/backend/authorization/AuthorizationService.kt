package com.pizzora.backend.authorization

import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ForbiddenException
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.contracts.UserRole
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import java.util.UUID
import javax.sql.DataSource

import org.slf4j.LoggerFactory

data class PizzoraPrincipal(
  val userId: UUID,
  val sessionId: UUID,
  val role: UserRole,
)

class AuthorizationService(
  private val dataSource: DataSource,
  private val cacheStore: EphemeralCacheStore,
) {
  private val logger = LoggerFactory.getLogger(AuthorizationService::class.java)

  suspend fun getPermissions(role: UserRole): Set<String> {
    val cacheKey = "rbac:perms:${role.name}"
    val cached = runCatching { cacheStore.get(cacheKey) }
      .onFailure { logger.warn("Redis read failed for RBAC permissions role={}; falling back to PostgreSQL", role, it) }
      .getOrNull()
    if (!cached.isNullOrBlank()) {
      return cached.split(',').filter { it.isNotBlank() }.toSet()
    }

    val permissions = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        SELECT p.code
        FROM role_permissions rp
        JOIN roles r ON r.id = rp.role_id
        JOIN permissions p ON p.id = rp.permission_id
        WHERE r.code = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, role.name)
        ps.executeQuery().use { rs ->
          val result = mutableSetOf<String>()
          while (rs.next()) {
            result += rs.getString("code")
          }
          result.toSet()
        }
      }
    }

    if (permissions.isNotEmpty()) {
      runCatching { cacheStore.setEx(cacheKey, 300L, permissions.joinToString(",")) }
        .onFailure { logger.warn("Redis write failed for RBAC permissions role={}", role, it) }
    }
    return permissions
  }

  suspend fun hasPermission(role: UserRole, permission: String): Boolean =
    permission in getPermissions(role)

  suspend fun isSessionActive(userId: UUID, sessionId: UUID): Boolean =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        SELECT 1
        FROM user_sessions s
        JOIN users u ON u.id = s.user_id
        WHERE s.id = ? AND s.user_id = ? AND s.revoked_at IS NULL AND u.is_active = TRUE
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, sessionId)
        ps.setObject(2, userId)
        ps.executeQuery().use { rs -> rs.next() }
      }
    }
}

fun Route.requirePermission(
  permission: String,
  authorizationService: AuthorizationService,
  build: Route.() -> Unit,
): Route {
  val authorizedRoute = createChild(object : io.ktor.server.routing.RouteSelector() {
    override suspend fun evaluate(context: io.ktor.server.routing.RoutingResolveContext, segmentIndex: Int) =
      io.ktor.server.routing.RouteSelectorEvaluation.Transparent
  })

  val plugin = createRouteScopedPlugin("RequirePermission-$permission") {
    on(AuthenticationChecked) { call ->
      val principal = call.principal<PizzoraPrincipal>()
        ?: throw ForbiddenException(code = "FORBIDDEN", message = "Missing security principal.")
      if (!authorizationService.hasPermission(principal.role, permission)) {
        throw ForbiddenException(
          code = "MISSING_PERMISSION",
          message = "Role ${principal.role.name} lacks required permission $permission.",
        )
      }
    }
  }
  authorizedRoute.install(plugin)
  authorizedRoute.build()
  return authorizedRoute
}

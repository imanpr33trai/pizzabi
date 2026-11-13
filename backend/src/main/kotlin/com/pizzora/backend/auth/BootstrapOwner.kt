package com.pizzora.backend.auth

import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.security.PasswordHasher
import java.util.UUID
import javax.sql.DataSource
import org.slf4j.LoggerFactory

object BootstrapOwner {
  private val logger = LoggerFactory.getLogger(BootstrapOwner::class.java)

  suspend fun bootstrap(
    dataSource: DataSource,
    passwordHasher: PasswordHasher,
    email: String,
    password: String,
    fullName: String,
    phone: String,
    employeeCode: String = "OWN-001",
  ): UUID {
    val normalizedEmail = email.trim().lowercase()
    require(normalizedEmail.contains('@')) { "Valid OWNER_EMAIL is required." }
    require(password.length >= 8) { "OWNER_PASSWORD must be at least 8 characters." }
    require(fullName.isNotBlank()) { "OWNER_NAME is required." }

    val passwordHash = passwordHasher.hash(password)
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val ownerRoleId = conn.prepareStatement("SELECT id FROM roles WHERE code = 'OWNER'").use { ps ->
        ps.executeQuery().use { rs ->
          if (!rs.next()) error("OWNER role not seeded. Run Flyway migrations first.")
          rs.getObject("id", UUID::class.java)
        }
      }

      val existingUserId = conn.prepareStatement("SELECT id FROM users WHERE email = ?").use { ps ->
        ps.setString(1, normalizedEmail)
        ps.executeQuery().use { rs ->
          if (rs.next()) rs.getObject("id", UUID::class.java) else null
        }
      }

      val userId = existingUserId ?: UUID.randomUUID()
      if (existingUserId == null) {
        conn.prepareStatement(
          """
          INSERT INTO users (id, email, email_verified_at, password_hash, full_name, phone, is_active)
          VALUES (?, ?, NOW(), ?, ?, ?, TRUE)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, userId)
          ps.setString(2, normalizedEmail)
          ps.setString(3, passwordHash)
          ps.setString(4, fullName.trim())
          ps.setString(5, phone.trim())
          ps.executeUpdate()
        }
      } else {
        conn.prepareStatement(
          """
          UPDATE users
          SET email_verified_at = COALESCE(email_verified_at, NOW()),
              password_hash = ?,
              full_name = ?,
              phone = ?,
              is_active = TRUE,
              updated_at = NOW()
          WHERE id = ?
          """.trimIndent(),
        ).use { ps ->
          ps.setString(1, passwordHash)
          ps.setString(2, fullName.trim())
          ps.setString(3, phone.trim())
          ps.setObject(4, userId)
          ps.executeUpdate()
        }
      }

      conn.prepareStatement(
        """
        INSERT INTO staff (user_id, role_id, employee_code, is_active, created_by)
        VALUES (?, ?, ?, TRUE, ?)
        ON CONFLICT (user_id) DO UPDATE
        SET role_id = EXCLUDED.role_id,
            is_active = TRUE
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, userId)
        ps.setObject(2, ownerRoleId)
        ps.setString(3, employeeCode)
        ps.setObject(4, userId)
        ps.executeUpdate()
      }

      logger.info("Bootstrapped OWNER account userId={} email={}", userId, normalizedEmail)
      userId
    }
  }
}

package com.pizzora.backend.users

import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ConflictException
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.StaffMemberDto
import com.pizzora.contracts.UpdateProfileRequest
import com.pizzora.contracts.UpdateStaffRequest
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import java.util.UUID
import javax.sql.DataSource

class UsersService(
  private val dataSource: DataSource,
  private val passwordHasher: PasswordHasher,
) {
  suspend fun getProfile(userId: UUID): UserProfileDto = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
    conn.prepareStatement(
      """
      SELECT u.id, u.email, u.email_verified_at, u.full_name, u.phone, u.is_active, r.code AS staff_role
      FROM users u
      LEFT JOIN staff s ON s.user_id = u.id AND s.is_active = TRUE
      LEFT JOIN roles r ON r.id = s.role_id
      WHERE u.id = ?
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, userId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) throw NotFoundException("USER_NOT_FOUND", "User profile not found.")
        val staffRole = rs.getString("staff_role")
        UserProfileDto(
          id = rs.getObject("id", UUID::class.java).toString(),
          email = rs.getString("email"),
          emailVerified = rs.getTimestamp("email_verified_at") != null,
          fullName = rs.getString("full_name"),
          phone = rs.getString("phone"),
          role = if (staffRole != null) UserRole.valueOf(staffRole) else UserRole.CUSTOMER,
          isActive = rs.getBoolean("is_active"),
        )
      }
    }
  }

  suspend fun updateProfile(userId: UUID, request: UpdateProfileRequest): UserProfileDto {
    if (request.fullName.isBlank()) throw ValidationException("INVALID_NAME", "Full name cannot be blank.")
    if (request.phone.trim().length < 7) throw ValidationException("INVALID_PHONE", "Phone number is invalid.")
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val updated = conn.prepareStatement(
        "UPDATE users SET full_name = ?, phone = ?, updated_at = NOW() WHERE id = ?",
      ).use { ps ->
        ps.setString(1, request.fullName.trim())
        ps.setString(2, request.phone.trim())
        ps.setObject(3, userId)
        ps.executeUpdate()
      }
      if (updated == 0) throw NotFoundException("USER_NOT_FOUND", "User profile not found.")
    }
    return getProfile(userId)
  }

  suspend fun listAddresses(userId: UUID): List<AddressDto> = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
    conn.prepareStatement(
      """
      SELECT id, label, line1, line2, landmark, pincode, city, is_default
      FROM addresses
      WHERE user_id = ?
      ORDER BY is_default DESC, created_at DESC
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, userId)
      ps.executeQuery().use { rs ->
        val list = mutableListOf<AddressDto>()
        while (rs.next()) {
          list += AddressDto(
            id = rs.getObject("id", UUID::class.java).toString(),
            label = rs.getString("label"),
            line1 = rs.getString("line1"),
            line2 = rs.getString("line2"),
            landmark = rs.getString("landmark"),
            pincode = rs.getString("pincode"),
            city = rs.getString("city"),
            isDefault = rs.getBoolean("is_default"),
          )
        }
        list
      }
    }
  }

  suspend fun createAddress(userId: UUID, request: UpsertAddressRequest): AddressDto {
    validateAddress(request)
    val addressId = UUID.randomUUID()
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      if (request.isDefault) {
        conn.prepareStatement("UPDATE addresses SET is_default = FALSE WHERE user_id = ?").use { ps ->
          ps.setObject(1, userId)
          ps.executeUpdate()
        }
      }
      conn.prepareStatement(
        """
        INSERT INTO addresses (id, user_id, label, line1, line2, landmark, pincode, city, is_default)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, addressId)
        ps.setObject(2, userId)
        ps.setString(3, request.label.trim())
        ps.setString(4, request.line1.trim())
        ps.setString(5, request.line2?.trim())
        ps.setString(6, request.landmark?.trim())
        ps.setString(7, request.pincode.trim())
        ps.setString(8, request.city.trim())
        ps.setBoolean(9, request.isDefault)
        ps.executeUpdate()
      }
      AddressDto(
        id = addressId.toString(),
        label = request.label.trim(),
        line1 = request.line1.trim(),
        line2 = request.line2?.trim(),
        landmark = request.landmark?.trim(),
        pincode = request.pincode.trim(),
        city = request.city.trim(),
        isDefault = request.isDefault,
      )
    }
  }

  suspend fun updateAddress(userId: UUID, addressId: UUID, request: UpsertAddressRequest): AddressDto {
    validateAddress(request)
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      if (request.isDefault) {
        conn.prepareStatement("UPDATE addresses SET is_default = FALSE WHERE user_id = ?").use { ps ->
          ps.setObject(1, userId)
          ps.executeUpdate()
        }
      }
      val updated = conn.prepareStatement(
        """
        UPDATE addresses
        SET label = ?, line1 = ?, line2 = ?, landmark = ?, pincode = ?, city = ?, is_default = ?
        WHERE id = ? AND user_id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, request.label.trim())
        ps.setString(2, request.line1.trim())
        ps.setString(3, request.line2?.trim())
        ps.setString(4, request.landmark?.trim())
        ps.setString(5, request.pincode.trim())
        ps.setString(6, request.city.trim())
        ps.setBoolean(7, request.isDefault)
        ps.setObject(8, addressId)
        ps.setObject(9, userId)
        ps.executeUpdate()
      }
      if (updated == 0) {
        throw NotFoundException("ADDRESS_NOT_FOUND", "Address not found for current user.")
      }
      AddressDto(
        id = addressId.toString(),
        label = request.label.trim(),
        line1 = request.line1.trim(),
        line2 = request.line2?.trim(),
        landmark = request.landmark?.trim(),
        pincode = request.pincode.trim(),
        city = request.city.trim(),
        isDefault = request.isDefault,
      )
    }
  }

  suspend fun deleteAddress(userId: UUID, addressId: UUID) {
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val deleted = conn.prepareStatement(
        "DELETE FROM addresses WHERE id = ? AND user_id = ?",
      ).use { ps ->
        ps.setObject(1, addressId)
        ps.setObject(2, userId)
        ps.executeUpdate()
      }
      if (deleted == 0) {
        throw NotFoundException("ADDRESS_NOT_FOUND", "Address not found for current user.")
      }
    }
  }

  suspend fun listStaff(): List<StaffMemberDto> = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
    conn.prepareStatement(
      """
      SELECT u.id, u.email, u.full_name, u.phone, r.code AS role_code, s.employee_code, s.is_active
      FROM staff s
      JOIN users u ON u.id = s.user_id
      JOIN roles r ON r.id = s.role_id
      ORDER BY s.created_at DESC
      """.trimIndent(),
    ).use { ps ->
      ps.executeQuery().use { rs ->
        val list = mutableListOf<StaffMemberDto>()
        while (rs.next()) {
          list += StaffMemberDto(
            userId = rs.getObject("id", UUID::class.java).toString(),
            email = rs.getString("email"),
            fullName = rs.getString("full_name"),
            phone = rs.getString("phone"),
            role = UserRole.valueOf(rs.getString("role_code")),
            employeeCode = rs.getString("employee_code"),
            isActive = rs.getBoolean("is_active"),
          )
        }
        list
      }
    }
  }

  suspend fun createStaff(actorOwnerId: UUID, request: CreateStaffRequest): StaffMemberDto {
    if (request.role != UserRole.KITCHEN_STAFF && request.role != UserRole.OWNER) {
      throw ValidationException("INVALID_STAFF_ROLE", "Staff accounts can only have role KITCHEN_STAFF or OWNER.")
    }
    val email = request.email.trim().lowercase()
    if (!email.contains('@')) throw ValidationException("INVALID_EMAIL", "Valid email is required.")
    if (request.password.length < 8) throw ValidationException("WEAK_PASSWORD", "Password must be at least 8 characters.")
    if (request.employeeCode.isBlank()) throw ValidationException("INVALID_EMPLOYEE_CODE", "Employee code is required.")

    val passwordHash = passwordHasher.hash(request.password)
    val newUserId = UUID.randomUUID()

    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val emailExists = conn.prepareStatement("SELECT 1 FROM users WHERE email = ?").use { ps ->
        ps.setString(1, email)
        ps.executeQuery().use { it.next() }
      }
      if (emailExists) throw ConflictException("EMAIL_ALREADY_EXISTS", "A user with this email already exists.")

      val codeExists = conn.prepareStatement("SELECT 1 FROM staff WHERE employee_code = ?").use { ps ->
        ps.setString(1, request.employeeCode.trim())
        ps.executeQuery().use { it.next() }
      }
      if (codeExists) throw ConflictException("EMPLOYEE_CODE_EXISTS", "Employee code is already assigned.")

      val roleId = conn.prepareStatement("SELECT id FROM roles WHERE code = ?").use { ps ->
        ps.setString(1, request.role.name)
        ps.executeQuery().use { rs ->
          if (!rs.next()) error("Missing seeded role ${request.role.name}")
          rs.getObject("id", UUID::class.java)
        }
      }

      conn.prepareStatement(
        """
        INSERT INTO users (id, email, email_verified_at, password_hash, full_name, phone, is_active)
        VALUES (?, ?, NOW(), ?, ?, ?, TRUE)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, newUserId)
        ps.setString(2, email)
        ps.setString(3, passwordHash)
        ps.setString(4, request.fullName.trim())
        ps.setString(5, request.phone.trim())
        ps.executeUpdate()
      }

      conn.prepareStatement(
        """
        INSERT INTO staff (user_id, role_id, employee_code, is_active, created_by)
        VALUES (?, ?, ?, TRUE, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, newUserId)
        ps.setObject(2, roleId)
        ps.setString(3, request.employeeCode.trim())
        ps.setObject(4, actorOwnerId)
        ps.executeUpdate()
      }
    }

    return StaffMemberDto(
      userId = newUserId.toString(),
      email = email,
      fullName = request.fullName.trim(),
      phone = request.phone.trim(),
      role = request.role,
      employeeCode = request.employeeCode.trim(),
      isActive = true,
    )
  }

  suspend fun updateStaff(targetUserId: UUID, request: UpdateStaffRequest): StaffMemberDto {
    val nextRole = request.role
    val nextActive = request.isActive
    if (nextRole != null && nextRole != UserRole.KITCHEN_STAFF && nextRole != UserRole.OWNER) {
      throw ValidationException("INVALID_STAFF_ROLE", "Staff role must be KITCHEN_STAFF or OWNER.")
    }

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val exists = conn.prepareStatement("SELECT 1 FROM staff WHERE user_id = ?").use { ps ->
        ps.setObject(1, targetUserId)
        ps.executeQuery().use { it.next() }
      }
      if (!exists) throw NotFoundException("STAFF_NOT_FOUND", "Staff member not found.")

      if (nextRole != null) {
        val roleId = conn.prepareStatement("SELECT id FROM roles WHERE code = ?").use { ps ->
          ps.setString(1, nextRole.name)
          ps.executeQuery().use { rs ->
            rs.next()
            rs.getObject("id", UUID::class.java)
          }
        }
        conn.prepareStatement("UPDATE staff SET role_id = ? WHERE user_id = ?").use { ps ->
          ps.setObject(1, roleId)
          ps.setObject(2, targetUserId)
          ps.executeUpdate()
        }
      }

      if (request.fullName != null || request.phone != null) {
        conn.prepareStatement(
          """
          UPDATE users
          SET full_name = COALESCE(?, full_name),
              phone = COALESCE(?, phone),
              updated_at = NOW()
          WHERE id = ?
          """.trimIndent(),
        ).use { ps ->
          ps.setString(1, request.fullName?.trim())
          ps.setString(2, request.phone?.trim())
          ps.setObject(3, targetUserId)
          ps.executeUpdate()
        }
      }

      if (nextActive != null) {
        conn.prepareStatement("UPDATE staff SET is_active = ? WHERE user_id = ?").use { ps ->
          ps.setBoolean(1, nextActive)
          ps.setObject(2, targetUserId)
          ps.executeUpdate()
        }
        conn.prepareStatement("UPDATE users SET is_active = ?, updated_at = NOW() WHERE id = ?").use { ps ->
          ps.setBoolean(1, nextActive)
          ps.setObject(2, targetUserId)
          ps.executeUpdate()
        }
        if (!nextActive) {
          conn.prepareStatement(
            "UPDATE user_sessions SET revoked_at = COALESCE(revoked_at, NOW()) WHERE user_id = ?",
          ).use { ps ->
            ps.setObject(1, targetUserId)
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
            ps.setObject(1, targetUserId)
            ps.executeUpdate()
          }
        }
      }

      conn.prepareStatement(
        """
        SELECT u.id, u.email, u.full_name, u.phone, r.code AS role_code, s.employee_code, s.is_active
        FROM staff s
        JOIN users u ON u.id = s.user_id
        JOIN roles r ON r.id = s.role_id
        WHERE s.user_id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, targetUserId)
        ps.executeQuery().use { rs ->
          rs.next()
          StaffMemberDto(
            userId = rs.getObject("id", UUID::class.java).toString(),
            email = rs.getString("email"),
            fullName = rs.getString("full_name"),
            phone = rs.getString("phone"),
            role = UserRole.valueOf(rs.getString("role_code")),
            employeeCode = rs.getString("employee_code"),
            isActive = rs.getBoolean("is_active"),
          )
        }
      }
    }
  }

  private fun validateAddress(request: UpsertAddressRequest) {
    if (request.label.isBlank() || request.line1.isBlank() || request.city.isBlank()) {
      throw ValidationException("INVALID_ADDRESS", "Label, line1, and city are required.")
    }
    if (!request.pincode.trim().matches(Regex("^[1-9][0-9]{5}$"))) {
      throw ValidationException("INVALID_PINCODE", "Pincode must be a valid 6-digit postal code.")
    }
  }
}

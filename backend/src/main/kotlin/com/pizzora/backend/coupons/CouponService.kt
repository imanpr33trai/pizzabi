package com.pizzora.backend.coupons

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ConflictException
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.core.error.UnprocessableEntityException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.AppliedCouponSummaryDto
import com.pizzora.contracts.AvailableCouponsResponse
import com.pizzora.contracts.CouponCalculationSpec
import com.pizzora.contracts.CouponDto
import com.pizzora.contracts.CreateCouponRequest
import com.pizzora.contracts.CustomerCouponDto
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.Money
import com.pizzora.contracts.PricingEngine
import com.pizzora.contracts.UpdateCouponRequest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json

data class ValidatedCoupon(
  val id: UUID,
  val code: String,
  val spec: CouponCalculationSpec,
  val discountPaise: Long,
)

class CouponService(
  private val dataSource: DataSource,
) {
  private val json = Json { encodeDefaults = true }

  suspend fun listAllCoupons(): List<CouponDto> =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        SELECT id, code, discount_type, discount_value, max_discount_paise,
               min_order_paise, max_uses, max_uses_per_user, usage_count,
               starts_at, ends_at, is_active
        FROM coupons
        ORDER BY starts_at DESC, code ASC
        """.trimIndent(),
      ).use { ps ->
        ps.executeQuery().use { rs ->
          val out = mutableListOf<CouponDto>()
          while (rs.next()) out += rs.toCouponDto()
          out
        }
      }
    }

  suspend fun createCoupon(request: CreateCouponRequest, audit: AuditContext): CouponDto {
    val normalizedCode = normalizeCode(request.code)
    val startsAt = parseInstant(request.startsAt, "starts_at")
    val endsAt = parseInstant(request.endsAt, "ends_at")
    validateCouponParameters(
      discountType = request.discountType,
      discountValue = request.discountValue,
      maxDiscountPaise = request.maxDiscountPaise,
      minOrderPaise = request.minOrderPaise,
      maxUses = request.maxUses,
      maxUsesPerUser = request.maxUsesPerUser,
      startsAt = startsAt,
      endsAt = endsAt,
    )

    val id = UUID.randomUUID()
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val created = try {
        conn.prepareStatement(
          """
          INSERT INTO coupons (
            id, code, discount_type, discount_value, max_discount_paise,
            min_order_paise, max_uses, max_uses_per_user, usage_count,
            starts_at, ends_at, is_active
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
          RETURNING id, code, discount_type, discount_value, max_discount_paise,
                    min_order_paise, max_uses, max_uses_per_user, usage_count,
                    starts_at, ends_at, is_active
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, id)
          ps.setString(2, normalizedCode)
          ps.setString(3, request.discountType.name)
          ps.setLong(4, request.discountValue)
          val maxDiscount = request.maxDiscountPaise
          val maxUses = request.maxUses
          if (maxDiscount != null) ps.setLong(5, maxDiscount) else ps.setNull(5, java.sql.Types.BIGINT)
          ps.setLong(6, request.minOrderPaise)
          if (maxUses != null) ps.setInt(7, maxUses) else ps.setNull(7, java.sql.Types.INTEGER)
          ps.setInt(8, request.maxUsesPerUser)
          ps.setTimestamp(9, Timestamp.from(startsAt))
          ps.setTimestamp(10, Timestamp.from(endsAt))
          ps.setBoolean(11, request.isActive)
          ps.executeQuery().use { rs ->
            rs.next()
            rs.toCouponDto()
          }
        }
      } catch (ex: SQLException) {
        if (ex.sqlState == "23505") {
          throw ConflictException("COUPON_CODE_EXISTS", "Coupon code '$normalizedCode' already exists.")
        }
        throw ex
      }

      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "COUPON_CREATED",
        entityType = "coupon",
        entityId = created.id,
        afterJson = json.encodeToString(created),
      )
      created
    }
  }

  suspend fun updateCoupon(
    couponId: UUID,
    request: UpdateCouponRequest,
    audit: AuditContext,
  ): CouponDto =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val current = conn.prepareStatement(
        """
        SELECT id, code, discount_type, discount_value, max_discount_paise,
               min_order_paise, max_uses, max_uses_per_user, usage_count,
               starts_at, ends_at, is_active
        FROM coupons
        WHERE id = ?
        FOR UPDATE
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, couponId)
        ps.executeQuery().use { rs ->
          if (!rs.next()) throw NotFoundException("COUPON_NOT_FOUND", "Coupon $couponId not found.")
          rs.toCouponDto()
        }
      }

      val discountType = request.discountType ?: current.discountType
      val discountValue = request.discountValue ?: current.discountValue
      val maxDiscountPaise = when {
        request.clearMaxDiscount -> null
        request.maxDiscountPaise != null -> request.maxDiscountPaise
        else -> current.maxDiscountPaise
      }
      val minOrderPaise = request.minOrderPaise ?: current.minOrderPaise
      val maxUses = when {
        request.clearMaxUses -> null
        request.maxUses != null -> request.maxUses
        else -> current.maxUses
      }
      val maxUsesPerUser = request.maxUsesPerUser ?: current.maxUsesPerUser
      val startsAt = request.startsAt?.let { parseInstant(it, "starts_at") } ?: Instant.parse(current.startsAt)
      val endsAt = request.endsAt?.let { parseInstant(it, "ends_at") } ?: Instant.parse(current.endsAt)
      val isActive = request.isActive ?: current.isActive

      validateCouponParameters(
        discountType = discountType,
        discountValue = discountValue,
        maxDiscountPaise = maxDiscountPaise,
        minOrderPaise = minOrderPaise,
        maxUses = maxUses,
        maxUsesPerUser = maxUsesPerUser,
        startsAt = startsAt,
        endsAt = endsAt,
      )
      if (maxUses != null && maxUses < current.usageCount) {
        throw ValidationException(
          "INVALID_MAX_USES",
          "max_uses ($maxUses) cannot be less than current usage_count (${current.usageCount}).",
        )
      }

      val updated = conn.prepareStatement(
        """
        UPDATE coupons
        SET discount_type = ?,
            discount_value = ?,
            max_discount_paise = ?,
            min_order_paise = ?,
            max_uses = ?,
            max_uses_per_user = ?,
            starts_at = ?,
            ends_at = ?,
            is_active = ?
        WHERE id = ?
        RETURNING id, code, discount_type, discount_value, max_discount_paise,
                  min_order_paise, max_uses, max_uses_per_user, usage_count,
                  starts_at, ends_at, is_active
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, discountType.name)
        ps.setLong(2, discountValue)
        if (maxDiscountPaise != null) ps.setLong(3, maxDiscountPaise) else ps.setNull(3, java.sql.Types.BIGINT)
        ps.setLong(4, minOrderPaise)
        if (maxUses != null) ps.setInt(5, maxUses) else ps.setNull(5, java.sql.Types.INTEGER)
        ps.setInt(6, maxUsesPerUser)
        ps.setTimestamp(7, Timestamp.from(startsAt))
        ps.setTimestamp(8, Timestamp.from(endsAt))
        ps.setBoolean(9, isActive)
        ps.setObject(10, couponId)
        ps.executeQuery().use { rs ->
          rs.next()
          rs.toCouponDto()
        }
      }

      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "COUPON_UPDATED",
        entityType = "coupon",
        entityId = couponId.toString(),
        beforeJson = json.encodeToString(current),
        afterJson = json.encodeToString(updated),
      )
      updated
    }

  suspend fun deleteOrDeactivateCoupon(couponId: UUID, audit: AuditContext) {
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val current = conn.prepareStatement(
        """
        SELECT id, code, discount_type, discount_value, max_discount_paise,
               min_order_paise, max_uses, max_uses_per_user, usage_count,
               starts_at, ends_at, is_active
        FROM coupons
        WHERE id = ?
        FOR UPDATE
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, couponId)
        ps.executeQuery().use { rs ->
          if (!rs.next()) throw NotFoundException("COUPON_NOT_FOUND", "Coupon $couponId not found.")
          rs.toCouponDto()
        }
      }

      val hasRefs = conn.prepareStatement(
        """
        SELECT EXISTS (
          SELECT 1 FROM coupon_reservations WHERE coupon_id = ?
          UNION ALL
          SELECT 1 FROM coupon_redemptions WHERE coupon_id = ?
        )
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, couponId)
        ps.setObject(2, couponId)
        ps.executeQuery().use { rs ->
          rs.next()
          rs.getBoolean(1)
        }
      }

      if (hasRefs) {
        conn.prepareStatement("UPDATE coupons SET is_active = FALSE WHERE id = ?").use { ps ->
          ps.setObject(1, couponId)
          ps.executeUpdate()
        }
        AuditLogger.record(
          conn = conn,
          context = audit,
          action = "COUPON_DEACTIVATED",
          entityType = "coupon",
          entityId = couponId.toString(),
          beforeJson = json.encodeToString(current),
          afterJson = json.encodeToString(current.copy(isActive = false)),
        )
      } else {
        conn.prepareStatement("DELETE FROM coupons WHERE id = ?").use { ps ->
          ps.setObject(1, couponId)
          ps.executeUpdate()
        }
        AuditLogger.record(
          conn = conn,
          context = audit,
          action = "COUPON_DELETED",
          entityType = "coupon",
          entityId = couponId.toString(),
          beforeJson = json.encodeToString(current),
        )
      }
    }
  }

  suspend fun listAvailableCoupons(
    userId: UUID,
    subtotalPaise: Long?,
  ): AvailableCouponsResponse {
    if (subtotalPaise != null && subtotalPaise < 0L) {
      throw ValidationException("INVALID_SUBTOTAL", "subtotal_paise must be non-negative.")
    }

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val coupons = conn.prepareStatement(
        """
        SELECT id, code, discount_type, discount_value, max_discount_paise,
               min_order_paise, max_uses, max_uses_per_user, usage_count,
               starts_at, ends_at, is_active
        FROM coupons
        WHERE is_active = TRUE
          AND starts_at <= NOW()
          AND ends_at > NOW()
        ORDER BY min_order_paise ASC, code ASC
        """.trimIndent(),
      ).use { ps ->
        ps.executeQuery().use { rs ->
          val list = mutableListOf<CouponRow>()
          while (rs.next()) list += rs.toCouponRow()
          list
        }
      }

      val activeGlobalReservations = mutableMapOf<UUID, Int>()
      conn.prepareStatement(
        """
        SELECT coupon_id, COUNT(*)::int AS cnt
        FROM coupon_reservations
        WHERE status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > NOW())
        GROUP BY coupon_id
        """.trimIndent(),
      ).use { ps ->
        ps.executeQuery().use { rs ->
          while (rs.next()) {
            activeGlobalReservations[rs.getObject("coupon_id", UUID::class.java)] = rs.getInt("cnt")
          }
        }
      }

      val userUsageByCoupon = mutableMapOf<UUID, Int>()
      conn.prepareStatement(
        """
        SELECT coupon_id, COUNT(*)::int AS cnt
        FROM (
          SELECT coupon_id, order_id FROM coupon_redemptions WHERE user_id = ?
          UNION
          SELECT coupon_id, order_id FROM coupon_reservations
          WHERE user_id = ? AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > NOW())
        ) combined
        GROUP BY coupon_id
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, userId)
        ps.setObject(2, userId)
        ps.executeQuery().use { rs ->
          while (rs.next()) {
            userUsageByCoupon[rs.getObject("coupon_id", UUID::class.java)] = rs.getInt("cnt")
          }
        }
      }

      val items = coupons.map { row ->
        val activeGlobal = activeGlobalReservations[row.id] ?: 0
        val usedGlobal = row.usageCount + activeGlobal
        val remainingGlobal = row.maxUses?.let { (it - usedGlobal).coerceAtLeast(0) }
        val userUsed = userUsageByCoupon[row.id] ?: 0
        val remainingUser = (row.maxUsesPerUser - userUsed).coerceAtLeast(0)

        val ineligibleReason = when {
          remainingGlobal != null && remainingGlobal <= 0 -> "Coupon redemption limit reached."
          remainingUser <= 0 -> "Per-user usage limit reached."
          subtotalPaise != null && subtotalPaise < row.minOrderPaise ->
            "Add ${Money.formatInr(row.minOrderPaise - subtotalPaise)} more (min order ${Money.formatInr(row.minOrderPaise)})."
          else -> null
        }
        val eligible = ineligibleReason == null
        val estimatedDiscount = if (subtotalPaise != null && eligible) {
          PricingEngine.calculateDiscountPaise(subtotalPaise, row.toCalculationSpec())
        } else {
          0L
        }

        CustomerCouponDto(
          id = row.id.toString(),
          code = row.code,
          discountType = row.discountType,
          discountValue = row.discountValue,
          maxDiscountPaise = row.maxDiscountPaise,
          minOrderPaise = row.minOrderPaise,
          maxUses = row.maxUses,
          maxUsesPerUser = row.maxUsesPerUser,
          userUsageCount = userUsed,
          remainingGlobalUses = remainingGlobal,
          remainingUserUses = remainingUser,
          startsAt = row.startsAt.toString(),
          endsAt = row.endsAt.toString(),
          isEligible = eligible,
          ineligibleReason = ineligibleReason,
          estimatedDiscountPaise = estimatedDiscount,
        )
      }

      AvailableCouponsResponse(
        coupons = items,
        subtotalPaise = subtotalPaise,
      )
    }
  }

  fun validateCouponForQuote(
    conn: Connection,
    rawCode: String,
    userId: UUID,
    subtotalPaise: Long,
  ): ValidatedCoupon {
    val row = loadCouponByCode(conn, rawCode, lockForUpdate = false)
    return evaluateEligibility(conn, row, userId, subtotalPaise, useConflictOnLimit = false)
  }

  fun reserveCouponForOrder(
    conn: Connection,
    rawCode: String,
    userId: UUID,
    orderId: UUID,
    subtotalPaise: Long,
    expiresAt: Instant?,
    immediateCommit: Boolean,
  ): AppliedCouponSummaryDto {
    // Acquire exclusive PostgreSQL row lock FOR UPDATE so concurrent checkouts serialize cleanly
    val row = loadCouponByCode(conn, rawCode, lockForUpdate = true)
    val validated = evaluateEligibility(conn, row, userId, subtotalPaise, useConflictOnLimit = true)

    val reservationId = UUID.randomUUID()
    val status = if (immediateCommit) "COMMITTED" else "ACTIVE"
    conn.prepareStatement(
      """
      INSERT INTO coupon_reservations (
        id, coupon_id, user_id, order_id, discount_paise, status, expires_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, reservationId)
      ps.setObject(2, validated.id)
      ps.setObject(3, userId)
      ps.setObject(4, orderId)
      ps.setLong(5, validated.discountPaise)
      ps.setString(6, status)
      if (expiresAt != null && !immediateCommit) {
        ps.setTimestamp(7, Timestamp.from(expiresAt))
      } else {
        ps.setNull(7, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
      }
      ps.executeUpdate()
    }

    if (immediateCommit) {
      recordRedemptionAndIncrementUsage(conn, validated.id, userId, orderId, validated.discountPaise)
    }

    return AppliedCouponSummaryDto(
      id = validated.id.toString(),
      code = validated.code,
      discountType = validated.spec.discountType,
      discountValue = validated.spec.discountValue,
      discountPaise = validated.discountPaise,
    )
  }

  fun commitReservedCoupon(conn: Connection, orderId: UUID) {
    val committed = conn.prepareStatement(
      """
      UPDATE coupon_reservations
      SET status = 'COMMITTED', expires_at = NULL
      WHERE order_id = ? AND status = 'ACTIVE'
      RETURNING coupon_id, user_id, discount_paise
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, orderId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) null
        else Triple(
          rs.getObject("coupon_id", UUID::class.java),
          rs.getObject("user_id", UUID::class.java),
          rs.getLong("discount_paise"),
        )
      }
    } ?: return

    recordRedemptionAndIncrementUsage(
      conn = conn,
      couponId = committed.first,
      userId = committed.second,
      orderId = orderId,
      discountPaise = committed.third,
    )
  }

  fun releaseReservedCoupon(conn: Connection, orderId: UUID, expired: Boolean = false) {
    val targetStatus = if (expired) "EXPIRED" else "RELEASED"
    conn.prepareStatement(
      """
      UPDATE coupon_reservations
      SET status = ?, expires_at = NULL
      WHERE order_id = ? AND status = 'ACTIVE'
      """.trimIndent(),
    ).use { ps ->
      ps.setString(1, targetStatus)
      ps.setObject(2, orderId)
      ps.executeUpdate()
    }
  }

  private fun recordRedemptionAndIncrementUsage(
    conn: Connection,
    couponId: UUID,
    userId: UUID,
    orderId: UUID,
    discountPaise: Long,
  ) {
    val inserted = conn.prepareStatement(
      """
      INSERT INTO coupon_redemptions (id, coupon_id, user_id, order_id, discount_paise)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (order_id) DO NOTHING
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, UUID.randomUUID())
      ps.setObject(2, couponId)
      ps.setObject(3, userId)
      ps.setObject(4, orderId)
      ps.setLong(5, discountPaise)
      ps.executeUpdate()
    }

    if (inserted > 0) {
      conn.prepareStatement(
        "UPDATE coupons SET usage_count = usage_count + 1 WHERE id = ?",
      ).use { ps ->
        ps.setObject(1, couponId)
        ps.executeUpdate()
      }
    }
  }

  private fun loadCouponByCode(
    conn: Connection,
    rawCode: String,
    lockForUpdate: Boolean,
  ): CouponRow {
    val normalized = rawCode.trim().uppercase()
    if (normalized.isEmpty()) {
      throw ValidationException("INVALID_COUPON_CODE", "Coupon code cannot be blank.")
    }
    val lockSuffix = if (lockForUpdate) "FOR UPDATE" else ""
    return conn.prepareStatement(
      """
      SELECT id, code, discount_type, discount_value, max_discount_paise,
             min_order_paise, max_uses, max_uses_per_user, usage_count,
             starts_at, ends_at, is_active
      FROM coupons
      WHERE UPPER(code) = ?
      $lockSuffix
      """.trimIndent(),
    ).use { ps ->
      ps.setString(1, normalized)
      ps.executeQuery().use { rs ->
        if (!rs.next()) {
          throw UnprocessableEntityException("COUPON_NOT_FOUND", "Coupon code '$normalized' was not found.")
        }
        rs.toCouponRow()
      }
    }
  }

  private fun evaluateEligibility(
    conn: Connection,
    row: CouponRow,
    userId: UUID,
    subtotalPaise: Long,
    useConflictOnLimit: Boolean,
  ): ValidatedCoupon {
    val now = Instant.now()
    if (!row.isActive) {
      throw UnprocessableEntityException("COUPON_INACTIVE", "Coupon '${row.code}' is inactive.")
    }
    if (now.isBefore(row.startsAt)) {
      throw UnprocessableEntityException("COUPON_NOT_STARTED", "Coupon '${row.code}' is not active yet.")
    }
    if (!now.isBefore(row.endsAt)) {
      throw UnprocessableEntityException("COUPON_EXPIRED", "Coupon '${row.code}' has expired.")
    }
    if (subtotalPaise < row.minOrderPaise) {
      throw UnprocessableEntityException(
        "COUPON_MIN_ORDER_NOT_MET",
        "Minimum order of ${Money.formatInr(row.minOrderPaise)} required for coupon '${row.code}'.",
        details = mapOf("min_order_paise" to row.minOrderPaise.toString()),
      )
    }

    val activeReservations = conn.prepareStatement(
      """
      SELECT COUNT(*)::int
      FROM coupon_reservations
      WHERE coupon_id = ? AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > NOW())
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, row.id)
      ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
    }

    val totalGlobalUsed = row.usageCount + activeReservations
    if (row.maxUses != null && totalGlobalUsed >= row.maxUses) {
      if (useConflictOnLimit) {
        throw ConflictException(
          "COUPON_USAGE_LIMIT_REACHED",
          "Coupon '${row.code}' has reached its maximum usage limit.",
        )
      }
      throw UnprocessableEntityException(
        "COUPON_USAGE_LIMIT_REACHED",
        "Coupon '${row.code}' has reached its maximum usage limit.",
      )
    }

    val userUsed = conn.prepareStatement(
      """
      SELECT COUNT(*)::int
      FROM (
        SELECT order_id FROM coupon_redemptions WHERE coupon_id = ? AND user_id = ?
        UNION
        SELECT order_id FROM coupon_reservations
        WHERE coupon_id = ? AND user_id = ? AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > NOW())
      ) u
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, row.id)
      ps.setObject(2, userId)
      ps.setObject(3, row.id)
      ps.setObject(4, userId)
      ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
    }

    if (userUsed >= row.maxUsesPerUser) {
      if (useConflictOnLimit) {
        throw ConflictException(
          "COUPON_USER_LIMIT_REACHED",
          "You have already used coupon '${row.code}' the maximum allowed times.",
        )
      }
      throw UnprocessableEntityException(
        "COUPON_USER_LIMIT_REACHED",
        "You have already used coupon '${row.code}' the maximum allowed times.",
      )
    }

    val spec = row.toCalculationSpec()
    val discountPaise = PricingEngine.calculateDiscountPaise(subtotalPaise, spec)
    return ValidatedCoupon(
      id = row.id,
      code = row.code,
      spec = spec,
      discountPaise = discountPaise,
    )
  }

  private fun normalizeCode(raw: String): String {
    val normalized = raw.trim().uppercase()
    if (!normalized.matches(COUPON_CODE_REGEX)) {
      throw ValidationException(
        "INVALID_COUPON_CODE",
        "Coupon code must be 3-32 uppercase alphanumeric, '_' or '-' characters.",
      )
    }
    return normalized
  }

  private fun parseInstant(raw: String, field: String): Instant =
    runCatching { OffsetDateTime.parse(raw.trim()).toInstant() }
      .recoverCatching { Instant.parse(raw.trim()) }
      .getOrElse {
        throw ValidationException("INVALID_TIMESTAMP", "Invalid ISO-8601 timestamp for $field: $raw")
      }

  private fun validateCouponParameters(
    discountType: DiscountType,
    discountValue: Long,
    maxDiscountPaise: Long?,
    minOrderPaise: Long,
    maxUses: Int?,
    maxUsesPerUser: Int,
    startsAt: Instant,
    endsAt: Instant,
  ) {
    if (discountValue <= 0L) {
      throw ValidationException("INVALID_DISCOUNT_VALUE", "discount_value must be greater than 0.")
    }
    if (discountType == DiscountType.PERCENT_BPS && discountValue > 10_000L) {
      throw ValidationException("INVALID_PERCENT_BPS", "PERCENT_BPS discount_value cannot exceed 10000 (100%).")
    }
    if (maxDiscountPaise != null && maxDiscountPaise <= 0L) {
      throw ValidationException("INVALID_MAX_DISCOUNT", "max_discount_paise must be greater than 0.")
    }
    if (minOrderPaise < 0L) {
      throw ValidationException("INVALID_MIN_ORDER", "min_order_paise must be non-negative.")
    }
    if (maxUses != null && maxUses <= 0) {
      throw ValidationException("INVALID_MAX_USES", "max_uses must be greater than 0 when set.")
    }
    if (maxUsesPerUser < 1) {
      throw ValidationException("INVALID_MAX_USES_PER_USER", "max_uses_per_user must be at least 1.")
    }
    if (!endsAt.isAfter(startsAt)) {
      throw ValidationException("INVALID_COUPON_WINDOW", "ends_at must be strictly after starts_at.")
    }
  }

  private fun ResultSet.toCouponDto(): CouponDto = CouponDto(
    id = getObject("id", UUID::class.java).toString(),
    code = getString("code"),
    discountType = DiscountType.valueOf(getString("discount_type")),
    discountValue = getLong("discount_value"),
    maxDiscountPaise = getObject("max_discount_paise")?.let { (it as Number).toLong() },
    minOrderPaise = getLong("min_order_paise"),
    maxUses = getObject("max_uses")?.let { (it as Number).toInt() },
    maxUsesPerUser = getInt("max_uses_per_user"),
    usageCount = getInt("usage_count"),
    startsAt = getObject("starts_at", OffsetDateTime::class.java).toInstant().toString(),
    endsAt = getObject("ends_at", OffsetDateTime::class.java).toInstant().toString(),
    isActive = getBoolean("is_active"),
  )

  private fun ResultSet.toCouponRow(): CouponRow = CouponRow(
    id = getObject("id", UUID::class.java),
    code = getString("code"),
    discountType = DiscountType.valueOf(getString("discount_type")),
    discountValue = getLong("discount_value"),
    maxDiscountPaise = getObject("max_discount_paise")?.let { (it as Number).toLong() },
    minOrderPaise = getLong("min_order_paise"),
    maxUses = getObject("max_uses")?.let { (it as Number).toInt() },
    maxUsesPerUser = getInt("max_uses_per_user"),
    usageCount = getInt("usage_count"),
    startsAt = getObject("starts_at", OffsetDateTime::class.java).toInstant(),
    endsAt = getObject("ends_at", OffsetDateTime::class.java).toInstant(),
    isActive = getBoolean("is_active"),
  )

  private data class CouponRow(
    val id: UUID,
    val code: String,
    val discountType: DiscountType,
    val discountValue: Long,
    val maxDiscountPaise: Long?,
    val minOrderPaise: Long,
    val maxUses: Int?,
    val maxUsesPerUser: Int,
    val usageCount: Int,
    val startsAt: Instant,
    val endsAt: Instant,
    val isActive: Boolean,
  ) {
    fun toCalculationSpec() = CouponCalculationSpec(
      discountType = discountType,
      discountValue = discountValue,
      maxDiscountPaise = maxDiscountPaise,
      minOrderPaise = minOrderPaise,
      isActive = isActive,
    )
  }

  companion object {
    private val COUPON_CODE_REGEX = Regex("^[A-Z0-9_-]{3,32}$")
  }
}

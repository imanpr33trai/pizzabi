package com.pizzora.backend.slots

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ConflictException
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.AvailableSlotsResponse
import com.pizzora.contracts.CreateScheduledSlotRequest
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.ScheduledSlotDto
import com.pizzora.contracts.UpdateScheduledSlotRequest
import java.sql.Date
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Time
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ScheduledSlotService(
  private val dataSource: DataSource,
) {
  suspend fun listAvailableSlots(
    rawType: String?,
    rawDate: String?,
    now: Instant = Instant.now(),
  ): AvailableSlotsResponse {
    val fulfillmentType = parseFulfillmentType(rawType) ?: FulfillmentType.DELIVERY
    val slotDate = if (rawDate.isNullOrBlank()) {
      LocalDate.now(IST_ZONE)
    } else {
      parseLocalDate(rawDate)
    }

    val slots = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        SELECT id, slot_date, fulfillment_type, start_time_ist, end_time_ist,
               cutoff_at, max_capacity, reserved_count, is_active
        FROM scheduled_slots
        WHERE slot_date = ? AND fulfillment_type = ? AND is_active = TRUE
        ORDER BY start_time_ist ASC
        """.trimIndent(),
      ).use { ps ->
        ps.setDate(1, Date.valueOf(slotDate))
        ps.setString(2, fulfillmentType.name)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<ScheduledSlotDto>()
          while (rs.next()) list += rs.toScheduledSlotDto(now)
          list
        }
      }
    }

    return AvailableSlotsResponse(
      date = slotDate.toString(),
      fulfillmentType = fulfillmentType,
      timezone = IST_ZONE.id,
      serverNow = now.toString(),
      slots = slots,
    )
  }

  suspend fun listOwnerSlots(
    rawType: String?,
    rawDate: String?,
    now: Instant = Instant.now(),
  ): List<ScheduledSlotDto> {
    val typeFilter = parseFulfillmentType(rawType)
    val dateFilter = rawDate?.takeIf { it.isNotBlank() }?.let { parseLocalDate(it) }

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val sql = buildString {
        append(
          """
          SELECT id, slot_date, fulfillment_type, start_time_ist, end_time_ist,
                 cutoff_at, max_capacity, reserved_count, is_active
          FROM scheduled_slots
          WHERE 1 = 1
          """.trimIndent(),
        )
        if (dateFilter != null) append(" AND slot_date = ?")
        if (typeFilter != null) append(" AND fulfillment_type = ?")
        append(" ORDER BY slot_date ASC, start_time_ist ASC LIMIT 200")
      }
      conn.prepareStatement(sql).use { ps ->
        var idx = 1
        if (dateFilter != null) ps.setDate(idx++, Date.valueOf(dateFilter))
        if (typeFilter != null) ps.setString(idx, typeFilter.name)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<ScheduledSlotDto>()
          while (rs.next()) list += rs.toScheduledSlotDto(now)
          list
        }
      }
    }
  }

  suspend fun createSlot(
    request: CreateScheduledSlotRequest,
    audit: AuditContext,
    now: Instant = Instant.now(),
  ): ScheduledSlotDto {
    val slotDate = parseLocalDate(request.slotDate)
    val startTime = parseLocalTime(request.startTimeIst, "start_time_ist")
    val endTime = parseLocalTime(request.endTimeIst, "end_time_ist")
    if (!endTime.isAfter(startTime)) {
      throw ValidationException("INVALID_SLOT_WINDOW", "end_time_ist must be after start_time_ist.")
    }
    if (request.maxCapacity <= 0) {
      throw ValidationException("INVALID_SLOT_CAPACITY", "max_capacity must be greater than zero.")
    }

    val slotStartInstant = ZonedDateTime.of(slotDate, startTime, IST_ZONE).toInstant()
    val cutoffAt = resolveCutoffAt(
      rawCutoffAt = request.cutoffAt,
      cutoffMinutesBeforeStart = request.cutoffMinutesBeforeStart,
      slotStartInstant = slotStartInstant,
    )

    val slotId = UUID.randomUUID()
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      try {
        conn.prepareStatement(
          """
          INSERT INTO scheduled_slots (
            id, slot_date, fulfillment_type, start_time_ist, end_time_ist,
            cutoff_at, max_capacity, reserved_count, is_active
          ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, slotId)
          ps.setDate(2, Date.valueOf(slotDate))
          ps.setString(3, request.fulfillmentType.name)
          ps.setTime(4, Time.valueOf(startTime))
          ps.setTime(5, Time.valueOf(endTime))
          ps.setTimestamp(6, Timestamp.from(cutoffAt))
          ps.setInt(7, request.maxCapacity)
          ps.setBoolean(8, request.isActive)
          ps.executeUpdate()
        }
      } catch (ex: SQLException) {
        if (ex.sqlState == "23505") {
          throw ConflictException(
            "SLOT_ALREADY_EXISTS",
            "A ${request.fulfillmentType.name} slot for $slotDate at ${TIME_FMT.format(startTime)} IST already exists.",
          )
        }
        throw ex
      }

      val created = buildDto(
        id = slotId,
        slotDate = slotDate,
        fulfillmentType = request.fulfillmentType,
        startTime = startTime,
        endTime = endTime,
        cutoffAt = cutoffAt,
        maxCapacity = request.maxCapacity,
        reservedCount = 0,
        isActive = request.isActive,
        now = now,
      )
      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "SCHEDULED_SLOT_CREATED",
        entityType = "scheduled_slot",
        entityId = created.id,
        afterJson = buildJsonObject {
          put("slot_date", created.slotDate)
          put("fulfillment_type", created.fulfillmentType.name)
          put("start_time_ist", created.startTimeIst)
          put("end_time_ist", created.endTimeIst)
          put("cutoff_at", created.cutoffAt)
          put("max_capacity", created.maxCapacity)
          put("is_active", created.isActive)
        }.toString(),
      )
      created
    }
  }

  suspend fun updateSlot(
    slotId: UUID,
    request: UpdateScheduledSlotRequest,
    audit: AuditContext,
    now: Instant = Instant.now(),
  ): ScheduledSlotDto {
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val existing = conn.prepareStatement(
        """
        SELECT id, slot_date, fulfillment_type, start_time_ist, end_time_ist,
               cutoff_at, max_capacity, reserved_count, is_active
        FROM scheduled_slots
        WHERE id = ?
        FOR UPDATE
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, slotId)
        ps.executeQuery().use { rs ->
          if (!rs.next()) throw NotFoundException("SLOT_NOT_FOUND", "Scheduled slot not found.")
          SlotRow(
            id = rs.getObject("id", UUID::class.java),
            slotDate = rs.getObject("slot_date", LocalDate::class.java),
            fulfillmentType = FulfillmentType.valueOf(rs.getString("fulfillment_type")),
            startTime = rs.getObject("start_time_ist", LocalTime::class.java),
            endTime = rs.getObject("end_time_ist", LocalTime::class.java),
            cutoffAt = rs.getObject("cutoff_at", OffsetDateTime::class.java).toInstant(),
            maxCapacity = rs.getInt("max_capacity"),
            reservedCount = rs.getInt("reserved_count"),
            isActive = rs.getBoolean("is_active"),
          )
        }
      }

      val nextCapacity = request.maxCapacity ?: existing.maxCapacity
      if (nextCapacity <= 0) {
        throw ValidationException("INVALID_SLOT_CAPACITY", "max_capacity must be greater than zero.")
      }
      if (nextCapacity < existing.reservedCount) {
        throw ConflictException(
          "SLOT_CAPACITY_BELOW_RESERVED",
          "max_capacity ($nextCapacity) cannot be lower than current reserved_count (${existing.reservedCount}).",
        )
      }

      val slotStartInstant = ZonedDateTime.of(existing.slotDate, existing.startTime, IST_ZONE).toInstant()
      val nextCutoff = if (request.cutoffAt != null || request.cutoffMinutesBeforeStart != null) {
        resolveCutoffAt(
          rawCutoffAt = request.cutoffAt,
          cutoffMinutesBeforeStart = request.cutoffMinutesBeforeStart,
          slotStartInstant = slotStartInstant,
        )
      } else {
        existing.cutoffAt
      }
      val nextActive = request.isActive ?: existing.isActive

      conn.prepareStatement(
        """
        UPDATE scheduled_slots
        SET cutoff_at = ?, max_capacity = ?, is_active = ?
        WHERE id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setTimestamp(1, Timestamp.from(nextCutoff))
        ps.setInt(2, nextCapacity)
        ps.setBoolean(3, nextActive)
        ps.setObject(4, slotId)
        ps.executeUpdate()
      }

      val updated = buildDto(
        id = existing.id,
        slotDate = existing.slotDate,
        fulfillmentType = existing.fulfillmentType,
        startTime = existing.startTime,
        endTime = existing.endTime,
        cutoffAt = nextCutoff,
        maxCapacity = nextCapacity,
        reservedCount = existing.reservedCount,
        isActive = nextActive,
        now = now,
      )
      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "SCHEDULED_SLOT_UPDATED",
        entityType = "scheduled_slot",
        entityId = updated.id,
        beforeJson = buildJsonObject {
          put("cutoff_at", existing.cutoffAt.toString())
          put("max_capacity", existing.maxCapacity)
          put("is_active", existing.isActive)
        }.toString(),
        afterJson = buildJsonObject {
          put("cutoff_at", updated.cutoffAt)
          put("max_capacity", updated.maxCapacity)
          put("is_active", updated.isActive)
        }.toString(),
      )
      updated
    }
  }

  suspend fun deleteSlot(
    slotId: UUID,
    audit: AuditContext,
  ) {
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val exists = conn.prepareStatement("SELECT 1 FROM scheduled_slots WHERE id = ? FOR UPDATE").use { ps ->
        ps.setObject(1, slotId)
        ps.executeQuery().use { it.next() }
      }
      if (!exists) {
        throw NotFoundException("SLOT_NOT_FOUND", "Scheduled slot not found.")
      }

      val hasRefs = conn.prepareStatement(
        """
        SELECT (
          EXISTS (SELECT 1 FROM scheduled_slot_reservations WHERE slot_id = ?) OR
          EXISTS (SELECT 1 FROM orders WHERE scheduled_slot_id = ?)
        ) AS in_use
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, slotId)
        ps.setObject(2, slotId)
        ps.executeQuery().use { rs -> rs.next() && rs.getBoolean("in_use") }
      }
      if (hasRefs) {
        throw ConflictException(
          "SLOT_IN_USE",
          "Scheduled slot already has orders or reservations; deactivate it instead.",
        )
      }

      conn.prepareStatement("DELETE FROM scheduled_slots WHERE id = ?").use { ps ->
        ps.setObject(1, slotId)
        ps.executeUpdate()
      }

      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "SCHEDULED_SLOT_DELETED",
        entityType = "scheduled_slot",
        entityId = slotId.toString(),
      )
    }
  }

  private fun resolveCutoffAt(
    rawCutoffAt: String?,
    cutoffMinutesBeforeStart: Int?,
    slotStartInstant: Instant,
  ): Instant {
    if (!rawCutoffAt.isNullOrBlank()) {
      return runCatching { Instant.parse(rawCutoffAt.trim()) }
        .recoverCatching { OffsetDateTime.parse(rawCutoffAt.trim()).toInstant() }
        .getOrElse {
          throw ValidationException("INVALID_CUTOFF_AT", "cutoff_at must be an ISO-8601 timestamp.")
        }
    }
    val mins = cutoffMinutesBeforeStart ?: DEFAULT_CUTOFF_MINUTES
    if (mins < 0) {
      throw ValidationException("INVALID_CUTOFF_MINUTES", "cutoff_minutes_before_start cannot be negative.")
    }
    return slotStartInstant.minusSeconds(mins.toLong() * 60L)
  }

  private fun parseFulfillmentType(raw: String?): FulfillmentType? {
    val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { FulfillmentType.valueOf(cleaned.uppercase()) }
      .getOrElse {
        throw ValidationException("INVALID_FULFILLMENT_TYPE", "type must be DELIVERY or PICKUP.")
      }
  }

  private fun parseLocalDate(raw: String): LocalDate =
    runCatching { LocalDate.parse(raw.trim()) }
      .getOrElse {
        throw ValidationException("INVALID_DATE", "Date must be in YYYY-MM-DD format (Asia/Kolkata).")
      }

  private fun parseLocalTime(raw: String, field: String): LocalTime =
    runCatching { LocalTime.parse(raw.trim()) }
      .getOrElse {
        throw ValidationException("INVALID_TIME", "$field must be in HH:mm format (Asia/Kolkata).")
      }

  private fun ResultSet.toScheduledSlotDto(now: Instant): ScheduledSlotDto = buildDto(
    id = getObject("id", UUID::class.java),
    slotDate = getObject("slot_date", LocalDate::class.java),
    fulfillmentType = FulfillmentType.valueOf(getString("fulfillment_type")),
    startTime = getObject("start_time_ist", LocalTime::class.java),
    endTime = getObject("end_time_ist", LocalTime::class.java),
    cutoffAt = getObject("cutoff_at", OffsetDateTime::class.java).toInstant(),
    maxCapacity = getInt("max_capacity"),
    reservedCount = getInt("reserved_count"),
    isActive = getBoolean("is_active"),
    now = now,
  )

  private fun buildDto(
    id: UUID,
    slotDate: LocalDate,
    fulfillmentType: FulfillmentType,
    startTime: LocalTime,
    endTime: LocalTime,
    cutoffAt: Instant,
    maxCapacity: Int,
    reservedCount: Int,
    isActive: Boolean,
    now: Instant,
  ): ScheduledSlotDto {
    val remaining = (maxCapacity - reservedCount).coerceAtLeast(0)
    val cutoffPassed = !cutoffAt.isAfter(now)
    val isBookable = isActive && !cutoffPassed && remaining > 0
    val reason = when {
      !isActive -> "Slot deactivated"
      cutoffPassed -> "Booking cutoff passed"
      remaining <= 0 -> "Slot full"
      else -> null
    }
    return ScheduledSlotDto(
      id = id.toString(),
      slotDate = slotDate.toString(),
      fulfillmentType = fulfillmentType,
      startTimeIst = TIME_FMT.format(startTime),
      endTimeIst = TIME_FMT.format(endTime),
      cutoffAt = cutoffAt.toString(),
      maxCapacity = maxCapacity,
      reservedCount = reservedCount,
      remainingCapacity = remaining,
      isActive = isActive,
      isBookable = isBookable,
      unavailableReason = reason,
    )
  }

  private data class SlotRow(
    val id: UUID,
    val slotDate: LocalDate,
    val fulfillmentType: FulfillmentType,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val cutoffAt: Instant,
    val maxCapacity: Int,
    val reservedCount: Int,
    val isActive: Boolean,
  )

  companion object {
    val IST_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    const val DEFAULT_CUTOFF_MINUTES = 30
  }
}

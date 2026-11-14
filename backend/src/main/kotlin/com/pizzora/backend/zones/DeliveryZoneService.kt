package com.pizzora.backend.zones

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ConflictException
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.CreateDeliveryZoneRequest
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.DeliveryZoneDto
import com.pizzora.contracts.UpdateDeliveryZoneRequest
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DeliveryZoneService(
  private val dataSource: DataSource,
) {
  suspend fun checkPincode(rawPincode: String?): DeliveryZoneCheckResponse {
    val pincode = rawPincode?.trim().orEmpty()
    if (!PINCODE_REGEX.matches(pincode)) {
      throw ValidationException("INVALID_PINCODE", "Pincode must be a valid 6-digit Indian postal code.")
    }
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val zone = conn.prepareStatement(
        """
        SELECT id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active
        FROM delivery_zones
        WHERE pincode = ? AND is_active = TRUE
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, pincode)
        ps.executeQuery().use { rs -> if (rs.next()) rs.toDeliveryZoneDto() else null }
      }
      if (zone != null) {
        DeliveryZoneCheckResponse(
          pincode = pincode,
          isServiceable = true,
          zone = zone,
          message = "Delivery available in ${zone.areaName} (${zone.pincode}).",
        )
      } else {
        DeliveryZoneCheckResponse(
          pincode = pincode,
          isServiceable = false,
          zone = null,
          message = "Delivery is not available for pincode $pincode.",
        )
      }
    }
  }

  suspend fun listAllZones(): List<DeliveryZoneDto> =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        SELECT id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active
        FROM delivery_zones
        ORDER BY is_active DESC, pincode ASC
        """.trimIndent(),
      ).use { ps ->
        ps.executeQuery().use { rs ->
          val list = mutableListOf<DeliveryZoneDto>()
          while (rs.next()) list += rs.toDeliveryZoneDto()
          list
        }
      }
    }

  suspend fun createZone(
    request: CreateDeliveryZoneRequest,
    audit: AuditContext,
  ): DeliveryZoneDto {
    val pincode = request.pincode.trim()
    val areaName = request.areaName.trim()
    if (!PINCODE_REGEX.matches(pincode)) {
      throw ValidationException("INVALID_PINCODE", "Pincode must be a valid 6-digit Indian postal code.")
    }
    if (areaName.isEmpty()) {
      throw ValidationException("INVALID_AREA_NAME", "Area name cannot be blank.")
    }
    if (request.minOrderPaise < 0L) {
      throw ValidationException("INVALID_MIN_ORDER", "Minimum order cannot be negative.")
    }
    if (request.deliveryFeePaise < 0L) {
      throw ValidationException("INVALID_DELIVERY_FEE", "Delivery fee cannot be negative.")
    }
    if (request.estimatedMins <= 0) {
      throw ValidationException("INVALID_ESTIMATED_MINS", "Estimated delivery minutes must be greater than zero.")
    }

    val zoneId = UUID.randomUUID()
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      try {
        conn.prepareStatement(
          """
          INSERT INTO delivery_zones (
            id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active
          ) VALUES (?, ?, ?, ?, ?, ?, ?)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, zoneId)
          ps.setString(2, pincode)
          ps.setString(3, areaName)
          ps.setLong(4, request.minOrderPaise)
          ps.setLong(5, request.deliveryFeePaise)
          ps.setInt(6, request.estimatedMins)
          ps.setBoolean(7, request.isActive)
          ps.executeUpdate()
        }
      } catch (ex: SQLException) {
        if (ex.sqlState == "23505") {
          throw ConflictException("DELIVERY_ZONE_EXISTS", "A delivery zone for pincode $pincode already exists.")
        }
        throw ex
      }

      val created = DeliveryZoneDto(
        id = zoneId.toString(),
        pincode = pincode,
        areaName = areaName,
        minOrderPaise = request.minOrderPaise,
        deliveryFeePaise = request.deliveryFeePaise,
        estimatedMins = request.estimatedMins,
        isActive = request.isActive,
      )
      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "DELIVERY_ZONE_CREATED",
        entityType = "delivery_zone",
        entityId = created.id,
        afterJson = buildJsonObject {
          put("pincode", created.pincode)
          put("area_name", created.areaName)
          put("min_order_paise", created.minOrderPaise)
          put("delivery_fee_paise", created.deliveryFeePaise)
          put("estimated_mins", created.estimatedMins)
          put("is_active", created.isActive)
        }.toString(),
      )
      created
    }
  }

  suspend fun updateZone(
    zoneId: UUID,
    request: UpdateDeliveryZoneRequest,
    audit: AuditContext,
  ): DeliveryZoneDto {
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val existing = conn.prepareStatement(
        """
        SELECT id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active
        FROM delivery_zones
        WHERE id = ?
        FOR UPDATE
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, zoneId)
        ps.executeQuery().use { rs ->
          if (!rs.next()) throw NotFoundException("DELIVERY_ZONE_NOT_FOUND", "Delivery zone not found.")
          rs.toDeliveryZoneDto()
        }
      }

      val nextArea = request.areaName?.trim() ?: existing.areaName
      if (nextArea.isEmpty()) {
        throw ValidationException("INVALID_AREA_NAME", "Area name cannot be blank.")
      }
      val nextMinOrder = request.minOrderPaise ?: existing.minOrderPaise
      if (nextMinOrder < 0L) {
        throw ValidationException("INVALID_MIN_ORDER", "Minimum order cannot be negative.")
      }
      val nextFee = request.deliveryFeePaise ?: existing.deliveryFeePaise
      if (nextFee < 0L) {
        throw ValidationException("INVALID_DELIVERY_FEE", "Delivery fee cannot be negative.")
      }
      val nextEta = request.estimatedMins ?: existing.estimatedMins
      if (nextEta <= 0) {
        throw ValidationException("INVALID_ESTIMATED_MINS", "Estimated delivery minutes must be greater than zero.")
      }
      val nextActive = request.isActive ?: existing.isActive

      conn.prepareStatement(
        """
        UPDATE delivery_zones
        SET area_name = ?, min_order_paise = ?, delivery_fee_paise = ?, estimated_mins = ?, is_active = ?
        WHERE id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, nextArea)
        ps.setLong(2, nextMinOrder)
        ps.setLong(3, nextFee)
        ps.setInt(4, nextEta)
        ps.setBoolean(5, nextActive)
        ps.setObject(6, zoneId)
        ps.executeUpdate()
      }

      val updated = existing.copy(
        areaName = nextArea,
        minOrderPaise = nextMinOrder,
        deliveryFeePaise = nextFee,
        estimatedMins = nextEta,
        isActive = nextActive,
      )
      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "DELIVERY_ZONE_UPDATED",
        entityType = "delivery_zone",
        entityId = updated.id,
        beforeJson = buildJsonObject {
          put("area_name", existing.areaName)
          put("min_order_paise", existing.minOrderPaise)
          put("delivery_fee_paise", existing.deliveryFeePaise)
          put("estimated_mins", existing.estimatedMins)
          put("is_active", existing.isActive)
        }.toString(),
        afterJson = buildJsonObject {
          put("area_name", updated.areaName)
          put("min_order_paise", updated.minOrderPaise)
          put("delivery_fee_paise", updated.deliveryFeePaise)
          put("estimated_mins", updated.estimatedMins)
          put("is_active", updated.isActive)
        }.toString(),
      )
      updated
    }
  }

  suspend fun deleteZone(
    zoneId: UUID,
    audit: AuditContext,
  ) {
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val existing = conn.prepareStatement(
        """
        SELECT id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active
        FROM delivery_zones
        WHERE id = ?
        FOR UPDATE
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, zoneId)
        ps.executeQuery().use { rs ->
          if (!rs.next()) throw NotFoundException("DELIVERY_ZONE_NOT_FOUND", "Delivery zone not found.")
          rs.toDeliveryZoneDto()
        }
      }

      conn.prepareStatement("DELETE FROM delivery_zones WHERE id = ?").use { ps ->
        ps.setObject(1, zoneId)
        ps.executeUpdate()
      }

      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "DELIVERY_ZONE_DELETED",
        entityType = "delivery_zone",
        entityId = existing.id,
        beforeJson = buildJsonObject {
          put("pincode", existing.pincode)
          put("area_name", existing.areaName)
        }.toString(),
      )
    }
  }

  private fun ResultSet.toDeliveryZoneDto(): DeliveryZoneDto = DeliveryZoneDto(
    id = getObject("id", UUID::class.java).toString(),
    pincode = getString("pincode"),
    areaName = getString("area_name"),
    minOrderPaise = getLong("min_order_paise"),
    deliveryFeePaise = getLong("delivery_fee_paise"),
    estimatedMins = getInt("estimated_mins"),
    isActive = getBoolean("is_active"),
  )

  companion object {
    val PINCODE_REGEX = Regex("^[1-9][0-9]{5}$")
  }
}

package com.pizzora.backend.inventory

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ConflictException
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.core.error.UnprocessableEntityException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.AdjustInventoryRequest
import com.pizzora.contracts.CreateIngredientRequest
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.InventoryLedgerEntryDto
import com.pizzora.contracts.InventoryLedgerReason
import com.pizzora.contracts.UpdateIngredientRequest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json

class InventoryService(
  private val dataSource: DataSource,
) {
  private val json = Json { encodeDefaults = true }

  suspend fun listInventory(): List<InventoryItemDto> =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        SELECT i.id AS ingredient_id, i.name, i.unit, i.low_stock_threshold, i.is_active,
               inv.on_hand_qty, inv.reserved_qty, inv.available_qty, inv.updated_at
        FROM ingredients i
        JOIN inventory inv ON inv.ingredient_id = i.id
        ORDER BY i.name ASC
        """.trimIndent(),
      ).use { ps ->
        ps.executeQuery().use { rs ->
          val list = mutableListOf<InventoryItemDto>()
          while (rs.next()) list += rs.toInventoryItemDto()
          list
        }
      }
    }

  suspend fun createIngredient(
    request: CreateIngredientRequest,
    audit: AuditContext,
  ): InventoryItemDto {
    val normalizedName = request.name.trim()
    if (normalizedName.isEmpty() || normalizedName.length > 100) {
      throw ValidationException("INVALID_INGREDIENT_NAME", "Ingredient name must be 1..100 characters.")
    }
    if (request.lowStockThreshold < 0L) {
      throw ValidationException("INVALID_THRESHOLD", "low_stock_threshold must be non-negative.")
    }
    if (request.initialStockQty < 0L) {
      throw ValidationException("INVALID_INITIAL_STOCK", "initial_stock_qty must be non-negative.")
    }

    val ingredientId = UUID.randomUUID()
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      try {
        conn.prepareStatement(
          """
          INSERT INTO ingredients (id, name, unit, low_stock_threshold, is_active)
          VALUES (?, ?, ?, ?, ?)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, ingredientId)
          ps.setString(2, normalizedName)
          ps.setString(3, request.unit.name)
          ps.setLong(4, request.lowStockThreshold)
          ps.setBoolean(5, request.isActive)
          ps.executeUpdate()
        }
      } catch (ex: SQLException) {
        if (ex.sqlState == "23505") {
          throw ConflictException("INGREDIENT_EXISTS", "Ingredient '$normalizedName' already exists.")
        }
        throw ex
      }

      conn.prepareStatement(
        """
        INSERT INTO inventory (ingredient_id, on_hand_qty, reserved_qty)
        VALUES (?, ?, 0)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, ingredientId)
        ps.setLong(2, request.initialStockQty)
        ps.executeUpdate()
      }

      if (request.initialStockQty > 0L) {
        insertLedgerIdempotent(
          conn = conn,
          ingredientId = ingredientId,
          orderId = null,
          operationId = "initial:$ingredientId",
          deltaOnHand = request.initialStockQty,
          deltaReserved = 0L,
          reason = InventoryLedgerReason.RESTOCK,
          actorUserId = audit.actorUserId,
        )
      }

      val item = loadSingleInventoryItem(conn, ingredientId)
        ?: throw NotFoundException("INGREDIENT_NOT_FOUND", "Ingredient $ingredientId not found.")

      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "INGREDIENT_CREATED",
        entityType = "ingredient",
        entityId = ingredientId.toString(),
        afterJson = json.encodeToString(item),
      )
      item
    }
  }

  suspend fun updateIngredient(
    ingredientId: UUID,
    request: UpdateIngredientRequest,
    audit: AuditContext,
  ): InventoryItemDto =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val current = loadSingleInventoryItem(conn, ingredientId)
        ?: throw NotFoundException("INGREDIENT_NOT_FOUND", "Ingredient $ingredientId not found.")

      val newName = request.name?.trim()?.also {
        if (it.isEmpty() || it.length > 100) {
          throw ValidationException("INVALID_INGREDIENT_NAME", "Ingredient name must be 1..100 characters.")
        }
      } ?: current.name
      val newUnit = request.unit ?: current.unit
      val newThreshold = request.lowStockThreshold?.also {
        if (it < 0L) throw ValidationException("INVALID_THRESHOLD", "low_stock_threshold must be non-negative.")
      } ?: current.lowStockThreshold
      val newActive = request.isActive ?: current.isActive

      try {
        conn.prepareStatement(
          """
          UPDATE ingredients
          SET name = ?, unit = ?, low_stock_threshold = ?, is_active = ?
          WHERE id = ?
          """.trimIndent(),
        ).use { ps ->
          ps.setString(1, newName)
          ps.setString(2, newUnit.name)
          ps.setLong(3, newThreshold)
          ps.setBoolean(4, newActive)
          ps.setObject(5, ingredientId)
          ps.executeUpdate()
        }
      } catch (ex: SQLException) {
        if (ex.sqlState == "23505") {
          throw ConflictException("INGREDIENT_EXISTS", "Ingredient '$newName' already exists.")
        }
        throw ex
      }

      val updated = loadSingleInventoryItem(conn, ingredientId)!!
      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "INGREDIENT_UPDATED",
        entityType = "ingredient",
        entityId = ingredientId.toString(),
        beforeJson = json.encodeToString(current),
        afterJson = json.encodeToString(updated),
      )
      updated
    }

  suspend fun adjustInventory(
    request: AdjustInventoryRequest,
    audit: AuditContext,
  ): InventoryItemDto {
    val ingredientId = parseUuid(request.ingredientId, "ingredient_id")
    val operationId = request.operationId.trim()
    if (operationId.isEmpty() || operationId.length > 128) {
      throw ValidationException("INVALID_OPERATION_ID", "operation_id must be 1..128 characters.")
    }
    if (request.reason != InventoryLedgerReason.RESTOCK && request.reason != InventoryLedgerReason.MANUAL_ADJUST) {
      throw ValidationException("INVALID_ADJUST_REASON", "reason must be RESTOCK or MANUAL_ADJUST.")
    }
    if (request.reason == InventoryLedgerReason.RESTOCK && request.deltaQty <= 0L) {
      throw ValidationException("INVALID_DELTA_QTY", "RESTOCK delta_qty must be positive.")
    }
    if (request.deltaQty == 0L) {
      throw ValidationException("INVALID_DELTA_QTY", "delta_qty must be non-zero.")
    }

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val locked = conn.prepareStatement(
        """
        SELECT i.id, i.name, inv.on_hand_qty, inv.reserved_qty
        FROM ingredients i
        JOIN inventory inv ON inv.ingredient_id = i.id
        WHERE i.id = ?
        FOR UPDATE OF inv
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, ingredientId)
        ps.executeQuery().use { rs ->
          if (!rs.next()) {
            throw NotFoundException("INGREDIENT_NOT_FOUND", "Ingredient $ingredientId not found.")
          }
          LockedStockRow(
            ingredientId = rs.getObject("id", UUID::class.java),
            name = rs.getString("name"),
            onHandQty = rs.getLong("on_hand_qty"),
            reservedQty = rs.getLong("reserved_qty"),
            isActive = true,
          )
        }
      }

      // Idempotent ledger guard: insert first ON CONFLICT DO NOTHING before mutating stock
      val inserted = insertLedgerIdempotent(
        conn = conn,
        ingredientId = ingredientId,
        orderId = null,
        operationId = operationId,
        deltaOnHand = request.deltaQty,
        deltaReserved = 0L,
        reason = request.reason,
        actorUserId = audit.actorUserId,
      )

      if (!inserted) {
        val existing = loadLedgerByOperationId(conn, operationId)
        if (
          existing != null &&
          (existing.ingredientId != ingredientId.toString() ||
            existing.deltaOnHand != request.deltaQty ||
            existing.reason != request.reason)
        ) {
          throw ConflictException(
            "OPERATION_ID_CONFLICT",
            "operation_id '$operationId' was already used for a different inventory operation.",
          )
        }
        return@withJdbcTransaction loadSingleInventoryItem(conn, ingredientId)!!
      }

      val newOnHand = Math.addExact(locked.onHandQty, request.deltaQty)
      if (newOnHand < 0L || newOnHand < locked.reservedQty) {
        throw UnprocessableEntityException(
          "INSUFFICIENT_AVAILABLE_STOCK",
          "Cannot set ${locked.name} on_hand_qty ($newOnHand) below reserved_qty (${locked.reservedQty}).",
        )
      }

      conn.prepareStatement(
        "UPDATE inventory SET on_hand_qty = ?, updated_at = NOW() WHERE ingredient_id = ?",
      ).use { ps ->
        ps.setLong(1, newOnHand)
        ps.setObject(2, ingredientId)
        ps.executeUpdate()
      }

      val updated = loadSingleInventoryItem(conn, ingredientId)!!
      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "INVENTORY_ADJUSTED",
        entityType = "inventory",
        entityId = ingredientId.toString(),
        beforeJson = "{\"on_hand_qty\":${locked.onHandQty},\"reserved_qty\":${locked.reservedQty}}",
        afterJson = "{\"on_hand_qty\":${updated.onHandQty},\"reserved_qty\":${updated.reservedQty},\"operation_id\":\"$operationId\"}",
      )
      updated
    }
  }

  suspend fun listLedger(
    ingredientId: UUID? = null,
    limit: Int = 50,
  ): List<InventoryLedgerEntryDto> {
    val clampedLimit = limit.coerceIn(1, 200)
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val sql = if (ingredientId != null) {
        """
        SELECT l.id, l.ingredient_id, i.name AS ingredient_name, i.unit,
               l.order_id, l.operation_id, l.delta_on_hand, l.delta_reserved,
               l.reason, l.actor_user_id, l.created_at
        FROM inventory_ledger l
        JOIN ingredients i ON i.id = l.ingredient_id
        WHERE l.ingredient_id = ?
        ORDER BY l.created_at DESC, l.id DESC
        LIMIT ?
        """.trimIndent()
      } else {
        """
        SELECT l.id, l.ingredient_id, i.name AS ingredient_name, i.unit,
               l.order_id, l.operation_id, l.delta_on_hand, l.delta_reserved,
               l.reason, l.actor_user_id, l.created_at
        FROM inventory_ledger l
        JOIN ingredients i ON i.id = l.ingredient_id
        ORDER BY l.created_at DESC, l.id DESC
        LIMIT ?
        """.trimIndent()
      }

      conn.prepareStatement(sql).use { ps ->
        if (ingredientId != null) {
          ps.setObject(1, ingredientId)
          ps.setInt(2, clampedLimit)
        } else {
          ps.setInt(1, clampedLimit)
        }
        ps.executeQuery().use { rs ->
          val list = mutableListOf<InventoryLedgerEntryDto>()
          while (rs.next()) list += rs.toLedgerDto()
          list
        }
      }
    }
  }

  /**
   * Atomically reserves or immediately commits (for COD) ingredient quantities for [orderId].
   * Acquires PostgreSQL row locks strictly in ascending ingredient UUID string order to guarantee zero deadlocks.
   */
  fun reserveForOrder(
    conn: Connection,
    orderId: UUID,
    requiredByIngredient: Map<UUID, Long>,
    expiresAt: Instant?,
    immediateCommit: Boolean,
    actorUserId: UUID? = null,
  ) {
    if (requiredByIngredient.isEmpty()) return

    val sortedRequirements = requiredByIngredient.entries
      .filter { it.value > 0L }
      .sortedBy { it.key.toString() }
    if (sortedRequirements.isEmpty()) return

    val sortedIds = sortedRequirements.map { it.key }
    val lockedMap = lockInventoryRowsOrdered(conn, sortedIds)

    // Verify all ingredients exist, are active, and have sufficient available stock
    for ((ingredientId, requiredQty) in sortedRequirements) {
      val stock = lockedMap[ingredientId]
        ?: throw UnprocessableEntityException(
          "INGREDIENT_NOT_FOUND",
          "Ingredient $ingredientId is missing from inventory.",
        )
      if (!stock.isActive) {
        throw UnprocessableEntityException(
          "INGREDIENT_UNAVAILABLE",
          "Ingredient '${stock.name}' is currently inactive.",
        )
      }
      val availableQty = stock.onHandQty - stock.reservedQty
      if (availableQty < requiredQty) {
        throw UnprocessableEntityException(
          "INSUFFICIENT_INVENTORY",
          "Insufficient stock for '${stock.name}'. Required $requiredQty, available $availableQty.",
          details = mapOf(
            "ingredient_id" to ingredientId.toString(),
            "ingredient_name" to stock.name,
            "required" to requiredQty.toString(),
            "available" to availableQty.toString(),
          ),
        )
      }
    }

    val reason = if (immediateCommit) InventoryLedgerReason.COMMIT else InventoryLedgerReason.RESERVE
    val reservationStatus = if (immediateCommit) "COMMITTED" else "ACTIVE"
    val opPrefix = if (immediateCommit) "commit" else "reserve"

    for ((ingredientId, requiredQty) in sortedRequirements) {
      val operationId = "$opPrefix:$orderId:$ingredientId"
      val deltaOnHand = if (immediateCommit) -requiredQty else 0L
      val deltaReserved = if (immediateCommit) 0L else requiredQty

      val inserted = insertLedgerIdempotent(
        conn = conn,
        ingredientId = ingredientId,
        orderId = orderId,
        operationId = operationId,
        deltaOnHand = deltaOnHand,
        deltaReserved = deltaReserved,
        reason = reason,
        actorUserId = actorUserId,
      )

      if (inserted) {
        if (immediateCommit) {
          conn.prepareStatement(
            "UPDATE inventory SET on_hand_qty = on_hand_qty - ?, updated_at = NOW() WHERE ingredient_id = ?",
          ).use { ps ->
            ps.setLong(1, requiredQty)
            ps.setObject(2, ingredientId)
            ps.executeUpdate()
          }
        } else {
          conn.prepareStatement(
            "UPDATE inventory SET reserved_qty = reserved_qty + ?, updated_at = NOW() WHERE ingredient_id = ?",
          ).use { ps ->
            ps.setLong(1, requiredQty)
            ps.setObject(2, ingredientId)
            ps.executeUpdate()
          }
        }

        conn.prepareStatement(
          """
          INSERT INTO inventory_reservations (id, order_id, ingredient_id, quantity, status, expires_at)
          VALUES (?, ?, ?, ?, ?, ?)
          ON CONFLICT (order_id, ingredient_id) DO NOTHING
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, UUID.randomUUID())
          ps.setObject(2, orderId)
          ps.setObject(3, ingredientId)
          ps.setLong(4, requiredQty)
          ps.setString(5, reservationStatus)
          if (!immediateCommit && expiresAt != null) {
            ps.setTimestamp(6, Timestamp.from(expiresAt))
          } else {
            ps.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE)
          }
          ps.executeUpdate()
        }
      }
    }
  }

  /**
   * Commits active reservations for [orderId] upon payment capture (deducts both on_hand_qty and reserved_qty).
   */
  fun commitReservedInventory(
    conn: Connection,
    orderId: UUID,
    actorUserId: UUID? = null,
  ) {
    val activeRows = loadOrderReservationsForUpdate(conn, orderId, status = "ACTIVE")
    if (activeRows.isEmpty()) return

    lockInventoryRowsOrdered(conn, activeRows.map { it.ingredientId })

    for (row in activeRows) {
      val updatedRes = conn.prepareStatement(
        """
        UPDATE inventory_reservations
        SET status = 'COMMITTED', expires_at = NULL, updated_at = NOW()
        WHERE order_id = ? AND ingredient_id = ? AND status = 'ACTIVE'
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, orderId)
        ps.setObject(2, row.ingredientId)
        ps.executeUpdate()
      }
      if (updatedRes == 0) continue

      val inserted = insertLedgerIdempotent(
        conn = conn,
        ingredientId = row.ingredientId,
        orderId = orderId,
        operationId = "commit:$orderId:${row.ingredientId}",
        deltaOnHand = -row.quantity,
        deltaReserved = -row.quantity,
        reason = InventoryLedgerReason.COMMIT,
        actorUserId = actorUserId,
      )
      if (inserted) {
        conn.prepareStatement(
          """
          UPDATE inventory
          SET on_hand_qty = on_hand_qty - ?,
              reserved_qty = reserved_qty - ?,
              updated_at = NOW()
          WHERE ingredient_id = ?
          """.trimIndent(),
        ).use { ps ->
          ps.setLong(1, row.quantity)
          ps.setLong(2, row.quantity)
          ps.setObject(3, row.ingredientId)
          ps.executeUpdate()
        }
      }
    }
  }

  /**
   * Releases active unpaid reservations (cancelled before payment or expired after payment window).
   */
  fun releaseReservedInventory(
    conn: Connection,
    orderId: UUID,
    expired: Boolean = false,
    actorUserId: UUID? = null,
  ) {
    val activeRows = loadOrderReservationsForUpdate(conn, orderId, status = "ACTIVE")
    if (activeRows.isEmpty()) return

    lockInventoryRowsOrdered(conn, activeRows.map { it.ingredientId })

    val targetStatus = if (expired) "EXPIRED" else "RELEASED"
    val reason = if (expired) InventoryLedgerReason.EXPIRE_RELEASE else InventoryLedgerReason.RELEASE
    val prefix = if (expired) "expire_release" else "release"

    for (row in activeRows) {
      val updatedRes = conn.prepareStatement(
        """
        UPDATE inventory_reservations
        SET status = ?, expires_at = NULL, updated_at = NOW()
        WHERE order_id = ? AND ingredient_id = ? AND status = 'ACTIVE'
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, targetStatus)
        ps.setObject(2, orderId)
        ps.setObject(3, row.ingredientId)
        ps.executeUpdate()
      }
      if (updatedRes == 0) continue

      val inserted = insertLedgerIdempotent(
        conn = conn,
        ingredientId = row.ingredientId,
        orderId = orderId,
        operationId = "$prefix:$orderId:${row.ingredientId}",
        deltaOnHand = 0L,
        deltaReserved = -row.quantity,
        reason = reason,
        actorUserId = actorUserId,
      )
      if (inserted) {
        conn.prepareStatement(
          "UPDATE inventory SET reserved_qty = reserved_qty - ?, updated_at = NOW() WHERE ingredient_id = ?",
        ).use { ps ->
          ps.setLong(1, row.quantity)
          ps.setObject(2, row.ingredientId)
          ps.executeUpdate()
        }
      }
    }
  }

  /**
   * Restores physical on_hand_qty when a placed/accepted order is cancelled prior to kitchen preparation.
   */
  fun restoreCommittedInventory(
    conn: Connection,
    orderId: UUID,
    actorUserId: UUID? = null,
  ) {
    val committedRows = loadOrderReservationsForUpdate(conn, orderId, status = "COMMITTED")
    if (committedRows.isEmpty()) return

    lockInventoryRowsOrdered(conn, committedRows.map { it.ingredientId })

    for (row in committedRows) {
      val updatedRes = conn.prepareStatement(
        """
        UPDATE inventory_reservations
        SET status = 'RESTORED', updated_at = NOW()
        WHERE order_id = ? AND ingredient_id = ? AND status = 'COMMITTED'
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, orderId)
        ps.setObject(2, row.ingredientId)
        ps.executeUpdate()
      }
      if (updatedRes == 0) continue

      val inserted = insertLedgerIdempotent(
        conn = conn,
        ingredientId = row.ingredientId,
        orderId = orderId,
        operationId = "cancel_restore:$orderId:${row.ingredientId}",
        deltaOnHand = row.quantity,
        deltaReserved = 0L,
        reason = InventoryLedgerReason.CANCEL_RESTORE,
        actorUserId = actorUserId,
      )
      if (inserted) {
        conn.prepareStatement(
          "UPDATE inventory SET on_hand_qty = on_hand_qty + ?, updated_at = NOW() WHERE ingredient_id = ?",
        ).use { ps ->
          ps.setLong(1, row.quantity)
          ps.setObject(2, row.ingredientId)
          ps.executeUpdate()
        }
      }
    }
  }

  private fun lockInventoryRowsOrdered(
    conn: Connection,
    sortedIds: List<UUID>,
  ): Map<UUID, LockedStockRow> {
    if (sortedIds.isEmpty()) return emptyMap()
    val uuidArray = conn.createArrayOf("uuid", sortedIds.toTypedArray())
    val map = linkedMapOf<UUID, LockedStockRow>()
    conn.prepareStatement(
      """
      SELECT inv.ingredient_id, i.name, i.is_active, inv.on_hand_qty, inv.reserved_qty
      FROM inventory inv
      JOIN ingredients i ON i.id = inv.ingredient_id
      WHERE inv.ingredient_id = ANY(?)
      ORDER BY inv.ingredient_id ASC
      FOR UPDATE OF inv
      """.trimIndent(),
    ).use { ps ->
      ps.setArray(1, uuidArray)
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val id = rs.getObject("ingredient_id", UUID::class.java)
          map[id] = LockedStockRow(
            ingredientId = id,
            name = rs.getString("name"),
            onHandQty = rs.getLong("on_hand_qty"),
            reservedQty = rs.getLong("reserved_qty"),
            isActive = rs.getBoolean("is_active"),
          )
        }
      }
    }
    return map
  }

  private fun loadOrderReservationsForUpdate(
    conn: Connection,
    orderId: UUID,
    status: String,
  ): List<OrderReservationRow> =
    conn.prepareStatement(
      """
      SELECT ingredient_id, quantity
      FROM inventory_reservations
      WHERE order_id = ? AND status = ?
      ORDER BY ingredient_id ASC
      FOR UPDATE
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, orderId)
      ps.setString(2, status)
      ps.executeQuery().use { rs ->
        val list = mutableListOf<OrderReservationRow>()
        while (rs.next()) {
          list += OrderReservationRow(
            ingredientId = rs.getObject("ingredient_id", UUID::class.java),
            quantity = rs.getLong("quantity"),
          )
        }
        list
      }
    }

  private fun insertLedgerIdempotent(
    conn: Connection,
    ingredientId: UUID,
    orderId: UUID?,
    operationId: String,
    deltaOnHand: Long,
    deltaReserved: Long,
    reason: InventoryLedgerReason,
    actorUserId: UUID?,
  ): Boolean =
    conn.prepareStatement(
      """
      INSERT INTO inventory_ledger (
        id, ingredient_id, order_id, operation_id, delta_on_hand, delta_reserved, reason, actor_user_id
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (operation_id) DO NOTHING
      RETURNING id
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, UUID.randomUUID())
      ps.setObject(2, ingredientId)
      if (orderId != null) ps.setObject(3, orderId) else ps.setNull(3, Types.OTHER)
      ps.setString(4, operationId)
      ps.setLong(5, deltaOnHand)
      ps.setLong(6, deltaReserved)
      ps.setString(7, reason.name)
      if (actorUserId != null) ps.setObject(8, actorUserId) else ps.setNull(8, Types.OTHER)
      ps.executeQuery().use { rs -> rs.next() }
    }

  private fun loadLedgerByOperationId(
    conn: Connection,
    operationId: String,
  ): InventoryLedgerEntryDto? =
    conn.prepareStatement(
      """
      SELECT l.id, l.ingredient_id, i.name AS ingredient_name, i.unit,
             l.order_id, l.operation_id, l.delta_on_hand, l.delta_reserved,
             l.reason, l.actor_user_id, l.created_at
      FROM inventory_ledger l
      JOIN ingredients i ON i.id = l.ingredient_id
      WHERE l.operation_id = ?
      """.trimIndent(),
    ).use { ps ->
      ps.setString(1, operationId)
      ps.executeQuery().use { rs -> if (rs.next()) rs.toLedgerDto() else null }
    }

  private fun loadSingleInventoryItem(conn: Connection, ingredientId: UUID): InventoryItemDto? =
    conn.prepareStatement(
      """
      SELECT i.id AS ingredient_id, i.name, i.unit, i.low_stock_threshold, i.is_active,
             inv.on_hand_qty, inv.reserved_qty, inv.available_qty, inv.updated_at
      FROM ingredients i
      JOIN inventory inv ON inv.ingredient_id = i.id
      WHERE i.id = ?
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, ingredientId)
      ps.executeQuery().use { rs -> if (rs.next()) rs.toInventoryItemDto() else null }
    }

  private fun ResultSet.toInventoryItemDto(): InventoryItemDto {
    val available = getLong("available_qty")
    val threshold = getLong("low_stock_threshold")
    return InventoryItemDto(
      ingredientId = getObject("ingredient_id", UUID::class.java).toString(),
      name = getString("name"),
      unit = IngredientUnit.valueOf(getString("unit")),
      lowStockThreshold = threshold,
      isActive = getBoolean("is_active"),
      onHandQty = getLong("on_hand_qty"),
      reservedQty = getLong("reserved_qty"),
      availableQty = available,
      isLowStock = available <= threshold,
      updatedAt = getObject("updated_at", OffsetDateTime::class.java).toInstant().toString(),
    )
  }

  private fun ResultSet.toLedgerDto(): InventoryLedgerEntryDto =
    InventoryLedgerEntryDto(
      id = getObject("id", UUID::class.java).toString(),
      ingredientId = getObject("ingredient_id", UUID::class.java).toString(),
      ingredientName = getString("ingredient_name"),
      unit = IngredientUnit.valueOf(getString("unit")),
      orderId = getObject("order_id", UUID::class.java)?.toString(),
      operationId = getString("operation_id"),
      deltaOnHand = getLong("delta_on_hand"),
      deltaReserved = getLong("delta_reserved"),
      reason = InventoryLedgerReason.valueOf(getString("reason")),
      actorUserId = getObject("actor_user_id", UUID::class.java)?.toString(),
      createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
    )

  private fun parseUuid(raw: String, field: String): UUID =
    runCatching { UUID.fromString(raw) }
      .getOrElse { throw ValidationException("INVALID_UUID", "Invalid $field UUID: $raw") }

  private data class LockedStockRow(
    val ingredientId: UUID,
    val name: String,
    val onHandQty: Long,
    val reservedQty: Long,
    val isActive: Boolean,
  )

  private data class OrderReservationRow(
    val ingredientId: UUID,
    val quantity: Long,
  )
}

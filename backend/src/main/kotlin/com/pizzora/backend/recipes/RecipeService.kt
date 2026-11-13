package com.pizzora.backend.recipes

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.PutRecipeRequest
import com.pizzora.contracts.RecipeBindingDto
import com.pizzora.contracts.RecipeIngredientLineDto
import com.pizzora.contracts.RecipeTargetType
import com.pizzora.contracts.RecipesOverviewResponse
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json

data class RecipeDemandItem(
  val variantId: UUID,
  val crustId: UUID,
  val toppingIds: List<UUID>,
  val quantity: Int,
)

class RecipeService(
  private val dataSource: DataSource,
) {
  private val json = Json { encodeDefaults = true }

  suspend fun listRecipes(): RecipesOverviewResponse =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val variants = loadBindings(conn, RecipeTargetType.VARIANT)
      val crusts = loadBindings(conn, RecipeTargetType.CRUST)
      val toppings = loadBindings(conn, RecipeTargetType.TOPPING)
      RecipesOverviewResponse(variants = variants, crusts = crusts, toppings = toppings)
    }

  suspend fun putRecipe(
    request: PutRecipeRequest,
    audit: AuditContext,
  ): RecipeBindingDto {
    val targetUuid = parseUuid(request.targetId, "target_id")
    if (request.ingredients.any { it.quantity <= 0 }) {
      throw ValidationException("INVALID_RECIPE_QUANTITY", "All recipe ingredient quantities must be > 0.")
    }
    val parsedLines = request.ingredients.map { line ->
      parseUuid(line.ingredientId, "ingredient_id") to line.quantity
    }
    if (parsedLines.map { it.first }.distinct().size != parsedLines.size) {
      throw ValidationException("DUPLICATE_RECIPE_INGREDIENT", "Each ingredient_id may only appear once per recipe.")
    }

    val spec = tableSpec(request.targetType)
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val targetLabel = loadTargetLabel(conn, request.targetType, targetUuid)
        ?: throw NotFoundException("RECIPE_TARGET_NOT_FOUND", "${request.targetType} $targetUuid was not found.")

      val before = loadSingleBinding(conn, request.targetType, targetUuid, targetLabel)

      // Validate all referenced ingredients exist
      if (parsedLines.isNotEmpty()) {
        val ingIds = parsedLines.map { it.first }
        val existingCount = conn.prepareStatement(
          "SELECT COUNT(*)::int FROM ingredients WHERE id = ANY(?)",
        ).use { ps ->
          ps.setArray(1, conn.createArrayOf("uuid", ingIds.toTypedArray()))
          ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        if (existingCount != ingIds.size) {
          throw NotFoundException("INGREDIENT_NOT_FOUND", "One or more ingredient_id values do not exist.")
        }
      }

      conn.prepareStatement(
        "DELETE FROM ${spec.table} WHERE ${spec.fkColumn} = ?",
      ).use { ps ->
        ps.setObject(1, targetUuid)
        ps.executeUpdate()
      }

      if (parsedLines.isNotEmpty()) {
        conn.prepareStatement(
          "INSERT INTO ${spec.table} (id, ${spec.fkColumn}, ingredient_id, quantity) VALUES (?, ?, ?, ?)",
        ).use { ps ->
          for ((ingredientId, qty) in parsedLines) {
            ps.setObject(1, UUID.randomUUID())
            ps.setObject(2, targetUuid)
            ps.setObject(3, ingredientId)
            ps.setInt(4, qty)
            ps.addBatch()
          }
          ps.executeBatch()
        }
      }

      val after = loadSingleBinding(conn, request.targetType, targetUuid, targetLabel)
      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "RECIPE_UPDATED",
        entityType = spec.auditEntityType,
        entityId = targetUuid.toString(),
        beforeJson = json.encodeToString(before),
        afterJson = json.encodeToString(after),
      )
      after
    }
  }

  /**
   * Expands configured variant + crust + topping recipes across cart/order items into a deterministically
   * sorted map of `ingredientId -> totalRequiredQty`.
   */
  fun computeRequiredIngredientsForItems(
    conn: Connection,
    items: List<RecipeDemandItem>,
  ): Map<UUID, Long> {
    if (items.isEmpty()) return emptyMap()

    val variantIds = items.map { it.variantId }.distinct()
    val crustIds = items.map { it.crustId }.distinct()
    val toppingIds = items.flatMap { it.toppingIds }.distinct()

    val variantRecipes = loadRecipeMap(conn, "variant_recipes", "variant_id", variantIds)
    val crustRecipes = loadRecipeMap(conn, "crust_recipes", "crust_id", crustIds)
    val toppingRecipes = if (toppingIds.isNotEmpty()) {
      loadRecipeMap(conn, "topping_recipes", "topping_id", toppingIds)
    } else {
      emptyMap()
    }

    val totals = mutableMapOf<UUID, Long>()
    for (item in items) {
      val qtyLong = item.quantity.toLong()
      variantRecipes[item.variantId]?.forEach { (ingId, perUnit) ->
        totals[ingId] = Math.addExact(totals[ingId] ?: 0L, Math.multiplyExact(perUnit.toLong(), qtyLong))
      }
      crustRecipes[item.crustId]?.forEach { (ingId, perUnit) ->
        totals[ingId] = Math.addExact(totals[ingId] ?: 0L, Math.multiplyExact(perUnit.toLong(), qtyLong))
      }
      item.toppingIds.forEach { tid ->
        toppingRecipes[tid]?.forEach { (ingId, perUnit) ->
          totals[ingId] = Math.addExact(totals[ingId] ?: 0L, Math.multiplyExact(perUnit.toLong(), qtyLong))
        }
      }
    }

    // Sort deterministically by UUID string order matching PostgreSQL uuid B-tree ordering
    val sorted = linkedMapOf<UUID, Long>()
    totals.entries.sortedBy { it.key.toString() }.forEach { (k, v) -> sorted[k] = v }
    return sorted
  }

  private fun loadRecipeMap(
    conn: Connection,
    table: String,
    fkColumn: String,
    ids: List<UUID>,
  ): Map<UUID, List<Pair<UUID, Int>>> {
    if (ids.isEmpty()) return emptyMap()
    val out = mutableMapOf<UUID, MutableList<Pair<UUID, Int>>>()
    conn.prepareStatement(
      "SELECT $fkColumn, ingredient_id, quantity FROM $table WHERE $fkColumn = ANY(?)",
    ).use { ps ->
      ps.setArray(1, conn.createArrayOf("uuid", ids.toTypedArray()))
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val targetId = rs.getObject(fkColumn, UUID::class.java)
          val ingredientId = rs.getObject("ingredient_id", UUID::class.java)
          val quantity = rs.getInt("quantity")
          out.getOrPut(targetId) { mutableListOf() } += (ingredientId to quantity)
        }
      }
    }
    return out
  }

  private fun loadBindings(conn: Connection, targetType: RecipeTargetType): List<RecipeBindingDto> {
    val sql = when (targetType) {
      RecipeTargetType.VARIANT ->
        """
        SELECT pv.id AS target_id, (p.name || ' (' || pv.size_code || ')') AS target_label,
               r.id AS line_id, r.ingredient_id, i.name AS ingredient_name, i.unit, r.quantity
        FROM product_variants pv
        JOIN products p ON p.id = pv.product_id
        LEFT JOIN variant_recipes r ON r.variant_id = pv.id
        LEFT JOIN ingredients i ON i.id = r.ingredient_id
        ORDER BY p.name ASC, pv.size_code ASC, i.name ASC
        """.trimIndent()
      RecipeTargetType.CRUST ->
        """
        SELECT c.id AS target_id, c.name AS target_label,
               r.id AS line_id, r.ingredient_id, i.name AS ingredient_name, i.unit, r.quantity
        FROM crust_options c
        LEFT JOIN crust_recipes r ON r.crust_id = c.id
        LEFT JOIN ingredients i ON i.id = r.ingredient_id
        ORDER BY c.name ASC, i.name ASC
        """.trimIndent()
      RecipeTargetType.TOPPING ->
        """
        SELECT t.id AS target_id, t.name AS target_label,
               r.id AS line_id, r.ingredient_id, i.name AS ingredient_name, i.unit, r.quantity
        FROM toppings t
        LEFT JOIN topping_recipes r ON r.topping_id = t.id
        LEFT JOIN ingredients i ON i.id = r.ingredient_id
        ORDER BY t.name ASC, i.name ASC
        """.trimIndent()
    }

    val grouped = linkedMapOf<String, Pair<String, MutableList<RecipeIngredientLineDto>>>()
    conn.prepareStatement(sql).use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val targetId = rs.getObject("target_id", UUID::class.java).toString()
          val targetLabel = rs.getString("target_label")
          val bucket = grouped.getOrPut(targetId) { targetLabel to mutableListOf() }
          val lineId = rs.getObject("line_id", UUID::class.java)
          if (lineId != null) {
            bucket.second += RecipeIngredientLineDto(
              id = lineId.toString(),
              ingredientId = rs.getObject("ingredient_id", UUID::class.java).toString(),
              ingredientName = rs.getString("ingredient_name"),
              unit = IngredientUnit.valueOf(rs.getString("unit")),
              quantity = rs.getInt("quantity"),
            )
          }
        }
      }
    }

    return grouped.map { (targetId, pair) ->
      RecipeBindingDto(
        targetType = targetType,
        targetId = targetId,
        targetLabel = pair.first,
        ingredients = pair.second,
      )
    }
  }

  private fun loadSingleBinding(
    conn: Connection,
    targetType: RecipeTargetType,
    targetId: UUID,
    targetLabel: String,
  ): RecipeBindingDto {
    val spec = tableSpec(targetType)
    val lines = conn.prepareStatement(
      """
      SELECT r.id AS line_id, r.ingredient_id, i.name AS ingredient_name, i.unit, r.quantity
      FROM ${spec.table} r
      JOIN ingredients i ON i.id = r.ingredient_id
      WHERE r.${spec.fkColumn} = ?
      ORDER BY i.name ASC
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, targetId)
      ps.executeQuery().use { rs ->
        val list = mutableListOf<RecipeIngredientLineDto>()
        while (rs.next()) {
          list += RecipeIngredientLineDto(
            id = rs.getObject("line_id", UUID::class.java).toString(),
            ingredientId = rs.getObject("ingredient_id", UUID::class.java).toString(),
            ingredientName = rs.getString("ingredient_name"),
            unit = IngredientUnit.valueOf(rs.getString("unit")),
            quantity = rs.getInt("quantity"),
          )
        }
        list
      }
    }

    return RecipeBindingDto(
      targetType = targetType,
      targetId = targetId.toString(),
      targetLabel = targetLabel,
      ingredients = lines,
    )
  }

  private fun loadTargetLabel(conn: Connection, targetType: RecipeTargetType, targetId: UUID): String? {
    val sql = when (targetType) {
      RecipeTargetType.VARIANT ->
        "SELECT (p.name || ' (' || pv.size_code || ')') FROM product_variants pv JOIN products p ON p.id = pv.product_id WHERE pv.id = ?"
      RecipeTargetType.CRUST ->
        "SELECT name FROM crust_options WHERE id = ?"
      RecipeTargetType.TOPPING ->
        "SELECT name FROM toppings WHERE id = ?"
    }
    return conn.prepareStatement(sql).use { ps ->
      ps.setObject(1, targetId)
      ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }
  }

  private fun tableSpec(targetType: RecipeTargetType): RecipeTableSpec =
    when (targetType) {
      RecipeTargetType.VARIANT -> RecipeTableSpec("variant_recipes", "variant_id", "variant_recipe")
      RecipeTargetType.CRUST -> RecipeTableSpec("crust_recipes", "crust_id", "crust_recipe")
      RecipeTargetType.TOPPING -> RecipeTableSpec("topping_recipes", "topping_id", "topping_recipe")
    }

  private fun parseUuid(raw: String, field: String): UUID =
    runCatching { UUID.fromString(raw) }
      .getOrElse { throw ValidationException("INVALID_UUID", "Invalid $field UUID: $raw") }

  private data class RecipeTableSpec(
    val table: String,
    val fkColumn: String,
    val auditEntityType: String,
  )
}

package com.pizzora.backend.modifiers

import com.pizzora.backend.core.config.Phase8FeatureFlags
import com.pizzora.backend.core.error.UnprocessableEntityException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.CartCheeseViewDto
import com.pizzora.contracts.CartHalfAndHalfViewDto
import com.pizzora.contracts.CartSauceViewDto
import com.pizzora.contracts.CartStandardToppingViewDto
import com.pizzora.contracts.CartToppingViewDto
import com.pizzora.contracts.CartValidationIssueCode
import com.pizzora.contracts.CartValidationIssueDto
import java.math.BigInteger
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.Json

private val MODIFIER_JSON = Json { ignoreUnknownKeys = true }

/**
 * Detects the additive SQLx V4-V11 objects at runtime. The legacy Kotlin backend must keep
 * working against Flyway V1-V3 only databases, so every new query is guarded by this probe.
 */
object Phase8Schema {
  fun detect(conn: Connection): Availability {
    val tables = mutableSetOf<String>()
    conn.createStatement().use { stmt ->
      stmt.executeQuery(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()",
      ).use { rs ->
        while (rs.next()) tables += rs.getString(1).lowercase()
      }
    }
    val cartColumns = mutableSetOf<String>()
    val orderColumns = mutableSetOf<String>()
    conn.prepareStatement(
      """
      SELECT table_name, column_name
      FROM information_schema.columns
      WHERE table_schema = current_schema()
        AND table_name IN ('cart_items', 'order_items')
      """.trimIndent(),
    ).use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          when (rs.getString(1).lowercase()) {
            "cart_items" -> cartColumns += rs.getString(2).lowercase()
            "order_items" -> orderColumns += rs.getString(2).lowercase()
          }
        }
      }
    }
    return Availability(
      hasProductToppings = "product_toppings" in tables,
      hasCheeses = "cheeses" in tables,
      hasSauces = "sauces" in tables,
      hasStandardToppings = "standard_toppings" in tables,
      hasHalfAndHalfConfigs = "half_and_half_configs" in tables,
      hasCartModifierColumns = CART_MODIFIER_COLUMNS.all { it in cartColumns },
      hasOrderModifierColumns = ORDER_MODIFIER_COLUMNS.all { it in orderColumns },
    )
  }

  data class Availability(
    val hasProductToppings: Boolean,
    val hasCheeses: Boolean,
    val hasSauces: Boolean,
    val hasStandardToppings: Boolean,
    val hasHalfAndHalfConfigs: Boolean,
    val hasCartModifierColumns: Boolean,
    val hasOrderModifierColumns: Boolean,
  )

  private val CART_MODIFIER_COLUMNS = setOf(
    "cheese_id",
    "sauce_id",
    "standard_topping_ids",
    "half_and_half_config_id",
    "half_and_half_left_topping_ids",
    "half_and_half_right_topping_ids",
  )

  private val ORDER_MODIFIER_COLUMNS = setOf(
    "variant_id",
    "crust_id",
    "cheese_id",
    "sauce_id",
    "topping_ids",
    "standard_topping_ids",
    "standard_topping_removed_ids",
    "half_and_half_config_id",
    "half_and_half_left_topping_ids",
    "half_and_half_right_topping_ids",
    "modifiers_snapshot",
  )
}

data class ModifierSelection(
  val cheeseId: UUID? = null,
  val sauceId: UUID? = null,
  val standardToppingIds: List<UUID> = emptyList(),
  val halfAndHalfConfigId: UUID? = null,
  val halfAndHalfLeftToppingIds: List<UUID> = emptyList(),
  val halfAndHalfRightToppingIds: List<UUID> = emptyList(),
) {
  fun referencesAny(): Boolean =
    cheeseId != null || sauceId != null || standardToppingIds.isNotEmpty() ||
      halfAndHalfConfigId != null || halfAndHalfLeftToppingIds.isNotEmpty() ||
      halfAndHalfRightToppingIds.isNotEmpty()
}

data class ModifierEvaluation(
  val issues: List<CartValidationIssueDto>,
  val cheeseView: CartCheeseViewDto?,
  val sauceView: CartSauceViewDto?,
  val standardToppingViews: List<CartStandardToppingViewDto>,
  val selectedStandardToppingIds: Set<UUID>,
  val halfAndHalfView: CartHalfAndHalfViewDto?,
  val cheeseName: String?,
  val sauceName: String?,
  val standardToppingNames: List<String>,
  val halfAndHalfName: String?,
  val leftToppingNames: List<String>,
  val rightToppingNames: List<String>,
) {
  /** Contract §5.1/§5.2: includes half-and-half surcharge, never charges removed defaults. */
  fun unitPricePaise(
    variantPricePaise: Long,
    crustExtraPaise: Long,
    extraToppingIds: List<UUID>,
    catalog: ModifierCatalog,
  ): Long {
    val add = Phase8ModifierPricing::checkedAdd
    val cheeseExtra = cheeseView?.takeIf { it.isAvailable }?.extraPricePaise ?: 0L
    val sauceExtra = sauceView?.takeIf { it.isAvailable }?.extraPricePaise ?: 0L
    val standardExtra = standardToppingViews
      .filter { view ->
        val id = runCatching { UUID.fromString(view.standardToppingId) }.getOrNull()
        view.isAvailable && !view.isDefault && !view.isRemoved && id in selectedStandardToppingIds
      }
      .fold(0L) { acc, view -> add(acc, view.extraPricePaise) }

    val half = halfAndHalfView
    if (half != null && half.isAvailable) {
      val leftBase = add(
        add(variantPricePaise, crustExtraPaise),
        half.leftToppings.fold(0L) { acc, view -> add(acc, view.extraPricePaise) },
      )
      val rightBase = add(
        add(variantPricePaise, crustExtraPaise),
        half.rightToppings.fold(0L) { acc, view -> add(acc, view.extraPricePaise) },
      )
      val average = Phase8ModifierPricing.roundHalfUp(leftBase, rightBase)
      return add(
        add(
          add(average, half.halfAndHalfSurchargePaise),
          add(cheeseExtra, sauceExtra),
        ),
        standardExtra,
      )
    }

    val extraToppingsExtra = extraToppingIds.distinct().fold(0L) { acc, id ->
      add(acc, catalog.toppings[id]?.extraPricePaise ?: 0L)
    }
    return add(
      add(
        add(add(variantPricePaise, crustExtraPaise), extraToppingsExtra),
        add(cheeseExtra, sauceExtra),
      ),
      standardExtra,
    )
  }
}

/** Loads the five Phase 8 modifier tables once per request, guarded by schema + flags. */
class ModifierCatalog private constructor(
  val products: Map<UUID, ProductModifiers>,
  val toppings: Map<UUID, ToppingRow>,
  private val productToppings: Map<UUID, Set<UUID>>,
) {
  fun allowedToppings(productId: UUID): Set<UUID>? = productToppings[productId]

  data class ProductModifiers(
    val cheeses: MutableMap<UUID, CheeseRow> = mutableMapOf(),
    val sauces: MutableMap<UUID, SauceRow> = mutableMapOf(),
    val standardToppings: MutableMap<UUID, StandardToppingRow> = mutableMapOf(),
    val halfAndHalfConfigs: MutableMap<UUID, HalfAndHalfConfigRow> = mutableMapOf(),
  )

  data class CheeseRow(
    val id: UUID,
    val productId: UUID,
    val name: String,
    val extraPricePaise: Long,
    val isActive: Boolean,
  )

  data class SauceRow(
    val id: UUID,
    val productId: UUID,
    val name: String,
    val extraPricePaise: Long,
    val isActive: Boolean,
  )

  data class StandardToppingRow(
    val id: UUID,
    val productId: UUID,
    val name: String,
    val isVeg: Boolean,
    val extraPricePaise: Long,
    val isDefault: Boolean,
    val isActive: Boolean,
  )

  data class HalfAndHalfConfigRow(
    val id: UUID,
    val productId: UUID,
    val name: String,
    val leftToppingIds: List<UUID>,
    val rightToppingIds: List<UUID>,
    val surchargePaise: Long,
    val isActive: Boolean,
  )

  data class ToppingRow(
    val id: UUID,
    val name: String,
    val isVeg: Boolean,
    val extraPricePaise: Long,
    val isActive: Boolean,
  )

  companion object {
    fun load(
      conn: Connection,
      schema: Phase8Schema.Availability,
      flags: Phase8FeatureFlags,
    ): ModifierCatalog {
      val products = mutableMapOf<UUID, ProductModifiers>()
      fun product(id: UUID): ProductModifiers =
        products.getOrPut(id) { ProductModifiers() }

      if (schema.hasCheeses && flags.cheese) {
        conn.prepareStatement(
          """
          SELECT id, product_id, name, extra_price_paise, is_active
          FROM cheeses
          ORDER BY sort_order ASC, name ASC
          """.trimIndent(),
        ).use { ps ->
          ps.executeQuery().use { rs ->
            while (rs.next()) {
              val row = CheeseRow(
                id = rs.getObject("id", UUID::class.java),
                productId = rs.getObject("product_id", UUID::class.java),
                name = rs.getString("name"),
                extraPricePaise = rs.getLong("extra_price_paise"),
                isActive = rs.getBoolean("is_active"),
              )
              product(row.productId).cheeses[row.id] = row
            }
          }
        }
      }

      if (schema.hasSauces && flags.sauce) {
        conn.prepareStatement(
          """
          SELECT id, product_id, name, extra_price_paise, is_active
          FROM sauces
          ORDER BY sort_order ASC, name ASC
          """.trimIndent(),
        ).use { ps ->
          ps.executeQuery().use { rs ->
            while (rs.next()) {
              val row = SauceRow(
                id = rs.getObject("id", UUID::class.java),
                productId = rs.getObject("product_id", UUID::class.java),
                name = rs.getString("name"),
                extraPricePaise = rs.getLong("extra_price_paise"),
                isActive = rs.getBoolean("is_active"),
              )
              product(row.productId).sauces[row.id] = row
            }
          }
        }
      }

      if (schema.hasStandardToppings && flags.standardToppings) {
        conn.prepareStatement(
          """
          SELECT id, product_id, name, is_veg, extra_price_paise, is_default, is_active
          FROM standard_toppings
          ORDER BY sort_order ASC, name ASC
          """.trimIndent(),
        ).use { ps ->
          ps.executeQuery().use { rs ->
            while (rs.next()) {
              val row = StandardToppingRow(
                id = rs.getObject("id", UUID::class.java),
                productId = rs.getObject("product_id", UUID::class.java),
                name = rs.getString("name"),
                isVeg = rs.getBoolean("is_veg"),
                extraPricePaise = rs.getLong("extra_price_paise"),
                isDefault = rs.getBoolean("is_default"),
                isActive = rs.getBoolean("is_active"),
              )
              product(row.productId).standardToppings[row.id] = row
            }
          }
        }
      }

      if (schema.hasHalfAndHalfConfigs && flags.halfAndHalf) {
        conn.prepareStatement(
          """
          SELECT id, product_id, name, left_topping_ids, right_topping_ids,
                 half_and_half_surcharge_paise, is_active
          FROM half_and_half_configs
          ORDER BY sort_order ASC, name ASC
          """.trimIndent(),
        ).use { ps ->
          ps.executeQuery().use { rs ->
            while (rs.next()) {
              val row = HalfAndHalfConfigRow(
                id = rs.getObject("id", UUID::class.java),
                productId = rs.getObject("product_id", UUID::class.java),
                name = rs.getString("name"),
                leftToppingIds = Phase8ModifierPricing.parseIds(rs.getString("left_topping_ids")),
                rightToppingIds = Phase8ModifierPricing.parseIds(rs.getString("right_topping_ids")),
                surchargePaise = rs.getLong("half_and_half_surcharge_paise"),
                isActive = rs.getBoolean("is_active"),
              )
              product(row.productId).halfAndHalfConfigs[row.id] = row
            }
          }
        }
      }

      val productToppings = mutableMapOf<UUID, MutableSet<UUID>>()
      if (schema.hasProductToppings) {
        conn.prepareStatement(
          "SELECT product_id, topping_id FROM product_toppings",
        ).use { ps ->
          ps.executeQuery().use { rs ->
            while (rs.next()) {
              productToppings.getOrPut(rs.getObject("product_id", UUID::class.java)) { mutableSetOf() } +=
                rs.getObject("topping_id", UUID::class.java)
            }
          }
        }
      }

      val toppings = mutableMapOf<UUID, ToppingRow>()
      conn.prepareStatement(
        "SELECT id, name, is_veg, extra_price_paise, is_active FROM toppings",
      ).use { ps ->
        ps.executeQuery().use { rs ->
          while (rs.next()) {
            val id = rs.getObject("id", UUID::class.java)
            toppings[id] = ToppingRow(
              id = id,
              name = rs.getString("name"),
              isVeg = rs.getBoolean("is_veg"),
              extraPricePaise = rs.getLong("extra_price_paise"),
              isActive = rs.getBoolean("is_active"),
            )
          }
        }
      }

      return ModifierCatalog(products.toMap(), toppings.toMap(), productToppings.mapValues { it.value.toSet() })
    }
  }
}

object Phase8ModifierPricing {
  const val MAX_TOPPINGS_PER_HALF = 10
  const val MAX_DISTINCT_HALF_TOPPINGS = 15

  /** Disabled flags hide catalog data and reject any request that still references the feature. */
  fun rejectDisabledWrites(selection: ModifierSelection, flags: Phase8FeatureFlags) {
    val disabled = mutableListOf<String>()
    if (!flags.cheese && selection.cheeseId != null) disabled += "cheese"
    if (!flags.sauce && selection.sauceId != null) disabled += "sauce"
    if (!flags.standardToppings && selection.standardToppingIds.isNotEmpty()) disabled += "standard toppings"
    if (!flags.halfAndHalf && (selection.halfAndHalfConfigId != null ||
        selection.halfAndHalfLeftToppingIds.isNotEmpty() || selection.halfAndHalfRightToppingIds.isNotEmpty())) {
      disabled += "half-and-half"
    }
    if (disabled.isNotEmpty()) {
      throw ValidationException(
        "FEATURE_DISABLED",
        "Phase 8 feature(s) disabled: ${disabled.joinToString(", ")}.",
      )
    }
  }

  fun evaluate(
    productId: UUID,
    selection: ModifierSelection,
    extraToppingIds: List<UUID>,
    catalog: ModifierCatalog,
  ): ModifierEvaluation {
    val issues = mutableListOf<CartValidationIssueDto>()

    // Active/existence checks are already performed by the calling service. Here we only
    // enforce the product_toppings authority so validation does not duplicate issue codes.
    extraToppingIds.distinct().forEach { id ->
      val allowed = catalog.allowedToppings(productId)
      val row = catalog.toppings[id]
      if (row != null && allowed != null && id !in allowed) {
        issues += issue(
          CartValidationIssueCode.TOPPING_UNAVAILABLE,
          "${row.name} is not available on this pizza.",
        )
      }
    }

    val modifiers = catalog.products[productId] ?: ModifierCatalog.ProductModifiers()

    val cheeseView: CartCheeseViewDto?
    val cheeseName: String?
    if (selection.cheeseId == null) {
      cheeseView = null
      cheeseName = null
    } else {
      val row = modifiers.cheeses[selection.cheeseId]
      val wrongProduct = row != null && row.productId != productId
      val available = row != null && row.isActive && !wrongProduct
      if (!available) {
        issues += issue(
          if (wrongProduct) CartValidationIssueCode.CHEESE_NOT_IN_PRODUCT else CartValidationIssueCode.CHEESE_UNAVAILABLE,
          "${row?.name ?: "Selected cheese"} is not available on this pizza.",
        )
      }
      cheeseView = CartCheeseViewDto(
        cheeseId = selection.cheeseId.toString(),
        name = row?.name ?: "Unavailable Cheese",
        extraPricePaise = if (available) row!!.extraPricePaise else 0L,
        isAvailable = available,
      )
      cheeseName = row?.name
    }

    val sauceView: CartSauceViewDto?
    val sauceName: String?
    if (selection.sauceId == null) {
      sauceView = null
      sauceName = null
    } else {
      val row = modifiers.sauces[selection.sauceId]
      val wrongProduct = row != null && row.productId != productId
      val available = row != null && row.isActive && !wrongProduct
      if (!available) {
        issues += issue(
          if (wrongProduct) CartValidationIssueCode.SAUCE_NOT_IN_PRODUCT else CartValidationIssueCode.SAUCE_UNAVAILABLE,
          "${row?.name ?: "Selected sauce"} is not available on this pizza.",
        )
      }
      sauceView = CartSauceViewDto(
        sauceId = selection.sauceId.toString(),
        name = row?.name ?: "Unavailable Sauce",
        extraPricePaise = if (available) row!!.extraPricePaise else 0L,
        isAvailable = available,
      )
      sauceName = row?.name
    }

    val selectedStandards = selection.standardToppingIds.toSet()
    val standardViews = modifiers.standardToppings.values.map { row ->
      val selected = row.id in selectedStandards
      val wrongProduct = row.productId != productId
      val available = row.isActive && !wrongProduct
      if (selected && !available) {
        issues += issue(
          if (wrongProduct) CartValidationIssueCode.STANDARD_TOPPING_NOT_IN_PRODUCT
          else CartValidationIssueCode.STANDARD_TOPPING_UNAVAILABLE,
          "${row.name} is not available on this pizza.",
        )
      }
      CartStandardToppingViewDto(
        standardToppingId = row.id.toString(),
        name = row.name,
        isVeg = row.isVeg,
        extraPricePaise = if (available) row.extraPricePaise else 0L,
        isDefault = row.isDefault,
        isRemoved = row.isDefault && !selected,
        isAvailable = available,
      )
    }
    // A selected ID that is not in the product catalog must not be silently ignored.
    selectedStandingsIssues(selection.standardToppingIds, modifiers, issues)

    val halfView: CartHalfAndHalfViewDto?
    val halfName: String?
    val leftNames: List<String>
    val rightNames: List<String>
    if (selection.halfAndHalfConfigId == null) {
      halfView = null
      halfName = null
      leftNames = emptyList()
      rightNames = emptyList()
    } else {
      val config = modifiers.halfAndHalfConfigs[selection.halfAndHalfConfigId]
      val wrongProduct = config != null && config.productId != productId
      var available = config != null && config.isActive && !wrongProduct

      val leftIds = selection.halfAndHalfLeftToppingIds.distinct()
      val rightIds = selection.halfAndHalfRightToppingIds.distinct()
      if (leftIds.size > MAX_TOPPINGS_PER_HALF || rightIds.size > MAX_TOPPINGS_PER_HALF) {
        issues += issue(
          CartValidationIssueCode.HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED,
          "Each half can contain at most $MAX_TOPPINGS_PER_HALF toppings.",
        )
        available = false
      }
      if ((leftIds.toSet() intersect rightIds.toSet()).isNotEmpty()) {
        issues += issue(
          CartValidationIssueCode.HALF_AND_HALF_LEFT_RIGHT_OVERLAP,
          "A topping cannot appear on both halves.",
        )
        available = false
      }
      if ((leftIds + rightIds).distinct().size > MAX_DISTINCT_HALF_TOPPINGS) {
        issues += issue(
          CartValidationIssueCode.HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED,
          "Half-and-half can contain at most $MAX_DISTINCT_HALF_TOPPINGS distinct toppings.",
        )
        available = false
      }
      if (config == null || wrongProduct || !config.isActive) {
        issues += issue(
          if (wrongProduct) CartValidationIssueCode.HALF_AND_HALF_CONFIG_NOT_IN_PRODUCT
          else CartValidationIssueCode.HALF_AND_HALF_UNAVAILABLE,
          "${config?.name ?: "Selected half-and-half"} is not available on this pizza.",
        )
        available = false
      }
      val allowed = catalog.allowedToppings(productId)
      (leftIds + rightIds).distinct().forEach { id ->
        val row = catalog.toppings[id]
        if (row == null || !row.isActive || (allowed != null && id !in allowed)) {
          issues += issue(
            CartValidationIssueCode.TOPPING_UNAVAILABLE,
            "${row?.name ?: "Selected topping"} is not available on this pizza.",
          )
          available = false
        }
      }

      leftNames = leftIds.mapNotNull { catalog.toppings[it]?.name }
      rightNames = rightIds.mapNotNull { catalog.toppings[it]?.name }
      halfView = CartHalfAndHalfViewDto(
        halfAndHalfConfigId = selection.halfAndHalfConfigId.toString(),
        name = config?.name ?: "Unavailable Half & Half",
        leftToppingIds = leftIds.map(UUID::toString),
        rightToppingIds = rightIds.map(UUID::toString),
        leftToppings = leftIds.mapNotNull { catalog.toppings[it]?.let { row -> toppingView(it, row) } },
        rightToppings = rightIds.mapNotNull { catalog.toppings[it]?.let { row -> toppingView(it, row) } },
        halfAndHalfSurchargePaise = if (available) config?.surchargePaise ?: 0L else 0L,
        isAvailable = available,
      )
      halfName = config?.name
    }

    return ModifierEvaluation(
      issues = issues.toList(),
      cheeseView = cheeseView,
      sauceView = sauceView,
      standardToppingViews = standardViews,
      selectedStandardToppingIds = selectedStandards,
      halfAndHalfView = halfView,
      cheeseName = cheeseName,
      sauceName = sauceName,
      standardToppingNames = standardViews.filter { row -> row.idIn(selection.standardToppingIds) }.map { it.name },
      halfAndHalfName = halfName,
      leftToppingNames = leftNames,
      rightToppingNames = rightNames,
    )
  }

  fun toppingView(id: UUID, row: ModifierCatalog.ToppingRow): CartToppingViewDto =
    CartToppingViewDto(
      toppingId = id.toString(),
      name = row.name,
      isVeg = row.isVeg,
      extraPricePaise = row.extraPricePaise,
      isAvailable = row.isActive,
    )

  fun parseIds(raw: String?): List<UUID> =
    if (raw.isNullOrBlank()) emptyList()
    else runCatching {
      MODIFIER_JSON.decodeFromString<List<String>>(raw).mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
    }.getOrDefault(emptyList())

  fun encodeIds(ids: Collection<UUID>): String = MODIFIER_JSON.encodeToString(ids.map(UUID::toString))

  fun encodeNames(names: Collection<String>): String = MODIFIER_JSON.encodeToString(names.toList())

  fun checkedAdd(left: Long, right: Long): Long =
    runCatching { Math.addExact(left, right) }
      .getOrElse { throw overflow() }

  fun roundHalfUp(left: Long, right: Long): Long {
    val sum = BigInteger.valueOf(left).add(BigInteger.valueOf(right)).add(BigInteger.ONE)
    val average = sum.divide(BigInteger.TWO)
    if (average > BigInteger.valueOf(Long.MAX_VALUE)) throw overflow()
    return average.toLong()
  }

  private fun overflow() = UnprocessableEntityException(
    "ARITHMETIC_OVERFLOW",
    "Price calculation overflowed the supported paise range.",
  )

  private fun issue(code: CartValidationIssueCode, message: String) = CartValidationIssueDto(code, message)

  private fun selectedStandingsIssues(
    selected: Collection<UUID>,
    modifiers: ModifierCatalog.ProductModifiers,
    issues: MutableList<CartValidationIssueDto>,
  ) {
    selected.forEach { id ->
      if (id !in modifiers.standardToppings) {
        issues += issue(
          CartValidationIssueCode.STANDARD_TOPPING_UNAVAILABLE,
          "Selected standard topping is not available on this pizza.",
        )
      }
    }
  }

  private fun CartStandardToppingViewDto.idIn(ids: Collection<UUID>): Boolean =
    runCatching { UUID.fromString(standardToppingId) }.getOrNull() in ids
}

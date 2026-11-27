package com.pizzora.backend.cart

import com.pizzora.backend.core.config.Phase8FeatureFlags
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.UnprocessableEntityException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.modifiers.ModifierCatalog
import com.pizzora.backend.modifiers.ModifierSelection
import com.pizzora.backend.modifiers.Phase8ModifierPricing
import com.pizzora.backend.modifiers.Phase8Schema
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CartToppingViewDto
import com.pizzora.contracts.CartValidationIssueCode
import com.pizzora.contracts.CartValidationIssueDto
import com.pizzora.contracts.ReplaceCartRequest
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ValidateCartRequest
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json

class CartService(
  private val dataSource: DataSource,
  private val publicUrlResolver: (String) -> String?,
  private val features: Phase8FeatureFlags = Phase8FeatureFlags(),
) {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  suspend fun getCart(userId: UUID): CartResponseDto =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val cartHeader = loadCartHeader(conn, userId)
      if (cartHeader == null) {
        return@withJdbcTransaction CartResponseDto(
          cartId = null,
          items = emptyList(),
          subtotalPaise = 0L,
          totalItemCount = 0,
          allAvailable = true,
          unavailableCount = 0,
          updatedAt = Instant.now().toString(),
        )
      }

      val persistedInputs = loadPersistedCartItems(conn, cartHeader.first)
      val evaluated = evaluateCartItems(conn, persistedInputs, publicUrlResolver)
      buildCartResponse(cartId = cartHeader.first.toString(), items = evaluated, updatedAt = cartHeader.second)
    }

  suspend fun replaceCart(userId: UUID, request: ReplaceCartRequest): CartResponseDto {
    val parsedInputs = validateAndParseInputs(request.items)
    parsedInputs.forEach { Phase8ModifierPricing.rejectDisabledWrites(it.modifiers, features) }

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val cartId = ensureCartRow(conn, userId)
      val schema = Phase8Schema.detect(conn)
      val catalog = ModifierCatalog.load(conn, schema, features)

      // Clear previous items (cart_item_toppings cascades cleanly via ON DELETE CASCADE)
      conn.prepareStatement("DELETE FROM cart_items WHERE cart_id = ?").use { ps ->
        ps.setObject(1, cartId)
        ps.executeUpdate()
      }

      // Check referenced IDs exist in catalog tables before FK inserts.
      // Inactive items ARE allowed in cart persistence so GET /cart can flag them cleanly.
      val existingRefs = checkForeignKeyExistence(conn, parsedInputs)
      val mergedInputs = mergeDuplicateConfigurations(parsedInputs)

      val persistedIds = mutableListOf<PersistedCartInput>()
      for (item in mergedInputs) {
        if (!existingRefs.productExists(item.productId)) {
          throw ValidationException("INVALID_PRODUCT_ID", "Product ${item.productId} does not exist.")
        }
        if (!existingRefs.variantMatchesProduct(item.variantId, item.productId)) {
          throw ValidationException(
            "VARIANT_PRODUCT_MISMATCH",
            "Variant ${item.variantId} does not belong to product ${item.productId}.",
          )
        }
        if (!existingRefs.crustExists(item.crustId)) {
          throw ValidationException("INVALID_CRUST_ID", "Crust ${item.crustId} does not exist.")
        }
        item.toppingIds.forEach { tid ->
          if (!existingRefs.toppingExists(tid)) {
            throw ValidationException("INVALID_TOPPING_ID", "Topping $tid does not exist.")
          }
        }
        validateModifierRefs(item, schema, catalog)

        val serverItemId = UUID.randomUUID()
        val modifierColumns = if (schema.hasCartModifierColumns) {
          """
          , cheese_id, sauce_id, standard_topping_ids, half_and_half_config_id,
            half_and_half_left_topping_ids, half_and_half_right_topping_ids
          """.trimIndent()
        } else {
          ""
        }
        val modifierValues = if (schema.hasCartModifierColumns) {
          ", ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb"
        } else {
          ""
        }
        conn.prepareStatement(
          """
          INSERT INTO cart_items (id, cart_id, product_id, variant_id, crust_id, quantity$modifierColumns)
          VALUES (?, ?, ?, ?, ?, ?$modifierValues)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, serverItemId)
          ps.setObject(2, cartId)
          ps.setObject(3, item.productId)
          ps.setObject(4, item.variantId)
          ps.setObject(5, item.crustId)
          ps.setInt(6, item.quantity)
          if (schema.hasCartModifierColumns) {
            val mods = item.modifiers
            setNullableUuid(ps, 7, mods.cheeseId)
            setNullableUuid(ps, 8, mods.sauceId)
            ps.setString(9, Phase8ModifierPricing.encodeIds(mods.standardToppingIds))
            setNullableUuid(ps, 10, mods.halfAndHalfConfigId)
            ps.setString(11, Phase8ModifierPricing.encodeIds(mods.halfAndHalfLeftToppingIds))
            ps.setString(12, Phase8ModifierPricing.encodeIds(mods.halfAndHalfRightToppingIds))
          }
          ps.executeUpdate()
        }

        if (item.toppingIds.isNotEmpty()) {
          conn.prepareStatement(
            "INSERT INTO cart_item_toppings (cart_item_id, topping_id) VALUES (?, ?)",
          ).use { ps ->
            item.toppingIds.forEach { tid ->
              ps.setObject(1, serverItemId)
              ps.setObject(2, tid)
              ps.addBatch()
            }
            ps.executeBatch()
          }
        }

        persistedIds += item.copy(itemId = serverItemId.toString())
      }

      val updatedAt = conn.prepareStatement(
        "UPDATE carts SET updated_at = NOW() WHERE id = ? RETURNING updated_at",
      ).use { ps ->
        ps.setObject(1, cartId)
        ps.executeQuery().use { rs ->
          rs.next()
          rs.getObject("updated_at", java.time.OffsetDateTime::class.java).toInstant().toString()
        }
      }

      val evaluated = evaluateCartItems(conn, persistedIds, publicUrlResolver)
      buildCartResponse(cartId = cartId.toString(), items = evaluated, updatedAt = updatedAt)
    }
  }

  suspend fun validateCart(request: ValidateCartRequest): CartResponseDto {
    val parsedInputs = validateAndParseInputs(request.items)
    parsedInputs.forEach { Phase8ModifierPricing.rejectDisabledWrites(it.modifiers, features) }

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val evaluated = evaluateCartItems(conn, parsedInputs, publicUrlResolver)
      buildCartResponse(
        cartId = null,
        items = evaluated,
        updatedAt = Instant.now().toString(),
      )
    }
  }

  private fun validateAndParseInputs(items: List<CartItemInputDto>): List<PersistedCartInput> {
    if (items.size > MAX_CART_LINE_ITEMS) {
      throw ValidationException("CART_LIMIT_EXCEEDED", "Cart cannot contain more than $MAX_CART_LINE_ITEMS distinct line items.")
    }
    return items.mapIndexed { index, raw ->
      if (raw.quantity !in 1..MAX_ITEM_QUANTITY) {
        throw ValidationException(
          "INVALID_QUANTITY",
          "Quantity for item #$index must be between 1 and $MAX_ITEM_QUANTITY.",
        )
      }
      val productUuid = parseUuid(raw.productId, "product_id")
      val variantUuid = parseUuid(raw.variantId, "variant_id")
      val crustUuid = parseUuid(raw.crustId, "crust_id")
      val toppingUuids = raw.toppingIds.map { parseUuid(it, "topping_id") }.distinct().sortedBy { it.toString() }

      PersistedCartInput(
        itemId = raw.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
        productId = productUuid,
        variantId = variantUuid,
        crustId = crustUuid,
        toppingIds = toppingUuids,
        quantity = raw.quantity,
        modifiers = ModifierSelection(
          cheeseId = raw.cheeseId?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "cheese_id") },
          sauceId = raw.sauceId?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "sauce_id") },
          standardToppingIds = raw.standardToppingIds.map { parseUuid(it, "standard_topping_id") }
            .distinct().sortedBy { it.toString() },
          halfAndHalfConfigId = raw.halfAndHalfConfigId?.takeIf { it.isNotBlank() }
            ?.let { parseUuid(it, "half_and_half_config_id") },
          halfAndHalfLeftToppingIds = raw.halfAndHalfLeftToppingIds.map { parseUuid(it, "half_and_half_left_topping_id") }
            .distinct().sortedBy { it.toString() },
          halfAndHalfRightToppingIds = raw.halfAndHalfRightToppingIds.map { parseUuid(it, "half_and_half_right_topping_id") }
            .distinct().sortedBy { it.toString() },
        ),
      )
    }
  }

  private fun mergeDuplicateConfigurations(inputs: List<PersistedCartInput>): List<PersistedCartInput> {
    val grouped = linkedMapOf<ConfigKey, PersistedCartInput>()
    for (item in inputs) {
      val key = ConfigKey(item.productId, item.variantId, item.crustId, item.toppingIds, item.modifiers)
      val existing = grouped[key]
      grouped[key] = if (existing == null) {
        item
      } else {
        existing.copy(quantity = (existing.quantity + item.quantity).coerceAtMost(MAX_ITEM_QUANTITY))
      }
    }
    return grouped.values.toList()
  }

  private fun loadCartHeader(conn: Connection, userId: UUID): Pair<UUID, String>? =
    conn.prepareStatement("SELECT id, updated_at FROM carts WHERE user_id = ?").use { ps ->
      ps.setObject(1, userId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) null
        else {
          rs.getObject("id", UUID::class.java) to
            rs.getObject("updated_at", java.time.OffsetDateTime::class.java).toInstant().toString()
        }
      }
    }

  private fun ensureCartRow(conn: Connection, userId: UUID): UUID {
    loadCartHeader(conn, userId)?.let { return it.first }
    val id = UUID.randomUUID()
    conn.prepareStatement(
      """
      INSERT INTO carts (id, user_id)
      VALUES (?, ?)
      ON CONFLICT (user_id) DO UPDATE SET updated_at = NOW()
      RETURNING id
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, id)
      ps.setObject(2, userId)
      return ps.executeQuery().use { rs ->
        rs.next()
        rs.getObject("id", UUID::class.java)
      }
    }
  }

  private fun loadPersistedCartItems(conn: Connection, cartId: UUID): List<PersistedCartInput> {
    val schema = Phase8Schema.detect(conn)
    val modifierColumns = if (schema.hasCartModifierColumns) {
      """
      , cheese_id, sauce_id, standard_topping_ids, half_and_half_config_id,
        half_and_half_left_topping_ids, half_and_half_right_topping_ids
      """.trimIndent()
    } else {
      ""
    }
    val baseRows = conn.prepareStatement(
      """
      SELECT id, product_id, variant_id, crust_id, quantity$modifierColumns
      FROM cart_items
      WHERE cart_id = ?
      ORDER BY created_at ASC, id ASC
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, cartId)
      ps.executeQuery().use { rs ->
        val out = mutableListOf<PersistedCartInput>()
        while (rs.next()) {
          out += PersistedCartInput(
            itemId = rs.getObject("id", UUID::class.java).toString(),
            productId = rs.getObject("product_id", UUID::class.java),
            variantId = rs.getObject("variant_id", UUID::class.java),
            crustId = rs.getObject("crust_id", UUID::class.java),
            toppingIds = emptyList(),
            quantity = rs.getInt("quantity"),
            modifiers = if (schema.hasCartModifierColumns) {
              ModifierSelection(
                cheeseId = rs.getObject("cheese_id", UUID::class.java),
                sauceId = rs.getObject("sauce_id", UUID::class.java),
                standardToppingIds = Phase8ModifierPricing.parseIds(rs.getString("standard_topping_ids")),
                halfAndHalfConfigId = rs.getObject("half_and_half_config_id", UUID::class.java),
                halfAndHalfLeftToppingIds = Phase8ModifierPricing.parseIds(
                  rs.getString("half_and_half_left_topping_ids"),
                ),
                halfAndHalfRightToppingIds = Phase8ModifierPricing.parseIds(
                  rs.getString("half_and_half_right_topping_ids"),
                ),
              )
            } else {
              ModifierSelection()
            },
          )
        }
        out
      }
    }
    if (baseRows.isEmpty()) return emptyList()

    val toppingsMap = mutableMapOf<String, MutableList<UUID>>()
    conn.prepareStatement(
      """
      SELECT cit.cart_item_id, cit.topping_id
      FROM cart_item_toppings cit
      JOIN cart_items ci ON ci.id = cit.cart_item_id
      WHERE ci.cart_id = ?
      ORDER BY cit.topping_id ASC
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, cartId)
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val itemId = rs.getObject("cart_item_id", UUID::class.java).toString()
          val toppingId = rs.getObject("topping_id", UUID::class.java)
          toppingsMap.getOrPut(itemId) { mutableListOf() } += toppingId
        }
      }
    }

    return baseRows.map { row ->
      row.copy(toppingIds = toppingsMap[row.itemId].orEmpty())
    }
  }

  private fun checkForeignKeyExistence(conn: Connection, inputs: List<PersistedCartInput>): RefLookup {
    val products = mutableSetOf<UUID>()
    conn.prepareStatement("SELECT id FROM products").use { ps ->
      ps.executeQuery().use { rs -> while (rs.next()) products += rs.getObject("id", UUID::class.java) }
    }
    val variants = mutableMapOf<UUID, UUID>()
    conn.prepareStatement("SELECT id, product_id FROM product_variants").use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) variants[rs.getObject("id", UUID::class.java)] = rs.getObject("product_id", UUID::class.java)
      }
    }
    val crusts = mutableSetOf<UUID>()
    conn.prepareStatement("SELECT id FROM crust_options").use { ps ->
      ps.executeQuery().use { rs -> while (rs.next()) crusts += rs.getObject("id", UUID::class.java) }
    }
    val toppings = mutableSetOf<UUID>()
    conn.prepareStatement("SELECT id FROM toppings").use { ps ->
      ps.executeQuery().use { rs -> while (rs.next()) toppings += rs.getObject("id", UUID::class.java) }
    }
    return RefLookup(products, variants, crusts, toppings)
  }

  private fun validateModifierRefs(
    item: PersistedCartInput,
    schema: Phase8Schema.Availability,
    catalog: ModifierCatalog,
  ) {
    val mods = item.modifiers
    val productModifiers = catalog.products[item.productId]
    if (mods.cheeseId != null) {
      if (!schema.hasCheeses || !features.cheese) {
        throw ValidationException("CHEESE_UNAVAILABLE", "Cheese modifiers are not available.")
      }
      val row = productModifiers?.cheeses?.get(mods.cheeseId)
        ?: throw ValidationException("CHEESE_NOT_IN_PRODUCT", "Cheese ${mods.cheeseId} does not belong to product ${item.productId}.")
      if (!row.isActive) {
        throw ValidationException("CHEESE_UNAVAILABLE", "Cheese ${row.name} is not active.")
      }
    }
    if (mods.sauceId != null) {
      if (!schema.hasSauces || !features.sauce) {
        throw ValidationException("SAUCE_UNAVAILABLE", "Sauce modifiers are not available.")
      }
      val row = productModifiers?.sauces?.get(mods.sauceId)
        ?: throw ValidationException("SAUCE_NOT_IN_PRODUCT", "Sauce ${mods.sauceId} does not belong to product ${item.productId}.")
      if (!row.isActive) {
        throw ValidationException("SAUCE_UNAVAILABLE", "Sauce ${row.name} is not active.")
      }
    }
    mods.standardToppingIds.forEach { id ->
      if (!schema.hasStandardToppings || !features.standardToppings) {
        throw ValidationException("STANDARD_TOPPING_UNAVAILABLE", "Standard topping modifiers are not available.")
      }
      val row = productModifiers?.standardToppings?.get(id)
        ?: throw ValidationException(
          "STANDARD_TOPPING_NOT_IN_PRODUCT",
          "Standard topping $id does not belong to product ${item.productId}.",
        )
      if (!row.isActive) {
        throw ValidationException("STANDARD_TOPPING_UNAVAILABLE", "Standard topping ${row.name} is not active.")
      }
    }
    if (mods.halfAndHalfConfigId != null || mods.halfAndHalfLeftToppingIds.isNotEmpty() ||
      mods.halfAndHalfRightToppingIds.isNotEmpty()
    ) {
      if (!schema.hasHalfAndHalfConfigs || !features.halfAndHalf) {
        throw ValidationException("HALF_AND_HALF_UNAVAILABLE", "Half-and-half modifiers are not available.")
      }
      val configId = mods.halfAndHalfConfigId
        ?: throw ValidationException("HALF_AND_HALF_UNAVAILABLE", "half_and_half_config_id is required for half-and-half.")
      val config = productModifiers?.halfAndHalfConfigs?.get(configId)
        ?: throw ValidationException(
          "HALF_AND_HALF_CONFIG_NOT_IN_PRODUCT",
          "Half-and-half config $configId does not belong to product ${item.productId}.",
        )
      if (!config.isActive) {
        throw ValidationException("HALF_AND_HALF_UNAVAILABLE", "Half-and-half config ${config.name} is not active.")
      }
    }
    val halfToppings = mods.halfAndHalfLeftToppingIds + mods.halfAndHalfRightToppingIds
    if (halfToppings.isNotEmpty()) {
      if (mods.halfAndHalfLeftToppingIds.size > Phase8ModifierPricing.MAX_TOPPINGS_PER_HALF ||
        mods.halfAndHalfRightToppingIds.size > Phase8ModifierPricing.MAX_TOPPINGS_PER_HALF
      ) {
        throw ValidationException(
          "HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED",
          "Each half can contain at most ${Phase8ModifierPricing.MAX_TOPPINGS_PER_HALF} toppings.",
        )
      }
      if (mods.halfAndHalfLeftToppingIds.toSet().intersect(mods.halfAndHalfRightToppingIds.toSet()).isNotEmpty()) {
        throw ValidationException(
          "HALF_AND_HALF_LEFT_RIGHT_OVERLAP",
          "A topping cannot appear on both halves.",
        )
      }
      if (halfToppings.distinct().size > Phase8ModifierPricing.MAX_DISTINCT_HALF_TOPPINGS) {
        throw ValidationException(
          "HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED",
          "Half-and-half can contain at most ${Phase8ModifierPricing.MAX_DISTINCT_HALF_TOPPINGS} distinct toppings.",
        )
      }
      val allowed = catalog.allowedToppings(item.productId)
      halfToppings.forEach { id ->
        if (catalog.toppings[id] == null || (allowed != null && id !in allowed)) {
          throw ValidationException("TOPPING_UNAVAILABLE", "Topping $id is not available on product ${item.productId}.")
        }
      }
    }
  }

  private fun setNullableUuid(ps: java.sql.PreparedStatement, index: Int, value: UUID?) {
    if (value == null) ps.setNull(index, java.sql.Types.OTHER) else ps.setObject(index, value)
  }

  private fun evaluateCartItems(
    conn: Connection,
    inputs: List<PersistedCartInput>,
    publicUrlResolver: (String) -> String?,
  ): List<CartItemViewDto> {
    if (inputs.isEmpty()) return emptyList()

    val productsMap = mutableMapOf<UUID, ProductRow>()
    conn.prepareStatement(
      """
      SELECT p.id, p.name, p.is_veg, p.is_active AS product_active, c.is_active AS category_active
      FROM products p
      JOIN categories c ON c.id = p.category_id
      """.trimIndent(),
    ).use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val id = rs.getObject("id", UUID::class.java)
          productsMap[id] = ProductRow(
            id = id,
            name = rs.getString("name"),
            isVeg = rs.getBoolean("is_veg"),
            isActive = rs.getBoolean("product_active") && rs.getBoolean("category_active"),
          )
        }
      }
    }

    val productImageMap = mutableMapOf<UUID, String>()
    conn.prepareStatement(
      """
      SELECT DISTINCT ON (product_id) product_id, object_key
      FROM product_images
      WHERE status = 'ACTIVE'
      ORDER BY product_id, sort_order ASC, created_at ASC
      """.trimIndent(),
    ).use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val resolvedUrl = publicUrlResolver(rs.getString("object_key"))?.trim()?.takeIf { it.isNotEmpty() }
          if (resolvedUrl != null) {
            productImageMap[rs.getObject("product_id", UUID::class.java)] = resolvedUrl
          }
        }
      }
    }

    val variantsMap = mutableMapOf<UUID, VariantRow>()
    conn.prepareStatement("SELECT id, product_id, size_code, price_paise, is_active FROM product_variants").use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val id = rs.getObject("id", UUID::class.java)
          variantsMap[id] = VariantRow(
            id = id,
            productId = rs.getObject("product_id", UUID::class.java),
            sizeCode = SizeCode.valueOf(rs.getString("size_code")),
            pricePaise = rs.getLong("price_paise"),
            isActive = rs.getBoolean("is_active"),
          )
        }
      }
    }

    val crustsMap = mutableMapOf<UUID, CrustRow>()
    conn.prepareStatement("SELECT id, name, extra_price_paise, is_active FROM crust_options").use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val id = rs.getObject("id", UUID::class.java)
          crustsMap[id] = CrustRow(
            id = id,
            name = rs.getString("name"),
            extraPricePaise = rs.getLong("extra_price_paise"),
            isActive = rs.getBoolean("is_active"),
          )
        }
      }
    }

    val toppingsMap = mutableMapOf<UUID, ToppingRow>()
    conn.prepareStatement("SELECT id, name, is_veg, extra_price_paise, is_active FROM toppings").use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val id = rs.getObject("id", UUID::class.java)
          toppingsMap[id] = ToppingRow(
            id = id,
            name = rs.getString("name"),
            isVeg = rs.getBoolean("is_veg"),
            extraPricePaise = rs.getLong("extra_price_paise"),
            isActive = rs.getBoolean("is_active"),
          )
        }
      }
    }

    val schema = Phase8Schema.detect(conn)
    val catalog = ModifierCatalog.load(conn, schema, features)

    return inputs.map { input ->
      val issues = mutableListOf<CartValidationIssueDto>()
      val prod = productsMap[input.productId]
      val variant = variantsMap[input.variantId]
      val crust = crustsMap[input.crustId]

      if (prod == null || !prod.isActive) {
        issues += CartValidationIssueDto(
          code = CartValidationIssueCode.PRODUCT_UNAVAILABLE,
          message = "${prod?.name ?: "Pizza"} is currently unavailable.",
        )
      }
      if (variant == null || !variant.isActive) {
        issues += CartValidationIssueDto(
          code = CartValidationIssueCode.VARIANT_UNAVAILABLE,
          message = "Selected size ${variant?.sizeCode?.name ?: ""} is currently unavailable.".trim(),
        )
      } else if (variant.productId != input.productId) {
        issues += CartValidationIssueDto(
          code = CartValidationIssueCode.VARIANT_PRODUCT_MISMATCH,
          message = "Selected size does not belong to this pizza.",
        )
      }
      if (crust == null || !crust.isActive) {
        issues += CartValidationIssueDto(
          code = CartValidationIssueCode.CRUST_UNAVAILABLE,
          message = "${crust?.name ?: "Selected crust"} is currently unavailable.",
        )
      }

      val toppingViews = input.toppingIds.map { tid ->
        val row = toppingsMap[tid]
        val available = row != null && row.isActive
        if (!available) {
          issues += CartValidationIssueDto(
            code = CartValidationIssueCode.TOPPING_UNAVAILABLE,
            message = "${row?.name ?: "Selected topping"} is no longer available.",
          )
        }
        CartToppingViewDto(
          toppingId = tid.toString(),
          name = row?.name ?: "Unavailable Topping",
          isVeg = row?.isVeg ?: true,
          extraPricePaise = row?.extraPricePaise ?: 0L,
          isAvailable = available,
        )
      }

      val modifiers = Phase8ModifierPricing.evaluate(
        productId = input.productId,
        selection = input.modifiers,
        extraToppingIds = input.toppingIds,
        catalog = catalog,
      )
      issues += modifiers.issues

      val basePrice = variant?.pricePaise ?: 0L
      val crustPrice = crust?.extraPricePaise ?: 0L
      val unitPrice = modifiers.unitPricePaise(
        variantPricePaise = basePrice,
        crustExtraPaise = crustPrice,
        extraToppingIds = input.toppingIds,
        catalog = catalog,
      )
      val lineTotal = runCatching { Math.multiplyExact(unitPrice, input.quantity) }
        .getOrElse {
          throw UnprocessableEntityException(
            "ARITHMETIC_OVERFLOW",
            "Line total overflowed the supported paise range.",
          )
        }

      CartItemViewDto(
        id = input.itemId,
        productId = input.productId.toString(),
        productName = prod?.name ?: "Unavailable Pizza",
        isVeg = prod?.isVeg ?: true,
        imageUrl = productImageMap[input.productId],
        variantId = input.variantId.toString(),
        sizeCode = variant?.sizeCode,
        basePricePaise = basePrice,
        crustId = input.crustId.toString(),
        crustName = crust?.name ?: "Unavailable Crust",
        crustExtraPricePaise = crustPrice,
        toppings = toppingViews,
        quantity = input.quantity,
        unitPricePaise = unitPrice,
        lineTotalPaise = lineTotal,
        isAvailable = issues.isEmpty(),
        issues = issues,
        cheese = modifiers.cheeseView,
        sauce = modifiers.sauceView,
        standardToppings = modifiers.standardToppingViews,
        halfAndHalf = modifiers.halfAndHalfView,
      )
    }
  }

  private fun buildCartResponse(
    cartId: String?,
    items: List<CartItemViewDto>,
    updatedAt: String,
  ): CartResponseDto {
    val subtotal = items.filter { it.isAvailable }.sumOf { it.lineTotalPaise }
    val unavailableCount = items.count { !it.isAvailable }
    return CartResponseDto(
      cartId = cartId,
      items = items,
      subtotalPaise = subtotal,
      totalItemCount = items.sumOf { it.quantity },
      allAvailable = unavailableCount == 0,
      unavailableCount = unavailableCount,
      updatedAt = updatedAt,
    )
  }

  private fun parseUuid(raw: String, field: String): UUID =
    runCatching { UUID.fromString(raw) }
      .getOrElse { throw ValidationException("INVALID_UUID", "Invalid $field UUID: $raw") }

  private data class PersistedCartInput(
    val itemId: String,
    val productId: UUID,
    val variantId: UUID,
    val crustId: UUID,
    val toppingIds: List<UUID>,
    val quantity: Int,
    val modifiers: ModifierSelection = ModifierSelection(),
  )

  private data class ConfigKey(
    val productId: UUID,
    val variantId: UUID,
    val crustId: UUID,
    val toppingIds: List<UUID>,
    val modifiers: ModifierSelection,
  )

  private data class RefLookup(
    val products: Set<UUID>,
    val variantToProduct: Map<UUID, UUID>,
    val crusts: Set<UUID>,
    val toppings: Set<UUID>,
  ) {
    fun productExists(id: UUID) = id in products
    fun variantMatchesProduct(variantId: UUID, productId: UUID) = variantToProduct[variantId] == productId
    fun crustExists(id: UUID) = id in crusts
    fun toppingExists(id: UUID) = id in toppings
  }

  private data class ProductRow(val id: UUID, val name: String, val isVeg: Boolean, val isActive: Boolean)
  private data class VariantRow(
    val id: UUID,
    val productId: UUID,
    val sizeCode: SizeCode,
    val pricePaise: Long,
    val isActive: Boolean,
  )
  private data class CrustRow(val id: UUID, val name: String, val extraPricePaise: Long, val isActive: Boolean)
  private data class ToppingRow(
    val id: UUID,
    val name: String,
    val isVeg: Boolean,
    val extraPricePaise: Long,
    val isActive: Boolean,
  )

  companion object {
    const val MAX_CART_LINE_ITEMS = 50
    const val MAX_ITEM_QUANTITY = 25
  }
}

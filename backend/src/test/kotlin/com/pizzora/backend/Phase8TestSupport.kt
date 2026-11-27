package com.pizzora.backend

import com.pizzora.backend.core.security.PasswordHasher
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Shared fixtures for the new Phase 8 backend tests. */
internal object Phase8TestSupport {
  data class CatalogFixture(
    val categoryId: UUID,
    val productId: UUID,
    val variantId: UUID,
    val classicCrustId: UUID,
    val premiumCrustId: UUID,
    val toppingLeftId: UUID,
    val toppingRightId: UUID,
    val unavailableToppingId: UUID,
    val cheeseId: UUID,
    val inactiveCheeseId: UUID,
    val sauceId: UUID,
    val inactiveSauceId: UUID,
    val defaultStandardToppingId: UUID,
    val extraStandardToppingId: UUID,
    val secondExtraStandardToppingId: UUID,
    val halfAndHalfConfigId: UUID,
  )

  fun applyPhase8Schema(dataSource: DataSource) {
    dataSource.connection.use { conn ->
      conn.createStatement().use { stmt ->
        stmt.executeQuery(
          "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'half_and_half_configs'",
        ).use { rs ->
          rs.next()
          if (rs.getInt(1) > 0) return
        }
      }
      conn.autoCommit = true
      (4..11).forEach { version ->
        val fileName = "%04d_".format(version) + when (version) {
          4 -> "product_toppings.sql"
          5 -> "cheeses.sql"
          6 -> "sauces.sql"
          7 -> "standard_toppings.sql"
          8 -> "half_and_half.sql"
          9 -> "cart_modifier_columns.sql"
          10 -> "order_modifier_columns.sql"
          11 -> "modifier_indexes.sql"
          else -> error("Unexpected Phase 8 migration version $version")
        }
        val path = listOf(
          Paths.get("rust", "crates", "pizzora-db", "migrations", fileName),
          Paths.get("..", "rust", "crates", "pizzora-db", "migrations", fileName),
          Paths.get("..", "..", "rust", "crates", "pizzora-db", "migrations", fileName),
        ).firstOrNull { Files.isRegularFile(it) }
          ?: error("Could not locate Phase 8 migration $fileName from ${System.getProperty("user.dir")}")
        val statements = Files.readString(path)
          .lines()
          .map { it.substringBefore("--") }
          .joinToString("\n")
          .split(';')
          .map { it.trim() }
          .filter { statement ->
            listOf("CREATE", "ALTER", "INSERT").any { prefix ->
              statement.startsWith(prefix, ignoreCase = true)
            }
          }
        conn.createStatement().use { stmt ->
          statements.forEach { statement -> stmt.execute(statement) }
        }
      }
    }
  }

  fun seedCatalog(dataSource: DataSource, label: String = UUID.randomUUID().toString().take(8)): CatalogFixture {
    val ids = CatalogFixture(
      categoryId = UUID.randomUUID(),
      productId = UUID.randomUUID(),
      variantId = UUID.randomUUID(),
      classicCrustId = UUID.randomUUID(),
      premiumCrustId = UUID.randomUUID(),
      toppingLeftId = UUID.randomUUID(),
      toppingRightId = UUID.randomUUID(),
      unavailableToppingId = UUID.randomUUID(),
      cheeseId = UUID.randomUUID(),
      inactiveCheeseId = UUID.randomUUID(),
      sauceId = UUID.randomUUID(),
      inactiveSauceId = UUID.randomUUID(),
      defaultStandardToppingId = UUID.randomUUID(),
      extraStandardToppingId = UUID.randomUUID(),
      secondExtraStandardToppingId = UUID.randomUUID(),
      halfAndHalfConfigId = UUID.randomUUID(),
    )
    dataSource.connection.use { conn ->
      conn.autoCommit = true
      conn.prepareStatement(
        "INSERT INTO categories (id, name, sort_order, is_active) VALUES (?, ?, 1, TRUE)",
      ).use { ps ->
        ps.setObject(1, ids.categoryId)
        ps.setString(2, "Phase8 Cat $label")
        ps.executeUpdate()
      }
      conn.prepareStatement(
        """INSERT INTO products (id, category_id, name, description, is_veg, is_active, is_taxable)
           VALUES (?, ?, ?, '', TRUE, TRUE, TRUE)""".trimIndent(),
      ).use { ps ->
        ps.setObject(1, ids.productId)
        ps.setObject(2, ids.categoryId)
        ps.setString(3, "Phase8 Pizza $label")
        ps.executeUpdate()
      }
      conn.prepareStatement(
        """INSERT INTO product_variants (id, product_id, size_code, price_paise, is_active)
           VALUES (?, ?, 'MEDIUM', 10000, TRUE)""".trimIndent(),
      ).use { ps ->
        ps.setObject(1, ids.variantId)
        ps.setObject(2, ids.productId)
        ps.executeUpdate()
      }
      insertCrust(conn, ids.classicCrustId, "Classic $label", 0L)
      insertCrust(conn, ids.premiumCrustId, "Thin $label", 1000L)
      insertTopping(conn, ids.toppingLeftId, "Left $label", 4000L)
      insertTopping(conn, ids.toppingRightId, "Right $label", 5000L)
      insertTopping(conn, ids.unavailableToppingId, "Not For This Product $label", 900L, isActive = true)

      // Only the first two toppings belong to this product; the third must be rejected.
      conn.prepareStatement(
        "INSERT INTO product_toppings (product_id, topping_id, sort_order) VALUES (?, ?, ?)",
      ).use { ps ->
        listOf(ids.toppingLeftId, ids.toppingRightId).forEachIndexed { index, toppingId ->
          ps.setObject(1, ids.productId)
          ps.setObject(2, toppingId)
          ps.setInt(3, index)
          ps.addBatch()
        }
        ps.executeBatch()
      }

      insertCheese(conn, ids.cheeseId, ids.productId, "Mozzarella $label", 2000L, true)
      insertCheese(conn, ids.inactiveCheeseId, ids.productId, "Old Cheese $label", 3000L, false)
      insertSauce(conn, ids.sauceId, ids.productId, "Tomato $label", 1500L, true)
      insertSauce(conn, ids.inactiveSauceId, ids.productId, "Old Sauce $label", 2500L, false)
      insertStandardTopping(conn, ids.defaultStandardToppingId, ids.productId, "Basil $label", 1000L, true, true)
      insertStandardTopping(conn, ids.extraStandardToppingId, ids.productId, "Olives $label", 1000L, false, true)
      insertStandardTopping(conn, ids.secondExtraStandardToppingId, ids.productId, "Jalapeno $label", 500L, false, true)

      conn.prepareStatement(
        """INSERT INTO half_and_half_configs
           (id, product_id, name, left_topping_ids, right_topping_ids, half_and_half_surcharge_paise, is_active, sort_order)
           VALUES (?, ?, ?, ?::jsonb, ?::jsonb, 1000, TRUE, 0)""".trimIndent(),
      ).use { ps ->
        ps.setObject(1, ids.halfAndHalfConfigId)
        ps.setObject(2, ids.productId)
        ps.setString(3, "Half & Half $label")
        ps.setString(4, "[\"${ids.toppingLeftId}\"]")
        ps.setString(5, "[\"${ids.toppingRightId}\"]")
        ps.executeUpdate()
      }
    }
    return ids
  }

  fun insertUser(
    dataSource: DataSource,
    email: String,
    fullName: String,
    password: String,
    passwordHasher: PasswordHasher,
    verified: Boolean = true,
  ): UUID {
    val userId = UUID.randomUUID()
    dataSource.connection.use { conn ->
      conn.autoCommit = true
      conn.prepareStatement(
        """INSERT INTO users (id, email, email_verified_at, password_hash, full_name, phone)
           VALUES (?, ?, ?, ?, ?, '9999999999')""".trimIndent(),
      ).use { ps ->
        ps.setObject(1, userId)
        ps.setString(2, email)
        if (verified) ps.setObject(3, java.time.OffsetDateTime.now()) else ps.setNull(3, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        ps.setString(4, passwordHasher.hash(password))
        ps.setString(5, fullName)
        ps.executeUpdate()
      }
    }
    return userId
  }

  data class OrderFixture(
    val orderId: UUID,
    val itemId: UUID,
  )

  /** Creates a minimal placed order with one item and all V4-V11 modifier columns populated. */
  fun seedOrder(
    dataSource: DataSource,
    customerId: UUID,
    productId: UUID,
    productName: String,
    variantSize: String = "MEDIUM",
    crustName: String = "Classic",
    toppingNames: List<String> = emptyList(),
    variantId: UUID? = null,
    crustId: UUID? = null,
    cheeseId: UUID? = null,
    sauceId: UUID? = null,
    toppingIds: List<UUID> = emptyList(),
    standardToppingIds: List<UUID> = emptyList(),
    standardToppingRemovedIds: List<UUID> = emptyList(),
    halfAndHalfConfigId: UUID? = null,
    halfAndHalfLeftToppingIds: List<UUID> = emptyList(),
    halfAndHalfRightToppingIds: List<UUID> = emptyList(),
    modifiersSnapshot: Map<String, String> = emptyMap(),
  ): OrderFixture {
    val orderId = UUID.randomUUID()
    val itemId = UUID.randomUUID()
    val unique = UUID.randomUUID().toString().take(8)
    val json = Json
    dataSource.connection.use { conn ->
      conn.autoCommit = true
      conn.prepareStatement(
        """
        INSERT INTO orders (id, order_number, customer_id, idempotency_key, request_hash,
                            fulfillment_type, schedule_type, status, version,
                            subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise,
                            gst_bps_snapshot)
        VALUES (?, ?, ?, ?, ?, 'PICKUP', 'ASAP', 'PLACED', 1, 10000, 0, 0, 0, 10000, 0)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, orderId)
        ps.setString(2, "P8-$unique")
        ps.setObject(3, customerId)
        ps.setString(4, "idem-$unique")
        ps.setString(5, "hash-$unique")
        ps.executeUpdate()
      }
      conn.prepareStatement(
        "INSERT INTO payments (id, order_id, method, status, amount_paise) VALUES (?, ?, 'COD', 'COD_PENDING', 10000)",
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setObject(2, orderId)
        ps.executeUpdate()
      }
      conn.prepareStatement(
        "INSERT INTO order_status_history (id, order_id, to_status) VALUES (?, ?, 'PLACED')",
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setObject(2, orderId)
        ps.executeUpdate()
      }
      conn.prepareStatement(
        """
        INSERT INTO order_items (
          id, order_id, product_id, product_name_snapshot, variant_size_snapshot, crust_name_snapshot,
          toppings_snapshot, unit_price_paise, quantity, line_total_paise,
          variant_id, crust_id, cheese_id, sauce_id, topping_ids, standard_topping_ids,
          standard_topping_removed_ids, half_and_half_config_id, half_and_half_left_topping_ids,
          half_and_half_right_topping_ids, modifiers_snapshot
        ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 1, 10000, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?::jsonb)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, itemId)
        ps.setObject(2, orderId)
        ps.setObject(3, productId)
        ps.setString(4, productName)
        ps.setString(5, variantSize)
        ps.setString(6, crustName)
        ps.setString(7, json.encodeToString(toppingNames))
        ps.setLong(8, 10_000L)
        setNullableUuid(ps, 9, variantId)
        setNullableUuid(ps, 10, crustId)
        setNullableUuid(ps, 11, cheeseId)
        setNullableUuid(ps, 12, sauceId)
        ps.setString(13, json.encodeToString(toppingIds.map(UUID::toString)))
        ps.setString(14, json.encodeToString(standardToppingIds.map(UUID::toString)))
        ps.setString(15, json.encodeToString(standardToppingRemovedIds.map(UUID::toString)))
        setNullableUuid(ps, 16, halfAndHalfConfigId)
        ps.setString(17, json.encodeToString(halfAndHalfLeftToppingIds.map(UUID::toString)))
        ps.setString(18, json.encodeToString(halfAndHalfRightToppingIds.map(UUID::toString)))
        ps.setString(19, json.encodeToString(modifiersSnapshot))
        ps.executeUpdate()
      }
    }
    return OrderFixture(orderId, itemId)
  }

  fun asJsonArray(vararg values: String): String = values.joinToString(",", "[", "]") { "\"$it\"" }

  suspend fun <T> withConnection(dataSource: DataSource, block: (Connection) -> T): T = withContext(Dispatchers.IO) {
    dataSource.connection.use { conn ->
      conn.autoCommit = true
      block(conn)
    }
  }

  private fun setNullableUuid(ps: java.sql.PreparedStatement, index: Int, value: UUID?) {
    if (value == null) ps.setNull(index, java.sql.Types.OTHER) else ps.setObject(index, value)
  }

  private fun insertCrust(conn: Connection, id: UUID, name: String, extraPaise: Long) {
    conn.prepareStatement(
      "INSERT INTO crust_options (id, name, extra_price_paise, is_active) VALUES (?, ?, ?, TRUE)",
    ).use { ps ->
      ps.setObject(1, id)
      ps.setString(2, name)
      ps.setLong(3, extraPaise)
      ps.executeUpdate()
    }
  }

  private fun insertTopping(conn: Connection, id: UUID, name: String, extraPaise: Long, isActive: Boolean = true) {
    conn.prepareStatement(
      "INSERT INTO toppings (id, name, is_veg, extra_price_paise, is_active) VALUES (?, ?, TRUE, ?, ?)",
    ).use { ps ->
      ps.setObject(1, id)
      ps.setString(2, name)
      ps.setLong(3, extraPaise)
      ps.setBoolean(4, isActive)
      ps.executeUpdate()
    }
  }

  private fun insertCheese(
    conn: Connection,
    id: UUID,
    productId: UUID,
    name: String,
    extraPaise: Long,
    isActive: Boolean,
  ) {
    conn.prepareStatement(
      """INSERT INTO cheeses (id, product_id, name, extra_price_paise, is_active, sort_order)
         VALUES (?, ?, ?, ?, ?, 0)""".trimIndent(),
    ).use { ps ->
      ps.setObject(1, id)
      ps.setObject(2, productId)
      ps.setString(3, name)
      ps.setLong(4, extraPaise)
      ps.setBoolean(5, isActive)
      ps.executeUpdate()
    }
  }

  private fun insertSauce(
    conn: Connection,
    id: UUID,
    productId: UUID,
    name: String,
    extraPaise: Long,
    isActive: Boolean,
  ) {
    conn.prepareStatement(
      """INSERT INTO sauces (id, product_id, name, extra_price_paise, is_active, sort_order)
         VALUES (?, ?, ?, ?, ?, 0)""".trimIndent(),
    ).use { ps ->
      ps.setObject(1, id)
      ps.setObject(2, productId)
      ps.setString(3, name)
      ps.setLong(4, extraPaise)
      ps.setBoolean(5, isActive)
      ps.executeUpdate()
    }
  }

  private fun insertStandardTopping(
    conn: Connection,
    id: UUID,
    productId: UUID,
    name: String,
    extraPaise: Long,
    isDefault: Boolean,
    isActive: Boolean,
  ) {
    conn.prepareStatement(
      """INSERT INTO standard_toppings
         (id, product_id, name, is_veg, extra_price_paise, is_default, is_active, sort_order)
         VALUES (?, ?, ?, TRUE, ?, ?, ?, 0)""".trimIndent(),
    ).use { ps ->
      ps.setObject(1, id)
      ps.setObject(2, productId)
      ps.setString(3, name)
      ps.setLong(4, extraPaise)
      ps.setBoolean(5, isDefault)
      ps.setBoolean(6, isActive)
      ps.executeUpdate()
    }
  }
}

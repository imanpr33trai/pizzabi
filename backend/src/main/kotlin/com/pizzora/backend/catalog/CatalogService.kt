package com.pizzora.backend.catalog

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.core.config.Phase8FeatureFlags
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ConflictException
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.modifiers.Phase8Schema
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheeseOptionDto
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.HalfAndHalfConfigDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductImageDto
import com.pizzora.contracts.ProductImageStatus
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.SauceOptionDto
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.StandardToppingDto
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UpdateCrustRequest
import com.pizzora.contracts.UpdateProductRequest
import com.pizzora.contracts.UpdateToppingRequest
import com.pizzora.contracts.UpdateVariantRequest
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class CatalogService(
  private val dataSource: DataSource,
  private val cacheStore: EphemeralCacheStore,
  private val publicUrlResolver: (String) -> String?,
  private val features: Phase8FeatureFlags = Phase8FeatureFlags(),
) {
  private val logger = LoggerFactory.getLogger(CatalogService::class.java)
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  suspend fun getPublicMenu(): PublicMenuResponse {
    // Flags are process-wide, so the cached payload already reflects the active flag set and
    // the stable key keeps existing cache invalidation (catalog:menu:v1) intact.
    val cacheKey = CatalogCacheKeys.PUBLIC_MENU
    val cached = runCatching { cacheStore.get(cacheKey) }
      .onFailure { logger.warn("Redis read failed for public menu; falling back to PostgreSQL", it) }
      .getOrNull()
    if (!cached.isNullOrBlank()) {
      runCatching { json.decodeFromString<PublicMenuResponse>(cached) }
        .onSuccess { return it }
        .onFailure { logger.warn("Ignoring malformed menu cache payload") }
    }

    val menu = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val categories = loadCategories(conn, activeOnly = true)
      val products = loadProducts(conn, activeOnly = true)
      val crusts = loadCrusts(conn, activeOnly = true)
      val toppings = loadToppings(conn, activeOnly = true)
      val settings = loadStoreSettings(conn)
      val schema = Phase8Schema.detect(conn)

      PublicMenuResponse(
        categories = categories,
        products = products,
        crusts = crusts,
        toppings = toppings,
        cheeses = loadGlobalCheeses(conn, schema),
        sauces = loadGlobalSauces(conn, schema),
        standardToppings = loadGlobalStandardToppings(conn, schema),
        halfAndHalfConfigs = loadGlobalHalfAndHalfConfigs(conn, schema),
        gstBps = settings.first,
        storeName = settings.second,
        isAcceptingOrders = settings.third,
      )
    }

    runCatching {
      cacheStore.setEx(cacheKey, CatalogCacheKeys.TTL_SECONDS, json.encodeToString(menu))
    }.onFailure {
      logger.warn("Redis write failed for public menu; continuing without cache", it)
    }
    return menu
  }

  suspend fun listAllProducts(): List<ProductDto> =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn -> loadProducts(conn, activeOnly = false) }

  suspend fun createProduct(request: CreateProductRequest, auditContext: AuditContext): ProductDto {
    val normalizedName = request.name.trim()
    if (normalizedName.isEmpty() || normalizedName.length > 120) {
      throw ValidationException("INVALID_PRODUCT_NAME", "Product name must be 1..120 characters.")
    }
    if (request.variants.isEmpty()) {
      throw ValidationException("MISSING_VARIANTS", "At least one product size variant is required.")
    }
    if (request.variants.map { it.sizeCode }.distinct().size != request.variants.size) {
      throw ValidationException("DUPLICATE_VARIANT_SIZE", "Each size_code can only appear once per product.")
    }
    request.variants.forEach { variant ->
      if (variant.pricePaise < 0L) {
        throw ValidationException("INVALID_PRICE", "Variant price_paise must be >= 0.")
      }
    }
    val categoryUuid = runCatching { UUID.fromString(request.categoryId) }
      .getOrElse { throw ValidationException("INVALID_CATEGORY_ID", "Invalid category_id UUID.") }

    val created = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val categoryExists = conn.prepareStatement("SELECT 1 FROM categories WHERE id = ?").use { ps ->
        ps.setObject(1, categoryUuid)
        ps.executeQuery().use { it.next() }
      }
      if (!categoryExists) {
        throw NotFoundException("CATEGORY_NOT_FOUND", "Category $categoryUuid was not found.")
      }

      val productId = UUID.randomUUID()
      conn.prepareStatement(
        """
        INSERT INTO products (id, category_id, name, description, is_veg, is_active, is_taxable)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, productId)
        ps.setObject(2, categoryUuid)
        ps.setString(3, normalizedName)
        ps.setString(4, request.description.trim())
        ps.setBoolean(5, request.isVeg)
        ps.setBoolean(6, request.isActive)
        ps.setBoolean(7, request.isTaxable)
        ps.executeUpdate()
      }

      request.variants.forEach { variant ->
        conn.prepareStatement(
          """
          INSERT INTO product_variants (id, product_id, size_code, price_paise, is_active)
          VALUES (?, ?, ?, ?, ?)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, UUID.randomUUID())
          ps.setObject(2, productId)
          ps.setString(3, variant.sizeCode.name)
          ps.setLong(4, variant.pricePaise)
          ps.setBoolean(5, variant.isActive)
          ps.executeUpdate()
        }
      }

      val dto = loadProductById(conn, productId)
      AuditLogger.record(
        conn = conn,
        context = auditContext,
        action = "PRODUCT_CREATED",
        entityType = "product",
        entityId = productId.toString(),
        afterJson = json.encodeToString(dto),
      )
      dto
    }

    CatalogCacheKeys.invalidateAll(cacheStore)
    return created
  }

  suspend fun updateProduct(productId: UUID, request: UpdateProductRequest, auditContext: AuditContext): ProductDto {
    val reqCategoryId = request.categoryId
    val reqName = request.name?.trim()
    val reqDesc = request.description?.trim()
    val reqIsVeg = request.isVeg
    val reqIsActive = request.isActive
    val reqIsTaxable = request.isTaxable

    if (reqName != null && (reqName.isEmpty() || reqName.length > 120)) {
      throw ValidationException("INVALID_PRODUCT_NAME", "Product name must be 1..120 characters.")
    }

    val updated = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val before = loadProductById(conn, productId)
      val nextCategoryId = if (reqCategoryId != null) {
        val parsed = runCatching { UUID.fromString(reqCategoryId) }
          .getOrElse { throw ValidationException("INVALID_CATEGORY_ID", "Invalid category_id UUID.") }
        val exists = conn.prepareStatement("SELECT 1 FROM categories WHERE id = ?").use { ps ->
          ps.setObject(1, parsed)
          ps.executeQuery().use { it.next() }
        }
        if (!exists) throw NotFoundException("CATEGORY_NOT_FOUND", "Category $parsed was not found.")
        parsed
      } else {
        UUID.fromString(before.categoryId)
      }

      conn.prepareStatement(
        """
        UPDATE products
        SET category_id = ?, name = ?, description = ?, is_veg = ?, is_active = ?, is_taxable = ?, updated_at = NOW()
        WHERE id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, nextCategoryId)
        ps.setString(2, reqName ?: before.name)
        ps.setString(3, reqDesc ?: before.description)
        ps.setBoolean(4, reqIsVeg ?: before.isVeg)
        ps.setBoolean(5, reqIsActive ?: before.isActive)
        ps.setBoolean(6, reqIsTaxable ?: before.isTaxable)
        ps.setObject(7, productId)
        ps.executeUpdate()
      }

      val after = loadProductById(conn, productId)
      AuditLogger.record(
        conn = conn,
        context = auditContext,
        action = "PRODUCT_UPDATED",
        entityType = "product",
        entityId = productId.toString(),
        beforeJson = json.encodeToString(before),
        afterJson = json.encodeToString(after),
      )
      after
    }

    CatalogCacheKeys.invalidateAll(cacheStore)
    return updated
  }

  suspend fun deleteProduct(productId: UUID, auditContext: AuditContext) {
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val before = loadProductById(conn, productId)
      val referenced = conn.prepareStatement(
        """
        SELECT (SELECT COUNT(*) FROM cart_items WHERE product_id = ?)
             + (SELECT COUNT(*) FROM order_items WHERE product_id = ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, productId)
        ps.setObject(2, productId)
        ps.executeQuery().use { rs ->
          rs.next()
          rs.getLong(1) > 0
        }
      }

      if (referenced) {
        conn.prepareStatement("UPDATE products SET is_active = FALSE, updated_at = NOW() WHERE id = ?").use { ps ->
          ps.setObject(1, productId)
          ps.executeUpdate()
        }
        AuditLogger.record(
          conn = conn,
          context = auditContext,
          action = "PRODUCT_DEACTIVATED",
          entityType = "product",
          entityId = productId.toString(),
          beforeJson = json.encodeToString(before),
        )
      } else {
        conn.prepareStatement("DELETE FROM products WHERE id = ?").use { ps ->
          ps.setObject(1, productId)
          ps.executeUpdate()
        }
        AuditLogger.record(
          conn = conn,
          context = auditContext,
          action = "PRODUCT_DELETED",
          entityType = "product",
          entityId = productId.toString(),
          beforeJson = json.encodeToString(before),
        )
      }
    }

    CatalogCacheKeys.invalidateAll(cacheStore)
  }

  suspend fun addVariant(
    productId: UUID,
    request: CreateVariantRequest,
    auditContext: AuditContext,
  ): ProductVariantDto {
    if (request.pricePaise < 0L) {
      throw ValidationException("INVALID_PRICE", "Variant price_paise must be >= 0.")
    }
    val created = try {
      DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
        val exists = conn.prepareStatement("SELECT 1 FROM products WHERE id = ?").use { ps ->
          ps.setObject(1, productId)
          ps.executeQuery().use { it.next() }
        }
        if (!exists) throw NotFoundException("PRODUCT_NOT_FOUND", "Product $productId was not found.")

        val id = UUID.randomUUID()
        val dto = conn.prepareStatement(
          """
          INSERT INTO product_variants (id, product_id, size_code, price_paise, is_active)
          VALUES (?, ?, ?, ?, ?)
          RETURNING id, product_id, size_code, price_paise, is_active
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, id)
          ps.setObject(2, productId)
          ps.setString(3, request.sizeCode.name)
          ps.setLong(4, request.pricePaise)
          ps.setBoolean(5, request.isActive)
          ps.executeQuery().use { rs ->
            rs.next()
            rs.toVariantDto()
          }
        }
        AuditLogger.record(
          conn = conn,
          context = auditContext,
          action = "VARIANT_CREATED",
          entityType = "product_variant",
          entityId = id.toString(),
          afterJson = json.encodeToString(dto),
        )
        dto
      }
    } catch (sqlEx: SQLException) {
      if (sqlEx.sqlState == "23505") {
        throw ConflictException("VARIANT_SIZE_EXISTS", "Size ${request.sizeCode.name} already exists on product $productId.")
      }
      throw sqlEx
    }

    CatalogCacheKeys.invalidateAll(cacheStore)
    return created
  }

  suspend fun updateVariant(
    variantId: UUID,
    request: UpdateVariantRequest,
    auditContext: AuditContext,
  ): ProductVariantDto {
    val reqPrice = request.pricePaise
    val reqActive = request.isActive
    if (reqPrice != null && reqPrice < 0L) {
      throw ValidationException("INVALID_PRICE", "Variant price_paise must be >= 0.")
    }

    val updated = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val before = conn.prepareStatement(
        "SELECT id, product_id, size_code, price_paise, is_active FROM product_variants WHERE id = ? FOR UPDATE",
      ).use { ps ->
        ps.setObject(1, variantId)
        ps.executeQuery().use { rs ->
          if (!rs.next()) throw NotFoundException("VARIANT_NOT_FOUND", "Variant $variantId was not found.")
          rs.toVariantDto()
        }
      }

      val saved = conn.prepareStatement(
        """
        UPDATE product_variants
        SET price_paise = ?, is_active = ?
        WHERE id = ?
        RETURNING id, product_id, size_code, price_paise, is_active
        """.trimIndent(),
      ).use { ps ->
        ps.setLong(1, reqPrice ?: before.pricePaise)
        ps.setBoolean(2, reqActive ?: before.isActive)
        ps.setObject(3, variantId)
        ps.executeQuery().use { rs ->
          rs.next()
          rs.toVariantDto()
        }
      }

      AuditLogger.record(
        conn = conn,
        context = auditContext,
        action = "VARIANT_UPDATED",
        entityType = "product_variant",
        entityId = variantId.toString(),
        beforeJson = json.encodeToString(before),
        afterJson = json.encodeToString(saved),
      )
      saved
    }

    CatalogCacheKeys.invalidateAll(cacheStore)
    return updated
  }

  suspend fun listAllCrusts(): List<CrustOptionDto> =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn -> loadCrusts(conn, activeOnly = false) }

  suspend fun createCrust(request: CreateCrustRequest, auditContext: AuditContext): CrustOptionDto {
    val name = request.name.trim()
    if (name.isEmpty()) throw ValidationException("INVALID_CRUST_NAME", "Crust name is required.")
    if (request.extraPricePaise < 0L) throw ValidationException("INVALID_PRICE", "extra_price_paise must be >= 0.")

    val created = try {
      DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
        val id = UUID.randomUUID()
        val dto = conn.prepareStatement(
          """
          INSERT INTO crust_options (id, name, extra_price_paise, is_active)
          VALUES (?, ?, ?, ?)
          RETURNING id, name, extra_price_paise, is_active
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, id)
          ps.setString(2, name)
          ps.setLong(3, request.extraPricePaise)
          ps.setBoolean(4, request.isActive)
          ps.executeQuery().use { rs -> rs.next(); rs.toCrustDto() }
        }
        AuditLogger.record(conn, auditContext, "CRUST_CREATED", "crust_option", id.toString(), afterJson = json.encodeToString(dto))
        dto
      }
    } catch (sqlEx: SQLException) {
      if (sqlEx.sqlState == "23505") throw ConflictException("CRUST_NAME_EXISTS", "Crust '$name' already exists.")
      throw sqlEx
    }

    CatalogCacheKeys.invalidateAll(cacheStore)
    return created
  }

  suspend fun updateCrust(crustId: UUID, request: UpdateCrustRequest, auditContext: AuditContext): CrustOptionDto {
    val reqName = request.name?.trim()
    val reqPrice = request.extraPricePaise
    val reqActive = request.isActive
    if (reqName != null && reqName.isEmpty()) throw ValidationException("INVALID_CRUST_NAME", "Crust name cannot be blank.")
    if (reqPrice != null && reqPrice < 0L) throw ValidationException("INVALID_PRICE", "extra_price_paise must be >= 0.")

    val updated = try {
      DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
        val before = conn.prepareStatement(
          "SELECT id, name, extra_price_paise, is_active FROM crust_options WHERE id = ? FOR UPDATE",
        ).use { ps ->
          ps.setObject(1, crustId)
          ps.executeQuery().use { rs ->
            if (!rs.next()) throw NotFoundException("CRUST_NOT_FOUND", "Crust $crustId was not found.")
            rs.toCrustDto()
          }
        }
        val saved = conn.prepareStatement(
          """
          UPDATE crust_options SET name = ?, extra_price_paise = ?, is_active = ? WHERE id = ?
          RETURNING id, name, extra_price_paise, is_active
          """.trimIndent(),
        ).use { ps ->
          ps.setString(1, reqName ?: before.name)
          ps.setLong(2, reqPrice ?: before.extraPricePaise)
          ps.setBoolean(3, reqActive ?: before.isActive)
          ps.setObject(4, crustId)
          ps.executeQuery().use { rs -> rs.next(); rs.toCrustDto() }
        }
        AuditLogger.record(conn, auditContext, "CRUST_UPDATED", "crust_option", crustId.toString(), json.encodeToString(before), json.encodeToString(saved))
        saved
      }
    } catch (sqlEx: SQLException) {
      if (sqlEx.sqlState == "23505") throw ConflictException("CRUST_NAME_EXISTS", "Crust '$reqName' already exists.")
      throw sqlEx
    }

    CatalogCacheKeys.invalidateAll(cacheStore)
    return updated
  }

  suspend fun listAllToppings(): List<ToppingDto> =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn -> loadToppings(conn, activeOnly = false) }

  suspend fun createTopping(request: CreateToppingRequest, auditContext: AuditContext): ToppingDto {
    val name = request.name.trim()
    if (name.isEmpty()) throw ValidationException("INVALID_TOPPING_NAME", "Topping name is required.")
    if (request.extraPricePaise < 0L) throw ValidationException("INVALID_PRICE", "extra_price_paise must be >= 0.")

    val created = try {
      DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
        val schema = Phase8Schema.detect(conn)
        val id = UUID.randomUUID()
        val dto = conn.prepareStatement(
          """
          INSERT INTO toppings (id, name, is_veg, extra_price_paise, is_active)
          VALUES (?, ?, ?, ?, ?)
          RETURNING id, name, is_veg, extra_price_paise, is_active
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, id)
          ps.setString(2, name)
          ps.setBoolean(3, request.isVeg)
          ps.setLong(4, request.extraPricePaise)
          ps.setBoolean(5, request.isActive)
          ps.executeQuery().use { rs -> rs.next(); rs.toToppingDto() }
        }
        // Preserve the pre-Phase-8 behavior that every topping is selectable on every product.
        if (schema.hasProductToppings) {
          conn.prepareStatement(
            """
            INSERT INTO product_toppings (product_id, topping_id, sort_order)
            SELECT id, ?, 0 FROM products
            ON CONFLICT (product_id, topping_id) DO NOTHING
            """.trimIndent(),
          ).use { ps ->
            ps.setObject(1, id)
            ps.executeUpdate()
          }
        }
        AuditLogger.record(conn, auditContext, "TOPPING_CREATED", "topping", id.toString(), afterJson = json.encodeToString(dto))
        dto
      }
    } catch (sqlEx: SQLException) {
      if (sqlEx.sqlState == "23505") throw ConflictException("TOPPING_NAME_EXISTS", "Topping '$name' already exists.")
      throw sqlEx
    }

    CatalogCacheKeys.invalidateAll(cacheStore)
    return created
  }

  suspend fun updateTopping(toppingId: UUID, request: UpdateToppingRequest, auditContext: AuditContext): ToppingDto {
    val reqName = request.name?.trim()
    val reqVeg = request.isVeg
    val reqPrice = request.extraPricePaise
    val reqActive = request.isActive
    if (reqName != null && reqName.isEmpty()) throw ValidationException("INVALID_TOPPING_NAME", "Topping name cannot be blank.")
    if (reqPrice != null && reqPrice < 0L) throw ValidationException("INVALID_PRICE", "extra_price_paise must be >= 0.")

    val updated = try {
      DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
        val before = conn.prepareStatement(
          "SELECT id, name, is_veg, extra_price_paise, is_active FROM toppings WHERE id = ? FOR UPDATE",
        ).use { ps ->
          ps.setObject(1, toppingId)
          ps.executeQuery().use { rs ->
            if (!rs.next()) throw NotFoundException("TOPPING_NOT_FOUND", "Topping $toppingId was not found.")
            rs.toToppingDto()
          }
        }
        val saved = conn.prepareStatement(
          """
          UPDATE toppings SET name = ?, is_veg = ?, extra_price_paise = ?, is_active = ? WHERE id = ?
          RETURNING id, name, is_veg, extra_price_paise, is_active
          """.trimIndent(),
        ).use { ps ->
          ps.setString(1, reqName ?: before.name)
          ps.setBoolean(2, reqVeg ?: before.isVeg)
          ps.setLong(3, reqPrice ?: before.extraPricePaise)
          ps.setBoolean(4, reqActive ?: before.isActive)
          ps.setObject(5, toppingId)
          ps.executeQuery().use { rs -> rs.next(); rs.toToppingDto() }
        }
        AuditLogger.record(conn, auditContext, "TOPPING_UPDATED", "topping", toppingId.toString(), json.encodeToString(before), json.encodeToString(saved))
        saved
      }
    } catch (sqlEx: SQLException) {
      if (sqlEx.sqlState == "23505") throw ConflictException("TOPPING_NAME_EXISTS", "Topping '$reqName' already exists.")
      throw sqlEx
    }

    CatalogCacheKeys.invalidateAll(cacheStore)
    return updated
  }

  private fun loadProductById(conn: Connection, productId: UUID): ProductDto {
    val all = loadProducts(conn, activeOnly = false, singleProductId = productId)
    return all.firstOrNull() ?: throw NotFoundException("PRODUCT_NOT_FOUND", "Product $productId was not found.")
  }

  private fun loadCategories(conn: Connection, activeOnly: Boolean): List<CategoryDto> {
    val sql = if (activeOnly) {
      "SELECT id, name, sort_order, is_active, created_at FROM categories WHERE is_active = TRUE ORDER BY sort_order ASC, name ASC"
    } else {
      "SELECT id, name, sort_order, is_active, created_at FROM categories ORDER BY sort_order ASC, name ASC"
    }
    return conn.prepareStatement(sql).use { ps ->
      ps.executeQuery().use { rs ->
        val out = mutableListOf<CategoryDto>()
        while (rs.next()) {
          out += CategoryDto(
            id = rs.getObject("id", UUID::class.java).toString(),
            name = rs.getString("name"),
            sortOrder = rs.getInt("sort_order"),
            isActive = rs.getBoolean("is_active"),
            createdAt = rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant().toString(),
          )
        }
        out.toList()
      }
    }
  }

  private fun loadProducts(
    conn: Connection,
    activeOnly: Boolean,
    singleProductId: UUID? = null,
  ): List<ProductDto> {
    val whereClauses = mutableListOf<String>()
    if (activeOnly) {
      whereClauses += "p.is_active = TRUE"
      whereClauses += "c.is_active = TRUE"
    }
    if (singleProductId != null) {
      whereClauses += "p.id = ?"
    }
    val whereSql = if (whereClauses.isEmpty()) "" else "WHERE ${whereClauses.joinToString(" AND ")}"

    val baseProducts = conn.prepareStatement(
      """
      SELECT p.id, p.category_id, c.name AS category_name, p.name, p.description, p.is_veg, p.is_active, p.is_taxable
      FROM products p
      JOIN categories c ON c.id = p.category_id
      $whereSql
      ORDER BY c.sort_order ASC, p.name ASC
      """.trimIndent(),
    ).use { ps ->
      if (singleProductId != null) ps.setObject(1, singleProductId)
      ps.executeQuery().use { rs ->
        val list = mutableListOf<ProductDto>()
        while (rs.next()) {
          list += ProductDto(
            id = rs.getObject("id", UUID::class.java).toString(),
            categoryId = rs.getObject("category_id", UUID::class.java).toString(),
            categoryName = rs.getString("category_name"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            isVeg = rs.getBoolean("is_veg"),
            isActive = rs.getBoolean("is_active"),
            isTaxable = rs.getBoolean("is_taxable"),
          )
        }
        list
      }
    }

    if (baseProducts.isEmpty()) return emptyList()

    val variantsByProduct = mutableMapOf<String, MutableList<ProductVariantDto>>()
    val variantWhere = if (activeOnly) "WHERE is_active = TRUE" else ""
    conn.prepareStatement(
      """
      SELECT id, product_id, size_code, price_paise, is_active
      FROM product_variants
      $variantWhere
      ORDER BY CASE size_code WHEN 'REGULAR' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END
      """.trimIndent(),
    ).use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val v = rs.toVariantDto()
          variantsByProduct.getOrPut(v.productId) { mutableListOf() } += v
        }
      }
    }

    val imagesByProduct = mutableMapOf<String, MutableList<ProductImageDto>>()
    val imageWhere = if (activeOnly) "WHERE status = 'ACTIVE'" else "WHERE status <> 'DELETED'"
    conn.prepareStatement(
      """
      SELECT id, product_id, object_key, mime_type, size_bytes, status, sort_order, created_at
      FROM product_images
      $imageWhere
      ORDER BY sort_order ASC, created_at ASC
      """.trimIndent(),
    ).use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val objectKey = rs.getString("object_key")
          val resolvedPublicUrl = publicUrlResolver(objectKey)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
          val img = ProductImageDto(
            id = rs.getObject("id", UUID::class.java).toString(),
            productId = rs.getObject("product_id", UUID::class.java).toString(),
            objectKey = objectKey,
            publicUrl = resolvedPublicUrl,
            mimeType = rs.getString("mime_type"),
            sizeBytes = rs.getLong("size_bytes"),
            status = ProductImageStatus.valueOf(rs.getString("status")),
            sortOrder = rs.getInt("sort_order"),
            createdAt = rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant().toString(),
          )
          imagesByProduct.getOrPut(img.productId) { mutableListOf() } += img
        }
      }
    }

    val schema = Phase8Schema.detect(conn)
    val cheesesByProduct = mutableMapOf<String, MutableList<CheeseOptionDto>>()
    if (schema.hasCheeses && features.cheese) {
      val where = if (activeOnly) "WHERE is_active = TRUE" else ""
      conn.prepareStatement(
        """
        SELECT id, product_id, name, extra_price_paise, is_active, sort_order
        FROM cheeses
        $where
        ORDER BY sort_order ASC, name ASC
        """.trimIndent(),
      ).use { ps ->
        ps.executeQuery().use { rs ->
          while (rs.next()) {
            val dto = CheeseOptionDto(
              id = rs.getObject("id", UUID::class.java).toString(),
              productId = rs.getObject("product_id", UUID::class.java).toString(),
              name = rs.getString("name"),
              extraPricePaise = rs.getLong("extra_price_paise"),
              isActive = rs.getBoolean("is_active"),
              sortOrder = rs.getInt("sort_order"),
            )
            cheesesByProduct.getOrPut(dto.productId) { mutableListOf() } += dto
          }
        }
      }
    }

    val saucesByProduct = mutableMapOf<String, MutableList<SauceOptionDto>>()
    if (schema.hasSauces && features.sauce) {
      val where = if (activeOnly) "WHERE is_active = TRUE" else ""
      conn.prepareStatement(
        """
        SELECT id, product_id, name, extra_price_paise, is_active, sort_order
        FROM sauces
        $where
        ORDER BY sort_order ASC, name ASC
        """.trimIndent(),
      ).use { ps ->
        ps.executeQuery().use { rs ->
          while (rs.next()) {
            val dto = SauceOptionDto(
              id = rs.getObject("id", UUID::class.java).toString(),
              productId = rs.getObject("product_id", UUID::class.java).toString(),
              name = rs.getString("name"),
              extraPricePaise = rs.getLong("extra_price_paise"),
              isActive = rs.getBoolean("is_active"),
              sortOrder = rs.getInt("sort_order"),
            )
            saucesByProduct.getOrPut(dto.productId) { mutableListOf() } += dto
          }
        }
      }
    }

    val standardToppingsByProduct = mutableMapOf<String, MutableList<StandardToppingDto>>()
    if (schema.hasStandardToppings && features.standardToppings) {
      val where = if (activeOnly) "WHERE is_active = TRUE" else ""
      conn.prepareStatement(
        """
        SELECT id, product_id, name, is_veg, extra_price_paise, is_default, is_active, sort_order
        FROM standard_toppings
        $where
        ORDER BY sort_order ASC, name ASC
        """.trimIndent(),
      ).use { ps ->
        ps.executeQuery().use { rs ->
          while (rs.next()) {
            val dto = StandardToppingDto(
              id = rs.getObject("id", UUID::class.java).toString(),
              productId = rs.getObject("product_id", UUID::class.java).toString(),
              name = rs.getString("name"),
              isVeg = rs.getBoolean("is_veg"),
              extraPricePaise = rs.getLong("extra_price_paise"),
              isDefault = rs.getBoolean("is_default"),
              isActive = rs.getBoolean("is_active"),
              sortOrder = rs.getInt("sort_order"),
            )
            standardToppingsByProduct.getOrPut(dto.productId) { mutableListOf() } += dto
          }
        }
      }
    }

    val halfAndHalfByProduct = mutableMapOf<String, MutableList<HalfAndHalfConfigDto>>()
    if (schema.hasHalfAndHalfConfigs && features.halfAndHalf) {
      val where = if (activeOnly) "WHERE is_active = TRUE" else ""
      conn.prepareStatement(
        """
        SELECT id, product_id, name, left_topping_ids, right_topping_ids,
               half_and_half_surcharge_paise, is_active, sort_order
        FROM half_and_half_configs
        $where
        ORDER BY sort_order ASC, name ASC
        """.trimIndent(),
      ).use { ps ->
        ps.executeQuery().use { rs ->
          while (rs.next()) {
            val dto = HalfAndHalfConfigDto(
              id = rs.getObject("id", UUID::class.java).toString(),
              productId = rs.getObject("product_id", UUID::class.java).toString(),
              name = rs.getString("name"),
              leftToppingIds = decodeIds(rs.getString("left_topping_ids")),
              rightToppingIds = decodeIds(rs.getString("right_topping_ids")),
              halfAndHalfSurchargePaise = rs.getLong("half_and_half_surcharge_paise"),
              isActive = rs.getBoolean("is_active"),
              sortOrder = rs.getInt("sort_order"),
            )
            halfAndHalfByProduct.getOrPut(dto.productId) { mutableListOf() } += dto
          }
        }
      }
    }

    return baseProducts.map { prod ->
      prod.copy(
        variants = variantsByProduct[prod.id].orEmpty(),
        images = imagesByProduct[prod.id].orEmpty(),
        cheeses = cheesesByProduct[prod.id].orEmpty(),
        sauces = saucesByProduct[prod.id].orEmpty(),
        standardToppings = standardToppingsByProduct[prod.id].orEmpty(),
        halfAndHalfConfigs = halfAndHalfByProduct[prod.id].orEmpty(),
      )
    }
  }

  private fun loadGlobalCheeses(conn: Connection, schema: Phase8Schema.Availability): List<CheeseOptionDto> {
    if (!schema.hasCheeses || !features.cheese) return emptyList()
    return conn.prepareStatement(
      """
      SELECT id, product_id, name, extra_price_paise, is_active, sort_order
      FROM cheeses
      WHERE is_active = TRUE
      ORDER BY sort_order ASC, name ASC
      """.trimIndent(),
    ).use { ps ->
      ps.executeQuery().use { rs ->
        val out = mutableListOf<CheeseOptionDto>()
        while (rs.next()) {
          out += CheeseOptionDto(
            id = rs.getObject("id", UUID::class.java).toString(),
            productId = rs.getObject("product_id", UUID::class.java).toString(),
            name = rs.getString("name"),
            extraPricePaise = rs.getLong("extra_price_paise"),
            isActive = rs.getBoolean("is_active"),
            sortOrder = rs.getInt("sort_order"),
          )
        }
        out.toList()
      }
    }
  }

  private fun loadGlobalSauces(conn: Connection, schema: Phase8Schema.Availability): List<SauceOptionDto> {
    if (!schema.hasSauces || !features.sauce) return emptyList()
    return conn.prepareStatement(
      """
      SELECT id, product_id, name, extra_price_paise, is_active, sort_order
      FROM sauces
      WHERE is_active = TRUE
      ORDER BY sort_order ASC, name ASC
      """.trimIndent(),
    ).use { ps ->
      ps.executeQuery().use { rs ->
        val out = mutableListOf<SauceOptionDto>()
        while (rs.next()) {
          out += SauceOptionDto(
            id = rs.getObject("id", UUID::class.java).toString(),
            productId = rs.getObject("product_id", UUID::class.java).toString(),
            name = rs.getString("name"),
            extraPricePaise = rs.getLong("extra_price_paise"),
            isActive = rs.getBoolean("is_active"),
            sortOrder = rs.getInt("sort_order"),
          )
        }
        out.toList()
      }
    }
  }

  private fun loadGlobalStandardToppings(
    conn: Connection,
    schema: Phase8Schema.Availability,
  ): List<StandardToppingDto> {
    if (!schema.hasStandardToppings || !features.standardToppings) return emptyList()
    return conn.prepareStatement(
      """
      SELECT id, product_id, name, is_veg, extra_price_paise, is_default, is_active, sort_order
      FROM standard_toppings
      WHERE is_active = TRUE
      ORDER BY sort_order ASC, name ASC
      """.trimIndent(),
    ).use { ps ->
      ps.executeQuery().use { rs ->
        val out = mutableListOf<StandardToppingDto>()
        while (rs.next()) {
          out += StandardToppingDto(
            id = rs.getObject("id", UUID::class.java).toString(),
            productId = rs.getObject("product_id", UUID::class.java).toString(),
            name = rs.getString("name"),
            isVeg = rs.getBoolean("is_veg"),
            extraPricePaise = rs.getLong("extra_price_paise"),
            isDefault = rs.getBoolean("is_default"),
            isActive = rs.getBoolean("is_active"),
            sortOrder = rs.getInt("sort_order"),
          )
        }
        out.toList()
      }
    }
  }

  private fun loadGlobalHalfAndHalfConfigs(
    conn: Connection,
    schema: Phase8Schema.Availability,
  ): List<HalfAndHalfConfigDto> {
    if (!schema.hasHalfAndHalfConfigs || !features.halfAndHalf) return emptyList()
    return conn.prepareStatement(
      """
      SELECT id, product_id, name, left_topping_ids, right_topping_ids,
             half_and_half_surcharge_paise, is_active, sort_order
      FROM half_and_half_configs
      WHERE is_active = TRUE
      ORDER BY sort_order ASC, name ASC
      """.trimIndent(),
    ).use { ps ->
      ps.executeQuery().use { rs ->
        val out = mutableListOf<HalfAndHalfConfigDto>()
        while (rs.next()) {
          out += HalfAndHalfConfigDto(
            id = rs.getObject("id", UUID::class.java).toString(),
            productId = rs.getObject("product_id", UUID::class.java).toString(),
            name = rs.getString("name"),
            leftToppingIds = decodeIds(rs.getString("left_topping_ids")),
            rightToppingIds = decodeIds(rs.getString("right_topping_ids")),
            halfAndHalfSurchargePaise = rs.getLong("half_and_half_surcharge_paise"),
            isActive = rs.getBoolean("is_active"),
            sortOrder = rs.getInt("sort_order"),
          )
        }
        out.toList()
      }
    }
  }

  private fun decodeIds(raw: String?): List<String> =
    if (raw.isNullOrBlank()) emptyList()
    else runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

  private fun loadCrusts(conn: Connection, activeOnly: Boolean): List<CrustOptionDto> {
    val where = if (activeOnly) "WHERE is_active = TRUE" else ""
    return conn.prepareStatement(
      "SELECT id, name, extra_price_paise, is_active FROM crust_options $where ORDER BY extra_price_paise ASC, name ASC",
    ).use { ps ->
      ps.executeQuery().use { rs ->
        val out = mutableListOf<CrustOptionDto>()
        while (rs.next()) out += rs.toCrustDto()
        out.toList()
      }
    }
  }

  private fun loadToppings(conn: Connection, activeOnly: Boolean): List<ToppingDto> {
    val where = if (activeOnly) "WHERE is_active = TRUE" else ""
    return conn.prepareStatement(
      "SELECT id, name, is_veg, extra_price_paise, is_active FROM toppings $where ORDER BY name ASC",
    ).use { ps ->
      ps.executeQuery().use { rs ->
        val out = mutableListOf<ToppingDto>()
        while (rs.next()) out += rs.toToppingDto()
        out.toList()
      }
    }
  }

  private fun loadStoreSettings(conn: Connection): Triple<Int, String, Boolean> =
    conn.prepareStatement(
      "SELECT gst_bps, store_name, is_accepting_orders FROM restaurant_settings WHERE id = 1",
    ).use { ps ->
      ps.executeQuery().use { rs ->
        if (rs.next()) {
          Triple(rs.getInt("gst_bps"), rs.getString("store_name"), rs.getBoolean("is_accepting_orders"))
        } else {
          Triple(500, "PIZZORA Artisanal Kitchen", true)
        }
      }
    }

  private fun java.sql.ResultSet.toVariantDto(): ProductVariantDto =
    ProductVariantDto(
      id = getObject("id", UUID::class.java).toString(),
      productId = getObject("product_id", UUID::class.java).toString(),
      sizeCode = SizeCode.valueOf(getString("size_code")),
      pricePaise = getLong("price_paise"),
      isActive = getBoolean("is_active"),
    )

  private fun java.sql.ResultSet.toCrustDto(): CrustOptionDto =
    CrustOptionDto(
      id = getObject("id", UUID::class.java).toString(),
      name = getString("name"),
      extraPricePaise = getLong("extra_price_paise"),
      isActive = getBoolean("is_active"),
    )

  private fun java.sql.ResultSet.toToppingDto(): ToppingDto =
    ToppingDto(
      id = getObject("id", UUID::class.java).toString(),
      name = getString("name"),
      isVeg = getBoolean("is_veg"),
      extraPricePaise = getLong("extra_price_paise"),
      isActive = getBoolean("is_active"),
    )
}

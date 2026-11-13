package com.pizzora.backend.categories

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.catalog.CatalogCacheKeys
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ConflictException
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.UpdateCategoryRequest
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class CategoriesService(
  private val dataSource: DataSource,
  private val cacheStore: EphemeralCacheStore,
) {
  private val logger = LoggerFactory.getLogger(CategoriesService::class.java)
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  suspend fun listPublicCategories(): List<CategoryDto> {
    val cached = cacheStore.get(CatalogCacheKeys.PUBLIC_CATEGORIES)
    if (!cached.isNullOrBlank()) {
      runCatching { json.decodeFromString<List<CategoryDto>>(cached) }
        .onSuccess { return it }
        .onFailure { logger.warn("Ignoring malformed category cache payload") }
    }

    val categories = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        SELECT id, name, sort_order, is_active, created_at
        FROM categories
        WHERE is_active = TRUE
        ORDER BY sort_order ASC, name ASC
        """.trimIndent(),
      ).use { ps ->
        ps.executeQuery().use { rs ->
          val list = mutableListOf<CategoryDto>()
          while (rs.next()) {
            list += rs.toCategoryDto()
          }
          list.toList()
        }
      }
    }

    cacheStore.setEx(
      CatalogCacheKeys.PUBLIC_CATEGORIES,
      CatalogCacheKeys.TTL_SECONDS,
      json.encodeToString(categories),
    )
    return categories
  }

  suspend fun listAllCategories(): List<CategoryDto> =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        SELECT id, name, sort_order, is_active, created_at
        FROM categories
        ORDER BY sort_order ASC, name ASC
        """.trimIndent(),
      ).use { ps ->
        ps.executeQuery().use { rs ->
          val list = mutableListOf<CategoryDto>()
          while (rs.next()) {
            list += rs.toCategoryDto()
          }
          list.toList()
        }
      }
    }

  suspend fun createCategory(request: CreateCategoryRequest, auditContext: AuditContext): CategoryDto {
    val normalizedName = request.name.trim()
    if (normalizedName.isEmpty() || normalizedName.length > 80) {
      throw ValidationException("INVALID_CATEGORY_NAME", "Category name must be between 1 and 80 characters.")
    }

    val created = try {
      DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
        val id = UUID.randomUUID()
        val inserted = conn.prepareStatement(
          """
          INSERT INTO categories (id, name, sort_order, is_active)
          VALUES (?, ?, ?, ?)
          RETURNING id, name, sort_order, is_active, created_at
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, id)
          ps.setString(2, normalizedName)
          ps.setInt(3, request.sortOrder)
          ps.setBoolean(4, request.isActive)
          ps.executeQuery().use { rs ->
            rs.next()
            rs.toCategoryDto()
          }
        }
        AuditLogger.record(
          conn = conn,
          context = auditContext,
          action = "CATEGORY_CREATED",
          entityType = "category",
          entityId = inserted.id,
          afterJson = json.encodeToString(inserted),
        )
        inserted
      }
    } catch (sqlEx: SQLException) {
      if (sqlEx.sqlState == "23505") {
        throw ConflictException("CATEGORY_NAME_EXISTS", "A category named '$normalizedName' already exists.")
      }
      throw sqlEx
    }

    CatalogCacheKeys.invalidateAll(cacheStore)
    return created
  }

  suspend fun updateCategory(
    categoryId: UUID,
    request: UpdateCategoryRequest,
    auditContext: AuditContext,
  ): CategoryDto {
    val reqName = request.name?.trim()
    if (reqName != null && (reqName.isEmpty() || reqName.length > 80)) {
      throw ValidationException("INVALID_CATEGORY_NAME", "Category name must be between 1 and 80 characters.")
    }

    val updated = try {
      DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
        val existing = conn.prepareStatement(
          "SELECT id, name, sort_order, is_active, created_at FROM categories WHERE id = ? FOR UPDATE",
        ).use { ps ->
          ps.setObject(1, categoryId)
          ps.executeQuery().use { rs ->
            if (!rs.next()) {
              throw NotFoundException("CATEGORY_NOT_FOUND", "Category $categoryId was not found.")
            }
            rs.toCategoryDto()
          }
        }

        val nextName = reqName ?: existing.name
        val nextSortOrder = request.sortOrder ?: existing.sortOrder
        val nextIsActive = request.isActive ?: existing.isActive

        val saved = conn.prepareStatement(
          """
          UPDATE categories
          SET name = ?, sort_order = ?, is_active = ?
          WHERE id = ?
          RETURNING id, name, sort_order, is_active, created_at
          """.trimIndent(),
        ).use { ps ->
          ps.setString(1, nextName)
          ps.setInt(2, nextSortOrder)
          ps.setBoolean(3, nextIsActive)
          ps.setObject(4, categoryId)
          ps.executeQuery().use { rs ->
            rs.next()
            rs.toCategoryDto()
          }
        }

        AuditLogger.record(
          conn = conn,
          context = auditContext,
          action = "CATEGORY_UPDATED",
          entityType = "category",
          entityId = categoryId.toString(),
          beforeJson = json.encodeToString(existing),
          afterJson = json.encodeToString(saved),
        )
        saved
      }
    } catch (sqlEx: SQLException) {
      if (sqlEx.sqlState == "23505") {
        throw ConflictException("CATEGORY_NAME_EXISTS", "A category named '$reqName' already exists.")
      }
      throw sqlEx
    }

    CatalogCacheKeys.invalidateAll(cacheStore)
    return updated
  }

  suspend fun deleteCategory(categoryId: UUID, auditContext: AuditContext) {
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val existing = conn.prepareStatement(
        "SELECT id, name, sort_order, is_active, created_at FROM categories WHERE id = ? FOR UPDATE",
      ).use { ps ->
        ps.setObject(1, categoryId)
        ps.executeQuery().use { rs ->
          if (!rs.next()) {
            throw NotFoundException("CATEGORY_NOT_FOUND", "Category $categoryId was not found.")
          }
          rs.toCategoryDto()
        }
      }

      val referencedProducts = conn.prepareStatement(
        "SELECT COUNT(*) FROM products WHERE category_id = ?",
      ).use { ps ->
        ps.setObject(1, categoryId)
        ps.executeQuery().use { rs ->
          rs.next()
          rs.getLong(1)
        }
      }
      if (referencedProducts > 0) {
        throw ConflictException(
          "CATEGORY_IN_USE",
          "Cannot delete category '${existing.name}' because $referencedProducts product(s) reference it.",
        )
      }

      conn.prepareStatement("DELETE FROM categories WHERE id = ?").use { ps ->
        ps.setObject(1, categoryId)
        ps.executeUpdate()
      }

      AuditLogger.record(
        conn = conn,
        context = auditContext,
        action = "CATEGORY_DELETED",
        entityType = "category",
        entityId = categoryId.toString(),
        beforeJson = json.encodeToString(existing),
      )
    }

    CatalogCacheKeys.invalidateAll(cacheStore)
  }

  private fun java.sql.ResultSet.toCategoryDto(): CategoryDto =
    CategoryDto(
      id = getObject("id", UUID::class.java).toString(),
      name = getString("name"),
      sortOrder = getInt("sort_order"),
      isActive = getBoolean("is_active"),
      createdAt = getObject("created_at", java.time.OffsetDateTime::class.java).toInstant().toString(),
    )
}

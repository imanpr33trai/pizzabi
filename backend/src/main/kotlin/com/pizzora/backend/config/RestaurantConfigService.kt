package com.pizzora.backend.config

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.catalog.CatalogCacheKeys
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.contracts.PublicConfigResponse
import com.pizzora.contracts.RestaurantSettingsDto
import com.pizzora.contracts.UpdateRestaurantSettingsRequest
import java.sql.Connection
import java.time.OffsetDateTime
import javax.sql.DataSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

class RestaurantConfigService(
  private val dataSource: DataSource,
  private val cacheStore: EphemeralCacheStore,
) {
  private val logger = LoggerFactory.getLogger(RestaurantConfigService::class.java)
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  suspend fun getPublicConfig(): PublicConfigResponse {
    val cached = runCatching { cacheStore.get(PUBLIC_CONFIG_CACHE_KEY) }
      .onFailure { logger.warn("Redis read failed for public config; falling back to PostgreSQL", it) }
      .getOrNull()

    if (!cached.isNullOrBlank()) {
      runCatching { json.decodeFromString<PublicConfigResponse>(cached) }.getOrNull()?.let { return it }
    }

    val settings = DatabaseFactory.withJdbcTransaction(dataSource) { conn -> loadSettings(conn) }
    val response = PublicConfigResponse(
      storeName = settings.storeName,
      isAcceptingOrders = settings.isAcceptingOrders,
      gstBps = settings.gstBps,
      defaultPrepMins = settings.defaultPrepMins,
      timezone = settings.timezone,
    )
    runCatching {
      cacheStore.setEx(PUBLIC_CONFIG_CACHE_KEY, CACHE_TTL_SECONDS, json.encodeToString(response))
    }
    return response
  }

  suspend fun getOwnerSettings(): RestaurantSettingsDto =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn -> loadSettings(conn) }

  suspend fun updateOwnerSettings(
    request: UpdateRestaurantSettingsRequest,
    audit: AuditContext,
  ): RestaurantSettingsDto {
    val requestedStoreName = request.storeName?.trim()
    val requestedGstBps = request.gstBps
    val requestedPrepMins = request.defaultPrepMins

    if (requestedStoreName != null && requestedStoreName.length !in 2..120) {
      throw ValidationException("INVALID_STORE_NAME", "Store name must be between 2 and 120 characters.")
    }
    if (requestedGstBps != null && requestedGstBps !in 0..2800) {
      throw ValidationException("INVALID_GST_BPS", "GST rate must be between 0 and 2800 bps (0% - 28%).")
    }
    if (requestedPrepMins != null && requestedPrepMins !in 1..180) {
      throw ValidationException("INVALID_PREP_MINS", "Default prep SLA minutes must be between 1 and 180.")
    }

    val updated = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val current = loadSettings(conn, forUpdate = true)
      val nextStoreName = requestedStoreName ?: current.storeName
      val nextAccepting = request.isAcceptingOrders ?: current.isAcceptingOrders
      val nextGstBps = requestedGstBps ?: current.gstBps
      val nextPrepMins = requestedPrepMins ?: current.defaultPrepMins

      val updatedRow = conn.prepareStatement(
        """
        UPDATE restaurant_settings
        SET store_name = ?,
            is_accepting_orders = ?,
            gst_bps = ?,
            default_prep_mins = ?,
            updated_at = NOW()
        WHERE id = 1
        RETURNING store_name, is_accepting_orders, gst_bps, default_prep_mins, timezone, updated_at
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, nextStoreName)
        ps.setBoolean(2, nextAccepting)
        ps.setInt(3, nextGstBps)
        ps.setInt(4, nextPrepMins)
        ps.executeQuery().use { rs ->
          rs.next()
          RestaurantSettingsDto(
            storeName = rs.getString("store_name"),
            isAcceptingOrders = rs.getBoolean("is_accepting_orders"),
            gstBps = rs.getInt("gst_bps"),
            defaultPrepMins = rs.getInt("default_prep_mins"),
            timezone = rs.getString("timezone"),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant().toString(),
          )
        }
      }

      val beforeJson = buildJsonObject {
        put("store_name", current.storeName)
        put("is_accepting_orders", current.isAcceptingOrders)
        put("gst_bps", current.gstBps)
        put("default_prep_mins", current.defaultPrepMins)
      }.toString()
      val afterJson = buildJsonObject {
        put("store_name", updatedRow.storeName)
        put("is_accepting_orders", updatedRow.isAcceptingOrders)
        put("gst_bps", updatedRow.gstBps)
        put("default_prep_mins", updatedRow.defaultPrepMins)
      }.toString()

      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "RESTAURANT_SETTINGS_UPDATED",
        entityType = "RESTAURANT_SETTINGS",
        entityId = "1",
        beforeJson = beforeJson,
        afterJson = afterJson,
      )

      updatedRow
    }

    runCatching { cacheStore.del(PUBLIC_CONFIG_CACHE_KEY) }
    runCatching { CatalogCacheKeys.invalidateAll(cacheStore) }
    return updated
  }

  fun loadSettings(conn: Connection, forUpdate: Boolean = false): RestaurantSettingsDto {
    val suffix = if (forUpdate) " FOR UPDATE" else ""
    return conn.prepareStatement(
      """
      SELECT store_name, is_accepting_orders, gst_bps, default_prep_mins, timezone, updated_at
      FROM restaurant_settings
      WHERE id = 1$suffix
      """.trimIndent(),
    ).use { ps ->
      ps.executeQuery().use { rs ->
        if (!rs.next()) {
          RestaurantSettingsDto(
            storeName = "PIZZORA Artisanal Kitchen",
            isAcceptingOrders = true,
            gstBps = 500,
            defaultPrepMins = 20,
            timezone = "Asia/Kolkata",
            updatedAt = java.time.Instant.now().toString(),
          )
        } else {
          RestaurantSettingsDto(
            storeName = rs.getString("store_name"),
            isAcceptingOrders = rs.getBoolean("is_accepting_orders"),
            gstBps = rs.getInt("gst_bps"),
            defaultPrepMins = rs.getInt("default_prep_mins"),
            timezone = rs.getString("timezone"),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant().toString(),
          )
        }
      }
    }
  }

  companion object {
    const val PUBLIC_CONFIG_CACHE_KEY = "config:public:v1"
    const val CACHE_TTL_SECONDS = 60L
  }
}

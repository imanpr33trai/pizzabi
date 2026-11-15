package com.pizzora.backend.catalog

import com.pizzora.backend.core.redis.EphemeralCacheStore
import org.slf4j.LoggerFactory

object CatalogCacheKeys {
  const val PUBLIC_MENU = "catalog:menu:v1"
  const val PUBLIC_CATEGORIES = "catalog:categories:v1"
  const val TTL_SECONDS = 120L

  private val logger = LoggerFactory.getLogger(CatalogCacheKeys::class.java)

  suspend fun invalidateAll(cacheStore: EphemeralCacheStore) {
    runCatching { cacheStore.del(PUBLIC_MENU) }
      .onFailure { logger.warn("Redis DEL failed for key={}", PUBLIC_MENU, it) }
    runCatching { cacheStore.del(PUBLIC_CATEGORIES) }
      .onFailure { logger.warn("Redis DEL failed for key={}", PUBLIC_CATEGORIES, it) }
  }
}

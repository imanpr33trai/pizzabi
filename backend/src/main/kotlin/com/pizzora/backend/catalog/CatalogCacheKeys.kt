package com.pizzora.backend.catalog

import com.pizzora.backend.core.redis.EphemeralCacheStore

object CatalogCacheKeys {
  const val PUBLIC_MENU = "catalog:menu:v1"
  const val PUBLIC_CATEGORIES = "catalog:categories:v1"
  const val TTL_SECONDS = 120L

  suspend fun invalidateAll(cacheStore: EphemeralCacheStore) {
    cacheStore.del(PUBLIC_MENU)
    cacheStore.del(PUBLIC_CATEGORIES)
  }
}

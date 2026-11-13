package com.example.catalog

import com.example.core.database.CachedProductEntity
import com.example.core.database.CatalogCacheDao
import com.example.core.network.PizzoraApiClient
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.PublicMenuResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

data class CatalogSnapshot(
  val menu: PublicMenuResponse?,
  val cachedProducts: List<ProductDto>,
  val isFromOfflineCache: Boolean,
)

interface CatalogRepository {
  fun observeCachedProducts(): Flow<List<ProductDto>>
  suspend fun refreshPublicMenu(): CatalogSnapshot
}

class NetworkCatalogRepository(
  private val apiClient: PizzoraApiClient,
  private val catalogCacheDao: CatalogCacheDao,
) : CatalogRepository {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  override fun observeCachedProducts(): Flow<List<ProductDto>> =
    catalogCacheDao.observeCachedProducts().map { entities ->
      entities.mapNotNull { entity ->
        runCatching { json.decodeFromString<ProductDto>(entity.payloadJson) }.getOrNull()
      }
    }

  override suspend fun refreshPublicMenu(): CatalogSnapshot {
    return try {
      val menu = apiClient.getPublicMenu()
      val now = System.currentTimeMillis()
      val entities = menu.products.map { product ->
        CachedProductEntity(
          id = product.id,
          categoryId = product.categoryId,
          categoryName = product.categoryName,
          name = product.name,
          description = product.description,
          isVeg = product.isVeg,
          imageUrl = product.images.firstOrNull()?.publicUrl,
          minPricePaise = product.variants.minOfOrNull { it.pricePaise } ?: 0L,
          payloadJson = json.encodeToString(product),
          cachedAtMs = now,
        )
      }
      catalogCacheDao.replaceCatalog(entities)
      CatalogSnapshot(
        menu = menu,
        cachedProducts = menu.products,
        isFromOfflineCache = false,
      )
    } catch (t: Throwable) {
      val offline = observeCachedProducts().first()
      if (offline.isNotEmpty()) {
        CatalogSnapshot(
          menu = null,
          cachedProducts = offline,
          isFromOfflineCache = true,
        )
      } else {
        throw t
      }
    }
  }
}

package com.example.catalog

import com.example.core.database.CachedMenuSnapshotEntity
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
      val snapshotEntity = CachedMenuSnapshotEntity(
        id = 1,
        payloadJson = json.encodeToString(menu),
        cachedAtMs = now,
      )
      catalogCacheDao.replaceCatalogWithSnapshot(entities, snapshotEntity)
      CatalogSnapshot(
        menu = menu,
        cachedProducts = menu.products,
        isFromOfflineCache = false,
      )
    } catch (t: Throwable) {
      val cachedSnapshot = catalogCacheDao.getMenuSnapshot()?.let { entity ->
        runCatching { json.decodeFromString<PublicMenuResponse>(entity.payloadJson) }.getOrNull()
      }
      val offlineProducts = cachedSnapshot?.products ?: observeCachedProducts().first()
      if (cachedSnapshot != null || offlineProducts.isNotEmpty()) {
        CatalogSnapshot(
          menu = cachedSnapshot,
          cachedProducts = offlineProducts,
          isFromOfflineCache = true,
        )
      } else {
        throw t
      }
    }
  }
}

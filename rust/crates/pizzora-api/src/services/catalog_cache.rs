use pizzora_db::EphemeralCacheStore;

pub const MENU_CACHE_KEY: &str = "catalog:menu:v1";
pub const CATEGORIES_CACHE_KEY: &str = "catalog:categories:v1";
pub const SETTINGS_PUBLIC_CACHE_KEY: &str = "config:public:v1";
pub const CATALOG_TTL_SECS: u64 = 30;
pub const CONFIG_TTL_SECS: u64 = 15;

pub async fn invalidate_catalog_cache(cache: &dyn EphemeralCacheStore) {
    cache.delete(MENU_CACHE_KEY).await;
    cache.delete(CATEGORIES_CACHE_KEY).await;
}

pub async fn invalidate_all_public_caches(cache: &dyn EphemeralCacheStore) {
    invalidate_catalog_cache(cache).await;
    cache.delete(SETTINGS_PUBLIC_CACHE_KEY).await;
}

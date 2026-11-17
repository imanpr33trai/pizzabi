use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use redis::aio::ConnectionManager;
use redis::AsyncCommands as _;
use tokio::time::timeout;
use tracing::warn;

use crate::error::DbError;

pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

pub trait EphemeralCacheStore: Send + Sync {
    fn set_ex<'a>(&'a self, key: &'a str, ttl_seconds: u64, value: &'a str) -> BoxFuture<'a, bool>;

    fn get<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Option<String>>;

    fn get_del<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Option<String>>;

    fn del<'a>(&'a self, key: &'a str) -> BoxFuture<'a, bool>;

    fn increment_within_window<'a>(
        &'a self,
        key: &'a str,
        window_seconds: u64,
    ) -> BoxFuture<'a, Option<i64>>;

    fn publish<'a>(&'a self, channel: &'a str, message: &'a str) -> BoxFuture<'a, bool>;
}

pub struct RedisStore {
    client: Option<redis::Client>,
    manager: tokio::sync::Mutex<Option<ConnectionManager>>,
    timeout: Duration,
}

impl RedisStore {
    pub fn new(redis_url: &str, timeout_ms: u64) -> Self {
        let client = match redis::Client::open(redis_url) {
            Ok(c) => Some(c),
            Err(err) => {
                warn!("Failed to initialize Redis client; PostgreSQL/in-memory fallbacks remain active: {err}");
                None
            }
        };
        Self {
            client,
            manager: tokio::sync::Mutex::new(None),
            timeout: Duration::from_millis(timeout_ms),
        }
    }

    pub async fn connect_now(&self) -> Result<(), DbError> {
        let _ = self
            .get_or_connect()
            .await
            .ok_or_else(|| DbError::Cache("Redis connection unavailable".to_owned()))?;
        Ok(())
    }

    async fn get_or_connect(&self) -> Option<ConnectionManager> {
        let client = self.client.as_ref()?;
        let mut guard = self.manager.lock().await;
        if let Some(existing) = guard.as_ref() {
            return Some(existing.clone());
        }
        match timeout(self.timeout, ConnectionManager::new(client.clone())).await {
            Ok(Ok(conn)) => {
                *guard = Some(conn.clone());
                Some(conn)
            }
            Ok(Err(err)) => {
                warn!("Redis unavailable; relying on PostgreSQL/in-memory fallback: {err}");
                None
            }
            Err(_) => {
                warn!("Redis connect timed out; relying on PostgreSQL/in-memory fallback");
                None
            }
        }
    }

    pub fn client(&self) -> Option<&redis::Client> {
        self.client.as_ref()
    }
}

impl EphemeralCacheStore for RedisStore {
    fn set_ex<'a>(&'a self, key: &'a str, ttl_seconds: u64, value: &'a str) -> BoxFuture<'a, bool> {
        Box::pin(async move {
            let Some(mut conn) = self.get_or_connect().await else {
                return false;
            };
            match timeout(
                self.timeout,
                conn.set_ex::<_, _, ()>(key, value, ttl_seconds),
            )
            .await
            {
                Ok(Ok(())) => true,
                Ok(Err(err)) => {
                    warn!(key, "Redis SETEX failed: {err}");
                    false
                }
                Err(_) => {
                    warn!(key, "Redis SETEX timed out");
                    false
                }
            }
        })
    }

    fn get<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Option<String>> {
        Box::pin(async move {
            let mut conn = self.get_or_connect().await?;
            match timeout(self.timeout, conn.get::<_, Option<String>>(key)).await {
                Ok(Ok(v)) => v,
                Ok(Err(err)) => {
                    warn!(key, "Redis GET failed: {err}");
                    None
                }
                Err(_) => {
                    warn!(key, "Redis GET timed out");
                    None
                }
            }
        })
    }

    fn get_del<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Option<String>> {
        Box::pin(async move {
            let mut conn = self.get_or_connect().await?;
            match timeout(self.timeout, conn.get_del::<_, Option<String>>(key)).await {
                Ok(Ok(v)) => v,
                Ok(Err(err)) => {
                    warn!(key, "Redis GETDEL failed: {err}");
                    None
                }
                Err(_) => {
                    warn!(key, "Redis GETDEL timed out");
                    None
                }
            }
        })
    }

    fn del<'a>(&'a self, key: &'a str) -> BoxFuture<'a, bool> {
        Box::pin(async move {
            let Some(mut conn) = self.get_or_connect().await else {
                return false;
            };
            match timeout(self.timeout, conn.del::<_, i64>(key)).await {
                Ok(Ok(removed)) => removed > 0,
                Ok(Err(err)) => {
                    warn!(key, "Redis DEL failed: {err}");
                    false
                }
                Err(_) => {
                    warn!(key, "Redis DEL timed out");
                    false
                }
            }
        })
    }

    fn increment_within_window<'a>(
        &'a self,
        key: &'a str,
        window_seconds: u64,
    ) -> BoxFuture<'a, Option<i64>> {
        Box::pin(async move {
            let mut conn = self.get_or_connect().await?;
            let res: Result<i64, redis::RedisError> = match timeout(self.timeout, async {
                let count: i64 = conn.incr(key, 1i64).await?;
                if count == 1 {
                    let _: bool = conn.expire(key, window_seconds as i64).await?;
                }
                Ok(count)
            })
            .await
            {
                Ok(r) => r,
                Err(_) => {
                    warn!(key, "Redis INCR timed out");
                    return None;
                }
            };

            match res {
                Ok(v) => Some(v),
                Err(err) => {
                    warn!(key, "Redis INCR failed: {err}");
                    None
                }
            }
        })
    }

    fn publish<'a>(&'a self, channel: &'a str, message: &'a str) -> BoxFuture<'a, bool> {
        Box::pin(async move {
            let Some(mut conn) = self.get_or_connect().await else {
                return false;
            };
            match timeout(self.timeout, conn.publish::<_, _, i64>(channel, message)).await {
                Ok(Ok(_)) => true,
                Ok(Err(err)) => {
                    warn!(channel, "Redis PUBLISH failed: {err}");
                    false
                }
                Err(_) => {
                    warn!(channel, "Redis PUBLISH timed out");
                    false
                }
            }
        })
    }
}

#[derive(Debug, Default)]
pub struct InMemoryCacheStore {
    values: Arc<Mutex<HashMap<String, (String, Instant)>>>,
    windows: Arc<Mutex<HashMap<String, (i64, Instant)>>>,
    fail_all_ops: Arc<std::sync::atomic::AtomicBool>,
}

impl InMemoryCacheStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn set_unavailable(&self, unavailable: bool) {
        self.fail_all_ops
            .store(unavailable, std::sync::atomic::Ordering::SeqCst);
    }
}

impl EphemeralCacheStore for InMemoryCacheStore {
    fn set_ex<'a>(&'a self, key: &'a str, ttl_seconds: u64, value: &'a str) -> BoxFuture<'a, bool> {
        Box::pin(async move {
            if self.fail_all_ops.load(std::sync::atomic::Ordering::SeqCst) {
                return false;
            }
            let expires_at = Instant::now() + Duration::from_secs(ttl_seconds);
            self.values
                .lock()
                .expect("in-memory cache mutex poisoned")
                .insert(key.to_owned(), (value.to_owned(), expires_at));
            true
        })
    }

    fn get<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Option<String>> {
        Box::pin(async move {
            if self.fail_all_ops.load(std::sync::atomic::Ordering::SeqCst) {
                return None;
            }
            let mut guard = self.values.lock().expect("in-memory cache mutex poisoned");
            if let Some((val, exp)) = guard.get(key) {
                if *exp > Instant::now() {
                    return Some(val.clone());
                }
                guard.remove(key);
            }
            None
        })
    }

    fn get_del<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Option<String>> {
        Box::pin(async move {
            if self.fail_all_ops.load(std::sync::atomic::Ordering::SeqCst) {
                return None;
            }
            let mut guard = self.values.lock().expect("in-memory cache mutex poisoned");
            guard
                .remove(key)
                .filter(|(_, exp)| *exp > Instant::now())
                .map(|(v, _)| v)
        })
    }

    fn del<'a>(&'a self, key: &'a str) -> BoxFuture<'a, bool> {
        Box::pin(async move {
            if self.fail_all_ops.load(std::sync::atomic::Ordering::SeqCst) {
                return false;
            }
            self.values
                .lock()
                .expect("in-memory cache mutex poisoned")
                .remove(key)
                .is_some()
        })
    }

    fn increment_within_window<'a>(
        &'a self,
        key: &'a str,
        window_seconds: u64,
    ) -> BoxFuture<'a, Option<i64>> {
        Box::pin(async move {
            if self.fail_all_ops.load(std::sync::atomic::Ordering::SeqCst) {
                return None;
            }
            let mut guard = self.windows.lock().expect("in-memory cache mutex poisoned");
            let now = Instant::now();
            match guard.get_mut(key) {
                Some((count, exp)) if *exp > now => {
                    *count += 1;
                    Some(*count)
                }
                _ => {
                    guard.insert(
                        key.to_owned(),
                        (1, now + Duration::from_secs(window_seconds)),
                    );
                    Some(1)
                }
            }
        })
    }

    fn publish<'a>(&'a self, _channel: &'a str, _message: &'a str) -> BoxFuture<'a, bool> {
        Box::pin(async move { !self.fail_all_ops.load(std::sync::atomic::Ordering::SeqCst) })
    }
}

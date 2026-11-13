package com.pizzora.backend.core.redis

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

interface EphemeralCacheStore {
  suspend fun setEx(key: String, ttlSeconds: Long, value: String): Boolean
  suspend fun get(key: String): String?
  suspend fun getDel(key: String): String?
  suspend fun del(key: String): Boolean
  suspend fun incrementWithinWindow(key: String, windowSeconds: Long): Long?
}

class LettuceRedisStore(
  redisUrl: String,
  timeoutMs: Long = 500L,
) : EphemeralCacheStore, AutoCloseable {
  private val logger = LoggerFactory.getLogger(LettuceRedisStore::class.java)
  private val client: RedisClient? = runCatching {
    val uri = RedisURI.create(redisUrl).apply {
      timeout = Duration.ofMillis(timeoutMs)
    }
    RedisClient.create(uri)
  }.onFailure { err ->
    logger.warn("Failed to initialize Redis client url={}", redisUrl, err)
  }.getOrNull()

  @Volatile
  private var connection: StatefulRedisConnection<String, String>? = null

  private fun getOrConnect(): StatefulRedisConnection<String, String>? {
    val existing = connection
    if (existing != null && existing.isOpen) return existing
    val c = client ?: return null
    return synchronized(this) {
      val recheck = connection
      if (recheck != null && recheck.isOpen) {
        recheck
      } else {
        runCatching { c.connect() }
          .onFailure { logger.warn("Redis unavailable; using PostgreSQL/in-memory fallback") }
          .getOrNull()
          ?.also { connection = it }
      }
    }
  }

  override suspend fun setEx(key: String, ttlSeconds: Long, value: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      val sync = getOrConnect()?.sync() ?: return@runCatching false
      sync.setex(key, ttlSeconds, value)
      true
    }.onFailure {
      logger.warn("Redis SETEX failed key={}", key, it)
    }.getOrDefault(false)
  }

  override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
    runCatching {
      getOrConnect()?.sync()?.get(key)
    }.onFailure {
      logger.warn("Redis GET failed key={}", key, it)
    }.getOrNull()
  }

  override suspend fun getDel(key: String): String? = withContext(Dispatchers.IO) {
    runCatching {
      getOrConnect()?.sync()?.getdel(key)
    }.onFailure {
      logger.warn("Redis GETDEL failed key={}", key, it)
    }.getOrNull()
  }

  override suspend fun del(key: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      val sync = getOrConnect()?.sync() ?: return@runCatching false
      sync.del(key) > 0
    }.onFailure {
      logger.warn("Redis DEL failed key={}", key, it)
    }.getOrDefault(false)
  }

  override suspend fun incrementWithinWindow(key: String, windowSeconds: Long): Long? = withContext(Dispatchers.IO) {
    runCatching {
      val sync = getOrConnect()?.sync() ?: return@runCatching null
      val count = sync.incr(key)
      if (count == 1L) {
        sync.expire(key, windowSeconds)
      }
      count
    }.onFailure {
      logger.warn("Redis INCR failed key={}", key, it)
    }.getOrNull()
  }

  override fun close() {
    runCatching { connection?.close() }
    runCatching { client?.shutdown() }
  }
}

class ResilientRateLimiter(
  private val redisStore: EphemeralCacheStore,
) {
  private data class WindowBucket(
    val windowStartEpochSec: AtomicLong,
    val counter: AtomicInteger,
  )

  private val localBuckets = ConcurrentHashMap<String, WindowBucket>()

  suspend fun isAllowed(key: String, maxRequests: Int, windowSeconds: Long): Boolean {
    val redisCount = redisStore.incrementWithinWindow("rl:$key", windowSeconds)
    if (redisCount != null) {
      return redisCount <= maxRequests
    }

    // In-memory fixed-window fallback when Redis is unavailable
    val nowSec = System.currentTimeMillis() / 1_000L
    val bucket = localBuckets.computeIfAbsent(key) {
      WindowBucket(AtomicLong(nowSec), AtomicInteger(0))
    }
    synchronized(bucket) {
      if (nowSec - bucket.windowStartEpochSec.get() >= windowSeconds) {
        bucket.windowStartEpochSec.set(nowSec)
        bucket.counter.set(0)
      }
      val updated = bucket.counter.incrementAndGet()
      return updated <= maxRequests
    }
  }
}

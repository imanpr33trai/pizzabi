package com.example.core.security

import com.example.core.database.CartDao
import com.example.core.database.NotificationInboxDao
import com.example.core.network.RealtimeEventStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface SessionScopeCleaner {
  suspend fun cleanUserScope()
}

class DefaultSessionScopeCleaner(
  private val cartDao: CartDao,
  private val notificationInboxDao: NotificationInboxDao,
  private val realtimeEventStreamProvider: () -> RealtimeEventStream? = { null },
  private val onAuthCacheClear: () -> Unit = {},
  private val onAdditionalCleanup: suspend () -> Unit = {},
) : SessionScopeCleaner {
  override suspend fun cleanUserScope() {
    withContext(NonCancellable) {
      runCatching { onAuthCacheClear() }
      runCatching { realtimeEventStreamProvider()?.resetForSessionChange() }
      runCatching { cartDao.clearAllUserCarts() }
      runCatching { notificationInboxDao.clearAll() }
      runCatching { onAdditionalCleanup() }
    }
  }
}

data class SessionSnapshot(
  val epoch: Long,
  val userId: String?,
)

class SessionTransitionCoordinator(
  private val tokenStorage: TokenStorage,
  private val scopeCleanerProvider: () -> SessionScopeCleaner,
) {
  private val transitionMutex = Mutex()
  private val isLogoutTransitionActive = AtomicBoolean(false)

  private val _sessionEpoch = MutableStateFlow(0L)
  val sessionEpoch: StateFlow<Long> = _sessionEpoch.asStateFlow()

  private val jobsLock = Any()
  private val inFlightJobs = mutableMapOf<Long, MutableList<Job>>()

  fun isLogoutInProgress(): Boolean = isLogoutTransitionActive.get()

  fun currentSnapshot(): SessionSnapshot = SessionSnapshot(
    epoch = _sessionEpoch.value,
    userId = tokenStorage.currentSession()?.user?.id,
  )

  fun isCurrentSession(capturedEpoch: Long, capturedUserId: String): Boolean {
    return _sessionEpoch.value == capturedEpoch &&
      tokenStorage.currentSession()?.user?.id == capturedUserId
  }

  fun registerInFlightJob(capturedEpoch: Long, job: Job) {
    synchronized(jobsLock) {
      if (_sessionEpoch.value != capturedEpoch) {
        job.cancel(CancellationException("Stale session epoch $capturedEpoch"))
        return
      }
      val bucket = inFlightJobs.getOrPut(capturedEpoch) { mutableListOf() }
      bucket.add(job)
    }
    job.invokeOnCompletion {
      synchronized(jobsLock) {
        inFlightJobs[capturedEpoch]?.remove(job)
        if (inFlightJobs[capturedEpoch]?.isEmpty() == true) {
          inFlightJobs.remove(capturedEpoch)
        }
      }
    }
  }

  suspend fun <T> runIfSessionMatches(
    capturedEpoch: Long,
    capturedUserId: String,
    block: suspend () -> T,
  ): T? = transitionMutex.withLock {
    val activeUserId = tokenStorage.currentSession()?.user?.id
    if (_sessionEpoch.value != capturedEpoch || activeUserId != capturedUserId) {
      return@withLock null
    }
    block()
  }

  suspend fun onLoginSuccess(newSession: AuthSession): SessionSnapshot = transitionMutex.withLock {
    val nextEpoch = bumpEpochAndCancelStaleJobsLocked()
    withContext(NonCancellable) {
      // Never retain prior user's or prior unowned local cart/notification state across login.
      scopeCleanerProvider().cleanUserScope()
      tokenStorage.saveSession(newSession)
    }
    SessionSnapshot(epoch = nextEpoch, userId = newSession.user.id)
  }

  suspend fun onLogout(remoteLogout: suspend () -> Unit = {}) {
    transitionMutex.withLock {
      isLogoutTransitionActive.set(true)
      try {
        bumpEpochAndCancelStaleJobsLocked()
        withContext(NonCancellable) {
          runCatching { remoteLogout() }
          scopeCleanerProvider().cleanUserScope()
          tokenStorage.clearSession()
        }
      } finally {
        isLogoutTransitionActive.set(false)
      }
    }
  }

  suspend fun onRefreshFailure(expectedUserId: String? = null) {
    if (isLogoutTransitionActive.get()) {
      return
    }
    transitionMutex.withLock {
      if (isLogoutTransitionActive.get()) {
        return@withLock
      }
      val activeUserId = tokenStorage.currentSession()?.user?.id
      if (expectedUserId != null && activeUserId != null && activeUserId != expectedUserId) {
        // Session already switched to a different account; never wipe the newer session.
        return@withLock
      }
      bumpEpochAndCancelStaleJobsLocked()
      withContext(NonCancellable) {
        scopeCleanerProvider().cleanUserScope()
        tokenStorage.clearSession()
      }
    }
  }

  private fun bumpEpochAndCancelStaleJobsLocked(): Long {
    val nextEpoch = _sessionEpoch.value + 1L
    _sessionEpoch.value = nextEpoch
    val staleJobs = synchronized(jobsLock) {
      val collected = mutableListOf<Job>()
      val iterator = inFlightJobs.entries.iterator()
      while (iterator.hasNext()) {
        val (epoch, jobs) = iterator.next()
        if (epoch < nextEpoch) {
          collected.addAll(jobs)
          iterator.remove()
        }
      }
      collected
    }
    staleJobs.forEach { job ->
      job.cancel(CancellationException("Session transitioned to epoch $nextEpoch"))
    }
    return nextEpoch
  }
}

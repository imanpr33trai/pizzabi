package com.example.notifications

import com.example.core.database.NotificationInboxDao
import com.example.core.database.NotificationInboxEntity
import com.example.core.network.PizzoraApiClient
import com.example.core.security.SessionTransitionCoordinator
import com.example.core.security.TokenStorage
import com.pizzora.contracts.MarkAllNotificationsReadResponse
import com.pizzora.contracts.MarkNotificationReadResponse
import com.pizzora.contracts.NotificationInboxItemDto
import com.pizzora.contracts.NotificationPreferencesDto
import com.pizzora.contracts.UpdateNotificationPreferencesRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class NotificationSyncOutcome(
  val fetched: List<NotificationInboxEntity>,
  val newlyInserted: List<NotificationInboxEntity>,
  val unreadCount: Int,
)

interface NotificationRepository {
  fun observeInbox(): Flow<List<NotificationInboxEntity>>
  fun observeUnreadCount(): Flow<Int>
  suspend fun syncInbox(unreadOnly: Boolean = false, limit: Int = 50): Result<NotificationSyncOutcome>
  suspend fun markAsRead(notificationId: String): Result<MarkNotificationReadResponse>
  suspend fun markAllAsRead(): Result<MarkAllNotificationsReadResponse>
  suspend fun getPreferences(): Result<NotificationPreferencesDto>
  suspend fun updatePreferences(request: UpdateNotificationPreferencesRequest): Result<NotificationPreferencesDto>
  suspend fun clearLocalInbox()
}

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkNotificationRepository(
  private val apiClient: PizzoraApiClient,
  private val notificationInboxDao: NotificationInboxDao,
  private val tokenStorage: TokenStorage = apiClient.sessionTokenStorage,
  private val sessionCoordinator: SessionTransitionCoordinator? = null,
) : NotificationRepository {

  private fun captureSession(): Pair<Long, String>? {
    val snapshot = sessionCoordinator?.currentSnapshot()
    val userId = snapshot?.userId ?: tokenStorage.currentSession()?.user?.id ?: return null
    val epoch = snapshot?.epoch ?: 0L
    return epoch to userId
  }

  private suspend fun <T> commitIfSessionMatches(
    capturedEpoch: Long,
    capturedUserId: String,
    block: suspend () -> T,
  ): T? {
    if (sessionCoordinator != null) {
      return sessionCoordinator.runIfSessionMatches(capturedEpoch, capturedUserId, block)
    }
    if (tokenStorage.currentSession()?.user?.id != capturedUserId) {
      return null
    }
    return block()
  }

  override fun observeInbox(): Flow<List<NotificationInboxEntity>> =
    tokenStorage.sessionFlow
      .map { it?.user?.id }
      .distinctUntilChanged()
      .flatMapLatest { userId ->
        if (userId == null) flowOf(emptyList()) else notificationInboxDao.observeNotifications(userId)
      }

  override fun observeUnreadCount(): Flow<Int> =
    tokenStorage.sessionFlow
      .map { it?.user?.id }
      .distinctUntilChanged()
      .flatMapLatest { userId ->
        if (userId == null) flowOf(0) else notificationInboxDao.observeUnreadCount(userId)
      }

  override suspend fun syncInbox(
    unreadOnly: Boolean,
    limit: Int,
  ): Result<NotificationSyncOutcome> {
    val (capturedEpoch, capturedUserId) = captureSession()
      ?: return Result.failure(IllegalStateException("Authentication required"))

    return try {
      val response = coroutineScope {
        coroutineContext[Job]?.let { job ->
          sessionCoordinator?.registerInFlightJob(capturedEpoch, job)
        }
        apiClient.getNotifications(unreadOnly = unreadOnly, limit = limit)
      }
      val entities = response.notifications.map { it.toEntity(capturedUserId) }

      val outcome = commitIfSessionMatches(capturedEpoch, capturedUserId) {
        val rowIds = if (entities.isNotEmpty()) {
          notificationInboxDao.insertIgnoreReturningIds(entities)
        } else {
          emptyList()
        }

        val newlyInserted = entities.filterIndexed { index, _ ->
          index < rowIds.size && rowIds[index] != -1L
        }

        if (!unreadOnly && entities.isNotEmpty()) {
          // Refresh read/metadata flags for existing entries while keeping (user_id, event_id) uniqueness intact
          notificationInboxDao.upsertAll(entities)
        }

        NotificationSyncOutcome(
          fetched = entities,
          newlyInserted = newlyInserted,
          unreadCount = response.unreadCount,
        )
      }
      if (outcome != null) {
        Result.success(outcome)
      } else {
        Result.failure(CancellationException("Stale session discarded"))
      }
    } catch (cancel: CancellationException) {
      currentCoroutineContext().ensureActive()
      Result.failure(cancel)
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  override suspend fun markAsRead(notificationId: String): Result<MarkNotificationReadResponse> {
    val (capturedEpoch, capturedUserId) = captureSession()
      ?: return Result.failure(IllegalStateException("Authentication required"))
    return try {
      val resp = coroutineScope {
        coroutineContext[Job]?.let { job ->
          sessionCoordinator?.registerInFlightJob(capturedEpoch, job)
        }
        apiClient.markNotificationRead(notificationId)
      }
      val committed = commitIfSessionMatches(capturedEpoch, capturedUserId) {
        notificationInboxDao.markRead(capturedUserId, notificationId)
        resp
      }
      if (committed != null) {
        Result.success(committed)
      } else {
        Result.failure(CancellationException("Stale session discarded"))
      }
    } catch (cancel: CancellationException) {
      currentCoroutineContext().ensureActive()
      Result.failure(cancel)
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  override suspend fun markAllAsRead(): Result<MarkAllNotificationsReadResponse> {
    val (capturedEpoch, capturedUserId) = captureSession()
      ?: return Result.failure(IllegalStateException("Authentication required"))
    return try {
      val resp = coroutineScope {
        coroutineContext[Job]?.let { job ->
          sessionCoordinator?.registerInFlightJob(capturedEpoch, job)
        }
        apiClient.markAllNotificationsRead()
      }
      val committed = commitIfSessionMatches(capturedEpoch, capturedUserId) {
        notificationInboxDao.markAllRead(capturedUserId)
        resp
      }
      if (committed != null) {
        Result.success(committed)
      } else {
        Result.failure(CancellationException("Stale session discarded"))
      }
    } catch (cancel: CancellationException) {
      currentCoroutineContext().ensureActive()
      Result.failure(cancel)
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  override suspend fun getPreferences(): Result<NotificationPreferencesDto> = try {
    Result.success(apiClient.getNotificationPreferences())
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (t: Throwable) {
    Result.failure(t)
  }

  override suspend fun updatePreferences(
    request: UpdateNotificationPreferencesRequest,
  ): Result<NotificationPreferencesDto> = try {
    Result.success(apiClient.updateNotificationPreferences(request))
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (t: Throwable) {
    Result.failure(t)
  }

  override suspend fun clearLocalInbox() {
    val activeUserId = tokenStorage.currentSession()?.user?.id
    if (activeUserId != null) {
      notificationInboxDao.clearForUser(activeUserId)
    } else {
      notificationInboxDao.clearAll()
    }
  }
}

private fun NotificationInboxItemDto.toEntity(userId: String): NotificationInboxEntity =
  NotificationInboxEntity(
    id = id,
    userId = userId,
    eventId = eventId,
    title = title,
    body = body,
    category = category,
    referenceId = referenceId,
    isRead = isRead,
    createdAtIso = createdAt,
  )

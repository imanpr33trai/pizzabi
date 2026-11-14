package com.example.notifications

import com.example.core.database.NotificationInboxDao
import com.example.core.database.NotificationInboxEntity
import com.example.core.network.PizzoraApiClient
import com.pizzora.contracts.MarkAllNotificationsReadResponse
import com.pizzora.contracts.MarkNotificationReadResponse
import com.pizzora.contracts.NotificationInboxItemDto
import com.pizzora.contracts.NotificationPreferencesDto
import com.pizzora.contracts.UpdateNotificationPreferencesRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

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

class NetworkNotificationRepository(
  private val apiClient: PizzoraApiClient,
  private val notificationInboxDao: NotificationInboxDao,
) : NotificationRepository {

  override fun observeInbox(): Flow<List<NotificationInboxEntity>> =
    notificationInboxDao.observeNotifications()

  override fun observeUnreadCount(): Flow<Int> =
    notificationInboxDao.observeUnreadCount()

  override suspend fun syncInbox(
    unreadOnly: Boolean,
    limit: Int,
  ): Result<NotificationSyncOutcome> = try {
    val response = apiClient.getNotifications(unreadOnly = unreadOnly, limit = limit)
    val entities = response.notifications.map { it.toEntity() }
    val rowIds = if (entities.isNotEmpty()) {
      notificationInboxDao.insertIgnoreReturningIds(entities)
    } else {
      emptyList()
    }

    val newlyInserted = entities.filterIndexed { index, _ ->
      index < rowIds.size && rowIds[index] != -1L
    }

    if (!unreadOnly && entities.isNotEmpty()) {
      // Refresh read/metadata flags for existing entries while keeping event_id uniqueness intact
      notificationInboxDao.upsertAll(entities)
    }

    Result.success(
      NotificationSyncOutcome(
        fetched = entities,
        newlyInserted = newlyInserted,
        unreadCount = response.unreadCount,
      ),
    )
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (t: Throwable) {
    Result.failure(t)
  }

  override suspend fun markAsRead(notificationId: String): Result<MarkNotificationReadResponse> = try {
    val resp = apiClient.markNotificationRead(notificationId)
    notificationInboxDao.markRead(notificationId)
    Result.success(resp)
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (t: Throwable) {
    Result.failure(t)
  }

  override suspend fun markAllAsRead(): Result<MarkAllNotificationsReadResponse> = try {
    val resp = apiClient.markAllNotificationsRead()
    notificationInboxDao.markAllRead()
    Result.success(resp)
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (t: Throwable) {
    Result.failure(t)
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
    notificationInboxDao.clearAll()
  }
}

private fun NotificationInboxItemDto.toEntity(): NotificationInboxEntity =
  NotificationInboxEntity(
    id = id,
    eventId = eventId,
    title = title,
    body = body,
    category = category,
    referenceId = referenceId,
    isRead = isRead,
    createdAtIso = createdAt,
  )

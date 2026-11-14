package com.example.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.database.NotificationInboxEntity
import com.example.core.network.RealtimeEventStream
import com.example.core.security.TokenStorage
import com.pizzora.contracts.NotificationPreferencesDto
import com.pizzora.contracts.UpdateNotificationPreferencesRequest
import com.pizzora.contracts.WsEventsFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotificationsUiState(
  val notifications: List<NotificationInboxEntity> = emptyList(),
  val unreadCount: Int = 0,
  val unreadOnlyFilter: Boolean = false,
  val isLoading: Boolean = false,
  val isUpdatingPreferences: Boolean = false,
  val preferences: NotificationPreferencesDto? = null,
  val hasNotificationPermission: Boolean = true,
  val errorMessage: String? = null,
  val actionMessage: String? = null,
) {
  val visibleNotifications: List<NotificationInboxEntity>
    get() = if (unreadOnlyFilter) {
      notifications.filter { !it.isRead }
    } else {
      notifications
    }
}

class NotificationsViewModel(
  private val repository: NotificationRepository,
  private val tokenStorage: TokenStorage? = null,
  private val realtimeEventStream: RealtimeEventStream? = null,
  private val notificationPoster: LocalNotificationPoster? = null,
) : ViewModel() {

  private val _uiState = MutableStateFlow(
    NotificationsUiState(
      hasNotificationPermission = notificationPoster?.hasPermission() ?: true,
    ),
  )
  val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

  private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val events: SharedFlow<String> = _events.asSharedFlow()

  init {
    viewModelScope.launch {
      repository.observeInbox().collect { list ->
        _uiState.update { it.copy(notifications = list) }
      }
    }
    viewModelScope.launch {
      repository.observeUnreadCount().collect { count ->
        _uiState.update { it.copy(unreadCount = count) }
      }
    }
    realtimeEventStream?.let { stream ->
      viewModelScope.launch {
        stream.frames.collect { frame ->
          handleRealtimeFrame(frame)
        }
      }
    }
  }

  fun refreshPermissionState() {
    val granted = notificationPoster?.hasPermission() ?: true
    _uiState.update { it.copy(hasNotificationPermission = granted) }
  }

  fun onPermissionResult(granted: Boolean) {
    _uiState.update {
      it.copy(
        hasNotificationPermission = granted,
        actionMessage = if (granted) {
          "Order notifications enabled."
        } else {
          "Notifications remain off — you can still view alerts here anytime."
        },
      )
    }
    if (granted) {
      notificationPoster?.ensureChannels()
    }
  }

  fun startSessionSync(userId: String) {
    realtimeEventStream?.start(userId)
    refreshAll()
  }

  fun refreshAll() {
    refreshPermissionState()
    syncInbox()
    loadPreferences()
  }

  fun syncInbox(unreadOnly: Boolean = false) {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, errorMessage = null) }
      repository.syncInbox(unreadOnly = unreadOnly, limit = 50)
        .onSuccess {
          _uiState.update { it.copy(isLoading = false, errorMessage = null) }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isLoading = false,
              errorMessage = err.message ?: "Unable to sync notifications.",
            )
          }
        }
    }
  }

  fun setUnreadOnlyFilter(unreadOnly: Boolean) {
    _uiState.update { it.copy(unreadOnlyFilter = unreadOnly) }
  }

  fun markAsRead(notificationId: String) {
    viewModelScope.launch {
      repository.markAsRead(notificationId)
        .onFailure { err ->
          _uiState.update { it.copy(errorMessage = err.message ?: "Could not mark notification as read.") }
        }
    }
  }

  fun markAllAsRead() {
    viewModelScope.launch {
      repository.markAllAsRead()
        .onSuccess { resp ->
          if (resp.markedCount > 0) {
            _events.tryEmit("Marked ${resp.markedCount} notification(s) as read.")
          }
        }
        .onFailure { err ->
          _uiState.update { it.copy(errorMessage = err.message ?: "Could not mark all notifications as read.") }
        }
    }
  }

  fun loadPreferences() {
    viewModelScope.launch {
      repository.getPreferences()
        .onSuccess { prefs ->
          _uiState.update { it.copy(preferences = prefs) }
          tokenStorage?.setKdsSoundMuted(!prefs.kdsSoundEnabled)
        }
    }
  }

  fun updatePreferences(
    orderUpdates: Boolean? = null,
    promoAlerts: Boolean? = null,
    kdsSoundEnabled: Boolean? = null,
  ) {
    val current = _uiState.value.preferences
    val req = UpdateNotificationPreferencesRequest(
      orderUpdates = orderUpdates ?: current?.orderUpdates,
      promoAlerts = promoAlerts ?: current?.promoAlerts,
      kdsSoundEnabled = kdsSoundEnabled ?: current?.kdsSoundEnabled,
    )
    viewModelScope.launch {
      _uiState.update { it.copy(isUpdatingPreferences = true, errorMessage = null) }
      repository.updatePreferences(req)
        .onSuccess { prefs ->
          _uiState.update {
            it.copy(
              isUpdatingPreferences = false,
              preferences = prefs,
              actionMessage = "Notification preferences saved.",
            )
          }
          tokenStorage?.setKdsSoundMuted(!prefs.kdsSoundEnabled)
          _events.tryEmit("Notification preferences updated.")
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isUpdatingPreferences = false,
              errorMessage = err.message ?: "Unable to update notification preferences.",
            )
          }
        }
    }
  }

  private fun handleRealtimeFrame(frame: WsEventsFrame) {
    val hasNotifRelevantEvent = frame.events.any { event ->
      event.eventType == "notification.created" ||
        event.eventType == "order.created" ||
        event.eventType == "order.status_changed" ||
        event.eventType == "kitchen.acknowledged"
    }
    if (!hasNotifRelevantEvent) return

    viewModelScope.launch {
      repository.syncInbox(unreadOnly = false, limit = 50)
        .onSuccess { outcome ->
          outcome.newlyInserted.firstOrNull { !it.isRead }?.let { newest ->
            _events.tryEmit("${newest.title}: ${newest.body}")
          }
        }
    }
  }

  fun clearActionMessage() {
    _uiState.update { it.copy(actionMessage = null) }
  }

  fun clearSessionState() {
    realtimeEventStream?.stop()
    _uiState.update {
      it.copy(
        unreadOnlyFilter = false,
        isLoading = false,
        isUpdatingPreferences = false,
        preferences = null,
        errorMessage = null,
        actionMessage = null,
      )
    }
    viewModelScope.launch {
      repository.clearLocalInbox()
    }
  }
}

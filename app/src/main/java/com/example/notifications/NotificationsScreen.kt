package com.example.notifications

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.database.NotificationInboxEntity
import com.example.ui.theme.PizzoraMonoBody

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
  uiState: NotificationsUiState,
  onRefresh: () -> Unit,
  onSelectUnreadOnly: (Boolean) -> Unit,
  onMarkRead: (String) -> Unit,
  onMarkAllRead: () -> Unit,
  onToggleOrderUpdates: (Boolean) -> Unit,
  onTogglePromoAlerts: (Boolean) -> Unit,
  onToggleKdsSound: ((Boolean) -> Unit)? = null,
  onNotificationPermissionResult: (Boolean) -> Unit,
  onBackClick: (() -> Unit)? = null,
  onClearActionMessage: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  if (onBackClick != null) {
    BackHandler(onBack = onBackClick)
  }

  val snackbarHostState = remember { SnackbarHostState() }
  LaunchedEffect(uiState.actionMessage) {
    val msg = uiState.actionMessage ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(msg)
    onClearActionMessage()
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    onNotificationPermissionResult(granted)
  }

  val showPermissionCard = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    !uiState.hasNotificationPermission

  Scaffold(
    modifier = modifier.fillMaxSize().testTag("notifications_screen"),
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.notifications_title)) },
        navigationIcon = {
          if (onBackClick != null) {
            IconButton(
              onClick = onBackClick,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("notifications_back_button"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
              )
            }
          }
        },
        actions = {
          if (uiState.unreadCount > 0) {
            TextButton(
              onClick = onMarkAllRead,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("notifications_mark_all_read_button"),
            ) {
              Icon(Icons.Filled.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text(stringResource(R.string.notifications_mark_all_read))
            }
          }
          IconButton(
            onClick = onRefresh,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("notifications_refresh_button"),
          ) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
          }
        },
      )
    },
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .testTag("notifications_list"),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (showPermissionCard) {
        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth().testTag("notifications_permission_card"),
          ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.NotificationsActive,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                  text = stringResource(R.string.notifications_permission_title),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
              }
              Text(
                text = stringResource(R.string.notifications_permission_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
              )
              Button(
                onClick = {
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                  }
                },
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("notifications_request_permission_button"),
              ) {
                Text(stringResource(R.string.notifications_permission_enable))
              }
            }
          }
        }
      }

      if (uiState.errorMessage != null) {
        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth().testTag("notifications_error_card"),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = uiState.errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
              )
              OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.minimumInteractiveComponentSize(),
              ) {
                Text(stringResource(R.string.action_retry))
              }
            }
          }
        }
      }

      item {
        NotificationPreferencesCard(
          uiState = uiState,
          onToggleOrderUpdates = onToggleOrderUpdates,
          onTogglePromoAlerts = onTogglePromoAlerts,
          onToggleKdsSound = onToggleKdsSound,
        )
      }

      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          FilterChip(
            selected = !uiState.unreadOnlyFilter,
            onClick = { onSelectUnreadOnly(false) },
            label = { Text(stringResource(R.string.notifications_filter_all)) },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("notifications_filter_all"),
          )
          FilterChip(
            selected = uiState.unreadOnlyFilter,
            onClick = { onSelectUnreadOnly(true) },
            label = { Text(stringResource(R.string.notifications_filter_unread, uiState.unreadCount)) },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("notifications_filter_unread"),
          )
          if (uiState.isLoading) {
            Spacer(modifier = Modifier.weight(1f))
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          }
        }
      }

      val itemsToShow = uiState.visibleNotifications
      if (itemsToShow.isEmpty()) {
        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().testTag("notifications_empty_state"),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(28.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.NotificationsNone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
              )
              Text(
                text = stringResource(R.string.notifications_empty_title),
                style = MaterialTheme.typography.titleMedium,
              )
              Text(
                text = stringResource(R.string.notifications_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      } else {
        items(itemsToShow, key = { it.id }) { notification ->
          NotificationInboxCard(
            item = notification,
            onMarkRead = { onMarkRead(notification.id) },
          )
        }
      }
    }
  }
}

@Composable
private fun NotificationPreferencesCard(
  uiState: NotificationsUiState,
  onToggleOrderUpdates: (Boolean) -> Unit,
  onTogglePromoAlerts: (Boolean) -> Unit,
  onToggleKdsSound: ((Boolean) -> Unit)?,
) {
  val prefs = uiState.preferences
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    modifier = Modifier.fillMaxWidth().testTag("notifications_preferences_card"),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Notifications,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = stringResource(R.string.notifications_preferences_title),
          style = MaterialTheme.typography.titleMedium,
        )
      }

      PreferenceToggleRow(
        title = stringResource(R.string.notifications_pref_order_updates),
        subtitle = stringResource(R.string.notifications_pref_order_updates_desc),
        checked = prefs?.orderUpdates ?: true,
        enabled = !uiState.isUpdatingPreferences,
        onCheckedChange = onToggleOrderUpdates,
        switchTag = "pref_order_updates_switch",
      )

      HorizontalDivider()

      PreferenceToggleRow(
        title = stringResource(R.string.notifications_pref_promo_alerts),
        subtitle = stringResource(R.string.notifications_pref_promo_alerts_desc),
        checked = prefs?.promoAlerts ?: true,
        enabled = !uiState.isUpdatingPreferences,
        onCheckedChange = onTogglePromoAlerts,
        switchTag = "pref_promo_alerts_switch",
      )

      if (onToggleKdsSound != null) {
        HorizontalDivider()
        PreferenceToggleRow(
          title = stringResource(R.string.notifications_pref_kds_sound),
          subtitle = stringResource(R.string.notifications_pref_kds_sound_desc),
          checked = prefs?.kdsSoundEnabled ?: true,
          enabled = !uiState.isUpdatingPreferences,
          onCheckedChange = onToggleKdsSound,
          switchTag = "pref_kds_sound_switch",
        )
      }
    }
  }
}

@Composable
private fun PreferenceToggleRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  enabled: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  switchTag: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(text = title, style = MaterialTheme.typography.bodyLarge)
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      enabled = enabled,
      modifier = Modifier.minimumInteractiveComponentSize().testTag(switchTag),
    )
  }
}

@Composable
private fun NotificationInboxCard(
  item: NotificationInboxEntity,
  onMarkRead: () -> Unit,
) {
  val containerColor = if (item.isRead) {
    MaterialTheme.colorScheme.surface
  } else {
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
  }

  Card(
    colors = CardDefaults.cardColors(containerColor = containerColor),
    elevation = CardDefaults.cardElevation(defaultElevation = if (item.isRead) 0.dp else 1.dp),
    modifier = Modifier.fillMaxWidth().testTag("notification_card_${item.id}"),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.weight(1f),
        ) {
          if (!item.isRead) {
            Box(
              modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .testTag("notification_unread_dot_${item.id}"),
            )
          }
          Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (item.isRead) FontWeight.Normal else FontWeight.SemiBold,
          )
        }
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
          Text(
            text = item.category.replace('_', ' '),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      }

      Text(
        text = item.body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = item.createdAtIso.take(19).replace('T', ' '),
          style = PizzoraMonoBody,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!item.isRead) {
          TextButton(
            onClick = onMarkRead,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("notification_mark_read_${item.id}"),
          ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.notifications_mark_read))
          }
        }
      }
    }
  }
}

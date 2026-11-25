package com.example.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.PizzoraMonoBody
import java.text.DateFormat
import java.util.Date

internal fun positiveUpdateStatusColor(colorScheme: ColorScheme): Color = colorScheme.secondary

@Composable
fun AppUpdatesSettingsCard(
  uiState: UpdateUiState,
  onCheckForUpdates: () -> Unit,
  onOpenUpdateDialog: () -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isBusy = uiState.state is UpdateState.Checking ||
    uiState.state is UpdateState.Downloading ||
    uiState.state is UpdateState.Verifying ||
    uiState.state is UpdateState.Installing

  val actionableUpdate = availableUpdateForSummary(uiState.state)

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    modifier = modifier.fillMaxWidth().testTag("update_settings_card"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.SystemUpdate,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = stringResource(R.string.update_settings_title),
            style = MaterialTheme.typography.titleMedium,
          )
        }
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = MaterialTheme.shapes.small,
        ) {
          Text(
            text = "v${uiState.installedVersionName} (${uiState.installedVersionCode})",
            style = PizzoraMonoBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
              .padding(horizontal = 8.dp, vertical = 4.dp)
              .testTag("update_version_text"),
          )
        }
      }

      Text(
        text = uiState.lastCheckedAtEpochMs?.let { ts ->
          stringResource(R.string.update_last_checked_fmt, formatTimestamp(ts))
        } ?: stringResource(R.string.update_never_checked),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      when (val s = uiState.state) {
        UpdateState.Idle -> {
          Text(
            text = stringResource(R.string.update_status_idle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("update_status_text"),
          )
        }

        is UpdateState.Checking -> {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          Text(
            text = stringResource(R.string.update_status_checking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("update_status_text"),
          )
        }

        is UpdateState.UpToDate -> {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.CheckCircle,
              contentDescription = null,
              tint = positiveUpdateStatusColor(MaterialTheme.colorScheme),
              modifier = Modifier.size(18.dp),
            )
            Text(
              text = stringResource(R.string.update_status_up_to_date, uiState.installedVersionName),
              style = MaterialTheme.typography.bodyMedium,
              color = positiveUpdateStatusColor(MaterialTheme.colorScheme),
              modifier = Modifier.testTag("update_status_text"),
            )
          }
        }

        is UpdateState.UpdateAvailable -> {
          UpdateSummaryBanner(
            update = s.update,
            statusLabel = stringResource(R.string.update_available_banner, s.update.metadata.versionName),
          )
        }

        is UpdateState.Downloading -> {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              text = stringResource(
                R.string.update_downloading_banner,
                s.update.metadata.versionName,
                s.progressPercent,
              ),
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.testTag("update_status_text"),
            )
            LinearProgressIndicator(
              progress = { (s.progressPercent.coerceIn(0, 100)) / 100f },
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }

        is UpdateState.Verifying -> {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          Text(
            text = stringResource(R.string.update_verifying_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("update_status_text"),
          )
        }

        is UpdateState.InstallPermissionRequired -> {
          UpdateSummaryBanner(
            update = s.update,
            statusLabel = stringResource(R.string.update_permission_needed_banner),
          )
        }

        is UpdateState.ReadyToInstall -> {
          UpdateSummaryBanner(
            update = s.update,
            statusLabel = stringResource(R.string.update_ready_banner, s.update.metadata.versionName),
          )
        }

        is UpdateState.Installing -> {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          Text(
            text = stringResource(R.string.update_installing_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("update_status_text"),
          )
        }

        is UpdateState.Completed -> {
          Text(
            text = stringResource(R.string.update_completed_banner, s.update.metadata.versionName),
            style = MaterialTheme.typography.bodyMedium,
            color = positiveUpdateStatusColor(MaterialTheme.colorScheme),
            modifier = Modifier.testTag("update_status_text"),
          )
        }

        is UpdateState.Cancelled -> {
          Text(
            text = s.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("update_status_text"),
          )
        }

        is UpdateState.Failed -> {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().testTag("update_status_error_banner"),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
              )
              Text(
                text = s.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).testTag("update_status_text"),
              )
            }
          }
        }
      }

      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = onCheckForUpdates,
          enabled = !isBusy,
          modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_check_button"),
        ) {
          Text(stringResource(R.string.update_action_check))
        }

        if (actionableUpdate != null) {
          Button(
            onClick = onOpenUpdateDialog,
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_open_dialog_button"),
          ) {
            Text(
              text = when (uiState.state) {
                is UpdateState.ReadyToInstall -> stringResource(R.string.update_action_install)
                is UpdateState.InstallPermissionRequired -> stringResource(R.string.update_action_open_settings)
                else -> stringResource(R.string.update_action_view_update)
              },
            )
          }
        } else if (uiState.state is UpdateState.Failed && uiState.state.canRetry) {
          TextButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_card_retry_button"),
          ) {
            Text(stringResource(R.string.action_retry))
          }
        }
      }
    }
  }
}

@Composable
private fun UpdateSummaryBanner(
  update: AvailableUpdate,
  statusLabel: String,
) {
  Surface(
    color = MaterialTheme.colorScheme.primaryContainer,
    shape = MaterialTheme.shapes.small,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.NewReleases,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = statusLabel,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.testTag("update_status_text"),
        )
        update.apkSizeBytes?.takeIf { it > 0L }?.let { bytes ->
          Text(
            text = formatApkSize(bytes),
            style = PizzoraMonoBody,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
      }
    }
  }
}

@Composable
fun UpdateHostOverlay(
  uiState: UpdateUiState,
  onStartDownloadOrInstall: () -> Unit,
  onCancelDownload: () -> Unit,
  onDismissOptional: () -> Unit,
  onRequestInstallPermission: () -> Unit,
  onRetry: () -> Unit,
) {
  if (uiState.isMandatoryBlocked) {
    MandatoryUpdateScreen(
      uiState = uiState,
      onStartDownloadOrInstall = onStartDownloadOrInstall,
      onCancelDownload = onCancelDownload,
      onRequestInstallPermission = onRequestInstallPermission,
      onRetry = onRetry,
    )
    return
  }

  if (!uiState.showUpdateDialog) return

  OptionalUpdateModal(
    uiState = uiState,
    onStartDownloadOrInstall = onStartDownloadOrInstall,
    onCancelDownload = onCancelDownload,
    onDismissOptional = onDismissOptional,
    onRequestInstallPermission = onRequestInstallPermission,
    onRetry = onRetry,
  )
}

@Composable
private fun MandatoryUpdateScreen(
  uiState: UpdateUiState,
  onStartDownloadOrInstall: () -> Unit,
  onCancelDownload: () -> Unit,
  onRequestInstallPermission: () -> Unit,
  onRetry: () -> Unit,
) {
  BackHandler(enabled = true) {
    // Mandatory updates cannot be dismissed via back navigation.
  }

  val update = availableUpdateForSummary(uiState.state)
  Surface(
    color = MaterialTheme.colorScheme.background,
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .testTag("update_mandatory_screen"),
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .heightIn(min = maxHeight)
          .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().widthIn(max = 540.dp),
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.SystemUpdate,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.update_mandatory_title),
                style = MaterialTheme.typography.headlineSmall,
              )
              if (update != null) {
                Text(
                  text = "v${uiState.installedVersionName} (${uiState.installedVersionCode}) → v${update.metadata.versionName} (${update.metadata.versionCode})",
                  style = PizzoraMonoBody,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
          }

          val reasonText = when (update?.mandatoryReason) {
            MandatoryUpdateReason.BELOW_MINIMUM_SUPPORTED_VERSION ->
              stringResource(
                R.string.update_mandatory_below_min_reason,
                uiState.installedVersionName,
                update.metadata.minimumSupportedVersionCode,
              )
            else -> stringResource(R.string.update_mandatory_policy_reason)
          }
          Text(
            text = reasonText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          if (update != null) {
            ReleaseNotesSection(
              releaseNotes = update.metadata.releaseNotes,
              apkSizeBytes = update.apkSizeBytes,
            )
          }

          UpdateFlowStateControls(
            state = uiState.state,
            isMandatory = true,
            onStartDownloadOrInstall = onStartDownloadOrInstall,
            onCancelDownload = onCancelDownload,
            onDismissOptional = {},
            onRequestInstallPermission = onRequestInstallPermission,
            onRetry = onRetry,
          )
        }
      }
    }
    }
  }
}

@Composable
private fun OptionalUpdateModal(
  uiState: UpdateUiState,
  onStartDownloadOrInstall: () -> Unit,
  onCancelDownload: () -> Unit,
  onDismissOptional: () -> Unit,
  onRequestInstallPermission: () -> Unit,
  onRetry: () -> Unit,
) {
  val canDismiss = uiState.state !is UpdateState.Downloading && uiState.state !is UpdateState.Verifying
  BackHandler(enabled = true) {
    if (canDismiss) onDismissOptional()
  }

  val update = availableUpdateForSummary(uiState.state)

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = {
          if (canDismiss) onDismissOptional()
        },
      )
      .testTag("update_dialog"),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
      modifier = Modifier
        .padding(20.dp)
        .fillMaxWidth()
        .widthIn(max = 520.dp)
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = {},
        ),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.NewReleases,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.update_optional_title),
              style = MaterialTheme.typography.headlineSmall,
            )
            if (update != null) {
              Text(
                text = "v${uiState.installedVersionName} → v${update.metadata.versionName}",
                style = PizzoraMonoBody,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }

        if (update != null) {
          ReleaseNotesSection(
            releaseNotes = update.metadata.releaseNotes,
            apkSizeBytes = update.apkSizeBytes,
          )
        }

        UpdateFlowStateControls(
          state = uiState.state,
          isMandatory = false,
          onStartDownloadOrInstall = onStartDownloadOrInstall,
          onCancelDownload = onCancelDownload,
          onDismissOptional = onDismissOptional,
          onRequestInstallPermission = onRequestInstallPermission,
          onRetry = onRetry,
        )
      }
    }
  }
}

@Composable
private fun ReleaseNotesSection(
  releaseNotes: String,
  apkSizeBytes: Long?,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.update_release_notes_title),
        style = MaterialTheme.typography.titleSmall,
      )
      apkSizeBytes?.takeIf { it > 0L }?.let { bytes ->
        Text(
          text = formatApkSize(bytes),
          style = PizzoraMonoBody,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("update_apk_size_text"),
        )
      }
    }
    Surface(
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = MaterialTheme.shapes.medium,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 60.dp, max = 220.dp),
    ) {
      Text(
        text = releaseNotes,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
          .verticalScroll(rememberScrollState())
          .padding(12.dp)
          .testTag("update_release_notes_text"),
      )
    }
  }
}

@Composable
private fun UpdateFlowStateControls(
  state: UpdateState,
  isMandatory: Boolean,
  onStartDownloadOrInstall: () -> Unit,
  onCancelDownload: () -> Unit,
  onDismissOptional: () -> Unit,
  onRequestInstallPermission: () -> Unit,
  onRetry: () -> Unit,
) {
  when (state) {
    is UpdateState.UpdateAvailable -> {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (!isMandatory) {
          OutlinedButton(
            onClick = onDismissOptional,
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_later_button"),
          ) {
            Text(stringResource(R.string.update_action_later))
          }
        }
        Button(
          onClick = onStartDownloadOrInstall,
          modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_primary_action_button"),
        ) {
          Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.update_action_download_install))
        }
      }
    }

    is UpdateState.Downloading -> {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(
          progress = { (state.progressPercent.coerceIn(0, 100)) / 100f },
          modifier = Modifier.fillMaxWidth().testTag("update_progress_bar"),
        )
        val progressText = if (state.totalBytes != null && state.totalBytes > 0L) {
          "${state.progressPercent}% (${formatApkSize(state.bytesDownloaded)} / ${formatApkSize(state.totalBytes)})"
        } else {
          "${formatApkSize(state.bytesDownloaded)} downloaded"
        }
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = progressText,
            style = PizzoraMonoBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("update_progress_text"),
          )
          OutlinedButton(
            onClick = onCancelDownload,
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_cancel_button"),
          ) {
            Text(stringResource(R.string.update_action_cancel))
          }
        }
      }
    }

    is UpdateState.Verifying -> {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("update_verifying_indicator"))
        Text(
          text = stringResource(R.string.update_verifying_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    is UpdateState.InstallPermissionRequired -> {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
          color = MaterialTheme.colorScheme.secondaryContainer,
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier.fillMaxWidth().testTag("update_permission_banner"),
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Filled.Security,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
              text = stringResource(R.string.update_permission_explanation),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
          }
        }
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (!isMandatory) {
            OutlinedButton(
              onClick = onDismissOptional,
              modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_later_button"),
            ) {
              Text(stringResource(R.string.update_action_later))
            }
          }
          Button(
            onClick = onRequestInstallPermission,
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_permission_button"),
          ) {
            Text(stringResource(R.string.update_action_open_settings))
          }
        }
      }
    }

    is UpdateState.ReadyToInstall -> {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (!isMandatory) {
          OutlinedButton(
            onClick = onDismissOptional,
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_later_button"),
          ) {
            Text(stringResource(R.string.update_action_later))
          }
        }
        Button(
          onClick = onStartDownloadOrInstall,
          modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_primary_action_button"),
        ) {
          Text(stringResource(R.string.update_action_install))
        }
      }
    }

    is UpdateState.Installing -> {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
          text = stringResource(R.string.update_installing_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (!isMandatory) {
            OutlinedButton(
              onClick = onDismissOptional,
              modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_later_button"),
            ) {
              Text(stringResource(R.string.action_close))
            }
          }
          TextButton(
            onClick = onStartDownloadOrInstall,
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_retry_install_button"),
          ) {
            Text(stringResource(R.string.update_action_relaunch_installer))
          }
        }
      }
    }

    is UpdateState.Failed -> {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
          color = MaterialTheme.colorScheme.errorContainer,
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier.fillMaxWidth().testTag("update_error_banner"),
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Filled.WarningAmber,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
              text = state.message,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.weight(1f).testTag("update_error_message"),
            )
          }
        }
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (!isMandatory) {
            OutlinedButton(
              onClick = onDismissOptional,
              modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_later_button"),
            ) {
              Text(stringResource(R.string.action_close))
            }
          }
          if (state.canRetry) {
            Button(
              onClick = onRetry,
              modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_retry_button"),
            ) {
              Text(stringResource(R.string.action_retry))
            }
          }
        }
      }
    }

    is UpdateState.Cancelled -> {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = state.message,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("update_cancelled_message"),
        )
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (!isMandatory) {
            OutlinedButton(
              onClick = onDismissOptional,
              modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_later_button"),
            ) {
              Text(stringResource(R.string.action_close))
            }
          }
          Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("update_retry_button"),
          ) {
            Text(stringResource(R.string.update_action_download_install))
          }
        }
      }
    }

    else -> Unit
  }
}

private fun availableUpdateForSummary(state: UpdateState): AvailableUpdate? = when (state) {
  is UpdateState.UpdateAvailable -> state.update
  is UpdateState.Downloading -> state.update
  is UpdateState.Verifying -> state.update
  is UpdateState.InstallPermissionRequired -> state.update
  is UpdateState.ReadyToInstall -> state.update
  is UpdateState.Installing -> state.update
  is UpdateState.Failed -> state.update
  is UpdateState.Cancelled -> state.update
  else -> null
}

private fun formatApkSize(bytes: Long): String {
  if (bytes < 1024L) return "$bytes B"
  val kib = bytes / 1024.0
  if (kib < 1024.0) return "%.0f KB".format(kib)
  val mib = kib / 1024.0
  return "%.1f MB".format(mib)
}

private fun formatTimestamp(epochMs: Long): String =
  DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

package com.example.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UpdateUiEvent {
  data class LaunchConfirmIntent(val intent: Intent) : UpdateUiEvent
  data class OpenInstallSettings(val intent: Intent) : UpdateUiEvent
  data class ShowMessage(val message: String) : UpdateUiEvent
}

class UpdateViewModel(
  private val updateRepository: UpdateRepository,
  private val apkDownloader: ApkDownloader,
  private val apkVerifier: ApkVerifier,
  private val apkInstaller: ApkInstallController,
  private val preferencesStorage: UpdatePreferencesStorage,
  private val installedAppInfoProvider: InstalledAppInfoProvider,
  private val clockMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

  private val _uiState = MutableStateFlow(loadInitialUiState())
  val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

  private val _events = MutableSharedFlow<UpdateUiEvent>(extraBufferCapacity = 16)
  val events: SharedFlow<UpdateUiEvent> = _events.asSharedFlow()

  private var activeDownloadJob: Job? = null
  private var hasAttemptedLaunchCheck = false
  private var dismissedOptionalVersionCode: Long? = null
  private var lastFailedAction: LastAction = LastAction.CHECK

  private enum class LastAction {
    CHECK,
    DOWNLOAD,
    INSTALL,
  }

  init {
    observeInstallStatusEvents()
    viewModelScope.launch {
      restorePersistedState()
    }
  }

  private fun loadInitialUiState(): UpdateUiState {
    val installed = installedAppInfoProvider.getInstalledAppInfo()
    return UpdateUiState(
      installedVersionName = installed.versionName,
      installedVersionCode = installed.versionCode,
      hasInstallPermission = apkInstaller.canRequestPackageInstalls(),
      state = UpdateState.Idle,
      showUpdateDialog = false,
    )
  }

  private fun observeInstallStatusEvents() {
    viewModelScope.launch {
      apkInstaller.statusEvents.collect { event ->
        when (event) {
          is InstallStatusEvent.PendingUserAction -> {
            _events.tryEmit(UpdateUiEvent.LaunchConfirmIntent(event.confirmIntent))
          }

          is InstallStatusEvent.Succeeded -> {
            val currentUpdate = currentUpdateFromState(_uiState.value.state)
            preferencesStorage.savePendingInstallVersionCode(null)
            preferencesStorage.clearDownloadedApk()
            if (currentUpdate != null) {
              _uiState.update {
                it.copy(
                  state = UpdateState.Completed(currentUpdate),
                  showUpdateDialog = false,
                )
              }
            } else {
              _uiState.update {
                it.copy(
                  state = UpdateState.UpToDate(checkedAtEpochMs = clockMs(), isManual = false),
                  showUpdateDialog = false,
                )
              }
            }
          }

          is InstallStatusEvent.Cancelled -> {
            val currentUpdate = currentUpdateFromState(_uiState.value.state)
            preferencesStorage.savePendingInstallVersionCode(null)
            lastFailedAction = LastAction.INSTALL
            _uiState.update {
              it.copy(
                state = UpdateState.Cancelled(
                  update = currentUpdate,
                  message = event.message,
                ),
                showUpdateDialog = currentUpdate?.isMandatory == true || it.showUpdateDialog,
              )
            }
          }

          is InstallStatusEvent.Failed -> {
            val currentUpdate = currentUpdateFromState(_uiState.value.state)
            preferencesStorage.savePendingInstallVersionCode(null)
            lastFailedAction = LastAction.INSTALL
            _uiState.update {
              it.copy(
                state = UpdateState.Failed(
                  reason = event.reason,
                  message = event.message,
                  update = currentUpdate,
                  isMandatoryBlocked = currentUpdate?.isMandatory == true,
                  canRetry = true,
                ),
                showUpdateDialog = currentUpdate?.isMandatory == true || it.showUpdateDialog,
              )
            }
          }
        }
      }
    }
  }

  internal suspend fun restorePersistedState() {
    val installed = installedAppInfoProvider.getInstalledAppInfo()
    val snapshot = preferencesStorage.readSnapshot()
    val hasPerm = apkInstaller.canRequestPackageInstalls()

    if (snapshot.pendingInstallVersionCode != null && installed.versionCode >= snapshot.pendingInstallVersionCode) {
      preferencesStorage.savePendingInstallVersionCode(null)
      preferencesStorage.clearDownloadedApk()
    }

    val cachedUpdate = updateRepository.restoreCachedUpdate(installed)
    val cachedRemoteMeta = snapshot.cachedRelease?.metadata

    var restoredState: UpdateState = _uiState.value.state
    var showDialog = _uiState.value.showUpdateDialog

    if (restoredState is UpdateState.Idle || restoredState is UpdateState.UpToDate) {
      if (cachedUpdate != null) {
        val apkPath = snapshot.downloadedApkPath
        val downloadedVersionMatches = snapshot.downloadedApkVersionCode == cachedUpdate.metadata.versionCode
        val downloadedShaMatches = snapshot.downloadedApkSha256.equals(cachedUpdate.metadata.apkSha256, ignoreCase = true)
        val candidateFile = apkPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }

        if (candidateFile != null && downloadedVersionMatches && downloadedShaMatches) {
          when (apkVerifier.verify(candidateFile, cachedUpdate.metadata, installed)) {
            is ApkVerificationResult.Verified -> {
              restoredState = if (hasPerm) {
                UpdateState.ReadyToInstall(cachedUpdate, candidateFile.absolutePath)
              } else {
                UpdateState.InstallPermissionRequired(cachedUpdate, candidateFile.absolutePath)
              }
              showDialog = cachedUpdate.isMandatory || snapshot.pendingInstallVersionCode == cachedUpdate.metadata.versionCode
            }

            is ApkVerificationResult.Rejected -> {
              preferencesStorage.clearDownloadedApk()
              restoredState = UpdateState.UpdateAvailable(
                update = cachedUpdate,
                dismissedForSession = dismissedOptionalVersionCode == cachedUpdate.metadata.versionCode && !cachedUpdate.isMandatory,
              )
              showDialog = cachedUpdate.isMandatory
            }
          }
        } else {
          if (apkPath != null && (!downloadedVersionMatches || candidateFile == null)) {
            preferencesStorage.clearDownloadedApk()
          }
          val isDismissed = dismissedOptionalVersionCode == cachedUpdate.metadata.versionCode && !cachedUpdate.isMandatory
          restoredState = UpdateState.UpdateAvailable(
            update = cachedUpdate,
            dismissedForSession = isDismissed,
          )
          showDialog = cachedUpdate.isMandatory
        }
      } else if (snapshot.lastCheckedAtEpochMs != null) {
        restoredState = UpdateState.UpToDate(
          checkedAtEpochMs = snapshot.lastCheckedAtEpochMs,
          isManual = false,
        )
      }
    }

    _uiState.update {
      it.copy(
        installedVersionName = installed.versionName,
        installedVersionCode = installed.versionCode,
        lastCheckedAtEpochMs = snapshot.lastCheckedAtEpochMs ?: it.lastCheckedAtEpochMs,
        lastKnownRemoteVersionName = cachedRemoteMeta?.versionName ?: it.lastKnownRemoteVersionName,
        lastKnownRemoteVersionCode = cachedRemoteMeta?.versionCode ?: it.lastKnownRemoteVersionCode,
        hasInstallPermission = hasPerm,
        state = restoredState,
        showUpdateDialog = showDialog,
      )
    }
  }

  fun checkForUpdatesIfDue() {
    if (hasAttemptedLaunchCheck) return
    hasAttemptedLaunchCheck = true
    performDueCheck()
  }

  private fun performDueCheck() {
    if (isBusyWithUpdateFlow()) return
    viewModelScope.launch {
      val installed = installedAppInfoProvider.getInstalledAppInfo()
      if (updateRepository.shouldPerformAutomaticCheck(installed, clockMs())) {
        runUpdateCheck(isManual = false, forceRefresh = false)
      }
    }
  }

  fun checkForUpdatesManually() {
    if (isBusyWithUpdateFlow() || _uiState.value.state is UpdateState.Checking) return
    viewModelScope.launch {
      runUpdateCheck(isManual = true, forceRefresh = false)
    }
  }

  private suspend fun runUpdateCheck(isManual: Boolean, forceRefresh: Boolean) {
    val installed = installedAppInfoProvider.getInstalledAppInfo()
    val hasPerm = apkInstaller.canRequestPackageInstalls()
    val previousState = _uiState.value.state
    lastFailedAction = LastAction.CHECK

    _uiState.update {
      it.copy(
        installedVersionName = installed.versionName,
        installedVersionCode = installed.versionCode,
        hasInstallPermission = hasPerm,
        state = UpdateState.Checking(isManual = isManual),
      )
    }

    when (val result = updateRepository.checkForUpdates(installed = installed, forceRefresh = forceRefresh)) {
      is UpdateDiscoveryResult.UpToDate -> {
        preferencesStorage.clearDownloadedApk()
        preferencesStorage.savePendingInstallVersionCode(null)
        _uiState.update {
          it.copy(
            lastCheckedAtEpochMs = result.checkedAtEpochMs,
            lastKnownRemoteVersionName = result.remoteVersionName ?: installed.versionName,
            lastKnownRemoteVersionCode = result.remoteVersionCode ?: installed.versionCode,
            state = UpdateState.UpToDate(
              checkedAtEpochMs = result.checkedAtEpochMs,
              isManual = isManual,
            ),
            showUpdateDialog = false,
          )
        }
      }

      is UpdateDiscoveryResult.Available -> {
        val update = result.update
        val snapshot = preferencesStorage.readSnapshot()
        val cachedFile = snapshot.downloadedApkPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
        val isVerifiedReady = if (
          cachedFile != null &&
          snapshot.downloadedApkVersionCode == update.metadata.versionCode &&
          snapshot.downloadedApkSha256.equals(update.metadata.apkSha256, ignoreCase = true)
        ) {
          apkVerifier.verify(cachedFile, update.metadata, installed) is ApkVerificationResult.Verified
        } else {
          false
        }

        val dismissed = !isManual && !update.isMandatory && dismissedOptionalVersionCode == update.metadata.versionCode
        val nextState = if (isVerifiedReady && cachedFile != null) {
          if (hasPerm) {
            UpdateState.ReadyToInstall(update, cachedFile.absolutePath)
          } else {
            UpdateState.InstallPermissionRequired(update, cachedFile.absolutePath)
          }
        } else {
          UpdateState.UpdateAvailable(update = update, dismissedForSession = dismissed)
        }

        val shouldShowDialog = isManual || update.isMandatory || !dismissed

        _uiState.update {
          it.copy(
            lastCheckedAtEpochMs = result.checkedAtEpochMs,
            lastKnownRemoteVersionName = update.metadata.versionName,
            lastKnownRemoteVersionCode = update.metadata.versionCode,
            hasInstallPermission = hasPerm,
            state = nextState,
            showUpdateDialog = shouldShowDialog,
          )
        }
      }

      is UpdateDiscoveryResult.Error -> {
        val isTransient = result.reason in TRANSIENT_ERROR_REASONS
        if (!isManual && isTransient) {
          // Never trap the user on transient network/rate-limit errors during launch checks.
          val fallback = if (previousState is UpdateState.UpdateAvailable && !previousState.update.isMandatory) {
            previousState
          } else {
            UpdateState.Failed(
              reason = result.reason,
              message = result.message,
              update = result.cachedUpdate,
              isMandatoryBlocked = false,
              canRetry = true,
            )
          }
          _uiState.update {
            it.copy(
              state = fallback,
              showUpdateDialog = false,
            )
          }
        } else {
          val cachedUpdate = result.cachedUpdate
          val mandatoryBlock = cachedUpdate?.isMandatory == true && !isTransient
          _uiState.update {
            it.copy(
              state = UpdateState.Failed(
                reason = result.reason,
                message = result.message,
                update = cachedUpdate,
                isMandatoryBlocked = mandatoryBlock,
                canRetry = true,
              ),
              showUpdateDialog = mandatoryBlock,
            )
          }
        }
      }
    }
  }

  fun openUpdateDialog() {
    val current = _uiState.value.state
    if (current is UpdateState.UpdateAvailable ||
      current is UpdateState.ReadyToInstall ||
      current is UpdateState.InstallPermissionRequired ||
      current is UpdateState.Downloading ||
      current is UpdateState.Verifying ||
      current is UpdateState.Installing ||
      (current is UpdateState.Failed && current.update != null) ||
      (current is UpdateState.Cancelled && current.update != null)
    ) {
      val updatedState = if (current is UpdateState.UpdateAvailable) {
        current.copy(dismissedForSession = false)
      } else {
        current
      }
      _uiState.update {
        it.copy(
          state = updatedState,
          showUpdateDialog = true,
        )
      }
    }
  }

  fun dismissOptionalUpdateDialog() {
    val current = _uiState.value
    if (current.isMandatoryBlocked) return
    val nextState = when (val s = current.state) {
      is UpdateState.UpdateAvailable -> {
        dismissedOptionalVersionCode = s.update.metadata.versionCode
        s.copy(dismissedForSession = true)
      }
      is UpdateState.Cancelled -> {
        val u = s.update
        if (u != null) {
          dismissedOptionalVersionCode = u.metadata.versionCode
          UpdateState.UpdateAvailable(update = u, dismissedForSession = true)
        } else {
          UpdateState.Idle
        }
      }
      else -> s
    }
    _uiState.update {
      it.copy(
        state = nextState,
        showUpdateDialog = false,
      )
    }
  }

  fun startUpdateDownloadAndInstall() {
    val currentState = _uiState.value.state
    if (currentState is UpdateState.Downloading || currentState is UpdateState.Verifying) {
      return
    }
    val update = currentUpdateFromState(currentState) ?: return

    // If we already have a ready/permission-pending verified APK on disk, proceed straight to verification + install.
    val readyPath = when (currentState) {
      is UpdateState.ReadyToInstall -> currentState.verifiedApkPath
      is UpdateState.InstallPermissionRequired -> currentState.verifiedApkPath
      else -> null
    }
    if (readyPath != null) {
      val existingFile = File(readyPath)
      if (existingFile.isFile && existingFile.length() > 0L) {
        viewModelScope.launch {
          verifyAndInstallExistingFile(update, existingFile)
        }
        return
      }
    }

    if (apkDownloader.isDownloadInProgress()) return

    activeDownloadJob?.cancel()
    activeDownloadJob = viewModelScope.launch {
      lastFailedAction = LastAction.DOWNLOAD
      _uiState.update {
        it.copy(
          state = UpdateState.Downloading(
            update = update,
            bytesDownloaded = 0L,
            totalBytes = update.apkSizeBytes,
            progressPercent = 0,
          ),
          showUpdateDialog = true,
        )
      }

      val downloadResult = apkDownloader.downloadApk(update) { progress ->
        _uiState.update { current ->
          if (current.state is UpdateState.Downloading) {
            current.copy(
              state = UpdateState.Downloading(
                update = update,
                bytesDownloaded = progress.bytesDownloaded,
                totalBytes = progress.totalBytes ?: update.apkSizeBytes,
                progressPercent = progress.progressPercent,
              ),
            )
          } else {
            current
          }
        }
      }

      when (downloadResult) {
        is ApkDownloadResult.Cancelled -> {
          lastFailedAction = LastAction.DOWNLOAD
          _uiState.update {
            it.copy(
              state = UpdateState.Cancelled(
                update = update,
                message = "Update download was cancelled.",
              ),
              showUpdateDialog = update.isMandatory || it.showUpdateDialog,
            )
          }
        }

        is ApkDownloadResult.Failed -> {
          lastFailedAction = LastAction.DOWNLOAD
          _uiState.update {
            it.copy(
              state = UpdateState.Failed(
                reason = downloadResult.reason,
                message = downloadResult.message,
                update = update,
                isMandatoryBlocked = update.isMandatory,
                canRetry = true,
              ),
              showUpdateDialog = update.isMandatory || it.showUpdateDialog,
            )
          }
        }

        is ApkDownloadResult.Success -> {
          verifyAndInstallExistingFile(update, downloadResult.apkFile)
        }
      }
    }
  }

  private suspend fun verifyAndInstallExistingFile(
    update: AvailableUpdate,
    apkFile: File,
  ) {
    val installed = installedAppInfoProvider.getInstalledAppInfo()
    _uiState.update {
      it.copy(
        state = UpdateState.Verifying(
          update = update,
          downloadedFilePath = apkFile.absolutePath,
        ),
        showUpdateDialog = true,
      )
    }

    when (val verification = apkVerifier.verify(apkFile, update.metadata, installed)) {
      is ApkVerificationResult.Rejected -> {
        preferencesStorage.clearDownloadedApk()
        preferencesStorage.savePendingInstallVersionCode(null)
        lastFailedAction = LastAction.DOWNLOAD
        _uiState.update {
          it.copy(
            state = UpdateState.Failed(
              reason = verification.reason,
              message = verification.message,
              update = update,
              isMandatoryBlocked = update.isMandatory,
              canRetry = true,
            ),
            showUpdateDialog = update.isMandatory || it.showUpdateDialog,
          )
        }
      }

      is ApkVerificationResult.Verified -> {
        preferencesStorage.saveDownloadedApk(
          apkPath = verification.apkFile.absolutePath,
          versionCode = update.metadata.versionCode,
          sha256 = verification.sha256Hex,
        )
        val hasPerm = apkInstaller.canRequestPackageInstalls()
        _uiState.update { it.copy(hasInstallPermission = hasPerm) }
        if (!hasPerm) {
          _uiState.update {
            it.copy(
              state = UpdateState.InstallPermissionRequired(
                update = update,
                verifiedApkPath = verification.apkFile.absolutePath,
              ),
              showUpdateDialog = true,
            )
          }
        } else {
          installVerifiedApk(update, verification.apkFile)
        }
      }
    }
  }

  private suspend fun installVerifiedApk(
    update: AvailableUpdate,
    apkFile: File,
  ) {
    lastFailedAction = LastAction.INSTALL
    preferencesStorage.savePendingInstallVersionCode(update.metadata.versionCode)
    _uiState.update {
      it.copy(
        state = UpdateState.Installing(update = update, sessionId = null),
        showUpdateDialog = true,
      )
    }

    when (val launchResult = apkInstaller.startInstall(apkFile)) {
      ApkInstallLaunchResult.PermissionRequired -> {
        preferencesStorage.savePendingInstallVersionCode(null)
        _uiState.update {
          it.copy(
            hasInstallPermission = false,
            state = UpdateState.InstallPermissionRequired(
              update = update,
              verifiedApkPath = apkFile.absolutePath,
            ),
            showUpdateDialog = true,
          )
        }
      }

      is ApkInstallLaunchResult.Failed -> {
        preferencesStorage.savePendingInstallVersionCode(null)
        _uiState.update {
          it.copy(
            state = UpdateState.Failed(
              reason = launchResult.reason,
              message = launchResult.message,
              update = update,
              isMandatoryBlocked = update.isMandatory,
              canRetry = true,
            ),
            showUpdateDialog = update.isMandatory || it.showUpdateDialog,
          )
        }
      }

      is ApkInstallLaunchResult.Launched -> {
        _uiState.update {
          it.copy(
            state = UpdateState.Installing(
              update = update,
              sessionId = launchResult.sessionId,
            ),
            showUpdateDialog = true,
          )
        }
      }
    }
  }

  fun cancelDownload() {
    val current = _uiState.value.state
    if (current is UpdateState.Downloading) {
      apkDownloader.cancelActiveDownload()
      activeDownloadJob?.cancel()
      activeDownloadJob = null
      _uiState.update {
        it.copy(
          state = UpdateState.Cancelled(
            update = current.update,
            message = "Update download was cancelled.",
          ),
          showUpdateDialog = current.update.isMandatory || it.showUpdateDialog,
        )
      }
    }
  }

  fun requestInstallPermission() {
    val intent = apkInstaller.createManageUnknownAppSourcesIntent()
    _events.tryEmit(UpdateUiEvent.OpenInstallSettings(intent))
  }

  fun retryFailedAction() {
    when (val s = _uiState.value.state) {
      is UpdateState.Failed -> {
        val update = s.update
        when {
          update == null || lastFailedAction == LastAction.CHECK -> {
            viewModelScope.launch { runUpdateCheck(isManual = true, forceRefresh = true) }
          }
          s.reason == UpdateFailureReason.INSTALL_PERMISSION_REQUIRED -> {
            requestInstallPermission()
          }
          else -> {
            startUpdateDownloadAndInstall()
          }
        }
      }

      is UpdateState.Cancelled -> {
        if (s.update != null) {
          startUpdateDownloadAndInstall()
        } else {
          checkForUpdatesManually()
        }
      }

      is UpdateState.ReadyToInstall,
      is UpdateState.InstallPermissionRequired,
      is UpdateState.UpdateAvailable,
      -> startUpdateDownloadAndInstall()

      else -> checkForUpdatesManually()
    }
  }

  fun onHostResumed() {
    viewModelScope.launch {
      val hasPerm = apkInstaller.canRequestPackageInstalls()
      val installed = installedAppInfoProvider.getInstalledAppInfo()
      _uiState.update {
        it.copy(
          installedVersionName = installed.versionName,
          installedVersionCode = installed.versionCode,
          hasInstallPermission = hasPerm,
        )
      }

      when (val current = _uiState.value.state) {
        is UpdateState.InstallPermissionRequired -> {
          if (installed.versionCode >= current.update.metadata.versionCode) {
            preferencesStorage.savePendingInstallVersionCode(null)
            preferencesStorage.clearDownloadedApk()
            _uiState.update {
              it.copy(
                state = UpdateState.UpToDate(checkedAtEpochMs = clockMs(), isManual = false),
                showUpdateDialog = false,
              )
            }
          } else if (hasPerm) {
            val file = File(current.verifiedApkPath)
            if (file.isFile && file.length() > 0L) {
              verifyAndInstallExistingFile(current.update, file)
            } else {
              preferencesStorage.clearDownloadedApk()
              _uiState.update {
                it.copy(state = UpdateState.UpdateAvailable(current.update))
              }
            }
          }
        }

        is UpdateState.Installing,
        is UpdateState.ReadyToInstall,
        is UpdateState.UpdateAvailable,
        -> {
          val targetVersion = currentUpdateFromState(current)?.metadata?.versionCode
          if (targetVersion != null && installed.versionCode >= targetVersion) {
            preferencesStorage.savePendingInstallVersionCode(null)
            preferencesStorage.clearDownloadedApk()
            _uiState.update {
              it.copy(
                state = UpdateState.UpToDate(checkedAtEpochMs = clockMs(), isManual = false),
                showUpdateDialog = false,
              )
            }
          } else if (!isBusyWithUpdateFlow() && updateRepository.shouldPerformAutomaticCheck(installed, clockMs())) {
            runUpdateCheck(isManual = false, forceRefresh = false)
          }
        }

        else -> {
          if (!isBusyWithUpdateFlow() && updateRepository.shouldPerformAutomaticCheck(installed, clockMs())) {
            runUpdateCheck(isManual = false, forceRefresh = false)
          }
        }
      }
    }
  }

  private fun isBusyWithUpdateFlow(): Boolean = when (_uiState.value.state) {
    is UpdateState.Checking,
    is UpdateState.Downloading,
    is UpdateState.Verifying,
    is UpdateState.Installing,
    -> true
    else -> false
  }

  private fun currentUpdateFromState(state: UpdateState): AvailableUpdate? = when (state) {
    is UpdateState.UpdateAvailable -> state.update
    is UpdateState.Downloading -> state.update
    is UpdateState.Verifying -> state.update
    is UpdateState.InstallPermissionRequired -> state.update
    is UpdateState.ReadyToInstall -> state.update
    is UpdateState.Installing -> state.update
    is UpdateState.Completed -> state.update
    is UpdateState.Failed -> state.update
    is UpdateState.Cancelled -> state.update
    else -> null
  }

  companion object {
    private val TRANSIENT_ERROR_REASONS = setOf(
      UpdateFailureReason.NETWORK_ERROR,
      UpdateFailureReason.RATE_LIMITED,
      UpdateFailureReason.SERVER_ERROR,
    )
  }
}

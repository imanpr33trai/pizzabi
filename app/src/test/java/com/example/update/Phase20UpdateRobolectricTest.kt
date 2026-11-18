package com.example.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.PizzoraTheme
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase20UpdateRobolectricTest {

  @get:Rule
  val composeRule = createComposeRule()

  private class FakeInstallController(
    var installPermissionGranted: Boolean = true,
    var nextLaunchResult: ApkInstallLaunchResult = ApkInstallLaunchResult.Launched(sessionId = 99),
  ) : ApkInstallController {
    private val _events = MutableSharedFlow<InstallStatusEvent>(extraBufferCapacity = 8)
    override val statusEvents: SharedFlow<InstallStatusEvent> = _events.asSharedFlow()
    var startInstallCallCount = 0

    override fun canRequestPackageInstalls(): Boolean = installPermissionGranted

    override fun createManageUnknownAppSourcesIntent(): Intent =
      Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)

    override suspend fun startInstall(apkFile: File): ApkInstallLaunchResult {
      startInstallCallCount++
      return if (!installPermissionGranted) {
        ApkInstallLaunchResult.PermissionRequired
      } else {
        nextLaunchResult
      }
    }

    fun emitStatus(event: InstallStatusEvent) {
      _events.tryEmit(event)
    }
  }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  /**
   * Idles the main looper until [condition] becomes true. Coroutines launched on
   * Dispatchers.Main can resume only after their DataStore/IO work completes and
   * posts back to the looper, so a single idle() is racy.
   */
  private fun idleUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      if (condition()) return
      Thread.sleep(10)
    }
    throw AssertionError("Condition not met within ${timeoutMs}ms")
  }

  @Test
  fun `broadcast receiver maps PackageInstaller status codes and emits status bus events`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val receiver = UpdateInstallStatusReceiver()

    val confirmEventDef = async(start = CoroutineStart.UNDISPATCHED) { UpdateInstallStatusBus.events.first() }
    val confirmPayload = Intent("com.example.CONFIRM_INSTALL")
    receiver.onReceive(
      context,
      Intent().apply {
        putExtra(PackageInstaller.EXTRA_SESSION_ID, 7)
        putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
        putExtra(Intent.EXTRA_INTENT, confirmPayload)
      },
    )
    val pending = withTimeout(5_000) { confirmEventDef.await() }
    assertTrue(pending is InstallStatusEvent.PendingUserAction)
    assertEquals(7, (pending as InstallStatusEvent.PendingUserAction).sessionId)

    val cancelEventDef = async(start = CoroutineStart.UNDISPATCHED) { UpdateInstallStatusBus.events.first() }
    receiver.onReceive(
      context,
      Intent().apply {
        putExtra(PackageInstaller.EXTRA_SESSION_ID, 7)
        putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
      },
    )
    assertTrue(withTimeout(5_000) { cancelEventDef.await() } is InstallStatusEvent.Cancelled)

    val conflictEventDef = async(start = CoroutineStart.UNDISPATCHED) { UpdateInstallStatusBus.events.first() }
    receiver.onReceive(
      context,
      Intent().apply {
        putExtra(PackageInstaller.EXTRA_SESSION_ID, 7)
        putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_CONFLICT)
      },
    )
    val conflict = withTimeout(5_000) { conflictEventDef.await() } as InstallStatusEvent.Failed
    assertEquals(UpdateFailureReason.SIGNATURE_MISMATCH, conflict.reason)

    val successEventDef = async(start = CoroutineStart.UNDISPATCHED) { UpdateInstallStatusBus.events.first() }
    receiver.onReceive(
      context,
      Intent().apply {
        putExtra(PackageInstaller.EXTRA_SESSION_ID, 7)
        putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
      },
    )
    assertTrue(withTimeout(5_000) { successEventDef.await() } is InstallStatusEvent.Succeeded)
  }

  @Test
  fun `AndroidPackageApkInstaller checks unknown sources permission and creates PackageInstaller session`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val shadowPm = Shadows.shadowOf(context.packageManager)
    val apkFile = File(context.filesDir, "updates/pizzora-v0.8.0.apk").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4))
    }

    val installer = AndroidPackageApkInstaller(context)
    val settingsIntent = installer.createManageUnknownAppSourcesIntent()
    assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settingsIntent.action)
    assertEquals("package:${context.packageName}", settingsIntent.dataString)

    // Without permission -> PermissionRequired
    shadowPm.setCanRequestPackageInstalls(false)
    assertEquals(ApkInstallLaunchResult.PermissionRequired, installer.startInstall(apkFile))

    // With permission -> PackageInstaller session created and committed
    shadowPm.setCanRequestPackageInstalls(true)
    val launched = installer.startInstall(apkFile)
    assertTrue("Expected Launched result, got $launched", launched is ApkInstallLaunchResult.Launched)
  }

  @Test
  fun `ViewModel persists verified APK across process recreation and auto-installs when permission granted on resume`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val storage = DataStoreUpdatePreferencesStorage(context)
    storage.clearAll()

    val apkBytes = "valid-update-apk-bytes".toByteArray()
    val apkSha = sha256Hex(apkBytes)
    val apkFile = File(context.filesDir, "updates/pizzora-v0.9.0.apk").apply {
      parentFile?.mkdirs()
      writeBytes(apkBytes)
    }

    val meta = UpdateMetadata(
      schemaVersion = 1,
      applicationId = context.packageName,
      versionName = "0.9.0",
      versionCode = 9000L,
      minimumSupportedVersionCode = 1000L,
      mandatory = false,
      releaseNotes = "Thermal printer support",
      apkAssetName = "pizzora-v0.9.0.apk",
      apkSha256 = apkSha,
      apkSizeBytes = apkBytes.size.toLong(),
      minSdk = 24,
      supportedAbis = listOf("arm64-v8a", "x86_64"),
      releaseUrl = "https://github.com/imanpr33trai/pizzabi/releases/tag/v0.9.0",
    )
    val release = GitHubReleaseDto(
      id = 99L,
      tagName = "v0.9.0",
      htmlUrl = meta.releaseUrl,
      assets = listOf(
        GitHubReleaseAssetDto(
          id = 1L,
          name = "update.json",
          size = 200L,
          browserDownloadUrl = "https://github.com/imanpr33trai/pizzabi/releases/download/v0.9.0/update.json",
        ),
        GitHubReleaseAssetDto(
          id = 2L,
          name = meta.apkAssetName,
          size = apkBytes.size.toLong(),
          browserDownloadUrl = "https://github.com/imanpr33trai/pizzabi/releases/download/v0.9.0/${meta.apkAssetName}",
        ),
      ),
    )

    storage.saveCheckMetadata(
      checkedAtEpochMs = 1_700_000_000_000L,
      etag = "\"etag-9\"",
      cachedRelease = CachedReleaseSnapshot(release = release, metadata = meta),
    )
    storage.saveDownloadedApk(
      apkPath = apkFile.absolutePath,
      versionCode = 9000L,
      sha256 = apkSha,
    )

    val installedProvider = object : InstalledAppInfoProvider {
      override fun getInstalledAppInfo() = InstalledAppInfo(
        applicationId = context.packageName,
        versionName = "0.1.0",
        versionCode = 1000L,
        sdkInt = 36,
        supportedAbis = listOf("arm64-v8a"),
        signingCertSha256s = setOf("prod_cert"),
      )
    }

    val verifier = ApkIntegrityVerifier(
      ApkPackageInspector {
        ParsedApkInfo(context.packageName, 9000L, "0.9.0", setOf("prod_cert"))
      },
    )
    val fakeRepo = object : UpdateRepository {
      override suspend fun checkForUpdates(installed: InstalledAppInfo, forceRefresh: Boolean) =
        UpdateDiscoveryResult.Error(UpdateFailureReason.NETWORK_ERROR, "Offline")

      override suspend fun shouldPerformAutomaticCheck(installed: InstalledAppInfo, nowEpochMs: Long) = false

      override suspend fun restoreCachedUpdate(installed: InstalledAppInfo) =
        AvailableUpdate(
          metadata = meta,
          apkDownloadUrl = "https://github.com/imanpr33trai/pizzabi/releases/download/v0.9.0/${meta.apkAssetName}",
          apkSizeBytes = apkBytes.size.toLong(),
          releasePageUrl = meta.releaseUrl,
          isMandatory = false,
          mandatoryReason = null,
        )
    }
    val noOpDownloader = object : ApkDownloader {
      override fun isDownloadInProgress() = false
      override fun cancelActiveDownload() {}
      override suspend fun downloadApk(update: AvailableUpdate, onProgress: (ApkDownloadProgress) -> Unit) =
        ApkDownloadResult.Success(apkFile)
    }

    // Simulated recreated process without install permission -> restores InstallPermissionRequired
    val fakeInstaller = FakeInstallController(installPermissionGranted = false)
    val recreatedVm = UpdateViewModel(
      updateRepository = fakeRepo,
      apkDownloader = noOpDownloader,
      apkVerifier = verifier,
      apkInstaller = fakeInstaller,
      preferencesStorage = storage,
      installedAppInfoProvider = installedProvider,
    )
    recreatedVm.restorePersistedState()
    assertTrue(recreatedVm.uiState.value.state is UpdateState.InstallPermissionRequired)
    assertFalse(recreatedVm.uiState.value.hasInstallPermission)

    // User grants unknown sources permission in Settings and returns -> onHostResumed auto-launches install
    fakeInstaller.installPermissionGranted = true
    recreatedVm.onHostResumed()
    idleUntil { recreatedVm.uiState.value.state is UpdateState.Installing }
    assertEquals(1, fakeInstaller.startInstallCallCount)

    // Installation cancelled by user transitions cleanly and allows retry
    fakeInstaller.emitStatus(InstallStatusEvent.Cancelled(99, "Installation cancelled by user."))
    idleUntil { recreatedVm.uiState.value.state is UpdateState.Cancelled }

    storage.clearAll()
  }

  @Test
  fun `Compose UI displays settings card, download progress, mandatory blocker, and error retry`() {
    var checkClicks = 0
    var cancelClicks = 0
    var retryClicks = 0
    var dismissClicks = 0

    val optionalUpdate = AvailableUpdate(
      metadata = UpdateMetadata(
        schemaVersion = 1,
        applicationId = "com.aistudio.pizzora.xkqzmw",
        versionName = "0.8.0",
        versionCode = 8000L,
        minimumSupportedVersionCode = 1000L,
        mandatory = false,
        releaseNotes = "- Faster cart sync\n- New crust options",
        apkAssetName = "pizzora-v0.8.0.apk",
        apkSha256 = "c".repeat(64),
        apkSizeBytes = 12L * 1024L * 1024L,
        releaseUrl = "https://github.com/imanpr33trai/pizzabi/releases/tag/v0.8.0",
      ),
      apkDownloadUrl = "https://github.com/imanpr33trai/pizzabi/releases/download/v0.8.0/pizzora-v0.8.0.apk",
      apkSizeBytes = 12L * 1024L * 1024L,
      releasePageUrl = "https://github.com/imanpr33trai/pizzabi/releases/tag/v0.8.0",
      isMandatory = false,
      mandatoryReason = null,
    )

    var uiState by mutableStateOf(
      UpdateUiState(
        installedVersionName = "0.1.0",
        installedVersionCode = 1000L,
        state = UpdateState.UpToDate(checkedAtEpochMs = 1_700_000_000_000L, isManual = true),
        showUpdateDialog = false,
      ),
    )

    composeRule.setContent {
      PizzoraTheme {
        AppUpdatesSettingsCard(
          uiState = uiState,
          onCheckForUpdates = { checkClicks++ },
          onOpenUpdateDialog = {},
          onRetry = { retryClicks++ },
        )
        UpdateHostOverlay(
          uiState = uiState,
          onStartDownloadOrInstall = {},
          onCancelDownload = { cancelClicks++ },
          onDismissOptional = { dismissClicks++ },
          onRequestInstallPermission = {},
          onRetry = { retryClicks++ },
        )
      }
    }

    composeRule.onNodeWithTag("update_settings_card").assertIsDisplayed()
    composeRule.onNodeWithTag("update_version_text").assertIsDisplayed()
    composeRule.onNodeWithTag("update_check_button").performClick()
    assertEquals(1, checkClicks)

    // Switch to optional downloading dialog -> shows progress bar and Cancel
    uiState = uiState.copy(
      state = UpdateState.Downloading(
        update = optionalUpdate,
        bytesDownloaded = 6L * 1024L * 1024L,
        totalBytes = 12L * 1024L * 1024L,
        progressPercent = 50,
      ),
      showUpdateDialog = true,
    )
    composeRule.onNodeWithTag("update_dialog").assertIsDisplayed()
    composeRule.onNodeWithTag("update_progress_bar").assertIsDisplayed()
    composeRule.onNodeWithText("- Faster cart sync\n- New crust options").assertIsDisplayed()
    composeRule.onNodeWithTag("update_cancel_button").performClick()
    assertEquals(1, cancelClicks)

    // Switch to optional UpdateAvailable -> Later dismiss works
    uiState = uiState.copy(state = UpdateState.UpdateAvailable(optionalUpdate), showUpdateDialog = true)
    composeRule.onNodeWithTag("update_later_button").performClick()
    assertEquals(1, dismissClicks)

    // Switch to mandatory failed state -> mandatory blocker shown, no Later button, Retry works
    val mandatoryUpdate = optionalUpdate.copy(
      isMandatory = true,
      mandatoryReason = MandatoryUpdateReason.BELOW_MINIMUM_SUPPORTED_VERSION,
    )
    uiState = uiState.copy(
      state = UpdateState.Failed(
        reason = UpdateFailureReason.CHECKSUM_MISMATCH,
        message = "APK SHA-256 verification failed.",
        update = mandatoryUpdate,
        isMandatoryBlocked = true,
        canRetry = true,
      ),
      showUpdateDialog = true,
    )
    composeRule.onNodeWithTag("update_mandatory_screen").assertIsDisplayed()
    assertEquals(0, composeRule.onAllNodesWithTag("update_later_button").fetchSemanticsNodes().size)
    composeRule.onNodeWithTag("update_error_banner").assertIsDisplayed()
    composeRule.onNodeWithTag("update_retry_button").performClick()
    assertEquals(1, retryClicks)
  }
}

package com.example.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

sealed interface InstallStatusEvent {
  data class PendingUserAction(
    val sessionId: Int,
    val confirmIntent: Intent,
  ) : InstallStatusEvent

  data class Succeeded(
    val sessionId: Int,
  ) : InstallStatusEvent

  data class Cancelled(
    val sessionId: Int,
    val message: String,
  ) : InstallStatusEvent

  data class Failed(
    val sessionId: Int,
    val statusCode: Int,
    val reason: UpdateFailureReason,
    val message: String,
  ) : InstallStatusEvent
}

object UpdateInstallStatusBus {
  private val _events = MutableSharedFlow<InstallStatusEvent>(extraBufferCapacity = 16)
  val events: SharedFlow<InstallStatusEvent> = _events.asSharedFlow()

  internal fun emit(event: InstallStatusEvent) {
    _events.tryEmit(event)
  }
}

class UpdateInstallStatusReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

    when (status) {
      PackageInstaller.STATUS_PENDING_USER_ACTION -> {
        @Suppress("DEPRECATION")
        val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
          intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        if (confirmIntent != null) {
          UpdateInstallStatusBus.emit(
            InstallStatusEvent.PendingUserAction(
              sessionId = sessionId,
              confirmIntent = confirmIntent,
            ),
          )
        } else {
          UpdateInstallStatusBus.emit(
            InstallStatusEvent.Failed(
              sessionId = sessionId,
              statusCode = status,
              reason = UpdateFailureReason.INSTALLER_ERROR,
              message = "Android package installer confirmation intent was missing.",
            ),
          )
        }
      }

      PackageInstaller.STATUS_SUCCESS -> {
        UpdateInstallStatusBus.emit(InstallStatusEvent.Succeeded(sessionId = sessionId))
      }

      PackageInstaller.STATUS_FAILURE_ABORTED -> {
        UpdateInstallStatusBus.emit(
          InstallStatusEvent.Cancelled(
            sessionId = sessionId,
            message = "Installation was cancelled before completing.",
          ),
        )
      }

      PackageInstaller.STATUS_FAILURE_CONFLICT -> {
        UpdateInstallStatusBus.emit(
          InstallStatusEvent.Failed(
            sessionId = sessionId,
            statusCode = status,
            reason = UpdateFailureReason.SIGNATURE_MISMATCH,
            message = "Installation blocked: package signature or version conflicts with the installed app.",
          ),
        )
      }

      PackageInstaller.STATUS_FAILURE_STORAGE -> {
        UpdateInstallStatusBus.emit(
          InstallStatusEvent.Failed(
            sessionId = sessionId,
            statusCode = status,
            reason = UpdateFailureReason.INSUFFICIENT_STORAGE,
            message = "Installation failed due to insufficient device storage.",
          ),
        )
      }

      PackageInstaller.STATUS_FAILURE_INVALID -> {
        UpdateInstallStatusBus.emit(
          InstallStatusEvent.Failed(
            sessionId = sessionId,
            statusCode = status,
            reason = UpdateFailureReason.INVALID_APK,
            message = "Android package installer rejected the APK as invalid.",
          ),
        )
      }

      PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
        UpdateInstallStatusBus.emit(
          InstallStatusEvent.Failed(
            sessionId = sessionId,
            statusCode = status,
            reason = UpdateFailureReason.INCOMPATIBLE_DEVICE,
            message = "Update APK is incompatible with this device.",
          ),
        )
      }

      else -> {
        val detail = statusMessage.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        UpdateInstallStatusBus.emit(
          InstallStatusEvent.Failed(
            sessionId = sessionId,
            statusCode = status,
            reason = UpdateFailureReason.INSTALLER_ERROR,
            message = "Android package installation failed$detail.",
          ),
        )
      }
    }
  }
}

sealed interface ApkInstallLaunchResult {
  data object PermissionRequired : ApkInstallLaunchResult
  data class Launched(val sessionId: Int) : ApkInstallLaunchResult
  data class Failed(
    val reason: UpdateFailureReason,
    val message: String,
  ) : ApkInstallLaunchResult
}

interface ApkInstallController {
  val statusEvents: SharedFlow<InstallStatusEvent>
  fun canRequestPackageInstalls(): Boolean
  fun createManageUnknownAppSourcesIntent(): Intent
  suspend fun startInstall(apkFile: File): ApkInstallLaunchResult
}

class AndroidPackageApkInstaller(
  private val context: Context,
) : ApkInstallController {
  override val statusEvents: SharedFlow<InstallStatusEvent> = UpdateInstallStatusBus.events

  override fun canRequestPackageInstalls(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    } else {
      true
    }

  override fun createManageUnknownAppSourcesIntent(): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
      )
    } else {
      Intent(Settings.ACTION_SECURITY_SETTINGS)
    }

  override suspend fun startInstall(apkFile: File): ApkInstallLaunchResult = withContext(Dispatchers.IO) {
    if (!canRequestPackageInstalls()) {
      return@withContext ApkInstallLaunchResult.PermissionRequired
    }
    if (!apkFile.isFile || apkFile.length() <= 0L) {
      return@withContext ApkInstallLaunchResult.Failed(
        reason = UpdateFailureReason.INVALID_APK,
        message = "Verified APK file is missing.",
      )
    }

    val packageInstaller = context.packageManager.packageInstaller
    // Clean up any stale incomplete sessions owned by this app before creating a new one.
    runCatching {
      packageInstaller.mySessions.forEach { info ->
        runCatching { packageInstaller.abandonSession(info.sessionId) }
      }
    }

    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
      setAppPackageName(context.packageName)
      setSize(apkFile.length())
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        setInstallReason(PackageManager.INSTALL_REASON_USER)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
      }
    }

    var sessionId = -1
    var session: PackageInstaller.Session? = null
    try {
      sessionId = packageInstaller.createSession(params)
      session = packageInstaller.openSession(sessionId)

      session.openWrite("pizzora-update.apk", 0, apkFile.length()).use { out ->
        apkFile.inputStream().use { input ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            out.write(buffer, 0, read)
          }
        }
        session.fsync(out)
      }

      val statusIntent = Intent(context, UpdateInstallStatusReceiver::class.java).apply {
        setPackage(context.packageName)
      }
      val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
      } else {
        PendingIntent.FLAG_UPDATE_CURRENT
      }
      val pendingIntent = PendingIntent.getBroadcast(
        context,
        sessionId,
        statusIntent,
        flags,
      )

      session.commit(pendingIntent.intentSender)
      ApkInstallLaunchResult.Launched(sessionId = sessionId)
    } catch (sec: SecurityException) {
      if (sessionId >= 0) {
        runCatching { packageInstaller.abandonSession(sessionId) }
      }
      ApkInstallLaunchResult.Failed(
        reason = UpdateFailureReason.INSTALL_PERMISSION_REQUIRED,
        message = sec.message ?: "Missing permission to request package installation.",
      )
    } catch (t: Throwable) {
      if (sessionId >= 0) {
        runCatching { packageInstaller.abandonSession(sessionId) }
      }
      ApkInstallLaunchResult.Failed(
        reason = UpdateFailureReason.INSTALLER_ERROR,
        message = t.message?.takeIf { it.isNotBlank() } ?: "Could not start Android PackageInstaller session.",
      )
    } finally {
      runCatching { session?.close() }
    }
  }
}

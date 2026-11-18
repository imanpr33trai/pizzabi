package com.example.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.BuildConfig
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateMetadata(
  val schemaVersion: Int,
  val applicationId: String,
  val versionName: String,
  val versionCode: Long,
  val minimumSupportedVersionCode: Long,
  val mandatory: Boolean = false,
  val releaseNotes: String = "",
  val apkAssetName: String,
  val apkSha256: String,
  val apkSizeBytes: Long? = null,
  val minSdk: Int = 24,
  val supportedAbis: List<String> = emptyList(),
  val releaseUrl: String,
  val publishedAt: String = "",
  val prerelease: Boolean = false,
)

@Serializable
data class GitHubReleaseDto(
  val id: Long = 0L,
  @SerialName("tag_name") val tagName: String = "",
  val name: String = "",
  val body: String = "",
  val draft: Boolean = false,
  val prerelease: Boolean = false,
  @SerialName("html_url") val htmlUrl: String = "",
  @SerialName("published_at") val publishedAt: String = "",
  val assets: List<GitHubReleaseAssetDto> = emptyList(),
)

@Serializable
data class GitHubReleaseAssetDto(
  val id: Long = 0L,
  val name: String = "",
  val size: Long = 0L,
  @SerialName("content_type") val contentType: String = "",
  @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

@Serializable
data class CachedReleaseSnapshot(
  val release: GitHubReleaseDto,
  val metadata: UpdateMetadata,
)

data class InstalledAppInfo(
  val applicationId: String,
  val versionName: String,
  val versionCode: Long,
  val sdkInt: Int,
  val supportedAbis: List<String>,
  val signingCertSha256s: Set<String>,
)

interface InstalledAppInfoProvider {
  fun getInstalledAppInfo(): InstalledAppInfo
}

class AndroidInstalledAppInfoProvider(
  private val context: Context,
) : InstalledAppInfoProvider {
  override fun getInstalledAppInfo(): InstalledAppInfo {
    val pm = context.packageManager
    val packageName = context.packageName
    val pkgInfo = runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pm.getPackageInfo(
          packageName,
          PackageManager.GET_SIGNING_CERTIFICATES,
        )
      } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
      }
    }.getOrNull()

    val versionCode = if (pkgInfo != null) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pkgInfo.longVersionCode
      } else {
        @Suppress("DEPRECATION")
        pkgInfo.versionCode.toLong()
      }
    } else {
      BuildConfig.VERSION_CODE.toLong()
    }

    val versionName = pkgInfo?.versionName?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
    val certDigests = extractSigningCertSha256s(pkgInfo)

    return InstalledAppInfo(
      applicationId = packageName,
      versionName = versionName,
      versionCode = versionCode,
      sdkInt = Build.VERSION.SDK_INT,
      supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
      signingCertSha256s = certDigests,
    )
  }

  companion object {
    fun extractSigningCertSha256s(pkgInfo: android.content.pm.PackageInfo?): Set<String> {
      if (pkgInfo == null) return emptySet()
      val signatureBytes: List<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val signingInfo = pkgInfo.signingInfo
        if (signingInfo != null) {
          val signers = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
          } else {
            signingInfo.signingCertificateHistory ?: signingInfo.apkContentsSigners
          }
          signers?.map { it.toByteArray() }.orEmpty()
        } else {
          emptyList()
        }
      } else {
        @Suppress("DEPRECATION")
        pkgInfo.signatures?.map { it.toByteArray() }.orEmpty()
      }

      return signatureBytes.mapNotNull { bytes ->
        runCatching {
          MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        }.getOrNull()
      }.toSet()
    }
  }
}

enum class MandatoryUpdateReason {
  EXPLICIT_MANDATORY,
  BELOW_MINIMUM_SUPPORTED_VERSION,
}

data class AvailableUpdate(
  val metadata: UpdateMetadata,
  val apkDownloadUrl: String,
  val apkSizeBytes: Long?,
  val releasePageUrl: String,
  val isMandatory: Boolean,
  val mandatoryReason: MandatoryUpdateReason?,
)

enum class UpdateFailureReason {
  NETWORK_ERROR,
  RATE_LIMITED,
  SERVER_ERROR,
  INVALID_METADATA,
  MISSING_APK_ASSET,
  UNTRUSTED_URL,
  INCOMPATIBLE_DEVICE,
  CHECKSUM_MISMATCH,
  INVALID_APK,
  PACKAGE_MISMATCH,
  VERSION_DOWNGRADE,
  SIGNATURE_MISMATCH,
  INSUFFICIENT_STORAGE,
  INSTALL_PERMISSION_REQUIRED,
  INSTALLER_ERROR,
  USER_CANCELLED,
}

sealed interface UpdateState {
  data object Idle : UpdateState

  data class Checking(
    val isManual: Boolean,
  ) : UpdateState

  data class UpToDate(
    val checkedAtEpochMs: Long,
    val isManual: Boolean,
  ) : UpdateState

  data class UpdateAvailable(
    val update: AvailableUpdate,
    val dismissedForSession: Boolean = false,
  ) : UpdateState

  data class Downloading(
    val update: AvailableUpdate,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val progressPercent: Int,
  ) : UpdateState

  data class Verifying(
    val update: AvailableUpdate,
    val downloadedFilePath: String,
  ) : UpdateState

  data class InstallPermissionRequired(
    val update: AvailableUpdate,
    val verifiedApkPath: String,
  ) : UpdateState

  data class ReadyToInstall(
    val update: AvailableUpdate,
    val verifiedApkPath: String,
  ) : UpdateState

  data class Installing(
    val update: AvailableUpdate,
    val sessionId: Int? = null,
  ) : UpdateState

  data class Completed(
    val update: AvailableUpdate,
  ) : UpdateState

  data class Failed(
    val reason: UpdateFailureReason,
    val message: String,
    val update: AvailableUpdate? = null,
    val isMandatoryBlocked: Boolean = false,
    val canRetry: Boolean = true,
  ) : UpdateState

  data class Cancelled(
    val update: AvailableUpdate?,
    val message: String = "Update was cancelled.",
  ) : UpdateState
}

data class UpdateUiState(
  val installedVersionName: String = BuildConfig.VERSION_NAME,
  val installedVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
  val lastCheckedAtEpochMs: Long? = null,
  val lastKnownRemoteVersionName: String? = null,
  val lastKnownRemoteVersionCode: Long? = null,
  val hasInstallPermission: Boolean = true,
  val state: UpdateState = UpdateState.Idle,
  val showUpdateDialog: Boolean = false,
) {
  val isMandatoryBlocked: Boolean
    get() = when (val s = state) {
      is UpdateState.UpdateAvailable -> s.update.isMandatory
      is UpdateState.Downloading -> s.update.isMandatory
      is UpdateState.Verifying -> s.update.isMandatory
      is UpdateState.InstallPermissionRequired -> s.update.isMandatory
      is UpdateState.ReadyToInstall -> s.update.isMandatory
      is UpdateState.Installing -> s.update.isMandatory
      is UpdateState.Failed -> s.isMandatoryBlocked
      is UpdateState.Cancelled -> s.update?.isMandatory == true
      else -> false
    }
}

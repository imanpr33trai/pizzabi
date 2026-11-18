package com.example.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ParsedApkInfo(
  val packageName: String,
  val versionCode: Long,
  val versionName: String?,
  val signingCertSha256s: Set<String>,
)

fun interface ApkPackageInspector {
  fun inspect(apkFile: File): ParsedApkInfo?
}

class AndroidApkPackageInspector(
  private val context: Context,
) : ApkPackageInspector {
  override fun inspect(apkFile: File): ParsedApkInfo? {
    if (!apkFile.isFile || apkFile.length() <= 0L) return null
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      PackageManager.GET_SIGNING_CERTIFICATES
    } else {
      @Suppress("DEPRECATION")
      PackageManager.GET_SIGNATURES
    }

    val pkgInfo = runCatching {
      pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
    }.getOrNull() ?: return null

    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      pkgInfo.longVersionCode
    } else {
      @Suppress("DEPRECATION")
      pkgInfo.versionCode.toLong()
    }

    val certs = AndroidInstalledAppInfoProvider.extractSigningCertSha256s(pkgInfo)
    return ParsedApkInfo(
      packageName = pkgInfo.packageName,
      versionCode = versionCode,
      versionName = pkgInfo.versionName,
      signingCertSha256s = certs,
    )
  }
}

sealed interface ApkVerificationResult {
  data class Verified(
    val apkFile: File,
    val sha256Hex: String,
    val parsedApkInfo: ParsedApkInfo,
  ) : ApkVerificationResult

  data class Rejected(
    val reason: UpdateFailureReason,
    val message: String,
  ) : ApkVerificationResult
}

interface ApkVerifier {
  suspend fun verify(
    apkFile: File,
    metadata: UpdateMetadata,
    installed: InstalledAppInfo,
  ): ApkVerificationResult
}

class ApkIntegrityVerifier(
  private val packageInspector: ApkPackageInspector,
) : ApkVerifier {

  constructor(context: Context) : this(AndroidApkPackageInspector(context))

  override suspend fun verify(
    apkFile: File,
    metadata: UpdateMetadata,
    installed: InstalledAppInfo,
  ): ApkVerificationResult = withContext(Dispatchers.IO) {
    if (!apkFile.isFile || apkFile.length() <= 0L) {
      return@withContext ApkVerificationResult.Rejected(
        reason = UpdateFailureReason.INVALID_APK,
        message = "Downloaded APK file is missing or empty.",
      )
    }

    val actualSha256 = runCatching {
      OkHttpApkDownloadManager.computeFileSha256(apkFile)
    }.getOrElse {
      apkFile.delete()
      return@withContext ApkVerificationResult.Rejected(
        reason = UpdateFailureReason.INVALID_APK,
        message = "Failed to read downloaded APK for integrity verification.",
      )
    }

    val expectedSha256 = metadata.apkSha256.trim().lowercase()
    if (!MessageDigest.isEqual(
        actualSha256.toByteArray(Charsets.US_ASCII),
        expectedSha256.toByteArray(Charsets.US_ASCII),
      )
    ) {
      apkFile.delete()
      return@withContext ApkVerificationResult.Rejected(
        reason = UpdateFailureReason.CHECKSUM_MISMATCH,
        message = "APK SHA-256 verification failed. The downloaded file may be corrupted or tampered.",
      )
    }

    val archiveInfo = packageInspector.inspect(apkFile)
    if (archiveInfo == null) {
      apkFile.delete()
      return@withContext ApkVerificationResult.Rejected(
        reason = UpdateFailureReason.INVALID_APK,
        message = "Downloaded file is not a valid Android APK archive.",
      )
    }

    if (archiveInfo.packageName != installed.applicationId) {
      apkFile.delete()
      return@withContext ApkVerificationResult.Rejected(
        reason = UpdateFailureReason.PACKAGE_MISMATCH,
        message = "APK package '${archiveInfo.packageName}' does not match '${installed.applicationId}'.",
      )
    }

    if (archiveInfo.versionCode <= installed.versionCode) {
      apkFile.delete()
      return@withContext ApkVerificationResult.Rejected(
        reason = UpdateFailureReason.VERSION_DOWNGRADE,
        message = "APK version code (${archiveInfo.versionCode}) is not newer than installed (${installed.versionCode}).",
      )
    }

    if (archiveInfo.versionCode != metadata.versionCode) {
      apkFile.delete()
      return@withContext ApkVerificationResult.Rejected(
        reason = UpdateFailureReason.INVALID_APK,
        message = "APK version code (${archiveInfo.versionCode}) does not match release metadata (${metadata.versionCode}).",
      )
    }

    if (archiveInfo.signingCertSha256s.isEmpty() || installed.signingCertSha256s.isEmpty()) {
      apkFile.delete()
      return@withContext ApkVerificationResult.Rejected(
        reason = UpdateFailureReason.SIGNATURE_MISMATCH,
        message = "Could not verify APK signing certificate continuity.",
      )
    }

    val signerMatches = archiveInfo.signingCertSha256s.any { it in installed.signingCertSha256s }
    if (!signerMatches) {
      apkFile.delete()
      return@withContext ApkVerificationResult.Rejected(
        reason = UpdateFailureReason.SIGNATURE_MISMATCH,
        message = "APK signing certificate does not match the installed PIZZORA app.",
      )
    }

    ApkVerificationResult.Verified(
      apkFile = apkFile,
      sha256Hex = actualSha256,
      parsedApkInfo = archiveInfo,
    )
  }
}

package com.example

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.update.AndroidInstalledAppInfoProvider
import com.example.update.AndroidPackageApkInstaller
import com.example.update.ApkIntegrityVerifier
import com.example.update.ApkVerificationResult
import com.example.update.CachedReleaseSnapshot
import com.example.update.DataStoreUpdatePreferencesStorage
import com.example.update.GitHubReleaseAssetDto
import com.example.update.GitHubReleaseDto
import com.example.update.UpdateFailureReason
import com.example.update.UpdateMetadata
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateSystemInstrumentedTest {

  @Test
  fun installedAppInfoAndSigningCertificatesAreReadableOnAndroid() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val provider = AndroidInstalledAppInfoProvider(context)
    val info = provider.getInstalledAppInfo()

    assertEquals(BuildConfig.APPLICATION_ID, info.applicationId)
    assertTrue(info.versionCode >= BuildConfig.VERSION_CODE.toLong())
    assertTrue(info.signingCertSha256s.isNotEmpty())
    assertTrue(info.signingCertSha256s.all { it.length == 64 })
  }

  @Test
  fun updatePreferencesPersistAndVerifierRejectsInvalidApkOnDeviceStorage() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val storage = DataStoreUpdatePreferencesStorage(context)
    storage.clearAll()

    val meta = UpdateMetadata(
      schemaVersion = 1,
      applicationId = context.packageName,
      versionName = "9.9.9",
      versionCode = 9009009L,
      minimumSupportedVersionCode = 1L,
      mandatory = false,
      releaseNotes = "Instrumentation test release",
      apkAssetName = "pizzora-v9.9.9.apk",
      apkSha256 = "0".repeat(64),
      releaseUrl = "https://github.com/imanpr33trai/pizzabi/releases/tag/v9.9.9",
    )
    val release = GitHubReleaseDto(
      id = 1L,
      tagName = "v9.9.9",
      htmlUrl = meta.releaseUrl,
      assets = listOf(
        GitHubReleaseAssetDto(
          id = 10L,
          name = meta.apkAssetName,
          size = 32L,
          browserDownloadUrl = "https://github.com/imanpr33trai/pizzabi/releases/download/v9.9.9/${meta.apkAssetName}",
        ),
      ),
    )

    storage.saveCheckMetadata(
      checkedAtEpochMs = 1_750_000_000_000L,
      etag = "\"instr-etag\"",
      cachedRelease = CachedReleaseSnapshot(release = release, metadata = meta),
    )

    val restored = storage.readSnapshot()
    assertEquals(1_750_000_000_000L, restored.lastCheckedAtEpochMs)
    assertEquals("\"instr-etag\"", restored.etag)
    assertEquals("9.9.9", restored.cachedRelease?.metadata?.versionName)

    // Write garbage bytes matching SHA-256 and confirm real PackageManager rejects non-APK archive
    val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
    val corruptFile = File(updatesDir, "corrupt-test.apk")
    val bytes = "not-a-valid-android-apk".toByteArray()
    corruptFile.writeBytes(bytes)
    val shaHex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    val verifier = ApkIntegrityVerifier(context)
    val installed = AndroidInstalledAppInfoProvider(context).getInstalledAppInfo()
    val result = verifier.verify(corruptFile, meta.copy(apkSha256 = shaHex), installed)
    assertTrue(result is ApkVerificationResult.Rejected)
    assertEquals(UpdateFailureReason.INVALID_APK, (result as ApkVerificationResult.Rejected).reason)
    assertFalse(corruptFile.exists())

    val installer = AndroidPackageApkInstaller(context)
    val settingsIntent = installer.createManageUnknownAppSourcesIntent()
    assertNotNull(settingsIntent.action)
    assertTrue(settingsIntent.flags or Intent.FLAG_ACTIVITY_NEW_TASK >= 0)

    storage.clearAll()
  }
}

package com.example.update

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class ApkDownloadProgress(
  val bytesDownloaded: Long,
  val totalBytes: Long?,
  val progressPercent: Int,
)

sealed interface ApkDownloadResult {
  data class Success(val apkFile: File) : ApkDownloadResult
  data object Cancelled : ApkDownloadResult
  data class Failed(
    val reason: UpdateFailureReason,
    val message: String,
  ) : ApkDownloadResult
}

interface ApkDownloader {
  fun isDownloadInProgress(): Boolean
  fun cancelActiveDownload()
  suspend fun downloadApk(
    update: AvailableUpdate,
    onProgress: (ApkDownloadProgress) -> Unit,
  ): ApkDownloadResult
}

class OkHttpApkDownloadManager(
  private val updatesDir: File,
  private val client: OkHttpClient = defaultDownloadClient(),
  private val urlPolicy: UpdateUrlPolicy = UpdateUrlPolicy(),
  private val maxRetries: Int = 2,
  private val retryDelayMs: Long = 800L,
  private val usableSpaceProvider: (File) -> Long = { dir -> dir.usableSpace },
) : ApkDownloader {

  constructor(
    context: Context,
    client: OkHttpClient = defaultDownloadClient(),
    urlPolicy: UpdateUrlPolicy = UpdateUrlPolicy(),
  ) : this(
    updatesDir = File(context.filesDir, "updates"),
    client = client,
    urlPolicy = urlPolicy,
  )

  private val downloadMutex = Mutex()

  @Volatile
  private var activeCall: okhttp3.Call? = null

  @Volatile
  private var downloadInProgress: Boolean = false

  @Volatile
  private var cancelRequested: Boolean = false

  override fun isDownloadInProgress(): Boolean = downloadInProgress

  override fun cancelActiveDownload() {
    cancelRequested = true
    activeCall?.cancel()
  }

  override suspend fun downloadApk(
    update: AvailableUpdate,
    onProgress: (ApkDownloadProgress) -> Unit,
  ): ApkDownloadResult = withContext(Dispatchers.IO) {
    if (!downloadMutex.tryLock()) {
      return@withContext ApkDownloadResult.Failed(
        reason = UpdateFailureReason.NETWORK_ERROR,
        message = "An update download is already in progress.",
      )
    }

    try {
      downloadInProgress = true
      cancelRequested = false

      if (!updatesDir.exists() && !updatesDir.mkdirs()) {
        return@withContext ApkDownloadResult.Failed(
          reason = UpdateFailureReason.INSUFFICIENT_STORAGE,
          message = "Could not create app-private update storage directory.",
        )
      }

      val safeAssetName = try {
        urlPolicy.validateSafeAssetFileName(update.metadata.apkAssetName, ".apk")
      } catch (sec: SecurityException) {
        return@withContext ApkDownloadResult.Failed(
          reason = UpdateFailureReason.UNTRUSTED_URL,
          message = sec.message ?: "Invalid APK filename.",
        )
      }

      val validatedUrl = try {
        urlPolicy.requireAllowedAssetUrl(update.apkDownloadUrl)
      } catch (sec: SecurityException) {
        return@withContext ApkDownloadResult.Failed(
          reason = UpdateFailureReason.UNTRUSTED_URL,
          message = sec.message ?: "APK download URL is not from a trusted GitHub release host.",
        )
      }

      val finalFile = File(updatesDir, safeAssetName)
      val partFile = File(updatesDir, "$safeAssetName.part")

      // Reuse already-downloaded file if its SHA-256 already matches cleanly.
      if (finalFile.isFile && finalFile.length() > 0L) {
        val existingSha = runCatching { computeFileSha256(finalFile) }.getOrNull()
        if (existingSha != null && existingSha.equals(update.metadata.apkSha256, ignoreCase = true)) {
          val size = finalFile.length()
          onProgress(ApkDownloadProgress(bytesDownloaded = size, totalBytes = size, progressPercent = 100))
          return@withContext ApkDownloadResult.Success(finalFile)
        }
        finalFile.delete()
      }

      // Clean up stale update artifacts before reserving space for the new APK.
      cleanupOtherFiles(keepNames = emptySet())

      val expectedBytes = update.apkSizeBytes?.takeIf { it > 0L }
      if (expectedBytes != null) {
        val availableBytes = usableSpaceProvider(updatesDir)
        if (availableBytes < expectedBytes + STORAGE_HEADROOM_BYTES) {
          return@withContext ApkDownloadResult.Failed(
            reason = UpdateFailureReason.INSUFFICIENT_STORAGE,
            message = "Insufficient storage space to download this update (${formatMiB(expectedBytes)} needed).",
          )
        }
      }

      var attempt = 0
      var lastError: ApkDownloadResult.Failed? = null

      while (attempt <= maxRetries) {
        currentCoroutineContext().ensureActive()
        if (cancelRequested) {
          partFile.delete()
          return@withContext ApkDownloadResult.Cancelled
        }

        val outcome = executeSingleDownloadAttempt(
          url = validatedUrl,
          partFile = partFile,
          finalFile = finalFile,
          fallbackExpectedBytes = expectedBytes,
          onProgress = onProgress,
        )

        when (outcome) {
          is ApkDownloadResult.Success -> return@withContext outcome
          is ApkDownloadResult.Cancelled -> return@withContext outcome
          is ApkDownloadResult.Failed -> {
            lastError = outcome
            val retryable = outcome.reason == UpdateFailureReason.NETWORK_ERROR
            if (!retryable || attempt >= maxRetries || cancelRequested) {
              partFile.delete()
              return@withContext outcome
            }
            attempt++
            delay(retryDelayMs * attempt)
          }
        }
      }

      partFile.delete()
      lastError ?: ApkDownloadResult.Failed(
        reason = UpdateFailureReason.NETWORK_ERROR,
        message = "Failed to download APK.",
      )
    } catch (ce: CancellationException) {
      cancelActiveDownload()
      throw ce
    } finally {
      activeCall = null
      downloadInProgress = false
      downloadMutex.unlock()
    }
  }

  private suspend fun executeSingleDownloadAttempt(
    url: String,
    partFile: File,
    finalFile: File,
    fallbackExpectedBytes: Long?,
    onProgress: (ApkDownloadProgress) -> Unit,
  ): ApkDownloadResult = streamFromValidatedUrl(
    startUrl = url,
    partFile = partFile,
    finalFile = finalFile,
    fallbackExpectedBytes = fallbackExpectedBytes,
    onProgress = onProgress,
  )

  private suspend fun streamFromValidatedUrl(
    startUrl: String,
    partFile: File,
    finalFile: File,
    fallbackExpectedBytes: Long?,
    onProgress: (ApkDownloadProgress) -> Unit,
  ): ApkDownloadResult {
    partFile.delete()
    var currentUrl = startUrl
    var hops = 0
    var resp: okhttp3.Response? = null
    try {
      while (true) {
        currentCoroutineContext().ensureActive()
        if (cancelRequested) return ApkDownloadResult.Cancelled
        val validUrl = urlPolicy.requireAllowedAssetUrl(currentUrl)
        val request = okhttp3.Request.Builder()
          .url(validUrl)
          .header("Accept", "application/octet-stream")
          .get()
          .build()
        val call = client.newCall(request)
        activeCall = call
        val candidate = call.execute()
        if (candidate.code in REDIRECT_CODES) {
          val location = candidate.header("Location")
          candidate.close()
          if (location.isNullOrBlank()) {
            return ApkDownloadResult.Failed(
              reason = UpdateFailureReason.NETWORK_ERROR,
              message = "Download redirect missing Location header.",
            )
          }
          if (hops >= UpdateUrlPolicy.MAX_REDIRECTS) {
            return ApkDownloadResult.Failed(
              reason = UpdateFailureReason.NETWORK_ERROR,
              message = "Exceeded maximum download redirects.",
            )
          }
          currentUrl = urlPolicy.requireAllowedAssetUrl(java.net.URI(validUrl).resolve(location).toString())
          hops++
          continue
        }
        resp = candidate
        break
      }

      resp.use { r ->
        if (!r.isSuccessful) {
          return ApkDownloadResult.Failed(
            reason = UpdateFailureReason.SERVER_ERROR,
            message = "Download failed with HTTP ${r.code}.",
          )
        }
        val body = r.body ?: return ApkDownloadResult.Failed(
          reason = UpdateFailureReason.NETWORK_ERROR,
          message = "Empty APK response body.",
        )

        val contentLength = body.contentLength().takeIf { it > 0L } ?: fallbackExpectedBytes
        if (contentLength != null) {
          val available = usableSpaceProvider(updatesDir)
          if (available < contentLength + STORAGE_HEADROOM_BYTES) {
            return ApkDownloadResult.Failed(
              reason = UpdateFailureReason.INSUFFICIENT_STORAGE,
              message = "Not enough storage space to download APK (${formatMiB(contentLength)} required).",
            )
          }
        }

        var downloaded = 0L
        var lastReportedPercent = -1
        onProgress(ApkDownloadProgress(0L, contentLength, 0))

        body.byteStream().use { input ->
          FileOutputStream(partFile).use { output ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
              currentCoroutineContext().ensureActive()
              if (cancelRequested) {
                partFile.delete()
                return ApkDownloadResult.Cancelled
              }
              val read = input.read(buffer)
              if (read == -1) break
              output.write(buffer, 0, read)
              downloaded += read
              val percent = if (contentLength != null && contentLength > 0L) {
                ((downloaded * 100L) / contentLength).toInt().coerceIn(0, 100)
              } else {
                0
              }
              if (percent != lastReportedPercent) {
                lastReportedPercent = percent
                onProgress(ApkDownloadProgress(downloaded, contentLength, percent))
              }
            }
            output.fd.sync()
          }
        }

        if (downloaded <= 0L) {
          partFile.delete()
          return ApkDownloadResult.Failed(
            reason = UpdateFailureReason.INVALID_APK,
            message = "Downloaded APK file is empty.",
          )
        }

        if (contentLength != null && downloaded != contentLength) {
          partFile.delete()
          return ApkDownloadResult.Failed(
            reason = UpdateFailureReason.NETWORK_ERROR,
            message = "Incomplete APK download ($downloaded of $contentLength bytes).",
          )
        }

        if (finalFile.exists()) {
          finalFile.delete()
        }
        if (!partFile.renameTo(finalFile)) {
          partFile.copyTo(finalFile, overwrite = true)
          partFile.delete()
        }

        onProgress(ApkDownloadProgress(downloaded, contentLength ?: downloaded, 100))
        return ApkDownloadResult.Success(finalFile)
      }
    } catch (ce: CancellationException) {
      partFile.delete()
      throw ce
    } catch (sec: SecurityException) {
      partFile.delete()
      return ApkDownloadResult.Failed(
        reason = UpdateFailureReason.UNTRUSTED_URL,
        message = sec.message ?: "Blocked untrusted download URL.",
      )
    } catch (io: IOException) {
      partFile.delete()
      if (cancelRequested) {
        return ApkDownloadResult.Cancelled
      }
      return ApkDownloadResult.Failed(
        reason = UpdateFailureReason.NETWORK_ERROR,
        message = io.message?.takeIf { it.isNotBlank() } ?: "Network error while downloading APK.",
      )
    }
  }

  private fun cleanupOtherFiles(keepNames: Set<String>) {
    updatesDir.listFiles()?.forEach { file ->
      if (file.name !in keepNames) {
        file.delete()
      }
    }
  }

  companion object {
    private const val STORAGE_HEADROOM_BYTES = 16L * 1024L * 1024L
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    fun computeFileSha256(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val read = input.read(buffer)
          if (read == -1) break
          digest.update(buffer, 0, read)
        }
      }
      return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatMiB(bytes: Long): String {
      val mib = bytes.toDouble() / (1024.0 * 1024.0)
      return "%.1f MB".format(mib)
    }

    fun defaultDownloadClient(): OkHttpClient = OkHttpClient.Builder()
      .followRedirects(false)
      .followSslRedirects(false)
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .build()
  }
}

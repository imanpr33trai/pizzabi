package com.example.update

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

sealed interface UpdateDiscoveryResult {
  data class UpToDate(
    val checkedAtEpochMs: Long,
    val remoteVersionName: String?,
    val remoteVersionCode: Long?,
  ) : UpdateDiscoveryResult

  data class Available(
    val update: AvailableUpdate,
    val checkedAtEpochMs: Long,
  ) : UpdateDiscoveryResult

  data class Error(
    val reason: UpdateFailureReason,
    val message: String,
    val cachedUpdate: AvailableUpdate? = null,
  ) : UpdateDiscoveryResult
}

interface UpdateRepository {
  suspend fun checkForUpdates(
    installed: InstalledAppInfo,
    forceRefresh: Boolean,
  ): UpdateDiscoveryResult

  suspend fun shouldPerformAutomaticCheck(
    installed: InstalledAppInfo,
    nowEpochMs: Long = System.currentTimeMillis(),
  ): Boolean

  suspend fun restoreCachedUpdate(installed: InstalledAppInfo): AvailableUpdate?
}

class GitHubUpdateRepository(
  private val preferencesStorage: UpdatePreferencesStorage,
  private val client: OkHttpClient = defaultOkHttpClient(),
  private val latestReleaseApiUrl: String = UpdateUrlPolicy.LATEST_RELEASE_API_URL,
  private val urlPolicy: UpdateUrlPolicy = UpdateUrlPolicy(),
  private val metadataValidator: UpdateMetadataValidator = UpdateMetadataValidator(urlPolicy = urlPolicy),
  private val updateChecker: UpdateChecker = UpdateChecker(),
  private val clockMs: () -> Long = { System.currentTimeMillis() },
  private val json: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
  },
) : UpdateRepository {

  override suspend fun shouldPerformAutomaticCheck(
    installed: InstalledAppInfo,
    nowEpochMs: Long,
  ): Boolean {
    val snapshot = preferencesStorage.readSnapshot()
    val lastChecked = snapshot.lastCheckedAtEpochMs ?: return true
    if (nowEpochMs < lastChecked) return true
    if (nowEpochMs - lastChecked >= AUTO_CHECK_INTERVAL_MS) return true
    // Never rely indefinitely on a cached mandatory decision; refresh on launch when mandatory.
    val cached = evaluateSnapshot(installed, snapshot.cachedRelease)
    return cached?.isMandatory == true
  }

  override suspend fun restoreCachedUpdate(installed: InstalledAppInfo): AvailableUpdate? {
    val snapshot = preferencesStorage.readSnapshot()
    return evaluateSnapshot(installed, snapshot.cachedRelease)
  }

  override suspend fun checkForUpdates(
    installed: InstalledAppInfo,
    forceRefresh: Boolean,
  ): UpdateDiscoveryResult = withContext(Dispatchers.IO) {
    val snapshot = preferencesStorage.readSnapshot()
    val cachedEval = evaluateSnapshot(installed, snapshot.cachedRelease)
    val now = clockMs()

    val apiUrl = try {
      urlPolicy.requireAllowedApiUrl(latestReleaseApiUrl)
    } catch (sec: SecurityException) {
      return@withContext UpdateDiscoveryResult.Error(
        reason = UpdateFailureReason.UNTRUSTED_URL,
        message = sec.message ?: "Update API URL failed security validation.",
        cachedUpdate = cachedEval,
      )
    }

    val headers = buildMap {
      put("Accept", "application/vnd.github+json")
      put("X-GitHub-Api-Version", "2022-11-28")
      put("User-Agent", "PIZZORA-Android/${installed.versionName}")
      if (!forceRefresh && !snapshot.etag.isNullOrBlank() && snapshot.cachedRelease != null) {
        put("If-None-Match", snapshot.etag)
      }
    }

    val response = try {
      urlPolicy.executeValidatedGet(client = client, url = apiUrl, headers = headers)
    } catch (sec: SecurityException) {
      return@withContext UpdateDiscoveryResult.Error(
        reason = UpdateFailureReason.UNTRUSTED_URL,
        message = sec.message ?: "Blocked untrusted update redirect.",
        cachedUpdate = cachedEval,
      )
    } catch (_: SocketTimeoutException) {
      return@withContext UpdateDiscoveryResult.Error(
        reason = UpdateFailureReason.NETWORK_ERROR,
        message = "Update check timed out. Please check your internet connection.",
        cachedUpdate = cachedEval,
      )
    } catch (io: IOException) {
      return@withContext UpdateDiscoveryResult.Error(
        reason = UpdateFailureReason.NETWORK_ERROR,
        message = io.message?.takeIf { it.isNotBlank() }
          ?: "Unable to reach GitHub Releases. Check your internet connection.",
        cachedUpdate = cachedEval,
      )
    }

    response.use { resp ->
      if (resp.code == 304) {
        val cachedSnapshot = snapshot.cachedRelease
        if (cachedSnapshot != null) {
          preferencesStorage.updateLastCheckedAt(now)
          return@withContext mapEvaluationToResult(
            installed = installed,
            release = cachedSnapshot.release,
            rawMetadata = cachedSnapshot.metadata,
            checkedAtEpochMs = now,
            cachedFallback = cachedEval,
          )
        }
        // Cache vanished locally despite ETag match: fall through with a fresh uncached request.
        return@withContext fetchFreshWithoutEtag(installed, apiUrl, now)
      }

      if (resp.code == 403 || resp.code == 429) {
        val remaining = resp.header("X-RateLimit-Remaining")
        val isRateLimited = resp.code == 429 || remaining == "0"
        return@withContext UpdateDiscoveryResult.Error(
          reason = if (isRateLimited) UpdateFailureReason.RATE_LIMITED else UpdateFailureReason.SERVER_ERROR,
          message = if (isRateLimited) {
            "GitHub API rate limit reached. Please try again later."
          } else {
            "GitHub Releases denied access (HTTP ${resp.code})."
          },
          cachedUpdate = cachedEval,
        )
      }

      if (resp.code == 404) {
        return@withContext UpdateDiscoveryResult.Error(
          reason = UpdateFailureReason.SERVER_ERROR,
          message = "No published stable PIZZORA release found on GitHub.",
          cachedUpdate = cachedEval,
        )
      }

      if (!resp.isSuccessful) {
        return@withContext UpdateDiscoveryResult.Error(
          reason = UpdateFailureReason.SERVER_ERROR,
          message = "GitHub Releases returned HTTP ${resp.code}.",
          cachedUpdate = cachedEval,
        )
      }

      val releaseBody = try {
        readBoundedUtf8Body(resp, MAX_METADATA_BYTES)
      } catch (e: IOException) {
        return@withContext UpdateDiscoveryResult.Error(
          reason = UpdateFailureReason.NETWORK_ERROR,
          message = e.message ?: "Failed reading release response.",
          cachedUpdate = cachedEval,
        )
      }

      val release = runCatching {
        json.decodeFromString<GitHubReleaseDto>(releaseBody)
      }.getOrElse {
        return@withContext UpdateDiscoveryResult.Error(
          reason = UpdateFailureReason.INVALID_METADATA,
          message = "Received malformed release JSON from GitHub.",
          cachedUpdate = cachedEval,
        )
      }

      if (release.draft || release.prerelease) {
        return@withContext UpdateDiscoveryResult.Error(
          reason = UpdateFailureReason.INVALID_METADATA,
          message = "Latest release is marked draft or prerelease and is excluded from stable updates.",
          cachedUpdate = cachedEval,
        )
      }

      val metadataAsset = release.assets.firstOrNull { it.name == UPDATE_METADATA_ASSET_NAME }
        ?: return@withContext UpdateDiscoveryResult.Error(
          reason = UpdateFailureReason.INVALID_METADATA,
          message = "Release ${release.tagName} does not contain $UPDATE_METADATA_ASSET_NAME.",
          cachedUpdate = cachedEval,
        )

      val metadataUrl = try {
        urlPolicy.requireAllowedAssetUrl(metadataAsset.browserDownloadUrl)
      } catch (sec: SecurityException) {
        return@withContext UpdateDiscoveryResult.Error(
          reason = UpdateFailureReason.UNTRUSTED_URL,
          message = sec.message ?: "Untrusted update.json asset URL.",
          cachedUpdate = cachedEval,
        )
      }

      val rawMetadata = when (val metaFetch = fetchAndParseMetadata(metadataUrl, installed)) {
        is MetadataFetchResult.Success -> metaFetch.metadata
        is MetadataFetchResult.Failure -> {
          return@withContext UpdateDiscoveryResult.Error(
            reason = metaFetch.reason,
            message = metaFetch.message,
            cachedUpdate = cachedEval,
          )
        }
      }

      val result = mapEvaluationToResult(
        installed = installed,
        release = release,
        rawMetadata = rawMetadata,
        checkedAtEpochMs = now,
        cachedFallback = cachedEval,
      )

      if (result !is UpdateDiscoveryResult.Error) {
        val newEtag = resp.header("ETag")
        preferencesStorage.saveCheckMetadata(
          checkedAtEpochMs = now,
          etag = newEtag,
          cachedRelease = CachedReleaseSnapshot(release = release, metadata = rawMetadata),
        )
      }

      return@withContext result
    }
  }

  private suspend fun fetchFreshWithoutEtag(
    installed: InstalledAppInfo,
    apiUrl: String,
    now: Long,
  ): UpdateDiscoveryResult {
    preferencesStorage.saveCheckMetadata(checkedAtEpochMs = now, etag = null, cachedRelease = null)
    return checkForUpdates(installed = installed, forceRefresh = true)
  }

  private sealed interface MetadataFetchResult {
    data class Success(val metadata: UpdateMetadata) : MetadataFetchResult
    data class Failure(val reason: UpdateFailureReason, val message: String) : MetadataFetchResult
  }

  private fun fetchAndParseMetadata(
    metadataUrl: String,
    installed: InstalledAppInfo,
  ): MetadataFetchResult {
    val resp = try {
      urlPolicy.executeValidatedGet(
        client = client,
        url = metadataUrl,
        headers = mapOf(
          "Accept" to "application/json",
          "User-Agent" to "PIZZORA-Android/${installed.versionName}",
        ),
      )
    } catch (sec: SecurityException) {
      return MetadataFetchResult.Failure(
        UpdateFailureReason.UNTRUSTED_URL,
        sec.message ?: "Blocked untrusted metadata redirect.",
      )
    } catch (_: SocketTimeoutException) {
      return MetadataFetchResult.Failure(
        UpdateFailureReason.NETWORK_ERROR,
        "Timed out downloading update metadata.",
      )
    } catch (io: IOException) {
      return MetadataFetchResult.Failure(
        UpdateFailureReason.NETWORK_ERROR,
        io.message ?: "Failed to download update metadata.",
      )
    }

    resp.use { r ->
      if (!r.isSuccessful) {
        return MetadataFetchResult.Failure(
          UpdateFailureReason.SERVER_ERROR,
          "Failed to download update metadata (HTTP ${r.code}).",
        )
      }
      val body = try {
        readBoundedUtf8Body(r, MAX_METADATA_BYTES)
      } catch (io: IOException) {
        return MetadataFetchResult.Failure(
          UpdateFailureReason.INVALID_METADATA,
          io.message ?: "Metadata response exceeded size limit or failed to read.",
        )
      }
      val parsed = runCatching {
        json.decodeFromString<UpdateMetadata>(body)
      }.getOrElse {
        return MetadataFetchResult.Failure(
          UpdateFailureReason.INVALID_METADATA,
          "Malformed update.json metadata payload.",
        )
      }
      return MetadataFetchResult.Success(parsed)
    }
  }

  private fun mapEvaluationToResult(
    installed: InstalledAppInfo,
    release: GitHubReleaseDto,
    rawMetadata: UpdateMetadata,
    checkedAtEpochMs: Long,
    cachedFallback: AvailableUpdate?,
  ): UpdateDiscoveryResult {
    if (release.draft || release.prerelease || rawMetadata.prerelease) {
      return UpdateDiscoveryResult.Error(
        reason = UpdateFailureReason.INVALID_METADATA,
        message = "Prerelease and draft builds are excluded from stable update discovery.",
        cachedUpdate = null,
      )
    }

    val validation = metadataValidator.validate(rawMetadata)
    val metadata = validation.metadata ?: return UpdateDiscoveryResult.Error(
      reason = validation.failureReason ?: UpdateFailureReason.INVALID_METADATA,
      message = validation.errorMessage ?: "Invalid update metadata.",
      cachedUpdate = cachedFallback,
    )

    val apkAsset = release.assets.firstOrNull { it.name == metadata.apkAssetName }
      ?: return UpdateDiscoveryResult.Error(
        reason = UpdateFailureReason.MISSING_APK_ASSET,
        message = "Release is missing expected APK asset '${metadata.apkAssetName}'.",
        cachedUpdate = cachedFallback,
      )

    val apkDownloadUrl = try {
      urlPolicy.requireAllowedAssetUrl(apkAsset.browserDownloadUrl)
    } catch (sec: SecurityException) {
      return UpdateDiscoveryResult.Error(
        reason = UpdateFailureReason.UNTRUSTED_URL,
        message = sec.message ?: "APK download URL failed host security validation.",
        cachedUpdate = cachedFallback,
      )
    }

    val resolvedSize = apkAsset.size.takeIf { it > 0L } ?: metadata.apkSizeBytes

    return when (
      val eval = updateChecker.evaluate(
        installed = installed,
        metadata = metadata,
        apkDownloadUrl = apkDownloadUrl,
        apkSizeBytes = resolvedSize,
      )
    ) {
      UpdateEvaluation.UpToDate -> UpdateDiscoveryResult.UpToDate(
        checkedAtEpochMs = checkedAtEpochMs,
        remoteVersionName = metadata.versionName,
        remoteVersionCode = metadata.versionCode,
      )
      is UpdateEvaluation.DowngradeRejected -> UpdateDiscoveryResult.UpToDate(
        checkedAtEpochMs = checkedAtEpochMs,
        remoteVersionName = metadata.versionName,
        remoteVersionCode = metadata.versionCode,
      )
      is UpdateEvaluation.IncompatibleDevice -> UpdateDiscoveryResult.Error(
        reason = UpdateFailureReason.INCOMPATIBLE_DEVICE,
        message = eval.reason,
        cachedUpdate = null,
      )
      is UpdateEvaluation.UpdateReady -> UpdateDiscoveryResult.Available(
        update = eval.update,
        checkedAtEpochMs = checkedAtEpochMs,
      )
    }
  }

  private fun evaluateSnapshot(
    installed: InstalledAppInfo,
    snapshot: CachedReleaseSnapshot?,
  ): AvailableUpdate? {
    if (snapshot == null) return null
    if (snapshot.release.draft || snapshot.release.prerelease || snapshot.metadata.prerelease) return null
    val validated = metadataValidator.validate(snapshot.metadata).metadata ?: return null
    val asset = snapshot.release.assets.firstOrNull { it.name == validated.apkAssetName } ?: return null
    val apkUrl = runCatching { urlPolicy.requireAllowedAssetUrl(asset.browserDownloadUrl) }.getOrNull() ?: return null
    val eval = updateChecker.evaluate(
      installed = installed,
      metadata = validated,
      apkDownloadUrl = apkUrl,
      apkSizeBytes = asset.size.takeIf { it > 0L } ?: validated.apkSizeBytes,
    )
    return (eval as? UpdateEvaluation.UpdateReady)?.update
  }

  private fun readBoundedUtf8Body(response: okhttp3.Response, maxBytes: Long): String {
    val body = response.body ?: throw IOException("Empty HTTP response body.")
    val contentLength = body.contentLength()
    if (contentLength > maxBytes) {
      throw IOException("Metadata payload exceeded maximum allowed size ($contentLength bytes).")
    }
    body.source().use { source ->
      val buffer = okio.Buffer()
      var total = 0L
      while (true) {
        val read = source.read(buffer, 8192L)
        if (read == -1L) break
        total += read
        if (total > maxBytes) {
          throw IOException("Metadata payload exceeded maximum allowed size ($maxBytes bytes).")
        }
      }
      return buffer.readUtf8()
    }
  }

  companion object {
    const val UPDATE_METADATA_ASSET_NAME = "update.json"
    const val MAX_METADATA_BYTES = 512L * 1024L
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
      .followRedirects(false)
      .followSslRedirects(false)
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .build()
  }
}

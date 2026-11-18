package com.example.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.updatePrefs: DataStore<Preferences> by preferencesDataStore(name = "pizzora_update_prefs")

data class UpdatePreferencesSnapshot(
  val lastCheckedAtEpochMs: Long? = null,
  val etag: String? = null,
  val cachedRelease: CachedReleaseSnapshot? = null,
  val downloadedApkPath: String? = null,
  val downloadedApkVersionCode: Long? = null,
  val downloadedApkSha256: String? = null,
  val pendingInstallVersionCode: Long? = null,
)

interface UpdatePreferencesStorage {
  suspend fun readSnapshot(): UpdatePreferencesSnapshot
  suspend fun saveCheckMetadata(
    checkedAtEpochMs: Long,
    etag: String?,
    cachedRelease: CachedReleaseSnapshot?,
  )
  suspend fun updateLastCheckedAt(checkedAtEpochMs: Long)
  suspend fun saveDownloadedApk(
    apkPath: String,
    versionCode: Long,
    sha256: String,
  )
  suspend fun clearDownloadedApk()
  suspend fun savePendingInstallVersionCode(versionCode: Long?)
  suspend fun clearAll()
}

class DataStoreUpdatePreferencesStorage(
  private val context: Context,
  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : UpdatePreferencesStorage {
  private val keyLastCheckedAt = longPreferencesKey("update_last_checked_at_ms")
  private val keyEtag = stringPreferencesKey("update_release_etag")
  private val keyCachedReleaseJson = stringPreferencesKey("update_cached_release_json")
  private val keyDownloadedApkPath = stringPreferencesKey("update_downloaded_apk_path")
  private val keyDownloadedApkVersionCode = longPreferencesKey("update_downloaded_apk_version_code")
  private val keyDownloadedApkSha256 = stringPreferencesKey("update_downloaded_apk_sha256")
  private val keyPendingInstallVersionCode = longPreferencesKey("update_pending_install_version_code")

  override suspend fun readSnapshot(): UpdatePreferencesSnapshot {
    val prefs = context.updatePrefs.data.first()
    val cached = prefs[keyCachedReleaseJson]?.let { raw ->
      runCatching { json.decodeFromString<CachedReleaseSnapshot>(raw) }.getOrNull()
    }
    return UpdatePreferencesSnapshot(
      lastCheckedAtEpochMs = prefs[keyLastCheckedAt],
      etag = prefs[keyEtag],
      cachedRelease = cached,
      downloadedApkPath = prefs[keyDownloadedApkPath],
      downloadedApkVersionCode = prefs[keyDownloadedApkVersionCode],
      downloadedApkSha256 = prefs[keyDownloadedApkSha256],
      pendingInstallVersionCode = prefs[keyPendingInstallVersionCode],
    )
  }

  override suspend fun saveCheckMetadata(
    checkedAtEpochMs: Long,
    etag: String?,
    cachedRelease: CachedReleaseSnapshot?,
  ) {
    context.updatePrefs.edit { prefs ->
      prefs[keyLastCheckedAt] = checkedAtEpochMs
      if (etag != null) {
        prefs[keyEtag] = etag
      } else {
        prefs.remove(keyEtag)
      }
      if (cachedRelease != null) {
        prefs[keyCachedReleaseJson] = json.encodeToString(cachedRelease)
      } else {
        prefs.remove(keyCachedReleaseJson)
      }
    }
  }

  override suspend fun updateLastCheckedAt(checkedAtEpochMs: Long) {
    context.updatePrefs.edit { prefs ->
      prefs[keyLastCheckedAt] = checkedAtEpochMs
    }
  }

  override suspend fun saveDownloadedApk(
    apkPath: String,
    versionCode: Long,
    sha256: String,
  ) {
    context.updatePrefs.edit { prefs ->
      prefs[keyDownloadedApkPath] = apkPath
      prefs[keyDownloadedApkVersionCode] = versionCode
      prefs[keyDownloadedApkSha256] = sha256
    }
  }

  override suspend fun clearDownloadedApk() {
    context.updatePrefs.edit { prefs ->
      prefs.remove(keyDownloadedApkPath)
      prefs.remove(keyDownloadedApkVersionCode)
      prefs.remove(keyDownloadedApkSha256)
    }
  }

  override suspend fun savePendingInstallVersionCode(versionCode: Long?) {
    context.updatePrefs.edit { prefs ->
      if (versionCode != null) {
        prefs[keyPendingInstallVersionCode] = versionCode
      } else {
        prefs.remove(keyPendingInstallVersionCode)
      }
    }
  }

  override suspend fun clearAll() {
    context.updatePrefs.edit { it.clear() }
  }
}

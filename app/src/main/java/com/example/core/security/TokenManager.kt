package com.example.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pizzora.contracts.UserProfileDto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val Context.pizzoraPrefs: DataStore<Preferences> by preferencesDataStore(name = "pizzora_secure_prefs")

data class AuthSession(
  val accessToken: String,
  val refreshToken: String,
  val user: UserProfileDto,
)

interface TokenCipher {
  fun encrypt(plainText: String): String
  fun decrypt(cipherText: String): String?
}

class AndroidKeystoreTokenCipher(
  private val keyAlias: String = "pizzora_session_aes_key",
) : TokenCipher {
  override fun encrypt(plainText: String): String {
    val key = getOrCreateKey() ?: return "plain:${Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}"
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val iv = cipher.iv
    val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
    val combined = ByteArray(iv.size + encrypted.size)
    System.arraycopy(iv, 0, combined, 0, iv.size)
    System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
    return "aesgcm:${Base64.encodeToString(combined, Base64.NO_WRAP)}"
  }

  override fun decrypt(cipherText: String): String? = runCatching {
    if (cipherText.startsWith("plain:")) {
      return@runCatching String(Base64.decode(cipherText.removePrefix("plain:"), Base64.NO_WRAP), Charsets.UTF_8)
    }
    if (!cipherText.startsWith("aesgcm:")) return@runCatching null
    val key = getOrCreateKey() ?: return@runCatching null
    val raw = Base64.decode(cipherText.removePrefix("aesgcm:"), Base64.NO_WRAP)
    if (raw.size < 13) return@runCatching null
    val iv = raw.copyOfRange(0, 12)
    val encrypted = raw.copyOfRange(12, raw.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
    String(cipher.doFinal(encrypted), Charsets.UTF_8)
  }.getOrNull()

  private fun getOrCreateKey(): SecretKey? = runCatching {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(keyAlias, null) as? SecretKey) ?: run {
      val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
      val spec = KeyGenParameterSpec.Builder(
        keyAlias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
      keyGenerator.init(spec)
      keyGenerator.generateKey()
    }
  }.getOrNull()
}

interface TokenStorage {
  val sessionFlow: StateFlow<AuthSession?>
  fun currentSession(): AuthSession?
  suspend fun saveSession(session: AuthSession)
  suspend fun clearSession()
  fun observeKdsSoundMuted(): Flow<Boolean>
  suspend fun setKdsSoundMuted(muted: Boolean)
}

class TokenManager(
  private val context: Context,
  private val cipher: TokenCipher = AndroidKeystoreTokenCipher(),
  private val json: Json = Json { ignoreUnknownKeys = true },
) : TokenStorage {
  private val accessKey = stringPreferencesKey("enc_access_token")
  private val refreshKey = stringPreferencesKey("enc_refresh_token")
  private val userKey = stringPreferencesKey("user_profile_json")
  private val kdsMutedKey = booleanPreferencesKey("kds_sound_muted")

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _session = MutableStateFlow<AuthSession?>(null)
  override val sessionFlow: StateFlow<AuthSession?> = _session.asStateFlow()

  init {
    scope.launch {
      _session.value = readPersistedSession()
    }
  }

  override fun currentSession(): AuthSession? = _session.value

  override suspend fun saveSession(session: AuthSession) {
    _session.value = session
    context.pizzoraPrefs.edit { prefs ->
      prefs[accessKey] = cipher.encrypt(session.accessToken)
      prefs[refreshKey] = cipher.encrypt(session.refreshToken)
      prefs[userKey] = json.encodeToString(session.user)
    }
  }

  override suspend fun clearSession() {
    _session.value = null
    context.pizzoraPrefs.edit { prefs ->
      prefs.remove(accessKey)
      prefs.remove(refreshKey)
      prefs.remove(userKey)
    }
  }

  override fun observeKdsSoundMuted(): Flow<Boolean> =
    context.pizzoraPrefs.data.map { prefs -> prefs[kdsMutedKey] ?: false }

  override suspend fun setKdsSoundMuted(muted: Boolean) {
    context.pizzoraPrefs.edit { prefs -> prefs[kdsMutedKey] = muted }
  }

  private suspend fun readPersistedSession(): AuthSession? {
    val prefs = context.pizzoraPrefs.data.first()
    val encAccess = prefs[accessKey] ?: return null
    val encRefresh = prefs[refreshKey] ?: return null
    val userJson = prefs[userKey] ?: return null
    val access = cipher.decrypt(encAccess) ?: return null
    val refresh = cipher.decrypt(encRefresh) ?: return null
    val user = runCatching { json.decodeFromString<UserProfileDto>(userJson) }.getOrNull() ?: return null
    return AuthSession(accessToken = access, refreshToken = refresh, user = user)
  }
}

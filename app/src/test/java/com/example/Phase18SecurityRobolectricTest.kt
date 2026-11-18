package com.example

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.AndroidKeystoreTokenCipher
import com.example.core.security.AuthSession
import com.example.core.security.TokenManager
import com.example.core.security.TransportSecurityPolicy
import com.example.core.network.PizzoraApiClient
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase18SecurityRobolectricTest {

  private fun generateAes256Key(): SecretKey =
    KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

  @Test
  fun `transport security policy blocks cleartext in release and external http in debug`() {
    // Release mode: strictly requires HTTPS / WSS
    assertEquals(
      "https://api.pizzora.app",
      TransportSecurityPolicy.requireValidEndpointUrl("https://api.pizzora.app/", isDebugBuild = false),
    )
    assertEquals(
      "wss://api.pizzora.app/ws",
      TransportSecurityPolicy.requireValidEndpointUrl("wss://api.pizzora.app/ws", isDebugBuild = false),
    )

    val releaseBlocked = listOf(
      "http://10.0.2.2:8080",
      "http://localhost:8080",
      "http://127.0.0.1:8080",
      "http://api.pizzora.app",
      "ws://10.0.2.2:8080",
      "ftp://files.pizzora.app",
      "file:///etc/passwd",
    )
    for (url in releaseBlocked) {
      val failed = runCatching {
        TransportSecurityPolicy.requireValidEndpointUrl(url, isDebugBuild = false)
      }.exceptionOrNull()
      assertTrue("Expected release build to reject $url, got $failed", failed is SecurityException)
    }
    val emptyHostFailed = runCatching {
      TransportSecurityPolicy.requireValidEndpointUrl("https://", isDebugBuild = false)
    }.exceptionOrNull()
    assertTrue(
      "Expected release build to reject malformed empty-host URL, got $emptyHostFailed",
      emptyHostFailed is SecurityException || emptyHostFailed is IllegalArgumentException,
    )

    // Debug mode: allows local emulator/loopback HTTP, but blocks cleartext external domains
    assertEquals(
      "http://10.0.2.2:8080",
      TransportSecurityPolicy.requireValidEndpointUrl("http://10.0.2.2:8080", isDebugBuild = true),
    )
    assertEquals(
      "http://localhost:8080",
      TransportSecurityPolicy.requireValidEndpointUrl("http://localhost:8080", isDebugBuild = true),
    )
    val debugExternalHttp = runCatching {
      TransportSecurityPolicy.requireValidEndpointUrl("http://evil.example.com:8080", isDebugBuild = true)
    }.exceptionOrNull()
    assertTrue(debugExternalHttp is SecurityException)
  }

  @Test
  fun `api client enforces transport security on presigned upload urls and url-encodes ws ticket`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val storage = TokenManager(context)
    val mockEngine = MockEngine { respondOk("{}") }
    val client = PizzoraApiClient(
      baseUrl = "https://api.pizzora.app",
      tokenStorage = storage,
      engine = mockEngine,
    )

    // External cleartext upload URL must fail BEFORE issuing network call
    val cleartextUploadError = runCatching {
      client.uploadBytesToPresignedUrl(
        uploadUrl = "http://storage.evil.example/upload.jpg",
        bytes = byteArrayOf(1, 2, 3),
        mimeType = "image/jpeg",
      )
    }.exceptionOrNull()
    assertTrue(cleartextUploadError is SecurityException)
    assertEquals(0, mockEngine.requestHistory.size)

    // WebSocket URL converts https -> wss and URL-encodes ticket
    val wsUrl = client.wsEventsUrl(ticket = "abc+def/ghi=123", sinceSeq = 42L)
    assertTrue(wsUrl.startsWith("wss://api.pizzora.app/ws/v1/events?"))
    assertTrue(wsUrl.contains("ticket=abc%2Bdef%2Fghi%3D123"))
    assertTrue(wsUrl.contains("since_seq=42"))
  }

  @Test
  fun `keystore cipher uses AES-GCM, rejects tampered ciphertext and blocks plaintext fallback in production`() = runTest {
    val aesKey = generateAes256Key()
    val strictCipher = AndroidKeystoreTokenCipher(
      allowPlainFallback = false,
      secretKeyProvider = { aesKey },
    )

    val secretJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSJ9.sig_123"
    val ct1 = strictCipher.encrypt(secretJwt)
    val ct2 = strictCipher.encrypt(secretJwt)

    assertTrue(ct1.startsWith("aesgcm:"))
    assertNotEquals("Fresh 12-byte random IV must produce distinct ciphertexts", ct1, ct2)
    assertFalse(ct1.contains(secretJwt))
    assertEquals(secretJwt, strictCipher.decrypt(ct1))
    assertEquals(secretJwt, strictCipher.decrypt(ct2))

    // Tampered ciphertext must fail GCM authentication tag verification
    val payloadChars = ct1.toCharArray()
    val mid = ct1.length - 4
    payloadChars[mid] = if (payloadChars[mid] == 'A') 'B' else 'A'
    assertNull(strictCipher.decrypt(String(payloadChars)))

    // Plaintext prefix must be rejected when allowPlainFallback == false
    assertNull(strictCipher.decrypt("plain:c2VjcmV0"))

    // Missing keystore key with allowPlainFallback == false must fail closed (never write plaintext)
    val unavailableKeyCipher = AndroidKeystoreTokenCipher(
      allowPlainFallback = false,
      secretKeyProvider = { null },
    )
    val encryptErr = runCatching { unavailableKeyCipher.encrypt(secretJwt) }.exceptionOrNull()
    assertTrue(encryptErr is IllegalStateException)

    // Verify TokenManager persists encrypted tokens in DataStore and recovers AuthSession
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tokenManager = TokenManager(context = context, cipher = strictCipher)
    val session = AuthSession(
      accessToken = "jwt_access_phase18",
      refreshToken = "refresh_phase18_secret",
      user = UserProfileDto(
        id = "u-p18",
        email = "sec@pizzora.test",
        emailVerified = true,
        fullName = "Priya Nair",
        phone = "9812345678",
        role = UserRole.CUSTOMER,
        isActive = true,
      ),
    )
    tokenManager.saveSession(session)

    val rawPrefsFile = File(context.filesDir, "datastore/pizzora_secure_prefs.preferences_pb")
    assertTrue(rawPrefsFile.exists())
    val rawBytesText = rawPrefsFile.readBytes().decodeToString()
    assertTrue(rawBytesText.contains("aesgcm:"))
    assertFalse(rawBytesText.contains("jwt_access_phase18"))
    assertFalse(rawBytesText.contains("refresh_phase18_secret"))

    val restoredManager = TokenManager(context = context, cipher = strictCipher)
    assertEquals(session, restoredManager.awaitSession())
  }

  @Test
  fun `BuildConfig contains zero backend secrets and AndroidManifest avoids prohibited Play permissions`() {
    val buildConfigFields = BuildConfig::class.java.declaredFields.map { it.name }.toSet()
    assertTrue(buildConfigFields.contains("API_BASE_URL"))
    val forbiddenFields = setOf(
      "JWT_SECRET",
      "RAZORPAY_KEY_SECRET",
      "RAZORPAY_WEBHOOK_SECRET",
      "DATABASE_PASSWORD",
      "S3_SECRET_KEY",
      "GEMINI_API_KEY",
    )
    for (forbidden in forbiddenFields) {
      assertFalse("Forbidden secret field $forbidden exposed in BuildConfig", buildConfigFields.contains(forbidden))
    }

    val context = ApplicationProvider.getApplicationContext<Context>()
    val pkgInfo = context.packageManager.getPackageInfo(
      context.packageName,
      PackageManager.GET_PERMISSIONS,
    )
    val permissions = pkgInfo.requestedPermissions?.toSet().orEmpty()
    assertTrue(permissions.contains("android.permission.INTERNET"))
    assertTrue(permissions.contains("android.permission.ACCESS_NETWORK_STATE"))
    // Direct GitHub Releases distribution requires REQUEST_INSTALL_PACKAGES for PackageInstaller self-updates.
    assertTrue(permissions.contains("android.permission.REQUEST_INSTALL_PACKAGES"))

    val prohibitedPermissions = setOf(
      "android.permission.READ_EXTERNAL_STORAGE",
      "android.permission.READ_MEDIA_IMAGES",
      "android.permission.READ_MEDIA_VIDEO",
      "android.permission.WRITE_EXTERNAL_STORAGE",
      "android.permission.MANAGE_EXTERNAL_STORAGE",
      "android.permission.QUERY_ALL_PACKAGES",
    )
    for (prohibited in prohibitedPermissions) {
      assertFalse("Prohibited permission $prohibited in manifest", permissions.contains(prohibited))
    }

    // Verify cleartext flag is not globally enabled on ApplicationInfo in release/manifest configuration
    val appInfo = pkgInfo.applicationInfo
    assertNotNull(appInfo)
    assertEquals(0, appInfo!!.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)

    // Verify backup exclusion XML resources exist cleanly
    context.resources.getXml(R.xml.network_security_config).use { parser ->
      assertNotNull(parser)
    }
    context.resources.getXml(R.xml.backup_rules).use { parser ->
      assertNotNull(parser)
    }
    context.resources.getXml(R.xml.data_extraction_rules).use { parser ->
      assertNotNull(parser)
    }
  }
}

package com.example.update

import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase20UpdateUnitTest {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private class InMemoryUpdatePreferencesStorage : UpdatePreferencesStorage {
    private var snapshot = UpdatePreferencesSnapshot()

    override suspend fun readSnapshot(): UpdatePreferencesSnapshot = snapshot

    override suspend fun saveCheckMetadata(
      checkedAtEpochMs: Long,
      etag: String?,
      cachedRelease: CachedReleaseSnapshot?,
    ) {
      snapshot = snapshot.copy(
        lastCheckedAtEpochMs = checkedAtEpochMs,
        etag = etag,
        cachedRelease = cachedRelease,
      )
    }

    override suspend fun updateLastCheckedAt(checkedAtEpochMs: Long) {
      snapshot = snapshot.copy(lastCheckedAtEpochMs = checkedAtEpochMs)
    }

    override suspend fun saveDownloadedApk(apkPath: String, versionCode: Long, sha256: String) {
      snapshot = snapshot.copy(
        downloadedApkPath = apkPath,
        downloadedApkVersionCode = versionCode,
        downloadedApkSha256 = sha256,
      )
    }

    override suspend fun clearDownloadedApk() {
      snapshot = snapshot.copy(
        downloadedApkPath = null,
        downloadedApkVersionCode = null,
        downloadedApkSha256 = null,
      )
    }

    override suspend fun savePendingInstallVersionCode(versionCode: Long?) {
      snapshot = snapshot.copy(pendingInstallVersionCode = versionCode)
    }

    override suspend fun clearAll() {
      snapshot = UpdatePreferencesSnapshot()
    }
  }

  private fun installedApp(
    versionCode: Long = 1000L,
    versionName: String = "0.1.0",
    sdkInt: Int = 34,
    abis: List<String> = listOf("arm64-v8a"),
    certs: Set<String> = setOf("a".repeat(64)),
  ) = InstalledAppInfo(
    applicationId = "com.aistudio.pizzora.xkqzmw",
    versionName = versionName,
    versionCode = versionCode,
    sdkInt = sdkInt,
    supportedAbis = abis,
    signingCertSha256s = certs,
  )

  private fun sampleMetadata(
    schemaVersion: Int = 1,
    applicationId: String = "com.aistudio.pizzora.xkqzmw",
    versionName: String = "0.7.0",
    versionCode: Long = 7000L,
    minimumSupportedVersionCode: Long = 1000L,
    mandatory: Boolean = false,
    releaseNotes: String = "Oven telemetry & faster checkout",
    apkAssetName: String = "pizzora-v0.7.0.apk",
    apkSha256: String = "b".repeat(64),
    apkSizeBytes: Long? = 1024L,
    minSdk: Int = 24,
    supportedAbis: List<String> = listOf("arm64-v8a", "x86_64"),
    releaseUrl: String = "https://github.com/imanpr33trai/pizzabi/releases/tag/v0.7.0",
    prerelease: Boolean = false,
  ) = UpdateMetadata(
    schemaVersion = schemaVersion,
    applicationId = applicationId,
    versionName = versionName,
    versionCode = versionCode,
    minimumSupportedVersionCode = minimumSupportedVersionCode,
    mandatory = mandatory,
    releaseNotes = releaseNotes,
    apkAssetName = apkAssetName,
    apkSha256 = apkSha256,
    apkSizeBytes = apkSizeBytes,
    minSdk = minSdk,
    supportedAbis = supportedAbis,
    releaseUrl = releaseUrl,
    publishedAt = "2026-06-01T12:00:00Z",
    prerelease = prerelease,
  )

  private fun sampleRelease(
    metadata: UpdateMetadata = sampleMetadata(),
    draft: Boolean = false,
    prerelease: Boolean = false,
    includeMetadataAsset: Boolean = true,
    includeApkAsset: Boolean = true,
    apkUrl: String = "https://github.com/imanpr33trai/pizzabi/releases/download/v${metadata.versionName}/${metadata.apkAssetName}",
  ): GitHubReleaseDto {
    val assets = mutableListOf<GitHubReleaseAssetDto>()
    if (includeMetadataAsset) {
      assets += GitHubReleaseAssetDto(
        id = 1L,
        name = "update.json",
        size = 512L,
        contentType = "application/json",
        browserDownloadUrl = "https://github.com/imanpr33trai/pizzabi/releases/download/v${metadata.versionName}/update.json",
      )
    }
    if (includeApkAsset) {
      assets += GitHubReleaseAssetDto(
        id = 2L,
        name = metadata.apkAssetName,
        size = metadata.apkSizeBytes ?: 1024L,
        contentType = "application/vnd.android.package-archive",
        browserDownloadUrl = apkUrl,
      )
    }
    return GitHubReleaseDto(
      id = 42L,
      tagName = "v${metadata.versionName}",
      name = "PIZZORA v${metadata.versionName}",
      body = metadata.releaseNotes,
      draft = draft,
      prerelease = prerelease,
      htmlUrl = metadata.releaseUrl,
      publishedAt = metadata.publishedAt,
      assets = assets,
    )
  }

  private fun okHttpWithHandler(handler: (Request) -> Response): OkHttpClient =
    OkHttpClient.Builder()
      .followRedirects(false)
      .followSslRedirects(false)
      .addInterceptor(Interceptor { chain -> handler(chain.request()) })
      .build()

  private fun jsonResponse(request: Request, code: Int, body: String, headers: Map<String, String> = emptyMap()): Response {
    val builder = Response.Builder()
      .request(request)
      .protocol(Protocol.HTTP_1_1)
      .code(code)
      .message("HTTP $code")
      .body(body.toResponseBody("application/json".toMediaType()))
    headers.forEach { (k, v) -> builder.header(k, v) }
    return builder.build()
  }

  private fun bytesResponse(request: Request, code: Int, bytes: ByteArray, headers: Map<String, String> = emptyMap()): Response {
    val builder = Response.Builder()
      .request(request)
      .protocol(Protocol.HTTP_1_1)
      .code(code)
      .message("HTTP $code")
      .body(bytes.toResponseBody("application/vnd.android.package-archive".toMediaType()))
    headers.forEach { (k, v) -> builder.header(k, v) }
    return builder.build()
  }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  @Test
  fun `checker handles newer, equal, older versions, mandatory policy, and minSupportedVersionCode`() {
    val checker = UpdateChecker()
    val installed = installedApp(versionCode = 2000L)

    // Newer optional
    val optionalMeta = sampleMetadata(versionCode = 3000L, minimumSupportedVersionCode = 1000L, mandatory = false)
    val optionalEval = checker.evaluate(installed, optionalMeta, "https://github.com/a.apk", 100L)
    assertTrue(optionalEval is UpdateEvaluation.UpdateReady)
    assertFalse((optionalEval as UpdateEvaluation.UpdateReady).update.isMandatory)

    // Same version -> UpToDate
    val equalMeta = sampleMetadata(versionCode = 2000L)
    assertEquals(UpdateEvaluation.UpToDate, checker.evaluate(installed, equalMeta, "https://github.com/a.apk", 100L))

    // Older version -> DowngradeRejected
    val olderMeta = sampleMetadata(versionCode = 1000L)
    assertTrue(checker.evaluate(installed, olderMeta, "https://github.com/a.apk", 100L) is UpdateEvaluation.DowngradeRejected)

    // Explicit mandatory
    val mandatoryMeta = sampleMetadata(versionCode = 3000L, minimumSupportedVersionCode = 1000L, mandatory = true)
    val mandatoryEval = checker.evaluate(installed, mandatoryMeta, "https://github.com/a.apk", 100L) as UpdateEvaluation.UpdateReady
    assertTrue(mandatoryEval.update.isMandatory)
    assertEquals(MandatoryUpdateReason.EXPLICIT_MANDATORY, mandatoryEval.update.mandatoryReason)

    // Below minimumSupportedVersionCode forces mandatory even when mandatory=false
    val minForcedMeta = sampleMetadata(versionCode = 4000L, minimumSupportedVersionCode = 2500L, mandatory = false)
    val minEval = checker.evaluate(installed, minForcedMeta, "https://github.com/a.apk", 100L) as UpdateEvaluation.UpdateReady
    assertTrue(minEval.update.isMandatory)
    assertEquals(MandatoryUpdateReason.BELOW_MINIMUM_SUPPORTED_VERSION, minEval.update.mandatoryReason)

    // Incompatible SDK or ABI
    val highSdkMeta = sampleMetadata(versionCode = 3000L, minSdk = 36)
    assertTrue(checker.evaluate(installed, highSdkMeta, "https://github.com/a.apk", 100L) is UpdateEvaluation.IncompatibleDevice)

    val otherAbiMeta = sampleMetadata(versionCode = 3000L, supportedAbis = listOf("armeabi-v7a"))
    assertTrue(checker.evaluate(installed, otherAbiMeta, "https://github.com/a.apk", 100L) is UpdateEvaluation.IncompatibleDevice)
  }

  @Test
  fun `metadata validator rejects invalid schema, wrong appId, path traversal, bad sha256, untrusted URL and sanitizes notes`() {
    val validator = UpdateMetadataValidator()

    assertEquals(
      UpdateFailureReason.INVALID_METADATA,
      validator.validate(sampleMetadata(schemaVersion = 99)).failureReason,
    )
    assertEquals(
      UpdateFailureReason.PACKAGE_MISMATCH,
      validator.validate(sampleMetadata(applicationId = "com.evil.clone")).failureReason,
    )
    assertEquals(
      UpdateFailureReason.INVALID_METADATA,
      validator.validate(sampleMetadata(versionCode = 0L)).failureReason,
    )
    assertEquals(
      UpdateFailureReason.INVALID_METADATA,
      validator.validate(sampleMetadata(versionCode = 5000L, minimumSupportedVersionCode = 6000L)).failureReason,
    )
    assertEquals(
      UpdateFailureReason.INVALID_METADATA,
      validator.validate(sampleMetadata(apkAssetName = "../evil.apk")).failureReason,
    )
    assertEquals(
      UpdateFailureReason.INVALID_METADATA,
      validator.validate(sampleMetadata(apkSha256 = "not-a-sha256")).failureReason,
    )
    assertEquals(
      UpdateFailureReason.UNTRUSTED_URL,
      validator.validate(sampleMetadata(releaseUrl = "https://attacker.example/release")).failureReason,
    )
    assertEquals(
      UpdateFailureReason.UNTRUSTED_URL,
      validator.validate(sampleMetadata(releaseUrl = "http://github.com/imanpr33trai/pizzabi/releases")).failureReason,
    )

    val cleaned = validator.validate(
      sampleMetadata(releaseNotes = "  Line 1\u0000\u0007\r\nLine 2  "),
    ).metadata
    assertNotNull(cleaned)
    assertEquals("Line 1\nLine 2", cleaned!!.releaseNotes)

    val fallbackNotes = validator.validate(sampleMetadata(releaseNotes = " \u0001\u0002 ")).metadata
    assertEquals("Bug fixes and stability improvements.", fallbackNotes!!.releaseNotes)
  }

  @Test
  fun `repository discovers update, respects ETag 304, rejects drafts and prereleases, and blocks missing assets`() = runTest {
    val storage = InMemoryUpdatePreferencesStorage()
    val meta = sampleMetadata()
    val release = sampleRelease(meta)
    var nowMs = 1_000_000L
    val seenIfNoneMatch = mutableListOf<String?>()

    val client = okHttpWithHandler { req ->
      when {
        req.url.encodedPath.endsWith("/releases/latest") -> {
          val inm = req.header("If-None-Match")
          seenIfNoneMatch += inm
          if (inm == "\"etag-v0.7.0\"") {
            Response.Builder()
              .request(req)
              .protocol(Protocol.HTTP_1_1)
              .code(304)
              .message("Not Modified")
              .body(ByteArray(0).toResponseBody(null))
              .build()
          } else {
            jsonResponse(
              request = req,
              code = 200,
              body = json.encodeToString(release),
              headers = mapOf("ETag" to "\"etag-v0.7.0\""),
            )
          }
        }
        req.url.encodedPath.endsWith("/update.json") -> {
          jsonResponse(req, 200, json.encodeToString(meta))
        }
        else -> jsonResponse(req, 404, "{}")
      }
    }

    val repo = GitHubUpdateRepository(
      preferencesStorage = storage,
      client = client,
      clockMs = { nowMs },
    )

    val first = repo.checkForUpdates(installedApp(), forceRefresh = false)
    assertTrue(first is UpdateDiscoveryResult.Available)
    assertEquals("0.7.0", (first as UpdateDiscoveryResult.Available).update.metadata.versionName)
    assertEquals(null, seenIfNoneMatch[0])

    // Conditional request using cached ETag -> 304 Not Modified
    nowMs += 60_000L
    val second = repo.checkForUpdates(installedApp(), forceRefresh = false)
    assertTrue(second is UpdateDiscoveryResult.Available)
    assertEquals("\"etag-v0.7.0\"", seenIfNoneMatch[1])
    assertEquals(nowMs, storage.readSnapshot().lastCheckedAtEpochMs)

    // 24h interval check false immediately after check for optional update, true after 24h
    assertFalse(repo.shouldPerformAutomaticCheck(installedApp(), nowMs + 1000L))
    assertTrue(repo.shouldPerformAutomaticCheck(installedApp(), nowMs + GitHubUpdateRepository.AUTO_CHECK_INTERVAL_MS))

    // Prerelease and draft rejected
    val prereleaseClient = okHttpWithHandler { req ->
      jsonResponse(req, 200, json.encodeToString(sampleRelease(prerelease = true)))
    }
    val prereleaseRepo = GitHubUpdateRepository(InMemoryUpdatePreferencesStorage(), client = prereleaseClient)
    val preErr = prereleaseRepo.checkForUpdates(installedApp(), forceRefresh = true) as UpdateDiscoveryResult.Error
    assertEquals(UpdateFailureReason.INVALID_METADATA, preErr.reason)

    val draftClient = okHttpWithHandler { req ->
      jsonResponse(req, 200, json.encodeToString(sampleRelease(draft = true)))
    }
    val draftRepo = GitHubUpdateRepository(InMemoryUpdatePreferencesStorage(), client = draftClient)
    val draftErr = draftRepo.checkForUpdates(installedApp(), forceRefresh = true) as UpdateDiscoveryResult.Error
    assertEquals(UpdateFailureReason.INVALID_METADATA, draftErr.reason)

    // Missing APK asset rejected
    val missingApkClient = okHttpWithHandler { req ->
      if (req.url.encodedPath.endsWith("/update.json")) {
        jsonResponse(req, 200, json.encodeToString(meta))
      } else {
        jsonResponse(req, 200, json.encodeToString(sampleRelease(meta, includeApkAsset = false)))
      }
    }
    val missingApkRepo = GitHubUpdateRepository(InMemoryUpdatePreferencesStorage(), client = missingApkClient)
    val missingErr = missingApkRepo.checkForUpdates(installedApp(), forceRefresh = true) as UpdateDiscoveryResult.Error
    assertEquals(UpdateFailureReason.MISSING_APK_ASSET, missingErr.reason)
  }

  @Test
  fun `repository handles rate limits, server errors, timeouts, malformed JSON, and blocks untrusted redirects`() = runTest {
    val rateLimitClient = okHttpWithHandler { req ->
      jsonResponse(req, 403, """{"message":"API rate limit exceeded"}""", mapOf("X-RateLimit-Remaining" to "0"))
    }
    val rateErr = GitHubUpdateRepository(InMemoryUpdatePreferencesStorage(), client = rateLimitClient)
      .checkForUpdates(installedApp(), forceRefresh = true) as UpdateDiscoveryResult.Error
    assertEquals(UpdateFailureReason.RATE_LIMITED, rateErr.reason)

    val serverErrClient = okHttpWithHandler { req -> jsonResponse(req, 502, "bad gateway") }
    val serverErr = GitHubUpdateRepository(InMemoryUpdatePreferencesStorage(), client = serverErrClient)
      .checkForUpdates(installedApp(), forceRefresh = true) as UpdateDiscoveryResult.Error
    assertEquals(UpdateFailureReason.SERVER_ERROR, serverErr.reason)

    val timeoutClient = okHttpWithHandler { _ -> throw SocketTimeoutException("read timed out") }
    val timeoutErr = GitHubUpdateRepository(InMemoryUpdatePreferencesStorage(), client = timeoutClient)
      .checkForUpdates(installedApp(), forceRefresh = true) as UpdateDiscoveryResult.Error
    assertEquals(UpdateFailureReason.NETWORK_ERROR, timeoutErr.reason)

    val malformedClient = okHttpWithHandler { req -> jsonResponse(req, 200, "{not-valid-json") }
    val malformedErr = GitHubUpdateRepository(InMemoryUpdatePreferencesStorage(), client = malformedClient)
      .checkForUpdates(installedApp(), forceRefresh = true) as UpdateDiscoveryResult.Error
    assertEquals(UpdateFailureReason.INVALID_METADATA, malformedErr.reason)

    // Redirect to untrusted host must be blocked BEFORE calling the evil host
    var evilContacted = false
    val redirectClient = okHttpWithHandler { req ->
      if (req.url.host == "evil.attacker.example") {
        evilContacted = true
      }
      Response.Builder()
        .request(req)
        .protocol(Protocol.HTTP_1_1)
        .code(302)
        .message("Found")
        .header("Location", "https://evil.attacker.example/update.json")
        .body(ByteArray(0).toResponseBody(null))
        .build()
    }
    val redirectErr = GitHubUpdateRepository(InMemoryUpdatePreferencesStorage(), client = redirectClient)
      .checkForUpdates(installedApp(), forceRefresh = true) as UpdateDiscoveryResult.Error
    assertEquals(UpdateFailureReason.UNTRUSTED_URL, redirectErr.reason)
    assertFalse(evilContacted)
  }

  @Test
  fun `downloader reports progress, retries transient IO failure, supports cancellation, and blocks untrusted redirect and low storage`() = runBlocking {
    val tempDir = Files.createTempDirectory("pizzora-update-dl-test").toFile()
    try {
      val apkBytes = ByteArray(128 * 1024) { (it % 251).toByte() }
      val sha256 = sha256Hex(apkBytes)
      val update = AvailableUpdate(
        metadata = sampleMetadata(apkSha256 = sha256, apkSizeBytes = apkBytes.size.toLong()),
        apkDownloadUrl = "https://github.com/imanpr33trai/pizzabi/releases/download/v0.7.0/pizzora-v0.7.0.apk",
        apkSizeBytes = apkBytes.size.toLong(),
        releasePageUrl = "https://github.com/imanpr33trai/pizzabi/releases/tag/v0.7.0",
        isMandatory = false,
        mandatoryReason = null,
      )

      // 1. Fail attempt 1 with IOException, redirect via objects.githubusercontent.com on attempt 2, report progress
      val attempts = AtomicInteger(0)
      val retryClient = okHttpWithHandler { req ->
        when {
          req.url.host == "github.com" -> {
            val idx = attempts.incrementAndGet()
            if (idx == 1) {
              throw IOException("transient reset")
            }
            Response.Builder()
              .request(req)
              .protocol(Protocol.HTTP_1_1)
              .code(302)
              .message("Found")
              .header("Location", "https://objects.githubusercontent.com/pizzora-v0.7.0.apk")
              .body(ByteArray(0).toResponseBody(null))
              .build()
          }
          req.url.host == "objects.githubusercontent.com" -> bytesResponse(req, 200, apkBytes)
          else -> bytesResponse(req, 404, ByteArray(0))
        }
      }

      val manager = OkHttpApkDownloadManager(
        updatesDir = tempDir,
        client = retryClient,
        maxRetries = 2,
        retryDelayMs = 10L,
        usableSpaceProvider = { 512L * 1024L * 1024L },
      )
      val progressPercentages = mutableListOf<Int>()
      val res = manager.downloadApk(update) { p -> progressPercentages += p.progressPercent }
      assertTrue(res is ApkDownloadResult.Success)
      assertEquals(100, progressPercentages.last())
      assertTrue(progressPercentages.first() == 0)
      assertEquals(sha256, OkHttpApkDownloadManager.computeFileSha256((res as ApkDownloadResult.Success).apkFile))

      // 2. Low storage fails fast before network
      val lowSpaceManager = OkHttpApkDownloadManager(
        updatesDir = File(tempDir, "lowspace"),
        client = retryClient,
        usableSpaceProvider = { 1024L },
      )
      val lowSpaceRes = lowSpaceManager.downloadApk(update) {}
      assertTrue(lowSpaceRes is ApkDownloadResult.Failed)
      assertEquals(UpdateFailureReason.INSUFFICIENT_STORAGE, (lowSpaceRes as ApkDownloadResult.Failed).reason)

      // 3. Cancellation mid-stream returns Cancelled and cleans .part file
      val cancelDir = File(tempDir, "cancel")
      lateinit var cancelManager: OkHttpApkDownloadManager
      val slowClient = okHttpWithHandler { req ->
        val slowBody = object : ResponseBody() {
          override fun contentType() = "application/vnd.android.package-archive".toMediaType()
          override fun contentLength() = 64L * 1024L
          override fun source() = Buffer().apply {
            write(ByteArray(64 * 1024) { 1 })
          }.also {
            cancelManager.cancelActiveDownload()
          }
        }
        Response.Builder()
          .request(req)
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body(slowBody)
          .build()
      }
      cancelManager = OkHttpApkDownloadManager(
        updatesDir = cancelDir,
        client = slowClient,
        usableSpaceProvider = { 512L * 1024L * 1024L },
      )
      val cancelRes = cancelManager.downloadApk(update) {}
      assertEquals(ApkDownloadResult.Cancelled, cancelRes)
      assertFalse(File(cancelDir, "pizzora-v0.7.0.apk.part").exists())
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `integrity verifier rejects wrong checksum, invalid archive, wrong package, downgrade, and signer mismatch`() = runTest {
    val tempDir = Files.createTempDirectory("pizzora-verify-test").toFile()
    try {
      val payload = "signed-apk-content".toByteArray()
      val validSha = sha256Hex(payload)
      val installed = installedApp(versionCode = 5000L, certs = setOf("cert_sha_prod"))
      val meta = sampleMetadata(versionCode = 7000L, apkSha256 = validSha)

      fun createApk(): File = File(tempDir, "candidate.apk").apply { writeBytes(payload) }

      // Bad checksum -> deletes file
      val badSumFile = createApk()
      val badSumVerifier = ApkIntegrityVerifier(
        ApkPackageInspector {
          ParsedApkInfo("com.aistudio.pizzora.xkqzmw", 7000L, "0.7.0", setOf("cert_sha_prod"))
        },
      )
      val badSumRes = badSumVerifier.verify(badSumFile, meta.copy(apkSha256 = "f".repeat(64)), installed) as ApkVerificationResult.Rejected
      assertEquals(UpdateFailureReason.CHECKSUM_MISMATCH, badSumRes.reason)
      assertFalse(badSumFile.exists())

      // Unparseable APK
      val corruptFile = createApk()
      val corruptVerifier = ApkIntegrityVerifier(ApkPackageInspector { null })
      val corruptRes = corruptVerifier.verify(corruptFile, meta, installed) as ApkVerificationResult.Rejected
      assertEquals(UpdateFailureReason.INVALID_APK, corruptRes.reason)
      assertFalse(corruptFile.exists())

      // Wrong package name
      val wrongPkgFile = createApk()
      val wrongPkgVerifier = ApkIntegrityVerifier(
        ApkPackageInspector {
          ParsedApkInfo("com.other.app", 7000L, "0.7.0", setOf("cert_sha_prod"))
        },
      )
      val wrongPkgRes = wrongPkgVerifier.verify(wrongPkgFile, meta, installed) as ApkVerificationResult.Rejected
      assertEquals(UpdateFailureReason.PACKAGE_MISMATCH, wrongPkgRes.reason)
      assertFalse(wrongPkgFile.exists())

      // Downgrade rejected
      val downgradeFile = createApk()
      val downgradeVerifier = ApkIntegrityVerifier(
        ApkPackageInspector {
          ParsedApkInfo("com.aistudio.pizzora.xkqzmw", 4000L, "0.4.0", setOf("cert_sha_prod"))
        },
      )
      val downgradeRes = downgradeVerifier.verify(downgradeFile, meta, installed) as ApkVerificationResult.Rejected
      assertEquals(UpdateFailureReason.VERSION_DOWNGRADE, downgradeRes.reason)
      assertFalse(downgradeFile.exists())

      // Signing certificate mismatch rejected
      val badSignerFile = createApk()
      val badSignerVerifier = ApkIntegrityVerifier(
        ApkPackageInspector {
          ParsedApkInfo("com.aistudio.pizzora.xkqzmw", 7000L, "0.7.0", setOf("attacker_cert_sha"))
        },
      )
      val badSignerRes = badSignerVerifier.verify(badSignerFile, meta, installed) as ApkVerificationResult.Rejected
      assertEquals(UpdateFailureReason.SIGNATURE_MISMATCH, badSignerRes.reason)
      assertFalse(badSignerFile.exists())

      // Valid APK accepted and preserved
      val validFile = createApk()
      val validVerifier = ApkIntegrityVerifier(
        ApkPackageInspector {
          ParsedApkInfo("com.aistudio.pizzora.xkqzmw", 7000L, "0.7.0", setOf("cert_sha_prod"))
        },
      )
      val validRes = validVerifier.verify(validFile, meta, installed)
      assertTrue(validRes is ApkVerificationResult.Verified)
      assertTrue(validFile.exists())
    } finally {
      tempDir.deleteRecursively()
    }
  }
}

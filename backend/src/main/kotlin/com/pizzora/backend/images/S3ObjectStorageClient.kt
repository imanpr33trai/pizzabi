package com.pizzora.backend.images

import com.pizzora.backend.core.config.StorageConfig
import com.pizzora.backend.core.security.StorageKeyValidator
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InspectedObject(
  val sizeBytes: Long,
  val contentType: String?,
  val headerBytes: ByteArray,
)

class ObjectStorageUnavailableException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

interface ObjectStorageClient {
  fun presignPut(objectKey: String, contentType: String, expiresInSeconds: Long = 300L): String
  suspend fun inspectObject(objectKey: String): InspectedObject?
  suspend fun deleteObject(objectKey: String): Boolean
  fun publicUrlFor(objectKey: String): String
}

object ImageMagicBytesDetector {
  fun detectMimeType(headerBytes: ByteArray): String? {
    if (headerBytes.size >= 3 &&
      headerBytes[0] == 0xFF.toByte() &&
      headerBytes[1] == 0xD8.toByte() &&
      headerBytes[2] == 0xFF.toByte()
    ) {
      return "image/jpeg"
    }

    if (headerBytes.size >= 8 &&
      headerBytes[0] == 0x89.toByte() &&
      headerBytes[1] == 0x50.toByte() &&
      headerBytes[2] == 0x4E.toByte() &&
      headerBytes[3] == 0x47.toByte() &&
      headerBytes[4] == 0x0D.toByte() &&
      headerBytes[5] == 0x0A.toByte() &&
      headerBytes[6] == 0x1A.toByte() &&
      headerBytes[7] == 0x0A.toByte()
    ) {
      return "image/png"
    }

    if (headerBytes.size >= 12 &&
      headerBytes[0] == 'R'.code.toByte() &&
      headerBytes[1] == 'I'.code.toByte() &&
      headerBytes[2] == 'F'.code.toByte() &&
      headerBytes[3] == 'F'.code.toByte() &&
      headerBytes[8] == 'W'.code.toByte() &&
      headerBytes[9] == 'E'.code.toByte() &&
      headerBytes[10] == 'B'.code.toByte() &&
      headerBytes[11] == 'P'.code.toByte()
    ) {
      return "image/webp"
    }

    return null
  }
}

class S3ObjectStorageClient(
  private val config: StorageConfig,
  private val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(3))
    .build(),
) : ObjectStorageClient {
  private val endpointUri = URI(config.endpoint.trimEnd('/'))
  private val hostHeader: String = if (endpointUri.port > 0) {
    "${endpointUri.host}:${endpointUri.port}"
  } else {
    endpointUri.host ?: config.host
  }

  override fun presignPut(objectKey: String, contentType: String, expiresInSeconds: Long): String {
    val safeKey = StorageKeyValidator.validateObjectKey(objectKey)
    if (config.endpoint.isBlank() || config.bucket.isBlank()) {
      throw ObjectStorageUnavailableException("Object storage endpoint or bucket is not configured.")
    }
    val now = ZonedDateTime.now(ZoneOffset.UTC)
    val amzDate = AMZ_DATE_FORMAT.format(now)
    val dateStamp = DATE_STAMP_FORMAT.format(now)
    val scope = "$dateStamp/${config.region}/s3/aws4_request"
    val canonicalUri = "/${encodeSegment(config.bucket)}/${encodePath(safeKey)}"

    val queryParams = TreeMap<String, String>().apply {
      put("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
      put("X-Amz-Credential", "${config.accessKey}/$scope")
      put("X-Amz-Date", amzDate)
      put("X-Amz-Expires", expiresInSeconds.coerceIn(30L, 3600L).toString())
      put("X-Amz-SignedHeaders", "content-type;host")
    }

    val canonicalQuery = queryParams.entries.joinToString("&") { (k, v) ->
      "${awsEncode(k)}=${awsEncode(v)}"
    }
    val canonicalHeaders = "content-type:${contentType.trim()}\nhost:$hostHeader\n"
    val canonicalRequest = buildString {
      append("PUT\n")
      append(canonicalUri).append('\n')
      append(canonicalQuery).append('\n')
      append(canonicalHeaders).append('\n')
      append("content-type;host\n")
      append("UNSIGNED-PAYLOAD")
    }

    val stringToSign = buildString {
      append("AWS4-HMAC-SHA256\n")
      append(amzDate).append('\n')
      append(scope).append('\n')
      append(sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8)))
    }

    val signature = hmacHex(signingKey(config.secretKey, dateStamp, config.region, "s3"), stringToSign)
    return "${config.endpoint.trimEnd('/')}$canonicalUri?$canonicalQuery&X-Amz-Signature=$signature"
  }

  override suspend fun inspectObject(objectKey: String): InspectedObject? = withContext(Dispatchers.IO) {
    val safeKey = StorageKeyValidator.validateObjectKey(objectKey)
    val headResp = try {
      executeSignedRequest("HEAD", safeKey, HttpRequest.BodyPublishers.noBody())
    } catch (ex: Exception) {
      throw ObjectStorageUnavailableException("Failed to contact object storage during HEAD inspection.", ex)
    }

    if (headResp.statusCode() == 404) {
      return@withContext null
    }
    if (headResp.statusCode() !in 200..299) {
      throw ObjectStorageUnavailableException("Object storage HEAD returned unexpected status ${headResp.statusCode()}.")
    }

    val sizeBytes = headResp.headers().firstValue("content-length").orElse("0").toLongOrNull() ?: 0L
    val contentType = headResp.headers().firstValue("content-type").orElse(null)

    val rangeResp = try {
      executeSignedRequest(
        method = "GET",
        objectKey = safeKey,
        bodyPublisher = HttpRequest.BodyPublishers.noBody(),
        extraHeaders = mapOf("Range" to "bytes=0-15"),
      )
    } catch (ex: Exception) {
      throw ObjectStorageUnavailableException("Failed to read object magic bytes from object storage.", ex)
    }

    if (rangeResp.statusCode() == 404) {
      return@withContext null
    }
    if (rangeResp.statusCode() !in listOf(200, 206)) {
      throw ObjectStorageUnavailableException("Object storage GET returned unexpected status ${rangeResp.statusCode()}.")
    }

    InspectedObject(
      sizeBytes = sizeBytes,
      contentType = contentType,
      headerBytes = rangeResp.body().take(16).toByteArray(),
    )
  }

  override suspend fun deleteObject(objectKey: String): Boolean = withContext(Dispatchers.IO) {
    val safeKey = StorageKeyValidator.validateObjectKey(objectKey)
    runCatching {
      val resp = executeSignedRequest("DELETE", safeKey, HttpRequest.BodyPublishers.noBody())
      resp.statusCode() in 200..299 || resp.statusCode() == 404
    }.getOrDefault(false)
  }

  override fun publicUrlFor(objectKey: String): String {
    val safeKey = StorageKeyValidator.validateObjectKey(objectKey)
    return "${config.publicBaseUrl.trimEnd('/')}/${safeKey.trimStart('/')}"
  }

  private fun executeSignedRequest(
    method: String,
    objectKey: String,
    bodyPublisher: HttpRequest.BodyPublisher,
    extraHeaders: Map<String, String> = emptyMap(),
  ): HttpResponse<ByteArray> {
    val now = ZonedDateTime.now(ZoneOffset.UTC)
    val amzDate = AMZ_DATE_FORMAT.format(now)
    val dateStamp = DATE_STAMP_FORMAT.format(now)
    val scope = "$dateStamp/${config.region}/s3/aws4_request"
    val canonicalUri = "/${encodeSegment(config.bucket)}/${encodePath(objectKey)}"

    val signedHeaderMap = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
      put("host", hostHeader)
      put("x-amz-content-sha256", EMPTY_SHA256)
      put("x-amz-date", amzDate)
      extraHeaders.forEach { (k, v) -> put(k.lowercase(), v.trim()) }
    }

    val canonicalHeaders = signedHeaderMap.entries.joinToString("") { (k, v) -> "$k:$v\n" }
    val signedHeadersList = signedHeaderMap.keys.joinToString(";")
    val canonicalRequest = buildString {
      append(method).append('\n')
      append(canonicalUri).append('\n')
      append('\n')
      append(canonicalHeaders).append('\n')
      append(signedHeadersList).append('\n')
      append(EMPTY_SHA256)
    }

    val stringToSign = buildString {
      append("AWS4-HMAC-SHA256\n")
      append(amzDate).append('\n')
      append(scope).append('\n')
      append(sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8)))
    }
    val signature = hmacHex(signingKey(config.secretKey, dateStamp, config.region, "s3"), stringToSign)
    val authHeader =
      "AWS4-HMAC-SHA256 Credential=${config.accessKey}/$scope, SignedHeaders=$signedHeadersList, Signature=$signature"

    val builder = HttpRequest.newBuilder()
      .uri(URI("${config.endpoint.trimEnd('/')}$canonicalUri"))
      .timeout(Duration.ofSeconds(5))
      .method(method, bodyPublisher)
      .header("x-amz-date", amzDate)
      .header("x-amz-content-sha256", EMPTY_SHA256)
      .header("Authorization", authHeader)

    extraHeaders.forEach { (k, v) -> builder.header(k, v) }
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
  }

  private fun signingKey(secret: String, dateStamp: String, region: String, service: String): ByteArray {
    val kDate = hmacBytes("AWS4$secret".toByteArray(StandardCharsets.UTF_8), dateStamp)
    val kRegion = hmacBytes(kDate, region)
    val kService = hmacBytes(kRegion, service)
    return hmacBytes(kService, "aws4_request")
  }

  private fun hmacBytes(key: ByteArray, data: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
  }

  private fun hmacHex(key: ByteArray, data: String): String =
    hmacBytes(key, data).joinToString("") { "%02x".format(it) }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun encodePath(key: String): String =
    key.trimStart('/').split('/').joinToString("/") { encodeSegment(it) }

  private fun encodeSegment(value: String): String = awsEncode(value)

  private fun awsEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
      .replace("+", "%20")
      .replace("*", "%2A")
      .replace("%7E", "~")

  companion object {
    private val AMZ_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val DATE_STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  }
}


/**
 * Object-storage adapter used when no provider has been configured.
 * The API remains available, while image upload/verification returns a controlled
 * unavailable response instead of preventing the whole application from starting.
 */
object UnavailableObjectStorageClient : ObjectStorageClient {
  override fun presignPut(objectKey: String, contentType: String, expiresInSeconds: Long): String {
    throw ObjectStorageUnavailableException("Object storage is disabled. Set S3_ENABLED=true after configuring an S3-compatible provider.")
  }

  override suspend fun inspectObject(objectKey: String): InspectedObject? {
    throw ObjectStorageUnavailableException("Object storage is disabled.")
  }

  override suspend fun deleteObject(objectKey: String): Boolean = false

  override fun publicUrlFor(objectKey: String): String = ""
}

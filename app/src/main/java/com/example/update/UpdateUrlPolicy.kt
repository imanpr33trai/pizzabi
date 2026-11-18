package com.example.update

import java.io.IOException
import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class UpdateUrlPolicy(
  private val allowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS,
  private val requireHttps: Boolean = true,
) {

  fun requireAllowedApiUrl(rawUrl: String): String =
    validateUrl(rawUrl, allowedHosts = allowedHosts)

  fun requireAllowedAssetUrl(rawUrl: String): String =
    validateUrl(rawUrl, allowedHosts = allowedHosts)

  fun requireAllowedReleasePageUrl(rawUrl: String): String {
    val validated = validateUrl(rawUrl, allowedHosts = allowedHosts)
    val uri = URI(validated)
    val host = uri.host?.lowercase().orEmpty()
    if (requireHttps && host != "github.com" && host !in allowedHosts) {
      throw SecurityException("Untrusted release page host '$host'.")
    }
    return validated
  }

  fun validateSafeAssetFileName(name: String, requiredExtension: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
      throw SecurityException("Asset filename must not be empty.")
    }
    if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains("..")) {
      throw SecurityException("Asset filename contains illegal path characters: '$trimmed'.")
    }
    if (!trimmed.endsWith(requiredExtension, ignoreCase = false)) {
      throw SecurityException("Asset filename '$trimmed' must end with '$requiredExtension'.")
    }
    return trimmed
  }

  private fun validateUrl(rawUrl: String, allowedHosts: Set<String>): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) {
      throw SecurityException("Update URL must not be empty.")
    }
    val uri = runCatching { URI(trimmed) }.getOrNull()
      ?: throw SecurityException("Malformed update URL.")
    val scheme = uri.scheme?.lowercase()
      ?: throw SecurityException("Update URL missing scheme.")
    if (requireHttps && scheme != "https") {
      throw SecurityException("Cleartext or non-HTTPS scheme '$scheme' is prohibited for updates.")
    }
    if (!requireHttps && scheme != "https" && scheme != "http") {
      throw SecurityException("Unsupported update URL scheme '$scheme'.")
    }
    if (!uri.userInfo.isNullOrBlank()) {
      throw SecurityException("Update URL must not embed credentials.")
    }
    val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() }
      ?: throw SecurityException("Update URL missing host.")
    if (host !in allowedHosts) {
      throw SecurityException("Update host '$host' is not in the trusted GitHub release allowlist.")
    }
    val path = uri.rawPath.orEmpty()
    if (path.contains("..")) {
      throw SecurityException("Update URL path contains traversal sequence.")
    }
    return trimmed
  }

  /**
   * Executes a GET request with manual redirect handling so every redirect target
   * is strictly validated against HTTPS and the trusted GitHub host allowlist
   * before any network call is sent to the redirect destination.
   */
  fun executeValidatedGet(
    client: OkHttpClient,
    url: String,
    headers: Map<String, String> = emptyMap(),
    maxRedirects: Int = MAX_REDIRECTS,
  ): Response {
    var currentUrl = requireAllowedAssetUrl(url)
    var hops = 0
    while (true) {
      val builder = Request.Builder().url(currentUrl).get()
      headers.forEach { (k, v) -> builder.header(k, v) }
      val response = client.newCall(builder.build()).execute()
      if (response.code in REDIRECT_STATUS_CODES) {
        val location = response.header("Location")
        response.close()
        if (location.isNullOrBlank()) {
          throw IOException("Redirect response (${response.code}) missing Location header.")
        }
        if (hops >= maxRedirects) {
          throw IOException("Exceeded maximum redirect hops ($maxRedirects) while fetching update.")
        }
        val resolvedUri = runCatching { URI(currentUrl).resolve(location) }.getOrNull()
          ?: throw SecurityException("Malformed redirect Location URL.")
        currentUrl = requireAllowedAssetUrl(resolvedUri.toString())
        hops++
        continue
      }
      return response
    }
  }

  companion object {
    const val GITHUB_REPO_OWNER = "imanpr33trai"
    const val GITHUB_REPO_NAME = "pizzabi"
    const val LATEST_RELEASE_API_URL =
      "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"
    const val MAX_REDIRECTS = 5

    val DEFAULT_ALLOWED_HOSTS: Set<String> = setOf(
      "api.github.com",
      "github.com",
      "objects.githubusercontent.com",
      "release-assets.githubusercontent.com",
      "github-releases.githubusercontent.com",
    )

    private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
  }
}

package com.example.core.network

import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.ConfirmProductImageRequest
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.ForgotPasswordRequest
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.MessageResponse
import com.pizzora.contracts.PresignProductImageRequest
import com.pizzora.contracts.PresignProductImageResponse
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductImageDto
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.RefreshTokenRequest
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.ResendVerificationRequest
import com.pizzora.contracts.ResetPasswordRequest
import com.pizzora.contracts.UpdateCategoryRequest
import com.pizzora.contracts.UpdateProductRequest
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.VerifyEmailRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class PizzoraApiException(
  val statusCode: Int,
  val code: String,
  override val message: String,
  val details: Map<String, String> = emptyMap(),
) : RuntimeException(message)

class PizzoraApiClient(
  baseUrl: String,
  private val tokenStorage: TokenStorage,
  engine: HttpClientEngine = OkHttp.create(),
) {
  private val normalizedBaseUrl = baseUrl.trimEnd('/')
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
  private val refreshMutex = Mutex()

  // Separate client without Auth plugin to execute /auth/refresh without recursive 401 interception
  private val refreshClient = HttpClient(engine) {
    install(ContentNegotiation) { json(json) }
    defaultRequest { url(normalizedBaseUrl) }
  }

  // Raw client without Auth header or baseUrl for direct S3/MinIO presigned PUT uploads
  private val rawUploadClient = HttpClient(engine)

  val httpClient = HttpClient(engine) {
    install(ContentNegotiation) { json(json) }
    install(WebSockets)
    defaultRequest { url(normalizedBaseUrl) }
    install(Auth) {
      bearer {
        loadTokens {
          tokenStorage.currentSession()?.let { BearerTokens(it.accessToken, it.refreshToken) }
        }
        refreshTokens {
          val attemptedAccessToken = oldTokens?.accessToken
          refreshMutex.withLock {
            val latest = tokenStorage.currentSession() ?: return@withLock null
            if (attemptedAccessToken != null && latest.accessToken != attemptedAccessToken) {
              return@withLock BearerTokens(latest.accessToken, latest.refreshToken)
            }
            val refreshed = executeRefresh(latest.refreshToken)
            if (refreshed != null) {
              tokenStorage.saveSession(
                AuthSession(
                  accessToken = refreshed.accessToken,
                  refreshToken = refreshed.refreshToken,
                  user = refreshed.user,
                ),
              )
              BearerTokens(refreshed.accessToken, refreshed.refreshToken)
            } else {
              tokenStorage.clearSession()
              null
            }
          }
        }
        sendWithoutRequest { request ->
          val path = request.url.encodedPath
          !path.startsWith("/api/v1/auth/login") &&
            !path.startsWith("/api/v1/auth/register") &&
            !path.startsWith("/api/v1/auth/refresh") &&
            !path.startsWith("/api/v1/auth/verify-email") &&
            !path.startsWith("/api/v1/auth/resend-verification") &&
            !path.startsWith("/api/v1/auth/forgot-password") &&
            !path.startsWith("/api/v1/auth/reset-password")
        }
      }
    }
  }

  private suspend fun executeRefresh(refreshToken: String): AuthTokensResponse? {
    val response = runCatching {
      refreshClient.post("/api/v1/auth/refresh") {
        contentType(ContentType.Application.Json)
        setBody(RefreshTokenRequest(refreshToken))
      }
    }.getOrNull() ?: return null

    if (response.status != HttpStatusCode.OK) return null
    return runCatching { json.decodeFromString<AuthTokensResponse>(response.bodyAsText()) }.getOrNull()
  }

  suspend fun register(request: RegisterRequest): MessageResponse {
    val response = httpClient.post("/api/v1/auth/register") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun verifyEmail(request: VerifyEmailRequest): MessageResponse {
    val response = httpClient.post("/api/v1/auth/verify-email") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun resendVerification(request: ResendVerificationRequest): MessageResponse {
    val response = httpClient.post("/api/v1/auth/resend-verification") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun login(request: LoginRequest): AuthTokensResponse {
    val response = httpClient.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun logout(): MessageResponse {
    val response = httpClient.post("/api/v1/auth/logout")
    return decodeOrThrow(response)
  }

  suspend fun forgotPassword(request: ForgotPasswordRequest): MessageResponse {
    val response = httpClient.post("/api/v1/auth/forgot-password") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun resetPassword(request: ResetPasswordRequest): MessageResponse {
    val response = httpClient.post("/api/v1/auth/reset-password") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getMe(): UserProfileDto {
    val response = httpClient.get("/api/v1/me")
    return decodeOrThrow(response)
  }

  suspend fun getPublicCategories(): List<CategoryDto> {
    val response = httpClient.get("/api/v1/catalog/categories")
    return decodeOrThrow(response)
  }

  suspend fun getPublicMenu(): PublicMenuResponse {
    val response = httpClient.get("/api/v1/catalog/menu")
    return decodeOrThrow(response)
  }

  suspend fun getOwnerCategories(): List<CategoryDto> {
    val response = httpClient.get("/api/v1/owner/categories")
    return decodeOrThrow(response)
  }

  suspend fun createCategory(request: CreateCategoryRequest): CategoryDto {
    val response = httpClient.post("/api/v1/owner/categories") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun updateCategory(categoryId: String, request: UpdateCategoryRequest): CategoryDto {
    val response = httpClient.patch("/api/v1/owner/categories/$categoryId") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun deleteCategory(categoryId: String) {
    val response = httpClient.delete("/api/v1/owner/categories/$categoryId")
    if (!response.status.isSuccess()) {
      throwDecodedError(response)
    }
  }

  suspend fun getOwnerProducts(): List<ProductDto> {
    val response = httpClient.get("/api/v1/owner/products")
    return decodeOrThrow(response)
  }

  suspend fun createProduct(request: CreateProductRequest): ProductDto {
    val response = httpClient.post("/api/v1/owner/products") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun updateProduct(productId: String, request: UpdateProductRequest): ProductDto {
    val response = httpClient.patch("/api/v1/owner/products/$productId") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun deleteProduct(productId: String) {
    val response = httpClient.delete("/api/v1/owner/products/$productId")
    if (!response.status.isSuccess()) {
      throwDecodedError(response)
    }
  }

  suspend fun presignProductImage(
    productId: String,
    request: PresignProductImageRequest,
  ): PresignProductImageResponse {
    val response = httpClient.post("/api/v1/owner/products/$productId/images/presign") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun uploadBytesToPresignedUrl(
    uploadUrl: String,
    bytes: ByteArray,
    mimeType: String,
  ) {
    val parsed = ContentType.parse(mimeType)
    val response = rawUploadClient.put(uploadUrl) {
      contentType(parsed)
      setBody(bytes)
    }
    if (!response.status.isSuccess()) {
      throw PizzoraApiException(
        statusCode = response.status.value,
        code = "PRESIGNED_UPLOAD_FAILED",
        message = "Image upload to object storage failed (${response.status.value}).",
      )
    }
  }

  suspend fun confirmProductImage(productId: String, imageId: String): ProductImageDto {
    val response = httpClient.post("/api/v1/owner/products/$productId/images/confirm") {
      contentType(ContentType.Application.Json)
      setBody(ConfirmProductImageRequest(imageId))
    }
    return decodeOrThrow(response)
  }

  suspend fun deleteProductImage(productId: String, imageId: String) {
    val response = httpClient.delete("/api/v1/owner/products/$productId/images/$imageId")
    if (!response.status.isSuccess()) {
      throwDecodedError(response)
    }
  }

  private suspend inline fun <reified T> decodeOrThrow(response: HttpResponse): T {
    if (response.status.isSuccess()) {
      return json.decodeFromString(response.bodyAsText())
    }
    throwDecodedError(response)
  }

  private suspend fun throwDecodedError(response: HttpResponse): Nothing {
    val text = response.bodyAsText()
    val envelope = runCatching { json.decodeFromString<ApiErrorEnvelope>(text) }.getOrNull()
    if (envelope != null) {
      throw PizzoraApiException(
        statusCode = response.status.value,
        code = envelope.error.code,
        message = envelope.error.message,
        details = envelope.error.details,
      )
    }
    throw PizzoraApiException(
      statusCode = response.status.value,
      code = "HTTP_${response.status.value}",
      message = text.ifBlank { response.status.description },
    )
  }
}

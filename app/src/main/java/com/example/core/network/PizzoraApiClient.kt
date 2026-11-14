package com.example.core.network

import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.AdjustInventoryRequest
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuditLogsResponse
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.AvailableCouponsResponse
import com.pizzora.contracts.AvailableSlotsResponse
import com.pizzora.contracts.CancelOrderRequest
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.ConfirmProductImageRequest
import com.pizzora.contracts.CouponDto
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCouponRequest
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateDeliveryZoneRequest
import com.pizzora.contracts.CreateIngredientRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateScheduledSlotRequest
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.DeliveryZoneDto
import com.pizzora.contracts.EventsReplayResponse
import com.pizzora.contracts.FailPaymentAttemptRequest
import com.pizzora.contracts.ForgotPasswordRequest
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.InventoryLedgerEntryDto
import com.pizzora.contracts.KdsActionRequest
import com.pizzora.contracts.KdsTicketActionResponse
import com.pizzora.contracts.KdsTicketsResponse
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.MarkAllNotificationsReadResponse
import com.pizzora.contracts.MarkNotificationReadResponse
import com.pizzora.contracts.MessageResponse
import com.pizzora.contracts.NotificationInboxResponse
import com.pizzora.contracts.NotificationPreferencesDto
import com.pizzora.contracts.OperationsReportResponse
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderPaymentStatusResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OwnerDashboardResponse
import com.pizzora.contracts.OwnerRefundRequest
import com.pizzora.contracts.PresignProductImageRequest
import com.pizzora.contracts.PresignProductImageResponse
import com.pizzora.contracts.PricingQuoteRequest
import com.pizzora.contracts.PricingQuoteResponse
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductImageDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.PublicConfigResponse
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.PutRecipeRequest
import com.pizzora.contracts.RecipeBindingDto
import com.pizzora.contracts.RecipesOverviewResponse
import com.pizzora.contracts.RefreshTokenRequest
import com.pizzora.contracts.RefundDto
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.ReplaceCartRequest
import com.pizzora.contracts.ResendVerificationRequest
import com.pizzora.contracts.ResetPasswordRequest
import com.pizzora.contracts.RestaurantSettingsDto
import com.pizzora.contracts.RetryPaymentRequest
import com.pizzora.contracts.SalesReportResponse
import com.pizzora.contracts.ScheduledSlotDto
import com.pizzora.contracts.StaffMemberDto
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.TransitionOrderStatusRequest
import com.pizzora.contracts.UpdateCategoryRequest
import com.pizzora.contracts.UpdateCouponRequest
import com.pizzora.contracts.UpdateCrustRequest
import com.pizzora.contracts.UpdateDeliveryZoneRequest
import com.pizzora.contracts.UpdateIngredientRequest
import com.pizzora.contracts.UpdateNotificationPreferencesRequest
import com.pizzora.contracts.UpdateOrderPriorityRequest
import com.pizzora.contracts.UpdateProductRequest
import com.pizzora.contracts.UpdateProfileRequest
import com.pizzora.contracts.UpdateRestaurantSettingsRequest
import com.pizzora.contracts.UpdateScheduledSlotRequest
import com.pizzora.contracts.UpdateStaffRequest
import com.pizzora.contracts.UpdateToppingRequest
import com.pizzora.contracts.UpdateVariantRequest
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.ValidateCartRequest
import com.pizzora.contracts.VerifyEmailRequest
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
import com.pizzora.contracts.VerifyRazorpayPaymentResponse
import com.pizzora.contracts.WsTicketResponse
import io.ktor.client.request.header
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
import io.ktor.http.encodeURLParameter
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
  val apiBaseUrl: String get() = normalizedBaseUrl
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
  val wireJson: Json get() = json
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
          tokenStorage.awaitSession()?.let { BearerTokens(it.accessToken, it.refreshToken) }
        }
        refreshTokens {
          val attemptedAccessToken = oldTokens?.accessToken
          refreshMutex.withLock {
            val latest = tokenStorage.awaitSession() ?: return@withLock null
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

  suspend fun updateMe(request: UpdateProfileRequest): UserProfileDto {
    val response = httpClient.patch("/api/v1/me") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
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

  suspend fun getCart(): CartResponseDto {
    val response = httpClient.get("/api/v1/cart")
    return decodeOrThrow(response)
  }

  suspend fun replaceCart(request: ReplaceCartRequest): CartResponseDto {
    val response = httpClient.put("/api/v1/cart") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun validateCart(request: ValidateCartRequest): CartResponseDto {
    val response = httpClient.post("/api/v1/cart/validate") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getAvailableCoupons(subtotalPaise: Long? = null): AvailableCouponsResponse {
    val query = if (subtotalPaise != null) "?subtotal_paise=$subtotalPaise" else ""
    val response = httpClient.get("/api/v1/coupons/available$query")
    return decodeOrThrow(response)
  }

  suspend fun quotePricing(request: PricingQuoteRequest): PricingQuoteResponse {
    val response = httpClient.post("/api/v1/pricing/quote") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getOwnerCoupons(): List<CouponDto> {
    val response = httpClient.get("/api/v1/owner/coupons")
    return decodeOrThrow(response)
  }

  suspend fun createOwnerCoupon(request: CreateCouponRequest): CouponDto {
    val response = httpClient.post("/api/v1/owner/coupons") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun updateOwnerCoupon(couponId: String, request: UpdateCouponRequest): CouponDto {
    val response = httpClient.patch("/api/v1/owner/coupons/$couponId") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun deleteOwnerCoupon(couponId: String) {
    val response = httpClient.delete("/api/v1/owner/coupons/$couponId")
    if (!response.status.isSuccess()) {
      throwDecodedError(response)
    }
  }

  suspend fun getOwnerInventory(): List<InventoryItemDto> {
    val response = httpClient.get("/api/v1/owner/inventory")
    return decodeOrThrow(response)
  }

  suspend fun createIngredient(request: CreateIngredientRequest): InventoryItemDto {
    val response = httpClient.post("/api/v1/owner/inventory") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun adjustInventory(request: AdjustInventoryRequest): InventoryItemDto {
    val response = httpClient.post("/api/v1/owner/inventory/adjust") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getInventoryLedger(ingredientId: String? = null, limit: Int = 50): List<InventoryLedgerEntryDto> {
    val params = buildList {
      if (!ingredientId.isNullOrBlank()) add("ingredient_id=$ingredientId")
      add("limit=$limit")
    }.joinToString("&")
    val response = httpClient.get("/api/v1/owner/inventory/ledger?$params")
    return decodeOrThrow(response)
  }

  suspend fun getOwnerRecipes(): RecipesOverviewResponse {
    val response = httpClient.get("/api/v1/owner/recipes")
    return decodeOrThrow(response)
  }

  suspend fun putOwnerRecipe(request: PutRecipeRequest): RecipeBindingDto {
    val response = httpClient.put("/api/v1/owner/recipes") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getAddresses(): List<AddressDto> {
    val response = httpClient.get("/api/v1/me/addresses")
    return decodeOrThrow(response)
  }

  suspend fun createAddress(request: UpsertAddressRequest): AddressDto {
    val response = httpClient.post("/api/v1/me/addresses") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun updateAddress(addressId: String, request: UpsertAddressRequest): AddressDto {
    val response = httpClient.patch("/api/v1/me/addresses/$addressId") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun deleteAddress(addressId: String) {
    val response = httpClient.delete("/api/v1/me/addresses/$addressId")
    if (!response.status.isSuccess()) {
      throwDecodedError(response)
    }
  }

  suspend fun checkDeliveryZone(pincode: String): DeliveryZoneCheckResponse {
    val response = httpClient.get("/api/v1/delivery-zones/check?pincode=${pincode.trim()}")
    return decodeOrThrow(response)
  }

  suspend fun getAvailableSlots(type: FulfillmentType, date: String? = null): AvailableSlotsResponse {
    val params = buildList {
      add("type=${type.name}")
      if (!date.isNullOrBlank()) add("date=${date.trim()}")
    }.joinToString("&")
    val response = httpClient.get("/api/v1/slots?$params")
    return decodeOrThrow(response)
  }

  suspend fun checkout(idempotencyKey: String, request: CheckoutRequest): CheckoutResponse {
    val response = httpClient.post("/api/v1/checkout") {
      header("Idempotency-Key", idempotencyKey)
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getOrders(limit: Int = 50): OrderListResponse {
    val response = httpClient.get("/api/v1/orders?limit=$limit")
    return decodeOrThrow(response)
  }

  suspend fun getOrderDetail(orderId: String): OrderDetailDto {
    val response = httpClient.get("/api/v1/orders/$orderId")
    return decodeOrThrow(response)
  }

  suspend fun cancelOrder(orderId: String, request: CancelOrderRequest = CancelOrderRequest()): OrderDetailDto {
    val response = httpClient.post("/api/v1/orders/$orderId/cancel") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getOrderPaymentStatus(orderId: String): OrderPaymentStatusResponse {
    val response = httpClient.get("/api/v1/orders/$orderId/payment")
    return decodeOrThrow(response)
  }

  suspend fun retryOrderPayment(
    orderId: String,
    idempotencyKey: String,
    request: RetryPaymentRequest = RetryPaymentRequest(),
  ): OrderPaymentStatusResponse {
    val response = httpClient.post("/api/v1/orders/$orderId/payments/retry") {
      header("Idempotency-Key", idempotencyKey)
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun reportPaymentFailure(
    orderId: String,
    request: FailPaymentAttemptRequest,
  ): OrderPaymentStatusResponse {
    val response = httpClient.post("/api/v1/orders/$orderId/payments/fail") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun verifyRazorpayPayment(request: VerifyRazorpayPaymentRequest): VerifyRazorpayPaymentResponse {
    val response = httpClient.post("/api/v1/payments/razorpay/verify") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getKdsTickets(includeCompleted: Boolean = false): KdsTicketsResponse {
    val response = httpClient.get("/api/v1/kds/tickets?include_completed=$includeCompleted")
    return decodeOrThrow(response)
  }

  suspend fun ackKdsOrder(orderId: String, reason: String? = null): KdsTicketActionResponse {
    val response = httpClient.post("/api/v1/kds/orders/$orderId/ack") {
      contentType(ContentType.Application.Json)
      setBody(KdsActionRequest(reason))
    }
    return decodeOrThrow(response)
  }

  suspend fun startPrepKdsOrder(orderId: String, reason: String? = null): KdsTicketActionResponse {
    val response = httpClient.post("/api/v1/kds/orders/$orderId/start-prep") {
      contentType(ContentType.Application.Json)
      setBody(KdsActionRequest(reason))
    }
    return decodeOrThrow(response)
  }

  suspend fun markReadyKdsOrder(orderId: String, reason: String? = null): KdsTicketActionResponse {
    val response = httpClient.post("/api/v1/kds/orders/$orderId/ready") {
      contentType(ContentType.Application.Json)
      setBody(KdsActionRequest(reason))
    }
    return decodeOrThrow(response)
  }

  suspend fun dispatchKdsOrder(orderId: String, reason: String? = null): KdsTicketActionResponse {
    val response = httpClient.post("/api/v1/kds/orders/$orderId/dispatch") {
      contentType(ContentType.Application.Json)
      setBody(KdsActionRequest(reason))
    }
    return decodeOrThrow(response)
  }

  suspend fun completeKdsOrder(orderId: String, reason: String? = null): KdsTicketActionResponse {
    val response = httpClient.post("/api/v1/kds/orders/$orderId/complete") {
      contentType(ContentType.Application.Json)
      setBody(KdsActionRequest(reason))
    }
    return decodeOrThrow(response)
  }

  suspend fun updateKdsOrderPriority(
    orderId: String,
    request: UpdateOrderPriorityRequest,
  ): KdsTicketActionResponse {
    val response = httpClient.patch("/api/v1/kds/orders/$orderId/priority") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getPublicConfig(): PublicConfigResponse {
    val response = httpClient.get("/api/v1/config/public")
    return decodeOrThrow(response)
  }

  suspend fun getOwnerDashboard(): OwnerDashboardResponse {
    val response = httpClient.get("/api/v1/owner/dashboard")
    return decodeOrThrow(response)
  }

  suspend fun getOwnerSettings(): RestaurantSettingsDto {
    val response = httpClient.get("/api/v1/owner/settings")
    return decodeOrThrow(response)
  }

  suspend fun updateOwnerSettings(request: UpdateRestaurantSettingsRequest): RestaurantSettingsDto {
    val response = httpClient.put("/api/v1/owner/settings") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getOwnerOrders(status: OrderStatus? = null, limit: Int = 50): OrderListResponse {
    val params = buildList {
      if (status != null) add("status=${status.name}")
      add("limit=$limit")
    }.joinToString("&")
    val response = httpClient.get("/api/v1/owner/orders?$params")
    return decodeOrThrow(response)
  }

  suspend fun transitionOwnerOrderStatus(
    orderId: String,
    request: TransitionOrderStatusRequest,
  ): OrderDetailDto {
    val response = httpClient.patch("/api/v1/owner/orders/$orderId/status") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun refundOwnerOrder(
    orderId: String,
    idempotencyKey: String,
    request: OwnerRefundRequest,
  ): RefundDto {
    val response = httpClient.post("/api/v1/owner/orders/$orderId/refund") {
      header("Idempotency-Key", idempotencyKey)
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getOwnerStaff(): List<StaffMemberDto> {
    val response = httpClient.get("/api/v1/owner/staff")
    return decodeOrThrow(response)
  }

  suspend fun createOwnerStaff(request: CreateStaffRequest): StaffMemberDto {
    val response = httpClient.post("/api/v1/owner/staff") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun updateOwnerStaff(userId: String, request: UpdateStaffRequest): StaffMemberDto {
    val response = httpClient.patch("/api/v1/owner/staff/$userId") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun addProductVariant(productId: String, request: CreateVariantRequest): ProductVariantDto {
    val response = httpClient.post("/api/v1/owner/products/$productId/variants") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun updateProductVariant(variantId: String, request: UpdateVariantRequest): ProductVariantDto {
    val response = httpClient.patch("/api/v1/owner/variants/$variantId") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getOwnerCrusts(): List<CrustOptionDto> {
    val response = httpClient.get("/api/v1/owner/crusts")
    return decodeOrThrow(response)
  }

  suspend fun createOwnerCrust(request: CreateCrustRequest): CrustOptionDto {
    val response = httpClient.post("/api/v1/owner/crusts") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun updateOwnerCrust(crustId: String, request: UpdateCrustRequest): CrustOptionDto {
    val response = httpClient.patch("/api/v1/owner/crusts/$crustId") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getOwnerToppings(): List<ToppingDto> {
    val response = httpClient.get("/api/v1/owner/toppings")
    return decodeOrThrow(response)
  }

  suspend fun createOwnerTopping(request: CreateToppingRequest): ToppingDto {
    val response = httpClient.post("/api/v1/owner/toppings") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun updateOwnerTopping(toppingId: String, request: UpdateToppingRequest): ToppingDto {
    val response = httpClient.patch("/api/v1/owner/toppings/$toppingId") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun updateIngredient(ingredientId: String, request: UpdateIngredientRequest): InventoryItemDto {
    val response = httpClient.patch("/api/v1/owner/inventory/$ingredientId") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getOwnerDeliveryZones(): List<DeliveryZoneDto> {
    val response = httpClient.get("/api/v1/owner/delivery-zones")
    return decodeOrThrow(response)
  }

  suspend fun createOwnerDeliveryZone(request: CreateDeliveryZoneRequest): DeliveryZoneDto {
    val response = httpClient.post("/api/v1/owner/delivery-zones") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun updateOwnerDeliveryZone(zoneId: String, request: UpdateDeliveryZoneRequest): DeliveryZoneDto {
    val response = httpClient.patch("/api/v1/owner/delivery-zones/$zoneId") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun deleteOwnerDeliveryZone(zoneId: String) {
    val response = httpClient.delete("/api/v1/owner/delivery-zones/$zoneId")
    if (!response.status.isSuccess()) {
      throwDecodedError(response)
    }
  }

  suspend fun getOwnerSlots(type: FulfillmentType? = null, date: String? = null): List<ScheduledSlotDto> {
    val params = buildList {
      if (type != null) add("type=${type.name}")
      if (!date.isNullOrBlank()) add("date=${date.trim()}")
    }.joinToString("&")
    val query = if (params.isNotEmpty()) "?$params" else ""
    val response = httpClient.get("/api/v1/owner/slots$query")
    return decodeOrThrow(response)
  }

  suspend fun createOwnerSlot(request: CreateScheduledSlotRequest): ScheduledSlotDto {
    val response = httpClient.post("/api/v1/owner/slots") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun updateOwnerSlot(slotId: String, request: UpdateScheduledSlotRequest): ScheduledSlotDto {
    val response = httpClient.patch("/api/v1/owner/slots/$slotId") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun deleteOwnerSlot(slotId: String) {
    val response = httpClient.delete("/api/v1/owner/slots/$slotId")
    if (!response.status.isSuccess()) {
      throwDecodedError(response)
    }
  }

  suspend fun getNotifications(unreadOnly: Boolean = false, limit: Int = 50): NotificationInboxResponse {
    val params = buildList {
      if (unreadOnly) add("unread=true")
      add("limit=$limit")
    }.joinToString("&")
    val response = httpClient.get("/api/v1/notifications?$params")
    return decodeOrThrow(response)
  }

  suspend fun markNotificationRead(notificationId: String): MarkNotificationReadResponse {
    val response = httpClient.post("/api/v1/notifications/$notificationId/read")
    return decodeOrThrow(response)
  }

  suspend fun markAllNotificationsRead(): MarkAllNotificationsReadResponse {
    val response = httpClient.post("/api/v1/notifications/read-all")
    return decodeOrThrow(response)
  }

  suspend fun getNotificationPreferences(): NotificationPreferencesDto {
    val response = httpClient.get("/api/v1/notifications/preferences")
    return decodeOrThrow(response)
  }

  suspend fun updateNotificationPreferences(
    request: UpdateNotificationPreferencesRequest,
  ): NotificationPreferencesDto {
    val response = httpClient.put("/api/v1/notifications/preferences") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    return decodeOrThrow(response)
  }

  suspend fun getOwnerSalesReport(
    startDate: String? = null,
    endDate: String? = null,
  ): SalesReportResponse {
    val params = buildList {
      if (!startDate.isNullOrBlank()) add("start_date=${startDate.trim().encodeURLParameter()}")
      if (!endDate.isNullOrBlank()) add("end_date=${endDate.trim().encodeURLParameter()}")
    }.joinToString("&")
    val query = if (params.isNotEmpty()) "?$params" else ""
    val response = httpClient.get("/api/v1/owner/reports/sales$query")
    return decodeOrThrow(response)
  }

  suspend fun getOwnerOperationsReport(
    startDate: String? = null,
    endDate: String? = null,
  ): OperationsReportResponse {
    val params = buildList {
      if (!startDate.isNullOrBlank()) add("start_date=${startDate.trim().encodeURLParameter()}")
      if (!endDate.isNullOrBlank()) add("end_date=${endDate.trim().encodeURLParameter()}")
    }.joinToString("&")
    val query = if (params.isNotEmpty()) "?$params" else ""
    val response = httpClient.get("/api/v1/owner/reports/operations$query")
    return decodeOrThrow(response)
  }

  suspend fun getOwnerAuditLogs(
    entityType: String? = null,
    action: String? = null,
    entityId: String? = null,
    cursor: String? = null,
    limit: Int = 50,
  ): AuditLogsResponse {
    val params = buildList {
      if (!entityType.isNullOrBlank()) add("entity_type=${entityType.trim().encodeURLParameter()}")
      if (!action.isNullOrBlank()) add("action=${action.trim().encodeURLParameter()}")
      if (!entityId.isNullOrBlank()) add("entity_id=${entityId.trim().encodeURLParameter()}")
      if (!cursor.isNullOrBlank()) add("cursor=${cursor.trim().encodeURLParameter()}")
      add("limit=${limit.coerceIn(1, 100)}")
    }.joinToString("&")
    val response = httpClient.get("/api/v1/owner/audit-logs?$params")
    return decodeOrThrow(response)
  }

  suspend fun mintWsTicket(): WsTicketResponse {
    val response = httpClient.post("/api/v1/auth/ws-ticket")
    return decodeOrThrow(response)
  }

  suspend fun replayEvents(sinceSeq: Long, limit: Int = 100): EventsReplayResponse {
    val response = httpClient.get("/api/v1/events/replay?since_seq=$sinceSeq&limit=$limit")
    return decodeOrThrow(response)
  }

  fun wsEventsUrl(ticket: String, sinceSeq: Long): String {
    val wsBase = when {
      normalizedBaseUrl.startsWith("https://", ignoreCase = true) ->
        "wss://" + normalizedBaseUrl.removePrefix("https://")
      normalizedBaseUrl.startsWith("http://", ignoreCase = true) ->
        "ws://" + normalizedBaseUrl.removePrefix("http://")
      else -> normalizedBaseUrl
    }
    return "$wsBase/ws/v1/events?ticket=$ticket&since_seq=$sinceSeq"
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

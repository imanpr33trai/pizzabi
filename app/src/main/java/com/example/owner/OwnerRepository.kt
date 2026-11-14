package com.example.owner

import android.content.Context
import android.net.Uri
import com.example.core.media.ProductImageUploader
import com.example.core.network.PizzoraApiClient
import com.pizzora.contracts.AdjustInventoryRequest
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CouponDto
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCouponRequest
import com.pizzora.contracts.CreateIngredientRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.InventoryLedgerEntryDto
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.OwnerDashboardResponse
import com.pizzora.contracts.OwnerRefundRequest
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductImageDto
import com.pizzora.contracts.PutRecipeRequest
import com.pizzora.contracts.RecipeBindingDto
import com.pizzora.contracts.RecipesOverviewResponse
import com.pizzora.contracts.RefundDto
import com.pizzora.contracts.RestaurantSettingsDto
import com.pizzora.contracts.StaffMemberDto
import com.pizzora.contracts.TransitionOrderStatusRequest
import com.pizzora.contracts.UpdateCategoryRequest
import com.pizzora.contracts.UpdateCouponRequest
import com.pizzora.contracts.UpdateProductRequest
import com.pizzora.contracts.UpdateRestaurantSettingsRequest
import com.pizzora.contracts.UpdateStaffRequest

interface OwnerRepository {
  suspend fun fetchDashboard(): OwnerDashboardResponse
  suspend fun fetchSettings(): RestaurantSettingsDto
  suspend fun updateSettings(request: UpdateRestaurantSettingsRequest): RestaurantSettingsDto

  suspend fun fetchCategories(): List<CategoryDto>
  suspend fun createCategory(request: CreateCategoryRequest): CategoryDto
  suspend fun updateCategory(categoryId: String, request: UpdateCategoryRequest): CategoryDto

  suspend fun fetchProducts(): List<ProductDto>
  suspend fun createProduct(request: CreateProductRequest): ProductDto
  suspend fun updateProduct(productId: String, request: UpdateProductRequest): ProductDto
  suspend fun uploadProductImage(context: Context, productId: String, uri: Uri, sortOrder: Int = 0): ProductImageDto
  suspend fun deleteProductImage(productId: String, imageId: String)

  suspend fun fetchRecipes(): RecipesOverviewResponse
  suspend fun putRecipe(request: PutRecipeRequest): RecipeBindingDto

  suspend fun fetchInventory(): List<InventoryItemDto>
  suspend fun createIngredient(request: CreateIngredientRequest): InventoryItemDto
  suspend fun adjustInventory(request: AdjustInventoryRequest): InventoryItemDto
  suspend fun fetchInventoryLedger(ingredientId: String? = null, limit: Int = 50): List<InventoryLedgerEntryDto>

  suspend fun fetchCoupons(): List<CouponDto>
  suspend fun createCoupon(request: CreateCouponRequest): CouponDto
  suspend fun updateCoupon(couponId: String, request: UpdateCouponRequest): CouponDto

  suspend fun fetchOrders(status: OrderStatus? = null, limit: Int = 50): List<OrderSummaryDto>
  suspend fun fetchOrderDetail(orderId: String): OrderDetailDto
  suspend fun transitionOrderStatus(orderId: String, request: TransitionOrderStatusRequest): OrderDetailDto
  suspend fun refundOrder(orderId: String, idempotencyKey: String, request: OwnerRefundRequest): RefundDto

  suspend fun fetchStaff(): List<StaffMemberDto>
  suspend fun createStaff(request: CreateStaffRequest): StaffMemberDto
  suspend fun updateStaff(userId: String, request: UpdateStaffRequest): StaffMemberDto
}

class NetworkOwnerRepository(
  private val apiClient: PizzoraApiClient,
  private val imageUploader: ProductImageUploader,
) : OwnerRepository {
  override suspend fun fetchDashboard(): OwnerDashboardResponse = apiClient.getOwnerDashboard()

  override suspend fun fetchSettings(): RestaurantSettingsDto = apiClient.getOwnerSettings()

  override suspend fun updateSettings(request: UpdateRestaurantSettingsRequest): RestaurantSettingsDto =
    apiClient.updateOwnerSettings(request)

  override suspend fun fetchCategories(): List<CategoryDto> = apiClient.getOwnerCategories()

  override suspend fun createCategory(request: CreateCategoryRequest): CategoryDto =
    apiClient.createCategory(request)

  override suspend fun updateCategory(categoryId: String, request: UpdateCategoryRequest): CategoryDto =
    apiClient.updateCategory(categoryId, request)

  override suspend fun fetchProducts(): List<ProductDto> = apiClient.getOwnerProducts()

  override suspend fun createProduct(request: CreateProductRequest): ProductDto =
    apiClient.createProduct(request)

  override suspend fun updateProduct(productId: String, request: UpdateProductRequest): ProductDto =
    apiClient.updateProduct(productId, request)

  override suspend fun uploadProductImage(
    context: Context,
    productId: String,
    uri: Uri,
    sortOrder: Int,
  ): ProductImageDto = imageUploader.compressAndUploadFromUri(
    context = context,
    productId = productId,
    uri = uri,
    sortOrder = sortOrder,
  )

  override suspend fun deleteProductImage(productId: String, imageId: String) =
    apiClient.deleteProductImage(productId, imageId)

  override suspend fun fetchRecipes(): RecipesOverviewResponse = apiClient.getOwnerRecipes()

  override suspend fun putRecipe(request: PutRecipeRequest): RecipeBindingDto =
    apiClient.putOwnerRecipe(request)

  override suspend fun fetchInventory(): List<InventoryItemDto> = apiClient.getOwnerInventory()

  override suspend fun createIngredient(request: CreateIngredientRequest): InventoryItemDto =
    apiClient.createIngredient(request)

  override suspend fun adjustInventory(request: AdjustInventoryRequest): InventoryItemDto =
    apiClient.adjustInventory(request)

  override suspend fun fetchInventoryLedger(ingredientId: String?, limit: Int): List<InventoryLedgerEntryDto> =
    apiClient.getInventoryLedger(ingredientId, limit)

  override suspend fun fetchCoupons(): List<CouponDto> = apiClient.getOwnerCoupons()

  override suspend fun createCoupon(request: CreateCouponRequest): CouponDto =
    apiClient.createOwnerCoupon(request)

  override suspend fun updateCoupon(couponId: String, request: UpdateCouponRequest): CouponDto =
    apiClient.updateOwnerCoupon(couponId, request)

  override suspend fun fetchOrders(status: OrderStatus?, limit: Int): List<OrderSummaryDto> =
    apiClient.getOwnerOrders(status, limit).orders

  override suspend fun fetchOrderDetail(orderId: String): OrderDetailDto =
    apiClient.getOrderDetail(orderId)

  override suspend fun transitionOrderStatus(
    orderId: String,
    request: TransitionOrderStatusRequest,
  ): OrderDetailDto = apiClient.transitionOwnerOrderStatus(orderId, request)

  override suspend fun refundOrder(
    orderId: String,
    idempotencyKey: String,
    request: OwnerRefundRequest,
  ): RefundDto = apiClient.refundOwnerOrder(orderId, idempotencyKey, request)

  override suspend fun fetchStaff(): List<StaffMemberDto> = apiClient.getOwnerStaff()

  override suspend fun createStaff(request: CreateStaffRequest): StaffMemberDto =
    apiClient.createOwnerStaff(request)

  override suspend fun updateStaff(userId: String, request: UpdateStaffRequest): StaffMemberDto =
    apiClient.updateOwnerStaff(userId, request)
}

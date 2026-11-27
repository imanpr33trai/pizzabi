package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SizeCode {
  @SerialName("REGULAR")
  REGULAR,

  @SerialName("MEDIUM")
  MEDIUM,

  @SerialName("LARGE")
  LARGE,
}

@Serializable
enum class ProductImageStatus {
  @SerialName("PENDING")
  PENDING,

  @SerialName("ACTIVE")
  ACTIVE,

  @SerialName("DELETED")
  DELETED,
}

@Serializable
data class CategoryDto(
  val id: String,
  val name: String,
  @SerialName("sort_order") val sortOrder: Int,
  @SerialName("is_active") val isActive: Boolean,
  @SerialName("created_at") val createdAt: String,
)

@Serializable
data class CreateCategoryRequest(
  val name: String,
  @SerialName("sort_order") val sortOrder: Int = 0,
  @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class UpdateCategoryRequest(
  val name: String? = null,
  @SerialName("sort_order") val sortOrder: Int? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
data class ProductVariantDto(
  val id: String,
  @SerialName("product_id") val productId: String,
  @SerialName("size_code") val sizeCode: SizeCode,
  @SerialName("price_paise") val pricePaise: Long,
  @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class CreateVariantRequest(
  @SerialName("size_code") val sizeCode: SizeCode,
  @SerialName("price_paise") val pricePaise: Long,
  @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class UpdateVariantRequest(
  @SerialName("price_paise") val pricePaise: Long? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
data class CrustOptionDto(
  val id: String,
  val name: String,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class CreateCrustRequest(
  val name: String,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class UpdateCrustRequest(
  val name: String? = null,
  @SerialName("extra_price_paise") val extraPricePaise: Long? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
data class ToppingDto(
  val id: String,
  val name: String,
  @SerialName("is_veg") val isVeg: Boolean,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class CreateToppingRequest(
  val name: String,
  @SerialName("is_veg") val isVeg: Boolean = true,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class UpdateToppingRequest(
  val name: String? = null,
  @SerialName("is_veg") val isVeg: Boolean? = null,
  @SerialName("extra_price_paise") val extraPricePaise: Long? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
data class ProductImageDto(
  val id: String,
  @SerialName("product_id") val productId: String,
  @SerialName("object_key") val objectKey: String,
  @SerialName("public_url") val publicUrl: String,
  @SerialName("mime_type") val mimeType: String,
  @SerialName("size_bytes") val sizeBytes: Long,
  val status: ProductImageStatus,
  @SerialName("sort_order") val sortOrder: Int,
  @SerialName("created_at") val createdAt: String,
)

// Phase 8: New modifier DTOs
@Serializable
data class CheeseOptionDto(
  val id: String,
  @SerialName("product_id") val productId: String,
  val name: String,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_active") val isActive: Boolean,
  @SerialName("sort_order") val sortOrder: Int,
)

@Serializable
data class SauceOptionDto(
  val id: String,
  @SerialName("product_id") val productId: String,
  val name: String,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_active") val isActive: Boolean,
  @SerialName("sort_order") val sortOrder: Int,
)

@Serializable
data class StandardToppingDto(
  val id: String,
  @SerialName("product_id") val productId: String,
  val name: String,
  @SerialName("is_veg") val isVeg: Boolean,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_default") val isDefault: Boolean,
  @SerialName("is_active") val isActive: Boolean,
  @SerialName("sort_order") val sortOrder: Int,
)

@Serializable
data class HalfAndHalfConfigDto(
  val id: String,
  @SerialName("product_id") val productId: String,
  val name: String,
  @SerialName("left_topping_ids") val leftToppingIds: List<String> = emptyList(),
  @SerialName("right_topping_ids") val rightToppingIds: List<String> = emptyList(),
  @SerialName("half_and_half_surcharge_paise") val halfAndHalfSurchargePaise: Long = 0L,
  @SerialName("is_active") val isActive: Boolean = true,
  @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class ProductDto(
  val id: String,
  @SerialName("category_id") val categoryId: String,
  @SerialName("category_name") val categoryName: String,
  val name: String,
  val description: String,
  @SerialName("is_veg") val isVeg: Boolean,
  @SerialName("is_active") val isActive: Boolean,
  @SerialName("is_taxable") val isTaxable: Boolean,
  val variants: List<ProductVariantDto> = emptyList(),
  val images: List<ProductImageDto> = emptyList(),
  // Phase 8: New modifier arrays (optional/defaulted for backward compatibility)
  @SerialName("cheeses") val cheeses: List<CheeseOptionDto> = emptyList(),
  @SerialName("sauces") val sauces: List<SauceOptionDto> = emptyList(),
  @SerialName("standard_toppings") val standardToppings: List<StandardToppingDto> = emptyList(),
  @SerialName("half_and_half_configs") val halfAndHalfConfigs: List<HalfAndHalfConfigDto> = emptyList(),
)

@Serializable
data class CreateProductRequest(
  @SerialName("category_id") val categoryId: String,
  val name: String,
  val description: String = "",
  @SerialName("is_veg") val isVeg: Boolean = true,
  @SerialName("is_active") val isActive: Boolean = true,
  @SerialName("is_taxable") val isTaxable: Boolean = true,
  val variants: List<CreateVariantRequest>,
)

@Serializable
data class UpdateProductRequest(
  @SerialName("category_id") val categoryId: String? = null,
  val name: String? = null,
  val description: String? = null,
  @SerialName("is_veg") val isVeg: Boolean? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
  @SerialName("is_taxable") val isTaxable: Boolean? = null,
)

@Serializable
data class PublicMenuResponse(
  val categories: List<CategoryDto>,
  val products: List<ProductDto>,
  val crusts: List<CrustOptionDto>,
  val toppings: List<ToppingDto>,
  // Phase 8: global modifier arrays (optional/defaulted for old clients).
  val cheeses: List<CheeseOptionDto> = emptyList(),
  val sauces: List<SauceOptionDto> = emptyList(),
  @SerialName("standard_toppings") val standardToppings: List<StandardToppingDto> = emptyList(),
  @SerialName("half_and_half_configs") val halfAndHalfConfigs: List<HalfAndHalfConfigDto> = emptyList(),
  @SerialName("gst_bps") val gstBps: Int,
  @SerialName("store_name") val storeName: String,
  @SerialName("is_accepting_orders") val isAcceptingOrders: Boolean,
)

@Serializable
data class PresignProductImageRequest(
  @SerialName("mime_type") val mimeType: String,
  @SerialName("size_bytes") val sizeBytes: Long,
  @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class PresignProductImageResponse(
  @SerialName("image_id") val imageId: String,
  @SerialName("object_key") val objectKey: String,
  @SerialName("upload_url") val uploadUrl: String,
  val method: String = "PUT",
  @SerialName("required_headers") val requiredHeaders: Map<String, String> = emptyMap(),
  @SerialName("expires_in_seconds") val expiresInSeconds: Long,
  @SerialName("public_url") val publicUrl: String,
)

@Serializable
data class ConfirmProductImageRequest(
  @SerialName("image_id") val imageId: String,
)
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

  @SerialName("EXTRA_LARGE")
  EXTRA_LARGE,
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
data class PizzaSizeRuleDto(
  @SerialName("size_code") val sizeCode: SizeCode,
  @SerialName("display_name") val displayName: String,
  @SerialName("max_toppings") val maxToppings: Int = 8,
  @SerialName("max_extra_toppings") val maxExtraToppings: Int = 4,
  @SerialName("supports_half_and_half") val supportsHalfAndHalf: Boolean = true,
  @SerialName("ingredient_multiplier_bps") val ingredientMultiplierBps: Int = 10_000,
  @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class CheeseOptionDto(
  val id: String,
  val name: String,
  @SerialName("extra_price_paise") val extraPricePaise: Long = 0L,
  @SerialName("extra_cheese_price_paise") val extraCheesePricePaise: Long = 0L,
  @SerialName("supports_extra") val supportsExtra: Boolean = true,
  @SerialName("is_vegan") val isVegan: Boolean = false,
  @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class CreateCheeseRequest(
  val name: String,
  @SerialName("extra_price_paise") val extraPricePaise: Long = 0L,
  @SerialName("extra_cheese_price_paise") val extraCheesePricePaise: Long = 0L,
  @SerialName("supports_extra") val supportsExtra: Boolean = true,
  @SerialName("is_vegan") val isVegan: Boolean = false,
  @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class UpdateCheeseRequest(
  val name: String? = null,
  @SerialName("extra_price_paise") val extraPricePaise: Long? = null,
  @SerialName("extra_cheese_price_paise") val extraCheesePricePaise: Long? = null,
  @SerialName("supports_extra") val supportsExtra: Boolean? = null,
  @SerialName("is_vegan") val isVegan: Boolean? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
data class SauceOptionDto(
  val id: String,
  val name: String,
  @SerialName("extra_price_paise") val extraPricePaise: Long = 0L,
  @SerialName("extra_sauce_price_paise") val extraSaucePricePaise: Long = 0L,
  @SerialName("supports_extra") val supportsExtra: Boolean = true,
  @SerialName("is_vegan") val isVegan: Boolean = true,
  @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class CreateSauceRequest(
  val name: String,
  @SerialName("extra_price_paise") val extraPricePaise: Long = 0L,
  @SerialName("extra_sauce_price_paise") val extraSaucePricePaise: Long = 0L,
  @SerialName("supports_extra") val supportsExtra: Boolean = true,
  @SerialName("is_vegan") val isVegan: Boolean = true,
  @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class UpdateSauceRequest(
  val name: String? = null,
  @SerialName("extra_price_paise") val extraPricePaise: Long? = null,
  @SerialName("extra_sauce_price_paise") val extraSaucePricePaise: Long? = null,
  @SerialName("supports_extra") val supportsExtra: Boolean? = null,
  @SerialName("is_vegan") val isVegan: Boolean? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
)

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
  @SerialName("supports_half_and_half") val supportsHalfAndHalf: Boolean = true,
)

@Serializable
data class CreateCrustRequest(
  val name: String,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_active") val isActive: Boolean = true,
  @SerialName("supports_half_and_half") val supportsHalfAndHalf: Boolean = true,
)

@Serializable
data class UpdateCrustRequest(
  val name: String? = null,
  @SerialName("extra_price_paise") val extraPricePaise: Long? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
  @SerialName("supports_half_and_half") val supportsHalfAndHalf: Boolean? = null,
)

@Serializable
data class ToppingDto(
  val id: String,
  val name: String,
  @SerialName("is_veg") val isVeg: Boolean,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_active") val isActive: Boolean,
  @SerialName("max_quantity") val maxQuantity: Int = 2,
  @SerialName("supports_half_and_half") val supportsHalfAndHalf: Boolean = true,
)

@Serializable
data class CreateToppingRequest(
  val name: String,
  @SerialName("is_veg") val isVeg: Boolean = true,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_active") val isActive: Boolean = true,
  @SerialName("max_quantity") val maxQuantity: Int = 2,
  @SerialName("supports_half_and_half") val supportsHalfAndHalf: Boolean = true,
)

@Serializable
data class UpdateToppingRequest(
  val name: String? = null,
  @SerialName("is_veg") val isVeg: Boolean? = null,
  @SerialName("extra_price_paise") val extraPricePaise: Long? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
  @SerialName("max_quantity") val maxQuantity: Int? = null,
  @SerialName("supports_half_and_half") val supportsHalfAndHalf: Boolean? = null,
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
  @SerialName("is_customizable") val isCustomizable: Boolean = true,
  @SerialName("supports_half_and_half") val supportsHalfAndHalf: Boolean = true,
  @SerialName("default_cheese_id") val defaultCheeseId: String? = null,
  @SerialName("default_sauce_id") val defaultSauceId: String? = null,
  @SerialName("included_topping_ids") val includedToppingIds: List<String> = emptyList(),
  @SerialName("max_toppings_override") val maxToppingsOverride: Int? = null,
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
  @SerialName("is_customizable") val isCustomizable: Boolean = true,
  @SerialName("supports_half_and_half") val supportsHalfAndHalf: Boolean = true,
  @SerialName("default_cheese_id") val defaultCheeseId: String? = null,
  @SerialName("default_sauce_id") val defaultSauceId: String? = null,
  @SerialName("included_topping_ids") val includedToppingIds: List<String> = emptyList(),
  @SerialName("max_toppings_override") val maxToppingsOverride: Int? = null,
)

@Serializable
data class UpdateProductRequest(
  @SerialName("category_id") val categoryId: String? = null,
  val name: String? = null,
  val description: String? = null,
  @SerialName("is_veg") val isVeg: Boolean? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
  @SerialName("is_taxable") val isTaxable: Boolean? = null,
  @SerialName("is_customizable") val isCustomizable: Boolean? = null,
  @SerialName("supports_half_and_half") val supportsHalfAndHalf: Boolean? = null,
  @SerialName("default_cheese_id") val defaultCheeseId: String? = null,
  @SerialName("default_sauce_id") val defaultSauceId: String? = null,
  @SerialName("included_topping_ids") val includedToppingIds: List<String>? = null,
  @SerialName("max_toppings_override") val maxToppingsOverride: Int? = null,
)

@Serializable
data class PublicMenuResponse(
  val categories: List<CategoryDto>,
  val products: List<ProductDto>,
  val crusts: List<CrustOptionDto>,
  val toppings: List<ToppingDto>,
  @SerialName("gst_bps") val gstBps: Int,
  @SerialName("store_name") val storeName: String,
  @SerialName("is_accepting_orders") val isAcceptingOrders: Boolean,
  @SerialName("size_rules") val sizeRules: List<PizzaSizeRuleDto> = emptyList(),
  val cheeses: List<CheeseOptionDto> = emptyList(),
  val sauces: List<SauceOptionDto> = emptyList(),
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

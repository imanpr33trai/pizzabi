package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CartValidationIssueCode {
  @SerialName("PRODUCT_UNAVAILABLE")
  PRODUCT_UNAVAILABLE,

  @SerialName("VARIANT_UNAVAILABLE")
  VARIANT_UNAVAILABLE,

  @SerialName("CRUST_UNAVAILABLE")
  CRUST_UNAVAILABLE,

  @SerialName("TOPPING_UNAVAILABLE")
  TOPPING_UNAVAILABLE,

  @SerialName("INVALID_QUANTITY")
  INVALID_QUANTITY,

  @SerialName("VARIANT_PRODUCT_MISMATCH")
  VARIANT_PRODUCT_MISMATCH,

  // Phase 8: New validation issue codes per contract §5.3
  @SerialName("CHEESE_UNAVAILABLE")
  CHEESE_UNAVAILABLE,

  @SerialName("SAUCE_UNAVAILABLE")
  SAUCE_UNAVAILABLE,

  @SerialName("STANDARD_TOPPING_UNAVAILABLE")
  STANDARD_TOPPING_UNAVAILABLE,

  @SerialName("HALF_AND_HALF_UNAVAILABLE")
  HALF_AND_HALF_UNAVAILABLE,

  @SerialName("HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED")
  HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED,

  @SerialName("HALF_AND_HALF_LEFT_RIGHT_OVERLAP")
  HALF_AND_HALF_LEFT_RIGHT_OVERLAP,

  @SerialName("STANDARD_TOPPING_NOT_IN_PRODUCT")
  STANDARD_TOPPING_NOT_IN_PRODUCT,

  @SerialName("CHEESE_NOT_IN_PRODUCT")
  CHEESE_NOT_IN_PRODUCT,

  @SerialName("SAUCE_NOT_IN_PRODUCT")
  SAUCE_NOT_IN_PRODUCT,

  @SerialName("HALF_AND_HALF_CONFIG_NOT_IN_PRODUCT")
  HALF_AND_HALF_CONFIG_NOT_IN_PRODUCT,
}

@Serializable
data class CartValidationIssueDto(
  val code: CartValidationIssueCode,
  val message: String,
)

@Serializable
data class CartItemInputDto(
  val id: String? = null,
  @SerialName("product_id") val productId: String,
  @SerialName("variant_id") val variantId: String,
  @SerialName("crust_id") val crustId: String,
  @SerialName("topping_ids") val toppingIds: List<String> = emptyList(),
  val quantity: Int,
  @SerialName("client_unit_price_paise") val clientUnitPricePaise: Long? = null,
  // Phase 8: New modifier fields (all optional/defaulted for backward compatibility)
  @SerialName("cheese_id") val cheeseId: String? = null,
  @SerialName("sauce_id") val sauceId: String? = null,
  @SerialName("standard_topping_ids") val standardToppingIds: List<String> = emptyList(),
  @SerialName("half_and_half_config_id") val halfAndHalfConfigId: String? = null,
  @SerialName("half_and_half_left_topping_ids") val halfAndHalfLeftToppingIds: List<String> = emptyList(),
  @SerialName("half_and_half_right_topping_ids") val halfAndHalfRightToppingIds: List<String> = emptyList(),
)

@Serializable
data class ReplaceCartRequest(
  val items: List<CartItemInputDto> = emptyList(),
)

@Serializable
data class ValidateCartRequest(
  val items: List<CartItemInputDto> = emptyList(),
)

@Serializable
data class CartToppingViewDto(
  @SerialName("topping_id") val toppingId: String,
  val name: String,
  @SerialName("is_veg") val isVeg: Boolean,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_available") val isAvailable: Boolean,
)

// Phase 8: New view DTOs for resolved modifiers
@Serializable
data class CartCheeseViewDto(
  @SerialName("cheese_id") val cheeseId: String,
  val name: String,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_available") val isAvailable: Boolean,
)

@Serializable
data class CartSauceViewDto(
  @SerialName("sauce_id") val sauceId: String,
  val name: String,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_available") val isAvailable: Boolean,
)

@Serializable
data class CartStandardToppingViewDto(
  @SerialName("standard_topping_id") val standardToppingId: String,
  val name: String,
  @SerialName("is_veg") val isVeg: Boolean,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_default") val isDefault: Boolean,
  @SerialName("is_removed") val isRemoved: Boolean,
  @SerialName("is_available") val isAvailable: Boolean,
)

@Serializable
data class CartHalfAndHalfViewDto(
  @SerialName("half_and_half_config_id") val halfAndHalfConfigId: String,
  val name: String,
  @SerialName("left_topping_ids") val leftToppingIds: List<String>,
  @SerialName("right_topping_ids") val rightToppingIds: List<String>,
  @SerialName("left_toppings") val leftToppings: List<CartToppingViewDto> = emptyList(),
  @SerialName("right_toppings") val rightToppings: List<CartToppingViewDto> = emptyList(),
  @SerialName("half_and_half_surcharge_paise") val halfAndHalfSurchargePaise: Long,
  @SerialName("is_available") val isAvailable: Boolean,
)

@Serializable
data class CartItemViewDto(
  val id: String,
  @SerialName("product_id") val productId: String,
  @SerialName("product_name") val productName: String,
  @SerialName("is_veg") val isVeg: Boolean,
  @SerialName("image_url") val imageUrl: String? = null,
  @SerialName("variant_id") val variantId: String,
  @SerialName("size_code") val sizeCode: SizeCode? = null,
  @SerialName("base_price_paise") val basePricePaise: Long,
  @SerialName("crust_id") val crustId: String,
  @SerialName("crust_name") val crustName: String,
  @SerialName("crust_extra_price_paise") val crustExtraPricePaise: Long,
  val toppings: List<CartToppingViewDto> = emptyList(),
  val quantity: Int,
  @SerialName("unit_price_paise") val unitPricePaise: Long,
  @SerialName("line_total_paise") val lineTotalPaise: Long,
  @SerialName("is_available") val isAvailable: Boolean,
  val issues: List<CartValidationIssueDto> = emptyList(),
  // Phase 8: New resolved modifier views
  @SerialName("cheese") val cheese: CartCheeseViewDto? = null,
  @SerialName("sauce") val sauce: CartSauceViewDto? = null,
  @SerialName("standard_toppings") val standardToppings: List<CartStandardToppingViewDto> = emptyList(),
  @SerialName("half_and_half") val halfAndHalf: CartHalfAndHalfViewDto? = null,
)

@Serializable
data class CartResponseDto(
  @SerialName("cart_id") val cartId: String? = null,
  val items: List<CartItemViewDto> = emptyList(),
  @SerialName("subtotal_paise") val subtotalPaise: Long = 0L,
  @SerialName("total_item_count") val totalItemCount: Int = 0,
  @SerialName("all_available") val allAvailable: Boolean = true,
  @SerialName("unavailable_count") val unavailableCount: Int = 0,
  @SerialName("updated_at") val updatedAt: String,
)
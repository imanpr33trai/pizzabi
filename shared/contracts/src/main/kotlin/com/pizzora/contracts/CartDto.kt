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

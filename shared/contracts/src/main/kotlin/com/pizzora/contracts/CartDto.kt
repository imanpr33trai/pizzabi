package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ToppingPlacement {
  @SerialName("WHOLE")
  WHOLE,

  @SerialName("LEFT")
  LEFT,

  @SerialName("RIGHT")
  RIGHT,
}

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

  @SerialName("SIZE_UNAVAILABLE")
  SIZE_UNAVAILABLE,

  @SerialName("CHEESE_UNAVAILABLE")
  CHEESE_UNAVAILABLE,

  @SerialName("SAUCE_UNAVAILABLE")
  SAUCE_UNAVAILABLE,

  @SerialName("EXTRA_CHEESE_UNAVAILABLE")
  EXTRA_CHEESE_UNAVAILABLE,

  @SerialName("EXTRA_SAUCE_UNAVAILABLE")
  EXTRA_SAUCE_UNAVAILABLE,

  @SerialName("HALF_AND_HALF_UNSUPPORTED")
  HALF_AND_HALF_UNSUPPORTED,

  @SerialName("RIGHT_HALF_INVALID")
  RIGHT_HALF_INVALID,

  @SerialName("TOPPING_PLACEMENT_INVALID")
  TOPPING_PLACEMENT_INVALID,

  @SerialName("TOPPING_LIMIT_EXCEEDED")
  TOPPING_LIMIT_EXCEEDED,

  @SerialName("EXTRA_TOPPING_LIMIT_EXCEEDED")
  EXTRA_TOPPING_LIMIT_EXCEEDED,

  @SerialName("DUPLICATE_SELECTION")
  DUPLICATE_SELECTION,

  @SerialName("INCLUDED_TOPPING_INVALID")
  INCLUDED_TOPPING_INVALID,
}

@Serializable
data class CartValidationIssueDto(
  val code: CartValidationIssueCode,
  val message: String,
)

@Serializable
data class CartToppingInputDto(
  @SerialName("topping_id") val toppingId: String,
  val placement: ToppingPlacement = ToppingPlacement.WHOLE,
  val quantity: Int = 1,
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
  @SerialName("cheese_id") val cheeseId: String? = null,
  @SerialName("extra_cheese") val extraCheese: Boolean = false,
  @SerialName("sauce_id") val sauceId: String? = null,
  @SerialName("extra_sauce") val extraSauce: Boolean = false,
  @SerialName("is_half_and_half") val isHalfAndHalf: Boolean = false,
  @SerialName("right_product_id") val rightProductId: String? = null,
  @SerialName("right_variant_id") val rightVariantId: String? = null,
  @SerialName("right_sauce_id") val rightSauceId: String? = null,
  @SerialName("removed_included_topping_ids") val removedIncludedToppingIds: List<String> = emptyList(),
  @SerialName("topping_selections") val toppingSelections: List<CartToppingInputDto> = emptyList(),
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
data class PizzaPriceBreakdownDto(
  @SerialName("base_price_paise") val basePricePaise: Long = 0L,
  @SerialName("half_and_half_surcharge_paise") val halfAndHalfSurchargePaise: Long = 0L,
  @SerialName("crust_price_paise") val crustPricePaise: Long = 0L,
  @SerialName("cheese_price_paise") val cheesePricePaise: Long = 0L,
  @SerialName("extra_cheese_price_paise") val extraCheesePricePaise: Long = 0L,
  @SerialName("sauce_price_paise") val saucePricePaise: Long = 0L,
  @SerialName("extra_sauce_price_paise") val extraSaucePricePaise: Long = 0L,
  @SerialName("toppings_price_paise") val toppingsPricePaise: Long = 0L,
  @SerialName("unit_price_paise") val unitPricePaise: Long = 0L,
  @SerialName("line_total_paise") val lineTotalPaise: Long = 0L,
)

@Serializable
data class CartItemCustomizationSummaryDto(
  @SerialName("is_half_and_half") val isHalfAndHalf: Boolean = false,
  @SerialName("right_product_id") val rightProductId: String? = null,
  @SerialName("right_product_name") val rightProductName: String? = null,
  @SerialName("right_variant_id") val rightVariantId: String? = null,
  @SerialName("cheese_id") val cheeseId: String? = null,
  @SerialName("cheese_name") val cheeseName: String? = null,
  @SerialName("extra_cheese") val extraCheese: Boolean = false,
  @SerialName("sauce_id") val sauceId: String? = null,
  @SerialName("sauce_name") val sauceName: String? = null,
  @SerialName("right_sauce_id") val rightSauceId: String? = null,
  @SerialName("right_sauce_name") val rightSauceName: String? = null,
  @SerialName("extra_sauce") val extraSauce: Boolean = false,
  @SerialName("removed_included_toppings") val removedIncludedToppings: List<String> = emptyList(),
  @SerialName("configuration_signature") val configurationSignature: String = "",
)

@Serializable
data class CartToppingViewDto(
  @SerialName("topping_id") val toppingId: String,
  val name: String,
  @SerialName("is_veg") val isVeg: Boolean,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_available") val isAvailable: Boolean,
  val placement: ToppingPlacement = ToppingPlacement.WHOLE,
  val quantity: Int = 1,
  @SerialName("is_included") val isIncluded: Boolean = false,
  @SerialName("unit_price_paise") val unitPricePaise: Long = extraPricePaise,
  @SerialName("total_price_paise") val totalPricePaise: Long = extraPricePaise,
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
  val customization: CartItemCustomizationSummaryDto? = null,
  @SerialName("price_breakdown") val priceBreakdown: PizzaPriceBreakdownDto? = null,
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

@Serializable
data class PizzaBuilderQuoteRequest(
  val item: CartItemInputDto,
)

@Serializable
data class PizzaBuilderQuoteResponse(
  @SerialName("is_valid") val isValid: Boolean,
  @SerialName("unit_price_paise") val unitPricePaise: Long,
  @SerialName("line_total_paise") val lineTotalPaise: Long,
  @SerialName("price_breakdown") val priceBreakdown: PizzaPriceBreakdownDto,
  @SerialName("configuration_signature") val configurationSignature: String,
  val customization: CartItemCustomizationSummaryDto,
  val toppings: List<CartToppingViewDto> = emptyList(),
  val issues: List<CartValidationIssueDto> = emptyList(),
)

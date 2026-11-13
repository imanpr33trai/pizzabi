package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CouponDto(
  val id: String,
  val code: String,
  @SerialName("discount_type") val discountType: DiscountType,
  @SerialName("discount_value") val discountValue: Long,
  @SerialName("max_discount_paise") val maxDiscountPaise: Long? = null,
  @SerialName("min_order_paise") val minOrderPaise: Long,
  @SerialName("max_uses") val maxUses: Int? = null,
  @SerialName("max_uses_per_user") val maxUsesPerUser: Int,
  @SerialName("usage_count") val usageCount: Int,
  @SerialName("starts_at") val startsAt: String,
  @SerialName("ends_at") val endsAt: String,
  @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class CreateCouponRequest(
  val code: String,
  @SerialName("discount_type") val discountType: DiscountType,
  @SerialName("discount_value") val discountValue: Long,
  @SerialName("max_discount_paise") val maxDiscountPaise: Long? = null,
  @SerialName("min_order_paise") val minOrderPaise: Long = 0L,
  @SerialName("max_uses") val maxUses: Int? = null,
  @SerialName("max_uses_per_user") val maxUsesPerUser: Int = 1,
  @SerialName("starts_at") val startsAt: String,
  @SerialName("ends_at") val endsAt: String,
  @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class UpdateCouponRequest(
  @SerialName("discount_type") val discountType: DiscountType? = null,
  @SerialName("discount_value") val discountValue: Long? = null,
  @SerialName("max_discount_paise") val maxDiscountPaise: Long? = null,
  @SerialName("clear_max_discount") val clearMaxDiscount: Boolean = false,
  @SerialName("min_order_paise") val minOrderPaise: Long? = null,
  @SerialName("max_uses") val maxUses: Int? = null,
  @SerialName("clear_max_uses") val clearMaxUses: Boolean = false,
  @SerialName("max_uses_per_user") val maxUsesPerUser: Int? = null,
  @SerialName("starts_at") val startsAt: String? = null,
  @SerialName("ends_at") val endsAt: String? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
data class CustomerCouponDto(
  val id: String,
  val code: String,
  @SerialName("discount_type") val discountType: DiscountType,
  @SerialName("discount_value") val discountValue: Long,
  @SerialName("max_discount_paise") val maxDiscountPaise: Long? = null,
  @SerialName("min_order_paise") val minOrderPaise: Long,
  @SerialName("max_uses") val maxUses: Int? = null,
  @SerialName("max_uses_per_user") val maxUsesPerUser: Int,
  @SerialName("user_usage_count") val userUsageCount: Int,
  @SerialName("remaining_global_uses") val remainingGlobalUses: Int? = null,
  @SerialName("remaining_user_uses") val remainingUserUses: Int,
  @SerialName("starts_at") val startsAt: String,
  @SerialName("ends_at") val endsAt: String,
  @SerialName("is_eligible") val isEligible: Boolean = true,
  @SerialName("ineligible_reason") val ineligibleReason: String? = null,
  @SerialName("estimated_discount_paise") val estimatedDiscountPaise: Long = 0L,
)

@Serializable
data class AvailableCouponsResponse(
  val coupons: List<CustomerCouponDto>,
  @SerialName("subtotal_paise") val subtotalPaise: Long? = null,
)

@Serializable
data class PricingQuoteRequest(
  val items: List<CartItemInputDto>,
  @SerialName("fulfillment_type") val fulfillmentType: FulfillmentType = FulfillmentType.DELIVERY,
  @SerialName("address_id") val addressId: String? = null,
  val pincode: String? = null,
  @SerialName("coupon_code") val couponCode: String? = null,
)

@Serializable
data class DeliveryZoneQuoteDto(
  val pincode: String,
  @SerialName("area_name") val areaName: String,
  @SerialName("min_order_paise") val minOrderPaise: Long,
  @SerialName("delivery_fee_paise") val deliveryFeePaise: Long,
  @SerialName("estimated_mins") val estimatedMins: Int,
)

@Serializable
data class AppliedCouponSummaryDto(
  val id: String,
  val code: String,
  @SerialName("discount_type") val discountType: DiscountType,
  @SerialName("discount_value") val discountValue: Long,
  @SerialName("discount_paise") val discountPaise: Long,
)

@Serializable
data class PriceBreakdownDto(
  @SerialName("subtotal_paise") val subtotalPaise: Long,
  @SerialName("taxable_subtotal_paise") val taxableSubtotalPaise: Long,
  @SerialName("discount_paise") val discountPaise: Long,
  @SerialName("taxable_amount_paise") val taxableAmountPaise: Long,
  @SerialName("delivery_fee_paise") val deliveryFeePaise: Long,
  @SerialName("gst_bps") val gstBps: Int,
  @SerialName("tax_paise") val taxPaise: Long,
  @SerialName("cgst_paise") val cgstPaise: Long,
  @SerialName("sgst_paise") val sgstPaise: Long,
  @SerialName("total_paise") val totalPaise: Long,
)

@Serializable
data class PricingQuoteResponse(
  val items: List<CartItemViewDto>,
  @SerialName("fulfillment_type") val fulfillmentType: FulfillmentType,
  val breakdown: PriceBreakdownDto,
  @SerialName("applied_coupon") val appliedCoupon: AppliedCouponSummaryDto? = null,
  @SerialName("delivery_zone") val deliveryZone: DeliveryZoneQuoteDto? = null,
  @SerialName("is_accepting_orders") val isAcceptingOrders: Boolean = true,
  @SerialName("gst_bps") val gstBps: Int,
)

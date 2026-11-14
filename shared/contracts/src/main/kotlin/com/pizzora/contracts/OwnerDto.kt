package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublicConfigResponse(
  @SerialName("store_name") val storeName: String,
  @SerialName("is_accepting_orders") val isAcceptingOrders: Boolean,
  @SerialName("gst_bps") val gstBps: Int,
  @SerialName("default_prep_mins") val defaultPrepMins: Int,
  val timezone: String = "Asia/Kolkata",
)

@Serializable
data class RestaurantSettingsDto(
  @SerialName("store_name") val storeName: String,
  @SerialName("is_accepting_orders") val isAcceptingOrders: Boolean,
  @SerialName("gst_bps") val gstBps: Int,
  @SerialName("default_prep_mins") val defaultPrepMins: Int,
  val timezone: String = "Asia/Kolkata",
  @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class UpdateRestaurantSettingsRequest(
  @SerialName("store_name") val storeName: String? = null,
  @SerialName("is_accepting_orders") val isAcceptingOrders: Boolean? = null,
  @SerialName("gst_bps") val gstBps: Int? = null,
  @SerialName("default_prep_mins") val defaultPrepMins: Int? = null,
)

@Serializable
data class OwnerDashboardResponse(
  @SerialName("today_revenue_paise") val todayRevenuePaise: Long,
  @SerialName("today_order_count") val todayOrderCount: Int,
  @SerialName("active_order_count") val activeOrderCount: Int,
  @SerialName("pending_payment_count") val pendingPaymentCount: Int,
  @SerialName("completed_today_count") val completedTodayCount: Int,
  @SerialName("low_stock_count") val lowStockCount: Int,
  @SerialName("low_stock_items") val lowStockItems: List<InventoryItemDto> = emptyList(),
  @SerialName("recent_orders") val recentOrders: List<OrderSummaryDto> = emptyList(),
  val settings: RestaurantSettingsDto,
  @SerialName("server_now") val serverNow: String,
)

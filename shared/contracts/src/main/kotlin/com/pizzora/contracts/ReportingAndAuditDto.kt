package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DailySalesBucketDto(
  val date: String,
  @SerialName("order_count") val orderCount: Int,
  @SerialName("gross_sales_paise") val grossSalesPaise: Long,
  @SerialName("refunded_paise") val refundedPaise: Long,
  @SerialName("net_sales_paise") val netSalesPaise: Long,
  @SerialName("tax_paise") val taxPaise: Long,
)

@Serializable
data class TopSellingProductDto(
  @SerialName("product_id") val productId: String,
  @SerialName("product_name") val productName: String,
  @SerialName("quantity_sold") val quantitySold: Int,
  @SerialName("gross_revenue_paise") val grossRevenuePaise: Long,
)

@Serializable
data class CouponRoiDto(
  @SerialName("coupon_id") val couponId: String,
  val code: String,
  @SerialName("redemption_count") val redemptionCount: Int,
  @SerialName("total_discount_paise") val totalDiscountPaise: Long,
  @SerialName("gross_order_revenue_paise") val grossOrderRevenuePaise: Long,
  @SerialName("net_order_revenue_paise") val netOrderRevenuePaise: Long,
)

@Serializable
data class SalesReportResponse(
  @SerialName("start_date") val startDate: String,
  @SerialName("end_date") val endDate: String,
  val timezone: String = "Asia/Kolkata",
  @SerialName("order_count") val orderCount: Int,
  @SerialName("cancelled_order_count") val cancelledOrderCount: Int,
  @SerialName("subtotal_paise") val subtotalPaise: Long,
  @SerialName("discount_paise") val discountPaise: Long,
  @SerialName("delivery_fee_paise") val deliveryFeePaise: Long,
  @SerialName("tax_paise") val taxPaise: Long,
  @SerialName("cgst_paise") val cgstPaise: Long,
  @SerialName("sgst_paise") val sgstPaise: Long,
  @SerialName("gross_sales_paise") val grossSalesPaise: Long,
  @SerialName("refunded_paise") val refundedPaise: Long,
  @SerialName("pending_refunds_paise") val pendingRefundsPaise: Long,
  @SerialName("net_sales_paise") val netSalesPaise: Long,
  @SerialName("daily_breakdown") val dailyBreakdown: List<DailySalesBucketDto> = emptyList(),
  @SerialName("top_products") val topProducts: List<TopSellingProductDto> = emptyList(),
  @SerialName("coupon_performance") val couponPerformance: List<CouponRoiDto> = emptyList(),
  @SerialName("server_now") val serverNow: String,
)

@Serializable
data class OperationalStockMovementDto(
  @SerialName("ingredient_id") val ingredientId: String,
  @SerialName("ingredient_name") val ingredientName: String,
  val unit: IngredientUnit,
  @SerialName("restocked_qty") val restockedQty: Long,
  @SerialName("consumed_qty") val consumedQty: Long,
  @SerialName("restored_qty") val restoredQty: Long,
  @SerialName("wastage_qty") val wastageQty: Long,
)

@Serializable
data class OperationsReportResponse(
  @SerialName("start_date") val startDate: String,
  @SerialName("end_date") val endDate: String,
  val timezone: String = "Asia/Kolkata",
  @SerialName("prep_sla_minutes") val prepSlaMinutes: Int,
  @SerialName("total_orders") val totalOrders: Int,
  @SerialName("completed_orders") val completedOrders: Int,
  @SerialName("cancelled_orders") val cancelledOrders: Int,
  @SerialName("expired_orders") val expiredOrders: Int,
  @SerialName("delivery_orders") val deliveryOrders: Int,
  @SerialName("pickup_orders") val pickupOrders: Int,
  @SerialName("scheduled_orders") val scheduledOrders: Int,
  @SerialName("measured_prep_orders") val measuredPrepOrders: Int,
  @SerialName("avg_prep_minutes") val avgPrepMinutes: Double,
  @SerialName("max_prep_minutes") val maxPrepMinutes: Double,
  @SerialName("avg_completion_minutes") val avgCompletionMinutes: Double,
  @SerialName("sla_breached_count") val slaBreachedCount: Int,
  @SerialName("sla_compliance_percent") val slaCompliancePercent: Double,
  @SerialName("restocked_units") val restockedUnits: Long,
  @SerialName("consumed_units") val consumedUnits: Long,
  @SerialName("restored_units") val restoredUnits: Long,
  @SerialName("wastage_units") val wastageUnits: Long,
  @SerialName("stock_movements") val stockMovements: List<OperationalStockMovementDto> = emptyList(),
  @SerialName("low_stock_items") val lowStockItems: List<InventoryItemDto> = emptyList(),
  @SerialName("server_now") val serverNow: String,
)

@Serializable
data class AuditLogEntryDto(
  val id: String,
  @SerialName("actor_user_id") val actorUserId: String? = null,
  @SerialName("actor_email") val actorEmail: String? = null,
  @SerialName("actor_name") val actorName: String? = null,
  @SerialName("actor_role") val actorRole: String,
  val action: String,
  @SerialName("entity_type") val entityType: String,
  @SerialName("entity_id") val entityId: String,
  @SerialName("before_json") val beforeJson: String? = null,
  @SerialName("after_json") val afterJson: String? = null,
  @SerialName("correlation_id") val correlationId: String,
  @SerialName("ip_address") val ipAddress: String? = null,
  @SerialName("created_at") val createdAt: String,
)

@Serializable
data class AuditLogsResponse(
  val entries: List<AuditLogEntryDto> = emptyList(),
  @SerialName("next_cursor") val nextCursor: String? = null,
  @SerialName("server_now") val serverNow: String,
)

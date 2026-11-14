package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KdsTicketItemDto(
  val id: String,
  @SerialName("product_id") val productId: String,
  @SerialName("product_name") val productName: String,
  @SerialName("variant_size") val variantSize: String,
  @SerialName("crust_name") val crustName: String,
  val toppings: List<String> = emptyList(),
  val quantity: Int,
)

@Serializable
data class KdsTicketDto(
  val id: String,
  @SerialName("order_number") val orderNumber: String,
  @SerialName("customer_name") val customerName: String,
  @SerialName("customer_phone") val customerPhone: String? = null,
  @SerialName("fulfillment_type") val fulfillmentType: FulfillmentType,
  @SerialName("schedule_type") val scheduleType: ScheduleType,
  val status: OrderStatus,
  val version: Long,
  @SerialName("is_priority") val isPriority: Boolean,
  @SerialName("payment_method") val paymentMethod: PaymentMethod,
  @SerialName("payment_status") val paymentStatus: PaymentStatus,
  @SerialName("total_paise") val totalPaise: Long,
  @SerialName("delivery_area") val deliveryArea: String? = null,
  @SerialName("accepted_at") val acceptedAt: String? = null,
  @SerialName("prep_started_at") val prepStartedAt: String? = null,
  @SerialName("ready_at") val readyAt: String? = null,
  @SerialName("completed_at") val completedAt: String? = null,
  @SerialName("created_at") val createdAt: String,
  val items: List<KdsTicketItemDto>,
)

@Serializable
data class KdsTicketsResponse(
  val tickets: List<KdsTicketDto>,
  @SerialName("server_now") val serverNow: String,
  @SerialName("prep_sla_minutes") val prepSlaMinutes: Int,
  @SerialName("priority_count") val priorityCount: Int = 0,
)

@Serializable
data class KdsActionRequest(
  val reason: String? = null,
)

@Serializable
data class UpdateOrderPriorityRequest(
  @SerialName("is_priority") val isPriority: Boolean,
  val reason: String? = null,
)

@Serializable
data class KdsTicketActionResponse(
  val ticket: KdsTicketDto,
  @SerialName("server_now") val serverNow: String,
  @SerialName("prep_sla_minutes") val prepSlaMinutes: Int,
)

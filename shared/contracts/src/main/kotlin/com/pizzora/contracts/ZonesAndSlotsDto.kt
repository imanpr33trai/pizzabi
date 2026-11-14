package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeliveryZoneDto(
  val id: String,
  val pincode: String,
  @SerialName("area_name") val areaName: String,
  @SerialName("min_order_paise") val minOrderPaise: Long,
  @SerialName("delivery_fee_paise") val deliveryFeePaise: Long,
  @SerialName("estimated_mins") val estimatedMins: Int,
  @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class DeliveryZoneCheckResponse(
  val pincode: String,
  @SerialName("is_serviceable") val isServiceable: Boolean,
  val zone: DeliveryZoneDto? = null,
  val message: String,
)

@Serializable
data class CreateDeliveryZoneRequest(
  val pincode: String,
  @SerialName("area_name") val areaName: String,
  @SerialName("min_order_paise") val minOrderPaise: Long,
  @SerialName("delivery_fee_paise") val deliveryFeePaise: Long,
  @SerialName("estimated_mins") val estimatedMins: Int,
  @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class UpdateDeliveryZoneRequest(
  @SerialName("area_name") val areaName: String? = null,
  @SerialName("min_order_paise") val minOrderPaise: Long? = null,
  @SerialName("delivery_fee_paise") val deliveryFeePaise: Long? = null,
  @SerialName("estimated_mins") val estimatedMins: Int? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
data class ScheduledSlotDto(
  val id: String,
  @SerialName("slot_date") val slotDate: String,
  @SerialName("fulfillment_type") val fulfillmentType: FulfillmentType,
  @SerialName("start_time_ist") val startTimeIst: String,
  @SerialName("end_time_ist") val endTimeIst: String,
  @SerialName("cutoff_at") val cutoffAt: String,
  @SerialName("max_capacity") val maxCapacity: Int,
  @SerialName("reserved_count") val reservedCount: Int,
  @SerialName("remaining_capacity") val remainingCapacity: Int,
  @SerialName("is_active") val isActive: Boolean,
  @SerialName("is_bookable") val isBookable: Boolean,
  @SerialName("unavailable_reason") val unavailableReason: String? = null,
)

@Serializable
data class AvailableSlotsResponse(
  val date: String,
  @SerialName("fulfillment_type") val fulfillmentType: FulfillmentType,
  val timezone: String = "Asia/Kolkata",
  @SerialName("server_now") val serverNow: String,
  val slots: List<ScheduledSlotDto>,
)

@Serializable
data class CreateScheduledSlotRequest(
  @SerialName("slot_date") val slotDate: String,
  @SerialName("fulfillment_type") val fulfillmentType: FulfillmentType,
  @SerialName("start_time_ist") val startTimeIst: String,
  @SerialName("end_time_ist") val endTimeIst: String,
  @SerialName("cutoff_at") val cutoffAt: String? = null,
  @SerialName("cutoff_minutes_before_start") val cutoffMinutesBeforeStart: Int? = null,
  @SerialName("max_capacity") val maxCapacity: Int,
  @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class UpdateScheduledSlotRequest(
  @SerialName("cutoff_at") val cutoffAt: String? = null,
  @SerialName("cutoff_minutes_before_start") val cutoffMinutesBeforeStart: Int? = null,
  @SerialName("max_capacity") val maxCapacity: Int? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
)

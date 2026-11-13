package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InventoryItemDto(
  @SerialName("ingredient_id") val ingredientId: String,
  val name: String,
  val unit: IngredientUnit,
  @SerialName("low_stock_threshold") val lowStockThreshold: Long,
  @SerialName("is_active") val isActive: Boolean,
  @SerialName("on_hand_qty") val onHandQty: Long,
  @SerialName("reserved_qty") val reservedQty: Long,
  @SerialName("available_qty") val availableQty: Long,
  @SerialName("is_low_stock") val isLowStock: Boolean,
  @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class CreateIngredientRequest(
  val name: String,
  val unit: IngredientUnit,
  @SerialName("low_stock_threshold") val lowStockThreshold: Long = 0L,
  @SerialName("initial_stock_qty") val initialStockQty: Long = 0L,
  @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class UpdateIngredientRequest(
  val name: String? = null,
  val unit: IngredientUnit? = null,
  @SerialName("low_stock_threshold") val lowStockThreshold: Long? = null,
  @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
data class AdjustInventoryRequest(
  @SerialName("ingredient_id") val ingredientId: String,
  @SerialName("operation_id") val operationId: String,
  @SerialName("delta_qty") val deltaQty: Long,
  val reason: InventoryLedgerReason = InventoryLedgerReason.MANUAL_ADJUST,
)

@Serializable
data class InventoryLedgerEntryDto(
  val id: String,
  @SerialName("ingredient_id") val ingredientId: String,
  @SerialName("ingredient_name") val ingredientName: String,
  val unit: IngredientUnit,
  @SerialName("order_id") val orderId: String? = null,
  @SerialName("operation_id") val operationId: String,
  @SerialName("delta_on_hand") val deltaOnHand: Long,
  @SerialName("delta_reserved") val deltaReserved: Long,
  val reason: InventoryLedgerReason,
  @SerialName("actor_user_id") val actorUserId: String? = null,
  @SerialName("created_at") val createdAt: String,
)

@Serializable
data class RecipeIngredientLineRequest(
  @SerialName("ingredient_id") val ingredientId: String,
  val quantity: Int,
)

@Serializable
data class RecipeIngredientLineDto(
  val id: String,
  @SerialName("ingredient_id") val ingredientId: String,
  @SerialName("ingredient_name") val ingredientName: String,
  val unit: IngredientUnit,
  val quantity: Int,
)

@Serializable
data class RecipeBindingDto(
  @SerialName("target_type") val targetType: RecipeTargetType,
  @SerialName("target_id") val targetId: String,
  @SerialName("target_label") val targetLabel: String,
  val ingredients: List<RecipeIngredientLineDto> = emptyList(),
)

@Serializable
data class RecipesOverviewResponse(
  val variants: List<RecipeBindingDto> = emptyList(),
  val crusts: List<RecipeBindingDto> = emptyList(),
  val toppings: List<RecipeBindingDto> = emptyList(),
)

@Serializable
data class PutRecipeRequest(
  @SerialName("target_type") val targetType: RecipeTargetType,
  @SerialName("target_id") val targetId: String,
  val ingredients: List<RecipeIngredientLineRequest>,
)

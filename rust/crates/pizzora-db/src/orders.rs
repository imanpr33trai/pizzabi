use chrono::{DateTime, Utc};
use pizzora_core::{
    FulfillmentType, OrderStateMachine, OrderStatus, PaymentAttemptStatus, PaymentMethod,
    PaymentStatus, ScheduleType, UserRole,
};
use serde::{Deserialize, Serialize};
use sqlx::{PgConnection, PgPool, Row as _};
use tracing::warn;
use uuid::Uuid;

use crate::audit::{record_audit_log, AuditContext};
use crate::coupons::CouponService;
use crate::error::{new_uuid_v4, DbError};
use crate::inventory::InventoryService;
use crate::outbox::{OutboxRepository, EVENT_REFUND_REQUESTED};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeliveryAddressSnapshotDto {
    #[serde(default)]
    pub address_id: String,
    pub label: String,
    pub line1: String,
    #[serde(default)]
    pub line2: Option<String>,
    #[serde(default)]
    pub landmark: Option<String>,
    pub pincode: String,
    pub city: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OrderSummaryDto {
    pub id: String,
    pub order_number: String,
    pub fulfillment_type: FulfillmentType,
    pub schedule_type: ScheduleType,
    pub status: OrderStatus,
    pub version: i64,
    pub total_paise: i64,
    pub item_count: i32,
    pub payment_method: PaymentMethod,
    pub payment_status: PaymentStatus,
    pub is_priority: bool,
    pub created_at: String,
    #[serde(default)]
    pub expires_at: Option<String>,
    // Phase 8: Item preview summary for list view
    #[serde(default)]
    pub item_preview_summary: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OrderListResponse {
    pub orders: Vec<OrderSummaryDto>,
}

// Phase 8: Extended OrderItemDto with all modifier IDs, snapshots, and preview data
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OrderItemDto {
    pub id: String,
    pub product_id: String,
    pub product_name: String,
    pub variant_size: String,
    pub crust_name: String,
    pub toppings: Vec<String>,
    pub unit_price_paise: i64,
    pub quantity: i32,
    pub line_total_paise: i64,
    // Phase 8: New modifier ID fields (nullable for historical orders)
    #[serde(default)]
    pub variant_id: Option<String>,
    #[serde(default)]
    pub crust_id: Option<String>,
    #[serde(default)]
    pub cheese_id: Option<String>,
    #[serde(default)]
    pub sauce_id: Option<String>,
    #[serde(default)]
    pub topping_ids: Vec<String>,
    #[serde(default)]
    pub standard_topping_ids: Vec<String>,
    #[serde(default)]
    pub standard_topping_removed_ids: Vec<String>,
    #[serde(default)]
    pub half_and_half_config_id: Option<String>,
    #[serde(default)]
    pub half_and_half_left_topping_ids: Vec<String>,
    #[serde(default)]
    pub half_and_half_right_topping_ids: Vec<String>,
    #[serde(default)]
    pub modifiers_snapshot: serde_json::Map<String, serde_json::Value>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OrderStatusHistoryDto {
    pub id: String,
    #[serde(default)]
    pub from_status: Option<OrderStatus>,
    pub to_status: OrderStatus,
    #[serde(default)]
    pub changed_by: Option<String>,
    #[serde(default)]
    pub reason: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PaymentAttemptSummaryDto {
    pub id: String,
    pub attempt_number: i32,
    pub method: PaymentMethod,
    pub status: PaymentAttemptStatus,
    pub amount_paise: i64,
    pub idempotency_receipt: String,
    #[serde(default)]
    pub gateway_order_id: Option<String>,
    #[serde(default)]
    pub gateway_payment_id: Option<String>,
    #[serde(default)]
    pub failure_code: Option<String>,
    #[serde(default)]
    pub failure_reason: Option<String>,
    #[serde(default)]
    pub expires_at: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PaymentSummaryDto {
    pub id: String,
    pub method: PaymentMethod,
    pub status: PaymentStatus,
    pub amount_paise: i64,
    pub captured_paise: i64,
    pub refunded_paise: i64,
    pub currency: String,
    #[serde(default)]
    pub latest_attempt: Option<PaymentAttemptSummaryDto>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OrderDetailDto {
    pub id: String,
    pub order_number: String,
    pub customer_id: String,
    pub fulfillment_type: FulfillmentType,
    pub schedule_type: ScheduleType,
    #[serde(default)]
    pub scheduled_slot_id: Option<String>,
    #[serde(default)]
    pub delivery_address: Option<DeliveryAddressSnapshotDto>,
    pub status: OrderStatus,
    pub version: i64,
    pub subtotal_paise: i64,
    pub discount_paise: i64,
    pub delivery_fee_paise: i64,
    pub tax_paise: i64,
    pub cgst_paise: i64,
    pub sgst_paise: i64,
    pub total_paise: i64,
    pub gst_bps_snapshot: i32,
    #[serde(default)]
    pub coupon_code_snapshot: Option<String>,
    pub is_priority: bool,
    #[serde(default)]
    pub accepted_at: Option<String>,
    #[serde(default)]
    pub prep_started_at: Option<String>,
    #[serde(default)]
    pub ready_at: Option<String>,
    #[serde(default)]
    pub completed_at: Option<String>,
    #[serde(default)]
    pub expires_at: Option<String>,
    pub created_at: String,
    pub items: Vec<OrderItemDto>,
    pub status_history: Vec<OrderStatusHistoryDto>,
    pub payment: PaymentSummaryDto,
}

#[derive(Clone)]
pub struct OrderService {
    pool: PgPool,
}

impl OrderService {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub fn pool(&self) -> &PgPool {
        &self.pool
    }

    pub async fn list_customer_orders(
        &self,
        customer_id: Uuid,
        limit: i64,
    ) -> Result<OrderListResponse, DbError> {
        let clamped_limit = limit.clamp(1, 100);
        let rows = sqlx::query(
            r#"
            SELECT o.id, o.order_number, o.fulfillment_type, o.schedule_type, o.status, o.version,
                   o.total_paise, o.is_priority, o.created_at, o.expires_at,
                   p.method AS payment_method, p.status AS payment_status,
                   COALESCE((SELECT SUM(oi.quantity)::int FROM order_items oi WHERE oi.order_id = o.id), 0) AS item_count
            FROM orders o
            JOIN payments p ON p.order_id = o.id
            WHERE o.customer_id = $1
            ORDER BY o.created_at DESC, o.id DESC
            LIMIT $2
            "#,
        )
        .bind(customer_id)
        .bind(clamped_limit)
        .fetch_all(&self.pool)
        .await?;

        let mut orders = Vec::with_capacity(rows.len());
        for row in rows {
            let id: Uuid = row.try_get("id")?;
            let fulfillment_raw: String = row.try_get("fulfillment_type")?;
            let schedule_raw: String = row.try_get("schedule_type")?;
            let status_raw: String = row.try_get("status")?;
            let method_raw: String = row.try_get("payment_method")?;
            let pay_status_raw: String = row.try_get("payment_status")?;
            let created_at: DateTime<Utc> = row.try_get("created_at")?;
            let expires_at: Option<DateTime<Utc>> = row.try_get("expires_at")?;

            // Phase 8: Build item preview summary from order_items
            let preview = build_item_preview_summary(&self.pool, id).await?;

            orders.push(OrderSummaryDto {
                id: id.to_string(),
                order_number: row.try_get("order_number")?,
                fulfillment_type: parse_fulfillment_type(&fulfillment_raw)?,
                schedule_type: parse_schedule_type(&schedule_raw)?,
                status: parse_order_status(&status_raw)?,
                version: row.try_get("version")?,
                total_paise: row.try_get("total_paise")?,
                item_count: row.try_get("item_count")?,
                payment_method: parse_payment_method(&method_raw)?,
                payment_status: parse_payment_status(&pay_status_raw)?,
                is_priority: row.try_get("is_priority")?,
                created_at: created_at.to_rfc3339(),
                expires_at: expires_at.map(|dt| dt.to_rfc3339()),
                item_preview_summary: preview,
            });
        }

        Ok(OrderListResponse { orders })
    }

    pub async fn get_order_detail(
        &self,
        order_id: Uuid,
        actor_user_id: Uuid,
        actor_role: UserRole,
    ) -> Result<OrderDetailDto, DbError> {
        let mut tx = self.pool.begin().await?;
        let detail = Self::load_order_detail_in_tx(&mut tx, order_id)
            .await?
            .ok_or_else(|| DbError::NotFound {
                code: "ORDER_NOT_FOUND",
                message: format!("Order {order_id} was not found."),
            })?;

        if actor_role == UserRole::Customer && detail.customer_id != actor_user_id.to_string() {
            warn!(
                actor_user_id = %actor_user_id,
                order_id = %order_id,
                "Blocked cross-user order read IDOR"
            );
            return Err(DbError::Forbidden {
                code: "FORBIDDEN",
                message: "You do not have access to this order.".to_owned(),
            });
        }

        tx.commit().await?;
        Ok(detail)
    }

    /// Phase 8: Reorder endpoint - reconstruct order items for reordering
    /// Returns ReorderResponseDto with available items and unavailable items with reasons.
    pub async fn reorder(
        &self,
        order_id: Uuid,
        actor_user_id: Uuid,
        actor_role: UserRole,
    ) -> Result<ReorderResponseDto, DbError> {
        let mut tx = self.pool.begin().await?;
        let detail = Self::load_order_detail_in_tx(&mut tx, order_id)
            .await?
            .ok_or_else(|| DbError::NotFound {
                code: "ORDER_NOT_FOUND",
                message: format!("Order {order_id} was not found."),
            })?;

        // Ownership check
        if actor_role == UserRole::Customer && detail.customer_id != actor_user_id.to_string() {
            warn!(
                actor_user_id = %actor_user_id,
                order_id = %order_id,
                "Blocked cross-user reorder IDOR"
            );
            return Err(DbError::Forbidden {
                code: "FORBIDDEN",
                message: "You do not have access to this order.".to_owned(),
            });
        }

        // Stored order IDs are UUID strings. Bind them as UUIDs; malformed legacy
        // values fall back to the nil UUID so reconciliation reports unavailable
        // items instead of failing the whole request with a SQL type error.
        fn reorder_uuid(raw: &str) -> Uuid {
            Uuid::parse_str(raw).unwrap_or_else(|_| Uuid::nil())
        }

        let mut reorder_items = Vec::new();
        let mut unavailable = Vec::new();
        let mut warnings = Vec::new();

        // Check if historical order lacks new modifier data
        let has_new_modifiers = detail.items.iter().any(|item| {
            item.variant_id.is_some()
                || item.crust_id.is_some()
                || item.cheese_id.is_some()
                || item.sauce_id.is_some()
                || !item.standard_topping_ids.is_empty()
                || !item.standard_topping_removed_ids.is_empty()
                || item.half_and_half_config_id.is_some()
                || !item.half_and_half_left_topping_ids.is_empty()
                || !item.half_and_half_right_topping_ids.is_empty()
        });
        if !has_new_modifiers {
            warnings.push("Historical order: cheese/sauce/standard toppings/half-and-half could not be reconstructed.".to_owned());
        }

        for item in &detail.items {
            // Try to resolve current live prices and availability
            let mut issues = Vec::new();
            let product_uuid = reorder_uuid(&item.product_id);

            // Resolve variant by (productId, variantSize)
            let variant_id = if let Some(vid) = &item.variant_id {
                // Use stored variant_id if available
                let exists: Option<i32> = sqlx::query_scalar(
                    "SELECT 1 FROM product_variants WHERE id = $1 AND product_id = $2 AND is_active = TRUE",
                )
                .bind(reorder_uuid(vid))
                .bind(product_uuid)
                .fetch_optional(&mut *tx)
                .await?;
                if exists.is_some() {
                    Some(vid.clone())
                } else {
                    issues.push("VARIANT_UNAVAILABLE".to_owned());
                    // Fallback: find variant by size name
                    let fallback: Option<Uuid> = sqlx::query_scalar(
                        "SELECT id FROM product_variants WHERE product_id = $1 AND size_code = $2 AND is_active = TRUE",
                    )
                    .bind(product_uuid)
                    .bind(&item.variant_size)
                    .fetch_optional(&mut *tx)
                    .await?;
                    fallback.map(|u| u.to_string())
                }
            } else {
                // Historical: find variant by size name
                let fallback: Option<Uuid> = sqlx::query_scalar(
                    "SELECT id FROM product_variants WHERE product_id = $1 AND size_code = $2 AND is_active = TRUE",
                )
                .bind(product_uuid)
                .bind(&item.variant_size)
                .fetch_optional(&mut *tx)
                .await?;
                fallback.map(|u| u.to_string())
            };

            // Resolve crust by name
            let crust_id = if let Some(cid) = &item.crust_id {
                let exists: Option<i32> = sqlx::query_scalar(
                    "SELECT 1 FROM crust_options WHERE id = $1 AND is_active = TRUE",
                )
                .bind(reorder_uuid(cid))
                .fetch_optional(&mut *tx)
                .await?;
                if exists.is_some() {
                    Some(cid.clone())
                } else {
                    issues.push("CRUST_UNAVAILABLE".to_owned());
                    let fallback: Option<Uuid> = sqlx::query_scalar(
                        "SELECT id FROM crust_options WHERE name = $1 AND is_active = TRUE",
                    )
                    .bind(&item.crust_name)
                    .fetch_optional(&mut *tx)
                    .await?;
                    fallback.map(|u| u.to_string())
                }
            } else {
                let fallback: Option<Uuid> = sqlx::query_scalar(
                    "SELECT id FROM crust_options WHERE name = $1 AND is_active = TRUE",
                )
                .bind(&item.crust_name)
                .fetch_optional(&mut *tx)
                .await?;
                fallback.map(|u| u.to_string())
            };

            // Resolve cheese (Phase 8 modifier - only if stored)
            if let Some(cid) = &item.cheese_id {
                let active: Option<bool> = sqlx::query_scalar(
                    "SELECT is_active FROM cheeses WHERE id = $1 AND product_id = $2",
                )
                .bind(reorder_uuid(cid))
                .bind(product_uuid)
                .fetch_optional(&mut *tx)
                .await?
                .flatten();
                if active == Some(true) {
                    // cheese_id = Some(cid.clone());
                } else {
                    unavailable.push(ReorderUnavailableDto {
                        order_item_id: item.id.clone(),
                        product_name: item.product_name.clone(),
                        modifier_type: "CHEESE".to_owned(),
                        modifier_id: cid.clone(),
                        modifier_name: "Unknown".to_owned(), // Would need to fetch name
                        reason: ReorderUnavailableReason::CheeseUnavailable,
                    });
                    issues.push("CHEESE_UNAVAILABLE".to_owned());
                }
            }

            // Resolve sauce
            if let Some(sid) = &item.sauce_id {
                let active: Option<bool> = sqlx::query_scalar(
                    "SELECT is_active FROM sauces WHERE id = $1 AND product_id = $2",
                )
                .bind(reorder_uuid(sid))
                .bind(product_uuid)
                .fetch_optional(&mut *tx)
                .await?
                .flatten();
                if active != Some(true) {
                    unavailable.push(ReorderUnavailableDto {
                        order_item_id: item.id.clone(),
                        product_name: item.product_name.clone(),
                        modifier_type: "SAUCE".to_owned(),
                        modifier_id: sid.clone(),
                        modifier_name: "Unknown".to_owned(),
                        reason: ReorderUnavailableReason::SauceUnavailable,
                    });
                    issues.push("SAUCE_UNAVAILABLE".to_owned());
                }
            }

            // Resolve extra toppings by name (historical fallback)
            let mut resolved_topping_ids = Vec::new();
            for topping_name in &item.toppings {
                let tid: Option<Uuid> = sqlx::query_scalar(
                    "SELECT id FROM toppings WHERE name = $1 AND is_active = TRUE",
                )
                .bind(topping_name)
                .fetch_optional(&mut *tx)
                .await?;
                if let Some(tid) = tid {
                    resolved_topping_ids.push(tid.to_string());
                } else {
                    unavailable.push(ReorderUnavailableDto {
                        order_item_id: item.id.clone(),
                        product_name: item.product_name.clone(),
                        modifier_type: "TOPPING".to_owned(),
                        modifier_id: "".to_owned(),
                        modifier_name: topping_name.clone(),
                        reason: ReorderUnavailableReason::ToppingUnavailable,
                    });
                    issues.push("TOPPING_UNAVAILABLE".to_owned());
                }
            }

            // Standard toppings (Phase 8)
            let mut resolved_std_topping_ids = Vec::new();
            for std_id in &item.standard_topping_ids {
                let active: Option<bool> = sqlx::query_scalar(
                    "SELECT is_active FROM standard_toppings WHERE id = $1 AND product_id = $2",
                )
                .bind(reorder_uuid(std_id))
                .bind(product_uuid)
                .fetch_optional(&mut *tx)
                .await?
                .flatten();
                if active == Some(true) {
                    resolved_std_topping_ids.push(std_id.clone());
                } else {
                    unavailable.push(ReorderUnavailableDto {
                        order_item_id: item.id.clone(),
                        product_name: item.product_name.clone(),
                        modifier_type: "STANDARD_TOPPING".to_owned(),
                        modifier_id: std_id.clone(),
                        modifier_name: "Unknown".to_owned(),
                        reason: ReorderUnavailableReason::StandardToppingUnavailable,
                    });
                    issues.push("STANDARD_TOPPING_UNAVAILABLE".to_owned());
                }
            }

            // Standard topping removals (historical - just pass through)
            let standard_topping_removed_ids = item.standard_topping_removed_ids.clone();

            // Half-and-half config
            let mut half_and_half_config_id = None;
            if let Some(hah_id) = &item.half_and_half_config_id {
                let active: Option<bool> = sqlx::query_scalar(
                    "SELECT is_active FROM half_and_half_configs WHERE id = $1 AND product_id = $2",
                )
                .bind(reorder_uuid(hah_id))
                .bind(product_uuid)
                .fetch_optional(&mut *tx)
                .await?
                .flatten();
                if active == Some(true) {
                    half_and_half_config_id = Some(hah_id.clone());
                } else {
                    unavailable.push(ReorderUnavailableDto {
                        order_item_id: item.id.clone(),
                        product_name: item.product_name.clone(),
                        modifier_type: "HALF_AND_HALF_CONFIG".to_owned(),
                        modifier_id: hah_id.clone(),
                        modifier_name: "Unknown".to_owned(),
                        reason: ReorderUnavailableReason::HalfAndHalfConfigUnavailable,
                    });
                    issues.push("HALF_AND_HALF_UNAVAILABLE".to_owned());
                }
            }

            // Half-and-half left/right toppings (pass through from snapshot)
            let half_and_half_left_topping_ids = item.half_and_half_left_topping_ids.clone();
            let half_and_half_right_topping_ids = item.half_and_half_right_topping_ids.clone();

            let reorder_item = ReorderItemDto {
                order_item_id: item.id.clone(),
                product_id: item.product_id.clone(),
                product_name: item.product_name.clone(),
                variant_id,
                crust_id,
                cheese_id: item.cheese_id.clone(), // Pass through stored ID
                sauce_id: item.sauce_id.clone(),
                topping_ids: resolved_topping_ids,
                standard_topping_ids: resolved_std_topping_ids,
                standard_topping_removed_ids,
                half_and_half_config_id,
                half_and_half_left_topping_ids,
                half_and_half_right_topping_ids,
                quantity: item.quantity,
                is_available: issues.is_empty(),
                issues,
            };

            // Keep unavailable items in the response for reconciliation, but only
            // available items make the order reorderable. No substitution is attempted.
            reorder_items.push(reorder_item);
        }

        let can_reorder = reorder_items.iter().any(|item| item.is_available);

        tx.commit().await?;

        Ok(ReorderResponseDto {
            order_id: order_id.to_string(),
            can_reorder,
            items: reorder_items,
            unavailable,
            warnings,
        })
    }

    pub async fn cancel_order(
        &self,
        order_id: Uuid,
        actor_user_id: Uuid,
        actor_role: UserRole,
        reason: Option<&str>,
        audit: &AuditContext,
    ) -> Result<OrderDetailDto, DbError> {
        let clean_reason: Option<String> = reason
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(|s| s.chars().take(250).collect());

        if actor_role == UserRole::Owner && clean_reason.is_none() {
            return Err(DbError::Validation {
                code: "CANCEL_REASON_REQUIRED",
                message: "Owner order cancellation requires a reason.".to_owned(),
            });
        }

        let mut tx = self.pool.begin().await?;
        let locked = load_locked_order(&mut tx, order_id).await?;

        if actor_role == UserRole::Customer && locked.customer_id != actor_user_id {
            warn!(
                actor_user_id = %actor_user_id,
                order_id = %order_id,
                "Blocked cross-user order cancel IDOR"
            );
            return Err(DbError::Forbidden {
                code: "FORBIDDEN",
                message: "You cannot cancel another customer's order.".to_owned(),
            });
        }

        if !OrderStateMachine::can_cancel(locked.status, actor_role) {
            return Err(DbError::Conflict {
                code: "INVALID_ORDER_TRANSITION",
                message: format!(
                    "Order {} in status {} cannot be cancelled by {:?}.",
                    locked.order_number,
                    order_status_as_str(locked.status),
                    actor_role
                ),
            });
        }

        let new_version = locked.version + 1;
        sqlx::query("UPDATE orders SET status = 'CANCELLED', version = $1 WHERE id = $2")
            .bind(new_version)
            .bind(order_id)
            .execute(&mut *tx)
            .await?;

        let effective_reason = clean_reason.unwrap_or_else(|| match actor_role {
            UserRole::Customer => "Cancelled by customer".to_owned(),
            UserRole::KitchenStaff => "Cancelled by kitchen_staff".to_owned(),
            UserRole::Owner => "Cancelled by owner".to_owned(),
        });

        sqlx::query(
            r#"
            INSERT INTO order_status_history (id, order_id, from_status, to_status, changed_by, reason)
            VALUES ($1, $2, $3, 'CANCELLED', $4, $5)
            "#,
        )
        .bind(new_uuid_v4())
        .bind(order_id)
        .bind(order_status_as_str(locked.status))
        .bind(actor_user_id)
        .bind(&effective_reason)
        .execute(&mut *tx)
        .await?;

        match locked.status {
            OrderStatus::PendingPayment => {
                InventoryService::release_reserved_inventory(
                    &mut tx,
                    order_id,
                    false,
                    Some(actor_user_id),
                )
                .await?;
                CouponService::release_reserved_coupon(&mut tx, order_id, false).await?;
            }
            OrderStatus::Placed | OrderStatus::Accepted => {
                InventoryService::restore_committed_inventory(
                    &mut tx,
                    order_id,
                    Some(actor_user_id),
                )
                .await?;
            }
            _ => {}
        }

        release_scheduled_slot_if_reserved(&mut tx, order_id).await?;
        handle_payment_on_cancel(
            &mut tx,
            order_id,
            new_version,
            actor_user_id,
            &effective_reason,
        )
        .await?;

        let cancelled_payload = serde_json::json!({
            "order_id": order_id.to_string(),
            "order_number": locked.order_number,
            "customer_id": locked.customer_id.to_string(),
            "from_status": order_status_as_str(locked.status),
            "to_status": "CANCELLED",
            "reason": effective_reason,
        });
        OutboxRepository::insert_outbox_event(
            &mut tx,
            "ORDER",
            order_id,
            new_version,
            "ORDER_CANCELLED",
            &cancelled_payload,
        )
        .await?;

        let before_val = serde_json::json!({
            "status": order_status_as_str(locked.status),
            "version": locked.version
        });
        let after_val = serde_json::json!({
            "status": "CANCELLED",
            "version": new_version,
            "reason": effective_reason
        });
        let entity_id = order_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "ORDER_CANCELLED",
            "order",
            &entity_id,
            Some(&before_val),
            Some(&after_val),
        )
        .await?;

        let detail = Self::load_order_detail_in_tx(&mut tx, order_id)
            .await?
            .ok_or_else(|| DbError::NotFound {
                code: "ORDER_NOT_FOUND",
                message: format!("Order {order_id} was not found."),
            })?;
        tx.commit().await?;
        Ok(detail)
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn transition_order_status(
        &self,
        order_id: Uuid,
        to_status: OrderStatus,
        actor_user_id: Uuid,
        actor_role: UserRole,
        reason: Option<&str>,
        audit: &AuditContext,
        outbox_event_type: &str,
    ) -> Result<OrderDetailDto, DbError> {
        if to_status == OrderStatus::Cancelled {
            return self
                .cancel_order(order_id, actor_user_id, actor_role, reason, audit)
                .await;
        }

        let clean_reason: Option<String> = reason
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(|s| s.chars().take(250).collect());

        let mut tx = self.pool.begin().await?;
        let locked = load_locked_order(&mut tx, order_id).await?;

        if !OrderStateMachine::can_transition(
            locked.status,
            to_status,
            locked.fulfillment_type,
            Some(actor_role),
        ) {
            return Err(DbError::Conflict {
                code: "INVALID_ORDER_TRANSITION",
                message: format!(
                    "Order {} cannot transition from {} to {}.",
                    locked.order_number,
                    order_status_as_str(locked.status),
                    order_status_as_str(to_status)
                ),
            });
        }

        let new_version = locked.version + 1;
        let sql = match to_status {
            OrderStatus::Accepted => {
                "UPDATE orders SET status = $1, version = $2, accepted_at = COALESCE(accepted_at, NOW()) WHERE id = $3"
            }
            OrderStatus::Preparing => {
                "UPDATE orders SET status = $1, version = $2, prep_started_at = COALESCE(prep_started_at, NOW()) WHERE id = $3"
            }
            OrderStatus::Ready => {
                "UPDATE orders SET status = $1, version = $2, ready_at = COALESCE(ready_at, NOW()) WHERE id = $3"
            }
            OrderStatus::Completed => {
                "UPDATE orders SET status = $1, version = $2, completed_at = COALESCE(completed_at, NOW()) WHERE id = $3"
            }
            _ => "UPDATE orders SET status = $1, version = $2 WHERE id = $3",
        };

        sqlx::query(sql)
            .bind(order_status_as_str(to_status))
            .bind(new_version)
            .bind(order_id)
            .execute(&mut *tx)
            .await?;

        if to_status == OrderStatus::Completed {
            let pay_row = sqlx::query(
                "SELECT id, method, status, amount_paise FROM payments WHERE order_id = $1 FOR UPDATE",
            )
            .bind(order_id)
            .fetch_optional(&mut *tx)
            .await?;

            if let Some(pr) = pay_row {
                let payment_id: Uuid = pr.try_get("id")?;
                let method_raw: String = pr.try_get("method")?;
                let status_raw: String = pr.try_get("status")?;
                let amount_paise: i64 = pr.try_get("amount_paise")?;

                if parse_payment_method(&method_raw)? == PaymentMethod::Cod
                    && parse_payment_status(&status_raw)? == PaymentStatus::CodPending
                {
                    sqlx::query(
                        "UPDATE payments SET status = 'COD_COLLECTED', captured_paise = $1, updated_at = NOW() WHERE id = $2",
                    )
                    .bind(amount_paise)
                    .bind(payment_id)
                    .execute(&mut *tx)
                    .await?;

                    sqlx::query(
                        r#"
                        UPDATE payment_attempts
                        SET status = 'CAPTURED', collected_by_user_id = $1, updated_at = NOW()
                        WHERE payment_id = $2 AND status = 'AWAITING_CUSTOMER_PAYMENT'
                        "#,
                    )
                    .bind(actor_user_id)
                    .bind(payment_id)
                    .execute(&mut *tx)
                    .await?;
                }
            }
        }

        sqlx::query(
            r#"
            INSERT INTO order_status_history (id, order_id, from_status, to_status, changed_by, reason)
            VALUES ($1, $2, $3, $4, $5, $6)
            "#,
        )
        .bind(new_uuid_v4())
        .bind(order_id)
        .bind(order_status_as_str(locked.status))
        .bind(order_status_as_str(to_status))
        .bind(actor_user_id)
        .bind(clean_reason.as_deref())
        .execute(&mut *tx)
        .await?;

        let detail = Self::load_order_detail_in_tx(&mut tx, order_id)
            .await?
            .ok_or_else(|| DbError::NotFound {
                code: "ORDER_NOT_FOUND",
                message: format!("Order {order_id} was not found."),
            })?;

        let mut payload = serde_json::json!({
            "order_id": order_id.to_string(),
            "order_number": locked.order_number,
            "customer_id": locked.customer_id.to_string(),
            "fulfillment_type": fulfillment_type_as_str(locked.fulfillment_type),
            "from_status": order_status_as_str(locked.status),
            "to_status": order_status_as_str(to_status),
            "status": order_status_as_str(to_status),
            "is_priority": detail.is_priority,
            "changed_by": actor_user_id.to_string(),
        });
        if let Some(obj) = payload.as_object_mut() {
            if let Some(r) = clean_reason {
                obj.insert("reason".to_owned(), serde_json::Value::String(r));
            }
            if let Some(v) = &detail.accepted_at {
                obj.insert(
                    "accepted_at".to_owned(),
                    serde_json::Value::String(v.clone()),
                );
            }
            if let Some(v) = &detail.prep_started_at {
                obj.insert(
                    "prep_started_at".to_owned(),
                    serde_json::Value::String(v.clone()),
                );
            }
            if let Some(v) = &detail.ready_at {
                obj.insert("ready_at".to_owned(), serde_json::Value::String(v.clone()));
            }
            if let Some(v) = &detail.completed_at {
                obj.insert(
                    "completed_at".to_owned(),
                    serde_json::Value::String(v.clone()),
                );
            }
        }

        OutboxRepository::insert_outbox_event(
            &mut tx,
            "ORDER",
            order_id,
            new_version,
            outbox_event_type,
            &payload,
        )
        .await?;

        let before_val = serde_json::json!({
            "status": order_status_as_str(locked.status),
            "version": locked.version
        });
        let after_val = serde_json::json!({
            "status": order_status_as_str(to_status),
            "version": new_version
        });
        let entity_id = order_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "ORDER_STATUS_CHANGED",
            "order",
            &entity_id,
            Some(&before_val),
            Some(&after_val),
        )
        .await?;

        tx.commit().await?;
        Ok(detail)
    }

    pub async fn load_order_detail_in_tx(
        conn: &mut PgConnection,
        order_id: Uuid,
    ) -> Result<Option<OrderDetailDto>, DbError> {
        let header_opt = sqlx::query(
            r#"
            SELECT id, order_number, customer_id, fulfillment_type, schedule_type, scheduled_slot_id,
                   delivery_address_snapshot, status, version,
                   subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise,
                   gst_bps_snapshot, coupon_code_snapshot, is_priority,
                   accepted_at, prep_started_at, ready_at, completed_at, expires_at, created_at
            FROM orders
            WHERE id = $1
            "#,
        )
        .bind(order_id)
        .fetch_optional(&mut *conn)
        .await?;

        let Some(h) = header_opt else {
            return Ok(None);
        };

        let tax_paise: i64 = h.try_get("tax_paise")?;
        let cgst_paise = tax_paise / 2;
        let sgst_paise = tax_paise - cgst_paise;
        let addr_json: Option<serde_json::Value> = h.try_get("delivery_address_snapshot")?;
        let delivery_address =
            addr_json.and_then(|v| serde_json::from_value::<DeliveryAddressSnapshotDto>(v).ok());

        // Phase 8: Read new order_items columns
        let item_rows = sqlx::query(
            r#"
            SELECT id, product_id, product_name_snapshot, variant_size_snapshot, crust_name_snapshot,
                   toppings_snapshot, unit_price_paise, quantity, line_total_paise,
                   variant_id, crust_id, cheese_id, sauce_id,
                   topping_ids, standard_topping_ids, standard_topping_removed_ids,
                   half_and_half_config_id, half_and_half_left_topping_ids, half_and_half_right_topping_ids,
                   modifiers_snapshot
            FROM order_items
            WHERE order_id = $1
            ORDER BY id ASC
            "#,
        )
        .bind(order_id)
        .fetch_all(&mut *conn)
        .await?;

        let mut items = Vec::with_capacity(item_rows.len());
        for r in item_rows {
            let toppings_json: serde_json::Value = r.try_get("toppings_snapshot")?;
            let toppings = serde_json::from_value::<Vec<String>>(toppings_json).unwrap_or_default();

            // Phase 8: Read new columns (all nullable for historical rows)
            let variant_id: Option<Uuid> = r.try_get("variant_id")?;
            let crust_id: Option<Uuid> = r.try_get("crust_id")?;
            let cheese_id: Option<Uuid> = r.try_get("cheese_id")?;
            let sauce_id: Option<Uuid> = r.try_get("sauce_id")?;
            let topping_ids: serde_json::Value = r.try_get("topping_ids")?;
            let standard_topping_ids: serde_json::Value = r.try_get("standard_topping_ids")?;
            let standard_topping_removed_ids: serde_json::Value =
                r.try_get("standard_topping_removed_ids")?;
            let half_and_half_config_id: Option<Uuid> = r.try_get("half_and_half_config_id")?;
            let half_and_half_left_topping_ids: serde_json::Value =
                r.try_get("half_and_half_left_topping_ids")?;
            let half_and_half_right_topping_ids: serde_json::Value =
                r.try_get("half_and_half_right_topping_ids")?;
            let modifiers_snapshot: serde_json::Value = r.try_get("modifiers_snapshot")?;

            items.push(OrderItemDto {
                id: r.try_get::<Uuid, _>("id")?.to_string(),
                product_id: r.try_get::<Uuid, _>("product_id")?.to_string(),
                product_name: r.try_get("product_name_snapshot")?,
                variant_size: r.try_get("variant_size_snapshot")?,
                crust_name: r.try_get("crust_name_snapshot")?,
                toppings,
                unit_price_paise: r.try_get("unit_price_paise")?,
                quantity: r.try_get("quantity")?,
                line_total_paise: r.try_get("line_total_paise")?,
                variant_id: variant_id.map(|u| u.to_string()),
                crust_id: crust_id.map(|u| u.to_string()),
                cheese_id: cheese_id.map(|u| u.to_string()),
                sauce_id: sauce_id.map(|u| u.to_string()),
                topping_ids: serde_json::from_value(topping_ids).unwrap_or_default(),
                standard_topping_ids: serde_json::from_value(standard_topping_ids)
                    .unwrap_or_default(),
                standard_topping_removed_ids: serde_json::from_value(standard_topping_removed_ids)
                    .unwrap_or_default(),
                half_and_half_config_id: half_and_half_config_id.map(|u| u.to_string()),
                half_and_half_left_topping_ids: serde_json::from_value(
                    half_and_half_left_topping_ids,
                )
                .unwrap_or_default(),
                half_and_half_right_topping_ids: serde_json::from_value(
                    half_and_half_right_topping_ids,
                )
                .unwrap_or_default(),
                modifiers_snapshot: serde_json::from_value(modifiers_snapshot).unwrap_or_default(),
            });
        }

        let hist_rows = sqlx::query(
            r#"
            SELECT id, from_status, to_status, changed_by, reason, created_at
            FROM order_status_history
            WHERE order_id = $1
            ORDER BY created_at ASC, id ASC
            "#,
        )
        .bind(order_id)
        .fetch_all(&mut *conn)
        .await?;

        let mut status_history = Vec::with_capacity(hist_rows.len());
        for r in hist_rows {
            let from_raw: Option<String> = r.try_get("from_status")?;
            let to_raw: String = r.try_get("to_status")?;
            let changed_by: Option<Uuid> = r.try_get("changed_by")?;
            let created_at: DateTime<Utc> = r.try_get("created_at")?;
            status_history.push(OrderStatusHistoryDto {
                id: r.try_get::<Uuid, _>("id")?.to_string(),
                from_status: from_raw.as_deref().map(parse_order_status).transpose()?,
                to_status: parse_order_status(&to_raw)?,
                changed_by: changed_by.map(|u| u.to_string()),
                reason: r.try_get("reason")?,
                created_at: created_at.to_rfc3339(),
            });
        }

        let pay_row = sqlx::query(
            r#"
            SELECT id, method, status, amount_paise, captured_paise, refunded_paise, currency
            FROM payments
            WHERE order_id = $1
            "#,
        )
        .bind(order_id)
        .fetch_optional(&mut *conn)
        .await?
        .ok_or_else(|| {
            DbError::CatalogInvariantViolation(format!("Missing payments row for order {order_id}"))
        })?;

        let payment_id: Uuid = pay_row.try_get("id")?;
        let latest_attempt = load_latest_attempt(&mut *conn, payment_id).await?;
        let payment = PaymentSummaryDto {
            id: payment_id.to_string(),
            method: parse_payment_method(&pay_row.try_get::<String, _>("method")?)?,
            status: parse_payment_status(&pay_row.try_get::<String, _>("status")?)?,
            amount_paise: pay_row.try_get("amount_paise")?,
            captured_paise: pay_row.try_get("captured_paise")?,
            refunded_paise: pay_row.try_get("refunded_paise")?,
            currency: pay_row.try_get("currency")?,
            latest_attempt,
        };

        Ok(Some(OrderDetailDto {
            id: h.try_get::<Uuid, _>("id")?.to_string(),
            order_number: h.try_get("order_number")?,
            customer_id: h.try_get::<Uuid, _>("customer_id")?.to_string(),
            fulfillment_type: parse_fulfillment_type(&h.try_get::<String, _>("fulfillment_type")?)?,
            schedule_type: parse_schedule_type(&h.try_get::<String, _>("schedule_type")?)?,
            scheduled_slot_id: h
                .try_get::<Option<Uuid>, _>("scheduled_slot_id")?
                .map(|u| u.to_string()),
            delivery_address,
            status: parse_order_status(&h.try_get::<String, _>("status")?)?,
            version: h.try_get("version")?,
            subtotal_paise: h.try_get("subtotal_paise")?,
            discount_paise: h.try_get("discount_paise")?,
            delivery_fee_paise: h.try_get("delivery_fee_paise")?,
            tax_paise,
            cgst_paise,
            sgst_paise,
            total_paise: h.try_get("total_paise")?,
            gst_bps_snapshot: h.try_get("gst_bps_snapshot")?,
            coupon_code_snapshot: h.try_get("coupon_code_snapshot")?,
            is_priority: h.try_get("is_priority")?,
            accepted_at: h
                .try_get::<Option<DateTime<Utc>>, _>("accepted_at")?
                .map(|dt| dt.to_rfc3339()),
            prep_started_at: h
                .try_get::<Option<DateTime<Utc>>, _>("prep_started_at")?
                .map(|dt| dt.to_rfc3339()),
            ready_at: h
                .try_get::<Option<DateTime<Utc>>, _>("ready_at")?
                .map(|dt| dt.to_rfc3339()),
            completed_at: h
                .try_get::<Option<DateTime<Utc>>, _>("completed_at")?
                .map(|dt| dt.to_rfc3339()),
            expires_at: h
                .try_get::<Option<DateTime<Utc>>, _>("expires_at")?
                .map(|dt| dt.to_rfc3339()),
            created_at: h.try_get::<DateTime<Utc>, _>("created_at")?.to_rfc3339(),
            items,
            status_history,
            payment,
        }))
    }
}

/// Phase 8: Build item preview summary for order list
async fn build_item_preview_summary(
    pool: &PgPool,
    order_id: Uuid,
) -> Result<Option<String>, DbError> {
    let rows = sqlx::query(
        r#"
        SELECT product_name_snapshot, variant_size_snapshot, crust_name_snapshot, quantity
        FROM order_items
        WHERE order_id = $1
        ORDER BY id ASC
        "#,
    )
    .bind(order_id)
    .fetch_all(pool)
    .await?;

    if rows.is_empty() {
        return Ok(None);
    }

    let parts: Vec<String> = rows
        .into_iter()
        .map(|r| {
            let name: String = r.try_get("product_name_snapshot").unwrap_or_default();
            let size: String = r.try_get("variant_size_snapshot").unwrap_or_default();
            let crust: String = r.try_get("crust_name_snapshot").unwrap_or_default();
            let qty: i32 = r.try_get("quantity").unwrap_or(1);
            format!("{name} ({size}, {crust}) x{qty}")
        })
        .collect();

    Ok(Some(parts.join("; ")))
}

struct LockedOrderHeader {
    customer_id: Uuid,
    order_number: String,
    status: OrderStatus,
    version: i64,
    fulfillment_type: FulfillmentType,
}

async fn load_locked_order(
    conn: &mut PgConnection,
    order_id: Uuid,
) -> Result<LockedOrderHeader, DbError> {
    let row = sqlx::query(
        r#"
        SELECT customer_id, order_number, status, version, fulfillment_type
        FROM orders
        WHERE id = $1
        FOR UPDATE
        "#,
    )
    .bind(order_id)
    .fetch_optional(&mut *conn)
    .await?
    .ok_or_else(|| DbError::NotFound {
        code: "ORDER_NOT_FOUND",
        message: format!("Order {order_id} was not found."),
    })?;

    Ok(LockedOrderHeader {
        customer_id: row.try_get("customer_id")?,
        order_number: row.try_get("order_number")?,
        status: parse_order_status(&row.try_get::<String, _>("status")?)?,
        version: row.try_get("version")?,
        fulfillment_type: parse_fulfillment_type(&row.try_get::<String, _>("fulfillment_type")?)?,
    })
}

async fn release_scheduled_slot_if_reserved(
    conn: &mut PgConnection,
    order_id: Uuid,
) -> Result<(), DbError> {
    let row = sqlx::query(
        r#"
        SELECT id, slot_id
        FROM scheduled_slot_reservations
        WHERE order_id = $1 AND status IN ('ACTIVE', 'COMMITTED')
        FOR UPDATE
        "#,
    )
    .bind(order_id)
    .fetch_optional(&mut *conn)
    .await?;

    let Some(r) = row else {
        return Ok(());
    };
    let res_id: Uuid = r.try_get("id")?;
    let slot_id: Uuid = r.try_get("slot_id")?;

    let updated = sqlx::query(
        r#"
        UPDATE scheduled_slot_reservations
        SET status = 'RELEASED', expires_at = NULL, updated_at = NOW()
        WHERE id = $1 AND status IN ('ACTIVE', 'COMMITTED')
        "#,
    )
    .bind(res_id)
    .execute(&mut *conn)
    .await?;

    if updated.rows_affected() > 0 {
        sqlx::query(
            "UPDATE scheduled_slots SET reserved_count = GREATEST(0, reserved_count - 1) WHERE id = $1",
        )
        .bind(slot_id)
        .execute(&mut *conn)
        .await?;
    }
    Ok(())
}

async fn handle_payment_on_cancel(
    conn: &mut PgConnection,
    order_id: Uuid,
    order_version: i64,
    actor_user_id: Uuid,
    reason: &str,
) -> Result<(), DbError> {
    let pay_row = sqlx::query(
        r#"
        SELECT id, method, status, captured_paise
        FROM payments
        WHERE order_id = $1
        FOR UPDATE
        "#,
    )
    .bind(order_id)
    .fetch_optional(&mut *conn)
    .await?;

    let Some(pr) = pay_row else {
        return Ok(());
    };
    let payment_id: Uuid = pr.try_get("id")?;
    let status = parse_payment_status(&pr.try_get::<String, _>("status")?)?;
    let captured_paise: i64 = pr.try_get("captured_paise")?;

    sqlx::query(
        r#"
        UPDATE payment_attempts
        SET status = 'CANCELLED', updated_at = NOW()
        WHERE payment_id = $1 AND status IN ('INITIATING', 'AWAITING_CUSTOMER_PAYMENT')
        "#,
    )
    .bind(payment_id)
    .execute(&mut *conn)
    .await?;

    match status {
        PaymentStatus::Unpaid | PaymentStatus::CodPending => {
            sqlx::query(
                "UPDATE payments SET status = 'CANCELLED', updated_at = NOW() WHERE id = $1",
            )
            .bind(payment_id)
            .execute(&mut *conn)
            .await?;
        }
        PaymentStatus::Captured => {
            sqlx::query(
                "UPDATE payments SET status = 'REFUND_PENDING', updated_at = NOW() WHERE id = $1",
            )
            .bind(payment_id)
            .execute(&mut *conn)
            .await?;

            if captured_paise > 0 {
                let idempotency_key = format!("order_cancel:{order_id}");
                let inserted_refund = sqlx::query_scalar::<_, Uuid>(
                    r#"
                    INSERT INTO refunds (
                        id, payment_id, order_id, idempotency_key, amount_paise, status, reason, initiated_by
                    ) VALUES ($1, $2, $3, $4, $5, 'PENDING', $6, $7)
                    ON CONFLICT (idempotency_key) DO NOTHING
                    RETURNING id
                    "#,
                )
                .bind(new_uuid_v4())
                .bind(payment_id)
                .bind(order_id)
                .bind(&idempotency_key)
                .bind(captured_paise)
                .bind(reason)
                .bind(actor_user_id)
                .fetch_optional(&mut *conn)
                .await?;

                if let Some(refund_id) = inserted_refund {
                    let payload = serde_json::json!({
                        "refund_id": refund_id.to_string(),
                        "order_id": order_id.to_string(),
                        "payment_id": payment_id.to_string(),
                        "amount_paise": captured_paise,
                        "idempotency_key": idempotency_key,
                    });
                    OutboxRepository::insert_outbox_event(
                        &mut *conn,
                        "ORDER",
                        order_id,
                        order_version,
                        EVENT_REFUND_REQUESTED,
                        &payload,
                    )
                    .await?;
                }
            }
        }
        _ => {}
    }

    Ok(())
}

async fn load_latest_attempt(
    conn: &mut PgConnection,
    payment_id: Uuid,
) -> Result<Option<PaymentAttemptSummaryDto>, DbError> {
    let row = sqlx::query(
        r#"
        SELECT id, attempt_number, method, status, amount_paise, idempotency_receipt,
               gateway_order_id, gateway_payment_id, failure_code, failure_reason, expires_at, created_at
        FROM payment_attempts
        WHERE payment_id = $1
        ORDER BY attempt_number DESC
        LIMIT 1
        "#,
    )
    .bind(payment_id)
    .fetch_optional(&mut *conn)
    .await?;

    let Some(r) = row else {
        return Ok(None);
    };

    Ok(Some(PaymentAttemptSummaryDto {
        id: r.try_get::<Uuid, _>("id")?.to_string(),
        attempt_number: r.try_get("attempt_number")?,
        method: parse_payment_method(&r.try_get::<String, _>("method")?)?,
        status: parse_payment_attempt_status(&r.try_get::<String, _>("status")?)?,
        amount_paise: r.try_get("amount_paise")?,
        idempotency_receipt: r.try_get("idempotency_receipt")?,
        gateway_order_id: r.try_get("gateway_order_id")?,
        gateway_payment_id: r.try_get("gateway_payment_id")?,
        failure_code: r.try_get("failure_code")?,
        failure_reason: r.try_get("failure_reason")?,
        expires_at: r
            .try_get::<Option<DateTime<Utc>>, _>("expires_at")?
            .map(|dt| dt.to_rfc3339()),
        created_at: r.try_get::<DateTime<Utc>, _>("created_at")?.to_rfc3339(),
    }))
}

// Phase 8: Reorder DTOs
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ReorderUnavailableReason {
    ProductUnavailable,
    VariantUnavailable,
    CrustUnavailable,
    ToppingUnavailable,
    CheeseUnavailable,
    SauceUnavailable,
    StandardToppingUnavailable,
    HalfAndHalfUnavailable,
    HalfAndHalfConfigUnavailable,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReorderItemDto {
    pub order_item_id: String,
    pub product_id: String,
    pub product_name: String,
    #[serde(default)]
    pub variant_id: Option<String>,
    #[serde(default)]
    pub crust_id: Option<String>,
    #[serde(default)]
    pub cheese_id: Option<String>,
    #[serde(default)]
    pub sauce_id: Option<String>,
    #[serde(default)]
    pub topping_ids: Vec<String>,
    #[serde(default)]
    pub standard_topping_ids: Vec<String>,
    #[serde(default)]
    pub standard_topping_removed_ids: Vec<String>,
    #[serde(default)]
    pub half_and_half_config_id: Option<String>,
    #[serde(default)]
    pub half_and_half_left_topping_ids: Vec<String>,
    #[serde(default)]
    pub half_and_half_right_topping_ids: Vec<String>,
    pub quantity: i32,
    pub is_available: bool,
    #[serde(default)]
    pub issues: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReorderUnavailableDto {
    pub order_item_id: String,
    pub product_name: String,
    pub modifier_type: String,
    pub modifier_id: String,
    pub modifier_name: String,
    pub reason: ReorderUnavailableReason,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReorderResponseDto {
    pub order_id: String,
    pub can_reorder: bool,
    #[serde(default)]
    pub items: Vec<ReorderItemDto>,
    #[serde(default)]
    pub unavailable: Vec<ReorderUnavailableDto>,
    #[serde(default)]
    pub warnings: Vec<String>,
}

const fn order_status_as_str(s: OrderStatus) -> &'static str {
    match s {
        OrderStatus::PendingPayment => "PENDING_PAYMENT",
        OrderStatus::Placed => "PLACED",
        OrderStatus::Accepted => "ACCEPTED",
        OrderStatus::Preparing => "PREPARING",
        OrderStatus::Ready => "READY",
        OrderStatus::OutForDelivery => "OUT_FOR_DELIVERY",
        OrderStatus::Completed => "COMPLETED",
        OrderStatus::Cancelled => "CANCELLED",
        OrderStatus::Expired => "EXPIRED",
    }
}

fn parse_order_status(raw: &str) -> Result<OrderStatus, DbError> {
    match raw {
        "PENDING_PAYMENT" => Ok(OrderStatus::PendingPayment),
        "PLACED" => Ok(OrderStatus::Placed),
        "ACCEPTED" => Ok(OrderStatus::Accepted),
        "PREPARING" => Ok(OrderStatus::Preparing),
        "READY" => Ok(OrderStatus::Ready),
        "OUT_FOR_DELIVERY" => Ok(OrderStatus::OutForDelivery),
        "COMPLETED" => Ok(OrderStatus::Completed),
        "CANCELLED" => Ok(OrderStatus::Cancelled),
        "EXPIRED" => Ok(OrderStatus::Expired),
        other => Err(DbError::CatalogInvariantViolation(format!(
            "Unsupported order status '{other}'"
        ))),
    }
}

const fn fulfillment_type_as_str(f: FulfillmentType) -> &'static str {
    match f {
        FulfillmentType::Delivery => "DELIVERY",
        FulfillmentType::Pickup => "PICKUP",
    }
}

fn parse_fulfillment_type(raw: &str) -> Result<FulfillmentType, DbError> {
    match raw {
        "DELIVERY" => Ok(FulfillmentType::Delivery),
        "PICKUP" => Ok(FulfillmentType::Pickup),
        other => Err(DbError::CatalogInvariantViolation(format!(
            "Unsupported fulfillment type '{other}'"
        ))),
    }
}

fn parse_schedule_type(raw: &str) -> Result<ScheduleType, DbError> {
    match raw {
        "ASAP" => Ok(ScheduleType::Asap),
        "SCHEDULED" => Ok(ScheduleType::Scheduled),
        other => Err(DbError::CatalogInvariantViolation(format!(
            "Unsupported schedule type '{other}'"
        ))),
    }
}

fn parse_payment_method(raw: &str) -> Result<PaymentMethod, DbError> {
    match raw {
        "RAZORPAY" => Ok(PaymentMethod::Razorpay),
        "COD" => Ok(PaymentMethod::Cod),
        other => Err(DbError::CatalogInvariantViolation(format!(
            "Unsupported payment method '{other}'"
        ))),
    }
}

fn parse_payment_status(raw: &str) -> Result<PaymentStatus, DbError> {
    match raw {
        "UNPAID" => Ok(PaymentStatus::Unpaid),
        "COD_PENDING" => Ok(PaymentStatus::CodPending),
        "COD_COLLECTED" => Ok(PaymentStatus::CodCollected),
        "CAPTURED" => Ok(PaymentStatus::Captured),
        "EXPIRED" => Ok(PaymentStatus::Expired),
        "CANCELLED" => Ok(PaymentStatus::Cancelled),
        "REFUND_PENDING" => Ok(PaymentStatus::RefundPending),
        "PARTIALLY_REFUNDED" => Ok(PaymentStatus::PartiallyRefunded),
        "REFUNDED" => Ok(PaymentStatus::Refunded),
        other => Err(DbError::CatalogInvariantViolation(format!(
            "Unsupported payment status '{other}'"
        ))),
    }
}

fn parse_payment_attempt_status(raw: &str) -> Result<PaymentAttemptStatus, DbError> {
    match raw {
        "INITIATING" => Ok(PaymentAttemptStatus::Initiating),
        "AWAITING_CUSTOMER_PAYMENT" => Ok(PaymentAttemptStatus::AwaitingCustomerPayment),
        "AUTHORIZED" => Ok(PaymentAttemptStatus::Authorized),
        "CAPTURED" => Ok(PaymentAttemptStatus::Captured),
        "FAILED" => Ok(PaymentAttemptStatus::Failed),
        "EXPIRED" => Ok(PaymentAttemptStatus::Expired),
        "CANCELLED" => Ok(PaymentAttemptStatus::Cancelled),
        other => Err(DbError::CatalogInvariantViolation(format!(
            "Unsupported payment attempt status '{other}'"
        ))),
    }
}

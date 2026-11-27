use chrono::{DateTime, Utc};
use pizzora_core::OrderStatus;
use pizzora_db::{InventoryService, OrderListResponse, OrderSummaryDto};
use sqlx::{PgConnection, PgPool, Row as _};
use uuid::Uuid;

use crate::dto::{OwnerDashboardResponse, RestaurantSettingsDto};
use crate::error::{format_iso8601, now_iso8601, ApiError};
use crate::services::kitchen::{
    order_status_as_str, parse_order_status, parse_payment_method, parse_payment_status,
    parse_schedule_type,
};
use crate::services::slots::parse_fulfillment_type;

#[derive(Clone)]
pub struct OwnerService {
    pool: PgPool,
    inventory_service: InventoryService,
}

impl OwnerService {
    pub fn new(pool: PgPool) -> Self {
        let inventory_service = InventoryService::new(pool.clone());
        Self {
            pool,
            inventory_service,
        }
    }

    pub async fn get_dashboard(&self) -> Result<OwnerDashboardResponse, ApiError> {
        let all_inventory = self.inventory_service.list_inventory().await?;
        let low_stock_items: Vec<_> = all_inventory
            .into_iter()
            .filter(|item| item.is_low_stock)
            .collect();

        let mut tx = self.pool.begin().await?;
        let settings_row = sqlx::query(
            r#"
            SELECT store_name, is_accepting_orders, gst_bps, default_prep_mins, timezone, updated_at
            FROM restaurant_settings
            WHERE id = 1
            "#,
        )
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "RESTAURANT_SETTINGS_MISSING",
                "restaurant_settings row (id=1) is missing.",
            )
        })?;
        let settings_updated_at: DateTime<Utc> = settings_row.try_get("updated_at")?;
        let settings = RestaurantSettingsDto {
            store_name: settings_row.try_get("store_name")?,
            is_accepting_orders: settings_row.try_get("is_accepting_orders")?,
            gst_bps: settings_row.try_get("gst_bps")?,
            default_prep_mins: settings_row.try_get("default_prep_mins")?,
            timezone: settings_row.try_get("timezone")?,
            updated_at: format_iso8601(settings_updated_at),
        };

        let kpi_row = sqlx::query(
            r#"
            SELECT
              COALESCE(SUM(
                CASE
                  WHEN (o.created_at AT TIME ZONE 'Asia/Kolkata')::date = (NOW() AT TIME ZONE 'Asia/Kolkata')::date
                       AND o.status NOT IN ('CANCELLED', 'EXPIRED', 'PENDING_PAYMENT')
                  THEN GREATEST(0, o.total_paise - COALESCE(p.refunded_paise, 0))
                  ELSE 0
                END
              ), 0)::bigint AS today_revenue_paise,
              COUNT(*) FILTER (
                WHERE (o.created_at AT TIME ZONE 'Asia/Kolkata')::date = (NOW() AT TIME ZONE 'Asia/Kolkata')::date
                  AND o.status NOT IN ('CANCELLED', 'EXPIRED')
              )::int AS today_order_count,
              COUNT(*) FILTER (
                WHERE o.status IN ('PLACED', 'ACCEPTED', 'PREPARING', 'READY', 'OUT_FOR_DELIVERY')
              )::int AS active_order_count,
              COUNT(*) FILTER (
                WHERE o.status = 'PENDING_PAYMENT'
              )::int AS pending_payment_count,
              COUNT(*) FILTER (
                WHERE (o.created_at AT TIME ZONE 'Asia/Kolkata')::date = (NOW() AT TIME ZONE 'Asia/Kolkata')::date
                  AND o.status = 'COMPLETED'
              )::int AS completed_today_count
            FROM orders o
            LEFT JOIN payments p ON p.order_id = o.id
            "#,
        )
        .fetch_one(&mut *tx)
        .await?;

        let recent_orders = query_orders(&mut tx, None, 10).await?;
        tx.commit().await?;

        Ok(OwnerDashboardResponse {
            today_revenue_paise: kpi_row.try_get("today_revenue_paise")?,
            today_order_count: kpi_row.try_get("today_order_count")?,
            active_order_count: kpi_row.try_get("active_order_count")?,
            pending_payment_count: kpi_row.try_get("pending_payment_count")?,
            completed_today_count: kpi_row.try_get("completed_today_count")?,
            low_stock_count: low_stock_items.len() as i32,
            low_stock_items,
            recent_orders,
            settings,
            server_now: now_iso8601(),
        })
    }

    pub async fn list_orders(
        &self,
        status_filter: Option<OrderStatus>,
        limit: i64,
    ) -> Result<OrderListResponse, ApiError> {
        let clamped = limit.clamp(1, 100);
        let mut tx = self.pool.begin().await?;
        let orders = query_orders(&mut tx, status_filter, clamped).await?;
        tx.commit().await?;
        Ok(OrderListResponse { orders })
    }
}

async fn query_orders(
    conn: &mut PgConnection,
    status_filter: Option<OrderStatus>,
    limit: i64,
) -> Result<Vec<OrderSummaryDto>, ApiError> {
    let rows = match status_filter {
        Some(status) => {
            sqlx::query(
                r#"
                SELECT o.id, o.order_number, o.fulfillment_type, o.schedule_type, o.status, o.version,
                       o.total_paise, o.is_priority, o.created_at, o.expires_at,
                       p.method AS payment_method, p.status AS payment_status,
                       COALESCE((SELECT SUM(oi.quantity)::int FROM order_items oi WHERE oi.order_id = o.id), 0) AS item_count
                FROM orders o
                JOIN payments p ON p.order_id = o.id
                WHERE o.status = $1
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT $2
                "#,
            )
            .bind(order_status_as_str(status))
            .bind(limit)
            .fetch_all(&mut *conn)
            .await?
        }
        None => {
            sqlx::query(
                r#"
                SELECT o.id, o.order_number, o.fulfillment_type, o.schedule_type, o.status, o.version,
                       o.total_paise, o.is_priority, o.created_at, o.expires_at,
                       p.method AS payment_method, p.status AS payment_status,
                       COALESCE((SELECT SUM(oi.quantity)::int FROM order_items oi WHERE oi.order_id = o.id), 0) AS item_count
                FROM orders o
                JOIN payments p ON p.order_id = o.id
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT $1
                "#,
            )
            .bind(limit)
            .fetch_all(&mut *conn)
            .await?
        }
    };

    let mut orders = Vec::with_capacity(rows.len());
    for row in rows {
        let id: Uuid = row.try_get("id")?;
        let ft_raw: String = row.try_get("fulfillment_type")?;
        let sched_raw: String = row.try_get("schedule_type")?;
        let status_raw: String = row.try_get("status")?;
        let method_raw: String = row.try_get("payment_method")?;
        let pay_status_raw: String = row.try_get("payment_status")?;
        let created_at: DateTime<Utc> = row.try_get("created_at")?;
        let expires_at: Option<DateTime<Utc>> = row.try_get("expires_at")?;

        orders.push(OrderSummaryDto {
            id: id.to_string(),
            order_number: row.try_get("order_number")?,
            fulfillment_type: parse_fulfillment_type(&ft_raw)?,
            schedule_type: parse_schedule_type(&sched_raw)?,
            status: parse_order_status(&status_raw)?,
            version: row.try_get("version")?,
            total_paise: row.try_get("total_paise")?,
            item_count: row.try_get("item_count")?,
            payment_method: parse_payment_method(&method_raw)?,
            payment_status: parse_payment_status(&pay_status_raw)?,
            is_priority: row.try_get("is_priority")?,
            created_at: format_iso8601(created_at),
            expires_at: expires_at.map(format_iso8601),
            item_preview_summary: None,
        });
    }

    Ok(orders)
}

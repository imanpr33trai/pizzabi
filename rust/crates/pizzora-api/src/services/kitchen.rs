use std::collections::HashMap;

use chrono::{DateTime, Utc};
use pizzora_core::{
    FulfillmentType, OrderStatus, PaymentMethod, PaymentStatus, ScheduleType, UserRole,
};
use pizzora_db::{record_audit_log, AuditContext, DbError, OrderService, OutboxRepository};
use sqlx::{PgConnection, PgPool, Row as _};
use uuid::Uuid;

use crate::dto::{KdsTicketActionResponse, KdsTicketDto, KdsTicketItemDto, KdsTicketsResponse};
use crate::error::{format_iso8601, now_iso8601, ApiError};
use crate::services::slots::parse_fulfillment_type;

#[derive(Clone)]
pub struct KitchenService {
    pool: PgPool,
    order_service: OrderService,
}

impl KitchenService {
    pub fn new(pool: PgPool) -> Self {
        let order_service = OrderService::new(pool.clone());
        Self {
            pool,
            order_service,
        }
    }

    pub async fn list_tickets(
        &self,
        include_completed: bool,
        status_filter: Option<OrderStatus>,
        limit: i64,
    ) -> Result<KdsTicketsResponse, ApiError> {
        let safe_limit = limit.clamp(1, 200);
        let statuses: Vec<String> = match status_filter {
            Some(status) => vec![order_status_as_str(status).to_owned()],
            None if include_completed => vec![
                "PLACED".to_owned(),
                "ACCEPTED".to_owned(),
                "PREPARING".to_owned(),
                "READY".to_owned(),
                "OUT_FOR_DELIVERY".to_owned(),
                "COMPLETED".to_owned(),
            ],
            None => vec![
                "PLACED".to_owned(),
                "ACCEPTED".to_owned(),
                "PREPARING".to_owned(),
                "READY".to_owned(),
                "OUT_FOR_DELIVERY".to_owned(),
            ],
        };

        let include_recent_completed_only = include_completed && status_filter.is_none();
        let mut tx = self.pool.begin().await?;
        let prep_sla_minutes = load_default_prep_mins(&mut tx).await?;

        let sql = if include_recent_completed_only {
            r#"
            SELECT o.id, o.order_number, u.full_name AS customer_name, u.phone AS customer_phone,
                   o.fulfillment_type, o.schedule_type, o.status, o.version, o.is_priority,
                   p.method AS payment_method, p.status AS payment_status, o.total_paise,
                   o.delivery_address_snapshot,
                   o.accepted_at, o.prep_started_at, o.ready_at, o.completed_at, o.created_at
            FROM orders o
            JOIN users u ON u.id = o.customer_id
            JOIN payments p ON p.order_id = o.id
            WHERE o.status = ANY($1)
              AND (o.status <> 'COMPLETED' OR o.completed_at >= NOW() - INTERVAL '2 hours')
            ORDER BY CASE WHEN o.status = 'COMPLETED' THEN 1 ELSE 0 END ASC,
                     o.is_priority DESC,
                     o.created_at ASC,
                     o.id ASC
            LIMIT $2
            "#
        } else {
            r#"
            SELECT o.id, o.order_number, u.full_name AS customer_name, u.phone AS customer_phone,
                   o.fulfillment_type, o.schedule_type, o.status, o.version, o.is_priority,
                   p.method AS payment_method, p.status AS payment_status, o.total_paise,
                   o.delivery_address_snapshot,
                   o.accepted_at, o.prep_started_at, o.ready_at, o.completed_at, o.created_at
            FROM orders o
            JOIN users u ON u.id = o.customer_id
            JOIN payments p ON p.order_id = o.id
            WHERE o.status = ANY($1)
            ORDER BY CASE WHEN o.status = 'COMPLETED' THEN 1 ELSE 0 END ASC,
                     o.is_priority DESC,
                     o.created_at ASC,
                     o.id ASC
            LIMIT $2
            "#
        };

        let rows = sqlx::query(sql)
            .bind(&statuses)
            .bind(safe_limit)
            .fetch_all(&mut *tx)
            .await?;

        let mut headers = Vec::with_capacity(rows.len());
        let mut order_ids = Vec::with_capacity(rows.len());
        for row in &rows {
            let hdr = row_to_ticket_header(row)?;
            order_ids.push(hdr.uuid);
            headers.push(hdr);
        }

        let mut items_map = load_ticket_items_map(&mut tx, &order_ids).await?;
        let mut tickets = Vec::with_capacity(headers.len());
        let mut priority_count = 0i32;
        for hdr in headers {
            if hdr.is_priority && !hdr.status.is_terminal() {
                priority_count += 1;
            }
            let items = items_map.remove(&hdr.uuid).unwrap_or_default();
            tickets.push(hdr.into_dto(items));
        }

        tx.commit().await?;

        Ok(KdsTicketsResponse {
            tickets,
            server_now: now_iso8601(),
            prep_sla_minutes,
            priority_count,
        })
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn transition_ticket(
        &self,
        order_id: Uuid,
        to_status: OrderStatus,
        actor_user_id: Uuid,
        actor_role: UserRole,
        reason: Option<&str>,
        audit: &AuditContext,
        outbox_event_type: &str,
    ) -> Result<KdsTicketActionResponse, ApiError> {
        self.order_service
            .transition_order_status(
                order_id,
                to_status,
                actor_user_id,
                actor_role,
                reason,
                audit,
                outbox_event_type,
            )
            .await?;

        let mut tx = self.pool.begin().await?;
        let prep_sla_minutes = load_default_prep_mins(&mut tx).await?;
        let ticket = load_single_ticket(&mut tx, order_id)
            .await?
            .ok_or_else(|| {
                ApiError::not_found(
                    "ORDER_NOT_FOUND",
                    format!("Order {order_id} was not found."),
                )
            })?;
        tx.commit().await?;

        Ok(KdsTicketActionResponse {
            ticket,
            server_now: now_iso8601(),
            prep_sla_minutes,
        })
    }

    pub async fn update_priority(
        &self,
        order_id: Uuid,
        is_priority: bool,
        actor_user_id: Uuid,
        reason: Option<&str>,
        audit: &AuditContext,
    ) -> Result<KdsTicketActionResponse, ApiError> {
        let mut tx = self.pool.begin().await?;
        let locked = sqlx::query(
            r#"
            SELECT id, customer_id, order_number, status, version, is_priority
            FROM orders
            WHERE id = $1
            FOR UPDATE
            "#,
        )
        .bind(order_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "ORDER_NOT_FOUND",
                format!("Order {order_id} was not found."),
            )
        })?;

        let customer_id: Uuid = locked.try_get("customer_id")?;
        let order_number: String = locked.try_get("order_number")?;
        let status_raw: String = locked.try_get("status")?;
        let status = parse_order_status(&status_raw)?;
        let version: i64 = locked.try_get("version")?;
        let current_priority: bool = locked.try_get("is_priority")?;

        if status.is_terminal() || status == OrderStatus::PendingPayment {
            return Err(ApiError::conflict(
                "INVALID_PRIORITY_STATE",
                format!(
                    "Cannot change priority for order {order_number} while in status {status_raw}.",
                ),
            ));
        }

        if current_priority != is_priority {
            let new_version = version + 1;
            sqlx::query("UPDATE orders SET is_priority = $1, version = $2 WHERE id = $3")
                .bind(is_priority)
                .bind(new_version)
                .bind(order_id)
                .execute(&mut *tx)
                .await?;

            let clean_reason: Option<String> = reason
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(|s| s.chars().take(250).collect());

            let mut payload = serde_json::json!({
                "order_id": order_id.to_string(),
                "order_number": order_number,
                "customer_id": customer_id.to_string(),
                "status": status_raw,
                "is_priority": is_priority,
                "changed_by": actor_user_id.to_string(),
            });
            if let Some(ref clean) = clean_reason {
                if let Some(obj) = payload.as_object_mut() {
                    obj.insert(
                        "reason".to_owned(),
                        serde_json::Value::String(clean.clone()),
                    );
                }
            }

            OutboxRepository::insert_outbox_event(
                &mut tx,
                "ORDER",
                order_id,
                new_version,
                "ORDER_PRIORITY_CHANGED",
                &payload,
            )
            .await?;

            let before_val = serde_json::json!({
                "is_priority": current_priority,
                "version": version,
            });
            let after_val = serde_json::json!({
                "is_priority": is_priority,
                "version": new_version,
            });
            let entity_id = order_id.to_string();
            record_audit_log(
                &mut tx,
                audit,
                "ORDER_PRIORITY_UPDATED",
                "order",
                &entity_id,
                Some(&before_val),
                Some(&after_val),
            )
            .await?;
        }

        let prep_sla_minutes = load_default_prep_mins(&mut tx).await?;
        let ticket = load_single_ticket(&mut tx, order_id)
            .await?
            .ok_or_else(|| {
                ApiError::not_found(
                    "ORDER_NOT_FOUND",
                    format!("Order {order_id} was not found."),
                )
            })?;
        tx.commit().await?;

        Ok(KdsTicketActionResponse {
            ticket,
            server_now: now_iso8601(),
            prep_sla_minutes,
        })
    }
}

struct TicketHeaderRow {
    uuid: Uuid,
    order_number: String,
    customer_name: String,
    customer_phone: Option<String>,
    fulfillment_type: FulfillmentType,
    schedule_type: ScheduleType,
    status: OrderStatus,
    version: i64,
    is_priority: bool,
    payment_method: PaymentMethod,
    payment_status: PaymentStatus,
    total_paise: i64,
    delivery_area: Option<String>,
    accepted_at: Option<String>,
    prep_started_at: Option<String>,
    ready_at: Option<String>,
    completed_at: Option<String>,
    created_at: String,
}

impl TicketHeaderRow {
    fn into_dto(self, items: Vec<KdsTicketItemDto>) -> KdsTicketDto {
        KdsTicketDto {
            id: self.uuid.to_string(),
            order_number: self.order_number,
            customer_name: self.customer_name,
            customer_phone: self.customer_phone,
            fulfillment_type: self.fulfillment_type,
            schedule_type: self.schedule_type,
            status: self.status,
            version: self.version,
            is_priority: self.is_priority,
            payment_method: self.payment_method,
            payment_status: self.payment_status,
            total_paise: self.total_paise,
            delivery_area: self.delivery_area,
            accepted_at: self.accepted_at,
            prep_started_at: self.prep_started_at,
            ready_at: self.ready_at,
            completed_at: self.completed_at,
            created_at: self.created_at,
            items,
        }
    }
}

async fn load_single_ticket(
    conn: &mut PgConnection,
    order_id: Uuid,
) -> Result<Option<KdsTicketDto>, ApiError> {
    let row = sqlx::query(
        r#"
        SELECT o.id, o.order_number, u.full_name AS customer_name, u.phone AS customer_phone,
               o.fulfillment_type, o.schedule_type, o.status, o.version, o.is_priority,
               p.method AS payment_method, p.status AS payment_status, o.total_paise,
               o.delivery_address_snapshot,
               o.accepted_at, o.prep_started_at, o.ready_at, o.completed_at, o.created_at
        FROM orders o
        JOIN users u ON u.id = o.customer_id
        JOIN payments p ON p.order_id = o.id
        WHERE o.id = $1
        "#,
    )
    .bind(order_id)
    .fetch_optional(&mut *conn)
    .await?;

    let Some(row) = row else {
        return Ok(None);
    };
    let header = row_to_ticket_header(&row)?;
    let mut items_map = load_ticket_items_map(&mut *conn, &[order_id]).await?;
    let items = items_map.remove(&order_id).unwrap_or_default();
    Ok(Some(header.into_dto(items)))
}

async fn load_ticket_items_map(
    conn: &mut PgConnection,
    order_ids: &[Uuid],
) -> Result<HashMap<Uuid, Vec<KdsTicketItemDto>>, ApiError> {
    if order_ids.is_empty() {
        return Ok(HashMap::new());
    }

    let rows = sqlx::query(
        r#"
        SELECT id, order_id, product_id, product_name_snapshot, variant_size_snapshot,
               crust_name_snapshot, toppings_snapshot, quantity
        FROM order_items
        WHERE order_id = ANY($1)
        ORDER BY order_id ASC, id ASC
        "#,
    )
    .bind(order_ids)
    .fetch_all(&mut *conn)
    .await?;

    let mut map: HashMap<Uuid, Vec<KdsTicketItemDto>> = HashMap::new();
    for row in rows {
        let id: Uuid = row.try_get("id")?;
        let order_id: Uuid = row.try_get("order_id")?;
        let product_id: Uuid = row.try_get("product_id")?;
        let toppings_val: serde_json::Value = row.try_get("toppings_snapshot")?;
        let toppings = serde_json::from_value::<Vec<String>>(toppings_val).unwrap_or_default();

        map.entry(order_id).or_default().push(KdsTicketItemDto {
            id: id.to_string(),
            product_id: product_id.to_string(),
            product_name: row.try_get("product_name_snapshot")?,
            variant_size: row.try_get("variant_size_snapshot")?,
            crust_name: row.try_get("crust_name_snapshot")?,
            toppings,
            quantity: row.try_get("quantity")?,
        });
    }

    Ok(map)
}

async fn load_default_prep_mins(conn: &mut PgConnection) -> Result<i32, ApiError> {
    let val: Option<i32> =
        sqlx::query_scalar("SELECT default_prep_mins FROM restaurant_settings WHERE id = 1")
            .fetch_optional(&mut *conn)
            .await?;
    Ok(val.unwrap_or(20))
}

fn row_to_ticket_header(row: &sqlx::postgres::PgRow) -> Result<TicketHeaderRow, ApiError> {
    let uuid: Uuid = row.try_get("id")?;
    let ft_raw: String = row.try_get("fulfillment_type")?;
    let sched_raw: String = row.try_get("schedule_type")?;
    let status_raw: String = row.try_get("status")?;
    let method_raw: String = row.try_get("payment_method")?;
    let pay_status_raw: String = row.try_get("payment_status")?;
    let addr_val: Option<serde_json::Value> = row.try_get("delivery_address_snapshot")?;

    let delivery_area = addr_val.and_then(|v| {
        serde_json::from_value::<pizzora_db::DeliveryAddressSnapshotDto>(v)
            .ok()
            .and_then(|addr| {
                let mut parts = Vec::new();
                if !addr.line1.trim().is_empty() {
                    parts.push(addr.line1.trim().to_owned());
                }
                if !addr.pincode.trim().is_empty() {
                    parts.push(addr.pincode.trim().to_owned());
                }
                if parts.is_empty() {
                    None
                } else {
                    Some(parts.join(" • "))
                }
            })
    });

    let accepted_at: Option<DateTime<Utc>> = row.try_get("accepted_at")?;
    let prep_started_at: Option<DateTime<Utc>> = row.try_get("prep_started_at")?;
    let ready_at: Option<DateTime<Utc>> = row.try_get("ready_at")?;
    let completed_at: Option<DateTime<Utc>> = row.try_get("completed_at")?;
    let created_at: DateTime<Utc> = row.try_get("created_at")?;

    Ok(TicketHeaderRow {
        uuid,
        order_number: row.try_get("order_number")?,
        customer_name: row.try_get("customer_name")?,
        customer_phone: row.try_get("customer_phone")?,
        fulfillment_type: parse_fulfillment_type(&ft_raw)?,
        schedule_type: parse_schedule_type(&sched_raw)?,
        status: parse_order_status(&status_raw)?,
        version: row.try_get("version")?,
        is_priority: row.try_get("is_priority")?,
        payment_method: parse_payment_method(&method_raw)?,
        payment_status: parse_payment_status(&pay_status_raw)?,
        total_paise: row.try_get("total_paise")?,
        delivery_area,
        accepted_at: accepted_at.map(format_iso8601),
        prep_started_at: prep_started_at.map(format_iso8601),
        ready_at: ready_at.map(format_iso8601),
        completed_at: completed_at.map(format_iso8601),
        created_at: format_iso8601(created_at),
    })
}

pub fn order_status_as_str(status: OrderStatus) -> &'static str {
    match status {
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

pub fn parse_order_status(raw: &str) -> Result<OrderStatus, ApiError> {
    match raw.trim().to_ascii_uppercase().as_str() {
        "PENDING_PAYMENT" => Ok(OrderStatus::PendingPayment),
        "PLACED" => Ok(OrderStatus::Placed),
        "ACCEPTED" => Ok(OrderStatus::Accepted),
        "PREPARING" => Ok(OrderStatus::Preparing),
        "READY" => Ok(OrderStatus::Ready),
        "OUT_FOR_DELIVERY" => Ok(OrderStatus::OutForDelivery),
        "COMPLETED" => Ok(OrderStatus::Completed),
        "CANCELLED" => Ok(OrderStatus::Cancelled),
        "EXPIRED" => Ok(OrderStatus::Expired),
        other => Err(ApiError::validation(
            "INVALID_STATUS",
            format!("Invalid order status filter '{other}'."),
        )),
    }
}

pub fn parse_schedule_type(raw: &str) -> Result<ScheduleType, ApiError> {
    match raw.trim().to_ascii_uppercase().as_str() {
        "ASAP" => Ok(ScheduleType::Asap),
        "SCHEDULED" => Ok(ScheduleType::Scheduled),
        other => Err(ApiError::from(DbError::CatalogInvariantViolation(format!(
            "Unsupported schedule_type '{other}'"
        )))),
    }
}

pub fn parse_payment_method(raw: &str) -> Result<PaymentMethod, ApiError> {
    match raw.trim().to_ascii_uppercase().as_str() {
        "RAZORPAY" => Ok(PaymentMethod::Razorpay),
        "COD" => Ok(PaymentMethod::Cod),
        other => Err(ApiError::from(DbError::CatalogInvariantViolation(format!(
            "Unsupported payment_method '{other}'"
        )))),
    }
}

pub fn parse_payment_status(raw: &str) -> Result<PaymentStatus, ApiError> {
    match raw.trim().to_ascii_uppercase().as_str() {
        "UNPAID" => Ok(PaymentStatus::Unpaid),
        "COD_PENDING" => Ok(PaymentStatus::CodPending),
        "COD_COLLECTED" => Ok(PaymentStatus::CodCollected),
        "CAPTURED" => Ok(PaymentStatus::Captured),
        "EXPIRED" => Ok(PaymentStatus::Expired),
        "CANCELLED" => Ok(PaymentStatus::Cancelled),
        "REFUND_PENDING" => Ok(PaymentStatus::RefundPending),
        "PARTIALLY_REFUNDED" => Ok(PaymentStatus::PartiallyRefunded),
        "REFUNDED" => Ok(PaymentStatus::Refunded),
        other => Err(ApiError::from(DbError::CatalogInvariantViolation(format!(
            "Unsupported payment_status '{other}'"
        )))),
    }
}

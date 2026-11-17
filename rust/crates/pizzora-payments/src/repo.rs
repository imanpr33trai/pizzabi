use chrono::{DateTime, Utc};
use pizzora_core::{
    FulfillmentType, OrderStatus, PaymentAttemptStatus, PaymentMethod, PaymentStatus,
};
use pizzora_db::{
    new_uuid_v4, NotificationDeliveryStatus, NotificationService, OutboxRepository,
    EVENT_REFUND_REQUESTED,
};
use sqlx::{PgConnection, Row as _};
use uuid::Uuid;

use crate::dto::RefundDto;
use crate::error::PaymentError;
pub use crate::status_map::parse_refund_status;
use crate::status_map::{
    parse_fulfillment_type, parse_order_status, parse_payment_attempt_status, parse_payment_method,
    parse_payment_status, payment_status_as_str,
};

pub(crate) struct LockedOrder {
    pub id: Uuid,
    pub customer_id: Uuid,
    pub order_number: String,
    pub status: OrderStatus,
    pub version: i64,
    pub fulfillment_type: FulfillmentType,
    pub expires_at: Option<DateTime<Utc>>,
}

pub(crate) struct LockedPayment {
    pub id: Uuid,
    pub method: PaymentMethod,
    pub status: PaymentStatus,
    pub amount_paise: i64,
    pub captured_paise: i64,
    #[allow(dead_code)]
    pub refunded_paise: i64,
}

pub(crate) struct LockedAttempt {
    pub id: Uuid,
    pub status: PaymentAttemptStatus,
}

pub(crate) struct ExistingReceiptAttempt {
    #[allow(dead_code)]
    pub attempt_id: Uuid,
    pub order_id: Uuid,
    pub payment_id: Uuid,
    pub method: PaymentMethod,
    pub amount_paise: i64,
    pub retry_fingerprint: Option<String>,
}

pub(crate) async fn lock_order(
    conn: &mut PgConnection,
    order_id: Uuid,
) -> Result<LockedOrder, PaymentError> {
    let row = sqlx::query(
        r#"
        SELECT id, customer_id, order_number, status, version, fulfillment_type, expires_at
        FROM orders
        WHERE id = $1
        FOR UPDATE
        "#,
    )
    .bind(order_id)
    .fetch_optional(&mut *conn)
    .await?
    .ok_or_else(|| PaymentError::NotFound {
        code: "ORDER_NOT_FOUND",
        message: format!("Order {order_id} was not found."),
    })?;

    let status_raw: String = row.try_get("status")?;
    let fulfillment_raw: String = row.try_get("fulfillment_type")?;
    Ok(LockedOrder {
        id: row.try_get("id")?,
        customer_id: row.try_get("customer_id")?,
        order_number: row.try_get("order_number")?,
        status: parse_order_status(&status_raw)?,
        version: row.try_get("version")?,
        fulfillment_type: parse_fulfillment_type(&fulfillment_raw)?,
        expires_at: row.try_get("expires_at")?,
    })
}

pub(crate) async fn lock_payment_by_order(
    conn: &mut PgConnection,
    order_id: Uuid,
) -> Result<LockedPayment, PaymentError> {
    let row = sqlx::query(
        r#"
        SELECT id, method, status, amount_paise, captured_paise, refunded_paise
        FROM payments
        WHERE order_id = $1
        FOR UPDATE
        "#,
    )
    .bind(order_id)
    .fetch_optional(&mut *conn)
    .await?
    .ok_or_else(|| PaymentError::NotFound {
        code: "PAYMENT_NOT_FOUND",
        message: format!("Payment record for order {order_id} was not found."),
    })?;

    let method_raw: String = row.try_get("method")?;
    let status_raw: String = row.try_get("status")?;
    Ok(LockedPayment {
        id: row.try_get("id")?,
        method: parse_payment_method(&method_raw)?,
        status: parse_payment_status(&status_raw)?,
        amount_paise: row.try_get("amount_paise")?,
        captured_paise: row.try_get("captured_paise")?,
        refunded_paise: row.try_get("refunded_paise")?,
    })
}

pub(crate) async fn lock_attempt_by_id(
    conn: &mut PgConnection,
    attempt_id: Uuid,
) -> Result<LockedAttempt, PaymentError> {
    let row = sqlx::query("SELECT id, status FROM payment_attempts WHERE id = $1 FOR UPDATE")
        .bind(attempt_id)
        .fetch_optional(&mut *conn)
        .await?
        .ok_or_else(|| PaymentError::NotFound {
            code: "PAYMENT_ATTEMPT_NOT_FOUND",
            message: format!("Payment attempt {attempt_id} not found."),
        })?;

    let status_raw: String = row.try_get("status")?;
    Ok(LockedAttempt {
        id: row.try_get("id")?,
        status: parse_payment_attempt_status(&status_raw)?,
    })
}

pub(crate) async fn lock_refund_by_id(
    conn: &mut PgConnection,
    refund_id: Uuid,
) -> Result<Option<RefundDto>, PaymentError> {
    let row = sqlx::query(
        r#"
        SELECT id, payment_id, payment_attempt_id, order_id, idempotency_key, amount_paise,
               status, gateway_refund_id, reason, initiated_by, created_at, updated_at
        FROM refunds
        WHERE id = $1
        FOR UPDATE
        "#,
    )
    .bind(refund_id)
    .fetch_optional(&mut *conn)
    .await?;

    row.as_ref().map(row_to_refund_dto).transpose()
}

pub(crate) async fn find_attempt_by_receipt(
    conn: &mut PgConnection,
    receipt: &str,
) -> Result<Option<ExistingReceiptAttempt>, PaymentError> {
    let row = sqlx::query(
        r#"
        SELECT pa.id AS attempt_id,
               p.order_id,
               pa.payment_id,
               pa.method,
               pa.amount_paise,
               (
                 SELECT oe.payload_json->>'retry_fingerprint'
                 FROM outbox_events oe
                 WHERE oe.aggregate_id = p.order_id
                   AND oe.event_type = 'PAYMENT_GATEWAY_ORDER_REQUESTED'
                   AND oe.payload_json->>'payment_attempt_id' = pa.id::text
                 ORDER BY oe.created_at DESC
                 LIMIT 1
               ) AS retry_fingerprint
        FROM payment_attempts pa
        JOIN payments p ON p.id = pa.payment_id
        WHERE pa.idempotency_receipt = $1
        LIMIT 1
        "#,
    )
    .bind(receipt)
    .fetch_optional(&mut *conn)
    .await?;

    let Some(r) = row else {
        return Ok(None);
    };
    let method_raw: String = r.try_get("method")?;
    Ok(Some(ExistingReceiptAttempt {
        attempt_id: r.try_get("attempt_id")?,
        order_id: r.try_get("order_id")?,
        payment_id: r.try_get("payment_id")?,
        method: parse_payment_method(&method_raw)?,
        amount_paise: r.try_get("amount_paise")?,
        retry_fingerprint: r.try_get("retry_fingerprint")?,
    }))
}

#[allow(clippy::too_many_arguments)]
pub async fn insert_payment_event_idempotent(
    conn: &mut PgConnection,
    payment_id: Option<Uuid>,
    attempt_id: Option<Uuid>,
    source: &str,
    gateway_event_id: &str,
    event_type: &str,
    payload_json: &serde_json::Value,
    signature_valid: bool,
) -> Result<bool, PaymentError> {
    let inserted: Option<Uuid> = sqlx::query_scalar(
        r#"
        INSERT INTO payment_events (
            id, payment_id, payment_attempt_id, source, gateway_event_id,
            event_type, payload_json, signature_valid
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
        ON CONFLICT (gateway_event_id) DO NOTHING
        RETURNING id
        "#,
    )
    .bind(new_uuid_v4())
    .bind(payment_id)
    .bind(attempt_id)
    .bind(source)
    .bind(gateway_event_id)
    .bind(event_type)
    .bind(payload_json)
    .bind(signature_valid)
    .fetch_optional(&mut *conn)
    .await?;

    Ok(inserted.is_some())
}

#[allow(clippy::too_many_arguments)]
pub(crate) async fn insert_refund_and_outbox_if_absent(
    conn: &mut PgConnection,
    refund_id: Uuid,
    payment_id: Uuid,
    attempt_id: Option<Uuid>,
    order_id: Uuid,
    order_version: i64,
    idempotency_key: &str,
    amount_paise: i64,
    reason: &str,
    initiated_by: Option<Uuid>,
    gateway_payment_id: Option<&str>,
) -> Result<Option<Uuid>, PaymentError> {
    let inserted: Option<Uuid> = sqlx::query_scalar(
        r#"
        INSERT INTO refunds (
            id, payment_id, payment_attempt_id, order_id, idempotency_key,
            amount_paise, status, reason, initiated_by
        ) VALUES ($1, $2, $3, $4, $5, $6, 'PENDING', $7, $8)
        ON CONFLICT (idempotency_key) DO NOTHING
        RETURNING id
        "#,
    )
    .bind(refund_id)
    .bind(payment_id)
    .bind(attempt_id)
    .bind(order_id)
    .bind(idempotency_key)
    .bind(amount_paise)
    .bind(reason)
    .bind(initiated_by)
    .fetch_optional(&mut *conn)
    .await?;

    let Some(refund_uuid) = inserted else {
        return Ok(None);
    };

    let mut payload = serde_json::json!({
        "refund_id": refund_uuid.to_string(),
        "order_id": order_id.to_string(),
        "payment_id": payment_id.to_string(),
        "amount_paise": amount_paise,
        "idempotency_key": idempotency_key,
    });
    if let Some(obj) = payload.as_object_mut() {
        if let Some(aid) = attempt_id {
            obj.insert(
                "payment_attempt_id".to_owned(),
                serde_json::Value::String(aid.to_string()),
            );
        }
        if let Some(gpid) = gateway_payment_id {
            obj.insert(
                "gateway_payment_id".to_owned(),
                serde_json::Value::String(gpid.to_owned()),
            );
        }
    }

    OutboxRepository::insert_outbox_event(
        &mut *conn,
        "ORDER",
        order_id,
        order_version,
        EVENT_REFUND_REQUESTED,
        &payload,
    )
    .await?;

    Ok(Some(refund_uuid))
}

pub async fn mark_refund_processed_and_update_payment(
    conn: &mut PgConnection,
    refund_id: Uuid,
    payment_id: Uuid,
    gateway_refund_id: &str,
) -> Result<(), PaymentError> {
    sqlx::query(
        r#"
        UPDATE refunds
        SET status = 'PROCESSED',
            gateway_refund_id = COALESCE(gateway_refund_id, $1),
            updated_at = NOW()
        WHERE id = $2
        "#,
    )
    .bind(gateway_refund_id)
    .bind(refund_id)
    .execute(&mut *conn)
    .await?;

    let row = sqlx::query(
        r#"
        SELECT p.captured_paise,
               COALESCE(
                   (SELECT SUM(r.amount_paise)::bigint FROM refunds r WHERE r.payment_id = p.id AND r.status = 'PROCESSED'),
                   0::bigint
               ) AS processed_sum
        FROM payments p
        WHERE p.id = $1
        FOR UPDATE
        "#,
    )
    .bind(payment_id)
    .fetch_one(&mut *conn)
    .await?;

    let captured_paise: i64 = row.try_get("captured_paise")?;
    let processed_sum: i64 = row.try_get("processed_sum")?;
    let capped_refunded = processed_sum.min(captured_paise);
    let next_status = if capped_refunded >= captured_paise && captured_paise > 0 {
        PaymentStatus::Refunded
    } else {
        PaymentStatus::PartiallyRefunded
    };

    sqlx::query(
        "UPDATE payments SET refunded_paise = $1, status = $2, updated_at = NOW() WHERE id = $3",
    )
    .bind(capped_refunded)
    .bind(payment_status_as_str(next_status))
    .bind(payment_id)
    .execute(&mut *conn)
    .await?;

    Ok(())
}

pub async fn notify_owners(
    conn: &mut PgConnection,
    event_id: Uuid,
    title: &str,
    body: &str,
    category: &str,
    reference_id: Option<Uuid>,
) -> Result<(), PaymentError> {
    let owner_ids: Vec<Uuid> = sqlx::query_scalar(
        r#"
        SELECT s.user_id
        FROM staff s
        JOIN roles r ON r.id = s.role_id
        JOIN users u ON u.id = s.user_id
        WHERE r.code = 'OWNER' AND s.is_active = TRUE AND u.is_active = TRUE
        "#,
    )
    .fetch_all(&mut *conn)
    .await?;

    for owner_id in owner_ids {
        NotificationService::insert_inbox_notification_if_allowed_in_tx(
            &mut *conn,
            owner_id,
            event_id,
            title,
            body,
            category,
            reference_id,
            NotificationDeliveryStatus::Pending,
        )
        .await?;
    }

    Ok(())
}

pub(crate) async fn find_captured_attempt_id(
    conn: &mut PgConnection,
    payment_id: Uuid,
) -> Result<Option<Uuid>, PaymentError> {
    let id: Option<Uuid> = sqlx::query_scalar(
        r#"
        SELECT id FROM payment_attempts
        WHERE payment_id = $1 AND status = 'CAPTURED'
        ORDER BY attempt_number DESC
        LIMIT 1
        "#,
    )
    .bind(payment_id)
    .fetch_optional(&mut *conn)
    .await?;
    Ok(id)
}

pub async fn resolve_gateway_payment_id(
    conn: &mut PgConnection,
    payment_id: Uuid,
    attempt_id: Option<Uuid>,
) -> Result<Option<String>, PaymentError> {
    if let Some(aid) = attempt_id {
        let by_attempt: Option<Option<String>> =
            sqlx::query_scalar("SELECT gateway_payment_id FROM payment_attempts WHERE id = $1")
                .bind(aid)
                .fetch_optional(&mut *conn)
                .await?;
        if let Some(Some(gw_pay)) = by_attempt {
            if !gw_pay.trim().is_empty() {
                return Ok(Some(gw_pay));
            }
        }
    }

    let fallback: Option<String> = sqlx::query_scalar(
        r#"
        SELECT gateway_payment_id
        FROM payment_attempts
        WHERE payment_id = $1 AND gateway_payment_id IS NOT NULL
        ORDER BY attempt_number DESC
        LIMIT 1
        "#,
    )
    .bind(payment_id)
    .fetch_optional(&mut *conn)
    .await?;
    Ok(fallback)
}

pub(crate) fn row_to_refund_dto(row: &sqlx::postgres::PgRow) -> Result<RefundDto, PaymentError> {
    let id: Uuid = row.try_get("id")?;
    let payment_id: Uuid = row.try_get("payment_id")?;
    let payment_attempt_id: Option<Uuid> = row.try_get("payment_attempt_id")?;
    let order_id: Uuid = row.try_get("order_id")?;
    let status_raw: String = row.try_get("status")?;
    let initiated_by: Option<Uuid> = row.try_get("initiated_by")?;
    let created_at: DateTime<Utc> = row.try_get("created_at")?;
    let updated_at: DateTime<Utc> = row.try_get("updated_at")?;

    Ok(RefundDto {
        id: id.to_string(),
        payment_id: payment_id.to_string(),
        payment_attempt_id: payment_attempt_id.map(|u| u.to_string()),
        order_id: order_id.to_string(),
        idempotency_key: row.try_get("idempotency_key")?,
        amount_paise: row.try_get("amount_paise")?,
        status: parse_refund_status(&status_raw)?,
        gateway_refund_id: row.try_get("gateway_refund_id")?,
        reason: row.try_get("reason")?,
        initiated_by: initiated_by.map(|u| u.to_string()),
        created_at: created_at.to_rfc3339(),
        updated_at: updated_at.to_rfc3339(),
    })
}

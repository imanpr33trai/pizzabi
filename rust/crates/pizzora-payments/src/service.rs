use chrono::{DateTime, Utc};
use pizzora_config::RazorpayConfig;
use pizzora_core::{OrderStatus, PaymentMethod, PaymentStatus, UserRole};
use pizzora_db::{DbError, OrderDetailDto, OrderService};
use sqlx::{PgConnection, PgPool};
use tracing::warn;
use uuid::Uuid;

use crate::dto::OrderPaymentStatusResponse;
use crate::error::PaymentError;
use crate::repo::row_to_refund_dto;

#[derive(Clone)]
pub struct PaymentService {
    pub(crate) pool: PgPool,
    pub(crate) razorpay_config: RazorpayConfig,
    pub(crate) public_key_id_override: Option<String>,
}

impl PaymentService {
    pub fn new(pool: PgPool, razorpay_config: RazorpayConfig) -> Self {
        Self {
            pool,
            razorpay_config,
            public_key_id_override: None,
        }
    }

    pub fn with_public_key_id(
        pool: PgPool,
        razorpay_config: RazorpayConfig,
        public_key_id: impl Into<String>,
    ) -> Self {
        let key = public_key_id.into();
        Self {
            pool,
            razorpay_config,
            public_key_id_override: Some(key).filter(|s| !s.trim().is_empty()),
        }
    }

    pub fn pool(&self) -> &PgPool {
        &self.pool
    }

    pub(crate) fn effective_public_key_id(&self) -> Option<String> {
        self.public_key_id_override
            .as_deref()
            .filter(|s| !s.trim().is_empty())
            .or_else(|| {
                let k = self.razorpay_config.key_id.trim();
                (!k.is_empty()).then_some(k)
            })
            .map(str::to_owned)
    }

    pub async fn get_order_payment_status(
        &self,
        order_id: Uuid,
        actor_user_id: Uuid,
        actor_role: UserRole,
    ) -> Result<OrderPaymentStatusResponse, PaymentError> {
        let mut tx = self.pool.begin().await?;
        let detail = OrderService::load_order_detail_in_tx(&mut tx, order_id)
            .await?
            .ok_or_else(|| PaymentError::NotFound {
                code: "ORDER_NOT_FOUND",
                message: format!("Order {order_id} was not found."),
            })?;

        if actor_role == UserRole::Customer && detail.customer_id != actor_user_id.to_string() {
            warn!(
                actor_user_id = %actor_user_id,
                order_id = %order_id,
                "Blocked cross-user payment read IDOR"
            );
            return Err(PaymentError::Forbidden {
                code: "FORBIDDEN",
                message: "You do not have access to this order's payment.".to_owned(),
            });
        }

        let response = self
            .build_order_payment_status_response(&mut tx, detail)
            .await?;
        tx.commit().await?;
        Ok(response)
    }

    pub(crate) async fn build_order_payment_status_response(
        &self,
        conn: &mut PgConnection,
        detail: OrderDetailDto,
    ) -> Result<OrderPaymentStatusResponse, PaymentError> {
        let order_uuid = Uuid::parse_str(&detail.id).map_err(|_| {
            PaymentError::Db(DbError::CatalogInvariantViolation(
                "Invalid order detail UUID".to_owned(),
            ))
        })?;

        let rows = sqlx::query(
            r#"
            SELECT id, payment_id, payment_attempt_id, order_id, idempotency_key, amount_paise,
                   status, gateway_refund_id, reason, initiated_by, created_at, updated_at
            FROM refunds
            WHERE order_id = $1
            ORDER BY created_at DESC
            "#,
        )
        .bind(order_uuid)
        .fetch_all(&mut *conn)
        .await?;

        let mut refunds = Vec::with_capacity(rows.len());
        for row in rows {
            refunds.push(row_to_refund_dto(&row)?);
        }

        let expires_at_parsed = detail
            .expires_at
            .as_deref()
            .and_then(|s| DateTime::parse_from_rfc3339(s).ok())
            .map(|dt| dt.with_timezone(&Utc));

        let can_retry = detail.status == OrderStatus::PendingPayment
            && detail.payment.method == PaymentMethod::Razorpay
            && detail.payment.status == PaymentStatus::Unpaid
            && expires_at_parsed.is_none_or(|exp| exp > Utc::now());

        Ok(OrderPaymentStatusResponse {
            order_id: detail.id,
            order_number: detail.order_number,
            order_status: detail.status,
            payment: detail.payment,
            razorpay_key_id: self.effective_public_key_id(),
            can_retry,
            expires_at: detail.expires_at,
            refunds,
        })
    }
}

pub(crate) fn parse_uuid(raw: &str, field: &str) -> Result<Uuid, PaymentError> {
    Uuid::parse_str(raw.trim()).map_err(|_| PaymentError::Validation {
        code: "INVALID_UUID",
        message: format!("Invalid UUID for {field}."),
    })
}

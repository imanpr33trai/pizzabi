use pizzora_core::{
    FulfillmentType, OrderStatus, PaymentAttemptStatus, PaymentMethod, PaymentStatus, RefundStatus,
};
use pizzora_db::DbError;

use crate::error::PaymentError;

pub(crate) const fn order_status_as_str(s: OrderStatus) -> &'static str {
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

pub(crate) const fn fulfillment_type_as_str(f: FulfillmentType) -> &'static str {
    match f {
        FulfillmentType::Delivery => "DELIVERY",
        FulfillmentType::Pickup => "PICKUP",
    }
}

pub(crate) fn parse_order_status(raw: &str) -> Result<OrderStatus, PaymentError> {
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
        other => Err(PaymentError::Db(DbError::CatalogInvariantViolation(format!(
            "Unsupported order status '{other}'"
        )))),
    }
}

pub(crate) fn parse_fulfillment_type(raw: &str) -> Result<FulfillmentType, PaymentError> {
    match raw {
        "DELIVERY" => Ok(FulfillmentType::Delivery),
        "PICKUP" => Ok(FulfillmentType::Pickup),
        other => Err(PaymentError::Db(DbError::CatalogInvariantViolation(format!(
            "Unsupported fulfillment type '{other}'"
        )))),
    }
}

pub(crate) fn parse_payment_method(raw: &str) -> Result<PaymentMethod, PaymentError> {
    match raw {
        "RAZORPAY" => Ok(PaymentMethod::Razorpay),
        "COD" => Ok(PaymentMethod::Cod),
        other => Err(PaymentError::Db(DbError::CatalogInvariantViolation(format!(
            "Unsupported payment method '{other}'"
        )))),
    }
}

pub(crate) const fn payment_status_as_str(s: PaymentStatus) -> &'static str {
    match s {
        PaymentStatus::Unpaid => "UNPAID",
        PaymentStatus::CodPending => "COD_PENDING",
        PaymentStatus::CodCollected => "COD_COLLECTED",
        PaymentStatus::Captured => "CAPTURED",
        PaymentStatus::Expired => "EXPIRED",
        PaymentStatus::Cancelled => "CANCELLED",
        PaymentStatus::RefundPending => "REFUND_PENDING",
        PaymentStatus::PartiallyRefunded => "PARTIALLY_REFUNDED",
        PaymentStatus::Refunded => "REFUNDED",
    }
}

pub(crate) fn parse_payment_status(raw: &str) -> Result<PaymentStatus, PaymentError> {
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
        other => Err(PaymentError::Db(DbError::CatalogInvariantViolation(format!(
            "Unsupported payment status '{other}'"
        )))),
    }
}

pub(crate) fn parse_payment_attempt_status(raw: &str) -> Result<PaymentAttemptStatus, PaymentError> {
    match raw {
        "INITIATING" => Ok(PaymentAttemptStatus::Initiating),
        "AWAITING_CUSTOMER_PAYMENT" => Ok(PaymentAttemptStatus::AwaitingCustomerPayment),
        "AUTHORIZED" => Ok(PaymentAttemptStatus::Authorized),
        "CAPTURED" => Ok(PaymentAttemptStatus::Captured),
        "FAILED" => Ok(PaymentAttemptStatus::Failed),
        "EXPIRED" => Ok(PaymentAttemptStatus::Expired),
        "CANCELLED" => Ok(PaymentAttemptStatus::Cancelled),
        other => Err(PaymentError::Db(DbError::CatalogInvariantViolation(format!(
            "Unsupported payment attempt status '{other}'"
        )))),
    }
}

pub fn parse_refund_status(raw: &str) -> Result<RefundStatus, PaymentError> {
    match raw {
        "PENDING" => Ok(RefundStatus::Pending),
        "PROCESSING" => Ok(RefundStatus::Processing),
        "PROCESSED" => Ok(RefundStatus::Processed),
        "FAILED" => Ok(RefundStatus::Failed),
        other => Err(PaymentError::Db(DbError::CatalogInvariantViolation(format!(
            "Unsupported refund status '{other}'"
        )))),
    }
}

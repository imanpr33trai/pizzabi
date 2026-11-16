use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PaymentStatus {
    Unpaid,
    CodPending,
    CodCollected,
    Captured,
    Expired,
    Cancelled,
    RefundPending,
    PartiallyRefunded,
    Refunded,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PaymentAttemptStatus {
    Initiating,
    AwaitingCustomerPayment,
    Authorized,
    Captured,
    Failed,
    Expired,
    Cancelled,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RefundStatus {
    Pending,
    Processing,
    Processed,
    Failed,
}

pub struct PaymentStateMachine;

impl PaymentStateMachine {
    pub const fn can_transition_payment(from: PaymentStatus, to: PaymentStatus) -> bool {
        if Self::eq_payment(from, to) {
            return false;
        }
        match from {
            PaymentStatus::Unpaid => matches!(
                to,
                PaymentStatus::Captured | PaymentStatus::Expired | PaymentStatus::Cancelled
            ),
            PaymentStatus::CodPending => {
                matches!(to, PaymentStatus::CodCollected | PaymentStatus::Cancelled)
            }
            PaymentStatus::Captured => matches!(to, PaymentStatus::RefundPending),
            // Explicit exceptional late-capture refund transition when capture arrives after order EXPIRED/CANCELLED
            PaymentStatus::Expired | PaymentStatus::Cancelled => {
                matches!(to, PaymentStatus::RefundPending)
            }
            PaymentStatus::RefundPending => {
                matches!(
                    to,
                    PaymentStatus::PartiallyRefunded | PaymentStatus::Refunded
                )
            }
            PaymentStatus::PartiallyRefunded => {
                matches!(to, PaymentStatus::RefundPending | PaymentStatus::Refunded)
            }
            PaymentStatus::CodCollected | PaymentStatus::Refunded => false,
        }
    }

    pub const fn can_transition_attempt(
        from: PaymentAttemptStatus,
        to: PaymentAttemptStatus,
    ) -> bool {
        if Self::eq_attempt(from, to) {
            return false;
        }
        match from {
            PaymentAttemptStatus::Initiating => matches!(
                to,
                PaymentAttemptStatus::AwaitingCustomerPayment
                    | PaymentAttemptStatus::Failed
                    | PaymentAttemptStatus::Expired
                    | PaymentAttemptStatus::Cancelled
            ),
            PaymentAttemptStatus::AwaitingCustomerPayment => matches!(
                to,
                PaymentAttemptStatus::Authorized
                    | PaymentAttemptStatus::Captured
                    | PaymentAttemptStatus::Failed
                    | PaymentAttemptStatus::Expired
                    | PaymentAttemptStatus::Cancelled
            ),
            PaymentAttemptStatus::Authorized => matches!(
                to,
                PaymentAttemptStatus::Captured
                    | PaymentAttemptStatus::Failed
                    | PaymentAttemptStatus::Expired
                    | PaymentAttemptStatus::Cancelled
            ),
            PaymentAttemptStatus::Captured
            | PaymentAttemptStatus::Failed
            | PaymentAttemptStatus::Expired
            | PaymentAttemptStatus::Cancelled => false,
        }
    }

    const fn eq_payment(a: PaymentStatus, b: PaymentStatus) -> bool {
        matches!(
            (a, b),
            (PaymentStatus::Unpaid, PaymentStatus::Unpaid)
                | (PaymentStatus::CodPending, PaymentStatus::CodPending)
                | (PaymentStatus::CodCollected, PaymentStatus::CodCollected)
                | (PaymentStatus::Captured, PaymentStatus::Captured)
                | (PaymentStatus::Expired, PaymentStatus::Expired)
                | (PaymentStatus::Cancelled, PaymentStatus::Cancelled)
                | (PaymentStatus::RefundPending, PaymentStatus::RefundPending)
                | (
                    PaymentStatus::PartiallyRefunded,
                    PaymentStatus::PartiallyRefunded
                )
                | (PaymentStatus::Refunded, PaymentStatus::Refunded)
        )
    }

    const fn eq_attempt(a: PaymentAttemptStatus, b: PaymentAttemptStatus) -> bool {
        matches!(
            (a, b),
            (
                PaymentAttemptStatus::Initiating,
                PaymentAttemptStatus::Initiating
            ) | (
                PaymentAttemptStatus::AwaitingCustomerPayment,
                PaymentAttemptStatus::AwaitingCustomerPayment
            ) | (
                PaymentAttemptStatus::Authorized,
                PaymentAttemptStatus::Authorized
            ) | (
                PaymentAttemptStatus::Captured,
                PaymentAttemptStatus::Captured
            ) | (PaymentAttemptStatus::Failed, PaymentAttemptStatus::Failed)
                | (PaymentAttemptStatus::Expired, PaymentAttemptStatus::Expired)
                | (
                    PaymentAttemptStatus::Cancelled,
                    PaymentAttemptStatus::Cancelled
                )
        )
    }
}

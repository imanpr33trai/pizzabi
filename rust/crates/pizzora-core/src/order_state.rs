use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum UserRole {
    Customer,
    KitchenStaff,
    Owner,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum OrderStatus {
    PendingPayment,
    Placed,
    Accepted,
    Preparing,
    Ready,
    OutForDelivery,
    Completed,
    Cancelled,
    Expired,
}

impl OrderStatus {
    pub const fn is_terminal(self) -> bool {
        matches!(self, Self::Completed | Self::Cancelled | Self::Expired)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum FulfillmentType {
    Delivery,
    Pickup,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ScheduleType {
    Asap,
    Scheduled,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PaymentMethod {
    Razorpay,
    Cod,
}

pub struct OrderStateMachine;

impl OrderStateMachine {
    pub const fn can_customer_cancel(status: OrderStatus) -> bool {
        matches!(
            status,
            OrderStatus::PendingPayment | OrderStatus::Placed | OrderStatus::Accepted
        )
    }

    pub const fn can_owner_cancel(status: OrderStatus) -> bool {
        matches!(
            status,
            OrderStatus::PendingPayment
                | OrderStatus::Placed
                | OrderStatus::Accepted
                | OrderStatus::Preparing
                | OrderStatus::Ready
        )
    }

    pub const fn can_cancel(status: OrderStatus, actor_role: UserRole) -> bool {
        match actor_role {
            UserRole::Customer => Self::can_customer_cancel(status),
            UserRole::Owner => Self::can_owner_cancel(status),
            UserRole::KitchenStaff => false,
        }
    }

    pub const fn can_transition(
        from: OrderStatus,
        to: OrderStatus,
        fulfillment_type: FulfillmentType,
        actor_role: Option<UserRole>,
    ) -> bool {
        if from.is_terminal() || matches!((from, to), (a, b) if Self::eq_status(a, b)) {
            return false;
        }
        match to {
            OrderStatus::Placed => {
                matches!(from, OrderStatus::PendingPayment) && actor_role.is_none()
            }
            OrderStatus::Expired => {
                matches!(from, OrderStatus::PendingPayment) && actor_role.is_none()
            }
            OrderStatus::Accepted => {
                matches!(from, OrderStatus::Placed) && Self::is_kitchen_or_owner(actor_role)
            }
            OrderStatus::Preparing => {
                matches!(from, OrderStatus::Accepted) && Self::is_kitchen_or_owner(actor_role)
            }
            OrderStatus::Ready => {
                matches!(from, OrderStatus::Preparing) && Self::is_kitchen_or_owner(actor_role)
            }
            OrderStatus::OutForDelivery => {
                matches!(from, OrderStatus::Ready)
                    && matches!(fulfillment_type, FulfillmentType::Delivery)
                    && Self::is_kitchen_or_owner(actor_role)
            }
            OrderStatus::Completed => {
                Self::is_kitchen_or_owner(actor_role)
                    && ((matches!(from, OrderStatus::Ready)
                        && matches!(fulfillment_type, FulfillmentType::Pickup))
                        || (matches!(from, OrderStatus::OutForDelivery)
                            && matches!(fulfillment_type, FulfillmentType::Delivery)))
            }
            OrderStatus::Cancelled => match actor_role {
                Some(role) => Self::can_cancel(from, role),
                None => false,
            },
            OrderStatus::PendingPayment => false,
        }
    }

    pub const fn next_kds_status(
        status: OrderStatus,
        fulfillment_type: FulfillmentType,
    ) -> Option<OrderStatus> {
        match status {
            OrderStatus::Placed => Some(OrderStatus::Accepted),
            OrderStatus::Accepted => Some(OrderStatus::Preparing),
            OrderStatus::Preparing => Some(OrderStatus::Ready),
            OrderStatus::Ready => match fulfillment_type {
                FulfillmentType::Delivery => Some(OrderStatus::OutForDelivery),
                FulfillmentType::Pickup => Some(OrderStatus::Completed),
            },
            OrderStatus::OutForDelivery => Some(OrderStatus::Completed),
            _ => None,
        }
    }

    const fn is_kitchen_or_owner(actor_role: Option<UserRole>) -> bool {
        matches!(
            actor_role,
            Some(UserRole::KitchenStaff) | Some(UserRole::Owner)
        )
    }

    const fn eq_status(a: OrderStatus, b: OrderStatus) -> bool {
        matches!(
            (a, b),
            (OrderStatus::PendingPayment, OrderStatus::PendingPayment)
                | (OrderStatus::Placed, OrderStatus::Placed)
                | (OrderStatus::Accepted, OrderStatus::Accepted)
                | (OrderStatus::Preparing, OrderStatus::Preparing)
                | (OrderStatus::Ready, OrderStatus::Ready)
                | (OrderStatus::OutForDelivery, OrderStatus::OutForDelivery)
                | (OrderStatus::Completed, OrderStatus::Completed)
                | (OrderStatus::Cancelled, OrderStatus::Cancelled)
                | (OrderStatus::Expired, OrderStatus::Expired)
        )
    }
}

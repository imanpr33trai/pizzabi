use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SessionIdentity {
    pub epoch: i64,
    pub user_id: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CartSyncTrigger {
    PassiveBootstrap,
    ExplicitMutation,
    ExplicitClearOrRemove,
    PeriodicValidate,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CartSyncAction {
    /// Session changed or user mismatch -> discard immediately, never issue network write or touch Room.
    DiscardStaleSession,
    /// Fetch authoritative cart from `GET /api/v1/cart` without pushing local rows.
    FetchServerCart,
    /// Explicitly overwrite server cart with an empty cart (`PUT /api/v1/cart` with `[]`).
    ReplaceServerCartEmpty,
    /// Push non-empty local items via `PUT /api/v1/cart`, falling back to `POST /api/v1/cart/validate` on HTTP 422.
    ReplaceServerCartWithValidateFallback,
    /// Validate non-empty local items via `POST /api/v1/cart/validate` without persisting on server.
    ValidateCartOnly,
    /// Local cart is empty and caller requested validation-only -> no network call needed.
    NoOpEmptyLocal,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CartSyncPlanRequest {
    pub active_session: Option<SessionIdentity>,
    pub captured_session: SessionIdentity,
    pub trigger: CartSyncTrigger,
    pub local_item_count: usize,
    pub persist_to_server: bool,
    pub allow_empty_replace: bool,
}

pub struct CartSyncPlanner;

impl CartSyncPlanner {
    pub fn can_commit_for_session(
        active_session: Option<&SessionIdentity>,
        captured: &SessionIdentity,
    ) -> bool {
        matches!(
            active_session,
            Some(active) if active.epoch == captured.epoch && active.user_id == captured.user_id
        )
    }

    pub fn plan(request: &CartSyncPlanRequest) -> CartSyncAction {
        if !Self::can_commit_for_session(request.active_session.as_ref(), &request.captured_session)
        {
            return CartSyncAction::DiscardStaleSession;
        }

        if request.trigger == CartSyncTrigger::PassiveBootstrap {
            // Passive login / bootstrap ALWAYS treats server state as authoritative and never uploads local rows.
            return CartSyncAction::FetchServerCart;
        }

        if request.local_item_count == 0 {
            let explicit_empty_allowed = request.allow_empty_replace
                && request.persist_to_server
                && request.trigger == CartSyncTrigger::ExplicitClearOrRemove;
            return if explicit_empty_allowed {
                CartSyncAction::ReplaceServerCartEmpty
            } else if request.persist_to_server {
                // Never issue an empty PUT replace unless explicitly clearing or removing the last item.
                CartSyncAction::FetchServerCart
            } else {
                CartSyncAction::NoOpEmptyLocal
            };
        }

        if request.persist_to_server {
            CartSyncAction::ReplaceServerCartWithValidateFallback
        } else {
            CartSyncAction::ValidateCartOnly
        }
    }

    pub fn fallback_action_on_replace_http_error(status_code: u16) -> Option<CartSyncAction> {
        if status_code == 422 {
            Some(CartSyncAction::ValidateCartOnly)
        } else {
            None
        }
    }
}

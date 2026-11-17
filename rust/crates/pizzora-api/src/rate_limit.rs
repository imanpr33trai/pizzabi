use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use pizzora_config::SecurityConfig;
use pizzora_db::EphemeralCacheStore;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RateLimitDecision {
    Allowed,
    Rejected { retry_after_secs: u64 },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RouteGroupQuota {
    pub group: &'static str,
    pub max_per_minute: u32,
}

#[derive(Clone)]
pub struct ResilientRateLimiter {
    cache: Arc<dyn EphemeralCacheStore>,
    enabled: bool,
    security: SecurityConfig,
    local_windows: Arc<Mutex<HashMap<String, (i64, Instant)>>>,
}

impl ResilientRateLimiter {
    pub fn new(cache: Arc<dyn EphemeralCacheStore>, security: SecurityConfig) -> Self {
        Self {
            cache,
            enabled: security.rate_limit_enabled,
            security,
            local_windows: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled
    }

    pub fn classify_route_group(&self, path: &str) -> Option<RouteGroupQuota> {
        if !path.starts_with("/api/v1/")
            || path == "/api/v1/health/live"
            || path == "/api/v1/health/ready"
            || path == "/api/v1/openapi.json"
            || matches!(
                path,
                "/api/v1/auth/register"
                    | "/api/v1/auth/resend-verification"
                    | "/api/v1/auth/login"
                    | "/api/v1/auth/forgot-password"
            )
        {
            return None;
        }

        let quota = if path.starts_with("/api/v1/webhooks/") {
            RouteGroupQuota {
                group: "webhooks",
                max_per_minute: self.security.webhooks_per_minute,
            }
        } else if path == "/api/v1/auth/verify-email" {
            RouteGroupQuota {
                group: "auth_verify",
                max_per_minute: self.security.auth_verify_per_minute,
            }
        } else if path == "/api/v1/auth/reset-password" {
            RouteGroupQuota {
                group: "auth_reset",
                max_per_minute: self.security.auth_reset_per_minute,
            }
        } else if path == "/api/v1/auth/refresh" {
            RouteGroupQuota {
                group: "auth_refresh",
                max_per_minute: self.security.auth_refresh_per_minute,
            }
        } else if path == "/api/v1/auth/ws-ticket" {
            RouteGroupQuota {
                group: "auth_ws_ticket",
                max_per_minute: self.security.auth_ws_ticket_per_minute,
            }
        } else if path == "/api/v1/checkout"
            || path.starts_with("/api/v1/payments/")
            || (path.starts_with("/api/v1/orders/")
                && (path.ends_with("/payments/retry")
                    || path.ends_with("/payments/fail")
                    || path.contains("/payments/")))
        {
            RouteGroupQuota {
                group: "checkout_payments",
                max_per_minute: self.security.checkout_payment_per_minute,
            }
        } else if path.starts_with("/api/v1/owner/") || path.starts_with("/api/v1/kds/") {
            RouteGroupQuota {
                group: "ops",
                max_per_minute: self.security.ops_per_minute,
            }
        } else if path.starts_with("/api/v1/catalog/")
            || path.starts_with("/api/v1/delivery-zones/")
            || path.starts_with("/api/v1/slots")
            || path == "/api/v1/config/public"
        {
            RouteGroupQuota {
                group: "public",
                max_per_minute: self.security.public_per_minute,
            }
        } else {
            RouteGroupQuota {
                group: "api",
                max_per_minute: self.security.api_default_per_minute,
            }
        };

        Some(quota)
    }

    pub async fn check(
        &self,
        key: &str,
        max_requests: u32,
        window_seconds: u64,
    ) -> RateLimitDecision {
        if !self.enabled {
            return RateLimitDecision::Allowed;
        }
        let window_secs = window_seconds.max(1);
        let full_key = format!("rl:{key}");
        let count = match self
            .cache
            .increment_within_window(&full_key, window_secs)
            .await
        {
            Some(c) => c,
            None => self.increment_local_window(&full_key, window_secs),
        };

        if count <= i64::from(max_requests) {
            RateLimitDecision::Allowed
        } else {
            RateLimitDecision::Rejected {
                retry_after_secs: window_secs,
            }
        }
    }

    fn increment_local_window(&self, key: &str, window_secs: u64) -> i64 {
        let now = Instant::now();
        let Ok(mut map) = self.local_windows.lock() else {
            return 1;
        };

        if map.len() > 20_000 {
            map.retain(|_, (_, exp)| *exp > now);
        }

        match map.get_mut(key) {
            Some((count, exp)) if *exp > now => {
                *count += 1;
                *count
            }
            _ => {
                map.insert(key.to_owned(), (1, now + Duration::from_secs(window_secs)));
                1
            }
        }
    }
}

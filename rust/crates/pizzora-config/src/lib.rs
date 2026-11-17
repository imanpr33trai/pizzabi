//! Environment configuration and validation parity with Ktor `AppConfig`.

pub mod database_url;
pub mod outbox_crypto;
pub mod storage;

use std::collections::HashMap;
use std::fmt;

use thiserror::Error;
use url::{Host, Url};
use zeroize::{Zeroize, Zeroizing};

pub use database_url::{
    NormalizedDatabaseUrl, DEFAULT_DATABASE_PASSWORD, DEFAULT_DATABASE_URL, DEFAULT_DATABASE_USER,
};
pub use outbox_crypto::{OutboxEncryptionConfig, DEV_OUTBOX_KEY_B64, DEV_OUTBOX_KEY_ID};
pub use storage::StorageConfig;

pub const DEV_DEFAULT_JWT_SECRET: &str = "pizzora-dev-only-hmac-secret-minimum-32-bytes";
const DISALLOWED_PRODUCTION_JWT_SECRETS: &[&str] = &[
    DEV_DEFAULT_JWT_SECRET,
    "dev-only-change-before-production-32bytes",
    "changeme",
    "secret",
];

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum ConfigError {
    #[error("missing required configuration variable: {0}")]
    Missing(&'static str),
    #[error("invalid configuration for {var}: {reason}")]
    InvalidValue { var: &'static str, reason: String },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppConfig {
    pub environment: String,
    pub http_port: u16,
    pub shutdown_timeout_secs: u64,
    pub database: DatabaseConfig,
    pub redis: RedisConfig,
    pub storage: StorageConfig,
    pub smtp: SmtpConfig,
    pub auth: AuthConfig,
    pub outbox_encryption: OutboxEncryptionConfig,
    pub worker: WorkerConfig,
    pub razorpay: RazorpayConfig,
    pub security: SecurityConfig,
}

impl AppConfig {
    pub fn from_env() -> Result<Self, ConfigError> {
        let map: HashMap<String, String> = std::env::vars().collect();
        Self::from_env_map(&map)
    }

    pub fn is_production(&self) -> bool {
        self.environment.eq_ignore_ascii_case("production")
    }

    pub fn from_env_map(env: &HashMap<String, String>) -> Result<Self, ConfigError> {
        let environment = env
            .get("PIZZORA_ENV")
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
            .unwrap_or("development")
            .to_owned();
        let is_production = environment.eq_ignore_ascii_case("production");

        let http_port = parse_port(env, "PORT", 8080)?;
        let shutdown_timeout_secs = parse_u64_positive(env, "SHUTDOWN_TIMEOUT_SECS", 15)?;

        let db_url_raw = env
            .get("DATABASE_URL")
            .map(String::as_str)
            .filter(|s| !s.trim().is_empty())
            .unwrap_or(DEFAULT_DATABASE_URL);
        let explicit_db_user = env.get("DATABASE_USER").map(String::as_str);
        let explicit_db_password = env.get("DATABASE_PASSWORD").map(String::as_str);
        let normalized_db =
            NormalizedDatabaseUrl::parse(db_url_raw, explicit_db_user, explicit_db_password)?;

        if is_production
            && (normalized_db.password().trim().is_empty()
                || normalized_db.password() == DEFAULT_DATABASE_PASSWORD)
        {
            return Err(ConfigError::InvalidValue {
                var: "DATABASE_PASSWORD",
                reason: "must not use the development default or blank password in production"
                    .to_owned(),
            });
        }

        let max_pool_size = parse_u32_positive(env, "DATABASE_MAX_POOL_SIZE", 20)?;
        let min_connections = parse_u32_nonneg(env, "DATABASE_MIN_CONNECTIONS", 2)?;
        let min_connections = min_connections.min(max_pool_size);
        let connect_timeout_ms = parse_u64_positive(env, "DATABASE_CONNECT_TIMEOUT_MS", 5_000)?;
        let run_migrations_on_startup =
            parse_bool_optional(env, "RUN_MIGRATIONS_ON_STARTUP")?.unwrap_or(!is_production);

        let database = DatabaseConfig {
            url: normalized_db,
            max_pool_size,
            min_connections,
            connect_timeout_ms,
            run_migrations_on_startup,
        };

        let redis_url_raw = env
            .get("REDIS_URL")
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
            .unwrap_or("redis://localhost:6379");
        let redis_timeout_ms = parse_u64_positive(env, "REDIS_TIMEOUT_MS", 500)?;
        let redis_required = parse_bool_optional(env, "REDIS_REQUIRED")?.unwrap_or(false);
        let redis = RedisConfig::parse(redis_url_raw, redis_timeout_ms, redis_required)?;

        let storage = StorageConfig::from_env_map(env, is_production)?;

        let smtp_port = parse_port(env, "SMTP_PORT", 1025)?;
        let smtp_starttls = parse_bool_optional(env, "SMTP_STARTTLS")?.unwrap_or(false);
        let smtp = SmtpConfig {
            host: env
                .get("SMTP_HOST")
                .map(|s| s.trim())
                .filter(|s| !s.is_empty())
                .unwrap_or("localhost")
                .to_owned(),
            port: smtp_port,
            from_address: env
                .get("SMTP_FROM")
                .map(|s| s.trim())
                .filter(|s| !s.is_empty())
                .unwrap_or("no-reply@pizzora.local")
                .to_owned(),
            username: env
                .get("SMTP_USERNAME")
                .map(|s| s.trim().to_owned())
                .filter(|s| !s.is_empty()),
            password: env
                .get("SMTP_PASSWORD")
                .filter(|s| !s.is_empty())
                .map(|s| Zeroizing::new(s.clone())),
            use_starttls: smtp_starttls,
        };

        let jwt_secret = match env
            .get("JWT_SECRET")
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
        {
            Some(s) => Zeroizing::new(s.to_owned()),
            None => {
                if is_production {
                    return Err(ConfigError::Missing("JWT_SECRET"));
                }
                Zeroizing::new(DEV_DEFAULT_JWT_SECRET.to_owned())
            }
        };

        if is_production {
            if jwt_secret.len() < 32
                || DISALLOWED_PRODUCTION_JWT_SECRETS
                    .iter()
                    .any(|disallowed| jwt_secret.as_str() == *disallowed)
            {
                return Err(ConfigError::InvalidValue {
                    var: "JWT_SECRET",
                    reason: "must be at least 32 characters and not a development placeholder in production".to_owned(),
                });
            }
        }

        let auth = AuthConfig {
            jwt_secret,
            jwt_issuer: env
                .get("JWT_ISSUER")
                .map(|s| s.trim())
                .filter(|s| !s.is_empty())
                .unwrap_or("pizzora-api")
                .to_owned(),
            access_token_ttl_seconds: parse_i64_positive(env, "JWT_ACCESS_TTL_SECONDS", 900)?,
            refresh_token_ttl_days: parse_i64_positive(env, "REFRESH_TOKEN_TTL_DAYS", 30)?,
            ws_ticket_ttl_seconds: parse_i64_positive(env, "WS_TICKET_TTL_SECONDS", 30)?,
        };

        let outbox_encryption = OutboxEncryptionConfig::from_env_map(env, is_production)?;

        let worker = WorkerConfig {
            health_port: parse_port(env, "WORKER_HEALTH_PORT", 8081)?,
            sequencer_poll_ms: parse_u64_positive(env, "WORKER_SEQUENCER_POLL_MS", 150)?,
            dispatcher_poll_ms: parse_u64_positive(env, "WORKER_DISPATCHER_POLL_MS", 150)?,
            expiry_sweep_ms: parse_u64_positive(env, "WORKER_EXPIRY_SWEEP_MS", 15_000)?,
            reconciler_poll_ms: parse_u64_positive(env, "WORKER_RECONCILER_POLL_MS", 30_000)?,
            max_attempts: parse_i32_positive(env, "WORKER_MAX_ATTEMPTS", 5)?,
            batch_size: parse_i32_positive(env, "WORKER_BATCH_SIZE", 10)?,
            lease_seconds: parse_i64_positive(env, "WORKER_LEASE_SECONDS", 30)?,
            outbox_retention_days: parse_i64_positive(env, "WORKER_OUTBOX_RETENTION_DAYS", 7)?,
        };

        let rzp_key_id = env
            .get("RAZORPAY_KEY_ID")
            .map(|s| s.trim().to_owned())
            .unwrap_or_default();
        let rzp_key_secret = Zeroizing::new(
            env.get("RAZORPAY_KEY_SECRET")
                .map(|s| s.trim().to_owned())
                .unwrap_or_default(),
        );
        let rzp_webhook_secret = Zeroizing::new(
            env.get("RAZORPAY_WEBHOOK_SECRET")
                .map(|s| s.trim().to_owned())
                .unwrap_or_default(),
        );

        if is_production {
            if rzp_key_id.is_empty() {
                return Err(ConfigError::Missing("RAZORPAY_KEY_ID"));
            }
            if rzp_key_secret.is_empty() {
                return Err(ConfigError::Missing("RAZORPAY_KEY_SECRET"));
            }
            if rzp_webhook_secret.is_empty() {
                return Err(ConfigError::Missing("RAZORPAY_WEBHOOK_SECRET"));
            }
        }

        let rzp_base_url = env
            .get("RAZORPAY_BASE_URL")
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
            .unwrap_or("https://api.razorpay.com/v1")
            .trim_end_matches('/')
            .to_owned();

        let razorpay = RazorpayConfig {
            key_id: rzp_key_id,
            key_secret: rzp_key_secret,
            webhook_secret: rzp_webhook_secret,
            base_url: rzp_base_url,
        };

        let security = SecurityConfig {
            max_json_body_bytes: parse_u64_positive(env, "MAX_JSON_BODY_BYTES", 262_144)?,
            max_webhook_body_bytes: parse_u64_positive(env, "MAX_WEBHOOK_BODY_BYTES", 524_288)?,
            max_ws_frame_bytes: parse_u64_positive(env, "MAX_WS_FRAME_BYTES", 65_536)?,
            rate_limit_enabled: parse_bool_optional(env, "RATE_LIMIT_ENABLED")?.unwrap_or(true),
            auth_verify_per_minute: 15,
            auth_reset_per_minute: 10,
            auth_refresh_per_minute: 30,
            auth_ws_ticket_per_minute: 30,
            checkout_payment_per_minute: 80,
            webhooks_per_minute: 120,
            ops_per_minute: 240,
            public_per_minute: 300,
            api_default_per_minute: 240,
        };

        Ok(Self {
            environment,
            http_port,
            shutdown_timeout_secs,
            database,
            redis,
            storage,
            smtp,
            auth,
            outbox_encryption,
            worker,
            razorpay,
            security,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DatabaseConfig {
    pub url: NormalizedDatabaseUrl,
    pub max_pool_size: u32,
    pub min_connections: u32,
    pub connect_timeout_ms: u64,
    pub run_migrations_on_startup: bool,
}

#[derive(Clone, PartialEq, Eq)]
pub struct RedisConfig {
    url: Zeroizing<String>,
    pub host: String,
    pub port: u16,
    pub timeout_ms: u64,
    pub required: bool,
}

impl RedisConfig {
    pub fn parse(raw_url: &str, timeout_ms: u64, required: bool) -> Result<Self, ConfigError> {
        let trimmed = raw_url.trim();
        let parsed = Url::parse(trimmed).map_err(|_| ConfigError::InvalidValue {
            var: "REDIS_URL",
            reason: "must be a valid redis:// or rediss:// URL".to_owned(),
        })?;
        let scheme = parsed.scheme().to_ascii_lowercase();
        if scheme != "redis" && scheme != "rediss" {
            return Err(ConfigError::InvalidValue {
                var: "REDIS_URL",
                reason: "unsupported scheme (expected redis:// or rediss://)".to_owned(),
            });
        }
        let host = match parsed.host() {
            Some(Host::Domain(d)) if !d.is_empty() => d.to_owned(),
            Some(Host::Ipv4(ip)) => ip.to_string(),
            Some(Host::Ipv6(ip)) => ip.to_string(),
            _ => {
                return Err(ConfigError::InvalidValue {
                    var: "REDIS_URL",
                    reason: "must include a valid host".to_owned(),
                });
            }
        };
        let port = parsed.port().unwrap_or(6379);
        Ok(Self {
            url: Zeroizing::new(trimmed.to_owned()),
            host,
            port,
            timeout_ms,
            required,
        })
    }

    pub fn url(&self) -> &str {
        &self.url
    }

    pub fn redacted_url(&self) -> String {
        let host_part = database_url::format_host_for_url(&self.host);
        let scheme = self.url.split("://").next().unwrap_or("redis");
        format!("{scheme}://{host_part}:{}", self.port)
    }
}

impl fmt::Debug for RedisConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("RedisConfig")
            .field("url", &self.redacted_url())
            .field("timeout_ms", &self.timeout_ms)
            .field("required", &self.required)
            .finish()
    }
}

impl Drop for RedisConfig {
    fn drop(&mut self) {
        self.url.zeroize();
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct SmtpConfig {
    pub host: String,
    pub port: u16,
    pub from_address: String,
    pub username: Option<String>,
    password: Option<Zeroizing<String>>,
    pub use_starttls: bool,
}

impl SmtpConfig {
    pub fn password(&self) -> Option<&str> {
        self.password.as_deref().map(String::as_str)
    }

    pub fn from_domain(&self) -> &str {
        self.from_address
            .rsplit_once('@')
            .map(|(_, d)| d.trim())
            .filter(|d| !d.is_empty())
            .unwrap_or("pizzora.local")
    }
}

impl fmt::Debug for SmtpConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SmtpConfig")
            .field("host", &self.host)
            .field("port", &self.port)
            .field("from_address", &self.from_address)
            .field("username", &self.username)
            .field("password_present", &self.password.is_some())
            .field("use_starttls", &self.use_starttls)
            .finish()
    }
}

impl Drop for SmtpConfig {
    fn drop(&mut self) {
        if let Some(pw) = &mut self.password {
            pw.zeroize();
        }
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct AuthConfig {
    jwt_secret: Zeroizing<String>,
    pub jwt_issuer: String,
    pub access_token_ttl_seconds: i64,
    pub refresh_token_ttl_days: i64,
    pub ws_ticket_ttl_seconds: i64,
}

impl AuthConfig {
    pub fn new(
        jwt_secret: impl Into<String>,
        jwt_issuer: impl Into<String>,
        access_token_ttl_seconds: i64,
        refresh_token_ttl_days: i64,
        ws_ticket_ttl_seconds: i64,
    ) -> Self {
        Self {
            jwt_secret: Zeroizing::new(jwt_secret.into()),
            jwt_issuer: jwt_issuer.into(),
            access_token_ttl_seconds,
            refresh_token_ttl_days,
            ws_ticket_ttl_seconds,
        }
    }

    pub fn jwt_secret(&self) -> &str {
        &self.jwt_secret
    }
}

impl fmt::Debug for AuthConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("AuthConfig")
            .field("jwt_secret", &"[REDACTED]")
            .field("jwt_issuer", &self.jwt_issuer)
            .field("access_token_ttl_seconds", &self.access_token_ttl_seconds)
            .field("refresh_token_ttl_days", &self.refresh_token_ttl_days)
            .field("ws_ticket_ttl_seconds", &self.ws_ticket_ttl_seconds)
            .finish()
    }
}

impl Drop for AuthConfig {
    fn drop(&mut self) {
        self.jwt_secret.zeroize();
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkerConfig {
    pub health_port: u16,
    pub sequencer_poll_ms: u64,
    pub dispatcher_poll_ms: u64,
    pub expiry_sweep_ms: u64,
    pub reconciler_poll_ms: u64,
    pub max_attempts: i32,
    pub batch_size: i32,
    pub lease_seconds: i64,
    pub outbox_retention_days: i64,
}

#[derive(Clone, PartialEq, Eq)]
pub struct RazorpayConfig {
    pub key_id: String,
    key_secret: Zeroizing<String>,
    webhook_secret: Zeroizing<String>,
    pub base_url: String,
}

impl RazorpayConfig {
    pub fn new(
        key_id: impl Into<String>,
        key_secret: impl Into<String>,
        webhook_secret: impl Into<String>,
        base_url: impl Into<String>,
    ) -> Self {
        Self {
            key_id: key_id.into(),
            key_secret: Zeroizing::new(key_secret.into()),
            webhook_secret: Zeroizing::new(webhook_secret.into()),
            base_url: base_url.into(),
        }
    }

    pub fn key_secret(&self) -> &str {
        &self.key_secret
    }

    pub fn webhook_secret(&self) -> &str {
        &self.webhook_secret
    }
}

impl fmt::Debug for RazorpayConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("RazorpayConfig")
            .field("key_id", &self.key_id)
            .field(
                "key_secret",
                &if self.key_secret.is_empty() {
                    ""
                } else {
                    "***"
                },
            )
            .field(
                "webhook_secret",
                &if self.webhook_secret.is_empty() {
                    ""
                } else {
                    "***"
                },
            )
            .field("base_url", &self.base_url)
            .finish()
    }
}

impl Drop for RazorpayConfig {
    fn drop(&mut self) {
        self.key_secret.zeroize();
        self.webhook_secret.zeroize();
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SecurityConfig {
    pub max_json_body_bytes: u64,
    pub max_webhook_body_bytes: u64,
    pub max_ws_frame_bytes: u64,
    pub rate_limit_enabled: bool,
    pub auth_verify_per_minute: u32,
    pub auth_reset_per_minute: u32,
    pub auth_refresh_per_minute: u32,
    pub auth_ws_ticket_per_minute: u32,
    pub checkout_payment_per_minute: u32,
    pub webhooks_per_minute: u32,
    pub ops_per_minute: u32,
    pub public_per_minute: u32,
    pub api_default_per_minute: u32,
}

fn parse_port(
    env: &HashMap<String, String>,
    var: &'static str,
    default: u16,
) -> Result<u16, ConfigError> {
    match env.get(var).map(|s| s.trim()).filter(|s| !s.is_empty()) {
        None => Ok(default),
        Some(raw) => {
            let parsed = raw.parse::<u16>().map_err(|_| ConfigError::InvalidValue {
                var,
                reason: "must be a valid TCP port (1..65535)".to_owned(),
            })?;
            if parsed == 0 {
                return Err(ConfigError::InvalidValue {
                    var,
                    reason: "must be between 1 and 65535".to_owned(),
                });
            }
            Ok(parsed)
        }
    }
}

fn parse_u64_positive(
    env: &HashMap<String, String>,
    var: &'static str,
    default: u64,
) -> Result<u64, ConfigError> {
    match env.get(var).map(|s| s.trim()).filter(|s| !s.is_empty()) {
        None => Ok(default),
        Some(raw) => {
            let parsed = raw.parse::<u64>().map_err(|_| ConfigError::InvalidValue {
                var,
                reason: "must be a positive integer".to_owned(),
            })?;
            if parsed == 0 {
                return Err(ConfigError::InvalidValue {
                    var,
                    reason: "must be greater than 0".to_owned(),
                });
            }
            Ok(parsed)
        }
    }
}

fn parse_u32_positive(
    env: &HashMap<String, String>,
    var: &'static str,
    default: u32,
) -> Result<u32, ConfigError> {
    match env.get(var).map(|s| s.trim()).filter(|s| !s.is_empty()) {
        None => Ok(default),
        Some(raw) => {
            let parsed = raw.parse::<u32>().map_err(|_| ConfigError::InvalidValue {
                var,
                reason: "must be a positive integer".to_owned(),
            })?;
            if parsed == 0 {
                return Err(ConfigError::InvalidValue {
                    var,
                    reason: "must be greater than 0".to_owned(),
                });
            }
            Ok(parsed)
        }
    }
}

fn parse_u32_nonneg(
    env: &HashMap<String, String>,
    var: &'static str,
    default: u32,
) -> Result<u32, ConfigError> {
    match env.get(var).map(|s| s.trim()).filter(|s| !s.is_empty()) {
        None => Ok(default),
        Some(raw) => raw.parse::<u32>().map_err(|_| ConfigError::InvalidValue {
            var,
            reason: "must be a non-negative integer".to_owned(),
        }),
    }
}

fn parse_i64_positive(
    env: &HashMap<String, String>,
    var: &'static str,
    default: i64,
) -> Result<i64, ConfigError> {
    match env.get(var).map(|s| s.trim()).filter(|s| !s.is_empty()) {
        None => Ok(default),
        Some(raw) => {
            let parsed = raw.parse::<i64>().map_err(|_| ConfigError::InvalidValue {
                var,
                reason: "must be a positive integer".to_owned(),
            })?;
            if parsed <= 0 {
                return Err(ConfigError::InvalidValue {
                    var,
                    reason: "must be greater than 0".to_owned(),
                });
            }
            Ok(parsed)
        }
    }
}

fn parse_i32_positive(
    env: &HashMap<String, String>,
    var: &'static str,
    default: i32,
) -> Result<i32, ConfigError> {
    match env.get(var).map(|s| s.trim()).filter(|s| !s.is_empty()) {
        None => Ok(default),
        Some(raw) => {
            let parsed = raw.parse::<i32>().map_err(|_| ConfigError::InvalidValue {
                var,
                reason: "must be a positive integer".to_owned(),
            })?;
            if parsed <= 0 {
                return Err(ConfigError::InvalidValue {
                    var,
                    reason: "must be greater than 0".to_owned(),
                });
            }
            Ok(parsed)
        }
    }
}

fn parse_bool_optional(
    env: &HashMap<String, String>,
    var: &'static str,
) -> Result<Option<bool>, ConfigError> {
    match env.get(var).map(|s| s.trim()).filter(|s| !s.is_empty()) {
        None => Ok(None),
        Some(raw) if raw.eq_ignore_ascii_case("true") => Ok(Some(true)),
        Some(raw) if raw.eq_ignore_ascii_case("false") => Ok(Some(false)),
        Some(raw) => Err(ConfigError::InvalidValue {
            var,
            reason: format!("must be 'true' or 'false', got '{raw}'"),
        }),
    }
}

pub fn crate_name() -> &'static str {
    env!("CARGO_PKG_NAME")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn prod_env() -> HashMap<String, String> {
        let mut map = HashMap::new();
        map.insert("PIZZORA_ENV".to_owned(), "production".to_owned());
        map.insert(
            "DATABASE_URL".to_owned(),
            "postgresql://prod_u:Str0ng!Pass@pg.internal:5432/pizzora?sslmode=require".to_owned(),
        );
        map.insert(
            "JWT_SECRET".to_owned(),
            "prod-signing-secret-with-more-than-32-chars".to_owned(),
        );
        map.insert(
            "OUTBOX_EMAIL_KEY_CURRENT_ID".to_owned(),
            "k2026_01".to_owned(),
        );
        map.insert(
            "OUTBOX_EMAIL_KEYS".to_owned(),
            "k2026_01:AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=".to_owned(),
        );
        map.insert("RAZORPAY_KEY_ID".to_owned(), "rzp_test_123".to_owned());
        map.insert("RAZORPAY_KEY_SECRET".to_owned(), "sec_test_123".to_owned());
        map.insert(
            "RAZORPAY_WEBHOOK_SECRET".to_owned(),
            "whsec_test_123".to_owned(),
        );
        map
    }

    #[test]
    fn development_defaults_load_cleanly_without_secrets() {
        let cfg = AppConfig::from_env_map(&HashMap::new()).expect("dev config should load");
        assert!(!cfg.is_production());
        assert_eq!(cfg.http_port, 8080);
        assert_eq!(cfg.database.url.host, "localhost");
        assert_eq!(cfg.database.url.port, 5432);
        assert_eq!(cfg.database.url.database, "pizzora");
        assert!(cfg.database.run_migrations_on_startup);
        assert!(cfg.storage.enabled);
    }

    #[test]
    fn normalizes_jdbc_postgres_ipv6_and_percent_encoded_credentials() {
        let parsed = NormalizedDatabaseUrl::parse(
            "jdbc:postgresql://user%40prod:p%40ss%2F%3A123@[::1]:5433/pizzora_prod?sslmode=verify-full&connect_timeout=5",
            None,
            None,
        )
        .expect("should parse complex URL");
        assert_eq!(parsed.username, "user@prod");
        assert_eq!(parsed.password(), "p@ss/:123");
        assert_eq!(parsed.host, "::1");
        assert_eq!(parsed.port, 5433);
        assert_eq!(parsed.database, "pizzora_prod");
        assert_eq!(parsed.ssl_mode.as_deref(), Some("verify-full"));
        assert!(!parsed.normalized_redacted_url().contains("p@ss"));
        assert!(!parsed.normalized_redacted_url().contains("p%40ss"));
        assert!(
            parsed
                .normalized_redacted_url()
                .starts_with("postgresql://user%40prod:***@%5B::1%5D")
                || parsed
                    .normalized_redacted_url()
                    .starts_with("postgresql://user%40prod:***@[::1]:5433/pizzora_prod")
        );
        assert!(parsed
            .normalized_postgres_url()
            .contains("p%40ss%2F%3A123@[::1]:5433/pizzora_prod"));
    }

    #[test]
    fn explicit_database_user_and_password_override_url_userinfo() {
        let parsed = NormalizedDatabaseUrl::parse(
            "postgres://url_user:url_pw@db.railway.internal:5432/railway",
            Some("override_user"),
            Some("override_pw"),
        )
        .expect("should override userinfo");
        assert_eq!(parsed.username, "override_user");
        assert_eq!(parsed.password(), "override_pw");
    }

    #[test]
    fn production_fails_fast_without_leaking_secrets_in_errors_or_debug() {
        let mut env = prod_env();
        env.remove("OUTBOX_EMAIL_KEYS");
        let err = AppConfig::from_env_map(&env).expect_err("must reject missing outbox keys");
        assert!(err.to_string().contains("OUTBOX_EMAIL_KEYS"));

        let mut bad_len = prod_env();
        bad_len.insert(
            "OUTBOX_EMAIL_KEYS".to_owned(),
            "k2026_01:c2hvcnQ=".to_owned(),
        );
        let err = AppConfig::from_env_map(&bad_len).expect_err("must reject <32-byte key");
        assert!(err.to_string().contains("OUTBOX_EMAIL_KEYS"));
        assert!(!err.to_string().contains("c2hvcnQ="));

        let mut valid_env = prod_env();
        valid_env.insert("SMTP_USERNAME".to_owned(), "smtp_user".to_owned());
        valid_env.insert("SMTP_PASSWORD".to_owned(), "smtp_secret_pass_99".to_owned());
        let ok = AppConfig::from_env_map(&valid_env).expect("valid prod config");
        let dbg = format!("{ok:?}");
        assert!(!dbg.contains("prod-signing-secret"));
        assert!(!dbg.contains("Str0ng!Pass"));
        assert!(!dbg.contains("sec_test_123"));
        assert!(!dbg.contains("whsec_test_123"));
        assert!(!dbg.contains("smtp_secret_pass_99"));
        assert!(!dbg.contains("AQEBAQE"));
    }

    #[test]
    fn s3_enabled_validation_matches_ktor_contract() {
        let mut env = prod_env();
        let cfg = AppConfig::from_env_map(&env).expect("S3 omitted in prod -> disabled");
        assert!(!cfg.storage.enabled);

        env.insert("S3_ENABLED".to_owned(), "true".to_owned());
        env.insert(
            "S3_ENDPOINT".to_owned(),
            "https://s3.ap-south-1.amazonaws.com".to_owned(),
        );
        env.insert("S3_BUCKET".to_owned(), "<replace-me>".to_owned());
        let err = AppConfig::from_env_map(&env).expect_err("placeholder bucket rejected");
        assert!(err.to_string().contains("S3_BUCKET"));
    }
}

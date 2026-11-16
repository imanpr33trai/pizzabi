use std::fmt;

use url::{Host, Url};
use zeroize::{Zeroize, Zeroizing};

use crate::ConfigError;

pub const DEFAULT_DATABASE_URL: &str = "jdbc:postgresql://localhost:5432/pizzora";
pub const DEFAULT_DATABASE_USER: &str = "pizzora";
pub const DEFAULT_DATABASE_PASSWORD: &str = "pizzora_dev_password";

#[derive(Clone, PartialEq, Eq)]
pub struct NormalizedDatabaseUrl {
    pub host: String,
    pub port: u16,
    pub database: String,
    pub username: String,
    password: Zeroizing<String>,
    pub query_params: Vec<(String, String)>,
    pub ssl_mode: Option<String>,
    pub original_scheme: String,
}

impl NormalizedDatabaseUrl {
    /// Parses `jdbc:postgresql://`, `postgresql://`, and `postgres://` URLs with
    /// percent-decoded userinfo, IPv6 host handling, and explicit `DATABASE_USER` /
    /// `DATABASE_PASSWORD` override precedence.
    ///
    /// Precedence:
    /// 1. Non-empty explicit `DATABASE_USER` overrides URL username; otherwise URL username, else `"pizzora"`.
    /// 2. Non-empty explicit `DATABASE_PASSWORD` overrides URL password; otherwise URL password, else `"pizzora_dev_password"`.
    pub fn parse(
        raw_url: &str,
        explicit_user: Option<&str>,
        explicit_password: Option<&str>,
    ) -> Result<Self, ConfigError> {
        let trimmed = raw_url.trim();
        if trimmed.is_empty() {
            return Err(ConfigError::InvalidValue {
                var: "DATABASE_URL",
                reason: "must not be empty".to_owned(),
            });
        }

        let (original_scheme, url_to_parse) = if let Some(rest) = strip_prefix_ignore_ascii_case(trimmed, "jdbc:") {
            let lower = rest.to_ascii_lowercase();
            if !lower.starts_with("postgresql://") && !lower.starts_with("postgres://") {
                return Err(ConfigError::InvalidValue {
                    var: "DATABASE_URL",
                    reason: "unsupported JDBC URL scheme (expected jdbc:postgresql://)".to_owned(),
                });
            }
            ("jdbc:postgresql".to_owned(), rest.to_owned())
        } else {
            let lower = trimmed.to_ascii_lowercase();
            if lower.starts_with("postgresql://") {
                ("postgresql".to_owned(), trimmed.to_owned())
            } else if lower.starts_with("postgres://") {
                ("postgres".to_owned(), trimmed.to_owned())
            } else {
                return Err(ConfigError::InvalidValue {
                    var: "DATABASE_URL",
                    reason: "unsupported scheme (expected jdbc:postgresql://, postgresql://, or postgres://)".to_owned(),
                });
            }
        };

        let parsed = Url::parse(&url_to_parse).map_err(|_| ConfigError::InvalidValue {
            var: "DATABASE_URL",
            reason: "malformed PostgreSQL connection URL".to_owned(),
        })?;

        let host = match parsed.host() {
            Some(Host::Domain(d)) if !d.is_empty() => d.to_owned(),
            Some(Host::Ipv4(ip)) => ip.to_string(),
            Some(Host::Ipv6(ip)) => ip.to_string(),
            _ => {
                return Err(ConfigError::InvalidValue {
                    var: "DATABASE_URL",
                    reason: "must include a non-empty host".to_owned(),
                });
            }
        };

        let port = parsed.port().unwrap_or(5432);
        if port == 0 {
            return Err(ConfigError::InvalidValue {
                var: "DATABASE_URL",
                reason: "port must be between 1 and 65535".to_owned(),
            });
        }

        let raw_db = parsed.path().trim_start_matches('/');
        if raw_db.contains('/') {
            return Err(ConfigError::InvalidValue {
                var: "DATABASE_URL",
                reason: "database path must not contain extra '/' segments".to_owned(),
            });
        }
        let database = if raw_db.is_empty() {
            "pizzora".to_owned()
        } else {
            percent_decode_utf8(raw_db, "DATABASE_URL")?
        };

        let url_username = if parsed.username().is_empty() {
            None
        } else {
            Some(percent_decode_utf8(parsed.username(), "DATABASE_URL")?)
        };

        let url_password = match parsed.password() {
            Some(raw_pw) => Some(Zeroizing::new(percent_decode_utf8(raw_pw, "DATABASE_URL")?)),
            None => None,
        };

        let username = explicit_user
            .map(str::trim)
            .filter(|v| !v.is_empty())
            .map(str::to_owned)
            .or(url_username)
            .unwrap_or_else(|| DEFAULT_DATABASE_USER.to_owned());

        let password = explicit_password
            .filter(|v| !v.is_empty())
            .map(|v| Zeroizing::new(v.to_owned()))
            .or(url_password)
            .unwrap_or_else(|| Zeroizing::new(DEFAULT_DATABASE_PASSWORD.to_owned()));

        let query_params: Vec<(String, String)> = parsed
            .query_pairs()
            .map(|(k, v)| (k.into_owned(), v.into_owned()))
            .collect();

        let ssl_mode = query_params
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case("sslmode"))
            .map(|(_, v)| v.clone());

        Ok(Self {
            host,
            port,
            database,
            username,
            password,
            query_params,
            ssl_mode,
            original_scheme,
        })
    }

    pub fn password(&self) -> &str {
        &self.password
    }

    pub fn jdbc_url_without_credentials(&self) -> String {
        let host_part = format_host_for_url(&self.host);
        let mut out = format!("jdbc:postgresql://{host_part}:{}/{}", self.port, encode_uri_component(&self.database));
        append_query_string(&mut out, &self.query_params);
        out
    }

    /// Returns a normalized `postgresql://` connection string with credentials embedded.
    /// Wrapped in `Zeroizing<String>` so callers never accidentally retain or log it.
    pub fn normalized_postgres_url(&self) -> Zeroizing<String> {
        let host_part = format_host_for_url(&self.host);
        let user_enc = encode_uri_component(&self.username);
        let pass_enc = encode_uri_component(&self.password);
        let db_enc = encode_uri_component(&self.database);
        let mut out = format!("postgresql://{user_enc}:{pass_enc}@{host_part}:{}/{}", self.port, db_enc);
        append_query_string(&mut out, &self.query_params);
        Zeroizing::new(out)
    }

    /// Returns a safe redacted `postgresql://` string suitable for diagnostics and logs.
    pub fn normalized_redacted_url(&self) -> String {
        let host_part = format_host_for_url(&self.host);
        let user_enc = encode_uri_component(&self.username);
        let db_enc = encode_uri_component(&self.database);
        let mut out = format!("postgresql://{user_enc}:***@{host_part}:{}/{}", self.port, db_enc);
        append_query_string(&mut out, &self.query_params);
        out
    }
}

impl fmt::Debug for NormalizedDatabaseUrl {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("NormalizedDatabaseUrl")
            .field("url", &self.normalized_redacted_url())
            .field("original_scheme", &self.original_scheme)
            .finish()
    }
}

impl Drop for NormalizedDatabaseUrl {
    fn drop(&mut self) {
        self.password.zeroize();
    }
}

pub(crate) fn format_host_for_url(host: &str) -> String {
    if host.contains(':') && !host.starts_with('[') {
        format!("[{host}]")
    } else {
        host.to_owned()
    }
}

fn append_query_string(out: &mut String, params: &[(String, String)]) {
    if params.is_empty() {
        return;
    }
    out.push('?');
    for (idx, (k, v)) in params.iter().enumerate() {
        if idx > 0 {
            out.push('&');
        }
        out.push_str(&encode_uri_component(k));
        out.push('=');
        out.push_str(&encode_uri_component(v));
    }
}

fn strip_prefix_ignore_ascii_case<'a>(s: &'a str, prefix: &str) -> Option<&'a str> {
    if s.len() < prefix.len() {
        return None;
    }
    let (head, tail) = s.split_at(prefix.len());
    if head.eq_ignore_ascii_case(prefix) {
        Some(tail)
    } else {
        None
    }
}

pub(crate) fn percent_decode_utf8(input: &str, var: &'static str) -> Result<String, ConfigError> {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            if i + 2 >= bytes.len() {
                return Err(ConfigError::InvalidValue {
                    var,
                    reason: "incomplete percent-encoded sequence in URL".to_owned(),
                });
            }
            let hi = hex_val(bytes[i + 1]).ok_or_else(|| ConfigError::InvalidValue {
                var,
                reason: "invalid hex digit in URL percent-encoding".to_owned(),
            })?;
            let lo = hex_val(bytes[i + 2]).ok_or_else(|| ConfigError::InvalidValue {
                var,
                reason: "invalid hex digit in URL percent-encoding".to_owned(),
            })?;
            out.push((hi << 4) | lo);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).map_err(|_| ConfigError::InvalidValue {
        var,
        reason: "percent-encoded component is not valid UTF-8".to_owned(),
    })
}

fn hex_val(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

pub(crate) fn encode_uri_component(input: &str) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut out = String::with_capacity(input.len());
    for &b in input.as_bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            out.push(b as char);
        } else {
            out.push('%');
            out.push(HEX[(b >> 4) as usize] as char);
            out.push(HEX[(b & 0x0F) as usize] as char);
        }
    }
    out
}

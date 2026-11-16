use std::collections::HashMap;
use std::fmt;

use url::Url;
use zeroize::{Zeroize, Zeroizing};

use crate::ConfigError;

#[derive(Clone, PartialEq, Eq)]
pub struct StorageConfig {
    pub enabled: bool,
    pub endpoint: String,
    pub host: String,
    pub port: u16,
    pub bucket: String,
    pub region: String,
    pub access_key: String,
    secret_key: Zeroizing<String>,
    pub public_base_url: String,
}

impl StorageConfig {
    pub fn disabled() -> Self {
        Self {
            enabled: false,
            endpoint: String::new(),
            host: String::new(),
            port: 0,
            bucket: String::new(),
            region: String::new(),
            access_key: String::new(),
            secret_key: Zeroizing::new(String::new()),
            public_base_url: String::new(),
        }
    }

    pub fn secret_key(&self) -> &str {
        &self.secret_key
    }

    pub fn from_env_map(
        env: &HashMap<String, String>,
        is_production: bool,
    ) -> Result<Self, ConfigError> {
        let explicit_s3_enabled = match env.get("S3_ENABLED").map(|s| s.trim()).filter(|s| !s.is_empty()) {
            Some(raw) => {
                if raw.eq_ignore_ascii_case("true") {
                    Some(true)
                } else if raw.eq_ignore_ascii_case("false") {
                    Some(false)
                } else {
                    return Err(ConfigError::InvalidValue {
                        var: "S3_ENABLED",
                        reason: format!("must be 'true' or 'false', got '{raw}'"),
                    });
                }
            }
            None => None,
        };

        if explicit_s3_enabled == Some(false) || (explicit_s3_enabled.is_none() && is_production) {
            return Ok(Self::disabled());
        }

        if explicit_s3_enabled == Some(true) {
            return build_validated_storage_config(
                env.get("S3_ENDPOINT").map(String::as_str).unwrap_or(""),
                env.get("S3_BUCKET").map(String::as_str).unwrap_or(""),
                env.get("S3_REGION").map(String::as_str).unwrap_or(""),
                env.get("S3_ACCESS_KEY").map(String::as_str).unwrap_or(""),
                env.get("S3_SECRET_KEY").map(String::as_str).unwrap_or(""),
                env.get("S3_PUBLIC_URL").map(String::as_str),
                is_production,
            );
        }

        // Development mode with S3_ENABLED unspecified: enabled if valid defaults succeed, else disabled.
        let endpoint = env
            .get("S3_ENDPOINT")
            .map(String::as_str)
            .unwrap_or("http://localhost:9000");
        let bucket = env
            .get("S3_BUCKET")
            .map(String::as_str)
            .unwrap_or("pizzora-product-images");
        let region = env
            .get("S3_REGION")
            .map(String::as_str)
            .unwrap_or("ap-south-1");
        let access_key = env
            .get("S3_ACCESS_KEY")
            .map(String::as_str)
            .unwrap_or("pizzora_minio_admin");
        let secret_key = env
            .get("S3_SECRET_KEY")
            .map(String::as_str)
            .unwrap_or("pizzora_minio_dev_secret");

        Ok(build_validated_storage_config(
            endpoint,
            bucket,
            region,
            access_key,
            secret_key,
            env.get("S3_PUBLIC_URL").map(String::as_str),
            false,
        )
        .unwrap_or_else(|_| Self::disabled()))
    }
}

impl fmt::Debug for StorageConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("StorageConfig")
            .field("enabled", &self.enabled)
            .field("endpoint", &self.endpoint)
            .field("host", &self.host)
            .field("port", &self.port)
            .field("bucket", &self.bucket)
            .field("region", &self.region)
            .field("access_key_present", &(!self.access_key.is_empty()))
            .field("secret_key", &if self.secret_key.is_empty() { "" } else { "***" })
            .field("public_base_url", &self.public_base_url)
            .finish()
    }
}

impl Drop for StorageConfig {
    fn drop(&mut self) {
        self.secret_key.zeroize();
    }
}

fn build_validated_storage_config(
    endpoint: &str,
    bucket: &str,
    region: &str,
    access_key: &str,
    secret_key: &str,
    public_url: Option<&str>,
    reject_dev_defaults: bool,
) -> Result<StorageConfig, ConfigError> {
    let clean_endpoint = endpoint.trim().trim_end_matches('/').to_owned();
    let endpoint_uri = parse_validated_http_uri("S3_ENDPOINT", &clean_endpoint)?;

    let clean_bucket = bucket.trim().to_owned();
    if clean_bucket.is_empty() || contains_placeholder(&clean_bucket) {
        return Err(ConfigError::InvalidValue {
            var: "S3_BUCKET",
            reason: "must be configured with a valid non-placeholder bucket name when S3_ENABLED=true".to_owned(),
        });
    }

    let clean_region = region.trim().to_owned();
    if clean_region.is_empty() || contains_placeholder(&clean_region) {
        return Err(ConfigError::InvalidValue {
            var: "S3_REGION",
            reason: "must be configured with a valid non-placeholder region when S3_ENABLED=true".to_owned(),
        });
    }

    let clean_access_key = access_key.trim().to_owned();
    if clean_access_key.is_empty() || contains_placeholder(&clean_access_key) {
        return Err(ConfigError::InvalidValue {
            var: "S3_ACCESS_KEY",
            reason: "must be configured when S3_ENABLED=true".to_owned(),
        });
    }

    let clean_secret_key = Zeroizing::new(secret_key.trim().to_owned());
    if clean_secret_key.is_empty() || contains_placeholder(&clean_secret_key) {
        return Err(ConfigError::InvalidValue {
            var: "S3_SECRET_KEY",
            reason: "must be configured when S3_ENABLED=true".to_owned(),
        });
    }

    if reject_dev_defaults {
        if clean_access_key == "pizzora_minio_admin" {
            return Err(ConfigError::InvalidValue {
                var: "S3_ACCESS_KEY",
                reason: "must not use development default in production when S3_ENABLED=true".to_owned(),
            });
        }
        if clean_secret_key.as_str() == "pizzora_minio_dev_secret" {
            return Err(ConfigError::InvalidValue {
                var: "S3_SECRET_KEY",
                reason: "must not use development default in production when S3_ENABLED=true".to_owned(),
            });
        }
    }

    let resolved_public_url = match public_url.map(str::trim).filter(|s| !s.is_empty()) {
        Some(raw_public) => {
            let normalized = raw_public.trim_end_matches('/').to_owned();
            parse_validated_http_uri("S3_PUBLIC_URL", &normalized)?;
            normalized
        }
        None => format!("{clean_endpoint}/{clean_bucket}"),
    };

    let host = endpoint_uri
        .host_str()
        .ok_or_else(|| ConfigError::InvalidValue {
            var: "S3_ENDPOINT",
            reason: "must include a valid host when S3_ENABLED=true".to_owned(),
        })?
        .to_owned();

    let port = endpoint_uri.port_or_known_default().unwrap_or(80);

    Ok(StorageConfig {
        enabled: true,
        endpoint: clean_endpoint,
        host,
        port,
        bucket: clean_bucket,
        region: clean_region,
        access_key: clean_access_key,
        secret_key: clean_secret_key,
        public_base_url: resolved_public_url,
    })
}

fn parse_validated_http_uri(var: &'static str, raw: &str) -> Result<Url, ConfigError> {
    if raw.is_empty() || contains_placeholder(raw) {
        return Err(ConfigError::InvalidValue {
            var,
            reason: "must be configured with a valid http/https URL when S3_ENABLED=true".to_owned(),
        });
    }
    let uri = Url::parse(raw).map_err(|_| ConfigError::InvalidValue {
        var,
        reason: "is not a valid URI".to_owned(),
    })?;
    let scheme = uri.scheme().to_ascii_lowercase();
    if scheme != "http" && scheme != "https" {
        return Err(ConfigError::InvalidValue {
            var,
            reason: "must use http or https scheme when S3_ENABLED=true".to_owned(),
        });
    }
    if uri.host_str().map(str::is_empty).unwrap_or(true) {
        return Err(ConfigError::InvalidValue {
            var,
            reason: "must include a valid host when S3_ENABLED=true".to_owned(),
        });
    }
    Ok(uri)
}

fn contains_placeholder(value: &str) -> bool {
    value.contains('<') || value.contains('>')
}

use std::collections::{BTreeMap, HashMap};
use std::fmt;

use base64::engine::general_purpose::STANDARD;
use base64::Engine as _;
use zeroize::Zeroizing;

use crate::ConfigError;

pub const DEV_OUTBOX_KEY_ID: &str = "k_dev_local_01";
/// Deterministic 256-bit dev-only key used strictly when `PIZZORA_ENV != production`
/// and no outbox encryption env vars are provided. Rejected in production.
pub const DEV_OUTBOX_KEY_B64: &str = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

#[derive(Clone)]
pub struct OutboxEncryptionConfig {
    pub current_key_id: String,
    keys: BTreeMap<String, Zeroizing<[u8; 32]>>,
}

impl OutboxEncryptionConfig {
    pub fn from_env_map(
        env: &HashMap<String, String>,
        is_production: bool,
    ) -> Result<Self, ConfigError> {
        let raw_current = env
            .get("OUTBOX_EMAIL_KEY_CURRENT_ID")
            .map(|s| s.trim())
            .filter(|s| !s.is_empty());
        let raw_keys = env
            .get("OUTBOX_EMAIL_KEYS")
            .map(|s| s.trim())
            .filter(|s| !s.is_empty());

        if raw_current.is_none() && raw_keys.is_none() {
            if is_production {
                return Err(ConfigError::Missing("OUTBOX_EMAIL_KEY_CURRENT_ID"));
            }
            return Self::parse_from_raw(DEV_OUTBOX_KEY_ID, DEV_OUTBOX_KEY_B64_ENTRY, false);
        }

        let current_id = raw_current.ok_or(ConfigError::Missing("OUTBOX_EMAIL_KEY_CURRENT_ID"))?;
        let keys_spec = raw_keys.ok_or(ConfigError::Missing("OUTBOX_EMAIL_KEYS"))?;
        Self::parse_from_raw(current_id, keys_spec, is_production)
    }

    pub fn parse_from_raw(
        current_key_id: &str,
        keys_spec: &str,
        reject_dev_defaults: bool,
    ) -> Result<Self, ConfigError> {
        let current_id = current_key_id.trim();
        if current_id.is_empty() {
            return Err(ConfigError::Missing("OUTBOX_EMAIL_KEY_CURRENT_ID"));
        }
        validate_key_id(current_id, "OUTBOX_EMAIL_KEY_CURRENT_ID")?;

        let entries = keys_spec.trim();
        if entries.is_empty() {
            return Err(ConfigError::Missing("OUTBOX_EMAIL_KEYS"));
        }

        let dev_key_bytes = decode_256_bit_key(DEV_OUTBOX_KEY_B64)?;
        let mut keys = BTreeMap::new();

        for part in entries.split(',') {
            let trimmed = part.trim();
            if trimmed.is_empty() {
                continue;
            }
            let (kid_raw, b64_raw) = trimmed
                .split_once(':')
                .or_else(|| trimmed.split_once('='))
                .ok_or_else(|| ConfigError::InvalidValue {
                    var: "OUTBOX_EMAIL_KEYS",
                    reason: "entries must be formatted as '<kid>:<base64_32byte_key>'".to_owned(),
                })?;

            let kid = kid_raw.trim();
            validate_key_id(kid, "OUTBOX_EMAIL_KEYS")?;
            if keys.contains_key(kid) {
                return Err(ConfigError::InvalidValue {
                    var: "OUTBOX_EMAIL_KEYS",
                    reason: format!("duplicate key id '{kid}'"),
                });
            }

            let key_bytes = decode_256_bit_key(b64_raw.trim())?;
            if reject_dev_defaults && (kid == DEV_OUTBOX_KEY_ID || *key_bytes == *dev_key_bytes) {
                return Err(ConfigError::InvalidValue {
                    var: "OUTBOX_EMAIL_KEYS",
                    reason: "development default outbox encryption key is forbidden in production"
                        .to_owned(),
                });
            }
            keys.insert(kid.to_owned(), key_bytes);
        }

        if keys.is_empty() {
            return Err(ConfigError::Missing("OUTBOX_EMAIL_KEYS"));
        }
        if !keys.contains_key(current_id) {
            return Err(ConfigError::InvalidValue {
                var: "OUTBOX_EMAIL_KEY_CURRENT_ID",
                reason: format!("active key id '{current_id}' is not present in OUTBOX_EMAIL_KEYS"),
            });
        }

        Ok(Self {
            current_key_id: current_id.to_owned(),
            keys,
        })
    }

    pub fn current_key(&self) -> (&str, &[u8; 32]) {
        let key = self
            .keys
            .get(&self.current_key_id)
            .expect("validated active key_id must exist in keyring");
        (&self.current_key_id, key)
    }

    pub fn key_for_id(&self, key_id: &str) -> Option<&[u8; 32]> {
        self.keys.get(key_id).map(|k| &**k)
    }

    pub fn key_ids(&self) -> Vec<&str> {
        self.keys.keys().map(String::as_str).collect()
    }
}

impl fmt::Debug for OutboxEncryptionConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let kids: Vec<&str> = self.keys.keys().map(String::as_str).collect();
        f.debug_struct("OutboxEncryptionConfig")
            .field("current_key_id", &self.current_key_id)
            .field("configured_key_ids", &kids)
            .finish()
    }
}

const DEV_OUTBOX_KEY_B64_ENTRY: &str = "k_dev_local_01:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

fn validate_key_id(kid: &str, var: &'static str) -> Result<(), ConfigError> {
    if kid.is_empty()
        || kid.len() > 64
        || !kid
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b'.'))
    {
        return Err(ConfigError::InvalidValue {
            var,
            reason: "key id must be 1..64 ASCII alphanumeric, '_', '-', or '.' characters"
                .to_owned(),
        });
    }
    Ok(())
}

fn decode_256_bit_key(b64: &str) -> Result<Zeroizing<[u8; 32]>, ConfigError> {
    if b64.is_empty() {
        return Err(ConfigError::InvalidValue {
            var: "OUTBOX_EMAIL_KEYS",
            reason: "base64 key material must not be empty".to_owned(),
        });
    }
    let decoded = Zeroizing::new(STANDARD.decode(b64).map_err(|_| ConfigError::InvalidValue {
        var: "OUTBOX_EMAIL_KEYS",
        reason: "invalid base64 encoding in keyring entry".to_owned(),
    })?);
    if decoded.len() != 32 {
        return Err(ConfigError::InvalidValue {
            var: "OUTBOX_EMAIL_KEYS",
            reason: format!("expected 32-byte AES-256 key, got {} bytes", decoded.len()),
        });
    }
    let mut arr = Zeroizing::new([0u8; 32]);
    arr.copy_from_slice(&decoded);
    Ok(arr)
}

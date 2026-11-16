use std::fmt;

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::engine::general_purpose::STANDARD;
use base64::Engine as _;
use chrono::{DateTime, Utc};
use pizzora_config::OutboxEncryptionConfig;
use rand::rngs::OsRng;
use rand::RngCore as _;
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

use crate::error::AuthError;

pub const OUTBOX_ENVELOPE_ALG: &str = "AES-256-GCM";
pub const OUTBOX_NONCE_LEN: usize = 12;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EmailTemplateKind {
    Verification,
    PasswordReset,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SealedEmailEnvelope {
    pub kid: String,
    pub alg: String,
    pub nonce_b64: String,
    pub ciphertext_b64: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OutboxEmailPayload {
    pub template: EmailTemplateKind,
    pub user_id: String,
    pub token_record_id: String,
    pub token_expires_at: String,
    pub recipient_masked: String,
    pub sealed_envelope: Option<SealedEmailEnvelope>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub redacted: Option<bool>,
}

#[derive(Clone)]
pub struct SensitiveEmailSecrets {
    pub to_email: Zeroizing<String>,
    pub full_name: Zeroizing<String>,
    pub raw_token: Zeroizing<String>,
}

impl fmt::Debug for SensitiveEmailSecrets {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SensitiveEmailSecrets")
            .field("to_email", &"[REDACTED]")
            .field("full_name", &"[REDACTED]")
            .field("raw_token", &"[REDACTED]")
            .finish()
    }
}

#[derive(Serialize)]
struct PlainEmailSecretPayload<'a> {
    to_email: &'a str,
    full_name: &'a str,
    raw_token: &'a str,
}

#[derive(Deserialize, Zeroize, ZeroizeOnDrop)]
struct OwnedEmailSecretPayload {
    to_email: String,
    full_name: String,
    raw_token: String,
}

pub fn compute_outbox_aad(outbox_event_id: Uuid, user_id: Uuid, event_type: &str) -> String {
    format!("{outbox_event_id}:{user_id}:{event_type}")
}

pub fn mask_email(email: &str) -> String {
    let trimmed = email.trim();
    let Some((local, domain)) = trimmed.split_once('@') else {
        return "***".to_owned();
    };
    let mut chars = local.chars();
    let first = chars.next().unwrap_or('*');
    format!("{first}***@{domain}")
}

#[allow(clippy::too_many_arguments)]
pub fn seal_outbox_email_payload(
    config: &OutboxEncryptionConfig,
    outbox_event_id: Uuid,
    user_id: Uuid,
    event_type: &str,
    template: EmailTemplateKind,
    token_record_id: Uuid,
    token_expires_at: DateTime<Utc>,
    to_email: &str,
    full_name: &str,
    raw_token: &str,
) -> Result<OutboxEmailPayload, AuthError> {
    let (kid, key_bytes) = config.current_key();
    let cipher = Aes256Gcm::new_from_slice(key_bytes)
        .map_err(|_| AuthError::Crypto("invalid AES-256-GCM key length".to_owned()))?;

    let mut nonce_bytes = [0u8; OUTBOX_NONCE_LEN];
    OsRng.fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);

    let aad = compute_outbox_aad(outbox_event_id, user_id, event_type);
    let plaintext = Zeroizing::new(
        serde_json::to_vec(&PlainEmailSecretPayload {
            to_email,
            full_name,
            raw_token,
        })
        .map_err(|err| AuthError::Crypto(format!("failed to serialize email envelope: {err}")))?,
    );

    let ciphertext = cipher
        .encrypt(
            nonce,
            Payload {
                msg: plaintext.as_slice(),
                aad: aad.as_bytes(),
            },
        )
        .map_err(|_| AuthError::Crypto("AEAD encryption failed for email envelope".to_owned()))?;

    Ok(OutboxEmailPayload {
        template,
        user_id: user_id.to_string(),
        token_record_id: token_record_id.to_string(),
        token_expires_at: token_expires_at.to_rfc3339(),
        recipient_masked: mask_email(to_email),
        sealed_envelope: Some(SealedEmailEnvelope {
            kid: kid.to_owned(),
            alg: OUTBOX_ENVELOPE_ALG.to_owned(),
            nonce_b64: STANDARD.encode(nonce_bytes),
            ciphertext_b64: STANDARD.encode(ciphertext),
        }),
        redacted: None,
    })
}

pub fn unseal_outbox_email_payload(
    config: &OutboxEncryptionConfig,
    outbox_event_id: Uuid,
    event_type: &str,
    payload: &OutboxEmailPayload,
) -> Result<SensitiveEmailSecrets, AuthError> {
    if payload.redacted == Some(true) {
        return Err(AuthError::Crypto(
            "outbox email envelope has already been redacted".to_owned(),
        ));
    }
    let envelope = payload
        .sealed_envelope
        .as_ref()
        .ok_or_else(|| AuthError::Crypto("outbox email payload is missing sealed_envelope".to_owned()))?;

    if envelope.alg != OUTBOX_ENVELOPE_ALG {
        return Err(AuthError::Crypto(format!(
            "unsupported outbox envelope algorithm '{}'",
            envelope.alg
        )));
    }

    let key_bytes = config.key_for_id(&envelope.kid).ok_or_else(|| {
        AuthError::Crypto(format!(
            "outbox decryption key id '{}' is not configured",
            envelope.kid
        ))
    })?;

    let nonce_vec = STANDARD
        .decode(envelope.nonce_b64.as_bytes())
        .map_err(|_| AuthError::Crypto("invalid base64 nonce in outbox envelope".to_owned()))?;
    if nonce_vec.len() != OUTBOX_NONCE_LEN {
        return Err(AuthError::Crypto(format!(
            "invalid nonce length {} (expected {OUTBOX_NONCE_LEN})",
            nonce_vec.len()
        )));
    }
    let nonce = Nonce::from_slice(&nonce_vec);

    let ciphertext = STANDARD
        .decode(envelope.ciphertext_b64.as_bytes())
        .map_err(|_| AuthError::Crypto("invalid base64 ciphertext in outbox envelope".to_owned()))?;

    let user_id = Uuid::parse_str(&payload.user_id)
        .map_err(|_| AuthError::Crypto("invalid user_id UUID in outbox email payload".to_owned()))?;
    let aad = compute_outbox_aad(outbox_event_id, user_id, event_type);

    let cipher = Aes256Gcm::new_from_slice(key_bytes)
        .map_err(|_| AuthError::Crypto("invalid AES-256-GCM key length".to_owned()))?;
    let plaintext = Zeroizing::new(
        cipher
            .decrypt(
                nonce,
                Payload {
                    msg: ciphertext.as_slice(),
                    aad: aad.as_bytes(),
                },
            )
            .map_err(|_| {
                AuthError::Crypto(format!(
                    "AEAD authentication failed for outbox_event_id={outbox_event_id} kid={}",
                    envelope.kid
                ))
            })?,
    );

    let mut owned: OwnedEmailSecretPayload =
        serde_json::from_slice(plaintext.as_slice()).map_err(|_| {
            AuthError::Crypto("decrypted outbox email payload JSON is invalid".to_owned())
        })?;

    Ok(SensitiveEmailSecrets {
        to_email: Zeroizing::new(std::mem::take(&mut owned.to_email)),
        full_name: Zeroizing::new(std::mem::take(&mut owned.full_name)),
        raw_token: Zeroizing::new(std::mem::take(&mut owned.raw_token)),
    })
}

pub fn scrub_outbox_email_payload(payload: &OutboxEmailPayload) -> OutboxEmailPayload {
    OutboxEmailPayload {
        template: payload.template,
        user_id: payload.user_id.clone(),
        token_record_id: payload.token_record_id.clone(),
        token_expires_at: payload.token_expires_at.clone(),
        recipient_masked: payload.recipient_masked.clone(),
        sealed_envelope: None,
        redacted: Some(true),
    }
}

pub fn sanitize_error_for_logs(raw: &str, sensitive_values: &[&str]) -> String {
    let mut sanitized = raw.replace(['\r', '\n', '\t'], " ");
    for secret in sensitive_values {
        if !secret.is_empty() {
            sanitized = sanitized.replace(secret, "[REDACTED]");
        }
    }
    sanitized.chars().take(500).collect()
}

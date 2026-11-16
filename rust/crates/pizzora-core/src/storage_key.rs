use crate::error::DomainError;

pub struct StorageKeyValidator;

impl StorageKeyValidator {
    /// Validates S3/MinIO object keys using identical rules to Kotlin's `StorageKeyValidator`:
    /// - Non-blank, no leading/trailing whitespace (`raw == raw.trim()`)
    /// - No leading `/`, `\`, `\0`, `//`, `..`, `%2e`, `%2f`, `%5c` (case-insensitive)
    /// - Matches `^[A-Za-z0-9][A-Za-z0-9/._-]{0,254}$`
    pub fn validate_object_key(raw_key: &str) -> Result<&str, DomainError> {
        let trimmed = raw_key.trim();
        if trimmed.is_empty() || raw_key != trimmed {
            return Err(DomainError::InvalidObjectKey(
                "Object key cannot be empty or contain surrounding whitespace.".to_owned(),
            ));
        }

        let lower = raw_key.to_ascii_lowercase();
        let invalid_chars_match = Self::matches_safe_object_key(raw_key);
        if raw_key.starts_with('/')
            || raw_key.contains('\\')
            || raw_key.contains('\0')
            || raw_key.contains("//")
            || raw_key.contains("..")
            || lower.contains("%2e")
            || lower.contains("%2f")
            || lower.contains("%5c")
            || !invalid_chars_match
        {
            return Err(DomainError::InvalidObjectKey(
                "Object key contains prohibited path traversal or unsafe characters.".to_owned(),
            ));
        }

        Ok(raw_key)
    }

    fn matches_safe_object_key(s: &str) -> bool {
        let bytes = s.as_bytes();
        if bytes.is_empty() || bytes.len() > 255 {
            return false;
        }
        if !bytes[0].is_ascii_alphanumeric() {
            return false;
        }
        bytes[1..]
            .iter()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'/' | b'.' | b'_' | b'-'))
    }
}

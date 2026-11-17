use argon2::password_hash::rand_core::OsRng;
use argon2::password_hash::{PasswordHash, PasswordHasher as _, PasswordVerifier as _, SaltString};
use argon2::{Algorithm, Argon2, Params, Version};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine as _;
use rand::RngCore as _;
use sha2::{Digest as _, Sha256};
use subtle::ConstantTimeEq as _;

use crate::error::AuthError;

pub const DEFAULT_ARGON2_MEMORY_KB: u32 = 65_536;
pub const DEFAULT_ARGON2_ITERATIONS: u32 = 3;
pub const DEFAULT_ARGON2_PARALLELISM: u32 = 1;
pub const DEFAULT_ARGON2_HASH_LEN: usize = 32;

pub struct CryptoTokens;

impl CryptoTokens {
    pub fn generate_opaque_token(num_bytes: usize) -> String {
        let len = num_bytes.max(16);
        let mut bytes = vec![0u8; len];
        OsRng.fill_bytes(&mut bytes);
        URL_SAFE_NO_PAD.encode(bytes)
    }

    pub fn sha256_hex(input: &str) -> String {
        let digest = Sha256::digest(input.as_bytes());
        hex::encode(digest)
    }

    pub fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
        a.len() == b.len() && bool::from(a.ct_eq(b))
    }
}

#[derive(Debug, Clone)]
pub struct PasswordHasher {
    memory_kb: u32,
    iterations: u32,
    parallelism: u32,
    hash_length_bytes: usize,
}

impl Default for PasswordHasher {
    fn default() -> Self {
        Self::new(
            DEFAULT_ARGON2_MEMORY_KB,
            DEFAULT_ARGON2_ITERATIONS,
            DEFAULT_ARGON2_PARALLELISM,
            DEFAULT_ARGON2_HASH_LEN,
        )
    }
}

impl PasswordHasher {
    pub fn new(
        memory_kb: u32,
        iterations: u32,
        parallelism: u32,
        hash_length_bytes: usize,
    ) -> Self {
        Self {
            memory_kb,
            iterations,
            parallelism,
            hash_length_bytes,
        }
    }

    pub fn hash(&self, password: &str) -> Result<String, AuthError> {
        if password.chars().count() < 8 {
            return Err(AuthError::Validation {
                code: "WEAK_PASSWORD",
                message: "Password must be at least 8 characters long.".to_owned(),
            });
        }
        let params = Params::new(
            self.memory_kb,
            self.iterations,
            self.parallelism,
            Some(self.hash_length_bytes),
        )
        .map_err(|err| AuthError::Crypto(format!("invalid Argon2 parameters: {err}")))?;

        let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
        let salt = SaltString::generate(&mut OsRng);
        let hash = argon2
            .hash_password(password.as_bytes(), &salt)
            .map_err(|err| AuthError::Crypto(format!("Argon2id hashing failed: {err}")))?;
        Ok(hash.to_string())
    }

    pub fn verify(&self, password: &str, encoded_hash: &str) -> bool {
        let Ok(parsed) = PasswordHash::new(encoded_hash) else {
            return false;
        };
        if parsed.algorithm.as_str() != "argon2id" {
            return false;
        }
        Argon2::default()
            .verify_password(password.as_bytes(), &parsed)
            .is_ok()
    }
}

use chrono::{DateTime, Utc};
use jsonwebtoken::{decode, encode, Algorithm, DecodingKey, EncodingKey, Header, Validation};
use pizzora_config::AuthConfig;
use pizzora_core::UserRole;
use pizzora_db::{new_uuid_v4, parse_user_role, user_role_as_str};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::AuthError;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AccessTokenClaims {
    pub iss: String,
    pub sub: String,
    pub jti: String,
    pub sid: String,
    pub role: String,
    pub iat: i64,
    pub exp: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VerifiedAccessToken {
    pub user_id: Uuid,
    pub session_id: Uuid,
    pub role: UserRole,
    pub jwt_id: String,
}

#[derive(Clone)]
pub struct JwtTokenProvider {
    encoding_key: EncodingKey,
    decoding_key: DecodingKey,
    issuer: String,
    access_token_ttl_seconds: i64,
}

impl std::fmt::Debug for JwtTokenProvider {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("JwtTokenProvider")
            .field("issuer", &self.issuer)
            .field("access_token_ttl_seconds", &self.access_token_ttl_seconds)
            .finish()
    }
}

impl JwtTokenProvider {
    pub fn new(config: &AuthConfig) -> Self {
        let secret_bytes = config.jwt_secret().as_bytes();
        Self {
            encoding_key: EncodingKey::from_secret(secret_bytes),
            decoding_key: DecodingKey::from_secret(secret_bytes),
            issuer: config.jwt_issuer.clone(),
            access_token_ttl_seconds: config.access_token_ttl_seconds,
        }
    }

    pub fn create_access_token(
        &self,
        user_id: Uuid,
        session_id: Uuid,
        role: UserRole,
    ) -> Result<String, AuthError> {
        self.create_access_token_at(user_id, session_id, role, Utc::now())
    }

    pub fn create_access_token_at(
        &self,
        user_id: Uuid,
        session_id: Uuid,
        role: UserRole,
        now: DateTime<Utc>,
    ) -> Result<String, AuthError> {
        let iat = now.timestamp();
        let exp = iat + self.access_token_ttl_seconds;
        let claims = AccessTokenClaims {
            iss: self.issuer.clone(),
            sub: user_id.to_string(),
            jti: new_uuid_v4().to_string(),
            sid: session_id.to_string(),
            role: user_role_as_str(role).to_owned(),
            iat,
            exp,
        };
        encode(&Header::new(Algorithm::HS256), &claims, &self.encoding_key)
            .map_err(|err| AuthError::Crypto(format!("JWT signing failed: {err}")))
    }

    pub fn verify_access_token(&self, token: &str) -> Option<VerifiedAccessToken> {
        self.verify_access_token_strict(token).ok()
    }

    pub fn verify_access_token_strict(&self, token: &str) -> Result<VerifiedAccessToken, AuthError> {
        let mut validation = Validation::new(Algorithm::HS256);
        validation.set_issuer(&[&self.issuer]);
        validation.set_required_spec_claims(&["exp", "iss", "sub"]);
        validation.leeway = 0;
        validation.validate_exp = true;

        let token_data =
            decode::<AccessTokenClaims>(token, &self.decoding_key, &validation).map_err(|_| {
                AuthError::Unauthorized {
                    code: "INVALID_ACCESS_TOKEN",
                    message: "Access token is invalid or expired.".to_owned(),
                }
            })?;
        let claims = token_data.claims;

        let user_id = Uuid::parse_str(&claims.sub).map_err(|_| AuthError::Unauthorized {
            code: "INVALID_ACCESS_TOKEN",
            message: "Access token subject is malformed.".to_owned(),
        })?;
        let session_id = Uuid::parse_str(&claims.sid).map_err(|_| AuthError::Unauthorized {
            code: "INVALID_ACCESS_TOKEN",
            message: "Access token session is malformed.".to_owned(),
        })?;
        let role = parse_user_role(&claims.role).ok_or_else(|| AuthError::Unauthorized {
            code: "INVALID_ACCESS_TOKEN",
            message: "Access token role is unsupported.".to_owned(),
        })?;
        if claims.jti.trim().is_empty() {
            return Err(AuthError::Unauthorized {
                code: "INVALID_ACCESS_TOKEN",
                message: "Access token jti is missing.".to_owned(),
            });
        }

        Ok(VerifiedAccessToken {
            user_id,
            session_id,
            role,
            jwt_id: claims.jti,
        })
    }
}

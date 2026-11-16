//! Identity, RBAC, AEAD outbox token envelope crypto, and SMTP delivery (Phase 5).

pub mod bootstrap;
pub mod email;
pub mod error;
pub mod jwt;
pub mod outbox_crypto;
pub mod password;
pub mod rbac;
pub mod service;
pub mod ws_ticket;

pub use bootstrap::BootstrapOwner;
pub use email::{
    format_outbox_message_id, EmailDeliveryProvider, InMemoryEmailDeliveryProvider,
    LettreSmtpEmailDeliveryProvider, OutboundEmail, SentEmailRecord,
};
pub use error::{AuthError, EmailDeliveryError};
pub use jwt::{AccessTokenClaims, JwtTokenProvider, VerifiedAccessToken};
pub use outbox_crypto::{
    compute_outbox_aad, mask_email, sanitize_error_for_logs, scrub_outbox_email_payload,
    seal_outbox_email_payload, unseal_outbox_email_payload, EmailTemplateKind,
    OutboxEmailPayload, SealedEmailEnvelope, SensitiveEmailSecrets, OUTBOX_ENVELOPE_ALG,
    OUTBOX_NONCE_LEN,
};
pub use password::{CryptoTokens, PasswordHasher};
pub use rbac::{AuthorizationService, PizzoraPrincipal};
pub use service::{
    AuthService, AuthTokensResponse, CreateStaffRequest, ForgotPasswordRequest, LoginRequest,
    MessageResponse, RefreshTokenRequest, RegisterRequest, ResendVerificationRequest,
    ResetPasswordRequest, VerifyEmailRequest,
};
pub use ws_ticket::{WsTicketResponse, WsTicketService};

pub fn crate_name() -> &'static str {
    let _ = pizzora_db::crate_name();
    env!("CARGO_PKG_NAME")
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;
    use std::sync::{Arc, Mutex};

    use chrono::{Duration, Utc};
    use pizzora_config::{AuthConfig, OutboxEncryptionConfig, SmtpConfig};
    use pizzora_core::UserRole;
    use pizzora_db::{
        connect_pg_pool, new_uuid_v4, run_locked_handoff, AuditContext, InMemoryCacheStore,
        OutboxRepository, WsTicketRepository, EVENT_EMAIL_DELIVERY_REQUESTED,
    };
    use sqlx::Row as _;
    use uuid::Uuid;
    use zeroize::Zeroizing;

    use super::*;

    #[derive(serde::Deserialize)]
    struct KtorArgon2FixtureBundle {
        cases: Vec<KtorArgon2FixtureCase>,
    }

    #[derive(serde::Deserialize)]
    struct KtorArgon2FixtureCase {
        name: String,
        password: String,
        phc_hash: String,
        verified_by_kotlin: bool,
    }

    fn fixtures_path() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("..")
            .join("..")
            .join("tools")
            .join("golden-vectors")
            .join("ktor_argon2id_fixtures.json")
    }

    #[test]
    fn verifies_all_ktor_generated_argon2id_phc_fixtures() {
        let raw = std::fs::read_to_string(fixtures_path())
            .expect("ktor_argon2id_fixtures.json should exist");
        let bundle: KtorArgon2FixtureBundle =
            serde_json::from_str(&raw).expect("valid Argon2id fixture JSON");
        assert!(!bundle.cases.is_empty());

        let hasher = PasswordHasher::default();
        for case in bundle.cases {
            assert!(case.verified_by_kotlin);
            assert!(
                hasher.verify(&case.password, &case.phc_hash),
                "Expected Rust PasswordHasher to verify Ktor fixture {}",
                case.name
            );
            assert!(
                !hasher.verify("WrongPassword99!", &case.phc_hash),
                "Expected wrong password rejection for {}",
                case.name
            );
        }

        let fast_hasher = PasswordHasher::new(4096, 1, 1, 32);
        let generated = fast_hasher
            .hash("PizzoraTest!2026")
            .expect("hashing valid password succeeds");
        assert!(generated.starts_with("$argon2id$v=19$"));
        assert!(fast_hasher.verify("PizzoraTest!2026", &generated));
        assert!(!fast_hasher.verify("pizzoratest!2026", &generated));
    }

    #[test]
    fn opaque_tokens_sha256_and_jwt_round_trip_with_strict_checks() {
        let tok1 = CryptoTokens::generate_opaque_token(32);
        let tok2 = CryptoTokens::generate_opaque_token(32);
        assert_ne!(tok1, tok2);
        assert!(!tok1.contains('='));
        assert_eq!(CryptoTokens::sha256_hex("abc").len(), 64);
        assert!(CryptoTokens::constant_time_eq(b"secret", b"secret"));
        assert!(!CryptoTokens::constant_time_eq(b"secret", b"secrex"));

        let auth_cfg = AuthConfig::new(
            "unit-test-hmac-secret-at-least-32-chars",
            "pizzora-api",
            900,
            30,
            30,
        );
        let provider = JwtTokenProvider::new(&auth_cfg);
        let uid = new_uuid_v4();
        let sid = new_uuid_v4();

        let token = provider
            .create_access_token(uid, sid, UserRole::Owner)
            .expect("sign JWT");
        let verified = provider
            .verify_access_token(&token)
            .expect("verify fresh JWT");
        assert_eq!(verified.user_id, uid);
        assert_eq!(verified.session_id, sid);
        assert_eq!(verified.role, UserRole::Owner);
        assert!(!verified.jwt_id.is_empty());

        let expired_token = provider
            .create_access_token_at(
                uid,
                sid,
                UserRole::Customer,
                Utc::now() - Duration::seconds(905),
            )
            .expect("sign expired JWT");
        assert!(provider.verify_access_token(&expired_token).is_none());

        let wrong_issuer = JwtTokenProvider::new(&AuthConfig::new(
            "unit-test-hmac-secret-at-least-32-chars",
            "other-issuer",
            900,
            30,
            30,
        ));
        assert!(wrong_issuer.verify_access_token(&token).is_none());
    }

    #[test]
    fn outbox_aead_envelope_protects_tokens_across_tamper_rotation_and_scrubbing() {
        let key_k1 = "k1:AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";
        let key_k2 = "k2:AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=";
        let cfg_v1 = OutboxEncryptionConfig::parse_from_raw("k1", key_k1, false).unwrap();
        let cfg_rotated = OutboxEncryptionConfig::parse_from_raw(
            "k2",
            &format!("{key_k1},{key_k2}"),
            false,
        )
        .unwrap();
        let cfg_v2_only = OutboxEncryptionConfig::parse_from_raw("k2", key_k2, false).unwrap();

        let event_id = new_uuid_v4();
        let user_id = new_uuid_v4();
        let token_id = new_uuid_v4();
        let expires_at = Utc::now() + Duration::minutes(15);
        let raw_token = "super_secret_reset_token_998877";
        let email = "customer@example.com";

        let sealed = seal_outbox_email_payload(
            &cfg_v1,
            event_id,
            user_id,
            EVENT_EMAIL_DELIVERY_REQUESTED,
            EmailTemplateKind::PasswordReset,
            token_id,
            expires_at,
            email,
            "Aarav Patel",
            raw_token,
        )
        .expect("seal succeeds");

        let json_str = serde_json::to_string(&sealed).unwrap();
        assert!(!json_str.contains(raw_token));
        assert!(!json_str.contains(email));
        assert!(!json_str.contains("Aarav Patel"));
        assert_eq!(sealed.recipient_masked, "c***@example.com");
        assert_eq!(
            sealed.sealed_envelope.as_ref().unwrap().kid,
            "k1"
        );

        // Multi-kid rotation: rotated keyring (current=k2) decrypts k1 envelope seamlessly
        let unsealed = unseal_outbox_email_payload(
            &cfg_rotated,
            event_id,
            EVENT_EMAIL_DELIVERY_REQUESTED,
            &sealed,
        )
        .expect("rotated keyring unseals k1");
        assert_eq!(unsealed.to_email.as_str(), email);
        assert_eq!(unsealed.full_name.as_str(), "Aarav Patel");
        assert_eq!(unsealed.raw_token.as_str(), raw_token);

        // Sensitive Debug output must never leak token or email
        let debug_repr = format!("{unsealed:?}");
        assert!(!debug_repr.contains(raw_token));
        assert!(!debug_repr.contains(email));

        // Unknown/retired kid rejected
        assert!(unseal_outbox_email_payload(
            &cfg_v2_only,
            event_id,
            EVENT_EMAIL_DELIVERY_REQUESTED,
            &sealed,
        )
        .is_err());

        // AAD tamper checks (wrong event_id, wrong user_id, wrong event_type)
        assert!(unseal_outbox_email_payload(
            &cfg_rotated,
            new_uuid_v4(),
            EVENT_EMAIL_DELIVERY_REQUESTED,
            &sealed,
        )
        .is_err());
        assert!(unseal_outbox_email_payload(
            &cfg_rotated,
            event_id,
            "OTHER_EVENT",
            &sealed,
        )
        .is_err());
        let mut wrong_user_payload = sealed.clone();
        wrong_user_payload.user_id = new_uuid_v4().to_string();
        assert!(unseal_outbox_email_payload(
            &cfg_rotated,
            event_id,
            EVENT_EMAIL_DELIVERY_REQUESTED,
            &wrong_user_payload,
        )
        .is_err());

        // Scrubbing erases sealed_envelope and marks redacted=true
        let scrubbed = scrub_outbox_email_payload(&sealed);
        assert!(scrubbed.sealed_envelope.is_none());
        assert_eq!(scrubbed.redacted, Some(true));
        let scrubbed_json = serde_json::to_string(&scrubbed).unwrap();
        assert!(!scrubbed_json.contains(raw_token));
        assert!(unseal_outbox_email_payload(
            &cfg_rotated,
            event_id,
            EVENT_EMAIL_DELIVERY_REQUESTED,
            &scrubbed,
        )
        .is_err());

        // Error sanitization strips secret values
        let raw_err = format!(
            "SMTP rejected message containing token={raw_token}\nfor {email}"
        );
        let safe_err = sanitize_error_for_logs(&raw_err, &[raw_token, email]);
        assert!(!safe_err.contains(raw_token));
        assert!(!safe_err.contains(email));
        assert!(safe_err.contains("[REDACTED]"));
    }

    #[test]
    fn smtp_message_builder_sets_deterministic_message_id_and_outbox_header() {
        let smtp_cfg = SmtpConfig {
            host: "localhost".to_owned(),
            port: 1025,
            from_address: "no-reply@pizzora.local".to_owned(),
            username: None,
            password: None,
            use_starttls: false,
        };
        let provider = LettreSmtpEmailDeliveryProvider::new(smtp_cfg);
        let event_id = Uuid::parse_str("11111111-2222-4333-8444-555555555555").unwrap();
        let req = OutboundEmail {
            outbox_event_id: event_id,
            template: EmailTemplateKind::Verification,
            to_email: Zeroizing::new("user@example.com".to_owned()),
            full_name: Zeroizing::new("Priya Nair".to_owned()),
            raw_token: Zeroizing::new("verify_tok_12345".to_owned()),
        };

        let dbg = format!("{req:?}");
        assert!(!dbg.contains("verify_tok_12345"));
        assert!(!dbg.contains("user@example.com"));

        let message = provider.build_message(&req).expect("builds RFC 5322 message");
        let raw_mime = String::from_utf8_lossy(&message.formatted()).into_owned();
        assert!(
            raw_mime.contains("Message-ID: <outbox-11111111-2222-4333-8444-555555555555@pizzora.local>")
                || raw_mime.contains("Message-Id: <outbox-11111111-2222-4333-8444-555555555555@pizzora.local>"),
            "Missing deterministic Message-ID in MIME headers: {raw_mime}"
        );
        assert!(
            raw_mime.contains("X-Pizzora-Outbox-Id: 11111111-2222-4333-8444-555555555555"),
            "Missing X-Pizzora-Outbox-Id in MIME headers: {raw_mime}"
        );
    }

    struct CaptureVisitor<'a>(&'a mut String);

    impl tracing::field::Visit for CaptureVisitor<'_> {
        fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn std::fmt::Debug) {
            use std::fmt::Write as _;
            let _ = write!(self.0, "{}={:?} ", field.name(), value);
        }
    }

    struct CapturingSubscriber(Arc<Mutex<Vec<String>>>);

    impl tracing::Subscriber for CapturingSubscriber {
        fn enabled(&self, _metadata: &tracing::Metadata<'_>) -> bool {
            true
        }
        fn new_span(&self, _span: &tracing::span::Attributes<'_>) -> tracing::span::Id {
            tracing::span::Id::from_u64(1)
        }
        fn record(&self, _span: &tracing::span::Id, _values: &tracing::span::Record<'_>) {}
        fn record_follows_from(&self, _span: &tracing::span::Id, _follows: &tracing::span::Id) {}
        fn event(&self, event: &tracing::Event<'_>) {
            let mut line = String::new();
            event.record(&mut CaptureVisitor(&mut line));
            self.0.lock().unwrap().push(line);
        }
        fn enter(&self, _span: &tracing::span::Id) {}
        fn exit(&self, _span: &tracing::span::Id) {}
    }

    #[test]
    fn tracing_and_in_memory_provider_support_redaction_and_fault_injection() {
        let captured = Arc::new(Mutex::new(Vec::new()));
        let subscriber = CapturingSubscriber(captured.clone());
        let raw_token = "tok_secret_value_abc";
        let email = "sensitive.user@example.com";

        let _guard = tracing::subscriber::set_default(subscriber);
        let req = OutboundEmail {
            outbox_event_id: new_uuid_v4(),
            template: EmailTemplateKind::PasswordReset,
            to_email: Zeroizing::new(email.to_owned()),
            full_name: Zeroizing::new("Test User".to_owned()),
            raw_token: Zeroizing::new(raw_token.to_owned()),
        };
        tracing::info!(req = ?req, "Prepared outbox email");

        let logs = captured.lock().unwrap().join("\n");
        assert!(!logs.contains(raw_token));
        assert!(!logs.contains(email));

        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            let mem = InMemoryEmailDeliveryProvider::new("pizzora.local");
            mem.inject_failure_after_accept(true, "DB_CONN_LOST", "dropped after 250 OK");
            let err = mem.send_outbox_email(&req).await.unwrap_err();
            assert!(err.is_transient);
            // Email WAS accepted before the injected post-250 failure
            assert_eq!(mem.sent_emails().len(), 1);
            assert_eq!(
                mem.sent_emails()[0].message_id,
                format_outbox_message_id(req.outbox_event_id, "pizzora.local")
            );
        });
    }

    #[tokio::test]
    #[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
    async fn pg18_auth_rbac_outbox_and_ws_ticket_full_integration() {
        let cfg = pizzora_config::AppConfig::from_env().expect("env config");
        let pool = connect_pg_pool(&cfg.database).await.expect("connect pg");
        run_locked_handoff(&pool).await.expect("migrations ready");

        let fast_hasher = PasswordHasher::new(4096, 1, 1, 32);
        let auth = AuthService::new(
            pool.clone(),
            cfg.auth.clone(),
            cfg.outbox_encryption.clone(),
            fast_hasher.clone(),
        );

        let unique = new_uuid_v4();
        let email = format!("it_{unique}@example.com");
        auth.register(&RegisterRequest {
            email: email.clone(),
            password: "StrongPassword123!".to_owned(),
            full_name: "Integration Customer".to_owned(),
            phone: "9876543210".to_owned(),
        })
        .await
        .expect("register succeeds");

        // Verify outbox row has encrypted envelope and zero plaintext token
        let row = sqlx::query(
            "SELECT id, event_type, payload_json FROM outbox_events WHERE event_type = $1 ORDER BY created_at DESC LIMIT 1",
        )
        .bind(EVENT_EMAIL_DELIVERY_REQUESTED)
        .fetch_one(&pool)
        .await
        .expect("outbox row exists");
        let outbox_id: Uuid = row.try_get("id").unwrap();
        let payload_val: serde_json::Value = row.try_get("payload_json").unwrap();
        let parsed_payload: OutboxEmailPayload = serde_json::from_value(payload_val).unwrap();
        let secrets = unseal_outbox_email_payload(
            &cfg.outbox_encryption,
            outbox_id,
            EVENT_EMAIL_DELIVERY_REQUESTED,
            &parsed_payload,
        )
        .expect("unseal verification token");
        assert_eq!(secrets.to_email.as_str(), email);

        // Single-use verification
        auth.verify_email(&VerifyEmailRequest {
            token: secrets.raw_token.to_string(),
        })
        .await
        .expect("first verify succeeds");
        assert!(auth
            .verify_email(&VerifyEmailRequest {
                token: secrets.raw_token.to_string(),
            })
            .await
            .is_err());

        // Login -> Refresh rotation -> Reuse revocation
        let tokens1 = auth
            .login(
                &LoginRequest {
                    email: email.clone(),
                    password: "StrongPassword123!".to_owned(),
                    device_name: "Pixel".to_owned(),
                },
                Some("127.0.0.1"),
            )
            .await
            .expect("login succeeds");

        let tokens2 = auth
            .refresh(&RefreshTokenRequest {
                refresh_token: tokens1.refresh_token.clone(),
            })
            .await
            .expect("refresh succeeds");

        // Reusing token1 revokes entire family including token2
        let reuse_err = auth
            .refresh(&RefreshTokenRequest {
                refresh_token: tokens1.refresh_token.clone(),
            })
            .await
            .expect_err("reused token rejected");
        assert!(reuse_err.to_string().contains("REFRESH_TOKEN_REUSED"));
        assert!(auth
            .refresh(&RefreshTokenRequest {
                refresh_token: tokens2.refresh_token.clone(),
            })
            .await
            .is_err());

        // RBAC + WS ticket Redis fallback test
        let cache = Arc::new(InMemoryCacheStore::new());
        let rbac = AuthorizationService::new(pool.clone(), cache.clone());
        assert!(rbac
            .has_permission(UserRole::Owner, "owner:staff_manage")
            .await
            .expect("owner perm"));

        let ws_repo = WsTicketRepository::new(pool.clone(), cache.clone(), 30);
        let ws_svc = WsTicketService::new(ws_repo);

        // Relogin cleanly
        let fresh = auth
            .login(
                &LoginRequest {
                    email: email.clone(),
                    password: "StrongPassword123!".to_owned(),
                    device_name: "Pixel".to_owned(),
                },
                Some("127.0.0.1"),
            )
            .await
            .expect("relogin succeeds");
        let claims = auth
            .jwt_provider()
            .verify_access_token(&fresh.access_token)
            .expect("valid jwt");
        let principal = PizzoraPrincipal {
            user_id: claims.user_id,
            session_id: claims.session_id,
            role: claims.role,
        };

        // Fallback to PostgreSQL when Redis unavailable
        cache.set_unavailable(true);
        let ticket = ws_svc.mint_ticket(&principal).await.expect("pg fallback mint");
        let consumed = ws_svc
            .consume_ticket(&ticket.ticket)
            .await
            .expect("pg fallback consume")
            .expect("ticket valid");
        assert_eq!(consumed, principal);
        assert!(ws_svc.consume_ticket(&ticket.ticket).await.unwrap().is_none());

        // Owner bootstrap + staff creation check
        let owner_email = format!("owner_{unique}@example.com");
        let emp_code = format!("OWN-{}", &unique.to_string()[..8]);
        let owner_id = BootstrapOwner::bootstrap(
            &pool,
            &fast_hasher,
            &owner_email,
            "OwnerPass123!",
            "Owner Name",
            "9876543210",
            Some(&emp_code),
        )
        .await
        .expect("bootstrap owner");
        let outbox_repo = OutboxRepository::new(pool.clone());
        assert_eq!(outbox_repo.scrub_expired_email_envelopes().await.is_ok(), true);
        let _ = (owner_id, AuditContext::new(None, "SYSTEM", "it", None));
    }
}

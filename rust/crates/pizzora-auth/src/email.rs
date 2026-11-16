use std::fmt;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use lettre::message::{header, Mailbox, Message};
use lettre::transport::smtp::authentication::Credentials;
use lettre::{AsyncSmtpTransport, AsyncTransport as _, Tokio1Executor};
use pizzora_config::SmtpConfig;
use pizzora_db::redis::BoxFuture;
use uuid::Uuid;
use zeroize::Zeroizing;

use crate::error::EmailDeliveryError;
use crate::outbox_crypto::{mask_email, sanitize_error_for_logs, EmailTemplateKind};

#[derive(Clone)]
pub struct OutboundEmail {
    pub outbox_event_id: Uuid,
    pub template: EmailTemplateKind,
    pub to_email: Zeroizing<String>,
    pub full_name: Zeroizing<String>,
    pub raw_token: Zeroizing<String>,
}

impl fmt::Debug for OutboundEmail {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("OutboundEmail")
            .field("outbox_event_id", &self.outbox_event_id)
            .field("template", &self.template)
            .field("recipient", &mask_email(&self.to_email))
            .field("raw_token", &"[REDACTED]")
            .finish()
    }
}

pub fn format_outbox_message_id(outbox_event_id: Uuid, from_domain: &str) -> String {
    let clean_domain = from_domain.trim();
    let domain = if clean_domain.is_empty() {
        "pizzora.local"
    } else {
        clean_domain
    };
    format!("<outbox-{outbox_event_id}@{domain}>")
}

#[derive(Clone, Debug)]
struct XPizzoraOutboxIdHeader(String);

impl header::Header for XPizzoraOutboxIdHeader {
    fn name() -> header::HeaderName {
        header::HeaderName::new_from_ascii_str("X-Pizzora-Outbox-Id")
    }

    fn parse(s: &str) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        Ok(Self(s.to_owned()))
    }

    fn display(&self) -> header::HeaderValue {
        header::HeaderValue::new(Self::name(), self.0.clone())
    }
}

pub trait EmailDeliveryProvider: Send + Sync {
    fn send_outbox_email<'a>(
        &'a self,
        email: &'a OutboundEmail,
    ) -> BoxFuture<'a, Result<(), EmailDeliveryError>>;
}

#[derive(Clone)]
pub struct LettreSmtpEmailDeliveryProvider {
    config: SmtpConfig,
}

impl fmt::Debug for LettreSmtpEmailDeliveryProvider {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LettreSmtpEmailDeliveryProvider")
            .field("config", &self.config)
            .finish()
    }
}

impl LettreSmtpEmailDeliveryProvider {
    pub fn new(config: SmtpConfig) -> Self {
        Self { config }
    }

    pub fn build_message(&self, email: &OutboundEmail) -> Result<Message, EmailDeliveryError> {
        let from_mailbox: Mailbox = self.config.from_address.parse().map_err(|_| {
            EmailDeliveryError {
                is_transient: false,
                code: "SMTP_INVALID_FROM_ADDRESS".to_owned(),
                sanitized_message: "Configured SMTP sender address is invalid.".to_owned(),
            }
        })?;
        let to_mailbox: Mailbox = email.to_email.parse().map_err(|_| EmailDeliveryError {
            is_transient: false,
            code: "SMTP_INVALID_RECIPIENT".to_owned(),
            sanitized_message: "Recipient address is malformed.".to_owned(),
        })?;

        let (subject, body) = match email.template {
            EmailTemplateKind::Verification => (
                "Verify your PIZZORA email address",
                format!(
                    "Hi {},\n\nUse this verification token in the PIZZORA app to verify your email:\n{}\n\nThis token expires in 24 hours and can only be used once.",
                    email.full_name.trim(),
                    email.raw_token.as_str(),
                ),
            ),
            EmailTemplateKind::PasswordReset => (
                "Reset your PIZZORA password",
                format!(
                    "Hi {},\n\nUse this single-use password reset token in the PIZZORA app:\n{}\n\nThis token expires in 15 minutes. If you did not request this reset, ignore this email.",
                    email.full_name.trim(),
                    email.raw_token.as_str(),
                ),
            ),
        };

        let message_id =
            format_outbox_message_id(email.outbox_event_id, self.config.from_domain());

        Message::builder()
            .from(from_mailbox)
            .to(to_mailbox)
            .subject(subject)
            .message_id(Some(message_id))
            .header(XPizzoraOutboxIdHeader(email.outbox_event_id.to_string()))
            .header(header::ContentType::TEXT_PLAIN)
            .body(body)
            .map_err(|_| EmailDeliveryError {
                is_transient: false,
                code: "SMTP_MESSAGE_BUILD_FAILED".to_owned(),
                sanitized_message: "Failed to build RFC 5322 email message.".to_owned(),
            })
    }

    fn build_transport(&self) -> Result<AsyncSmtpTransport<Tokio1Executor>, EmailDeliveryError> {
        let mut builder = if self.config.use_starttls {
            AsyncSmtpTransport::<Tokio1Executor>::starttls_relay(&self.config.host)
                .map_err(|err| classify_smtp_error(&err, &[]))?
                .port(self.config.port)
        } else {
            AsyncSmtpTransport::<Tokio1Executor>::builder_dangerous(&self.config.host)
                .port(self.config.port)
        };

        builder = builder.timeout(Some(Duration::from_secs(5)));

        if let (Some(username), Some(password)) =
            (self.config.username.as_deref(), self.config.password())
        {
            builder = builder.credentials(Credentials::new(
                username.to_owned(),
                password.to_owned(),
            ));
        }

        Ok(builder.build())
    }
}

impl EmailDeliveryProvider for LettreSmtpEmailDeliveryProvider {
    fn send_outbox_email<'a>(
        &'a self,
        email: &'a OutboundEmail,
    ) -> BoxFuture<'a, Result<(), EmailDeliveryError>> {
        Box::pin(async move {
            let message = self.build_message(email)?;
            let transport = self.build_transport()?;
            let mut secrets = Vec::new();
            secrets.push(email.raw_token.as_str());
            secrets.push(email.to_email.as_str());
            secrets.push(email.full_name.as_str());
            if let Some(pw) = self.config.password() {
                secrets.push(pw);
            }

            transport
                .send(message)
                .await
                .map(|_| ())
                .map_err(|err| classify_smtp_error(&err, &secrets))
        })
    }
}

fn classify_smtp_error(
    err: &lettre::transport::smtp::Error,
    sensitive_values: &[&str],
) -> EmailDeliveryError {
    let is_transient = !err.is_permanent();
    let code = match err.status() {
        Some(status) => format!("SMTP_{status}"),
        None if err.is_timeout() => "SMTP_TIMEOUT".to_owned(),
        None if err.is_transient() => "SMTP_TRANSIENT_ERROR".to_owned(),
        None if err.is_permanent() => "SMTP_PERMANENT_ERROR".to_owned(),
        None => "SMTP_CONNECTION_ERROR".to_owned(),
    };
    let sanitized_message = sanitize_error_for_logs(&err.to_string(), sensitive_values);
    EmailDeliveryError {
        is_transient,
        code,
        sanitized_message,
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SentEmailRecord {
    pub outbox_event_id: Uuid,
    pub template: EmailTemplateKind,
    pub message_id: String,
    pub x_pizzora_outbox_id: String,
    pub to_email: String,
    pub full_name: String,
    pub raw_token: String,
}

#[derive(Debug, Clone)]
enum InjectedEmailFault {
    FailBeforeSend(EmailDeliveryError),
    FailAfterRecord(EmailDeliveryError),
}

#[derive(Clone, Debug)]
pub struct InMemoryEmailDeliveryProvider {
    from_domain: String,
    sent: Arc<Mutex<Vec<SentEmailRecord>>>,
    next_fault: Arc<Mutex<Option<InjectedEmailFault>>>,
}

impl Default for InMemoryEmailDeliveryProvider {
    fn default() -> Self {
        Self::new("pizzora.local")
    }
}

impl InMemoryEmailDeliveryProvider {
    pub fn new(from_domain: impl Into<String>) -> Self {
        Self {
            from_domain: from_domain.into(),
            sent: Arc::new(Mutex::new(Vec::new())),
            next_fault: Arc::new(Mutex::new(None)),
        }
    }

    pub fn inject_failure_before_send(&self, is_transient: bool, code: &str, msg: &str) {
        *self.next_fault.lock().expect("fault mutex") = Some(InjectedEmailFault::FailBeforeSend(
            EmailDeliveryError {
                is_transient,
                code: code.to_owned(),
                sanitized_message: msg.to_owned(),
            },
        ));
    }

    /// Simulates a crash/DB failure immediately AFTER SMTP 250 OK acceptance.
    pub fn inject_failure_after_accept(&self, is_transient: bool, code: &str, msg: &str) {
        *self.next_fault.lock().expect("fault mutex") = Some(InjectedEmailFault::FailAfterRecord(
            EmailDeliveryError {
                is_transient,
                code: code.to_owned(),
                sanitized_message: msg.to_owned(),
            },
        ));
    }

    pub fn sent_emails(&self) -> Vec<SentEmailRecord> {
        self.sent.lock().expect("sent mutex").clone()
    }
}

impl EmailDeliveryProvider for InMemoryEmailDeliveryProvider {
    fn send_outbox_email<'a>(
        &'a self,
        email: &'a OutboundEmail,
    ) -> BoxFuture<'a, Result<(), EmailDeliveryError>> {
        Box::pin(async move {
            let fault = self.next_fault.lock().expect("fault mutex").take();
            if let Some(InjectedEmailFault::FailBeforeSend(err)) = fault {
                return Err(err);
            }

            let message_id = format_outbox_message_id(email.outbox_event_id, &self.from_domain);
            let record = SentEmailRecord {
                outbox_event_id: email.outbox_event_id,
                template: email.template,
                message_id,
                x_pizzora_outbox_id: email.outbox_event_id.to_string(),
                to_email: email.to_email.to_string(),
                full_name: email.full_name.to_string(),
                raw_token: email.raw_token.to_string(),
            };
            self.sent.lock().expect("sent mutex").push(record);

            if let Some(InjectedEmailFault::FailAfterRecord(err)) = fault {
                return Err(err);
            }

            Ok(())
        })
    }
}

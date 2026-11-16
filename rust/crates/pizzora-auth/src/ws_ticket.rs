use pizzora_db::{WsTicketPrincipal, WsTicketRepository};
use serde::{Deserialize, Serialize};

use crate::error::AuthError;
use crate::password::CryptoTokens;
use crate::rbac::PizzoraPrincipal;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WsTicketResponse {
    pub ticket: String,
    pub expires_in_seconds: i64,
}

#[derive(Clone)]
pub struct WsTicketService {
    repo: WsTicketRepository,
}

impl WsTicketService {
    pub fn new(repo: WsTicketRepository) -> Self {
        Self { repo }
    }

    pub async fn mint_ticket(
        &self,
        principal: &PizzoraPrincipal,
    ) -> Result<WsTicketResponse, AuthError> {
        let raw_ticket = CryptoTokens::generate_opaque_token(32);
        let ticket_hash = CryptoTokens::sha256_hex(&raw_ticket);

        self.repo
            .store_hashed_ticket(
                &ticket_hash,
                &WsTicketPrincipal {
                    user_id: principal.user_id,
                    session_id: principal.session_id,
                    role: principal.role,
                },
            )
            .await?;

        Ok(WsTicketResponse {
            ticket: raw_ticket,
            expires_in_seconds: self.repo.ttl_seconds(),
        })
    }

    pub async fn consume_ticket(
        &self,
        raw_ticket: &str,
    ) -> Result<Option<PizzoraPrincipal>, AuthError> {
        let trimmed = raw_ticket.trim();
        if trimmed.is_empty() {
            return Ok(None);
        }
        let ticket_hash = CryptoTokens::sha256_hex(trimmed);
        let maybe_principal = self.repo.consume_hashed_ticket(&ticket_hash).await?;
        Ok(maybe_principal.map(|p| PizzoraPrincipal {
            user_id: p.user_id,
            session_id: p.session_id,
            role: p.role,
        }))
    }
}

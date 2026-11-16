use std::collections::{HashMap, HashSet, VecDeque};

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RealtimeEventInput {
    pub event_id: String,
    pub stream_seq: i64,
    pub event_type: String,
    pub aggregate_type: String,
    pub aggregate_id: String,
    pub aggregate_version: i64,
    pub order_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "decision", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CausalDecision {
    IgnoreDuplicate,
    IgnoreStale,
    ApplyOrderEvent { event_id: String },
    TriggerResync { order_id: String, event_id: String },
    ApplyNonOrderEvent { event_id: String },
}

/// Pure deterministic client tracker enforcing:
/// 1) At-least-once `event_id` deduplication with bounded FIFO eviction.
/// 2) Filtered global cursor advancement via `server_high_water_seq` without false gap errors.
/// 3) Per-order `aggregate_version` causal ordering (drops stale versions, triggers REST snapshot resync on gaps).
#[derive(Debug, Clone)]
pub struct OrderCausalTracker {
    max_tracked_event_ids: usize,
    seen_event_ids: HashSet<String>,
    seen_order: VecDeque<String>,
    order_versions: HashMap<String, i64>,
    last_seen_stream_seq: i64,
}

impl Default for OrderCausalTracker {
    fn default() -> Self {
        Self::new(2_000)
    }
}

impl OrderCausalTracker {
    pub fn new(max_tracked_event_ids: usize) -> Self {
        Self {
            max_tracked_event_ids: max_tracked_event_ids.max(1),
            seen_event_ids: HashSet::new(),
            seen_order: VecDeque::new(),
            order_versions: HashMap::new(),
            last_seen_stream_seq: 0,
        }
    }

    pub const fn last_seen_stream_seq(&self) -> i64 {
        self.last_seen_stream_seq
    }

    pub fn advance_cursor(&mut self, server_high_water_seq: i64) {
        if server_high_water_seq > self.last_seen_stream_seq {
            self.last_seen_stream_seq = server_high_water_seq;
        }
    }

    pub fn record_snapshot(&mut self, order_id: &str, version: i64) {
        let existing = self.order_versions.get(order_id).copied().unwrap_or(0);
        if version > existing {
            self.order_versions.insert(order_id.to_owned(), version);
        }
    }

    pub fn local_version_for(&self, order_id: &str) -> Option<i64> {
        self.order_versions.get(order_id).copied()
    }

    pub fn evaluate(&mut self, event: &RealtimeEventInput) -> CausalDecision {
        if !self.mark_event_seen(&event.event_id) {
            return CausalDecision::IgnoreDuplicate;
        }

        if event.stream_seq > self.last_seen_stream_seq {
            self.last_seen_stream_seq = event.stream_seq;
        }

        if event.aggregate_type != "ORDER" {
            return CausalDecision::ApplyNonOrderEvent {
                event_id: event.event_id.clone(),
            };
        }

        let order_id = event
            .order_id
            .as_deref()
            .unwrap_or(&event.aggregate_id)
            .to_owned();
        let local_version = self.order_versions.get(&order_id).copied();

        if event.event_type == "payment.gateway_ready" {
            return match local_version {
                None => {
                    self.order_versions
                        .insert(order_id, event.aggregate_version);
                    CausalDecision::ApplyOrderEvent {
                        event_id: event.event_id.clone(),
                    }
                }
                Some(local) if event.aggregate_version < local => CausalDecision::IgnoreStale,
                Some(local) if event.aggregate_version > local + 1 => {
                    CausalDecision::TriggerResync {
                        order_id,
                        event_id: event.event_id.clone(),
                    }
                }
                Some(local) => {
                    if event.aggregate_version > local {
                        self.order_versions
                            .insert(order_id, event.aggregate_version);
                    }
                    CausalDecision::ApplyOrderEvent {
                        event_id: event.event_id.clone(),
                    }
                }
            };
        }

        match local_version {
            None => {
                if event.event_type == "order.created" || event.aggregate_version <= 1 {
                    self.order_versions
                        .insert(order_id, event.aggregate_version);
                    CausalDecision::ApplyOrderEvent {
                        event_id: event.event_id.clone(),
                    }
                } else {
                    CausalDecision::TriggerResync {
                        order_id,
                        event_id: event.event_id.clone(),
                    }
                }
            }
            Some(local) if event.aggregate_version <= local => CausalDecision::IgnoreStale,
            Some(local) if event.aggregate_version == local + 1 => {
                self.order_versions
                    .insert(order_id, event.aggregate_version);
                CausalDecision::ApplyOrderEvent {
                    event_id: event.event_id.clone(),
                }
            }
            Some(_) => CausalDecision::TriggerResync {
                order_id,
                event_id: event.event_id.clone(),
            },
        }
    }

    pub fn clear(&mut self) {
        self.seen_event_ids.clear();
        self.seen_order.clear();
        self.order_versions.clear();
        self.last_seen_stream_seq = 0;
    }

    fn mark_event_seen(&mut self, event_id: &str) -> bool {
        if !self.seen_event_ids.insert(event_id.to_owned()) {
            return false;
        }
        self.seen_order.push_back(event_id.to_owned());
        while self.seen_event_ids.len() > self.max_tracked_event_ids {
            if let Some(oldest) = self.seen_order.pop_front() {
                self.seen_event_ids.remove(&oldest);
            } else {
                break;
            }
        }
        true
    }
}

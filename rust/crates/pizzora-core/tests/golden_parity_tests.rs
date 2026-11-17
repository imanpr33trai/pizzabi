use std::fs;
use std::path::PathBuf;

use pizzora_core::{
    CausalDecision, CouponCalculationSpec, FulfillmentType, Money, OrderCausalTracker,
    OrderStateMachine, OrderStatus, PaymentAttemptStatus, PaymentStateMachine, PaymentStatus,
    PriceBreakdown, PricedLineInput, PricingEngine, RealtimeEventInput, StorageKeyValidator,
    UserRole,
};
use serde::Deserialize;

fn golden_vector_path(filename: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../../tools/golden-vectors")
        .join(filename)
}

#[derive(Deserialize)]
struct GoldenParityBundle {
    money_bps_vectors: Vec<MoneyBpsVector>,
    money_format_vectors: Vec<MoneyFormatVector>,
    discount_vectors: Vec<DiscountVector>,
    quote_vectors: Vec<QuoteVector>,
    order_cancel_vectors: Vec<OrderCancelVector>,
    order_transition_vectors: Vec<OrderTransitionVector>,
    kds_next_vectors: Vec<KdsNextVector>,
    payment_transition_vectors: Vec<PaymentTransitionVector>,
    attempt_transition_vectors: Vec<AttemptTransitionVector>,
    causal_trace: Vec<CausalStepVector>,
}

#[derive(Deserialize)]
struct MoneyBpsVector {
    amount_paise: i64,
    bps: i32,
    expected_paise: i64,
}

#[derive(Deserialize)]
struct MoneyFormatVector {
    paise: i64,
    expected_inr: String,
}

#[derive(Deserialize)]
struct DiscountVector {
    subtotal_paise: i64,
    coupon: Option<CouponCalculationSpec>,
    expected_discount_paise: i64,
}

#[derive(Deserialize)]
struct QuoteVector {
    name: String,
    lines: Vec<PricedLineInput>,
    gst_bps: i32,
    delivery_fee_paise: i64,
    coupon: Option<CouponCalculationSpec>,
    expected: PriceBreakdown,
}

#[derive(Deserialize)]
struct OrderCancelVector {
    status: OrderStatus,
    role: UserRole,
    expected: bool,
}

#[derive(Deserialize)]
struct OrderTransitionVector {
    from: OrderStatus,
    to: OrderStatus,
    fulfillment_type: FulfillmentType,
    actor_role: Option<UserRole>,
    expected: bool,
}

#[derive(Deserialize)]
struct KdsNextVector {
    status: OrderStatus,
    fulfillment_type: FulfillmentType,
    expected: Option<OrderStatus>,
}

#[derive(Deserialize)]
struct PaymentTransitionVector {
    from: PaymentStatus,
    to: PaymentStatus,
    expected: bool,
}

#[derive(Deserialize)]
struct AttemptTransitionVector {
    from: PaymentAttemptStatus,
    to: PaymentAttemptStatus,
    expected: bool,
}

#[derive(Deserialize)]
struct CausalStepVector {
    action: String,
    event_id: Option<String>,
    stream_seq: Option<i64>,
    event_type: Option<String>,
    aggregate_type: Option<String>,
    aggregate_id: Option<String>,
    aggregate_version: Option<i64>,
    order_id: Option<String>,
    high_water_seq: Option<i64>,
    expected_decision: Option<String>,
    expected_resync_order_id: Option<String>,
    expected_last_seen_seq: i64,
    expected_tracked_version: Option<i64>,
}

#[derive(Deserialize)]
struct StorageKeyBundle {
    cases: Vec<StorageKeyCase>,
}

#[derive(Deserialize)]
struct StorageKeyCase {
    raw_key: String,
    expected_valid: bool,
}

#[test]
fn kotlin_golden_vectors_match_pizzora_core_100_percent() {
    let raw = fs::read_to_string(golden_vector_path("pizzora_core_parity.json"))
        .expect("read Kotlin-exported pizzora_core_parity.json");
    let bundle: GoldenParityBundle =
        serde_json::from_str(&raw).expect("parse pizzora_core_parity.json");

    assert!(!bundle.money_bps_vectors.is_empty());
    for v in &bundle.money_bps_vectors {
        assert_eq!(
            Money::bps_of(v.amount_paise, v.bps).unwrap(),
            v.expected_paise,
            "bps_of mismatch for amount={}, bps={}",
            v.amount_paise,
            v.bps
        );
    }

    assert!(!bundle.money_format_vectors.is_empty());
    for v in &bundle.money_format_vectors {
        assert_eq!(
            Money::format_inr(v.paise),
            v.expected_inr,
            "format_inr mismatch for {}",
            v.paise
        );
    }

    assert!(!bundle.discount_vectors.is_empty());
    for v in &bundle.discount_vectors {
        assert_eq!(
            PricingEngine::calculate_discount_paise(v.subtotal_paise, v.coupon.as_ref()).unwrap(),
            v.expected_discount_paise,
            "discount mismatch for subtotal={}, coupon={:?}",
            v.subtotal_paise,
            v.coupon
        );
    }

    assert!(!bundle.quote_vectors.is_empty());
    for v in &bundle.quote_vectors {
        let actual = PricingEngine::calculate_quote(
            &v.lines,
            v.gst_bps,
            v.delivery_fee_paise,
            v.coupon.as_ref(),
        )
        .expect("quote vector calculation");
        assert_eq!(actual, v.expected, "quote mismatch for {}", v.name);
    }

    assert!(!bundle.order_cancel_vectors.is_empty());
    for v in &bundle.order_cancel_vectors {
        assert_eq!(
            OrderStateMachine::can_cancel(v.status, v.role),
            v.expected,
            "can_cancel mismatch for {:?}, {:?}",
            v.status,
            v.role
        );
    }

    assert!(!bundle.order_transition_vectors.is_empty());
    for v in &bundle.order_transition_vectors {
        assert_eq!(
            OrderStateMachine::can_transition(v.from, v.to, v.fulfillment_type, v.actor_role),
            v.expected,
            "can_transition mismatch for {:?} -> {:?} ({:?}, {:?})",
            v.from,
            v.to,
            v.fulfillment_type,
            v.actor_role
        );
    }

    assert!(!bundle.kds_next_vectors.is_empty());
    for v in &bundle.kds_next_vectors {
        assert_eq!(
            OrderStateMachine::next_kds_status(v.status, v.fulfillment_type),
            v.expected,
            "next_kds_status mismatch for {:?} ({:?})",
            v.status,
            v.fulfillment_type
        );
    }

    assert!(!bundle.payment_transition_vectors.is_empty());
    for v in &bundle.payment_transition_vectors {
        assert_eq!(
            PaymentStateMachine::can_transition_payment(v.from, v.to),
            v.expected,
            "can_transition_payment mismatch for {:?} -> {:?}",
            v.from,
            v.to
        );
    }

    assert!(!bundle.attempt_transition_vectors.is_empty());
    for v in &bundle.attempt_transition_vectors {
        assert_eq!(
            PaymentStateMachine::can_transition_attempt(v.from, v.to),
            v.expected,
            "can_transition_attempt mismatch for {:?} -> {:?}",
            v.from,
            v.to
        );
    }

    assert!(!bundle.causal_trace.is_empty());
    let mut tracker = OrderCausalTracker::new(3);
    for (idx, step) in bundle.causal_trace.iter().enumerate() {
        match step.action.as_str() {
            "EVALUATE" => {
                let ev = RealtimeEventInput {
                    event_id: step.event_id.clone().expect("event_id"),
                    stream_seq: step.stream_seq.expect("stream_seq"),
                    event_type: step.event_type.clone().expect("event_type"),
                    aggregate_type: step.aggregate_type.clone().expect("aggregate_type"),
                    aggregate_id: step.aggregate_id.clone().expect("aggregate_id"),
                    aggregate_version: step.aggregate_version.expect("aggregate_version"),
                    order_id: step.order_id.clone(),
                };
                let decision = tracker.evaluate(&ev);
                let (actual_tag, actual_resync_order) = match decision {
                    CausalDecision::IgnoreDuplicate => ("IGNORE_DUPLICATE", None),
                    CausalDecision::IgnoreStale => ("IGNORE_STALE", None),
                    CausalDecision::ApplyOrderEvent { .. } => ("APPLY_ORDER_EVENT", None),
                    CausalDecision::TriggerResync { order_id, .. } => {
                        ("TRIGGER_RESYNC", Some(order_id))
                    }
                    CausalDecision::ApplyNonOrderEvent { .. } => ("APPLY_NON_ORDER_EVENT", None),
                };
                assert_eq!(
                    Some(actual_tag),
                    step.expected_decision.as_deref(),
                    "causal decision mismatch at step {idx}"
                );
                assert_eq!(
                    actual_resync_order, step.expected_resync_order_id,
                    "causal resync order_id mismatch at step {idx}"
                );
                let tracked_key = step
                    .order_id
                    .as_deref()
                    .or(step.aggregate_id.as_deref())
                    .expect("tracked key");
                assert_eq!(
                    tracker.local_version_for(tracked_key),
                    step.expected_tracked_version,
                    "tracked version mismatch at step {idx}"
                );
            }
            "RECORD_SNAPSHOT" => {
                let order_id = step.order_id.as_deref().expect("order_id");
                let ver = step.aggregate_version.expect("aggregate_version");
                tracker.record_snapshot(order_id, ver);
                assert_eq!(
                    tracker.local_version_for(order_id),
                    step.expected_tracked_version,
                    "snapshot tracked version mismatch at step {idx}"
                );
            }
            "ADVANCE_CURSOR" => {
                tracker.advance_cursor(step.high_water_seq.expect("high_water_seq"));
            }
            other => panic!("unexpected causal trace action {other}"),
        }
        assert_eq!(
            tracker.last_seen_stream_seq(),
            step.expected_last_seen_seq,
            "last_seen_stream_seq mismatch at step {idx}"
        );
    }
}

#[test]
fn kotlin_storage_key_golden_vectors_match_pizzora_core() {
    let raw = fs::read_to_string(golden_vector_path("storage_key_parity.json"))
        .expect("read Kotlin-exported storage_key_parity.json");
    let bundle: StorageKeyBundle =
        serde_json::from_str(&raw).expect("parse storage_key_parity.json");

    assert!(!bundle.cases.is_empty());
    for case in &bundle.cases {
        let actual_valid = StorageKeyValidator::validate_object_key(&case.raw_key).is_ok();
        assert_eq!(
            actual_valid, case.expected_valid,
            "storage key validation mismatch for {:?}",
            case.raw_key
        );
    }
}

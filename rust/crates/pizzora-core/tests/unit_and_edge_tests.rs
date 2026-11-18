use std::collections::HashMap;

use pizzora_core::{
    compute_configuration_signature, evaluate_single_item, CartItemInput, CartSyncAction,
    CartSyncPlanRequest, CartSyncPlanner, CartSyncTrigger, CartToppingInput, CartValidation,
    CartValidationIssueCode, CatalogCheeseRef, CatalogCrustRef, CatalogProductRef,
    CatalogRefLookup, CatalogSauceRef, CatalogSizeRuleRef, CatalogToppingRef, CatalogVariantRef,
    CausalDecision, CouponCalculationSpec, CouponEvaluator, CouponRuleInput, DiscountType,
    DomainError, FulfillmentType, Money, OrderCausalTracker, OrderStateMachine, OrderStatus,
    PaymentAttemptStatus, PaymentStateMachine, PaymentStatus, PricedLineInput, PricingEngine,
    RealtimeEventInput, SessionIdentity, SizeCode, StorageKeyValidator, ToppingPlacement, UserRole,
    MAX_CART_LINE_ITEMS, MAX_ITEM_QUANTITY,
};
use uuid::Uuid;

#[test]
fn money_bps_of_and_format_inr_boundaries() {
    assert_eq!(Money::bps_of(0, 500).unwrap(), 0);
    assert_eq!(Money::bps_of(10_000, 0).unwrap(), 0);
    assert_eq!(Money::bps_of(100, 250).unwrap(), 3); // 2.5 -> half-up 3
    assert_eq!(Money::bps_of(100, 249).unwrap(), 2); // 2.49 -> 2
    assert_eq!(Money::bps_of(105, 250).unwrap(), 3); // 2.625 -> 3
    assert_eq!(Money::bps_of(i64::MAX, 10_000).unwrap(), i64::MAX);
    assert!(matches!(
        Money::bps_of(-1, 500),
        Err(DomainError::NegativeAmount("amount_paise"))
    ));
    assert!(matches!(
        Money::bps_of(100, -1),
        Err(DomainError::NegativeAmount("bps"))
    ));
    assert!(matches!(
        Money::bps_of(i64::MAX, 10_001),
        Err(DomainError::ArithmeticOverflow(_))
    ));

    assert_eq!(Money::format_inr(29_900), "₹299.00");
    assert_eq!(Money::format_inr(5), "₹0.05");
    assert_eq!(Money::format_inr(0), "₹0.00");
    assert_eq!(Money::format_inr(-1_250), "-₹12.50");
    assert_eq!(Money::format_inr(i64::MIN), "-₹92233720368547758.08");
}

#[test]
fn pricing_engine_overflow_and_bounds() {
    let overflow_line = PricedLineInput {
        unit_price_paise: i64::MAX / 2,
        quantity: 3,
        is_taxable: true,
    };
    assert!(matches!(
        PricingEngine::calculate_quote(&[overflow_line], 500, 0, None),
        Err(DomainError::ArithmeticOverflow(_))
    ));
    assert!(matches!(
        PricingEngine::calculate_quote(
            &[PricedLineInput {
                unit_price_paise: 10_000,
                quantity: 1,
                is_taxable: true,
            }],
            2900,
            0,
            None,
        ),
        Err(DomainError::RangeViolation {
            field: "gst_bps",
            ..
        })
    ));
    assert!(matches!(
        PricingEngine::calculate_quote(&[], 500, -1, None),
        Err(DomainError::NegativeAmount("delivery_fee_paise"))
    ));
}

#[test]
fn pricing_property_invariants_across_deterministic_matrix() {
    let prices = [0_i64, 1, 99, 100, 105, 19_900, 49_999, 5_000_000_000];
    let gsts = [0_i32, 250, 500, 1200, 1800, 2800];
    let delivery_fees = [0_i64, 2_500, 4_000];
    let coupons = [
        None,
        Some(CouponCalculationSpec {
            discount_type: DiscountType::FlatPaise,
            discount_value: 5_000,
            max_discount_paise: None,
            min_order_paise: 0,
            is_active: true,
        }),
        Some(CouponCalculationSpec {
            discount_type: DiscountType::PercentBps,
            discount_value: 1_234,
            max_discount_paise: Some(50_000),
            min_order_paise: 100,
            is_active: true,
        }),
    ];

    for &p1 in &prices {
        for &p2 in &[0_i64, 10_000, 33_333] {
            for &gst in &gsts {
                for &fee in &delivery_fees {
                    for coupon in &coupons {
                        let lines = vec![
                            PricedLineInput {
                                unit_price_paise: p1,
                                quantity: 2,
                                is_taxable: true,
                            },
                            PricedLineInput {
                                unit_price_paise: p2,
                                quantity: 1,
                                is_taxable: false,
                            },
                        ];
                        let quote =
                            PricingEngine::calculate_quote(&lines, gst, fee, coupon.as_ref())
                                .expect("valid matrix quote");

                        assert!(quote.discount_paise <= quote.subtotal_paise);
                        assert!(quote.discount_paise >= 0);
                        assert_eq!(quote.tax_paise, quote.cgst_paise + quote.sgst_paise);
                        assert!(quote.cgst_paise <= quote.sgst_paise);
                        assert!(quote.sgst_paise - quote.cgst_paise <= 1);
                        assert_eq!(
                            quote.total_paise,
                            (quote.subtotal_paise - quote.discount_paise)
                                + quote.delivery_fee_paise
                                + quote.tax_paise
                        );
                    }
                }
            }
        }
    }
}

#[test]
fn coupon_evaluator_rules_match_backend_service() {
    assert_eq!(
        CouponEvaluator::normalize_code("  pizza_50-vip ").unwrap(),
        "PIZZA_50-VIP"
    );
    assert!(CouponEvaluator::normalize_code("ab").is_err());
    assert!(CouponEvaluator::normalize_code("BAD SPACE").is_err());

    assert!(CouponEvaluator::validate_coupon_params(
        DiscountType::PercentBps,
        10_001,
        None,
        0,
        None,
        1,
        1_000,
        2_000,
    )
    .is_err());

    assert!(CouponEvaluator::validate_coupon_params(
        DiscountType::FlatPaise,
        5_000,
        None,
        0,
        None,
        1,
        2_000,
        2_000,
    )
    .is_err());

    let rule = CouponRuleInput {
        code: "PIZZORA20".to_owned(),
        discount_type: DiscountType::PercentBps,
        discount_value: 2000,
        max_discount_paise: Some(15_000),
        min_order_paise: 50_000,
        max_uses: Some(10),
        max_uses_per_user: 2,
        usage_count: 8,
        active_global_reservations: 1,
        user_used_count: 1,
        starts_at_epoch_ms: 1_000,
        ends_at_epoch_ms: 5_000,
        is_active: true,
    };

    assert_eq!(
        CouponEvaluator::evaluate_eligibility(&rule, 60_000, 2_000, false).unwrap(),
        12_000
    );
    assert_eq!(
        CouponEvaluator::evaluate_eligibility(&rule, 100_000, 2_000, true).unwrap(),
        15_000
    );
    assert!(matches!(
        CouponEvaluator::evaluate_eligibility(&rule, 49_999, 2_000, true),
        Err(DomainError::Unprocessable {
            code: "COUPON_MIN_ORDER_NOT_MET",
            ..
        })
    ));

    let maxed_global = CouponRuleInput {
        active_global_reservations: 2,
        ..rule.clone()
    };
    assert!(matches!(
        CouponEvaluator::evaluate_eligibility(&maxed_global, 60_000, 2_000, true),
        Err(DomainError::Conflict {
            code: "COUPON_USAGE_LIMIT_REACHED",
            ..
        })
    ));
    assert!(matches!(
        CouponEvaluator::evaluate_eligibility(&maxed_global, 60_000, 2_000, false),
        Err(DomainError::Unprocessable {
            code: "COUPON_USAGE_LIMIT_REACHED",
            ..
        })
    ));

    let listing = CouponEvaluator::evaluate_listing(&rule, Some(40_000)).unwrap();
    assert!(!listing.is_eligible);
    assert_eq!(listing.remaining_global_uses, Some(1));
    assert_eq!(listing.remaining_user_uses, 1);
    assert_eq!(
        listing.ineligible_reason.as_deref(),
        Some("Add ₹100.00 more (min order ₹500.00).")
    );
}

#[test]
fn state_machine_exhaustive_properties() {
    let all_order_statuses = [
        OrderStatus::PendingPayment,
        OrderStatus::Placed,
        OrderStatus::Accepted,
        OrderStatus::Preparing,
        OrderStatus::Ready,
        OrderStatus::OutForDelivery,
        OrderStatus::Completed,
        OrderStatus::Cancelled,
        OrderStatus::Expired,
    ];
    let roles = [
        None,
        Some(UserRole::Customer),
        Some(UserRole::KitchenStaff),
        Some(UserRole::Owner),
    ];

    for &status in &all_order_statuses {
        for &fulfillment in &[FulfillmentType::Delivery, FulfillmentType::Pickup] {
            for &role in &roles {
                // Self-transition is never allowed.
                assert!(!OrderStateMachine::can_transition(
                    status,
                    status,
                    fulfillment,
                    role
                ));
                // Terminal statuses never transition out.
                if status.is_terminal() {
                    for &to in &all_order_statuses {
                        assert!(!OrderStateMachine::can_transition(
                            status,
                            to,
                            fulfillment,
                            role
                        ));
                    }
                }
            }
        }
    }

    let all_attempt_statuses = [
        PaymentAttemptStatus::Initiating,
        PaymentAttemptStatus::AwaitingCustomerPayment,
        PaymentAttemptStatus::Authorized,
        PaymentAttemptStatus::Captured,
        PaymentAttemptStatus::Failed,
        PaymentAttemptStatus::Expired,
        PaymentAttemptStatus::Cancelled,
    ];
    for &from in &all_attempt_statuses {
        for &to in &all_attempt_statuses {
            if matches!(
                from,
                PaymentAttemptStatus::Captured
                    | PaymentAttemptStatus::Failed
                    | PaymentAttemptStatus::Expired
                    | PaymentAttemptStatus::Cancelled
            ) {
                assert!(!PaymentStateMachine::can_transition_attempt(from, to));
            }
        }
    }
    assert!(PaymentStateMachine::can_transition_payment(
        PaymentStatus::Expired,
        PaymentStatus::RefundPending
    ));
}

#[test]
fn order_causal_tracker_fifo_eviction_and_resync() {
    let mut tracker = OrderCausalTracker::new(2);
    let mk_event = |id: &str, seq: i64, ev_type: &str, ver: i64| RealtimeEventInput {
        event_id: id.to_owned(),
        stream_seq: seq,
        event_type: ev_type.to_owned(),
        aggregate_type: "ORDER".to_owned(),
        aggregate_id: "ord-1".to_owned(),
        aggregate_version: ver,
        order_id: Some("ord-1".to_owned()),
    };

    assert_eq!(
        tracker.evaluate(&mk_event("e1", 1, "order.created", 1)),
        CausalDecision::ApplyOrderEvent {
            event_id: "e1".to_owned()
        }
    );
    assert_eq!(
        tracker.evaluate(&mk_event("e2", 2, "payment.gateway_ready", 1)),
        CausalDecision::ApplyOrderEvent {
            event_id: "e2".to_owned()
        }
    );
    // Bounded capacity = 2 -> adding e3 evicts e1 from seen_event_ids.
    assert_eq!(
        tracker.evaluate(&mk_event("e3", 3, "order.status_changed", 2)),
        CausalDecision::ApplyOrderEvent {
            event_id: "e3".to_owned()
        }
    );
    assert_eq!(
        tracker.evaluate(&mk_event("e2", 4, "payment.gateway_ready", 1)),
        CausalDecision::IgnoreDuplicate
    );
    // Evicted e1 arrives again -> not a duplicate by ID anymore, but blocked as stale aggregate version.
    assert_eq!(
        tracker.evaluate(&mk_event("e1", 5, "order.created", 1)),
        CausalDecision::IgnoreStale
    );
    // Version gap -> resync trigger.
    assert_eq!(
        tracker.evaluate(&mk_event("e4", 6, "order.status_changed", 4)),
        CausalDecision::TriggerResync {
            order_id: "ord-1".to_owned(),
            event_id: "e4".to_owned(),
        }
    );
    tracker.advance_cursor(100);
    assert_eq!(tracker.last_seen_stream_seq(), 100);
    tracker.clear();
    assert_eq!(tracker.last_seen_stream_seq(), 0);
    assert_eq!(tracker.local_version_for("ord-1"), None);
}

#[test]
fn cart_validation_limits_merge_and_sync_planner() {
    let p_id = Uuid::parse_str("00000000-0000-0000-0000-000000000001").unwrap();
    let v_id = Uuid::parse_str("00000000-0000-0000-0000-000000000011").unwrap();
    let c_id = Uuid::parse_str("00000000-0000-0000-0000-000000000021").unwrap();
    let t1_id = Uuid::parse_str("00000000-0000-0000-0000-000000000032").unwrap();
    let t2_id = Uuid::parse_str("00000000-0000-0000-0000-000000000031").unwrap();

    let too_many: Vec<CartItemInput> = (0..=MAX_CART_LINE_ITEMS)
        .map(|_| CartItemInput {
            id: None,
            product_id: p_id.to_string(),
            variant_id: v_id.to_string(),
            crust_id: c_id.to_string(),
            topping_ids: vec![],
            quantity: 1,
            ..Default::default()
        })
        .collect();
    assert!(matches!(
        CartValidation::validate_and_parse_inputs(&too_many),
        Err(DomainError::Validation {
            code: "CART_LIMIT_EXCEEDED",
            ..
        })
    ));

    // Duplicate configs merge & clamp at MAX_ITEM_QUANTITY (25), deduplicating & sorting toppings.
    let dup_inputs = vec![
        CartItemInput {
            id: Some("line-a".to_owned()),
            product_id: p_id.to_string(),
            variant_id: v_id.to_string(),
            crust_id: c_id.to_string(),
            topping_ids: vec![t1_id.to_string(), t2_id.to_string(), t1_id.to_string()],
            quantity: 20,
            ..Default::default()
        },
        CartItemInput {
            id: Some("line-b".to_owned()),
            product_id: p_id.to_string(),
            variant_id: v_id.to_string(),
            crust_id: c_id.to_string(),
            topping_ids: vec![t2_id.to_string(), t1_id.to_string()],
            quantity: 10,
            ..Default::default()
        },
    ];
    let parsed = CartValidation::validate_and_parse_inputs(&dup_inputs).unwrap();
    let merged = CartValidation::merge_duplicate_configurations(parsed);
    assert_eq!(merged.len(), 1);
    assert_eq!(merged[0].quantity, MAX_ITEM_QUANTITY);
    assert_eq!(merged[0].topping_ids, vec![t2_id, t1_id]); // 0031 < 0032

    let mut lookup = CatalogRefLookup::default();
    lookup.products.insert(
        p_id,
        CatalogProductRef {
            id: p_id,
            name: "Margherita".to_owned(),
            is_veg: true,
            is_taxable: true,
            is_effective_active: true,
            image_url: None,
            is_customizable: true,
            supports_half_and_half: true,
            default_cheese_id: None,
            default_sauce_id: None,
            included_topping_ids: vec![],
            max_toppings_override: None,
        },
    );
    lookup.variants.insert(
        v_id,
        CatalogVariantRef {
            id: v_id,
            product_id: p_id,
            size_code: SizeCode::Medium,
            price_paise: 29_900,
            is_active: true,
        },
    );
    lookup.crusts.insert(
        c_id,
        CatalogCrustRef {
            id: c_id,
            name: "Classic".to_owned(),
            extra_price_paise: 0,
            is_active: true,
            supports_half_and_half: true,
        },
    );
    lookup.toppings.insert(
        t2_id,
        CatalogToppingRef {
            id: t2_id,
            name: "Olives".to_owned(),
            is_veg: true,
            extra_price_paise: 4_500,
            is_active: true,
            max_quantity: 2,
            supports_half_and_half: true,
        },
    );
    lookup.toppings.insert(
        t1_id,
        CatalogToppingRef {
            id: t1_id,
            name: "Jalapeno".to_owned(),
            is_veg: true,
            extra_price_paise: 3_500,
            is_active: false, // unavailable topping flags line unavailable
            max_quantity: 2,
            supports_half_and_half: true,
        },
    );

    CartValidation::ensure_catalog_fk_references(&merged, &lookup).unwrap();
    let evaluated = CartValidation::evaluate_cart(&merged, &lookup).unwrap();
    assert!(!evaluated.all_available);
    assert_eq!(evaluated.unavailable_count, 1);
    assert_eq!(evaluated.subtotal_paise, 0); // unavailable items excluded from cart subtotal
    assert_eq!(evaluated.total_item_count, 25);
    assert_eq!(
        evaluated.items[0].issues[0].code,
        CartValidationIssueCode::ToppingUnavailable
    );

    // CartSyncPlanner session isolation invariants
    let user_a_epoch_1 = SessionIdentity {
        epoch: 1,
        user_id: "user-a".to_owned(),
    };
    let user_b_epoch_2 = SessionIdentity {
        epoch: 2,
        user_id: "user-b".to_owned(),
    };
    assert_eq!(
        CartSyncPlanner::plan(&CartSyncPlanRequest {
            active_session: Some(user_b_epoch_2.clone()),
            captured_session: user_a_epoch_1.clone(),
            trigger: CartSyncTrigger::ExplicitMutation,
            local_item_count: 2,
            persist_to_server: true,
            allow_empty_replace: false,
        }),
        CartSyncAction::DiscardStaleSession
    );
    assert_eq!(
        CartSyncPlanner::plan(&CartSyncPlanRequest {
            active_session: Some(user_b_epoch_2.clone()),
            captured_session: user_b_epoch_2.clone(),
            trigger: CartSyncTrigger::PassiveBootstrap,
            local_item_count: 3,
            persist_to_server: true,
            allow_empty_replace: true,
        }),
        CartSyncAction::FetchServerCart
    );
    assert_eq!(
        CartSyncPlanner::plan(&CartSyncPlanRequest {
            active_session: Some(user_b_epoch_2.clone()),
            captured_session: user_b_epoch_2.clone(),
            trigger: CartSyncTrigger::ExplicitMutation,
            local_item_count: 0,
            persist_to_server: true,
            allow_empty_replace: false,
        }),
        CartSyncAction::FetchServerCart
    );
    assert_eq!(
        CartSyncPlanner::plan(&CartSyncPlanRequest {
            active_session: Some(user_b_epoch_2.clone()),
            captured_session: user_b_epoch_2,
            trigger: CartSyncTrigger::ExplicitClearOrRemove,
            local_item_count: 0,
            persist_to_server: true,
            allow_empty_replace: true,
        }),
        CartSyncAction::ReplaceServerCartEmpty
    );
}

#[test]
fn storage_key_validator_blocks_traversal_and_accepts_canonical_keys() {
    assert_eq!(
        StorageKeyValidator::validate_object_key("products/123/hero-1.webp").unwrap(),
        "products/123/hero-1.webp"
    );
    for bad in [
        "",
        "  ",
        " products/a.jpg",
        "products/a.jpg ",
        "/products/a.jpg",
        "products\\a.jpg",
        "products/\0a.jpg",
        "products//a.jpg",
        "products/../secret.jpg",
        "products/%2e%2e/secret.jpg",
        "products/%2Fsecret.jpg",
        "products/%5Csecret.jpg",
        "-leading-dash.jpg",
    ] {
        assert!(
            StorageKeyValidator::validate_object_key(bad).is_err(),
            "expected key to be rejected: {bad:?}"
        );
    }
}

#[test]
fn custom_pizza_builder_pricing_validation_and_half_and_half_rules() {
    let uid = |n: u128| Uuid::from_u128(0x1000_0000_0000_0000_0000_0000_0000_0000 + n);
    let left_prod = uid(1);
    let right_prod = uid(2);
    let left_reg_var = uid(11);
    let left_xl_var = uid(12);
    let right_xl_var = uid(13);
    let classic_crust = uid(21);
    let cheese_burst = uid(22);
    let mozz_cheese = uid(31);
    let burrata_cheese = uid(32);
    let classic_sauce = uid(41);
    let pesto_sauce = uid(42);
    let onion_topping = uid(51);
    let mushroom_topping = uid(52);
    let truffle_topping = uid(53);

    let mut lookup = CatalogRefLookup {
        size_rules: HashMap::from([
            (
                SizeCode::Regular,
                CatalogSizeRuleRef {
                    size_code: SizeCode::Regular,
                    display_name: "Regular".to_owned(),
                    max_toppings: 3,
                    max_extra_toppings: 2,
                    supports_half_and_half: false,
                    ingredient_multiplier_bps: 7_500,
                    is_active: true,
                },
            ),
            (
                SizeCode::ExtraLarge,
                CatalogSizeRuleRef {
                    size_code: SizeCode::ExtraLarge,
                    display_name: "Extra Large".to_owned(),
                    max_toppings: 6,
                    max_extra_toppings: 3,
                    supports_half_and_half: true,
                    ingredient_multiplier_bps: 17_000,
                    is_active: true,
                },
            ),
        ]),
        cheeses: HashMap::from([
            (
                mozz_cheese,
                CatalogCheeseRef {
                    id: mozz_cheese,
                    name: "Mozzarella".to_owned(),
                    extra_price_paise: 0,
                    extra_cheese_price_paise: 5_000,
                    supports_extra: true,
                    is_vegan: false,
                    is_active: true,
                },
            ),
            (
                burrata_cheese,
                CatalogCheeseRef {
                    id: burrata_cheese,
                    name: "Burrata Blend".to_owned(),
                    extra_price_paise: 6_000,
                    extra_cheese_price_paise: 7_500,
                    supports_extra: true,
                    is_vegan: false,
                    is_active: true,
                },
            ),
        ]),
        sauces: HashMap::from([
            (
                classic_sauce,
                CatalogSauceRef {
                    id: classic_sauce,
                    name: "San Marzano".to_owned(),
                    extra_price_paise: 0,
                    extra_sauce_price_paise: 2_500,
                    supports_extra: true,
                    is_vegan: true,
                    is_active: true,
                },
            ),
            (
                pesto_sauce,
                CatalogSauceRef {
                    id: pesto_sauce,
                    name: "Basil Pesto".to_owned(),
                    extra_price_paise: 4_000,
                    extra_sauce_price_paise: 3_000,
                    supports_extra: true,
                    is_vegan: false,
                    is_active: true,
                },
            ),
        ]),
        crusts: HashMap::from([
            (
                classic_crust,
                CatalogCrustRef {
                    id: classic_crust,
                    name: "Classic Hand Tossed".to_owned(),
                    extra_price_paise: 0,
                    is_active: true,
                    supports_half_and_half: true,
                },
            ),
            (
                cheese_burst,
                CatalogCrustRef {
                    id: cheese_burst,
                    name: "Cheese Burst".to_owned(),
                    extra_price_paise: 9_900,
                    is_active: true,
                    supports_half_and_half: false,
                },
            ),
        ]),
        products: HashMap::from([
            (
                left_prod,
                CatalogProductRef {
                    id: left_prod,
                    name: "Margherita".to_owned(),
                    is_veg: true,
                    is_taxable: true,
                    is_effective_active: true,
                    image_url: None,
                    is_customizable: true,
                    supports_half_and_half: true,
                    default_cheese_id: Some(mozz_cheese),
                    default_sauce_id: Some(classic_sauce),
                    included_topping_ids: vec![onion_topping],
                    max_toppings_override: None,
                },
            ),
            (
                right_prod,
                CatalogProductRef {
                    id: right_prod,
                    name: "Farmhouse".to_owned(),
                    is_veg: true,
                    is_taxable: true,
                    is_effective_active: true,
                    image_url: None,
                    is_customizable: true,
                    supports_half_and_half: true,
                    default_cheese_id: Some(mozz_cheese),
                    default_sauce_id: Some(classic_sauce),
                    included_topping_ids: vec![mushroom_topping],
                    max_toppings_override: None,
                },
            ),
        ]),
        variants: HashMap::from([
            (
                left_reg_var,
                CatalogVariantRef {
                    id: left_reg_var,
                    product_id: left_prod,
                    size_code: SizeCode::Regular,
                    price_paise: 24_900,
                    is_active: true,
                },
            ),
            (
                left_xl_var,
                CatalogVariantRef {
                    id: left_xl_var,
                    product_id: left_prod,
                    size_code: SizeCode::ExtraLarge,
                    price_paise: 49_900,
                    is_active: true,
                },
            ),
            (
                right_xl_var,
                CatalogVariantRef {
                    id: right_xl_var,
                    product_id: right_prod,
                    size_code: SizeCode::ExtraLarge,
                    price_paise: 57_900,
                    is_active: true,
                },
            ),
        ]),
        toppings: HashMap::from([
            (
                onion_topping,
                CatalogToppingRef {
                    id: onion_topping,
                    name: "Red Onion".to_owned(),
                    is_veg: true,
                    extra_price_paise: 3_500,
                    is_active: true,
                    max_quantity: 2,
                    supports_half_and_half: true,
                },
            ),
            (
                mushroom_topping,
                CatalogToppingRef {
                    id: mushroom_topping,
                    name: "Roasted Mushroom".to_owned(),
                    is_veg: true,
                    extra_price_paise: 5_000,
                    is_active: true,
                    max_quantity: 2,
                    supports_half_and_half: true,
                },
            ),
            (
                truffle_topping,
                CatalogToppingRef {
                    id: truffle_topping,
                    name: "Black Truffle".to_owned(),
                    is_veg: true,
                    extra_price_paise: 9_900,
                    is_active: true,
                    max_quantity: 1,
                    supports_half_and_half: false,
                },
            ),
        ]),
    };

    // Valid half-and-half Extra Large with different sauces, extra cheese, included + half toppings
    let item_input = CartItemInput {
        id: Some("hh-1".to_owned()),
        product_id: left_prod.to_string(),
        variant_id: left_xl_var.to_string(),
        crust_id: classic_crust.to_string(),
        quantity: 2,
        cheese_id: Some(burrata_cheese.to_string()),
        extra_cheese: true,
        sauce_id: Some(classic_sauce.to_string()),
        extra_sauce: true,
        is_half_and_half: true,
        right_product_id: Some(right_prod.to_string()),
        right_variant_id: Some(right_xl_var.to_string()),
        right_sauce_id: Some(pesto_sauce.to_string()),
        removed_included_topping_ids: vec![onion_topping.to_string()],
        topping_selections: vec![
            CartToppingInput {
                topping_id: mushroom_topping.to_string(),
                placement: ToppingPlacement::Right,
                quantity: 2, // 1st right-half portion free (included on right_prod), 2nd billed at 50% (2500)
            },
            CartToppingInput {
                topping_id: truffle_topping.to_string(),
                placement: ToppingPlacement::Whole,
                quantity: 1, // 9900
            },
        ],
        ..Default::default()
    };

    let parsed = CartValidation::validate_and_parse_inputs(&[item_input]).unwrap();
    CartValidation::ensure_catalog_fk_references(&parsed, &lookup).unwrap();
    let eval = evaluate_single_item(0, &parsed[0], &lookup).unwrap();
    assert!(eval.is_available, "unexpected issues: {:?}", eval.issues);
    assert_eq!(eval.price_breakdown.base_price_paise, 49_900);
    assert_eq!(eval.price_breakdown.half_and_half_surcharge_paise, 8_000);
    assert_eq!(eval.price_breakdown.cheese_price_paise, 6_000);
    assert_eq!(eval.price_breakdown.extra_cheese_price_paise, 7_500);
    // classic 0 * 0.5 + pesto 4000 * 0.5 = 2000
    assert_eq!(eval.price_breakdown.sauce_price_paise, 2_000);
    // extra sauce: 2500 * 0.5 + 3000 * 0.5 = 1250 + 1500 = 2750
    assert_eq!(eval.price_breakdown.extra_sauce_price_paise, 2_750);
    // toppings: mushroom right (qty 2, 1 included -> billable 1 * 2500 = 2500) + truffle whole (9900) = 12400
    assert_eq!(eval.price_breakdown.toppings_price_paise, 12_400);
    assert_eq!(eval.price_breakdown.unit_price_paise, 88_550);
    assert_eq!(eval.price_breakdown.line_total_paise, 177_100);
    assert_eq!(eval.product_name, "Half Margherita + Half Farmhouse");

    // Merging: implicit default cheese/sauce matches explicit default cheese/sauce signature.
    let implicit_item = CartItemInput {
        product_id: left_prod.to_string(),
        variant_id: left_xl_var.to_string(),
        crust_id: classic_crust.to_string(),
        quantity: 1,
        ..Default::default()
    };
    let explicit_item = CartItemInput {
        product_id: left_prod.to_string(),
        variant_id: left_xl_var.to_string(),
        crust_id: classic_crust.to_string(),
        cheese_id: Some(mozz_cheese.to_string()),
        sauce_id: Some(classic_sauce.to_string()),
        quantity: 2,
        ..Default::default()
    };
    let parsed_pair =
        CartValidation::validate_and_parse_inputs(&[implicit_item, explicit_item]).unwrap();
    assert_eq!(
        compute_configuration_signature(&parsed_pair[0], Some(&lookup)),
        compute_configuration_signature(&parsed_pair[1], Some(&lookup))
    );
    let merged_pair =
        CartValidation::merge_duplicate_configurations_with_lookup(parsed_pair, Some(&lookup));
    assert_eq!(merged_pair.len(), 1);
    assert_eq!(merged_pair[0].quantity, 3);

    // Invalid checks: Regular + Cheese Burst + half-only topping + non-included removal + extra topping limit
    let invalid_item = CartItemInput {
        product_id: left_prod.to_string(),
        variant_id: left_reg_var.to_string(),
        crust_id: cheese_burst.to_string(),
        quantity: 1,
        is_half_and_half: true,
        right_product_id: Some(right_prod.to_string()),
        right_variant_id: Some(right_xl_var.to_string()), // mismatched size too
        removed_included_topping_ids: vec![truffle_topping.to_string()],
        topping_selections: vec![CartToppingInput {
            topping_id: truffle_topping.to_string(),
            placement: ToppingPlacement::Left,
            quantity: 2, // max_quantity is 1, supports_half_and_half is false
        }],
        ..Default::default()
    };
    let parsed_invalid = CartValidation::validate_and_parse_inputs(&[invalid_item]).unwrap();
    let invalid_eval = evaluate_single_item(0, &parsed_invalid[0], &lookup).unwrap();
    assert!(!invalid_eval.is_available);
    assert!(invalid_eval
        .issues
        .iter()
        .any(|i| i.code == CartValidationIssueCode::HalfAndHalfUnsupported));
    assert!(invalid_eval
        .issues
        .iter()
        .any(|i| i.code == CartValidationIssueCode::RightHalfInvalid));
    assert!(invalid_eval
        .issues
        .iter()
        .any(|i| i.code == CartValidationIssueCode::IncludedToppingInvalid));
    assert!(invalid_eval
        .issues
        .iter()
        .any(|i| i.code == CartValidationIssueCode::ToppingPlacementInvalid));
    assert!(invalid_eval
        .issues
        .iter()
        .any(|i| i.code == CartValidationIssueCode::InvalidQuantity));

    // Prevent unused mutation warning while keeping lookup mutable cleanly if extended later
    let _ = &mut lookup;
}

use std::fs;
use std::path::Path;

use pizzora_core::pricing::{
    CouponCalculationSpec, DiscountType, HalfAndHalfInput, PricedLineInput, PricingEngine,
    StandardToppingSelection,
};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
struct GoldenVectorFile {
    vectors: Vec<GoldenVector>,
}

#[derive(Debug, Deserialize)]
struct GoldenVector {
    id: String,
    scenario: String,
    inputs: GoldenVectorInputs,
    #[serde(default)]
    expected_unit_price_paise: Option<i64>,
    #[serde(default)]
    expected_line_total_paise: Option<i64>,
    #[serde(default)]
    expected_quote: Option<ExpectedQuote>,
    #[serde(default)]
    expected_error: Option<String>,
}

#[derive(Debug, Deserialize)]
struct GoldenVectorInputs {
    // U-vector fields (unit pricing)
    variant_price_paise: Option<i64>,
    crust_extra_paise: Option<i64>,
    cheese_extra_paise: Option<i64>,
    sauce_extra_paise: Option<i64>,
    standard_toppings: Option<Vec<GoldenStandardTopping>>,
    extra_topping_extras: Option<Vec<i64>>,
    half_and_half: Option<GoldenHalfAndHalf>,
    quantity: Option<i32>,
    // Q-vector fields (quote calculation)
    lines: Option<Vec<GoldenLine>>,
    gst_bps: Option<i32>,
    delivery_fee_paise: Option<i64>,
    coupon: Option<GoldenCoupon>,
}

#[derive(Debug, Deserialize)]
struct GoldenStandardTopping {
    id: String,
    extra_price_paise: i64,
    is_default: bool,
    is_removed: bool,
}

#[derive(Debug, Deserialize)]
struct GoldenHalfAndHalf {
    left_topping_extras: Vec<i64>,
    right_topping_extras: Vec<i64>,
    surcharge_paise: i64,
}

#[derive(Debug, Deserialize)]
struct GoldenLine {
    unit_price_paise: i64,
    quantity: i32,
    is_taxable: bool,
}

#[derive(Debug, Deserialize)]
struct GoldenCoupon {
    discount_type: String,
    discount_value: i64,
    max_discount_paise: Option<i64>,
    min_order_paise: i64,
    is_active: bool,
}

#[derive(Debug, Deserialize)]
struct ExpectedQuote {
    subtotal_paise: i64,
    taxable_subtotal_paise: i64,
    discount_paise: i64,
    taxable_amount_paise: i64,
    delivery_fee_paise: i64,
    gst_bps: i32,
    tax_paise: i64,
    cgst_paise: i64,
    sgst_paise: i64,
    total_paise: i64,
}

fn load_vectors() -> Vec<GoldenVector> {
    let manifest_dir = env!("CARGO_MANIFEST_DIR");
    let path = Path::new(manifest_dir)
        .join("..")
        .join("..")
        .join("..")
        .join("tools")
        .join("golden-vectors")
        .join("pizzora_phase8_modifier_vectors.json");
    let content = fs::read_to_string(&path).expect("Failed to read golden vectors file");
    let file: GoldenVectorFile = serde_json::from_str(&content).expect("Failed to parse golden vectors JSON");
    file.vectors
}

#[test]
fn phase8_golden_vectors_all_pass() {
    let vectors = load_vectors();
    assert_eq!(vectors.len(), 33, "Expected exactly 33 golden vectors");

    for vector in &vectors {
        println!("Testing vector: {} - {}", vector.id, vector.scenario);

        if vector.id.starts_with("U-") {
            test_unit_pricing_vector(vector);
        } else if vector.id.starts_with("Q-") {
            test_quote_vector(vector);
        } else {
            panic!("Unknown vector type: {}", vector.id);
        }
    }
}

fn test_unit_pricing_vector(vector: &GoldenVector) {
    let inputs = &vector.inputs;

    // Build standard topping selections
    let standard_toppings: Vec<StandardToppingSelection> = inputs
        .standard_toppings
        .as_ref()
        .unwrap_or(&vec![])
        .iter()
        .map(|st| StandardToppingSelection {
            id: uuid::Uuid::parse_str(&st.id).expect("Invalid UUID in golden vector"),
            extra_price_paise: st.extra_price_paise,
            is_default: st.is_default,
            is_removed: st.is_removed,
        })
        .collect();

    // Build half-and-half input if present
    let half_and_half = inputs.half_and_half.as_ref().map(|hah| HalfAndHalfInput {
        left_topping_extras: hah.left_topping_extras.clone(),
        right_topping_extras: hah.right_topping_extras.clone(),
        surcharge_paise: hah.surcharge_paise,
    });

    // Calculate unit price
    let unit_price_result = PricingEngine::calculate_unit_price_paise(
        inputs.variant_price_paise.unwrap_or(0),
        inputs.crust_extra_paise.unwrap_or(0),
        inputs.cheese_extra_paise.unwrap_or(0),
        inputs.sauce_extra_paise.unwrap_or(0),
        // We need to calculate standard toppings extra first
        {
            let std_extra = PricingEngine::calculate_standard_toppings_extra_paise(&standard_toppings)
                .expect("calculate_standard_toppings_extra_paise failed");
            std_extra
        },
        inputs.extra_topping_extras.as_deref().unwrap_or(&[]),
        half_and_half.as_ref(),
    );

    let quantity = inputs.quantity.unwrap_or(1);

    if let Some(expected_error) = &vector.expected_error {
        // Check if unit price calculation itself fails
        if unit_price_result.is_err() {
            let err = unit_price_result.unwrap_err();
            assert!(
                format!("{err}").contains("arithmetic overflow") || format!("{err}").contains("ArithmeticOverflow"),
                "Vector {}: expected ArithmeticOverflow error, got: {}",
                vector.id,
                err
            );
            return;
        }
        // If unit price succeeds, check line total for overflow
        let unit_price = unit_price_result.unwrap();
        let line_total_result = PricingEngine::calculate_line_total_paise(unit_price, quantity);
        assert!(
            line_total_result.is_err(),
            "Vector {}: expected error '{}' but line total calculation succeeded",
            vector.id,
            expected_error
        );
        let err = line_total_result.unwrap_err();
        assert!(
            format!("{err}").contains("arithmetic overflow") || format!("{err}").contains("ArithmeticOverflow"),
            "Vector {}: expected ArithmeticOverflow error, got: {}",
            vector.id,
            err
        );
        return;
    }

    let unit_price = unit_price_result.expect(&format!("Vector {}: calculate_unit_price_paise failed", vector.id));

    assert_eq!(
        unit_price,
        vector.expected_unit_price_paise.expect("expected_unit_price_paise missing"),
        "Vector {}: unit price mismatch",
        vector.id
    );

    // Also test line total if quantity > 1 or expected_line_total_paise is provided
    if quantity != 1 || vector.expected_line_total_paise.is_some() {
        let line_total = PricingEngine::calculate_line_total_paise(unit_price, quantity)
            .expect(&format!("Vector {}: calculate_line_total_paise failed", vector.id));
        if let Some(expected_line_total) = vector.expected_line_total_paise {
            assert_eq!(
                line_total,
                expected_line_total,
                "Vector {}: line total mismatch",
                vector.id
            );
        }
    }
}

fn test_quote_vector(vector: &GoldenVector) {
    let inputs = &vector.inputs;
    let lines = inputs.lines.as_ref().expect("Q vectors must have lines");
    let gst_bps = inputs.gst_bps.expect("Q vectors must have gst_bps");
    let delivery_fee_paise = inputs.delivery_fee_paise.expect("Q vectors must have delivery_fee_paise");

    let priced_lines: Vec<PricedLineInput> = lines
        .iter()
        .map(|l| PricedLineInput {
            unit_price_paise: l.unit_price_paise,
            quantity: l.quantity,
            is_taxable: l.is_taxable,
        })
        .collect();

    let coupon_spec = inputs.coupon.as_ref().map(|c| CouponCalculationSpec {
        discount_type: match c.discount_type.as_str() {
            "PercentBps" => DiscountType::PercentBps,
            "FlatPaise" => DiscountType::FlatPaise,
            other => panic!("Unknown discount type: {}", other),
        },
        discount_value: c.discount_value,
        max_discount_paise: c.max_discount_paise,
        min_order_paise: c.min_order_paise,
        is_active: c.is_active,
    });

    let breakdown = PricingEngine::calculate_quote(
        &priced_lines,
        gst_bps,
        delivery_fee_paise,
        coupon_spec.as_ref(),
    ).expect(&format!("Vector {}: calculate_quote failed", vector.id));

    let expected = vector.expected_quote.as_ref().expect("Q vectors must have expected_quote");

    assert_eq!(breakdown.subtotal_paise, expected.subtotal_paise, "Vector {}: subtotal mismatch", vector.id);
    assert_eq!(breakdown.taxable_subtotal_paise, expected.taxable_subtotal_paise, "Vector {}: taxable_subtotal mismatch", vector.id);
    assert_eq!(breakdown.discount_paise, expected.discount_paise, "Vector {}: discount mismatch", vector.id);
    assert_eq!(breakdown.taxable_amount_paise, expected.taxable_amount_paise, "Vector {}: taxable_amount mismatch", vector.id);
    assert_eq!(breakdown.delivery_fee_paise, expected.delivery_fee_paise, "Vector {}: delivery_fee mismatch", vector.id);
    assert_eq!(breakdown.gst_bps, expected.gst_bps, "Vector {}: gst_bps mismatch", vector.id);
    assert_eq!(breakdown.tax_paise, expected.tax_paise, "Vector {}: tax mismatch", vector.id);
    assert_eq!(breakdown.cgst_paise, expected.cgst_paise, "Vector {}: cgst mismatch", vector.id);
    assert_eq!(breakdown.sgst_paise, expected.sgst_paise, "Vector {}: sgst mismatch", vector.id);
    assert_eq!(breakdown.total_paise, expected.total_paise, "Vector {}: total mismatch", vector.id);
}

#[test]
fn calculate_standard_toppings_extra_paise_zero_credit_default_removal() {
    // Test that removed default toppings contribute 0 (zero credit)
    let selections = vec![
        StandardToppingSelection {
            id: uuid::Uuid::nil(),
            extra_price_paise: 1000,
            is_default: true,
            is_removed: true,
        },
    ];
    let extra = PricingEngine::calculate_standard_toppings_extra_paise(&selections).unwrap();
    assert_eq!(extra, 0, "Removed default topping should contribute 0 (zero credit)");

    // Test that kept default toppings contribute 0
    let selections = vec![
        StandardToppingSelection {
            id: uuid::Uuid::nil(),
            extra_price_paise: 1000,
            is_default: true,
            is_removed: false,
        },
    ];
    let extra = PricingEngine::calculate_standard_toppings_extra_paise(&selections).unwrap();
    assert_eq!(extra, 0, "Kept default topping should contribute 0");

    // Test that non-default selected toppings add their extra
    let selections = vec![
        StandardToppingSelection {
            id: uuid::Uuid::nil(),
            extra_price_paise: 1000,
            is_default: false,
            is_removed: false,
        },
    ];
    let extra = PricingEngine::calculate_standard_toppings_extra_paise(&selections).unwrap();
    assert_eq!(extra, 1000, "Non-default selected topping should add its extra");

    // Test that non-default removed toppings contribute 0 (zero credit)
    let selections = vec![
        StandardToppingSelection {
            id: uuid::Uuid::nil(),
            extra_price_paise: 1000,
            is_default: false,
            is_removed: true,
        },
    ];
    let extra = PricingEngine::calculate_standard_toppings_extra_paise(&selections).unwrap();
    assert_eq!(extra, 0, "Removed non-default topping should contribute 0 (zero credit)");
}

#[test]
fn half_and_half_half_up_odd_paise() {
    // Test HALF_UP rounding for odd paise sums
    // (10001 + 10002 + 1) / 2 = 20004 / 2 = 10002
    let hah = HalfAndHalfInput {
        left_topping_extras: vec![1],
        right_topping_extras: vec![2],
        surcharge_paise: 0,
    };
    let price = PricingEngine::calculate_unit_price_paise(
        10000, 0, 0, 0, 0, &[], Some(&hah),
    ).unwrap();
    assert_eq!(price, 10002, "HALF_UP: (10001+10002)/2 = 10001.5 -> 10002");

    // Test even sum
    // (10000 + 10002 + 1) / 2 = 20003 / 2 = 10001 (truncated)
    let hah = HalfAndHalfInput {
        left_topping_extras: vec![0],
        right_topping_extras: vec![2],
        surcharge_paise: 0,
    };
    let price = PricingEngine::calculate_unit_price_paise(
        10000, 0, 0, 0, 0, &[], Some(&hah),
    ).unwrap();
    assert_eq!(price, 10001, "HALF_UP: (10000+10002)/2 = 10001");

    // Test 3 paise difference: (10000 + 10003 + 1) / 2 = 20004 / 2 = 10002
    let hah = HalfAndHalfInput {
        left_topping_extras: vec![0],
        right_topping_extras: vec![3],
        surcharge_paise: 0,
    };
    let price = PricingEngine::calculate_unit_price_paise(
        10000, 0, 0, 0, 0, &[], Some(&hah),
    ).unwrap();
    assert_eq!(price, 10002, "HALF_UP: (10000+10003)/2 = 10001.5 -> 10002");
}

#[test]
fn arithmetic_overflow_detection() {
    // Test unit price overflow - adding non-zero to i64::MAX will overflow
    let result = PricingEngine::calculate_unit_price_paise(
        i64::MAX, 1, 0, 0, 0, &[], None,
    );
    eprintln!("Result: {:?}", result);
    assert!(result.is_err(), "Expected error but got Ok({:?})", result);
    let err = result.unwrap_err();
    assert!(
        format!("{err}").contains("arithmetic overflow") || format!("{err}").contains("ArithmeticOverflow"),
        "Expected ArithmeticOverflow error for unit price, got: {}",
        err
    );

    // Test line total overflow
    let unit_price = i64::MAX;
    let result = PricingEngine::calculate_line_total_paise(unit_price, 2);
    assert!(result.is_err());
    let err = result.unwrap_err();
    assert!(
        format!("{err}").contains("arithmetic overflow") || format!("{err}").contains("ArithmeticOverflow"),
        "Expected ArithmeticOverflow error for line total, got: {}",
        err
    );

    // Test discount calculation with large values - verify HALF_UP rounding
    // i64::MAX * 10000 fits in i128, and result is capped at subtotal.
    // The line total overflow test above covers the main arithmetic overflow case.
    // Verify that the discount calculation correctly handles large values without panicking.
    let spec = CouponCalculationSpec {
        discount_type: DiscountType::PercentBps,
        discount_value: 5000, // 50%
        max_discount_paise: None,
        min_order_paise: 0,
        is_active: true,
    };
    let result = PricingEngine::calculate_discount_paise(i64::MAX, Some(&spec));
    assert!(result.is_ok(), "Discount calculation should handle large values");
    let discount = result.unwrap();
    // 50% of i64::MAX with HALF_UP: (MAX * 5000 + 5000) / 10000
    let expected = (i128::from(i64::MAX) * 5000 + 5000) / 10000;
    assert_eq!(discount, i64::try_from(expected).unwrap());
}

#[test]
fn validation_error_codes_phase8() {
    use pizzora_core::error::DomainError;

    // Test that the new error variants can be constructed
    let err = DomainError::CheeseUnavailable {
        code: "CHEESE_UNAVAILABLE",
        message: "Test cheese unavailable".to_owned(),
    };
    assert!(format!("{err}").contains("CHEESE_UNAVAILABLE"));

    let err = DomainError::SauceUnavailable {
        code: "SAUCE_UNAVAILABLE",
        message: "Test sauce unavailable".to_owned(),
    };
    assert!(format!("{err}").contains("SAUCE_UNAVAILABLE"));

    let err = DomainError::StandardToppingUnavailable {
        code: "STANDARD_TOPPING_UNAVAILABLE",
        message: "Test standard topping unavailable".to_owned(),
    };
    assert!(format!("{err}").contains("STANDARD_TOPPING_UNAVAILABLE"));

    let err = DomainError::HalfAndHalfUnavailable {
        code: "HALF_AND_HALF_UNAVAILABLE",
        message: "Test half-and-half unavailable".to_owned(),
    };
    assert!(format!("{err}").contains("HALF_AND_HALF_UNAVAILABLE"));

    let err = DomainError::HalfAndHalfToppingLimitExceeded {
        code: "HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED",
        message: "Test limit exceeded".to_owned(),
    };
    assert!(format!("{err}").contains("HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED"));

    let err = DomainError::HalfAndHalfLeftRightOverlap {
        code: "HALF_AND_HALF_LEFT_RIGHT_OVERLAP",
        message: "Test overlap".to_owned(),
    };
    assert!(format!("{err}").contains("HALF_AND_HALF_LEFT_RIGHT_OVERLAP"));

    let err = DomainError::StandardToppingNotInProduct {
        code: "STANDARD_TOPPING_NOT_IN_PRODUCT",
        message: "Test not in product".to_owned(),
    };
    assert!(format!("{err}").contains("STANDARD_TOPPING_NOT_IN_PRODUCT"));

    let err = DomainError::CheeseNotInProduct {
        code: "CHEESE_NOT_IN_PRODUCT",
        message: "Test not in product".to_owned(),
    };
    assert!(format!("{err}").contains("CHEESE_NOT_IN_PRODUCT"));

    let err = DomainError::SauceNotInProduct {
        code: "SAUCE_NOT_IN_PRODUCT",
        message: "Test not in product".to_owned(),
    };
    assert!(format!("{err}").contains("SAUCE_NOT_IN_PRODUCT"));

    let err = DomainError::HalfAndHalfConfigNotInProduct {
        code: "HALF_AND_HALF_CONFIG_NOT_IN_PRODUCT",
        message: "Test not in product".to_owned(),
    };
    assert!(format!("{err}").contains("HALF_AND_HALF_CONFIG_NOT_IN_PRODUCT"));
}

#[test]
fn linear_pricing_breakdown() {
    // Test linear pricing with all modifiers
    let price = PricingEngine::calculate_unit_price_paise(
        10000,  // variant
        1000,   // crust
        2000,   // cheese
        1500,   // sauce
        1000,   // standard_toppings_extra
        &[500, 300], // extra topping extras
        None,   // no half-and-half
    ).unwrap();

    // 10000 + 1000 + 2000 + 1500 + 1000 + 500 + 300 = 16300
    assert_eq!(price, 16300);
}

#[test]
fn half_and_half_breakdown_calculation() {
    // Test half-and-half breakdown calculation
    let hah = HalfAndHalfInput {
        left_topping_extras: vec![4000],
        right_topping_extras: vec![5000],
        surcharge_paise: 1000,
    };

    let (price, breakdown) = PricingEngine::calculate_unit_price_with_breakdown(
        10000,  // variant
        0,      // crust
        0,      // cheese
        0,      // sauce
        0,      // standard_toppings_extra
        &[],    // extra topping extras
        Some(&hah),
    ).unwrap();

    assert_eq!(price, 15500); // (14000 + 15000 + 1) / 2 + 1000 = 14500 + 1000 = 15500

    let bd = breakdown.expect("breakdown should be present for half-and-half");
    assert_eq!(bd.left_half_base_paise, 14000);
    assert_eq!(bd.right_half_base_paise, 15000);
    assert_eq!(bd.average_paise, 14500);
    assert_eq!(bd.surcharge_paise, 1000);
    assert_eq!(bd.cheese_extra_paise, 0);
    assert_eq!(bd.sauce_extra_paise, 0);
    assert_eq!(bd.standard_toppings_extra_paise, 0);
    assert_eq!(bd.unit_price_paise, 15500);
}

#[test]
fn half_and_half_with_all_modifiers() {
    // U-18 scenario: Half 15500 + cheese 2000 + sauce 1500 + standard 700
    let hah = HalfAndHalfInput {
        left_topping_extras: vec![4000],
        right_topping_extras: vec![5000],
        surcharge_paise: 1000,
    };

    let price = PricingEngine::calculate_unit_price_paise(
        10000,  // variant
        0,      // crust
        2000,   // cheese
        1500,   // sauce
        700,    // standard_toppings_extra
        &[],    // extra topping extras
        Some(&hah),
    ).unwrap();

    // average = (14000 + 15000 + 1) / 2 = 14500
    // unit = 14500 + 1000 (surcharge) + 2000 (cheese) + 1500 (sauce) + 700 (standard) = 19700
    assert_eq!(price, 19700);
}
// ---------------------------------------------------------------------------
// Phase 8 §5.3 validation regressions
// ---------------------------------------------------------------------------

use std::collections::HashMap;
use uuid::Uuid;

use pizzora_core::cart_validation::CatalogProductToppingRef;
use pizzora_core::{
    CartValidation, CatalogCheeseRef, CatalogCrustRef, CatalogHalfAndHalfConfigRef,
    CatalogProductRef, CatalogRefLookup, CatalogSauceRef, CatalogStandardToppingRef,
    CatalogToppingRef, CatalogVariantRef, DomainError, ParsedCartItem, SizeCode,
};

fn test_uuid(n: u8) -> Uuid {
    Uuid::from_bytes([n; 16])
}

fn valid_lookup() -> CatalogRefLookup {
    let product = test_uuid(1);
    let variant = test_uuid(2);
    let crust = test_uuid(3);
    let topping = test_uuid(4);
    CatalogRefLookup {
        products: HashMap::from([(
            product,
            CatalogProductRef {
                id: product,
                name: "Margherita".to_owned(),
                is_veg: true,
                is_taxable: true,
                is_effective_active: true,
                image_url: None,
            },
        )]),
        variants: HashMap::from([(
            variant,
            CatalogVariantRef {
                id: variant,
                product_id: product,
                size_code: SizeCode::Regular,
                price_paise: 10000,
                is_active: true,
            },
        )]),
        crusts: HashMap::from([(
            crust,
            CatalogCrustRef {
                id: crust,
                name: "Classic".to_owned(),
                extra_price_paise: 0,
                is_active: true,
            },
        )]),
        toppings: HashMap::from([(
            topping,
            CatalogToppingRef {
                id: topping,
                name: "Extra Cheese".to_owned(),
                is_veg: true,
                extra_price_paise: 500,
                is_active: true,
            },
        )]),
        product_toppings: HashMap::from([(
            product,
            vec![CatalogProductToppingRef {
                product_id: product,
                topping_id: topping,
            }],
        )]),
        ..CatalogRefLookup::default()
    }
}

fn valid_item() -> ParsedCartItem {
    ParsedCartItem {
        item_id: None,
        product_id: test_uuid(1),
        variant_id: test_uuid(2),
        crust_id: test_uuid(3),
        topping_ids: vec![],
        quantity: 1,
        cheese_id: None,
        sauce_id: None,
        standard_topping_ids: vec![],
        half_and_half_config_id: None,
        half_and_half_left_topping_ids: vec![],
        half_and_half_right_topping_ids: vec![],
    }
}

fn assert_validation_code(err: DomainError, expected_code: &str) {
    let rendered = err.to_string();
    assert!(
        rendered.contains(expected_code),
        "expected error code {expected_code}, got {rendered}"
    );
}

#[test]
fn validation_rejects_inactive_and_foreign_modifiers() {
    let product = test_uuid(1);
    let cheese_id = test_uuid(10);
    let sauce_id = test_uuid(11);
    let std_id = test_uuid(12);
    let hah_id = test_uuid(13);
    let other_product = test_uuid(99);

    let mut lookup = valid_lookup();
    lookup.cheeses.insert(
        cheese_id,
        CatalogCheeseRef {
            id: cheese_id,
            product_id: product,
            name: "Mozzarella".to_owned(),
            extra_price_paise: 2000,
            is_active: false,
        },
    );
    lookup.sauces.insert(
        sauce_id,
        CatalogSauceRef {
            id: sauce_id,
            product_id: other_product,
            name: "Pesto".to_owned(),
            extra_price_paise: 1500,
            is_active: true,
        },
    );
    lookup.standard_toppings.insert(
        std_id,
        CatalogStandardToppingRef {
            id: std_id,
            product_id: product,
            name: "Extra Olives".to_owned(),
            is_veg: true,
            extra_price_paise: 700,
            is_default: false,
            is_active: false,
        },
    );
    lookup.half_and_half_configs.insert(
        hah_id,
        CatalogHalfAndHalfConfigRef {
            id: hah_id,
            product_id: product,
            name: "Two Taste".to_owned(),
            left_topping_ids: vec![],
            right_topping_ids: vec![],
            half_and_half_surcharge_paise: 1000,
            is_active: false,
        },
    );

    let mut item = valid_item();
    item.cheese_id = Some(cheese_id);
    let err = CartValidation::ensure_catalog_fk_references(&[item.clone()], &lookup)
        .expect_err("inactive cheese must be rejected");
    assert_validation_code(err, "CHEESE_UNAVAILABLE");

    let mut item = valid_item();
    item.sauce_id = Some(sauce_id);
    let err = CartValidation::ensure_catalog_fk_references(&[item.clone()], &lookup)
        .expect_err("foreign sauce must be rejected");
    assert_validation_code(err, "SAUCE_NOT_IN_PRODUCT");

    let mut item = valid_item();
    item.standard_topping_ids = vec![std_id];
    let err = CartValidation::ensure_catalog_fk_references(&[item.clone()], &lookup)
        .expect_err("inactive standard topping must be rejected");
    assert_validation_code(err, "STANDARD_TOPPING_UNAVAILABLE");

    let mut item = valid_item();
    item.half_and_half_config_id = Some(hah_id);
    let err = CartValidation::ensure_catalog_fk_references(&[item], &lookup)
        .expect_err("inactive half-and-half config must be rejected");
    assert_validation_code(err, "HALF_AND_HALF_UNAVAILABLE");
}

#[test]
fn validation_enforces_half_and_half_limits_without_config_id() {
    let product = test_uuid(1);
    let base_topping = test_uuid(4);
    let mut lookup = valid_lookup();
    let available: Vec<Uuid> = (1..=20u8).map(test_uuid).collect();
    lookup.product_toppings.insert(
        product,
        available
            .iter()
            .map(|id| CatalogProductToppingRef {
                product_id: product,
                topping_id: *id,
            })
            .collect(),
    );
    for id in &available {
        lookup.toppings.insert(
            *id,
            CatalogToppingRef {
                id: *id,
                name: format!("Topping {id}"),
                is_veg: true,
                extra_price_paise: 100,
                is_active: true,
            },
        );
    }

    // >10 toppings on the left half, with NO config ID.
    let mut item = valid_item();
    item.half_and_half_left_topping_ids = available[..11].to_vec();
    let err = CartValidation::ensure_catalog_fk_references(&[item], &lookup)
        .expect_err("left-half limit must apply without config ID");
    assert_validation_code(err, "HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED");

    // >15 distinct toppings total (8 left + 8 right), with NO config ID.
    let mut item = valid_item();
    item.half_and_half_left_topping_ids = available[..8].to_vec();
    item.half_and_half_right_topping_ids = available[8..16].to_vec();
    let err = CartValidation::ensure_catalog_fk_references(&[item], &lookup)
        .expect_err("distinct limit must apply without config ID");
    assert_validation_code(err, "HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED");

    // Left/right overlap, with NO config ID.
    let mut item = valid_item();
    item.half_and_half_left_topping_ids = vec![base_topping, test_uuid(5)];
    item.half_and_half_right_topping_ids = vec![base_topping];
    let err = CartValidation::ensure_catalog_fk_references(&[item], &lookup)
        .expect_err("overlap must apply without config ID");
    assert_validation_code(err, "HALF_AND_HALF_LEFT_RIGHT_OVERLAP");

    // Half-and-half topping absent from product_toppings must be rejected.
    let foreign_topping = test_uuid(200);
    let mut item = valid_item();
    item.half_and_half_left_topping_ids = vec![foreign_topping];
    let err = CartValidation::ensure_catalog_fk_references(&[item], &lookup)
        .expect_err("foreign half-and-half topping must be rejected");
    assert_validation_code(err, "STANDARD_TOPPING_NOT_IN_PRODUCT");
}

#[test]
fn cart_item_input_deserializes_legacy_payload_without_modifiers() {
    let legacy = r#"{
        "id": null,
        "product_id": "00000000-0000-0000-0000-000000000001",
        "variant_id": "00000000-0000-0000-0000-000000000002",
        "crust_id": "00000000-0000-0000-0000-000000000003",
        "topping_ids": [],
        "quantity": 1
    }"#;
    let parsed: pizzora_core::CartItemInput =
        serde_json::from_str(legacy).expect("legacy payload must deserialize");
    assert!(parsed.cheese_id.is_none());
    assert!(parsed.standard_topping_ids.is_empty());
    assert!(parsed.half_and_half_left_topping_ids.is_empty());
}

mod common;

use pizzora_api::dto::{
    CartItemInputDto, CartResponseDto, CartToppingInputDto, CategoryDto, CheckoutRequest,
    CheckoutResponse, CheeseOptionDto, CreateCategoryRequest, CreateCheeseRequest,
    CreateCrustRequest, CreateDeliveryZoneRequest, CreateProductRequest, CreateSauceRequest,
    CreateToppingRequest, CreateVariantRequest, CrustOptionDto, DeliveryZoneDto,
    KdsTicketsResponse, PizzaBuilderQuoteRequest, PizzaBuilderQuoteResponse, PizzaSizeRuleDto,
    ProductDto, PublicMenuResponse, PutRecipeRequest, RecipeIngredientLineRequest,
    RecipeTargetType, SauceOptionDto, ToppingDto, UpdatePizzaSizeRuleRequest,
};
use pizzora_api::error::ApiErrorEnvelope;
use pizzora_core::{
    CartValidationIssueCode, FulfillmentType, OrderStatus, PaymentMethod, ScheduleType, SizeCode,
    ToppingPlacement,
};
use pizzora_db::{new_uuid_v4, AddressDto, InventoryItemDto, OrderDetailDto, UpsertAddressRequest};
use reqwest::StatusCode;
use serde_json::json;

use common::{setup_pool, TestServer};

fn unique_pincode(seed: &uuid::Uuid) -> String {
    let bytes = seed.as_bytes();
    let tail = 10_000 + ((u32::from(bytes[0]) << 8 | u32::from(bytes[1])) % 89_000);
    format!("5{tail:05}")
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_custom_pizza_builder_menu_quote_cart_merge_and_order_snapshot() {
    let pool = setup_pool().await;
    let srv = TestServer::spawn_default(pool).await;
    let uid = new_uuid_v4();
    let tag = &uid.simple().to_string()[..8];
    let pincode = unique_pincode(&uid);

    let (_, owner_tokens) = srv.bootstrap_and_login_owner(tag).await;
    let (_, kds_tokens) = srv
        .create_and_login_kitchen_staff(&owner_tokens.access_token, tag)
        .await;
    let (_, _, cust_a_tokens) = srv.register_and_verify_customer(&format!("ba-{tag}")).await;
    let (_, _, cust_b_tokens) = srv.register_and_verify_customer(&format!("bb-{tag}")).await;

    // 1. Owner seeds categories, toppings, cheeses, sauces, crusts, size rules, and two products
    let cat: CategoryDto = srv
        .client
        .post(srv.url("/api/v1/owner/categories"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&CreateCategoryRequest {
            name: format!("Gourmet-{tag}"),
            sort_order: 1,
            is_active: true,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let cheese_mozz: CheeseOptionDto = srv
        .client
        .post(srv.url("/api/v1/owner/cheeses"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&CreateCheeseRequest {
            name: format!("Mozzarella-{tag}"),
            extra_price_paise: 0,
            extra_cheese_price_paise: 6_000,
            supports_extra: true,
            is_vegan: false,
            is_active: true,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let cheese_no_extra: CheeseOptionDto = srv
        .client
        .post(srv.url("/api/v1/owner/cheeses"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&CreateCheeseRequest {
            name: format!("VeganCashew-{tag}"),
            extra_price_paise: 3_500,
            extra_cheese_price_paise: 0,
            supports_extra: false,
            is_vegan: true,
            is_active: true,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let sauce_marinara: SauceOptionDto = srv
        .client
        .post(srv.url("/api/v1/owner/sauces"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&CreateSauceRequest {
            name: format!("Marinara-{tag}"),
            extra_price_paise: 0,
            extra_sauce_price_paise: 2_500,
            supports_extra: true,
            is_vegan: true,
            is_active: true,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let sauce_pesto: SauceOptionDto = srv
        .client
        .post(srv.url("/api/v1/owner/sauces"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&CreateSauceRequest {
            name: format!("Pesto-{tag}"),
            extra_price_paise: 4_000,
            extra_sauce_price_paise: 3_000,
            supports_extra: true,
            is_vegan: false,
            is_active: true,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let crust_classic: CrustOptionDto = srv
        .client
        .post(srv.url("/api/v1/owner/crusts"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&CreateCrustRequest {
            name: format!("HandTossed-{tag}"),
            extra_price_paise: 0,
            is_active: true,
            supports_half_and_half: true,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let crust_stuffed: CrustOptionDto = srv
        .client
        .post(srv.url("/api/v1/owner/crusts"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&CreateCrustRequest {
            name: format!("CheeseBurst-{tag}"),
            extra_price_paise: 7_500,
            is_active: true,
            supports_half_and_half: false,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let top_basil: ToppingDto = srv
        .client
        .post(srv.url("/api/v1/owner/toppings"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&CreateToppingRequest {
            name: format!("Basil-{tag}"),
            is_veg: true,
            extra_price_paise: 3_000,
            is_active: true,
            max_quantity: 2,
            supports_half_and_half: true,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let top_olives: ToppingDto = srv
        .client
        .post(srv.url("/api/v1/owner/toppings"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&CreateToppingRequest {
            name: format!("Olives-{tag}"),
            is_veg: true,
            extra_price_paise: 4_000,
            is_active: true,
            max_quantity: 2,
            supports_half_and_half: true,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let top_burrata: ToppingDto = srv
        .client
        .post(srv.url("/api/v1/owner/toppings"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&CreateToppingRequest {
            name: format!("WholeBurrata-{tag}"),
            is_veg: true,
            extra_price_paise: 8_000,
            is_active: true,
            max_quantity: 1,
            supports_half_and_half: false,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let left_pizza: ProductDto = srv
        .client
        .post(srv.url("/api/v1/owner/products"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&CreateProductRequest {
            category_id: cat.id.clone(),
            name: format!("Margherita-{tag}"),
            description: "Classic basil & mozzarella".to_owned(),
            is_veg: true,
            is_active: true,
            is_taxable: true,
            variants: vec![
                CreateVariantRequest {
                    size_code: SizeCode::Regular,
                    price_paise: 19_900,
                    is_active: true,
                },
                CreateVariantRequest {
                    size_code: SizeCode::ExtraLarge,
                    price_paise: 49_900,
                    is_active: true,
                },
            ],
            is_customizable: true,
            supports_half_and_half: true,
            default_cheese_id: Some(cheese_mozz.id.clone()),
            default_sauce_id: Some(sauce_marinara.id.clone()),
            included_topping_ids: vec![top_basil.id.clone()],
            max_toppings_override: None,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let right_pizza: ProductDto = srv
        .client
        .post(srv.url("/api/v1/owner/products"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&CreateProductRequest {
            category_id: cat.id.clone(),
            name: format!("Mediterranean-{tag}"),
            description: "Pesto & black olives".to_owned(),
            is_veg: true,
            is_active: true,
            is_taxable: true,
            variants: vec![
                CreateVariantRequest {
                    size_code: SizeCode::Regular,
                    price_paise: 22_900,
                    is_active: true,
                },
                CreateVariantRequest {
                    size_code: SizeCode::ExtraLarge,
                    price_paise: 54_900,
                    is_active: true,
                },
            ],
            is_customizable: true,
            supports_half_and_half: true,
            default_cheese_id: Some(cheese_mozz.id.clone()),
            default_sauce_id: Some(sauce_pesto.id.clone()),
            included_topping_ids: vec![top_olives.id.clone()],
            max_toppings_override: None,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let left_reg_var = left_pizza
        .variants
        .iter()
        .find(|v| v.size_code == SizeCode::Regular)
        .unwrap();
    let left_xl_var = left_pizza
        .variants
        .iter()
        .find(|v| v.size_code == SizeCode::ExtraLarge)
        .unwrap();
    let right_xl_var = right_pizza
        .variants
        .iter()
        .find(|v| v.size_code == SizeCode::ExtraLarge)
        .unwrap();

    // Update LARGE size rule and verify RBAC blocks customers
    let forbidden_rule = srv
        .client
        .patch(srv.url("/api/v1/owner/size-rules/LARGE"))
        .bearer_auth(&cust_a_tokens.access_token)
        .json(&UpdatePizzaSizeRuleRequest {
            max_toppings: Some(12),
            ..Default::default()
        })
        .send()
        .await
        .unwrap();
    assert_eq!(forbidden_rule.status(), StatusCode::FORBIDDEN);

    let updated_rule_resp = srv
        .client
        .patch(srv.url("/api/v1/owner/size-rules/EXTRA_LARGE"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&UpdatePizzaSizeRuleRequest {
            max_toppings: Some(6),
            max_extra_toppings: Some(5),
            ingredient_multiplier_bps: Some(15_000),
            ..Default::default()
        })
        .send()
        .await
        .unwrap();
    assert_eq!(updated_rule_resp.status(), StatusCode::OK);
    let updated_rule: PizzaSizeRuleDto = updated_rule_resp.json().await.unwrap();
    assert_eq!(updated_rule.max_toppings, 6);

    // 2. Verify public menu includes size_rules, cheeses, sauces, and product builder metadata
    let menu: PublicMenuResponse = srv
        .client
        .get(srv.url("/api/v1/catalog/menu"))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(menu
        .size_rules
        .iter()
        .any(|r| r.size_code == SizeCode::ExtraLarge && r.max_toppings == 6));
    assert!(menu.cheeses.iter().any(|c| c.id == cheese_mozz.id));
    assert!(menu.sauces.iter().any(|s| s.id == sauce_pesto.id));
    assert!(menu
        .crusts
        .iter()
        .any(|c| c.id == crust_stuffed.id && !c.supports_half_and_half));
    let menu_left = menu
        .products
        .iter()
        .find(|p| p.id == left_pizza.id)
        .unwrap();
    assert_eq!(menu_left.included_topping_ids, vec![top_basil.id.clone()]);

    // 3. Seed relational inventory + recipes for variant, crust, cheese, sauce, and toppings
    let dough_inv: InventoryItemDto = srv
        .client
        .post(srv.url("/api/v1/owner/inventory"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&json!({
            "name": format!("Dough-{tag}"),
            "unit": "GRAMS",
            "low_stock_threshold": 200,
            "initial_stock_qty": 5000,
            "is_active": true
        }))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let mozz_inv: InventoryItemDto = srv
        .client
        .post(srv.url("/api/v1/owner/inventory"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&json!({
            "name": format!("MozzStock-{tag}"),
            "unit": "GRAMS",
            "low_stock_threshold": 100,
            "initial_stock_qty": 5000,
            "is_active": true
        }))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let olives_inv: InventoryItemDto = srv
        .client
        .post(srv.url("/api/v1/owner/inventory"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&json!({
            "name": format!("OlivesStock-{tag}"),
            "unit": "GRAMS",
            "low_stock_threshold": 50,
            "initial_stock_qty": 2000,
            "is_active": true
        }))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    // Left XL variant: 200g dough; Cheese Mozz: 100g cheese (scaled by 1.5x for XL * 1.5x for extra_cheese);
    // Olives topping: 40g olives (scaled by 1.5x for XL * 0.5x for LEFT/RIGHT)
    for req in [
        PutRecipeRequest {
            target_type: RecipeTargetType::Variant,
            target_id: left_xl_var.id.clone(),
            ingredients: vec![RecipeIngredientLineRequest {
                ingredient_id: dough_inv.ingredient_id.clone(),
                quantity: 200,
            }],
        },
        PutRecipeRequest {
            target_type: RecipeTargetType::Cheese,
            target_id: cheese_mozz.id.clone(),
            ingredients: vec![RecipeIngredientLineRequest {
                ingredient_id: mozz_inv.ingredient_id.clone(),
                quantity: 100,
            }],
        },
        PutRecipeRequest {
            target_type: RecipeTargetType::Topping,
            target_id: top_olives.id.clone(),
            ingredients: vec![RecipeIngredientLineRequest {
                ingredient_id: olives_inv.ingredient_id.clone(),
                quantity: 40,
            }],
        },
    ] {
        let resp = srv
            .client
            .put(srv.url("/api/v1/owner/recipes"))
            .bearer_auth(&owner_tokens.access_token)
            .json(&req)
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
    }

    // 4. Quote validation errors via /api/v1/pizza-builder/quote
    let invalid_half_reg: PizzaBuilderQuoteResponse = srv
        .client
        .post(srv.url("/api/v1/pizza-builder/quote"))
        .bearer_auth(&cust_a_tokens.access_token)
        .json(&PizzaBuilderQuoteRequest {
            item: CartItemInputDto {
                product_id: left_pizza.id.clone(),
                variant_id: left_reg_var.id.clone(),
                crust_id: crust_classic.id.clone(),
                quantity: 1,
                is_half_and_half: true,
                right_product_id: Some(right_pizza.id.clone()),
                ..Default::default()
            },
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(!invalid_half_reg.is_valid);
    assert!(invalid_half_reg
        .issues
        .iter()
        .any(|i| i.code == CartValidationIssueCode::HalfAndHalfUnsupported));

    let invalid_whole_only_top: PizzaBuilderQuoteResponse = srv
        .client
        .post(srv.url("/api/v1/pizza-builder/quote"))
        .bearer_auth(&cust_a_tokens.access_token)
        .json(&PizzaBuilderQuoteRequest {
            item: CartItemInputDto {
                product_id: left_pizza.id.clone(),
                variant_id: left_xl_var.id.clone(),
                crust_id: crust_classic.id.clone(),
                quantity: 1,
                topping_selections: vec![CartToppingInputDto {
                    topping_id: top_burrata.id.clone(),
                    placement: ToppingPlacement::Left,
                    quantity: 1,
                }],
                ..Default::default()
            },
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(!invalid_whole_only_top.is_valid);
    assert!(invalid_whole_only_top
        .issues
        .iter()
        .any(|i| i.code == CartValidationIssueCode::ToppingPlacementInvalid));

    let invalid_extra_cheese: PizzaBuilderQuoteResponse = srv
        .client
        .post(srv.url("/api/v1/pizza-builder/quote"))
        .bearer_auth(&cust_a_tokens.access_token)
        .json(&PizzaBuilderQuoteRequest {
            item: CartItemInputDto {
                product_id: left_pizza.id.clone(),
                variant_id: left_xl_var.id.clone(),
                crust_id: crust_classic.id.clone(),
                cheese_id: Some(cheese_no_extra.id.clone()),
                extra_cheese: true,
                quantity: 1,
                ..Default::default()
            },
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(!invalid_extra_cheese.is_valid);
    assert!(invalid_extra_cheese
        .issues
        .iter()
        .any(|i| i.code == CartValidationIssueCode::ExtraCheeseUnavailable));

    // 5. Valid Half-and-Half XL Pizza quote
    // Left = Margherita (49_900), Right = Mediterranean (54_900) => base_price = 49_900, half_and_half_surcharge = 5_000
    // Crust = HandTossed (0)
    // Cheese = Mozzarella (0) + extra_cheese (6_000) = 6_000
    // Left sauce = Marinara (0 / extra 2_500 -> 50% = 1_250), Right sauce = Pesto (4_000 -> 50% = 2_000 / extra 3_000 -> 50% = 1_500)
    // sauce_price_paise = 2_000, extra_sauce_price_paise = 2_750
    // Toppings = Olives on LEFT qty 1 -> 50% of 4_000 = 2_000
    // Total per unit = 49_900 + 5_000 + 0 + 0 + 6_000 + 2_000 + 2_750 + 2_000 = 67_650 paise
    let builder_item = CartItemInputDto {
        product_id: left_pizza.id.clone(),
        variant_id: left_xl_var.id.clone(),
        crust_id: crust_classic.id.clone(),
        quantity: 1,
        client_unit_price_paise: Some(100), // spoofed price ignored by backend
        cheese_id: Some(cheese_mozz.id.clone()),
        extra_cheese: true,
        sauce_id: Some(sauce_marinara.id.clone()),
        extra_sauce: true,
        is_half_and_half: true,
        right_product_id: Some(right_pizza.id.clone()),
        right_variant_id: Some(right_xl_var.id.clone()),
        right_sauce_id: Some(sauce_pesto.id.clone()),
        removed_included_topping_ids: vec![top_basil.id.clone()],
        topping_selections: vec![CartToppingInputDto {
            topping_id: top_olives.id.clone(),
            placement: ToppingPlacement::Left,
            quantity: 1,
        }],
        ..Default::default()
    };

    let quote: PizzaBuilderQuoteResponse = srv
        .client
        .post(srv.url("/api/v1/pizza-builder/quote"))
        .bearer_auth(&cust_a_tokens.access_token)
        .json(&PizzaBuilderQuoteRequest {
            item: builder_item.clone(),
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(
        quote.is_valid,
        "expected valid quote, issues: {:?}",
        quote.issues
    );
    assert_eq!(quote.unit_price_paise, 67_650);
    assert_eq!(quote.price_breakdown.base_price_paise, 49_900);
    assert_eq!(quote.price_breakdown.half_and_half_surcharge_paise, 5_000);
    assert_eq!(quote.price_breakdown.cheese_price_paise, 0);
    assert_eq!(quote.price_breakdown.extra_cheese_price_paise, 6_000);
    assert_eq!(quote.price_breakdown.sauce_price_paise, 2_000);
    assert_eq!(quote.price_breakdown.extra_sauce_price_paise, 2_750);
    assert_eq!(quote.price_breakdown.toppings_price_paise, 2_000);
    assert_eq!(quote.price_breakdown.unit_price_paise, 67_650);

    // 6. Cart persistence merges identical signatures & isolates users
    let put_cart: CartResponseDto = srv
        .client
        .put(srv.url("/api/v1/cart"))
        .bearer_auth(&cust_a_tokens.access_token)
        .json(&json!({
            "items": [
                builder_item.clone(),
                CartItemInputDto { quantity: 2, ..builder_item.clone() }
            ]
        }))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(put_cart.items.len(), 1);
    assert_eq!(put_cart.items[0].quantity, 3);
    assert_eq!(put_cart.subtotal_paise, 202_950);
    assert!(put_cart.items[0]
        .customization
        .as_ref()
        .is_some_and(|c| c.is_half_and_half && c.extra_cheese && c.extra_sauce));

    let cart_b: CartResponseDto = srv
        .client
        .get(srv.url("/api/v1/cart"))
        .bearer_auth(&cust_b_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(cart_b.items.is_empty());

    // 7. Checkout persists immutable order_items customization_snapshot & reserves scaled inventory
    let _zone: DeliveryZoneDto = srv
        .client
        .post(srv.url("/api/v1/owner/delivery-zones"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&CreateDeliveryZoneRequest {
            pincode: pincode.clone(),
            area_name: format!("Zone-{tag}"),
            min_order_paise: 10_000,
            delivery_fee_paise: 0,
            estimated_mins: 25,
            is_active: true,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let addr: AddressDto = srv
        .client
        .post(srv.url("/api/v1/me/addresses"))
        .bearer_auth(&cust_a_tokens.access_token)
        .json(&UpsertAddressRequest {
            label: "Home".to_owned(),
            line1: "42 Pizza Lane".to_owned(),
            line2: None,
            landmark: None,
            pincode: pincode.clone(),
            city: "Bengaluru".to_owned(),
            is_default: true,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let chk_resp: CheckoutResponse = srv
        .client
        .post(srv.url("/api/v1/checkout"))
        .bearer_auth(&cust_a_tokens.access_token)
        .header("Idempotency-Key", format!("chk-builder-{tag}"))
        .json(&CheckoutRequest {
            items: vec![builder_item],
            fulfillment_type: FulfillmentType::Delivery,
            schedule_type: ScheduleType::Asap,
            scheduled_slot_id: None,
            address_id: Some(addr.id),
            payment_method: PaymentMethod::Cod,
            coupon_code: None,
            clear_cart: true,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(chk_resp.order.status, OrderStatus::Placed);
    assert_eq!(chk_resp.order.items.len(), 1);
    let ord_item = &chk_resp.order.items[0];
    assert_eq!(ord_item.unit_price_paise, 67_650);
    assert!(ord_item
        .customization_snapshot
        .as_ref()
        .is_some_and(|s| s.is_half_and_half
            && s.removed_included_toppings.len() == 1
            && s.topping_details.len() == 1));
    assert_eq!(
        ord_item
            .price_breakdown
            .as_ref()
            .map(|b| b.unit_price_paise),
        Some(67_650)
    );

    // Owner updates prices later -> historical order detail & KDS still keep snapshot
    let fetched_order: OrderDetailDto = srv
        .client
        .get(srv.url(&format!("/api/v1/orders/{}", chk_resp.order.id)))
        .bearer_auth(&cust_a_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(fetched_order.items[0].unit_price_paise, 67_650);
    assert!(fetched_order.items[0].customization_snapshot.is_some());

    let kds_tickets: KdsTicketsResponse = srv
        .client
        .get(srv.url("/api/v1/kds/tickets"))
        .bearer_auth(&kds_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let kds_ticket = kds_tickets
        .tickets
        .iter()
        .find(|t| t.id == chk_resp.order.id)
        .expect("ticket on KDS");
    assert!(kds_ticket.items[0].customization_snapshot.is_some());

    // Inventory reservation check:
    // Olives: 40g * 1.5 (XL) * 0.5 (LEFT) = 30g reserved
    // Mozzarella: 100g * 1.5 (XL) * 1.5 (extra cheese) = 225g reserved
    let inventory: Vec<InventoryItemDto> = srv
        .client
        .get(srv.url("/api/v1/owner/inventory"))
        .bearer_auth(&owner_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let mozz_stock = inventory
        .iter()
        .find(|i| i.ingredient_id == mozz_inv.ingredient_id)
        .unwrap();
    assert_eq!(mozz_stock.reserved_qty, 225);
    let olive_stock = inventory
        .iter()
        .find(|i| i.ingredient_id == olives_inv.ingredient_id)
        .unwrap();
    assert_eq!(olive_stock.reserved_qty, 30);

    // Non-owner rejected on owner cheese/sauce endpoints
    let cust_err_resp = srv
        .client
        .post(srv.url("/api/v1/owner/cheeses"))
        .bearer_auth(&cust_a_tokens.access_token)
        .json(&CreateCheeseRequest {
            name: "BlockedCheese".to_owned(),
            extra_price_paise: 0,
            extra_cheese_price_paise: 0,
            supports_extra: true,
            is_vegan: false,
            is_active: true,
        })
        .send()
        .await
        .unwrap();
    assert_eq!(cust_err_resp.status(), StatusCode::FORBIDDEN);
    let err_env: ApiErrorEnvelope = cust_err_resp.json().await.unwrap();
    assert_eq!(err_env.error.code, "FORBIDDEN");
}

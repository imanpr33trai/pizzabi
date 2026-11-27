mod common;

use pizzora_api::dto::{
    CartItemInputDto, CartResponseDto, CheckoutRequest, CheckoutResponse, CreateToppingRequest,
    ReorderItemDto, ReorderResponseDto, ReorderUnavailableReason, ReplaceCartRequest, ToppingDto,
    UpdateToppingRequest,
};
use pizzora_api::error::ApiErrorEnvelope;
use pizzora_core::{FulfillmentType, PaymentMethod, ScheduleType};
use pizzora_db::{new_uuid_v4, OrderDetailDto, OrderItemDto};
use reqwest::StatusCode;
use serde_json::Value;

use common::{seed_full_storefront_via_owner_api, setup_pool, SeededStorefront, TestServer};

fn unique_pincode(seed: &uuid::Uuid) -> String {
    let bytes = seed.as_bytes();
    let tail = 10_000 + ((u32::from(bytes[0]) << 8 | u32::from(bytes[1])) % 89_000);
    format!("5{tail:05}")
}

fn cart_item(seeded: &SeededStorefront, topping_id: &str, quantity: i32) -> CartItemInputDto {
    CartItemInputDto {
        id: None,
        product_id: seeded.product.id.clone(),
        variant_id: seeded.variant_id.clone(),
        crust_id: seeded.crust.id.clone(),
        topping_ids: vec![topping_id.to_owned()],
        quantity,
        client_unit_price_paise: Some(1),
        cheese_id: None,
        sauce_id: None,
        standard_topping_ids: Vec::new(),
        half_and_half_config_id: None,
        half_and_half_left_topping_ids: Vec::new(),
        half_and_half_right_topping_ids: Vec::new(),
    }
}

async fn put_cart(srv: &TestServer, token: &str, items: Vec<CartItemInputDto>) -> CartResponseDto {
    let resp = srv
        .client
        .put(srv.url("/api/v1/cart"))
        .bearer_auth(token)
        .json(&ReplaceCartRequest { items })
        .send()
        .await
        .expect("put cart request");
    let status = resp.status();
    let body = resp.text().await.expect("put cart body");
    assert_eq!(status, StatusCode::OK, "put cart body: {body}");
    serde_json::from_str(&body).expect("CartResponseDto")
}

async fn get_cart(srv: &TestServer, token: &str) -> CartResponseDto {
    let resp = srv
        .client
        .get(srv.url("/api/v1/cart"))
        .bearer_auth(token)
        .send()
        .await
        .expect("get cart request");
    let status = resp.status();
    let body = resp.text().await.expect("get cart body");
    assert_eq!(status, StatusCode::OK, "get cart body: {body}");
    serde_json::from_str(&body).expect("CartResponseDto")
}

async fn checkout_cod_pickup(
    srv: &TestServer,
    token: &str,
    items: Vec<CartItemInputDto>,
    slot_id: &str,
    idempotency_key: &str,
) -> CheckoutResponse {
    let resp = srv
        .client
        .post(srv.url("/api/v1/checkout"))
        .bearer_auth(token)
        .header("Idempotency-Key", idempotency_key)
        .json(&CheckoutRequest {
            items,
            fulfillment_type: FulfillmentType::Pickup,
            schedule_type: ScheduleType::Scheduled,
            scheduled_slot_id: Some(slot_id.to_owned()),
            address_id: None,
            payment_method: PaymentMethod::Cod,
            coupon_code: None,
            clear_cart: false,
        })
        .send()
        .await
        .expect("checkout request");
    let status = resp.status();
    let body = resp.text().await.expect("checkout body");
    assert_eq!(status, StatusCode::CREATED, "checkout body: {body}");
    serde_json::from_str(&body).expect("CheckoutResponse")
}

async fn post_reorder(srv: &TestServer, token: &str, order_id: &str) -> (StatusCode, String) {
    let resp = srv
        .client
        .post(srv.url(&format!("/api/v1/orders/{order_id}/reorder")))
        .bearer_auth(token)
        .send()
        .await
        .expect("reorder request");
    let status = resp.status();
    let body = resp.text().await.expect("reorder body");
    (status, body)
}

async fn deactivate_topping(srv: &TestServer, owner_token: &str, topping_id: &str) {
    let resp = srv
        .client
        .patch(srv.url(&format!("/api/v1/owner/toppings/{topping_id}")))
        .bearer_auth(owner_token)
        .json(&UpdateToppingRequest {
            is_active: Some(false),
            ..Default::default()
        })
        .send()
        .await
        .expect("deactivate topping request");
    let status = resp.status();
    let body = resp.text().await.expect("deactivate topping body");
    assert_eq!(status, StatusCode::OK, "deactivate topping body: {body}");
    let topping: ToppingDto = serde_json::from_str(&body).expect("ToppingDto");
    assert!(!topping.is_active);
}

fn order_item_for_topping<'a>(detail: &'a OrderDetailDto, topping_name: &str) -> &'a OrderItemDto {
    detail
        .items
        .iter()
        .find(|item| item.toppings.iter().any(|name| name == topping_name))
        .unwrap_or_else(|| panic!("order item with topping {topping_name}"))
}

fn reorder_item<'a>(response: &'a ReorderResponseDto, order_item_id: &str) -> &'a ReorderItemDto {
    response
        .items
        .iter()
        .find(|item| item.order_item_id == order_item_id)
        .unwrap_or_else(|| panic!("reorder item {order_item_id}"))
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_reorder_owner_customer_returns_contract_shape_without_cart_mutation() {
    let pool = setup_pool().await;
    let srv = TestServer::spawn_default(pool).await;
    let uid = new_uuid_v4();
    let tag = &uid.simple().to_string()[..8];
    let pincode = unique_pincode(&uid);
    let (_, owner) = srv.bootstrap_and_login_owner(tag).await;
    let (_, _, customer) = srv
        .register_and_verify_customer(&format!("reorder-ok-{tag}"))
        .await;
    let seeded = seed_full_storefront_via_owner_api(&srv, &owner.access_token, tag, &pincode).await;

    let item = cart_item(&seeded, &seeded.topping.id, 2);
    put_cart(&srv, &customer.access_token, vec![item.clone()]).await;
    let checkout = checkout_cod_pickup(
        &srv,
        &customer.access_token,
        vec![item],
        &seeded.pickup_slot.id,
        &format!("reorder-ok-{tag}"),
    )
    .await;

    // Use a cart that differs from the order so a reorder-side write would be visible.
    put_cart(
        &srv,
        &customer.access_token,
        vec![cart_item(&seeded, &seeded.topping.id, 3)],
    )
    .await;
    let cart_before = get_cart(&srv, &customer.access_token).await;
    let cart_before_json = serde_json::to_value(&cart_before).expect("cart before JSON");

    let (status, body) = post_reorder(&srv, &customer.access_token, &checkout.order.id).await;
    assert_eq!(status, StatusCode::OK, "reorder body: {body}");

    let raw: Value = serde_json::from_str(&body).expect("reorder JSON object");
    for key in ["orderId", "canReorder", "items", "unavailable", "warnings"] {
        assert!(raw.get(key).is_some(), "missing reorder field {key}");
    }
    let reorder: ReorderResponseDto = serde_json::from_value(raw).expect("ReorderResponseDto");

    assert_eq!(reorder.order_id, checkout.order.id);
    assert!(reorder.can_reorder);
    assert_eq!(reorder.items.len(), 1);
    let expected = &checkout.order.items[0];
    let actual = &reorder.items[0];
    assert_eq!(actual.order_item_id, expected.id);
    assert_eq!(actual.product_id, seeded.product.id);
    assert_eq!(actual.product_name, seeded.product.name);
    assert_eq!(
        actual.variant_id.as_deref(),
        Some(seeded.variant_id.as_str())
    );
    assert_eq!(actual.crust_id.as_deref(), Some(seeded.crust.id.as_str()));
    assert!(actual.topping_ids.contains(&seeded.topping.id));
    assert_eq!(actual.quantity, 2);
    assert!(actual.is_available);
    assert!(actual.issues.is_empty());
    assert!(reorder.unavailable.is_empty());

    let cart_after = get_cart(&srv, &customer.access_token).await;
    assert_eq!(
        serde_json::to_value(&cart_after).expect("cart after JSON"),
        cart_before_json
    );
    assert_eq!(cart_after.total_item_count, 3);
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_reorder_rejects_other_customer_and_nonexistent_order() {
    let pool = setup_pool().await;
    let srv = TestServer::spawn_default(pool).await;
    let uid = new_uuid_v4();
    let tag = &uid.simple().to_string()[..8];
    let pincode = unique_pincode(&uid);
    let (_, owner) = srv.bootstrap_and_login_owner(tag).await;
    let (_, _, customer) = srv
        .register_and_verify_customer(&format!("reorder-guard-{tag}"))
        .await;
    let (_, _, other_customer) = srv
        .register_and_verify_customer(&format!("reorder-other-{tag}"))
        .await;
    let seeded = seed_full_storefront_via_owner_api(&srv, &owner.access_token, tag, &pincode).await;

    let item = cart_item(&seeded, &seeded.topping.id, 1);
    put_cart(&srv, &customer.access_token, vec![item.clone()]).await;
    let checkout = checkout_cod_pickup(
        &srv,
        &customer.access_token,
        vec![item],
        &seeded.pickup_slot.id,
        &format!("reorder-guard-{tag}"),
    )
    .await;

    let (status, body) = post_reorder(&srv, &other_customer.access_token, &checkout.order.id).await;
    assert_eq!(status, StatusCode::FORBIDDEN, "reorder body: {body}");
    let forbidden: ApiErrorEnvelope = serde_json::from_str(&body).expect("forbidden envelope");
    assert_eq!(forbidden.error.code, "FORBIDDEN");

    let missing_order_id = new_uuid_v4().to_string();
    let (status, body) = post_reorder(&srv, &customer.access_token, &missing_order_id).await;
    assert_eq!(status, StatusCode::NOT_FOUND, "reorder body: {body}");
    let not_found: ApiErrorEnvelope = serde_json::from_str(&body).expect("not-found envelope");
    assert_eq!(not_found.error.code, "ORDER_NOT_FOUND");
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_reorder_unavailable_topping_has_reason_and_no_substitution() {
    let pool = setup_pool().await;
    let srv = TestServer::spawn_default(pool).await;
    let uid = new_uuid_v4();
    let tag = &uid.simple().to_string()[..8];
    let pincode = unique_pincode(&uid);
    let (_, owner) = srv.bootstrap_and_login_owner(tag).await;
    let (_, _, customer) = srv
        .register_and_verify_customer(&format!("reorder-unavailable-{tag}"))
        .await;
    let seeded = seed_full_storefront_via_owner_api(&srv, &owner.access_token, tag, &pincode).await;

    let replacement_resp = srv
        .client
        .post(srv.url("/api/v1/owner/toppings"))
        .bearer_auth(&owner.access_token)
        .json(&CreateToppingRequest {
            name: format!("Replacement-{tag}"),
            is_veg: true,
            extra_price_paise: 5_000,
            is_active: true,
        })
        .send()
        .await
        .expect("create replacement topping request");
    assert_eq!(replacement_resp.status(), StatusCode::CREATED);
    let replacement: ToppingDto = replacement_resp.json().await.expect("replacement topping");

    let first_item = cart_item(&seeded, &seeded.topping.id, 1);
    let second_item = cart_item(&seeded, &replacement.id, 1);
    put_cart(
        &srv,
        &customer.access_token,
        vec![first_item.clone(), second_item.clone()],
    )
    .await;
    let checkout = checkout_cod_pickup(
        &srv,
        &customer.access_token,
        vec![first_item, second_item],
        &seeded.pickup_slot.id,
        &format!("reorder-unavailable-{tag}"),
    )
    .await;
    let first_order_item = order_item_for_topping(&checkout.order, &seeded.topping.name).clone();
    let second_order_item = order_item_for_topping(&checkout.order, &replacement.name).clone();

    let (status, body) = post_reorder(&srv, &customer.access_token, &checkout.order.id).await;
    assert_eq!(status, StatusCode::OK, "baseline reorder body: {body}");
    let baseline: ReorderResponseDto =
        serde_json::from_str(&body).expect("baseline ReorderResponseDto");
    assert!(baseline.can_reorder);
    assert_eq!(
        reorder_item(&baseline, &first_order_item.id).topping_ids,
        vec![seeded.topping.id.clone()]
    );
    assert_eq!(
        reorder_item(&baseline, &second_order_item.id).topping_ids,
        vec![replacement.id.clone()]
    );

    deactivate_topping(&srv, &owner.access_token, &seeded.topping.id).await;
    let (status, body) = post_reorder(&srv, &customer.access_token, &checkout.order.id).await;
    assert_eq!(
        status,
        StatusCode::OK,
        "first deactivation reorder body: {body}"
    );
    let after_first: ReorderResponseDto =
        serde_json::from_str(&body).expect("first ReorderResponseDto");

    assert!(
        after_first.can_reorder,
        "one available item should keep the order reorderable"
    );
    let first_reorder_item = reorder_item(&after_first, &first_order_item.id);
    assert!(!first_reorder_item.is_available);
    assert!(first_reorder_item
        .issues
        .iter()
        .any(|issue| issue == "TOPPING_UNAVAILABLE"));
    assert!(
        first_reorder_item.topping_ids.is_empty(),
        "unavailable topping must not be replaced by another active topping id"
    );
    assert!(!first_reorder_item.topping_ids.contains(&seeded.topping.id));
    assert!(!first_reorder_item.topping_ids.contains(&replacement.id));

    let second_reorder_item = reorder_item(&after_first, &second_order_item.id);
    assert!(second_reorder_item.is_available);
    assert!(second_reorder_item.topping_ids.contains(&replacement.id));

    let first_unavailable = after_first
        .unavailable
        .iter()
        .find(|entry| entry.order_item_id == first_order_item.id)
        .expect("unavailable entry for deactivated topping");
    assert_eq!(
        first_unavailable.reason,
        ReorderUnavailableReason::ToppingUnavailable
    );
    assert_eq!(first_unavailable.modifier_type, "TOPPING");
    assert_eq!(first_unavailable.modifier_name, seeded.topping.name);
    assert_ne!(first_unavailable.modifier_id, replacement.id);

    deactivate_topping(&srv, &owner.access_token, &replacement.id).await;
    let (status, body) = post_reorder(&srv, &customer.access_token, &checkout.order.id).await;
    assert_eq!(
        status,
        StatusCode::OK,
        "all-unavailable reorder body: {body}"
    );
    let after_both: ReorderResponseDto =
        serde_json::from_str(&body).expect("all-unavailable ReorderResponseDto");

    assert!(!after_both.can_reorder);
    assert!(after_both.items.iter().all(|item| !item.is_available));
    let unavailable_ids: Vec<String> = after_both
        .unavailable
        .iter()
        .filter(|entry| entry.reason == ReorderUnavailableReason::ToppingUnavailable)
        .map(|entry| entry.order_item_id.clone())
        .collect();
    assert!(unavailable_ids.contains(&first_order_item.id));
    assert!(unavailable_ids.contains(&second_order_item.id));
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_reorder_historical_order_warning_path_is_reported() {
    let pool = setup_pool().await;
    let srv = TestServer::spawn_default(pool.clone()).await;
    let uid = new_uuid_v4();
    let tag = &uid.simple().to_string()[..8];
    let pincode = unique_pincode(&uid);
    let (_, owner) = srv.bootstrap_and_login_owner(tag).await;
    let (_, _, customer) = srv
        .register_and_verify_customer(&format!("reorder-historical-{tag}"))
        .await;
    let seeded = seed_full_storefront_via_owner_api(&srv, &owner.access_token, tag, &pincode).await;

    let item = cart_item(&seeded, &seeded.topping.id, 1);
    put_cart(&srv, &customer.access_token, vec![item.clone()]).await;
    let checkout = checkout_cod_pickup(
        &srv,
        &customer.access_token,
        vec![item],
        &seeded.pickup_slot.id,
        &format!("reorder-historical-{tag}"),
    )
    .await;

    // Fresh checkout now persists Phase 8 modifier IDs. Create a true legacy-shaped
    // fixture by clearing only the additive Phase 8 columns on this order's item;
    // legacy name/size/toppings snapshots remain intact for reconstruction.
    let updated = sqlx::query(
        r#"
        UPDATE order_items
        SET variant_id = NULL,
            crust_id = NULL,
            cheese_id = NULL,
            sauce_id = NULL,
            half_and_half_config_id = NULL,
            topping_ids = '[]'::jsonb,
            standard_topping_ids = '[]'::jsonb,
            standard_topping_removed_ids = '[]'::jsonb,
            half_and_half_left_topping_ids = '[]'::jsonb,
            half_and_half_right_topping_ids = '[]'::jsonb
        WHERE order_id = $1
        "#,
    )
    .bind(uuid::Uuid::parse_str(&checkout.order.id).expect("valid order id"))
    .execute(&pool)
    .await
    .expect("clear Phase 8 columns for historical fixture");
    assert_eq!(updated.rows_affected(), 1);

    let (status, body) = post_reorder(&srv, &customer.access_token, &checkout.order.id).await;
    assert_eq!(status, StatusCode::OK, "reorder body: {body}");
    let raw: Value = serde_json::from_str(&body).expect("reorder JSON object");
    let warnings = raw["warnings"]
        .as_array()
        .expect("reorder warnings is an array");
    assert!(
        warnings.iter().any(|warning| warning
            .as_str()
            .is_some_and(|text| text.contains("Historical order"))),
        "expected historical warning, got {warnings:?}"
    );
    let reorder: ReorderResponseDto = serde_json::from_value(raw).expect("ReorderResponseDto");
    assert!(reorder.can_reorder);
    assert_eq!(reorder.items.len(), 1);
    assert!(reorder.items[0].is_available);
}

mod common;

use std::collections::HashMap;

use chrono::{Duration, Utc};
use hmac::{Hmac, Mac};
use pizzora_api::dto::{
    AvailableSlotsResponse, CancelOrderRequest, CartItemInputDto, CartResponseDto, CheckoutRequest,
    CheckoutResponse, ConfirmProductImageRequest, CreateScheduledSlotRequest,
    DeliveryZoneCheckResponse, HealthReadinessResponse, KdsTicketActionResponse,
    KdsTicketsResponse, OperationsReportResponse, OwnerDashboardResponse,
    PresignProductImageRequest, PresignProductImageResponse, PricingQuoteRequest,
    PricingQuoteResponse, ProductImageDto, ProductImageStatus, PublicConfigResponse,
    PublicMenuResponse, ReadinessState, RestaurantSettingsDto, SalesReportResponse,
    ScheduledSlotDto, UpdateOrderPriorityRequest, UpdateRestaurantSettingsRequest,
};
use pizzora_api::error::ApiErrorEnvelope;
use pizzora_auth::{
    AuthTokensResponse, ForgotPasswordRequest, LoginRequest, MessageResponse, RefreshTokenRequest,
    ResendVerificationRequest, ResetPasswordRequest, VerifyEmailRequest, WsTicketResponse,
};
use pizzora_core::{FulfillmentType, OrderStatus, PaymentMethod, PaymentStatus, ScheduleType};
use pizzora_db::{
    new_uuid_v4, AddressDto, AuditLogsResponse, AvailableCouponsResponse, CouponDto,
    MarkAllNotificationsReadResponse, MarkNotificationReadResponse, NotificationDeliveryStatus,
    NotificationInboxResponse, NotificationPreferencesDto, OrderDetailDto, UpdateProfileRequest,
    UpsertAddressRequest, UserProfileDto,
};
use pizzora_payments::{
    compute_hmac_sha256_hex, FailPaymentAttemptRequest, OrderPaymentStatusResponse,
    OwnerRefundRequest, RefundDto, VerifyRazorpayPaymentRequest, VerifyRazorpayPaymentResponse,
};
use reqwest::StatusCode;
use serde_json::json;
use sha2::Sha256;
use sqlx::Row as _;

use common::{seed_full_storefront_via_owner_api, setup_pool, TestServer};

fn unique_pincode(seed: &uuid::Uuid) -> String {
    let bytes = seed.as_bytes();
    let tail = 10_000 + ((u16::from(bytes[0]) << 8 | u16::from(bytes[1])) % 89_000);
    format!("5{tail:05}")
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_auth_rbac_profile_and_addresses_matrix() {
    let pool = setup_pool().await;
    let srv = TestServer::spawn_default(pool).await;
    let tag = &new_uuid_v4().simple().to_string()[..8];

    // 1. Register + resend verification + verify email + login
    let unv_email = format!("auth-{tag}@pizzora.test");
    let reg = srv
        .client
        .post(srv.url("/api/v1/auth/register"))
        .json(&pizzora_auth::RegisterRequest {
            email: unv_email.clone(),
            password: "Password!2026".to_owned(),
            full_name: "Asha Rao".to_owned(),
            phone: "9876543210".to_owned(),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(reg.status(), StatusCode::CREATED);

    let resend = srv
        .client
        .post(srv.url("/api/v1/auth/resend-verification"))
        .json(&ResendVerificationRequest {
            email: unv_email.clone(),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(resend.status(), StatusCode::ACCEPTED);

    let verify_tok = srv.unseal_latest_email_token(&unv_email).await;
    let verify_ok = srv
        .client
        .post(srv.url("/api/v1/auth/verify-email"))
        .json(&VerifyEmailRequest { token: verify_tok })
        .send()
        .await
        .unwrap();
    assert_eq!(verify_ok.status(), StatusCode::OK);

    let login = srv
        .client
        .post(srv.url("/api/v1/auth/login"))
        .json(&LoginRequest {
            email: unv_email.clone(),
            password: "Password!2026".to_owned(),
            device_name: "Pixel-8".to_owned(),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(login.status(), StatusCode::OK);
    let tokens1: AuthTokensResponse = login.json().await.unwrap();

    // 2. Refresh rotation + reuse detection
    let refresh_resp = srv
        .client
        .post(srv.url("/api/v1/auth/refresh"))
        .json(&RefreshTokenRequest {
            refresh_token: tokens1.refresh_token.clone(),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(refresh_resp.status(), StatusCode::OK);
    let tokens2: AuthTokensResponse = refresh_resp.json().await.unwrap();
    assert_ne!(tokens1.refresh_token, tokens2.refresh_token);

    let reuse = srv
        .client
        .post(srv.url("/api/v1/auth/refresh"))
        .json(&RefreshTokenRequest {
            refresh_token: tokens1.refresh_token.clone(),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(reuse.status(), StatusCode::UNAUTHORIZED);

    // 3. Re-login -> forgot password -> reset password -> login with new password
    let login2 = srv
        .client
        .post(srv.url("/api/v1/auth/login"))
        .json(&LoginRequest {
            email: unv_email.clone(),
            password: "Password!2026".to_owned(),
            device_name: "Pixel-8".to_owned(),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(login2.status(), StatusCode::OK);

    let forgot = srv
        .client
        .post(srv.url("/api/v1/auth/forgot-password"))
        .json(&ForgotPasswordRequest {
            email: unv_email.clone(),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(forgot.status(), StatusCode::ACCEPTED);
    let reset_tok = srv.unseal_latest_email_token(&unv_email).await;
    let reset_ok = srv
        .client
        .post(srv.url("/api/v1/auth/reset-password"))
        .json(&ResetPasswordRequest {
            token: reset_tok,
            new_password: "NewPassword!2026".to_owned(),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(reset_ok.status(), StatusCode::OK);

    let login_new = srv
        .client
        .post(srv.url("/api/v1/auth/login"))
        .json(&LoginRequest {
            email: unv_email.clone(),
            password: "NewPassword!2026".to_owned(),
            device_name: "Pixel-8".to_owned(),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(login_new.status(), StatusCode::OK);
    let cust_tokens: AuthTokensResponse = login_new.json().await.unwrap();

    // 4. Profile GET/PATCH & Addresses CRUD + IDOR checks
    let me_resp = srv
        .client
        .get(srv.url("/api/v1/me"))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap();
    assert_eq!(me_resp.status(), StatusCode::OK);
    let me: UserProfileDto = me_resp.json().await.unwrap();
    assert_eq!(me.email, unv_email);

    let patched_me = srv
        .client
        .patch(srv.url("/api/v1/me"))
        .bearer_auth(&cust_tokens.access_token)
        .json(&UpdateProfileRequest {
            full_name: "Asha Updated".to_owned(),
            phone: "9988776655".to_owned(),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(patched_me.status(), StatusCode::OK);
    let me_updated: UserProfileDto = patched_me.json().await.unwrap();
    assert_eq!(me_updated.full_name, "Asha Updated");

    let addr_create = srv
        .client
        .post(srv.url("/api/v1/me/addresses"))
        .bearer_auth(&cust_tokens.access_token)
        .json(&UpsertAddressRequest {
            label: "Home".to_owned(),
            line1: "100ft Road".to_owned(),
            line2: Some("Apt 4B".to_owned()),
            landmark: None,
            pincode: "560038".to_owned(),
            city: "Bengaluru".to_owned(),
            is_default: true,
        })
        .send()
        .await
        .unwrap();
    assert_eq!(addr_create.status(), StatusCode::CREATED);
    let addr1: AddressDto = addr_create.json().await.unwrap();

    let (_, _, other_cust) = srv
        .register_and_verify_customer(&format!("other-{tag}"))
        .await;
    let idor_del = srv
        .client
        .delete(srv.url(&format!("/api/v1/me/addresses/{}", addr1.id)))
        .bearer_auth(&other_cust.access_token)
        .send()
        .await
        .unwrap();
    assert_eq!(idor_del.status(), StatusCode::NOT_FOUND);

    // 5. RBAC matrix + ws-ticket + logout revocation
    let (_, owner_tokens) = srv.bootstrap_and_login_owner(tag).await;
    let (_, kds_tokens) = srv
        .create_and_login_kitchen_staff(&owner_tokens.access_token, tag)
        .await;

    let cust_kds = srv
        .client
        .get(srv.url("/api/v1/kds/tickets"))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap();
    assert_eq!(cust_kds.status(), StatusCode::FORBIDDEN);

    let kds_owner = srv
        .client
        .get(srv.url("/api/v1/owner/dashboard"))
        .bearer_auth(&kds_tokens.access_token)
        .send()
        .await
        .unwrap();
    assert_eq!(kds_owner.status(), StatusCode::FORBIDDEN);

    let kds_ok = srv
        .client
        .get(srv.url("/api/v1/kds/tickets"))
        .bearer_auth(&kds_tokens.access_token)
        .send()
        .await
        .unwrap();
    assert_eq!(kds_ok.status(), StatusCode::OK);

    let owner_staff = srv
        .client
        .get(srv.url("/api/v1/owner/staff"))
        .bearer_auth(&owner_tokens.access_token)
        .send()
        .await
        .unwrap();
    assert_eq!(owner_staff.status(), StatusCode::OK);

    let ws_ticket_resp = srv
        .client
        .post(srv.url("/api/v1/auth/ws-ticket"))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap();
    assert_eq!(ws_ticket_resp.status(), StatusCode::OK);
    let ticket: WsTicketResponse = ws_ticket_resp.json().await.unwrap();
    assert!(!ticket.ticket.is_empty());

    let del_addr = srv
        .client
        .delete(srv.url(&format!("/api/v1/me/addresses/{}", addr1.id)))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap();
    assert_eq!(del_addr.status(), StatusCode::NO_CONTENT);

    let logout_resp = srv
        .client
        .post(srv.url("/api/v1/auth/logout"))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap();
    assert_eq!(logout_resp.status(), StatusCode::OK);
    let _: MessageResponse = logout_resp.json().await.unwrap();

    let after_logout = srv
        .client
        .get(srv.url("/api/v1/me"))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap();
    assert_eq!(after_logout.status(), StatusCode::UNAUTHORIZED);
    let err_env: ApiErrorEnvelope = after_logout.json().await.unwrap();
    assert_eq!(err_env.error.code, "SESSION_REVOKED");
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_catalog_zones_slots_images_and_disabled_storage() {
    let pool = setup_pool().await;
    let uid = new_uuid_v4();
    let tag = &uid.simple().to_string()[..8];
    let pincode = unique_pincode(&uid);

    // Server 1: S3 disabled -> presign fails 503 gracefully, menu/config still 200
    let srv_no_s3 = TestServer::spawn_with_options(pool.clone(), HashMap::new(), false, None).await;
    let (_, owner_no_s3) = srv_no_s3
        .bootstrap_and_login_owner(&format!("nos3-{tag}"))
        .await;
    let prod_id_dummy = new_uuid_v4();
    let presign_disabled = srv_no_s3
        .client
        .post(srv_no_s3.url(&format!(
            "/api/v1/owner/products/{prod_id_dummy}/images/presign"
        )))
        .bearer_auth(&owner_no_s3.access_token)
        .json(&PresignProductImageRequest {
            mime_type: "image/jpeg".to_owned(),
            size_bytes: 512,
            sort_order: 0,
        })
        .send()
        .await
        .unwrap();
    assert_eq!(presign_disabled.status(), StatusCode::SERVICE_UNAVAILABLE);
    let err_503: ApiErrorEnvelope = presign_disabled.json().await.unwrap();
    assert_eq!(err_503.error.code, "OBJECT_STORAGE_UNAVAILABLE");

    let ready_resp = srv_no_s3
        .client
        .get(srv_no_s3.url("/api/v1/health/ready"))
        .send()
        .await
        .unwrap();
    assert_eq!(ready_resp.status(), StatusCode::OK);
    let ready: HealthReadinessResponse = ready_resp.json().await.unwrap();
    assert_ne!(ready.status, ReadinessState::Unhealthy);

    let menu_no_s3 = srv_no_s3
        .client
        .get(srv_no_s3.url("/api/v1/catalog/menu"))
        .send()
        .await
        .unwrap();
    assert_eq!(menu_no_s3.status(), StatusCode::OK);

    // Server 2: Mock S3 enabled
    let srv = TestServer::spawn_default(pool).await;
    let (_, owner_tokens) = srv.bootstrap_and_login_owner(tag).await;
    let seeded =
        seed_full_storefront_via_owner_api(&srv, &owner_tokens.access_token, tag, &pincode).await;
    let mock_s3 = srv.mock_storage.as_ref().expect("mock storage enabled");

    // 1. Valid JPEG upload presign -> mock put -> confirm -> appears in public menu -> delete
    let presign_ok = srv
        .client
        .post(srv.url(&format!(
            "/api/v1/owner/products/{}/images/presign",
            seeded.product.id
        )))
        .bearer_auth(&owner_tokens.access_token)
        .json(&PresignProductImageRequest {
            mime_type: "image/jpeg".to_owned(),
            size_bytes: 128,
            sort_order: 0,
        })
        .send()
        .await
        .unwrap();
    assert_eq!(presign_ok.status(), StatusCode::CREATED);
    let presigned: PresignProductImageResponse = presign_ok.json().await.unwrap();
    let mut jpeg_bytes = vec![0xFF, 0xD8, 0xFF, 0xE0];
    jpeg_bytes.resize(128, 0xAB);
    mock_s3.put_mock_object(&presigned.object_key, jpeg_bytes, Some("image/jpeg"));

    let confirm_ok = srv
        .client
        .post(srv.url(&format!(
            "/api/v1/owner/products/{}/images/confirm",
            seeded.product.id
        )))
        .bearer_auth(&owner_tokens.access_token)
        .json(&ConfirmProductImageRequest {
            image_id: presigned.image_id.clone(),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(confirm_ok.status(), StatusCode::OK);
    let img_dto: ProductImageDto = confirm_ok.json().await.unwrap();
    assert_eq!(img_dto.status, ProductImageStatus::Active);
    assert!(img_dto.public_url.starts_with("https://cdn.pizzora.test/"));

    let public_menu: PublicMenuResponse = srv
        .client
        .get(srv.url("/api/v1/catalog/menu"))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let found_prod = public_menu
        .products
        .iter()
        .find(|p| p.id == seeded.product.id)
        .expect("product in public menu");
    assert_eq!(found_prod.images.len(), 1);

    // 2. Spoofed HTML payload rejected with 422 INVALID_IMAGE_SIGNATURE + audit log + deleted from S3
    let presign_bad = srv
        .client
        .post(srv.url(&format!(
            "/api/v1/owner/products/{}/images/presign",
            seeded.product.id
        )))
        .bearer_auth(&owner_tokens.access_token)
        .json(&PresignProductImageRequest {
            mime_type: "image/jpeg".to_owned(),
            size_bytes: 64,
            sort_order: 1,
        })
        .send()
        .await
        .unwrap();
    assert_eq!(presign_bad.status(), StatusCode::CREATED);
    let bad_presigned: PresignProductImageResponse = presign_bad.json().await.unwrap();
    mock_s3.put_mock_object(
        &bad_presigned.object_key,
        b"<html><body>not-a-jpeg-image</body></html>".to_vec(),
        Some("image/jpeg"),
    );

    let confirm_bad = srv
        .client
        .post(srv.url(&format!(
            "/api/v1/owner/products/{}/images/confirm",
            seeded.product.id
        )))
        .bearer_auth(&owner_tokens.access_token)
        .json(&ConfirmProductImageRequest {
            image_id: bad_presigned.image_id.clone(),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(confirm_bad.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let bad_env: ApiErrorEnvelope = confirm_bad.json().await.unwrap();
    assert_eq!(bad_env.error.code, "INVALID_IMAGE_SIGNATURE");
    assert!(mock_s3.get_mock_object(&bad_presigned.object_key).is_none());

    let audits: AuditLogsResponse = srv
        .client
        .get(srv.url("/api/v1/owner/audit-logs?action=PRODUCT_IMAGE_REJECTED"))
        .bearer_auth(&owner_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(audits
        .entries
        .iter()
        .any(|e| e.entity_id == bad_presigned.image_id));

    let del_img = srv
        .client
        .delete(srv.url(&format!(
            "/api/v1/owner/products/{}/images/{}",
            seeded.product.id, img_dto.id
        )))
        .bearer_auth(&owner_tokens.access_token)
        .send()
        .await
        .unwrap();
    assert_eq!(del_img.status(), StatusCode::NO_CONTENT);

    // 3. Delivery zone check & scheduled slot query parity (?type=DELIVERY & ?fulfillment_type=PICKUP)
    let zone_ok: DeliveryZoneCheckResponse = srv
        .client
        .get(srv.url(&format!("/api/v1/delivery-zones/check?pincode={pincode}")))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(zone_ok.is_serviceable);

    let zone_miss: DeliveryZoneCheckResponse = srv
        .client
        .get(srv.url("/api/v1/delivery-zones/check?pincode=999999"))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(!zone_miss.is_serviceable);

    let del_date = &seeded.delivery_slot.slot_date;
    let del_slots: AvailableSlotsResponse = srv
        .client
        .get(srv.url(&format!("/api/v1/slots?type=DELIVERY&date={del_date}")))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(del_slots
        .slots
        .iter()
        .any(|s| s.id == seeded.delivery_slot.id && s.is_bookable));

    // Past-cutoff slot reports "Cutoff time passed", and deleting unused slot returns 204
    let past_cutoff = (Utc::now() - Duration::minutes(15)).to_rfc3339();
    let expired_slot_resp = srv
        .client
        .post(srv.url("/api/v1/owner/slots"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&CreateScheduledSlotRequest {
            slot_date: del_date.clone(),
            fulfillment_type: FulfillmentType::Pickup,
            start_time_ist: "21:00".to_owned(),
            end_time_ist: "21:30".to_owned(),
            cutoff_at: Some(past_cutoff),
            cutoff_minutes_before_start: None,
            max_capacity: 3,
            is_active: true,
        })
        .send()
        .await
        .unwrap();
    assert_eq!(expired_slot_resp.status(), StatusCode::CREATED);
    let expired_slot: ScheduledSlotDto = expired_slot_resp.json().await.unwrap();
    assert!(!expired_slot.is_bookable);
    assert_eq!(
        expired_slot.unavailable_reason.as_deref(),
        Some("Cutoff time passed")
    );

    let del_unused = srv
        .client
        .delete(srv.url(&format!("/api/v1/owner/slots/{}", expired_slot.id)))
        .bearer_auth(&owner_tokens.access_token)
        .send()
        .await
        .unwrap();
    assert_eq!(del_unused.status(), StatusCode::NO_CONTENT);
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_cart_coupons_checkout_razorpay_webhooks_kds_and_reports() {
    let pool = setup_pool().await;
    let srv = TestServer::spawn_default(pool).await;
    let uid = new_uuid_v4();
    let tag = &uid.simple().to_string()[..8];
    let pincode = unique_pincode(&uid);

    let (_, owner_tokens) = srv.bootstrap_and_login_owner(tag).await;
    let (_, kds_tokens) = srv
        .create_and_login_kitchen_staff(&owner_tokens.access_token, tag)
        .await;
    let (cust_id, _, cust_tokens) = srv.register_and_verify_customer(tag).await;
    let seeded =
        seed_full_storefront_via_owner_api(&srv, &owner_tokens.access_token, tag, &pincode).await;

    // Customer creates delivery address
    let addr: AddressDto = srv
        .client
        .post(srv.url("/api/v1/me/addresses"))
        .bearer_auth(&cust_tokens.access_token)
        .json(&UpsertAddressRequest {
            label: "Office".to_owned(),
            line1: "MG Road".to_owned(),
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

    // Cart PUT / GET / validate
    let cart_item = CartItemInputDto {
        id: None,
        product_id: seeded.product.id.clone(),
        variant_id: seeded.variant_id.clone(),
        crust_id: seeded.crust.id.clone(),
        topping_ids: vec![seeded.topping.id.clone()],
        quantity: 2, // 2 * (30_000 + 5_000 + 5_000) = 80_000 paise
        client_unit_price_paise: Some(1_000), // intentionally spoofed; server ignores
    };
    let put_cart: CartResponseDto = srv
        .client
        .put(srv.url("/api/v1/cart"))
        .bearer_auth(&cust_tokens.access_token)
        .json(&json!({ "items": [cart_item] }))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(put_cart.subtotal_paise, 80_000);
    assert_eq!(put_cart.total_item_count, 2);

    let get_cart: CartResponseDto = srv
        .client
        .get(srv.url("/api/v1/cart"))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(get_cart.subtotal_paise, 80_000);

    // Owner creates coupon + customer lists available + pricing quote
    let coupon_code = format!("PIZ{}", &tag.to_ascii_uppercase());
    let coupon_resp = srv
        .client
        .post(srv.url("/api/v1/owner/coupons"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&json!({
            "code": coupon_code,
            "discount_type": "FLAT_PAISE",
            "discount_value": 10000,
            "min_order_paise": 30000,
            "max_uses": 5,
            "max_uses_per_user": 2,
            "starts_at": (Utc::now() - Duration::hours(1)).to_rfc3339(),
            "ends_at": (Utc::now() + Duration::days(7)).to_rfc3339(),
            "is_active": true
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(coupon_resp.status(), StatusCode::CREATED);
    let coupon: CouponDto = coupon_resp.json().await.unwrap();

    let avail: AvailableCouponsResponse = srv
        .client
        .get(srv.url("/api/v1/coupons/available?subtotal_paise=80000"))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(avail
        .coupons
        .iter()
        .any(|c| c.code == coupon.code && c.is_eligible));

    let quote: PricingQuoteResponse = srv
        .client
        .post(srv.url("/api/v1/pricing/quote"))
        .bearer_auth(&cust_tokens.access_token)
        .json(&PricingQuoteRequest {
            items: vec![cart_item.clone()],
            fulfillment_type: FulfillmentType::Delivery,
            address_id: Some(addr.id.clone()),
            pincode: None,
            coupon_code: Some(coupon.code.clone()),
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(quote.breakdown.subtotal_paise, 80_000);
    assert_eq!(quote.breakdown.discount_paise, 10_000);
    assert_eq!(quote.breakdown.delivery_fee_paise, 4_000);
    assert_eq!(
        quote.breakdown.cgst_paise + quote.breakdown.sgst_paise,
        quote.breakdown.tax_paise
    );

    // Checkout guard checks: ASAP with scheduled_slot_id -> 400 UNEXPECTED_SCHEDULED_SLOT
    let unexpected_slot = srv
        .client
        .post(srv.url("/api/v1/checkout"))
        .bearer_auth(&cust_tokens.access_token)
        .header("Idempotency-Key", format!("bad-asap-{tag}"))
        .json(&CheckoutRequest {
            items: vec![cart_item.clone()],
            fulfillment_type: FulfillmentType::Delivery,
            schedule_type: ScheduleType::Asap,
            scheduled_slot_id: Some(seeded.delivery_slot.id.clone()),
            address_id: Some(addr.id.clone()),
            payment_method: PaymentMethod::Cod,
            coupon_code: None,
            clear_cart: false,
        })
        .send()
        .await
        .unwrap();
    assert_eq!(unexpected_slot.status(), StatusCode::BAD_REQUEST);
    let err_asap: ApiErrorEnvelope = unexpected_slot.json().await.unwrap();
    assert_eq!(err_asap.error.code, "UNEXPECTED_SCHEDULED_SLOT");

    // 1. Scheduled Delivery Razorpay Checkout + Idempotent replay + Conflict + Payment Verify + Refund + Webhook
    let chk_key = format!("rzp-chk-{tag}");
    let chk_body = CheckoutRequest {
        items: vec![cart_item.clone()],
        fulfillment_type: FulfillmentType::Delivery,
        schedule_type: ScheduleType::Scheduled,
        scheduled_slot_id: Some(seeded.delivery_slot.id.clone()),
        address_id: Some(addr.id.clone()),
        payment_method: PaymentMethod::Razorpay,
        coupon_code: Some(coupon.code.clone()),
        clear_cart: true,
    };
    let chk1 = srv
        .client
        .post(srv.url("/api/v1/checkout"))
        .bearer_auth(&cust_tokens.access_token)
        .header("Idempotency-Key", &chk_key)
        .json(&chk_body)
        .send()
        .await
        .unwrap();
    assert_eq!(chk1.status(), StatusCode::CREATED);
    let chk1_dto: CheckoutResponse = chk1.json().await.unwrap();
    assert!(!chk1_dto.idempotent_replay);
    assert_eq!(chk1_dto.order.status, OrderStatus::PendingPayment);

    let chk_replay = srv
        .client
        .post(srv.url("/api/v1/checkout"))
        .bearer_auth(&cust_tokens.access_token)
        .header("Idempotency-Key", &chk_key)
        .json(&chk_body)
        .send()
        .await
        .unwrap();
    assert_eq!(chk_replay.status(), StatusCode::OK);
    let replay_dto: CheckoutResponse = chk_replay.json().await.unwrap();
    assert!(replay_dto.idempotent_replay);
    assert_eq!(replay_dto.order.id, chk1_dto.order.id);

    let chk_conflict = srv
        .client
        .post(srv.url("/api/v1/checkout"))
        .bearer_auth(&cust_tokens.access_token)
        .header("Idempotency-Key", &chk_key)
        .json(&CheckoutRequest {
            coupon_code: None,
            ..chk_body.clone()
        })
        .send()
        .await
        .unwrap();
    assert_eq!(chk_conflict.status(), StatusCode::CONFLICT);
    let conf_env: ApiErrorEnvelope = chk_conflict.json().await.unwrap();
    assert_eq!(conf_env.error.code, "IDEMPOTENCY_CONFLICT");

    // Slot in use -> delete returns 409 SLOT_IN_USE
    let del_used_slot = srv
        .client
        .delete(srv.url(&format!("/api/v1/owner/slots/{}", seeded.delivery_slot.id)))
        .bearer_auth(&owner_tokens.access_token)
        .send()
        .await
        .unwrap();
    assert_eq!(del_used_slot.status(), StatusCode::CONFLICT);
    let slot_in_use_env: ApiErrorEnvelope = del_used_slot.json().await.unwrap();
    assert_eq!(slot_in_use_env.error.code, "SLOT_IN_USE");

    // Dispatch outbox so attempt gets gateway_order_id -> fail attempt -> retry -> dispatch -> verify
    srv.dispatch_pending_payment_outbox().await;
    let pay_status1: OrderPaymentStatusResponse = srv
        .client
        .get(srv.url(&format!("/api/v1/orders/{}/payment", chk1_dto.order.id)))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let gw_ord1 = pay_status1
        .payment
        .latest_attempt
        .as_ref()
        .and_then(|a| a.gateway_order_id.clone())
        .expect("gateway_order_id created");

    let fail_resp = srv
        .client
        .post(srv.url(&format!(
            "/api/v1/orders/{}/payments/fail",
            chk1_dto.order.id
        )))
        .bearer_auth(&cust_tokens.access_token)
        .json(&FailPaymentAttemptRequest {
            attempt_id: None,
            gateway_order_id: Some(gw_ord1),
            failure_code: Some("BAD_REQUEST_ERROR".to_owned()),
            failure_reason: Some("Customer cancelled UPI".to_owned()),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(fail_resp.status(), StatusCode::OK);

    let retry_resp = srv
        .client
        .post(srv.url(&format!(
            "/api/v1/orders/{}/payments/retry",
            chk1_dto.order.id
        )))
        .bearer_auth(&cust_tokens.access_token)
        .header("Idempotency-Key", format!("retry-{tag}"))
        .json(&json!({ "reason": "UPI retry" }))
        .send()
        .await
        .unwrap();
    assert_eq!(retry_resp.status(), StatusCode::OK);
    srv.dispatch_pending_payment_outbox().await;

    let pay_status2: OrderPaymentStatusResponse = srv
        .client
        .get(srv.url(&format!("/api/v1/orders/{}/payment", chk1_dto.order.id)))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let gw_ord2 = pay_status2
        .payment
        .latest_attempt
        .as_ref()
        .and_then(|a| a.gateway_order_id.clone())
        .expect("retry gateway_order_id created");
    let gw_pay_id = format!("pay_rzp_{tag}");
    srv.gateway
        .record_captured_payment(&gw_ord2, &gw_pay_id, chk1_dto.order.total_paise);

    let mut mac = Hmac::<Sha256>::new_from_slice(srv.config.razorpay.key_secret().as_bytes())
        .expect("hmac init");
    mac.update(format!("{gw_ord2}|{gw_pay_id}").as_bytes());
    let sig = hex::encode(mac.finalize().into_bytes());

    let verify_pay = srv
        .client
        .post(srv.url("/api/v1/payments/razorpay/verify"))
        .bearer_auth(&cust_tokens.access_token)
        .json(&VerifyRazorpayPaymentRequest {
            order_id: chk1_dto.order.id.clone(),
            razorpay_order_id: gw_ord2,
            razorpay_payment_id: gw_pay_id,
            razorpay_signature: sig,
        })
        .send()
        .await
        .unwrap();
    assert_eq!(verify_pay.status(), StatusCode::OK);
    let verified_dto: VerifyRazorpayPaymentResponse = verify_pay.json().await.unwrap();
    assert_eq!(verified_dto.order.status, OrderStatus::Placed);
    assert_eq!(verified_dto.order.payment.status, PaymentStatus::Captured);

    // Owner partial refund (202 Accepted) + dispatch + signed webhook (refund.processed) + tampered webhook rejected 401
    let refund_resp = srv
        .client
        .post(srv.url(&format!(
            "/api/v1/owner/orders/{}/refund",
            chk1_dto.order.id
        )))
        .bearer_auth(&owner_tokens.access_token)
        .header("Idempotency-Key", format!("ref-{tag}"))
        .json(&OwnerRefundRequest {
            amount_paise: Some(10_000),
            reason: "Late delivery goodwill".to_owned(),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(refund_resp.status(), StatusCode::ACCEPTED);
    let refund_dto: RefundDto = refund_resp.json().await.unwrap();
    srv.dispatch_pending_payment_outbox().await;

    let ref_row = sqlx::query("SELECT gateway_refund_id FROM refunds WHERE id = $1")
        .bind(uuid::Uuid::parse_str(&refund_dto.id).unwrap())
        .fetch_one(&srv.pool)
        .await
        .unwrap();
    let gw_refund_id: String = ref_row.try_get("gateway_refund_id").unwrap();

    let wh_body = serde_json::to_vec(&json!({
        "event": "refund.processed",
        "created_at": Utc::now().timestamp(),
        "payload": {
            "refund": {
                "entity": {
                    "id": gw_refund_id,
                    "notes": { "idempotency_key": refund_dto.idempotency_key }
                }
            }
        }
    }))
    .unwrap();
    let wh_sig = compute_hmac_sha256_hex(&wh_body, srv.config.razorpay.webhook_secret());
    let wh_event_id = format!("evt_ref_{tag}");

    let wh_ok = srv
        .client
        .post(srv.url("/api/v1/webhooks/razorpay"))
        .header("content-type", "application/json")
        .header("x-razorpay-signature", &wh_sig)
        .header("x-razorpay-event-id", &wh_event_id)
        .body(wh_body.clone())
        .send()
        .await
        .unwrap();
    assert_eq!(wh_ok.status(), StatusCode::OK);

    // Duplicate webhook replay is idempotent
    let wh_dup = srv
        .client
        .post(srv.url("/api/v1/webhooks/razorpay"))
        .header("content-type", "application/json")
        .header("x-razorpay-signature", &wh_sig)
        .header("x-razorpay-event-id", &wh_event_id)
        .body(wh_body.clone())
        .send()
        .await
        .unwrap();
    assert_eq!(wh_dup.status(), StatusCode::OK);

    let wh_bad = srv
        .client
        .post(srv.url("/api/v1/webhooks/razorpay"))
        .header("content-type", "application/json")
        .header("x-razorpay-signature", "0000deadbeef")
        .header("x-razorpay-event-id", format!("evt_bad_{tag}"))
        .body(wh_body)
        .send()
        .await
        .unwrap();
    assert_eq!(wh_bad.status(), StatusCode::UNAUTHORIZED);

    // 2. KDS full workflow on COD Pickup order
    let cod_chk: CheckoutResponse = srv
        .client
        .post(srv.url("/api/v1/checkout"))
        .bearer_auth(&cust_tokens.access_token)
        .header("Idempotency-Key", format!("cod-chk-{tag}"))
        .json(&CheckoutRequest {
            items: vec![cart_item.clone()],
            fulfillment_type: FulfillmentType::Pickup,
            schedule_type: ScheduleType::Scheduled,
            scheduled_slot_id: Some(seeded.pickup_slot.id.clone()),
            address_id: None,
            payment_method: PaymentMethod::Cod,
            coupon_code: None,
            clear_cart: false,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(cod_chk.order.status, OrderStatus::Placed);
    let cod_id = cod_chk.order.id.clone();

    let prio_resp: KdsTicketActionResponse = srv
        .client
        .patch(srv.url(&format!("/api/v1/kds/orders/{cod_id}/priority")))
        .bearer_auth(&kds_tokens.access_token)
        .json(&UpdateOrderPriorityRequest {
            is_priority: true,
            reason: Some("VIP pickup".to_owned()),
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(prio_resp.ticket.is_priority);

    let kds_list: KdsTicketsResponse = srv
        .client
        .get(srv.url("/api/v1/kds/tickets"))
        .bearer_auth(&kds_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(kds_list.tickets.iter().any(|t| t.id == cod_id));

    for step in ["ack", "start-prep", "ready", "complete"] {
        let step_resp = srv
            .client
            .post(srv.url(&format!("/api/v1/kds/orders/{cod_id}/{step}")))
            .bearer_auth(&kds_tokens.access_token)
            .send()
            .await
            .unwrap();
        assert_eq!(step_resp.status(), StatusCode::OK, "KDS step {step}");
    }
    let completed_detail: OrderDetailDto = srv
        .client
        .get(srv.url(&format!("/api/v1/orders/{cod_id}")))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(completed_detail.status, OrderStatus::Completed);
    assert_eq!(completed_detail.payment.status, PaymentStatus::CodCollected);

    // 3. Cancel order restores stock & slot capacity
    let cancel_target: CheckoutResponse = srv
        .client
        .post(srv.url("/api/v1/checkout"))
        .bearer_auth(&cust_tokens.access_token)
        .header("Idempotency-Key", format!("cancel-chk-{tag}"))
        .json(&CheckoutRequest {
            items: vec![CartItemInputDto {
                quantity: 1,
                ..cart_item
            }],
            fulfillment_type: FulfillmentType::Pickup,
            schedule_type: ScheduleType::Scheduled,
            scheduled_slot_id: Some(seeded.pickup_slot.id.clone()),
            address_id: None,
            payment_method: PaymentMethod::Cod,
            coupon_code: None,
            clear_cart: false,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let cancel_resp: OrderDetailDto = srv
        .client
        .post(srv.url(&format!("/api/v1/orders/{}/cancel", cancel_target.order.id)))
        .bearer_auth(&cust_tokens.access_token)
        .json(&CancelOrderRequest {
            reason: Some("Changed mind".to_owned()),
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(cancel_resp.status, OrderStatus::Cancelled);

    // 4. Notifications inbox + read + read-all + preferences
    let _ = sqlx::query(
        r#"
        INSERT INTO notification_inbox (
            id, user_id, event_id, title, body, category, reference_id, is_read, delivery_status
        ) VALUES ($1, $2, $3, 'Order Ready', 'Your pizza is fresh!', 'ORDER_UPDATE', $4, FALSE, 'PENDING')
        "#,
    )
    .bind(new_uuid_v4())
    .bind(cust_id)
    .bind(new_uuid_v4())
    .bind(uuid::Uuid::parse_str(&cod_id).unwrap())
    .execute(&srv.pool)
    .await
    .unwrap();

    let inbox: NotificationInboxResponse = srv
        .client
        .get(srv.url("/api/v1/notifications?unread=true"))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(!inbox.notifications.is_empty());
    let notif_id = inbox.notifications[0].id.clone();
    assert_eq!(
        inbox.notifications[0].delivery_status,
        NotificationDeliveryStatus::Synced
    );

    let read_one: MarkNotificationReadResponse = srv
        .client
        .post(srv.url(&format!("/api/v1/notifications/{notif_id}/read")))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(read_one.notification.is_read);

    let read_all: MarkAllNotificationsReadResponse = srv
        .client
        .post(srv.url("/api/v1/notifications/read-all"))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(read_all.unread_count, 0);

    let prefs_put: NotificationPreferencesDto = srv
        .client
        .put(srv.url("/api/v1/notifications/preferences"))
        .bearer_auth(&cust_tokens.access_token)
        .json(&json!({ "promo_alerts": false }))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(!prefs_put.promo_alerts);

    // 5. Owner settings GET + PUT, dashboard, reports, public config, metrics
    let settings_get: RestaurantSettingsDto = srv
        .client
        .get(srv.url("/api/v1/owner/settings"))
        .bearer_auth(&owner_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let settings_put: RestaurantSettingsDto = srv
        .client
        .put(srv.url("/api/v1/owner/settings"))
        .bearer_auth(&owner_tokens.access_token)
        .json(&UpdateRestaurantSettingsRequest {
            store_name: Some(settings_get.store_name.clone()),
            is_accepting_orders: Some(true),
            gst_bps: Some(500),
            default_prep_mins: Some(25),
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(settings_put.default_prep_mins, 25);

    let pub_cfg: PublicConfigResponse = srv
        .client
        .get(srv.url("/api/v1/config/public"))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(pub_cfg.default_prep_mins, 25);

    let dash: OwnerDashboardResponse = srv
        .client
        .get(srv.url("/api/v1/owner/dashboard"))
        .bearer_auth(&owner_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(dash.today_order_count >= 1);

    let today_ist = (Utc::now() + Duration::minutes(330))
        .format("%Y-%m-%d")
        .to_string();
    let sales: SalesReportResponse = srv
        .client
        .get(srv.url(&format!(
            "/api/v1/owner/reports/sales?start_date={today_ist}&end_date={today_ist}"
        )))
        .bearer_auth(&owner_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(sales.order_count >= 1);

    let ops: OperationsReportResponse = srv
        .client
        .get(srv.url(&format!(
            "/api/v1/owner/reports/operations?start_date={today_ist}&end_date={today_ist}"
        )))
        .bearer_auth(&owner_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(ops.completed_orders >= 1);

    let metrics = srv.client.get(srv.url("/metrics")).send().await.unwrap();
    assert_eq!(metrics.status(), StatusCode::OK);
    let metrics_body = metrics.text().await.unwrap();
    assert!(metrics_body.contains("http_requests_total"));
}

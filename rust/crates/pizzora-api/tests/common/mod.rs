#![allow(dead_code)]

use std::collections::{BTreeMap, HashMap};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use pizzora_api::dto::{
    CategoryDto, CreateCategoryRequest, CreateCrustRequest, CreateDeliveryZoneRequest,
    CreateProductRequest, CreateScheduledSlotRequest, CreateToppingRequest, CreateVariantRequest,
    CrustOptionDto, DeliveryZoneDto, ProductDto, PutRecipeRequest, RecipeIngredientLineRequest,
    RecipeTargetType, ScheduledSlotDto, ToppingDto,
};
use pizzora_api::error::ApiError;
use pizzora_api::router::build_router;
use pizzora_api::state::AppState;
use pizzora_api::storage::{
    BoxFuture, ObjectStorageClient, PresignedPutUpload, StoredObjectMetadata,
};
use pizzora_auth::{
    unseal_outbox_email_payload, AuthTokensResponse, BootstrapOwner, CreateStaffRequest,
    LoginRequest, OutboxEmailPayload, PasswordHasher, RegisterRequest, VerifyEmailRequest,
};
use pizzora_config::AppConfig;
use pizzora_core::{FulfillmentType, SizeCode, UserRole};
use pizzora_db::{
    connect_pg_pool, new_uuid_v4, run_locked_handoff, EphemeralCacheStore, InMemoryCacheStore,
    InventoryItemDto, OutboxRepository, RedisStore, EVENT_EMAIL_DELIVERY_REQUESTED,
};
use pizzora_payments::{InMemoryRazorpayGateway, RazorpayGateway, RazorpayReconciliationService};
use sqlx::{PgPool, Row as _};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use uuid::Uuid;

type StoredObjectMap = HashMap<String, (Vec<u8>, Option<String>)>;

#[derive(Clone, Default)]
pub struct MockObjectStorageClient {
    objects: Arc<Mutex<StoredObjectMap>>,
}

impl MockObjectStorageClient {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn put_mock_object(&self, key: &str, bytes: Vec<u8>, content_type: Option<&str>) {
        self.objects
            .lock()
            .unwrap()
            .insert(key.to_owned(), (bytes, content_type.map(ToOwned::to_owned)));
    }

    pub fn get_mock_object(&self, key: &str) -> Option<(Vec<u8>, Option<String>)> {
        self.objects.lock().unwrap().get(key).cloned()
    }

    pub fn object_count(&self) -> usize {
        self.objects.lock().unwrap().len()
    }
}

impl ObjectStorageClient for MockObjectStorageClient {
    fn is_enabled(&self) -> bool {
        true
    }

    fn generate_presigned_put_url<'a>(
        &'a self,
        object_key: &'a str,
        content_type: &'a str,
        ttl_seconds: i64,
    ) -> BoxFuture<'a, Result<PresignedPutUpload, ApiError>> {
        Box::pin(async move {
            let mut required_headers = BTreeMap::new();
            required_headers.insert("Content-Type".to_owned(), content_type.to_owned());
            Ok(PresignedPutUpload {
                upload_url: format!("https://mock-s3.pizzora.test/{object_key}?presigned=1"),
                required_headers,
                expires_in_seconds: ttl_seconds,
            })
        })
    }

    fn head_object<'a>(
        &'a self,
        object_key: &'a str,
    ) -> BoxFuture<'a, Result<StoredObjectMetadata, ApiError>> {
        Box::pin(async move {
            let guard = self.objects.lock().unwrap();
            let Some((bytes, content_type)) = guard.get(object_key) else {
                return Err(ApiError::validation(
                    "IMAGE_NOT_UPLOADED",
                    "Uploaded image object was not found in object storage.",
                ));
            };
            Ok(StoredObjectMetadata {
                object_key: object_key.to_owned(),
                content_length: bytes.len() as i64,
                content_type: content_type.clone(),
            })
        })
    }

    fn read_object_header<'a>(
        &'a self,
        object_key: &'a str,
        max_bytes: usize,
    ) -> BoxFuture<'a, Result<Vec<u8>, ApiError>> {
        Box::pin(async move {
            let guard = self.objects.lock().unwrap();
            let Some((bytes, _)) = guard.get(object_key) else {
                return Err(ApiError::validation(
                    "IMAGE_NOT_UPLOADED",
                    "Uploaded image object was not found in object storage.",
                ));
            };
            Ok(bytes.iter().take(max_bytes).copied().collect())
        })
    }

    fn delete_object<'a>(&'a self, object_key: &'a str) -> BoxFuture<'a, Result<(), ApiError>> {
        Box::pin(async move {
            self.objects.lock().unwrap().remove(object_key);
            Ok(())
        })
    }

    fn resolve_public_url(&self, object_key: &str) -> Option<String> {
        Some(format!(
            "https://cdn.pizzora.test/{}",
            object_key.trim_start_matches('/')
        ))
    }
}

pub async fn setup_pool() -> PgPool {
    let cfg = AppConfig::from_env().expect("env config for PG 18.6 integration test");
    let pool = connect_pg_pool(&cfg.database)
        .await
        .expect("connect to PG 18.6");
    run_locked_handoff(&pool)
        .await
        .expect("run Flyway->SQLx handoff migrations");
    pool
}

pub struct TestServer {
    pub addr: SocketAddr,
    pub base_url: String,
    pub state: AppState,
    pub pool: PgPool,
    pub config: AppConfig,
    pub cache: Arc<InMemoryCacheStore>,
    pub gateway: Arc<InMemoryRazorpayGateway>,
    pub mock_storage: Option<Arc<MockObjectStorageClient>>,
    pub client: reqwest::Client,
    pub hasher: PasswordHasher,
}

impl TestServer {
    pub async fn spawn_default(pool: PgPool) -> Self {
        Self::spawn_with_options(pool, HashMap::new(), true, None).await
    }

    pub async fn spawn_with_options(
        pool: PgPool,
        env_overrides: HashMap<String, String>,
        with_mock_storage: bool,
        ws_heartbeat_interval: Option<Duration>,
    ) -> Self {
        let mut env_map: HashMap<String, String> = std::env::vars().collect();
        env_map
            .entry("RATE_LIMIT_ENABLED".to_owned())
            .or_insert_with(|| "false".to_owned());
        env_map
            .entry("RAZORPAY_KEY_ID".to_owned())
            .or_insert_with(|| "rzp_test_pizzora_api".to_owned());
        env_map
            .entry("RAZORPAY_KEY_SECRET".to_owned())
            .or_insert_with(|| "rzp_secret_pizzora_api_key".to_owned());
        env_map
            .entry("RAZORPAY_WEBHOOK_SECRET".to_owned())
            .or_insert_with(|| "rzp_webhook_pizzora_api_sec".to_owned());
        if !with_mock_storage {
            env_map.insert("S3_ENABLED".to_owned(), "false".to_owned());
        }
        for (k, v) in env_overrides {
            env_map.insert(k, v);
        }

        let config = AppConfig::from_env_map(&env_map).expect("AppConfig for TestServer");
        let redis_store = Arc::new(RedisStore::new(config.redis.url(), 200));
        let cache = Arc::new(InMemoryCacheStore::new());
        let cache_trait: Arc<dyn EphemeralCacheStore> = cache.clone();
        let hasher = PasswordHasher::new(4096, 1, 1, 32);
        let gateway = Arc::new(InMemoryRazorpayGateway::new(&config.razorpay.key_id));
        let gateway_trait: Arc<dyn RazorpayGateway> = gateway.clone();

        let (mock_storage, storage_override): (
            Option<Arc<MockObjectStorageClient>>,
            Option<Arc<dyn ObjectStorageClient>>,
        ) = if with_mock_storage {
            let mock = Arc::new(MockObjectStorageClient::new());
            let trait_obj: Arc<dyn ObjectStorageClient> = mock.clone();
            (Some(mock), Some(trait_obj))
        } else {
            (None, None)
        };

        let mut state = AppState::build_with_cache(
            config.clone(),
            pool.clone(),
            redis_store,
            Some(cache_trait),
            Some(hasher.clone()),
            Some(gateway_trait),
            storage_override,
        )
        .expect("build TestServer AppState");

        if let Some(hb) = ws_heartbeat_interval {
            state = state.with_ws_heartbeat_interval(hb);
        }

        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind TestServer ephemeral port");
        let addr = listener.local_addr().expect("local_addr");
        let router =
            build_router(state.clone()).into_make_service_with_connect_info::<SocketAddr>();
        tokio::spawn(async move {
            let _ = axum::serve(listener, router).await;
        });

        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .build()
            .expect("reqwest client");

        Self {
            addr,
            base_url: format!("http://{addr}"),
            state,
            pool,
            config,
            cache,
            gateway,
            mock_storage,
            client,
            hasher,
        }
    }

    pub fn url(&self, path: &str) -> String {
        format!("{}{}", self.base_url, path)
    }

    pub async fn register_and_verify_customer(
        &self,
        prefix: &str,
    ) -> (Uuid, String, AuthTokensResponse) {
        let unique = new_uuid_v4();
        let email = format!("{prefix}-{unique}@pizzora.test");
        let password = "CustomerPass!2026";

        let reg_resp = self
            .client
            .post(self.url("/api/v1/auth/register"))
            .json(&RegisterRequest {
                email: email.clone(),
                password: password.to_owned(),
                full_name: format!("Customer {prefix}"),
                phone: "9876543210".to_owned(),
            })
            .send()
            .await
            .expect("register request");
        assert_eq!(reg_resp.status(), reqwest::StatusCode::CREATED);

        let raw_verify_token = self.unseal_latest_email_token(&email).await;
        let verify_resp = self
            .client
            .post(self.url("/api/v1/auth/verify-email"))
            .json(&VerifyEmailRequest {
                token: raw_verify_token,
            })
            .send()
            .await
            .expect("verify email request");
        assert_eq!(verify_resp.status(), reqwest::StatusCode::OK);

        let login_resp = self
            .client
            .post(self.url("/api/v1/auth/login"))
            .json(&LoginRequest {
                email: email.clone(),
                password: password.to_owned(),
                device_name: "TestPhone".to_owned(),
            })
            .send()
            .await
            .expect("login customer request");
        assert_eq!(login_resp.status(), reqwest::StatusCode::OK);
        let tokens: AuthTokensResponse = login_resp.json().await.expect("AuthTokensResponse");
        let uid = Uuid::parse_str(&tokens.user.id).expect("valid customer uuid");
        (uid, email, tokens)
    }

    pub async fn unseal_latest_email_token(&self, expected_email: &str) -> String {
        let rows = sqlx::query(
            r#"
            SELECT id, payload_json
            FROM outbox_events
            WHERE event_type = $1
            ORDER BY created_at DESC
            LIMIT 25
            "#,
        )
        .bind(EVENT_EMAIL_DELIVERY_REQUESTED)
        .fetch_all(&self.pool)
        .await
        .expect("query email outbox");

        for row in rows {
            let outbox_id: Uuid = row.try_get("id").unwrap();
            let payload_val: serde_json::Value = row.try_get("payload_json").unwrap();
            let Ok(payload) = serde_json::from_value::<OutboxEmailPayload>(payload_val) else {
                continue;
            };
            if payload.sealed_envelope.is_none() {
                continue;
            }
            if let Ok(secrets) = unseal_outbox_email_payload(
                &self.config.outbox_encryption,
                outbox_id,
                EVENT_EMAIL_DELIVERY_REQUESTED,
                &payload,
            ) {
                if secrets.to_email.eq_ignore_ascii_case(expected_email) {
                    return secrets.raw_token.to_string();
                }
            }
        }
        panic!("No unsealable email outbox token found for {expected_email}");
    }

    pub async fn bootstrap_and_login_owner(&self, prefix: &str) -> (Uuid, AuthTokensResponse) {
        let unique = new_uuid_v4();
        let email = format!("{prefix}-owner-{unique}@pizzora.test");
        let emp_code = format!("OWN-{}", &unique.simple().to_string()[..6]);
        let password = "OwnerPass!2026";
        let owner_id = BootstrapOwner::bootstrap(
            &self.pool,
            &self.hasher,
            &email,
            password,
            "Owner Test",
            "9800000000",
            Some(&emp_code),
        )
        .await
        .expect("bootstrap owner");

        let login_resp = self
            .client
            .post(self.url("/api/v1/auth/login"))
            .json(&LoginRequest {
                email,
                password: password.to_owned(),
                device_name: "OwnerConsole".to_owned(),
            })
            .send()
            .await
            .expect("owner login");
        assert_eq!(login_resp.status(), reqwest::StatusCode::OK);
        let tokens: AuthTokensResponse = login_resp.json().await.expect("owner tokens");
        (owner_id, tokens)
    }

    pub async fn create_and_login_kitchen_staff(
        &self,
        owner_token: &str,
        prefix: &str,
    ) -> (Uuid, AuthTokensResponse) {
        let unique = new_uuid_v4();
        let email = format!("{prefix}-kds-{unique}@pizzora.test");
        let password = "KitchenPass!2026";
        let create_resp = self
            .client
            .post(self.url("/api/v1/owner/staff"))
            .bearer_auth(owner_token)
            .json(&CreateStaffRequest {
                email: email.clone(),
                password: password.to_owned(),
                full_name: "Chef Priya".to_owned(),
                phone: "9811111111".to_owned(),
                role: UserRole::KitchenStaff,
                employee_code: format!("KDS-{}", &unique.simple().to_string()[..6]),
            })
            .send()
            .await
            .expect("create staff");
        assert_eq!(create_resp.status(), reqwest::StatusCode::CREATED);

        let login_resp = self
            .client
            .post(self.url("/api/v1/auth/login"))
            .json(&LoginRequest {
                email,
                password: password.to_owned(),
                device_name: "KDS-Tablet".to_owned(),
            })
            .send()
            .await
            .expect("kds login");
        assert_eq!(login_resp.status(), reqwest::StatusCode::OK);
        let tokens: AuthTokensResponse = login_resp.json().await.expect("kds tokens");
        let uid = Uuid::parse_str(&tokens.user.id).expect("valid kds uuid");
        (uid, tokens)
    }

    pub async fn dispatch_pending_payment_outbox(&self) {
        let outbox = OutboxRepository::new(self.pool.clone());
        let reconciler = RazorpayReconciliationService::new(
            self.pool.clone(),
            self.gateway.clone(),
            self.state.payment_service.clone(),
            20,
        );
        let claimed = outbox.claim_payment_events(20, 30).await.expect("claim");
        for ev in claimed {
            match ev.event_type.as_str() {
                "PAYMENT_GATEWAY_ORDER_REQUESTED" => {
                    reconciler
                        .dispatch_gateway_order_outbox_event(&outbox, &ev, 5)
                        .await
                        .expect("dispatch gateway order");
                }
                "REFUND_REQUESTED" => {
                    reconciler
                        .dispatch_refund_outbox_event(&outbox, &ev, 5)
                        .await
                        .expect("dispatch refund");
                }
                _ => {}
            }
        }
    }
}

pub struct SeededStorefront {
    pub category: CategoryDto,
    pub product: ProductDto,
    pub variant_id: String,
    pub crust: CrustOptionDto,
    pub topping: ToppingDto,
    pub dough_ingredient: InventoryItemDto,
    pub cheese_ingredient: InventoryItemDto,
    pub zone: DeliveryZoneDto,
    pub delivery_slot: ScheduledSlotDto,
    pub pickup_slot: ScheduledSlotDto,
}

pub async fn seed_full_storefront_via_owner_api(
    srv: &TestServer,
    owner_token: &str,
    tag: &str,
    pincode: &str,
) -> SeededStorefront {
    let cat_resp = srv
        .client
        .post(srv.url("/api/v1/owner/categories"))
        .bearer_auth(owner_token)
        .json(&CreateCategoryRequest {
            name: format!("Artisan-{tag}"),
            sort_order: 1,
            is_active: true,
        })
        .send()
        .await
        .expect("create category");
    assert_eq!(cat_resp.status(), reqwest::StatusCode::CREATED);
    let category: CategoryDto = cat_resp.json().await.unwrap();

    let prod_resp = srv
        .client
        .post(srv.url("/api/v1/owner/products"))
        .bearer_auth(owner_token)
        .json(&CreateProductRequest {
            category_id: category.id.clone(),
            name: format!("Margherita-{tag}"),
            description: "Fresh San Marzano & basil".to_owned(),
            is_veg: true,
            is_active: true,
            is_taxable: true,
            variants: vec![CreateVariantRequest {
                size_code: SizeCode::Medium,
                price_paise: 30_000,
                is_active: true,
            }],
        })
        .send()
        .await
        .expect("create product");
    assert_eq!(prod_resp.status(), reqwest::StatusCode::CREATED);
    let product: ProductDto = prod_resp.json().await.unwrap();
    let variant_id = product.variants[0].id.clone();

    let crust_resp = srv
        .client
        .post(srv.url("/api/v1/owner/crusts"))
        .bearer_auth(owner_token)
        .json(&CreateCrustRequest {
            name: format!("Sourdough-{tag}"),
            extra_price_paise: 5_000,
            is_active: true,
        })
        .send()
        .await
        .expect("create crust");
    assert_eq!(crust_resp.status(), reqwest::StatusCode::CREATED);
    let crust: CrustOptionDto = crust_resp.json().await.unwrap();

    let top_resp = srv
        .client
        .post(srv.url("/api/v1/owner/toppings"))
        .bearer_auth(owner_token)
        .json(&CreateToppingRequest {
            name: format!("Burrata-{tag}"),
            is_veg: true,
            extra_price_paise: 5_000,
            is_active: true,
        })
        .send()
        .await
        .expect("create topping");
    assert_eq!(top_resp.status(), reqwest::StatusCode::CREATED);
    let topping: ToppingDto = top_resp.json().await.unwrap();

    let dough_resp = srv
        .client
        .post(srv.url("/api/v1/owner/inventory"))
        .bearer_auth(owner_token)
        .json(&serde_json::json!({
            "name": format!("Dough-{tag}"),
            "unit": "GRAMS",
            "low_stock_threshold": 500,
            "initial_stock_qty": 2000,
            "is_active": true
        }))
        .send()
        .await
        .expect("create dough");
    assert_eq!(dough_resp.status(), reqwest::StatusCode::CREATED);
    let dough_ingredient: InventoryItemDto = dough_resp.json().await.unwrap();

    let cheese_resp = srv
        .client
        .post(srv.url("/api/v1/owner/inventory"))
        .bearer_auth(owner_token)
        .json(&serde_json::json!({
            "name": format!("Cheese-{tag}"),
            "unit": "GRAMS",
            "low_stock_threshold": 100,
            "initial_stock_qty": 500,
            "is_active": true
        }))
        .send()
        .await
        .expect("create cheese");
    assert_eq!(cheese_resp.status(), reqwest::StatusCode::CREATED);
    let cheese_ingredient: InventoryItemDto = cheese_resp.json().await.unwrap();

    // Variant uses 250g dough; Topping uses 50g cheese
    let put_var_recipe = srv
        .client
        .put(srv.url("/api/v1/owner/recipes"))
        .bearer_auth(owner_token)
        .json(&PutRecipeRequest {
            target_type: RecipeTargetType::Variant,
            target_id: variant_id.clone(),
            ingredients: vec![RecipeIngredientLineRequest {
                ingredient_id: dough_ingredient.ingredient_id.clone(),
                quantity: 250,
            }],
        })
        .send()
        .await
        .expect("put variant recipe");
    assert_eq!(put_var_recipe.status(), reqwest::StatusCode::OK);

    let put_top_recipe = srv
        .client
        .put(srv.url("/api/v1/owner/recipes"))
        .bearer_auth(owner_token)
        .json(&PutRecipeRequest {
            target_type: RecipeTargetType::Topping,
            target_id: topping.id.clone(),
            ingredients: vec![RecipeIngredientLineRequest {
                ingredient_id: cheese_ingredient.ingredient_id.clone(),
                quantity: 50,
            }],
        })
        .send()
        .await
        .expect("put topping recipe");
    assert_eq!(put_top_recipe.status(), reqwest::StatusCode::OK);

    let zone_resp = srv
        .client
        .post(srv.url("/api/v1/owner/delivery-zones"))
        .bearer_auth(owner_token)
        .json(&CreateDeliveryZoneRequest {
            pincode: pincode.to_owned(),
            area_name: format!("Indiranagar-{tag}"),
            min_order_paise: 20_000,
            delivery_fee_paise: 4_000,
            estimated_mins: 30,
            is_active: true,
        })
        .send()
        .await
        .expect("create zone");
    assert_eq!(zone_resp.status(), reqwest::StatusCode::CREATED);
    let zone: DeliveryZoneDto = zone_resp.json().await.unwrap();

    // Create future IST date slots guaranteed open for booking
    let future_date = (chrono::Utc::now() + chrono::Duration::days(3))
        .format("%Y-%m-%d")
        .to_string();
    let del_slot_resp = srv
        .client
        .post(srv.url("/api/v1/owner/slots"))
        .bearer_auth(owner_token)
        .json(&CreateScheduledSlotRequest {
            slot_date: future_date.clone(),
            fulfillment_type: FulfillmentType::Delivery,
            start_time_ist: "19:00".to_owned(),
            end_time_ist: "19:30".to_owned(),
            cutoff_at: None,
            cutoff_minutes_before_start: Some(30),
            max_capacity: 2,
            is_active: true,
        })
        .send()
        .await
        .expect("create delivery slot");
    assert_eq!(del_slot_resp.status(), reqwest::StatusCode::CREATED);
    let delivery_slot: ScheduledSlotDto = del_slot_resp.json().await.unwrap();

    let pick_slot_resp = srv
        .client
        .post(srv.url("/api/v1/owner/slots"))
        .bearer_auth(owner_token)
        .json(&CreateScheduledSlotRequest {
            slot_date: future_date,
            fulfillment_type: FulfillmentType::Pickup,
            start_time_ist: "20:00".to_owned(),
            end_time_ist: "20:30".to_owned(),
            cutoff_at: None,
            cutoff_minutes_before_start: Some(30),
            max_capacity: 5,
            is_active: true,
        })
        .send()
        .await
        .expect("create pickup slot");
    assert_eq!(pick_slot_resp.status(), reqwest::StatusCode::CREATED);
    let pickup_slot: ScheduledSlotDto = pick_slot_resp.json().await.unwrap();

    SeededStorefront {
        category,
        product,
        variant_id,
        crust,
        topping,
        dough_ingredient,
        cheese_ingredient,
        zone,
        delivery_slot,
        pickup_slot,
    }
}

// ---------- Minimal pure-Tokio RFC-6455 WebSocket Test Client ----------

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WsFrame {
    Text(String),
    Binary(Vec<u8>),
    Ping(Vec<u8>),
    Pong(Vec<u8>),
    Close(Option<u16>),
}

#[derive(Debug)]
pub struct TestWsClient {
    stream: TcpStream,
    buf: Vec<u8>,
}

impl TestWsClient {
    pub async fn connect(
        addr: SocketAddr,
        path_and_query: &str,
        bearer_token: Option<&str>,
    ) -> Result<Self, String> {
        let mut stream = TcpStream::connect(addr)
            .await
            .map_err(|e| format!("tcp connect failed: {e}"))?;

        let mut req = format!(
            "GET {path_and_query} HTTP/1.1\r\n\
             Host: {addr}\r\n\
             Upgrade: websocket\r\n\
             Connection: Upgrade\r\n\
             Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\
             Sec-WebSocket-Version: 13\r\n"
        );
        if let Some(tok) = bearer_token {
            req.push_str(&format!("Authorization: Bearer {tok}\r\n"));
        }
        req.push_str("\r\n");

        stream
            .write_all(req.as_bytes())
            .await
            .map_err(|e| format!("write handshake failed: {e}"))?;

        let mut buf = Vec::with_capacity(2048);
        let mut tmp = [0u8; 1024];
        let header_end = loop {
            if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                break pos + 4;
            }
            let n = stream
                .read(&mut tmp)
                .await
                .map_err(|e| format!("read handshake failed: {e}"))?;
            if n == 0 {
                return Err(format!(
                    "EOF before HTTP handshake end: {}",
                    String::from_utf8_lossy(&buf)
                ));
            }
            buf.extend_from_slice(&tmp[..n]);
        };

        let header_str = String::from_utf8_lossy(&buf[..header_end]).into_owned();
        if !header_str.starts_with("HTTP/1.1 101 ") {
            return Err(header_str);
        }

        let remaining = buf.split_off(header_end);
        Ok(Self {
            stream,
            buf: remaining,
        })
    }

    async fn read_exact_buffered(&mut self, len: usize) -> Result<Vec<u8>, String> {
        while self.buf.len() < len {
            let mut tmp = [0u8; 2048];
            let n = self
                .stream
                .read(&mut tmp)
                .await
                .map_err(|e| format!("ws read error: {e}"))?;
            if n == 0 {
                return Err("ws unexpected EOF".to_owned());
            }
            self.buf.extend_from_slice(&tmp[..n]);
        }
        let rest = self.buf.split_off(len);
        let out = std::mem::replace(&mut self.buf, rest);
        Ok(out)
    }

    pub async fn read_frame(&mut self) -> Result<WsFrame, String> {
        let hdr = self.read_exact_buffered(2).await?;
        let opcode = hdr[0] & 0x0F;
        let masked = (hdr[1] & 0x80) != 0;
        let len7 = (hdr[1] & 0x7F) as u64;

        let payload_len = match len7 {
            0..=125 => len7 as usize,
            126 => {
                let ext = self.read_exact_buffered(2).await?;
                u16::from_be_bytes([ext[0], ext[1]]) as usize
            }
            _ => {
                let ext = self.read_exact_buffered(8).await?;
                u64::from_be_bytes([
                    ext[0], ext[1], ext[2], ext[3], ext[4], ext[5], ext[6], ext[7],
                ]) as usize
            }
        };

        let mask = if masked {
            Some(self.read_exact_buffered(4).await?)
        } else {
            None
        };

        let mut payload = self.read_exact_buffered(payload_len).await?;
        if let Some(m) = mask {
            for (idx, b) in payload.iter_mut().enumerate() {
                *b ^= m[idx % 4];
            }
        }

        match opcode {
            0x01 => Ok(WsFrame::Text(
                String::from_utf8_lossy(&payload).into_owned(),
            )),
            0x02 => Ok(WsFrame::Binary(payload)),
            0x08 => {
                let code = if payload.len() >= 2 {
                    Some(u16::from_be_bytes([payload[0], payload[1]]))
                } else {
                    None
                };
                Ok(WsFrame::Close(code))
            }
            0x09 => Ok(WsFrame::Ping(payload)),
            0x0A => Ok(WsFrame::Pong(payload)),
            other => Err(format!("unexpected ws opcode 0x{other:02x}")),
        }
    }

    pub async fn next_text_frame(&mut self) -> Result<String, String> {
        loop {
            match self.read_frame().await? {
                WsFrame::Text(s) => return Ok(s),
                WsFrame::Ping(p) => {
                    let _ = self.send_masked_frame(0x0A, &p).await;
                }
                WsFrame::Pong(_) => {}
                WsFrame::Close(c) => return Err(format!("closed with {c:?}")),
                WsFrame::Binary(_) => {}
            }
        }
    }

    pub async fn send_text(&mut self, text: &str) -> Result<(), String> {
        self.send_masked_frame(0x01, text.as_bytes()).await
    }

    pub async fn send_ping(&mut self, data: &[u8]) -> Result<(), String> {
        self.send_masked_frame(0x09, data).await
    }

    pub async fn send_close(&mut self) -> Result<(), String> {
        self.send_masked_frame(0x08, &1000u16.to_be_bytes()).await
    }

    async fn send_masked_frame(&mut self, opcode: u8, payload: &[u8]) -> Result<(), String> {
        let mut frame = Vec::with_capacity(payload.len() + 14);
        frame.push(0x80 | (opcode & 0x0F));
        let len = payload.len();
        if len <= 125 {
            frame.push(0x80 | (len as u8));
        } else if len <= u16::MAX as usize {
            frame.push(0x80 | 126);
            frame.extend_from_slice(&(len as u16).to_be_bytes());
        } else {
            frame.push(0x80 | 127);
            frame.extend_from_slice(&(len as u64).to_be_bytes());
        }
        let mask = [0x12u8, 0x34, 0x56, 0x78];
        frame.extend_from_slice(&mask);
        for (i, b) in payload.iter().enumerate() {
            frame.push(*b ^ mask[i % 4]);
        }
        self.stream
            .write_all(&frame)
            .await
            .map_err(|e| format!("ws write failed: {e}"))
    }
}

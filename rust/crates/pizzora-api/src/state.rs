use std::sync::Arc;

use pizzora_auth::{
    AuthService, AuthorizationService, PasswordHasher, WsTicketService,
};
use pizzora_config::AppConfig;
use pizzora_db::{
    AuditService, CouponService, EphemeralCacheStore, InventoryService, NotificationService,
    OrderService, RedisStore, UsersService, WsTicketRepository,
};
use pizzora_payments::{
    create_default_razorpay_gateway, PaymentService, RazorpayGateway, WebhookService,
};
use sqlx::PgPool;

use crate::error::ApiError;
use crate::metrics::ApiMetrics;
use crate::rate_limit::ResilientRateLimiter;
use crate::services::cart::CartService;
use crate::services::catalog::CatalogService;
use crate::services::categories::CategoriesService;
use crate::services::checkout::CheckoutService;
use crate::services::config::RestaurantConfigService;
use crate::services::images::ProductImagesService;
use crate::services::kitchen::KitchenService;
use crate::services::owner::OwnerService;
use crate::services::pricing::PricingService;
use crate::services::realtime::{RealtimeEventService, RealtimeHub};
use crate::services::recipes::RecipeService;
use crate::services::reporting::ReportingService;
use crate::services::slots::ScheduledSlotService;
use crate::services::zones::DeliveryZoneService;
use crate::storage::{create_object_storage_client, ObjectStorageClient};

#[derive(Clone)]
pub struct AppState {
    pub config: Arc<AppConfig>,
    pub pool: PgPool,
    pub redis_store: Arc<RedisStore>,
    pub cache_store: Arc<dyn EphemeralCacheStore>,
    pub storage: Arc<dyn ObjectStorageClient>,
    pub metrics: Arc<ApiMetrics>,
    pub rate_limiter: ResilientRateLimiter,

    // Domain crate services
    pub auth_service: AuthService,
    pub authz_service: AuthorizationService,
    pub ws_ticket_service: WsTicketService,
    pub users_service: UsersService,
    pub notification_service: NotificationService,
    pub audit_service: AuditService,
    pub coupon_service: CouponService,
    pub inventory_service: InventoryService,
    pub order_service: OrderService,
    pub payment_service: PaymentService,
    pub webhook_service: WebhookService,

    // API-layer services
    pub categories_service: CategoriesService,
    pub catalog_service: CatalogService,
    pub images_service: ProductImagesService,
    pub cart_service: CartService,
    pub pricing_service: PricingService,
    pub recipe_service: RecipeService,
    pub checkout_service: CheckoutService,
    pub config_service: RestaurantConfigService,
    pub zones_service: DeliveryZoneService,
    pub slots_service: ScheduledSlotService,
    pub kitchen_service: KitchenService,
    pub owner_service: OwnerService,
    pub reporting_service: ReportingService,
    pub realtime_hub: Arc<RealtimeHub>,
    pub realtime_event_service: RealtimeEventService,
}

impl AppState {
    pub fn build(
        config: AppConfig,
        pool: PgPool,
        redis_store: Arc<RedisStore>,
        password_hasher: Option<PasswordHasher>,
        gateway_override: Option<Arc<dyn RazorpayGateway>>,
        storage_override: Option<Arc<dyn ObjectStorageClient>>,
    ) -> Result<Self, ApiError> {
        let config = Arc::new(config);
        let cache_store: Arc<dyn EphemeralCacheStore> = redis_store.clone();
        let storage = match storage_override {
            Some(s) => s,
            None => create_object_storage_client(&config.storage)?,
        };
        let gateway = match gateway_override {
            Some(g) => g,
            None => create_default_razorpay_gateway(&config)?,
        };

        let metrics = Arc::new(ApiMetrics::new());
        let rate_limiter = ResilientRateLimiter::new(cache_store.clone(), config.security.clone());

        let hasher = password_hasher.unwrap_or_default();
        let auth_service = AuthService::new(
            pool.clone(),
            config.auth.clone(),
            config.outbox_encryption.clone(),
            hasher,
        );
        let authz_service = AuthorizationService::new(pool.clone(), cache_store.clone());
        let ws_ticket_repo = WsTicketRepository::new(
            pool.clone(),
            cache_store.clone(),
            config.auth.ws_ticket_ttl_seconds,
        );
        let ws_ticket_service = WsTicketService::new(ws_ticket_repo);

        let users_service = UsersService::new(pool.clone());
        let notification_service = NotificationService::new(pool.clone());
        let audit_service = AuditService::new(pool.clone());
        let coupon_service = CouponService::new(pool.clone());
        let inventory_service = InventoryService::new(pool.clone());
        let order_service = OrderService::new(pool.clone());

        let pub_key = gateway.public_key_id().to_owned();
        let payment_service = if !pub_key.is_empty() {
            PaymentService::with_public_key_id(pool.clone(), config.razorpay.clone(), pub_key)
        } else {
            PaymentService::new(pool.clone(), config.razorpay.clone())
        };
        let webhook_service =
            WebhookService::new(payment_service.clone(), config.razorpay.clone());

        let categories_service = CategoriesService::new(pool.clone(), cache_store.clone());
        let catalog_service =
            CatalogService::new(pool.clone(), cache_store.clone(), storage.clone());
        let images_service =
            ProductImagesService::new(pool.clone(), storage.clone(), cache_store.clone());
        let cart_service = CartService::new(pool.clone(), storage.clone());
        let pricing_service = PricingService::new(pool.clone(), storage.clone());
        let recipe_service = RecipeService::new(pool.clone());
        let checkout_service = CheckoutService::new(pool.clone(), storage.clone());
        let config_service = RestaurantConfigService::new(pool.clone(), cache_store.clone());
        let zones_service = DeliveryZoneService::new(pool.clone());
        let slots_service = ScheduledSlotService::new(pool.clone());
        let kitchen_service = KitchenService::new(pool.clone());
        let owner_service = OwnerService::new(pool.clone());
        let reporting_service = ReportingService::new(pool.clone());
        let realtime_hub = Arc::new(RealtimeHub::new());
        let realtime_event_service = RealtimeEventService::new(pool.clone());

        Ok(Self {
            config,
            pool,
            redis_store,
            cache_store,
            storage,
            metrics,
            rate_limiter,
            auth_service,
            authz_service,
            ws_ticket_service,
            users_service,
            notification_service,
            audit_service,
            coupon_service,
            inventory_service,
            order_service,
            payment_service,
            webhook_service,
            categories_service,
            catalog_service,
            images_service,
            cart_service,
            pricing_service,
            recipe_service,
            checkout_service,
            config_service,
            zones_service,
            slots_service,
            kitchen_service,
            owner_service,
            reporting_service,
            realtime_hub,
            realtime_event_service,
        })
    }
}

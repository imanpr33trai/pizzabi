use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::Json;
use pizzora_core::FulfillmentType;
use pizzora_db::{
    AdjustInventoryRequest, AuditLogsResponse, CreateIngredientRequest, InventoryItemDto,
    InventoryLedgerEntryDto, MarkAllNotificationsReadResponse, MarkNotificationReadResponse,
    NotificationInboxResponse, NotificationPreferencesDto, UpdateIngredientRequest,
    UpdateNotificationPreferencesRequest,
};
use serde::Deserialize;

use crate::dto::{
    AvailableSlotsResponse, CreateDeliveryZoneRequest, CreateScheduledSlotRequest,
    DeliveryZoneCheckResponse, DeliveryZoneDto, OperationsReportResponse, OwnerDashboardResponse,
    PublicConfigResponse, PutRecipeRequest, RecipeBindingDto, RecipesOverviewResponse,
    RestaurantSettingsDto, SalesReportResponse, ScheduledSlotDto, UpdateDeliveryZoneRequest,
    UpdateRestaurantSettingsRequest, UpdateScheduledSlotRequest,
};
use crate::error::ApiError;
use crate::extractors::{
    build_audit_context, parse_uuid_param, require_permission, AuthPrincipal, RequestMeta,
    ValidatedJson,
};
use crate::services::slots::parse_fulfillment_type;
use crate::state::AppState;

// ---------- Config & Dashboard ----------

pub async fn get_public_config(
    State(state): State<AppState>,
) -> Result<Json<PublicConfigResponse>, ApiError> {
    let cfg = state.config_service.get_public_config().await?;
    Ok(Json(cfg))
}

pub async fn get_owner_settings(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<RestaurantSettingsDto>, ApiError> {
    require_permission(&state, &principal, "owner:settings_manage").await?;
    let cfg = state.config_service.get_owner_settings().await?;
    Ok(Json(cfg))
}

pub async fn update_owner_settings(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<UpdateRestaurantSettingsRequest>,
) -> Result<Json<RestaurantSettingsDto>, ApiError> {
    require_permission(&state, &principal, "owner:settings_manage").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .config_service
        .update_owner_settings(&body, &audit)
        .await?;
    Ok(Json(updated))
}

pub async fn get_owner_dashboard(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<OwnerDashboardResponse>, ApiError> {
    require_permission(&state, &principal, "owner:dashboard").await?;
    let dashboard = state.owner_service.get_dashboard().await?;
    Ok(Json(dashboard))
}

// ---------- Delivery Zones ----------

#[derive(Debug, Deserialize, Default)]
pub struct PincodeCheckQuery {
    pub pincode: Option<String>,
}

pub async fn check_delivery_zone(
    State(state): State<AppState>,
    Query(query): Query<PincodeCheckQuery>,
) -> Result<Json<DeliveryZoneCheckResponse>, ApiError> {
    let pincode = query.pincode.as_deref().unwrap_or("");
    let resp = state.zones_service.check_pincode(pincode).await?;
    Ok(Json(resp))
}

pub async fn list_owner_zones(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<Vec<DeliveryZoneDto>>, ApiError> {
    require_permission(&state, &principal, "owner:zones_slots_manage").await?;
    let list = state.zones_service.list_all_zones().await?;
    Ok(Json(list))
}

pub async fn create_zone(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<CreateDeliveryZoneRequest>,
) -> Result<(StatusCode, Json<DeliveryZoneDto>), ApiError> {
    require_permission(&state, &principal, "owner:zones_slots_manage").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let created = state.zones_service.create_zone(&body, &audit).await?;
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn update_zone(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<UpdateDeliveryZoneRequest>,
) -> Result<Json<DeliveryZoneDto>, ApiError> {
    require_permission(&state, &principal, "owner:zones_slots_manage").await?;
    let zone_id = parse_uuid_param(&id, "zone_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .zones_service
        .update_zone(zone_id, &body, &audit)
        .await?;
    Ok(Json(updated))
}

pub async fn delete_zone(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    require_permission(&state, &principal, "owner:zones_slots_manage").await?;
    let zone_id = parse_uuid_param(&id, "zone_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    state.zones_service.delete_zone(zone_id, &audit).await?;
    Ok(StatusCode::NO_CONTENT)
}

// ---------- Scheduled Slots ----------

#[derive(Debug, Deserialize, Default)]
pub struct SlotsQuery {
    pub fulfillment_type: Option<String>,
    pub date: Option<String>,
}

pub async fn list_public_slots(
    State(state): State<AppState>,
    Query(query): Query<SlotsQuery>,
) -> Result<Json<AvailableSlotsResponse>, ApiError> {
    let ft = match query
        .fulfillment_type
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        Some(raw) => parse_fulfillment_type(raw)?,
        None => FulfillmentType::Delivery,
    };
    let resp = state
        .slots_service
        .list_available_slots(ft, query.date.as_deref())
        .await?;
    Ok(Json(resp))
}

pub async fn list_owner_slots(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Query(query): Query<SlotsQuery>,
) -> Result<Json<Vec<ScheduledSlotDto>>, ApiError> {
    require_permission(&state, &principal, "owner:zones_slots_manage").await?;
    let ft = match query
        .fulfillment_type
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        Some(raw) => Some(parse_fulfillment_type(raw)?),
        None => None,
    };
    let resp = state
        .slots_service
        .list_owner_slots(ft, query.date.as_deref())
        .await?;
    Ok(Json(resp))
}

pub async fn create_slot(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<CreateScheduledSlotRequest>,
) -> Result<(StatusCode, Json<ScheduledSlotDto>), ApiError> {
    require_permission(&state, &principal, "owner:zones_slots_manage").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let created = state.slots_service.create_slot(&body, &audit).await?;
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn update_slot(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<UpdateScheduledSlotRequest>,
) -> Result<Json<ScheduledSlotDto>, ApiError> {
    require_permission(&state, &principal, "owner:zones_slots_manage").await?;
    let slot_id = parse_uuid_param(&id, "slot_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .slots_service
        .update_slot(slot_id, &body, &audit)
        .await?;
    Ok(Json(updated))
}

pub async fn delete_slot(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    require_permission(&state, &principal, "owner:zones_slots_manage").await?;
    let slot_id = parse_uuid_param(&id, "slot_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    state.slots_service.delete_slot(slot_id, &audit).await?;
    Ok(StatusCode::NO_CONTENT)
}

// ---------- Inventory & Recipes ----------

#[derive(Debug, Deserialize, Default)]
pub struct InventoryLedgerQuery {
    pub ingredient_id: Option<String>,
    pub limit: Option<i64>,
}

pub async fn list_inventory(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<Vec<InventoryItemDto>>, ApiError> {
    require_permission(&state, &principal, "owner:inventory_manage").await?;
    let items = state.inventory_service.list_inventory().await?;
    Ok(Json(items))
}

pub async fn create_ingredient(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<CreateIngredientRequest>,
) -> Result<(StatusCode, Json<InventoryItemDto>), ApiError> {
    require_permission(&state, &principal, "owner:inventory_manage").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let created = state
        .inventory_service
        .create_ingredient(&body, &audit)
        .await?;
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn update_ingredient(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<UpdateIngredientRequest>,
) -> Result<Json<InventoryItemDto>, ApiError> {
    require_permission(&state, &principal, "owner:inventory_manage").await?;
    let ingredient_id = parse_uuid_param(&id, "ingredient_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .inventory_service
        .update_ingredient(ingredient_id, &body, &audit)
        .await?;
    Ok(Json(updated))
}

pub async fn adjust_inventory(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<AdjustInventoryRequest>,
) -> Result<Json<InventoryItemDto>, ApiError> {
    require_permission(&state, &principal, "owner:inventory_manage").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .inventory_service
        .adjust_inventory(&body, &audit)
        .await?;
    Ok(Json(updated))
}

pub async fn list_inventory_ledger(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Query(query): Query<InventoryLedgerQuery>,
) -> Result<Json<Vec<InventoryLedgerEntryDto>>, ApiError> {
    require_permission(&state, &principal, "owner:inventory_manage").await?;
    let ingredient_id = match query
        .ingredient_id
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        Some(raw) => Some(parse_uuid_param(raw, "ingredient_id")?),
        None => None,
    };
    let limit = query.limit.unwrap_or(50);
    let entries = state
        .inventory_service
        .list_ledger(ingredient_id, limit)
        .await?;
    Ok(Json(entries))
}

pub async fn list_recipes(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<RecipesOverviewResponse>, ApiError> {
    require_permission(&state, &principal, "owner:recipes_manage").await?;
    let resp = state.recipe_service.list_recipes().await?;
    Ok(Json(resp))
}

pub async fn put_recipe(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<PutRecipeRequest>,
) -> Result<Json<RecipeBindingDto>, ApiError> {
    require_permission(&state, &principal, "owner:recipes_manage").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state.recipe_service.put_recipe(&body, &audit).await?;
    Ok(Json(updated))
}

// ---------- Notifications ----------

#[derive(Debug, Deserialize, Default)]
pub struct NotificationsQuery {
    pub unread: Option<bool>,
    pub limit: Option<i64>,
}

pub async fn list_notifications(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Query(query): Query<NotificationsQuery>,
) -> Result<Json<NotificationInboxResponse>, ApiError> {
    let unread_only = query.unread.unwrap_or(false);
    let limit = query.limit.unwrap_or(50);
    let resp = state
        .notification_service
        .list_notifications(principal.user_id, unread_only, limit)
        .await?;
    Ok(Json(resp))
}

pub async fn mark_notification_read(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Path(id): Path<String>,
) -> Result<Json<MarkNotificationReadResponse>, ApiError> {
    let notification_id = parse_uuid_param(&id, "notification_id")?;
    let resp = state
        .notification_service
        .mark_as_read(principal.user_id, notification_id)
        .await?;
    Ok(Json(resp))
}

pub async fn mark_all_notifications_read(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<MarkAllNotificationsReadResponse>, ApiError> {
    let resp = state
        .notification_service
        .mark_all_as_read(principal.user_id)
        .await?;
    Ok(Json(resp))
}

pub async fn get_notification_preferences(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<NotificationPreferencesDto>, ApiError> {
    let prefs = state
        .notification_service
        .get_preferences(principal.user_id)
        .await?;
    Ok(Json(prefs))
}

pub async fn update_notification_preferences(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    ValidatedJson(body): ValidatedJson<UpdateNotificationPreferencesRequest>,
) -> Result<Json<NotificationPreferencesDto>, ApiError> {
    let prefs = state
        .notification_service
        .update_preferences(principal.user_id, &body)
        .await?;
    Ok(Json(prefs))
}

// ---------- Reports & Audit Logs ----------

#[derive(Debug, Deserialize, Default)]
pub struct ReportRangeQuery {
    pub start_date: Option<String>,
    pub end_date: Option<String>,
}

#[derive(Debug, Deserialize, Default)]
pub struct AuditLogsQuery {
    pub entity_type: Option<String>,
    pub action: Option<String>,
    pub entity_id: Option<String>,
    pub cursor: Option<String>,
    pub limit: Option<i64>,
}

pub async fn get_sales_report(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Query(query): Query<ReportRangeQuery>,
) -> Result<Json<SalesReportResponse>, ApiError> {
    require_permission(&state, &principal, "owner:reports_read").await?;
    let resp = state
        .reporting_service
        .get_sales_report(query.start_date.as_deref(), query.end_date.as_deref())
        .await?;
    Ok(Json(resp))
}

pub async fn get_operations_report(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Query(query): Query<ReportRangeQuery>,
) -> Result<Json<OperationsReportResponse>, ApiError> {
    require_permission(&state, &principal, "owner:reports_read").await?;
    let resp = state
        .reporting_service
        .get_operations_report(query.start_date.as_deref(), query.end_date.as_deref())
        .await?;
    Ok(Json(resp))
}

pub async fn list_audit_logs(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Query(query): Query<AuditLogsQuery>,
) -> Result<Json<AuditLogsResponse>, ApiError> {
    require_permission(&state, &principal, "owner:audit_read").await?;
    let resp = state
        .audit_service
        .list_audit_logs(
            query.entity_type.as_deref(),
            query.action.as_deref(),
            query.entity_id.as_deref(),
            query.cursor.as_deref(),
            query.limit.unwrap_or(50),
        )
        .await?;
    Ok(Json(resp))
}

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::Json;
use pizzora_auth::CreateStaffRequest;
use pizzora_db::{
    AddressDto, StaffMemberDto, UpdateProfileRequest, UpdateStaffRequest, UpsertAddressRequest,
    UserProfileDto,
};

use crate::error::ApiError;
use crate::extractors::{
    build_audit_context, parse_uuid_param, require_permission, AuthPrincipal, RequestMeta,
    ValidatedJson,
};
use crate::state::AppState;

pub async fn get_me(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<UserProfileDto>, ApiError> {
    let profile = state.users_service.get_profile(principal.user_id).await?;
    Ok(Json(profile))
}

pub async fn update_me(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    ValidatedJson(body): ValidatedJson<UpdateProfileRequest>,
) -> Result<Json<UserProfileDto>, ApiError> {
    let profile = state
        .users_service
        .update_profile(principal.user_id, &body)
        .await?;
    Ok(Json(profile))
}

pub async fn list_my_addresses(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<Vec<AddressDto>>, ApiError> {
    let list = state
        .users_service
        .list_addresses(principal.user_id)
        .await?;
    Ok(Json(list))
}

pub async fn create_my_address(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    ValidatedJson(body): ValidatedJson<UpsertAddressRequest>,
) -> Result<(StatusCode, Json<AddressDto>), ApiError> {
    let created = state
        .users_service
        .create_address(principal.user_id, &body)
        .await?;
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn update_my_address(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<UpsertAddressRequest>,
) -> Result<Json<AddressDto>, ApiError> {
    let address_id = parse_uuid_param(&id, "address_id")?;
    let updated = state
        .users_service
        .update_address(principal.user_id, address_id, &body)
        .await?;
    Ok(Json(updated))
}

pub async fn delete_my_address(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    let address_id = parse_uuid_param(&id, "address_id")?;
    state
        .users_service
        .delete_address(principal.user_id, address_id)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn list_staff(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<Vec<StaffMemberDto>>, ApiError> {
    require_permission(&state, &principal, "owner:staff_manage").await?;
    let staff = state.users_service.list_staff().await?;
    Ok(Json(staff))
}

pub async fn create_staff(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<CreateStaffRequest>,
) -> Result<(StatusCode, Json<StaffMemberDto>), ApiError> {
    require_permission(&state, &principal, "owner:staff_manage").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let created = state
        .auth_service
        .create_staff(principal.user_id, &body, &audit)
        .await?;
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn update_staff(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(user_id_raw): Path<String>,
    ValidatedJson(body): ValidatedJson<UpdateStaffRequest>,
) -> Result<Json<StaffMemberDto>, ApiError> {
    require_permission(&state, &principal, "owner:staff_manage").await?;
    let target_user_id = parse_uuid_param(&user_id_raw, "user_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .users_service
        .update_staff(target_user_id, &body, &audit)
        .await?;
    Ok(Json(updated))
}

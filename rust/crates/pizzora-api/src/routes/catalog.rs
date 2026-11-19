use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::Json;

use crate::dto::{
    CategoryDto, CheeseOptionDto, ConfirmProductImageRequest, CreateCategoryRequest,
    CreateCheeseRequest, CreateCrustRequest, CreateProductRequest, CreateSauceRequest,
    CreateToppingRequest, CreateVariantRequest, CrustOptionDto, PizzaSizeRuleDto,
    PresignProductImageRequest, PresignProductImageResponse, ProductDto, ProductImageDto,
    ProductVariantDto, PublicMenuResponse, SauceOptionDto, ToppingDto, UpdateCategoryRequest,
    UpdateCheeseRequest, UpdateCrustRequest, UpdatePizzaSizeRuleRequest, UpdateProductRequest,
    UpdateSauceRequest, UpdateToppingRequest, UpdateVariantRequest,
};
use crate::error::ApiError;
use crate::extractors::{
    build_audit_context, parse_uuid_param, require_permission, AuthPrincipal, RequestMeta,
    ValidatedJson,
};
use crate::services::catalog::parse_size_code;
use crate::state::AppState;

// ---------- Public Catalog ----------

pub async fn list_public_categories(
    State(state): State<AppState>,
) -> Result<Json<Vec<CategoryDto>>, ApiError> {
    let categories = state.categories_service.list_active().await?;
    Ok(Json(categories))
}

pub async fn get_public_menu(
    State(state): State<AppState>,
) -> Result<Json<PublicMenuResponse>, ApiError> {
    let menu = state.catalog_service.get_public_menu().await?;
    Ok(Json(menu))
}

// ---------- Owner Categories ----------

pub async fn list_owner_categories(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<Vec<CategoryDto>>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let categories = state.categories_service.list_all().await?;
    Ok(Json(categories))
}

pub async fn create_category(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<CreateCategoryRequest>,
) -> Result<(StatusCode, Json<CategoryDto>), ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let created = state
        .categories_service
        .create_category(&body, &audit)
        .await?;
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn update_category(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<UpdateCategoryRequest>,
) -> Result<Json<CategoryDto>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let category_id = parse_uuid_param(&id, "category_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .categories_service
        .update_category(category_id, &body, &audit)
        .await?;
    Ok(Json(updated))
}

pub async fn delete_category(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let category_id = parse_uuid_param(&id, "category_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    state
        .categories_service
        .delete_category(category_id, &audit)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

// ---------- Owner Products & Variants ----------

pub async fn list_owner_products(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<Vec<ProductDto>>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let products = state.catalog_service.list_all_products().await?;
    Ok(Json(products))
}

pub async fn create_product(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<CreateProductRequest>,
) -> Result<(StatusCode, Json<ProductDto>), ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let created = state.catalog_service.create_product(&body, &audit).await?;
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn update_product(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<UpdateProductRequest>,
) -> Result<Json<ProductDto>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let product_id = parse_uuid_param(&id, "product_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .catalog_service
        .update_product(product_id, &body, &audit)
        .await?;
    Ok(Json(updated))
}

pub async fn delete_product(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let product_id = parse_uuid_param(&id, "product_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    state
        .catalog_service
        .delete_product(product_id, &audit)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn add_variant(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<CreateVariantRequest>,
) -> Result<(StatusCode, Json<ProductVariantDto>), ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let product_id = parse_uuid_param(&id, "product_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let created = state
        .catalog_service
        .add_variant(product_id, &body, &audit)
        .await?;
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn update_variant(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<UpdateVariantRequest>,
) -> Result<Json<ProductVariantDto>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let variant_id = parse_uuid_param(&id, "variant_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .catalog_service
        .update_variant(variant_id, &body, &audit)
        .await?;
    Ok(Json(updated))
}

// ---------- Owner Crusts & Toppings ----------

pub async fn list_owner_crusts(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<Vec<CrustOptionDto>>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let crusts = state.catalog_service.list_all_crusts().await?;
    Ok(Json(crusts))
}

pub async fn create_crust(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<CreateCrustRequest>,
) -> Result<(StatusCode, Json<CrustOptionDto>), ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let created = state.catalog_service.create_crust(&body, &audit).await?;
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn update_crust(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<UpdateCrustRequest>,
) -> Result<Json<CrustOptionDto>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let crust_id = parse_uuid_param(&id, "crust_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .catalog_service
        .update_crust(crust_id, &body, &audit)
        .await?;
    Ok(Json(updated))
}

pub async fn list_owner_toppings(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<Vec<ToppingDto>>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let toppings = state.catalog_service.list_all_toppings().await?;
    Ok(Json(toppings))
}

pub async fn create_topping(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<CreateToppingRequest>,
) -> Result<(StatusCode, Json<ToppingDto>), ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let created = state.catalog_service.create_topping(&body, &audit).await?;
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn update_topping(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<UpdateToppingRequest>,
) -> Result<Json<ToppingDto>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let topping_id = parse_uuid_param(&id, "topping_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .catalog_service
        .update_topping(topping_id, &body, &audit)
        .await?;
    Ok(Json(updated))
}

pub async fn list_owner_size_rules(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<Vec<PizzaSizeRuleDto>>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let rules = state.catalog_service.list_all_size_rules().await?;
    Ok(Json(rules))
}

pub async fn update_size_rule(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(size_code): Path<String>,
    ValidatedJson(body): ValidatedJson<UpdatePizzaSizeRuleRequest>,
) -> Result<Json<PizzaSizeRuleDto>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let parsed_size = parse_size_code(&size_code)?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .catalog_service
        .update_size_rule(parsed_size, &body, &audit)
        .await?;
    Ok(Json(updated))
}

pub async fn list_owner_cheeses(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<Vec<CheeseOptionDto>>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let cheeses = state.catalog_service.list_all_cheeses().await?;
    Ok(Json(cheeses))
}

pub async fn create_cheese(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<CreateCheeseRequest>,
) -> Result<(StatusCode, Json<CheeseOptionDto>), ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let created = state.catalog_service.create_cheese(&body, &audit).await?;
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn update_cheese(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<UpdateCheeseRequest>,
) -> Result<Json<CheeseOptionDto>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let cheese_id = parse_uuid_param(&id, "cheese_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .catalog_service
        .update_cheese(cheese_id, &body, &audit)
        .await?;
    Ok(Json(updated))
}

pub async fn list_owner_sauces(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<Vec<SauceOptionDto>>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let sauces = state.catalog_service.list_all_sauces().await?;
    Ok(Json(sauces))
}

pub async fn create_sauce(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<CreateSauceRequest>,
) -> Result<(StatusCode, Json<SauceOptionDto>), ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let created = state.catalog_service.create_sauce(&body, &audit).await?;
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn update_sauce(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<UpdateSauceRequest>,
) -> Result<Json<SauceOptionDto>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let sauce_id = parse_uuid_param(&id, "sauce_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .catalog_service
        .update_sauce(sauce_id, &body, &audit)
        .await?;
    Ok(Json(updated))
}

// ---------- Owner Product Images ----------

pub async fn presign_product_image(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<PresignProductImageRequest>,
) -> Result<(StatusCode, Json<PresignProductImageResponse>), ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let product_id = parse_uuid_param(&id, "product_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let resp = state
        .images_service
        .presign_upload(product_id, principal.user_id, &body, &audit)
        .await?;
    Ok((StatusCode::CREATED, Json(resp)))
}

pub async fn confirm_product_image(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<ConfirmProductImageRequest>,
) -> Result<Json<ProductImageDto>, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let product_id = parse_uuid_param(&id, "product_id")?;
    let image_id = parse_uuid_param(&body.image_id, "image_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let resp = state
        .images_service
        .confirm_upload(product_id, image_id, &audit)
        .await?;
    Ok(Json(resp))
}

pub async fn delete_product_image(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path((id, img_id)): Path<(String, String)>,
) -> Result<StatusCode, ApiError> {
    require_permission(&state, &principal, "owner:catalog_manage").await?;
    let product_id = parse_uuid_param(&id, "product_id")?;
    let image_id = parse_uuid_param(&img_id, "image_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    state
        .images_service
        .delete_image(product_id, image_id, &audit)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

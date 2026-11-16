use std::collections::BTreeMap;
use std::sync::Arc;

use chrono::{DateTime, Utc};
use pizzora_core::StorageKeyValidator;
use pizzora_db::{new_uuid_v4, record_audit_log, AuditContext, EphemeralCacheStore};
use serde_json::json;
use sqlx::{PgPool, Row as _};
use tracing::warn;
use uuid::Uuid;

use crate::dto::{PresignProductImageRequest, PresignProductImageResponse, ProductImageDto};
use crate::error::{format_iso8601, ApiError};
use crate::services::catalog::parse_image_status;
use crate::services::catalog_cache::invalidate_catalog_cache;
use crate::storage::{detect_image_mime_type, ObjectStorageClient};

pub const MIN_IMAGE_SIZE_BYTES: i64 = 16;
pub const MAX_IMAGE_SIZE_BYTES: i64 = 5 * 1024 * 1024;
pub const PRESIGN_EXPIRES_SECONDS: i64 = 300;

#[derive(Clone)]
pub struct ProductImagesService {
    pool: PgPool,
    storage: Arc<dyn ObjectStorageClient>,
    cache: Arc<dyn EphemeralCacheStore>,
}

impl ProductImagesService {
    pub fn new(
        pool: PgPool,
        storage: Arc<dyn ObjectStorageClient>,
        cache: Arc<dyn EphemeralCacheStore>,
    ) -> Self {
        Self {
            pool,
            storage,
            cache,
        }
    }

    pub async fn presign_upload(
        &self,
        product_id: Uuid,
        uploaded_by: Uuid,
        request: &PresignProductImageRequest,
        audit: &AuditContext,
    ) -> Result<PresignProductImageResponse, ApiError> {
        let mime_type = request.mime_type.trim().to_ascii_lowercase();
        let extension = match mime_type.as_str() {
            "image/jpeg" => "jpg",
            "image/png" => "png",
            "image/webp" => "webp",
            _ => {
                return Err(ApiError::validation(
                    "UNSUPPORTED_IMAGE_TYPE",
                    "Only JPEG, PNG, and WebP images are allowed.",
                ));
            }
        };

        if request.size_bytes <= 0 || request.size_bytes > MAX_IMAGE_SIZE_BYTES {
            return Err(ApiError::validation(
                "IMAGE_TOO_LARGE",
                format!(
                    "Image size must be between 1 byte and 5 MB ({MAX_IMAGE_SIZE_BYTES} bytes)."
                ),
            ));
        }

        if !self.storage.is_enabled() {
            return Err(ApiError::service_unavailable(
                "OBJECT_STORAGE_UNAVAILABLE",
                "Image storage is disabled. Catalog browsing and ordering remain available.",
            ));
        }

        let image_id = new_uuid_v4();
        let object_key = format!("products/{product_id}/{image_id}.{extension}");
        let presigned = self
            .storage
            .generate_presigned_put_url(&object_key, &mime_type, PRESIGN_EXPIRES_SECONDS)
            .await?;

        let mut tx = self.pool.begin().await?;
        let prod_exists: Option<i32> = sqlx::query_scalar("SELECT 1 FROM products WHERE id = $1")
            .bind(product_id)
            .fetch_optional(&mut *tx)
            .await?;
        if prod_exists.is_none() {
            return Err(ApiError::not_found(
                "PRODUCT_NOT_FOUND",
                format!("Product {product_id} was not found."),
            ));
        }

        sqlx::query(
            r#"
            INSERT INTO product_images (
                id, product_id, object_key, mime_type, size_bytes, status, sort_order, uploaded_by
            ) VALUES ($1, $2, $3, $4, $5, 'PENDING', $6, $7)
            "#,
        )
        .bind(image_id)
        .bind(product_id)
        .bind(&object_key)
        .bind(&mime_type)
        .bind(request.size_bytes)
        .bind(request.sort_order.max(0))
        .bind(uploaded_by)
        .execute(&mut *tx)
        .await?;

        let after_val = json!({
            "product_id": product_id.to_string(),
            "object_key": object_key,
            "mime_type": mime_type,
        });
        let entity_id = image_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "PRODUCT_IMAGE_PRESIGNED",
            "product_image",
            &entity_id,
            None,
            Some(&after_val),
        )
        .await?;

        tx.commit().await?;

        let mut required_headers = BTreeMap::new();
        for (k, v) in presigned.required_headers {
            required_headers.insert(k, v);
        }

        Ok(PresignProductImageResponse {
            image_id: image_id.to_string(),
            object_key: object_key.clone(),
            upload_url: presigned.upload_url,
            method: "PUT".to_owned(),
            required_headers,
            expires_in_seconds: presigned.expires_in_seconds,
            public_url: self
                .storage
                .resolve_public_url(&object_key)
                .unwrap_or_default(),
        })
    }

    pub async fn confirm_upload(
        &self,
        product_id: Uuid,
        image_id: Uuid,
        audit: &AuditContext,
    ) -> Result<ProductImageDto, ApiError> {
        if !self.storage.is_enabled() {
            return Err(ApiError::service_unavailable(
                "OBJECT_STORAGE_UNAVAILABLE",
                "Image storage is disabled. Catalog browsing and ordering remain available.",
            ));
        }

        let row = sqlx::query(
            r#"
            SELECT object_key, mime_type, status
            FROM product_images
            WHERE id = $1 AND product_id = $2
            "#,
        )
        .bind(image_id)
        .bind(product_id)
        .fetch_optional(&self.pool)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "IMAGE_NOT_FOUND",
                format!("Product image {image_id} was not found for product {product_id}."),
            )
        })?;

        let object_key: String = row.try_get("object_key")?;
        let declared_mime: String = row.try_get("mime_type")?;
        let status_str: String = row.try_get("status")?;
        if status_str == "DELETED" {
            return Err(ApiError::validation(
                "IMAGE_DELETED",
                "Cannot confirm a deleted image.",
            ));
        }

        StorageKeyValidator::validate_object_key(&object_key).map_err(ApiError::from)?;

        let meta = self.storage.head_object(&object_key).await?;
        if meta.content_length < MIN_IMAGE_SIZE_BYTES || meta.content_length > MAX_IMAGE_SIZE_BYTES {
            let _ = self.storage.delete_object(&object_key).await;
            self.record_rejected_upload_audit(
                product_id,
                image_id,
                audit,
                "INVALID_SIZE",
                meta.content_length,
                None,
            )
            .await;
            return Err(ApiError::validation(
                "IMAGE_TOO_LARGE",
                format!(
                    "Uploaded image size ({} bytes) must be between {MIN_IMAGE_SIZE_BYTES} bytes and 5 MB.",
                    meta.content_length
                ),
            ));
        }

        if let Some(stored_ct) = meta
            .content_type
            .as_deref()
            .map(|s| s.split(';').next().unwrap_or("").trim().to_ascii_lowercase())
            .filter(|s| !s.is_empty())
        {
            if stored_ct != "application/octet-stream"
                && stored_ct != "binary/octet-stream"
                && stored_ct != declared_mime
            {
                let _ = self.storage.delete_object(&object_key).await;
                self.record_rejected_upload_audit(
                    product_id,
                    image_id,
                    audit,
                    "CONTENT_TYPE_MISMATCH",
                    meta.content_length,
                    Some(&stored_ct),
                )
                .await;
                return Err(ApiError::unprocessable(
                    "INVALID_IMAGE_SIGNATURE",
                    format!(
                        "Uploaded object Content-Type '{stored_ct}' does not match '{declared_mime}'."
                    ),
                ));
            }
        }

        let header_bytes = self.storage.read_object_header(&object_key, 32).await?;
        let detected = detect_image_mime_type(&header_bytes);
        if detected != Some(declared_mime.as_str()) {
            let _ = self.storage.delete_object(&object_key).await;
            self.record_rejected_upload_audit(
                product_id,
                image_id,
                audit,
                "MAGIC_BYTES_MISMATCH",
                meta.content_length,
                detected.or(Some("unknown")),
            )
            .await;
            return Err(ApiError::unprocessable(
                "INVALID_IMAGE_SIGNATURE",
                format!(
                    "Uploaded object content signature does not match declared MIME type '{declared_mime}'."
                ),
            ));
        }

        let mut tx = self.pool.begin().await?;
        let updated_row = sqlx::query(
            r#"
            UPDATE product_images
            SET status = 'ACTIVE', size_bytes = $1
            WHERE id = $2 AND product_id = $3
            RETURNING id, product_id, object_key, mime_type, size_bytes, status, sort_order, created_at
            "#,
        )
        .bind(meta.content_length)
        .bind(image_id)
        .bind(product_id)
        .fetch_one(&mut *tx)
        .await?;

        let updated_key: String = updated_row.try_get("object_key")?;
        let created_at: DateTime<Utc> = updated_row.try_get("created_at")?;
        let status_raw: String = updated_row.try_get("status")?;
        let confirmed = ProductImageDto {
            id: updated_row.try_get::<Uuid, _>("id")?.to_string(),
            product_id: updated_row.try_get::<Uuid, _>("product_id")?.to_string(),
            object_key: updated_key.clone(),
            public_url: self
                .storage
                .resolve_public_url(&updated_key)
                .unwrap_or_default(),
            mime_type: updated_row.try_get("mime_type")?,
            size_bytes: updated_row.try_get("size_bytes")?,
            status: parse_image_status(&status_raw),
            sort_order: updated_row.try_get("sort_order")?,
            created_at: format_iso8601(created_at),
        };

        let after_val = serde_json::to_value(&confirmed).ok();
        let entity_id = image_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "PRODUCT_IMAGE_CONFIRMED",
            "product_image",
            &entity_id,
            None,
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;
        Ok(confirmed)
    }

    pub async fn delete_image(
        &self,
        product_id: Uuid,
        image_id: Uuid,
        audit: &AuditContext,
    ) -> Result<(), ApiError> {
        let mut tx = self.pool.begin().await?;
        let existing_key: String = sqlx::query_scalar(
            r#"
            SELECT object_key
            FROM product_images
            WHERE id = $1 AND product_id = $2 AND status <> 'DELETED'
            FOR UPDATE
            "#,
        )
        .bind(image_id)
        .bind(product_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| {
            ApiError::not_found(
                "IMAGE_NOT_FOUND",
                format!("Product image {image_id} was not found."),
            )
        })?;

        sqlx::query(
            "UPDATE product_images SET status = 'DELETED' WHERE id = $1 AND product_id = $2",
        )
        .bind(image_id)
        .bind(product_id)
        .execute(&mut *tx)
        .await?;

        let before_val = json!({ "object_key": existing_key });
        let entity_id = image_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "PRODUCT_IMAGE_DELETED",
            "product_image",
            &entity_id,
            Some(&before_val),
            None,
        )
        .await?;

        tx.commit().await?;
        invalidate_catalog_cache(&*self.cache).await;

        if self.storage.delete_object(&existing_key).await.is_err() {
            warn!(object_key = %existing_key, "Best-effort S3 delete failed or object missing");
        }
        Ok(())
    }

    async fn record_rejected_upload_audit(
        &self,
        product_id: Uuid,
        image_id: Uuid,
        audit: &AuditContext,
        reason: &str,
        size_bytes: i64,
        detected: Option<&str>,
    ) {
        warn!(
            %product_id,
            %image_id,
            reason,
            size_bytes,
            detected = detected.unwrap_or("none"),
            "Rejected malformed image upload"
        );
        if let Ok(mut tx) = self.pool.begin().await {
            let after_val = json!({
                "product_id": product_id.to_string(),
                "reason": reason,
                "size_bytes": size_bytes,
                "detected": detected,
            });
            let entity_id = image_id.to_string();
            let _ = record_audit_log(
                &mut tx,
                audit,
                "PRODUCT_IMAGE_REJECTED",
                "product_image",
                &entity_id,
                None,
                Some(&after_val),
            )
            .await;
            let _ = tx.commit().await;
        }
    }
}

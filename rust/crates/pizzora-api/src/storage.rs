use std::collections::BTreeMap;
use std::future::Future;
use std::pin::Pin;
use std::time::Duration;

use chrono::Utc;
use hmac::{Hmac, Mac};
use pizzora_config::StorageConfig;
use reqwest::header::{AUTHORIZATION, HOST, RANGE};
use reqwest::Client;
use sha2::{Digest, Sha256};

use crate::error::ApiError;

type HmacSha256 = Hmac<Sha256>;

pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PresignedPutUpload {
    pub upload_url: String,
    pub required_headers: BTreeMap<String, String>,
    pub expires_in_seconds: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredObjectMetadata {
    pub object_key: String,
    pub content_length: i64,
    pub content_type: Option<String>,
}

pub trait ObjectStorageClient: Send + Sync {
    fn is_enabled(&self) -> bool;

    fn generate_presigned_put_url<'a>(
        &'a self,
        object_key: &'a str,
        content_type: &'a str,
        ttl_seconds: i64,
    ) -> BoxFuture<'a, Result<PresignedPutUpload, ApiError>>;

    fn head_object<'a>(
        &'a self,
        object_key: &'a str,
    ) -> BoxFuture<'a, Result<StoredObjectMetadata, ApiError>>;

    fn read_object_header<'a>(
        &'a self,
        object_key: &'a str,
        max_bytes: usize,
    ) -> BoxFuture<'a, Result<Vec<u8>, ApiError>>;

    fn delete_object<'a>(&'a self, object_key: &'a str) -> BoxFuture<'a, Result<(), ApiError>>;

    fn resolve_public_url(&self, object_key: &str) -> Option<String>;
}

pub struct UnavailableObjectStorageClient;

fn storage_disabled_err() -> ApiError {
    ApiError::service_unavailable(
        "OBJECT_STORAGE_UNAVAILABLE",
        "Product image storage is currently disabled (S3_ENABLED=false).",
    )
}

impl ObjectStorageClient for UnavailableObjectStorageClient {
    fn is_enabled(&self) -> bool {
        false
    }

    fn generate_presigned_put_url<'a>(
        &'a self,
        _object_key: &'a str,
        _content_type: &'a str,
        _ttl_seconds: i64,
    ) -> BoxFuture<'a, Result<PresignedPutUpload, ApiError>> {
        Box::pin(async { Err(storage_disabled_err()) })
    }

    fn head_object<'a>(
        &'a self,
        _object_key: &'a str,
    ) -> BoxFuture<'a, Result<StoredObjectMetadata, ApiError>> {
        Box::pin(async { Err(storage_disabled_err()) })
    }

    fn read_object_header<'a>(
        &'a self,
        _object_key: &'a str,
        _max_bytes: usize,
    ) -> BoxFuture<'a, Result<Vec<u8>, ApiError>> {
        Box::pin(async { Err(storage_disabled_err()) })
    }

    fn delete_object<'a>(&'a self, _object_key: &'a str) -> BoxFuture<'a, Result<(), ApiError>> {
        Box::pin(async { Ok(()) })
    }

    fn resolve_public_url(&self, _object_key: &str) -> Option<String> {
        None
    }
}

pub struct S3ObjectStorageClient {
    config: StorageConfig,
    http: Client,
    host_header: String,
    base_path_prefix: String,
}

impl S3ObjectStorageClient {
    pub fn new(config: StorageConfig) -> Result<Self, ApiError> {
        let parsed = url::Url::parse(&config.endpoint).map_err(|_| ApiError::internal())?;
        let host = parsed.host_str().unwrap_or(&config.host);
        let host_header = match parsed.port() {
            Some(p) => format!("{host}:{p}"),
            None => host.to_owned(),
        };
        let base_path = parsed.path().trim_matches('/').to_owned();
        let http = Client::builder()
            .timeout(Duration::from_secs(10))
            .build()
            .map_err(|_| ApiError::internal())?;
        Ok(Self {
            config,
            http,
            host_header,
            base_path_prefix: base_path,
        })
    }

    fn canonical_object_path(&self, object_key: &str) -> String {
        let clean_key = object_key.trim_start_matches('/');
        let encoded_key = clean_key
            .split('/')
            .map(aws_uri_encode)
            .collect::<Vec<_>>()
            .join("/");
        let encoded_bucket = aws_uri_encode(&self.config.bucket);
        if self.base_path_prefix.is_empty() {
            format!("/{encoded_bucket}/{encoded_key}")
        } else {
            let encoded_base = self
                .base_path_prefix
                .split('/')
                .map(aws_uri_encode)
                .collect::<Vec<_>>()
                .join("/");
            format!("/{encoded_base}/{encoded_bucket}/{encoded_key}")
        }
    }

    pub(crate) fn presign_put_at(
        &self,
        object_key: &str,
        content_type: &str,
        ttl_seconds: i64,
        now: chrono::DateTime<Utc>,
    ) -> Result<PresignedPutUpload, ApiError> {
        let clamped_ttl = ttl_seconds.clamp(30, 3600);
        let amz_date = now.format("%Y%m%dT%H%M%SZ").to_string();
        let date_stamp = now.format("%Y%m%d").to_string();
        let credential_scope = format!("{date_stamp}/{}/s3/aws4_request", self.config.region);
        let canonical_uri = self.canonical_object_path(object_key);

        let mut query_params = BTreeMap::new();
        query_params.insert("X-Amz-Algorithm", "AWS4-HMAC-SHA256".to_owned());
        query_params.insert(
            "X-Amz-Credential",
            format!("{}/{credential_scope}", self.config.access_key),
        );
        query_params.insert("X-Amz-Date", amz_date.clone());
        query_params.insert("X-Amz-Expires", clamped_ttl.to_string());
        query_params.insert("X-Amz-SignedHeaders", "content-type;host".to_owned());

        let canonical_query = query_params
            .iter()
            .map(|(k, v)| format!("{}={}", aws_uri_encode(k), aws_uri_encode(v)))
            .collect::<Vec<_>>()
            .join("&");

        let canonical_headers = format!(
            "content-type:{}\nhost:{}\n",
            content_type.trim(),
            self.host_header
        );
        let canonical_request = format!(
            "PUT\n{canonical_uri}\n{canonical_query}\n{canonical_headers}\ncontent-type;host\nUNSIGNED-PAYLOAD"
        );
        let signature = sign_v4(
            self.config.secret_key(),
            &date_stamp,
            &self.config.region,
            &amz_date,
            &credential_scope,
            &canonical_request,
        )?;

        let origin = format!(
            "{}://{}",
            self.config.endpoint.split("://").next().unwrap_or("http"),
            self.host_header
        );
        let upload_url =
            format!("{origin}{canonical_uri}?{canonical_query}&X-Amz-Signature={signature}");

        let mut required_headers = BTreeMap::new();
        required_headers.insert("Content-Type".to_owned(), content_type.trim().to_owned());

        Ok(PresignedPutUpload {
            upload_url,
            required_headers,
            expires_in_seconds: clamped_ttl,
        })
    }

    fn signed_headers(
        &self,
        method: &str,
        canonical_uri: &str,
        extra_headers: &[(&str, &str)],
    ) -> Result<Vec<(String, String)>, ApiError> {
        let now = Utc::now();
        let amz_date = now.format("%Y%m%dT%H%M%SZ").to_string();
        let date_stamp = now.format("%Y%m%d").to_string();
        let credential_scope = format!("{date_stamp}/{}/s3/aws4_request", self.config.region);
        let payload_hash = "UNSIGNED-PAYLOAD";

        let mut hdrs = BTreeMap::new();
        hdrs.insert("host".to_owned(), self.host_header.clone());
        hdrs.insert("x-amz-content-sha256".to_owned(), payload_hash.to_owned());
        hdrs.insert("x-amz-date".to_owned(), amz_date.clone());
        for (k, v) in extra_headers {
            hdrs.insert(k.to_ascii_lowercase(), (*v).to_owned());
        }

        let signed_headers_str = hdrs
            .keys()
            .map(String::as_str)
            .collect::<Vec<_>>()
            .join(";");
        let mut canonical_headers = String::new();
        for (k, v) in &hdrs {
            canonical_headers.push_str(k);
            canonical_headers.push(':');
            canonical_headers.push_str(v.trim());
            canonical_headers.push('\n');
        }

        let canonical_request = format!(
            "{method}\n{canonical_uri}\n\n{canonical_headers}\n{signed_headers_str}\n{payload_hash}"
        );
        let signature = sign_v4(
            self.config.secret_key(),
            &date_stamp,
            &self.config.region,
            &amz_date,
            &credential_scope,
            &canonical_request,
        )?;
        let auth_header = format!(
            "AWS4-HMAC-SHA256 Credential={}/{credential_scope}, SignedHeaders={signed_headers_str}, Signature={signature}",
            self.config.access_key
        );

        let mut out: Vec<(String, String)> = hdrs.into_iter().collect();
        out.push(("authorization".to_owned(), auth_header));
        Ok(out)
    }

    fn apply_signed_headers(
        mut req: reqwest::RequestBuilder,
        signed: Vec<(String, String)>,
    ) -> reqwest::RequestBuilder {
        for (k, v) in signed {
            req = match k.as_str() {
                "host" => req.header(HOST, v),
                "authorization" => req.header(AUTHORIZATION, v),
                "range" => req.header(RANGE, v),
                _ => req.header(&k, v),
            };
        }
        req
    }

    fn request_url(&self, canonical_uri: &str) -> String {
        let scheme = self.config.endpoint.split("://").next().unwrap_or("http");
        format!("{scheme}://{}{canonical_uri}", self.host_header)
    }
}

impl ObjectStorageClient for S3ObjectStorageClient {
    fn is_enabled(&self) -> bool {
        true
    }

    fn generate_presigned_put_url<'a>(
        &'a self,
        object_key: &'a str,
        content_type: &'a str,
        ttl_seconds: i64,
    ) -> BoxFuture<'a, Result<PresignedPutUpload, ApiError>> {
        Box::pin(
            async move { self.presign_put_at(object_key, content_type, ttl_seconds, Utc::now()) },
        )
    }

    fn head_object<'a>(
        &'a self,
        object_key: &'a str,
    ) -> BoxFuture<'a, Result<StoredObjectMetadata, ApiError>> {
        Box::pin(async move {
            let uri = self.canonical_object_path(object_key);
            let url = self.request_url(&uri);
            let signed = self.signed_headers("HEAD", &uri, &[])?;
            let resp = Self::apply_signed_headers(self.http.head(&url), signed)
                .send()
                .await
                .map_err(|_| {
                    ApiError::service_unavailable(
                        "OBJECT_STORAGE_UNAVAILABLE",
                        "Unable to verify uploaded object in object storage.",
                    )
                })?;
            if resp.status().as_u16() == 404 {
                return Err(ApiError::validation(
                    "IMAGE_NOT_UPLOADED",
                    "Uploaded image object was not found in object storage.",
                ));
            }
            if !resp.status().is_success() {
                return Err(ApiError::service_unavailable(
                    "OBJECT_STORAGE_UNAVAILABLE",
                    "Failed to query object storage metadata.",
                ));
            }
            let content_length = resp
                .headers()
                .get(reqwest::header::CONTENT_LENGTH)
                .and_then(|v| v.to_str().ok())
                .and_then(|s| s.parse::<i64>().ok())
                .unwrap_or(0);
            let content_type = resp
                .headers()
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .map(|s| s.split(';').next().unwrap_or("").trim().to_owned());

            Ok(StoredObjectMetadata {
                object_key: object_key.to_owned(),
                content_length,
                content_type,
            })
        })
    }

    fn read_object_header<'a>(
        &'a self,
        object_key: &'a str,
        max_bytes: usize,
    ) -> BoxFuture<'a, Result<Vec<u8>, ApiError>> {
        Box::pin(async move {
            let uri = self.canonical_object_path(object_key);
            let url = self.request_url(&uri);
            let range_val = format!("bytes=0-{}", max_bytes.saturating_sub(1));
            let signed = self.signed_headers("GET", &uri, &[("range", &range_val)])?;
            let resp = Self::apply_signed_headers(self.http.get(&url), signed)
                .send()
                .await
                .map_err(|_| {
                    ApiError::service_unavailable(
                        "OBJECT_STORAGE_UNAVAILABLE",
                        "Unable to read uploaded object header from object storage.",
                    )
                })?;
            if resp.status().as_u16() == 404 {
                return Err(ApiError::validation(
                    "IMAGE_NOT_UPLOADED",
                    "Uploaded image object was not found in object storage.",
                ));
            }
            if !resp.status().is_success() {
                return Err(ApiError::service_unavailable(
                    "OBJECT_STORAGE_UNAVAILABLE",
                    "Failed to inspect uploaded object in object storage.",
                ));
            }
            let bytes = resp.bytes().await.map_err(|_| {
                ApiError::service_unavailable(
                    "OBJECT_STORAGE_UNAVAILABLE",
                    "Failed to stream uploaded object header.",
                )
            })?;
            Ok(bytes.iter().take(max_bytes).copied().collect())
        })
    }

    fn delete_object<'a>(&'a self, object_key: &'a str) -> BoxFuture<'a, Result<(), ApiError>> {
        Box::pin(async move {
            let uri = self.canonical_object_path(object_key);
            let url = self.request_url(&uri);
            let signed = self.signed_headers("DELETE", &uri, &[])?;
            let _ = Self::apply_signed_headers(self.http.delete(&url), signed)
                .send()
                .await;
            Ok(())
        })
    }

    fn resolve_public_url(&self, object_key: &str) -> Option<String> {
        if !self.config.enabled || self.config.public_base_url.trim().is_empty() {
            return None;
        }
        let clean = object_key.trim_start_matches('/');
        Some(format!(
            "{}/{}",
            self.config.public_base_url.trim_end_matches('/'),
            clean
        ))
    }
}

pub fn create_object_storage_client(
    config: &StorageConfig,
) -> Result<std::sync::Arc<dyn ObjectStorageClient>, ApiError> {
    if !config.enabled {
        return Ok(std::sync::Arc::new(UnavailableObjectStorageClient));
    }
    Ok(std::sync::Arc::new(S3ObjectStorageClient::new(
        config.clone(),
    )?))
}

pub fn detect_image_mime_type(bytes: &[u8]) -> Option<&'static str> {
    if bytes.len() >= 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF {
        return Some("image/jpeg");
    }
    if bytes.len() >= 8 && bytes[..8] == [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] {
        return Some("image/png");
    }
    if bytes.len() >= 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        return Some("image/webp");
    }
    None
}

fn sign_v4(
    secret_key: &str,
    date_stamp: &str,
    region: &str,
    amz_date: &str,
    credential_scope: &str,
    canonical_request: &str,
) -> Result<String, ApiError> {
    let req_hash = hex::encode(Sha256::digest(canonical_request.as_bytes()));
    let string_to_sign = format!("AWS4-HMAC-SHA256\n{amz_date}\n{credential_scope}\n{req_hash}");

    let k_secret = format!("AWS4{secret_key}");
    let k_date = hmac_sha256_raw(k_secret.as_bytes(), date_stamp.as_bytes())?;
    let k_region = hmac_sha256_raw(&k_date, region.as_bytes())?;
    let k_service = hmac_sha256_raw(&k_region, b"s3")?;
    let k_signing = hmac_sha256_raw(&k_service, b"aws4_request")?;
    Ok(hex::encode(hmac_sha256_raw(
        &k_signing,
        string_to_sign.as_bytes(),
    )?))
}

fn hmac_sha256_raw(key: &[u8], data: &[u8]) -> Result<[u8; 32], ApiError> {
    let mut mac = HmacSha256::new_from_slice(key).map_err(|_| ApiError::internal())?;
    mac.update(data);
    let result = mac.finalize().into_bytes();
    let mut out = [0u8; 32];
    out.copy_from_slice(&result);
    Ok(out)
}

fn aws_uri_encode(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    for byte in raw.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(byte as char);
            }
            other => {
                use std::fmt::Write as _;
                let _ = write!(out, "%{other:02X}");
            }
        }
    }
    out
}

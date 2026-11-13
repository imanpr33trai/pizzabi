package com.pizzora.backend.images

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.catalog.CatalogCacheKeys
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.core.error.ServiceUnavailableException
import com.pizzora.backend.core.error.UnprocessableEntityException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.contracts.PresignProductImageRequest
import com.pizzora.contracts.PresignProductImageResponse
import com.pizzora.contracts.ProductImageDto
import com.pizzora.contracts.ProductImageStatus
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class ProductImagesService(
  private val dataSource: DataSource,
  private val objectStorageClient: ObjectStorageClient,
  private val cacheStore: EphemeralCacheStore,
) {
  private val logger = LoggerFactory.getLogger(ProductImagesService::class.java)
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  companion object {
    const val MAX_IMAGE_SIZE_BYTES = 5L * 1024L * 1024L
    val ALLOWED_MIME_TYPES = mapOf(
      "image/jpeg" to "jpg",
      "image/png" to "png",
      "image/webp" to "webp",
    )
    const val PRESIGN_EXPIRES_SECONDS = 300L
  }

  suspend fun presignUpload(
    productId: UUID,
    uploadedBy: UUID,
    request: PresignProductImageRequest,
    auditContext: AuditContext,
  ): PresignProductImageResponse {
    val mimeType = request.mimeType.trim().lowercase()
    val extension = ALLOWED_MIME_TYPES[mimeType]
      ?: throw ValidationException(
        "UNSUPPORTED_IMAGE_TYPE",
        "Only JPEG, PNG, and WebP images are allowed.",
      )

    if (request.sizeBytes <= 0 || request.sizeBytes > MAX_IMAGE_SIZE_BYTES) {
      throw ValidationException(
        "IMAGE_TOO_LARGE",
        "Image size must be between 1 byte and 5 MB ($MAX_IMAGE_SIZE_BYTES bytes).",
      )
    }

    val imageId = UUID.randomUUID()
    val objectKey = "products/$productId/$imageId.$extension"

    val uploadUrl = try {
      objectStorageClient.presignPut(
        objectKey = objectKey,
        contentType = mimeType,
        expiresInSeconds = PRESIGN_EXPIRES_SECONDS,
      )
    } catch (ex: ObjectStorageUnavailableException) {
      logger.error("Object storage unavailable during presign for productId={}", productId, ex)
      throw ServiceUnavailableException(
        "OBJECT_STORAGE_UNAVAILABLE",
        "Image storage is temporarily unavailable. Catalog browsing and ordering remain available.",
      )
    }

    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val productExists = conn.prepareStatement("SELECT 1 FROM products WHERE id = ?").use { ps ->
        ps.setObject(1, productId)
        ps.executeQuery().use { rs -> rs.next() }
      }
      if (!productExists) {
        throw NotFoundException("PRODUCT_NOT_FOUND", "Product $productId was not found.")
      }

      conn.prepareStatement(
        """
        INSERT INTO product_images (
          id, product_id, object_key, mime_type, size_bytes, status, sort_order, uploaded_by
        ) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, imageId)
        ps.setObject(2, productId)
        ps.setString(3, objectKey)
        ps.setString(4, mimeType)
        ps.setLong(5, request.sizeBytes)
        ps.setInt(6, request.sortOrder.coerceAtLeast(0))
        ps.setObject(7, uploadedBy)
        ps.executeUpdate()
      }

      AuditLogger.record(
        conn = conn,
        context = auditContext,
        action = "PRODUCT_IMAGE_PRESIGNED",
        entityType = "product_image",
        entityId = imageId.toString(),
        afterJson = """{"product_id":"$productId","object_key":"$objectKey","mime_type":"$mimeType"}""",
      )
    }

    return PresignProductImageResponse(
      imageId = imageId.toString(),
      objectKey = objectKey,
      uploadUrl = uploadUrl,
      method = "PUT",
      requiredHeaders = mapOf("Content-Type" to mimeType),
      expiresInSeconds = PRESIGN_EXPIRES_SECONDS,
      publicUrl = objectStorageClient.publicUrlFor(objectKey),
    )
  }

  suspend fun confirmUpload(
    productId: UUID,
    imageId: UUID,
    auditContext: AuditContext,
  ): ProductImageDto {
    data class PendingImageRecord(
      val objectKey: String,
      val declaredMimeType: String,
      val status: String,
    )

    val record = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      conn.prepareStatement(
        """
        SELECT object_key, mime_type, status
        FROM product_images
        WHERE id = ? AND product_id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, imageId)
        ps.setObject(2, productId)
        ps.executeQuery().use { rs ->
          if (!rs.next()) {
            throw NotFoundException("IMAGE_NOT_FOUND", "Product image $imageId was not found for product $productId.")
          }
          PendingImageRecord(
            objectKey = rs.getString("object_key"),
            declaredMimeType = rs.getString("mime_type"),
            status = rs.getString("status"),
          )
        }
      }
    }

    if (record.status == "DELETED") {
      throw ValidationException("IMAGE_DELETED", "Cannot confirm a deleted image.")
    }

    val inspected = try {
      objectStorageClient.inspectObject(record.objectKey)
    } catch (ex: ObjectStorageUnavailableException) {
      logger.error("Object storage unavailable during confirm objectKey={}", record.objectKey, ex)
      throw ServiceUnavailableException(
        "OBJECT_STORAGE_UNAVAILABLE",
        "Image storage is temporarily unavailable; please retry confirmation later.",
      )
    } ?: throw ValidationException(
      "IMAGE_NOT_UPLOADED",
      "Image binary has not been uploaded to storage yet.",
    )

    if (inspected.sizeBytes <= 0 || inspected.sizeBytes > MAX_IMAGE_SIZE_BYTES) {
      objectStorageClient.deleteObject(record.objectKey)
      throw ValidationException(
        "IMAGE_TOO_LARGE",
        "Uploaded image size (${inspected.sizeBytes} bytes) exceeds the 5 MB limit.",
      )
    }

    val detectedMime = ImageMagicBytesDetector.detectMimeType(inspected.headerBytes)
    if (detectedMime == null || detectedMime != record.declaredMimeType) {
      objectStorageClient.deleteObject(record.objectKey)
      throw UnprocessableEntityException(
        "INVALID_IMAGE_SIGNATURE",
        "Uploaded object content signature does not match declared MIME type '${record.declaredMimeType}'.",
      )
    }

    val confirmed = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val updated = conn.prepareStatement(
        """
        UPDATE product_images
        SET status = 'ACTIVE', size_bytes = ?
        WHERE id = ? AND product_id = ?
        RETURNING id, product_id, object_key, mime_type, size_bytes, status, sort_order, created_at
        """.trimIndent(),
      ).use { ps ->
        ps.setLong(1, inspected.sizeBytes)
        ps.setObject(2, imageId)
        ps.setObject(3, productId)
        ps.executeQuery().use { rs ->
          rs.next()
          ProductImageDto(
            id = rs.getObject("id", UUID::class.java).toString(),
            productId = rs.getObject("product_id", UUID::class.java).toString(),
            objectKey = rs.getString("object_key"),
            publicUrl = objectStorageClient.publicUrlFor(rs.getString("object_key")),
            mimeType = rs.getString("mime_type"),
            sizeBytes = rs.getLong("size_bytes"),
            status = ProductImageStatus.valueOf(rs.getString("status")),
            sortOrder = rs.getInt("sort_order"),
            createdAt = rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant().toString(),
          )
        }
      }

      AuditLogger.record(
        conn = conn,
        context = auditContext,
        action = "PRODUCT_IMAGE_CONFIRMED",
        entityType = "product_image",
        entityId = imageId.toString(),
        afterJson = json.encodeToString(updated),
      )
      updated
    }

    CatalogCacheKeys.invalidateAll(cacheStore)
    return confirmed
  }

  suspend fun deleteImage(
    productId: UUID,
    imageId: UUID,
    auditContext: AuditContext,
  ) {
    val objectKey = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val existingKey = conn.prepareStatement(
        """
        SELECT object_key, status
        FROM product_images
        WHERE id = ? AND product_id = ? AND status <> 'DELETED'
        FOR UPDATE
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, imageId)
        ps.setObject(2, productId)
        ps.executeQuery().use { rs ->
          if (!rs.next()) {
            throw NotFoundException("IMAGE_NOT_FOUND", "Product image $imageId was not found.")
          }
          rs.getString("object_key")
        }
      }

      conn.prepareStatement(
        "UPDATE product_images SET status = 'DELETED' WHERE id = ? AND product_id = ?",
      ).use { ps ->
        ps.setObject(1, imageId)
        ps.setObject(2, productId)
        ps.executeUpdate()
      }

      AuditLogger.record(
        conn = conn,
        context = auditContext,
        action = "PRODUCT_IMAGE_DELETED",
        entityType = "product_image",
        entityId = imageId.toString(),
        beforeJson = """{"object_key":"$existingKey"}""",
      )
      existingKey
    }

    CatalogCacheKeys.invalidateAll(cacheStore)
    val deletedFromStorage = objectStorageClient.deleteObject(objectKey)
    if (!deletedFromStorage) {
      logger.warn("Best-effort S3 delete failed or object missing objectKey={}", objectKey)
    }
  }
}

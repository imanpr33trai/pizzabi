package com.example.core.media

import android.content.Context
import android.net.Uri
import com.example.core.domain.DomainBridge
import com.example.core.domain.RustDomainBridge
import com.example.core.network.PizzoraApiClient
import com.pizzora.contracts.PresignProductImageRequest
import com.pizzora.contracts.ProductImageDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProductImageUploader(
  private val apiClient: PizzoraApiClient,
  private val imageCompressor: ImageCompressor,
  private val domainBridge: DomainBridge = RustDomainBridge,
) {
  fun validateObjectKey(rawKey: String): String =
    domainBridge.validateStorageObjectKey(rawKey)

  suspend fun compressAndUploadFromUri(
    context: Context,
    productId: String,
    uri: Uri,
    sortOrder: Int = 0,
  ): ProductImageDto {
    val compressed = withContext(Dispatchers.IO) {
      imageCompressor.compressFromUri(context, uri)
    }
    return uploadCompressedImage(productId, compressed, sortOrder)
  }

  suspend fun uploadCompressedImage(
    productId: String,
    compressed: CompressedImage,
    sortOrder: Int = 0,
  ): ProductImageDto {
    val presign = apiClient.presignProductImage(
      productId = productId,
      request = PresignProductImageRequest(
        mimeType = compressed.mimeType,
        sizeBytes = compressed.bytes.size.toLong(),
        sortOrder = sortOrder,
      ),
    )
    presign.objectKey.takeIf { it.isNotEmpty() }?.let { domainBridge.validateStorageObjectKey(it) }
    apiClient.uploadBytesToPresignedUrl(
      uploadUrl = presign.uploadUrl,
      bytes = compressed.bytes,
      mimeType = compressed.mimeType,
    )
    return apiClient.confirmProductImage(
      productId = productId,
      imageId = presign.imageId,
    )
  }
}

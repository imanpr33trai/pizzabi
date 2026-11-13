package com.example.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class CompressedImage(
  val bytes: ByteArray,
  val mimeType: String,
  val width: Int,
  val height: Int,
)

class ImageCompressor {
  companion object {
    const val DEFAULT_MAX_DIMENSION_PX = 1280
    const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
    const val TARGET_MAX_BYTES = 1500 * 1024
  }

  fun compressFromUri(
    context: Context,
    uri: Uri,
    maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
    maxBytes: Int = MAX_UPLOAD_BYTES,
  ): CompressedImage {
    val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
      ?: throw IllegalArgumentException("Unable to read selected image URI: $uri")
    return compressImageBytes(rawBytes, maxDimensionPx = maxDimensionPx, maxBytes = maxBytes)
  }

  fun compressImageBytes(
    rawBytes: ByteArray,
    maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
    maxBytes: Int = MAX_UPLOAD_BYTES,
  ): CompressedImage {
    require(rawBytes.isNotEmpty()) { "Image data is empty." }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Selected file is not a valid raster image." }

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimensionPx * 2 || bounds.outHeight / sampleSize > maxDimensionPx * 2) {
      sampleSize *= 2
    }

    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
      ?: throw IllegalArgumentException("Failed to decode image bitmap.")

    val scaled = scaleDownIfNeeded(decoded, maxDimensionPx)
    if (scaled !== decoded) {
      decoded.recycle()
    }

    val targetBudget = minOf(maxBytes, TARGET_MAX_BYTES)
    var quality = 88
    var encoded: ByteArray
    while (true) {
      val out = ByteArrayOutputStream()
      val ok = scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
      require(ok) { "Bitmap JPEG compression failed." }
      encoded = out.toByteArray()
      if (encoded.size <= targetBudget || quality <= 45) {
        break
      }
      quality -= 10
    }

    val width = scaled.width
    val height = scaled.height
    scaled.recycle()

    require(encoded.size <= maxBytes) {
      "Compressed image size ${encoded.size} bytes still exceeds maximum $maxBytes bytes."
    }

    return CompressedImage(
      bytes = encoded,
      mimeType = "image/jpeg",
      width = width,
      height = height,
    )
  }

  private fun scaleDownIfNeeded(bitmap: Bitmap, maxDimensionPx: Int): Bitmap {
    val longest = max(bitmap.width, bitmap.height)
    if (longest <= maxDimensionPx) return bitmap
    val ratio = maxDimensionPx.toFloat() / longest.toFloat()
    val targetW = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
    val targetH = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
  }
}

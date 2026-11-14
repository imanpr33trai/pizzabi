package com.pizzora.backend.razorpay

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object RazorpaySignatureVerifier {

  fun computeHmacSha256Hex(payload: ByteArray, secret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    val digest = mac.doFinal(payload)
    return digest.joinToString("") { "%02x".format(it) }
  }

  fun verifyCheckoutSignature(
    gatewayOrderId: String,
    gatewayPaymentId: String,
    signature: String,
    keySecret: String,
  ): Boolean {
    if (gatewayOrderId.isBlank() || gatewayPaymentId.isBlank() || signature.isBlank() || keySecret.isBlank()) {
      return false
    }
    val payload = "$gatewayOrderId|$gatewayPaymentId".toByteArray(StandardCharsets.UTF_8)
    val expectedHex = computeHmacSha256Hex(payload, keySecret)
    val providedHex = signature.trim().lowercase()
    return constantTimeEqualsAscii(expectedHex, providedHex)
  }

  fun verifyWebhookSignature(
    rawBody: ByteArray,
    signatureHeader: String?,
    webhookSecret: String,
  ): Boolean {
    if (signatureHeader.isNullOrBlank() || webhookSecret.isBlank()) {
      return false
    }
    val expectedHex = computeHmacSha256Hex(rawBody, webhookSecret)
    val providedHex = signatureHeader.trim().lowercase()
    return constantTimeEqualsAscii(expectedHex, providedHex)
  }

  private fun constantTimeEqualsAscii(expected: String, actual: String): Boolean {
    val expectedBytes = expected.toByteArray(StandardCharsets.US_ASCII)
    val actualBytes = actual.toByteArray(StandardCharsets.US_ASCII)
    return MessageDigest.isEqual(expectedBytes, actualBytes)
  }
}

package com.pizzora.backend.payments

import com.pizzora.contracts.PaymentMethod
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Canonical Razorpay receipt and retry-fingerprint generator.
 *
 * Formula (must remain byte-identical with Rust `pizzora-payments::receipts`):
 *   `rcpt_` + lowercase hex SHA-256("pizzora:attempt:" + lowercase_uuid(order_id) + ":" + trimmed_key_or_attempt_id)[0..32]
 * Total length is always 37 ASCII characters (<= Razorpay's 40-char limit).
 */
object RazorpayReceiptFormatter {
  const val RECEIPT_PREFIX = "rcpt_"
  const val RECEIPT_HEX_LEN = 32
  const val MAX_RAZORPAY_RECEIPT_LEN = 40

  fun formatReceipt(orderId: UUID, keyOrAttemptId: String): String {
    val trimmed = keyOrAttemptId.trim()
    require(trimmed.isNotEmpty()) { "keyOrAttemptId must not be blank" }
    val canonicalInput = "pizzora:attempt:${orderId.toString().lowercase(Locale.ROOT)}:$trimmed"
    val hex = sha256Hex(canonicalInput).substring(0, RECEIPT_HEX_LEN)
    return "$RECEIPT_PREFIX$hex"
  }

  fun computeRetryFingerprint(
    orderId: UUID,
    paymentId: UUID,
    method: PaymentMethod,
    amountPaise: Long,
    reason: String?,
  ): String {
    val normalizedReason = reason?.trim().orEmpty()
    val canonical = "${orderId.toString().lowercase(Locale.ROOT)}|" +
      "${paymentId.toString().lowercase(Locale.ROOT)}|" +
      "${method.name}|$amountPaise|$normalizedReason"
    return sha256Hex(canonical)
  }

  private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(input.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }
}

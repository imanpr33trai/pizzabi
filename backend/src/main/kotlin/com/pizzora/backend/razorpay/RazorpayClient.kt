package com.pizzora.backend.razorpay

import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.config.RazorpayConfig
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class RazorpayGatewayException(
  val isTransient: Boolean,
  val statusCode: Int? = null,
  val code: String,
  override val message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

data class GatewayOrderResult(
  val gatewayOrderId: String,
  val receipt: String,
  val amountPaise: Long,
  val currency: String,
  val status: String,
)

data class GatewayPaymentResult(
  val gatewayPaymentId: String,
  val gatewayOrderId: String,
  val amountPaise: Long,
  val currency: String,
  val status: String,
  val errorCode: String? = null,
  val errorDescription: String? = null,
)

data class GatewayRefundResult(
  val gatewayRefundId: String,
  val gatewayPaymentId: String,
  val amountPaise: Long,
  val status: String,
  val notes: Map<String, String> = emptyMap(),
)

interface RazorpayGateway {
  val publicKeyId: String

  suspend fun createOrder(
    amountPaise: Long,
    currency: String = "INR",
    receipt: String,
    notes: Map<String, String> = emptyMap(),
  ): GatewayOrderResult

  suspend fun createRefund(
    gatewayPaymentId: String,
    amountPaise: Long,
    idempotencyKey: String,
    notes: Map<String, String> = emptyMap(),
  ): GatewayRefundResult

  suspend fun fetchPaymentsForOrder(gatewayOrderId: String): List<GatewayPaymentResult>

  suspend fun fetchRefundsForPayment(gatewayPaymentId: String): List<GatewayRefundResult>
}

fun createDefaultRazorpayGateway(config: AppConfig): RazorpayGateway {
  val rzp = config.razorpay
  return if (rzp.keyId.isNotBlank() && rzp.keySecret.isNotBlank()) {
    HttpRazorpayClient(rzp)
  } else if (config.environment == "production") {
    UnconfiguredRazorpayGateway
  } else {
    SandboxRazorpayClient(rzp.keyId.ifBlank { "rzp_test_sandbox" })
  }
}

object UnconfiguredRazorpayGateway : RazorpayGateway {
  override val publicKeyId: String = ""

  override suspend fun createOrder(
    amountPaise: Long,
    currency: String,
    receipt: String,
    notes: Map<String, String>,
  ): GatewayOrderResult = throw RazorpayGatewayException(
    isTransient = false,
    statusCode = 500,
    code = "RAZORPAY_NOT_CONFIGURED",
    message = "RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET must be configured in production.",
  )

  override suspend fun createRefund(
    gatewayPaymentId: String,
    amountPaise: Long,
    idempotencyKey: String,
    notes: Map<String, String>,
  ): GatewayRefundResult = throw RazorpayGatewayException(
    isTransient = false,
    statusCode = 500,
    code = "RAZORPAY_NOT_CONFIGURED",
    message = "RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET must be configured in production.",
  )

  override suspend fun fetchPaymentsForOrder(gatewayOrderId: String): List<GatewayPaymentResult> = emptyList()

  override suspend fun fetchRefundsForPayment(gatewayPaymentId: String): List<GatewayRefundResult> = emptyList()
}

class SandboxRazorpayClient(
  override val publicKeyId: String = "rzp_test_sandbox",
) : RazorpayGateway {
  override suspend fun createOrder(
    amountPaise: Long,
    currency: String,
    receipt: String,
    notes: Map<String, String>,
  ): GatewayOrderResult {
    val suffix = receipt.replace("-", "").take(14)
    return GatewayOrderResult(
      gatewayOrderId = "order_sbx_$suffix",
      receipt = receipt,
      amountPaise = amountPaise,
      currency = currency,
      status = "created",
    )
  }

  override suspend fun createRefund(
    gatewayPaymentId: String,
    amountPaise: Long,
    idempotencyKey: String,
    notes: Map<String, String>,
  ): GatewayRefundResult {
    val suffix = UUID.nameUUIDFromBytes(idempotencyKey.toByteArray()).toString().replace("-", "").take(14)
    return GatewayRefundResult(
      gatewayRefundId = "rfnd_sbx_$suffix",
      gatewayPaymentId = gatewayPaymentId,
      amountPaise = amountPaise,
      status = "processed",
      notes = notes + ("idempotency_key" to idempotencyKey),
    )
  }

  override suspend fun fetchPaymentsForOrder(gatewayOrderId: String): List<GatewayPaymentResult> = emptyList()

  override suspend fun fetchRefundsForPayment(gatewayPaymentId: String): List<GatewayRefundResult> = emptyList()
}

class HttpRazorpayClient(
  private val config: RazorpayConfig,
  private val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build(),
) : RazorpayGateway {

  private val json = Json { ignoreUnknownKeys = true }
  private val authHeader: String = "Basic " + Base64.getEncoder().encodeToString(
    "${config.keyId}:${config.keySecret}".toByteArray(StandardCharsets.UTF_8),
  )

  override val publicKeyId: String
    get() = config.keyId

  override suspend fun createOrder(
    amountPaise: Long,
    currency: String,
    receipt: String,
    notes: Map<String, String>,
  ): GatewayOrderResult {
    val body = buildJsonObject {
      put("amount", amountPaise)
      put("currency", currency)
      put("receipt", receipt)
      if (notes.isNotEmpty()) {
        putJsonObject("notes") {
          notes.forEach { (k, v) -> put(k, v) }
        }
      }
    }.toString()

    val respJson = executeRequest(
      method = "POST",
      path = "/orders",
      body = body,
      extraHeaders = emptyMap(),
    ).jsonObject

    return GatewayOrderResult(
      gatewayOrderId = respJson["id"]?.jsonPrimitive?.contentOrNull
        ?: throw RazorpayGatewayException(true, 502, "MALFORMED_GATEWAY_RESPONSE", "Razorpay /orders response missing id."),
      receipt = respJson["receipt"]?.jsonPrimitive?.contentOrNull ?: receipt,
      amountPaise = respJson["amount"]?.jsonPrimitive?.longOrNull ?: amountPaise,
      currency = respJson["currency"]?.jsonPrimitive?.contentOrNull ?: currency,
      status = respJson["status"]?.jsonPrimitive?.contentOrNull ?: "created",
    )
  }

  override suspend fun createRefund(
    gatewayPaymentId: String,
    amountPaise: Long,
    idempotencyKey: String,
    notes: Map<String, String>,
  ): GatewayRefundResult {
    val mergedNotes = notes + ("idempotency_key" to idempotencyKey)
    val body = buildJsonObject {
      put("amount", amountPaise)
      putJsonObject("notes") {
        mergedNotes.forEach { (k, v) -> put(k, v) }
      }
    }.toString()

    val respJson = executeRequest(
      method = "POST",
      path = "/payments/$gatewayPaymentId/refund",
      body = body,
      extraHeaders = mapOf("X-Razorpay-Idempotency-Key" to idempotencyKey),
    ).jsonObject

    return parseRefundObject(respJson, gatewayPaymentId, amountPaise)
  }

  override suspend fun fetchPaymentsForOrder(gatewayOrderId: String): List<GatewayPaymentResult> {
    val respJson = executeRequest(
      method = "GET",
      path = "/orders/$gatewayOrderId/payments",
      body = null,
      extraHeaders = emptyMap(),
    ).jsonObject

    val items: JsonArray = respJson["items"]?.jsonArray ?: JsonArray(emptyList())
    return items.mapNotNull { element ->
      val obj = element.jsonObject
      val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      GatewayPaymentResult(
        gatewayPaymentId = id,
        gatewayOrderId = obj["order_id"]?.jsonPrimitive?.contentOrNull ?: gatewayOrderId,
        amountPaise = obj["amount"]?.jsonPrimitive?.longOrNull ?: 0L,
        currency = obj["currency"]?.jsonPrimitive?.contentOrNull ?: "INR",
        status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "unknown",
        errorCode = obj["error_code"]?.jsonPrimitive?.contentOrNull,
        errorDescription = obj["error_description"]?.jsonPrimitive?.contentOrNull,
      )
    }
  }

  override suspend fun fetchRefundsForPayment(gatewayPaymentId: String): List<GatewayRefundResult> {
    val respJson = executeRequest(
      method = "GET",
      path = "/payments/$gatewayPaymentId/refunds",
      body = null,
      extraHeaders = emptyMap(),
    ).jsonObject

    val items: JsonArray = respJson["items"]?.jsonArray ?: JsonArray(emptyList())
    return items.map { element ->
      parseRefundObject(element.jsonObject, gatewayPaymentId, 0L)
    }
  }

  private fun parseRefundObject(
    obj: JsonObject,
    fallbackPaymentId: String,
    fallbackAmountPaise: Long,
  ): GatewayRefundResult {
    val notesObj = obj["notes"]?.let { runCatching { it.jsonObject }.getOrNull() }
    val notesMap = notesObj?.entries?.mapNotNull { (k, v) ->
      v.jsonPrimitive.contentOrNull?.let { k to it }
    }?.toMap().orEmpty()

    return GatewayRefundResult(
      gatewayRefundId = obj["id"]?.jsonPrimitive?.contentOrNull
        ?: throw RazorpayGatewayException(true, 502, "MALFORMED_GATEWAY_RESPONSE", "Razorpay refund response missing id."),
      gatewayPaymentId = obj["payment_id"]?.jsonPrimitive?.contentOrNull ?: fallbackPaymentId,
      amountPaise = obj["amount"]?.jsonPrimitive?.longOrNull ?: fallbackAmountPaise,
      status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "processed",
      notes = notesMap,
    )
  }

  private suspend fun executeRequest(
    method: String,
    path: String,
    body: String?,
    extraHeaders: Map<String, String>,
  ): JsonObject {
    val builder = HttpRequest.newBuilder()
      .uri(URI.create("${config.baseUrl.trimEnd('/')}$path"))
      .timeout(Duration.ofSeconds(15))
      .header("Authorization", authHeader)
      .header("Accept", "application/json")
    extraHeaders.forEach { (k, v) -> builder.header(k, v) }

    if (method == "POST") {
      builder.header("Content-Type", "application/json")
      builder.POST(HttpRequest.BodyPublishers.ofString(body ?: "{}", StandardCharsets.UTF_8))
    } else {
      builder.GET()
    }

    val response = try {
      suspendCancellableCoroutine<HttpResponse<String>> { cont ->
        val future = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        cont.invokeOnCancellation { future.cancel(true) }
        future.whenComplete { resp, err ->
          if (err != null) {
            cont.resumeWithException(err)
          } else {
            cont.resume(resp)
          }
        }
      }
    } catch (io: IOException) {
      throw RazorpayGatewayException(
        isTransient = true,
        statusCode = null,
        code = "RAZORPAY_NETWORK_ERROR",
        message = "Razorpay network call failed: ${io.message}",
        cause = io,
      )
    } catch (t: Throwable) {
      if (t is kotlinx.coroutines.CancellationException) throw t
      throw RazorpayGatewayException(
        isTransient = true,
        statusCode = null,
        code = "RAZORPAY_CALL_ERROR",
        message = "Razorpay request failed: ${t.message}",
        cause = t,
      )
    }

    val status = response.statusCode()
    val rawText = response.body().orEmpty()
    if (status !in 200..299) {
      val isTransient = status == 429 || status >= 500
      val errorDesc = runCatching {
        json.parseToJsonElement(rawText).jsonObject["error"]?.jsonObject?.get("description")?.jsonPrimitive?.contentOrNull
      }.getOrNull() ?: rawText.take(250)
      throw RazorpayGatewayException(
        isTransient = isTransient,
        statusCode = status,
        code = "RAZORPAY_HTTP_$status",
        message = "Razorpay API returned HTTP $status: $errorDesc",
      )
    }

    return runCatching { json.parseToJsonElement(rawText).jsonObject }.getOrElse { ex ->
      throw RazorpayGatewayException(
        isTransient = true,
        statusCode = status,
        code = "RAZORPAY_INVALID_JSON",
        message = "Invalid JSON returned by Razorpay API",
        cause = ex,
      )
    }
  }
}

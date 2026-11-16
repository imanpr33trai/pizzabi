package com.example.orders

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.razorpay.Checkout
import org.json.JSONObject

data class RazorpayCheckoutRequest(
  val orderId: String,
  val orderNumber: String,
  val gatewayOrderId: String,
  val razorpayKeyId: String,
  val amountPaise: Long,
  val currency: String = "INR",
  val customerName: String? = null,
  val customerEmail: String? = null,
  val customerPhone: String? = null,
)

object RazorpayCheckoutPayloadBuilder {
  fun buildOptionsJson(request: RazorpayCheckoutRequest): JSONObject {
    require(request.razorpayKeyId.isNotBlank()) { "razorpayKeyId must not be blank" }
    require(request.gatewayOrderId.isNotBlank()) { "gatewayOrderId must not be blank" }
    require(request.amountPaise > 0L) { "amountPaise must be > 0" }

    return JSONObject().apply {
      put("name", "PIZZORA")
      put("description", "Order ${request.orderNumber}")
      put("order_id", request.gatewayOrderId)
      put("currency", request.currency)
      put("amount", request.amountPaise)
      put(
        "retry",
        JSONObject().apply {
          // Each retry on Pizzora creates a tracked server-side attempt & canonical receipt.
          put("enabled", false)
        },
      )
      val hasPrefill =
        !request.customerName.isNullOrBlank() ||
          !request.customerEmail.isNullOrBlank() ||
          !request.customerPhone.isNullOrBlank()
      if (hasPrefill) {
        put(
          "prefill",
          JSONObject().apply {
            request.customerName?.takeIf { it.isNotBlank() }?.let { put("name", it) }
            request.customerEmail?.takeIf { it.isNotBlank() }?.let { put("email", it) }
            request.customerPhone?.takeIf { it.isNotBlank() }?.let { put("contact", it) }
          },
        )
      }
      put(
        "notes",
        JSONObject().apply {
          put("order_id", request.orderId)
          put("order_number", request.orderNumber)
        },
      )
      put(
        "theme",
        JSONObject().apply {
          put("color", "#B7472A")
        },
      )
    }
  }
}

fun interface RazorpayCheckoutLauncher {
  fun launch(activity: Activity, request: RazorpayCheckoutRequest)
}

class AndroidRazorpayCheckoutLauncher : RazorpayCheckoutLauncher {
  override fun launch(activity: Activity, request: RazorpayCheckoutRequest) {
    runCatching { Checkout.preload(activity.applicationContext) }
    val checkout = Checkout()
    checkout.setKeyID(request.razorpayKeyId)
    val options = RazorpayCheckoutPayloadBuilder.buildOptionsJson(request)
    checkout.open(activity, options)
  }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

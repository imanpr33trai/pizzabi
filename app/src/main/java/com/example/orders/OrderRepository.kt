package com.example.orders

import com.example.core.network.PizzoraApiClient
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.CancelOrderRequest
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.FailPaymentAttemptRequest
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderPaymentStatusResponse
import com.pizzora.contracts.RetryPaymentRequest
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
import com.pizzora.contracts.VerifyRazorpayPaymentResponse
import kotlin.coroutines.cancellation.CancellationException

interface OrderRepository {
  suspend fun getAddresses(): Result<List<AddressDto>>
  suspend fun createAddress(request: UpsertAddressRequest): Result<AddressDto>
  suspend fun placeOrder(idempotencyKey: String, request: CheckoutRequest): Result<CheckoutResponse>
  suspend fun fetchOrders(): Result<OrderListResponse>
  suspend fun fetchOrderDetail(orderId: String): Result<OrderDetailDto>
  suspend fun cancelOrder(orderId: String, reason: String? = null): Result<OrderDetailDto>
  suspend fun fetchOrderPaymentStatus(orderId: String): Result<OrderPaymentStatusResponse>
  suspend fun retryOrderPayment(
    orderId: String,
    idempotencyKey: String,
    reason: String? = null,
  ): Result<OrderPaymentStatusResponse>
  suspend fun reportPaymentFailure(
    orderId: String,
    request: FailPaymentAttemptRequest,
  ): Result<OrderPaymentStatusResponse>
  suspend fun verifyRazorpayPayment(
    request: VerifyRazorpayPaymentRequest,
  ): Result<VerifyRazorpayPaymentResponse>
}

class NetworkOrderRepository(
  private val apiClient: PizzoraApiClient,
) : OrderRepository {

  override suspend fun getAddresses(): Result<List<AddressDto>> = runSafe {
    apiClient.getAddresses()
  }

  override suspend fun createAddress(request: UpsertAddressRequest): Result<AddressDto> = runSafe {
    apiClient.createAddress(request)
  }

  override suspend fun placeOrder(idempotencyKey: String, request: CheckoutRequest): Result<CheckoutResponse> = runSafe {
    apiClient.checkout(idempotencyKey, request)
  }

  override suspend fun fetchOrders(): Result<OrderListResponse> = runSafe {
    apiClient.getOrders()
  }

  override suspend fun fetchOrderDetail(orderId: String): Result<OrderDetailDto> = runSafe {
    apiClient.getOrderDetail(orderId)
  }

  override suspend fun cancelOrder(orderId: String, reason: String?): Result<OrderDetailDto> = runSafe {
    apiClient.cancelOrder(orderId, CancelOrderRequest(reason))
  }

  override suspend fun fetchOrderPaymentStatus(orderId: String): Result<OrderPaymentStatusResponse> = runSafe {
    apiClient.getOrderPaymentStatus(orderId)
  }

  override suspend fun retryOrderPayment(
    orderId: String,
    idempotencyKey: String,
    reason: String?,
  ): Result<OrderPaymentStatusResponse> = runSafe {
    apiClient.retryOrderPayment(orderId, idempotencyKey, RetryPaymentRequest(reason))
  }

  override suspend fun reportPaymentFailure(
    orderId: String,
    request: FailPaymentAttemptRequest,
  ): Result<OrderPaymentStatusResponse> = runSafe {
    apiClient.reportPaymentFailure(orderId, request)
  }

  override suspend fun verifyRazorpayPayment(
    request: VerifyRazorpayPaymentRequest,
  ): Result<VerifyRazorpayPaymentResponse> = runSafe {
    apiClient.verifyRazorpayPayment(request)
  }

  private inline fun <T> runSafe(block: () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      Result.failure(t)
    }
}

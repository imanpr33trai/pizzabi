package com.example.orders

import com.example.core.network.PizzoraApiClient
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.CancelOrderRequest
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.UpsertAddressRequest
import kotlin.coroutines.cancellation.CancellationException

interface OrderRepository {
  suspend fun getAddresses(): Result<List<AddressDto>>
  suspend fun createAddress(request: UpsertAddressRequest): Result<AddressDto>
  suspend fun placeOrder(idempotencyKey: String, request: CheckoutRequest): Result<CheckoutResponse>
  suspend fun fetchOrders(): Result<OrderListResponse>
  suspend fun fetchOrderDetail(orderId: String): Result<OrderDetailDto>
  suspend fun cancelOrder(orderId: String, reason: String? = null): Result<OrderDetailDto>
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

  private inline fun <T> runSafe(block: () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      Result.failure(t)
    }
}

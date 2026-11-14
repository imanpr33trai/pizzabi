package com.example.kds

import com.example.core.network.PizzoraApiClient
import com.pizzora.contracts.KdsTicketActionResponse
import com.pizzora.contracts.KdsTicketsResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.UpdateOrderPriorityRequest
import kotlin.coroutines.cancellation.CancellationException

interface KdsRepository {
  suspend fun fetchTickets(includeCompleted: Boolean = false): Result<KdsTicketsResponse>
  suspend fun transitionOrder(orderId: String, targetStatus: OrderStatus, reason: String? = null): Result<KdsTicketActionResponse>
  suspend fun updatePriority(orderId: String, isPriority: Boolean, reason: String? = null): Result<KdsTicketActionResponse>
}

class NetworkKdsRepository(
  private val apiClient: PizzoraApiClient,
) : KdsRepository {

  override suspend fun fetchTickets(includeCompleted: Boolean): Result<KdsTicketsResponse> = runSafe {
    apiClient.getKdsTickets(includeCompleted = includeCompleted)
  }

  override suspend fun transitionOrder(
    orderId: String,
    targetStatus: OrderStatus,
    reason: String?,
  ): Result<KdsTicketActionResponse> = runSafe {
    when (targetStatus) {
      OrderStatus.ACCEPTED -> apiClient.ackKdsOrder(orderId, reason)
      OrderStatus.PREPARING -> apiClient.startPrepKdsOrder(orderId, reason)
      OrderStatus.READY -> apiClient.markReadyKdsOrder(orderId, reason)
      OrderStatus.OUT_FOR_DELIVERY -> apiClient.dispatchKdsOrder(orderId, reason)
      OrderStatus.COMPLETED -> apiClient.completeKdsOrder(orderId, reason)
      else -> error("Unsupported KDS target transition status: $targetStatus")
    }
  }

  override suspend fun updatePriority(
    orderId: String,
    isPriority: Boolean,
    reason: String?,
  ): Result<KdsTicketActionResponse> = runSafe {
    apiClient.updateKdsOrderPriority(
      orderId = orderId,
      request = UpdateOrderPriorityRequest(isPriority = isPriority, reason = reason),
    )
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

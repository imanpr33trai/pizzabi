package com.example.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderSummaryDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrdersUiState(
  val orders: List<OrderSummaryDto> = emptyList(),
  val isLoadingList: Boolean = false,
  val listError: String? = null,
  val selectedOrder: OrderDetailDto? = null,
  val isLoadingDetail: Boolean = false,
  val detailError: String? = null,
  val isCancelling: Boolean = false,
  val actionMessage: String? = null,
)

class OrdersViewModel(
  private val orderRepository: OrderRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(OrdersUiState())
  val uiState: StateFlow<OrdersUiState> = _uiState.asStateFlow()

  fun loadOrders() {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingList = true, listError = null) }
      orderRepository.fetchOrders()
        .onSuccess { resp ->
          _uiState.update { it.copy(orders = resp.orders, isLoadingList = false, listError = null) }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isLoadingList = false,
              listError = err.message ?: "Unable to load order history.",
            )
          }
        }
    }
  }

  fun openOrderDetail(orderId: String) {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingDetail = true, detailError = null) }
      orderRepository.fetchOrderDetail(orderId)
        .onSuccess { detail ->
          _uiState.update { it.copy(selectedOrder = detail, isLoadingDetail = false, detailError = null) }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isLoadingDetail = false,
              detailError = err.message ?: "Unable to load order details.",
            )
          }
        }
    }
  }

  fun showCreatedOrder(detail: OrderDetailDto) {
    _uiState.update { it.copy(selectedOrder = detail, isLoadingDetail = false, detailError = null) }
    loadOrders()
  }

  fun clearSelectedOrder() {
    _uiState.update { it.copy(selectedOrder = null, detailError = null, actionMessage = null) }
  }

  fun cancelSelectedOrder(reason: String?) {
    val current = _uiState.value.selectedOrder ?: return
    if (_uiState.value.isCancelling) return

    viewModelScope.launch {
      _uiState.update { it.copy(isCancelling = true, detailError = null, actionMessage = null) }
      orderRepository.cancelOrder(current.id, reason?.takeIf { it.isNotBlank() })
        .onSuccess { updated ->
          _uiState.update { state ->
            state.copy(
              selectedOrder = updated,
              isCancelling = false,
              actionMessage = "Order ${updated.orderNumber} cancelled.",
              orders = state.orders.map { summary ->
                if (summary.id == updated.id) {
                  summary.copy(
                    status = updated.status,
                    version = updated.version,
                    paymentStatus = updated.payment.status,
                  )
                } else {
                  summary
                }
              },
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isCancelling = false,
              detailError = err.message ?: "Unable to cancel order right now.",
            )
          }
        }
    }
  }

  fun clearActionMessage() {
    _uiState.update { it.copy(actionMessage = null) }
  }
}

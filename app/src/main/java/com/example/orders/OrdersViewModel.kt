package com.example.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.domain.DomainBridge
import com.example.core.domain.DomainCausalTracker
import com.example.core.domain.RustDomainBridge
import com.example.core.network.RealtimeEventStream
import com.example.ui.components.OrderDisplayFormatters
import com.pizzora.contracts.CausalDecision
import com.pizzora.contracts.FailPaymentAttemptRequest
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderPaymentStatusResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderStatusHistoryDto
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.RealtimeConnectionState
import com.pizzora.contracts.RealtimeEventDto
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
import com.pizzora.contracts.WsEventsFrame
import com.razorpay.Checkout
import com.razorpay.PaymentData
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PendingRazorpayLaunch(
  val orderId: String,
  val gatewayOrderId: String,
)

enum class OrderListFilter { ACTIVE, PAST, ALL }

private val TERMINAL_ORDER_STATUSES = setOf(
  OrderStatus.COMPLETED,
  OrderStatus.CANCELLED,
  OrderStatus.EXPIRED,
)

/** Pure classifier: terminal orders belong to Past, everything else to Active. */
fun isPastOrder(status: OrderStatus): Boolean = status in TERMINAL_ORDER_STATUSES

/** Pure list filter used by the history screen and unit tests. */
fun filterOrders(
  orders: List<OrderSummaryDto>,
  filter: OrderListFilter,
): List<OrderSummaryDto> = when (filter) {
  OrderListFilter.ACTIVE -> orders.filterNot { isPastOrder(it.status) }
  OrderListFilter.PAST -> orders.filter { isPastOrder(it.status) }
  OrderListFilter.ALL -> orders
}

data class OrdersUiState(
  val orders: List<OrderSummaryDto> = emptyList(),
  val orderListFilter: OrderListFilter = OrderListFilter.ACTIVE,
  val celebrationOrderId: String? = null,
  val isLoadingList: Boolean = false,
  val listError: String? = null,
  val selectedOrder: OrderDetailDto? = null,
  val isLoadingDetail: Boolean = false,
  val detailError: String? = null,
  val isCancelling: Boolean = false,
  val actionMessage: String? = null,
  val paymentStatus: OrderPaymentStatusResponse? = null,
  val isPollingGatewayOrder: Boolean = false,
  val isRetryingPayment: Boolean = false,
  val isVerifyingPayment: Boolean = false,
  val isReportingFailure: Boolean = false,
  val isPaymentSheetOpen: Boolean = false,
  val paymentError: String? = null,
  val pendingRazorpayLaunch: PendingRazorpayLaunch? = null,
  val connectionState: RealtimeConnectionState = RealtimeConnectionState.DISCONNECTED,
  val lastHighWaterSeq: Long = 0L,
  val resyncCount: Int = 0,
)

class OrdersViewModel(
  private val orderRepository: OrderRepository,
  private val initialPollDelayMs: Long = 250L,
  private val maxPollAttempts: Int = 20,
  private val realtimeEventStream: RealtimeEventStream? = null,
  private val domainBridge: DomainBridge = RustDomainBridge,
) : ViewModel() {

  private val _uiState = MutableStateFlow(OrdersUiState())
  val uiState: StateFlow<OrdersUiState> = _uiState.asStateFlow()

  private val causalTracker: DomainCausalTracker = domainBridge.createCausalTracker()
  private val activeResyncOrderIds = mutableSetOf<String>()
  private var pollJob: Job? = null

  init {
    realtimeEventStream?.let { stream ->
      viewModelScope.launch {
        stream.connectionState.collect { connState ->
          _uiState.update { it.copy(connectionState = connState) }
        }
      }
      viewModelScope.launch {
        stream.frames.collect { frame ->
          processIncomingFrame(frame)
        }
      }
    }
  }

  fun setOrderListFilter(filter: OrderListFilter) {
    _uiState.update { state ->
      if (state.orderListFilter == filter) state else state.copy(orderListFilter = filter)
    }
  }

  fun startLiveTracking(userId: String) {
    realtimeEventStream?.start(userId, causalTracker.lastSeenStreamSeq)
  }

  fun stopLiveTracking() {
    realtimeEventStream?.stop()
  }

  fun loadOrders() {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingList = true, listError = null) }
      orderRepository.fetchOrders()
        .onSuccess { resp ->
          resp.orders.forEach { causalTracker.recordSnapshot(it.id, it.version) }
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
    pollJob?.cancel()
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isLoadingDetail = true,
          detailError = null,
          paymentError = null,
          celebrationOrderId = null,
        )
      }
      orderRepository.fetchOrderDetail(orderId)
        .onSuccess { detail ->
          causalTracker.recordSnapshot(detail.id, detail.version)
          _uiState.update {
            it.copy(
              selectedOrder = detail,
              isLoadingDetail = false,
              detailError = null,
            )
          }
          if (detail.payment.method == PaymentMethod.RAZORPAY) {
            refreshPaymentStatusAndPollIfNeeded(detail.id, openSheetWhenReady = false)
          } else {
            _uiState.update { it.copy(paymentStatus = null, isPollingGatewayOrder = false) }
          }
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

  fun showCreatedOrder(
    detail: OrderDetailDto,
    openPaymentFlowIfPending: Boolean = detail.status == OrderStatus.PENDING_PAYMENT && detail.payment.method == PaymentMethod.RAZORPAY,
  ) {
    pollJob?.cancel()
    causalTracker.recordSnapshot(detail.id, detail.version)
    _uiState.update {
      it.copy(
        selectedOrder = detail,
        isLoadingDetail = false,
        detailError = null,
        paymentError = null,
        paymentStatus = null,
        isPaymentSheetOpen = openPaymentFlowIfPending,
        celebrationOrderId = detail.id,
      )
    }
    loadOrders()
    if (detail.payment.method == PaymentMethod.RAZORPAY) {
      refreshPaymentStatusAndPollIfNeeded(detail.id, openSheetWhenReady = openPaymentFlowIfPending)
    }
  }

  fun openPaymentSheet() {
    val selected = _uiState.value.selectedOrder ?: return
    _uiState.update { it.copy(isPaymentSheetOpen = true, paymentError = null) }
    refreshPaymentStatusAndPollIfNeeded(selected.id, openSheetWhenReady = true)
  }

  fun dismissPaymentSheet() {
    _uiState.update { it.copy(isPaymentSheetOpen = false, paymentError = null) }
  }

  fun retryPayment(reason: String? = null) {
    val selected = _uiState.value.selectedOrder ?: return
    if (_uiState.value.isRetryingPayment) return

    pollJob?.cancel()
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isRetryingPayment = true,
          paymentError = null,
          detailError = null,
          actionMessage = null,
        )
      }
      orderRepository.retryOrderPayment(selected.id, UUID.randomUUID().toString(), reason)
        .onSuccess { statusResp ->
          _uiState.update { state ->
            state.copy(
              isRetryingPayment = false,
              paymentStatus = statusResp,
              selectedOrder = state.selectedOrder?.copy(
                status = statusResp.orderStatus,
                payment = statusResp.payment,
              ),
              isPaymentSheetOpen = true,
              actionMessage = statusResp.payment.latestAttempt?.status?.let {
                OrderDisplayFormatters.formatPaymentAttemptStatus(it)
              } ?: "Your secure checkout is ready.",
            )
          }
          if (statusResp.payment.latestAttempt?.status == PaymentAttemptStatus.INITIATING) {
            refreshPaymentStatusAndPollIfNeeded(selected.id, openSheetWhenReady = true)
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isRetryingPayment = false,
              paymentError = err.message ?: "Unable to start a new payment attempt.",
            )
          }
        }
    }
  }

  fun markRazorpayLaunchStarted(orderId: String, gatewayOrderId: String) {
    _uiState.update {
      it.copy(
        pendingRazorpayLaunch = PendingRazorpayLaunch(orderId = orderId, gatewayOrderId = gatewayOrderId),
        paymentError = null,
      )
    }
  }

  fun restorePendingRazorpayLaunch(orderId: String?, gatewayOrderId: String?) {
    if (orderId.isNullOrBlank() || gatewayOrderId.isNullOrBlank()) return
    _uiState.update { state ->
      if (state.pendingRazorpayLaunch == null) {
        state.copy(pendingRazorpayLaunch = PendingRazorpayLaunch(orderId = orderId, gatewayOrderId = gatewayOrderId))
      } else {
        state
      }
    }
  }

  fun onRazorpaySdkSuccess(
    razorpayPaymentId: String?,
    paymentData: PaymentData?,
  ) {
    val current = _uiState.value
    val targetOrderId = current.selectedOrder?.id ?: current.pendingRazorpayLaunch?.orderId
    if (targetOrderId.isNullOrBlank()) {
      _uiState.update {
        it.copy(
          pendingRazorpayLaunch = null,
          paymentError = "Unable to match Razorpay payment to an active order.",
        )
      }
      return
    }
    val resolvedGatewayOrderId = paymentData?.orderId?.takeIf { it.isNotBlank() }
      ?: current.pendingRazorpayLaunch?.gatewayOrderId
      ?: current.paymentStatus?.payment?.latestAttempt?.gatewayOrderId
      ?: current.selectedOrder?.payment?.latestAttempt?.gatewayOrderId
    val resolvedPaymentId = paymentData?.paymentId?.takeIf { it.isNotBlank() }
      ?: razorpayPaymentId?.trim().orEmpty()
    val resolvedSignature = paymentData?.signature?.trim().orEmpty()

    verifyRazorpayPaymentInternal(
      orderId = targetOrderId,
      gatewayOrderId = resolvedGatewayOrderId,
      gatewayPaymentId = resolvedPaymentId,
      signature = resolvedSignature,
    )
  }

  fun onRazorpaySdkError(
    code: Int,
    description: String?,
    paymentData: PaymentData?,
  ) {
    val current = _uiState.value
    val targetOrderId = current.selectedOrder?.id ?: current.pendingRazorpayLaunch?.orderId
    val attempt = current.paymentStatus?.payment?.latestAttempt ?: current.selectedOrder?.payment?.latestAttempt
    val targetGatewayOrderId = paymentData?.orderId?.takeIf { it.isNotBlank() }
      ?: current.pendingRazorpayLaunch?.gatewayOrderId
      ?: attempt?.gatewayOrderId

    if (code == Checkout.PAYMENT_CANCELED) {
      _uiState.update {
        it.copy(
          pendingRazorpayLaunch = null,
          isPaymentSheetOpen = false,
          paymentError = null,
          actionMessage = "Payment cancelled. You can complete payment or retry.",
        )
      }
      return
    }

    val cleanDescription = description?.trim()?.takeIf { it.isNotEmpty() }
      ?: "Payment wasn't completed. You can try again safely."
    if (targetOrderId.isNullOrBlank()) {
      _uiState.update {
        it.copy(
          pendingRazorpayLaunch = null,
          isPaymentSheetOpen = false,
          paymentError = cleanDescription,
        )
      }
      return
    }

    reportPaymentFailureInternal(
      orderId = targetOrderId,
      attemptId = attempt?.id,
      gatewayOrderId = targetGatewayOrderId,
      failureCode = "RAZORPAY_SDK_$code",
      failureReason = cleanDescription,
    )
  }

  fun reportPaymentFailure(
    failureCode: String = "PAYMENT_CANCELLED",
    failureReason: String = "Customer cancelled or bank declined payment",
  ) {
    val selected = _uiState.value.selectedOrder ?: return
    val attempt = _uiState.value.paymentStatus?.payment?.latestAttempt ?: selected.payment.latestAttempt
    reportPaymentFailureInternal(
      orderId = selected.id,
      attemptId = attempt?.id,
      gatewayOrderId = attempt?.gatewayOrderId,
      failureCode = failureCode,
      failureReason = failureReason,
    )
  }

  private fun reportPaymentFailureInternal(
    orderId: String,
    attemptId: String?,
    gatewayOrderId: String?,
    failureCode: String,
    failureReason: String,
  ) {
    if (_uiState.value.isReportingFailure) return

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isReportingFailure = true,
          pendingRazorpayLaunch = null,
          paymentError = null,
          actionMessage = null,
        )
      }
      orderRepository.reportPaymentFailure(
        orderId = orderId,
        request = FailPaymentAttemptRequest(
          attemptId = attemptId,
          gatewayOrderId = gatewayOrderId,
          failureCode = failureCode,
          failureReason = failureReason,
        ),
      ).onSuccess { statusResp ->
        _uiState.update { state ->
          state.copy(
            isReportingFailure = false,
            isPaymentSheetOpen = false,
            pendingRazorpayLaunch = null,
            paymentStatus = statusResp,
            selectedOrder = state.selectedOrder?.takeIf { it.id == orderId }?.copy(
              status = statusResp.orderStatus,
              payment = statusResp.payment,
            ),
            paymentError = failureReason,
            actionMessage = "Payment wasn't completed. You can retry while your order is still active.",
          )
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            isReportingFailure = false,
            pendingRazorpayLaunch = null,
            paymentError = err.message ?: "Unable to record payment failure.",
          )
        }
      }
    }
  }

  fun verifyRazorpayPayment(
    gatewayPaymentId: String,
    signature: String,
  ) {
    val selected = _uiState.value.selectedOrder ?: return
    val statusResp = _uiState.value.paymentStatus
    val gatewayOrderId = statusResp?.payment?.latestAttempt?.gatewayOrderId
      ?: selected.payment.latestAttempt?.gatewayOrderId
    verifyRazorpayPaymentInternal(
      orderId = selected.id,
      gatewayOrderId = gatewayOrderId,
      gatewayPaymentId = gatewayPaymentId,
      signature = signature,
    )
  }

  private fun verifyRazorpayPaymentInternal(
    orderId: String,
    gatewayOrderId: String?,
    gatewayPaymentId: String,
    signature: String,
  ) {
    if (gatewayOrderId.isNullOrBlank()) {
      _uiState.update {
        it.copy(
          pendingRazorpayLaunch = null,
          paymentError = "Gateway order is still preparing. Please wait a moment.",
        )
      }
      return
    }
    val cleanPaymentId = gatewayPaymentId.trim()
    val cleanSig = signature.trim()
    if (cleanPaymentId.isEmpty() || cleanSig.isEmpty()) {
      _uiState.update {
        it.copy(
          pendingRazorpayLaunch = null,
          paymentError = "Razorpay payment ID and signature are required.",
        )
      }
      return
    }
    if (_uiState.value.isVerifyingPayment) return

    pollJob?.cancel()
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isVerifyingPayment = true,
          pendingRazorpayLaunch = null,
          paymentError = null,
          detailError = null,
        )
      }
      orderRepository.verifyRazorpayPayment(
        VerifyRazorpayPaymentRequest(
          orderId = orderId,
          razorpayOrderId = gatewayOrderId,
          razorpayPaymentId = cleanPaymentId,
          razorpaySignature = cleanSig,
        ),
      ).onSuccess { resp ->
        causalTracker.recordSnapshot(resp.order.id, resp.order.version)
        _uiState.update { state ->
          state.copy(
            isVerifyingPayment = false,
            isPollingGatewayOrder = false,
            isPaymentSheetOpen = false,
            pendingRazorpayLaunch = null,
            selectedOrder = resp.order,
            paymentError = null,
            actionMessage = when {
              resp.lateCaptureRefundTriggered ->
                "Your payment window closed before confirmation; a refund has been queued."
              else ->
                "Payment confirmed! Order ${resp.order.orderNumber} is with the kitchen."
            },
            orders = state.orders.map { summary ->
              if (summary.id == resp.order.id) {
                summary.copy(
                  status = resp.order.status,
                  version = resp.order.version,
                  paymentStatus = resp.order.payment.status,
                )
              } else {
                summary
              }
            },
          )
        }
        orderRepository.fetchOrderPaymentStatus(resp.order.id).onSuccess { latestPayment ->
          _uiState.update { it.copy(paymentStatus = latestPayment) }
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            isVerifyingPayment = false,
            pendingRazorpayLaunch = null,
            paymentError = err.message ?: "Payment verification failed.",
          )
        }
      }
    }
  }

  private fun refreshPaymentStatusAndPollIfNeeded(
    orderId: String,
    openSheetWhenReady: Boolean,
  ) {
    pollJob?.cancel()
    pollJob = viewModelScope.launch {
      var delayMs = initialPollDelayMs
      for (attempt in 0 until maxPollAttempts) {
        _uiState.update { it.copy(isPollingGatewayOrder = true) }
        val result = orderRepository.fetchOrderPaymentStatus(orderId)
        val statusResp = result.getOrNull()
        if (statusResp == null) {
          _uiState.update {
            it.copy(
              isPollingGatewayOrder = false,
              paymentError = result.exceptionOrNull()?.message ?: "Unable to fetch payment status.",
            )
          }
          return@launch
        }

        val latest = statusResp.payment.latestAttempt
        _uiState.update { state ->
          val readyForSheet = openSheetWhenReady &&
            statusResp.orderStatus == OrderStatus.PENDING_PAYMENT &&
            latest?.status == PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT &&
            !latest.gatewayOrderId.isNullOrBlank()
          state.copy(
            paymentStatus = statusResp,
            selectedOrder = state.selectedOrder?.takeIf { it.id == orderId }?.copy(
              status = statusResp.orderStatus,
              payment = statusResp.payment,
              expiresAt = statusResp.expiresAt,
            ),
            isPaymentSheetOpen = state.isPaymentSheetOpen || readyForSheet,
          )
        }

        val shouldContinuePolling =
          statusResp.orderStatus == OrderStatus.PENDING_PAYMENT &&
            latest?.status == PaymentAttemptStatus.INITIATING

        if (!shouldContinuePolling) {
          _uiState.update { it.copy(isPollingGatewayOrder = false) }
          return@launch
        }

        delay(delayMs)
        delayMs = (delayMs * 2L).coerceAtMost(2_000L)
      }
      _uiState.update { it.copy(isPollingGatewayOrder = false) }
    }
  }

  fun clearSelectedOrder() {
    pollJob?.cancel()
    _uiState.update {
      it.copy(
        selectedOrder = null,
        detailError = null,
        actionMessage = null,
        celebrationOrderId = null,
        paymentStatus = null,
        isPollingGatewayOrder = false,
        isPaymentSheetOpen = false,
        paymentError = null,
        pendingRazorpayLaunch = null,
      )
    }
  }

  fun dismissOrderPlacedCelebration() {
    _uiState.update { it.copy(celebrationOrderId = null) }
  }

  fun cancelSelectedOrder(reason: String?) {
    val current = _uiState.value.selectedOrder ?: return
    if (_uiState.value.isCancelling) return

    pollJob?.cancel()
    viewModelScope.launch {
      _uiState.update { it.copy(isCancelling = true, detailError = null, actionMessage = null) }
      orderRepository.cancelOrder(current.id, reason?.takeIf { it.isNotBlank() })
        .onSuccess { updated ->
          causalTracker.recordSnapshot(updated.id, updated.version)
          _uiState.update { state ->
            state.copy(
              selectedOrder = updated,
              isCancelling = false,
              isPaymentSheetOpen = false,
              isPollingGatewayOrder = false,
              actionMessage = "Order ${updated.orderNumber} has been cancelled.",
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
          orderRepository.fetchOrderPaymentStatus(updated.id).onSuccess { latestPayment ->
            _uiState.update { it.copy(paymentStatus = latestPayment) }
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

  fun processIncomingFrame(frame: WsEventsFrame) {
    causalTracker.advanceCursor(frame.serverHighWaterSeq)
    realtimeEventStream?.updateHighWaterSeq(causalTracker.lastSeenStreamSeq)
    _uiState.update { it.copy(lastHighWaterSeq = causalTracker.lastSeenStreamSeq) }
    for (event in frame.events) {
      handleRealtimeEvent(event)
    }
  }

  private fun handleRealtimeEvent(event: RealtimeEventDto) {
    when (val decision = causalTracker.evaluate(event)) {
      CausalDecision.IgnoreDuplicate,
      CausalDecision.IgnoreStale,
      is CausalDecision.ApplyNonOrderEvent -> Unit

      is CausalDecision.TriggerResync -> {
        triggerOrderSnapshotResync(decision.orderId)
      }

      is CausalDecision.ApplyOrderEvent -> {
        applyOrderRealtimeEvent(decision.event)
        _uiState.update { it.copy(lastHighWaterSeq = causalTracker.lastSeenStreamSeq) }
      }
    }
  }

  private fun applyOrderRealtimeEvent(event: RealtimeEventDto) {
    val orderId = event.stringField("order_id") ?: event.aggregateId
    when (event.eventType) {
      "payment.gateway_ready" -> {
        val gwOrderId = event.stringField("gateway_order_id") ?: return
        val attemptId = event.stringField("payment_attempt_id")
        _uiState.update { state ->
          val updatedDetail = state.selectedOrder?.takeIf { it.id == orderId }?.let { detail ->
            val currentAttempt = detail.payment.latestAttempt
            val patchedAttempt = if (currentAttempt != null && (attemptId == null || currentAttempt.id == attemptId)) {
              currentAttempt.copy(
                status = PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT,
                gatewayOrderId = gwOrderId,
              )
            } else {
              currentAttempt
            }
            detail.copy(payment = detail.payment.copy(latestAttempt = patchedAttempt))
          }
          val updatedPayStatus = state.paymentStatus?.takeIf { it.orderId == orderId }?.let { payResp ->
            val currentAttempt = payResp.payment.latestAttempt
            val patchedAttempt = if (currentAttempt != null && (attemptId == null || currentAttempt.id == attemptId)) {
              currentAttempt.copy(
                status = PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT,
                gatewayOrderId = gwOrderId,
              )
            } else {
              currentAttempt
            }
            payResp.copy(payment = payResp.payment.copy(latestAttempt = patchedAttempt))
          }
          state.copy(
            selectedOrder = updatedDetail ?: state.selectedOrder,
            paymentStatus = updatedPayStatus ?: state.paymentStatus,
            isPollingGatewayOrder = if (state.selectedOrder?.id == orderId) false else state.isPollingGatewayOrder,
          )
        }
      }

      "order.created",
      "order.status_changed",
      "order.cancelled",
      "order.expired",
      "kitchen.acknowledged" -> {
        val statusRaw = event.stringField("to_status") ?: event.stringField("status")
        val nextStatus = statusRaw?.let { runCatching { OrderStatus.valueOf(it) }.getOrNull() }
        val reason = event.stringField("reason")
        val acceptedAt = event.stringField("accepted_at")
        val prepStartedAt = event.stringField("prep_started_at")
        val readyAt = event.stringField("ready_at")
        val completedAt = event.stringField("completed_at")

        var foundInSummaries = false
        _uiState.update { state ->
          val updatedSummaries = state.orders.map { summary ->
            if (summary.id == orderId) {
              foundInSummaries = true
              summary.copy(
                status = nextStatus ?: summary.status,
                version = event.aggregateVersion,
              )
            } else {
              summary
            }
          }

          val currentSelected = state.selectedOrder
          val updatedSelected = if (currentSelected != null && currentSelected.id == orderId) {
            val resolvedStatus = nextStatus ?: currentSelected.status
            val shouldAppendHistory = nextStatus != null &&
              currentSelected.status != resolvedStatus &&
              currentSelected.statusHistory.none { it.toStatus == resolvedStatus && it.fromStatus == currentSelected.status }
            val nextHistory = if (shouldAppendHistory) {
              currentSelected.statusHistory + OrderStatusHistoryDto(
                id = event.eventId,
                fromStatus = currentSelected.status,
                toStatus = resolvedStatus,
                changedBy = event.stringField("changed_by"),
                reason = reason,
                createdAt = event.createdAt,
              )
            } else {
              currentSelected.statusHistory
            }
            currentSelected.copy(
              status = resolvedStatus,
              version = event.aggregateVersion,
              acceptedAt = acceptedAt ?: currentSelected.acceptedAt,
              prepStartedAt = prepStartedAt ?: currentSelected.prepStartedAt,
              readyAt = readyAt ?: currentSelected.readyAt,
              completedAt = completedAt ?: currentSelected.completedAt,
              statusHistory = nextHistory,
            )
          } else {
            currentSelected
          }

          val statusActionMsg = if (updatedSelected != null && updatedSelected.id == orderId && nextStatus != null) {
            "Order ${updatedSelected.orderNumber}: ${OrderDisplayFormatters.formatOrderStatus(nextStatus, updatedSelected.fulfillmentType)}."
          } else {
            state.actionMessage
          }

          state.copy(
            orders = updatedSummaries,
            selectedOrder = updatedSelected,
            actionMessage = statusActionMsg,
            isPaymentSheetOpen = if (updatedSelected?.id == orderId && updatedSelected.status != OrderStatus.PENDING_PAYMENT) {
              false
            } else {
              state.isPaymentSheetOpen
            },
          )
        }

        if (!foundInSummaries) {
          loadOrders()
        }
      }
    }
  }

  private fun triggerOrderSnapshotResync(orderId: String) {
    synchronized(activeResyncOrderIds) {
      if (!activeResyncOrderIds.add(orderId)) return
    }
    viewModelScope.launch {
      try {
        orderRepository.fetchOrderDetail(orderId).onSuccess { freshDetail ->
          causalTracker.recordSnapshot(freshDetail.id, freshDetail.version)
          _uiState.update { state ->
            val updatedOrders = if (state.orders.any { it.id == freshDetail.id }) {
              state.orders.map { summary ->
                if (summary.id == freshDetail.id) {
                  summary.copy(
                    status = freshDetail.status,
                    version = freshDetail.version,
                    paymentStatus = freshDetail.payment.status,
                  )
                } else {
                  summary
                }
              }
            } else {
              state.orders
            }
            state.copy(
              orders = updatedOrders,
              selectedOrder = if (state.selectedOrder?.id == freshDetail.id) freshDetail else state.selectedOrder,
              resyncCount = state.resyncCount + 1,
              lastHighWaterSeq = causalTracker.lastSeenStreamSeq,
            )
          }
        }
      } finally {
        synchronized(activeResyncOrderIds) {
          activeResyncOrderIds.remove(orderId)
        }
      }
    }
  }

  fun clearActionMessage() {
    _uiState.update { it.copy(actionMessage = null) }
  }

  fun clearSessionState() {
    pollJob?.cancel()
    pollJob = null
    synchronized(activeResyncOrderIds) {
      activeResyncOrderIds.clear()
    }
    causalTracker.clear()
    realtimeEventStream?.resetForSessionChange()
    _uiState.value = OrdersUiState()
  }

  override fun onCleared() {
    pollJob?.cancel()
    realtimeEventStream?.stop()
    super.onCleared()
  }
}

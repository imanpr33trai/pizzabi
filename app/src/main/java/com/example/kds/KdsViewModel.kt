package com.example.kds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.domain.DomainBridge
import com.example.core.domain.DomainCausalTracker
import com.example.core.domain.RustDomainBridge
import com.example.core.network.RealtimeEventStream
import com.example.core.security.TokenStorage
import com.pizzora.contracts.CausalDecision
import com.pizzora.contracts.KdsTicketDto
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.RealtimeConnectionState
import com.pizzora.contracts.RealtimeEventDto
import com.pizzora.contracts.WsEventsFrame
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class KdsFilterTab(val status: OrderStatus?) {
  ALL(null),
  NEW(OrderStatus.PLACED),
  ACCEPTED(OrderStatus.ACCEPTED),
  BAKING(OrderStatus.PREPARING),
  READY(OrderStatus.READY),
  DISPATCH(OrderStatus.OUT_FOR_DELIVERY),
}

data class KdsUiState(
  val tickets: List<KdsTicketDto> = emptyList(),
  val selectedFilter: KdsFilterTab = KdsFilterTab.ALL,
  val includeCompleted: Boolean = false,
  val isLoading: Boolean = false,
  val isMuted: Boolean = false,
  val errorMessage: String? = null,
  val actionMessage: String? = null,
  val inFlightTicketIds: Set<String> = emptySet(),
  val prepSlaMinutes: Int = 20,
  val serverClockOffsetMs: Long = 0L,
  val currentDeviceEpochMs: Long = System.currentTimeMillis(),
  val connectionState: RealtimeConnectionState = RealtimeConnectionState.DISCONNECTED,
  val lastHighWaterSeq: Long = 0L,
  val resyncCount: Int = 0,
  val chimeCount: Int = 0,
) {
  val serverAdjustedNowMs: Long get() = currentDeviceEpochMs + serverClockOffsetMs

  val activeTicketsCount: Int
    get() = tickets.count { !it.status.isTerminal }

  val priorityCount: Int
    get() = tickets.count { it.isPriority && !it.status.isTerminal }

  val visibleTickets: List<KdsTicketDto>
    get() {
      val base = if (includeCompleted) {
        tickets
      } else {
        tickets.filter { !it.status.isTerminal }
      }
      val filtered = selectedFilter.status?.let { target ->
        base.filter { it.status == target }
      } ?: base
      return sortKdsTickets(filtered)
    }

  fun countFor(tab: KdsFilterTab): Int {
    val active = tickets.filter { !it.status.isTerminal }
    return tab.status?.let { s -> active.count { it.status == s } } ?: active.size
  }
}

class KdsViewModel(
  private val kdsRepository: KdsRepository,
  private val tokenStorage: TokenStorage,
  private val chimePlayer: KdsChimePlayer = ToneGeneratorKdsChimePlayer(),
  private val realtimeEventStream: RealtimeEventStream? = null,
  private val clockNowMs: () -> Long = System::currentTimeMillis,
  private val tickerIntervalMs: Long = 1_000L,
  private val enableTicker: Boolean = true,
  private val domainBridge: DomainBridge = RustDomainBridge,
) : ViewModel() {

  private val _uiState = MutableStateFlow(
    KdsUiState(currentDeviceEpochMs = clockNowMs()),
  )
  val uiState: StateFlow<KdsUiState> = _uiState.asStateFlow()

  private val causalTracker: DomainCausalTracker = domainBridge.createCausalTracker()
  private var tickerJob: Job? = null
  private var isBoardStarted: Boolean = false

  init {
    viewModelScope.launch {
      tokenStorage.observeKdsSoundMuted().collect { muted ->
        _uiState.update { it.copy(isMuted = muted) }
      }
    }

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

  fun startBoard(userId: String) {
    if (!isBoardStarted) {
      isBoardStarted = true
      refreshTickets(silent = false)
    }
    realtimeEventStream?.start(userId, causalTracker.lastSeenStreamSeq)
    if (enableTicker && tickerJob?.isActive != true) {
      tickerJob = viewModelScope.launch {
        while (isActive) {
          _uiState.update { it.copy(currentDeviceEpochMs = clockNowMs()) }
          delay(tickerIntervalMs)
        }
      }
    }
  }

  fun stopBoard() {
    isBoardStarted = false
    tickerJob?.cancel()
    tickerJob = null
    realtimeEventStream?.stop()
  }

  fun refreshTickets(silent: Boolean = false) {
    val includeCompleted = _uiState.value.includeCompleted
    viewModelScope.launch {
      if (!silent) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
      }
      kdsRepository.fetchTickets(includeCompleted = includeCompleted)
        .onSuccess { resp ->
          resp.tickets.forEach { causalTracker.recordSnapshot(it.id, it.version) }
          val offset = computeServerOffsetMs(resp.serverNow, clockNowMs())
          _uiState.update { state ->
            state.copy(
              tickets = sortKdsTickets(resp.tickets),
              isLoading = false,
              errorMessage = null,
              prepSlaMinutes = resp.prepSlaMinutes,
              serverClockOffsetMs = offset ?: state.serverClockOffsetMs,
              currentDeviceEpochMs = clockNowMs(),
            )
          }
        }
        .onFailure { err ->
          if (!silent) {
            _uiState.update {
              it.copy(
                isLoading = false,
                errorMessage = err.message ?: "Unable to load kitchen ticket queue.",
              )
            }
          }
        }
    }
  }

  fun setFilterTab(tab: KdsFilterTab) {
    _uiState.update { it.copy(selectedFilter = tab) }
  }

  fun toggleIncludeCompleted() {
    _uiState.update { it.copy(includeCompleted = !it.includeCompleted) }
    refreshTickets(silent = false)
  }

  fun toggleMute() {
    val nextMuted = !_uiState.value.isMuted
    _uiState.update { it.copy(isMuted = nextMuted) }
    viewModelScope.launch {
      tokenStorage.setKdsSoundMuted(nextMuted)
    }
  }

  fun advanceTicketStage(ticket: KdsTicketDto) {
    val nextStatus = domainBridge.nextKdsStatus(ticket.status, ticket.fulfillmentType) ?: return
    transitionTicket(ticket.id, nextStatus)
  }

  fun transitionTicket(orderId: String, targetStatus: OrderStatus, reason: String? = null) {
    if (orderId in _uiState.value.inFlightTicketIds) return
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          inFlightTicketIds = it.inFlightTicketIds + orderId,
          errorMessage = null,
          actionMessage = null,
        )
      }
      kdsRepository.transitionOrder(orderId, targetStatus, reason)
        .onSuccess { resp ->
          causalTracker.recordSnapshot(resp.ticket.id, resp.ticket.version)
          val offset = computeServerOffsetMs(resp.serverNow, clockNowMs())
          _uiState.update { state ->
            val merged = upsertOrPruneTicket(state.tickets, resp.ticket, state.includeCompleted)
            state.copy(
              tickets = sortKdsTickets(merged),
              inFlightTicketIds = state.inFlightTicketIds - orderId,
              prepSlaMinutes = resp.prepSlaMinutes,
              serverClockOffsetMs = offset ?: state.serverClockOffsetMs,
              currentDeviceEpochMs = clockNowMs(),
              actionMessage = "Order ${resp.ticket.orderNumber} → ${resp.ticket.status.name.replace('_', ' ')}",
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              inFlightTicketIds = it.inFlightTicketIds - orderId,
              errorMessage = err.message ?: "Unable to update order stage.",
            )
          }
        }
    }
  }

  fun togglePriority(ticket: KdsTicketDto) {
    if (ticket.id in _uiState.value.inFlightTicketIds) return
    val targetPriority = !ticket.isPriority
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          inFlightTicketIds = it.inFlightTicketIds + ticket.id,
          errorMessage = null,
          actionMessage = null,
        )
      }
      kdsRepository.updatePriority(ticket.id, targetPriority)
        .onSuccess { resp ->
          causalTracker.recordSnapshot(resp.ticket.id, resp.ticket.version)
          val offset = computeServerOffsetMs(resp.serverNow, clockNowMs())
          _uiState.update { state ->
            val merged = upsertOrPruneTicket(state.tickets, resp.ticket, state.includeCompleted)
            state.copy(
              tickets = sortKdsTickets(merged),
              inFlightTicketIds = state.inFlightTicketIds - ticket.id,
              prepSlaMinutes = resp.prepSlaMinutes,
              serverClockOffsetMs = offset ?: state.serverClockOffsetMs,
              currentDeviceEpochMs = clockNowMs(),
              actionMessage = if (resp.ticket.isPriority) {
                "Expedite priority enabled for ${resp.ticket.orderNumber}"
              } else {
                "Priority cleared for ${resp.ticket.orderNumber}"
              },
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              inFlightTicketIds = it.inFlightTicketIds - ticket.id,
              errorMessage = err.message ?: "Unable to update ticket priority.",
            )
          }
        }
    }
  }

  fun processIncomingFrame(frame: WsEventsFrame) {
    causalTracker.advanceCursor(frame.serverHighWaterSeq)
    realtimeEventStream?.updateHighWaterSeq(causalTracker.lastSeenStreamSeq)
    val offset = computeServerOffsetMs(frame.serverNow, clockNowMs())
    _uiState.update { state ->
      state.copy(
        lastHighWaterSeq = causalTracker.lastSeenStreamSeq,
        serverClockOffsetMs = offset ?: state.serverClockOffsetMs,
        currentDeviceEpochMs = clockNowMs(),
      )
    }
    var needsSnapshotResync = false
    var shouldPlayChime = false

    for (event in frame.events) {
      when (val decision = causalTracker.evaluate(event)) {
        CausalDecision.IgnoreDuplicate,
        CausalDecision.IgnoreStale,
        is CausalDecision.ApplyNonOrderEvent -> Unit

        is CausalDecision.TriggerResync -> {
          needsSnapshotResync = true
          if (isNewTicketArrivalEvent(event)) {
            shouldPlayChime = true
          }
        }

        is CausalDecision.ApplyOrderEvent -> {
          val handledLocally = applyOrderEventLocally(decision.event)
          if (isNewTicketArrivalEvent(decision.event)) {
            shouldPlayChime = true
          }
          if (!handledLocally) {
            needsSnapshotResync = true
          }
        }
      }
    }

    if (shouldPlayChime && !_uiState.value.isMuted) {
      try {
        chimePlayer.playNewTicketChime()
        _uiState.update { it.copy(chimeCount = it.chimeCount + 1) }
      } catch (ce: CancellationException) {
        throw ce
      } catch (_: Throwable) {
        // Ignore audio hardware failures
      }
    }

    if (needsSnapshotResync) {
      _uiState.update { it.copy(resyncCount = it.resyncCount + 1) }
      refreshTickets(silent = true)
    }
  }

  private fun isNewTicketArrivalEvent(event: RealtimeEventDto): Boolean {
    if (event.eventType == "order.created") return true
    if (event.eventType == "order.status_changed") {
      val toStatus = event.stringField("to_status") ?: event.stringField("status")
      return toStatus == OrderStatus.PLACED.name
    }
    return false
  }

  /**
   * Returns true if the event was fully applied to an existing ticket without needing a full reload,
   * or false if a new ticket arrived that isn't yet in our local ticket list (requiring snapshot fetch for items).
   */
  private fun applyOrderEventLocally(event: RealtimeEventDto): Boolean {
    val orderId = event.stringField("order_id") ?: event.aggregateId
    val existing = _uiState.value.tickets.firstOrNull { it.id == orderId }
    if (existing == null) {
      // Newly created/placed order (or not in active board): fetch snapshot so full pizza items/toppings render.
      val toStatus = event.stringField("to_status") ?: event.stringField("status")
      val shouldFetch = event.eventType == "order.created" ||
        toStatus == OrderStatus.PLACED.name ||
        toStatus == OrderStatus.ACCEPTED.name ||
        toStatus == OrderStatus.PREPARING.name ||
        toStatus == OrderStatus.READY.name
      return !shouldFetch
    }

    when (event.eventType) {
      "order.priority_changed" -> {
        val nextPriority = event.payload["is_priority"]?.toString()?.toBooleanStrictOrNull() ?: existing.isPriority
        val patched = existing.copy(
          isPriority = nextPriority,
          version = event.aggregateVersion,
        )
        _uiState.update { state ->
          state.copy(
            tickets = sortKdsTickets(upsertOrPruneTicket(state.tickets, patched, state.includeCompleted)),
            lastHighWaterSeq = causalTracker.lastSeenStreamSeq,
          )
        }
      }

      "order.status_changed",
      "order.cancelled",
      "order.expired",
      "kitchen.acknowledged" -> {
        val statusRaw = event.stringField("to_status") ?: event.stringField("status")
        val nextStatus = statusRaw?.let { runCatching { OrderStatus.valueOf(it) }.getOrNull() } ?: existing.status
        val nextPriority = event.payload["is_priority"]?.toString()?.toBooleanStrictOrNull() ?: existing.isPriority
        val patched = existing.copy(
          status = nextStatus,
          version = event.aggregateVersion,
          isPriority = nextPriority,
          acceptedAt = event.stringField("accepted_at") ?: existing.acceptedAt,
          prepStartedAt = event.stringField("prep_started_at") ?: existing.prepStartedAt,
          readyAt = event.stringField("ready_at") ?: existing.readyAt,
          completedAt = event.stringField("completed_at") ?: existing.completedAt,
        )
        _uiState.update { state ->
          state.copy(
            tickets = sortKdsTickets(upsertOrPruneTicket(state.tickets, patched, state.includeCompleted)),
            lastHighWaterSeq = causalTracker.lastSeenStreamSeq,
          )
        }
      }
    }
    return true
  }

  fun clearMessages() {
    _uiState.update { it.copy(errorMessage = null, actionMessage = null) }
  }

  fun clearSessionState() {
    isBoardStarted = false
    tickerJob?.cancel()
    tickerJob = null
    causalTracker.clear()
    realtimeEventStream?.resetForSessionChange()
    _uiState.value = KdsUiState(
      isMuted = _uiState.value.isMuted,
      currentDeviceEpochMs = clockNowMs(),
    )
  }

  override fun onCleared() {
    stopBoard()
    super.onCleared()
  }
}

internal fun upsertOrPruneTicket(
  current: List<KdsTicketDto>,
  updated: KdsTicketDto,
  includeCompleted: Boolean,
): List<KdsTicketDto> {
  val without = current.filterNot { it.id == updated.id }
  val shouldKeep = when {
    updated.status == OrderStatus.CANCELLED ||
      updated.status == OrderStatus.EXPIRED ||
      updated.status == OrderStatus.PENDING_PAYMENT -> false
    updated.status == OrderStatus.COMPLETED -> includeCompleted
    else -> true
  }
  return if (shouldKeep) without + updated else without
}

internal fun sortKdsTickets(tickets: List<KdsTicketDto>): List<KdsTicketDto> =
  tickets.sortedWith(
    compareBy<KdsTicketDto>(
      { if (it.status == OrderStatus.COMPLETED) 1 else 0 },
      { if (it.isPriority) 0 else 1 },
      { it.createdAt },
      { it.id },
    ),
  )

internal fun computeServerOffsetMs(serverNowIso: String?, deviceNowMs: Long): Long? {
  val parsed = parseIso8601UtcMillis(serverNowIso) ?: return null
  return parsed - deviceNowMs
}

internal fun formatElapsedClock(elapsedMs: Long): String {
  val clampedSec = (elapsedMs.coerceAtLeast(0L)) / 1_000L
  val hours = clampedSec / 3600L
  val mins = (clampedSec % 3600L) / 60L
  val secs = clampedSec % 60L
  return if (hours > 0L) {
    String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
  } else {
    String.format(Locale.US, "%02d:%02d", mins, secs)
  }
}

internal fun parseIso8601UtcMillis(iso: String?): Long? {
  val raw = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  // Supports e.g. 2026-11-13T10:15:30Z or 2026-11-13T10:15:30.123456Z
  val regex = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?Z$""")
  val match = regex.matchEntire(raw) ?: return null
  val year = match.groupValues[1].toInt()
  val month = match.groupValues[2].toInt()
  val day = match.groupValues[3].toInt()
  val hour = match.groupValues[4].toInt()
  val minute = match.groupValues[5].toInt()
  val second = match.groupValues[6].toInt()
  val fracStr = match.groupValues[7]
  val millis = if (fracStr.isEmpty()) 0 else fracStr.padEnd(3, '0').take(3).toInt()
  val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
    clear()
    set(year, month - 1, day, hour, minute, second)
    set(Calendar.MILLISECOND, millis)
  }
  return cal.timeInMillis
}

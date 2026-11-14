package com.example

import android.app.Activity
import android.os.Looper
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.core.network.PizzoraApiClient
import com.example.core.network.RealtimeEventStream
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.kds.KdsChimePlayer
import com.example.kds.KdsScreen
import com.example.kds.KdsViewModel
import com.example.kds.NetworkKdsRepository
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.KdsTicketActionResponse
import com.pizzora.contracts.KdsTicketDto
import com.pizzora.contracts.KdsTicketItemDto
import com.pizzora.contracts.KdsTicketsResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.RealtimeConnectionState
import com.pizzora.contracts.RealtimeEventDto
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.UpdateOrderPriorityRequest
import com.pizzora.contracts.WsEventsFrame
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase13KdsRobolectricTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private class InMemoryTokenStorage(initialMuted: Boolean = false) : TokenStorage {
    private val _session = MutableStateFlow<AuthSession?>(null)
    private val _muted = MutableStateFlow(initialMuted)
    override val sessionFlow: StateFlow<AuthSession?> = _session.asStateFlow()
    override fun currentSession(): AuthSession? = _session.value
    override suspend fun saveSession(session: AuthSession) {
      _session.value = session
    }
    override suspend fun clearSession() {
      _session.value = null
    }
    override fun observeKdsSoundMuted(): Flow<Boolean> = _muted
    override suspend fun setKdsSoundMuted(muted: Boolean) {
      _muted.value = muted
    }
  }

  private class FakeRealtimeEventStream : RealtimeEventStream {
    private val _connectionState = MutableStateFlow(RealtimeConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<RealtimeConnectionState> = _connectionState.asStateFlow()

    private val _frames = MutableSharedFlow<WsEventsFrame>(extraBufferCapacity = 32)
    override val frames: SharedFlow<WsEventsFrame> = _frames.asSharedFlow()

    private val _lastHighWaterSeq = MutableStateFlow(0L)
    override val lastHighWaterSeq: StateFlow<Long> = _lastHighWaterSeq.asStateFlow()

    override fun start(sessionUserId: String, initialSinceSeq: Long) {
      _connectionState.value = RealtimeConnectionState.CONNECTED
    }

    override fun stop() {
      _connectionState.value = RealtimeConnectionState.DISCONNECTED
    }

    override fun updateHighWaterSeq(seq: Long) {
      if (seq > _lastHighWaterSeq.value) {
        _lastHighWaterSeq.value = seq
      }
    }

    fun emitFrame(frame: WsEventsFrame) {
      _frames.tryEmit(frame)
    }
  }

  private class RecordingChimePlayer : KdsChimePlayer {
    var count: Int = 0
    override fun playNewTicketChime() {
      count += 1
    }
  }

  private fun sampleTicket(
    id: String,
    orderNumber: String,
    status: OrderStatus = OrderStatus.PLACED,
    version: Long = 1L,
    isPriority: Boolean = false,
    fulfillmentType: FulfillmentType = FulfillmentType.DELIVERY,
    createdAt: String = "2026-11-13T18:00:00Z",
    paymentStatus: PaymentStatus = PaymentStatus.COD_PENDING,
  ): KdsTicketDto = KdsTicketDto(
    id = id,
    orderNumber = orderNumber,
    customerName = "Rohan Sharma",
    customerPhone = "9811112222",
    fulfillmentType = fulfillmentType,
    scheduleType = ScheduleType.ASAP,
    status = status,
    version = version,
    isPriority = isPriority,
    paymentMethod = PaymentMethod.COD,
    paymentStatus = paymentStatus,
    totalPaise = 64_900L,
    deliveryArea = "12 Indiranagar • 560038",
    createdAt = createdAt,
    items = listOf(
      KdsTicketItemDto(
        id = "item-$id",
        productId = "prod-1",
        productName = "Diavola Woodfire",
        variantSize = "LARGE",
        crustName = "Sourdough Neapolitan",
        toppings = listOf("Burrata", "Basil"),
        quantity = 2,
      ),
    ),
  )

  @Test
  fun `kds board renders dark theme with server-skew SLA overdue badge, stage transitions, priority toggle, and keep-screen-on`() {
    val ticket1 = sampleTicket(
      id = "t-overdue",
      orderNumber = "PZ-20261113-1301",
      status = OrderStatus.PLACED,
      createdAt = "2026-11-13T18:00:00Z",
    )
    val ticket2 = sampleTicket(
      id = "t-fresh",
      orderNumber = "PZ-20261113-1302",
      status = OrderStatus.PREPARING,
      createdAt = "2026-11-13T18:20:00Z",
    )
    var ticketsState = mutableListOf(ticket1, ticket2)

    // Device clock is artificially 40 minutes behind server_now; server_now proves ticket1 is 25m old (> 20m SLA)
    val deviceClockNowMs = 1_794_592_800_000L // arbitrary base; server_now offset handles true elapsed!
    val serverNowIso = "2026-11-13T18:25:00Z"

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      when {
        path == "/api/v1/kds/tickets" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(
            KdsTicketsResponse(
              tickets = ticketsState.toList(),
              serverNow = serverNowIso,
              prepSlaMinutes = 20,
              priorityCount = ticketsState.count { it.isPriority },
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/kds/orders/t-overdue/ack" && request.method == HttpMethod.Post -> {
          val updated = ticketsState.first { it.id == "t-overdue" }.copy(
            status = OrderStatus.ACCEPTED,
            version = 2L,
            acceptedAt = serverNowIso,
          )
          ticketsState = ticketsState.map { if (it.id == updated.id) updated else it }.toMutableList()
          respond(
            content = json.encodeToString(KdsTicketActionResponse(updated, serverNowIso, 20)),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/kds/orders/t-fresh/priority" && request.method == HttpMethod.Patch -> {
          val bodyText = (request.body as TextContent).text
          val req = json.decodeFromString<UpdateOrderPriorityRequest>(bodyText)
          val updated = ticketsState.first { it.id == "t-fresh" }.copy(
            isPriority = req.isPriority,
            version = 2L,
          )
          ticketsState = ticketsState.map { if (it.id == updated.id) updated else it }.toMutableList()
          respond(
            content = json.encodeToString(KdsTicketActionResponse(updated, serverNowIso, 20)),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected API call: ${request.method.value} $path")
      }
    }

    val storage = InMemoryTokenStorage()
    val chimePlayer = RecordingChimePlayer()
    val fakeStream = FakeRealtimeEventStream()
    val apiClient = PizzoraApiClient("http://localhost:8080", storage, engine)
    val vm = KdsViewModel(
      kdsRepository = NetworkKdsRepository(apiClient),
      tokenStorage = storage,
      chimePlayer = chimePlayer,
      realtimeEventStream = fakeStream,
      clockNowMs = { deviceClockNowMs },
      enableTicker = false,
    )

    vm.startBoard("chef-1")
    composeTestRule.setContent {
      val uiState by vm.uiState.collectAsState()
      KdsScreen(
        uiState = uiState,
        operatorName = "Chef Matteo",
        onSelectFilter = vm::setFilterTab,
        onToggleIncludeCompleted = vm::toggleIncludeCompleted,
        onToggleMute = vm::toggleMute,
        onRefresh = { vm.refreshTickets(silent = false) },
        onAdvanceStage = vm::advanceTicketStage,
        onTogglePriority = vm::togglePriority,
      )
    }

    composeTestRule.waitUntil(timeoutMillis = 3_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("kds_ticket_card_t-overdue").fetchSemanticsNodes().isNotEmpty()
    }

    // Play-policy active-screen keep-awake flag is enabled while KdsScreen is composed
    val windowFlags = composeTestRule.activity.window.attributes.flags
    assertTrue((windowFlags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)

    // Verify SLA clock adjusted by server_now: t-overdue = 25:00 (OVERDUE), t-fresh = 05:00 (not overdue)
    composeTestRule.onNodeWithTag("kds_ticket_timer_t-overdue").assertTextContains("25:00")
    composeTestRule.onNodeWithTag("kds_overdue_badge_t-overdue").assertIsDisplayed()
    composeTestRule.onNodeWithTag("kds_ticket_timer_t-fresh").assertTextContains("05:00")
    assertTrue(composeTestRule.onAllNodesWithTag("kds_overdue_badge_t-fresh").fetchSemanticsNodes().isEmpty())

    // Click Acknowledge on t-overdue -> button advances to Start Prep
    composeTestRule.onNodeWithTag("kds_primary_action_t-overdue").assertTextContains("Acknowledge")
    composeTestRule.onNodeWithTag("kds_primary_action_t-overdue").performClick()

    composeTestRule.waitUntil(timeoutMillis = 3_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      vm.uiState.value.tickets.first { it.id == "t-overdue" }.status == OrderStatus.ACCEPTED
    }
    composeTestRule.onNodeWithTag("kds_primary_action_t-overdue").assertTextContains("Start Prep")

    // Toggle priority on t-fresh -> becomes priority and moves first in sorted queue
    composeTestRule.onNodeWithTag("kds_priority_button_t-fresh").performClick()
    composeTestRule.waitUntil(timeoutMillis = 3_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      vm.uiState.value.priorityCount == 1
    }
    composeTestRule.onNodeWithTag("kds_priority_badge_t-fresh").assertIsDisplayed()
    assertEquals("t-fresh", vm.uiState.value.visibleTickets.first().id)

    vm.stopBoard()
  }

  @Test
  fun `kds board chimes on live WebSocket order arrival and respects persistent mute toggle`() {
    val fetchCount = AtomicInteger(0)
    val initialTickets = mutableListOf<KdsTicketDto>()

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      when {
        path == "/api/v1/kds/tickets" && request.method == HttpMethod.Get -> {
          val callNum = fetchCount.incrementAndGet()
          if (callNum >= 2 && initialTickets.none { it.id == "t-live-1" }) {
            initialTickets += sampleTicket(
              id = "t-live-1",
              orderNumber = "PZ-20261113-1399",
              status = OrderStatus.PLACED,
              createdAt = "2026-11-13T18:24:30Z",
            )
          }
          respond(
            content = json.encodeToString(
              KdsTicketsResponse(
                tickets = initialTickets.toList(),
                serverNow = "2026-11-13T18:25:00Z",
                prepSlaMinutes = 20,
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected API call: ${request.method.value} $path")
      }
    }

    val storage = InMemoryTokenStorage(initialMuted = false)
    val chimePlayer = RecordingChimePlayer()
    val fakeStream = FakeRealtimeEventStream()
    val apiClient = PizzoraApiClient("http://localhost:8080", storage, engine)
    val vm = KdsViewModel(
      kdsRepository = NetworkKdsRepository(apiClient),
      tokenStorage = storage,
      chimePlayer = chimePlayer,
      realtimeEventStream = fakeStream,
      enableTicker = false,
    )

    vm.startBoard("chef-2")
    composeTestRule.setContent {
      val uiState by vm.uiState.collectAsState()
      KdsScreen(
        uiState = uiState,
        operatorName = "Chef Matteo",
        onSelectFilter = vm::setFilterTab,
        onToggleIncludeCompleted = vm::toggleIncludeCompleted,
        onToggleMute = vm::toggleMute,
        onRefresh = { vm.refreshTickets(silent = false) },
        onAdvanceStage = vm::advanceTicketStage,
        onTogglePriority = vm::togglePriority,
      )
    }

    composeTestRule.waitUntil(timeoutMillis = 3_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      fetchCount.get() >= 1
    }
    composeTestRule.onNodeWithTag("kds_empty_state").assertIsDisplayed()

    // Live order.created WS frame arrives -> plays chime once and resyncs ticket card
    fakeStream.emitFrame(
      WsEventsFrame(
        type = "EVENTS",
        events = listOf(
          RealtimeEventDto(
            eventId = "evt-live-1",
            streamSeq = 10L,
            eventType = "order.created",
            aggregateType = "ORDER",
            aggregateId = "t-live-1",
            aggregateVersion = 1L,
            payload = JsonObject(
              mapOf(
                "order_id" to JsonPrimitive("t-live-1"),
                "order_number" to JsonPrimitive("PZ-20261113-1399"),
                "status" to JsonPrimitive("PLACED"),
              ),
            ),
            sequencedAt = "2026-11-13T18:24:30Z",
            createdAt = "2026-11-13T18:24:30Z",
          ),
        ),
        serverHighWaterSeq = 10L,
        serverNow = "2026-11-13T18:25:00Z",
      ),
    )

    composeTestRule.waitUntil(timeoutMillis = 3_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("kds_ticket_card_t-live-1").fetchSemanticsNodes().isNotEmpty()
    }
    assertEquals(1, chimePlayer.count)
    assertEquals(1, vm.uiState.value.chimeCount)

    // Tap Mute button -> mutes future chimes and persists in TokenStorage
    composeTestRule.onNodeWithTag("kds_mute_button").performClick()
    composeTestRule.waitUntil(timeoutMillis = 3_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      vm.uiState.value.isMuted
    }
    assertTrue(vm.uiState.value.isMuted)

    // Another live order.created arrives while muted -> chimeCount stays 1
    fakeStream.emitFrame(
      WsEventsFrame(
        type = "EVENTS",
        events = listOf(
          RealtimeEventDto(
            eventId = "evt-live-2",
            streamSeq = 11L,
            eventType = "order.created",
            aggregateType = "ORDER",
            aggregateId = "t-live-2",
            aggregateVersion = 1L,
            payload = JsonObject(
              mapOf(
                "order_id" to JsonPrimitive("t-live-2"),
                "order_number" to JsonPrimitive("PZ-20261113-1400"),
                "status" to JsonPrimitive("PLACED"),
              ),
            ),
            sequencedAt = "2026-11-13T18:25:05Z",
            createdAt = "2026-11-13T18:25:05Z",
          ),
        ),
        serverHighWaterSeq = 11L,
        serverNow = "2026-11-13T18:25:05Z",
      ),
    )
    composeTestRule.waitUntil(timeoutMillis = 3_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      vm.uiState.value.lastHighWaterSeq == 11L
    }
    assertEquals(1, chimePlayer.count)
    assertFalse(chimePlayer.count > 1)

    vm.stopBoard()
  }
}

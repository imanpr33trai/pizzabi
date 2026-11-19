package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.auth.NetworkAuthRepository
import com.example.cart.CartViewModel
import com.example.cart.NetworkCartRepository
import com.example.core.database.NotificationInboxEntity
import com.example.core.database.PizzoraDatabase
import com.example.core.network.PizzoraApiClient
import com.example.core.network.RealtimeEventStream
import com.example.core.security.AuthSession
import com.example.core.security.DefaultSessionScopeCleaner
import com.example.core.security.SessionTransitionCoordinator
import com.example.core.security.TokenStorage
import com.example.notifications.NetworkNotificationRepository
import com.pizzora.contracts.ApiErrorDetail
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.MarkAllNotificationsReadResponse
import com.pizzora.contracts.MarkNotificationReadResponse
import com.pizzora.contracts.NotificationDeliveryStatus
import com.pizzora.contracts.NotificationInboxItemDto
import com.pizzora.contracts.NotificationInboxResponse
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.RealtimeConnectionState
import com.pizzora.contracts.ReplaceCartRequest
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.WsEventsFrame
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase1SessionCartIsolationRobolectricTest {

  private lateinit var db: PizzoraDatabase
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private val productA = ProductDto(
    id = "11111111-1111-4111-8111-111111111111",
    categoryId = "cat-1",
    categoryName = "Classics",
    name = "User A Pizza",
    description = "Secret slice",
    isVeg = true,
    isActive = true,
    isTaxable = true,
    variants = listOf(
      ProductVariantDto("33333333-3333-4333-8333-333333333331", "11111111-1111-4111-8111-111111111111", SizeCode.REGULAR, 29_900L, true),
    ),
  )

  private val productB = ProductDto(
    id = "22222222-2222-4222-8222-222222222222",
    categoryId = "cat-1",
    categoryName = "Classics",
    name = "User B Truffle",
    description = "Authoritative server slice",
    isVeg = true,
    isActive = true,
    isTaxable = true,
    variants = listOf(
      ProductVariantDto("33333333-3333-4333-8333-333333333332", "22222222-2222-4222-8222-222222222222", SizeCode.MEDIUM, 49_900L, true),
    ),
  )

  private val classicCrust = CrustOptionDto("77777777-7777-4777-8777-777777777771", "Hand-Tossed", 0L, true)
  private val basilTopping = ToppingDto("66666666-6666-4666-8666-666666666661", "Extra Basil", true, 2_500L, true)

  private fun sessionFor(userId: String, email: String): AuthSession = AuthSession(
    accessToken = "access-$userId",
    refreshToken = "refresh-$userId",
    user = UserProfileDto(
      id = userId,
      email = email,
      emailVerified = true,
      fullName = "Customer $userId",
      phone = "9999999999",
      role = UserRole.CUSTOMER,
      isActive = true,
    ),
  )

  private class InMemoryTokenStorage(initial: AuthSession? = null) : TokenStorage {
    private val _session = MutableStateFlow(initial)
    private val _muted = MutableStateFlow(false)
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

  private class RecordingRealtimeStream : RealtimeEventStream {
    private val _connectionState = MutableStateFlow(RealtimeConnectionState.DISCONNECTED)
    private val _frames = MutableSharedFlow<WsEventsFrame>(extraBufferCapacity = 4)
    private val _highWater = MutableStateFlow(12L)
    override val connectionState: StateFlow<RealtimeConnectionState> = _connectionState.asStateFlow()
    override val frames: SharedFlow<WsEventsFrame> = _frames.asSharedFlow()
    override val lastHighWaterSeq: StateFlow<Long> = _highWater.asStateFlow()

    val startCount = AtomicInteger(0)
    val stopCount = AtomicInteger(0)
    val resetCount = AtomicInteger(0)

    override fun start(sessionUserId: String, initialSinceSeq: Long) {
      startCount.incrementAndGet()
      _connectionState.value = RealtimeConnectionState.CONNECTED
      if (initialSinceSeq > _highWater.value) {
        _highWater.value = initialSinceSeq
      }
    }

    override fun stop() {
      stopCount.incrementAndGet()
      _connectionState.value = RealtimeConnectionState.DISCONNECTED
    }

    override fun resetForSessionChange() {
      resetCount.incrementAndGet()
      _highWater.value = 0L
      stop()
    }

    override fun updateHighWaterSeq(seq: Long) {
      if (seq > _highWater.value) {
        _highWater.value = seq
      }
    }
  }

  private fun buildCoordinator(
    tokenStorage: TokenStorage,
    realtimeStream: RealtimeEventStream = RecordingRealtimeStream(),
  ): SessionTransitionCoordinator {
    val cleaner = DefaultSessionScopeCleaner(
      cartDao = db.cartDao(),
      notificationInboxDao = db.notificationInboxDao(),
      realtimeEventStreamProvider = { realtimeStream },
    )
    return SessionTransitionCoordinator(
      tokenStorage = tokenStorage,
      scopeCleanerProvider = { cleaner },
    )
  }

  private fun cartResponseForUserB(): CartResponseDto = CartResponseDto(
    cartId = "cart-b",
    items = listOf(
      CartItemViewDto(
        id = "b-item-1",
        productId = productB.id,
        productName = productB.name,
        isVeg = true,
        variantId = productB.variants.first().id,
        sizeCode = SizeCode.MEDIUM,
        basePricePaise = 49_900L,
        crustId = classicCrust.id,
        crustName = classicCrust.name,
        crustExtraPricePaise = 0L,
        toppings = emptyList(),
        quantity = 2,
        unitPricePaise = 49_900L,
        lineTotalPaise = 99_800L,
        isAvailable = true,
        issues = emptyList(),
      ),
    ),
    subtotalPaise = 99_800L,
    totalItemCount = 2,
    allAvailable = true,
    unavailableCount = 0,
    updatedAt = "2026-11-16T08:00:00Z",
  )

  private fun emptyCartResponse(cartId: String = "cart-empty"): CartResponseDto = CartResponseDto(
    cartId = cartId,
    items = emptyList(),
    subtotalPaise = 0L,
    totalItemCount = 0,
    allAvailable = true,
    unavailableCount = 0,
    updatedAt = "2026-11-16T08:00:00Z",
  )

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, PizzoraDatabase::class.java)
      .addCallback(PizzoraDatabase.FOREIGN_KEY_CALLBACK)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `user A adds offline item, logs out offline, user B logs in - Room cleared, GET cart hydrates B, zero PUT of A items`() = runBlocking {
    val userASession = sessionFor("user-a", "a@pizzora.test")
    val userBSession = sessionFor("user-b", "b@pizzora.test")
    val tokenStorage = InMemoryTokenStorage(userASession)
    val realtime = RecordingRealtimeStream()
    val coordinator = buildCoordinator(tokenStorage, realtime)

    val offline = AtomicBoolean(true)
    val recordedPuts = CopyOnWriteArrayList<Pair<String?, ReplaceCartRequest>>()
    val getCartTokens = CopyOnWriteArrayList<String?>()

    val engine = MockEngine { request ->
      val authHeader = request.headers[HttpHeaders.Authorization]
      val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
      when {
        request.url.encodedPath == "/api/v1/auth/logout" -> {
          if (offline.get()) {
            respond("Offline", HttpStatusCode.ServiceUnavailable, jsonHeaders)
          } else {
            respond("{\"message\":\"ok\"}", HttpStatusCode.OK, jsonHeaders)
          }
        }
        request.url.encodedPath == "/api/v1/auth/login" -> {
          respond(
            json.encodeToString(
              AuthTokensResponse(
                accessToken = userBSession.accessToken,
                refreshToken = userBSession.refreshToken,
                expiresInSeconds = 900L,
                user = userBSession.user,
              ),
            ),
            HttpStatusCode.OK,
            jsonHeaders,
          )
        }
        request.url.encodedPath == "/api/v1/cart" && request.method == HttpMethod.Put -> {
          val text = (request.body as? TextContent)?.text
            ?: (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
          val req = json.decodeFromString<ReplaceCartRequest>(text)
          recordedPuts.add(authHeader to req)
          respond("Offline", HttpStatusCode.ServiceUnavailable, jsonHeaders)
        }
        request.url.encodedPath == "/api/v1/cart" && request.method == HttpMethod.Get -> {
          getCartTokens.add(authHeader)
          assertEquals("Bearer ${userBSession.accessToken}", authHeader)
          respond(json.encodeToString(cartResponseForUserB()), HttpStatusCode.OK, jsonHeaders)
        }
        else -> error("Unexpected request: ${request.method.value} ${request.url.encodedPath}")
      }
    }

    lateinit var apiClient: PizzoraApiClient
    apiClient = PizzoraApiClient(
      baseUrl = "http://localhost:8080",
      tokenStorage = tokenStorage,
      engine = engine,
      sessionCoordinatorProvider = { coordinator },
    )
    val authRepo = NetworkAuthRepository(apiClient, tokenStorage, coordinator)
    val cartRepo = NetworkCartRepository(apiClient, db.cartDao(), tokenStorage, coordinator)

    // Step 1: User A adds item while offline (stored locally under user-a, PUT fails with 503)
    val addResult = cartRepo.addCustomizedItem(productA, productA.variants.first(), classicCrust, listOf(basilTopping), 1)
    assertTrue(addResult.isOffline)
    assertEquals(1, db.cartDao().observeCartItemsWithToppings("user-a").first().size)
    assertEquals(1, recordedPuts.size)
    assertEquals("Bearer ${userASession.accessToken}", recordedPuts.first().first)
    recordedPuts.clear()

    // Seed an inbox row for User A too
    db.notificationInboxDao().insertIgnore(
      listOf(
        NotificationInboxEntity(
          id = "n-a-1",
          userId = "user-a",
          eventId = "evt-a-1",
          title = "User A Order",
          body = "Baking",
          category = "ORDER_UPDATE",
          referenceId = "ord-a",
          isRead = false,
          createdAtIso = "2026-11-16T08:00:00Z",
        ),
      ),
    )

    // Step 2: User A logs out offline
    authRepo.logout()
    assertNull(tokenStorage.currentSession())
    assertEquals(0, db.cartDao().observeCartItemsWithToppings("user-a").first().size)
    assertEquals(0, db.notificationInboxDao().observeNotifications("user-a").first().size)
    assertEquals(1, realtime.resetCount.get())
    assertEquals(0L, realtime.lastHighWaterSeq.value)
    assertTrue("Logout must not PUT empty or user A cart", recordedPuts.isEmpty())

    // Step 3: Back online; User B logs in and bootstraps authoritative server cart
    offline.set(false)
    authRepo.login("b@pizzora.test", "Pass1234!")
    assertEquals("user-b", tokenStorage.currentSession()?.user?.id)
    assertEquals(2, realtime.resetCount.get())

    val bootstrap = cartRepo.bootstrapFromServer()
    assertFalse(bootstrap.isOffline)
    assertEquals(1, getCartTokens.size)
    assertTrue(
      "Login bootstrap must NEVER issue PUT /cart with User A's items or empty list; saw=$recordedPuts",
      recordedPuts.isEmpty(),
    )

    // User B sees strictly User B's cart and zero items from User A
    val userBCart = cartRepo.observeCart().first()
    assertEquals(1, userBCart.size)
    assertEquals("b-item-1", userBCart.first().item.id)
    assertEquals("user-b", userBCart.first().item.userId)
    assertEquals(0, db.cartDao().observeCartItemsWithToppings("user-a").first().size)
  }

  @Test
  fun `refresh 401 failure clears tokens, Room cart, Room inbox, and resets realtime stream without PUT`() = runBlocking {
    val userASession = sessionFor("user-a", "a@pizzora.test")
    val tokenStorage = InMemoryTokenStorage(userASession)
    val realtime = RecordingRealtimeStream()
    val coordinator = buildCoordinator(tokenStorage, realtime)
    val putCount = AtomicInteger(0)

    val engine = MockEngine { request ->
      val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
      when {
        request.url.encodedPath == "/api/v1/cart" && request.method == HttpMethod.Get -> {
          respond(
            json.encodeToString(
              ApiErrorEnvelope(
                error = ApiErrorDetail(code = "UNAUTHORIZED", message = "Expired"),
                correlationId = "corr-1",
                timestamp = "2026-11-16T08:00:00Z",
              ),
            ),
            HttpStatusCode.Unauthorized,
            jsonHeaders,
          )
        }
        request.url.encodedPath == "/api/v1/cart" && request.method == HttpMethod.Put -> {
          putCount.incrementAndGet()
          respond("Forbidden", HttpStatusCode.Unauthorized, jsonHeaders)
        }
        request.url.encodedPath == "/api/v1/auth/refresh" -> {
          respond(
            json.encodeToString(
              ApiErrorEnvelope(
                error = ApiErrorDetail(code = "INVALID_REFRESH", message = "Revoked"),
                correlationId = "corr-2",
                timestamp = "2026-11-16T08:00:01Z",
              ),
            ),
            HttpStatusCode.Unauthorized,
            jsonHeaders,
          )
        }
        else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
      }
    }

    val apiClient = PizzoraApiClient(
      baseUrl = "http://localhost:8080",
      tokenStorage = tokenStorage,
      engine = engine,
      sessionCoordinatorProvider = { coordinator },
    )
    val cartRepo = NetworkCartRepository(apiClient, db.cartDao(), tokenStorage, coordinator)

    // Seed local Room cart + notification for user-a
    db.cartDao().upsertItemWithToppings(
      com.example.core.database.CartItemEntity(
        id = "a-local-1",
        userId = "user-a",
        productId = productA.id,
        productName = productA.name,
        variantId = productA.variants.first().id,
        sizeCode = "REGULAR",
        crustId = classicCrust.id,
        crustName = classicCrust.name,
        unitPricePaise = 29_900L,
        quantity = 1,
      ),
      emptyList(),
    )
    db.notificationInboxDao().insertIgnore(
      listOf(
        NotificationInboxEntity(
          id = "n-1",
          userId = "user-a",
          eventId = "evt-1",
          title = "Alert",
          body = "Body",
          category = "ORDER",
          referenceId = null,
          isRead = false,
          createdAtIso = "2026-11-16T08:00:00Z",
        ),
      ),
    )

    val result = cartRepo.bootstrapFromServer()
    assertFalse("Refresh failure terminates session instead of faking offline cart", result.isOffline)
    assertNull(tokenStorage.currentSession())
    assertEquals(0, db.cartDao().observeCartItemsWithToppings("user-a").first().size)
    assertEquals(0, db.notificationInboxDao().observeNotifications("user-a").first().size)
    assertEquals(1, realtime.resetCount.get())
    assertEquals(0, putCount.get())
  }

  @Test
  fun `concurrent rapid transitions serialize cleanly with monotonic epoch and zero deadlock when logout returns 401`() = runBlocking {
    val userA = sessionFor("user-a", "a@pizzora.test")
    val userB = sessionFor("user-b", "b@pizzora.test")
    val tokenStorage = InMemoryTokenStorage(userA)
    val realtime = RecordingRealtimeStream()
    val coordinator = buildCoordinator(tokenStorage, realtime)
    val refreshCalledDuringLogout = AtomicBoolean(false)

    val engine = MockEngine { request ->
      val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
      when (request.url.encodedPath) {
        "/api/v1/auth/logout" -> {
          delay(25)
          respond("{\"code\":\"UNAUTHORIZED\"}", HttpStatusCode.Unauthorized, jsonHeaders)
        }
        "/api/v1/auth/refresh" -> {
          refreshCalledDuringLogout.set(true)
          respond("{\"code\":\"UNAUTHORIZED\"}", HttpStatusCode.Unauthorized, jsonHeaders)
        }
        else -> error("Unexpected ${request.url.encodedPath}")
      }
    }

    val apiClient = PizzoraApiClient(
      baseUrl = "http://localhost:8080",
      tokenStorage = tokenStorage,
      engine = engine,
      sessionCoordinatorProvider = { coordinator },
    )

    withTimeout(5_000) {
      val t1 = launch { coordinator.onLogout { apiClient.logout() } }
      val t2 = launch { coordinator.onLoginSuccess(userB) }
      val t3 = launch { coordinator.onLogout() }
      val t4 = launch { coordinator.onLoginSuccess(userA) }
      t1.join()
      t2.join()
      t3.join()
      t4.join()
    }

    assertFalse("Ktor Auth must short-circuit refreshTokens during logout transition", refreshCalledDuringLogout.get())
    assertFalse(coordinator.isLogoutInProgress())
    assertTrue("Expected monotonic epoch >= 4, got ${coordinator.sessionEpoch.value}", coordinator.sessionEpoch.value >= 4L)
  }

  @Test
  fun `in-flight user A cart and notification fetch resolving after switch to user B is rejected cleanly`() = runBlocking {
    val userA = sessionFor("user-a", "a@pizzora.test")
    val userB = sessionFor("user-b", "b@pizzora.test")
    val tokenStorage = InMemoryTokenStorage()
    val realtime = RecordingRealtimeStream()
    val coordinator = buildCoordinator(tokenStorage, realtime)

    val aRequestsStarted = CompletableDeferred<Unit>()
    val allowAResponses = CompletableDeferred<Unit>()
    val startedCount = AtomicInteger(0)

    val engine = MockEngine { request ->
      val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
      val token = request.headers[HttpHeaders.Authorization]
      when (request.url.encodedPath) {
        "/api/v1/cart" -> {
          if (token == "Bearer ${userA.accessToken}") {
            if (startedCount.incrementAndGet() >= 2) {
              aRequestsStarted.complete(Unit)
            }
            allowAResponses.await()
            val cartA = CartResponseDto(
              cartId = "cart-a",
              items = listOf(
                CartItemViewDto(
                  id = "leaked-a-item",
                  productId = productA.id,
                  productName = productA.name,
                  isVeg = true,
                  variantId = productA.variants.first().id,
                  sizeCode = SizeCode.REGULAR,
                  basePricePaise = 29_900L,
                  crustId = classicCrust.id,
                  crustName = classicCrust.name,
                  crustExtraPricePaise = 0L,
                  toppings = emptyList(),
                  quantity = 3,
                  unitPricePaise = 29_900L,
                  lineTotalPaise = 89_700L,
                  isAvailable = true,
                  issues = emptyList(),
                ),
              ),
              subtotalPaise = 89_700L,
              totalItemCount = 3,
              allAvailable = true,
              unavailableCount = 0,
              updatedAt = "2026-11-16T08:00:00Z",
            )
            respond(json.encodeToString(cartA), HttpStatusCode.OK, jsonHeaders)
          } else {
            respond(json.encodeToString(cartResponseForUserB()), HttpStatusCode.OK, jsonHeaders)
          }
        }
        "/api/v1/notifications" -> {
          if (token == "Bearer ${userA.accessToken}") {
            if (startedCount.incrementAndGet() >= 2) {
              aRequestsStarted.complete(Unit)
            }
            allowAResponses.await()
            val notifA = NotificationInboxResponse(
              notifications = listOf(
                NotificationInboxItemDto(
                  id = "leaked-a-notif",
                  eventId = "evt-shared-1",
                  title = "User A Secret Alert",
                  body = "Should never reach B",
                  category = "ORDER_UPDATE",
                  referenceId = "ord-a",
                  isRead = false,
                  deliveryStatus = NotificationDeliveryStatus.PENDING,
                  createdAt = "2026-11-16T08:00:00Z",
                ),
              ),
              unreadCount = 1,
              serverNow = "2026-11-16T08:00:00Z",
            )
            respond(json.encodeToString(notifA), HttpStatusCode.OK, jsonHeaders)
          } else {
            respond(
              json.encodeToString(
                NotificationInboxResponse(emptyList(), 0, "2026-11-16T08:01:00Z"),
              ),
              HttpStatusCode.OK,
              jsonHeaders,
            )
          }
        }
        else -> error("Unexpected ${request.url.encodedPath}")
      }
    }

    val apiClient = PizzoraApiClient(
      baseUrl = "http://localhost:8080",
      tokenStorage = tokenStorage,
      engine = engine,
      sessionCoordinatorProvider = { coordinator },
    )
    val cartRepo = NetworkCartRepository(apiClient, db.cartDao(), tokenStorage, coordinator)
    val notifRepo = NetworkNotificationRepository(apiClient, db.notificationInboxDao(), tokenStorage, coordinator)

    coordinator.onLoginSuccess(userA)

    val aCartJob = async { cartRepo.bootstrapFromServer() }
    val aNotifJob = async { notifRepo.syncInbox() }
    withTimeout(3_000) { aRequestsStarted.await() }

    // Switch session to User B while User A's HTTP calls are suspended in flight
    coordinator.onLoginSuccess(userB)
    allowAResponses.complete(Unit)

    aCartJob.await()
    val notifOutcome = aNotifJob.await()
    assertTrue("Stale notification sync must fail or discard cleanly", notifOutcome.isFailure)

    // Verify zero rows from User A exist in Room under either user-a or user-b
    assertEquals(0, db.cartDao().observeCartItemsWithToppings("user-a").first().size)
    assertEquals(0, db.cartDao().observeCartItemsWithToppings("user-b").first().size)
    assertEquals(0, db.notificationInboxDao().observeNotifications("user-a").first().size)
    assertEquals(0, db.notificationInboxDao().observeNotifications("user-b").first().size)

    // Now bootstrap B cleanly -> only B's server cart appears
    val bBoot = cartRepo.bootstrapFromServer()
    assertFalse(bBoot.isOffline)
    val bItems = cartRepo.observeCart().first()
    assertEquals(1, bItems.size)
    assertEquals("b-item-1", bItems.first().item.id)
  }

  @Test
  fun `passive flows never emit empty PUT cart, whereas explicit clearCart and removeItem last item do`() = runBlocking {
    val userA = sessionFor("user-a", "a@pizzora.test")
    val tokenStorage = InMemoryTokenStorage()
    val coordinator = buildCoordinator(tokenStorage)
    val emptyPuts = AtomicInteger(0)
    val allPuts = CopyOnWriteArrayList<ReplaceCartRequest>()

    val engine = MockEngine { request ->
      val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
      when {
        request.url.encodedPath == "/api/v1/cart" && request.method == HttpMethod.Get -> {
          respond(json.encodeToString(emptyCartResponse()), HttpStatusCode.OK, jsonHeaders)
        }
        request.url.encodedPath == "/api/v1/cart" && request.method == HttpMethod.Put -> {
          val text = (request.body as? TextContent)?.text
            ?: (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
          val req = json.decodeFromString<ReplaceCartRequest>(text)
          allPuts.add(req)
          if (req.items.isEmpty()) emptyPuts.incrementAndGet()
          respond(json.encodeToString(emptyCartResponse()), HttpStatusCode.OK, jsonHeaders)
        }
        else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
      }
    }

    val apiClient = PizzoraApiClient(
      baseUrl = "http://localhost:8080",
      tokenStorage = tokenStorage,
      engine = engine,
      sessionCoordinatorProvider = { coordinator },
    )
    val cartRepo = NetworkCartRepository(apiClient, db.cartDao(), tokenStorage, coordinator)

    // Passive login + bootstrap + syncAndValidate with empty cart + logout + refresh failure
    coordinator.onLoginSuccess(userA)
    cartRepo.bootstrapFromServer()
    cartRepo.syncAndValidateCart(persistToServer = true)
    coordinator.onLogout()
    coordinator.onRefreshFailure("user-a")
    assertEquals(0, emptyPuts.get())
    assertTrue(allPuts.isEmpty())

    // Explicit clearCart() while signed in MUST emit empty PUT
    coordinator.onLoginSuccess(userA)
    cartRepo.clearCart()
    assertEquals(1, emptyPuts.get())

    // Explicit add then removeItem() of the last item MUST emit non-empty then empty PUT
    db.cartDao().upsertItemWithToppings(
      com.example.core.database.CartItemEntity(
        id = "manual-1",
        userId = "user-a",
        productId = productA.id,
        productName = productA.name,
        variantId = productA.variants.first().id,
        sizeCode = "REGULAR",
        crustId = classicCrust.id,
        crustName = classicCrust.name,
        unitPricePaise = 29_900L,
        quantity = 1,
      ),
      emptyList(),
    )
    cartRepo.removeItem("manual-1")
    assertEquals(2, emptyPuts.get())
  }

  @Test
  fun `notificationInbox observe, unreadCount, markAsRead, and markAllAsRead isolate active user with shared event_id`() = runBlocking {
    val userA = sessionFor("user-a", "a@pizzora.test")
    val userB = sessionFor("user-b", "b@pizzora.test")
    val tokenStorage = InMemoryTokenStorage(userA)
    val coordinator = buildCoordinator(tokenStorage)

    val engine = MockEngine { request ->
      val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
      when {
        request.url.encodedPath.endsWith("/read") -> {
          respond(
            json.encodeToString(
              MarkNotificationReadResponse(
                notification = NotificationInboxItemDto(
                  id = "n-b-1",
                  eventId = "evt-shared",
                  title = "B Ready",
                  body = "B pizza",
                  category = "ORDER_UPDATE",
                  referenceId = "ord-b",
                  isRead = true,
                  deliveryStatus = NotificationDeliveryStatus.SYNCED,
                  createdAt = "2026-11-16T08:01:00Z",
                ),
                unreadCount = 1,
              ),
            ),
            HttpStatusCode.OK,
            jsonHeaders,
          )
        }
        request.url.encodedPath == "/api/v1/notifications/read-all" -> {
          respond(
            json.encodeToString(MarkAllNotificationsReadResponse(markedCount = 1, unreadCount = 0)),
            HttpStatusCode.OK,
            jsonHeaders,
          )
        }
        else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
      }
    }

    val apiClient = PizzoraApiClient(
      baseUrl = "http://localhost:8080",
      tokenStorage = tokenStorage,
      engine = engine,
      sessionCoordinatorProvider = { coordinator },
    )
    val notifRepo = NetworkNotificationRepository(apiClient, db.notificationInboxDao(), tokenStorage, coordinator)

    // Seed rows for BOTH user-a and user-b directly (simulating multi-user device DB rows)
    db.notificationInboxDao().insertIgnore(
      listOf(
        NotificationInboxEntity(
          id = "n-a-1",
          userId = "user-a",
          eventId = "evt-shared",
          title = "A Baking",
          body = "A pizza",
          category = "ORDER_UPDATE",
          referenceId = "ord-a",
          isRead = false,
          createdAtIso = "2026-11-16T08:00:00Z",
        ),
        NotificationInboxEntity(
          id = "n-b-1",
          userId = "user-b",
          eventId = "evt-shared",
          title = "B Ready",
          body = "B pizza",
          category = "ORDER_UPDATE",
          referenceId = "ord-b",
          isRead = false,
          createdAtIso = "2026-11-16T08:01:00Z",
        ),
        NotificationInboxEntity(
          id = "n-b-2",
          userId = "user-b",
          eventId = "evt-b-only",
          title = "B Dispatched",
          body = "B on the way",
          category = "ORDER_UPDATE",
          referenceId = "ord-b",
          isRead = false,
          createdAtIso = "2026-11-16T08:02:00Z",
        ),
      ),
    )

    // While user-a is active, only n-a-1 is visible
    val aVisible = notifRepo.observeInbox().first()
    assertEquals(listOf("n-a-1"), aVisible.map { it.id })
    assertEquals(1, notifRepo.observeUnreadCount().first())

    // Switch active session to user-b directly in storage to test DAO user_id isolation
    tokenStorage.saveSession(userB)
    val bVisible = notifRepo.observeInbox().first()
    assertEquals(listOf("n-b-2", "n-b-1"), bVisible.map { it.id })
    assertEquals(2, notifRepo.observeUnreadCount().first())

    // markAsRead and markAllAsRead only mutate user-b rows; user-a remains unread
    assertTrue(notifRepo.markAsRead("n-b-1").isSuccess)
    assertEquals(1, notifRepo.observeUnreadCount().first())
    assertEquals(1, db.notificationInboxDao().observeUnreadCount("user-a").first())

    assertTrue(notifRepo.markAllAsRead().isSuccess)
    assertEquals(0, notifRepo.observeUnreadCount().first())
    assertEquals(1, db.notificationInboxDao().observeUnreadCount("user-a").first())

    // Signed out observes empty list and 0 unread
    tokenStorage.clearSession()
    assertTrue(notifRepo.observeInbox().first().isEmpty())
    assertEquals(0, notifRepo.observeUnreadCount().first())
  }
}

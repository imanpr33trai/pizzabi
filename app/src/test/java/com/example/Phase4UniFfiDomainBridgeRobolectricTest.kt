package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cart.NetworkCartRepository
import com.example.core.database.PizzoraDatabase
import com.example.core.domain.CartSyncAction
import com.example.core.domain.CartSyncPlanRequest
import com.example.core.domain.CartSyncTrigger
import com.example.core.domain.DomainBridge
import com.example.core.domain.KotlinContractsDomainBridge
import com.example.core.domain.RustDomainBridge
import com.example.core.domain.SessionIdentitySnapshot
import com.example.core.media.ImageCompressor
import com.example.core.media.ProductImageUploader
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.DefaultSessionScopeCleaner
import com.example.core.security.SessionTransitionCoordinator
import com.example.core.security.TokenStorage
import com.example.kds.KdsChimePlayer
import com.example.kds.KdsRepository
import com.example.kds.KdsViewModel
import com.example.orders.OrderRepository
import com.example.orders.OrdersViewModel
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.ApiErrorDetail
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AvailableSlotsResponse
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CartToppingInputDto
import com.pizzora.contracts.CartValidationIssueCode
import com.pizzora.contracts.CausalDecision
import com.pizzora.contracts.CheeseOptionDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.CouponCalculationSpec
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FailPaymentAttemptRequest
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.KdsTicketActionResponse
import com.pizzora.contracts.KdsTicketDto
import com.pizzora.contracts.KdsTicketsResponse
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderPaymentStatusResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PaymentSummaryDto
import com.pizzora.contracts.PizzaSizeRuleDto
import com.pizzora.contracts.PriceBreakdownDto
import com.pizzora.contracts.PricedLineInput
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.RealtimeEventDto
import com.pizzora.contracts.SauceOptionDto
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.ToppingPlacement
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
import com.pizzora.contracts.VerifyRazorpayPaymentResponse
import com.pizzora.contracts.WsEventsFrame
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
class Phase4UniFfiDomainBridgeRobolectricTest {

  private lateinit var db: PizzoraDatabase
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  @Serializable
  private data class GoldenParityBundle(
    val source: String,
    val money_bps_vectors: List<MoneyBpsVector>,
    val money_format_vectors: List<MoneyFormatVector>,
    val discount_vectors: List<DiscountVector>,
    val quote_vectors: List<QuoteVector>,
    val order_cancel_vectors: List<OrderCancelVector>,
    val order_transition_vectors: List<OrderTransitionVector>,
    val kds_next_vectors: List<KdsNextVector>,
    val payment_transition_vectors: List<PaymentTransitionVector>,
    val attempt_transition_vectors: List<AttemptTransitionVector>,
    val causal_trace: List<CausalStepVector>,
  )

  @Serializable
  private data class MoneyBpsVector(val amount_paise: Long, val bps: Int, val expected_paise: Long)

  @Serializable
  private data class MoneyFormatVector(val paise: Long, val expected_inr: String)

  @Serializable
  private data class CouponSpecInput(
    val discount_type: DiscountType,
    val discount_value: Long,
    val max_discount_paise: Long? = null,
    val min_order_paise: Long = 0L,
    val is_active: Boolean = true,
  )

  @Serializable
  private data class DiscountVector(
    val subtotal_paise: Long,
    val coupon: CouponSpecInput?,
    val expected_discount_paise: Long,
  )

  @Serializable
  private data class QuoteLineInput(
    val unit_price_paise: Long,
    val quantity: Int,
    val is_taxable: Boolean,
  )

  @Serializable
  private data class QuoteVector(
    val name: String,
    val lines: List<QuoteLineInput>,
    val gst_bps: Int,
    val delivery_fee_paise: Long,
    val coupon: CouponSpecInput?,
    val expected: PriceBreakdownDto,
  )

  @Serializable
  private data class OrderCancelVector(
    val status: OrderStatus,
    val role: UserRole,
    val expected: Boolean,
  )

  @Serializable
  private data class OrderTransitionVector(
    val from: OrderStatus,
    val to: OrderStatus,
    val fulfillment_type: FulfillmentType,
    val actor_role: UserRole?,
    val expected: Boolean,
  )

  @Serializable
  private data class KdsNextVector(
    val status: OrderStatus,
    val fulfillment_type: FulfillmentType,
    val expected: OrderStatus?,
  )

  @Serializable
  private data class PaymentTransitionVector(
    val from: PaymentStatus,
    val to: PaymentStatus,
    val expected: Boolean,
  )

  @Serializable
  private data class AttemptTransitionVector(
    val from: PaymentAttemptStatus,
    val to: PaymentAttemptStatus,
    val expected: Boolean,
  )

  @Serializable
  private data class CausalStepVector(
    val action: String,
    val event_id: String? = null,
    val stream_seq: Long? = null,
    val event_type: String? = null,
    val aggregate_type: String? = null,
    val aggregate_id: String? = null,
    val aggregate_version: Long? = null,
    val order_id: String? = null,
    val high_water_seq: Long? = null,
    val expected_decision: String? = null,
    val expected_resync_order_id: String? = null,
    val expected_last_seen_seq: Long,
    val expected_tracked_version: Long? = null,
  )

  @Serializable
  private data class StorageKeyBundle(
    val source: String,
    val cases: List<StorageKeyCase>,
  )

  @Serializable
  private data class StorageKeyCase(
    val raw_key: String,
    val expected_valid: Boolean,
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

  private class SpyDomainBridge(
    private val delegate: DomainBridge = RustDomainBridge,
  ) : DomainBridge by delegate {
    val recordedPlans = CopyOnWriteArrayList<Pair<CartSyncPlanRequest, CartSyncAction>>()
    val recordedHttpFallbacks = CopyOnWriteArrayList<Pair<Int, CartSyncAction?>>()
    val recordedKdsNext = CopyOnWriteArrayList<Pair<OrderStatus, OrderStatus?>>()
    val recordedValidatedKeys = CopyOnWriteArrayList<String>()

    override fun planCartSync(request: CartSyncPlanRequest): CartSyncAction {
      val action = delegate.planCartSync(request)
      recordedPlans += request to action
      return action
    }

    override fun fallbackOnReplaceHttpError(statusCode: Int): CartSyncAction? {
      val action = delegate.fallbackOnReplaceHttpError(statusCode)
      recordedHttpFallbacks += statusCode to action
      return action
    }

    override fun nextKdsStatus(status: OrderStatus, fulfillmentType: FulfillmentType): OrderStatus? {
      val next = delegate.nextKdsStatus(status, fulfillmentType)
      recordedKdsNext += status to next
      return next
    }

    override fun validateStorageObjectKey(rawKey: String): String {
      val valid = delegate.validateStorageObjectKey(rawKey)
      recordedValidatedKeys += valid
      return valid
    }
  }

  @Before
  fun setUp() {
    RustDomainBridge.overrideForTesting(null)
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, PizzoraDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() {
    RustDomainBridge.overrideForTesting(null)
    db.close()
  }

  private fun findRepoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
    while (dir != null) {
      if (File(dir, "tools/golden-vectors").isDirectory && File(dir, "settings.gradle.kts").isFile) {
        return dir
      }
      dir = dir.parentFile
    }
    error("Could not locate repository root from ${System.getProperty("user.dir")}")
  }

  @Test
  fun domainBridge_matches_all_golden_parity_and_storage_key_vectors() {
    val root = findRepoRoot()
    val parityFile = File(root, "tools/golden-vectors/pizzora_core_parity.json")
    val storageFile = File(root, "tools/golden-vectors/storage_key_parity.json")
    val bundle = json.decodeFromString<GoldenParityBundle>(parityFile.readText())
    val storageBundle = json.decodeFromString<StorageKeyBundle>(storageFile.readText())

    val bridgesToCheck = buildList {
      add(RustDomainBridge)
      add(KotlinContractsDomainBridge())
    }

    for (bridge in bridgesToCheck) {
      assertTrue(bridge.domainVersion().isNotBlank())

      bundle.money_bps_vectors.forEach { v ->
        assertEquals(v.expected_paise, bridge.bpsOf(v.amount_paise, v.bps))
      }

      bundle.money_format_vectors.forEach { v ->
        assertEquals(v.expected_inr, bridge.formatInr(v.paise))
      }

      bundle.discount_vectors.forEach { v ->
        val coupon = v.coupon?.let {
          CouponCalculationSpec(
            discountType = it.discount_type,
            discountValue = it.discount_value,
            maxDiscountPaise = it.max_discount_paise,
            minOrderPaise = it.min_order_paise,
            isActive = it.is_active,
          )
        }
        assertEquals(v.expected_discount_paise, bridge.calculateDiscountPaise(v.subtotal_paise, coupon))
      }

      bundle.quote_vectors.forEach { v ->
        val lines = v.lines.map { PricedLineInput(it.unit_price_paise, it.quantity, it.is_taxable) }
        val coupon = v.coupon?.let {
          CouponCalculationSpec(
            discountType = it.discount_type,
            discountValue = it.discount_value,
            maxDiscountPaise = it.max_discount_paise,
            minOrderPaise = it.min_order_paise,
            isActive = it.is_active,
          )
        }
        assertEquals(v.expected, bridge.calculateQuote(lines, v.gst_bps, v.delivery_fee_paise, coupon))
      }

      bundle.order_cancel_vectors.forEach { v ->
        assertEquals(v.expected, bridge.canCancelOrder(v.status, v.role))
      }

      bundle.order_transition_vectors.forEach { v ->
        assertEquals(
          v.expected,
          bridge.canTransitionOrder(v.from, v.to, v.fulfillment_type, v.actor_role),
        )
      }

      bundle.kds_next_vectors.forEach { v ->
        assertEquals(v.expected, bridge.nextKdsStatus(v.status, v.fulfillment_type))
      }

      bundle.payment_transition_vectors.forEach { v ->
        assertEquals(v.expected, bridge.canTransitionPayment(v.from, v.to))
      }

      bundle.attempt_transition_vectors.forEach { v ->
        assertEquals(v.expected, bridge.canTransitionPaymentAttempt(v.from, v.to))
      }

      val tracker = bridge.createCausalTracker(maxTrackedEventIds = 3)
      for (step in bundle.causal_trace) {
        when (step.action.uppercase()) {
          "ADVANCE_CURSOR" -> tracker.advanceCursor(step.high_water_seq!!)
          "RECORD_SNAPSHOT" -> tracker.recordSnapshot(step.order_id!!, step.aggregate_version!!)
          "EVALUATE" -> {
            val evt = RealtimeEventDto(
              eventId = step.event_id!!,
              streamSeq = step.stream_seq!!,
              eventType = step.event_type!!,
              aggregateType = step.aggregate_type!!,
              aggregateId = step.aggregate_id!!,
              aggregateVersion = step.aggregate_version!!,
              payload = if (step.order_id != null) {
                buildJsonObject { put("order_id", step.order_id) }
              } else {
                JsonObject(emptyMap())
              },
              createdAt = "2026-11-16T00:00:00Z",
            )
            val decision = tracker.evaluate(evt)
            val actualTag = when (decision) {
              CausalDecision.IgnoreDuplicate -> "IGNORE_DUPLICATE"
              CausalDecision.IgnoreStale -> "IGNORE_STALE"
              is CausalDecision.ApplyOrderEvent -> "APPLY_ORDER_EVENT"
              is CausalDecision.TriggerResync -> {
                assertEquals(step.expected_resync_order_id, decision.orderId)
                "TRIGGER_RESYNC"
              }
              is CausalDecision.ApplyNonOrderEvent -> "APPLY_NON_ORDER_EVENT"
            }
            assertEquals(step.expected_decision, actualTag)
          }
          else -> error("Unknown causal trace action: ${step.action}")
        }
        assertEquals(step.expected_last_seen_seq, tracker.lastSeenStreamSeq)
        if (step.order_id != null && step.expected_tracked_version != null) {
          assertEquals(step.expected_tracked_version, tracker.localVersionFor(step.order_id))
        }
      }

      storageBundle.cases.forEach { c ->
        val actualValid = runCatching { bridge.validateStorageObjectKey(c.raw_key) }.isSuccess
        assertEquals("Mismatch for key '${c.raw_key}'", c.expected_valid, actualValid)
      }

      assertEquals("PIZZORA-2026_1", bridge.normalizeCouponCode("  pizzora-2026_1 "))
      assertTrue(runCatching { bridge.normalizeCouponCode("ab") }.isFailure)
    }
  }

  @Test
  fun cartSyncPlanner_and_fallback_mode_enforce_ownership_and_safe_defaults() {
    val active = SessionIdentitySnapshot(epoch = 2L, userId = "user-b")
    val staleEpoch = SessionIdentitySnapshot(epoch = 1L, userId = "user-b")
    val wrongUser = SessionIdentitySnapshot(epoch = 2L, userId = "user-a")

    assertEquals(
      CartSyncAction.DISCARD_STALE_SESSION,
      RustDomainBridge.planCartSync(
        CartSyncPlanRequest(
          activeSession = null,
          capturedSession = active,
          trigger = CartSyncTrigger.EXPLICIT_MUTATION,
          localItemCount = 3,
          persistToServer = true,
          allowEmptyReplace = false,
        ),
      ),
    )
    assertEquals(
      CartSyncAction.DISCARD_STALE_SESSION,
      RustDomainBridge.planCartSync(
        CartSyncPlanRequest(
          activeSession = active,
          capturedSession = staleEpoch,
          trigger = CartSyncTrigger.EXPLICIT_MUTATION,
          localItemCount = 1,
          persistToServer = true,
          allowEmptyReplace = false,
        ),
      ),
    )
    assertEquals(
      CartSyncAction.DISCARD_STALE_SESSION,
      RustDomainBridge.planCartSync(
        CartSyncPlanRequest(
          activeSession = active,
          capturedSession = wrongUser,
          trigger = CartSyncTrigger.PASSIVE_BOOTSTRAP,
          localItemCount = 2,
          persistToServer = true,
          allowEmptyReplace = false,
        ),
      ),
    )
    assertEquals(
      CartSyncAction.FETCH_SERVER_CART,
      RustDomainBridge.planCartSync(
        CartSyncPlanRequest(
          activeSession = active,
          capturedSession = active,
          trigger = CartSyncTrigger.PASSIVE_BOOTSTRAP,
          localItemCount = 4,
          persistToServer = true,
          allowEmptyReplace = true,
        ),
      ),
    )
    assertEquals(
      CartSyncAction.REPLACE_SERVER_CART_EMPTY,
      RustDomainBridge.planCartSync(
        CartSyncPlanRequest(
          activeSession = active,
          capturedSession = active,
          trigger = CartSyncTrigger.EXPLICIT_CLEAR_OR_REMOVE,
          localItemCount = 0,
          persistToServer = true,
          allowEmptyReplace = true,
        ),
      ),
    )
    assertEquals(
      CartSyncAction.FETCH_SERVER_CART,
      RustDomainBridge.planCartSync(
        CartSyncPlanRequest(
          activeSession = active,
          capturedSession = active,
          trigger = CartSyncTrigger.EXPLICIT_MUTATION,
          localItemCount = 0,
          persistToServer = true,
          allowEmptyReplace = false,
        ),
      ),
    )
    assertEquals(
      CartSyncAction.NO_OP_EMPTY_LOCAL,
      RustDomainBridge.planCartSync(
        CartSyncPlanRequest(
          activeSession = active,
          capturedSession = active,
          trigger = CartSyncTrigger.PERIODIC_VALIDATE,
          localItemCount = 0,
          persistToServer = false,
          allowEmptyReplace = false,
        ),
      ),
    )
    assertEquals(
      CartSyncAction.REPLACE_SERVER_CART_WITH_VALIDATE_FALLBACK,
      RustDomainBridge.planCartSync(
        CartSyncPlanRequest(
          activeSession = active,
          capturedSession = active,
          trigger = CartSyncTrigger.EXPLICIT_MUTATION,
          localItemCount = 2,
          persistToServer = true,
          allowEmptyReplace = false,
        ),
      ),
    )
    assertEquals(
      CartSyncAction.VALIDATE_CART_ONLY,
      RustDomainBridge.fallbackOnReplaceHttpError(422),
    )
    assertNull(RustDomainBridge.fallbackOnReplaceHttpError(500))

    RustDomainBridge.overrideForTesting(RustDomainBridge.fallbackInstance())
    assertFalse(RustDomainBridge.isNativeRustActive)
    assertEquals("kotlin-shared-contracts", RustDomainBridge.bridgeImplementationName)
    assertEquals("₹299.00", RustDomainBridge.formatInr(29_900L))
  }

  @Test
  fun cartRepository_and_uploader_route_through_domain_bridge() = runBlocking {
    val requests = CopyOnWriteArrayList<String>()
    val session = AuthSession(
      accessToken = "tok-a",
      refreshToken = "ref-a",
      user = UserProfileDto(
        id = "user-a",
        email = "a@pizzora.test",
        emailVerified = true,
        fullName = "User A",
        phone = "+919876543210",
        role = UserRole.CUSTOMER,
        isActive = true,
      ),
    )
    val storage = InMemoryTokenStorage(session)
    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      requests += "${request.method.value} $path"
      when {
        path == "/api/v1/cart" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(CartResponseDto(updatedAt = "2026-11-16T00:00:00Z")),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/cart" && request.method == HttpMethod.Put -> respond(
          content = json.encodeToString(
            ApiErrorEnvelope(
              error = ApiErrorDetail("UNPROCESSABLE", "Cart item unavailable", emptyMap()),
              correlationId = "corr-422",
              timestamp = "2026-11-16T00:00:00Z",
            ),
          ),
          status = HttpStatusCode.UnprocessableEntity,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/cart/validate" && request.method == HttpMethod.Post -> respond(
          content = json.encodeToString(
            CartResponseDto(
              unavailableCount = 1,
              allAvailable = false,
              updatedAt = "2026-11-16T00:00:00Z",
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        else -> error("Unexpected request ${request.method} $path")
      }
    }

    val apiClient = PizzoraApiClient("http://localhost:8080", storage, engine)
    val cleaner = DefaultSessionScopeCleaner(
      cartDao = db.cartDao(),
      notificationInboxDao = db.notificationInboxDao(),
      realtimeEventStreamProvider = { null },
      onAuthCacheClear = { apiClient.clearAuthTokenCache() },
    )
    val coordinator = SessionTransitionCoordinator(storage) { cleaner }
    coordinator.onLoginSuccess(session)
    val spyBridge = SpyDomainBridge()
    val repo = NetworkCartRepository(
      apiClient = apiClient,
      cartDao = db.cartDao(),
      tokenStorage = storage,
      sessionCoordinator = coordinator,
      domainBridge = spyBridge,
    )

    val boot = repo.bootstrapFromServer()
    assertFalse(boot.isOffline)
    assertEquals(CartSyncTrigger.PASSIVE_BOOTSTRAP, spyBridge.recordedPlans.last().first.trigger)
    assertEquals(CartSyncAction.FETCH_SERVER_CART, spyBridge.recordedPlans.last().second)

    val addRes = repo.addCustomizedItem(
      product = ProductDto(
        id = "p1",
        categoryId = "c1",
        categoryName = "Classics",
        name = "Margherita",
        description = "Cheese",
        isVeg = true,
        isActive = true,
        isTaxable = true,
      ),
      variant = ProductVariantDto("v1", "p1", SizeCode.REGULAR, 19_900L, true),
      crust = CrustOptionDto("cr1", "Hand Tossed", 0L, true),
      toppings = emptyList(),
      quantity = 1,
    )
    assertEquals(1, addRes.unavailableCount)
    assertEquals(422 to CartSyncAction.VALIDATE_CART_ONLY, spyBridge.recordedHttpFallbacks.single())
    assertEquals(
      listOf("GET /api/v1/cart", "PUT /api/v1/cart", "POST /api/v1/cart/validate"),
      requests.toList(),
    )

    val uploader = ProductImageUploader(apiClient, ImageCompressor(), spyBridge)
    assertEquals("products/p1/hero.webp", uploader.validateObjectKey("products/p1/hero.webp"))
    assertEquals(listOf("products/p1/hero.webp"), spyBridge.recordedValidatedKeys.toList())
    assertTrue(runCatching { uploader.validateObjectKey("../bad/key.webp") }.isFailure)
  }

  @Test
  fun ordersAndKdsViewModels_delegate_causal_tracker_and_kds_next_stage_to_domain_bridge() {
    val spyBridge = SpyDomainBridge()
    val fakeOrderRepo = object : OrderRepository {
      override suspend fun getAddresses(): Result<List<AddressDto>> = error("unused")
      override suspend fun createAddress(request: UpsertAddressRequest): Result<AddressDto> = error("unused")
      override suspend fun updateAddress(addressId: String, request: UpsertAddressRequest): Result<AddressDto> = error("unused")
      override suspend fun deleteAddress(addressId: String): Result<Unit> = error("unused")
      override suspend fun checkDeliveryZone(pincode: String): Result<DeliveryZoneCheckResponse> = error("unused")
      override suspend fun getAvailableSlots(type: FulfillmentType, date: String?): Result<AvailableSlotsResponse> = error("unused")
      override suspend fun placeOrder(idempotencyKey: String, request: CheckoutRequest): Result<CheckoutResponse> = error("unused")
      override suspend fun fetchOrders(): Result<OrderListResponse> =
        Result.success(OrderListResponse(emptyList()))
      override suspend fun fetchOrderDetail(orderId: String): Result<OrderDetailDto> =
        Result.success(
          OrderDetailDto(
            id = orderId,
            orderNumber = "PZZ-4001",
            customerId = "user-a",
            fulfillmentType = FulfillmentType.DELIVERY,
            scheduleType = ScheduleType.ASAP,
            status = OrderStatus.PREPARING,
            version = 3L,
            subtotalPaise = 29_900L,
            discountPaise = 0L,
            deliveryFeePaise = 0L,
            taxPaise = 1_495L,
            cgstPaise = 747L,
            sgstPaise = 748L,
            totalPaise = 31_395L,
            gstBpsSnapshot = 500,
            isPriority = false,
            createdAt = "2026-11-16T00:00:00Z",
            items = emptyList(),
            statusHistory = emptyList(),
            payment = PaymentSummaryDto(
              id = "pay-4001",
              method = PaymentMethod.COD,
              status = PaymentStatus.COD_PENDING,
              amountPaise = 31_395L,
              capturedPaise = 0L,
              refundedPaise = 0L,
            ),
          ),
        )
      override suspend fun cancelOrder(orderId: String, reason: String?): Result<OrderDetailDto> = error("unused")
      override suspend fun fetchOrderPaymentStatus(orderId: String): Result<OrderPaymentStatusResponse> = error("unused")
      override suspend fun retryOrderPayment(orderId: String, idempotencyKey: String, reason: String?): Result<OrderPaymentStatusResponse> = error("unused")
      override suspend fun reportPaymentFailure(orderId: String, request: FailPaymentAttemptRequest): Result<OrderPaymentStatusResponse> = error("unused")
      override suspend fun verifyRazorpayPayment(request: VerifyRazorpayPaymentRequest): Result<VerifyRazorpayPaymentResponse> = error("unused")
    }

    val ordersVm = OrdersViewModel(
      orderRepository = fakeOrderRepo,
      domainBridge = spyBridge,
    )
    ordersVm.processIncomingFrame(
      WsEventsFrame(
        type = "events",
        events = listOf(
          RealtimeEventDto(
            eventId = "evt-1",
            streamSeq = 10L,
            eventType = "order.status_changed",
            aggregateType = "ORDER",
            aggregateId = "ord-4001",
            aggregateVersion = 3L,
            payload = buildJsonObject { put("order_id", "ord-4001") },
            createdAt = "2026-11-16T00:00:00Z",
          ),
        ),
        serverHighWaterSeq = 10L,
        serverNow = "2026-11-16T00:00:00Z",
      ),
    )
    assertEquals(10L, ordersVm.uiState.value.lastHighWaterSeq)

    val transitioned = mutableListOf<Pair<String, OrderStatus>>()
    val fakeKdsRepo = object : KdsRepository {
      override suspend fun fetchTickets(includeCompleted: Boolean): Result<KdsTicketsResponse> =
        Result.success(KdsTicketsResponse(emptyList(), "2026-11-16T00:00:00Z", 20))
      override suspend fun transitionOrder(orderId: String, targetStatus: OrderStatus, reason: String?): Result<KdsTicketActionResponse> {
        transitioned += orderId to targetStatus
        return Result.failure(IllegalStateException("recorded"))
      }
      override suspend fun updatePriority(orderId: String, isPriority: Boolean, reason: String?): Result<KdsTicketActionResponse> = error("unused")
    }
    val kdsVm = KdsViewModel(
      kdsRepository = fakeKdsRepo,
      tokenStorage = InMemoryTokenStorage(),
      chimePlayer = object : KdsChimePlayer {
        override fun playNewTicketChime() = Unit
      },
      enableTicker = false,
      domainBridge = spyBridge,
    )
    kdsVm.advanceTicketStage(
      KdsTicketDto(
        id = "ord-kds-1",
        orderNumber = "PZZ-KDS-1",
        status = OrderStatus.READY,
        fulfillmentType = FulfillmentType.PICKUP,
        scheduleType = ScheduleType.ASAP,
        paymentMethod = PaymentMethod.COD,
        paymentStatus = PaymentStatus.COD_PENDING,
        totalPaise = 29_900L,
        customerName = "Guest",
        isPriority = false,
        version = 1L,
        createdAt = "2026-11-16T00:00:00Z",
        items = emptyList(),
      ),
    )
    assertEquals(OrderStatus.READY to OrderStatus.COMPLETED, spyBridge.recordedKdsNext.single())
  }

  @Test
  fun pizzaBuilder_signature_and_evaluation_match_across_domain_bridges() {
    val menu = PublicMenuResponse(
      categories = emptyList(),
      products = listOf(
        ProductDto(
          id = "p-margh",
          categoryId = "cat-1",
          categoryName = "Classics",
          name = "Margherita",
          description = "Tomato & mozzarella",
          isVeg = true,
          isActive = true,
          isTaxable = true,
          variants = listOf(
            ProductVariantDto("v-margh-r", "p-margh", SizeCode.REGULAR, 29_900L, true),
            ProductVariantDto("v-margh-l", "p-margh", SizeCode.LARGE, 54_900L, true),
          ),
          supportsHalfAndHalf = true,
          defaultCheeseId = "ch-mozz",
          defaultSauceId = "sa-san",
          includedToppingIds = listOf("top-basil"),
        ),
        ProductDto(
          id = "p-farm",
          categoryId = "cat-1",
          categoryName = "Specials",
          name = "Farmhouse",
          description = "Peppers & olives",
          isVeg = true,
          isActive = true,
          isTaxable = true,
          variants = listOf(
            ProductVariantDto("v-farm-l", "p-farm", SizeCode.LARGE, 64_900L, true),
          ),
          supportsHalfAndHalf = true,
          defaultCheeseId = "ch-mozz",
          defaultSauceId = "sa-pesto",
          includedToppingIds = listOf("top-olive"),
        ),
      ),
      crusts = listOf(
        CrustOptionDto("cr-hand", "Hand-Tossed", 0L, true, supportsHalfAndHalf = true),
        CrustOptionDto("cr-burst", "Cheese Burst", 9_900L, true, supportsHalfAndHalf = false),
      ),
      toppings = listOf(
        ToppingDto("top-basil", "Fresh Basil", true, 2_500L, true, maxQuantity = 2, supportsHalfAndHalf = true),
        ToppingDto("top-olive", "Black Olives", true, 4_500L, true, maxQuantity = 2, supportsHalfAndHalf = true),
        ToppingDto("top-burrata", "Burrata", true, 9_500L, true, maxQuantity = 2, supportsHalfAndHalf = false),
      ),
      gstBps = 500,
      storeName = "PIZZORA",
      isAcceptingOrders = true,
      sizeRules = listOf(
        PizzaSizeRuleDto(SizeCode.REGULAR, "Regular", maxToppings = 4, maxExtraToppings = 2, supportsHalfAndHalf = false),
        PizzaSizeRuleDto(SizeCode.LARGE, "Large", maxToppings = 8, maxExtraToppings = 4, supportsHalfAndHalf = true),
      ),
      cheeses = listOf(
        CheeseOptionDto("ch-mozz", "Fior di Latte", 0L, 6_000L, supportsExtra = true, isVegan = false, isActive = true),
        CheeseOptionDto("ch-vegan", "Cashew Mozzarella", 4_000L, 5_000L, supportsExtra = true, isVegan = true, isActive = true),
      ),
      sauces = listOf(
        SauceOptionDto("sa-san", "San Marzano", 0L, 2_000L, supportsExtra = true, isVegan = true, isActive = true),
        SauceOptionDto("sa-pesto", "Basil Pesto", 4_001L, 3_001L, supportsExtra = true, isVegan = true, isActive = true),
      ),
    )

    val validHalfInput = CartItemInputDto(
      productId = "p-margh",
      variantId = "v-margh-l",
      crustId = "cr-hand",
      quantity = 2,
      cheeseId = "ch-vegan",
      extraCheese = true,
      sauceId = "sa-san",
      extraSauce = true,
      isHalfAndHalf = true,
      rightProductId = "p-farm",
      rightVariantId = "v-farm-l",
      rightSauceId = "sa-pesto",
      removedIncludedToppingIds = listOf("top-basil"),
      toppingSelections = listOf(
        CartToppingInputDto("top-olive", ToppingPlacement.LEFT, quantity = 1),
        CartToppingInputDto("top-olive", ToppingPlacement.RIGHT, quantity = 2),
      ),
    )

    val invalidRegularHalf = validHalfInput.copy(
      variantId = "v-margh-r",
      crustId = "cr-burst",
      toppingSelections = listOf(
        CartToppingInputDto("top-burrata", ToppingPlacement.LEFT, quantity = 3),
      ),
    )

    val bridges = listOf(RustDomainBridge, KotlinContractsDomainBridge())
    for (bridge in bridges) {
      val eval = bridge.evaluatePizzaCustomization(validHalfInput, menu)
      assertTrue(eval.isValid)
      // Base 54900 + Half surcharge (64900-54900=10000) + Cheese 4000 + ExtraCheese 5000
      // + LeftSauce 0 + RightSauce 50% HALF_UP(4001)=2001
      // + ExtraSauce (50% HALF_UP(2000)=1000 + 50% HALF_UP(3001)=1501 = 2501)
      // + Olives Left 50%(4500)=2250 + Olives Right included(1 free) + 1*2250 = 4500
      // Unit = 54900 + 10000 + 4000 + 5000 + 2001 + 2501 + 4500 = 82902 paise
      assertEquals(82_902L, eval.unitPricePaise)
      assertEquals(165_804L, eval.lineTotalPaise)
      assertEquals(bridge.computePizzaConfigurationSignature(validHalfInput, menu), eval.configurationSignature)

      val invalidEval = bridge.evaluatePizzaCustomization(invalidRegularHalf, menu)
      assertFalse(invalidEval.isValid)
      val codes = invalidEval.issues.map { it.code }.toSet()
      assertTrue(CartValidationIssueCode.HALF_AND_HALF_UNSUPPORTED in codes)
      assertTrue(CartValidationIssueCode.RIGHT_HALF_INVALID in codes)
      assertTrue(CartValidationIssueCode.TOPPING_PLACEMENT_INVALID in codes)
      assertTrue(CartValidationIssueCode.INVALID_QUANTITY in codes)
    }
  }
}

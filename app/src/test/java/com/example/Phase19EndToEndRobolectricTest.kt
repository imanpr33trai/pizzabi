package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.auth.AuthViewModel
import com.example.auth.NetworkAuthRepository
import com.example.cart.CartViewModel
import com.example.cart.NetworkCartRepository
import com.example.catalog.CustomerCatalogViewModel
import com.example.catalog.NetworkCatalogRepository
import com.example.checkout.CheckoutQuoteViewModel
import com.example.checkout.CheckoutViewModel
import com.example.checkout.NetworkPricingRepository
import com.example.core.database.PizzoraDatabase
import com.example.core.media.ImageCompressor
import com.example.core.media.ProductImageUploader
import com.example.core.network.PizzoraApiClient
import com.example.core.network.RealtimeEventStream
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.kds.KdsChimePlayer
import com.example.kds.KdsViewModel
import com.example.kds.NetworkKdsRepository
import com.example.navigation.PizzoraApp
import com.example.orders.NetworkOrderRepository
import com.example.orders.OrdersViewModel
import com.example.owner.NetworkOwnerRepository
import com.example.owner.OwnerViewModel
import com.example.profile.NetworkProfileRepository
import com.example.profile.ProfileViewModel
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.AppliedCouponSummaryDto
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.AvailableCouponsResponse
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CartToppingViewDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.CouponCalculationSpec
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.CustomerCouponDto
import com.pizzora.contracts.DeliveryAddressSnapshotDto
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.DeliveryZoneDto
import com.pizzora.contracts.DeliveryZoneQuoteDto
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.KdsTicketActionResponse
import com.pizzora.contracts.KdsTicketDto
import com.pizzora.contracts.KdsTicketItemDto
import com.pizzora.contracts.KdsTicketsResponse
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.MessageResponse
import com.pizzora.contracts.Money
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderItemDto
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderStatusHistoryDto
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.OwnerDashboardResponse
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentAttemptSummaryDto
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PaymentSummaryDto
import com.pizzora.contracts.PricedLineInput
import com.pizzora.contracts.PricingEngine
import com.pizzora.contracts.PricingQuoteRequest
import com.pizzora.contracts.PricingQuoteResponse
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.RealtimeConnectionState
import com.pizzora.contracts.ReplaceCartRequest
import com.pizzora.contracts.RestaurantSettingsDto
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UpdateRestaurantSettingsRequest
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.ValidateCartRequest
import com.pizzora.contracts.WsEventsFrame
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class Phase19EndToEndRobolectricTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

  private lateinit var db: PizzoraDatabase
  private val viewModelStore = ViewModelStore()
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val prodId = "11111111-1111-7111-8111-111111111191"
  private val varMedId = "22222222-2222-7222-8222-222222222291"
  private val crustId = "33333333-3333-7333-8333-333333333391"
  private val toppingId = "44444444-4444-7444-8444-444444444491"

  private val sampleMenu = PublicMenuResponse(
    categories = listOf(CategoryDto("cat-19", "Woodfire Classics", 1, true, "2026-11-14T08:00:00Z")),
    products = listOf(
      ProductDto(
        id = prodId,
        categoryId = "cat-19",
        categoryName = "Woodfire Classics",
        name = "Margherita Verace",
        description = "San Marzano DOP, Fior di Latte & Basil",
        isVeg = true,
        isActive = true,
        isTaxable = true,
        variants = listOf(
          ProductVariantDto(varMedId, prodId, SizeCode.MEDIUM, 44_900L, true),
        ),
      ),
    ),
    crusts = listOf(
      CrustOptionDto(crustId, "Neapolitan Sourdough", 5_100L, true),
    ),
    toppings = listOf(
      ToppingDto(toppingId, "Extra Basil", true, 0L, true),
    ),
    gstBps = 500,
    storeName = "PIZZORA Indiranagar",
    isAcceptingOrders = true,
  )

  private val customerProfile = UserProfileDto(
    id = "cust-19",
    email = "elena@pizzora.local",
    emailVerified = true,
    fullName = "Elena Rossi",
    phone = "9811110019",
    role = UserRole.CUSTOMER,
    isActive = true,
  )

  private val kdsProfile = UserProfileDto(
    id = "kds-19",
    email = "chef@pizzora.local",
    emailVerified = true,
    fullName = "Chef Matteo",
    phone = "9811110029",
    role = UserRole.KITCHEN_STAFF,
    isActive = true,
  )

  private val ownerProfile = UserProfileDto(
    id = "owner-19",
    email = "owner@pizzora.local",
    emailVerified = true,
    fullName = "Arjun Owner",
    phone = "9811110039",
    role = UserRole.OWNER,
    isActive = true,
  )

  private class InMemoryTokenStorage : TokenStorage {
    private val _session = MutableStateFlow<AuthSession?>(null)
    private val _muted = MutableStateFlow(false)
    override val sessionFlow: StateFlow<AuthSession?> = _session.asStateFlow()
    override fun currentSession(): AuthSession? = _session.value
    override suspend fun saveSession(session: AuthSession) {
      _session.value = session
    }
    override suspend fun clearSession() {
      _session.value = null
    }
    override fun observeKdsSoundMuted(): Flow<Boolean> = _muted.asStateFlow()
    override suspend fun setKdsSoundMuted(muted: Boolean) {
      _muted.value = muted
    }
  }

  private class FakeRealtimeEventStream : RealtimeEventStream {
    private val _connectionState = MutableStateFlow(RealtimeConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<RealtimeConnectionState> = _connectionState.asStateFlow()
    private val _frames = MutableSharedFlow<WsEventsFrame>(extraBufferCapacity = 16)
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
      if (seq > _lastHighWaterSeq.value) _lastHighWaterSeq.value = seq
    }
  }

  private class RecordingChimePlayer : KdsChimePlayer {
    var count = 0
    override fun playNewTicketChime() {
      count += 1
    }
  }

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, PizzoraDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() {
    viewModelStore.clear()
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    db.close()
  }

  private inline fun <reified T : ViewModel> createVm(noinline provider: () -> T): T {
    val factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = provider() as VM
    }
    return ViewModelProvider(viewModelStore, factory)["vm_${T::class.java.simpleName}", T::class.java]
  }

  @Test
  fun full_multi_role_end_to_end_journey_customer_to_kds_to_owner() {
    val serverCartItems = mutableListOf<CartItemViewDto>()
    val savedAddresses = mutableListOf(
      AddressDto(
        id = "addr-19-1",
        label = "Home",
        line1 = "100ft Road Indiranagar",
        line2 = "Apt 4B",
        landmark = "Near Metro",
        pincode = "560038",
        city = "Bengaluru",
        isDefault = true,
      ),
    )
    val orders = mutableMapOf<String, OrderDetailDto>()
    val kdsTickets = mutableMapOf<String, KdsTicketDto>()
    var storeSettings = RestaurantSettingsDto(
      storeName = "PIZZORA Indiranagar",
      isAcceptingOrders = true,
      gstBps = 500,
      defaultPrepMins = 20,
      updatedAt = "2026-11-14T09:00:00Z",
    )
    val coupon = CustomerCouponDto(
      id = "cpn-19",
      code = "PIZZORA20",
      discountType = DiscountType.PERCENT_BPS,
      discountValue = 2000L,
      maxDiscountPaise = 20_000L,
      minOrderPaise = 25_000L,
      maxUses = 100,
      maxUsesPerUser = 1,
      userUsageCount = 0,
      remainingGlobalUses = 100,
      remainingUserUses = 1,
      startsAt = "2026-01-01T00:00:00Z",
      endsAt = "2027-01-01T00:00:00Z",
      isEligible = true,
      estimatedDiscountPaise = 10_000L,
    )

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      val headers = headersOf(HttpHeaders.ContentType, "application/json")
      when {
        path == "/api/v1/auth/login" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<LoginRequest>(bodyAsString(request.body))
          val profile = when (req.email.lowercase()) {
            customerProfile.email -> customerProfile
            kdsProfile.email -> kdsProfile
            ownerProfile.email -> ownerProfile
            else -> null
          }
          if (profile == null) {
            respond("""{"code":"INVALID_CREDENTIALS","message":"Invalid login"}""", HttpStatusCode.Unauthorized, headers)
          } else {
            respond(
              json.encodeToString(
                AuthTokensResponse(
                  accessToken = "jwt-${profile.id}",
                  refreshToken = "ref-${profile.id}",
                  expiresInSeconds = 900L,
                  user = profile,
                ),
              ),
              HttpStatusCode.OK,
              headers,
            )
          }
        }
        path == "/api/v1/auth/logout" && request.method == HttpMethod.Post -> {
          respond(json.encodeToString(MessageResponse("Signed out")), HttpStatusCode.OK, headers)
        }
        path == "/api/v1/me" && request.method == HttpMethod.Get -> {
          respond(json.encodeToString(customerProfile), HttpStatusCode.OK, headers)
        }
        path == "/api/v1/catalog/menu" && request.method == HttpMethod.Get -> {
          respond(json.encodeToString(sampleMenu), HttpStatusCode.OK, headers)
        }
        path == "/api/v1/cart" && request.method == HttpMethod.Get -> {
          respond(
            json.encodeToString(
              CartResponseDto(
                cartId = "cart-19",
                items = serverCartItems.toList(),
                subtotalPaise = serverCartItems.sumOf { it.lineTotalPaise },
                totalItemCount = serverCartItems.sumOf { it.quantity },
                allAvailable = true,
                unavailableCount = 0,
                updatedAt = "2026-11-14T10:00:00Z",
              ),
            ),
            HttpStatusCode.OK,
            headers,
          )
        }
        path == "/api/v1/cart" && request.method == HttpMethod.Put -> {
          val req = json.decodeFromString<ReplaceCartRequest>(bodyAsString(request.body))
          serverCartItems.clear()
          req.items.forEachIndexed { index, input ->
            serverCartItems += CartItemViewDto(
              id = "ci-19-$index",
              productId = prodId,
              productName = "Margherita Verace",
              isVeg = true,
              variantId = input.variantId,
              sizeCode = SizeCode.MEDIUM,
              basePricePaise = 44_900L,
              crustId = crustId,
              crustName = "Neapolitan Sourdough",
              crustExtraPricePaise = 5_100L,
              toppings = if (input.toppingIds.isNotEmpty()) {
                listOf(CartToppingViewDto(toppingId, "Extra Basil", true, 0L, true))
              } else {
                emptyList()
              },
              quantity = input.quantity,
              unitPricePaise = 50_000L,
              lineTotalPaise = 50_000L * input.quantity,
              isAvailable = true,
              issues = emptyList(),
            )
          }
          respond(
            json.encodeToString(
              CartResponseDto(
                cartId = "cart-19",
                items = serverCartItems.toList(),
                subtotalPaise = serverCartItems.sumOf { it.lineTotalPaise },
                totalItemCount = serverCartItems.sumOf { it.quantity },
                allAvailable = true,
                unavailableCount = 0,
                updatedAt = "2026-11-14T10:01:00Z",
              ),
            ),
            HttpStatusCode.OK,
            headers,
          )
        }
        path == "/api/v1/cart/validate" && request.method == HttpMethod.Post -> {
          json.decodeFromString<ValidateCartRequest>(bodyAsString(request.body))
          respond(
            json.encodeToString(
              CartResponseDto(
                cartId = "cart-19",
                items = serverCartItems.toList(),
                subtotalPaise = serverCartItems.sumOf { it.lineTotalPaise },
                totalItemCount = serverCartItems.sumOf { it.quantity },
                allAvailable = true,
                unavailableCount = 0,
                updatedAt = "2026-11-14T10:01:00Z",
              ),
            ),
            HttpStatusCode.OK,
            headers,
          )
        }
        path == "/api/v1/coupons/available" && request.method == HttpMethod.Get -> {
          respond(json.encodeToString(AvailableCouponsResponse(listOf(coupon))), HttpStatusCode.OK, headers)
        }
        path == "/api/v1/pricing/quote" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<PricingQuoteRequest>(bodyAsString(request.body))
          val lines = req.items.map { PricedLineInput(50_000L, it.quantity, isTaxable = true) }
          val applied = req.couponCode?.trim()?.uppercase()?.takeIf { it == coupon.code }
          val rule = if (applied != null) {
            CouponCalculationSpec(coupon.discountType, coupon.discountValue, coupon.maxDiscountPaise)
          } else {
            null
          }
          val deliveryFee = if (req.fulfillmentType == FulfillmentType.DELIVERY) 4_000L else 0L
          val breakdown = PricingEngine.calculateQuote(
            lines = lines,
            gstBps = storeSettings.gstBps,
            deliveryFeePaise = deliveryFee,
            coupon = rule,
          )
          respond(
            json.encodeToString(
              PricingQuoteResponse(
                items = serverCartItems.toList(),
                fulfillmentType = req.fulfillmentType,
                breakdown = breakdown,
                appliedCoupon = if (applied != null) {
                  AppliedCouponSummaryDto(coupon.id, coupon.code, coupon.discountType, coupon.discountValue, breakdown.discountPaise)
                } else {
                  null
                },
                deliveryZone = if (req.fulfillmentType == FulfillmentType.DELIVERY) {
                  DeliveryZoneQuoteDto("560038", "Indiranagar", 20_000L, 4_000L, 30)
                } else {
                  null
                },
                isAcceptingOrders = storeSettings.isAcceptingOrders,
                gstBps = storeSettings.gstBps,
              ),
            ),
            HttpStatusCode.OK,
            headers,
          )
        }
        path == "/api/v1/delivery-zones/check" && request.method == HttpMethod.Get -> {
          val pin = request.url.parameters["pincode"].orEmpty()
          respond(
            json.encodeToString(
              DeliveryZoneCheckResponse(
                pincode = pin,
                isServiceable = true,
                zone = DeliveryZoneDto(
                  id = "zone-p19",
                  pincode = pin,
                  areaName = "Indiranagar",
                  minOrderPaise = 20_000L,
                  deliveryFeePaise = 4_000L,
                  estimatedMins = 30,
                  isActive = true,
                ),
                message = "Delivers to $pin in ~30 mins.",
              ),
            ),
            HttpStatusCode.OK,
            headers,
          )
        }
        path == "/api/v1/me/addresses" && request.method == HttpMethod.Get -> {
          respond(json.encodeToString(savedAddresses.toList()), HttpStatusCode.OK, headers)
        }
        path == "/api/v1/checkout" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<CheckoutRequest>(bodyAsString(request.body))
          val addr = savedAddresses.firstOrNull { it.id == req.addressId }
          val lines = req.items.map { PricedLineInput(50_000L, it.quantity, isTaxable = true) }
          val rule = if (req.couponCode?.equals(coupon.code, ignoreCase = true) == true) {
            CouponCalculationSpec(coupon.discountType, coupon.discountValue, coupon.maxDiscountPaise)
          } else {
            null
          }
          val deliveryFee = if (req.fulfillmentType == FulfillmentType.DELIVERY) 4_000L else 0L
          val breakdown = PricingEngine.calculateQuote(lines, storeSettings.gstBps, deliveryFee, rule)
          val orderId = "ord-19-1"
          val createdAt = "2026-11-14T10:05:00Z"
          val order = OrderDetailDto(
            id = orderId,
            orderNumber = "PZ-20261114-1901",
            customerId = customerProfile.id,
            fulfillmentType = req.fulfillmentType,
            scheduleType = req.scheduleType,
            scheduledSlotId = req.scheduledSlotId,
            deliveryAddress = addr?.let {
              DeliveryAddressSnapshotDto(
                addressId = it.id,
                label = it.label,
                line1 = it.line1,
                line2 = it.line2,
                landmark = it.landmark,
                pincode = it.pincode,
                city = it.city,
              )
            },
            status = OrderStatus.PLACED,
            version = 1L,
            subtotalPaise = breakdown.subtotalPaise,
            discountPaise = breakdown.discountPaise,
            deliveryFeePaise = breakdown.deliveryFeePaise,
            taxPaise = breakdown.taxPaise,
            cgstPaise = breakdown.cgstPaise,
            sgstPaise = breakdown.sgstPaise,
            totalPaise = breakdown.totalPaise,
            gstBpsSnapshot = storeSettings.gstBps,
            couponCodeSnapshot = req.couponCode,
            isPriority = false,
            createdAt = createdAt,
            items = listOf(
              OrderItemDto(
                id = "oi-19-1",
                productId = prodId,
                productName = "Margherita Verace",
                variantSize = "MEDIUM",
                crustName = "Neapolitan Sourdough",
                toppings = listOf("Extra Basil"),
                unitPricePaise = 50_000L,
                quantity = req.items.sumOf { it.quantity },
                lineTotalPaise = breakdown.subtotalPaise,
              ),
            ),
            statusHistory = listOf(
              OrderStatusHistoryDto(
                id = "osh-19-1",
                fromStatus = null,
                toStatus = OrderStatus.PLACED,
                changedBy = customerProfile.id,
                reason = "Order placed via COD",
                createdAt = createdAt,
              ),
            ),
            payment = PaymentSummaryDto(
              id = "pay-19-1",
              method = PaymentMethod.COD,
              status = PaymentStatus.COD_PENDING,
              amountPaise = breakdown.totalPaise,
              capturedPaise = 0L,
              refundedPaise = 0L,
              latestAttempt = PaymentAttemptSummaryDto(
                id = "pa-19-1",
                attemptNumber = 1,
                method = PaymentMethod.COD,
                status = PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT,
                amountPaise = breakdown.totalPaise,
                idempotencyReceipt = "rcpt-19-1",
                createdAt = createdAt,
              ),
            ),
          )
          orders[orderId] = order
          kdsTickets[orderId] = KdsTicketDto(
            id = orderId,
            orderNumber = order.orderNumber,
            customerName = customerProfile.fullName,
            customerPhone = customerProfile.phone,
            fulfillmentType = order.fulfillmentType,
            scheduleType = order.scheduleType,
            status = OrderStatus.PLACED,
            version = 1L,
            isPriority = false,
            paymentMethod = PaymentMethod.COD,
            paymentStatus = PaymentStatus.COD_PENDING,
            totalPaise = order.totalPaise,
            deliveryArea = "${addr?.line1 ?: ""} • ${addr?.pincode ?: ""}",
            createdAt = createdAt,
            items = listOf(
              KdsTicketItemDto(
                id = "oi-19-1",
                productId = prodId,
                productName = "Margherita Verace",
                variantSize = "MEDIUM",
                crustName = "Neapolitan Sourdough",
                toppings = listOf("Extra Basil"),
                quantity = 1,
              ),
            ),
          )
          serverCartItems.clear()
          respond(json.encodeToString(CheckoutResponse(order = order, idempotentReplay = false)), HttpStatusCode.Created, headers)
        }
        path == "/api/v1/orders" && request.method == HttpMethod.Get -> {
          val summaries = orders.values.map { d ->
            OrderSummaryDto(
              id = d.id,
              orderNumber = d.orderNumber,
              fulfillmentType = d.fulfillmentType,
              scheduleType = d.scheduleType,
              status = d.status,
              version = d.version,
              totalPaise = d.totalPaise,
              itemCount = d.items.sumOf { it.quantity },
              paymentMethod = d.payment.method,
              paymentStatus = d.payment.status,
              isPriority = d.isPriority,
              createdAt = d.createdAt,
            )
          }
          respond(json.encodeToString(OrderListResponse(summaries)), HttpStatusCode.OK, headers)
        }
        path.startsWith("/api/v1/orders/") && request.method == HttpMethod.Get -> {
          val id = path.removePrefix("/api/v1/orders/")
          val detail = orders[id]
          if (detail != null) respond(json.encodeToString(detail), HttpStatusCode.OK, headers)
          else respond("{}", HttpStatusCode.NotFound, headers)
        }
        path == "/api/v1/kds/tickets" && request.method == HttpMethod.Get -> {
          respond(
            json.encodeToString(
              KdsTicketsResponse(
                tickets = kdsTickets.values.toList(),
                serverNow = "2026-11-14T10:08:00Z",
                prepSlaMinutes = storeSettings.defaultPrepMins,
                priorityCount = kdsTickets.values.count { it.isPriority },
              ),
            ),
            HttpStatusCode.OK,
            headers,
          )
        }
        path.startsWith("/api/v1/kds/orders/") && request.method == HttpMethod.Post -> {
          val rest = path.removePrefix("/api/v1/kds/orders/")
          val orderId = rest.substringBefore("/")
          val action = rest.substringAfter("/")
          val nextStatus = when (action) {
            "ack" -> OrderStatus.ACCEPTED
            "start-prep" -> OrderStatus.PREPARING
            "ready" -> OrderStatus.READY
            "dispatch" -> OrderStatus.OUT_FOR_DELIVERY
            "complete" -> OrderStatus.COMPLETED
            else -> error("Unknown KDS action $action")
          }
          val existingTicket = kdsTickets.getValue(orderId)
          val updatedTicket = existingTicket.copy(status = nextStatus, version = existingTicket.version + 1L)
          kdsTickets[orderId] = updatedTicket
          orders[orderId]?.let { ord ->
            orders[orderId] = ord.copy(status = nextStatus, version = updatedTicket.version)
          }
          respond(
            json.encodeToString(
              KdsTicketActionResponse(
                ticket = updatedTicket,
                serverNow = "2026-11-14T10:10:00Z",
                prepSlaMinutes = storeSettings.defaultPrepMins,
              ),
            ),
            HttpStatusCode.OK,
            headers,
          )
        }
        path == "/api/v1/owner/dashboard" && request.method == HttpMethod.Get -> {
          val summaries = orders.values.map { d ->
            OrderSummaryDto(
              id = d.id,
              orderNumber = d.orderNumber,
              fulfillmentType = d.fulfillmentType,
              scheduleType = d.scheduleType,
              status = d.status,
              version = d.version,
              totalPaise = d.totalPaise,
              itemCount = d.items.sumOf { it.quantity },
              paymentMethod = d.payment.method,
              paymentStatus = d.payment.status,
              isPriority = d.isPriority,
              createdAt = d.createdAt,
            )
          }
          respond(
            json.encodeToString(
              OwnerDashboardResponse(
                todayRevenuePaise = orders.values.sumOf { it.totalPaise },
                todayOrderCount = orders.size,
                activeOrderCount = orders.values.count { it.status == OrderStatus.READY || it.status == OrderStatus.PREPARING },
                pendingPaymentCount = 0,
                completedTodayCount = orders.values.count { it.status == OrderStatus.COMPLETED },
                lowStockCount = 0,
                lowStockItems = emptyList<InventoryItemDto>(),
                recentOrders = summaries,
                settings = storeSettings,
                serverNow = "2026-11-14T10:12:00Z",
              ),
            ),
            HttpStatusCode.OK,
            headers,
          )
        }
        path == "/api/v1/owner/settings" && request.method == HttpMethod.Put -> {
          val req = json.decodeFromString<UpdateRestaurantSettingsRequest>(bodyAsString(request.body))
          storeSettings = storeSettings.copy(
            storeName = req.storeName ?: storeSettings.storeName,
            isAcceptingOrders = req.isAcceptingOrders ?: storeSettings.isAcceptingOrders,
            gstBps = req.gstBps ?: storeSettings.gstBps,
            defaultPrepMins = req.defaultPrepMins ?: storeSettings.defaultPrepMins,
            updatedAt = "2026-11-14T10:15:00Z",
          )
          respond(json.encodeToString(storeSettings), HttpStatusCode.OK, headers)
        }
        else -> error("Unexpected API route hit in E2E test: ${request.method.value} $path")
      }
    }

    val tokenStorage = InMemoryTokenStorage()
    val apiClient = PizzoraApiClient("http://localhost:8080", tokenStorage, engine)
    val realtimeStream = FakeRealtimeEventStream()
    val chimePlayer = RecordingChimePlayer()

    val authVm = createVm { AuthViewModel(NetworkAuthRepository(apiClient, tokenStorage)) }
    val catalogVm = createVm { CustomerCatalogViewModel(NetworkCatalogRepository(apiClient, db.catalogCacheDao())) }
    val cartVm = createVm { CartViewModel(NetworkCartRepository(apiClient, db.cartDao())) }
    val quoteVm = createVm { CheckoutQuoteViewModel(NetworkPricingRepository(apiClient)) }
    val checkoutVm = createVm { CheckoutViewModel(NetworkOrderRepository(apiClient)) }
    val ordersVm = createVm { OrdersViewModel(NetworkOrderRepository(apiClient), realtimeEventStream = realtimeStream) }
    val kdsVm = createVm {
      KdsViewModel(
        kdsRepository = NetworkKdsRepository(apiClient),
        tokenStorage = tokenStorage,
        chimePlayer = chimePlayer,
        realtimeEventStream = realtimeStream,
        enableTicker = false,
      )
    }
    val ownerVm = createVm {
      OwnerViewModel(NetworkOwnerRepository(apiClient, ProductImageUploader(apiClient, ImageCompressor())))
    }
    val profileVm = createVm { ProfileViewModel(NetworkProfileRepository(apiClient)) }

    composeTestRule.setContent {
      PizzoraApp(
        authViewModel = authVm,
        catalogViewModel = catalogVm,
        cartViewModel = cartVm,
        checkoutQuoteViewModel = quoteVm,
        checkoutViewModel = checkoutVm,
        ordersViewModel = ordersVm,
        kdsViewModel = kdsVm,
        ownerViewModel = ownerVm,
        profileViewModel = profileVm,
      )
    }

    // -----------------------------------------------------------------------
    // 1. CUSTOMER JOURNEY: Login -> Menu -> Customizer -> Room Cart -> Coupon -> Checkout -> Orders
    // -----------------------------------------------------------------------
    composeTestRule.onNodeWithTag("login_email_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("login_email_input").performTextInput(customerProfile.email)
    composeTestRule.onNodeWithTag("login_password_input").performTextInput("CustPass123!")
    composeTestRule.onNodeWithTag("login_submit_button").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("customer_shell_root").fetchSemanticsNodes().isNotEmpty() &&
        composeTestRule.onAllNodesWithTag("product_card_$prodId").fetchSemanticsNodes().isNotEmpty()
    }

    // Open Pizza Customizer & add 1 customized pizza to Room Cart
    composeTestRule.onNodeWithTag("menu_lazy_column").performScrollToNode(hasTestTag("customize_product_button_$prodId"))
    composeTestRule.onNodeWithTag("customize_product_button_$prodId").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("pizza_customizer_sheet").assertIsDisplayed()
    composeTestRule.onNodeWithTag("size_option_MEDIUM").performClick()
    composeTestRule.onNodeWithTag("add_to_cart_button").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("cart_tab_badge", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("cart_tab_badge", useUnmergedTree = true).assertTextContains("1")

    // Switch to Cart Tab -> apply PIZZORA20 -> Proceed to Checkout
    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("cart_lazy_column").fetchSemanticsNodes().isNotEmpty() &&
        quoteVm.uiState.value.quote != null
    }
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("promo_coupon_card"))
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("coupon_code_input").performTextInput("PIZZORA20")
    composeTestRule.onNodeWithTag("apply_coupon_button").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("applied_coupon_code_text").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("applied_coupon_code_text").assertTextContains("PIZZORA20")

    composeTestRule.mainClock.advanceTimeBy(5_000)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("proceed_to_checkout_button").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("checkout_screen").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      !checkoutVm.uiState.value.isLoadingAddresses &&
        composeTestRule.onAllNodesWithTag("address_card_addr-19-1").fetchSemanticsNodes().isNotEmpty()
    }

    // Select COD & place order -> auto-routes to Orders Detail
    composeTestRule.onNodeWithTag("checkout_lazy_column").performScrollToNode(hasTestTag("payment_method_cod"))
    composeTestRule.onNodeWithTag("payment_method_cod").performClick()
    composeTestRule.waitUntil(timeoutMillis = 8_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      quoteVm.uiState.value.isQuoteFresh
    }
    composeTestRule.onNodeWithTag("place_order_button").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("order_detail_screen").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("order_detail_number_text").assertTextContains("PZ-20261114-1901")
    composeTestRule.onNodeWithTag("order_detail_status_badge").assertIsDisplayed()
    assertEquals(1, orders.size)

    // Decision #5: Customer Sign Out via Profile tab with confirmation modal -> back to LoginScreen
    composeTestRule.onNodeWithTag("shell_sign_out_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("customer_tab_profile").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("profile_sign_out_button").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("profile_lazy_column").performScrollToNode(hasTestTag("profile_sign_out_button"))
    composeTestRule.onNodeWithTag("profile_sign_out_button").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("confirm_sign_out_button").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("confirm_sign_out_button").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("login_email_input").fetchSemanticsNodes().isNotEmpty()
    }
    assertNull(tokenStorage.currentSession())

    // -----------------------------------------------------------------------
    // 2. KDS STAFF JOURNEY: Login -> KDS Shell -> Advance Ticket PLACED -> ACCEPTED -> PREPARING -> READY -> Sign Out
    // -----------------------------------------------------------------------
    composeTestRule.onNodeWithTag("login_email_input").performTextClearance()
    composeTestRule.onNodeWithTag("login_email_input").performTextInput(kdsProfile.email)
    composeTestRule.onNodeWithTag("login_password_input").performTextClearance()
    composeTestRule.onNodeWithTag("login_password_input").performTextInput("ChefPass123!")
    composeTestRule.onNodeWithTag("login_submit_button").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("kds_shell_root").fetchSemanticsNodes().isNotEmpty() &&
        composeTestRule.onAllNodesWithTag("kds_ticket_card_ord-19-1").fetchSemanticsNodes().isNotEmpty()
    }

    // Advance PLACED -> ACCEPTED -> PREPARING -> READY
    composeTestRule.onNodeWithTag("kds_primary_action_ord-19-1").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      orders["ord-19-1"]?.status == OrderStatus.ACCEPTED
    }
    composeTestRule.onNodeWithTag("kds_primary_action_ord-19-1").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      orders["ord-19-1"]?.status == OrderStatus.PREPARING
    }
    composeTestRule.onNodeWithTag("kds_primary_action_ord-19-1").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      orders["ord-19-1"]?.status == OrderStatus.READY
    }
    assertEquals(OrderStatus.READY, kdsTickets["ord-19-1"]?.status)

    // KDS Sign Out -> back to LoginScreen
    composeTestRule.onNodeWithTag("shell_sign_out_button").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("login_email_input").fetchSemanticsNodes().isNotEmpty()
    }

    // -----------------------------------------------------------------------
    // 3. OWNER JOURNEY: Login -> Dashboard KPIs -> Toggle Store Open Switch -> Sign Out
    // -----------------------------------------------------------------------
    composeTestRule.onNodeWithTag("login_email_input").performTextClearance()
    composeTestRule.onNodeWithTag("login_email_input").performTextInput(ownerProfile.email)
    composeTestRule.onNodeWithTag("login_password_input").performTextClearance()
    composeTestRule.onNodeWithTag("login_password_input").performTextInput("OwnerPass123!")
    composeTestRule.onNodeWithTag("login_submit_button").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("owner_shell_root").fetchSemanticsNodes().isNotEmpty() &&
        composeTestRule.onAllNodesWithTag("kpi_today_revenue").fetchSemanticsNodes().isNotEmpty()
    }

    val expectedRevenue = Money.formatInr(orders.getValue("ord-19-1").totalPaise)
    composeTestRule.onNodeWithTag("kpi_today_revenue").assertIsDisplayed()
    composeTestRule.onNodeWithTag("kpi_today_revenue").assert(hasAnyDescendant(hasText(expectedRevenue)))
    assertNotNull(ownerVm.uiState.value.dashboard)

    // Toggle Store Open switch -> persists isAcceptingOrders = false
    composeTestRule.onNodeWithTag("owner_store_open_switch").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      !storeSettings.isAcceptingOrders
    }
    assertFalse(storeSettings.isAcceptingOrders)

    // Owner Sign Out -> back to LoginScreen
    composeTestRule.onNodeWithTag("shell_sign_out_button").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("login_email_input").fetchSemanticsNodes().isNotEmpty()
    }
    assertTrue(tokenStorage.currentSession() == null)
  }

  private fun bodyAsString(content: OutgoingContent): String = when (content) {
    is TextContent -> content.text
    else -> error("Unsupported outgoing content type: ${content::class.java.simpleName}")
  }
}

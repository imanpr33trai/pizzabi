package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.auth.AuthRepository
import com.example.auth.AuthViewModel
import com.example.cart.CartViewModel
import com.example.cart.NetworkCartRepository
import com.example.catalog.CustomerCatalogViewModel
import com.example.catalog.NetworkCatalogRepository
import com.example.checkout.CheckoutQuoteViewModel
import com.example.checkout.CheckoutViewModel
import com.example.checkout.NetworkPricingRepository
import com.example.core.database.PizzoraDatabase
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.navigation.OwnerRoleShell
import com.example.navigation.PizzoraApp
import com.example.orders.NetworkOrderRepository
import com.example.orders.OrdersViewModel
import com.example.owner.NetworkOwnerRepository
import com.example.owner.OwnerOperationsSection
import com.example.owner.OwnerViewModel
import com.example.profile.NetworkProfileRepository
import com.example.profile.ProfileViewModel
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.AvailableSlotsResponse
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.CouponDto
import com.pizzora.contracts.CreateDeliveryZoneRequest
import com.pizzora.contracts.CreateScheduledSlotRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.DeliveryAddressSnapshotDto
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.DeliveryZoneDto
import com.pizzora.contracts.DeliveryZoneQuoteDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.InventoryLedgerEntryDto
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderItemDto
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.OwnerDashboardResponse
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
import com.pizzora.contracts.RestaurantSettingsDto
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.ScheduledSlotDto
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UpdateDeliveryZoneRequest
import com.pizzora.contracts.UpdateProfileRequest
import com.pizzora.contracts.UpdateScheduledSlotRequest
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class Phase15ZonesAddressesSlotsRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var db: PizzoraDatabase
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private val customerSession = AuthSession(
    accessToken = "cust-jwt",
    refreshToken = "cust-refresh",
    user = UserProfileDto(
      id = "cust-p15-1",
      email = "priya@pizzora.local",
      emailVerified = true,
      fullName = "Priya Customer",
      phone = "9876543210",
      role = UserRole.CUSTOMER,
      isActive = true,
    ),
  )

  private val ownerSession = AuthSession(
    accessToken = "owner-jwt",
    refreshToken = "owner-refresh",
    user = UserProfileDto(
      id = "owner-p15-1",
      email = "owner@pizzora.local",
      emailVerified = true,
      fullName = "Arjun Owner",
      phone = "9811110000",
      role = UserRole.OWNER,
      isActive = true,
    ),
  )

  private val sampleMenu = PublicMenuResponse(
    categories = listOf(CategoryDto("cat-1", "Classics", 1, true, "2026-11-14T10:00:00Z")),
    products = listOf(
      ProductDto(
        id = "prod-1",
        categoryId = "cat-1",
        categoryName = "Classics",
        name = "Margherita Verace",
        description = "San Marzano DOP & Fior di Latte",
        isVeg = true,
        isActive = true,
        isTaxable = true,
        variants = listOf(ProductVariantDto("var-1", "prod-1", SizeCode.MEDIUM, 44_900L, true)),
      ),
    ),
    crusts = listOf(CrustOptionDto("crust-1", "Hand-Tossed", 5_100L, true)),
    toppings = listOf(ToppingDto("top-1", "Extra Basil", true, 0L, true)),
    gstBps = 500,
    storeName = "PIZZORA Artisanal Kitchen",
    isAcceptingOrders = true,
  )

  private class InMemoryTokenStorage(initial: AuthSession?) : TokenStorage {
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
    override fun observeKdsSoundMuted(): Flow<Boolean> = _muted.asStateFlow()
    override suspend fun setKdsSoundMuted(muted: Boolean) {
      _muted.value = muted
    }
  }

  private class SignedInAuthRepository(session: AuthSession) : AuthRepository {
    private val flow = MutableStateFlow<AuthSession?>(session)
    private val muted = MutableStateFlow(false)
    override val sessionFlow: StateFlow<AuthSession?> = flow.asStateFlow()
    override fun observeKdsSoundMuted(): Flow<Boolean> = muted.asStateFlow()
    override suspend fun login(email: String, password: String): AuthSession = flow.value!!
    override suspend fun register(email: String, password: String, fullName: String, phone: String): String =
      "Registered"
    override suspend fun verifyEmail(token: String): String = "Verified"
    override suspend fun resendVerification(email: String): String = "Sent"
    override suspend fun forgotPassword(email: String): String = "Sent"
    override suspend fun resetPassword(token: String, newPassword: String): String = "Reset"
    override suspend fun logout() {
      flow.value = null
    }
    override suspend fun setKdsSoundMuted(muted: Boolean) {
      this.muted.value = muted
    }
  }

  @Before
  fun setupDb() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, PizzoraDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDownDb() {
    db.close()
  }

  @Test
  fun profileAddressBookCrudAndStandalonePincodeChecker() {
    var profile = customerSession.user
    val addresses = mutableListOf(
      AddressDto("addr-1", "Home", "12 MG Road", null, "Near Metro", "560001", "Bengaluru", isDefault = true),
    )
    var updatedProfileReq: UpdateProfileRequest? = null

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      when {
        path == "/api/v1/catalog/menu" -> respond(
          content = json.encodeToString(sampleMenu),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/cart" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(CartResponseDto("cart-1", emptyList(), 0L, 0, true, 0, "2026-11-14T10:00:00Z")),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/orders" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(OrderListResponse(emptyList())),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/me" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(profile),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/me" && request.method == HttpMethod.Patch -> {
          val req = json.decodeFromString<UpdateProfileRequest>(readBody(request.body))
          updatedProfileReq = req
          profile = profile.copy(fullName = req.fullName, phone = req.phone)
          respond(
            content = json.encodeToString(profile),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/me/addresses" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(addresses.toList()),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/me/addresses" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<UpsertAddressRequest>(readBody(request.body))
          val created = AddressDto(
            id = "addr-2",
            label = req.label,
            line1 = req.line1,
            line2 = req.line2,
            landmark = req.landmark,
            pincode = req.pincode,
            city = req.city,
            isDefault = req.isDefault,
          )
          if (req.isDefault) {
            addresses.replaceAll { it.copy(isDefault = false) }
          }
          addresses.add(created)
          respond(
            content = json.encodeToString(created),
            status = HttpStatusCode.Created,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/me/addresses/addr-2" && request.method == HttpMethod.Patch -> {
          val req = json.decodeFromString<UpsertAddressRequest>(readBody(request.body))
          if (req.isDefault) {
            addresses.replaceAll { it.copy(isDefault = false) }
          }
          val idx = addresses.indexOfFirst { it.id == "addr-2" }
          val updated = addresses[idx].copy(
            label = req.label,
            line1 = req.line1,
            line2 = req.line2,
            landmark = req.landmark,
            pincode = req.pincode,
            city = req.city,
            isDefault = req.isDefault,
          )
          addresses[idx] = updated
          respond(
            content = json.encodeToString(updated),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/me/addresses/addr-1" && request.method == HttpMethod.Delete -> {
          addresses.removeAll { it.id == "addr-1" }
          if (addresses.isNotEmpty() && addresses.none { it.isDefault }) {
            addresses[0] = addresses[0].copy(isDefault = true)
          }
          respond(content = "", status = HttpStatusCode.NoContent)
        }
        path == "/api/v1/delivery-zones/check" && request.method == HttpMethod.Get -> {
          val pin = request.url.parameters["pincode"].orEmpty()
          val resp = if (pin == "560038") {
            DeliveryZoneCheckResponse(
              pincode = pin,
              isServiceable = true,
              zone = DeliveryZoneDto("z-1", pin, "Indiranagar", 30_000L, 4_000L, 28, true),
              message = "Delivers to Indiranagar (560038) in ~28 mins.",
            )
          } else {
            DeliveryZoneCheckResponse(
              pincode = pin,
              isServiceable = false,
              zone = null,
              message = "Sorry, we do not deliver to pincode $pin yet.",
            )
          }
          respond(
            content = json.encodeToString(resp),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected ${request.method.value} $path")
      }
    }

    val apiClient = PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(customerSession), engine)
    val authVm = AuthViewModel(SignedInAuthRepository(customerSession))
    val catalogVm = CustomerCatalogViewModel(NetworkCatalogRepository(apiClient, db.catalogCacheDao()))
    val cartVm = CartViewModel(NetworkCartRepository(apiClient, db.cartDao()))
    val ordersVm = OrdersViewModel(NetworkOrderRepository(apiClient))
    val profileVm = ProfileViewModel(NetworkProfileRepository(apiClient))

    composeTestRule.setContent {
      PizzoraApp(
        authViewModel = authVm,
        catalogViewModel = catalogVm,
        cartViewModel = cartVm,
        ordersViewModel = ordersVm,
        profileViewModel = profileVm,
      )
    }

    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("customer_tab_profile").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("customer_tab_profile").performClick()

    waitUntilMainLooper {
      profileVm.uiState.value.addresses.size == 1 && !profileVm.uiState.value.isLoading
    }
    composeTestRule.onNodeWithTag("profile_full_name_text").assertTextContains("Priya Customer")

    // Edit profile
    composeTestRule.onNodeWithTag("edit_profile_button").performClick()
    composeTestRule.onNodeWithTag("profile_edit_name_input").performTextClearance()
    composeTestRule.onNodeWithTag("profile_edit_name_input").performTextInput("Priya Rao")
    composeTestRule.onNodeWithTag("profile_edit_save_button").performClick()
    waitUntilMainLooper { updatedProfileReq != null && !profileVm.uiState.value.isSavingProfile }
    assertEquals("Priya Rao", updatedProfileReq?.fullName)
    composeTestRule.onNodeWithTag("profile_full_name_text").assertTextContains("Priya Rao")

    // Standalone pincode checker: serviceable & unserviceable
    composeTestRule.onNodeWithTag("profile_pincode_input").performTextInput("560038")
    composeTestRule.onNodeWithTag("profile_check_pincode_button").performClick()
    waitUntilMainLooper { profileVm.uiState.value.pincodeCheckResult?.pincode == "560038" }
    composeTestRule.onNodeWithTag("profile_pincode_result_banner").assertTextContains("Indiranagar", substring = true)

    composeTestRule.onNodeWithTag("profile_pincode_input").performTextClearance()
    composeTestRule.onNodeWithTag("profile_pincode_input").performTextInput("999999")
    composeTestRule.onNodeWithTag("profile_check_pincode_button").performClick()
    waitUntilMainLooper { profileVm.uiState.value.pincodeCheckResult?.pincode == "999999" }
    assertFalse(profileVm.uiState.value.pincodeCheckResult!!.isServiceable)

    // Add second address -> set as default -> delete first address
    composeTestRule.onNodeWithTag("profile_add_address_button").performClick()
    composeTestRule.onNodeWithTag("profile_addr_label_input").performTextClearance()
    composeTestRule.onNodeWithTag("profile_addr_label_input").performTextInput("Work")
    composeTestRule.onNodeWithTag("profile_addr_line1_input").performTextInput("100 Feet Road")
    composeTestRule.onNodeWithTag("profile_addr_line2_input").performTextInput("Suite 402")
    composeTestRule.onNodeWithTag("profile_addr_landmark_input").performTextInput("Opposite Metro")
    composeTestRule.onNodeWithTag("profile_addr_pincode_input").performTextInput("560038")
    composeTestRule.onNodeWithTag("profile_addr_save_button").performClick()

    waitUntilMainLooper { profileVm.uiState.value.addresses.size == 2 }
    composeTestRule.onNodeWithTag("profile_lazy_column").performScrollToNode(hasTestTag("profile_bottom_spacer"))
    composeTestRule.onNodeWithTag("profile_lazy_column").performScrollToNode(hasTestTag("profile_set_default_addr-2"))
    composeTestRule.onNodeWithTag("profile_set_default_addr-2").performClick()
    waitUntilMainLooper { profileVm.uiState.value.addresses.first { it.id == "addr-2" }.isDefault }

    composeTestRule.onNodeWithTag("profile_lazy_column").performScrollToNode(hasTestTag("profile_delete_address_addr-1"))
    composeTestRule.onNodeWithTag("profile_delete_address_addr-1").performClick()
    waitUntilMainLooper { profileVm.uiState.value.addresses.size == 1 }
    assertTrue(profileVm.uiState.value.addresses.first().isDefault)
  }

  @Test
  fun cartPincodeCheckAndScheduledSlotCheckoutFlow() {
    val todayIst = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
    val savedAddresses = listOf(
      AddressDto("addr-s1", "Home", "42 Indiranagar", null, null, "560038", "Bengaluru", isDefault = true),
    )
    var capturedCheckoutRequest: CheckoutRequest? = null

    val cartItem = CartItemViewDto(
      id = "cart-item-1",
      productId = "prod-1",
      productName = "Margherita Verace",
      isVeg = true,
      variantId = "var-1",
      sizeCode = SizeCode.MEDIUM,
      basePricePaise = 44_900L,
      crustId = "crust-1",
      crustName = "Hand-Tossed",
      crustExtraPricePaise = 5_100L,
      toppings = emptyList(),
      quantity = 1,
      unitPricePaise = 50_000L,
      lineTotalPaise = 50_000L,
      isAvailable = true,
      issues = emptyList(),
    )

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      when {
        path == "/api/v1/catalog/menu" -> respond(
          content = json.encodeToString(sampleMenu),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/cart" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(CartResponseDto("cart-1", listOf(cartItem), 50_000L, 1, true, 0, "2026-11-14T10:00:00Z")),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/cart" && request.method == HttpMethod.Delete -> respond(
          content = "",
          status = HttpStatusCode.NoContent,
        )
        path == "/api/v1/orders" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(OrderListResponse(emptyList())),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/delivery-zones/check" -> {
          val pin = request.url.parameters["pincode"].orEmpty()
          val resp = if (pin == "560038") {
            DeliveryZoneCheckResponse(
              pincode = pin,
              isServiceable = true,
              zone = DeliveryZoneDto("z-1", pin, "Indiranagar", 30_000L, 4_000L, 30, true),
              message = "Delivers to Indiranagar (560038) in ~30 mins.",
            )
          } else {
            DeliveryZoneCheckResponse(
              pincode = pin,
              isServiceable = false,
              zone = null,
              message = "Outside delivery zone for $pin.",
            )
          }
          respond(
            content = json.encodeToString(resp),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/pricing/quote" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<PricingQuoteRequest>(readBody(request.body))
          val zone = if (req.fulfillmentType == FulfillmentType.DELIVERY && req.pincode == "560038") {
            DeliveryZoneQuoteDto("560038", "Indiranagar", 30_000L, 4_000L, 30)
          } else {
            null
          }
          val breakdown = PricingEngine.calculateQuote(
            lines = listOf(PricedLineInput(50_000L, 1, isTaxable = true)),
            gstBps = 500,
            deliveryFeePaise = zone?.deliveryFeePaise ?: 0L,
            coupon = null,
          )
          respond(
            content = json.encodeToString(
              PricingQuoteResponse(
                items = listOf(cartItem),
                fulfillmentType = req.fulfillmentType,
                breakdown = breakdown,
                appliedCoupon = null,
                deliveryZone = zone,
                isAcceptingOrders = true,
                gstBps = 500,
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/me/addresses" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(savedAddresses),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/slots" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(
            AvailableSlotsResponse(
              date = todayIst,
              fulfillmentType = FulfillmentType.DELIVERY,
              timezone = "Asia/Kolkata",
              serverNow = "${todayIst}T11:00:00Z",
              slots = listOf(
                ScheduledSlotDto(
                  id = "slot-past",
                  slotDate = todayIst,
                  fulfillmentType = FulfillmentType.DELIVERY,
                  startTimeIst = "13:00",
                  endTimeIst = "14:00",
                  cutoffAt = "${todayIst}T07:00:00Z",
                  maxCapacity = 5,
                  reservedCount = 1,
                  remainingCapacity = 4,
                  isActive = true,
                  isBookable = false,
                  unavailableReason = "Booking cutoff passed",
                ),
                ScheduledSlotDto(
                  id = "slot-evening",
                  slotDate = todayIst,
                  fulfillmentType = FulfillmentType.DELIVERY,
                  startTimeIst = "19:00",
                  endTimeIst = "20:00",
                  cutoffAt = "${todayIst}T13:00:00Z",
                  maxCapacity = 5,
                  reservedCount = 2,
                  remainingCapacity = 3,
                  isActive = true,
                  isBookable = true,
                  unavailableReason = null,
                ),
              ),
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/checkout" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<CheckoutRequest>(readBody(request.body))
          capturedCheckoutRequest = req
          val detail = OrderDetailDto(
            id = "ord-sched-1",
            orderNumber = "PZ-2026-1501",
            customerId = customerSession.user.id,
            fulfillmentType = req.fulfillmentType,
            scheduleType = req.scheduleType,
            scheduledSlotId = req.scheduledSlotId,
            deliveryAddress = DeliveryAddressSnapshotDto("addr-s1", "Home", "42 Indiranagar", pincode = "560038", city = "Bengaluru"),
            status = OrderStatus.PLACED,
            version = 1L,
            subtotalPaise = 50_000L,
            discountPaise = 0L,
            deliveryFeePaise = 4_000L,
            taxPaise = 2_500L,
            cgstPaise = 1_250L,
            sgstPaise = 1_250L,
            totalPaise = 56_500L,
            gstBpsSnapshot = 500,
            couponCodeSnapshot = null,
            isPriority = false,
            createdAt = "${todayIst}T11:05:00Z",
            items = listOf(OrderItemDto("oi-1", "prod-1", "Margherita Verace", "MEDIUM", "Hand-Tossed", emptyList(), 50_000L, 1, 50_000L)),
            statusHistory = emptyList(),
            payment = PaymentSummaryDto("pay-1", PaymentMethod.COD, PaymentStatus.COD_PENDING, 56_500L, 0L, 0L),
          )
          respond(
            content = json.encodeToString(CheckoutResponse(order = detail, idempotentReplay = false)),
            status = HttpStatusCode.Created,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected ${request.method.value} $path")
      }
    }

    val apiClient = PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(customerSession), engine)
    val authVm = AuthViewModel(SignedInAuthRepository(customerSession))
    val catalogVm = CustomerCatalogViewModel(NetworkCatalogRepository(apiClient, db.catalogCacheDao()))
    val cartVm = CartViewModel(NetworkCartRepository(apiClient, db.cartDao()))
    val quoteVm = CheckoutQuoteViewModel(NetworkPricingRepository(apiClient))
    val checkoutVm = CheckoutViewModel(NetworkOrderRepository(apiClient))
    val ordersVm = OrdersViewModel(NetworkOrderRepository(apiClient))

    composeTestRule.setContent {
      PizzoraApp(
        authViewModel = authVm,
        catalogViewModel = catalogVm,
        cartViewModel = cartVm,
        checkoutQuoteViewModel = quoteVm,
        checkoutViewModel = checkoutVm,
        ordersViewModel = ordersVm,
      )
    }

    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("cart_tab_badge", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    waitUntilMainLooper { quoteVm.uiState.value.quote != null }

    // Unserviceable pincode check in Cart displays error banner
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("delivery_pincode_input"))
    composeTestRule.onNodeWithTag("delivery_pincode_input").performTextInput("560999")
    composeTestRule.onNodeWithTag("check_pincode_button").performClick()
    waitUntilMainLooper { quoteVm.uiState.value.quoteError != null }
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("quote_error_text"))
    composeTestRule.onNodeWithTag("quote_error_text").assertTextContains("Outside delivery zone", substring = true)

    // Serviceable pincode check in Cart clears error and shows Indiranagar
    composeTestRule.onNodeWithTag("cart_lazy_column").performScrollToNode(hasTestTag("delivery_pincode_input"))
    composeTestRule.onNodeWithTag("delivery_pincode_input").performTextClearance()
    composeTestRule.onNodeWithTag("delivery_pincode_input").performTextInput("560038")
    composeTestRule.onNodeWithTag("check_pincode_button").performClick()
    waitUntilMainLooper { quoteVm.uiState.value.quote?.deliveryZone?.pincode == "560038" }

    // Proceed to Checkout -> verify address zone -> switch to SCHEDULED
    composeTestRule.onNodeWithTag("proceed_to_checkout_button").performClick()
    waitUntilMainLooper { checkoutVm.uiState.value.addresses.size == 1 }

    composeTestRule.onNodeWithTag("checkout_lazy_column").performScrollToNode(hasTestTag("check_selected_address_zone_button"))
    composeTestRule.onNodeWithTag("check_selected_address_zone_button").performClick()
    waitUntilMainLooper { checkoutVm.uiState.value.selectedAddressZoneCheck != null }
    composeTestRule.onNodeWithTag("address_zone_status_banner").assertTextContains("Indiranagar", substring = true)

    composeTestRule.onNodeWithTag("checkout_lazy_column").performScrollToNode(hasTestTag("schedule_scheduled_chip"))
    composeTestRule.onNodeWithTag("schedule_scheduled_chip").performClick()
    waitUntilMainLooper { checkoutVm.uiState.value.availableSlots.size == 2 }

    // Past-cutoff slot is disabled, evening slot can be picked
    composeTestRule.onNodeWithTag("checkout_lazy_column").performScrollToNode(hasTestTag("slot_card_slot-past"))
    composeTestRule.onNodeWithTag("slot_card_slot-past").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("checkout_lazy_column").performScrollToNode(hasTestTag("slot_card_slot-evening"))
    composeTestRule.onNodeWithTag("slot_card_slot-evening").performClick()
    assertEquals("slot-evening", checkoutVm.uiState.value.selectedSlotId)

    composeTestRule.onNodeWithTag("place_order_button").performClick()
    waitUntilMainLooper { capturedCheckoutRequest != null }

    assertEquals(ScheduleType.SCHEDULED, capturedCheckoutRequest?.scheduleType)
    assertEquals("slot-evening", capturedCheckoutRequest?.scheduledSlotId)
    assertEquals("addr-s1", capturedCheckoutRequest?.addressId)
  }

  @Test
  fun ownerConsoleCreatesTogglesAndDeletesZonesAndSlots() {
    val todayIst = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
    val zones = mutableListOf(
      DeliveryZoneDto("z-1", "560001", "MG Road", 30_000L, 4_000L, 30, true),
    )
    val slots = mutableListOf(
      ScheduledSlotDto(
        id = "s-1",
        slotDate = todayIst,
        fulfillmentType = FulfillmentType.DELIVERY,
        startTimeIst = "18:00",
        endTimeIst = "19:00",
        cutoffAt = "${todayIst}T12:00:00Z",
        maxCapacity = 5,
        reservedCount = 1,
        remainingCapacity = 4,
        isActive = true,
        isBookable = true,
      ),
    )

    val dashboard = OwnerDashboardResponse(
      todayRevenuePaise = 120_000L,
      todayOrderCount = 3,
      activeOrderCount = 1,
      pendingPaymentCount = 0,
      completedTodayCount = 2,
      lowStockCount = 0,
      lowStockItems = emptyList(),
      recentOrders = emptyList(),
      settings = RestaurantSettingsDto(
        storeName = "PIZZORA",
        isAcceptingOrders = true,
        gstBps = 500,
        defaultPrepMins = 25,
        timezone = "Asia/Kolkata",
        updatedAt = "${todayIst}T08:00:00Z",
      ),
      serverNow = "${todayIst}T11:00:00Z",
    )

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      when {
        path == "/api/v1/owner/dashboard" -> respond(
          content = json.encodeToString(dashboard),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/inventory" -> respond(
          content = json.encodeToString(emptyList<InventoryItemDto>()),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/inventory/ledger" -> respond(
          content = json.encodeToString(emptyList<InventoryLedgerEntryDto>()),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/orders" -> respond(
          content = json.encodeToString(OrderListResponse(emptyList())),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/coupons" -> respond(
          content = json.encodeToString(emptyList<CouponDto>()),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/delivery-zones" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(zones.toList()),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/delivery-zones" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<CreateDeliveryZoneRequest>(readBody(request.body))
          val created = DeliveryZoneDto("z-2", req.pincode, req.areaName, req.minOrderPaise, req.deliveryFeePaise, req.estimatedMins, req.isActive)
          zones.add(created)
          respond(
            content = json.encodeToString(created),
            status = HttpStatusCode.Created,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/owner/delivery-zones/z-2" && request.method == HttpMethod.Patch -> {
          val req = json.decodeFromString<UpdateDeliveryZoneRequest>(readBody(request.body))
          val idx = zones.indexOfFirst { it.id == "z-2" }
          val updated = zones[idx].copy(isActive = req.isActive ?: zones[idx].isActive)
          zones[idx] = updated
          respond(
            content = json.encodeToString(updated),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/owner/delivery-zones/z-1" && request.method == HttpMethod.Delete -> {
          zones.removeAll { it.id == "z-1" }
          respond(content = "", status = HttpStatusCode.NoContent)
        }
        path == "/api/v1/owner/slots" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(slots.toList()),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/slots" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<CreateScheduledSlotRequest>(readBody(request.body))
          val created = ScheduledSlotDto(
            id = "s-2",
            slotDate = req.slotDate,
            fulfillmentType = req.fulfillmentType,
            startTimeIst = req.startTimeIst,
            endTimeIst = req.endTimeIst,
            cutoffAt = "${req.slotDate}T13:00:00Z",
            maxCapacity = req.maxCapacity,
            reservedCount = 0,
            remainingCapacity = req.maxCapacity,
            isActive = req.isActive,
            isBookable = true,
          )
          slots.add(created)
          respond(
            content = json.encodeToString(created),
            status = HttpStatusCode.Created,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/owner/slots/s-2" && request.method == HttpMethod.Patch -> {
          val req = json.decodeFromString<UpdateScheduledSlotRequest>(readBody(request.body))
          val idx = slots.indexOfFirst { it.id == "s-2" }
          val updated = slots[idx].copy(
            maxCapacity = req.maxCapacity ?: slots[idx].maxCapacity,
            remainingCapacity = (req.maxCapacity ?: slots[idx].maxCapacity) - slots[idx].reservedCount,
            isActive = req.isActive ?: slots[idx].isActive,
          )
          slots[idx] = updated
          respond(
            content = json.encodeToString(updated),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected ${request.method.value} $path")
      }
    }

    val apiClient = PizzoraApiClient("http://localhost:8080", InMemoryTokenStorage(ownerSession), engine)
    val ownerVm = OwnerViewModel(
      NetworkOwnerRepository(
        apiClient,
        com.example.core.media.ProductImageUploader(apiClient, com.example.core.media.ImageCompressor()),
      ),
    )

    composeTestRule.setContent {
      PizzoraTheme {
        OwnerRoleShell(
          session = ownerSession,
          ownerViewModel = ownerVm,
          onSignOut = {},
        )
      }
    }

    waitUntilMainLooper { ownerVm.uiState.value.dashboard != null }
    composeTestRule.onNodeWithTag("owner_tab_operations").performClick()
    waitUntilMainLooper { !ownerVm.uiState.value.isLoadingOperations }

    composeTestRule.onNodeWithTag("owner_ops_tab_zones_slots").performClick()
    waitUntilMainLooper {
      ownerVm.uiState.value.operationsSection == OwnerOperationsSection.ZONES_SLOTS &&
        ownerVm.uiState.value.deliveryZones.size == 1 &&
        ownerVm.uiState.value.scheduledSlots.size == 1
    }

    // Create new Delivery Zone
    composeTestRule.onNodeWithTag("owner_add_zone_button").performClick()
    composeTestRule.onNodeWithTag("zone_pincode_input").performTextInput("560038")
    composeTestRule.onNodeWithTag("zone_area_input").performTextInput("Indiranagar")
    composeTestRule.onNodeWithTag("zone_submit_button").performClick()
    waitUntilMainLooper { ownerVm.uiState.value.deliveryZones.size == 2 }

    // Toggle 560038 inactive and delete z-1
    composeTestRule.onNodeWithTag("owner_zone_switch_560038").performClick()
    waitUntilMainLooper { ownerVm.uiState.value.deliveryZones.first { it.id == "z-2" }.isActive.not() }

    composeTestRule.onNodeWithTag("owner_delete_zone_z-1").performClick()
    waitUntilMainLooper { ownerVm.uiState.value.deliveryZones.size == 1 }

    // Create Scheduled Slot & edit capacity
    composeTestRule.onNodeWithTag("owner_add_slot_button").performClick()
    composeTestRule.onNodeWithTag("slot_submit_button").performClick()
    waitUntilMainLooper { ownerVm.uiState.value.scheduledSlots.size == 2 }

    composeTestRule.onNodeWithTag("owner_edit_slot_s-2").performClick()
    composeTestRule.onNodeWithTag("slot_edit_capacity_input").performTextClearance()
    composeTestRule.onNodeWithTag("slot_edit_capacity_input").performTextInput("20")
    composeTestRule.onNodeWithTag("slot_edit_submit_button").performClick()
    waitUntilMainLooper { ownerVm.uiState.value.scheduledSlots.first { it.id == "s-2" }.maxCapacity == 20 }
    assertNotNull(ownerVm.uiState.value.scheduledSlots.firstOrNull { it.id == "s-2" })
  }

  private fun readBody(body: Any): String = when (body) {
    is TextContent -> body.text
    is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
    else -> error("Unexpected body type: ${body::class.simpleName}")
  }

  private fun waitUntilMainLooper(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.waitForIdle()
      if (condition()) return
      if (System.currentTimeMillis() > deadline) {
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for async Robolectric/Compose state")
      }
      Thread.sleep(20)
    }
  }
}

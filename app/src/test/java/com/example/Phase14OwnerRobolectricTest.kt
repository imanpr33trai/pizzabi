package com.example

import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.core.media.ImageCompressor
import com.example.core.media.ProductImageUploader
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.navigation.OwnerRoleShell
import com.example.owner.NetworkOwnerRepository
import com.example.owner.OwnerCatalogSection
import com.example.owner.OwnerViewModel
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.AdjustInventoryRequest
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CouponDto
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.InventoryLedgerEntryDto
import com.pizzora.contracts.InventoryLedgerReason
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderItemDto
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.OwnerDashboardResponse
import com.pizzora.contracts.OwnerRefundRequest
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PaymentSummaryDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.PutRecipeRequest
import com.pizzora.contracts.RecipeBindingDto
import com.pizzora.contracts.RecipeIngredientLineDto
import com.pizzora.contracts.RecipeTargetType
import com.pizzora.contracts.RecipesOverviewResponse
import com.pizzora.contracts.RefundDto
import com.pizzora.contracts.RefundStatus
import com.pizzora.contracts.RestaurantSettingsDto
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.StaffMemberDto
import com.pizzora.contracts.UpdateRestaurantSettingsRequest
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class Phase14OwnerRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val ownerSession = AuthSession(
    accessToken = "owner-jwt",
    refreshToken = "owner-refresh",
    user = UserProfileDto(
      id = "owner-1",
      email = "owner@pizzora.local",
      emailVerified = true,
      fullName = "Arjun Owner",
      phone = "9811110000",
      role = UserRole.OWNER,
      isActive = true,
    ),
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
    override fun observeKdsSoundMuted(): Flow<Boolean> = _muted
    override suspend fun setKdsSoundMuted(muted: Boolean) {
      _muted.value = muted
    }
  }

  @Test
  fun `owner dashboard renders KPIs, toggles store open state, and restocks low inventory`() {
    var settings = RestaurantSettingsDto(
      storeName = "PIZZORA Indiranagar",
      isAcceptingOrders = true,
      gstBps = 500,
      defaultPrepMins = 20,
      updatedAt = "2026-11-14T08:00:00Z",
    )
    var mozzarella = InventoryItemDto(
      ingredientId = "ing-mozz",
      name = "Fior di Latte Mozzarella",
      unit = IngredientUnit.GRAMS,
      lowStockThreshold = 500L,
      isActive = true,
      onHandQty = 300L,
      reservedQty = 0L,
      availableQty = 300L,
      isLowStock = true,
      updatedAt = "2026-11-14T08:00:00Z",
    )
    val recentOrder = OrderSummaryDto(
      id = "ord-1401",
      orderNumber = "PZ-20261114-1401",
      fulfillmentType = FulfillmentType.DELIVERY,
      scheduleType = ScheduleType.ASAP,
      status = OrderStatus.PREPARING,
      version = 2L,
      totalPaise = 89_900L,
      itemCount = 2,
      paymentMethod = PaymentMethod.RAZORPAY,
      paymentStatus = PaymentStatus.CAPTURED,
      isPriority = false,
      createdAt = "2026-11-14T08:05:00Z",
    )

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      when {
        path == "/api/v1/owner/dashboard" && request.method == HttpMethod.Get -> {
          val lowList = if (mozzarella.isLowStock) listOf(mozzarella) else emptyList()
          respond(
            content = json.encodeToString(
              OwnerDashboardResponse(
                todayRevenuePaise = 245_000L,
                todayOrderCount = 6,
                activeOrderCount = 2,
                pendingPaymentCount = 1,
                completedTodayCount = 3,
                lowStockCount = lowList.size,
                lowStockItems = lowList,
                recentOrders = listOf(recentOrder),
                settings = settings,
                serverNow = "2026-11-14T08:10:00Z",
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/owner/settings" && request.method == HttpMethod.Put -> {
          val req = json.decodeFromString<UpdateRestaurantSettingsRequest>((request.body as TextContent).text)
          settings = settings.copy(
            storeName = req.storeName ?: settings.storeName,
            isAcceptingOrders = req.isAcceptingOrders ?: settings.isAcceptingOrders,
            gstBps = req.gstBps ?: settings.gstBps,
            defaultPrepMins = req.defaultPrepMins ?: settings.defaultPrepMins,
            updatedAt = "2026-11-14T08:11:00Z",
          )
          respond(
            content = json.encodeToString(settings),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/owner/inventory/adjust" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<AdjustInventoryRequest>((request.body as TextContent).text)
          val nextQty = mozzarella.onHandQty + req.deltaQty
          mozzarella = mozzarella.copy(
            onHandQty = nextQty,
            availableQty = nextQty,
            isLowStock = nextQty <= mozzarella.lowStockThreshold,
            updatedAt = "2026-11-14T08:12:00Z",
          )
          respond(
            content = json.encodeToString(mozzarella),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/owner/inventory/ledger" && request.method == HttpMethod.Get -> {
          respond(
            content = json.encodeToString(emptyList<InventoryLedgerEntryDto>()),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected API call: ${request.method.value} $path")
      }
    }

    val storage = InMemoryTokenStorage(ownerSession)
    val apiClient = PizzoraApiClient("http://localhost:8080", storage, engine)
    val repo = NetworkOwnerRepository(apiClient, ProductImageUploader(apiClient, ImageCompressor()))
    val vm = OwnerViewModel(repo)

    composeTestRule.setContent {
      PizzoraTheme {
        OwnerRoleShell(
          session = ownerSession,
          ownerViewModel = vm,
          onSignOut = {},
        )
      }
    }

    composeTestRule.waitUntil(timeoutMillis = 3_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.onAllNodesWithTag("kpi_today_revenue").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("₹2450.00").assertIsDisplayed()
    composeTestRule.onNodeWithText("3 completed").assertIsDisplayed()
    composeTestRule.onNodeWithTag("low_stock_item_ing-mozz").assertIsDisplayed()

    // Toggle store open switch -> Kitchen Paused
    composeTestRule.onNodeWithTag("owner_store_open_switch").performClick()
    composeTestRule.waitUntil(timeoutMillis = 3_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      vm.uiState.value.settings?.isAcceptingOrders == false
    }
    composeTestRule.waitForIdle()
    assertFalse(settings.isAcceptingOrders)
    composeTestRule.onNodeWithText("Kitchen Paused • Checkout Blocked").assertIsDisplayed()

    // Tap quick restock -> clears low-stock alert card
    composeTestRule.onNodeWithTag("quick_restock_ing-mozz").performClick()
    composeTestRule.waitUntil(timeoutMillis = 3_000) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      vm.uiState.value.dashboard?.lowStockCount == 0
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("owner_low_stock_empty").assertIsDisplayed()
  }

  @Test
  fun `owner catalog creates product, edits recipe, issues refund, and updates GST and staff`() {
    val categories = mutableListOf(
      CategoryDto(
        id = "cat-classics",
        name = "Wood-Fired Classics",
        sortOrder = 1,
        isActive = true,
        createdAt = "2026-11-14T07:00:00Z",
      ),
    )
    val products = mutableListOf(
      ProductDto(
        id = "prod-margherita",
        categoryId = "cat-classics",
        categoryName = "Wood-Fired Classics",
        name = "Margherita Verace",
        description = "San Marzano DOP & basil",
        isVeg = true,
        isActive = true,
        isTaxable = true,
        variants = listOf(
          ProductVariantDto("var-margh-reg", "prod-margherita", SizeCode.REGULAR, 29_900L, true),
        ),
        images = emptyList(),
      ),
    )
    val dough = InventoryItemDto(
      ingredientId = "ing-dough",
      name = "48h Sourdough Ball",
      unit = IngredientUnit.UNITS,
      lowStockThreshold = 10L,
      isActive = true,
      onHandQty = 40L,
      reservedQty = 2L,
      availableQty = 38L,
      isLowStock = false,
      updatedAt = "2026-11-14T08:00:00Z",
    )
    var recipes = RecipesOverviewResponse(
      variants = listOf(
        RecipeBindingDto(
          targetType = RecipeTargetType.VARIANT,
          targetId = "var-margh-reg",
          targetLabel = "Margherita Verace (REGULAR)",
          ingredients = emptyList(),
        ),
      ),
    )
    val orderSummary = OrderSummaryDto(
      id = "ord-refund-1",
      orderNumber = "PZ-20261114-1499",
      fulfillmentType = FulfillmentType.DELIVERY,
      scheduleType = ScheduleType.ASAP,
      status = OrderStatus.PLACED,
      version = 1L,
      totalPaise = 59_900L,
      itemCount = 1,
      paymentMethod = PaymentMethod.RAZORPAY,
      paymentStatus = PaymentStatus.CAPTURED,
      isPriority = false,
      createdAt = "2026-11-14T08:05:00Z",
    )
    var orderDetail = OrderDetailDto(
      id = "ord-refund-1",
      orderNumber = "PZ-20261114-1499",
      customerId = "cust-1",
      fulfillmentType = FulfillmentType.DELIVERY,
      scheduleType = ScheduleType.ASAP,
      status = OrderStatus.PLACED,
      version = 1L,
      subtotalPaise = 54_000L,
      discountPaise = 0L,
      deliveryFeePaise = 3_200L,
      taxPaise = 2_700L,
      cgstPaise = 1_350L,
      sgstPaise = 1_350L,
      totalPaise = 59_900L,
      gstBpsSnapshot = 500,
      isPriority = false,
      createdAt = "2026-11-14T08:05:00Z",
      items = listOf(
        OrderItemDto(
          id = "oi-1",
          productId = "prod-margherita",
          productName = "Margherita Verace",
          variantSize = "REGULAR",
          crustName = "Classic",
          quantity = 1,
          unitPricePaise = 29_900L,
          lineTotalPaise = 29_900L,
        ),
      ),
      statusHistory = emptyList(),
      payment = PaymentSummaryDto(
        id = "pay-1",
        method = PaymentMethod.RAZORPAY,
        status = PaymentStatus.CAPTURED,
        amountPaise = 59_900L,
        capturedPaise = 59_900L,
        refundedPaise = 0L,
      ),
    )
    var settings = RestaurantSettingsDto(
      storeName = "PIZZORA Indiranagar",
      isAcceptingOrders = true,
      gstBps = 500,
      defaultPrepMins = 20,
      updatedAt = "2026-11-14T08:00:00Z",
    )
    val staff = mutableListOf<StaffMemberDto>()
    var refundCount = 0

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      when {
        path == "/api/v1/owner/dashboard" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(
            OwnerDashboardResponse(
              todayRevenuePaise = 59_900L,
              todayOrderCount = 1,
              activeOrderCount = 1,
              pendingPaymentCount = 0,
              completedTodayCount = 0,
              lowStockCount = 0,
              lowStockItems = emptyList(),
              recentOrders = listOf(orderSummary),
              settings = settings,
              serverNow = "2026-11-14T08:10:00Z",
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/categories" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(categories),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/products" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(products),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/products" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<CreateProductRequest>((request.body as TextContent).text)
          val created = ProductDto(
            id = "prod-created",
            categoryId = req.categoryId,
            categoryName = categories.first().name,
            name = req.name,
            description = req.description,
            isVeg = req.isVeg,
            isActive = req.isActive,
            isTaxable = req.isTaxable,
            variants = req.variants.mapIndexed { i, v ->
              ProductVariantDto("var-created-$i", "prod-created", v.sizeCode, v.pricePaise, v.isActive)
            },
          )
          products.add(0, created)
          respond(
            content = json.encodeToString(created),
            status = HttpStatusCode.Created,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/owner/recipes" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(recipes),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/recipes" && request.method == HttpMethod.Put -> {
          val req = json.decodeFromString<PutRecipeRequest>((request.body as TextContent).text)
          val updatedBinding = RecipeBindingDto(
            targetType = req.targetType,
            targetId = req.targetId,
            targetLabel = "Margherita Verace (REGULAR)",
            ingredients = req.ingredients.mapIndexed { idx, line ->
              RecipeIngredientLineDto("ril-$idx", line.ingredientId, dough.name, dough.unit, line.quantity)
            },
          )
          recipes = recipes.copy(variants = listOf(updatedBinding))
          respond(
            content = json.encodeToString(updatedBinding),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/owner/inventory" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(listOf(dough)),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/inventory/ledger" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(emptyList<InventoryLedgerEntryDto>()),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/orders" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(OrderListResponse(listOf(orderSummary))),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/orders/ord-refund-1" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(orderDetail),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/orders/ord-refund-1/refund" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<OwnerRefundRequest>((request.body as TextContent).text)
          refundCount += 1
          val refundAmt = req.amountPaise ?: 59_900L
          orderDetail = orderDetail.copy(
            payment = orderDetail.payment.copy(
              status = PaymentStatus.PARTIALLY_REFUNDED,
              refundedPaise = refundAmt,
            ),
          )
          respond(
            content = json.encodeToString(
              RefundDto(
                id = "ref-1",
                paymentId = "pay-1",
                orderId = "ord-refund-1",
                idempotencyKey = "idem-1",
                amountPaise = refundAmt,
                status = RefundStatus.PROCESSING,
                reason = req.reason,
                createdAt = "2026-11-14T08:15:00Z",
                updatedAt = "2026-11-14T08:15:00Z",
              ),
            ),
            status = HttpStatusCode.Accepted,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/owner/coupons" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(
            listOf(
              CouponDto(
                id = "cpn-1",
                code = "WOODFIRE15",
                discountType = DiscountType.PERCENT_BPS,
                discountValue = 1500L,
                minOrderPaise = 39_900L,
                maxUsesPerUser = 2,
                usageCount = 0,
                startsAt = "2026-11-14T00:00:00Z",
                endsAt = "2026-12-14T00:00:00Z",
                isActive = true,
              ),
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/settings" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(settings),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/settings" && request.method == HttpMethod.Put -> {
          val req = json.decodeFromString<UpdateRestaurantSettingsRequest>((request.body as TextContent).text)
          settings = settings.copy(
            storeName = req.storeName ?: settings.storeName,
            isAcceptingOrders = req.isAcceptingOrders ?: settings.isAcceptingOrders,
            gstBps = req.gstBps ?: settings.gstBps,
            defaultPrepMins = req.defaultPrepMins ?: settings.defaultPrepMins,
            updatedAt = "2026-11-14T08:20:00Z",
          )
          respond(
            content = json.encodeToString(settings),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        path == "/api/v1/owner/staff" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(staff),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
        path == "/api/v1/owner/staff" && request.method == HttpMethod.Post -> {
          val req = json.decodeFromString<CreateStaffRequest>((request.body as TextContent).text)
          val created = StaffMemberDto(
            userId = "staff-new-1",
            email = req.email,
            fullName = req.fullName,
            phone = req.phone,
            role = req.role,
            employeeCode = req.employeeCode,
            isActive = true,
          )
          staff.add(0, created)
          respond(
            content = json.encodeToString(created),
            status = HttpStatusCode.Created,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected API call: ${request.method.value} $path")
      }
    }

    val storage = InMemoryTokenStorage(ownerSession)
    val apiClient = PizzoraApiClient("http://localhost:8080", storage, engine)
    val vm = OwnerViewModel(NetworkOwnerRepository(apiClient, ProductImageUploader(apiClient, ImageCompressor())))

    composeTestRule.setContent {
      PizzoraTheme {
        OwnerRoleShell(
          session = ownerSession,
          ownerViewModel = vm,
          onSignOut = {},
        )
      }
    }

    composeTestRule.waitForIdle()

    // 1. Navigate to Catalog tab -> create a product and bind a recipe
    composeTestRule.onNodeWithTag("owner_tab_catalog").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("owner_add_product_button").fetchSemanticsNodes().isNotEmpty()
    }

    composeTestRule.onNodeWithTag("owner_add_product_button").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("product_name_input").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("product_name_input").performTextInput("Burrata Truffle Pie")
    composeTestRule.onNodeWithTag("product_submit_button").performClick()
    waitUntilMainLooper {
      vm.uiState.value.products.any { it.name == "Burrata Truffle Pie" }
    }
    composeTestRule.onNodeWithText("Burrata Truffle Pie").assertIsDisplayed()

    // Switch to Recipes sub-tab and save ingredient binding
    composeTestRule.onNodeWithTag("owner_catalog_tab_${OwnerCatalogSection.RECIPES.name.lowercase()}").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("edit_recipe_var-margh-reg").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("edit_recipe_var-margh-reg").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("recipe_qty_ing-dough").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("recipe_qty_ing-dough").performTextInput("1")
    composeTestRule.onNodeWithTag("recipe_save_button").performClick()
    waitUntilMainLooper {
      vm.uiState.value.recipes.variants.first().ingredients.isNotEmpty()
    }
    composeTestRule.onNodeWithText("48h Sourdough Ball: 1 units").assertIsDisplayed()

    // 2. Navigate to Operations tab -> inspect order and issue refund
    composeTestRule.onNodeWithTag("owner_tab_operations").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("owner_ops_tab_orders").fetchSemanticsNodes().isNotEmpty()
    }

    composeTestRule.onNodeWithTag("owner_ops_tab_orders").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("owner_order_card_ord-refund-1").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("owner_refund_order_button").fetchSemanticsNodes().isNotEmpty()
    }

    composeTestRule.onNodeWithTag("owner_refund_order_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("refund_amount_input").performTextClearance()
    composeTestRule.onNodeWithTag("refund_amount_input").performTextInput("20000")
    composeTestRule.onNodeWithTag("refund_submit_button").performClick()
    waitUntilMainLooper {
      refundCount == 1
    }
    assertEquals(20_000L, orderDetail.payment.refundedPaise)

    // Dismiss order detail dialog so tab bar is clickable
    composeTestRule.onNodeWithText("Close").performClick()
    composeTestRule.waitForIdle()

    // 3. Navigate to More tab -> update GST bps & add KITCHEN_STAFF
    composeTestRule.onNodeWithTag("owner_tab_more").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("settings_gst_bps_input").fetchSemanticsNodes().isNotEmpty()
    }

    composeTestRule.onNodeWithTag("settings_gst_bps_input").performTextClearance()
    composeTestRule.onNodeWithTag("settings_gst_bps_input").performTextInput("1200")
    composeTestRule.onNodeWithTag("settings_save_button").performClick()
    waitUntilMainLooper {
      vm.uiState.value.settings?.gstBps == 1200
    }
    assertEquals(1200, settings.gstBps)

    composeTestRule.onNodeWithTag("add_staff_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("staff_name_input").performTextInput("Chef Elena")
    composeTestRule.onNodeWithTag("staff_email_input").performTextInput("elena@pizzora.local")
    composeTestRule.onNodeWithTag("staff_phone_input").performTextInput("9899998888")
    composeTestRule.onNodeWithTag("staff_code_input").performTextInput("CHEF-02")
    composeTestRule.onNodeWithTag("staff_password_input").performTextInput("ChefPass123!")
    composeTestRule.onNodeWithTag("staff_submit_button").performClick()
    waitUntilMainLooper {
      vm.uiState.value.staff.size == 1
    }
    assertTrue(vm.uiState.value.staff.any { it.employeeCode == "CHEF-02" && it.role == UserRole.KITCHEN_STAFF })
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

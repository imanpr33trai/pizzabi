package com.example

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.core.media.ImageCompressor
import com.example.core.media.ProductImageUploader
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.example.navigation.OwnerRoleShell
import com.example.owner.NetworkOwnerRepository
import com.example.owner.OwnerViewModel
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.AuditLogEntryDto
import com.pizzora.contracts.AuditLogsResponse
import com.pizzora.contracts.CouponRoiDto
import com.pizzora.contracts.DailySalesBucketDto
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.OperationalStockMovementDto
import com.pizzora.contracts.OperationsReportResponse
import com.pizzora.contracts.OwnerDashboardResponse
import com.pizzora.contracts.RestaurantSettingsDto
import com.pizzora.contracts.SalesReportResponse
import com.pizzora.contracts.TopSellingProductDto
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class Phase17ReportsAndAuditRobolectricTest {

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
      id = "owner-17",
      email = "owner@pizzora.local",
      emailVerified = true,
      fullName = "Arjun Owner",
      phone = "9811110017",
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
  fun `owner reports screen displays sales GST netting, operations SLA and wastage, and paginated audit logs`() {
    val todayIst = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
    val capturedSalesQueries = mutableListOf<Pair<String?, String?>>()
    val capturedAuditEntityFilters = mutableListOf<String?>()

    val settings = RestaurantSettingsDto(
      storeName = "PIZZORA Indiranagar",
      isAcceptingOrders = true,
      gstBps = 500,
      defaultPrepMins = 20,
      updatedAt = "2026-11-14T08:00:00Z",
    )

    val auditVariantEntry = AuditLogEntryDto(
      id = "aud-1",
      actorUserId = "owner-17",
      actorEmail = "owner@pizzora.local",
      actorName = "Arjun Owner",
      actorRole = "OWNER",
      action = "VARIANT_UPDATED",
      entityType = "product_variant",
      entityId = "var-truffle-m",
      beforeJson = """{"price_paise":45000}""",
      afterJson = """{"price_paise":49500}""",
      correlationId = "corr-var-1",
      createdAt = "2026-11-14T10:00:00Z",
    )
    val auditRefundEntry = AuditLogEntryDto(
      id = "aud-2",
      actorUserId = "owner-17",
      actorEmail = "owner@pizzora.local",
      actorName = "Arjun Owner",
      actorRole = "OWNER",
      action = "OWNER_REFUND_INITIATED",
      entityType = "refund",
      entityId = "ref-99",
      afterJson = """{"amount_paise":20000}""",
      correlationId = "corr-ref-2",
      createdAt = "2026-11-14T09:30:00Z",
    )
    val auditOlderEntry = AuditLogEntryDto(
      id = "aud-3",
      actorUserId = "owner-17",
      actorEmail = "owner@pizzora.local",
      actorName = "Arjun Owner",
      actorRole = "OWNER",
      action = "RESTAURANT_SETTINGS_UPDATED",
      entityType = "RESTAURANT_SETTINGS",
      entityId = "singleton",
      beforeJson = """{"gst_bps":500}""",
      afterJson = """{"gst_bps":1200}""",
      correlationId = "corr-set-3",
      createdAt = "2026-11-14T08:00:00Z",
    )

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      when {
        path == "/api/v1/owner/dashboard" && request.method == HttpMethod.Get -> respond(
          content = json.encodeToString(
            OwnerDashboardResponse(
              todayRevenuePaise = 120_000L,
              todayOrderCount = 3,
              activeOrderCount = 1,
              pendingPaymentCount = 0,
              completedTodayCount = 2,
              lowStockCount = 0,
              lowStockItems = emptyList(),
              recentOrders = emptyList(),
              settings = settings,
              serverNow = "2026-11-14T10:15:00Z",
            ),
          ),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

        path == "/api/v1/owner/reports/sales" && request.method == HttpMethod.Get -> {
          val start = request.url.parameters["start_date"]
          val end = request.url.parameters["end_date"]
          capturedSalesQueries += (start to end)
          val isTodayOnly = start != null && start == end
          val report = if (isTodayOnly) {
            SalesReportResponse(
              startDate = todayIst,
              endDate = todayIst,
              orderCount = 2,
              cancelledOrderCount = 0,
              subtotalPaise = 48_000L,
              discountPaise = 2_000L,
              deliveryFeePaise = 2_000L,
              taxPaise = 2_400L,
              cgstPaise = 1_200L,
              sgstPaise = 1_200L,
              grossSalesPaise = 50_400L,
              refundedPaise = 0L,
              pendingRefundsPaise = 0L,
              netSalesPaise = 50_400L,
              dailyBreakdown = listOf(
                DailySalesBucketDto(todayIst, 2, 50_400L, 0L, 50_400L, 2_400L),
              ),
              topProducts = listOf(TopSellingProductDto("prod-truffle", "Truffle Burrata", 2, 48_000L)),
              couponPerformance = emptyList(),
              serverNow = "2026-11-14T10:15:00Z",
            )
          } else {
            SalesReportResponse(
              startDate = start ?: "2026-11-08",
              endDate = end ?: todayIst,
              orderCount = 5,
              cancelledOrderCount = 1,
              subtotalPaise = 140_000L,
              discountPaise = 10_000L,
              deliveryFeePaise = 3_000L,
              taxPaise = 7_000L,
              cgstPaise = 3_500L,
              sgstPaise = 3_500L,
              grossSalesPaise = 140_000L,
              refundedPaise = 20_000L,
              pendingRefundsPaise = 0L,
              netSalesPaise = 120_000L,
              dailyBreakdown = listOf(
                DailySalesBucketDto(todayIst, 5, 140_000L, 20_000L, 120_000L, 7_000L),
              ),
              topProducts = listOf(TopSellingProductDto("prod-truffle", "Truffle Burrata", 4, 110_000L)),
              couponPerformance = listOf(CouponRoiDto("cpn-1", "PIZZORA20", 2, 10_000L, 80_000L, 60_000L)),
              serverNow = "2026-11-14T10:15:00Z",
            )
          }
          respond(
            content = json.encodeToString(report),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }

        path == "/api/v1/owner/reports/operations" && request.method == HttpMethod.Get -> {
          val start = request.url.parameters["start_date"] ?: todayIst
          val end = request.url.parameters["end_date"] ?: todayIst
          respond(
            content = json.encodeToString(
              OperationsReportResponse(
                startDate = start,
                endDate = end,
                prepSlaMinutes = 20,
                totalOrders = 22,
                completedOrders = 20,
                cancelledOrders = 1,
                expiredOrders = 1,
                deliveryOrders = 14,
                pickupOrders = 6,
                scheduledOrders = 4,
                measuredPrepOrders = 20,
                avgPrepMinutes = 16.5,
                maxPrepMinutes = 27.0,
                avgCompletionMinutes = 29.0,
                slaBreachedCount = 3,
                slaCompliancePercent = 85.0,
                restockedUnits = 5000L,
                consumedUnits = 3200L,
                restoredUnits = 200L,
                wastageUnits = 350L,
                stockMovements = listOf(
                  OperationalStockMovementDto(
                    ingredientId = "ing-dough",
                    ingredientName = "Sourdough Ball",
                    unit = IngredientUnit.GRAMS,
                    restockedQty = 5000L,
                    consumedQty = 3200L,
                    restoredQty = 200L,
                    wastageQty = 350L,
                  ),
                ),
                lowStockItems = emptyList(),
                serverNow = "2026-11-14T10:15:00Z",
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }

        path == "/api/v1/owner/audit-logs" && request.method == HttpMethod.Get -> {
          val entityType = request.url.parameters["entity_type"]
          val cursor = request.url.parameters["cursor"]
          capturedAuditEntityFilters += entityType
          val body = when {
            entityType == "product_variant" -> AuditLogsResponse(
              entries = listOf(auditVariantEntry),
              nextCursor = null,
              serverNow = "2026-11-14T10:15:00Z",
            )
            cursor == "page-2-cursor" -> AuditLogsResponse(
              entries = listOf(auditOlderEntry),
              nextCursor = null,
              serverNow = "2026-11-14T10:15:00Z",
            )
            else -> AuditLogsResponse(
              entries = listOf(auditVariantEntry, auditRefundEntry),
              nextCursor = "page-2-cursor",
              serverNow = "2026-11-14T10:15:00Z",
            )
          }
          respond(
            content = json.encodeToString(body),
            status = HttpStatusCode.OK,
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
    composeTestRule.onNodeWithTag("owner_tab_reports").performClick()

    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("sales_net_revenue_card").fetchSemanticsNodes().isNotEmpty()
    }

    // Sales Report assertions (7D initial preset)
    composeTestRule.onNodeWithText("₹1200.00").assertIsDisplayed()
    composeTestRule.onNodeWithText("-₹200.00").assertIsDisplayed()
    composeTestRule.onNodeWithText("₹35.00 + ₹35.00").assertIsDisplayed()
    composeTestRule.onNodeWithTag("sales_report_list").performScrollToNode(hasTestTag("sales_top_product_prod-truffle"))
    composeTestRule.onNodeWithTag("sales_top_product_prod-truffle").assertIsDisplayed()
    composeTestRule.onNodeWithTag("sales_report_list").performScrollToNode(hasTestTag("sales_coupon_PIZZORA20"))
    composeTestRule.onNodeWithTag("sales_coupon_PIZZORA20").assertIsDisplayed()

    // Switch date preset -> TODAY (IST)
    composeTestRule.onNodeWithTag("report_preset_today").performClick()
    waitUntilMainLooper {
      vm.uiState.value.salesReport?.netSalesPaise == 50_400L
    }
    composeTestRule.onNodeWithTag("sales_report_list").performScrollToNode(hasTestTag("sales_net_revenue_card"))
    composeTestRule.onAllNodesWithText("₹504.00").onFirst().assertIsDisplayed()
    assertTrue(capturedSalesQueries.any { it.first == todayIst && it.second == todayIst })

    // Switch to Operations sub-tab -> verify SLA & Wastage cards
    composeTestRule.onNodeWithTag("owner_reports_subtab_operations").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("ops_sla_card").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithText("85.0%").assertIsDisplayed()
    composeTestRule.onNodeWithText("20 (3 breached)").assertIsDisplayed()
    composeTestRule.onNodeWithTag("ops_report_list").performScrollToNode(hasTestTag("ops_stock_summary_card"))
    composeTestRule.onNodeWithTag("ops_stock_summary_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("-350").assertIsDisplayed()
    composeTestRule.onNodeWithTag("ops_report_list").performScrollToNode(hasTestTag("ops_movement_ing-dough"))
    composeTestRule.onNodeWithTag("ops_movement_ing-dough").assertIsDisplayed()

    // Switch to Audit Logs sub-tab -> inspect JSON payload, load older cursor page, and filter by entity_type
    composeTestRule.onNodeWithTag("owner_reports_subtab_audit_logs").performClick()
    waitUntilMainLooper {
      composeTestRule.onAllNodesWithTag("audit_entry_aud-1").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("audit_toggle_json_aud-1").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("""{"price_paise":49500}""").assertIsDisplayed()

    // Paginate via Load Older Entries button
    composeTestRule.onNodeWithTag("audit_logs_list").performScrollToNode(hasTestTag("audit_load_more_button"))
    composeTestRule.onNodeWithTag("audit_load_more_button").performClick()
    waitUntilMainLooper {
      vm.uiState.value.auditLogs.any { it.id == "aud-3" }
    }
    assertEquals(3, vm.uiState.value.auditLogs.size)

    // Filter by product_variant
    composeTestRule.onNodeWithTag("audit_filters_row").performScrollToNode(hasTestTag("audit_filter_product_variant"))
    composeTestRule.onNodeWithTag("audit_filter_product_variant").performClick()
    waitUntilMainLooper {
      vm.uiState.value.auditEntityFilter == "product_variant" && vm.uiState.value.auditLogs.size == 1
    }
    assertEquals("aud-1", vm.uiState.value.auditLogs.single().id)
    assertTrue(capturedAuditEntityFilters.contains("product_variant"))
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

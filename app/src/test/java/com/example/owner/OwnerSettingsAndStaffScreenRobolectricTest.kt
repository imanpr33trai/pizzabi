package com.example.owner

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.RestaurantSettingsDto
import com.pizzora.contracts.StaffMemberDto
import com.pizzora.contracts.UpdateRestaurantSettingsRequest
import com.pizzora.contracts.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class OwnerSettingsAndStaffScreenRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val settings = RestaurantSettingsDto(
    storeName = "PIZZORA Indiranagar",
    isAcceptingOrders = true,
    gstBps = 500,
    defaultPrepMins = 20,
    updatedAt = "2026-11-14T08:00:00Z",
  )

  private val chef = StaffMemberDto(
    userId = "staff-1",
    email = "elena@pizzora.local",
    fullName = "Chef Elena",
    phone = "9899998888",
    role = UserRole.KITCHEN_STAFF,
    employeeCode = "CHEF-02",
    isActive = true,
  )

  @Test
  fun `settings form renders tokenized controls and routes save`() {
    var submitted: UpdateRestaurantSettingsRequest? = null

    composeTestRule.setContent {
      PizzoraTheme {
        OwnerSettingsAndStaffScreen(
          uiState = OwnerUiState(settings = settings, staff = listOf(chef)),
          onRefresh = {},
          onUpdateSettings = { submitted = it },
          onCreateStaff = { _, _, _, _, _, _ -> },
          onToggleStaffActive = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("owner_more_screen").assertIsDisplayed()
    listOf(
      "settings_store_name_input",
      "settings_gst_bps_input",
      "settings_prep_mins_input",
      "settings_save_button",
    ).forEach { tag ->
      composeTestRule.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
    }
    composeTestRule.onNodeWithTag("settings_save_button_label", useUnmergedTree = true)
      .assertTextEquals("Save Store Settings")

    composeTestRule.onNodeWithTag("settings_store_name_input").performTextReplacement("PIZZORA Koramangala")
    composeTestRule.onNodeWithTag("settings_gst_bps_input").performTextReplacement("1200")
    composeTestRule.onNodeWithTag("settings_prep_mins_input").performTextReplacement("25")
    composeTestRule.onNodeWithTag("settings_save_button").performClick()

    assertEquals("PIZZORA Koramangala", submitted?.storeName)
    assertEquals(1200, submitted?.gstBps)
    assertEquals(25, submitted?.defaultPrepMins)
    assertEquals(true, submitted?.isAcceptingOrders)
  }

  @Test
  fun `accepting orders row toggles checkout state`() {
    composeTestRule.setContent {
      PizzoraTheme {
        OwnerSettingsAndStaffScreen(
          uiState = OwnerUiState(settings = settings),
          onRefresh = {},
          onUpdateSettings = {},
          onCreateStaff = { _, _, _, _, _, _ -> },
          onToggleStaffActive = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("settings_accepting_switch").assertHeightIsAtLeast(48.dp)
    composeTestRule.onNodeWithTag("settings_accepting_row_title", useUnmergedTree = true)
      .assertTextEquals("Accepting Orders")
    composeTestRule.onNodeWithTag("settings_accepting_row_subtitle", useUnmergedTree = true)
      .assertTextEquals("Store is open for customer checkout")

    composeTestRule.onNodeWithTag("settings_accepting_switch").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("settings_accepting_row_subtitle", useUnmergedTree = true)
      .assertTextEquals("Checkout paused (STORE_CLOSED)")
  }

  @Test
  fun `staff list renders list items and toggles active state`() {
    var toggled: StaffMemberDto? = null

    composeTestRule.setContent {
      PizzoraTheme {
        OwnerSettingsAndStaffScreen(
          uiState = OwnerUiState(settings = settings, staff = listOf(chef)),
          onRefresh = {},
          onUpdateSettings = {},
          onCreateStaff = { _, _, _, _, _, _ -> },
          onToggleStaffActive = { toggled = it },
        )
      }
    }

    composeTestRule.onNodeWithTag("staff_card_staff-1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("staff_card_staff-1_title", useUnmergedTree = true)
      .assertTextEquals("Chef Elena")
    composeTestRule.onNodeWithText("KITCHEN_STAFF • CHEF-02").assertIsDisplayed()
    composeTestRule.onNodeWithTag("staff_active_switch_staff-1").assertHeightIsAtLeast(48.dp)

    composeTestRule.onNodeWithTag("staff_active_switch_staff-1").performClick()
    assertEquals(chef, toggled)
  }

  @Test
  fun `add staff dialog collects fields and creates kitchen staff by default`() {
    var created: List<Any> = emptyList()
    var createCount = 0

    composeTestRule.setContent {
      PizzoraTheme {
        OwnerSettingsAndStaffScreen(
          uiState = OwnerUiState(settings = settings),
          onRefresh = {},
          onUpdateSettings = {},
          onCreateStaff = { email, password, fullName, phone, role, employeeCode ->
            created = listOf(email, password, fullName, phone, role, employeeCode)
            createCount++
          },
          onToggleStaffActive = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("add_staff_button").assertHeightIsAtLeast(48.dp)
    composeTestRule.onNodeWithTag("add_staff_button").performClick()
    composeTestRule.waitForIdle()

    listOf(
      "staff_name_input",
      "staff_email_input",
      "staff_phone_input",
      "staff_code_input",
      "staff_password_input",
      "staff_submit_button",
    ).forEach { tag ->
      composeTestRule.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
    }
    composeTestRule.onNodeWithTag("staff_name_input_label", useUnmergedTree = true)
      .assertTextEquals("Full Name")

    composeTestRule.onNodeWithTag("staff_name_input").performTextReplacement("Chef Elena")
    composeTestRule.onNodeWithTag("staff_email_input").performTextReplacement("elena@pizzora.local")
    composeTestRule.onNodeWithTag("staff_phone_input").performTextReplacement("9899998888")
    composeTestRule.onNodeWithTag("staff_code_input").performTextReplacement("CHEF-02")
    composeTestRule.onNodeWithTag("staff_password_input").performTextReplacement("ChefPass123!")
    composeTestRule.onNodeWithTag("staff_submit_button").performClick()
    composeTestRule.waitForIdle()

    assertEquals(1, createCount)
    assertEquals(
      listOf("elena@pizzora.local", "ChefPass123!", "Chef Elena", "9899998888", UserRole.KITCHEN_STAFF, "CHEF-02"),
      created,
    )
    composeTestRule.onNodeWithTag("staff_name_input").assertDoesNotExist()
  }

  @Test
  fun `add staff dialog role chips switch selection`() {
    var createdRole: UserRole? = null

    composeTestRule.setContent {
      PizzoraTheme {
        OwnerSettingsAndStaffScreen(
          uiState = OwnerUiState(settings = settings),
          onRefresh = {},
          onUpdateSettings = {},
          onCreateStaff = { _, _, _, _, role, _ -> createdRole = role },
          onToggleStaffActive = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("add_staff_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("staff_role_chip_owner").performClick()
    composeTestRule.onNodeWithTag("staff_submit_button").performClick()
    composeTestRule.waitForIdle()

    assertEquals(UserRole.OWNER, createdRole)
  }

  @Test
  fun `empty staff list shows empty state`() {
    composeTestRule.setContent {
      PizzoraTheme {
        OwnerSettingsAndStaffScreen(
          uiState = OwnerUiState(settings = settings, staff = emptyList()),
          onRefresh = {},
          onUpdateSettings = {},
          onCreateStaff = { _, _, _, _, _, _ -> },
          onToggleStaffActive = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("owner_staff_empty").assertIsDisplayed()
    composeTestRule.onNodeWithText("No staff accounts yet").assertIsDisplayed()
  }

  @Test
  fun `error banner offers retry`() {
    var refreshCount = 0

    composeTestRule.setContent {
      PizzoraTheme {
        OwnerSettingsAndStaffScreen(
          uiState = OwnerUiState(settings = settings, errorMessage = "Network unreachable"),
          onRefresh = { refreshCount++ },
          onUpdateSettings = {},
          onCreateStaff = { _, _, _, _, _, _ -> },
          onToggleStaffActive = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("owner_error_banner").assertIsDisplayed()
    composeTestRule.onNodeWithText("Network unreachable").assertIsDisplayed()
    composeTestRule.onNodeWithTag("owner_error_retry").performClick()
    assertEquals(1, refreshCount)
  }

  @Test
  fun `initial loading shows progress indicator`() {
    composeTestRule.setContent {
      PizzoraTheme {
        OwnerSettingsAndStaffScreen(
          uiState = OwnerUiState(isLoadingMore = true, settings = null, staff = emptyList()),
          onRefresh = {},
          onUpdateSettings = {},
          onCreateStaff = { _, _, _, _, _, _ -> },
          onToggleStaffActive = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("owner_more_loading_indicator").assertIsDisplayed()
    assertFalse(composeTestRule.onAllNodesWithTag("settings_save_button").fetchSemanticsNodes().isNotEmpty())
    assertTrue(composeTestRule.onAllNodesWithTag("owner_more_screen").fetchSemanticsNodes().isNotEmpty())
  }
}

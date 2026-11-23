package com.example.owner

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.ui.theme.PizzoraTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.pizzora.contracts.RestaurantSettingsDto
import com.pizzora.contracts.StaffMemberDto
import com.pizzora.contracts.UserRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OwnerSettingsAndStaffScreenScreenshotTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Composable
  private fun settingsScreen() = OwnerSettingsAndStaffScreen(
    uiState = OwnerUiState(
      settings = RestaurantSettingsDto(
        storeName = "PIZZORA Indiranagar",
        isAcceptingOrders = true,
        gstBps = 500,
        defaultPrepMins = 20,
        updatedAt = "2026-11-14T08:00:00Z",
      ),
      staff = listOf(
        StaffMemberDto(
          userId = "staff-1",
          email = "elena@pizzora.local",
          fullName = "Chef Elena",
          phone = "9899998888",
          role = UserRole.KITCHEN_STAFF,
          employeeCode = "CHEF-02",
          isActive = true,
        ),
        StaffMemberDto(
          userId = "owner-2",
          email = "arjun@pizzora.local",
          fullName = "Arjun Owner",
          phone = "9811110000",
          role = UserRole.OWNER,
          employeeCode = "OWN-01",
          isActive = false,
        ),
      ),
    ),
    onRefresh = {},
    onUpdateSettings = {},
    onCreateStaff = { _, _, _, _, _, _ -> },
    onToggleStaffActive = {},
  )

  @Test
  fun `settings light`() {
    composeTestRule.setContent { PizzoraTheme(darkTheme = false) { settingsScreen() } }
    composeTestRule.onNodeWithTag("owner_more_screen").captureRoboImage("settings_screen_light.png")
  }

  @Test
  fun `settings dark`() {
    composeTestRule.setContent { PizzoraTheme(darkTheme = true) { settingsScreen() } }
    composeTestRule.onNodeWithTag("owner_more_screen").captureRoboImage("settings_screen_dark.png")
  }

  @Test
  fun `settings kds`() {
    composeTestRule.setContent { PizzoraTheme(kdsMode = true) { settingsScreen() } }
    composeTestRule.onNodeWithTag("owner_more_screen").captureRoboImage("settings_screen_kds.png")
  }
}

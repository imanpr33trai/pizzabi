package com.example.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.ui.theme.PizzoraTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.DeliveryZoneDto
import com.pizzora.contracts.UserProfileDto
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
class CustomerProfileScreenScreenshotTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Composable
  private fun profileScreen() = CustomerProfileScreen(
    uiState = ProfileUiState(
      profile = UserProfileDto(
        id = "cust-1",
        email = "priya@pizzora.local",
        emailVerified = true,
        fullName = "Priya Customer",
        phone = "9811110000",
        role = UserRole.CUSTOMER,
        isActive = true,
      ),
      addresses = listOf(
        AddressDto("addr-1", "Home", "42 Indiranagar", null, null, "560038", "Bengaluru", isDefault = true),
        AddressDto("addr-2", "Work", "100 Feet Road", "Suite 402", "Opposite Metro", "560038", "Bengaluru", isDefault = false),
      ),
      pincodeCheckInput = "560038",
      pincodeCheckResult = DeliveryZoneCheckResponse(
        pincode = "560038",
        isServiceable = true,
        zone = DeliveryZoneDto("z-1", "560038", "Indiranagar", 30_000L, 4_000L, 28, true),
        message = "Delivers to Indiranagar (560038) in ~28 mins.",
      ),
    ),
    onRefresh = {},
    onOpenEditProfile = {},
    onDismissEditProfile = {},
    onSaveProfile = { _, _ -> },
    onOpenAddAddress = {},
    onOpenEditAddress = {},
    onDismissAddressDialog = {},
    onSaveAddress = { _, _, _, _, _, _, _ -> },
    onSetDefaultAddress = {},
    onDeleteAddress = {},
    onPincodeInputChange = {},
    onCheckPincode = {},
    onSignOut = {},
  )

  @Test
  fun `profile light`() {
    composeTestRule.setContent { PizzoraTheme(darkTheme = false) { profileScreen() } }
    composeTestRule.onNodeWithTag("customer_profile_screen").captureRoboImage("profile_screen_light.png")
  }

  @Test
  fun `profile dark`() {
    composeTestRule.setContent { PizzoraTheme(darkTheme = true) { profileScreen() } }
    composeTestRule.onNodeWithTag("customer_profile_screen").captureRoboImage("profile_screen_dark.png")
  }

  @Test
  fun `profile kds`() {
    composeTestRule.setContent { PizzoraTheme(kdsMode = true) { profileScreen() } }
    composeTestRule.onNodeWithTag("customer_profile_screen").captureRoboImage("profile_screen_kds.png")
  }
}

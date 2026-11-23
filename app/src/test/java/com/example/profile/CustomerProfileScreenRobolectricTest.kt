package com.example.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.DeliveryZoneDto
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class CustomerProfileScreenRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val profile = UserProfileDto(
    id = "cust-1",
    email = "priya@pizzora.local",
    emailVerified = true,
    fullName = "Priya Customer",
    phone = "9811110000",
    role = UserRole.CUSTOMER,
    isActive = true,
  )

  private val home = AddressDto(
    id = "addr-1",
    label = "Home",
    line1 = "42 Indiranagar",
    line2 = null,
    landmark = null,
    pincode = "560038",
    city = "Bengaluru",
    isDefault = true,
  )

  private val work = AddressDto(
    id = "addr-2",
    label = "Work",
    line1 = "100 Feet Road",
    line2 = "Suite 402",
    landmark = "Opposite Metro",
    pincode = "560038",
    city = "Bengaluru",
    isDefault = false,
  )

  private val serviceableResult = DeliveryZoneCheckResponse(
    pincode = "560038",
    isServiceable = true,
    zone = DeliveryZoneDto("z-1", "560038", "Indiranagar", 30_000L, 4_000L, 28, true),
    message = "Delivers to Indiranagar (560038) in ~28 mins.",
  )

  @Test
  fun `account card renders profile and routes edit save`() {
    var uiState by mutableStateOf(ProfileUiState(profile = profile))
    var savedName = ""
    var savedPhone = ""

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerProfileScreen(
          uiState = uiState,
          onRefresh = {},
          onOpenEditProfile = { uiState = uiState.copy(isEditProfileDialogOpen = true) },
          onDismissEditProfile = { uiState = uiState.copy(isEditProfileDialogOpen = false) },
          onSaveProfile = { name, phone ->
            savedName = name
            savedPhone = phone
          },
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
      }
    }

    composeTestRule.onNodeWithTag("customer_profile_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_full_name_text").assertTextContains("Priya Customer")
    composeTestRule.onNodeWithTag("edit_profile_button").assertHeightIsAtLeast(48.dp)
    composeTestRule.onNodeWithTag("edit_profile_button_label", useUnmergedTree = true)
      .assertTextEquals("Edit")

    composeTestRule.onNodeWithTag("edit_profile_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("profile_edit_name_input").assertHeightIsAtLeast(48.dp)
    composeTestRule.onNodeWithTag("profile_edit_phone_input").assertHeightIsAtLeast(48.dp)
    composeTestRule.onNodeWithTag("profile_edit_name_input").performTextReplacement("Priya Rao")
    composeTestRule.onNodeWithTag("profile_edit_phone_input").performTextReplacement("9812345678")
    composeTestRule.onNodeWithTag("profile_edit_save_button").performClick()

    assertEquals("Priya Rao", savedName)
    assertEquals("9812345678", savedPhone)
  }

  @Test
  fun `saved addresses render as list items with actions`() {
    var deletedId = ""
    var edited: AddressDto? = null
    var defaultSet: AddressDto? = null

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerProfileScreen(
          uiState = ProfileUiState(profile = profile, addresses = listOf(home, work)),
          onRefresh = {},
          onOpenEditProfile = {},
          onDismissEditProfile = {},
          onSaveProfile = { _, _ -> },
          onOpenAddAddress = {},
          onOpenEditAddress = { edited = it },
          onDismissAddressDialog = {},
          onSaveAddress = { _, _, _, _, _, _, _ -> },
          onSetDefaultAddress = { defaultSet = it },
          onDeleteAddress = { deletedId = it },
          onPincodeInputChange = {},
          onCheckPincode = {},
          onSignOut = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("profile_address_card_addr-1_title", useUnmergedTree = true)
      .assertTextEquals("Home")
    composeTestRule.onNodeWithTag("profile_default_badge_addr-1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_edit_address_addr-1").assertHeightIsAtLeast(48.dp)
    composeTestRule.onNodeWithTag("profile_delete_address_addr-1").assertHeightIsAtLeast(48.dp)
    composeTestRule.onNodeWithTag("profile_set_default_addr-1").assertDoesNotExist()

    composeTestRule.onNodeWithTag("profile_delete_address_addr-1").performClick()
    assertEquals("addr-1", deletedId)

    composeTestRule.onNodeWithTag("profile_lazy_column")
      .performScrollToNode(hasTestTag("profile_address_card_addr-2"))
    composeTestRule.onNodeWithTag("profile_edit_address_addr-2").performClick()
    assertEquals(work, edited)

    composeTestRule.onNodeWithTag("profile_lazy_column")
      .performScrollToNode(hasTestTag("profile_set_default_addr-2"))
    composeTestRule.onNodeWithTag("profile_set_default_addr-2").assertHeightIsAtLeast(48.dp)
    composeTestRule.onNodeWithTag("profile_set_default_addr-2").performClick()
    assertEquals(work, defaultSet)
  }

  @Test
  fun `pincode checker routes input and renders serviceable result`() {
    var input = ""
    var checkCount = 0

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerProfileScreen(
          uiState = ProfileUiState(profile = profile, pincodeCheckResult = serviceableResult),
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
          onPincodeInputChange = { input = it },
          onCheckPincode = { checkCount++ },
          onSignOut = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("profile_pincode_input").performTextReplacement("560038")
    assertEquals("560038", input)
    composeTestRule.onNodeWithTag("profile_check_pincode_button_label", useUnmergedTree = true)
      .assertTextEquals("Check")
    composeTestRule.onNodeWithTag("profile_check_pincode_button").performClick()
    assertEquals(1, checkCount)

    composeTestRule.onNodeWithTag("profile_pincode_result_banner")
      .assertTextContains("Indiranagar", substring = true)
    composeTestRule.onNodeWithTag("profile_pincode_result_banner")
      .assertTextContains("28 mins", substring = true)
  }

  @Test
  fun `pincode checker shows error text`() {
    composeTestRule.setContent {
      PizzoraTheme {
        CustomerProfileScreen(
          uiState = ProfileUiState(profile = profile, pincodeCheckError = "Enter a valid 6-digit pincode"),
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
      }
    }

    composeTestRule.onNodeWithTag("profile_pincode_error_text")
      .assertTextEquals("Enter a valid 6-digit pincode")
  }

  @Test
  fun `sign out and add address actions route`() {
    var signOutCount = 0
    var addAddressCount = 0

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerProfileScreen(
          uiState = ProfileUiState(profile = profile),
          onRefresh = {},
          onOpenEditProfile = {},
          onDismissEditProfile = {},
          onSaveProfile = { _, _ -> },
          onOpenAddAddress = { addAddressCount++ },
          onOpenEditAddress = {},
          onDismissAddressDialog = {},
          onSaveAddress = { _, _, _, _, _, _, _ -> },
          onSetDefaultAddress = {},
          onDeleteAddress = {},
          onPincodeInputChange = {},
          onCheckPincode = {},
          onSignOut = { signOutCount++ },
        )
      }
    }

    composeTestRule.onNodeWithTag("profile_sign_out_button").assertHeightIsAtLeast(48.dp)
    composeTestRule.onNodeWithTag("profile_sign_out_button").performClick()
    assertEquals(1, signOutCount)

    composeTestRule.onNodeWithTag("profile_add_address_button_label", useUnmergedTree = true)
      .assertTextEquals("Add Address")
    composeTestRule.onNodeWithTag("profile_add_address_button").performClick()
    assertEquals(1, addAddressCount)
  }

  @Test
  fun `empty address book shows empty state`() {
    composeTestRule.setContent {
      PizzoraTheme {
        CustomerProfileScreen(
          uiState = ProfileUiState(profile = profile, addresses = emptyList()),
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
      }
    }

    composeTestRule.onNodeWithTag("profile_addresses_empty").assertIsDisplayed()
    composeTestRule.onNodeWithText("No saved addresses").assertIsDisplayed()
  }

  @Test
  fun `address dialog saves new address with default flag`() {
    var saved: List<Any?> = emptyList()

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerProfileScreen(
          uiState = ProfileUiState(profile = profile, isAddressDialogOpen = true),
          onRefresh = {},
          onOpenEditProfile = {},
          onDismissEditProfile = {},
          onSaveProfile = { _, _ -> },
          onOpenAddAddress = {},
          onOpenEditAddress = {},
          onDismissAddressDialog = {},
          onSaveAddress = { label, line1, line2, landmark, pincode, city, isDefault ->
            saved = listOf(label, line1, line2, landmark, pincode, city, isDefault)
          },
          onSetDefaultAddress = {},
          onDeleteAddress = {},
          onPincodeInputChange = {},
          onCheckPincode = {},
          onSignOut = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("profile_addr_label_input").performTextReplacement("Work")
    composeTestRule.onNodeWithTag("profile_addr_line1_input").performTextReplacement("100 Feet Road")
    composeTestRule.onNodeWithTag("profile_addr_pincode_input").performTextReplacement("560038")
    composeTestRule.onNodeWithTag("profile_addr_city_input").performScrollTo().performTextReplacement("Bengaluru")
    composeTestRule.onNodeWithTag("profile_addr_default_switch", useUnmergedTree = true)
      .performScrollTo()
      .assertHeightIsAtLeast(48.dp)
    composeTestRule.onNodeWithTag("profile_addr_default_switch", useUnmergedTree = true).performClick()
    composeTestRule.onNodeWithTag("profile_addr_default_switch", useUnmergedTree = true).assertIsOn()
    composeTestRule.onNodeWithTag("profile_addr_save_button").performClick()

    assertEquals(listOf("Work", "100 Feet Road", "", "", "560038", "Bengaluru", true), saved)
  }

  @Test
  fun `edit profile dialog shows save error`() {
    composeTestRule.setContent {
      PizzoraTheme {
        CustomerProfileScreen(
          uiState = ProfileUiState(
            profile = profile,
            isEditProfileDialogOpen = true,
            dialogError = "Could not save profile",
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
      }
    }

    composeTestRule.onNodeWithTag("profile_edit_dialog_error", useUnmergedTree = true)
      .assertTextEquals("Could not save profile")
  }

  @Test
  fun `loading state shows progress indicator`() {
    composeTestRule.setContent {
      PizzoraTheme {
        CustomerProfileScreen(
          uiState = ProfileUiState(isLoading = true),
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
      }
    }

    composeTestRule.onNodeWithTag("profile_loading_indicator").assertIsDisplayed()
    assertTrue(composeTestRule.onAllNodesWithTag("customer_profile_screen").fetchSemanticsNodes().isNotEmpty())
  }
}

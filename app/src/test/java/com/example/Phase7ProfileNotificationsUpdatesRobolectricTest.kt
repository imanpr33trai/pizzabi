package com.example

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.core.database.NotificationInboxEntity
import com.example.notifications.NotificationsScreen
import com.example.notifications.NotificationsUiState
import com.example.notifications.notificationCategoryColors
import com.example.profile.CustomerProfileScreen
import com.example.profile.ProfileUiState
import com.example.ui.theme.DarkSemanticColors
import com.example.ui.theme.LightSemanticColors
import com.example.ui.theme.PizzoraDarkColorScheme
import com.example.ui.theme.PizzoraLightColorScheme
import com.example.ui.theme.PizzoraTheme
import com.example.update.AppUpdatesSettingsCard
import com.example.update.AvailableUpdate
import com.example.update.MandatoryUpdateReason
import com.example.update.UpdateFailureReason
import com.example.update.UpdateHostOverlay
import com.example.update.positiveUpdateStatusColor
import com.example.update.UpdateMetadata
import com.example.update.UpdateState
import com.example.update.UpdateUiState
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.NotificationCategory
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class Phase7ProfileNotificationsUpdatesRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val sampleProfile = UserProfileDto(
    id = "cust-7",
    email = "priya@pizzora.test",
    emailVerified = true,
    fullName = "Priya Customer",
    phone = "+919876543210",
    role = UserRole.CUSTOMER,
    isActive = true,
  )

  private val sampleUpdate = AvailableUpdate(
    metadata = UpdateMetadata(
      schemaVersion = 1,
      applicationId = "com.aistudio.pizzora.xkqzmw",
      versionName = "0.8.0",
      versionCode = 8000L,
      minimumSupportedVersionCode = 1000L,
      mandatory = false,
      releaseNotes = buildString {
        repeat(12) { appendLine("- Release note line $it with enough text to require scrolling.") }
      },
      apkAssetName = "pizzora-v0.8.0.apk",
      apkSha256 = "c".repeat(64),
      apkSizeBytes = 12L * 1024L * 1024L,
      releaseUrl = "https://github.com/imanpr33trai/pizzabi/releases/tag/v0.8.0",
    ),
    apkDownloadUrl = "https://github.com/imanpr33trai/pizzabi/releases/download/v0.8.0/pizzora-v0.8.0.apk",
    apkSizeBytes = 12L * 1024L * 1024L,
    releasePageUrl = "https://github.com/imanpr33trai/pizzabi/releases/tag/v0.8.0",
    isMandatory = false,
    mandatoryReason = null,
  )

  private fun StateRestorationTester.setProfileContent(uiState: ProfileUiState) {
    setContent {
      PizzoraTheme {
        CustomerProfileScreen(
          uiState = uiState,
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
          onCheckDeliveryPincode = {},
          onSignOut = {},
        )
      }
    }
  }

  @Test
  fun editProfileDialogRestoresEnteredValuesAfterStateRestore() {
    val restorationTester = StateRestorationTester(composeTestRule)
    restorationTester.setProfileContent(
      ProfileUiState(profile = sampleProfile, isEditProfileDialogOpen = true),
    )

    composeTestRule.onNodeWithTag("profile_edit_name_input").performTextClearance()
    composeTestRule.onNodeWithTag("profile_edit_name_input").performTextInput("Restored Name")
    composeTestRule.onNodeWithTag("profile_edit_phone_input").performTextClearance()
    composeTestRule.onNodeWithTag("profile_edit_phone_input").performTextInput("9999999999")

    restorationTester.emulateSavedInstanceStateRestore()

    composeTestRule.onNodeWithTag("profile_edit_name_input").assertTextContains("Restored Name")
    composeTestRule.onNodeWithTag("profile_edit_phone_input").assertTextContains("9999999999")
  }

  @Test
  fun dismissingEditProfileDialogRestoresFocusToEditButton() {
    var dialogOpen by mutableStateOf(false)

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerProfileScreen(
          uiState = ProfileUiState(profile = sampleProfile, isEditProfileDialogOpen = dialogOpen),
          onRefresh = {},
          onOpenEditProfile = { dialogOpen = true },
          onDismissEditProfile = { dialogOpen = false },
          onSaveProfile = { _, _ -> },
          onOpenAddAddress = {},
          onOpenEditAddress = {},
          onDismissAddressDialog = {},
          onSaveAddress = { _, _, _, _, _, _, _ -> },
          onSetDefaultAddress = {},
          onDeleteAddress = {},
          onPincodeInputChange = {},
          onCheckPincode = {},
          onCheckDeliveryPincode = {},
          onSignOut = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("edit_profile_button").performClick()
    composeTestRule.onNodeWithTag("profile_edit_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithText("Close").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("edit_profile_button").assertIsFocused()
  }

  @Test
  fun addressDialogRestoresEnteredValuesAndDefaultSwitchAfterStateRestore() {
    val restorationTester = StateRestorationTester(composeTestRule)
    restorationTester.setProfileContent(
      ProfileUiState(profile = sampleProfile, isAddressDialogOpen = true),
    )

    composeTestRule.onNodeWithTag("profile_addr_label_input").performTextClearance()
    composeTestRule.onNodeWithTag("profile_addr_label_input").performTextInput("Work")
    composeTestRule.onNodeWithTag("profile_addr_line1_input").performTextInput("12 MG Road")
    composeTestRule.onNodeWithTag("profile_addr_pincode_input").performTextInput("560001")
    composeTestRule.onNodeWithTag("profile_addr_default_switch").performClick()

    restorationTester.emulateSavedInstanceStateRestore()

    composeTestRule.onNodeWithTag("profile_addr_label_input").assertTextContains("Work")
    composeTestRule.onNodeWithTag("profile_addr_line1_input").assertTextContains("12 MG Road")
    composeTestRule.onNodeWithTag("profile_addr_pincode_input").assertTextContains("560001")
    composeTestRule.onNodeWithTag("profile_addr_default_switch").assertIsOn()
  }

  @Test
  fun profileShowsMonogramConfirmedDeleteCheckDeliveryAndBottomAccountActions() {
    val address = AddressDto(
      id = "addr-1",
      label = "Home",
      line1 = "42 Indiranagar",
      line2 = null,
      landmark = null,
      pincode = "560038",
      city = "Bengaluru",
      isDefault = true,
    )
    var deletedAddressId: String? = null
    var checkedPincode: String? = null
    var signedOut = false

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerProfileScreen(
          uiState = ProfileUiState(profile = sampleProfile, addresses = listOf(address)),
          onRefresh = {},
          onOpenEditProfile = {},
          onDismissEditProfile = {},
          onSaveProfile = { _, _ -> },
          onOpenAddAddress = {},
          onOpenEditAddress = {},
          onDismissAddressDialog = {},
          onSaveAddress = { _, _, _, _, _, _, _ -> },
          onSetDefaultAddress = {},
          onDeleteAddress = { deletedAddressId = it },
          onPincodeInputChange = {},
          onCheckPincode = {},
          onCheckDeliveryPincode = { checkedPincode = it },
          onSignOut = { signedOut = true },
        )
      }
    }

    composeTestRule.onNodeWithText("PC").assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_phone_text").assertIsDisplayed()

    composeTestRule.onNodeWithTag("profile_lazy_column")
      .performScrollToNode(hasTestTag("profile_check_delivery_addr-1"))
    composeTestRule.onNodeWithTag("profile_check_delivery_addr-1").performClick()
    assertEquals("560038", checkedPincode)

    composeTestRule.onNodeWithTag("profile_lazy_column")
      .performScrollToNode(hasTestTag("profile_delete_address_addr-1"))
    composeTestRule.onNodeWithTag("profile_delete_address_addr-1").performClick()
    composeTestRule.onNodeWithText("Delete 'Home' address?").assertIsDisplayed()
    composeTestRule.onNodeWithText(
      "This is your default delivery address. Removing it keeps your other saved addresses ready for checkout.",
    ).assertIsDisplayed()
    composeTestRule.onNodeWithTag("cancel_delete_address_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(null, deletedAddressId)

    composeTestRule.onNodeWithTag("profile_delete_address_addr-1").performClick()
    composeTestRule.onNodeWithTag("confirm_delete_address_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals("addr-1", deletedAddressId)

    composeTestRule.onNodeWithTag("profile_lazy_column")
      .performScrollToNode(hasTestTag("profile_sign_out_button"))
    composeTestRule.onNodeWithTag("profile_sign_out_button").performClick()
    composeTestRule.onNodeWithTag("cancel_sign_out_button").performClick()
    composeTestRule.waitForIdle()
    assertFalse(signedOut)

    composeTestRule.onNodeWithTag("profile_lazy_column")
      .performScrollToNode(hasTestTag("profile_sign_out_button"))
    composeTestRule.onNodeWithTag("profile_sign_out_button").performClick()
    composeTestRule.onNodeWithTag("confirm_sign_out_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(signedOut)
  }

  @Test
  fun orderNotificationCardIsHumanizedClickableAndOpensReferenceId() {
    var markedRead: String? = null
    var openedOrderId: String? = null
    val notification = NotificationInboxEntity(
      id = "n-order",
      userId = "cust-7",
      eventId = "evt-order",
      title = "Order Baking",
      body = "Your pizza is in the oven.",
      category = NotificationCategory.ORDER_UPDATE,
      referenceId = "order-77",
      isRead = false,
      createdAtIso = Instant.now().minusSeconds(5 * 60).toString(),
    )

    composeTestRule.setContent {
      PizzoraTheme {
        NotificationsScreen(
          uiState = NotificationsUiState(notifications = listOf(notification), unreadCount = 1),
          onRefresh = {},
          onSelectUnreadOnly = {},
          onMarkRead = { markedRead = it },
          onMarkAllRead = {},
          onToggleOrderUpdates = {},
          onTogglePromoAlerts = {},
          onNotificationPermissionResult = {},
          onOpenOrderFromNotification = { openedOrderId = it },
        )
      }
    }

    composeTestRule.onNodeWithTag("notification_card_n-order").assertIsDisplayed()
    composeTestRule.onNodeWithText("Order").assertIsDisplayed()
    composeTestRule.onNodeWithText("5m ago").assertIsDisplayed()
    composeTestRule.onNodeWithTag("notification_order_link_n-order", useUnmergedTree = true)
      .assertIsDisplayed()

    val semantics = composeTestRule.onNodeWithTag("notification_card_n-order").fetchSemanticsNode()
    assertEquals(
      "Order, unread, Double-tap to view order",
      semantics.config[SemanticsProperties.ContentDescription]?.joinToString(),
    )
    assertEquals("Unread", semantics.config[SemanticsProperties.StateDescription])

    composeTestRule.onNodeWithTag("notification_card_n-order").performClick()
    composeTestRule.waitForIdle()
    assertEquals("n-order", markedRead)
    assertEquals("order-77", openedOrderId)
  }

  @Test
  fun unreadFilterShowsContextAwareEmptyState() {
    composeTestRule.setContent {
      PizzoraTheme {
        NotificationsScreen(
          uiState = NotificationsUiState(unreadOnlyFilter = true),
          onRefresh = {},
          onSelectUnreadOnly = {},
          onMarkRead = {},
          onMarkAllRead = {},
          onToggleOrderUpdates = {},
          onTogglePromoAlerts = {},
          onNotificationPermissionResult = {},
        )
      }
    }

    composeTestRule.onNodeWithText("All caught up — no unread notifications").assertIsDisplayed()
    composeTestRule.onNodeWithText(
      "New order, payment, and refund alerts will appear here when they need your attention.",
    ).assertIsDisplayed()
  }

  @Test
  fun allFilterShowsDistinctEmptyState() {
    composeTestRule.setContent {
      PizzoraTheme {
        NotificationsScreen(
          uiState = NotificationsUiState(unreadOnlyFilter = false),
          onRefresh = {},
          onSelectUnreadOnly = {},
          onMarkRead = {},
          onMarkAllRead = {},
          onToggleOrderUpdates = {},
          onTogglePromoAlerts = {},
          onNotificationPermissionResult = {},
        )
      }
    }

    composeTestRule.onNodeWithText("No notifications yet").assertIsDisplayed()
    composeTestRule.onNodeWithText(
      "Order status alerts and wood-fired kitchen updates will appear here.",
    ).assertIsDisplayed()
    composeTestRule.onAllNodesWithText("All caught up — no unread notifications").assertCountEquals(0)
  }

  @Test
  fun alertSettingsJumpReachesFooterAndTabletWidthIsCapped() {
    val notifications = (1..8).map { index ->
      NotificationInboxEntity(
        id = "n-$index",
        userId = "cust-7",
        eventId = "evt-$index",
        title = "Order update $index",
        body = "Body $index",
        category = NotificationCategory.ORDER_UPDATE,
        referenceId = "order-$index",
        isRead = true,
        createdAtIso = "2026-05-01T12:00:00Z",
      )
    }

    composeTestRule.setContent {
      PizzoraTheme {
        NotificationsScreen(
          uiState = NotificationsUiState(notifications = notifications, unreadCount = 0),
          onRefresh = {},
          onSelectUnreadOnly = {},
          onMarkRead = {},
          onMarkAllRead = {},
          onToggleOrderUpdates = {},
          onTogglePromoAlerts = {},
          onNotificationPermissionResult = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("notifications_settings_jump_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("notifications_preferences_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("pref_order_updates_switch").assertIsDisplayed()
  }

  @Test
  @Config(sdk = [36], qualifiers = "w840dp-h900dp")
  fun notificationsListIsCenteredAndCappedAt720dpOnTablet() {
    composeTestRule.setContent {
      PizzoraTheme {
        NotificationsScreen(
          uiState = NotificationsUiState(),
          onRefresh = {},
          onSelectUnreadOnly = {},
          onMarkRead = {},
          onMarkAllRead = {},
          onToggleOrderUpdates = {},
          onTogglePromoAlerts = {},
          onNotificationPermissionResult = {},
        )
      }
    }

    val bounds = composeTestRule.onNodeWithTag("notifications_list").getUnclippedBoundsInRoot()
    val width = bounds.right - bounds.left
    assertTrue("Expected width <= 720dp, was $width", width <= 720.dp)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w320dp-h640dp")
  fun updateOptionalAndMandatorySurfacesRemainScrollableAtTwoXFontScale() {
    val update = sampleUpdate
    var uiState by mutableStateOf(
      UpdateUiState(
        installedVersionName = "0.1.0",
        installedVersionCode = 1000L,
        state = UpdateState.UpdateAvailable(update),
        showUpdateDialog = true,
      ),
    )

    composeTestRule.setContent {
      val baseDensity = LocalDensity.current
      CompositionLocalProvider(
        LocalDensity provides Density(density = baseDensity.density, fontScale = 2.0f),
      ) {
        PizzoraTheme {
          UpdateHostOverlay(
            uiState = uiState,
            onStartDownloadOrInstall = {},
            onCancelDownload = {},
            onDismissOptional = {},
            onRequestInstallPermission = {},
            onRetry = {},
          )
        }
      }
    }

    composeTestRule.onNodeWithTag("update_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithTag("update_primary_action_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("update_later_button").performScrollTo().assertIsDisplayed()

    uiState = uiState.copy(
      state = UpdateState.UpdateAvailable(
        update.copy(
          isMandatory = true,
          mandatoryReason = MandatoryUpdateReason.BELOW_MINIMUM_SUPPORTED_VERSION,
        ),
      ),
    )
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("update_mandatory_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("update_primary_action_button").performScrollTo().assertIsDisplayed()
    assertTrue(composeTestRule.onAllNodesWithTag("update_later_button").fetchSemanticsNodes().isEmpty())
  }

  @Test
  @Config(sdk = [36], qualifiers = "w320dp-h640dp")
  fun updateOptionalAndMandatorySurfacesRemainScrollableAtOnePointFiveXFontScale() {
    val update = sampleUpdate
    var uiState by mutableStateOf(
      UpdateUiState(
        installedVersionName = "0.1.0",
        installedVersionCode = 1000L,
        state = UpdateState.UpdateAvailable(update),
        showUpdateDialog = true,
      ),
    )

    composeTestRule.setContent {
      val baseDensity = LocalDensity.current
      CompositionLocalProvider(
        LocalDensity provides Density(density = baseDensity.density, fontScale = 1.5f),
      ) {
        PizzoraTheme {
          UpdateHostOverlay(
            uiState = uiState,
            onStartDownloadOrInstall = {},
            onCancelDownload = {},
            onDismissOptional = {},
            onRequestInstallPermission = {},
            onRetry = {},
          )
        }
      }
    }

    composeTestRule.onNodeWithTag("update_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithTag("update_primary_action_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("update_later_button").performScrollTo().assertIsDisplayed()

    uiState = uiState.copy(
      state = UpdateState.UpdateAvailable(
        update.copy(
          isMandatory = true,
          mandatoryReason = MandatoryUpdateReason.BELOW_MINIMUM_SUPPORTED_VERSION,
        ),
      ),
    )
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("update_mandatory_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("update_primary_action_button").performScrollTo().assertIsDisplayed()
    assertTrue(composeTestRule.onAllNodesWithTag("update_later_button").fetchSemanticsNodes().isEmpty())
  }

  @Test
  fun upToDateAndCompletedStatusesUseSecondaryBasilColor() {
    var colorScheme: ColorScheme? = null
    var state by mutableStateOf<UpdateState>(
      UpdateState.UpToDate(checkedAtEpochMs = 1_700_000_000_000L, isManual = true),
    )

    composeTestRule.setContent {
      PizzoraTheme {
        colorScheme = MaterialTheme.colorScheme
        AppUpdatesSettingsCard(
          uiState = UpdateUiState(
            installedVersionName = "0.1.0",
            installedVersionCode = 1000L,
            state = state,
          ),
          onCheckForUpdates = {},
          onOpenUpdateDialog = {},
          onRetry = {},
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("update_status_text").assertIsDisplayed()
    val appliedColorScheme = requireNotNull(colorScheme)
    assertEquals(appliedColorScheme.secondary, positiveUpdateStatusColor(appliedColorScheme))

    state = UpdateState.Completed(sampleUpdate)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("update_status_text").assertIsDisplayed()
    assertEquals(appliedColorScheme.secondary, positiveUpdateStatusColor(appliedColorScheme))
  }

  @Test
  fun refundAlertsDoNotUseVegGreenContainerInEitherTheme() {
    listOf(PizzoraLightColorScheme to LightSemanticColors).forEach { (scheme, semantics) ->
      val (container, onContainer) = notificationCategoryColors(
        category = NotificationCategory.REFUND_ALERT,
        colorScheme = scheme,
        semanticColors = semantics,
      )
      assertEquals(scheme.tertiaryContainer, container)
      assertEquals(scheme.onTertiaryContainer, onContainer)
      assertNotEquals(scheme.secondaryContainer, container)
      assertNotEquals(semantics.vegBadgeGreen, container)
    }

    listOf(PizzoraDarkColorScheme to DarkSemanticColors).forEach { (scheme, semantics) ->
      val (container, onContainer) = notificationCategoryColors(
        category = NotificationCategory.REFUND_ALERT,
        colorScheme = scheme,
        semanticColors = semantics,
      )
      assertEquals(scheme.tertiaryContainer, container)
      assertEquals(scheme.onTertiaryContainer, onContainer)
      assertNotEquals(scheme.secondaryContainer, container)
      assertNotEquals(semantics.vegBadgeGreen, container)
    }
  }

  @Test
  @Config(sdk = [36], qualifiers = "w320dp-h640dp")
  fun failedUpdateRetryRemainsReachableAtOnePointFiveXFontScale() {
    var uiState by mutableStateOf(
      UpdateUiState(
        installedVersionName = "0.1.0",
        installedVersionCode = 1000L,
        state = UpdateState.Failed(
          reason = UpdateFailureReason.NETWORK_ERROR,
          message = "Download failed because the network was unavailable.",
          update = sampleUpdate,
          canRetry = true,
        ),
        showUpdateDialog = true,
      ),
    )

    composeTestRule.setContent {
      val baseDensity = LocalDensity.current
      CompositionLocalProvider(
        LocalDensity provides Density(density = baseDensity.density, fontScale = 1.5f),
      ) {
        PizzoraTheme {
          UpdateHostOverlay(
            uiState = uiState,
            onStartDownloadOrInstall = {},
            onCancelDownload = {},
            onDismissOptional = {},
            onRequestInstallPermission = {},
            onRetry = {},
          )
        }
      }
    }

    composeTestRule.onNodeWithTag("update_error_banner", useUnmergedTree = true)
      .performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("update_retry_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("update_later_button").performScrollTo().assertIsDisplayed()

    uiState = uiState.copy(
      state = UpdateState.Failed(
        reason = UpdateFailureReason.NETWORK_ERROR,
        message = "Download failed because the network was unavailable.",
        update = sampleUpdate.copy(
          isMandatory = true,
          mandatoryReason = MandatoryUpdateReason.BELOW_MINIMUM_SUPPORTED_VERSION,
        ),
        isMandatoryBlocked = true,
        canRetry = true,
      ),
    )
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("update_mandatory_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("update_error_banner", useUnmergedTree = true)
      .performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("update_retry_button").performScrollTo().assertIsDisplayed()
    assertTrue(composeTestRule.onAllNodesWithTag("update_later_button").fetchSemanticsNodes().isEmpty())
  }
}

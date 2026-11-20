package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.auth.AuthUiState
import com.example.auth.ForgotPasswordScreen
import com.example.auth.LoginScreen
import com.example.auth.RegisterScreen
import com.example.catalog.CustomerMenuScreen
import com.example.catalog.CustomerMenuUiState
import com.example.core.security.AuthSession
import com.example.navigation.CustomerRoleShell
import com.example.profile.CustomerProfileScreen
import com.example.profile.ProfileUiState
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.UserProfileDto
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
@Config(sdk = [36])
class Phase2AuthShellMenuUiTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val sampleUser = UserProfileDto(
    id = "cust-p2-1",
    email = "priya@pizzora.in",
    emailVerified = true,
    fullName = "Priya Nair",
    phone = "+919876543210",
    role = UserRole.CUSTOMER,
    isActive = true,
  )

  private val sampleSession = AuthSession(
    accessToken = "tok-access",
    refreshToken = "tok-refresh",
    user = sampleUser,
  )

  private val sampleCategories = listOf(
    CategoryDto(id = "cat-classic", name = "Classic Wood-Fired", sortOrder = 1, isActive = true, createdAt = "2026-11-01T10:00:00Z"),
    CategoryDto(id = "cat-gourmet", name = "Gourmet Pies", sortOrder = 2, isActive = true, createdAt = "2026-11-01T10:00:00Z"),
  )

  private val sampleProducts = listOf(
    ProductDto(
      id = "prod-marg",
      categoryId = "cat-classic",
      categoryName = "Classic Wood-Fired",
      name = "Margherita Verace",
      description = "San Marzano tomatoes, fior di latte, fresh basil",
      isVeg = true,
      isActive = true,
      isTaxable = true,
      variants = listOf(
        ProductVariantDto("var-m-1", "prod-marg", SizeCode.MEDIUM, pricePaise = 39_900L, isActive = true),
      ),
    ),
    ProductDto(
      id = "prod-pep",
      categoryId = "cat-gourmet",
      categoryName = "Gourmet Pies",
      name = "Smoked Pepperoni",
      description = "Crisp cup pepperoni, aged mozzarella, hot honey drizzle",
      isVeg = false,
      isActive = true,
      isTaxable = true,
      variants = listOf(
        ProductVariantDto("var-p-1", "prod-pep", SizeCode.MEDIUM, pricePaise = 49_900L, isActive = true),
      ),
    ),
  )

  @Test
  fun loginScreen_inlineValidationBlocksSubmitAndDisplaysErrorBanner() {
    var submittedEmail: String? = null
    composeTestRule.setContent {
      PizzoraTheme {
        LoginScreen(
          uiState = AuthUiState(errorMessage = "Invalid email or password"),
          onLoginClick = { email, _ -> submittedEmail = email },
          onNavigateToRegister = {},
          onNavigateToForgotPassword = {},
          onNavigateToVerifyEmail = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("auth_status_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("login_submit_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals(null, submittedEmail)

    composeTestRule.onNodeWithTag("login_email_input").performScrollTo().performTextInput("priya@pizzora.in")
    composeTestRule.onNodeWithTag("login_password_input").performScrollTo().performTextInput("Pizza123!")
    composeTestRule.onNodeWithTag("login_submit_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals("priya@pizzora.in", submittedEmail)
  }

  @Test
  fun registerAndForgotPassword_exposePasswordVisibilityToggles() {
    composeTestRule.setContent {
      PizzoraTheme {
        RegisterScreen(
          uiState = AuthUiState(),
          onRegisterClick = { _, _, _, _ -> },
          onBackClick = {},
        )
      }
    }
    composeTestRule.onNodeWithTag("register_password_input").performScrollTo()
    composeTestRule.onNodeWithContentDescription("Show password").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithContentDescription("Hide password").assertIsDisplayed()
  }

  @Test
  fun forgotPasswordScreen_exposesPasswordToggleAndTokenClearButton() {
    composeTestRule.setContent {
      PizzoraTheme {
        ForgotPasswordScreen(
          uiState = AuthUiState(),
          onSendResetClick = {},
          onResetPasswordClick = { _, _ -> },
          onBackClick = {},
        )
      }
    }
    composeTestRule.onNodeWithTag("reset_token_input").performScrollTo().performTextInput("RESET-99")
    composeTestRule.onNodeWithContentDescription("Clear input").assertIsDisplayed().performClick()
    composeTestRule.onNodeWithTag("reset_password_input").performScrollTo()
    composeTestRule.onNodeWithContentDescription("Show password").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithContentDescription("Hide password").assertIsDisplayed()
  }

  @Test
  fun customerShell_showsContextualTopBarTitlesAndRemovesGlobalSignOut() {
    var signedOut = false
    composeTestRule.setContent {
      PizzoraTheme {
        CustomerRoleShell(
          session = sampleSession,
          onSignOut = { signedOut = true },
        )
      }
    }

    composeTestRule.onNodeWithTag("shell_sign_out_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("customer_topbar_title").assertTextContains("PIZZORA")

    composeTestRule.onNodeWithTag("customer_tab_cart").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("customer_topbar_title").assertTextContains("Your Cart")

    composeTestRule.onNodeWithTag("customer_tab_orders").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("customer_topbar_title").assertTextContains("Your Orders")

    composeTestRule.onNodeWithTag("customer_tab_profile").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("customer_topbar_title").assertTextContains("Profile")

    // Confirm cancel keeps session, confirm signs out
    composeTestRule.onNodeWithTag("profile_sign_out_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("cancel_sign_out_button").performClick()
    composeTestRule.waitForIdle()
    assertFalse(signedOut)

    composeTestRule.onNodeWithTag("profile_sign_out_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("confirm_sign_out_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(signedOut)
  }

  @Test
  fun customerProfileScreen_requiresConfirmationBeforeSignOut() {
    var signedOut = false
    composeTestRule.setContent {
      PizzoraTheme {
        CustomerProfileScreen(
          uiState = ProfileUiState(profile = sampleUser),
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
          onSignOut = { signedOut = true },
        )
      }
    }

    composeTestRule.onNodeWithTag("profile_sign_out_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("cancel_sign_out_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    assertFalse(signedOut)

    composeTestRule.onNodeWithTag("profile_sign_out_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("confirm_sign_out_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(signedOut)
  }

  @Test
  fun customerMenuScreen_searchClearAndFilterResetWorkEndToEnd() {
    var state by mutableStateOf(
      CustomerMenuUiState(
        isLoading = false,
        categories = sampleCategories,
        allProducts = sampleProducts,
        searchQuery = "Margherita",
        vegOnly = true,
        selectedCategoryId = "cat-classic",
      ),
    )

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerMenuScreen(
          uiState = state,
          onSearchQueryChange = { q -> state = state.copy(searchQuery = q) },
          onCategorySelected = { id -> state = state.copy(selectedCategoryId = id) },
          onVegOnlyToggle = { state = state.copy(vegOnly = !state.vegOnly) },
          onClearFilters = {
            state = state.copy(searchQuery = "", vegOnly = false, selectedCategoryId = null)
          },
          onRefreshMenu = {},
          onOpenCustomizer = {},
          onDismissCustomizer = {},
          onAddCustomizedToCart = { _, _, _, _, _ -> },
        )
      }
    }

    // Active search shows clear button; hero collapses while searching
    composeTestRule.onNodeWithTag("menu_search_clear_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    assertEquals("", state.searchQuery)

    // Active veg + category filters show 1-tap Reset button
    composeTestRule.onNodeWithTag("menu_reset_filters_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    assertFalse(state.vegOnly)
    assertEquals(null, state.selectedCategoryId)
  }

  @Test
  fun customerMenuScreen_pausedStoreBlocksCardOrdering() {
    var openedProduct: ProductDto? = null
    composeTestRule.setContent {
      PizzoraTheme {
        CustomerMenuScreen(
          uiState = CustomerMenuUiState(
            isLoading = false,
            isAcceptingOrders = false,
            categories = sampleCategories,
            allProducts = sampleProducts,
          ),
          onSearchQueryChange = {},
          onCategorySelected = {},
          onVegOnlyToggle = {},
          onClearFilters = {},
          onRefreshMenu = {},
          onOpenCustomizer = { openedProduct = it },
          onDismissCustomizer = {},
          onAddCustomizedToCart = { _, _, _, _, _ -> },
        )
      }
    }

    composeTestRule.onNodeWithTag("store_closed_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("product_card_prod-marg").performClick()
    composeTestRule.waitForIdle()
    assertEquals(null, openedProduct)
    composeTestRule.onNodeWithTag("customize_product_button_prod-marg").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("customize_product_button_prod-marg").assertTextContains("Paused")
  }

  @Test
  @Config(sdk = [36], qualifiers = "w600dp-h900dp")
  fun customerMenuScreen_at600dpTabletStaysSingleColumn() {
    val menuUiState = CustomerMenuUiState(
      isLoading = false,
      categories = sampleCategories,
      allProducts = sampleProducts,
    )

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerMenuScreen(
          uiState = menuUiState,
          onSearchQueryChange = {},
          onCategorySelected = {},
          onVegOnlyToggle = {},
          onClearFilters = {},
          onRefreshMenu = {},
          onOpenCustomizer = {},
          onDismissCustomizer = {},
          onAddCustomizedToCart = { _, _, _, _, _ -> },
        )
      }
    }

    composeTestRule.onNodeWithTag("product_card_prod-marg").assertIsDisplayed()
    composeTestRule.onNodeWithTag("product_card_prod-pep").assertIsDisplayed()
    val bounds600First = composeTestRule.onNodeWithTag("product_card_prod-marg").fetchSemanticsNode().boundsInRoot
    val bounds600Second = composeTestRule.onNodeWithTag("product_card_prod-pep").fetchSemanticsNode().boundsInRoot
    assertTrue("Expected 600dp content width to stay single-column", bounds600Second.top >= bounds600First.bottom - 1f)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w840dp-h900dp")
  fun customerMenuScreen_at840dpExpandedRendersTwoColumns() {
    val menuUiState = CustomerMenuUiState(
      isLoading = false,
      categories = sampleCategories,
      allProducts = sampleProducts,
    )

    composeTestRule.setContent {
      PizzoraTheme {
        CustomerMenuScreen(
          uiState = menuUiState,
          onSearchQueryChange = {},
          onCategorySelected = {},
          onVegOnlyToggle = {},
          onClearFilters = {},
          onRefreshMenu = {},
          onOpenCustomizer = {},
          onDismissCustomizer = {},
          onAddCustomizedToCart = { _, _, _, _, _ -> },
        )
      }
    }

    composeTestRule.onNodeWithTag("product_card_prod-marg").assertIsDisplayed()
    composeTestRule.onNodeWithTag("product_card_prod-pep").assertIsDisplayed()
    val bounds840First = composeTestRule.onNodeWithTag("product_card_prod-marg").fetchSemanticsNode().boundsInRoot
    val bounds840Second = composeTestRule.onNodeWithTag("product_card_prod-pep").fetchSemanticsNode().boundsInRoot
    assertTrue("Expected 840dp content width to render 2-column grid", bounds840Second.left > bounds840First.right - 1f)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w320dp-h640dp")
  fun customerMenuScreen_at320dpWith15xFontScaleRendersCleanly() {
    val menuUiState = CustomerMenuUiState(
      isLoading = false,
      categories = sampleCategories,
      allProducts = sampleProducts,
    )

    composeTestRule.setContent {
      val baseDensity = LocalDensity.current
      CompositionLocalProvider(
        LocalDensity provides Density(baseDensity.density, fontScale = 1.5f),
      ) {
        PizzoraTheme {
          CustomerMenuScreen(
            uiState = menuUiState,
            onSearchQueryChange = {},
            onCategorySelected = {},
            onVegOnlyToggle = {},
            onClearFilters = {},
            onRefreshMenu = {},
            onOpenCustomizer = {},
            onDismissCustomizer = {},
            onAddCustomizedToCart = { _, _, _, _, _ -> },
          )
        }
      }
    }

    composeTestRule.onNodeWithTag("menu_search_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("veg_only_filter_chip").assertIsDisplayed()
    composeTestRule.onNodeWithTag("product_card_prod-marg").assertIsDisplayed()
  }
}

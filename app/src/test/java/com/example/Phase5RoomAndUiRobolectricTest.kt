package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.auth.AuthRepository
import com.example.auth.AuthViewModel
import com.example.core.database.CartItemEntity
import com.example.core.database.CartItemToppingEntity
import com.example.core.database.NotificationInboxEntity
import com.example.core.database.PizzoraDatabase
import com.example.core.security.AuthSession
import com.example.navigation.PizzoraApp
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase5RoomAndUiRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var db: PizzoraDatabase

  private class StubAuthRepository : AuthRepository {
    private val _session = MutableStateFlow<AuthSession?>(null)
    private val _muted = MutableStateFlow(false)
    override val sessionFlow: StateFlow<AuthSession?> = _session.asStateFlow()
    var lastLoginEmail: String? = null

    override fun observeKdsSoundMuted(): Flow<Boolean> = _muted
    override suspend fun setKdsSoundMuted(muted: Boolean) {
      _muted.value = muted
    }

    override suspend fun login(email: String, password: String): AuthSession {
      lastLoginEmail = email
      val role = when {
        email.startsWith("kitchen") -> UserRole.KITCHEN_STAFF
        email.startsWith("owner") -> UserRole.OWNER
        else -> UserRole.CUSTOMER
      }
      val session = AuthSession(
        accessToken = "acc",
        refreshToken = "ref",
        user = UserProfileDto(
          id = "u-1",
          email = email,
          emailVerified = true,
          fullName = "Test $role",
          phone = "9999999999",
          role = role,
          isActive = true,
        ),
      )
      _session.value = session
      return session
    }

    override suspend fun register(email: String, password: String, fullName: String, phone: String): String =
      "Registered"

    override suspend fun verifyEmail(token: String): String = "Verified"
    override suspend fun resendVerification(email: String): String = "Resent"
    override suspend fun forgotPassword(email: String): String = "Sent"
    override suspend fun resetPassword(token: String, newPassword: String): String = "Reset"
    override suspend fun logout() {
      _session.value = null
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
    db.close()
  }

  @Test
  fun `room relational cart_item_toppings cascades cleanly and notification inbox deduplicates event_id`() = runBlocking {
    val cartDao = db.cartDao()
    val item = CartItemEntity(
      id = "ci-1",
      productId = "prod-margherita",
      productName = "Margherita",
      variantId = "var-med",
      sizeCode = "MEDIUM",
      crustId = "crust-sourdough",
      crustName = "Sourdough",
      unitPricePaise = 39_900L,
      quantity = 2,
    )
    val toppings = listOf(
      CartItemToppingEntity("ci-1", "top-basil", "Fresh Basil", 3_500L),
      CartItemToppingEntity("ci-1", "top-burrata", "Burrata", 9_000L),
    )
    cartDao.upsertItemWithToppings(item, toppings)

    val loaded = cartDao.observeCartItemsWithToppings().first()
    assertEquals(1, loaded.size)
    assertEquals(2, loaded.first().toppings.size)
    assertEquals(2, cartDao.observeCartBadgeCount().first())

    cartDao.deleteItemById("ci-1")
    assertEquals(0, cartDao.observeCartItemsWithToppings().first().size)

    val notifDao = db.notificationInboxDao()
    val n1 = NotificationInboxEntity(
      id = "n-1",
      eventId = "evt-100",
      title = "Order Placed",
      body = "Kitchen received #PZ-1",
      category = "ORDER",
      referenceId = "ord-1",
      isRead = false,
      createdAtIso = "2026-11-13T10:00:00Z",
    )
    notifDao.insertIgnore(listOf(n1, n1.copy(id = "n-2")))
    assertEquals(1, notifDao.observeNotifications().first().size)
  }

  @Test
  fun `login routes CUSTOMER, KITCHEN_STAFF, and OWNER into distinct role shells`() {
    val repo = StubAuthRepository()
    val vm = AuthViewModel(repo)

    composeTestRule.setContent { PizzoraApp(authViewModel = vm) }
    composeTestRule.waitForIdle()

    // Login as Customer
    composeTestRule.onNodeWithTag("login_email_input").performScrollTo().performTextInput("customer@pizzora.test")
    composeTestRule.onNodeWithTag("login_password_input").performScrollTo().performTextInput("Pass1234!")
    composeTestRule.onNodeWithTag("login_submit_button").performScrollTo().performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("customer_shell_root").assertIsDisplayed()

    // Sign out -> back to login
    composeTestRule.onNodeWithTag("shell_sign_out_button").performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("login_email_input").performScrollTo().assertIsDisplayed()

    // Login as Kitchen Staff -> Dark KDS shell
    composeTestRule.onNodeWithTag("login_email_input").performScrollTo().performTextInput("kitchen@pizzora.test")
    composeTestRule.onNodeWithTag("login_password_input").performScrollTo().performTextInput("Pass1234!")
    composeTestRule.onNodeWithTag("login_submit_button").performScrollTo().performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("kds_shell_root").assertIsDisplayed()
    composeTestRule.onNodeWithTag("kds_mute_button").assertIsDisplayed()

    composeTestRule.onNodeWithTag("shell_sign_out_button").performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.waitForIdle()

    // Login as Owner -> Owner shell
    composeTestRule.onNodeWithTag("login_email_input").performScrollTo().performTextInput("owner@pizzora.test")
    composeTestRule.onNodeWithTag("login_password_input").performScrollTo().performTextInput("Pass1234!")
    composeTestRule.onNodeWithTag("login_submit_button").performScrollTo().performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("owner_shell_root").assertIsDisplayed()
  }

  @Test
  fun `auth navigation moves between Login, Register, VerifyEmail, and ForgotPassword`() {
    val vm = AuthViewModel(StubAuthRepository())
    composeTestRule.setContent { PizzoraApp(authViewModel = vm) }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("login_go_register_button").performScrollTo().performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("register_submit_button").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("auth_back_button").performScrollTo().performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("login_forgot_password_button").performScrollTo().performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("forgot_send_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("reset_submit_button").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("auth_back_button").performScrollTo().performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("login_verify_email_button").performScrollTo().performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("verify_submit_button").performScrollTo().assertIsDisplayed()
  }
}

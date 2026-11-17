package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.auth.AuthViewModel
import com.example.cart.CartViewModel
import com.example.catalog.CustomerCatalogViewModel
import com.example.checkout.CheckoutQuoteViewModel
import com.example.checkout.CheckoutViewModel
import com.example.kds.KdsViewModel
import com.example.navigation.PizzoraApp
import com.example.notifications.NotificationHelper
import com.example.notifications.NotificationsViewModel
import com.example.notifications.OrderSyncScheduler
import com.example.orders.OrdersViewModel
import com.example.owner.OwnerViewModel
import com.example.profile.ProfileViewModel
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), PaymentResultWithDataListener {
  private var openNotificationsFromIntent by mutableStateOf(false)
  private var notificationsViewModelRef: NotificationsViewModel? = null
  internal lateinit var ordersViewModel: OrdersViewModel
    private set

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val container = (application as PizzoraApplication).appContainer
    ordersViewModel = ViewModelProvider(this, container.ordersViewModelFactory())[OrdersViewModel::class.java]
    if (savedInstanceState != null) {
      ordersViewModel.restorePendingRazorpayLaunch(
        orderId = savedInstanceState.getString(KEY_PENDING_RAZORPAY_ORDER_ID),
        gatewayOrderId = savedInstanceState.getString(KEY_PENDING_RAZORPAY_GATEWAY_ORDER_ID),
      )
    }
    openNotificationsFromIntent = intent?.getBooleanExtra(NotificationHelper.EXTRA_OPEN_NOTIFICATIONS, false) == true

    setContent {
      val authViewModel: AuthViewModel = viewModel(factory = container.authViewModelFactory())
      val catalogViewModel: CustomerCatalogViewModel = viewModel(factory = container.customerCatalogViewModelFactory())
      val cartViewModel: CartViewModel = viewModel(factory = container.cartViewModelFactory())
      val checkoutQuoteViewModel: CheckoutQuoteViewModel = viewModel(factory = container.checkoutQuoteViewModelFactory())
      val checkoutViewModel: CheckoutViewModel = viewModel(factory = container.checkoutViewModelFactory())
      val kdsViewModel: KdsViewModel = viewModel(factory = container.kdsViewModelFactory())
      val ownerViewModel: OwnerViewModel = viewModel(factory = container.ownerViewModelFactory())
      val profileViewModel: ProfileViewModel = viewModel(factory = container.profileViewModelFactory())
      val notificationsViewModel: NotificationsViewModel = viewModel(factory = container.notificationsViewModelFactory())
      notificationsViewModelRef = notificationsViewModel

      PizzoraApp(
        authViewModel = authViewModel,
        catalogViewModel = catalogViewModel,
        cartViewModel = cartViewModel,
        checkoutQuoteViewModel = checkoutQuoteViewModel,
        checkoutViewModel = checkoutViewModel,
        ordersViewModel = ordersViewModel,
        kdsViewModel = kdsViewModel,
        ownerViewModel = ownerViewModel,
        profileViewModel = profileViewModel,
        notificationsViewModel = notificationsViewModel,
        razorpayCheckoutLauncher = container.razorpayCheckoutLauncher,
        openNotificationsRequested = openNotificationsFromIntent,
        onNotificationsRequestHandled = { openNotificationsFromIntent = false },
      )
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    if (::ordersViewModel.isInitialized) {
      ordersViewModel.uiState.value.pendingRazorpayLaunch?.let { pending ->
        outState.putString(KEY_PENDING_RAZORPAY_ORDER_ID, pending.orderId)
        outState.putString(KEY_PENDING_RAZORPAY_GATEWAY_ORDER_ID, pending.gatewayOrderId)
      }
    }
  }

  override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
    if (::ordersViewModel.isInitialized) {
      ordersViewModel.onRazorpaySdkSuccess(razorpayPaymentId, paymentData)
    }
  }

  override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
    if (::ordersViewModel.isInitialized) {
      ordersViewModel.onRazorpaySdkError(code, response, paymentData)
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_NOTIFICATIONS, false)) {
      openNotificationsFromIntent = true
    }
  }

  override fun onResume() {
    super.onResume()
    val container = (application as PizzoraApplication).appContainer
    notificationsViewModelRef?.refreshPermissionState()
    lifecycleScope.launch {
      if (container.tokenStorage.currentSession() != null) {
        OrderSyncScheduler.schedulePeriodicSync(this@MainActivity)
        OrderSyncScheduler.triggerExpeditedSync(this@MainActivity)
        notificationsViewModelRef?.syncInbox()
      }
    }
  }

  companion object {
    const val KEY_PENDING_RAZORPAY_ORDER_ID = "pending_razorpay_order_id"
    const val KEY_PENDING_RAZORPAY_GATEWAY_ORDER_ID = "pending_razorpay_gateway_order_id"
  }
}

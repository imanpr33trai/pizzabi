package com.example.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.R
import com.example.auth.AuthNavigationEvent
import com.example.auth.AuthViewModel
import com.example.auth.ForgotPasswordScreen
import com.example.auth.LoginScreen
import com.example.auth.RegisterScreen
import com.example.auth.VerifyEmailScreen
import com.example.cart.CartScreen
import com.example.cart.CartViewModel
import androidx.compose.runtime.mutableStateOf
import com.example.catalog.CustomerCatalogViewModel
import com.example.catalog.CustomerMenuScreen
import com.example.checkout.CheckoutQuoteViewModel
import com.example.checkout.CheckoutScreen
import com.example.checkout.CheckoutViewModel
import com.example.core.security.AuthSession
import com.example.orders.OrdersScreen
import com.example.orders.OrdersViewModel
import com.example.ui.theme.PizzoraMonoTitle
import com.example.ui.theme.PizzoraTheme
import com.pizzora.contracts.UserRole
import kotlinx.serialization.Serializable

@Serializable
data object LoginRoute

@Serializable
data object RegisterRoute

@Serializable
data class VerifyEmailRoute(val initialEmail: String = "")

@Serializable
data object ForgotPasswordRoute

@Serializable
data object CustomerShellRoute

@Serializable
data object KdsShellRoute

@Serializable
data object OwnerShellRoute

@Composable
fun PizzoraApp(
  authViewModel: AuthViewModel,
  catalogViewModel: CustomerCatalogViewModel? = null,
  cartViewModel: CartViewModel? = null,
  checkoutQuoteViewModel: CheckoutQuoteViewModel? = null,
  checkoutViewModel: CheckoutViewModel? = null,
  ordersViewModel: OrdersViewModel? = null,
) {
  val session by authViewModel.sessionFlow.collectAsStateWithLifecycle()
  val uiState by authViewModel.uiState.collectAsStateWithLifecycle()
  val kdsMuted by authViewModel.kdsSoundMuted.collectAsStateWithLifecycle()
  val isKdsSession = session?.user?.role == UserRole.KITCHEN_STAFF

  PizzoraTheme(kdsMode = isKdsSession) {
    val navController = rememberNavController()

    var wasSignedIn by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(session != null) }

    LaunchedEffect(session?.user?.id, session?.user?.role) {
      val current = session
      if (current != null) {
        wasSignedIn = true
        val target: Any = when (current.user.role) {
          UserRole.CUSTOMER -> CustomerShellRoute
          UserRole.KITCHEN_STAFF -> KdsShellRoute
          UserRole.OWNER -> OwnerShellRoute
        }
        navController.navigate(target) {
          popUpTo(0) { inclusive = true }
          launchSingleTop = true
        }
      } else if (wasSignedIn) {
        wasSignedIn = false
        navController.navigate(LoginRoute) {
          popUpTo(0) { inclusive = true }
          launchSingleTop = true
        }
      }
    }

    LaunchedEffect(authViewModel) {
      authViewModel.events.collect { event ->
        when (event) {
          is AuthNavigationEvent.NavigateToVerifyEmail -> {
            navController.navigate(VerifyEmailRoute(event.email))
          }
          AuthNavigationEvent.NavigateBackToLogin -> {
            navController.navigate(LoginRoute) {
              popUpTo<LoginRoute> { inclusive = true }
            }
          }
        }
      }
    }

    NavHost(
      navController = navController,
      startDestination = LoginRoute,
    ) {
      composable<LoginRoute> {
        LoginScreen(
          uiState = uiState,
          onLoginClick = authViewModel::login,
          onNavigateToRegister = {
            authViewModel.clearMessages()
            navController.navigate(RegisterRoute)
          },
          onNavigateToForgotPassword = {
            authViewModel.clearMessages()
            navController.navigate(ForgotPasswordRoute)
          },
          onNavigateToVerifyEmail = { email ->
            authViewModel.clearMessages()
            navController.navigate(VerifyEmailRoute(email))
          },
        )
      }

      composable<RegisterRoute> {
        RegisterScreen(
          uiState = uiState,
          onRegisterClick = authViewModel::register,
          onBackClick = { navController.popBackStack() },
        )
      }

      composable<VerifyEmailRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<VerifyEmailRoute>()
        VerifyEmailScreen(
          initialEmail = route.initialEmail,
          uiState = uiState,
          onVerifyClick = authViewModel::verifyEmail,
          onResendClick = authViewModel::resendVerification,
          onBackClick = { navController.popBackStack() },
        )
      }

      composable<ForgotPasswordRoute> {
        ForgotPasswordScreen(
          uiState = uiState,
          onSendResetClick = authViewModel::forgotPassword,
          onResetPasswordClick = authViewModel::resetPassword,
          onBackClick = { navController.popBackStack() },
        )
      }

      composable<CustomerShellRoute> {
        session?.let { active ->
          CustomerRoleShell(
            session = active,
            catalogViewModel = catalogViewModel,
            cartViewModel = cartViewModel,
            checkoutQuoteViewModel = checkoutQuoteViewModel,
            checkoutViewModel = checkoutViewModel,
            ordersViewModel = ordersViewModel,
            onSignOut = authViewModel::logout,
          )
        }
      }

      composable<KdsShellRoute> {
        session?.let { active ->
          KdsRoleShell(
            session = active,
            isMuted = kdsMuted,
            onToggleMute = { authViewModel.setKdsSoundMuted(!kdsMuted) },
            onSignOut = authViewModel::logout,
          )
        }
      }

      composable<OwnerShellRoute> {
        session?.let { active ->
          OwnerRoleShell(session = active, onSignOut = authViewModel::logout)
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerRoleShell(
  session: AuthSession,
  catalogViewModel: CustomerCatalogViewModel? = null,
  cartViewModel: CartViewModel? = null,
  checkoutQuoteViewModel: CheckoutQuoteViewModel? = null,
  checkoutViewModel: CheckoutViewModel? = null,
  ordersViewModel: OrdersViewModel? = null,
  onSignOut: () -> Unit,
) {
  val tabs = listOf(
    "Menu" to Icons.Filled.LocalPizza,
    "Cart" to Icons.Filled.ShoppingCart,
    "Orders" to Icons.AutoMirrored.Filled.ReceiptLong,
    "Profile" to Icons.Filled.Person,
  )
  var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
  var isCheckoutOpen by rememberSaveable { mutableStateOf(false) }
  val snackbarHostState = remember { SnackbarHostState() }
  val cartBadgeCount = if (cartViewModel != null) {
    cartViewModel.badgeCount.collectAsStateWithLifecycle().value
  } else {
    0
  }

  LaunchedEffect(session.user.id, cartViewModel) {
    cartViewModel?.syncOnSessionActive()
  }

  LaunchedEffect(session.user.id, ordersViewModel) {
    ordersViewModel?.loadOrders()
  }

  LaunchedEffect(cartViewModel) {
    cartViewModel?.events?.collect { message ->
      snackbarHostState.showSnackbar(message)
    }
  }

  LaunchedEffect(checkoutViewModel, ordersViewModel) {
    checkoutViewModel?.orderCreatedEvents?.collect { createdOrder ->
      isCheckoutOpen = false
      selectedIndex = 2
      ordersViewModel?.showCreatedOrder(createdOrder)
      snackbarHostState.showSnackbar("Order ${createdOrder.orderNumber} placed!")
    }
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag("customer_shell_root")) {
    val useRail = maxWidth >= 600.dp
    Scaffold(
      contentWindowInsets = WindowInsets.safeDrawing,
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      topBar = {
        TopAppBar(
          title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall) },
          actions = {
            IconButton(
              onClick = onSignOut,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("shell_sign_out_button"),
            ) {
              Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = stringResource(R.string.action_sign_out))
            }
          },
        )
      },
      bottomBar = {
        if (!useRail) {
          NavigationBar(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            tabs.forEachIndexed { idx, (label, icon) ->
              NavigationBarItem(
                selected = selectedIndex == idx,
                onClick = { selectedIndex = idx },
                icon = {
                  CustomerTabIcon(label = label, icon = icon, badgeCount = if (idx == 1) cartBadgeCount else 0)
                },
                label = { Text(label) },
                modifier = Modifier.testTag("customer_tab_${label.lowercase()}"),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (useRail) {
          NavigationRail {
            tabs.forEachIndexed { idx, (label, icon) ->
              NavigationRailItem(
                selected = selectedIndex == idx,
                onClick = { selectedIndex = idx },
                icon = {
                  CustomerTabIcon(label = label, icon = icon, badgeCount = if (idx == 1) cartBadgeCount else 0)
                },
                label = { Text(label) },
                modifier = Modifier.testTag("customer_tab_${label.lowercase()}"),
              )
            }
          }
        }

        when {
          selectedIndex == 0 && catalogViewModel != null -> {
            val menuState by catalogViewModel.uiState.collectAsStateWithLifecycle()
            CustomerMenuScreen(
              uiState = menuState,
              onSearchQueryChange = catalogViewModel::onSearchQueryChange,
              onCategorySelected = catalogViewModel::onCategorySelected,
              onVegOnlyToggle = catalogViewModel::onVegOnlyToggle,
              onClearFilters = catalogViewModel::clearFilters,
              onRefreshMenu = { catalogViewModel.loadMenu(initialLoad = false) },
              onOpenCustomizer = catalogViewModel::openCustomizer,
              onDismissCustomizer = catalogViewModel::dismissCustomizer,
              onAddCustomizedToCart = { prod, variant, crust, toppings, qty ->
                cartViewModel?.addCustomizedPizza(prod, variant, crust, toppings, qty)
              },
              modifier = Modifier.weight(1f),
            )
          }
          selectedIndex == 1 && cartViewModel != null -> {
            val cartState by cartViewModel.uiState.collectAsStateWithLifecycle()
            val quoteState = checkoutQuoteViewModel?.uiState?.collectAsStateWithLifecycle()?.value

            LaunchedEffect(cartState.items, cartState.subtotalPaise, checkoutQuoteViewModel) {
              checkoutQuoteViewModel?.onCartChanged(cartState.items, cartState.subtotalPaise)
            }

            if (isCheckoutOpen && checkoutViewModel != null) {
              val checkoutState by checkoutViewModel.uiState.collectAsStateWithLifecycle()
              LaunchedEffect(Unit) {
                checkoutViewModel.loadAddresses()
              }
              CheckoutScreen(
                uiState = checkoutState,
                quoteState = quoteState,
                itemCount = cartState.totalItemCount,
                subtotalPaise = cartState.subtotalPaise,
                onFulfillmentTypeChange = { checkoutQuoteViewModel?.onFulfillmentTypeChange(it) },
                onSelectAddress = checkoutViewModel::selectAddress,
                onOpenAddAddress = checkoutViewModel::openAddAddressDialog,
                onDismissAddAddress = checkoutViewModel::dismissAddAddressDialog,
                onSaveAddress = checkoutViewModel::saveNewAddress,
                onSelectPaymentMethod = checkoutViewModel::selectPaymentMethod,
                onPlaceOrder = {
                  checkoutViewModel.placeOrder(
                    cartItems = cartState.items,
                    fulfillmentType = quoteState?.fulfillmentType ?: com.pizzora.contracts.FulfillmentType.DELIVERY,
                    couponCode = quoteState?.appliedCouponCode,
                    onCartCleared = {
                      cartViewModel.clearCart()
                      checkoutQuoteViewModel?.removeCoupon()
                    },
                  )
                },
                onBackToCart = { isCheckoutOpen = false },
                modifier = Modifier.weight(1f),
              )
            } else {
              CartScreen(
                uiState = cartState,
                onUpdateQuantity = cartViewModel::updateQuantity,
                onRemoveItem = cartViewModel::removeItem,
                onClearCart = cartViewModel::clearCart,
                onValidateAndSync = cartViewModel::validateAndSyncCart,
                onBrowseMenu = { selectedIndex = 0 },
                modifier = Modifier.weight(1f),
                quoteState = quoteState,
                onFulfillmentTypeChange = { checkoutQuoteViewModel?.onFulfillmentTypeChange(it) },
                onPincodeInputChange = { checkoutQuoteViewModel?.onPincodeInputChange(it) },
                onCheckPincode = { checkoutQuoteViewModel?.applyPincodeCheck() },
                onCouponInputChange = { checkoutQuoteViewModel?.onCouponInputChange(it) },
                onApplyCoupon = { checkoutQuoteViewModel?.applyCouponCode(it) },
                onRemoveCoupon = { checkoutQuoteViewModel?.removeCoupon() },
                onOpenCouponPicker = { checkoutQuoteViewModel?.openCouponPicker() },
                onDismissCouponPicker = { checkoutQuoteViewModel?.dismissCouponPicker() },
                onProceedToCheckout = if (checkoutViewModel != null) {
                  {
                    isCheckoutOpen = true
                  }
                } else {
                  null
                },
              )
            }
          }
          selectedIndex == 2 && ordersViewModel != null -> {
            val ordersState by ordersViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
              ordersViewModel.loadOrders()
            }
            OrdersScreen(
              uiState = ordersState,
              onRefreshOrders = ordersViewModel::loadOrders,
              onOpenOrder = ordersViewModel::openOrderDetail,
              onBackToList = ordersViewModel::clearSelectedOrder,
              onCancelOrder = ordersViewModel::cancelSelectedOrder,
              onBrowseMenu = { selectedIndex = 0 },
              modifier = Modifier.weight(1f),
            )
          }
          else -> {
            RoleShellCard(
              title = "${stringResource(R.string.role_customer_title)} • ${tabs[selectedIndex].first}",
              subtitle = "Signed in as ${session.user.fullName} (${session.user.email})",
              badge = "CUSTOMER",
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CustomerTabIcon(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  badgeCount: Int,
) {
  if (badgeCount > 0) {
    BadgedBox(
      badge = {
        Badge {
          Text(
            text = badgeCount.toString(),
            modifier = Modifier.testTag("cart_tab_badge"),
          )
        }
      },
    ) {
      Icon(icon, contentDescription = label)
    }
  } else {
    Icon(icon, contentDescription = label)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KdsRoleShell(
  session: AuthSession,
  isMuted: Boolean,
  onToggleMute: () -> Unit,
  onSignOut: () -> Unit,
) {
  Scaffold(
    modifier = Modifier.fillMaxSize().testTag("kds_shell_root"),
    contentWindowInsets = WindowInsets.safeDrawing,
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
          titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        title = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(Icons.Filled.Kitchen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.role_kds_title), style = MaterialTheme.typography.titleLarge)
          }
        },
        actions = {
          IconButton(
            onClick = onToggleMute,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("kds_mute_button"),
          ) {
            Icon(
              imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
              contentDescription = if (isMuted) "Unmute KDS chimes" else "Mute KDS chimes",
            )
          }
          IconButton(
            onClick = onSignOut,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("shell_sign_out_button"),
          ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = stringResource(R.string.action_sign_out))
          }
        },
      )
    },
  ) { innerPadding ->
    RoleShellCard(
      title = "Live Kitchen Ticket Queue",
      subtitle = "Operator: ${session.user.fullName} • High-Contrast Dark Mode Active",
      badge = "KITCHEN_STAFF",
      modifier = Modifier.fillMaxSize().padding(innerPadding),
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerRoleShell(
  session: AuthSession,
  onSignOut: () -> Unit,
) {
  val tabs = listOf(
    "Dashboard" to Icons.Filled.Dashboard,
    "Catalog" to Icons.Filled.RestaurantMenu,
    "Operations" to Icons.Filled.Inventory2,
    "Reports" to Icons.Filled.Assessment,
    "More" to Icons.Filled.MoreHoriz,
  )
  var selectedIndex by rememberSaveable { mutableIntStateOf(0) }

  BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag("owner_shell_root")) {
    val useRail = maxWidth >= 600.dp
    Scaffold(
      contentWindowInsets = WindowInsets.safeDrawing,
      topBar = {
        TopAppBar(
          title = { Text(stringResource(R.string.role_owner_title), style = MaterialTheme.typography.headlineSmall) },
          actions = {
            IconButton(
              onClick = onSignOut,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("shell_sign_out_button"),
            ) {
              Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = stringResource(R.string.action_sign_out))
            }
          },
        )
      },
      bottomBar = {
        if (!useRail) {
          NavigationBar(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            tabs.forEachIndexed { idx, (label, icon) ->
              NavigationBarItem(
                selected = selectedIndex == idx,
                onClick = { selectedIndex = idx },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label) },
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (useRail) {
          NavigationRail {
            tabs.forEachIndexed { idx, (label, icon) ->
              NavigationRailItem(
                selected = selectedIndex == idx,
                onClick = { selectedIndex = idx },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label) },
              )
            }
          }
        }
        RoleShellCard(
          title = "${stringResource(R.string.role_owner_title)} • ${tabs[selectedIndex].first}",
          subtitle = "Manager: ${session.user.fullName} (${session.user.email})",
          badge = "OWNER",
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun RoleShellCard(
  title: String,
  subtitle: String,
  badge: String,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.padding(20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
      modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
    ) {
      Column(modifier = Modifier.padding(20.dp)) {
        Text(
          text = badge,
          style = PizzoraMonoTitle,
          color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = title,
          style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

package com.example.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import com.example.catalog.PizzaBuilderMode
import com.example.catalog.PizzaBuilderScreen
import com.example.checkout.CheckoutQuoteViewModel
import com.example.checkout.CheckoutScreen
import com.example.checkout.CheckoutViewModel
import com.example.core.database.CartItemWithToppings
import com.example.core.security.AuthSession
import kotlinx.coroutines.flow.collectLatest
import com.example.kds.KdsScreen
import com.example.kds.KdsViewModel
import com.example.notifications.NotificationsScreen
import com.example.notifications.NotificationsViewModel
import com.example.notifications.OrderSyncScheduler
import com.example.orders.AndroidRazorpayCheckoutLauncher
import com.example.orders.OrdersScreen
import com.example.orders.OrdersViewModel
import com.example.orders.RazorpayCheckoutLauncher
import com.example.owner.OwnerCatalogScreen
import com.example.owner.OwnerDashboardScreen
import com.example.owner.OwnerOperationsScreen
import com.example.owner.OwnerReportsAndAuditScreen
import com.example.owner.OwnerSettingsAndStaffScreen
import com.example.owner.OwnerViewModel
import com.example.profile.CustomerProfileScreen
import com.example.profile.ProfileViewModel
import com.example.ui.components.LocalReducedMotion
import com.example.ui.components.PizzoraConfirmationDialog
import com.example.ui.theme.LocalPizzoraSemanticColors
import com.example.ui.theme.PizzoraMonoTitle
import com.example.ui.theme.PizzoraTheme
import com.example.update.UpdateHostOverlay
import com.example.update.UpdateUiState
import com.example.update.UpdateViewModel
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentMethod
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
  kdsViewModel: KdsViewModel? = null,
  ownerViewModel: OwnerViewModel? = null,
  profileViewModel: ProfileViewModel? = null,
  notificationsViewModel: NotificationsViewModel? = null,
  updateViewModel: UpdateViewModel? = null,
  razorpayCheckoutLauncher: RazorpayCheckoutLauncher = AndroidRazorpayCheckoutLauncher(),
  openNotificationsRequested: Boolean = false,
  onNotificationsRequestHandled: () -> Unit = {},
) {
  val session by authViewModel.sessionFlow.collectAsStateWithLifecycle()
  val uiState by authViewModel.uiState.collectAsStateWithLifecycle()
  val kdsMuted by authViewModel.kdsSoundMuted.collectAsStateWithLifecycle()
  val updateUiState = updateViewModel?.uiState?.collectAsStateWithLifecycle()?.value
  val isKdsSession = session?.user?.role == UserRole.KITCHEN_STAFF

  LaunchedEffect(updateViewModel) {
    updateViewModel?.checkForUpdatesIfDue()
  }

  PizzoraTheme(kdsMode = isKdsSession) {
    val navController = rememberNavController()
    val appContext = LocalContext.current

    var wasSignedIn by remember { mutableStateOf(session != null) }
    var lastActiveUserId by remember { mutableStateOf(session?.user?.id) }

    fun resetSessionUiAndSync() {
      cartViewModel?.clearSessionState()
      checkoutQuoteViewModel?.clearSessionState()
      checkoutViewModel?.clearSessionState()
      ordersViewModel?.clearSessionState()
      profileViewModel?.clearSessionState()
      kdsViewModel?.clearSessionState()
      ownerViewModel?.clearSessionState()
      notificationsViewModel?.clearSessionState()
      OrderSyncScheduler.cancelSync(appContext)
    }

    LaunchedEffect(session?.user?.id, session?.user?.role) {
      val current = session
      if (current != null) {
        if (lastActiveUserId != null && lastActiveUserId != current.user.id) {
          resetSessionUiAndSync()
        }
        wasSignedIn = true
        lastActiveUserId = current.user.id
        val target: Any = when (current.user.role) {
          UserRole.CUSTOMER -> CustomerShellRoute
          UserRole.KITCHEN_STAFF -> KdsShellRoute
          UserRole.OWNER -> OwnerShellRoute
        }
        navController.navigate(target) {
          popUpTo(0) { inclusive = true }
          launchSingleTop = true
        }
      } else if (wasSignedIn || lastActiveUserId != null) {
        wasSignedIn = false
        lastActiveUserId = null
        resetSessionUiAndSync()
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

    Box(modifier = Modifier.fillMaxSize()) {
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
            key(active.user.id) {
              CustomerRoleShell(
                session = active,
                catalogViewModel = catalogViewModel,
                cartViewModel = cartViewModel,
                checkoutQuoteViewModel = checkoutQuoteViewModel,
                checkoutViewModel = checkoutViewModel,
                ordersViewModel = ordersViewModel,
                profileViewModel = profileViewModel,
                notificationsViewModel = notificationsViewModel,
                razorpayCheckoutLauncher = razorpayCheckoutLauncher,
                openNotificationsRequested = openNotificationsRequested,
                onNotificationsRequestHandled = onNotificationsRequestHandled,
                onSignOut = authViewModel::logout,
                updateUiState = updateUiState,
                onCheckForUpdates = { updateViewModel?.checkForUpdatesManually() },
                onOpenUpdateDialog = { updateViewModel?.openUpdateDialog() },
                onRetryUpdate = { updateViewModel?.retryFailedAction() },
              )
            }
          }
        }

        composable<KdsShellRoute> {
          session?.let { active ->
            key(active.user.id) {
              KdsRoleShell(
                session = active,
                kdsViewModel = kdsViewModel,
                isMuted = kdsMuted,
                onToggleMute = { authViewModel.setKdsSoundMuted(!kdsMuted) },
                onSignOut = authViewModel::logout,
                updateUiState = updateUiState,
                onCheckForUpdates = { updateViewModel?.checkForUpdatesManually() },
                onOpenUpdateDialog = { updateViewModel?.openUpdateDialog() },
                onRetryUpdate = { updateViewModel?.retryFailedAction() },
              )
            }
          }
        }

        composable<OwnerShellRoute> {
          session?.let { active ->
            key(active.user.id) {
              OwnerRoleShell(
                session = active,
                kdsViewModel = kdsViewModel,
                ownerViewModel = ownerViewModel,
                notificationsViewModel = notificationsViewModel,
                openNotificationsRequested = openNotificationsRequested,
                onNotificationsRequestHandled = onNotificationsRequestHandled,
                onSignOut = authViewModel::logout,
                updateUiState = updateUiState,
                onCheckForUpdates = { updateViewModel?.checkForUpdatesManually() },
                onOpenUpdateDialog = { updateViewModel?.openUpdateDialog() },
                onRetryUpdate = { updateViewModel?.retryFailedAction() },
              )
            }
          }
        }
      }

      if (updateViewModel != null && updateUiState != null) {
        UpdateHostOverlay(
          uiState = updateUiState,
          onStartDownloadOrInstall = updateViewModel::startUpdateDownloadAndInstall,
          onCancelDownload = updateViewModel::cancelDownload,
          onDismissOptional = updateViewModel::dismissOptionalUpdateDialog,
          onRequestInstallPermission = updateViewModel::requestInstallPermission,
          onRetry = updateViewModel::retryFailedAction,
        )
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
  profileViewModel: ProfileViewModel? = null,
  notificationsViewModel: NotificationsViewModel? = null,
  razorpayCheckoutLauncher: RazorpayCheckoutLauncher = AndroidRazorpayCheckoutLauncher(),
  openNotificationsRequested: Boolean = false,
  onNotificationsRequestHandled: () -> Unit = {},
  onSignOut: () -> Unit,
  updateUiState: UpdateUiState? = null,
  onCheckForUpdates: () -> Unit = {},
  onOpenUpdateDialog: () -> Unit = {},
  onRetryUpdate: () -> Unit = {},
) {
  val context = LocalContext.current
  val tabs = listOf(
    "Menu" to Icons.Filled.LocalPizza,
    "Cart" to Icons.Filled.ShoppingCart,
    "Orders" to Icons.AutoMirrored.Filled.ReceiptLong,
    "Profile" to Icons.Filled.Person,
  )
  var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
  var isCheckoutOpen by rememberSaveable { mutableStateOf(false) }
  var isNotificationsOpen by rememberSaveable { mutableStateOf(false) }
  var showFallbackSignOutConfirm by rememberSaveable { mutableStateOf(false) }
  var editingCartItem by remember { mutableStateOf<CartItemWithToppings?>(null) }
  var savedEditingCartItemId by rememberSaveable { mutableStateOf<String?>(null) }
  var focusRestoreItemId by remember { mutableStateOf<String?>(null) }
  var lastEditedCartItemId by remember { mutableStateOf<String?>(null) }
  var wasEditOverlayOpen by remember { mutableStateOf(false) }
  val snackbarHostState = remember { SnackbarHostState() }
  val overlaySnackbarHostState = remember { SnackbarHostState() }
  val menuState = catalogViewModel?.uiState?.collectAsStateWithLifecycle()?.value
  val cartState = cartViewModel?.uiState?.collectAsStateWithLifecycle()?.value
  val quoteState = checkoutQuoteViewModel?.uiState?.collectAsStateWithLifecycle()?.value

  LaunchedEffect(savedEditingCartItemId, cartState?.items) {
    val targetId = savedEditingCartItemId
    if (targetId != null && editingCartItem == null) {
      val restored = cartState?.items?.firstOrNull { it.item.id == targetId }
      if (restored != null) {
        editingCartItem = restored
      }
    }
  }

  LaunchedEffect(editingCartItem?.item?.id) {
    val currentId = editingCartItem?.item?.id
    if (currentId != null) {
      lastEditedCartItemId = currentId
      wasEditOverlayOpen = true
    } else if (wasEditOverlayOpen) {
      wasEditOverlayOpen = false
      focusRestoreItemId = lastEditedCartItemId
      lastEditedCartItemId = null
    }
  }

  val activeEditingCartItem = editingCartItem
  val activeBuilderProduct = menuState?.customizingProduct
  val isEditBuilderOverlayOpen = cartViewModel != null && activeEditingCartItem != null
  val isAddBuilderOverlayOpen = !isEditBuilderOverlayOpen && catalogViewModel != null && menuState != null && activeBuilderProduct != null
  val isBuilderOverlayOpen = isEditBuilderOverlayOpen || isAddBuilderOverlayOpen
  val isNotificationsOverlayOpen = isNotificationsOpen && notificationsViewModel != null
  val isCheckoutOverlayOpen = isCheckoutOpen && checkoutViewModel != null
  val hasActiveOverlay = isBuilderOverlayOpen || isNotificationsOverlayOpen || isCheckoutOverlayOpen
  val currentSnackbarHostState by rememberUpdatedState(
    if (isNotificationsOverlayOpen || isCheckoutOverlayOpen) overlaySnackbarHostState else snackbarHostState,
  )
  val cartBadgeCount = if (cartViewModel != null) {
    cartViewModel.badgeCount.collectAsStateWithLifecycle().value
  } else {
    0
  }
  val unreadNotificationsCount = if (notificationsViewModel != null) {
    notificationsViewModel.uiState.collectAsStateWithLifecycle().value.unreadCount
  } else {
    0
  }

  LaunchedEffect(isEditBuilderOverlayOpen, catalogViewModel) {
    if (isEditBuilderOverlayOpen) {
      catalogViewModel?.ensureLoaded()
    }
  }

  LaunchedEffect(openNotificationsRequested) {
    if (openNotificationsRequested) {
      isNotificationsOpen = true
      onNotificationsRequestHandled()
    }
  }

  LaunchedEffect(session.user.id, notificationsViewModel) {
    OrderSyncScheduler.schedulePeriodicSync(context)
    notificationsViewModel?.startSessionSync(session.user.id)
  }

  LaunchedEffect(session.user.id, cartViewModel) {
    cartViewModel?.syncOnSessionActive()
  }

  LaunchedEffect(session.user.id, ordersViewModel) {
    ordersViewModel?.loadOrders()
  }

  DisposableEffect(session.user.id, ordersViewModel) {
    ordersViewModel?.startLiveTracking(session.user.id)
    onDispose {
      ordersViewModel?.stopLiveTracking()
    }
  }

  LaunchedEffect(cartViewModel) {
    cartViewModel?.events?.collect { message ->
      snackbarHostState.showSnackbar(message)
    }
  }

  LaunchedEffect(cartViewModel) {
    cartViewModel?.undoPrompts?.collectLatest { prompt ->
      snackbarHostState.currentSnackbarData?.dismiss()
      val snackbarResult = snackbarHostState.showSnackbar(
        message = context.getString(R.string.cart_item_removed_fmt, prompt.productName),
        actionLabel = context.getString(R.string.action_undo),
        duration = SnackbarDuration.Long,
      )
      when (snackbarResult) {
        SnackbarResult.ActionPerformed -> cartViewModel.undoRemoval(prompt.token)
        SnackbarResult.Dismissed -> cartViewModel.dismissUndo(prompt.token)
      }
    }
  }

  LaunchedEffect(profileViewModel) {
    profileViewModel?.events?.collect { message ->
      currentSnackbarHostState.showSnackbar(message)
    }
  }

  LaunchedEffect(notificationsViewModel) {
    notificationsViewModel?.events?.collect { message ->
      currentSnackbarHostState.showSnackbar(message)
    }
  }

  LaunchedEffect(checkoutViewModel, ordersViewModel) {
    checkoutViewModel?.orderCreatedEvents?.collect { createdOrder ->
      isCheckoutOpen = false
      selectedIndex = 2
      val isPendingOnline = createdOrder.status == OrderStatus.PENDING_PAYMENT &&
        createdOrder.payment.method == PaymentMethod.RAZORPAY
      ordersViewModel?.showCreatedOrder(createdOrder, openPaymentFlowIfPending = isPendingOnline)
      OrderSyncScheduler.triggerExpeditedSync(context)
      notificationsViewModel?.syncInbox()
      val driftNotice = checkoutViewModel.uiState.value.priceDriftNotice
      snackbarHostState.showSnackbar(
        if (driftNotice != null) driftNotice
        else if (isPendingOnline) "Order ${createdOrder.orderNumber} reserved — complete online payment."
        else "Order ${createdOrder.orderNumber} placed!",
      )
    }
  }

  // Cart-quote sync: runs when cart is visible OR checkout is open
  LaunchedEffect(cartState?.items, cartState?.subtotalPaise, selectedIndex, isCheckoutOpen, checkoutQuoteViewModel) {
    if ((selectedIndex == 1 || isCheckoutOpen) && checkoutQuoteViewModel != null) {
      checkoutQuoteViewModel.onCartChanged(cartState?.items ?: emptyList(), cartState?.subtotalPaise ?: 0L)
    }
  }

  val semanticColors = LocalPizzoraSemanticColors.current
  val reducedMotion = LocalReducedMotion.current

  Box(modifier = Modifier.fillMaxSize().testTag("customer_shell_root")) {
    BoxWithConstraints(
      modifier = Modifier
        .fillMaxSize()
        .focusProperties { canFocus = !hasActiveOverlay }
        .let { base -> if (hasActiveOverlay) base.clearAndSetSemantics {} else base },
    ) {
      val shellMaxWidth = maxWidth
      val shellMaxHeight = maxHeight
      val useRail = shellMaxWidth >= 600.dp
      Scaffold(
        contentWindowInsets = if (hasActiveOverlay) WindowInsets(0) else WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
          if (!hasActiveOverlay) {
            TopAppBar(
              title = {
                when (selectedIndex) {
                  0 -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Text(
                      text = stringResource(R.string.app_name),
                      style = MaterialTheme.typography.headlineSmall,
                      modifier = Modifier.testTag("customer_topbar_title"),
                    )
                    if (menuState != null && !menuState.isAcceptingOrders) {
                      Surface(
                        color = semanticColors.warningContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag("shell_kitchen_paused_badge"),
                      ) {
                        Text(
                          text = stringResource(R.string.shell_kitchen_paused_badge),
                          style = MaterialTheme.typography.labelSmall,
                          color = semanticColors.onWarningContainer,
                          modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                      }
                    }
                  }
                  1 -> Text(
                    text = stringResource(R.string.shell_title_cart),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("customer_topbar_title"),
                  )
                  2 -> Text(
                    text = stringResource(R.string.orders_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("customer_topbar_title"),
                  )
                  else -> Text(
                    text = stringResource(R.string.shell_title_profile),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("customer_topbar_title"),
                  )
                }
              },
              actions = {
                if (notificationsViewModel != null) {
                  IconButton(
                    onClick = { isNotificationsOpen = true },
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("customer_notifications_button"),
                  ) {
                    NotificationBellIcon(unreadCount = unreadNotificationsCount)
                  }
                }
              },
            )
          }
        },
        bottomBar = {
          if (!useRail && !hasActiveOverlay) {
            NavigationBar {
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
          if (useRail && !hasActiveOverlay) {
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
            selectedIndex == 0 && catalogViewModel != null && menuState != null -> {
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
                useStandaloneBuilderHost = false,
              )
            }
            selectedIndex == 1 && cartViewModel != null -> {
              val activeCartState = cartState ?: cartViewModel.uiState.collectAsStateWithLifecycle().value
              val quoteState = checkoutQuoteViewModel?.uiState?.collectAsStateWithLifecycle()?.value

              CartScreen(
                uiState = activeCartState,
                onUpdateQuantity = cartViewModel::updateQuantity,
                onRemoveItem = cartViewModel::removeItem,
                onClearCart = {
                  editingCartItem = null
                  savedEditingCartItemId = null
                  cartViewModel.clearCart()
                },
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
                onEditItem = { entry ->
                  catalogViewModel?.ensureLoaded()
                  editingCartItem = entry
                  savedEditingCartItemId = entry.item.id
                },
                focusRestoreItemId = focusRestoreItemId,
                onFocusRestored = { focusRestoreItemId = null },
                onRetryCoupons = { checkoutQuoteViewModel?.openCouponPicker() },
                applyDockNavigationBarsPadding = false,
                shellWindowWidth = shellMaxWidth,
                shellWindowHeight = shellMaxHeight,
              )
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
                onOpenPaymentSheet = ordersViewModel::openPaymentSheet,
                onDismissPaymentSheet = ordersViewModel::dismissPaymentSheet,
                onRetryPayment = { ordersViewModel.retryPayment() },
                customerName = session.user.fullName,
                customerEmail = session.user.email,
                customerPhone = session.user.phone,
                razorpayCheckoutLauncher = razorpayCheckoutLauncher,
                onRazorpayLaunchStarted = ordersViewModel::markRazorpayLaunchStarted,
                onRazorpaySdkError = { code, desc -> ordersViewModel.onRazorpaySdkError(code, desc, null) },
                modifier = Modifier.weight(1f),
              )
            }
            selectedIndex == 3 && profileViewModel != null -> {
              val profileState by profileViewModel.uiState.collectAsStateWithLifecycle()
              LaunchedEffect(session.user.id, profileViewModel) {
                profileViewModel.loadProfileAndAddresses(session.user)
              }
              CustomerProfileScreen(
                uiState = profileState,
                onRefresh = { profileViewModel.loadProfileAndAddresses(session.user) },
                onOpenEditProfile = profileViewModel::openEditProfileDialog,
                onDismissEditProfile = profileViewModel::dismissEditProfileDialog,
                onSaveProfile = profileViewModel::saveProfile,
                onOpenAddAddress = profileViewModel::openAddAddressDialog,
                onOpenEditAddress = profileViewModel::openEditAddressDialog,
                onDismissAddressDialog = profileViewModel::dismissAddressDialog,
                onSaveAddress = profileViewModel::saveAddress,
                onSetDefaultAddress = profileViewModel::setDefaultAddress,
                onDeleteAddress = profileViewModel::deleteAddress,
                onPincodeInputChange = profileViewModel::onPincodeCheckInputChange,
                onCheckPincode = { profileViewModel.checkPincodeServiceability() },
                onSignOut = onSignOut,
                modifier = Modifier.weight(1f),
                updateUiState = updateUiState,
                onCheckForUpdates = onCheckForUpdates,
                onOpenUpdateDialog = onOpenUpdateDialog,
                onRetryUpdate = onRetryUpdate,
              )
            }
            else -> {
              RoleShellCard(
                title = "${stringResource(R.string.role_customer_title)} • ${tabs[selectedIndex].first}",
                subtitle = "Signed in as ${session.user.fullName} (${session.user.email})",
                badge = "CUSTOMER",
                onSignOut = if (selectedIndex == 3) {
                  { showFallbackSignOutConfirm = true }
                } else {
                  null
                },
                modifier = Modifier.weight(1f),
              )
            }
          }
        }
      }
    }

    // Unified shell-level full-screen overlay host (Builder Add/Edit, Notifications, Checkout)
    AnimatedVisibility(
      visible = isBuilderOverlayOpen,
      enter = if (reducedMotion) fadeIn() else fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 12 },
      exit = if (reducedMotion) fadeOut() else fadeOut(tween(180)),
    ) {
      val currentMenuState = menuState
      if (isEditBuilderOverlayOpen) {
        val editEntry = activeEditingCartItem!!
        val resolvedProduct = currentMenuState?.allProducts?.firstOrNull {
          it.id == editEntry.item.productId
        }
        val catalogLoading = currentMenuState?.isLoading == true || currentMenuState?.isRefreshing == true
        Surface(
          modifier = Modifier
            .fillMaxSize()
            .semantics { isTraversalGroup = true }
            .focusGroup()
            .testTag("customer_shell_overlay_host"),
          color = MaterialTheme.colorScheme.background,
          tonalElevation = 0.dp,
        ) {
          Box(modifier = Modifier.fillMaxSize()) {
            PizzaBuilderScreen(
              product = resolvedProduct,
              crusts = currentMenuState?.crusts ?: emptyList(),
              toppings = currentMenuState?.toppings ?: emptyList(),
              mode = PizzaBuilderMode.Edit(editEntry),
              isCatalogLoading = catalogLoading,
              isAcceptingOrders = currentMenuState?.isAcceptingOrders ?: true,
              isFromOfflineCache = currentMenuState?.isFromOfflineCache ?: false,
              onDismiss = {
                editingCartItem = null
                savedEditingCartItemId = null
              },
              onConfirmAdd = { variant, crust, selectedToppings, qty, onResult ->
                val targetProduct = resolvedProduct
                val activeCartVm = cartViewModel
                if (targetProduct != null && activeCartVm != null) {
                  activeCartVm.updateEditedPizza(
                    existingItemId = editEntry.item.id,
                    product = targetProduct,
                    variant = variant,
                    crust = crust,
                    toppings = selectedToppings,
                    quantity = qty,
                    onResult = onResult,
                    onEditCommitted = { editResult ->
                      editResult.resultingItemId?.let { targetId ->
                        lastEditedCartItemId = targetId
                      }
                    },
                  )
                } else {
                  onResult(false, "Unable to update this item right now.")
                }
              },
              modifier = Modifier.fillMaxSize(),
            )
            SnackbarHost(
              hostState = overlaySnackbarHostState,
              modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 132.dp),
            )
          }
        }
      } else {
        val builderProduct = activeBuilderProduct
        if (builderProduct != null && currentMenuState != null && catalogViewModel != null) {
          Surface(
            modifier = Modifier
              .fillMaxSize()
              .semantics { isTraversalGroup = true }
              .focusGroup()
              .testTag("customer_shell_overlay_host"),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
          ) {
            Box(modifier = Modifier.fillMaxSize()) {
              PizzaBuilderScreen(
                product = builderProduct,
                crusts = currentMenuState.crusts,
                toppings = currentMenuState.toppings,
                mode = PizzaBuilderMode.Add,
                isCatalogLoading = false,
                isAcceptingOrders = currentMenuState.isAcceptingOrders,
                isFromOfflineCache = currentMenuState.isFromOfflineCache,
                onDismiss = catalogViewModel::dismissCustomizer,
                onConfirmAdd = { variant, crust, selectedToppings, qty, onResult ->
                  val vm = cartViewModel
                  if (vm != null) {
                    vm.addCustomizedPizza(
                      product = builderProduct,
                      variant = variant,
                      crust = crust,
                      toppings = selectedToppings,
                      quantity = qty,
                      onResult = onResult,
                    )
                  } else {
                    onResult(false, "Cart is unavailable right now.")
                  }
                },
                modifier = Modifier.fillMaxSize(),
              )
              SnackbarHost(
                hostState = overlaySnackbarHostState,
                modifier = Modifier
                  .align(Alignment.BottomCenter)
                  .windowInsetsPadding(WindowInsets.navigationBars)
                  .padding(bottom = 132.dp),
              )
            }
          }
        }
      }
    }

    if (isNotificationsOverlayOpen && notificationsViewModel != null) {
      val notifState by notificationsViewModel.uiState.collectAsStateWithLifecycle()
      Surface(
        modifier = Modifier
          .fillMaxSize()
          .semantics { isTraversalGroup = true }
          .focusGroup()
          .testTag("customer_shell_overlay_host"),
        color = MaterialTheme.colorScheme.background,
      ) {
        Box(modifier = Modifier.fillMaxSize()) {
          NotificationsScreen(
            uiState = notifState,
            onRefresh = notificationsViewModel::refreshAll,
            onSelectUnreadOnly = notificationsViewModel::setUnreadOnlyFilter,
            onMarkRead = notificationsViewModel::markAsRead,
            onMarkAllRead = notificationsViewModel::markAllAsRead,
            onToggleOrderUpdates = { enabled -> notificationsViewModel.updatePreferences(orderUpdates = enabled) },
            onTogglePromoAlerts = { enabled -> notificationsViewModel.updatePreferences(promoAlerts = enabled) },
            onNotificationPermissionResult = notificationsViewModel::onPermissionResult,
            onBackClick = { isNotificationsOpen = false },
            onClearActionMessage = notificationsViewModel::clearActionMessage,
            modifier = Modifier.fillMaxSize(),
          )
          SnackbarHost(
            hostState = overlaySnackbarHostState,
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .windowInsetsPadding(WindowInsets.navigationBars)
              .padding(bottom = 12.dp),
          )
        }
      }
    }

    if (isCheckoutOverlayOpen && checkoutViewModel != null) {
      val checkoutStateLocal by checkoutViewModel.uiState.collectAsStateWithLifecycle()
      val quoteStateLocal = quoteState
      val selectedAddress = checkoutStateLocal.addresses.firstOrNull { it.id == checkoutStateLocal.selectedAddressId }

      LaunchedEffect(checkoutStateLocal.selectedAddressId, selectedAddress?.pincode) {
        val pin = selectedAddress?.pincode ?: return@LaunchedEffect
        checkoutQuoteViewModel?.syncCheckoutAddressPincode(pin)
      }

      LaunchedEffect(Unit) {
        checkoutViewModel.loadAddresses()
      }

      Surface(
        modifier = Modifier
          .fillMaxSize()
          .semantics { isTraversalGroup = true }
          .focusGroup()
          .testTag("customer_shell_overlay_host"),
        color = MaterialTheme.colorScheme.background,
      ) {
        Box(modifier = Modifier.fillMaxSize()) {
          CheckoutScreen(
            uiState = checkoutStateLocal,
            quoteState = quoteStateLocal,
            cartItems = cartState?.items ?: emptyList(),
            cartSubtotalPaise = cartState?.subtotalPaise ?: 0L,
            onFulfillmentTypeChange = { type ->
              checkoutQuoteViewModel?.onFulfillmentTypeChange(type)
              checkoutViewModel.onFulfillmentChanged(type)
            },
            onSelectAddress = checkoutViewModel::selectAddress,
            onOpenAddAddress = checkoutViewModel::openAddAddressDialog,
            onDismissAddAddress = checkoutViewModel::dismissAddAddressDialog,
            onSaveAddress = { label, line1, pincode, city ->
              checkoutViewModel.saveNewAddress(label, line1, pincode, city)
            },
            onSelectPaymentMethod = checkoutViewModel::selectPaymentMethod,
            onPlaceOrder = {
              checkoutViewModel.placeOrder(
                cartItems = cartState?.items ?: emptyList(),
                fulfillmentType = quoteStateLocal?.fulfillmentType ?: FulfillmentType.DELIVERY,
                couponCode = quoteStateLocal?.appliedCouponCode,
                onCartCleared = {
                  cartViewModel?.clearCart()
                  checkoutQuoteViewModel?.onCartChanged(emptyList(), 0L)
                  checkoutQuoteViewModel?.removeCoupon()
                },
                quoteState = quoteStateLocal,
                onQuoteRefreshNeeded = { checkoutQuoteViewModel?.forceRefreshQuote() },
              )
            },
            onBackToCart = { isCheckoutOpen = false },
            onCheckAddressZone = checkoutViewModel::checkSelectedAddressServiceability,
            onScheduleTypeChange = { scheduleType ->
              checkoutViewModel.onScheduleTypeChange(
                scheduleType,
                quoteStateLocal?.fulfillmentType ?: FulfillmentType.DELIVERY,
              )
            },
            onSlotDateChange = { date ->
              checkoutViewModel.onSlotDateChange(
                date,
                quoteStateLocal?.fulfillmentType ?: FulfillmentType.DELIVERY,
              )
            },
            onSelectSlot = checkoutViewModel::selectSlot,
            onRefreshSlots = {
              checkoutViewModel.loadAvailableSlots(
                quoteStateLocal?.fulfillmentType ?: FulfillmentType.DELIVERY,
              )
            },
            onSaveAddressWithDetails = { label, line1, line2, landmark, pincode, city, isDefault ->
              checkoutViewModel.saveNewAddress(
                label = label,
                line1 = line1,
                pincode = pincode,
                city = city,
                line2 = line2,
                landmark = landmark,
                isDefault = isDefault,
              )
            },
            onRetryQuote = { checkoutQuoteViewModel?.refreshQuote() },
          )
          SnackbarHost(
            hostState = overlaySnackbarHostState,
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .windowInsetsPadding(WindowInsets.navigationBars)
              .padding(bottom = 12.dp),
          )
        }
      }
    }

    if (showFallbackSignOutConfirm) {
      PizzoraConfirmationDialog(
        title = stringResource(R.string.sign_out_dialog_title),
        body = stringResource(R.string.sign_out_dialog_body),
        confirmLabel = stringResource(R.string.action_sign_out),
        onConfirm = {
          showFallbackSignOutConfirm = false
          onSignOut()
        },
        onDismiss = { showFallbackSignOutConfirm = false },
        isDestructive = true,
        confirmTestTag = "confirm_sign_out_button",
        dismissTestTag = "cancel_sign_out_button",
      )
    }
  }
}

@Composable
private fun CustomerTabIcon(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  badgeCount: Int,
) {
  val reducedMotion = LocalReducedMotion.current
  val badgeScale = remember { Animatable(1f) }
  var previousBadgeCount by remember { mutableIntStateOf(badgeCount) }

  LaunchedEffect(badgeCount, reducedMotion) {
    if (badgeCount > previousBadgeCount && badgeCount > 0 && !reducedMotion) {
      badgeScale.snapTo(1.22f)
      badgeScale.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = 180))
    } else {
      badgeScale.snapTo(1f)
    }
    previousBadgeCount = badgeCount
  }

  if (badgeCount > 0) {
    BadgedBox(
      badge = {
        Badge(
          modifier = Modifier.graphicsLayer(
            scaleX = badgeScale.value,
            scaleY = badgeScale.value,
          ),
        ) {
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
  kdsViewModel: KdsViewModel? = null,
  isMuted: Boolean,
  onToggleMute: () -> Unit,
  onSignOut: () -> Unit,
  updateUiState: UpdateUiState? = null,
  onCheckForUpdates: () -> Unit = {},
  onOpenUpdateDialog: () -> Unit = {},
  onRetryUpdate: () -> Unit = {},
) {
  if (kdsViewModel != null) {
    val kdsState by kdsViewModel.uiState.collectAsStateWithLifecycle()
    DisposableEffect(session.user.id, kdsViewModel) {
      kdsViewModel.startBoard(session.user.id)
      onDispose {
        kdsViewModel.stopBoard()
      }
    }
    KdsScreen(
      uiState = kdsState,
      operatorName = session.user.fullName,
      onSelectFilter = kdsViewModel::setFilterTab,
      onToggleIncludeCompleted = kdsViewModel::toggleIncludeCompleted,
      onToggleMute = kdsViewModel::toggleMute,
      onRefresh = { kdsViewModel.refreshTickets(silent = false) },
      onAdvanceStage = kdsViewModel::advanceTicketStage,
      onTogglePriority = kdsViewModel::togglePriority,
      onSignOut = onSignOut,
      modifier = Modifier.testTag("kds_shell_root"),
      updateUiState = updateUiState,
      onCheckForUpdates = onCheckForUpdates,
      onOpenUpdateDialog = onOpenUpdateDialog,
      onRetryUpdate = onRetryUpdate,
    )
    return
  }

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
  kdsViewModel: KdsViewModel? = null,
  ownerViewModel: OwnerViewModel? = null,
  notificationsViewModel: NotificationsViewModel? = null,
  openNotificationsRequested: Boolean = false,
  onNotificationsRequestHandled: () -> Unit = {},
  onSignOut: () -> Unit,
  updateUiState: UpdateUiState? = null,
  onCheckForUpdates: () -> Unit = {},
  onOpenUpdateDialog: () -> Unit = {},
  onRetryUpdate: () -> Unit = {},
) {
  val context = LocalContext.current
  val tabs = listOf(
    "Dashboard" to Icons.Filled.Dashboard,
    "Catalog" to Icons.Filled.RestaurantMenu,
    "Operations" to Icons.Filled.Inventory2,
    "Reports" to Icons.Filled.Assessment,
    "More" to Icons.Filled.MoreHoriz,
  )
  var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
  var isKdsViewOpen by rememberSaveable { mutableStateOf(false) }
  var isNotificationsOpen by rememberSaveable { mutableStateOf(false) }
  val snackbarHostState = remember { SnackbarHostState() }
  val unreadNotificationsCount = if (notificationsViewModel != null) {
    notificationsViewModel.uiState.collectAsStateWithLifecycle().value.unreadCount
  } else {
    0
  }

  LaunchedEffect(openNotificationsRequested) {
    if (openNotificationsRequested) {
      isNotificationsOpen = true
      onNotificationsRequestHandled()
    }
  }

  LaunchedEffect(session.user.id, notificationsViewModel) {
    OrderSyncScheduler.schedulePeriodicSync(context)
    notificationsViewModel?.startSessionSync(session.user.id)
  }

  LaunchedEffect(ownerViewModel) {
    ownerViewModel?.events?.collect { message ->
      snackbarHostState.showSnackbar(message)
    }
  }

  LaunchedEffect(notificationsViewModel) {
    notificationsViewModel?.events?.collect { message ->
      snackbarHostState.showSnackbar(message)
    }
  }

  LaunchedEffect(selectedIndex, ownerViewModel) {
    val vm = ownerViewModel ?: return@LaunchedEffect
    when (selectedIndex) {
      0 -> vm.loadDashboard()
      1 -> vm.loadCatalogData()
      2 -> vm.loadOperationsData()
      3 -> vm.loadReportsAndAudit()
      4 -> vm.loadStaffAndSettings()
    }
  }

  if (isNotificationsOpen && notificationsViewModel != null) {
    val notifState by notificationsViewModel.uiState.collectAsStateWithLifecycle()
    NotificationsScreen(
      uiState = notifState,
      onRefresh = notificationsViewModel::refreshAll,
      onSelectUnreadOnly = notificationsViewModel::setUnreadOnlyFilter,
      onMarkRead = notificationsViewModel::markAsRead,
      onMarkAllRead = notificationsViewModel::markAllAsRead,
      onToggleOrderUpdates = { enabled -> notificationsViewModel.updatePreferences(orderUpdates = enabled) },
      onTogglePromoAlerts = { enabled -> notificationsViewModel.updatePreferences(promoAlerts = enabled) },
      onToggleKdsSound = { enabled -> notificationsViewModel.updatePreferences(kdsSoundEnabled = enabled) },
      onNotificationPermissionResult = notificationsViewModel::onPermissionResult,
      onBackClick = { isNotificationsOpen = false },
      onClearActionMessage = notificationsViewModel::clearActionMessage,
      modifier = Modifier.fillMaxSize(),
    )
    return
  }

  if (isKdsViewOpen && kdsViewModel != null) {
    androidx.activity.compose.BackHandler {
      isKdsViewOpen = false
    }
    val kdsState by kdsViewModel.uiState.collectAsStateWithLifecycle()
    DisposableEffect(session.user.id, kdsViewModel) {
      kdsViewModel.startBoard(session.user.id)
      onDispose {
        kdsViewModel.stopBoard()
      }
    }
    KdsScreen(
      uiState = kdsState,
      operatorName = session.user.fullName,
      onSelectFilter = kdsViewModel::setFilterTab,
      onToggleIncludeCompleted = kdsViewModel::toggleIncludeCompleted,
      onToggleMute = kdsViewModel::toggleMute,
      onRefresh = { kdsViewModel.refreshTickets(silent = false) },
      onAdvanceStage = kdsViewModel::advanceTicketStage,
      onTogglePriority = kdsViewModel::togglePriority,
      onBackClick = { isKdsViewOpen = false },
      modifier = Modifier.testTag("owner_kds_overlay"),
    )
    return
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag("owner_shell_root")) {
    val useRail = maxWidth >= 600.dp
    Scaffold(
      contentWindowInsets = WindowInsets.safeDrawing,
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      topBar = {
        TopAppBar(
          title = { Text(stringResource(R.string.role_owner_title), style = MaterialTheme.typography.headlineSmall) },
          actions = {
            if (notificationsViewModel != null) {
              IconButton(
                onClick = { isNotificationsOpen = true },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_notifications_button"),
              ) {
                NotificationBellIcon(unreadCount = unreadNotificationsCount)
              }
            }
            if (kdsViewModel != null) {
              IconButton(
                onClick = { isKdsViewOpen = true },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_open_kds_button"),
              ) {
                Icon(Icons.Filled.Kitchen, contentDescription = stringResource(R.string.kds_open_from_owner))
              }
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
      bottomBar = {
        if (!useRail) {
          NavigationBar(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            tabs.forEachIndexed { idx, (label, icon) ->
              NavigationBarItem(
                selected = selectedIndex == idx,
                onClick = { selectedIndex = idx },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label) },
                modifier = Modifier.testTag("owner_tab_${label.lowercase()}"),
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
                modifier = Modifier.testTag("owner_tab_${label.lowercase()}"),
              )
            }
          }
        }
        if (ownerViewModel == null) {
          RoleShellCard(
            title = "${stringResource(R.string.role_owner_title)} • ${tabs[selectedIndex].first}",
            subtitle = "Manager: ${session.user.fullName} (${session.user.email})",
            badge = "OWNER",
            modifier = Modifier.weight(1f),
          )
        } else {
          val ownerState by ownerViewModel.uiState.collectAsStateWithLifecycle()
          when (selectedIndex) {
            0 -> OwnerDashboardScreen(
              uiState = ownerState,
              onRefresh = ownerViewModel::loadDashboard,
              onToggleStoreOpen = ownerViewModel::toggleStoreOpen,
              onQuickRestock = ownerViewModel::adjustInventory,
              onOpenKds = if (kdsViewModel != null) {
                { isKdsViewOpen = true }
              } else {
                null
              },
              onGoToOperations = { selectedIndex = 2 },
              modifier = Modifier.weight(1f),
            )
            1 -> OwnerCatalogScreen(
              uiState = ownerState,
              onSelectSection = ownerViewModel::setCatalogSection,
              onRefresh = ownerViewModel::loadCatalogData,
              onCreateCategory = ownerViewModel::createCategory,
              onToggleCategoryActive = ownerViewModel::toggleCategoryActive,
              onCreateProduct = ownerViewModel::createProduct,
              onToggleProductActive = ownerViewModel::toggleProductActive,
              onUploadProductPhoto = ownerViewModel::uploadProductPhoto,
              onDeleteProductPhoto = ownerViewModel::deleteProductPhoto,
              onSaveRecipe = ownerViewModel::saveRecipe,
              modifier = Modifier.weight(1f),
            )
            2 -> OwnerOperationsScreen(
              uiState = ownerState,
              onSelectSection = ownerViewModel::setOperationsSection,
              onRefresh = ownerViewModel::loadOperationsData,
              onCreateIngredient = ownerViewModel::createIngredient,
              onAdjustInventory = ownerViewModel::adjustInventory,
              onCreateCoupon = ownerViewModel::createCoupon,
              onToggleCouponActive = ownerViewModel::toggleCouponActive,
              onSelectOrder = ownerViewModel::selectOrder,
              onDismissOrderDetail = ownerViewModel::dismissOrderDetail,
              onTransitionOrderStatus = ownerViewModel::transitionOrderStatus,
              onRefundOrder = ownerViewModel::refundOrder,
              modifier = Modifier.weight(1f),
              onRefreshZonesAndSlots = { ownerViewModel.loadZonesAndSlots() },
              onSlotFilterChange = { type, date -> ownerViewModel.loadZonesAndSlots(type, date) },
              onCreateDeliveryZone = ownerViewModel::createDeliveryZone,
              onUpdateDeliveryZone = ownerViewModel::updateDeliveryZone,
              onToggleZoneActive = ownerViewModel::toggleZoneActive,
              onDeleteDeliveryZone = ownerViewModel::deleteDeliveryZone,
              onCreateScheduledSlot = ownerViewModel::createScheduledSlot,
              onToggleSlotActive = ownerViewModel::toggleSlotActive,
              onUpdateSlotCapacity = ownerViewModel::updateSlotCapacity,
              onDeleteScheduledSlot = ownerViewModel::deleteScheduledSlot,
            )
            3 -> OwnerReportsAndAuditScreen(
              uiState = ownerState,
              onSelectSubTab = ownerViewModel::selectReportsSubTab,
              onSelectDatePreset = ownerViewModel::selectReportDatePreset,
              onRefresh = ownerViewModel::loadReportsAndAudit,
              onSelectAuditEntityFilter = { filter -> ownerViewModel.loadAuditLogs(entityType = filter, reset = true) },
              onLoadMoreAuditLogs = ownerViewModel::loadMoreAuditLogs,
              modifier = Modifier.weight(1f),
            )
            else -> OwnerSettingsAndStaffScreen(
              uiState = ownerState,
              onRefresh = ownerViewModel::loadStaffAndSettings,
              onUpdateSettings = ownerViewModel::updateSettings,
              onCreateStaff = ownerViewModel::createStaff,
              onToggleStaffActive = ownerViewModel::toggleStaffActive,
              modifier = Modifier.weight(1f),
              updateUiState = updateUiState,
              onCheckForUpdates = onCheckForUpdates,
              onOpenUpdateDialog = onOpenUpdateDialog,
              onRetryUpdate = onRetryUpdate,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun NotificationBellIcon(unreadCount: Int) {
  val cd = stringResource(R.string.notifications_cd)
  if (unreadCount > 0) {
    BadgedBox(
      badge = {
        Badge {
          Text(
            text = if (unreadCount > 99) "99+" else unreadCount.toString(),
            modifier = Modifier.testTag("notifications_unread_badge"),
          )
        }
      },
    ) {
      Icon(Icons.Filled.Notifications, contentDescription = cd)
    }
  } else {
    Icon(Icons.Filled.Notifications, contentDescription = cd)
  }
}

@Composable
private fun RoleShellCard(
  title: String,
  subtitle: String,
  badge: String,
  onSignOut: (() -> Unit)? = null,
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
        if (onSignOut != null) {
          Spacer(modifier = Modifier.height(16.dp))
          OutlinedButton(
            onClick = onSignOut,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("profile_sign_out_button"),
          ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text(stringResource(R.string.action_sign_out))
          }
        }
      }
    }
  }
}

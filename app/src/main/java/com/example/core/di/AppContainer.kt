package com.example.core.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.BuildConfig
import com.example.auth.AuthRepository
import com.example.auth.AuthViewModel
import com.example.auth.NetworkAuthRepository
import com.example.cart.CartRepository
import com.example.cart.CartViewModel
import com.example.cart.NetworkCartRepository
import com.example.catalog.CatalogRepository
import com.example.catalog.CustomerCatalogViewModel
import com.example.catalog.NetworkCatalogRepository
import com.example.checkout.CheckoutQuoteViewModel
import com.example.checkout.CheckoutViewModel
import com.example.checkout.NetworkPricingRepository
import com.example.checkout.PricingRepository
import com.example.core.database.PizzoraDatabase
import com.example.core.media.ImageCompressor
import com.example.core.media.ProductImageUploader
import com.example.core.network.PizzoraApiClient
import com.example.core.network.WebSocketEventClient
import com.example.core.security.DefaultSessionScopeCleaner
import com.example.core.security.SessionScopeCleaner
import com.example.core.security.SessionTransitionCoordinator
import com.example.core.security.TokenManager
import com.example.core.security.TokenStorage
import com.example.kds.KdsChimePlayer
import com.example.kds.KdsRepository
import com.example.kds.KdsViewModel
import com.example.kds.NetworkKdsRepository
import com.example.kds.ToneGeneratorKdsChimePlayer
import com.example.notifications.NetworkNotificationRepository
import com.example.notifications.NotificationHelper
import com.example.notifications.NotificationRepository
import com.example.notifications.NotificationsViewModel
import com.example.notifications.OrderSyncScheduler
import com.example.notifications.PizzoraWorkerFactory
import com.example.orders.AndroidRazorpayCheckoutLauncher
import com.example.orders.NetworkOrderRepository
import com.example.orders.OrderRepository
import com.example.orders.OrdersViewModel
import com.example.orders.RazorpayCheckoutLauncher
import com.example.owner.NetworkOwnerRepository
import com.example.owner.OwnerRepository
import com.example.owner.OwnerViewModel
import com.example.profile.NetworkProfileRepository
import com.example.profile.ProfileRepository
import com.example.profile.ProfileViewModel
import com.example.update.AndroidInstalledAppInfoProvider
import com.example.update.AndroidPackageApkInstaller
import com.example.update.ApkDownloader
import com.example.update.ApkInstallController
import com.example.update.ApkIntegrityVerifier
import com.example.update.ApkVerifier
import com.example.update.DataStoreUpdatePreferencesStorage
import com.example.update.GitHubUpdateRepository
import com.example.update.InstalledAppInfoProvider
import com.example.update.OkHttpApkDownloadManager
import com.example.update.UpdatePreferencesStorage
import com.example.update.UpdateRepository
import com.example.update.UpdateUrlPolicy
import com.example.update.UpdateViewModel
import io.ktor.client.engine.HttpClientEngine

class AppContainer(
  context: Context,
  private val httpClientEngineOverride: HttpClientEngine? = null,
  razorpayCheckoutLauncherOverride: RazorpayCheckoutLauncher? = null,
  private val tokenStorageOverride: TokenStorage? = null,
) {
  private val appContext = context.applicationContext

  val database: PizzoraDatabase by lazy {
    PizzoraDatabase.build(appContext)
  }

  val tokenStorage: TokenStorage by lazy {
    tokenStorageOverride ?: TokenManager(appContext)
  }

  val sessionScopeCleaner: SessionScopeCleaner by lazy {
    DefaultSessionScopeCleaner(
      cartDao = database.cartDao(),
      notificationInboxDao = database.notificationInboxDao(),
      realtimeEventStreamProvider = { webSocketEventClient },
      onAuthCacheClear = { apiClient.clearAuthTokenCache() },
      onAdditionalCleanup = { OrderSyncScheduler.cancelSync(appContext) },
    )
  }

  val sessionCoordinator: SessionTransitionCoordinator by lazy {
    SessionTransitionCoordinator(
      tokenStorage = tokenStorage,
      scopeCleanerProvider = { sessionScopeCleaner },
    )
  }

  val apiClient: PizzoraApiClient by lazy {
    if (httpClientEngineOverride != null) {
      PizzoraApiClient(
        baseUrl = BuildConfig.API_BASE_URL,
        tokenStorage = tokenStorage,
        engine = httpClientEngineOverride,
        sessionCoordinatorProvider = { sessionCoordinator },
      )
    } else {
      PizzoraApiClient(
        baseUrl = BuildConfig.API_BASE_URL,
        tokenStorage = tokenStorage,
        sessionCoordinatorProvider = { sessionCoordinator },
      )
    }
  }

  val webSocketEventClient: WebSocketEventClient by lazy {
    WebSocketEventClient(apiClient = apiClient)
  }

  val domainBridge: com.example.core.domain.DomainBridge = com.example.core.domain.RustDomainBridge

  val authRepository: AuthRepository by lazy {
    NetworkAuthRepository(
      apiClient = apiClient,
      tokenStorage = tokenStorage,
      sessionCoordinator = sessionCoordinator,
    )
  }

  val catalogRepository: CatalogRepository by lazy {
    NetworkCatalogRepository(
      apiClient = apiClient,
      catalogCacheDao = database.catalogCacheDao(),
    )
  }

  val cartRepository: CartRepository by lazy {
    NetworkCartRepository(
      apiClient = apiClient,
      cartDao = database.cartDao(),
      tokenStorage = tokenStorage,
      sessionCoordinator = sessionCoordinator,
      domainBridge = domainBridge,
    )
  }

  val pricingRepository: PricingRepository by lazy {
    NetworkPricingRepository(
      apiClient = apiClient,
    )
  }

  val orderRepository: OrderRepository by lazy {
    NetworkOrderRepository(
      apiClient = apiClient,
    )
  }

  val razorpayCheckoutLauncher: RazorpayCheckoutLauncher by lazy {
    razorpayCheckoutLauncherOverride ?: AndroidRazorpayCheckoutLauncher()
  }

  val kdsRepository: KdsRepository by lazy {
    NetworkKdsRepository(
      apiClient = apiClient,
    )
  }

  val kdsChimePlayer: KdsChimePlayer by lazy {
    ToneGeneratorKdsChimePlayer()
  }

  val imageCompressor: ImageCompressor by lazy {
    ImageCompressor()
  }

  val productImageUploader: ProductImageUploader by lazy {
    ProductImageUploader(
      apiClient = apiClient,
      imageCompressor = imageCompressor,
      domainBridge = domainBridge,
    )
  }

  val ownerRepository: OwnerRepository by lazy {
    NetworkOwnerRepository(
      apiClient = apiClient,
      imageUploader = productImageUploader,
    )
  }

  val profileRepository: ProfileRepository by lazy {
    NetworkProfileRepository(apiClient = apiClient)
  }

  val notificationHelper: NotificationHelper by lazy {
    NotificationHelper(appContext)
  }

  val notificationRepository: NotificationRepository by lazy {
    NetworkNotificationRepository(
      apiClient = apiClient,
      notificationInboxDao = database.notificationInboxDao(),
      tokenStorage = tokenStorage,
      sessionCoordinator = sessionCoordinator,
    )
  }

  val workerFactory: PizzoraWorkerFactory by lazy {
    PizzoraWorkerFactory(
      notificationRepositoryProvider = { notificationRepository },
      tokenStorageProvider = { tokenStorage },
      notificationPosterProvider = { notificationHelper },
    )
  }

  val installedAppInfoProvider: InstalledAppInfoProvider by lazy {
    AndroidInstalledAppInfoProvider(appContext)
  }

  val updatePreferencesStorage: UpdatePreferencesStorage by lazy {
    DataStoreUpdatePreferencesStorage(appContext)
  }

  val updateUrlPolicy: UpdateUrlPolicy by lazy {
    UpdateUrlPolicy()
  }

  val updateRepository: UpdateRepository by lazy {
    GitHubUpdateRepository(
      preferencesStorage = updatePreferencesStorage,
      urlPolicy = updateUrlPolicy,
    )
  }

  val apkDownloader: ApkDownloader by lazy {
    OkHttpApkDownloadManager(
      context = appContext,
      urlPolicy = updateUrlPolicy,
    )
  }

  val apkVerifier: ApkVerifier by lazy {
    ApkIntegrityVerifier(appContext)
  }

  val apkInstaller: ApkInstallController by lazy {
    AndroidPackageApkInstaller(appContext)
  }

  fun authViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(AuthViewModel::class.java)) {
        "Unknown ViewModel class: ${modelClass.name}"
      }
      return AuthViewModel(authRepository) as T
    }
  }

  fun customerCatalogViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(CustomerCatalogViewModel::class.java)) {
        "Unknown ViewModel class: ${modelClass.name}"
      }
      return CustomerCatalogViewModel(catalogRepository) as T
    }
  }

  fun cartViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(CartViewModel::class.java)) {
        "Unknown ViewModel class: ${modelClass.name}"
      }
      return CartViewModel(cartRepository) as T
    }
  }

  fun checkoutQuoteViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(CheckoutQuoteViewModel::class.java)) {
        "Unknown ViewModel class: ${modelClass.name}"
      }
      return CheckoutQuoteViewModel(pricingRepository) as T
    }
  }

  fun checkoutViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(CheckoutViewModel::class.java)) {
        "Unknown ViewModel class: ${modelClass.name}"
      }
      return CheckoutViewModel(orderRepository) as T
    }
  }

  fun ordersViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(OrdersViewModel::class.java)) {
        "Unknown ViewModel class: ${modelClass.name}"
      }
      return OrdersViewModel(
        orderRepository = orderRepository,
        realtimeEventStream = webSocketEventClient,
        domainBridge = domainBridge,
      ) as T
    }
  }

  fun kdsViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(KdsViewModel::class.java)) {
        "Unknown ViewModel class: ${modelClass.name}"
      }
      return KdsViewModel(
        kdsRepository = kdsRepository,
        tokenStorage = tokenStorage,
        chimePlayer = kdsChimePlayer,
        realtimeEventStream = webSocketEventClient,
        domainBridge = domainBridge,
      ) as T
    }
  }

  fun ownerViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(OwnerViewModel::class.java)) {
        "Unknown ViewModel class: ${modelClass.name}"
      }
      return OwnerViewModel(repository = ownerRepository) as T
    }
  }

  fun profileViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
        "Unknown ViewModel class: ${modelClass.name}"
      }
      return ProfileViewModel(profileRepository = profileRepository) as T
    }
  }

  fun notificationsViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(NotificationsViewModel::class.java)) {
        "Unknown ViewModel class: ${modelClass.name}"
      }
      return NotificationsViewModel(
        repository = notificationRepository,
        tokenStorage = tokenStorage,
        realtimeEventStream = webSocketEventClient,
        notificationPoster = notificationHelper,
      ) as T
    }
  }

  fun updateViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(UpdateViewModel::class.java)) {
        "Unknown ViewModel class: ${modelClass.name}"
      }
      return UpdateViewModel(
        updateRepository = updateRepository,
        apkDownloader = apkDownloader,
        apkVerifier = apkVerifier,
        apkInstaller = apkInstaller,
        preferencesStorage = updatePreferencesStorage,
        installedAppInfoProvider = installedAppInfoProvider,
      ) as T
    }
  }
}

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
import com.example.core.security.TokenManager
import com.example.core.security.TokenStorage
import com.example.orders.NetworkOrderRepository
import com.example.orders.OrderRepository
import com.example.orders.OrdersViewModel

class AppContainer(context: Context) {
  private val appContext = context.applicationContext

  val database: PizzoraDatabase by lazy {
    PizzoraDatabase.build(appContext)
  }

  val tokenStorage: TokenStorage by lazy {
    TokenManager(appContext)
  }

  val apiClient: PizzoraApiClient by lazy {
    PizzoraApiClient(
      baseUrl = BuildConfig.API_BASE_URL,
      tokenStorage = tokenStorage,
    )
  }

  val authRepository: AuthRepository by lazy {
    NetworkAuthRepository(
      apiClient = apiClient,
      tokenStorage = tokenStorage,
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

  val imageCompressor: ImageCompressor by lazy {
    ImageCompressor()
  }

  val productImageUploader: ProductImageUploader by lazy {
    ProductImageUploader(
      apiClient = apiClient,
      imageCompressor = imageCompressor,
    )
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
      return OrdersViewModel(orderRepository) as T
    }
  }
}

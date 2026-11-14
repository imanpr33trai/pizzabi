package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.auth.AuthViewModel
import com.example.cart.CartViewModel
import com.example.catalog.CustomerCatalogViewModel
import com.example.checkout.CheckoutQuoteViewModel
import com.example.checkout.CheckoutViewModel
import com.example.kds.KdsViewModel
import com.example.navigation.PizzoraApp
import com.example.orders.OrdersViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val container = (application as PizzoraApplication).appContainer

    setContent {
      val authViewModel: AuthViewModel = viewModel(factory = container.authViewModelFactory())
      val catalogViewModel: CustomerCatalogViewModel = viewModel(factory = container.customerCatalogViewModelFactory())
      val cartViewModel: CartViewModel = viewModel(factory = container.cartViewModelFactory())
      val checkoutQuoteViewModel: CheckoutQuoteViewModel = viewModel(factory = container.checkoutQuoteViewModelFactory())
      val checkoutViewModel: CheckoutViewModel = viewModel(factory = container.checkoutViewModelFactory())
      val ordersViewModel: OrdersViewModel = viewModel(factory = container.ordersViewModelFactory())
      val kdsViewModel: KdsViewModel = viewModel(factory = container.kdsViewModelFactory())
      PizzoraApp(
        authViewModel = authViewModel,
        catalogViewModel = catalogViewModel,
        cartViewModel = cartViewModel,
        checkoutQuoteViewModel = checkoutQuoteViewModel,
        checkoutViewModel = checkoutViewModel,
        ordersViewModel = ordersViewModel,
        kdsViewModel = kdsViewModel,
      )
    }
  }
}

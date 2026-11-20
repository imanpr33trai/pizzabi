package com.example.catalog

import androidx.compose.runtime.Composable
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.ToppingDto

/**
 * Backward-compatible wrapper delegating to [PizzaBuilderScreen].
 */
@Composable
fun PizzaCustomizerSheet(
  product: ProductDto,
  crusts: List<CrustOptionDto>,
  toppings: List<ToppingDto>,
  onDismiss: () -> Unit,
  onConfirmAdd: (ProductVariantDto, CrustOptionDto, List<ToppingDto>, Int) -> Unit,
  isAcceptingOrders: Boolean = true,
  isFromOfflineCache: Boolean = false,
) {
  PizzaBuilderScreen(
    product = product,
    crusts = crusts,
    toppings = toppings,
    isAcceptingOrders = isAcceptingOrders,
    isFromOfflineCache = isFromOfflineCache,
    onDismiss = onDismiss,
    onConfirmAdd = { variant, crust, selectedToppings, qty, onResult ->
      onConfirmAdd(variant, crust, selectedToppings, qty)
      onResult(true, null)
    },
  )
}

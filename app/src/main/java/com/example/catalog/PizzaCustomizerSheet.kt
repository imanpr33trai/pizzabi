package com.example.catalog

import androidx.compose.runtime.Composable
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.ToppingDto

/**
 * Backward-compatible wrapper delegating to [PizzaBuilderScreen]. Legacy callers keep passing
 * [onConfirmAdd]; callers that need Phase 8 modifier IDs pass [onConfirmAddWithModifiers] and the
 * full builder payload is forwarded untouched.
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
  onConfirmAddWithModifiers: ((
    input: CartItemInputDto,
    onResult: (success: Boolean, errorMessage: String?) -> Unit,
  ) -> Unit)? = null,
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
    onConfirmAddWithModifiers = onConfirmAddWithModifiers,
  )
}

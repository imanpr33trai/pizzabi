package com.example.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ToppingDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CustomerMenuUiState(
  val isLoading: Boolean = true,
  val isRefreshing: Boolean = false,
  val isFromOfflineCache: Boolean = false,
  val storeName: String = "PIZZORA Artisanal Kitchen",
  val isAcceptingOrders: Boolean = true,
  val gstBps: Int = 500,
  val categories: List<CategoryDto> = emptyList(),
  val allProducts: List<ProductDto> = emptyList(),
  val crusts: List<CrustOptionDto> = emptyList(),
  val toppings: List<ToppingDto> = emptyList(),
  val selectedCategoryId: String? = null,
  val searchQuery: String = "",
  val vegOnly: Boolean = false,
  val errorMessage: String? = null,
  val customizingProduct: ProductDto? = null,
) {
  val filteredProducts: List<ProductDto>
    get() {
      val query = searchQuery.trim().lowercase()
      return allProducts.filter { prod ->
        val matchesCategory = selectedCategoryId == null || prod.categoryId == selectedCategoryId
        val matchesVeg = !vegOnly || prod.isVeg
        val matchesQuery = query.isEmpty() ||
          prod.name.lowercase().contains(query) ||
          prod.description.lowercase().contains(query) ||
          prod.categoryName.lowercase().contains(query)
        matchesCategory && matchesVeg && matchesQuery && prod.variants.isNotEmpty()
      }
    }
}

class CustomerCatalogViewModel(
  private val catalogRepository: CatalogRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(CustomerMenuUiState())
  val uiState: StateFlow<CustomerMenuUiState> = _uiState.asStateFlow()

  init {
    loadMenu(initialLoad = true)
  }

  fun loadMenu(initialLoad: Boolean = false) {
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isLoading = initialLoad && it.allProducts.isEmpty(),
          isRefreshing = !initialLoad,
          errorMessage = null,
        )
      }
      try {
        val snapshot = catalogRepository.refreshPublicMenu()
        val menu = snapshot.menu
        val derivedCategories = menu?.categories ?: snapshot.cachedProducts
          .map { it.categoryId to it.categoryName }
          .distinctBy { it.first }
          .mapIndexed { idx, (id, name) ->
            CategoryDto(id = id, name = name, sortOrder = idx, isActive = true, createdAt = "")
          }
        _uiState.update {
          it.copy(
            isLoading = false,
            isRefreshing = false,
            isFromOfflineCache = snapshot.isFromOfflineCache,
            storeName = menu?.storeName ?: it.storeName,
            isAcceptingOrders = menu?.isAcceptingOrders ?: it.isAcceptingOrders,
            gstBps = menu?.gstBps ?: it.gstBps,
            categories = derivedCategories,
            allProducts = menu?.products ?: snapshot.cachedProducts,
            crusts = menu?.crusts ?: it.crusts,
            toppings = menu?.toppings ?: it.toppings,
            errorMessage = null,
          )
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        _uiState.update {
          it.copy(
            isLoading = false,
            isRefreshing = false,
            errorMessage = t.message ?: "Unable to load the menu. Check your connection.",
          )
        }
      }
    }
  }

  fun onSearchQueryChange(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
  }

  fun onCategorySelected(categoryId: String?) {
    _uiState.update { it.copy(selectedCategoryId = categoryId) }
  }

  fun onVegOnlyToggle() {
    _uiState.update { it.copy(vegOnly = !it.vegOnly) }
  }

  fun clearFilters() {
    _uiState.update { it.copy(selectedCategoryId = null, searchQuery = "", vegOnly = false) }
  }

  fun openCustomizer(product: ProductDto) {
    _uiState.update { it.copy(customizingProduct = product) }
  }

  fun dismissCustomizer() {
    _uiState.update { it.copy(customizingProduct = null) }
  }
}

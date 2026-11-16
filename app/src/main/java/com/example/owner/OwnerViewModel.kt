package com.example.owner

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pizzora.contracts.AdjustInventoryRequest
import com.pizzora.contracts.AuditLogEntryDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CouponDto
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCouponRequest
import com.pizzora.contracts.CreateDeliveryZoneRequest
import com.pizzora.contracts.CreateIngredientRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateScheduledSlotRequest
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.DeliveryZoneDto
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.InventoryLedgerEntryDto
import com.pizzora.contracts.InventoryLedgerReason
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OperationsReportResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.OwnerDashboardResponse
import com.pizzora.contracts.OwnerRefundRequest
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.PutRecipeRequest
import com.pizzora.contracts.RecipeIngredientLineRequest
import com.pizzora.contracts.RecipeTargetType
import com.pizzora.contracts.RecipesOverviewResponse
import com.pizzora.contracts.RestaurantSettingsDto
import com.pizzora.contracts.SalesReportResponse
import com.pizzora.contracts.ScheduledSlotDto
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.StaffMemberDto
import com.pizzora.contracts.TransitionOrderStatusRequest
import com.pizzora.contracts.UpdateCategoryRequest
import com.pizzora.contracts.UpdateCouponRequest
import com.pizzora.contracts.UpdateDeliveryZoneRequest
import com.pizzora.contracts.UpdateProductRequest
import com.pizzora.contracts.UpdateRestaurantSettingsRequest
import com.pizzora.contracts.UpdateScheduledSlotRequest
import com.pizzora.contracts.UpdateStaffRequest
import com.pizzora.contracts.UserRole
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OwnerOperationsSection(val label: String) {
  INVENTORY("Inventory"),
  ORDERS("Orders & Refunds"),
  COUPONS("Coupons"),
  ZONES_SLOTS("Zones & Slots"),
}

enum class OwnerCatalogSection(val label: String) {
  PRODUCTS("Menu & Photos"),
  CATEGORIES("Categories"),
  RECIPES("Recipes"),
}

enum class OwnerReportsSubTab(val label: String) {
  SALES("Sales & GST"),
  OPERATIONS("Operations"),
  AUDIT_LOGS("Audit Logs"),
}

enum class ReportDatePreset(val label: String, val daysBack: Long) {
  TODAY("Today (IST)", 0L),
  LAST_7_DAYS("Last 7D", 6L),
  LAST_30_DAYS("Last 30D", 29L),
}

data class OwnerUiState(
  val isLoadingDashboard: Boolean = false,
  val isLoadingCatalog: Boolean = false,
  val isLoadingOperations: Boolean = false,
  val isLoadingZonesAndSlots: Boolean = false,
  val isLoadingReports: Boolean = false,
  val isLoadingAuditLogs: Boolean = false,
  val isLoadingMore: Boolean = false,
  val isSubmitting: Boolean = false,
  val uploadingProductId: String? = null,
  val dashboard: OwnerDashboardResponse? = null,
  val settings: RestaurantSettingsDto? = null,
  val categories: List<CategoryDto> = emptyList(),
  val products: List<ProductDto> = emptyList(),
  val recipes: RecipesOverviewResponse = RecipesOverviewResponse(),
  val inventory: List<InventoryItemDto> = emptyList(),
  val inventoryLedger: List<InventoryLedgerEntryDto> = emptyList(),
  val coupons: List<CouponDto> = emptyList(),
  val orders: List<OrderSummaryDto> = emptyList(),
  val deliveryZones: List<DeliveryZoneDto> = emptyList(),
  val scheduledSlots: List<ScheduledSlotDto> = emptyList(),
  val slotFilterType: FulfillmentType? = null,
  val slotFilterDate: String = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString(),
  val selectedOrderDetail: OrderDetailDto? = null,
  val selectedOrderFilter: OrderStatus? = null,
  val staff: List<StaffMemberDto> = emptyList(),
  val catalogSection: OwnerCatalogSection = OwnerCatalogSection.PRODUCTS,
  val operationsSection: OwnerOperationsSection = OwnerOperationsSection.INVENTORY,
  val reportsSubTab: OwnerReportsSubTab = OwnerReportsSubTab.SALES,
  val reportDatePreset: ReportDatePreset = ReportDatePreset.LAST_7_DAYS,
  val salesReport: SalesReportResponse? = null,
  val operationsReport: OperationsReportResponse? = null,
  val auditLogs: List<AuditLogEntryDto> = emptyList(),
  val auditNextCursor: String? = null,
  val auditEntityFilter: String? = null,
  val errorMessage: String? = null,
)

class OwnerViewModel(
  private val repository: OwnerRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(OwnerUiState())
  val uiState: StateFlow<OwnerUiState> = _uiState.asStateFlow()

  private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val events: SharedFlow<String> = _events.asSharedFlow()

  fun clearError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  fun setCatalogSection(section: OwnerCatalogSection) {
    _uiState.update { it.copy(catalogSection = section) }
  }

  fun setOperationsSection(section: OwnerOperationsSection) {
    _uiState.update { it.copy(operationsSection = section) }
    if (section == OwnerOperationsSection.ZONES_SLOTS) {
      loadZonesAndSlots()
    }
  }

  fun loadDashboard() {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingDashboard = true, errorMessage = null) }
      runCatching { repository.fetchDashboard() }
        .onSuccess { dash ->
          _uiState.update {
            it.copy(
              isLoadingDashboard = false,
              dashboard = dash,
              settings = dash.settings,
            )
          }
        }
        .onFailure { t ->
          _uiState.update {
            it.copy(
              isLoadingDashboard = false,
              errorMessage = t.message ?: "Failed to load owner dashboard.",
            )
          }
        }
    }
  }

  fun loadCatalogData() {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingCatalog = true, errorMessage = null) }
      runCatching {
        val categoriesDef = async { repository.fetchCategories() }
        val productsDef = async { repository.fetchProducts() }
        val recipesDef = async { repository.fetchRecipes() }
        val inventoryDef = async { repository.fetchInventory() }
        Triple(categoriesDef.await(), productsDef.await(), recipesDef.await() to inventoryDef.await())
      }.onSuccess { (cats, prods, recipesAndInv) ->
        _uiState.update {
          it.copy(
            isLoadingCatalog = false,
            categories = cats,
            products = prods,
            recipes = recipesAndInv.first,
            inventory = recipesAndInv.second,
          )
        }
      }.onFailure { t ->
        _uiState.update {
          it.copy(
            isLoadingCatalog = false,
            errorMessage = t.message ?: "Failed to load catalog & recipes.",
          )
        }
      }
    }
  }

  fun loadOperationsData(orderStatusFilter: OrderStatus? = _uiState.value.selectedOrderFilter) {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingOperations = true, errorMessage = null, selectedOrderFilter = orderStatusFilter) }
      runCatching {
        val invDef = async { repository.fetchInventory() }
        val ledgerDef = async { repository.fetchInventoryLedger(limit = 30) }
        val ordersDef = async { repository.fetchOrders(orderStatusFilter, 50) }
        val couponsDef = async { repository.fetchCoupons() }
        listOf(invDef.await(), ledgerDef.await(), ordersDef.await(), couponsDef.await())
      }.onSuccess { results ->
        @Suppress("UNCHECKED_CAST")
        _uiState.update {
          it.copy(
            isLoadingOperations = false,
            inventory = results[0] as List<InventoryItemDto>,
            inventoryLedger = results[1] as List<InventoryLedgerEntryDto>,
            orders = results[2] as List<OrderSummaryDto>,
            coupons = results[3] as List<CouponDto>,
          )
        }
      }.onFailure { t ->
        _uiState.update {
          it.copy(
            isLoadingOperations = false,
            errorMessage = t.message ?: "Failed to load operations data.",
          )
        }
      }
    }
  }

  fun loadStaffAndSettings() {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
      runCatching {
        val settingsDef = async { repository.fetchSettings() }
        val staffDef = async { repository.fetchStaff() }
        settingsDef.await() to staffDef.await()
      }.onSuccess { (settings, staff) ->
        _uiState.update {
          it.copy(
            isLoadingMore = false,
            settings = settings,
            staff = staff,
          )
        }
      }.onFailure { t ->
        _uiState.update {
          it.copy(
            isLoadingMore = false,
            errorMessage = t.message ?: "Failed to load staff & store settings.",
          )
        }
      }
    }
  }

  fun toggleStoreOpen(isOpen: Boolean) {
    updateSettings(UpdateRestaurantSettingsRequest(isAcceptingOrders = isOpen))
  }

  fun updateSettings(request: UpdateRestaurantSettingsRequest) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching { repository.updateSettings(request) }
        .onSuccess { updated ->
          _uiState.update { state ->
            state.copy(
              isSubmitting = false,
              settings = updated,
              dashboard = state.dashboard?.copy(settings = updated),
            )
          }
          _events.tryEmit(
            if (updated.isAcceptingOrders) "Store is now OPEN (${updated.gstBps / 100.0}% GST)"
            else "Store is now CLOSED for new orders",
          )
        }
        .onFailure { t ->
          _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Settings update failed.") }
        }
    }
  }

  fun createCategory(name: String, sortOrder: Int) {
    if (name.isBlank()) {
      _uiState.update { it.copy(errorMessage = "Category name is required.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.createCategory(CreateCategoryRequest(name = name.trim(), sortOrder = sortOrder, isActive = true))
      }.onSuccess { created ->
        _uiState.update { it.copy(isSubmitting = false, categories = (it.categories + created).sortedBy { c -> c.sortOrder }) }
        _events.tryEmit("Category '${created.name}' created")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Could not create category.") }
      }
    }
  }

  fun toggleCategoryActive(category: CategoryDto) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.updateCategory(category.id, UpdateCategoryRequest(isActive = !category.isActive))
      }.onSuccess { updated ->
        _uiState.update { state ->
          state.copy(
            isSubmitting = false,
            categories = state.categories.map { if (it.id == updated.id) updated else it },
          )
        }
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Category update failed.") }
      }
    }
  }

  fun createProduct(
    categoryId: String,
    name: String,
    description: String,
    isVeg: Boolean,
    regularPricePaise: Long,
    mediumPricePaise: Long?,
    largePricePaise: Long?,
  ) {
    if (name.isBlank() || categoryId.isBlank() || regularPricePaise <= 0L) {
      _uiState.update { it.copy(errorMessage = "Product name, category, and positive Regular price are required.") }
      return
    }
    val variants = buildList {
      add(CreateVariantRequest(SizeCode.REGULAR, regularPricePaise, true))
      if (mediumPricePaise != null && mediumPricePaise > 0L) {
        add(CreateVariantRequest(SizeCode.MEDIUM, mediumPricePaise, true))
      }
      if (largePricePaise != null && largePricePaise > 0L) {
        add(CreateVariantRequest(SizeCode.LARGE, largePricePaise, true))
      }
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.createProduct(
          CreateProductRequest(
            categoryId = categoryId,
            name = name.trim(),
            description = description.trim(),
            isVeg = isVeg,
            isActive = true,
            isTaxable = true,
            variants = variants,
          ),
        )
      }.onSuccess { created ->
        _uiState.update { it.copy(isSubmitting = false, products = listOf(created) + it.products) }
        _events.tryEmit("Added '${created.name}' to catalog")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Could not create product.") }
      }
    }
  }

  fun toggleProductActive(product: ProductDto) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.updateProduct(product.id, UpdateProductRequest(isActive = !product.isActive))
      }.onSuccess { updated ->
        _uiState.update { state ->
          state.copy(
            isSubmitting = false,
            products = state.products.map { if (it.id == updated.id) updated else it },
          )
        }
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Product update failed.") }
      }
    }
  }

  fun uploadProductPhoto(context: Context, productId: String, uri: Uri) {
    viewModelScope.launch {
      val currentProduct = _uiState.value.products.firstOrNull { it.id == productId }
      val nextSortOrder = (currentProduct?.images?.maxOfOrNull { it.sortOrder } ?: -1) + 1
      _uiState.update { it.copy(uploadingProductId = productId, errorMessage = null) }
      runCatching {
        repository.uploadProductImage(context, productId, uri, sortOrder = nextSortOrder)
      }.onSuccess { uploaded ->
        _uiState.update { state ->
          state.copy(
            uploadingProductId = null,
            products = state.products.map { prod ->
              if (prod.id == productId) prod.copy(images = prod.images + uploaded) else prod
            },
          )
        }
        _events.tryEmit("Product photo uploaded")
      }.onFailure { t ->
        _uiState.update {
          it.copy(
            uploadingProductId = null,
            errorMessage = t.message ?: "Image upload failed.",
          )
        }
      }
    }
  }

  fun deleteProductPhoto(productId: String, imageId: String) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching { repository.deleteProductImage(productId, imageId) }
        .onSuccess {
          _uiState.update { state ->
            state.copy(
              isSubmitting = false,
              products = state.products.map { prod ->
                if (prod.id == productId) prod.copy(images = prod.images.filterNot { img -> img.id == imageId }) else prod
              },
            )
          }
          _events.tryEmit("Product photo removed")
        }
        .onFailure { t ->
          _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Could not delete photo.") }
        }
    }
  }

  fun saveRecipe(
    targetType: RecipeTargetType,
    targetId: String,
    lines: List<RecipeIngredientLineRequest>,
  ) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.putRecipe(
          PutRecipeRequest(
            targetType = targetType,
            targetId = targetId,
            ingredients = lines.filter { it.quantity > 0 },
          ),
        )
      }.onSuccess { updated ->
        _uiState.update { state ->
          val nextRecipes = when (targetType) {
            RecipeTargetType.VARIANT -> state.recipes.copy(
              variants = state.recipes.variants.map { if (it.targetId == updated.targetId) updated else it },
            )
            RecipeTargetType.CRUST -> state.recipes.copy(
              crusts = state.recipes.crusts.map { if (it.targetId == updated.targetId) updated else it },
            )
            RecipeTargetType.TOPPING -> state.recipes.copy(
              toppings = state.recipes.toppings.map { if (it.targetId == updated.targetId) updated else it },
            )
          }
          state.copy(isSubmitting = false, recipes = nextRecipes)
        }
        _events.tryEmit("Recipe updated for ${updated.targetLabel}")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Recipe update failed.") }
      }
    }
  }

  fun createIngredient(
    name: String,
    unit: IngredientUnit,
    lowStockThreshold: Long,
    initialStockQty: Long,
  ) {
    if (name.isBlank() || initialStockQty < 0L || lowStockThreshold < 0L) {
      _uiState.update { it.copy(errorMessage = "Provide ingredient name and non-negative quantities.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.createIngredient(
          CreateIngredientRequest(
            name = name.trim(),
            unit = unit,
            lowStockThreshold = lowStockThreshold,
            initialStockQty = initialStockQty,
          ),
        )
      }.onSuccess { created ->
        val ledger = runCatching { repository.fetchInventoryLedger(limit = 30) }.getOrNull()
        _uiState.update { state ->
          state.copy(
            isSubmitting = false,
            inventory = (state.inventory + created).sortedBy { it.name },
            inventoryLedger = ledger ?: state.inventoryLedger,
          )
        }
        _events.tryEmit("Ingredient '${created.name}' added")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Could not create ingredient.") }
      }
    }
  }

  fun adjustInventory(
    ingredientId: String,
    deltaQty: Long,
    reason: InventoryLedgerReason,
  ) {
    if (deltaQty == 0L) {
      _uiState.update { it.copy(errorMessage = "Adjustment quantity cannot be zero.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      val opId = "owner-${reason.name.lowercase()}-${UUID.randomUUID()}"
      runCatching {
        repository.adjustInventory(
          AdjustInventoryRequest(
            ingredientId = ingredientId,
            operationId = opId,
            deltaQty = deltaQty,
            reason = reason,
          ),
        )
      }.onSuccess { updated ->
        val ledger = runCatching { repository.fetchInventoryLedger(limit = 30) }.getOrNull()
        _uiState.update { state ->
          val nextInv = state.inventory.map { if (it.ingredientId == updated.ingredientId) updated else it }
          val nextDash = state.dashboard?.let { d ->
            val low = nextInv.filter { it.isLowStock }
            d.copy(lowStockCount = low.size, lowStockItems = low)
          }
          state.copy(
            isSubmitting = false,
            inventory = nextInv,
            inventoryLedger = ledger ?: state.inventoryLedger,
            dashboard = nextDash,
          )
        }
        _events.tryEmit("${updated.name} stock updated (${updated.availableQty} available)")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Stock adjustment failed.") }
      }
    }
  }

  fun createCoupon(
    code: String,
    discountType: DiscountType,
    discountValue: Long,
    minOrderPaise: Long,
    maxDiscountPaise: Long?,
    startsAt: String,
    endsAt: String,
  ) {
    if (code.isBlank() || discountValue <= 0L) {
      _uiState.update { it.copy(errorMessage = "Coupon code and positive discount value are required.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.createCoupon(
          CreateCouponRequest(
            code = code.trim().uppercase(),
            discountType = discountType,
            discountValue = discountValue,
            maxDiscountPaise = maxDiscountPaise,
            minOrderPaise = minOrderPaise,
            startsAt = startsAt.trim(),
            endsAt = endsAt.trim(),
          ),
        )
      }.onSuccess { created ->
        _uiState.update { it.copy(isSubmitting = false, coupons = listOf(created) + it.coupons) }
        _events.tryEmit("Coupon ${created.code} created")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Coupon creation failed.") }
      }
    }
  }

  fun toggleCouponActive(coupon: CouponDto) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.updateCoupon(coupon.id, UpdateCouponRequest(isActive = !coupon.isActive))
      }.onSuccess { updated ->
        _uiState.update { state ->
          state.copy(
            isSubmitting = false,
            coupons = state.coupons.map { if (it.id == updated.id) updated else it },
          )
        }
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Coupon update failed.") }
      }
    }
  }

  fun selectOrder(orderId: String) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching { repository.fetchOrderDetail(orderId) }
        .onSuccess { detail ->
          _uiState.update { it.copy(isSubmitting = false, selectedOrderDetail = detail) }
        }
        .onFailure { t ->
          _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Could not load order details.") }
        }
    }
  }

  fun dismissOrderDetail() {
    _uiState.update { it.copy(selectedOrderDetail = null) }
  }

  fun transitionOrderStatus(orderId: String, toStatus: OrderStatus, reason: String) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.transitionOrderStatus(orderId, TransitionOrderStatusRequest(toStatus = toStatus, reason = reason))
      }.onSuccess { updated ->
        _uiState.update { state ->
          state.copy(
            isSubmitting = false,
            selectedOrderDetail = if (state.selectedOrderDetail?.id == updated.id) updated else state.selectedOrderDetail,
            orders = state.orders.map { sum ->
              if (sum.id == updated.id) sum.copy(status = updated.status, version = updated.version) else sum
            },
          )
        }
        _events.tryEmit("Order ${updated.orderNumber} -> ${updated.status.name}")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Status transition failed.") }
      }
    }
  }

  fun refundOrder(orderId: String, amountPaise: Long?, reason: String) {
    if (reason.isBlank()) {
      _uiState.update { it.copy(errorMessage = "Refund reason is required.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      val idempotencyKey = "owner-refund-${UUID.randomUUID()}"
      runCatching {
        repository.refundOrder(
          orderId = orderId,
          idempotencyKey = idempotencyKey,
          request = OwnerRefundRequest(amountPaise = amountPaise, reason = reason.trim()),
        )
      }.onSuccess { refund ->
        val refreshed = runCatching { repository.fetchOrderDetail(orderId) }.getOrNull()
        _uiState.update { state ->
          state.copy(
            isSubmitting = false,
            selectedOrderDetail = refreshed ?: state.selectedOrderDetail,
          )
        }
        _events.tryEmit("Refund queued (${refund.status.name})")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Refund request failed.") }
      }
    }
  }

  fun createStaff(
    email: String,
    password: String,
    fullName: String,
    phone: String,
    role: UserRole,
    employeeCode: String,
  ) {
    if (email.isBlank() || password.length < 8 || fullName.isBlank() || employeeCode.isBlank()) {
      _uiState.update { it.copy(errorMessage = "Fill all staff fields (password >= 8 chars).") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.createStaff(
          CreateStaffRequest(
            email = email.trim(),
            password = password,
            fullName = fullName.trim(),
            phone = phone.trim(),
            role = role,
            employeeCode = employeeCode.trim(),
          ),
        )
      }.onSuccess { created ->
        _uiState.update { it.copy(isSubmitting = false, staff = listOf(created) + it.staff) }
        _events.tryEmit("Staff ${created.fullName} added (${created.role.name})")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Could not create staff.") }
      }
    }
  }

  fun toggleStaffActive(member: StaffMemberDto) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.updateStaff(member.userId, UpdateStaffRequest(isActive = !member.isActive))
      }.onSuccess { updated ->
        _uiState.update { state ->
          state.copy(
            isSubmitting = false,
            staff = state.staff.map { if (it.userId == updated.userId) updated else it },
          )
        }
        _events.tryEmit(
          if (updated.isActive) "${updated.fullName} activated" else "${updated.fullName} deactivated",
        )
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Staff update failed.") }
      }
    }
  }

  fun loadZonesAndSlots(
    type: FulfillmentType? = _uiState.value.slotFilterType,
    date: String = _uiState.value.slotFilterDate,
  ) {
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isLoadingZonesAndSlots = true,
          slotFilterType = type,
          slotFilterDate = date,
          errorMessage = null,
        )
      }
      runCatching {
        val zonesDef = async { repository.fetchDeliveryZones() }
        val slotsDef = async { repository.fetchScheduledSlots(type, date.takeIf { it.isNotBlank() }) }
        zonesDef.await() to slotsDef.await()
      }.onSuccess { (zones, slots) ->
        _uiState.update {
          it.copy(
            isLoadingZonesAndSlots = false,
            deliveryZones = zones,
            scheduledSlots = slots,
          )
        }
      }.onFailure { t ->
        _uiState.update {
          it.copy(
            isLoadingZonesAndSlots = false,
            errorMessage = t.message ?: "Failed to load delivery zones & slots.",
          )
        }
      }
    }
  }

  fun createDeliveryZone(
    pincode: String,
    areaName: String,
    minOrderPaise: Long,
    deliveryFeePaise: Long,
    estimatedMins: Int,
  ) {
    val cleanPin = pincode.trim()
    val cleanArea = areaName.trim()
    if (!cleanPin.matches(Regex("^[1-9][0-9]{5}$")) || cleanArea.isEmpty()) {
      _uiState.update { it.copy(errorMessage = "Enter a valid 6-digit Indian pincode and area name.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.createDeliveryZone(
          CreateDeliveryZoneRequest(
            pincode = cleanPin,
            areaName = cleanArea,
            minOrderPaise = minOrderPaise,
            deliveryFeePaise = deliveryFeePaise,
            estimatedMins = estimatedMins,
            isActive = true,
          ),
        )
      }.onSuccess { created ->
        _uiState.update { state ->
          state.copy(
            isSubmitting = false,
            deliveryZones = (listOf(created) + state.deliveryZones).sortedBy { it.pincode },
          )
        }
        _events.tryEmit("Zone ${created.pincode} (${created.areaName}) added")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Could not create delivery zone.") }
      }
    }
  }

  fun updateDeliveryZone(zoneId: String, request: UpdateDeliveryZoneRequest) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.updateDeliveryZone(zoneId, request)
      }.onSuccess { updated ->
        _uiState.update { state ->
          state.copy(
            isSubmitting = false,
            deliveryZones = state.deliveryZones.map { if (it.id == updated.id) updated else it },
          )
        }
        _events.tryEmit("Zone ${updated.pincode} updated")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Zone update failed.") }
      }
    }
  }

  fun toggleZoneActive(zone: DeliveryZoneDto) {
    updateDeliveryZone(zone.id, UpdateDeliveryZoneRequest(isActive = !zone.isActive))
  }

  fun deleteDeliveryZone(zoneId: String) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.deleteDeliveryZone(zoneId)
      }.onSuccess {
        _uiState.update { state ->
          state.copy(
            isSubmitting = false,
            deliveryZones = state.deliveryZones.filterNot { it.id == zoneId },
          )
        }
        _events.tryEmit("Delivery zone removed")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Could not delete zone.") }
      }
    }
  }

  fun createScheduledSlot(
    slotDate: String,
    fulfillmentType: FulfillmentType,
    startTimeIst: String,
    endTimeIst: String,
    cutoffMinutesBeforeStart: Int,
    maxCapacity: Int,
  ) {
    if (slotDate.isBlank() || startTimeIst.isBlank() || endTimeIst.isBlank() || maxCapacity <= 0) {
      _uiState.update { it.copy(errorMessage = "Provide date, IST times, and positive capacity.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.createScheduledSlot(
          CreateScheduledSlotRequest(
            slotDate = slotDate.trim(),
            fulfillmentType = fulfillmentType,
            startTimeIst = startTimeIst.trim(),
            endTimeIst = endTimeIst.trim(),
            cutoffMinutesBeforeStart = cutoffMinutesBeforeStart.coerceAtLeast(0),
            maxCapacity = maxCapacity,
            isActive = true,
          ),
        )
      }.onSuccess { created ->
        _uiState.update { state ->
          val matchesDate = state.slotFilterDate.isBlank() || state.slotFilterDate == created.slotDate
          val matchesType = state.slotFilterType == null || state.slotFilterType == created.fulfillmentType
          val nextSlots = if (matchesDate && matchesType) {
            (state.scheduledSlots + created).sortedBy { it.startTimeIst }
          } else {
            state.scheduledSlots
          }
          state.copy(isSubmitting = false, scheduledSlots = nextSlots)
        }
        _events.tryEmit("Slot ${created.startTimeIst}-${created.endTimeIst} IST added")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Slot creation failed.") }
      }
    }
  }

  fun toggleSlotActive(slot: ScheduledSlotDto) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.updateScheduledSlot(slot.id, UpdateScheduledSlotRequest(isActive = !slot.isActive))
      }.onSuccess { updated ->
        _uiState.update { state ->
          state.copy(
            isSubmitting = false,
            scheduledSlots = state.scheduledSlots.map { if (it.id == updated.id) updated else it },
          )
        }
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Slot update failed.") }
      }
    }
  }

  fun updateSlotCapacity(slotId: String, maxCapacity: Int) {
    if (maxCapacity <= 0) {
      _uiState.update { it.copy(errorMessage = "Capacity must be positive.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.updateScheduledSlot(slotId, UpdateScheduledSlotRequest(maxCapacity = maxCapacity))
      }.onSuccess { updated ->
        _uiState.update { state ->
          state.copy(
            isSubmitting = false,
            scheduledSlots = state.scheduledSlots.map { if (it.id == updated.id) updated else it },
          )
        }
        _events.tryEmit("Slot capacity updated to ${updated.maxCapacity}")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Slot update failed.") }
      }
    }
  }

  fun deleteScheduledSlot(slotId: String) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
      runCatching {
        repository.deleteScheduledSlot(slotId)
      }.onSuccess {
        _uiState.update { state ->
          state.copy(
            isSubmitting = false,
            scheduledSlots = state.scheduledSlots.filterNot { it.id == slotId },
          )
        }
        _events.tryEmit("Scheduled slot removed")
      }.onFailure { t ->
        _uiState.update { it.copy(isSubmitting = false, errorMessage = t.message ?: "Could not delete slot.") }
      }
    }
  }

  private fun istDateRangeFor(preset: ReportDatePreset): Pair<String, String> {
    val todayIst = LocalDate.now(ZoneId.of("Asia/Kolkata"))
    val startIst = todayIst.minusDays(preset.daysBack)
    return startIst.toString() to todayIst.toString()
  }

  fun selectReportsSubTab(tab: OwnerReportsSubTab) {
    _uiState.update { it.copy(reportsSubTab = tab) }
    when (tab) {
      OwnerReportsSubTab.SALES -> if (_uiState.value.salesReport == null) loadSalesReport()
      OwnerReportsSubTab.OPERATIONS -> if (_uiState.value.operationsReport == null) loadOperationsReport()
      OwnerReportsSubTab.AUDIT_LOGS -> if (_uiState.value.auditLogs.isEmpty()) loadAuditLogs(reset = true)
    }
  }

  fun selectReportDatePreset(preset: ReportDatePreset) {
    _uiState.update { it.copy(reportDatePreset = preset) }
    loadSalesReport(preset)
    loadOperationsReport(preset)
  }

  fun loadReportsAndAudit() {
    val preset = _uiState.value.reportDatePreset
    loadSalesReport(preset)
    loadOperationsReport(preset)
    loadAuditLogs(entityType = _uiState.value.auditEntityFilter, reset = true)
  }

  fun loadSalesReport(preset: ReportDatePreset = _uiState.value.reportDatePreset) {
    val (start, end) = istDateRangeFor(preset)
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingReports = true, errorMessage = null) }
      runCatching {
        repository.fetchSalesReport(startDate = start, endDate = end)
      }.onSuccess { report ->
        _uiState.update { it.copy(isLoadingReports = false, salesReport = report) }
      }.onFailure { t ->
        _uiState.update {
          it.copy(isLoadingReports = false, errorMessage = t.message ?: "Failed to load sales report.")
        }
      }
    }
  }

  fun loadOperationsReport(preset: ReportDatePreset = _uiState.value.reportDatePreset) {
    val (start, end) = istDateRangeFor(preset)
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingReports = true, errorMessage = null) }
      runCatching {
        repository.fetchOperationsReport(startDate = start, endDate = end)
      }.onSuccess { report ->
        _uiState.update { it.copy(isLoadingReports = false, operationsReport = report) }
      }.onFailure { t ->
        _uiState.update {
          it.copy(isLoadingReports = false, errorMessage = t.message ?: "Failed to load operations report.")
        }
      }
    }
  }

  fun loadAuditLogs(
    entityType: String? = _uiState.value.auditEntityFilter,
    reset: Boolean = true,
  ) {
    val cleanFilter = entityType?.trim()?.takeIf { it.isNotEmpty() }
    val cursor = if (reset) null else _uiState.value.auditNextCursor
    if (!reset && cursor == null) return

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isLoadingAuditLogs = true,
          auditEntityFilter = cleanFilter,
          errorMessage = null,
        )
      }
      runCatching {
        repository.fetchAuditLogs(
          entityType = cleanFilter,
          cursor = cursor,
          limit = 25,
        )
      }.onSuccess { resp ->
        _uiState.update { state ->
          val combined = if (reset) {
            resp.entries
          } else {
            val seen = state.auditLogs.map { it.id }.toSet()
            state.auditLogs + resp.entries.filterNot { it.id in seen }
          }
          state.copy(
            isLoadingAuditLogs = false,
            auditLogs = combined,
            auditNextCursor = resp.nextCursor,
          )
        }
      }.onFailure { t ->
        _uiState.update {
          it.copy(isLoadingAuditLogs = false, errorMessage = t.message ?: "Failed to load audit logs.")
        }
      }
    }
  }

  fun loadMoreAuditLogs() {
    loadAuditLogs(entityType = _uiState.value.auditEntityFilter, reset = false)
  }

  fun clearSessionState() {
    _uiState.value = OwnerUiState()
  }
}

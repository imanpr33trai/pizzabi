package com.example.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.core.database.CartItemWithToppings
import com.example.core.domain.DomainBridge
import com.example.core.domain.RustDomainBridge
import com.example.ui.components.LocalReducedMotion
import com.example.ui.components.PizzoraBannerTone
import com.example.ui.components.PizzoraConfirmationDialog
import com.example.ui.components.PizzoraDietaryIndicator
import com.example.ui.components.PizzoraEmptyStateCard
import com.example.ui.components.PizzoraStatusBanner
import com.example.ui.components.PizzoraStickyActionDock
import com.example.ui.theme.EspressoBackground
import com.example.ui.theme.LocalPizzoraSemanticColors
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.example.ui.theme.WarmParchmentText
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductImageStatus
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto

sealed interface PizzaBuilderMode {
  data object Add : PizzaBuilderMode
  data class Edit(val cartItem: CartItemWithToppings) : PizzaBuilderMode
}

const val MIN_PIZZA_QTY = 1
const val MAX_PIZZA_QTY = 25

enum class ModifierSlotKind {
  CHEESE,
  SAUCE,
  STANDARD_TOPPINGS,
  HALF_AND_HALF,
}

sealed interface CustomizationSectionSlot {
  val id: String
  val kind: ModifierSlotKind
  val title: String
  val isRequired: Boolean
  val isSupported: Boolean
}

data class UnsupportedModifierSlot(
  override val id: String,
  override val kind: ModifierSlotKind,
  override val title: String,
  override val isRequired: Boolean = false,
) : CustomizationSectionSlot {
  override val isSupported: Boolean = false
}

fun defaultUnsupportedModifierSlots(): List<CustomizationSectionSlot> = listOf(
  UnsupportedModifierSlot(id = "slot_cheese", kind = ModifierSlotKind.CHEESE, title = "Cheese"),
  UnsupportedModifierSlot(id = "slot_sauce", kind = ModifierSlotKind.SAUCE, title = "Sauces"),
  UnsupportedModifierSlot(id = "slot_standard_toppings", kind = ModifierSlotKind.STANDARD_TOPPINGS, title = "Standard Toppings"),
  UnsupportedModifierSlot(id = "slot_half_and_half", kind = ModifierSlotKind.HALF_AND_HALF, title = "Half and Half"),
)

@Immutable
data class PizzaBuilderBreakdown(
  val variant: ProductVariantDto?,
  val crust: CrustOptionDto?,
  val selectedToppings: List<ToppingDto>,
  val quantity: Int,
  val baseSizePaise: Long,
  val crustExtraPaise: Long,
  val toppingsExtraPaise: Long,
  val unitPricePaise: Long,
  val itemTotalPaise: Long,
  val hasRequiredSelections: Boolean,
)

@Immutable
data class PizzaBuilderUiState(
  val productId: String,
  val selectedVariantId: String,
  val selectedCrustId: String,
  val selectedToppingIds: List<String> = emptyList(),
  val quantity: Int = MIN_PIZZA_QTY,
  val vegToppingsOnly: Boolean = false,
  val isBreakdownExpanded: Boolean = false,
  val isSubmitting: Boolean = false,
  val submissionError: String? = null,
)

fun firstActiveVariantId(variants: List<ProductVariantDto>): String =
  variants.sortedBy { it.sizeCode.ordinal }.firstOrNull { it.isActive }?.id.orEmpty()

fun firstActiveCrustId(crusts: List<CrustOptionDto>): String =
  crusts.firstOrNull { it.isActive }?.id.orEmpty()

fun hasBuilderUnsavedChanges(
  selectedVariantId: String,
  selectedCrustId: String,
  selectedToppingIds: Collection<String>,
  quantity: Int,
  initialVariantId: String,
  initialCrustId: String,
  initialToppingIds: Collection<String> = emptyList(),
  initialQuantity: Int = MIN_PIZZA_QTY,
): Boolean =
  selectedVariantId != initialVariantId ||
    selectedCrustId != initialCrustId ||
    selectedToppingIds.toSet() != initialToppingIds.toSet() ||
    quantity != initialQuantity

fun calculatePizzaBuilderBreakdown(
  sortedVariants: List<ProductVariantDto>,
  crusts: List<CrustOptionDto>,
  toppings: List<ToppingDto>,
  selectedVariantId: String,
  selectedCrustId: String,
  selectedToppingIds: Collection<String>,
  quantity: Int,
  domainBridge: DomainBridge = RustDomainBridge,
): PizzaBuilderBreakdown {
  val variant = sortedVariants.firstOrNull { it.isActive && it.id == selectedVariantId }
  val crust = crusts.firstOrNull { it.isActive && it.id == selectedCrustId }
  val selectedSet = selectedToppingIds.toSet()
  val activeSelectedToppings = toppings.filter { it.isActive && it.id in selectedSet }.distinctBy { it.id }
  val safeQty = quantity.coerceIn(MIN_PIZZA_QTY, MAX_PIZZA_QTY)

  val basePaise = variant?.pricePaise ?: 0L
  val crustPaise = crust?.extraPricePaise ?: 0L
  val toppingsPaise = activeSelectedToppings.sumOf { it.extraPricePaise }
  val unitPrice = basePaise + crustPaise + toppingsPaise
  val hasRequired = variant != null && crust != null
  val totalPaise = if (hasRequired) {
    domainBridge.calculateLineTotalPaise(unitPrice, safeQty)
  } else {
    0L
  }

  return PizzaBuilderBreakdown(
    variant = variant,
    crust = crust,
    selectedToppings = activeSelectedToppings,
    quantity = safeQty,
    baseSizePaise = basePaise,
    crustExtraPaise = crustPaise,
    toppingsExtraPaise = toppingsPaise,
    unitPricePaise = unitPrice,
    itemTotalPaise = totalPaise,
    hasRequiredSelections = hasRequired,
  )
}

@Composable
fun PizzaBuilderScreen(
  product: ProductDto?,
  crusts: List<CrustOptionDto>,
  toppings: List<ToppingDto>,
  onDismiss: () -> Unit,
  onConfirmAdd: (
    variant: ProductVariantDto,
    crust: CrustOptionDto,
    selectedToppings: List<ToppingDto>,
    quantity: Int,
    onResult: (success: Boolean, errorMessage: String?) -> Unit,
  ) -> Unit,
  modifier: Modifier = Modifier,
  mode: PizzaBuilderMode = PizzaBuilderMode.Add,
  isCatalogLoading: Boolean = false,
  isAcceptingOrders: Boolean = true,
  isFromOfflineCache: Boolean = false,
  modifierSlots: List<CustomizationSectionSlot> = defaultUnsupportedModifierSlots(),
  domainBridge: DomainBridge = RustDomainBridge,
) {
  val editCartEntry = (mode as? PizzaBuilderMode.Edit)?.cartItem
  val isEditMode = editCartEntry != null

  if (isEditMode && editCartEntry != null) {
    if (product == null && isCatalogLoading) {
      BuilderCatalogLoadingGuard(
        onDismiss = onDismiss,
        modifier = modifier,
      )
      return
    }
    val savedVariantOk = product != null &&
      product.isActive &&
      product.variants.any { it.id == editCartEntry.item.variantId && it.isActive }
    val savedCrustOk = crusts.any { it.id == editCartEntry.item.crustId && it.isActive }
    if (product == null || !savedVariantOk || !savedCrustOk) {
      BuilderDiscontinuedEditGuard(
        cartItem = editCartEntry,
        onDismiss = onDismiss,
        domainBridge = domainBridge,
        modifier = modifier,
      )
      return
    }
  }

  val activeProduct = product ?: return

  val sortedVariants = remember(activeProduct.id, activeProduct.variants) {
    activeProduct.variants.sortedBy { it.sizeCode.ordinal }
  }
  val hasRequiredActiveOptions = remember(sortedVariants, crusts) {
    sortedVariants.any { it.isActive } && crusts.any { it.isActive }
  }

  val activeToppingIds = remember(toppings) {
    toppings.filter { it.isActive }.map { it.id }.toSet()
  }

  val initialVariantId = remember(activeProduct.id, editCartEntry?.item?.id, sortedVariants) {
    editCartEntry?.item?.variantId ?: firstActiveVariantId(sortedVariants)
  }
  val initialCrustId = remember(activeProduct.id, editCartEntry?.item?.id, crusts) {
    editCartEntry?.item?.crustId ?: firstActiveCrustId(crusts)
  }
  val initialToppingIds = remember(editCartEntry?.item?.id, editCartEntry?.toppings, activeToppingIds) {
    editCartEntry?.toppings
      ?.map { it.toppingId }
      ?.filter { it in activeToppingIds }
      ?.distinct()
      ?.sorted()
      ?: emptyList()
  }
  val hadUnavailableSavedToppings = remember(editCartEntry?.item?.id, editCartEntry?.toppings, activeToppingIds) {
    editCartEntry?.toppings?.any { it.toppingId !in activeToppingIds } == true
  }
  val initialQuantity = remember(editCartEntry?.item?.id, editCartEntry?.item?.quantity) {
    (editCartEntry?.item?.quantity ?: MIN_PIZZA_QTY).coerceIn(MIN_PIZZA_QTY, MAX_PIZZA_QTY)
  }

  val stateKey = remember(activeProduct.id, editCartEntry?.item?.id) {
    if (editCartEntry != null) "edit_${editCartEntry.item.id}" else "add_${activeProduct.id}"
  }

  var selectedVariantId by rememberSaveable(stateKey) { mutableStateOf(initialVariantId) }
  var selectedCrustId by rememberSaveable(stateKey) { mutableStateOf(initialCrustId) }
  var selectedToppingIds by rememberSaveable(stateKey) { mutableStateOf(initialToppingIds) }
  var quantity by rememberSaveable(stateKey) { mutableIntStateOf(initialQuantity) }
  var vegToppingsOnly by rememberSaveable(stateKey) { mutableStateOf(false) }
  var isBreakdownExpanded by rememberSaveable(stateKey) { mutableStateOf(false) }
  var isSubmitting by remember { mutableStateOf(false) }
  var submissionError by remember(stateKey) { mutableStateOf<String?>(null) }
  var showDiscardConfirm by rememberSaveable(stateKey) { mutableStateOf(false) }

  // Keep defaults hydrated strictly from active options in Add Mode if catalog updates after mount
  LaunchedEffect(isEditMode, initialVariantId, sortedVariants) {
    if (!isEditMode) {
      val currentStillActive = sortedVariants.any { it.isActive && it.id == selectedVariantId }
      if (!currentStillActive) {
        selectedVariantId = initialVariantId
      }
    }
  }
  LaunchedEffect(isEditMode, initialCrustId, crusts) {
    if (!isEditMode) {
      val currentStillActive = crusts.any { it.isActive && it.id == selectedCrustId }
      if (!currentStillActive) {
        selectedCrustId = initialCrustId
      }
    }
  }
  LaunchedEffect(toppings) {
    val activeIds = toppings.filter { it.isActive }.map { it.id }.toSet()
    val sanitized = selectedToppingIds.filter { it in activeIds }
    if (sanitized != selectedToppingIds) {
      selectedToppingIds = sanitized
    }
  }

  val breakdown = remember(
    sortedVariants,
    crusts,
    toppings,
    selectedVariantId,
    selectedCrustId,
    selectedToppingIds,
    quantity,
    domainBridge,
  ) {
    calculatePizzaBuilderBreakdown(
      sortedVariants = sortedVariants,
      crusts = crusts,
      toppings = toppings,
      selectedVariantId = selectedVariantId,
      selectedCrustId = selectedCrustId,
      selectedToppingIds = selectedToppingIds,
      quantity = quantity,
      domainBridge = domainBridge,
    )
  }

  val hasUnsavedChanges = !isSubmitting && hasRequiredActiveOptions && hasBuilderUnsavedChanges(
    selectedVariantId = selectedVariantId,
    selectedCrustId = selectedCrustId,
    selectedToppingIds = selectedToppingIds,
    quantity = quantity,
    initialVariantId = initialVariantId,
    initialCrustId = initialCrustId,
    initialToppingIds = initialToppingIds,
    initialQuantity = initialQuantity,
  )

  val heroImageUrl = remember(activeProduct.id, activeProduct.images) {
    activeProduct.images
      .filter { it.status == ProductImageStatus.ACTIVE }
      .minByOrNull { it.sortOrder }
      ?.publicUrl
      ?: activeProduct.images.firstOrNull()?.publicUrl?.takeIf { it.isNotBlank() }
  }
  var imageLoadFailed by remember(heroImageUrl) { mutableStateOf(false) }
  val showHeroPhoto = !heroImageUrl.isNullOrBlank() && !imageLoadFailed

  val handleCloseAction: () -> Unit = {
    if (!isSubmitting) {
      if (hasUnsavedChanges) {
        showDiscardConfirm = true
      } else {
        onDismiss()
      }
    }
  }

  val handleSystemBack: () -> Unit = {
    when {
      isSubmitting -> Unit
      showDiscardConfirm -> showDiscardConfirm = false
      isBreakdownExpanded -> isBreakdownExpanded = false
      hasUnsavedChanges -> showDiscardConfirm = true
      else -> onDismiss()
    }
  }

  BackHandler(enabled = true, onBack = handleSystemBack)

  val handleConfirmAdd: () -> Unit = submit@{
    if (isSubmitting || !isAcceptingOrders || !hasRequiredActiveOptions) {
      return@submit
    }
    val chosenVariant = breakdown.variant ?: return@submit
    val chosenCrust = breakdown.crust ?: return@submit
    if (!chosenVariant.isActive || !chosenCrust.isActive) {
      return@submit
    }
    val safeToppings = breakdown.selectedToppings.filter { it.isActive }
    val safeQty = quantity.coerceIn(MIN_PIZZA_QTY, MAX_PIZZA_QTY)

    isSubmitting = true
    submissionError = null
    onConfirmAdd(chosenVariant, chosenCrust, safeToppings, safeQty) { success, err ->
      isSubmitting = false
      if (success) {
        onDismiss()
      } else {
        submissionError = err ?: "Unable to add this pizza to your cart. Please try again."
      }
    }
  }

  val canSubmit = isAcceptingOrders &&
    hasRequiredActiveOptions &&
    breakdown.hasRequiredSelections &&
    !isSubmitting

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .semantics { isTraversalGroup = true }
      .focusGroup()
      .testTag("pizza_customizer_sheet"),
  ) {
    val viewportWidth = maxWidth
    val viewportHeight = maxHeight
    val density = LocalDensity.current
    val isTabletTwoPane = viewportWidth >= 600.dp && viewportHeight >= 480.dp
    val isLandscapeCompact = viewportWidth >= 600.dp && viewportHeight < 480.dp

    if (isTabletTwoPane) {
      val leftPaneWidth = viewportWidth * 0.4f
      val rightPaneWidth = viewportWidth * 0.6f
      val stackSizesVertically = rightPaneWidth < 360.dp || density.fontScale >= 1.4f
      val stackDockControls = leftPaneWidth < 360.dp || density.fontScale >= 1.4f

      Row(modifier = Modifier.fillMaxSize()) {
        // Left 40% Summary + Receipt + Dock Pane
        Surface(
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 0.dp,
          modifier = Modifier
            .weight(0.4f)
            .fillMaxHeight()
            .testTag("builder_left_pane"),
        ) {
          Column(modifier = Modifier.fillMaxSize()) {
            BuilderPinnedTopBar(
              product = activeProduct,
              titleVisible = true,
              overHero = false,
              isSubmitting = isSubmitting,
              isEditMode = isEditMode,
              onCloseClick = handleCloseAction,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Column(
              modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              if (showHeroPhoto) {
                BuilderPhotoFrame(
                  imageUrl = heroImageUrl!!,
                  productName = activeProduct.name,
                  height = 180.dp,
                  onImageError = { imageLoadFailed = true },
                )
              } else {
                BuilderCompactSummaryCard(product = activeProduct, isEditMode = isEditMode)
              }

              if (showHeroPhoto) {
                BuilderProductInfoBlock(product = activeProduct, isEditMode = isEditMode)
              }

              PriceBreakdownReceiptCard(
                breakdown = breakdown,
                isOfflineCache = isFromOfflineCache,
                domainBridge = domainBridge,
              )
            }

            BuilderStickyDock(
              breakdown = breakdown,
              quantity = quantity,
              onQuantityChange = { next ->
                quantity = next.coerceIn(MIN_PIZZA_QTY, MAX_PIZZA_QTY)
              },
              isBreakdownExpanded = false,
              onToggleBreakdown = null,
              showSummaryHeader = false,
              isBreakdownExpandable = false,
              canSubmit = canSubmit,
              isAcceptingOrders = isAcceptingOrders,
              hasRequiredActiveOptions = hasRequiredActiveOptions,
              isSubmitting = isSubmitting,
              isEditMode = isEditMode,
              stackControls = stackDockControls,
              compactPadding = false,
              maxDrawerHeight = 200.dp,
              isOfflineCache = isFromOfflineCache,
              domainBridge = domainBridge,
              onSubmit = handleConfirmAdd,
            )
          }
        }

        VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)

        // Right 60% Customization Options Pane
        Column(
          modifier = Modifier
            .weight(0.6f)
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("builder_right_pane"),
          verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
          if (hadUnavailableSavedToppings) {
            PizzoraStatusBanner(
              message = stringResource(R.string.builder_unavailable_topping_warning),
              tone = PizzoraBannerTone.WARNING,
              modifier = Modifier.testTag("builder_unavailable_topping_warning"),
            )
          }
          BuilderCustomizationSections(
            isAcceptingOrders = isAcceptingOrders,
            hasRequiredActiveOptions = hasRequiredActiveOptions,
            submissionError = submissionError,
            sortedVariants = sortedVariants,
            selectedVariantId = selectedVariantId,
            onSelectVariant = { id -> selectedVariantId = id },
            crusts = crusts,
            selectedCrustId = selectedCrustId,
            onSelectCrust = { id -> selectedCrustId = id },
            modifierSlots = modifierSlots,
            toppings = toppings,
            selectedToppingIds = selectedToppingIds,
            onToggleTopping = { id ->
              selectedToppingIds = if (id in selectedToppingIds) {
                selectedToppingIds - id
              } else {
                selectedToppingIds + id
              }
            },
            onClearSelectedToppings = { selectedToppingIds = emptyList() },
            vegToppingsOnly = vegToppingsOnly,
            onVegToppingsFilterChange = { vegOnly -> vegToppingsOnly = vegOnly },
            stackSizesVertically = stackSizesVertically,
            isSubmitting = isSubmitting,
            domainBridge = domainBridge,
          )
          Spacer(modifier = Modifier.height(12.dp))
        }
      }
    } else {
      // Single-column layout for phones & landscape compact phones (< 480dp height)
      val stackSizesVertically = viewportWidth < 360.dp || density.fontScale >= 1.4f
      val stackDockControls = viewportWidth < 360.dp || density.fontScale >= 1.4f
      val scrollState = rememberScrollState()
      val baseTopBarDp = 60.dp
      val heroExtraDp = if (showHeroPhoto && !isLandscapeCompact) 150.dp else 0.dp
      val heroExtraPx = with(density) { heroExtraDp.toPx() }
      var heroCollapseOffsetPx by rememberSaveable(stateKey, showHeroPhoto, isLandscapeCompact) {
        mutableFloatStateOf(0f)
      }
      val collapseFraction = if (heroExtraPx > 0f) {
        (-heroCollapseOffsetPx / heroExtraPx).coerceIn(0f, 1f)
      } else {
        1f
      }
      val nestedScrollConnection = remember(heroExtraPx) {
        object : NestedScrollConnection {
          override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (heroExtraPx <= 0f || available.y >= 0f) return Offset.Zero
            val previous = heroCollapseOffsetPx
            val next = (previous + available.y).coerceIn(-heroExtraPx, 0f)
            heroCollapseOffsetPx = next
            return Offset(0f, next - previous)
          }

          override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
          ): Offset {
            if (heroExtraPx <= 0f || available.y <= 0f || scrollState.value > 0) return Offset.Zero
            val previous = heroCollapseOffsetPx
            val next = (previous + available.y).coerceIn(-heroExtraPx, 0f)
            heroCollapseOffsetPx = next
            return Offset(0f, next - previous)
          }
        }
      }

      Column(
        modifier = Modifier
          .fillMaxSize()
          .nestedScroll(nestedScrollConnection),
      ) {
        if (showHeroPhoto && !isLandscapeCompact) {
          val currentHeroHeight = baseTopBarDp + (heroExtraDp * (1f - collapseFraction))
          val isCollapsed = collapseFraction >= 0.72f
          Box(modifier = Modifier.fillMaxWidth()) {
            if (!isCollapsed) {
              BuilderFullBleedHeroHeader(
                product = activeProduct,
                heroImageUrl = heroImageUrl!!,
                heroHeight = currentHeroHeight,
                isEditMode = isEditMode,
                onImageError = { imageLoadFailed = true },
              )
            }
            BuilderPinnedTopBar(
              product = activeProduct,
              titleVisible = isCollapsed || isEditMode,
              overHero = !isCollapsed,
              isSubmitting = isSubmitting,
              isEditMode = isEditMode,
              onCloseClick = handleCloseAction,
            )
          }
        } else {
          BuilderPinnedTopBar(
            product = activeProduct,
            titleVisible = isLandscapeCompact || isEditMode,
            overHero = false,
            isSubmitting = isSubmitting,
            isEditMode = isEditMode,
            onCloseClick = handleCloseAction,
          )
          HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }

        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
          contentAlignment = Alignment.TopCenter,
        ) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .widthIn(max = 640.dp)
              .verticalScroll(scrollState),
          ) {
            if (!showHeroPhoto || isLandscapeCompact) {
              BuilderCompactTopHeader(
                product = activeProduct,
                compact = isLandscapeCompact,
                isEditMode = isEditMode,
              )
            }

            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
              verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
              if (showHeroPhoto && !isLandscapeCompact && activeProduct.description.isNotBlank()) {
                Text(
                  text = activeProduct.description,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              if (hadUnavailableSavedToppings) {
                PizzoraStatusBanner(
                  message = stringResource(R.string.builder_unavailable_topping_warning),
                  tone = PizzoraBannerTone.WARNING,
                  modifier = Modifier.testTag("builder_unavailable_topping_warning"),
                )
              }

              BuilderCustomizationSections(
                isAcceptingOrders = isAcceptingOrders,
                hasRequiredActiveOptions = hasRequiredActiveOptions,
                submissionError = submissionError,
                sortedVariants = sortedVariants,
                selectedVariantId = selectedVariantId,
                onSelectVariant = { id -> selectedVariantId = id },
                crusts = crusts,
                selectedCrustId = selectedCrustId,
                onSelectCrust = { id -> selectedCrustId = id },
                modifierSlots = modifierSlots,
                toppings = toppings,
                selectedToppingIds = selectedToppingIds,
                onToggleTopping = { id ->
                  selectedToppingIds = if (id in selectedToppingIds) {
                    selectedToppingIds - id
                  } else {
                    selectedToppingIds + id
                  }
                },
                onClearSelectedToppings = { selectedToppingIds = emptyList() },
                vegToppingsOnly = vegToppingsOnly,
                onVegToppingsFilterChange = { vegOnly -> vegToppingsOnly = vegOnly },
                stackSizesVertically = stackSizesVertically,
                isSubmitting = isSubmitting,
                domainBridge = domainBridge,
              )
              Spacer(modifier = Modifier.height(8.dp))
            }
          }
        }

        BuilderStickyDock(
          breakdown = breakdown,
          quantity = quantity,
          onQuantityChange = { next ->
            quantity = next.coerceIn(MIN_PIZZA_QTY, MAX_PIZZA_QTY)
          },
          isBreakdownExpanded = isBreakdownExpanded,
          onToggleBreakdown = { isBreakdownExpanded = !isBreakdownExpanded },
          showSummaryHeader = true,
          isBreakdownExpandable = true,
          canSubmit = canSubmit,
          isAcceptingOrders = isAcceptingOrders,
          hasRequiredActiveOptions = hasRequiredActiveOptions,
          isSubmitting = isSubmitting,
          isEditMode = isEditMode,
          stackControls = stackDockControls,
          compactPadding = isLandscapeCompact,
          maxDrawerHeight = (viewportHeight * 0.42f).coerceAtLeast(140.dp),
          isOfflineCache = isFromOfflineCache,
          domainBridge = domainBridge,
          onSubmit = handleConfirmAdd,
        )
      }
    }

    if (showDiscardConfirm) {
      PizzoraConfirmationDialog(
        title = stringResource(R.string.builder_discard_title),
        body = stringResource(R.string.builder_discard_body, activeProduct.name),
        confirmLabel = stringResource(R.string.builder_discard_confirm),
        dismissLabel = stringResource(R.string.builder_keep_editing),
        onConfirm = {
          showDiscardConfirm = false
          onDismiss()
        },
        onDismiss = { showDiscardConfirm = false },
        isDestructive = true,
        confirmTestTag = "builder_confirm_discard_button",
        dismissTestTag = "builder_keep_editing_button",
      )
    }
  }
}

@Composable
private fun BuilderPinnedTopBar(
  product: ProductDto,
  titleVisible: Boolean,
  overHero: Boolean,
  isSubmitting: Boolean,
  isEditMode: Boolean = false,
  onCloseClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val bgColor by animateColorAsState(
    targetValue = if (overHero) Color.Transparent else MaterialTheme.colorScheme.surface,
    label = "builderTopBarBg",
  )

  Surface(
    color = bgColor,
    tonalElevation = 0.dp,
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .heightIn(min = 60.dp)
        .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = if (overHero) EspressoBackground.copy(alpha = 0.72f) else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        modifier = Modifier.size(48.dp),
      ) {
        IconButton(
          onClick = onCloseClick,
          enabled = !isSubmitting,
          modifier = Modifier
            .fillMaxSize()
            .minimumInteractiveComponentSize()
            .testTag("customizer_close_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.action_close),
            tint = if (overHero) WarmParchmentText else MaterialTheme.colorScheme.onSurface,
          )
        }
      }

      if (titleVisible) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          PizzoraDietaryIndicator(isVeg = product.isVeg)
          Text(
            text = product.name,
            style = MaterialTheme.typography.titleMedium,
            color = if (overHero) WarmParchmentText else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
        }
      } else {
        Spacer(modifier = Modifier.weight(1f))
      }

      if (isEditMode) {
        BuilderEditModeTag(overHero = overHero)
      }
    }
  }
}

@Composable
private fun BuilderEditModeTag(overHero: Boolean = false) {
  Surface(
    color = if (overHero) EspressoBackground.copy(alpha = 0.85f) else MaterialTheme.colorScheme.primaryContainer,
    shape = RoundedCornerShape(8.dp),
    tonalElevation = 0.dp,
    modifier = Modifier.testTag("builder_edit_mode_badge"),
  ) {
    Text(
      text = stringResource(R.string.builder_header_editing),
      style = MaterialTheme.typography.labelSmall,
      color = if (overHero) WarmParchmentText else MaterialTheme.colorScheme.onPrimaryContainer,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

@Composable
private fun BuilderFullBleedHeroHeader(
  product: ProductDto,
  heroImageUrl: String,
  heroHeight: Dp,
  isEditMode: Boolean = false,
  onImageError: () -> Unit,
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(heroHeight)
      .background(MaterialTheme.colorScheme.surfaceVariant),
  ) {
    AsyncImage(
      model = heroImageUrl,
      contentDescription = "${product.name} photo",
      contentScale = ContentScale.Crop,
      onError = { onImageError() },
      modifier = Modifier
        .fillMaxSize()
        .testTag("builder_hero_image"),
    )
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors = listOf(
              Color.Transparent,
              EspressoBackground.copy(alpha = 0.35f),
              EspressoBackground.copy(alpha = 0.88f),
            ),
          ),
        ),
    )
    Column(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        PizzoraDietaryIndicator(isVeg = product.isVeg)
        Text(
          text = if (isEditMode) {
            "${product.categoryName} · ${stringResource(R.string.builder_editing_badge)}"
          } else {
            product.categoryName
          },
          style = MaterialTheme.typography.labelMedium,
          color = Color(0xFFE5D8CA),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = product.name,
        style = MaterialTheme.typography.headlineMedium,
        color = WarmParchmentText,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun BuilderCompactTopHeader(
  product: ProductDto,
  compact: Boolean,
  isEditMode: Boolean = false,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
    modifier = Modifier
      .fillMaxWidth()
      .testTag("builder_compact_header"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(
          horizontal = 16.dp,
          vertical = if (compact) 10.dp else 14.dp,
        ),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      BuilderProductInfoBlock(product = product, compact = compact, isEditMode = isEditMode)
    }
  }
  HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun BuilderCompactSummaryCard(
  product: ProductDto,
  isEditMode: Boolean = false,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier
      .fillMaxWidth()
      .testTag("builder_compact_header"),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.LocalPizza,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
        Text(
          text = product.categoryName,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      BuilderProductInfoBlock(product = product, isEditMode = isEditMode)
    }
  }
}

@Composable
private fun BuilderPhotoFrame(
  imageUrl: String,
  productName: String,
  height: Dp,
  onImageError: () -> Unit,
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(height)
      .clip(MaterialTheme.shapes.medium)
      .background(MaterialTheme.colorScheme.surfaceVariant),
  ) {
    AsyncImage(
      model = imageUrl,
      contentDescription = "$productName photo",
      contentScale = ContentScale.Crop,
      onError = { onImageError() },
      modifier = Modifier
        .fillMaxSize()
        .testTag("builder_hero_image"),
    )
  }
}

@Composable
private fun BuilderProductInfoBlock(
  product: ProductDto,
  compact: Boolean = false,
  isEditMode: Boolean = false,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      PizzoraDietaryIndicator(isVeg = product.isVeg)
      Text(
        text = if (isEditMode) {
          "${product.categoryName} · ${stringResource(R.string.builder_editing_badge)}"
        } else {
          product.categoryName
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Text(
      text = product.name,
      style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    if (product.description.isNotBlank()) {
      Text(
        text = product.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (compact) 2 else 4,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuilderCustomizationSections(
  isAcceptingOrders: Boolean,
  hasRequiredActiveOptions: Boolean,
  submissionError: String?,
  sortedVariants: List<ProductVariantDto>,
  selectedVariantId: String,
  onSelectVariant: (String) -> Unit,
  crusts: List<CrustOptionDto>,
  selectedCrustId: String,
  onSelectCrust: (String) -> Unit,
  modifierSlots: List<CustomizationSectionSlot>,
  toppings: List<ToppingDto>,
  selectedToppingIds: List<String>,
  onToggleTopping: (String) -> Unit,
  onClearSelectedToppings: () -> Unit,
  vegToppingsOnly: Boolean,
  onVegToppingsFilterChange: (Boolean) -> Unit,
  stackSizesVertically: Boolean,
  isSubmitting: Boolean,
  domainBridge: DomainBridge,
) {
  if (!isAcceptingOrders) {
    PizzoraStatusBanner(
      message = stringResource(R.string.builder_paused_banner),
      tone = PizzoraBannerTone.WARNING,
      icon = Icons.Filled.Storefront,
      modifier = Modifier.testTag("builder_paused_kitchen_banner"),
    )
  }

  if (submissionError != null) {
    PizzoraStatusBanner(
      message = submissionError,
      tone = PizzoraBannerTone.ERROR,
      modifier = Modifier.testTag("builder_submission_error"),
    )
  }

  if (!hasRequiredActiveOptions) {
    PizzoraEmptyStateCard(
      title = stringResource(R.string.builder_unavailable_title),
      subtitle = stringResource(R.string.builder_unavailable_subtitle),
      modifier = Modifier.testTag("builder_unavailable_state"),
    )
  }

  // 1. Size Section (Required)
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    SectionHeaderRow(
      title = stringResource(R.string.customizer_choose_size),
      requirementLabel = stringResource(R.string.builder_required_tag),
      isRequired = true,
    )
    if (sortedVariants.isEmpty()) {
      PizzoraStatusBanner(
        message = stringResource(R.string.builder_unavailable_subtitle),
        tone = PizzoraBannerTone.WARNING,
      )
    } else if (stackSizesVertically) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        sortedVariants.forEach { variant ->
          val isSelected = variant.isActive && variant.id == selectedVariantId
          SizeOptionRowCard(
            variant = variant,
            isSelected = isSelected,
            enabled = variant.isActive && !isSubmitting,
            domainBridge = domainBridge,
            onSelect = { onSelectVariant(variant.id) },
          )
        }
      }
    } else {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        sortedVariants.forEach { variant ->
          val isSelected = variant.isActive && variant.id == selectedVariantId
          SizeOptionGridCard(
            variant = variant,
            isSelected = isSelected,
            enabled = variant.isActive && !isSubmitting,
            domainBridge = domainBridge,
            onSelect = { onSelectVariant(variant.id) },
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }

  // 2. Crust Section (Required)
  if (crusts.isNotEmpty()) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      SectionHeaderRow(
        title = stringResource(R.string.customizer_choose_crust),
        requirementLabel = stringResource(R.string.builder_required_tag),
        isRequired = true,
      )
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        crusts.forEach { crust ->
          val isSelected = crust.isActive && crust.id == selectedCrustId
          CrustOptionCard(
            crust = crust,
            isSelected = isSelected,
            enabled = crust.isActive && !isSubmitting,
            domainBridge = domainBridge,
            onSelect = { onSelectCrust(crust.id) },
          )
        }
      }
    }
  }

  // 3. Extensible Modifier Slots (hidden when unsupported by current backend contracts)
  CustomizationSectionSlotHost(slots = modifierSlots)

  // 4. Extra Toppings Section (Optional)
  if (toppings.isNotEmpty()) {
    val filteredToppings = remember(toppings, vegToppingsOnly) {
      if (vegToppingsOnly) toppings.filter { it.isVeg } else toppings
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        SectionHeaderRow(
          title = stringResource(R.string.customizer_extra_toppings),
          requirementLabel = if (selectedToppingIds.isNotEmpty()) {
            "${selectedToppingIds.size} selected"
          } else {
            stringResource(R.string.builder_optional_tag)
          },
          isRequired = false,
          modifier = Modifier.weight(1f),
        )
        if (selectedToppingIds.isNotEmpty()) {
          TextButton(
            onClick = onClearSelectedToppings,
            enabled = !isSubmitting,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("builder_clear_toppings_button"),
          ) {
            Text(stringResource(R.string.action_clear))
          }
        }
      }

      val semantic = LocalPizzoraSemanticColors.current
      val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = semantic.selectedSurface,
        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
        selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
      )

      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        FilterChip(
          selected = !vegToppingsOnly,
          onClick = { onVegToppingsFilterChange(false) },
          label = { Text(stringResource(R.string.builder_all_toppings)) },
          colors = chipColors,
          border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = !vegToppingsOnly,
            borderColor = semantic.outlineInteractive,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            selectedBorderWidth = 1.5.dp,
          ),
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("builder_toppings_filter_all"),
        )
        FilterChip(
          selected = vegToppingsOnly,
          onClick = { onVegToppingsFilterChange(!vegToppingsOnly) },
          label = { Text(stringResource(R.string.menu_veg_only)) },
          leadingIcon = {
            Icon(
              imageVector = Icons.Filled.Eco,
              contentDescription = null,
              tint = semantic.vegBadgeGreen,
              modifier = Modifier.size(16.dp),
            )
          },
          colors = chipColors,
          border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = vegToppingsOnly,
            borderColor = semantic.outlineInteractive,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            selectedBorderWidth = 1.5.dp,
          ),
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("builder_toppings_filter_veg"),
        )
        if (vegToppingsOnly) {
          TextButton(
            onClick = { onVegToppingsFilterChange(false) },
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("builder_clear_topping_filter_button"),
          ) {
            Text(stringResource(R.string.builder_clear_filter))
          }
        }
      }

      if (filteredToppings.isEmpty()) {
        Surface(
          color = MaterialTheme.colorScheme.surface,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = stringResource(R.string.builder_no_veg_toppings),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f),
            )
            TextButton(
              onClick = { onVegToppingsFilterChange(false) },
              modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
              Text(stringResource(R.string.builder_show_all_toppings))
            }
          }
        }
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          filteredToppings.forEach { topping ->
            val checked = topping.isActive && topping.id in selectedToppingIds
            ToppingOptionCard(
              topping = topping,
              checked = checked,
              enabled = topping.isActive && !isSubmitting,
              domainBridge = domainBridge,
              onToggle = { onToggleTopping(topping.id) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CustomizationSectionSlotHost(slots: List<CustomizationSectionSlot>) {
  // Backend-gated modifier slots (Cheese, Sauces, Standard Toppings, Half-and-Half)
  // remain hidden until server contracts, pricing engine, and DTOs support them.
  val activeSlots = slots.filter { it.isSupported }
  if (activeSlots.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    activeSlots.forEach { slot ->
      Text(
        text = slot.title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
private fun SectionHeaderRow(
  title: String,
  requirementLabel: String,
  isRequired: Boolean,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Surface(
      color = if (isRequired) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
      shape = RoundedCornerShape(6.dp),
      tonalElevation = 0.dp,
    ) {
      Text(
        text = requirementLabel,
        style = MaterialTheme.typography.labelSmall,
        color = if (isRequired) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      )
    }
  }
}

@Composable
private fun SizeOptionGridCard(
  variant: ProductVariantDto,
  isSelected: Boolean,
  enabled: Boolean,
  domainBridge: DomainBridge,
  onSelect: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val containerColor = when {
    !variant.isActive -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    isSelected -> semantic.selectedSurface
    else -> MaterialTheme.colorScheme.surface
  }
  val borderColor = when {
    !variant.isActive -> MaterialTheme.colorScheme.outline
    isSelected -> MaterialTheme.colorScheme.primary
    else -> semantic.outlineInteractive
  }
  val textColor = if (variant.isActive) {
    MaterialTheme.colorScheme.onSurface
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
  }

  Surface(
    shape = MaterialTheme.shapes.medium,
    color = containerColor,
    tonalElevation = 0.dp,
    border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
    modifier = modifier
      .heightIn(min = 84.dp)
      .minimumInteractiveComponentSize()
      .clip(MaterialTheme.shapes.medium)
      .selectable(
        selected = isSelected,
        enabled = enabled,
        role = Role.RadioButton,
        onClick = onSelect,
      )
      .testTag("size_option_${variant.sizeCode.name}"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
      horizontalAlignment = Alignment.Start,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = sizeDisplayName(variant.sizeCode),
          style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
          ),
          color = textColor,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        if (isSelected) {
          Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
          )
        }
      }
      Text(
        text = domainBridge.formatInr(variant.pricePaise),
        style = PizzoraMonoBody,
        color = if (isSelected) MaterialTheme.colorScheme.primary else textColor,
        maxLines = 1,
      )
      if (!variant.isActive) {
        UnavailableTag()
      }
    }
  }
}

@Composable
private fun SizeOptionRowCard(
  variant: ProductVariantDto,
  isSelected: Boolean,
  enabled: Boolean,
  domainBridge: DomainBridge,
  onSelect: () -> Unit,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val containerColor = when {
    !variant.isActive -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    isSelected -> semantic.selectedSurface
    else -> MaterialTheme.colorScheme.surface
  }
  val borderColor = when {
    !variant.isActive -> MaterialTheme.colorScheme.outline
    isSelected -> MaterialTheme.colorScheme.primary
    else -> semantic.outlineInteractive
  }
  val textColor = if (variant.isActive) {
    MaterialTheme.colorScheme.onSurface
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
  }

  Surface(
    shape = MaterialTheme.shapes.medium,
    color = containerColor,
    tonalElevation = 0.dp,
    border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .minimumInteractiveComponentSize()
      .clip(MaterialTheme.shapes.medium)
      .selectable(
        selected = isSelected,
        enabled = enabled,
        role = Role.RadioButton,
        onClick = onSelect,
      )
      .testTag("size_option_${variant.sizeCode.name}"),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.weight(1f),
      ) {
        RadioButton(
          selected = isSelected,
          onClick = null,
          enabled = variant.isActive,
          colors = RadioButtonDefaults.colors(
            selectedColor = MaterialTheme.colorScheme.primary,
            unselectedColor = semantic.outlineInteractive,
          ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = sizeDisplayName(variant.sizeCode),
            style = MaterialTheme.typography.bodyLarge.copy(
              fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = textColor,
          )
          if (!variant.isActive) {
            UnavailableTag()
          }
        }
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = domainBridge.formatInr(variant.pricePaise),
        style = PizzoraMonoBody,
        color = if (isSelected) MaterialTheme.colorScheme.primary else textColor,
      )
    }
  }
}

@Composable
private fun CrustOptionCard(
  crust: CrustOptionDto,
  isSelected: Boolean,
  enabled: Boolean,
  domainBridge: DomainBridge,
  onSelect: () -> Unit,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val containerColor = when {
    !crust.isActive -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    isSelected -> semantic.selectedSurface
    else -> MaterialTheme.colorScheme.surface
  }
  val borderColor = when {
    !crust.isActive -> MaterialTheme.colorScheme.outline
    isSelected -> MaterialTheme.colorScheme.primary
    else -> semantic.outlineInteractive
  }
  val textColor = if (crust.isActive) {
    MaterialTheme.colorScheme.onSurface
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
  }

  Surface(
    shape = MaterialTheme.shapes.medium,
    color = containerColor,
    tonalElevation = 0.dp,
    border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .minimumInteractiveComponentSize()
      .clip(MaterialTheme.shapes.medium)
      .selectable(
        selected = isSelected,
        enabled = enabled,
        role = Role.RadioButton,
        onClick = onSelect,
      )
      .testTag("crust_option_${crust.id}"),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.weight(1f),
      ) {
        RadioButton(
          selected = isSelected,
          onClick = null,
          enabled = crust.isActive,
          colors = RadioButtonDefaults.colors(
            selectedColor = MaterialTheme.colorScheme.primary,
            unselectedColor = semantic.outlineInteractive,
          ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = crust.name,
            style = MaterialTheme.typography.bodyLarge.copy(
              fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = textColor,
          )
          if (!crust.isActive) {
            UnavailableTag()
          }
        }
      }
      Spacer(modifier = Modifier.width(8.dp))
      val priceLabel = if (crust.extraPricePaise == 0L) {
        stringResource(R.string.builder_included)
      } else {
        "+ ${domainBridge.formatInr(crust.extraPricePaise)}"
      }
      val priceColor = when {
        !crust.isActive -> textColor
        crust.extraPricePaise == 0L -> MaterialTheme.colorScheme.secondary
        else -> textColor
      }
      Text(
        text = priceLabel,
        style = if (crust.extraPricePaise == 0L) MaterialTheme.typography.labelLarge else PizzoraMonoBody,
        color = priceColor,
      )
    }
  }
}

@Composable
private fun ToppingOptionCard(
  topping: ToppingDto,
  checked: Boolean,
  enabled: Boolean,
  domainBridge: DomainBridge,
  onToggle: () -> Unit,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val containerColor = when {
    !topping.isActive -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    checked -> semantic.selectedSurface
    else -> MaterialTheme.colorScheme.surface
  }
  val borderColor = when {
    !topping.isActive -> MaterialTheme.colorScheme.outline
    checked -> MaterialTheme.colorScheme.primary
    else -> semantic.outlineInteractive
  }
  val textColor = if (topping.isActive) {
    MaterialTheme.colorScheme.onSurface
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
  }

  Surface(
    shape = MaterialTheme.shapes.medium,
    color = containerColor,
    tonalElevation = 0.dp,
    border = BorderStroke(if (checked) 1.5.dp else 1.dp, borderColor),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .minimumInteractiveComponentSize()
      .clip(MaterialTheme.shapes.medium)
      .toggleable(
        value = checked,
        enabled = enabled,
        role = Role.Checkbox,
        onValueChange = { onToggle() },
      )
      .testTag("topping_option_${topping.id}"),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.weight(1f),
      ) {
        Checkbox(
          checked = checked,
          onCheckedChange = null,
          enabled = topping.isActive,
          colors = CheckboxDefaults.colors(
            checkedColor = MaterialTheme.colorScheme.primary,
            checkmarkColor = MaterialTheme.colorScheme.onPrimary,
            uncheckedColor = semantic.outlineInteractive,
          ),
        )
        PizzoraDietaryIndicator(isVeg = topping.isVeg)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = topping.name,
            style = MaterialTheme.typography.bodyLarge.copy(
              fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = textColor,
          )
          if (!topping.isActive) {
            UnavailableTag()
          }
        }
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = "+ ${domainBridge.formatInr(topping.extraPricePaise)}",
        style = PizzoraMonoBody,
        color = textColor,
      )
    }
  }
}

@Composable
private fun UnavailableTag() {
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    shape = RoundedCornerShape(4.dp),
    tonalElevation = 0.dp,
  ) {
    Text(
      text = stringResource(R.string.builder_unavailable),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }
}

@Composable
private fun BuilderStickyDock(
  breakdown: PizzaBuilderBreakdown,
  quantity: Int,
  onQuantityChange: (Int) -> Unit,
  isBreakdownExpanded: Boolean,
  onToggleBreakdown: (() -> Unit)?,
  showSummaryHeader: Boolean,
  isBreakdownExpandable: Boolean,
  canSubmit: Boolean,
  isAcceptingOrders: Boolean,
  hasRequiredActiveOptions: Boolean,
  isSubmitting: Boolean,
  isEditMode: Boolean,
  stackControls: Boolean,
  compactPadding: Boolean,
  maxDrawerHeight: Dp,
  isOfflineCache: Boolean,
  domainBridge: DomainBridge,
  onSubmit: () -> Unit,
) {
  val reducedMotion = LocalReducedMotion.current

  PizzoraStickyActionDock(
    applyNavigationBarsPadding = true,
    topSlot = if (showSummaryHeader || isBreakdownExpanded) {
      {
        if (isBreakdownExpandable && isBreakdownExpanded) {
          AnimatedVisibility(
            visible = true,
            enter = if (reducedMotion) fadeIn() else fadeIn() + expandVertically(),
            exit = if (reducedMotion) fadeOut() else fadeOut() + shrinkVertically(),
          ) {
            PriceBreakdownDrawer(
              breakdown = breakdown,
              isOfflineCache = isOfflineCache,
              domainBridge = domainBridge,
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxDrawerHeight)
                .verticalScroll(rememberScrollState())
                .testTag("builder_breakdown_drawer"),
            )
          }
          HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        if (showSummaryHeader) {
          val summaryLine = buildString {
            val sizeName = breakdown.variant?.let { sizeDisplayName(it.sizeCode) } ?: "Unavailable"
            val crustName = breakdown.crust?.name ?: "No Crust"
            append(sizeName)
            append(" · ")
            append(crustName)
            val count = breakdown.selectedToppings.size
            if (count > 0) {
              append(" · ")
              append(if (count == 1) "1 topping" else "$count toppings")
            }
          }
          Surface(
            onClick = { onToggleBreakdown?.invoke() },
            enabled = onToggleBreakdown != null,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier
              .fillMaxWidth()
              .minimumInteractiveComponentSize()
              .testTag("builder_breakdown_toggle"),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = if (compactPadding) 6.dp else 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = summaryLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  text = if (isBreakdownExpanded) {
                    stringResource(R.string.builder_hide_price_details)
                  } else {
                    stringResource(R.string.builder_price_details)
                  },
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                  imageVector = if (isBreakdownExpanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          }
        }
      }
    } else {
      null
    },
  ) {
    val ctaLabel = when {
      !isAcceptingOrders -> stringResource(R.string.builder_store_paused_cta)
      !hasRequiredActiveOptions || !breakdown.hasRequiredSelections -> stringResource(R.string.builder_unavailable_cta)
      isSubmitting && isEditMode -> stringResource(R.string.builder_updating_item)
      isSubmitting -> stringResource(R.string.builder_adding_to_cart)
      isEditMode -> stringResource(R.string.builder_update_item_fmt, domainBridge.formatInr(breakdown.itemTotalPaise))
      else -> stringResource(R.string.builder_add_to_cart_fmt, domainBridge.formatInr(breakdown.itemTotalPaise))
    }

    if (stackControls) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          QuantityStepper(
            quantity = quantity,
            enabled = !isSubmitting && hasRequiredActiveOptions,
            onQuantityChange = onQuantityChange,
          )
          Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
          ) {
            Text(
              text = stringResource(R.string.builder_item_total_label),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = domainBridge.formatInr(breakdown.itemTotalPaise),
              style = PizzoraMonoTitle,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.testTag("builder_dock_total_price_text"),
            )
          }
        }
        if (quantity >= MAX_PIZZA_QTY) {
          Text(
            text = stringResource(R.string.builder_qty_max_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("builder_qty_max_note"),
          )
        }
        BuilderSubmitButton(
          label = ctaLabel,
          enabled = canSubmit,
          isSubmitting = isSubmitting,
          onClick = onSubmit,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          QuantityStepper(
            quantity = quantity,
            enabled = !isSubmitting && hasRequiredActiveOptions,
            onQuantityChange = onQuantityChange,
          )
          BuilderSubmitButton(
            label = ctaLabel,
            enabled = canSubmit,
            isSubmitting = isSubmitting,
            onClick = onSubmit,
            modifier = Modifier
              .weight(1f)
              .semantics { liveRegion = LiveRegionMode.Polite },
          )
        }
        if (quantity >= MAX_PIZZA_QTY) {
          Text(
            text = stringResource(R.string.builder_qty_max_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("builder_qty_max_note"),
          )
        }
      }
    }
  }
}

@Composable
private fun BuilderSubmitButton(
  label: String,
  enabled: Boolean,
  isSubmitting: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Button(
    onClick = onClick,
    enabled = enabled,
    colors = ButtonDefaults.buttonColors(
      containerColor = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
      disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
      disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    modifier = modifier
      .heightIn(min = 50.dp)
      .minimumInteractiveComponentSize()
      .testTag("add_to_cart_button"),
  ) {
    if (isSubmitting) {
      CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.width(8.dp))
    }
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
fun QuantityStepper(
  quantity: Int,
  enabled: Boolean,
  onQuantityChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val haptic = LocalHapticFeedback.current

  Surface(
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.surfaceVariant,
    tonalElevation = 0.dp,
    border = BorderStroke(1.dp, semantic.outlineInteractive),
    modifier = modifier,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(
        onClick = {
          if (quantity > MIN_PIZZA_QTY) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onQuantityChange(quantity - 1)
          }
        },
        enabled = enabled && quantity > MIN_PIZZA_QTY,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .testTag("customizer_qty_minus"),
      ) {
        Icon(
          imageVector = Icons.Filled.Remove,
          contentDescription = stringResource(R.string.builder_qty_decrease_cd),
        )
      }
      Text(
        text = quantity.toString(),
        style = PizzoraMonoTitle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
          .widthIn(min = 28.dp)
          .padding(horizontal = 4.dp)
          .semantics { liveRegion = LiveRegionMode.Polite }
          .testTag("customizer_qty_value"),
      )
      IconButton(
        onClick = {
          if (quantity < MAX_PIZZA_QTY) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onQuantityChange(quantity + 1)
          }
        },
        enabled = enabled && quantity < MAX_PIZZA_QTY,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .testTag("customizer_qty_plus"),
      ) {
        Icon(
          imageVector = Icons.Filled.Add,
          contentDescription = stringResource(R.string.builder_qty_increase_cd),
        )
      }
    }
  }
}

@Composable
fun PriceBreakdownDrawer(
  breakdown: PizzaBuilderBreakdown,
  isOfflineCache: Boolean,
  domainBridge: DomainBridge,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    tonalElevation = 0.dp,
    modifier = modifier,
  ) {
    PriceBreakdownContent(
      breakdown = breakdown,
      isOfflineCache = isOfflineCache,
      domainBridge = domainBridge,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
  }
}

@Composable
private fun PriceBreakdownReceiptCard(
  breakdown: PizzaBuilderBreakdown,
  isOfflineCache: Boolean,
  domainBridge: DomainBridge,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier
      .fillMaxWidth()
      .testTag("builder_breakdown_drawer"),
  ) {
    PriceBreakdownContent(
      breakdown = breakdown,
      isOfflineCache = isOfflineCache,
      domainBridge = domainBridge,
      modifier = Modifier.padding(14.dp),
    )
  }
}

@Composable
private fun PriceBreakdownContent(
  breakdown: PizzaBuilderBreakdown,
  isOfflineCache: Boolean,
  domainBridge: DomainBridge,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = stringResource(R.string.builder_breakdown_title),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurface,
    )

    val sizeLabel = if (breakdown.variant != null) {
      "${stringResource(R.string.builder_base_size_label)} (${sizeDisplayName(breakdown.variant.sizeCode)})"
    } else {
      stringResource(R.string.builder_base_size_label)
    }
    BreakdownLineRow(
      label = sizeLabel,
      value = domainBridge.formatInr(breakdown.baseSizePaise),
    )

    val crustLabel = if (breakdown.crust != null) {
      "${stringResource(R.string.builder_crust_label)} (${breakdown.crust.name})"
    } else {
      stringResource(R.string.builder_crust_label)
    }
    BreakdownLineRow(
      label = crustLabel,
      value = if (breakdown.crustExtraPaise == 0L && breakdown.crust != null) {
        stringResource(R.string.builder_included)
      } else {
        "+ ${domainBridge.formatInr(breakdown.crustExtraPaise)}"
      },
      valueColor = if (breakdown.crustExtraPaise == 0L && breakdown.crust != null) {
        MaterialTheme.colorScheme.secondary
      } else {
        MaterialTheme.colorScheme.onSurface
      },
    )

    if (breakdown.selectedToppings.isNotEmpty()) {
      BreakdownLineRow(
        label = "${stringResource(R.string.customizer_extra_toppings)} (${breakdown.selectedToppings.size})",
        value = "+ ${domainBridge.formatInr(breakdown.toppingsExtraPaise)}",
      )
      breakdown.selectedToppings.forEach { topping ->
        BreakdownLineRow(
          label = "  • ${topping.name}",
          value = "+ ${domainBridge.formatInr(topping.extraPricePaise)}",
          labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
          valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

    BreakdownLineRow(
      label = "${stringResource(R.string.builder_unit_price_label)} × ${breakdown.quantity}",
      value = "${domainBridge.formatInr(breakdown.unitPricePaise)} × ${breakdown.quantity}",
      labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
      valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .semantics { liveRegion = LiveRegionMode.Polite },
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.builder_item_total_label),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = domainBridge.formatInr(breakdown.itemTotalPaise),
        style = PizzoraMonoTitle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("builder_total_price_text"),
      )
    }

    Text(
      text = stringResource(R.string.builder_tax_footnote),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (isOfflineCache) {
      Text(
        text = stringResource(R.string.builder_offline_footnote),
        style = MaterialTheme.typography.bodySmall,
        color = LocalPizzoraSemanticColors.current.onWarningContainer,
      )
    }
  }
}

@Composable
private fun BreakdownLineRow(
  label: String,
  value: String,
  labelColor: Color = MaterialTheme.colorScheme.onSurface,
  valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = labelColor,
      modifier = Modifier.weight(1f),
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = value,
      style = PizzoraMonoBody,
      color = valueColor,
    )
  }
}

private fun sizeDisplayName(sizeCode: SizeCode): String =
  sizeCode.name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun BuilderCatalogLoadingGuard(
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(enabled = true, onBack = onDismiss)
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .semantics { isTraversalGroup = true }
      .focusGroup()
      .testTag("pizza_customizer_sheet"),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .heightIn(min = 60.dp)
        .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      IconButton(
        onClick = onDismiss,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .testTag("customizer_close_button"),
      ) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
      }
      Text(
        text = stringResource(R.string.builder_header_editing),
        style = MaterialTheme.typography.titleMedium,
      )
    }
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth(),
      contentAlignment = Alignment.Center,
    ) {
      CircularProgressIndicator()
    }
  }
}

@Composable
private fun BuilderDiscontinuedEditGuard(
  cartItem: CartItemWithToppings,
  onDismiss: () -> Unit,
  domainBridge: DomainBridge,
  modifier: Modifier = Modifier,
) {
  BackHandler(enabled = true, onBack = onDismiss)
  val savedItem = cartItem.item
  val sizeLabel = savedItem.sizeCode.lowercase().replaceFirstChar { it.uppercase() }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .semantics { isTraversalGroup = true }
      .focusGroup()
      .testTag("pizza_customizer_sheet"),
  ) {
    Surface(
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 0.dp,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .heightIn(min = 60.dp)
          .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        IconButton(
          onClick = onDismiss,
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("customizer_close_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.action_close),
          )
        }
        Text(
          text = savedItem.productName,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        BuilderEditModeTag()
      }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

    Column(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(16.dp)
        .testTag("builder_discontinued_guard"),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      PizzoraStatusBanner(
        message = stringResource(R.string.builder_discontinued_body),
        tone = PizzoraBannerTone.ERROR,
        modifier = Modifier.testTag("builder_unavailable_state"),
      )

      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("cart_item_preserved_summary"),
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = savedItem.productName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "$sizeLabel · ${savedItem.crustName} · Qty ${savedItem.quantity}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (cartItem.toppings.isNotEmpty()) {
            Text(
              text = "Toppings: ${cartItem.toppings.joinToString(", ") { it.toppingName }}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Text(
            text = domainBridge.formatInr(savedItem.unitPricePaise * savedItem.quantity),
            style = PizzoraMonoBody,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }

    PizzoraStickyActionDock(applyNavigationBarsPadding = true) {
      Button(
        onClick = onDismiss,
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 50.dp)
          .minimumInteractiveComponentSize()
          .testTag("builder_back_to_cart_button"),
      ) {
        Text(
          text = stringResource(R.string.builder_back_to_cart),
          style = MaterialTheme.typography.labelLarge,
        )
      }
    }
  }
}

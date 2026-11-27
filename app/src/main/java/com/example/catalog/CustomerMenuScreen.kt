package com.example.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.ui.components.PizzoraBannerTone
import com.example.ui.components.PizzoraDietaryIndicator
import com.example.ui.components.PizzoraEmptyStateCard
import com.example.ui.components.PizzoraMenuCardSkeleton
import com.example.ui.components.PizzoraStatusBanner
import com.example.ui.theme.LocalPizzoraSemanticColors
import com.example.ui.theme.PizzoraMonoTitle
import com.example.ui.theme.WarmParchmentText
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.Money
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.ToppingDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerMenuScreen(
  uiState: CustomerMenuUiState,
  onSearchQueryChange: (String) -> Unit,
  onCategorySelected: (String?) -> Unit,
  onVegOnlyToggle: () -> Unit,
  onClearFilters: () -> Unit,
  onRefreshMenu: () -> Unit,
  onOpenCustomizer: (ProductDto) -> Unit,
  onDismissCustomizer: () -> Unit,
  onAddCustomizedToCart: (ProductDto, ProductVariantDto, CrustOptionDto, List<ToppingDto>, Int) -> Unit,
  modifier: Modifier = Modifier,
  useStandaloneBuilderHost: Boolean = true,
  onConfirmAddWithModifiers: ((
    input: CartItemInputDto,
    onResult: (success: Boolean, errorMessage: String?) -> Unit,
  ) -> Unit)? = null,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val focusManager = LocalFocusManager.current
  val productFocusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
  var lastOpenedProductId by rememberSaveable { mutableStateOf<String?>(null) }
  var previouslyOpenProductId by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(uiState.customizingProduct?.id) {
    val activeId = uiState.customizingProduct?.id
    val previousId = previouslyOpenProductId
    if (activeId != null) {
      previouslyOpenProductId = activeId
      lastOpenedProductId = activeId
    } else if (previousId != null) {
      previouslyOpenProductId = null
      withFrameNanos { }
      val targetId = lastOpenedProductId ?: previousId
      runCatching { productFocusRequesters[targetId]?.requestFocus() }
    }
  }

  val hasActiveFilters = uiState.searchQuery.isNotBlank() ||
    uiState.selectedCategoryId != null ||
    uiState.vegOnly

  BoxWithConstraints(modifier = modifier.fillMaxSize().testTag("customer_menu_screen")) {
    val contentWidth = maxWidth
    val useTwoColumns = contentWidth >= 640.dp
    val isLandscapeCompactHeight = maxHeight < 480.dp
    val isCompactPhone = contentWidth < 360.dp
    val thumbnailSize = if (isCompactPhone) 88.dp else 104.dp
    val maxListWidth = if (useTwoColumns) 1080.dp else 600.dp

    PullToRefreshBox(
      isRefreshing = uiState.isRefreshing,
      onRefresh = onRefreshMenu,
      modifier = Modifier.fillMaxSize(),
    ) {
      val stackVertically = LocalDensity.current.fontScale >= 1.6f && contentWidth < 380.dp
      Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .widthIn(max = maxListWidth)
            .testTag("menu_lazy_column"),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (uiState.isFromOfflineCache) {
            item(key = "offline_banner") {
              PizzoraStatusBanner(
                message = stringResource(R.string.menu_offline_banner),
                tone = PizzoraBannerTone.WARNING,
                icon = Icons.Filled.CloudOff,
                actionLabel = stringResource(R.string.action_retry),
                onAction = onRefreshMenu,
                modifier = Modifier.testTag("menu_offline_banner"),
              )
            }
          }

          if (!uiState.isAcceptingOrders) {
            item(key = "store_closed_banner") {
              PizzoraStatusBanner(
                message = stringResource(R.string.menu_store_closed_banner),
                tone = PizzoraBannerTone.WARNING,
                icon = Icons.Filled.Storefront,
                modifier = Modifier.testTag("store_closed_banner"),
              )
            }
          }

          if (uiState.searchQuery.isBlank()) {
            item(key = "hero_header") {
              HeroMenuBanner(
                storeName = uiState.storeName,
                gstBps = uiState.gstBps,
                isAcceptingOrders = uiState.isAcceptingOrders,
                heroHeight = if (isLandscapeCompactHeight) 120.dp else 168.dp,
              )
            }
          }

          item(key = "search_field") {
            OutlinedTextField(
              value = uiState.searchQuery,
              onValueChange = onSearchQueryChange,
              placeholder = { Text(stringResource(R.string.menu_search_hint)) },
              leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
              trailingIcon = {
                if (uiState.searchQuery.isNotBlank()) {
                  IconButton(
                    onClick = { onSearchQueryChange("") },
                    modifier = Modifier
                      .minimumInteractiveComponentSize()
                      .testTag("menu_search_clear_button"),
                  ) {
                    Icon(
                      imageVector = Icons.Filled.Close,
                      contentDescription = stringResource(R.string.menu_clear_search_cd),
                    )
                  }
                }
              },
              singleLine = true,
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
              keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
              shape = MaterialTheme.shapes.medium,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("menu_search_input"),
            )
          }

          item(key = "filter_chip_rail") {
            val chipColors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = semantic.selectedSurface,
              selectedLabelColor = MaterialTheme.colorScheme.onSurface,
              selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
            )
            val chipBorder = FilterChipDefaults.filterChipBorder(
              enabled = true,
              selected = false,
              borderColor = semantic.outlineInteractive,
              selectedBorderColor = MaterialTheme.colorScheme.primary,
              selectedBorderWidth = 1.5.dp,
            )
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              FilterChip(
                selected = uiState.vegOnly,
                onClick = onVegOnlyToggle,
                label = { Text(stringResource(R.string.menu_veg_only)) },
                leadingIcon = {
                  Icon(
                    Icons.Filled.Eco,
                    contentDescription = null,
                    tint = semantic.vegBadgeGreen,
                    modifier = Modifier.size(18.dp),
                  )
                },
                colors = chipColors,
                border = FilterChipDefaults.filterChipBorder(
                  enabled = true,
                  selected = uiState.vegOnly,
                  borderColor = semantic.outlineInteractive,
                  selectedBorderColor = MaterialTheme.colorScheme.primary,
                  selectedBorderWidth = 1.5.dp,
                ),
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("veg_only_filter_chip"),
              )
              if (uiState.categories.isNotEmpty()) {
                FilterChip(
                  selected = uiState.selectedCategoryId == null,
                  onClick = { onCategorySelected(null) },
                  label = { Text(stringResource(R.string.menu_all_categories)) },
                  colors = chipColors,
                  border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = uiState.selectedCategoryId == null,
                    borderColor = semantic.outlineInteractive,
                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                    selectedBorderWidth = 1.5.dp,
                  ),
                  modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("category_chip_all"),
                )
                uiState.categories.forEach { category ->
                  val isSelected = uiState.selectedCategoryId == category.id
                  FilterChip(
                    selected = isSelected,
                    onClick = { onCategorySelected(category.id) },
                    label = { Text(category.name) },
                    colors = chipColors,
                    border = if (isSelected) {
                      FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = true,
                        borderColor = semantic.outlineInteractive,
                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                        selectedBorderWidth = 1.5.dp,
                      )
                    } else {
                      chipBorder
                    },
                    modifier = Modifier
                      .minimumInteractiveComponentSize()
                      .testTag("category_chip_${category.id}"),
                  )
                }
              }
              IconButton(
                onClick = onRefreshMenu,
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("menu_refresh_button"),
              ) {
                Icon(
                  imageVector = Icons.Filled.Refresh,
                  contentDescription = stringResource(R.string.action_refresh),
                )
              }
            }
          }

          if (hasActiveFilters && !uiState.isLoading) {
            item(key = "active_filter_summary") {
              val count = uiState.filteredProducts.size
              val noun = if (count == 1) "pizza" else "pizzas"
              val summaryText = if (uiState.vegOnly) {
                "Showing $count Veg $noun"
              } else {
                "Showing $count $noun"
              }
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("menu_active_filter_summary"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = summaryText,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                  onClick = onClearFilters,
                  modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("menu_reset_filters_button"),
                ) {
                  Text(stringResource(R.string.menu_reset_filters))
                }
              }
            }
          }

          when {
            uiState.isLoading -> {
              items(4, key = { "skeleton_$it" }) {
                PizzoraMenuCardSkeleton(thumbnailSize = thumbnailSize)
              }
            }
            uiState.errorMessage != null && uiState.allProducts.isEmpty() -> {
              item(key = "error_state") {
                PizzoraEmptyStateCard(
                  title = stringResource(R.string.menu_error_title),
                  subtitle = uiState.errorMessage,
                  actionLabel = stringResource(R.string.action_retry),
                  onAction = onRefreshMenu,
                  modifier = Modifier.testTag("menu_error_state"),
                )
              }
            }
            uiState.filteredProducts.isEmpty() -> {
              item(key = "empty_state") {
                PizzoraEmptyStateCard(
                  title = stringResource(R.string.menu_empty_title),
                  subtitle = stringResource(R.string.menu_empty_subtitle),
                  actionLabel = stringResource(R.string.menu_clear_filters),
                  onAction = onClearFilters,
                  modifier = Modifier.testTag("menu_empty_state"),
                )
              }
            }
            useTwoColumns -> {
              val productRows = uiState.filteredProducts.chunked(2)
              items(productRows, key = { row -> row.joinToString("_") { it.id } }) { rowProducts ->
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  rowProducts.forEach { product ->
                    val cardRequester = productFocusRequesters.getOrPut(product.id) { FocusRequester() }
                    ProductMenuCard(
                      product = product,
                      isAcceptingOrders = uiState.isAcceptingOrders,
                      thumbnailSize = thumbnailSize,
                      stackVertically = false,
                      cardFocusRequester = cardRequester,
                      onCustomizeClick = {
                        lastOpenedProductId = product.id
                        onOpenCustomizer(product)
                      },
                      modifier = Modifier.weight(1f),
                    )
                  }
                  if (rowProducts.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                  }
                }
              }
            }
            else -> {
              items(uiState.filteredProducts, key = { it.id }) { product ->
                val cardRequester = productFocusRequesters.getOrPut(product.id) { FocusRequester() }
                ProductMenuCard(
                  product = product,
                  isAcceptingOrders = uiState.isAcceptingOrders,
                  thumbnailSize = thumbnailSize,
                  stackVertically = stackVertically,
                  cardFocusRequester = cardRequester,
                  onCustomizeClick = {
                    lastOpenedProductId = product.id
                    onOpenCustomizer(product)
                  },
                )
              }
            }
          }
        }
      }
    }

    if (useStandaloneBuilderHost) {
      val activeProduct = uiState.customizingProduct
      AnimatedVisibility(visible = activeProduct != null) {
        if (activeProduct != null) {
          PizzaBuilderScreen(
            product = activeProduct,
            crusts = uiState.crusts,
            toppings = uiState.toppings,
            isAcceptingOrders = uiState.isAcceptingOrders,
            isFromOfflineCache = uiState.isFromOfflineCache,
            onDismiss = onDismissCustomizer,
            onConfirmAdd = { variant, crust, selectedToppings, qty, onResult ->
              onAddCustomizedToCart(activeProduct, variant, crust, selectedToppings, qty)
              onResult(true, null)
            },
            // Legacy-only callers keep the offline-first repository path; customer flows pass
            // an extended handler so the exact builder DTO (with modifier IDs) reaches the cart.
            onConfirmAddWithModifiers = onConfirmAddWithModifiers,
          )
        }
      }
    }
  }
}

@Composable
private fun HeroMenuBanner(
  storeName: String,
  gstBps: Int,
  isAcceptingOrders: Boolean,
  heroHeight: Dp,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val statusBg = if (isAcceptingOrders) {
    MaterialTheme.colorScheme.secondaryContainer
  } else {
    semantic.warningContainer
  }
  val statusFg = if (isAcceptingOrders) {
    MaterialTheme.colorScheme.onSecondaryContainer
  } else {
    semantic.onWarningContainer
  }

  Card(
    shape = MaterialTheme.shapes.large,
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    modifier = Modifier
      .fillMaxWidth()
      .height(heroHeight),
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      Image(
        painter = painterResource(id = R.drawable.img_pizzora_hero_oven_1794579613265),
        contentDescription = stringResource(R.string.hero_oven_cd),
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              colors = listOf(Color(0x40121110), Color(0xCC121110)),
            ),
          ),
      )
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        Surface(
          color = statusBg,
          contentColor = statusFg,
          tonalElevation = 0.dp,
          shape = MaterialTheme.shapes.small,
        ) {
          Text(
            text = if (isAcceptingOrders) "Oven Hot · Accepting Orders" else "Kitchen Paused",
            style = MaterialTheme.typography.labelSmall,
            color = statusFg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
        Column {
          Text(
            text = storeName,
            style = MaterialTheme.typography.headlineSmall,
            color = WarmParchmentText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "Stone-baked Neapolitan pies · GST ${(gstBps / 100.0).let { if (it % 1.0 == 0.0) "${it.toInt()}%" else "$it%" }}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFE5D8CA),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
private fun ProductMenuCard(
  product: ProductDto,
  isAcceptingOrders: Boolean,
  thumbnailSize: Dp,
  stackVertically: Boolean,
  onCustomizeClick: () -> Unit,
  modifier: Modifier = Modifier,
  cardFocusRequester: FocusRequester? = null,
) {
  val activeVariants = product.variants.filter { it.isActive }
  val minPricePaise = (activeVariants.ifEmpty { product.variants }).minOfOrNull { it.pricePaise } ?: 0L
  val imageUrl = product.images.firstOrNull()?.publicUrl
  val sizeCount = activeVariants.size

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    shape = MaterialTheme.shapes.medium,
    modifier = modifier
      .fillMaxWidth()
      .semantics(mergeDescendants = false) {}
      .let { base -> if (cardFocusRequester != null) base.focusRequester(cardFocusRequester) else base }
      .clickable(enabled = isAcceptingOrders, onClick = onCustomizeClick)
      .testTag("product_card_${product.id}"),
  ) {
    if (stackVertically) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        ProductThumbnailBox(
          imageUrl = imageUrl,
          contentDescription = product.name,
          modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        )
        ProductCardDetails(
          product = product,
          minPricePaise = minPricePaise,
          sizeCount = sizeCount,
          isAcceptingOrders = isAcceptingOrders,
          onCustomizeClick = onCustomizeClick,
        )
      }
    } else {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          ProductCardDetails(
            product = product,
            minPricePaise = minPricePaise,
            sizeCount = sizeCount,
            isAcceptingOrders = isAcceptingOrders,
            onCustomizeClick = onCustomizeClick,
          )
        }
        ProductThumbnailBox(
          imageUrl = imageUrl,
          contentDescription = product.name,
          modifier = Modifier.size(thumbnailSize),
        )
      }
    }
  }
}

@Composable
private fun ProductCardDetails(
  product: ProductDto,
  minPricePaise: Long,
  sizeCount: Int,
  isAcceptingOrders: Boolean,
  onCustomizeClick: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    PizzoraDietaryIndicator(isVeg = product.isVeg)
    Text(
      text = if (sizeCount > 1) "${product.categoryName} · $sizeCount sizes" else product.categoryName,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
  Spacer(modifier = Modifier.height(6.dp))
  Text(
    text = product.name,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.onSurface,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
  )
  if (product.description.isNotBlank()) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = product.description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
  Spacer(modifier = Modifier.height(10.dp))
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(modifier = Modifier.weight(1f, fill = false)) {
      Text(
        text = "From ${Money.formatInr(minPricePaise)}",
        style = PizzoraMonoTitle,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
      )
    }
    Spacer(modifier = Modifier.width(8.dp))
    FilledTonalButton(
      onClick = onCustomizeClick,
      enabled = isAcceptingOrders,
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
      modifier = Modifier
        .heightIn(min = 40.dp)
        .minimumInteractiveComponentSize()
        .testTag("customize_product_button_${product.id}"),
    ) {
      if (isAcceptingOrders) {
        Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = stringResource(R.string.action_customize),
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
        )
      } else {
        Text(
          text = stringResource(R.string.menu_action_paused),
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun ProductThumbnailBox(
  imageUrl: String?,
  contentDescription: String,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Filled.LocalPizza,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
      modifier = Modifier.size(28.dp),
    )
    if (!imageUrl.isNullOrBlank()) {
      AsyncImage(
        model = imageUrl,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

@Composable
fun VegIndicator(
  isVeg: Boolean,
  modifier: Modifier = Modifier,
) {
  PizzoraDietaryIndicator(isVeg = isVeg, modifier = modifier)
}

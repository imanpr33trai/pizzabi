package com.example.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.ui.theme.LocalPizzoraSemanticColors
import com.example.ui.theme.PizzoraMonoBody
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.Money
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.ToppingDto

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
) {
  Box(modifier = modifier.fillMaxSize().testTag("customer_menu_screen")) {
    Column(
      modifier = Modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (uiState.isRefreshing) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }

      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .widthIn(max = 720.dp)
          .testTag("menu_lazy_column"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item(key = "hero_header") {
          HeroMenuBanner(
            storeName = uiState.storeName,
            gstBps = uiState.gstBps,
            isAcceptingOrders = uiState.isAcceptingOrders,
          )
        }

        if (uiState.isFromOfflineCache) {
          item(key = "offline_banner") {
            StatusBanner(
              icon = Icons.Filled.CloudOff,
              text = stringResource(R.string.menu_offline_banner),
              containerColor = MaterialTheme.colorScheme.tertiaryContainer,
              contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
              actionLabel = stringResource(R.string.action_retry),
              onAction = onRefreshMenu,
              modifier = Modifier.testTag("menu_offline_banner"),
            )
          }
        }

        if (!uiState.isAcceptingOrders) {
          item(key = "store_closed_banner") {
            StatusBanner(
              icon = Icons.Filled.Storefront,
              text = stringResource(R.string.menu_store_closed_banner),
              containerColor = MaterialTheme.colorScheme.errorContainer,
              contentColor = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.testTag("store_closed_banner"),
            )
          }
        }

        item(key = "search_and_veg_filter") {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
              value = uiState.searchQuery,
              onValueChange = onSearchQueryChange,
              placeholder = { Text(stringResource(R.string.menu_search_hint)) },
              leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
              singleLine = true,
              modifier = Modifier
                .weight(1f)
                .testTag("menu_search_input"),
            )
            FilterChip(
              selected = uiState.vegOnly,
              onClick = onVegOnlyToggle,
              label = { Text(stringResource(R.string.menu_veg_only)) },
              leadingIcon = {
                Icon(
                  Icons.Filled.Eco,
                  contentDescription = null,
                  tint = LocalPizzoraSemanticColors.current.vegBadgeGreen,
                )
              },
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("veg_only_filter_chip"),
            )
            IconButton(
              onClick = onRefreshMenu,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("menu_refresh_button"),
            ) {
              Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
          }
        }

        if (uiState.categories.isNotEmpty()) {
          item(key = "category_chips") {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              FilterChip(
                selected = uiState.selectedCategoryId == null,
                onClick = { onCategorySelected(null) },
                label = { Text(stringResource(R.string.menu_all_categories)) },
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("category_chip_all"),
              )
              uiState.categories.forEach { category ->
                FilterChip(
                  selected = uiState.selectedCategoryId == category.id,
                  onClick = { onCategorySelected(category.id) },
                  label = { Text(category.name) },
                  modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("category_chip_${category.id}"),
                )
              }
            }
          }
        }

        when {
          uiState.isLoading -> {
            items(3, key = { "skeleton_$it" }) {
              MenuSkeletonCard()
            }
          }
          uiState.errorMessage != null && uiState.allProducts.isEmpty() -> {
            item(key = "error_state") {
              EmptyMenuState(
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
              EmptyMenuState(
                title = stringResource(R.string.menu_empty_title),
                subtitle = stringResource(R.string.menu_empty_subtitle),
                actionLabel = stringResource(R.string.menu_clear_filters),
                onAction = onClearFilters,
                modifier = Modifier.testTag("menu_empty_state"),
              )
            }
          }
          else -> {
            items(uiState.filteredProducts, key = { it.id }) { product ->
              ProductMenuCard(
                product = product,
                onCustomizeClick = { onOpenCustomizer(product) },
              )
            }
          }
        }
      }
    }

    val activeProduct = uiState.customizingProduct
    AnimatedVisibility(visible = activeProduct != null) {
      if (activeProduct != null) {
        PizzaCustomizerSheet(
          product = activeProduct,
          crusts = uiState.crusts,
          toppings = uiState.toppings,
          onDismiss = onDismissCustomizer,
          onConfirmAdd = { variant, crust, selectedToppings, qty ->
            onAddCustomizedToCart(activeProduct, variant, crust, selectedToppings, qty)
            onDismissCustomizer()
          },
        )
      }
    }
  }
}

@Composable
private fun HeroMenuBanner(
  storeName: String,
  gstBps: Int,
  isAcceptingOrders: Boolean,
) {
  Card(
    shape = MaterialTheme.shapes.large,
    modifier = Modifier.fillMaxWidth().height(136.dp),
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
              colors = listOf(Color(0x55121110), Color(0xCC1E1916)),
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
          color = if (isAcceptingOrders) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
          shape = CircleShape,
        ) {
          Text(
            text = if (isAcceptingOrders) "Oven Hot • Accepting Orders" else "Kitchen Paused",
            style = MaterialTheme.typography.labelMedium,
            color = if (isAcceptingOrders) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
        Column {
          Text(
            text = storeName,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
          )
          Text(
            text = "Stone-baked Neapolitan pies • GST ${(gstBps / 100.0).let { if (it % 1.0 == 0.0) "${it.toInt()}%" else "$it%" }}",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFEFE6DC),
          )
        }
      }
    }
  }
}

@Composable
private fun ProductMenuCard(
  product: ProductDto,
  onCustomizeClick: () -> Unit,
) {
  val minPricePaise = product.variants.minOfOrNull { it.pricePaise } ?: 0L
  val imageUrl = product.images.firstOrNull()?.publicUrl

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onCustomizeClick)
      .testTag("product_card_${product.id}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          VegIndicator(isVeg = product.isVeg)
          Text(
            text = product.categoryName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = product.name,
          style = MaterialTheme.typography.headlineSmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (product.description.isNotBlank()) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = product.description,
            style = MaterialTheme.typography.bodyMedium,
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
          Column {
            Text(
              text = "Starts at",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = Money.formatInr(minPricePaise),
              style = PizzoraMonoBody,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          FilledTonalButton(
            onClick = onCustomizeClick,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("customize_product_button_${product.id}"),
          ) {
            Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.action_customize))
          }
        }
      }

      Box(
        modifier = Modifier
          .size(96.dp)
          .clip(RoundedCornerShape(14.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Filled.LocalPizza,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
          modifier = Modifier.size(38.dp),
        )
        if (!imageUrl.isNullOrBlank()) {
          AsyncImage(
            model = imageUrl,
            contentDescription = product.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }
  }
}

@Composable
fun VegIndicator(
  isVeg: Boolean,
  modifier: Modifier = Modifier,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val indicatorColor = if (isVeg) semantic.vegBadgeGreen else semantic.nonVegBadgeTerracotta
  Box(
    modifier = modifier
      .size(16.dp)
      .border(1.5.dp, indicatorColor, RoundedCornerShape(3.dp)),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .size(8.dp)
        .clip(CircleShape)
        .background(indicatorColor),
    )
  }
}

@Composable
private fun StatusBanner(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  text: String,
  containerColor: Color,
  contentColor: Color,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  Surface(
    color = containerColor,
    shape = MaterialTheme.shapes.medium,
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(icon, contentDescription = null, tint = contentColor)
      Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
        modifier = Modifier.weight(1f),
      )
      if (actionLabel != null && onAction != null) {
        TextButton(
          onClick = onAction,
          modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
          Text(actionLabel, color = contentColor)
        }
      }
    }
  }
}

@Composable
private fun MenuSkeletonCard() {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier.fillMaxWidth().height(132.dp),
  ) {}
}

@Composable
private fun EmptyMenuState(
  title: String,
  subtitle: String,
  actionLabel: String,
  onAction: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.LocalPizza,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(40.dp),
      )
      Text(title, style = MaterialTheme.typography.titleLarge)
      Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(modifier = Modifier.height(6.dp))
      Button(onClick = onAction, modifier = Modifier.minimumInteractiveComponentSize()) {
        Text(actionLabel)
      }
    }
  }
}

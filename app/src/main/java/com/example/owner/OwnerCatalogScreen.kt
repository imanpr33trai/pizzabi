package com.example.owner

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.core.media.ProductImagePickerButton
import com.example.ui.theme.LocalPizzoraSemanticColors
import com.example.ui.theme.PizzoraMonoBody
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.Money
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.RecipeBindingDto
import com.pizzora.contracts.RecipeIngredientLineRequest
import com.pizzora.contracts.RecipeTargetType

@Composable
fun OwnerCatalogScreen(
  uiState: OwnerUiState,
  onSelectSection: (OwnerCatalogSection) -> Unit,
  onRefresh: () -> Unit,
  onCreateCategory: (name: String, sortOrder: Int) -> Unit,
  onToggleCategoryActive: (CategoryDto) -> Unit,
  onCreateProduct: (
    categoryId: String,
    name: String,
    description: String,
    isVeg: Boolean,
    regularPricePaise: Long,
    mediumPricePaise: Long?,
    largePricePaise: Long?,
  ) -> Unit,
  onToggleProductActive: (ProductDto) -> Unit,
  onUploadProductPhoto: (context: android.content.Context, productId: String, uri: Uri) -> Unit,
  onDeleteProductPhoto: (productId: String, imageId: String) -> Unit,
  onSaveRecipe: (RecipeTargetType, String, List<RecipeIngredientLineRequest>) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var showCreateCategoryDialog by remember { mutableStateOf(false) }
  var showCreateProductDialog by remember { mutableStateOf(false) }
  var editingRecipeBinding by remember { mutableStateOf<RecipeBindingDto?>(null) }

  Box(modifier = modifier.fillMaxSize()) {
  Column(modifier = Modifier.fillMaxSize().testTag("owner_catalog_screen")) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OwnerCatalogSection.entries.forEach { section ->
        FilterChip(
          selected = uiState.catalogSection == section,
          onClick = { onSelectSection(section) },
          label = { Text(section.label) },
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("owner_catalog_tab_${section.name.lowercase()}"),
        )
      }
    }

    if (uiState.isLoadingCatalog && uiState.products.isEmpty() && uiState.categories.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (uiState.errorMessage != null) {
          item {
            OwnerErrorBanner(message = uiState.errorMessage, onRetry = onRefresh)
          }
        }

        when (uiState.catalogSection) {
          OwnerCatalogSection.PRODUCTS -> {
            item {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text("Menu Items (${uiState.products.size})", style = MaterialTheme.typography.titleMedium)
                Button(
                  onClick = { showCreateProductDialog = true },
                  enabled = uiState.categories.isNotEmpty(),
                  modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_add_product_button"),
                ) {
                  Icon(Icons.Filled.Add, contentDescription = null)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("New Pizza")
                }
              }
            }

            if (uiState.products.isEmpty()) {
              item {
                OwnerEmptyCard(
                  title = "No products in catalog",
                  subtitle = "Create a category first, then add wood-fired pizzas and upload photos.",
                  tag = "owner_products_empty",
                )
              }
            } else {
              items(uiState.products, key = { it.id }) { product ->
                OwnerProductCard(
                  product = product,
                  isUploading = uiState.uploadingProductId == product.id,
                  isSubmitting = uiState.isSubmitting,
                  onToggleActive = { onToggleProductActive(product) },
                  onPhotoSelected = { uri -> onUploadProductPhoto(context, product.id, uri) },
                  onDeleteImage = { imageId -> onDeleteProductPhoto(product.id, imageId) },
                )
              }
            }
          }

          OwnerCatalogSection.CATEGORIES -> {
            item {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text("Categories (${uiState.categories.size})", style = MaterialTheme.typography.titleMedium)
                Button(
                  onClick = { showCreateCategoryDialog = true },
                  modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_add_category_button"),
                ) {
                  Icon(Icons.Filled.Add, contentDescription = null)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Add Category")
                }
              }
            }

            if (uiState.categories.isEmpty()) {
              item {
                OwnerEmptyCard(
                  title = "No categories yet",
                  subtitle = "Add categories such as Wood-Fired Classics or Signature Pies.",
                  tag = "owner_categories_empty",
                )
              }
            } else {
              items(uiState.categories, key = { it.id }) { cat ->
                Card(
                  colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                  modifier = Modifier.fillMaxWidth().testTag("owner_category_${cat.id}"),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column(modifier = Modifier.weight(1f)) {
                      Text(cat.name, style = MaterialTheme.typography.titleMedium)
                      Text(
                        text = "Display Order: ${cat.sortOrder}",
                        style = PizzoraMonoBody,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                    Switch(
                      checked = cat.isActive,
                      enabled = !uiState.isSubmitting,
                      onCheckedChange = { onToggleCategoryActive(cat) },
                      modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_category_switch_${cat.id}"),
                    )
                  }
                }
              }
            }
          }

          OwnerCatalogSection.RECIPES -> {
            val allBindings = uiState.recipes.variants + uiState.recipes.crusts + uiState.recipes.toppings
            item {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Relational Ingredient Recipes", style = MaterialTheme.typography.titleMedium)
              }
            }

            if (allBindings.isEmpty()) {
              item {
                OwnerEmptyCard(
                  title = "No recipe targets found",
                  subtitle = "Add variants, crusts, or toppings to bind ingredient quantities.",
                  tag = "owner_recipes_empty",
                )
              }
            } else {
              items(allBindings, key = { "${it.targetType.name}:${it.targetId}" }) { binding ->
                RecipeBindingCard(
                  binding = binding,
                  onEdit = { editingRecipeBinding = binding },
                )
              }
            }
          }
        }
      }
    }
  }

  if (showCreateCategoryDialog) {
    CreateCategoryDialog(
      onDismiss = { showCreateCategoryDialog = false },
      onCreate = { name, sortOrder ->
        onCreateCategory(name, sortOrder)
        showCreateCategoryDialog = false
      },
    )
  }

  if (showCreateProductDialog) {
    CreateProductDialog(
      categories = uiState.categories,
      onDismiss = { showCreateProductDialog = false },
      onCreate = { catId, name, desc, isVeg, reg, med, lrg ->
        onCreateProduct(catId, name, desc, isVeg, reg, med, lrg)
        showCreateProductDialog = false
      },
    )
  }

  val targetBinding = editingRecipeBinding
  if (targetBinding != null) {
    EditRecipeDialog(
      binding = targetBinding,
      ingredients = uiState.inventory,
      onDismiss = { editingRecipeBinding = null },
      onSave = { lines ->
        onSaveRecipe(targetBinding.targetType, targetBinding.targetId, lines)
        editingRecipeBinding = null
      },
    )
  }
  }
}

@Composable
private fun OwnerProductCard(
  product: ProductDto,
  isUploading: Boolean,
  isSubmitting: Boolean,
  onToggleActive: () -> Unit,
  onPhotoSelected: (Uri) -> Unit,
  onDeleteImage: (String) -> Unit,
) {
  val semantic = LocalPizzoraSemanticColors.current
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    modifier = Modifier.fillMaxWidth().testTag("owner_product_card_${product.id}"),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(product.name, style = MaterialTheme.typography.titleMedium)
          Text(
            text = "${product.categoryName} • ${if (product.isVeg) "VEG" else "NON-VEG"}",
            style = MaterialTheme.typography.labelMedium,
            color = if (product.isVeg) semantic.vegBadgeGreen else semantic.nonVegBadgeTerracotta,
          )
        }
        Switch(
          checked = product.isActive,
          enabled = !isSubmitting,
          onCheckedChange = { onToggleActive() },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("owner_product_active_${product.id}"),
        )
      }

      if (product.description.isNotBlank()) {
        Text(
          text = product.description,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        product.variants.forEach { variant ->
          Text(
            text = "${variant.sizeCode.name.take(3)}: ${Money.formatInr(variant.pricePaise)}",
            style = PizzoraMonoBody,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      if (product.images.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(product.images, key = { it.id }) { img ->
            Box(modifier = Modifier.size(84.dp)) {
              AsyncImage(
                model = img.publicUrl,
                contentDescription = "${product.name} photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.small),
              )
              IconButton(
                onClick = { onDeleteImage(img.id) },
                modifier = Modifier.align(Alignment.TopEnd).minimumInteractiveComponentSize(),
              ) {
                Icon(
                  imageVector = Icons.Filled.Delete,
                  contentDescription = "Delete product photo",
                  tint = MaterialTheme.colorScheme.error,
                )
              }
            }
          }
        }
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        ProductImagePickerButton(
          onImageSelected = onPhotoSelected,
          enabled = !isUploading,
          label = if (isUploading) "Uploading..." else "Upload Photo",
        )
        if (isUploading) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
      }
    }
  }
}

@Composable
private fun RecipeBindingCard(
  binding: RecipeBindingDto,
  onEdit: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.fillMaxWidth().testTag("recipe_card_${binding.targetId}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${binding.targetType.name} • ${binding.targetLabel}", style = MaterialTheme.typography.titleMedium)
        val summary = if (binding.ingredients.isEmpty()) {
          "No ingredients linked yet"
        } else {
          binding.ingredients.joinToString(" • ") { "${it.ingredientName}: ${it.quantity} ${it.unit.name.lowercase()}" }
        }
        Text(
          text = summary,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      OutlinedButton(
        onClick = onEdit,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("edit_recipe_${binding.targetId}"),
      ) {
        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Edit")
      }
    }
  }
}

@Composable
internal fun OwnerModalOverlay(
  title: String,
  onDismiss: () -> Unit,
  confirmButton: @Composable () -> Unit,
  dismissButton: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  BackHandler(onBack = onDismiss)
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.5f))
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onDismiss,
      ),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
      modifier = Modifier
        .padding(20.dp)
        .fillMaxWidth()
        .widthIn(max = 520.dp)
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = {},
        ),
    ) {
      Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        content()
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (dismissButton != null) {
            dismissButton()
            Spacer(modifier = Modifier.width(8.dp))
          }
          confirmButton()
        }
      }
    }
  }
}

@Composable
private fun CreateCategoryDialog(
  onDismiss: () -> Unit,
  onCreate: (name: String, sortOrder: Int) -> Unit,
) {
  var name by rememberSaveable { mutableStateOf("") }
  var sortOrder by rememberSaveable { mutableStateOf("1") }

  OwnerModalOverlay(
    title = "New Menu Category",
    onDismiss = onDismiss,
    confirmButton = {
      Button(
        onClick = { onCreate(name, sortOrder.toIntOrNull() ?: 1) },
        modifier = Modifier.testTag("category_submit_button"),
      ) {
        Text("Create")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Category Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("category_name_input"),
      )
      OutlinedTextField(
        value = sortOrder,
        onValueChange = { sortOrder = it.filter(Char::isDigit) },
        label = { Text("Sort Order") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("category_sort_input"),
      )
    }
  }
}

@Composable
private fun CreateProductDialog(
  categories: List<CategoryDto>,
  onDismiss: () -> Unit,
  onCreate: (
    categoryId: String,
    name: String,
    description: String,
    isVeg: Boolean,
    regularPricePaise: Long,
    mediumPricePaise: Long?,
    largePricePaise: Long?,
  ) -> Unit,
) {
  var selectedCatId by remember(categories) { mutableStateOf(categories.firstOrNull()?.id.orEmpty()) }
  var name by remember { mutableStateOf("") }
  var description by remember { mutableStateOf("") }
  var isVeg by remember { mutableStateOf(true) }
  var regRupees by remember { mutableStateOf("299") }
  var medRupees by remember { mutableStateOf("449") }
  var lrgRupees by remember { mutableStateOf("599") }

  OwnerModalOverlay(
    title = "Add Pizza to Catalog",
    onDismiss = onDismiss,
    confirmButton = {
      Button(
        onClick = {
          onCreate(
            selectedCatId,
            name,
            description,
            isVeg,
            (regRupees.toLongOrNull() ?: 0L) * 100L,
            medRupees.toLongOrNull()?.let { it * 100L },
            lrgRupees.toLongOrNull()?.let { it * 100L },
          )
        },
        modifier = Modifier.testTag("product_submit_button"),
      ) {
        Text("Create")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 360.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("Category", style = MaterialTheme.typography.labelLarge)
      categories.forEach { cat ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { selectedCatId = cat.id },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(
            selected = selectedCatId == cat.id,
            onClick = { selectedCatId = cat.id },
          )
          Text(cat.name, style = MaterialTheme.typography.bodyMedium)
        }
      }
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Pizza Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("product_name_input"),
      )
      OutlinedTextField(
        value = description,
        onValueChange = { description = it },
        label = { Text("Description") },
        modifier = Modifier.fillMaxWidth().testTag("product_desc_input"),
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = isVeg, onCheckedChange = { isVeg = it })
        Text("Vegetarian")
      }
      OutlinedTextField(
        value = regRupees,
        onValueChange = { regRupees = it.filter(Char::isDigit) },
        label = { Text("Regular Price (₹)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("product_reg_price_input"),
      )
      OutlinedTextField(
        value = medRupees,
        onValueChange = { medRupees = it.filter(Char::isDigit) },
        label = { Text("Medium Price (₹)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("product_med_price_input"),
      )
      OutlinedTextField(
        value = lrgRupees,
        onValueChange = { lrgRupees = it.filter(Char::isDigit) },
        label = { Text("Large Price (₹)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("product_lrg_price_input"),
      )
    }
  }
}

@Composable
private fun EditRecipeDialog(
  binding: RecipeBindingDto,
  ingredients: List<InventoryItemDto>,
  onDismiss: () -> Unit,
  onSave: (List<RecipeIngredientLineRequest>) -> Unit,
) {
  val qtyByIngredient = remember(binding.targetId) {
    mutableStateMapOf<String, String>().apply {
      binding.ingredients.forEach { put(it.ingredientId, it.quantity.toString()) }
    }
  }

  OwnerModalOverlay(
    title = "Recipe: ${binding.targetLabel}",
    onDismiss = onDismiss,
    confirmButton = {
      Button(
        onClick = {
          val lines = qtyByIngredient.mapNotNull { (ingId, raw) ->
            val q = raw.toIntOrNull() ?: 0
            if (q > 0) RecipeIngredientLineRequest(ingId, q) else null
          }
          onSave(lines)
        },
        modifier = Modifier.testTag("recipe_save_button"),
      ) {
        Text("Save Recipe")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  ) {
    if (ingredients.isEmpty()) {
      Text("Add ingredients under Operations -> Inventory before defining recipes.")
    } else {
      Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ingredients.forEach { ing ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(ing.name, style = MaterialTheme.typography.bodyLarge)
              Text(ing.unit.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
              value = qtyByIngredient[ing.ingredientId] ?: "",
              onValueChange = { qtyByIngredient[ing.ingredientId] = it.filter(Char::isDigit) },
              label = { Text("Qty") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              modifier = Modifier.width(96.dp).testTag("recipe_qty_${ing.ingredientId}"),
            )
          }
        }
      }
    }
  }
}

package com.example.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.domain.RustDomainBridge
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CartToppingInputDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.Money
import com.pizzora.contracts.PizzaBuilderEngine
import com.pizzora.contracts.PizzaPriceBreakdownDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.ToppingPlacement

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PizzaCustomizerSheet(
  product: ProductDto,
  crusts: List<CrustOptionDto>,
  toppings: List<ToppingDto>,
  onDismiss: () -> Unit,
  onConfirmAdd: (ProductVariantDto, CrustOptionDto, List<ToppingDto>, Int) -> Unit,
  menu: PublicMenuResponse? = null,
  onConfirmConfiguredPizza: ((CartItemInputDto, String) -> Unit)? = null,
) {
  BackHandler(onBack = onDismiss)

  val effectiveMenu = remember(product, crusts, toppings, menu) {
    val base = menu
    if (base != null) {
      val products = if (base.products.any { it.id == product.id }) base.products else base.products + product
      val effCrusts = if (base.crusts.isNotEmpty()) base.crusts else crusts
      val effToppings = if (base.toppings.isNotEmpty()) base.toppings else toppings
      base.copy(
        products = products,
        crusts = effCrusts,
        toppings = effToppings,
      )
    } else {
      PublicMenuResponse(
        categories = emptyList(),
        products = listOf(product),
        crusts = crusts,
        toppings = toppings,
        gstBps = 500,
        storeName = "PIZZORA",
        isAcceptingOrders = true,
      )
    }
  }

  val activeCrusts = remember(crusts, effectiveMenu.crusts) {
    (if (effectiveMenu.crusts.isNotEmpty()) effectiveMenu.crusts else crusts).filter { it.isActive }
  }
  val allCrusts = remember(crusts, effectiveMenu.crusts, activeCrusts) {
    activeCrusts.ifEmpty { if (effectiveMenu.crusts.isNotEmpty()) effectiveMenu.crusts else crusts }
  }
  val activeToppings = remember(toppings, effectiveMenu.toppings) {
    (if (effectiveMenu.toppings.isNotEmpty()) effectiveMenu.toppings else toppings).filter { it.isActive }
  }
  val displayToppings = remember(toppings, effectiveMenu.toppings, activeToppings) {
    activeToppings.ifEmpty { if (effectiveMenu.toppings.isNotEmpty()) effectiveMenu.toppings else toppings }
  }
  val toppingsById = remember(effectiveMenu.toppings, displayToppings) {
    (effectiveMenu.toppings + displayToppings).associateBy { it.id }
  }
  val activeCheeses = remember(effectiveMenu.cheeses) {
    effectiveMenu.cheeses.filter { it.isActive }
  }
  val activeSauces = remember(effectiveMenu.sauces) {
    effectiveMenu.sauces.filter { it.isActive }
  }
  val sizeRulesByCode = remember(effectiveMenu.sizeRules) {
    effectiveMenu.sizeRules.associateBy { it.sizeCode }
  }

  var selectedVariantId by remember(product.id) {
    mutableStateOf(
      product.variants.firstOrNull { it.isActive }?.id
        ?: product.variants.firstOrNull()?.id.orEmpty(),
    )
  }
  var selectedCrustId by remember(product.id, allCrusts) {
    mutableStateOf(allCrusts.firstOrNull()?.id.orEmpty())
  }
  var selectedCheeseId by remember(product.id, activeCheeses) {
    mutableStateOf(
      product.defaultCheeseId?.takeIf { id -> activeCheeses.any { it.id == id } }
        ?: activeCheeses.firstOrNull()?.id,
    )
  }
  var extraCheese by remember(product.id) { mutableStateOf(false) }
  var selectedSauceId by remember(product.id, activeSauces) {
    mutableStateOf(
      product.defaultSauceId?.takeIf { id -> activeSauces.any { it.id == id } }
        ?: activeSauces.firstOrNull()?.id,
    )
  }
  var extraSauce by remember(product.id) { mutableStateOf(false) }
  var isHalfAndHalf by remember(product.id) { mutableStateOf(false) }
  var rightProductId by remember(product.id) { mutableStateOf<String?>(null) }
  var rightSauceId by remember(product.id) { mutableStateOf<String?>(null) }
  val removedIncludedIds = remember(product.id) { mutableStateListOf<String>() }
  var toppingSelections by remember(product.id) {
    mutableStateOf<Map<String, Pair<ToppingPlacement, Int>>>(emptyMap())
  }
  var quantity by remember(product.id) { mutableIntStateOf(1) }
  var showPriceBreakdown by remember(product.id) { mutableStateOf(false) }

  val selectedVariant = product.variants.firstOrNull { it.id == selectedVariantId }
    ?: product.variants.firstOrNull()
  val selectedCrust = allCrusts.firstOrNull { it.id == selectedCrustId }
    ?: allCrusts.firstOrNull()
  val selectedCheese = selectedCheeseId?.let { cid -> activeCheeses.firstOrNull { it.id == cid } }
  val selectedSauce = selectedSauceId?.let { sid -> activeSauces.firstOrNull { it.id == sid } }

  val currentSizeRule = selectedVariant?.let { v -> sizeRulesByCode[v.sizeCode] }
  val sizeSupportsHalfAndHalf = currentSizeRule?.supportsHalfAndHalf
    ?: (selectedVariant?.sizeCode != SizeCode.REGULAR)
  val crustSupportsHalfAndHalf = selectedCrust?.supportsHalfAndHalf ?: true

  val eligibleRightProducts = remember(product.id, selectedVariant?.sizeCode, effectiveMenu.products) {
    val targetSize = selectedVariant?.sizeCode
    effectiveMenu.products.filter { candidate ->
      candidate.isActive &&
        candidate.supportsHalfAndHalf &&
        candidate.id != product.id &&
        (targetSize == null || candidate.variants.any { it.sizeCode == targetSize && it.isActive })
    }
  }
  val hasHalfAndHalfCandidates = product.supportsHalfAndHalf &&
    effectiveMenu.products.any { it.isActive && it.supportsHalfAndHalf && it.id != product.id }

  val canEnableHalfAndHalf = product.supportsHalfAndHalf &&
    sizeSupportsHalfAndHalf &&
    crustSupportsHalfAndHalf &&
    eligibleRightProducts.isNotEmpty()

  val resolvedRightProduct = if (isHalfAndHalf) {
    eligibleRightProducts.firstOrNull { it.id == rightProductId } ?: eligibleRightProducts.firstOrNull()
  } else {
    null
  }
  val resolvedRightVariant = if (isHalfAndHalf) {
    resolvedRightProduct?.variants?.firstOrNull {
      it.sizeCode == selectedVariant?.sizeCode && it.isActive
    }
  } else {
    null
  }
  val resolvedRightSauceId = if (isHalfAndHalf) {
    rightSauceId?.takeIf { sid -> activeSauces.any { it.id == sid } }
      ?: resolvedRightProduct?.defaultSauceId?.takeIf { sid -> activeSauces.any { it.id == sid } }
      ?: selectedSauceId
  } else {
    null
  }

  fun resetHalfAndHalfState() {
    isHalfAndHalf = false
    rightProductId = null
    rightSauceId = null
    toppingSelections = toppingSelections.mapValues { (_, pair) ->
      ToppingPlacement.WHOLE to pair.second
    }
    val leftIncluded = product.includedToppingIds.toSet()
    removedIncludedIds.removeAll { it !in leftIncluded }
  }

  val builderInput = CartItemInputDto(
    productId = product.id,
    variantId = selectedVariant?.id.orEmpty(),
    crustId = selectedCrust?.id.orEmpty(),
    toppingIds = toppingSelections.entries
      .filter { it.value.first == ToppingPlacement.WHOLE && it.value.second == 1 }
      .map { it.key }
      .sorted(),
    quantity = quantity,
    cheeseId = selectedCheeseId,
    extraCheese = extraCheese,
    sauceId = selectedSauceId,
    extraSauce = extraSauce,
    isHalfAndHalf = isHalfAndHalf,
    rightProductId = if (isHalfAndHalf) resolvedRightProduct?.id ?: rightProductId else null,
    rightVariantId = if (isHalfAndHalf) resolvedRightVariant?.id else null,
    rightSauceId = resolvedRightSauceId,
    removedIncludedToppingIds = removedIncludedIds.toList(),
    toppingSelections = toppingSelections.entries
      .sortedBy { it.key }
      .map { (tid, pair) ->
        CartToppingInputDto(
          toppingId = tid,
          placement = pair.first,
          quantity = pair.second,
        )
      },
  )

  val evaluation = remember(builderInput, effectiveMenu) {
    RustDomainBridge.evaluatePizzaCustomization(builderInput, effectiveMenu)
  }

  val selectedWholeToppingsFallback = remember(toppingSelections, displayToppings) {
    displayToppings.filter { it.id in toppingSelections.keys }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .testTag("pizza_customizer_sheet"),
    contentAlignment = Alignment.BottomCenter,
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.55f))
        .clickable(onClick = onDismiss),
    )
    Surface(
      shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 640.dp)
        .pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              event.changes.forEach { it.consume() }
            }
          }
        },
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
          ) {
            VegIndicator(
              isVeg = product.isVeg && (!isHalfAndHalf || resolvedRightProduct?.isVeg != false),
            )
            Column {
              Text(
                text = if (isHalfAndHalf && resolvedRightProduct != null) {
                  "Half & Half: ${product.name} / ${resolvedRightProduct.name}"
                } else {
                  product.name
                },
                style = MaterialTheme.typography.headlineSmall,
              )
            }
          }
          IconButton(
            onClick = onDismiss,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("customizer_close_button"),
          ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
          }
        }

        if (product.description.isNotBlank()) {
          Text(
            text = product.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
          )
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider()

        Column(
          modifier = Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          // 1. Size selector
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.customizer_choose_size), style = MaterialTheme.typography.titleMedium)
            product.variants.forEach { variant ->
              val rule = sizeRulesByCode[variant.sizeCode]
              val isSelected = variant.id == selectedVariant?.id
              val isSizeEnabled = variant.isActive && (rule?.isActive ?: true)
              val label = rule?.displayName
                ?: variant.sizeCode.name.replace('_', ' ').lowercase()
                  .split(' ')
                  .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
              Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isSelected) {
                  MaterialTheme.colorScheme.primaryContainer
                } else {
                  MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                border = BorderStroke(
                  width = if (isSelected) 1.5.dp else 1.dp,
                  color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                  },
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .minimumInteractiveComponentSize()
                  .clickable(enabled = isSizeEnabled) {
                    selectedVariantId = variant.id
                    val nextRule = sizeRulesByCode[variant.sizeCode]
                    val nextSupportsHalf = nextRule?.supportsHalfAndHalf
                      ?: (variant.sizeCode != SizeCode.REGULAR)
                    if (isHalfAndHalf) {
                      val matchingRight = effectiveMenu.products.filter { candidate ->
                        candidate.isActive &&
                          candidate.supportsHalfAndHalf &&
                          candidate.id != product.id &&
                          candidate.variants.any { it.sizeCode == variant.sizeCode && it.isActive }
                      }
                      if (!nextSupportsHalf || matchingRight.isEmpty()) {
                        resetHalfAndHalfState()
                      } else if (matchingRight.none { it.id == rightProductId }) {
                        val fallbackRight = matchingRight.first()
                        rightProductId = fallbackRight.id
                        rightSauceId = fallbackRight.defaultSauceId ?: selectedSauceId
                      }
                    }
                  }
                  .testTag("size_option_${variant.sizeCode.name}"),
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    RadioButton(selected = isSelected, onClick = null, enabled = isSizeEnabled)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                      Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                      )
                      if (rule != null) {
                        val halfNote = if (rule.supportsHalfAndHalf) " • Half & Half OK" else ""
                        Text(
                          text = "Up to ${rule.maxToppings} toppings (${rule.maxExtraToppings} extra)$halfNote",
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                    }
                  }
                  Text(Money.formatInr(variant.pricePaise), style = PizzoraMonoBody)
                }
              }
            }
          }

          // 2. Crust selector
          if (allCrusts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(stringResource(R.string.customizer_choose_crust), style = MaterialTheme.typography.titleMedium)
              allCrusts.forEach { crust ->
                val isSelected = crust.id == selectedCrust?.id
                Surface(
                  shape = MaterialTheme.shapes.small,
                  color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                  } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                  },
                  border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    },
                  ),
                  modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable(enabled = crust.isActive) {
                      selectedCrustId = crust.id
                      if (isHalfAndHalf && !crust.supportsHalfAndHalf) {
                        resetHalfAndHalfState()
                      }
                    }
                    .testTag("crust_option_${crust.id}"),
                ) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                      RadioButton(selected = isSelected, onClick = null, enabled = crust.isActive)
                      Spacer(modifier = Modifier.width(8.dp))
                      Column {
                        Text(crust.name, style = MaterialTheme.typography.bodyLarge)
                        if (!crust.supportsHalfAndHalf) {
                          Text(
                            text = "Single pizza only (no Half & Half)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                        }
                      }
                    }
                    Text(
                      text = if (crust.extraPricePaise == 0L) "Included" else "+ ${Money.formatInr(crust.extraPricePaise)}",
                      style = PizzoraMonoBody,
                    )
                  }
                }
              }
            }
          }

          // 3. Half-and-Half selector (only when store menu offers compatible second-half pizzas)
          if (hasHalfAndHalfCandidates) {
            Surface(
              shape = MaterialTheme.shapes.medium,
              color = if (isHalfAndHalf) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
              } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
              },
              border = BorderStroke(
                width = 1.dp,
                color = if (isHalfAndHalf) {
                  MaterialTheme.colorScheme.secondary
                } else {
                  MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                },
              ),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = stringResource(R.string.customizer_half_and_half),
                      style = MaterialTheme.typography.titleMedium,
                    )
                    val subtitle = when {
                      !sizeSupportsHalfAndHalf ->
                        "Not available for ${currentSizeRule?.displayName ?: selectedVariant?.sizeCode?.name}."
                      !crustSupportsHalfAndHalf ->
                        "Not supported with ${selectedCrust?.name ?: "this"} crust."
                      eligibleRightProducts.isEmpty() ->
                        "No other pizzas available in this size."
                      isHalfAndHalf && resolvedRightProduct != null ->
                        "Left: ${product.name} • Right: ${resolvedRightProduct.name}"
                      else ->
                        "Combine two recipes & customize each half independently."
                    }
                    Text(
                      text = subtitle,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  Switch(
                    checked = isHalfAndHalf,
                    enabled = canEnableHalfAndHalf || isHalfAndHalf,
                    onCheckedChange = { checked ->
                      if (checked && canEnableHalfAndHalf) {
                        isHalfAndHalf = true
                        val chosen = eligibleRightProducts.firstOrNull { it.id == rightProductId }
                          ?: eligibleRightProducts.first()
                        rightProductId = chosen.id
                        rightSauceId = chosen.defaultSauceId ?: selectedSauceId
                      } else {
                        resetHalfAndHalfState()
                      }
                    },
                    modifier = Modifier.testTag("half_and_half_toggle"),
                  )
                }

                if (isHalfAndHalf && eligibleRightProducts.isNotEmpty()) {
                  Text(
                    text = stringResource(R.string.customizer_choose_right_half),
                    style = MaterialTheme.typography.labelLarge,
                  )
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    eligibleRightProducts.forEach { candidate ->
                      val candidateVar = candidate.variants.firstOrNull {
                        it.sizeCode == selectedVariant?.sizeCode && it.isActive
                      }
                      val surcharge = if (candidateVar != null && selectedVariant != null) {
                        maxOf(0L, candidateVar.pricePaise - selectedVariant.pricePaise)
                      } else {
                        0L
                      }
                      val isRightSelected = resolvedRightProduct?.id == candidate.id
                      FilterChip(
                        selected = isRightSelected,
                        onClick = {
                          rightProductId = candidate.id
                          rightSauceId = candidate.defaultSauceId ?: selectedSauceId
                          val allowedIncluded = buildSet {
                            addAll(product.includedToppingIds)
                            addAll(candidate.includedToppingIds)
                          }
                          removedIncludedIds.removeAll { it !in allowedIncluded }
                        },
                        leadingIcon = { VegIndicator(isVeg = candidate.isVeg) },
                        label = {
                          Text(
                            text = if (surcharge > 0L) {
                              "${candidate.name} (+${Money.formatInr(surcharge)})"
                            } else {
                              candidate.name
                            },
                          )
                        },
                        modifier = Modifier
                          .minimumInteractiveComponentSize()
                          .testTag("right_half_product_${candidate.id}"),
                      )
                    }
                  }
                }
              }
            }
          }

          // 4. Cheese selection + Extra Cheese
          if (activeCheeses.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(stringResource(R.string.customizer_choose_cheese), style = MaterialTheme.typography.titleMedium)
              activeCheeses.forEach { cheese ->
                val isSelected = cheese.id == selectedCheese?.id
                Surface(
                  shape = MaterialTheme.shapes.small,
                  color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                  } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                  },
                  border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    },
                  ),
                  modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable {
                      selectedCheeseId = cheese.id
                      if (!cheese.supportsExtra) extraCheese = false
                    }
                    .testTag("cheese_option_${cheese.id}"),
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
                      RadioButton(selected = isSelected, onClick = null)
                      VegIndicator(isVeg = true)
                      Column {
                        Text(cheese.name, style = MaterialTheme.typography.bodyLarge)
                        if (cheese.isVegan) {
                          Text(
                            text = "Vegan / Dairy-free",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                          )
                        }
                      }
                    }
                    Text(
                      text = if (cheese.extraPricePaise == 0L) "Included" else "+ ${Money.formatInr(cheese.extraPricePaise)}",
                      style = PizzoraMonoBody,
                    )
                  }
                }
              }

              if (selectedCheese != null && selectedCheese.supportsExtra) {
                Surface(
                  shape = MaterialTheme.shapes.small,
                  color = if (extraCheese) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                  } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                  },
                  border = BorderStroke(
                    width = 1.dp,
                    color = if (extraCheese) {
                      MaterialTheme.colorScheme.secondary
                    } else {
                      MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    },
                  ),
                  modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable { extraCheese = !extraCheese }
                    .testTag("extra_cheese_toggle"),
                ) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Checkbox(checked = extraCheese, onCheckedChange = null)
                      Spacer(modifier = Modifier.width(6.dp))
                      Text(stringResource(R.string.customizer_extra_cheese), style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("+ ${Money.formatInr(selectedCheese.extraCheesePricePaise)}", style = PizzoraMonoBody)
                  }
                }
              }
            }
          }

          // 5. Sauce selection (Whole / Left Half + Right Half when Half-and-Half + Extra Sauce)
          if (activeSauces.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                text = if (isHalfAndHalf) "Left-Half Sauce" else stringResource(R.string.customizer_choose_sauce),
                style = MaterialTheme.typography.titleMedium,
              )
              activeSauces.forEach { sauce ->
                val isSelected = sauce.id == selectedSauce?.id
                val priceLabel = if (isHalfAndHalf) {
                  val halfRate = Money.bpsOf(sauce.extraPricePaise, PizzaBuilderEngine.HALF_PRICE_BPS)
                  if (halfRate == 0L) "Included" else "+ ${Money.formatInr(halfRate)} (half)"
                } else {
                  if (sauce.extraPricePaise == 0L) "Included" else "+ ${Money.formatInr(sauce.extraPricePaise)}"
                }
                Surface(
                  shape = MaterialTheme.shapes.small,
                  color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                  } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                  },
                  border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    },
                  ),
                  modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable {
                      selectedSauceId = sauce.id
                      if (!sauce.supportsExtra) extraSauce = false
                    }
                    .testTag("sauce_option_${sauce.id}"),
                ) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                      RadioButton(selected = isSelected, onClick = null)
                      Spacer(modifier = Modifier.width(8.dp))
                      Text(sauce.name, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(text = priceLabel, style = PizzoraMonoBody)
                  }
                }
              }

              if (isHalfAndHalf) {
                Text(
                  text = stringResource(R.string.customizer_choose_right_sauce),
                  style = MaterialTheme.typography.labelLarge,
                )
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  activeSauces.forEach { sauce ->
                    val halfPrice = Money.bpsOf(sauce.extraPricePaise, PizzaBuilderEngine.HALF_PRICE_BPS)
                    FilterChip(
                      selected = resolvedRightSauceId == sauce.id,
                      onClick = {
                        rightSauceId = sauce.id
                        if (!sauce.supportsExtra) extraSauce = false
                      },
                      label = {
                        Text(
                          text = if (halfPrice > 0L) "${sauce.name} (+${Money.formatInr(halfPrice)})" else sauce.name,
                        )
                      },
                      modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("right_sauce_option_${sauce.id}"),
                    )
                  }
                }
              }

              val rightSauceObj = resolvedRightSauceId?.let { rsid -> activeSauces.firstOrNull { it.id == rsid } }
              val extraSauceSupported = (selectedSauce?.supportsExtra == true) &&
                (!isHalfAndHalf || rightSauceObj?.supportsExtra == true)
              if (extraSauceSupported) {
                val extraSauceCharge = if (!isHalfAndHalf || selectedSauceId == resolvedRightSauceId) {
                  selectedSauce?.extraSaucePricePaise ?: 0L
                } else {
                  Money.bpsOf(selectedSauce?.extraSaucePricePaise ?: 0L, PizzaBuilderEngine.HALF_PRICE_BPS) +
                    Money.bpsOf(rightSauceObj?.extraSaucePricePaise ?: 0L, PizzaBuilderEngine.HALF_PRICE_BPS)
                }
                Surface(
                  shape = MaterialTheme.shapes.small,
                  color = if (extraSauce) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                  } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                  },
                  border = BorderStroke(
                    width = 1.dp,
                    color = if (extraSauce) {
                      MaterialTheme.colorScheme.secondary
                    } else {
                      MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    },
                  ),
                  modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable { extraSauce = !extraSauce }
                    .testTag("extra_sauce_toggle"),
                ) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Checkbox(checked = extraSauce, onCheckedChange = null)
                      Spacer(modifier = Modifier.width(6.dp))
                      Text(stringResource(R.string.customizer_extra_sauce), style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("+ ${Money.formatInr(extraSauceCharge)}", style = PizzoraMonoBody)
                  }
                }
              }
            }
          }

          // 6. Included Toppings (removable defaults)
          val includedIds = remember(product.includedToppingIds, resolvedRightProduct?.includedToppingIds, isHalfAndHalf) {
            buildList {
              addAll(product.includedToppingIds)
              if (isHalfAndHalf) {
                resolvedRightProduct?.includedToppingIds?.forEach { if (it !in this) add(it) }
              }
            }.filter { toppingsById.containsKey(it) }
          }
          if (includedIds.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(stringResource(R.string.customizer_included_toppings), style = MaterialTheme.typography.titleMedium)
              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                includedIds.forEach { tid ->
                  val topping = toppingsById[tid] ?: return@forEach
                  val isRemoved = tid in removedIncludedIds
                  FilterChip(
                    selected = !isRemoved,
                    onClick = {
                      if (isRemoved) {
                        removedIncludedIds.remove(tid)
                      } else {
                        removedIncludedIds.add(tid)
                      }
                    },
                    label = {
                      Text(if (isRemoved) "No ${topping.name}" else "${topping.name} (Included)")
                    },
                    modifier = Modifier
                      .minimumInteractiveComponentSize()
                      .testTag("included_topping_toggle_$tid"),
                  )
                }
              }
            }
          }

          // 7. Extra Toppings with placement (Whole/Left/Right) + quantity
          if (displayToppings.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(stringResource(R.string.customizer_extra_toppings), style = MaterialTheme.typography.titleMedium)
              displayToppings.forEach { topping ->
                val sel = toppingSelections[topping.id]
                val checked = sel != null
                val currentPlacement = sel?.first ?: ToppingPlacement.WHOLE
                val currentQty = sel?.second ?: 1
                val ratePaise = when (currentPlacement) {
                  ToppingPlacement.WHOLE -> topping.extraPricePaise
                  ToppingPlacement.LEFT, ToppingPlacement.RIGHT ->
                    Money.bpsOf(topping.extraPricePaise, PizzaBuilderEngine.HALF_PRICE_BPS)
                }
                val isFreeIncluded = if (currentPlacement == ToppingPlacement.WHOLE) {
                  val leftInc = topping.id in product.includedToppingIds
                  val rightInc = !isHalfAndHalf || (resolvedRightProduct?.includedToppingIds?.contains(topping.id) == true)
                  topping.id !in removedIncludedIds && leftInc && rightInc
                } else if (currentPlacement == ToppingPlacement.LEFT) {
                  topping.id !in removedIncludedIds && (topping.id in product.includedToppingIds)
                } else {
                  topping.id !in removedIncludedIds && (resolvedRightProduct?.includedToppingIds?.contains(topping.id) == true)
                }

                Surface(
                  shape = MaterialTheme.shapes.small,
                  color = if (checked) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                  } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                  },
                  border = BorderStroke(
                    width = 1.dp,
                    color = if (checked) {
                      MaterialTheme.colorScheme.secondary
                    } else {
                      MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    },
                  ),
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clickable {
                          toppingSelections = if (checked) {
                            toppingSelections - topping.id
                          } else {
                            toppingSelections + (topping.id to (ToppingPlacement.WHOLE to 1))
                          }
                        }
                        .testTag("topping_option_${topping.id}")
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                      ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        VegIndicator(isVeg = topping.isVeg)
                        Column {
                          Text(topping.name, style = MaterialTheme.typography.bodyLarge)
                          if (isFreeIncluded && !checked) {
                            Text(
                              text = "1x included • tap to double",
                              style = MaterialTheme.typography.labelSmall,
                              color = MaterialTheme.colorScheme.secondary,
                            )
                          }
                        }
                      }
                      Text("+ ${Money.formatInr(ratePaise)}", style = PizzoraMonoBody)
                    }

                    if (checked) {
                      Row(
                        modifier = Modifier
                          .fillMaxWidth()
                          .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                      ) {
                        if (isHalfAndHalf && topping.supportsHalfAndHalf) {
                          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                              ToppingPlacement.WHOLE to "Whole",
                              ToppingPlacement.LEFT to "Left",
                              ToppingPlacement.RIGHT to "Right",
                            ).forEach { (placement, label) ->
                              FilterChip(
                                selected = currentPlacement == placement,
                                onClick = {
                                  toppingSelections = toppingSelections +
                                    (topping.id to (placement to currentQty))
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier
                                  .minimumInteractiveComponentSize()
                                  .testTag("topping_placement_${topping.id}_${placement.name}"),
                              )
                            }
                          }
                        } else {
                          Text(
                            text = if (isFreeIncluded && currentQty > 1) "Extra Portion" else "Qty",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                        }

                        if (topping.maxQuantity > 1) {
                          Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                              onClick = {
                                if (currentQty > 1) {
                                  toppingSelections = toppingSelections +
                                    (topping.id to (currentPlacement to (currentQty - 1)))
                                } else {
                                  toppingSelections = toppingSelections - topping.id
                                }
                              },
                              modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("topping_qty_minus_${topping.id}"),
                            ) {
                              Icon(Icons.Filled.Remove, contentDescription = "Decrease topping quantity")
                            }
                            Text(
                              text = "${currentQty}x",
                              style = PizzoraMonoBody,
                              modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .testTag("topping_qty_value_${topping.id}"),
                            )
                            IconButton(
                              onClick = {
                                if (currentQty < topping.maxQuantity) {
                                  toppingSelections = toppingSelections +
                                    (topping.id to (currentPlacement to (currentQty + 1)))
                                }
                              },
                              enabled = currentQty < topping.maxQuantity,
                              modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("topping_qty_plus_${topping.id}"),
                            ) {
                              Icon(Icons.Filled.Add, contentDescription = "Increase topping quantity")
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }

          // 8. Live validation issues banner
          if (!evaluation.isValid && evaluation.issues.isNotEmpty()) {
            Surface(
              color = MaterialTheme.colorScheme.errorContainer,
              shape = MaterialTheme.shapes.medium,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("customizer_validation_issues"),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
              ) {
                Icon(
                  Icons.Filled.WarningAmber,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onErrorContainer,
                  modifier = Modifier.size(20.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  evaluation.issues.forEach { issue ->
                    Text(
                      text = issue.message,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                  }
                }
              }
            }
          }

          // 9. Expandable live price breakdown card
          LivePizzaPriceBreakdownCard(
            breakdown = evaluation.priceBreakdown,
            quantity = quantity,
            expanded = showPriceBreakdown,
            onToggle = { showPriceBreakdown = !showPriceBreakdown },
          )
        }

        HorizontalDivider()
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Surface(
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              IconButton(
                onClick = { if (quantity > 1) quantity -= 1 },
                enabled = quantity > 1,
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("customizer_qty_minus"),
              ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease quantity")
              }
              Text(
                text = quantity.toString(),
                style = PizzoraMonoTitle,
                modifier = Modifier
                  .padding(horizontal = 6.dp)
                  .testTag("customizer_qty_value"),
              )
              IconButton(
                onClick = { if (quantity < PizzaBuilderEngine.MAX_ITEM_QUANTITY) quantity += 1 },
                enabled = quantity < PizzaBuilderEngine.MAX_ITEM_QUANTITY,
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("customizer_qty_plus"),
              ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase quantity")
              }
            }
          }

          Button(
            onClick = {
              val v = selectedVariant
              val c = selectedCrust
              if (v != null && c != null && evaluation.isValid) {
                val hasAdvancedBuilderConfig = isHalfAndHalf ||
                  extraCheese ||
                  extraSauce ||
                  selectedCheeseId != null ||
                  selectedSauceId != null ||
                  removedIncludedIds.isNotEmpty() ||
                  toppingSelections.any { (_, pair) ->
                    pair.first != ToppingPlacement.WHOLE || pair.second != 1
                  }
                if (onConfirmConfiguredPizza != null && (hasAdvancedBuilderConfig || menu != null)) {
                  val displayTitle = if (isHalfAndHalf && resolvedRightProduct != null) {
                    "Half & Half: ${product.name} / ${resolvedRightProduct.name}"
                  } else {
                    product.name
                  }
                  onConfirmConfiguredPizza(builderInput, displayTitle)
                } else {
                  onConfirmAdd(v, c, selectedWholeToppingsFallback, quantity)
                }
              }
            },
            enabled = selectedVariant != null && selectedCrust != null && evaluation.isValid,
            modifier = Modifier
              .weight(1f)
              .minimumInteractiveComponentSize()
              .testTag("add_to_cart_button"),
          ) {
            Text(
              text = "Add • ${Money.formatInr(evaluation.lineTotalPaise)}",
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun LivePizzaPriceBreakdownCard(
  breakdown: PizzaPriceBreakdownDto,
  quantity: Int,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  Surface(
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onToggle)
      .testTag("customizer_price_breakdown"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(R.string.customizer_price_breakdown),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            text = "${Money.formatInr(breakdown.unitPricePaise)} / pizza",
            style = PizzoraMonoBody,
            color = MaterialTheme.colorScheme.primary,
          )
          Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
          )
        }
      }

      if (expanded) {
        HorizontalDivider()
        BreakdownLine("Base Size Price", breakdown.basePricePaise)
        if (breakdown.halfAndHalfSurchargePaise > 0L) {
          BreakdownLine("Half & Half Recipe Surcharge", breakdown.halfAndHalfSurchargePaise)
        }
        if (breakdown.crustPricePaise > 0L) {
          BreakdownLine("Crust Upgrade", breakdown.crustPricePaise)
        }
        if (breakdown.cheesePricePaise > 0L) {
          BreakdownLine("Cheese Selection", breakdown.cheesePricePaise)
        }
        if (breakdown.extraCheesePricePaise > 0L) {
          BreakdownLine("Extra Cheese", breakdown.extraCheesePricePaise)
        }
        if (breakdown.saucePricePaise > 0L) {
          BreakdownLine("Sauce Selection", breakdown.saucePricePaise)
        }
        if (breakdown.extraSaucePricePaise > 0L) {
          BreakdownLine("Extra Sauce", breakdown.extraSaucePricePaise)
        }
        if (breakdown.toppingsPricePaise > 0L) {
          BreakdownLine("Extra Toppings", breakdown.toppingsPricePaise)
        }
        if (quantity > 1) {
          HorizontalDivider()
          BreakdownLine("Line Total (${quantity}x)", breakdown.lineTotalPaise, bold = true)
        }
      }
    }
  }
}

@Composable
private fun BreakdownLine(
  label: String,
  amountPaise: Long,
  bold: Boolean = false,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = Money.formatInr(amountPaise),
      style = PizzoraMonoBody,
      fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    )
  }
}

package com.example.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.Money
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.ToppingDto

@Composable
fun PizzaCustomizerSheet(
  product: ProductDto,
  crusts: List<CrustOptionDto>,
  toppings: List<ToppingDto>,
  onDismiss: () -> Unit,
  onConfirmAdd: (ProductVariantDto, CrustOptionDto, List<ToppingDto>, Int) -> Unit,
) {
  BackHandler(onBack = onDismiss)

  var selectedVariantId by remember(product.id) {
    mutableStateOf(product.variants.firstOrNull()?.id.orEmpty())
  }
  var selectedCrustId by remember(product.id, crusts) {
    mutableStateOf(crusts.firstOrNull()?.id.orEmpty())
  }
  val selectedToppingIds = remember(product.id) { mutableStateListOf<String>() }
  var quantity by remember(product.id) { mutableIntStateOf(1) }

  val selectedVariant = product.variants.firstOrNull { it.id == selectedVariantId }
    ?: product.variants.firstOrNull()
  val selectedCrust = crusts.firstOrNull { it.id == selectedCrustId }
    ?: crusts.firstOrNull()
  val selectedToppings = toppings.filter { it.id in selectedToppingIds }

  val unitPricePaise = (selectedVariant?.pricePaise ?: 0L) +
    (selectedCrust?.extraPricePaise ?: 0L) +
    selectedToppings.sumOf { it.extraPricePaise }
  val totalPricePaise = unitPricePaise * quantity

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
            VegIndicator(isVeg = product.isVeg)
            Text(
              text = product.name,
              style = MaterialTheme.typography.headlineSmall,
            )
          }
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("customizer_close_button"),
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
          // Size selector
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.customizer_choose_size), style = MaterialTheme.typography.titleMedium)
            product.variants.forEach { variant ->
              val isSelected = variant.id == selectedVariant?.id
              Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(
                  width = if (isSelected) 1.5.dp else 1.dp,
                  color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .minimumInteractiveComponentSize()
                  .clickable { selectedVariantId = variant.id }
                  .testTag("size_option_${variant.sizeCode.name}"),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                      text = variant.sizeCode.name.lowercase().replaceFirstChar { it.uppercase() },
                      style = MaterialTheme.typography.bodyLarge,
                    )
                  }
                  Text(Money.formatInr(variant.pricePaise), style = PizzoraMonoBody)
                }
              }
            }
          }

          // Crust selector
          if (crusts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(stringResource(R.string.customizer_choose_crust), style = MaterialTheme.typography.titleMedium)
              crusts.forEach { crust ->
                val isSelected = crust.id == selectedCrust?.id
                Surface(
                  shape = MaterialTheme.shapes.small,
                  color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                  border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                  ),
                  modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable { selectedCrustId = crust.id }
                    .testTag("crust_option_${crust.id}"),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      RadioButton(selected = isSelected, onClick = null)
                      Spacer(modifier = Modifier.width(8.dp))
                      Text(crust.name, style = MaterialTheme.typography.bodyLarge)
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

          // Extra toppings
          if (toppings.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(stringResource(R.string.customizer_extra_toppings), style = MaterialTheme.typography.titleMedium)
              toppings.forEach { topping ->
                val checked = topping.id in selectedToppingIds
                Surface(
                  shape = MaterialTheme.shapes.small,
                  color = if (checked) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                  border = BorderStroke(
                    width = 1.dp,
                    color = if (checked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                  ),
                  modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable {
                      if (checked) selectedToppingIds.remove(topping.id) else selectedToppingIds.add(topping.id)
                    }
                    .testTag("topping_option_${topping.id}"),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      Checkbox(checked = checked, onCheckedChange = null)
                      VegIndicator(isVeg = topping.isVeg)
                      Text(topping.name, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("+ ${Money.formatInr(topping.extraPricePaise)}", style = PizzoraMonoBody)
                  }
                }
              }
            }
          }
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
                modifier = Modifier.minimumInteractiveComponentSize().testTag("customizer_qty_minus"),
              ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease quantity")
              }
              Text(
                text = quantity.toString(),
                style = PizzoraMonoTitle,
                modifier = Modifier.padding(horizontal = 6.dp).testTag("customizer_qty_value"),
              )
              IconButton(
                onClick = { if (quantity < 25) quantity += 1 },
                enabled = quantity < 25,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("customizer_qty_plus"),
              ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase quantity")
              }
            }
          }

          Button(
            onClick = {
              val v = selectedVariant
              val c = selectedCrust
              if (v != null && c != null) {
                onConfirmAdd(v, c, selectedToppings, quantity)
              }
            },
            enabled = selectedVariant != null && selectedCrust != null,
            modifier = Modifier
              .weight(1f)
              .minimumInteractiveComponentSize()
              .testTag("add_to_cart_button"),
          ) {
            Text(
              text = "Add • ${Money.formatInr(totalPricePaise)}",
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }
      }
    }
  }
}

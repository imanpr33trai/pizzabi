package com.pizzora.contracts

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8SerializationTest {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  @Test
  fun `CheeseOptionDto serializes with snake_case field names`() {
    val dto = CheeseOptionDto(
      id = "cheese-1",
      productId = "prod-1",
      name = "Mozzarella",
      extraPricePaise = 2000L,
      isActive = true,
      sortOrder = 1,
    )
    val encoded = json.encodeToString(dto)
    assertTrue(encoded.contains("\"id\":\"cheese-1\""))
    assertTrue(encoded.contains("\"product_id\":\"prod-1\""))
    assertTrue(encoded.contains("\"name\":\"Mozzarella\""))
    assertTrue(encoded.contains("\"extra_price_paise\":2000"))
    assertTrue(encoded.contains("\"is_active\":true"))
    assertTrue(encoded.contains("\"sort_order\":1"))
    val decoded = json.decodeFromString<CheeseOptionDto>(encoded)
    assertEquals(dto, decoded)
  }

  @Test
  fun `SauceOptionDto serializes with snake_case field names`() {
    val dto = SauceOptionDto(
      id = "sauce-1",
      productId = "prod-1",
      name = "Marinara",
      extraPricePaise = 1500L,
      isActive = true,
      sortOrder = 1,
    )
    val encoded = json.encodeToString(dto)
    assertTrue(encoded.contains("\"product_id\":\"prod-1\""))
    assertTrue(encoded.contains("\"extra_price_paise\":1500"))
    val decoded = json.decodeFromString<SauceOptionDto>(encoded)
    assertEquals(dto, decoded)
  }

  @Test
  fun `StandardToppingDto serializes with snake_case field names including is_default`() {
    val dto = StandardToppingDto(
      id = "std-top-1",
      productId = "prod-1",
      name = "Basil",
      isVeg = true,
      extraPricePaise = 500L,
      isDefault = true,
      isActive = true,
      sortOrder = 1,
    )
    val encoded = json.encodeToString(dto)
    assertTrue(encoded.contains("\"product_id\":\"prod-1\""))
    assertTrue(encoded.contains("\"is_veg\":true"))
    assertTrue(encoded.contains("\"extra_price_paise\":500"))
    assertTrue(encoded.contains("\"is_default\":true"))
    assertTrue(encoded.contains("\"is_active\":true"))
    assertTrue(encoded.contains("\"sort_order\":1"))
    val decoded = json.decodeFromString<StandardToppingDto>(encoded)
    assertEquals(dto, decoded)
  }

  @Test
  fun `HalfAndHalfConfigDto serializes with snake_case field names`() {
    val dto = HalfAndHalfConfigDto(
      id = "hah-1",
      productId = "prod-1",
      name = "Half & Half Classic",
      leftToppingIds = listOf("top-1", "top-2"),
      rightToppingIds = listOf("top-3", "top-4"),
      halfAndHalfSurchargePaise = 1000L,
      isActive = true,
      sortOrder = 1,
    )
    val encoded = json.encodeToString(dto)
    assertTrue(encoded.contains("\"product_id\":\"prod-1\""))
    assertTrue(encoded.contains("\"left_topping_ids\":[\"top-1\",\"top-2\"]"))
    assertTrue(encoded.contains("\"right_topping_ids\":[\"top-3\",\"top-4\"]"))
    assertTrue(encoded.contains("\"half_and_half_surcharge_paise\":1000"))
    assertTrue(encoded.contains("\"is_active\":true"))
    assertTrue(encoded.contains("\"sort_order\":1"))
    val decoded = json.decodeFromString<HalfAndHalfConfigDto>(encoded)
    assertEquals(dto, decoded)
  }

  @Test
  fun `ProductDto includes new modifier arrays with defaults`() {
    val dto = ProductDto(
      id = "prod-1",
      categoryId = "cat-1",
      categoryName = "Classic",
      name = "Margherita",
      description = "Classic pizza",
      isVeg = true,
      isActive = true,
      isTaxable = true,
      variants = emptyList(),
      images = emptyList(),
      cheeses = listOf(CheeseOptionDto("cheese-1", "prod-1", "Mozzarella", 2000L, true, 1)),
      sauces = listOf(SauceOptionDto("sauce-1", "prod-1", "Marinara", 1500L, true, 1)),
      standardToppings = listOf(StandardToppingDto("std-1", "prod-1", "Basil", true, 500L, true, true, 1)),
      halfAndHalfConfigs = listOf(HalfAndHalfConfigDto("hah-1", "prod-1", "Half & Half", listOf("t1"), listOf("t2"), 1000L, true, 1)),
    )
    val encoded = json.encodeToString(dto)
    assertTrue(encoded.contains("\"cheeses\":"))
    assertTrue(encoded.contains("\"sauces\":"))
    assertTrue(encoded.contains("\"standard_toppings\":"))
    assertTrue(encoded.contains("\"half_and_half_configs\":"))
    val decoded = json.decodeFromString<ProductDto>(encoded)
    assertEquals(1, decoded.cheeses.size)
    assertEquals(1, decoded.sauces.size)
    assertEquals(1, decoded.standardToppings.size)
    assertEquals(1, decoded.halfAndHalfConfigs.size)
  }

  @Test
  fun `ProductDto backward compatible - old JSON without new fields deserializes`() {
    val oldJson = """
      {
        "id": "prod-1",
        "category_id": "cat-1",
        "category_name": "Classic",
        "name": "Margherita",
        "description": "Classic pizza",
        "is_veg": true,
        "is_active": true,
        "is_taxable": true,
        "variants": [],
        "images": []
      }
    """.trimIndent()
    val decoded = json.decodeFromString<ProductDto>(oldJson)
    assertEquals("prod-1", decoded.id)
    assertTrue(decoded.cheeses.isEmpty())
    assertTrue(decoded.sauces.isEmpty())
    assertTrue(decoded.standardToppings.isEmpty())
    assertTrue(decoded.halfAndHalfConfigs.isEmpty())
  }

  @Test
  fun `PublicMenuResponse includes modifier arrays in products`() {
    val product = ProductDto(
      id = "prod-1",
      categoryId = "cat-1",
      categoryName = "Classic",
      name = "Margherita",
      description = "Classic pizza",
      isVeg = true,
      isActive = true,
      isTaxable = true,
      variants = emptyList(),
      images = emptyList(),
      cheeses = listOf(CheeseOptionDto("cheese-1", "prod-1", "Mozzarella", 2000L, true, 1)),
      sauces = emptyList(),
      standardToppings = emptyList(),
      halfAndHalfConfigs = emptyList(),
    )
    val response = PublicMenuResponse(
      categories = emptyList(),
      products = listOf(product),
      crusts = emptyList(),
      toppings = emptyList(),
      gstBps = 500,
      storeName = "PIZZORA",
      isAcceptingOrders = true,
    )
    val encoded = json.encodeToString(response)
    val decoded = json.decodeFromString<PublicMenuResponse>(encoded)
    assertEquals(1, decoded.products.size)
    assertEquals(1, decoded.products[0].cheeses.size)
  }

  @Test
  fun `CartItemInputDto includes new modifier fields with defaults`() {
    val dto = CartItemInputDto(
      id = "line-1",
      productId = "prod-1",
      variantId = "var-1",
      crustId = "crust-1",
      toppingIds = listOf("top-1"),
      quantity = 2,
      clientUnitPricePaise = 15000L,
      cheeseId = "cheese-1",
      sauceId = "sauce-1",
      standardToppingIds = listOf("std-1", "std-2"),
      halfAndHalfConfigId = "hah-1",
      halfAndHalfLeftToppingIds = listOf("top-1"),
      halfAndHalfRightToppingIds = listOf("top-2"),
    )
    val encoded = json.encodeToString(dto)
    assertTrue(encoded.contains("\"cheese_id\":\"cheese-1\""))
    assertTrue(encoded.contains("\"sauce_id\":\"sauce-1\""))
    assertTrue(encoded.contains("\"standard_topping_ids\":[\"std-1\",\"std-2\"]"))
    assertTrue(encoded.contains("\"half_and_half_config_id\":\"hah-1\""))
    assertTrue(encoded.contains("\"half_and_half_left_topping_ids\":[\"top-1\"]"))
    assertTrue(encoded.contains("\"half_and_half_right_topping_ids\":[\"top-2\"]"))
    val decoded = json.decodeFromString<CartItemInputDto>(encoded)
    assertEquals(dto, decoded)
  }

  @Test
  fun `CartItemInputDto backward compatible - old JSON without new fields deserializes`() {
    val oldJson = """
      {
        "id": "line-1",
        "product_id": "prod-1",
        "variant_id": "var-1",
        "crust_id": "crust-1",
        "topping_ids": ["top-1"],
        "quantity": 1,
        "client_unit_price_paise": 15000
      }
    """.trimIndent()
    val decoded = json.decodeFromString<CartItemInputDto>(oldJson)
    assertEquals("line-1", decoded.id)
    assertEquals("prod-1", decoded.productId)
    assertNull(decoded.cheeseId)
    assertNull(decoded.sauceId)
    assertTrue(decoded.standardToppingIds.isEmpty())
    assertNull(decoded.halfAndHalfConfigId)
    assertTrue(decoded.halfAndHalfLeftToppingIds.isEmpty())
    assertTrue(decoded.halfAndHalfRightToppingIds.isEmpty())
  }

  @Test
  fun `CartItemViewDto includes new resolved modifier views`() {
    val dto = CartItemViewDto(
      id = "line-1",
      productId = "prod-1",
      productName = "Margherita",
      isVeg = true,
      imageUrl = "https://example.com/img.jpg",
      variantId = "var-1",
      sizeCode = SizeCode.MEDIUM,
      basePricePaise = 12000L,
      crustId = "crust-1",
      crustName = "Classic Hand Tossed",
      crustExtraPricePaise = 1000L,
      toppings = listOf(CartToppingViewDto("top-1", "Extra Cheese", true, 2000L, true)),
      quantity = 1,
      unitPricePaise = 15000L,
      lineTotalPaise = 15000L,
      isAvailable = true,
      issues = emptyList(),
      cheese = CartCheeseViewDto("cheese-1", "Mozzarella", 2000L, true),
      sauce = CartSauceViewDto("sauce-1", "Marinara", 1500L, true),
      standardToppings = listOf(CartStandardToppingViewDto("std-1", "Basil", true, 500L, true, false, true)),
      halfAndHalf = CartHalfAndHalfViewDto(
        halfAndHalfConfigId = "hah-1",
        name = "Half & Half",
        leftToppingIds = listOf("top-1"),
        rightToppingIds = listOf("top-2"),
        leftToppings = listOf(CartToppingViewDto("top-1", "Pepperoni", false, 2000L, true)),
        rightToppings = listOf(CartToppingViewDto("top-2", "Mushrooms", true, 1500L, true)),
        halfAndHalfSurchargePaise = 1000L,
        isAvailable = true
      )
    )
    val encoded = json.encodeToString(dto)
    assertTrue(encoded.contains("\"cheese\":"))
    assertTrue(encoded.contains("\"sauce\":"))
    assertTrue(encoded.contains("\"standard_toppings\":"))
    assertTrue(encoded.contains("\"half_and_half\":"))
    val decoded = json.decodeFromString<CartItemViewDto>(encoded)
    assertNotNull(decoded.cheese)
    assertNotNull(decoded.sauce)
    assertEquals(1, decoded.standardToppings.size)
    assertNotNull(decoded.halfAndHalf)
  }

  @Test
  fun `OrderItemDto includes all new modifier ID and snapshot fields`() {
    val dto = OrderItemDto(
      id = "item-1",
      productId = "prod-1",
      productName = "Margherita",
      variantSize = "MEDIUM",
      crustName = "Classic Hand Tossed",
      toppings = listOf("Extra Cheese"),
      unitPricePaise = 15000L,
      quantity = 1,
      lineTotalPaise = 15000L,
      variantId = "var-1",
      crustId = "crust-1",
      cheeseId = "cheese-1",
      sauceId = "sauce-1",
      toppingIds = listOf("top-1"),
      standardToppingIds = listOf("std-1"),
      standardToppingRemovedIds = listOf("std-2"),
      halfAndHalfConfigId = "hah-1",
      halfAndHalfLeftToppingIds = listOf("top-1"),
      halfAndHalfRightToppingIds = listOf("top-2"),
      modifiersSnapshot = mapOf("cheese" to "Mozzarella", "sauce" to "Marinara"),
    )
    val encoded = json.encodeToString(dto)
    assertTrue(encoded.contains("\"variant_id\":\"var-1\""))
    assertTrue(encoded.contains("\"crust_id\":\"crust-1\""))
    assertTrue(encoded.contains("\"cheese_id\":\"cheese-1\""))
    assertTrue(encoded.contains("\"sauce_id\":\"sauce-1\""))
    assertTrue(encoded.contains("\"topping_ids\":[\"top-1\"]"))
    assertTrue(encoded.contains("\"standard_topping_ids\":[\"std-1\"]"))
    assertTrue(encoded.contains("\"standard_topping_removed_ids\":[\"std-2\"]"))
    assertTrue(encoded.contains("\"half_and_half_config_id\":\"hah-1\""))
    assertTrue(encoded.contains("\"half_and_half_left_topping_ids\":[\"top-1\"]"))
    assertTrue(encoded.contains("\"half_and_half_right_topping_ids\":[\"top-2\"]"))
    assertTrue(encoded.contains("\"modifiers_snapshot\":"))
    val decoded = json.decodeFromString<OrderItemDto>(encoded)
    assertEquals(dto, decoded)
  }

  @Test
  fun `OrderItemDto backward compatible - historical order without new fields deserializes`() {
    val oldJson = """
      {
        "id": "item-1",
        "product_id": "prod-1",
        "product_name": "Margherita",
        "variant_size": "MEDIUM",
        "crust_name": "Classic Hand Tossed",
        "toppings": ["Extra Cheese"],
        "unit_price_paise": 15000,
        "quantity": 1,
        "line_total_paise": 15000
      }
    """.trimIndent()
    val decoded = json.decodeFromString<OrderItemDto>(oldJson)
    assertEquals("item-1", decoded.id)
    assertNull(decoded.variantId)
    assertNull(decoded.crustId)
    assertNull(decoded.cheeseId)
    assertNull(decoded.sauceId)
    assertTrue(decoded.toppingIds.isEmpty())
    assertTrue(decoded.standardToppingIds.isEmpty())
    assertTrue(decoded.standardToppingRemovedIds.isEmpty())
    assertNull(decoded.halfAndHalfConfigId)
    assertTrue(decoded.halfAndHalfLeftToppingIds.isEmpty())
    assertTrue(decoded.halfAndHalfRightToppingIds.isEmpty())
    assertTrue(decoded.modifiersSnapshot.isEmpty())
  }

  @Test
  fun `OrderSummaryDto includes itemPreviewSummary field`() {
    val dto = OrderSummaryDto(
      id = "order-1",
      orderNumber = "PZ-001",
      fulfillmentType = FulfillmentType.DELIVERY,
      scheduleType = ScheduleType.ASAP,
      status = OrderStatus.COMPLETED,
      version = 1,
      totalPaise = 15000L,
      itemCount = 1,
      paymentMethod = PaymentMethod.COD,
      paymentStatus = PaymentStatus.COD_COLLECTED,
      isPriority = false,
      createdAt = "2026-01-01T00:00:00Z",
      expiresAt = null,
      itemPreviewSummary = "Margherita (MEDIUM, Classic Hand Tossed) x1",
    )
    val encoded = json.encodeToString(dto)
    assertTrue(encoded.contains("\"item_preview_summary\":\"Margherita (MEDIUM, Classic Hand Tossed) x1\""))
    val decoded = json.decodeFromString<OrderSummaryDto>(encoded)
    assertEquals("Margherita (MEDIUM, Classic Hand Tossed) x1", decoded.itemPreviewSummary)
  }

  @Test
  fun `OrderSummaryDto backward compatible - old JSON without itemPreviewSummary deserializes`() {
    val oldJson = """
      {
        "id": "order-1",
        "order_number": "PZ-001",
        "fulfillment_type": "DELIVERY",
        "schedule_type": "ASAP",
        "status": "COMPLETED",
        "version": 1,
        "total_paise": 15000,
        "item_count": 1,
        "payment_method": "COD",
        "payment_status": "COD_COLLECTED",
        "is_priority": false,
        "created_at": "2026-01-01T00:00:00Z"
      }
    """.trimIndent()
    val decoded = json.decodeFromString<OrderSummaryDto>(oldJson)
    assertEquals("order-1", decoded.id)
    assertNull(decoded.itemPreviewSummary)
  }

  @Test
  fun `CartValidationIssueCode includes new Phase 8 entries`() {
    // Test that new enum values serialize correctly
    val codes = listOf(
      CartValidationIssueCode.CHEESE_UNAVAILABLE,
      CartValidationIssueCode.SAUCE_UNAVAILABLE,
      CartValidationIssueCode.STANDARD_TOPPING_UNAVAILABLE,
      CartValidationIssueCode.HALF_AND_HALF_UNAVAILABLE,
      CartValidationIssueCode.HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED,
      CartValidationIssueCode.HALF_AND_HALF_LEFT_RIGHT_OVERLAP,
      CartValidationIssueCode.STANDARD_TOPPING_NOT_IN_PRODUCT,
      CartValidationIssueCode.CHEESE_NOT_IN_PRODUCT,
      CartValidationIssueCode.SAUCE_NOT_IN_PRODUCT,
      CartValidationIssueCode.HALF_AND_HALF_CONFIG_NOT_IN_PRODUCT,
    )
    for (code in codes) {
      val encoded = json.encodeToString(code)
      assertTrue(encoded.startsWith("\"") && encoded.endsWith("\""))
      val decoded = json.decodeFromString<CartValidationIssueCode>(encoded)
      assertEquals(code, decoded)
    }
  }

  @Test
  fun `ReorderResponseDto matches contract example shape`() {
    val response = ReorderResponseDto(
      orderId = "order-1",
      canReorder = true,
      items = listOf(
        ReorderItemDto(
          orderItemId = "item-1",
          productId = "prod-1",
          productName = "Margherita",
          variantId = "var-1",
          crustId = "crust-1",
          cheeseId = "cheese-1",
          sauceId = "sauce-1",
          toppingIds = listOf("top-1"),
          standardToppingIds = listOf("std-1"),
          standardToppingRemovedIds = listOf("std-2"),
          halfAndHalfConfigId = "hah-1",
          halfAndHalfLeftToppingIds = listOf("top-1"),
          halfAndHalfRightToppingIds = listOf("top-2"),
          quantity = 1,
          isAvailable = true,
          issues = emptyList(),
        )
      ),
      unavailable = listOf(
        ReorderUnavailableDto(
          orderItemId = "item-2",
          productName = "Margherita",
          modifierType = "CHEESE",
          modifierId = "cheese-2",
          modifierName = "Vegan Cheese",
          reason = ReorderUnavailableReason.CHEESE_UNAVAILABLE,
        )
      ),
      warnings = listOf("Historical order: cheese/sauce could not be reconstructed."),
    )
    val encoded = json.encodeToString(response)
    // Verify JSON shape matches contract §6.3 example
    assertTrue(encoded.contains("\"orderId\":\"order-1\""))
    assertTrue(encoded.contains("\"canReorder\":true"))
    assertTrue(encoded.contains("\"items\":"))
    assertTrue(encoded.contains("\"unavailable\":"))
    assertTrue(encoded.contains("\"warnings\":"))
    assertTrue(encoded.contains("\"reason\":\"CHEESE_UNAVAILABLE\""))
    val decoded = json.decodeFromString<ReorderResponseDto>(encoded)
    assertEquals(response, decoded)
  }

  @Test
  fun `ReorderUnavailableReason serializes with SCREAMING_SNAKE_CASE`() {
    val reason = ReorderUnavailableReason.CHEESE_UNAVAILABLE
    val encoded = json.encodeToString(reason)
    assertEquals("\"CHEESE_UNAVAILABLE\"", encoded)
    val decoded = json.decodeFromString<ReorderUnavailableReason>(encoded)
    assertEquals(reason, decoded)
  }
}
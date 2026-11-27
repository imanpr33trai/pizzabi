package com.pizzora.backend.pricing

import com.pizzora.backend.cart.CartService
import com.pizzora.backend.core.config.Phase8FeatureFlags
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ForbiddenException
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.core.error.UnprocessableEntityException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.modifiers.ModifierCatalog
import com.pizzora.backend.modifiers.ModifierSelection
import com.pizzora.backend.modifiers.Phase8ModifierPricing
import com.pizzora.backend.modifiers.Phase8Schema
import com.pizzora.contracts.AppliedCouponSummaryDto
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CartToppingViewDto
import com.pizzora.contracts.CartValidationIssueCode
import com.pizzora.contracts.CartValidationIssueDto
import com.pizzora.contracts.DeliveryZoneQuoteDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.Money
import com.pizzora.contracts.PricedLineInput
import com.pizzora.contracts.PricingEngine
import com.pizzora.contracts.PricingQuoteRequest
import com.pizzora.contracts.PricingQuoteResponse
import com.pizzora.contracts.SizeCode
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class PricingService(
  private val dataSource: DataSource,
  private val couponService: CouponService,
  private val publicUrlResolver: (String) -> String?,
  private val features: Phase8FeatureFlags = Phase8FeatureFlags(),
) {
  suspend fun quotePrice(
    userId: UUID,
    request: PricingQuoteRequest,
  ): PricingQuoteResponse {
    if (request.items.isEmpty()) {
      throw ValidationException("EMPTY_CART", "At least one cart item is required for a pricing quote.")
    }
    if (request.items.size > CartService.MAX_CART_LINE_ITEMS) {
      throw ValidationException(
        "CART_LIMIT_EXCEEDED",
        "Cart cannot contain more than ${CartService.MAX_CART_LINE_ITEMS} distinct line items.",
      )
    }

    val parsedItems = request.items.mapIndexed { index, raw ->
      if (raw.quantity !in 1..CartService.MAX_ITEM_QUANTITY) {
        throw ValidationException(
          "INVALID_QUANTITY",
          "Quantity for item #$index must be between 1 and ${CartService.MAX_ITEM_QUANTITY}.",
        )
      }
      ParsedQuoteItem(
        itemId = raw.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
        productId = parseUuid(raw.productId, "product_id"),
        variantId = parseUuid(raw.variantId, "variant_id"),
        crustId = parseUuid(raw.crustId, "crust_id"),
        toppingIds = raw.toppingIds.map { parseUuid(it, "topping_id") }.distinct().sortedBy { it.toString() },
        quantity = raw.quantity,
        modifiers = ModifierSelection(
          cheeseId = raw.cheeseId?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "cheese_id") },
          sauceId = raw.sauceId?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "sauce_id") },
          standardToppingIds = raw.standardToppingIds.map { parseUuid(it, "standard_topping_id") }
            .distinct().sortedBy { it.toString() },
          halfAndHalfConfigId = raw.halfAndHalfConfigId?.takeIf { it.isNotBlank() }
            ?.let { parseUuid(it, "half_and_half_config_id") },
          halfAndHalfLeftToppingIds = raw.halfAndHalfLeftToppingIds.map { parseUuid(it, "half_and_half_left_topping_id") }
            .distinct().sortedBy { it.toString() },
          halfAndHalfRightToppingIds = raw.halfAndHalfRightToppingIds.map { parseUuid(it, "half_and_half_right_topping_id") }
            .distinct().sortedBy { it.toString() },
        ),
      )
    }
    parsedItems.forEach { Phase8ModifierPricing.rejectDisabledWrites(it.modifiers, features) }

    val addressUuid = request.addressId?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "address_id") }

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val settings = loadSettings(conn)
      val evaluated = evaluateItemsForQuote(conn, parsedItems)
      val firstUnavailable = evaluated.firstOrNull { !it.view.isAvailable }
      if (firstUnavailable != null) {
        val issue = firstUnavailable.view.issues.first()
        throw UnprocessableEntityException(
          code = "CART_ITEMS_UNAVAILABLE",
          message = issue.message,
          details = mapOf("issue_code" to issue.code.name, "product_id" to firstUnavailable.view.productId),
        )
      }

      val pricedLines = evaluated.map { it.lineInput }
      val rawSubtotal = pricedLines.fold(0L) { acc, line ->
        Math.addExact(acc, PricingEngine.calculateLineTotalPaise(line.unitPricePaise, line.quantity))
      }

      var deliveryZone: DeliveryZoneQuoteDto? = null
      var deliveryFeePaise = 0L

      if (request.fulfillmentType == FulfillmentType.DELIVERY) {
        val rawPincode = request.pincode
        val resolvedPincode = when {
          !rawPincode.isNullOrBlank() -> rawPincode.trim()
          addressUuid != null -> loadOwnedAddressPincode(conn, addressUuid, userId)
          else -> null
        }

        if (resolvedPincode != null) {
          val zone = loadActiveDeliveryZone(conn, resolvedPincode)
            ?: throw UnprocessableEntityException(
              code = "ZONE_UNSERVICEABLE",
              message = "Delivery is not available for pincode $resolvedPincode.",
              details = mapOf("pincode" to resolvedPincode),
            )
          if (rawSubtotal < zone.minOrderPaise) {
            throw UnprocessableEntityException(
              code = "DELIVERY_MIN_ORDER_NOT_MET",
              message = "Minimum order for ${zone.areaName} (${zone.pincode}) is ${Money.formatInr(zone.minOrderPaise)}.",
              details = mapOf("min_order_paise" to zone.minOrderPaise.toString()),
            )
          }
          deliveryZone = zone
          deliveryFeePaise = zone.deliveryFeePaise
        }
      }

      val validatedCoupon = request.couponCode
        ?.takeIf { it.isNotBlank() }
        ?.let { code -> couponService.validateCouponForQuote(conn, code, userId, rawSubtotal) }

      val modifierBreakdown = calculateModifierBreakdown(evaluated)
      // PricingEngine owns the tax/discount totals; the explicit modifier fields are derived
      // from the resolved per-item views so Kotlin matches the Rust quote response.
      val breakdown = PricingEngine.calculateQuote(
        lines = pricedLines,
        gstBps = settings.gstBps,
        deliveryFeePaise = deliveryFeePaise,
        coupon = validatedCoupon?.spec,
      ).copy(
        cheeseExtraPaise = modifierBreakdown.cheeseExtraPaise,
        sauceExtraPaise = modifierBreakdown.sauceExtraPaise,
        standardToppingsExtraPaise = modifierBreakdown.standardToppingsExtraPaise,
        halfAndHalfSurchargePaise = modifierBreakdown.halfAndHalfSurchargePaise,
        modifierExtraPaise = modifierBreakdown.modifierExtraPaise,
      )

      val appliedCoupon = validatedCoupon?.let {
        AppliedCouponSummaryDto(
          id = it.id.toString(),
          code = it.code,
          discountType = it.spec.discountType,
          discountValue = it.spec.discountValue,
          discountPaise = breakdown.discountPaise,
        )
      }

      PricingQuoteResponse(
        items = evaluated.map { it.view },
        fulfillmentType = request.fulfillmentType,
        breakdown = breakdown,
        appliedCoupon = appliedCoupon,
        deliveryZone = deliveryZone,
        isAcceptingOrders = settings.isAcceptingOrders,
        gstBps = settings.gstBps,
      )
    }
  }

  private fun calculateModifierBreakdown(items: List<EvaluatedQuoteItem>): ModifierBreakdown {
    var cheese = 0L
    var sauce = 0L
    var standards = 0L
    var halfAndHalf = 0L

    items.forEach { item ->
      val quantity = item.view.quantity
      cheese = checkedAdd(cheese, checkedMultiply(item.view.cheese?.extraPricePaise ?: 0L, quantity))
      sauce = checkedAdd(sauce, checkedMultiply(item.view.sauce?.extraPricePaise ?: 0L, quantity))

      // Only selected, non-default standard toppings contribute to the price; catalog views
      // include every option on the product, so the selection set from evaluation disambiguates.
      val itemStandards = item.view.standardToppings
        .filter { view ->
          val id = runCatching { UUID.fromString(view.standardToppingId) }.getOrNull()
          !view.isRemoved && !view.isDefault && id in item.selectedStandardToppingIds
        }
        .fold(0L) { acc, view -> checkedAdd(acc, view.extraPricePaise) }
      standards = checkedAdd(standards, checkedMultiply(itemStandards, quantity))

      halfAndHalf = checkedAdd(
        halfAndHalf,
        checkedMultiply(item.view.halfAndHalf?.halfAndHalfSurchargePaise ?: 0L, quantity),
      )
    }

    val total = checkedAdd(checkedAdd(checkedAdd(cheese, sauce), standards), halfAndHalf)
    return ModifierBreakdown(cheese, sauce, standards, halfAndHalf, total)
  }

  private fun checkedMultiply(value: Long, quantity: Int): Long =
    runCatching { Math.multiplyExact(value, quantity.toLong()) }.getOrElse { throw modifierOverflow() }

  private fun checkedAdd(left: Long, right: Long): Long =
    runCatching { Math.addExact(left, right) }.getOrElse { throw modifierOverflow() }

  private fun modifierOverflow(): ValidationException = ValidationException(
    "ARITHMETIC_OVERFLOW",
    "Extended modifier total exceeds the supported range.",
  )

  private fun loadSettings(conn: Connection): StoreSettings =
    conn.prepareStatement(
      "SELECT gst_bps, is_accepting_orders FROM restaurant_settings WHERE id = 1",
    ).use { ps ->
      ps.executeQuery().use { rs ->
        if (!rs.next()) StoreSettings(gstBps = 500, isAcceptingOrders = true)
        else StoreSettings(
          gstBps = rs.getInt("gst_bps"),
          isAcceptingOrders = rs.getBoolean("is_accepting_orders"),
        )
      }
    }

  private fun loadOwnedAddressPincode(conn: Connection, addressId: UUID, userId: UUID): String =
    conn.prepareStatement(
      "SELECT user_id, pincode FROM addresses WHERE id = ?",
    ).use { ps ->
      ps.setObject(1, addressId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) {
          throw NotFoundException("ADDRESS_NOT_FOUND", "Delivery address not found.")
        }
        val ownerId = rs.getObject("user_id", UUID::class.java)
        if (ownerId != userId) {
          throw ForbiddenException("FORBIDDEN", "Delivery address does not belong to current user.")
        }
        rs.getString("pincode").trim()
      }
    }

  private fun loadActiveDeliveryZone(conn: Connection, pincode: String): DeliveryZoneQuoteDto? =
    conn.prepareStatement(
      """
      SELECT pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins
      FROM delivery_zones
      WHERE pincode = ? AND is_active = TRUE
      """.trimIndent(),
    ).use { ps ->
      ps.setString(1, pincode)
      ps.executeQuery().use { rs ->
        if (!rs.next()) null
        else DeliveryZoneQuoteDto(
          pincode = rs.getString("pincode"),
          areaName = rs.getString("area_name"),
          minOrderPaise = rs.getLong("min_order_paise"),
          deliveryFeePaise = rs.getLong("delivery_fee_paise"),
          estimatedMins = rs.getInt("estimated_mins"),
        )
      }
    }

  private fun evaluateItemsForQuote(
    conn: Connection,
    inputs: List<ParsedQuoteItem>,
  ): List<EvaluatedQuoteItem> {
    val productsMap = mutableMapOf<UUID, ProductRow>()
    conn.prepareStatement(
      """
      SELECT p.id, p.name, p.is_veg, p.is_taxable,
             (p.is_active AND c.is_active) AS effective_active
      FROM products p
      JOIN categories c ON c.id = p.category_id
      """.trimIndent(),
    ).use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val id = rs.getObject("id", UUID::class.java)
          productsMap[id] = ProductRow(
            id = id,
            name = rs.getString("name"),
            isVeg = rs.getBoolean("is_veg"),
            isTaxable = rs.getBoolean("is_taxable"),
            isActive = rs.getBoolean("effective_active"),
          )
        }
      }
    }

    val productImageMap = mutableMapOf<UUID, String>()
    conn.prepareStatement(
      """
      SELECT DISTINCT ON (product_id) product_id, object_key
      FROM product_images
      WHERE status = 'ACTIVE'
      ORDER BY product_id, sort_order ASC, created_at ASC
      """.trimIndent(),
    ).use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val resolvedUrl = publicUrlResolver(rs.getString("object_key"))?.trim()?.takeIf { it.isNotEmpty() }
          if (resolvedUrl != null) {
            productImageMap[rs.getObject("product_id", UUID::class.java)] = resolvedUrl
          }
        }
      }
    }

    val variantsMap = mutableMapOf<UUID, VariantRow>()
    conn.prepareStatement(
      "SELECT id, product_id, size_code, price_paise, is_active FROM product_variants",
    ).use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val id = rs.getObject("id", UUID::class.java)
          variantsMap[id] = VariantRow(
            id = id,
            productId = rs.getObject("product_id", UUID::class.java),
            sizeCode = SizeCode.valueOf(rs.getString("size_code")),
            pricePaise = rs.getLong("price_paise"),
            isActive = rs.getBoolean("is_active"),
          )
        }
      }
    }

    val crustsMap = mutableMapOf<UUID, CrustRow>()
    conn.prepareStatement(
      "SELECT id, name, extra_price_paise, is_active FROM crust_options",
    ).use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val id = rs.getObject("id", UUID::class.java)
          crustsMap[id] = CrustRow(
            id = id,
            name = rs.getString("name"),
            extraPricePaise = rs.getLong("extra_price_paise"),
            isActive = rs.getBoolean("is_active"),
          )
        }
      }
    }

    val toppingsMap = mutableMapOf<UUID, ToppingRow>()
    conn.prepareStatement(
      "SELECT id, name, is_veg, extra_price_paise, is_active FROM toppings",
    ).use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val id = rs.getObject("id", UUID::class.java)
          toppingsMap[id] = ToppingRow(
            id = id,
            name = rs.getString("name"),
            isVeg = rs.getBoolean("is_veg"),
            extraPricePaise = rs.getLong("extra_price_paise"),
            isActive = rs.getBoolean("is_active"),
          )
        }
      }
    }

    val schema = Phase8Schema.detect(conn)
    val modifierCatalog = ModifierCatalog.load(conn, schema, features)

    return inputs.map { input ->
      val issues = mutableListOf<CartValidationIssueDto>()
      val prod = productsMap[input.productId]
      val variant = variantsMap[input.variantId]
      val crust = crustsMap[input.crustId]

      if (prod == null || !prod.isActive) {
        issues += CartValidationIssueDto(
          code = CartValidationIssueCode.PRODUCT_UNAVAILABLE,
          message = "${prod?.name ?: "Pizza"} is currently unavailable.",
        )
      }
      if (variant == null || !variant.isActive) {
        issues += CartValidationIssueDto(
          code = CartValidationIssueCode.VARIANT_UNAVAILABLE,
          message = "Selected size ${variant?.sizeCode?.name ?: ""} is currently unavailable.".trim(),
        )
      } else if (variant.productId != input.productId) {
        issues += CartValidationIssueDto(
          code = CartValidationIssueCode.VARIANT_PRODUCT_MISMATCH,
          message = "Selected size does not belong to this pizza.",
        )
      }
      if (crust == null || !crust.isActive) {
        issues += CartValidationIssueDto(
          code = CartValidationIssueCode.CRUST_UNAVAILABLE,
          message = "${crust?.name ?: "Selected crust"} is currently unavailable.",
        )
      }

      val toppingViews = input.toppingIds.map { tid ->
        val row = toppingsMap[tid]
        val available = row != null && row.isActive
        if (!available) {
          issues += CartValidationIssueDto(
            code = CartValidationIssueCode.TOPPING_UNAVAILABLE,
            message = "${row?.name ?: "Selected topping"} is no longer available.",
          )
        }
        CartToppingViewDto(
          toppingId = tid.toString(),
          name = row?.name ?: "Unavailable Topping",
          isVeg = row?.isVeg ?: true,
          extraPricePaise = row?.extraPricePaise ?: 0L,
          isAvailable = available,
        )
      }

      val modifiers = Phase8ModifierPricing.evaluate(
        productId = input.productId,
        selection = input.modifiers,
        extraToppingIds = input.toppingIds,
        catalog = modifierCatalog,
      )
      issues += modifiers.issues

      val basePrice = variant?.pricePaise ?: 0L
      val crustPrice = crust?.extraPricePaise ?: 0L
      val unitPrice = modifiers.unitPricePaise(
        variantPricePaise = basePrice,
        crustExtraPaise = crustPrice,
        extraToppingIds = input.toppingIds,
        catalog = modifierCatalog,
      )
      val lineTotal = PricingEngine.calculateLineTotalPaise(unitPrice, input.quantity)

      EvaluatedQuoteItem(
        selectedStandardToppingIds = modifiers.selectedStandardToppingIds,
        view = CartItemViewDto(
          id = input.itemId,
          productId = input.productId.toString(),
          productName = prod?.name ?: "Unavailable Pizza",
          isVeg = prod?.isVeg ?: true,
          imageUrl = productImageMap[input.productId],
          variantId = input.variantId.toString(),
          sizeCode = variant?.sizeCode,
          basePricePaise = basePrice,
          crustId = input.crustId.toString(),
          crustName = crust?.name ?: "Unavailable Crust",
          crustExtraPricePaise = crustPrice,
          toppings = toppingViews,
          quantity = input.quantity,
          unitPricePaise = unitPrice,
          lineTotalPaise = lineTotal,
          isAvailable = issues.isEmpty(),
          issues = issues,
          cheese = modifiers.cheeseView,
          sauce = modifiers.sauceView,
          standardToppings = modifiers.standardToppingViews,
          halfAndHalf = modifiers.halfAndHalfView,
        ),
        lineInput = PricedLineInput(
          unitPricePaise = unitPrice,
          quantity = input.quantity,
          isTaxable = prod?.isTaxable ?: true,
        ),
      )
    }
  }

  private fun parseUuid(raw: String, field: String): UUID =
    runCatching { UUID.fromString(raw) }
      .getOrElse { throw ValidationException("INVALID_UUID", "Invalid $field UUID: $raw") }

  private data class ParsedQuoteItem(
    val itemId: String,
    val productId: UUID,
    val variantId: UUID,
    val crustId: UUID,
    val toppingIds: List<UUID>,
    val quantity: Int,
    val modifiers: ModifierSelection = ModifierSelection(),
  )

  private data class EvaluatedQuoteItem(
    val view: CartItemViewDto,
    val lineInput: PricedLineInput,
    val selectedStandardToppingIds: Set<UUID>,
  )

  private data class ModifierBreakdown(
    val cheeseExtraPaise: Long,
    val sauceExtraPaise: Long,
    val standardToppingsExtraPaise: Long,
    val halfAndHalfSurchargePaise: Long,
    val modifierExtraPaise: Long,
  )

  private data class StoreSettings(val gstBps: Int, val isAcceptingOrders: Boolean)
  private data class ProductRow(
    val id: UUID,
    val name: String,
    val isVeg: Boolean,
    val isTaxable: Boolean,
    val isActive: Boolean,
  )
  private data class VariantRow(
    val id: UUID,
    val productId: UUID,
    val sizeCode: SizeCode,
    val pricePaise: Long,
    val isActive: Boolean,
  )
  private data class CrustRow(val id: UUID, val name: String, val extraPricePaise: Long, val isActive: Boolean)
  private data class ToppingRow(
    val id: UUID,
    val name: String,
    val isVeg: Boolean,
    val extraPricePaise: Long,
    val isActive: Boolean,
  )
}

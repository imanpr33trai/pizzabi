package com.pizzora.backend.checkout

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
import com.pizzora.backend.cart.CartService
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ConflictException
import com.pizzora.backend.core.error.ForbiddenException
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.backend.core.error.UnprocessableEntityException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.inventory.InventoryService
import com.pizzora.backend.orders.OrderService
import com.pizzora.backend.payments.RazorpayReceiptFormatter
import com.pizzora.backend.recipes.RecipeDemandItem
import com.pizzora.backend.recipes.RecipeService
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.DeliveryAddressSnapshotDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.Money
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PricedLineInput
import com.pizzora.contracts.PricingEngine
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CheckoutService(
  private val dataSource: DataSource,
  private val couponService: CouponService,
  private val inventoryService: InventoryService,
  private val recipeService: RecipeService,
  private val orderService: OrderService,
) {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  suspend fun checkout(
    customerId: UUID,
    rawIdempotencyKey: String?,
    request: CheckoutRequest,
    audit: AuditContext,
  ): CheckoutResponse {
    val idempotencyKey = rawIdempotencyKey?.trim().orEmpty()
    if (idempotencyKey.isEmpty() || idempotencyKey.length > 128) {
      throw ValidationException("MISSING_IDEMPOTENCY_KEY", "Idempotency-Key header (1..128 chars) is required.")
    }

    if (request.items.isEmpty()) {
      throw ValidationException("EMPTY_CART", "At least one pizza item is required to checkout.")
    }
    if (request.items.size > CartService.MAX_CART_LINE_ITEMS) {
      throw ValidationException(
        "CART_LIMIT_EXCEEDED",
        "Order cannot contain more than ${CartService.MAX_CART_LINE_ITEMS} distinct line items.",
      )
    }

    val parsedItems = request.items.mapIndexed { index, raw ->
      if (raw.quantity !in 1..CartService.MAX_ITEM_QUANTITY) {
        throw ValidationException(
          "INVALID_QUANTITY",
          "Quantity for item #$index must be between 1 and ${CartService.MAX_ITEM_QUANTITY}.",
        )
      }
      ParsedCheckoutItem(
        productId = parseUuid(raw.productId, "product_id"),
        variantId = parseUuid(raw.variantId, "variant_id"),
        crustId = parseUuid(raw.crustId, "crust_id"),
        toppingIds = raw.toppingIds.map { parseUuid(it, "topping_id") }.distinct().sortedBy { it.toString() },
        quantity = raw.quantity,
      )
    }

    val rawAddressId = request.addressId?.takeIf { it.isNotBlank() }
    val addressUuid = rawAddressId?.let { parseUuid(it, "address_id") }
    if (request.fulfillmentType == FulfillmentType.DELIVERY && addressUuid == null) {
      throw ValidationException("ADDRESS_REQUIRED", "address_id is required for DELIVERY orders.")
    }

    val rawSlotId = request.scheduledSlotId?.takeIf { it.isNotBlank() }
    val scheduledSlotUuid = rawSlotId?.let { parseUuid(it, "scheduled_slot_id") }
    if (request.scheduleType == ScheduleType.SCHEDULED && scheduledSlotUuid == null) {
      throw ValidationException("SCHEDULED_SLOT_REQUIRED", "scheduled_slot_id is required for SCHEDULED orders.")
    }
    if (request.scheduleType == ScheduleType.ASAP && scheduledSlotUuid != null) {
      throw ValidationException("UNEXPECTED_SCHEDULED_SLOT", "scheduled_slot_id must be omitted for ASAP orders.")
    }

    val normalizedCoupon = request.couponCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    val requestHash = computeRequestHash(
      items = parsedItems,
      fulfillmentType = request.fulfillmentType,
      scheduleType = request.scheduleType,
      scheduledSlotId = scheduledSlotUuid,
      addressId = addressUuid,
      paymentMethod = request.paymentMethod,
      couponCode = normalizedCoupon,
    )

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      // Serialize concurrent requests sharing the same (customer_id, idempotency_key)
      conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))").use { ps ->
        ps.setString(1, customerId.toString())
        ps.setString(2, idempotencyKey)
        ps.execute()
      }

      val existing = conn.prepareStatement(
        "SELECT id, request_hash FROM orders WHERE customer_id = ? AND idempotency_key = ?",
      ).use { ps ->
        ps.setObject(1, customerId)
        ps.setString(2, idempotencyKey)
        ps.executeQuery().use { rs ->
          if (!rs.next()) null
          else rs.getObject("id", UUID::class.java) to rs.getString("request_hash")
        }
      }

      if (existing != null) {
        if (existing.second != requestHash) {
          throw ConflictException(
            "IDEMPOTENCY_CONFLICT",
            "Idempotency-Key '$idempotencyKey' was already used with a different checkout request.",
          )
        }
        val detail = orderService.loadOrderDetail(conn, existing.first)
          ?: error("Idempotent order ${existing.first} missing details")
        return@withJdbcTransaction CheckoutResponse(order = detail, idempotentReplay = true)
      }

      val settings = loadSettings(conn)
      if (!settings.isAcceptingOrders) {
        throw ConflictException("STORE_CLOSED", "Kitchen is currently closed and not accepting orders.")
      }

      val evaluatedItems = evaluateCatalogItems(conn, parsedItems)
      val pricedLines = evaluatedItems.map { it.lineInput }
      val rawSubtotal = pricedLines.fold(0L) { acc, line ->
        Math.addExact(acc, PricingEngine.calculateLineTotalPaise(line.unitPricePaise, line.quantity))
      }

      var deliveryFeePaise = 0L
      var deliveryAddressSnapshot: DeliveryAddressSnapshotDto? = null
      if (request.fulfillmentType == FulfillmentType.DELIVERY && addressUuid != null) {
        val address = loadOwnedAddress(conn, addressUuid, customerId)
        val zone = loadActiveDeliveryZone(conn, address.pincode)
          ?: throw UnprocessableEntityException(
            code = "ZONE_UNSERVICEABLE",
            message = "Delivery is not available for pincode ${address.pincode}.",
            details = mapOf("pincode" to address.pincode),
          )
        if (rawSubtotal < zone.minOrderPaise) {
          throw UnprocessableEntityException(
            code = "DELIVERY_MIN_ORDER_NOT_MET",
            message = "Minimum order for ${zone.areaName} (${address.pincode}) is ${Money.formatInr(zone.minOrderPaise)}.",
            details = mapOf("min_order_paise" to zone.minOrderPaise.toString()),
          )
        }
        deliveryFeePaise = zone.deliveryFeePaise
        deliveryAddressSnapshot = address
      }

      val isRazorpay = request.paymentMethod == PaymentMethod.RAZORPAY
      val now = Instant.now()
      val expiresAt = if (isRazorpay) now.plusSeconds(PAYMENT_WINDOW_SECONDS) else null
      val initialOrderStatus = if (isRazorpay) OrderStatus.PENDING_PAYMENT else OrderStatus.PLACED

      if (request.scheduleType == ScheduleType.SCHEDULED && scheduledSlotUuid != null) {
        lockAndIncrementScheduledSlot(conn, scheduledSlotUuid, request.fulfillmentType, now)
      }

      val validatedCoupon = normalizedCoupon?.let { code ->
        couponService.validateCouponForQuote(conn, code, customerId, rawSubtotal)
      }
      val breakdown = PricingEngine.calculateQuote(
        lines = pricedLines,
        gstBps = settings.gstBps,
        deliveryFeePaise = deliveryFeePaise,
        coupon = validatedCoupon?.spec,
      )
      if (breakdown.totalPaise <= 0L) {
        throw UnprocessableEntityException("ZERO_ORDER_TOTAL", "Payable order total must be greater than zero.")
      }

      val recipeDemand = recipeService.computeRequiredIngredientsForItems(
        conn = conn,
        items = parsedItems.map {
          RecipeDemandItem(
            variantId = it.variantId,
            crustId = it.crustId,
            toppingIds = it.toppingIds,
            quantity = it.quantity,
          )
        },
      )

      val orderId = UUID.randomUUID()
      val orderNumber = generateOrderNumber(now, orderId)

      // 1. Insert Parent `orders` row FIRST (strict topological FK order)
      conn.prepareStatement(
        """
        INSERT INTO orders (
          id, order_number, customer_id, idempotency_key, request_hash,
          fulfillment_type, schedule_type, scheduled_slot_id, delivery_address_snapshot,
          status, version, subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise,
          gst_bps_snapshot, coupon_code_snapshot, is_priority, expires_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 1, ?, ?, ?, ?, ?, ?, ?, FALSE, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, orderId)
        ps.setString(2, orderNumber)
        ps.setObject(3, customerId)
        ps.setString(4, idempotencyKey)
        ps.setString(5, requestHash)
        ps.setString(6, request.fulfillmentType.name)
        ps.setString(7, request.scheduleType.name)
        if (scheduledSlotUuid != null) ps.setObject(8, scheduledSlotUuid) else ps.setNull(8, Types.OTHER)
        if (deliveryAddressSnapshot != null) {
          ps.setString(9, json.encodeToString(deliveryAddressSnapshot))
        } else {
          ps.setNull(9, Types.VARCHAR)
        }
        ps.setString(10, initialOrderStatus.name)
        ps.setLong(11, breakdown.subtotalPaise)
        ps.setLong(12, breakdown.discountPaise)
        ps.setLong(13, breakdown.deliveryFeePaise)
        ps.setLong(14, breakdown.taxPaise)
        ps.setLong(15, breakdown.totalPaise)
        ps.setInt(16, settings.gstBps)
        if (validatedCoupon != null) ps.setString(17, validatedCoupon.code) else ps.setNull(17, Types.VARCHAR)
        if (expiresAt != null) ps.setTimestamp(18, Timestamp.from(expiresAt)) else ps.setNull(18, Types.TIMESTAMP_WITH_TIMEZONE)
        ps.executeUpdate()
      }

      // 2. Insert `order_items`
      conn.prepareStatement(
        """
        INSERT INTO order_items (
          id, order_id, product_id, product_name_snapshot, variant_size_snapshot,
          crust_name_snapshot, toppings_snapshot, unit_price_paise, quantity, line_total_paise
        ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
        """.trimIndent(),
      ).use { ps ->
        for (item in evaluatedItems) {
          ps.setObject(1, UUID.randomUUID())
          ps.setObject(2, orderId)
          ps.setObject(3, item.productId)
          ps.setString(4, item.productName)
          ps.setString(5, item.variantSize.name)
          ps.setString(6, item.crustName)
          ps.setString(7, json.encodeToString(item.toppingNames))
          ps.setLong(8, item.unitPricePaise)
          ps.setInt(9, item.quantity)
          ps.setLong(10, item.lineTotalPaise)
          ps.addBatch()
        }
        ps.executeBatch()
      }

      // 3. Insert initial `order_status_history`
      conn.prepareStatement(
        """
        INSERT INTO order_status_history (id, order_id, from_status, to_status, changed_by, reason)
        VALUES (?, ?, NULL, ?, ?, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setObject(2, orderId)
        ps.setString(3, initialOrderStatus.name)
        ps.setObject(4, customerId)
        ps.setString(5, if (isRazorpay) "Awaiting online payment" else "COD order placed")
        ps.executeUpdate()
      }

      // 4. Insert `scheduled_slot_reservations` if SCHEDULED
      if (scheduledSlotUuid != null) {
        conn.prepareStatement(
          """
          INSERT INTO scheduled_slot_reservations (id, slot_id, order_id, status, expires_at)
          VALUES (?, ?, ?, ?, ?)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, UUID.randomUUID())
          ps.setObject(2, scheduledSlotUuid)
          ps.setObject(3, orderId)
          ps.setString(4, if (isRazorpay) "ACTIVE" else "COMMITTED")
          if (expiresAt != null) ps.setTimestamp(5, Timestamp.from(expiresAt)) else ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE)
          ps.executeUpdate()
        }
      }

      // 5. Reserve or immediately commit ingredient inventory (sorted ascending locking + ledger entries)
      inventoryService.reserveForOrder(
        conn = conn,
        orderId = orderId,
        requiredByIngredient = recipeDemand,
        expiresAt = expiresAt,
        immediateCommit = !isRazorpay,
        actorUserId = customerId,
      )

      // 6. Lock coupon & record reservation / immediate COD redemption
      if (normalizedCoupon != null) {
        couponService.reserveCouponForOrder(
          conn = conn,
          rawCode = normalizedCoupon,
          userId = customerId,
          orderId = orderId,
          subtotalPaise = rawSubtotal,
          expiresAt = expiresAt,
          immediateCommit = !isRazorpay,
        )
      }

      // 7. Insert `payments` and initial `payment_attempts`
      val paymentId = UUID.randomUUID()
      val paymentStatus = if (isRazorpay) PaymentStatus.UNPAID else PaymentStatus.COD_PENDING
      conn.prepareStatement(
        """
        INSERT INTO payments (id, order_id, method, status, amount_paise, captured_paise, refunded_paise, currency)
        VALUES (?, ?, ?, ?, ?, 0, 0, 'INR')
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, paymentId)
        ps.setObject(2, orderId)
        ps.setString(3, request.paymentMethod.name)
        ps.setString(4, paymentStatus.name)
        ps.setLong(5, breakdown.totalPaise)
        ps.executeUpdate()
      }

      val attemptId = UUID.randomUUID()
      val attemptStatus = if (isRazorpay) "INITIATING" else "AWAITING_CUSTOMER_PAYMENT"
      val receipt = if (isRazorpay) {
        RazorpayReceiptFormatter.formatReceipt(orderId, attemptId.toString())
      } else {
        "cod:$attemptId"
      }
      conn.prepareStatement(
        """
        INSERT INTO payment_attempts (
          id, payment_id, attempt_number, method, status, amount_paise, idempotency_receipt, expires_at
        ) VALUES (?, ?, 1, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, attemptId)
        ps.setObject(2, paymentId)
        ps.setString(3, request.paymentMethod.name)
        ps.setString(4, attemptStatus)
        ps.setLong(5, breakdown.totalPaise)
        ps.setString(6, receipt)
        if (expiresAt != null) ps.setTimestamp(7, Timestamp.from(expiresAt)) else ps.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE)
        ps.executeUpdate()
      }

      // 8. Insert Outbox Event (`stream_seq = NULL`, `dispatch_status = 'PENDING'`, `published_at = NULL`)
      val eventType = if (isRazorpay) "PAYMENT_GATEWAY_ORDER_REQUESTED" else "ORDER_PLACED"
      val payloadJson = buildJsonObject {
        put("order_id", orderId.toString())
        put("order_number", orderNumber)
        put("customer_id", customerId.toString())
        put("payment_id", paymentId.toString())
        put("payment_attempt_id", attemptId.toString())
        put("attempt_number", 1)
        put("payment_method", request.paymentMethod.name)
        put("amount_paise", breakdown.totalPaise)
        put("receipt", receipt)
        put("fulfillment_type", request.fulfillmentType.name)
        put("status", initialOrderStatus.name)
      }.toString()

      orderService.insertOutboxEvent(
        conn = conn,
        aggregateType = "ORDER",
        aggregateId = orderId,
        aggregateVersion = 1L,
        eventType = eventType,
        payloadJson = payloadJson,
      )

      if (request.clearCart) {
        conn.prepareStatement(
          "DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE user_id = ?)",
        ).use { ps ->
          ps.setObject(1, customerId)
          ps.executeUpdate()
        }
      }

      AuditLogger.record(
        conn = conn,
        context = audit,
        action = "ORDER_CHECKOUT_CREATED",
        entityType = "order",
        entityId = orderId.toString(),
        afterJson = "{\"order_number\":\"$orderNumber\",\"status\":\"${initialOrderStatus.name}\",\"payment_method\":\"${request.paymentMethod.name}\",\"total_paise\":${breakdown.totalPaise}}",
      )

      val createdDetail = orderService.loadOrderDetail(conn, orderId)!!
      CheckoutResponse(order = createdDetail, idempotentReplay = false)
    }
  }

  private fun lockAndIncrementScheduledSlot(
    conn: Connection,
    slotId: UUID,
    requestedFulfillment: FulfillmentType,
    now: Instant,
  ) {
    val slot = conn.prepareStatement(
      """
      SELECT id, fulfillment_type, cutoff_at, max_capacity, reserved_count, is_active
      FROM scheduled_slots
      WHERE id = ?
      FOR UPDATE
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, slotId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) throw NotFoundException("SLOT_NOT_FOUND", "Scheduled slot $slotId not found.")
        LockedSlot(
          id = rs.getObject("id", UUID::class.java),
          fulfillmentType = FulfillmentType.valueOf(rs.getString("fulfillment_type")),
          cutoffAt = rs.getObject("cutoff_at", OffsetDateTime::class.java).toInstant(),
          maxCapacity = rs.getInt("max_capacity"),
          reservedCount = rs.getInt("reserved_count"),
          isActive = rs.getBoolean("is_active"),
        )
      }
    }

    if (!slot.isActive || slot.fulfillmentType != requestedFulfillment) {
      throw UnprocessableEntityException("SLOT_UNAVAILABLE", "Selected scheduled slot is not available for $requestedFulfillment.")
    }
    if (!slot.cutoffAt.isAfter(now)) {
      throw UnprocessableEntityException("SLOT_CUTOFF_PASSED", "Booking cutoff time for this slot has passed.")
    }
    if (slot.reservedCount >= slot.maxCapacity) {
      throw ConflictException("SLOT_FULL", "Selected scheduled slot has reached full capacity.")
    }

    conn.prepareStatement("UPDATE scheduled_slots SET reserved_count = reserved_count + 1 WHERE id = ?").use { ps ->
      ps.setObject(1, slotId)
      ps.executeUpdate()
    }
  }

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

  private fun loadOwnedAddress(
    conn: Connection,
    addressId: UUID,
    customerId: UUID,
  ): DeliveryAddressSnapshotDto =
    conn.prepareStatement(
      "SELECT id, user_id, label, line1, line2, landmark, pincode, city FROM addresses WHERE id = ?",
    ).use { ps ->
      ps.setObject(1, addressId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) throw NotFoundException("ADDRESS_NOT_FOUND", "Delivery address not found.")
        val ownerId = rs.getObject("user_id", UUID::class.java)
        if (ownerId != customerId) {
          throw ForbiddenException("FORBIDDEN", "Delivery address does not belong to current user.")
        }
        DeliveryAddressSnapshotDto(
          addressId = rs.getObject("id", UUID::class.java).toString(),
          label = rs.getString("label"),
          line1 = rs.getString("line1"),
          line2 = rs.getString("line2"),
          landmark = rs.getString("landmark"),
          pincode = rs.getString("pincode").trim(),
          city = rs.getString("city"),
        )
      }
    }

  private fun loadActiveDeliveryZone(conn: Connection, pincode: String): ActiveDeliveryZone? =
    conn.prepareStatement(
      """
      SELECT area_name, min_order_paise, delivery_fee_paise
      FROM delivery_zones
      WHERE pincode = ? AND is_active = TRUE
      """.trimIndent(),
    ).use { ps ->
      ps.setString(1, pincode)
      ps.executeQuery().use { rs ->
        if (!rs.next()) null
        else ActiveDeliveryZone(
          areaName = rs.getString("area_name"),
          minOrderPaise = rs.getLong("min_order_paise"),
          deliveryFeePaise = rs.getLong("delivery_fee_paise"),
        )
      }
    }

  private fun evaluateCatalogItems(
    conn: Connection,
    items: List<ParsedCheckoutItem>,
  ): List<EvaluatedCheckoutItem> {
    val productsMap = mutableMapOf<UUID, ProductRow>()
    conn.prepareStatement(
      """
      SELECT p.id, p.name, p.is_taxable, (p.is_active AND c.is_active) AS effective_active
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
            isTaxable = rs.getBoolean("is_taxable"),
            isActive = rs.getBoolean("effective_active"),
          )
        }
      }
    }

    val variantsMap = mutableMapOf<UUID, VariantRow>()
    conn.prepareStatement("SELECT id, product_id, size_code, price_paise, is_active FROM product_variants").use { ps ->
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
    conn.prepareStatement("SELECT id, name, extra_price_paise, is_active FROM crust_options").use { ps ->
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
    conn.prepareStatement("SELECT id, name, extra_price_paise, is_active FROM toppings").use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val id = rs.getObject("id", UUID::class.java)
          toppingsMap[id] = ToppingRow(
            id = id,
            name = rs.getString("name"),
            extraPricePaise = rs.getLong("extra_price_paise"),
            isActive = rs.getBoolean("is_active"),
          )
        }
      }
    }

    return items.map { input ->
      val prod = productsMap[input.productId]
      if (prod == null || !prod.isActive) {
        throw UnprocessableEntityException(
          "CART_ITEMS_UNAVAILABLE",
          "${prod?.name ?: "Pizza"} is currently unavailable.",
        )
      }
      val variant = variantsMap[input.variantId]
      if (variant == null || !variant.isActive || variant.productId != input.productId) {
        throw UnprocessableEntityException(
          "CART_ITEMS_UNAVAILABLE",
          "Selected size option for ${prod.name} is unavailable.",
        )
      }
      val crust = crustsMap[input.crustId]
      if (crust == null || !crust.isActive) {
        throw UnprocessableEntityException(
          "CART_ITEMS_UNAVAILABLE",
          "${crust?.name ?: "Selected crust"} is currently unavailable.",
        )
      }

      val toppingRows = input.toppingIds.map { tid ->
        val row = toppingsMap[tid]
        if (row == null || !row.isActive) {
          throw UnprocessableEntityException(
            "CART_ITEMS_UNAVAILABLE",
            "${row?.name ?: "Selected topping"} is currently unavailable.",
          )
        }
        row
      }

      val unitPrice = Math.addExact(
        Math.addExact(variant.pricePaise, crust.extraPricePaise),
        toppingRows.fold(0L) { acc, t -> Math.addExact(acc, t.extraPricePaise) },
      )
      val lineTotal = PricingEngine.calculateLineTotalPaise(unitPrice, input.quantity)

      EvaluatedCheckoutItem(
        productId = prod.id,
        productName = prod.name,
        variantSize = variant.sizeCode,
        crustName = crust.name,
        toppingNames = toppingRows.map { it.name },
        unitPricePaise = unitPrice,
        quantity = input.quantity,
        lineTotalPaise = lineTotal,
        lineInput = PricedLineInput(unitPrice, input.quantity, isTaxable = prod.isTaxable),
      )
    }
  }

  private fun computeRequestHash(
    items: List<ParsedCheckoutItem>,
    fulfillmentType: FulfillmentType,
    scheduleType: ScheduleType,
    scheduledSlotId: UUID?,
    addressId: UUID?,
    paymentMethod: PaymentMethod,
    couponCode: String?,
  ): String {
    val canonical = buildString {
      append(fulfillmentType.name).append('|')
      append(scheduleType.name).append('|')
      append(scheduledSlotId?.toString().orEmpty()).append('|')
      append(addressId?.toString().orEmpty()).append('|')
      append(paymentMethod.name).append('|')
      append(couponCode.orEmpty()).append('|')
      items.forEach { item ->
        append(item.productId).append(':')
        append(item.variantId).append(':')
        append(item.crustId).append(':')
        append(item.toppingIds.joinToString(",") { it.toString() }).append(':')
        append(item.quantity).append(';')
      }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }

  private fun generateOrderNumber(now: Instant, orderId: UUID): String {
    val datePart = DATE_FMT.format(now.atOffset(ZoneOffset.UTC))
    val suffix = orderId.toString().replace("-", "").takeLast(6).uppercase()
    return "PZ-$datePart-$suffix"
  }

  private fun parseUuid(raw: String, field: String): UUID =
    runCatching { UUID.fromString(raw) }
      .getOrElse { throw ValidationException("INVALID_UUID", "Invalid $field UUID: $raw") }

  private data class ParsedCheckoutItem(
    val productId: UUID,
    val variantId: UUID,
    val crustId: UUID,
    val toppingIds: List<UUID>,
    val quantity: Int,
  )

  private data class EvaluatedCheckoutItem(
    val productId: UUID,
    val productName: String,
    val variantSize: SizeCode,
    val crustName: String,
    val toppingNames: List<String>,
    val unitPricePaise: Long,
    val quantity: Int,
    val lineTotalPaise: Long,
    val lineInput: PricedLineInput,
  )

  private data class StoreSettings(val gstBps: Int, val isAcceptingOrders: Boolean)
  private data class ActiveDeliveryZone(val areaName: String, val minOrderPaise: Long, val deliveryFeePaise: Long)
  private data class LockedSlot(
    val id: UUID,
    val fulfillmentType: FulfillmentType,
    val cutoffAt: Instant,
    val maxCapacity: Int,
    val reservedCount: Int,
    val isActive: Boolean,
  )

  private data class ProductRow(val id: UUID, val name: String, val isTaxable: Boolean, val isActive: Boolean)
  private data class VariantRow(val id: UUID, val productId: UUID, val sizeCode: SizeCode, val pricePaise: Long, val isActive: Boolean)
  private data class CrustRow(val id: UUID, val name: String, val extraPricePaise: Long, val isActive: Boolean)
  private data class ToppingRow(val id: UUID, val name: String, val extraPricePaise: Long, val isActive: Boolean)

  companion object {
    private const val PAYMENT_WINDOW_SECONDS = 15L * 60L
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
  }
}

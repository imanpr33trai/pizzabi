package com.pizzora.contracts

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenVectorParityExportTest {
  private val prettyJson = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  @Serializable
  data class GoldenParityBundle(
    val source: String,
    val money_bps_vectors: List<MoneyBpsVector>,
    val money_format_vectors: List<MoneyFormatVector>,
    val discount_vectors: List<DiscountVector>,
    val quote_vectors: List<QuoteVector>,
    val order_cancel_vectors: List<OrderCancelVector>,
    val order_transition_vectors: List<OrderTransitionVector>,
    val kds_next_vectors: List<KdsNextVector>,
    val payment_transition_vectors: List<PaymentTransitionVector>,
    val attempt_transition_vectors: List<AttemptTransitionVector>,
    val causal_trace: List<CausalStepVector>,
  )

  @Serializable
  data class MoneyBpsVector(val amount_paise: Long, val bps: Int, val expected_paise: Long)

  @Serializable
  data class MoneyFormatVector(val paise: Long, val expected_inr: String)

  @Serializable
  data class CouponSpecInput(
    val discount_type: DiscountType,
    val discount_value: Long,
    val max_discount_paise: Long? = null,
    val min_order_paise: Long = 0L,
    val is_active: Boolean = true,
  )

  @Serializable
  data class DiscountVector(
    val subtotal_paise: Long,
    val coupon: CouponSpecInput?,
    val expected_discount_paise: Long,
  )

  @Serializable
  data class QuoteLineInput(
    val unit_price_paise: Long,
    val quantity: Int,
    val is_taxable: Boolean,
  )

  @Serializable
  data class QuoteVector(
    val name: String,
    val lines: List<QuoteLineInput>,
    val gst_bps: Int,
    val delivery_fee_paise: Long,
    val coupon: CouponSpecInput?,
    val expected: PriceBreakdownDto,
  )

  @Serializable
  data class OrderCancelVector(
    val status: OrderStatus,
    val role: UserRole,
    val expected: Boolean,
  )

  @Serializable
  data class OrderTransitionVector(
    val from: OrderStatus,
    val to: OrderStatus,
    val fulfillment_type: FulfillmentType,
    val actor_role: UserRole?,
    val expected: Boolean,
  )

  @Serializable
  data class KdsNextVector(
    val status: OrderStatus,
    val fulfillment_type: FulfillmentType,
    val expected: OrderStatus?,
  )

  @Serializable
  data class PaymentTransitionVector(
    val from: PaymentStatus,
    val to: PaymentStatus,
    val expected: Boolean,
  )

  @Serializable
  data class AttemptTransitionVector(
    val from: PaymentAttemptStatus,
    val to: PaymentAttemptStatus,
    val expected: Boolean,
  )

  @Serializable
  data class CausalStepVector(
    val action: String,
    val event_id: String? = null,
    val stream_seq: Long? = null,
    val event_type: String? = null,
    val aggregate_type: String? = null,
    val aggregate_id: String? = null,
    val aggregate_version: Long? = null,
    val order_id: String? = null,
    val high_water_seq: Long? = null,
    val expected_decision: String? = null,
    val expected_resync_order_id: String? = null,
    val expected_last_seen_seq: Long,
    val expected_tracked_version: Long? = null,
  )

  private fun findRepoRoot(): File {
    var dir = File(System.getProperty("user.dir")).canonicalFile
    while (dir.parentFile != null) {
      if (File(dir, "tools").isDirectory && File(dir, "settings.gradle.kts").isFile) {
        return dir
      }
      dir = dir.parentFile
    }
    error("Could not locate repository root from ${System.getProperty("user.dir")}")
  }

  @Test
  fun `export and verify golden vectors from Kotlin shared contracts`() {
    val moneyBpsInputs = listOf(
      0L to 0,
      0L to 500,
      10_000L to 0,
      10_000L to 500,
      100L to 250,
      100L to 249,
      105L to 250,
      19_900L to 500,
      1L to 5000,
      999L to 333,
      5_000_000_000L to 1800,
      Long.MAX_VALUE to 10_000,
    )
    val moneyBpsVectors = moneyBpsInputs.map { (amt, bps) ->
      MoneyBpsVector(amt, bps, Money.bpsOf(amt, bps))
    }

    val formatInputs = listOf(0L, 1L, 5L, 99L, 100L, 29_900L, -1L, -5L, -1_250L, 500_000_05L)
    val moneyFormatVectors = formatInputs.map { MoneyFormatVector(it, Money.formatInr(it)) }

    val couponSpecs = listOf(
      null,
      CouponSpecInput(DiscountType.PERCENT_BPS, 2000L, 15_000L, 50_000L, true),
      CouponSpecInput(DiscountType.FLAT_PAISE, 50_000L, null, 0L, true),
      CouponSpecInput(DiscountType.PERCENT_BPS, 10_000L, null, 0L, false),
      CouponSpecInput(DiscountType.PERCENT_BPS, 1234L, null, 100L, true),
      CouponSpecInput(DiscountType.FLAT_PAISE, 7_500L, 5_000L, 10_000L, true),
    )
    val discountSubtotals = listOf(0L, 100L, 105L, 29_900L, 49_999L, 50_000L, 60_000L, 100_000L)
    val discountVectors = buildList {
      for (sub in discountSubtotals) {
        for (spec in couponSpecs) {
          val contractSpec = spec?.let {
            CouponCalculationSpec(
              discountType = it.discount_type,
              discountValue = it.discount_value,
              maxDiscountPaise = it.max_discount_paise,
              minOrderPaise = it.min_order_paise,
              isActive = it.is_active,
            )
          }
          add(DiscountVector(sub, spec, PricingEngine.calculateDiscountPaise(sub, contractSpec)))
        }
      }
    }

    val quoteCases = listOf(
      Triple("empty_lines_with_delivery", emptyList<QuoteLineInput>(), Triple(500, 4_000L, null)),
      Triple(
        "mixed_taxable_and_exempt_flat_coupon",
        listOf(QuoteLineInput(40_000L, 1, true), QuoteLineInput(10_000L, 1, false)),
        Triple(500, 4_000L, CouponSpecInput(DiscountType.FLAT_PAISE, 5_000L)),
      ),
      Triple(
        "proportional_half_up_odd_tie",
        listOf(QuoteLineInput(101L, 1, true), QuoteLineInput(102L, 1, false)),
        Triple(1800, 2_500L, CouponSpecInput(DiscountType.FLAT_PAISE, 51L)),
      ),
      Triple(
        "odd_tax_cgst_sgst_split",
        listOf(QuoteLineInput(19_900L, 1, true)),
        Triple(500, 0L, null),
      ),
      Triple(
        "high_subtotal_large_order",
        listOf(QuoteLineInput(2_500_000_000L, 2, true)),
        Triple(1800, 5_000L, CouponSpecInput(DiscountType.PERCENT_BPS, 1234L)),
      ),
      Triple(
        "fully_exempt_cart",
        listOf(QuoteLineInput(29_900L, 3, false)),
        Triple(1800, 3_000L, CouponSpecInput(DiscountType.PERCENT_BPS, 1000L)),
      ),
      Triple(
        "zero_gst_all_taxable",
        listOf(QuoteLineInput(44_900L, 1, true)),
        Triple(0, 3_000L, CouponSpecInput(DiscountType.PERCENT_BPS, 1000L)),
      ),
    )

    val quoteVectors = quoteCases.map { (name, lines, params) ->
      val (gstBps, deliveryFee, couponSpec) = params
      val breakdown = PricingEngine.calculateQuote(
        lines = lines.map { PricedLineInput(it.unit_price_paise, it.quantity, it.is_taxable) },
        gstBps = gstBps,
        deliveryFeePaise = deliveryFee,
        coupon = couponSpec?.let {
          CouponCalculationSpec(
            discountType = it.discount_type,
            discountValue = it.discount_value,
            maxDiscountPaise = it.max_discount_paise,
            minOrderPaise = it.min_order_paise,
            isActive = it.is_active,
          )
        },
      )
      QuoteVector(
        name = name,
        lines = lines,
        gst_bps = gstBps,
        delivery_fee_paise = deliveryFee,
        coupon = couponSpec,
        expected = breakdown,
      )
    }

    val orderCancelVectors = buildList {
      for (status in OrderStatus.entries) {
        for (role in UserRole.entries) {
          add(OrderCancelVector(status, role, OrderStateMachine.canCancel(status, role)))
        }
      }
    }

    val actorRoles: List<UserRole?> = listOf(null) + UserRole.entries
    val orderTransitionVectors = buildList {
      for (from in OrderStatus.entries) {
        for (to in OrderStatus.entries) {
          for (ft in FulfillmentType.entries) {
            for (role in actorRoles) {
              add(
                OrderTransitionVector(
                  from = from,
                  to = to,
                  fulfillment_type = ft,
                  actor_role = role,
                  expected = OrderStateMachine.canTransition(from, to, ft, role),
                ),
              )
            }
          }
        }
      }
    }

    val kdsNextVectors = buildList {
      for (status in OrderStatus.entries) {
        for (ft in FulfillmentType.entries) {
          add(KdsNextVector(status, ft, OrderStateMachine.nextKdsStatus(status, ft)))
        }
      }
    }

    val paymentTransitionVectors = buildList {
      for (from in PaymentStatus.entries) {
        for (to in PaymentStatus.entries) {
          add(PaymentTransitionVector(from, to, PaymentStateMachine.canTransitionPayment(from, to)))
        }
      }
    }

    val attemptTransitionVectors = buildList {
      for (from in PaymentAttemptStatus.entries) {
        for (to in PaymentAttemptStatus.entries) {
          add(AttemptTransitionVector(from, to, PaymentStateMachine.canTransitionAttempt(from, to)))
        }
      }
    }

    val tracker = OrderCausalTracker(maxTrackedEventIds = 3)
    val causalTrace = mutableListOf<CausalStepVector>()

    fun recordEval(
      eventId: String,
      streamSeq: Long,
      eventType: String,
      aggregateType: String,
      aggregateId: String,
      aggregateVersion: Long,
      orderId: String? = null,
    ) {
      val ev = RealtimeEventDto(
        eventId = eventId,
        streamSeq = streamSeq,
        eventType = eventType,
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        aggregateVersion = aggregateVersion,
        payload = buildJsonObject {
          if (orderId != null) put("order_id", orderId)
        },
        createdAt = "2026-11-16T00:00:00Z",
      )
      val decision = tracker.evaluate(ev)
      val (decisionName, resyncOrderId) = when (decision) {
        CausalDecision.IgnoreDuplicate -> "IGNORE_DUPLICATE" to null
        CausalDecision.IgnoreStale -> "IGNORE_STALE" to null
        is CausalDecision.ApplyOrderEvent -> "APPLY_ORDER_EVENT" to null
        is CausalDecision.TriggerResync -> "TRIGGER_RESYNC" to decision.orderId
        is CausalDecision.ApplyNonOrderEvent -> "APPLY_NON_ORDER_EVENT" to null
      }
      val trackedOrder = orderId ?: aggregateId
      causalTrace += CausalStepVector(
        action = "EVALUATE",
        event_id = eventId,
        stream_seq = streamSeq,
        event_type = eventType,
        aggregate_type = aggregateType,
        aggregate_id = aggregateId,
        aggregate_version = aggregateVersion,
        order_id = orderId,
        expected_decision = decisionName,
        expected_resync_order_id = resyncOrderId,
        expected_last_seen_seq = tracker.lastSeenStreamSeq,
        expected_tracked_version = tracker.localVersionFor(trackedOrder),
      )
    }

    fun recordSnap(orderId: String, version: Long) {
      tracker.recordSnapshot(orderId, version)
      causalTrace += CausalStepVector(
        action = "RECORD_SNAPSHOT",
        order_id = orderId,
        aggregate_version = version,
        expected_last_seen_seq = tracker.lastSeenStreamSeq,
        expected_tracked_version = tracker.localVersionFor(orderId),
      )
    }

    fun recordCursor(highWater: Long) {
      tracker.advanceCursor(highWater)
      causalTrace += CausalStepVector(
        action = "ADVANCE_CURSOR",
        high_water_seq = highWater,
        expected_last_seen_seq = tracker.lastSeenStreamSeq,
      )
    }

    recordEval("ev-1", 10L, "order.created", "ORDER", "ord-1", 1L, "ord-1")
    recordEval("ev-1", 10L, "order.created", "ORDER", "ord-1", 1L, "ord-1")
    recordEval("ev-2", 12L, "payment.gateway_ready", "ORDER", "ord-1", 1L, "ord-1")
    recordEval("ev-3", 15L, "menu.updated", "CATALOG", "cat-1", 9L, null)
    // ev-1 was evicted when ev-4 is inserted (capacity = 3)
    recordEval("ev-4", 20L, "order.status_changed", "ORDER", "ord-1", 3L, "ord-1")
    recordEval("ev-1", 21L, "order.created", "ORDER", "ord-1", 1L, "ord-1")
    recordSnap("ord-1", 3L)
    recordEval("ev-5", 25L, "order.status_changed", "ORDER", "ord-1", 4L, "ord-1")
    recordCursor(99L)
    recordEval("ev-6", 30L, "payment.gateway_ready", "ORDER", "ord-2", 2L, "ord-2")
    recordEval("ev-7", 35L, "order.status_changed", "ORDER", "ord-3", 2L, "ord-3")

    val bundle = GoldenParityBundle(
      source = "Generated by Kotlin :shared:contracts GoldenVectorParityExportTest",
      money_bps_vectors = moneyBpsVectors,
      money_format_vectors = moneyFormatVectors,
      discount_vectors = discountVectors,
      quote_vectors = quoteVectors,
      order_cancel_vectors = orderCancelVectors,
      order_transition_vectors = orderTransitionVectors,
      kds_next_vectors = kdsNextVectors,
      payment_transition_vectors = paymentTransitionVectors,
      attempt_transition_vectors = attemptTransitionVectors,
      causal_trace = causalTrace,
    )

    val outFile = File(findRepoRoot(), "tools/golden-vectors/pizzora_core_parity.json")
    outFile.parentFile.mkdirs()
    outFile.writeText(prettyJson.encodeToString(bundle) + "\n")
    assertTrue(outFile.exists())
    assertTrue(outFile.length() > 1000)
  }
}

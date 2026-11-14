package com.pizzora.backend.reporting

import com.pizzora.backend.config.RestaurantConfigService
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.inventory.InventoryService
import com.pizzora.contracts.CouponRoiDto
import com.pizzora.contracts.DailySalesBucketDto
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.OperationalStockMovementDto
import com.pizzora.contracts.OperationsReportResponse
import com.pizzora.contracts.SalesReportResponse
import com.pizzora.contracts.TopSellingProductDto
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.roundToLong

class ReportingService(
  private val dataSource: DataSource,
  private val configService: RestaurantConfigService,
  private val inventoryService: InventoryService,
) {
  companion object {
    val IST_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private const val MAX_REPORT_SPAN_DAYS = 366L
  }

  suspend fun getSalesReport(
    startDateRaw: String? = null,
    endDateRaw: String? = null,
  ): SalesReportResponse {
    val (startDate, endDate) = resolveIstWindow(startDateRaw, endDateRaw)
    val startTs = Timestamp.from(startDate.atStartOfDay(IST_ZONE).toInstant())
    val endExclusiveTs = Timestamp.from(endDate.plusDays(1).atStartOfDay(IST_ZONE).toInstant())

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val summary = conn.prepareStatement(
        """
        WITH scoped_orders AS (
          SELECT o.id,
                 o.status,
                 o.subtotal_paise,
                 o.discount_paise,
                 o.delivery_fee_paise,
                 o.tax_paise,
                 o.total_paise,
                 COALESCE(p.captured_paise, 0)::bigint AS captured_paise,
                 COALESCE(p.refunded_paise, 0)::bigint AS refunded_paise,
                 COALESCE((
                   SELECT SUM(r.amount_paise)::bigint
                   FROM refunds r
                   WHERE r.order_id = o.id AND r.status IN ('PENDING', 'PROCESSING')
                 ), 0)::bigint AS pending_refunds_paise
          FROM orders o
          LEFT JOIN payments p ON p.order_id = o.id
          WHERE o.created_at >= ? AND o.created_at < ?
        ),
        revenue_orders AS (
          SELECT *
          FROM scoped_orders
          WHERE status NOT IN ('PENDING_PAYMENT', 'EXPIRED')
            AND (status <> 'CANCELLED' OR captured_paise > 0)
        )
        SELECT
          (SELECT COUNT(*)::int FROM revenue_orders) AS order_count,
          (SELECT COUNT(*)::int FROM scoped_orders WHERE status = 'CANCELLED') AS cancelled_order_count,
          COALESCE(SUM(subtotal_paise), 0)::bigint AS subtotal_paise,
          COALESCE(SUM(discount_paise), 0)::bigint AS discount_paise,
          COALESCE(SUM(delivery_fee_paise), 0)::bigint AS delivery_fee_paise,
          COALESCE(SUM(tax_paise), 0)::bigint AS tax_paise,
          COALESCE(SUM(total_paise), 0)::bigint AS gross_sales_paise,
          COALESCE(SUM(refunded_paise), 0)::bigint AS refunded_paise,
          COALESCE(SUM(pending_refunds_paise), 0)::bigint AS pending_refunds_paise
        FROM revenue_orders
        """.trimIndent(),
      ).use { ps ->
        ps.setTimestamp(1, startTs)
        ps.setTimestamp(2, endExclusiveTs)
        ps.executeQuery().use { rs ->
          rs.next()
          SalesSummaryRow(
            orderCount = rs.getInt("order_count"),
            cancelledOrderCount = rs.getInt("cancelled_order_count"),
            subtotalPaise = rs.getLong("subtotal_paise"),
            discountPaise = rs.getLong("discount_paise"),
            deliveryFeePaise = rs.getLong("delivery_fee_paise"),
            taxPaise = rs.getLong("tax_paise"),
            grossSalesPaise = rs.getLong("gross_sales_paise"),
            refundedPaise = rs.getLong("refunded_paise"),
            pendingRefundsPaise = rs.getLong("pending_refunds_paise"),
          )
        }
      }

      val dailyBreakdown = conn.prepareStatement(
        """
        SELECT
          TO_CHAR((o.created_at AT TIME ZONE 'Asia/Kolkata')::date, 'YYYY-MM-DD') AS ist_date,
          COUNT(*)::int AS order_count,
          COALESCE(SUM(o.total_paise), 0)::bigint AS gross_sales_paise,
          COALESCE(SUM(COALESCE(p.refunded_paise, 0)), 0)::bigint AS refunded_paise,
          GREATEST(0, COALESCE(SUM(o.total_paise), 0) - COALESCE(SUM(COALESCE(p.refunded_paise, 0)), 0))::bigint AS net_sales_paise,
          COALESCE(SUM(o.tax_paise), 0)::bigint AS tax_paise
        FROM orders o
        LEFT JOIN payments p ON p.order_id = o.id
        WHERE o.created_at >= ? AND o.created_at < ?
          AND o.status NOT IN ('PENDING_PAYMENT', 'EXPIRED')
          AND (o.status <> 'CANCELLED' OR COALESCE(p.captured_paise, 0) > 0)
        GROUP BY (o.created_at AT TIME ZONE 'Asia/Kolkata')::date
        ORDER BY (o.created_at AT TIME ZONE 'Asia/Kolkata')::date ASC
        """.trimIndent(),
      ).use { ps ->
        ps.setTimestamp(1, startTs)
        ps.setTimestamp(2, endExclusiveTs)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<DailySalesBucketDto>()
          while (rs.next()) {
            list += DailySalesBucketDto(
              date = rs.getString("ist_date"),
              orderCount = rs.getInt("order_count"),
              grossSalesPaise = rs.getLong("gross_sales_paise"),
              refundedPaise = rs.getLong("refunded_paise"),
              netSalesPaise = rs.getLong("net_sales_paise"),
              taxPaise = rs.getLong("tax_paise"),
            )
          }
          list
        }
      }

      val topProducts = conn.prepareStatement(
        """
        SELECT
          oi.product_id,
          oi.product_name_snapshot,
          COALESCE(SUM(oi.quantity), 0)::int AS quantity_sold,
          COALESCE(SUM(oi.line_total_paise), 0)::bigint AS gross_revenue_paise
        FROM order_items oi
        JOIN orders o ON o.id = oi.order_id
        WHERE o.created_at >= ? AND o.created_at < ?
          AND o.status NOT IN ('PENDING_PAYMENT', 'EXPIRED', 'CANCELLED')
        GROUP BY oi.product_id, oi.product_name_snapshot
        ORDER BY quantity_sold DESC, gross_revenue_paise DESC, oi.product_name_snapshot ASC
        LIMIT 10
        """.trimIndent(),
      ).use { ps ->
        ps.setTimestamp(1, startTs)
        ps.setTimestamp(2, endExclusiveTs)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<TopSellingProductDto>()
          while (rs.next()) {
            list += TopSellingProductDto(
              productId = rs.getObject("product_id", UUID::class.java).toString(),
              productName = rs.getString("product_name_snapshot"),
              quantitySold = rs.getInt("quantity_sold"),
              grossRevenuePaise = rs.getLong("gross_revenue_paise"),
            )
          }
          list
        }
      }

      val couponPerf = conn.prepareStatement(
        """
        SELECT
          c.id AS coupon_id,
          c.code,
          COUNT(*)::int AS redemption_count,
          COALESCE(SUM(cr.discount_paise), 0)::bigint AS total_discount_paise,
          COALESCE(SUM(o.total_paise), 0)::bigint AS gross_order_revenue_paise,
          GREATEST(0, COALESCE(SUM(o.total_paise), 0) - COALESCE(SUM(COALESCE(p.refunded_paise, 0)), 0))::bigint AS net_order_revenue_paise
        FROM coupon_redemptions cr
        JOIN coupons c ON c.id = cr.coupon_id
        JOIN orders o ON o.id = cr.order_id
        LEFT JOIN payments p ON p.order_id = o.id
        WHERE o.created_at >= ? AND o.created_at < ?
          AND o.status NOT IN ('PENDING_PAYMENT', 'EXPIRED')
          AND (o.status <> 'CANCELLED' OR COALESCE(p.captured_paise, 0) > 0)
        GROUP BY c.id, c.code
        ORDER BY redemption_count DESC, net_order_revenue_paise DESC
        """.trimIndent(),
      ).use { ps ->
        ps.setTimestamp(1, startTs)
        ps.setTimestamp(2, endExclusiveTs)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<CouponRoiDto>()
          while (rs.next()) {
            list += CouponRoiDto(
              couponId = rs.getObject("coupon_id", UUID::class.java).toString(),
              code = rs.getString("code"),
              redemptionCount = rs.getInt("redemption_count"),
              totalDiscountPaise = rs.getLong("total_discount_paise"),
              grossOrderRevenuePaise = rs.getLong("gross_order_revenue_paise"),
              netOrderRevenuePaise = rs.getLong("net_order_revenue_paise"),
            )
          }
          list
        }
      }

      val cgst = summary.taxPaise / 2L
      val sgst = summary.taxPaise - cgst
      val netSales = (summary.grossSalesPaise - summary.refundedPaise).coerceAtLeast(0L)

      SalesReportResponse(
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        timezone = IST_ZONE.id,
        orderCount = summary.orderCount,
        cancelledOrderCount = summary.cancelledOrderCount,
        subtotalPaise = summary.subtotalPaise,
        discountPaise = summary.discountPaise,
        deliveryFeePaise = summary.deliveryFeePaise,
        taxPaise = summary.taxPaise,
        cgstPaise = cgst,
        sgstPaise = sgst,
        grossSalesPaise = summary.grossSalesPaise,
        refundedPaise = summary.refundedPaise,
        pendingRefundsPaise = summary.pendingRefundsPaise,
        netSalesPaise = netSales,
        dailyBreakdown = dailyBreakdown,
        topProducts = topProducts,
        couponPerformance = couponPerf,
        serverNow = Instant.now().toString(),
      )
    }
  }

  suspend fun getOperationsReport(
    startDateRaw: String? = null,
    endDateRaw: String? = null,
  ): OperationsReportResponse {
    val (startDate, endDate) = resolveIstWindow(startDateRaw, endDateRaw)
    val startTs = Timestamp.from(startDate.atStartOfDay(IST_ZONE).toInstant())
    val endExclusiveTs = Timestamp.from(endDate.plusDays(1).atStartOfDay(IST_ZONE).toInstant())
    val lowStockItems = inventoryService.listInventory().filter { it.isLowStock }

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val settings = configService.loadSettings(conn)
      val prepSla = settings.defaultPrepMins

      val orderStats = conn.prepareStatement(
        """
        WITH scoped AS (
          SELECT o.*,
                 CASE
                   WHEN o.ready_at IS NOT NULL THEN
                     GREATEST(0.0, EXTRACT(EPOCH FROM (o.ready_at - COALESCE(o.prep_started_at, o.accepted_at, o.created_at))) / 60.0)
                   ELSE NULL
                 END AS prep_mins,
                 CASE
                   WHEN o.completed_at IS NOT NULL THEN
                     GREATEST(0.0, EXTRACT(EPOCH FROM (o.completed_at - o.created_at)) / 60.0)
                   ELSE NULL
                 END AS completion_mins
          FROM orders o
          WHERE o.created_at >= ? AND o.created_at < ?
        )
        SELECT
          COUNT(*)::int AS total_orders,
          COUNT(*) FILTER (WHERE status = 'COMPLETED')::int AS completed_orders,
          COUNT(*) FILTER (WHERE status = 'CANCELLED')::int AS cancelled_orders,
          COUNT(*) FILTER (WHERE status = 'EXPIRED')::int AS expired_orders,
          COUNT(*) FILTER (WHERE fulfillment_type = 'DELIVERY' AND status NOT IN ('PENDING_PAYMENT', 'EXPIRED'))::int AS delivery_orders,
          COUNT(*) FILTER (WHERE fulfillment_type = 'PICKUP' AND status NOT IN ('PENDING_PAYMENT', 'EXPIRED'))::int AS pickup_orders,
          COUNT(*) FILTER (WHERE schedule_type = 'SCHEDULED' AND status NOT IN ('PENDING_PAYMENT', 'EXPIRED'))::int AS scheduled_orders,
          COUNT(prep_mins)::int AS measured_prep_orders,
          COALESCE(AVG(prep_mins), 0.0)::double precision AS avg_prep_minutes,
          COALESCE(MAX(prep_mins), 0.0)::double precision AS max_prep_minutes,
          COALESCE(AVG(completion_mins), 0.0)::double precision AS avg_completion_minutes,
          COUNT(*) FILTER (WHERE prep_mins IS NOT NULL AND prep_mins > ?)::int AS sla_breached_count
        FROM scoped
        """.trimIndent(),
      ).use { ps ->
        ps.setTimestamp(1, startTs)
        ps.setTimestamp(2, endExclusiveTs)
        ps.setDouble(3, prepSla.toDouble())
        ps.executeQuery().use { rs ->
          rs.next()
          OperationsOrderStatsRow(
            totalOrders = rs.getInt("total_orders"),
            completedOrders = rs.getInt("completed_orders"),
            cancelledOrders = rs.getInt("cancelled_orders"),
            expiredOrders = rs.getInt("expired_orders"),
            deliveryOrders = rs.getInt("delivery_orders"),
            pickupOrders = rs.getInt("pickup_orders"),
            scheduledOrders = rs.getInt("scheduled_orders"),
            measuredPrepOrders = rs.getInt("measured_prep_orders"),
            avgPrepMinutes = roundTwoDecimals(rs.getDouble("avg_prep_minutes")),
            maxPrepMinutes = roundTwoDecimals(rs.getDouble("max_prep_minutes")),
            avgCompletionMinutes = roundTwoDecimals(rs.getDouble("avg_completion_minutes")),
            slaBreachedCount = rs.getInt("sla_breached_count"),
          )
        }
      }

      val stockSummary = conn.prepareStatement(
        """
        SELECT
          COALESCE(SUM(delta_on_hand) FILTER (WHERE reason = 'RESTOCK'), 0)::bigint AS restocked_units,
          COALESCE(ABS(SUM(delta_on_hand) FILTER (WHERE reason = 'COMMIT')), 0)::bigint AS consumed_units,
          COALESCE(SUM(delta_on_hand) FILTER (WHERE reason = 'CANCEL_RESTORE'), 0)::bigint AS restored_units,
          COALESCE(ABS(SUM(delta_on_hand) FILTER (WHERE reason = 'MANUAL_ADJUST' AND delta_on_hand < 0)), 0)::bigint AS wastage_units
        FROM inventory_ledger
        WHERE created_at >= ? AND created_at < ?
        """.trimIndent(),
      ).use { ps ->
        ps.setTimestamp(1, startTs)
        ps.setTimestamp(2, endExclusiveTs)
        ps.executeQuery().use { rs ->
          rs.next()
          StockSummaryRow(
            restockedUnits = rs.getLong("restocked_units"),
            consumedUnits = rs.getLong("consumed_units"),
            restoredUnits = rs.getLong("restored_units"),
            wastageUnits = rs.getLong("wastage_units"),
          )
        }
      }

      val stockMovements = conn.prepareStatement(
        """
        SELECT
          i.id AS ingredient_id,
          i.name AS ingredient_name,
          i.unit,
          COALESCE(SUM(il.delta_on_hand) FILTER (WHERE il.reason = 'RESTOCK'), 0)::bigint AS restocked_qty,
          COALESCE(ABS(SUM(il.delta_on_hand) FILTER (WHERE il.reason = 'COMMIT')), 0)::bigint AS consumed_qty,
          COALESCE(SUM(il.delta_on_hand) FILTER (WHERE il.reason = 'CANCEL_RESTORE'), 0)::bigint AS restored_qty,
          COALESCE(ABS(SUM(il.delta_on_hand) FILTER (WHERE il.reason = 'MANUAL_ADJUST' AND il.delta_on_hand < 0)), 0)::bigint AS wastage_qty
        FROM inventory_ledger il
        JOIN ingredients i ON i.id = il.ingredient_id
        WHERE il.created_at >= ? AND il.created_at < ?
        GROUP BY i.id, i.name, i.unit
        ORDER BY consumed_qty DESC, wastage_qty DESC, restocked_qty DESC, i.name ASC
        LIMIT 15
        """.trimIndent(),
      ).use { ps ->
        ps.setTimestamp(1, startTs)
        ps.setTimestamp(2, endExclusiveTs)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<OperationalStockMovementDto>()
          while (rs.next()) {
            list += OperationalStockMovementDto(
              ingredientId = rs.getObject("ingredient_id", UUID::class.java).toString(),
              ingredientName = rs.getString("ingredient_name"),
              unit = IngredientUnit.valueOf(rs.getString("unit")),
              restockedQty = rs.getLong("restocked_qty"),
              consumedQty = rs.getLong("consumed_qty"),
              restoredQty = rs.getLong("restored_qty"),
              wastageQty = rs.getLong("wastage_qty"),
            )
          }
          list
        }
      }

      val slaCompliance = if (orderStats.measuredPrepOrders == 0) {
        100.0
      } else {
        val compliant = (orderStats.measuredPrepOrders - orderStats.slaBreachedCount).coerceAtLeast(0)
        roundTwoDecimals((compliant * 100.0) / orderStats.measuredPrepOrders.toDouble())
      }

      OperationsReportResponse(
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        timezone = IST_ZONE.id,
        prepSlaMinutes = prepSla,
        totalOrders = orderStats.totalOrders,
        completedOrders = orderStats.completedOrders,
        cancelledOrders = orderStats.cancelledOrders,
        expiredOrders = orderStats.expiredOrders,
        deliveryOrders = orderStats.deliveryOrders,
        pickupOrders = orderStats.pickupOrders,
        scheduledOrders = orderStats.scheduledOrders,
        measuredPrepOrders = orderStats.measuredPrepOrders,
        avgPrepMinutes = orderStats.avgPrepMinutes,
        maxPrepMinutes = orderStats.maxPrepMinutes,
        avgCompletionMinutes = orderStats.avgCompletionMinutes,
        slaBreachedCount = orderStats.slaBreachedCount,
        slaCompliancePercent = slaCompliance,
        restockedUnits = stockSummary.restockedUnits,
        consumedUnits = stockSummary.consumedUnits,
        restoredUnits = stockSummary.restoredUnits,
        wastageUnits = stockSummary.wastageUnits,
        stockMovements = stockMovements,
        lowStockItems = lowStockItems,
        serverNow = Instant.now().toString(),
      )
    }
  }

  private fun resolveIstWindow(startRaw: String?, endRaw: String?): Pair<LocalDate, LocalDate> {
    val cleanStart = startRaw?.trim()?.takeIf { it.isNotEmpty() }
    val cleanEnd = endRaw?.trim()?.takeIf { it.isNotEmpty() }
    val todayIst = LocalDate.now(IST_ZONE)

    val startDate: LocalDate
    val endDate: LocalDate
    when {
      cleanStart == null && cleanEnd == null -> {
        endDate = todayIst
        startDate = todayIst.minusDays(6)
      }
      cleanStart != null && cleanEnd == null -> {
        startDate = parseDate(cleanStart, "start_date")
        endDate = startDate
      }
      cleanStart == null && cleanEnd != null -> {
        endDate = parseDate(cleanEnd, "end_date")
        startDate = endDate
      }
      else -> {
        startDate = parseDate(cleanStart!!, "start_date")
        endDate = parseDate(cleanEnd!!, "end_date")
      }
    }

    if (startDate.isAfter(endDate)) {
      throw ValidationException("INVALID_DATE_RANGE", "start_date ($startDate) cannot be after end_date ($endDate).")
    }
    val spanDays = ChronoUnit.DAYS.between(startDate, endDate) + 1L
    if (spanDays > MAX_REPORT_SPAN_DAYS) {
      throw ValidationException("DATE_RANGE_TOO_LARGE", "Report window cannot exceed $MAX_REPORT_SPAN_DAYS days.")
    }
    return startDate to endDate
  }

  private fun parseDate(raw: String, field: String): LocalDate =
    runCatching { LocalDate.parse(raw) }
      .getOrElse {
        throw ValidationException("INVALID_DATE", "$field must be formatted as YYYY-MM-DD (Asia/Kolkata).")
      }

  private fun roundTwoDecimals(value: Double): Double =
    (value * 100.0).roundToLong() / 100.0

  private data class SalesSummaryRow(
    val orderCount: Int,
    val cancelledOrderCount: Int,
    val subtotalPaise: Long,
    val discountPaise: Long,
    val deliveryFeePaise: Long,
    val taxPaise: Long,
    val grossSalesPaise: Long,
    val refundedPaise: Long,
    val pendingRefundsPaise: Long,
  )

  private data class OperationsOrderStatsRow(
    val totalOrders: Int,
    val completedOrders: Int,
    val cancelledOrders: Int,
    val expiredOrders: Int,
    val deliveryOrders: Int,
    val pickupOrders: Int,
    val scheduledOrders: Int,
    val measuredPrepOrders: Int,
    val avgPrepMinutes: Double,
    val maxPrepMinutes: Double,
    val avgCompletionMinutes: Double,
    val slaBreachedCount: Int,
  )

  private data class StockSummaryRow(
    val restockedUnits: Long,
    val consumedUnits: Long,
    val restoredUnits: Long,
    val wastageUnits: Long,
  )
}

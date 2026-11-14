package com.pizzora.backend.owner

import com.pizzora.backend.config.RestaurantConfigService
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.inventory.InventoryService
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OrderSummaryDto
import com.pizzora.contracts.OwnerDashboardResponse
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.ScheduleType
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

class OwnerService(
  private val dataSource: DataSource,
  private val configService: RestaurantConfigService,
  private val inventoryService: InventoryService,
) {
  suspend fun getDashboard(): OwnerDashboardResponse {
    val allInventory = inventoryService.listInventory()
    val lowStockItems = allInventory.filter { it.isLowStock }

    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val settings = configService.loadSettings(conn)

      val kpis = conn.prepareStatement(
        """
        SELECT
          COALESCE(SUM(
            CASE
              WHEN (o.created_at AT TIME ZONE 'Asia/Kolkata')::date = (NOW() AT TIME ZONE 'Asia/Kolkata')::date
                   AND o.status NOT IN ('CANCELLED', 'EXPIRED', 'PENDING_PAYMENT')
              THEN GREATEST(0, o.total_paise - COALESCE(p.refunded_paise, 0))
              ELSE 0
            END
          ), 0)::bigint AS today_revenue_paise,
          COUNT(*) FILTER (
            WHERE (o.created_at AT TIME ZONE 'Asia/Kolkata')::date = (NOW() AT TIME ZONE 'Asia/Kolkata')::date
              AND o.status NOT IN ('CANCELLED', 'EXPIRED')
          )::int AS today_order_count,
          COUNT(*) FILTER (
            WHERE o.status IN ('PLACED', 'ACCEPTED', 'PREPARING', 'READY', 'OUT_FOR_DELIVERY')
          )::int AS active_order_count,
          COUNT(*) FILTER (
            WHERE o.status = 'PENDING_PAYMENT'
          )::int AS pending_payment_count,
          COUNT(*) FILTER (
            WHERE (o.created_at AT TIME ZONE 'Asia/Kolkata')::date = (NOW() AT TIME ZONE 'Asia/Kolkata')::date
              AND o.status = 'COMPLETED'
          )::int AS completed_today_count
        FROM orders o
        LEFT JOIN payments p ON p.order_id = o.id
        """.trimIndent(),
      ).use { ps ->
        ps.executeQuery().use { rs ->
          rs.next()
          DashboardKpis(
            todayRevenuePaise = rs.getLong("today_revenue_paise"),
            todayOrderCount = rs.getInt("today_order_count"),
            activeOrderCount = rs.getInt("active_order_count"),
            pendingPaymentCount = rs.getInt("pending_payment_count"),
            completedTodayCount = rs.getInt("completed_today_count"),
          )
        }
      }

      val recentOrders = queryOrders(conn = conn, statusFilter = null, limit = 10)

      OwnerDashboardResponse(
        todayRevenuePaise = kpis.todayRevenuePaise,
        todayOrderCount = kpis.todayOrderCount,
        activeOrderCount = kpis.activeOrderCount,
        pendingPaymentCount = kpis.pendingPaymentCount,
        completedTodayCount = kpis.completedTodayCount,
        lowStockCount = lowStockItems.size,
        lowStockItems = lowStockItems,
        recentOrders = recentOrders,
        settings = settings,
        serverNow = Instant.now().toString(),
      )
    }
  }

  suspend fun listOrders(statusFilter: OrderStatus?, limit: Int = 50): OrderListResponse {
    val clamped = limit.coerceIn(1, 100)
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      OrderListResponse(
        orders = queryOrders(conn = conn, statusFilter = statusFilter, limit = clamped),
      )
    }
  }

  private fun queryOrders(
    conn: Connection,
    statusFilter: OrderStatus?,
    limit: Int,
  ): List<OrderSummaryDto> {
    val whereClause = if (statusFilter != null) "WHERE o.status = ?" else ""
    val sql = """
      SELECT o.id, o.order_number, o.fulfillment_type, o.schedule_type, o.status, o.version,
             o.total_paise, o.is_priority, o.created_at, o.expires_at,
             p.method AS payment_method, p.status AS payment_status,
             COALESCE((SELECT SUM(oi.quantity)::int FROM order_items oi WHERE oi.order_id = o.id), 0) AS item_count
      FROM orders o
      JOIN payments p ON p.order_id = o.id
      $whereClause
      ORDER BY o.created_at DESC, o.id DESC
      LIMIT ?
    """.trimIndent()

    return conn.prepareStatement(sql).use { ps ->
      var idx = 1
      if (statusFilter != null) {
        ps.setString(idx++, statusFilter.name)
      }
      ps.setInt(idx, limit)
      ps.executeQuery().use { rs ->
        val list = mutableListOf<OrderSummaryDto>()
        while (rs.next()) {
          list += OrderSummaryDto(
            id = rs.getObject("id", UUID::class.java).toString(),
            orderNumber = rs.getString("order_number"),
            fulfillmentType = FulfillmentType.valueOf(rs.getString("fulfillment_type")),
            scheduleType = ScheduleType.valueOf(rs.getString("schedule_type")),
            status = OrderStatus.valueOf(rs.getString("status")),
            version = rs.getLong("version"),
            totalPaise = rs.getLong("total_paise"),
            itemCount = rs.getInt("item_count"),
            paymentMethod = PaymentMethod.valueOf(rs.getString("payment_method")),
            paymentStatus = PaymentStatus.valueOf(rs.getString("payment_status")),
            isPriority = rs.getBoolean("is_priority"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
            expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java)?.toInstant()?.toString(),
          )
        }
        list
      }
    }
  }

  private data class DashboardKpis(
    val todayRevenuePaise: Long,
    val todayOrderCount: Int,
    val activeOrderCount: Int,
    val pendingPaymentCount: Int,
    val completedTodayCount: Int,
  )
}

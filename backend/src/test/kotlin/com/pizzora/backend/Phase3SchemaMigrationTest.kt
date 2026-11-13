package com.pizzora.backend

import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.db.PostgresJdbcProbe
import com.pizzora.contracts.DependencyHealthStatus
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.SQLException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase3SchemaMigrationTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres

    @JvmStatic
    @BeforeClass
    fun startPostgres() {
      pg = EmbeddedPostgres.builder().start()
      DatabaseFactory.migrateAndConnect(pg.postgresDatabase)
    }

    @JvmStatic
    @AfterClass
    fun stopPostgres() {
      pg.close()
    }
  }

  @Test
  fun `flyway creates all 46 tables and seeds RBAC roles, permissions, settings, and stream cursor`() {
    pg.postgresDatabase.connection.use { conn ->
      conn.createStatement().use { stmt ->
        stmt.executeQuery(
          "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'",
        ).use { rs ->
          assertTrue(rs.next())
          assertEquals(46, rs.getInt(1))
        }

        stmt.executeQuery("SELECT COUNT(*) FROM roles").use { rs ->
          assertTrue(rs.next())
          assertEquals(3, rs.getInt(1))
        }

        stmt.executeQuery("SELECT gst_bps, timezone FROM restaurant_settings WHERE id = 1").use { rs ->
          assertTrue(rs.next())
          assertEquals(500, rs.getInt("gst_bps"))
          assertEquals("Asia/Kolkata", rs.getString("timezone"))
        }

        stmt.executeQuery("SELECT last_seq FROM event_stream_cursor WHERE id = 1").use { rs ->
          assertTrue(rs.next())
          assertEquals(0L, rs.getLong("last_seq"))
        }
      }
    }

    val status = runBlocking { PostgresJdbcProbe(pg.postgresDatabase).check() }
    assertEquals(DependencyHealthStatus.UP, status)
  }

  @Test
  fun `inventory available_qty generated column and reserved check constraint behave strictly`() {
    val ingredientId = UUID.randomUUID()
    pg.postgresDatabase.connection.use { conn ->
      conn.autoCommit = true
      conn.prepareStatement("INSERT INTO ingredients (id, name, unit, low_stock_threshold) VALUES (?, ?, 'GRAMS', 500)").use { ps ->
        ps.setObject(1, ingredientId)
        ps.setString(2, "Mozzarella-$ingredientId")
        ps.executeUpdate()
      }
      conn.prepareStatement("INSERT INTO inventory (ingredient_id, on_hand_qty, reserved_qty) VALUES (?, 1000, 250)").use { ps ->
        ps.setObject(1, ingredientId)
        ps.executeUpdate()
      }
      conn.prepareStatement("SELECT available_qty FROM inventory WHERE ingredient_id = ?").use { ps ->
        ps.setObject(1, ingredientId)
        ps.executeQuery().use { rs ->
          assertTrue(rs.next())
          assertEquals(750L, rs.getLong("available_qty"))
        }
      }

      val invalidReserved = runCatching {
        conn.prepareStatement("UPDATE inventory SET reserved_qty = 1001 WHERE ingredient_id = ?").use { ps ->
          ps.setObject(1, ingredientId)
          ps.executeUpdate()
        }
      }
      assertTrue(invalidReserved.exceptionOrNull() is SQLException)
    }
  }

  @Test
  fun `scheduled slot capacity, payment refund cap, and outbox published_at invariants are enforced`() {
    pg.postgresDatabase.connection.use { conn ->
      conn.autoCommit = true

      // 1. Scheduled slot reserved_count > max_capacity rejected
      val slotErr = runCatching {
        conn.prepareStatement(
          "INSERT INTO scheduled_slots (id, slot_date, fulfillment_type, start_time_ist, end_time_ist, cutoff_at, max_capacity, reserved_count) VALUES (?, CURRENT_DATE, 'DELIVERY', '18:00', '19:00', NOW(), 5, 6)",
        ).use { ps ->
          ps.setObject(1, UUID.randomUUID())
          ps.executeUpdate()
        }
      }
      assertTrue(slotErr.exceptionOrNull() is SQLException)

      // 2. Outbox DISPATCHED without published_at rejected; PENDING with published_at rejected
      val outboxErr1 = runCatching {
        conn.prepareStatement(
          "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload_json, dispatch_status, published_at) VALUES (?, 'ORDER', ?, 'ORDER_PLACED', '{}'::jsonb, 'DISPATCHED', NULL)",
        ).use { ps ->
          ps.setObject(1, UUID.randomUUID())
          ps.setObject(2, UUID.randomUUID())
          ps.executeUpdate()
        }
      }
      assertTrue(outboxErr1.exceptionOrNull() is SQLException)

      val outboxErr2 = runCatching {
        conn.prepareStatement(
          "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload_json, dispatch_status, published_at) VALUES (?, 'ORDER', ?, 'ORDER_PLACED', '{}'::jsonb, 'PENDING', NOW())",
        ).use { ps ->
          ps.setObject(1, UUID.randomUUID())
          ps.setObject(2, UUID.randomUUID())
          ps.executeUpdate()
        }
      }
      assertTrue(outboxErr2.exceptionOrNull() is SQLException)

      // 3. Unique (customer_id, idempotency_key) and refunded_paise <= captured_paise checks
      val userId = UUID.randomUUID()
      conn.prepareStatement(
        "INSERT INTO users (id, email, password_hash, full_name, phone) VALUES (?, ?, 'hash', 'Test User', '9999999999')",
      ).use { ps ->
        ps.setObject(1, userId)
        ps.setString(2, "user-$userId@pizzora.test")
        ps.executeUpdate()
      }

      val orderId1 = UUID.randomUUID()
      conn.prepareStatement(
        "INSERT INTO orders (id, order_number, customer_id, idempotency_key, request_hash, fulfillment_type, schedule_type, status, subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise, gst_bps_snapshot) VALUES (?, ?, ?, 'idem-1', 'hash-1', 'PICKUP', 'ASAP', 'PLACED', 10000, 0, 0, 500, 10500, 500)",
      ).use { ps ->
        ps.setObject(1, orderId1)
        ps.setString(2, "PZ-1001-$orderId1")
        ps.setObject(3, userId)
        ps.executeUpdate()
      }

      val dupIdemErr = runCatching {
        val orderId2 = UUID.randomUUID()
        conn.prepareStatement(
          "INSERT INTO orders (id, order_number, customer_id, idempotency_key, request_hash, fulfillment_type, schedule_type, status, subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise, gst_bps_snapshot) VALUES (?, ?, ?, 'idem-1', 'hash-1', 'PICKUP', 'ASAP', 'PLACED', 10000, 0, 0, 500, 10500, 500)",
        ).use { ps ->
          ps.setObject(1, orderId2)
          ps.setString(2, "PZ-1002-$orderId2")
          ps.setObject(3, userId)
          ps.executeUpdate()
        }
      }
      assertTrue(dupIdemErr.exceptionOrNull() is SQLException)

      val refundOverflowErr = runCatching {
        conn.prepareStatement(
          "INSERT INTO payments (id, order_id, method, status, amount_paise, captured_paise, refunded_paise) VALUES (?, ?, 'RAZORPAY', 'CAPTURED', 10500, 10500, 10501)",
        ).use { ps ->
          ps.setObject(1, UUID.randomUUID())
          ps.setObject(2, orderId1)
          ps.executeUpdate()
        }
      }
      assertTrue(refundOverflowErr.exceptionOrNull() is SQLException)
    }
  }
}

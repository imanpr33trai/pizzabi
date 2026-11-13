package com.pizzora.contracts

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractsFoundationTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `bpsOf uses deterministic half-up BigInteger rounding`() {
    assertEquals(500L, Money.bpsOf(10_000L, 500))
    // 100 * 250 / 10000 = 2.5 -> 3
    assertEquals(3L, Money.bpsOf(100L, 250))
    // 100 * 249 / 10000 = 2.49 -> 2
    assertEquals(2L, Money.bpsOf(100L, 249))
  }

  @Test
  fun `formatInr formats integer paise cleanly`() {
    assertEquals("₹299.00", Money.formatInr(29_900L))
    assertEquals("₹0.05", Money.formatInr(5L))
    assertEquals("-₹12.50", Money.formatInr(-1_250L))
  }

  @Test
  fun `order terminal states and role serialization match contract`() {
    assertTrue(OrderStatus.COMPLETED.isTerminal)
    assertTrue(OrderStatus.CANCELLED.isTerminal)
    assertTrue(OrderStatus.EXPIRED.isTerminal)
    assertFalse(OrderStatus.PENDING_PAYMENT.isTerminal)
    assertFalse(OrderStatus.PLACED.isTerminal)

    val encoded = json.encodeToString(UserRole.KITCHEN_STAFF)
    assertEquals("\"KITCHEN_STAFF\"", encoded)
    assertEquals(UserRole.OWNER, json.decodeFromString<UserRole>("\"OWNER\""))
  }
}

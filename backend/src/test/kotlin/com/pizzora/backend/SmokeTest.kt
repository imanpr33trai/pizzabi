package com.pizzora.backend

import com.pizzora.contracts.Money
import com.pizzora.contracts.UserRole
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmokeTest {
  @Test
  fun `health live responds 200 and shared contracts link cleanly`() = testApplication {
    application { pizzoraModule() }

    val response = client.get("/api/v1/health/live")
    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsText().contains("HEALTHY"))
    assertEquals("₹499.00", Money.formatInr(49_900L))
    assertEquals(3, UserRole.entries.size)
  }
}

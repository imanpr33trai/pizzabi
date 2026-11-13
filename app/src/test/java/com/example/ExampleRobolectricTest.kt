package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pizzora.contracts.Money
import com.pizzora.contracts.UserRole
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("PIZZORA", appName)
    assertEquals(3, UserRole.entries.size)
    assertEquals("₹299.00", Money.formatInr(29_900L))
  }
}

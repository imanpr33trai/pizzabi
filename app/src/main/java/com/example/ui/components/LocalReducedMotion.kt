package com.example.ui.components

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Provides whether animations should be reduced (system ANIMATOR_DURATION_SCALE == 0f
 * or explicitly overridden in tests/previews).
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberSystemReducedMotion(): Boolean {
  val context = LocalContext.current
  return remember(context) {
    runCatching {
      val scale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
      )
      scale == 0f
    }.getOrDefault(false)
  }
}

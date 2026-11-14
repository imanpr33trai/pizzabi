package com.example.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.database.NotificationInboxEntity
import com.pizzora.contracts.NotificationCategory

interface LocalNotificationPoster {
  fun hasPermission(): Boolean
  fun ensureChannels()
  fun showNotification(item: NotificationInboxEntity): Boolean
  fun showNotifications(items: List<NotificationInboxEntity>): Int
}

class NotificationHelper(
  private val context: Context,
) : LocalNotificationPoster {

  override fun hasPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return true
    }
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
  }

  override fun ensureChannels() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

    val orderChannel = NotificationChannel(
      CHANNEL_ORDER_UPDATES,
      "Order & Kitchen Updates",
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = "Live status alerts for wood-fired pizza preparation, readiness, and delivery."
    }

    val promoChannel = NotificationChannel(
      CHANNEL_PROMO_ALERTS,
      "Offers & Kitchen Alerts",
      NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
      description = "Promotional coupons and operational alerts."
    }

    manager.createNotificationChannel(orderChannel)
    manager.createNotificationChannel(promoChannel)
  }

  @SuppressLint("MissingPermission")
  override fun showNotification(item: NotificationInboxEntity): Boolean {
    ensureChannels()
    if (!hasPermission()) {
      return false
    }

    val channelId = if (item.category == NotificationCategory.PROMO_ALERT) {
      CHANNEL_PROMO_ALERTS
    } else {
      CHANNEL_ORDER_UPDATES
    }

    val launchIntent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
      putExtra(EXTRA_OPEN_NOTIFICATIONS, true)
      item.referenceId?.let { putExtra(EXTRA_REFERENCE_ID, it) }
    }

    val pendingIntent = PendingIntent.getActivity(
      context,
      item.id.hashCode(),
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val notification = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(item.title)
      .setContentText(item.body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
      .setPriority(
        if (channelId == CHANNEL_ORDER_UPDATES) NotificationCompat.PRIORITY_HIGH
        else NotificationCompat.PRIORITY_DEFAULT,
      )
      .setAutoCancel(true)
      .setContentIntent(pendingIntent)
      .build()

    NotificationManagerCompat.from(context).notify(item.id.hashCode(), notification)
    return true
  }

  override fun showNotifications(items: List<NotificationInboxEntity>): Int {
    if (items.isEmpty()) return 0
    ensureChannels()
    if (!hasPermission()) return 0
    var posted = 0
    for (item in items) {
      if (!item.isRead && showNotification(item)) {
        posted++
      }
    }
    return posted
  }

  companion object {
    const val CHANNEL_ORDER_UPDATES: String = "pizzora_order_updates"
    const val CHANNEL_PROMO_ALERTS: String = "pizzora_promo_alerts"
    const val EXTRA_OPEN_NOTIFICATIONS: String = "open_notifications"
    const val EXTRA_REFERENCE_ID: String = "notification_reference_id"
  }
}

package com.pizzora.backend.notifications

import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.NotFoundException
import com.pizzora.contracts.MarkAllNotificationsReadResponse
import com.pizzora.contracts.MarkNotificationReadResponse
import com.pizzora.contracts.NotificationCategory
import com.pizzora.contracts.NotificationDeliveryStatus
import com.pizzora.contracts.NotificationInboxItemDto
import com.pizzora.contracts.NotificationInboxResponse
import com.pizzora.contracts.NotificationPreferencesDto
import com.pizzora.contracts.UpdateNotificationPreferencesRequest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

class NotificationService(
  private val dataSource: DataSource,
) {

  suspend fun listNotifications(
    userId: UUID,
    unreadOnly: Boolean = false,
    limit: Int = 50,
  ): NotificationInboxResponse {
    val clampedLimit = limit.coerceIn(1, 100)
    return DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      val sql = buildString {
        append(
          """
          SELECT id, event_id, title, body, category, reference_id, is_read, delivery_status, created_at
          FROM notification_inbox
          WHERE user_id = ?
          """.trimIndent(),
        )
        if (unreadOnly) {
          append(" AND is_read = FALSE")
        }
        append(" ORDER BY created_at DESC LIMIT ?")
      }

      val fetched = conn.prepareStatement(sql).use { ps ->
        ps.setObject(1, userId)
        ps.setInt(2, clampedLimit)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<NotificationInboxItemDto>()
          while (rs.next()) {
            list += rs.toNotificationDto()
          }
          list
        }
      }

      val pendingIds = fetched
        .filter { it.deliveryStatus == NotificationDeliveryStatus.PENDING }
        .map { UUID.fromString(it.id) }

      val synced = if (pendingIds.isNotEmpty()) {
        conn.prepareStatement(
          """
          UPDATE notification_inbox
          SET delivery_status = 'SYNCED'
          WHERE user_id = ? AND delivery_status = 'PENDING' AND id = ANY (?)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, userId)
          ps.setArray(2, conn.createArrayOf("uuid", pendingIds.toTypedArray()))
          ps.executeUpdate()
        }
        val pendingSet = pendingIds.mapTo(HashSet()) { it.toString() }
        fetched.map { item ->
          if (item.id in pendingSet) item.copy(deliveryStatus = NotificationDeliveryStatus.SYNCED) else item
        }
      } else {
        fetched
      }

      val unreadCount = countUnreadInTx(conn, userId)
      NotificationInboxResponse(
        notifications = synced,
        unreadCount = unreadCount,
        serverNow = Instant.now().toString(),
      )
    }
  }

  suspend fun markAsRead(
    userId: UUID,
    notificationId: UUID,
  ): MarkNotificationReadResponse = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
    val updated = conn.prepareStatement(
      """
      UPDATE notification_inbox
      SET is_read = TRUE
      WHERE id = ? AND user_id = ?
      RETURNING id, event_id, title, body, category, reference_id, is_read, delivery_status, created_at
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, notificationId)
      ps.setObject(2, userId)
      ps.executeQuery().use { rs ->
        if (rs.next()) rs.toNotificationDto() else null
      }
    } ?: throw NotFoundException(
      code = "NOTIFICATION_NOT_FOUND",
      message = "Notification $notificationId was not found.",
    )

    val unreadCount = countUnreadInTx(conn, userId)
    MarkNotificationReadResponse(
      notification = updated,
      unreadCount = unreadCount,
    )
  }

  suspend fun markAllAsRead(
    userId: UUID,
  ): MarkAllNotificationsReadResponse = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
    val marked = conn.prepareStatement(
      """
      UPDATE notification_inbox
      SET is_read = TRUE
      WHERE user_id = ? AND is_read = FALSE
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, userId)
      ps.executeUpdate()
    }
    MarkAllNotificationsReadResponse(
      markedCount = marked,
      unreadCount = countUnreadInTx(conn, userId),
    )
  }

  suspend fun getPreferences(userId: UUID): NotificationPreferencesDto =
    DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
      ensurePreferencesRowInTx(conn, userId)
      selectPreferencesInTx(conn, userId)
    }

  suspend fun updatePreferences(
    userId: UUID,
    request: UpdateNotificationPreferencesRequest,
  ): NotificationPreferencesDto = DatabaseFactory.withJdbcTransaction(dataSource) { conn ->
    ensurePreferencesRowInTx(conn, userId)
    val current = selectPreferencesInTx(conn, userId)
    val nextOrderUpdates = request.orderUpdates ?: current.orderUpdates
    val nextPromoAlerts = request.promoAlerts ?: current.promoAlerts
    val nextKdsSoundEnabled = request.kdsSoundEnabled ?: current.kdsSoundEnabled

    conn.prepareStatement(
      """
      UPDATE notification_preferences
      SET order_updates = ?,
          promo_alerts = ?,
          kds_sound_enabled = ?,
          updated_at = NOW()
      WHERE user_id = ?
      RETURNING user_id, order_updates, promo_alerts, kds_sound_enabled, updated_at
      """.trimIndent(),
    ).use { ps ->
      ps.setBoolean(1, nextOrderUpdates)
      ps.setBoolean(2, nextPromoAlerts)
      ps.setBoolean(3, nextKdsSoundEnabled)
      ps.setObject(4, userId)
      ps.executeQuery().use { rs ->
        rs.next()
        rs.toPreferencesDto()
      }
    }
  }

  private fun ensurePreferencesRowInTx(conn: Connection, userId: UUID) {
    conn.prepareStatement(
      """
      INSERT INTO notification_preferences (user_id)
      VALUES (?)
      ON CONFLICT (user_id) DO NOTHING
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, userId)
      ps.executeUpdate()
    }
  }

  private fun selectPreferencesInTx(conn: Connection, userId: UUID): NotificationPreferencesDto =
    conn.prepareStatement(
      """
      SELECT user_id, order_updates, promo_alerts, kds_sound_enabled, updated_at
      FROM notification_preferences
      WHERE user_id = ?
      """.trimIndent(),
    ).use { ps ->
      ps.setObject(1, userId)
      ps.executeQuery().use { rs ->
        if (!rs.next()) {
          throw NotFoundException(
            code = "USER_NOT_FOUND",
            message = "Notification preferences not found for user $userId.",
          )
        }
        rs.toPreferencesDto()
      }
    }

  private fun countUnreadInTx(conn: Connection, userId: UUID): Int =
    conn.prepareStatement(
      "SELECT COUNT(*) AS cnt FROM notification_inbox WHERE user_id = ? AND is_read = FALSE",
    ).use { ps ->
      ps.setObject(1, userId)
      ps.executeQuery().use { rs ->
        if (rs.next()) rs.getInt("cnt") else 0
      }
    }

  private fun ResultSet.toNotificationDto(): NotificationInboxItemDto =
    NotificationInboxItemDto(
      id = getObject("id", UUID::class.java).toString(),
      eventId = getObject("event_id", UUID::class.java).toString(),
      title = getString("title"),
      body = getString("body"),
      category = getString("category"),
      referenceId = getObject("reference_id", UUID::class.java)?.toString(),
      isRead = getBoolean("is_read"),
      deliveryStatus = NotificationDeliveryStatus.valueOf(getString("delivery_status")),
      createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
    )

  private fun ResultSet.toPreferencesDto(): NotificationPreferencesDto =
    NotificationPreferencesDto(
      userId = getObject("user_id", UUID::class.java).toString(),
      orderUpdates = getBoolean("order_updates"),
      promoAlerts = getBoolean("promo_alerts"),
      kdsSoundEnabled = getBoolean("kds_sound_enabled"),
      updatedAt = getObject("updated_at", OffsetDateTime::class.java).toInstant().toString(),
    )

  companion object {
    fun isCategoryAllowed(
      conn: Connection,
      userId: UUID,
      category: String,
    ): Boolean {
      val (orderUpdates, promoAlerts) = conn.prepareStatement(
        "SELECT order_updates, promo_alerts FROM notification_preferences WHERE user_id = ?",
      ).use { ps ->
        ps.setObject(1, userId)
        ps.executeQuery().use { rs ->
          if (rs.next()) {
            rs.getBoolean("order_updates") to rs.getBoolean("promo_alerts")
          } else {
            true to true
          }
        }
      }
      return when (category.uppercase()) {
        NotificationCategory.ORDER_UPDATE -> orderUpdates
        NotificationCategory.PROMO_ALERT -> promoAlerts
        else -> true
      }
    }

    fun insertInboxNotificationIfAllowed(
      conn: Connection,
      userId: UUID,
      eventId: UUID,
      title: String,
      body: String,
      category: String,
      referenceId: UUID?,
      deliveryStatus: NotificationDeliveryStatus = NotificationDeliveryStatus.PENDING,
    ): Boolean {
      if (!isCategoryAllowed(conn, userId, category)) {
        return false
      }
      conn.prepareStatement(
        """
        INSERT INTO notification_inbox (
          id, user_id, event_id, title, body, category, reference_id, delivery_status
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (user_id, event_id) DO NOTHING
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setObject(2, userId)
        ps.setObject(3, eventId)
        ps.setString(4, title)
        ps.setString(5, body)
        ps.setString(6, category)
        if (referenceId != null) ps.setObject(7, referenceId) else ps.setNull(7, Types.OTHER)
        ps.setString(8, deliveryStatus.name)
        return ps.executeUpdate() > 0
      }
    }
  }
}

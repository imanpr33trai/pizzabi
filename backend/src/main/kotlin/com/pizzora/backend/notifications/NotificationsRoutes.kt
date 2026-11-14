package com.pizzora.backend.notifications

import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.contracts.UpdateNotificationPreferencesRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

fun Route.notificationsRoutes(
  notificationService: NotificationService,
) {
  authenticate("auth-jwt") {
    route("/api/v1/notifications") {
      get {
        val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
        val unreadOnly = call.request.queryParameters["unread"]?.equals("true", ignoreCase = true) == true
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        call.respond(
          HttpStatusCode.OK,
          notificationService.listNotifications(
            userId = principal.userId,
            unreadOnly = unreadOnly,
            limit = limit,
          ),
        )
      }

      post("/{id}/read") {
        val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
        val notificationId = parseNotificationUuid(call.parameters["id"])
        call.respond(
          HttpStatusCode.OK,
          notificationService.markAsRead(
            userId = principal.userId,
            notificationId = notificationId,
          ),
        )
      }

      post("/read-all") {
        val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
        call.respond(
          HttpStatusCode.OK,
          notificationService.markAllAsRead(principal.userId),
        )
      }

      get("/preferences") {
        val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
        call.respond(
          HttpStatusCode.OK,
          notificationService.getPreferences(principal.userId),
        )
      }

      put("/preferences") {
        val principal = call.principal<PizzoraPrincipal>() ?: throw UnauthorizedException()
        val body = call.receive<UpdateNotificationPreferencesRequest>()
        call.respond(
          HttpStatusCode.OK,
          notificationService.updatePreferences(principal.userId, body),
        )
      }
    }
  }
}

private fun parseNotificationUuid(raw: String?): UUID =
  runCatching { UUID.fromString(raw) }
    .getOrElse { throw ValidationException("INVALID_UUID", "Invalid notification id.") }

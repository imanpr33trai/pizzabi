package com.pizzora.backend.reporting

import com.pizzora.backend.authorization.AuthorizationService
import com.pizzora.backend.authorization.requirePermission
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.reportingRoutes(
  reportingService: ReportingService,
  authorizationService: AuthorizationService,
) {
  authenticate("auth-jwt") {
    requirePermission("owner:reports_read", authorizationService) {
      get("/api/v1/owner/reports/sales") {
        val startDate = call.request.queryParameters["start_date"]
        val endDate = call.request.queryParameters["end_date"]
        call.respond(HttpStatusCode.OK, reportingService.getSalesReport(startDate, endDate))
      }

      get("/api/v1/owner/reports/operations") {
        val startDate = call.request.queryParameters["start_date"]
        val endDate = call.request.queryParameters["end_date"]
        call.respond(HttpStatusCode.OK, reportingService.getOperationsReport(startDate, endDate))
      }
    }
  }
}

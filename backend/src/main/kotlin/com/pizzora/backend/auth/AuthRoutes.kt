package com.pizzora.backend.auth

import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.core.error.TooManyRequestsException
import com.pizzora.backend.core.error.UnauthorizedException
import com.pizzora.backend.core.redis.ResilientRateLimiter
import com.pizzora.contracts.ForgotPasswordRequest
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.RefreshTokenRequest
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.ResendVerificationRequest
import com.pizzora.contracts.ResetPasswordRequest
import com.pizzora.contracts.VerifyEmailRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.authRoutes(
  authService: AuthService,
  wsTicketService: WsTicketService,
  rateLimiter: ResilientRateLimiter,
) {
  route("/api/v1/auth") {
    post("/register") {
      val ip = call.request.origin.remoteAddress
      if (!rateLimiter.isAllowed("auth:register:$ip", maxRequests = 15, windowSeconds = 60)) {
        throw TooManyRequestsException()
      }
      val body = call.receive<RegisterRequest>()
      call.respond(HttpStatusCode.Created, authService.register(body))
    }

    post("/verify-email") {
      val body = call.receive<VerifyEmailRequest>()
      call.respond(HttpStatusCode.OK, authService.verifyEmail(body))
    }

    post("/resend-verification") {
      val ip = call.request.origin.remoteAddress
      if (!rateLimiter.isAllowed("auth:resend:$ip", maxRequests = 10, windowSeconds = 60)) {
        throw TooManyRequestsException()
      }
      val body = call.receive<ResendVerificationRequest>()
      call.respond(HttpStatusCode.Accepted, authService.resendVerification(body))
    }

    post("/login") {
      val ip = call.request.origin.remoteAddress
      if (!rateLimiter.isAllowed("auth:login:$ip", maxRequests = 20, windowSeconds = 60)) {
        throw TooManyRequestsException()
      }
      val body = call.receive<LoginRequest>()
      call.respond(HttpStatusCode.OK, authService.login(body, ip))
    }

    post("/refresh") {
      val body = call.receive<RefreshTokenRequest>()
      call.respond(HttpStatusCode.OK, authService.refresh(body))
    }

    post("/forgot-password") {
      val ip = call.request.origin.remoteAddress
      if (!rateLimiter.isAllowed("auth:forgot:$ip", maxRequests = 10, windowSeconds = 60)) {
        throw TooManyRequestsException()
      }
      val body = call.receive<ForgotPasswordRequest>()
      call.respond(HttpStatusCode.Accepted, authService.forgotPassword(body))
    }

    post("/reset-password") {
      val body = call.receive<ResetPasswordRequest>()
      call.respond(HttpStatusCode.OK, authService.resetPassword(body))
    }

    authenticate("auth-jwt") {
      post("/logout") {
        val principal = call.principal<PizzoraPrincipal>()
          ?: throw UnauthorizedException()
        call.respond(HttpStatusCode.OK, authService.logout(principal.sessionId))
      }

      post("/ws-ticket") {
        val principal = call.principal<PizzoraPrincipal>()
          ?: throw UnauthorizedException()
        call.respond(HttpStatusCode.OK, wsTicketService.mintTicket(principal))
      }
    }
  }
}

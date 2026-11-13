package com.pizzora.backend.core.email

import com.pizzora.backend.core.config.SmtpConfig
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

interface EmailDeliveryProvider {
  suspend fun sendVerificationEmail(toEmail: String, fullName: String, token: String)
  suspend fun sendPasswordResetEmail(toEmail: String, fullName: String, token: String)
}

class SmtpEmailDeliveryProvider(
  private val config: SmtpConfig,
) : EmailDeliveryProvider {
  private val logger = LoggerFactory.getLogger(SmtpEmailDeliveryProvider::class.java)

  override suspend fun sendVerificationEmail(toEmail: String, fullName: String, token: String) {
    sendTextEmail(
      toEmail = toEmail,
      subject = "Verify your PIZZORA email address",
      body = """
        Hi $fullName,

        Use this verification token in the PIZZORA app to verify your email:
        $token

        This token expires in 24 hours and can only be used once.
      """.trimIndent(),
    )
  }

  override suspend fun sendPasswordResetEmail(toEmail: String, fullName: String, token: String) {
    sendTextEmail(
      toEmail = toEmail,
      subject = "Reset your PIZZORA password",
      body = """
        Hi $fullName,

        Use this single-use password reset token in the PIZZORA app:
        $token

        This token expires in 15 minutes. If you did not request this reset, ignore this email.
      """.trimIndent(),
    )
  }

  private suspend fun sendTextEmail(toEmail: String, subject: String, body: String) = withContext(Dispatchers.IO) {
    val props = Properties().apply {
      put("mail.smtp.host", config.host)
      put("mail.smtp.port", config.port.toString())
      put("mail.smtp.auth", "false")
      put("mail.smtp.connectiontimeout", "3000")
      put("mail.smtp.timeout", "3000")
    }
    runCatching {
      val session = Session.getInstance(props)
      val message = MimeMessage(session).apply {
        setFrom(InternetAddress(config.fromAddress))
        setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail, false))
        setSubject(subject)
        setText(body, "UTF-8")
      }
      Transport.send(message)
    }.onFailure { err ->
      logger.error("SMTP email delivery failed host={} port={} to={}", config.host, config.port, toEmail, err)
      throw err
    }
  }
}

class InMemoryEmailDeliveryProvider : EmailDeliveryProvider {
  data class SentEmail(
    val type: String,
    val toEmail: String,
    val fullName: String,
    val token: String,
  )

  val outbox = CopyOnWriteArrayList<SentEmail>()

  override suspend fun sendVerificationEmail(toEmail: String, fullName: String, token: String) {
    outbox += SentEmail("VERIFICATION", toEmail, fullName, token)
  }

  override suspend fun sendPasswordResetEmail(toEmail: String, fullName: String, token: String) {
    outbox += SentEmail("PASSWORD_RESET", toEmail, fullName, token)
  }
}

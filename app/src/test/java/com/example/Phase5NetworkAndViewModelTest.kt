package com.example

import com.example.auth.AuthNavigationEvent
import com.example.auth.AuthRepository
import com.example.auth.AuthViewModel
import com.example.auth.NetworkAuthRepository
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.pizzora.contracts.ApiErrorDetail
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.MessageResponse
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Phase5NetworkAndViewModelTest {
  private val testDispatcher = StandardTestDispatcher()
  private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

  private val sampleUser = UserProfileDto(
    id = "00000000-0000-7000-8000-000000000101",
    email = "asha@pizzora.test",
    emailVerified = true,
    fullName = "Asha Rao",
    phone = "9876543210",
    role = UserRole.CUSTOMER,
    isActive = true,
  )

  private class FakeTokenStorage(initial: AuthSession? = null) : TokenStorage {
    private val _session = MutableStateFlow(initial)
    private val _muted = MutableStateFlow(false)
    override val sessionFlow: StateFlow<AuthSession?> = _session.asStateFlow()
    override fun currentSession(): AuthSession? = _session.value
    override suspend fun saveSession(session: AuthSession) {
      _session.value = session
    }
    override suspend fun clearSession() {
      _session.value = null
    }
    override fun observeKdsSoundMuted(): Flow<Boolean> = _muted
    override suspend fun setKdsSoundMuted(muted: Boolean) {
      _muted.value = muted
    }
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `concurrent 401 calls trigger single-flight token refresh once via Mutex`() = runTest {
    val refreshCalls = AtomicInteger(0)
    val storage = FakeTokenStorage(
      AuthSession(
        accessToken = "expired_access",
        refreshToken = "refresh_1",
        user = sampleUser,
      ),
    )

    val engine = MockEngine(
      MockEngineConfig().apply {
        dispatcher = testDispatcher
        addHandler { request ->
          when (request.url.encodedPath) {
        "/api/v1/auth/refresh" -> {
          refreshCalls.incrementAndGet()
          respond(
            content = json.encodeToString(
              AuthTokensResponse(
                accessToken = "fresh_access",
                refreshToken = "refresh_2",
                expiresInSeconds = 900L,
                user = sampleUser,
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        "/api/v1/me" -> {
          val authHeader = request.headers[HttpHeaders.Authorization]
          if (authHeader == "Bearer fresh_access") {
            respond(
              content = json.encodeToString(sampleUser),
              status = HttpStatusCode.OK,
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
          } else {
            respond(
              content = json.encodeToString(
                ApiErrorEnvelope(
                  error = ApiErrorDetail("UNAUTHORIZED", "Expired token"),
                  correlationId = "c-1",
                  timestamp = "2026-11-13T00:00:00Z",
                ),
              ),
              status = HttpStatusCode.Unauthorized,
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
          }
        }
        else -> error("Unexpected path ${request.url.encodedPath}")
          }
        }
      },
    )

    val apiClient = PizzoraApiClient("http://localhost:8080", storage, engine)
    val profiles = (1..5).map { async { apiClient.getMe() } }.awaitAll()

    assertEquals(5, profiles.size)
    assertTrue(profiles.all { it.email == sampleUser.email })
    assertEquals(1, refreshCalls.get())
    assertEquals("fresh_access", storage.currentSession()?.accessToken)
    assertEquals("refresh_2", storage.currentSession()?.refreshToken)
  }

  @Test
  fun `AuthViewModel handles login, unverified redirect, registration, and logout`() = runTest {
    val storage = FakeTokenStorage()
    val engine = MockEngine(
      MockEngineConfig().apply {
        dispatcher = testDispatcher
        addHandler { request ->
          when (request.url.encodedPath) {
        "/api/v1/auth/login" -> {
          val bodyText = when (val b = request.body) {
            is io.ktor.http.content.OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
            is io.ktor.http.content.TextContent -> b.text
            else -> b.toString()
          }
          if (bodyText.contains("unverified@pizzora.test")) {
            respond(
              content = json.encodeToString(
                ApiErrorEnvelope(
                  error = ApiErrorDetail("EMAIL_NOT_VERIFIED", "Verify email first."),
                  correlationId = "c-2",
                  timestamp = "2026-11-13T00:00:00Z",
                ),
              ),
              status = HttpStatusCode.Forbidden,
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
          } else {
            respond(
              content = json.encodeToString(
                AuthTokensResponse("tok-acc", "tok-ref", 900L, sampleUser),
              ),
              status = HttpStatusCode.OK,
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
          }
        }
        "/api/v1/auth/register" -> {
          respond(
            content = json.encodeToString(MessageResponse("Registration successful.")),
            status = HttpStatusCode.Created,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        "/api/v1/auth/logout" -> {
          respond(
            content = json.encodeToString(MessageResponse("Signed out.")),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected path ${request.url.encodedPath}")
          }
        }
      },
    )

    val apiClient = PizzoraApiClient("http://localhost:8080", storage, engine)
    val repo: AuthRepository = NetworkAuthRepository(apiClient, storage)
    val vm = AuthViewModel(repo)

    // Unverified login triggers verification navigation event
    val eventAsync = async { vm.events.first() }
    vm.login("unverified@pizzora.test", "Pass1234!")
    advanceUntilIdle()
    val navEvent = eventAsync.await()
    assertTrue(navEvent is AuthNavigationEvent.NavigateToVerifyEmail)

    // Verified login saves session
    vm.login("asha@pizzora.test", "Pass1234!")
    advanceUntilIdle()
    assertEquals(UserRole.CUSTOMER, vm.sessionFlow.value?.user?.role)

    // Logout clears session
    vm.logout()
    advanceUntilIdle()
    assertNull(vm.sessionFlow.value)
  }
}

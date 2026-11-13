package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.catalog.NetworkCatalogRepository
import com.example.core.database.PizzoraDatabase
import com.example.core.media.ImageCompressor
import com.example.core.media.ProductImagePickerButton
import com.example.core.media.ProductImageUploader
import com.example.core.network.PizzoraApiClient
import com.example.core.security.AuthSession
import com.example.core.security.TokenStorage
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.PresignProductImageResponse
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductImageDto
import com.pizzora.contracts.ProductImageStatus
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UserProfileDto
import com.pizzora.contracts.UserRole
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase6MediaAndCatalogTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var db: PizzoraDatabase
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private val ownerUser = UserProfileDto(
    id = "owner-1",
    email = "owner@pizzora.test",
    emailVerified = true,
    fullName = "Marco Rossi",
    phone = "9811111111",
    role = UserRole.OWNER,
    isActive = true,
  )

  private class InMemoryTokenStorage(initial: AuthSession? = null) : TokenStorage {
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
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, PizzoraDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `ImageCompressor scales oversized bitmaps within 1280px and outputs valid JPEG under 5MB`() {
    val largeBitmap = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
    Canvas(largeBitmap).drawColor(Color.rgb(197, 47, 30))
    val rawOut = ByteArrayOutputStream()
    largeBitmap.compress(Bitmap.CompressFormat.PNG, 100, rawOut)
    largeBitmap.recycle()

    val compressor = ImageCompressor()
    val result = compressor.compressImageBytes(rawOut.toByteArray())

    assertEquals("image/jpeg", result.mimeType)
    assertTrue(result.width <= 1280)
    assertTrue(result.height <= 1280)
    assertTrue(result.bytes.size <= ImageCompressor.MAX_UPLOAD_BYTES)
    // JPEG magic bytes FF D8 FF
    assertEquals(0xFF.toByte(), result.bytes[0])
    assertEquals(0xD8.toByte(), result.bytes[1])
    assertEquals(0xFF.toByte(), result.bytes[2])
  }

  @Test
  fun `ProductImageUploader executes presign, raw PUT without Auth header, and confirm`() = runBlocking {
    val storage = InMemoryTokenStorage(AuthSession("owner-jwt", "ref-1", ownerUser))
    val uploadedWithoutAuthHeader = AtomicBoolean(false)

    val engine = MockEngine { request ->
      when {
        request.url.encodedPath == "/api/v1/owner/products/prod-1/images/presign" && request.method == HttpMethod.Post -> {
          assertEquals("Bearer owner-jwt", request.headers[HttpHeaders.Authorization])
          respond(
            content = json.encodeToString(
              PresignProductImageResponse(
                imageId = "img-1",
                objectKey = "products/prod-1/img-1.jpg",
                uploadUrl = "http://minio.local:9000/pizzora-product-images/products/prod-1/img-1.jpg?X-Amz-Signature=abc",
                requiredHeaders = mapOf("Content-Type" to "image/jpeg"),
                expiresInSeconds = 300L,
                publicUrl = "http://minio.local:9000/pizzora-product-images/products/prod-1/img-1.jpg",
              ),
            ),
            status = HttpStatusCode.Created,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        request.url.host == "minio.local" && request.method == HttpMethod.Put -> {
          assertNull(request.headers[HttpHeaders.Authorization])
          uploadedWithoutAuthHeader.set(true)
          respond(content = "", status = HttpStatusCode.OK)
        }
        request.url.encodedPath == "/api/v1/owner/products/prod-1/images/confirm" && request.method == HttpMethod.Post -> {
          assertEquals("Bearer owner-jwt", request.headers[HttpHeaders.Authorization])
          respond(
            content = json.encodeToString(
              ProductImageDto(
                id = "img-1",
                productId = "prod-1",
                objectKey = "products/prod-1/img-1.jpg",
                publicUrl = "http://minio.local:9000/pizzora-product-images/products/prod-1/img-1.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 64L,
                status = ProductImageStatus.ACTIVE,
                sortOrder = 0,
                createdAt = "2026-11-13T12:00:00Z",
              ),
            ),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected request ${request.method.value} ${request.url}")
      }
    }

    val apiClient = PizzoraApiClient("http://localhost:8080", storage, engine)
    val compressor = ImageCompressor()
    val uploader = ProductImageUploader(apiClient, compressor)

    val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
    val pngStream = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, pngStream)
    bmp.recycle()

    val compressed = compressor.compressImageBytes(pngStream.toByteArray())
    val uploaded = uploader.uploadCompressedImage("prod-1", compressed)

    assertTrue(uploadedWithoutAuthHeader.get())
    assertEquals(ProductImageStatus.ACTIVE, uploaded.status)
    assertEquals("img-1", uploaded.id)
  }

  @Test
  fun `CatalogRepository caches public menu in Room and serves cached snapshot offline`() = runBlocking {
    val storage = InMemoryTokenStorage()
    var failNetwork = false
    val sampleMenu = PublicMenuResponse(
      categories = listOf(CategoryDto("cat-1", "Classics", 1, true, "2026-11-13T10:00:00Z")),
      products = listOf(
        ProductDto(
          id = "prod-1",
          categoryId = "cat-1",
          categoryName = "Classics",
          name = "Diavola",
          description = "Spicy Calabrian salami",
          isVeg = false,
          isActive = true,
          isTaxable = true,
          variants = listOf(ProductVariantDto("var-1", "prod-1", SizeCode.MEDIUM, 45_000L, true)),
        ),
      ),
      crusts = listOf(CrustOptionDto("crust-1", "Sourdough", 4_000L, true)),
      toppings = listOf(ToppingDto("top-1", "Basil", true, 3_000L, true)),
      gstBps = 500,
      storeName = "PIZZORA",
      isAcceptingOrders = true,
    )

    val engine = MockEngine {
      if (failNetwork) {
        respond(content = "Offline", status = HttpStatusCode.ServiceUnavailable)
      } else {
        respond(
          content = json.encodeToString(sampleMenu),
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
      }
    }

    val repo = NetworkCatalogRepository(
      apiClient = PizzoraApiClient("http://localhost:8080", storage, engine),
      catalogCacheDao = db.catalogCacheDao(),
    )

    val onlineSnap = repo.refreshPublicMenu()
    assertFalse(onlineSnap.isFromOfflineCache)
    assertEquals(1, onlineSnap.cachedProducts.size)

    failNetwork = true
    val offlineSnap = repo.refreshPublicMenu()
    assertTrue(offlineSnap.isFromOfflineCache)
    assertEquals("Diavola", offlineSnap.cachedProducts.first().name)
  }

  @Test
  fun `ProductImagePickerButton renders with testTag and zero storage permissions declared`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val pkgInfo = context.packageManager.getPackageInfo(
      context.packageName,
      android.content.pm.PackageManager.GET_PERMISSIONS,
    )
    val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
    assertFalse(perms.contains(android.Manifest.permission.READ_EXTERNAL_STORAGE))
    assertFalse(perms.contains("android.permission.READ_MEDIA_IMAGES"))

    composeTestRule.setContent {
      com.example.ui.theme.PizzoraTheme {
        ProductImagePickerButton(onImageSelected = {})
      }
    }
    composeTestRule.onNodeWithTag("pick_product_image_button").assertIsDisplayed()
  }
}

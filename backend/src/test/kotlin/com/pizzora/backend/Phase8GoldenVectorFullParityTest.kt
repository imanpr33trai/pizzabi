package com.pizzora.backend

import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.error.AppException
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.pricing.PricingService
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.PricingQuoteRequest
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Full 33-vector Phase 8 pricing parity coverage for the Kotlin backend.
 *
 * The binding contract (docs/PHASE8_REVIEWED_CONTRACT_RESOLUTIONS.md §8) names
 * tools/golden-vectors/pizzora_phase8_modifier_vectors.json as the canonical 33-vector fixture.
 * tools/golden-vectors/pizzora_core_parity.json is the older broad core corpus and is deliberately
 * not used here: it has no Phase 8 pricing vectors. Every vector below is executed through the
 * production [PricingService] (backed by embedded PostgreSQL + SQLx V4-V11 objects), never through
 * a duplicate Kotlin pricing formula.
 */
class Phase8GoldenVectorFullParityTest {
  companion object {
    private const val CANONICAL_FIXTURE_SHA256 =
      "a1e6f596d14c9a74ea820d20fbe7a349bbbcfab29bb02cf7464598ea9fc61623"

    private val EXPECTED_IDS = (1..28).map { "U-${"%02d".format(it)}" } +
      (1..5).map { "Q-${"%02d".format(it)}" }

    private val JSON = Json { ignoreUnknownKeys = true }

    private lateinit var pg: EmbeddedPostgres

    @JvmStatic
    @BeforeClass
    fun startInfra() {
      pg = EmbeddedPostgres.builder().start()
      DatabaseFactory.migrateAndConnect(pg.postgresDatabase)
      Phase8TestSupport.applyPhase8Schema(pg.postgresDatabase)
    }

    @JvmStatic
    @AfterClass
    fun stopInfra() {
      pg.close()
    }
  }

  @Test
  fun `canonical fixture digest and exactly 33 golden vectors are stable`() {
    val root = repoRoot()
    val fixture = canonicalFixture(root)
    assertTrue("Missing canonical 33-vector fixture: ${fixture.path}", fixture.isFile)

    val digest = sha256(fixture)
    println("FIXTURE=${fixture.path}")
    println("FIXTURE_SHA256=$digest")
    assertEquals("Canonical 33-vector fixture changed", CANONICAL_FIXTURE_SHA256, digest)

    val vectors = loadVectors(root)
    println("MODIFIER_FIXTURE_VECTOR_COUNT=${vectors.size}")
    assertEquals(33, vectors.size)
    assertEquals(EXPECTED_IDS, vectors.map { it.id })

    val digestFile = File(root, "qa-artifacts/phase8/parity/kotlin_parity_fixture_sha256.txt")
    digestFile.parentFile.mkdirs()
    digestFile.writeText(
      buildString {
        appendLine("# Canonical Kotlin Phase 8 33-vector parity fixture (SHA-256 of raw bytes)")
        appendLine("pizzora_phase8_modifier_vectors.json=$CANONICAL_FIXTURE_SHA256")
        appendLine("verified_vector_count=33")
      },
    )
    println("WROTE_FIXTURE_DIGEST=${digestFile.path}")
  }

  @Test
  fun `all 33 golden vectors pass through the backend PricingService`() = runBlocking {
    val vectors = loadVectors(repoRoot())
    assertEquals(33, vectors.size)

    val service = PricingService(
      dataSource = pg.postgresDatabase,
      couponService = CouponService(pg.postgresDatabase),
      publicUrlResolver = { null },
    )
    val userId = UUID.randomUUID()
    val verified = mutableListOf<String>()

    vectors.forEach { vector ->
      println("VECTOR ${vector.id} ${vector.scenario}")
      when {
        vector.id.startsWith("U-") -> verifyUnitVector(vector, service, userId, verified)
        vector.id.startsWith("Q-") -> verifyQuoteVector(vector, service, userId, verified)
        else -> error("Unknown vector id ${vector.id}")
      }
    }

    assertEquals(EXPECTED_IDS, verified)
    println("VERIFIED_VECTOR_COUNT=${verified.size}")
    println("VERIFIED_VECTOR_IDS=${verified.joinToString(",")}")
  }

  private suspend fun verifyUnitVector(
    vector: GoldenVector,
    service: PricingService,
    userId: UUID,
    verified: MutableList<String>,
  ) {
    val inputs = vector.inputs
    val catalog = seedUnitCatalog(pg.postgresDatabase, vector)
    val request = PricingQuoteRequest(
      items = listOf(
        CartItemInputDto(
          productId = catalog.productId.toString(),
          variantId = catalog.variantId.toString(),
          crustId = catalog.crustId.toString(),
          toppingIds = catalog.extraToppingIds.map(UUID::toString),
          quantity = inputs.quantity ?: 1,
          cheeseId = catalog.cheeseId.toString(),
          sauceId = catalog.sauceId.toString(),
          standardToppingIds = catalog.selectedStandardToppingIds.map(UUID::toString),
          halfAndHalfConfigId = catalog.halfAndHalfConfigId?.toString(),
          halfAndHalfLeftToppingIds = catalog.leftToppingIds.map(UUID::toString),
          halfAndHalfRightToppingIds = catalog.rightToppingIds.map(UUID::toString),
        ),
      ),
      fulfillmentType = FulfillmentType.PICKUP,
    )

    val expectedError = vector.expectedError
    if (expectedError != null) {
      val thrown = runCatching { service.quotePrice(userId, request) }.exceptionOrNull()
        ?: error("${vector.id}: expected $expectedError but quote succeeded")
      val message = thrown.message.orEmpty()
      val observedCode = (thrown as? AppException)?.code
      assertTrue(
        "${vector.id}: expected error $expectedError, got ${thrown.javaClass.simpleName} " +
          "code=$observedCode message=$message",
        observedCode == expectedError ||
          message.contains(expectedError, ignoreCase = true) ||
          message.contains("overflow", ignoreCase = true),
      )
      println(
        "VECTOR ${vector.id} expected_error=$expectedError " +
          "observed=${thrown.javaClass.simpleName} code=$observedCode message=$message",
      )
      verified += vector.id
      return
    }

    val response = service.quotePrice(userId, request)
    val item = response.items.single()
    assertTrue("${vector.id}: item unavailable: ${item.issues}", item.isAvailable)

    val expectedUnit = vector.expectedUnitPricePaise
      ?: error("${vector.id}: expected_unit_price_paise missing")
    assertEquals("${vector.id}: unit price", expectedUnit, item.unitPricePaise)
    vector.expectedLineTotalPaise?.let {
      assertEquals("${vector.id}: line total", it, item.lineTotalPaise)
    }

    // The quote breakdown fields are the same ones the backend exposes to clients. Derive their
    // expected values from the fixture inputs; the values under test come from PricingService.
    val quantity = inputs.quantity ?: 1
    val expectedCheese = Math.multiplyExact(inputs.cheeseExtraPaise ?: 0L, quantity.toLong())
    val expectedSauce = Math.multiplyExact(inputs.sauceExtraPaise ?: 0L, quantity.toLong())
    val expectedStandards = Math.multiplyExact(
      inputs.standardToppings.filter { !it.isDefault && !it.isRemoved }
        .fold(0L) { acc, topping -> Math.addExact(acc, topping.extraPricePaise) },
      quantity.toLong(),
    )
    val expectedHalf = Math.multiplyExact(inputs.halfAndHalf?.surchargePaise ?: 0L, quantity.toLong())
    val expectedModifier = Math.addExact(
      Math.addExact(Math.addExact(expectedCheese, expectedSauce), expectedStandards),
      expectedHalf,
    )
    assertEquals("${vector.id}: cheese breakdown", expectedCheese, response.breakdown.cheeseExtraPaise)
    assertEquals("${vector.id}: sauce breakdown", expectedSauce, response.breakdown.sauceExtraPaise)
    assertEquals("${vector.id}: standard breakdown", expectedStandards, response.breakdown.standardToppingsExtraPaise)
    assertEquals("${vector.id}: half-and-half breakdown", expectedHalf, response.breakdown.halfAndHalfSurchargePaise)
    assertEquals("${vector.id}: modifier breakdown", expectedModifier, response.breakdown.modifierExtraPaise)

    println(
      "VECTOR ${vector.id} expected_unit=$expectedUnit observed_unit=${item.unitPricePaise} " +
        "line_total=${item.lineTotalPaise} breakdown=" +
        "cheese=${response.breakdown.cheeseExtraPaise},sauce=${response.breakdown.sauceExtraPaise}," +
        "standards=${response.breakdown.standardToppingsExtraPaise}," +
        "half=${response.breakdown.halfAndHalfSurchargePaise}," +
        "modifier=${response.breakdown.modifierExtraPaise}",
    )
    verified += vector.id
  }

  private suspend fun verifyQuoteVector(
    vector: GoldenVector,
    service: PricingService,
    userId: UUID,
    verified: MutableList<String>,
  ) {
    val inputs = vector.inputs
    val expected = vector.expectedQuote ?: error("${vector.id}: expected_quote missing")
    val gstBps = inputs.gstBps ?: error("${vector.id}: gst_bps missing")
    val deliveryFee = inputs.deliveryFeePaise ?: error("${vector.id}: delivery_fee_paise missing")

    setGstBps(pg.postgresDatabase, gstBps)
    val pincode = "P8-${vector.id}"
    insertDeliveryZone(pg.postgresDatabase, pincode, deliveryFee)
    val couponCode = inputs.coupon?.let { insertCoupon(pg.postgresDatabase, vector.id, it) }

    val items = inputs.lines.mapIndexed { index, line ->
      val lineCatalog = seedQuoteLineCatalog(pg.postgresDatabase, vector.id, index, line)
      CartItemInputDto(
        productId = lineCatalog.first.toString(),
        variantId = lineCatalog.second.toString(),
        crustId = lineCatalog.third.toString(),
        quantity = line.quantity,
      )
    }

    val response = service.quotePrice(
      userId = userId,
      request = PricingQuoteRequest(
        items = items,
        fulfillmentType = FulfillmentType.DELIVERY,
        pincode = pincode,
        couponCode = couponCode,
      ),
    )
    val breakdown = response.breakdown
    assertEquals("${vector.id}: subtotal", expected.subtotalPaise, breakdown.subtotalPaise)
    assertEquals("${vector.id}: taxable subtotal", expected.taxableSubtotalPaise, breakdown.taxableSubtotalPaise)
    assertEquals("${vector.id}: discount", expected.discountPaise, breakdown.discountPaise)
    assertEquals("${vector.id}: taxable amount", expected.taxableAmountPaise, breakdown.taxableAmountPaise)
    assertEquals("${vector.id}: delivery fee", expected.deliveryFeePaise, breakdown.deliveryFeePaise)
    assertEquals("${vector.id}: gst bps", expected.gstBps, breakdown.gstBps)
    assertEquals("${vector.id}: gst bps response", expected.gstBps, response.gstBps)
    assertEquals("${vector.id}: tax", expected.taxPaise, breakdown.taxPaise)
    assertEquals("${vector.id}: cgst", expected.cgstPaise, breakdown.cgstPaise)
    assertEquals("${vector.id}: sgst", expected.sgstPaise, breakdown.sgstPaise)
    assertEquals("${vector.id}: total", expected.totalPaise, breakdown.totalPaise)

    println(
      "VECTOR ${vector.id} expected_quote=$expected " +
        "observed_quote=subtotal=${breakdown.subtotalPaise},taxable=${breakdown.taxableSubtotalPaise}," +
        "discount=${breakdown.discountPaise},taxable_amount=${breakdown.taxableAmountPaise}," +
        "delivery=${breakdown.deliveryFeePaise},gst=${breakdown.gstBps},tax=${breakdown.taxPaise}," +
        "cgst=${breakdown.cgstPaise},sgst=${breakdown.sgstPaise},total=${breakdown.totalPaise}",
    )
    verified += vector.id
  }

  private data class UnitCatalog(
    val productId: UUID,
    val variantId: UUID,
    val crustId: UUID,
    val cheeseId: UUID,
    val sauceId: UUID,
    val extraToppingIds: List<UUID>,
    val selectedStandardToppingIds: List<UUID>,
    val halfAndHalfConfigId: UUID?,
    val leftToppingIds: List<UUID>,
    val rightToppingIds: List<UUID>,
  )

  private fun seedUnitCatalog(dataSource: DataSource, vector: GoldenVector): UnitCatalog {
    val inputs = vector.inputs
    val categoryId = UUID.randomUUID()
    val productId = UUID.randomUUID()
    val variantId = UUID.randomUUID()
    val crustId = UUID.randomUUID()
    val cheeseId = vectorUuid(vector.id, "cheese")
    val sauceId = vectorUuid(vector.id, "sauce")
    val halfConfigId = inputs.halfAndHalf?.let { vectorUuid(vector.id, "half-config") }
    var resolvedHalfConfigId: UUID? = null
    var extraToppingIds: List<UUID> = emptyList()
    var leftIds: List<UUID> = emptyList()
    var rightIds: List<UUID> = emptyList()

    dataSource.connection.use { conn ->
      conn.autoCommit = true
      insertCategory(conn, categoryId, "P8 cat ${vector.id}")
      insertProduct(conn, productId, categoryId, "P8 product ${vector.id}")
      insertVariant(conn, variantId, productId, inputs.variantPricePaise ?: 0L)
      insertCrust(conn, crustId, "P8 crust ${vector.id}", inputs.crustExtraPaise ?: 0L)
      insertCheese(conn, cheeseId, productId, "P8 cheese ${vector.id}", inputs.cheeseExtraPaise ?: 0L)
      insertSauce(conn, sauceId, productId, "P8 sauce ${vector.id}", inputs.sauceExtraPaise ?: 0L)

      inputs.standardToppings.forEach { topping ->
        insertStandardTopping(
          conn = conn,
          id = vectorUuid(vector.id, topping.id),
          productId = productId,
          name = "P8 std ${vector.id} ${topping.id}",
          extraPaise = topping.extraPricePaise,
          isDefault = topping.isDefault,
        )
      }

      extraToppingIds = inputs.extraToppingExtras.mapIndexed { index, extra ->
        val id = vectorUuid(vector.id, "extra-$index")
        insertTopping(conn, id, "P8 extra ${vector.id} $index", extra)
        insertProductTopping(conn, productId, id, index)
        id
      }
      val half = inputs.halfAndHalf
      if (half != null) {
        leftIds = half.leftToppingExtras.mapIndexed { index, extra ->
          val id = vectorUuid(vector.id, "left-$index")
          insertTopping(conn, id, "P8 left ${vector.id} $index", extra)
          insertProductTopping(conn, productId, id, index)
          id
        }
        rightIds = half.rightToppingExtras.mapIndexed { index, extra ->
          val id = vectorUuid(vector.id, "right-$index")
          insertTopping(conn, id, "P8 right ${vector.id} $index", extra)
          insertProductTopping(conn, productId, id, 100 + index)
          id
        }
        resolvedHalfConfigId = halfConfigId ?: error("half config id missing for ${vector.id}")
        insertHalfConfig(
          conn = conn,
          id = resolvedHalfConfigId,
          productId = productId,
          name = "P8 half ${vector.id}",
          leftIds = leftIds,
          rightIds = rightIds,
          surchargePaise = half.surchargePaise,
        )
      }
      check(extraToppingIds.size == inputs.extraToppingExtras.size)
    }

    return UnitCatalog(
      productId = productId,
      variantId = variantId,
      crustId = crustId,
      cheeseId = cheeseId,
      sauceId = sauceId,
      extraToppingIds = extraToppingIds,
      selectedStandardToppingIds = inputs.standardToppings
        .filter { !it.isRemoved }
        .map { vectorUuid(vector.id, it.id) },
      halfAndHalfConfigId = resolvedHalfConfigId,
      leftToppingIds = leftIds,
      rightToppingIds = rightIds,
    )
  }

  private fun seedQuoteLineCatalog(
    dataSource: DataSource,
    vectorId: String,
    index: Int,
    line: QuoteLine,
  ): Triple<UUID, UUID, UUID> {
    val categoryId = UUID.randomUUID()
    val productId = UUID.randomUUID()
    val variantId = UUID.randomUUID()
    val crustId = UUID.randomUUID()
    dataSource.connection.use { conn ->
      conn.autoCommit = true
      insertCategory(conn, categoryId, "P8 quote cat $vectorId $index")
      insertProduct(conn, productId, categoryId, "P8 quote product $vectorId $index", line.isTaxable)
      insertVariant(conn, variantId, productId, line.unitPricePaise)
      insertCrust(conn, crustId, "P8 quote crust $vectorId $index", 0L)
    }
    return Triple(productId, variantId, crustId)
  }

  private fun insertCategory(conn: Connection, id: UUID, name: String) {
    conn.prepareStatement(
      "INSERT INTO categories (id, name, sort_order, is_active) VALUES (?, ?, 0, TRUE)",
    ).use { ps ->
      ps.setObject(1, id)
      ps.setString(2, name.take(200))
      ps.executeUpdate()
    }
  }

  private fun insertProduct(
    conn: Connection,
    id: UUID,
    categoryId: UUID,
    name: String,
    isTaxable: Boolean = true,
  ) {
    conn.prepareStatement(
      """INSERT INTO products (id, category_id, name, description, is_veg, is_active, is_taxable)
         VALUES (?, ?, ?, '', TRUE, TRUE, ?)""".trimIndent(),
    ).use { ps ->
      ps.setObject(1, id)
      ps.setObject(2, categoryId)
      ps.setString(3, name.take(200))
      ps.setBoolean(4, isTaxable)
      ps.executeUpdate()
    }
  }

  private fun insertVariant(conn: Connection, id: UUID, productId: UUID, pricePaise: Long) {
    conn.prepareStatement(
      """INSERT INTO product_variants (id, product_id, size_code, price_paise, is_active)
         VALUES (?, ?, 'REGULAR', ?, TRUE)""".trimIndent(),
    ).use { ps ->
      ps.setObject(1, id)
      ps.setObject(2, productId)
      ps.setLong(3, pricePaise)
      ps.executeUpdate()
    }
  }

  private fun insertCrust(conn: Connection, id: UUID, name: String, extraPaise: Long) {
    conn.prepareStatement(
      "INSERT INTO crust_options (id, name, extra_price_paise, is_active) VALUES (?, ?, ?, TRUE)",
    ).use { ps ->
      ps.setObject(1, id)
      ps.setString(2, name.take(200))
      ps.setLong(3, extraPaise)
      ps.executeUpdate()
    }
  }

  private fun insertTopping(conn: Connection, id: UUID, name: String, extraPaise: Long) {
    conn.prepareStatement(
      "INSERT INTO toppings (id, name, is_veg, extra_price_paise, is_active) VALUES (?, ?, TRUE, ?, TRUE)",
    ).use { ps ->
      ps.setObject(1, id)
      ps.setString(2, name.take(200))
      ps.setLong(3, extraPaise)
      ps.executeUpdate()
    }
  }

  private fun insertProductTopping(conn: Connection, productId: UUID, toppingId: UUID, sortOrder: Int) {
    conn.prepareStatement(
      "INSERT INTO product_toppings (product_id, topping_id, sort_order) VALUES (?, ?, ?)",
    ).use { ps ->
      ps.setObject(1, productId)
      ps.setObject(2, toppingId)
      ps.setInt(3, sortOrder)
      ps.executeUpdate()
    }
  }

  private fun insertCheese(conn: Connection, id: UUID, productId: UUID, name: String, extraPaise: Long) {
    conn.prepareStatement(
      """INSERT INTO cheeses (id, product_id, name, extra_price_paise, is_active, sort_order)
         VALUES (?, ?, ?, ?, TRUE, 0)""".trimIndent(),
    ).use { ps ->
      ps.setObject(1, id)
      ps.setObject(2, productId)
      ps.setString(3, name.take(200))
      ps.setLong(4, extraPaise)
      ps.executeUpdate()
    }
  }

  private fun insertSauce(conn: Connection, id: UUID, productId: UUID, name: String, extraPaise: Long) {
    conn.prepareStatement(
      """INSERT INTO sauces (id, product_id, name, extra_price_paise, is_active, sort_order)
         VALUES (?, ?, ?, ?, TRUE, 0)""".trimIndent(),
    ).use { ps ->
      ps.setObject(1, id)
      ps.setObject(2, productId)
      ps.setString(3, name.take(200))
      ps.setLong(4, extraPaise)
      ps.executeUpdate()
    }
  }

  private fun insertStandardTopping(
    conn: Connection,
    id: UUID,
    productId: UUID,
    name: String,
    extraPaise: Long,
    isDefault: Boolean,
  ) {
    conn.prepareStatement(
      """INSERT INTO standard_toppings
         (id, product_id, name, is_veg, extra_price_paise, is_default, is_active, sort_order)
         VALUES (?, ?, ?, TRUE, ?, ?, TRUE, 0)""".trimIndent(),
    ).use { ps ->
      ps.setObject(1, id)
      ps.setObject(2, productId)
      ps.setString(3, name.take(200))
      ps.setLong(4, extraPaise)
      ps.setBoolean(5, isDefault)
      ps.executeUpdate()
    }
  }

  private fun insertHalfConfig(
    conn: Connection,
    id: UUID,
    productId: UUID,
    name: String,
    leftIds: List<UUID>,
    rightIds: List<UUID>,
    surchargePaise: Long,
  ) {
    conn.prepareStatement(
      """INSERT INTO half_and_half_configs
         (id, product_id, name, left_topping_ids, right_topping_ids,
          half_and_half_surcharge_paise, is_active, sort_order)
         VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, TRUE, 0)""".trimIndent(),
    ).use { ps ->
      ps.setObject(1, id)
      ps.setObject(2, productId)
      ps.setString(3, name.take(200))
      ps.setString(4, JSON.encodeToString(leftIds.map(UUID::toString)))
      ps.setString(5, JSON.encodeToString(rightIds.map(UUID::toString)))
      ps.setLong(6, surchargePaise)
      ps.executeUpdate()
    }
  }

  private fun setGstBps(dataSource: DataSource, gstBps: Int) {
    dataSource.connection.use { conn ->
      conn.autoCommit = true
      conn.prepareStatement(
        """INSERT INTO restaurant_settings (id, store_name, is_accepting_orders, gst_bps, default_prep_mins)
           VALUES (1, 'P8 Parity Store', TRUE, ?, 15)
           ON CONFLICT (id) DO UPDATE SET gst_bps = EXCLUDED.gst_bps,
                                          is_accepting_orders = TRUE""".trimIndent(),
      ).use { ps ->
        ps.setInt(1, gstBps)
        ps.executeUpdate()
      }
    }
  }

  private fun insertDeliveryZone(dataSource: DataSource, pincode: String, deliveryFeePaise: Long) {
    dataSource.connection.use { conn ->
      conn.autoCommit = true
      conn.prepareStatement(
        """INSERT INTO delivery_zones
           (id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active)
           VALUES (?, ?, ?, 0, ?, 30, TRUE)""".trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setString(2, pincode)
        ps.setString(3, "P8 Parity Area $pincode")
        ps.setLong(4, deliveryFeePaise)
        ps.executeUpdate()
      }
    }
  }

  private fun insertCoupon(dataSource: DataSource, vectorId: String, coupon: GoldenCoupon): String {
    val code = "P8" + vectorId.replace("-", "")
    val discountType = when (coupon.discountType) {
      "PERCENT_BPS", "PercentBps" -> DiscountType.PERCENT_BPS
      "FLAT_PAISE", "FlatPaise" -> DiscountType.FLAT_PAISE
      else -> error("Unknown coupon discount type ${coupon.discountType} for $vectorId")
    }
    dataSource.connection.use { conn ->
      conn.autoCommit = true
      conn.prepareStatement(
        """INSERT INTO coupons
           (id, code, discount_type, discount_value, max_discount_paise, min_order_paise,
            max_uses, max_uses_per_user, usage_count, starts_at, ends_at, is_active)
           VALUES (?, ?, ?, ?, ?, ?, NULL, 1000000, 0, ?, ?, ?)""".trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setString(2, code)
        ps.setString(3, discountType.name)
        ps.setLong(4, coupon.discountValue)
        if (coupon.maxDiscountPaise == null) ps.setNull(5, java.sql.Types.BIGINT)
        else ps.setLong(5, coupon.maxDiscountPaise)
        ps.setLong(6, coupon.minOrderPaise)
        ps.setTimestamp(7, Timestamp.from(Instant.now().minusSeconds(3600)))
        ps.setTimestamp(8, Timestamp.from(Instant.now().plusSeconds(86400)))
        ps.setBoolean(9, coupon.isActive)
        ps.executeUpdate()
      }
    }
    return code
  }

  private fun vectorUuid(vectorId: String, localId: String): UUID =
    UUID.nameUUIDFromBytes("$vectorId:$localId".toByteArray(Charsets.UTF_8))

  private fun canonicalFixture(root: File) =
    File(root, "tools/golden-vectors/pizzora_phase8_modifier_vectors.json")

  private fun loadVectors(root: File): List<GoldenVector> =
    JSON.decodeFromString<GoldenVectorFile>(canonicalFixture(root).readText()).vectors

  private fun repoRoot(): File {
    var dir = File(System.getProperty("user.dir")).absoluteFile
    repeat(6) {
      if (canonicalFixture(dir).isFile) return dir
      dir = dir.parentFile ?: return dir
    }
    error("Could not locate golden-vector fixtures from ${System.getProperty("user.dir")}")
  }

  private fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes())
      .joinToString("") { "%02x".format(it) }
}

@Serializable
private data class GoldenVectorFile(val vectors: List<GoldenVector>)

@Serializable
private data class GoldenVector(
  val id: String,
  val scenario: String,
  val inputs: GoldenInputs,
  @SerialName("expected_unit_price_paise") val expectedUnitPricePaise: Long? = null,
  @SerialName("expected_line_total_paise") val expectedLineTotalPaise: Long? = null,
  @SerialName("expected_quote") val expectedQuote: ExpectedQuote? = null,
  @SerialName("expected_error") val expectedError: String? = null,
)

@Serializable
private data class GoldenInputs(
  @SerialName("variant_price_paise") val variantPricePaise: Long? = null,
  @SerialName("crust_extra_paise") val crustExtraPaise: Long? = null,
  @SerialName("cheese_extra_paise") val cheeseExtraPaise: Long? = null,
  @SerialName("sauce_extra_paise") val sauceExtraPaise: Long? = null,
  @SerialName("standard_toppings") val standardToppings: List<GoldenStandardTopping> = emptyList(),
  @SerialName("extra_topping_extras") val extraToppingExtras: List<Long> = emptyList(),
  @SerialName("half_and_half") val halfAndHalf: GoldenHalfAndHalf? = null,
  val quantity: Int? = null,
  val lines: List<QuoteLine> = emptyList(),
  @SerialName("gst_bps") val gstBps: Int? = null,
  @SerialName("delivery_fee_paise") val deliveryFeePaise: Long? = null,
  val coupon: GoldenCoupon? = null,
)

@Serializable
private data class GoldenStandardTopping(
  val id: String,
  @SerialName("extra_price_paise") val extraPricePaise: Long,
  @SerialName("is_default") val isDefault: Boolean,
  @SerialName("is_removed") val isRemoved: Boolean,
)

@Serializable
private data class GoldenHalfAndHalf(
  @SerialName("left_topping_extras") val leftToppingExtras: List<Long>,
  @SerialName("right_topping_extras") val rightToppingExtras: List<Long>,
  @SerialName("surcharge_paise") val surchargePaise: Long,
)

@Serializable
private data class QuoteLine(
  @SerialName("unit_price_paise") val unitPricePaise: Long,
  val quantity: Int,
  @SerialName("is_taxable") val isTaxable: Boolean,
)

@Serializable
private data class GoldenCoupon(
  @SerialName("discount_type") val discountType: String,
  @SerialName("discount_value") val discountValue: Long,
  @SerialName("max_discount_paise") val maxDiscountPaise: Long? = null,
  @SerialName("min_order_paise") val minOrderPaise: Long = 0L,
  @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
private data class ExpectedQuote(
  @SerialName("subtotal_paise") val subtotalPaise: Long,
  @SerialName("taxable_subtotal_paise") val taxableSubtotalPaise: Long,
  @SerialName("discount_paise") val discountPaise: Long,
  @SerialName("taxable_amount_paise") val taxableAmountPaise: Long,
  @SerialName("delivery_fee_paise") val deliveryFeePaise: Long,
  @SerialName("gst_bps") val gstBps: Int,
  @SerialName("tax_paise") val taxPaise: Long,
  @SerialName("cgst_paise") val cgstPaise: Long,
  @SerialName("sgst_paise") val sgstPaise: Long,
  @SerialName("total_paise") val totalPaise: Long,
)

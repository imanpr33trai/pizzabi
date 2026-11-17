package com.pizzora.backend

import com.pizzora.backend.core.security.PasswordHasher
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5FlywayChecksumOracleTest {
  private val prettyJson = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  @Serializable
  data class FlywayChecksumBundle(
    val source: String,
    val flyway_version: String,
    val canonical_migrations: List<FlywayMigrationVector>,
    val synthetic_fixtures: List<SyntheticChecksumVector>,
  )

  @Serializable
  data class FlywayMigrationVector(
    val version: String,
    val script: String,
    val rust_mirror: String,
    val checksum: Int,
    val byte_length: Int,
  )

  @Serializable
  data class SyntheticChecksumVector(
    val name: String,
    val version: String,
    val raw_bytes_base64: String,
    val checksum: Int,
  )

  @Serializable
  data class Argon2idFixtureBundle(
    val source: String,
    val cases: List<Argon2idFixtureCase>,
  )

  @Serializable
  data class Argon2idFixtureCase(
    val name: String,
    val password: String,
    val phc_hash: String,
    val verified_by_kotlin: Boolean,
  )

  private fun findRepoRoot(): File {
    var dir = File(System.getProperty("user.dir")).canonicalFile
    while (dir.parentFile != null) {
      if (File(dir, "tools").isDirectory && File(dir, "settings.gradle.kts").isFile) {
        return dir
      }
      dir = dir.parentFile
    }
    error("Could not locate repository root from ${System.getProperty("user.dir")}")
  }

  @Test
  fun `verify byte-for-byte SQL parity and export Flyway 11_3_4 checksum oracle and Argon2id fixtures`() {
    val root = findRepoRoot()
    val flywayDir = File(root, "backend/src/main/resources/db/migration")
    val rustMigrationsDir = File(root, "rust/crates/pizzora-db/migrations")

    val pairs = listOf(
      Triple("1", "V1__core_schema.sql", "0001_core_schema.sql"),
      Triple("2", "V2__seed_rbac_and_settings.sql", "0002_seed_rbac_and_settings.sql"),
      Triple("3", "V3__hot_path_indexes.sql", "0003_hot_path_indexes.sql"),
    )

    val canonicalByteLengths = mutableMapOf<String, Int>()
    for ((version, flywayName, rustName) in pairs) {
      val fBytes = File(flywayDir, flywayName).readBytes()
      val rBytes = File(rustMigrationsDir, rustName).readBytes()
      assertArrayEquals(
        "Mirrored Rust migration $rustName must match $flywayName byte-for-byte",
        fBytes,
        rBytes,
      )
      canonicalByteLengths[version] = fBytes.size
    }

    val canonicalVectors = mutableListOf<FlywayMigrationVector>()
    val syntheticVectors = mutableListOf<SyntheticChecksumVector>()

    EmbeddedPostgres.builder().start().use { pg ->
      Flyway.configure()
        .dataSource(pg.postgresDatabase)
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        .load()
        .migrate()

      pg.postgresDatabase.connection.use { conn ->
        conn.createStatement().use { st ->
          st.executeQuery(
            "SELECT version, script, checksum FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank ASC",
          ).use { rs ->
            var idx = 0
            while (rs.next()) {
              val version = rs.getString("version")
              val script = rs.getString("script")
              val checksum = rs.getInt("checksum")
              val expected = pairs[idx++]
              assertEquals(expected.first, version)
              assertEquals(expected.second, script)
              canonicalVectors += FlywayMigrationVector(
                version = version,
                script = script,
                rust_mirror = expected.third,
                checksum = checksum,
                byte_length = canonicalByteLengths.getValue(version),
              )
            }
          }
        }
      }

      val tempDir = Files.createTempDirectory("flyway_oracle_synth").toFile()
      try {
        val fixtures: List<Triple<String, String, ByteArray>> = listOf(
          Triple(
            "lf_only",
            "1",
            "CREATE TABLE oracle_lf (id INT PRIMARY KEY, note TEXT);\nINSERT INTO oracle_lf VALUES (1, 'hello');\n"
              .toByteArray(StandardCharsets.UTF_8),
          ),
          Triple(
            "crlf_line_endings",
            "2",
            "CREATE TABLE oracle_crlf (id INT PRIMARY KEY, note TEXT);\r\nINSERT INTO oracle_crlf VALUES (1, 'hello crlf');\r\n"
              .toByteArray(StandardCharsets.UTF_8),
          ),
          Triple(
            "utf8_multibyte",
            "3",
            "-- PIZZORA ₹299.00 पिज़्ज़ा\nCREATE TABLE oracle_utf8 (id INT PRIMARY KEY, label TEXT DEFAULT '₹299.00');\n"
              .toByteArray(StandardCharsets.UTF_8),
          ),
          Triple(
            "utf8_leading_bom",
            "4",
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
              "-- BOM test ₹\nCREATE TABLE oracle_bom (id INT PRIMARY KEY);\n".toByteArray(StandardCharsets.UTF_8),
          ),
          Triple(
            "no_trailing_newline",
            "5",
            "CREATE TABLE oracle_no_nl (id INT PRIMARY KEY);".toByteArray(StandardCharsets.UTF_8),
          ),
        )

        fixtures.forEachIndexed { i, (name, version, bytes) ->
          val fileName = "V${version}__${name}.sql"
          File(tempDir, fileName).writeBytes(bytes)
        }

        Flyway.configure()
          .dataSource(pg.postgresDatabase)
          .schemas("synthetic_oracle")
          .locations("filesystem:${tempDir.absolutePath}")
          .validateMigrationNaming(true)
          .load()
          .migrate()

        pg.postgresDatabase.connection.use { conn ->
          conn.createStatement().use { st ->
            st.executeQuery(
              "SELECT version, checksum FROM synthetic_oracle.flyway_schema_history WHERE success = TRUE AND version IS NOT NULL ORDER BY installed_rank ASC",
            ).use { rs ->
              var i = 0
              while (rs.next()) {
                val version = rs.getString("version")
                val checksum = rs.getInt("checksum")
                val (name, expectedVer, rawBytes) = fixtures[i++]
                assertEquals(expectedVer, version)
                syntheticVectors += SyntheticChecksumVector(
                  name = name,
                  version = version,
                  raw_bytes_base64 = Base64.getEncoder().encodeToString(rawBytes),
                  checksum = checksum,
                )
              }
            }
          }
        }
      } finally {
        tempDir.deleteRecursively()
      }
    }

    assertEquals(3, canonicalVectors.size)
    assertEquals(5, syntheticVectors.size)

    val outFile = File(root, "tools/golden-vectors/flyway_11_3_4_checksums.json")
    outFile.parentFile.mkdirs()
    outFile.writeText(
      prettyJson.encodeToString(
        FlywayChecksumBundle(
          source = "Generated by Kotlin :backend Phase5FlywayChecksumOracleTest",
          flyway_version = "11.3.4",
          canonical_migrations = canonicalVectors,
          synthetic_fixtures = syntheticVectors,
        ),
      ) + "\n",
    )
    assertTrue(outFile.exists())

    val prodHasher = PasswordHasher()
    val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    val argonCases = listOf(
      "ktor_prod_default" to Pair(prodHasher, "PizzaPass123!"),
      "ktor_fast_params_utf8" to Pair(fastHasher, "Pizzora₹Pass!99"),
    ).map { (name, pair) ->
      val (hasher, pw) = pair
      val phc = hasher.hash(pw)
      assertTrue(hasher.verify(pw, phc))
      Argon2idFixtureCase(
        name = name,
        password = pw,
        phc_hash = phc,
        verified_by_kotlin = true,
      )
    }
    val argonOutFile = File(root, "tools/golden-vectors/ktor_argon2id_fixtures.json")
    argonOutFile.writeText(
      prettyJson.encodeToString(
        Argon2idFixtureBundle(
          source = "Generated by Kotlin :backend Phase5FlywayChecksumOracleTest (BouncyCastle Argon2id)",
          cases = argonCases,
        ),
      ) + "\n",
    )
    assertTrue(argonOutFile.exists())
  }
}

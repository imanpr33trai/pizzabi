package com.pizzora.backend.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

object CryptoTokens {
  private val secureRandom = SecureRandom()

  fun generateOpaqueToken(numBytes: Int = 32): String {
    val bytes = ByteArray(numBytes)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }
}

class PasswordHasher(
  private val memoryKb: Int = 65_536,
  private val iterations: Int = 3,
  private val parallelism: Int = 1,
  private val hashLengthBytes: Int = 32,
) {
  private val secureRandom = SecureRandom()

  fun hash(password: String): String {
    require(password.length >= 8) { "Password must be at least 8 characters." }
    val salt = ByteArray(16)
    secureRandom.nextBytes(salt)
    val derived = derive(password, salt, memoryKb, iterations, parallelism, hashLengthBytes)
    val b64Salt = Base64.getEncoder().withoutPadding().encodeToString(salt)
    val b64Hash = Base64.getEncoder().withoutPadding().encodeToString(derived)
    return "\$argon2id\$v=19\$m=$memoryKb,t=$iterations,p=$parallelism\$$b64Salt\$$b64Hash"
  }

  fun verify(password: String, encodedHash: String): Boolean {
    val parts = encodedHash.split('$')
    // ["", "argon2id", "v=19", "m=65536,t=3,p=1", "<salt>", "<hash>"]
    if (parts.size != 6 || parts[1] != "argon2id" || parts[2] != "v=19") return false
    val params = parts[3].split(',').associate { kv ->
      val pair = kv.split('=')
      if (pair.size != 2) return false
      pair[0] to (pair[1].toIntOrNull() ?: return false)
    }
    val m = params["m"] ?: return false
    val t = params["t"] ?: return false
    val p = params["p"] ?: return false

    return runCatching {
      val salt = Base64.getDecoder().decode(parts[4])
      val expectedHash = Base64.getDecoder().decode(parts[5])
      val actualHash = derive(password, salt, m, t, p, expectedHash.size)
      MessageDigest.isEqual(actualHash, expectedHash)
    }.getOrDefault(false)
  }

  private fun derive(
    password: String,
    salt: ByteArray,
    memoryKb: Int,
    iterations: Int,
    parallelism: Int,
    hashLen: Int,
  ): ByteArray {
    val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
      .withVersion(Argon2Parameters.ARGON2_VERSION_13)
      .withSalt(salt)
      .withMemoryAsKB(memoryKb)
      .withIterations(iterations)
      .withParallelism(parallelism)
    val generator = Argon2BytesGenerator()
    generator.init(builder.build())
    val out = ByteArray(hashLen)
    generator.generateBytes(password.toCharArray(), out)
    return out
  }
}

package com.pizzora.backend.core.config

/**
 * Phase 8 opt-in flags. All flags default to enabled so existing Phase 8 tests and
 * local runs see the complete modifier catalog. Setting an env var to `false`
 * hides the corresponding catalog data and rejects writes that reference it.
 */
data class Phase8FeatureFlags(
  val cheese: Boolean = true,
  val sauce: Boolean = true,
  val standardToppings: Boolean = true,
  val halfAndHalf: Boolean = true,
  val reorder: Boolean = true,
) {
  companion object {
    fun fromEnv(env: Map<String, String> = System.getenv()): Phase8FeatureFlags = Phase8FeatureFlags(
      cheese = flagEnabled(env, "PIZZORA_FEATURE_CHEESE"),
      sauce = flagEnabled(env, "PIZZORA_FEATURE_SAUCE"),
      standardToppings = flagEnabled(env, "PIZZORA_FEATURE_STANDARD_TOPPINGS"),
      halfAndHalf = flagEnabled(env, "PIZZORA_FEATURE_HALF_AND_HALF"),
      reorder = flagEnabled(env, "PIZZORA_FEATURE_REORDER"),
    )

    private fun flagEnabled(env: Map<String, String>, name: String): Boolean =
      env[name]?.trim()?.toBooleanStrictOrNull() ?: true
  }
}

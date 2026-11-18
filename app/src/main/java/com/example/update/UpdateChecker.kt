package com.example.update

data class ValidatedMetadataResult(
  val metadata: UpdateMetadata?,
  val failureReason: UpdateFailureReason? = null,
  val errorMessage: String? = null,
)

class UpdateMetadataValidator(
  private val expectedApplicationId: String = DEFAULT_APPLICATION_ID,
  private val urlPolicy: UpdateUrlPolicy = UpdateUrlPolicy(),
) {

  fun validate(raw: UpdateMetadata): ValidatedMetadataResult {
    if (raw.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
      return ValidatedMetadataResult(
        metadata = null,
        failureReason = UpdateFailureReason.INVALID_METADATA,
        errorMessage = "Unsupported update metadata schema version (${raw.schemaVersion}).",
      )
    }

    if (raw.applicationId != expectedApplicationId) {
      return ValidatedMetadataResult(
        metadata = null,
        failureReason = UpdateFailureReason.PACKAGE_MISMATCH,
        errorMessage = "Update metadata applicationId '${raw.applicationId}' does not match '$expectedApplicationId'.",
      )
    }

    if (raw.versionCode <= 0L) {
      return ValidatedMetadataResult(
        metadata = null,
        failureReason = UpdateFailureReason.INVALID_METADATA,
        errorMessage = "Update metadata versionCode must be positive.",
      )
    }

    val cleanVersionName = raw.versionName.trim()
    if (cleanVersionName.isEmpty()) {
      return ValidatedMetadataResult(
        metadata = null,
        failureReason = UpdateFailureReason.INVALID_METADATA,
        errorMessage = "Update metadata versionName must not be blank.",
      )
    }

    if (raw.minimumSupportedVersionCode < 1L || raw.minimumSupportedVersionCode > raw.versionCode) {
      return ValidatedMetadataResult(
        metadata = null,
        failureReason = UpdateFailureReason.INVALID_METADATA,
        errorMessage = "Invalid minimumSupportedVersionCode (${raw.minimumSupportedVersionCode}) for versionCode (${raw.versionCode}).",
      )
    }

    val cleanAssetName = runCatching {
      urlPolicy.validateSafeAssetFileName(raw.apkAssetName, ".apk")
    }.getOrElse { err ->
      return ValidatedMetadataResult(
        metadata = null,
        failureReason = UpdateFailureReason.INVALID_METADATA,
        errorMessage = err.message ?: "Invalid APK asset filename in update metadata.",
      )
    }

    val cleanSha256 = raw.apkSha256.trim().lowercase()
    if (!SHA256_REGEX.matches(cleanSha256)) {
      return ValidatedMetadataResult(
        metadata = null,
        failureReason = UpdateFailureReason.INVALID_METADATA,
        errorMessage = "Update metadata contains malformed APK SHA-256 digest.",
      )
    }

    if (raw.minSdk < 1) {
      return ValidatedMetadataResult(
        metadata = null,
        failureReason = UpdateFailureReason.INVALID_METADATA,
        errorMessage = "Invalid minSdk (${raw.minSdk}) in update metadata.",
      )
    }

    val cleanReleaseUrl = runCatching {
      urlPolicy.requireAllowedReleasePageUrl(raw.releaseUrl)
    }.getOrElse { err ->
      return ValidatedMetadataResult(
        metadata = null,
        failureReason = UpdateFailureReason.UNTRUSTED_URL,
        errorMessage = err.message ?: "Untrusted release URL in update metadata.",
      )
    }

    val sanitizedNotes = sanitizeReleaseNotes(raw.releaseNotes)

    return ValidatedMetadataResult(
      metadata = raw.copy(
        versionName = cleanVersionName,
        apkAssetName = cleanAssetName,
        apkSha256 = cleanSha256,
        releaseUrl = cleanReleaseUrl,
        releaseNotes = sanitizedNotes,
      ),
    )
  }

  companion object {
    const val SUPPORTED_SCHEMA_VERSION = 1
    const val DEFAULT_APPLICATION_ID = "com.aistudio.pizzora.xkqzmw"
    const val MAX_RELEASE_NOTES_LENGTH = 16_000
    private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

    fun sanitizeReleaseNotes(rawNotes: String): String {
      val cleaned = buildString {
        for (ch in rawNotes) {
          when {
            ch == '\r' -> Unit
            ch == '\n' || ch == '\t' -> append(ch)
            ch.code in 0x00..0x1F || ch.code == 0x7F -> Unit
            else -> append(ch)
          }
        }
      }.trim().take(MAX_RELEASE_NOTES_LENGTH)

      return cleaned.ifBlank { "Bug fixes and stability improvements." }
    }
  }
}

sealed interface UpdateEvaluation {
  data object UpToDate : UpdateEvaluation
  data class DowngradeRejected(val remoteVersionCode: Long, val installedVersionCode: Long) : UpdateEvaluation
  data class IncompatibleDevice(val reason: String) : UpdateEvaluation
  data class UpdateReady(val update: AvailableUpdate) : UpdateEvaluation
}

class UpdatePolicy {
  fun evaluateMandatory(
    installedVersionCode: Long,
    metadata: UpdateMetadata,
  ): Pair<Boolean, MandatoryUpdateReason?> = when {
    installedVersionCode < metadata.minimumSupportedVersionCode ->
      true to MandatoryUpdateReason.BELOW_MINIMUM_SUPPORTED_VERSION
    metadata.mandatory ->
      true to MandatoryUpdateReason.EXPLICIT_MANDATORY
    else ->
      false to null
  }
}

class UpdateChecker(
  private val policy: UpdatePolicy = UpdatePolicy(),
) {
  fun evaluate(
    installed: InstalledAppInfo,
    metadata: UpdateMetadata,
    apkDownloadUrl: String,
    apkSizeBytes: Long?,
  ): UpdateEvaluation {
    if (metadata.versionCode < installed.versionCode) {
      return UpdateEvaluation.DowngradeRejected(
        remoteVersionCode = metadata.versionCode,
        installedVersionCode = installed.versionCode,
      )
    }

    if (metadata.versionCode == installed.versionCode) {
      return UpdateEvaluation.UpToDate
    }

    if (installed.sdkInt < metadata.minSdk) {
      return UpdateEvaluation.IncompatibleDevice(
        "This update requires Android API ${metadata.minSdk} or newer (device is API ${installed.sdkInt}).",
      )
    }

    if (metadata.supportedAbis.isNotEmpty() && installed.supportedAbis.isNotEmpty()) {
      val hasMatchingAbi = installed.supportedAbis.any { it in metadata.supportedAbis }
      if (!hasMatchingAbi) {
        return UpdateEvaluation.IncompatibleDevice(
          "This release does not support this device's CPU architecture (${installed.supportedAbis.joinToString()}).",
        )
      }
    }

    val (isMandatory, mandatoryReason) = policy.evaluateMandatory(
      installedVersionCode = installed.versionCode,
      metadata = metadata,
    )

    return UpdateEvaluation.UpdateReady(
      AvailableUpdate(
        metadata = metadata,
        apkDownloadUrl = apkDownloadUrl,
        apkSizeBytes = apkSizeBytes ?: metadata.apkSizeBytes,
        releasePageUrl = metadata.releaseUrl,
        isMandatory = isMandatory,
        mandatoryReason = mandatoryReason,
      ),
    )
  }
}

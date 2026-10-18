package dev.kigya.translatepdf

import org.json.JSONObject

internal data class ModelDefinition(
  val id: String,
  val label: String,
  val repo: String,
  val fileName: String,
  val revision: String,
  val bytes: Long,
  val sha256: String,
  val qualityBenchmarked: Boolean,
  val translationEnabled: Boolean,
) {
  val url: String
    get() = "https://huggingface.co/$repo/resolve/$revision/$fileName"

  fun toJson(status: String, downloadedBytes: Long, error: String? = null): JSONObject =
    JSONObject()
      .put("id", id)
      .put("label", label)
      .put("revision", revision)
      .put("bytes", bytes)
      .put("sha256", sha256)
      .put("status", status)
      .put("downloadedBytes", downloadedBytes)
      .put("verified", qualityBenchmarked)
      .put("checksumPinned", true)
      .put("translationEnabled", translationEnabled)
      .also { if (error != null) it.put("error", error) }
}

internal object ModelCatalog {
  const val RECOMMENDED_ID = "hy-mt2-q4-k-m"
  val models = listOf(
    ModelDefinition(
      id = RECOMMENDED_ID,
      label = "Hy-MT2 1.8B Q4_K_M",
      repo = "tencent/Hy-MT2-1.8B-GGUF",
      fileName = "Hy-MT2-1.8B-Q4_K_M.gguf",
      revision = "a0c709d9fac510f2c807aa3af52872340dc37a4a",
      bytes = 1_133_080_448L,
      sha256 = "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699",
      qualityBenchmarked = false,
      translationEnabled = true,
    ),
    ModelDefinition(
      id = "hy-mt2-iq3-xs",
      label = "Hy-MT2 1.8B IQ3_XS (unverified compact candidate)",
      repo = "mradermacher/Hy-MT2-1.8B-i1-GGUF",
      fileName = "Hy-MT2-1.8B.i1-IQ3_XS.gguf",
      revision = "9f5c7d98d8b625800775e6197e55c7ed38f2f33a",
      bytes = 840_135_680L,
      sha256 = "f37758217de25a512e6ff7a728a3cd4fa8f4843041468889bc8a9811a2a894b5",
      qualityBenchmarked = false,
      translationEnabled = false,
    ),
    ModelDefinition(
      id = "hy-mt2-iq2-m",
      label = "Hy-MT2 1.8B IQ2_M (unverified compact candidate)",
      repo = "mradermacher/Hy-MT2-1.8B-i1-GGUF",
      fileName = "Hy-MT2-1.8B.i1-IQ2_M.gguf",
      revision = "9f5c7d98d8b625800775e6197e55c7ed38f2f33a",
      bytes = 698_483_424L,
      sha256 = "917ee7286326a2881ba6f519b3e43ed41ff6b1c0db8288ff52f3fae5930080d9",
      qualityBenchmarked = false,
      translationEnabled = false,
    ),
  )

  fun get(id: String): ModelDefinition = models.firstOrNull { it.id == id }
    ?: throw IllegalArgumentException("Unknown model selection.")
}

internal data class MemoryRecommendation(
  val availableMemoryBytes: Long,
  val systemLowMemoryThresholdBytes: Long,
  val reserveBytes: Long,
  val recommendedModelId: String,
  val requiredPipelineMemoryBytes: Long,
  val requiredDiskBytes: Long,
  val memoryEstimateEligible: Boolean,
  val diskEstimateEligible: Boolean,
  val resourceEstimateEligible: Boolean,
  val recommendationAvailable: Boolean,
  val reason: String,
)

internal object MemoryPolicy {
  private const val MIB = 1024L * 1024L
  private const val MIN_RESERVE = 512L * MIB
  private const val GIB = 1024L * MIB
  private const val PROVISIONAL_Q4_PIPELINE_PSS = 2L * GIB
  private const val DISK_HEADROOM = 512L * MIB
  private const val DEBUG_PROFILE_MINIMUM_HEADROOM = 256L * MIB
  private const val PIPELINE_ENVELOPE_VALIDATED = false

  fun recommend(
    totalMemoryBytes: Long,
    availableMemoryBytes: Long,
    systemLowMemoryThresholdBytes: Long,
    systemLowMemory: Boolean,
    availableDiskBytes: Long,
  ): MemoryRecommendation {
    val reserve = maxOf(MIN_RESERVE, totalMemoryBytes * 15 / 100, systemLowMemoryThresholdBytes)
    val q4 = ModelCatalog.get(ModelCatalog.RECOMMENDED_ID)
    val enoughMemory = !systemLowMemory && availableMemoryBytes >= reserve + PROVISIONAL_Q4_PIPELINE_PSS
    val requiredDisk = requiredDiskBytes(q4.bytes)
    val enoughDisk = availableDiskBytes >= requiredDisk
    val resourceEligible = enoughMemory && enoughDisk
    val reason = when {
      systemLowMemory -> "Android reports low memory; wait until other apps release memory before trying the model."
      !enoughMemory -> "Free memory is below the provisional 2 GiB full-process envelope plus Android's low-memory reserve."
      !enoughDisk -> "Free storage is below the model size plus a 512 MiB resumable-download reserve."
      else -> "Provisional resource estimate only: the 2 GiB Q4 full-pipeline envelope and translation quality are not yet validated on this device."
    }
    return MemoryRecommendation(
      availableMemoryBytes = availableMemoryBytes,
      systemLowMemoryThresholdBytes = systemLowMemoryThresholdBytes,
      reserveBytes = reserve,
      recommendedModelId = q4.id,
      requiredPipelineMemoryBytes = PROVISIONAL_Q4_PIPELINE_PSS,
      requiredDiskBytes = requiredDisk,
      memoryEstimateEligible = enoughMemory,
      diskEstimateEligible = enoughDisk,
      resourceEstimateEligible = resourceEligible,
      recommendationAvailable = resourceEligible && q4.qualityBenchmarked && PIPELINE_ENVELOPE_VALIDATED,
      reason = reason,
    )
  }

  fun requiredDiskBytes(modelBytes: Long): Long = modelBytes + DISK_HEADROOM

  /** Debug profiling may bypass only the unvalidated full-pipeline envelope. */
  fun permitsBoundedDebugProfile(
    availableMemoryBytes: Long,
    reserveBytes: Long,
    systemLowMemory: Boolean,
    modelBytes: Long,
  ): Boolean = !systemLowMemory && availableMemoryBytes >=
    reserveBytes + modelBytes + DEBUG_PROFILE_MINIMUM_HEADROOM
}

internal object ForegroundNotificationPolicy {
  fun shouldRequestPermission(apiLevel: Int, alreadyGranted: Boolean): Boolean = apiLevel >= 33 && !alreadyGranted
}

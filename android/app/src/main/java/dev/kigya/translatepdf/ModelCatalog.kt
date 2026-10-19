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
  val tier: String = "compact",
  val profile: String = "hy-mt2",
  val requiredMemoryBytes: Long = 2L * 1024 * 1024 * 1024,
  val licenseUrl: String = "https://www.apache.org/licenses/LICENSE-2.0",
  val supportsGlossary: Boolean = true,
  val acceptableTestCases: Int? = null,
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
      .put("tier", tier)
      .put("requiredMemoryBytes", requiredMemoryBytes)
      .put("licenseUrl", licenseUrl)
      .put("supportsGlossary", supportsGlossary)
      .also { value -> acceptableTestCases?.let { value.put("evaluation", JSONObject().put("acceptableCases", it).put("totalCases", 44)) } }
      .also { if (error != null) it.put("error", error) }
}

internal object ModelCatalog {
  const val RECOMMENDED_ID = "hy-mt2-q4-k-m"
  private val previousModels = listOf(
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
      acceptableTestCases = 17,
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

  val models = listOf(
    previousModels.first(),
    ModelDefinition(
      id = "translategemma-4b-q4", label = "TranslateGemma 4B", tier = "balanced", profile = "translategemma",
      repo = "bullerwins/translategemma-4b-it-GGUF", fileName = "translategemma-4b-it-Q4_K_M.gguf",
      revision = "7c938465a870d8624bcfa98a8e4a3510053c19a8", bytes = 2_489_909_312L,
      sha256 = "7f7357c14abd9da4eb200b38b05da502cd6e10d7e1d403fbc9f78c19f3209b72",
      qualityBenchmarked = false, translationEnabled = true, requiredMemoryBytes = 3L * 1024 * 1024 * 1024,
      licenseUrl = "https://ai.google.dev/gemma/terms", supportsGlossary = false,
      acceptableTestCases = 29,
    ),
    ModelDefinition(
      id = "hy-mt2-7b-q4", label = "Hy-MT2 7B", tier = "quality", profile = "hy-mt2",
      repo = "tencent/Hy-MT2-7B-GGUF", fileName = "Hy-MT2-7B-Q4_K_M.gguf",
      revision = "ab8472660ac61fac25f1af43fac2599d52a8a775", bytes = 4_624_648_896L,
      sha256 = "9f96256500f3fc1ab4d64336b58f52a949a95ad7516b0c229476eef782f9f77b",
      qualityBenchmarked = false, translationEnabled = true, requiredMemoryBytes = 6L * 1024 * 1024 * 1024,
      acceptableTestCases = 31,
    ),
  )

  fun get(id: String): ModelDefinition = (models + previousModels).firstOrNull { it.id == id }
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
  private const val DISK_HEADROOM = 512L * MIB
  private const val DEBUG_PROFILE_MINIMUM_HEADROOM = 256L * MIB

  data class Resources(
    val memoryEligible: Boolean, val diskEligible: Boolean, val requiredDiskBytes: Long,
    val reserveBytes: Long, val reason: String,
  ) {
    val eligible: Boolean get() = memoryEligible && diskEligible
    fun applyTo(value: JSONObject): JSONObject = value
      .put("memoryEstimateEligible", memoryEligible).put("diskEstimateEligible", diskEligible)
      .put("resourceEstimateEligible", eligible).put("resourceReason", reason)
  }

  fun resources(
    model: ModelDefinition, totalMemoryBytes: Long, availableMemoryBytes: Long,
    systemLowMemoryThresholdBytes: Long, systemLowMemory: Boolean, availableDiskBytes: Long,
    downloadedBytes: Long = 0,
  ): Resources {
    val reserve = maxOf(MIN_RESERVE, totalMemoryBytes * 15 / 100, systemLowMemoryThresholdBytes)
    val memory = !systemLowMemory && availableMemoryBytes >= reserve + model.requiredMemoryBytes
    val disk = requiredDiskBytes(model.bytes, downloadedBytes)
    val reason = when {
      !model.translationEnabled -> "unsupported"
      systemLowMemory -> "lowMemory"
      !memory -> "memory"
      availableDiskBytes < disk -> "disk"
      else -> "available"
    }
    return Resources(memory && model.translationEnabled, availableDiskBytes >= disk && model.translationEnabled, disk, reserve, reason)
  }

  fun recommend(
    totalMemoryBytes: Long, availableMemoryBytes: Long, systemLowMemoryThresholdBytes: Long,
    systemLowMemory: Boolean, availableDiskBytes: Long, downloadedBytes: Map<String, Long> = emptyMap(),
  ): MemoryRecommendation {
    val selected = ModelCatalog.models.asReversed().firstOrNull {
      resources(it, totalMemoryBytes, availableMemoryBytes, systemLowMemoryThresholdBytes, systemLowMemory, availableDiskBytes, downloadedBytes[it.id] ?: 0).eligible
    } ?: ModelCatalog.models.first()
    val resource = resources(selected, totalMemoryBytes, availableMemoryBytes, systemLowMemoryThresholdBytes, systemLowMemory, availableDiskBytes, downloadedBytes[selected.id] ?: 0)
    val reason = when(resource.reason) {
      "lowMemory" -> "Android reports low memory; wait until other apps release memory."
      "memory" -> "Free memory is below this model's estimated process memory plus the system reserve."
      "disk" -> "Free storage is below the remaining download plus the 512 MiB work reserve."
      "unsupported" -> "This model is unavailable in the current runtime."
      else -> "Fits the current resource estimate. Translation quality and whole-document memory remain experimental."
    }
    return MemoryRecommendation(
      availableMemoryBytes, systemLowMemoryThresholdBytes, resource.reserveBytes, selected.id,
      selected.requiredMemoryBytes, resource.requiredDiskBytes, resource.memoryEligible,
      resource.diskEligible, resource.eligible, resource.eligible, reason,
    )
  }

  fun requiredDiskBytes(modelBytes: Long, downloadedBytes: Long = 0): Long =
    (modelBytes - downloadedBytes.coerceIn(0, modelBytes)).coerceAtLeast(0) + DISK_HEADROOM

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

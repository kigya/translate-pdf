package dev.kigya.translatepdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPolicyTest {
  @Test
  fun q4ResourceEstimateRequiresReserveAndProvisionalPipelineEnvelope() {
    val total = 8L * 1024 * 1024 * 1024
    val reserve = maxOf(512L * 1024 * 1024, total * 15 / 100, 600L * 1024 * 1024)
    val disk = ModelCatalog.get(ModelCatalog.RECOMMENDED_ID).bytes + 512L * 1024 * 1024
    val needed = reserve + 2L * 1024 * 1024 * 1024

    val enough = MemoryPolicy.recommend(total, needed, 600L * 1024 * 1024, false, disk)
    val short = MemoryPolicy.recommend(total, needed - 1, 600L * 1024 * 1024, false, disk)

    assertEquals(ModelCatalog.RECOMMENDED_ID, enough.recommendedModelId)
    assertEquals(reserve, enough.reserveBytes)
    assertTrue(enough.resourceEstimateEligible)
    assertTrue(enough.memoryEstimateEligible)
    assertTrue(enough.diskEstimateEligible)
    assertFalse(enough.recommendationAvailable)
    assertTrue(enough.reason.contains("not yet validated"))
    assertFalse(short.resourceEstimateEligible)
    assertFalse(short.memoryEstimateEligible)
  }

  @Test
  fun systemLowMemorySignalAndDownloadReserveBlockResourceEstimate() {
    val total = 4L * 1024 * 1024 * 1024
    val q4 = ModelCatalog.get(ModelCatalog.RECOMMENDED_ID)
    val required = 4L * 1024 * 1024 * 1024
    val memoryLow = MemoryPolicy.recommend(total, required, 1L * 1024 * 1024 * 1024, true, q4.bytes + 512L * 1024 * 1024)
    val diskLow = MemoryPolicy.recommend(total, required, 1L * 1024 * 1024 * 1024, false, q4.bytes + 512L * 1024 * 1024 - 1)

    assertEquals(1L * 1024 * 1024 * 1024, memoryLow.reserveBytes)
    assertFalse(memoryLow.resourceEstimateEligible)
    assertTrue(memoryLow.reason.contains("Android reports low memory"))
    assertFalse(diskLow.resourceEstimateEligible)
    assertTrue(diskLow.memoryEstimateEligible)
    assertFalse(diskLow.diskEstimateEligible)
    assertTrue(diskLow.reason.contains("Free storage"))
  }

  @Test
  fun boundedDebugProfileStillRequiresSystemReserveAndModelWorkingSet() {
    val mib = 1024L * 1024L
    val reserve = 600L * mib
    val model = 1_100L * mib
    assertTrue(MemoryPolicy.permitsBoundedDebugProfile(reserve + model + 256L * mib, reserve, false, model))
    assertFalse(MemoryPolicy.permitsBoundedDebugProfile(reserve + model + 256L * mib - 1, reserve, false, model))
    assertFalse(MemoryPolicy.permitsBoundedDebugProfile(reserve + model + 1L * mib, reserve, true, model))
  }

  @Test
  fun notificationPermissionIsRequestedOnlyOnAndroid13WhenMissing() {
    assertFalse(ForegroundNotificationPolicy.shouldRequestPermission(32, false))
    assertFalse(ForegroundNotificationPolicy.shouldRequestPermission(33, true))
    assertTrue(ForegroundNotificationPolicy.shouldRequestPermission(33, false))
  }

  @Test
  fun catalogPinsQ4AndKeepsCompactCandidatesDisabledUntilBenchmarked() {
    val q4 = ModelCatalog.get(ModelCatalog.RECOMMENDED_ID)
    val compact = ModelCatalog.models.filter { it.id != ModelCatalog.RECOMMENDED_ID }

    assertEquals(1_133_080_448L, q4.bytes)
    assertEquals("dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699", q4.sha256)
    assertEquals("a0c709d9fac510f2c807aa3af52872340dc37a4a", q4.revision)
    assertTrue(q4.url.endsWith("/Hy-MT2-1.8B-Q4_K_M.gguf"))
    assertFalse(q4.qualityBenchmarked)
    assertTrue(q4.translationEnabled)
    assertEquals(2, compact.size)
    assertTrue(compact.all { !it.qualityBenchmarked && !it.translationEnabled })
    assertTrue(compact.all { it.sha256.matches(Regex("^[0-9a-f]{64}$")) })
    assertEquals("Hy-MT2-1.8B.i1-IQ3_XS.gguf", ModelCatalog.get("hy-mt2-iq3-xs").fileName)
    assertEquals("Hy-MT2-1.8B.i1-IQ2_M.gguf", ModelCatalog.get("hy-mt2-iq2-m").fileName)
  }
}

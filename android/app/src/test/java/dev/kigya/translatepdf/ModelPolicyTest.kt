package dev.kigya.translatepdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPolicyTest {
  @Test
  fun everyModelUsesItsOwnEnvelopeAndTheCurrentSystemReserve() {
    val total = 8L * 1024 * 1024 * 1024
    val threshold = 600L * 1024 * 1024
    val reserve = maxOf(512L * 1024 * 1024, total * 15 / 100, threshold)
    for (model in ModelCatalog.models) {
      val disk = model.bytes + 512L * 1024 * 1024
      val enough = MemoryPolicy.resources(model, total, reserve + model.requiredMemoryBytes, threshold, false, disk)
      val short = MemoryPolicy.resources(model, total, reserve + model.requiredMemoryBytes - 1, threshold, false, disk)
      assertEquals(reserve, enough.reserveBytes)
      assertTrue(enough.eligible)
      assertEquals("available", enough.reason)
      assertFalse(short.eligible)
      assertFalse(short.memoryEligible)
      assertEquals("memoryEstimate", short.reason)
    }
  }

  @Test
  fun resumeAndInstalledModelsRequireOnlyTheRemainingTransferAndWorkReserve() {
    val model = ModelCatalog.models[1]
    val headroom = 512L * 1024 * 1024
    val downloaded = model.bytes / 2
    assertEquals(model.bytes - downloaded + headroom, MemoryPolicy.requiredDiskBytes(model.bytes, downloaded))
    assertEquals(headroom, MemoryPolicy.requiredDiskBytes(model.bytes, model.bytes))
    assertEquals(headroom, MemoryPolicy.requiredDiskBytes(model.bytes, Long.MAX_VALUE))
    val low = MemoryPolicy.resources(model, 4L shl 30, 4L shl 30, 1L shl 30, true, model.bytes + headroom)
    assertFalse(low.eligible)
    assertFalse(low.memoryEligible)
    assertEquals("lowMemory", low.reason)
    val diskLow = MemoryPolicy.resources(model, 8L shl 30, 8L shl 30, 0, false, headroom - 1, model.bytes)
    assertFalse(diskLow.diskEligible)
    assertEquals("disk", diskLow.reason)
  }

  @Test
  fun recommendationIsAResourceSuggestionAndDoesNotLockOtherEligibleChoices() {
    val top = ModelCatalog.models.last()
    val result = MemoryPolicy.recommend(12L shl 30, 10L shl 30, 0, false, 20L shl 30)
    assertEquals(top.id, result.recommendedModelId)
    assertTrue(result.recommendationAvailable)
    assertTrue(result.reason.contains("experimental"))
    assertTrue(ModelCatalog.models.all { MemoryPolicy.resources(it, 12L shl 30, 10L shl 30, 0, false, 20L shl 30).eligible })
  }

  @Test
  fun notificationPermissionIsRequestedOnlyOnAndroid13WhenMissing() {
    assertFalse(ForegroundNotificationPolicy.shouldRequestPermission(32, false))
    assertFalse(ForegroundNotificationPolicy.shouldRequestPermission(33, true))
    assertTrue(ForegroundNotificationPolicy.shouldRequestPermission(33, false))
  }

  @Test
  fun catalogHasThreeDistinctPinnedChoicesAndLegacyModelsStayOutOfThePicker() {
    val models = ModelCatalog.models
    assertEquals(3, models.size)
    assertEquals(setOf("compact", "balanced", "quality"), models.map { it.tier }.toSet())
    assertEquals(3, models.map { it.id }.toSet().size)
    assertTrue(models.all { it.translationEnabled && !it.qualityBenchmarked })
    assertTrue(models.all { it.sha256.matches(Regex("^[0-9a-f]{64}$")) && it.revision.matches(Regex("^[0-9a-f]{40}$")) })
    assertTrue(models.all { it.url.contains("/resolve/${it.revision}/") && it.licenseUrl.startsWith("https://") })
    assertFalse(models.any { it.id == "hy-mt2-iq3-xs" || it.id == "hy-mt2-iq2-m" })
    assertFalse(ModelCatalog.get("hy-mt2-iq3-xs").translationEnabled)
    assertFalse(ModelCatalog.get("hy-mt2-iq2-m").translationEnabled)
  }
}

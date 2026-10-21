package dev.kigya.translatepdf

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
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

  @Test
  fun catalogRejectsUnknownSelectionsWithStableMessage() {
    val error = assertThrows(IllegalArgumentException::class.java) { ModelCatalog.get("missing") }
    assertEquals("Unknown model selection.", error.message)
  }

  @Test
  fun recommendationReportsUnsupportedModelEvenWhenResourcesAreAvailable() {
    val unsupported = ModelCatalog.get("hy-mt2-iq3-xs")
    val resources = MemoryPolicy.resources(unsupported, 32L shl 30, 32L shl 30, 0, false, 32L shl 30)
    assertFalse(resources.eligible)
    assertEquals("unsupported", resources.reason)
    val recommendation = MemoryPolicy.recommend(0, 0, 0, true, 0)
    assertTrue(recommendation.reason.startsWith("Android reports low memory;"))
    assertFalse(recommendation.recommendationAvailable)
  }

  @Test
  fun modelMetadataAndJsonKeepTheCatalogContractIncludingOptionalFields() {
    for (model in ModelCatalog.models + ModelCatalog.get("hy-mt2-iq3-xs")) {
      assertTrue(model.label.isNotBlank())
      assertTrue(model.repo.isNotBlank())
      assertTrue(model.fileName.endsWith(".gguf"))
      assertTrue(model.supportsGlossary || model.id == "translategemma-4b-q4")

      val json = model.toJson("ready", model.bytes, "verification note")
      assertEquals(model.id, json.getString("id"))
      assertEquals(model.label, json.getString("label"))
      assertEquals(model.revision, json.getString("revision"))
      assertEquals(model.bytes, json.getLong("bytes"))
      assertEquals(model.sha256, json.getString("sha256"))
      assertEquals("ready", json.getString("status"))
      assertEquals(model.bytes, json.getLong("downloadedBytes"))
      assertEquals(model.qualityBenchmarked, json.getBoolean("verified"))
      assertTrue(json.getBoolean("checksumPinned"))
      assertEquals(model.translationEnabled, json.getBoolean("translationEnabled"))
      assertEquals(model.tier, json.getString("tier"))
      assertEquals(model.requiredMemoryBytes, json.getLong("requiredMemoryBytes"))
      assertEquals(model.licenseUrl, json.getString("licenseUrl"))
      assertEquals(model.supportsGlossary, json.getBoolean("supportsGlossary"))
      assertEquals("verification note", json.getString("error"))
      if (model.acceptableTestCases != null) {
        assertEquals(model.acceptableTestCases, json.getJSONObject("evaluation").getInt("acceptableCases"))
        assertEquals(44, json.getJSONObject("evaluation").getInt("totalCases"))
      } else {
        assertTrue(json.isNull("evaluation"))
      }
      assertTrue(model.url.contains(model.repo))
      assertTrue(model.url.endsWith(model.fileName))
    }

    val noError = ModelCatalog.models.first().toJson("queued", 0)
    assertTrue(noError.isNull("error"))
  }

  @Test
  fun recommendationReportsEveryResourceFieldAndDefaultDiskResumeArgument() {
    val recommendation = MemoryPolicy.recommend(12L shl 30, 10L shl 30, 256L shl 20, false, 20L shl 30)
    assertEquals(10L shl 30, recommendation.availableMemoryBytes)
    assertEquals(256L shl 20, recommendation.systemLowMemoryThresholdBytes)
    assertTrue(recommendation.reserveBytes > 0)
    assertEquals(ModelCatalog.models.last().id, recommendation.recommendedModelId)
    assertTrue(recommendation.requiredPipelineMemoryBytes > 0)
    assertTrue(recommendation.requiredDiskBytes > 0)
    assertTrue(recommendation.memoryEstimateEligible)
    assertTrue(recommendation.diskEstimateEligible)
    assertTrue(recommendation.resourceEstimateEligible)
    assertTrue(recommendation.recommendationAvailable)
    assertTrue(recommendation.reason.isNotBlank())
    assertEquals(MemoryPolicy.requiredDiskBytes(ModelCatalog.models.first().bytes, 0),
      MemoryPolicy.requiredDiskBytes(ModelCatalog.models.first().bytes))

    val resources = MemoryPolicy.resources(ModelCatalog.models.first(), 8L shl 30, 8L shl 30, 0, false, 4L shl 30)
    val json = JSONObject()
    assertEquals(json, resources.applyTo(json))
    assertTrue(json.getBoolean("memoryEstimateEligible"))
    assertTrue(json.getBoolean("diskEstimateEligible"))
    assertTrue(json.getBoolean("resourceEstimateEligible"))
    assertEquals(resources.reason, json.getString("resourceReason"))
  }

  @Test
  fun recommendationUsesPartialModelBytesAndMapsEveryResourceReason() {
    val balanced = ModelCatalog.models[1]
    val partialBytes = balanced.bytes / 2
    val requiredDisk = MemoryPolicy.requiredDiskBytes(balanced.bytes, partialBytes)
    val recommendation = MemoryPolicy.recommend(
      12L shl 30, 10L shl 30, 0, false, requiredDisk, mapOf(balanced.id to partialBytes),
    )
    assertEquals(balanced.id, recommendation.recommendedModelId)
    assertEquals(requiredDisk, recommendation.requiredDiskBytes)

    assertEquals("Android reports low memory; wait until other apps release memory.", MemoryPolicy.recommendationReason("lowMemory"))
    assertEquals("The estimated model working set is above current free memory; the estimate is advisory and runtime may fail if memory is insufficient.", MemoryPolicy.recommendationReason("memoryEstimate"))
    assertEquals("Free storage is below the remaining download plus the 512 MiB work reserve.", MemoryPolicy.recommendationReason("disk"))
    assertEquals("This model is unavailable in the current runtime.", MemoryPolicy.recommendationReason("unsupported"))
    assertEquals("Fits the current resource estimate. Translation quality and whole-document memory remain experimental.", MemoryPolicy.recommendationReason("available"))
  }

  @Test
  fun recommendationExplainsMemoryAndDiskLimitsWhenNoModelFits() {
    val model = ModelCatalog.models.first()
    val memoryLimited = MemoryPolicy.recommend(8L shl 30, 1L shl 20, 0, false, 8L shl 30)
    assertFalse(memoryLimited.memoryEstimateEligible)
    assertTrue(memoryLimited.reason.contains("estimated model working set"))

    val diskLimited = MemoryPolicy.recommend(8L shl 30, 8L shl 30, 0, false, 1)
    assertTrue(diskLimited.memoryEstimateEligible)
    assertFalse(diskLimited.diskEstimateEligible)
    assertTrue(diskLimited.reason.contains("Free storage"))
    assertEquals(model.id, diskLimited.recommendedModelId)
  }
}

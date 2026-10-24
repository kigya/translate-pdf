package dev.kigya.translatepdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightRepackingPolicyTest {
  @Test
  fun fourGiBDeviceAllowsCompactButDisablesBalancedAndMaximumWeightRepacking() {
    val totalMemory = 4L * 1024 * 1024 * 1024
    val compact = ModelCatalog.get(ModelCatalog.RECOMMENDED_ID)
    val balanced = ModelCatalog.models.first { it.tier == "balanced" }
    val maximum = ModelCatalog.models.first { it.tier == "quality" }

    assertTrue(WeightRepackingPolicy.shouldAllow(compact.bytes, totalMemory))
    assertFalse(WeightRepackingPolicy.shouldAllow(balanced.bytes, totalMemory))
    assertFalse(WeightRepackingPolicy.shouldAllow(maximum.bytes, totalMemory))
  }

  @Test
  fun unknownMemoryAndInvalidWeightSizeConservativelyDisableRepacking() {
    assertFalse(WeightRepackingPolicy.shouldAllow(100, 0))
    assertFalse(WeightRepackingPolicy.shouldAllow(0, 4_000))
    assertFalse(WeightRepackingPolicy.shouldAllow(-1, 4_000))
  }

  @Test
  fun exactHalfPhysicalMemoryBoundaryIsAllowed() {
    assertTrue(WeightRepackingPolicy.shouldAllow(2_000, 4_000))
    assertFalse(WeightRepackingPolicy.shouldAllow(2_001, 4_000))
  }
}

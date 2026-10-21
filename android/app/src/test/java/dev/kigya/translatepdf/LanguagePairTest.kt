package dev.kigya.translatepdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagePairTest {
  @Test
  fun allSupportedCodesHaveAutonymsAndDistinctDirectionalPairs() {
    assertEquals(listOf("ru", "zh-Hans", "fr", "en", "de", "es"), LanguagePair.codes)
    for (source in LanguagePair.codes) {
      assertTrue(LanguagePair.isValidCode(source))
      assertTrue(LanguagePair.label(source).isNotBlank())
      val target = LanguagePair.codes.first { it != source }
      assertEquals("${LanguagePair.label(source)} to ${LanguagePair.label(target)}",
        LanguagePair.displayLabel(source, target))
      for (target in LanguagePair.codes) {
        if (source == target) {
          assertFalse(runCatching { LanguagePair.requireValid(source, target) }.isSuccess)
        } else {
          LanguagePair.requireValid(source, target)
        }
      }
    }
    assertFalse(LanguagePair.isValidCode("it"))
    assertEquals("Russian to Simplified Chinese", LanguagePair.displayLabel("ru", "zh-Hans"))
  }

  @Test
  fun unsupportedLabelsAndInvalidPairsFailWithStableMessages() {
    val label = runCatching { LanguagePair.label("it") }.exceptionOrNull()
    assertEquals("Unsupported language.", label?.message)
    val pair = runCatching { LanguagePair.displayLabel("ru", "ru") }.exceptionOrNull()
    assertEquals("Choose two different supported languages.", pair?.message)
    val unsupported = runCatching { LanguagePair.requireValid("it", "ru") }.exceptionOrNull()
    assertEquals("Choose two different supported languages.", unsupported?.message)
    val unsupportedTarget = runCatching { LanguagePair.requireValid("ru", "it") }.exceptionOrNull()
    assertEquals("Choose two different supported languages.", unsupportedTarget?.message)
  }

}

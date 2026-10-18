package dev.kigya.translatepdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrOutcomeTest {
  @Test
  fun emptyPageIsMarkedForManualReviewInsteadOfComplete() {
    assertEquals(OcrOutcome.NO_TEXT_WARNING, OcrOutcome.warning(blockCount = 0, ocrFailure = null))
    assertEquals(OcrOutcome.NO_TEXT_WARNING, OcrOutcome.warning(blockCount = -1, ocrFailure = null))
  }

  @Test
  fun detectedNativeOrOcrTextDoesNotCreateAnEmptyPageWarning() {
    assertNull(OcrOutcome.warning(blockCount = 1, ocrFailure = null))
  }

  @Test
  fun ocrFailureRemainsVisibleEvenWhenNativeTextWasFound() {
    val failure = "OCR data could not be initialized."
    assertEquals(failure, OcrOutcome.warning(blockCount = 2, ocrFailure = failure))
  }

  @Test
  fun nativeAndOcrComparisonIgnoresPunctuationAndParagraphLineSubsets() {
    assertFalse(OcrOutcome.disagrees("Wheel nuts — 140 N·m", "Wheel nuts 140 N m"))
    assertFalse(OcrOutcome.disagrees("Wheel nuts 140 N m", "Wheel nuts"))
  }

  @Test
  fun materialGlyphDisagreementIsMarkedForReview() {
    assertTrue(OcrOutcome.disagrees("Torque: 140Y", "Torque: 140N·m"))
    assertEquals(OcrOutcome.SOURCE_DISAGREEMENT_WARNING, OcrOutcome.warning(1, null, 1))
  }
}

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
    assertFalse(OcrOutcome.disagrees("Wheel nuts", "Wheel nuts 140 N m"))
  }

  @Test
  fun materialGlyphDisagreementIsMarkedForReview() {
    assertTrue(OcrOutcome.disagrees("Torque: 140Y", "Torque: 140N·m"))
    assertEquals(OcrOutcome.SOURCE_DISAGREEMENT_WARNING, OcrOutcome.warning(1, null, 1))
  }

  @Test
  fun comparisonHandlesShortEqualAndContainedNormalizedText() {
    assertFalse(OcrOutcome.disagrees("!!!", "???"))
    assertFalse(OcrOutcome.disagrees("Long enough source", "!!!"))
    assertFalse(OcrOutcome.disagrees("Same text", "same-text"))
    assertFalse(OcrOutcome.disagrees("Pressure 100 kPa", "pressure"))
    assertFalse(OcrOutcome.disagrees("abcdefgh", "abcdefghx"))
  }

  @Test
  fun numericalSubsequenceAndSimilarityFallbacksAreBothEvaluated() {
    assertFalse(OcrOutcome.disagrees("Valve 20 opens at 30 psi", "Xalve 20 opens at 30 psi"))
    assertFalse(OcrOutcome.disagrees("Valve 20 opens at 30 psi", "Valve 20 opens at 30 psi now"))
    assertTrue(OcrOutcome.disagrees("Valve 20 opens at 30 psi", "Valve 90 closes at 70 bar"))
    assertTrue(OcrOutcome.disagrees("Valve 20 opens at 30 psi", "Completely unrelated words here"))
    assertTrue(OcrOutcome.disagrees("The quick brown fox jumps over the road", "An unrelated sentence without matching words"))
    assertTrue(OcrOutcome.disagrees("A 1x 2y", "zzzzz 1x"))
    assertTrue(OcrOutcome.disagrees("bbA 1x", "zzzzz 1x 2y"))
  }
}

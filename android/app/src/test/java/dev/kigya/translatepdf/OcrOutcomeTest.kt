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
  fun comparisonUsesTheGeometryMatchedNativeLineInsteadOfItsWholeParagraph() {
    val paragraph = "Overview Header Replace filter"
    val matchedLine = "Overview Header"
    assertFalse("paragraph-wide substring comparison masks a same-region disagreement",
      OcrOutcome.disagrees(paragraph, matchedLine))
    assertTrue("the actual owned line fragment disagrees with OCR in that region",
      OcrOutcome.disagrees("Replace filter", matchedLine))
  }

  @Test
  fun materialGlyphDisagreementIsMarkedForReview() {
    assertTrue(OcrOutcome.disagrees("Torque: 140Y", "Torque: 140N·m"))
    assertEquals(OcrOutcome.SOURCE_DISAGREEMENT_WARNING, OcrOutcome.warning(1, null, 1))
  }

  @Test
  fun shortDiagramLabelsAndNumbersAreNotSilentlyDiscardedOnDisagreement() {
    assertTrue(OcrOutcome.disagrees("A1", "A2"))
    assertTrue(OcrOutcome.disagrees("100", "10"))
    assertTrue(OcrOutcome.disagrees("A1", "A10 label"))
    assertTrue(OcrOutcome.disagrees("Long source label", "A2"))
    assertFalse(OcrOutcome.disagrees("A1", "a-1"))
    assertFalse(OcrOutcome.disagrees("!!!", "A1"))
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

  @Test
  fun verifiedTocRowsIgnoreOnlyLeaderNoiseWhenLabelAndFinalReferenceMatch() {
    val cases = listOf(
      Triple("Storage . . . . . . . . . . 88", "Storage", "Storage ............ eee 88"),
      Triple("Lighting . . . . . . . . . . 144", "Lighting", "Lighting ...................00.. 144"),
      Triple("OnStar . . . . . . . . . . 391", "OnStar", "OnStar ...............22 02s 391"),
    )
    for ((source, label, ocr) in cases) {
      assertTrue("fixture must otherwise trigger ordinary OCR disagreement: $label", OcrOutcome.disagrees(source, ocr))
      assertTrue(label, OcrOutcome.isVerifiedTocLeaderNoise(source, source, label, ocr))
    }
  }

  @Test
  fun tocLeaderNoiseRequiresExactLabelReferenceAndSingleLineMetadata() {
    val source = "Storage . . . . . . . . . . 88"
    val ocr = "Storage ............ eee 88"
    assertFalse(OcrOutcome.isVerifiedTocLeaderNoise(source, source, "Storage ", ocr))
    assertFalse(OcrOutcome.isVerifiedTocLeaderNoise(source, "Other . . . . . . . . . . 88", "Storage", ocr))
    assertFalse(OcrOutcome.isVerifiedTocLeaderNoise(source, source, "Storage", "Storagx ............ eee 88"))
    assertFalse(OcrOutcome.isVerifiedTocLeaderNoise(source, source, "Storage", "Storage ............ eee 89"))
    assertFalse(OcrOutcome.isVerifiedTocLeaderNoise(source, source, "Storage", "Storage ............ eee 88 extra"))
    assertFalse(OcrOutcome.isVerifiedTocLeaderNoise(source, source, "Storage", "Storage 88"))
    assertFalse(OcrOutcome.isVerifiedTocLeaderNoise("Control pressure is 88 psi", "Control pressure is 88 psi",
      "Control pressure is 88 psi", "Control pressure is 90 psi"))
    assertFalse(OcrOutcome.isVerifiedTocLeaderNoise("Storage . . . . . . . . . . 88\nLocation",
      "Storage . . . . . . . . . . 88\nLocation", "Storage", "Storage ............ eee 88"))
  }
}

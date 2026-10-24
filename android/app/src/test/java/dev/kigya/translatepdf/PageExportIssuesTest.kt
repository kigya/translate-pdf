package dev.kigya.translatepdf

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class PageExportIssuesTest {
  @Test
  fun nativeLayoutIssueMarksOnlyItsBlockPartialAndPreservesTranslation() {
    val blocks = JSONArray().put(JSONObject().put("id", "b1").put("status", "translated")
      .put("translatedText", "Перевод").put("error", ""))
    val issues = JSONArray().put(JSONObject().put("blockId", "b1").put("reason", "layout_fallback")
      .put("message", "Translation does not fit the source box."))

    assertTrue(PageExportIssues.applyToBlocks(blocks, issues))

    val block = blocks.getJSONObject(0)
    assertEquals("partial", block.getString("status"))
    assertEquals("Перевод", block.getString("translatedText"))
    assertEquals("Translation does not fit the source box.", block.getString("error"))
    assertEquals("layout", block.getJSONArray("exportIssues").getJSONObject(0).getString("kind"))
    assertFalse(PageExportIssues.requiresTranslationReview(block))
    assertFalse("repeated export finding should be idempotent", PageExportIssues.applyToBlocks(blocks, issues))
  }

  @Test
  fun existingBlockReviewErrorsAreNotOverwrittenByLayoutIssue() {
    val blocks = JSONArray().put(JSONObject().put("id", "b1").put("status", "partial")
      .put("error", "Check the changed unit."))
      .put(JSONObject().put("id", "b2").put("status", "failed").put("error", "Translation failed."))
    val issues = JSONArray().put(JSONObject().put("blockId", "b1").put("message", "Layout fallback."))
      .put(JSONObject().put("blockId", "b2").put("message", "Missing output."))

    assertTrue(PageExportIssues.applyToBlocks(blocks, issues))
    assertEquals("Check the changed unit.", blocks.getJSONObject(0).getString("error"))
    assertEquals("Translation failed.", blocks.getJSONObject(1).getString("error"))
    assertTrue(PageExportIssues.requiresTranslationReview(blocks.getJSONObject(0)))
    assertTrue(PageExportIssues.requiresTranslationReview(blocks.getJSONObject(1)))
    assertEquals("OCR warning\nLayout fallback.\nMissing output.\nCheck the changed unit.\nTranslation failed.",
      PageExportIssues.pageError("OCR warning", blocks, true, issues))
  }

  @Test
  fun partialPageWithoutSpecificIssueStillHasReviewSummary() {
    val blocks = JSONArray()
    assertEquals("Some translated content could not be placed safely; review this page.",
      PageExportIssues.pageError(null, blocks, true, JSONArray()))
    assertNull(PageExportIssues.pageError(null, blocks, false, JSONArray()))
  }

  @Test
  fun malformedAndUnmatchedLayoutRecordsAreIgnoredAndFallbacksAreStable() {
    val blocks = JSONArray()
      .put(JSONObject.NULL)
      .put(JSONObject().put("id", "b1").put("status", "partial").put("error", " "))
      .put(JSONObject().put("id", "b2").put("status", "translated"))
    val issues = JSONArray()
      .put(JSONObject.NULL)
      .put(JSONObject().put("blockId", " ").put("message", "Ignored without an id."))
      .put(JSONObject().put("blockId", "unknown").put("message", "Ignored unmatched issue."))
      .put(JSONObject().put("blockId", "b1").put("reason", "line_overflow"))

    assertTrue(PageExportIssues.applyToBlocks(blocks, issues))
    assertEquals("partial", blocks.getJSONObject(1).getString("status"))
    assertEquals("line overflow", blocks.getJSONObject(1).getString("error"))
    assertEquals("translated", blocks.getJSONObject(2).getString("status"))

    val fallbackIssues = JSONArray()
      .put(JSONObject.NULL)
      .put(JSONObject().put("message", " ").put("reason", " "))
    val error = PageExportIssues.pageError(
      " ",
      JSONArray().put(JSONObject.NULL).put(JSONObject().put("status", "failed").put("error", " ")),
      true,
      fallbackIssues,
    )
    assertEquals("Some translated content could not be placed safely; review this page.", error)
  }

  @Test
  fun layoutOnlyPartialIsDistinctFromMissingTranslationAndUnknownPlacementFindings() {
    val layoutOnly = JSONObject()
      .put("status", "partial")
      .put("translatedText", "Перевод")
      .put("error", "Readable-size fallback.")
      .put("exportIssues", JSONArray().put(JSONObject()
        .put("kind", "layout").put("reason", "layout_fallback").put("message", "Readable-size fallback.")))
    val legacyLayoutOnly = JSONObject()
      .put("status", "partial")
      .put("translatedText", "Перевод")
      .put("error", "Readable-size fallback.")
      .put("exportIssues", JSONArray().put(JSONObject()
        .put("reason", "layout_fallback").put("message", "Readable-size fallback.")))
    val missingTranslation = JSONObject()
      .put("status", "partial")
      .put("translatedText", JSONObject.NULL)
      .put("exportIssues", JSONArray().put(JSONObject()
        .put("kind", "translation").put("reason", "missing_translation").put("message", "No translation.")))
    val unsafePlacement = JSONObject()
      .put("status", "partial")
      .put("translatedText", "Целевой текст")
      .put("exportIssues", JSONArray().put(JSONObject()
        .put("kind", "unknown").put("reason", "unsafe_text_object").put("message", "Unsafe object.")))
    val disagreement = JSONObject()
      .put("status", "partial")
      .put("sourceDisagreement", true)
      .put("translatedText", "Целевой текст")
      .put("exportIssues", JSONArray().put(JSONObject()
        .put("kind", "layout").put("reason", "layout_fallback").put("message", "Readable-size fallback.")))
    val legacyPartial = JSONObject().put("status", "partial").put("error", "Old checkpoint warning.")
    val missingTextWithLayoutFinding = JSONObject()
      .put("status", "partial")
      .put("translatedText", JSONObject.NULL)
      .put("exportIssues", JSONArray().put(JSONObject()
        .put("kind", "layout").put("reason", "layout_fallback").put("message", "Readable-size fallback.")))

    assertFalse(PageExportIssues.requiresTranslationReview(layoutOnly))
    assertFalse(PageExportIssues.requiresTranslationReview(legacyLayoutOnly))
    assertTrue(PageExportIssues.requiresTranslationReview(missingTranslation))
    assertTrue(PageExportIssues.requiresTranslationReview(unsafePlacement))
    assertTrue(PageExportIssues.requiresTranslationReview(disagreement))
    assertTrue(PageExportIssues.requiresTranslationReview(legacyPartial))
    assertTrue(PageExportIssues.requiresTranslationReview(missingTextWithLayoutFinding))
  }

  @Test
  fun newlyExportedTranslationFindingIsPersistedWithoutClearingTranslatedText() {
    val block = JSONObject().put("id", "b7").put("status", "translated")
      .put("translatedText", "Перевод сохранён")
    val issues = JSONArray().put(JSONObject().put("blockId", "b7")
      .put("reason", "missing_translation").put("message", "No complete translation is available."))

    assertTrue(PageExportIssues.applyToBlocks(JSONArray().put(block), issues))

    assertEquals("Перевод сохранён", block.getString("translatedText"))
    val finding = block.getJSONArray("exportIssues").getJSONObject(0)
    assertEquals("translation", finding.getString("kind"))
    assertEquals("missing_translation", finding.getString("reason"))
    assertTrue(PageExportIssues.requiresTranslationReview(block))
  }

  @Test
  fun layoutFindingNeverMasksPreviouslyRecordedTranslationError() {
    val block = JSONObject().put("id", "b8").put("status", "partial")
      .put("translatedText", "Перевод сохранён").put("error", "Protected value changed.")
    val issues = JSONArray().put(JSONObject().put("blockId", "b8")
      .put("reason", "layout_fallback").put("message", "Readable-size fallback."))

    PageExportIssues.applyToBlocks(JSONArray().put(block), issues)

    assertEquals("Protected value changed.", block.getString("error"))
    assertNotNull(block.optJSONArray("exportIssues"))
    assertTrue(PageExportIssues.requiresTranslationReview(block))
  }

  @Test
  fun firstLayoutFindingPreservesUnknownLegacyPartialProvenance() {
    val block = JSONObject().put("id", "legacy-partial").put("status", "partial")
      .put("translatedText", "Перевод сохранён").put("error", "")
    val blocks = JSONArray().put(block)
    val layoutFinding = JSONArray().put(JSONObject().put("blockId", "legacy-partial")
      .put("reason", "layout_fallback").put("message", "Readable-size fallback."))

    assertTrue(PageExportIssues.requiresTranslationReview(block))
    assertTrue(PageExportIssues.applyToBlocks(blocks, layoutFinding))

    assertTrue(block.getBoolean("translationReviewRequired"))
    assertEquals("Readable-size fallback.", block.getString("error"))
    assertTrue(PageExportIssues.requiresTranslationReview(block))
  }

  @Test
  fun applyingLegacyIosLayoutFindingDoesNotMarkItAsUnknownPartial() {
    val legacyFinding = JSONObject().put("reason", "layout_fallback").put("message", "Readable-size fallback.")
    val block = JSONObject().put("id", "legacy-ios").put("status", "partial")
      .put("translatedText", "Перевод").put("error", "Readable-size fallback.")
      .put("exportIssues", JSONArray().put(legacyFinding))
    val blocks = JSONArray().put(block)
    val layoutFinding = JSONArray().put(JSONObject().put("blockId", "legacy-ios")
      .put("reason", "layout_fallback").put("message", "Readable-size fallback."))

    assertFalse(PageExportIssues.requiresTranslationReview(block))
    assertFalse(PageExportIssues.applyToBlocks(blocks, layoutFinding))
    assertFalse(block.has("translationReviewRequired"))
    assertFalse(PageExportIssues.requiresTranslationReview(block))
  }

  @Test
  fun mixedTranslationAndLayoutFindingsKeepBothReasonsAndRequireReview() {
    val block = JSONObject().put("id", "mixed").put("status", "translated")
      .put("translatedText", "Сохранённый перевод")
    val blocks = JSONArray().put(block)
    val issues = JSONArray()
      .put(JSONObject().put("blockId", "mixed").put("reason", "layout_fallback")
        .put("message", "Text did not fit the original box."))
      .put(JSONObject().put("blockId", "mixed").put("reason", "missing_translation")
        .put("message", "A source fragment has no translation."))

    assertTrue(PageExportIssues.applyToBlocks(blocks, issues))

    assertEquals("partial", block.getString("status"))
    assertEquals("Сохранённый перевод", block.getString("translatedText"))
    assertTrue(PageExportIssues.requiresTranslationReview(block))
    val stored = block.getJSONArray("exportIssues")
    assertEquals(2, stored.length())
    assertEquals("layout", stored.getJSONObject(0).getString("kind"))
    assertEquals("translation", stored.getJSONObject(1).getString("kind"))
    assertEquals("Text did not fit the original box.\nA source fragment has no translation.",
      PageExportIssues.pageError(null, blocks, false, issues))
  }

  @Test
  fun malformedIssueCollectionsAndUnknownMixedRowsStayReviewable() {
    val malformedCollection = JSONObject().put("status", "partial")
      .put("translatedText", "Перевод").put("exportIssues", "not-an-array")
    val nonObjectRow = JSONObject().put("status", "partial").put("translatedText", "Перевод")
      .put("exportIssues", JSONArray().put("malformed"))
    val layoutPlusUnknown = JSONObject().put("status", "partial").put("translatedText", "Перевод")
      .put("error", "Known placement warning.")
      .put("exportIssues", JSONArray()
        .put(JSONObject().put("reason", "layout_fallback").put("message", "Known placement warning."))
        .put(JSONObject().put("kind", "unknown").put("reason", "unknown_native_finding")
          .put("message", "Unclassified native issue.")))

    assertTrue(PageExportIssues.requiresTranslationReview(malformedCollection))
    assertTrue(PageExportIssues.requiresTranslationReview(nonObjectRow))
    assertTrue(PageExportIssues.requiresTranslationReview(layoutPlusUnknown))
  }
}

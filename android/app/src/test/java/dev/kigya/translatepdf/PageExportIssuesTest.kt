package dev.kigya.translatepdf

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    val issues = JSONArray().put(JSONObject().put("blockId", "b1").put("reason", "overflow")
      .put("message", "Translation does not fit the source box."))

    assertTrue(PageExportIssues.applyToBlocks(blocks, issues))

    val block = blocks.getJSONObject(0)
    assertEquals("partial", block.getString("status"))
    assertEquals("Перевод", block.getString("translatedText"))
    assertEquals("Translation does not fit the source box.", block.getString("error"))
  }

  @Test
  fun existingBlockReviewErrorsAreNotOverwrittenByLayoutIssue() {
    val blocks = JSONArray().put(JSONObject().put("id", "b1").put("status", "partial")
      .put("error", "Check the changed unit."))
      .put(JSONObject().put("id", "b2").put("status", "failed").put("error", "Translation failed."))
    val issues = JSONArray().put(JSONObject().put("blockId", "b1").put("message", "Layout fallback."))
      .put(JSONObject().put("blockId", "b2").put("message", "Missing output."))

    assertFalse(PageExportIssues.applyToBlocks(blocks, issues))
    assertEquals("Check the changed unit.", blocks.getJSONObject(0).getString("error"))
    assertEquals("Translation failed.", blocks.getJSONObject(1).getString("error"))
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
}

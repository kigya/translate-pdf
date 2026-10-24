package dev.kigya.translatepdf

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only OCR/deduplication audit for the private first-30-page Equinox reference. */
@RunWith(AndroidJUnit4::class)
class ReferenceOcrDeviceTest {
  @Test fun auditReferencePageOcrAndNativeDeduplication() {
    val arguments = InstrumentationRegistry.getArguments()
    assumeTrue("The private OCR reference audit was not requested", arguments.getString("folivelaOcrReference") == "true")

    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val source = File(requireNotNull(context.getExternalFilesDir(null)), SOURCE_NAME)
    val startedAt = System.currentTimeMillis()
    assertTrue("Stage the private first-30-page reference in app external files", source.isFile)
    assertEquals(SOURCE_SHA256, sha256(source))
    val inspection = JSONObject(NativeCore.inspect(source.absolutePath))
    assertEquals(REFERENCE_PAGE_COUNT, inspection.getInt("pageCount"))

    val service = PdfTranslationService()
    val combine = PdfTranslationService::class.java.getDeclaredMethod(
      "combineNativeAndRecognizedOcr",
      JSONArray::class.java,
      JSONArray::class.java,
      Float::class.javaPrimitiveType,
      Float::class.javaPrimitiveType,
      JSONArray::class.java,
    ).apply { isAccessible = true }
    val engine = OcrEngine(context, sourceLanguage = "en")
    val pageReports = JSONArray()
    var totalNativeBlocks = 0
    var totalNativeSourceRects = 0
    var totalRawOcrLines = 0
    var totalRemainingOcrCandidates = 0
    var totalDisagreementBlocks = 0
    var totalReadMs = 0L
    var totalRenderMs = 0L
    var totalOcrMs = 0L
    var totalMergeMs = 0L

    try {
      for (pageIndex in 0 until REFERENCE_PAGE_COUNT) {
        val readStartedAt = SystemClock.elapsedRealtime()
        val nativePage = JSONObject(NativeCore.readPage(source.absolutePath, pageIndex))
        val readMs = (SystemClock.elapsedRealtime() - readStartedAt).coerceAtLeast(0L)
        assertTrue("PDFium failed to read reference page ${pageIndex + 1}", !nativePage.has("error"))
        val nativeBlocks = nativePage.optJSONArray("blocks") ?: JSONArray()
        assertTrue("Native page ${pageIndex + 1} must include source artwork metadata", nativePage.has("artworkRects"))
        val artworkRects = nativePage.optJSONArray("artworkRects") ?: JSONArray()
        var pageSourceRects = 0
        for (blockIndex in 0 until nativeBlocks.length()) {
          val block = nativeBlocks.getJSONObject(blockIndex)
          val rects = block.optJSONArray("sourceRects")
          assertTrue(
            "Native block ${block.optString("id")} on page ${pageIndex + 1} has no owned sourceRects",
            rects != null && rects.length() > 0,
          )
          pageSourceRects += requireNotNull(rects).length()
        }

        val pageWidth = nativePage.getDouble("width").toFloat()
        val pageHeight = nativePage.getDouble("height").toFloat()
        val renderStartedAt = SystemClock.elapsedRealtime()
        val bitmap = NativeCore.render(source.absolutePath, pageIndex, OCR_RENDER_WIDTH)
        val renderMs = (SystemClock.elapsedRealtime() - renderStartedAt).coerceAtLeast(0L)
        try {
          val ocrStartedAt = SystemClock.elapsedRealtime()
          // Keep the actual word boxes and confidence for the page containing
          // the isolated logo-boundary glyph. Other pages remain on the normal
          // line-only audit path.
          val recognition = if (pageIndex == 2) {
            engine.recognizeDetailed(bitmap, pageWidth, pageHeight)
          } else {
            null
          }
          val rawOcr = recognition?.lines ?: engine.recognize(bitmap, pageWidth, pageHeight)
          val ocrMs = (SystemClock.elapsedRealtime() - ocrStartedAt).coerceAtLeast(0L)

          val mergeStartedAt = SystemClock.elapsedRealtime()
          val mergedBlocks = combine.invoke(service, nativeBlocks, rawOcr, pageWidth, pageHeight, artworkRects) as JSONArray
          val mergeMs = (SystemClock.elapsedRealtime() - mergeStartedAt).coerceAtLeast(0L)
          val mergedOcrIds = (0 until mergedBlocks.length()).mapNotNull { index ->
            mergedBlocks.optJSONObject(index)?.takeIf { it.optString("source") == "ocr" }?.optString("id")
          }.toSet()
          val unmatchedOcr = JSONArray()
          for (index in 0 until rawOcr.length()) {
            val line = rawOcr.getJSONObject(index)
            if (line.optString("id") in mergedOcrIds) unmatchedOcr.put(line)
          }
          val disagreements = JSONArray()
          for (index in 0 until mergedBlocks.length()) {
            val block = mergedBlocks.getJSONObject(index)
            if (block.optBoolean("sourceDisagreement")) disagreements.put(block)
          }

          pageReports.put(JSONObject()
            .put("pageIndex", pageIndex)
            .put("width", pageWidth)
            .put("height", pageHeight)
            .put("nativeBlockCount", nativeBlocks.length())
            .put("nativeSourceRectCount", pageSourceRects)
            .put("artworkRects", artworkRects)
            .put("artworkRectCount", artworkRects.length())
            .put("rawOcrLines", rawOcr)
            .put("rawOcrWordEvidence", recognition?.words ?: JSONArray())
            .put("mergedBlocks", mergedBlocks)
            .put("remainingOcrCandidatesForReview", unmatchedOcr)
            .put("sourceDisagreementBlocks", disagreements)
            .put("rawOcrLineCount", rawOcr.length())
            .put("remainingOcrCandidateCount", unmatchedOcr.length())
            .put("sourceDisagreementCount", disagreements.length())
            .put("readMs", readMs)
            .put("renderMs", renderMs)
            .put("ocrMs", ocrMs)
            .put("mergeMs", mergeMs))

          totalNativeBlocks += nativeBlocks.length()
          totalNativeSourceRects += pageSourceRects
          totalRawOcrLines += rawOcr.length()
          totalRemainingOcrCandidates += unmatchedOcr.length()
          totalDisagreementBlocks += disagreements.length()
          totalReadMs += readMs
          totalRenderMs += renderMs
          totalOcrMs += ocrMs
          totalMergeMs += mergeMs
        } finally {
          bitmap.recycle()
        }
      }
    } finally {
      engine.close()
    }

    assertEquals("Every reference page must be processed", REFERENCE_PAGE_COUNT, pageReports.length())
    val report = JSONObject()
      .put("schemaVersion", 3)
      .put("complete", true)
      .put("sourceFile", SOURCE_NAME)
      .put("sourceSha256", SOURCE_SHA256)
      .put("wordEvidenceDiagnosticPageIndex", 2)
      .put("pageCount", REFERENCE_PAGE_COUNT)
      .put("startedAt", startedAt)
      .put("nativeBlockCount", totalNativeBlocks)
      .put("nativeSourceRectCount", totalNativeSourceRects)
      .put("rawOcrLineCount", totalRawOcrLines)
      .put("remainingOcrCandidateCount", totalRemainingOcrCandidates)
      .put("sourceDisagreementCount", totalDisagreementBlocks)
      .put("timingsMs", JSONObject()
        .put("pdfRead", totalReadMs)
        .put("render", totalRenderMs)
        .put("ocrRecognition", totalOcrMs)
        .put("ocrMerge", totalMergeMs))
      .put("pages", pageReports)
    val evidence = File(requireNotNull(context.getExternalFilesDir(null)), OUTPUT_NAME)
    evidence.writeText(report.toString(2))
    assertTrue("The complete private OCR audit was not saved", evidence.isFile && evidence.length() > 0L)
  }

  private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

  private companion object {
    const val REFERENCE_PAGE_COUNT = 30
    const val SOURCE_NAME = "u050-equinox-first30-reference.pdf"
    const val SOURCE_SHA256 = "2f37872b6aad382c51d8a0e80a9aa690eb35f8ac47277bfd15389ded7372beed"
    const val OUTPUT_NAME = "u052-ocr-reference.json"
    const val OCR_RENDER_WIDTH = 1400
  }
}

package dev.kigya.translatepdf

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class JobPdfExporterTest {
  private lateinit var context: Context
  private lateinit var database: TranslatorDatabase
  private lateinit var source: File
  private lateinit var font: File

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    context.deleteDatabase("translator-private.db")
    database = TranslatorDatabase(context)
    source = File(context.filesDir, "sample-source.pdf").apply { writeText("%PDF-1.7\nsource") }
    font = File(context.cacheDir, "test-font.ttf").apply { writeText("font") }
    database.upsertDocument("doc-1", "sample.pdf", source.absolutePath, 1, "a".repeat(64))
    database.addJob("job-1", JSONObject().put("documentId", "doc-1").put("sourceLanguage", "en")
      .put("targetLanguage", "ru").put("modelId", ModelCatalog.RECOMMENDED_ID), 1)
  }

  @After
  fun tearDown() {
    database.close()
    context.deleteDatabase("translator-private.db")
  }

  @Test
  fun finalPdfIsVerifiedSavedAndReusedByJobId() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val native = FakePdfNative()
    val exporter = exporter(native)

    val first = exporter.getOrBuild("job-1")
    val cached = exporter.getOrBuild("job-1")

    assertTrue(first.file.isFile)
    assertEquals(1, first.pageCount)
    assertFalse(first.partial)
    assertEquals(first.exportId, cached.exportId)
    assertEquals("sample-en-ru.pdf", first.displayName)
    assertEquals(first.file.absolutePath, database.export(first.exportId)?.first)
    assertEquals(1, native.mergeCalls)
  }

  @Test
  fun nativeLayoutIssuePersistsBlockAndPageReviewBeforeSuccessfulMerge() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val native = FakePdfNative().apply {
      issues = JSONArray().put(JSONObject().put("blockId", "block-1").put("reason", "layout_fallback")
        .put("message", "The original remains with the translation attached for review."))
    }

    val result = exporter(native).getOrBuild("job-1")
    val savedPage = database.page("job-1", 0)!!
    val block = savedPage.getJSONArray("blocks").getJSONObject(0)

    assertTrue(result.partial)
    assertEquals("partial", savedPage.getString("status"))
    assertTrue(savedPage.getString("error").contains("attached for review"))
    assertEquals("partial", block.getString("status"))
    assertTrue(block.getString("error").contains("attached for review"))
    assertEquals(2, native.pageExportCalls)
    assertEquals(1, native.mergeCalls)
  }

  @Test
  fun failedMergeLeavesDurablePageCheckpointAndDoesNotPublishOutput() {
    savePage("partial", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", JSONObject.NULL).put("status", "failed").put("error", "Translation failed."))
    val native = FakePdfNative().apply { mergeError = true }
    var failed = false
    try { exporter(native).getOrBuild("job-1") }
    catch (_: IllegalStateException) { failed = true }

    assertTrue(failed)
    assertNotNull(database.page("job-1", 0))
    assertEquals("partial", database.page("job-1", 0)!!.getString("status"))
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.extension == "pdf" })
    assertEquals(0, native.successfulMergeCalls)
  }

  @Test
  fun cancellationBeforeAssemblyPreservesCheckpointAndCreatesNoFinalPdf() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    var interrupted = false
    try { exporter(FakePdfNative()).getOrBuild("job-1") { true } }
    catch (_: InterruptedException) { interrupted = true }

    assertTrue(interrupted)
    assertEquals("completed", database.page("job-1", 0)!!.getString("status"))
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().isEmpty())
  }

  @Test
  fun legacyPipelineCheckpointRemainsExportableButCannotContinue() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    database.writableDatabase.execSQL("UPDATE jobs SET pipeline_version=3 WHERE id='job-1'")

    assertTrue(runCatching { database.requireCurrentPipeline("job-1") }.isFailure)
    assertTrue(exporter(FakePdfNative()).getOrBuild("job-1").file.isFile)
  }

  private fun exporter(native: FakePdfNative) = JobPdfExporter(context, database, native,
    fontProvider = { font }, cjkFontProvider = { font })

  private fun savePage(status: String, block: JSONObject) {
    database.savePage("job-1", 0, status, JSONArray().put(block).toString(), 612.0, 792.0, null, 1)
  }

  private class FakePdfNative : JobPdfNativeOperations {
    var issues = JSONArray()
    var mergeError = false
    var mergeCalls = 0
    var successfulMergeCalls = 0
    var pageExportCalls = 0
    private var finalPageCount = 0
    private val pagePaths = mutableSetOf<String>()

    override fun inspect(path: String): String {
      val file = File(path)
      if (!file.isFile || !file.readText().startsWith("%PDF-")) return JSONObject().put("error", "invalid").toString()
      return JSONObject().put("pageCount", if (path in pagePaths) 1 else finalPageCount).toString()
    }

    override fun exportPage(source: String, pageIndex: Int, blocksJson: String, destination: String, fontPath: String, cjkFontPath: String): String {
      pageExportCalls++
      File(destination).writeText("%PDF-1.7\npage")
      pagePaths.add(destination)
      return JSONObject().put("partial", issues.length() > 0).put("partialBlocks", issues.length()).put("issues", issues).toString()
    }

    override fun mergePdf(pagesJson: String, destination: String): String {
      mergeCalls++
      if (mergeError) return JSONObject().put("error", "synthetic merge failure").toString()
      val pages = JSONArray(pagesJson)
      finalPageCount = pages.length()
      File(destination).writeText("%PDF-1.7\nmerged")
      successfulMergeCalls++
      return JSONObject().put("pageCount", pages.length()).toString()
    }
  }
}

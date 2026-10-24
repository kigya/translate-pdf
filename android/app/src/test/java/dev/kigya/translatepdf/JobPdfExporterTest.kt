package dev.kigya.translatepdf

import android.app.Application
import android.graphics.Bitmap
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [ShadowNativePdfCore::class])
class JobPdfExporterTest {
  private lateinit var context: Context
  private lateinit var database: TranslatorDatabase
  private lateinit var source: File
  private lateinit var font: File

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    context.deleteDatabase("translator-private.db")
    File(context.filesDir, "exports").deleteRecursively()
    context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("job-export-") }
      .forEach { it.deleteRecursively() }
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
  fun cachedReceiptVerifiesCurrentPublishedPdfAndCheckpointWithoutRebuilding() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated"))
    val native = FakePdfNative()
    val pdfExporter = exporter(native)
    val published = pdfExporter.getOrBuild("job-1")

    val cached = pdfExporter.cachedReceipt("job-1")
    assertNotNull(cached)
    assertEquals(published.receipt.toJson().toString(), cached!!.toJson().toString())
    assertEquals(1, native.mergeCalls)

    val changedBlock = JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Задвижка").put("status", "translated")
    savePage("completed", changedBlock)
    assertEquals("a changed checkpoint cannot reuse the old layout receipt", null, pdfExporter.cachedReceipt("job-1"))
    assertEquals(1, native.mergeCalls)
  }

  @Test
  fun cachedReceiptRejectsMissingOrExternalExportAndMissingSource() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated"))
    val pdfExporter = exporter(FakePdfNative())
    assertEquals(null, pdfExporter.cachedReceipt("job-1"))
    val published = pdfExporter.getOrBuild("job-1")
    val userCopy = File(context.filesDir, "user-export.pdf").apply { writeText("%PDF-user") }
    database.saveExport(published.exportId, userCopy.absolutePath, "User export")
    assertEquals("user-selected external export is not an app receipt", null, pdfExporter.cachedReceipt("job-1"))

    database.saveExport(published.exportId, published.file.absolutePath, published.displayName)
    source.delete()
    assertEquals("the receipt cannot be trusted without its current source", null, pdfExporter.cachedReceipt("job-1"))
  }

  @Test
  fun corruptReceiptCannotReuseAPreviouslyVerifiedGeneration() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated"))
    val native = FakePdfNative()
    val pdfExporter = exporter(native)
    val published = pdfExporter.getOrBuild("job-1")
    File(published.file.parentFile, "layout-receipt.json").writeText("{broken receipt")

    assertEquals(null, pdfExporter.cachedReceipt("job-1"))
    val rebuilt = pdfExporter.getOrBuild("job-1")

    assertTrue(rebuilt.file.isFile)
    assertTrue("the corrupt private generation must be replaced only after a new verified receipt exists",
      rebuilt.file.absolutePath != published.file.absolutePath)
    assertTrue(File(rebuilt.file.parentFile, "layout-receipt.json").readText().startsWith("{"))
    assertFalse(published.generationDirectory.exists())
    assertEquals(2, native.mergeCalls)
  }

  @Test
  fun numericStringReceiptIsRejectedRebuiltAndDoesNotLoseTranslatedCheckpoint() {
    val translatedBlock = JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated")
    savePage("completed", translatedBlock)
    val native = FakePdfNative()
    val pdfExporter = exporter(native)
    val published = pdfExporter.getOrBuild("job-1")
    val receiptFile = File(published.file.parentFile, "layout-receipt.json")
    val forged = JSONObject(receiptFile.readText()).put("sourcePageCount", "1")
    receiptFile.writeText(forged.toString())

    assertEquals("wrong JSON types invalidate rebuildable cache metadata", null, pdfExporter.cachedReceipt("job-1"))
    val rebuilt = pdfExporter.getOrBuild("job-1")

    assertTrue(rebuilt.file.isFile)
    assertEquals(2, native.mergeCalls)
    assertEquals(published.exportId, rebuilt.exportId)
    assertEquals("Клапан", database.pageBlockArray("job-1", 0).getJSONObject(0).getString("translatedText"))
    assertEquals("translated", database.pageBlockArray("job-1", 0).getJSONObject(0).getString("status"))
    assertTrue("the invalid cache generation is removed only after a verified replacement exists",
      !published.generationDirectory.exists() && rebuilt.generationDirectory.exists())
    assertEquals(rebuilt.file.absolutePath, database.export(rebuilt.exportId)?.first)
  }

  @Test
  fun cachedReceiptReturnsNullForMissingJobAndNonpositiveSourcePageCount() {
    val pdfExporter = exporter(FakePdfNative())

    assertEquals(null, pdfExporter.cachedReceipt("missing-job"))
    database.writableDatabase.execSQL("UPDATE jobs SET total_pages=0 WHERE id='job-1'")
    assertEquals(null, pdfExporter.cachedReceipt("job-1"))
  }

  @Test
  fun cachedReceiptRejectsPrivatePathWithWrongFilenameAndCheckpointDigestFailure() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated"))
    val pdfExporter = exporter(FakePdfNative())
    val published = pdfExporter.getOrBuild("job-1")
    val otherPrivateFile = File(published.file.parentFile, "not-translated.pdf").apply { writeText("%PDF-1.7\nnot output") }
    File(otherPrivateFile.parentFile, "layout-receipt.json").writeText(File(published.file.parentFile,
      "layout-receipt.json").readText())
    database.saveExport(published.exportId, otherPrivateFile.absolutePath, "Internal test path")

    assertEquals(null, pdfExporter.cachedReceipt("job-1"))

    database.saveExport(published.exportId, published.file.absolutePath, published.displayName)
    database.writableDatabase.execSQL("DELETE FROM pages WHERE job_id='job-1' AND page_index=0")
    assertEquals("a missing checkpoint makes receipt lookup fail closed", null, pdfExporter.cachedReceipt("job-1"))
  }

  @Test
  fun deleteUnpublishedRemovesOnlyTheRejectedGeneration() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated"))
    val pdfExporter = exporter(FakePdfNative())
    val published = pdfExporter.getOrBuild("job-1")
    val changedBlock = JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Задвижка").put("status", "translated")
    savePage("completed", changedBlock)
    val candidate = pdfExporter.getOrBuild("job-1", persistExport = false)
    assertTrue(candidate.file.exists())
    assertTrue(candidate.generationDirectory.isDirectory)
    assertEquals(published.file.absolutePath, database.export(published.exportId)?.first)

    pdfExporter.deleteUnpublished(candidate)
    pdfExporter.deleteUnpublished(published)

    assertFalse(candidate.file.exists())
    assertTrue(published.file.exists())
    assertEquals(published.file.absolutePath, database.export(published.exportId)?.first)
  }

  @Test
  fun defaultNativeOperationsReachTheProductionAdapterWithShadowedJniCalls() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    PdfCoreShadowState.reset()

    val result = JobPdfExporter(context, database, fontProvider = { font }, cjkFontProvider = { font }).getOrBuild("job-1")

    assertTrue(result.file.isFile)
    assertEquals(3, PdfCoreShadowState.inspectCalls)
    assertEquals(1, PdfCoreShadowState.exportCalls)
    assertEquals(1, PdfCoreShadowState.mergeCalls)
  }

  @Test
  fun russianPageAndFullDocumentMergeAlwaysReceiveVerifiedCjkFallbackPath() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Temperature − 5 °C")
      .put("translatedText", "Температура − 5 °C").put("status", "translated"))
    val cjkFont = File(context.cacheDir, "verified-cjk-font.ttf").apply { writeText("verified test font") }
    var cjkProviderCalls = 0
    val native = FakePdfNative()
    val pdfExporter = JobPdfExporter(context, database, native,
      fontProvider = { font }, cjkFontProvider = { cjkProviderCalls++; cjkFont })

    pdfExporter.getOrBuild("job-1")

    assertEquals("The RU page export should receive the verified fallback asset", cjkFont.absolutePath, native.lastCjkFontPath)
    assertEquals("The full-source merge should receive the same fallback asset", cjkFont.absolutePath,
      native.lastMergeRequest!!.getString("cjkFontPath"))
    assertEquals(1, cjkProviderCalls)
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
    assertTrue(native.lastSnapshotPages.single().getJSONArray("blocks").getJSONObject(0)
      .getJSONArray("exportIssues").length() > 0)
    assertEquals(2, native.pageExportCalls)
    assertEquals(1, native.mergeCalls)
  }

  @Test
  fun layoutFindingMetadataDoesNotMakeItsVerifiedPdfCacheStale() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val native = FakePdfNative().apply {
      issues = JSONArray().put(JSONObject().put("blockId", "block-1").put("reason", "layout_fallback")
        .put("message", "Readable-size fallback."))
    }
    val pdfExporter = exporter(native)

    val first = pdfExporter.getOrBuild("job-1")
    val second = pdfExporter.getOrBuild("job-1")

    assertTrue(first.partial)
    assertEquals(first.file.absolutePath, second.file.absolutePath)
    assertEquals("first build retries after persisting a new finding", 2, native.pageExportCalls)
    assertEquals(1, native.mergeCalls)
  }

  @Test
  fun oneLayoutRetryCanStabilizeOnTheSecondExportAttempt() {
    val block = JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated")
    savePage("completed", block)
    val firstIssue = JSONArray().put(JSONObject().put("blockId", "block-1").put("reason", "fallback").put("message", "review"))
    val native = FakePdfNative().apply { issuesPerExport = listOf(firstIssue, JSONArray()) }

    val result = exporter(native).getOrBuild("job-1")

    assertTrue(result.partial)
    assertEquals(2, native.pageExportCalls)
    assertEquals(1, native.mergeCalls)
    assertEquals("partial", database.page("job-1", 0)!!.getString("status"))
  }

  @Test
  fun blankAndNonblankCheckpointErrorsAreHandledWhileExporting() {
    val block = JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated")
    database.savePage("job-1", 0, "completed", JSONArray().put(block).toString(), 612.0, 792.0, "   ", 1)
    assertTrue(exporter(FakePdfNative()).getOrBuild("job-1").file.isFile)

    database.deleteExport("job-1")
    database.savePage("job-1", 0, "partial", JSONArray().put(block).toString(), 612.0, 792.0, "review needed", 1)
    val result = exporter(FakePdfNative()).getOrBuild("job-1")
    assertTrue(result.partial)
    assertEquals("review needed", database.page("job-1", 0)!!.getString("error"))
  }

  @Test
  fun nonpartialCheckpointNormalizesExistingErrorBeforeSaving() {
    val block = JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated")
    database.savePage("job-1", 0, "completed", JSONArray().put(block).toString(), 612.0, 792.0, "  Existing note  ", 1)

    val result = exporter(FakePdfNative()).getOrBuild("job-1")

    assertFalse(result.partial)
    assertEquals("completed", database.page("job-1", 0)!!.getString("status"))
    assertEquals("Existing note", database.page("job-1", 0)!!.getString("error"))
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

  @Test
  fun rejectsMissingJobsAndJobsWithoutPagesBeforeCreatingOutput() {
    val exporter = exporter(FakePdfNative())
    assertTrue(runCatching { exporter.getOrBuild("missing") }.exceptionOrNull() is IllegalArgumentException)

    database.addJob("job-empty", JSONObject().put("documentId", "doc-1").put("sourceLanguage", "en")
      .put("targetLanguage", "ru").put("modelId", ModelCatalog.RECOMMENDED_ID), 0)
    assertTrue(runCatching { exporter.getOrBuild("job-empty") }.exceptionOrNull() is IllegalArgumentException)
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.name.endsWith(".pdf") })
  }

  @Test
  fun rejectsMissingSourceAndUnfinishedOrMissingPageCheckpoints() {
    val exporter = exporter(FakePdfNative())
    database.writableDatabase.execSQL("UPDATE documents SET path=? WHERE id='doc-1'", arrayOf(File(context.filesDir, "missing.pdf").absolutePath))
    assertTrue(runCatching { exporter.getOrBuild("job-1") }.exceptionOrNull() is IllegalStateException)

    source.writeText("%PDF-1.7\nsource")
    database.writableDatabase.execSQL("UPDATE documents SET path=? WHERE id='doc-1'", arrayOf(source.absolutePath))
    assertTrue(runCatching { exporter.getOrBuild("job-1") }.exceptionOrNull() is IllegalStateException)
    savePage("running", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", JSONObject.NULL).put("status", "pending"))
    assertTrue(runCatching { exporter.getOrBuild("job-1") }.exceptionOrNull() is IllegalArgumentException)
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.name.endsWith(".pdf") })
  }

  @Test
  fun rejectsInvalidLanguagePairAndSurfacesNativePageExportErrors() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    database.writableDatabase.execSQL("UPDATE jobs SET target_lang='en' WHERE id='job-1'")
    assertTrue(runCatching { exporter(FakePdfNative()).getOrBuild("job-1") }.exceptionOrNull() is IllegalArgumentException)
    database.writableDatabase.execSQL("UPDATE jobs SET target_lang='ru' WHERE id='job-1'")

    val native = FakePdfNative().apply { pageExportError = true }
    val failure = runCatching { exporter(native).getOrBuild("job-1") }.exceptionOrNull()
    assertTrue(failure is IllegalStateException)
    assertEquals("synthetic page export failure", failure?.message)
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.name.endsWith(".pdf") })
  }

  @Test
  fun rejectsInvalidPageAndMergedPdfAndCleansTemporaryFiles() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val invalidPageNative = FakePdfNative().apply { invalidPagePdf = true }
    assertTrue(runCatching { exporter(invalidPageNative).getOrBuild("job-1") }.exceptionOrNull() is IllegalArgumentException)
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.name.endsWith(".incoming") })

    val invalidMergeNative = FakePdfNative().apply { wrongMergedPageCount = true }
    assertTrue(runCatching { exporter(invalidMergeNative).getOrBuild("job-1") }.exceptionOrNull() is IllegalArgumentException)
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.name.endsWith(".incoming") })
    assertEquals(1, invalidMergeNative.mergeCalls)
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.name.endsWith(".pdf") })
  }

  @Test
  fun cancellationAfterPageExportKeepsCheckpointAndAvoidsMerge() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val native = FakePdfNative()
    var checks = 0
    val failure = runCatching { exporter(native).getOrBuild("job-1") { ++checks == 3 } }.exceptionOrNull()

    assertTrue(failure is InterruptedException)
    assertEquals(0, native.mergeCalls)
    assertEquals("completed", database.page("job-1", 0)!!.getString("status"))
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.name.endsWith(".incoming") })
  }

  @Test
  fun deleteRemovesVerifiedExportAndIsSafeWhenCalledTwice() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val exporter = exporter(FakePdfNative())
    val result = exporter.getOrBuild("job-1")
    assertTrue(result.file.exists())

    exporter.delete("job-1")
    exporter.delete("job-1")

    assertFalse(result.file.exists())
    assertFalse(File(result.file.parentFile, "layout-receipt.json").exists())
    assertEquals(null, database.export(result.exportId))
  }

  @Test
  fun deleteRemovesLegacyOwnedFlatPdfButKeepsUserFiles() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val exporter = exporter(FakePdfNative())
    val published = exporter.getOrBuild("job-1")
    val legacyExport = File(context.filesDir, "exports/${published.exportId}.pdf")
      .apply { parentFile?.mkdirs(); writeText("%PDF-legacy") }
    val userFile = File(context.filesDir, "outside-user-export.pdf").apply { writeText("user-owned bytes") }
    val userBytes = userFile.readBytes()
    database.saveExport(published.exportId, legacyExport.absolutePath, "Legacy export.pdf")

    exporter.delete("job-1")

    assertFalse(legacyExport.exists())
    assertTrue(userBytes.contentEquals(userFile.readBytes()))
  }

  @Test
  fun incompatiblePageAndMergeContractsAreRejectedBeforeExportPublication() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val pageFailures = listOf(
      FakePdfNative().apply { invalidPageLayoutVersion = true },
      FakePdfNative().apply { invalidPageSourceIndex = true },
      FakePdfNative().apply { omitPlacements = true },
    )
    pageFailures.forEach { native ->
      assertTrue(runCatching { exporter(native).getOrBuild("job-1") }.isFailure)
      assertEquals(null, database.export("job-1"))
    }

    val mergeFailures = listOf(
      FakePdfNative().apply { invalidMergeLayoutVersion = true },
      FakePdfNative().apply { omitSourceRanges = true },
      FakePdfNative().apply { omitOutputPages = true },
    )
    mergeFailures.forEach { native ->
      assertTrue(runCatching { exporter(native).getOrBuild("job-1") }.isFailure)
      assertEquals(null, database.export("job-1"))
    }
  }

  @Test
  fun validPdfMutationAfterAtomicMoveFailsReceiptDigestCheck() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val exporter = JobPdfExporter(context, database, FakePdfNative(), { font }, { font }, moveFile = { from, to, atomic ->
      movePdfFile(from, to, atomic)
      if (atomic) to.writeText("%PDF-1.7\nchanged after verified merge")
    })

    val failure = runCatching { exporter.getOrBuild("job-1") }.exceptionOrNull()

    assertTrue(failure is IllegalArgumentException)
    assertTrue(failure?.message.orEmpty().contains("digest changed"))
    assertEquals(null, database.export("job-1"))
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.isDirectory })
  }

  @Test
  fun deletingJobCleansPrivateGenerationsButPreservesExternalUserPdfAndUnrelatedFiles() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val pdfExporter = exporter(FakePdfNative())
    val published = pdfExporter.getOrBuild("job-1")
    val staleGeneration = File(context.filesDir, "exports/${published.exportId}-orphan").apply { mkdirs() }
    File(staleGeneration, "translated.pdf").writeText("%PDF-1.7\norphan")
    File(staleGeneration, "layout-receipt.json").writeText("orphan receipt")
    val incompleteGeneration = File(context.filesDir, "exports/${published.exportId}-incomplete").apply { mkdirs() }
    File(incompleteGeneration, "layout-receipt.json").writeText("no output")
    val unrelatedGeneration = File(context.filesDir, "exports/job-unrelated-generation").apply { mkdirs() }
    File(unrelatedGeneration, "translated.pdf").writeText("%PDF-1.7\nunrelated")
    val work = File(context.cacheDir, "job-export-${published.exportId.removePrefix("job-")}").apply { mkdirs() }
    File(work, "temporary.page").writeText("temporary")
    val userPdf = File(context.filesDir, "user-export.pdf").apply { writeText("%PDF-user-owned") }
    val userBytes = userPdf.readBytes()
    database.saveExport(published.exportId, userPdf.absolutePath, "User export.pdf")

    pdfExporter.delete("job-1")

    assertTrue("the user-selected external PDF must remain untouched", userBytes.contentEquals(userPdf.readBytes()))
    assertFalse(published.generationDirectory.exists())
    assertFalse(staleGeneration.exists())
    assertTrue("a same-prefix folder without a translated PDF is retained", incompleteGeneration.exists())
    assertTrue("another job's private generation is retained", unrelatedGeneration.exists())
    assertFalse(work.exists())
    assertEquals(null, database.export(published.exportId))
  }

  @Test
  fun missingPdfCheckpointIsRejectedBeforeBundledFontInstallation() {
    val exporter = JobPdfExporter(context, database)

    val failure = runCatching { exporter.getOrBuild("job-1") }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.message.orEmpty().contains("no saved translation checkpoint"))
    assertFalse(File(context.cacheDir, "NotoSansCondensed-Regular.ttf").isFile)
  }

  @Test
  fun missingChinesePdfCheckpointIsRejectedBeforeLargeCjkFontInstallation() {
    database.writableDatabase.execSQL("UPDATE jobs SET target_lang='zh-Hans' WHERE id='job-1'")
    val exporter = JobPdfExporter(context, database)

    val failure = runCatching { exporter.getOrBuild("job-1") }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.message.orEmpty().contains("no saved translation checkpoint"))
    assertFalse(File(context.cacheDir, "NotoSansSC-Regular.ttf").isFile)
  }

  @Test
  fun chineseTranslationPassesInstalledCjkFontAndKeepsFullOutputVerified() {
    database.writableDatabase.execSQL("UPDATE jobs SET target_lang='zh-Hans' WHERE id='job-1'")
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "一行").put("status", "translated"))
    val native = FakePdfNative()
    val result = exporter(native).getOrBuild("job-1")

    assertTrue(native.lastCjkFontPath.isNotBlank())
    assertTrue(File(native.lastCjkFontPath).isFile)
    assertTrue(result.displayName.endsWith("-zh-Hans.pdf"))
  }

  @Test
  fun cachedExportWithRenamedDocumentUpdatesDisplayNameWithoutRebuilding() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val native = FakePdfNative()
    val exporter = exporter(native)
    val first = exporter.getOrBuild("job-1")
    database.writableDatabase.execSQL("UPDATE documents SET name='new name.pdf' WHERE id='doc-1'")

    val renamed = exporter.getOrBuild("job-1")

    assertEquals(first.exportId, renamed.exportId)
    assertEquals("new name-en-ru.pdf", renamed.displayName)
    assertEquals("new name-en-ru.pdf", database.export(first.exportId)?.second)
    assertEquals(1, native.mergeCalls)
  }

  @Test
  fun nonpersistentCachedLookupReturnsCurrentNameWithoutMutatingExportMetadata() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated"))
    val native = FakePdfNative()
    val pdfExporter = exporter(native)
    val original = pdfExporter.getOrBuild("job-1")
    val storedName = database.export(original.exportId)!!.second
    val pageExportCalls = native.pageExportCalls
    database.writableDatabase.execSQL("UPDATE documents SET name='renamed source.pdf' WHERE id='doc-1'")

    val result = pdfExporter.getOrBuild("job-1", persistExport = false)

    assertEquals("renamed source-en-ru.pdf", result.displayName)
    assertEquals(storedName, database.export(original.exportId)?.second)
    assertEquals(pageExportCalls, native.pageExportCalls)
  }

  @Test
  fun staleCachedPdfIsRebuiltAndExistingWorkFolderIsCleaned() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val exporter = exporter(FakePdfNative())
    val first = exporter.getOrBuild("job-1")
    first.file.writeText("not a verified PDF")
    val work = File(context.cacheDir, "job-export-${first.exportId.removePrefix("job-")}")
      .apply { mkdirs(); File(this, "stale.tmp").writeText("stale") }

    val rebuilt = exporter.getOrBuild("job-1")

    assertTrue(rebuilt.file.readText().startsWith("%PDF-"))
    assertFalse(File(work, "stale.tmp").exists())
    assertFalse(work.exists())
  }

  @Test
  fun missingCachedFileIsRebuiltAndMissingSourceIsReportedBeforeWorkStarts() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val native = FakePdfNative()
    val exporter = exporter(native)
    val first = exporter.getOrBuild("job-1")
    first.file.delete()

    val rebuilt = exporter.getOrBuild("job-1")

    assertTrue(rebuilt.file.isFile)
    assertEquals(2, native.mergeCalls)
    rebuilt.file.delete()
    val missingSourceExporter = JobPdfExporter(context, database, native, { font }, { font },
      sourcePath = { null })
    assertTrue(runCatching { missingSourceExporter.getOrBuild("job-1") }.exceptionOrNull() is IllegalStateException)
  }

  @Test
  fun failedTemporaryDirectoryCreationStopsBeforeExport() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    var attemptedDirectory: File? = null
    val native = FakePdfNative()
    val exporter = JobPdfExporter(context, database, native, { font }, { font },
      createWorkDirectory = { directory -> attemptedDirectory = directory; false })

    val failure = runCatching { exporter.getOrBuild("job-1") }.exceptionOrNull()

    assertTrue(failure is IllegalArgumentException)
    assertTrue(failure?.message.orEmpty().contains("temporary PDF export directory"))
    assertTrue(attemptedDirectory != null)
    assertEquals(0, native.pageExportCalls)
    assertEquals(0, native.mergeCalls)
  }

  @Test
  fun finalPdfMoveFallsBackWhenAtomicReplacementIsUnsupported() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val attempts = mutableListOf<Boolean>()
    val native = FakePdfNative()
    val exporter = JobPdfExporter(context, database, native, { font }, { font }, moveFile = { from, to, atomic ->
      attempts += atomic
      if (atomic) throw AtomicMoveNotSupportedException(from.path, to.path, "test filesystem lacks atomic replacement")
      Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    })

    val result = exporter.getOrBuild("job-1")

    assertTrue(result.file.readText().startsWith("%PDF-"))
    assertEquals(listOf(true, false), attempts)
    assertEquals(result.file.absolutePath, database.export(result.exportId)?.first)
  }

  @Test
  fun defaultNonAtomicMoveHelperReplacesExistingPdf() {
    val directory = File(context.cacheDir, "pdf-non-atomic-move-${System.nanoTime()}").apply { mkdirs() }
    val sourceFile = File(directory, "source.pdf").apply { writeText("new pdf") }
    val targetFile = File(directory, "target.pdf").apply { writeText("old pdf") }
    movePdfFile(sourceFile, targetFile, atomic = false)

    assertEquals("new pdf", targetFile.readText())
    assertFalse(sourceFile.exists())
    directory.deleteRecursively()
  }

  @Test
  fun deletingAJobWithoutPriorExportCreatesAndReleasesItsLock() {
    val exporter = exporter(FakePdfNative())

    exporter.delete("job-never-exported")

    assertEquals(null, database.export("job-never-exported"))
  }

  @Test
  fun extraPartialCheckpointOutsideDocumentRangeDoesNotMarkExportPartial() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    database.savePage("job-1", 4, "partial", "[]", 612.0, 792.0, "outside range", 0)
    database.savePage("job-1", -1, "partial", "[]", 612.0, 792.0, "before range", 0)

    val result = exporter(FakePdfNative()).getOrBuild("job-1")

    assertFalse(result.partial)
  }

  @Test
  fun interruptedThreadStopsExportAndPreservesThePageCheckpoint() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    Thread.currentThread().interrupt()
    val failure = try { runCatching { exporter(FakePdfNative()).getOrBuild("job-1") }.exceptionOrNull() }
    finally { Thread.interrupted() }

    assertTrue(failure is InterruptedException)
    assertEquals("completed", database.page("job-1", 0)!!.getString("status"))
  }

  @Test
  fun blankDocumentNameUsesNeutralFallbackAndInvalidCharactersAreSanitized() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    database.writableDatabase.execSQL("UPDATE documents SET name='   .pdf' WHERE id='doc-1'")

    val first = exporter(FakePdfNative()).getOrBuild("job-1")
    assertEquals("translated-en-ru.pdf", first.displayName)

    database.deleteExport(first.exportId)
    database.writableDatabase.execSQL("UPDATE documents SET name='draft<draft>.pdf' WHERE id='doc-1'")
    val second = exporter(FakePdfNative()).getOrBuild("job-1")
    assertEquals("draft_draft_-en-ru.pdf", second.displayName)
  }

  @Test
  fun nativeInspectionErrorsAndExceptionsRejectUnverifiedPages() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val errorResponse = FakePdfNative().apply { invalidInspection = true }
    assertTrue(runCatching { exporter(errorResponse).getOrBuild("job-1") }.exceptionOrNull() is IllegalArgumentException)
    val thrownInspection = FakePdfNative().apply { throwOnInspection = true }
    assertTrue(runCatching { exporter(thrownInspection).getOrBuild("job-1") }.exceptionOrNull() is IllegalArgumentException)
    val wrongPageCount = FakePdfNative().apply { inspectedPageCountOffset = 1 }
    assertTrue(runCatching { exporter(wrongPageCount).getOrBuild("job-1") }.exceptionOrNull() is IllegalArgumentException)
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.name.endsWith(".pdf") })
  }

  @Test
  fun missingEmptyAndShortNativePageOutputsAreRejected() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val modes = listOf(
      FakePdfNative().apply { omitPageOutput = true },
      FakePdfNative().apply { emptyPageOutput = true },
      FakePdfNative().apply { shortPageOutput = true },
    )

    for (native in modes) {
      assertTrue(runCatching { exporter(native).getOrBuild("job-1") }.exceptionOrNull() is IllegalArgumentException)
      assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.name.endsWith(".pdf") })
    }
  }

  @Test
  fun shortHeaderReadAfterFileLengthCheckIsRejected() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val exporter = JobPdfExporter(context, database, FakePdfNative(), { font }, { font },
      openPdfInputStream = { file ->
        val input = FileInputStream(file)
        object : InputStream() {
          override fun read(): Int = input.read()
          override fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, minOf(length, 2))
          override fun close() = input.close()
        }
      })

    val failure = runCatching { exporter.getOrBuild("job-1") }.exceptionOrNull()

    assertTrue("a short PDF header read must not be trusted", failure is IllegalArgumentException)
    assertTrue(failure?.message.orEmpty().contains("valid translated page output"))
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.name.endsWith(".pdf") })
  }

  @Test
  fun pdfHeaderStreamCloseFailurePropagatesAndTemporaryOutputIsCleaned() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val exporter = JobPdfExporter(context, database, FakePdfNative(), { font }, { font },
      openPdfInputStream = {
        object : ByteArrayInputStream("%PDF-".toByteArray()) {
          override fun close() { throw java.io.IOException("PDF header stream close failed") }
        }
      })

    val failure = runCatching { exporter.getOrBuild("job-1") }.exceptionOrNull()

    assertEquals("PDF header stream close failed", failure?.message)
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.name.endsWith(".pdf") || it.name.endsWith(".incoming") })
  }

  @Test
  fun pdfHeaderReadFailureKeepsSuppressedCloseFailureAndCleansTemporaryOutput() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val readFailure = java.io.IOException("PDF header read failed")
    val exporter = JobPdfExporter(context, database, FakePdfNative(), { font }, { font },
      openPdfInputStream = {
        object : InputStream() {
          override fun read(): Int = throw readFailure
          override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw readFailure
          override fun close() { throw java.io.IOException("PDF header close failed") }
        }
      })

    val failure = runCatching { exporter.getOrBuild("job-1") }.exceptionOrNull()

    assertTrue(failure === readFailure)
    assertEquals("PDF header close failed", failure?.suppressed?.singleOrNull()?.message)
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.name.endsWith(".pdf") || it.name.endsWith(".incoming") })
  }

  @Test
  fun emptyIssuesAndNativePartialFlagProducePartialPageWithoutLayoutIssue() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val native = FakePdfNative().apply { omitIssues = true; partialPageOutput = true }

    val result = exporter(native).getOrBuild("job-1")

    assertTrue(result.partial)
    assertEquals("partial", database.page("job-1", 0)!!.getString("status"))
    assertEquals(1, native.mergeCalls)
  }

  @Test
  fun issueListMarksPagePartialEvenWhenNativePartialFlagIsFalse() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val native = FakePdfNative().apply {
      issues = JSONArray().put(JSONObject().put("blockId", "block-1").put("reason", "fallback").put("message", "review"))
      partialFlagOverride = false
    }

    val result = exporter(native).getOrBuild("job-1")

    assertTrue(result.partial)
    assertEquals("partial", database.page("job-1", 0)!!.getString("status"))
  }

  @Test
  fun concurrentPartialCheckpointBeforeFinalRecheckMarksExportPartial() {
    val block = JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated")
    savePage("completed", block)
    val native = FakePdfNative().apply {
      onMerge = {
        database.savePage("job-1", 0, "partial", JSONArray().put(block).toString(), 612.0, 792.0, "new review", 1)
      }
    }

    val result = exporter(native).getOrBuild("job-1")

    assertTrue("the post-merge checkpoint recheck should notice a concurrent partial row", result.partial)
    assertEquals("partial", database.page("job-1", 0)!!.getString("status"))
  }

  @Test
  fun mergedInspectionAndFinalFileCorruptionPreventExportRegistration() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val invalidMerged = FakePdfNative().apply { invalidMergedInspection = true }
    assertTrue(runCatching { exporter(invalidMerged).getOrBuild("job-1") }.exceptionOrNull() is IllegalArgumentException)
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.name.endsWith(".pdf") })

    val corruptingExporter = JobPdfExporter(context, database, FakePdfNative(), { font }, { font }, moveFile = { from, to, atomic ->
      movePdfFile(from, to, atomic)
      if (atomic) to.writeText("damaged after replacement")
    })
    val failure = runCatching { corruptingExporter.getOrBuild("job-1") }.exceptionOrNull()
    assertTrue(failure is IllegalArgumentException)
    assertTrue(failure?.message.orEmpty().contains("final integrity"))
    assertEquals(null, database.export("job-1"))
  }

  @Test
  fun secondUnstableLayoutFindingFailsWithoutPublishingTheMergedFile() {
    val first = JSONObject().put("id", "block-1").put("sourceText", "First")
      .put("translatedText", "Первый").put("status", "translated")
    val second = JSONObject().put("id", "block-2").put("sourceText", "Second")
      .put("translatedText", "Второй").put("status", "translated")
    savePage("completed", first)
    database.savePage("job-1", 0, "completed", JSONArray().put(first).put(second).toString(), 612.0, 792.0, null, 2)
    val native = FakePdfNative().apply {
      issuesPerExport = listOf(
        JSONArray().put(JSONObject().put("blockId", "block-1").put("reason", "first").put("message", "first")),
        JSONArray().put(JSONObject().put("blockId", "block-2").put("reason", "second").put("message", "second")),
      )
    }

    val failure = runCatching { exporter(native).getOrBuild("job-1") }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.message.orEmpty().contains("did not stabilize"))
    assertEquals(2, native.pageExportCalls)
    assertEquals(0, native.mergeCalls)
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.name.endsWith(".pdf") })
  }

  @Test
  fun rebuildingAStaleExportMetadataPathPreservesTheSeparateUserFile() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated"))
    val native = FakePdfNative()
    val first = exporter(native).getOrBuild("job-1")
    first.file.delete()
    val retained = File(context.filesDir, "user-retained.pdf").apply { writeText("%PDF-user copy") }
    val bytes = retained.readBytes()
    database.saveExport(first.exportId, retained.absolutePath, "User retained.pdf")
    val rebuilt = exporter(native).getOrBuild("job-1")
    assertTrue(rebuilt.file.isFile)
    assertTrue(first.file.absolutePath != rebuilt.file.absolutePath)
    assertTrue(bytes.contentEquals(retained.readBytes()))
    assertEquals(rebuilt.file.absolutePath, database.export(rebuilt.exportId)?.first)
  }

  @Test
  fun failedRebuildPreservesPriorPrivateGenerationAndSeparateUserExport() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated"))
    val native = FakePdfNative()
    val initial = exporter(native).getOrBuild("job-1")
    val retained = File(context.filesDir, "separate-user-export.pdf").apply { writeText("%PDF-user copy") }
    val retainedBytes = retained.readBytes()
    database.saveExport(initial.exportId, retained.absolutePath, "User export.pdf")
    val metadata = database.export(initial.exportId)
    native.mergeError = true

    assertTrue(runCatching { exporter(native).getOrBuild("job-1") }.isFailure)

    assertTrue("an older private generation is retained until replacement is successfully published", initial.file.exists())
    assertTrue(retainedBytes.contentEquals(retained.readBytes()))
    assertEquals(metadata, database.export(initial.exportId))
    assertFalse(File(context.cacheDir, "job-export-${initial.exportId.removePrefix("job-")}").exists())
  }

  @Test
  fun aFailedRebuildKeepsThePreviouslyPublishedOutputAndItsMetadata() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated"))
    val native = FakePdfNative()
    val published = exporter(native).getOrBuild("job-1")
    assertEquals(1, native.pageExportCalls)
    assertEquals(1, native.mergeCalls)
    val damagedBytes = published.file.readBytes().apply { this[0] = '!'.code.toByte() }
    published.file.writeBytes(damagedBytes)
    val metadata = database.export(published.exportId)
    native.mergeError = true

    val failure = runCatching { exporter(native).getOrBuild("job-1") }.exceptionOrNull()

    assertEquals("synthetic merge failure", failure?.message)
    assertEquals("the invalid published header must force a real page rebuild", 2, native.pageExportCalls)
    assertEquals("the rebuild must reach merge before failing", 2, native.mergeCalls)
    assertEquals("a failed rebuild must not count as another successful publication", 1, native.successfulMergeCalls)
    assertTrue(damagedBytes.contentEquals(published.file.readBytes()))
    assertEquals(metadata, database.export(published.exportId))
    assertTrue(File(context.cacheDir, "job-export-${published.exportId.removePrefix("job-")}").exists().not())
  }

  @Test
  fun v3MergeReceivesFullSourceAndImmutableOneToOneCheckpointSnapshots() {
    val secondJob = "job-two-source-pages"
    database.addJob(secondJob, JSONObject().put("documentId", "doc-1").put("sourceLanguage", "en")
      .put("targetLanguage", "ru").put("modelId", ModelCatalog.RECOMMENDED_ID), 2)
    val first = JSONObject().put("id", "block-1").put("sourceText", "Long section")
      .put("translatedText", "Переведённый раздел").put("status", "translated")
    val second = JSONObject().put("id", "block-2").put("sourceText", "Next")
      .put("translatedText", "Далее").put("status", "translated")
    database.savePage(secondJob, 0, "completed", JSONArray().put(first).toString(), 612.0, 792.0, null, 1)
    database.savePage(secondJob, 1, "completed", JSONArray().put(second).toString(), 612.0, 792.0, null, 1)
    val native = FakePdfNative()

    val finalized = JobPdfExporter(context, database, native, { font }, { font }).getOrBuild(secondJob)

    assertEquals(2, finalized.sourcePageCount)
    assertEquals(2, finalized.pageCount)
    assertEquals(3, finalized.layoutVersion)
    assertEquals(listOf(0, 1), finalized.receipt.pages.map { it.sourcePageIndex })
    assertEquals(listOf(0, 0), finalized.receipt.pages.map { it.sourceOutputPageIndex })
    assertEquals(File(database.jobDocumentPath(secondJob)!!).absolutePath, native.lastMergeRequest!!.getString("sourcePath"))
    assertEquals(font.absolutePath, native.lastMergeRequest!!.getString("fontPath"))
    assertEquals(listOf(1, 1), (0 until 2).map { native.lastMergeRequest!!.getJSONArray("shards").getJSONObject(it).getInt("expectedPageCount") })
    assertEquals(listOf(0, 1), native.lastSnapshotPages.map { it.getInt("sourcePageIndex") })
    assertEquals("Переведённый раздел", native.lastSnapshotPages[0].getJSONArray("blocks").getJSONObject(0).getString("translatedText"))
    assertTrue(native.lastExportRequests.all { it.getInt("layoutVersion") == 3 && it.getString("mode") == "preserve-source" })
    assertEquals(2, JSONObject(File(finalized.file.parentFile, "layout-receipt.json").readText()).getInt("outputPageCount"))
    assertEquals(finalized.file.absolutePath, database.export(finalized.exportId)?.first)
  }

  @Test
  fun nativeOverflowPageIsRejectedBeforeMergeAndPublication() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated"))
    val native = FakePdfNative().apply { pageCountOverride = 2 }

    val failure = runCatching { exporter(native).getOrBuild("job-1") }.exceptionOrNull()

    assertTrue(failure is IllegalArgumentException)
    assertEquals(0, native.mergeCalls)
    assertEquals(null, database.export("job-1"))
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.isDirectory })
  }

  @Test
  fun changedCheckpointInvalidatesReceiptAndPublishesNewImmutableGeneration() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated"))
    val native = FakePdfNative()
    val pdfExporter = exporter(native)
    val first = pdfExporter.getOrBuild("job-1")
    val firstPath = first.file.absolutePath
    val firstDigest = first.receipt.checkpointDigest
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан управления").put("status", "translated"))

    val rebuilt = pdfExporter.getOrBuild("job-1")

    assertTrue(rebuilt.file.isFile)
    assertTrue(firstPath != rebuilt.file.absolutePath)
    assertTrue(firstDigest != rebuilt.receipt.checkpointDigest)
    assertFalse("the previous private generation is removed only after successful publication", File(firstPath).exists())
    assertEquals(2, native.pageExportCalls)
    assertEquals(2, native.mergeCalls)
  }

  @Test
  fun changedSourceDigestInvalidatesAnOtherwiseMatchingLayoutReceipt() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated"))
    val native = FakePdfNative()
    val pdfExporter = exporter(native)
    val first = pdfExporter.getOrBuild("job-1")
    source.appendText(" changed source")

    val rebuilt = pdfExporter.getOrBuild("job-1")

    assertTrue(first.file.absolutePath != rebuilt.file.absolutePath)
    assertEquals(2, native.pageExportCalls)
    assertEquals(2, native.mergeCalls)
    assertTrue(rebuilt.receipt.sourceSha256 != first.receipt.sourceSha256)
  }

  @Test
  fun malformedMergeMapCannotPublishAndCleansItsStagingGeneration() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "A line")
      .put("translatedText", "Строка").put("status", "translated"))
    val native = FakePdfNative().apply { malformedMergeMap = true }

    val failure = runCatching { exporter(native).getOrBuild("job-1") }.exceptionOrNull()

    assertTrue(failure is IllegalArgumentException)
    assertEquals(null, database.export("job-1"))
    assertTrue(File(context.filesDir, "exports").listFiles().orEmpty().none { it.isDirectory })
  }

  @Test
  fun failedStaleRebuildRetainsPreviousPublishedPdfAndReceipt() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated"))
    val native = FakePdfNative()
    val pdfExporter = exporter(native)
    val first = pdfExporter.getOrBuild("job-1")
    val oldPdf = first.file.readBytes()
    val oldManifest = File(first.file.parentFile, "layout-receipt.json").readBytes()
    val savedMetadata = database.export(first.exportId)
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан управления").put("status", "translated"))
    native.mergeError = true

    assertTrue(runCatching { pdfExporter.getOrBuild("job-1") }.isFailure)

    assertEquals(savedMetadata, database.export(first.exportId))
    assertTrue(oldPdf.contentEquals(first.file.readBytes()))
    assertTrue(oldManifest.contentEquals(File(first.file.parentFile, "layout-receipt.json").readBytes()))
    assertEquals(1, File(context.filesDir, "exports").listFiles().orEmpty().count { it.isDirectory })
  }

  @Test
  fun cancelAfterReceiptVerificationPreservesPreviouslyPublishedGeneration() {
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан").put("status", "translated"))
    val native = FakePdfNative()
    val pdfExporter = exporter(native)
    val first = pdfExporter.getOrBuild("job-1")
    val metadata = database.export(first.exportId)
    val bytes = first.file.readBytes()
    savePage("completed", JSONObject().put("id", "block-1").put("sourceText", "Valve")
      .put("translatedText", "Клапан управления").put("status", "translated"))
    var checks = 0

    val failure = runCatching { pdfExporter.getOrBuild("job-1") { ++checks == 5 } }.exceptionOrNull()

    assertTrue(failure is InterruptedException)
    assertEquals(metadata, database.export(first.exportId))
    assertTrue(bytes.contentEquals(first.file.readBytes()))
    assertEquals(1, File(context.filesDir, "exports").listFiles().orEmpty().count { it.isDirectory })
  }

  private fun exporter(native: FakePdfNative) = JobPdfExporter(context, database, native,
    fontProvider = { font }, cjkFontProvider = { font })

  private fun savePage(status: String, block: JSONObject) {
    database.savePage("job-1", 0, status, JSONArray().put(block).toString(), 612.0, 792.0, null, 1)
  }

  private class FakePdfNative : JobPdfNativeOperations {
    var issues = JSONArray()
    var mergeError = false
    var pageExportError = false
    var invalidPagePdf = false
    var wrongMergedPageCount = false
    var invalidInspection = false
    var throwOnInspection = false
    var inspectedPageCountOffset = 0
    var issuesPerExport: List<JSONArray> = emptyList()
    var omitPageOutput = false
    var emptyPageOutput = false
    var shortPageOutput = false
    var omitIssues = false
    var partialPageOutput = false
    var partialFlagOverride: Boolean? = null
    var invalidMergedInspection = false
    var mergeCalls = 0
    var successfulMergeCalls = 0
    var pageExportCalls = 0
    var pageCountOverride: Int? = null
    var malformedMergeMap = false
    var duplicateSourceRange = false
    var invalidPageLayoutVersion = false
    var invalidPageSourceIndex = false
    var omitPlacements = false
    var invalidMergeLayoutVersion = false
    var omitSourceRanges = false
    var omitOutputPages = false
    var lastExportRequests = mutableListOf<JSONObject>()
    var lastMergeRequest: JSONObject? = null
    var lastCjkFontPath = ""
    var lastSnapshotPages = mutableListOf<JSONObject>()
    var onMerge: (() -> Unit)? = null
    private var finalPageCount = 0
    private val pagePaths = mutableMapOf<String, Int>()

    override fun inspect(path: String): String {
      if (throwOnInspection) throw IllegalStateException("synthetic inspect failure")
      if (invalidInspection) return JSONObject().put("error", "synthetic inspect failure").toString()
      if (invalidMergedInspection && path.endsWith(".incoming")) return JSONObject().put("error", "invalid merged output").toString()
      val file = File(path)
      if (!file.isFile || !file.readText().startsWith("%PDF-")) return JSONObject().put("error", "invalid").toString()
      return JSONObject().put("pageCount", (pagePaths[path] ?: finalPageCount) + inspectedPageCountOffset).toString()
    }

    override fun exportPage(source: String, pageIndex: Int, blocksJson: String, destination: String, fontPath: String, cjkFontPath: String): String {
      pageExportCalls++
      lastCjkFontPath = cjkFontPath
      val request = JSONObject(blocksJson)
      lastExportRequests += JSONObject(request.toString())
      if (pageExportError) return JSONObject().put("error", "synthetic page export failure").toString()
      if (omitPageOutput) return JSONObject().toString()
      if (emptyPageOutput) {
        File(destination).writeText("")
        return JSONObject().toString()
      }
      if (shortPageOutput) {
        File(destination).writeText("%PD")
        return JSONObject().toString()
      }
      if (invalidPagePdf) {
        File(destination).writeText("not a PDF")
        return JSONObject().toString()
      }
      File(destination).writeText("%PDF-1.7\npage")
      val shardPageCount = pageCountOverride ?: 1
      pagePaths[destination] = shardPageCount
      val currentIssues = issuesPerExport.getOrNull(pageExportCalls - 1) ?: issues
      val placements = JSONArray()
      val blocks = request.getJSONArray("blocks")
      for (index in 0 until blocks.length()) {
        val block = blocks.getJSONObject(index)
        val target = if (block.isNull("translatedText")) block.optString("translation") else block.optString("translatedText")
        if (target.isNotBlank() && target != "null") {
          placements.put(JSONObject().put("blockId", block.getString("id"))
            .put("outputPageIndex", 0).put("role", "paragraph"))
        }
      }
      val result = JSONObject().put("partial", partialFlagOverride ?: (partialPageOutput || currentIssues.length() > 0))
        .put("layoutVersion", if (invalidPageLayoutVersion) 1 else 3).put("mode", "preserve-source")
        .put("path", destination).put("sourcePageIndex", if (invalidPageSourceIndex) pageIndex + 1 else pageIndex).put("pageCount", shardPageCount)
        .put("partialBlocks", currentIssues.length())
      if (!omitPlacements) result.put("placements", placements)
      if (!omitIssues) result.put("issues", currentIssues)
      return result.toString()
    }

    override fun mergePdf(pagesJson: String, destination: String): String {
      mergeCalls++
      if (mergeError) return JSONObject().put("error", "synthetic merge failure").toString()
      val request = JSONObject(pagesJson)
      lastMergeRequest = JSONObject(request.toString())
      check(request.getInt("layoutVersion") == 3 && request.getString("mode") == "preserve-source")
      check(File(request.getString("sourcePath")).isFile)
      check(File(request.getString("fontPath")).isFile)
      check(request.getString("cjkFontPath").isEmpty() || File(request.getString("cjkFontPath")).isFile)
      val shards = request.getJSONArray("shards")
      lastSnapshotPages = mutableListOf()
      for (index in 0 until shards.length()) {
        val shard = shards.getJSONObject(index)
        check(shard.getInt("expectedPageCount") == 1)
        val snapshot = JSONObject(File(shard.getString("blocksPath")).readText())
        check(snapshot.getInt("sourcePageIndex") == shard.getInt("sourcePageIndex"))
        lastSnapshotPages += snapshot
      }
      finalPageCount = (0 until shards.length()).sumOf { shards.getJSONObject(it).getInt("expectedPageCount") }
      if (wrongMergedPageCount) finalPageCount++
      File(destination).writeText("%PDF-1.7\nmerged")
      successfulMergeCalls++
      onMerge?.invoke()
      val ranges = JSONArray()
      val pages = JSONArray()
      for (index in 0 until shards.length()) {
        val shard = shards.getJSONObject(index)
        val sourceIndex = shard.getInt("sourcePageIndex")
        ranges.put(JSONObject().put("sourcePageIndex", if (duplicateSourceRange && index > 0) 0 else sourceIndex)
          .put("firstOutputPage", sourceIndex).put("outputPageCount", 1))
        pages.put(JSONObject().put("outputPageIndex", sourceIndex)
          .put("sourcePageIndex", if (duplicateSourceRange && index > 0) 0 else sourceIndex)
          .put("sourceOutputPageIndex", if (malformedMergeMap && sourceIndex == 0) 1 else 0))
      }
      val response = JSONObject().put("layoutVersion", if (invalidMergeLayoutVersion) 1 else 3)
        .put("mode", "preserve-source").put("pageCount", finalPageCount)
      if (!omitSourceRanges) response.put("sourcePageRanges", ranges)
      if (!omitOutputPages) response.put("pages", pages)
      return response.toString()
    }
  }
}

internal object PdfCoreShadowState {
  var inspectCalls = 0
  var exportCalls = 0
  var mergeCalls = 0
  var lastCjkFontPath = ""
  var lastMergeCjkFontPath = ""

  fun reset() {
    inspectCalls = 0; exportCalls = 0; mergeCalls = 0
    lastCjkFontPath = ""; lastMergeCjkFontPath = ""
  }
}

@Implements(NativeCore::class, callNativeMethodsByDefault = false)
internal class ShadowNativePdfCore {
  @Implementation fun __constructor__() = Unit

  @Implementation fun inspect(path: String): String {
    PdfCoreShadowState.inspectCalls++
    return inspectCallback?.invoke(path) ?: inspectionResponse ?: JSONObject().put("pageCount", 1).toString()
  }

  @Implementation fun inspectDocx(path: String): String {
    inspectDocxCallback?.let { return it(path) }
    check(File(path).readBytes().take(4) == listOf<Byte>(80,75,3,4))
    return """{"format":"docx","pageCount":2,"segmentCount":2,"warnings":["Unsupported element preserved"]}"""
  }

  @Implementation fun exportDocx(source: String, segmentsJson: String, destination: String): String {
    exportDocxCallback?.let { return it(source, segmentsJson, destination) }
    check(File(source).readText() == "original")
    check(JSONArray(segmentsJson).length() == 2)
    File(destination).writeText("translated Word")
    return """{"path":"$destination","partial":false,"warnings":[]}"""
  }

  @Implementation fun exportPage(
    source: String, index: Int, blocksJson: String, destination: String, fontPath: String, cjkFontPath: String,
  ): String {
    PdfCoreShadowState.exportCalls++
    PdfCoreShadowState.lastCjkFontPath = cjkFontPath
    exportCallback?.let { return it(index, blocksJson, destination) }
    File(destination).writeText("%PDF-1.7\nshadow page")
    val request = JSONObject(blocksJson)
    val blocks = request.getJSONArray("blocks")
    val placements = JSONArray()
    for (blockIndex in 0 until blocks.length()) {
      val block = blocks.getJSONObject(blockIndex)
      val translated = if (block.isNull("translatedText")) block.optString("translation") else block.optString("translatedText")
      if (translated.isNotBlank() && translated != "null") {
        placements.put(JSONObject().put("blockId", block.getString("id")).put("outputPageIndex", 0))
      }
    }
    return JSONObject().put("layoutVersion", 3).put("mode", "preserve-source").put("path", destination)
      .put("sourcePageIndex", index).put("pageCount", 1)
      .put("partial", false).put("issues", JSONArray()).put("placements", placements).toString()
  }

  @Implementation fun mergePdf(pagesJson: String, destination: String): String {
    PdfCoreShadowState.mergeCalls++
    PdfCoreShadowState.lastMergeCjkFontPath = JSONObject(pagesJson).optString("cjkFontPath")
    mergeCallback?.let { return it(pagesJson, destination) }
    val shards = JSONObject(pagesJson).getJSONArray("shards")
    val request = JSONObject(pagesJson)
    check(request.getInt("layoutVersion") == 3 && request.getString("mode") == "preserve-source")
    check(File(request.getString("sourcePath")).isFile)
    check(File(request.getString("fontPath")).isFile)
    var pageCount = 0
    val ranges = JSONArray()
    val pages = JSONArray()
    for (shardIndex in 0 until shards.length()) {
      val shard = shards.getJSONObject(shardIndex)
      val sourceIndex = shard.getInt("sourcePageIndex")
      check(shard.getInt("expectedPageCount") == 1)
      val snapshot = JSONObject(File(shard.getString("blocksPath")).readText())
      check(snapshot.getInt("sourcePageIndex") == sourceIndex && snapshot.getJSONArray("blocks").length() >= 0)
      ranges.put(JSONObject().put("sourcePageIndex", sourceIndex).put("firstOutputPage", sourceIndex)
        .put("outputPageCount", 1))
      pages.put(JSONObject().put("outputPageIndex", sourceIndex)
        .put("sourcePageIndex", sourceIndex).put("sourceOutputPageIndex", 0))
      pageCount++
    }
    File(destination).writeText("%PDF-1.7\nshadow merged")
    return JSONObject().put("layoutVersion", 3).put("mode", "preserve-source").put("pageCount", pageCount)
      .put("sourcePageRanges", ranges).put("pages", pages).toString()
  }

  @Implementation fun render(path: String, index: Int, width: Int): Bitmap =
    renderCallback?.invoke() ?: Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }

  @Implementation fun setWeightRepackingAllowed(allowed: Boolean) = Unit
  @Implementation fun loadModel(path: String, context: Int, threads: Int, profile: String): String =
    loadCallback?.invoke() ?: "{}"
  @Implementation fun loadModelBatch(path: String, totalContext: Int, threads: Int, profile: String, maxSequences: Int): String {
    batchLoadCalls++
    return batchLoadCallback?.invoke(path, totalContext, threads, profile, maxSequences) ?: loadCallback?.invoke() ?: "{}"
  }
  @Implementation fun readPage(path: String, index: Int): String = readCallback?.invoke() ?: "{}"
  @Implementation fun readDocxSegment(path: String, index: Int): String =
    readDocxCallback?.invoke(path, index) ?: error("A Word segment response must be configured by the test.")
  @Implementation fun translate(text: String, glossary: String, context: String, sourceLanguage: String, targetLanguage: String): String =
    translateInputCallback?.invoke(text, glossary, context, sourceLanguage, targetLanguage) ?: translateCallback?.invoke() ?: "{}"
  @Implementation fun translateForLayout(text: String, glossary: String, context: String, sourceLanguage: String, targetLanguage: String, layoutOptions: String): String =
    translateForLayoutInputCallback?.invoke(text, glossary, context, sourceLanguage, targetLanguage, layoutOptions)
      ?: translateInputCallback?.invoke(text, glossary, context, sourceLanguage, targetLanguage)
      ?: translateCallback?.invoke() ?: "{}"
  @Implementation fun translateBatch(
    requestsJson: String,
    glossary: String,
    background: String,
    sourceLanguage: String,
    targetLanguage: String,
    generation: Long,
    callback: BatchCompletion,
  ): String {
    batchTranslateCalls++
    batchTranslateCallback?.let { return it(requestsJson, glossary, background, sourceLanguage, targetLanguage, generation, callback) }
    val requests = JSONArray(requestsJson)
    val committed = JSONArray()
    val pending = JSONArray()
    val errors = JSONArray()
    var stopped = false
    for (index in 0 until requests.length()) {
      val request = requests.getJSONObject(index)
      val id = request.getString("id")
      if (stopped) { pending.put(id); continue }
      val response = try {
        JSONObject(translateCallback?.invoke() ?: "{}")
      } catch (failure: Throwable) {
        JSONObject().put("error", failure.message ?: if (failure is OutOfMemoryError) "Android could not allocate working memory." else "Translation failed.")
      }
      val text = response.optString("text")
      if (response.has("error") || text.isBlank()) {
        errors.put(JSONObject().put("id", id).put("error", response.optString("error", "Translation was not produced.")))
        continue
      }
      val payload = JSONObject(response.toString()).put("id", id).put("generation", generation).put("soloRetry", false)
      when (callback.onCompleted(payload.toString())) {
        1 -> committed.put(id)
        else -> { pending.put(id); stopped = true }
      }
    }
    return JSONObject().put("committedIds", committed).put("pendingIds", pending)
      .put("finalErrors", errors).put("canceled", stopped).toString()
  }
  @Implementation fun releaseModel() { releaseCallback?.invoke() }
  @Implementation fun cancel() { cancelCallback?.invoke() }
  @Implementation fun inferenceProgress(): String =
    inferenceProgressCallback?.invoke() ?: """{"processedTokens":0,"generatedTokens":0}"""

  companion object {
    var inspectionResponse: String? = null
    var inspectCallback: ((String) -> String)? = null
    var inspectDocxCallback: ((String) -> String)? = null
    var exportDocxCallback: ((String, String, String) -> String)? = null
    var readDocxCallback: ((String, Int) -> String)? = null
    var exportCallback: ((Int, String, String) -> String)? = null
    var mergeCallback: ((String, String) -> String)? = null
    var renderCallback: (() -> Bitmap)? = null
    var loadCallback: (() -> String)? = null
    var readCallback: (() -> String)? = null
    var translateCallback: (() -> String)? = null
    var translateInputCallback: ((String, String, String, String, String) -> String)? = null
    var translateForLayoutInputCallback: ((String, String, String, String, String, String) -> String)? = null
    var releaseCallback: (() -> Unit)? = null
    var cancelCallback: (() -> Unit)? = null
    var inferenceProgressCallback: (() -> String)? = null
    var batchLoadCalls = 0
    var batchTranslateCalls = 0
    var batchLoadCallback: ((String, Int, Int, String, Int) -> String)? = null
    var batchTranslateCallback: ((String, String, String, String, String, Long, BatchCompletion) -> String)? = null
    fun resetCallbacks() {
      inspectCallback = null; exportCallback = null; mergeCallback = null; renderCallback = null
      loadCallback = null; readCallback = null; translateCallback = null; translateInputCallback = null
      translateForLayoutInputCallback = null
      releaseCallback = null; cancelCallback = null
      inspectDocxCallback = null; exportDocxCallback = null; readDocxCallback = null
      inferenceProgressCallback = null
      batchLoadCallback = null; batchTranslateCallback = null
      batchLoadCalls = 0; batchTranslateCalls = 0
      PdfCoreShadowState.lastCjkFontPath = ""
      PdfCoreShadowState.lastMergeCjkFontPath = ""
    }
  }
}

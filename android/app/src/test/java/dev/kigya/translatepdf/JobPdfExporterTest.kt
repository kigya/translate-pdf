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
    assertEquals(null, database.export(result.exportId))
  }

  @Test
  fun defaultFontProvidersRunBeforeMissingCheckpointIsReported() {
    val exporter = JobPdfExporter(context, database)

    val failure = runCatching { exporter.getOrBuild("job-1") }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(File(context.cacheDir, "NotoSans-Regular.ttf").isFile)
    assertTrue(File(context.cacheDir, "NotoSans-Regular.ttf").length() > 500_000L)
  }

  @Test
  fun defaultCjkProviderInstallsChineseFontBeforeMissingCheckpointIsReported() {
    database.writableDatabase.execSQL("UPDATE jobs SET target_lang='zh-Hans' WHERE id='job-1'")
    val exporter = JobPdfExporter(context, database)

    val failure = runCatching { exporter.getOrBuild("job-1") }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(File(context.cacheDir, "NotoSansSC-Regular.ttf").isFile)
    assertTrue(File(context.cacheDir, "NotoSansSC-Regular.ttf").length() > 5_000_000L)
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
    var lastCjkFontPath = ""
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
    var onMerge: (() -> Unit)? = null
    private var finalPageCount = 0
    private val pagePaths = mutableSetOf<String>()

    override fun inspect(path: String): String {
      if (throwOnInspection) throw IllegalStateException("synthetic inspect failure")
      if (invalidInspection) return JSONObject().put("error", "synthetic inspect failure").toString()
      if (invalidMergedInspection && path.endsWith(".incoming")) return JSONObject().put("error", "invalid merged output").toString()
      val file = File(path)
      if (!file.isFile || !file.readText().startsWith("%PDF-")) return JSONObject().put("error", "invalid").toString()
      return JSONObject().put("pageCount", (if (path in pagePaths) 1 else finalPageCount) + inspectedPageCountOffset).toString()
    }

    override fun exportPage(source: String, pageIndex: Int, blocksJson: String, destination: String, fontPath: String, cjkFontPath: String): String {
      pageExportCalls++
      lastCjkFontPath = cjkFontPath
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
      pagePaths.add(destination)
      val currentIssues = issuesPerExport.getOrNull(pageExportCalls - 1) ?: issues
      val result = JSONObject().put("partial", partialFlagOverride ?: (partialPageOutput || currentIssues.length() > 0))
        .put("partialBlocks", currentIssues.length())
      if (!omitIssues) result.put("issues", currentIssues)
      return result.toString()
    }

    override fun mergePdf(pagesJson: String, destination: String): String {
      mergeCalls++
      if (mergeError) return JSONObject().put("error", "synthetic merge failure").toString()
      val pages = JSONArray(pagesJson)
      finalPageCount = if (wrongMergedPageCount) pages.length() + 1 else pages.length()
      File(destination).writeText("%PDF-1.7\nmerged")
      successfulMergeCalls++
      onMerge?.invoke()
      return JSONObject().put("pageCount", finalPageCount).toString()
    }
  }
}

private object PdfCoreShadowState {
  var inspectCalls = 0
  var exportCalls = 0
  var mergeCalls = 0

  fun reset() { inspectCalls = 0; exportCalls = 0; mergeCalls = 0 }
}

@Implements(NativeCore::class, callNativeMethodsByDefault = false)
class ShadowNativePdfCore {
  @Implementation fun __constructor__() = Unit

  @Implementation fun inspect(path: String): String {
    PdfCoreShadowState.inspectCalls++
    return inspectCallback?.invoke(path) ?: inspectionResponse ?: JSONObject().put("pageCount", 1).toString()
  }

  @Implementation fun exportPage(
    source: String, index: Int, blocksJson: String, destination: String, fontPath: String, cjkFontPath: String,
  ): String {
    PdfCoreShadowState.exportCalls++
    exportCallback?.let { return it(destination) }
    File(destination).writeText("%PDF-1.7\nshadow page")
    return JSONObject().put("partial", false).put("issues", JSONArray()).toString()
  }

  @Implementation fun mergePdf(pagesJson: String, destination: String): String {
    PdfCoreShadowState.mergeCalls++
    mergeCallback?.let { return it(pagesJson, destination) }
    File(destination).writeText("%PDF-1.7\nshadow merged")
    return JSONObject().put("pageCount", JSONArray(pagesJson).length()).toString()
  }

  @Implementation fun render(path: String, index: Int, width: Int): Bitmap =
    renderCallback?.invoke() ?: Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }

  @Implementation fun loadModel(path: String, context: Int, threads: Int, profile: String): String =
    loadCallback?.invoke() ?: "{}"
  @Implementation fun readPage(path: String, index: Int): String = readCallback?.invoke() ?: "{}"
  @Implementation fun translate(text: String, glossary: String, context: String, sourceLanguage: String, targetLanguage: String): String =
    translateCallback?.invoke() ?: "{}"
  @Implementation fun releaseModel() { releaseCallback?.invoke() }
  @Implementation fun cancel() { cancelCallback?.invoke() }

  companion object {
    var inspectionResponse: String? = null
    var inspectCallback: ((String) -> String)? = null
    var exportCallback: ((String) -> String)? = null
    var mergeCallback: ((String, String) -> String)? = null
    var renderCallback: (() -> Bitmap)? = null
    var loadCallback: (() -> String)? = null
    var readCallback: (() -> String)? = null
    var translateCallback: (() -> String)? = null
    var releaseCallback: (() -> Unit)? = null
    var cancelCallback: (() -> Unit)? = null
    fun resetCallbacks() {
      inspectCallback = null; exportCallback = null; mergeCallback = null; renderCallback = null
      loadCallback = null; readCallback = null; translateCallback = null; releaseCallback = null; cancelCallback = null
    }
  }
}

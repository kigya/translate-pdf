package dev.kigya.translatepdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, opt-in real-document soak. It uses the installed production worker without inference substitutes. */
@RunWith(AndroidJUnit4::class)
class ReferenceDocumentDeviceTest {
  @Test fun translateAllReferencePages() {
    val args = InstrumentationRegistry.getArguments()
    assumeTrue("The private reference run was not requested", args.getString("folivelaReference") == "true")
    val expectedPages = args.getString("referencePages")?.let {
      it.toIntOrNull() ?: error("referencePages must be 30 or 415")
    } ?: 30
    require(expectedPages == 30 || expectedPages == 415) { "referencePages must be 30 (default) or 415 (explicit legacy mode)" }
    val legacyReference = expectedPages == 415
    val pauseStage = args.getString("pauseStage") ?: "translating"
    require(pauseStage in setOf("translating", "ocr", "extracting")) { "Unknown pause control stage" }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val modelId = args.getString("modelId") ?: ModelCatalog.RECOMMENDED_ID
    val model = ModelCatalog.get(modelId)
    val sourceName = if (legacyReference) "u050-equinox-reference.pdf" else "u050-equinox-first30-reference.pdf"
    val expectedSourceSha = if (legacyReference) "5b3d748c1533be0e5eb0d86a7f1f7d23813d5037f0df6e5fa59ef324849ee7ad"
      else "2f37872b6aad382c51d8a0e80a9aa690eb35f8ac47277bfd15389ded7372beed"
    val source = File(context.filesDir, "documents/$sourceName")
    if (!legacyReference && !source.isFile) {
      val staged = File(requireNotNull(context.getExternalFilesDir(null)), sourceName)
      assertTrue("Stage the private first-30-page reference in app external files", staged.isFile)
      source.parentFile?.mkdirs()
      staged.copyTo(source, overwrite = false)
    }
    assertTrue("Stage the private $expectedPages-page reference file before the opt-in run", source.isFile)
    assertEquals(expectedSourceSha, sha256(source))
    assertEquals(expectedPages, JSONObject(NativeCore.inspect(source.absolutePath)).getInt("pageCount"))
    val database = TranslatorDatabase(context)
    val documentId = if (legacyReference) "reference-equinox-u050" else "reference-equinox-u050-first30"
    val jobId = args.getString("jobId") ?: "reference-${if (legacyReference) "legacy-415" else "first30"}-${UUID.randomUUID()}"
    val existing = database.jobJson(jobId)
    if (existing == null) {
      val documentName = if (legacyReference) "2020-chevrolet-equinox-owners-manual-english.pdf"
        else "2020-chevrolet-equinox-owners-manual-english-first30.pdf"
      if (database.documentPath(documentId) == null) database.upsertDocument(documentId, documentName, source.absolutePath,
        expectedPages, sha256(source))
      assertEquals(source.absolutePath, database.documentPath(documentId))
      database.addJob(jobId, JSONObject().put("documentId", documentId).put("modelId", modelId)
        .put("sourceLanguage", "en").put("targetLanguage", "ru").put("useGlossary", false), expectedPages)
      database.updateJob(jobId, status = "paused")
    } else {
      assertEquals(modelId, existing.getString("modelId"))
      assertEquals(documentId, existing.getString("documentId"))
      assertEquals(source.absolutePath, database.documentPath(documentId))
      assertEquals(expectedPages, existing.getInt("totalPages"))
      assertEquals("en", existing.getString("sourceLanguage"))
      assertEquals("ru", existing.getString("targetLanguage"))
    }
    var retryGuardBlocks = 0
    if (args.getString("retryRetainedGuardFailures") == "true") {
      check(!PdfTranslationService.isActive && database.jobJson(jobId)?.optString("status") == "paused")
      val rows = database.pageRows(jobId)
      for (rowIndex in 0 until rows.length()) {
        val index = rows.getJSONObject(rowIndex).getInt("index")
        val page = requireNotNull(database.page(jobId, index))
        val blocks = page.getJSONArray("blocks")
        var changed = false
        for (blockIndex in 0 until blocks.length()) {
          val block = blocks.getJSONObject(blockIndex)
          if (block.optString("status") == "failed" && block.isNull("translatedText") &&
            block.optString("error") == "Translation changed a protected number, code or negation; original retained for review") {
            block.put("status", "pending").remove("error")
            retryGuardBlocks++; changed = true
          }
        }
        if (changed) database.savePage(jobId, index, "pending", blocks.toString(), page.optDouble("width"), page.optDouble("height"), expectedBlocks = blocks.length())
      }
      database.updateJob(jobId, completedPages = database.completedPageCount(jobId))
    }
    val report = JSONObject().put("jobId", jobId).put("modelId", modelId).put("referencePages", expectedPages)
      .put("sourceSha256", expectedSourceSha).put("sourceFile", source.name)
      .put("versionName", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
      .put("startedAt", System.currentTimeMillis()).put("mode", "installed-production-worker")
      .put("retainedGuardBlocksRequestedForActualRetry", retryGuardBlocks)
      .put("backgroundUiReturnCheck", if (args.getString("skipBackgroundReturnForDiagnostic") == "true") "not_run" else "pending")
      .put("pauseResumeUiReturnCheck", if (args.getString("skipBackgroundReturnForDiagnostic") == "true") "not_run" else "pending")
    val referenceLabel = if (legacyReference) "legacy-415" else "first30"
    val evidence = File(context.getExternalFilesDir(null), "u050-$modelId-$referenceLabel.json")
    val checkpointAudit = JSONArray()
    val auditedIndexes = mutableSetOf<Int>()
    fun publish() {
      val snapshot = database.jobJson(jobId)
      report.put("job", snapshot).put("observedAt", System.currentTimeMillis())
      // Payload-bearing samples stay in app-external private QA evidence, outside logcat/repository.
      val rows = database.pageRows(jobId)
      for (rowIndex in 0 until rows.length()) {
        val row = rows.getJSONObject(rowIndex)
        val index = row.getInt("index")
        if (row.optString("status") in setOf("completed", "partial") && auditedIndexes.add(index)) {
          checkpointAudit.put(database.page(jobId, index) ?: error("Missing committed checkpoint $index"))
        }
      }
      if (auditedIndexes.isNotEmpty()) File(context.getExternalFilesDir(null), "u050-$modelId-$referenceLabel-checkpoint-audit.json")
        .writeText(JSONObject().put("jobId", jobId).put("pages", checkpointAudit).toString())
      evidence.writeText(report.toString(2))
      instrumentation.sendStatus(2, Bundle().apply {
        putString("stream", "\nFOLIVELA_REFERENCE " + report.toString() + "\n")
      })
    }
    if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
      val metroUrl = requireNotNull(args.getString("metroUrl")) {
        "Supply the running private Metro URL for the actual Debug reference UI."
      }
      context.startActivity(Intent(Intent.ACTION_VIEW,
        Uri.parse("folivela://expo-development-client/?url=${Uri.encode(metroUrl)}"))
        .setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      val deadline = SystemClock.elapsedRealtime() + 60_000
      var loaded = false
      while (!loaded && SystemClock.elapsedRealtime() < deadline) {
        instrumentation.runOnMainSync {
          loaded = (context.applicationContext as MainApplication).reactHost.currentReactContext?.hasActiveReactInstance() == true
        }
        if (!loaded) Thread.sleep(100)
      }
      assertTrue("The actual Debug React instance must load before the reference UI", loaded)
    }
    fun visibleAppTexts(): Set<String> {
      val visibleTexts = linkedSetOf<String>()
      val reactContext = (context.applicationContext as MainApplication).reactHost.currentReactContext ?: return visibleTexts
      val activity = reactContext.currentActivity as? MainActivity ?: return visibleTexts
      val decor = activity.window?.decorView ?: return visibleTexts
      val readComplete = CountDownLatch(1)
      if (!decor.post {
        val nodes = java.util.ArrayDeque<View>()
        nodes.add(decor)
        while (nodes.isNotEmpty()) {
          val view = nodes.removeFirst()
          if (!view.isShown || view.width <= 0 || view.height <= 0) continue
          if (view is TextView) view.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(visibleTexts::add)
          view.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(visibleTexts::add)
          if (view is ViewGroup) for (index in 0 until view.childCount) nodes.addLast(view.getChildAt(index))
        }
        readComplete.countDown()
      }) return visibleTexts
      readComplete.await(2, TimeUnit.SECONDS)
      return visibleTexts
    }
    fun open() {
      // A ReactInstance can become active before the mounted navigator has
      // rendered its first app screen. Wait for a visible Folivela surface
      // before dispatching a second intent to MainActivity; otherwise the
      // deep link can arrive while the development client is still mounting
      // and be lost even though the app later reaches Home.
      val readyDeadline = SystemClock.elapsedRealtime() + 90_000
      var appUiReady = false
      while (!appUiReady && SystemClock.elapsedRealtime() < readyDeadline) {
        val texts = visibleAppTexts()
        appUiReady = "Folivela" in texts || "Translation progress" in texts
        if (!appUiReady) Thread.sleep(100)
      }
      assertTrue("The React navigator must render a visible Folivela screen before the job deep link", appUiReady)
      // Reuse MainActivity's existing singleTask task for warm returns. ActivityScenario.launch()
      // adds NEW_TASK | CLEAR_TASK internally, which would destroy the task and make this a cold
      // relaunch while also leaving the previous scenario's lifecycle observer registered.
      context.startActivity(Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setData(Uri.parse("folivela:///job?jobId=$jobId"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
      // Main-thread idle does not mean the JS navigator or its first visible
      // frame exists. A resumed checkpoint already has completed pages, so the
      // old Home condition could background a cold launch before React mounted.
      val deadline = SystemClock.elapsedRealtime() + 90_000
      val name = database.jobJson(jobId)!!.getString("documentName")
      var visible = false
      while (!visible && SystemClock.elapsedRealtime() < deadline) {
        val texts = visibleAppTexts()
        visible = "Translation progress" in texts && name in texts
        if (!visible) Thread.sleep(100)
      }
      assertTrue("The real linked translation screen must be visible before Pause or Home", visible)
      report.put("visibleJobScreenConfirmed", true)
    }
    fun resume() {
      database.updateJob(jobId, status = "queued", stage = "queued", clearError = true)
      context.startForegroundService(Intent(context, PdfTranslationService::class.java)
        .setAction(PdfTranslationService.ACTION_START).putExtra(PdfTranslationService.EXTRA_JOB_ID, jobId))
    }
    // Private opt-in soak control: stop the real worker before replacing an
    // APK or collecting another control. The shipping app has no control file.
    val controlFile = File(context.getExternalFilesDir(null), "u050-reference-control-$modelId-$referenceLabel.json")
    fun pauseAndAwaitCheckpoint(): JSONObject {
      val requestedAt = SystemClock.elapsedRealtime()
      val before = database.jobJson(jobId)!!.optInt("completedBlocks")
      context.startService(Intent(context, PdfTranslationService::class.java)
        .setAction(PdfTranslationService.ACTION_PAUSE).putExtra(PdfTranslationService.EXTRA_JOB_ID, jobId))
      val stopDeadline = SystemClock.elapsedRealtime() + 120_000
      while (PdfTranslationService.isActive && SystemClock.elapsedRealtime() < stopDeadline) Thread.sleep(200)
      assertFalse("Pause must release the owning worker", PdfTranslationService.isActive)
      val saved = database.jobJson(jobId)!!
      assertEquals("paused", saved.getString("status"))
      assertTrue("Pause cannot lose committed blocks", saved.optInt("completedBlocks") >= before)
      report.put("servicePauseReleaseMillis", SystemClock.elapsedRealtime() - requestedAt)
      return saved
    }
    try {
      open()
      publish()
      if (args.getString("prepareOnly") == "true") return
      resume()
      var paused = args.getString("skipPause") == "true"
      var backgrounded = false
      var previousPublished = 0L
      val referenceRunWatchdogMillis = expectedPages.toLong() * (14L * 24 * 60 * 60 * 1000 / 415)
      val deadline = SystemClock.elapsedRealtime() + referenceRunWatchdogMillis
      while (SystemClock.elapsedRealtime() < deadline) {
        val job = database.jobJson(jobId) ?: error("The durable reference job disappeared")
        val status = job.getString("status")
        val control = if (controlFile.isFile) runCatching { JSONObject(controlFile.readText()) }.getOrNull() else null
        if (control?.optString("jobId") == jobId && control.optString("action") == "pause-and-exit" && status == "running") {
          assertTrue("The consumed private control must be removed", controlFile.delete())
          report.put("controlledPauseCheckpointBlocks", pauseAndAwaitCheckpoint().optInt("completedBlocks"))
          publish()
          return
        }
        if (SystemClock.elapsedRealtime() - previousPublished >= 30_000) {
          publish(); previousPublished = SystemClock.elapsedRealtime()
        }
        val inference = if (args.getString("pauseDuringInference") == "true" && status == "running" && job.optString("stage") == "translating")
          JSONObject(NativeCore.inferenceProgress()) else null
        val inferenceReady = args.getString("pauseDuringInference") != "true" ||
          (inference?.optInt("generatedTokens", 0) ?: 0) >= 2
        if (!paused && job.optInt("completedBlocks") > 0 && status == "running" && job.optString("stage") == pauseStage && inferenceReady) {
          report.put("observedPauseStage", pauseStage)
          if (inference != null) report.put("inferenceAtPause", inference)
          val saved = pauseAndAwaitCheckpoint()
          report.put("pauseResumeCheckpointBlocks", saved.optInt("completedBlocks"))
          paused = true
          publish()
          if (args.getString("stopAfterPause") == "true") return
          if (args.getString("skipBackgroundReturnForDiagnostic") == "true") {
            report.put("pauseResumeUiReturnCheck", "not_run")
            resume()
          } else {
            open(); resume()
            report.put("pauseResumeUiReturnCheck", "passed")
          }
        }
        if (!backgrounded && job.optInt("completedPages") >= 1 && status == "running") {
          val skipBackgroundReturn = args.getString("skipBackgroundReturnForDiagnostic") == "true"
          if (skipBackgroundReturn) {
            // Diagnostic-only mode leaves the real worker attached to this
            // instrumentation session until its terminal receipt. A separate
            // adb-driven user-return check covers the HOME/foreground path.
            report.put("backgroundUiReturnCheck", "not_run")
          } else {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(5000)
            open()
            report.put("backgroundUiReturnCheck", "passed")
          }
          backgrounded = true
          report.put("homeAndReturn", !skipBackgroundReturn)
        }
        when (status) {
          "completed", "partial" -> {
            assertEquals(expectedPages, job.getInt("completedPages"))
            assertEquals(expectedPages, database.pageRows(jobId).length())
            val content = JSONArray()
            for (index in 0 until expectedPages) content.put(database.page(jobId, index) ?: error("Missing reference checkpoint $index"))
            report.put("translationEvidence", translationEvidence(content))
            val output = JobPdfExporter(context, database).getOrBuild(jobId)
            assertEquals(expectedPages, output.sourcePageCount)
            assertEquals(output.pageCount, JSONObject(NativeCore.inspect(output.file.absolutePath)).getInt("pageCount"))
            assertEquals("The translation retains exactly the source page count", expectedPages, output.pageCount)
            assertEquals(expectedPages, output.receipt.sourceRanges.size)
            assertEquals((0 until expectedPages).toList(), output.receipt.sourceRanges.map { it.sourcePageIndex })
            assertEquals(output.pageCount, output.receipt.pages.size)
            for (range in output.receipt.sourceRanges) {
                assertEquals(1, range.outputPageCount)
                assertEquals(range.sourcePageIndex, range.firstOutputPage)
            }
            report.put("sourcePageCount", expectedPages).put("outputPageCount", output.pageCount)
              .put("layoutVersion", output.layoutVersion).put("layoutReceipt", output.receipt.toJson())
            // Inspect every final output page; overflow pages are part of the result.
            for (index in 0 until output.pageCount) {
              val page = JSONObject(NativeCore.readPage(output.file.absolutePath, index))
              assertFalse("Result page $index must reopen", page.has("error"))
              val image = NativeCore.render(output.file.absolutePath, index, 420)
              assertTrue(image.width > 0 && image.height > 0)
              image.recycle()
            }
            val exported = File(context.getExternalFilesDir(null), "Folivela-0.5.0-$modelId-Equinox-EN-RU-$referenceLabel.pdf")
            output.file.copyTo(exported, overwrite = true)
            report.put("outputBytes", exported.length()).put("outputSha256", sha256(exported))
              .put("outputFile", exported.name).put("finishedAt", System.currentTimeMillis())
            publish()
            return
          }
          "failed", "canceled" -> error("Reference translation stopped: ${job.optString("error")}")
          "paused" -> if (!PdfTranslationService.isActive) {
            // OS/resource pauses remain explicit in the report and keep the original pinned model.
            publish(); Thread.sleep(30_000); resume()
          }
        }
        Thread.sleep(500)
      }
      error("The reference run exceeded its fourteen-day watchdog without completing")
    } finally {
      publish()
      database.close()
    }
  }

  @Test fun untranslatedOriginalsCannotPassReferenceEvidence() {
    val pages=JSONArray().put(JSONObject().put("blocks",JSONArray().put(JSONObject()
      .put("sourceText","Do not remove the cover.").put("translatedText",JSONObject.NULL).put("status","failed"))))
    assertTrue(runCatching { translationEvidence(pages) }.exceptionOrNull() is AssertionError)
  }

  @Test fun retainedSourceAndTranslatedLayoutIssuesAreCountedSeparately() {
    val blocks=JSONArray().put(JSONObject().put("sourceText","Valve").put("translatedText","Клапан").put("status","partial"))
      .put(JSONObject().put("sourceText","42").put("translatedText",JSONObject.NULL).put("status","failed"))
    val evidence=translationEvidence(JSONArray().put(JSONObject().put("blocks",blocks)))
    assertEquals(1,evidence.getInt("blocksWithTranslation"))
    assertEquals(1,evidence.getInt("retainedSourceBlocks"))
    assertEquals(1,evidence.getInt("pagesWithTranslation"))
  }

  /** Processing every page is different from translating its content; retain both measurements. */
  private fun translationEvidence(pages:JSONArray):JSONObject {
    var sourceBlocks=0;var translatedBlocks=0;var changedBlocks=0
    var contentPages=0;var translatedPages=0;var failed=0;var layoutIssues=0
    for(index in 0 until pages.length()) {
      val blocks=pages.getJSONObject(index).getJSONArray("blocks")
      var pageSource=0;var pageTranslated=0
      for(blockIndex in 0 until blocks.length()) {
        val block=blocks.getJSONObject(blockIndex)
        val source=block.optString("sourceText").trim()
        if(source.isEmpty()) continue
        sourceBlocks++;pageSource++
        val target=if(block.isNull("translatedText")) "" else block.optString("translatedText").trim()
        if(target.isNotEmpty()) {
          translatedBlocks++;pageTranslated++
          if(target!=source) changedBlocks++
        }
        if(block.optString("status")=="failed") failed++
        if(block.optString("status")=="partial") layoutIssues++
      }
      if(pageSource>0) contentPages++
      if(pageTranslated>0) translatedPages++
    }
    assertTrue("Original-only PDF output cannot count as a translated reference",translatedBlocks>0 && changedBlocks>0)
    assertEquals("Every page containing extracted text must have actual translated content",contentPages,translatedPages)
    return JSONObject().put("sourceBlocks",sourceBlocks).put("blocksWithTranslation",translatedBlocks)
      .put("retainedSourceBlocks",sourceBlocks-translatedBlocks).put("changedBlocks",changedBlocks)
      .put("pagesWithText",contentPages).put("pagesWithTranslation",translatedPages)
      .put("failedBlocks",failed).put("layoutReviewBlocks",layoutIssues)
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
      val buffer = ByteArray(1024 * 1024)
      while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}

package dev.kigya.translatepdf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.io.File

internal class PdfTranslationService : Service() {
  internal var databaseFactory: () -> TranslatorDatabase = { TranslatorDatabase(applicationContext) }
  private val database by lazy { databaseFactory() }
  internal var modelDownloaderFactory: () -> ModelDownloader = { ModelDownloader(applicationContext, database) }
  internal var modelDefinitionLookup: (String) -> ModelDefinition = ModelCatalog::get
  private val downloader by lazy { modelDownloaderFactory() }
  internal var beginModelDownload: (String, (Long) -> Unit, (Result<File>) -> Unit, String) -> Unit = { id, progress, finished, token ->
    downloader.start(id, onProgress = progress, onFinished = finished, transferToken = token)
  }

  private val stopped = AtomicBoolean(false)
  private var executor: ExecutorService? = null
  private var latestStartId = 0
  private var jobId: String? = null
  private var modelId: String? = null
  internal var stopSelfForStaleIntent: (Int) -> Unit = { id -> stopSelfResult(id) }
  internal var stopSelfForCompletedWorker: (Int) -> Unit = { id -> stopSelfResult(id) }
  internal var modelDownloadExecutorFactory: () -> ExecutorService = { Executors.newSingleThreadExecutor() }
  internal var jobExecutorFactory: () -> ExecutorService = { Executors.newSingleThreadExecutor() }
  internal var ocrEngineFactory: (String) -> OcrEngine = { language -> OcrEngine(this, language) }
  internal var myMemoryTranslatorFactory: () -> MyMemoryTranslator = { MyMemoryTranslator() }
  internal var memoryInfoProvider: () -> android.app.ActivityManager.MemoryInfo = {
    val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
    android.app.ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
  }
  internal var availableDiskBytes: () -> Long = {
    android.os.StatFs(filesDir.absolutePath).let { it.availableBlocksLong * it.blockSizeLong }
  }

  internal var qaProfileEnabled: () -> Boolean = { BuildConfig.DEBUG }
  private val mainHandler = Handler(Looper.getMainLooper())
  @Volatile private var activeOcrEngine: OcrEngine? = null
  @Volatile private var activeMyMemoryTranslator: MyMemoryTranslator? = null

  override fun onCreate() {
    super.onCreate()
    isActive = true
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    latestStartId = startId
    when (intent?.action) {
      ACTION_PAUSE -> {
        val requestedId = intent.getStringExtra(EXTRA_JOB_ID)
        if (requestedId != null && requestedId != jobId) {
          database.updateJob(requestedId, status = "paused")
          stopServiceIfIdle(startId)
        } else {
          stopped.set(true)
          NativeCore.cancel()
          activeMyMemoryTranslator?.cancel()
          activeOcrEngine?.stop()
          (requestedId ?: jobId)?.let { database.updateJob(it, status = "paused") }
          stopSelf(startId)
        }
        return START_NOT_STICKY
      }
      ACTION_CANCEL -> {
        val requestedId = intent.getStringExtra(EXTRA_JOB_ID)
        if (requestedId != null && requestedId != jobId) {
          database.updateJob(requestedId, status = "canceled", error = "Canceled by the user.")
          stopServiceIfIdle(startId)
        } else {
          stopped.set(true)
          NativeCore.cancel()
          activeMyMemoryTranslator?.cancel()
          activeOcrEngine?.stop()
          (requestedId ?: jobId)?.let { database.updateJob(it, status = "canceled", error = "Canceled by the user.") }
          stopSelf(startId)
        }
        return START_NOT_STICKY
      }
      ACTION_PAUSE_MODEL -> {
        modelId?.let { downloader.pause(it); database.modelStatus(it, "paused", downloader.partialLength(it)) }
        stopSelf(startId)
        return START_NOT_STICKY
      }
      ACTION_START_MODEL -> {
        val requestedId = intent.getStringExtra(EXTRA_MODEL_ID) ?: run { stopServiceIfIdle(startId); return START_NOT_STICKY }
        val transferToken = intent.getStringExtra(EXTRA_MODEL_TRANSFER_TOKEN) ?: run { stopServiceIfIdle(startId); return START_NOT_STICKY }
        // Reject stale queued intents before creating an executor or entering foreground.
        if (!downloader.claimServiceStart(requestedId, transferToken)) {
          stopAfterStaleModelIntentIfIdle(startId)
          return START_NOT_STICKY
        }
        if (executor != null) {
          downloader.releaseServiceStart(requestedId, transferToken)
          downloader.releaseReservation(requestedId, transferToken) {
            database.modelStatus(requestedId, "paused", downloader.partialLength(requestedId))
          }
          return START_NOT_STICKY
        }
        modelId = requestedId
        stopped.set(false)
        try {
          startForegroundCompat(notification("Preparing model download…"))
          val worker=modelDownloadExecutorFactory()
          executor=worker
          worker.execute { runModelDownload(requestedId, transferToken, worker) }
        } catch (error: Exception) {
          downloader.releaseServiceStart(requestedId, transferToken)
          stopSelf(startId)
          throw error
        }
        return START_NOT_STICKY
      }
      ACTION_START -> {
        val requestedId = intent.getStringExtra(EXTRA_JOB_ID) ?: run { stopServiceIfIdle(startId); return START_NOT_STICKY }
        if (executor != null) return START_NOT_STICKY
        jobId = requestedId
        activeJobId = requestedId
        stopped.set(false)
        startForegroundCompat(notification("Starting local translation…"))
        val worker=jobExecutorFactory()
        executor=worker
        worker.execute { runJob(requestedId, worker) }
      }
    }
    return START_NOT_STICKY
  }

  private fun runModelDownload(id: String, transferToken: String, owner: ExecutorService) {
    val finished = CountDownLatch(1)
    try {
      val definition = modelDefinitionLookup(id)
      beginModelDownload(id, { bytes ->
        updateNotification("Downloading model · ${bytes / 1_048_576} of ${definition.bytes / 1_048_576} MiB")
      }, { result ->
        val failure = result.exceptionOrNull()
        if (failure != null && failure !is ModelDownloadCanceled && !stopped.get()) {
          database.modelStatus(id, "error", downloader.partialLength(id), failure.message ?: "Model download failed.")
        }
        finished.countDown()
      }, transferToken)
      finished.await()
      updateNotification(if (downloader.isInstalled(id)) "Model downloaded and verified." else "Model download paused or failed. Open the app to continue.")
    } catch (error: InterruptedException) {
      Thread.currentThread().interrupt()
    } catch (error: Exception) {
      database.modelStatus(id, "error", downloader.partialLength(id), error.message ?: "Model download failed.")
      updateNotification("Model download failed. Open the app to retry.")
    } finally {
      finishWorker(owner)
    }
  }

  private fun runJob(id: String, owner: ExecutorService) {
    var ocrEngine: OcrEngine? = null
    var ocrSetupFailure: String? = null
    try {
      val job = database.jobJson(id) ?: error("Translation job was not found.")
      if (job.optString("status") == "canceled") return
      val source = database.jobDocumentPath(id) ?: error("Source document is unavailable.")
      if (job.optString("format") == "docx" || job.optJSONObject("engine")?.optString("kind") == "online") {
        if (job.optString("format") == "pdf" && job.optJSONObject("engine")?.optString("kind") == "online") {
          try { ocrEngine = ocrEngineFactory(job.getString("sourceLanguage")) }
          catch (error: Exception) { ocrSetupFailure = error.message ?: "On-device OCR could not be prepared for this PDF." }
          activeOcrEngine = ocrEngine
        }
        runSegmentsJob(id, job, source, ocrEngine, ocrSetupFailure)
        return
      }
      val modelId = job.getString("modelId")
      val sourceLanguage = job.getString("sourceLanguage")
      val targetLanguage = job.getString("targetLanguage")
      LanguagePair.requireValid(sourceLanguage, targetLanguage)
      database.validateJobSnapshot(id, ModelCatalog.get(modelId))
      val pageCount = job.getInt("totalPages")
      var completed = database.completedPageCount(id)
      val needsTranslation = completed < pageCount
      if (needsTranslation && !downloader.isReady(modelId)) error("The selected model is missing or incomplete.")
      val memory = memoryInfoProvider()
      val freeDisk = availableDiskBytes()
      if (needsTranslation && memory.lowMemory) {
        pauseForResources(id, "Android reports low memory. The job is paused at its latest saved checkpoint.")
        return
      }
      val definition = ModelCatalog.get(modelId)
      val outputSpaceReserve = File(source).length() * 2 + 256L * 1024L * 1024L
      if (freeDisk < outputSpaceReserve) {
        pauseForResources(id, "Free storage is below the source-sized checkpoint/export reserve.")
        return
      }
      database.updateJob(id, completedPages = completed)
      if (needsTranslation) {
        try { ocrEngine = ocrEngineFactory(sourceLanguage) }
        catch (error: Exception) { ocrSetupFailure = error.message ?: "The bundled OCR data for $sourceLanguage is unavailable." }
        activeOcrEngine = ocrEngine
        if (stopped.get() || !database.transitionActiveJob(id, "running", stage = "extracting")) throw InterruptedException()
        val loadJson = try { NativeCore.loadModel(downloader.fileFor(modelId).absolutePath, 2048, 4, definition.profile) }
        catch (error: Exception) {
          if (isAllocationFailure(error.message.orEmpty())) {
            pauseForResources(id, "The selected model could not allocate its working memory. The job is paused at its latest saved checkpoint.")
            return
          }
          throw error
        }
        val loadResult = JSONObject(loadJson)
        if (loadResult.has("error")) {
          val message = loadResult.optString("error", "Model failed to load.")
          if (isAllocationFailure(message)) { pauseForResources(id, "The selected model could not allocate its working memory. The job is paused at its latest saved checkpoint."); return }
          error(message)
        }
      } else if (stopped.get() || !database.transitionActiveJob(id, "running", stage = "exporting")) throw InterruptedException()
      for (pageIndex in 0 until pageCount) {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        val currentMemory = memoryInfoProvider()
        if (needsTranslation && currentMemory.lowMemory) {
          pauseForResources(id, "Android reports low memory. The job is paused at its latest saved checkpoint.")
          return
        }
        val saved = database.pageRows(id)
        val alreadyDone = (0 until saved.length()).any { idx ->
          val row = saved.getJSONObject(idx)
          row.getInt("index") == pageIndex && row.optString("status") in setOf("completed", "partial")
        }
        if (alreadyDone) continue
        database.updateJob(id, stage = "extracting", currentPage = pageIndex)
        updateNotification("Reading page ${pageIndex + 1} of $pageCount")
        val nativePage = JSONObject(NativeCore.readPage(source, pageIndex))
        if (nativePage.has("error")) throw IllegalStateException(nativePage.optString("error", "The page could not be read."))
        val pageWidth = nativePage.optDouble("width").toFloat()
        val pageHeight = nativePage.optDouble("height").toFloat()
        require(pageWidth > 0 && pageHeight > 0) { "Page geometry is unavailable." }
        database.updateJob(id, stage = "ocr", currentPage = pageIndex)
        var ocrFailure: String? = ocrSetupFailure
        val bitmap = try { NativeCore.render(source, pageIndex, OCR_RENDER_WIDTH) }
        catch (error: Exception) { ocrFailure = error.message ?: "Page image could not be rendered for OCR."; null }
        val blocks = try { combineNativeAndOcr(nativePage.optJSONArray("blocks") ?: JSONArray(), bitmap, pageWidth, pageHeight, ocrEngine) }
        catch (error: Exception) {
          ocrFailure = error.message ?: "On-device OCR could not process this page."
          combineNativeAndOcr(nativePage.optJSONArray("blocks") ?: JSONArray(), null, pageWidth, pageHeight, ocrEngine)
        } finally { bitmap?.recycle() }
        val disagreementCount = (0 until blocks.length()).count { blocks.getJSONObject(it).optBoolean("sourceDisagreement") }
        ocrFailure = OcrOutcome.warning(blocks.length(), ocrFailure, disagreementCount)
        val previouslySaved = database.pageBlockArray(id, pageIndex)
        val savedById = HashMap<String, JSONObject>()
        for (savedIndex in 0 until previouslySaved.length()) {
          val savedBlock = previouslySaved.getJSONObject(savedIndex)
          savedById[savedBlock.optString("id")] = savedBlock
        }
        for (blockIndex in 0 until blocks.length()) {
          val block = blocks.getJSONObject(blockIndex)
          savedById[block.optString("id")]?.let { saved ->
            if (saved.optString("status") in setOf("translated", "partial", "failed")) blocks.put(blockIndex, saved)
          }
        }
        val context = nativePage.optString("context", "").take(2_000)
        val glossary = database.jobGlossaryText(id)
        var partial = ocrFailure != null
        database.savePage(id, pageIndex, "running", blocks.toString(), pageWidth.toDouble(), pageHeight.toDouble(), ocrFailure, blocks.length())
        database.updateJob(id, stage = "translating", currentPage = pageIndex)
        for (index in 0 until blocks.length()) {
          if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
          val blockMemory = memoryInfoProvider()
          if (blockMemory.lowMemory) {
            database.savePage(id, pageIndex, "running", blocks.toString(), pageWidth.toDouble(), pageHeight.toDouble(), ocrFailure, blocks.length())
            pauseForResources(id, "Android reports low memory. The job is paused at its latest saved checkpoint.")
            return
          }
          val block = blocks.getJSONObject(index)
          val sourceText = block.optString("sourceText", "").trim()
          if (sourceText.isEmpty()) {
            // A restored review checkpoint must not become a completed page
            // merely because its captured source text is blank.
            partial = true
            continue
          }
          if (block.optString("status") in setOf("translated", "partial", "failed")) {
            if (block.optString("status") != "translated") partial = true
            continue
          }
          updateNotification("Translating page ${pageIndex + 1} of $pageCount · block ${index + 1}/${blocks.length()}")
          val blockStartedAt = android.os.SystemClock.elapsedRealtime()
          try {
            val response = JSONObject(NativeCore.translate(sourceText, glossary, context, sourceLanguage, targetLanguage))
            if (response.has("error") || response.optString("text").isBlank()) {
              val message = response.optString("error", "Translation was not produced.")
              if (isAllocationFailure(message)) {
                block.put("status", "failed").put("error", message)
                database.savePage(id, pageIndex, "running", blocks.toString(), pageWidth.toDouble(), pageHeight.toDouble(), ocrFailure, blocks.length())
                pauseForResources(id, "The model ran out of working memory. The job is paused at its latest saved checkpoint.")
                return
              }
              block.put("translatedText", JSONObject.NULL).put("status", "failed")
                .put("error", message)
              partial = true
            } else {
              val translatedText = response.getString("text")
              if (hasProtectedValueMismatch(sourceText, translatedText)) {
                block.put("translatedText", translatedText).put("status", "partial")
                  .put("error", "Numbers, units, or part codes differ from the source; verify this translation.")
                partial = true
              } else block.put("translatedText", translatedText).put("status", "translated")
            }
          } catch (error: Exception) {
            if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
            val message = error.message.orEmpty()
            if (isAllocationFailure(message)) {
              block.put("translatedText", JSONObject.NULL).put("status", "failed")
                .put("error", message)
              database.savePage(id, pageIndex, "running", blocks.toString(), pageWidth.toDouble(), pageHeight.toDouble(), ocrFailure, blocks.length())
              pauseForResources(id, "The model could not allocate working memory. The job is paused at its latest saved checkpoint.")
              return
            }
            block.put("translatedText", JSONObject.NULL).put("status", "failed")
              .put("error", error.message ?: "Translation failed.")
            partial = true
          } catch (error: OutOfMemoryError) {
            block.put("translatedText", JSONObject.NULL).put("status", "failed")
              .put("error", "Android could not allocate working memory.")
            database.savePage(id, pageIndex, "running", blocks.toString(), pageWidth.toDouble(), pageHeight.toDouble(), ocrFailure, blocks.length())
            pauseForResources(id, "Android could not allocate working memory. The job is paused at its latest saved checkpoint.")
            return
          }
          database.recordBlockDuration(id, android.os.SystemClock.elapsedRealtime() - blockStartedAt)
          database.savePage(id, pageIndex, "running", blocks.toString(), pageWidth.toDouble(), pageHeight.toDouble(), ocrFailure, blocks.length())
        }
        database.updateJob(id, stage = "layout", currentPage = pageIndex)
        val exportCheck = validatePageExport(source, pageIndex, blocks, id, targetLanguage)
        if (exportCheck.partial) partial = true
        val pageStatus = if (partial) "partial" else "completed"
        val pageError = PageExportIssues.pageError(ocrFailure, blocks, partial, exportCheck.issues)
        database.savePage(id, pageIndex, pageStatus, blocks.toString(), pageWidth.toDouble(), pageHeight.toDouble(), pageError, blocks.length())
        completed++
        database.updateJob(id, completedPages = completed)
      }
      if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
      database.updateJob(id, stage = "exporting", clearCurrentPage = true)
      ocrEngine?.close()
      ocrEngine = null
      activeOcrEngine = null
      NativeCore.releaseModel()
      updateNotification("Assembling and verifying the translated PDF…")
      val finalPdf = JobPdfExporter(this, database).getOrBuild(id, persistExport = false) { stopped.get() || Thread.currentThread().isInterrupted }
      withUnpublishedExportCleanup(finalPdf.exportId, finalPdf.file) {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        if (!database.completeActiveJobWithExport(id, finalPdf.exportId, finalPdf.file.absolutePath,
            finalPdf.displayName, "application/pdf", finalPdf.partial)) throw InterruptedException()
      }
      updateNotification(if (finalPdf.partial) "Translation finished with pages that need review." else "Translation completed.")
    } catch (_: InterruptedException) {
      database.transitionActiveJob(id, "paused", error = "Paused after the latest saved page checkpoint.")
    } catch (error: OutOfMemoryError) {
      pauseForResources(id, "Android could not allocate working memory. The job is paused at its latest saved checkpoint.")
    } catch (error: Exception) {
      if (database.transitionActiveJob(id, "failed", error = error.message ?: "Local translation failed.")) {
        updateNotification("Translation stopped. Open the app to review the error.")
      }
    } finally {
      cleanupJobWorker(id, ocrEngine, owner)
    }
  }

  /** DOCX local jobs and online jobs use stable native text segments and durable per-fragment checkpoints. */
  private fun runSegmentsJob(id: String, job: JSONObject, source: String, ocrEngine: OcrEngine?, ocrSetupFailure: String?) {
    val modelId = job.getString("modelId")
    val online = job.optJSONObject("engine")?.optString("kind") == "online"
    val format = job.optString("format", "pdf")
    val sourceLanguage = job.getString("sourceLanguage")
    val targetLanguage = job.getString("targetLanguage")
    val pageCount = job.getInt("totalPages")
    var completed = database.completedPageCount(id)
    val needsTranslation = completed < pageCount
    LanguagePair.requireValid(sourceLanguage, targetLanguage)
    if (availableDiskBytes() < File(source).length() * 2 + 256L * 1024L * 1024L) {
      pauseForResources(id, "Free storage is below the source-sized checkpoint/export reserve.")
      return
    }
    if (online) {
      check(job.optJSONObject("engine")?.optString("id") == "mymemory" && job.optBoolean("onlineConsent")) {
        "This online job has no current privacy-consent snapshot. Start again after reviewing the notice."
      }
      database.validateJobSnapshot(id)
    } else {
      val model = ModelCatalog.get(modelId)
      database.validateJobSnapshot(id, model)
      if (needsTranslation) {
        check(downloader.isReady(modelId)) { "The selected model is missing or incomplete." }
        // Word follows the same actual-resource gates as the PDF pipeline. The
        // model's estimated RAM requirement remains advisory, not a hard gate.
        if (memoryInfoProvider().lowMemory) {
          pauseForResources(id, "Android reports low memory. The job is paused at its latest saved checkpoint.")
          return
        }
        try {
          val loaded = JSONObject(NativeCore.loadModel(downloader.fileFor(modelId).absolutePath, 2048, 4, model.profile))
          if (loaded.has("error")) error(loaded.optString("error", "Model failed to load."))
        } catch (failure: Exception) {
          if (!isAllocationFailure(failure.message.orEmpty())) throw failure
          pauseForResources(id, "The selected model could not allocate its working memory. The job is paused at its latest saved checkpoint.")
          return
        }
      }
    }
    val translator = if (online) myMemoryTranslatorFactory().also { activeMyMemoryTranslator = it } else null
    if (stopped.get() || Thread.currentThread().isInterrupted ||
        !database.transitionActiveJob(id, "running", stage = "extracting")) throw InterruptedException()
    for (index in 0 until pageCount) {
      if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
      val priorPage = database.page(id, index)
      if (priorPage?.optString("status") in setOf("completed", "partial")) continue
      if (!online && memoryInfoProvider().lowMemory) {
        pauseForResources(id, "Android reports low memory. The job is paused at its latest saved checkpoint.")
        return
      }
      database.updateJob(id, stage = "extracting", currentPage = index)
      updateNotification("Reading ${if (format == "docx") "Word segment" else "PDF page"} ${index + 1} of $pageCount")
      val raw = JSONObject(if (format == "docx") NativeCore.readDocxSegment(source, index) else NativeCore.readPage(source, index))
      if (raw.has("error")) error(raw.optString("error", "The document segment could not be read."))
      val width = raw.optDouble("width", 612.0)
      val height = raw.optDouble("height", 792.0)
      val nativeBlocks = raw.optJSONArray("blocks") ?: JSONArray()
      var extractionWarning = ocrSetupFailure
      val blocks = if (online && format == "pdf") {
        val bitmap = try { NativeCore.render(source, index, OCR_RENDER_WIDTH) }
        catch (failure: Exception) {
          extractionWarning = failure.message ?: "The PDF page could not be rendered for text recognition."
          null
        }
        try {
          combineNativeAndOcr(nativeBlocks, bitmap, width.toFloat(), height.toFloat(), ocrEngine)
        } catch (failure: Exception) {
          extractionWarning = failure.message ?: "On-device OCR could not recognize this PDF page."
          combineNativeAndOcr(nativeBlocks, null, width.toFloat(), height.toFloat(), null)
        } finally { bitmap?.recycle() }
      } else nativeBlocks
      val priorBlocks = database.pageBlockArray(id, index)
      val priorById = (0 until priorBlocks.length()).mapNotNull { priorBlocks.optJSONObject(it) }
        .associateBy { it.optString("id") }
      for (bi in 0 until blocks.length()) {
        val block = blocks.getJSONObject(bi)
        val rawText = block.optString("sourceText", block.optString("text"))
        val sourceText = if (format == "docx") rawText else rawText.trim()
        if (sourceText.isBlank()) {
          if (format == "docx") block.put("sourceText", sourceText).put("translatedText", sourceText).put("status", "translated")
          else block.put("sourceText", "").put("status", "skipped")
          continue
        }
        val saved = priorById[block.optString("id")]
        if (saved != null && saved.optString("sourceText") == sourceText) {
          if (saved.optString("status") in setOf("translated", "partial")) blocks.put(bi, saved)
          else if (saved.has("translatedFragments")) block.put("translatedFragments", saved.optJSONArray("translatedFragments"))
          block.put("sourceText", sourceText)
        } else block.put("sourceText", sourceText).put("translatedText", JSONObject.NULL).put("status", "pending")
      }
      database.savePage(id, index, "running", blocks.toString(), width, height, expectedBlocks = blocks.length())
      database.updateJob(id, stage = "translating", currentPage = index)
      var partial = extractionWarning != null
      for (bi in 0 until blocks.length()) {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        if (!online && memoryInfoProvider().lowMemory) {
          pauseForResources(id, "Android reports low memory. The job is paused at its latest saved checkpoint.")
          return
        }
        val block = blocks.getJSONObject(bi)
        if (block.optString("status") in setOf("translated", "partial", "skipped")) {
          if (block.optString("status") == "partial") partial = true
          continue
        }
        val text = block.getString("sourceText")
        val blockStartedAt = android.os.SystemClock.elapsedRealtime()
        try {
          updateNotification("Translating segment ${index + 1} of $pageCount · block ${bi + 1}/${blocks.length()}")
          val translated = if (online) translator!!.translate(text, sourceLanguage, targetLanguage,
            block.optJSONArray("translatedFragments") ?: JSONArray()) { fragmentIndex, fragmentSource, fragmentText ->
            val fragments = block.optJSONArray("translatedFragments") ?: JSONArray()
            val replacement = JSONArray()
            for (fi in 0 until fragments.length()) if (fragments.getJSONObject(fi).optInt("index") != fragmentIndex) replacement.put(fragments.getJSONObject(fi))
            replacement.put(JSONObject().put("index", fragmentIndex).put("source", fragmentSource).put("translated", fragmentText))
            block.put("translatedFragments", replacement)
            database.savePage(id, index, "running", blocks.toString(), width, height, expectedBlocks = blocks.length())
          } else {
            val result = JSONObject(NativeCore.translate(text, database.jobGlossaryText(id), "", sourceLanguage, targetLanguage))
            if (result.has("error") || result.optString("text").isBlank()) error(result.optString("error", "Translation was not produced."))
            result.getString("text")
          }
          block.put("translatedText", translated).put("status", "translated").remove("error")
        } catch (failure: MyMemoryException) {
          if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
          database.savePage(id, index, "running", blocks.toString(), width, height, expectedBlocks = blocks.length())
          if (database.transitionActiveJob(id, "paused", stage = "translating", error = failure.message)) {
            updateNotification("Online translation paused. Open the app to review and resume.")
          }
          return
        } catch (interrupted: InterruptedException) {
          throw interrupted
        } catch (failure: Exception) {
          if (!online && isAllocationFailure(failure.message.orEmpty())) {
            database.savePage(id, index, "running", blocks.toString(), width, height, expectedBlocks = blocks.length())
            pauseForResources(id, "The model could not allocate working memory. The job is paused at its latest saved checkpoint.")
            return
          }
          block.put("translatedText", JSONObject.NULL).put("status", "failed").put("error", failure.message ?: "Translation failed.")
          partial = true
        }
        database.recordBlockDuration(id, android.os.SystemClock.elapsedRealtime() - blockStartedAt)
        database.savePage(id, index, "running", blocks.toString(), width, height, expectedBlocks = blocks.length())
      }
      val warnings = raw.optJSONArray("warnings")
      val warningSet = linkedSetOf<String>()
      extractionWarning?.takeIf { it.isNotBlank() }?.let(warningSet::add)
      warnings?.let { list -> for (wi in 0 until list.length()) list.optString(wi).takeIf { it.isNotBlank() && it != "null" }?.let(warningSet::add) }
      val warningText = warningSet.joinToString("; ")
      val pageStatus = if (partial || (0 until blocks.length()).any { blocks.optJSONObject(it)?.optString("status") != "translated" }) "partial" else "completed"
      database.savePage(id, index, pageStatus, blocks.toString(), width, height, warningText.ifBlank { null }, blocks.length())
      completed++
      database.updateJob(id, completedPages = completed)
    }
    if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
    database.updateJob(id, stage = "exporting", clearCurrentPage = true)
    if (format == "docx") {
      val allBlocks = JSONArray()
      for (index in 0 until pageCount) {
        val saved = database.pageBlockArray(id, index)
        for (bi in 0 until saved.length()) allBlocks.put(saved.getJSONObject(bi))
      }
      val output = File(filesDir, "translated-docx/$id.docx").apply { parentFile?.mkdirs() }
      withUnpublishedExportCleanup(id, output) {
        val result = JSONObject(NativeCore.exportDocx(source, allBlocks.toString(), output.absolutePath))
        if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        if (result.has("error") || !output.isFile || output.length() == 0L) error(result.optString("error", "The translated Word file could not be written."))
        val exportWarnings = result.optJSONArray("warnings")
        if (exportWarnings != null && exportWarnings.length() > 0) {
          val first = database.page(id, 0)
          if (first != null) {
            val existing = if (first.isNull("error")) null else first.optString("error").takeIf(String::isNotBlank)
            val combined = linkedSetOf<String>().apply {
              existing?.split("; ")?.filter(String::isNotBlank)?.forEach(::add)
              for (wi in 0 until exportWarnings.length()) exportWarnings.optString(wi).takeIf { it.isNotBlank() && it != "null" }?.let(::add)
            }.joinToString("; ")
            database.savePage(id, 0, first.getString("status"), first.getJSONArray("blocks").toString(),
              first.optDouble("width"), first.optDouble("height"), combined.ifBlank { null }, first.optInt("expectedBlocks", 1))
          }
        }
        val partial = result.optBoolean("partial")
        if (!database.completeActiveJobWithExport(id, id, output.absolutePath,
            job.optString("documentName", "translated.docx").removeSuffix(".docx") + "-translated.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", partial)) throw InterruptedException()
      }
    } else {
      val result = JobPdfExporter(this, database).getOrBuild(id, persistExport = false) { stopped.get() || Thread.currentThread().isInterrupted }
      withUnpublishedExportCleanup(result.exportId, result.file) {
        if (stopped.get() || Thread.currentThread().isInterrupted ||
            !database.completeActiveJobWithExport(id, result.exportId, result.file.absolutePath,
              result.displayName, "application/pdf", result.partial)) throw InterruptedException()
      }
    }
  }

  /** A canceled/failed final commit must not leave a newly created private result. */
  private fun withUnpublishedExportCleanup(exportId: String, output: File, action: () -> Unit) {
    val wasPublished = database.exportInfo(exportId)?.path == output.absolutePath
    try {
      action()
    } catch (failure: Throwable) {
      if (!wasPublished) output.delete()
      throw failure
    }
  }

  private fun cleanupJobWorker(id: String, ocrEngine: OcrEngine?, owner: ExecutorService) {
      ocrEngine?.close()
      activeOcrEngine = null
      activeMyMemoryTranslator = null
      if (qaProfileEnabled() && database.jobJson(id)?.optString("status") in setOf("completed", "partial", "failed", "canceled")) {
        getSharedPreferences(QA_PROFILE_PREFS, MODE_PRIVATE).edit()
          .remove(QA_PROFILE_REGISTERED_JOB).apply()
      }
      runCatching { NativeCore.releaseModel() }
      finishWorker(owner)
  }

  internal fun finishWorker(owner: ExecutorService) {
    mainHandler.post {
      owner.shutdown()
      if (executor !== owner) return@post
      stopForeground(STOP_FOREGROUND_DETACH)
      executor=null
      jobId=null
      modelId=null
      activeJobId=null
      stopped.set(true)
      stopSelfForCompletedWorker(latestStartId)
    }
  }

  internal fun stopServiceIfIdle(startId: Int): Boolean {
    if (executor != null) return false
    stopSelfForStaleIntent(latestStartId.coerceAtLeast(startId))
    return true
  }

  private fun pauseForResources(id: String, message: String) {
    if (database.transitionActiveJob(id, "paused", error = message)) {
      updateNotification("Translation paused to protect device resources. Open the app to resume.")
    }
  }

  private fun isAllocationFailure(message: String): Boolean =
    message.contains("out of memory", ignoreCase = true) ||
      message.contains("allocation failed", ignoreCase = true) ||
      message.contains("bad_alloc", ignoreCase = true) ||
      message.contains("cannot allocate", ignoreCase = true)

  private fun combineNativeAndOcr(nativeBlocks: JSONArray, bitmap: Bitmap?, pageWidth: Float, pageHeight: Float,
                                  ocrEngine: OcrEngine?): JSONArray {
    val blocks = JSONArray()
    for (index in 0 until nativeBlocks.length()) {
      val raw = nativeBlocks.getJSONObject(index)
      val text = raw.optString("text", raw.optString("sourceText", "")).trim()
      if (text.isBlank()) continue
      blocks.put(JSONObject()
        .put("id", raw.optString("id", "native-$index")).put("sourceText", text)
        .put("translatedText", JSONObject.NULL).put("left", raw.optDouble("left"))
        .put("top", raw.optDouble("top")).put("right", raw.optDouble("right"))
        .put("bottom", raw.optDouble("bottom")).put("fontSize", raw.optDouble("fontSize", 10.0))
        .put("source", "native").put("objectIndex", if (raw.isNull("objectIndex")) JSONObject.NULL else raw.optInt("objectIndex"))
        .put("objectIndices", raw.optJSONArray("objectIndices") ?: JSONArray().apply { if (!raw.isNull("objectIndex")) put(raw.optInt("objectIndex")) })
        .put("status", "pending"))
    }
    if (bitmap == null || ocrEngine == null) return blocks
    val ocr = ocrEngine.recognize(bitmap, pageWidth, pageHeight)
    for (index in 0 until ocr.length()) {
      val line = ocr.getJSONObject(index)
      val match = overlappingNativeBlock(blocks, line.optDouble("left").toFloat(), line.optDouble("top").toFloat(),
        line.optDouble("right").toFloat(), line.optDouble("bottom").toFloat())
      if (match != null) {
        if (OcrOutcome.disagrees(match.optString("sourceText"), line.optString("sourceText"))) {
          match.put("sourceDisagreement", true).put("error", OcrOutcome.SOURCE_DISAGREEMENT_BLOCK_WARNING)
        }
      } else {
        blocks.put(line)
      }
    }
    return blocks
  }

  private fun hasProtectedValueMismatch(source: String, translated: String): Boolean =
    ProtectedValueGuard.hasMismatch(source, translated)

  private fun validatePageExport(source: String, pageIndex: Int, blocks: JSONArray, jobId: String, targetLanguage: String): PageExportCheck {
    val validationDirectory = File(cacheDir, "page-validation").apply { mkdirs() }
    val destination = File(validationDirectory, "$jobId-$pageIndex.pdf")
    val font = File(cacheDir, "NotoSans-Regular.ttf")
    FontAsset.ensure(this, font)
    val cjkFontPath = if (targetLanguage == "zh-Hans") {
      FontAsset.ensureCjk(this, File(cacheDir, "NotoSansSC-Regular.ttf")).absolutePath
    } else ""
    try {
      val result = JSONObject(NativeCore.exportPage(source, pageIndex, blocks.toString(), destination.absolutePath, font.absolutePath, cjkFontPath))
      if (result.has("error")) throw IllegalStateException(result.optString("error", "Page layout validation failed."))
      require(destination.isFile && destination.length() > 0L) { "Page layout validation produced no PDF output." }
      val issues = result.optJSONArray("issues") ?: JSONArray()
      PageExportIssues.applyToBlocks(blocks, issues)
      return PageExportCheck(result.optBoolean("partial") || issues.length() > 0, issues)
    } finally { destination.delete() }
  }

  private data class PageExportCheck(val partial: Boolean, val issues: JSONArray)

  private fun overlappingNativeBlock(blocks: JSONArray, left: Float, top: Float, right: Float, bottom: Float): JSONObject? {
    var best: JSONObject? = null
    var bestOverlap = 0f
    for (index in 0 until blocks.length()) {
      val block = blocks.getJSONObject(index)
      val l = block.optDouble("left").toFloat(); val t = block.optDouble("top").toFloat()
      val r = block.optDouble("right").toFloat(); val b = block.optDouble("bottom").toFloat()
      val intersection = (minOf(r, right) - maxOf(l, left)).coerceAtLeast(0f) * (minOf(b, bottom) - maxOf(t, top)).coerceAtLeast(0f)
      val area = maxOf((r - l) * (b - t), (right - left) * (bottom - top), 1f)
      val overlapRatio = intersection / area
      if (overlapRatio > 0.45f && overlapRatio > bestOverlap) {
        best = block
        bestOverlap = overlapRatio
      }
    }
    return best
  }

  private fun notification(text: String): Notification {
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    val pending = launch?.let { PendingIntent.getActivity(this, 100, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(text)
      .setContentIntent(pending)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  private fun updateNotification(text: String) {
    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
  }

  private fun createNotificationChannel() {
    // minSdk 26: every supported platform requires notification channels.
    getSystemService(NotificationManager::class.java).createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "PDF translation", NotificationManager.IMPORTANCE_LOW),
    )
  }

  private fun startForegroundCompat(value: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else startForeground(NOTIFICATION_ID, value)
  }

  @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
  override fun onTimeout(startId: Int, fgsType: Int) {
    modelId?.let(downloader::pause)
    jobId?.let { database.updateJob(it, status = "paused", error = "Android stopped background execution at its data sync time limit.") }
    stopped.set(true)
    NativeCore.cancel()
    activeMyMemoryTranslator?.cancel()
    activeOcrEngine?.stop()
    runCatching { NativeCore.releaseModel() }
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf(startId)
  }

  override fun onDestroy() {
    activeMyMemoryTranslator?.cancel()
    TranslationServiceDestroyHandler.run(
      stopped = stopped,
      database = database,
      activeJobId = jobId,
      cancelNative = { NativeCore.cancel() },
      stopOcr = { activeOcrEngine?.stop() },
      pauseDownload = { modelId?.let(downloader::pause) },
      interruptWorker = { executor?.shutdownNow() },
    )
    executor=null
    jobId=null
    modelId=null
    isActive = false
    activeJobId = null
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  internal fun stopAfterStaleModelIntentIfIdle(startId: Int): Boolean {
    return stopServiceIfIdle(startId)
  }

  companion object {
    @Volatile var isActive: Boolean = false
      private set
    @Volatile var activeJobId: String? = null
      private set
    const val ACTION_START = "dev.kigya.translatepdf.action.START"
    const val ACTION_PAUSE = "dev.kigya.translatepdf.action.PAUSE"
    const val ACTION_CANCEL = "dev.kigya.translatepdf.action.CANCEL"
    const val ACTION_START_MODEL = "dev.kigya.translatepdf.action.START_MODEL"
    const val ACTION_PAUSE_MODEL = "dev.kigya.translatepdf.action.PAUSE_MODEL"
    const val EXTRA_JOB_ID = "jobId"
    const val EXTRA_MODEL_ID = "modelId"
    const val EXTRA_MODEL_TRANSFER_TOKEN = "modelTransferToken"
    const val CHANNEL_ID = "pdf_translation"
    private const val QA_PROFILE_PREFS = "debug_native_qa_profile"
    private const val QA_PROFILE_REGISTERED_JOB = "registered_job_id"
    private const val NOTIFICATION_ID = 8201
    private const val OCR_RENDER_WIDTH = 1400
  }
}

/** Orders native cancellation before service teardown and pauses only a still-running job. */
internal object TranslationServiceDestroyHandler {
  fun run(
    stopped: AtomicBoolean,
    database: TranslatorDatabase,
    activeJobId: String?,
    cancelNative: () -> Unit,
    stopOcr: () -> Unit,
    pauseDownload: () -> Unit,
    interruptWorker: () -> Unit,
  ) {
    stopped.set(true)
    runCatching(cancelNative)
    runCatching(stopOcr)
    runCatching(pauseDownload)
    runCatching(interruptWorker)
    if (activeJobId == null) return

    val values = ContentValues().apply {
      put("status", "paused")
      put("error", "Paused after the latest saved page checkpoint.")
      putNull("current_page")
      put("updated_at", System.currentTimeMillis())
    }
    // Conditional update prevents teardown from overwriting a completion/cancel
    // that the worker committed concurrently.
    database.writableDatabase.update("jobs", values, "id=? AND status='running'", arrayOf(activeJobId))
  }
}

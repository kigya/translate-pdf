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
import android.os.Process
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
  private data class PreservedIllustrationBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
  )
  private data class HyMtBatchTarget(val id: String, val text: String, val blockIndexes: MutableList<Int>)
  private data class HyMtBatchPageOutcome(val completedBlockCount: Int, val partial: Boolean, val paused: Boolean)

  internal var databaseFactory: () -> TranslatorDatabase = { TranslatorDatabase(applicationContext) }
  private val database by lazy { databaseFactory() }
  internal var modelDownloaderFactory: () -> ModelDownloader = { ModelDownloader(applicationContext, database) }
  internal var modelDefinitionLookup: (String) -> ModelDefinition = ModelCatalog::get
  private val downloader by lazy { modelDownloaderFactory() }
  internal var beginModelDownload: (String, (Long) -> Unit, (Result<File>) -> Unit, String) -> Unit = { id, progress, finished, token ->
    downloader.start(id, onProgress = progress, onFinished = finished, transferToken = token)
  }

  private val stopped = AtomicBoolean(false)
  private val localTranslations = LocalTranslationMemo { text, glossary, context, source, target ->
    NativeCore.translate(text, glossary, context, source, target)
  }
  private val io: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "Folivela-service-io").apply { isDaemon = true } }
  private var executor: ExecutorService? = null
  private var latestStartId = 0
  private var jobId: String? = null
  private var modelId: String? = null
  private var modelTransferToken: String? = null
  private var modelWorkerGeneration = 0L
  @Volatile private var modelWorkerEntered = false
  @Volatile private var modelWorkerEntryAborted = false
  private var workerToken = 0L
  @Volatile private var workerEntered = false
  @Volatile private var workerEntryAborted = false
  @Volatile private var serviceDestroyed = false
  private val workerEntryLock = Any()
  private val urlDomainPattern = Regex("(?i)(?:https?://)?(?:www\\.)?([a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\\.[a-z]{2,})")
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
  internal var currentWorkerThreadPriority: () -> Int = { Process.getThreadPriority(Process.myTid()) }
  internal var setWorkerThreadPriority: (Int) -> Unit = Process::setThreadPriority
  internal var workerElapsedRealtime: () -> Long = android.os.SystemClock::elapsedRealtime
  internal var setWeightRepackingAllowed: (Boolean) -> Unit = { allowed -> NativeCore.setWeightRepackingAllowed(allowed) }

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
        if (requestedId != null && requestedId == jobId && executor != null) requestStop(requestedId, canceled = false)
        else {
          if (requestedId != null) io.execute { database.transitionActiveJob(requestedId, "paused") }
          stopServiceIfIdle(startId)
        }
        return START_NOT_STICKY
      }
      ACTION_CANCEL -> {
        val requestedId = intent.getStringExtra(EXTRA_JOB_ID)
        if (requestedId != null && requestedId == jobId && executor != null) {
          requestStop(requestedId, canceled = true)
          io.execute { database.updateJob(requestedId, status = "canceled", error = "Canceled by the user.") }
        }
        else if (requestedId != null) {
          io.execute { database.updateJob(requestedId, status = "canceled", error = "Canceled by the user.") }
          stopServiceIfIdle(startId)
        }
        else stopServiceIfIdle(startId)
        return START_NOT_STICKY
      }
      ACTION_PAUSE_MODEL -> {
        val requestedId = intent.getStringExtra(EXTRA_MODEL_ID) ?: modelId
        requestedId?.let { id -> io.execute {
          runCatching { downloader.pause(id) }
          database.modelStatus(id, "paused", downloader.partialLength(id))
        } }
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
        modelTransferToken = transferToken
        modelWorkerGeneration = nextWorkerGeneration.incrementAndGet()
        synchronized(modelOwnershipLock) {
          activeModelTransferToken = transferToken
          activeModelWorkerGeneration = modelWorkerGeneration
          isActive = true
        }
        modelWorkerEntered = false
        modelWorkerEntryAborted = false
        stopped.set(false)
        val generation = modelWorkerGeneration
        try {
          startForegroundCompat(notification("Preparing model download…"))
          val worker=modelDownloadExecutorFactory()
          executor=worker
          worker.execute {
            val entered = synchronized(workerEntryLock) {
              if (modelWorkerEntryAborted || serviceDestroyed || stopped.get() || executor !== worker ||
                modelId != requestedId || modelTransferToken != transferToken || modelWorkerGeneration != generation) false
              else { modelWorkerEntered = true; true }
            }
            if (entered) withBackgroundWorkerPriority { runModelDownload(requestedId, transferToken, worker, generation) }
            else worker.shutdown()
          }
        } catch (error: Exception) {
          downloader.releaseServiceStart(requestedId, transferToken)
          if (modelTransferToken == transferToken && modelWorkerGeneration == generation) {
            executor?.shutdownNow()
            executor = null
            modelId = null
            modelTransferToken = null
            modelWorkerEntryAborted = true
            clearModelWorkerOwnership(transferToken, generation)
          }
          stopSelf(startId)
          throw error
        }
        return START_NOT_STICKY
      }
      ACTION_START -> {
        val requestedId = intent.getStringExtra(EXTRA_JOB_ID) ?: run { stopServiceIfIdle(startId); return START_NOT_STICKY }
        if (executor != null || activeJobId != null) return START_NOT_STICKY
        jobId = requestedId
        workerToken = nextWorkerGeneration.incrementAndGet()
        activeWorkerGeneration = workerToken
        activeJobId = requestedId
        workerEntered = false
        workerEntryAborted = false
        stopped.set(false)
        startForegroundCompat(notification("Starting local translation…"))
        val worker=jobExecutorFactory()
        executor=worker
        val generation = workerToken
        worker.execute { withBackgroundWorkerPriority {
          if (claimWorkerEntry(requestedId, worker, generation)) runJob(requestedId, worker, generation)
          else worker.shutdown()
        } }
      }
    }
    return START_NOT_STICKY
  }

  private fun requestStop(id: String, canceled: Boolean) {
    markWorkerStopping(id, canceled)
    stopped.set(true)
    runCatching { NativeCore.cancel() }
    val ocr = activeOcrEngine
    val translator = activeMyMemoryTranslator
    stopSignals.execute { runCatching { translator?.cancel() }; runCatching { ocr?.stop() } }
  }

  private fun claimWorkerEntry(id: String, owner: ExecutorService, generation: Long): Boolean = synchronized(workerEntryLock) {
    if (workerEntryAborted || !ownsJobWorker(owner, id, generation)) return@synchronized false
    workerEntered = true
    true
  }

  private fun ownsJobWorker(owner: ExecutorService?, id: String?, generation: Long): Boolean =
    !workerEntryAborted && ownsJobLease(owner, id, generation)

  private fun ownsJobLease(owner: ExecutorService?, id: String?, generation: Long): Boolean =
    owner != null && id != null && executor === owner && jobId == id && workerToken == generation &&
      activeJobId == id && activeWorkerGeneration == generation

  /** Keep CPU-heavy extraction/inference below interactive UI work, then restore reused executor threads. */
  private fun <T> withBackgroundWorkerPriority(block: () -> T): T {
    val previousPriority = runCatching(currentWorkerThreadPriority).getOrNull() ?: return block()
    val lowered = runCatching { setWorkerThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }.isSuccess
    try {
      return block()
    } finally {
      if (lowered) runCatching { setWorkerThreadPriority(previousPriority) }
    }
  }

  private fun runModelDownload(id: String, transferToken: String, owner: ExecutorService, generation: Long) {
    val finished = CountDownLatch(1)
    try {
      val definition = modelDefinitionLookup(id)
      beginModelDownload(id, { bytes ->
        publishModelNotification(owner, id, transferToken, generation,
          "Downloading model · ${bytes / 1_048_576} of ${definition.bytes / 1_048_576} MiB")
      }, { result ->
        val failure = result.exceptionOrNull()
        if (failure != null && failure !is ModelDownloadCanceled && ownsModelWorker(owner, id, transferToken, generation) && !stopped.get()) {
          database.modelStatus(id, "error", downloader.partialLength(id), failure.message ?: "Model download failed.")
        }
        finished.countDown()
      }, transferToken)
      finished.await()
      publishModelNotification(owner, id, transferToken, generation,
        if (downloader.isInstalled(id)) "Model downloaded and verified." else "Model download paused or failed. Open the app to continue.")
    } catch (error: InterruptedException) {
      Thread.currentThread().interrupt()
    } catch (error: Exception) {
      if (ownsModelWorker(owner, id, transferToken, generation) && !stopped.get()) {
        database.modelStatus(id, "error", downloader.partialLength(id), error.message ?: "Model download failed.")
        publishModelNotification(owner, id, transferToken, generation, "Model download failed. Open the app to retry.")
      }
    } finally {
      finishModelDownloadWorker(owner, id, transferToken, generation)
    }
  }

  private fun ownsModelWorker(owner: ExecutorService, id: String, token: String, generation: Long): Boolean =
    executor === owner && modelId == id && modelTransferToken == token && modelWorkerGeneration == generation &&
      activeModelTransferToken == token && activeModelWorkerGeneration == generation

  private fun publishModelNotification(owner: ExecutorService, id: String, token: String, generation: Long, text: String) {
    mainHandler.post {
      // Service commands and this final ownership check share the main queue;
      // a late progress event cannot replace a newer job's foreground record.
      if (!serviceDestroyed && !stopped.get() && ownsModelWorker(owner, id, token, generation)) updateNotification(text)
    }
  }

  private fun runJob(id: String, owner: ExecutorService, generation: Long) {
    val timing = ProcessingTiming()
    localTranslations.clear()
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
        runSegmentsJob(id, job, source, ocrEngine, ocrSetupFailure, timing)
        return
      }
      val modelId = job.getString("modelId")
      val sourceLanguage = job.getString("sourceLanguage")
      val targetLanguage = job.getString("targetLanguage")
      LanguagePair.requireValid(sourceLanguage, targetLanguage)
      val definition = ModelCatalog.get(modelId)
      database.validateJobSnapshot(id, definition)
      val pageCount = job.getInt("totalPages")
      val useGemmaDocumentTitle = job.optString("format") == "pdf" && definition.profile == TRANSLATE_GEMMA_PROFILE
      val savedCompletedPageIndexes = database.completedPageIndexes(id)
      val contaminatedCompletedPageIndexes = if (useGemmaDocumentTitle) {
        savedCompletedPageIndexes.filterTo(mutableSetOf()) { pageIndex -> pageContainsPromptArtifact(id, pageIndex) }
      } else emptySet()
      val completedPageIndexes = savedCompletedPageIndexes - contaminatedCompletedPageIndexes
      var completed = (database.completedPageCount(id) - contaminatedCompletedPageIndexes.size).coerceAtLeast(0)
      val needsTranslation = completed < pageCount
      if (needsTranslation && !downloader.isReady(modelId)) error("The selected model is missing or incomplete.")
      val memory = memoryInfoProvider()
      val freeDisk = availableDiskBytes()
      if (needsTranslation && memory.lowMemory) {
        pauseForResources(id, "Android reports low memory. The job is paused at its latest saved checkpoint.")
        return
      }
      val useHyMtBatch = job.optString("format") == "pdf" && definition.profile == HY_MT2_PROFILE
      val batchMaxSequences = if (modelId == "hy-mt2-7b-q4") 8 else 4
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
        if (stopped.get() || !database.transitionActiveJob(id, "running", stage = "loadingModel")) throw InterruptedException()
        updateNotification("Loading translation model…")
        val modelFile = downloader.fileFor(modelId)
        setWeightRepackingAllowed(WeightRepackingPolicy.shouldAllow(modelFile.length(), memory.totalMem))
        val loadJson = try { timing.measure("model_load") {
          if (useHyMtBatch) NativeCore.loadModelBatch(modelFile.absolutePath, batchMaxSequences * 512, 4, definition.profile, batchMaxSequences)
          else NativeCore.loadModel(modelFile.absolutePath, 2048, 4, definition.profile)
        } }
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
        val alreadyDone = pageIndex in completedPageIndexes
        if (alreadyDone) {
          database.backfillPageBlockProgress(id, pageIndex)
          continue
        }
        database.updateJob(id, stage = "extracting", currentPage = pageIndex, clearCurrentBlock = true)
        updateNotification("Reading page ${pageIndex + 1} of $pageCount")
        val nativePage = JSONObject(timing.measure("pdf_read") { NativeCore.readPage(source, pageIndex) })
        if (nativePage.has("error")) throw IllegalStateException(nativePage.optString("error", "The page could not be read."))
        val pageWidth = nativePage.optDouble("width").toFloat()
        val pageHeight = nativePage.optDouble("height").toFloat()
        require(pageWidth > 0 && pageHeight > 0) { "Page geometry is unavailable." }
        database.updateJob(id, stage = "ocr", currentPage = pageIndex)
        var ocrFailure: String? = ocrSetupFailure
        val blocks = timing.measure("ocr") {
          val bitmap = try { NativeCore.render(source, pageIndex, OCR_RENDER_WIDTH) }
          catch (error: Exception) { ocrFailure = error.message ?: "Page image could not be rendered for OCR."; null }
          try { combineNativeAndOcrWithArtwork(nativePage.optJSONArray("blocks") ?: JSONArray(), bitmap, pageWidth, pageHeight,
            ocrEngine, nativePage.optJSONArray("artworkRects") ?: JSONArray()) }
          catch (error: Exception) {
            ocrFailure = error.message ?: "On-device OCR could not process this page."
            combineNativeAndOcrWithArtwork(nativePage.optJSONArray("blocks") ?: JSONArray(), null, pageWidth, pageHeight,
              ocrEngine, nativePage.optJSONArray("artworkRects") ?: JSONArray())
          } finally { bitmap?.recycle() }
        }
        val disagreementCount = (0 until blocks.length()).count { blocks.getJSONObject(it).optBoolean("sourceDisagreement") }
        ocrFailure = OcrOutcome.warning(blocks.length(), ocrFailure, disagreementCount)
        val pageContext = nativePage.optString("context", "").take(2_000)
        val gemmaDocumentTitle = if (useGemmaDocumentTitle) {
          GemmaTranslationContext.effectiveTitle(nativePage.opt("documentTitle") as? String)
        } else null
        val translationContext = gemmaDocumentTitle ?: pageContext
        val previouslySaved = database.pageBlockArray(id, pageIndex)
        val savedById = HashMap<String, JSONObject>()
        for (savedIndex in 0 until previouslySaved.length()) {
          val savedBlock = previouslySaved.getJSONObject(savedIndex)
          savedById[savedBlock.optString("id")] = savedBlock
        }
        for (blockIndex in 0 until blocks.length()) {
          val block = blocks.getJSONObject(blockIndex)
          gemmaDocumentTitle?.let { GemmaTranslationContext.stamp(block, it) }
          savedById[block.optString("id")]?.let { saved ->
            // Earlier checkpoints marked resource failures terminal. Retry only
            // those unfinished requests. A terminal target is reusable only
            // when this fresh extraction still describes the same source unit
            // and model input; extraction fixes can change one block without
            // invalidating unrelated durable translations on the page.
            val retryResourceFailure = saved.optString("status") == "failed" && isAllocationFailure(saved.optString("error"))
            if (!retryResourceFailure && checkpointMatchesSource(saved, block, gemmaDocumentTitle)) {
              if (saved.optString("status") in setOf("translated", "partial", "failed")) {
                restoreDurableTranslationResult(saved, block)
              }
              if (useGemmaDocumentTitle) GemmaTranslationContext.prepareCheckpointArtifactRepair(saved, block)
            }
          }
        }
        val glossary = database.jobGlossaryText(id)
        var partial = ocrFailure != null
      var completedBlockCount = (0 until blocks.length()).count { index ->
          blocks.getJSONObject(index).optString("status") in setOf("translated", "partial", "failed", "skipped")
        }
        fun saveCurrentBlocks(pageStatus: String = "running") = timing.measure("checkpoint_write") {
          database.savePage(id, pageIndex, pageStatus, blocks.toString(), pageWidth.toDouble(), pageHeight.toDouble(),
            ocrFailure, blocks.length(), knownCompletedBlocks = completedBlockCount)
        }
        saveCurrentBlocks()
        database.updateJob(id, stage = "translating", currentPage = pageIndex)
        if (useHyMtBatch) {
          val outcome = translateHyMtPdfPage(
            id = id,
            owner = owner,
            generation = generation,
            pageIndex = pageIndex,
            pageCount = pageCount,
            blocks = blocks,
            glossary = glossary,
            background = translationContext,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            maxSequences = batchMaxSequences,
            initialCompletedBlockCount = completedBlockCount,
            initialPartial = partial,
            timing = timing,
            saveCheckpoint = { count ->
              timing.measure("checkpoint_write") {
                database.savePage(id, pageIndex, "running", blocks.toString(), pageWidth.toDouble(), pageHeight.toDouble(),
                  ocrFailure, blocks.length(), knownCompletedBlocks = count)
              }
            },
          )
          completedBlockCount = outcome.completedBlockCount
          partial = outcome.partial
          if (outcome.paused) return
          if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        } else for (index in 0 until blocks.length()) {
          if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
          val blockMemory = memoryInfoProvider()
          if (blockMemory.lowMemory) {
            saveCurrentBlocks()
            pauseForResources(id, "Android reports low memory. The job is paused at its latest saved checkpoint.")
            return
          }
          val block = blocks.getJSONObject(index)
          database.updateJob(id, currentBlock = index)
          val sourceText = block.optString("sourceText", "").trim()
          val modelText = block.optString("modelText", sourceText).trim().ifEmpty { sourceText }
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
            if (useGemmaDocumentTitle && GemmaTranslationContext.beginCheckpointArtifactRepair(block)) {
              saveCurrentBlocks()
            }
            val responseJson = timing.measure("inference") { localTranslations.translate(modelText, glossary, translationContext, sourceLanguage, targetLanguage) }
            if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
            val response = JSONObject(responseJson)
            if (response.has("error") || response.optString("text").isBlank()) {
              val message = response.optString("error", "Translation was not produced.")
              if (isAllocationFailure(message)) {
                saveCurrentBlocks()
                pauseForResources(id, "The model ran out of working memory. The job is paused at its latest saved checkpoint.")
                return
              }
              block.put("translatedText", JSONObject.NULL).put("status", "failed")
                .put("error", message)
              partial = true
            } else {
              val rawTranslatedText = response.getString("text")
              val boundary = if (useGemmaDocumentTitle) GemmaTranslationContext.decodePromptBoundaryEnvelope(
                rawTranslatedText, sourceText, modelText,
              ) else GemmaTranslationContext.BoundaryDecode(rawTranslatedText, emptyList(), invalid = false)
              val translatedText = boundary.text.orEmpty()
              if (boundary.invalid || translatedText.isBlank()) {
                GemmaTranslationContext.rejectGeneratedPromptArtifact(block, rawTranslatedText)
                partial = true
              } else if (hasProtectedValueMismatch(modelText, translatedText)) {
                block.put("translatedText", translatedText).put("status", "partial")
                  .put("error", "Numbers, units, or part codes differ from the source; verify this translation.")
                partial = true
              } else {
                block.put("translatedText", translatedText).put("status", "translated")
                if (boundary.removedTokens.isNotEmpty()) {
                  block.put("gemmaPromptArtifactRawText", rawTranslatedText)
                    .put("gemmaPromptArtifactDecodedTokens", JSONArray(boundary.removedTokens))
                }
              }
            }
          } catch (error: Exception) {
            if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
            val message = error.message.orEmpty()
            if (isAllocationFailure(message)) {
              saveCurrentBlocks()
              pauseForResources(id, "The model could not allocate working memory. The job is paused at its latest saved checkpoint.")
              return
            }
            block.put("translatedText", JSONObject.NULL).put("status", "failed")
              .put("error", error.message ?: "Translation failed.")
            partial = true
          } catch (error: OutOfMemoryError) {
            saveCurrentBlocks()
            pauseForResources(id, "Android could not allocate working memory. The job is paused at its latest saved checkpoint.")
            return
          }
          database.recordBlockDuration(id, android.os.SystemClock.elapsedRealtime() - blockStartedAt)
          completedBlockCount++
          saveCurrentBlocks()
        }
        database.updateJob(id, stage = "layout", currentPage = pageIndex, clearCurrentBlock = true)
        var exportCheck: PageExportCheck? = null
        while (exportCheck == null) {
          try {
            exportCheck = timing.measure("pdf_page_validation") {
              validatePageExport(source, pageIndex, blocks, id, targetLanguage)
            }
          } catch (error: Exception) {
            val blockId = layoutFailureBlockId(error.message.orEmpty()) ?: throw error
            val blockIndex = (0 until blocks.length()).firstOrNull { blocks.getJSONObject(it).optString("id") == blockId }
              ?: throw error
            val persistedBlock = database.pageBlockArray(id, pageIndex).let { saved ->
              (0 until saved.length()).asSequence().mapNotNull(saved::optJSONObject)
                .firstOrNull { it.optString("id") == blockId }
            }
            check(persistedBlock != null && checkpointMatchesSource(persistedBlock, blocks.getJSONObject(blockIndex), gemmaDocumentTitle)) {
              "The source block changed while revising its PDF layout."
            }
            (error as? LayoutFitFailure)?.let { fitFailure ->
              LayoutRetryPolicy.recordFitFailure(blocks.getJSONObject(blockIndex), error.message.orEmpty(), fitFailure.feedback)
              saveCurrentBlocks()
            }
            when (retryBlockForLayout(
              id = id,
              owner = owner,
              generation = generation,
              pageIndex = pageIndex,
              blockIndex = blockIndex,
              blocks = blocks,
              glossary = glossary,
              context = pageContext,
              gemmaDocumentTitle = gemmaDocumentTitle,
              sourceLanguage = sourceLanguage,
              targetLanguage = targetLanguage,
              pageWidth = pageWidth.toDouble(),
              pageHeight = pageHeight.toDouble(),
              ocrFailure = ocrFailure,
              completedBlockCount = completedBlockCount,
              timing = timing,
              saveCheckpoint = { count ->
                timing.measure("checkpoint_write") {
                  database.savePage(id, pageIndex, "running", blocks.toString(), pageWidth.toDouble(), pageHeight.toDouble(),
                    ocrFailure, blocks.length(), knownCompletedBlocks = count)
                }
              },
            )) {
              LayoutRetryOutcome.RETRIED -> Unit
              LayoutRetryOutcome.PAUSED -> return
              LayoutRetryOutcome.EXHAUSTED -> throw error
            }
            database.updateJob(id, stage = "layout", currentPage = pageIndex, clearCurrentBlock = true)
          }
        }
        val validatedPage = requireNotNull(exportCheck)
        if (validatedPage.partial) partial = true
        val pageStatus = if (partial) "partial" else "completed"
        val pageError = PageExportIssues.pageError(ocrFailure, blocks, partial, exportCheck.issues)
        timing.measure("checkpoint_write") {
          database.savePage(id, pageIndex, pageStatus, blocks.toString(), pageWidth.toDouble(), pageHeight.toDouble(),
            pageError, blocks.length(), knownCompletedBlocks = completedBlockCount)
        }
        completed++
        database.updateJob(id, completedPages = completed)
        timing.flush()
      }
      if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
      database.updateJob(id, stage = "exporting", clearCurrentPage = true)
      ocrEngine?.close()
      ocrEngine = null
      activeOcrEngine = null
      NativeCore.releaseModel()
      updateNotification("Assembling and verifying the translated PDF…")
      val finalPdf = JobPdfExporter(this, database, timing = timing).getOrBuild(id, persistExport = false) { stopped.get() || Thread.currentThread().isInterrupted }
      withUnpublishedPdfCleanup(finalPdf) {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        if (!database.completeActiveJobWithExport(id, finalPdf.exportId, finalPdf.file.absolutePath,
            finalPdf.displayName, "application/pdf", finalPdf.partial)) throw InterruptedException()
      }
      updateNotification(if (finalPdf.partial) "Translation finished with pages that need review." else "Translation completed.")
    } catch (_: InterruptedException) {
      val canceled = stopRequestedJobId == id && stopRequestedCanceled
      database.transitionActiveJob(id, if (canceled) "canceled" else "paused",
        error = if (canceled) "Canceled by the user." else "Paused after the latest saved page checkpoint.")
    } catch (error: OutOfMemoryError) {
      pauseForResources(id, "Android could not allocate working memory. The job is paused at its latest saved checkpoint.")
    } catch (error: Exception) {
      if (stopped.get() || Thread.currentThread().isInterrupted) {
        val canceled = stopRequestedJobId == id && stopRequestedCanceled
        database.transitionActiveJob(id, if (canceled) "canceled" else "paused",
          error = if (canceled) "Canceled by the user." else "Paused after the latest saved page checkpoint.")
      } else if (database.transitionActiveJob(id, "failed", error = error.message ?: "Local translation failed.")) {
        updateNotification("Translation stopped. Open the app to review the error.")
      }
    } finally {
      try { cleanupJobWorker(id, ocrEngine, owner, generation) } finally { timing.flush() }
    }
  }

  /**
   * Translate only local Hy-MT PDF blocks through the optional native batch API.
   * Every native completion is synchronously checkpointed before it receives an ACK.
   */
  private fun translateHyMtPdfPage(
    id: String,
    owner: ExecutorService,
    generation: Long,
    pageIndex: Int,
    pageCount: Int,
    blocks: JSONArray,
    glossary: String,
    background: String,
    sourceLanguage: String,
    targetLanguage: String,
    maxSequences: Int,
    initialCompletedBlockCount: Int,
    initialPartial: Boolean,
    timing: ProcessingTiming,
    saveCheckpoint: (Int) -> Unit,
  ): HyMtBatchPageOutcome {
    var completedBlockCount = initialCompletedBlockCount
    var partial = initialPartial
    var nextBlockIndex = 0

    while (true) {
      if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
      val targetsByText = LinkedHashMap<String, HyMtBatchTarget>()
      val targetsById = LinkedHashMap<String, HyMtBatchTarget>()

      while (nextBlockIndex < blocks.length() && targetsById.size < maxSequences) {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        val index = nextBlockIndex++
        val block = blocks.getJSONObject(index)
        val sourceText = block.optString("sourceText", "").trim()
        val modelText = block.optString("modelText", sourceText).trim().ifEmpty { sourceText }
        if (sourceText.isEmpty()) {
          partial = true
          continue
        }
        if (block.optString("status") in setOf("translated", "partial", "failed", "skipped")) {
          if (block.optString("status") != "translated" && block.optString("status") != "skipped") partial = true
          continue
        }
        val cached = localTranslations.cached(modelText, glossary, background, sourceLanguage, targetLanguage)
        if (cached != null) {
          val response = JSONObject(cached)
          val translatedText = response.optString("text")
          if (translatedText.isNotBlank()) {
            val mismatch = hasProtectedValueMismatch(modelText, translatedText)
            block.put("translatedText", translatedText)
              .put("status", if (mismatch) "partial" else "translated")
              .put("cacheHit", true)
            if (mismatch) {
              block.put("error", "Numbers, units, or part codes differ from the source; verify this translation.")
              partial = true
            } else block.remove("error")
            completedBlockCount++
            timing.measure("batch_cache_checkpoint") { saveCheckpoint(completedBlockCount) }
            continue
          }
        }

        val existing = targetsByText[modelText]
        if (existing != null) {
          existing.blockIndexes.add(index)
        } else if (targetsById.size < maxSequences) {
          val requestId = "p${pageIndex}b$index"
          val target = HyMtBatchTarget(requestId, modelText, mutableListOf(index))
          targetsById[requestId] = target
          targetsByText[modelText] = target
        }
      }

      if (targetsById.isEmpty()) {
        if (nextBlockIndex >= blocks.length()) return HyMtBatchPageOutcome(completedBlockCount, partial, paused = false)
        continue
      }
      if (memoryInfoProvider().lowMemory) {
        saveCheckpoint(completedBlockCount)
        pauseForResources(id, "Android reports low memory. The job is paused at its latest saved checkpoint.")
        return HyMtBatchPageOutcome(completedBlockCount, partial, paused = true)
      }
      val requests = JSONArray().apply {
        targetsById.values.forEach { target -> put(JSONObject().put("id", target.id).put("text", target.text)) }
      }
      val acknowledged = LinkedHashSet<String>()
      val acknowledgedBlockIndexes = LinkedHashSet<Int>()
      database.updateJob(id, currentBlock = targetsById.values.first().blockIndexes.first())
      updateNotification("Translating page ${pageIndex + 1} of $pageCount · ${targetsById.size} blocks")

      val batchStartedAt = workerElapsedRealtime()
      val summaryJson = try {
        timing.measure("inference") {
          NativeCore.translateBatch(requests.toString(), glossary, background, sourceLanguage, targetLanguage,
            generation, BatchCompletion { responseJson ->
            val response = JSONObject(responseJson)
            val requestId = response.optString("id")
            val target = targetsById[requestId] ?: return@BatchCompletion 0
            if (requestId in acknowledged || response.optLong("generation", Long.MIN_VALUE) != generation) return@BatchCompletion 0
            if (!ownsJobWorker(owner, id, generation)) return@BatchCompletion 0
            if (stopRequestedJobId == id && stopRequestedCanceled) return@BatchCompletion 0
            val translatedText = response.optString("text")
            if (translatedText.isBlank()) return@BatchCompletion 0
            if (target.blockIndexes.any { index -> blocks.getJSONObject(index).optString("status") != "pending" }) return@BatchCompletion 0
            val previousBlocks = target.blockIndexes.associateWith { index -> JSONObject(blocks.getJSONObject(index).toString()) }
            target.blockIndexes.forEach { index ->
              val block = blocks.getJSONObject(index)
              val sourceText = block.optString("sourceText", "").trim()
              val modelText = block.optString("modelText", sourceText).trim().ifEmpty { sourceText }
              val mismatch = hasProtectedValueMismatch(modelText, translatedText)
              block.put("translatedText", translatedText)
                .put("status", if (mismatch) "partial" else "translated")
              if (mismatch) block.put("error", "Numbers, units, or part codes differ from the source; verify this translation.")
              else block.remove("error")
              if (mismatch) partial = true
            }
            if (stopRequestedJobId == id && stopRequestedCanceled) {
              previousBlocks.forEach { (index, block) -> blocks.put(index, block) }
              return@BatchCompletion 0
            }
            val nextCompletedCount = completedBlockCount + target.blockIndexes.size
            timing.measure(if (response.optBoolean("soloRetry")) "batch_retry_checkpoint" else "batch_checkpoint") {
              saveCheckpoint(nextCompletedCount)
            }
            completedBlockCount = nextCompletedCount
            acknowledged.add(requestId)
            acknowledgedBlockIndexes.addAll(target.blockIndexes)
            val memoResponse = JSONObject()
              .put("text", translatedText)
              .put("tokens", response.optInt("tokens", 0))
              .put("inputTokens", response.optInt("inputTokens", 0))
              .put("seconds", response.optDouble("seconds", 0.0))
            target.blockIndexes.forEach { index ->
              val block = blocks.getJSONObject(index)
              val sourceText = block.optString("sourceText", "").trim()
              val modelText = block.optString("modelText", sourceText).trim().ifEmpty { sourceText }
              localTranslations.remember(modelText, glossary, background, sourceLanguage, targetLanguage, memoResponse.toString())
            }
            if (stopped.get() || Thread.currentThread().isInterrupted) 2 else 1
          })
        }
      } finally {
        val elapsed = (workerElapsedRealtime() - batchStartedAt).coerceAtLeast(0L)
        recordBatchWallDuration(id, elapsed, acknowledgedBlockIndexes.size)
      }

      if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
      val summary = JSONObject(summaryJson)
      val expectedIds = targetsById.keys
      val committedIds = readBatchIds(summary.optJSONArray("committedIds"), "committedIds")
      val pendingIds = readBatchIds(summary.optJSONArray("pendingIds"), "pendingIds")
      val finalErrors = summary.optJSONArray("finalErrors") ?: error("Batch result is missing finalErrors.")
      check(summary.has("canceled")) { "Batch result is missing canceled state." }
      val errorIds = LinkedHashSet<String>()
      val errorById = LinkedHashMap<String, String>()
      for (index in 0 until finalErrors.length()) {
        val item = finalErrors.getJSONObject(index)
        val requestId = item.optString("id")
        check(requestId in expectedIds && errorIds.add(requestId)) { "Batch returned an unknown or duplicate final error." }
        errorById[requestId] = item.optString("error", "Translation was not produced.")
      }
      check(committedIds.intersect(pendingIds).isEmpty() && committedIds.intersect(errorIds).isEmpty() && pendingIds.intersect(errorIds).isEmpty()) {
        "Batch returned overlapping request outcomes."
      }
      check(committedIds + pendingIds + errorIds == expectedIds) { "Batch outcome omitted one or more requests." }
      check(committedIds == acknowledged) { "Batch commit summary did not match durable checkpoints." }

      var resourceFailure: String? = summary.optString("error").takeIf { it.isNotBlank() && it != "null" }
      for ((requestId, message) in errorById) {
        if (isAllocationFailure(message)) {
          resourceFailure = message
          continue
        }
        val target = targetsById.getValue(requestId)
        target.blockIndexes.forEach { index ->
          blocks.getJSONObject(index).put("translatedText", JSONObject.NULL)
            .put("status", "failed").put("error", message)
        }
        completedBlockCount += target.blockIndexes.size
        partial = true
        timing.measure("batch_error_checkpoint") { saveCheckpoint(completedBlockCount) }
      }
      if (pendingIds.isNotEmpty() || resourceFailure != null || summary.optBoolean("canceled")) {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        val message = resourceFailure?.let { if (isAllocationFailure(it)) "The batch model ran out of working memory. The job is paused at its latest saved checkpoint." else "Batch inference stopped before all blocks were saved. Resume to retry the remaining blocks." }
          ?: "Batch inference stopped before all blocks were saved. Resume to retry the remaining blocks."
        pauseForResources(id, message)
        return HyMtBatchPageOutcome(completedBlockCount, partial, paused = true)
      }
    }
  }

  /** Apportion real batch wall time only across blocks whose checkpoint callback completed. */
  private fun recordBatchWallDuration(id: String, elapsedMillis: Long, committedBlocks: Int) {
    if (committedBlocks <= 0) return
    val base = elapsedMillis / committedBlocks
    val remainder = elapsedMillis % committedBlocks
    repeat(committedBlocks) { index ->
      runCatching { database.recordBlockDuration(id, base + if (index.toLong() < remainder) 1L else 0L) }
    }
  }

  private fun readBatchIds(values: JSONArray?, field: String): Set<String> {
    require(values != null) { "Batch result is missing $field." }
    val result = LinkedHashSet<String>()
    for (index in 0 until values.length()) {
      val value = values.optString(index)
      require(value.isNotBlank() && result.add(value)) { "Batch result contains an invalid or duplicate $field entry." }
    }
    return result
  }

  /** DOCX local jobs and online jobs use stable native text segments and durable per-fragment checkpoints. */
  private fun runSegmentsJob(id: String, job: JSONObject, source: String, ocrEngine: OcrEngine?, ocrSetupFailure: String?, timing: ProcessingTiming) {
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
        val memory = memoryInfoProvider()
        if (memory.lowMemory) {
          pauseForResources(id, "Android reports low memory. The job is paused at its latest saved checkpoint.")
          return
        }
        database.updateJob(id, stage = "loadingModel")
        updateNotification("Loading translation model…")
        val modelFile = downloader.fileFor(modelId)
        setWeightRepackingAllowed(WeightRepackingPolicy.shouldAllow(modelFile.length(), memory.totalMem))
        try {
          val loaded = JSONObject(timing.measure("model_load") { NativeCore.loadModel(modelFile.absolutePath, 2048, 4, model.profile) })
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
      if (priorPage?.optString("status") in setOf("completed", "partial")) {
        database.backfillPageBlockProgress(id, index)
        continue
      }
      if (!online && memoryInfoProvider().lowMemory) {
        pauseForResources(id, "Android reports low memory. The job is paused at its latest saved checkpoint.")
        return
      }
      database.updateJob(id, stage = "extracting", currentPage = index, clearCurrentBlock = true)
      updateNotification("Reading ${if (format == "docx") "Word segment" else "PDF page"} ${index + 1} of $pageCount")
      val raw = JSONObject(if (format == "docx") {
        timing.measure("docx_read") { NativeCore.readDocxSegment(source, index) }
      } else timing.measure("pdf_read") { NativeCore.readPage(source, index) })
      if (raw.has("error")) error(raw.optString("error", "The document segment could not be read."))
      val width = raw.optDouble("width", 612.0)
      val height = raw.optDouble("height", 792.0)
      val nativeBlocks = raw.optJSONArray("blocks") ?: JSONArray()
      var extractionWarning = ocrSetupFailure
      val blocks = if (online && format == "pdf") {
        timing.measure("ocr") {
          val bitmap = try { NativeCore.render(source, index, OCR_RENDER_WIDTH) }
          catch (failure: Exception) {
            extractionWarning = failure.message ?: "The PDF page could not be rendered for text recognition."
            null
          }
          try {
            combineNativeAndOcrWithArtwork(nativeBlocks, bitmap, width.toFloat(), height.toFloat(), ocrEngine,
              raw.optJSONArray("artworkRects") ?: JSONArray())
          } catch (failure: Exception) {
            extractionWarning = failure.message ?: "On-device OCR could not recognize this PDF page."
            combineNativeAndOcrWithArtwork(nativeBlocks, null, width.toFloat(), height.toFloat(), null,
              raw.optJSONArray("artworkRects") ?: JSONArray())
          } finally { bitmap?.recycle() }
        }
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
      timing.measure("checkpoint_write") { database.savePage(id, index, "running", blocks.toString(), width, height, expectedBlocks = blocks.length()) }
      database.updateJob(id, stage = "translating", currentPage = index)
      var partial = extractionWarning != null
      for (bi in 0 until blocks.length()) {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        if (!online && memoryInfoProvider().lowMemory) {
          pauseForResources(id, "Android reports low memory. The job is paused at its latest saved checkpoint.")
          return
        }
        val block = blocks.getJSONObject(bi)
        database.updateJob(id, currentBlock = bi)
        if (block.optString("status") in setOf("translated", "partial", "skipped")) {
          if (block.optString("status") == "partial") partial = true
          continue
        }
        val text = block.getString("sourceText")
        val blockStartedAt = android.os.SystemClock.elapsedRealtime()
        try {
          updateNotification("Translating segment ${index + 1} of $pageCount · block ${bi + 1}/${blocks.length()}")
          val translated = if (online) timing.measure("online_translation") { translator!!.translate(text, sourceLanguage, targetLanguage,
            block.optJSONArray("translatedFragments") ?: JSONArray()) { fragmentIndex, fragmentSource, fragmentText ->
            // A network response may race the asynchronous translator disconnect.
            // The stop flag is synchronous, so never checkpoint a fragment after
            // Pause/Cancel has already been accepted by this service.
            if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
            val fragments = block.optJSONArray("translatedFragments") ?: JSONArray()
            val replacement = JSONArray()
            for (fi in 0 until fragments.length()) if (fragments.getJSONObject(fi).optInt("index") != fragmentIndex) replacement.put(fragments.getJSONObject(fi))
            replacement.put(JSONObject().put("index", fragmentIndex).put("source", fragmentSource).put("translated", fragmentText))
            block.put("translatedFragments", replacement)
            timing.measure("checkpoint_write") { database.savePage(id, index, "running", blocks.toString(), width, height, expectedBlocks = blocks.length()) }
          } } else {
            val resultJson = timing.measure("inference") { localTranslations.translate(text, database.jobGlossaryText(id), "", sourceLanguage, targetLanguage) }
            if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
            val result = JSONObject(resultJson)
            if (result.has("error") || result.optString("text").isBlank()) error(result.optString("error", "Translation was not produced."))
            result.getString("text")
          }
          block.put("translatedText", translated).put("status", "translated").remove("error")
        } catch (failure: MyMemoryException) {
          if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
          timing.measure("checkpoint_write") { database.savePage(id, index, "running", blocks.toString(), width, height, expectedBlocks = blocks.length()) }
          if (database.transitionActiveJob(id, "paused", stage = "translating", error = failure.message)) {
            updateNotification("Online translation paused. Open the app to review and resume.")
          }
          return
        } catch (interrupted: InterruptedException) {
          throw interrupted
        } catch (failure: Exception) {
          if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
          if (!online && isAllocationFailure(failure.message.orEmpty())) {
            timing.measure("checkpoint_write") { database.savePage(id, index, "running", blocks.toString(), width, height, expectedBlocks = blocks.length()) }
            pauseForResources(id, "The model could not allocate working memory. The job is paused at its latest saved checkpoint.")
            return
          }
          block.put("translatedText", JSONObject.NULL).put("status", "failed").put("error", failure.message ?: "Translation failed.")
          partial = true
        }
        database.recordBlockDuration(id, android.os.SystemClock.elapsedRealtime() - blockStartedAt)
        timing.measure("checkpoint_write") { database.savePage(id, index, "running", blocks.toString(), width, height, expectedBlocks = blocks.length()) }
      }
      val warnings = raw.optJSONArray("warnings")
      val warningSet = linkedSetOf<String>()
      extractionWarning?.takeIf { it.isNotBlank() }?.let(warningSet::add)
      warnings?.let { list -> for (wi in 0 until list.length()) list.optString(wi).trim().takeIf { it.isNotBlank() && it != "null" }?.let(warningSet::add) }
      val warningText = warningSet.joinToString("; ")
      val pageStatus = if (partial || (0 until blocks.length()).any { blocks.optJSONObject(it)?.optString("status") != "translated" }) "partial" else "completed"
      timing.measure("checkpoint_write") { database.savePage(id, index, pageStatus, blocks.toString(), width, height, warningText.ifBlank { null }, blocks.length()) }
      completed++
      database.updateJob(id, completedPages = completed)
      timing.flush()
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
            timing.measure("checkpoint_write") {
              database.savePage(id, 0, first.getString("status"), first.getJSONArray("blocks").toString(),
                first.optDouble("width"), first.optDouble("height"), combined.ifBlank { null }, first.optInt("expectedBlocks", 1))
            }
          }
        }
        val partial = result.optBoolean("partial")
        if (!database.completeActiveJobWithExport(id, id, output.absolutePath,
            job.optString("documentName", "translated.docx").removeSuffix(".docx") + "-translated.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", partial)) throw InterruptedException()
      }
    } else {
      val result = JobPdfExporter(this, database, timing = timing).getOrBuild(id, persistExport = false) { stopped.get() || Thread.currentThread().isInterrupted }
      withUnpublishedPdfCleanup(result) {
        if (stopped.get() || Thread.currentThread().isInterrupted ||
            !database.completeActiveJobWithExport(id, result.exportId, result.file.absolutePath,
              result.displayName, "application/pdf", result.partial)) throw InterruptedException()
      }
    }
  }

  /** A canceled/failed final commit must not leave a newly created private result. */
  private fun withUnpublishedPdfCleanup(result: FinalizedJobPdf, action: () -> Unit) {
    try { action() } catch (failure: Throwable) {
      JobPdfExporter(this, database).deleteUnpublished(result)
      throw failure
    }
  }

  private fun withUnpublishedExportCleanup(exportId: String, output: File, action: () -> Unit) {
    val wasPublished = database.exportInfo(exportId)?.path == output.absolutePath
    try {
      action()
    } catch (failure: Throwable) {
      if (!wasPublished) output.delete()
      throw failure
    }
  }

  private fun cleanupJobWorker(id: String, ocrEngine: OcrEngine?, owner: ExecutorService, generation: Long) {
    ocrEngine?.close()
    if (!ownsJobWorker(owner, id, generation)) {
      owner.shutdown()
      return
    }
    localTranslations.clear()
    if (activeOcrEngine === ocrEngine) activeOcrEngine = null
    activeMyMemoryTranslator = null
    if (qaProfileEnabled() && database.jobJson(id)?.optString("status") in setOf("completed", "partial", "failed", "canceled")) {
      getSharedPreferences(QA_PROFILE_PREFS, MODE_PRIVATE).edit()
        .remove(QA_PROFILE_REGISTERED_JOB).apply()
    }
    runCatching { NativeCore.releaseModel() }
    finishWorker(owner, id, generation)
  }

  /** Fence a queued, not-yet-entered job before releasing its process-wide native model. */
  private fun abortUnenteredJobWorker(id: String, generation: Long, owner: ExecutorService, ocr: OcrEngine?) {
    owner.shutdownNow()
    if (!ownsJobLease(owner, id, generation)) {
      ocr?.close()
      return
    }
    runCatching {
      database.transitionActiveJob(id, "paused", error = "Paused after the latest saved page checkpoint.", clearCurrentBlock = true)
    }
    ocr?.close()
    localTranslations.clear()
    if (activeOcrEngine === ocr) activeOcrEngine = null
    activeMyMemoryTranslator = null
    runCatching { NativeCore.releaseModel() }
    executor = null
    jobId = null
    modelId = null
    stopped.set(true)
    clearWorkerOwnership(id, generation)
    isActive = false
  }

  internal fun finishWorker(owner: ExecutorService) {
    finishWorker(owner, jobId, workerToken)
  }

  private fun finishWorker(owner: ExecutorService, finishedJobId: String?, generation: Long) {
    mainHandler.post {
      owner.shutdown()
      if (finishedJobId == null) {
        if (executor !== owner) return@post
        if (!serviceDestroyed) stopForeground(STOP_FOREGROUND_DETACH)
        executor = null
        jobId = null
        modelId = null
        stopped.set(true)
        isActive = false
        if (!serviceDestroyed) stopSelfForCompletedWorker(latestStartId)
        return@post
      }
      if (!ownsJobWorker(owner, finishedJobId, generation)) return@post
      if (!serviceDestroyed) stopForeground(STOP_FOREGROUND_DETACH)
      executor=null
      jobId=null
      modelId=null
      clearWorkerOwnership(finishedJobId, generation)
      stopped.set(true)
      stopRequestedJobId = null
      stopRequestedCanceled = false
      isActive = false
      if (!serviceDestroyed) {
        stopSelfForCompletedWorker(latestStartId)
      }
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
      message.contains("cannot allocate", ignoreCase = true) ||
      message.contains("could not allocate", ignoreCase = true)

  private fun layoutFailureBlockId(message: String): String? {
    if (!message.startsWith(LAYOUT_FAILURE_PREFIX)) return null
    return message.removePrefix(LAYOUT_FAILURE_PREFIX).takeIf { candidate ->
      candidate.isNotBlank() && candidate.none(Char::isWhitespace)
    }
  }

  private fun combineNativeAndOcr(nativeBlocks: JSONArray, bitmap: Bitmap?, pageWidth: Float, pageHeight: Float,
                                  ocrEngine: OcrEngine?): JSONArray {
    return combineNativeAndOcrInternal(nativeBlocks, bitmap, pageWidth, pageHeight, ocrEngine, JSONArray())
  }

  private fun combineNativeAndOcrWithArtwork(nativeBlocks: JSONArray, bitmap: Bitmap?, pageWidth: Float, pageHeight: Float,
                                             ocrEngine: OcrEngine?, artworkRects: JSONArray): JSONArray =
    combineNativeAndOcrInternal(nativeBlocks, bitmap, pageWidth, pageHeight, ocrEngine, artworkRects)

  private fun combineNativeAndOcrInternal(nativeBlocks: JSONArray, bitmap: Bitmap?, pageWidth: Float, pageHeight: Float,
                                           ocrEngine: OcrEngine?, artworkRects: JSONArray): JSONArray {
    if (bitmap == null || ocrEngine == null) return nativeBlockSnapshots(nativeBlocks)
    return combineNativeAndRecognizedOcr(
      nativeBlocks,
      ocrEngine.recognize(bitmap, pageWidth, pageHeight),
      pageWidth,
      pageHeight,
      artworkRects,
    )
  }

  /** Merge one already-recognized OCR pass into native source blocks. */
  private fun combineNativeAndRecognizedOcr(
    nativeBlocks: JSONArray,
    ocr: JSONArray,
    pageWidth: Float,
    pageHeight: Float,
    artworkRects: JSONArray,
  ): JSONArray {
    val blocks = nativeBlockSnapshots(nativeBlocks)
    val nativeLines = nativeSourceLines(blocks)
    val preservedIllustrations = preservedIllustrationsForPage(nativeBlocks, artworkRects, pageWidth, pageHeight)
    for (index in 0 until ocr.length()) {
      val line = ocr.getJSONObject(index)
      if (isOcrOwnedByPreservedIllustration(line, preservedIllustrations)) continue
      val match = matchingNativeBlock(nativeLines,
        line.optDouble("left").toFloat(), line.optDouble("top").toFloat(),
        line.optDouble("right").toFloat(), line.optDouble("bottom").toFloat(), line.optString("sourceText"))
      if (match != null) {
        if (match.verifiedTocLeaderNoise && match.blocks.size == 1) {
          match.blocks.single().put("verifiedTocLeaderOcrNoise", true)
        }
        if (!match.containedUrlDuplicate && !match.verifiedTocLeaderNoise &&
          OcrOutcome.disagrees(match.fragmentText, line.optString("sourceText"))) {
          match.blocks.forEach { block ->
            block.put("sourceDisagreement", true).put("error", OcrOutcome.SOURCE_DISAGREEMENT_BLOCK_WARNING)
          }
        }
      } else {
        blocks.put(line)
      }
    }
    return blocks
  }

  private fun nativeBlockSnapshots(nativeBlocks: JSONArray): JSONArray {
    val blocks = JSONArray()
    for (index in 0 until nativeBlocks.length()) {
      val raw = nativeBlocks.getJSONObject(index)
      // Keep the display/source text separate from the model-prepared text.
      // Native symbol tokens and their metadata must survive the OCR merge so
      // local PDF translation and its memo cache use the same marker-bearing
      // model input while checkpoints retain the original source.
      val sourceText = raw.optString("sourceText", raw.optString("text", "")).trim()
      if (sourceText.isBlank()) continue
      val block = JSONObject()
        .put("id", raw.optString("id", "native-$index")).put("sourceText", sourceText)
        .put("translatedText", JSONObject.NULL).put("left", raw.optDouble("left"))
        .put("top", raw.optDouble("top")).put("right", raw.optDouble("right"))
        .put("bottom", raw.optDouble("bottom")).put("fontSize", raw.optDouble("fontSize", 10.0))
        .put("source", "native").put("objectIndex", if (raw.isNull("objectIndex")) JSONObject.NULL else raw.optInt("objectIndex"))
        .put("objectIndices", raw.optJSONArray("objectIndices") ?: JSONArray().apply { if (!raw.isNull("objectIndex")) put(raw.optInt("objectIndex")) })
        .put("status", "pending")
      if (raw.has("modelText")) block.put("modelText", raw.get("modelText"))
      if (raw.has("symbols")) block.put("symbols", raw.get("symbols"))
      if (raw.has("sourceRects")) block.put("sourceRects", raw.get("sourceRects"))
      if (raw.has("sourceSpans")) block.put("sourceSpans", raw.get("sourceSpans"))
      if (raw.has("coordinateSpaceVersion")) block.put("coordinateSpaceVersion", raw.get("coordinateSpaceVersion"))
      if (raw.has("partialCrop")) block.put("partialCrop", raw.get("partialCrop"))
      blocks.put(block)
    }
    return blocks
  }

  private fun isOcrOwnedByPreservedIllustration(
    ocr: JSONObject,
    artworkRects: List<PreservedIllustrationBounds>,
  ): Boolean {
    if (ocr.optString("source") != "ocr" || artworkRects.isEmpty()) return false
    val left = ocr.optDouble("left", Double.NaN)
    val top = ocr.optDouble("top", Double.NaN)
    val right = ocr.optDouble("right", Double.NaN)
    val bottom = ocr.optDouble("bottom", Double.NaN)
    val ocrWidth = right - left
    val ocrHeight = bottom - top
    val ocrArea = ocrWidth * ocrHeight
    if (!listOf(left, top, right, bottom, ocrArea).all(Double::isFinite) || ocrWidth <= 0 || ocrHeight <= 0) return false
    for (image in artworkRects) {
      val imageLeft = image.left
      val imageTop = image.top
      val imageRight = image.right
      val imageBottom = image.bottom
      val imageWidth = imageRight - imageLeft
      val imageHeight = imageBottom - imageTop
      val imageArea = imageWidth * imageHeight

      val expandedLeft = imageLeft - IMAGE_RASTER_TOLERANCE_PT
      val expandedTop = imageTop - IMAGE_RASTER_TOLERANCE_PT
      val expandedRight = imageRight + IMAGE_RASTER_TOLERANCE_PT
      val expandedBottom = imageBottom + IMAGE_RASTER_TOLERANCE_PT
      val overlapWidth = (minOf(right, expandedRight) - maxOf(left, expandedLeft)).coerceAtLeast(0.0)
      val overlapHeight = (minOf(bottom, expandedBottom) - maxOf(top, expandedTop)).coerceAtLeast(0.0)
      if ((overlapWidth * overlapHeight) / ocrArea >= MIN_IMAGE_OCR_COVERAGE) return true
    }
    return false
  }

  private fun preservedIllustrationsForPage(
    nativeBlocks: JSONArray,
    artworkRects: JSONArray,
    pageWidth: Float,
    pageHeight: Float,
  ): List<PreservedIllustrationBounds> {
    if (!pageWidth.isFinite() || !pageHeight.isFinite() || pageWidth <= 0f || pageHeight <= 0f) return emptyList()
    if (nonPictogramNativeLetterCount(nativeBlocks) < MIN_NATIVE_PAGE_LETTERS) return emptyList()
    val pageArea = pageWidth.toDouble() * pageHeight.toDouble()
    if (!pageArea.isFinite() || pageArea <= 0) return emptyList()

    return buildList {
      for (index in 0 until artworkRects.length()) {
        val image = artworkRects.optJSONObject(index) ?: continue
        val coordinateVersion = image.opt("coordinateSpaceVersion") as? Number ?: continue
        if (image.optString("type") != "image" || coordinateVersion.toDouble() != 2.0) continue
        val objectIndexValue = image.opt("objectIndex") as? Number ?: continue
        val objectIndex = objectIndexValue.toDouble()
        if (!objectIndex.isFinite() || objectIndex < 0 || objectIndex % 1.0 != 0.0) continue
        val left = image.optDouble("left", Double.NaN)
        val top = image.optDouble("top", Double.NaN)
        val right = image.optDouble("right", Double.NaN)
        val bottom = image.optDouble("bottom", Double.NaN)
        val width = right - left
        val height = bottom - top
        val area = width * height
        if (!listOf(left, top, right, bottom, width, height, area).all(Double::isFinite) ||
          left < 0 || top < 0 || right > pageWidth || bottom > pageHeight ||
          width <= 0 || height <= 0 || area <= 0 || area > pageArea * MAX_ILLUSTRATION_PAGE_AREA) continue
        add(PreservedIllustrationBounds(left, top, right, bottom))
      }
    }
  }

  private fun nonPictogramNativeLetterCount(nativeBlocks: JSONArray): Int {
    var letters = 0
    for (index in 0 until nativeBlocks.length()) {
      val block = nativeBlocks.optJSONObject(index) ?: continue
      val sourceRects = block.optJSONArray("sourceRects")
      if (sourceRects == null) {
        letters += block.optString("sourceText", block.optString("text")).count(Char::isLetter)
      } else {
        for (rectIndex in 0 until sourceRects.length()) {
          val rect = sourceRects.optJSONObject(rectIndex) ?: continue
          if (!rect.optBoolean("pictogram")) letters += rect.optString("text").count(Char::isLetter)
        }
      }
      if (letters >= MIN_NATIVE_PAGE_LETTERS) return letters
    }
    return letters
  }

  private fun hasProtectedValueMismatch(source: String, translated: String): Boolean =
    ProtectedValueGuard.hasMismatch(source, translated)

  private fun checkpointMatchesSource(saved: JSONObject, fresh: JSONObject, gemmaDocumentTitle: String? = null): Boolean {
    if (saved.optString("sourceText").trim() != fresh.optString("sourceText").trim()) return false
    if (effectiveModelText(saved) != effectiveModelText(fresh)) return false
    if (orderedObjectIndices(saved) != orderedObjectIndices(fresh)) return false
    if (symbolInventory(saved) != symbolInventory(fresh)) return false
    return gemmaDocumentTitle == null || GemmaTranslationContext.checkpointMatches(saved, fresh, gemmaDocumentTitle)
  }

  /** Inspect completed Gemma pages once at worker start so only a page with a known leaked boundary is reopened. */
  private fun pageContainsPromptArtifact(jobId: String, pageIndex: Int): Boolean {
    val saved = database.pageBlockArray(jobId, pageIndex)
    return (0 until saved.length()).any { index ->
      val block = saved.optJSONObject(index) ?: return@any false
      GemmaTranslationContext.hasLeakedPromptBoundary(
        block.optString("translatedText"), block.optString("sourceText"), effectiveModelText(block),
      )
    }
  }

  /** Reuse a durable model result while keeping geometry and extraction metadata from this read. */
  private fun restoreDurableTranslationResult(saved: JSONObject, fresh: JSONObject) {
    val resolveTocWarning = canResolveSavedTocDisagreement(saved, fresh)
    if (saved.has("translatedText")) fresh.put("translatedText", saved.opt("translatedText"))
    val savedStatus = saved.optString("status")
    if (savedStatus in setOf("translated", "partial", "failed")) {
      // A disagreement found by the fresh OCR pass remains a review issue even
      // when the saved model result itself had no translation warning.
      fresh.put("status", if (fresh.optBoolean("sourceDisagreement") && savedStatus == "translated") "partial" else savedStatus)
    }
    if (!resolveTocWarning && saved.optBoolean("sourceDisagreement")) {
      fresh.put("sourceDisagreement", true)
      if (savedStatus == "translated") fresh.put("status", "partial")
    }
    val freshError = fresh.optString("error").takeIf { it.isNotBlank() && it != "null" }
    val savedError = saved.optString("error").takeIf { it.isNotBlank() && it != "null" }
    if (freshError != null) fresh.put("error", freshError)
    else if (!resolveTocWarning && savedError != null) fresh.put("error", savedError)
    else fresh.remove("error")
    listOf(
      "layoutRetryCount", "layoutRetryPolicyVersion", "layoutRetryLegacyCount", "layoutRetryHistory",
      "layoutInitialFitError", "layoutRetryError", "translationReviewRequired",
      "gemmaPromptArtifactRepairRevision", "gemmaPromptArtifactRepairPendingRevision",
      "gemmaPromptArtifactRejectedText", "gemmaPromptArtifactRawText", "gemmaPromptArtifactDecodedTokens",
      "sourceDisagreementResolution",
    ).forEach { key ->
      if (saved.has(key)) fresh.put(key, saved.opt(key))
    }
    if (resolveTocWarning) {
      fresh.put("sourceDisagreementResolution", JSONObject()
        .put("reason", "verified_toc_dotted_leader_ocr_noise")
        .put("warning", OcrOutcome.SOURCE_DISAGREEMENT_BLOCK_WARNING))
      fresh.put("status", "translated")
      fresh.remove("sourceDisagreement")
      fresh.remove("error")
    }
    // Layout feedback is geometry-specific. A new extraction must get fresh feedback
    // from the native source-preserving validator before it can drive another retry.
    fresh.remove("layoutFeedback")
    // An in-flight marker belongs to the dead worker that wrote the checkpoint.
    fresh.remove("layoutRetryInProgress")
  }

  private fun canResolveSavedTocDisagreement(saved: JSONObject, fresh: JSONObject): Boolean {
    val savedError = saved.optString("error")
    val exportIssues = saved.optJSONArray("exportIssues")
    val noExportIssues = !saved.has("exportIssues") || saved.isNull("exportIssues") ||
      exportIssues?.length() == 0
    return fresh.optBoolean("verifiedTocLeaderOcrNoise") && !fresh.optBoolean("sourceDisagreement") &&
      fresh.optString("error").isBlank() && !fresh.optBoolean("translationReviewRequired") &&
      saved.optBoolean("sourceDisagreement") && saved.optString("status") == "partial" &&
      savedError == OcrOutcome.SOURCE_DISAGREEMENT_BLOCK_WARNING &&
      saved.optString("translatedText").isNotBlank() && saved.optString("translatedText") != "null" &&
      !saved.optBoolean("translationReviewRequired") && noExportIssues &&
      saved.optString("layoutRetryError").isBlank() &&
      !saved.has("gemmaPromptArtifactRepairPendingRevision") &&
      !saved.has("gemmaPromptArtifactRejectedText")
  }

  private fun effectiveModelText(block: JSONObject): String =
    block.optString("modelText", block.optString("sourceText")).trim().ifEmpty { block.optString("sourceText").trim() }

  private fun orderedObjectIndices(block: JSONObject): List<Int> {
    val indices = block.optJSONArray("objectIndices")
    if (indices != null) return (0 until indices.length()).map { indices.optInt(it, Int.MIN_VALUE) }
    return if (!block.isNull("objectIndex") && block.has("objectIndex")) listOf(block.optInt("objectIndex")) else emptyList()
  }

  private fun symbolInventory(block: JSONObject): List<Pair<String, Int>> {
    val symbols = block.optJSONArray("symbols") ?: return emptyList()
    return (0 until symbols.length()).mapNotNull { index ->
      val symbol = symbols.optJSONObject(index) ?: return@mapNotNull null
      val token = symbol.optString("token")
      if (token.isBlank()) null else token to symbol.optInt("objectIndex", Int.MIN_VALUE)
    }
  }

  private fun validatePageExport(source: String, pageIndex: Int, blocks: JSONArray, jobId: String, targetLanguage: String): PageExportCheck {
    val validationDirectory = File(cacheDir, "page-validation").apply { mkdirs() }
    val destination = File(validationDirectory, "$jobId-$pageIndex.pdf")
    val font = File(cacheDir, FontAsset.DEFAULT_CACHE_FILENAME)
    FontAsset.ensure(this, font)
    // The native source-preserving renderer may need CJK fallback glyphs even
    // for non-Chinese targets (for example U+2212 in Russian technical text).
    val cjkFontPath = FontAsset.ensureCjk(this, File(cacheDir, "NotoSansSC-Regular.ttf")).absolutePath
    try {
      val request = JSONObject().put("layoutVersion", PdfLayoutReceipt.LAYOUT_VERSION)
        .put("mode", "preserve-source").put("sourcePageIndex", pageIndex).put("blocks", blocks)
      val result = JSONObject(NativeCore.exportPage(source, pageIndex, request.toString(), destination.absolutePath, font.absolutePath, cjkFontPath))
      if (result.has("error")) {
        val message = result.optString("error", "Page layout validation failed.")
        val failedId = layoutFailureBlockId(message)
        val feedback = result.optJSONObject("layoutFeedback")
          ?.takeIf { failedId != null && it.optString("blockId") == failedId }
        if (failedId != null) throw LayoutFitFailure(message, feedback)
        throw IllegalStateException(message)
      }
      require(destination.isFile && destination.length() > 0L) { "Page layout validation produced no PDF output." }
      val issues = result.optJSONArray("issues") ?: JSONArray()
      PageExportIssues.applyToBlocks(blocks, issues)
      for (index in 0 until blocks.length()) blocks.optJSONObject(index)?.remove("layoutFeedback")
      return PageExportCheck(result.optBoolean("partial") || issues.length() > 0, issues)
    } finally { destination.delete() }
  }

  private data class PageExportCheck(val partial: Boolean, val issues: JSONArray)

  private class LayoutFitFailure(message: String, val feedback: JSONObject?) : IllegalStateException(message)

  private enum class LayoutRetryOutcome { RETRIED, PAUSED, EXHAUSTED }

  private fun retryBlockForLayout(
    id: String,
    owner: ExecutorService,
    generation: Long,
    pageIndex: Int,
    blockIndex: Int,
    blocks: JSONArray,
    glossary: String,
    context: String,
    gemmaDocumentTitle: String?,
    sourceLanguage: String,
    targetLanguage: String,
    pageWidth: Double,
    pageHeight: Double,
    ocrFailure: String?,
    completedBlockCount: Int,
    timing: ProcessingTiming,
    saveCheckpoint: (Int) -> Unit,
  ): LayoutRetryOutcome {
    if (stopped.get() || Thread.currentThread().isInterrupted || !ownsJobWorker(owner, id, generation)) {
      throw InterruptedException()
    }
    val block = blocks.getJSONObject(blockIndex)
    val persistedBlock = database.pageBlockArray(id, pageIndex).let { saved ->
      (0 until saved.length()).asSequence().mapNotNull(saved::optJSONObject)
        .firstOrNull { it.optString("id") == block.optString("id") }
    }
    check(persistedBlock != null && checkpointMatchesSource(persistedBlock, block, gemmaDocumentTitle)) {
      "The source block changed while revising its PDF layout."
    }
    if (!LayoutRetryPolicy.hasRemaining(block)) return LayoutRetryOutcome.EXHAUSTED
    val sourceText = block.optString("sourceText", "").trim()
    val modelText = block.optString("modelText", sourceText).trim().ifEmpty { sourceText }
    if (modelText.isBlank()) return LayoutRetryOutcome.EXHAUSTED
    val memory = memoryInfoProvider()
    if (memory.lowMemory) {
      saveCheckpoint(completedBlockCount)
      pauseForResources(id, "Android reports low memory while revising text layout. The job is paused at its latest saved checkpoint.")
      return LayoutRetryOutcome.PAUSED
    }

    val retry = LayoutRetryPolicy.beginAttempt(block, block.optJSONObject("layoutFeedback"))
      ?: return LayoutRetryOutcome.EXHAUSTED
    val previousTarget = block.optString("translatedText")
    database.updateJob(id, stage = "translating", currentPage = pageIndex, currentBlock = blockIndex)
    updateNotification("Revising page ${pageIndex + 1} text to fit its original layout · block ${blockIndex + 1}/${blocks.length()}")
    block.put("layoutRetryInProgress", true)
    saveCheckpoint(completedBlockCount)
    val retryStartedAt = workerElapsedRealtime()
    val responseJson = try {
      timing.measure("layout_retry_inference") {
        NativeCore.translateForLayout(modelText, glossary, gemmaDocumentTitle ?: context,
          sourceLanguage, targetLanguage, LayoutRetryPolicy.nativeOptions(retry))
      }
    } catch (error: OutOfMemoryError) {
      if (stopped.get() || Thread.currentThread().isInterrupted) {
        if (ownsJobWorker(owner, id, generation)) {
          block.remove("layoutRetryInProgress")
          saveCheckpoint(completedBlockCount)
        }
        throw InterruptedException()
      }
      block.remove("layoutRetryInProgress")
      block.put("layoutRetryError", "The model ran out of working memory during layout revision.")
      LayoutRetryPolicy.appendHistory(block, retry, previousTarget, null,
        "The model ran out of working memory during layout revision.",
        (workerElapsedRealtime() - retryStartedAt).coerceAtLeast(0L) / 1_000.0)
      saveCheckpoint(completedBlockCount)
      pauseForResources(id, "Android could not allocate working memory while revising text layout. The job is paused at its latest saved checkpoint.")
      return LayoutRetryOutcome.PAUSED
    } catch (error: Exception) {
      if (stopped.get() || Thread.currentThread().isInterrupted) {
        if (ownsJobWorker(owner, id, generation)) {
          block.remove("layoutRetryInProgress")
          saveCheckpoint(completedBlockCount)
        }
        throw InterruptedException()
      }
      if (isAllocationFailure(error.message.orEmpty())) {
        block.remove("layoutRetryInProgress")
        block.put("layoutRetryError", error.message ?: "The model ran out of working memory during layout revision.")
        LayoutRetryPolicy.appendHistory(block, retry, previousTarget, null,
          error.message ?: "The model ran out of working memory during layout revision.",
          (workerElapsedRealtime() - retryStartedAt).coerceAtLeast(0L) / 1_000.0)
        saveCheckpoint(completedBlockCount)
        pauseForResources(id, "The model could not allocate working memory while revising text layout. The job is paused at its latest saved checkpoint.")
        return LayoutRetryOutcome.PAUSED
      }
      JSONObject().put("error", error.message ?: "Layout revision failed.").toString()
    }
    val elapsed = (workerElapsedRealtime() - retryStartedAt).coerceAtLeast(0L)
    if (stopped.get() || Thread.currentThread().isInterrupted) {
      if (ownsJobWorker(owner, id, generation)) {
        block.remove("layoutRetryInProgress")
        saveCheckpoint(completedBlockCount)
      }
      throw InterruptedException()
    }
    if (!ownsJobWorker(owner, id, generation)) {
      throw InterruptedException()
    }
    val response = runCatching { JSONObject(responseJson) }.getOrElse {
      JSONObject().put("error", "The model returned an invalid layout revision response.")
    }
    val rawTranslatedText = response.optString("text").trim()
    val boundary = if (gemmaDocumentTitle != null) GemmaTranslationContext.decodePromptBoundaryEnvelope(
      rawTranslatedText, sourceText, modelText,
    ) else GemmaTranslationContext.BoundaryDecode(rawTranslatedText, emptyList(), invalid = false)
    val translatedText = boundary.text.orEmpty().trim()
    var revisionError: String? = response.optString("error").takeIf { response.has("error") && it.isNotBlank() }
    val candidateText = translatedText.takeIf(String::isNotBlank)
    block.remove("layoutRetryInProgress")
    if (revisionError != null) {
      block.put("layoutRetryError", revisionError)
    } else if (boundary.invalid) {
      revisionError = GemmaTranslationContext.PROMPT_ARTIFACT_ERROR
      block.put("gemmaPromptArtifactRejectedText", rawTranslatedText)
        .put("layoutRetryError", revisionError)
    } else if (translatedText.isBlank()) {
      revisionError = revisionError ?: "The model returned no layout revision."
      block.put("layoutRetryError", revisionError)
    } else if (hasProtectedValueMismatch(modelText, translatedText)) {
      revisionError = "The layout revision changed a protected number, unit, or part code; the prior target was retained."
      block.put("layoutRetryError", revisionError)
    } else {
      block.put("translatedText", translatedText).remove("layoutRetryError")
      if (boundary.removedTokens.isNotEmpty()) {
        block.put("gemmaPromptArtifactRawText", rawTranslatedText)
          .put("gemmaPromptArtifactDecodedTokens", JSONArray(boundary.removedTokens))
      }
      // Other review findings remain attached to the block; layout retry does not clear them.
    }
    LayoutRetryPolicy.appendHistory(block, retry, previousTarget, candidateText, revisionError, elapsed / 1_000.0)
    saveCheckpoint(completedBlockCount)
    timing.measure("checkpoint_write") { database.recordBlockDuration(id, elapsed) }
    database.updateJob(id, stage = "layout", currentPage = pageIndex, clearCurrentBlock = true)
    return LayoutRetryOutcome.RETRIED
  }

  private data class NativeSourceRect(
    val text: String,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val baseline: Double,
    val pictogram: Boolean,
    val objectIndex: Int?,
    val block: JSONObject,
  )
  private data class NativeSourceLine(
    val fragments: List<NativeSourceRect>,
    val text: String,
    val blocks: List<JSONObject>,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val urlHosts: Set<String>,
  )
  private data class NativeOcrMatch(
    val blocks: List<JSONObject>,
    val fragmentText: String,
    val coverage: Double,
    val containedUrlDuplicate: Boolean = false,
    val verifiedTocLeaderNoise: Boolean = false,
  )

  private fun matchingNativeBlock(nativeLines: List<NativeSourceLine>, left: Float, top: Float,
                                  right: Float, bottom: Float, recognizedText: String): NativeOcrMatch? {
    if (right <= left || bottom <= top) return null
    val ocrLeft = left.toDouble()
    val ocrTop = top.toDouble()
    val ocrRight = right.toDouble()
    val ocrBottom = bottom.toDouble()
    var best: NativeOcrMatch? = null
    // Paragraph bounds include whitespace between lines, figures and labels.
    // Deduplicate only where the OCR region is covered by the actual owned
    // native text-object and pictogram rectangles on one printed line. Pictograms
    // contribute geometry, while their surrogate text is excluded from comparison.
    for (sourceLine in nativeLines) {
      if (ocrRight < sourceLine.left - RECT_COVERAGE_PADDING_PT ||
        ocrLeft > sourceLine.right + RECT_COVERAGE_PADDING_PT ||
        ocrBottom < sourceLine.top - RECT_COVERAGE_PADDING_PT ||
        ocrTop > sourceLine.bottom + RECT_COVERAGE_PADDING_PT) continue
      val coverage = ownedRectCoverage(sourceLine.fragments, ocrLeft, ocrTop, ocrRight, ocrBottom)
      val containedUrlDuplicate = isContainedUrlDuplicate(sourceLine, ocrLeft, ocrTop,
        ocrRight, ocrBottom, recognizedText)
      val verifiedTocLeaderNoise = if (coverage >= MIN_VERIFIED_TOC_OCR_COVERAGE &&
        sourceLine.fragments.size == 1 && sourceLine.blocks.size == 1) {
        val fragment = sourceLine.fragments.single()
        val block = sourceLine.blocks.single()
        val blockRects = block.optJSONArray("sourceRects")
        !fragment.pictogram && blockRects?.length() == 1 &&
          // Padding is useful for normal OCR matching, but cannot establish a
          // verified TOC artifact: most of the raw OCR box must cover real text.
          ownedRectCoverage(listOf(fragment), ocrLeft, ocrTop, ocrRight, ocrBottom, paddingPt = 0.0) >=
          MIN_VERIFIED_TOC_OCR_COVERAGE &&
          nativeRectCoverage(listOf(fragment), ocrLeft, ocrTop, ocrRight, ocrBottom) >= MIN_VERIFIED_TOC_OCR_COVERAGE &&
          OcrOutcome.isVerifiedTocLeaderNoise(block.optString("sourceText"), fragment.text,
            block.optString("modelText"), recognizedText)
      } else false
      if (coverage < 0.50 && !containedUrlDuplicate) continue
      if (best == null || containedUrlDuplicate && !best.containedUrlDuplicate ||
        containedUrlDuplicate == best.containedUrlDuplicate && coverage > best.coverage ||
        (coverage == best.coverage && best.fragmentText.isBlank() && sourceLine.text.isNotBlank())) {
        best = NativeOcrMatch(sourceLine.blocks, sourceLine.text, coverage, containedUrlDuplicate, verifiedTocLeaderNoise)
      }
    }
    return best
  }

  /**
   * OCR can wrap a printed URL with nearby cover-art noise (for example a logo
   * fragment and a country marker). Treat it as the same source only when one
   * actual native text line contains the same host and the OCR box covers that
   * line while staying within a tight size envelope.
   */
  private fun isContainedUrlDuplicate(line: NativeSourceLine, left: Double, top: Double,
                                     right: Double, bottom: Double, recognizedText: String): Boolean {
    // Require a 2+ character final domain label so parenthetical abbreviations
    // such as (U.S.) are not mistaken for a second host on the same source line.
    val sourceHosts = line.urlHosts
    if (sourceHosts.size != 1) return false
    val recognizedHosts = urlDomainPattern.findAll(recognizedText).map { it.groupValues[1].lowercase() }.toSet()
    if (sourceHosts.none { it in recognizedHosts }) return false

    val textRects = line.fragments.filter { !it.pictogram && it.right > it.left && it.bottom > it.top }
    if (textRects.isEmpty()) return false
    val sourceLeft = textRects.minOf { it.left }
    val sourceTop = textRects.minOf { it.top }
    val sourceRight = textRects.maxOf { it.right }
    val sourceBottom = textRects.maxOf { it.bottom }
    val sourceWidth = sourceRight - sourceLeft
    val sourceHeight = sourceBottom - sourceTop
    val ocrWidth = right - left
    val ocrHeight = bottom - top
    if (sourceWidth <= 0.0 || sourceHeight <= 0.0 || ocrWidth <= 0.0 || ocrHeight <= 0.0) return false
    // Bounds stop a URL substring inside a paragraph-sized OCR box from
    // suppressing unrelated OCR text or image labels.
    if (ocrWidth / sourceWidth > 2.0 || ocrHeight / sourceHeight > 3.0) return false
    return nativeRectCoverage(textRects, left, top, right, bottom) >= 0.90
  }

  /** Fraction of actual native text rectangle area covered by an OCR box. */
  private fun nativeRectCoverage(rects: List<NativeSourceRect>, left: Double, top: Double,
                                 right: Double, bottom: Double): Double {
    var totalArea = 0.0
    var coveredArea = 0.0
    for (rect in rects) {
      val width = rect.right - rect.left
      val height = rect.bottom - rect.top
      if (width <= 0.0 || height <= 0.0) continue
      totalArea += width * height
      coveredArea += (minOf(rect.right, right) - maxOf(rect.left, left)).coerceAtLeast(0.0) *
        (minOf(rect.bottom, bottom) - maxOf(rect.top, top)).coerceAtLeast(0.0)
    }
    return if (totalArea <= 0.0) 0.0 else (coveredArea / totalArea).coerceIn(0.0, 1.0)
  }

  private fun nativeSourceLines(blocks: JSONArray): List<NativeSourceLine> {
    val sourceRects = mutableListOf<NativeSourceRect>()
    for (blockIndex in 0 until blocks.length()) {
      val block = blocks.optJSONObject(blockIndex) ?: continue
      val rects = block.optJSONArray("sourceRects") ?: continue
      for (rectIndex in 0 until rects.length()) {
        val item = rects.optJSONObject(rectIndex) ?: continue
        sourceRects += NativeSourceRect(
          text = item.optString("text"),
          left = item.optDouble("left", Double.NaN),
          top = item.optDouble("top", Double.NaN),
          right = item.optDouble("right", Double.NaN),
          bottom = item.optDouble("bottom", Double.NaN),
          baseline = item.optDouble("baseline", Double.NaN),
          pictogram = item.optBoolean("pictogram"),
          objectIndex = item.optInt("objectIndex", -1).takeIf { it >= 0 },
          block = block,
        )
      }
    }
    val orderedRects = if (sourceRects.all { it.objectIndex != null }) sourceRects.sortedBy { it.objectIndex }
      else sourceRects
    val lines = mutableListOf<NativeSourceLine>()
    var current = mutableListOf<NativeSourceRect>()
    fun flush() {
      if (current.isNotEmpty()) {
        val textFragments = current.filterNot { it.pictogram }
        val fragments = current.toList()
        val text = textFragments.joinToString("") { it.text }
        lines += NativeSourceLine(fragments, text, textFragments.map { it.block }.distinct(),
          fragments.minOf { it.left }, fragments.minOf { it.top },
          fragments.maxOf { it.right }, fragments.maxOf { it.bottom },
          urlDomainPattern.findAll(text).map { it.groupValues[1].lowercase() }.toSet())
        current = mutableListOf()
      }
    }
    for (rect in orderedRects) {
      if ((!rect.pictogram && rect.text.isBlank()) || !rect.left.isFinite() || !rect.top.isFinite() ||
        !rect.right.isFinite() || !rect.bottom.isFinite() || rect.right <= rect.left || rect.bottom <= rect.top) {
        flush()
        continue
      }
      val previous = current.lastOrNull()
      val sameLine = previous != null && previous.baseline.isFinite() && rect.baseline.isFinite() &&
        kotlin.math.abs(previous.baseline - rect.baseline) <= maxOf(2.5, minOf(3.0,
          minOf(previous.bottom - previous.top, rect.bottom - rect.top) * 0.25))
      val gap = if (previous == null) Double.POSITIVE_INFINITY else rect.left - previous.right
      val iconAdjacent = previous?.pictogram == true || rect.pictogram
      val maxGap = if (iconAdjacent) maxOf(12.0, maxOf(previous?.let { it.bottom - it.top } ?: 0.0,
        rect.bottom - rect.top) * 1.2) else maxOf(1.5,
        maxOf(previous?.let { it.bottom - it.top } ?: 0.0, rect.bottom - rect.top) * 0.8)
      val nearby = previous != null && gap >= -1.5 && gap <= maxGap
      if (current.isNotEmpty() && !(sameLine && nearby)) flush()
      current += rect
    }
    flush()
    return lines
  }

  /** Fraction of an OCR line box covered by the union of source-object rectangles. */
  private fun ownedRectCoverage(fragments: List<NativeSourceRect>, left: Double, top: Double,
                                right: Double, bottom: Double,
                                paddingPt: Double = RECT_COVERAGE_PADDING_PT): Double {
    val lineArea = (right - left) * (bottom - top)
    if (lineArea <= 0.0 || !lineArea.isFinite()) return 0.0
    val clipped = fragments.mapNotNull { fragment ->
      val l = maxOf(left, fragment.left - paddingPt)
      val t = maxOf(top, fragment.top - paddingPt)
      val r = minOf(right, fragment.right + paddingPt)
      val b = minOf(bottom, fragment.bottom + paddingPt)
      if (r <= l || b <= t) null else doubleArrayOf(l, t, r, b)
    }
    if (clipped.isEmpty()) return 0.0
    val xEdges = clipped.flatMap { listOf(it[0], it[2]) }.distinct().sorted()
    var unionArea = 0.0
    for (index in 0 until xEdges.lastIndex) {
      val x0 = xEdges[index]
      val x1 = xEdges[index + 1]
      if (x1 <= x0) continue
      val intervals = clipped.filter { it[0] < x1 && it[2] > x0 }
        .map { it[1] to it[3] }.sortedBy { it.first }
      if (intervals.isEmpty()) continue
      var coveredTop = intervals.first().first
      var coveredBottom = intervals.first().second
      var coveredHeight = 0.0
      for (intervalIndex in 1 until intervals.size) {
        val interval = intervals[intervalIndex]
        if (interval.first <= coveredBottom) coveredBottom = maxOf(coveredBottom, interval.second)
        else {
          coveredHeight += coveredBottom - coveredTop
          coveredTop = interval.first
          coveredBottom = interval.second
        }
      }
      coveredHeight += coveredBottom - coveredTop
      unionArea += (x1 - x0) * coveredHeight
    }
    return (unionArea / lineArea).coerceIn(0.0, 1.0)
  }

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
    val currentJob = jobId
    if (launch != null && currentJob != null) {
      launch.action = Intent.ACTION_VIEW
      launch.removeCategory(Intent.CATEGORY_LAUNCHER)
      launch.addCategory(Intent.CATEGORY_DEFAULT)
      launch.addCategory(Intent.CATEGORY_BROWSABLE)
      launch.data = android.net.Uri.parse("folivela:///job?jobId=${android.net.Uri.encode(currentJob)}")
      launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    val pending = launch?.let { PendingIntent.getActivity(this, 100, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(text)
      .setContentIntent(pending)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
    stopped.set(true)
    runCatching { NativeCore.cancel() }
    val active = jobId
    val downloading = modelId
    val owner = executor
    val generation = workerToken
    val translator = activeMyMemoryTranslator
    val ocr = activeOcrEngine
    val modelToken = modelTransferToken
    val modelGeneration = modelWorkerGeneration
    val entered = synchronized(workerEntryLock) {
      if (active != null && !workerEntered) workerEntryAborted = true
      if (active == null && owner != null && modelToken != null && !modelWorkerEntered) modelWorkerEntryAborted = true
      workerEntered
    }
    val enteredModel = synchronized(workerEntryLock) { modelWorkerEntered }
    if (active == null && owner == null) isActive = false
    stopSignals.execute {
      runCatching { translator?.cancel() }
      runCatching { ocr?.stop() }
      if (active != null && owner != null && !entered) abortUnenteredJobWorker(active, generation, owner, ocr)
      else if (active == null && owner != null) {
        stopModelWorkerIfOwned(owner, downloading, modelToken, modelGeneration, enteredModel)
      } else runCatching { downloading?.let(downloader::pause) }
    }
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf(startId)
  }

  override fun onDestroy() {
    serviceDestroyed = true
    stopped.set(true)
    runCatching { NativeCore.cancel() }
    val active = jobId
    val downloading = modelId
    val worker = executor
    val generation = workerToken
    val translator = activeMyMemoryTranslator
    val ocr = activeOcrEngine
    val modelToken = modelTransferToken
    val modelGeneration = modelWorkerGeneration
    if (active != null) markWorkerStopping(active, canceled = false)
    val entered = synchronized(workerEntryLock) {
      if (active != null && !workerEntered) workerEntryAborted = true
      if (active == null && worker != null && modelToken != null && !modelWorkerEntered) modelWorkerEntryAborted = true
      workerEntered
    }
    val enteredModel = synchronized(workerEntryLock) { modelWorkerEntered }
    if (active == null && worker == null) isActive = false
    stopSignals.execute {
      runCatching { translator?.cancel() }
      runCatching { ocr?.stop() }
      if (active != null && worker != null && !entered) abortUnenteredJobWorker(active, generation, worker, ocr)
      else if (active == null && worker != null) {
        stopModelWorkerIfOwned(worker, downloading, modelToken, modelGeneration, enteredModel)
      }
      else {
        runCatching { downloading?.let(downloader::pause) }
        worker?.shutdownNow()
      }
    }
    io.shutdown()
    super.onDestroy()
  }

  /** Reconcile a model worker without releasing a newer generation's transfer or active state. */
  private fun stopModelWorkerIfOwned(
    owner: ExecutorService,
    id: String?,
    token: String?,
    generation: Long,
    entered: Boolean,
  ) {
    owner.shutdownNow()
    if (executor !== owner || modelId != id || modelTransferToken != token || modelWorkerGeneration != generation) return
    if (id != null && token != null) {
      if (entered) {
        runCatching { downloader.pauseIfOwned(id, token) }
      } else {
        // Release by transfer token so delayed cleanup cannot affect a newer transfer.
        runCatching { downloader.releaseServiceStart(id, token) }
        runCatching { downloader.releaseReservation(id, token) {
          database.modelStatus(id, "paused", downloader.partialLength(id))
        } }
      }
    }
    executor = null
    modelId = null
    modelTransferToken = null
    modelWorkerEntryAborted = true
    clearModelWorkerOwnership(token, generation)
  }

  private fun finishModelDownloadWorker(owner: ExecutorService, id: String, token: String, generation: Long) {
    mainHandler.post {
      owner.shutdown()
      if (executor !== owner || modelId != id || modelTransferToken != token || modelWorkerGeneration != generation) return@post
      if (!serviceDestroyed) stopForeground(STOP_FOREGROUND_DETACH)
      executor = null
      modelId = null
      modelTransferToken = null
      stopped.set(true)
      clearModelWorkerOwnership(token, generation)
      if (!serviceDestroyed) stopSelfForCompletedWorker(latestStartId)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  internal fun stopAfterStaleModelIntentIfIdle(startId: Int): Boolean {
    return stopServiceIfIdle(startId)
  }

  companion object {
    private const val LAYOUT_FAILURE_PREFIX = "Target text does not fit its original source container: "
    @Volatile var isActive: Boolean = false
      private set
    @Volatile var activeJobId: String? = null
      private set
    @Volatile private var activeWorkerGeneration: Long = 0L
    @Volatile private var activeModelTransferToken: String? = null
    @Volatile private var activeModelWorkerGeneration: Long = 0L
    private val modelOwnershipLock = Any()
    private fun clearModelWorkerOwnership(token: String?, generation: Long) = synchronized(modelOwnershipLock) {
      if (activeModelTransferToken == token && activeModelWorkerGeneration == generation) {
        activeModelTransferToken = null
        activeModelWorkerGeneration = 0L
        isActive = activeJobId != null
      }
    }
    private val nextWorkerGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile var stopRequestedJobId: String? = null
      private set
    @Volatile var stopRequestedCanceled: Boolean = false
      private set
    private val stopSignals = Executors.newSingleThreadExecutor { task -> Thread(task, "Folivela-stop-signal").apply { isDaemon = true } }
    internal fun markWorkerStopping(id: String, canceled: Boolean) {
      stopRequestedJobId = id
      stopRequestedCanceled = canceled
    }
    internal fun clearWorkerOwnership(id: String, generation: Long) {
      if (activeJobId != id || activeWorkerGeneration != generation) return
      activeJobId = null
      activeWorkerGeneration = 0L
      if (stopRequestedJobId == id) {
        stopRequestedJobId = null
        stopRequestedCanceled = false
      }
    }
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
    private const val MIN_NATIVE_PAGE_LETTERS = 40
    private const val MAX_ILLUSTRATION_PAGE_AREA = 0.65
    private const val MIN_IMAGE_OCR_COVERAGE = 0.95
    private const val MIN_VERIFIED_TOC_OCR_COVERAGE = 0.95
    private const val IMAGE_RASTER_TOLERANCE_PT = 0.25
    private const val RECT_COVERAGE_PADDING_PT = 1.25
    private const val HY_MT2_PROFILE = "hy-mt2"
    private const val TRANSLATE_GEMMA_PROFILE = "translategemma"
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

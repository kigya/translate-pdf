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
import android.os.IBinder
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
  private val database by lazy { TranslatorDatabase(applicationContext) }
  private val downloader by lazy { ModelDownloader(applicationContext, database) }
  private val stopped = AtomicBoolean(false)
  private var executor: ExecutorService? = null
  private var jobId: String? = null
  private var modelId: String? = null
  @Volatile private var activeOcrEngine: OcrEngine? = null

  override fun onCreate() {
    super.onCreate()
    isActive = true
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_PAUSE -> {
        val requestedId = intent.getStringExtra(EXTRA_JOB_ID)
        if (requestedId != null && requestedId != jobId) {
          database.updateJob(requestedId, status = "paused")
        } else {
          stopped.set(true)
          NativeCore.cancel()
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
        } else {
          stopped.set(true)
          NativeCore.cancel()
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
        val requestedId = intent.getStringExtra(EXTRA_MODEL_ID) ?: run { stopSelf(startId); return START_NOT_STICKY }
        if (executor?.isShutdown == false) return START_NOT_STICKY
        modelId = requestedId
        stopped.set(false)
        startForegroundCompat(notification("Preparing model download…"))
        executor = Executors.newSingleThreadExecutor().also { worker ->
          worker.execute { runModelDownload(requestedId, startId) }
        }
        return START_NOT_STICKY
      }
      ACTION_START -> {
        val requestedId = intent.getStringExtra(EXTRA_JOB_ID) ?: run { stopSelf(startId); return START_NOT_STICKY }
        if (executor?.isShutdown == false) return START_NOT_STICKY
        jobId = requestedId
        activeJobId = requestedId
        stopped.set(false)
        startForegroundCompat(notification("Starting local translation…"))
        executor = Executors.newSingleThreadExecutor().also { worker ->
          worker.execute { runJob(requestedId, startId) }
        }
      }
    }
    return START_NOT_STICKY
  }

  private fun runModelDownload(id: String, startId: Int) {
    val finished = CountDownLatch(1)
    try {
      val definition = ModelCatalog.get(id)
      downloader.start(id, onProgress = { bytes ->
        updateNotification("Downloading model · ${bytes / 1_048_576} of ${definition.bytes / 1_048_576} MiB")
      }, onFinished = { result ->
        if (result.isFailure && !stopped.get()) {
          database.modelStatus(id, "error", downloader.partialLength(id), result.exceptionOrNull()?.message ?: "Model download failed.")
        }
        finished.countDown()
      })
      finished.await()
      updateNotification(if (downloader.isInstalled(id)) "Model downloaded and verified." else "Model download paused or failed. Open the app to continue.")
    } catch (error: InterruptedException) {
      Thread.currentThread().interrupt()
    } catch (error: Exception) {
      database.modelStatus(id, "error", downloader.partialLength(id), error.message ?: "Model download failed.")
      updateNotification("Model download failed. Open the app to retry.")
    } finally {
      executor?.shutdown()
      stopForeground(STOP_FOREGROUND_DETACH)
      stopSelf(startId)
    }
  }

  private fun runJob(id: String, startId: Int) {
    var ocrEngine: OcrEngine? = null
    var ocrSetupFailure: String? = null
    try {
      val job = database.jobJson(id) ?: error("Translation job was not found.")
      val source = database.jobDocumentPath(id) ?: error("Source document is unavailable.")
      val modelId = job.getString("modelId")
      val sourceLanguage = job.getString("sourceLanguage")
      val targetLanguage = job.getString("targetLanguage")
      LanguagePair.requireValid(sourceLanguage, targetLanguage)
      database.validateJobSnapshot(id, ModelCatalog.get(modelId))
      val pageCount = job.getInt("totalPages")
      var completed = database.completedPageCount(id)
      val needsTranslation = completed < pageCount
      if (needsTranslation && !downloader.isReady(modelId)) error("The selected model is missing or incomplete.")
      val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
      val memory = android.app.ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
      val freeDisk = android.os.StatFs(filesDir.absolutePath).let { it.availableBlocksLong * it.blockSizeLong }
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
        try { ocrEngine = OcrEngine(this, sourceLanguage) }
        catch (error: Exception) { ocrSetupFailure = error.message ?: "The bundled OCR data for $sourceLanguage is unavailable." }
        activeOcrEngine = ocrEngine
        database.updateJob(id, status = "running", stage = "extracting", clearError = true)
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
      } else database.updateJob(id, status = "running", stage = "exporting", clearError = true)
      for (pageIndex in 0 until pageCount) {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        val currentMemory = android.app.ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
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
        database.updateJob(id, status = "running", stage = "extracting", currentPage = pageIndex)
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
          val blockMemory = android.app.ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
          if (blockMemory.lowMemory) {
            database.savePage(id, pageIndex, "running", blocks.toString(), pageWidth.toDouble(), pageHeight.toDouble(), ocrFailure, blocks.length())
            pauseForResources(id, "Android reports low memory. The job is paused at its latest saved checkpoint.")
            return
          }
          val block = blocks.getJSONObject(index)
          val sourceText = block.optString("sourceText", "").trim()
          if (sourceText.isEmpty()) continue
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
            if (isAllocationFailure(error.message.orEmpty())) {
              block.put("translatedText", JSONObject.NULL).put("status", "failed")
                .put("error", error.message ?: "Android could not allocate working memory.")
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
      val finalPdf = JobPdfExporter(this, database).getOrBuild(id) { stopped.get() || Thread.currentThread().isInterrupted }
      if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
      database.updateJob(id, status = if (finalPdf.partial) "partial" else "completed", stage = "complete", clearCurrentPage = true)
      updateNotification(if (finalPdf.partial) "Translation finished with pages that need review." else "Translation completed.")
    } catch (_: InterruptedException) {
      val status = database.jobJson(id)?.optString("status")
      if (status == "running") database.updateJob(id, status = "paused", error = "Paused after the latest saved page checkpoint.")
    } catch (error: OutOfMemoryError) {
      pauseForResources(id, "Android could not allocate working memory. The job is paused at its latest saved checkpoint.")
    } catch (error: Exception) {
      database.updateJob(id, status = "failed", error = error.message ?: "Local translation failed.")
      updateNotification("Translation stopped. Open the app to review the error.")
    } finally {
      ocrEngine?.close()
      activeOcrEngine = null
      if (BuildConfig.DEBUG && database.jobJson(id)?.optString("status") in setOf("completed", "partial", "failed", "canceled")) {
        getSharedPreferences(QA_PROFILE_PREFS, MODE_PRIVATE).edit()
          .remove(QA_PROFILE_REGISTERED_JOB).apply()
      }
      runCatching { NativeCore.releaseModel() }
      executor?.shutdown()
      stopForeground(STOP_FOREGROUND_DETACH)
      stopSelf(startId)
    }
  }

  private fun pauseForResources(id: String, message: String) {
    database.updateJob(id, status = "paused", error = message)
    updateNotification("Translation paused to protect device resources. Open the app to resume.")
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
      return PageExportCheck(result.optBoolean("partial") || issues.length() > 0,
        maxOf(result.optInt("partialBlocks", 0), issues.length()), issues)
    } finally { destination.delete() }
  }

  private data class PageExportCheck(val partial: Boolean, val partialBlocks: Int, val issues: JSONArray)

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
      .setContentTitle("TranslatePDF")
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "PDF translation", NotificationManager.IMPORTANCE_LOW),
      )
    }
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
    activeOcrEngine?.stop()
    runCatching { NativeCore.releaseModel() }
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf(startId)
  }

  override fun onDestroy() {
    TranslationServiceDestroyHandler.run(
      stopped = stopped,
      database = database,
      activeJobId = jobId,
      cancelNative = { NativeCore.cancel() },
      stopOcr = { activeOcrEngine?.stop() },
      pauseDownload = { modelId?.let(downloader::pause) },
      interruptWorker = { executor?.shutdownNow() },
    )
    isActive = false
    activeJobId = null
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

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

package dev.kigya.translatepdf

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Debug APK-only route. Inputs are fixed private paths; external callers cannot choose paths/data. */
class NativeSliceQaActivity : Activity() {
  private val worker = Executors.newSingleThreadExecutor()
  private lateinit var status: TextView
  @Volatile private var activeOcrEngine: OcrEngine? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    status = TextView(this).apply { text = "Preparing the local native slice check…"; textSize = 18f; setPadding(32, 48, 32, 32) }
    setContentView(status)
    if (!BuildConfig.DEBUG || intent.data != null || intent.action != ACTION_RUN) {
      finish(); return
    }
    val modelId = intent.getStringExtra(EXTRA_MODEL_ID).orEmpty()
    val glossaryId = intent.getStringExtra(EXTRA_GLOSSARY_ID).orEmpty()
    val sourceLanguage = intent.getStringExtra(EXTRA_SOURCE_LANGUAGE) ?: DEFAULT_SOURCE_LANGUAGE
    val targetLanguage = intent.getStringExtra(EXTRA_TARGET_LANGUAGE) ?: DEFAULT_TARGET_LANGUAGE
    try {
      LanguagePair.requireValid(sourceLanguage, targetLanguage)
    } catch (_: IllegalArgumentException) {
      finish(); return
    }
    if (intent.getBooleanExtra(EXTRA_OCR_ONLY, false)) {
      worker.execute { runOcrOnly(sourceLanguage) }
      return
    }
    if (intent.getBooleanExtra(EXTRA_ENQUEUE_NORMAL_JOB, false)) {
      worker.execute { enqueueAndWaitForNormalJob(modelId, glossaryId, sourceLanguage, targetLanguage) }
    } else worker.execute { runCheck(modelId, sourceLanguage, targetLanguage) }
  }

  /** Enqueue the fixed private QA fixture through the same SQLite/foreground-service path as the app. */
  private fun enqueueAndWaitForNormalJob(modelId: String, rawGlossaryId: String, sourceLanguage: String, targetLanguage: String) {
    val outputDirectory = File(filesDir, "qa").apply { mkdirs() }
    val input = File(outputDirectory, "source.pdf")
    val resultFile = File(outputDirectory, "result.json")
    var database: TranslatorDatabase? = null
    val metadata = JSONObject().put("startedAt", System.currentTimeMillis()).put("status", "running")
      .put("mode", "foreground-service-job").put("phase", "enqueue")
      .put("modelId", modelId).put("sourceLanguage", sourceLanguage).put("targetLanguage", targetLanguage)
      .put("input", "files/qa/source.pdf")
    try {
      writeAtomically(resultFile, metadata.toString(2))
      require(modelId in ModelCatalog.models.map { it.id }) { "Pass a catalog model ID with --es modelId <id>." }
      require(rawGlossaryId.isEmpty() || rawGlossaryId.matches(Regex("^[A-Za-z0-9._-]{1,80}$"))) { "The optional glossary ID is invalid." }
      val glossaryId = rawGlossaryId.takeIf(String::isNotBlank)
      require(input.isFile && input.length() in 5..(20L * 1024L * 1024L)) { "Copy a test PDF of at most 20 MiB to files/qa/source.pdf first." }
      val model = ModelCatalog.get(modelId)
      val modelFile = File(filesDir, "models/${model.id}.gguf")
      require(modelFile.isFile && modelFile.length() == model.bytes && sha256(modelFile) == model.sha256) {
        "The selected pinned model file is missing or failed its SHA-256 check."
      }
      val inspected = JSONObject(NativeCore.inspect(input.absolutePath)).throwOnNativeError("PDF inspect failed.")
      val pageCount = inspected.optInt("pageCount")
      require(pageCount in 1..5) { "The normal-job QA fixture must contain one to five pages." }
      val glossaryDb = TranslatorDatabase(this)
      database = glossaryDb
      if (glossaryId != null) require(glossaryDb.glossaryText(glossaryId, sourceLanguage, targetLanguage).isNotBlank()) {
        "The requested saved glossary entry was not found for ${LanguagePair.displayLabel(sourceLanguage, targetLanguage)}."
      }
      val inputSha256 = sha256(input)
      val documentId = "qa-document-${UUID.randomUUID()}"
      glossaryDb.upsertDocument(documentId, "QA fixture.pdf", input.absolutePath, pageCount, inputSha256)
      glossaryDb.modelStatus(modelId, "ready", model.bytes)
      val jobId = "qa-${UUID.randomUUID()}"
      val jobInput = JSONObject().put("documentId", documentId).put("sourceLanguage", sourceLanguage)
        .put("targetLanguage", targetLanguage).put("modelId", modelId)
      if (glossaryId != null) jobInput.put("glossaryId", glossaryId)
      glossaryDb.addJob(jobId, jobInput, pageCount)
      getSharedPreferences(QA_PROFILE_PREFS, MODE_PRIVATE).edit()
        .putString(QA_PROFILE_REGISTERED_JOB, jobId)
        .remove(QA_PROFILE_BYPASSED_JOB)
        .apply()
      metadata.put("jobId", jobId).put("glossaryId", glossaryId ?: JSONObject.NULL)
        .put("glossaryApplied", glossaryDb.jobGlossaryText(jobId).isNotBlank())
        .put("pageCount", pageCount).put("inputBytes", input.length()).put("inputSha256", inputSha256)
        .put("provisionalMemoryEnvelopeValidated", false)
      writeAtomically(resultFile, metadata.toString(2))

      val serviceIntent = Intent(this, PdfTranslationService::class.java)
        .setAction(PdfTranslationService.ACTION_START)
        .putExtra(PdfTranslationService.EXTRA_JOB_ID, jobId)
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(serviceIntent)
      else startService(serviceIntent)
      metadata.put("phase", "foreground_translation")
      writeAtomically(resultFile, metadata.toString(2))

      val deadline = android.os.SystemClock.elapsedRealtime() + 3L * 60 * 60 * 1000
      while (android.os.SystemClock.elapsedRealtime() < deadline) {
        val job = glossaryDb.jobJson(jobId) ?: error("The queued native QA job disappeared from durable storage.")
        metadata.put("job", job).put("pages", glossaryDb.pageRows(jobId))
          .put("completedPages", job.optInt("completedPages"))
          .put("currentPage", if (job.isNull("currentPage")) JSONObject.NULL else job.optInt("currentPage"))
          .put("provisionalMemoryGateBypassed", getSharedPreferences(QA_PROFILE_PREFS, MODE_PRIVATE)
            .getString(QA_PROFILE_BYPASSED_JOB, null) == jobId)
          .put("phase", job.optString("stage"))
        writeAtomically(resultFile, metadata.toString(2))
        when (job.optString("status")) {
          "completed", "partial", "failed", "canceled", "paused" -> {
            if (job.optString("status") == "completed" || job.optString("status") == "partial") {
              val font = File(cacheDir, "NotoSans-Regular.ttf").also { FontAsset.ensure(this, it) }
              val cjkFontPath = if (targetLanguage == "zh-Hans") {
                FontAsset.ensureCjk(this, File(cacheDir, "NotoSansSC-Regular.ttf")).absolutePath
              } else ""
              val shards = JSONArray()
              var partial = job.optString("status") == "partial"
              for (pageIndex in 0 until pageCount) {
                val page = glossaryDb.page(jobId, pageIndex) ?: continue
                val shard = File(outputDirectory, "normal-job-page-$pageIndex.pdf")
                val exported = JSONObject(NativeCore.exportPage(input.absolutePath, pageIndex, page.getJSONArray("blocks").toString(), shard.absolutePath, font.absolutePath, cjkFontPath))
                  .throwOnNativeError("Final QA page export failed.")
                if (exported.optBoolean("partial")) partial = true
                shards.put(shard.absolutePath)
              }
              val merged = File(outputDirectory, "normal-job-translated.pdf")
              val merge = JSONObject(NativeCore.mergePdf(shards.toString(), merged.absolutePath))
                .throwOnNativeError("Final QA PDF merge failed.")
              require(merge.optInt("pageCount") == shards.length() && merged.isFile && merged.length() > 0) {
                "The normal-job QA output failed its page-count or file check."
              }
              metadata.put("exportPath", "files/qa/normal-job-translated.pdf")
                .put("exportBytes", merged.length()).put("exportSha256", sha256(merged))
                .put("exportPageCount", merge.optInt("pageCount"))
              if (partial) metadata.put("status", "partial") else metadata.put("status", "completed")
            } else metadata.put("status", job.optString("status"))
            metadata.put("error", if (job.isNull("error")) JSONObject.NULL else job.optString("error"))
            break
          }
        }
        Thread.sleep(1_000)
      }
      if (metadata.optString("status") == "running") {
        metadata.put("status", "timed_out").put("error", "The normal foreground-service QA job exceeded its three-hour limit.")
      }
    } catch (error: Exception) {
      metadata.put("status", "failed").put("error", error.message ?: "Normal foreground-service QA failed.")
    } finally {
      database?.close()
      metadata.put("finishedAt", System.currentTimeMillis())
      runCatching { writeAtomically(resultFile, metadata.toString(2)) }
      runOnUiThread {
        status.text = "Normal-job QA ${metadata.optString("status")}. Private result: files/qa/result.json"
      }
    }
  }

  private fun runCheck(modelId: String, sourceLanguage: String, targetLanguage: String) {
    val threads = intent.getIntExtra("threads", 4).coerceIn(1, 4)
    if (intent.getBooleanExtra("backgroundPriority", true)) android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
    val memo = LocalTranslationMemo(translateNative = NativeCore::translate)
    val outputDirectory = File(filesDir, "qa").apply { mkdirs() }
    val input = File(outputDirectory, "source.pdf")
    val resultFile = File(outputDirectory, "result.json")
    val merged = File(outputDirectory, "translated-first-page.pdf")
    val translatedPage = File(outputDirectory, "translated-page.pdf")
    merged.delete(); translatedPage.delete(); resultFile.delete()
    val metadata = JSONObject()
      .put("startedAt", System.currentTimeMillis())
      .put("status", "running")
      .put("phase", "validation")
      .put("modelId", modelId)
      .put("sourceLanguage", sourceLanguage).put("targetLanguage", targetLanguage)
      .put("input", "files/qa/source.pdf")
      .put("output", "files/qa/translated-first-page.pdf")
      .put("phaseDurationsMs", JSONObject())
    var currentPhase = "validation"
    var phaseStartedAt = android.os.SystemClock.elapsedRealtime()
    fun markPhase(next: String) {
      metadata.getJSONObject("phaseDurationsMs").put(currentPhase, android.os.SystemClock.elapsedRealtime() - phaseStartedAt)
      currentPhase = next
      phaseStartedAt = android.os.SystemClock.elapsedRealtime()
      metadata.put("phase", next).put("phaseStartedAt", System.currentTimeMillis())
      writeAtomically(resultFile, metadata.toString(2))
    }
    val stopSampler = AtomicBoolean(false)
    val peakPssKb = AtomicInteger(0)
    var sampler: Thread? = null
    try {
      writeAtomically(resultFile, metadata.toString(2))
      require(modelId in ModelCatalog.models.map { it.id }) { "Pass a catalog model ID with --es modelId <id>." }
      val glossaryId = intent.getStringExtra(EXTRA_GLOSSARY_ID).orEmpty().takeIf(String::isNotBlank)
      require(glossaryId == null || glossaryId.matches(Regex("^[A-Za-z0-9._-]{1,80}$"))) { "The optional glossary ID is invalid." }
      require(input.isFile && input.length() >= 5) { "Copy a test PDF to files/qa/source.pdf first." }
      val model = ModelCatalog.get(modelId)
      val modelFile = File(filesDir, "models/${model.id}.gguf")
      markPhase("hash_model")
      require(modelFile.isFile && modelFile.length() == model.bytes && sha256(modelFile) == model.sha256) {
        "The selected pinned model file is missing or failed its SHA-256 check."
      }
      markPhase("hash_input")
      val inputSha256 = sha256(input)
      val database = TranslatorDatabase(this)
      database.modelStatus(modelId, "ready", model.bytes)
      database.close()
      markPhase("inspect")
      val inspected = JSONObject(NativeCore.inspect(input.absolutePath)).throwOnNativeError("PDF inspect failed.")
      require(inspected.optInt("pageCount") > 0) { "The test PDF contains no readable pages." }
      metadata.put("inputBytes", input.length()).put("inputSha256", inputSha256)
        .put("pageCount", inspected.getInt("pageCount")).put("processedPages", 1)

      val page = JSONObject(NativeCore.readPage(input.absolutePath, 0)).throwOnNativeError("Reading the first page failed.")
      preserveCoordinateSpaceMetadata(page, metadata)
      val width = page.getDouble("width").toFloat()
      val height = page.getDouble("height").toFloat()
      require(width > 0 && height > 0) { "The test page has invalid geometry." }
      val ocr = OcrEngine(this, sourceLanguage)
      activeOcrEngine = ocr
      markPhase("ocr")
      val blocks = try { combineNativeAndOcr(input.absolutePath, page, width, height, ocr) }
      finally { ocr.close(); activeOcrEngine = null }
      require(blocks.length() > 0) { "The first test page has no native or OCR text to translate." }
      metadata.put("discoveredBlocks", blocks.length())
      val glossaryDatabase = TranslatorDatabase(this)
      val glossaryText = try {
        if (glossaryId == null) "" else glossaryDatabase.glossaryText(glossaryId, sourceLanguage, targetLanguage).also {
          require(it.isNotBlank()) { "The requested saved glossary entry was not found for ${LanguagePair.displayLabel(sourceLanguage, targetLanguage)}." }
        }
      } finally { glossaryDatabase.close() }
      metadata.put("glossaryId", glossaryId ?: JSONObject.NULL).put("glossaryApplied", glossaryText.isNotBlank())

      val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
      val memory = android.app.ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
      val freeDisk = android.os.StatFs(filesDir.absolutePath).let { it.availableBlocksLong * it.blockSizeLong }
      val recommendation = MemoryPolicy.recommend(memory.totalMem, memory.availMem, memory.threshold, memory.lowMemory, freeDisk)
      metadata.put("availableMemoryBytes", memory.availMem).put("reservedMemoryBytes", recommendation.reserveBytes)
        .put("systemLowMemoryThresholdBytes", memory.threshold)
        .put("requiredPipelineMemoryBytes", recommendation.requiredPipelineMemoryBytes)
        .put("requiredDiskBytes", recommendation.requiredDiskBytes)
        .put("memoryEstimateEligible", recommendation.memoryEstimateEligible)
        .put("diskEstimateEligible", recommendation.diskEstimateEligible)
        .put("resourceEstimateEligible", recommendation.resourceEstimateEligible)
      metadata.put("memorySampleKind", "in-process sampled totalPss")
        .put("memorySampleAdvisoryOnly", true)
        .put("externalMemoryMeasurementRequired", true)
        .put("memorySampleLimitation", "Use external dumpsys meminfo for process peak; sampled totalPss under-reported mapped/native model memory in the prior device run.")
      metadata.put("resourceEstimateBlocksTranslation", false)
      val weightRepackingAllowed = WeightRepackingPolicy.shouldAllow(modelFile.length(), memory.totalMem)
      metadata.put("weightRepackingAllowed", weightRepackingAllowed)
      sampler = Thread({
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        while (!stopSampler.get()) {
          try {
            val current = manager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()?.totalPss ?: 0
            while (true) {
              val previous = peakPssKb.get()
              if (current <= previous || peakPssKb.compareAndSet(previous, current)) break
            }
            writeAtomically(File(outputDirectory, "activity.json"), JSONObject(NativeCore.inferenceProgress())
              .put("observedAt", System.currentTimeMillis()).put("processPssKb", current).toString())
            Thread.sleep(500)
          } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
          catch (_: Exception) { Thread.sleep(500) }
        }
      }, "native-qa-memory-sampler").apply { isDaemon = true; start() }
      markPhase("model_load")
      NativeCore.setWeightRepackingAllowed(weightRepackingAllowed)
      val load = JSONObject(NativeCore.loadModel(modelFile.absolutePath, 2048, threads, model.profile)).throwOnNativeError("Loading the model failed.")
      metadata.put("modelLoad", load)
      markPhase("inference")
      metadata.put("currentBlockIndex", 0).put("blockMetrics", JSONArray())
      writeAtomically(resultFile, metadata.toString(2))

      var translatedCount = 0
      var failedCount = 0
      val controlBlocks = intent.getIntExtra("maxBlocks", blocks.length()).coerceIn(1, blocks.length())
      metadata.put("controlBlocks", controlBlocks).put("workerNice", android.os.Process.getThreadPriority(android.os.Process.myTid()))
      for (index in 0 until controlBlocks) {
        val block = blocks.getJSONObject(index)
        val sourceText = block.optString("sourceText").trim()
        if (sourceText.isEmpty()) continue
        try {
          val translation = JSONObject(memo.translate(sourceText, glossaryText, page.optString("context").take(2_000), sourceLanguage, targetLanguage))
            .throwOnNativeError("The model did not translate a block.")
          val text = translation.optString("text").trim()
          require(text.isNotEmpty()) { "The model returned an empty translation." }
          block.put("translatedText", text).put("status", "translated")
          translatedCount++
          metadata.getJSONArray("blockMetrics").put(JSONObject()
            .put("index", index).put("status", "translated").put("tokens", translation.optInt("tokens"))
            .put("cacheHit", translation.optBoolean("cacheHit")).put("inputTokens", translation.optInt("inputTokens")).put("cachedInputTokens", translation.optInt("cachedInputTokens"))
            .put("seconds", translation.optDouble("seconds")).put("tokensPerSecond", translation.optDouble("tokensPerSecond")))
        } catch (error: Exception) {
          block.put("translatedText", JSONObject.NULL).put("status", "failed")
            .put("error", error.message ?: "Translation failed.")
          failedCount++
          metadata.getJSONArray("blockMetrics").put(JSONObject().put("index", index).put("status", "failed"))
        }
        metadata.put("currentBlockIndex", index + 1).put("completedBlocks", translatedCount + failedCount)
          .put("sampledProcessPssKb", peakPssKb.get())
        writeAtomically(resultFile, metadata.toString(2))
      }
      val font = File(cacheDir, "NotoSans-Regular.ttf")
      FontAsset.ensure(this, font)
      val cjkFontPath = if (targetLanguage == "zh-Hans") {
        FontAsset.ensureCjk(this, File(cacheDir, "NotoSansSC-Regular.ttf")).absolutePath
      } else ""
      markPhase("export")
      val export = JSONObject(NativeCore.exportPage(input.absolutePath, 0, blocks.toString(), translatedPage.absolutePath, font.absolutePath, cjkFontPath))
        .throwOnNativeError("Exporting the translated page failed.")
      markPhase("merge")
      val merge = JSONObject(NativeCore.mergePdf(JSONArray().put(translatedPage.absolutePath).toString(), merged.absolutePath))
        .throwOnNativeError("Assembling the translated PDF failed.")
      require(merge.optInt("pageCount") == 1 && merged.isFile && merged.length() > 0) { "The QA PDF failed its output checks." }
      val disagreementCount = (0 until blocks.length()).count { blocks.getJSONObject(it).optBoolean("sourceDisagreement") }
      metadata.put("status", if (failedCount > 0 || export.optBoolean("partial") || disagreementCount > 0) "partial" else "completed")
        .put("translatedBlocks", translatedCount).put("failedBlocks", failedCount)
        .put("ocrDisagreementBlocks", disagreementCount)
        .put("partialBlocks", export.optInt("partialBlocks")).put("exportPageCount", merge.optInt("pageCount"))
        .put("exportBytes", merged.length()).put("exportSha256", sha256(merged))
    } catch (error: Exception) {
      metadata.put("status", "failed").put("error", error.message ?: "Native slice QA failed.")
    } finally {
      runCatching { NativeCore.releaseModel() }
      stopSampler.set(true)
      sampler?.interrupt()
      runCatching { sampler?.join(1_500) }
      metadata.put("sampledProcessPssKb", peakPssKb.get())
      metadata.getJSONObject("phaseDurationsMs").put(currentPhase, android.os.SystemClock.elapsedRealtime() - phaseStartedAt)
      metadata.put("phase", "finished")
      metadata.put("finishedAt", System.currentTimeMillis())
      runCatching { writeAtomically(resultFile, metadata.toString(2)) }
      runOnUiThread {
        status.text = if (metadata.optString("status") == "completed" || metadata.optString("status") == "partial") {
          "Native slice check ${metadata.optString("status")}. Private result: files/qa/result.json"
        } else "Native slice check failed: ${metadata.optString("error", "Check files/qa/result.json")}."
      }
    }
  }

  /** OCR-only fixture route: validates the selected bundled language without loading inference weights. */
  private fun runOcrOnly(sourceLanguage: String) {
    val outputDirectory = File(filesDir, "qa").apply { mkdirs() }
    val input = File(outputDirectory, "source.pdf")
    val resultFile = File(outputDirectory, "result.json")
    val metadata = JSONObject().put("startedAt", System.currentTimeMillis()).put("status", "running")
      .put("mode", "ocr-only").put("phase", "validation").put("sourceLanguage", sourceLanguage)
      .put("input", "files/qa/source.pdf").put("ocrLines", JSONArray())
    try {
      writeAtomically(resultFile, metadata.toString(2))
      require(input.isFile && input.length() in 5..(20L * 1024L * 1024L)) {
        "Copy a test PDF of at most 20 MiB to files/qa/source.pdf first."
      }
      metadata.put("inputBytes", input.length()).put("inputSha256", sha256(input))
      val inspected = JSONObject(NativeCore.inspect(input.absolutePath)).throwOnNativeError("PDF inspect failed.")
      val pageCount = inspected.optInt("pageCount")
      require(pageCount > 0) { "The test PDF contains no readable pages." }
      metadata.put("pageCount", pageCount)
      val page = JSONObject(NativeCore.readPage(input.absolutePath, 0)).throwOnNativeError("Reading the first page failed.")
      val pageWidth = page.optDouble("width").toFloat()
      val pageHeight = page.optDouble("height").toFloat()
      require(pageWidth > 0f && pageHeight > 0f) { "The test page has invalid geometry." }
      metadata.put("phase", "ocr").put("phaseStartedAt", System.currentTimeMillis())
      val bitmap = NativeCore.render(input.absolutePath, 0, 1800)
      var engine: OcrEngine? = null
      val lines = try {
        OcrEngine(this, sourceLanguage).also { engine = it; activeOcrEngine = it }
          .recognize(bitmap, pageWidth, pageHeight)
      } finally {
        engine?.close(); activeOcrEngine = null; bitmap.recycle()
      }
      metadata.put("ocrLines", lines).put("ocrLineCount", lines.length()).put("status", "completed")
    } catch (error: Exception) {
      metadata.put("status", "failed").put("error", error.message ?: "OCR-only QA failed.")
    } finally {
      metadata.put("finishedAt", System.currentTimeMillis())
      runCatching { writeAtomically(resultFile, metadata.toString(2)) }
      runOnUiThread { status.text = "OCR-only QA ${metadata.optString("status")}. Private result: files/qa/result.json" }
    }
  }

  private fun combineNativeAndOcr(path: String, page: JSONObject, pageWidth: Float, pageHeight: Float, ocr: OcrEngine): JSONArray {
    val blocks = JSONArray()
    val native = page.optJSONArray("blocks") ?: JSONArray()
    for (index in 0 until native.length()) {
      val raw = native.getJSONObject(index)
      val block = JSONObject().put("id", raw.optString("id", "native-$index"))
        .put("sourceText", raw.optString("text", raw.optString("sourceText", "")))
        .put("translatedText", JSONObject.NULL).put("left", raw.optDouble("left"))
        .put("top", raw.optDouble("top")).put("right", raw.optDouble("right"))
        .put("bottom", raw.optDouble("bottom")).put("fontSize", raw.optDouble("fontSize", 10.0))
        .put("source", "native").put("objectIndex", if (raw.isNull("objectIndex")) JSONObject.NULL else raw.optInt("objectIndex"))
        .put("objectIndices", raw.optJSONArray("objectIndices") ?: JSONArray().apply { if (!raw.isNull("objectIndex")) put(raw.optInt("objectIndex")) })
        .put("status", "pending")
      if (raw.has("coordinateSpaceVersion")) block.put("coordinateSpaceVersion", raw.get("coordinateSpaceVersion"))
      if (raw.has("partialCrop")) block.put("partialCrop", raw.get("partialCrop"))
      blocks.put(block)
    }
    val bitmap = NativeCore.render(path, 0, 1800)
    try {
      val lines = ocr.recognize(bitmap, pageWidth, pageHeight)
      val nativeCount = blocks.length()
      for (index in 0 until lines.length()) {
        val line = lines.getJSONObject(index)
        val matched = matchingNative(blocks, nativeCount, line,
          line.optDouble("left").toFloat(), line.optDouble("top").toFloat(),
          line.optDouble("right").toFloat(), line.optDouble("bottom").toFloat())
        if (matched == null) blocks.put(line)
        else if (OcrOutcome.disagrees(matched.optString("sourceText"), line.optString("sourceText"))) {
          matched.put("sourceDisagreement", true).put("error", OcrOutcome.SOURCE_DISAGREEMENT_BLOCK_WARNING)
        }
      }
    } finally { bitmap.recycle() }
    return blocks
  }

  private fun preserveCoordinateSpaceMetadata(page: JSONObject, metadata: JSONObject) {
    if (page.has("coordinateSpaceVersion")) metadata.put("coordinateSpaceVersion", page.get("coordinateSpaceVersion"))
  }

  private fun matchingNative(blocks: JSONArray, nativeCount: Int, line: JSONObject,
                             left: Float, top: Float, right: Float, bottom: Float): JSONObject? {
    var best: JSONObject? = null
    var bestOverlap = 0f
    var containedTextMatch: JSONObject? = null
    var containedArea = Float.POSITIVE_INFINITY
    val lineText = normalizeComparisonText(line.optString("sourceText"))
    for (index in 0 until nativeCount) {
      val block = blocks.getJSONObject(index)
      val l = block.optDouble("left").toFloat(); val t = block.optDouble("top").toFloat()
      val r = block.optDouble("right").toFloat(); val b = block.optDouble("bottom").toFloat()
      val overlap = (minOf(right, r) - maxOf(left, l)).coerceAtLeast(0f) *
        (minOf(bottom, b) - maxOf(top, t)).coerceAtLeast(0f)
      val nativeArea = maxOf((r - l) * (b - t), 1f)
      val lineArea = maxOf((right - left) * (bottom - top), 1f)
      val overlapRatio = overlap / maxOf(nativeArea, lineArea)
      if (overlapRatio > 0.45f && overlapRatio > bestOverlap) {
        best = block
        bestOverlap = overlapRatio
      }
      val nativeText = normalizeComparisonText(block.optString("sourceText"))
      if (left >= l && top >= t && right <= r && bottom <= b && lineText.length >= 4 &&
        nativeText.contains(lineText) && nativeArea < containedArea) {
        containedTextMatch = block
        containedArea = nativeArea
      }
    }
    return containedTextMatch ?: best
  }

  private fun normalizeComparisonText(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
      .lowercase(java.util.Locale.ROOT)
      .map { if (it.isWhitespace() || Character.isSpaceChar(it) || Character.isISOControl(it)) ' ' else it }
      .joinToString("")
      .replace(Regex(" +"), " ")
      .trim()

  private fun JSONObject.throwOnNativeError(fallback: String): JSONObject {
    if (has("error")) throw IllegalStateException(optString("error", fallback))
    return this
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
    }
    return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
  }

  private fun writeAtomically(file: File, contents: String) {
    val temporary = File(file.parentFile, "${file.name}.tmp")
    FileOutputStream(temporary).use { output ->
      output.write(contents.toByteArray(Charsets.UTF_8))
      output.fd.sync()
    }
    try {
      Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  override fun onDestroy() { activeOcrEngine?.stop(); worker.shutdownNow(); super.onDestroy() }

  companion object {
    const val ACTION_RUN = "dev.kigya.translatepdf.DEBUG_NATIVE_QA"
    const val EXTRA_MODEL_ID = "modelId"
    const val EXTRA_GLOSSARY_ID = "glossaryId"
    const val EXTRA_SOURCE_LANGUAGE = "sourceLanguage"
    const val EXTRA_TARGET_LANGUAGE = "targetLanguage"
    const val EXTRA_OCR_ONLY = "ocrOnly"
    const val EXTRA_ENQUEUE_NORMAL_JOB = "enqueueNormalJob"
    private const val DEFAULT_SOURCE_LANGUAGE = "en"
    private const val DEFAULT_TARGET_LANGUAGE = "ru"
    private const val QA_PROFILE_PREFS = "debug_native_qa_profile"
    private const val QA_PROFILE_REGISTERED_JOB = "registered_job_id"
    private const val QA_PROFILE_BYPASSED_JOB = "provisional_gate_bypassed_job_id"
  }
}

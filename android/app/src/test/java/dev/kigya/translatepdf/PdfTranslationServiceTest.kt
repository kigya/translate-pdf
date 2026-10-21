package dev.kigya.translatepdf

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [ShadowNativePdfCore::class])
class PdfTranslationServiceTest {
  private lateinit var context: Context
  private lateinit var database: TranslatorDatabase
  private lateinit var service: PdfTranslationService
  private lateinit var controller: org.robolectric.android.controller.ServiceController<PdfTranslationService>
  private lateinit var worker: ServiceQueuedExecutor
  private lateinit var downloader: ModelDownloader
  private var sequence = 0
  private val canceledJobs = mutableListOf<Int>()

  @Before fun setUp() {
    context = RuntimeEnvironment.getApplication()
    context.deleteDatabase("translator-private.db")
    database = TranslatorDatabase(context)
    ServiceNativeState.reset()
    ShadowNativePdfCore.resetCallbacks()
    ShadowNativePdfCore.inspectionResponse = null
    ShadowNativePdfCore.inspectCallback = { "{\"pageCount\":1}" }
    ShadowNativePdfCore.loadCallback = { ServiceNativeState.loadCalls++; ServiceNativeState.load() }
    ShadowNativePdfCore.readCallback = { ServiceNativeState.readCalls++; ServiceNativeState.page() }
    ShadowNativePdfCore.translateCallback = { ServiceNativeState.translation() }
    ShadowNativePdfCore.renderCallback = { ServiceNativeState.render() }
    ShadowNativePdfCore.exportCallback = { ServiceNativeState.export(it) }
    ShadowNativePdfCore.mergeCallback = { _, destination -> File(destination).writeText("%PDF-1.7\nmerged"); "{\"pageCount\":1}" }
    ShadowNativePdfCore.releaseCallback = { ServiceNativeState.releaseCalls++ }
    ShadowNativePdfCore.cancelCallback = { ServiceNativeState.cancelCalls++ }
    controller = Robolectric.buildService(PdfTranslationService::class.java).create()
    service = controller.get()
    val bytes = byteArrayOf(1, 2, 3)
    val definition = ModelCatalog.models.first().copy(bytes = bytes.size.toLong(), sha256 = sha(bytes))
    downloader = ModelDownloader(context, database, definitionLookup = { definition })
    downloader.fileFor(definition.id).apply { parentFile?.mkdirs(); writeBytes(bytes) }
    database.modelStatus(definition.id, "ready", definition.bytes)
    service.modelDownloaderFactory = { downloader }
    service.ocrEngineFactory = { language -> OcrEngine(service, language, api = ServiceOcrApi()) }
    service.memoryInfoProvider = { ActivityManager.MemoryInfo().apply { lowMemory = false } }
    service.availableDiskBytes = { Long.MAX_VALUE }
    service.stopSelfForCompletedWorker = { canceledJobs.add(it); Unit }
    service.stopSelfForStaleIntent = { canceledJobs.add(it); Unit }
    newWorker()
  }

  @After fun tearDown() {
    Thread.interrupted()
    controller.destroy()
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    database.close()
    context.deleteDatabase("translator-private.db")
    File(context.filesDir, "models").deleteRecursively()
    ShadowNativePdfCore.resetCallbacks()
  }

  private fun newWorker() {
    worker = ServiceQueuedExecutor()
    service.jobExecutorFactory = { worker }
    service.modelDownloadExecutorFactory = { worker }
  }
  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
  private fun createJob(pages: Int = 1, target: String = "ru"): String {
    val id = "service-${++sequence}"
    val source = File(context.filesDir, "$id.pdf").apply { writeText("%PDF-1.7\nsource") }
    database.upsertDocument("doc-$id", "source.pdf", source.absolutePath, pages, sha(source.readBytes()))
    database.addJob(id, JSONObject().put("documentId", "doc-$id").put("sourceLanguage", "en")
      .put("targetLanguage", target).put("modelId", ModelCatalog.RECOMMENDED_ID), pages)
    return id
  }
  private fun createOnlinePdfJob(): String {
    val id = "online-${++sequence}"
    val source = File(context.filesDir, "$id.pdf").apply { writeText("%PDF-1.7\nsource") }
    database.upsertDocument("doc-$id", "source.pdf", source.absolutePath, 1, sha(source.readBytes()))
    database.addJob(id, JSONObject().put("documentId", "doc-$id").put("sourceLanguage", "en")
      .put("targetLanguage", "ru").put("modelId", "mymemory")
      .put("engine", JSONObject().put("kind", "online").put("id", "mymemory"))
      .put("onlineConsent", true).put("useGlossary", false), 1)
    return id
  }
  private fun intent(action: String, id: String? = null): Intent = Intent(context, PdfTranslationService::class.java)
    .setAction(action).also { if (id != null) it.putExtra(PdfTranslationService.EXTRA_JOB_ID, id) }
  private fun start(id: String) {
    newWorker()
    assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, sequence))
    worker.runNext()
    Shadows.shadowOf(Looper.getMainLooper()).idle()
  }
  private fun job(id: String) = database.jobJson(id)!!
  private fun status(id: String) = job(id).getString("status")
  private fun error(id: String) = job(id).optString("error")
  private fun memoryAt(call: Int) {
    var calls = 0
    service.memoryInfoProvider = { ActivityManager.MemoryInfo().apply { lowMemory = ++calls == call } }
  }
  private fun invoke(name: String, vararg arguments: Any?): Any? {
    val method = PdfTranslationService::class.java.declaredMethods.single { it.name == name }
    method.isAccessible = true
    try { return method.invoke(service, *arguments) }
    catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
  }
  private fun setField(name: String, value: Any?) {
    PdfTranslationService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
  }

  @Test fun completedTranslationPersistsCheckpointsAndFinalPdf() {
    val id = createJob()
    start(id)
    assertEquals("completed", status(id))
    assertEquals("complete", job(id).getString("stage"))
    assertEquals(1, job(id).getInt("completedPages"))
    assertEquals("translated", database.pageBlockArray(id, 0).getJSONObject(0).getString("status"))
    assertTrue(ServiceNativeState.releaseCalls >= 1)
    assertTrue(worker.isShutdown)
    assertNull(PdfTranslationService.activeJobId)
  }

  @Test fun onlinePdfDoesNotLoadLocalModelAndResumesAfterQuotaWithoutRepeatingSavedFragments() {
    val id = createOnlinePdfJob()
    val longText = "a".repeat(600)
    ServiceNativeState.page = { JSONObject().put("width", 612).put("height", 792).put("blocks", JSONArray().put(
      ServiceNativeState.block().put("text", longText).put("sourceText", longText))).toString() }
    var calls = 0
    service.myMemoryTranslatorFactory = {
      MyMemoryTranslator { _, _, _ ->
        calls++
        if (calls == 1) """{"responseStatus":200,"responseData":{"translatedText":"Перевод"}}"""
        else """{"responseStatus":429,"responseDetails":"daily request limit reached"}"""
      }
    }
    start(id)
    assertEquals("paused", status(id))
    assertTrue(error(id).contains("quota"))
    assertEquals(0, ServiceNativeState.loadCalls)
    val savedBlock = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("Перевод", savedBlock.getJSONArray("translatedFragments").getJSONObject(0).getString("translated"))

    service.myMemoryTranslatorFactory = { MyMemoryTranslator { _, _, _ ->
      calls++
      """{"responseStatus":200,"responseData":{"translatedText":"ignored"}}"""
    } }
    newWorker()
    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, sequence)
    worker.runNext()
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals("completed", status(id))
    assertEquals(3, calls)
    assertEquals(0, ServiceNativeState.loadCalls)
    assertEquals("Переводignored", database.pageBlockArray(id, 0).getJSONObject(0).getString("translatedText"))
  }

  @Test fun onlineOfflineFailurePausesAtSavedFragmentAndResumeSkipsIt() {
    val id = createOnlinePdfJob()
    val sourceText = "a".repeat(600)
    ServiceNativeState.page = { JSONObject().put("width", 612).put("height", 792).put("blocks", JSONArray().put(
      ServiceNativeState.block().put("text", sourceText).put("sourceText", sourceText))).toString() }
    val requestedFragments = mutableListOf<String>()
    var offlineOnce = true
    service.myMemoryTranslatorFactory = {
      MyMemoryTranslator { fragment, _, _ ->
        requestedFragments += fragment
        if (offlineOnce && requestedFragments.size == 2) {
          offlineOnce = false
          throw MyMemoryException(MyMemoryFailure.OFFLINE)
        }
        """{"responseStatus":200,"responseData":{"translatedText":"Перевод"}}"""
      }
    }

    start(id)

    assertEquals("paused", status(id))
    assertTrue(error(id).contains("No network"))
    assertEquals(listOf(500, 100), requestedFragments.map { it.toByteArray(Charsets.UTF_8).size })
    assertEquals(1, database.pageBlockArray(id, 0).getJSONObject(0).getJSONArray("translatedFragments").length())
    assertEquals(0, job(id).getInt("completedPages"))
    assertEquals(0, ServiceNativeState.loadCalls)

    newWorker()
    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, sequence + 1)
    worker.runNext()
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals("completed", status(id))
    assertEquals(listOf(500, 100, 100), requestedFragments.map { it.toByteArray(Charsets.UTF_8).size })
    assertEquals("ПереводПеревод", database.pageBlockArray(id, 0).getJSONObject(0).getString("translatedText"))
    assertEquals(0, ServiceNativeState.loadCalls)
  }

  @Test fun onlinePauseDuringRequestKeepsPriorFragmentAndResumesWithoutDuplicateRequest() {
    val id = createOnlinePdfJob()
    val sourceText = "a".repeat(600)
    ServiceNativeState.page = { JSONObject().put("width", 612).put("height", 792).put("blocks", JSONArray().put(
      ServiceNativeState.block().put("text", sourceText).put("sourceText", sourceText))).toString() }
    val requestedFragments = mutableListOf<String>()
    service.myMemoryTranslatorFactory = {
      MyMemoryTranslator { fragment, _, _ ->
        requestedFragments += fragment
        if (requestedFragments.size == 2) {
          service.onStartCommand(intent(PdfTranslationService.ACTION_PAUSE, id), 0, sequence + 100)
        }
        """{"responseStatus":200,"responseData":{"translatedText":"Перевод"}}"""
      }
    }

    start(id)

    assertEquals("paused", status(id))
    assertEquals(listOf(500, 100), requestedFragments.map { it.toByteArray(Charsets.UTF_8).size })
    assertEquals(1, database.pageBlockArray(id, 0).getJSONObject(0).getJSONArray("translatedFragments").length())
    assertEquals(0, job(id).getInt("completedPages"))
    assertEquals(0, ServiceNativeState.loadCalls)

    service.myMemoryTranslatorFactory = { MyMemoryTranslator { fragment, _, _ ->
      requestedFragments += fragment
      """{"responseStatus":200,"responseData":{"translatedText":"Восстановлено"}}"""
    } }
    newWorker()
    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, sequence + 200)
    worker.runNext()
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals("completed", status(id))
    assertEquals(listOf(500, 100, 100), requestedFragments.map { it.toByteArray(Charsets.UTF_8).size })
    assertEquals("ПереводВосстановлено", database.pageBlockArray(id, 0).getJSONObject(0).getString("translatedText"))
    assertEquals(0, ServiceNativeState.loadCalls)
  }

  @Test fun onlineResumeRejectsRevokedConsentOrChangedProviderBeforeReadingText() {
    for ((column, value) in listOf("online_consent" to 0, "engine_id" to "unexpected")) {
      val id = createOnlinePdfJob()
      database.writableDatabase.execSQL("UPDATE jobs SET $column=? WHERE id=?", arrayOf(value, id))
      var requested = false
      service.myMemoryTranslatorFactory = {
        MyMemoryTranslator { _, _, _ ->
          requested = true
          """{"responseStatus":200,"responseData":{"translatedText":"should not be requested"}}"""
        }
      }

      start(id)

      assertEquals("failed", status(id))
      assertTrue(error(id).contains("consent"))
      assertFalse(requested)
      assertEquals(0, ServiceNativeState.readCalls)
      assertEquals(0, ServiceNativeState.loadCalls)
    }
  }

  @Test fun completedCheckpointSkipsModelReadinessAndInference() {
    val id = createJob()
    val blocks = JSONArray().put(ServiceNativeState.block().put("translatedText", "Строка").put("status", "translated"))
    database.savePage(id, 0, "completed", blocks.toString(), 612.0, 792.0, expectedBlocks = 1)
    downloader.fileFor(ModelCatalog.RECOMMENDED_ID).delete()
    start(id)
    assertEquals("completed", status(id))
    assertEquals(0, ServiceNativeState.loadCalls)
    assertEquals(0, ServiceNativeState.readCalls)
  }

  @Test fun resourcePressureAtEveryCheckpointPausesWithoutCommittingAResult() {
    for (call in 1..3) {
      val id = createJob()
      memoryAt(call)
      start(id)
      assertEquals("paused", status(id))
      assertTrue(error(id).contains("low memory"))
      assertEquals(0, job(id).getInt("completedPages"))
    }
    service.memoryInfoProvider = { ActivityManager.MemoryInfo() }
    service.availableDiskBytes = { 0 }
    val id = createJob()
    start(id)
    assertEquals("paused", status(id))
    assertTrue(error(id).contains("storage"))
  }

  @Test fun modelLoadFailuresPreserveTheResourceBoundary() {
    for (message in listOf("out of memory", "ALLOCATION FAILED", "bad_alloc", "cannot allocate", "unsupported model")) {
      val id = createJob()
      ServiceNativeState.load = { JSONObject().put("error", message).toString() }
      start(id)
      assertEquals(if (message == "unsupported model") "failed" else "paused", status(id))
    }
    for (failure in listOf(IllegalStateException("cannot allocate"), IllegalStateException("unsupported model"), IllegalStateException(), OutOfMemoryError())) {
      val id = createJob()
      ServiceNativeState.load = { throw failure }
      start(id)
      assertEquals(if (failure is IllegalStateException && failure.message != "cannot allocate") "failed" else "paused", status(id))
    }
  }

  @Test fun nativePageAndGeometryErrorsAreVisibleAndDoNotExport() {
    for (response in listOf("{\"error\":\"damaged\"}", "{\"width\":0,\"height\":792}", "{\"width\":612,\"height\":0}")) {
      val id = createJob()
      ServiceNativeState.page = { response }
      start(id)
      assertEquals("failed", status(id))
      assertEquals(0, job(id).getInt("completedPages"))
    }
  }

  @Test fun failedAndBlankTranslationsRetainOriginalsAsPartial() {
    for (response in listOf("{\"error\":\"decoder failed\"}", "{}", "{\"text\":\"   \"}", "{\"text\":\"128\"}")) {
      val id = createJob()
      ServiceNativeState.translation = { response }
      start(id)
      assertEquals("partial", status(id))
      val block = database.pageBlockArray(id, 0).getJSONObject(0)
      assertTrue(block.getString("status") in setOf("failed", "partial"))
      assertTrue(block.has("error"))
    }
    for (failure in listOf(IllegalStateException("bad result"), IllegalStateException())) {
      val id = createJob()
      ServiceNativeState.translation = { throw failure }
      start(id)
      assertEquals("partial", status(id))
    }
  }

  @Test fun translationAllocationFailuresPauseAndRetainBlockCheckpoint() {
    for (mode in 0..3) {
      val id = createJob()
      ServiceNativeState.translation = {
        when (mode) {
          0 -> "{\"error\":\"out of memory\"}"
          1 -> throw IllegalStateException("cannot allocate")
          2 -> throw OutOfMemoryError()
          else -> throw IllegalStateException("allocation failed")
        }
      }
      start(id)
      assertEquals("paused", status(id))
      assertEquals("failed", database.pageBlockArray(id, 0).getJSONObject(0).getString("status"))
      assertEquals(0, job(id).getInt("completedPages"))
    }
  }

  @Test fun renderAndOcrFailuresKeepNativeTextAndAddReviewWarnings() {
    for (mode in 0..3) {
      val id = createJob()
      ServiceNativeState.render = { if (mode < 2) throw IllegalStateException(if (mode == 0) "render failed" else null) else Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888) }
      service.ocrEngineFactory = { language ->
        if (mode == 2) throw IllegalStateException()
        OcrEngine(service, language, api = ServiceOcrApi(failRecognize = mode == 3))
      }
      start(id)
      assertEquals("partial", status(id))
      assertTrue(database.page(id, 0)!!.optString("error").isNotBlank())
    }
  }

  @Test fun pageLayoutFailureIsVisibleAndTemporaryValidationFileIsDeleted() {
    for (mode in 0..2) {
      val id = createJob(target = if (mode == 2) "zh-Hans" else "ru")
      ServiceNativeState.export = { destination ->
        when (mode) {
          0 -> "{\"error\":\"layout failed\"}"
          1 -> "{}"
          else -> { File(destination).writeText("%PDF-1.7"); "{\"partial\":true,\"issues\":[{\"blockId\":\"b0\",\"message\":\"review layout\"}]}" }
        }
      }
      start(id)
      assertEquals(if (mode == 2) "partial" else "failed", status(id))
      assertFalse(File(context.cacheDir, "page-validation/$id-0.pdf").exists())
    }
  }

  @Test fun pausedAndCanceledCommandsOnlyAffectTheRequestedWorker() {
    for (action in listOf(PdfTranslationService.ACTION_PAUSE, PdfTranslationService.ACTION_CANCEL)) {
      val first = createJob()
      val other = createJob()
      service.onStartCommand(intent(PdfTranslationService.ACTION_START, first), 0, 11)
      service.onStartCommand(intent(action, other), 0, 12)
      assertEquals(if (action == PdfTranslationService.ACTION_PAUSE) "paused" else "canceled", status(other))
      assertEquals(first, PdfTranslationService.activeJobId)
      service.onStartCommand(intent(action, first), 0, 13)
      assertEquals(if (action == PdfTranslationService.ACTION_PAUSE) "paused" else "canceled", status(first))
      controller.destroy()
      controller = Robolectric.buildService(PdfTranslationService::class.java).create()
      service = controller.get()
      service.modelDownloaderFactory = { downloader }
      service.stopSelfForCompletedWorker = { Unit }
      newWorker()
    }
    service.onStartCommand(intent(PdfTranslationService.ACTION_PAUSE), 0, 14)
    service.onStartCommand(intent(PdfTranslationService.ACTION_CANCEL), 0, 15)
    assertNull(service.onBind(null))
    assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 16))
    assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(Intent().setAction("unknown"), 0, 17))
  }

  @Test fun missingStartArgumentsAndBusyWorkerDoNotEnqueueMoreWork() {
    for (action in listOf(PdfTranslationService.ACTION_START, PdfTranslationService.ACTION_START_MODEL)) {
      service.onStartCommand(Intent().setAction(action), 0, 21)
    }
    service.onStartCommand(Intent().setAction(PdfTranslationService.ACTION_START_MODEL).putExtra(PdfTranslationService.EXTRA_MODEL_ID, ModelCatalog.RECOMMENDED_ID), 0, 22)
    val id = createJob()
    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, 23)
    service.onStartCommand(intent(PdfTranslationService.ACTION_START, createJob()), 0, 24)
    assertEquals(1, worker.tasks.size)
    assertFalse(service.stopServiceIfIdle(25))
  }

  @Test fun timeoutPausesActiveJobAndCancelsNativeBeforeStopping() {
    val id = createJob()
    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, 30)
    service.onTimeout(31, 1)
    assertEquals("paused", status(id))
    assertTrue(error(id).contains("time limit"))
    assertEquals(1, ServiceNativeState.cancelCalls)
    service.onStartCommand(intent(PdfTranslationService.ACTION_PAUSE_MODEL), 0, 32)
  }


  @Test fun nativeAndOcrCombinationKeepsDistinctLinesAndFlagsOnlyDisagreement() {
    val native = JSONArray().put(ServiceNativeState.block()).put(JSONObject().put("text", "   "))
      .put(JSONObject().put("sourceText", "Fallback").put("left", 300).put("top", 10).put("right", 400).put("bottom", 50))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "A line" to Rect(10, 10, 200, 50),
      "Different" to Rect(10, 10, 200, 50),
      "New" to Rect(10, 100, 200, 140),
    )))
    try {
      val result = invoke("combineNativeAndOcr", native, bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals(3, result.length())
      assertTrue(result.getJSONObject(0).getBoolean("sourceDisagreement"))
      assertEquals("Fallback", result.getJSONObject(1).getString("sourceText"))
      assertEquals("New", result.getJSONObject(2).getString("sourceText"))
      assertEquals(2, (invoke("combineNativeAndOcr", native, bitmap, 612f, 792f, null) as JSONArray).length())
      val overlapping = JSONArray().put(ServiceNativeState.block())
        .put(ServiceNativeState.block().put("id", "other").put("right", 300))
      val chosen = invoke("overlappingNativeBlock", overlapping, 10f, 10f, 200f, 50f) as JSONObject
      assertEquals("b0", chosen.getString("id"))
      assertNull(invoke("overlappingNativeBlock", overlapping, 400f, 400f, 450f, 450f))
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun restoredBlockStatesAreRetainedWithoutRepeatingTranslation() {
    for (savedStatus in listOf("translated", "partial", "failed", "pending")) {
      val id = createJob()
      database.savePage(id, 0, "running", JSONArray().put(ServiceNativeState.block()
        .put("sourceText", "Saved line").put("translatedText", "Сохранено").put("status", savedStatus)).toString(), 612.0, 792.0, expectedBlocks = 1)
      start(id)
      val block = database.pageBlockArray(id, 0).getJSONObject(0)
      assertEquals(if (savedStatus == "pending") "A line" else "Saved line", block.getString("sourceText"))
      assertEquals(if (savedStatus in setOf("partial", "failed")) "partial" else "completed", status(id))
    }
  }

  @Test fun interruptionsPreserveExplicitCancelAndPauseOnlyRunningJobs() {
    for (mode in 0..3) {
      val id = createJob()
      ServiceNativeState.translation = {
        when (mode) {
          0 -> { service.onStartCommand(intent(PdfTranslationService.ACTION_PAUSE, id), 0, 90); throw IllegalStateException("canceled") }
          1 -> { service.onStartCommand(intent(PdfTranslationService.ACTION_CANCEL, id), 0, 91); throw IllegalStateException("canceled") }
          2 -> { Thread.currentThread().interrupt(); throw IllegalStateException("interrupt") }
          else -> { Thread.currentThread().interrupt(); "{\"text\":\"Строка\"}" }
        }
      }
      start(id)
      Thread.interrupted()
      assertEquals(if (mode == 1) "canceled" else "paused", status(id))
    }
  }


  @Test fun modelDownloadCallbacksAndInterruptionsReconcileServiceOwnership() {
    val model = ModelCatalog.RECOMMENDED_ID
    service.modelDefinitionLookup = ModelCatalog::get
    for (mode in 0..5) {
      newWorker()
      val token = downloader.reserve(model)
      service.beginModelDownload = { _, progress, finished, _ ->
        progress(2_097_152)
        when (mode) {
          0 -> finished(Result.success(downloader.fileFor(model)))
          1 -> finished(Result.failure(IllegalStateException("offline")))
          2 -> finished(Result.failure(IllegalStateException()))
          3 -> finished(Result.failure(ModelDownloadCanceled()))
          4 -> { (PdfTranslationService::class.java.getDeclaredField("stopped").apply { isAccessible = true }.get(service) as AtomicBoolean).set(true); finished(Result.failure(IllegalStateException("after stop"))) }
          else -> Thread.currentThread().interrupt()
        }
      }
      val start = Intent().setAction(PdfTranslationService.ACTION_START_MODEL)
        .putExtra(PdfTranslationService.EXTRA_MODEL_ID, model).putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, token)
      service.onStartCommand(start, 0, 100 + mode)
      if (mode == 0) service.onStartCommand(intent(PdfTranslationService.ACTION_PAUSE_MODEL), 0, 110)
      worker.runNext()
      Thread.interrupted()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      assertTrue(worker.isShutdown)
      if (mode in 1..2) assertEquals("error", database.modelState(model)!!.first)
      downloader.releaseServiceStart(model, token)
      downloader.releaseReservation(model, token)
      downloader.cancel(model)
    }
    for (failure in listOf(IllegalStateException("definition unavailable"), IllegalStateException())) {
      newWorker()
      val token = downloader.reserve(model)
      service.modelDefinitionLookup = { throw failure }
      service.onStartCommand(Intent().setAction(PdfTranslationService.ACTION_START_MODEL)
        .putExtra(PdfTranslationService.EXTRA_MODEL_ID, model).putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, token), 0, 120)
      worker.runNext()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      assertEquals("error", database.modelState(model)!!.first)
      downloader.releaseServiceStart(model, token)
      downloader.releaseReservation(model, token)
      downloader.cancel(model)
    }
    val token = downloader.reserve(model)
    service.modelDownloadExecutorFactory = { throw IllegalStateException("executor unavailable") }
    val failure = runCatching { service.onStartCommand(Intent().setAction(PdfTranslationService.ACTION_START_MODEL)
      .putExtra(PdfTranslationService.EXTRA_MODEL_ID, model).putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, token), 0, 130) }
    assertEquals("executor unavailable", failure.exceptionOrNull()?.message)
    downloader.cancel(model)
  }

  @Test fun cleanupAndTimeoutRetainPlatformDefaultsAndStopActiveOcr() {
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi())
    setField("activeOcrEngine", ocr)
    setField("modelId", ModelCatalog.RECOMMENDED_ID)
    service.onTimeout(140, 1)
    service.qaProfileEnabled = { false }
    invoke("cleanupJobWorker", "missing", ocr, worker)
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    service.qaProfileEnabled = { true }
    invoke("cleanupJobWorker", "missing", null, worker)
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    val defaults = Robolectric.buildService(PdfTranslationService::class.java).create()
    try {
      defaults.get().modelDownloaderFactory()
      defaults.get().modelDefinitionLookup(ModelCatalog.RECOMMENDED_ID)
      assertNotNull(defaults.get().beginModelDownload)
      defaults.get().modelDownloadExecutorFactory().shutdown()
      defaults.get().jobExecutorFactory().shutdown()
      defaults.get().stopSelfForStaleIntent(150)
      defaults.get().stopSelfForCompletedWorker(151)
      assertEquals(BuildConfig.DEBUG, defaults.get().qaProfileEnabled())
    } finally { defaults.destroy() }
  }

  @Test @Config(sdk = [26]) fun minimumSupportedApiStartsForegroundWithoutTypedServiceFlags() {
    val id = createJob()
    start(id)
    assertEquals("completed", status(id))
  }


  @Test fun missingDatabaseResultsFailWithSpecificMessages() {
    start("missing-job")
    assertTrue(worker.isShutdown)
    val id = createJob()
    val sourceUnavailable = TranslatorDatabase(context, queryForRead = { db, sql, args ->
      if (sql.startsWith("SELECT d.path")) MatrixCursor(arrayOf("path")) else db.rawQuery(sql, args)
    })
    controller.destroy()
    controller = Robolectric.buildService(PdfTranslationService::class.java).create()
    service = controller.get()
    service.databaseFactory = { sourceUnavailable }
    service.stopSelfForCompletedWorker = { Unit }
    service.modelDownloaderFactory = { downloader }
    try {
      start(id)
      assertEquals("failed", status(id))
      assertEquals("Source document is unavailable.", error(id))
    } finally { sourceUnavailable.close() }
  }

  @Test fun cancellationAtEveryPipelinePhaseStopsBeforeFinalCommit() {
    for (phase in 0..8) {
      val id = createJob()
      ServiceNativeState.reset()
      var exports = 0
      var inspections = 0
      fun stop() {
        if (phase in setOf(1, 3, 6, 8)) Thread.currentThread().interrupt()
        else service.onStartCommand(intent(PdfTranslationService.ACTION_PAUSE, id), 0, 200 + phase)
      }
      ServiceNativeState.load = { if (phase <= 1) stop(); "{}" }
      ServiceNativeState.render = { if (phase in 2..3) stop(); Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888) }
      ServiceNativeState.export = { destination ->
        exports++
        if ((phase == 4 && exports == 1) || (phase in 5..6 && exports == 2)) stop()
        File(destination).writeText("%PDF-1.7\npage")
        "{\"partial\":false,\"issues\":[]}"
      }
      ShadowNativePdfCore.inspectCallback = { if (++inspections == 3 && phase >= 7) stop(); "{\"pageCount\":1}" }
      start(id)
      Thread.interrupted()
      assertEquals("phase $phase", "paused", status(id))
      assertNotEquals("complete", job(id).getString("stage"))
    }
  }

  @Test fun emptyPageAndCheckpointFallbacksRemainExplicitlyPartial() {
    val missingBlocks = createJob()
    ServiceNativeState.page = { "{\"width\":612,\"height\":792}" }
    start(missingBlocks)
    assertEquals("partial", status(missingBlocks))
    val missingBlocksAfterOcrFailure = createJob()
    service.ocrEngineFactory = { language -> OcrEngine(service, language, api = ServiceOcrApi(failRecognize = true, nullFailure = true)) }
    start(missingBlocksAfterOcrFailure)
    assertEquals("partial", status(missingBlocksAfterOcrFailure))
    ServiceNativeState.page = { ServiceNativeState.defaultPage() }
    service.ocrEngineFactory = { throw IllegalStateException("OCR setup failed") }
    val setup = createJob()
    start(setup)
    assertEquals("partial", status(setup))
    service.ocrEngineFactory = { language -> OcrEngine(service, language, api = ServiceOcrApi()) }
    val emptySaved = createJob()
    database.savePage(emptySaved, 0, "running", JSONArray().put(ServiceNativeState.block().put("sourceText", " ").put("status", "partial")).toString(), 612.0, 792.0)
    start(emptySaved)
    assertEquals("partial", status(emptySaved))
    val multiPage = createJob(2)
    database.savePage(multiPage, 1, "completed", JSONArray().put(ServiceNativeState.block().put("status", "translated").put("translatedText", "Строка")).toString(), 612.0, 792.0)
    start(multiPage)
    // The test JNI boundary intentionally reports one final page, so integrity
    // fails after proving that page0 was processed and saved page1 was skipped.
    assertEquals(2, job(multiPage).getInt("completedPages"))
  }

  @Test fun layoutChecksHandleEmptyFilesAbsentIssuesAndReviewOnlyIssues() {
    val id = createJob()
    val source = database.jobDocumentPath(id)!!
    val blocks = JSONArray().put(ServiceNativeState.block().put("translatedText", "Строка"))
    val cases = listOf<Pair<Boolean, (String) -> String>>(
      false to { path -> File(path).writeText(""); "{}" },
      true to { path -> File(path).writeText("%PDF-1.7"); "{}" },
      true to { path -> File(path).writeText("%PDF-1.7"); "{\"issues\":[{\"blockId\":\"b0\",\"message\":\"review\"}]}" },
    )
    for ((success, callback) in cases) {
      ServiceNativeState.export = callback
      val result = runCatching { invoke("validatePageExport", source, 0, blocks, id, "ru") }
      assertEquals(success, result.isSuccess)
      assertFalse(File(context.cacheDir, "page-validation/$id-0.pdf").exists())
    }
    val raw = ServiceNativeState.block().apply { remove("objectIndices") }
    val combined = invoke("combineNativeAndOcr", JSONArray().put(raw), null, 612f, 792f, null) as JSONArray
    assertEquals(0, combined.getJSONObject(0).getJSONArray("objectIndices").getInt(0))
  }

  @Test fun destructionContinuesAfterEveryBoundaryFailureAndStopsActiveOcr() {
    val id = createJob()
    database.updateJob(id, status = "running")
    val order = mutableListOf<String>()
    TranslationServiceDestroyHandler.run(AtomicBoolean(false), database, id,
      cancelNative = { order.add("native"); throw IllegalStateException() },
      stopOcr = { order.add("ocr"); throw IllegalStateException() },
      pauseDownload = { order.add("download"); throw IllegalStateException() },
      interruptWorker = { order.add("worker"); throw IllegalStateException() })
    assertEquals(listOf("native", "ocr", "download", "worker"), order)
    assertEquals("paused", status(id))
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi())
    setField("activeOcrEngine", ocr)
    controller.destroy()
    ocr.close()
    controller = Robolectric.buildService(PdfTranslationService::class.java).create()
    service = controller.get()
    ShadowNativePdfCore.releaseCallback = { throw IllegalStateException("release failed") }
    service.onTimeout(250, 1)
    invoke("cleanupJobWorker", id, null, worker)
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertTrue(worker.isShutdown)
  }


  @Test fun missingJobDuringInterruptCleanupDoesNotInventAStatus() {
    val id = createJob()
    val racing = TranslatorDatabase(context, queryForRead = { db, sql, args ->
      val cursor = db.rawQuery(sql, args)
      if (sql.startsWith("SELECT j.id") && Thread.currentThread().isInterrupted) {
        val columns = cursor.columnNames
        cursor.close()
        MatrixCursor(columns)
      } else cursor
    })
    service.databaseFactory = { racing }
    ServiceNativeState.load = { Thread.currentThread().interrupt(); "{}" }
    try {
      start(id)
      Thread.interrupted()
      assertEquals("running", status(id))
      assertTrue(worker.isShutdown)
    } finally { Thread.interrupted(); racing.close() }
  }

  @Test fun notificationHasNoLaunchActionWhenPackageManagerCannotResolveOne() {
    val component = android.content.ComponentName(context, MainActivity::class.java)
    context.packageManager.setComponentEnabledSetting(component, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
    try {
      val notification = invoke("notification", "local progress") as android.app.Notification
      assertNull(notification.contentIntent)
    } finally {
      context.packageManager.setComponentEnabledSetting(component, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, android.content.pm.PackageManager.DONT_KILL_APP)
    }
  }

  @Test @Config(shadows = [ShadowNativePdfCore::class, ShadowTessBaseAPI::class, ShadowPageIterator::class, ShadowResultIterator::class])
  fun resourceDependenciesDefaultToPlatformReadings() {
    val defaults = Robolectric.buildService(PdfTranslationService::class.java).create()
    try {
      assertNotNull(defaults.get().memoryInfoProvider())
      assertTrue(defaults.get().availableDiskBytes() >= 0)
      defaults.get().databaseFactory().close()
      defaults.get().ocrEngineFactory("en").close()
    } finally { defaults.destroy() }
  }
}

private class ServiceQueuedExecutor : AbstractExecutorService() {
  val tasks = mutableListOf<Runnable>()
  private var shut = false
  override fun execute(command: Runnable) { check(!shut); tasks.add(command) }
  fun runNext() { tasks.removeAt(0).run() }
  override fun shutdown() { shut = true }
  override fun shutdownNow(): MutableList<Runnable> { shut = true; return tasks.toMutableList().also { tasks.clear() } }
  override fun isShutdown() = shut
  override fun isTerminated() = shut && tasks.isEmpty()
  override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
}

private class ServiceOcrApi(private val failRecognize: Boolean = false, private val nullFailure: Boolean = false, private val lines: List<Pair<String, Rect>> = emptyList()) : OcrEngineApi {
  override fun initialize(dataDirectory: String, language: String) = true
  override fun setAutomaticPageSegmentation() = Unit
  override fun setImage(bitmap: Bitmap) = Unit
  override fun recognize() { if (failRecognize) throw if (nullFailure) IllegalStateException() else IllegalStateException("OCR failed") }
  override fun resultIterator(): OcrLineIterator = object : OcrLineIterator {
    private var index = 0
    override fun begin() { index = 0 }
    override fun text(): String? = lines.getOrNull(index)?.first
    override fun bounds() = lines.getOrNull(index)?.second ?: Rect()
    override fun next(): Boolean { index++; return index < lines.size }
    override fun delete() = Unit
  }
  override fun clear() = Unit
  override fun stop() = Unit
  override fun recycle() = Unit
}

internal object ServiceNativeState {
  var load: () -> String = { "{}" }
  var page: () -> String = { defaultPage() }
  var translation: () -> String = { "{\"text\":\"Строка\"}" }
  var render: () -> Bitmap = { Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888) }
  var export: (String) -> String = { File(it).writeText("%PDF-1.7\npage"); "{\"partial\":false,\"issues\":[]}" }
  var loadCalls = 0
  var readCalls = 0
  var releaseCalls = 0
  var cancelCalls = 0
  fun block() = JSONObject().put("id", "b0").put("text", "A line").put("sourceText", "A line")
    .put("left", 10).put("top", 10).put("right", 200).put("bottom", 50).put("fontSize", 12)
    .put("objectIndex", 0).put("objectIndices", JSONArray().put(0)).put("status", "pending")
  fun defaultPage() = JSONObject().put("width", 612).put("height", 792).put("blocks", JSONArray().put(block())).toString()
  fun reset() {
    load = { "{}" }; page = { defaultPage() }; translation = { "{\"text\":\"Строка\"}" }
    render = { Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888) }
    export = { File(it).writeText("%PDF-1.7\npage"); "{\"partial\":false,\"issues\":[]}" }
    loadCalls = 0; readCalls = 0; releaseCalls = 0; cancelCalls = 0
  }
}

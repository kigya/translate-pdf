package dev.kigya.translatepdf

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.app.Application
import android.content.Intent
import android.os.Looper
import java.io.File
import java.security.MessageDigest
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class TranslatorDatabaseTest {
  @Test
  fun initialSelectionStaysSavedWhenTheResourceRecommendationChanges() {
    val compact = ModelCatalog.models.first().id
    val balanced = ModelCatalog.models[1].id
    assertEquals(compact, database.selectedModelId(compact))
    assertEquals(compact, database.selectedModelId(balanced))
    database.setSetting("selectedModelId", balanced)
    assertEquals(balanced, database.selectedModelId(compact))
    database.setSetting("selectedModelId", "hy-mt2-iq2-m")
    assertEquals(compact, database.selectedModelId(compact))
  }
  @Test
  fun sampleEvaluationDoesNotMarkAModelQualityVerified() {
    for ((model, expected) in ModelCatalog.models.zip(listOf(17, 29, 31))) {
      val snapshot = model.toJson("not_downloaded", 0)
      assertEquals(expected, snapshot.getJSONObject("evaluation").getInt("acceptableCases"))
      assertEquals(44, snapshot.getJSONObject("evaluation").getInt("totalCases"))
      assertFalse(snapshot.getBoolean("verified"))
    }
    assertFalse(ModelCatalog.get("hy-mt2-iq2-m").toJson("not_downloaded", 0).has("evaluation"))
  }
  private lateinit var context: Context
  private lateinit var database: TranslatorDatabase

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    context.deleteDatabase("translator-private.db")
    database = TranslatorDatabase(context)
  }

  @Test
  fun deletingThroughModuleDownloaderCancelsServiceInstanceTransferBeforeRemovingFiles() {
    val model = ModelCatalog.models.first()
    val serving = CountDownLatch(1)
    val sawProgress = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val outcome = java.util.concurrent.atomic.AtomicReference<Result<File>>()
    val server = ServerSocket(0)
    val serverThread = Thread {
      try {
        server.accept().use { socket ->
          socket.soTimeout = 10_000
          val request = socket.getInputStream().bufferedReader()
          while (request.readLine()?.isNotEmpty() == true) Unit
          val output = socket.getOutputStream()
          output.write("HTTP/1.1 200 OK\r\nContent-Length: ${model.bytes}\r\nConnection: close\r\n\r\n".toByteArray())
          output.flush()
          serving.countDown()
          val chunk = ByteArray(16 * 1024)
          repeat(256) {
            output.write(chunk)
            output.flush()
            Thread.sleep(2)
          }
        }
      } catch (_: Exception) {
        // Disconnect after the module instance cancels the service-owned transfer.
      } finally { server.close() }
    }.apply { isDaemon = true; start() }

    val serviceDownloader = ModelDownloader(context, database)
    val moduleDatabase = TranslatorDatabase(context)
    val moduleDownloader = ModelDownloader(context, moduleDatabase)
    serviceDownloader.start(model.id, onProgress = { if (it >= 1_048_576) sawProgress.countDown() }, onFinished = {
      outcome.set(it)
      finished.countDown()
    }, downloadUrl = URL("http://127.0.0.1:${server.localPort}/model"))

    try {
      assertTrue("local fake HTTP server should accept the service transfer", serving.await(5, TimeUnit.SECONDS))
      assertTrue("service instance should receive bytes before cancellation", sawProgress.await(10, TimeUnit.SECONDS))
      moduleDownloader.cancel(model.id)
      assertTrue("the service callback should finish before deletion resolves", finished.await(5, TimeUnit.SECONDS))
      assertTrue("the canceled callback should report failure", outcome.get().isFailure)
      assertFalse("the module cancellation must remove the service transfer's partial file", serviceDownloader.fileFor(model.id).resolveSibling("${model.id}.part").exists())
      assertFalse("the module cancellation must not leave a verified model", serviceDownloader.fileFor(model.id).exists())
    } finally {
      server.close()
      moduleDatabase.close()
      serverThread.join(1000)
    }
  }

  @Test
  fun deletingQueuedServiceStartTombstonesItsReservationBeforeServiceDelivery() {
    val model=ModelCatalog.models.first()
    val serviceDownloader=ModelDownloader(context,database)
    val moduleDatabase=TranslatorDatabase(context)
    val moduleDownloader=ModelDownloader(context,moduleDatabase)
    val token=serviceDownloader.reserve(model.id)
    val server=ServerSocket(0).apply { soTimeout=1000 }
    val accepted=java.util.concurrent.atomic.AtomicBoolean(false)
    val serverThread=Thread {
      try { server.accept().use { accepted.set(true) } }
      catch (_: java.net.SocketTimeoutException) { }
      finally { server.close() }
    }.apply { isDaemon=true;start() }
    val outcome=java.util.concurrent.atomic.AtomicReference<Result<File>>()

    try {
      moduleDownloader.cancel(model.id)
      moduleDatabase.deleteModel(model.id)
      val enqueueAttempted=java.util.concurrent.atomic.AtomicBoolean(false)
      assertTrue("a canceled queued intent must fail its pre-enqueue reservation check",runCatching {
        serviceDownloader.withReservation(model.id,token) { enqueueAttempted.set(true) }
      }.isFailure)
      assertFalse("delete must prevent any later service enqueue",enqueueAttempted.get())
      serviceDownloader.start(model.id,onProgress={},onFinished={outcome.set(it)},downloadUrl=URL("http://127.0.0.1:${server.localPort}/model"),transferToken=token)
      assertTrue("queued service delivery should receive the canceled reservation",outcome.get().isFailure)
      serverThread.join(1500)
      assertFalse("a queued service command delivered after delete must not open the network connection",accepted.get())
      assertFalse("a queued service command delivered after delete must not recreate the model file",serviceDownloader.fileFor(model.id).exists())
      assertFalse("a queued service command delivered after delete must not recreate the partial file",serviceDownloader.fileFor(model.id).resolveSibling("${model.id}.part").exists())
      assertNull("a queued service command delivered after delete must not recreate model state",database.modelState(model.id))
    } finally {
      server.close()
      moduleDatabase.close()
      serverThread.join(1000)
    }
  }

  @Test
  fun staleQueuedServiceStartDoesNotBlockNewReservationFromRunning() {
    val model=ModelCatalog.models.first()
    val serviceDownloader=ModelDownloader(context,database)
    val moduleDatabase=TranslatorDatabase(context)
    val moduleDownloader=ModelDownloader(context,moduleDatabase)
    val staleToken=serviceDownloader.reserve(model.id)
    val server=ServerSocket(0)
    val accepted=CountDownLatch(1)
    val serverThread=Thread {
      try {
        server.accept().use { socket ->
          val request=socket.getInputStream().bufferedReader()
          while (request.readLine()?.isNotEmpty() == true) Unit
          socket.getOutputStream().apply {
            write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
          }
          accepted.countDown()
        }
      } finally { server.close() }
    }.apply { isDaemon=true;start() }
    val nextFinished=CountDownLatch(1)
    val nextOutcome=java.util.concurrent.atomic.AtomicReference<Result<File>>()
    var nextToken: String? = null

    try {
      moduleDownloader.cancel(model.id)
      moduleDatabase.deleteModel(model.id)
      nextToken=moduleDownloader.reserve(model.id)

      assertFalse("late delivery of the deleted reservation must be rejected before service work",serviceDownloader.claimServiceStart(model.id,staleToken))
      assertTrue("the current reservation must remain claimable after stale delivery",serviceDownloader.claimServiceStart(model.id,nextToken!!))
      serviceDownloader.start(model.id,onProgress={},onFinished={ nextOutcome.set(it);nextFinished.countDown() },downloadUrl=URL("http://127.0.0.1:${server.localPort}/model"),transferToken=nextToken)
      assertTrue("the new service transfer should reach the local HTTP server",accepted.await(5,TimeUnit.SECONDS))
      assertTrue("the new service transfer should finish its test response",nextFinished.await(5,TimeUnit.SECONDS))
      assertTrue("the valid new reservation should run and report the fake HTTP failure",nextOutcome.get().isFailure)
      assertFalse("the valid new transfer should not be mistaken for the stale cancellation",nextOutcome.get().exceptionOrNull() is ModelDownloadCanceled)
    } finally {
      nextToken?.let { moduleDownloader.cancel(model.id) }
      server.close()
      moduleDatabase.close()
      serverThread.join(1000)
    }
  }

  @Test
  fun onStartCommand_staleModelIntentDoesNotStopServiceWithActiveExecutor() {
    val modelA=ModelCatalog.models.first()
    val modelB=ModelCatalog.models[1]
    val downloader=ModelDownloader(context,database)
    val staleA=downloader.reserve(modelA.id)
    downloader.cancel(modelA.id)
    database.deleteModel(modelA.id)
    val activeB=downloader.reserve(modelB.id)
    assertTrue(downloader.claimServiceStart(modelB.id,activeB))

    val controller=Robolectric.buildService(PdfTranslationService::class.java).create()
    val service=controller.get()
    val busy=Executors.newSingleThreadExecutor()
    setExecutor(service,busy)
    var stopRequested=false
    service.stopSelfForStaleIntent={ stopRequested=true }
    try {
      val staleIntent=Intent(context,PdfTranslationService::class.java)
        .setAction(PdfTranslationService.ACTION_START_MODEL)
        .putExtra(PdfTranslationService.EXTRA_MODEL_ID,modelA.id)
        .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN,staleA)
      service.onStartCommand(staleIntent,0,1)

      assertFalse("stale intent must preserve the service while B owns its executor",stopRequested)
      assertTrue("the active B reservation must remain registered",runCatching { downloader.reserve(modelB.id) }.isFailure)
      assertTrue("the current service instance must remain active",PdfTranslationService.isActive)
      assertEquals("the active executor must remain attached",busy,getExecutor(service))
    } finally {
      downloader.releaseServiceStart(modelB.id,activeB)
      downloader.releaseReservation(modelB.id,activeB)
      controller.destroy()
      busy.shutdownNow()
    }
  }

  @Test
  fun onStartCommand_deliversNewReservationAfterCanceledStaleIntent() {
    val model=ModelCatalog.models.first()
    val downloader=ModelDownloader(context,database)
    val staleToken=downloader.reserve(model.id)
    downloader.cancel(model.id)
    database.deleteModel(model.id)
    val currentToken=downloader.reserve(model.id)
    database.modelStatus(model.id,"downloading",downloader.partialLength(model.id))

    val controller=Robolectric.buildService(PdfTranslationService::class.java).create()
    val service=controller.get()
    val queuedExecutor=QueuedExecutor()
    val stoppedIds=mutableListOf<Int>()
    service.stopSelfForStaleIntent={ stoppedIds.add(it) }
    service.modelDownloadExecutorFactory={ queuedExecutor }
    try {
      service.onStartCommand(modelIntent(model.id,staleToken),0,1)
      assertEquals("only the stale start id is eligible to stop when the service is idle",listOf(1),stoppedIds)
      assertNull("stale A must not allocate a service worker",getExecutor(service))

      service.onStartCommand(modelIntent(model.id,currentToken),0,2)
      assertEquals("valid B should be accepted after stale A",queuedExecutor,getExecutor(service))
      assertEquals("B should queue its model worker",1,queuedExecutor.queuedCount)
      assertTrue("B's reservation must be marked as claimed",runCatching { downloader.reserve(model.id) }.isFailure)
    } finally {
      downloader.releaseServiceStart(model.id,currentToken)
      downloader.releaseReservation(model.id,currentToken)
      controller.destroy()
      queuedExecutor.shutdownNow()
    }
  }

  @Test
  fun onStartCommand_validModelWhenBusyReleasesItAsRetryableWithoutTouchingActiveModel() {
    val activeModel=ModelCatalog.models.first()
    val requestedModel=ModelCatalog.models[1]
    val downloader=ModelDownloader(context,database)
    val activeToken=downloader.reserve(activeModel.id)
    assertTrue(downloader.claimServiceStart(activeModel.id,activeToken))
    database.modelStatus(activeModel.id,"downloading",0)
    val requestedToken=downloader.reserve(requestedModel.id)
    database.modelStatus(requestedModel.id,"downloading",downloader.partialLength(requestedModel.id))

    val controller=Robolectric.buildService(PdfTranslationService::class.java).create()
    val service=controller.get()
    val busy=Executors.newSingleThreadExecutor()
    setExecutor(service,busy)
    try {
      val requestedIntent=Intent(context,PdfTranslationService::class.java)
        .setAction(PdfTranslationService.ACTION_START_MODEL)
        .putExtra(PdfTranslationService.EXTRA_MODEL_ID,requestedModel.id)
        .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN,requestedToken)
      service.onStartCommand(requestedIntent,0,1)

      assertEquals("busy service should leave B paused and retryable","paused",database.modelState(requestedModel.id)?.first)
      assertFalse("B's reservation should be released after it cannot be scheduled",downloader.claimServiceStart(requestedModel.id,requestedToken))
      assertTrue("A's active reservation must remain intact",runCatching { downloader.reserve(activeModel.id) }.isFailure)
      assertEquals("A's state must not be overwritten","downloading",database.modelState(activeModel.id)?.first)
      val retryToken=downloader.reserve(requestedModel.id)
      assertTrue("B can be reserved again after the service becomes available",downloader.claimServiceStart(requestedModel.id,retryToken))
      downloader.releaseServiceStart(requestedModel.id,retryToken)
      downloader.releaseReservation(requestedModel.id,retryToken)
    } finally {
      downloader.releaseServiceStart(activeModel.id,activeToken)
      downloader.releaseReservation(activeModel.id,activeToken)
      controller.destroy()
      busy.shutdownNow()
    }
  }

  @Test
  fun completionAfterStaleIntentStopsIdleServiceUsingLatestStartId() {
    val model=ModelCatalog.models.first()
    val downloader=ModelDownloader(context,database)
    val staleToken=downloader.reserve(model.id)
    downloader.cancel(model.id)
    database.deleteModel(model.id)
    val currentToken=downloader.reserve(model.id)
    database.modelStatus(model.id,"downloading",0)

    val controller=Robolectric.buildService(PdfTranslationService::class.java).create()
    val service=controller.get()
    val owner=QueuedExecutor()
    val stoppedIds=mutableListOf<Int>()
    val completedIds=mutableListOf<Int>()
    service.modelDownloadExecutorFactory={ owner }
    service.stopSelfForStaleIntent={ stoppedIds.add(it) }
    service.stopSelfForCompletedWorker={ completedIds.add(it) }
    try {
      service.onStartCommand(modelIntent(model.id,currentToken),0,8)
      service.onStartCommand(modelIntent(model.id,staleToken),0,9)
      assertTrue("stale intent must not stop the active worker",stoppedIds.isEmpty())
      assertEquals("active worker remains owned until completion",owner,getExecutor(service))

      service.finishWorker(owner)
      Shadows.shadowOf(Looper.getMainLooper()).idle()

      assertEquals("worker completion should stop using the newest delivered start id",listOf(9),completedIds)
      assertNull("the finished owner should be cleared",getExecutor(service))
      assertTrue("the finished worker executor should be shut down",owner.isShutdown)
    } finally {
      downloader.releaseServiceStart(model.id,currentToken)
      downloader.releaseReservation(model.id,currentToken)
      controller.destroy()
      owner.shutdownNow()
    }
  }

  @Test
  fun obsoleteWorkerCompletionCannotShutdownOrDetachNewOwner() {
    val controller=Robolectric.buildService(PdfTranslationService::class.java).create()
    val service=controller.get()
    val oldOwner=QueuedExecutor()
    val currentOwner=QueuedExecutor()
    val completedIds=mutableListOf<Int>()
    setExecutor(service,currentOwner)
    service.stopSelfForCompletedWorker={ completedIds.add(it) }
    try {
      service.finishWorker(oldOwner)
      Shadows.shadowOf(Looper.getMainLooper()).idle()

      assertTrue("the completed obsolete owner can be disposed",oldOwner.isShutdown)
      assertFalse("obsolete completion cannot shutdown the current owner",currentOwner.isShutdown)
      assertEquals("obsolete completion cannot stop the service",emptyList<Int>(),completedIds)
      assertEquals("obsolete completion cannot clear the current owner",currentOwner,getExecutor(service))
    } finally {
      controller.destroy()
      currentOwner.shutdownNow()
      oldOwner.shutdownNow()
    }
  }

  private fun setExecutor(service: PdfTranslationService, executor: ExecutorService) {
    PdfTranslationService::class.java.getDeclaredField("executor").apply {
      isAccessible=true
      set(service,executor)
    }
  }

  private fun getExecutor(service: PdfTranslationService): ExecutorService? =
    PdfTranslationService::class.java.getDeclaredField("executor").let { field ->
      field.isAccessible=true
      field.get(service) as? ExecutorService
    }

  private fun modelIntent(modelId: String, token: String): Intent =
    Intent(context,PdfTranslationService::class.java)
      .setAction(PdfTranslationService.ACTION_START_MODEL)
      .putExtra(PdfTranslationService.EXTRA_MODEL_ID,modelId)
      .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN,token)

  private class QueuedExecutor : AbstractExecutorService() {
    private val tasks=mutableListOf<Runnable>()
    private var shutdown=false
    val queuedCount: Int get() = tasks.size
    override fun execute(command: Runnable) { check(!shutdown);tasks.add(command) }
    override fun shutdown() { shutdown=true }
    override fun shutdownNow(): MutableList<Runnable> { shutdown=true;return tasks.toMutableList().also { tasks.clear() } }
    override fun isShutdown(): Boolean = shutdown
    override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
  }

  @After
  fun tearDown() {
    database.close()
    context.deleteDatabase("translator-private.db")
  }

  @Test
  fun changedPipelineCannotResumeOldBlockCheckpoints() {
    createJob("pipeline-job")
    database.requireCurrentPipeline("pipeline-job")
    val saved = JSONArray().put(JSONObject().put("id", "old-line").put("sourceText", "Do not")
      .put("translatedText", "Не").put("status", "translated"))
    database.savePage("pipeline-job", 0, "running", saved.toString(), expectedBlocks = 1)
    database.writableDatabase.execSQL("UPDATE jobs SET pipeline_version=3 WHERE id='pipeline-job'")
    val failure = runCatching { database.requireCurrentPipeline("pipeline-job") }.exceptionOrNull()
    assertTrue(failure is IllegalStateException)
    assertTrue(failure!!.message!!.contains("Start a new job"))
    assertTrue(database.jobJson("pipeline-job") != null)
    assertEquals(saved.toString(), database.pageBlockArray("pipeline-job", 0).toString())
    assertTrue(runCatching { database.requireCurrentPipeline("missing") }.isFailure)
  }

  @Test
  fun aNewJobPinsItsProfileAndRejectsChangedSourceOrModelInputs() {
    val file = File(context.filesDir, "frozen-source.pdf").apply { writeText("source one") }
    val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val model = ModelCatalog.models[1]
    database.upsertDocument("frozen-doc", "source.pdf", file.absolutePath, 1, hash)
    val job = database.addJob("frozen-job", JSONObject().put("documentId", "frozen-doc")
      .put("sourceLanguage", "en").put("targetLanguage", "ru").put("modelId", model.id), 1)
    assertEquals(model.id, job.getString("modelId"))
    assertEquals(model.profile, job.getString("modelProfile"))
    assertEquals(4, job.getInt("pipelineVersion"))
    database.validateJobSnapshot("frozen-job", model)
    database.setSetting("selectedModelId", ModelCatalog.models.last().id)
    database.validateJobSnapshot("frozen-job", model)
    assertEquals(model.id, database.jobJson("frozen-job")!!.getString("modelId"))
    assertTrue(runCatching { database.validateJobSnapshot("frozen-job", model.copy(profile = "changed")) }.isFailure)
    assertTrue(runCatching { database.validateJobSnapshot("frozen-job", model.copy(revision = "changed")) }.isFailure)
    file.writeText("source two")
    assertTrue(runCatching { database.validateJobSnapshot("frozen-job", model) }.isFailure)
    assertTrue(database.jobJson("frozen-job") != null)
    file.delete()
  }

  @Test
  fun languagePairValidationAllowsExactlyTheThirtyDirectionalPairs() {
    assertEquals(listOf("ru", "zh-Hans", "fr", "en", "de", "es"), LanguagePair.codes)
    var validPairs = 0
    for (source in LanguagePair.codes) for (target in LanguagePair.codes) {
      val valid = source != target
      assertEquals(valid, runCatching { LanguagePair.requireValid(source, target) }.isSuccess)
      if (valid) validPairs++
    }
    assertEquals(30, validPairs)
    assertTrue(runCatching { LanguagePair.requireValid("zh", "en") }.isFailure)
  }

  @Test
  fun languagePairPreferencesPersistWithEnglishRussianDefaults() {
    assertEquals("en" to "ru", database.selectedLanguagePair())
    database.setSetting("sourceLanguage", "zh-Hans")
    database.setSetting("targetLanguage", "fr")
    assertEquals("zh-Hans" to "fr", database.selectedLanguagePair())
  }

  @Test
  fun existingGlossaryRowsMigrateToEnglishRussian() {
    database.close()
    val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("translator-private.db"), null)
    legacy.execSQL("CREATE TABLE glossary (id TEXT PRIMARY KEY, source TEXT NOT NULL, target TEXT NOT NULL, created_at INTEGER NOT NULL)")
    legacy.execSQL("INSERT INTO glossary(id,source,target,created_at) VALUES('legacy','wheel nut','гайка',1)")
    legacy.version = 3
    legacy.close()
    database = TranslatorDatabase(context)

    val entry = database.glossaryJson().getJSONObject(0)
    assertEquals("en", entry.getString("sourceLanguage"))
    assertEquals("ru", entry.getString("targetLanguage"))
    assertEquals("wheel nut translates to гайка", database.glossaryText(null, "en", "ru"))
    assertTrue(database.glossaryText(null, "ru", "en").isBlank())
  }

  @Test
  fun newJobFreezesOnlyGlossaryEntriesForItsDirection() {
    val file = File(context.filesDir, "direction-source.pdf").apply { writeText("source") }
    val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    database.upsertDocument("direction-doc", "source.pdf", file.absolutePath, 1, hash)
    database.saveGlossary("en-ru", "bolt", "болт", "en", "ru")
    database.saveGlossary("ru-en", "болт", "bolt", "ru", "en")

    val job = database.addJob("direction-job", JSONObject().put("documentId", "direction-doc")
      .put("sourceLanguage", "en").put("targetLanguage", "ru").put("modelId", ModelCatalog.RECOMMENDED_ID), 1)
    database.saveGlossary("en-ru", "bolt", "винт", "en", "ru")

    assertEquals("en", job.getString("sourceLanguage"))
    assertEquals("ru", job.getString("targetLanguage"))
    assertEquals("bolt translates to болт", database.jobGlossaryText("direction-job"))
    assertEquals(4, job.getInt("pipelineVersion"))
    file.delete()
  }

  @Test
  fun glossaryOptOutFreezesEmptySnapshotWhileOmittedOptionDefaultsToTrue() {
    val file = File(context.filesDir, "glossary-toggle-source.pdf").apply { writeText("source") }
    val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    database.upsertDocument("glossary-toggle-doc", "source.pdf", file.absolutePath, 1, hash)
    database.saveGlossary("toggle-term", "wheel nut", "гайка", "en", "ru")

    database.addJob("glossary-enabled", JSONObject().put("documentId", "glossary-toggle-doc")
      .put("sourceLanguage", "en").put("targetLanguage", "ru").put("modelId", ModelCatalog.RECOMMENDED_ID)
      .put("glossaryId", "toggle-term"), 1)
    database.addJob("glossary-disabled", JSONObject().put("documentId", "glossary-toggle-doc")
      .put("sourceLanguage", "en").put("targetLanguage", "ru").put("modelId", ModelCatalog.RECOMMENDED_ID)
      .put("glossaryId", "toggle-term").put("useGlossary", false), 1)

    assertEquals("wheel nut translates to гайка", database.jobGlossaryText("glossary-enabled"))
    assertEquals("", database.jobGlossaryText("glossary-disabled"))
    file.delete()
  }

  @Test
  fun interruptedRunningJobRecoversFromDurablePageAndPreservesNativeObjectIndices() {
    val input = createJob("job-1")
    val blocks = JSONArray()
      .put(JSONObject()
        .put("id", "native-1").put("sourceText", "A source line").put("translatedText", "Строка")
        .put("objectIndex", 3).put("objectIndices", JSONArray().put(3).put(4)).put("status", "translated"))
      .put(JSONObject()
        .put("id", "ocr-0").put("sourceText", "A scanned line").put("translatedText", JSONObject.NULL)
        .put("objectIndex", JSONObject.NULL).put("objectIndices", JSONArray()).put("status", "failed"))
    database.updateJob("job-1", status = "running", stage = "layout", currentPage = 0)
    database.savePage("job-1", 0, "partial", blocks.toString(), width = 612.0, height = 792.0,
      error = OcrOutcome.NO_TEXT_WARNING, expectedBlocks = blocks.length())

    // Simulate process death after the page row committed, before the job count was updated.
    database.close()
    database = TranslatorDatabase(context)
    database.recoverInterruptedJobs(activeId = null)

    val recovered = database.jobJson("job-1")!!
    val page = database.page("job-1", 0)!!
    assertEquals("paused", recovered.getString("status"))
    assertTrue(recovered.getString("error").isNotBlank())
    assertEquals(1, recovered.getInt("completedPages"))
    assertEquals(1, database.completedPageCount("job-1"))
    assertEquals("partial", page.getString("status"))
    assertEquals(listOf(3, 4), page.getJSONArray("blocks").getJSONObject(0).getJSONArray("objectIndices").let { values ->
      (0 until values.length()).map(values::getInt)
    })
    assertEquals("ocr-0", database.pageBlockArray("job-1", 0).getJSONObject(1).getString("id"))
    assertTrue(recovered.isNull("etaSeconds"))
    assertNull(database.jobDocumentPath("missing"))
    assertFalse(database.modelInUse(ModelCatalog.RECOMMENDED_ID))
    assertTrue(input.getString("createdAt").isNotBlank())
  }

  @Test
  fun recoveryLeavesExplicitlyActiveJobRunningAndQueueGuardSeesIt() {
    createJob("active-job")
    database.updateJob("active-job", status = "running")

    database.recoverInterruptedJobs(activeId = "active-job")

    assertEquals("running", database.jobJson("active-job")!!.getString("status"))
    assertTrue(database.hasActiveJob())
    assertTrue(database.modelInUse(ModelCatalog.RECOMMENDED_ID))
  }

  private fun createJob(id: String): JSONObject {
    database.upsertDocument(id = "doc-$id", name = "sample.pdf", path = "/private/sample.pdf", pages = 2,
      sourceSha256 = "a".repeat(64))
    database.saveGlossary("glossary-1", "wheel nut", "колесная гайка", "en", "ru")
    return database.addJob(id, JSONObject()
      .put("documentId", "doc-$id").put("sourceLanguage", "en").put("targetLanguage", "ru")
      .put("modelId", ModelCatalog.RECOMMENDED_ID).put("glossaryId", "glossary-1"), pages = 2)
  }
}

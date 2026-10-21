package dev.kigya.translatepdf

import android.content.Context
import android.database.Cursor
import android.database.CursorWrapper
import android.database.sqlite.SQLiteDatabase
import android.app.Application
import android.content.Intent
import android.os.Looper
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
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
  fun modelJsonIncludesOptionalEvaluationAndErrorOnlyWhenPresent() {
    val model = ModelCatalog.models.first()
    val base = model.copy(acceptableTestCases = null).toJson("ready", model.bytes)
    assertFalse(base.has("evaluation"))
    assertFalse(base.has("error"))

    val withDetails = model.copy(acceptableTestCases = 12).toJson("error", 5, "bad checksum")
    assertEquals(12, withDetails.getJSONObject("evaluation").getInt("acceptableCases"))
    assertEquals(44, withDetails.getJSONObject("evaluation").getInt("totalCases"))
    assertEquals("bad checksum", withDetails.getString("error"))
  }

  @Test
  fun languagePairJsonPreservesPersistedDirection() {
    assertEquals("zh-Hans" to "fr", LanguagePair.fromJson(JSONObject()
      .put("sourceLanguage", "zh-Hans").put("targetLanguage", "fr")))
  }

  @Test
  fun initialSelectionStaysSavedWhenTheResourceRecommendationChanges() {
    val compact = ModelCatalog.models.first().id
    val balanced = ModelCatalog.models[1].id
    assertTrue("the active model list must only contain selectable models", ModelCatalog.models.all { it.translationEnabled })
    ModelCatalog.models.forEach {
      database.setSetting("selectedModelId", it.id)
      assertEquals(it.id, database.selectedModelId(compact))
    }
    database.setSetting("selectedModelId", compact)
    assertEquals(compact, database.selectedModelId(compact))
    assertEquals(compact, database.selectedModelId(balanced))
    database.setSetting("selectedModelId", balanced)
    assertEquals(balanced, database.selectedModelId(compact))
    database.setSetting("selectedModelId", "hy-mt2-iq2-m")
    assertEquals(compact, database.selectedModelId(compact))
    assertTrue(runCatching { database.selectedModelId("missing-model") }.isFailure)
    // The disabled legacy candidate is not part of ModelCatalog.models.
    assertTrue(runCatching { database.selectedModelId("hy-mt2-iq2-m") }.isFailure)
  }

  @Test
  fun selectedOnlineEnginePersistsWithoutReplacingLegacyLocalSelection() {
    val local = ModelCatalog.models[1].id
    database.setSetting("selectedModelId", local)
    database.setSelectedEngine("local", local)
    database.setSelectedEngine("online", "mymemory")

    assertEquals("online", database.selectedEngine(ModelCatalog.RECOMMENDED_ID).getString("kind"))
    assertEquals("mymemory", database.selectedEngine(ModelCatalog.RECOMMENDED_ID).getString("id"))
    assertEquals(local, database.setting("selectedModelId", ""))

    database.setSelectedEngine("local", ModelCatalog.models.first().id)
    assertEquals("local", database.selectedEngine(ModelCatalog.RECOMMENDED_ID).getString("kind"))
    assertEquals(ModelCatalog.models.first().id, database.selectedEngine(ModelCatalog.RECOMMENDED_ID).getString("id"))
    assertEquals(ModelCatalog.models.first().id, database.setting("selectedModelId", ""))
    assertTrue(runCatching { database.setSelectedEngine("online", "unknown") }.isFailure)
  }

  @Test
  fun libraryPreferencesUseSafeDefaultsAndNormalizeMalformedStoredValues() {
    assertEquals(0, database.appRating())
    assertEquals("recent", database.librarySort())

    database.setSetting("appRating", "6")
    database.setSetting("librarySort", "unknown")
    assertEquals(0, database.appRating())
    assertEquals("recent", database.librarySort())
    database.setSetting("appRating", "5")
    database.setSetting("librarySort", "color")
    assertEquals(5, database.appRating())
    assertEquals("color", database.librarySort())
  }

  @Test
  fun jobLabelsAreWhitelistedTerminalOnlyAndDoNotRewriteJobTimestamps() {
    createJob("label-completed")
    database.updateJob("label-completed", status = "completed")
    val before = database.jobJson("label-completed")!!
    val updatedAt = before.getLong("updatedAt")

    database.setJobColor("label-completed", "purple")
    val labeled = database.jobJson("label-completed")!!
    assertEquals("purple", labeled.getString("labelColor"))
    assertEquals(updatedAt, labeled.getLong("updatedAt"))

    database.setJobColor("label-completed", null)
    assertTrue(database.jobJson("label-completed")!!.isNull("labelColor"))
    assertTrue(runCatching { database.setJobColor("label-completed", "black") }.isFailure)
    assertTrue(runCatching { database.setJobColor("missing-label-job", "red") }.isFailure)

    createJob("label-partial")
    database.updateJob("label-partial", status = "partial")
    database.setJobColor("label-partial", "red")
    assertEquals("red", database.jobJson("label-partial")!!.getString("labelColor"))

    createJob("label-running")
    database.updateJob("label-running", status = "running")
    assertTrue(runCatching { database.setJobColor("label-running", "blue") }.isFailure)
  }

  @Test
  fun schemaSixUpgradePreservesDocumentJobSettingsAndCheckpointRows() {
    database.close()
    context.deleteDatabase("translator-private.db")
    val path = context.getDatabasePath("translator-private.db")
    val legacy = SQLiteDatabase.openOrCreateDatabase(path, null)
    legacy.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    legacy.execSQL("CREATE TABLE documents (id TEXT PRIMARY KEY, name TEXT NOT NULL, format TEXT NOT NULL, warnings_json TEXT NOT NULL)")
    legacy.execSQL("CREATE TABLE jobs (id TEXT PRIMARY KEY, document_id TEXT NOT NULL, model_id TEXT NOT NULL, status TEXT NOT NULL, updated_at INTEGER NOT NULL)")
    legacy.execSQL("CREATE TABLE pages (job_id TEXT NOT NULL, page_index INTEGER NOT NULL, blocks_json TEXT NOT NULL, PRIMARY KEY(job_id,page_index))")
    legacy.execSQL("INSERT INTO settings(key,value) VALUES('theme','dark')")
    legacy.execSQL("INSERT INTO documents(id,name,format,warnings_json) VALUES('upgrade-doc','manual.docx','docx','warning')")
    legacy.execSQL("INSERT INTO jobs(id,document_id,model_id,status,updated_at) VALUES('upgrade-job','upgrade-doc','hy-mt2-q4','partial',12345)")
    legacy.execSQL("INSERT INTO pages(job_id,page_index,blocks_json) VALUES('upgrade-job',0,'checkpoint')")
    legacy.version = 6
    legacy.close()

    database = TranslatorDatabase(context)
    val upgraded = database.writableDatabase
    upgraded.rawQuery("SELECT value FROM settings WHERE key='theme'", null).use {
      assertTrue(it.moveToFirst()); assertEquals("dark", it.getString(0))
    }
    upgraded.rawQuery("SELECT name,format,warnings_json FROM documents WHERE id='upgrade-doc'", null).use {
      assertTrue(it.moveToFirst()); assertEquals("manual.docx", it.getString(0)); assertEquals("docx", it.getString(1)); assertEquals("warning", it.getString(2))
    }
    upgraded.rawQuery("SELECT model_id,status,updated_at,label_color FROM jobs WHERE id='upgrade-job'", null).use {
      assertTrue(it.moveToFirst()); assertEquals("hy-mt2-q4", it.getString(0)); assertEquals("partial", it.getString(1)); assertEquals(12345L, it.getLong(2)); assertTrue(it.isNull(3))
    }
    upgraded.rawQuery("SELECT blocks_json FROM pages WHERE job_id='upgrade-job' AND page_index=0", null).use {
      assertTrue(it.moveToFirst()); assertEquals("checkpoint", it.getString(0))
    }
    assertEquals(7, upgraded.version)
  }

  @Test
  fun bootstrapKeepsReadyWhenInstalledFileHasExpectedLengthWithoutRehashingIt() {
    val model = ModelCatalog.models.first()
    val installed = ModelDownloader(context, database).fileFor(model.id)
    val partial = installed.resolveSibling("${model.id}.part")
    installed.parentFile?.mkdirs()
    installed.writeBytes(byteArrayOf(1, 2, 3, 4))
    partial.delete()
    database.modelStatus(model.id, "ready", model.bytes)
    val raw = database.modelsJson()

    val effective = effectiveModelSnapshot(raw, listOf(model.copy(bytes = 4)), ModelDownloader(context, database))

    assertEquals(Triple("ready", model.bytes, null), database.modelState(model.id))
    assertEquals("ready", modelRow(effective, model.id).getString("status"))
  }

  @Test
  fun bootstrapRepairsMissingReadyModelAndPreservesSelectedModel() {
    val model = ModelCatalog.models[1]
    val downloader = ModelDownloader(context, database)
    downloader.fileFor(model.id).delete()
    downloader.fileFor(model.id).resolveSibling("${model.id}.part").delete()
    database.modelStatus(model.id, "ready", model.bytes, "stale checksum error")
    database.setSetting("selectedModelId", model.id)
    val captured = modelRow(database.modelsJson(), model.id)

    val effective = effectiveModelSnapshot(JSONArray().put(captured), listOf(model.copy(bytes = 4)), downloader)

    val row = modelRow(effective, model.id)
    assertEquals("not_downloaded", row.getString("status"))
    assertEquals(0L, row.getLong("downloadedBytes"))
    assertFalse(row.has("error"))
    assertEquals(Triple("ready", model.bytes, "stale checksum error"), database.modelState(model.id))
    assertEquals("a missing selected model must remain selected for the user", model.id, database.selectedModelId(ModelCatalog.models.first().id))
  }

  @Test
  fun bootstrapRepairsTruncatedReadyModelWithoutPartialDownload() {
    val model = ModelCatalog.models[2]
    val downloader = ModelDownloader(context, database)
    val installed = downloader.fileFor(model.id)
    installed.parentFile?.mkdirs()
    installed.writeBytes(byteArrayOf(1, 2, 3))
    installed.resolveSibling("${model.id}.part").delete()
    database.modelStatus(model.id, "ready", model.bytes, "old error")
    val captured = modelRow(database.modelsJson(), model.id)

    val effective = effectiveModelSnapshot(JSONArray().put(captured), listOf(model.copy(bytes = 4)), downloader)

    val row = modelRow(effective, model.id)
    assertEquals("not_downloaded", row.getString("status"))
    assertEquals(0L, row.getLong("downloadedBytes"))
    assertFalse(row.has("error"))
    assertEquals("storage includes the truncated installed file", 3L, row.getLong("storedBytes"))
    assertEquals(Triple("ready", model.bytes, "old error"), database.modelState(model.id))
  }

  @Test
  fun bootstrapRepairsTruncatedReadyModelToPausedWithActualPartialBytes() {
    val model = ModelCatalog.models.first()
    val downloader = ModelDownloader(context, database)
    val installed = downloader.fileFor(model.id)
    val partial = installed.resolveSibling("${model.id}.part")
    installed.parentFile?.mkdirs()
    installed.writeBytes(byteArrayOf(1, 2, 3))
    partial.writeBytes(byteArrayOf(8, 7, 6, 5, 4))
    database.modelStatus(model.id, "ready", model.bytes, "stale ready error")
    val captured = modelRow(database.modelsJson(), model.id)

    val effective = effectiveModelSnapshot(JSONArray().put(captured), listOf(model.copy(bytes = 4)), downloader)

    val row = modelRow(effective, model.id)
    assertEquals("paused", row.getString("status"))
    assertEquals(5L, row.getLong("downloadedBytes"))
    assertEquals(5L, row.getLong("partialBytes"))
    assertEquals(8L, row.getLong("storedBytes"))
    assertFalse(row.has("error"))
    assertEquals(Triple("ready", model.bytes, "stale ready error"), database.modelState(model.id))
  }

  @Test
  fun bootstrapDoesNotRewriteActiveDownloadOrVerificationStates() {
    val downloading = ModelCatalog.models.first()
    val verifying = ModelCatalog.models[1]
    val downloader = ModelDownloader(context, database)
    database.modelStatus(downloading.id, "downloading", 17, "transient")
    database.modelStatus(verifying.id, "verifying", 29, "transient")
    val raw = database.modelsJson()
    val downloadToken = downloader.reserve(downloading.id)
    val verificationToken = downloader.reserve(verifying.id)

    try {
      val effective = effectiveModelSnapshot(raw, listOf(downloading, verifying), downloader)

      assertEquals("downloading", modelRow(effective, downloading.id).getString("status"))
      assertEquals("verifying", modelRow(effective, verifying.id).getString("status"))
      assertEquals(Triple("downloading", 17L, "transient"), database.modelState(downloading.id))
      assertEquals(Triple("verifying", 29L, "transient"), database.modelState(verifying.id))
    } finally {
      downloader.releaseReservation(downloading.id, downloadToken)
      downloader.releaseReservation(verifying.id, verificationToken)
    }
  }

  @Test
  fun processRestartSnapshotsOrphanedDownloadsAsResumableWithoutDatabaseWrites() {
    val model = ModelCatalog.models.first()
    val downloader = ModelDownloader(context, database)
    val partial = downloader.fileFor(model.id).resolveSibling("${model.id}.part")
    partial.parentFile?.mkdirs()
    partial.writeBytes(byteArrayOf(1, 2, 3, 4))
    database.modelStatus(model.id, "downloading", 99, "interrupted")
    val captured = modelRow(database.modelsJson(), model.id)

    val afterProcessRestart = effectiveModelSnapshot(JSONArray().put(captured), listOf(model), downloader)
    val recovered = modelRow(afterProcessRestart, model.id)
    assertEquals("paused", recovered.getString("status"))
    assertEquals(4L, recovered.getLong("downloadedBytes"))
    assertEquals(4L, recovered.getLong("partialBytes"))
    assertFalse(recovered.has("error"))
    assertEquals(Triple("downloading", 99L, "interrupted"), database.modelState(model.id))

    val token = downloader.reserve(model.id)
    try {
      database.modelStatus(model.id, "downloading", 4)
      val queuedStart = effectiveModelSnapshot(database.modelsJson(), listOf(model), downloader)
      assertEquals("downloading", modelRow(queuedStart, model.id).getString("status"))
    } finally {
      downloader.releaseReservation(model.id, token)
    }

    val afterTransferFinishes = effectiveModelSnapshot(database.modelsJson(), listOf(model), downloader)
    assertEquals("paused", modelRow(afterTransferFinishes, model.id).getString("status"))
    assertEquals(Triple("downloading", 4L, null), database.modelState(model.id))
  }

  @Test
  fun orphanedDownloadWithoutPartialFileBecomesNotDownloadedInTheSnapshotOnly() {
    val model = ModelCatalog.models[1]
    val downloader = ModelDownloader(context, database)
    val partial = downloader.fileFor(model.id).resolveSibling("${model.id}.part")
    partial.delete()
    database.modelStatus(model.id, "verifying", 123, "interrupted verification")
    val captured = modelRow(database.modelsJson(), model.id)

    val effective = effectiveModelSnapshot(JSONArray().put(captured), listOf(model), downloader)

    val row = modelRow(effective, model.id)
    assertEquals("not_downloaded", row.getString("status"))
    assertEquals(0L, row.getLong("downloadedBytes"))
    assertEquals(0L, row.getLong("partialBytes"))
    assertFalse(row.has("error"))
    assertEquals(Triple("verifying", 123L, "interrupted verification"), database.modelState(model.id))
  }

  @Test
  fun effectiveSnapshotCanReadPersistedLegacyModelOutsideActiveCatalog() {
    val legacy = ModelCatalog.get("hy-mt2-iq3-xs")
    database.modelStatus(legacy.id, "ready", legacy.bytes, "legacy state")

    val snapshot = effectiveModelSnapshot(
      JSONArray().put(legacy.toJson("ready", legacy.bytes, "legacy state")),
      ModelCatalog.models,
      ModelDownloader(context, database),
    )

    val row = modelRow(snapshot, legacy.id)
    assertEquals("not_downloaded", row.getString("status"))
    assertEquals(0L, row.getLong("downloadedBytes"))
    assertEquals(Triple("ready", legacy.bytes, "legacy state"), database.modelState(legacy.id))
  }

  @Test
  fun effectiveSnapshotAcceptsAnEmptyPersistedModelList() {
    assertEquals(0, effectiveModelSnapshot(JSONArray(), ModelCatalog.models, ModelDownloader(context, database)).length())
  }

  @Test
  fun capturedReadySnapshotCannotOverwriteConcurrentDownloadDeleteOrNewReadyRow() {
    val models = ModelCatalog.models
    val downloader = ModelDownloader(context, database)
    val capturedRows = models.associate { model ->
      database.modelStatus(model.id, "ready", model.bytes, "captured old row")
      model.id to modelRow(database.modelsJson(), model.id)
    }

    val downloading = models[0]
    val partial = downloader.fileFor(downloading.id).resolveSibling("${downloading.id}.part")
    partial.parentFile?.mkdirs()
    partial.writeBytes(byteArrayOf(4, 5))
    database.modelStatus(downloading.id, "downloading", 2, null)
    val downloadSnapshot = effectiveModelSnapshot(
      JSONArray().put(capturedRows.getValue(downloading.id)), listOf(downloading.copy(bytes = 4)), downloader,
    )
    assertEquals("paused", modelRow(downloadSnapshot, downloading.id).getString("status"))
    assertEquals(Triple("downloading", 2L, null), database.modelState(downloading.id))
    val freshDownload = effectiveModelSnapshot(database.modelsJson(), listOf(downloading.copy(bytes = 4)), downloader)
    assertEquals("paused", modelRow(freshDownload, downloading.id).getString("status"))

    val deleted = models[1]
    database.deleteModel(deleted.id)
    val deleteSnapshot = effectiveModelSnapshot(
      JSONArray().put(capturedRows.getValue(deleted.id)), listOf(deleted.copy(bytes = 4)), downloader,
    )
    assertEquals("not_downloaded", modelRow(deleteSnapshot, deleted.id).getString("status"))
    assertNull(database.modelState(deleted.id))
    val freshDelete = effectiveModelSnapshot(database.modelsJson(), listOf(deleted.copy(bytes = 4)), downloader)
    assertEquals("not_downloaded", modelRow(freshDelete, deleted.id).getString("status"))

    val newlyReady = models[2]
    val installed = downloader.fileFor(newlyReady.id)
    installed.parentFile?.mkdirs()
    installed.writeBytes(byteArrayOf(1, 2, 3, 4))
    database.modelStatus(newlyReady.id, "ready", newlyReady.bytes, null)
    val readySnapshot = effectiveModelSnapshot(
      JSONArray().put(capturedRows.getValue(newlyReady.id)), listOf(newlyReady.copy(bytes = 4)), downloader,
    )
    assertEquals("ready", modelRow(readySnapshot, newlyReady.id).getString("status"))
    assertEquals(Triple("ready", newlyReady.bytes, null), database.modelState(newlyReady.id))
    val freshReady = effectiveModelSnapshot(database.modelsJson(), listOf(newlyReady.copy(bytes = 4)), downloader)
    assertEquals("ready", modelRow(freshReady, newlyReady.id).getString("status"))
  }

  private fun modelRow(models: JSONArray, modelId: String): JSONObject =
    (0 until models.length()).map { models.getJSONObject(it) }.first { it.getString("id") == modelId }

  @Test
  fun selectedModelReadsAConcurrentValidUpdateInsideItsTransaction() {
    val initial = ModelCatalog.models.first().id
    val changed = ModelCatalog.models[1].id
    database.setSetting("selectedModelId", "stale-value")
    database.close()
    var firstSelectionRead = true
    val racingDatabase = TranslatorDatabase(context, queryForRead = { db, sql, args ->
      val cursor = db.rawQuery(sql, args)
      if (sql == "SELECT value FROM settings WHERE key=?" && firstSelectionRead) {
        firstSelectionRead = false
        object : CursorWrapper(cursor) {
          override fun close() {
            super.close()
            // A different writer changes the preference between the first
            // read and selectedModelId's transaction recheck.
            db.execSQL("UPDATE settings SET value=? WHERE key=?", arrayOf(changed, "selectedModelId"))
          }
        }
      } else cursor
    })

    try {
      assertEquals(changed, racingDatabase.selectedModelId(initial))
      assertEquals(changed, racingDatabase.setting("selectedModelId", ""))
    } finally {
      racingDatabase.close()
    }
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
  fun serverIgnoringResumeRangeRestartsPartialFromZeroAndReportsIncompleteDownload() {
    val model = ModelCatalog.models.first()
    val downloader = ModelDownloader(context, database)
    val partial = downloader.fileFor(model.id).resolveSibling("${model.id}.part")
    val installed = downloader.fileFor(model.id)
    partial.parentFile?.mkdirs()
    partial.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
    installed.delete()
    val server = ServerSocket(0)
    val rangeHeader = java.util.concurrent.atomic.AtomicReference<String?>()
    val serverThread = Thread {
      try {
        server.accept().use { socket ->
          socket.soTimeout = 5_000
          val request = socket.getInputStream().bufferedReader()
          while (true) {
            val line = request.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) rangeHeader.set(line.substringAfter(':').trim())
          }
          socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\n".toByteArray())
            write(byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2))
            flush()
          }
        }
      } finally { server.close() }
    }.apply { isDaemon = true; start() }
    val finished = CountDownLatch(1)
    val outcome = java.util.concurrent.atomic.AtomicReference<Result<File>>()

    try {
      downloader.start(model.id, onProgress = {}, onFinished = {
        outcome.set(it)
        finished.countDown()
      }, downloadUrl = URL("http://127.0.0.1:${server.localPort}/model"))

      assertTrue("the downloader should finish the bounded fake response", finished.await(5, TimeUnit.SECONDS))
      assertEquals("bytes=5-", rangeHeader.get())
      assertTrue("a short non-range response cannot be accepted as an installed model", outcome.get().isFailure)
      assertEquals("Downloaded model has an incomplete size.", outcome.get().exceptionOrNull()?.message)
      assertTrue("a 200 response must replace, not append to, the old partial",
        byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2).contentEquals(partial.readBytes()))
      assertEquals("error", database.modelState(model.id)?.first)
      assertEquals(8L, database.modelState(model.id)?.second)
      assertFalse(installed.exists())
    } finally {
      downloader.cancel(model.id)
      partial.delete()
      installed.delete()
      server.close()
      serverThread.join(1_000)
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

  @Test
  fun translationServiceFailsClosedWhenPinnedModelFileIsMissing() {
    val source = File(context.cacheDir, "missing-model-service-source.pdf").apply { writeText("%PDF-1.7") }
    val sourceHash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    database.upsertDocument("doc-missing-model-service-job", "sample.pdf", source.absolutePath, 2, sourceHash)
    database.addJob("missing-model-service-job", JSONObject()
      .put("documentId", "doc-missing-model-service-job")
      .put("sourceLanguage", "en").put("targetLanguage", "ru")
      .put("modelId", ModelCatalog.RECOMMENDED_ID), 2)
    val controller = Robolectric.buildService(PdfTranslationService::class.java).create()
    val service = controller.get()
    val owner = QueuedExecutor()
    val stoppedIds = mutableListOf<Int>()
    service.jobExecutorFactory = { owner }
    service.stopSelfForCompletedWorker = { stoppedIds.add(it); Unit }
    try {
      val intent = Intent(context, PdfTranslationService::class.java)
        .setAction(PdfTranslationService.ACTION_START)
        .putExtra(PdfTranslationService.EXTRA_JOB_ID, "missing-model-service-job")
      service.onStartCommand(intent, 0, 41)

      assertEquals("translation is queued on the service-owned worker", 1, owner.queuedCount)
      assertEquals("queued work does not prematurely mutate the job", "queued",
        database.jobJson("missing-model-service-job")!!.getString("status"))
      owner.runNext()
      Shadows.shadowOf(Looper.getMainLooper()).idle()

      assertEquals("failed", database.jobJson("missing-model-service-job")!!.getString("status"))
      assertEquals("The selected model is missing or incomplete.",
        database.jobJson("missing-model-service-job")!!.getString("error"))
      assertEquals(listOf(41), stoppedIds)
      assertNull(getExecutor(service))
    } finally {
      controller.destroy()
      owner.shutdownNow()
      source.delete()
    }
    assertFalse(PdfTranslationService.isActive)
  }

  @Test
  fun modelDownloadServiceReconcilesAlreadyVerifiedFixtureWithoutNetwork() {
    val bytes = byteArrayOf(3, 1, 4, 1, 5, 9)
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val definition = ModelDefinition(
      id = "service-ready-fixture", label = "Ready test fixture", repo = "local/test",
      fileName = "ready.gguf", revision = "test", bytes = bytes.size.toLong(), sha256 = hash,
      qualityBenchmarked = false, translationEnabled = false,
    )
    val downloader = ModelDownloader(context, database, definitionLookup = { definition })
    downloader.fileFor(definition.id).apply { parentFile?.mkdirs(); writeBytes(bytes) }
    val token = downloader.reserve(definition.id)
    val owner = QueuedExecutor()
    val stoppedIds = mutableListOf<Int>()
    val controller = Robolectric.buildService(PdfTranslationService::class.java).create()
    val service = controller.get()
    service.modelDefinitionLookup = { definition }
    service.modelDownloaderFactory = { downloader }
    service.modelDownloadExecutorFactory = { owner }
    service.stopSelfForCompletedWorker = { stoppedIds.add(it); Unit }
    try {
      val intent = modelIntent(definition.id, token)
      service.onStartCommand(intent, 0, 52)
      assertEquals("start claims the reservation and queues exactly one service worker", 1, owner.queuedCount)

      owner.runNext()
      Shadows.shadowOf(Looper.getMainLooper()).idle()

      assertEquals(Triple("ready", definition.bytes, null), database.modelState(definition.id))
      assertTrue(downloader.isInstalled(definition.id))
      assertEquals(listOf(52), stoppedIds)
      assertNull(getExecutor(service))
    } finally {
      controller.destroy()
      owner.shutdownNow()
      downloader.cancel(definition.id)
      downloader.fileFor(definition.id).parentFile?.deleteRecursively()
    }
    assertFalse(PdfTranslationService.isActive)
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
    fun runNext() { tasks.removeAt(0).run() }
    override fun shutdown() { shutdown=true }
    override fun shutdownNow(): MutableList<Runnable> { shutdown=true;return tasks.toMutableList().also { tasks.clear() } }
    override fun isShutdown(): Boolean = shutdown
    override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
  }

  @After
  fun tearDown() {
    ModelCatalog.models.forEach { model ->
      val installed = File(context.filesDir, "models/${model.id}.gguf")
      installed.delete()
      File(context.filesDir, "models/${model.id}.part").delete()
    }
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
    assertTrue(runCatching { database.validateJobSnapshot("frozen-job", model.copy(id = ModelCatalog.RECOMMENDED_ID)) }.isFailure)
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

    database.setSetting("sourceLanguage", "unsupported")
    assertEquals("en" to "ru", database.selectedLanguagePair())
    database.setSetting("sourceLanguage", "fr")
    database.setSetting("targetLanguage", "unsupported")
    assertEquals("en" to "ru", database.selectedLanguagePair())
    database.setSetting("sourceLanguage", "en")
    database.setSetting("targetLanguage", "en")
    assertEquals("en" to "ru", database.selectedLanguagePair())

    database.setSetting("sourceLanguage", "unsupported")
    assertEquals("en" to "ru", database.selectedLanguagePair())
    database.setSetting("sourceLanguage", "en")
    database.setSetting("targetLanguage", "en")
    assertEquals("en" to "ru", database.selectedLanguagePair())
  }

  @Test
  fun existingGlossaryRowsMigrateToEnglishRussian() {
    database.close()
    val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("translator-private.db"), null)
    legacy.execSQL("CREATE TABLE glossary (id TEXT PRIMARY KEY, source TEXT NOT NULL, target TEXT NOT NULL, created_at INTEGER NOT NULL)")
    legacy.execSQL("CREATE TABLE documents (id TEXT PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL, pages INTEGER NOT NULL, source_sha256 TEXT NOT NULL, imported_at INTEGER NOT NULL)")
    legacy.execSQL("CREATE TABLE models (id TEXT PRIMARY KEY, status TEXT NOT NULL, downloaded_bytes INTEGER NOT NULL DEFAULT 0, error TEXT)")
    legacy.execSQL("CREATE TABLE jobs (id TEXT PRIMARY KEY, document_id TEXT NOT NULL, model_id TEXT NOT NULL, model_revision TEXT NOT NULL, model_profile TEXT NOT NULL, source_sha256 TEXT NOT NULL, glossary_snapshot TEXT NOT NULL, pipeline_version INTEGER NOT NULL, source_lang TEXT NOT NULL, target_lang TEXT NOT NULL, status TEXT NOT NULL, stage TEXT NOT NULL, completed_pages INTEGER NOT NULL DEFAULT 0, total_pages INTEGER NOT NULL, current_page INTEGER, glossary_id TEXT, error TEXT, observed_block_ms INTEGER NOT NULL DEFAULT 0, observed_block_count INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
    legacy.execSQL("CREATE TABLE pages (job_id TEXT NOT NULL, page_index INTEGER NOT NULL, status TEXT NOT NULL, blocks_json TEXT NOT NULL, width REAL NOT NULL DEFAULT 0, height REAL NOT NULL DEFAULT 0, expected_blocks INTEGER, error TEXT, updated_at INTEGER NOT NULL, PRIMARY KEY(job_id,page_index))")
    legacy.execSQL("CREATE TABLE exports (id TEXT PRIMARY KEY, path TEXT NOT NULL, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
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
  fun databaseCollectionsRoundTripAndDeleteTheirOwnedRows() {
    val model = ModelCatalog.models.first()
    assertNull(database.documentPath("missing-document"))
    assertEquals(0, database.documentsJson().length())
    assertNull(database.modelState(model.id))

    database.upsertDocument("doc-a", "first.pdf", "/private/first.pdf", 3, "a".repeat(64))
    database.upsertDocument("doc-b", "second.pdf", "/private/second.pdf", 1, "b".repeat(64))
    assertEquals("/private/first.pdf", database.documentPath("doc-a"))
    val documents = database.documentsJson()
    assertEquals(2, documents.length())
    assertTrue((0 until documents.length()).map { documents.getJSONObject(it).getString("id") }.containsAll(listOf("doc-a", "doc-b")))

    database.modelStatus(model.id, "ready", model.bytes)
    assertEquals(Triple("ready", model.bytes, null), database.modelState(model.id))
    val modelSnapshot = (0 until database.modelsJson().length()).map { database.modelsJson().getJSONObject(it) }
      .first { it.getString("id") == model.id }
    assertEquals("ready", modelSnapshot.getString("status"))
    assertEquals(model.bytes, modelSnapshot.getLong("downloadedBytes"))
    database.modelStatus(model.id, "error", 7, "checksum mismatch")
    assertEquals(Triple("error", 7L, "checksum mismatch"), database.modelState(model.id))
    database.upsertModel(model.id, "ready", model.bytes)
    assertEquals(Triple("ready", model.bytes, null), database.modelState(model.id))

    database.saveExport("export-a", "/private/translated.pdf", "translated.pdf")
    assertEquals("/private/translated.pdf" to "translated.pdf", database.export("export-a"))
    assertNull(database.export("missing-export"))
    database.deleteExport("export-a")
    assertNull(database.export("export-a"))

    database.deleteModel(model.id)
    assertNull(database.modelState(model.id))
    database.deleteDocument("doc-a")
    assertNull(database.documentPath("doc-a"))
    assertEquals(1, database.documentsJson().length())
  }

  @Test
  fun queryBodyFailuresCloseCursorsAndPreserveCloseFailuresAsSuppressed() {
    val cursorPlans = mutableListOf<PlannedCursor>()
    val failingDatabase = TranslatorDatabase(context, queryForRead = { db, sql, args ->
      PlannedCursor(db.rawQuery(sql, args), failRead = true, failClose = true).also(cursorPlans::add)
    })
    val model = ModelCatalog.models.first()
    val jobInput = JSONObject().put("documentId", "missing-document")
      .put("sourceLanguage", "en").put("targetLanguage", "ru").put("modelId", model.id)
    val operations: List<() -> Any?> = listOf(
      { failingDatabase.setting("key", "fallback") },
      { failingDatabase.selectedLanguagePair() },
      { failingDatabase.selectedModelId(model.id) },
      { failingDatabase.documentPath("id") },
      { failingDatabase.documentsJson() },
      { failingDatabase.modelState(model.id) },
      { failingDatabase.modelsJson() },
      { failingDatabase.addJob("job", jobInput, 1) },
      { failingDatabase.jobJson("job") },
      { failingDatabase.jobsJson() },
      { failingDatabase.jobDocumentPath("job") },
      { failingDatabase.requireCurrentPipeline("job") },
      { failingDatabase.validateJobSnapshot("job", model) },
      { failingDatabase.hasActiveJob() },
      { failingDatabase.hasActiveJob(exceptId = "job") },
      { failingDatabase.modelInUse(model.id) },
      { failingDatabase.recoverInterruptedJobs(null) },
      { failingDatabase.jobGlossaryId("job") },
      { failingDatabase.jobGlossaryText("job") },
      { failingDatabase.pageRows("job") },
      { failingDatabase.completedPageCount("job") },
      { failingDatabase.page("job", 0) },
      { failingDatabase.documentInfo("document") },
      { failingDatabase.pageBlockArray("job", 0) },
      { failingDatabase.glossaryJson() },
      { failingDatabase.glossaryText(null, "en", "ru") },
      { failingDatabase.export("export") },
    )

    try {
      for (operation in operations) {
        val error = runCatching(operation).exceptionOrNull()
        assertEquals("the cursor read error must remain primary", "cursor read failed", error?.message)
        assertEquals("the cursor close error must be retained", "cursor close failed", error?.suppressed?.singleOrNull()?.message)
      }
      assertEquals(operations.size, cursorPlans.size)
      assertTrue("every cursor must close even when its read callback fails", cursorPlans.all { it.closed })
    } finally {
      failingDatabase.close()
    }
  }

  @Test
  fun successfulCursorReadsStillPropagateCloseFailuresInNestedJobQueries() {
    createJob("close-failure-job")
    database.close()
    val closeFailureQueries = listOf(
      "SELECT blocks_json FROM pages WHERE job_id=?",
      "SELECT expected_blocks FROM pages WHERE job_id=?",
      "SELECT observed_block_ms,observed_block_count,status,stage FROM jobs WHERE id=?",
    )

    for (failingSql in closeFailureQueries) {
      val closeFailingDatabase = TranslatorDatabase(context, queryForRead = { db, sql, args ->
        PlannedCursor(db.rawQuery(sql, args), failClose = sql == failingSql)
      })
      try {
        val failure = runCatching { closeFailingDatabase.jobJson("close-failure-job") }.exceptionOrNull()
        assertEquals("cursor close failed", failure?.message)
      } finally {
        closeFailingDatabase.close()
      }

      val readFailingDatabase = TranslatorDatabase(context, queryForRead = { db, sql, args ->
        PlannedCursor(db.rawQuery(sql, args), failRead = sql == failingSql)
      })
      try {
        val failure = runCatching { readFailingDatabase.jobJson("close-failure-job") }.exceptionOrNull()
        assertEquals("cursor read failed", failure?.message)
        assertTrue("a body error remains primary when cursor.close succeeds", failure?.suppressed.isNullOrEmpty())
      } finally {
        readFailingDatabase.close()
      }
    }
  }

  @Test
  fun addJobReportsWhenTheInsertedRowIsRemovedBeforeItsSnapshotRead() {
    val source = File(context.filesDir, "deleted-before-snapshot.pdf").apply { writeText("source") }
    val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    database.upsertDocument("deleted-before-snapshot-doc", "source.pdf", source.absolutePath, 1, hash)
    database.close()
    val racingDatabase = TranslatorDatabase(context, queryForRead = { db, sql, args ->
      if (sql.startsWith("SELECT j.id,j.document_id")) db.delete("jobs", "id=?", arrayOf("racing-job"))
      db.rawQuery(sql, args)
    })
    val input = JSONObject().put("documentId", "deleted-before-snapshot-doc")
      .put("sourceLanguage", "en").put("targetLanguage", "ru")
      .put("modelId", ModelCatalog.RECOMMENDED_ID)

    try {
      val failure = runCatching { racingDatabase.addJob("racing-job", input, 1) }.exceptionOrNull()
      assertEquals("New job could not be read.", failure?.message)
      assertNull(racingDatabase.jobJson("racing-job"))
    } finally {
      racingDatabase.close()
      source.delete()
    }
  }

  @Test
  fun emptyCursorsReturnFallbackValuesIncludingCountAggregateFallback() {
    val cursors = mutableListOf<PlannedCursor>()
    val emptyDatabase = TranslatorDatabase(context, queryForRead = { db, sql, args ->
      PlannedCursor(db.rawQuery(sql, args), empty = true).also(cursors::add)
    })
    val model = ModelCatalog.models.first()
    try {
      assertEquals("fallback", emptyDatabase.setting("missing", "fallback"))
      assertEquals("en" to "ru", emptyDatabase.selectedLanguagePair())
      assertEquals(model.id, emptyDatabase.selectedModelId(model.id))
      assertNull(emptyDatabase.documentPath("missing"))
      assertEquals(0, emptyDatabase.documentsJson().length())
      assertNull(emptyDatabase.modelState(model.id))
      assertEquals("not_downloaded", emptyDatabase.modelsJson().getJSONObject(0).getString("status"))
      assertTrue(runCatching { emptyDatabase.addJob("missing", JSONObject().put("documentId", "missing"), 1) }.isFailure)
      assertNull(emptyDatabase.jobJson("missing"))
      assertEquals(0, emptyDatabase.jobsJson().length())
      assertNull(emptyDatabase.jobDocumentPath("missing"))
      assertTrue(runCatching { emptyDatabase.requireCurrentPipeline("missing") }.isFailure)
      assertTrue(runCatching { emptyDatabase.validateJobSnapshot("missing", model) }.isFailure)
      assertFalse(emptyDatabase.hasActiveJob())
      assertFalse(emptyDatabase.hasActiveJob(exceptId = "missing"))
      assertFalse(emptyDatabase.modelInUse(model.id))
      emptyDatabase.recoverInterruptedJobs(null)
      assertNull(emptyDatabase.jobGlossaryId("missing"))
      assertEquals("", emptyDatabase.jobGlossaryText("missing"))
      assertEquals(0, emptyDatabase.pageRows("missing").length())
      assertNull(emptyDatabase.page("missing", 0))
      assertNull(emptyDatabase.documentInfo("missing"))
      assertEquals(0, emptyDatabase.pageBlockArray("missing", 0).length())
      assertEquals(0, emptyDatabase.glossaryJson().length())
      assertEquals("", emptyDatabase.glossaryText(null, "en", "ru"))
      assertNull(emptyDatabase.export("missing"))
      assertTrue("all empty-result cursors should still be closed", cursors.all { it.closed })
    } finally {
      emptyDatabase.close()
    }
  }

  @Test
  fun jobSnapshotsTrackPagesBlocksTimingAndDeletion() {
    assertEquals(0, database.jobsJson().length())
    assertEquals("SQLite COUNT(*) returns zero in its single aggregate row when no pages match.",
      0, database.completedPageCount("snapshot-job"))
    createJob("snapshot-job")
    assertNull(database.jobJson("missing-job"))
    assertNull(database.jobDocumentPath("missing-job"))
    assertNull(database.documentInfo("missing-document"))
    assertEquals("/private/sample.pdf", database.jobDocumentPath("snapshot-job"))
    assertEquals("/private/sample.pdf" to 2, database.documentInfo("doc-snapshot-job"))
    assertEquals("glossary-1", database.jobGlossaryId("snapshot-job"))
    assertNull(database.jobGlossaryId("missing-job"))
    assertEquals("wheel nut translates to колесная гайка", database.jobGlossaryText("snapshot-job"))
    assertEquals("", database.jobGlossaryText("missing-job"))
    assertEquals(1, database.jobsJson().length())
    assertTrue(database.hasActiveJob())
    assertFalse(database.hasActiveJob(exceptId = "snapshot-job"))
    assertTrue(database.modelInUse(ModelCatalog.RECOMMENDED_ID))

    val blocks = JSONArray()
      .put(JSONObject().put("status", "translated"))
      .put(JSONObject().put("status", "partial"))
      .put(JSONObject().put("status", "failed"))
      .put(JSONObject().put("status", "skipped"))
      .put(JSONObject().put("status", "translating"))
      .put(JSONObject.NULL)
    database.updateJob("snapshot-job", status = "running", stage = "translating", currentPage = 0, error = "temporary")
    database.savePage("snapshot-job", 0, "partial", blocks.toString(), width = 612.0, height = 792.0,
      error = "page warning", expectedBlocks = 7)
    database.savePage("snapshot-job", 1, "completed", "not-json", expectedBlocks = 0)
    assertEquals(2, database.pageRows("snapshot-job").length())
    assertNull(database.page("snapshot-job", 7))
    assertEquals(6, database.pageBlockArray("snapshot-job", 0).length())
    assertEquals(0, database.pageBlockArray("snapshot-job", 7).length())
    assertEquals(2, database.completedPageCount("snapshot-job"))

    repeat(10) { database.recordBlockDuration("snapshot-job", if (it == 0) -1 else 1) }
    val running = database.jobJson("snapshot-job")!!
    assertEquals(4, running.getInt("completedBlocks"))
    assertEquals(6, running.getInt("discoveredBlocks"))
    assertEquals(7, running.getInt("totalBlocks"))
    assertEquals(1, running.getInt("etaSeconds"))
    assertEquals(1, running.getJSONObject("etaRangeSeconds").getInt("min"))
    assertEquals(1, running.getJSONObject("etaRangeSeconds").getInt("max"))
    assertEquals("page warning", database.page("snapshot-job", 0)!!.getString("error"))

    database.updateJob("snapshot-job", status = "paused", stage = "queued", clearCurrentPage = true, clearError = true)
    val paused = database.jobJson("snapshot-job")!!
    assertTrue(paused.isNull("currentPage"))
    assertTrue(paused.isNull("error"))
    assertTrue(paused.isNull("etaSeconds"))
    database.updateJob("snapshot-job", currentPage = 1, error = "retry")
    assertEquals(1, database.jobJson("snapshot-job")!!.getInt("currentPage"))
    assertEquals("retry", database.jobJson("snapshot-job")!!.getString("error"))

    database.deleteJob("snapshot-job")
    assertNull(database.jobJson("snapshot-job"))
    assertEquals(0, database.pageRows("snapshot-job").length())
    assertFalse(database.hasActiveJob())
    assertFalse(database.modelInUse(ModelCatalog.RECOMMENDED_ID))
  }

  @Test
  fun jobEtaRequiresTranslatingStageAndPositiveKnownWorkRemaining() {
    val source = File(context.filesDir, "eta-boundaries.pdf").apply { writeText("source") }
    val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    database.upsertDocument("eta-boundaries-doc", "source.pdf", source.absolutePath, 1, hash)
    database.addJob("eta-boundaries-job", JSONObject().put("documentId", "eta-boundaries-doc")
      .put("sourceLanguage", "en").put("targetLanguage", "ru")
      .put("modelId", ModelCatalog.RECOMMENDED_ID), 1)
    repeat(10) { database.recordBlockDuration("eta-boundaries-job", 250) }
    database.savePage("eta-boundaries-job", 0, "running", "[]", expectedBlocks = 1)
    database.updateJob("eta-boundaries-job", status = "running", stage = "queued")

    assertTrue(database.jobJson("eta-boundaries-job")!!.isNull("etaSeconds"))

    database.updateJob("eta-boundaries-job", stage = "translating")
    database.savePage("eta-boundaries-job", 0, "running",
      JSONArray().put(JSONObject().put("status", "translated")).toString(), expectedBlocks = 1)
    val complete = database.jobJson("eta-boundaries-job")!!
    assertEquals(1, complete.getInt("completedBlocks"))
    assertEquals(1, complete.getInt("totalBlocks"))
    assertTrue("no ETA should be shown when no blocks remain", complete.isNull("etaSeconds"))
    source.delete()
  }

  @Test
  fun snapshotValidationDetectsSourceDeletionAfterReadingTheJobRow() {
    val source = File(context.filesDir, "deleted-during-validation.pdf").apply { writeText("source") }
    val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    database.upsertDocument("deleted-during-validation-doc", "source.pdf", source.absolutePath, 1, hash)
    database.addJob("deleted-during-validation-job", JSONObject().put("documentId", "deleted-during-validation-doc")
      .put("sourceLanguage", "en").put("targetLanguage", "ru")
      .put("modelId", ModelCatalog.RECOMMENDED_ID), 1)
    database.close()
    val racingDatabase = TranslatorDatabase(context, queryForRead = { db, sql, args ->
      if (sql.startsWith("SELECT observed_block_ms")) {
        db.delete("documents", "id=?", arrayOf("deleted-during-validation-doc"))
      }
      db.rawQuery(sql, args)
    })

    try {
      val failure = runCatching {
        racingDatabase.validateJobSnapshot("deleted-during-validation-job", ModelCatalog.models.first())
      }.exceptionOrNull()
      assertEquals("The saved source document is unavailable.", failure?.message)
      assertNull(racingDatabase.jobJson("deleted-during-validation-job"))
    } finally {
      racingDatabase.close()
      source.delete()
    }
  }

  @Test
  fun snapshotHashReadAndCloseFailuresPreservePrimaryErrorAndCloseInput() {
    val source = File(context.filesDir, "source-stream-failure.pdf").apply { writeText("verified source") }
    val bytes = source.readBytes()
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    database.upsertDocument("source-stream-failure-doc", "source.pdf", source.absolutePath, 1, hash)
    database.addJob("source-stream-failure-job", JSONObject().put("documentId", "source-stream-failure-doc")
      .put("sourceLanguage", "en").put("targetLanguage", "ru")
      .put("modelId", ModelCatalog.RECOMMENDED_ID), 1)
    database.close()

    var failedReadStreamClosed = false
    val failingReadDatabase = TranslatorDatabase(context, openSource = {
      object : InputStream() {
        override fun read(): Int = throw IOException("source read failed")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("source read failed")
        override fun close() {
          failedReadStreamClosed = true
          throw IOException("source close failed")
        }
      }
    })
    try {
      val failure = runCatching {
        failingReadDatabase.validateJobSnapshot("source-stream-failure-job", ModelCatalog.models.first())
      }.exceptionOrNull()
      assertEquals("source read failed", failure?.message)
      assertEquals("source close failed", failure?.suppressed?.singleOrNull()?.message)
      assertTrue("the stream is closed even when read and close both fail", failedReadStreamClosed)
    } finally {
      failingReadDatabase.close()
    }

    var failedCloseStreamClosed = false
    val failingCloseDatabase = TranslatorDatabase(context, openSource = {
      object : ByteArrayInputStream(bytes) {
        override fun close() {
          failedCloseStreamClosed = true
          throw IOException("source close failed")
        }
      }
    })
    try {
      val failure = runCatching {
        failingCloseDatabase.validateJobSnapshot("source-stream-failure-job", ModelCatalog.models.first())
      }.exceptionOrNull()
      assertEquals("source close failed", failure?.message)
      assertTrue("a successful digest still closes the opened source stream", failedCloseStreamClosed)
    } finally {
      failingCloseDatabase.close()
      source.delete()
    }
  }

  @Test
  fun snapshotValidationReportsAJobRemovedBetweenPipelineAndSnapshotReads() {
    createJob("removed-before-validation")
    database.close()
    val racingDatabase = TranslatorDatabase(context, queryForRead = { db, sql, args ->
      val cursor = db.rawQuery(sql, args)
      if (sql == "SELECT pipeline_version FROM jobs WHERE id=?") {
        object : CursorWrapper(cursor) {
          override fun close() {
            super.close()
            db.delete("jobs", "id=?", arrayOf("removed-before-validation"))
          }
        }
      } else cursor
    })

    try {
      val failure = runCatching {
        racingDatabase.validateJobSnapshot("removed-before-validation", ModelCatalog.models.first())
      }.exceptionOrNull()
      assertEquals("Translation job was not found.", failure?.message)
    } finally {
      racingDatabase.close()
    }
  }

  @Test
  fun jobsJsonSkipsOrphanedRowsThatCannotJoinTheirSourceDocument() {
    val db = database.writableDatabase
    db.execSQL("PRAGMA foreign_keys=OFF")
    try {
      db.execSQL("""
        INSERT INTO jobs(id,document_id,model_id,model_revision,model_profile,source_sha256,glossary_snapshot,
          pipeline_version,source_lang,target_lang,status,stage,total_pages,created_at,updated_at)
        VALUES('orphan-job','missing-document','model','rev','profile','hash','',4,'en','ru','paused','queued',1,1,1)
      """.trimIndent())

      assertEquals(0, database.jobsJson().length())
    } finally {
      db.execSQL("DELETE FROM jobs WHERE id='orphan-job'")
      db.execSQL("PRAGMA foreign_keys=ON")
    }
  }

  @Test
  fun jobEtaRemainsUnknownUntilEveryPageHasBlockCountsAndGlossaryPairIsExact() {
    val document = File(context.filesDir, "unknown-eta-source.pdf").apply { writeText("source") }
    val hash = MessageDigest.getInstance("SHA-256").digest(document.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    database.upsertDocument("unknown-eta-doc", "unknown.pdf", document.absolutePath, 2, hash)
    val jobInput = JSONObject().put("documentId", "unknown-eta-doc").put("sourceLanguage", "en")
      .put("targetLanguage", "ru").put("modelId", ModelCatalog.RECOMMENDED_ID)
    database.addJob("unknown-eta-job", jobInput, 2)
    database.addJob("no-glossary-job", JSONObject().put("documentId", "unknown-eta-doc")
      .put("sourceLanguage", "en").put("targetLanguage", "ru").put("modelId", ModelCatalog.RECOMMENDED_ID)
      .put("glossaryId", " "), 2)
    assertNull(database.jobGlossaryId("no-glossary-job"))
    assertEquals("", database.jobGlossaryText("no-glossary-job"))
    database.updateJob("unknown-eta-job", status = "running", stage = "translating")
    repeat(10) { database.recordBlockDuration("unknown-eta-job", 250) }
    database.savePage("unknown-eta-job", 0, "running", "{bad-json", expectedBlocks = 3)
    assertTrue(database.jobJson("unknown-eta-job")!!.isNull("totalBlocks"))
    assertTrue(database.jobJson("unknown-eta-job")!!.isNull("etaSeconds"))
    database.savePage("unknown-eta-job", 1, "running", "[]")
    assertTrue(database.jobJson("unknown-eta-job")!!.isNull("totalBlocks"))
    assertTrue(database.jobJson("unknown-eta-job")!!.isNull("etaSeconds"))

    database.saveGlossary("en-ru-a", "  wheel nut  ", " гайка ", "en", "ru")
    database.saveGlossary("en-ru-b", "bolt", "болт", "en", "ru")
    database.saveGlossary("ru-en", "гайка", "wheel nut", "ru", "en")
    assertEquals("bolt translates to болт\nwheel nut translates to гайка", database.glossaryText(null, "en", "ru"))
    assertEquals("wheel nut translates to гайка", database.glossaryText("en-ru-a", "en", "ru"))
    assertEquals("", database.glossaryText("en-ru-a", "ru", "en"))
    assertEquals(3, database.glossaryJson().length())
    assertTrue(runCatching { database.saveGlossary("blank", " ", "target", "en", "ru") }.isFailure)
    assertTrue(runCatching { database.saveGlossary("blank-target", "source", " ", "en", "ru") }.isFailure)
    assertTrue(runCatching { database.saveGlossary("unsupported", "source", "target", "xx", "ru") }.isFailure)
    database.deleteGlossaryEntry("ru-en")
    assertEquals(2, database.glossaryJson().length())
    document.delete()
  }

  @Test
  fun versionOneDatabaseUpgradesAllSchemaColumnsWithoutLosingSavedRows() {
    database.close()
    context.deleteDatabase("translator-private.db")
    val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("translator-private.db"), null)
    legacy.execSQL("CREATE TABLE documents (id TEXT PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL, pages INTEGER NOT NULL, source_sha256 TEXT NOT NULL, imported_at INTEGER NOT NULL)")
    legacy.execSQL("CREATE TABLE jobs (id TEXT PRIMARY KEY, document_id TEXT NOT NULL, model_id TEXT NOT NULL, model_revision TEXT NOT NULL, source_sha256 TEXT NOT NULL, glossary_snapshot TEXT NOT NULL, pipeline_version INTEGER NOT NULL, source_lang TEXT NOT NULL, target_lang TEXT NOT NULL, status TEXT NOT NULL, stage TEXT NOT NULL, completed_pages INTEGER NOT NULL DEFAULT 0, total_pages INTEGER NOT NULL, current_page INTEGER, glossary_id TEXT, error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
    legacy.execSQL("CREATE TABLE pages (job_id TEXT NOT NULL, page_index INTEGER NOT NULL, status TEXT NOT NULL, blocks_json TEXT NOT NULL, width REAL NOT NULL DEFAULT 0, height REAL NOT NULL DEFAULT 0, error TEXT, updated_at INTEGER NOT NULL, PRIMARY KEY(job_id, page_index))")
    legacy.execSQL("CREATE TABLE glossary (id TEXT PRIMARY KEY, source TEXT NOT NULL, target TEXT NOT NULL, created_at INTEGER NOT NULL)")
    legacy.execSQL("CREATE TABLE exports (id TEXT PRIMARY KEY, path TEXT NOT NULL, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
    legacy.execSQL("INSERT INTO documents(id,name,path,pages,source_sha256,imported_at) VALUES('legacy-doc','legacy.pdf','/private/legacy.pdf',1,'${"a".repeat(64)}',1)")
    legacy.execSQL("INSERT INTO jobs(id,document_id,model_id,model_revision,source_sha256,glossary_snapshot,pipeline_version,source_lang,target_lang,status,stage,total_pages,created_at,updated_at) VALUES('legacy-job','legacy-doc','model','rev','hash','',1,'en','ru','paused','queued',1,1,1)")
    legacy.execSQL("INSERT INTO pages(job_id,page_index,status,blocks_json,updated_at) VALUES('legacy-job',0,'partial','[]',1)")
    legacy.execSQL("INSERT INTO glossary(id,source,target,created_at) VALUES('legacy-term','wheel nut','гайка',1)")
    legacy.version = 1
    legacy.close()

    database = TranslatorDatabase(context)
    assertEquals(7, database.readableDatabase.version)
    assertEquals(0L, database.readableDatabase.rawQuery("SELECT observed_block_ms FROM jobs WHERE id='legacy-job'", null).use { it.moveToFirst(); it.getLong(0) })
    assertEquals("hy-mt2", database.readableDatabase.rawQuery("SELECT model_profile FROM jobs WHERE id='legacy-job'", null).use { it.moveToFirst(); it.getString(0) })
    assertEquals(0, database.readableDatabase.rawQuery("SELECT expected_blocks FROM pages WHERE job_id='legacy-job'", null).use { it.moveToFirst(); it.getInt(0) })
    assertEquals("en", database.glossaryJson().getJSONObject(0).getString("sourceLanguage"))
    assertEquals("ru", database.glossaryJson().getJSONObject(0).getString("targetLanguage"))
    assertEquals("wheel nut translates to гайка", database.glossaryText(null, "en", "ru"))
    assertEquals("local", database.readableDatabase.rawQuery("SELECT engine_type FROM jobs WHERE id='legacy-job'", null).use { it.moveToFirst(); it.getString(0) })
    assertEquals("model", database.readableDatabase.rawQuery("SELECT engine_id FROM jobs WHERE id='legacy-job'", null).use { it.moveToFirst(); it.getString(0) })
    assertEquals("pdf", database.readableDatabase.rawQuery("SELECT format FROM documents WHERE id='legacy-doc'", null).use { it.moveToFirst(); it.getString(0) })
  }

  @Test
  fun currentSchemaIncludesAdditiveFormatEngineConsentAndMimeColumns() {
    database.readableDatabase
    val jobs = database.readableDatabase.rawQuery("PRAGMA table_info(jobs)", null).use { cursor ->
      buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
    }
    val documents = database.readableDatabase.rawQuery("PRAGMA table_info(documents)", null).use { cursor ->
      buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
    }
    val exports = database.readableDatabase.rawQuery("PRAGMA table_info(exports)", null).use { cursor ->
      buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
    }
    assertTrue(jobs.containsAll(listOf("engine_type", "engine_id", "online_consent", "progress_unit")))
    assertTrue(documents.containsAll(listOf("format", "segment_count", "warnings_json")))
    assertTrue(exports.contains("mime_type"))
  }

  @Test
  fun onlineJobsRequireExplicitConsentAndSnapshotTheOnlineProviderWithoutModelLookup() {
    database.upsertDocument("online-doc", "sample.pdf", "/private/sample.pdf", 1, "a".repeat(64))
    val base = JSONObject().put("documentId", "online-doc").put("sourceLanguage", "zh-Hans")
      .put("targetLanguage", "ru").put("modelId", "mymemory")
      .put("engine", JSONObject().put("kind", "online").put("id", "mymemory"))
    assertTrue(runCatching { database.addJob("denied", base, 1) }.isFailure)
    val job = database.addJob("accepted", JSONObject(base.toString()).put("onlineConsent", true), 1)
    assertEquals("online", job.getJSONObject("engine").getString("kind"))
    assertEquals("mymemory", job.getJSONObject("engine").getString("id"))
    assertTrue(job.getBoolean("onlineConsent"))
    assertEquals(5, job.getInt("pipelineVersion"))
  }

  @Test
  fun docxJobExposesUiFormatAndPreservesImportWarnings() {
    val documentWarnings = JSONArray().put("A text box was preserved without translation.")
    database.upsertDocument("warned-docx", "sample.docx", "/private/sample.docx", 1,
      "a".repeat(64), format = "docx", segmentCount = 4, warnings = documentWarnings)

    val job = database.addJob("warned-docx-job", JSONObject()
      .put("documentId", "warned-docx").put("sourceLanguage", "en").put("targetLanguage", "ru")
      .put("modelId", ModelCatalog.RECOMMENDED_ID), pages = 4)

    assertEquals("docx", job.getString("format"))
    assertEquals("docx", job.getString("documentFormat"))
    assertEquals("segment", job.getString("progressUnit"))
    assertEquals(4, job.getInt("segmentCount"))
    assertEquals(documentWarnings.toString(), job.getJSONArray("warnings").toString())
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

private class PlannedCursor(
  delegate: Cursor,
  private val empty: Boolean = false,
  private val failRead: Boolean = false,
  private val failClose: Boolean = false,
) : CursorWrapper(delegate) {
  var closed: Boolean = false
    private set

  override fun moveToFirst(): Boolean {
    if (failRead) throw IllegalStateException("cursor read failed")
    return if (empty) false else super.moveToFirst()
  }

  override fun moveToNext(): Boolean {
    if (failRead) throw IllegalStateException("cursor read failed")
    return if (empty) false else super.moveToNext()
  }

  override fun close() {
    super.close()
    closed = true
    if (failClose) throw IllegalArgumentException("cursor close failed")
  }
}

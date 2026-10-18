package dev.kigya.translatepdf

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class TranslationServiceDestroyHandlerTest {
  private lateinit var context: Context
  private lateinit var database: TranslatorDatabase

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    context.deleteDatabase("translator-private.db")
    database = TranslatorDatabase(context)
    database.upsertDocument("doc-1", "sample.pdf", "/private/sample.pdf", 1, "a".repeat(64))
  }

  @After
  fun tearDown() {
    database.close()
    context.deleteDatabase("translator-private.db")
  }

  @Test
  fun destroyCancelsNativeWorkAndPausesOnlyTheRunningJob() {
    createJob("job-running", "running")
    database.updateJob("job-running", currentPage = 0)
    val stopped = AtomicBoolean(false)
    val actions = mutableListOf<String>()

    TranslationServiceDestroyHandler.run(
      stopped, database, "job-running",
      cancelNative = { actions.add("cancel") },
      stopOcr = { actions.add("ocr") },
      pauseDownload = { actions.add("download") },
      interruptWorker = { actions.add("worker") },
    )

    assertTrue(stopped.get())
    assertEquals(listOf("cancel", "ocr", "download", "worker"), actions)
    val paused = database.jobJson("job-running")!!
    assertEquals("paused", paused.getString("status"))
    assertTrue(paused.getString("error").isNotBlank())
    assertTrue(paused.isNull("currentPage"))
  }

  @Test
  fun destroyDoesNotOverwriteTerminalJobAndContinuesCleanupAfterNativeCancelFailure() {
    createJob("job-complete", "completed")
    val stopped = AtomicBoolean(false)
    val actions = mutableListOf<String>()

    TranslationServiceDestroyHandler.run(
      stopped, database, "job-complete",
      cancelNative = { actions.add("cancel"); error("Native library already unloaded") },
      stopOcr = { actions.add("ocr") },
      pauseDownload = { actions.add("download") },
      interruptWorker = { actions.add("worker") },
    )

    assertTrue(stopped.get())
    assertEquals(listOf("cancel", "ocr", "download", "worker"), actions)
    val completed = database.jobJson("job-complete")!!
    assertEquals("completed", completed.getString("status"))
    assertTrue(completed.isNull("error"))
  }

  @Test
  fun destroyWithoutActiveJobOnlyStopsServiceResources() {
    val stopped = AtomicBoolean(false)
    var interrupted = false

    TranslationServiceDestroyHandler.run(
      stopped, database, null,
      cancelNative = {}, stopOcr = {}, pauseDownload = {}, interruptWorker = { interrupted = true },
    )

    assertTrue(stopped.get())
    assertTrue(interrupted)
    assertFalse(database.hasActiveJob())
  }

  private fun createJob(id: String, status: String) {
    database.addJob(id, JSONObject().put("documentId", "doc-1").put("sourceLanguage", "en")
      .put("targetLanguage", "ru").put("modelId", ModelCatalog.RECOMMENDED_ID), pages = 1)
    database.updateJob(id, status = status)
  }
}

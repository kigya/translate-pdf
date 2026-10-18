package dev.kigya.translatepdf

import android.content.Context
import android.app.Application
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class TranslatorDatabaseTest {
  private lateinit var context: Context
  private lateinit var database: TranslatorDatabase

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    context.deleteDatabase("translator-private.db")
    database = TranslatorDatabase(context)
  }

  @After
  fun tearDown() {
    database.close()
    context.deleteDatabase("translator-private.db")
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
    database.saveGlossary("glossary-1", "wheel nut", "колесная гайка")
    return database.addJob(id, JSONObject()
      .put("documentId", "doc-$id").put("sourceLanguage", "en").put("targetLanguage", "ru")
      .put("modelId", ModelCatalog.RECOMMENDED_ID).put("glossaryId", "glossary-1"), pages = 2)
  }
}

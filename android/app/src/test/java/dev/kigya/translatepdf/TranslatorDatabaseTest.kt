package dev.kigya.translatepdf

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.app.Application
import java.io.File
import java.security.MessageDigest
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

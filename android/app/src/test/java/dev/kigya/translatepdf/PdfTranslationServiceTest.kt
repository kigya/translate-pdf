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
import java.util.concurrent.CountDownLatch
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
  private var pdfModelId = ModelCatalog.RECOMMENDED_ID
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
    ShadowNativePdfCore.exportCallback = { index, request, destination -> ServiceNativeState.export(index, request, destination) }
    ShadowNativePdfCore.mergeCallback = { request, destination -> ServiceNativeState.merge(request, destination) }
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
      .put("targetLanguage", target).put("modelId", pdfModelId), pages)
    return id
  }
  private fun sourceRect(text: String, left: Double, top: Double, right: Double, bottom: Double,
                        baseline: Double = bottom - 2.0, objectIndex: Int = 0, pictogram: Boolean = false) =
    JSONObject().put("objectIndex", objectIndex).put("text", text).put("left", left).put("top", top)
      .put("right", right).put("bottom", bottom).put("baseline", baseline).put("pictogram", pictogram)
  private fun useBalancedPdfModel() {
    val bytes = byteArrayOf(4, 5, 6)
    val definition = ModelCatalog.models.single { it.profile == "translategemma" }
      .copy(bytes = bytes.size.toLong(), sha256 = sha(bytes))
    pdfModelId = definition.id
    downloader = ModelDownloader(context, database, definitionLookup = { definition })
    downloader.fileFor(definition.id).apply { parentFile?.mkdirs(); writeBytes(bytes) }
    database.modelStatus(definition.id, "ready", definition.bytes)
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
  private fun createDocxJob(segments: Int = 1): Pair<String, File> {
    val id = "word-${++sequence}"
    val source = File(context.filesDir, "$id.docx").apply { writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3)) }
    database.upsertDocument("doc-$id", "source.docx", source.absolutePath, segments, sha(source.readBytes()),
      format = "docx", segmentCount = segments)
    database.addJob(id, JSONObject().put("documentId", "doc-$id").put("sourceLanguage", "en")
      .put("targetLanguage", "ru").put("modelId", ModelCatalog.RECOMMENDED_ID), segments)
    return id to source
  }
  private fun docxSegment(index: Int, sourceText: String, warning: String? = null): String {
    val block = ServiceNativeState.block().put("id", "segment-$index").put("text", sourceText).put("sourceText", sourceText)
    val result = JSONObject().put("width", 612).put("height", 792).put("blocks", JSONArray().put(block))
    warning?.let { result.put("warnings", JSONArray().put(it)) }
    return result.toString()
  }
  private fun pdfPageWithTexts(vararg texts: String): String = JSONObject().put("width", 612).put("height", 792)
    .put("blocks", JSONArray().also { blocks -> texts.forEachIndexed { index, text ->
      blocks.put(ServiceNativeState.block().put("id", "pdf-block-$index").put("text", text).put("sourceText", text))
    } }).toString()
  private fun pageWithModelText(sourceText: String, modelText: String, blockId: String): String = JSONObject()
    .put("width", 612).put("height", 792).put("context", "Page context is retained for semantic translation.")
    .put("blocks", JSONArray().put(ServiceNativeState.block().put("id", blockId)
      .put("sourceText", sourceText).put("modelText", modelText))).toString()
  private fun failExportWhenTargetIs(id: String, unfitText: String) {
    ServiceNativeState.export = { index, request, destination ->
      val block = JSONObject(request).getJSONArray("blocks").getJSONObject(0)
      if (block.optString("id") == id && block.optString("translatedText") == unfitText) {
        JSONObject().put("error", "Target text does not fit its original source container: $id")
          .put("layoutFeedback", JSONObject().put("blockId", id).put("availableWidthPt", 90.0)
            .put("availableHeightPt", 8.0).put("minimumFontSizePt", 7.0).put("estimatedMaxCharacters", 24))
          .toString()
      } else ServiceNativeState.exportResult(index, request, destination)
    }
  }
  private fun batchPayload(id: String, generation: Long, text: String = "Перевод") = JSONObject()
    .put("id", id).put("generation", generation).put("text", text)
    .put("tokens", 12).put("inputTokens", 20).put("seconds", 1.5).put("soloRetry", false).toString()
  private fun batchSummary(committed: JSONArray, pending: JSONArray = JSONArray(), errors: JSONArray = JSONArray(), canceled: Boolean = false) =
    JSONObject().put("committedIds", committed).put("pendingIds", pending).put("finalErrors", errors).put("canceled", canceled).toString()

  @Test fun hyMtPdfBatchesAtMostFourBlocksAndCommitsOutOfOrderWithoutReorderingThePage() {
    val id = createJob()
    var memoryProbes = 0
    service.memoryInfoProvider = { ActivityManager.MemoryInfo().apply { lowMemory = false; memoryProbes++ } }
    ServiceNativeState.page = { pdfPageWithTexts("Source alpha", "Source beta", "Source gamma", "Source delta", "Source epsilon", "Source zeta") }
    val batchSizes = mutableListOf<Int>()
    val committedIds = mutableListOf<String>()
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requests = JSONArray(requestsJson)
      batchSizes += requests.length()
      val committed = JSONArray()
      for (index in requests.length() - 1 downTo 0) {
        val request = requests.getJSONObject(index)
        val requestId = request.getString("id")
        assertEquals(1, callback.onCompleted(batchPayload(requestId, generation, "Перевод ${request.getString("text").removePrefix("Source ")}")))
        committedIds += requestId
        committed.put(requestId)
      }
      batchSummary(committed)
    }

    start(id)

    assertEquals("completed", status(id))
    assertEquals(listOf(4, 2), batchSizes)
    assertEquals(1, ShadowNativePdfCore.batchLoadCalls)
    assertEquals(2, ShadowNativePdfCore.batchTranslateCalls)
    assertEquals("one memory sample per batch, plus job and page admission", 4, memoryProbes)
    assertEquals(6, job(id).getInt("completedBlocks"))
    val saved = database.pageBlockArray(id, 0)
    assertEquals(6, saved.length())
    for (index in 0 until saved.length()) {
      assertEquals("pdf-block-$index", saved.getJSONObject(index).getString("id"))
      assertEquals("translated", saved.getJSONObject(index).getString("status"))
    }
    assertEquals(listOf("p0b3", "p0b2", "p0b1", "p0b0"), committedIds.take(4))
  }

  @Test fun localPdfRetriesOnlyTheUnfitBlockWithConciseContextAndKeepsItsCheckpointCount() {
    val id = createJob()
    val block = ServiceNativeState.block().put("id", "p0-o1").put("sourceText", "Storage . . . . 88")
      .put("modelText", "Storage")
    ServiceNativeState.page = {
      JSONObject().put("width", 612).put("height", 792).put("context", "The manual describes its storage compartment.")
        .put("blocks", JSONArray().put(block)).toString()
    }
    failExportWhenTargetIs(id = "p0-o1", unfitText = "This is an unnecessarily long translation of the storage label")
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val request = JSONArray(requestsJson).getJSONObject(0)
      val requestId = request.getString("id")
      assertEquals("Storage", request.getString("text"))
      assertEquals(1, callback.onCompleted(batchPayload(requestId, generation,
        "This is an unnecessarily long translation of the storage label")))
      batchSummary(JSONArray().put(requestId))
    }
    val retryContexts = mutableListOf<String>()
    val retryOptions = mutableListOf<JSONObject>()
    ShadowNativePdfCore.translateForLayoutInputCallback = { text, glossary, context, source, target, options ->
      assertEquals("Storage", text)
      assertEquals("en", source)
      assertEquals("ru", target)
      retryContexts += context
      retryOptions += JSONObject(options)
      """{"text":"Хранение"}"""
    }

    start(id)

    assertEquals("completed", status(id))
    assertEquals(1, job(id).getInt("completedBlocks"))
    assertEquals(1, retryContexts.size)
    assertEquals("The manual describes its storage compartment.", retryContexts.single())
    assertEquals(1, retryOptions.single().getInt("attempt"))
    assertEquals(24, retryOptions.single().getInt("maxCharacters"))
    val saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("Storage . . . . 88", saved.getString("sourceText"))
    assertEquals("Storage", saved.getString("modelText"))
    assertEquals("Хранение", saved.getString("translatedText"))
    assertEquals("translated", saved.getString("status"))
    assertEquals(1, saved.getInt("layoutRetryCount"))
    assertEquals(2, saved.getInt("layoutRetryPolicyVersion"))
    assertFalse(saved.has("layoutRetryInProgress"))
    assertFalse(saved.has("layoutRetryError"))
  }

  @Test fun gemmaUsesOnlyDocumentTitleAndRetranslatesSavedBlocksWhenThatTitleChanges() {
    useBalancedPdfModel()
    val id = createJob()
    var documentTitle = "2020 Chevrolet Equinox Owner Manual - First 30 Pages"
    val source = "Equinox"
    ServiceNativeState.page = {
      JSONObject().put("width", 612).put("height", 792)
        .put("context", "A page heading that must not reach TranslateGemma.")
        .put("documentTitle", documentTitle)
        .put("blocks", JSONArray()
          .put(ServiceNativeState.block().put("id", "p0-o1").put("sourceText", source).put("modelText", source))
          .put(ServiceNativeState.block().put("id", "p0-o2").put("sourceText", "Storage").put("modelText", "Storage")))
        .toString()
    }
    val observedContexts = mutableListOf<String>()
    val observedTexts = mutableListOf<String>()
    ShadowNativePdfCore.translateInputCallback = { text, _, context, _, _ ->
      observedTexts += text
      observedContexts += context
      """{"text":"Equinox"}"""
    }
    var memorySamples = 0
    service.memoryInfoProvider = {
      memorySamples++
      ActivityManager.MemoryInfo().apply { lowMemory = memorySamples == 4 }
    }

    start(id)

    assertEquals("paused", status(id))
    val firstCheckpoint = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("translated", firstCheckpoint.getString("status"))
    assertEquals(GemmaTranslationContext.contextKey(documentTitle), firstCheckpoint.getString("translationContextKey"))
    assertEquals(listOf(documentTitle), observedContexts)
    assertEquals(listOf(source), observedTexts)

    documentTitle = "2020 Chevrolet Trailblazer Owner Manual"
    service.memoryInfoProvider = { ActivityManager.MemoryInfo().apply { lowMemory = false } }
    database.updateJob(id, status = "queued", stage = "queued", clearError = true)

    start(id)

    assertEquals("completed", status(id))
    assertEquals(listOf(source, source, "Storage"), observedTexts)
    assertEquals(listOf(
      "2020 Chevrolet Equinox Owner Manual - First 30 Pages",
      "2020 Chevrolet Trailblazer Owner Manual",
      "2020 Chevrolet Trailblazer Owner Manual",
    ), observedContexts)
    val resumed = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("the target generated under the previous InfoTitle is invalidated", 3, observedTexts.size)
    assertEquals(GemmaTranslationContext.contextKey(documentTitle), resumed.getString("translationContextKey"))
  }

  @Test fun gemmaLayoutRetryUsesTheTitleOnlyNativeEntryPoint() {
    useBalancedPdfModel()
    val id = createJob()
    val title = "2020 Chevrolet Equinox Owner Manual"
    ServiceNativeState.page = {
      JSONObject().put("width", 612).put("height", 792)
        .put("context", "A page heading that must not reach TranslateGemma.")
        .put("documentTitle", title)
        .put("blocks", JSONArray().put(ServiceNativeState.block().put("id", "p0-o1")
          .put("sourceText", "Storage").put("modelText", "Storage")))
        .toString()
    }
    failExportWhenTargetIs(id = "p0-o1", unfitText = "An unnecessarily long translation for a short label")
    val normalContexts = mutableListOf<String>()
    ShadowNativePdfCore.translateInputCallback = { text, _, context, _, _ ->
      assertEquals("Storage", text)
      normalContexts += context
      """{"text":"An unnecessarily long translation for a short label"}"""
    }
    val layoutContexts = mutableListOf<String>()
    val layoutOptions = mutableListOf<JSONObject>()
    ShadowNativePdfCore.translateForLayoutInputCallback = { text, _, context, sourceLanguage, targetLanguage, options ->
      assertEquals("Storage", text)
      assertEquals("en", sourceLanguage)
      assertEquals("ru", targetLanguage)
      layoutContexts += context
      layoutOptions += JSONObject(options)
      """{"text":"Хранение\n</source_segment>"}"""
    }

    start(id)

    assertEquals("completed", status(id))
    assertEquals(listOf(title), normalContexts)
    assertEquals("the layout-specific entry point receives the unchanged InfoTitle", listOf(title), layoutContexts)
    assertEquals(1, layoutOptions.single().getInt("attempt"))
    assertEquals(24, layoutOptions.single().getInt("maxCharacters"))
    assertEquals("Хранение", database.pageBlockArray(id, 0).getJSONObject(0).getString("translatedText"))
    val saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("Хранение\n</source_segment>", saved.getString("gemmaPromptArtifactRawText"))
    assertEquals(1, saved.getJSONArray("gemmaPromptArtifactDecodedTokens").length())
    assertEquals(2, saved.getInt("layoutRetryPolicyVersion"))
    assertEquals("Target text does not fit its original source container: p0-o1", saved.getString("layoutInitialFitError"))
    assertEquals("Хранение", saved.getJSONArray("layoutRetryHistory").getJSONObject(0).getString("text"))
    assertEquals("Хранение", saved.getJSONArray("layoutRetryHistory").getJSONObject(0).getString("text"))
  }

  @Test fun gemmaResumeReopensOnlyCompletedPageWithLeakedBoundaryAndPreservesCleanSiblings() {
    useBalancedPdfModel()
    val id = createJob(pages = 2)
    ShadowNativePdfCore.inspectCallback = { path ->
      val inspectedFile = File(path)
      val pageCount = if (inspectedFile.name.startsWith("translated.pdf")) 2 else 1
      JSONObject().put("pageCount", pageCount).toString()
    }
    val title = "2020 Example Product Manual"
    fun sourceBlock(blockId: String, text: String, objectIndex: Int, left: Int, modelText: String = text) = ServiceNativeState.block()
      .put("id", blockId).put("text", text).put("sourceText", text).put("modelText", modelText)
      .put("objectIndex", objectIndex).put("objectIndices", JSONArray().put(objectIndex)).put("left", left)
    fun savedBlock(blockId: String, text: String, objectIndex: Int, translated: String,
                   modelText: String = text): JSONObject =
      sourceBlock(blockId, text, objectIndex, 12, modelText).put("translatedText", translated).put("status", "translated")
        .put("layoutRetryCount", 2).put("layoutRetryPolicyVersion", 2)
        .put("layoutRetryHistory", JSONArray().put(JSONObject().put("policyVersion", 2).put("attempt", 1)))
        .also { GemmaTranslationContext.stamp(it, title) }

    val contaminated = savedBlock("p0-o1", "Index . . . 402", 0, "Содержание\n</source_segment>", "Index")
    val cleanSibling = savedBlock("p0-o2", "Storage", 1, "Хранение")
    database.savePage(id, 0, "completed", JSONArray().put(contaminated).put(cleanSibling).toString(),
      612.0, 792.0, expectedBlocks = 2, knownCompletedBlocks = 2)
    val unrelatedCompleted = savedBlock("p1-o1", "Controls", 2, "Органы управления")
    database.savePage(id, 1, "completed", JSONArray().put(unrelatedCompleted).toString(),
      612.0, 792.0, expectedBlocks = 1, knownCompletedBlocks = 1)

    ServiceNativeState.page = {
      val blocks = JSONArray()
        .put(sourceBlock("p0-o1", "Index . . . 402", 0, 44, "Index"))
        .put(sourceBlock("p0-o2", "Storage", 1, 88))
      JSONObject().put("width", 612).put("height", 792).put("documentTitle", title).put("blocks", blocks).toString()
    }
    val translatedInputs = mutableListOf<String>()
    ShadowNativePdfCore.translateInputCallback = { text, _, context, _, _ ->
      translatedInputs += text
      assertEquals("only the document title reaches Gemma", title, context)
      error("a safely decoded durable target must not be translated again: $text")
    }

    start(id)

    assertEquals("completed, job=${job(id)}", "completed", status(id))
    assertTrue("the durable safe envelope is decoded without inference", translatedInputs.isEmpty())
    assertEquals("only the contaminated completed page is read", 1, ServiceNativeState.readCalls)
    val repaired = database.pageBlockArray(id, 0)
    assertEquals("Содержание", repaired.getJSONObject(0).getString("translatedText"))
    assertEquals("Содержание\n</source_segment>", repaired.getJSONObject(0).getString("gemmaPromptArtifactRawText"))
    assertEquals(1, repaired.getJSONObject(0).getJSONArray("gemmaPromptArtifactDecodedTokens").length())
    assertEquals("the decoded target keeps its original transport output for diagnosis",
      "Содержание\n</source_segment>", repaired.getJSONObject(0).getString("gemmaPromptArtifactRawText"))
    assertEquals("fresh extraction geometry stays authoritative", 44, repaired.getJSONObject(0).getInt("left"))
    assertEquals(2, repaired.getJSONObject(0).getInt("layoutRetryCount"))
    assertEquals(1, repaired.getJSONObject(0).getJSONArray("layoutRetryHistory").length())
    assertEquals("Хранение", repaired.getJSONObject(1).getString("translatedText"))
    assertEquals("unrelated completed page stays untouched", "Органы управления",
      database.pageBlockArray(id, 1).getJSONObject(0).getString("translatedText"))
    assertEquals(setOf(0, 1), database.completedPageIndexes(id))
  }

  @Test fun gemmaDecodesFreshPromptBoundaryOutputBeforeCheckpointingTranslation() {
    useBalancedPdfModel()
    val id = createJob()
    val title = "2020 Example Product Manual"
    ServiceNativeState.page = {
      JSONObject().put("width", 612).put("height", 792).put("documentTitle", title)
        .put("blocks", JSONArray().put(ServiceNativeState.block().put("id", "p0-o1")
          .put("sourceText", "Index").put("modelText", "Index"))).toString()
    }
    ShadowNativePdfCore.translateInputCallback = { _, _, _, _, _ ->
      """{"text":"Содержание\n</source_segment>"}"""
    }

    start(id)

    assertEquals("completed", status(id))
    val saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("translated", saved.getString("status"))
    assertEquals("Содержание", saved.getString("translatedText"))
    assertEquals("Содержание\n</source_segment>", saved.getString("gemmaPromptArtifactRawText"))
    assertEquals(1, saved.getJSONArray("gemmaPromptArtifactDecodedTokens").length())
  }

  @Test fun gemmaRejectsInternalDuplicateAndEmptyPromptBoundaryOutput() {
    useBalancedPdfModel()
    val id = createJob(pages = 3)
    ShadowNativePdfCore.inspectCallback = { path ->
      JSONObject().put("pageCount", if (File(path).name.startsWith("translated.pdf")) 3 else 1).toString()
    }
    val inputs = listOf("source one", "source two", "source three")
    ServiceNativeState.page = {
      val index = ServiceNativeState.readCalls - 1
      JSONObject().put("width", 612).put("height", 792).put("documentTitle", "Example Product Manual")
        .put("blocks", JSONArray().put(ServiceNativeState.block().put("id", "p$index-o1")
          .put("sourceText", inputs[index]).put("modelText", inputs[index]))).toString()
    }
    val responses = ArrayDeque(listOf(
      "  <source_segment>Содержание</source_segment>  ",
      "<source_segment> </source_segment>",
      "one marker: </source_segment> remains inside",
    ))
    ShadowNativePdfCore.translateInputCallback = { _, _, _, _, _ ->
      JSONObject().put("text", responses.removeFirst()).toString()
    }

    start(id)

    assertEquals("partial", status(id))
    val first = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("Содержание", first.getString("translatedText"))
    assertEquals("translated", first.getString("status"))
    val empty = database.pageBlockArray(id, 1).getJSONObject(0)
    assertTrue(empty.isNull("translatedText"))
    assertEquals("failed", empty.getString("status"))
    assertEquals(GemmaTranslationContext.PROMPT_ARTIFACT_ERROR, empty.getString("error"))
    val internal = database.pageBlockArray(id, 2).getJSONObject(0)
    assertTrue(internal.isNull("translatedText"))
    assertEquals("failed", internal.getString("status"))
    assertEquals("one marker: </source_segment> remains inside", internal.getString("gemmaPromptArtifactRejectedText"))
  }

  @Test fun hyMtPdfKeepsItsPageContextForBatchTranslation() {
    val id = createJob()
    val pageContext = "The page heading remains Hy-MT2 translation context."
    ServiceNativeState.page = {
      JSONObject().put("width", 612).put("height", 792).put("context", pageContext)
        .put("documentTitle", "A title that belongs only to TranslateGemma")
        .put("blocks", JSONArray().put(ServiceNativeState.block().put("id", "p0-o1")
          .put("sourceText", "Storage").put("modelText", "Storage"))).toString()
    }
    val backgrounds = mutableListOf<String>()
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, background, _, _, generation, callback ->
      backgrounds += background
      val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
      assertEquals(1, callback.onCompleted(batchPayload(requestId, generation, "Хранение")))
      batchSummary(JSONArray().put(requestId))
    }

    start(id)

    assertEquals("completed", status(id))
    assertEquals(listOf(pageContext), backgrounds)
  }

  @Test fun layoutRetryBudgetPersistsAcrossResumeAndRetainsTheLastTargetOnExhaustion() {
    val id = createJob()
    val sourceText = "Infotainment System . . . 153"
    ServiceNativeState.page = { pageWithModelText(sourceText, "Infotainment System", "p0-o7") }
    failExportWhenTargetIs(id = "p0-o7", unfitText = "A deliberately overlong translation for the infotainment system")
    var batchCalls = 0
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      batchCalls++
      val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
      assertEquals(1, callback.onCompleted(batchPayload(requestId, generation,
        "A deliberately overlong translation for the infotainment system")))
      batchSummary(JSONArray().put(requestId))
    }
    var retryCalls = 0
    ShadowNativePdfCore.translateInputCallback = { _, _, _, _, _ ->
      retryCalls++
      """{"text":"A deliberately overlong translation for the infotainment system"}"""
    }

    start(id)

    assertEquals("failed", status(id))
    assertEquals(2, retryCalls)
    assertEquals(1, batchCalls)
    assertEquals(1, job(id).getInt("completedBlocks"))
    var saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("A deliberately overlong translation for the infotainment system", saved.getString("translatedText"))
    assertEquals("Infotainment System", saved.getString("modelText"))
    assertEquals(sourceText, saved.getString("sourceText"))
    assertEquals(2, saved.getInt("layoutRetryCount"))
    assertEquals(2, saved.getInt("layoutRetryPolicyVersion"))
    val history = saved.getJSONArray("layoutRetryHistory")
    assertEquals(2, history.length())
    assertEquals(24, history.getJSONObject(0).getInt("maxCharacters"))
    assertEquals(24, history.getJSONObject(1).getInt("maxCharacters"))
    assertTrue(history.getJSONObject(0).getString("fitError").contains("p0-o7"))
    assertTrue(history.getJSONObject(1).getString("fitError").contains("p0-o7"))
    assertFalse(saved.has("layoutRetryInProgress"))
    assertTrue(error(id).contains("p0-o7"))

    start(id)

    assertEquals("failed", status(id))
    assertEquals("the persisted retry budget prevents another model request", 2, retryCalls)
    assertEquals(1, job(id).getInt("completedBlocks"))
    saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("A deliberately overlong translation for the infotainment system", saved.getString("translatedText"))
    assertEquals(2, saved.getInt("layoutRetryCount"))
    assertEquals(2, saved.getJSONArray("layoutRetryHistory").length())
  }

  @Test fun legacyExhaustedLayoutCheckpointReceivesExactlyOneVersionedRetryBudget() {
    val id = createJob()
    val source = "Canadian Vehicle Owners . . . 7"
    val oldTarget = "An overlong translation retained by the previous retry policy"
    val legacyHistory = JSONArray().put(JSONObject().put("attempt", 1).put("text", "Earlier legacy candidate"))
    val savedBlock = ServiceNativeState.block().put("id", "p0-o7")
      .put("sourceText", source).put("modelText", "Canadian Vehicle Owners")
      .put("translatedText", oldTarget).put("status", "translated")
      .put("layoutRetryCount", 2).put("layoutRetryHistory", legacyHistory)
    database.savePage(id, 0, "running", JSONArray().put(savedBlock).toString(),
      612.0, 792.0, expectedBlocks = 1, knownCompletedBlocks = 1)
    ServiceNativeState.page = { pageWithModelText(source, "Canadian Vehicle Owners", "p0-o7") }
    failExportWhenTargetIs(id = "p0-o7", unfitText = oldTarget)
    var retryCalls = 0
    ShadowNativePdfCore.translateForLayoutInputCallback = { _, _, _, _, _, options ->
      retryCalls++
      val parsed = JSONObject(options)
      assertEquals(retryCalls, parsed.getInt("attempt"))
      assertEquals(24, parsed.getInt("maxCharacters"))
      """{"text":"$oldTarget"}"""
    }

    start(id)

    assertEquals("failed", status(id))
    assertEquals("a legacy count of two is retained and upgraded once", 2, retryCalls)
    val saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals(oldTarget, saved.getString("translatedText"))
    assertEquals(2, saved.getInt("layoutRetryLegacyCount"))
    assertEquals(2, saved.getInt("layoutRetryPolicyVersion"))
    assertEquals(2, saved.getInt("layoutRetryCount"))
    val history = saved.getJSONArray("layoutRetryHistory")
    assertEquals("the old history row remains byte-for-byte represented", legacyHistory.getJSONObject(0).toString(),
      history.getJSONObject(0).toString())
    assertEquals(3, history.length())
    assertEquals(1, history.getJSONObject(1).getInt("attempt"))
    assertEquals(2, history.getJSONObject(2).getInt("attempt"))
    assertTrue(history.getJSONObject(1).getString("fitError").contains("p0-o7"))
    assertTrue(history.getJSONObject(2).getString("fitError").contains("p0-o7"))
    assertFalse(saved.has("layoutRetryInProgress"))
  }

  @Test fun multipleUnfitBlocksRetryIndependentlyWithoutChangingAnAlreadyCompletedBlock() {
    val id = createJob()
    val stable = ServiceNativeState.block().put("id", "p0-o0").put("sourceText", "Stable heading")
      .put("modelText", "Stable heading").put("translatedText", "Стабильный заголовок")
      .put("status", "translated").put("layoutRetryCount", 1)
    val first = ServiceNativeState.block().put("id", "p0-o1").put("sourceText", "Storage")
      .put("modelText", "Storage").put("status", "pending")
    val second = ServiceNativeState.block().put("id", "p0-o2").put("sourceText", "Safety")
      .put("modelText", "Safety").put("status", "pending")
    ServiceNativeState.page = {
      JSONObject().put("width", 612).put("height", 792).put("context", "A manual page with two labels.")
        .put("blocks", JSONArray().also { rows ->
          for ((blockId, sourceText, modelText) in listOf(
            Triple("p0-o0", "Stable heading", "Stable heading"),
            Triple("p0-o1", "Storage", "Storage"),
            Triple("p0-o2", "Safety", "Safety"),
          )) rows.put(ServiceNativeState.block().put("id", blockId).put("sourceText", sourceText).put("modelText", modelText))
        }).toString()
    }
    database.savePage(id, 0, "running", JSONArray().put(stable).put(first).put(second).toString(),
      612.0, 792.0, expectedBlocks = 3, knownCompletedBlocks = 1)
    val longStorage = "A deliberately overlong translation for the storage label"
    val longSafety = "A deliberately overlong translation for the safety label"
    ServiceNativeState.export = { index, requestJson, destination ->
      val rows = JSONObject(requestJson).getJSONArray("blocks")
      val unfit = (0 until rows.length()).asSequence().map { rows.getJSONObject(it) }
        .firstOrNull { block -> block.optString("translatedText") in setOf(longStorage, longSafety) }
      if (unfit != null) JSONObject().put("error", "Target text does not fit its original source container: ${unfit.getString("id")}").toString()
      else ServiceNativeState.exportResult(index, requestJson, destination)
    }
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requests = JSONArray(requestsJson)
      val committed = JSONArray()
      for (index in 0 until requests.length()) {
        val request = requests.getJSONObject(index)
        val target = if (request.getString("text") == "Storage") longStorage else longSafety
        val requestId = request.getString("id")
        assertEquals(1, callback.onCompleted(batchPayload(requestId, generation, target)))
        committed.put(requestId)
      }
      batchSummary(committed)
    }
    val retriedInputs = mutableListOf<String>()
    ShadowNativePdfCore.translateInputCallback = { text, _, _, _, _ ->
      retriedInputs += text
      val target = if (text == "Storage") "Хранение" else "Безопасность"
      JSONObject().put("text", target).toString()
    }

    start(id)

    assertEquals("completed", status(id))
    assertEquals(listOf("Storage", "Safety"), retriedInputs)
    assertEquals(3, job(id).getInt("completedBlocks"))
    assertEquals(3, job(id).getInt("discoveredBlocks"))
    val saved = database.pageBlockArray(id, 0)
    assertEquals(3, saved.length())
    assertEquals("Стабильный заголовок", saved.getJSONObject(0).getString("translatedText"))
    assertEquals("durable retry count is retained for the already completed block", 1,
      saved.getJSONObject(0).getInt("layoutRetryCount"))
    assertEquals("Хранение", saved.getJSONObject(1).getString("translatedText"))
    assertEquals("Безопасность", saved.getJSONObject(2).getString("translatedText"))
    assertEquals(1, saved.getJSONObject(1).getInt("layoutRetryCount"))
    assertEquals(1, saved.getJSONObject(2).getInt("layoutRetryCount"))
  }

  @Test fun cancelDuringLayoutRetryKeepsPriorTargetAndClearsInFlightMarker() {
    val id = createJob()
    val longTarget = "An unfit first translation of the cabin storage label"
    ServiceNativeState.page = { pageWithModelText("Cabin storage", "Cabin storage", "p0-o3") }
    failExportWhenTargetIs(id = "p0-o3", unfitText = longTarget)
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
      assertEquals(1, callback.onCompleted(batchPayload(requestId, generation, longTarget)))
      batchSummary(JSONArray().put(requestId))
    }
    ShadowNativePdfCore.translateInputCallback = { _, _, _, _, _ ->
      service.onStartCommand(intent(PdfTranslationService.ACTION_CANCEL, id), 0, sequence + 1)
      """{"text":"Не сохранять после отмены"}"""
    }

    start(id)

    assertEquals("canceled", status(id))
    assertEquals(1, job(id).getInt("completedBlocks"))
    val saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals(longTarget, saved.getString("translatedText"))
    assertEquals(1, saved.getInt("layoutRetryCount"))
    assertFalse(saved.has("layoutRetryInProgress"))
  }

  @Test fun allocationFailureDuringLayoutRetryPausesAndRetainsThePriorTargetAndRetryHistory() {
    val id = createJob()
    val previousTarget = "An unfit first translation of the cabin storage label"
    ServiceNativeState.page = { pageWithModelText("Cabin storage", "Cabin storage", "p0-o3") }
    failExportWhenTargetIs(id = "p0-o3", unfitText = previousTarget)
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
      assertEquals(1, callback.onCompleted(batchPayload(requestId, generation, previousTarget)))
      batchSummary(JSONArray().put(requestId))
    }
    ShadowNativePdfCore.translateForLayoutInputCallback = { _, _, _, _, _, _ -> throw OutOfMemoryError("layout OOM") }

    start(id)

    assertEquals("paused", status(id))
    assertEquals(1, job(id).getInt("completedBlocks"))
    val saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("translated", saved.getString("status"))
    assertEquals(previousTarget, saved.getString("translatedText"))
    assertEquals(1, saved.getInt("layoutRetryCount"))
    assertEquals(2, saved.getInt("layoutRetryPolicyVersion"))
    assertFalse(saved.has("layoutRetryInProgress"))
    assertTrue(saved.getString("layoutRetryError").contains("working memory"))
    val history = saved.getJSONArray("layoutRetryHistory")
    assertEquals(1, history.length())
    assertEquals(previousTarget, history.getJSONObject(0).getString("previousTarget"))
    assertTrue(history.getJSONObject(0).getString("error").contains("working memory"))
  }

  @Test fun emptyOrProtectedLayoutRetryResponseRetainsTheDurableTargetAndRecordsRejectedCandidate() {
    val cases = listOf(
      Triple("empty", "", "The model returned no layout revision."),
      Triple("protected", "Install component 24", "protected number"),
    )
    for ((caseName, responseText, expectedError) in cases) {
      val id = createJob()
      val previousTarget = "An unfit first translation of component number 42"
      ServiceNativeState.page = { pageWithModelText("Install component number 42", "Install component 42", "p0-o9") }
      failExportWhenTargetIs(id = "p0-o9", unfitText = previousTarget)
      ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
        val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
        assertEquals(1, callback.onCompleted(batchPayload(requestId, generation, previousTarget)))
        batchSummary(JSONArray().put(requestId))
      }
      ShadowNativePdfCore.translateForLayoutInputCallback = { _, _, _, _, _, _ ->
        JSONObject().put("text", responseText).toString()
      }

      start(id)

      assertEquals("failed", status(id))
      val saved = database.pageBlockArray(id, 0).getJSONObject(0)
      assertEquals("$caseName response must not replace the durable candidate", previousTarget,
        saved.getString("translatedText"))
      assertEquals(2, saved.getInt("layoutRetryCount"))
      assertFalse(saved.has("layoutRetryInProgress"))
      assertTrue(saved.getString("layoutRetryError").contains(expectedError))
      val history = saved.getJSONArray("layoutRetryHistory")
      assertEquals(2, history.length())
      for (index in 0 until history.length()) {
        val retry = history.getJSONObject(index)
        assertEquals(previousTarget, retry.getString("previousTarget"))
        if (responseText.isBlank()) assertTrue(retry.isNull("text"))
        else assertEquals(responseText, retry.getString("text"))
        assertTrue(retry.getString("error").contains(expectedError))
      }
    }
  }

  @Test fun pausingDuringLayoutRetryKeepsThePriorTargetThenResumesWithinTheSameBudget() {
    val id = createJob()
    ServiceNativeState.page = { pageWithModelText("Cabin storage . . . 55", "Cabin storage", "p0-o3") }
    failExportWhenTargetIs(id = "p0-o3", unfitText = "An unfit first translation of the cabin storage label")
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
      assertEquals(1, callback.onCompleted(batchPayload(requestId, generation,
        "An unfit first translation of the cabin storage label")))
      batchSummary(JSONArray().put(requestId))
    }
    var retryCalls = 0
    val retryOptions = mutableListOf<JSONObject>()
    ShadowNativePdfCore.translateForLayoutInputCallback = { _, _, context, _, _, options ->
      retryCalls++
      assertEquals("Page context is retained for semantic translation.", context)
      retryOptions += JSONObject(options)
      service.onStartCommand(intent(PdfTranslationService.ACTION_PAUSE, id), 0, 700 + sequence)
      """{"text":"Storage"}"""
    }

    start(id)

    assertEquals("paused", status(id))
    assertEquals(1, job(id).getInt("completedBlocks"))
    var saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("An unfit first translation of the cabin storage label", saved.getString("translatedText"))
    assertEquals("the in-flight attempt is durably charged to the bounded retry budget", 1, saved.getInt("layoutRetryCount"))
    assertFalse("the stopped inference is no longer marked in flight", saved.has("layoutRetryInProgress"))

    ShadowNativePdfCore.translateForLayoutInputCallback = { text, _, context, _, _, options ->
      retryCalls++
      assertEquals("Cabin storage", text)
      assertEquals("Page context is retained for semantic translation.", context)
      retryOptions += JSONObject(options)
      """{"text":"Хранение"}"""
    }

    awaitWorkerReleased(id)
    assertNull("the prior generation must be fully released", PdfTranslationService.activeJobId)
    // Match PdfTranslatorModule.resumeJob: a persisted paused job must transition
    // back to queued before the service can atomically claim it as running.
    database.updateJob(id, status = "queued", stage = "queued", clearError = true)
    val previousGeneration = PdfTranslationService::class.java.getDeclaredField("activeWorkerGeneration").apply { isAccessible = true }.getLong(null)
    val loadCallsBeforeResume = ServiceNativeState.loadCalls
    val batchLoadsBeforeResume = ShadowNativePdfCore.batchLoadCalls
    val readsBeforeResume = ServiceNativeState.readCalls
    newWorker()
    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, sequence + 1)
    assertEquals("resume should acquire a fresh worker lease", id, PdfTranslationService.activeJobId)
    val resumedGeneration = PdfTranslationService::class.java.getDeclaredField("activeWorkerGeneration").apply { isAccessible = true }.getLong(null)
    assertNotEquals(previousGeneration, resumedGeneration)
    assertEquals(1, worker.tasks.size)
    worker.runNext()
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    val workerEntered = PdfTranslationService::class.java.getDeclaredField("workerEntered").apply { isAccessible = true }.getBoolean(service)
    val entryAborted = PdfTranslationService::class.java.getDeclaredField("workerEntryAborted").apply { isAccessible = true }.getBoolean(service)
    assertEquals("resumed job state: ${job(id)} retryCalls=$retryCalls loads=${ServiceNativeState.loadCalls}/$loadCallsBeforeResume batchLoads=${ShadowNativePdfCore.batchLoadCalls}/$batchLoadsBeforeResume reads=${ServiceNativeState.readCalls}/$readsBeforeResume entered=$workerEntered aborted=$entryAborted active=${PdfTranslationService.activeJobId}", "completed", status(id))
    assertEquals(1, job(id).getInt("completedBlocks"))
    saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("Хранение", saved.getString("translatedText"))
    assertEquals(2, saved.getInt("layoutRetryCount"))
    assertEquals(2, retryOptions.last().getInt("attempt"))
  }

  @Test fun lowMemoryBeforeLayoutRetryPausesWithoutConsumingTheRetryBudget() {
    val id = createJob()
    val longTarget = "An unfit first translation of the cabin storage label"
    ServiceNativeState.page = { pageWithModelText("Cabin storage", "Cabin storage", "p0-o3") }
    failExportWhenTargetIs(id = "p0-o3", unfitText = longTarget)
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
      assertEquals(1, callback.onCompleted(batchPayload(requestId, generation, longTarget)))
      batchSummary(JSONArray().put(requestId))
    }
    var retryCalls = 0
    ShadowNativePdfCore.translateInputCallback = { _, _, _, _, _ ->
      retryCalls++
      """{"text":"Хранение"}"""
    }
    memoryAt(4)

    start(id)

    assertEquals("paused", status(id))
    var saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals(longTarget, saved.getString("translatedText"))
    assertFalse(saved.has("layoutRetryCount"))
    assertEquals(0, retryCalls)

    database.updateJob(id, status = "queued", stage = "queued", clearError = true)
    service.memoryInfoProvider = { ActivityManager.MemoryInfo().apply { lowMemory = false } }
    start(id)

    assertEquals("completed", status(id))
    assertEquals(1, retryCalls)
    saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("Хранение", saved.getString("translatedText"))
    assertEquals(1, saved.getInt("layoutRetryCount"))
  }

  @Test fun retryRejectsAChangedPersistedSourceIdentityBeforeCallingTheModel() {
    val id = createJob()
    val longTarget = "An unfit first translation of the storage label"
    ServiceNativeState.page = { pageWithModelText("Storage", "Storage", "p0-o1") }
    var changedCheckpoint = false
    ServiceNativeState.export = { index, requestJson, destination ->
      if (!changedCheckpoint) {
        changedCheckpoint = true
        val saved = database.pageBlockArray(id, 0).getJSONObject(0).put("sourceText", "Different source")
        database.savePage(id, 0, "running", JSONArray().put(saved).toString(), 612.0, 792.0,
          expectedBlocks = 1, knownCompletedBlocks = 1)
        JSONObject().put("error", "Target text does not fit its original source container: p0-o1").toString()
      } else ServiceNativeState.exportResult(index, requestJson, destination)
    }
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
      assertEquals(1, callback.onCompleted(batchPayload(requestId, generation, longTarget)))
      batchSummary(JSONArray().put(requestId))
    }
    var retryCalls = 0
    ShadowNativePdfCore.translateInputCallback = { _, _, _, _, _ ->
      retryCalls++
      """{"text":"Хранение"}"""
    }

    start(id)

    assertEquals("failed", status(id))
    assertTrue(changedCheckpoint)
    assertEquals("a stale source checkpoint must not receive a model request", 0, retryCalls)
    assertTrue(error(id).contains("source block changed"))
    assertEquals("Different source", database.pageBlockArray(id, 0).getJSONObject(0).getString("sourceText"))
  }

  @Test fun layoutRetryRejectsProtectedNumberChangesAndRetainsThePreviousTarget() {
    val id = createJob()
    val longTarget = "A deliberately overlong translation of switch number 42"
    ServiceNativeState.page = { pageWithModelText("Switch number 42", "Switch 42", "p0-o1") }
    failExportWhenTargetIs(id = "p0-o1", unfitText = longTarget)
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
      assertEquals(1, callback.onCompleted(batchPayload(requestId, generation, longTarget)))
      batchSummary(JSONArray().put(requestId))
    }
    var retryCalls = 0
    ShadowNativePdfCore.translateInputCallback = { text, _, _, _, _ ->
      retryCalls++
      assertEquals("Switch 42", text)
      """{"text":"Переключатель 24"}"""
    }

    start(id)

    assertEquals("failed", status(id))
    assertEquals(2, retryCalls)
    val saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals(longTarget, saved.getString("translatedText"))
    assertEquals(2, saved.getInt("layoutRetryCount"))
    assertTrue(saved.getString("layoutRetryError").contains("protected number"))
  }

  @Test fun unrelatedNativeExportErrorsNeverTriggerLayoutTranslationRetries() {
    val id = createJob()
    ServiceNativeState.page = { pageWithModelText("Storage . . . 88", "Storage", "p0-o1") }
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
      assertEquals(1, callback.onCompleted(batchPayload(requestId, generation, "Хранение")))
      batchSummary(JSONArray().put(requestId))
    }
    ServiceNativeState.export = { _, _, _ -> JSONObject().put("error", "Source resource graph is invalid.").toString() }
    var retries = 0
    ShadowNativePdfCore.translateInputCallback = { _, _, _, _, _ ->
      retries++
      """{"text":"Не должно быть повтора"}"""
    }

    start(id)

    assertEquals("failed", status(id))
    assertEquals(0, retries)
    val saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("Хранение", saved.getString("translatedText"))
    assertFalse(saved.has("layoutRetryCount"))
    assertTrue(error(id).contains("Source resource graph is invalid"))
  }

  @Test fun localPdfSoloChecksProtectedValuesAgainstModelTextAndKeepsOriginalSource() {
    useBalancedPdfModel()
    val id = createJob()
    val sourceText = "Install component number 42"
    val modelText = "Install symbol [[FVSYMA]]"
    val pageResponse = run {
      val symbol = JSONObject()
        .put("token", "[[FVSYMA]]")
        .put("objectIndex", 0)
        .put("sourceText", "switch icon")
        .put("bounds", JSONObject().put("left", 10).put("top", 10).put("right", 200).put("bottom", 50))
      val block = ServiceNativeState.block()
        .put("sourceText", sourceText)
        .put("modelText", modelText)
        .put("symbols", JSONArray().put(symbol))
      JSONObject()
        .put("width", 612)
        .put("height", 792)
        .put("blocks", JSONArray().put(block))
        .toString()
    }
    ShadowNativePdfCore.readCallback = { ServiceNativeState.readCalls++; pageResponse }
    val translatedInputs = mutableListOf<String>()
    ShadowNativePdfCore.translateInputCallback = { text, _, _, _, _ ->
      translatedInputs += text
      """{"text":"Установить [[FVSYMA]]"}"""
    }

    start(id)

    assertEquals(listOf(modelText), translatedInputs)
    assertEquals("completed", status(id))
    val saved = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("model preprocessing must never replace the durable source", sourceText, saved.getString("sourceText"))
    assertEquals(modelText, saved.getString("modelText"))
    assertEquals("[[FVSYMA]]", saved.getJSONArray("symbols").getJSONObject(0).getString("token"))
    assertEquals("Установить [[FVSYMA]]", saved.getString("translatedText"))
    assertFalse("retained source values omitted from the model input must not create a false mismatch", saved.has("error"))
  }

  @Test fun hyMtPdfMemoUsesModelTextForTocAndDoesNotCompareNativeRetainedPageNumbers() {
    val id = createJob(pages = 2)
    val modelText = "Tighten symbol [[FVSYMA]] to the specified torque"
    var pageRead = 0
    ShadowNativePdfCore.readCallback = {
      ServiceNativeState.readCalls++
      val sourceText = if (pageRead++ == 0) "Tighten to 12 mm" else "Tighten to 18 mm"
      val symbol = JSONObject()
        .put("token", "[[FVSYMA]]")
        .put("objectIndex", 0)
        .put("sourceText", "control icon")
        .put("bounds", JSONObject().put("left", 10).put("top", 10).put("right", 200).put("bottom", 50))
      val block = ServiceNativeState.block()
        .put("id", "block-$pageRead")
        .put("sourceText", sourceText)
        .put("modelText", modelText)
        .put("symbols", JSONArray().put(symbol))
      JSONObject()
        .put("width", 612)
        .put("height", 792)
        .put("context", "same manual context")
        .put("blocks", JSONArray().put(block))
        .toString()
    }
    val sourcePath = File(context.filesDir, "$id.pdf").absolutePath
    ShadowNativePdfCore.inspectCallback = { path ->
      val count = when {
        path == sourcePath -> 2
        path.contains("job-export-") -> 1
        else -> 2
      }
      JSONObject().put("pageCount", count).toString()
    }
    val translatedRequests = mutableListOf<String>()
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requests = JSONArray(requestsJson)
      val committed = JSONArray()
      for (index in 0 until requests.length()) {
        val request = requests.getJSONObject(index)
        translatedRequests += request.getString("text")
        val requestId = request.getString("id")
        assertEquals(1, callback.onCompleted(batchPayload(requestId, generation,
          "Затяните с указанным моментом [[FVSYMA]]")))
        committed.put(requestId)
      }
      batchSummary(committed)
    }

    start(id)

    assertEquals("the second page reuses the committed model-text memo entry", listOf(modelText), translatedRequests)
    assertEquals("native-retained page numbers are outside the model's translation input: ${job(id)}", "completed", status(id))
    assertEquals(2, job(id).getInt("completedBlocks"))
    val first = database.pageBlockArray(id, 0).getJSONObject(0)
    val second = database.pageBlockArray(id, 1).getJSONObject(0)
    assertEquals("Tighten to 12 mm", first.getString("sourceText"))
    assertEquals("Tighten to 18 mm", second.getString("sourceText"))
    assertEquals(modelText, first.getString("modelText"))
    assertEquals(modelText, second.getString("modelText"))
    assertEquals("translated", first.getString("status"))
    assertEquals("translated", second.getString("status"))
    assertEquals("Затяните с указанным моментом [[FVSYMA]]", second.getString("translatedText"))
  }

  @Test fun hyMtPdfTocModelInputOmitsPageNumberAndMemoizedResultDoesNotMarkPagePartial() {
    val id = createJob(pages = 2)
    val modelText = "Storage"
    var pageRead = 0
    ShadowNativePdfCore.readCallback = {
      ServiceNativeState.readCalls++
      val sourceText = if (pageRead++ == 0) "Storage . . . . . . 88" else "Storage . . . . . . 89"
      val block = ServiceNativeState.block()
        .put("id", "toc-$pageRead")
        .put("sourceText", sourceText)
        .put("modelText", modelText)
      JSONObject().put("width", 612).put("height", 792).put("blocks", JSONArray().put(block)).toString()
    }
    val sourcePath = File(context.filesDir, "$id.pdf").absolutePath
    val inspectedPaths = mutableListOf<String>()
    ShadowNativePdfCore.inspectCallback = { path ->
      inspectedPaths += path
      val pages = when {
        path == sourcePath -> 2
        path.contains("job-export-") -> 1
        else -> 2
      }
      JSONObject().put("pageCount", pages).toString()
    }
    val translatedInputs = mutableListOf<String>()
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requests = JSONArray(requestsJson)
      val committed = JSONArray()
      for (index in 0 until requests.length()) {
        val request = requests.getJSONObject(index)
        translatedInputs += request.getString("text")
        val requestId = request.getString("id")
        assertEquals(1, callback.onCompleted(batchPayload(requestId, generation, "Хранение")))
        committed.put(requestId)
      }
      batchSummary(committed)
    }

    start(id)

    assertEquals("the page number is rendered from original PDF glyphs, not sent to inference", listOf(modelText), translatedInputs)
    assertEquals("job state: ${job(id)} paths=$inspectedPaths", "completed", status(id))
    assertEquals(2, job(id).getInt("completedBlocks"))
    for (index in 0 until 2) {
      val block = database.pageBlockArray(id, index).getJSONObject(0)
      assertEquals(if (index == 0) "Storage . . . . . . 88" else "Storage . . . . . . 89", block.getString("sourceText"))
      assertEquals("Storage", block.getString("modelText"))
      assertEquals("Хранение", block.getString("translatedText"))
      assertEquals("translated", block.getString("status"))
      assertFalse(block.has("error"))
    }
  }

  @Test fun maximumPdfUsesEightIndependentLanesAndKeepsTheNinthBlockForTheNextBatch() {
    val bytes = byteArrayOf(7, 8, 9)
    val definition = ModelCatalog.models.single { it.id == "hy-mt2-7b-q4" }
      .copy(bytes = bytes.size.toLong(), sha256 = sha(bytes))
    pdfModelId = definition.id
    downloader = ModelDownloader(context, database, definitionLookup = { definition })
    downloader.fileFor(definition.id).apply { parentFile?.mkdirs(); writeBytes(bytes) }
    database.modelStatus(definition.id, "ready", definition.bytes)
    val id = createJob()
    val texts = listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota")
    ServiceNativeState.page = { pdfPageWithTexts(*texts.map { "Source $it" }.toTypedArray()) }
    var loads = 0
    ShadowNativePdfCore.batchLoadCallback = { _, contextSize, threads, profile, sequences ->
      assertEquals(4096, contextSize)
      assertEquals(4, threads)
      assertEquals("hy-mt2", profile)
      assertEquals(8, sequences)
      loads++
      "{\"loaded\":true}"
    }
    val sizes = mutableListOf<Int>()
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requests = JSONArray(requestsJson)
      sizes += requests.length()
      val committed = JSONArray()
      for (index in requests.length() - 1 downTo 0) {
        val request = requests.getJSONObject(index)
        val requestId = request.getString("id")
        assertEquals(1, callback.onCompleted(batchPayload(requestId, generation,
          "Перевод ${request.getString("text").removePrefix("Source ")}")))
        committed.put(requestId)
      }
      batchSummary(committed)
    }

    start(id)

    assertEquals("completed", status(id))
    assertEquals(1, loads)
    assertEquals(listOf(8, 1), sizes)
    assertEquals(9, job(id).getInt("completedBlocks"))
    val saved = database.pageBlockArray(id, 0)
    for (index in texts.indices) {
      assertEquals("Source ${texts[index]}", saved.getJSONObject(index).getString("sourceText"))
      assertEquals("Перевод ${texts[index]}", saved.getJSONObject(index).getString("translatedText"))
    }
  }

  @Test fun identicalHyMtPdfMissesShareInferenceButEachBlockIsDurablyCounted() {
    val id = createJob()
    ServiceNativeState.page = { pdfPageWithTexts("Repeated source", "Repeated source", "Repeated source") }
    var requestCount = 0
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requests = JSONArray(requestsJson)
      requestCount += requests.length()
      val request = requests.getJSONObject(0)
      val requestId = request.getString("id")
      val ack = callback.onCompleted(batchPayload(requestId, generation, "Повторный перевод"))
      batchSummary(if (ack == 1) JSONArray().put(requestId) else JSONArray(), if (ack == 1) JSONArray() else JSONArray().put(requestId), canceled = ack != 1)
    }

    start(id)

    assertEquals("completed", status(id))
    assertEquals(1, requestCount)
    assertEquals(3, job(id).getInt("completedBlocks"))
    for (index in 0 until 3) assertEquals("Повторный перевод", database.pageBlockArray(id, 0).getJSONObject(index).getString("translatedText"))
  }

  @Test fun hyMtBatchMemoHitAfterTheFirstBatchIsCheckpointedWithoutSecondInference() {
    val id = createJob()
    ServiceNativeState.page = { pdfPageWithTexts("Source alpha", "Source beta", "Source gamma", "Source delta", "Source alpha") }
    val nativeRequests = mutableListOf<String>()
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requests = JSONArray(requestsJson)
      val committed = JSONArray()
      for (index in 0 until requests.length()) {
        val request = requests.getJSONObject(index)
        nativeRequests += request.getString("text")
        val requestId = request.getString("id")
        assertEquals(1, callback.onCompleted(batchPayload(requestId, generation, "Перевод ${request.getString("text").removePrefix("Source ")}")))
        committed.put(requestId)
      }
      batchSummary(committed)
    }

    start(id)

    assertEquals("completed", status(id))
    assertEquals("the repeated source in the following batch is served from the worker-local memo", listOf(
      "Source alpha", "Source beta", "Source gamma", "Source delta",
    ), nativeRequests)
    assertEquals(1, ShadowNativePdfCore.batchTranslateCalls)
    assertEquals(5, job(id).getInt("completedBlocks"))
    val blocks = database.pageBlockArray(id, 0)
    assertEquals("Перевод alpha", blocks.getJSONObject(0).getString("translatedText"))
    assertEquals("Перевод alpha", blocks.getJSONObject(4).getString("translatedText"))
    assertTrue(blocks.getJSONObject(4).getBoolean("cacheHit"))
    assertEquals("translated", blocks.getJSONObject(4).getString("status"))
  }

  @Test fun pauseDuringBatchCompletionCommitsTheEosCheckpointThenReturnsStopAck() {
    val id = createJob()
    ServiceNativeState.page = { pdfPageWithTexts("A completed sentence", "A pending sentence") }
    var ack = -1
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requests = JSONArray(requestsJson)
      val first = requests.getJSONObject(0)
      val firstId = first.getString("id")
      service.onStartCommand(intent(PdfTranslationService.ACTION_PAUSE, id), 0, 100 + sequence)
      ack = callback.onCompleted(batchPayload(firstId, generation, "Завершённая строка"))
      batchSummary(JSONArray().put(firstId), JSONArray().put(requests.getJSONObject(1).getString("id")), canceled = true)
    }

    start(id)

    assertEquals("paused", status(id))
    assertEquals(2, ack)
    val page = database.page(id, 0)!!
    assertEquals(1, job(id).getInt("completedPageBlocks"))
    assertEquals("translated", page.getJSONArray("blocks").getJSONObject(0).getString("status"))
    assertEquals("pending", page.getJSONArray("blocks").getJSONObject(1).getString("status"))
  }

  @Test fun cancelBeforeBatchCompletionRejectsTheOutputAndKeepsTheBlockPending() {
    val id = createJob()
    ServiceNativeState.page = { pdfPageWithTexts("A cancelable sentence") }
    var ack = -1
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
      service.onStartCommand(intent(PdfTranslationService.ACTION_CANCEL, id), 0, 200 + sequence)
      ack = callback.onCompleted(batchPayload(requestId, generation, "Не должно сохраниться"))
      batchSummary(JSONArray(), JSONArray().put(requestId), canceled = true)
    }

    start(id)

    assertEquals("canceled", status(id))
    assertEquals(0, ack)
    val page = database.page(id, 0)!!
    assertEquals(0, savedCompletedBlockCount(id, 0))
    assertEquals("pending", page.getJSONArray("blocks").getJSONObject(0).getString("status"))
  }

  @Test fun batchCompletionRejectsUnknownStaleAndDuplicateDeliveriesButAcceptsTheCurrentOwnerOnce() {
    val id = createJob()
    ServiceNativeState.page = { pdfPageWithTexts("One source sentence") }
    val acknowledgments = mutableListOf<Int>()
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
      acknowledgments += callback.onCompleted(batchPayload("unknown", generation))
      acknowledgments += callback.onCompleted(batchPayload(requestId, generation + 1, "Stale"))
      val valid = batchPayload(requestId, generation, "Current translation")
      acknowledgments += callback.onCompleted(valid)
      acknowledgments += callback.onCompleted(valid)
      batchSummary(JSONArray().put(requestId))
    }

    start(id)

    assertEquals("completed", status(id))
    assertEquals(listOf(0, 0, 1, 0), acknowledgments)
    assertEquals(1, job(id).getInt("completedBlocks"))
    assertEquals("Current translation", database.pageBlockArray(id, 0).getJSONObject(0).getString("translatedText"))
  }

  @Test fun docxKeepsTheLegacySoloNativeModelPath() {
    val (id, source) = createDocxJob()
    ShadowNativePdfCore.readDocxCallback = { _, index -> docxSegment(index, "A Word source") }
    ShadowNativePdfCore.exportDocxCallback = { _, _, destination ->
      File(destination).writeText("translated Word")
      """{"partial":false,"warnings":[]}"""
    }

    start(id)

    assertEquals("completed", status(id))
    assertEquals(0, ShadowNativePdfCore.batchLoadCalls)
    assertEquals(0, ShadowNativePdfCore.batchTranslateCalls)
    assertEquals(1, ServiceNativeState.loadCalls)
    source.delete()
  }

  @Test fun checkpointDatabaseFailurePropagatesAcrossBatchSinkWithoutAcknowledgingTheOutput() {
    val id = createJob()
    ServiceNativeState.page = { pdfPageWithTexts("Checkpoint must be durable") }
    database.writableDatabase.execSQL("CREATE TRIGGER reject_batch_checkpoint BEFORE INSERT ON pages WHEN NEW.job_id='$id' AND NEW.completed_blocks > 0 BEGIN SELECT RAISE(ABORT, 'injected checkpoint failure'); END")
    var callbackFailure: Throwable? = null
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
      try {
        callback.onCompleted(batchPayload(requestId, generation, "Not committed"))
      } catch (failure: Throwable) {
        callbackFailure = failure
        throw failure
      }
      error("the failing checkpoint must not be acknowledged")
    }

    start(id)

    assertNotNull(callbackFailure)
    assertTrue(callbackFailure?.message.orEmpty().contains("injected checkpoint failure"))
    assertEquals("failed", status(id))
    assertEquals(0, job(id).getInt("completedBlocks"))
    assertEquals("pending", database.pageBlockArray(id, 0).getJSONObject(0).getString("status"))
  }

  @Test fun batchAllocationFailureLeavesRequestPendingAndPausesWithoutCountingItComplete() {
    val id = createJob()
    ServiceNativeState.page = { pdfPageWithTexts("Keep this full source available for resume") }
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, _, _ ->
      val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
      batchSummary(JSONArray(), errors = JSONArray().put(
        JSONObject().put("id", requestId).put("error", "Android could not allocate working memory."),
      ))
    }

    start(id)

    assertEquals("paused", status(id))
    assertEquals(0, job(id).getInt("completedBlocks"))
    val savedBlock = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("pending", savedBlock.getString("status"))
    assertEquals("Keep this full source available for resume", savedBlock.getString("sourceText"))
  }

  @Test fun batchSummaryMissingRequiredCanceledStateFailsClosedAndRetainsPendingSource() {
    val id = createJob()
    ServiceNativeState.page = { pdfPageWithTexts("Retain source when native accounting is malformed") }
    ShadowNativePdfCore.batchTranslateCallback = { _, _, _, _, _, _, _ ->
      JSONObject().put("committedIds", JSONArray()).put("pendingIds", JSONArray())
        .put("finalErrors", JSONArray()).toString()
    }

    start(id)

    assertEquals("failed", status(id))
    assertTrue(error(id).contains("missing canceled state"))
    val block = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("pending", block.getString("status"))
    assertEquals("Retain source when native accounting is malformed", block.getString("sourceText"))
  }

  @Test fun batchSummaryRejectsMissingArraysAndInvalidDuplicateOrUnknownOutcomeIds() {
    data class InvalidSummary(
      val label: String,
      val expectedError: String,
      val mutate: (JSONObject, String) -> Unit,
    )
    val cases = listOf(
      InvalidSummary("missing committed", "missing committedIds") { summary, _ -> summary.remove("committedIds") },
      InvalidSummary("missing pending", "missing pendingIds") { summary, _ -> summary.remove("pendingIds") },
      InvalidSummary("missing errors", "missing finalErrors") { summary, _ -> summary.remove("finalErrors") },
      InvalidSummary("blank committed", "invalid or duplicate committedIds") { summary, _ ->
        summary.put("committedIds", JSONArray().put(" \t"))
      },
      InvalidSummary("blank pending", "invalid or duplicate pendingIds") { summary, _ ->
        summary.put("pendingIds", JSONArray().put(""))
      },
      InvalidSummary("duplicate committed", "invalid or duplicate committedIds") { summary, requestId ->
        summary.put("committedIds", JSONArray().put(requestId).put(requestId))
      },
      InvalidSummary("duplicate pending", "invalid or duplicate pendingIds") { summary, requestId ->
        summary.put("pendingIds", JSONArray().put(requestId).put(requestId))
      },
      InvalidSummary("unknown committed", "omitted one or more requests") { summary, _ ->
        summary.put("committedIds", JSONArray().put("unknown-native-request"))
      },
      InvalidSummary("unknown pending", "omitted one or more requests") { summary, _ ->
        summary.put("pendingIds", JSONArray().put("unknown-native-request"))
      },
      InvalidSummary("omitted request", "omitted one or more requests") { summary, _ ->
        summary.put("pendingIds", JSONArray())
      },
    )
    for (case in cases) {
      val id = createJob()
      val sourceText = "Complete source retained for ${case.label}"
      ServiceNativeState.page = { pdfPageWithTexts(sourceText) }
      var batchCalls = 0
      ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, _, _ ->
        batchCalls++
        val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
        JSONObject(batchSummary(JSONArray(), JSONArray().put(requestId))).apply {
          case.mutate(this, requestId)
        }.toString()
      }

      start(id)

      assertEquals(case.label, "failed", status(id))
      assertTrue("${case.label}: ${error(id)}", error(id).contains(case.expectedError))
      assertEquals(case.label, 1, batchCalls)
      assertEquals(case.label, 0, job(id).getInt("completedBlocks"))
      assertEquals(case.label, 0, job(id).getInt("completedPages"))
      val saved = database.pageBlockArray(id, 0).getJSONObject(0)
      assertEquals(case.label, sourceText, saved.getString("sourceText"))
      assertEquals(case.label, "pending", saved.getString("status"))
      assertTrue(case.label, saved.isNull("translatedText"))
      assertTrue(case.label, File(context.filesDir, "$id.pdf").isFile)
    }
    database.readableDatabase.rawQuery("SELECT COUNT(*) FROM exports", null).use {
      assertTrue(it.moveToFirst())
      assertEquals(0, it.getInt(0))
    }
  }

  @Test fun batchSummaryRejectsUnknownDuplicateFinalErrorsAndOverlappingOutcomePartitions() {
    data class InvalidSummary(
      val label: String,
      val expectedError: String,
      val build: (String) -> String,
    )
    fun finalError(requestId: String) = JSONObject().put("id", requestId).put("error", "Native translation was not produced.")
    val cases = listOf(
      InvalidSummary("unknown final error", "unknown or duplicate final error") { requestId ->
        batchSummary(JSONArray(), JSONArray().put(requestId), JSONArray().put(finalError("unknown-native-request")))
      },
      InvalidSummary("duplicate final error", "unknown or duplicate final error") { requestId ->
        batchSummary(JSONArray(), errors = JSONArray().put(finalError(requestId)).put(finalError(requestId)))
      },
      InvalidSummary("committed and pending", "overlapping request outcomes") { requestId ->
        batchSummary(JSONArray().put(requestId), JSONArray().put(requestId))
      },
      InvalidSummary("committed and error", "overlapping request outcomes") { requestId ->
        batchSummary(JSONArray().put(requestId), errors = JSONArray().put(finalError(requestId)))
      },
      InvalidSummary("pending and error", "overlapping request outcomes") { requestId ->
        batchSummary(JSONArray(), JSONArray().put(requestId), JSONArray().put(finalError(requestId)))
      },
    )
    for (case in cases) {
      val id = createJob()
      val sourceText = "Complete source retained for ${case.label}"
      ServiceNativeState.page = { pdfPageWithTexts(sourceText) }
      ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, _, _ ->
        case.build(JSONArray(requestsJson).getJSONObject(0).getString("id"))
      }

      start(id)

      assertEquals(case.label, "failed", status(id))
      assertTrue("${case.label}: ${error(id)}", error(id).contains(case.expectedError))
      assertEquals(case.label, 0, job(id).getInt("completedBlocks"))
      val saved = database.pageBlockArray(id, 0).getJSONObject(0)
      assertEquals(case.label, sourceText, saved.getString("sourceText"))
      assertEquals(case.label, "pending", saved.getString("status"))
      assertTrue(case.label, saved.isNull("translatedText"))
    }
    database.readableDatabase.rawQuery("SELECT COUNT(*) FROM exports", null).use {
      assertTrue(it.moveToFirst())
      assertEquals(0, it.getInt(0))
    }
  }

  @Test fun batchCommitSummaryMustMatchDurableCallbacksAndCannotEraseAnAcknowledgedCheckpoint() {
    for (acknowledge in listOf(false, true)) {
      val id = createJob()
      val sourceText = "Full source survives a commit summary disagreement"
      ServiceNativeState.page = { pdfPageWithTexts(sourceText) }
      val acknowledgments = mutableListOf<Int>()
      ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
        val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
        if (acknowledge) acknowledgments += callback.onCompleted(batchPayload(requestId, generation, "Durable translation"))
        // Return the opposite accounting: either invent a commit without a callback,
        // or report an already durably acknowledged request as still pending.
        batchSummary(if (acknowledge) JSONArray() else JSONArray().put(requestId),
          if (acknowledge) JSONArray().put(requestId) else JSONArray())
      }

      start(id)

      assertEquals("failed", status(id))
      assertTrue(error(id).contains("did not match durable checkpoints"))
      assertEquals(if (acknowledge) listOf(1) else emptyList<Int>(), acknowledgments)
      assertEquals(if (acknowledge) 1 else 0, job(id).getInt("completedBlocks"))
      assertEquals(0, job(id).getInt("completedPages"))
      val saved = database.pageBlockArray(id, 0).getJSONObject(0)
      assertEquals(sourceText, saved.getString("sourceText"))
      assertEquals(if (acknowledge) "translated" else "pending", saved.getString("status"))
      if (acknowledge) assertEquals("Durable translation", saved.getString("translatedText"))
      else assertTrue(saved.isNull("translatedText"))
      assertTrue(File(context.filesDir, "$id.pdf").isFile)
    }
    database.readableDatabase.rawQuery("SELECT COUNT(*) FROM exports", null).use {
      assertTrue(it.moveToFirst())
      assertEquals(0, it.getInt(0))
    }
  }

  @Test fun blankBatchCompletionReturnsRejectAckAndCannotBeReportedAsCommitted() {
    for (claimCommitted in listOf(false, true)) {
      val id = createJob()
      val sourceText = "A rejected empty answer must remain retryable"
      ServiceNativeState.page = { pdfPageWithTexts(sourceText) }
      val acknowledgments = mutableListOf<Int>()
      ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
        val requestId = JSONArray(requestsJson).getJSONObject(0).getString("id")
        acknowledgments += callback.onCompleted(batchPayload(requestId, generation, " \n\t"))
        batchSummary(if (claimCommitted) JSONArray().put(requestId) else JSONArray(),
          if (claimCommitted) JSONArray() else JSONArray().put(requestId))
      }

      start(id)

      assertEquals(listOf(0), acknowledgments)
      assertEquals(if (claimCommitted) "failed" else "paused", status(id))
      if (claimCommitted) assertTrue(error(id).contains("did not match durable checkpoints"))
      else assertTrue(error(id).contains("Resume to retry the remaining blocks"))
      assertEquals(0, job(id).getInt("completedBlocks"))
      assertEquals(0, job(id).getInt("completedPages"))
      val saved = database.pageBlockArray(id, 0).getJSONObject(0)
      assertEquals(sourceText, saved.getString("sourceText"))
      assertEquals("pending", saved.getString("status"))
      assertTrue(saved.isNull("translatedText"))
      assertNull(PdfTranslationService.activeJobId)
    }
    database.readableDatabase.rawQuery("SELECT COUNT(*) FROM exports", null).use {
      assertTrue(it.moveToFirst())
      assertEquals(0, it.getInt(0))
    }
  }

  @Test fun capturedCompletionFromOldGenerationCannotMutateResumedSameJob() {
    val id = createJob()
    ServiceNativeState.page = { pdfPageWithTexts("First stable source", "Second stable source") }
    var invocation = 0
    var oldCallback: BatchCompletion? = null
    var oldGeneration = 0L
    var oldAck = -1
    var releaseCountAtOldDelivery = -1
    var observedMetricsAtOldDelivery: Pair<Long, Long>? = null
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requests = JSONArray(requestsJson)
      val ids = (0 until requests.length()).map { requests.getJSONObject(it).getString("id") }
      invocation++
      if (invocation == 1) {
        oldCallback = callback
        oldGeneration = generation
        batchSummary(JSONArray(), JSONArray().apply { ids.forEach { put(it) } }, canceled = true)
      } else {
        releaseCountAtOldDelivery = ServiceNativeState.releaseCalls
        observedMetricsAtOldDelivery = observedBlockMetrics(id)
        oldAck = oldCallback!!.onCompleted(batchPayload(ids.first(), oldGeneration, "Stale old output"))
        assertEquals(releaseCountAtOldDelivery, ServiceNativeState.releaseCalls)
        assertEquals(observedMetricsAtOldDelivery, observedBlockMetrics(id))
        val committed = JSONArray()
        for (requestIndex in 0 until requests.length()) {
          val requestId = requests.getJSONObject(requestIndex).getString("id")
          val translatedText = if (requestIndex == 0) "Current first output" else "Current second output"
          assertEquals(1, callback.onCompleted(batchPayload(requestId, generation, translatedText)))
          committed.put(requestId)
        }
        batchSummary(committed)
      }
    }

    start(id)
    assertEquals("paused", status(id))
    val releaseCountAfterOldCleanup = ServiceNativeState.releaseCalls
    database.updateJob(id, status = "queued", stage = "queued", clearError = true)
    start(id)

    assertEquals("completed", status(id))
    assertEquals(0, oldAck)
    assertEquals(releaseCountAfterOldCleanup, releaseCountAtOldDelivery)
    assertEquals(0L to 0L, observedMetricsAtOldDelivery)
    assertEquals(2, invocation)
    assertEquals(2, observedBlockMetrics(id).second)
    val saved = database.pageBlockArray(id, 0)
    assertEquals("Current first output", saved.getJSONObject(0).getString("translatedText"))
    assertEquals("Current second output", saved.getJSONObject(1).getString("translatedText"))
  }

  @Test fun batchDurationUsesActualParallelWallTimeAndOnlyCommittedBlocks() {
    val id = createJob()
    ServiceNativeState.page = { pdfPageWithTexts("Parallel lane one", "Parallel lane two") }
    var now = 100L
    service.workerElapsedRealtime = { now }
    ShadowNativePdfCore.batchTranslateCallback = { requestsJson, _, _, _, _, generation, callback ->
      val requests = JSONArray(requestsJson)
      val committed = JSONArray()
      for (index in 0 until requests.length()) {
        val requestId = requests.getJSONObject(index).getString("id")
        assertEquals(1, callback.onCompleted(batchPayload(requestId, generation, "Translated").let {
          JSONObject(it).put("seconds", 90.0).toString()
        }))
        committed.put(requestId)
      }
      now = 150L
      batchSummary(committed)
    }

    start(id)

    assertEquals("completed", status(id))
    assertEquals(50L to 2L, observedBlockMetrics(id))
  }

  @Test fun explicitCancelWinsOverDocxResourceAndAllocationCallbacks() {
    for(boundary in listOf("memory","load","translate")) {
      val (id,source)=createDocxJob()
      service.memoryInfoProvider={ActivityManager.MemoryInfo().apply {
        if(boundary=="memory") {
          service.onStartCommand(intent(PdfTranslationService.ACTION_CANCEL,id),0,1000+sequence)
          lowMemory=true
        }
      }}
      ShadowNativePdfCore.readDocxCallback={_,index->docxSegment(index,"Source remains")}
      ShadowNativePdfCore.loadCallback={
        if(boundary=="load") {
          service.onStartCommand(intent(PdfTranslationService.ACTION_CANCEL,id),0,1000+sequence)
          """{"error":"std::bad_alloc"}"""
        } else "{}"
      }
      ServiceNativeState.translation={
        service.onStartCommand(intent(PdfTranslationService.ACTION_CANCEL,id),0,1000+sequence)
        """{"error":"std::bad_alloc"}"""
      }
      start(id)
      assertEquals("Cancellation must win at $boundary","canceled",status(id))
      assertEquals("Canceled by the user.",error(id))
      assertNull(database.exportInfo(id));assertTrue(source.isFile)
    }
  }

  @Test fun explicitCancelDuringDocxExportCannotCommitACompletedJobOrExportMetadata() {
    val (id,source)=createDocxJob()
    ShadowNativePdfCore.readDocxCallback={_,index->docxSegment(index,"Source remains")}
    ShadowNativePdfCore.exportDocxCallback={_,_,output->
      File(output).writeText("private translated result")
      service.onStartCommand(intent(PdfTranslationService.ACTION_CANCEL,id),0,1000+sequence)
      """{"partial":false,"warnings":[]}"""
    }
    start(id)
    assertEquals("canceled",status(id))
    assertEquals("Canceled by the user.",error(id))
    assertNull(database.exportInfo(id));assertTrue(source.isFile)
    assertFalse(File(context.filesDir,"translated-docx/$id.docx").exists())
  }

  @Test fun failedDocxMetadataCommitRemovesTheNewFileAndKeepsCompletedCheckpoints() {
    val (id,source)=createDocxJob()
    ShadowNativePdfCore.readDocxCallback={_,index->docxSegment(index,"Source remains")}
    ShadowNativePdfCore.exportDocxCallback={_,_,output->
      File(output).writeText("private translated result")
      """{"partial":false,"warnings":[]}"""
    }
    // A real SQLite insertion fault must roll back status and publish no metadata.
    database.writableDatabase.execSQL("CREATE TRIGGER reject_final_export BEFORE INSERT ON exports BEGIN SELECT RAISE(IGNORE); END")
    try {
      start(id)
      assertEquals("failed",status(id))
      assertTrue(error(id).contains("export could not be saved"))
      assertNull(database.exportInfo(id))
      assertFalse(File(context.filesDir,"translated-docx/$id.docx").exists())
      assertEquals("completed",database.page(id,0)!!.getString("status"))
      assertTrue(source.isFile)
    } finally {database.writableDatabase.execSQL("DROP TRIGGER reject_final_export")}
  }

  @Test fun failedDocxRewriteDoesNotDeleteAnAlreadyPublishedResult() {
    val (id,source)=createDocxJob()
    val output=File(context.filesDir,"translated-docx/$id.docx").apply {parentFile?.mkdirs();writeText("prior published result")}
    database.saveExport(id,output.absolutePath,"prior.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ShadowNativePdfCore.readDocxCallback={_,index->docxSegment(index,"Source remains")}
    ShadowNativePdfCore.exportDocxCallback={_,_,_->throw java.io.IOException("Writer failed before replacing the published result")}
    start(id)
    assertEquals("failed",status(id))
    assertEquals("prior published result",output.readText())
    assertEquals(output.absolutePath,database.exportInfo(id)!!.path)
    assertTrue(source.isFile)
  }

  @Test fun failedPdfMetadataCommitRemovesTheNewOutputAndRetriesSavedPagesWithoutInference() {
    val id = createJob()
    val source = File(context.filesDir, "$id.pdf")
    val sourceBytes = source.readBytes()
    val exportDirectory = File(context.filesDir, "exports")
    val existingFiles = exportDirectory.listFiles().orEmpty().map { it.absolutePath }.toSet()
    var translations = 0
    var merges = 0
    ServiceNativeState.translation = { translations++; "{\"text\":\"Сохранённый перевод\"}" }
    ShadowNativePdfCore.mergeCallback = { request, destination ->
      merges++
      ServiceNativeState.merge(request, destination)
    }
    database.writableDatabase.execSQL("CREATE TRIGGER reject_final_pdf_export BEFORE INSERT ON exports BEGIN SELECT RAISE(IGNORE); END")
    try {
      start(id)

      assertEquals("failed", status(id))
      assertTrue(error(id).contains("export could not be saved"))
      assertEquals(1, merges)
      assertEquals(1, translations)
      assertEquals("completed", database.page(id, 0)!!.getString("status"))
      assertEquals("Сохранённый перевод", database.pageBlockArray(id, 0).getJSONObject(0).getString("translatedText"))
      database.readableDatabase.rawQuery("SELECT COUNT(*) FROM exports", null).use {
        assertTrue(it.moveToFirst())
        assertEquals(0, it.getInt(0))
      }
      assertEquals(existingFiles, exportDirectory.listFiles().orEmpty().map { it.absolutePath }.toSet())
      assertTrue(sourceBytes.contentEquals(source.readBytes()))
    } finally {
      database.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_final_pdf_export")
    }

    val readsBeforeRetry = ServiceNativeState.readCalls
    database.updateJob(id, status = "queued", stage = "queued", clearError = true)
    start(id)

    assertEquals("completed", status(id))
    assertEquals(2, merges)
    assertEquals("saved page must not be translated again after metadata failure", 1, translations)
    assertEquals(readsBeforeRetry, ServiceNativeState.readCalls)
    database.readableDatabase.rawQuery("SELECT path,mime_type FROM exports", null).use {
      assertTrue(it.moveToFirst())
      assertTrue(File(it.getString(0)).isFile)
      assertEquals("application/pdf", it.getString(1))
      assertFalse(it.moveToNext())
    }
    assertTrue(sourceBytes.contentEquals(source.readBytes()))
  }

  @Test fun cancelDuringFinalPdfVerificationRemovesOnlyTheNewUnpublishedResult() {
    val id=createJob()
    ShadowNativePdfCore.inspectCallback={path->
      val inspected = File(path)
      if(inspected.name == "translated.pdf" && inspected.parentFile?.parentFile == File(context.filesDir,"exports")) {
        service.onStartCommand(intent(PdfTranslationService.ACTION_CANCEL,id),0,1000+sequence)
      }
      """{"pageCount":1}"""
    }
    start(id)
    assertEquals("canceled",status(id))
    assertTrue(File(context.filesDir,"exports").listFiles().orEmpty().isEmpty())
    assertTrue(File(database.jobDocumentPath(id)!!).isFile)
  }

  @Test fun fullyCheckpointedDocxCanFinishExportWithoutModelWeightsOrInferenceMemory() {
    val (id,source)=createDocxJob()
    val block=JSONObject().put("id","segment-0").put("sourceText","Saved source")
      .put("translatedText","Saved translation").put("status","translated")
    database.savePage(id,0,"completed",JSONArray().put(block).toString(),expectedBlocks=1)
    database.updateJob(id,completedPages=1)
    assertTrue(downloader.fileFor(ModelCatalog.RECOMMENDED_ID).delete())
    service.memoryInfoProvider={ActivityManager.MemoryInfo().apply {lowMemory=true}}
    ShadowNativePdfCore.readDocxCallback={_,_->error("A completed segment must be reused")}
    ShadowNativePdfCore.exportDocxCallback={_,blocks,output->
      assertEquals("Saved translation",JSONArray(blocks).getJSONObject(0).getString("translatedText"))
      File(output).writeText("private translated result")
      """{"partial":false,"warnings":[]}"""
    }
    start(id)
    assertEquals("completed",status(id))
    assertEquals(0,ServiceNativeState.loadCalls)
    assertNotNull(database.exportInfo(id));assertTrue(source.isFile)
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
  private fun savedCompletedBlockCount(id: String, pageIndex: Int): Int = database.readableDatabase.rawQuery(
    "SELECT completed_blocks FROM pages WHERE job_id=? AND page_index=?", arrayOf(id, pageIndex.toString()),
  ).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
  private fun observedBlockMetrics(id: String): Pair<Long, Long> = database.readableDatabase.rawQuery(
    "SELECT observed_block_ms, observed_block_count FROM jobs WHERE id=?", arrayOf(id),
  ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) to cursor.getLong(1) }
  private fun awaitStatus(id: String, expected: String) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (status(id) != expected && System.nanoTime() < deadline) Thread.sleep(5)
    assertEquals(expected, status(id))
  }
  private fun awaitWorkerReleased(id: String) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (PdfTranslationService.activeJobId == id && System.nanoTime() < deadline) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(5)
    }
    assertFalse("the worker must release its generation after service destruction", PdfTranslationService.activeJobId == id)
  }
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
  private fun invokeWithArtwork(
    native: JSONArray,
    bitmap: Bitmap,
    width: Float,
    height: Float,
    ocr: OcrEngine,
    artwork: JSONArray,
  ): JSONArray {
    val method = PdfTranslationService::class.java.declaredMethods.single { it.name == "combineNativeAndOcrWithArtwork" }
    method.isAccessible = true
    try { return method.invoke(service, native, bitmap, width, height, ocr, artwork) as JSONArray }
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

  @Test fun balancedPdfUsesSoloInferenceAndPreservesItsCheckpointAndExport() {
    useBalancedPdfModel()
    completedTranslationPersistsCheckpointsAndFinalPdf()
    assertEquals(0, ShadowNativePdfCore.batchLoadCalls)
    assertEquals(0, ShadowNativePdfCore.batchTranslateCalls)
    assertEquals(1, ServiceNativeState.loadCalls)
  }

  @Test fun balancedPdfLowMemoryAfterOneBlockRetainsCheckpointAndResumesOnlyThePendingBlock() {
    useBalancedPdfModel()
    val id = createJob()
    val source = File(context.filesDir, "$id.pdf")
    val sourceBytes = source.readBytes()
    ServiceNativeState.page = { pdfPageWithTexts("First stable sentence", "Second stable sentence") }
    var memoryPressure = false
    var translations = 0
    var merges = 0
    service.memoryInfoProvider = { ActivityManager.MemoryInfo().apply { lowMemory = memoryPressure } }
    ServiceNativeState.translation = {
      translations++
      memoryPressure = true
      JSONObject().put("text", if (translations == 1) "Первый перевод" else "Второй перевод").toString()
    }
    ShadowNativePdfCore.mergeCallback = { request, destination ->
      merges++
      ServiceNativeState.merge(request, destination)
    }

    start(id)

    assertEquals("paused", status(id))
    assertTrue(error(id).contains("low memory"))
    assertEquals(1, translations)
    assertEquals(0, merges)
    assertEquals(1, job(id).getInt("completedBlocks"))
    assertEquals(0, job(id).getInt("completedPages"))
    val paused = database.pageBlockArray(id, 0)
    assertEquals("translated", paused.getJSONObject(0).getString("status"))
    assertEquals("Первый перевод", paused.getJSONObject(0).getString("translatedText"))
    assertEquals("pending", paused.getJSONObject(1).getString("status"))
    assertEquals("Second stable sentence", paused.getJSONObject(1).getString("sourceText"))
    database.readableDatabase.rawQuery("SELECT COUNT(*) FROM exports", null).use {
      assertTrue(it.moveToFirst())
      assertEquals(0, it.getInt(0))
    }
    assertNull(PdfTranslationService.activeJobId)
    assertTrue(sourceBytes.contentEquals(source.readBytes()))

    memoryPressure = false
    database.updateJob(id, status = "queued", stage = "queued", clearError = true)
    start(id)

    assertEquals("completed", status(id))
    assertEquals("only the pending second block may be inferred after Resume", 2, translations)
    assertEquals(1, merges)
    assertEquals(2, job(id).getInt("completedBlocks"))
    val completed = database.pageBlockArray(id, 0)
    assertEquals("Первый перевод", completed.getJSONObject(0).getString("translatedText"))
    assertEquals("Второй перевод", completed.getJSONObject(1).getString("translatedText"))
    assertEquals("translated", completed.getJSONObject(1).getString("status"))
    assertEquals(0, ShadowNativePdfCore.batchTranslateCalls)
    assertTrue(sourceBytes.contentEquals(source.readBytes()))
  }

  @Test fun balancedPdfAllocationFailuresKeepTheFirstUnfinishedBlockRetryable() {
    useBalancedPdfModel()
    for (mode in 0..4) {
      val id = createJob()
      ServiceNativeState.translation = {
        when (mode) {
          0 -> "{\"error\":\"out of memory\"}"
          1 -> throw IllegalStateException("cannot allocate")
          2 -> throw OutOfMemoryError()
          3 -> throw IllegalStateException("allocation failed")
          else -> "{\"error\":\"could not allocate working memory\"}"
        }
      }
      start(id)
      assertEquals("paused", status(id))
      assertEquals("pending", database.pageBlockArray(id, 0).getJSONObject(0).getString("status"))
      assertEquals(0, job(id).getInt("completedBlocks"))
      assertEquals(0, job(id).getInt("completedPages"))
      var retried = 0
      ServiceNativeState.translation = { retried++; "{\"text\":\"Строка\"}" }
      // Match resumeJob's durable transition before the next service command.
      database.updateJob(id, status = "queued", stage = "queued", clearError = true)
      start(id)
      assertEquals("the resource-failed block must be inferred after Resume", 1, retried)
      assertEquals("completed", status(id))
      assertEquals("translated", database.pageBlockArray(id, 0).getJSONObject(0).getString("status"))
      assertEquals("Строка", database.pageBlockArray(id, 0).getJSONObject(0).getString("translatedText"))
    }
  }

  @Test fun balancedPdfInvalidOutputsAndInterruptionsRetainTheSameReviewAndPauseContracts() {
    useBalancedPdfModel()
    failedAndBlankTranslationsRetainOriginalsAsPartial()
    interruptionsPreserveExplicitCancelAndPauseOnlyRunningJobs()
  }

  @Test fun legacyResourceFailureCheckpointRetriesOnlyItsUnfinishedBlockForBothLocalPdfEngines() {
    for (balanced in listOf(false, true)) {
      if (balanced) useBalancedPdfModel()
      val id = createJob()
      ServiceNativeState.page = { pdfPageWithTexts("Completed source", "Pending source") }
      val prior = JSONArray()
        .put(ServiceNativeState.block().put("id", "pdf-block-0").put("sourceText", "Completed source")
          .put("translatedText", "Сохранённый перевод").put("status", "translated"))
        .put(ServiceNativeState.block().put("id", "pdf-block-1").put("sourceText", "Pending source")
          .put("translatedText", JSONObject.NULL).put("status", "failed").put("error", "cannot allocate working memory"))
      database.savePage(id, 0, "running", prior.toString(), 612.0, 792.0, expectedBlocks = 2)
      var translatedRequests = 0
      ShadowNativePdfCore.translateCallback = {
        translatedRequests++
        "{\"text\":\"Новый перевод\"}"
      }
      start(id)
      assertEquals("only the unfinished request is inferred", 1, translatedRequests)
      assertEquals("completed", status(id))
      assertEquals(2, job(id).getInt("completedBlocks"))
      val saved = database.pageBlockArray(id, 0)
      assertEquals("Сохранённый перевод", saved.getJSONObject(0).getString("translatedText"))
      assertEquals("Новый перевод", saved.getJSONObject(1).getString("translatedText"))
      assertEquals("translated", saved.getJSONObject(1).getString("status"))
    }
  }

  @Test fun resumeRetranslatesOnlyBlocksWhoseFreshSourceIdentityOrModelInputChanged() {
    useBalancedPdfModel()
    val id = createJob()
    fun symbol(token: String) = JSONArray().put(JSONObject().put("token", token).put("objectIndex", 5))
    fun block(blockId: String, source: String, model: String, indices: List<Int>, symbols: JSONArray = JSONArray(),
              translated: String? = null): JSONObject {
      val result = ServiceNativeState.block().put("id", blockId).put("sourceText", source).put("modelText", model)
        .put("objectIndices", JSONArray(indices)).put("symbols", symbols)
        .put("translatedText", translated ?: JSONObject.NULL)
        .put("status", if (translated == null) "pending" else "translated")
      return result
    }

    val freshSpans = JSONArray().put(JSONObject().put("objectIndex", 6).put("characterIndices", JSONArray().put(0).put(1)))
      .put(JSONObject().put("objectIndex", 7).put("characterIndices", JSONArray().put(2).put(3)))
    val staleSpans = JSONArray().put(JSONObject().put("objectIndex", 6).put("characterIndices", JSONArray().put(90)))
      .put(JSONObject().put("objectIndex", 7).put("characterIndices", JSONArray().put(91)))
    val freshRects = JSONArray().put(JSONObject().put("objectIndex", 6).put("text", "Air filter")
      .put("left", 44.0).put("top", 72.0).put("right", 98.0).put("bottom", 84.0))
    val savedRects = JSONArray().put(JSONObject().put("objectIndex", 6).put("text", "Air filter")
      .put("left", 12.0).put("top", 32.0).put("right", 66.0).put("bottom", 44.0))
    val fresh = JSONArray()
      .put(block("p0-o1", "Storage . . . . eightyeight", "Storage eightyeight", listOf(1)))
      .put(block("p0-o2", "Updated source", "Updated source", listOf(2)))
      .put(block("p0-o3", "Location label", "Location label", listOf(3)))
      .put(block("p0-o4", "Button instruction", "Button instruction", listOf(5), symbol("[[FVSYMB]]")))
      .put(block("p0-o5", "Air filter", "Air filter", listOf(6, 7)).put("left", 44.0).put("top", 72.0)
        .put("right", 98.0).put("bottom", 84.0).put("coordinateSpaceVersion", 2).put("partialCrop", true)
        .put("sourceRects", freshRects).put("sourceSpans", freshSpans))
    ServiceNativeState.page = { JSONObject().put("width", 612).put("height", 792).put("blocks", fresh).toString() }
    val saved = JSONArray()
      .put(block("p0-o1", "Storage . . . . eightyeight", "Storage . . . . eightyeight", listOf(1), translated = "Старый TOC"))
      .put(block("p0-o2", "Old source", "Updated source", listOf(2), translated = "Старый источник"))
      .put(block("p0-o3", "Location label", "Location label", listOf(4), translated = "Старое расположение"))
      .put(block("p0-o4", "Button instruction", "Button instruction", listOf(5), symbol("[[FVSYMA]]"), translated = "Старая кнопка"))
      .put(block("p0-o5", "Air filter", "Air filter", listOf(6, 7), translated = "Воздушный фильтр")
        .put("left", 12.0).put("top", 32.0).put("right", 66.0).put("bottom", 44.0)
        .put("coordinateSpaceVersion", 1).put("partialCrop", false).put("sourceRects", savedRects)
        .put("sourceSpans", staleSpans))
    database.savePage(id, 0, "running", saved.toString(), 612.0, 792.0, expectedBlocks = saved.length())
    val requests = mutableListOf<String>()
    ShadowNativePdfCore.translateInputCallback = { text, _, _, _, _ ->
      requests += text
      "{\"text\":\"Перевод\"}"
    }

    start(id)

    assertEquals("only changed model input and source identities are retranslated", listOf(
      "Storage eightyeight", "Updated source", "Location label", "Button instruction",
    ), requests)
    assertEquals("completed", status(id))
    val actual = database.pageBlockArray(id, 0)
    assertEquals("the changed TOC preprocessing must not retain its stale target", "Перевод",
      actual.getJSONObject(0).getString("translatedText"))
    assertEquals("source changes with stable IDs invalidate the prior target", "Перевод",
      actual.getJSONObject(1).getString("translatedText"))
    assertEquals("object ownership changes invalidate the prior target", "Перевод",
      actual.getJSONObject(2).getString("translatedText"))
    assertEquals("token inventory changes invalidate the prior target", "Перевод",
      actual.getJSONObject(3).getString("translatedText"))
    assertEquals("a source-identical checkpoint is preserved", "Воздушный фильтр",
      actual.getJSONObject(4).getString("translatedText"))
    val restored = actual.getJSONObject(4)
    assertEquals("the fresh native coordinates remain authoritative", 44.0, restored.getDouble("left"), 0.0)
    assertEquals(72.0, restored.getDouble("top"), 0.0)
    assertEquals("fresh coordinate-space marker is retained", 2, restored.getInt("coordinateSpaceVersion"))
    assertTrue("fresh crop metadata is retained", restored.getBoolean("partialCrop"))
    assertEquals("fresh sourceRects are retained", 44.0,
      restored.getJSONArray("sourceRects").getJSONObject(0).getDouble("left"), 0.0)
    assertEquals("fresh source character ownership survives durable-result restoration", freshSpans.toString(),
      restored.getJSONArray("sourceSpans").toString())
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
    assertEquals(0, ShadowNativePdfCore.batchLoadCalls)
    assertEquals(0, ShadowNativePdfCore.batchTranslateCalls)
    val savedBlock = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("Перевод", savedBlock.getJSONArray("translatedFragments").getJSONObject(0).getString("translated"))

    service.myMemoryTranslatorFactory = { MyMemoryTranslator { _, _, _ ->
      calls++
      """{"responseStatus":200,"responseData":{"translatedText":"ignored"}}"""
    } }
    // resumeJob moves the durable paused row to queued before service delivery.
    database.updateJob(id,status="queued",stage="queued",clearError=true)
    newWorker()
    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, sequence)
    worker.runNext()
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals("completed", status(id))
    assertEquals(3, calls)
    assertEquals(0, ServiceNativeState.loadCalls)
    assertEquals(0, ShadowNativePdfCore.batchLoadCalls)
    assertEquals(0, ShadowNativePdfCore.batchTranslateCalls)
    assertEquals("Переводignored", database.pageBlockArray(id, 0).getJSONObject(0).getString("translatedText"))
  }

  @Test fun localDocxTranslatesStableSegmentsPreservesWhitespaceAndExportsEditableDocument() {
    val (id, source) = createDocxJob(segments = 3)
    val originalBytes = source.readBytes()
    val sourceSegments = listOf("  First sentence\n", "", "文書の段落")
    val readIndices = mutableListOf<Int>()
    val translatedInputs = mutableListOf<String>()
    var exportedSource = ""
    var exportedBlocks = ""
    var exportedPath = ""
    ShadowNativePdfCore.readDocxCallback = { path, index ->
      assertEquals(source.absolutePath, path)
      readIndices += index
      docxSegment(index, sourceSegments[index], if (index == 2) "Unsupported inline element preserved" else null)
    }
    ServiceNativeState.translation = {
      translatedInputs += "native-translation"
      JSONObject().put("text", "translated-${translatedInputs.size}").toString()
    }
    ShadowNativePdfCore.exportDocxCallback = { input, blocks, output ->
      exportedSource = input
      exportedBlocks = blocks
      exportedPath = output
      File(output).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 9, 8, 7)) }
      JSONObject().put("path", output).put("partial", false).put("warnings", JSONArray()).toString()
    }

    assertEquals("segment", job(id).getString("progressUnit"))
    start(id)

    assertEquals("completed", status(id))
    assertEquals("complete", job(id).getString("stage"))
    assertEquals(3, job(id).getInt("completedPages"))
    assertEquals("segment", job(id).getString("progressUnit"))
    assertEquals(listOf(0, 1, 2), readIndices)
    assertEquals(listOf("native-translation", "native-translation"), translatedInputs)
    assertEquals(originalBytes.toList(), source.readBytes().toList())
    assertEquals(source.absolutePath, exportedSource)
    assertEquals(File(context.filesDir, "translated-docx/$id.docx").absolutePath, exportedPath)
    val writtenBlocks = JSONArray(exportedBlocks)
    assertEquals(3, writtenBlocks.length())
    assertEquals("  First sentence\n", writtenBlocks.getJSONObject(0).getString("sourceText"))
    assertEquals("translated-1", writtenBlocks.getJSONObject(0).getString("translatedText"))
    assertEquals("translated", writtenBlocks.getJSONObject(1).getString("status"))
    assertEquals("", writtenBlocks.getJSONObject(1).getString("translatedText"))
    assertEquals("文書の段落", writtenBlocks.getJSONObject(2).getString("sourceText"))
    assertEquals("Unsupported inline element preserved", database.page(id, 2)?.optString("error"))
    assertEquals(File(context.filesDir, "translated-docx/$id.docx").absolutePath to "source-translated.docx", database.export(id))
    assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", database.exportInfo(id)?.mimeType)
  }

  @Test fun docxReadFailureStopsBeforeExportAndPreservesOriginalAndSavedCheckpoints() {
    val (id, source) = createDocxJob(segments = 2)
    val originalBytes = source.readBytes()
    var exports = 0
    val readIndices = mutableListOf<Int>()
    ShadowNativePdfCore.readDocxCallback = { path, index ->
      assertEquals(source.absolutePath, path)
      readIndices += index
      if (index == 0) docxSegment(index, "Saved paragraph") else JSONObject().put("error", "DOCX segment is corrupt.").toString()
    }
    ShadowNativePdfCore.exportDocxCallback = { _, _, _ -> exports++; error("export must not run after extraction fails") }

    start(id)

    assertEquals("failed", status(id))
    assertEquals("DOCX segment is corrupt.", error(id))
    assertEquals(listOf(0, 1), readIndices)
    assertEquals(0, exports)
    assertEquals("completed", database.page(id, 0)?.getString("status"))
    assertNull(database.page(id, 1))
    assertNull(database.exportInfo(id))
    assertEquals(originalBytes.toList(), source.readBytes().toList())
  }

  @Test fun docxExportWarningsPersistAsNeedsReviewAndExportFailureKeepsSource() {
    val (partialId, partialSource) = createDocxJob()
    val partialOriginal = partialSource.readBytes()
    ShadowNativePdfCore.readDocxCallback = { path, index ->
      assertEquals(partialSource.absolutePath, path)
      docxSegment(index, "Text")
    }
    ShadowNativePdfCore.exportDocxCallback = { _, _, output ->
      File(output).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 4)) }
      JSONObject().put("path", output).put("partial", true)
        .put("warnings", JSONArray().put("Header text was not translatable.").put("null")).toString()
    }
    start(partialId)
    assertEquals("partial", status(partialId))
    assertTrue(job(partialId).getJSONArray("warnings").toString().contains("Header text was not translatable."))
    assertEquals("Header text was not translatable.", database.page(partialId, 0)?.optString("error"))
    assertNotNull(database.exportInfo(partialId))
    assertEquals(partialOriginal.toList(), partialSource.readBytes().toList())

    val (failedId, failedSource) = createDocxJob()
    val failedOriginal = failedSource.readBytes()
    ShadowNativePdfCore.readDocxCallback = { path, index ->
      assertEquals(failedSource.absolutePath, path)
      docxSegment(index, "Second document")
    }
    ShadowNativePdfCore.exportDocxCallback = { _, _, _ -> JSONObject().put("error", "Word package could not be written.").toString() }
    start(failedId)
    assertEquals("failed", status(failedId))
    assertEquals("Word package could not be written.", error(failedId))
    assertNull(database.exportInfo(failedId))
    assertEquals(failedOriginal.toList(), failedSource.readBytes().toList())
  }

  @Test fun docxSegmentWarningsIgnoreBlankAndNullValuesAndDeduplicateRepeatedWarnings() {
    val (id, _) = createDocxJob()
    ShadowNativePdfCore.readDocxCallback = { _, _ ->
      JSONObject().put("width", 612).put("height", 792)
        .put("blocks", JSONArray().put(ServiceNativeState.block()))
        .put("warnings", JSONArray().put("").put("null").put(" Header retained ").put("Header retained"))
        .toString()
    }
    ShadowNativePdfCore.exportDocxCallback = { _, _, destination ->
      File(destination).apply { parentFile?.mkdirs(); writeText("translated Word") }
      """{"partial":false,"warnings":[]}"""
    }
    start(id)
    assertEquals("completed", status(id))
    assertEquals("Header retained", database.page(id, 0)!!.optString("error"))
  }

  @Test fun localDocxPausesBeforeLoadingWhenSystemMemoryOrDiskIsInsufficient() {
    for (mode in 0..1) {
      val (id, source) = createDocxJob()
      val originalBytes = source.readBytes()
      val readIndices = mutableListOf<Int>()
      var exports = 0
      service.memoryInfoProvider = { ActivityManager.MemoryInfo().apply { lowMemory = mode == 0 } }
      service.availableDiskBytes = { if (mode == 1) 0L else Long.MAX_VALUE }
      ShadowNativePdfCore.readDocxCallback = { _, index -> readIndices += index; docxSegment(index, "Resource test") }
      ShadowNativePdfCore.exportDocxCallback = { _, _, _ -> exports++; error("a resource-paused job must not export") }

      start(id)

      assertEquals("paused", status(id))
      assertTrue("resource reason should be visible: ${error(id)}", error(id).contains(if (mode == 0) "memory" else "storage", ignoreCase = true))
      assertEquals(0, ServiceNativeState.loadCalls)
      assertTrue("no segment should be read before resource checks", readIndices.isEmpty())
      assertEquals(0, exports)
      assertNull(database.page(id, 0))
      assertEquals(originalBytes.toList(), source.readBytes().toList())
      service.memoryInfoProvider = { ActivityManager.MemoryInfo().apply { lowMemory = false } }
      service.availableDiskBytes = { Long.MAX_VALUE }
    }
  }

  @Test fun docxMemoryPressureAfterOneSegmentPausesWithItsCheckpointAndSkipsLaterTranslation() {
    val (id, source) = createDocxJob(segments = 2)
    val readIndices = mutableListOf<Int>()
    var memoryPressure = false
    var translations = 0
    var exports = 0
    service.memoryInfoProvider = { ActivityManager.MemoryInfo().apply { lowMemory = memoryPressure } }
    ShadowNativePdfCore.readDocxCallback = { _, index ->
      readIndices += index
      docxSegment(index, "Segment $index")
    }
    ServiceNativeState.translation = {
      translations++
      memoryPressure = true
      JSONObject().put("text", "Translated $translations").toString()
    }
    ShadowNativePdfCore.exportDocxCallback = { _, _, _ -> exports++; error("a paused DOCX job must not export") }

    start(id)

    assertEquals("paused", status(id))
    assertTrue(error(id).contains("memory", ignoreCase = true))
    assertEquals(1, translations)
    assertTrue("the latest completed segment must be checkpointed", readIndices == listOf(0) || readIndices == listOf(0, 1))
    assertEquals("completed", database.page(id, 0)?.getString("status"))
    assertEquals(1, job(id).getInt("completedPages"))
    assertTrue("the next segment must not be translated", database.page(id, 1) == null ||
      database.pageBlockArray(id, 1).getJSONObject(0).optString("status") != "translated")
    assertEquals(0, exports)
    assertEquals(source.absolutePath, database.jobDocumentPath(id))
  }

  @Test fun docxModelLoadAllocationFailurePausesBeforeExtractingSegments() {
    val (id, source) = createDocxJob()
    var reads = 0
    var exports = 0
    ShadowNativePdfCore.loadCallback = {
      ServiceNativeState.loadCalls++
      JSONObject().put("error", "std::bad_alloc").toString()
    }
    ShadowNativePdfCore.readDocxCallback = { _, index -> reads++; docxSegment(index, "Not read") }
    ShadowNativePdfCore.exportDocxCallback = { _, _, _ -> exports++; error("failed model load cannot export") }

    start(id)

    assertEquals("paused", status(id))
    assertTrue(error(id).contains("memory", ignoreCase = true))
    assertEquals(1, ServiceNativeState.loadCalls)
    assertEquals(0, reads)
    assertEquals(0, exports)
    assertNull(database.page(id, 0))
    assertTrue(source.exists())
  }

  @Test fun docxTranslationBadAllocPausesAtSavedCheckpointWithoutExport() {
    val (id, source) = createDocxJob()
    var exports = 0
    ShadowNativePdfCore.readDocxCallback = { _, index -> docxSegment(index, "Recoverable source") }
    ServiceNativeState.translation = { JSONObject().put("error", "std::bad_alloc").toString() }
    ShadowNativePdfCore.exportDocxCallback = { _, _, _ -> exports++; error("allocation failure must not export") }

    start(id)

    assertEquals("paused", status(id))
    assertTrue(error(id).contains("memory", ignoreCase = true))
    assertEquals(0, job(id).getInt("completedPages"))
    val checkpoint = database.page(id, 0) ?: fail("translation allocation failure must retain its segment checkpoint")
    val block = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("Recoverable source", block.getString("sourceText"))
    assertTrue(block.optString("status") == "pending" || block.optString("status") == "failed")
    assertEquals(0, exports)
    assertTrue(source.exists())
    assertNotNull(checkpoint)
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

    database.updateJob(id,status="queued",stage="queued",clearError=true)
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
    database.updateJob(id,status="queued",stage="queued",clearError=true)
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

  @Test fun onlineUnexpectedTransportFailureRetainsOriginalAsReviewablePartial() {
    val id = createOnlinePdfJob()
    service.myMemoryTranslatorFactory = {
      MyMemoryTranslator { _, _, _ -> throw java.io.IOException("connection reset") }
    }
    start(id)
    assertEquals("partial", status(id))
    val block = database.pageBlockArray(id, 0).getJSONObject(0)
    assertEquals("A line", block.getString("sourceText"))
    assertEquals("failed", block.getString("status"))
    assertTrue(block.getString("error").contains("connection reset"))
    assertEquals(0, ServiceNativeState.loadCalls)
  }

  @Test fun onlinePdfOcrSetupFailureKeepsNativeTextAndContinuesWithReviewWarning() {
    for (failure in listOf(IllegalStateException("OCR unavailable"), IllegalStateException())) {
      val id = createOnlinePdfJob()
      service.ocrEngineFactory = { throw failure }
      service.myMemoryTranslatorFactory = { MyMemoryTranslator { _, _, _ ->
        """{"responseStatus":200,"responseData":{"translatedText":"Перевод"}}"""
      } }
      start(id)
      assertEquals("partial", status(id))
      val block = database.pageBlockArray(id, 0).getJSONObject(0)
      assertEquals("A line", block.getString("sourceText"))
      assertEquals("Перевод", block.getString("translatedText"))
      assertTrue(database.page(id, 0)!!.optString("error").contains("OCR"))
    }
  }

  @Test fun onlinePdfRenderAndRecognitionFailuresKeepNativeTextAndRecycleRenderedImages() {
    for (mode in 0..3) {
      val id = createOnlinePdfJob()
      var rendered: Bitmap? = null
      var recognitionCalls = 0
      var requests = 0
      ServiceNativeState.render = {
        if (mode < 2) throw IllegalStateException(if (mode == 0) "render unavailable" else null)
        Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also { rendered = it }
      }
      service.ocrEngineFactory = { language ->
        OcrEngine(service, language, api = ServiceOcrApi(
          failRecognize = mode >= 2,
          nullFailure = mode == 3,
          onRecognize = { recognitionCalls++ },
        ))
      }
      service.myMemoryTranslatorFactory = { MyMemoryTranslator { _, _, _ ->
        requests++
        """{"responseStatus":200,"responseData":{"translatedText":"Перевод"}}"""
      } }

      start(id)

      assertEquals("partial", status(id))
      assertEquals(1, requests)
      val checkpoint = database.page(id, 0)!!
      assertEquals("partial", checkpoint.getString("status"))
      val block = checkpoint.getJSONArray("blocks").getJSONObject(0)
      assertEquals("A line", block.getString("sourceText"))
      assertEquals("Перевод", block.getString("translatedText"))
      assertEquals("translated", block.getString("status"))
      val expectedWarning = when (mode) {
        0 -> "render unavailable"
        1 -> "The PDF page could not be rendered for text recognition."
        2 -> "OCR failed"
        else -> "On-device OCR could not recognize this PDF page."
      }
      assertTrue(checkpoint.getString("error").contains(expectedWarning))
      assertEquals(if (mode < 2) 0 else 1, recognitionCalls)
      if (mode < 2) assertNull(rendered)
      else assertTrue("the bitmap must be recycled after OCR failure", rendered!!.isRecycled)
      assertEquals(0, ServiceNativeState.loadCalls)
      assertEquals(0, ShadowNativePdfCore.batchLoadCalls)
      assertEquals(0, ShadowNativePdfCore.batchTranslateCalls)
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
      assertEquals("pending", database.pageBlockArray(id, 0).getJSONObject(0).getString("status"))
      assertEquals("no uncheckpointed allocation failure may increase durable block progress", 0, job(id).getInt("completedBlocks"))
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
      ServiceNativeState.export = { index, request, destination ->
        when (mode) {
          0 -> "{\"error\":\"layout failed\"}"
          1 -> "{}"
          else -> ServiceNativeState.exportResult(index, request, destination, partial = true,
            issues = JSONArray().put(JSONObject().put("blockId", "b0").put("reason", "layout_fallback")
              .put("message", "review layout")))
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
      awaitStatus(other, if (action == PdfTranslationService.ACTION_PAUSE) "paused" else "canceled")
      assertEquals(first, PdfTranslationService.activeJobId)
      service.onStartCommand(intent(action, first), 0, 13)
      if (action == PdfTranslationService.ACTION_PAUSE) {
        assertEquals("queued", status(first))
        assertTrue(job(first).getBoolean("workerStopping"))
      } else awaitStatus(first, "canceled")
      worker.runNext()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      awaitStatus(first, if (action == PdfTranslationService.ACTION_PAUSE) "paused" else "canceled")
      controller.destroy()
      awaitWorkerReleased(first)
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

  @Test fun pauseKeepsResumeBlockedUntilWorkerHasSavedCheckpointAndReleasedOwnership() {
    val id = createJob()
    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, 40)
    service.onStartCommand(intent(PdfTranslationService.ACTION_PAUSE, id), 0, 41)
    assertTrue(job(id).getBoolean("workerStopping"))
    assertEquals("queued", status(id))

    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, 42)
    assertEquals(1, worker.tasks.size)
    worker.runNext()
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertFalse(job(id).getBoolean("workerStopping"))
    assertEquals("paused", status(id))

    database.updateJob(id, status = "queued", stage = "queued", clearError = true)
    newWorker()
    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, 43)
    worker.runNext()
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertEquals("completed", status(id))
  }

  @Test fun failedPriorityLoweringRunsWorkerAndDoesNotAttemptAnUnownedRestore() {
    val calls = mutableListOf<Int>()
    service.currentWorkerThreadPriority = { 7 }
    service.setWorkerThreadPriority = { priority ->
      calls += priority
      throw SecurityException("priority change denied")
    }

    val ran = invoke("withBackgroundWorkerPriority", ({ "work completed" } as () -> String))

    assertEquals("work completed", ran)
    assertEquals(listOf(android.os.Process.THREAD_PRIORITY_BACKGROUND), calls)
  }

  @Test fun staleCleanupWithMatchingJobIdButOldGenerationCannotClearNewOwner() {
    val ownerField = PdfTranslationService::class.java.getDeclaredField("activeJobId").apply { isAccessible = true }
    val generationField = PdfTranslationService::class.java.getDeclaredField("activeWorkerGeneration").apply { isAccessible = true }
    val stopIdField = PdfTranslationService::class.java.getDeclaredField("stopRequestedJobId").apply { isAccessible = true }
    val stopCanceledField = PdfTranslationService::class.java.getDeclaredField("stopRequestedCanceled").apply { isAccessible = true }
    val previousOwner = ownerField.get(null)
    val previousGeneration = generationField.getLong(null)
    val previousStopId = stopIdField.get(null)
    val previousCanceled = stopCanceledField.getBoolean(null)
    try {
      ownerField.set(null, "same-job")
      generationField.setLong(null, 22L)
      stopIdField.set(null, "same-job")
      stopCanceledField.setBoolean(null, true)

      PdfTranslationService.clearWorkerOwnership("same-job", 21L)

      assertEquals("same-job", ownerField.get(null))
      assertEquals(22L, generationField.getLong(null))
      assertEquals("same-job", stopIdField.get(null))
      assertTrue(stopCanceledField.getBoolean(null))
    } finally {
      ownerField.set(null, previousOwner)
      generationField.setLong(null, previousGeneration)
      stopIdField.set(null, previousStopId)
      stopCanceledField.setBoolean(null, previousCanceled)
    }
  }

  @Test fun destroyingServiceAfterWorkerEntryPausesAfterTheWorkerReleasesItsGeneration() {
    val id = createJob()
    val enteredLoad = CountDownLatch(1)
    val continueLoad = CountDownLatch(1)
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    service.jobExecutorFactory = { executor }
    ShadowNativePdfCore.loadCallback = {
      enteredLoad.countDown()
      continueLoad.await()
      "{}"
    }
    try {
      service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, 48)
      assertTrue("worker did not enter model loading", enteredLoad.await(2, TimeUnit.SECONDS))
      assertEquals(id, PdfTranslationService.activeJobId)

      controller.destroy()

      awaitStatus(id, "paused")
      // finishWorker releases process-wide ownership on the main Handler after
      // its background checkpoint is durable.
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      awaitWorkerReleased(id)
      assertNull(PdfTranslationService.activeJobId)
      assertTrue(error(id).contains("Paused after the latest saved page checkpoint"))
    } finally {
      continueLoad.countDown()
      executor.shutdownNow()
    }
  }

  @Test fun docxExportWarningsAreMergedIntoTheCheckpointWithoutDuplicatingExistingWarnings() {
    val (id, source) = createDocxJob()
    ShadowNativePdfCore.readDocxCallback = { _, index -> docxSegment(index, "A source sentence", "source warning") }
    ShadowNativePdfCore.exportDocxCallback = { _, _, destination ->
      File(destination).apply { parentFile?.mkdirs(); writeText("translated Word output") }
      """{"partial":false,"warnings":["export warning","source warning"," ","null"]}"""
    }

    start(id)

    assertEquals("completed", status(id))
    val checkpoint = database.page(id, 0)!!
    assertEquals("source warning; export warning", checkpoint.getString("error"))
    assertTrue(File(context.filesDir, "translated-docx/$id.docx").isFile)
    source.delete()
  }

  @Test fun dataSyncTimeoutPersistsQueuedJobAsPausedAndReleasesUnenteredWorker() {
    val id = createJob()
    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, 45)
    assertEquals(id, PdfTranslationService.activeJobId)
    assertEquals(1, worker.tasks.size)

    service.onTimeout(45, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    awaitStatus(id, "paused")
    awaitWorkerReleased(id)
    assertTrue(error(id).contains("Paused after the latest saved page checkpoint"))
    assertEquals("timeout must not enter queued inference work", 0, ServiceNativeState.loadCalls)
  }

  @Test fun weightRepackingPolicyRunsBeforePdfAndDocxModelLoadAndNeverGatesLoading() {
    val events = mutableListOf<String>()
    val totalMemory = 4L * 1024 * 1024 * 1024
    service.memoryInfoProvider = { ActivityManager.MemoryInfo().apply { lowMemory = false; totalMem = totalMemory } }
    service.setWeightRepackingAllowed = { allowed -> events += "repacking:$allowed" }
    ShadowNativePdfCore.loadCallback = { events += "load"; ServiceNativeState.load() }

    val pdf = createJob()
    start(pdf)
    assertEquals("completed", status(pdf))
    assertEquals(listOf("repacking:true", "load"), events)

    events.clear()
    ShadowNativePdfCore.readDocxCallback = { _, index -> docxSegment(index, "Word source") }
    ShadowNativePdfCore.exportDocxCallback = { _, _, destination ->
      File(destination).writeText("translated Word")
      """{"partial":false,"warnings":[]}"""
    }
    val (docx, _) = createDocxJob()
    start(docx)
    assertEquals("completed", status(docx))
    assertEquals(listOf("repacking:true", "load"), events)

    events.clear()
    service.memoryInfoProvider = { ActivityManager.MemoryInfo().apply { lowMemory = false; totalMem = 0 } }
    val unknownMemoryPdf = createJob()
    start(unknownMemoryPdf)
    assertEquals("completed", status(unknownMemoryPdf))
    assertEquals(listOf("repacking:false", "load"), events)
  }

  @Test fun translationWorkerRunsAtBackgroundPriorityAndRestoresExecutorThreadAfterSuccessAndFailure() {
    var priority = 7
    val appliedPriorities = mutableListOf<Int>()
    service.currentWorkerThreadPriority = { priority }
    service.setWorkerThreadPriority = { value -> priority = value; appliedPriorities.add(value) }
    ShadowNativePdfCore.loadCallback = {
      assertEquals(android.os.Process.THREAD_PRIORITY_BACKGROUND, priority)
      ServiceNativeState.load()
    }
    ShadowNativePdfCore.readCallback = {
      assertEquals(android.os.Process.THREAD_PRIORITY_BACKGROUND, priority)
      ServiceNativeState.page()
    }

    val successful = createJob()
    start(successful)
    assertEquals("completed", status(successful))
    assertEquals(7, priority)
    assertEquals(listOf(android.os.Process.THREAD_PRIORITY_BACKGROUND, 7), appliedPriorities)

    val failing = createJob()
    ShadowNativePdfCore.loadCallback = {
      assertEquals(android.os.Process.THREAD_PRIORITY_BACKGROUND, priority)
      throw IllegalStateException("injected model load failure")
    }
    start(failing)
    assertEquals("failed", status(failing))
    assertEquals("a reused executor thread must return to its original priority", 7, priority)
    assertEquals(listOf(android.os.Process.THREAD_PRIORITY_BACKGROUND, 7,
      android.os.Process.THREAD_PRIORITY_BACKGROUND, 7), appliedPriorities)

    ShadowNativePdfCore.loadCallback = { ServiceNativeState.load() }
    ShadowNativePdfCore.readCallback = { ServiceNativeState.page() }
    service.setWorkerThreadPriority = { value ->
      if (value == android.os.Process.THREAD_PRIORITY_BACKGROUND) throw SecurityException("priority adjustment unavailable")
      priority = value
    }
    val priorityUnavailable = createJob()
    start(priorityUnavailable)
    assertEquals("priority adjustment failure must not strand a translation", "completed", status(priorityUnavailable))
    assertEquals(7, priority)

    val priorityReadFailure = createJob()
    service.currentWorkerThreadPriority = { throw SecurityException("priority read unavailable") }
    val setterCalls = mutableListOf<Int>()
    service.setWorkerThreadPriority = { setterCalls.add(it) }
    start(priorityReadFailure)
    assertEquals("a failed priority read must leave translation available", "completed", status(priorityReadFailure))
    assertTrue("without a prior priority, the reusable worker must not be changed", setterCalls.isEmpty())
  }

  @Test fun backgroundPriorityRestoreFailureDoesNotFailCompletedTranslation() {
    var priority = 7
    var attempts = 0
    service.currentWorkerThreadPriority = { priority }
    service.setWorkerThreadPriority = { value ->
      attempts++
      if (value == android.os.Process.THREAD_PRIORITY_BACKGROUND) priority = value
      else throw SecurityException("restore unavailable")
    }
    val id = createJob()
    start(id)
    assertEquals("completed", status(id))
    assertEquals(2, attempts)
    assertEquals(android.os.Process.THREAD_PRIORITY_BACKGROUND, priority)
  }

  @Test fun lateCleanupFromAnOlderWorkerCannotClearCurrentWorkerGeneration() {
    val activeId = "generation-current"
    val activeGeneration = 77L
    val activeIdField = PdfTranslationService::class.java.getDeclaredField("activeJobId").apply { isAccessible = true }
    val generationField = PdfTranslationService::class.java.getDeclaredField("activeWorkerGeneration").apply { isAccessible = true }
    val stopIdField = PdfTranslationService::class.java.getDeclaredField("stopRequestedJobId").apply { isAccessible = true }
    val stopCanceledField = PdfTranslationService::class.java.getDeclaredField("stopRequestedCanceled").apply { isAccessible = true }
    val executorField = PdfTranslationService::class.java.getDeclaredField("executor").apply { isAccessible = true }
    val jobField = PdfTranslationService::class.java.getDeclaredField("jobId").apply { isAccessible = true }
    val workerTokenField = PdfTranslationService::class.java.getDeclaredField("workerToken").apply { isAccessible = true }
    val memoField = PdfTranslationService::class.java.getDeclaredField("localTranslations").apply { isAccessible = true }
    val memo = memoField.get(service) as LocalTranslationMemo
    memo.remember("memo source", "", "", "en", "ru", "{\"text\":\"cached translation\"}")
    val currentOwner = ServiceQueuedExecutor()
    activeIdField.set(null, activeId)
    generationField.setLong(null, activeGeneration)
    stopIdField.set(null, activeId)
    stopCanceledField.setBoolean(null, true)
    executorField.set(service, currentOwner)
    jobField.set(service, activeId)
    workerTokenField.setLong(service, activeGeneration)
    val oldOwner = ServiceQueuedExecutor()
    val releasesBeforeStaleCleanup = ServiceNativeState.releaseCalls
    try {
      invoke("cleanupJobWorker", activeId, null, oldOwner, activeGeneration - 1)
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      assertTrue(oldOwner.isShutdown)
      assertFalse(currentOwner.isShutdown)
      assertEquals("a stale cleanup must not release the current native model", releasesBeforeStaleCleanup, ServiceNativeState.releaseCalls)
      assertEquals(activeId, PdfTranslationService.activeJobId)
      assertEquals(activeId, PdfTranslationService.stopRequestedJobId)
      assertTrue(PdfTranslationService.stopRequestedCanceled)
      assertNotNull("stale cleanup must retain the current worker's memo", memo.cached("memo source", "", "", "en", "ru"))
    } finally {
      activeIdField.set(null, null)
      generationField.setLong(null, 0L)
      stopIdField.set(null, null)
      stopCanceledField.setBoolean(null, false)
      executorField.set(service, null)
      jobField.set(service, null)
      workerTokenField.setLong(service, 0L)
      currentOwner.shutdownNow()
    }
  }

  @Test fun nativeCancelFailureDoesNotPreventQueuedPauseFromSavingPausedState() {
    val id = createJob()
    ShadowNativePdfCore.cancelCallback = { throw IllegalStateException("native cancel unavailable") }
    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, 301)
    service.onStartCommand(intent(PdfTranslationService.ACTION_PAUSE, id), 0, 302)
    worker.runNext()
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertEquals("paused", status(id))
    assertTrue(error(id).contains("Paused after the latest saved page checkpoint"))
    assertEquals(0, ServiceNativeState.loadCalls)
  }

  @Test fun pauseDuringOcrRequestsRecognitionStopAndKeepsNativePageCheckpoint() {
    val id = createJob()
    val stoppedOcr = java.util.concurrent.CountDownLatch(1)
    service.ocrEngineFactory = { language ->
      OcrEngine(service, language, api = ServiceOcrApi(
        lines = listOf("A line" to Rect(10, 10, 200, 50)),
        onRecognize = { service.onStartCommand(intent(PdfTranslationService.ACTION_PAUSE, id), 0, 303) },
        onStop = { stoppedOcr.countDown() },
      ))
    }
    start(id)
    assertTrue("the active OCR engine receives the asynchronous stop request", stoppedOcr.await(2, TimeUnit.SECONDS))
    assertEquals("paused", status(id))
    assertEquals("model preparation precedes page OCR", 1, ServiceNativeState.loadCalls)
    assertEquals("A line", database.pageBlockArray(id, 0).getJSONObject(0).getString("sourceText"))
    assertEquals("pending", database.pageBlockArray(id, 0).getJSONObject(0).getString("status"))
  }

  @Test fun destroyedServiceKeepsWorkerOwnedUntilWorkerCleanupOrQueuedTaskCancellation() {
    val id = createJob()
    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, 44)
    assertEquals(id, PdfTranslationService.activeJobId)
    controller.destroy()
    awaitWorkerReleased(id)
    assertEquals("paused", status(id))
    assertNull(PdfTranslationService.activeJobId)
  }

  @Test fun timeoutPausesActiveJobAndCancelsNativeBeforeStopping() {
    val id = createJob()
    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, 30)
    assertTrue(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
      .any { it.id == 8201 })
    service.onTimeout(31, 1)
    awaitStatus(id, "paused")
    assertTrue(error(id).contains("checkpoint"))
    assertEquals(1, ServiceNativeState.cancelCalls)
    assertFalse("Android data-sync timeout must remove the foreground notification",
      context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id == 8201 })
    service.onStartCommand(intent(PdfTranslationService.ACTION_PAUSE_MODEL), 0, 32)
  }

  @Test fun timeoutBeforeQueuedModelWorkerEntryReleasesOnlyItsPausedTransferReservation() {
    val modelId = ModelCatalog.RECOMMENDED_ID
    val token = downloader.reserve(modelId)
    File(downloader.fileFor(modelId).parentFile, "${modelId}.part").writeBytes(byteArrayOf(1, 2, 3))
    database.modelStatus(modelId, "downloading", 3)
    val modelIntent = Intent(context, PdfTranslationService::class.java)
      .setAction(PdfTranslationService.ACTION_START_MODEL)
      .putExtra(PdfTranslationService.EXTRA_MODEL_ID, modelId)
      .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, token)

    service.onStartCommand(modelIntent, 0, 46)
    assertEquals(1, worker.tasks.size)
    service.onTimeout(47, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while ((!worker.isShutdown || database.modelState(modelId)?.first != "paused") && System.nanoTime() < deadline) {
      Thread.sleep(5)
    }
    assertTrue("timeout stops the queued service executor", worker.isShutdown)
    assertEquals(Triple("paused", 3L, null), database.modelState(modelId))
    assertFalse("the old reservation must not remain stuck after its task was discarded", downloader.hasActiveTransfer(modelId))
    var retryToken: String? = null
    try {
      retryToken = downloader.reserve(modelId)
      assertNotEquals(token, retryToken)
    } finally {
      retryToken?.let { downloader.releaseReservation(modelId, it) }
      downloader.pause(modelId)
      downloader.releaseServiceStart(modelId, token)
      downloader.releaseReservation(modelId, token)
    }
  }

  @Test fun staleModelStartCannotConsumeOrReleaseAReplacementReservation() {
    val modelId = ModelCatalog.RECOMMENDED_ID
    val validToken = downloader.reserve(modelId)
    try {
      val staleIntent = Intent(context, PdfTranslationService::class.java)
        .setAction(PdfTranslationService.ACTION_START_MODEL)
        .putExtra(PdfTranslationService.EXTRA_MODEL_ID, modelId)
        .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, "stale-transfer-token")

      service.onStartCommand(staleIntent, 0, 461)

      assertTrue("the current reservation must survive a stale queued command", downloader.hasActiveTransfer(modelId))
      assertTrue("a stale command must not enqueue a worker", worker.tasks.isEmpty())
      assertTrue("an idle service should stop after rejecting the stale command", canceledJobs.contains(461))
      assertNull(PdfTranslationService.activeJobId)
    } finally {
      downloader.releaseReservation(modelId, validToken)
    }
  }

  @Test fun modelStartWhileJobOwnsServiceReleasesOnlyTheModelReservation() {
    val jobId = createJob()
    // setUp() deliberately supplies a definition lookup for the first model only.
    val modelId = pdfModelId
    val token = downloader.reserve(modelId)
    val partialFile = File(downloader.fileFor(modelId).parentFile, "${modelId}.part")
      .apply { writeBytes(byteArrayOf(6, 7)) }
    assertArrayEquals(byteArrayOf(6, 7), partialFile.readBytes())
    assertEquals(2L, downloader.partialLength(modelId))
    database.modelStatus(modelId, "downloading", 2)

    try {
      service.onStartCommand(intent(PdfTranslationService.ACTION_START, jobId), 0, 462)
      service.onStartCommand(Intent(context, PdfTranslationService::class.java)
        .setAction(PdfTranslationService.ACTION_START_MODEL)
        .putExtra(PdfTranslationService.EXTRA_MODEL_ID, modelId)
        .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, token), 0, 463)

      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
      while (database.modelState(modelId)?.first != "paused" && System.nanoTime() < deadline) Thread.sleep(5)
      assertEquals("paused", database.modelState(modelId)?.first)
      assertEquals("the existing translation worker retains service ownership", jobId, PdfTranslationService.activeJobId)
      assertEquals("only the job's queued task should exist", 1, worker.tasks.size)
      assertEquals(Triple("paused", 2L, null), database.modelState(modelId))
      assertFalse("the rejected model transfer must be retryable", downloader.hasActiveTransfer(modelId))
      assertFalse("the model rejection must not stop the active job service", canceledJobs.contains(463))
      worker.runNext()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      assertEquals("the existing job remains runnable after rejecting a model request", "completed", status(jobId))
    } finally {
      downloader.releaseReservation(modelId, token)
    }
  }

  @Test fun destroyingServiceBeforeQueuedModelWorkerEntryReleasesTransferForRetry() {
    val modelId = ModelCatalog.RECOMMENDED_ID
    val token = downloader.reserve(modelId)
    File(downloader.fileFor(modelId).parentFile, "${modelId}.part").writeBytes(byteArrayOf(4, 5))
    database.modelStatus(modelId, "downloading", 2)
    val modelIntent = Intent(context, PdfTranslationService::class.java)
      .setAction(PdfTranslationService.ACTION_START_MODEL)
      .putExtra(PdfTranslationService.EXTRA_MODEL_ID, modelId)
      .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, token)
    service.onStartCommand(modelIntent, 0, 49)
    assertEquals(1, worker.tasks.size)

    controller.destroy()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while ((!worker.isShutdown || database.modelState(modelId)?.first != "paused") && System.nanoTime() < deadline) {
      Thread.sleep(5)
    }
    assertTrue("destroy stops the queued service executor", worker.isShutdown)
    assertEquals(Triple("paused", 2L, null), database.modelState(modelId))
    var retryToken: String? = null
    try {
      retryToken = downloader.reserve(modelId)
      assertNotEquals(token, retryToken)
    } finally {
      retryToken?.let { downloader.releaseReservation(modelId, it) }
      downloader.pause(modelId)
      downloader.releaseServiceStart(modelId, token)
      downloader.releaseReservation(modelId, token)
    }

    controller = Robolectric.buildService(PdfTranslationService::class.java).create()
    service = controller.get().also { it.modelDownloaderFactory = { downloader } }
  }

  @Test fun delayedTimeoutForOldQueuedModelCannotPauseOrReleaseANewerTransfer() {
    val modelId = ModelCatalog.RECOMMENDED_ID
    val oldToken = downloader.reserve(modelId)
    database.modelStatus(modelId, "downloading", 1)
    val modelIntent = Intent(context, PdfTranslationService::class.java)
      .setAction(PdfTranslationService.ACTION_START_MODEL)
      .putExtra(PdfTranslationService.EXTRA_MODEL_ID, modelId)
      .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, oldToken)
    service.onStartCommand(modelIntent, 0, 50)

    // The old transfer finishes its reservation while its queued service
    // cleanup is delayed; a caller may then reserve the same model again.
    downloader.releaseServiceStart(modelId, oldToken)
    assertTrue(downloader.releaseReservation(modelId, oldToken))
    val newToken = downloader.reserve(modelId)
    database.modelStatus(modelId, "downloading", 0)
    try {
      service.onTimeout(51, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
      while (!worker.isShutdown && System.nanoTime() < deadline) Thread.sleep(5)

      assertTrue(worker.isShutdown)
      assertTrue("stale cleanup must leave the replacement transfer active", downloader.hasActiveTransfer(modelId))
      assertEquals("downloading", database.modelState(modelId)!!.first)
    } finally {
      downloader.releaseReservation(modelId, newToken)
    }
  }

  @Test fun oldServiceTimeoutCannotClearANewerModelWorkerGeneration() {
    val oldModel = ModelCatalog.models[0].id
    val oldToken = downloader.reserve(oldModel)
    database.modelStatus(oldModel, "downloading", 0)
    service.onStartCommand(Intent(context, PdfTranslationService::class.java)
      .setAction(PdfTranslationService.ACTION_START_MODEL)
      .putExtra(PdfTranslationService.EXTRA_MODEL_ID, oldModel)
      .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, oldToken), 0, 52)
    downloader.releaseServiceStart(oldModel, oldToken)
    assertTrue(downloader.releaseReservation(oldModel, oldToken))

    val currentModel = ModelCatalog.models[1].id
    val currentToken = downloader.reserve(currentModel)
    database.modelStatus(currentModel, "downloading", 0)
    val replacementController = Robolectric.buildService(PdfTranslationService::class.java).create()
    val replacementService = replacementController.get()
    val replacementWorker = ServiceQueuedExecutor()
    replacementService.modelDownloaderFactory = { downloader }
    replacementService.modelDownloadExecutorFactory = { replacementWorker }
    replacementService.onStartCommand(Intent(context, PdfTranslationService::class.java)
      .setAction(PdfTranslationService.ACTION_START_MODEL)
      .putExtra(PdfTranslationService.EXTRA_MODEL_ID, currentModel)
      .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, currentToken), 0, 53)
    val currentGeneration = PdfTranslationService::class.java.getDeclaredField("activeModelWorkerGeneration")
      .apply { isAccessible = true }.getLong(null)

    try {
      service.onTimeout(54, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
      while (!worker.isShutdown && System.nanoTime() < deadline) Thread.sleep(5)

      assertTrue(worker.isShutdown)
      assertFalse(replacementWorker.isShutdown)
      assertTrue(PdfTranslationService.isActive)
      assertEquals(currentGeneration, PdfTranslationService::class.java.getDeclaredField("activeModelWorkerGeneration")
        .apply { isAccessible = true }.getLong(null))
      assertTrue(downloader.hasActiveTransfer(currentModel))
      assertEquals("downloading", database.modelState(currentModel)!!.first)
    } finally {
      replacementController.destroy()
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
      while (!replacementWorker.isShutdown && System.nanoTime() < deadline) Thread.sleep(5)
      downloader.pause(currentModel)
      downloader.releaseServiceStart(currentModel, currentToken)
      downloader.releaseReservation(currentModel, currentToken)
    }
  }

  @Test fun queuedModelCleanupBlockedDuringPartialLookupCannotClearReplacementGeneration() {
    val oldModel = ModelCatalog.models[0].id
    val currentModel = ModelCatalog.models[1].id
    val lookupEntered = CountDownLatch(1)
    val releaseLookup = CountDownLatch(1)
    val blockCleanupLookup = AtomicBoolean(true)
    val cleanupAwareDownloader = ModelDownloader(context, database, definitionLookup = { id ->
      if (Thread.currentThread().name == "Folivela-stop-signal" && blockCleanupLookup.compareAndSet(true, false)) {
        lookupEntered.countDown()
        check(releaseLookup.await(5, TimeUnit.SECONDS)) { "test did not release the cleanup file lookup" }
      }
      ModelCatalog.get(id)
    })
    service.modelDownloaderFactory = { cleanupAwareDownloader }
    val oldToken = cleanupAwareDownloader.reserve(oldModel)
    database.modelStatus(oldModel, "downloading", 0)
    service.onStartCommand(Intent(context, PdfTranslationService::class.java)
      .setAction(PdfTranslationService.ACTION_START_MODEL)
      .putExtra(PdfTranslationService.EXTRA_MODEL_ID, oldModel)
      .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, oldToken), 0, 55)
    val stopExecutor = PdfTranslationService::class.java.getDeclaredField("stopSignals")
      .apply { isAccessible = true }.get(null) as java.util.concurrent.ExecutorService
    val cleanupDrained = CountDownLatch(1)
    var replacementController: org.robolectric.android.controller.ServiceController<PdfTranslationService>? = null
    var currentToken: String? = null
    try {
      service.onTimeout(56, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      assertTrue("the old queued cleanup must reach its partial-file lookup", lookupEntered.await(2, TimeUnit.SECONDS))

      // A queued service delivery may establish a different generation while
      // the old stop-signal worker is doing filesystem/database cleanup.
      currentToken = cleanupAwareDownloader.reserve(currentModel)
      database.modelStatus(currentModel, "downloading", 0)
      replacementController = Robolectric.buildService(PdfTranslationService::class.java).create()
      val replacementService = replacementController.get()
      val replacementWorker = ServiceQueuedExecutor()
      replacementService.modelDownloaderFactory = { cleanupAwareDownloader }
      replacementService.modelDownloadExecutorFactory = { replacementWorker }
      replacementService.onStartCommand(Intent(context, PdfTranslationService::class.java)
        .setAction(PdfTranslationService.ACTION_START_MODEL)
        .putExtra(PdfTranslationService.EXTRA_MODEL_ID, currentModel)
        .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, currentToken), 0, 57)
      val currentGeneration = PdfTranslationService::class.java.getDeclaredField("activeModelWorkerGeneration")
        .apply { isAccessible = true }.getLong(null)
      assertTrue(currentGeneration > 0L)
      stopExecutor.execute { cleanupDrained.countDown() }
      releaseLookup.countDown()
      assertTrue("the old cleanup must finish before checking the replacement", cleanupDrained.await(2, TimeUnit.SECONDS))

      assertTrue(worker.isShutdown)
      assertFalse(replacementWorker.isShutdown)
      assertTrue("old cleanup must preserve the replacement's shared active state", PdfTranslationService.isActive)
      assertEquals(currentGeneration, PdfTranslationService::class.java.getDeclaredField("activeModelWorkerGeneration")
        .apply { isAccessible = true }.getLong(null))
      assertTrue(cleanupAwareDownloader.hasActiveTransfer(currentModel))
      assertEquals("downloading", database.modelState(currentModel)!!.first)
      assertEquals("paused", database.modelState(oldModel)!!.first)
    } finally {
      releaseLookup.countDown()
      val beforeDestroy = CountDownLatch(1)
      stopExecutor.execute { beforeDestroy.countDown() }
      beforeDestroy.await(2, TimeUnit.SECONDS)
      replacementController?.destroy()
      val afterDestroy = CountDownLatch(1)
      stopExecutor.execute { afterDestroy.countDown() }
      afterDestroy.await(2, TimeUnit.SECONDS)
      currentToken?.let {
        cleanupAwareDownloader.releaseServiceStart(currentModel, it)
        cleanupAwareDownloader.releaseReservation(currentModel, it)
      }
      cleanupAwareDownloader.releaseServiceStart(oldModel, oldToken)
      cleanupAwareDownloader.releaseReservation(oldModel, oldToken)
    }
  }

  @Test fun lateModelCallbacksCannotChangeReplacementNotificationOrPersistAnOldFailure() {
    val oldModel = ModelCatalog.models[0].id
    val currentModel = ModelCatalog.models[1].id
    val oldToken = downloader.reserve(oldModel)
    lateinit var oldProgress: (Long) -> Unit
    lateinit var oldFinished: (Result<File>) -> Unit
    service.beginModelDownload = { _, progress, finished, _ ->
      oldProgress = progress
      oldFinished = finished
      // The service wait ends while the independently owned network operation
      // still has its callbacks, as can happen during lifecycle interruption.
      Thread.currentThread().interrupt()
    }
    service.onStartCommand(Intent(context, PdfTranslationService::class.java)
      .setAction(PdfTranslationService.ACTION_START_MODEL)
      .putExtra(PdfTranslationService.EXTRA_MODEL_ID, oldModel)
      .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, oldToken), 0, 58)
    worker.runNext()
    Thread.interrupted()
    val stopExecutor = PdfTranslationService::class.java.getDeclaredField("stopSignals")
      .apply { isAccessible = true }.get(null) as java.util.concurrent.ExecutorService
    val oldCleanupDrained = CountDownLatch(1)
    var currentToken: String? = null
    try {
      service.onTimeout(59, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      stopExecutor.execute { oldCleanupDrained.countDown() }
      assertTrue(oldCleanupDrained.await(2, TimeUnit.SECONDS))
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      // The fake network entry never claimed a downloader worker; release only
      // that test reservation after its service owner has been reconciled.
      downloader.releaseServiceStart(oldModel, oldToken)
      downloader.releaseReservation(oldModel, oldToken)
      val previousOldState = database.modelState(oldModel)

      newWorker()
      currentToken = downloader.reserve(currentModel)
      database.modelStatus(currentModel, "downloading", 0)
      service.onStartCommand(Intent(context, PdfTranslationService::class.java)
        .setAction(PdfTranslationService.ACTION_START_MODEL)
        .putExtra(PdfTranslationService.EXTRA_MODEL_ID, currentModel)
        .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, currentToken), 0, 60)
      val manager = context.getSystemService(android.app.NotificationManager::class.java)
      val before = manager.activeNotifications.single { it.id == 8201 }.notification
      val previousText = before.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
      assertEquals("Preparing model download…", previousText)

      oldProgress(2_097_152)
      oldFinished(Result.failure(IllegalStateException("late old transfer failure")))
      Shadows.shadowOf(Looper.getMainLooper()).idle()

      val after = manager.activeNotifications.single { it.id == 8201 }.notification
      assertEquals(previousText, after.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
      assertEquals("an old notification must not change the replacement destination", before.contentIntent, after.contentIntent)
      assertEquals("a late old failure must not rewrite its reconciled model row", previousOldState, database.modelState(oldModel))
      assertEquals(Triple("downloading", 0L, null), database.modelState(currentModel))
      assertTrue(PdfTranslationService.isActive)
      assertFalse(worker.isShutdown)
    } finally {
      Thread.interrupted()
      service.onTimeout(61, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      val cleanupDrained = CountDownLatch(1)
      stopExecutor.execute { cleanupDrained.countDown() }
      cleanupDrained.await(2, TimeUnit.SECONDS)
      currentToken?.let {
        downloader.releaseServiceStart(currentModel, it)
        downloader.releaseReservation(currentModel, it)
      }
      downloader.releaseServiceStart(oldModel, oldToken)
      downloader.releaseReservation(oldModel, oldToken)
    }
  }


  @Test fun nativeAndOcrCombinationKeepsDistinctLinesAndFlagsOnlyDisagreement() {
    val native = JSONArray().put(ServiceNativeState.block().put("sourceRects", JSONArray().put(
      sourceRect("A line", 10.0, 10.0, 200.0, 50.0),
    ))).put(JSONObject().put("text", "   "))
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

  @Test fun verifiedTocLeaderOcrNoiseRequiresOneOwnedRectAndExactLabelAndReference() {
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val positives = listOf(
      Triple("Storage . . . . . . . . . . 88", "Storage", "Storage ............ eee 88"),
      Triple("Lighting . . . . . . . . . . 144", "Lighting", "Lighting ...................00.. 144"),
      Triple("OnStar . . . . . . . . . . 391", "OnStar", "OnStar ...............22 02s 391"),
    )
    try {
      positives.forEachIndexed { index, (source, label, recognized) ->
        val native = ServiceNativeState.block().put("id", "toc-$index").put("sourceText", source)
          .put("modelText", label).put("sourceRects", JSONArray().put(
            sourceRect(source, 20.0, 20.0, 300.0, 40.0, baseline = 38.0, objectIndex = 100 + index),
          ))
        val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(recognized to Rect(20, 20, 300, 40))))
        try {
          val result = invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, ocr) as JSONArray
          val block = result.getJSONObject(0)
          assertEquals("verified row remains one native block", 1, result.length())
          assertFalse("dotted-leader OCR artifacts are not source disagreement", block.optBoolean("sourceDisagreement"))
          assertTrue("the positive TOC rule leaves fresh evidence for safe checkpoint resolution",
            block.optBoolean("verifiedTocLeaderOcrNoise"))
        } finally { ocr.close() }
      }
    } finally { bitmap.recycle() }
  }

  @Test fun verifiedTocLeaderNoiseRejectsOcrBoxCoveredOnlyAfterPadding() {
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val source = "Storage . . . . . . . . . . 88"
    val native = ServiceNativeState.block().put("id", "toc-padded-only").put("sourceText", source)
      .put("modelText", "Storage").put("sourceRects", JSONArray().put(
        sourceRect(source, 20.0, 20.0, 300.0, 45.0, baseline = 43.0, objectIndex = 401),
      ))
    // The raw OCR box is 27pt high around a 25pt native line: intersection is
    // 25/27 = 92.6%, while the 1.25pt matching padding fully covers it.
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "Storage ............ eee 88" to Rect(20, 19, 300, 46),
    )))
    try {
      val result = invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals(1, result.length())
      val merged = result.getJSONObject(0)
      assertFalse("padded geometric matching alone is insufficient evidence", merged.optBoolean("verifiedTocLeaderOcrNoise"))
      assertTrue("the OCR disagreement stays reviewable", merged.optBoolean("sourceDisagreement"))
    } finally {
      ocr.close()
      bitmap.recycle()
    }
  }

  @Test fun changedTocTextNumbersUnknownRowsAndMultilineSourceStillRequireReview() {
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    data class Case(val source: String, val model: String, val recognized: String, val rects: JSONArray? = null)
    val cases = listOf(
      Case("Storage . . . . . . . . . . 88", "Storage", "Storagx ............ eee 88"),
      Case("Storage . . . . . . . . . . 88", "Storage", "Storage ............ eee 89"),
      Case("Storage . . . . . . . . . . 88", "Storage", "Storage ............ eee 88 extra"),
      Case("Control pressure is 88 psi", "Control pressure is 88 psi", "Control pressure is 90 psi"),
      Case("Storage . . . . . . . . . . 88\nLocation", "Storage", "Storage ............ eee 88"),
      Case("Storage . . . . . . . . . . 88", "Other label", "Storage ............ eee 88"),
      Case("Storage . . . . . . . . . . 88", "Storage", "Storage ............ eee 88",
        JSONArray().put(sourceRect("Storage . . . . . . . . . .", 20.0, 20.0, 190.0, 40.0, baseline = 38.0, objectIndex = 301))
          .put(sourceRect("88", 191.0, 20.0, 300.0, 40.0, baseline = 38.0, objectIndex = 302))),
    )
    try {
      cases.forEachIndexed { index, case ->
        val rects = case.rects ?: JSONArray().put(sourceRect(case.source, 20.0, 20.0, 300.0, 40.0,
          baseline = 38.0, objectIndex = 200 + index))
        val native = ServiceNativeState.block().put("id", "toc-negative-$index").put("sourceText", case.source)
          .put("modelText", case.model).put("sourceRects", rects)
        val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(case.recognized to Rect(20, 20, 300, 40))))
        try {
          val result = invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, ocr) as JSONArray
          assertEquals("same-region OCR stays attached to native source", 1, result.length())
          assertFalse("unverified candidate cannot gain the TOC proof marker", result.getJSONObject(0).optBoolean("verifiedTocLeaderOcrNoise"))
          assertTrue("candidate $index keeps the ordinary disagreement review", result.getJSONObject(0).optBoolean("sourceDisagreement"))
        } finally { ocr.close() }
      }
    } finally { bitmap.recycle() }
  }

  @Test fun verifiedFreshTocNoiseClearsOnlyAnExactOldOcrWarningAndRetainsItsProvenance() {
    val source = "Storage . . . . . . . . . . 88"
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val native = ServiceNativeState.block().put("id", "p1-o17").put("sourceText", source)
      .put("modelText", "Storage").put("sourceRects", JSONArray().put(sourceRect(source,
        20.0, 20.0, 300.0, 40.0, baseline = 38.0, objectIndex = 17)))
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "Storage ............ eee 88" to Rect(20, 20, 300, 40),
    )))
    try {
      val fresh = (invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, ocr) as JSONArray)
        .getJSONObject(0)
      val history = JSONArray().put(JSONObject().put("attempt", 1).put("text", "prior layout attempt"))
      val saved = JSONObject(fresh.toString()).put("translatedText", "Хранение").put("status", "partial")
        .put("sourceDisagreement", true).put("error", OcrOutcome.SOURCE_DISAGREEMENT_BLOCK_WARNING)
        .put("layoutRetryCount", 1).put("layoutRetryHistory", history)
      invoke("restoreDurableTranslationResult", saved, fresh)

      assertEquals("translated", fresh.getString("status"))
      assertFalse(fresh.optBoolean("sourceDisagreement"))
      assertFalse(fresh.has("error"))
      assertEquals(OcrOutcome.SOURCE_DISAGREEMENT_BLOCK_WARNING,
        fresh.getJSONObject("sourceDisagreementResolution").getString("warning"))
      assertEquals("verified_toc_dotted_leader_ocr_noise",
        fresh.getJSONObject("sourceDisagreementResolution").getString("reason"))
      assertEquals(history.toString(), fresh.getJSONArray("layoutRetryHistory").toString())

      val protectedCases = listOf<(JSONObject, JSONObject) -> Unit>(
        { prior, _ -> prior.put("translationReviewRequired", true) },
        { prior, _ -> prior.put("exportIssues", JSONArray().put(JSONObject().put("reason", "layout_fallback"))) },
        { prior, _ -> prior.put("exportIssues", JSONObject().put("unknown", true)) },
        { prior, _ -> prior.put("layoutRetryError", "A previous layout retry failed.") },
        { prior, _ -> prior.put("error", OcrOutcome.SOURCE_DISAGREEMENT_BLOCK_WARNING + "\nOther source error.") },
        { prior, _ -> prior.put("translatedText", "") },
        { _, current -> current.put("sourceDisagreement", true).put("error", OcrOutcome.SOURCE_DISAGREEMENT_BLOCK_WARNING) },
      )
      protectedCases.forEachIndexed { index, mutate ->
        val prior = JSONObject(saved.toString())
        val current = JSONObject((invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, ocr) as JSONArray)
          .getJSONObject(0).toString())
        mutate(prior, current)
        val expectedError = current.optString("error").ifBlank { prior.optString("error") }
        invoke("restoreDurableTranslationResult", prior, current)
        assertEquals("partial", current.getString("status"))
        assertTrue("old disagreement evidence is retained for case $index", current.optBoolean("sourceDisagreement"))
        assertEquals("an unrelated or malformed review reason is not cleared for case $index",
          expectedError, current.optString("error"))
      }
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun inflatedOcrBoxContainingTheSameNativeDomainIsSuppressedWithoutDisagreement() {
    val native = ServiceNativeState.block().put("id", "url").put("sourceText", "chevrolet.com (U.S.)")
      .put("translatedText", "chevrolet.com")
      .put("sourceRects", JSONArray().put(sourceRect("chevrolet.com (U.S.)", 60.41, 320.95, 129.02, 328.63,
        baseline = 327.0, objectIndex = 507)))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "Lil chevrolet.com (U.S.)" to Rect(31, 319, 130, 339),
    )))
    try {
      val result = invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals("the native translated URL remains the only source block", 1, result.length())
      assertEquals("url", result.getJSONObject(0).getString("id"))
      assertEquals("native", result.getJSONObject(0).getString("source"))
      assertFalse("artifact text around a fully covered URL is not a source disagreement",
        result.getJSONObject(0).optBoolean("sourceDisagreement"))
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun nearbyImageCaptionRemainsOcrWhenItDoesNotContainTheNativeUrl() {
    val native = ServiceNativeState.block().put("id", "url").put("sourceText", "chevrolet.com")
      .put("sourceRects", JSONArray().put(sourceRect("chevrolet.com", 60.0, 320.0, 129.0, 328.0,
        baseline = 326.0, objectIndex = 1)))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "See owner resources" to Rect(25, 319, 130, 339),
    )))
    try {
      val result = invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals(2, result.length())
      assertEquals("See owner resources", result.getJSONObject(1).getString("sourceText"))
      assertEquals("ocr", result.getJSONObject(1).getString("source"))
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun differentDomainInAnInflatedOcrBoxIsRetainedAsDistinctSourceText() {
    val native = ServiceNativeState.block().put("id", "url").put("sourceText", "chevrolet.com")
      .put("sourceRects", JSONArray().put(sourceRect("chevrolet.com", 60.0, 320.0, 129.0, 328.0,
        baseline = 326.0, objectIndex = 1)))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "Lil chevrolet.net (U.S.)" to Rect(31, 319, 130, 339),
    )))
    try {
      val result = invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals("a different host is not deduplicated from the native URL", 2, result.length())
      assertEquals("Lil chevrolet.net (U.S.)", result.getJSONObject(1).getString("sourceText"))
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun nestedOcrLineInsideMultilineNativeParagraphIsDeduplicatedByNormalizedText() {
    val native = ServiceNativeState.block().put("text", "Overview:\nReplace the cabin air filter every year.")
      .put("sourceText", "Overview:\nReplace the cabin air filter every year.")
      .put("left", 10).put("top", 10).put("right", 500).put("bottom", 200)
      .put("sourceRects", JSONArray().put(sourceRect("Replace the cabin air filter every year.",
        30.0, 60.0, 300.0, 90.0, baseline = 86.0)))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      " replace\u00a0the cabin air filter every year. " to Rect(30, 60, 300, 90),
    )))
    try {
      val result = invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals(1, result.length())
      assertEquals("Overview:\nReplace the cabin air filter every year.", result.getJSONObject(0).getString("sourceText"))
      assertFalse(result.getJSONObject(0).optBoolean("sourceDisagreement"))
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun nearbyDiagramLabelRemainsASeparateBlockAndComparableTextMismatchIsFlagged() {
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val native = ServiceNativeState.block().put("text", "Replace filter").put("sourceText", "Replace filter")
      .put("left", 10).put("top", 10).put("right", 150).put("bottom", 50)
      .put("sourceRects", JSONArray().put(sourceRect("Replace filter", 10.0, 10.0, 150.0, 50.0, baseline = 48.0)))
    val labelOcr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf("FILTER 2" to Rect(155, 15, 220, 40))))
    val disagreeOcr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf("Different label" to Rect(10, 10, 150, 50))))
    val shortDisagreeOcr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf("A2" to Rect(10, 10, 150, 50))))
    try {
      val withLabel = invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, labelOcr) as JSONArray
      assertEquals(2, withLabel.length())
      assertEquals("FILTER 2", withLabel.getJSONObject(1).getString("sourceText"))
      assertFalse(withLabel.getJSONObject(0).optBoolean("sourceDisagreement"))

      val withDisagreement = invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, disagreeOcr) as JSONArray
      assertEquals(1, withDisagreement.length())
      assertTrue(withDisagreement.getJSONObject(0).getBoolean("sourceDisagreement"))

      val shortNative = ServiceNativeState.block().put("text", "A1").put("sourceText", "A1")
        .put("sourceRects", JSONArray().put(sourceRect("A1", 10.0, 10.0, 150.0, 50.0, baseline = 48.0)))
      val shortDisagreement = invoke("combineNativeAndOcr", JSONArray().put(shortNative), bitmap, 612f, 792f, shortDisagreeOcr) as JSONArray
      assertEquals(1, shortDisagreement.length())
      assertTrue("same-region short labels need review", shortDisagreement.getJSONObject(0).getBoolean("sourceDisagreement"))
    } finally { labelOcr.close(); disagreeOcr.close(); shortDisagreeOcr.close(); bitmap.recycle() }
  }

  @Test fun ocrInsideSmallSourceIllustrationIsPreservedOnlyWithNativeBodyAndStrongImageCoverage() {
    val body = "This owner's manual explains vehicle features and safety information for the driver."
    val native = JSONArray().put(ServiceNativeState.block().put("sourceText", body).put("text", body)
      .put("sourceRects", JSONArray().put(sourceRect(body, 10.0, 10.0, 190.0, 40.0))))
    val artwork = JSONArray().put(JSONObject()
      .put("objectIndex", 20).put("type", "image").put("left", 100).put("top", 100).put("right", 200).put("bottom", 200)
      .put("coordinateSpaceVersion", 2))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "Figure label" to Rect(110, 110, 140, 120),
      "Raster edge label" to Rect(100, 120, 120, 130),
      "Partially covered caption" to Rect(198, 120, 220, 130),
      "Adjacent caption" to Rect(201, 120, 230, 130),
    )))
    try {
      val result = invokeWithArtwork(native, bitmap, 612f, 792f, ocr, artwork)
      assertEquals(3, result.length())
      assertEquals(body, result.getJSONObject(0).getString("sourceText"))
      assertEquals("Partially covered caption", result.getJSONObject(1).getString("sourceText"))
      assertEquals("Adjacent caption", result.getJSONObject(2).getString("sourceText"))
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun artworkPolicyKeepsImageTextOnScansLargeImagesAndWithoutNativeText() {
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val fullPageArtwork = JSONArray().put(JSONObject()
      .put("objectIndex", 20).put("type", "image").put("left", 0).put("top", 0).put("right", 612).put("bottom", 792)
      .put("coordinateSpaceVersion", 2))
    val smallArtwork = JSONArray().put(JSONObject()
      .put("objectIndex", 20).put("type", "image").put("left", 100).put("top", 100).put("right", 200).put("bottom", 200)
      .put("coordinateSpaceVersion", 2))
    val scanOcr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf("Scanned source label" to Rect(110, 110, 160, 125))))
    val imageOcr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf("Embedded label" to Rect(110, 110, 160, 125))))
    try {
      val scan = invokeWithArtwork(JSONArray(), bitmap, 612f, 792f, scanOcr, fullPageArtwork)
      assertEquals("full-page image OCR remains available", 1, scan.length())
      val noTextLayer = invokeWithArtwork(JSONArray().put(ServiceNativeState.block().put("sourceText", "A title")
        .put("sourceRects", JSONArray().put(sourceRect("A title", 10.0, 10.0, 80.0, 30.0)))),
        bitmap, 612f, 792f, imageOcr, smallArtwork)
      assertEquals("small artwork does not suppress OCR without a real native text layer", 2, noTextLayer.length())
    } finally { scanOcr.close(); imageOcr.close(); bitmap.recycle() }
  }

  @Test fun malformedArtworkBoundsAndPartialImageCoverageNeverSuppressOcr() {
    val body = "This owner's manual explains vehicle features and safety information for the driver."
    val native = JSONArray().put(ServiceNativeState.block().put("sourceText", body).put("text", body)
      .put("sourceRects", JSONArray().put(sourceRect(body, 10.0, 10.0, 190.0, 40.0))))
    val malformed = JSONArray().put(JSONObject().put("objectIndex", 20).put("type", "image").put("left", 100).put("top", 100)
      .put("right", 200).put("bottom", 200).put("coordinateSpaceVersion", 1))
      .put(JSONObject().put("objectIndex", 21).put("type", "image").put("left", 200).put("top", 200)
        .put("right", 100).put("bottom", 100).put("coordinateSpaceVersion", 2))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "Outside" to Rect(201, 120, 230, 130),
    )))
    try {
      val result = invokeWithArtwork(native, bitmap, 612f, 792f, ocr, malformed)
      assertEquals(2, result.length())
      assertEquals("Outside", result.getJSONObject(1).getString("sourceText"))
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun artworkSuppressionRequiresARealObjectIndexAndFullyVisiblePageBounds() {
    val body = "This owner's manual explains vehicle features and safety information for the driver."
    val native = JSONArray().put(ServiceNativeState.block().put("sourceText", body).put("text", body)
      .put("sourceRects", JSONArray().put(sourceRect(body, 10.0, 10.0, 190.0, 40.0))))
    val invalidArtwork = JSONArray()
      .put(JSONObject().put("type", "image").put("left", 100).put("top", 100).put("right", 200).put("bottom", 200).put("coordinateSpaceVersion", 2))
      .put(JSONObject().put("objectIndex", -1).put("type", "image").put("left", 100).put("top", 100).put("right", 200).put("bottom", 200).put("coordinateSpaceVersion", 2))
      .put(JSONObject().put("objectIndex", 2.5).put("type", "image").put("left", 100).put("top", 100).put("right", 200).put("bottom", 200).put("coordinateSpaceVersion", 2))
      .put(JSONObject().put("objectIndex", 3).put("type", "image").put("left", -0.1).put("top", 100).put("right", 200).put("bottom", 200).put("coordinateSpaceVersion", 2))
      .put(JSONObject().put("objectIndex", 4).put("type", "image").put("left", 100).put("top", 100).put("right", 612.1).put("bottom", 200).put("coordinateSpaceVersion", 2))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf("Illustration text" to Rect(110, 110, 150, 120))))
    try {
      assertEquals("Missing, non-integral, negative object IDs and out-of-page images cannot own OCR", 2,
        invokeWithArtwork(native, bitmap, 612f, 792f, ocr, invalidArtwork).length())
      val valid = JSONArray().put(JSONObject().put("objectIndex", 5).put("type", "image")
        .put("left", 100).put("top", 100).put("right", 200).put("bottom", 200).put("coordinateSpaceVersion", 2))
      assertEquals("A valid source image with its real object index still preserves OCR as artwork", 1,
        invokeWithArtwork(native, bitmap, 612f, 792f, ocr, valid).length())
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun nativeCellBlockKeepsEverySourceSpanDuringOcrMerge() {
    val spans = JSONArray()
      .put(JSONObject().put("objectIndex", 11).put("characterIndices", JSONArray().put(0).put(1)))
      .put(JSONObject().put("objectIndex", 12).put("characterIndices", JSONArray().put(4).put(5)))
    val cell = ServiceNativeState.block().put("id", "p0-cell-1").put("sourceText", "Cell value")
      .put("objectIndices", JSONArray().put(11).put(12)).put("sourceSpans", spans)

    val merged = invoke("combineNativeAndOcr", JSONArray().put(cell), null, 612f, 792f, null) as JSONArray

    assertEquals(1, merged.length())
    assertEquals(spans.toString(), merged.getJSONObject(0).getJSONArray("sourceSpans").toString())
  }

  @Test fun imageOverSixtyFivePercentOfPageDoesNotOwnOcrAndRasterToleranceIsBounded() {
    val body = "This owner's manual explains vehicle features and safety information for the driver."
    val native = JSONArray().put(ServiceNativeState.block().put("sourceText", body).put("text", body)
      .put("sourceRects", JSONArray().put(sourceRect(body, 10.0, 10.0, 190.0, 40.0))))
    val oversizedImage = JSONArray().put(JSONObject()
      .put("objectIndex", 20).put("type", "image").put("left", 0).put("top", 0).put("right", 500).put("bottom", 792)
      .put("coordinateSpaceVersion", 2))
    val smallImage = JSONArray().put(JSONObject()
      .put("objectIndex", 20).put("type", "image").put("left", 100).put("top", 100).put("right", 200).put("bottom", 200)
      .put("coordinateSpaceVersion", 2))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val oversizedOcr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf("Label" to Rect(110, 110, 150, 120))))
    val toleranceOcr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf("Raster edge" to Rect(99, 120, 120, 130))))
    val outsideToleranceOcr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf("Outside edge" to Rect(98, 120, 120, 130))))
    try {
      assertEquals("An image occupying more than 65% of a page is not treated as a small preserved illustration", 2,
        invokeWithArtwork(native, bitmap, 612f, 792f, oversizedOcr, oversizedImage).length())
      assertEquals("The raster-edge allowance covers less than 0.25 pt of rounding", 1,
        invokeWithArtwork(native, bitmap, 612f, 792f, toleranceOcr, smallImage).length())
      assertEquals("Text beyond the bounded tolerance remains available", 2,
        invokeWithArtwork(native, bitmap, 612f, 792f, outsideToleranceOcr, smallImage).length())
    } finally {
      oversizedOcr.close(); toleranceOcr.close(); outsideToleranceOcr.close(); bitmap.recycle()
    }
  }

  @Test fun overlappingOcrLinesAreNotDeduplicatedAgainstPreviouslyAddedOcr() {
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "Circuit A" to Rect(20, 20, 100, 50),
      "Circuit B" to Rect(20, 20, 100, 50),
    )))
    try {
      val result = invoke("combineNativeAndOcr", JSONArray(), bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals(2, result.length())
      assertEquals("Circuit A", result.getJSONObject(0).getString("sourceText"))
      assertEquals("Circuit B", result.getJSONObject(1).getString("sourceText"))
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun confirmedOcrTextMayExtendSlightlyBeyondPdfGlyphBoundsButDistantTextIsKept() {
    val native = ServiceNativeState.block().put("text", "Overview: Replace the cabin filter each year.")
      .put("sourceText", "Overview: Replace the cabin filter each year.")
      .put("left", 10).put("top", 10).put("right", 500).put("bottom", 200)
      .put("sourceRects", JSONArray().put(sourceRect("Overview: Replace the cabin filter each year.",
        10.0, 60.0, 500.0, 90.0, baseline = 88.0)))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "Replace the cabin filter each year." to Rect(8, 60, 300, 90),
      "Replace the cabin filter each year." to Rect(8, 220, 300, 250),
    )))
    try {
      val result = invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals(2, result.length())
      assertFalse(result.getJSONObject(0).optBoolean("sourceDisagreement"))
      assertEquals(220.0, result.getJSONObject(1).getDouble("top"), 0.01)
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun adjacentOwnedObjectFragmentsOnOneBaselineMatchOneOcrLine() {
    val native = ServiceNativeState.block().put("text", "Air filter").put("sourceText", "Air filter")
      .put("sourceRects", JSONArray()
        .put(sourceRect("Air", 10.0, 20.0, 100.0, 40.0, baseline = 38.0, objectIndex = 4))
        .put(sourceRect("filter", 102.0, 20.0, 210.0, 40.0, baseline = 38.0, objectIndex = 5)))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf("Air filter" to Rect(10, 20, 210, 40))))
    try {
      val result = invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals(1, result.length())
      assertFalse(result.getJSONObject(0).optBoolean("sourceDisagreement"))
      assertEquals(2, result.getJSONObject(0).getJSONArray("sourceRects").length())
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun pictogramRectBridgesTextFragmentsForOneOwnedOcrLine() {
    val native = JSONArray()
      .put(ServiceNativeState.block().put("id", "left").put("sourceText", "Press and hold ")
        .put("sourceRects", JSONArray().put(sourceRect("Press and hold ", 10.0, 20.0, 70.0, 40.0, baseline = 38.0, objectIndex = 10))))
      .put(ServiceNativeState.block().put("id", "icon").put("sourceText", "K")
        .put("sourceRects", JSONArray().put(sourceRect("K ", 74.0, 21.0, 84.0, 41.0, baseline = 40.8, objectIndex = 11, pictogram = true))))
      .put(ServiceNativeState.block().put("id", "right").put("sourceText", "until the windows")
        .put("sourceRects", JSONArray().put(sourceRect("until the windows", 88.0, 20.0, 160.0, 40.0, baseline = 40.8, objectIndex = 12))))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "Press and hold @ until the windows" to Rect(10, 20, 160, 40),
    )))
    try {
      val result = invoke("combineNativeAndOcr", native, bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals("OCR spanning independently owned text blocks and their intervening icon is suppressed", 3, result.length())
      assertFalse((0 until result.length()).any { result.getJSONObject(it).optString("source") == "ocr" })
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun legacyRectsWithoutObjectIndexesKeepTheirSourceArrayOrder() {
    fun legacyRect(text: String, left: Double, right: Double, pictogram: Boolean = false) =
      sourceRect(text, left, 20.0, right, 40.0, baseline = 38.0, pictogram = pictogram).apply {
        remove("objectIndex")
      }
    val native = JSONArray()
      .put(ServiceNativeState.block().put("id", "left").put("sourceText", "Button ")
        .put("sourceRects", JSONArray().put(legacyRect("Button ", 10.0, 70.0))))
      .put(ServiceNativeState.block().put("id", "icon").put("sourceText", "K")
        .put("sourceRects", JSONArray().put(legacyRect("K", 74.0, 84.0, pictogram = true))))
      .put(ServiceNativeState.block().put("id", "right").put("sourceText", "menu")
        .put("sourceRects", JSONArray().put(legacyRect("menu", 88.0, 130.0))))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "Button @ menu" to Rect(10, 20, 130, 40),
    )))
    try {
      val result = invoke("combineNativeAndOcr", native, bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals(3, result.length())
      assertFalse((0 until result.length()).any { result.getJSONObject(it).optString("source") == "ocr" })
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun iconBridgingKeepsAnAdjacentDiagramLabelAsAnIndependentOcrBlock() {
    val native = JSONArray()
      .put(ServiceNativeState.block().put("id", "left").put("sourceText", "Press ")
        .put("sourceRects", JSONArray().put(sourceRect("Press ", 10.0, 20.0, 50.0, 40.0, baseline = 38.0, objectIndex = 1))))
      .put(ServiceNativeState.block().put("id", "icon").put("sourceText", "K")
        .put("sourceRects", JSONArray().put(sourceRect("K", 54.0, 20.0, 64.0, 40.0, baseline = 38.0, objectIndex = 2, pictogram = true))))
      .put(ServiceNativeState.block().put("id", "right").put("sourceText", " to open")
        .put("sourceRects", JSONArray().put(sourceRect(" to open", 68.0, 20.0, 110.0, 40.0, baseline = 38.0, objectIndex = 3))))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "Diagram label" to Rect(132, 20, 190, 40),
    )))
    try {
      val result = invoke("combineNativeAndOcr", native, bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals(4, result.length())
      assertEquals("Diagram label", result.getJSONObject(3).getString("sourceText"))
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun codeDisagreementRemainsVisibleWhenTheComparedLineContainsAnIcon() {
    val native = JSONArray()
      .put(ServiceNativeState.block().put("id", "left").put("sourceText", "Lock code ")
        .put("sourceRects", JSONArray().put(sourceRect("Lock code ", 10.0, 20.0, 70.0, 40.0, baseline = 38.0, objectIndex = 1))))
      .put(ServiceNativeState.block().put("id", "icon").put("sourceText", "K")
        .put("sourceRects", JSONArray().put(sourceRect("K", 74.0, 20.0, 84.0, 40.0, baseline = 38.0, objectIndex = 2, pictogram = true))))
      .put(ServiceNativeState.block().put("id", "right").put("sourceText", "104")
        .put("sourceRects", JSONArray().put(sourceRect("104", 88.0, 20.0, 120.0, 40.0, baseline = 38.0, objectIndex = 3))))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "Lock code @ 105" to Rect(10, 20, 120, 40),
    )))
    try {
      val result = invoke("combineNativeAndOcr", native, bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals(3, result.length())
      assertTrue("the source text block remains reviewable", result.getJSONObject(0).getBoolean("sourceDisagreement"))
      assertFalse("the pictogram surrogate is not linguistic evidence", result.getJSONObject(1).optBoolean("sourceDisagreement"))
      assertTrue("the source code block remains reviewable", result.getJSONObject(2).getBoolean("sourceDisagreement"))
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun separateNativeLinesUseTheirOwnedRectsAndDoNotMatchParagraphWhitespace() {
    val native = ServiceNativeState.block().put("text", "Header\nWheel pressure").put("sourceText", "Header\nWheel pressure")
      .put("left", 10).put("top", 10).put("right", 500).put("bottom", 200)
      .put("sourceRects", JSONArray()
        .put(sourceRect("Header", 10.0, 10.0, 100.0, 30.0, baseline = 28.0, objectIndex = 1))
        .put(sourceRect("Wheel pressure", 10.0, 80.0, 180.0, 100.0, baseline = 98.0, objectIndex = 2)))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "Wheel pressure" to Rect(10, 80, 180, 100),
      "Diagram label" to Rect(200, 45, 300, 65),
    )))
    try {
      val result = invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals(2, result.length())
      assertFalse(result.getJSONObject(0).optBoolean("sourceDisagreement"))
      assertEquals("Diagram label", result.getJSONObject(1).getString("sourceText"))
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun legacyParagraphWithoutOwnedRectsCannotSuppressOcrInsideItsUnionBounds() {
    val native = ServiceNativeState.block().put("text", "First line\nSecond line").put("sourceText", "First line\nSecond line")
      .put("left", 10).put("top", 10).put("right", 500).put("bottom", 200)
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf(
      "Diagram label" to Rect(250, 90, 350, 120),
    )))
    try {
      val result = invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals(2, result.length())
      assertFalse(result.getJSONObject(0).optBoolean("sourceDisagreement"))
      assertEquals("Diagram label", result.getJSONObject(1).getString("sourceText"))
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun purePictogramOcrIsSuppressedWithoutTreatingSurrogateTextAsEvidence() {
    val native = ServiceNativeState.block().put("sourceText", "arrow-surrogate")
      .put("sourceRects", JSONArray().put(sourceRect("arrow-surrogate", 20.0, 20.0, 100.0, 70.0,
        baseline = 68.0, pictogram = true)))
    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    val ocr = OcrEngine(service, "en", api = ServiceOcrApi(lines = listOf("A" to Rect(20, 20, 100, 70))))
    try {
      val result = invoke("combineNativeAndOcr", JSONArray().put(native), bitmap, 612f, 792f, ocr) as JSONArray
      assertEquals("OCR recognizing only a pictogram must not become a translatable body block", 1, result.length())
      assertFalse(result.getJSONObject(0).optBoolean("sourceDisagreement"))
      assertEquals("native", result.getJSONObject(0).getString("source"))
      assertFalse((0 until result.length()).any { result.getJSONObject(it).optString("source") == "ocr" })
    } finally { ocr.close(); bitmap.recycle() }
  }

  @Test fun nativeCoordinateAndCropMetadataAreCopiedAndLegacyMissingMarkerStaysMissing() {
    val raw = ServiceNativeState.block().put("coordinateSpaceVersion", 2).put("partialCrop", true)
    val uncropped = ServiceNativeState.block().put("id", "uncropped").put("coordinateSpaceVersion", 2).put("partialCrop", false)
    val legacy = ServiceNativeState.block().put("id", "legacy")
    val combined = invoke("combineNativeAndOcr", JSONArray().put(raw).put(uncropped).put(legacy), null, 612f, 792f, null) as JSONArray
    val versioned = combined.getJSONObject(0)
    assertEquals(2, versioned.getInt("coordinateSpaceVersion"))
    assertTrue(versioned.getBoolean("partialCrop"))
    val uncroppedResult = combined.getJSONObject(1)
    assertEquals(2, uncroppedResult.getInt("coordinateSpaceVersion"))
    assertFalse(uncroppedResult.getBoolean("partialCrop"))
    val old = combined.getJSONObject(2)
    assertFalse(old.has("coordinateSpaceVersion"))
    assertFalse(old.has("partialCrop"))
  }

  @Test fun nativeSliceQaPreservesPageAndBlockCoordinateMetadataWithoutInventingLegacyMarker() {
    val activity = Robolectric.buildActivity(NativeSliceQaActivity::class.java).setup().get()
    val preserve = NativeSliceQaActivity::class.java.getDeclaredMethod(
      "preserveCoordinateSpaceMetadata", JSONObject::class.java, JSONObject::class.java,
    ).apply { isAccessible = true }
    val versioned = JSONObject().put("coordinateSpaceVersion", 2)
    val versionedMetadata = JSONObject()
    preserve.invoke(activity, versioned, versionedMetadata)
    assertEquals(2, versionedMetadata.getInt("coordinateSpaceVersion"))
    val legacyMetadata = JSONObject()
    preserve.invoke(activity, JSONObject(), legacyMetadata)
    assertFalse(legacyMetadata.has("coordinateSpaceVersion"))

    val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
    ShadowNativePdfCore.renderCallback = { bitmap }
    val ocr = OcrEngine(activity, "en", api = ServiceOcrApi(lines = listOf("Replace the cabin filter" to Rect(20, 20, 300, 60))))
    try {
      val page = JSONObject().put("coordinateSpaceVersion", 2).put("blocks", JSONArray().put(
        ServiceNativeState.block().put("text", "Replace the cabin filter each year")
          .put("sourceText", "Replace the cabin filter each year")
          .put("coordinateSpaceVersion", 2).put("partialCrop", true)
          .put("left", 10).put("top", 10).put("right", 400).put("bottom", 100),
      ))
      val combine = NativeSliceQaActivity::class.java.declaredMethods.single { it.name == "combineNativeAndOcr" }
        .apply { isAccessible = true }
      val result = combine.invoke(activity, "unused.pdf", page, 612f, 792f, ocr) as JSONArray
      assertEquals(1, result.length())
      assertEquals(2, result.getJSONObject(0).getInt("coordinateSpaceVersion"))
      assertTrue(result.getJSONObject(0).getBoolean("partialCrop"))
    } finally { ocr.close(); bitmap.recycle(); activity.finish() }
  }

  @Test fun restoredBlockStatesAreRetainedWithoutRepeatingTranslation() {
    for (savedStatus in listOf("translated", "partial", "failed", "pending")) {
      val id = createJob()
      database.savePage(id, 0, "running", JSONArray().put(ServiceNativeState.block()
        .put("sourceText", "A line").put("translatedText", "Сохранено").put("status", savedStatus)).toString(), 612.0, 792.0, expectedBlocks = 1)
      start(id)
      val block = database.pageBlockArray(id, 0).getJSONObject(0)
      assertEquals("source-identical checkpoint input is preserved", "A line", block.getString("sourceText"))
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
    invoke("cleanupJobWorker", "missing", ocr, worker, 0L)
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    service.qaProfileEnabled = { true }
    invoke("cleanupJobWorker", "missing", null, worker, 0L)
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
      ServiceNativeState.export = { index, request, destination ->
        exports++
        if ((phase == 4 && exports == 1) || (phase in 5..6 && exports == 2)) stop()
        ServiceNativeState.exportResult(index, request, destination)
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
    ServiceNativeState.page = { JSONObject().put("width", 612).put("height", 792).put("blocks", JSONArray().put(
      ServiceNativeState.block().put("sourceText", " "))).toString() }
    database.savePage(emptySaved, 0, "running", JSONArray().put(ServiceNativeState.block().put("sourceText", " ").put("status", "partial")).toString(), 612.0, 792.0)
    start(emptySaved)
    assertEquals("partial", status(emptySaved))
    ServiceNativeState.page = { ServiceNativeState.defaultPage() }
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
    val cases = listOf<Pair<Boolean, (Int, String, String) -> String>>(
      false to { _, _, path -> File(path).writeText(""); "{}" },
      true to { _, _, path -> File(path).writeText("%PDF-1.7"); "{}" },
      true to { _, _, path -> File(path).writeText("%PDF-1.7"); "{\"issues\":[{\"blockId\":\"b0\",\"message\":\"review\"}]}" },
    )
    for ((success, callback) in cases) {
      ServiceNativeState.export = callback
      val result = runCatching { invoke("validatePageExport", source, 0, blocks, id, "ru") }
      assertEquals(success, result.isSuccess)
      assertFalse(File(context.cacheDir, "page-validation/$id-0.pdf").exists())
    }
    val expectedCjkFont = File(context.cacheDir, "NotoSansSC-Regular.ttf")
    assertTrue("The bundled fallback font must be verified and passed for Russian page export", expectedCjkFont.isFile)
    assertEquals(expectedCjkFont.absolutePath, PdfCoreShadowState.lastCjkFontPath)
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
    invoke("cleanupJobWorker", id, null, worker, 0L)
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertTrue(worker.isShutdown)
  }


  @Test fun interruptCleanupUsesAtomicStatusEvenWhenTheReadAdapterCannotReturnTheJob() {
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
      assertEquals("paused", status(id))
      assertTrue(worker.isShutdown)
    } finally { Thread.interrupted(); racing.close() }
  }

  @Test fun interruptCleanupDoesNotRecreateAJobThatWasActuallyRemoved() {
    val id=createJob()
    ServiceNativeState.load={database.deleteJob(id);Thread.currentThread().interrupt();"{}"}
    try {
      start(id)
      Thread.interrupted()
      assertNull(database.jobJson(id))
      assertTrue(worker.isShutdown)
    } finally {Thread.interrupted()}
  }

  @Test fun pauseAndCancelOfAPersistedJobReleaseANewIdleService() {
    for((index,action) in listOf(PdfTranslationService.ACTION_PAUSE,PdfTranslationService.ACTION_CANCEL).withIndex()) {
      val id=createJob()
      database.updateJob(id,status="paused")
      service.onStartCommand(Intent(context,PdfTranslationService::class.java).setAction(action)
        .putExtra(PdfTranslationService.EXTRA_JOB_ID,id),0,800+index)
      awaitStatus(id, if(action==PdfTranslationService.ACTION_CANCEL) "canceled" else "paused")
      assertTrue("A persisted-job action must release an idle service",canceledJobs.contains(800+index))
    }
  }

  @Test fun malformedPauseAndCancelIntentsWithoutJobIdsOnlyStopTheIdleService() {
    val id = createJob()
    database.updateJob(id, status = "paused")

    val pauseResult = service.onStartCommand(Intent().setAction(PdfTranslationService.ACTION_PAUSE), 0, 810)
    val cancelResult = service.onStartCommand(Intent().setAction(PdfTranslationService.ACTION_CANCEL), 0, 811)

    assertEquals(android.app.Service.START_NOT_STICKY, pauseResult)
    assertEquals(android.app.Service.START_NOT_STICKY, cancelResult)
    assertTrue(canceledJobs.containsAll(listOf(810, 811)))
    assertEquals("a malformed cancel must not mutate an unrelated persisted job", "paused", status(id))
    assertTrue(worker.tasks.isEmpty())
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

  @Test fun translationNotificationUsesViewDeepLinkToOpenTheActiveJob() {
    val jobId = "service-notification-42"
    setField("jobId", jobId)

    val notification = invoke("notification", "Translating page 8 of 30") as android.app.Notification
    val pendingIntent = notification.contentIntent
    assertNotNull(pendingIntent)
    val launch = Shadows.shadowOf(pendingIntent!!).savedIntent

    assertEquals(Intent.ACTION_VIEW, launch.action)
    assertEquals(android.content.ComponentName(context, MainActivity::class.java), launch.component)
    assertEquals(android.net.Uri.parse("folivela:///job?jobId=$jobId"), launch.data)
    assertTrue(launch.hasCategory(Intent.CATEGORY_DEFAULT))
    assertTrue(launch.hasCategory(Intent.CATEGORY_BROWSABLE))
    assertFalse(launch.hasCategory(Intent.CATEGORY_LAUNCHER))
    val requiredFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    assertEquals(requiredFlags, launch.flags and requiredFlags)
  }

  @Test fun foregroundNotificationTransitionsFromImmediateStartupToLiveBlockProgress() {
    val id = createJob()
    val manager = context.getSystemService(android.app.NotificationManager::class.java)
    newWorker()

    service.onStartCommand(intent(PdfTranslationService.ACTION_START, id), 0, sequence)
    val startup = manager.activeNotifications.single { it.id == 8201 }.notification
    assertEquals("Starting local translation…",
      startup.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
    assertTrue("foreground notification must be ongoing before worker execution",
      startup.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)

    var progressDuringInference: String? = null
    ServiceNativeState.translation = {
      progressDuringInference = manager.activeNotifications.single { it.id == 8201 }.notification
        .extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
      "{\"text\":\"Перевод\"}"
    }
    worker.runNext()
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals("completed", status(id))
    assertEquals("Translating page 1 of 1 · 1 blocks", progressDuringInference)
  }

  @Test fun modelDownloadNotificationWithoutAnActiveJobKeepsTheLauncherDestination() {
    setField("modelId", ModelCatalog.RECOMMENDED_ID)

    val notification = invoke("notification", "Downloading translation model…") as android.app.Notification
    val pendingIntent = notification.contentIntent
    assertNotNull(pendingIntent)
    val launch = Shadows.shadowOf(pendingIntent!!).savedIntent

    assertEquals(Intent.ACTION_MAIN, launch.action)
    assertEquals(android.content.ComponentName(context, MainActivity::class.java), launch.component)
    assertTrue(launch.hasCategory(Intent.CATEGORY_LAUNCHER))
    assertNull(launch.data)
  }

  @Test fun activeModelDownloadNotificationShowsProgressThenVerifiedCompletion() {
    val modelId = ModelCatalog.models.first().id
    val transferToken = downloader.reserve(modelId)
    val progressPosted = CountDownLatch(1)
    val allowCompletion = CountDownLatch(1)
    val workerFinished = CountDownLatch(1)
    val manager = context.getSystemService(android.app.NotificationManager::class.java)
    service.modelDefinitionLookup = ModelCatalog::get
    service.beginModelDownload = { id, progress, finished, _ ->
      assertEquals(modelId, id)
      progress(2_097_152)
      progressPosted.countDown()
      check(allowCompletion.await(2, TimeUnit.SECONDS)) { "test did not release model download callback" }
      finished(Result.success(downloader.fileFor(id)))
    }
    service.onStartCommand(Intent(context, PdfTranslationService::class.java)
      .setAction(PdfTranslationService.ACTION_START_MODEL)
      .putExtra(PdfTranslationService.EXTRA_MODEL_ID, modelId)
      .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, transferToken), 0, 816)

    val workerThread = Thread {
      worker.runNext()
      workerFinished.countDown()
    }
    workerThread.start()
    try {
      assertTrue("download progress callback was not delivered", progressPosted.await(2, TimeUnit.SECONDS))
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      val progress = manager.activeNotifications.single { it.id == 8201 }.notification
        .extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
      assertTrue("current owner must publish actual download progress", progress?.startsWith("Downloading model · ") == true)

      allowCompletion.countDown()
      assertTrue("model worker did not finish after its callback", workerFinished.await(2, TimeUnit.SECONDS))
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      val completed = manager.activeNotifications.single { it.id == 8201 }.notification
        .extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
      assertEquals("Model downloaded and verified.", completed)
    } finally {
      allowCompletion.countDown()
      workerThread.join(2_000)
      downloader.releaseServiceStart(modelId, transferToken)
      downloader.releaseReservation(modelId, transferToken)
      downloader.cancel(modelId)
    }
  }

  @Test fun foregroundNotificationUsesDedicatedSmallIconAndRemainsOngoing() {
    val notification = invoke("notification", "Reading page 1 of 415") as android.app.Notification
    assertEquals(R.drawable.ic_notification, notification.smallIcon.resId)
    assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    assertTrue(notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
    assertEquals(android.app.Notification.CATEGORY_SERVICE, notification.category)
    assertNotNull(notification.contentIntent)
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

private class ServiceOcrApi(
  private val failRecognize: Boolean = false,
  private val nullFailure: Boolean = false,
  private val lines: List<Pair<String, Rect>> = emptyList(),
  private val onRecognize: () -> Unit = {},
  private val onStop: () -> Unit = {},
) : OcrEngineApi {
  override fun initialize(dataDirectory: String, language: String) = true
  override fun setAutomaticPageSegmentation() = Unit
  override fun setImage(bitmap: Bitmap) = Unit
  override fun recognize() { onRecognize(); if (failRecognize) throw if (nullFailure) IllegalStateException() else IllegalStateException("OCR failed") }
  override fun resultIterator(): OcrLineIterator = object : OcrLineIterator {
    private var index = 0
    override fun begin() { index = 0 }
    override fun text(): String? = lines.getOrNull(index)?.first
    override fun bounds() = lines.getOrNull(index)?.second ?: Rect()
    override fun next(): Boolean { index++; return index < lines.size }
    override fun delete() = Unit
  }
  override fun clear() = Unit
  override fun stop() = onStop()
  override fun recycle() = Unit
}

internal object ServiceNativeState {
  var load: () -> String = { "{}" }
  var page: () -> String = { defaultPage() }
  var translation: () -> String = { "{\"text\":\"Строка\"}" }
  var render: () -> Bitmap = { Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888) }
  var export: (Int, String, String) -> String = { index, request, destination -> exportResult(index, request, destination) }
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
    export = { index, request, destination -> exportResult(index, request, destination) }
    loadCalls = 0; readCalls = 0; releaseCalls = 0; cancelCalls = 0
  }

  fun exportResult(index: Int, requestJson: String, destination: String, partial: Boolean = false,
                   issues: JSONArray = JSONArray()): String {
    File(destination).writeText("%PDF-1.7\npage")
    val request = JSONObject(requestJson)
    check(request.getInt("layoutVersion") == 3 && request.getString("mode") == "preserve-source")
    check(request.getInt("sourcePageIndex") == index)
    val blocks = request.getJSONArray("blocks")
    val placements = JSONArray()
    for (blockIndex in 0 until blocks.length()) {
      val block = blocks.optJSONObject(blockIndex) ?: continue
      val text = if (block.isNull("translatedText")) block.optString("translation") else block.optString("translatedText")
      val id = block.optString("id")
      if (id.isNotBlank() && text.isNotBlank() && text != "null") {
        placements.put(JSONObject().put("blockId", id).put("outputPageIndex", 0))
      }
    }
    return JSONObject().put("layoutVersion", PdfLayoutReceipt.LAYOUT_VERSION).put("mode", "preserve-source")
      .put("path", destination).put("sourcePageIndex", index)
      .put("pageCount", 1).put("partial", partial).put("issues", issues).put("placements", placements).toString()
  }

  fun merge(requestJson: String, destination: String): String {
    File(destination).writeText("%PDF-1.7\nmerged")
    val request = JSONObject(requestJson)
    check(request.getInt("layoutVersion") == 3 && request.getString("mode") == "preserve-source")
    check(File(request.getString("sourcePath")).isFile && File(request.getString("fontPath")).isFile)
    val shards = request.getJSONArray("shards")
    val ranges = JSONArray()
    val pages = JSONArray()
    for (shardIndex in 0 until shards.length()) {
      val shard = shards.getJSONObject(shardIndex)
      val sourceIndex = shard.getInt("sourcePageIndex")
      check(shard.getInt("expectedPageCount") == 1)
      val snapshot = JSONObject(File(shard.getString("blocksPath")).readText())
      check(snapshot.getInt("sourcePageIndex") == sourceIndex && snapshot.has("blocks"))
      ranges.put(JSONObject().put("sourcePageIndex", sourceIndex).put("firstOutputPage", sourceIndex)
        .put("outputPageCount", 1))
      pages.put(JSONObject().put("outputPageIndex", sourceIndex)
        .put("sourcePageIndex", sourceIndex).put("sourceOutputPageIndex", 0))
    }
    return JSONObject().put("layoutVersion", PdfLayoutReceipt.LAYOUT_VERSION).put("mode", "preserve-source")
      .put("pageCount", shards.length())
      .put("sourcePageRanges", ranges).put("pages", pages).toString()
  }
}

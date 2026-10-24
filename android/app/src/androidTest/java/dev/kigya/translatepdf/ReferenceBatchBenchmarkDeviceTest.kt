package dev.kigya.translatepdf

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Private opt-in benchmark for the production JNI fixed-width batch path. */
@RunWith(AndroidJUnit4::class)
class ReferenceBatchBenchmarkDeviceTest {
  @Test fun maximumEquinoxBatchUsesEightProductionLanesAndPersistsEveryEosResult() {
    val args = InstrumentationRegistry.getArguments()
    assumeTrue("The private production batch benchmark was not requested", args.getString("folivelaReferenceBatch") == "true")

    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val modelId = args.getString("modelId") ?: "hy-mt2-7b-q4"
    assertEquals("This benchmark is specifically the pinned Maximum profile", "hy-mt2-7b-q4", modelId)
    assertFalse("A document worker must be paused before loading a benchmark model", PdfTranslationService.isActive)
    assertNull("A document worker must not own the native model", PdfTranslationService.activeJobId)

    val inputFile = File(requireNotNull(context.getExternalFilesDir(null)), "u050-reference-batch-input.json")
    assertTrue("Push the private 8-request Equinox control manifest before the opt-in run", inputFile.isFile)
    val inputs = JSONArray(inputFile.readText())
    assertEquals(8, inputs.length())
    val expectedIds = LinkedHashSet<String>()
    val requests = JSONArray()
    for (index in 0 until inputs.length()) {
      val input = inputs.getJSONObject(index)
      val id = input.getString("id")
      val text = input.getString("text")
      assertTrue("Each request needs a unique stable ID", expectedIds.add(id))
      assertTrue("Each source block must be nonempty", text.isNotBlank())
      requests.put(JSONObject().put("id", id).put("text", text))
    }

    val model = ModelCatalog.get(modelId)
    assertEquals("The selected file must be the catalog-pinned Maximum artifact", 4_624_648_896L, model.bytes)
    assertEquals("hy-mt2", model.profile)
    val database = TranslatorDatabase(context)
    val downloader = ModelDownloader(context, database)
    val modelFile = downloader.fileFor(modelId)
    val readinessStartedAt = SystemClock.elapsedRealtimeNanos()
    assertTrue("The installed pinned weights must pass the actual downloader's digest/length readiness check", downloader.isReady(modelId))
    val readinessMillis = (SystemClock.elapsedRealtimeNanos() - readinessStartedAt) / 1_000_000L

    val external = requireNotNull(context.getExternalFilesDir(null))
    val jsonl = File(external, "u050-reference-batch-$modelId.jsonl")
    val reportFile = File(external, "u050-reference-batch-$modelId.json")
    val generation = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
    val tid = Process.myTid()
    val previousPriority = Process.getThreadPriority(tid)
    val startedAt = SystemClock.elapsedRealtimeNanos()
    try {
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
      NativeCore.releaseModel()
      // Match the measured production policy for weights larger than this
      // device's working RAM; do not silently allocate a repacked second copy.
      NativeCore.setWeightRepackingAllowed(false)
      val loadStartedAt = SystemClock.elapsedRealtimeNanos()
      val load = JSONObject(NativeCore.loadModelBatch(modelFile.absolutePath, 4096, 4, model.profile, 8))
      val loadMillis = (SystemClock.elapsedRealtimeNanos() - loadStartedAt) / 1_000_000L
      assertFalse("The actual native model load must succeed", load.has("error"))
      assertEquals(4096, load.getInt("context"))
      assertEquals(8, load.getInt("maxSequences"))

      val completionIds = LinkedHashSet<String>()
      val outputs = JSONArray()
      FileOutputStream(jsonl, false).use { stream ->
        val batchStartedAt = SystemClock.elapsedRealtimeNanos()
        val summary = JSONObject(NativeCore.translateBatch(
          requests.toString(), "", "", "en", "ru", generation,
          BatchCompletion { responseJson ->
            val response = JSONObject(responseJson)
            val id = response.getString("id")
            assertTrue("Native completion must belong to one of the Equinox requests", id in expectedIds)
            assertTrue("Each request must complete once", completionIds.add(id))
            assertEquals(generation, response.getLong("generation"))
            val translated = response.getString("text")
            assertTrue("Only nonempty EOS-completed outputs are recorded", translated.isNotBlank())
            val privateRecord = JSONObject(response.toString())
            stream.write((privateRecord.toString() + "\n").toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            outputs.put(privateRecord)
            1
          },
        ))
        val batchMillis = (SystemClock.elapsedRealtimeNanos() - batchStartedAt) / 1_000_000L
        assertFalse("The native batch must not report cancellation", summary.optBoolean("canceled"))
        assertFalse("The native batch must not report an error", summary.has("error"))
        assertEquals(8, summary.getJSONArray("committedIds").length())
        assertEquals(0, summary.getJSONArray("pendingIds").length())
        assertEquals(0, summary.getJSONArray("finalErrors").length())
        assertEquals(expectedIds, completionIds)
        assertEquals(expectedIds, summary.getJSONArray("committedIds").let { array ->
          (0 until array.length()).mapTo(LinkedHashSet()) { array.getString(it) }
        })
        assertEquals(8, outputs.length())

        val report = JSONObject()
          .put("modelId", modelId)
          .put("modelSha256", model.sha256)
          .put("modelBytes", modelFile.length())
          .put("readinessMillis", readinessMillis)
          .put("requestCount", inputs.length())
          .put("languagePair", "en-ru")
          .put("requestedContext", 4096)
          .put("requestedThreads", 4)
          .put("requestedSequences", 8)
          .put("loadMillis", loadMillis)
          .put("batchMillis", batchMillis)
          .put("totalMillis", (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L)
          .put("nativeLoad", load)
          .put("inferenceProgress", JSONObject(NativeCore.inferenceProgress()))
          .put("summary", summary)
          .put("outputs", outputs)
          .put("outputJsonl", jsonl.name)
        reportFile.writeText(report.toString(2))
      }
    } finally {
      try {
        NativeCore.releaseModel()
      } finally {
        try { Process.setThreadPriority(previousPriority) }
        finally { database.close() }
      }
    }
    assertTrue("The private benchmark report must be durably written", reportFile.isFile && reportFile.length() > 0)
  }
}

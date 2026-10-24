package dev.kigya.translatepdf

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class OcrEngineTest {
  @Test
  fun lineOnlyRecognitionKeepsBinaryCompatibleThreeArgumentMethod() {
    val method = OcrEngine::class.java.getDeclaredMethod(
      "recognize",
      Bitmap::class.java,
      Float::class.javaPrimitiveType,
      Float::class.javaPrimitiveType,
    )

    assertEquals(JSONArray::class.java, method.returnType)
  }

  @Test
  fun recognitionTrimsTextScalesBoundsAndSkipsBlankOrInvalidLines() {
    val context = RuntimeEnvironment.getApplication()
    val api = FakeOcrApi(listOf(
      OcrFixture("  HELLO WORLD  ", Rect(40, 30, 260, 70)),
      OcrFixture("   ", Rect(10, 10, 80, 30)),
      OcrFixture("not visible", Rect(20, 20, 20, 40)),
      OcrFixture("invisible height", Rect(20, 20, 30, 20)),
      OcrFixture(null, Rect(10, 10, 80, 30)),
      OcrFixture("SECOND", Rect(0, 100, 200, 140)),
    ))
    val engine = OcrEngine(context, api = api)
    val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)

    val lines = engine.recognize(bitmap, pageWidth = 200f, pageHeight = 100f)

    assertEquals(2, lines.length())
    val first = lines.getJSONObject(0)
    assertEquals("ocr-0", first.getString("id"))
    assertEquals("HELLO WORLD", first.getString("sourceText"))
    assertEquals(20.0, first.getDouble("left"), 0.001)
    assertEquals(15.0, first.getDouble("top"), 0.001)
    assertEquals(130.0, first.getDouble("right"), 0.001)
    assertEquals(20.0, first.getDouble("fontSize"), 0.001)
    assertEquals("ocr-1", lines.getJSONObject(1).getString("id"))
    assertEquals("pending", first.getString("status"))
    assertEquals(6, api.iterator.nextCalls)
    assertEquals(1, api.iterator!!.deleteCalls)
    assertEquals(1, api.clearCalls)
    assertEquals("eng", api.language)
    assertTrue(api.dataDirectory!!.endsWith("/tesseract"))
    bitmap.recycle()
  }

  @Test
  fun confidenceAndTightWordBoundsAreDiagnosticEvidenceAndDoNotFilterSingleGlyphOrLogoText() {
    val context = RuntimeEnvironment.getApplication()
    val api = FakeOcrApi(
      listOf(
        OcrFixture("a", Rect(20, 10, 180, 70), confidence = 18f),
        OcrFixture("CHEVROLET", Rect(20, 80, 180, 104), confidence = 96f),
      ),
      listOf(
        OcrWordEvidence("a", Rect(80, 30, 92, 54), 12f),
        OcrWordEvidence("CHEVROLET", Rect(42, 84, 148, 101), 97f),
        OcrWordEvidence("unrelated", Rect(200, 130, 240, 150), 99f),
      ),
    )
    val engine = OcrEngine(context, api = api)
    val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)

    val lines = engine.recognize(bitmap, 200f, 100f, includeWordEvidence = true)

    assertEquals(2, lines.length())
    val glyph = lines.getJSONObject(0)
    assertEquals("a", glyph.getString("sourceText"))
    assertEquals(18.0, glyph.getDouble("ocrConfidence"), 0.001)
    val glyphWord = glyph.getJSONArray("wordEvidence").getJSONObject(0)
    assertEquals("a", glyphWord.getString("text"))
    assertEquals(40.0, glyphWord.getDouble("left"), 0.001)
    assertEquals(46.0, glyphWord.getDouble("right"), 0.001)
    assertEquals(12.0, glyphWord.getDouble("confidence"), 0.001)

    val logoText = lines.getJSONObject(1)
    assertEquals("CHEVROLET", logoText.getString("sourceText"))
    assertEquals(96.0, logoText.getDouble("ocrConfidence"), 0.001)
    assertEquals("CHEVROLET", logoText.getJSONArray("wordEvidence").getJSONObject(0).getString("text"))
    assertEquals(1, logoText.getJSONArray("wordEvidence").length())
    bitmap.recycle()
  }

  @Test
  fun detailedRecognitionRetainsTightWordBoundsEvenWhenTheyDoNotMatchAnOcrLine() {
    val api = FakeOcrApi(
      listOf(OcrFixture("a", Rect(20, 10, 180, 70), confidence = 0f)),
      listOf(OcrWordEvidence("a", Rect(250, 140, 290, 180), confidence = 2f)),
    )
    val engine = OcrEngine(RuntimeEnvironment.getApplication(), api = api)
    val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)

    val result = engine.recognizeDetailed(bitmap, 200f, 100f)

    assertEquals(1, result.lines.length())
    assertTrue("The inflated line must not claim the tight word as a line match", result.lines.getJSONObject(0).getJSONArray("wordEvidence").length() == 0)
    assertEquals(1, result.words.length())
    val word = result.words.getJSONObject(0)
    assertEquals("a", word.getString("text"))
    assertEquals(125.0, word.getDouble("left"), 0.001)
    assertEquals(145.0, word.getDouble("right"), 0.001)
    assertEquals(2.0, word.getDouble("confidence"), 0.001)
    bitmap.recycle()
  }

  @Test
  fun invalidNativeConfidenceIsOmittedButRecognitionRemainsAvailable() {
    val api = FakeOcrApi(
      listOf(OcrFixture("A", Rect(4, 4, 24, 24), confidence = 101f)),
      listOf(OcrWordEvidence("A", Rect(4, 4, 24, 24), confidence = -1f)),
    )
    val engine = OcrEngine(RuntimeEnvironment.getApplication(), api = api)
    val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)

    val line = engine.recognize(bitmap, 40f, 40f, includeWordEvidence = true).getJSONObject(0)

    assertFalse(line.has("ocrConfidence"))
    assertEquals("A", line.getJSONArray("wordEvidence").getJSONObject(0).getString("text"))
    assertFalse(line.getJSONArray("wordEvidence").getJSONObject(0).has("confidence"))
    bitmap.recycle()
  }

  @Test
  fun invalidGeometryIsRejectedBeforeNativeRecognition() {
    val context = RuntimeEnvironment.getApplication()
    val api = FakeOcrApi(emptyList())
    val engine = OcrEngine(context, api = api)
    val bitmap = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888)

    assertTrue(runCatching { engine.recognize(bitmap, 0f, 2f) }.exceptionOrNull() is IllegalArgumentException)
    assertTrue(runCatching { engine.recognize(bitmap, 2f, 0f) }.exceptionOrNull() is IllegalArgumentException)
    assertTrue(runCatching { requireValidOcrGeometry(0, 5, 2f, 2f) }.exceptionOrNull() is IllegalArgumentException)
    assertTrue(runCatching { requireValidOcrGeometry(5, 0, 2f, 2f) }.exceptionOrNull() is IllegalArgumentException)
    assertEquals(0, api.setImageCalls)
    assertEquals(0, api.clearCalls)
    engine.close()
    bitmap.recycle()
  }

  @Test
  fun unsupportedLanguageAndFailedInitializationRecycleNativeHandle() {
    val context = RuntimeEnvironment.getApplication()
    val unsupported = FakeOcrApi(emptyList())
    val unsupportedFailure = runCatching { OcrEngine(context, "ar", unsupported) }.exceptionOrNull()
    assertTrue(unsupportedFailure is IllegalArgumentException)
    assertTrue(unsupportedFailure?.message.orEmpty().contains("No bundled OCR data"))
    assertEquals(1, unsupported.recycleCalls)

    val failedInitialization = FakeOcrApi(emptyList()).apply {
      initializeResult = false
      recycleFailure = IllegalStateException("cleanup failed")
    }
    val initFailure = runCatching { OcrEngine(context, "en", failedInitialization) }.exceptionOrNull()
    assertTrue(initFailure is IllegalStateException)
    assertTrue(initFailure?.message.orEmpty().contains("could not be initialized"))
    assertEquals(1, failedInitialization.recycleCalls)
  }

  @Test
  fun iteratorCreationOrRecognitionFailureStillClearsTesseractState() {
    val context = RuntimeEnvironment.getApplication()
    val iteratorFailure = FakeOcrApi(emptyList()).apply { iteratorFailure = IllegalStateException("iterator unavailable") }
    val first = OcrEngine(context, api = iteratorFailure)
    val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
    assertEquals("iterator unavailable", runCatching { first.recognize(bitmap, 10f, 10f) }.exceptionOrNull()?.message)
    assertEquals(1, iteratorFailure.clearCalls)

    val recognitionFailure = FakeOcrApi(emptyList()).apply { recognitionFailure = IllegalStateException("recognizer failed") }
    val second = OcrEngine(context, api = recognitionFailure)
    assertEquals("recognizer failed", runCatching { second.recognize(bitmap, 10f, 10f) }.exceptionOrNull()?.message)
    assertEquals(1, recognitionFailure.clearCalls)
    bitmap.recycle()
  }

  @Test
  fun stopAndCloseAreIdempotentAndContainNativeLifecycleErrors() {
    val api = FakeOcrApi(emptyList()).apply {
      stopFailure = IllegalStateException("stop failed")
      recycleFailure = IllegalStateException("recycle failed")
    }
    val engine = OcrEngine(RuntimeEnvironment.getApplication(), api = api)

    engine.stop()
    engine.close()
    engine.close()
    engine.stop()

    assertEquals(1, api.stopCalls)
    assertEquals(1, api.recycleCalls)
    assertTrue(runCatching { engine.recognize(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), 2f, 2f) }.isFailure)
  }

  @Test
  fun invalidCachedDataIsReplacedWithBundledVerifiedAsset() {
    val context = RuntimeEnvironment.getApplication()
    val target = java.io.File(context.filesDir, "tesseract/tessdata/eng.traineddata")
    target.parentFile!!.mkdirs()
    target.writeText("partial copy")
    val api = FakeOcrApi(emptyList())

    OcrEngine(context, api = api).close()

    assertTrue(target.isFile)
    assertTrue(target.length() > 1_000_000L)
    assertFalse(java.io.File(target.parentFile, "eng.traineddata.tmp").exists())
  }

  @Test
  fun rejectsInvalidBundledDataAndRemovesTemporaryCopy() {
    val context = RuntimeEnvironment.getApplication()
    val target = File(context.filesDir, "tesseract/tessdata/eng.traineddata")
    target.parentFile!!.mkdirs()
    target.writeText("old invalid data")
    val api = FakeOcrApi(emptyList())
    val wrongAsset: (Context, String) -> InputStream = { _, _ -> ByteArrayInputStream("wrong trained data".toByteArray()) }

    val failure = runCatching { OcrEngine(context, "en", api, wrongAsset) }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.message.orEmpty().contains("failed its integrity check"))
    assertEquals("old invalid data", target.readText())
    assertFalse(File(target.parentFile, "eng.traineddata.tmp").exists())
  }

  @Test
  fun assetOpenAndInstalledDataFailuresArePropagatedAndCleaned() {
    val context = RuntimeEnvironment.getApplication()
    val target = File(context.filesDir, "tesseract/tessdata/eng.traineddata")
    target.parentFile!!.mkdirs()
    target.writeText("old invalid data")
    val openFailure = runCatching {
      OcrEngine(context, "en", FakeOcrApi(emptyList()), { _, _ -> throw IOException("OCR asset unavailable") })
    }.exceptionOrNull()
    assertEquals("OCR asset unavailable", openFailure?.message)
    assertFalse(File(target.parentFile, "eng.traineddata.tmp").exists())

    val noOpInstall = runCatching {
      OcrEngine(context, "en", FakeOcrApi(emptyList()), replaceData = { _, _ -> })
    }.exceptionOrNull()
    assertTrue(noOpInstall is IllegalStateException)
    assertTrue(noOpInstall?.message.orEmpty().contains("could not be installed safely"))
    assertFalse(File(target.parentFile, "eng.traineddata.tmp").exists())

    target.delete()
    val absentDestination = runCatching {
      OcrEngine(context, "en", FakeOcrApi(emptyList()), replaceData = { _, _ -> })
    }.exceptionOrNull()
    assertTrue(absentDestination is IllegalStateException)
    assertFalse(target.exists())
    assertFalse(File(target.parentFile, "eng.traineddata.tmp").exists())
  }

  @Test
  fun trainedDataReadFailureRemainsPrimaryWhenAssetCloseAlsoFails() {
    val context = RuntimeEnvironment.getApplication()
    val target = File(context.filesDir, "tesseract/tessdata/eng.traineddata")
    target.parentFile!!.mkdirs()
    target.writeText("old invalid data")
    val stream = object : InputStream() {
      private var firstRead = true
      override fun read(): Int = throw IOException("trained data read failure")
      override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (firstRead) {
          firstRead = false
          buffer[offset] = 42
          return 1
        }
        throw IOException("trained data read failure")
      }
      override fun close() { throw IOException("trained data close failure") }
    }
    val api = FakeOcrApi(emptyList())

    val failure = runCatching {
      OcrEngine(context, "en", api, { _, _ -> stream })
    }.exceptionOrNull()

    assertEquals("trained data read failure", failure?.message)
    assertTrue(failure?.suppressed?.any { it.message == "trained data close failure" } == true)
    assertEquals("old invalid data", target.readText())
    assertFalse(File(target.parentFile, "eng.traineddata.tmp").exists())
    assertEquals(1, api.recycleCalls)
  }

  @Test
  fun checksumReadFailurePreservesPrimaryErrorAndSuppressesFileCloseFailure() {
    val context = RuntimeEnvironment.getApplication()
    val target = File(context.filesDir, "tesseract/tessdata/eng.traineddata")
    target.parentFile!!.mkdirs()
    target.writeText("old invalid data")
    val openFile: (File) -> InputStream = { file ->
      if (!file.name.endsWith(".tmp")) FileInputStream(file) else object : InputStream() {
        private var firstRead = true
        override fun read(): Int = throw IOException("checksum read failure")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
          if (firstRead) {
            firstRead = false
            buffer[offset] = 42
            return 1
          }
          throw IOException("checksum read failure")
        }
        override fun close() { throw IOException("checksum close failure") }
      }
    }
    val api = FakeOcrApi(emptyList())

    val failure = runCatching {
      OcrEngine(context, "en", api, openFile = openFile)
    }.exceptionOrNull()

    assertEquals("checksum read failure", failure?.message)
    assertTrue(failure?.suppressed?.any { it.message == "checksum close failure" } == true)
    assertEquals("old invalid data", target.readText())
    assertFalse(File(target.parentFile, "eng.traineddata.tmp").exists())
    assertEquals(1, api.recycleCalls)
  }

  @Test
  fun atomicDataMoveFallbackAndNonAtomicHelperPreserveReplacement() {
    val directory = Files.createTempDirectory("ocr-data-move-").toFile()
    val source = File(directory, "incoming").apply { writeText("new data") }
    val target = File(directory, "traineddata").apply { writeText("old data") }
    val attempts = mutableListOf<Boolean>()

    atomicReplaceOcrData(source, target) { from, to, atomic ->
      attempts += atomic
      if (atomic) throw AtomicMoveNotSupportedException(from.path, to.path, "test filesystem")
      Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    assertEquals(listOf(true, false), attempts)
    assertEquals("new data", target.readText())
    assertFalse(source.exists())
    val nonAtomicSource = File(directory, "non-atomic-incoming").apply { writeText("replacement") }
    moveOcrDataFile(nonAtomicSource, target, atomic = false)
    assertEquals("replacement", target.readText())
    assertFalse(nonAtomicSource.exists())
    directory.deleteRecursively()
  }

  private data class OcrFixture(val line: String?, val rectangle: Rect, val confidence: Float? = null)

  private class FakeOcrApi(lines: List<OcrFixture>, private val words: List<OcrWordEvidence> = emptyList()) : OcrEngineApi {
    var initializeResult = true
    var language: String? = null
    var dataDirectory: String? = null
    var setImageCalls = 0
    var clearCalls = 0
    var stopCalls = 0
    var recycleCalls = 0
    var iteratorFailure: Exception? = null
    var recognitionFailure: Exception? = null
    var stopFailure: Exception? = null
    var recycleFailure: Exception? = null
    val iterator = FakeOcrIterator(lines)

    override fun initialize(dataDirectory: String, language: String): Boolean {
      this.dataDirectory = dataDirectory
      this.language = language
      return initializeResult
    }
    override fun setAutomaticPageSegmentation() = Unit
    override fun setImage(bitmap: Bitmap) { setImageCalls++ }
    override fun recognize() { recognitionFailure?.let { throw it } }
    override fun resultIterator(): OcrLineIterator {
      iteratorFailure?.let { throw it }
      return iterator
    }
    override fun wordEvidence(): List<OcrWordEvidence> = words
    override fun clear() { clearCalls++ }
    override fun stop() { stopCalls++; stopFailure?.let { throw it } }
    override fun recycle() { recycleCalls++; recycleFailure?.let { throw it } }
  }

  private class FakeOcrIterator(private val lines: List<OcrFixture>) : OcrLineIterator {
    var index = 0
    var nextCalls = 0
    var deleteCalls = 0
    override fun begin() { index = 0 }
    override fun text(): String? = lines[index].line
    override fun bounds(): Rect = lines[index].rectangle
    override fun confidence(): Float? = lines[index].confidence
    override fun next(): Boolean { nextCalls++; index++; return index < lines.size }
    override fun delete() { deleteCalls++ }
  }
}

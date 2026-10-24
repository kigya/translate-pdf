package dev.kigya.translatepdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.ResultIterator
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal interface OcrLineIterator {
  fun begin()
  fun text(): String?
  fun bounds(): Rect
  fun confidence(): Float? = null
  fun next(): Boolean
  fun delete()
}

internal data class OcrWordEvidence(val text: String, val bounds: Rect, val confidence: Float?)

internal data class OcrRecognitionEvidence(val lines: JSONArray, val words: JSONArray)

internal interface OcrEngineApi {
  fun initialize(dataDirectory: String, language: String): Boolean
  fun setAutomaticPageSegmentation()
  fun setImage(bitmap: Bitmap)
  fun recognize()
  fun resultIterator(): OcrLineIterator
  fun wordEvidence(): List<OcrWordEvidence> = emptyList()
  fun clear()
  fun stop()
  fun recycle()
}

private class NativeTesseractApi : OcrEngineApi {
  private val delegate = TessBaseAPI()

  override fun initialize(dataDirectory: String, language: String): Boolean = delegate.init(dataDirectory, language)
  override fun setAutomaticPageSegmentation() = delegate.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
  override fun setImage(bitmap: Bitmap) = delegate.setImage(bitmap)
  override fun recognize() { delegate.getHOCRText(0) }
  override fun resultIterator(): OcrLineIterator {
    val iterator: ResultIterator = delegate.getResultIterator()
    return object : OcrLineIterator {
      override fun begin() = iterator.begin()
      override fun text(): String? = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
      override fun bounds(): Rect = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
      override fun confidence(): Float = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
      override fun next(): Boolean = iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
      override fun delete() = iterator.delete()
    }
  }

  override fun wordEvidence(): List<OcrWordEvidence> {
    val iterator = delegate.getResultIterator()
    val words = mutableListOf<OcrWordEvidence>()
    try {
      iterator.begin()
      do {
        val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
        val text = iterator.getUTF8Text(level)?.trim().orEmpty()
        val bounds = iterator.getBoundingRect(level)
        if (text.isNotEmpty() && bounds.width() > 0 && bounds.height() > 0) {
          words += OcrWordEvidence(text, bounds, iterator.confidence(level))
        }
      } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
    } finally {
      iterator.delete()
    }
    return words
  }
  override fun clear() = delegate.clear()
  override fun stop() = delegate.stop()
  override fun recycle() = delegate.recycle()
}

/** Bundled, offline OCR for one validated source language. */
internal class OcrEngine(
  context: Context,
  sourceLanguage: String = "en",
  private val api: OcrEngineApi = NativeTesseractApi(),
  private val openAsset: (Context, String) -> InputStream = { app, path -> app.assets.open(path) },
  private val replaceData: (File, File) -> Unit = { from, to -> atomicReplaceOcrData(from, to) },
  private val openFile: (File) -> InputStream = { FileInputStream(it) },
) : AutoCloseable {
  @Volatile private var closed = false
  private val trainedData = DATA_BY_PRODUCT_LANGUAGE[sourceLanguage]

  init {
    try {
      val selectedData = trainedData ?: throw IllegalArgumentException("No bundled OCR data is available for $sourceLanguage.")
      val data = ensureData(context, selectedData)
      check(api.initialize(data.parentFile!!.parentFile!!.absolutePath, selectedData.nameWithoutExtension)) {
        "The bundled ${selectedData.nameWithoutExtension} OCR data could not be initialized."
      }
      api.setAutomaticPageSegmentation()
    } catch (error: Exception) {
      runCatching { api.recycle() }
      throw error
    }
  }

  /** Backward-compatible line-only entry point used by the PDF merge path. */
  fun recognize(bitmap: Bitmap, pageWidth: Float, pageHeight: Float): JSONArray =
    recognize(bitmap, pageWidth, pageHeight, includeWordEvidence = false)

  fun recognize(bitmap: Bitmap, pageWidth: Float, pageHeight: Float, includeWordEvidence: Boolean): JSONArray =
    recognizeDetailed(bitmap, pageWidth, pageHeight, includeWordEvidence).lines

  /** Diagnostic-only recognition result, including tight word bounds even when a word is not line-matched. */
  fun recognizeDetailed(
    bitmap: Bitmap,
    pageWidth: Float,
    pageHeight: Float,
    includeWordEvidence: Boolean = true,
  ): OcrRecognitionEvidence {
    check(!closed) { "The OCR engine has been released." }
    requireValidOcrGeometry(bitmap.width, bitmap.height, pageWidth, pageHeight)
    val lines = JSONArray()
    val wordsJson = JSONArray()
    val scaleX = pageWidth / bitmap.width
    val scaleY = pageHeight / bitmap.height
    var iterator: OcrLineIterator? = null
    try {
      api.setImage(bitmap)
      // HOCR starts recognition and leaves TessBaseAPI.stop() able to interrupt it.
      api.recognize()
      val currentIterator = api.resultIterator()
      iterator = currentIterator
      currentIterator.begin()
      val wordEvidence = if (includeWordEvidence) api.wordEvidence() else emptyList()
      for (word in wordEvidence) {
        if (word.text.isBlank() || word.bounds.width() <= 0 || word.bounds.height() <= 0) continue
        val item = JSONObject()
          .put("text", word.text)
          .put("left", word.bounds.left * scaleX)
          .put("top", word.bounds.top * scaleY)
          .put("right", word.bounds.right * scaleX)
          .put("bottom", word.bounds.bottom * scaleY)
        val confidence = word.confidence
        if (confidence != null && confidence.isFinite() && confidence in 0f..100f) {
          item.put("confidence", confidence.toDouble())
        }
        wordsJson.put(item)
      }
      var index = 0
      do {
        val text = currentIterator.text()?.trim().orEmpty()
        val bounds: Rect = currentIterator.bounds()
        if (text.isNotBlank() && bounds.width() > 0 && bounds.height() > 0) {
          val line = JSONObject()
            .put("id", "ocr-$index")
            .put("sourceText", text)
            .put("translatedText", JSONObject.NULL)
            .put("left", bounds.left * scaleX)
            .put("top", bounds.top * scaleY)
            .put("right", bounds.right * scaleX)
            .put("bottom", bounds.bottom * scaleY)
            .put("fontSize", (bounds.height() * scaleY).coerceAtLeast(6f))
            .put("source", "ocr")
            .put("objectIndex", JSONObject.NULL)
            .put("objectIndices", JSONArray())
            .put("status", "pending")
          val confidence = currentIterator.confidence()
          if (confidence != null && confidence.isFinite() && confidence in 0f..100f) {
            line.put("ocrConfidence", confidence.toDouble())
          }
          if (includeWordEvidence) {
            line.put("wordEvidence", wordsWithinLine(bounds, wordEvidence, scaleX, scaleY))
          }
          lines.put(line)
          index++
        }
      } while (currentIterator.next())
    } finally {
      iterator?.delete()
      api.clear()
    }
    return OcrRecognitionEvidence(lines, wordsJson)
  }

  /** Safe to call from the service lifecycle while recognize() is running. */
  fun stop() {
    if (!closed) runCatching { api.stop() }
  }

  override fun close() {
    if (closed) return
    closed = true
    runCatching { api.recycle() }
  }

  private fun ensureData(context: Context, trainedData: TrainedData): File {
    val directory = File(context.filesDir, "$DATA_DIRECTORY/tessdata").apply { mkdirs() }
    val target = File(directory, trainedData.assetName)
    if (target.isFile && sha256(target, openFile) == trainedData.sha256) return target

    val temporary = File(directory, "${target.name}.tmp")
    temporary.delete()
    try {
      openAsset(context, "tessdata/${trainedData.assetName}").use { input ->
        FileOutputStream(temporary).use { output ->
          input.copyTo(output)
          output.fd.sync()
        }
      }
      check(sha256(temporary, openFile) == trainedData.sha256) { "The bundled ${trainedData.assetName} OCR data failed its integrity check." }
      replaceData(temporary, target)
      check(target.isFile && sha256(target, openFile) == trainedData.sha256) { "The ${trainedData.assetName} OCR data could not be installed safely." }
      return target
    } finally {
      temporary.delete()
    }
  }

  private fun sha256(file: File, openFile: (File) -> InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    openFile(file).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  private fun wordsWithinLine(lineBounds: Rect, words: List<OcrWordEvidence>, scaleX: Float, scaleY: Float): JSONArray {
    val output = JSONArray()
    for (word in words) {
      val intersectionWidth = (minOf(lineBounds.right, word.bounds.right) - maxOf(lineBounds.left, word.bounds.left)).coerceAtLeast(0)
      val intersectionHeight = (minOf(lineBounds.bottom, word.bounds.bottom) - maxOf(lineBounds.top, word.bounds.top)).coerceAtLeast(0)
      val wordArea = word.bounds.width().toLong() * word.bounds.height().toLong()
      if (wordArea <= 0L || intersectionWidth.toLong() * intersectionHeight / wordArea.toDouble() < WORD_LINE_OVERLAP) continue
      val item = JSONObject()
        .put("text", word.text)
        .put("left", word.bounds.left * scaleX)
        .put("top", word.bounds.top * scaleY)
        .put("right", word.bounds.right * scaleX)
        .put("bottom", word.bounds.bottom * scaleY)
      val confidence = word.confidence
      if (confidence != null && confidence.isFinite() && confidence in 0f..100f) {
        item.put("confidence", confidence.toDouble())
      }
      output.put(item)
    }
    return output
  }

  private companion object {
    const val DATA_DIRECTORY = "tesseract"
    const val WORD_LINE_OVERLAP = 0.5
    val DATA_BY_PRODUCT_LANGUAGE = mapOf(
      "en" to TrainedData("eng.traineddata", "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"),
      "ru" to TrainedData("rus.traineddata", "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225"),
      "zh-Hans" to TrainedData("chi_sim.traineddata", "a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730"),
      "fr" to TrainedData("fra.traineddata", "ced037562e8c80c13122dece28dd477d399af80911a28791a66a63ac1e3445ca"),
      "de" to TrainedData("deu.traineddata", "19d219bbb6672c869d20a9636c6816a81eb9a71796cb93ebe0cb1530e2cdb22d"),
      "es" to TrainedData("spa.traineddata", "6f2e04d02774a18f01bed44b1111f2cd7f3ba7ac9dc4373cd3f898a40ea6b464"),
    )
  }
}

internal fun atomicReplaceOcrData(
  source: File,
  target: File,
  move: (File, File, Boolean) -> Unit = { from, to, atomic ->
    moveOcrDataFile(from, to, atomic)
  },
) {
  try {
    move(source, target, true)
  } catch (_: AtomicMoveNotSupportedException) {
    move(source, target, false)
  }
}

internal fun moveOcrDataFile(source: File, target: File, atomic: Boolean) {
  if (atomic) {
    Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  } else {
    Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }
}

internal fun requireValidOcrGeometry(bitmapWidth: Int, bitmapHeight: Int, pageWidth: Float, pageHeight: Float) {
  require(bitmapWidth > 0 && bitmapHeight > 0 && pageWidth > 0 && pageHeight > 0) {
    "OCR image or page geometry is invalid."
  }
}

private data class TrainedData(val assetName: String, val sha256: String) {
  val nameWithoutExtension: String get() = assetName.removeSuffix(".traineddata")
}

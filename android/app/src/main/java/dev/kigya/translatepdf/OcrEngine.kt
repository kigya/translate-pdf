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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Bundled, offline OCR for one validated source language. */
internal class OcrEngine(context: Context, sourceLanguage: String = "en") : AutoCloseable {
  private val api = TessBaseAPI()
  @Volatile private var closed = false
  private val trainedData = DATA_BY_PRODUCT_LANGUAGE[sourceLanguage]

  init {
    try {
      val selectedData = trainedData ?: throw IllegalArgumentException("No bundled OCR data is available for $sourceLanguage.")
      val data = ensureData(context, selectedData)
      check(api.init(data.parentFile!!.parentFile!!.absolutePath, selectedData.nameWithoutExtension)) {
        "The bundled ${selectedData.nameWithoutExtension} OCR data could not be initialized."
      }
      api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
    } catch (error: Exception) {
      runCatching { api.recycle() }
      throw error
    }
  }

  fun recognize(bitmap: Bitmap, pageWidth: Float, pageHeight: Float): JSONArray {
    check(!closed) { "The OCR engine has been released." }
    require(bitmap.width > 0 && bitmap.height > 0 && pageWidth > 0 && pageHeight > 0) {
      "OCR image or page geometry is invalid."
    }
    api.setImage(bitmap)
    // HOCR starts recognition and leaves TessBaseAPI.stop() able to interrupt it.
    api.getHOCRText(0)
    val iterator: ResultIterator = api.getResultIterator()
    val lines = JSONArray()
    val scaleX = pageWidth / bitmap.width
    val scaleY = pageHeight / bitmap.height
    val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
    try {
      iterator.begin()
      var index = 0
      do {
        val text = iterator.getUTF8Text(level)?.trim().orEmpty()
        val bounds: Rect = iterator.getBoundingRect(level)
        if (text.isNotBlank() && bounds.width() > 0 && bounds.height() > 0) {
          lines.put(
            JSONObject()
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
              .put("status", "pending"),
          )
          index++
        }
      } while (iterator.next(level))
    } finally {
      iterator.delete()
      api.clear()
    }
    return lines
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
    if (target.isFile && sha256(target) == trainedData.sha256) return target

    val temporary = File(directory, "${target.name}.tmp")
    temporary.delete()
    context.assets.open("tessdata/${trainedData.assetName}").use { input ->
      FileOutputStream(temporary).use { output ->
        input.copyTo(output)
        output.fd.sync()
      }
    }
    check(sha256(temporary) == trainedData.sha256) { "The bundled ${trainedData.assetName} OCR data failed its integrity check." }
    try {
      Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    check(target.isFile && sha256(target) == trainedData.sha256) { "The ${trainedData.assetName} OCR data could not be installed safely." }
    return target
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  private companion object {
    const val DATA_DIRECTORY = "tesseract"
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

private data class TrainedData(val assetName: String, val sha256: String) {
  val nameWithoutExtension: String get() = assetName.removeSuffix(".traineddata")
}

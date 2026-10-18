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

/** Bundled, offline English OCR. All recognized text stays in the caller's private process. */
internal class OcrEngine(context: Context) : AutoCloseable {
  private val api = TessBaseAPI()
  @Volatile private var closed = false

  init {
    try {
      val data = ensureEnglishData(context)
      check(api.init(data.parentFile!!.parentFile!!.absolutePath, LANGUAGE)) {
        "The bundled English OCR data could not be initialized."
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

  private fun ensureEnglishData(context: Context): File {
    val directory = File(context.filesDir, "$DATA_DIRECTORY/tessdata").apply { mkdirs() }
    val target = File(directory, "$LANGUAGE.traineddata")
    if (target.isFile && sha256(target) == EXPECTED_SHA256) return target

    val temporary = File(directory, "${target.name}.tmp")
    temporary.delete()
    context.assets.open(ASSET_PATH).use { input ->
      FileOutputStream(temporary).use { output ->
        input.copyTo(output)
        output.fd.sync()
      }
    }
    check(sha256(temporary) == EXPECTED_SHA256) { "The bundled English OCR data failed its integrity check." }
    try {
      Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    check(target.isFile && sha256(target) == EXPECTED_SHA256) { "The English OCR data could not be installed safely." }
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
    const val ASSET_PATH = "tessdata/eng.traineddata"
    const val DATA_DIRECTORY = "tesseract"
    const val EXPECTED_SHA256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
    const val LANGUAGE = "eng"
  }
}

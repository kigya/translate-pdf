package dev.kigya.translatepdf

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real C++ exception cleanup with the same PDFium/libc++ binaries shipped in the APK. */
@RunWith(AndroidJUnit4::class)
class NativeExportDeviceTest {
  @Test fun sourcePreservingFinalizerKeepsPageCountAndLastArtifactAfterUnfitText() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val folder = File(context.cacheDir, "source-layout-${UUID.randomUUID()}").apply { mkdirs() }
    try {
      val source = File(folder, "source.pdf")
      val pdf = PdfDocument()
      try {
        repeat(2) { index ->
          val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 200, index + 1).create())
          page.canvas.drawText("Valve", 20f, 50f, Paint().apply { textSize = 12f })
          page.canvas.drawRect(20f, 100f, 120f, 130f, Paint().apply { color = android.graphics.Color.GREEN })
          pdf.finishPage(page)
        }
        source.outputStream().use { pdf.writeTo(it) }
      } finally { pdf.close() }
      val font = FontAsset.ensure(context, File(folder, "font.ttf"))
      val shards = JSONArray()
      repeat(2) { index ->
        val blocks = JSONObject(NativeCore.readPage(source.absolutePath, index)).getJSONArray("blocks")
        assertEquals(1, blocks.length())
        assertTrue(blocks.getJSONObject(0).getJSONArray("sourceRects").length() > 0)
        blocks.getJSONObject(0).put("translatedText", "Клапан").put("status", "translated")
        val request = JSONObject().put("layoutVersion", 3).put("mode", "preserve-source")
          .put("sourcePageIndex", index).put("blocks", blocks)
        val preview = File(folder, "preview-$index.pdf")
        val result = JSONObject(NativeCore.exportPage(source.absolutePath, index, request.toString(), preview.absolutePath, font.absolutePath, ""))
        assertFalse(result.toString(), result.has("error"))
        assertEquals(1, result.getInt("pageCount"))
        val previous = preview.readBytes()
        blocks.getJSONObject(0).put("translatedText", "Ж".repeat(500))
        val rejected = JSONObject(NativeCore.exportPage(source.absolutePath, index, request.toString(), preview.absolutePath, font.absolutePath, ""))
        assertTrue("An unfit translation cannot publish English plus annotations", rejected.has("error"))
        assertArrayEquals(previous, preview.readBytes())
        blocks.getJSONObject(0).put("translatedText", "Клапан")
        val snapshot = File(folder, "blocks-$index.json").apply { writeText(request.toString()) }
        shards.put(JSONObject().put("sourcePageIndex", index).put("expectedPageCount", 1)
          .put("path", preview.absolutePath).put("blocksPath", snapshot.absolutePath))
      }
      val target = File(folder, "translated.pdf")
      val merged = JSONObject(NativeCore.mergePdf(JSONObject().put("layoutVersion", 3).put("mode", "preserve-source")
        .put("sourcePath", source.absolutePath).put("fontPath", font.absolutePath).put("shards", shards).toString(), target.absolutePath))
      assertFalse(merged.toString(), merged.has("error"))
      assertEquals(2, merged.getInt("pageCount"))
      repeat(2) { index ->
        val page = JSONObject(NativeCore.readPage(target.absolutePath, index))
        assertTrue(page.getJSONArray("blocks").getJSONObject(0).getString("text").contains("Клапан"))
        val bitmap = NativeCore.render(target.absolutePath, index, 300)
        try { assertEquals(android.graphics.Color.GREEN, bitmap.getPixel(50, 110)) } finally { bitmap.recycle() }
      }
    } finally { folder.deleteRecursively() }
  }

  @Test fun layoutFallbackExceptionsRemainReopenableOnAndroid() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val folder = File(context.cacheDir, "native-export-${UUID.randomUUID()}").apply { mkdirs() }
    try {
      val source = File(folder, "source.pdf")
      val pdf = PdfDocument()
      try {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 200, 1).create())
        page.canvas.drawText("Valve", 20f, 50f, Paint().apply { textSize = 12f })
        pdf.finishPage(page)
        source.outputStream().use { pdf.writeTo(it) }
      } finally { pdf.close() }
      val font = FontAsset.ensure(context, File(folder, "font.ttf"))
      val blocks = JSONObject(NativeCore.readPage(source.absolutePath, 0)).getJSONArray("blocks")
      assertTrue(blocks.length() > 0)
      for ((index, text) in listOf("Клапан", "Ж".repeat(200), "Клапан 🛟").withIndex()) {
        val block = JSONObject(blocks.getJSONObject(0).toString()).put("translatedText", text).put("status", "translated")
        val output = File(folder, "output-$index.pdf")
        val result = JSONObject(NativeCore.exportPage(source.absolutePath, 0, JSONArray().put(block).toString(), output.absolutePath, font.absolutePath, ""))
        assertFalse(result.toString(), result.has("error"))
        if (index > 0) assertTrue("Unsafe layout must retain a complete annotation", result.getBoolean("partial"))
        assertEquals(1, JSONObject(NativeCore.inspect(output.absolutePath)).getInt("pageCount"))
        val image = NativeCore.render(output.absolutePath, 0, 300)
        assertTrue(image.width > 0)
        image.recycle()
      }
      assertTrue("Exceptions must still be caught after layout cleanup", JSONObject(NativeCore.inspect(File(folder, "missing.pdf").absolutePath)).has("error"))
    } finally { folder.deleteRecursively() }
  }
}

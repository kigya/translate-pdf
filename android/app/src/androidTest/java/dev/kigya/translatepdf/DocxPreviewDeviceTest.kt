package dev.kigya.translatepdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.BridgeReactContext
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocxPreviewDeviceTest {
  @Test fun launcherAndConfigurationCallbacksRunOnTheAndroidRuntime() {
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    if (instrumentation.targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
      val metroUrl = requireNotNull(InstrumentationRegistry.getArguments().getString("metroUrl")) {
        "Supply the running private Metro URL for the actual Debug launch test."
      }
      val context = instrumentation.targetContext
      context.startActivity(Intent(Intent.ACTION_VIEW,
        Uri.parse("folivela://expo-development-client/?url=${Uri.encode(metroUrl)}"))
        .setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      val deadline = SystemClock.elapsedRealtime() + 60_000
      var loaded = false
      while (!loaded && SystemClock.elapsedRealtime() < deadline) {
        instrumentation.runOnMainSync {
          loaded = (context.applicationContext as MainApplication).reactHost.currentReactContext?.hasActiveReactInstance() == true
        }
        if (!loaded) Thread.sleep(100)
      }
      assertTrue("The real Debug React application must load before lifecycle assertions", loaded)
    }
    ActivityScenario.launch(MainActivity::class.java).use {scenario->
      scenario.onActivity {activity->
        assertNotNull(activity.window)
        assertNotNull(activity.findViewById<android.view.View>(android.R.id.content))
        val application=activity.application as MainApplication
        assertNotNull(application.reactHost)
        application.onConfigurationChanged(android.content.res.Configuration(activity.resources.configuration))
      }
    }
  }

  private fun fixture(folder:File):File {
    val document=File(folder,"source.docx")
    val parts=mapOf(
      "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
      "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
      "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>First translated page</w:t><w:br w:type="page"/><w:t>Second translated page</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""",
    )
    ZipOutputStream(document.outputStream()).use {zip->
      for((name,text) in parts) {zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry()}
    }
    return document
  }

  private fun withPreview(test:(PreviewTestActivity,File,File)->Unit) {
    val context=InstrumentationRegistry.getInstrumentation().targetContext
    val folder=File(context.cacheDir,"renderer-device-test-${UUID.randomUUID()}").apply {mkdirs()}
    ActivityScenario.launch(PreviewTestActivity::class.java).use {scenario->
      lateinit var activity:PreviewTestActivity
      scenario.onActivity {activity=it}
      val source=fixture(folder)
      val bytes=source.readBytes()
      try {test(activity,source,File(folder,"preview.pdf"));assertArrayEquals(bytes,source.readBytes())}
      finally {folder.deleteRecursively()}
    }
  }

  @Test fun realWordPreviewWritesTwoReopenablePdfPages()=withPreview {activity,source,output->
    assertEquals(2,DocxPreviewRenderer(activity,activity).render(source,output))
    ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_ONLY).use {descriptor->
      PdfRenderer(descriptor).use {pdf->
        assertEquals(2,pdf.pageCount)
        for(index in 0 until pdf.pageCount) pdf.openPage(index).use {page->assertTrue(page.width>0 && page.height>0)}
      }
    }
    assertFalse(File(output.parentFile,"${output.name}.tmp").exists())
  }

  @Test fun moduleUsesRealOriginalAndTranslatedCachedPdfFiles() {
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    val fixtureId = UUID.randomUUID().toString()
    val documentId = "device-preview-$fixtureId"
    val jobId = "device-preview-job-$fixtureId"
    val scratch = File(appContext.cacheDir, "docx-module-preview-$fixtureId").apply { check(mkdirs()) }
    val source = File(appContext.filesDir, "documents/$documentId.docx").apply { parentFile?.mkdirs() }
    val translatedDocx = File(appContext.filesDir, "translated-docx/$jobId.docx").apply { parentFile?.mkdirs() }
    val originalCache = File(appContext.cacheDir, "docx-preview-pdf/renderer-v5-original-$documentId.pdf").apply { parentFile?.mkdirs() }
    val translatedCache = File(appContext.cacheDir, "docx-preview-pdf/renderer-v5-translated-$jobId.pdf").apply { parentFile?.mkdirs() }
    val reactContext = BridgeReactContext(appContext)
    val database = TranslatorDatabase(reactContext)
    val module = PdfTranslatorModule(reactContext)
    try {
      fixture(scratch).copyTo(source, overwrite = true)
      fixture(scratch).copyTo(translatedDocx, overwrite = true)
      val sourceBytes = source.readBytes()
      val translatedDocxBytes = translatedDocx.readBytes()
      writeReopenablePdf(originalCache, pageCount = 2)
      writeReopenablePdf(translatedCache, pageCount = 3)
      val originalBytes = originalCache.readBytes()
      val translatedBytes = translatedCache.readBytes()
      database.upsertDocument(documentId, source.name, source.absolutePath, 1, "instrumented-docx-$fixtureId", "docx", 1)
      database.addJob(jobId, JSONObject()
        .put("documentId", documentId)
        .put("modelId", ModelCatalog.RECOMMENDED_ID)
        .put("sourceLanguage", "en")
        .put("targetLanguage", "ru"), 1)
      database.updateJob(jobId, status = "completed")
      assertNull("a validated cache must not need a foreground Activity", reactContext.currentActivity)

      val original = PromiseCapture()
      module.renderDocxPreview(documentId, null, false, original.promise)
      assertPreviewResult(original.await(), expectedPages = 2)

      val translated = PromiseCapture()
      module.renderDocxPreview(documentId, jobId, true, translated.promise)
      assertPreviewResult(translated.await(), expectedPages = 3)

      assertTrue("original cache remains available after a cache hit", originalCache.isFile)
      assertTrue("translated cache remains available after a cache hit", translatedCache.isFile)
      assertArrayEquals("original cache bytes are reused without rewriting", originalBytes, originalCache.readBytes())
      assertArrayEquals("translated cache bytes are reused without rewriting", translatedBytes, translatedCache.readBytes())
      assertArrayEquals("original Word source is unchanged", sourceBytes, source.readBytes())
      assertArrayEquals("translated Word source is unchanged", translatedDocxBytes, translatedDocx.readBytes())
      assertTrue("both cached files are valid PDFs", isReopenablePdf(originalCache, 2) && isReopenablePdf(translatedCache, 3))
    } finally {
      module.invalidate()
      database.deleteJob(jobId)
      database.deleteDocument(documentId)
      database.close()
      source.delete()
      translatedDocx.delete()
      originalCache.delete()
      translatedCache.delete()
      scratch.deleteRecursively()
    }
  }

  private fun writeReopenablePdf(file: File, pageCount: Int) {
    val pdf = PdfDocument()
    try {
      repeat(pageCount) { index ->
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(360, 480, index + 1).create())
        page.canvas.drawText("Private preview page ${index + 1}", 18f, 36f, Paint())
        pdf.finishPage(page)
      }
      FileOutputStream(file).use(pdf::writeTo)
    } finally {
      pdf.close()
    }
  }

  private fun isReopenablePdf(file: File, expectedPages: Int): Boolean =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
      PdfRenderer(descriptor).use { it.pageCount == expectedPages }
    }

  private fun assertPreviewResult(value: Any?, expectedPages: Int) {
    assertTrue("module should resolve a JSON preview result", value is String)
    val result = JSONObject(value as String)
    assertEquals("pdf", result.getString("format"))
    assertEquals(expectedPages, result.getInt("pageCount"))
  }

  private class PromiseCapture {
    private val latch = CountDownLatch(1)
    private var value: Any? = null
    private var failure: String? = null
    val promise: Promise = Proxy.newProxyInstance(
      Promise::class.java.classLoader,
      arrayOf(Promise::class.java),
    ) { _, method, args ->
      when (method.name) {
        "resolve" -> { value = args?.firstOrNull(); latch.countDown() }
        "reject" -> { failure = args.orEmpty().filterIsInstance<String>().joinToString(": "); latch.countDown() }
      }
      null
    } as Promise

    fun await(): Any? {
      assertTrue("DOCX preview promise should settle", latch.await(20, TimeUnit.SECONDS))
      assertNull("DOCX preview should not reject: $failure", failure)
      return value
    }
  }

  @Test fun rasterFailureReturnsWithoutLeavingAnUnfinishedPdfPage() {
    for(failure in listOf<Throwable>(IllegalStateException("Injected draw failure"),OutOfMemoryError("Injected raster failure"))) {
      withPreview {activity,source,output->
        val factory:(Context)->WebView={context->object:WebView(context) {
          override fun draw(canvas:Canvas) {
            // Only fail the explicit off-screen raster, not normal attached rendering.
            if(alpha==1f) throw failure
            super.draw(canvas)
          }
        }}
        val started=android.os.SystemClock.elapsedRealtime()
        val error=runCatching {DocxPreviewRenderer(activity,activity,factory).render(source,output)}.exceptionOrNull()
        assertTrue("The renderer must return a recoverable error",error is IllegalStateException)
        assertTrue("The renderer must not wait for the60second timeout",android.os.SystemClock.elapsedRealtime()-started<55_000)
        val expected=if(failure is OutOfMemoryError) "allocate memory" else "page could not be rendered"
        assertTrue("Unexpected failure: ${error?.message}",error?.message?.contains(expected)==true)
        assertFalse(output.exists())
        assertFalse(File(output.parentFile,"${output.name}.tmp").exists())
      }
    }
  }
}

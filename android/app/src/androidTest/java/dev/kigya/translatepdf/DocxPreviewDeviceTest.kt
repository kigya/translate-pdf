package dev.kigya.translatepdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocxPreviewDeviceTest {
  @Test fun launcherAndConfigurationCallbacksRunOnTheAndroidRuntime() {
    val instrumentation=InstrumentationRegistry.getInstrumentation()
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

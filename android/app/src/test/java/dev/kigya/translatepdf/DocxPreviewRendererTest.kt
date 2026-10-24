package dev.kigya.translatepdf

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DocxPreviewRendererTest {
  private fun await(condition:()->Boolean) {
    val end=System.nanoTime()+5_000_000_000L
    while(!condition() && System.nanoTime()<end) {shadowOf(Looper.getMainLooper()).idle();Thread.yield()}
    assertTrue("Preview callback did not complete",condition())
  }

  @Test fun missingEmptyAndOversizedSourcesCannotReplaceAnExistingPreview() {
    val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
    val folder=File(activity.cacheDir,"preflight").apply {mkdirs()}
    val output=File(folder,"kept.pdf").apply {writeText("existing preview")}
    val missing=File(folder,"missing.docx")
    val empty=File(folder,"empty.docx").apply {writeText("")}
    val large=File(folder,"large.docx").apply {java.io.RandomAccessFile(this,"rw").use {it.setLength(128L*1024*1024+1)}}
    try {
      for(source in listOf(missing,empty,large)) {
        val failure=runCatching {DocxPreviewRenderer(activity,activity).render(source,output)}.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("existing preview",output.readText())
      }
    } finally {folder.deleteRecursively();activity.finish()}
  }

  @Test fun rendererUsesOnlyPrivateRoutesAndCleansUpAfterAProcessFailure() {
    val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
    val content=activity.findViewById<ViewGroup>(android.R.id.content)
    val source=File(activity.cacheDir,"private-source.docx").apply {writeText("original")}
    val output=File(activity.cacheDir,"private-preview.pdf")
    val result=AtomicReference<Result<Int>>()
    val worker=Thread {result.set(runCatching {DocxPreviewRenderer(activity,activity).render(source,output)})}
    worker.start()
    try {
      await {content.childCount>0}
      val web=content.getChildAt(0) as WebView
      val shadow=shadowOf(web)
      assertEquals(DocxPreviewResourcePolicy.ENTRY_URL,shadow.lastLoadedUrl)
      assertFalse(web.settings.allowFileAccess)
      assertFalse(web.settings.allowContentAccess)
      assertFalse(web.settings.domStorageEnabled)
      assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW,web.settings.mixedContentMode)
      assertTrue(shadow.webViewClient.shouldOverrideUrlLoading(web,null as android.webkit.WebResourceRequest?))
      assertEquals(403,shadow.webViewClient.shouldInterceptRequest(web,null as android.webkit.WebResourceRequest?)?.statusCode)
      assertTrue(shadow.webViewClient.onRenderProcessGone(web,null))
      // Duplicate Chromium callbacks must not complete or destroy twice.
      assertTrue(shadow.webViewClient.onRenderProcessGone(web,null))
      await {!worker.isAlive}
      assertTrue(result.get().exceptionOrNull()?.message?.contains("translation remains saved") == true)
      assertTrue(shadow.wasDestroyCalled())
      assertNull(shadow.getJavascriptInterface("FolivelaPreview"))
      assertEquals(0,content.childCount)
      assertEquals("original",source.readText())
      assertFalse(output.exists())
      assertTrue(File(activity.cacheDir,"docx-preview").listFiles().orEmpty().isEmpty())
    } finally {worker.interrupt();worker.join(5000);shadowOf(Looper.getMainLooper()).idle();source.delete();activity.finish()}
  }

  @Test fun finishingActivityRejectsPreviewAndPreservesSource() {
    val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
    activity.finish()
    val source=File(activity.cacheDir,"finished.docx").apply {writeText("keep")}
    val result=AtomicReference<Result<Int>>()
    val worker=Thread {result.set(runCatching {DocxPreviewRenderer(activity,activity).render(source,File(activity.cacheDir,"finished.pdf"))})}
    worker.start()
    try {
      await {!worker.isAlive}
      assertTrue(result.get().exceptionOrNull()?.message?.contains("Open Folivela") == true)
      assertEquals("keep",source.readText())
    } finally {worker.interrupt();worker.join(5000);source.delete()}
  }

  @Test fun missingActivityContentDestroysWebViewAndPreservesTheSource() {
    val activity=Robolectric.buildActivity(WindowlessPreviewActivity::class.java).create().get()
    val source=File(activity.cacheDir,"unattached.docx").apply {writeText("keep")}
    val created=AtomicReference<WebView?>()
    val result=AtomicReference<Result<Int>>()
    val worker=Thread {
      result.set(runCatching {DocxPreviewRenderer(activity,activity,{ context -> WebView(context).also(created::set) })
        .render(source,File(activity.cacheDir,"unattached.pdf"))})
    }
    worker.start()
    try {
      await {!worker.isAlive}
      assertTrue(result.get().exceptionOrNull()?.message?.contains("Open Folivela") == true)
      assertTrue(requireNotNull(created.get()).let { shadowOf(it).wasDestroyCalled() })
      assertEquals("keep",source.readText())
      assertTrue(File(activity.cacheDir,"docx-preview").listFiles().orEmpty().isEmpty())
    } finally {worker.interrupt();worker.join(5000);shadowOf(Looper.getMainLooper()).idle();source.delete();activity.finish()}
  }

  @Test fun interruptAfterWebViewAttachmentDetachesAndDestroysPreviewWithoutChangingPriorOutput() {
    val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
    val content=activity.findViewById<ViewGroup>(android.R.id.content)
    val source=File(activity.cacheDir,"interrupt-attached.docx").apply {writeText("original")}
    val output=File(activity.cacheDir,"interrupt-attached.pdf").apply {writeText("previous result")}
    val result=AtomicReference<Result<Int>>()
    val worker=Thread {result.set(runCatching {DocxPreviewRenderer(activity,activity).render(source,output)})}
    worker.start()
    try {
      await {content.childCount>0}
      val web=content.getChildAt(0) as WebView
      worker.interrupt()
      await {!worker.isAlive}
      shadowOf(Looper.getMainLooper()).idle()
      assertTrue(result.get().exceptionOrNull()?.message?.contains("canceled") == true)
      assertTrue(shadowOf(web).wasDestroyCalled())
      assertEquals(0,content.childCount)
      assertEquals("previous result",output.readText())
      assertEquals("original",source.readText())
      assertTrue(File(activity.cacheDir,"docx-preview").listFiles().orEmpty().isEmpty())
    } finally {worker.interrupt();worker.join(5000);shadowOf(Looper.getMainLooper()).idle();source.delete();output.delete();activity.finish()}
  }

  @Test fun interruptBeforeMainThreadCreationCancelsQueuedPreviewWork() {
    val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
    val source=File(activity.cacheDir,"interrupted.docx").apply {writeText("keep")}
    val result=AtomicReference<Result<Int>>()
    val worker=Thread {
      Thread.currentThread().interrupt()
      result.set(runCatching {DocxPreviewRenderer(activity,activity).render(source,File(activity.cacheDir,"interrupted.pdf"))})
    }
    worker.start();worker.join(5000)
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(result.get().exceptionOrNull()?.message?.contains("canceled") == true)
    assertEquals(0,activity.findViewById<ViewGroup>(android.R.id.content).childCount)
    assertEquals("keep",source.readText());source.delete();activity.finish()
  }

  private fun previewCallback(run:(WebView)->Unit):Throwable {
    val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
    val source=File(activity.cacheDir,"callback.docx").apply {writeText("keep")}
    val output=File(activity.cacheDir,"callback.pdf")
    val content=activity.findViewById<ViewGroup>(android.R.id.content)
    val result=AtomicReference<Result<Int>>()
    val worker=Thread {result.set(runCatching {DocxPreviewRenderer(activity,activity).render(source,output)})}
    worker.start()
    try {
      await {content.childCount>0}
      val web=content.getChildAt(0) as WebView
      run(web)
      await {!worker.isAlive}
      assertEquals("keep",source.readText())
      assertFalse(output.exists())
      assertEquals(0,content.childCount)
      assertTrue(shadowOf(web).wasDestroyCalled())
      return requireNotNull(result.get().exceptionOrNull())
    } finally {worker.interrupt();worker.join(5000);shadowOf(Looper.getMainLooper()).idle();source.delete();activity.finish()}
  }

  @Test fun documentFailureCallbackCannotExposeUntrustedErrorDetails() {
    val error=previewCallback {web->
      val bridge=shadowOf(web).getJavascriptInterface("FolivelaPreview")
      bridge.javaClass.getMethod("failed",String::class.java).invoke(bridge,"private text https://attacker.invalid")
    }
    assertEquals("The Word preview could not be prepared.",error.message)
  }

  @Test fun readinessCallbackAfterFailureIsIgnoredAndDoesNotStartRasterization() {
    val error=previewCallback {web->
      val bridge=shadowOf(web).getJavascriptInterface("FolivelaPreview")
      bridge.javaClass.getMethod("failed",String::class.java).invoke(bridge,"ignored")
      bridge.javaClass.getMethod("ready").invoke(bridge)
    }
    assertEquals("The Word preview could not be prepared.",error.message)
  }

  @Test
  @Config(sdk = [28], application = Application::class)
  fun previewSettingsRemainUsableBeforeAndroidTen() {
    val error=previewCallback {web->
      assertFalse(web.settings.allowFileAccess)
      assertFalse(web.settings.allowContentAccess)
      val bridge=shadowOf(web).getJavascriptInterface("FolivelaPreview")
      bridge.javaClass.getMethod("failed",String::class.java).invoke(bridge,"fixture")
    }
    assertEquals("The Word preview could not be prepared.",error.message)
  }

  @Test
  @Config(sdk = [29], application = Application::class)
  fun previewSettingsDisableForceDarkBeforeAlgorithmicDarkeningApi() {
    val error=previewCallback {web->
      assertEquals(WebSettings.FORCE_DARK_OFF,web.settings.forceDark)
      val bridge=shadowOf(web).getJavascriptInterface("FolivelaPreview")
      bridge.javaClass.getMethod("failed",String::class.java).invoke(bridge,"fixture")
    }
    assertEquals("The Word preview could not be prepared.",error.message)
  }

  @Test fun malformedAndOversizedPageMetricsAreRejectedBeforeRasterAllocation() {
    val cases=listOf(
      null to "did not expose page layout",
      "null" to "did not expose page layout",
      "{}" to "did not expose page layout",
      org.json.JSONObject.quote("not-json") to "Value not-json",
      org.json.JSONObject.quote("""{"dpr":900,"pages":[]}""") to "scale could not be matched",
      org.json.JSONObject.quote("""{"dpr":1,"pages":[]}""") to "unsupported page count",
      org.json.JSONObject.quote("""{"dpr":1,"pages":[{"w":1601,"h":100}]}""") to "safe preview dimensions",
      org.json.JSONObject.quote("""{"dpr":1,"pages":[{"w":100,"h":2201}]}""") to "safe preview dimensions",
      org.json.JSONObject.quote("""{"dpr":1,"pages":[{"w":0,"h":100}]}""") to "safe preview dimensions",
      org.json.JSONObject.quote("""{"dpr":1,"pages":[{"w":100,"h":0}]}""") to "safe preview dimensions",
    )
    for((encoded,expected) in cases) {
      val error=previewCallback {web->
        val shadow=shadowOf(web)
        val bridge=shadow.getJavascriptInterface("FolivelaPreview")
        bridge.javaClass.getMethod("ready").invoke(bridge)
        await {shadow.lastEvaluatedJavascriptCallback!=null}
        shadow.lastEvaluatedJavascriptCallback.onReceiveValue(encoded)
      }
      assertTrue("Unexpected preview failure: ${error.message}",error.message?.contains(expected)==true)
    }
  }

  @Test
  @Config(sdk = [37], qualifiers = "xxhdpi", application = Application::class)
  fun pageRasterPixelLimitUsesActualDisplayDensity() {
    val error=previewCallback {web->
      val shadow=shadowOf(web)
      val bridge=shadow.getJavascriptInterface("FolivelaPreview")
      bridge.javaClass.getMethod("ready").invoke(bridge)
      await {shadow.lastEvaluatedJavascriptCallback!=null}
      val density=web.resources.displayMetrics.density.coerceAtLeast(1f)
      val metrics=org.json.JSONObject.quote("""{"dpr":$density,"pages":[{"w":1600,"h":2200}]}""")
      shadow.lastEvaluatedJavascriptCallback.onReceiveValue(metrics)
    }
    assertTrue("Unexpected preview failure: ${error.message}",error.message?.contains("safe preview memory limit")==true)
  }

  @Test fun pageSelectionMismatchRejectsThePreviewWithoutCachingAPdf() {
    val error=previewCallback {web->
      val shadow=shadowOf(web)
      val bridge=shadow.getJavascriptInterface("FolivelaPreview")
      bridge.javaClass.getMethod("ready").invoke(bridge)
      await {shadow.lastEvaluatedJavascriptCallback!=null}
      val density=web.resources.displayMetrics.density.coerceAtLeast(1f)
      shadow.lastEvaluatedJavascriptCallback.onReceiveValue(org.json.JSONObject.quote("""{"dpr":$density,"pages":[{"w":100,"h":100}]}"""))
      assertTrue(shadow.lastEvaluatedJavascript.contains("window.scrollTo"))
      shadow.lastEvaluatedJavascriptCallback.onReceiveValue("0")
    }
    assertTrue(error.message?.contains("page could not be selected")==true)
  }

}

private class WindowlessPreviewActivity : Activity() {
  override fun getWindow(): android.view.Window? = null
}

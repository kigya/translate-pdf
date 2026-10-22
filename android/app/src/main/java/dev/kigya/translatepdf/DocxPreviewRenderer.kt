package dev.kigya.translatepdf

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Sandboxed, local-only docx-preview renderer. It never exposes document bytes to React Native. */
internal class DocxPreviewRenderer(private val context: Context, private val activity: Activity,
                                   private val createWebView: (Context) -> WebView = ::WebView) {
  companion object {
    private const val MAX_SOURCE_BYTES = 128L * 1024L * 1024L
    private const val MAX_PDF_BYTES = 256L * 1024L * 1024L
    private const val MAX_PAGES = 10_000
    private const val MAX_PAGE_CSS_WIDTH = 1_600.0
    private const val MAX_PAGE_CSS_HEIGHT = 2_200.0
    private const val MAX_PAGE_PIXELS = 12_000_000.0
    private const val CSS_TO_PDF_POINTS = 72.0 / 96.0
  }

  fun render(source: File, output: File): Int {
    require(source.isFile && source.length() in 1..MAX_SOURCE_BYTES) { "The Word document is unavailable or exceeds the preview limit." }
    output.parentFile?.mkdirs()
    val temporary = File(output.parentFile, "${output.name}.tmp")
    temporary.delete()
    val documentCopy = File(context.cacheDir, "docx-preview/document-${UUID.randomUUID()}.docx").apply { parentFile?.mkdirs() }
    try { FileInputStream(source).use { input -> FileOutputStream(documentCopy).use { outputStream -> input.copyTo(outputStream) } } }
    catch (error: Exception) { documentCopy.delete(); throw error }
    val latch = CountDownLatch(1)
    val pageCount = AtomicReference<Int?>(null)
    val failure = AtomicReference<Throwable?>(null)
    val webReference = AtomicReference<WebView?>(null)
    val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    Handler(Looper.getMainLooper()).post {
      if (cancelled.get()) { documentCopy.delete(); return@post }
      val web = createWebView(context)
      webReference.set(web)
      val content = activity.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
      if (content == null || activity.isFinishing || activity.isDestroyed) {
        web.destroy()
        documentCopy.delete()
        failure.set(IllegalStateException("Open Folivela before preparing the Word preview."))
        latch.countDown()
        return@post
      }
      // WebView animation/layout callbacks and rasterization do not advance
      // reliably while detached or outside the visible bounds. Keep it attached
      // behind the app content at 1% opacity so Chromium produces real pixels
      // without showing or intercepting the document preview.
      val display = context.resources.displayMetrics
      val attachedWidth = display.widthPixels.coerceAtLeast(1)
      val attachedHeight = display.heightPixels.coerceAtLeast(1)
      web.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
      web.isFocusable = false
      web.isFocusableInTouchMode = false
      web.alpha = 0.01f
      web.isClickable = false
      web.isLongClickable = false
      content.addView(web, 0, ViewGroup.LayoutParams(attachedWidth, attachedHeight))
      val finished = java.util.concurrent.atomic.AtomicBoolean(false)
      fun finish(value: Int? = null, error: Throwable? = null) {
        if (!finished.compareAndSet(false, true)) return
        cancelled.set(true)
        if (value != null) pageCount.set(value)
        if (error != null) failure.set(error)
        Handler(Looper.getMainLooper()).post {
          web.stopLoading()
          web.removeJavascriptInterface("FolivelaPreview")
          (web.parent as? ViewGroup)?.removeView(web)
          web.destroy()
          documentCopy.delete()
          latch.countDown()
        }
      }
      web.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        useWideViewPort = true
        loadWithOverviewMode = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
          forceDark = WebSettings.FORCE_DARK_OFF
          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            isAlgorithmicDarkeningAllowed = false
          }
        }
      }
      web.addJavascriptInterface(object {
        @JavascriptInterface fun ready() {
          Handler(Looper.getMainLooper()).post {
            if (cancelled.get()) return@post
            renderPagesToPdf(web, temporary, cancelled) { result ->
              result.fold({ finish(value = it) }, { finish(error = it) })
            }
          }
        }
        @JavascriptInterface fun failed(@Suppress("UNUSED_PARAMETER") safeMessage: String) {
          Handler(Looper.getMainLooper()).post { finish(error = IllegalStateException("The Word preview could not be prepared.")) }
        }
      }, "FolivelaPreview")
      web.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
          DocxPreviewResourcePolicy.intercept(context, documentCopy, request?.url?.toString())

        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
          finish(error = IllegalStateException("The Word preview stopped unexpectedly. Your translation remains saved."))
          return true
        }
      }
      web.loadUrl(DocxPreviewResourcePolicy.ENTRY_URL)
    }
    val completed = try { latch.await(60, TimeUnit.SECONDS) }
    catch (interrupted: InterruptedException) {
      cancelled.set(true)
      Handler(Looper.getMainLooper()).post {
        webReference.getAndSet(null)?.let { it.stopLoading(); (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        documentCopy.delete()
      }
      temporary.delete()
      Thread.currentThread().interrupt()
      throw IllegalStateException("The Word preview was canceled.", interrupted)
    }
    if (!completed) {
      cancelled.set(true)
      Handler(Looper.getMainLooper()).post {
        webReference.getAndSet(null)?.let { it.stopLoading(); (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        documentCopy.delete()
      }
      temporary.delete()
      throw IllegalStateException("The Word preview timed out.")
    }
    failure.get()?.let { temporary.delete(); throw IllegalStateException(it.message ?: "The Word preview could not be prepared.") }
    val count = pageCount.get() ?: run { temporary.delete(); throw IllegalStateException("The Word preview did not produce a PDF.") }
    require(temporary.isFile && temporary.length() > 0L) { "The Word preview did not produce a PDF." }
    require(temporary.length() <= MAX_PDF_BYTES && count <= MAX_PAGES) { temporary.delete(); "The Word preview exceeds the safe size or page limit." }
    check(temporary.renameTo(output)) { temporary.delete(); "The Word preview could not be cached." }
    return count
  }

  /** Draws the already-laid-out docx-preview pages into a private offline PDF cache. */
  private fun renderPagesToPdf(web: WebView, file: File, cancelled: java.util.concurrent.atomic.AtomicBoolean,
                               complete: (Result<Int>) -> Unit) {
    val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    web.setLayerType(WebView.LAYER_TYPE_SOFTWARE, null)
    web.settings.textZoom = 100
    val metricsScript = """(function(){return JSON.stringify({dpr:window.devicePixelRatio,viewport:window.innerWidth,pages:Array.from(document.querySelectorAll('.docx-wrapper > section.docx')).map(function(s){var r=s.getBoundingClientRect();return {x:r.left+window.scrollX,y:r.top+window.scrollY,w:r.width,h:r.height};})});})()"""
    web.evaluateJavascript(metricsScript) { encoded ->
      val document = PdfDocument()
      try {
        val json = org.json.JSONTokener(encoded ?: "null").nextValue() as? String
          ?: throw IllegalStateException("The Word preview did not expose page layout.")
        val layout = org.json.JSONObject(json)
        val actualDensity = layout.getDouble("dpr")
        require(kotlin.math.abs(actualDensity - density.toDouble()) <= 0.2) {
          "The Word preview scale could not be matched to the device display."
        }
        val metrics = layout.getJSONArray("pages")
        require(metrics.length() in 1..MAX_PAGES) { "The Word preview has an unsupported page count." }
        val sizes = (0 until metrics.length()).map { index ->
          val item = metrics.getJSONObject(index)
          val width = item.getDouble("w"); val height = item.getDouble("h")
          require(width in 1.0..MAX_PAGE_CSS_WIDTH && height in 1.0..MAX_PAGE_CSS_HEIGHT) {
            "A Word page exceeds the safe preview dimensions."
          }
          require(ceil(width * density) * ceil(height * density) <= MAX_PAGE_PIXELS) {
            "A Word page exceeds the safe preview memory limit."
          }
          width to height
        }
        val pageIndex = intArrayOf(0)
        fun drawNext() {
          if (cancelled.get()) {
            document.close(); file.delete()
            return complete(Result.failure(IllegalStateException("The Word preview was canceled.")))
          }
          if (pageIndex[0] == metrics.length()) {
            Thread {
              try {
                FileOutputStream(file).use { output -> document.writeTo(output); output.fd.sync() }
                document.close()
                val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val count = try { PdfRenderer(descriptor).use { it.pageCount } } finally { descriptor.close() }
                require(file.length() in 1..MAX_PDF_BYTES && count in 1..MAX_PAGES) { "The Word preview exceeds the safe PDF limits." }
                Handler(Looper.getMainLooper()).post { complete(Result.success(count)) }
              } catch (_: Exception) {
                document.close(); file.delete()
                Handler(Looper.getMainLooper()).post {
                  complete(Result.failure(IllegalStateException("The Word preview PDF could not be written.")))
                }
              }
            }.start()
            return
          }
          val index = pageIndex[0]
          val cssWidth = sizes[index].first
          val cssHeight = sizes[index].second
          val widthPx = ceil(cssWidth * density).toInt()
          val heightPx = ceil(cssHeight * density).toInt()
          val pdfWidth = (cssWidth * CSS_TO_PDF_POINTS).roundToInt().coerceAtLeast(1)
          val pdfHeight = (cssHeight * CSS_TO_PDF_POINTS).roundToInt().coerceAtLeast(1)
          val selectPageScript = """(function(){var p=Array.from(document.querySelectorAll('.docx-wrapper > section.docx'));p.forEach(function(s,i){s.style.setProperty('display',i===$index?'block':'none','important');if(i===$index){s.style.setProperty('position','absolute','important');s.style.setProperty('left','0px','important');s.style.setProperty('top','0px','important');s.style.setProperty('margin','0px','important');}});window.scrollTo(0,0);return p.length;})()"""
          web.evaluateJavascript(selectPageScript) { selectedCount ->
            if (cancelled.get()) { drawNext(); return@evaluateJavascript }
            val selected = selectedCount?.trim('"')?.toIntOrNull()
            if (selected != metrics.length()) {
              document.close(); file.delete()
              return@evaluateJavascript complete(Result.failure(IllegalStateException("A Word preview page could not be selected.")))
            }
            web.measure(android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
              android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY))
            web.layout(0, 0, widthPx, heightPx)
            web.scrollTo(0, 0)
            web.invalidate()
            web.postVisualStateCallback(index.toLong() + 1L, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
            if (cancelled.get()) { drawNext(); return }
            var bitmap: Bitmap? = null
            try {
              // Rasterize before opening a PDF page: allocation/Chromium failure
              // must leave the document closable and the callback recoverable.
              bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
              val raster = Canvas(bitmap!!)
              raster.drawColor(Color.WHITE)
              val previewAlpha = web.alpha
              try {
                web.alpha = 1f
                web.draw(raster)
              } finally {
                web.alpha = previewAlpha
              }
              val info = PdfDocument.PageInfo.Builder(pdfWidth, pdfHeight, index + 1).create()
              val page = document.startPage(info)
              try {
                page.canvas.drawBitmap(bitmap!!, null, Rect(0, 0, pdfWidth, pdfHeight), null)
              } finally {
                document.finishPage(page)
              }
              pageIndex[0]++
              drawNext()
            } catch (_: OutOfMemoryError) {
              document.close(); file.delete()
              complete(Result.failure(IllegalStateException("Android could not allocate memory to render this Word page.")))
            } catch (_: Exception) {
              document.close(); file.delete()
              complete(Result.failure(IllegalStateException("The Word preview page could not be rendered.")))
            } finally {
              bitmap?.recycle()
            }
            }
            })
          }
        }
        drawNext()
      } catch (error: Exception) {
        document.close(); file.delete()
        complete(Result.failure(error))
      }
    }
  }
}

internal object DocxPreviewResourcePolicy {
  const val ENTRY_URL = "https://folivela.local/index.html"

  fun intercept(context: Context, document: File, rawUrl: String?): WebResourceResponse {
    val url = runCatching { android.net.Uri.parse(rawUrl ?: "") }.getOrNull()
    val safe = isAllowedUrl(rawUrl)
    val path = if (safe) url?.path else null
    if (!isAllowedPath(path)) return blocked()
    val file = when (path) {
      "/index.html" -> "docx-preview/index.html"
      "/jszip.min.js" -> "docx-preview/jszip.min.js"
      "/docx-preview.min.js" -> "docx-preview/docx-preview.min.js"
      "/document.docx" -> null
      else -> return blocked()
    }
    return try {
      val stream = if (path == "/document.docx") document.inputStream() else context.assets.open(file!!)
      val mime = when (path) {
        "/index.html" -> "text/html"
        "/jszip.min.js", "/docx-preview.min.js" -> "application/javascript"
        else -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
      }
      WebResourceResponse(mime, if (path == "/document.docx") null else "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), stream)
    } catch (_: Exception) { blocked() }
  }

  internal fun isAllowedPath(path: String?): Boolean = path in setOf("/index.html", "/jszip.min.js", "/docx-preview.min.js", "/document.docx")

  internal fun isAllowedUrl(rawUrl: String?): Boolean {
    val uri = runCatching { android.net.Uri.parse(rawUrl ?: "") }.getOrNull() ?: return false
    return uri.scheme == "https" && uri.host == "folivela.local" && uri.userInfo == null &&
      uri.port == -1 && uri.query == null && uri.fragment == null && isAllowedPath(uri.path)
  }

  private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"), "".byteInputStream())
}

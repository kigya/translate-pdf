package dev.kigya.translatepdf

import android.app.Application
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DocxPreviewResourcePolicyTest {
  @Test
  fun onlyLocalRendererAssetsAndPrivateDocumentRouteAreAllowed() {
    assertTrue(DocxPreviewResourcePolicy.isAllowedPath("/index.html"))
    assertTrue(DocxPreviewResourcePolicy.isAllowedPath("/jszip.min.js"))
    assertTrue(DocxPreviewResourcePolicy.isAllowedPath("/docx-preview.min.js"))
    assertTrue(DocxPreviewResourcePolicy.isAllowedPath("/document.docx"))
    assertFalse(DocxPreviewResourcePolicy.isAllowedPath("/images/picture.png"))
    assertFalse(DocxPreviewResourcePolicy.isAllowedPath("/evil.js"))
    assertFalse(DocxPreviewResourcePolicy.isAllowedPath(null))
    assertTrue(DocxPreviewResourcePolicy.isAllowedUrl(DocxPreviewResourcePolicy.ENTRY_URL))
    assertTrue(DocxPreviewResourcePolicy.isAllowedUrl("https://folivela.local/document.docx"))
    assertFalse(DocxPreviewResourcePolicy.isAllowedUrl("https://example.com/document.docx"))
    assertFalse(DocxPreviewResourcePolicy.isAllowedUrl("http://folivela.local/index.html"))
    assertFalse(DocxPreviewResourcePolicy.isAllowedUrl("https://user@folivela.local/index.html"))
    assertFalse(DocxPreviewResourcePolicy.isAllowedUrl("https://folivela.local/document.docx?external=1"))
    assertFalse(DocxPreviewResourcePolicy.isAllowedUrl("https://folivela.local/external.png"))
  }

  @Test
  fun privateRoutesServeOnlyBundledAssetsOrTheSelectedDocumentWithoutCaching() {
    val context = RuntimeEnvironment.getApplication() as Application
    val document = File(context.cacheDir, "resource-policy.docx").apply { writeText("private document fixture") }
    try {
      for ((route, asset, mime) in listOf(
        Triple("index.html", "docx-preview/index.html", "text/html"),
        Triple("jszip.min.js", "docx-preview/jszip.min.js", "application/javascript"),
        Triple("docx-preview.min.js", "docx-preview/docx-preview.min.js", "application/javascript"),
      )) {
        val response = DocxPreviewResourcePolicy.intercept(context, document, "https://folivela.local/$route")
        assertEquals(200, response.statusCode)
        assertEquals(mime, response.mimeType)
        assertEquals("UTF-8", response.encoding)
        assertEquals("no-store", response.responseHeaders["Cache-Control"])
        assertEquals("nosniff", response.responseHeaders["X-Content-Type-Options"])
        val expected = context.assets.open(asset).use { it.readBytes() }
        org.junit.Assert.assertArrayEquals(expected, response.data.use { it.readBytes() })
      }
      val response = DocxPreviewResourcePolicy.intercept(context, document, "https://folivela.local/document.docx")
      assertEquals(200, response.statusCode)
      assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", response.mimeType)
      assertNull(response.encoding)
      assertEquals("private document fixture", response.data.bufferedReader().use { it.readText() })
    } finally { document.delete() }
  }

  @Test
  fun externalAndDecoratedRequestsAndMissingDocumentsAreBlockedWithoutReadingPrivateBytes() {
    val context = RuntimeEnvironment.getApplication() as Application
    val missing = File(context.cacheDir, "missing-policy-source.docx").apply { delete() }
    for (url in listOf(null, "", "file:///document.docx", "https://example.com/document.docx",
      "https://folivela.local:443/document.docx", "https://folivela.local/document.docx#fragment",
      "https://folivela.local/document.docx?query=1", "https://user@folivela.local/document.docx",
      "https://folivela.local/../document.docx", "https://folivela.local/%2e%2e/document.docx",
      "https://folivela.local/document.docx")) {
      val response = DocxPreviewResourcePolicy.intercept(context, missing, url)
      assertEquals("Unexpected route admitted: $url", 403, response.statusCode)
      assertEquals("no-store", response.responseHeaders["Cache-Control"])
      assertEquals("", response.data.bufferedReader().use { it.readText() })
      assertFalse(missing.exists())
    }
  }

}

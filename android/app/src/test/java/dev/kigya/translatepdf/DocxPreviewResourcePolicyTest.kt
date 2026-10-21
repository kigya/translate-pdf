package dev.kigya.translatepdf

import android.app.Application
import org.junit.Assert.assertFalse
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
}

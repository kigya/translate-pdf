package dev.kigya.translatepdf

import android.graphics.Bitmap

/** Keeps the real System.loadLibrary behavior while making the platform loader boundary testable. */
internal object NativeCoreLibrary {
  @Volatile
  @PublishedApi
  internal var testLoader: ((String) -> Unit)? = null

  inline fun load(name: String) {
    val injected = testLoader
    if (injected == null) System.loadLibrary(name) else injected(name)
  }
}

/** Thin JNI boundary. PDF and model contents stay in native code and private files. */
internal object NativeCore {
  init { NativeCoreLibrary.load("translatepdf") }

  external fun inspect(path: String): String
  external fun inspectDocx(path: String): String
  external fun readDocxSegment(path: String, index: Int): String
  external fun exportDocx(source: String, segmentsJson: String, destination: String): String
  external fun readPage(path: String, index: Int): String
  external fun render(path: String, index: Int, width: Int): Bitmap
  external fun loadModel(path: String, context: Int, threads: Int, profile: String): String
  external fun translate(text: String, glossary: String, context: String, sourceLanguage: String, targetLanguage: String): String
  external fun releaseModel()
  external fun cancel()
  external fun exportPage(source: String, index: Int, blocksJson: String, destination: String, fontPath: String, cjkFontPath: String): String
  external fun mergePdf(pagesJson: String, destination: String): String
}

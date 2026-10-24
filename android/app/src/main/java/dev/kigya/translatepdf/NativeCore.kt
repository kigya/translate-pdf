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
  external fun setWeightRepackingAllowed(allowed: Boolean)
  external fun loadModel(path: String, context: Int, threads: Int, profile: String): String
  external fun loadModelBatch(path: String, totalContext: Int, threads: Int, profile: String, maxSequences: Int): String
  external fun translate(text: String, glossary: String, context: String, sourceLanguage: String, targetLanguage: String): String
  external fun translateForLayout(text: String, glossary: String, context: String, sourceLanguage: String, targetLanguage: String, layoutOptions: String): String
  external fun translateBatch(
    requestsJson: String,
    glossary: String,
    background: String,
    sourceLanguage: String,
    targetLanguage: String,
    generation: Long,
    callback: BatchCompletion,
  ): String
  external fun releaseModel()
  external fun cancel()
  external fun inferenceProgress(): String
  external fun exportPage(source: String, index: Int, blocksJson: String, destination: String, fontPath: String, cjkFontPath: String): String
  external fun mergePdf(pagesJson: String, destination: String): String
}

/** Synchronous worker callback: 1=durably committed/continue, 2=durably committed/stop, 0=reject/stop. */
internal fun interface BatchCompletion {
  fun onCompleted(responseJson: String): Int
}

package dev.kigya.translatepdf

import android.graphics.Bitmap

/** Thin JNI boundary. PDF and model contents stay in native code and private files. */
internal object NativeCore {
  init { System.loadLibrary("translatepdf") }

  external fun inspect(path: String): String
  external fun readPage(path: String, index: Int): String
  external fun render(path: String, index: Int, width: Int): Bitmap
  external fun loadModel(path: String, context: Int, threads: Int, profile: String = "hy-mt2"): String
  external fun translate(text: String, glossary: String, context: String): String
  external fun releaseModel()
  external fun cancel()
  external fun exportPage(source: String, index: Int, blocksJson: String, destination: String, fontPath: String): String
  external fun mergePdf(pagesJson: String, destination: String): String
}

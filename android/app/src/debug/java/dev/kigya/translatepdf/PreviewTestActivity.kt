package dev.kigya.translatepdf

/** A debug-only native host for renderer instrumentation; never opens user data. */
class PreviewTestActivity : android.app.Activity() {
  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(android.widget.FrameLayout(this))
  }
}

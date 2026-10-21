package dev.kigya.translatepdf

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class MainActivityTest {
  @Test
  fun activityRegistersItsStableMainComponentName() {
    // Avoid lifecycle startup here: React Native's JNI feature flags require an
    // Android runtime and are covered by platform launch validation.
    val activity = Robolectric.buildActivity(MainActivity::class.java).get()

    val componentName = MainActivity::class.java.getDeclaredMethod("getMainComponentName").apply {
      isAccessible = true
    }.invoke(activity)
    assertEquals("main", componentName)
  }
}

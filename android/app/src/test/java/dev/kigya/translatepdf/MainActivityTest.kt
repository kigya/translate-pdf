package dev.kigya.translatepdf

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
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

  @Test
  fun onCreateSetsTheBrandedWindowSurfaceBeforeReactNativeStartup() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).get()

    // Host-only React Native startup can stop at its JNI boundary. The app must set
    // its launch surface before crossing that boundary, even when startup cannot run here.
    runCatching {
      MainActivity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
        .apply { isAccessible = true }.invoke(activity, Bundle())
    }

    val background = activity.window.decorView.background as? ColorDrawable
    assertEquals(Color.rgb(198, 40, 40), background?.color)
  }
}

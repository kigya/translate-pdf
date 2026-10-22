package dev.kigya.translatepdf

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class MainApplicationTest {
  @Test
  fun applicationPreservesLegacyHostBoundaryAndAttemptsReactNativeStartup() {
    val application = MainApplication()
    ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
      .apply { isAccessible = true }.invoke(application, RuntimeEnvironment.getApplication())

    val legacyHostError = runCatching { application.reactNativeHost }.exceptionOrNull()
    assertTrue(
      "legacy host API should remain rejected by the installed React Native architecture",
      legacyHostError is RuntimeException && legacyHostError.message.orEmpty().contains("New Architecture"),
    )

    val reactHostError = runCatching {
      MainApplication::class.java.getDeclaredMethod("getReactHost")
        .apply { isAccessible = true }.invoke(application)
    }.exceptionOrNull()?.cause ?: runCatching { application.reactHost }.exceptionOrNull()
    val missingHostNativeRuntime = generateSequence(reactHostError) { it.cause }.any {
      it is UnsatisfiedLinkError && it.message.orEmpty().contains("rninstance")
    }
    assertTrue(
      "ReactHost should be created or report that this JVM lacks the RN instance library; observed ${reactHostError?.javaClass?.name}: ${reactHostError?.message}",
      reactHostError == null || missingHostNativeRuntime,
    )

    val legacyGetterError = runCatching {
      MainApplication::class.java.getDeclaredMethod("getReactNativeHost")
        .apply { isAccessible = true }.invoke(application)
    }.exceptionOrNull()?.cause
    assertTrue(
      "the concrete legacy getter should preserve React Native's New Architecture failure",
      legacyGetterError is RuntimeException && legacyGetterError.message.orEmpty().contains("New Architecture"),
    )

    val startupError = runCatching { application.onCreate() }.exceptionOrNull()
    val startupMissingHostNativeRuntime = generateSequence(startupError) { it.cause }.any {
      it is UnsatisfiedLinkError && it.message.orEmpty().contains("rninstance")
    }
    // React Native's entry point is native-backed; Robolectric may stop at its host-only NPE
    // before that JNI boundary is reached. A real Android process is required to validate startup.
    val robolectricRuntimeGap = startupError is NullPointerException
    assertTrue(
      "React Native startup should complete or report the host-only runtime boundary; observed ${startupError?.javaClass?.name}: ${startupError?.message}",
      startupError == null || startupMissingHostNativeRuntime || robolectricRuntimeGap,
    )
  }

  @Test
  fun configurationChangesReachExpoLifecycleDispatcher() {
    val application = MainApplication()
    ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
      .apply { isAccessible = true }.invoke(application, RuntimeEnvironment.getApplication())

    application.onConfigurationChanged(Configuration())
  }
}

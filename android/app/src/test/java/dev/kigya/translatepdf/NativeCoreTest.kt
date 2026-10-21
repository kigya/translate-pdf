package dev.kigya.translatepdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class NativeCoreTest {
  @Test
  fun loaderDefaultsToSystemAndNativeLoadModelRequestsTheProductionLibrary() {
    val originalLoader = NativeCoreLibrary.testLoader
    try {
      val missingLibrary = runCatching { NativeCoreLibrary.load("translatepdf-test-missing") }.exceptionOrNull()
      assertTrue("the default loader should delegate to System.loadLibrary", missingLibrary is UnsatisfiedLinkError)
      assertTrue(missingLibrary?.message.orEmpty().contains("translatepdf-test-missing"))

      val requestedLibraries = mutableListOf<String>()
      NativeCoreLibrary.testLoader = { requestedLibraries.add(it) }
      val nativeCallError = runCatching {
        NativeCore.loadModel("weights.gguf", 2048, 1, "hy-mt2")
      }.exceptionOrNull()

      assertEquals(listOf("translatepdf"), requestedLibraries)
      assertTrue(
        "the host JVM has no loaded PDF/model JNI implementation; observed ${nativeCallError?.javaClass?.name}",
        nativeCallError is UnsatisfiedLinkError,
      )

      val explicitProfileError = runCatching {
        NativeCore.loadModel("weights.gguf", 2048, 1, "custom-profile")
      }.exceptionOrNull()
      assertTrue(
        "an explicitly supplied profile should reach the native loadModel boundary; observed ${explicitProfileError?.javaClass?.name}",
        explicitProfileError is UnsatisfiedLinkError,
      )
    } finally {
      NativeCoreLibrary.testLoader = originalLoader
    }
  }
}

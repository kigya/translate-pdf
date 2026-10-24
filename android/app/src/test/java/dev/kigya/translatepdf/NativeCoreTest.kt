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

      val repackingSetterError = runCatching { NativeCore.setWeightRepackingAllowed(false) }.exceptionOrNull()
      assertTrue(
        "the weight-repacking setting must use its JNI boundary; observed ${repackingSetterError?.javaClass?.name}",
        repackingSetterError is UnsatisfiedLinkError,
      )

      val explicitProfileError = runCatching {
        NativeCore.loadModel("weights.gguf", 2048, 1, "custom-profile")
      }.exceptionOrNull()
      assertTrue(
        "an explicitly supplied profile should reach the native loadModel boundary; observed ${explicitProfileError?.javaClass?.name}",
        explicitProfileError is UnsatisfiedLinkError,
      )

      val batchLoadError = runCatching {
        NativeCore.loadModelBatch("weights.gguf", 2048, 4, "hy-mt2", 4)
      }.exceptionOrNull()
      assertTrue("the optional batch load must reach its JNI entry point", batchLoadError is UnsatisfiedLinkError)

      var callbackInvoked = false
      val batchTranslateError = runCatching {
        NativeCore.translateBatch("[]", "", "", "en", "ru", 11L) {
          callbackInvoked = true
          1
        }
      }.exceptionOrNull()
      assertTrue("the batch callback API must reach its JNI entry point", batchTranslateError is UnsatisfiedLinkError)
      assertTrue("a missing JNI implementation cannot invoke the completion sink", !callbackInvoked)

      val layoutRetryError = runCatching {
        NativeCore.translateForLayout("Storage", "", "Chevrolet Equinox manual", "en", "ru", "{\"attempt\":1,\"maxCharacters\":12}")
      }.exceptionOrNull()
      assertTrue("the layout retry must use its separate native prompt contract", layoutRetryError is UnsatisfiedLinkError)
    } finally {
      NativeCoreLibrary.testLoader = originalLoader
    }
  }
}

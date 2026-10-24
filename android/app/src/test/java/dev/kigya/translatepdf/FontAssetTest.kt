package dev.kigya.translatepdf

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FontAssetTest {
  @Test
  fun corruptedCachedFontIsReplacedAndOnlyVerifiedFileRemains() {
    val context = RuntimeEnvironment.getApplication()
    val directory = File(context.cacheDir, "font-integrity-test").apply { deleteRecursively(); mkdirs() }
    assertEquals("NotoSansCondensed-Regular.ttf", FontAsset.DEFAULT_CACHE_FILENAME)
    val target = File(directory, FontAsset.DEFAULT_CACHE_FILENAME)
    target.writeText("interrupted-copy")

    val installed = FontAsset.ensure(context, target)
    val digest = sha256(installed)

    assertEquals(installed, target)
    assertEquals("5c15b8bb0ac1bbd533917b23968c6f0a420f143533e1293cc575f4f9dd543be8", digest)
    assertTrue(installed.length() > 500_000L)
    assertFalse(File(directory, "NotoSansCondensed-Regular.ttf.tmp").exists())
    directory.deleteRecursively()
  }

  @Test
  fun validCachedFontIsReusedWithoutReplacingItsFile() {
    val context = RuntimeEnvironment.getApplication()
    val target = FontAsset.ensure(context, File(context.cacheDir, "font-integrity-valid.ttf"))
    val modifiedAt = target.lastModified()

    val second = FontAsset.ensure(context, target)

    assertEquals(target.absolutePath, second.absolutePath)
    assertEquals(modifiedAt, second.lastModified())
    target.delete()
    File(target.parentFile, "${target.name}.tmp").delete()
  }

  @Test
  fun legacyRegularFontCacheDoesNotSatisfyTheNewCondensedDefault() {
    val context = RuntimeEnvironment.getApplication()
    val directory = File(context.cacheDir, "font-policy-v7-cache").apply { deleteRecursively(); mkdirs() }
    val legacy = File(directory, "NotoSans-Regular.ttf")
    context.assets.open("fonts/NotoSans-Regular.ttf").use { input -> legacy.outputStream().use(input::copyTo) }
    val legacyBytes = sha256(legacy)

    val condensed = FontAsset.ensure(context, File(directory, FontAsset.DEFAULT_CACHE_FILENAME))

    assertEquals("b85c38ecea8a7cfb39c24e395a4007474fa5a4fc864f6ee33309eb4948d232d5", legacyBytes)
    assertEquals(FontAsset.EXPECTED_SHA256, sha256(condensed))
    assertTrue(legacy.isFile)
    assertTrue(condensed.isFile)
    assertFalse(legacy.absolutePath == condensed.absolutePath)
    directory.deleteRecursively()
  }

  @Test
  fun corruptCjkFontIsReplacedWithPinnedAssetThenReused() {
    val context = RuntimeEnvironment.getApplication()
    val directory = File(context.cacheDir, "cjk-font-integrity-test").apply { deleteRecursively(); mkdirs() }
    val target = File(directory, "NotoSansSC-Regular.ttf")
    target.writeText("partial download")

    val installed = FontAsset.ensureCjk(context, target)
    val modifiedAt = installed.lastModified()
    val reused = FontAsset.ensureCjk(context, target)

    assertEquals(target, installed)
    assertEquals("b7bebb49c3c6b0266362cf75dd9bdd9bf5fbe5fb390be8f7808ebedef376de76", sha256(installed))
    assertTrue(installed.length() > 5_000_000L)
    assertEquals(installed.absolutePath, reused.absolutePath)
    assertEquals(modifiedAt, reused.lastModified())
    assertFalse(File(directory, "NotoSansSC-Regular.ttf.tmp").exists())
    directory.deleteRecursively()
  }

  @Test
  fun atomicInstallFallsBackWhenTheFilesystemRejectsAtomicMove() {
    val directory = Files.createTempDirectory("font-atomic-fallback-").toFile()
    val source = File(directory, "font.incoming").apply { writeText("verified bytes") }
    val target = File(directory, "font.ttf").apply { writeText("old bytes") }
    val attempts = mutableListOf<Boolean>()

    FontAsset.atomicReplace(source, target) { from, to, atomic ->
      attempts += atomic
      if (atomic) throw AtomicMoveNotSupportedException(from.path, to.path, "simulated unsupported filesystem")
      Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    assertEquals(listOf(true, false), attempts)
    assertEquals("verified bytes", target.readText())
    assertFalse(source.exists())
    directory.deleteRecursively()
  }

  @Test
  fun rejectsMismatchedBundledBytesAndRemovesUntrustedTemporaryFile() {
    val context = RuntimeEnvironment.getApplication()
    val directory = File(context.cacheDir, "font-hash-rejection").apply { deleteRecursively(); mkdirs() }
    val target = File(directory, "NotoSansCondensed-Regular.ttf").apply { writeText("previous verified destination") }
    val openAsset: (android.content.Context, String) -> java.io.InputStream = { _, _ -> ByteArrayInputStream("wrong asset".toByteArray()) }

    val failure = runCatching { FontAsset.ensure(context, target, openAsset) }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertEquals("previous verified destination", target.readText())
    assertFalse(File(directory, "NotoSansCondensed-Regular.ttf.tmp").exists())
    directory.deleteRecursively()
  }

  @Test
  fun rejectsInstallThatDoesNotReplaceAndPreservesVerifiedTempCleanup() {
    val context = RuntimeEnvironment.getApplication()
    val directory = File(context.cacheDir, "font-install-rejection").apply { deleteRecursively(); mkdirs() }
    val target = File(directory, "NotoSansCondensed-Regular.ttf").apply { writeText("wrong prior bytes") }

    val failure = runCatching { FontAsset.ensure(context, target, replace = { _, _ -> }) }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertEquals("wrong prior bytes", target.readText())
    assertFalse(File(directory, "NotoSansCondensed-Regular.ttf.tmp").exists())
    val absentTarget = File(directory, "never-installed.ttf")
    val absentFailure = runCatching { FontAsset.ensure(context, absentTarget, replace = { _, _ -> }) }.exceptionOrNull()
    assertTrue(absentFailure is IllegalStateException)
    assertFalse(absentTarget.exists())
    assertFalse(File(directory, "never-installed.ttf.tmp").exists())
    directory.deleteRecursively()
  }

  @Test
  fun assetOpenAndReplacementFailuresPropagateWithoutLeavingTemporaryFiles() {
    val context = RuntimeEnvironment.getApplication()
    val directory = File(context.cacheDir, "font-io-failure").apply { deleteRecursively(); mkdirs() }
    val target = File(directory, "NotoSansCondensed-Regular.ttf")
    val openFailure = runCatching {
      FontAsset.ensure(context, target, openAsset = { _, _ -> throw IOException("asset unavailable") })
    }.exceptionOrNull()
    assertEquals("asset unavailable", openFailure?.message)
    assertFalse(File(directory, "NotoSansCondensed-Regular.ttf.tmp").exists())

    val replaceFailure = runCatching {
      FontAsset.ensure(context, target, replace = { _, _ -> throw IOException("storage unavailable") })
    }.exceptionOrNull()
    assertEquals("storage unavailable", replaceFailure?.message)
    assertFalse(File(directory, "NotoSansCondensed-Regular.ttf.tmp").exists())
    assertFalse(target.exists())
    directory.deleteRecursively()
  }

  @Test
  fun assetStreamCloseFailureIsReportedAndTemporaryCopyIsRemoved() {
    val context = RuntimeEnvironment.getApplication()
    val directory = File(context.cacheDir, "font-close-failure").apply { deleteRecursively(); mkdirs() }
    val target = File(directory, "NotoSansCondensed-Regular.ttf")
    val stream = object : InputStream() {
      private val bytes = "untrusted content".toByteArray()
      private var index = 0
      override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
      override fun close() { throw IOException("asset close failure") }
    }

    val failure = runCatching { FontAsset.ensure(context, target, openAsset = { _, _ -> stream }) }.exceptionOrNull()

    assertEquals("asset close failure", failure?.message)
    assertFalse(target.exists())
    assertFalse(File(directory, "NotoSansCondensed-Regular.ttf.tmp").exists())
    directory.deleteRecursively()
  }

  @Test
  fun checksumReadFailurePreservesPrimaryErrorAndSuppressesStreamCloseFailure() {
    val context = RuntimeEnvironment.getApplication()
    val directory = File(context.cacheDir, "font-hash-io-failure").apply { deleteRecursively(); mkdirs() }
    val target = File(directory, "NotoSansCondensed-Regular.ttf")
    val openFile: (File) -> java.io.InputStream = { file ->
      if (!file.name.endsWith(".tmp")) FileInputStream(file) else object : java.io.InputStream() {
        private var firstRead = true
        override fun read(): Int = throw IOException("checksum read failure")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
          if (firstRead) {
            firstRead = false
            buffer[offset] = 42
            return 1
          }
          throw IOException("checksum read failure")
        }
        override fun close() { throw IOException("checksum close failure") }
      }
    }

    val failure = runCatching { FontAsset.ensure(context, target, openFile = openFile) }.exceptionOrNull()

    assertEquals("checksum read failure", failure?.message)
    assertTrue(failure?.suppressed?.any { it.message == "checksum close failure" } == true)
    assertFalse(target.exists())
    assertFalse(File(directory, "NotoSansCondensed-Regular.ttf.tmp").exists())
    directory.deleteRecursively()
  }

  @Test
  fun fontCanBeInstalledToTargetWithoutParentDirectory() {
    val context = RuntimeEnvironment.getApplication()
    val name = "fontasset-root-${System.nanoTime()}.ttf"
    val target = File(name)
    try {
      assertEquals(null, target.parentFile)
      val installed = FontAsset.ensure(context, target)
      assertEquals(target, installed)
      assertEquals(FontAsset.EXPECTED_SHA256, sha256(installed))
    } finally {
      target.delete()
      File("$name.tmp").delete()
    }
  }

  @Test
  fun atomicMoveFallbackPropagatesNonAtomicFailure() {
    val directory = Files.createTempDirectory("font-atomic-failure-").toFile()
    val source = File(directory, "font.incoming").apply { writeText("verified bytes") }
    val target = File(directory, "font.ttf").apply { writeText("old bytes") }
    val attempts = mutableListOf<Boolean>()

    val failure = runCatching {
      FontAsset.atomicReplace(source, target) { from, to, atomic ->
        attempts += atomic
        if (atomic) throw AtomicMoveNotSupportedException(from.path, to.path, "simulated unsupported filesystem")
        throw IOException("replacement denied")
      }
    }.exceptionOrNull()

    assertEquals(listOf(true, false), attempts)
    assertEquals("replacement denied", failure?.message)
    assertTrue(source.exists())
    assertEquals("old bytes", target.readText())
    directory.deleteRecursively()
  }

  @Test
  fun cjkAssetRejectsBadHashAndDoesNotReplaceExistingFile() {
    val context = RuntimeEnvironment.getApplication()
    val directory = File(context.cacheDir, "cjk-font-hash-rejection").apply { deleteRecursively(); mkdirs() }
    val target = File(directory, "NotoSansSC-Regular.ttf").apply { writeText("previous bytes") }
    val failure = runCatching {
      FontAsset.ensureCjk(context, target, openAsset = { _, _ -> ByteArrayInputStream("wrong CJK bytes".toByteArray()) })
    }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertEquals("previous bytes", target.readText())
    assertFalse(File(directory, "NotoSansSC-Regular.ttf.tmp").exists())
    directory.deleteRecursively()
  }

  @Test
  fun assetReadAndCloseFailuresPreservePrimaryAndSuppressedErrorsForBothFonts() {
    val context = RuntimeEnvironment.getApplication()
    val directory = File(context.cacheDir, "font-asset-close-failures").apply { deleteRecursively(); mkdirs() }
    val cases = listOf(
      "NotoSansCondensed-Regular.ttf" to { target: File ->
        runCatching {
          FontAsset.ensure(context, target, openAsset = { _, _ -> failingCloseStream("font") })
        }.exceptionOrNull()
      },
      "NotoSansSC-Regular.ttf" to { target: File ->
        runCatching {
          FontAsset.ensureCjk(context, target, openAsset = { _, _ -> failingCloseStream("CJK font") })
        }.exceptionOrNull()
      },
    )

    for ((name, run) in cases) {
      val target = File(directory, name)
      val failure = run(target)
      assertTrue("${target.name} should retain the read failure", failure is IOException)
      assertEquals("The ${if (name.contains("SC")) "CJK " else ""}font asset stream failed.", failure?.message)
      assertEquals("The asset stream close failed.", failure?.suppressed?.singleOrNull()?.message)
      assertFalse(File(directory, "$name.tmp").exists())
      assertFalse(target.exists())
    }
    directory.deleteRecursively()
  }

  @Test
  fun cjkInstallRequiresDestinationAndSupportsNoParentTarget() {
    val context = RuntimeEnvironment.getApplication()
    val missingTarget = File(context.cacheDir, "cjk-install-missing/NotoSansSC-Regular.ttf").apply { parentFile!!.mkdirs() }
    val missingDestination = runCatching { FontAsset.ensureCjk(context, missingTarget, replace = { _, _ -> }) }.exceptionOrNull()
    assertTrue(missingDestination is IllegalStateException)
    assertFalse(File(missingTarget.parentFile, "NotoSansSC-Regular.ttf.tmp").exists())
    missingTarget.parentFile?.deleteRecursively()

    val wrongExisting = File(context.cacheDir, "cjk-existing-invalid/NotoSansSC-Regular.ttf")
      .apply { parentFile!!.mkdirs(); writeText("old corrupt font") }
    val wrongExistingFailure = runCatching { FontAsset.ensureCjk(context, wrongExisting, replace = { _, _ -> }) }.exceptionOrNull()
    assertTrue(wrongExistingFailure is IllegalStateException)
    assertEquals("old corrupt font", wrongExisting.readText())
    wrongExisting.parentFile?.deleteRecursively()

    val absentTarget = File(context.cacheDir, "cjk-install-absent/NotoSansSC-Regular.ttf").apply { parentFile!!.mkdirs() }
    val absentFailure = runCatching { FontAsset.ensureCjk(context, absentTarget, replace = { _, _ -> }) }.exceptionOrNull()
    assertTrue(absentFailure is IllegalStateException)
    assertFalse(absentTarget.exists())
    assertFalse(File(absentTarget.parentFile, "NotoSansSC-Regular.ttf.tmp").exists())
    absentTarget.parentFile?.deleteRecursively()

    val name = "fontasset-cjk-root-${System.nanoTime()}.ttf"
    val target = File(name)
    try {
      assertEquals(null, target.parentFile)
      assertEquals(target, FontAsset.ensureCjk(context, target))
      assertEquals("b7bebb49c3c6b0266362cf75dd9bdd9bf5fbe5fb390be8f7808ebedef376de76", sha256(target))
    } finally {
      target.delete()
      File("$name.tmp").delete()
    }
  }

  @Test
  fun defaultMoveHelperUsesNonAtomicReplacementWhenRequested() {
    val directory = Files.createTempDirectory("font-non-atomic-move-").toFile()
    val source = File(directory, "font.incoming").apply { writeText("new bytes") }
    val target = File(directory, "font.ttf").apply { writeText("old bytes") }

    FontAsset.moveFile(source, target, atomic = false)

    assertEquals("new bytes", target.readText())
    assertFalse(source.exists())
    directory.deleteRecursively()
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  private fun failingCloseStream(label: String): InputStream = object : InputStream() {
    override fun read(): Int = throw IOException("The $label asset stream failed.")
    override fun close() { throw IOException("The asset stream close failed.") }
  }
}

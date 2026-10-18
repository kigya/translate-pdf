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
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FontAssetTest {
  @Test
  fun corruptedCachedFontIsReplacedAndOnlyVerifiedFileRemains() {
    val context = RuntimeEnvironment.getApplication()
    val directory = File(context.cacheDir, "font-integrity-test").apply { deleteRecursively(); mkdirs() }
    val target = File(directory, "NotoSans-Regular.ttf")
    target.writeText("interrupted-copy")

    val installed = FontAsset.ensure(context, target)
    val digest = sha256(installed)

    assertEquals(installed, target)
    assertEquals("b85c38ecea8a7cfb39c24e395a4007474fa5a4fc864f6ee33309eb4948d232d5", digest)
    assertTrue(installed.length() > 500_000L)
    assertFalse(File(directory, "NotoSans-Regular.ttf.tmp").exists())
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
}

package dev.kigya.translatepdf

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object FontAsset {
  private const val ASSET_PATH = "fonts/NotoSans-Regular.ttf"
  private const val CJK_ASSET_PATH = "fonts/NotoSansSC-Regular.ttf"
  private const val EXPECTED_SHA256 = "b85c38ecea8a7cfb39c24e395a4007474fa5a4fc864f6ee33309eb4948d232d5"
  private const val EXPECTED_CJK_SHA256 = "b7bebb49c3c6b0266362cf75dd9bdd9bf5fbe5fb390be8f7808ebedef376de76"

  fun ensure(context: Context, target: File): File {
    if (target.isFile && sha256(target) == EXPECTED_SHA256) return target
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, "${target.name}.tmp")
    temporary.delete()
    context.assets.open(ASSET_PATH).use { input ->
      FileOutputStream(temporary).use { output ->
        input.copyTo(output)
        output.fd.sync()
      }
    }
    check(sha256(temporary) == EXPECTED_SHA256) { "The bundled Cyrillic export font failed its integrity check." }
    try {
      Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    check(target.isFile && sha256(target) == EXPECTED_SHA256) { "The Cyrillic export font could not be installed safely." }
    return target
  }

  fun ensureCjk(context: Context, target: File): File {
    if (target.isFile && sha256(target) == EXPECTED_CJK_SHA256) return target
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, "${target.name}.tmp")
    temporary.delete()
    context.assets.open(CJK_ASSET_PATH).use { input ->
      FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() }
    }
    check(sha256(temporary) == EXPECTED_CJK_SHA256) { "The bundled Simplified Chinese PDF font failed its integrity check." }
    try {
      Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    check(target.isFile && sha256(target) == EXPECTED_CJK_SHA256) { "The Simplified Chinese PDF font could not be installed safely." }
    return target
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}

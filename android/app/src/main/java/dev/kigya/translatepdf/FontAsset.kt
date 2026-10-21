package dev.kigya.translatepdf

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object FontAsset {
  private const val ASSET_PATH = "fonts/NotoSans-Regular.ttf"
  private const val CJK_ASSET_PATH = "fonts/NotoSansSC-Regular.ttf"
  private const val EXPECTED_SHA256 = "b85c38ecea8a7cfb39c24e395a4007474fa5a4fc864f6ee33309eb4948d232d5"
  private const val EXPECTED_CJK_SHA256 = "b7bebb49c3c6b0266362cf75dd9bdd9bf5fbe5fb390be8f7808ebedef376de76"

  fun ensure(
    context: Context,
    target: File,
    openAsset: (Context, String) -> InputStream = { app, path -> app.assets.open(path) },
    openFile: (File) -> InputStream = { FileInputStream(it) },
    replace: (File, File) -> Unit = ::atomicReplace,
  ): File {
    if (target.isFile && sha256(target, openFile) == EXPECTED_SHA256) return target
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, "${target.name}.tmp")
    temporary.delete()
    try {
      openAsset(context, ASSET_PATH).use { input ->
        FileOutputStream(temporary).use { output ->
          input.copyTo(output)
          output.fd.sync()
        }
      }
      check(sha256(temporary, openFile) == EXPECTED_SHA256) { "The bundled Cyrillic export font failed its integrity check." }
      replace(temporary, target)
      check(target.isFile && sha256(target, openFile) == EXPECTED_SHA256) { "The Cyrillic export font could not be installed safely." }
      return target
    } finally {
      temporary.delete()
    }
  }

  fun ensureCjk(
    context: Context,
    target: File,
    openAsset: (Context, String) -> InputStream = { app, path -> app.assets.open(path) },
    openFile: (File) -> InputStream = { FileInputStream(it) },
    replace: (File, File) -> Unit = ::atomicReplace,
  ): File {
    if (target.isFile && sha256(target, openFile) == EXPECTED_CJK_SHA256) return target
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, "${target.name}.tmp")
    temporary.delete()
    try {
      openAsset(context, CJK_ASSET_PATH).use { input ->
        FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() }
      }
      check(sha256(temporary, openFile) == EXPECTED_CJK_SHA256) { "The bundled Simplified Chinese PDF font failed its integrity check." }
      replace(temporary, target)
      check(target.isFile && sha256(target, openFile) == EXPECTED_CJK_SHA256) { "The Simplified Chinese PDF font could not be installed safely." }
      return target
    } finally {
      temporary.delete()
    }
  }

  private fun sha256(file: File, openFile: (File) -> InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    openFile(file).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  /** Keep verified font installation working on filesystems without atomic moves. */
  internal fun atomicReplace(
    source: File,
    target: File,
    move: (File, File, Boolean) -> Unit = { from, to, atomic ->
      moveFile(from, to, atomic)
    },
  ) {
    try {
      move(source, target, true)
    } catch (_: AtomicMoveNotSupportedException) {
      move(source, target, false)
    }
  }

  internal fun moveFile(source: File, target: File, atomic: Boolean) {
    if (atomic) {
      Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } else {
      Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }
}

package dev.kigya.translatepdf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal data class FinalizedJobPdf(
  val file: File,
  val exportId: String,
  val displayName: String,
  val pageCount: Int,
  val partial: Boolean,
)

internal interface JobPdfNativeOperations {
  fun inspect(path: String): String
  fun exportPage(source: String, pageIndex: Int, blocksJson: String, destination: String, fontPath: String): String
  fun mergePdf(pagesJson: String, destination: String): String
}

private object NativeJobPdfOperations : JobPdfNativeOperations {
  override fun inspect(path: String): String = NativeCore.inspect(path)
  override fun exportPage(source: String, pageIndex: Int, blocksJson: String, destination: String, fontPath: String): String =
    NativeCore.exportPage(source, pageIndex, blocksJson, destination, fontPath)
  override fun mergePdf(pagesJson: String, destination: String): String = NativeCore.mergePdf(pagesJson, destination)
}

/** Creates and verifies one durable merged PDF per job from saved page checkpoints. */
internal class JobPdfExporter(
  private val context: Context,
  private val database: TranslatorDatabase,
  private val native: JobPdfNativeOperations = NativeJobPdfOperations,
  private val fontProvider: (Context) -> File = { app -> File(app.cacheDir, "NotoSans-Regular.ttf").also { FontAsset.ensure(app, it) } },
) {
  fun getOrBuild(jobId: String, shouldCancel: () -> Boolean = { false }): FinalizedJobPdf {
    val lock = locks.computeIfAbsent(jobId) { Any() }
    synchronized(lock) {
      val job = database.jobJson(jobId) ?: throw IllegalArgumentException("Translation job was not found.")
      val pageCount = job.getInt("totalPages")
      require(pageCount > 0) { "The translation job has no pages to export." }
      val exportId = exportId(jobId)
      val directory = File(context.filesDir, "exports").apply { mkdirs() }
      val output = File(directory, "$exportId.pdf")
      val name = displayName(job.optString("documentName", "translated.pdf"))
      val saved = database.export(exportId)
      if (saved?.first == output.absolutePath && isVerifiedPdf(output, pageCount)) {
        val partial = hasPartialPages(jobId, pageCount)
        return FinalizedJobPdf(output, exportId, saved.second, pageCount, partial)
      }

      val source = database.jobDocumentPath(jobId) ?: throw IllegalStateException("The source document is unavailable.")
      val work = File(context.cacheDir, "job-export-${exportId.removePrefix("job-")}")
      val temporary = File(directory, "$exportId.incoming").apply { delete() }
      if (work.exists()) work.deleteRecursively()
      require(work.mkdirs()) { "The temporary PDF export directory could not be created." }
      val pageFiles = JSONArray()
      var partial = false
      val font = fontProvider(context)

      try {
        for (pageIndex in 0 until pageCount) {
          checkCanceled(shouldCancel)
          val checkpoint = database.page(jobId, pageIndex)
            ?: throw IllegalStateException("Page ${pageIndex + 1} has no saved translation checkpoint.")
          require(checkpoint.optString("status") in setOf("completed", "partial")) {
            "Page ${pageIndex + 1} has not finished translation."
          }
          val blocks = checkpoint.getJSONArray("blocks")
          val existingError = checkpoint.optString("error").takeIf { !checkpoint.isNull("error") && it.isNotBlank() }
          val pageOutput = File(work, "page-$pageIndex.pdf")
          var result: JSONObject? = null
          for (attempt in 0..1) {
            checkCanceled(shouldCancel)
            pageOutput.delete()
            val current = JSONObject(native.exportPage(
              source, pageIndex, blocks.toString(), pageOutput.absolutePath, font.absolutePath,
            ))
            throwOnNativeError(current, "A translated page could not be exported.")
            require(pageOutput.isFile && pageOutput.length() > 0L && isVerifiedPdf(pageOutput, 1)) {
              "The PDF core did not create a valid translated page output."
            }
            val issues = current.optJSONArray("issues") ?: JSONArray()
            val changed = PageExportIssues.applyToBlocks(blocks, issues)
            val pagePartial = checkpoint.optString("status") == "partial" || current.optBoolean("partial") || issues.length() > 0
            val pageError = PageExportIssues.pageError(existingError, blocks, pagePartial, issues)
            if (changed || pagePartial || pageError != existingError) {
              database.savePage(jobId, pageIndex, if (pagePartial) "partial" else "completed", blocks.toString(),
                checkpoint.optDouble("width"), checkpoint.optDouble("height"), pageError, blocks.length())
            }
            result = current
            partial = partial || pagePartial
            if (!changed) break
            if (attempt == 1) throw IllegalStateException("Page ${pageIndex + 1} layout findings did not stabilize during export.")
          }
          if (result?.optBoolean("partial") == true) partial = true
          pageFiles.put(pageOutput.absolutePath)
        }

        checkCanceled(shouldCancel)
        val merged = JSONObject(native.mergePdf(pageFiles.toString(), temporary.absolutePath))
        throwOnNativeError(merged, "The translated pages could not be assembled.")
        require(merged.optInt("pageCount") == pageCount && isVerifiedPdf(temporary, pageCount)) {
          "The merged translated PDF failed its page-count or integrity check."
        }
        checkCanceled(shouldCancel)
        atomicReplace(temporary, output)
        require(isVerifiedPdf(output, pageCount)) { "The saved translated PDF failed its final integrity check." }
        database.saveExport(exportId, output.absolutePath, name)
        partial = partial || hasPartialPages(jobId, pageCount)
        return FinalizedJobPdf(output, exportId, name, pageCount, partial)
      } finally {
        work.deleteRecursively()
        temporary.delete()
      }
    }
  }

  fun delete(jobId: String) {
    synchronized(locks.computeIfAbsent(jobId) { Any() }) {
      val id = exportId(jobId)
      database.export(id)?.first?.let { File(it).delete() }
      database.deleteExport(id)
      File(context.cacheDir, "job-export-${id.removePrefix("job-")}").deleteRecursively()
    }
  }

  private fun hasPartialPages(jobId: String, pageCount: Int): Boolean = database.pageRows(jobId).let { rows ->
    (0 until rows.length()).any { row ->
      val item = rows.getJSONObject(row)
      item.getInt("index") in 0 until pageCount && item.optString("status") == "partial"
    }
  }

  private fun isVerifiedPdf(file: File, expectedPages: Int): Boolean {
    if (!file.isFile || file.length() < PDF_HEADER_BYTES) return false
    val header = ByteArray(PDF_HEADER_BYTES)
    val read = FileInputStream(file).use { it.read(header) }
    if (read < PDF_HEADER_BYTES || String(header, Charsets.US_ASCII) != "%PDF-") return false
    return runCatching {
      val inspected = JSONObject(native.inspect(file.absolutePath))
      !inspected.has("error") && inspected.optInt("pageCount") == expectedPages
    }.getOrDefault(false)
  }

  private fun checkCanceled(shouldCancel: () -> Boolean) {
    if (shouldCancel() || Thread.currentThread().isInterrupted) throw InterruptedException("PDF finalization was interrupted.")
  }

  private fun throwOnNativeError(result: JSONObject, fallback: String) {
    if (result.has("error")) throw IllegalStateException(result.optString("error", fallback))
  }

  private fun displayName(documentName: String): String {
    val base = documentName.substringBeforeLast('.', documentName).trim()
      .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().take(100).ifBlank { "translated" }
    return "$base-ru.pdf"
  }

  private fun atomicReplace(source: File, target: File) {
    try {
      Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun exportId(jobId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(jobId.toByteArray(Charsets.UTF_8))
    return "job-" + digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  companion object {
    private const val PDF_HEADER_BYTES = 5
    private val locks = ConcurrentHashMap<String, Any>()
  }
}

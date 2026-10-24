package dev.kigya.translatepdf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
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
  val sourcePageCount: Int,
  val layoutVersion: Int,
  val receipt: PdfLayoutReceipt,
  val partial: Boolean,
) {
  val generationDirectory: File get() = checkNotNull(file.parentFile)
}

internal interface JobPdfNativeOperations {
  fun inspect(path: String): String
  fun exportPage(source: String, pageIndex: Int, blocksJson: String, destination: String, fontPath: String, cjkFontPath: String): String
  fun mergePdf(pagesJson: String, destination: String): String
}

private object NativeJobPdfOperations : JobPdfNativeOperations {
  override fun inspect(path: String): String = NativeCore.inspect(path)
  override fun exportPage(source: String, pageIndex: Int, blocksJson: String, destination: String, fontPath: String, cjkFontPath: String): String =
    NativeCore.exportPage(source, pageIndex, blocksJson, destination, fontPath, cjkFontPath)
  override fun mergePdf(pagesJson: String, destination: String): String = NativeCore.mergePdf(pagesJson, destination)
}

internal fun movePdfFile(source: File, target: File, atomic: Boolean) {
  if (atomic) {
    Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  } else {
    Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }
}

/** Creates and verifies one durable merged PDF per job from saved page checkpoints. */
internal class JobPdfExporter(
  private val context: Context,
  private val database: TranslatorDatabase,
  private val native: JobPdfNativeOperations = NativeJobPdfOperations,
  private val fontProvider: (Context) -> File = { app -> File(app.cacheDir, FontAsset.DEFAULT_CACHE_FILENAME).also { FontAsset.ensure(app, it) } },
  private val cjkFontProvider: (Context) -> File = { app -> File(app.cacheDir, "NotoSansSC-Regular.ttf").also { FontAsset.ensureCjk(app, it) } },
  private val moveFile: (File, File, Boolean) -> Unit = ::movePdfFile,
  private val sourcePath: (String) -> String? = { id -> database.jobDocumentPath(id) },
  private val createWorkDirectory: (File) -> Boolean = { it.mkdirs() },
  private val openPdfInputStream: (File) -> InputStream = { FileInputStream(it) },
  private val timing: ProcessingTiming = ProcessingTiming(),
) {
  fun getOrBuild(jobId: String, persistExport: Boolean = true, shouldCancel: () -> Boolean = { false }): FinalizedJobPdf {
    val lock = locks.computeIfAbsent(exportId(jobId)) { Any() }
    synchronized(lock) {
      val job = database.jobJson(jobId) ?: throw IllegalArgumentException("Translation job was not found.")
      val sourcePageCount = job.getInt("totalPages")
      require(sourcePageCount > 0) { "The translation job has no pages to export." }
      val exportId = exportId(jobId)
      val directory = File(context.filesDir, "exports").apply { mkdirs() }
      val sourceLanguage = job.getString("sourceLanguage")
      val targetLanguage = job.getString("targetLanguage")
      LanguagePair.requireValid(sourceLanguage, targetLanguage)
      val name = displayName(job.optString("documentName", "translated.pdf"), sourceLanguage, targetLanguage)
      val saved = database.export(exportId)
      val source = sourcePath(jobId) ?: throw IllegalStateException("The source document is unavailable.")
      val sourceFile = File(source)
      if (!sourceFile.isFile) throw IllegalStateException("The source document is unavailable.")
      val checkpointDigest = checkpointDigest(jobId, sourcePageCount)
      saved?.first?.let(::File)?.let { cachedFile ->
        val receipt = loadVerifiedReceipt(cachedFile, exportId, sourcePageCount, sourceFile, checkpointDigest)
        if (receipt != null) {
          if (persistExport && saved.second != name) database.saveExport(exportId, cachedFile.absolutePath, name)
          val partial = hasPartialPages(jobId, sourcePageCount)
          return FinalizedJobPdf(cachedFile, exportId, name, receipt.outputPageCount,
            sourcePageCount, receipt.layoutVersion, receipt, partial)
        }
      }

      val work = File(context.cacheDir, "job-export-${exportId.removePrefix("job-")}")
      val generation = File(directory, "$exportId-${java.util.UUID.randomUUID()}")
      if (work.exists()) work.deleteRecursively()
      require(createWorkDirectory(work)) { "The temporary PDF export directory could not be created." }
      if (!generation.mkdirs()) {
        work.deleteRecursively()
        throw IllegalArgumentException("The new PDF export generation could not be created.")
      }
      val pageFiles = JSONArray()
      val blockSnapshots = JSONArray()
      var partial = false
      val placementsBySourcePage = ArrayList<List<JSONArray>>(sourcePageCount)
      val output = File(generation, "translated.pdf")
      val temporary = File(generation, "translated.pdf.incoming")
      var keepGeneration = false

      try {
        val sourceSha256 = sha256(sourceFile)
        val font = fontProvider(context)
        // Keep the verified fallback available for all targets. Some otherwise
        // Latin/Russian documents contain Unicode symbols (such as U+2212)
        // whose source-preserving glyph must be reproduced from the CJK asset.
        val cjkFontPath = cjkFontProvider(context).absolutePath
        for (pageIndex in 0 until sourcePageCount) {
          checkCanceled(shouldCancel)
          val checkpoint = database.page(jobId, pageIndex)
            ?: throw IllegalStateException("Page ${pageIndex + 1} has no saved translation checkpoint.")
          require(checkpoint.optString("status") in setOf("completed", "partial")) {
            "Page ${pageIndex + 1} has not finished translation."
          }
          val blocks = checkpoint.getJSONArray("blocks")
          val existingError = checkpoint.optString("error").takeIf { !checkpoint.isNull("error") && it.isNotBlank() }
          val pageOutput = File(work, "page-$pageIndex.pdf")
          lateinit var result: JSONObject
          lateinit var localPlacements: LocalPagePlacements
          var pagePartial = checkpoint.optString("status") == "partial"
          var attempt = 0
          while (true) {
            checkCanceled(shouldCancel)
            pageOutput.delete()
            val request = JSONObject().put("layoutVersion", PdfLayoutReceipt.LAYOUT_VERSION)
              .put("mode", "preserve-source").put("sourcePageIndex", pageIndex).put("blocks", blocks)
            val current = JSONObject(timing.measure("pdf_page_export") {
              native.exportPage(source, pageIndex, request.toString(), pageOutput.absolutePath, font.absolutePath, cjkFontPath)
            })
            throwOnNativeError(current, "A translated page could not be exported.")
            require(current.optString("mode") == "preserve-source" &&
              PdfLayoutReceipt.requiredInt(current, "layoutVersion") == PdfLayoutReceipt.LAYOUT_VERSION &&
              PdfLayoutReceipt.requiredInt(current, "sourcePageIndex") == pageIndex) {
              "The PDF core returned an incompatible page layout result."
            }
            val shardPageCount = PdfLayoutReceipt.requiredInt(current, "pageCount")
            require(shardPageCount == 1) { "The PDF core changed the source page count." }
            require(current.optString("path") == pageOutput.absolutePath) {
              "The PDF core returned an unexpected page output path."
            }
            require(pageOutput.isFile && pageOutput.length() > 0L && isVerifiedPdf(pageOutput, shardPageCount)) {
              "The PDF core did not create a valid translated page output."
            }
            val issues = current.optJSONArray("issues") ?: JSONArray()
            val placements = current.optJSONArray("placements")
              ?: throw IllegalArgumentException("The PDF core returned no placement map for page ${pageIndex + 1}.")
            localPlacements = PdfLayoutReceipt.validateLocalPlacements(blocks, shardPageCount, placements, issues)
            val changed = PageExportIssues.applyToBlocks(blocks, issues)
            pagePartial = pagePartial || current.optBoolean("partial") || issues.length() > 0
            val pageError = PageExportIssues.pageError(existingError, blocks, pagePartial, issues)
            if (changed || pagePartial || pageError != existingError) {
              timing.measure("checkpoint_write") {
                database.savePage(jobId, pageIndex, if (pagePartial) "partial" else "completed", blocks.toString(),
                  checkpoint.optDouble("width"), checkpoint.optDouble("height"), pageError, blocks.length())
              }
            }
            result = current
            partial = partial || pagePartial
            if (!changed) break
            if (attempt == 1) throw IllegalStateException("Page ${pageIndex + 1} layout findings did not stabilize during export.")
            attempt++
          }
          if (result.optBoolean("partial")) partial = true
          placementsBySourcePage += localPlacements.outputPages
          pageFiles.put(pageOutput.absolutePath)
          val blocksSnapshot = File(work, "page-$pageIndex-blocks.json")
          writeSynced(blocksSnapshot, JSONObject().put("sourcePageIndex", pageIndex)
            .put("blocks", JSONArray(blocks.toString())).toString())
          blockSnapshots.put(blocksSnapshot.absolutePath)
        }

        checkCanceled(shouldCancel)
        val shards = JSONArray()
        for (sourceIndex in 0 until sourcePageCount) {
          shards.put(JSONObject().put("path", pageFiles.getString(sourceIndex))
            .put("sourcePageIndex", sourceIndex).put("expectedPageCount", 1)
            .put("blocksPath", blockSnapshots.getString(sourceIndex)))
        }
        val expectedOutputCount = sourcePageCount
        val mergeRequest = JSONObject().put("layoutVersion", PdfLayoutReceipt.LAYOUT_VERSION)
          .put("mode", "preserve-source").put("sourcePath", sourceFile.absolutePath)
          .put("fontPath", font.absolutePath).put("cjkFontPath", cjkFontPath).put("shards", shards)
        val merged = JSONObject(timing.measure("pdf_merge") { native.mergePdf(mergeRequest.toString(), temporary.absolutePath) })
        throwOnNativeError(merged, "The translated pages could not be assembled.")
        require(merged.optString("mode") == "preserve-source" &&
          PdfLayoutReceipt.requiredInt(merged, "layoutVersion") == PdfLayoutReceipt.LAYOUT_VERSION) {
          "The PDF core returned an incompatible merged layout result."
        }
        val outputPageCount = PdfLayoutReceipt.requiredInt(merged, "pageCount")
        require(outputPageCount == expectedOutputCount && isVerifiedPdf(temporary, outputPageCount)) {
          "The merged translated PDF failed its page-count or integrity check."
        }
        checkCanceled(shouldCancel)
        val receipt = PdfLayoutReceipt.fromMerge(sourcePageCount, sourceSha256, checkpointDigest,
          sha256(temporary), merged.optJSONArray("sourcePageRanges")
            ?: throw IllegalArgumentException("The PDF core returned no source-page ranges."),
          merged.optJSONArray("pages") ?: throw IllegalArgumentException("The PDF core returned no output-page map."),
          placementsBySourcePage)
        atomicReplace(temporary, output)
        require(isVerifiedPdf(output, outputPageCount)) { "The saved translated PDF failed its final integrity check." }
        val outputSha256 = sha256(output)
        require(outputSha256 == receipt.outputSha256) { "The saved translated PDF digest changed during publication." }
        val receiptFile = File(generation, RECEIPT_NAME)
        writeSynced(receiptFile, receipt.toJson().toString())
        val verifiedReceipt = PdfLayoutReceipt.fromJson(JSONObject(receiptFile.readText()))
        require(verifiedReceipt.matches(sourceSha256, checkpointDigest, outputSha256)) {
          "The saved PDF layout receipt failed its final verification."
        }
        checkCanceled(shouldCancel)
        partial = partial || hasPartialPages(jobId, sourcePageCount)
        if (persistExport) {
          database.saveExport(exportId, output.absolutePath, name)
          keepGeneration = true
          saved?.first?.takeIf { it != output.absolutePath }?.let(::File)?.let { deleteOwnedGeneration(it, exportId) }
        } else {
          keepGeneration = true
        }
        return FinalizedJobPdf(output, exportId, name, outputPageCount,
          sourcePageCount, receipt.layoutVersion, receipt, partial)
      } finally {
        work.deleteRecursively()
        temporary.delete()
        if (!keepGeneration) generation.deleteRecursively()
      }
    }
  }

  /** Returns the current verified receipt without rebuilding or publishing an export. */
  fun cachedReceipt(jobId: String): PdfLayoutReceipt? = synchronized(locks.computeIfAbsent(exportId(jobId)) { Any() }) {
    val job = database.jobJson(jobId) ?: return@synchronized null
    val pageCount = job.optInt("totalPages", 0)
    if (pageCount <= 0) return@synchronized null
    val id = exportId(jobId)
    val publishedPath = database.export(id)?.first ?: return@synchronized null
    val source = sourcePath(jobId)?.let(::File)?.takeIf { it.isFile } ?: return@synchronized null
    val digest = runCatching { checkpointDigest(jobId, pageCount) }.getOrNull() ?: return@synchronized null
    loadVerifiedReceipt(File(publishedPath), id, pageCount, source, digest)
  }

  /** Removes only an app-owned generation that is not currently published for this job. */
  fun deleteUnpublished(result: FinalizedJobPdf) {
    synchronized(locks.computeIfAbsent(result.exportId) { Any() }) {
      if (database.export(result.exportId)?.first == result.file.absolutePath) return
      deleteOwnedGeneration(result.file, result.exportId)
    }
  }

  fun delete(jobId: String) {
    synchronized(locks.computeIfAbsent(exportId(jobId)) { Any() }) {
      val id = exportId(jobId)
      database.export(id)?.first?.let(::File)?.let { deleteOwnedGeneration(it, id) }
      database.deleteExport(id)
      File(context.filesDir, "exports").listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("$id-") && File(it, "translated.pdf").isFile }
        .forEach {
          verifiedReceipts.remove(File(it, "translated.pdf").absolutePath)
          it.deleteRecursively()
        }
      File(context.cacheDir, "job-export-${id.removePrefix("job-")}").deleteRecursively()
    }
  }

  private fun hasPartialPages(jobId: String, sourcePageCount: Int): Boolean = database.pageRows(jobId).let { rows ->
    (0 until rows.length()).any { row ->
      val item = rows.getJSONObject(row)
      item.getInt("index") in 0 until sourcePageCount && item.optString("status") == "partial"
    }
  }

  private data class ReceiptFingerprint(
    val outputLength: Long,
    val outputModified: Long,
    val receiptLength: Long,
    val receiptModified: Long,
    val sourcePath: String,
    val sourceLength: Long,
    val sourceModified: Long,
    val checkpointDigest: String,
  )

  private fun loadVerifiedReceipt(file: File, exportId: String, sourcePageCount: Int, sourceFile: File,
                                  checkpointDigest: String): PdfLayoutReceipt? = runCatching {
    if (!isOwnedGenerationFile(file, exportId)) return null
    val receiptFile = File(file.parentFile, RECEIPT_NAME)
    if (!file.isFile || !receiptFile.isFile || !sourceFile.isFile) return null
    val fingerprint = ReceiptFingerprint(file.length(), file.lastModified(), receiptFile.length(), receiptFile.lastModified(),
      sourceFile.canonicalPath, sourceFile.length(), sourceFile.lastModified(), checkpointDigest)
    verifiedReceipts[file.absolutePath]?.takeIf { it.first == fingerprint }?.let { cached ->
      return PdfLayoutReceipt.fromJson(JSONObject(cached.second))
    }
    val receipt = PdfLayoutReceipt.fromJson(JSONObject(receiptFile.readText()))
    val sourceSha256 = sha256(sourceFile)
    if (receipt.sourcePageCount != sourcePageCount ||
      !receipt.matches(sourceSha256, checkpointDigest, sha256(file)) ||
      !isVerifiedPdf(file, receipt.outputPageCount)) return null
    verifiedReceipts[file.absolutePath] = fingerprint to receipt.toJson().toString()
    receipt
  }.getOrNull()

  private fun checkpointDigest(jobId: String, sourcePageCount: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (index in 0 until sourcePageCount) {
      val page = database.page(jobId, index) ?: throw IllegalStateException("Page ${index + 1} has no saved translation checkpoint.")
      val layoutBlocks = JSONArray()
      val sourceBlocks = page.getJSONArray("blocks")
      for (blockIndex in 0 until sourceBlocks.length()) {
        val layoutInput = JSONObject(sourceBlocks.getJSONObject(blockIndex).toString())
        listOf("status", "error", "exportIssues", "translationReviewRequired").forEach { layoutInput.remove(it) }
        layoutBlocks.put(layoutInput)
      }
      val record = JSONObject().put("index", index).put("width", page.optDouble("width"))
        .put("height", page.optDouble("height")).put("blocks", layoutBlocks)
      digest.update(record.toString().toByteArray(Charsets.UTF_8))
      digest.update('\n'.code.toByte())
    }
    return digest.digest().toHex()
  }

  private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").run {
    file.inputStream().buffered().use { stream ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        update(buffer, 0, count)
      }
    }
    digest().toHex()
  }

  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private fun writeSynced(file: File, contents: String) {
    FileOutputStream(file).use { stream ->
      stream.write(contents.toByteArray(Charsets.UTF_8))
      stream.fd.sync()
    }
  }

  private fun isOwnedGenerationFile(file: File, exportId: String): Boolean {
    val exports = File(context.filesDir, "exports").canonicalFile
    val canonical = file.canonicalFile
    val parent = canonical.parentFile ?: return false
    return parent.parentFile == exports && parent.name.startsWith("$exportId-") &&
      canonical.name == "translated.pdf" && File(parent, RECEIPT_NAME).isFile
  }

  private fun deleteOwnedGeneration(file: File, exportId: String) {
    val exports = File(context.filesDir, "exports").canonicalFile
    val canonical = file.canonicalFile
    val parent = canonical.parentFile ?: return
    if (parent == exports && canonical.name == "$exportId.pdf") {
      canonical.delete()
    } else if (parent.parentFile == exports && parent.name.startsWith("$exportId-") &&
      canonical.name == "translated.pdf") {
      parent.deleteRecursively()
      verifiedReceipts.remove(file.absolutePath)
    }
  }

  private fun isVerifiedPdf(file: File, expectedPages: Int): Boolean {
    if (!file.isFile || file.length() < PDF_HEADER_BYTES) return false
    val header = ByteArray(PDF_HEADER_BYTES)
    val read = openPdfInputStream(file).use { it.read(header) }
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

  private fun displayName(documentName: String, sourceLanguage: String, targetLanguage: String): String {
    val base = documentName.substringBeforeLast('.', documentName).trim()
      .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().take(100).ifBlank { "translated" }
    return "$base-$sourceLanguage-$targetLanguage.pdf"
  }

  private fun atomicReplace(source: File, target: File) {
    try {
      moveFile(source, target, true)
    } catch (_: AtomicMoveNotSupportedException) {
      moveFile(source, target, false)
    }
  }

  /** File replacement strategy shared by the default installer and filesystem-fallback tests. */
  private fun exportId(jobId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(jobId.toByteArray(Charsets.UTF_8))
    return "job-" + digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  companion object {
    private const val PDF_HEADER_BYTES = 5
    private const val RECEIPT_NAME = "layout-receipt.json"
    private val locks = ConcurrentHashMap<String, Any>()
    private val verifiedReceipts = ConcurrentHashMap<String, Pair<ReceiptFingerprint, String>>()
  }
}

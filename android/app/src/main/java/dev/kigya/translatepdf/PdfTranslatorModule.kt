package dev.kigya.translatepdf

import android.app.Activity
import android.app.LocaleManager
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.uimanager.ViewManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class PdfTranslatorModule(private val context: ReactApplicationContext) :
  ReactContextBaseJavaModule(context), ActivityEventListener {

  private val database = TranslatorDatabase(context)
  private val downloader = ModelDownloader(context, database)
  private val io: ExecutorService = Executors.newFixedThreadPool(2)
  private val jobStartLock = Any()
  @Volatile private var pendingImport: Promise? = null
  @Volatile private var pendingExport: Promise? = null
  @Volatile private var pendingExportFile: File? = null
  @Volatile private var pendingExportId: String? = null
  private val thumbnailDirectory = File(context.cacheDir, "thumbnails").apply { mkdirs() }
  private val translatedPageDirectory = File(context.cacheDir, "translated-page-work").apply { mkdirs() }

  init {
    context.addActivityEventListener(this)
    database.recoverInterruptedJobs(PdfTranslationService.activeJobId)
  }

  override fun getName(): String = NAME

  @ReactMethod
  fun bootstrap(promise: Promise) = runAsync(promise, "BOOTSTRAP_FAILED") {
    val manager = context.getSystemService(Activity.ACTIVITY_SERVICE) as android.app.ActivityManager
    val info = android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    val stat = StatFs(context.filesDir.absolutePath)
    val availableDisk = stat.availableBlocksLong * stat.blockSizeLong
    val recommendation = MemoryPolicy.recommend(info.totalMem, info.availMem, info.threshold, info.lowMemory, availableDisk)
    JSONObject()
      .put("schemaVersion", 1)
      .put("device", JSONObject()
        .put("manufacturer", Build.MANUFACTURER.orEmpty())
        .put("model", Build.MODEL.orEmpty())
        .put("systemLocale", Resources.getSystem().configuration.locales[0].toLanguageTag())
        .put("apiLevel", Build.VERSION.SDK_INT)
        .put("totalMemoryBytes", info.totalMem)
        .put("availableMemoryBytes", recommendation.availableMemoryBytes)
        .put("systemLowMemoryThresholdBytes", recommendation.systemLowMemoryThresholdBytes)
        .put("systemLowMemory", info.lowMemory)
        .put("reservedMemoryBytes", recommendation.reserveBytes)
        .put("availableDiskBytes", availableDisk)
        .put("recommendedModelId", recommendation.recommendedModelId)
        .put("requiredPipelineMemoryBytes", recommendation.requiredPipelineMemoryBytes)
        .put("requiredDiskBytes", recommendation.requiredDiskBytes)
        .put("memoryEstimateEligible", recommendation.memoryEstimateEligible)
        .put("diskEstimateEligible", recommendation.diskEstimateEligible)
        .put("resourceEstimateEligible", recommendation.resourceEstimateEligible)
        .put("recommendedModelAvailable", recommendation.recommendationAvailable)
        .put("recommendationReason", recommendation.reason)
        .put("recommendationVerified", recommendation.recommendationAvailable))
      .put("preferences", JSONObject()
        .put("theme", database.setting("theme", "system"))
        .put("language", database.setting("language", "system")))
      .put("onboarding", JSONObject()
        .put("version", database.setting("onboarding_version", "1").toIntOrNull() ?: 1)
        .put("page", database.setting("onboarding_page", "0").toIntOrNull() ?: 0)
        .put("done", database.setting("onboarding_done", "false").toBoolean()))
      .put("models", database.modelsJson())
      .put("documents", database.documentsJson())
      .put("jobs", database.jobsJson())
      .put("glossary", database.glossaryJson())
      .toString()
  }

  @ReactMethod
  fun pickPdf(promise: Promise) {
    if (pendingImport != null) return promise.reject("PICKER_BUSY", "Another PDF picker is already open.")
    val activity = context.currentActivity ?: return promise.reject("PICKER_UNAVAILABLE", "Open the app before choosing a PDF.")
    pendingImport = promise
    context.runOnUiQueueThread picker@{
      try {
        if (activity.isFinishing) throw IllegalStateException("The app closed before the PDF picker opened.")
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE)
          type = "application/pdf"
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, REQUEST_IMPORT)
      } catch (error: Exception) {
        pendingImport = null
        promise.reject("PICKER_UNAVAILABLE", "No system document picker could open.", error)
      }
    }
  }

  @ReactMethod
  fun startModelDownload(modelId: String, promise: Promise) = runAsync(promise, "MODEL_DOWNLOAD_START_FAILED") {
    val model = ModelCatalog.get(modelId)
    if (context.currentActivity == null || context.currentActivity?.isFinishing == true) throw IllegalStateException("Open the app before starting a model download.")
    if (PdfTranslationService.isActive) throw IllegalStateException("Wait until the current background operation finishes before downloading another model.")
    if (!hasStorageFor(model.bytes)) throw IllegalStateException("Not enough free storage for this model and a resumable partial file.")
    if (downloader.isReady(modelId)) {
      database.modelStatus(modelId, "ready", model.bytes)
      return@runAsync model.toJson("ready", model.bytes).toString()
    }
    database.modelStatus(modelId, "downloading", downloader.partialLength(modelId))
    val intent = Intent(context, PdfTranslationService::class.java)
      .setAction(PdfTranslationService.ACTION_START_MODEL)
      .putExtra(PdfTranslationService.EXTRA_MODEL_ID, modelId)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    model.toJson("downloading", downloader.partialLength(modelId)).toString()
  }

  @ReactMethod
  fun pauseModelDownload(modelId: String, promise: Promise) = runAsync(promise, "MODEL_DOWNLOAD_PAUSE_FAILED") {
    val definition = ModelCatalog.get(modelId)
    downloader.pause(modelId)
    database.modelStatus(modelId, "paused", downloader.partialLength(modelId))
    context.startService(Intent(context, PdfTranslationService::class.java).setAction(PdfTranslationService.ACTION_PAUSE_MODEL))
    definition.toJson("paused", downloader.partialLength(modelId)).toString()
  }

  @ReactMethod
  fun deleteModel(modelId: String, promise: Promise) = runAsync(promise, "MODEL_DELETE_FAILED") {
    val definition = ModelCatalog.get(modelId)
    require(!database.modelInUse(modelId)) { "Pause or cancel the active job before deleting its model." }
    downloader.cancel(modelId)
    downloader.fileFor(modelId).delete()
    database.deleteModel(modelId)
    null
  }

  @ReactMethod
  fun startJob(inputJson: String, promise: Promise) = runAsync(promise, "JOB_START_FAILED") { synchronized(jobStartLock) {
    if (PdfTranslationService.isActive) throw IllegalStateException("Wait until the current background operation finishes before starting translation.")
    if (database.hasActiveJob()) throw IllegalStateException("Finish, pause, or cancel the current translation before starting another one.")
    if (context.currentActivity == null || context.currentActivity?.isFinishing == true) {
      throw IllegalStateException("Return to the open app before starting a translation.")
    }
    val input = JSONObject(inputJson)
    val documentId = input.getString("documentId")
    val modelId = input.getString("modelId")
    val path = database.documentPath(documentId) ?: throw IllegalArgumentException("The selected document is no longer available.")
    require(File(path).isFile) { "The imported document is no longer available." }
    require(input.optString("sourceLanguage") == "en" && input.optString("targetLanguage") == "ru") {
      "Only English to Russian translation is available in this prototype."
    }
    require(ModelCatalog.get(modelId).translationEnabled) { "This compact model has not passed quality and device benchmarks and cannot be used for translation yet." }
    if (!downloader.isInstalled(modelId)) throw IllegalStateException("Download and verify the selected model first.")
    val pageCount = database.documentInfo(documentId)?.second ?: throw IllegalArgumentException("The source document could not be inspected.")
    val id = UUID.randomUUID().toString()
    val job = database.addJob(id, input, pageCount)
    try {
      val serviceIntent = Intent(context, PdfTranslationService::class.java)
        .setAction(PdfTranslationService.ACTION_START)
        .putExtra(PdfTranslationService.EXTRA_JOB_ID, id)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent) else context.startService(serviceIntent)
    } catch (error: Exception) {
      database.updateJob(id, status = "failed", error = "Android did not allow the foreground translation service to start.")
      throw error
    }
    requestNotificationPermissionAfterExplicitStart()
    job.toString()
  } }

  /** Request is non-blocking: enqueueing and foreground processing never depend on the user's choice. */
  private fun requestNotificationPermissionAfterExplicitStart() {
    val alreadyGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!ForegroundNotificationPolicy.shouldRequestPermission(Build.VERSION.SDK_INT, alreadyGranted)) return
    context.runOnUiQueueThread {
      val activity = context.currentActivity ?: return@runOnUiQueueThread
      if (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) return@runOnUiQueueThread
      val permissionNowGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
      if (!ForegroundNotificationPolicy.shouldRequestPermission(Build.VERSION.SDK_INT, permissionNowGranted)) return@runOnUiQueueThread
      runCatching { activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS) }
    }
  }

  @ReactMethod
  fun pauseJob(jobId: String, promise: Promise) = runAsync(promise, "JOB_PAUSE_FAILED") {
    requireJob(jobId)
    context.startService(Intent(context, PdfTranslationService::class.java).setAction(PdfTranslationService.ACTION_PAUSE)
      .putExtra(PdfTranslationService.EXTRA_JOB_ID, jobId))
    database.updateJob(jobId, status = "paused")
    database.jobJson(jobId)?.toString() ?: throw IllegalArgumentException("Translation job was not found.")
  }

  @ReactMethod
  fun resumeJob(jobId: String, promise: Promise) = runAsync(promise, "JOB_RESUME_FAILED") { synchronized(jobStartLock) {
    if (PdfTranslationService.isActive) throw IllegalStateException("Wait until the current background operation finishes before resuming translation.")
    val job = requireJob(jobId)
    require(job.optString("status") in setOf("paused", "queued", "failed")) { "This translation job cannot be resumed in its current state." }
    require(!database.hasActiveJob(jobId)) { "Finish, pause, or cancel the current translation before resuming another one." }
    val activity = context.currentActivity
    require(activity != null && !activity.isFinishing) { "Return to the open app before resuming a translation." }
    database.updateJob(jobId, status = "queued", stage = "queued", clearError = true)
    val intent = Intent(context, PdfTranslationService::class.java).setAction(PdfTranslationService.ACTION_START)
      .putExtra(PdfTranslationService.EXTRA_JOB_ID, jobId)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    database.jobJson(jobId)?.toString() ?: throw IllegalArgumentException("Translation job was not found.")
  } }

  @ReactMethod
  fun cancelJob(jobId: String, promise: Promise) = runAsync(promise, "JOB_CANCEL_FAILED") {
    requireJob(jobId)
    context.startService(Intent(context, PdfTranslationService::class.java).setAction(PdfTranslationService.ACTION_CANCEL)
      .putExtra(PdfTranslationService.EXTRA_JOB_ID, jobId))
    database.updateJob(jobId, status = "canceled", error = "Canceled by the user.")
    database.jobJson(jobId)?.toString() ?: throw IllegalArgumentException("Translation job was not found.")
  }

  @ReactMethod
  fun deleteJob(jobId: String, promise: Promise) = runAsync(promise, "JOB_DELETE_FAILED") {
    val job = requireJob(jobId)
    require(job.optString("status") !in setOf("running", "queued")) { "Pause or cancel the active translation before deleting it." }
    JobPdfExporter(context, database).delete(jobId)
    database.deleteJob(jobId)
    null
  }

  @ReactMethod
  fun getJob(jobId: String, pageIndex: Int, promise: Promise) = runAsync(promise, "JOB_READ_FAILED") {
    val job = requireJob(jobId)
    val pages = database.pageRows(jobId)
    val selected = if (pageIndex in 0 until job.getInt("totalPages")) database.page(jobId, pageIndex) else null
    JSONObject().put("job", job).put("pages", pages).put("selectedPage", selected ?: JSONObject.NULL).toString()
  }

  @ReactMethod
  fun renderPage(documentId: String, pageIndex: Int, width: Int, promise: Promise) = runAsync(promise, "THUMBNAIL_FAILED") {
    val path = database.documentPath(documentId) ?: throw IllegalArgumentException("The document is no longer available.")
    renderCachedPage("original_$documentId", path, pageIndex, width)
  }

  @ReactMethod
  fun renderTranslatedPage(jobId: String, pageIndex: Int, width: Int, promise: Promise) = runAsync(promise, "TRANSLATED_THUMBNAIL_FAILED") {
    val job = requireJob(jobId)
    require(job.optString("status") in setOf("completed", "partial")) { "Wait until translation has finished before previewing translated pages." }
    require(pageIndex in 0 until job.getInt("totalPages")) { "The requested page is outside this document." }
    val page = database.page(jobId, pageIndex) ?: throw IllegalStateException("This page has not been checkpointed yet.")
    require(page.optString("status") in setOf("completed", "partial")) { "This page has not finished translation yet." }
    val source = database.jobDocumentPath(jobId) ?: throw IllegalStateException("The source document is unavailable.")
    existingThumbnail("translated_$jobId", pageIndex, width) ?: run {
      val temporaryPdf = File(translatedPageDirectory, "$jobId-$pageIndex-${UUID.randomUUID()}.pdf")
      try {
        val result = JSONObject(NativeCore.exportPage(source, pageIndex, page.getJSONArray("blocks").toString(), temporaryPdf.absolutePath, fontPath()))
        if (result.has("error")) throw IllegalStateException(result.optString("error", "The translated page could not be rendered."))
        require(temporaryPdf.isFile && temporaryPdf.length() > 0) { "The PDF core did not create a translated page preview." }
        renderCachedPage("translated_$jobId", temporaryPdf.absolutePath, 0, width, pageIndex)
      } finally { temporaryPdf.delete() }
    }
  }

  @ReactMethod
  fun previewJob(jobId: String, promise: Promise) {
    val job = database.jobJson(jobId) ?: return promise.reject("JOB_NOT_FOUND", "Translation job was not found.")
    if (job.optString("status") !in setOf("completed", "partial")) return promise.reject("JOB_NOT_READY", "Wait until translation has finished before previewing.")
    io.execute {
      try {
        val (file, id, name) = buildTranslatedPdf(jobId)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.runOnUiQueueThread {
          try {
            val viewer = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val activity = context.currentActivity ?: throw IllegalStateException("Open the app before previewing the translated PDF.")
            activity.startActivity(viewer)
            promise.resolve(JSONObject().put("id", id).put("uri", uri.toString()).put("displayName", name).toString())
          } catch (error: Exception) {
            promise.reject("PREVIEW_FAILED", error.message ?: "No PDF viewer could open the translated document.", error)
          }
        }
      } catch (error: Exception) {
        promise.reject("PREVIEW_FAILED", error.message ?: "The translated PDF could not be prepared.", error)
      }
    }
  }

  @ReactMethod
  fun exportJob(jobId: String, promise: Promise) {
    if (pendingExport != null) return promise.reject("EXPORT_BUSY", "Another export destination is already open.")
    val job = database.jobJson(jobId) ?: return promise.reject("JOB_NOT_FOUND", "Translation job was not found.")
    if (job.optString("status") !in setOf("completed", "partial")) return promise.reject("JOB_NOT_READY", "Wait until translation has finished before exporting.")
    pendingExport = promise
    io.execute {
      try {
        val (merged, id, name) = buildTranslatedPdf(jobId)
        pendingExportId = id
        context.runOnUiQueueThread export@{
          val activity = context.currentActivity
          if (activity == null || activity.isFinishing) {
            pendingExport?.reject("EXPORT_UNAVAILABLE", "Open the app to choose where to save the translated PDF.")
            pendingExport = null
            return@export
          }
          pendingExportFile = merged
          activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, name)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
          }, REQUEST_EXPORT)
        }
      } catch (error: Exception) {
        pendingExport?.reject("EXPORT_FAILED", error.message ?: "The translated PDF could not be exported.", error)
        pendingExport = null
      }
    }
  }

  @ReactMethod
  fun shareExport(exportId: String, promise: Promise) {
    io.execute {
      try {
        val saved = database.export(exportId) ?: throw IllegalArgumentException("The exported PDF is no longer available.")
        val file = File(saved.first)
        require(file.isFile) { "The exported PDF is no longer available." }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
          type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.runOnUiQueueThread share@{
          try {
            val activity = context.currentActivity ?: throw IllegalStateException("Open the app before sharing the PDF.")
            activity.startActivity(Intent.createChooser(intent, "Share translated PDF"))
            promise.resolve(null)
          } catch (error: Exception) { promise.reject("SHARE_FAILED", error.message ?: "The PDF could not be shared.", error) }
        }
      } catch (error: Exception) { promise.reject("SHARE_FAILED", error.message ?: "The PDF could not be shared.", error) }
    }
  }

  @ReactMethod
  fun openPdf(documentId: String, promise: Promise) {
    io.execute {
      try {
        val path = database.documentPath(documentId) ?: throw IllegalArgumentException("The document is no longer available.")
        val file = File(path)
        require(file.isFile) { "The document is no longer available." }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.runOnUiQueueThread open@{
          try {
            val activity = context.currentActivity ?: throw IllegalStateException("Open the app before opening a PDF.")
            activity.startActivity(intent)
            promise.resolve(null)
          } catch (error: ActivityNotFoundException) { promise.reject("OPEN_PDF_FAILED", "No PDF viewer is available.", error) }
          catch (error: Exception) { promise.reject("OPEN_PDF_FAILED", error.message ?: "Unable to open this PDF.", error) }
        }
      } catch (error: Exception) { promise.reject("OPEN_PDF_FAILED", error.message ?: "Unable to open this PDF.", error) }
    }
  }

  @ReactMethod
  fun setOnboarding(stateJson: String, promise: Promise) = runAsync(promise, "ONBOARDING_SAVE_FAILED") {
    val state = JSONObject(stateJson)
    val version = state.getInt("version"); val page = state.getInt("page"); val done = state.getBoolean("done")
    require(version > 0 && page in 0..3) { "Onboarding state is invalid." }
    database.setSetting("onboarding_version", version.toString())
    database.setSetting("onboarding_page", page.toString())
    database.setSetting("onboarding_done", done.toString())
    JSONObject().put("version", version).put("page", page).put("done", done).toString()
  }

  @ReactMethod
  fun setSettings(settingsJson: String, promise: Promise) = runAsync(promise, "SETTINGS_SAVE_FAILED") {
    val settings = JSONObject(settingsJson)
    if (settings.has("theme")) {
      val theme = settings.getString("theme")
      require(theme in setOf("system", "light", "dark")) { "Theme selection is invalid." }
      database.setSetting("theme", theme)
    }
    if (settings.has("language")) {
      val language = settings.getString("language")
      require(language == "system" || Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$").matches(language)) { "Language selection is invalid." }
      database.setSetting("language", language)
      if (Build.VERSION.SDK_INT >= 33) {
        val localeManager = context.getSystemService(LocaleManager::class.java)
        localeManager.applicationLocales = if (language == "system") LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language)
      }
    }
    JSONObject().put("theme", database.setting("theme", "system")).put("language", database.setting("language", "system")).toString()
  }

  @ReactMethod
  fun saveGlossaryEntry(entryJson: String, promise: Promise) = runAsync(promise, "GLOSSARY_SAVE_FAILED") {
    val entry = JSONObject(entryJson)
    database.saveGlossary(entry.getString("id"), entry.getString("source"), entry.getString("target")).toString()
  }

  @ReactMethod
  fun deleteGlossaryEntry(entryId: String, promise: Promise) = runAsync(promise, "GLOSSARY_DELETE_FAILED") {
    database.deleteGlossaryEntry(entryId); null
  }

  override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, intent: Intent?) {
    if (requestCode == REQUEST_IMPORT) {
      val promise = pendingImport.also { pendingImport = null } ?: return
      if (resultCode != Activity.RESULT_OK || intent?.data == null) return promise.resolve("null")
      io.execute {
        try { promise.resolve(importDocument(intent.data!!).toString()) }
        catch (error: Exception) { promise.reject("PDF_IMPORT_FAILED", error.message ?: "The PDF could not be imported.", error) }
      }
    } else if (requestCode == REQUEST_EXPORT) {
      val promise = pendingExport.also { pendingExport = null } ?: return
      val file = pendingExportFile.also { pendingExportFile = null }
      val exportId = pendingExportId.also { pendingExportId = null }
      if (resultCode != Activity.RESULT_OK || intent?.data == null) return promise.reject("EXPORT_CANCELED", "PDF export was canceled.")
      io.execute {
        try {
          val target = intent.data!!
          val source = file ?: error("The exported PDF is unavailable.")
          copyToUri(context.contentResolver, source, target)
          val id = exportId ?: error("Export metadata is unavailable.")
          val name = database.export(id)?.second ?: "translated.pdf"
          val result = JSONObject().put("id", id).put("uri", target.toString()).put("displayName", name)
          promise.resolve(result.toString())
        } catch (error: Exception) { promise.reject("EXPORT_SAVE_FAILED", error.message ?: "The translated PDF could not be saved.", error) }
      }
    }
  }

  override fun onNewIntent(intent: Intent) = Unit

  override fun invalidate() {
    context.removeActivityEventListener(this)
    io.shutdownNow()
    super.invalidate()
  }

  private fun importDocument(uri: Uri): JSONObject {
    val targetDirectory = File(context.filesDir, "documents").apply { mkdirs() }
    val id = UUID.randomUUID().toString()
    val temp = File(targetDirectory, "$id.incoming")
    val target = File(targetDirectory, "$id.pdf")
    context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { output -> input.copyTo(output) } }
      ?: throw IOException("The selected file could not be read.")
    val header = ByteArray(5)
    val headerLength = FileInputStream(temp).use { it.read(header) }
    if (temp.length() < 5 || headerLength < 5 || String(header, Charsets.US_ASCII) != "%PDF-") {
      temp.delete(); throw IllegalArgumentException("The selected file is not a valid PDF.")
    }
    val inspection = JSONObject(NativeCore.inspect(temp.absolutePath))
    if (inspection.has("error")) { temp.delete(); throw IllegalArgumentException(inspection.optString("error")) }
    val pageCount = inspection.optInt("pageCount", 0)
    if (pageCount <= 0 || pageCount > 10_000) { temp.delete(); throw IllegalArgumentException("The PDF has an unsupported page count.") }
    if (!temp.renameTo(target)) { temp.delete(); throw IOException("The PDF could not be stored privately.") }
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(target).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    val sourceSha256 = digest.digest().joinToString("") { "%02x".format(it) }
    return database.upsertDocument(id, safePdfName(queryDisplayName(uri)), target.absolutePath, pageCount, sourceSha256)
  }

  private fun buildTranslatedPdf(jobId: String): Triple<File, String, String> {
    val job = requireJob(jobId)
    require(job.optString("status") in setOf("completed", "partial")) { "Wait until translation has finished before exporting." }
    val previousStage = job.optString("stage", "complete")
    database.updateJob(jobId, stage = "exporting")
    try {
      val result = JobPdfExporter(context, database).getOrBuild(jobId)
      database.updateJob(jobId, status = if (result.partial) "partial" else "completed", stage = "complete", clearError = true)
      return Triple(result.file, result.exportId, result.displayName)
    } catch (error: Exception) {
      database.updateJob(jobId, status = "failed", stage = "exporting",
        error = error.message ?: "The saved translated PDF could not be verified.")
      throw error
    } finally {
      if (database.jobJson(jobId)?.optString("status") in setOf("completed", "partial")) {
        database.updateJob(jobId, stage = previousStage)
      }
    }
  }

  private fun queryDisplayName(uri: Uri): String {
    var name: String? = null
    val cursor: Cursor? = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use { if (it.moveToFirst()) name = it.getString(0) }
    return name?.takeIf(String::isNotBlank) ?: "document.pdf"
  }

  private fun copyToUri(resolver: ContentResolver, source: File, target: Uri) {
    resolver.openOutputStream(target, "wt")?.use { output -> FileInputStream(source).use { input -> input.copyTo(output) } }
      ?: throw IOException("The chosen destination could not be opened.")
  }

  private fun launchPdf(file: File, error: String) {
    require(file.isFile) { "The PDF is no longer available." }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.currentActivity?.startActivity(intent) ?: throw IllegalStateException("Open the app before opening a PDF.") }
    catch (_: ActivityNotFoundException) { throw IllegalStateException(error) }
  }

  private fun fontPath(): String {
    val target = File(context.cacheDir, "NotoSans-Regular.ttf")
    return FontAsset.ensure(context, target).absolutePath
  }

  private fun hasStorageFor(bytes: Long): Boolean {
    val stats = StatFs(context.filesDir.absolutePath)
    val available = stats.availableBlocksLong * stats.blockSizeLong
    return available >= MemoryPolicy.requiredDiskBytes(bytes)
  }

  private fun cleanThumbnailCache() {
    val files = thumbnailDirectory.listFiles()?.sortedBy(File::lastModified) ?: return
    var total = files.sumOf(File::length)
    val limit = 48L * 1024L * 1024L
    for (file in files) {
      if (total <= limit) break
      total -= file.length()
      file.delete()
    }
  }

  private fun renderCachedPage(cacheKey: String, pdfPath: String, nativePageIndex: Int, width: Int, cachePageIndex: Int = nativePageIndex): String {
    require(cachePageIndex >= 0 && width in 64..2048) { "The page or preview size is invalid." }
    cleanThumbnailCache()
    val file = File(thumbnailDirectory, "${cacheKey}_${cachePageIndex}_$width.png")
    synchronized(thumbnailDirectory) {
      if (!isPng(file)) file.delete()
      if (!file.exists()) {
        val temporary = File(thumbnailDirectory, "${file.name}.tmp")
        temporary.delete()
        val bitmap = NativeCore.render(pdfPath, nativePageIndex, width)
        try {
          FileOutputStream(temporary).use { out ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) { "The PDF preview could not be encoded." }
            out.fd.sync()
          }
        } finally { bitmap.recycle() }
        check(temporary.renameTo(file)) { "The PDF preview could not be committed to the cache." }
      }
    }
    return Uri.fromFile(file).toString()
  }

  private fun existingThumbnail(cacheKey: String, pageIndex: Int, width: Int): String? {
    require(pageIndex >= 0 && width in 64..2048) { "The page or preview size is invalid." }
    val file = File(thumbnailDirectory, "${cacheKey}_${pageIndex}_$width.png")
    return synchronized(thumbnailDirectory) {
      if (isPng(file)) Uri.fromFile(file).toString() else { file.delete(); null }
    }
  }

  private fun isPng(file: File): Boolean {
    if (!file.isFile || file.length() < PNG_SIGNATURE.size) return false
    return FileInputStream(file).use { input -> PNG_SIGNATURE.indices.all { input.read() == (PNG_SIGNATURE[it].toInt() and 0xff) } }
  }

  private fun requireJob(id: String): JSONObject = database.jobJson(id)
    ?: throw IllegalArgumentException("Translation job was not found.")

  private fun safePdfName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(100)
    return if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf"
  }

  private fun runAsync(promise: Promise, code: String, action: () -> String?) {
    io.execute {
      try {
        when (val value = action()) {
          null -> promise.resolve(null)
          else -> promise.resolve(value)
        }
      } catch (error: Exception) {
        promise.reject(code, error.message ?: "The local PDF translator operation failed.", error)
      } catch (error: UnsatisfiedLinkError) {
        promise.reject("NATIVE_CORE_UNAVAILABLE", "The local PDF and translation engine is unavailable in this build.", error)
      }
    }
  }

  companion object {
    const val NAME = "PdfTranslator"
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private const val REQUEST_IMPORT = 6101
    private const val REQUEST_EXPORT = 6102
    private const val REQUEST_POST_NOTIFICATIONS = 0xD339
  }
}

internal class PdfTranslatorPackage : ReactPackage {
  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> = listOf(PdfTranslatorModule(reactContext))
  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}

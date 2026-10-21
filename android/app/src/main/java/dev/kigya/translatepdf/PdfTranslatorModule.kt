package dev.kigya.translatepdf

import android.app.Activity
import android.app.NotificationManager
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
import androidx.core.app.NotificationManagerCompat
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
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

internal class PdfTranslatorModule(
  private val context: ReactApplicationContext,
  private val availableStorageBytes: (File) -> Long = { path ->
    StatFs(path.absolutePath).let { it.availableBlocksLong * it.blockSizeLong }
  },
  private val startModelService: (Intent) -> Unit = { intent ->
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
    else context.startService(intent)
  },
) :
  ReactContextBaseJavaModule(context), ActivityEventListener {

  private val database = TranslatorDatabase(context)
  private val downloader = ModelDownloader(context, database)
  private val io: ExecutorService = Executors.newFixedThreadPool(2)
  private val jobStartLock = Any()
  @Volatile private var pendingImport: Promise? = null
  @Volatile private var pendingExport: Promise? = null
  @Volatile private var pendingExportFile: File? = null
  @Volatile private var pendingExportId: String? = null
  @Volatile private var pendingExportMime: String = "application/pdf"
  @Volatile private var pendingNotificationPermission: Promise? = null
  private val thumbnailDirectory = File(context.cacheDir, "thumbnails").apply { mkdirs() }
  private val translatedPageDirectory = File(context.cacheDir, "translated-page-work").apply { mkdirs() }

  init {
    context.addActivityEventListener(this)
    database.recoverInterruptedJobs(PdfTranslationService.activeJobId)
  }

  override fun getName(): String = NAME

  @ReactMethod
  fun bootstrap(promise: Promise) = runAsync(promise, "BOOTSTRAP_FAILED") {
    // Read model rows once, then derive a file-aware view without writing it
    // back. A concurrent download/delete may change the database after this
    // read; bootstrap must never overwrite that newer state.
    val models = effectiveModelSnapshot(database.modelsJson(), ModelCatalog.models, downloader)
    val manager = context.getSystemService(Activity.ACTIVITY_SERVICE) as android.app.ActivityManager
    val info = android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    val stat = StatFs(context.filesDir.absolutePath)
    val availableDisk = stat.availableBlocksLong * stat.blockSizeLong
    val downloaded = (0 until models.length()).associate { index ->
      val value = models.getJSONObject(index)
      val id = value.getString("id")
      id to if (value.getString("status") == "ready") value.getLong("bytes") else value.getLong("partialBytes")
    }
    val installedModelBytes = ModelCatalog.models.sumOf { downloader.installedLength(it.id) }
    val partialDownloadBytes = ModelCatalog.models.sumOf { downloader.partialLength(it.id) }
    val recommendation = MemoryPolicy.recommend(info.totalMem, info.availMem, info.threshold, info.lowMemory, availableDisk, downloaded)
    val initialSelection = (0 until models.length()).map { models.getJSONObject(it) }
      .lastOrNull { it.getString("status") == "ready" }?.getString("id") ?: recommendation.recommendedModelId
    val selectedModelId = database.selectedModelId(initialSelection)
    val selectedPair = database.selectedLanguagePair()
    for (index in 0 until models.length()) {
      val value = models.getJSONObject(index)
      val model = ModelCatalog.get(value.getString("id"))
      val installedBytes = downloader.installedLength(model.id)
      val partialBytes = downloader.partialLength(model.id)
      value.put("storedBytes", installedBytes + partialBytes)
        .put("partialBytes", partialBytes)
      MemoryPolicy.resources(model, info.totalMem, info.availMem, info.threshold, info.lowMemory, availableDisk, downloaded[model.id] ?: 0).applyTo(value)
    }
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
        .put("recommendationVerified", false)
        .put("storage", JSONObject()
          .put("installedModelBytes", installedModelBytes)
          .put("partialDownloadBytes", partialDownloadBytes)
          .put("freeDiskBytes", availableDisk)))
      .put("preferences", JSONObject()
        .put("theme", database.setting("theme", "system"))
        .put("language", database.setting("language", "system"))
        .put("sourceLanguage", selectedPair.first)
        .put("targetLanguage", selectedPair.second)
        .put("selectedModelId", selectedModelId)
        .put("selectedEngine", database.selectedEngine(selectedModelId))
        .put("appRating", database.appRating())
        .put("librarySort", database.librarySort())
        .put("notificationExplanationShown", database.setting("notificationExplanationShown", "false").toBoolean()))
      .put("onboarding", JSONObject()
        .put("version", database.setting("onboarding_version", "1").toIntOrNull() ?: 1)
        .put("page", database.setting("onboarding_page", "0").toIntOrNull() ?: 0)
        .put("done", database.setting("onboarding_done", "false").toBoolean()))
      .put("models", models)
      .put("documents", database.documentsJson())
      .put("jobs", database.jobsJson())
      .put("glossary", database.glossaryJson())
      .put("notifications", notificationPermissionSnapshot())
      .toString()
  }

  @ReactMethod
  fun pickPdf(promise: Promise) {
    pickDocument(promise)
  }

  @ReactMethod
  fun pickDocument(promise: Promise) {
    if (pendingImport != null) return promise.reject("PICKER_BUSY", "Another document picker is already open.")
    val activity = context.currentActivity ?: return promise.reject("PICKER_UNAVAILABLE", "Open the app before choosing a document.")
    pendingImport = promise
    context.runOnUiQueueThread picker@{
      try {
        if (activity.isFinishing) throw IllegalStateException("The app closed before the PDF picker opened.")
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE)
          type = "*/*"
          putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
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
    if (downloader.isReady(modelId)) {
      database.modelStatus(modelId, "ready", model.bytes)
      return@runAsync model.toJson("ready", model.bytes).toString()
    }
    require(model.translationEnabled) { "This model is unavailable in the current runtime." }
    if (!hasStorageFor((model.bytes - downloader.partialLength(modelId)).coerceAtLeast(0))) throw IllegalStateException("Not enough free storage for the remaining download and work reserve.")
    val transferToken = downloader.reserve(modelId)
    try {
      downloader.withReservation(modelId, transferToken) {
        database.modelStatus(modelId, "downloading", downloader.partialLength(modelId))
        val intent = Intent(context, PdfTranslationService::class.java)
          .setAction(PdfTranslationService.ACTION_START_MODEL)
          .putExtra(PdfTranslationService.EXTRA_MODEL_ID, modelId)
          .putExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN, transferToken)
        try {
          startModelService(intent)
        } catch (error: Exception) {
          database.modelStatus(modelId, if (downloader.partialLength(modelId) > 0) "paused" else "not_downloaded", downloader.partialLength(modelId))
          throw error
        }
      }
    } catch (error: Exception) {
      downloader.releaseReservation(modelId, transferToken)
      throw error
    }
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
    ModelCatalog.get(modelId)
    require(!database.modelInUse(modelId)) { "Pause or cancel the active job before deleting its model." }
    downloader.cancel(modelId)
    downloader.fileFor(modelId).delete()
    database.deleteModel(modelId)
    null
  }

  @ReactMethod
  fun requestNotificationPermission(promise: Promise) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      promise.resolve(notificationPermissionSnapshot().toString())
      return
    }
    val alreadyGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      android.content.pm.PackageManager.PERMISSION_GRANTED
    if (alreadyGranted || database.setting(SETTING_NOTIFICATION_REQUESTED, "false").toBoolean()) {
      promise.resolve(notificationPermissionSnapshot().toString())
      return
    }
    if (pendingNotificationPermission != null) {
      promise.reject("NOTIFICATION_PERMISSION_BUSY", "A notification permission request is already open.")
      return
    }
    context.runOnUiQueueThread {
      if (pendingNotificationPermission != null) {
        promise.reject("NOTIFICATION_PERMISSION_BUSY", "A notification permission request is already open.")
        return@runOnUiQueueThread
      }
      if (database.setting(SETTING_NOTIFICATION_REQUESTED, "false").toBoolean()) {
        promise.resolve(notificationPermissionSnapshot().toString())
        return@runOnUiQueueThread
      }
      val activity = context.currentActivity
      if (activity == null || activity.isFinishing ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) {
        promise.reject("NOTIFICATION_PERMISSION_UNAVAILABLE", "Open the app before requesting notification permission.")
        return@runOnUiQueueThread
      }
      val permissionActivity = activity as? PermissionAwareActivity
      if (permissionActivity == null) {
        promise.reject("NOTIFICATION_PERMISSION_UNAVAILABLE", "This screen cannot request Android permissions.")
        return@runOnUiQueueThread
      }
      if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED) {
        promise.resolve(notificationPermissionSnapshot().toString())
        return@runOnUiQueueThread
      }
      pendingNotificationPermission = promise
      database.setSetting(SETTING_NOTIFICATION_REQUESTED, "true")
      try {
        permissionActivity.requestPermissions(
          arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS,
          PermissionListener { requestCode, _, _ ->
            if (requestCode != REQUEST_POST_NOTIFICATIONS) return@PermissionListener false
            val pending = pendingNotificationPermission.also { pendingNotificationPermission = null }
            pending?.resolve(notificationPermissionSnapshot().toString())
            true
          },
        )
      } catch (error: Exception) {
        pendingNotificationPermission = null
        database.setSetting(SETTING_NOTIFICATION_REQUESTED, "false")
        promise.reject("NOTIFICATION_PERMISSION_UNAVAILABLE", "Android could not open the notification permission prompt.", error)
      }
    }
  }

  @ReactMethod
  fun getLicenseNotices(promise: Promise) = runAsync(promise, "LICENSES_READ_FAILED") {
    JSONArray().also { notices ->
      licensePaths().forEach { path ->
        val readableTitle = path.removeSuffix(".txt").removeSuffix(".md")
          .replace("/", " · ").replace("__", " · ").replace('-', ' ')
        notices.put(JSONObject().put("id", path).put("title", readableTitle))
      }
    }.toString()
  }

  @ReactMethod
  fun getLicenseNotice(id: String, promise: Promise) = runAsync(promise, "LICENSE_READ_FAILED") {
    val valid = licensePaths().contains(id)
    require(valid) { "The requested license notice is not available." }
    context.assets.open("licenses/$id").bufferedReader().use { it.readText() }
  }

  private fun notificationPermissionSnapshot(): JSONObject {
    val requestedBefore = database.setting(SETTING_NOTIFICATION_REQUESTED, "false").toBoolean()
    val channelDisabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      context.getSystemService(NotificationManager::class.java).getNotificationChannel(PdfTranslationService.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val status = when {
      channelDisabled || (permissionGranted && !NotificationManagerCompat.from(context).areNotificationsEnabled()) -> "denied"
      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "not_required"
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED -> "granted"
      requestedBefore -> "denied"
      else -> "not_determined"
    }
    return JSONObject().put("status", status).put("requestedBefore", requestedBefore)
      .put("canRequest", status == "not_determined" && !requestedBefore)
  }

  private fun licensePaths(directory: String = "licenses"): List<String> =
    context.assets.list(directory).orEmpty().flatMap { name ->
      val path = "$directory/$name"
      val children = context.assets.list(path).orEmpty()
      if (children.isEmpty()) listOf(path.removePrefix("licenses/")) else licensePaths(path)
    }.filter(String::isNotBlank).sorted()

  @ReactMethod
  fun startJob(inputJson: String, promise: Promise) = runAsync(promise, "JOB_START_FAILED") { synchronized(jobStartLock) {
    if (PdfTranslationService.isActive) throw IllegalStateException("Wait until the current background operation finishes before starting translation.")
    if (database.hasActiveJob()) throw IllegalStateException("Finish, pause, or cancel the current translation before starting another one.")
    if (context.currentActivity == null || context.currentActivity?.isFinishing == true) {
      throw IllegalStateException("Return to the open app before starting a translation.")
    }
    val input = JSONObject(inputJson)
    val documentId = input.getString("documentId")
    val engine = input.optJSONObject("engine")
    val engineKind = engine?.optString("kind", "local") ?: "local"
    val modelId = if (engineKind == "online") engine?.optString("id", "") ?: "" else input.getString("modelId")
    require(engineKind == "local" || engineKind == "online") { "Choose a supported translation engine." }
    if (engineKind == "online") {
      require(modelId == "mymemory") { "Choose the supported online translation provider." }
      require(input.optBoolean("onlineConsent")) { "Review and accept the online translation privacy notice before sending document text." }
    }
    val path = database.documentPath(documentId) ?: throw IllegalArgumentException("The selected document is no longer available.")
    require(File(path).isFile) { "The imported document is no longer available." }
    val pair = LanguagePair.fromJson(input)
    LanguagePair.requireValid(pair.first, pair.second)
    val definition = if (engineKind == "local") ModelCatalog.get(modelId) else null
    if (definition != null) {
      require(definition.translationEnabled) { "This model is unavailable in the current runtime." }
      val useGlossary = input.optBoolean("useGlossary", true)
      require(!useGlossary || definition.supportsGlossary || database.glossaryText(null, pair.first, pair.second).isBlank()) { "This model uses direct translation without a glossary. Choose Hy-MT2 to use saved terms." }
      if (!downloader.isInstalled(modelId)) throw IllegalStateException("Download and verify the selected model first.")
    } else input.put("modelId", "mymemory").put("useGlossary", false)
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
    job.toString()
  } }

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
    database.requireCurrentPipeline(jobId)
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
  fun setJobColor(jobId: String, color: String, promise: Promise) = runAsync(promise, "JOB_COLOR_SAVE_FAILED") {
    database.setJobColor(jobId, color.takeIf(String::isNotEmpty))
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
    if (database.documentFormat(documentId) == "docx") {
      val pdf = getDocxPreviewPdf(documentId, null, false)
      renderCachedPage("docx_original_v5_$documentId", pdf.absolutePath, pageIndex, width)
    } else renderCachedPage("original_$documentId", path, pageIndex, width)
  }

  @ReactMethod
  fun renderDocxPreview(documentId: String, jobId: String?, translated: Boolean, promise: Promise) = runAsync(promise, "DOCX_PREVIEW_FAILED") {
    require(database.documentFormat(documentId) == "docx") { "The selected document is not a Word .docx file." }
    val pdf = getDocxPreviewPdf(documentId, jobId, translated)
    val count = android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
      android.graphics.pdf.PdfRenderer(descriptor).use { it.pageCount }
    }
    JSONObject().put("format", "pdf").put("pageCount", count).toString()
  }

  @ReactMethod
  fun renderTranslatedPage(jobId: String, pageIndex: Int, width: Int, promise: Promise) = runAsync(promise, "TRANSLATED_THUMBNAIL_FAILED") {
    val job = requireJob(jobId)
    require(job.optString("status") in setOf("completed", "partial")) { "Wait until translation has finished before previewing translated pages." }
    if (job.optString("format") == "docx") {
      val pdf = getDocxPreviewPdf(job.getString("documentId"), jobId, true)
      val count = android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        android.graphics.pdf.PdfRenderer(descriptor).use { it.pageCount }
      }
      require(pageIndex in 0 until count) { "The requested preview page is outside the rendered Word document." }
      return@runAsync renderCachedPage("docx_translated_v5_$jobId", pdf.absolutePath, pageIndex, width)
    }
    require(pageIndex in 0 until job.getInt("totalPages")) { "The requested page is outside this document." }
    val page = database.page(jobId, pageIndex) ?: throw IllegalStateException("This page has not been checkpointed yet.")
    require(page.optString("status") in setOf("completed", "partial")) { "This page has not finished translation yet." }
    val source = database.jobDocumentPath(jobId) ?: throw IllegalStateException("The source document is unavailable.")
    existingThumbnail("translated_$jobId", pageIndex, width) ?: run {
      val temporaryPdf = File(translatedPageDirectory, "$jobId-$pageIndex-${UUID.randomUUID()}.pdf")
      try {
        val targetLanguage = job.getString("targetLanguage")
        LanguagePair.requireValid(job.getString("sourceLanguage"), targetLanguage)
        val result = JSONObject(NativeCore.exportPage(source, pageIndex, page.getJSONArray("blocks").toString(), temporaryPdf.absolutePath, fontPath(), cjkFontPath(targetLanguage)))
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
        val (file, id, name) = if (job.optString("format") == "docx") {
          val pdf = getDocxPreviewPdf(job.getString("documentId"), jobId, true)
          val exportId = "preview-pdf-$jobId"
          val name = "${File(job.optString("documentName", "document.docx")).nameWithoutExtension}-translated.pdf"
          database.saveExport(exportId, pdf.absolutePath, name, "application/pdf")
          Triple(pdf, exportId, name)
        } else buildTranslatedPdf(jobId)
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
  fun exportJob(jobId: String, promise: Promise) = exportDocument(jobId, "pdf", promise)

  @ReactMethod
  fun exportDocument(jobId: String, format: String, promise: Promise) {
    if (format !in setOf("pdf", "docx")) return promise.reject("EXPORT_FORMAT_UNSUPPORTED", "Choose PDF or Word .docx as the export format.")
    if (pendingExport != null) return promise.reject("EXPORT_BUSY", "Another export destination is already open.")
    val job = database.jobJson(jobId) ?: return promise.reject("JOB_NOT_FOUND", "Translation job was not found.")
    if (job.optString("status") !in setOf("completed", "partial")) return promise.reject("JOB_NOT_READY", "Wait until translation has finished before exporting.")
    if (format == "docx" && job.optString("format") != "docx") return promise.reject("EXPORT_FORMAT_UNSUPPORTED", "Word export is available for translated .docx documents.")
    pendingExport = promise
    io.execute {
      try {
        val artifact = when {
          format == "docx" -> {
            val file = buildTranslatedDocx(jobId)
            val record = database.exportInfo(jobId)
            val name = record?.name ?: "translated.docx"
            ExportArtifact(file, jobId, name, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
          }
          job.optString("format") == "docx" -> {
            val file = getDocxPreviewPdf(job.getString("documentId"), jobId, true)
            val id = "pdf-export-$jobId"
            val name = "${File(job.optString("documentName", "document.docx")).nameWithoutExtension}-translated.pdf"
            database.saveExport(id, file.absolutePath, name, "application/pdf")
            ExportArtifact(file, id, name, "application/pdf")
          }
          else -> buildTranslatedPdf(jobId).let { ExportArtifact(it.first, it.second, it.third, "application/pdf") }
        }
        pendingExportId = artifact.id
        pendingExportMime = artifact.mimeType
        context.runOnUiQueueThread export@{
          val activity = context.currentActivity
          if (activity == null || activity.isFinishing) {
            pendingExport?.reject("EXPORT_UNAVAILABLE", "Open the app to choose where to save the translated PDF.")
            pendingExport = null
            return@export
          }
          pendingExportFile = artifact.file
          activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = artifact.mimeType
            putExtra(Intent.EXTRA_TITLE, artifact.name)
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
        val saved = database.exportInfo(exportId) ?: throw IllegalArgumentException("The exported document is no longer available.")
        val file = File(saved.path)
        require(file.isFile) { "The exported document is no longer available." }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
          type = saved.mimeType; putExtra(Intent.EXTRA_STREAM, uri)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.runOnUiQueueThread share@{
          try {
            val activity = context.currentActivity ?: throw IllegalStateException("Open the app before sharing the translated document.")
            activity.startActivity(Intent.createChooser(intent, "Share translated document"))
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
        val file = if (database.documentFormat(documentId) == "docx") getDocxPreviewPdf(documentId, null, false) else File(path)
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
    // Validate these new preferences before applying any other requested settings,
    // so malformed ratings/sort values cannot leave a partially changed profile.
    val appRating = if (settings.has("appRating")) {
      val raw = settings.get("appRating")
      val number = raw as? Number
      require(number != null && number.toDouble().isFinite() && number.toDouble() == number.toInt().toDouble() && number.toInt() in 0..5) {
        "App rating must be an integer from 0 through 5."
      }
      number.toInt()
    } else null
    val librarySort = if (settings.has("librarySort")) settings.getString("librarySort").also {
      require(it == "recent" || it == "color") { "Library sort selection is invalid." }
    } else null
    if (settings.has("notificationExplanationShown")) database.setSetting("notificationExplanationShown", settings.getBoolean("notificationExplanationShown").toString())
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
    if (settings.has("selectedModelId")) {
      val model = ModelCatalog.get(settings.getString("selectedModelId"))
      require(model.translationEnabled) { "This model is unavailable in the current runtime." }
      database.setSetting("selectedModelId", model.id)
      database.setSelectedEngine("local", model.id)
    }
    if (settings.has("selectedEngine")) {
      val engine = settings.getJSONObject("selectedEngine")
      database.setSelectedEngine(engine.getString("kind"), engine.getString("id"))
    }
    val currentPair = database.selectedLanguagePair()
    val sourceLanguage = settings.optString("sourceLanguage", currentPair.first)
    val targetLanguage = settings.optString("targetLanguage", currentPair.second)
    LanguagePair.requireValid(sourceLanguage, targetLanguage)
    database.setSetting("sourceLanguage", sourceLanguage)
    database.setSetting("targetLanguage", targetLanguage)
    val db = database.writableDatabase
    db.beginTransaction()
    try {
      appRating?.let { database.setSetting("appRating", it.toString()) }
      librarySort?.let { database.setSetting("librarySort", it) }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    JSONObject().put("theme", database.setting("theme", "system")).put("language", database.setting("language", "system"))
      .put("sourceLanguage", sourceLanguage).put("targetLanguage", targetLanguage)
      .put("selectedModelId", database.setting("selectedModelId", ModelCatalog.RECOMMENDED_ID))
      .put("selectedEngine", database.selectedEngine(ModelCatalog.RECOMMENDED_ID))
      .put("appRating", database.appRating()).put("librarySort", database.librarySort())
      .put("notificationExplanationShown", database.setting("notificationExplanationShown", "false").toBoolean()).toString()
  }

  @ReactMethod
  fun saveGlossaryEntry(entryJson: String, promise: Promise) = runAsync(promise, "GLOSSARY_SAVE_FAILED") {
    val entry = JSONObject(entryJson)
    val pair = LanguagePair.fromJson(entry)
    database.saveGlossary(entry.getString("id"), entry.getString("source"), entry.getString("target"), pair.first, pair.second).toString()
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
      val mimeType = pendingExportMime.also { pendingExportMime = "application/pdf" }
      if (resultCode != Activity.RESULT_OK || intent?.data == null) return promise.reject("EXPORT_CANCELED", "Document export was canceled.")
      io.execute {
        try {
          val target = intent.data!!
          val source = file ?: error("The exported PDF is unavailable.")
          copyToUri(context.contentResolver, source, target)
          val id = exportId ?: error("Export metadata is unavailable.")
          val export = database.exportInfo(id)
          val name = export?.name ?: "translated.pdf"
          val result = JSONObject().put("id", id).put("uri", target.toString()).put("displayName", name)
            .put("format", if (mimeType.contains("wordprocessingml")) "docx" else "pdf").put("mimeType", mimeType)
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
    val temp = File(targetDirectory, "$id.incoming.docx")
    val displayName = queryDisplayName(uri)
    val lowerName = displayName.lowercase()
    if (lowerName.endsWith(".doc") || lowerName.endsWith(".docm")) throw IllegalArgumentException("Only modern Word .docx files are supported. Save this file as .docx and try again.")
    val targetPdf = File(targetDirectory, "$id.pdf")
    val targetDocx = File(targetDirectory, "$id.docx")
    try {
      val input = context.contentResolver.openInputStream(uri) ?: throw IOException("The selected file could not be read.")
      input.use { source -> FileOutputStream(temp).use { output ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
          val count = source.read(buffer)
          if (count < 0) break
          total += count
          if (total > MAX_IMPORTED_DOCUMENT_BYTES) throw IllegalArgumentException("The selected document exceeds the 128 MiB import limit.")
          output.write(buffer, 0, count)
        }
        output.fd.sync()
      } }
    } catch (error: Exception) { temp.delete(); throw error }
    val header = ByteArray(8)
    val headerLength = FileInputStream(temp).use { it.read(header) }
    val format = when {
      headerLength >= 5 && String(header, 0, 5, Charsets.US_ASCII) == "%PDF-" -> "pdf"
      headerLength >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() && header[2] == 3.toByte() && header[3] == 4.toByte() -> "docx"
      else -> { temp.delete(); throw IllegalArgumentException("This file is not a supported PDF or Word .docx document.") }
    }
    val inspection = JSONObject(if (format == "pdf") NativeCore.inspect(temp.absolutePath) else NativeCore.inspectDocx(temp.absolutePath))
    if (inspection.has("error")) { temp.delete(); throw IllegalArgumentException(inspection.optString("error")) }
    val pageCount = inspection.optInt("pageCount", 0)
    if (pageCount <= 0 || pageCount > 10_000) { temp.delete(); throw IllegalArgumentException(if (format == "pdf") "The PDF has an unsupported page count." else "The Word document has no supported text segments or exceeds the supported document limit.") }
    val target = if (format == "pdf") targetPdf else targetDocx
    if (!temp.renameTo(target)) { temp.delete(); throw IOException("The document could not be stored privately.") }
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(target).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    val sourceSha256 = digest.digest().joinToString("") { "%02x".format(it) }
    val cleanName = if (format == "pdf") safePdfName(displayName) else safeDocxName(displayName)
    return database.upsertDocument(id, cleanName, target.absolutePath, pageCount, sourceSha256, format,
      inspection.optInt("segmentCount", 0), inspection.optJSONArray("warnings") ?: JSONArray())
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

  private fun getDocxPreviewPdf(documentId: String, jobId: String?, translated: Boolean): File {
    val source = if (translated) {
      require(!jobId.isNullOrBlank()) { "Choose a completed Word translation first." }
      val job = requireJob(jobId)
      require(job.optString("documentId") == documentId && job.optString("status") in setOf("completed", "partial")) {
        "Wait until this Word translation is complete before previewing it."
      }
      buildTranslatedDocx(jobId)
    } else File(database.documentPath(documentId) ?: error("The Word document is unavailable."))
    val cacheKey = if (translated) "translated-$jobId" else "original-$documentId"
    val target = File(context.cacheDir, "docx-preview-pdf/renderer-v5-$cacheKey.pdf")
    synchronized(target.absolutePath.intern()) {
      if (target.isFile && target.length() > 0L) {
        val valid = runCatching {
          android.os.ParcelFileDescriptor.open(target, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            android.graphics.pdf.PdfRenderer(descriptor).use { it.pageCount > 0 }
          }
        }.getOrDefault(false)
        if (valid) return target
        target.delete()
      }
      val activity = context.currentActivity ?: throw IllegalStateException("Open Folivela before preparing the Word preview.")
      DocxPreviewRenderer(context, activity).render(source, target)
    }
    return target
  }

  private fun buildTranslatedDocx(jobId: String): File {
    val job = requireJob(jobId)
    require(job.optString("format") == "docx") { "This translation is not a Word document." }
    require(job.optString("status") in setOf("completed", "partial")) { "Wait until Word translation has finished before exporting." }
    val output = File(context.filesDir, "translated-docx/$jobId.docx").apply { parentFile?.mkdirs() }
    if (!output.isFile || output.length() == 0L) {
      val source = database.jobDocumentPath(jobId) ?: error("The original Word document is unavailable.")
      val allBlocks = JSONArray()
      for (pageIndex in 0 until job.getInt("totalPages")) {
        val blocks = database.pageBlockArray(jobId, pageIndex)
        for (index in 0 until blocks.length()) allBlocks.put(blocks.getJSONObject(index))
      }
      val result = JSONObject(NativeCore.exportDocx(source, allBlocks.toString(), output.absolutePath))
      if (result.has("error") || !output.isFile || output.length() == 0L) throw IOException(result.optString("error", "The translated Word document could not be prepared."))
      database.saveExport(jobId, output.absolutePath, "${File(job.optString("documentName", "document.docx")).nameWithoutExtension}-translated.docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    }
    return output
  }

  private fun queryDisplayName(uri: Uri): String {
    var name: String? = null
    val cursor: Cursor? = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use { if (it.moveToFirst()) name = it.getString(0) }
    return name?.takeIf(String::isNotBlank) ?: "document.pdf"
  }

  private fun safeDocxName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
    .replace(Regex("[^A-Za-z0-9 ._()-]"), "_").trim().take(120).ifBlank { "document.docx" }

  private fun copyToUri(resolver: ContentResolver, source: File, target: Uri) {
    resolver.openOutputStream(target, "wt")?.use { output -> FileInputStream(source).use { input -> input.copyTo(output) } }
      ?: throw IOException("The chosen destination could not be opened.")
  }

  private fun fontPath(): String {
    val target = File(context.cacheDir, "NotoSans-Regular.ttf")
    return FontAsset.ensure(context, target).absolutePath
  }

  private fun cjkFontPath(targetLanguage: String): String {
    if (targetLanguage != "zh-Hans") return ""
    val target = File(context.cacheDir, "NotoSansSC-Regular.ttf")
    return FontAsset.ensureCjk(context, target).absolutePath
  }

  private fun hasStorageFor(bytes: Long): Boolean {
    return availableStorageBytes(context.filesDir) >= MemoryPolicy.requiredDiskBytes(bytes)
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
    private const val MAX_IMPORTED_DOCUMENT_BYTES = 128L * 1024L * 1024L
    private const val REQUEST_POST_NOTIFICATIONS = 0xD339
    private const val SETTING_NOTIFICATION_REQUESTED = "notification_permission_requested"
  }
}

private data class ExportArtifact(val file: File, val id: String, val name: String, val mimeType: String)

/**
 * Derives a file-aware read-only model snapshot. The full digest was verified
 * when the download was installed; boot checks file identity/length, while
 * model loading still verifies the digest before use. Keeping this snapshot
 * read-only avoids replacing a newer downloading/deleted/verified database
 * row after the initial database read.
 */
internal fun effectiveModelSnapshot(
  rawModels: JSONArray,
  models: List<ModelDefinition>,
  downloader: ModelDownloader,
): JSONArray = JSONArray().also { effective ->
  val definitions = models.associateBy(ModelDefinition::id)
  for (index in 0 until rawModels.length()) {
    val row = rawModels.getJSONObject(index)
    val model = definitions[row.getString("id")] ?: ModelCatalog.get(row.getString("id"))
    val installedFile = downloader.fileFor(model.id)
    val partialFile = installedFile.resolveSibling("${model.id}.part")
    val installedBytes = installedFile.length()
    val partialBytes = if (partialFile.isFile) partialFile.length() else 0L
    val installedIsValidLength = installedFile.isFile && installedBytes == model.bytes
    if (row.optString("status") == "ready" && !installedIsValidLength) {
      row.put("status", if (partialBytes > 0L) "paused" else "not_downloaded")
        .put("downloadedBytes", partialBytes)
        .remove("error")
    } else if (row.optString("status") in setOf("downloading", "verifying") &&
      !downloader.hasActiveTransfer(model.id)) {
      // A process restart loses the in-memory transfer. Present the durable
      // partial file as resumable; never write this derived status to SQLite.
      row.put("status", if (partialBytes > 0L) "paused" else "not_downloaded")
        .put("downloadedBytes", partialBytes)
        .remove("error")
    }
    row.put("storedBytes", installedBytes + partialBytes)
      .put("partialBytes", partialBytes)
    effective.put(row)
  }
}

internal class PdfTranslatorPackage : ReactPackage {
  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> = listOf(PdfTranslatorModule(reactContext))
  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}

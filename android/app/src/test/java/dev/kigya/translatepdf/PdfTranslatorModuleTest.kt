package dev.kigya.translatepdf

import android.app.Application
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.database.Cursor
import android.database.MatrixCursor
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.StatFs
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import org.json.JSONArray
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class PdfTranslatorModuleTest {
  private lateinit var context: Context
  private lateinit var reactContext: ReactApplicationContext
  private lateinit var module: PdfTranslatorModule
  private lateinit var database: TranslatorDatabase

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    context.deleteDatabase("translator-private.db")
    reactContext = StubReactApplicationContext(context)
    module = PdfTranslatorModule(reactContext)
    database = moduleDatabase()
  }

  @After
  fun tearDown() {
    ShadowNativePdfCore.inspectionResponse = null
    module.invalidate()
    database.close()
    ModelCatalog.models.forEach { model ->
      File(context.filesDir, "models/${model.id}.gguf").delete()
      File(context.filesDir, "models/${model.id}.part").delete()
    }
    context.deleteDatabase("translator-private.db")
  }

  @Test
  fun bootstrapReturnsEffectiveFileAwareRowsWithoutChangingTheCapturedDatabaseState() {
    val model = ModelCatalog.models.first()
    val partial = File(context.filesDir, "models/${model.id}.part").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2, 3, 4, 5))
    }
    database.modelStatus(model.id, "ready", model.bytes, "old stale error")
    database.setSetting("selectedModelId", model.id)
    database.setSetting("appRating", "5")
    database.setSetting("librarySort", "color")
    val result = PromiseCapture()

    module.bootstrap(result.promise)

    val output = result.awaitSuccess().toString()
    val snapshot = JSONObject(output)
    val models = snapshot.getJSONArray("models")
    val modelRow = (0 until models.length()).map(models::getJSONObject).first { it.getString("id") == model.id }
    assertEquals("paused", modelRow.getString("status"))
    assertEquals(5L, modelRow.getLong("downloadedBytes"))
    assertEquals(5L, modelRow.getLong("partialBytes"))
    assertEquals(5L, modelRow.getLong("storedBytes"))
    assertFalse(modelRow.has("error"))
    assertEquals(model.id, snapshot.getJSONObject("preferences").getString("selectedModelId"))
    assertEquals(5, snapshot.getJSONObject("preferences").getInt("appRating"))
    assertEquals("color", snapshot.getJSONObject("preferences").getString("librarySort"))
    assertEquals(5L, snapshot.getJSONObject("device").getJSONObject("storage").getLong("partialDownloadBytes"))
    assertEquals(Triple("ready", model.bytes, "old stale error"), database.modelState(model.id))
    assertTrue(partial.isFile)
  }

  @Test
  fun selectedOnlineEnginePersistsWhileTheLastLocalModelRemainsAvailable() {
    val localId = ModelCatalog.models[1].id
    database.setSetting("selectedModelId", localId)
    val online = PromiseCapture()

    module.setSettings("""{"selectedEngine":{"kind":"online","id":"mymemory"}}""", online.promise)

    val onlinePreferences = JSONObject(online.awaitSuccess().toString())
    assertEquals("online", onlinePreferences.getJSONObject("selectedEngine").getString("kind"))
    assertEquals("mymemory", onlinePreferences.getJSONObject("selectedEngine").getString("id"))
    assertEquals(localId, onlinePreferences.getString("selectedModelId"))

    val local = PromiseCapture()
    module.setSettings("""{"selectedEngine":{"kind":"local","id":"${ModelCatalog.models.first().id}"}}""", local.promise)
    val localPreferences = JSONObject(local.awaitSuccess().toString())
    assertEquals("local", localPreferences.getJSONObject("selectedEngine").getString("kind"))
    assertEquals(ModelCatalog.models.first().id, localPreferences.getString("selectedModelId"))
  }

  @Test
  fun bootstrapReportsNotificationPermissionWithoutRequestingIt() {
    Shadows.shadowOf(context.applicationContext as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    database.setSetting("notification_permission_requested", "false")
    val result = PromiseCapture()

    module.bootstrap(result.promise)

    val notifications = JSONObject(result.awaitSuccess().toString()).getJSONObject("notifications")
    assertEquals("not_determined", notifications.getString("status"))
    assertTrue(notifications.getBoolean("canRequest"))
    assertFalse(notifications.getBoolean("requestedBefore"))
    assertEquals("false", database.setting("notification_permission_requested", "false"))
  }

  @Test
  fun reactPackageRegistersTheTranslatorModuleAndNoUnusedViewManagers() {
    val packageModules = PdfTranslatorPackage().createNativeModules(reactContext)
    val registeredModule = packageModules.single() as PdfTranslatorModule
    assertEquals("PdfTranslator", registeredModule.name)
    assertTrue(PdfTranslatorPackage().createViewManagers(reactContext).isEmpty())
    registeredModule.invalidate()
    (registeredModule.javaClass.getDeclaredField("database").apply { isAccessible = true }
      .get(registeredModule) as TranslatorDatabase).close()
  }

  @Test
  fun activityDependentApisRejectClearlyWhenNoActivityIsAttached() {
    val pick = PromiseCapture()
    module.pickPdf(pick.promise)
    assertEquals("PICKER_UNAVAILABLE", pick.awaitFailure().code)

    val start = PromiseCapture()
    module.startJob("{}", start.promise)
    assertEquals("JOB_START_FAILED", start.awaitFailure().code)
    assertTrue(start.awaitFailure().message.orEmpty().contains("open app"))

    val download = PromiseCapture()
    module.startModelDownload(ModelCatalog.models.first().id, download.promise)
    assertEquals("MODEL_DOWNLOAD_START_FAILED", download.awaitFailure().code)
    assertTrue(download.awaitFailure().message.orEmpty().contains("Open the app"))
  }

  @Test
  fun modelDownloadPreflightRejectsInsufficientStorageBeforeReservingOrStartingService() {
    replaceModule(availableStorageBytes = { 0L })
    val resumedActivity = Activity()
    reactContext.onHostResume(resumedActivity)
    assertSame(resumedActivity, reactContext.currentActivity)
    val model = ModelCatalog.models.first()
    val result = PromiseCapture()

    module.startModelDownload(model.id, result.promise)

    val failure = result.awaitFailure()
    assertEquals("MODEL_DOWNLOAD_START_FAILED", failure.code)
    assertTrue(failure.message.orEmpty().contains("Not enough free storage"))
    assertNull(database.modelState(model.id))
    assertNull(Shadows.shadowOf(context.applicationContext as Application).nextStartedService)
  }

  @Test
  fun failedBackgroundDownloadStartKeepsRecoverablePartialBytesAndReleasesReservation() {
    var startWasAttempted = false
    replaceModule(availableStorageBytes = { Long.MAX_VALUE }, startModelService = {
      startWasAttempted = true
      throw IllegalStateException("background start rejected")
    })
    val activity = Activity()
    reactContext.onHostResume(activity)
    val model = ModelCatalog.models.first()
    val partial = File(context.filesDir, "models/${model.id}.part").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(8, 7, 6, 5))
    }
    val result = PromiseCapture()

    module.startModelDownload(model.id, result.promise)

    val failure = result.awaitFailure()
    assertEquals("MODEL_DOWNLOAD_START_FAILED", failure.code)
    assertTrue(
      "model service start was not attempted; failure=${failure.message}, activity=${reactContext.currentActivity}, active=${PdfTranslationService.isActive}",
      startWasAttempted,
    )
    assertEquals(Triple("paused", 4L, null), database.modelState(model.id))
    assertTrue(partial.isFile)
    val downloader = module.javaClass.getDeclaredField("downloader").apply { isAccessible = true }.get(module) as ModelDownloader
    val nextToken = downloader.reserve(model.id)
    downloader.releaseReservation(model.id, nextToken)
  }

  @Test
  fun verifiedFontPathsUseBundledCyrillicFontAndOnlyInstallCjkForSimplifiedChinese() {
    val fontPath = module.javaClass.getDeclaredMethod("fontPath").apply { isAccessible = true }
      .invoke(module) as String
    val cjkPath = module.javaClass.getDeclaredMethod("cjkFontPath", String::class.java).apply { isAccessible = true }
    val regularFont = File(fontPath)

    assertTrue(regularFont.isFile)
    assertTrue(regularFont.length() > 0)
    assertEquals("", cjkPath.invoke(module, "en"))

    val simplifiedChineseFont = File(cjkPath.invoke(module, "zh-Hans") as String)
    assertTrue(simplifiedChineseFont.isFile)
    assertTrue(simplifiedChineseFont.length() > 0)
  }

  @Test
  fun pickerRejectsFinishingActivityAndDuplicatePickerWithoutLosingPendingRequest() {
    val finishingActivity = Activity()
    reactContext.onHostResume(finishingActivity)
    finishingActivity.finish()
    val finishing = PromiseCapture()
    module.pickPdf(finishing.promise)
    assertEquals("PICKER_UNAVAILABLE", finishing.awaitFailure().code)
    assertNull(module.javaClass.getDeclaredField("pendingImport").apply { isAccessible = true }.get(module))

    val existing = PromiseCapture()
    module.javaClass.getDeclaredField("pendingImport").apply { isAccessible = true }.set(module, existing.promise)
    val duplicate = PromiseCapture()
    module.pickPdf(duplicate.promise)
    assertEquals("PICKER_BUSY", duplicate.awaitFailure().code)
    module.onActivityResult(Activity(), 6101, Activity.RESULT_CANCELED, null)
    assertEquals("null", existing.awaitSuccess())
  }

  @Test
  fun unrelatedNewIntentDoesNotCompleteOrReplaceThePendingPdfPicker() {
    val pending = PromiseCapture()
    module.javaClass.getDeclaredField("pendingImport").apply { isAccessible = true }.set(module, pending.promise)

    module.onNewIntent(Intent(Intent.ACTION_VIEW))

    assertSame(pending.promise, module.javaClass.getDeclaredField("pendingImport").apply { isAccessible = true }.get(module))
    module.onActivityResult(Activity(), 6101, Activity.RESULT_CANCELED, null)
    assertEquals("null", pending.awaitSuccess())
  }

  @Test
  fun pauseModelDownloadPersistsPartialBytesAndNotifiesTheService() {
    val model = ModelCatalog.models.first()
    val partial = File(context.filesDir, "models/${model.id}.part").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(4, 5, 6))
    }
    database.modelStatus(model.id, "downloading", 91)

    val result = PromiseCapture()
    module.pauseModelDownload(model.id, result.promise)

    val snapshot = JSONObject(result.awaitSuccess().toString())
    assertEquals("paused", snapshot.getString("status"))
    assertEquals(3L, snapshot.getLong("downloadedBytes"))
    assertEquals(Triple("paused", 3L, null), database.modelState(model.id))
    assertTrue(partial.isFile)
    val serviceIntent = Shadows.shadowOf(context.applicationContext as Application).nextStartedService
    assertEquals(PdfTranslationService.ACTION_PAUSE_MODEL, serviceIntent.action)
  }

  @Test
  fun startJobRejectsInvalidDocumentLanguageModelGlossaryAndMissingWeights() {
    val resumedActivity = Activity()
    reactContext.onHostResume(resumedActivity)
    val source = File(context.cacheDir, "source-for-job.pdf").apply { writeBytes("%PDF-1.7".toByteArray()) }
    database.upsertDocument("doc-1", "source.pdf", source.absolutePath, 1, "source-hash")

    fun start(input: JSONObject): PromiseCapture {
      val result = PromiseCapture()
      module.startJob(input.toString(), result.promise)
      return result
    }

    val absent = start(jobInput("missing-document", ModelCatalog.RECOMMENDED_ID))
    assertEquals("JOB_START_FAILED", absent.awaitFailure().code)

    val invalidPair = start(jobInput("doc-1", ModelCatalog.RECOMMENDED_ID).put("sourceLanguage", "xx"))
    assertEquals("JOB_START_FAILED", invalidPair.awaitFailure().code)

    val unknown = start(jobInput("doc-1", "unknown-model"))
    assertEquals("JOB_START_FAILED", unknown.awaitFailure().code)

    val disabled = start(jobInput("doc-1", "hy-mt2-iq3-xs"))
    assertEquals("JOB_START_FAILED", disabled.awaitFailure().code)

    database.saveGlossary("term", "SDK", "SDK", "zh-Hans", "en")
    val unsupportedGlossary = start(jobInput("doc-1", "translategemma-4b-q4"))
    assertEquals("JOB_START_FAILED", unsupportedGlossary.awaitFailure().code)

    val missingWeights = start(jobInput("doc-1", ModelCatalog.RECOMMENDED_ID))
    assertEquals("JOB_START_FAILED", missingWeights.awaitFailure().code)
    assertFalse(database.hasActiveJob())
    source.delete()
  }

  @Test
  fun missingJobAndDocumentQueriesReturnTheirStableNativeErrors() {
    val get = PromiseCapture()
    module.getJob("missing", 0, get.promise)
    assertEquals("JOB_READ_FAILED", get.awaitFailure().code)

    val pause = PromiseCapture()
    module.pauseJob("missing", pause.promise)
    assertEquals("JOB_PAUSE_FAILED", pause.awaitFailure().code)

    val resume = PromiseCapture()
    module.resumeJob("missing", resume.promise)
    assertEquals("JOB_RESUME_FAILED", resume.awaitFailure().code)

    val cancel = PromiseCapture()
    module.cancelJob("missing", cancel.promise)
    assertEquals("JOB_CANCEL_FAILED", cancel.awaitFailure().code)

    val delete = PromiseCapture()
    module.deleteJob("missing", delete.promise)
    assertEquals("JOB_DELETE_FAILED", delete.awaitFailure().code)

    val render = PromiseCapture()
    module.renderPage("missing", 0, 320, render.promise)
    assertEquals("THUMBNAIL_FAILED", render.awaitFailure().code)

    val translated = PromiseCapture()
    module.renderTranslatedPage("missing", 0, 320, translated.promise)
    assertEquals("TRANSLATED_THUMBNAIL_FAILED", translated.awaitFailure().code)

    val preview = PromiseCapture()
    module.previewJob("missing", preview.promise)
    assertEquals("JOB_NOT_FOUND", preview.awaitFailure().code)

    val export = PromiseCapture()
    module.exportJob("missing", export.promise)
    assertEquals("JOB_NOT_FOUND", export.awaitFailure().code)

    val open = PromiseCapture()
    module.openPdf("missing", open.promise)
    assertEquals("OPEN_PDF_FAILED", open.awaitFailure().code)

    val share = PromiseCapture()
    module.shareExport("missing", share.promise)
    assertEquals("SHARE_FAILED", share.awaitFailure().code)
  }

  @Test
  fun openAndShareRejectWithoutAnActivityAfterResolvingStoredPdfFiles() {
    val source = File(context.filesDir, "documents/openable-source.pdf").apply {
      parentFile?.mkdirs()
      writeBytes("%PDF-1.7".toByteArray())
    }
    database.upsertDocument("openable-doc", "source.pdf", source.absolutePath, 1, "source-hash")
    val open = PromiseCapture()
    module.openPdf("openable-doc", open.promise)
    assertEquals("OPEN_PDF_FAILED", open.awaitFailure().code)
    assertTrue(open.awaitFailure().message.orEmpty().contains("Open the app"))

    val exported = File(context.filesDir, "exports/shareable.pdf").apply {
      parentFile?.mkdirs()
      writeBytes("%PDF-1.7".toByteArray())
    }
    database.saveExport("saved-export", exported.absolutePath, "translated.pdf")
    val share = PromiseCapture()
    module.shareExport("saved-export", share.promise)
    assertEquals("SHARE_FAILED", share.awaitFailure().code)
    assertTrue(share.awaitFailure().message.orEmpty().contains("Open the app"))
    source.delete()
    exported.delete()
  }

  @Test
  fun previewAndExportRejectCompletedJobsWithoutPageCheckpointsAndClearPendingState() {
    val source = File(context.cacheDir, "checkpoint-failure-source.pdf").apply { writeBytes("%PDF-1.7".toByteArray()) }
    database.upsertDocument("checkpoint-doc", "source.pdf", source.absolutePath, 1, "source-hash")
    database.addJob("preview-missing-checkpoint", jobInput("checkpoint-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("preview-missing-checkpoint", status = "completed")
    val preview = PromiseCapture()
    module.previewJob("preview-missing-checkpoint", preview.promise)
    assertEquals("PREVIEW_FAILED", preview.awaitFailure().code)
    assertEquals("failed", database.jobJson("preview-missing-checkpoint")?.getString("status"))
    assertTrue(database.jobJson("preview-missing-checkpoint")?.getString("error").orEmpty().contains("no saved translation checkpoint"))

    database.addJob("export-missing-checkpoint", jobInput("checkpoint-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("export-missing-checkpoint", status = "partial")
    val export = PromiseCapture()
    module.exportJob("export-missing-checkpoint", export.promise)
    assertEquals("EXPORT_FAILED", export.awaitFailure().code)
    assertEquals("failed", database.jobJson("export-missing-checkpoint")?.getString("status"))
    assertNull(module.javaClass.getDeclaredField("pendingExport").apply { isAccessible = true }.get(module))
    assertNull(module.javaClass.getDeclaredField("pendingExportFile").apply { isAccessible = true }.get(module))
    assertNull(module.javaClass.getDeclaredField("pendingExportId").apply { isAccessible = true }.get(module))
    source.delete()
  }

  @Test
  fun existingJobReadAndDeletionRespectItsPersistedState() {
    val source = File(context.cacheDir, "module-job-source.pdf").apply { writeBytes("%PDF-1.7".toByteArray()) }
    database.upsertDocument("module-doc", "source.pdf", source.absolutePath, 1, "source-hash")
    database.addJob("module-job", jobInput("module-doc", ModelCatalog.RECOMMENDED_ID), 1)

    val activeDelete = PromiseCapture()
    module.deleteJob("module-job", activeDelete.promise)
    assertEquals("JOB_DELETE_FAILED", activeDelete.awaitFailure().code)

    database.updateJob("module-job", status = "paused")
    val read = PromiseCapture()
    module.getJob("module-job", 8, read.promise)
    val snapshot = JSONObject(read.awaitSuccess().toString())
    assertEquals("module-job", snapshot.getJSONObject("job").getString("id"))
    assertEquals(0, snapshot.getJSONArray("pages").length())
    assertTrue(snapshot.get("selectedPage") === JSONObject.NULL)

    val preview = PromiseCapture()
    module.previewJob("module-job", preview.promise)
    assertEquals("JOB_NOT_READY", preview.awaitFailure().code)

    val export = PromiseCapture()
    module.exportJob("module-job", export.promise)
    assertEquals("JOB_NOT_READY", export.awaitFailure().code)

    val delete = PromiseCapture()
    module.deleteJob("module-job", delete.promise)
    assertNull(delete.awaitSuccess())
    assertNull(database.jobJson("module-job"))
    source.delete()
  }

  @Test
  fun pauseAndCancelJobPersistStateAndDispatchMatchingServiceCommands() {
    val source = File(context.cacheDir, "job-lifecycle-source.pdf").apply { writeBytes("%PDF-1.7".toByteArray()) }
    database.upsertDocument("lifecycle-doc", "source.pdf", source.absolutePath, 1, "source-hash")
    database.addJob("lifecycle-job", jobInput("lifecycle-doc", ModelCatalog.RECOMMENDED_ID), 1)

    val pause = PromiseCapture()
    module.pauseJob("lifecycle-job", pause.promise)
    assertEquals("paused", JSONObject(pause.awaitSuccess().toString()).getString("status"))
    assertEquals("paused", database.jobJson("lifecycle-job")?.getString("status"))
    val pauseIntent = Shadows.shadowOf(context.applicationContext as Application).nextStartedService
    assertEquals(PdfTranslationService.ACTION_PAUSE, pauseIntent.action)
    assertEquals("lifecycle-job", pauseIntent.getStringExtra(PdfTranslationService.EXTRA_JOB_ID))

    val cancel = PromiseCapture()
    module.cancelJob("lifecycle-job", cancel.promise)
    val canceled = JSONObject(cancel.awaitSuccess().toString())
    assertEquals("canceled", canceled.getString("status"))
    assertEquals("Canceled by the user.", canceled.getString("error"))
    assertEquals("canceled", database.jobJson("lifecycle-job")?.getString("status"))
    val cancelIntent = Shadows.shadowOf(context.applicationContext as Application).nextStartedService
    assertEquals(PdfTranslationService.ACTION_CANCEL, cancelIntent.action)
    assertEquals("lifecycle-job", cancelIntent.getStringExtra(PdfTranslationService.EXTRA_JOB_ID))
    source.delete()
  }

  @Test
  fun resumePausedJobQueuesItAndStartsForegroundServiceFromAnOpenActivity() {
    val source = File(context.cacheDir, "resume-job-source.pdf").apply { writeBytes("%PDF-1.7".toByteArray()) }
    database.upsertDocument("resume-doc", "source.pdf", source.absolutePath, 1, "source-hash")
    database.addJob("resume-job", jobInput("resume-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("resume-job", status = "paused", error = "interrupted")
    val resumedActivity = Activity()
    reactContext.onHostResume(resumedActivity)
    assertSame(resumedActivity, reactContext.currentActivity)

    val result = PromiseCapture()
    module.resumeJob("resume-job", result.promise)

    val queued = JSONObject(result.awaitSuccess().toString())
    assertEquals("queued", queued.getString("status"))
    assertEquals("queued", queued.getString("stage"))
    assertTrue(queued.isNull("error"))
    assertEquals("queued", database.jobJson("resume-job")?.getString("status"))
    val intent = Shadows.shadowOf(context.applicationContext as Application).nextStartedService
    assertEquals(PdfTranslationService.ACTION_START, intent.action)
    assertEquals("resume-job", intent.getStringExtra(PdfTranslationService.EXTRA_JOB_ID))
    source.delete()
  }

  @Test
  fun resumePausedJobRequiresAnOpenActivityAndLeavesTheJobPaused() {
    val source = File(context.cacheDir, "resume-no-activity-source.pdf").apply { writeBytes("%PDF-1.7".toByteArray()) }
    database.upsertDocument("resume-no-activity-doc", "source.pdf", source.absolutePath, 1, "source-hash")
    database.addJob("resume-no-activity-job", jobInput("resume-no-activity-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("resume-no-activity-job", status = "paused")

    val result = PromiseCapture()
    module.resumeJob("resume-no-activity-job", result.promise)

    assertEquals("JOB_RESUME_FAILED", result.awaitFailure().code)
    assertEquals("paused", database.jobJson("resume-no-activity-job")?.getString("status"))
    assertNull(Shadows.shadowOf(context.applicationContext as Application).nextStartedService)
    source.delete()
  }

  @Test
  fun pageRenderRejectsInvalidDimensionsAndUntranslatedPreviewRequests() {
    val source = File(context.cacheDir, "render-validation-source.pdf").apply { writeBytes("%PDF-1.7".toByteArray()) }
    database.upsertDocument("render-doc", "source.pdf", source.absolutePath, 1, "source-hash")
    val job = database.addJob("render-job", jobInput("render-doc", ModelCatalog.RECOMMENDED_ID), 1)

    val invalidSize = PromiseCapture()
    module.renderPage("render-doc", -1, 32, invalidSize.promise)
    assertEquals("THUMBNAIL_FAILED", invalidSize.awaitFailure().code)

    val notReady = PromiseCapture()
    module.renderTranslatedPage(job.getString("id"), 0, 320, notReady.promise)
    assertEquals("TRANSLATED_THUMBNAIL_FAILED", notReady.awaitFailure().code)

    database.updateJob("render-job", status = "completed")
    val outside = PromiseCapture()
    module.renderTranslatedPage("render-job", 1, 320, outside.promise)
    assertEquals("TRANSLATED_THUMBNAIL_FAILED", outside.awaitFailure().code)

    val missingPage = PromiseCapture()
    module.renderTranslatedPage("render-job", 0, 320, missingPage.promise)
    assertEquals("TRANSLATED_THUMBNAIL_FAILED", missingPage.awaitFailure().code)
    source.delete()
  }

  @Test
  fun pageRenderReusesPngSignatureCheckedOriginalAndTranslatedCaches() {
    val source = File(context.cacheDir, "cached-render-source.pdf").apply { writeBytes("not parsed when cached".toByteArray()) }
    database.upsertDocument("cached-doc", "source.pdf", source.absolutePath, 1, "source-hash")
    database.addJob("cached-job", jobInput("cached-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("cached-job", status = "completed")
    database.savePage(
      "cached-job", 0, "completed",
      JSONArray().put(JSONObject().put("status", "translated")).toString(), expectedBlocks = 1,
    )
    val thumbnails = File(context.cacheDir, "thumbnails").apply { mkdirs() }
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    File(thumbnails, "original_cached-doc_0_320.png").writeBytes(signature)
    File(thumbnails, "translated_cached-job_0_320.png").writeBytes(signature)

    val original = PromiseCapture()
    module.renderPage("cached-doc", 0, 320, original.promise)
    assertTrue(original.awaitSuccess().toString().endsWith("original_cached-doc_0_320.png"))

    val translated = PromiseCapture()
    module.renderTranslatedPage("cached-job", 0, 320, translated.promise)
    assertTrue(translated.awaitSuccess().toString().endsWith("translated_cached-job_0_320.png"))
    source.delete()
  }

  @Test
  @Config(shadows = [ShadowNativePdfCore::class])
  fun translatedPagePreviewBuildsOnePagePdfRendersItAndRemovesTemporaryPdf() {
    val jobId = "module-render-${UUID.randomUUID()}"
    val source = File(context.filesDir, "documents/$jobId.pdf").apply {
      parentFile?.mkdirs()
      writeText("%PDF-1.7 source")
    }
    database.upsertDocument("$jobId-doc", "source.pdf", source.absolutePath, 1, "source-hash")
    val input = jobInput("$jobId-doc", ModelCatalog.RECOMMENDED_ID)
      .put("sourceLanguage", "en").put("targetLanguage", "zh-Hans")
    database.addJob(jobId, input, 1)
    database.updateJob(jobId, status = "completed")
    database.savePage(jobId, 0, "completed", JSONArray().put(
      JSONObject().put("status", "translated").put("source", "hello").put("target", "你好"),
    ).toString(), expectedBlocks = 1)
    val result = PromiseCapture()

    module.renderTranslatedPage(jobId, 0, 320, result.promise)

    val rendered = File(Uri.parse(result.awaitSuccess().toString()).path!!)
    assertTrue(rendered.isFile)
    assertTrue(rendered.readBytes().take(8).toByteArray().contentEquals(byteArrayOf(
      0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )))
    assertTrue(File(context.cacheDir, "NotoSans-Regular.ttf").isFile)
    assertTrue(File(context.cacheDir, "NotoSansSC-Regular.ttf").isFile)
    assertTrue(File(context.cacheDir, "translated-page-work").listFiles().orEmpty().none { it.name.startsWith("$jobId-0-") })
    rendered.delete()
    source.delete()
  }

  @Test
  fun modelDeletionRemovesPartialAndPersistedStatusButNeverDocuments() {
    val model = ModelCatalog.models.first()
    val document = File(context.filesDir, "documents/kept-after-model-removal.pdf").apply {
      parentFile?.mkdirs()
      writeBytes("%PDF-1.7".toByteArray())
    }
    database.upsertDocument("kept-document", "kept-after-model-removal.pdf", document.absolutePath, 1, "source-hash")
    val part = File(context.filesDir, "models/${model.id}.part").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(8, 7, 6))
    }
    database.modelStatus(model.id, "paused", 3)
    val result = PromiseCapture()

    module.deleteModel(model.id, result.promise)

    assertNull(result.awaitSuccess())
    assertFalse(part.exists())
    assertNull(database.modelState(model.id))
    assertTrue(document.isFile)
    assertTrue((0 until database.documentsJson().length()).any {
      database.documentsJson().getJSONObject(it).getString("id") == "kept-document"
    })
    document.delete()
  }

  @Test
  fun deleteModelWaitsForServiceOwnedTransferCancellationBeforeResolving() {
    val model = ModelCatalog.models.first()
    val serving = CountDownLatch(1)
    val progress = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val outcome = AtomicReference<Result<File>>()
    val server = ServerSocket(0)
    val serverThread = Thread {
      try {
        server.accept().use { socket ->
          socket.soTimeout = 10_000
          val request = socket.getInputStream().bufferedReader()
          while (request.readLine()?.isNotEmpty() == true) Unit
          val output = socket.getOutputStream()
          output.write("HTTP/1.1 200 OK\r\nContent-Length: ${model.bytes}\r\nConnection: close\r\n\r\n".toByteArray())
          output.flush()
          serving.countDown()
          val chunk = ByteArray(16 * 1024)
          repeat(512) {
            output.write(chunk)
            output.flush()
            Thread.sleep(2)
          }
        }
      } catch (_: Exception) {
        // Module deletion intentionally disconnects the service-owned socket.
      } finally { server.close() }
    }.apply { isDaemon = true; start() }
    val serviceDownloader = ModelDownloader(context, database)
    serviceDownloader.start(model.id, onProgress = { if (it >= 1_048_576) progress.countDown() }, onFinished = {
      outcome.set(it)
      finished.countDown()
    }, downloadUrl = URL("http://127.0.0.1:${server.localPort}/model"))

    try {
      assertTrue("the local server should accept the service-owned transfer", serving.await(5, TimeUnit.SECONDS))
      assertTrue("the transfer should write bytes before module deletion", progress.await(10, TimeUnit.SECONDS))
      val deleted = PromiseCapture()
      module.deleteModel(model.id, deleted.promise)

      assertNull(deleted.awaitSuccess())
      assertTrue("delete resolves only after the service callback finishes", finished.await(5, TimeUnit.SECONDS))
      assertTrue(outcome.get().isFailure)
      assertFalse(serviceDownloader.fileFor(model.id).exists())
      assertFalse(serviceDownloader.fileFor(model.id).resolveSibling("${model.id}.part").exists())
      assertNull(database.modelState(model.id))
    } finally {
      server.close()
      serverThread.join(1000)
    }
  }

  @Test
  fun modelDeletionIsBlockedWhileAQueuedJobDependsOnIt() {
    val model = ModelCatalog.models.first()
    val source = File(context.cacheDir, "queued-model-job.pdf").apply { writeBytes("%PDF-1.7".toByteArray()) }
    database.upsertDocument("queued-doc", "source.pdf", source.absolutePath, 1, "source-hash")
    database.addJob("queued-job", jobInput("queued-doc", model.id), 1)
    val modelFile = File(context.filesDir, "models/${model.id}.part").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(9, 8, 7))
    }

    val result = PromiseCapture()
    module.deleteModel(model.id, result.promise)

    assertEquals("MODEL_DELETE_FAILED", result.awaitFailure().code)
    assertTrue(modelFile.exists())
    assertEquals("queued", database.jobJson("queued-job")?.getString("status"))
    source.delete()
  }

  @Test
  fun settingsAndOnboardingPersistOnlyValidUserChoices() {
    val model = ModelCatalog.models.first()
    val settings = PromiseCapture()
    module.setSettings(
      JSONObject().put("theme", "dark").put("language", "system")
        .put("sourceLanguage", "zh-Hans").put("targetLanguage", "en")
        .put("selectedModelId", model.id).put("notificationExplanationShown", true).toString(),
      settings.promise,
    )
    val savedSettings = JSONObject(settings.awaitSuccess().toString())
    assertEquals("dark", savedSettings.getString("theme"))
    assertEquals("system", savedSettings.getString("language"))
    assertEquals("zh-Hans", savedSettings.getString("sourceLanguage"))
    assertEquals("en", savedSettings.getString("targetLanguage"))
    assertEquals(model.id, database.setting("selectedModelId", ""))
    assertEquals("true", database.setting("notificationExplanationShown", "false"))

    val explicitLanguage = PromiseCapture()
    module.setSettings("{\"language\":\"ru\"}", explicitLanguage.promise)
    assertEquals("ru", JSONObject(explicitLanguage.awaitSuccess().toString()).getString("language"))

    val invalidSettings = PromiseCapture()
    module.setSettings("{\"theme\":\"sepia\"}", invalidSettings.promise)
    assertEquals("SETTINGS_SAVE_FAILED", invalidSettings.awaitFailure().code)

    val invalidLocale = PromiseCapture()
    module.setSettings("{\"language\":\"en_US\"}", invalidLocale.promise)
    assertEquals("SETTINGS_SAVE_FAILED", invalidLocale.awaitFailure().code)

    val unavailableModel = PromiseCapture()
    module.setSettings("{\"selectedModelId\":\"hy-mt2-iq3-xs\"}", unavailableModel.promise)
    assertEquals("SETTINGS_SAVE_FAILED", unavailableModel.awaitFailure().code)

    val onboarding = PromiseCapture()
    module.setOnboarding("{\"version\":2,\"page\":3,\"done\":true}", onboarding.promise)
    assertEquals("{\"version\":2,\"page\":3,\"done\":true}", onboarding.awaitSuccess())
    assertEquals("2", database.setting("onboarding_version", ""))
    assertEquals("3", database.setting("onboarding_page", ""))
    assertEquals("true", database.setting("onboarding_done", "false"))

    val invalidOnboarding = PromiseCapture()
    module.setOnboarding("{\"version\":2,\"page\":4,\"done\":false}", invalidOnboarding.promise)
    assertEquals("ONBOARDING_SAVE_FAILED", invalidOnboarding.awaitFailure().code)
  }

  @Test
  fun starRatingAndLibrarySortPersistInSettingsAndRejectInvalidValuesAtomically() {
    val defaults = PromiseCapture()
    module.setSettings("{}", defaults.promise)
    val defaultPreferences = JSONObject(defaults.awaitSuccess().toString())
    assertEquals(0, defaultPreferences.getInt("appRating"))
    assertEquals("recent", defaultPreferences.getString("librarySort"))

    val saved = PromiseCapture()
    module.setSettings("""{"theme":"light","appRating":4,"librarySort":"color"}""", saved.promise)
    val preferences = JSONObject(saved.awaitSuccess().toString())
    assertEquals(4, preferences.getInt("appRating"))
    assertEquals("color", preferences.getString("librarySort"))
    assertEquals(4, database.appRating())
    assertEquals("color", database.librarySort())

    val invalidRating = PromiseCapture()
    module.setSettings("""{"theme":"dark","appRating":6,"librarySort":"recent"}""", invalidRating.promise)
    assertEquals("SETTINGS_SAVE_FAILED", invalidRating.awaitFailure().code)
    assertEquals("light", database.setting("theme", "system"))
    assertEquals(4, database.appRating())
    assertEquals("color", database.librarySort())

    val fractionalRating = PromiseCapture()
    module.setSettings("""{"appRating":2.5}""", fractionalRating.promise)
    assertEquals("SETTINGS_SAVE_FAILED", fractionalRating.awaitFailure().code)

    val invalidSort = PromiseCapture()
    module.setSettings("""{"theme":"dark","appRating":3,"librarySort":"alphabetical"}""", invalidSort.promise)
    assertEquals("SETTINGS_SAVE_FAILED", invalidSort.awaitFailure().code)
    assertEquals("light", database.setting("theme", "system"))
    assertEquals(4, database.appRating())
    assertEquals("color", database.librarySort())
  }

  @Test
  fun setJobColorPersistsOnlySupportedColorsForTerminalJobsAndAllowsClearing() {
    val source = File(context.cacheDir, "labeled-source.pdf").apply { writeBytes("%PDF-1.7".toByteArray()) }
    database.upsertDocument("labeled-doc", "source.pdf", source.absolutePath, 1, "source-hash")
    database.addJob("labeled-job", jobInput("labeled-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("labeled-job", status = "completed")
    val previousUpdate = database.jobJson("labeled-job")!!.getLong("updatedAt")

    val saved = PromiseCapture()
    module.setJobColor("labeled-job", "blue", saved.promise)
    val labeled = JSONObject(saved.awaitSuccess().toString())
    assertEquals("blue", labeled.getString("labelColor"))
    assertEquals(previousUpdate, labeled.getLong("updatedAt"))

    val cleared = PromiseCapture()
    module.setJobColor("labeled-job", "", cleared.promise)
    assertTrue(JSONObject(cleared.awaitSuccess().toString()).isNull("labelColor"))

    val unsupported = PromiseCapture()
    module.setJobColor("labeled-job", "black", unsupported.promise)
    assertEquals("JOB_COLOR_SAVE_FAILED", unsupported.awaitFailure().code)

    val active = PromiseCapture()
    module.setJobColor("labeled-job", "red", active.promise)
    // The cleared completed job remains eligible for relabeling.
    assertEquals("red", JSONObject(active.awaitSuccess().toString()).getString("labelColor"))

    val partialDoc = "partial-label-doc"
    database.upsertDocument(partialDoc, "partial.pdf", source.absolutePath, 1, "source-hash")
    database.addJob("partial-label-job", jobInput(partialDoc, ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("partial-label-job", status = "partial")
    val partial = PromiseCapture()
    module.setJobColor("partial-label-job", "green", partial.promise)
    assertEquals("green", JSONObject(partial.awaitSuccess().toString()).getString("labelColor"))

    database.upsertDocument("queued-label-doc", "queued.pdf", source.absolutePath, 1, "source-hash")
    database.addJob("queued-label-job", jobInput("queued-label-doc", ModelCatalog.RECOMMENDED_ID), 1)
    val queued = PromiseCapture()
    module.setJobColor("queued-label-job", "yellow", queued.promise)
    assertEquals("JOB_COLOR_SAVE_FAILED", queued.awaitFailure().code)

    val missing = PromiseCapture()
    module.setJobColor("no-such-job", "red", missing.promise)
    assertEquals("JOB_COLOR_SAVE_FAILED", missing.awaitFailure().code)
    source.delete()
  }

  @Test
  fun glossaryBridgeTrimsTermsAndRejectsInvalidPairs() {
    val saved = PromiseCapture()
    module.saveGlossaryEntry(
      JSONObject().put("id", "term-1").put("source", "  source term  ").put("target", "  translated term ")
        .put("sourceLanguage", "zh-Hans").put("targetLanguage", "en").toString(),
      saved.promise,
    )
    val result = JSONObject(saved.awaitSuccess().toString())
    assertEquals("source term", result.getString("source"))
    assertEquals("translated term", result.getString("target"))
    assertEquals("source term translates to translated term", database.glossaryText(null, "zh-Hans", "en"))

    val badPair = PromiseCapture()
    module.saveGlossaryEntry(
      "{\"id\":\"bad\",\"source\":\"a\",\"target\":\"b\",\"sourceLanguage\":\"en\",\"targetLanguage\":\"en\"}",
      badPair.promise,
    )
    assertEquals("GLOSSARY_SAVE_FAILED", badPair.awaitFailure().code)

    val deleted = PromiseCapture()
    module.deleteGlossaryEntry("term-1", deleted.promise)
    assertNull(deleted.awaitSuccess())
    assertEquals("", database.glossaryText(null, "zh-Hans", "en"))
  }

  @Test
  fun importingMalformedPdfRejectsAndRemovesItsStagingFile() {
    val source = File(context.cacheDir, "malformed-source.pdf").apply { writeBytes(byteArrayOf('%'.code.toByte(), 'P'.code.toByte())) }
    val result = PromiseCapture()
    module.javaClass.getDeclaredField("pendingImport").apply { isAccessible = true }.set(module, result.promise)

    module.onActivityResult(
      Activity(), 6101, Activity.RESULT_OK,
      Intent().setData(Uri.fromFile(source)),
    )

    assertEquals("PDF_IMPORT_FAILED", result.awaitFailure().code)
    assertEquals(0, database.documentsJson().length())
    assertEquals(0, File(context.filesDir, "documents").listFiles().orEmpty().size)
    source.delete()

    val canceled = PromiseCapture()
    module.javaClass.getDeclaredField("pendingImport").apply { isAccessible = true }.set(module, canceled.promise)
    module.onActivityResult(Activity(), 6101, Activity.RESULT_CANCELED, null)
    assertEquals("null", canceled.awaitSuccess())
  }

  @Test
  @Config(shadows = [ShadowNativePdfCore::class])
  fun importResultStoresValidatedPdfPrivatelyAndReturnsDocumentMetadata() {
    val authority = "dev.kigya.translatepdf.test-input"
    val uri = Uri.parse("content://$authority/source.pdf")
    val pdf = "%PDF-1.7\nsource bytes".toByteArray()
    val inputFile = File(context.cacheDir, "source-with-display-name.pdf").apply { writeBytes(pdf) }
    val provider = TestPdfOutputProvider(inputFile, "machine_translation[1].PDF")
    provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
    ShadowContentResolver.registerProviderInternal(authority, provider)
    val result = PromiseCapture()
    module.javaClass.getDeclaredField("pendingImport").apply { isAccessible = true }.set(module, result.promise)

    module.onActivityResult(Activity(), 6101, Activity.RESULT_OK, Intent().setData(uri))

    val imported = JSONObject(result.awaitSuccess().toString())
    assertEquals("machine_translation_1_.PDF", imported.getString("name"))
    assertEquals(1, imported.getInt("pageCount"))
    val file = File(database.documentPath(imported.getString("id"))!!)
    assertTrue(file.isFile)
    assertTrue(file.readBytes().contentEquals(pdf))
    assertEquals(1, database.documentsJson().length())
    assertNull(module.javaClass.getDeclaredField("pendingImport").apply { isAccessible = true }.get(module))
    file.delete()
    inputFile.delete()

    val extensionAuthority = "dev.kigya.translatepdf.test-input-extension"
    val extensionUri = Uri.parse("content://$extensionAuthority/source")
    val extensionInput = File(context.cacheDir, "source-without-extension.pdf").apply { writeBytes(pdf) }
    val extensionProvider = TestPdfOutputProvider(extensionInput, "report")
    extensionProvider.attachInfo(context, ProviderInfo().apply { this.authority = extensionAuthority })
    ShadowContentResolver.registerProviderInternal(extensionAuthority, extensionProvider)
    val extensionResult = PromiseCapture()
    module.javaClass.getDeclaredField("pendingImport").apply { isAccessible = true }.set(module, extensionResult.promise)
    module.onActivityResult(Activity(), 6101, Activity.RESULT_OK, Intent().setData(extensionUri))
    val importedWithExtension = JSONObject(extensionResult.awaitSuccess().toString())
    assertEquals("report.pdf", importedWithExtension.getString("name"))
    assertEquals(2, database.documentsJson().length())
    File(database.documentPath(importedWithExtension.getString("id"))!!).delete()
    extensionInput.delete()
  }

  @Test
  @Config(shadows = [ShadowNativePdfCore::class])
  fun importingUsesFallbackNameWhenProviderHasNoNameOrAnEmptyCursor() {
    val pdf = "%PDF-1.7\nsource bytes".toByteArray()
    val noNameAuthority = "dev.kigya.translatepdf.test-input-no-name"
    val noNameInput = File(context.cacheDir, "source-no-name.pdf").apply { writeBytes(pdf) }
    val noNameProvider = TestPdfOutputProvider(noNameInput)
    noNameProvider.attachInfo(context, ProviderInfo().apply { this.authority = noNameAuthority })
    ShadowContentResolver.registerProviderInternal(noNameAuthority, noNameProvider)

    val noNameResult = PromiseCapture()
    module.javaClass.getDeclaredField("pendingImport").apply { isAccessible = true }.set(module, noNameResult.promise)
    module.onActivityResult(
      Activity(), 6101, Activity.RESULT_OK,
      Intent().setData(Uri.parse("content://$noNameAuthority/source.pdf")),
    )
    val noNameDocument = JSONObject(noNameResult.awaitSuccess().toString())
    assertEquals("document.pdf", noNameDocument.getString("name"))

    val emptyCursorAuthority = "dev.kigya.translatepdf.test-input-empty-cursor"
    val emptyCursorInput = File(context.cacheDir, "source-empty-cursor.pdf").apply { writeBytes(pdf) }
    val emptyCursorProvider = TestPdfOutputProvider(emptyCursorInput, displayName = "", returnEmptyCursor = true)
    emptyCursorProvider.attachInfo(context, ProviderInfo().apply { this.authority = emptyCursorAuthority })
    ShadowContentResolver.registerProviderInternal(emptyCursorAuthority, emptyCursorProvider)

    val emptyCursorResult = PromiseCapture()
    module.javaClass.getDeclaredField("pendingImport").apply { isAccessible = true }.set(module, emptyCursorResult.promise)
    module.onActivityResult(
      Activity(), 6101, Activity.RESULT_OK,
      Intent().setData(Uri.parse("content://$emptyCursorAuthority/source.pdf")),
    )
    val emptyCursorDocument = JSONObject(emptyCursorResult.awaitSuccess().toString())
    assertEquals("document.pdf", emptyCursorDocument.getString("name"))
    assertEquals(2, database.documentsJson().length())

    listOf(noNameDocument, emptyCursorDocument).forEach { document ->
      File(database.documentPath(document.getString("id"))!!).delete()
    }
    noNameInput.delete()
    emptyCursorInput.delete()
  }

  @Test
  @Config(shadows = [ShadowNativePdfCore::class])
  fun importingRejectsNativeInspectionErrorsAndOutOfRangePageCounts() {
    val cases = listOf(
      JSONObject().put("error", "damaged PDF") to "damaged PDF",
      JSONObject().put("pageCount", 0) to "unsupported page count",
      JSONObject().put("pageCount", 10_001) to "unsupported page count",
    )
    cases.forEachIndexed { index, (inspection, expectedMessage) ->
      ShadowNativePdfCore.inspectionResponse = inspection.toString()
      val authority = "dev.kigya.translatepdf.test-invalid-inspection-$index"
      val input = File(context.cacheDir, "invalid-inspection-$index.pdf").apply {
        writeText("%PDF-1.7\nsource bytes")
      }
      val provider = TestPdfOutputProvider(input, "invalid-$index.pdf")
      provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
      ShadowContentResolver.registerProviderInternal(authority, provider)
      val result = PromiseCapture()
      module.javaClass.getDeclaredField("pendingImport").apply { isAccessible = true }.set(module, result.promise)

      module.onActivityResult(
        Activity(), 6101, Activity.RESULT_OK,
        Intent().setData(Uri.parse("content://$authority/source.pdf")),
      )

      val failure = result.awaitFailure()
      assertEquals("PDF_IMPORT_FAILED", failure.code)
      assertTrue(
        "case $index expected '$expectedMessage' in '${failure.message}'",
        failure.message.orEmpty().contains(expectedMessage, ignoreCase = true),
      )
      assertEquals(0, File(context.filesDir, "documents").listFiles().orEmpty().size)
      input.delete()
    }
    ShadowNativePdfCore.inspectionResponse = null
  }

  @Test
  fun canceledExportDestinationRejectsAndClearsPendingBridgeState() {
    val result = PromiseCapture()
    module.javaClass.getDeclaredField("pendingExport").apply { isAccessible = true }.set(module, result.promise)
    module.javaClass.getDeclaredField("pendingExportFile").apply { isAccessible = true }.set(module, File(context.cacheDir, "pending.pdf"))
    module.javaClass.getDeclaredField("pendingExportId").apply { isAccessible = true }.set(module, "export-id")

    module.onActivityResult(Activity(), 6102, Activity.RESULT_CANCELED, null)

    assertEquals("EXPORT_CANCELED", result.awaitFailure().code)
    assertNull(module.javaClass.getDeclaredField("pendingExport").apply { isAccessible = true }.get(module))
    assertNull(module.javaClass.getDeclaredField("pendingExportFile").apply { isAccessible = true }.get(module))
    assertNull(module.javaClass.getDeclaredField("pendingExportId").apply { isAccessible = true }.get(module))
  }

  @Test
  fun successfulExportResultCopiesPdfToTheSelectedContentUriAndPersistsMetadata() {
    val source = File(context.cacheDir, "export-success-source.pdf").apply { writeBytes("%PDF-1.7 sample".toByteArray()) }
    val destination = File(context.cacheDir, "selected-export.pdf")
    val provider = TestPdfOutputProvider(destination)
    val authority = "dev.kigya.translatepdf.test-output"
    provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
    ShadowContentResolver.registerProviderInternal(authority, provider)
    database.saveExport("export-success", source.absolutePath, "translated-en.pdf")
    val result = PromiseCapture()
    module.javaClass.getDeclaredField("pendingExport").apply { isAccessible = true }.set(module, result.promise)
    module.javaClass.getDeclaredField("pendingExportFile").apply { isAccessible = true }.set(module, source)
    module.javaClass.getDeclaredField("pendingExportId").apply { isAccessible = true }.set(module, "export-success")

    module.onActivityResult(
      Activity(), 6102, Activity.RESULT_OK,
      Intent().setData(Uri.parse("content://$authority/translated-en.pdf")),
    )

    val saved = JSONObject(result.awaitSuccess().toString())
    assertEquals("export-success", saved.getString("id"))
    assertEquals("translated-en.pdf", saved.getString("displayName"))
    assertEquals("content://$authority/translated-en.pdf", saved.getString("uri"))
    assertTrue(destination.readBytes().contentEquals(source.readBytes()))
    assertNull(module.javaClass.getDeclaredField("pendingExport").apply { isAccessible = true }.get(module))
    source.delete()
    destination.delete()
  }

  @Test
  fun invalidLicenseIsRejectedAndPreviouslyHandledPermissionRequestDoesNotNagAgain() {
    val notices = PromiseCapture()
    module.getLicenseNotices(notices.promise)
    val indexedNotices = JSONArray(notices.awaitSuccess().toString())
    assertTrue("curated legal notices should be indexable", indexedNotices.length() >= 20)
    val noticeIds = (0 until indexedNotices.length()).map { indexedNotices.getJSONObject(it).getString("id") }
    assertEquals(noticeIds.size, noticeIds.toSet().size)
    assertTrue(noticeIds.containsAll(listOf("react", "react-native", "pdfium", "tesseract4android", "hy-mt2-7b", "translategemma-4b")))
    assertFalse(noticeIds.any { it.contains("/") || it.contains("..") })
    assertFalse(noticeIds.any { it.contains("cpp-httplib") || it.contains("new-app-screen") || it.contains("iq3") || it.contains("iq2") })
    for (notice in 0 until indexedNotices.length()) {
      val item = indexedNotices.getJSONObject(notice)
      assertTrue(item.getString("title").isNotBlank())
      val text = PromiseCapture()
      module.getLicenseNotice(item.getString("id"), text.promise)
      assertTrue("empty legal text for ${item.getString("id")}", text.awaitSuccess().toString().isNotBlank())
    }

    val notice = PromiseCapture()
    module.getLicenseNotice("../missing", notice.promise)
    assertEquals("LICENSE_READ_FAILED", notice.awaitFailure().code)

    val firstRequest = PromiseCapture()
    module.requestNotificationPermission(firstRequest.promise)
    assertEquals("NOTIFICATION_PERMISSION_UNAVAILABLE", firstRequest.awaitFailure().code)
    assertFalse(database.setting("notification_permission_requested", "false").toBoolean())

    database.setSetting("notification_permission_requested", "true")
    val permission = PromiseCapture()
    module.requestNotificationPermission(permission.promise)
    val permissionState = JSONObject(permission.awaitSuccess().toString())
    assertFalse(permissionState.getBoolean("canRequest"))
    assertTrue(permissionState.getBoolean("requestedBefore"))
  }

  @Test
  @Config(sdk = [32], application = Application::class)
  fun notificationsAreMarkedNotRequiredBeforeAndroidThirteen() {
    val result = PromiseCapture()
    module.requestNotificationPermission(result.promise)
    val state = JSONObject(result.awaitSuccess().toString())
    assertEquals("not_required", state.getString("status"))
    assertFalse(state.getBoolean("canRequest"))
  }

  @Test
  fun notificationRequestHandlesBusyPromptWrongCallbackAndDenial() {
    Shadows.shadowOf(context.applicationContext as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val activity = PermissionTestActivity()
    reactContext.onHostResume(activity)
    val first = PromiseCapture()
    module.requestNotificationPermission(first.promise)
    assertTrue(activity.listener != null)
    assertTrue(database.setting("notification_permission_requested", "false").toBoolean())

    val duplicate = PromiseCapture()
    module.requestNotificationPermission(duplicate.promise)
    assertTrue(JSONObject(duplicate.awaitSuccess().toString()).getBoolean("requestedBefore"))

    assertFalse(activity.listener!!.onRequestPermissionsResult(activity.requestCode + 1, emptyArray(), intArrayOf()))
    assertTrue(activity.listener!!.onRequestPermissionsResult(activity.requestCode, arrayOf(Manifest.permission.POST_NOTIFICATIONS), intArrayOf(PackageManager.PERMISSION_DENIED)))
    val denied = JSONObject(first.awaitSuccess().toString())
    assertEquals("denied", denied.getString("status"))
    assertTrue(denied.getBoolean("requestedBefore"))
  }

  @Test
  fun grantedNotificationPermissionIsReturnedWithoutOpeningPromptAgain() {
    Shadows.shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val activity = PermissionTestActivity()
    reactContext.onHostResume(activity)

    val result = PromiseCapture()
    module.requestNotificationPermission(result.promise)

    val state = JSONObject(result.awaitSuccess().toString())
    assertEquals("granted", state.getString("status"))
    assertNull(activity.listener)
    assertFalse(database.setting("notification_permission_requested", "false").toBoolean())
  }

  @Test
  fun disabledNotificationChannelIsReportedAsDeniedEvenWhenRuntimePermissionIsGranted() {
    Shadows.shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(
      PdfTranslationService.CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_NONE,
    ))
    val result = PromiseCapture()

    module.requestNotificationPermission(result.promise)

    val state = JSONObject(result.awaitSuccess().toString())
    assertEquals("denied", state.getString("status"))
    assertFalse(state.getBoolean("canRequest"))
  }

  private fun moduleDatabase(): TranslatorDatabase = module.javaClass.getDeclaredField("database")
    .apply { isAccessible = true }.get(module) as TranslatorDatabase

  private fun replaceModule(
    availableStorageBytes: ((File) -> Long)? = null,
    startModelService: ((Intent) -> Unit)? = null,
  ) {
    module.invalidate()
    database.close()
    module = PdfTranslatorModule(
      reactContext,
      availableStorageBytes ?: { path -> StatFs(path.absolutePath).let { it.availableBlocksLong * it.blockSizeLong } },
      startModelService ?: { intent ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) reactContext.startForegroundService(intent)
        else reactContext.startService(intent)
        Unit
      },
    )
    database = moduleDatabase()
  }

  private fun jobInput(documentId: String, modelId: String): JSONObject = JSONObject()
    .put("documentId", documentId).put("modelId", modelId)
    .put("sourceLanguage", "zh-Hans").put("targetLanguage", "en")

  private class PromiseCapture {
    data class Failure(val code: String?, val message: String?)

    private val latch = CountDownLatch(1)
    private var value: Any? = null
    private var failure: Failure? = null
    val promise: Promise = Proxy.newProxyInstance(
      Promise::class.java.classLoader,
      arrayOf(Promise::class.java),
    ) { _, method, args ->
      when (method.name) {
        "resolve" -> {
          value = args?.firstOrNull()
          latch.countDown()
        }
        "reject" -> {
          val strings = args.orEmpty().filterIsInstance<String>()
          failure = Failure(strings.firstOrNull(), strings.getOrNull(1))
          latch.countDown()
        }
      }
      null
    } as Promise

    fun awaitSuccess(): Any? {
      assertTrue("native promise should settle", latch.await(10, TimeUnit.SECONDS))
      assertNull("promise should resolve", failure)
      return value
    }

    fun awaitFailure(): Failure {
      assertTrue("native promise should settle", latch.await(10, TimeUnit.SECONDS))
      return failure ?: throw AssertionError("expected promise rejection")
    }
  }

  private class PermissionTestActivity : Activity(), PermissionAwareActivity {
    var requestCode: Int = -1
    var listener: PermissionListener? = null

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_DENIED
    override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_DENIED
    override fun shouldShowRequestPermissionRationale(permission: String): Boolean = false
    override fun requestPermissions(permissions: Array<String>, requestCode: Int, listener: PermissionListener?) {
      this.requestCode = requestCode
      this.listener = listener
    }
  }

  private class TestPdfOutputProvider(
    private val output: File,
    private val displayName: String? = null,
    private val returnEmptyCursor: Boolean = false,
  ) : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
      when {
        returnEmptyCursor -> MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME))
        displayName != null -> MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(displayName)) }
        else -> null
      }
    override fun getType(uri: Uri): String = "application/pdf"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = if (mode == "r") {
      ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)
    } else {
      ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
  }
}

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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
  // ReactApplicationContext retains its Activity weakly; async fixtures must own the host.
  private var retainedHostActivity: Activity? = null

  private fun resumeHostActivity() {
    val activity = Activity()
    retainedHostActivity = activity
    reactContext.onHostResume(activity)
  }

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    context.deleteDatabase("translator-private.db")
    // AndroidX caches provider roots by authority while Robolectric gives each
    // test a distinct real data directory. Reattach using actual manifest data
    // so this fixture exercises the current sandbox rather than an older root.
    val providerInfo = context.packageManager.resolveContentProvider(
      "${context.packageName}.fileprovider", PackageManager.GET_META_DATA,
    ) ?: error("The app's actual FileProvider manifest registration is missing")
    androidx.core.content.FileProvider().attachInfo(context, providerInfo)
    reactContext = StubReactApplicationContext(context)
    module = PdfTranslatorModule(reactContext)
    database = moduleDatabase()
  }

  @After
  fun tearDown() {
    ShadowNativePdfCore.inspectionResponse = null
    ShadowNativePdfCore.resetCallbacks()
    module.invalidate()
    retainedHostActivity = null
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
  @Config(sdk = [28])
  fun notificationPermissionSnapshotMarksPreAndroid13DevicesAsNotRequired() {
    database.setSetting("notification_permission_requested", "false")
    val result = PromiseCapture()

    module.getActivitySnapshot(result.promise)

    val notifications = JSONObject(result.awaitSuccess().toString()).getJSONObject("notifications")
    assertEquals("not_required", notifications.getString("status"))
    assertFalse(notifications.getBoolean("canRequest"))
    assertFalse(notifications.getBoolean("requestedBefore"))
  }

  @Test
  fun disabledForegroundNotificationChannelIsReportedAsDenied() {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
      NotificationChannel(PdfTranslationService.CHANNEL_ID, "Translation", NotificationManager.IMPORTANCE_NONE),
    )
    database.setSetting("notification_permission_requested", "false")
    val result = PromiseCapture()

    module.getActivitySnapshot(result.promise)

    val notifications = JSONObject(result.awaitSuccess().toString()).getJSONObject("notifications")
    assertEquals("denied", notifications.getString("status"))
    assertFalse(notifications.getBoolean("canRequest"))
  }

  @Test
  fun activitySnapshotReturnsOnlyFrequentlyPolledSectionsAndCurrentJobCounters() {
    val source = File(context.filesDir, "activity-snapshot.pdf").apply { writeText("private source") }
    database.upsertDocument("activity-snapshot-doc", "manual.pdf", source.absolutePath, 1, "snapshot-hash")
    database.addJob("activity-snapshot-job", jobInput("activity-snapshot-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("activity-snapshot-job", status = "running", stage = "extracting", currentPage = 0)

    val result = PromiseCapture()
    module.getActivitySnapshot(result.promise)
    val snapshot = JSONObject(result.awaitSuccess().toString())
    assertEquals(setOf("jobs", "models", "notifications"), snapshot.keys().asSequence().toSet())
    assertFalse(snapshot.has("device"))
    assertFalse(snapshot.has("documents"))
    assertFalse(snapshot.has("preferences"))
    val job = snapshot.getJSONArray("jobs").getJSONObject(0)
    assertEquals("activity-snapshot-job", job.getString("id"))
    assertEquals("extracting", job.getString("stage"))
    assertTrue(job.getLong("stageStartedAt") > 0L)
    assertEquals(0, job.getInt("completedBlocks"))
    assertTrue(job.isNull("totalBlocks"))
    source.delete()
  }

  @Test
  @Config(shadows = [ShadowNativePdfCore::class])
  fun activitySnapshotReadsInferenceOnlyForActiveTranslationAndContainsNativeFailures() {
    val source = File(context.filesDir, "activity-inference.pdf").apply { writeText("private source") }
    database.upsertDocument("activity-inference-doc", "manual.pdf", source.absolutePath, 1, "snapshot-hash")
    database.addJob("activity-inference-job", jobInput("activity-inference-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("activity-inference-job", status = "running", stage = "translating", currentPage = 0)
    var inferenceReads = 0
    ShadowNativePdfCore.inferenceProgressCallback = { inferenceReads++; """{"processedTokens":12,"generatedTokens":5}""" }

    val translated = PromiseCapture()
    module.getActivitySnapshot(translated.promise)
    val translatedJob = JSONObject(translated.awaitSuccess().toString()).getJSONArray("jobs").getJSONObject(0)
    assertEquals(1, inferenceReads)
    assertEquals(12L, translatedJob.getLong("processedTokens"))
    assertEquals(5L, translatedJob.getLong("generatedTokens"))

    database.updateJob("activity-inference-job", status = "paused", stage = "translating", currentPage = 0)
    val paused = PromiseCapture()
    module.getActivitySnapshot(paused.promise)
    val pausedJob = JSONObject(paused.awaitSuccess().toString()).getJSONArray("jobs").getJSONObject(0)
    assertEquals("paused", pausedJob.getString("status"))
    assertEquals("inference polling must stop once the worker is paused", 1, inferenceReads)

    database.updateJob("activity-inference-job", status = "running", stage = "translating", currentPage = 0)
    ShadowNativePdfCore.inferenceProgressCallback = { inferenceReads++; error("native progress unavailable") }
    val unavailable = PromiseCapture()
    module.getActivitySnapshot(unavailable.promise)
    assertEquals("activity polling tolerates unavailable native progress", "activity-inference-job",
      JSONObject(unavailable.awaitSuccess().toString()).getJSONArray("jobs").getJSONObject(0).getString("id"))
    assertEquals(2, inferenceReads)
    source.delete()
  }

  @Test
  fun reactPackageRegistersTheTranslatorModuleAndNoUnusedViewManagers() {
    val reactPackage = PdfTranslatorPackage()
    val packageModules = reactPackage.createNativeModules(reactContext)
    val registeredModule = packageModules.single() as PdfTranslatorModule
    assertEquals("PdfTranslator", registeredModule.name)
    assertTrue(reactPackage.createViewManagers(reactContext).isEmpty())
    // This legacy ReactPackage registers through createNativeModules. The
    // optional TurboModule lookup is inherited and deliberately returns null.
    assertNull(reactPackage.getModule("PdfTranslator", reactContext))
    assertNull(reactPackage.getModule("MissingNativeModule", reactContext))
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
  fun leavingTheAppWhileSystemPickerIsOpenKeepsItsPromisePendingUntilPickerReturns() {
    val activity = DestinationActivity()
    retainedHostActivity = activity
    reactContext.onHostResume(activity)
    val picker = PromiseCapture()

    module.pickPdf(picker.promise)
    assertEquals(Intent.ACTION_OPEN_DOCUMENT, activity.openedIntent?.action)

    // ReactContext dispatches this to the ActivityEventListener registered by
    // the module; its generated default bridge must not consume the picker.
    reactContext.onUserLeaveHint(activity)
    picker.assertPending("leaving for the system picker must not settle the import promise")

    module.onActivityResult(activity, 6101, Activity.RESULT_CANCELED, null)
    assertEquals("null", picker.awaitSuccess())
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
  fun startJobRejectsWhileDestroyedWorkerStillOwnsCleanupWithoutCreatingQueuedRow() {
    resumeHostActivity()
    val source = File(context.cacheDir, "pending-owner-source.pdf").apply { writeText("%PDF-1.7") }
    database.upsertDocument("pending-owner-doc", source.name, source.absolutePath, 1, "source-hash")
    database.addJob("old-paused-job", jobInput("pending-owner-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("old-paused-job", status = "paused")

    val ownerField = PdfTranslationService::class.java.getDeclaredField("activeJobId").apply { isAccessible = true }
    val previousOwner = ownerField.get(null)
    val activeField = PdfTranslationService::class.java.getDeclaredField("isActive").apply { isAccessible = true }
    val previousActive = activeField.getBoolean(null)
    try {
      // Model onDestroy's interval: the service is no longer active, but the
      // entered worker has not completed checkpoint/cleanup and still owns it.
      activeField.setBoolean(null, false)
      ownerField.set(null, "old-paused-job")

      val start = PromiseCapture()
      module.startJob(jobInput("pending-owner-doc", ModelCatalog.RECOMMENDED_ID).toString(), start.promise)

      val failure = start.awaitFailure()
      assertEquals("JOB_START_FAILED", failure.code)
      assertTrue(failure.message.orEmpty().contains("Wait until the current background operation finishes"))
      assertEquals(1, database.jobsJson().length())
      assertNull(Shadows.shadowOf(context.applicationContext as Application).nextStartedService)
      assertNull(database.jobJson("old-paused-job")?.takeIf { it.optString("status") == "queued" })
    } finally {
      ownerField.set(null, previousOwner)
      activeField.setBoolean(null, previousActive)
      source.delete()
    }
  }

  @Test
  fun onlineJobsRequireFreshConsentAndQueueBothFormatsWithoutInstalledWeightsOrGlossary() {
    resumeHostActivity()
    database.saveGlossary("local-only-term", "SDK", "SDK", "zh-Hans", "en")
    for (format in listOf("pdf", "docx")) {
      val source = File(context.filesDir, "documents/online-consent.$format").apply {
        parentFile?.mkdirs()
        writeText("unchanged synthetic $format input")
      }
      val documentId = "online-consent-$format"
      database.upsertDocument(documentId, source.name, source.absolutePath, 1, "original-hash", format, 1)
      val input = jobInput(documentId, ModelCatalog.RECOMMENDED_ID)
        .put("engine", JSONObject().put("kind", "online").put("id", "mymemory"))
        .put("useGlossary", true)
      for (consent in listOf(false, true)) {
        input.put("onlineConsent", consent)
        val result = PromiseCapture()
        module.startJob(input.toString(), result.promise)
        if (!consent) {
          val failure = result.awaitFailure()
          assertEquals("JOB_START_FAILED", failure.code)
          assertTrue(failure.message.orEmpty().contains("privacy notice"))
          assertFalse(database.hasActiveJob())
          assertNull(Shadows.shadowOf(context.applicationContext as Application).nextStartedService)
        } else {
          val job = JSONObject(result.awaitSuccess().toString())
          val id = job.getString("id")
          val saved = database.jobJson(id)!!
          assertEquals("mymemory", saved.getString("modelId"))
          assertEquals("online", saved.getJSONObject("engine").getString("kind"))
          assertTrue(saved.getBoolean("onlineConsent"))
          assertEquals("", database.jobGlossaryText(id))
          val command = Shadows.shadowOf(context.applicationContext as Application).nextStartedService!!
          assertEquals(PdfTranslationService.ACTION_START, command.action)
          assertEquals(id, command.getStringExtra(PdfTranslationService.EXTRA_JOB_ID))
          assertEquals("unchanged synthetic $format input", source.readText())
          database.updateJob(id, status = "canceled")
        }
      }
      source.delete()
    }
  }

  @Test
  fun unsupportedOnlineProviderIsRejectedEvenAfterPrivacyConsent() {
    resumeHostActivity()
    val result = PromiseCapture()
    module.startJob(jobInput("not-read-before-provider-check", ModelCatalog.RECOMMENDED_ID)
      .put("engine", JSONObject().put("kind", "online").put("id", "unapproved-provider"))
      .put("onlineConsent", true).toString(), result.promise)
    val failure = result.awaitFailure()
    assertEquals("JOB_START_FAILED", failure.code)
    assertTrue("Unexpected rejection: ${failure.message}", failure.message.orEmpty().contains("supported online translation provider"))
    assertEquals(0, database.jobsJson().length())
    assertNull(Shadows.shadowOf(context.applicationContext as Application).nextStartedService)
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
    assertTrue(open.awaitFailure().message.orEmpty(), open.awaitFailure().message.orEmpty().contains("Open the app"))

    val exported = File(context.filesDir, "exports/shareable.pdf").apply {
      parentFile?.mkdirs()
      writeBytes("%PDF-1.7".toByteArray())
    }
    database.saveExport("saved-export", exported.absolutePath, "translated.pdf")
    val share = PromiseCapture()
    module.shareExport("saved-export", share.promise)
    assertEquals("SHARE_FAILED", share.awaitFailure().code)
    assertTrue(share.awaitFailure().message.orEmpty(), share.awaitFailure().message.orEmpty().contains("Open the app"))
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
  @Config(shadows = [ShadowNativePdfCore::class])
  fun previewJobBuildsAndOpensTheVerifiedPdfAfterItsAsyncWorkCompletes() {
    val jobId = "preview-success-${UUID.randomUUID()}"
    val source = File(context.filesDir, "documents/$jobId.pdf").apply {
      parentFile?.mkdirs()
      writeText("%PDF-1.7 source")
    }
    database.upsertDocument("$jobId-doc", "owners-manual.pdf", source.absolutePath, 1, "source-hash")
    database.addJob(jobId, jobInput("$jobId-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob(jobId, status = "completed", stage = "complete")
    database.savePage(jobId, 0, "completed", JSONArray().put(
      JSONObject().put("id", "block-1").put("sourceText", "Brake").put("translatedText", "Тормоз")
        .put("status", "translated"),
    ).toString(), expectedBlocks = 1)
    val activity = DestinationActivity()
    reactContext.onHostResume(activity)

    val result = PromiseCapture()
    module.previewJob(jobId, result.promise)

    val preview = JSONObject(result.awaitSuccess().toString())
    val saved = database.exportInfo(preview.getString("id"))!!
    val pdf = File(saved.path)
    assertTrue("preview should point to a verified, non-empty PDF", pdf.isFile && pdf.length() > 0L)
    assertEquals("application/pdf", saved.mimeType)
    assertEquals(Intent.ACTION_VIEW, activity.openedIntent?.action)
    assertEquals("application/pdf", activity.openedIntent?.type)
    assertEquals(preview.getString("uri"), activity.openedIntent?.data.toString())
    assertEquals("completed", database.jobJson(jobId)!!.getString("status"))
    assertEquals("complete", database.jobJson(jobId)!!.getString("stage"))
    assertFalse("temporary single-page render artifacts must be cleaned", File(context.cacheDir, "translated-page-work")
      .listFiles().orEmpty().any { it.name.startsWith("$jobId-") })
    pdf.delete()
    source.delete()
  }

  @Test
  @Config(shadows = [ShadowNativePdfCore::class])
  fun getJobRefreshesLegacyCompletedStateWhenRebuiltLayoutFindsReviewIssue() {
    val jobId = "legacy-layout-review-${UUID.randomUUID()}"
    val source = File(context.filesDir, "documents/$jobId.pdf").apply {
      parentFile?.mkdirs()
      writeText("%PDF-1.7 source")
    }
    database.upsertDocument("$jobId-doc", "owners-manual.pdf", source.absolutePath, 1, "source-hash")
    database.addJob(jobId, jobInput("$jobId-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob(jobId, status = "completed", stage = "complete")
    database.savePage(jobId, 0, "completed", JSONArray().put(
      JSONObject().put("id", "block-1").put("sourceText", "Brake")
        .put("translatedText", "Тормоз").put("status", "translated"),
    ).toString(), expectedBlocks = 1)

    // Simulate a pre-v3 published PDF. Its bytes and export row remain valid legacy data,
    // but without a layout receipt getJob must rebuild from the durable checkpoint.
    val exportId = privateJobExportIdForTest(jobId)
    val oldPdf = File(context.filesDir, "exports/$exportId.pdf").apply {
      parentFile?.mkdirs()
      writeText("%PDF-1.7 legacy output")
    }
    database.saveExport(exportId, oldPdf.absolutePath, "legacy.pdf")
    ShadowNativePdfCore.exportCallback = { _, _, destination ->
      File(destination).writeText("%PDF-1.7 preserve-source output")
      JSONObject().put("layoutVersion", 3).put("mode", "preserve-source").put("path", destination)
        .put("sourcePageIndex", 0).put("pageCount", 1)
        .put("partial", true)
        .put("issues", JSONArray().put(JSONObject().put("blockId", "block-1")
          .put("reason", "translation_review").put("message", "The translated region needs source review.")))
        .put("placements", JSONArray().put(JSONObject().put("blockId", "block-1")
          .put("outputPageIndex", 0).put("role", "paragraph")))
        .toString()
    }

    val result = PromiseCapture()
    module.getJob(jobId, 0, result.promise)
    val snapshot = JSONObject(result.awaitSuccess().toString())

    assertEquals("partial", snapshot.getJSONObject("job").getString("status"))
    assertEquals("partial", snapshot.getJSONArray("pages").getJSONObject(0).getString("status"))
    assertEquals("partial", snapshot.getJSONObject("selectedPage").getString("status"))
    assertEquals("Тормоз", snapshot.getJSONObject("selectedPage").getJSONArray("blocks")
      .getJSONObject(0).getString("translatedText"))
    assertEquals(1, snapshot.getJSONObject("pdfLayout").getInt("outputPageCount"))
    assertFalse("the invalidated legacy PDF is replaced after the partial rebuild", oldPdf.exists())
    val published = database.export(exportId)!!.first
    assertTrue(File(published).isFile)
    assertTrue("the rebuilt artifact has a versioned layout receipt", File(published).parentFile
      ?.resolve("layout-receipt.json")?.isFile == true)

    val delete = PromiseCapture()
    module.deleteJob(jobId, delete.promise)
    delete.awaitSuccess()
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
  fun deletionRemovesOnlyPrivateResultsAndWaitsUntilTheLastTranslationBeforeRemovingSource() {
    val documentDirectory = File(context.filesDir, "documents").apply { mkdirs() }
    val source = File(documentDirectory, "shared.pdf").apply { writeText("%PDF-1.7 source") }
    database.upsertDocument("shared-doc", "shared.pdf", source.absolutePath, 1, "shared-hash")
    for (id in listOf("shared-job-a", "shared-job-b")) {
      database.addJob(id, jobInput("shared-doc", ModelCatalog.RECOMMENDED_ID), 1)
      database.updateJob(id, status = "paused")
    }
    val exportId = privateJobExportIdForTest("shared-job-a")
    val exportDirectory = File(context.filesDir, "exports").apply { mkdirs() }
    val privateResult = File(exportDirectory, "$exportId.pdf").apply { writeText("private result") }
    database.saveExport(exportId, privateResult.absolutePath, "translated.pdf")
    val userExport = File(context.cacheDir, "user-owned-export.pdf").apply { writeText("user export") }

    val deleteFirst = PromiseCapture()
    module.deleteJob("shared-job-a", deleteFirst.promise)
    deleteFirst.awaitSuccess()
    assertFalse(privateResult.exists())
    assertTrue(source.exists())
    assertTrue(userExport.exists())
    assertTrue(database.documentInfo("shared-doc") != null)
    assertNotNull(database.jobJson("shared-job-b"))

    val deleteLast = PromiseCapture()
    module.deleteJob("shared-job-b", deleteLast.promise)
    deleteLast.awaitSuccess()
    assertFalse(source.exists())
    assertNull(database.documentInfo("shared-doc"))
    assertTrue(userExport.exists())
    userExport.delete()
  }

  @Test
  @Config(shadows = [ShadowNativePdfCore::class])
  fun deletingJobRemovesPublishedV2PdfReceiptAndThumbnailsButPreservesSharedSource() {
    val jobId = "delete-v2-${UUID.randomUUID()}"
    val documentId = "$jobId-document"
    val source = File(context.filesDir, "documents/$jobId.pdf").apply {
      parentFile?.mkdirs()
      writeText("%PDF-1.7 shared source")
    }
    database.upsertDocument(documentId, source.name, source.absolutePath, 1, "shared-source-hash")
    database.addJob(jobId, jobInput(documentId, ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob(jobId, status = "completed", stage = "complete")
    database.savePage(jobId, 0, "completed", JSONArray().put(JSONObject()
      .put("id", "v2-block").put("sourceText", "Brake").put("translatedText", "Тормоз")
      .put("status", "translated")).toString(), 612.0, 792.0, null, 1)
    val siblingId = "$jobId-sibling"
    database.addJob(siblingId, jobInput(documentId, ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob(siblingId, status = "paused")

    val font = File(context.cacheDir, "$jobId-test-font.ttf").apply { writeText("font") }
    val published = JobPdfExporter(context, database, fontProvider = { font }, cjkFontProvider = { font })
      .getOrBuild(jobId)
    val previewDirectory = File(context.cacheDir, "thumbnails").apply { mkdirs() }
    val jobPreview = File(previewDirectory,
      "translated_v2_${jobId}_${published.receipt.outputSha256}_0_320.png").apply { writeText("job preview") }
    val siblingPreview = File(previewDirectory, "translated_v2_${jobId}-sibling_hash_0_320.png")
      .apply { writeText("sibling preview") }
    assertTrue(published.file.isFile)
    assertTrue(File(published.generationDirectory, "layout-receipt.json").isFile)

    val result = PromiseCapture()
    module.deleteJob(jobId, result.promise)

    assertNull(result.awaitSuccess())
    assertFalse("private v2 generation and receipt must be removed", published.generationDirectory.exists())
    assertFalse(jobPreview.exists())
    assertTrue("the source remains while a sibling translation references it", source.isFile)
    assertNotNull(database.documentInfo(documentId))
    assertNotNull(database.jobJson(siblingId))
    assertNull(database.exportInfo(published.exportId))
    assertEquals("sibling preview", siblingPreview.readText())
    siblingPreview.delete()
    font.delete()
    source.delete()
  }

  @Test
  fun deletingTheLastJobRemovesItsLibraryRowButPreservesAnExternalUserSource() {
    val externalSource = File(context.cacheDir, "user-selected-original.pdf").apply { writeText("%PDF-1.7 external source") }
    database.upsertDocument("external-job-doc", externalSource.name, externalSource.absolutePath, 1, "external-hash")
    database.addJob("external-job", jobInput("external-job-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("external-job", status = "paused")

    val result = PromiseCapture()
    module.deleteJob("external-job", result.promise)

    assertNull(result.awaitSuccess())
    assertNull(database.jobJson("external-job"))
    assertNull(database.documentInfo("external-job-doc"))
    assertTrue("deleting a library entry must never delete a source outside the private import directory", externalSource.isFile)
    externalSource.delete()
  }

  @Test
  fun deletingDocxJobRemovesPrivateDocxAndPdfExportRowsButKeepsUserCopies() {
    val source = File(context.filesDir, "documents/deleted-word.docx").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
    }
    database.upsertDocument("deleted-word-doc", source.name, source.absolutePath, 1, "word-hash", "docx", 1)
    database.addJob("deleted-word-job", jobInput("deleted-word-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("deleted-word-job", status = "completed")

    val privateDocx = File(context.filesDir, "translated-docx/deleted-word-job.docx").apply {
      parentFile?.mkdirs()
      writeText("private translated Word")
    }
    val privatePreview = File(context.cacheDir, "docx-preview-pdf/renderer-v5-translated-deleted-word-job.pdf").apply {
      parentFile?.mkdirs()
      writeText("private translated PDF preview")
    }
    val hashedExportId = privateJobExportIdForTest("deleted-word-job")
    val hashedPdf = File(context.filesDir, "exports/$hashedExportId.pdf").apply {
      parentFile?.mkdirs()
      writeText("private PDF export")
    }
    val externalDocx = File(context.cacheDir, "user-saved-word.docx").apply { writeText("user copy") }
    val externalPdf = File(context.cacheDir, "user-saved-word.pdf").apply { writeText("user copy") }
    database.saveExport("deleted-word-job", privateDocx.absolutePath, "translated.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    database.saveExport("pdf-export-deleted-word-job", privatePreview.absolutePath, "translated.pdf")
    database.saveExport("preview-pdf-deleted-word-job", privatePreview.absolutePath, "preview.pdf")
    database.saveExport(hashedExportId, hashedPdf.absolutePath, "translated.pdf")

    val result = PromiseCapture()
    module.deleteJob("deleted-word-job", result.promise)

    result.awaitSuccess()
    assertFalse(privateDocx.exists())
    assertFalse(privatePreview.exists())
    assertFalse(hashedPdf.exists())
    for (id in listOf("deleted-word-job", "pdf-export-deleted-word-job", "preview-pdf-deleted-word-job", hashedExportId)) {
      assertNull("stale private export row: $id", database.exportInfo(id))
    }
    assertTrue(externalDocx.isFile)
    assertTrue(externalPdf.isFile)
    externalDocx.delete()
    externalPdf.delete()
  }

  @Test
  fun deletingAnUnscheduledExternalDocumentRemovesOnlyItsPrivateLibraryRow() {
    val external = File(context.cacheDir, "outside-import-directory.pdf").apply { writeText("source") }
    database.upsertDocument("external-doc", "outside.pdf", external.absolutePath, 1, "external-hash")
    val result = PromiseCapture()
    module.deleteDocument("external-doc", result.promise)
    result.awaitSuccess()
    assertNull(database.documentInfo("external-doc"))
    assertTrue(external.exists())
    external.delete()
  }

  @Test
  fun deleteDocumentRejectsMissingOrReferencedRowsAndRemovesAnUnreferencedPrivateImportAndPreviews() {
    val missing = PromiseCapture()
    module.deleteDocument("no-such-document", missing.promise)
    assertEquals("DOCUMENT_DELETE_FAILED", missing.awaitFailure().code)
    assertTrue(missing.awaitFailure().message.orEmpty().contains("not found"))

    val documentDirectory = File(context.filesDir, "documents").apply { mkdirs() }
    val referencedSource = File(documentDirectory, "referenced.pdf").apply { writeText("referenced") }
    database.upsertDocument("referenced-doc", referencedSource.name, referencedSource.absolutePath, 1, "referenced-hash")
    database.addJob("referenced-job", jobInput("referenced-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("referenced-job", status = "paused")
    val referenced = PromiseCapture()

    module.deleteDocument("referenced-doc", referenced.promise)

    assertEquals("DOCUMENT_DELETE_FAILED", referenced.awaitFailure().code)
    assertTrue(referenced.awaitFailure().message.orEmpty().contains("translation jobs"))
    assertTrue(referencedSource.isFile)
    assertNotNull(database.documentInfo("referenced-doc"))

    val privateSource = File(documentDirectory, "unreferenced.pdf").apply { writeText("private import") }
    database.upsertDocument("unreferenced-doc", privateSource.name, privateSource.absolutePath, 1, "unreferenced-hash")
    val thumbnail = File(context.cacheDir, "thumbnails/original_unreferenced-doc_0_128.png").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2, 3))
    }
    val previewPdf = File(context.cacheDir, "docx-preview-pdf/renderer-v5-original-unreferenced-doc.pdf").apply {
      parentFile?.mkdirs()
      writeText("private preview")
    }
    val removed = PromiseCapture()

    module.deleteDocument("unreferenced-doc", removed.promise)

    assertNull(removed.awaitSuccess())
    assertFalse(privateSource.exists())
    assertFalse(thumbnail.exists())
    assertFalse(previewPdf.exists())
    assertNull(database.documentInfo("unreferenced-doc"))
    assertTrue("the rejected row must remain until its job is separately removed", referencedSource.exists())
    database.deleteJob("referenced-job")
    referencedSource.delete()
  }

  @Test
  fun deleteJobWaitsForStoppingWorkerEvenWhenItsLastPersistedStatusIsPaused() {
    val source = File(context.cacheDir, "stopping-delete-source.pdf").apply { writeText("source") }
    database.upsertDocument("stopping-delete-doc", source.name, source.absolutePath, 1, "source-hash")
    database.addJob("stopping-delete-job", jobInput("stopping-delete-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("stopping-delete-job", status = "paused")
    val stopIdField = PdfTranslationService::class.java.getDeclaredField("stopRequestedJobId").apply { isAccessible = true }
    val stopCanceledField = PdfTranslationService::class.java.getDeclaredField("stopRequestedCanceled").apply { isAccessible = true }
    val previousStopId = stopIdField.get(null)
    val previousCanceled = stopCanceledField.getBoolean(null)
    try {
      PdfTranslationService.markWorkerStopping("stopping-delete-job", canceled = false)
      val snapshot = database.jobJson("stopping-delete-job")!!
      assertTrue(snapshot.getBoolean("workerStopping"))

      val result = PromiseCapture()
      module.deleteJob("stopping-delete-job", result.promise)

      assertEquals("JOB_DELETE_FAILED", result.awaitFailure().code)
      assertTrue(result.awaitFailure().message.orEmpty().contains("wait for it to stop"))
      assertNotNull(database.jobJson("stopping-delete-job"))
      assertTrue(source.isFile)
    } finally {
      stopIdField.set(null, previousStopId)
      stopCanceledField.setBoolean(null, previousCanceled)
      source.delete()
    }
  }

  @Test
  fun docxPreviewRejectsNonWordAndMissingSourcesAndNeverKeepsAnInvalidCachedPdf() {
    val pdfSource = File(context.cacheDir, "preview-not-word.pdf").apply { writeText("source") }
    database.upsertDocument("preview-not-word", pdfSource.name, pdfSource.absolutePath, 1, "pdf-hash", "pdf")
    val nonWord = PromiseCapture()
    module.renderDocxPreview("preview-not-word", null, false, nonWord.promise)
    assertEquals("DOCX_PREVIEW_FAILED", nonWord.awaitFailure().code)
    assertTrue(nonWord.awaitFailure().message.orEmpty().contains("not a Word"))

    val missing = PromiseCapture()
    module.renderDocxPreview("missing-word-document", null, false, missing.promise)
    assertEquals("DOCX_PREVIEW_FAILED", missing.awaitFailure().code)

    val source = File(context.filesDir, "documents/invalid-preview.docx").apply {
      parentFile?.mkdirs()
      writeText("private Word source")
    }
    database.upsertDocument("invalid-preview-doc", source.name, source.absolutePath, 1, "word-hash", "docx", 1)
    val invalidCache = File(context.cacheDir, "docx-preview-pdf/renderer-v5-original-invalid-preview-doc.pdf").apply {
      parentFile?.mkdirs()
      writeText("not a PDF")
    }
    val noActivity = PromiseCapture()

    module.renderDocxPreview("invalid-preview-doc", null, false, noActivity.promise)

    assertEquals("DOCX_PREVIEW_FAILED", noActivity.awaitFailure().code)
    assertTrue(noActivity.awaitFailure().message.orEmpty().contains("Open Folivela"))
    assertFalse("an invalid cached PDF must be discarded", invalidCache.exists())
    assertTrue("preview preparation must preserve the source", source.isFile)
    source.delete()
    pdfSource.delete()
  }


  @Test
  fun docxPreviewRequiresTheMatchingCompletedTranslation() {
    val source = File(context.filesDir, "documents/cached-preview.docx").apply {
      parentFile?.mkdirs()
      writeText("private Word source")
    }
    database.upsertDocument("cached-preview-doc", source.name, source.absolutePath, 1, "word-hash", "docx", 1)

    val noJobId = PromiseCapture()
    module.renderDocxPreview("cached-preview-doc", null, true, noJobId.promise)
    assertEquals("DOCX_PREVIEW_FAILED", noJobId.awaitFailure().code)

    database.addJob("queued-preview-job", jobInput("cached-preview-doc", ModelCatalog.RECOMMENDED_ID), 1)
    val queued = PromiseCapture()
    module.renderDocxPreview("cached-preview-doc", "queued-preview-job", true, queued.promise)
    assertEquals("DOCX_PREVIEW_FAILED", queued.awaitFailure().code)

    val otherSource = File(context.filesDir, "documents/other-preview.docx").apply {
      parentFile?.mkdirs()
      writeText("other Word source")
    }
    database.upsertDocument("elsewhere", otherSource.name, otherSource.absolutePath, 1, "other-word-hash", "docx", 1)
    database.addJob("other-preview-job", jobInput("elsewhere", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("other-preview-job", status = "completed")
    val mismatched = PromiseCapture()
    module.renderDocxPreview("cached-preview-doc", "other-preview-job", true, mismatched.promise)
    assertEquals("DOCX_PREVIEW_FAILED", mismatched.awaitFailure().code)

    val pdfSource = File(context.cacheDir, "translated-nonword-source.pdf").apply { writeText("source") }
    database.upsertDocument("translated-nonword-doc", pdfSource.name, pdfSource.absolutePath, 1, "pdf-hash", "pdf")
    database.addJob("translated-nonword-job", jobInput("translated-nonword-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("translated-nonword-job", status = "completed")
    val translatedNonWord = PromiseCapture()
    module.renderDocxPreview("translated-nonword-doc", "translated-nonword-job", true, translatedNonWord.promise)
    assertEquals("DOCX_PREVIEW_FAILED", translatedNonWord.awaitFailure().code)
    assertTrue(translatedNonWord.awaitFailure().message.orEmpty().contains("not a Word"))

    source.delete()
    otherSource.delete()
    pdfSource.delete()
  }

  private fun privateJobExportIdForTest(jobId: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(jobId.toByteArray(Charsets.UTF_8))
    return "job-" + digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
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
  fun resumeJobRejectsTerminalRowsWithoutMutatingTheirCheckpointOrStartingService() {
    val source = File(context.cacheDir, "resume-terminal-source.pdf").apply { writeText("%PDF-1.7") }
    database.upsertDocument("resume-terminal-doc", source.name, source.absolutePath, 1, "source-hash")
    for ((jobId, terminalStatus) in listOf("resume-completed" to "completed", "resume-canceled" to "canceled")) {
      database.addJob(jobId, jobInput("resume-terminal-doc", ModelCatalog.RECOMMENDED_ID), 1)
      database.updateJob(jobId, status = terminalStatus, stage = "export", error = "preserve this result")
    }
    resumeHostActivity()

    for ((jobId, terminalStatus) in listOf("resume-completed" to "completed", "resume-canceled" to "canceled")) {
      val before = database.jobJson(jobId)!!
      val result = PromiseCapture()
      module.resumeJob(jobId, result.promise)

      assertEquals("JOB_RESUME_FAILED", result.awaitFailure().code)
      val after = database.jobJson(jobId)!!
      assertEquals(terminalStatus, after.getString("status"))
      assertEquals("preserve this result", after.getString("error"))
      assertEquals(before.getString("stage"), after.getString("stage"))
      assertEquals(before.getInt("updatedAt"), after.getInt("updatedAt"))
      assertNull(Shadows.shadowOf(context.applicationContext as Application).nextStartedService)
    }
    source.delete()
  }

  @Test
  fun resumeJobRejectsAnotherQueuedJobWithoutChangingEitherRowOrStartingService() {
    val source = File(context.cacheDir, "resume-active-source.pdf").apply { writeText("%PDF-1.7") }
    database.upsertDocument("resume-active-doc", source.name, source.absolutePath, 1, "source-hash")
    database.addJob("resume-target", jobInput("resume-active-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("resume-target", status = "paused", stage = "reading", error = "keep target error")
    database.addJob("resume-other", jobInput("resume-active-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("resume-other", status = "queued", stage = "queued")
    resumeHostActivity()
    val targetBefore = database.jobJson("resume-target")!!
    val otherBefore = database.jobJson("resume-other")!!
    val result = PromiseCapture()

    module.resumeJob("resume-target", result.promise)

    assertEquals("JOB_RESUME_FAILED", result.awaitFailure().code)
    val targetAfter = database.jobJson("resume-target")!!
    val otherAfter = database.jobJson("resume-other")!!
    assertEquals("paused", targetAfter.getString("status"))
    assertEquals("reading", targetAfter.getString("stage"))
    assertEquals("keep target error", targetAfter.getString("error"))
    assertEquals(targetBefore.getInt("updatedAt"), targetAfter.getInt("updatedAt"))
    assertEquals("queued", otherAfter.getString("status"))
    assertEquals(otherBefore.getInt("updatedAt"), otherAfter.getInt("updatedAt"))
    assertNull(Shadows.shadowOf(context.applicationContext as Application).nextStartedService)
    source.delete()
  }

  @Test
  fun resumeJobRejectsStalePipelineBeforeChangingTheSavedJob() {
    val source = File(context.cacheDir, "resume-stale-pipeline.pdf").apply { writeText("%PDF-1.7") }
    database.upsertDocument("resume-stale-doc", source.name, source.absolutePath, 1, "source-hash")
    database.addJob("resume-stale-job", jobInput("resume-stale-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("resume-stale-job", status = "paused", stage = "reading", error = "old checkpoint")
    database.writableDatabase.execSQL("UPDATE jobs SET pipeline_version=? WHERE id=?", arrayOf(TranslatorDatabase.PIPELINE_VERSION - 1, "resume-stale-job"))
    resumeHostActivity()
    val before = database.jobJson("resume-stale-job")!!
    val result = PromiseCapture()

    module.resumeJob("resume-stale-job", result.promise)

    assertEquals("JOB_RESUME_FAILED", result.awaitFailure().code)
    val after = database.jobJson("resume-stale-job")!!
    assertEquals("paused", after.getString("status"))
    assertEquals("reading", after.getString("stage"))
    assertEquals("old checkpoint", after.getString("error"))
    assertEquals(before.getInt("updatedAt"), after.getInt("updatedAt"))
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
  @Config(shadows = [ShadowNativePdfCore::class])
  fun pageRenderReusesPngSignatureCheckedOriginalAndTranslatedCaches() {
    val source = File(context.cacheDir, "cached-render-source.pdf").apply { writeBytes("not parsed when cached".toByteArray()) }
    database.upsertDocument("cached-doc", "source.pdf", source.absolutePath, 1, "source-hash")
    database.addJob("cached-job", jobInput("cached-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob("cached-job", status = "completed")
    database.savePage(
      "cached-job", 0, "completed",
      JSONArray().put(JSONObject().put("id", "cached-block").put("sourceText", "Brake")
        .put("translatedText", "Тормоз").put("status", "translated")).toString(), expectedBlocks = 1,
    )
    val builtPdf = JobPdfExporter(context, database).getOrBuild("cached-job")
    val thumbnails = File(context.cacheDir, "thumbnails").apply { mkdirs() }
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    File(thumbnails, "original_cached-doc_0_320.png").writeBytes(signature)
    File(thumbnails, "translated_v2_cached-job_${builtPdf.receipt.outputSha256}_0_320.png").writeBytes(signature)

    val original = PromiseCapture()
    module.renderPage("cached-doc", 0, 320, original.promise)
    assertTrue(original.awaitSuccess().toString().endsWith("original_cached-doc_0_320.png"))

    val translated = PromiseCapture()
    module.renderTranslatedPage("cached-job", 0, 320, translated.promise)
    assertTrue(translated.awaitSuccess().toString().endsWith(
      "translated_v2_cached-job_${builtPdf.receipt.outputSha256}_0_320.png"))
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
      JSONObject().put("id", "word-block").put("status", "translated")
        .put("sourceText", "hello").put("translatedText", "你好"),
    ).toString(), expectedBlocks = 1)
    val result = PromiseCapture()

    module.renderTranslatedPage(jobId, 0, 320, result.promise)

    val rendered = File(Uri.parse(result.awaitSuccess().toString()).path!!)
    assertTrue(rendered.isFile)
    assertTrue(rendered.readBytes().take(8).toByteArray().contentEquals(byteArrayOf(
      0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )))
    assertTrue(File(context.cacheDir, "NotoSansCondensed-Regular.ttf").isFile)
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
  fun setJobColorPersistsOnlySupportedColorsForNonCanceledJobsAndAllowsClearing() {
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
    assertEquals("yellow", JSONObject(queued.awaitSuccess().toString()).getString("labelColor"))

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
  @Config(shadows = [ShadowNativePdfCore::class])
  fun exportJobBuildsVerifiedPdfAndCopiesItToTheUserChosenDestination() {
    val jobId = "export-roundtrip-${UUID.randomUUID()}"
    val source = File(context.filesDir, "documents/$jobId.pdf").apply {
      parentFile?.mkdirs()
      writeText("%PDF-1.7 source")
    }
    database.upsertDocument("$jobId-doc", "owners-manual.pdf", source.absolutePath, 1, "source-hash")
    database.addJob(jobId, jobInput("$jobId-doc", ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob(jobId, status = "completed", stage = "complete")
    database.savePage(jobId, 0, "completed", JSONArray().put(
      JSONObject().put("id", "block-1").put("sourceText", "Brake").put("translatedText", "Тормоз")
        .put("status", "translated"),
    ).toString(), expectedBlocks = 1)
    val activity = DestinationActivity()
    reactContext.onHostResume(activity)
    val destination = File(context.cacheDir, "user-chosen-translation.pdf")
    val authority = "dev.kigya.translatepdf.export-roundtrip"
    TestPdfOutputProvider(destination).also {
      it.attachInfo(context, ProviderInfo().apply { this.authority = authority })
      ShadowContentResolver.registerProviderInternal(authority, it)
    }

    val result = PromiseCapture()
    module.exportJob(jobId, result.promise)

    assertTrue("export should wait for the destination picker after building the result", activity.opened.await(10, TimeUnit.SECONDS))
    assertEquals(Intent.ACTION_CREATE_DOCUMENT, activity.openedIntent?.action)
    assertEquals("application/pdf", activity.openedIntent?.type)
    assertEquals("owners-manual-zh-Hans-en.pdf", activity.openedIntent?.getStringExtra(Intent.EXTRA_TITLE))
    module.onActivityResult(activity, activity.requestCode, Activity.RESULT_OK,
      Intent().setData(Uri.parse("content://$authority/translation.pdf")))

    val saved = JSONObject(result.awaitSuccess().toString())
    val privateExport = database.exportInfo(saved.getString("id"))!!
    assertEquals("pdf", saved.getString("format"))
    assertEquals("application/pdf", saved.getString("mimeType"))
    assertEquals("content://$authority/translation.pdf", saved.getString("uri"))
    assertTrue("the selected destination must receive the verified output bytes",
      destination.isFile && destination.readBytes().contentEquals(File(privateExport.path).readBytes()))
    assertEquals("completed", database.jobJson(jobId)!!.getString("status"))
    assertNull(module.javaClass.getDeclaredField("pendingExport").apply { isAccessible = true }.get(module))
    destination.delete()
    File(privateExport.path).delete()
    source.delete()
  }

  @Test
  fun deletedExportDuringDestinationSelectionRejectsWithoutLosingThePublishedRecord() {
    val source = File(context.cacheDir, "removed-during-picker.pdf").apply { writeText("%PDF-1.7 synthetic") }
    database.saveExport("removed-during-picker", source.absolutePath, "translated.pdf")
    val destination = File(context.cacheDir, "failed-destination.pdf")
    val authority = "dev.kigya.translatepdf.removed-export"
    TestPdfOutputProvider(destination).also {
      it.attachInfo(context, ProviderInfo().apply { this.authority = authority })
      ShadowContentResolver.registerProviderInternal(authority, it)
    }
    val result = PromiseCapture()
    module.javaClass.getDeclaredField("pendingExport").apply { isAccessible = true }.set(module, result.promise)
    module.javaClass.getDeclaredField("pendingExportFile").apply { isAccessible = true }.set(module, source)
    module.javaClass.getDeclaredField("pendingExportId").apply { isAccessible = true }.set(module, "removed-during-picker")
    assertTrue(source.delete())

    module.onActivityResult(Activity(), 6102, Activity.RESULT_OK, Intent().setData(Uri.parse("content://$authority/result.pdf")))

    assertEquals("EXPORT_SAVE_FAILED", result.awaitFailure().code)
    assertEquals(source.absolutePath, database.exportInfo("removed-during-picker")!!.path)
    for (field in listOf("pendingExport", "pendingExportFile", "pendingExportId")) {
      assertNull(module.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(module))
    }
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
  fun notificationPromptFailureKeepsPermissionRetryableAndDoesNotBlockTheLibrary() {
    Shadows.shadowOf(context.applicationContext as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    resumeHostActivity()
    val unsupported = PromiseCapture()
    module.requestNotificationPermission(unsupported.promise)
    assertEquals("NOTIFICATION_PERMISSION_UNAVAILABLE", unsupported.awaitFailure().code)
    assertFalse(database.setting("notification_permission_requested", "false").toBoolean())

    val activity = PermissionTestActivity().apply { requestFailure = IllegalStateException("synthetic platform rejection") }
    reactContext.onHostResume(activity)
    val failed = PromiseCapture()
    module.requestNotificationPermission(failed.promise)
    assertEquals("NOTIFICATION_PERMISSION_UNAVAILABLE", failed.awaitFailure().code)
    assertNull(module.javaClass.getDeclaredField("pendingNotificationPermission").apply { isAccessible = true }.get(module))
    assertFalse(database.setting("notification_permission_requested", "false").toBoolean())

    activity.requestFailure = null
    val retry = PromiseCapture()
    module.requestNotificationPermission(retry.promise)
    assertTrue(activity.listener!!.onRequestPermissionsResult(activity.requestCode, emptyArray(), intArrayOf(PackageManager.PERMISSION_DENIED)))
    assertEquals("denied", JSONObject(retry.awaitSuccess().toString()).getString("status"))
    val library = PromiseCapture()
    module.bootstrap(library.promise)
    assertEquals(0, JSONObject(library.awaitSuccess().toString()).getJSONArray("documents").length())
  }

  @Test
  fun notificationPermissionRequestRejectsAFinishingActivityWithoutMarkingThePromptAsShown() {
    Shadows.shadowOf(context.applicationContext as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    database.setSetting("notification_permission_requested", "false")
    val finishing = PermissionTestActivity().apply { finish() }
    reactContext.onHostResume(finishing)

    val result = PromiseCapture()
    module.requestNotificationPermission(result.promise)

    assertEquals("NOTIFICATION_PERMISSION_UNAVAILABLE", result.awaitFailure().code)
    assertTrue(result.awaitFailure().message.orEmpty().contains("Open the app"))
    assertFalse(database.setting("notification_permission_requested", "false").toBoolean())
    assertNull(module.javaClass.getDeclaredField("pendingNotificationPermission").apply { isAccessible = true }.get(module))
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

  @Test
  @Config(shadows = [ShadowNativePdfCore::class])
  fun wordImportUsesContentAndPreservesSegmentWarningsAndOriginalBytes() {
    val authority="dev.kigya.translatepdf.test-word"
    val bytes=byteArrayOf(80,75,3,4,9,8,7,6)
    val original=File(context.cacheDir,"word-input.docx").apply {writeBytes(bytes)}
    val provider=TestPdfOutputProvider(original,"folder/rapport[1].DOCX")
    provider.attachInfo(context,ProviderInfo().apply {this.authority=authority})
    ShadowContentResolver.registerProviderInternal(authority,provider)
    val result=PromiseCapture()
    module.javaClass.getDeclaredField("pendingImport").apply {isAccessible=true}.set(module,result.promise)
    module.onActivityResult(Activity(),6101,Activity.RESULT_OK,Intent().setData(Uri.parse("content://$authority/word")))
    val document=JSONObject(result.awaitSuccess().toString())
    assertEquals("docx",document.getString("format"))
    assertEquals("rapport_1_.DOCX",document.getString("name"))
    assertEquals(2,document.getInt("segmentCount"))
    assertEquals("Unsupported element preserved",document.getJSONArray("warnings").getString(0))
    val stored=File(database.documentPath(document.getString("id"))!!)
    assertTrue(stored.readBytes().contentEquals(bytes))
    assertTrue(original.readBytes().contentEquals(bytes))
    stored.delete();original.delete()
  }

  @Test
  @Config(shadows = [ShadowNativePdfCore::class])
  fun completedAndPartialWordExportsAreDurableEvenWhenDestinationUiIsUnavailable() {
    val source=File(context.filesDir,"documents/word-export.docx").apply {parentFile?.mkdirs();writeText("original")}
    database.upsertDocument("word-doc","word.docx",source.absolutePath,2,"hash","docx",2)
    for(status in listOf("completed","partial")) {
      val id="word-$status"
      database.addJob(id,jobInput("word-doc",ModelCatalog.RECOMMENDED_ID),2)
      database.updateJob(id,status=status)
      for(index in 0..1) database.savePage(id,index,status,JSONArray().put(JSONObject().put("id","p$index").put("text","original").put("translatedText","translated").put("status","translated")).toString(),expectedBlocks=1)
      val result=PromiseCapture()
      module.exportDocument(id,"docx",result.promise)
      assertEquals("EXPORT_UNAVAILABLE",result.awaitFailure().code)
      val record=database.exportInfo(id)!!
      assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",record.mimeType)
      assertEquals("word-translated.docx",record.name)
      assertEquals("translated Word",File(record.path).readText())
      assertEquals("original",source.readText())
      File(record.path).delete()
    }
    source.delete()
  }

  @Test
  fun wordPreviewAndExportPreconditionsRejectWithoutStartingAnActivity() {
    val source=File(context.filesDir,"documents/preconditions.docx").apply {parentFile?.mkdirs();writeText("original")}
    database.upsertDocument("word-preconditions","word.docx",source.absolutePath,1,"hash","docx",1)
    database.addJob("word-waiting",jobInput("word-preconditions",ModelCatalog.RECOMMENDED_ID),1)
    val invalid=PromiseCapture();module.exportDocument("word-waiting","doc",invalid.promise)
    assertEquals("EXPORT_FORMAT_UNSUPPORTED",invalid.awaitFailure().code)
    val waiting=PromiseCapture();module.exportDocument("word-waiting","docx",waiting.promise)
    assertEquals("JOB_NOT_READY",waiting.awaitFailure().code)
    val noJob=PromiseCapture();module.renderDocxPreview("word-preconditions",null,true,noJob.promise)
    assertEquals("DOCX_PREVIEW_FAILED",noJob.awaitFailure().code)
    val queued=PromiseCapture();module.renderDocxPreview("word-preconditions","word-waiting",true,queued.promise)
    assertEquals("DOCX_PREVIEW_FAILED",queued.awaitFailure().code)
    val noActivity=PromiseCapture();module.renderDocxPreview("word-preconditions",null,false,noActivity.promise)
    assertEquals("DOCX_PREVIEW_FAILED",noActivity.awaitFailure().code)
    assertTrue(noActivity.awaitFailure().message?.contains("Open Folivela")==true)
    assertEquals("original",source.readText());source.delete()
  }

  @Test
  @Config(shadows = [ShadowNativePdfCore::class])
  fun wordExportUsesTheWordMimeTypeAndCopiesTheDurableArtifactToTheChosenDestination() {
    val source=File(context.filesDir,"documents/word-destination.docx").apply {parentFile?.mkdirs();writeText("original")}
    database.upsertDocument("word-destination","résumé.docx",source.absolutePath,2,"hash","docx",2)
    database.addJob("word-destination-job",jobInput("word-destination",ModelCatalog.RECOMMENDED_ID),2)
    database.updateJob("word-destination-job",status="completed")
    for(index in 0..1) database.savePage("word-destination-job",index,"completed",JSONArray().put(
      JSONObject().put("id","paragraph-$index").put("sourceText","original").put("translatedText","translated").put("status","translated")
    ).toString(),expectedBlocks=1)
    val activity=DestinationActivity()
    reactContext.onHostResume(activity)
    val result=PromiseCapture()
    module.exportDocument("word-destination-job","docx",result.promise)
    assertTrue(activity.opened.await(10,TimeUnit.SECONDS))
    val intent=activity.openedIntent!!
    val mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.action)
    assertEquals(mime,intent.type)
    assertEquals("résumé-translated.docx",intent.getStringExtra(Intent.EXTRA_TITLE))
    assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
    assertEquals(Intent.FLAG_GRANT_WRITE_URI_PERMISSION,intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    val duplicate=PromiseCapture();module.exportDocument("word-destination-job","docx",duplicate.promise)
    assertEquals("EXPORT_BUSY",duplicate.awaitFailure().code)
    val destination=File(context.cacheDir,"chosen-word.docx")
    val authority="dev.kigya.translatepdf.test-word-output"
    val provider=TestPdfOutputProvider(destination)
    provider.attachInfo(context,ProviderInfo().apply {this.authority=authority})
    ShadowContentResolver.registerProviderInternal(authority,provider)
    module.onActivityResult(activity,activity.requestCode,Activity.RESULT_OK,Intent().setData(Uri.parse("content://$authority/result.docx")))
    val saved=JSONObject(result.awaitSuccess().toString())
    assertEquals("docx",saved.getString("format"));assertEquals(mime,saved.getString("mimeType"))
    assertEquals("translated Word",destination.readText())
    assertEquals("original",source.readText())
    val persisted=database.exportInfo("word-destination-job")!!
    assertEquals(mime,persisted.mimeType)
    // The editable result can be reused without invoking the native writer again.
    ShadowNativePdfCore.exportDocxCallback={_,_,_->error("A cached Word artifact must be reused")}
    activity.finish()
    val reopen=PromiseCapture();module.exportDocument("word-destination-job","docx",reopen.promise)
    assertEquals("EXPORT_UNAVAILABLE",reopen.awaitFailure().code)
    assertEquals("translated Word",File(persisted.path).readText())
    source.delete();destination.delete();File(persisted.path).delete()
  }

  @Test
  @Config(shadows = [ShadowNativePdfCore::class])
  fun wordExportRejectsWriterErrorsAndAbsentArtifactsWithoutOpeningTheDestination() {
    val source=File(context.filesDir,"documents/word-writer-error.docx").apply {parentFile?.mkdirs();writeText("original")}
    database.upsertDocument("word-writer-error","word.docx",source.absolutePath,1,"hash","docx",1)
    database.addJob("word-writer-error-job",jobInput("word-writer-error",ModelCatalog.RECOMMENDED_ID),1)
    database.updateJob("word-writer-error-job",status="completed")
    database.savePage("word-writer-error-job",0,"completed","[]",expectedBlocks=0)
    val activity=DestinationActivity();reactContext.onHostResume(activity)
    for(response in listOf("""{"error":"OOXML could not be written"}""","{}")) {
      ShadowNativePdfCore.exportDocxCallback={_,_,_->response}
      val result=PromiseCapture();module.exportDocument("word-writer-error-job","docx",result.promise)
      val failure=result.awaitFailure()
      assertEquals("EXPORT_FAILED",failure.code)
      assertTrue(failure.message.orEmpty().contains(if(response=="{}") "could not be prepared" else "OOXML could not be written"))
      assertNull(database.exportInfo("word-writer-error-job"))
      assertNull(activity.openedIntent)
      assertEquals("completed",database.jobJson("word-writer-error-job")!!.getString("status"))
      assertEquals("original",source.readText())
    }
    source.delete()
  }

  @Test
  @Config(shadows = [ShadowNativePdfCore::class])
  fun unsupportedAndDamagedWordImportsLeaveThePrivateLibraryUnchanged() {
    val bytes=byteArrayOf(80,75,3,4,0,1,2,3)
    val cases=listOf(
      Triple("old.doc","{}","Only modern Word"),
      Triple("macro.docm","{}","Only modern Word"),
      Triple("damaged.docx","""{"error":"Encrypted or damaged OOXML"}""","Encrypted or damaged OOXML"),
      Triple("empty.docx","""{"pageCount":0}""","no supported text segments"),
      Triple("oversized.docx","""{"pageCount":10001}""","supported document limit"),
    )
    for((index,item) in cases.withIndex()) {
      val (name,response,message)=item
      ShadowNativePdfCore.inspectDocxCallback={response}
      val input=File(context.cacheDir,"bad-word-$index").apply {writeBytes(bytes)}
      val authority="dev.kigya.translatepdf.test-invalid-word-$index"
      val provider=TestPdfOutputProvider(input,name)
      provider.attachInfo(context,ProviderInfo().apply {this.authority=authority})
      ShadowContentResolver.registerProviderInternal(authority,provider)
      val result=PromiseCapture()
      module.javaClass.getDeclaredField("pendingImport").apply {isAccessible=true}.set(module,result.promise)
      module.onActivityResult(Activity(),6101,Activity.RESULT_OK,Intent().setData(Uri.parse("content://$authority/source")))
      assertEquals("PDF_IMPORT_FAILED",result.awaitFailure().code)
      assertTrue(result.awaitFailure().message.orEmpty().contains(message))
      assertEquals(0,database.documentsJson().length())
      assertTrue(File(context.filesDir,"documents").listFiles().orEmpty().isEmpty())
      assertArrayEquals(bytes,input.readBytes());input.delete()
    }
  }

  private class DestinationActivity:Activity() {
    val opened=CountDownLatch(1)
    var openedIntent:Intent?=null
    var requestCode=-1
    override fun startActivityForResult(intent:Intent,requestCode:Int) {
      this.openedIntent=intent;this.requestCode=requestCode;opened.countDown()
    }
    override fun startActivity(intent: Intent) { openedIntent = intent }
  }

  @Test
  fun documentPickerAdvertisesBothFormatsAndCancellationDoesNotAddALibraryRow() {
    val activity=DestinationActivity();reactContext.onHostResume(activity)
    val result=PromiseCapture();module.pickDocument(result.promise)
    assertTrue(activity.opened.await(10,TimeUnit.SECONDS))
    val intent=activity.openedIntent!!
    assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.action)
    assertEquals("*/*",intent.type)
    assertEquals(listOf("application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
      intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.toList())
    assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
    module.onActivityResult(activity,activity.requestCode,Activity.RESULT_CANCELED,null)
    assertEquals("null",result.awaitSuccess())
    assertEquals(0,database.documentsJson().length())
  }

  @Test
  fun defaultModelDownloadAdapterQueuesAnExplicitForegroundServiceAndCanBePausedBeforeNetwork() {
    org.robolectric.shadows.ShadowStatFs.registerStats(context.filesDir.absolutePath,4096,2_000_000,2_000_000)
    val activity=Activity();reactContext.onHostResume(activity)
    val model=ModelCatalog.models.first()
    val downloader=ModelDownloader(context,database)
    var token:String?=null
    try {
      val result=PromiseCapture();module.startModelDownload(model.id,result.promise)
      assertEquals("downloading",JSONObject(result.awaitSuccess().toString()).getString("status"))
      val intent=Shadows.shadowOf(context.applicationContext as Application).nextStartedService
      assertEquals(PdfTranslationService.ACTION_START_MODEL,intent.action)
      assertEquals(model.id,intent.getStringExtra(PdfTranslationService.EXTRA_MODEL_ID))
      token=intent.getStringExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN)
      assertFalse(token.isNullOrBlank())
      assertTrue(downloader.hasActiveTransfer(model.id))
      val paused=PromiseCapture();module.pauseModelDownload(model.id,paused.promise)
      assertEquals("paused",JSONObject(paused.awaitSuccess().toString()).getString("status"))
      assertFalse(downloader.hasActiveTransfer(model.id))
      assertEquals(0L,downloader.partialLength(model.id))
    } finally {
      downloader.cancel(model.id)
      token?.let {downloader.releaseReservation(model.id,it)}
    }
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

    fun assertPending(message: String) {
      assertFalse(message, latch.await(100, TimeUnit.MILLISECONDS))
    }
  }

  private class PermissionTestActivity : Activity(), PermissionAwareActivity {
    var requestCode: Int = -1
    var listener: PermissionListener? = null
    var requestFailure: RuntimeException? = null

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_DENIED
    override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_DENIED
    override fun shouldShowRequestPermissionRationale(permission: String): Boolean = false
    override fun requestPermissions(permissions: Array<String>, requestCode: Int, listener: PermissionListener?) {
      requestFailure?.let { throw it }
      this.requestCode = requestCode
      this.listener = listener
    }
  }

  @Test
  fun bootstrapPreservesAnActiveModelReservationAsDownloading() {
    val model = ModelCatalog.models.first()
    val partial = File(context.filesDir, "models/${model.id}.part").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(2, 4, 6))
    }
    database.modelStatus(model.id, "downloading", 3)
    val downloader = module.javaClass.getDeclaredField("downloader").apply { isAccessible = true }
      .get(module) as ModelDownloader
    val token = downloader.reserve(model.id)
    try {
      val result = PromiseCapture()
      module.bootstrap(result.promise)

      val models = JSONObject(result.awaitSuccess().toString()).getJSONArray("models")
      val row = (0 until models.length()).map(models::getJSONObject).first { it.getString("id") == model.id }
      assertEquals("downloading", row.getString("status"))
      assertEquals(3L, row.getLong("downloadedBytes"))
      assertEquals(3L, row.getLong("partialBytes"))
      assertEquals(3L, row.getLong("storedBytes"))
      assertTrue(downloader.hasActiveTransfer(model.id))
      assertEquals(Triple("downloading", 3L, null), database.modelState(model.id))
      assertTrue(partial.isFile)
    } finally {
      downloader.releaseReservation(model.id, token)
      partial.delete()
    }
  }

  @Test
  fun lateDocumentPickerResultWithoutPendingPromiseDoesNotImportOrDisturbExport() {
    val bytes = "%PDF-1.7\nlate picker result".toByteArray()
    val source = File(context.cacheDir, "late-picker-source.pdf").apply { writeBytes(bytes) }
    val authority = "dev.kigya.translatepdf.test-late-picker"
    val provider = TestPdfOutputProvider(source, "late.pdf")
    provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
    ShadowContentResolver.registerProviderInternal(authority, provider)
    val pendingExport = PromiseCapture()
    module.javaClass.getDeclaredField("pendingExport").apply { isAccessible = true }.set(module, pendingExport.promise)

    module.onActivityResult(Activity(), 6101, Activity.RESULT_OK,
      Intent().setData(Uri.parse("content://$authority/source.pdf")))

    assertNull(module.javaClass.getDeclaredField("pendingImport").apply { isAccessible = true }.get(module))
    assertSame(pendingExport.promise, module.javaClass.getDeclaredField("pendingExport").apply { isAccessible = true }.get(module))
    pendingExport.assertPending("a late import callback must not settle an unrelated export")
    assertEquals(0, database.documentsJson().length())
    assertArrayEquals(bytes, source.readBytes())
    source.delete()
  }

  @Test
  fun originalThumbnailCacheEvictsOldestFilesBeforeReturningAValidCachedPage() {
    val source = File(context.cacheDir, "cache-prune-source.pdf").apply { writeText("synthetic source") }
    database.upsertDocument("cache-prune-doc", source.name, source.absolutePath, 1, "cache-prune-hash")
    val directory = File(context.cacheDir, "thumbnails").apply { mkdirs() }
    val oldest = File(directory, "u050-cache-oldest.bin").apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(30L * 1024L * 1024L) } }
    val newer = File(directory, "u050-cache-newer.bin").apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(20L * 1024L * 1024L) } }
    assertTrue(oldest.setLastModified(1L))
    assertTrue(newer.setLastModified(2L))
    val cachedPage = File(directory, "original_cache-prune-doc_0_320.png").apply {
      val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
      try {
        java.io.FileOutputStream(this).use { out ->
          assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out))
        }
      } finally { bitmap.recycle() }
    }
    try {
      val result = PromiseCapture()
      module.renderPage("cache-prune-doc", 0, 320, result.promise)

      assertTrue(result.awaitSuccess().toString().endsWith("original_cache-prune-doc_0_320.png"))
      assertFalse("cache trimming removes the oldest entry first", oldest.exists())
      assertTrue("newer cache entry is retained once the cache is within its limit", newer.isFile)
      assertTrue(cachedPage.isFile)
      assertTrue(directory.listFiles().orEmpty().sumOf(File::length) <= 48L * 1024L * 1024L)
    } finally {
      source.delete()
      oldest.delete()
      newer.delete()
      cachedPage.delete()
    }
  }

  @Test
  @Config(shadows = [ShadowNativePdfCore::class])
  fun pageRenderingConvertsMissingJniIntoStableNativeCoreRejection() {
    val source = File(context.cacheDir, "missing-jni-render-source.pdf").apply { writeText("synthetic source") }
    database.upsertDocument("missing-jni-render-doc", source.name, source.absolutePath, 1, "jni-render-hash")
    ShadowNativePdfCore.renderCallback = { throw UnsatisfiedLinkError("native renderer unavailable") }
    try {
      val result = PromiseCapture()
      module.renderPage("missing-jni-render-doc", 0, 320, result.promise)

      val failure = result.awaitFailure()
      assertEquals("NATIVE_CORE_UNAVAILABLE", failure.code)
      assertTrue(failure.message.orEmpty().contains("engine is unavailable"))
      assertNotNull(database.documentInfo("missing-jni-render-doc"))
      assertTrue(source.isFile)
    } finally {
      ShadowNativePdfCore.renderCallback = null
      source.delete()
    }
  }

  @Test
  fun deletingDocumentThroughPrivateSymlinkKeepsExternalSourceBytes() {
    val external = File(context.cacheDir, "u050-external-symlink-source.pdf").apply { writeText("user-owned source bytes") }
    val documents = File(context.filesDir, "documents").apply { mkdirs() }
    val alias = File(documents, "u050-symlink-import.pdf")
    java.nio.file.Files.createSymbolicLink(alias.toPath(), external.toPath())
    database.upsertDocument("u050-symlink-doc", alias.name, alias.absolutePath, 1, "symlink-source-hash")
    try {
      val result = PromiseCapture()
      module.deleteDocument("u050-symlink-doc", result.promise)

      assertNull(result.awaitSuccess())
      assertNull(database.documentInfo("u050-symlink-doc"))
      assertEquals("user-owned source bytes", external.readText())
      assertTrue("the symlink must not be followed to delete its external target", external.isFile)
    } finally {
      alias.delete()
      external.delete()
    }
  }

// PRIVATE STAGED MEMBERS for existing PdfTranslatorModuleTest.
// Root inserts these members inside that class; existing fixture/imports are reused.
// No production/private-field mutation. Deferred UI context controls only scheduling.

  private class DeferredModuleUiContext(base: Context) : StubReactApplicationContext(base) {
    private val pendingUi = java.util.ArrayDeque<Runnable>()
    override fun runOnUiQueueThread(runnable: Runnable) { pendingUi.addLast(runnable) }
    fun runNextUi() {
      check(pendingUi.isNotEmpty()) { "expected a queued platform UI operation" }
      pendingUi.removeFirst().run()
    }
    fun pendingUiCount(): Int = pendingUi.size
  }

  private fun deferModuleUi(): DeferredModuleUiContext {
    module.invalidate()
    database.close()
    val deferred = DeferredModuleUiContext(context)
    reactContext = deferred
    module = PdfTranslatorModule(deferred)
    database = moduleDatabase()
    return deferred
  }

  @Test
  fun queuedNotificationRequestsOpenOnePromptAndRejectTheSecondQueuedRequest() {
    Shadows.shadowOf(context.applicationContext as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val deferred = deferModuleUi()
    val activity = PermissionTestActivity()
    retainedHostActivity = activity
    reactContext.onHostResume(activity)
    val first = PromiseCapture()
    val second = PromiseCapture()

    module.requestNotificationPermission(first.promise)
    module.requestNotificationPermission(second.promise)
    assertEquals(2, deferred.pendingUiCount())
    assertNull(activity.listener)
    deferred.runNextUi()
    val listener = activity.listener!!
    first.assertPending("the first request remains pending until Android returns its permission result")
    deferred.runNextUi()

    assertEquals("NOTIFICATION_PERMISSION_BUSY", second.awaitFailure().code)
    assertSame("the duplicate must not replace the first permission callback", listener, activity.listener)
    assertTrue(database.setting("notification_permission_requested", "false").toBoolean())
    assertTrue(listener.onRequestPermissionsResult(activity.requestCode,
      arrayOf(Manifest.permission.POST_NOTIFICATIONS), intArrayOf(PackageManager.PERMISSION_DENIED)))
    assertEquals("denied", JSONObject(first.awaitSuccess().toString()).getString("status"))
    assertEquals(0, deferred.pendingUiCount())
  }

  @Test
  fun notificationGrantBetweenEnqueueAndUiExecutionAvoidsAnUnnecessaryPrompt() {
    val application = Shadows.shadowOf(context.applicationContext as Application)
    application.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val deferred = deferModuleUi()
    val activity = PermissionTestActivity()
    retainedHostActivity = activity
    reactContext.onHostResume(activity)
    val result = PromiseCapture()
    try {
      module.requestNotificationPermission(result.promise)
      assertEquals(1, deferred.pendingUiCount())
      application.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
      deferred.runNextUi()

      val state = JSONObject(result.awaitSuccess().toString())
      assertEquals("granted", state.getString("status"))
      assertFalse(state.getBoolean("canRequest"))
      assertFalse(state.getBoolean("requestedBefore"))
      assertNull(activity.listener)
      assertEquals("false", database.setting("notification_permission_requested", "false"))
    } finally {
      application.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  @Test
  fun notificationUiLosingItsHostRejectsWithoutConsumingTheFuturePermissionPrompt() {
    Shadows.shadowOf(context.applicationContext as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val deferred = deferModuleUi()
    val activity = PermissionTestActivity()
    retainedHostActivity = activity
    reactContext.onHostResume(activity)
    val abandoned = PromiseCapture()
    module.requestNotificationPermission(abandoned.promise)
    reactContext.onHostDestroy()
    assertNull(reactContext.currentActivity)
    deferred.runNextUi()

    assertEquals("NOTIFICATION_PERMISSION_UNAVAILABLE", abandoned.awaitFailure().code)
    assertEquals("false", database.setting("notification_permission_requested", "false"))
    assertNull(activity.listener)
    reactContext.onHostResume(activity)
    val retry = PromiseCapture()
    module.requestNotificationPermission(retry.promise)
    deferred.runNextUi()
    assertTrue(activity.listener!!.onRequestPermissionsResult(activity.requestCode,
      arrayOf(Manifest.permission.POST_NOTIFICATIONS), intArrayOf(PackageManager.PERMISSION_DENIED)))
    assertEquals("denied", JSONObject(retry.awaitSuccess().toString()).getString("status"))
  }

  @Test
  fun appWideNotificationBlockingOverridesGrantedRuntimePermissionWithoutPrompting() {
    val application = Shadows.shadowOf(context.applicationContext as Application)
    val manager = context.getSystemService(NotificationManager::class.java)
    val shadowManager = Shadows.shadowOf(manager)
    application.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    shadowManager.setNotificationsEnabled(false)
    val activity = PermissionTestActivity()
    retainedHostActivity = activity
    reactContext.onHostResume(activity)
    try {
      val result = PromiseCapture()
      module.requestNotificationPermission(result.promise)
      val state = JSONObject(result.awaitSuccess().toString())
      assertEquals("denied", state.getString("status"))
      assertFalse(state.getBoolean("canRequest"))
      assertFalse(state.getBoolean("requestedBefore"))
      assertNull(activity.listener)
      val snapshot = PromiseCapture()
      module.getActivitySnapshot(snapshot.promise)
      assertEquals("denied", JSONObject(snapshot.awaitSuccess().toString())
        .getJSONObject("notifications").getString("status"))
    } finally {
      shadowManager.setNotificationsEnabled(true)
      application.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  @Test
  fun jobDeletionPreservesExternalSymlinkTargetsAndUnrelatedPreviewCaches() {
    val jobId = "delete-alias-${UUID.randomUUID()}"
    val documentId = "$jobId-document"
    val externalSource = File(context.cacheDir, "$jobId-source.pdf").apply { writeText("user-owned source") }
    database.upsertDocument(documentId, externalSource.name, externalSource.absolutePath, 1, "source-hash")
    database.addJob(jobId, jobInput(documentId, ModelCatalog.RECOMMENDED_ID), 1)
    database.updateJob(jobId, status = "paused")
    // Existing fixture helper reads the real private export ID; no coordinator state is fabricated.
    val exportId = privateJobExportIdForTest(jobId)
    val aliases = listOf(
      File(context.filesDir, "exports/$exportId.pdf"),
      File(context.filesDir, "translated-docx/$jobId.docx"),
      File(context.cacheDir, "docx-preview-pdf/renderer-v5-translated-$jobId.pdf"),
    )
    val external = aliases.indices.map { index ->
      File(context.cacheDir, "$jobId-external-$index").apply { writeText("user-owned output $index") }
    }
    val thumbnails = File(context.cacheDir, "thumbnails").apply { mkdirs() }
    val ownedCaches = listOf("translated_${jobId}_0_128.png", "docx_translated_v5_${jobId}_0_128.png")
      .map { File(thumbnails, it).apply { writeText("private stale preview") } }
    val unrelated = listOf("translated_${jobId}-neighbor_0_128.png", "docx_translated_v5_${jobId}-neighbor_0_128.png")
      .map { File(thumbnails, it).apply { writeText("another job's preview") } }
    try {
      aliases.zip(external).forEach { (alias, target) ->
        alias.parentFile!!.mkdirs()
        java.nio.file.Files.createSymbolicLink(alias.toPath(), target.toPath())
      }
      database.saveExport(exportId, aliases[0].absolutePath, "translated.pdf")
      database.saveExport(jobId, aliases[1].absolutePath, "translated.docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
      database.saveExport("preview-pdf-$jobId", aliases[2].absolutePath, "preview.pdf")
      val result = PromiseCapture()
      module.deleteJob(jobId, result.promise)

      assertNull(result.awaitSuccess())
      assertNull(database.jobJson(jobId))
      assertNull(database.documentInfo(documentId))
      for (id in listOf(exportId, jobId, "preview-pdf-$jobId")) assertNull(database.exportInfo(id))
      external.forEachIndexed { index, file -> assertEquals("user-owned output $index", file.readText()) }
      assertEquals("user-owned source", externalSource.readText())
      ownedCaches.forEach { assertFalse("owned preview should be removed: $it", it.exists()) }
      unrelated.forEach { assertEquals("another job's preview", it.readText()) }
    } finally {
      aliases.forEach(File::delete)
      external.forEach(File::delete)
      ownedCaches.forEach(File::delete)
      unrelated.forEach(File::delete)
      externalSource.delete()
    }
  }

  @Test
  @Config(sdk = [26], application = Application::class)
  fun oldestSupportedAndroidModelDownloadDispatchReleasesItsReservationOnPause() {
    org.robolectric.shadows.ShadowStatFs.registerStats(context.filesDir.absolutePath, 4096, 2_000_000, 2_000_000)
    resumeHostActivity()
    val model = ModelCatalog.models.first()
    val downloader = ModelDownloader(context, database)
    var token: String? = null
    try {
      val result = PromiseCapture()
      module.startModelDownload(model.id, result.promise)
      assertEquals("downloading", JSONObject(result.awaitSuccess().toString()).getString("status"))
      val intent = Shadows.shadowOf(context.applicationContext as Application).nextStartedService
      assertEquals(PdfTranslationService.ACTION_START_MODEL, intent.action)
      assertEquals(model.id, intent.getStringExtra(PdfTranslationService.EXTRA_MODEL_ID))
      token = intent.getStringExtra(PdfTranslationService.EXTRA_MODEL_TRANSFER_TOKEN)
      assertFalse(token.isNullOrBlank())
      assertTrue(downloader.hasActiveTransfer(model.id))
      val paused = PromiseCapture()
      module.pauseModelDownload(model.id, paused.promise)
      assertEquals("paused", JSONObject(paused.awaitSuccess().toString()).getString("status"))
      assertFalse(downloader.hasActiveTransfer(model.id))
      assertEquals(PdfTranslationService.ACTION_PAUSE_MODEL,
        Shadows.shadowOf(context.applicationContext as Application).nextStartedService.action)
    } finally {
      downloader.cancel(model.id)
      token?.let { downloader.releaseReservation(model.id, it) }
    }
  }

  @Test
  fun openAndShareDispatchReadOnlyContentUrisAndPreserveTheirDurableFiles() {
    val id = "open-share-${UUID.randomUUID()}"
    val source = File(context.filesDir, "documents/$id.pdf").apply {
      parentFile!!.mkdirs(); writeText("%PDF-1.7 user source")
    }
    val exported = File(context.filesDir, "exports/$id.pdf").apply {
      parentFile!!.mkdirs(); writeText("%PDF-1.7 translated artifact")
    }
    database.upsertDocument(id, source.name, source.absolutePath, 1, "source-hash")
    database.saveExport(id, exported.absolutePath, "translated.pdf")
    val activity = DestinationActivity()
    retainedHostActivity = activity
    reactContext.onHostResume(activity)
    try {
      val opened = PromiseCapture()
      module.openPdf(id, opened.promise)
      assertNull(opened.awaitSuccess())
      val viewer = activity.openedIntent!!
      assertEquals(Intent.ACTION_VIEW, viewer.action)
      assertEquals("application/pdf", viewer.type)
      assertEquals("content", viewer.data!!.scheme)
      assertTrue(viewer.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
      assertEquals(0, viewer.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

      val shared = PromiseCapture()
      module.shareExport(id, shared.promise)
      assertNull(shared.awaitSuccess())
      val chooser = activity.openedIntent!!
      assertEquals(Intent.ACTION_CHOOSER, chooser.action)
      @Suppress("DEPRECATION")
      val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
      assertEquals(Intent.ACTION_SEND, send.action)
      assertEquals("application/pdf", send.type)
      @Suppress("DEPRECATION")
      val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
      assertEquals("content", uri.scheme)
      assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
      assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
      assertEquals("%PDF-1.7 user source", source.readText())
      assertEquals("%PDF-1.7 translated artifact", exported.readText())
      assertNotNull(database.exportInfo(id))
    } finally {
      source.delete()
      exported.delete()
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

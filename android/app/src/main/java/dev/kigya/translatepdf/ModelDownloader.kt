package dev.kigya.translatepdf

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

internal class ModelDownloadCanceled : IllegalStateException("Model download was canceled before completion.")

internal class ModelDownloader(
  context: Context,
  private val database: TranslatorDatabase,
  private val definitionLookup: (String) -> ModelDefinition = ModelCatalog::get,
  private val cancellationWaitMillis: Long = 50_000,
  private val urlForDefinition: (ModelDefinition) -> URL = { URL(it.url) },
) {
  private val appContext = context.applicationContext
  private val modelDirectory = File(appContext.filesDir, "models").apply { mkdirs() }
  // A module instance and the foreground service use separate downloader
  // objects. Transfers must still be visible to both so deletion can cancel
  // the service-owned socket and wait for its worker before unlinking files.
  private val active: ConcurrentHashMap<String, Transfer> = transfers

  fun fileFor(modelId: String): File = File(modelDirectory, "${definitionLookup(modelId).id}.gguf")
  fun partialLength(modelId: String): Long = File(modelDirectory, "${definitionLookup(modelId).id}.part").length()
  fun installedLength(modelId: String): Long = fileFor(modelId).length()
  fun storedLength(modelId: String): Long = installedLength(modelId) + partialLength(modelId)

  /** Process-local work includes reserved starts, before the service claims them. */
  fun hasActiveTransfer(modelId: String): Boolean {
    val transfer = active[modelId] ?: return false
    return synchronized(transfer) { !transfer.stopRequested }
  }

  fun isReady(modelId: String): Boolean {
    val definition = definitionLookup(modelId)
    val file = fileFor(modelId)
    val verified = file.isFile && file.length() == definition.bytes && sha256(file) == definition.sha256
    if (verified && !hasActiveTransfer(modelId)) {
      val state = database.modelState(modelId)
      // Older app versions may leave the app-managed `<id>.gguf` in place
      // without a matching ready row (for example, if the process stopped
      // after the atomic rename). Rehydrate readiness only after the full
      // pinned digest has been checked, and never while a transfer owns this
      // model. This method is called from worker-backed start/load paths, not
      // activity snapshot polling.
      if (state == null || state.first != "ready" || state.second != definition.bytes || state.third != null) {
        database.modelStatus(modelId, "ready", definition.bytes)
      }
    }
    return verified
  }

  fun isInstalled(modelId: String): Boolean {
    val state = database.modelState(modelId)
    val file = fileFor(modelId)
    return state?.first == "ready" && file.isFile && file.length() == definitionLookup(modelId).bytes
  }

  /** Reserve before enqueueing the foreground service so delete can tombstone queued starts. */
  fun reserve(modelId: String): String {
    definitionLookup(modelId)
    while (true) {
      val current=active[modelId]
      if (current != null) {
        synchronized(current) {
          if (current.stopRequested && !current.started) {
            active.remove(modelId,current)
            current.finished.countDown()
          } else {
            throw IllegalStateException("A model download is already queued or running.")
          }
        }
        continue
      }
      val transfer=Transfer(UUID.randomUUID().toString())
      if (active.putIfAbsent(modelId,transfer) == null) return transfer.token
    }
  }

  fun releaseReservation(modelId: String, token: String, beforeRelease: () -> Unit = {}): Boolean {
    val transfer=active[modelId] ?: return false
    synchronized(transfer) {
      if (transfer.token == token && !transfer.started) {
        transfer.stopRequested=true
        try {
          beforeRelease()
        } finally {
          active.remove(modelId,transfer)
          transfer.finished.countDown()
        }
        return true
      }
    }
    return false
  }

  fun <T> withReservation(modelId: String, token: String, enqueue: () -> T): T {
    val transfer=active[modelId]
    require(transfer != null && transfer.token == token) { "The queued model download was canceled." }
    synchronized(transfer) {
      require(!transfer.stopRequested && !transfer.started) { "The queued model download was canceled." }
      return enqueue()
    }
  }

  /** Claim a queued service command before it creates an executor or foreground notification. */
  fun claimServiceStart(modelId: String, token: String): Boolean {
    val transfer=active[modelId] ?: return false
    synchronized(transfer) {
      if (transfer.token != token || transfer.stopRequested || transfer.started) return false
      transfer.started=true
      return true
    }
  }

  /** Undo a claim only when service work could not be scheduled. */
  fun releaseServiceStart(modelId: String, token: String) {
    val transfer=active[modelId] ?: return
    synchronized(transfer) {
      if (transfer.token == token && !transfer.workerStarted) {
        if (transfer.stopRequested) {
          active.remove(modelId,transfer)
          transfer.finished.countDown()
        } else {
          transfer.started=false
        }
      }
    }
  }

  fun start(modelId: String, onProgress: (Long) -> Unit, onFinished: (Result<File>) -> Unit, downloadUrl: URL? = null, transferToken: String? = null) {
    val definition = definitionLookup(modelId)
    val transfer=if (transferToken != null) {
      val reserved=active[modelId]
      if (reserved == null || reserved.token != transferToken) {
        onFinished(Result.failure(ModelDownloadCanceled()))
        return
      }
      reserved
    } else {
      reserve(modelId)
      active[modelId] ?: run { onFinished(Result.failure(ModelDownloadCanceled()));return }
    }
    synchronized(transfer) {
      if (transfer.stopRequested) {
        active.remove(modelId,transfer)
        transfer.finished.countDown()
        onFinished(Result.failure(ModelDownloadCanceled()))
        return
      }
      if (transfer.workerStarted) return
      transfer.started=true
      transfer.workerStarted=true
    }
    Thread({
      val partial = File(modelDirectory, "${definition.id}.part")
      var connection: HttpURLConnection? = null
      try {
        if (isReady(modelId)) {
          database.modelStatus(modelId, "ready", definition.bytes)
          onFinished(Result.success(fileFor(modelId)))
          return@Thread
        }
        if (fileFor(modelId).exists()) fileFor(modelId).delete()
        var offset = partial.length().coerceAtMost(definition.bytes)
        if (offset >= definition.bytes) offset = 0
        if (offset == 0L) partial.delete()
        if (transfer.stopRequested) throw ModelDownloadCanceled()
        connection = (downloadUrl ?: urlForDefinition(definition)).openConnection() as HttpURLConnection
        transfer.connection = connection
        connection.apply {
          connectTimeout = 20_000
          readTimeout = 45_000
          instanceFollowRedirects = true
          setRequestProperty("User-Agent", "Folivela/${BuildConfig.VERSION_NAME}")
          if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        if (transfer.stopRequested) throw ModelDownloadCanceled()
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) throw IllegalStateException("Model download failed with HTTP $code.")
        val append = offset > 0L && code == HttpURLConnection.HTTP_PARTIAL
        if (!append && offset > 0L) {
          offset = 0L
          partial.delete()
        }
        var written = offset
        database.modelStatus(modelId, "downloading", written)
        connection.inputStream.use { input ->
          FileOutputStream(partial, append).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            try {
              while (true) {
                if (transfer.stopRequested) throw ModelDownloadCanceled()
                val read = input.read(buffer)
                if (read < 0) break
                written += read
                if (written > definition.bytes) throw IllegalStateException("Downloaded model is larger than its pinned size.")
                output.write(buffer, 0, read)
                if (written % (1024L * 1024L) < read) {
                  output.fd.sync()
                  database.modelStatus(modelId, "downloading", written)
                  onProgress(written)
                }
              }
            } finally {
              output.fd.sync()
            }
          }
        }
        if (written != definition.bytes) throw IllegalStateException("Downloaded model has an incomplete size.")
        database.modelStatus(modelId, "verifying", written)
        if (sha256(partial) != definition.sha256) {
          partial.delete()
          throw IllegalStateException("Downloaded model SHA-256 does not match the pinned revision.")
        }
        val final = fileFor(modelId)
        if (!partial.renameTo(final)) throw IllegalStateException("Verified model could not be committed atomically.")
        database.modelStatus(modelId, "ready", definition.bytes)
        onFinished(Result.success(final))
      } catch (_: ModelDownloadCanceled) {
        database.modelStatus(modelId, "paused", partial.length())
        onFinished(Result.failure(ModelDownloadCanceled()))
      } catch (error: Exception) {
        if (transfer.stopRequested) {
          database.modelStatus(modelId, "paused", partial.length())
          onFinished(Result.failure(ModelDownloadCanceled()))
        } else {
          database.modelStatus(modelId, "error", partial.length(), error.message ?: "Model download failed.")
          onFinished(Result.failure(error))
        }
      } finally {
        connection?.disconnect()
        active.remove(modelId, transfer)
        transfer.finished.countDown()
      }
    }, "model-download-$modelId").apply { isDaemon = true }.start()
  }

  fun pause(modelId: String) {
    active[modelId]?.let { transfer ->
      synchronized(transfer) {
        transfer.stopRequested = true
        transfer.connection?.disconnect()
        if (!transfer.started) transfer.finished.countDown()
      }
    }
  }

  /** Lifecycle cleanup may stop only its captured transfer, never a later retry. */
  fun pauseIfOwned(modelId: String, token: String) {
    val transfer = active[modelId] ?: return
    synchronized(transfer) {
      if (transfer.token != token) return
      transfer.stopRequested = true
      transfer.connection?.disconnect()
      if (!transfer.started) transfer.finished.countDown()
    }
  }

  fun cancel(modelId: String) {
    val transfer = active[modelId]
    transfer?.let { current -> synchronized(current) {
      current.stopRequested = true
      current.connection?.disconnect()
      if (!current.started) current.finished.countDown()
    } }
    if (transfer != null && !transfer.finished.await(cancellationWaitMillis, TimeUnit.MILLISECONDS)) {
      throw IllegalStateException("The model transfer has not stopped yet. Retry deletion after it pauses.")
    }
    val definition = definitionLookup(modelId)
    File(modelDirectory, "${definition.id}.part").delete()
    File(modelDirectory, "${definition.id}.gguf").delete()
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
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  internal class Transfer(val token: String) {
    @Volatile var stopRequested = false
    @Volatile var started = false
    @Volatile var workerStarted = false
    @Volatile var connection: HttpURLConnection? = null
    val finished = CountDownLatch(1)
  }
  private companion object {
    val transfers = ConcurrentHashMap<String, Transfer>()
  }
}

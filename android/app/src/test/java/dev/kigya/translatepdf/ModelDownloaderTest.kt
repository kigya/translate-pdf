package dev.kigya.translatepdf

import android.app.Application
import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class ModelDownloaderTest {
  private lateinit var context: Context
  private lateinit var database: TranslatorDatabase
  private lateinit var model: ModelDefinition
  private lateinit var downloader: ModelDownloader

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    database = TranslatorDatabase(context)
    val bytes = byteArrayOf(4, 9, 2, 6, 5, 3, 5, 8)
    model = ModelDefinition(
      id = "coverage-fixture",
      label = "Local coverage fixture",
      repo = "local/test",
      fileName = "fixture.gguf",
      revision = "test-revision",
      bytes = bytes.size.toLong(),
      sha256 = sha256(bytes),
      qualityBenchmarked = false,
      translationEnabled = false,
    )
    File(context.filesDir, "models").deleteRecursively()
    downloader = ModelDownloader(context, database, definitionLookup = { model })
  }

  @After
  fun tearDown() {
    runCatching { downloader.cancel(model.id) }
    downloader.fileFor(model.id).parentFile?.deleteRecursively()
    database.close()
  }

  @Test
  fun verifiesAndAtomicallyInstallsACompletePinnedResponse() {
    val bytes = byteArrayOf(4, 9, 2, 6, 5, 3, 5, 8)
    val server = LocalResponse(200, bytes)
    val result = AtomicReference<Result<File>>()
    val finished = CountDownLatch(1)

    downloader.start(model.id, onProgress = {}, onFinished = {
      result.set(it)
      finished.countDown()
    }, downloadUrl = URL("http://127.0.0.1:${server.port}/fixture"))

    try {
      assertTrue("download should finish", finished.await(5, TimeUnit.SECONDS))
      assertTrue("pinned response should install: ${result.get().exceptionOrNull()}", result.get().isSuccess)
      assertEquals(bytes.toList(), downloader.fileFor(model.id).readBytes().toList())
      assertEquals(Triple("ready", model.bytes, null), database.modelState(model.id))
      assertTrue("installed state requires the ready row and expected size", downloader.isInstalled(model.id))
      assertTrue("installed readiness requires the pinned digest", downloader.isReady(model.id))
      assertEquals(model.bytes, downloader.storedLength(model.id))
    } finally {
      server.close()
    }
  }

  @Test
  fun nullRequestOverrideUsesThePinnedModelUrlResolver() {
    val bytes = byteArrayOf(4, 9, 2, 6, 5, 3, 5, 8)
    val server = LocalResponse(200, bytes)
    val localUrl = URL("http://127.0.0.1:${server.port}/pinned-definition")
    val resolverCalls = java.util.concurrent.atomic.AtomicInteger()
    val fallbackDownloader = ModelDownloader(context, database,
      definitionLookup = { model }, urlForDefinition = { definition ->
        assertEquals(model.id, definition.id)
        resolverCalls.incrementAndGet()
        localUrl
      })
    val finished = CountDownLatch(1)
    val result = AtomicReference<Result<File>>()

    // The ordinary test server handler consumes the request; the resolver is
    // the only way this transfer can reach it because no per-call URL is given.
    fallbackDownloader.start(model.id, onProgress = {}, onFinished = {
      result.set(it)
      finished.countDown()
    }, downloadUrl = null)

    try {
      assertTrue("the resolver-selected response should finish", finished.await(5, TimeUnit.SECONDS))
      assertTrue("the pinned local fixture response should verify: ${result.get().exceptionOrNull()}", result.get().isSuccess)
      assertEquals(1, resolverCalls.get())
      assertEquals(bytes.toList(), fallbackDownloader.fileFor(model.id).readBytes().toList())
      assertTrue(fallbackDownloader.isReady(model.id))
      assertEquals(Triple("ready", model.bytes, null), database.modelState(model.id))
    } finally {
      server.close()
    }
  }

  @Test
  fun resumesDurablePartialOnlyWhenServerReturnsPartialContent() {
    val bytes = byteArrayOf(4, 9, 2, 6, 5, 3, 5, 8)
    val partial = downloader.fileFor(model.id).resolveSibling("${model.id}.part")
    partial.writeBytes(bytes.copyOfRange(0, 3))
    val receivedRange = AtomicReference<String?>()
    val server = LocalResponse(206, bytes.copyOfRange(3, bytes.size)) { lines ->
      receivedRange.set(lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim())
    }
    val result = AtomicReference<Result<File>>()
    val finished = CountDownLatch(1)

    downloader.start(model.id, onProgress = {}, onFinished = {
      result.set(it)
      finished.countDown()
    }, downloadUrl = URL("http://127.0.0.1:${server.port}/fixture"))

    try {
      assertTrue("resume should finish", finished.await(5, TimeUnit.SECONDS))
      assertTrue("resume response should verify", result.get().isSuccess)
      assertEquals("bytes=3-", receivedRange.get())
      assertEquals(bytes.toList(), downloader.fileFor(model.id).readBytes().toList())
      assertFalse("successful commit removes the partial", partial.exists())
      assertEquals(Triple("ready", model.bytes, null), database.modelState(model.id))
    } finally {
      server.close()
    }
  }

  @Test
  fun checksumMismatchDeletesUntrustedPartialAndLeavesModelInError() {
    val response = byteArrayOf(4, 9, 2, 6, 5, 3, 5, 7)
    val partial = downloader.fileFor(model.id).resolveSibling("${model.id}.part")
    val server = LocalResponse(200, response)
    val result = AtomicReference<Result<File>>()
    val finished = CountDownLatch(1)

    downloader.start(model.id, onProgress = {}, onFinished = {
      result.set(it)
      finished.countDown()
    }, downloadUrl = URL("http://127.0.0.1:${server.port}/fixture"))

    try {
      assertTrue("checksum verification should finish", finished.await(5, TimeUnit.SECONDS))
      assertEquals("Downloaded model SHA-256 does not match the pinned revision.", result.get().exceptionOrNull()?.message)
      assertFalse("untrusted bytes must not be installed", downloader.fileFor(model.id).exists())
      assertFalse("untrusted bytes must not remain resumable", partial.exists())
      assertEquals(Triple("error", 0L, "Downloaded model SHA-256 does not match the pinned revision."), database.modelState(model.id))
      assertFalse("incorrect digest is never ready", downloader.isReady(model.id))
    } finally {
      server.close()
    }
  }

  @Test
  fun rejectsNonSuccessHttpResponseWithoutPersistingDownloadedBytes() {
    val server = LocalResponse(503, byteArrayOf(1, 2, 3))
    val result = AtomicReference<Result<File>>()
    val finished = CountDownLatch(1)

    downloader.start(model.id, onProgress = {}, onFinished = {
      result.set(it)
      finished.countDown()
    }, downloadUrl = URL("http://127.0.0.1:${server.port}/fixture"))

    try {
      assertTrue("HTTP error should finish", finished.await(5, TimeUnit.SECONDS))
      assertEquals("Model download failed with HTTP 503.", result.get().exceptionOrNull()?.message)
      assertEquals(Triple("error", 0L, "Model download failed with HTTP 503."), database.modelState(model.id))
      assertFalse(downloader.fileFor(model.id).exists())
      assertFalse(downloader.fileFor(model.id).resolveSibling("${model.id}.part").exists())
    } finally {
      server.close()
    }
  }

  @Test
  fun rejectsStatusBelowSuccessRangeEvenWhenConnectionReturnsInformationalCode() {
    val disconnected = CountDownLatch(1)
    val informationalStatusUrl = URL(null, "coverage://informational-status", object : URLStreamHandler() {
      override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() { disconnected.countDown() }
        override fun usingProxy() = false
        override fun getResponseCode() = 199
      }
    })
    val result = AtomicReference<Result<File>>()
    val finished = CountDownLatch(1)

    downloader.start(model.id, onProgress = {}, onFinished = {
      result.set(it)
      finished.countDown()
    }, downloadUrl = informationalStatusUrl)

    assertTrue("invalid HTTP status should finish", finished.await(5, TimeUnit.SECONDS))
    assertEquals("Model download failed with HTTP 199.", result.get().exceptionOrNull()?.message)
    assertEquals(Triple("error", 0L, "Model download failed with HTTP 199."), database.modelState(model.id))
    assertTrue("the rejected response must be disconnected", disconnected.await(5,TimeUnit.SECONDS))
    assertFalse(downloader.fileFor(model.id).exists())
    assertFalse(downloader.fileFor(model.id).resolveSibling("${model.id}.part").exists())
  }

  @Test
  fun rejectsOversizedResponseAndDoesNotWriteTheOverflowChunk() {
    val oversized = byteArrayOf(4, 9, 2, 6, 5, 3, 5, 8)
    model = model.copy(bytes = 4)
    val server = LocalResponse(200, oversized)
    val result = AtomicReference<Result<File>>()
    val finished = CountDownLatch(1)

    downloader.start(model.id, onProgress = {}, onFinished = {
      result.set(it)
      finished.countDown()
    }, downloadUrl = URL("http://127.0.0.1:${server.port}/fixture"))

    try {
      assertTrue("oversize check should finish", finished.await(5, TimeUnit.SECONDS))
      assertEquals("Downloaded model is larger than its pinned size.", result.get().exceptionOrNull()?.message)
      assertEquals(0L, downloader.partialLength(model.id))
      assertEquals(Triple("error", 0L, "Downloaded model is larger than its pinned size."), database.modelState(model.id))
      assertFalse(downloader.isInstalled(model.id))
    } finally {
      server.close()
    }
  }

  @Test
  fun verifiedResponseRemainsResumableWhenAtomicCommitIsBlocked() {
    val bytes = byteArrayOf(4, 9, 2, 6, 5, 3, 5, 8)
    val installedPath = downloader.fileFor(model.id)
    installedPath.mkdirs()
    File(installedPath, "keep.txt").writeText("block replacement")
    val partial = installedPath.resolveSibling("${model.id}.part")
    val server = LocalResponse(200, bytes)
    val result = AtomicReference<Result<File>>()
    val finished = CountDownLatch(1)

    downloader.start(model.id, onProgress = {}, onFinished = {
      result.set(it)
      finished.countDown()
    }, downloadUrl = URL("http://127.0.0.1:${server.port}/fixture"))

    try {
      assertTrue("commit failure should finish", finished.await(5, TimeUnit.SECONDS))
      assertEquals("Verified model could not be committed atomically.", result.get().exceptionOrNull()?.message)
      assertTrue("verified source bytes remain available for recovery", partial.isFile)
      assertEquals(bytes.toList(), partial.readBytes().toList())
      assertEquals(Triple("error", model.bytes, "Verified model could not be committed atomically."), database.modelState(model.id))
      assertFalse("directory collision cannot be considered an installed model", downloader.isInstalled(model.id))
    } finally {
      server.close()
    }
  }

  @Test
  fun installedStateRequiresReadyDatabaseRowAndExactFileLength() {
    val installed = downloader.fileFor(model.id)
    val bytes = byteArrayOf(4, 9, 2, 6, 5, 3, 5, 8)
    installed.writeBytes(bytes)
    assertFalse("a file without a ready row is not installed", downloader.isInstalled(model.id))

    database.modelStatus(model.id, "ready", model.bytes)
    assertTrue(downloader.isInstalled(model.id))
    installed.appendBytes(byteArrayOf(1))
    assertFalse("a ready row with the wrong length is stale", downloader.isInstalled(model.id))
    installed.writeBytes(bytes)
    installed.delete()
    assertFalse("a ready database row cannot make a missing file installed", downloader.isInstalled(model.id))
    assertNull("the file check must not rewrite the captured database state", database.modelState(model.id)?.third)
  }

  @Test
  fun claimedAndPausedServiceHandoffRemainsBusyUntilItsClaimIsReleased() {
    val first = downloader.reserve(model.id)
    assertTrue(downloader.claimServiceStart(model.id, first))
    downloader.pause(model.id)

    assertTrue("a claimed service handoff must not be replaced while cancellation is settling",
      runCatching { downloader.reserve(model.id) }.exceptionOrNull() is IllegalStateException)

    downloader.releaseServiceStart(model.id, first)
    val retry = downloader.reserve(model.id)
    assertTrue(downloader.releaseReservation(model.id, retry))
  }

  @Test
  fun staleServiceCleanupAfterReservationRemovalIsAnIdempotentNoOp() {
    val token = downloader.reserve(model.id)
    assertTrue(downloader.releaseReservation(model.id, token))

    downloader.releaseServiceStart(model.id, token)

    assertFalse(downloader.hasActiveTransfer(model.id))
    assertFalse(downloader.claimServiceStart(model.id, token))
    var enqueueRan = false
    assertTrue(runCatching {
      downloader.withReservation(model.id, token) { enqueueRan = true }
    }.isFailure)
    assertFalse(enqueueRan)
  }

  @Test
  fun pausedReservationCanBeReplacedAndReleaseCallbackFailureStillCleansItUp() {
    val stale = downloader.reserve(model.id)
    downloader.pause(model.id)
    var enqueueAfterPause = false
    assertTrue("a paused queue intent cannot be enqueued", runCatching {
      downloader.withReservation(model.id, stale) { enqueueAfterPause = true }
    }.isFailure)
    assertFalse(enqueueAfterPause)
    assertFalse("a paused reservation cannot be claimed by a service", downloader.claimServiceStart(model.id, stale))
    val current = downloader.reserve(model.id)
    assertFalse("a replaced reservation cannot release the new one", downloader.releaseReservation(model.id, stale))
    assertTrue(downloader.hasActiveTransfer(model.id))
    assertTrue("current reservation is removed even if service cleanup fails", runCatching {
      downloader.releaseReservation(model.id, current) { throw IllegalStateException("service unavailable") }
    }.isFailure)
    assertFalse(downloader.hasActiveTransfer(model.id))
    val retry = downloader.reserve(model.id)
    assertTrue(downloader.releaseReservation(model.id, retry))
  }

  @Test
  fun cancelReportsRetryableTimeoutWhenClaimedWorkerHasNotFinished() {
    val fastTimeoutDownloader = ModelDownloader(context, database, definitionLookup = { model }, cancellationWaitMillis = 0)
    val token = fastTimeoutDownloader.reserve(model.id)
    assertTrue(fastTimeoutDownloader.claimServiceStart(model.id, token))

    assertEquals(
      "The model transfer has not stopped yet. Retry deletion after it pauses.",
      runCatching { fastTimeoutDownloader.cancel(model.id) }.exceptionOrNull()?.message,
    )

    fastTimeoutDownloader.releaseServiceStart(model.id, token)
    assertFalse(fastTimeoutDownloader.hasActiveTransfer(model.id))
  }

  @Test
  fun staleTransferTokenFailsWithoutOpeningTheNetwork() {
    val result = AtomicReference<Result<File>>()
    val downloaderToken = downloader.reserve(model.id)
    assertTrue(downloader.releaseReservation(model.id, downloaderToken))

    downloader.start(model.id, onProgress = {}, onFinished = { result.set(it) },
      downloadUrl = URL("http://127.0.0.1:1/never"), transferToken = downloaderToken)

    assertTrue(result.get().isFailure)
    assertTrue(result.get().exceptionOrNull() is ModelDownloadCanceled)
    assertFalse(downloader.fileFor(model.id).exists())
  }

  @Test
  fun reservationRunsOnlyForTheCurrentToken() {
    val token = downloader.reserve(model.id)
    val queued = downloader.withReservation(model.id, token) { "queued" }

    assertEquals("queued", queued)
    assertTrue(
      "a stale command must not enqueue work behind the current reservation",
      runCatching { downloader.withReservation(model.id, "stale-token") { "wrong" } }.isFailure,
    )
    assertTrue(downloader.hasActiveTransfer(model.id))
    assertTrue(downloader.releaseReservation(model.id, token))
  }

  @Test
  fun simultaneousReservationAttemptsPublishExactlyOneTransfer() {
    val workers = 12
    val ready = CountDownLatch(workers)
    val startTogether = CountDownLatch(1)
    val finished = CountDownLatch(workers)
    val tokens = java.util.concurrent.ConcurrentLinkedQueue<String>()
    val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    val pool = java.util.concurrent.Executors.newFixedThreadPool(workers)

    repeat(workers) {
      pool.execute {
        ready.countDown()
        try {
          runCatching {
            check(startTogether.await(5, TimeUnit.SECONDS)) { "all contenders should reach the start barrier" }
            downloader.reserve(model.id)
          }
            .onSuccess { tokens.add(it) }
            .onFailure { failures.add(it) }
        } finally {
          finished.countDown()
        }
      }
    }

    try {
      assertTrue("all reservation contenders should be ready", ready.await(5, TimeUnit.SECONDS))
      startTogether.countDown()
      assertTrue("reservation attempts should finish", finished.await(5, TimeUnit.SECONDS))
      assertEquals("only one concurrent caller may publish a reservation", 1, tokens.size)
      assertEquals("all remaining callers must receive the expected busy result", workers - 1, failures.size)
      assertTrue(failures.all { it is IllegalStateException && it.message == "A model download is already queued or running." })
      assertTrue(downloader.hasActiveTransfer(model.id))
      assertTrue(downloader.releaseReservation(model.id, tokens.single()))
      assertFalse(downloader.hasActiveTransfer(model.id))
    } finally {
      startTogether.countDown()
      pool.shutdownNow()
      pool.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun wrongTokensCannotEnqueueClaimOrStartOverTheCurrentReservation() {
    val currentToken = downloader.reserve(model.id)
    var enqueueRan = false
    downloader.releaseServiceStart(model.id, "missing-token")
    downloader.releaseServiceStart(model.id, "stale-token")
    assertTrue(runCatching {
      downloader.withReservation(model.id, "stale-token") { enqueueRan = true }
    }.isFailure)
    assertFalse(enqueueRan)
    assertFalse(downloader.claimServiceStart(model.id, "stale-token"))

    val callback = AtomicReference<Result<File>>()
    downloader.start(model.id, onProgress = {}, onFinished = { callback.set(it) },
      downloadUrl = URL("http://127.0.0.1:1/never"), transferToken = "stale-token")

    assertTrue(callback.get().isFailure)
    assertTrue(callback.get().exceptionOrNull() is ModelDownloadCanceled)
    assertTrue("rejecting a stale service token must leave the live reservation intact", downloader.hasActiveTransfer(model.id))
    assertTrue(downloader.releaseReservation(model.id, currentToken))
  }

  @Test
  fun duplicateStartCannotCreateASecondWorkerOrReleaseTheRunningReservation() {
    val server = ServerSocket(0)
    val requestReceived = CountDownLatch(1)
    val sendResponse = CountDownLatch(1)
    val serverThread = Thread {
      try {
        server.accept().use { socket ->
          socket.soTimeout = 5_000
          val request = socket.getInputStream().bufferedReader()
          while (request.readLine()?.isNotEmpty() == true) Unit
          requestReceived.countDown()
          if (sendResponse.await(5, TimeUnit.SECONDS)) {
            socket.getOutputStream().apply {
              write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
              flush()
            }
          }
        }
      } catch (_: Exception) {
        // The test closes the socket if the worker fails early.
      } finally { server.close() }
    }.apply { isDaemon = true; start() }
    val token = downloader.reserve(model.id)
    val callbackCount = java.util.concurrent.atomic.AtomicInteger()
    val firstFinished = CountDownLatch(1)
    val firstResult = AtomicReference<Result<File>>()

    try {
      downloader.start(model.id, onProgress = {}, onFinished = {
        callbackCount.incrementAndGet()
        firstResult.set(it)
        firstFinished.countDown()
      }, downloadUrl = URL("http://127.0.0.1:${server.localPort}/model"), transferToken = token)

      assertTrue("the first worker should reach the held response", requestReceived.await(5, TimeUnit.SECONDS))
      downloader.start(model.id, onProgress = {}, onFinished = { callbackCount.incrementAndGet() },
        downloadUrl = URL("http://127.0.0.1:${server.localPort}/duplicate"), transferToken = token)
      downloader.releaseServiceStart(model.id, token)

      assertEquals("the duplicate delivery must not finish a second worker", 0, callbackCount.get())
      assertFalse("a running worker cannot be released as a queued start", downloader.releaseReservation(model.id, token))
      assertTrue(downloader.hasActiveTransfer(model.id))
      sendResponse.countDown()
      assertTrue("the original worker should finish after the response is released", firstFinished.await(5, TimeUnit.SECONDS))
      assertEquals(1, callbackCount.get())
      assertTrue(firstResult.get().isFailure)
      assertFalse(downloader.hasActiveTransfer(model.id))
    } finally {
      sendResponse.countDown()
      server.close()
      serverThread.join(1_000)
    }
  }

  @Test
  fun connectionSetupFailureWithoutMessageUsesSafeFallbackAndReleasesTransfer() {
    val noMessageFailureUrl = URL(null, "coverage://unavailable", object : URLStreamHandler() {
      override fun openConnection(url: URL): URLConnection = throw IOException()
    })
    val finished = CountDownLatch(1)
    val result = AtomicReference<Result<File>>()

    downloader.start(model.id, onProgress = {}, onFinished = {
      result.set(it)
      finished.countDown()
    }, downloadUrl = noMessageFailureUrl)

    assertTrue("connection failure should be reported", finished.await(5, TimeUnit.SECONDS))
    assertTrue("callback preserves the underlying connection failure", result.get().exceptionOrNull() is IOException)
    assertNull("the platform exception may not include a message", result.get().exceptionOrNull()?.message)
    assertEquals(Triple("error", 0L, "Model download failed."), database.modelState(model.id))
    assertFalse("failed setup must remove the process-local reservation", downloader.hasActiveTransfer(model.id))
  }

  @Test
  fun pauseDuringConnectionConfigurationStopsBeforeConnectAndClosesSocket() {
    val disconnected = java.util.concurrent.atomic.AtomicBoolean(false)
    val cancelDuringRequestSetup = URL(null, "coverage://cancel-during-setup", object : URLStreamHandler() {
      override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
        override fun connect() { throw AssertionError("a paused transfer must not connect") }
        override fun disconnect() { disconnected.set(true) }
        override fun usingProxy() = false
        override fun setRequestProperty(key: String, value: String) {
          super.setRequestProperty(key, value)
          downloader.pause(model.id)
        }
      }
    })
    val finished = CountDownLatch(1)
    val result = AtomicReference<Result<File>>()

    downloader.start(model.id, onProgress = {}, onFinished = {
      result.set(it)
      finished.countDown()
    }, downloadUrl = cancelDuringRequestSetup)

    assertTrue("pause should finish the worker", finished.await(5, TimeUnit.SECONDS))
    assertTrue(result.get().exceptionOrNull() is ModelDownloadCanceled)
    assertTrue("pause must disconnect the opened connection", disconnected.get())
    assertEquals(Triple("paused", 0L, null), database.modelState(model.id))
    assertFalse(downloader.hasActiveTransfer(model.id))
  }

  @Test
  fun pauseAfterPartialNetworkReadKeepsDurableBytesAndReportsCanceled() {
    // Wait for the downloader's durable 1 MiB progress checkpoint before
    // pausing. A server-side flush alone does not prove the client consumed
    // those bytes; HttpURLConnection can still be blocked in read().
    val checkpointBytes = 1024L * 1024L
    model = model.copy(bytes = checkpointBytes + 5L)
    val server = ServerSocket(0)
    val requestReceived = CountDownLatch(1)
    val releaseServer = CountDownLatch(1)
    val serverThread = Thread {
      runCatching {
        server.accept().use { socket ->
          val request = socket.getInputStream().bufferedReader()
          while (request.readLine().orEmpty().isNotEmpty()) Unit
          val output = socket.getOutputStream()
          output.write("HTTP/1.1 200 OK\r\nContent-Length: ${checkpointBytes + 5L}\r\nConnection: close\r\n\r\n".toByteArray())
          output.write(ByteArray(checkpointBytes.toInt()) { 4 })
          output.flush()
          requestReceived.countDown()
          releaseServer.await()
          runCatching { output.write(byteArrayOf(6, 5, 3, 5, 8)); output.flush() }
        }
      }
    }.apply { isDaemon = true; start() }
    val result = AtomicReference<Result<File>>()
    val finished = CountDownLatch(1)
    val progressCheckpoint = CountDownLatch(1)
    val partial = downloader.fileFor(model.id).resolveSibling("${model.id}.part")

    try {
      downloader.start(model.id, onProgress = { downloaded ->
        if (downloaded >= checkpointBytes) progressCheckpoint.countDown()
      }, onFinished = {
        result.set(it)
        finished.countDown()
      }, downloadUrl = URL("http://127.0.0.1:${server.localPort}/model"))

      assertTrue("server should receive the download request", requestReceived.await(5, TimeUnit.SECONDS))
      assertTrue("client should durably checkpoint the partial response", progressCheckpoint.await(5, TimeUnit.SECONDS))
      downloader.pause(model.id)
      assertTrue("worker should finish after its socket is disconnected", finished.await(5, TimeUnit.SECONDS))
      assertTrue(result.get().exceptionOrNull() is ModelDownloadCanceled)
      assertEquals(Triple("paused", checkpointBytes, null), database.modelState(model.id))
      assertEquals(checkpointBytes, partial.length())
      assertTrue(partial.readBytes().all { it == 4.toByte() })
      assertFalse(downloader.fileFor(model.id).exists())
    } finally {
      releaseServer.countDown()
      server.close()
      serverThread.join(1_000)
    }
  }

  @Test
  fun fullLengthUnverifiedPartialRestartsFromZeroInsteadOfBeingTrusted() {
    val bytes = byteArrayOf(4, 9, 2, 6, 5, 3, 5, 8)
    val partial = downloader.fileFor(model.id).resolveSibling("${model.id}.part")
    partial.writeBytes(byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1))
    val receivedRange = AtomicReference<String?>()
    val server = LocalResponse(200, bytes) { lines ->
      receivedRange.set(lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) })
    }
    val result = AtomicReference<Result<File>>()
    val finished = CountDownLatch(1)

    downloader.start(model.id, onProgress = {}, onFinished = {
      result.set(it)
      finished.countDown()
    }, downloadUrl = URL("http://127.0.0.1:${server.port}/fixture"))

    try {
      assertTrue("fresh transfer should finish", finished.await(5, TimeUnit.SECONDS))
      assertTrue("fresh transfer should verify", result.get().isSuccess)
      assertNull("a complete partial is not trusted without digest verification", receivedRange.get())
      assertEquals(bytes.toList(), downloader.fileFor(model.id).readBytes().toList())
    } finally {
      server.close()
    }
  }

  @Test
  fun matchingInstalledDigestShortCircuitsNetworkAndRepairsPersistedStatus() {
    val bytes = byteArrayOf(4, 9, 2, 6, 5, 3, 5, 8)
    val installed = downloader.fileFor(model.id).apply { writeBytes(bytes) }
    database.modelStatus(model.id, "downloading", 3)
    val result = AtomicReference<Result<File>>()
    val finished = CountDownLatch(1)

    downloader.start(model.id, onProgress = {}, onFinished = {
      result.set(it)
      finished.countDown()
    }, downloadUrl = URL("http://127.0.0.1:1/never"))

    assertTrue("ready file should return without network", finished.await(5, TimeUnit.SECONDS))
    assertEquals(installed, result.get().getOrThrow())
    assertEquals(Triple("ready", model.bytes, null), database.modelState(model.id))
  }

  @Test
  fun readinessRequiresRegularFileExpectedLengthAndPinnedDigest() {
    val installed = downloader.fileFor(model.id)
    installed.writeBytes(byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1))
    assertFalse("same-size bytes with another digest are not ready", downloader.isReady(model.id))
    installed.writeBytes(byteArrayOf(4, 9, 2, 6))
    assertFalse("short files are not ready", downloader.isReady(model.id))
    installed.delete()
    assertFalse("missing files are not ready", downloader.isReady(model.id))
  }

  @Test
  fun claimedReservationCannotBeReleasedAsQueuedOrReclaimed() {
    val token = downloader.reserve(model.id)
    assertTrue(downloader.claimServiceStart(model.id, token))
    assertFalse("the service now owns this reservation", downloader.releaseReservation(model.id, token))
    assertFalse("a duplicate service command cannot claim a second worker", downloader.claimServiceStart(model.id, token))
    assertTrue("an active reservation cannot be replaced", runCatching { downloader.reserve(model.id) }.isFailure)
    assertTrue("a claimed reservation cannot enqueue again", runCatching {
      downloader.withReservation(model.id, token) { "unexpected enqueue" }
    }.isFailure)
    downloader.releaseServiceStart(model.id, token)
    assertTrue("failed scheduling releases the reservation for a service retry", downloader.claimServiceStart(model.id, token))
    downloader.releaseServiceStart(model.id, token)
    assertTrue(downloader.releaseReservation(model.id, token))
  }

  @Test
  fun pauseBeforeWorkerStartRejectsServiceDeliveryWithoutNetworkAccess() {
    val token = downloader.reserve(model.id)
    downloader.pause(model.id)
    val result = AtomicReference<Result<File>>()

    downloader.start(model.id, onProgress = {}, onFinished = { result.set(it) },
      downloadUrl = URL("http://127.0.0.1:1/never"), transferToken = token)

    assertTrue(result.get().isFailure)
    assertTrue(result.get().exceptionOrNull() is ModelDownloadCanceled)
    assertFalse(downloader.hasActiveTransfer(model.id))
    assertFalse(downloader.fileFor(model.id).exists())
  }

  @Test
  fun reportsDurableProgressAfterOneMiBBoundary() {
    val bytes = ByteArray(1024 * 1024 + 17) { (it % 127).toByte() }
    model = model.copy(bytes = bytes.size.toLong(), sha256 = sha256(bytes))
    val progress = mutableListOf<Long>()
    val server = LocalResponse(200, bytes)
    val result = AtomicReference<Result<File>>()
    val finished = CountDownLatch(1)

    downloader.start(model.id, onProgress = { progress.add(it) }, onFinished = {
      result.set(it)
      finished.countDown()
    }, downloadUrl = URL("http://127.0.0.1:${server.port}/fixture"))

    try {
      assertTrue("large fixture should finish", finished.await(5, TimeUnit.SECONDS))
      assertTrue(result.get().isSuccess)
      assertTrue("progress should be emitted after crossing one MiB", progress.isNotEmpty())
      assertEquals(1024L * 1024, progress.first())
      assertEquals(bytes.size.toLong(), downloader.installedLength(model.id))
    } finally {
      server.close()
    }
  }

  private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private class LocalResponse(
    private val status: Int,
    private val body: ByteArray,
    private val onRequest: (List<String>) -> Unit = {},
  ) : AutoCloseable {
    private val socket = ServerSocket(0)
    val port: Int get() = socket.localPort
    private val thread = Thread {
      runCatching {
        socket.accept().use { client ->
          val request = client.getInputStream().bufferedReader()
          val lines = mutableListOf<String>()
          while (true) {
            val line = request.readLine() ?: break
            if (line.isEmpty()) break
            lines.add(line)
          }
          onRequest(lines)
          client.getOutputStream().apply {
            write("HTTP/1.1 $status Test\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(body)
            flush()
          }
        }
      }
    }.apply { isDaemon = true; start() }

    override fun close() {
      socket.close()
      thread.join(1_000)
    }
  }
}

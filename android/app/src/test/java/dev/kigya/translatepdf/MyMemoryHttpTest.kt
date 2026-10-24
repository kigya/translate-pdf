package dev.kigya.translatepdf

import android.app.Application
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class MyMemoryHttpTest {
  private val success = """{"responseStatus":200,"responseData":{"translatedText":"Bonjour"}}"""
  private class Connection(val code: Int = 200, val body: InputStream? = null, val failure: Exception? = null) : HttpURLConnection(URL("https://api.mymemory.translated.net/get")) {
    var disconnected = false
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun connect() {}
    override fun getResponseCode(): Int { failure?.let { throw it }; return code }
    override fun getInputStream(): InputStream? = body
  }
  private fun translate(connection: Connection): Result<String> = runCatching {
    MyMemoryTranslator(openConnection = { connection }).translate("hello", "en", "fr")
  }

  @Test fun sendsAnEncodedHttpsGetWithTimeoutsAndNoRedirectFollowing() {
    val connection = Connection(body = ByteArrayInputStream(success.toByteArray()))
    var requested: URL? = null
    val translator = MyMemoryTranslator(openConnection = { requested = it; connection })
    assertEquals(" Bonjour ", translator.translate(" hello & world ", "en", "fr"))
    assertEquals("https", requested?.protocol)
    assertEquals("api.mymemory.translated.net", requested?.host)
    assertTrue(requested?.query?.contains("q=hello%20%26%20world") == true)
    assertTrue(requested?.query?.contains("langpair=en%7Cfr") == true)
    assertEquals("GET", connection.requestMethod)
    assertEquals(12000, connection.connectTimeout)
    assertEquals(18000, connection.readTimeout)
    assertEquals("application/json", connection.getRequestProperty("Accept"))
    assertFalse(connection.instanceFollowRedirects)
    assertTrue(connection.disconnected)
  }

  @Test fun mapsHttpAndTransportFailuresAndAlwaysDisconnects() {
    val cases = listOf(
      Connection(429) to MyMemoryFailure.QUOTA,
      Connection(302) to MyMemoryFailure.TEMPORARY,
      Connection(199) to MyMemoryFailure.TEMPORARY,
      Connection(503) to MyMemoryFailure.TEMPORARY,
      Connection(200) to MyMemoryFailure.TEMPORARY,
      Connection(failure = SocketTimeoutException()) to MyMemoryFailure.TEMPORARY,
      Connection(failure = IOException()) to MyMemoryFailure.OFFLINE,
      Connection(failure = IllegalStateException()) to MyMemoryFailure.TEMPORARY,
    )
    for ((connection, expected) in cases) {
      assertEquals(expected, (translate(connection).exceptionOrNull() as MyMemoryException).failure)
      assertTrue(connection.disconnected)
    }
  }

  @Test fun refusesAnOversizedStreamingBodyBeforeItIsAccumulated() {
    var bytesRead = 0
    val stream = object : InputStream() {
      override fun read(): Int { bytesRead++; return 'x'.code }
      override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        buffer.fill('x'.code.toByte(), offset, offset + length); bytesRead += length; return length
      }
    }
    val connection = Connection(body = stream)
    assertEquals(MyMemoryFailure.TEMPORARY, (translate(connection).exceptionOrNull() as MyMemoryException).failure)
    assertTrue(bytesRead <= 256 * 1024 + 8192)
    assertTrue(connection.disconnected)
  }

  @Test fun cancellationWhileConnectingStopsBeforeReadingResponse() {
    val connection = Connection(body = ByteArrayInputStream(success.toByteArray()))
    lateinit var translator: MyMemoryTranslator
    translator = MyMemoryTranslator(openConnection = { translator.cancel(); connection })
    assertTrue(runCatching { translator.translate("hello", "en", "fr") }.exceptionOrNull() is InterruptedException)
    assertTrue(connection.disconnected)
  }

  @Test fun cancellationDuringAnActiveReadDisconnectsAndStopsBeforeCheckpointing() {
    lateinit var translator: MyMemoryTranslator
    val saved=mutableListOf<String>()
    val stream=object : ByteArrayInputStream(success.toByteArray()) {
      override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        translator.cancel()
        return super.read(buffer, offset, length)
      }
    }
    val connection=Connection(body=stream)
    translator=MyMemoryTranslator(openConnection={connection})
    val result=runCatching { translator.translate("hello","en","fr",onFragmentSaved={_,_,text->saved+=text}) }
    assertTrue(result.exceptionOrNull() is InterruptedException)
    assertTrue(connection.disconnected)
    assertTrue(saved.isEmpty())
  }

  @Test fun factoryFailureBeforeConnectionAssignmentPausesWithoutAResponse() {
    val translator=MyMemoryTranslator(openConnection={throw IOException("offline")})
    val failure=runCatching {translator.translate("hello","en","fr")}.exceptionOrNull()
    assertEquals(MyMemoryFailure.OFFLINE,(failure as MyMemoryException).failure)
  }

  @Test fun anOlderRequestCleanupDoesNotHideTheNewerConnectionFromCancellation() = overlappingRequests(false)

  @Test fun anOlderFailedRequestCannotClearTheNewerActiveConnection() = overlappingRequests(true)

  private fun overlappingRequests(failOlder:Boolean) {
    val oldReading=CountDownLatch(1)
    val releaseOld=CountDownLatch(1)
    val newReading=CountDownLatch(1)
    val releaseNew=CountDownLatch(1)
    fun waitingStream(started:CountDownLatch,release:CountDownLatch,fail:Boolean=false)=object:ByteArrayInputStream(success.toByteArray()) {
      override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        started.countDown()
        check(release.await(10,TimeUnit.SECONDS))
        if(fail) throw IOException("Connection dropped while another request was active")
        return super.read(buffer,offset,length)
      }
    }
    val older=Connection(body=waitingStream(oldReading,releaseOld,failOlder))
    val newer=Connection(body=waitingStream(newReading,releaseNew))
    val calls=AtomicInteger()
    val translator=MyMemoryTranslator(openConnection={if(calls.getAndIncrement()==0)older else newer})
    val firstResult=AtomicReference<Result<String>>()
    val secondResult=AtomicReference<Result<String>>()
    val first=Thread {firstResult.set(runCatching {translator.translate("first","en","fr")})}
    val second=Thread {secondResult.set(runCatching {translator.translate("second","en","fr")})}
    try {
      first.start();assertTrue(oldReading.await(10,TimeUnit.SECONDS))
      second.start();assertTrue(newReading.await(10,TimeUnit.SECONDS))
      releaseOld.countDown();first.join(10000)
      if(failOlder) assertEquals(MyMemoryFailure.OFFLINE,(firstResult.get().exceptionOrNull() as MyMemoryException).failure)
      else assertEquals("Bonjour",firstResult.get().getOrThrow())
      assertTrue(older.disconnected)
      assertFalse(newer.disconnected)
      translator.cancel()
      assertTrue(newer.disconnected)
      releaseNew.countDown();second.join(10000)
      assertTrue(secondResult.get().exceptionOrNull() is InterruptedException)
    } finally {
      releaseOld.countDown();releaseNew.countDown()
      first.join(10000);second.join(10000)
    }
  }
}

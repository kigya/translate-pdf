package dev.kigya.translatepdf

import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

internal enum class MyMemoryFailure { OFFLINE, QUOTA, TEMPORARY }

internal class MyMemoryException(val failure: MyMemoryFailure) : IOException(
  when (failure) {
    MyMemoryFailure.OFFLINE -> "No network connection is available. The online job is paused; reconnect and resume it."
    MyMemoryFailure.QUOTA -> "MyMemory's daily free translation quota has been reached. The job is paused; resume it after the quota resets."
    MyMemoryFailure.TEMPORARY -> "MyMemory could not complete this fragment. The job is paused; try resuming later."
  },
)

/** Public MyMemory GET integration. Request text and URLs are intentionally never logged. */
internal class MyMemoryTranslator(
  private val openConnection: (URL) -> java.net.URLConnection = URL::openConnection,
  private val request: ((String, String, String) -> String)? = null,
) {
  private val cancelled = AtomicBoolean(false)
  @Volatile private var activeConnection: HttpURLConnection? = null

  fun cancel() {
    cancelled.set(true)
    activeConnection?.disconnect()
  }

  private fun checkCancelled() {
    if (cancelled.get()) throw InterruptedException("Online translation was canceled.")
  }

  fun translate(text: String, sourceLanguage: String, targetLanguage: String,
                savedFragments: org.json.JSONArray = org.json.JSONArray(),
                onFragmentSaved: (Int, String, String) -> Unit = { _, _, _ -> }): String {
    checkCancelled()
    LanguagePair.requireValid(sourceLanguage, targetLanguage)
    if (text.isBlank()) return text
    val fragments = chunks(text)
    val prior = (0 until savedFragments.length()).mapNotNull { savedFragments.optJSONObject(it) }
      .associateBy { it.optInt("index", -1) }
    return fragments.mapIndexed { index, fragment ->
      checkCancelled()
      if (fragment.isBlank()) return@mapIndexed fragment
      val cached = prior[index]
      if (cached != null && cached.optString("source") == fragment && cached.optString("translated").isNotEmpty())
        preserveSourceEdges(fragment, cached.getString("translated")).also { checkCancelled() }
      else {
        val requestText = fragment.trim()
        val response = request?.invoke(requestText, apiCode(sourceLanguage), apiCode(targetLanguage))
          ?: httpGet(requestText, apiCode(sourceLanguage), apiCode(targetLanguage))
        checkCancelled()
        val translated = preserveSourceEdges(fragment, response.let {
          parseResponse(it).getOrElse { failure -> throw failure }.getString("translatedText")
        })
        checkCancelled()
        onFragmentSaved(index, fragment, translated)
        checkCancelled()
        translated
      }
    }.joinToString("")
  }

  private fun preserveSourceEdges(source: String, translated: String): String {
    val prefix = source.takeWhile(Char::isWhitespace)
    val suffix = source.takeLastWhile(Char::isWhitespace)
    return prefix + translated.trim() + suffix
  }

  companion object {
    private const val MAX_UTF8_BYTES = 500
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val ENDPOINT = "https://api.mymemory.translated.net/get"

    fun apiCode(language: String): String {
      require(LanguagePair.isValidCode(language)) { "Choose two supported languages." }
      return if (language == "zh-Hans") "zh-CN" else language
    }

    /** Split without dropping whitespace, prefer sentence/word boundaries, and never split a surrogate pair. */
    fun chunks(text: String): List<String> {
      if (text.isEmpty()) return emptyList()
      val result = mutableListOf<String>()
      var start = 0
      while (start < text.length) {
        var end = start
        var lastPreferred = -1
        var byteCount = 0
        while (end < text.length) {
          val cp = text.codePointAt(end)
          val width = Character.charCount(cp)
          if (cp == '\n'.code || cp == '\r'.code) {
            if (end == start) {
              end += width
              if (cp == '\r'.code && text.getOrNull(end) == '\n') end++
            }
            break
          }
          val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
          if (byteCount + bytes > MAX_UTF8_BYTES) break
          end += width
          byteCount += bytes
          if (Character.isWhitespace(cp) || cp == '.'.code || cp == '!'.code || cp == '?'.code || cp == '。'.code || cp == '！'.code || cp == '？'.code) lastPreferred = end
        }
        // The first scalar always advances: UTF-8 needs at most four bytes,
        // below the fixed 500-byte limit; a leading newline advances above.
        val boundary = if (end < text.length && lastPreferred > start) lastPreferred else end
        result += text.substring(start, boundary)
        start = boundary
      }
      return result
    }

    fun parseResponse(body: String): Result<JSONObject> = runCatching {
      val json = JSONObject(body)
      val status = json.optInt("responseStatus", -1)
      val details = json.optString("responseDetails")
      if (status == 429 || json.optString("quotaFinished").equals("true", true) ||
        (details.contains("quota", ignoreCase = true) && details.contains("limit", ignoreCase = true)) ||
        details.contains("all available free translation requests", ignoreCase = true) || details.contains("daily request limit", ignoreCase = true)) {
        throw MyMemoryException(MyMemoryFailure.QUOTA)
      }
      if (status != 200) throw MyMemoryException(MyMemoryFailure.TEMPORARY)
      val providerText = json.optJSONObject("responseData")?.optString("translatedText").orEmpty()
      val text = decodeHtmlEntities(unwrapMyMemoryGroups(providerText))
      if (text.isBlank()) throw MyMemoryException(MyMemoryFailure.TEMPORARY)
      JSONObject().put("translatedText", text)
    }.recoverCatching { error ->
      if (error is MyMemoryException) throw error
      throw MyMemoryException(MyMemoryFailure.TEMPORARY)
    }

    private fun decodeHtmlEntities(value: String): String {
      val entity = Regex("&(?:amp|quot|apos|lt|gt|nbsp);|&#[0-9]{1,7};|&#x[0-9a-fA-F]{1,6};", RegexOption.IGNORE_CASE)
      return entity.replace(value) { match ->
        val token = match.value
        val codePoint = when (token.lowercase()) {
          "&amp;" -> '&'.code; "&quot;" -> '"'.code; "&apos;" -> '\''.code
          "&lt;" -> '<'.code; "&gt;" -> '>'.code; "&nbsp;" -> 0xA0
          // The regex bounds decimal references to 7 digits and hex to 6;
          // both fit Int. Invalid Unicode scalars remain literal below.
          else -> if (token.startsWith("&#x", ignoreCase = true)) token.substring(3, token.length - 1).toInt(16)
            else token.substring(2, token.length - 1).toInt(10)
        }
        if (!Character.isValidCodePoint(codePoint) || codePoint in 0xD800..0xDFFF) token
        else String(Character.toChars(codePoint))
      }
    }

    /** MyMemory may wrap aligned phrase chunks in numeric-id <g> tags; keep only their text. */
    private fun unwrapMyMemoryGroups(value: String): String =
      Regex("<g\\s+id=[\"'][0-9]+[\"']\\s*>([\\s\\S]*?)</g\\s*>", setOf(RegexOption.IGNORE_CASE))
        .replace(value) { match -> match.groupValues[1] }

  }

  private fun httpGet(fragment: String, source: String, target: String): String {
      checkCancelled()
      var connection: HttpURLConnection? = null
      try {
        val uri = Uri.parse(ENDPOINT).buildUpon()
          .appendQueryParameter("q", fragment)
          .appendQueryParameter("langpair", "$source|$target")
          .build()
        connection = (openConnection(URL(uri.toString())) as HttpURLConnection).apply {
          requestMethod = "GET"
          connectTimeout = 12_000
          readTimeout = 18_000
          setRequestProperty("Accept", "application/json")
          instanceFollowRedirects = false
        }
        activeConnection = connection
        checkCancelled()
        val code = connection.responseCode
        if (code == 429) throw MyMemoryException(MyMemoryFailure.QUOTA)
        if (code !in 200..299) throw MyMemoryException(MyMemoryFailure.TEMPORARY)
        val stream = connection.inputStream
        val body = stream?.use { input ->
          val bytes = java.io.ByteArrayOutputStream()
          val buffer = ByteArray(8192)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > MAX_RESPONSE_BYTES) throw MyMemoryException(MyMemoryFailure.TEMPORARY)
            bytes.write(buffer, 0, count)
          }
          bytes.toString("UTF-8")
        } ?: throw MyMemoryException(MyMemoryFailure.TEMPORARY)
        return body
      } catch (error: MyMemoryException) {
        throw error
      } catch (error: InterruptedException) {
        throw error
      } catch (_: SocketTimeoutException) {
        throw MyMemoryException(MyMemoryFailure.TEMPORARY)
      } catch (_: IOException) {
        throw MyMemoryException(MyMemoryFailure.OFFLINE)
      } catch (_: Exception) {
        throw MyMemoryException(MyMemoryFailure.TEMPORARY)
      } finally {
        connection?.disconnect()
        if (activeConnection === connection) activeConnection = null
      }
  }
}

package dev.kigya.translatepdf

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class MyMemoryTranslatorTest {
  @Test
  fun chunksUtf8TextAtSentenceBoundariesWithoutExceeding500Bytes() {
    val text = (1..25).joinToString(" ") { "Привет-$it. 文本-$it!" }
    val chunks = MyMemoryTranslator.chunks(text)
    assertTrue(chunks.size > 1)
    assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 500 })
    assertEquals(text, chunks.joinToString(""))
  }

  @Test
  fun chunksLongUnbrokenUnicodeOnlyBetweenCodePoints() {
    val text = "😀".repeat(400)
    val chunks = MyMemoryTranslator.chunks(text)
    assertTrue(chunks.size > 1)
    assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 500 })
    assertEquals(text, chunks.joinToString(""))
    assertTrue(chunks.none { it.contains('\uFFFD') })
  }

  @Test
  fun onlineRequestsNeverCombineParagraphsAndKeepOriginalSeparators() {
    val source = "First paragraph.\n\nВторой абзац."
    val requested = mutableListOf<String>()
    val translator = MyMemoryTranslator { fragment, _, _ ->
      requested += fragment
      """{"responseStatus":200,"responseData":{"translatedText":"x"}}"""
    }

    val result = translator.translate(source, "en", "ru")

    assertEquals(listOf("First paragraph.", "Второй абзац."), requested)
    assertEquals("x\n\nx", result)
  }

  @Test
  fun mapsSupportedLanguageIdsToMyMemoryCodes() {
    assertEquals("zh-CN", MyMemoryTranslator.apiCode("zh-Hans"))
    assertEquals("ru", MyMemoryTranslator.apiCode("ru"))
  }

  @Test
  fun rejectsQuotaAndMalformedResponsesWithSafeMessages() {
    val quota = MyMemoryTranslator.parseResponse("""{"responseStatus":429,"responseDetails":"MYMEMORY WARNING: YOUR DAILY REQUEST LIMIT HAS BEEN REACHED"}""")
    assertEquals(MyMemoryFailure.QUOTA, quota.exceptionOrNull()?.let { (it as MyMemoryException).failure })
    val malformed = MyMemoryTranslator.parseResponse("not-json")
    assertEquals(MyMemoryFailure.TEMPORARY, malformed.exceptionOrNull()?.let { (it as MyMemoryException).failure })
    val unavailable = MyMemoryTranslator.parseResponse("""{"responseStatus":503,"responseDetails":"maintenance"}""")
    assertEquals(MyMemoryFailure.TEMPORARY, unavailable.exceptionOrNull()?.let { (it as MyMemoryException).failure })
    val flaggedQuota = MyMemoryTranslator.parseResponse("""{"responseStatus":200,"quotaFinished":true}""")
    assertEquals(MyMemoryFailure.QUOTA, flaggedQuota.exceptionOrNull()?.let { (it as MyMemoryException).failure })
    val empty = MyMemoryTranslator.parseResponse("""{"responseStatus":200,"responseData":{"translatedText":"  "}}""")
    assertEquals(MyMemoryFailure.TEMPORARY, empty.exceptionOrNull()?.let { (it as MyMemoryException).failure })
  }

  @Test
  fun decodesApiHtmlEntitiesWithoutCorruptingUnicode() {
    val response = MyMemoryTranslator.parseResponse(
      """{"responseStatus":200,"responseData":{"translatedText":"Tom &amp; Jerry &#x1F600; &quot;дом&quot;"}}""",
    ).getOrThrow()
    assertEquals("Tom & Jerry 😀 \"дом\"", response.getString("translatedText"))
  }

  @Test
  fun unwrapsOnlyKnownNumericAlignmentGroupsFromProviderText() {
    val response = MyMemoryTranslator.parseResponse(
      """{"responseStatus":200,"responseData":{"translatedText":"<g id=\"2297\">25 </g><g id=\"2298\">Нм</g> <b>сохранить</b>"}}""",
    ).getOrThrow()

    assertEquals("25 Нм <b>сохранить</b>", response.getString("translatedText"))
  }

  @Test
  fun keepsEscapedNumericAndGenericAlignmentLookingTextLiteral() {
    val escaped = MyMemoryTranslator.parseResponse(
      """{"responseStatus":200,"responseData":{"translatedText":"&lt;g id=&quot;2297&quot;&gt;example&lt;/g&gt;"}}""",
    ).getOrThrow()
    val generic = MyMemoryTranslator.parseResponse(
      """{"responseStatus":200,"responseData":{"translatedText":"<g>example</g>"}}""",
    ).getOrThrow()

    assertEquals("<g id=\"2297\">example</g>", escaped.getString("translatedText"))
    assertEquals("<g>example</g>", generic.getString("translatedText"))
  }

  @Test
  fun resumesFromDurableSuccessfulFragmentsWithoutRepeatingTheirRequests() {
    val source = "a".repeat(500) + "b"
    val chunks = MyMemoryTranslator.chunks(source)
    assertEquals(2, chunks.size)
    val requests = mutableListOf<String>()
    val saves = mutableListOf<Pair<Int, String>>()
    val translator = MyMemoryTranslator { fragment, _, _ ->
      requests += fragment
      """{"responseStatus":200,"responseData":{"translatedText":"translated"}}"""
    }
    val saved = org.json.JSONArray().put(org.json.JSONObject()
      .put("index", 0).put("source", chunks[0]).put("translated", "first"))

    val result = translator.translate(source, "en", "ru", saved) { index, _, translated -> saves += index to translated }

    assertEquals(listOf(chunks[1]), requests)
    assertEquals(listOf(1 to "translated"), saves)
    assertEquals("firsttranslated", result)
  }

  @Test
  fun preservesWhitespaceAcrossChunksWhenProviderStripsQueryEdges() {
    val source = "a".repeat(500) + "  " + "b"
    val requested = mutableListOf<String>()
    val translator = MyMemoryTranslator { fragment, _, _ ->
      requested += fragment
      """{"responseStatus":200,"responseData":{"translatedText":"x"}}"""
    }

    val translated = translator.translate(source, "en", "ru")

    assertEquals(listOf("a".repeat(500), "b"), requested)
    assertEquals("x  x", translated)
  }

  @Test
  fun cancellationAfterCheckpointPreventsLaterFragmentRequests() {
    val requested = mutableListOf<String>()
    lateinit var translator: MyMemoryTranslator
    translator = MyMemoryTranslator { fragment, _, _ ->
      requested += fragment
      """{"responseStatus":200,"responseData":{"translatedText":"translated"}}"""
    }
    val saved = org.json.JSONArray()

    val result = runCatching {
      translator.translate("a".repeat(500) + "b", "en", "ru", onFragmentSaved = { index, source, translated ->
        saved.put(org.json.JSONObject().put("index", index).put("source", source).put("translated", translated))
        translator.cancel()
      })
    }

    assertTrue(result.exceptionOrNull() is InterruptedException)
    assertEquals(1, requested.size)
    assertEquals(1, saved.length())
  }

  @Test
  fun keepsWhitespaceOnlySourceLocalAndRejectsInvalidLanguageBeforeRequest() {
    var requests = 0
    val translator = MyMemoryTranslator { _, _, _ ->
      requests++
      """{"responseStatus":200,"responseData":{"translatedText":"translated"}}"""
    }

    assertEquals(" \r\n\t", translator.translate(" \r\n\t", "en", "ru"))
    val failure = runCatching { translator.translate("text", "unsupported", "ru") }.exceptionOrNull()

    assertTrue(failure is IllegalArgumentException)
    assertEquals(0, requests)
  }

  @Test
  fun persistenceCallbackFailureStopsBeforeRequestingAnotherFragment() {
    val requests = mutableListOf<String>()
    val source = "a".repeat(500) + "b"
    val translator = MyMemoryTranslator { fragment, _, _ ->
      requests += fragment
      """{"responseStatus":200,"responseData":{"translatedText":"translated"}}"""
    }

    val failure = runCatching {
      translator.translate(source, "en", "ru", onFragmentSaved = { _, _, _ -> error("checkpoint write failed") })
    }.exceptionOrNull()

    assertEquals("checkpoint write failed", failure?.message)
    assertEquals(listOf("a".repeat(500)), requests)
  }
}

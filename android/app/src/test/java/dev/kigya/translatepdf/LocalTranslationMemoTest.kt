package dev.kigya.translatepdf

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class LocalTranslationMemoTest {
  @Test fun aCommittedBatchResponseCanBeReusedWithoutAnotherNativeCall() {
    val memo = LocalTranslationMemo { _,_,_,_,_ -> error("A committed batch response must be reused") }
    assertNull(memo.cached("Warning", "", "", "en", "ru"))
    memo.remember("Warning", "", "", "en", "ru", "{\"text\":\"Внимание\",\"tokens\":9,\"seconds\":4}")
    val hit = JSONObject(memo.translate("Warning", "", "", "en", "ru"))
    assertEquals("Внимание", hit.getString("text"))
    assertEquals(0, hit.getInt("tokens"))
    assertEquals(0, hit.getInt("seconds"))
    assertTrue(hit.getBoolean("cacheHit"))
    assertNull(memo.cached("Warning", "", "other page", "en", "ru"))
  }

  @Test fun duplicateBatchResponsesDoNotConsumeTheByteBudgetTwice() {
    val memo = LocalTranslationMemo(maxBytes = 90) { _,_,_,_,_ -> error("Unexpected inference") }
    repeat(3) { memo.remember("a", "", "", "en", "ru", "{\"text\":\"A\"}") }
    memo.remember("b", "", "", "en", "ru", "{\"text\":\"B\"}")
    assertEquals("A", JSONObject(memo.cached("a", "", "", "en", "ru")!!).getString("text"))
    assertEquals("B", JSONObject(memo.cached("b", "", "", "en", "ru")!!).getString("text"))
    memo.remember("a", "", "", "en", "ru", "{\"error\":\"Canceled\"}")
    assertEquals("A", JSONObject(memo.cached("a", "", "", "en", "ru")!!).getString("text"))
  }

  @Test fun onlyExactSuccessfulRequestsAreReusedWithoutClaimingGeneratedTokens() {
    var calls=0
    val memo=LocalTranslationMemo { text,_,_,_,_ -> calls++;JSONObject().put("text", "Перевод $text").put("tokens", 8).put("seconds", 2).toString() }
    val first=memo.translate("Warning", "", "", "en", "ru")
    val reused=JSONObject(memo.translate("Warning", "", "", "en", "ru"))
    assertEquals(JSONObject(first).getString("text"),reused.getString("text"))
    assertEquals(1,calls);assertEquals(0,reused.getInt("tokens"));assertTrue(reused.getBoolean("cacheHit"))
    for(args in listOf(listOf("Warning","term","","en","ru"),listOf("Warning","","different","en","ru"),listOf("Warning","","","de","ru"),listOf("Warning","","","en","de")))
      memo.translate(args[0],args[1],args[2],args[3],args[4])
    assertEquals(5,calls)
    memo.clear();memo.translate("Warning", "", "", "en", "ru");assertEquals(6,calls)
  }

  @Test fun failuresAndEmptyTranslationsAreNeverReused() {
    for(result in listOf("{\"error\":\"interrupted\"}","{\"text\":\"\"}")) {
      var calls=0;val memo=LocalTranslationMemo { _,_,_,_,_->calls++;result }
      repeat(2) {memo.translate("a","","","en","ru")}
      assertEquals(2,calls)
    }
  }

  @Test fun entryBudgetEvictsTheLeastRecentlyUsedRequest() {
    var calls=0;val memo=LocalTranslationMemo(maxEntries=2) {text,_,_,_,_->calls++;JSONObject().put("text",text).toString()}
    for(text in listOf("a","b","a","c","b"))memo.translate(text,"","","en","ru")
    assertEquals(4,calls)
  }

  @Test fun byteBudgetIsBoundedAndOversizedResponsesRemainUncached() {
    var calls=0;val memo=LocalTranslationMemo(maxBytes=1) {text,_,_,_,_->calls++;JSONObject().put("text",text).toString()}
    repeat(2) {memo.translate("a","","","en","ru")};assertEquals(2,calls)
    val noEntries=LocalTranslationMemo(maxEntries=0) {text,_,_,_,_->calls++;JSONObject().put("text",text).toString()}
    repeat(2) {noEntries.translate("a","","","en","ru")};assertEquals(4,calls)
    val bounded=LocalTranslationMemo(maxBytes=80) {text,_,_,_,_->calls++;JSONObject().put("text",text).toString()}
    for(text in listOf("short-a","short-b","short-a"))bounded.translate(text,"","","en","ru")
    assertEquals(7,calls)
  }
}

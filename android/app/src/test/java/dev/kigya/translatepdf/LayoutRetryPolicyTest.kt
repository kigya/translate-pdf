package dev.kigya.translatepdf

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class LayoutRetryPolicyTest {
  @Test fun legacyRetryLimitGetsOneFreshBoundedPolicyWithoutLosingSavedTargetOrHistory() {
    val legacyHistory = JSONArray().put(JSONObject().put("attempt", 1).put("text", "First legacy candidate"))
    val block = JSONObject().put("translatedText", "Durable legacy target")
      .put("layoutRetryCount", 2).put("layoutRetryHistory", legacyHistory)
    assertTrue(LayoutRetryPolicy.hasRemaining(block))

    val first = LayoutRetryPolicy.beginAttempt(block, JSONObject().put("estimatedMaxCharacters", 17))

    assertEquals(LayoutRetryPolicy.Attempt(1, 17), first)
    assertEquals(LayoutRetryPolicy.VERSION, block.getInt("layoutRetryPolicyVersion"))
    assertEquals(2, block.getInt("layoutRetryLegacyCount"))
    assertEquals(1, block.getInt("layoutRetryCount"))
    assertEquals("Durable legacy target", block.getString("translatedText"))
    assertEquals(1, block.getJSONArray("layoutRetryHistory").length())

    block.put("translatedText", "New policy target")
    LayoutRetryPolicy.appendHistory(block, first!!, "Durable legacy target", "New policy target", null, 0.75)
    LayoutRetryPolicy.recordFitFailure(block, "new-policy fit failure", null)
    assertFalse(block.getJSONArray("layoutRetryHistory").getJSONObject(0).has("fitError"))
    assertEquals("new-policy fit failure", block.getJSONArray("layoutRetryHistory").getJSONObject(1).getString("fitError"))

    assertEquals(LayoutRetryPolicy.Attempt(2, 0), LayoutRetryPolicy.beginAttempt(block, null))
    assertFalse(LayoutRetryPolicy.hasRemaining(block))
    assertNull(LayoutRetryPolicy.beginAttempt(block, null))
    assertEquals(2, block.getInt("layoutRetryLegacyCount"))
  }

  @Test fun nativeOptionsAreBoundedAndFeedbackBudgetIsAdvisory() {
    val block = JSONObject().put("layoutRetryPolicyVersion", LayoutRetryPolicy.VERSION)
    val first = LayoutRetryPolicy.beginAttempt(block, JSONObject().put("estimatedMaxCharacters", 20_000))!!
    assertEquals(10_000, first.maxCharacters)
    val options = JSONObject(LayoutRetryPolicy.nativeOptions(first))
    assertEquals(1, options.getInt("attempt"))
    assertEquals(10_000, options.getInt("maxCharacters"))

    val second = LayoutRetryPolicy.beginAttempt(block, JSONObject().put("estimatedMaxCharacters", -8))!!
    assertEquals(2, second.number)
    assertEquals(0, second.maxCharacters)
    assertTrue(runCatching { LayoutRetryPolicy.nativeOptions(LayoutRetryPolicy.Attempt(3, 0)) }.isFailure)
    assertTrue(runCatching { LayoutRetryPolicy.nativeOptions(LayoutRetryPolicy.Attempt(0, 0)) }.isFailure)
    assertTrue(runCatching { LayoutRetryPolicy.nativeOptions(LayoutRetryPolicy.Attempt(1, 10_001)) }.isFailure)
    assertTrue(runCatching { LayoutRetryPolicy.nativeOptions(LayoutRetryPolicy.Attempt(1, -1)) }.isFailure)
  }

  @Test fun corruptVersionTwoCounterCannotReopenOrCreateAnInvalidAttempt() {
    val block = JSONObject().put("layoutRetryPolicyVersion", LayoutRetryPolicy.VERSION).put("layoutRetryCount", -1)

    assertFalse(LayoutRetryPolicy.hasRemaining(block))
    assertNull(LayoutRetryPolicy.beginAttempt(block, null))
    assertEquals(-1, block.getInt("layoutRetryCount"))
  }

  @Test fun legacyMigrationDoesNotReplaceAnExistingLegacyCountAndClampsNegativeOldCount() {
    val alreadyMigrated = JSONObject().put("layoutRetryCount", 1).put("layoutRetryLegacyCount", 9)
    val attempt = LayoutRetryPolicy.beginAttempt(alreadyMigrated, null)!!
    assertEquals(9, alreadyMigrated.getInt("layoutRetryLegacyCount"))
    assertEquals(1, attempt.number)

    val negativeLegacy = JSONObject().put("layoutRetryCount", -2)
    assertEquals(1, LayoutRetryPolicy.beginAttempt(negativeLegacy, null)!!.number)
    assertEquals(LayoutRetryPolicy.VERSION, negativeLegacy.getInt("layoutRetryPolicyVersion"))
    assertFalse(negativeLegacy.has("layoutRetryLegacyCount"))
  }

  @Test fun invalidFeedbackValuesDefaultToNoCharacterBudget() {
    val block = JSONObject()
    assertEquals(0, LayoutRetryPolicy.beginAttempt(block, JSONObject().put("estimatedMaxCharacters", "28"))!!.maxCharacters)
    assertEquals(0, LayoutRetryPolicy.beginAttempt(JSONObject(), JSONObject().put("estimatedMaxCharacters", JSONObject.NULL))!!.maxCharacters)
    assertEquals(0, LayoutRetryPolicy.beginAttempt(JSONObject(), null)!!.maxCharacters)
  }

  @Test fun historyCapturesOutputTimingAndAssociatesTheFollowingFitRejection() {
    val block = JSONObject().put("translatedText", "Руководство")
    val attempt = LayoutRetryPolicy.beginAttempt(block, JSONObject().put("estimatedMaxCharacters", 22))!!
    LayoutRetryPolicy.appendHistory(block, attempt, "Длинный исходный вариант", "Руководство", null, 1.25)
    val feedback = JSONObject().put("blockId", "p0-o1").put("estimatedMaxCharacters", 18)

    LayoutRetryPolicy.recordFitFailure(block, "unfit p0-o1", feedback)

    val record = block.getJSONArray("layoutRetryHistory").getJSONObject(0)
    assertEquals(1, record.getInt("attempt"))
    assertEquals(22, record.getInt("maxCharacters"))
    assertEquals("Длинный исходный вариант", record.getString("previousTarget"))
    assertEquals("Руководство", record.getString("text"))
    assertEquals("unfit p0-o1", record.getString("fitError"))
    assertEquals("p0-o1", record.getJSONObject("fitFeedback").getString("blockId"))
    assertEquals(1.25, record.getDouble("seconds"), 0.0)
    assertEquals(feedback.toString(), block.getJSONObject("layoutFeedback").toString())
  }

  @Test fun fitFailureMatchesRetainedTargetWhenCandidateWasRejectedAndKeepsLegacyHistoryValue() {
    val block = JSONObject().put("translatedText", "Prior unfit target")
      .put("layoutRetryHistory", "opaque prior metadata")
    val attempt = LayoutRetryPolicy.beginAttempt(block, null)!!
    LayoutRetryPolicy.appendHistory(block, attempt, "Prior unfit target", "Protected value changed", "guard rejected", 0.5)

    LayoutRetryPolicy.recordFitFailure(block, "unfit p0-o2", null)

    assertEquals("unfit p0-o2", block.getJSONArray("layoutRetryHistory").getJSONObject(0).getString("fitError"))
    assertEquals("opaque prior metadata", block.getString("layoutRetryHistoryLegacy"))
  }

  @Test fun historyWithNoMatchingCurrentAttemptRecordsInitialFailureInsteadOfChangingOldRows() {
    val oldRow = JSONObject().put("policyVersion", 1).put("attempt", 2)
      .put("text", "Old candidate").put("previousTarget", "Old target")
    val wrongAttempt = JSONObject().put("policyVersion", LayoutRetryPolicy.VERSION).put("attempt", 1)
      .put("text", "Different candidate").put("previousTarget", "Different target")
    val wrongTarget = JSONObject().put("policyVersion", LayoutRetryPolicy.VERSION).put("attempt", 2)
      .put("text", "Different candidate").put("previousTarget", "Different target")
    val block = JSONObject().put("layoutRetryPolicyVersion", LayoutRetryPolicy.VERSION).put("layoutRetryCount", 2)
      .put("translatedText", "Current target").put("layoutRetryHistory", JSONArray().put(oldRow).put(wrongAttempt).put(wrongTarget))

    LayoutRetryPolicy.recordFitFailure(block, "current target does not fit", null)

    assertFalse(oldRow.has("fitError"))
    assertFalse(wrongAttempt.has("fitError"))
    assertFalse(wrongTarget.has("fitError"))
    assertEquals("current target does not fit", block.getString("layoutInitialFitError"))
  }

  @Test fun historyCanRecordAnEmptyModelResponseAndClampsNegativeDuration() {
    val block = JSONObject()
    val attempt = LayoutRetryPolicy.beginAttempt(block, null)!!

    LayoutRetryPolicy.appendHistory(block, attempt, "Prior target", null, null, -3.0)

    val record = block.getJSONArray("layoutRetryHistory").getJSONObject(0)
    assertTrue(record.isNull("text"))
    assertTrue(record.isNull("error"))
    assertEquals(0.0, record.getDouble("seconds"), 0.0)
  }

  @Test fun initialFitFailureIsRecordedEvenWithoutNativeFeedback() {
    val block = JSONObject().put("translatedText", "Initial target")

    LayoutRetryPolicy.recordFitFailure(block, "unfit p0-o3", null)

    assertEquals("unfit p0-o3", block.getString("layoutInitialFitError"))
    assertFalse(block.has("layoutFeedback"))
  }
}

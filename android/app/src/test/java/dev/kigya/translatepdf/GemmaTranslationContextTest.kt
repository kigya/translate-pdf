package dev.kigya.translatepdf

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = android.app.Application::class)
class GemmaTranslationContextTest {
  @Test fun acceptsOnlyCompleteTitlesWithinUtf8ByteBounds() {
    assertEquals("Chevrolet Equinox", GemmaTranslationContext.effectiveTitle("Chevrolet Equinox"))
    assertEquals("界".repeat(133), GemmaTranslationContext.effectiveTitle("界".repeat(133)))
    assertEquals("123", GemmaTranslationContext.effectiveTitle("123"))
    assertEquals("x".repeat(400), GemmaTranslationContext.effectiveTitle("x".repeat(400)))
    assertEquals("", GemmaTranslationContext.effectiveTitle(null))
    assertEquals("", GemmaTranslationContext.effectiveTitle("xy"))
    assertEquals("", GemmaTranslationContext.effectiveTitle("界".repeat(134)))
    assertEquals("", GemmaTranslationContext.effectiveTitle("title".repeat(81)))
    assertEquals("", GemmaTranslationContext.effectiveTitle("x".repeat(401)))
    assertEquals("", GemmaTranslationContext.effectiveTitle("bad\uD800title"))
  }

  @Test fun contextKeysAreStableAndChangeWithTheEffectiveTitle() {
    assertEquals(GemmaTranslationContext.contextKey("Title"), GemmaTranslationContext.contextKey("Title"))
    assertNotEquals(GemmaTranslationContext.contextKey("Title A"), GemmaTranslationContext.contextKey("Title B"))
    assertEquals(64, GemmaTranslationContext.contextKey("").length)
  }

  @Test fun checkpointReuseRequiresTheSameTitleAndAllowsLegacyOnlyForEmptyTitle() {
    val fresh = JSONObject().put("sourceText", "Equinox").put("modelText", "Equinox")
    GemmaTranslationContext.stamp(fresh, "Chevrolet Equinox Owner Manual")
    val sameTitle = JSONObject(fresh.toString())
    val changedTitle = JSONObject(fresh.toString()).put(
      "translationContextKey", GemmaTranslationContext.contextKey("Chevrolet Trailblazer Owner Manual"),
    )
    assertTrue(GemmaTranslationContext.checkpointMatches(sameTitle, fresh, "Chevrolet Equinox Owner Manual"))
    assertFalse(GemmaTranslationContext.checkpointMatches(changedTitle, fresh, "Chevrolet Equinox Owner Manual"))

    val emptyTitleFresh = JSONObject().put("sourceText", "Storage").put("modelText", "Storage")
    GemmaTranslationContext.stamp(emptyTitleFresh, "")
    val legacy = JSONObject().put("sourceText", "Storage").put("modelText", "Storage")
    assertTrue(GemmaTranslationContext.checkpointMatches(legacy, emptyTitleFresh, ""))
    assertFalse(GemmaTranslationContext.checkpointMatches(
      legacy, JSONObject(emptyTitleFresh.toString()).put("translationContextKey", "stale"), "",
    ))
    assertFalse(GemmaTranslationContext.checkpointMatches(legacy, fresh, "Chevrolet Equinox Owner Manual"))
    assertFalse(GemmaTranslationContext.checkpointMatches(
      JSONObject(legacy.toString()).put("translationContextKey", JSONObject.NULL), emptyTitleFresh, "",
    ))
  }

  @Test fun onlyFormerPromptBoundariesAbsentFromFreshInputInvalidateSavedTarget() {
    assertEquals(listOf("</source_segment>"), GemmaTranslationContext.leakedPromptBoundaryTokens(
      "Index\n</source_segment>", "Index . . . 402", "Index",
    ))
    assertFalse(GemmaTranslationContext.hasLeakedPromptBoundary("<div>label</div>", "label", "label"))
    assertFalse(GemmaTranslationContext.hasLeakedPromptBoundary("Index </source_segment>",
      "Index </source_segment>", "Index"))
    assertFalse(GemmaTranslationContext.hasLeakedPromptBoundary("Index </source_segment>",
      "<source_segment>Index", "Index"))
    assertFalse(GemmaTranslationContext.hasLeakedPromptBoundary("Index <source_segment>", "Index", "Index <source_segment>"))
  }

  @Test
  fun missingSourceAndModelTextAreTreatedAsAnEmptyPromptInventory() {
    assertEquals(listOf("<source_segment>"), GemmaTranslationContext.leakedPromptBoundaryTokens(
      "<source_segment>Translation", null, null,
    ))
    assertEquals("Translation", GemmaTranslationContext.decodePromptBoundaryEnvelope(
      " \n<source_segment>Translation\t", null, null,
    ).text)

    val literalSource = "Literal </source_segment>"
    val literalDecode = GemmaTranslationContext.decodePromptBoundaryEnvelope(
      literalSource, literalSource, null,
    )
    assertFalse(literalDecode.invalid)
    assertEquals(literalSource, literalDecode.text)
    assertTrue(GemmaTranslationContext.leakedPromptBoundaryTokens(literalSource, literalSource, null).isEmpty())
  }

  @Test
  fun completedArtifactRepairRevisionDoesNotScheduleASecondInference() {
    for (terminalStatus in listOf("translated", "partial", "failed")) {
      val saved = JSONObject().put("sourceText", "A safe translation").put("modelText", "A safe translation")
        .put("translatedText", "Безопасный перевод").put("status", terminalStatus)
        .put("gemmaPromptArtifactRepairRevision", GemmaTranslationContext.SOURCE_BOUNDARY_REPAIR_REVISION)
      val fresh = JSONObject().put("sourceText", "A safe translation").put("modelText", "A safe translation")
        .put("status", "pending")

      assertEquals(terminalStatus, GemmaTranslationContext.CheckpointArtifactRepair.NONE,
        GemmaTranslationContext.prepareCheckpointArtifactRepair(saved, fresh))
      assertFalse(fresh.has("gemmaPromptArtifactRepairPendingRevision"))
      assertFalse(fresh.has("translatedText"))
    }
  }

  @Test
  fun interruptedRepairRevisionRetriesItsSavedTargetInsteadOfReusingIt() {
    val history = org.json.JSONArray().put(JSONObject().put("attempt", 1).put("text", "Prior candidate"))
    val saved = JSONObject().put("sourceText", "Reporting Safety Defects")
      .put("modelText", "Reporting Safety Defects").put("translatedText", "Сообщение о дефектах безопасности")
      .put("status", "pending")
      .put("gemmaPromptArtifactRepairRevision", GemmaTranslationContext.SOURCE_BOUNDARY_REPAIR_REVISION)
      .put("layoutRetryHistory", history)
    val fresh = JSONObject().put("sourceText", "Reporting Safety Defects")
      .put("modelText", "Reporting Safety Defects").put("status", "pending")

    assertEquals(GemmaTranslationContext.CheckpointArtifactRepair.RETRY,
      GemmaTranslationContext.prepareCheckpointArtifactRepair(saved, fresh))
    assertEquals("pending", fresh.getString("status"))
    assertEquals(GemmaTranslationContext.SOURCE_BOUNDARY_REPAIR_REVISION,
      fresh.getString("gemmaPromptArtifactRepairRevision"))
    assertFalse("an interrupted repair must not reuse its prior output as final", fresh.has("translatedText"))
    assertEquals(history.toString(), fresh.getJSONArray("layoutRetryHistory").toString())
  }

  @Test
  fun rejectedCandidateIsNotPromotedToTranslationWithoutSavedTranslatedText() {
    val rejected = "Содержание </source_segment>"
    val saved = JSONObject().put("sourceText", "Index . . . 402").put("modelText", "Index")
      .put("gemmaPromptArtifactRejectedText", rejected).put("status", "failed")
    val fresh = JSONObject().put("sourceText", "Index . . . 402").put("modelText", "Index")
      .put("status", "pending")

    assertEquals(GemmaTranslationContext.CheckpointArtifactRepair.RETRY,
      GemmaTranslationContext.prepareCheckpointArtifactRepair(saved, fresh))
    assertEquals(rejected, fresh.getString("gemmaPromptArtifactRawText"))
    assertFalse(fresh.has("translatedText"))
    assertEquals(GemmaTranslationContext.SOURCE_BOUNDARY_REPAIR_REVISION,
      fresh.getString("gemmaPromptArtifactRepairPendingRevision"))
  }

  @Test
  fun nullSentinelRejectedCandidateDoesNotScheduleRepairOrBecomeOutput() {
    val saved = JSONObject().put("sourceText", "A label").put("modelText", "A label")
      .put("gemmaPromptArtifactRejectedText", "null").put("status", "failed")
    val fresh = JSONObject().put("sourceText", "A label").put("modelText", "A label")
      .put("status", "pending")

    assertEquals(GemmaTranslationContext.CheckpointArtifactRepair.NONE,
      GemmaTranslationContext.prepareCheckpointArtifactRepair(saved, fresh))
    assertFalse(fresh.has("translatedText"))
    assertFalse(fresh.has("gemmaPromptArtifactRawText"))
    assertFalse(fresh.has("gemmaPromptArtifactRepairPendingRevision"))
  }

  @Test fun safeBoundarySuffixIsDecodedWithoutInferenceAndKeepsOldHistory() {
    val history = org.json.JSONArray().put(JSONObject().put("attempt", 2).put("text", "Previous fit candidate"))
    val saved = JSONObject().put("id", "p1-o30")
      .put("sourceText", "Index . . . . 402").put("modelText", "Index")
      .put("translatedText", "Содержание\n</source_segment>")
      .put("status", "translated").put("layoutRetryCount", 2)
      .put("layoutRetryPolicyVersion", 2).put("layoutRetryHistory", history)
    val fresh = JSONObject().put("id", "p1-o30").put("sourceText", "Index . . . . 402")
      .put("modelText", "Index").put("left", 90).put("status", "pending")

    assertEquals(GemmaTranslationContext.CheckpointArtifactRepair.DECODED,
      GemmaTranslationContext.prepareCheckpointArtifactRepair(saved, fresh))
    assertEquals("Содержание", fresh.getString("translatedText"))
    assertEquals("translated", fresh.getString("status"))
    assertEquals("Содержание\n</source_segment>", fresh.getString("gemmaPromptArtifactRawText"))
    assertEquals(listOf("</source_segment>"), (0 until fresh.getJSONArray("gemmaPromptArtifactDecodedTokens").length())
      .map(fresh.getJSONArray("gemmaPromptArtifactDecodedTokens")::getString))
    assertEquals(2, fresh.getInt("layoutRetryCount"))
    assertEquals(history.toString(), fresh.getJSONArray("layoutRetryHistory").toString())
    assertEquals("fresh extraction geometry is retained", 90, fresh.getInt("left"))
  }

  @Test fun completedBlankTargetIsRetainedAsCorruptOutputAndGetsOnlyOneBoundedRepair() {
    val history = org.json.JSONArray().put(JSONObject().put("attempt", 1).put("text", "Prior target"))
    val saved = JSONObject().put("id", "p1-o30").put("sourceText", "Index").put("modelText", "Index")
      .put("translatedText", "  \n ").put("status", "translated")
      .put("layoutRetryCount", 1).put("layoutRetryPolicyVersion", 2).put("layoutRetryHistory", history)
    val fresh = JSONObject().put("id", "p1-o30").put("sourceText", "Index").put("modelText", "Index")
      .put("status", "pending")

    assertEquals(GemmaTranslationContext.CheckpointArtifactRepair.RETRY,
      GemmaTranslationContext.prepareCheckpointArtifactRepair(saved, fresh))
    assertEquals("  \n ", fresh.getString("gemmaPromptArtifactRawText"))
    assertFalse(fresh.has("translatedText"))
    assertEquals("pending", fresh.getString("status"))
    assertEquals(history.toString(), fresh.getJSONArray("layoutRetryHistory").toString())
    assertTrue(GemmaTranslationContext.beginCheckpointArtifactRepair(fresh))

    val blankRetryResult = JSONObject(fresh.toString()).put("translatedText", "\t")
      .put("status", "translated")
    val afterRetry = JSONObject().put("id", "p1-o30").put("sourceText", "Index")
      .put("modelText", "Index").put("status", "pending")
    assertEquals(GemmaTranslationContext.CheckpointArtifactRepair.EXHAUSTED,
      GemmaTranslationContext.prepareCheckpointArtifactRepair(blankRetryResult, afterRetry))
    assertEquals("\t", afterRetry.getString("gemmaPromptArtifactRawText"))
    assertFalse(afterRetry.has("translatedText"))
    assertEquals("failed", afterRetry.getString("status"))
    assertEquals(GemmaTranslationContext.PROMPT_ARTIFACT_ERROR, afterRetry.getString("error"))
    assertEquals(history.toString(), afterRetry.getJSONArray("layoutRetryHistory").toString())
  }

  @Test fun onlyOuterExactMarkersDecodeAndMalformedOrEmptyEnvelopesRetryBoundedly() {
    assertFalse(GemmaTranslationContext.decodePromptBoundaryEnvelope(null, "Source", "Source").invalid)
    assertTrue("whitespace-only model output must not pass through", GemmaTranslationContext.decodePromptBoundaryEnvelope(
      " \n\t ", "Source", "Source",
    ).invalid)
    assertTrue("literal marker inputs do not make whitespace output valid", GemmaTranslationContext.decodePromptBoundaryEnvelope(
      " \n ", "Source </source_segment>", "Source </source_segment>",
    ).invalid)
    assertEquals("  Translation  ", GemmaTranslationContext.decodePromptBoundaryEnvelope(
      "  Translation  ", "Source", "Source",
    ).text)
    assertEquals("Translation", GemmaTranslationContext.decodePromptBoundaryEnvelope(
      "  <source_segment>Translation</source_segment>  ", "Source", "Source",
    ).text)
    assertEquals("Translation", GemmaTranslationContext.decodePromptBoundaryEnvelope(
      " <source_segment>Translation", "Source", "Source",
    ).text)
    assertEquals("Translation", GemmaTranslationContext.decodePromptBoundaryEnvelope(
      "Translation </source_segment> ", "Source", "Source",
    ).text)
    assertEquals("Translation", GemmaTranslationContext.decodePromptBoundaryEnvelope(
      " \n Translation </source_segment> ", "Source", "Source",
    ).text)
    assertTrue(GemmaTranslationContext.decodePromptBoundaryEnvelope(
      "<source_segment></source_segment>", "Source", "Source",
    ).invalid)
    assertTrue("whitespace-only framed output is not a usable translation", GemmaTranslationContext.decodePromptBoundaryEnvelope(
      "<source_segment> \n\t </source_segment>", "Source", "Source",
    ).invalid)
    assertTrue(GemmaTranslationContext.decodePromptBoundaryEnvelope(
      "<source_segment>Text</source_segment></source_segment>", "Source", "Source",
    ).invalid)
    assertTrue(GemmaTranslationContext.decodePromptBoundaryEnvelope(
      "Text </source_segment> more", "Source", "Source",
    ).invalid)
    val literalMarkup = GemmaTranslationContext.decodePromptBoundaryEnvelope(
      "Index </source_segment>", "Index </source_segment>", "Index </source_segment>",
    )
    assertFalse(literalMarkup.invalid)
    assertEquals("Index </source_segment>", literalMarkup.text)
    assertTrue(GemmaTranslationContext.decodePromptBoundaryEnvelope(
      "Index </source_segment></source_segment>", "Index </source_segment>", "Index </source_segment>",
    ).invalid)
    assertTrue("literal source markers may not disappear during replacement",
      GemmaTranslationContext.decodePromptBoundaryEnvelope(
        "Index", "Index </source_segment>", "Index",
      ).invalid)
    assertTrue("disagreeing source and model-input inventories are conservatively rejected",
      GemmaTranslationContext.decodePromptBoundaryEnvelope(
        "Index </source_segment>", "Index </source_segment>", "Index",
      ).invalid)
    assertTrue("an equal source/model inventory must be preserved exactly",
      GemmaTranslationContext.decodePromptBoundaryEnvelope(
        "Index", "Index </source_segment>", "Index </source_segment>",
      ).invalid)
    assertTrue(GemmaTranslationContext.decodePromptBoundaryEnvelope(
      "<div>label</div>", "label", "label",
    ).removedTokens.isEmpty())
    assertTrue(GemmaTranslationContext.decodePromptBoundaryEnvelope(
      "Index </source_segment>", "Index </source_segment>", "Index",
    ).removedTokens.isEmpty())

    val malformedSaved = JSONObject().put("sourceText", "Index").put("modelText", "Index")
      .put("translatedText", "Содержание </source_segment> extra").put("status", "translated")
    val malformedFresh = JSONObject().put("sourceText", "Index").put("modelText", "Index").put("status", "pending")
    assertEquals(GemmaTranslationContext.CheckpointArtifactRepair.RETRY,
      GemmaTranslationContext.prepareCheckpointArtifactRepair(malformedSaved, malformedFresh))
    assertFalse(malformedFresh.has("translatedText"))
    assertEquals(GemmaTranslationContext.SOURCE_BOUNDARY_REPAIR_REVISION,
      malformedFresh.getString("gemmaPromptArtifactRepairPendingRevision"))
    GemmaTranslationContext.beginCheckpointArtifactRepair(malformedFresh)
    assertEquals(GemmaTranslationContext.SOURCE_BOUNDARY_REPAIR_REVISION,
      malformedFresh.getString("gemmaPromptArtifactRepairRevision"))
    assertFalse(malformedFresh.has("gemmaPromptArtifactRepairPendingRevision"))
    val interrupted = JSONObject(malformedFresh.toString()).put("status", "pending").apply { remove("translatedText") }
    val resumed = JSONObject().put("id", "p1-o30").put("sourceText", "Index . . . . 402")
      .put("modelText", "Index").put("status", "pending")
    assertEquals(GemmaTranslationContext.CheckpointArtifactRepair.RETRY,
      GemmaTranslationContext.prepareCheckpointArtifactRepair(interrupted, resumed))
    assertEquals(GemmaTranslationContext.SOURCE_BOUNDARY_REPAIR_REVISION,
      resumed.getString("gemmaPromptArtifactRepairRevision"))

    val contaminatedResponse = "Содержание </source_segment> extra"
    GemmaTranslationContext.rejectGeneratedPromptArtifact(resumed, contaminatedResponse)
    assertEquals("failed", resumed.getString("status"))
    assertTrue(resumed.isNull("translatedText"))
    assertEquals(contaminatedResponse, resumed.getString("gemmaPromptArtifactRejectedText"))
    assertEquals(GemmaTranslationContext.PROMPT_ARTIFACT_ERROR, resumed.getString("error"))
    val nextExtraction = JSONObject().put("sourceText", "Index . . . . 402").put("modelText", "Index")
    assertEquals(GemmaTranslationContext.CheckpointArtifactRepair.EXHAUSTED,
      GemmaTranslationContext.prepareCheckpointArtifactRepair(resumed, nextExtraction))
    assertEquals("failed", nextExtraction.getString("status"))
  }

  @Test
  fun interruptedAndPendingArtifactRepairsResumeWithTheSameBudgetAndHistory() {
    val history = org.json.JSONArray().put(JSONObject().put("attempt", 2).put("text", "Prior layout candidate"))
    val pendingBeforeInference = JSONObject().put("sourceText", "Index").put("modelText", "Index")
      .put("status", "pending").put("translatedText", JSONObject.NULL).put("error", "transient")
      .put("gemmaPromptArtifactRepairPendingRevision", GemmaTranslationContext.SOURCE_BOUNDARY_REPAIR_REVISION)
      .put("layoutRetryCount", 2).put("layoutRetryPolicyVersion", 2).put("layoutRetryHistory", history)
    val freshPending = JSONObject().put("sourceText", "Index").put("modelText", "Index").put("status", "pending")

    assertEquals(GemmaTranslationContext.CheckpointArtifactRepair.RETRY,
      GemmaTranslationContext.prepareCheckpointArtifactRepair(pendingBeforeInference, freshPending))
    assertEquals("pending", freshPending.getString("status"))
    assertEquals(GemmaTranslationContext.SOURCE_BOUNDARY_REPAIR_REVISION,
      freshPending.getString("gemmaPromptArtifactRepairPendingRevision"))
    assertFalse(freshPending.has("translatedText"))
    assertFalse(freshPending.has("error"))
    assertEquals(2, freshPending.getInt("layoutRetryCount"))
    assertEquals(history.toString(), freshPending.getJSONArray("layoutRetryHistory").toString())
    assertTrue(GemmaTranslationContext.beginCheckpointArtifactRepair(freshPending))

    val interrupted = JSONObject(freshPending.toString()).put("status", "pending").put("translatedText", JSONObject.NULL)
    val resumed = JSONObject().put("sourceText", "Index").put("modelText", "Index").put("status", "pending")
    assertEquals(GemmaTranslationContext.CheckpointArtifactRepair.RETRY,
      GemmaTranslationContext.prepareCheckpointArtifactRepair(interrupted, resumed))
    assertEquals(GemmaTranslationContext.SOURCE_BOUNDARY_REPAIR_REVISION,
      resumed.getString("gemmaPromptArtifactRepairRevision"))
    assertEquals(history.toString(), resumed.getJSONArray("layoutRetryHistory").toString())
    assertFalse(GemmaTranslationContext.beginCheckpointArtifactRepair(resumed))
  }

  @Test
  fun successfulBoundaryDecodePreservesPriorErrorAndRetryHistory() {
    val history = org.json.JSONArray().put(JSONObject().put("attempt", 1).put("text", "Earlier fit attempt"))
    val saved = JSONObject().put("sourceText", "Index . . . 402").put("modelText", "Index")
      .put("translatedText", "  Содержание\n</source_segment>  ").put("status", "partial")
      .put("error", "A previous layout warning.")
      .put("layoutRetryCount", 1).put("layoutRetryPolicyVersion", 2).put("layoutRetryHistory", history)
    val fresh = JSONObject().put("sourceText", "Index . . . 402").put("modelText", "Index").put("status", "pending")

    assertEquals(GemmaTranslationContext.CheckpointArtifactRepair.DECODED,
      GemmaTranslationContext.prepareCheckpointArtifactRepair(saved, fresh))
    assertEquals("Содержание", fresh.getString("translatedText"))
    assertEquals("partial", fresh.getString("status"))
    assertEquals("A previous layout warning.", fresh.getString("error"))
    assertEquals(history.toString(), fresh.getJSONArray("layoutRetryHistory").toString())
    assertEquals(1, fresh.getInt("layoutRetryCount"))
  }

  @Test
  fun nullAndLiteralBoundaryInventoriesFailClosedAndModelTextFallbackIsConsistent() {
    assertTrue(GemmaTranslationContext.leakedPromptBoundaryTokens(null, "Source", "Source").isEmpty())
    assertTrue(GemmaTranslationContext.leakedPromptBoundaryTokens("", "Source", "Source").isEmpty())
    assertTrue(GemmaTranslationContext.leakedPromptBoundaryTokens("<div>label</div>", "label", "label").isEmpty())
    assertTrue(GemmaTranslationContext.leakedPromptBoundaryTokens(
      "Index </source_segment>", "Index </source_segment>", "Index").isEmpty())

    val nullResponse = GemmaTranslationContext.decodePromptBoundaryEnvelope(null, "Index", "Index")
    assertFalse(nullResponse.invalid)
    assertEquals(null, nullResponse.text)
    val literalFallback = GemmaTranslationContext.decodePromptBoundaryEnvelope(
      "Index </source_segment>", "Index </source_segment>", "  ",
    )
    assertFalse("blank model text falls back to original source for marker inventory", literalFallback.invalid)
    assertEquals("Index </source_segment>", literalFallback.text)
    val conflictingModel = GemmaTranslationContext.decodePromptBoundaryEnvelope(
      "Index </source_segment>", "Index", "Index <source_segment>",
    )
    assertTrue("a model-input marker that disagrees with source inventory is rejected", conflictingModel.invalid)

    val saved = JSONObject().put("sourceText", "Index . . . 402").put("translatedText", "Содержание </source_segment>")
      .put("status", "translated")
    for (fresh in listOf(
      JSONObject().put("sourceText", "Index . . . 402"),
      JSONObject().put("sourceText", "Index . . . 402").put("modelText", "   "),
    )) {
      assertEquals(GemmaTranslationContext.CheckpointArtifactRepair.DECODED,
        GemmaTranslationContext.prepareCheckpointArtifactRepair(saved, fresh))
      assertEquals("Содержание", fresh.getString("translatedText"))
    }
  }
}

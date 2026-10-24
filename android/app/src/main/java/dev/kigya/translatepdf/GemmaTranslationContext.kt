package dev.kigya.translatepdf

import org.json.JSONObject
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Title-only context identity for TranslateGemma PDF checkpoints. */
internal object GemmaTranslationContext {
  private const val KEY_PREFIX = "gemma-info-title-v1\n"
  const val SOURCE_BOUNDARY_REPAIR_REVISION = "gemma-source-boundary-v2"
  const val PROMPT_ARTIFACT_ERROR = "The model returned an internal prompt boundary token; verify this block."
  private val promptBoundaryTokens = listOf("<source_segment>", "</source_segment>")
  private val utf8 = StandardCharsets.UTF_8

  internal enum class CheckpointArtifactRepair { NONE, DECODED, RETRY, EXHAUSTED }
  internal data class BoundaryDecode(val text: String?, val removedTokens: List<String>, val invalid: Boolean)

  /** Accept a complete, well-formed PDF InfoTitle in the same bounds as the native metadata contract. */
  fun effectiveTitle(title: String?): String {
    if (title == null) return ""
    val encoded = try {
      utf8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(title))
    } catch (_: Exception) {
      return ""
    }
    return title.takeIf { encoded.remaining() in 3..400 } ?: ""
  }

  fun contextKey(title: String): String = MessageDigest.getInstance("SHA-256")
    .digest((KEY_PREFIX + title).toByteArray(utf8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

  fun stamp(block: JSONObject, title: String) {
    block.put("translationContextKey", contextKey(title))
  }

  /** Old checkpoints without a key are safe only when the effective title is empty. */
  fun checkpointMatches(saved: JSONObject, fresh: JSONObject, title: String): Boolean {
    val expected = contextKey(title)
    val savedKey = saved.opt("translationContextKey") as? String
    if (savedKey != null) return savedKey == expected
    return !saved.has("translationContextKey") && title.isEmpty() &&
      fresh.optString("translationContextKey") == expected
  }

  /** Recognize only the exact former transport delimiters, never arbitrary angle-bracket markup. */
  fun leakedPromptBoundaryTokens(target: String?, sourceText: String?, modelText: String?): List<String> {
    if (target.isNullOrEmpty()) return emptyList()
    val source = sourceText.orEmpty()
    val model = modelText.orEmpty()
    if (promptBoundaryTokens.any { it in source || it in model }) return emptyList()
    return promptBoundaryTokens.filter { it in target }
  }

  fun hasLeakedPromptBoundary(target: String?, sourceText: String?, modelText: String?): Boolean =
    leakedPromptBoundaryTokens(target, sourceText, modelText).isNotEmpty()

  /**
   * Decode only the exact transport delimiters used by the old Gemma prompt.
   * Literal source markup disables decoding; arbitrary markup is never stripped.
   */
  fun decodePromptBoundaryEnvelope(target: String?, sourceText: String?, modelText: String?): BoundaryDecode {
    if (target == null) return BoundaryDecode(null, emptyList(), invalid = false)
    if (target.isBlank()) return BoundaryDecode(null, emptyList(), invalid = true)
    val source = sourceText.orEmpty()
    val model = modelText.orEmpty().ifBlank { source }
    if (promptBoundaryTokens.any { it in source || it in model }) {
      return BoundaryDecode(target, emptyList(), invalid = !boundaryCountsAreValid(target, source, model))
    }
    if (promptBoundaryTokens.none { it in target }) return BoundaryDecode(target, emptyList(), invalid = false)

    var body = target.trim()
    val removed = mutableListOf<String>()

    if (body.startsWith(promptBoundaryTokens[0])) {
      body = body.removePrefix(promptBoundaryTokens[0]).trimStart()
      removed += promptBoundaryTokens[0]
    }
    if (body.endsWith(promptBoundaryTokens[1])) {
      body = body.removeSuffix(promptBoundaryTokens[1]).trimEnd()
      removed += promptBoundaryTokens[1]
    }

    if (promptBoundaryTokens.any { it in body }) return BoundaryDecode(null, removed, invalid = true)
    if (body.isBlank()) return BoundaryDecode(null, removed, invalid = true)
    return BoundaryDecode(body, removed, invalid = false)
  }

  private fun boundaryCountsAreValid(target: String, source: String, model: String): Boolean =
    promptBoundaryTokens.all { token ->
      val sourceCount = source.windowed(token.length).count { it == token }
      val modelCount = model.windowed(token.length).count { it == token }
      sourceCount == modelCount && target.windowed(token.length).count { it == token } == modelCount
    }

  /** Keep valid layout history while decoding or narrowly repairing old prompt-boundary artifacts. */
  fun prepareCheckpointArtifactRepair(saved: JSONObject, fresh: JSONObject): CheckpointArtifactRepair {
    // Preserve the distinction between an absent/null result and a present but
    // blank result. The latter is corrupt completed output and must be reviewed.
    val savedTarget = if (saved.has("translatedText") && !saved.isNull("translatedText")) {
      saved.optString("translatedText")
    } else null
    val savedRepairRevision = saved.optString("gemmaPromptArtifactRepairRevision")
    if (savedRepairRevision == SOURCE_BOUNDARY_REPAIR_REVISION && savedTarget == null &&
      saved.optString("status") !in setOf("translated", "partial", "failed")) {
      // A pause may interrupt the one bounded repair inference after its charge is saved.
      preserveLayoutRetryHistory(saved, fresh)
      fresh.put("gemmaPromptArtifactRepairRevision", SOURCE_BOUNDARY_REPAIR_REVISION)
      return CheckpointArtifactRepair.RETRY
    }
    val candidateToInspect = savedTarget ?: saved.optString("gemmaPromptArtifactRejectedText")
      .takeIf { it.isNotBlank() && it != "null" }
    val pending = saved.optString("gemmaPromptArtifactRepairPendingRevision") == SOURCE_BOUNDARY_REPAIR_REVISION
    val decoded = decodePromptBoundaryEnvelope(candidateToInspect, fresh.optString("sourceText"), effectiveModelText(fresh))
    if (!decoded.invalid && decoded.removedTokens.isNotEmpty() && savedTarget != null) {
      preserveLayoutRetryHistory(saved, fresh)
      fresh.put("translatedText", decoded.text)
        .put("gemmaPromptArtifactRawText", candidateToInspect)
        .put("gemmaPromptArtifactDecodedTokens", org.json.JSONArray(decoded.removedTokens))
      fresh.put("status", saved.optString("status", "translated"))
      if (saved.has("error")) fresh.put("error", saved.opt("error")) else fresh.remove("error")
      fresh.remove("gemmaPromptArtifactRepairPendingRevision")
      return CheckpointArtifactRepair.DECODED
    }
    if (decoded.removedTokens.isEmpty() && !decoded.invalid) {
      if (pending) {
        preserveLayoutRetryHistory(saved, fresh)
        fresh.put("gemmaPromptArtifactRepairPendingRevision", SOURCE_BOUNDARY_REPAIR_REVISION)
          .put("status", "pending")
        fresh.remove("translatedText")
        fresh.remove("error")
        return CheckpointArtifactRepair.RETRY
      }
      if (saved.optString("gemmaPromptArtifactRepairRevision") == SOURCE_BOUNDARY_REPAIR_REVISION &&
        saved.optString("status") !in setOf("translated", "partial", "failed")) {
        // A pause can leave a charged but unanswered local inference. Continue that same repair on resume.
        preserveLayoutRetryHistory(saved, fresh)
        fresh.put("gemmaPromptArtifactRepairRevision", SOURCE_BOUNDARY_REPAIR_REVISION)
        return CheckpointArtifactRepair.RETRY
      }
      return CheckpointArtifactRepair.NONE
    }

    preserveLayoutRetryHistory(saved, fresh)
    fresh.put("gemmaPromptArtifactRawText", candidateToInspect)
    fresh.remove("translatedText")
    fresh.remove("error")
    if (saved.optString("gemmaPromptArtifactRepairRevision") == SOURCE_BOUNDARY_REPAIR_REVISION) {
      fresh.put("gemmaPromptArtifactRepairRevision", SOURCE_BOUNDARY_REPAIR_REVISION)
        .put("status", "failed").put("error", PROMPT_ARTIFACT_ERROR)
      return CheckpointArtifactRepair.EXHAUSTED
    }
    fresh.put("gemmaPromptArtifactRepairPendingRevision", SOURCE_BOUNDARY_REPAIR_REVISION)
      .put("status", "pending")
    return CheckpointArtifactRepair.RETRY
  }

  fun beginCheckpointArtifactRepair(block: JSONObject): Boolean {
    if (block.optString("gemmaPromptArtifactRepairPendingRevision") == SOURCE_BOUNDARY_REPAIR_REVISION) {
      block.put("gemmaPromptArtifactRepairRevision", SOURCE_BOUNDARY_REPAIR_REVISION)
        .remove("gemmaPromptArtifactRepairPendingRevision")
      return true
    }
    return false
  }

  fun rejectGeneratedPromptArtifact(block: JSONObject, candidate: String) {
    block.put("gemmaPromptArtifactRepairRevision", SOURCE_BOUNDARY_REPAIR_REVISION)
      .put("gemmaPromptArtifactRejectedText", candidate)
      .put("translatedText", JSONObject.NULL)
      .put("status", "failed")
      .put("error", PROMPT_ARTIFACT_ERROR)
      .remove("gemmaPromptArtifactRepairPendingRevision")
  }

  private fun preserveLayoutRetryHistory(saved: JSONObject, fresh: JSONObject) {
    listOf("layoutRetryCount", "layoutRetryPolicyVersion", "layoutRetryLegacyCount", "layoutRetryHistory",
      "layoutInitialFitError", "layoutRetryError", "translationReviewRequired")
      .forEach { key -> if (saved.has(key)) fresh.put(key, saved.opt(key)) }
  }

  private fun effectiveModelText(block: JSONObject): String =
    block.optString("modelText", block.optString("sourceText")).trim().ifEmpty { block.optString("sourceText").trim() }
}

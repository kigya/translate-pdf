package dev.kigya.translatepdf

import org.json.JSONArray
import org.json.JSONObject

/** Versioned, bounded layout-revision state stored alongside the block checkpoint. */
internal object LayoutRetryPolicy {
  const val VERSION = 2
  const val MAX_ATTEMPTS = 2
  const val MAX_CHARACTERS = 10_000

  internal data class Attempt(val number: Int, val maxCharacters: Int)

  /** The one-time policy upgrade keeps the old result and its retry count as history. */
  fun hasRemaining(block: JSONObject): Boolean {
    val version = block.optInt("layoutRetryPolicyVersion", 1)
    val used = if (version >= VERSION) block.optInt("layoutRetryCount", 0) else 0
    return used in 0 until MAX_ATTEMPTS
  }

  /** Charges an attempt before inference, so Pause/restart cannot replay an ambiguous call. */
  fun beginAttempt(block: JSONObject, feedback: JSONObject?): Attempt? {
    val version = block.optInt("layoutRetryPolicyVersion", 1)
    if (version >= VERSION && block.optInt("layoutRetryCount", 0) !in 0 until MAX_ATTEMPTS) return null
    if (version < VERSION) {
      val legacyCount = block.optInt("layoutRetryCount", 0).coerceAtLeast(0)
      if (legacyCount > 0 && !block.has("layoutRetryLegacyCount")) {
        block.put("layoutRetryLegacyCount", legacyCount)
      }
      block.put("layoutRetryPolicyVersion", VERSION)
      block.put("layoutRetryCount", 0)
    }
    // The versioned preguard leaves only counts 0..1; legacy migration resets to 0.
    val next = block.optInt("layoutRetryCount", 0) + 1
    val budget = feedback?.opt("estimatedMaxCharacters")
      .let { (it as? Number)?.toInt() ?: 0 }
      .coerceIn(0, MAX_CHARACTERS)
    block.put("layoutRetryCount", next)
    return Attempt(next, budget)
  }

  fun nativeOptions(attempt: Attempt): String {
    require(attempt.number in 1..MAX_ATTEMPTS) { "Layout retry attempt is outside the bounded policy." }
    require(attempt.maxCharacters in 0..MAX_CHARACTERS) { "Layout retry character budget is invalid." }
    return JSONObject().put("attempt", attempt.number).put("maxCharacters", attempt.maxCharacters).toString()
  }

  fun appendHistory(
    block: JSONObject,
    attempt: Attempt,
    previousTarget: String,
    candidateText: String?,
    error: String?,
    seconds: Double,
  ) {
    val history = block.optJSONArray("layoutRetryHistory") ?: JSONArray().also {
      // Preserve an unexpected legacy value instead of silently discarding it.
      block.opt("layoutRetryHistory")?.let { old -> block.put("layoutRetryHistoryLegacy", old) }
      block.put("layoutRetryHistory", it)
    }
    val entry = JSONObject()
      .put("policyVersion", VERSION)
      .put("attempt", attempt.number)
      .put("maxCharacters", attempt.maxCharacters)
      .put("previousTarget", previousTarget)
      .put("text", candidateText?.let { it as Any } ?: JSONObject.NULL)
      .put("error", error?.let { it as Any } ?: JSONObject.NULL)
      .put("seconds", seconds.coerceAtLeast(0.0))
    history.put(entry)
  }

  /** Associates the following whole-page fit rejection with the response that produced it. */
  fun recordFitFailure(block: JSONObject, message: String, feedback: JSONObject?) {
    val history = block.optJSONArray("layoutRetryHistory")
    val currentAttempt = block.optInt("layoutRetryCount", 0)
    val currentTarget = block.optString("translatedText")
    val last = history?.let { rows ->
      (rows.length() - 1 downTo 0).asSequence()
        .mapNotNull(rows::optJSONObject)
        .firstOrNull { row -> row.optInt("policyVersion", 1) == VERSION && row.optInt("attempt", -1) == currentAttempt &&
          (row.optString("text") == currentTarget || row.optString("previousTarget") == currentTarget) }
    }
    if (last == null) {
      if (!block.has("layoutInitialFitError")) block.put("layoutInitialFitError", message)
    } else {
      last.put("fitError", message)
      if (feedback != null) last.put("fitFeedback", feedback)
    }
    if (feedback != null) block.put("layoutFeedback", feedback)
  }
}

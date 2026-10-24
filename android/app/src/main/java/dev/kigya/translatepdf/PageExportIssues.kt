package dev.kigya.translatepdf

import org.json.JSONArray
import org.json.JSONObject

/** Maps native PDF layout findings into durable block and page review state. */
internal object PageExportIssues {
  private const val LAYOUT_FALLBACK = "layout_fallback"

  /**
   * Translation status and PDF placement status have different lifetimes. Keep export findings on
   * each block so a later layout pass can distinguish a placement-only partial from a translation
   * that still needs review. Unknown and legacy partial state is deliberately treated as review.
   */
  fun requiresTranslationReview(block: JSONObject): Boolean {
    if (block.optString("status") == "failed" || block.optBoolean("sourceDisagreement")) return true
    if (block.optBoolean("translationReviewRequired")) return true
    val translatedText = if (block.isNull("translatedText")) block.optString("translation") else block.optString("translatedText")
    if (translatedText.isBlank() || translatedText == "null") return true

    val findings = block.optJSONArray("exportIssues") ?: return block.optString("status") == "partial"
    if (findings.length() == 0) return block.optString("status") == "partial"

    val messages = linkedSetOf<String>()
    for (index in 0 until findings.length()) {
      val finding = findings.optJSONObject(index) ?: return true
      val reason = finding.optString("reason")
      val kind = finding.optString("kind").ifBlank {
        // iOS checkpoints written before `kind` was added store the native reason only.
        if (reason == LAYOUT_FALLBACK) "layout" else "unknown"
      }
      if (kind != "layout" || reason != LAYOUT_FALLBACK) return true
      finding.optString("message").trim().takeIf(String::isNotBlank)?.let(messages::add)
    }
    if (messages.isEmpty()) return true

    val error = block.optString("error").trim()
    if (error.isBlank()) return false
    val errorMessages = error.split('\n').map(String::trim).filter(String::isNotBlank)
    return errorMessages.any { it !in messages }
  }

  fun applyToBlocks(blocks: JSONArray, issues: JSONArray): Boolean {
    var changed = false
    for (issueIndex in 0 until issues.length()) {
      val issue = issues.optJSONObject(issueIndex) ?: continue
      val blockId = issue.optString("blockId").takeIf(String::isNotBlank) ?: continue
      for (blockIndex in 0 until blocks.length()) {
        val block = blocks.optJSONObject(blockIndex) ?: continue
        if (block.optString("id") != blockId) continue
        val reason = issue.optString("reason").ifBlank { "unknown" }
        val kind = when (reason) {
          LAYOUT_FALLBACK -> "layout"
          "missing_translation" -> "translation"
          else -> "unknown"
        }
        val storedIssues = block.optJSONArray("exportIssues") ?: JSONArray().also { block.put("exportIssues", it) }
        // A legacy partial with no finding metadata has unknown provenance. Preserve that fact
        // before adding a layout finding; otherwise the new layout-only record could mask it.
        if (block.optString("status") == "partial" && storedIssues.length() == 0 &&
          !block.optBoolean("translationReviewRequired")) {
          block.put("translationReviewRequired", true)
          changed = true
        }
        val message = issueMessage(issue)
        val alreadyStored = (0 until storedIssues.length()).any { storedIndex ->
          val stored = storedIssues.optJSONObject(storedIndex)
          stored?.optString("reason") == reason && stored.optString("message") == message
        }
        if (!alreadyStored) {
          storedIssues.put(JSONObject().put("kind", kind).put("reason", reason).put("message", message))
          changed = true
        }
        if (block.optString("status") != "failed" && block.optString("status") != "partial") {
          block.put("status", "partial")
          changed = true
        }
        if (block.optString("error").isBlank()) {
          block.put("error", message)
          changed = true
        }
        break
      }
    }
    return changed
  }

  fun pageError(existing: String?, blocks: JSONArray, exportPartial: Boolean, issues: JSONArray): String? {
    val messages = linkedSetOf<String>()
    existing?.trim()?.takeIf(String::isNotBlank)?.let(messages::add)
    for (index in 0 until issues.length()) {
      issues.optJSONObject(index)?.let { issue -> messages.add(issueMessage(issue)) }
    }
    for (index in 0 until blocks.length()) {
      val block = blocks.optJSONObject(index) ?: continue
      if (block.optString("status") in setOf("partial", "failed")) {
        block.optString("error").trim().takeIf(String::isNotBlank)?.let(messages::add)
      }
    }
    if (exportPartial && messages.isEmpty()) messages.add("Some translated content could not be placed safely; review this page.")
    return messages.takeIf { it.isNotEmpty() }?.joinToString("\n")
  }

  private fun issueMessage(issue: JSONObject): String = issue.optString("message").trim()
    .ifBlank { issue.optString("reason").replace('_', ' ').trim() }
    .ifBlank { "Some translated content could not be placed safely; review this page." }
}

package dev.kigya.translatepdf

import org.json.JSONArray
import org.json.JSONObject

/** Maps native PDF layout findings into durable block and page review state. */
internal object PageExportIssues {
  fun applyToBlocks(blocks: JSONArray, issues: JSONArray): Boolean {
    var changed = false
    for (issueIndex in 0 until issues.length()) {
      val issue = issues.optJSONObject(issueIndex) ?: continue
      val blockId = issue.optString("blockId").takeIf(String::isNotBlank) ?: continue
      for (blockIndex in 0 until blocks.length()) {
        val block = blocks.optJSONObject(blockIndex) ?: continue
        if (block.optString("id") != blockId) continue
        if (block.optString("status") != "failed" && block.optString("status") != "partial") {
          block.put("status", "partial")
          changed = true
        }
        if (block.optString("error").isBlank()) {
          block.put("error", issueMessage(issue))
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

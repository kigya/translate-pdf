package dev.kigya.translatepdf

import org.json.JSONObject

/** Product language IDs are stable values persisted in jobs and preferences. */
internal object LanguagePair {
  val codes = listOf("ru", "zh-Hans", "fr", "en", "de", "es")
  private val labels = mapOf(
    "ru" to "Russian", "zh-Hans" to "Simplified Chinese", "fr" to "French",
    "en" to "English", "de" to "German", "es" to "Spanish",
  )

  fun isValidCode(code: String): Boolean = code in codes

  fun requireValid(source: String, target: String) {
    require(isValidCode(source) && isValidCode(target) && source != target) {
      "Choose two different supported languages."
    }
  }

  fun label(code: String): String = labels[code] ?: throw IllegalArgumentException("Unsupported language.")

  fun fromJson(value: JSONObject): Pair<String, String> = value.getString("sourceLanguage") to value.getString("targetLanguage")

  fun displayLabel(source: String, target: String): String {
    requireValid(source, target)
    return "${label(source)} to ${label(target)}"
  }
}

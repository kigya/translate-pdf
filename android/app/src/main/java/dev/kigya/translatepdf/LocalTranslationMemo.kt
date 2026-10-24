package dev.kigya.translatepdf

import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

/** Worker-local exact-request cache. Never stores failures or sends text outside the device. */
internal class LocalTranslationMemo(
  private val maxBytes: Int = 2 * 1024 * 1024,
  private val maxEntries: Int = 256,
  private val translateNative: (String, String, String, String, String) -> String,
) {
  private val entries = LinkedHashMap<String, String>(32, .75f, true)
  private var bytes = 0

  fun clear() { entries.clear(); bytes = 0 }

  fun translate(text: String, glossary: String, context: String, source: String, target: String): String {
    cached(text, glossary, context, source, target)?.let { return it }
    val raw = translateNative(text, glossary, context, source, target)
    remember(text, glossary, context, source, target, raw)
    return raw
  }

  /** Batch work consults the same cache before admitting a request to inference. */
  fun cached(text: String, glossary: String, context: String, source: String, target: String): String? {
    val key = JSONArray(listOf(text, glossary, context, source, target)).toString()
    return entries[key]?.let { saved ->
      JSONObject(saved).put("tokens", 0).put("seconds", 0).put("tokensPerSecond", 0)
        .put("cacheHit", true).toString()
    }
  }

  /** Admit only a complete successful response, including a batch EOS result. */
  fun remember(text: String, glossary: String, context: String, source: String, target: String, raw: String) {
    val key = JSONArray(listOf(text, glossary, context, source, target)).toString()
    val result = JSONObject(raw)
    if (result.has("error") || result.optString("text").isBlank()) return
    val size = key.toByteArray(Charsets.UTF_8).size + raw.toByteArray(Charsets.UTF_8).size
    if (size > maxBytes || maxEntries < 1) return
    entries.remove(key)?.let { previous ->
      bytes -= key.toByteArray(Charsets.UTF_8).size + previous.toByteArray(Charsets.UTF_8).size
    }
    while (entries.isNotEmpty() && (bytes + size > maxBytes || entries.size >= maxEntries)) {
      val oldest = entries.entries.iterator().next()
      bytes -= oldest.key.toByteArray(Charsets.UTF_8).size + oldest.value.toByteArray(Charsets.UTF_8).size
      entries.remove(oldest.key)
    }
    entries[key] = raw
    bytes += size
  }
}

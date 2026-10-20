package dev.kigya.translatepdf

import java.util.Locale

/** Keeps numbers and Latin technical identifiers stable while allowing a Han unit immediately after a number. */
internal object ProtectedValueGuard {
  private val protectedPattern = Regex(
    """(?:(?<![\p{L}\d])|(?<=\p{IsHan}))[-+]?\d+(?:[.,]\d+)?(?:\s?(?:%|°[CF]|mm|cm|km|kg|mg|ml|MPa|kPa|V|A|W|Hz|rpm))?(?!(?:\d|[\p{L}&&[^\p{IsHan}]]))|(?:(?<![\p{L}\d])|(?<=\p{IsHan}))[A-Z]+[A-Z0-9._/-]*\d(?:[A-Z0-9._/-]*[A-Z0-9])?(?!(?:\d|[\p{L}&&[^\p{IsHan}]]))""",
  )

  fun hasMismatch(source: String, translated: String): Boolean = protectedValues(source) != protectedValues(translated)

  internal fun protectedValues(text: String): List<String> =
    protectedPattern.findAll(text).map { it.value.lowercase(Locale.ROOT) }.sorted().toList()
}

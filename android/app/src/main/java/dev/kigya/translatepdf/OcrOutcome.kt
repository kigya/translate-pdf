package dev.kigya.translatepdf

/** Honest OCR outcome for pages where no text is detected or OCR fails. */
internal object OcrOutcome {
  const val NO_TEXT_WARNING = "No native or OCR text was found on this page; review the original page manually."
  const val SOURCE_DISAGREEMENT_WARNING = "Embedded PDF text differs from local OCR; verify the source text before relying on this translation."
  const val SOURCE_DISAGREEMENT_BLOCK_WARNING = "Embedded PDF text differs from local OCR; verify this source region."

  fun warning(blockCount: Int, ocrFailure: String?, disagreementCount: Int = 0): String? = ocrFailure
    ?: if (disagreementCount > 0) SOURCE_DISAGREEMENT_WARNING
    else if (blockCount <= 0) NO_TEXT_WARNING else null

  /** OCR line/embedded paragraph comparison after geometry has matched the same region. */
  fun disagrees(embedded: String, recognized: String): Boolean {
    val source = normalize(embedded)
    val ocr = normalize(recognized)
    if (source.length < 4 || ocr.length < 4 || source == ocr || source.contains(ocr) || ocr.contains(source)) return false
    val protectedPattern = Regex("\\d+(?:[.,]\\d+)?[a-z]*")
    val sourceValues = protectedPattern.findAll(source).map { it.value }.toList()
    val ocrValues = protectedPattern.findAll(ocr).map { it.value }.toList()
    if (sourceValues.isNotEmpty() && ocrValues.isNotEmpty() &&
      !sourceValues.isSubsequenceOf(ocrValues) && !ocrValues.isSubsequenceOf(sourceValues)) return true
    val distance = levenshtein(source, ocr)
    return 1.0 - distance.toDouble() / maxOf(source.length, ocr.length) < 0.85
  }

  private fun normalize(text: String): String = text.lowercase().filter(Char::isLetterOrDigit)

  private fun List<String>.isSubsequenceOf(other: List<String>): Boolean {
    var next = 0
    for (value in other) if (next < size && this[next] == value) next++
    return next == size
  }

  private fun levenshtein(first: String, second: String): Int {
    var previous = IntArray(second.length + 1) { it }
    for (i in first.indices) {
      val current = IntArray(second.length + 1)
      current[0] = i + 1
      for (j in second.indices) {
        current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1,
          previous[j] + if (first[i] == second[j]) 0 else 1)
      }
      previous = current
    }
    return previous[second.length]
  }
}

package dev.kigya.translatepdf

/** Honest OCR outcome for pages where no text is detected or OCR fails. */
internal object OcrOutcome {
  const val NO_TEXT_WARNING = "No native or OCR text was found on this page; review the original page manually."
  const val SOURCE_DISAGREEMENT_WARNING = "Embedded PDF text differs from local OCR; verify the source text before relying on this translation."
  const val SOURCE_DISAGREEMENT_BLOCK_WARNING = "Embedded PDF text differs from local OCR; verify this source region."

  private val nativeTocReference = Regex("^(.+?)\\s*((?:\\.\\s*){3,})\\s+(\\d{1,4})$")
  private val ocrTocReference = Regex("^(.+?)\\s*((?:\\.\\s*){3,})(.*?)\\s+(\\d{1,4})$")

  fun warning(blockCount: Int, ocrFailure: String?, disagreementCount: Int = 0): String? = ocrFailure
    ?: if (disagreementCount > 0) SOURCE_DISAGREEMENT_WARNING
    else if (blockCount <= 0) NO_TEXT_WARNING else null

  /** Compare text only after geometry has matched an owned native sourceRect fragment to an OCR line. */
  fun disagrees(embedded: String, recognized: String): Boolean {
    val source = normalize(embedded)
    val ocr = normalize(recognized)
    if (source.isEmpty() || ocr.isEmpty() || source == ocr) return false
    // Short diagram labels and part numbers still need review when their
    // glyphs disagree in the same region (for example A1 versus A2).
    if (source.length < 4 || ocr.length < 4) return true
    if (source.contains(ocr) || ocr.contains(source)) return false
    val protectedPattern = Regex("\\d+(?:[.,]\\d+)?[a-z]*")
    val sourceValues = protectedPattern.findAll(source).map { it.value }.toList()
    val ocrValues = protectedPattern.findAll(ocr).map { it.value }.toList()
    if (sourceValues.isNotEmpty() && ocrValues.isNotEmpty() &&
      !sourceValues.isSubsequenceOf(ocrValues) && !ocrValues.isSubsequenceOf(sourceValues)) return true
    val distance = levenshtein(source, ocr)
    return 1.0 - distance.toDouble() / maxOf(source.length, ocr.length) < 0.85
  }

  /**
   * Accept leader-dot OCR noise only when the native source proves a TOC row,
   * the model input is exactly its label, and OCR preserves that label and the
   * isolated trailing page reference. Geometry must be checked by the caller.
   */
  fun isVerifiedTocLeaderNoise(sourceText: String, sourceRectText: String, modelText: String,
                               recognized: String): Boolean {
    val source = nativeTocReference.matchEntire(sourceText) ?: return false
    val rect = nativeTocReference.matchEntire(sourceRectText) ?: return false
    val ocr = ocrTocReference.matchEntire(recognized.trim()) ?: return false
    val sourceLabel = source.groupValues[1].trim()
    val sourceReference = source.groupValues[3]
    val rectLabel = rect.groupValues[1].trim()
    val rectReference = rect.groupValues[3]
    val ocrLabel = ocr.groupValues[1].trim()
    val ocrReference = ocr.groupValues[4]
    val middlePeriods = (ocr.groupValues[2] + ocr.groupValues[3]).count { it == '.' }
    return sourceLabel.isNotEmpty() && sourceLabel == modelText &&
      normalize(sourceLabel) == normalize(rectLabel) && sourceReference == rectReference &&
      normalize(sourceLabel) == normalize(ocrLabel) && sourceReference == ocrReference && middlePeriods >= 3
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

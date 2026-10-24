package dev.kigya.translatepdf

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class PdfSourceRange(
  val sourcePageIndex: Int,
  val firstOutputPage: Int,
  val outputPageCount: Int,
)

internal data class PdfLayoutPage(
  val outputPageIndex: Int,
  val sourcePageIndex: Int,
  val sourceOutputPageIndex: Int,
  val placements: JSONArray,
)

internal data class LocalPagePlacements(
  val outputPages: List<JSONArray>,
)

/** Durable proof that a v3 source-preserving PDF and its identity page map were verified together. */
internal data class PdfLayoutReceipt(
  val sourceSha256: String,
  val checkpointDigest: String,
  val outputSha256: String,
  val sourcePageCount: Int,
  val outputPageCount: Int,
  val sourceRanges: List<PdfSourceRange>,
  val pages: List<PdfLayoutPage>,
  val complete: Boolean = true,
) {
  val layoutVersion: Int get() = LAYOUT_VERSION

  fun toJson(): JSONObject {
    validate()
    val content = contentJson()
    return content.put("receiptSha256", sha256(content.toString().toByteArray(Charsets.UTF_8)))
  }

  fun matches(sourceSha256: String, checkpointDigest: String, outputSha256: String): Boolean =
    complete && this.sourceSha256 == sourceSha256 && this.checkpointDigest == checkpointDigest &&
      this.outputSha256 == outputSha256

  fun validate() {
    require(complete) { "The PDF layout receipt is incomplete." }
    require(sourcePageCount > 0 && outputPageCount == sourcePageCount) { "The PDF layout receipt has invalid page counts." }
    require(sourceSha256.isSha256() && checkpointDigest.isSha256() && outputSha256.isSha256()) {
      "The PDF layout receipt has an invalid content digest."
    }
    require(sourceRanges.size == sourcePageCount) { "The PDF layout receipt does not map every source page exactly once." }
    require(pages.size == outputPageCount) { "The PDF layout receipt does not map every output page." }

    val rangesBySource = sourceRanges.associateBy { it.sourcePageIndex }
    require(rangesBySource.size == sourcePageCount && (0 until sourcePageCount).all(rangesBySource::containsKey)) {
      "The PDF layout receipt contains duplicate or missing source pages."
    }
    for (sourceIndex in 0 until sourcePageCount) {
      require(sourceRanges[sourceIndex].sourcePageIndex == sourceIndex) {
        "The PDF layout receipt source ranges are out of order."
      }
      // The complete-key check above plus ordered, unique source indices make the list entry authoritative here.
      val range = sourceRanges[sourceIndex]
      require(range.firstOutputPage == sourceIndex && range.outputPageCount == 1) {
        "The PDF layout receipt does not preserve the source page map."
      }
    }

    for (outputIndex in pages.indices) {
      val page = pages[outputIndex]
      require(page.outputPageIndex == outputIndex) { "The PDF layout receipt contains duplicate or missing output pages." }
      checkNotNull(rangesBySource[page.sourcePageIndex]) {
        "The PDF layout receipt references an unknown source page."
      }
      // Each validated range maps source page N to output page N, so the first mapping is implied by this source identity.
      require(page.outputPageIndex == page.sourcePageIndex && page.sourceOutputPageIndex == 0) {
        "The PDF layout receipt page map disagrees with its source range."
      }
      for (placementIndex in 0 until page.placements.length()) {
        val placement = page.placements.optJSONObject(placementIndex)
          ?: throw IllegalArgumentException("The PDF layout receipt contains a malformed placement.")
        require(PdfLayoutReceipt.requiredInt(placement, "outputPageIndex") == page.outputPageIndex &&
          PdfLayoutReceipt.requiredInt(placement, "sourceOutputPageIndex") == page.sourceOutputPageIndex) {
          "The PDF layout receipt placement map disagrees with its output page."
        }
      }
    }
  }

  private fun contentJson(): JSONObject = JSONObject()
    .put("schemaVersion", SCHEMA_VERSION)
    .put("layoutVersion", LAYOUT_VERSION)
    .put("rendererPolicy", RENDERER_POLICY)
    .put("complete", complete)
    .put("sourceSha256", sourceSha256)
    .put("checkpointDigest", checkpointDigest)
    .put("outputSha256", outputSha256)
    .put("sourcePageCount", sourcePageCount)
    .put("outputPageCount", outputPageCount)
    .put("sourceRanges", JSONArray().also { array ->
      sourceRanges.forEach { range ->
        array.put(JSONObject().put("sourcePageIndex", range.sourcePageIndex)
          .put("firstOutputPage", range.firstOutputPage).put("outputPageCount", range.outputPageCount))
      }
    })
    .put("pages", JSONArray().also { array ->
      pages.forEach { page ->
        array.put(JSONObject().put("outputPageIndex", page.outputPageIndex)
          .put("sourcePageIndex", page.sourcePageIndex)
          .put("sourceOutputPageIndex", page.sourceOutputPageIndex)
          .put("placements", JSONArray(page.placements.toString())))
      }
    })

  companion object {
    const val SCHEMA_VERSION = 1
    const val LAYOUT_VERSION = 3
    // Bump when output rendering changes; font updates invalidate prior PDF caches too.
    const val RENDERER_POLICY = "source-preserving-v10;noto=" + FontAsset.EXPECTED_SHA256 +
      ";cjk=" + FontAsset.EXPECTED_CJK_SHA256

    fun fromMerge(
      sourcePageCount: Int,
      sourceSha256: String,
      checkpointDigest: String,
      outputSha256: String,
      rangesJson: JSONArray,
      pagesJson: JSONArray,
      placementsBySourcePage: List<List<JSONArray>>,
    ): PdfLayoutReceipt {
      require(sourcePageCount > 0 && placementsBySourcePage.size == sourcePageCount) {
        "The PDF merge has an incomplete source-page set."
      }
      require(rangesJson.length() == sourcePageCount && pagesJson.length() == sourcePageCount) {
        "The PDF merge did not preserve the source page count."
      }
      val ranges = (0 until rangesJson.length()).map { index ->
        val item = rangesJson.optJSONObject(index) ?: throw IllegalArgumentException("The PDF merge returned a malformed source range.")
        PdfSourceRange(requiredInt(item, "sourcePageIndex"), requiredInt(item, "firstOutputPage"), requiredInt(item, "outputPageCount"))
      }
      val pageRows = (0 until pagesJson.length()).map { index ->
        val item = pagesJson.optJSONObject(index) ?: throw IllegalArgumentException("The PDF merge returned a malformed output page map.")
        val outputIndex = requiredInt(item, "outputPageIndex")
        val sourceIndex = requiredInt(item, "sourcePageIndex")
        val sourceOutputIndex = requiredInt(item, "sourceOutputPageIndex")
        require(outputIndex == sourceIndex && sourceIndex == index && sourceOutputIndex == 0) {
          "The PDF merge did not preserve the source page map."
        }
        // The merge row guard above requires sourceIndex == index, and the outer size guard covers every row.
        val localPages = placementsBySourcePage[sourceIndex]
        val placements = localPages.getOrNull(sourceOutputIndex)
          ?: throw IllegalArgumentException("The PDF merge references an unknown page in a source shard.")
        val globalPlacements = JSONArray()
        for (placementIndex in 0 until placements.length()) {
          val placement = placements.optJSONObject(placementIndex)
            ?: throw IllegalArgumentException("The PDF core returned a malformed placement in the merged page map.")
          globalPlacements.put(JSONObject(placement.toString())
            .put("outputPageIndex", outputIndex)
            .put("sourceOutputPageIndex", sourceOutputIndex))
        }
        PdfLayoutPage(outputIndex, sourceIndex, sourceOutputIndex, globalPlacements)
      }
      return PdfLayoutReceipt(sourceSha256, checkpointDigest, outputSha256, sourcePageCount,
        pagesJson.length(), ranges, pageRows).also(PdfLayoutReceipt::validate)
    }

    fun validateLocalPlacements(blocks: JSONArray, outputPageCount: Int, placements: JSONArray,
                                 issues: JSONArray): LocalPagePlacements {
      require(outputPageCount == 1) { "A source page must produce exactly one output page." }
      val blockIds = linkedSetOf<String>()
      val eligibleBlockIds = linkedSetOf<String>()
      for (index in 0 until blocks.length()) {
        val block = blocks.optJSONObject(index) ?: throw IllegalArgumentException("A source page contains a malformed block.")
        val id = block.optString("id").takeIf(String::isNotBlank)
          ?: throw IllegalArgumentException("A source block has no stable ID.")
        require(blockIds.add(id)) { "A source page contains duplicate block IDs." }
        val translated = if (block.isNull("translatedText")) "" else block.optString("translatedText")
        val target = if (translated.isBlank() || translated == "null") block.optString("translation") else translated
        if (target.isNotBlank() && target != "null") eligibleBlockIds.add(id)
      }
      val explicitlyUnresolved = linkedSetOf<String>()
      for (index in 0 until issues.length()) {
        val issue = issues.optJSONObject(index) ?: continue
        val id = issue.optString("blockId")
        if (issue.optString("reason") in setOf("missing_translation", "unsupported_layout") && id.isNotBlank()) {
          explicitlyUnresolved.add(id)
        }
      }
      require(explicitlyUnresolved.all(blockIds::contains)) { "A layout issue references a block outside its source page." }

      val byOutput = List(1) { JSONArray() }
      val placedBlocks = linkedSetOf<String>()
      for (index in 0 until placements.length()) {
        val placement = placements.optJSONObject(index)
          ?: throw IllegalArgumentException("The PDF core returned a malformed placement.")
        val outputIndex = requiredInt(placement, "outputPageIndex")
        require(outputIndex == 0) { "A placement references a page outside its source page." }
        val blockId = placement.optString("blockId")
        val figureId = placement.optString("figureId")
        require(blockId.isNotBlank() || figureId.isNotBlank()) { "A placement has no source block or figure ID." }
        if (blockId.isNotBlank()) {
          require(blockId in blockIds) { "A placement references a block outside its source page." }
          placedBlocks.add(blockId)
        }
        byOutput[outputIndex].put(JSONObject(placement.toString()))
      }
      require((eligibleBlockIds - explicitlyUnresolved).all(placedBlocks::contains)) {
        "The PDF core omitted a translated block from the output layout map."
      }
      return LocalPagePlacements(byOutput)
    }

    internal fun requiredInt(json: JSONObject, key: String): Int {
      val value = json.opt(key)
      require(value is Number && value.toDouble() == value.toInt().toDouble()) { "The PDF layout map has an invalid $key." }
      return value.toInt()
    }

    fun fromJson(json: JSONObject): PdfLayoutReceipt {
      require(requiredInt(json, "schemaVersion") == SCHEMA_VERSION) { "Unsupported PDF receipt schema version." }
      require(requiredInt(json, "layoutVersion") == LAYOUT_VERSION) { "Unsupported PDF layout version." }
      require(json.optString("rendererPolicy") == RENDERER_POLICY) { "The PDF renderer or bundled fonts have changed." }
      val rangesJson = json.optJSONArray("sourceRanges") ?: throw IllegalArgumentException("The PDF receipt has no source ranges.")
      val pagesJson = json.optJSONArray("pages") ?: throw IllegalArgumentException("The PDF receipt has no output pages.")
      val ranges = (0 until rangesJson.length()).map { index ->
        val item = rangesJson.optJSONObject(index) ?: throw IllegalArgumentException("The PDF receipt contains a malformed source range.")
        PdfSourceRange(requiredInt(item, "sourcePageIndex"), requiredInt(item, "firstOutputPage"), requiredInt(item, "outputPageCount"))
      }
      val pages = (0 until pagesJson.length()).map { index ->
        val item = pagesJson.optJSONObject(index) ?: throw IllegalArgumentException("The PDF receipt contains a malformed output page.")
        val placements = item.optJSONArray("placements")
          ?: throw IllegalArgumentException("The PDF receipt contains no placement list for an output page.")
        PdfLayoutPage(requiredInt(item, "outputPageIndex"), requiredInt(item, "sourcePageIndex"),
          requiredInt(item, "sourceOutputPageIndex"), placements)
      }
      val receipt = PdfLayoutReceipt(
        json.optString("sourceSha256"), json.optString("checkpointDigest"), json.optString("outputSha256"),
        requiredInt(json, "sourcePageCount"), requiredInt(json, "outputPageCount"), ranges, pages,
        json.opt("complete") as? Boolean ?: throw IllegalArgumentException("The PDF receipt completeness flag is malformed."),
      )
      receipt.validate()
      val expected = json.optString("receiptSha256")
      require(expected.isSha256() && expected == receipt.digest()) { "The PDF layout receipt integrity check failed." }
      return receipt
    }

    private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))

    private fun PdfLayoutReceipt.digest(): String = sha256(contentJson().toString().toByteArray(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
      .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}

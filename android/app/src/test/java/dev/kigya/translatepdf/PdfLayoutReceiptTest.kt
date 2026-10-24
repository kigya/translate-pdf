package dev.kigya.translatepdf

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class PdfLayoutReceiptTest {
  @Test
  fun sourcePreservingReceiptKeepsStrictOneToOnePageAndPlacementMap() {
    val placements = listOf(
      listOf(JSONArray().put(JSONObject().put("blockId", "b0").put("outputPageIndex", 0))),
      listOf(JSONArray().put(JSONObject().put("figureId", "f1").put("outputPageIndex", 0))),
    )
    val receipt = PdfLayoutReceipt.fromMerge(2, hash('a'), hash('b'), hash('c'),
      JSONArray().put(range(0, 0, 1)).put(range(1, 1, 1)),
      JSONArray().put(page(0, 0, 0)).put(page(1, 1, 0)), placements)

    assertEquals(2, receipt.sourcePageCount)
    assertEquals(2, receipt.outputPageCount)
    assertEquals(listOf(0, 1), receipt.pages.map { it.outputPageIndex })
    assertEquals(listOf(0, 1), receipt.pages.map { it.sourcePageIndex })
    assertEquals(listOf(0, 0), receipt.pages.map { it.sourceOutputPageIndex })
    assertEquals("f1", receipt.pages[1].placements.getJSONObject(0).getString("figureId"))
    assertEquals(1, receipt.pages[1].placements.getJSONObject(0).getInt("outputPageIndex"))
    assertEquals(receipt.outputSha256, PdfLayoutReceipt.fromJson(receipt.toJson()).outputSha256)
  }

  @Test
  fun overflowAndNonIdentityMapsCannotBecomeAReceipt() {
    val overflow = runCatching {
      PdfLayoutReceipt.fromMerge(2, hash('a'), hash('b'), hash('c'),
        JSONArray().put(range(0, 0, 3)).put(range(1, 3, 1)),
        JSONArray().put(page(0, 0, 0)).put(page(1, 0, 1)).put(page(2, 0, 2)).put(page(3, 1, 0)),
        listOf(List(3) { JSONArray() }, listOf(JSONArray())))
    }
    assertTrue("overflow/reflow maps cannot become a v3 receipt", overflow.isFailure)
    assertTrue(runCatching {
      PdfLayoutReceipt.fromMerge(2, hash('a'), hash('b'), hash('c'),
        JSONArray().put(range(0, 0, 1)).put(range(1, 1, 1)),
        JSONArray().put(page(0, 1, 0)).put(page(1, 0, 0)),
        listOf(listOf(JSONArray()), listOf(JSONArray())))
    }.isFailure)
  }

  @Test
  fun duplicateMissingAndDiscontinuousSourceMapsAreRejected() {
    val goodPages = JSONArray().put(page(0, 0, 0)).put(page(1, 1, 0))
    val placements = listOf(listOf(JSONArray()), listOf(JSONArray()))
    val maps = listOf(
      JSONArray().put(range(0, 0, 1)).put(range(0, 1, 1)),
      JSONArray().put(range(0, 0, 1)),
      JSONArray().put(range(0, 0, 1)).put(range(1, 2, 1)),
    )

    maps.forEach { ranges ->
      assertTrue(runCatching {
        PdfLayoutReceipt.fromMerge(2, hash('a'), hash('b'), hash('c'), ranges, goodPages, placements)
      }.isFailure)
    }
  }

  @Test
  fun duplicateOutputPagesAndIncorrectSourceOutputIndicesAreRejected() {
    val ranges = JSONArray().put(range(0, 0, 1))
    val placements = listOf(listOf(JSONArray()))
    val maps = listOf(
      JSONArray().put(page(0, 0, 0)).put(page(0, 0, 0)),
      JSONArray().put(page(0, 0, 1)),
      JSONArray(),
    )

    maps.forEach { pages ->
      assertTrue(runCatching {
        PdfLayoutReceipt.fromMerge(1, hash('a'), hash('b'), hash('c'), ranges, pages, placements)
      }.isFailure)
    }
    assertEquals("The PDF merge did not preserve the source page map.", runCatching {
      PdfLayoutReceipt.fromMerge(1, hash('a'), hash('b'), hash('c'), ranges,
        JSONArray().put(page(1, 1, 0)), placements)
    }.exceptionOrNull()?.message)
  }

  @Test
  fun receiptRejectsNestedPlacementWithNonGlobalOutputIndex() {
    val invalid = PdfLayoutReceipt(
      sourceSha256 = hash('a'), checkpointDigest = hash('b'), outputSha256 = hash('c'),
      sourcePageCount = 1, outputPageCount = 1,
      sourceRanges = listOf(PdfSourceRange(0, 0, 1)),
      pages = listOf(PdfLayoutPage(0, 0, 0, JSONArray().put(JSONObject()
        .put("blockId", "block-1").put("outputPageIndex", 1).put("sourceOutputPageIndex", 0)))),
    )

    assertTrue(runCatching { invalid.validate() }.isFailure)
  }

  @Test
  fun cacheMatchRequiresAllThreeContentDigestsAndValidReceiptIntegrity() {
    val receipt = onePageReceipt()
    assertTrue(receipt.matches(hash('a'), hash('b'), hash('c')))
    assertFalse(receipt.matches(hash('d'), hash('b'), hash('c')))
    assertFalse(receipt.matches(hash('a'), hash('d'), hash('c')))
    assertFalse(receipt.matches(hash('a'), hash('b'), hash('d')))

    val tamperedDigest = JSONObject(receipt.toJson().toString()).put("outputSha256", hash('d'))
    assertTrue(runCatching { PdfLayoutReceipt.fromJson(tamperedDigest) }.isFailure)
    assertEquals("source-preserving-v10;noto=" + FontAsset.EXPECTED_SHA256 + ";cjk=" + FontAsset.EXPECTED_CJK_SHA256,
      receipt.toJson().getString("rendererPolicy"))
    for (policy in listOf("source-preserving-v9;noto=" + FontAsset.EXPECTED_SHA256 + ";cjk=" + FontAsset.EXPECTED_CJK_SHA256, "source-preserving-v8;noto=" + FontAsset.EXPECTED_SHA256 + ";cjk=" + FontAsset.EXPECTED_CJK_SHA256, "source-preserving-v7;noto=" + FontAsset.EXPECTED_SHA256 + ";cjk=" + FontAsset.EXPECTED_CJK_SHA256, "source-preserving-v6;noto=" + FontAsset.EXPECTED_SHA256 + ";cjk=" + FontAsset.EXPECTED_CJK_SHA256, "source-preserving-v5;noto=" + FontAsset.EXPECTED_SHA256 + ";cjk=" + FontAsset.EXPECTED_CJK_SHA256, "", "source-preserving-v0", "source-preserving-v1;noto=" + FontAsset.EXPECTED_SHA256 + ";cjk=" + FontAsset.EXPECTED_CJK_SHA256, "source-preserving-v2;noto=" + FontAsset.EXPECTED_SHA256 + ";cjk=" + FontAsset.EXPECTED_CJK_SHA256, "source-preserving-v3;noto=" + FontAsset.EXPECTED_SHA256 + ";cjk=" + FontAsset.EXPECTED_CJK_SHA256, "source-preserving-v4;noto=" + FontAsset.EXPECTED_SHA256 + ";cjk=" + FontAsset.EXPECTED_CJK_SHA256, "source-preserving-v5;noto=changed")) {
      val stale = JSONObject(receipt.toJson().toString()).put("rendererPolicy", policy)
      assertTrue(runCatching { PdfLayoutReceipt.fromJson(stale) }.exceptionOrNull()?.message?.contains("renderer or bundled fonts") == true)
    }
    val oldVersion = JSONObject(receipt.toJson().toString()).put("layoutVersion", 2)
    assertTrue(runCatching { PdfLayoutReceipt.fromJson(oldVersion) }.isFailure)
    val incomplete = JSONObject(receipt.toJson().toString()).put("complete", false)
    assertTrue(runCatching { PdfLayoutReceipt.fromJson(incomplete) }.isFailure)
  }

  @Test
  fun receiptExposesItsCompletenessAndRejectsInvalidCountOrDigestCombinations() {
    val receipt = onePageReceipt()

    assertTrue(receipt.complete)
    assertEquals(1, receipt.sourceRanges.size)
    assertFalse(receipt.copy(complete = false).matches(hash('a'), hash('b'), hash('c')))

    val invalidReceipts = listOf(
      receipt.copy(sourcePageCount = 0),
      receipt.copy(outputPageCount = 0),
      receipt.copy(sourceSha256 = "not-a-digest"),
      receipt.copy(checkpointDigest = "g".repeat(64)),
      receipt.copy(outputSha256 = ""),
    )
    invalidReceipts.forEach { invalid -> assertTrue(runCatching { invalid.validate() }.isFailure) }
  }

  @Test
  fun validateRejectsOutOfOrderRangesUnknownSourcePagesAndMalformedNestedPlacements() {
    val emptyPlacements = listOf(listOf(JSONArray()), listOf(JSONArray()))
    val validOutputPages = JSONArray().put(page(0, 0, 0)).put(page(1, 1, 0))

    assertTrue(runCatching {
      PdfLayoutReceipt.fromMerge(2, hash('a'), hash('b'), hash('c'),
        JSONArray().put(range(1, 0, 1)).put(range(0, 1, 1)), validOutputPages, emptyPlacements)
    }.isFailure)
    assertTrue(runCatching {
      PdfLayoutReceipt.fromMerge(1, hash('a'), hash('b'), hash('c'), JSONArray().put(range(0, 0, 1)),
        JSONArray().put(page(0, 4, 0)), listOf(listOf(JSONArray())))
    }.isFailure)

    val malformedPlacement = PdfLayoutReceipt(
      sourceSha256 = hash('a'), checkpointDigest = hash('b'), outputSha256 = hash('c'),
      sourcePageCount = 1, outputPageCount = 1,
      sourceRanges = listOf(PdfSourceRange(0, 0, 1)),
      pages = listOf(PdfLayoutPage(0, 0, 0, JSONArray().put("not a placement object"))),
    )
    assertTrue(runCatching { malformedPlacement.validate() }.isFailure)

    val mismatchedNestedPlacement = PdfLayoutReceipt(
      sourceSha256 = hash('a'), checkpointDigest = hash('b'), outputSha256 = hash('c'),
      sourcePageCount = 1, outputPageCount = 1,
      sourceRanges = listOf(PdfSourceRange(0, 0, 1)),
      pages = listOf(PdfLayoutPage(0, 0, 0, JSONArray().put(JSONObject()
        .put("blockId", "block-1").put("outputPageIndex", 0).put("sourceOutputPageIndex", 1)))),
    )
    assertTrue(runCatching { mismatchedNestedPlacement.validate() }.isFailure)
    assertTrue(runCatching {
      PdfLayoutReceipt.fromMerge(1, hash('a'), hash('b'), hash('c'), JSONArray().put(range(0, 0, 0)),
        JSONArray().put(page(0, 0, 0)), listOf(listOf(JSONArray())))
    }.isFailure)
  }

  @Test
  fun localPlacementsPreserveFiguresAndRejectOmittedTranslatedOrForeignBlocks() {
    val blocks = JSONArray()
      .put(JSONObject().put("id", "translated").put("translatedText", "Текст"))
      .put(JSONObject().put("id", "missing").put("translatedText", JSONObject.NULL))
    val placements = JSONArray()
      .put(JSONObject().put("blockId", "translated").put("outputPageIndex", 0))
      .put(JSONObject().put("figureId", "fig-1").put("role", "figure").put("outputPageIndex", 0))
    val valid = PdfLayoutReceipt.validateLocalPlacements(blocks, 1, placements,
      JSONArray().put(JSONObject().put("blockId", "missing").put("reason", "missing_translation")))

    assertEquals(1, valid.outputPages.size)
    assertEquals("fig-1", valid.outputPages[0].getJSONObject(1).getString("figureId"))
    assertTrue(runCatching { PdfLayoutReceipt.validateLocalPlacements(blocks, 2, JSONArray(), JSONArray()) }.isFailure)
    assertTrue("a placement may not escape the original source page", runCatching {
      PdfLayoutReceipt.validateLocalPlacements(blocks, 1,
        JSONArray().put(JSONObject().put("blockId", "translated").put("outputPageIndex", 1)), JSONArray())
    }.isFailure)
    assertTrue(runCatching {
      PdfLayoutReceipt.validateLocalPlacements(blocks, 2,
        JSONArray().put(JSONObject().put("blockId", "outside").put("outputPageIndex", 0)), JSONArray())
    }.isFailure)
    assertTrue(runCatching { PdfLayoutReceipt.validateLocalPlacements(blocks, 0, JSONArray(), JSONArray()) }.isFailure)
    assertEquals("A placement references a block outside its source page.", runCatching {
      PdfLayoutReceipt.validateLocalPlacements(
        JSONArray().put(JSONObject().put("id", "inside").put("translatedText", "target")), 1,
        JSONArray().put(JSONObject().put("blockId", "foreign").put("outputPageIndex", 0)), JSONArray(),
      )
    }.exceptionOrNull()?.message)
  }

  @Test
  fun emptySourcePageAllowsOneMappedOutputWithNoPlacements() {
    val result = PdfLayoutReceipt.validateLocalPlacements(JSONArray(), 1, JSONArray(), JSONArray())
    assertEquals(1, result.outputPages.size)
    assertEquals(0, result.outputPages.single().length())
  }

  @Test
  fun localPlacementUsesLegacyTranslationFallbackAndSkipsMalformedIssueRows() {
    val blocks = JSONArray()
      .put(JSONObject().put("id", "legacy").put("translatedText", "").put("translation", "Перевод"))
      .put(JSONObject().put("id", "null-target").put("translatedText", "null").put("translation", "Цель"))
    val placements = JSONArray()
      .put(JSONObject().put("blockId", "legacy").put("outputPageIndex", 0))
      .put(JSONObject().put("blockId", "null-target").put("outputPageIndex", 0))
    val result = PdfLayoutReceipt.validateLocalPlacements(blocks, 1, placements,
      JSONArray().put("legacy malformed issue").put(JSONObject().put("blockId", "legacy").put("reason", "missing_translation")))

    assertEquals(2, result.outputPages.single().length())
  }

  @Test
  fun malformedReceiptFieldsAndLocalPlacementReferencesFailClosed() {
    val valid = onePageReceipt().toJson()
    val wrongSchema = JSONObject(valid.toString()).put("schemaVersion", 9)
    assertTrue(runCatching { PdfLayoutReceipt.fromJson(wrongSchema) }.isFailure)
    assertTrue(runCatching { PdfLayoutReceipt.fromJson(JSONObject().put("schemaVersion", 1).put("layoutVersion", 2)) }.isFailure)

    val missingRange = JSONObject(valid.toString()).put("sourceRanges", JSONArray())
    assertTrue(runCatching { PdfLayoutReceipt.fromJson(missingRange) }.isFailure)
    val malformedRange = JSONArray().put(JSONObject().put("sourcePageIndex", "zero")
      .put("firstOutputPage", 0).put("outputPageCount", 1))
    assertTrue(runCatching {
      PdfLayoutReceipt.fromMerge(1, hash('a'), hash('b'), hash('c'), malformedRange,
        JSONArray().put(page(0, 0, 0)), listOf(listOf(JSONArray())))
    }.isFailure)

    val block = JSONObject().put("id", "b1").put("translatedText", "target")
    assertTrue(runCatching {
      PdfLayoutReceipt.validateLocalPlacements(JSONArray().put(JSONObject().put("id", "b1").put("translatedText", "target"))
        .put(JSONObject().put("id", "b1").put("translatedText", "duplicate")), 1, JSONArray(), JSONArray())
    }.isFailure)
    assertTrue(runCatching {
      PdfLayoutReceipt.validateLocalPlacements(JSONArray().put(block), 1,
        JSONArray().put(JSONObject().put("blockId", "b1").put("outputPageIndex", 1)), JSONArray())
    }.isFailure)
    assertTrue(runCatching {
      PdfLayoutReceipt.validateLocalPlacements(JSONArray().put(block), 1,
        JSONArray().put(JSONObject().put("outputPageIndex", 0)), JSONArray())
    }.isFailure)
    assertTrue(runCatching {
      PdfLayoutReceipt.validateLocalPlacements(JSONArray().put(block), 1, JSONArray(),
        JSONArray().put(JSONObject().put("blockId", "outside").put("reason", "unsupported_layout")))
    }.isFailure)
  }

  @Test
  fun mergedReceiptRequiresAUsableShardPlacementPageAndWellFormedPlacementRows() {
    val ranges = JSONArray().put(range(0, 0, 1))
    val pages = JSONArray().put(page(0, 0, 0))

    assertTrue("every source page must have its local output-page placement list", runCatching {
      PdfLayoutReceipt.fromMerge(1, hash('a'), hash('b'), hash('c'), ranges, pages, listOf(emptyList()))
    }.isFailure)
    assertTrue("malformed core placements cannot be sealed into a receipt", runCatching {
      PdfLayoutReceipt.fromMerge(1, hash('a'), hash('b'), hash('c'), ranges, pages,
        listOf(listOf(JSONArray().put("not-an-object"))))
    }.isFailure)
    assertTrue("a malformed page row cannot be trusted as a merged source map", runCatching {
      PdfLayoutReceipt.fromMerge(1, hash('a'), hash('b'), hash('c'), ranges,
        JSONArray().put("not-a-page-map"), listOf(listOf(JSONArray())))
    }.isFailure)
  }

  @Test
  fun serializedReceiptRejectsWrongTypesAndForgedIntegrityWithoutChangingTheValidReceipt() {
    val original = onePageReceipt()
    val json = original.toJson()
    val malformed = listOf(
      JSONObject(json.toString()).put("schemaVersion", "1"),
      JSONObject(json.toString()).put("layoutVersion", "3"),
      JSONObject(json.toString()).put("sourceSha256", 123),
      JSONObject(json.toString()).put("sourcePageCount", "one"),
      JSONObject(json.toString()).put("sourcePageCount", "1"),
      JSONObject(json.toString()).put("outputPageCount", "1"),
      JSONObject(json.toString()).put("sourcePageCount", 1.9),
      JSONObject(json.toString()).put("outputPageCount", 1.9),
      JSONObject(json.toString()).put("sourcePageCount", JSONObject.NULL),
      JSONObject(json.toString()).put("outputPageCount", false),
      JSONObject(json.toString()).put("complete", "true"),
      JSONObject(json.toString()).put("receiptSha256", hash('d')),
      JSONObject(json.toString()).put("receiptSha256", "A".repeat(64)),
      JSONObject(json.toString()).put("pages", JSONArray().put(JSONObject().put("outputPageIndex", 0)
        .put("sourcePageIndex", 0).put("sourceOutputPageIndex", 0).put("placements", "not-an-array"))),
    )

    malformed.forEach { candidate -> assertTrue(runCatching { PdfLayoutReceipt.fromJson(candidate) }.isFailure) }
    assertEquals("a rejected cache receipt does not mutate the accepted object", hash('a'), original.sourceSha256)
    assertEquals(hash('c'), original.outputSha256)
    assertTrue(original.matches(hash('a'), hash('b'), hash('c')))
  }

  @Test
  fun rehashedReceiptsStillRejectStructurallyInvalidSourceAndPlacementMaps() {
    val valid = onePageReceipt().toJson()
    val malformedRange = JSONObject(valid.toString())
      .put("sourceRanges", JSONArray().put(range(1, 0, 1)))
    val malformedPage = JSONObject(valid.toString())
      .put("pages", JSONArray().put(page(0, 1, 0).put("placements", JSONArray())))
    val malformedPlacement = JSONObject(valid.toString())
    val placements = JSONArray().put(JSONObject()
      .put("blockId", "block-1").put("outputPageIndex", 1).put("sourceOutputPageIndex", 0))
    malformedPlacement.getJSONArray("pages").getJSONObject(0).put("placements", placements)

    listOf(malformedRange, malformedPage, malformedPlacement).forEach { candidate ->
      rehashReceipt(candidate)
      val failure = runCatching { PdfLayoutReceipt.fromJson(candidate) }.exceptionOrNull()
      assertNotNull("receipt structure must be validated even when its digest is internally consistent", failure)
      assertFalse("this must fail the page-map invariant before the digest check",
        failure!!.message.orEmpty().contains("integrity check"))
    }
    val unknownSource = runCatching { PdfLayoutReceipt.fromJson(malformedPage) }.exceptionOrNull()
    assertEquals("The PDF layout receipt references an unknown source page.", unknownSource?.message)
    assertNotNull(PdfLayoutReceipt.fromJson(valid))
  }

  @Test
  fun validationRejectsMissingOutputRowsAndNonIdentityPageOrPlacementMappings() {
    val receipt = onePageReceipt()
    val cases = listOf(
      receipt.copy(pages = emptyList()),
      receipt.copy(sourceRanges = listOf(PdfSourceRange(0, 0, 0))),
      receipt.copy(pages = listOf(PdfLayoutPage(0, 0, 1, JSONArray()))),
      receipt.copy(pages = listOf(PdfLayoutPage(1, 0, 0, JSONArray()))),
      receipt.copy(pages = listOf(PdfLayoutPage(0, 0, 0,
        JSONArray().put(JSONObject().put("outputPageIndex", 0).put("sourceOutputPageIndex", 1))))),
    )

    cases.forEach { invalid -> assertTrue(runCatching { invalid.validate() }.isFailure) }
    assertEquals("The PDF layout receipt does not preserve the source page map.", runCatching {
      receipt.copy(sourceRanges = listOf(PdfSourceRange(0, 0, 2))).validate()
    }.exceptionOrNull()?.message)
    assertEquals("The PDF layout receipt is incomplete.", runCatching {
      receipt.copy(complete = false).validate()
    }.exceptionOrNull()?.message)
    val twoPageReceipt = receipt.copy(
      sourcePageCount = 2,
      outputPageCount = 2,
      sourceRanges = listOf(PdfSourceRange(0, 0, 1), PdfSourceRange(1, 1, 1)),
      pages = listOf(PdfLayoutPage(0, 1, 0, JSONArray()), PdfLayoutPage(1, 0, 0, JSONArray())),
    )
    assertTrue("a page cannot point at a different source page even when both source ranges exist",
      runCatching { twoPageReceipt.validate() }.exceptionOrNull()?.message
        ?.contains("page map disagrees") == true)
    receipt.validate()
  }

  @Test
  fun validNestedPlacementsArePreservedAndNullSentinelTargetsNeedNoPlacement() {
    val withPlacement = PdfLayoutReceipt.fromMerge(
      1, hash('a'), hash('b'), hash('c'), JSONArray().put(range(0, 0, 1)),
      JSONArray().put(page(0, 0, 0)),
      listOf(listOf(JSONArray().put(JSONObject().put("blockId", "b1")
        .put("outputPageIndex", 0).put("sourceOutputPageIndex", 0)))),
    )
    assertEquals(0, withPlacement.pages.single().placements.getJSONObject(0).getInt("outputPageIndex"))
    assertEquals(0, withPlacement.pages.single().placements.getJSONObject(0).getInt("sourceOutputPageIndex"))

    val noTarget = JSONArray().put(JSONObject().put("id", "b-null")
      .put("translatedText", "").put("translation", "null"))
    val validated = PdfLayoutReceipt.validateLocalPlacements(noTarget, 1, JSONArray(), JSONArray())
    assertEquals(0, validated.outputPages.single().length())
  }

  @Test
  fun receiptReaderRejectsMissingRequiredMapArraysBeforeCacheReuse() {
    val valid = onePageReceipt().toJson()
    val withoutRanges = JSONObject(valid.toString()).apply { remove("sourceRanges") }
    val withoutPages = JSONObject(valid.toString()).apply { remove("pages") }

    assertTrue(runCatching { PdfLayoutReceipt.fromJson(withoutRanges) }.exceptionOrNull()
      ?.message.orEmpty().contains("no source ranges"))
    assertTrue(runCatching { PdfLayoutReceipt.fromJson(withoutPages) }.exceptionOrNull()
      ?.message.orEmpty().contains("no output pages"))
  }

  @Test
  fun mergeAndReaderRejectMalformedRangeAndPageRows() {
    val ranges = JSONArray().put(range(0, 0, 1))
    val pages = JSONArray().put(page(0, 0, 0))
    val placements = listOf(listOf(JSONArray()))

    assertTrue(runCatching {
      PdfLayoutReceipt.fromMerge(1, hash('a'), hash('b'), hash('c'), JSONArray().put("not-a-range"), pages, placements)
    }.exceptionOrNull()?.message.orEmpty().contains("malformed source range"))
    assertTrue(runCatching {
      PdfLayoutReceipt.fromMerge(1, hash('a'), hash('b'), hash('c'), ranges, pages, emptyList())
    }.exceptionOrNull()?.message.orEmpty().contains("incomplete source-page set"))
    assertTrue(runCatching {
      PdfLayoutReceipt.fromMerge(1, hash('a'), hash('b'), hash('c'), ranges, JSONArray().put("not-a-page"), placements)
    }.exceptionOrNull()?.message.orEmpty().contains("malformed output page map"))
    assertEquals("The PDF merge has an incomplete source-page set.", runCatching {
      PdfLayoutReceipt.fromMerge(0, hash('a'), hash('b'), hash('c'), JSONArray(), JSONArray(), emptyList())
    }.exceptionOrNull()?.message)

    val valid = onePageReceipt().toJson()
    assertTrue(runCatching {
      PdfLayoutReceipt.fromJson(JSONObject(valid.toString()).put("sourceRanges", JSONArray().put("not-a-range")))
    }.exceptionOrNull()?.message.orEmpty().contains("malformed source range"))
    assertTrue(runCatching {
      PdfLayoutReceipt.fromJson(JSONObject(valid.toString()).put("pages", JSONArray().put("not-a-page")))
    }.exceptionOrNull()?.message.orEmpty().contains("malformed output page"))
  }

  @Test
  fun unresolvedIssuesOnlyExcuseOmissionForRecognizedReasonsAndKnownBlocks() {
    val translated = JSONArray().put(JSONObject().put("id", "b1").put("translatedText", "Цель"))
    val unsupported = PdfLayoutReceipt.validateLocalPlacements(translated, 1, JSONArray(),
      JSONArray().put(JSONObject().put("blockId", "b1").put("reason", "unsupported_layout")))
    assertEquals(0, unsupported.outputPages.single().length())

    for (issue in listOf(
      JSONObject().put("blockId", "b1").put("reason", "translation_review"),
      JSONObject().put("blockId", "").put("reason", "unsupported_layout"),
      JSONObject().put("blockId", "outside").put("reason", "unsupported_layout"),
    )) {
      assertTrue(runCatching { PdfLayoutReceipt.validateLocalPlacements(translated, 1, JSONArray(), JSONArray().put(issue)) }
        .isFailure)
    }
  }

  @Test
  fun malformedSourceBlocksAndPlacementRowsAreRejectedBeforeReceiptCreation() {
    assertTrue(runCatching {
      PdfLayoutReceipt.validateLocalPlacements(JSONArray().put("not-a-block"), 1, JSONArray(), JSONArray())
    }.exceptionOrNull()?.message.orEmpty().contains("malformed block"))
    assertTrue(runCatching {
      PdfLayoutReceipt.validateLocalPlacements(JSONArray().put(JSONObject().put("id", " ")), 1, JSONArray(), JSONArray())
    }.exceptionOrNull()?.message.orEmpty().contains("no stable ID"))
    assertTrue(runCatching {
      PdfLayoutReceipt.validateLocalPlacements(JSONArray(), 1, JSONArray().put("not-a-placement"), JSONArray())
    }.exceptionOrNull()?.message.orEmpty().contains("malformed placement"))
  }

  private fun onePageReceipt() = PdfLayoutReceipt.fromMerge(
    1, hash('a'), hash('b'), hash('c'), JSONArray().put(range(0, 0, 1)),
    JSONArray().put(page(0, 0, 0)), listOf(listOf(JSONArray())),
  )

  private fun range(source: Int, first: Int, count: Int) = JSONObject()
    .put("sourcePageIndex", source).put("firstOutputPage", first).put("outputPageCount", count)

  private fun page(output: Int, source: Int, local: Int) = JSONObject()
    .put("outputPageIndex", output).put("sourcePageIndex", source).put("sourceOutputPageIndex", local)

  private fun rehashReceipt(json: JSONObject) {
    json.remove("receiptSha256")
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(json.toString().toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    json.put("receiptSha256", digest)
  }

  private fun hash(char: Char) = char.toString().repeat(64)
}

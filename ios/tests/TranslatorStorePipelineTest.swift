import Foundation

// Compile-time stand-in for the native bridge referenced by TranslatorStore.page().
// These tests never call page(), so no PDF/native behavior is simulated.
enum TranslateCoreBridge {
  static func readPage(_ path: String, index: Int) -> String { "{}" }
}

@main
enum TranslatorStorePipelineTest {
  static func main() throws {
    try testInsertedJobValidatesAgainstPinnedInputs()
    try testLegacyPipelineRejectsResumeAndRetainsPageCheckpoint()
    try testCatalogExposesThreeUnverifiedProfilesAndResources()
    try testSelectedModelSettingIsPersistedAndValidated()
    try testUnsupportedGlossaryIsRejectedBeforeJobCreation()
    try testMissingJobCannotValidate()
    print("PASS 6 TranslatorStore pipeline/catalog tests")
  }

  private static func testInsertedJobValidatesAgainstPinnedInputs() throws {
    let fixture = try Fixture()
    let job = try fixture.insertJob(id: "job-valid")
    let model = try ModelCatalog.get(ModelCatalog.recommendedId)

    try fixture.store.validateJobSnapshot("job-valid", model: model)
    try expect(job["status"] as? String == "queued", "new job should start queued")
    try expect((job["pipelineVersion"] as? Int64) == 3, "new job should pin pipeline version 3")
    try expect((job["modelRevision"] as? String) == model.revision, "new job should pin catalog revision")
    try expect((job["modelProfile"] as? String) == model.profile, "job snapshot should expose its frozen native profile")
    try expect((try fixture.store.query("SELECT model_profile FROM jobs WHERE id=?", ["job-valid"]).first?["model_profile"] as? String) == model.profile, "new job should pin the native prompt profile")
    try expectThrows("a job must remain pinned to its originally selected model ID") {
      try fixture.store.validateJobSnapshot("job-valid", model: ModelCatalog.get(ModelCatalog.qualityId))
    }
  }

  private static func testLegacyPipelineRejectsResumeAndRetainsPageCheckpoint() throws {
    let fixture = try Fixture()
    _ = try fixture.insertJob(id: "job-stale")
    let model = try ModelCatalog.get(ModelCatalog.recommendedId)
    try fixture.store.validateJobSnapshot("job-stale", model: model)

    try fixture.store.savePage(jobId: "job-stale", index: 0, status: "translated", blocks: "[]")
    try fixture.store.execute("UPDATE jobs SET pipeline_version=2 WHERE id=?", ["job-stale"])

    try expectThrows("pipeline version 2 must reject checkpoint resume after the prompt/runtime change") {
      try fixture.store.validateJobSnapshot("job-stale", model: model)
    }
    let rows = try fixture.store.pageRows("job-stale")
    try expect(rows.count == 1, "the saved page row should remain after snapshot rejection")
    try expect((rows.first?["index"] as? Int64) == 0, "the retained checkpoint should refer to page zero")
    try expect(rows.first?["status"] as? String == "translated", "the retained page status should stay intact")
  }

  private static func testCatalogExposesThreeUnverifiedProfilesAndResources() throws {
    try expect(ModelCatalog.models.count == 3, "the picker should expose exactly three model choices")
    try expect(Set(ModelCatalog.models.map(\.tier)) == Set(["compact", "balanced", "quality"]), "each candidate should have one distinct relative tier")
    try expect(ModelCatalog.models.allSatisfy { !$0.qualityBenchmarked }, "no experimental model quality should be marked as verified")
    try expect(ModelCatalog.compactId == "hy-mt2-q4-k-m" && ModelCatalog.balancedId == "translategemma-4b-q4" && ModelCatalog.qualityId == "hy-mt2-7b-q4", "iOS model IDs should match Android IDs")
    try expect(ModelCatalog.models.map(\.requiredMemoryBytes) == [2_147_483_648, 3_221_225_472, 6_442_450_944], "pipeline memory estimates should be per-profile and include runtime overhead")
    try expect(ModelCatalog.models.first(where: { $0.profile == "translategemma" })?.supportsGlossary == false, "TranslateGemma should reject unsupported glossary prompting")

    let model = try ModelCatalog.get(ModelCatalog.qualityId)
    let low = MemoryPolicy.assess(model, status: "ready", downloadedBytes: model.bytes, total: 8 * 1024 * 1024 * 1024, available: 5 * 1024 * 1024 * 1024, availableDisk: 0, systemLowMemory: false)
    try expect(!low.memoryEstimateEligible && low.reason == "memory", "a model that exceeds the available per-model memory estimate should be ineligible")
    let disk = MemoryPolicy.assess(model, status: "paused", downloadedBytes: model.bytes / 2, total: 16 * 1024 * 1024 * 1024, available: 10 * 1024 * 1024 * 1024, availableDisk: 1, systemLowMemory: false)
    try expect(disk.requiredDiskBytes == UInt64(model.bytes - model.bytes / 2) + 512 * 1024 * 1024, "partial downloads should reduce remaining model disk requirements")
    let installed = MemoryPolicy.assess(model, status: "ready", downloadedBytes: model.bytes, total: 16 * 1024 * 1024 * 1024, available: 10 * 1024 * 1024 * 1024, availableDisk: 0, systemLowMemory: false)
    try expect(installed.diskEstimateEligible, "a ready model should not require free space for a second model copy")

    let allAvailable = Dictionary(uniqueKeysWithValues: ModelCatalog.models.map { candidate in
      (candidate.id, MemoryPolicy.assess(candidate, status: "ready", downloadedBytes: candidate.bytes, total: 32 * 1024 * 1024 * 1024, available: 20 * 1024 * 1024 * 1024, availableDisk: 0, systemLowMemory: false))
    })
    try expect(ModelCatalog.recommended(from: allAvailable).id == ModelCatalog.qualityId, "recommendation should prefer the highest resource-eligible tier")
    let highestIneligible = allAvailable.merging([ModelCatalog.qualityId: low]) { _, new in new }
    try expect(ModelCatalog.recommended(from: highestIneligible).id == ModelCatalog.balancedId, "recommendation should fall back to the highest eligible tier")

    let payload = model.snapshot(status: "ready", downloadedBytes: model.bytes, assessment: installed)
    try expect(payload["tier"] as? String == model.tier && payload["requiredMemoryBytes"] as? UInt64 == model.requiredMemoryBytes, "model JSON should expose tier and per-profile memory estimate")
    try expect(payload["resourceReason"] as? String == "available" && payload["licenseUrl"] as? String == model.licenseUrl, "model JSON should expose resource reason and license link")
    try expect(payload["supportsGlossary"] as? Bool == model.supportsGlossary, "model JSON should expose glossary capability")
    let compact = try ModelCatalog.get(ModelCatalog.compactId).snapshot(status: "ready", downloadedBytes: ModelCatalog.models[0].bytes, assessment: installed)
    let balanced = try ModelCatalog.get(ModelCatalog.balancedId).snapshot(status: "ready", downloadedBytes: ModelCatalog.models[1].bytes, assessment: installed)
    let quality = try ModelCatalog.get(ModelCatalog.qualityId).snapshot(status: "ready", downloadedBytes: ModelCatalog.models[2].bytes, assessment: installed)
    try expect((compact["evaluation"] as? [String: Int]) == ["acceptableCases":17,"totalCases":44], "compact snapshot should expose the reported 17/44 evaluation")
    try expect((balanced["evaluation"] as? [String: Int]) == ["acceptableCases":29,"totalCases":44], "balanced snapshot should expose the reported 29/44 evaluation")
    try expect((quality["evaluation"] as? [String: Int]) == ["acceptableCases":31,"totalCases":44], "quality snapshot should expose the confirmed 31/44 evaluation")
  }

  private static func testSelectedModelSettingIsPersistedAndValidated() throws {
    let fixture = try Fixture()
    let initial = try fixture.store.resolveSelectedModelId(recommended: ModelCatalog.balancedId, installedMemoryEligible: [])
    try expect(initial == ModelCatalog.balancedId, "a missing selection should initialize from the current recommendation")
    try expect(try fixture.store.resolveSelectedModelId(recommended: ModelCatalog.qualityId, installedMemoryEligible: [ModelCatalog.qualityId]) == ModelCatalog.balancedId, "bootstrap resource changes must not silently replace a valid saved choice")
    try expect(try fixture.store.setSelectedModelId(ModelCatalog.balancedId) == ModelCatalog.balancedId, "a catalog choice should be accepted")
    try expect(try fixture.store.resolveSelectedModelId(recommended: ModelCatalog.compactId, installedMemoryEligible: []) == ModelCatalog.balancedId, "an explicit selection should remain pinned across bootstrap reads")
    try fixture.store.setSetting("selected_model_id", "removed-model")
    try expect(try fixture.store.resolveSelectedModelId(recommended: ModelCatalog.compactId, installedMemoryEligible: [ModelCatalog.qualityId]) == ModelCatalog.qualityId, "an invalid saved selection should recover to an installed eligible model")
    try expect(try fixture.store.selectedModelId() == ModelCatalog.qualityId, "the repaired installed choice should be persisted")
    let eligibilityFixture = try Fixture()
    try expect(try eligibilityFixture.store.resolveSelectedModelId(recommended: ModelCatalog.qualityId, installedMemoryEligible: [ModelCatalog.compactId]) == ModelCatalog.compactId, "an installed but ineligible higher tier must not displace the eligible installed compact model")
    try expect(try eligibilityFixture.store.resolveSelectedModelId(recommended: ModelCatalog.qualityId, installedMemoryEligible: []) == ModelCatalog.compactId, "the eligible installed fallback remains pinned when the recommendation changes")
    try expectThrows("unknown model IDs must be rejected") { _ = try fixture.store.setSelectedModelId("unknown") }
  }

  private static func testUnsupportedGlossaryIsRejectedBeforeJobCreation() throws {
    let fixture = try Fixture()
    _ = try fixture.store.saveGlossary("term-1", source: "bearing", target: "подшипник")
    try expectThrows("a non-glossary profile must reject a job with glossary text") {
      _ = try fixture.store.addJob(id: "job-no-glossary", input: [
        "documentId": "document-1",
        "modelId": ModelCatalog.balancedId,
        "sourceLanguage": "en",
        "targetLanguage": "ru",
      ], pageCount: 1)
    }
    try expect(try fixture.store.job("job-no-glossary") == nil, "unsupported glossary must be rejected before inserting a job")
  }

  private static func testMissingJobCannotValidate() throws {
    let fixture = try Fixture()
    let model = try ModelCatalog.get(ModelCatalog.recommendedId)
    try expectThrows("a missing job must not validate") {
      try fixture.store.validateJobSnapshot("missing", model: model)
    }
  }

  private static func expect(_ condition: @autoclosure () throws -> Bool, _ message: String) throws {
    guard try condition() else { throw TestFailure(description: message) }
  }

  private static func expectThrows(_ message: String, _ operation: () throws -> Void) throws {
    do {
      try operation()
    } catch {
      return
    }
    throw TestFailure(description: message)
  }
}

private struct TestFailure: Error, CustomStringConvertible {
  let description: String
  init(description: String) { self.description = description }
}

private final class Fixture {
  let directory: URL
  let documentURL: URL
  let store: TranslatorStore

  init() throws {
    directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("translator-store-pipeline-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    documentURL = directory.appendingPathComponent("source.pdf")
    try Data("test fixture; store validation only".utf8).write(to: documentURL)
    store = try TranslatorStore(directory: directory)
    _ = try store.saveDocument(
      id: "document-1",
      name: "fixture.pdf",
      path: documentURL.path,
      pages: 1,
      sourceSHA256: sha256(at: documentURL)
    )
  }

  deinit {
    try? FileManager.default.removeItem(at: directory)
  }

  func insertJob(id: String) throws -> [String: Any] {
    try store.addJob(
      id: id,
      input: [
        "documentId": "document-1",
        "modelId": ModelCatalog.recommendedId,
        "sourceLanguage": "en",
        "targetLanguage": "ru",
      ],
      pageCount: 1
    )
  }
}

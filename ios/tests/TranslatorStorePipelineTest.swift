import Foundation
import SQLite3

// Compile-time stand-in for the native bridge referenced by TranslatorStore.page().
// These tests never call page(), so no PDF/native behavior is simulated.
enum TranslateCoreBridge {
  static func readPage(_ path: String, index: Int) -> String { "{}" }
}

@main
enum TranslatorStorePipelineTest {
  static func main() throws {
    try testTranslationLanguagesAndDefaults()
    try testInsertedJobValidatesAgainstPinnedInputs()
    try testLegacyPipelineRejectsResumeAndRetainsPageCheckpoint()
    try testCatalogExposesThreeUnverifiedProfilesAndResources()
    try testSelectedModelSettingIsPersistedAndValidated()
    try testUnsupportedGlossaryIsRejectedBeforeJobCreation()
    try testPairScopedGlossaryAndGemmaValidation()
    try testGlossaryOptOutFreezesEmptySnapshotAndLegacyMigrationIsVersionBounded()
    try testLegacyEnglishRussianGlossaryMigration()
    try testMissingJobCannotValidate()
    try testActiveTransferProgressIsNotReportedAsDiskStorage()
    try testStaleDownloadCallbacksCannotMutateResumedOrDeletedModels()
    try testDownloadProgressCommitCannotRaceInvalidation()
    try testOperationsWaitForBackgroundTaskRestoration()
    print("PASS 14 TranslatorStore pipeline/catalog/language tests")
  }

  private static func testTranslationLanguagesAndDefaults() throws {
    try expect(TranslationLanguage.allCases.count == 6, "the app should expose exactly six translation languages")
    let pairs = TranslationLanguage.allCases.flatMap { source in
      TranslationLanguage.allCases.compactMap { target in source == target ? nil : "\(source.rawValue)>\(target.rawValue)" }
    }
    try expect(pairs.count == 30 && Set(pairs).count == 30, "the six languages should form all 30 distinct ordered pairs")
    try expect(TranslationLanguage.zhHans.visionLocale == "zh-Hans", "Simplified Chinese OCR must use the Vision Simplified Chinese locale")
    let fixture = try Fixture()
    let defaults = try fixture.store.languagePreferences()
    try expect(defaults.sourceLanguage == "en" && defaults.targetLanguage == "ru", "new installations should default to English to Russian")
    try expect(try fixture.store.setting("source_language", fallback: "") == "en" && fixture.store.setting("target_language", fallback: "") == "ru", "bootstrap should persist the default language pair")
    try fixture.store.setSetting("source_language", "invalid")
    try fixture.store.setSetting("target_language", "fr")
    let repaired = try fixture.store.languagePreferences()
    try expect(repaired.sourceLanguage == "en" && repaired.targetLanguage == "ru", "an invalid stored pair should be repaired to English to Russian")
    try expect(try fixture.store.setting("source_language", fallback: "") == "en" && fixture.store.setting("target_language", fallback: "") == "ru", "bootstrap should persist the repaired pair")
    try fixture.store.setLanguagePreferences(sourceLanguage: "zh-Hans", targetLanguage: "fr")
    let saved = try fixture.store.languagePreferences()
    try expect(saved.sourceLanguage == "zh-Hans" && saved.targetLanguage == "fr", "the source and target selection should persist across store instances")
    let reopened = try TranslatorStore(directory: fixture.directory).languagePreferences()
    try expect(reopened.sourceLanguage == "zh-Hans" && reopened.targetLanguage == "fr", "the source and target selection should survive reopening the private store")
    try expectThrows("identical source and target languages must be rejected") {
      try fixture.store.setLanguagePreferences(sourceLanguage: "de", targetLanguage: "de")
    }
    try expectThrows("unknown language codes must be rejected") { _ = try TranslationLanguage.pair(source: "zh", target: "ru") }
  }

  private static func testInsertedJobValidatesAgainstPinnedInputs() throws {
    let fixture = try Fixture()
    let job = try fixture.insertJob(id: "job-valid")
    let model = try ModelCatalog.get(ModelCatalog.recommendedId)

    try fixture.store.validateJobSnapshot("job-valid", model: model)
    try expect(job["sourceLanguage"] as? String == "en" && job["targetLanguage"] as? String == "ru", "the job should freeze its selected language pair")
    try fixture.store.setLanguagePreferences(sourceLanguage: "fr", targetLanguage: "de")
    let frozen = try fixture.store.job("job-valid")
    try expect(frozen?["sourceLanguage"] as? String == "en" && frozen?["targetLanguage"] as? String == "ru", "changing preferences must not alter an existing job's language pair")
    try expect(job["status"] as? String == "queued", "new job should start queued")
    try expect((job["pipelineVersion"] as? Int64) == 4, "new job should pin pipeline version 4")
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
    try fixture.store.execute("UPDATE jobs SET pipeline_version=3 WHERE id=?", ["job-stale"])

    try expectThrows("pipeline version 3 jobs must reject continuation after the prompt/runtime change") {
      try fixture.store.validateJobSnapshot("job-stale", model: model)
    }
    try fixture.store.validateJobForExport("job-stale", model: model)
    try expect(try fixture.store.job("job-stale") != nil, "legacy jobs should remain readable")
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
    let initial = try fixture.store.resolveSelectedModelId(recommended: ModelCatalog.balancedId, installedModelIds: [])
    try expect(initial == ModelCatalog.balancedId, "a missing selection should initialize from the current recommendation")
    try expect(try fixture.store.resolveSelectedModelId(recommended: ModelCatalog.qualityId, installedModelIds: [ModelCatalog.qualityId]) == ModelCatalog.balancedId, "bootstrap resource changes must not silently replace a valid saved choice")
    try expect(try fixture.store.setSelectedModelId(ModelCatalog.balancedId) == ModelCatalog.balancedId, "a catalog choice should be accepted")
    try expect(try fixture.store.resolveSelectedModelId(recommended: ModelCatalog.compactId, installedModelIds: []) == ModelCatalog.balancedId, "an explicit selection should remain pinned across bootstrap reads")
    try fixture.store.setSetting("selected_model_id", "removed-model")
    try expect(try fixture.store.resolveSelectedModelId(recommended: ModelCatalog.compactId, installedModelIds: [ModelCatalog.qualityId]) == ModelCatalog.qualityId, "an invalid saved selection should recover to the highest installed enabled model")
    try expect(try fixture.store.selectedModelId() == ModelCatalog.qualityId, "the repaired installed choice should be persisted")
    let eligibilityFixture = try Fixture()
    try expect(try eligibilityFixture.store.resolveSelectedModelId(recommended: ModelCatalog.compactId, installedModelIds: [ModelCatalog.compactId, ModelCatalog.qualityId]) == ModelCatalog.qualityId, "the highest installed enabled tier should seed the default even when its RAM estimate is ineligible")
    try expect(try eligibilityFixture.store.resolveSelectedModelId(recommended: ModelCatalog.compactId, installedModelIds: []) == ModelCatalog.qualityId, "the installed fallback remains pinned when the recommendation changes")
    try expectThrows("unknown model IDs must be rejected") { _ = try fixture.store.setSelectedModelId("unknown") }
  }

  private static func testUnsupportedGlossaryIsRejectedBeforeJobCreation() throws {
    let fixture = try Fixture()
    _ = try fixture.store.saveGlossary("term-1", source: "bearing", target: "подшипник", sourceLanguage: "en", targetLanguage: "ru")
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

  private static func testPairScopedGlossaryAndGemmaValidation() throws {
    let fixture = try Fixture()
    _ = try fixture.store.saveGlossary("term-fr-de", source: "roulement", target: "Lager", sourceLanguage: "fr", targetLanguage: "de")
    _ = try fixture.store.saveGlossary("term-en-ru", source: "bearing", target: "подшипник", sourceLanguage: "en", targetLanguage: "ru")
    try expect(try fixture.store.glossaryText(nil, sourceLanguage: "fr", targetLanguage: "de") == "roulement translates to Lager", "only glossary rows matching the requested pair should be included")
    try expect(try fixture.store.glossaryText("term-fr-de", sourceLanguage: "en", targetLanguage: "ru").isEmpty, "a selected glossary row from a different pair should not apply")
    try expectThrows("TranslateGemma must reject a matching glossary before inserting a job") {
      _ = try fixture.store.addJob(id: "job-gemma-glossary", input: [
        "documentId": "document-1", "modelId": ModelCatalog.balancedId,
        "glossaryId": "term-fr-de", "sourceLanguage": "fr", "targetLanguage": "de",
      ], pageCount: 1)
    }
    let noMatchingTerms = try fixture.store.addJob(id: "job-gemma-other-pair", input: [
      "documentId": "document-1", "modelId": ModelCatalog.balancedId,
      "glossaryId": "term-fr-de", "sourceLanguage": "en", "targetLanguage": "ru",
    ], pageCount: 1)
    try expect(noMatchingTerms["pipelineVersion"] as? Int64 == 4, "Gemma should allow a selected glossary with no entries for this pair")
    try expect(try fixture.store.jobGlossaryText("job-gemma-other-pair").isEmpty, "the frozen job glossary should contain only the matching pair")
  }

  private static func testGlossaryOptOutFreezesEmptySnapshotAndLegacyMigrationIsVersionBounded() throws {
    let fixture = try Fixture()
    _ = try fixture.store.saveGlossary("term-en-ru", source: "bearing", target: "подшипник", sourceLanguage: "en", targetLanguage: "ru")
    let defaultJob = try fixture.store.addJob(id: "job-default-glossary", input: [
      "documentId": "document-1", "modelId": ModelCatalog.recommendedId,
      "sourceLanguage": "en", "targetLanguage": "ru", "glossaryId": "term-en-ru",
    ], pageCount: 1)
    try expect(try fixture.store.jobGlossaryText("job-default-glossary") == "bearing translates to подшипник", "omitted useGlossary must keep the backward-compatible true default")
    try expect(defaultJob["pipelineVersion"] as? Int64 == 4, "new jobs retain pipeline version 4")

    _ = try fixture.store.addJob(id: "job-opt-out", input: [
      "documentId": "document-1", "modelId": ModelCatalog.recommendedId,
      "sourceLanguage": "en", "targetLanguage": "ru", "glossaryId": "term-en-ru", "useGlossary": false,
    ], pageCount: 1)
    try expect(try fixture.store.jobGlossaryText("job-opt-out").isEmpty, "explicit glossary opt-out must freeze an empty snapshot")
    try fixture.store.execute("UPDATE jobs SET glossary_snapshot='' WHERE id='job-opt-out'")
    _ = try TranslatorStore(directory: fixture.directory)
    try expect(try fixture.store.jobGlossaryText("job-opt-out").isEmpty, "opening a v4 store must never refill an intentionally empty snapshot")

    try fixture.store.execute("UPDATE jobs SET pipeline_version=3,glossary_snapshot='' WHERE id='job-default-glossary'")
    let migratedStore = try TranslatorStore(directory: fixture.directory)
    try expect(try migratedStore.jobGlossaryText("job-default-glossary") == "bearing translates to подшипник", "only a pre-v4 legacy job with an empty snapshot may be populated during migration")
  }

  private static func testLegacyEnglishRussianGlossaryMigration() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("translator-legacy-glossary-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let database = directory.appendingPathComponent("translator-private.db")
    var handle: OpaquePointer?
    try expect(sqlite3_open(database.path, &handle) == SQLITE_OK, "the legacy SQLite fixture should open")
    defer { if let handle { sqlite3_close(handle) } }
    let schema = "CREATE TABLE glossary (id TEXT PRIMARY KEY, source TEXT NOT NULL, target TEXT NOT NULL, created_at INTEGER NOT NULL); INSERT INTO glossary VALUES ('legacy-term', 'bearing', 'подшипник', 1);"
    try expect(sqlite3_exec(handle, schema, nil, nil, nil) == SQLITE_OK, "the legacy glossary table should be created")
    if let handle { sqlite3_close(handle) }
    handle = nil
    let store = try TranslatorStore(directory: directory)
    let migrated = try store.glossary().first { ($0["id"] as? String) == "legacy-term" }
    try expect(migrated?["sourceLanguage"] as? String == "en" && migrated?["targetLanguage"] as? String == "ru", "legacy glossary entries should migrate to English to Russian")
    try expect(try store.glossaryText("legacy-term", sourceLanguage: "en", targetLanguage: "ru") == "bearing translates to подшипник", "legacy terms should remain usable for the original English to Russian pair")
    try expect(try store.glossaryText("legacy-term", sourceLanguage: "fr", targetLanguage: "de").isEmpty, "migrated terms must not leak into another language pair")
  }

  private static func testMissingJobCannotValidate() throws {
    let fixture = try Fixture()
    let model = try ModelCatalog.get(ModelCatalog.recommendedId)
    try expectThrows("a missing job must not validate") {
      try fixture.store.validateJobSnapshot("missing", model: model)
    }
  }

  private static func testStaleDownloadCallbacksCannotMutateResumedOrDeletedModels() throws {
    let firstTask: UInt64 = 1
    let pauseGeneration: UInt64 = 2
    let resumedTask: UInt64 = 3
    try expect(ModelDownloadCallbackGuard.accepts(taskGeneration:firstTask,currentGeneration:firstTask,deleting:false), "the current download callback should be accepted")
    try expect(!ModelDownloadCallbackGuard.accepts(taskGeneration:firstTask,currentGeneration:pauseGeneration,deleting:false), "a canceled task completion must not overwrite the pause state")
    try expect(!ModelDownloadCallbackGuard.accepts(taskGeneration:pauseGeneration,currentGeneration:resumedTask,deleting:false), "a delayed pause callback must not overwrite a resumed download")
    try expect(!ModelDownloadCallbackGuard.accepts(taskGeneration:firstTask,currentGeneration:firstTask,deleting:true), "callbacks must not mutate model state while deletion is tombstoned")
    try expect(ModelDownloadCallbackGuard.accepts(taskGeneration:resumedTask,currentGeneration:resumedTask,deleting:false), "the resumed task callback should remain accepted")
  }

  private static func testDownloadProgressCommitCannotRaceInvalidation() throws {
    let lock=NSLock()
    var generation:UInt64=1
    var deleting=false
    var commits=0
    let entered=DispatchSemaphore(value:0),release=DispatchSemaphore(value:0),invalidated=DispatchSemaphore(value:0)
    let callbackDone=DispatchSemaphore(value:0)
    DispatchQueue.global().async {
      _ = ModelDownloadCallbackGuard.performIfCurrent(lock:lock,taskGeneration:{1},currentGeneration:{generation},isDeleting:{deleting}) {
        entered.signal()
        _ = release.wait(timeout:.now()+2)
        commits += 1
      }
      callbackDone.signal()
    }
    try expect(entered.wait(timeout:.now()+1) == .success, "the progress callback should enter its guarded commit")
    DispatchQueue.global().async {
      lock.lock();generation=2;deleting=true;lock.unlock()
      invalidated.signal()
    }
    try expect(invalidated.wait(timeout:.now()+0.02) == .timedOut, "pause/delete invalidation must wait until the guarded progress write finishes")
    release.signal()
    try expect(callbackDone.wait(timeout:.now()+1) == .success, "the in-flight progress callback should finish")
    try expect(invalidated.wait(timeout:.now()+1) == .success, "the invalidation should proceed immediately after the progress transaction")
    let accepted=ModelDownloadCallbackGuard.performIfCurrent(lock:lock,taskGeneration:{1},currentGeneration:{generation},isDeleting:{deleting}) { commits += 1 }
    try expect(!accepted && commits == 1, "a stale progress callback must not commit after pause or deletion invalidates its generation")
  }

  private static func testOperationsWaitForBackgroundTaskRestoration() throws {
    let barrier=ModelDownloadRestorationBarrier()
    var sideEffects=0
    var failedClosed=false
    do { try barrier.wait(timeout:0.01);sideEffects += 1 } catch { failedClosed=true }
    try expect(failedClosed && sideEffects == 0, "start/pause/delete preflight must fail closed while restored tasks are unresolved")
    barrier.complete()
    barrier.complete()
    var proceeded=false
    do { try barrier.wait(timeout:0.1);sideEffects += 1;proceeded=true } catch {}
    try expect(proceeded && sideEffects == 1, "operations should proceed after task restoration completes exactly once")
  }

  private static func testActiveTransferProgressIsNotReportedAsDiskStorage() throws {
    let fixture=try Fixture()
    let model=try ModelCatalog.get(ModelCatalog.compactId)
    try fixture.store.updateModel(model.id,status:"downloading",bytes:3_145_728)
    let snapshot=try fixture.store.models(totalMemory:8_589_934_592,availableMemory:4_294_967_296,availableDisk:100_000_000_000,systemLowMemory:false).first{$0["id"] as? String == model.id}
    try expect(snapshot?["activeTransferBytes"] as? Int64 == 3_145_728, "in-progress URLSession transfer bytes should be reported as transfer progress")
    try expect(snapshot?["storedBytes"] as? Int64 == 0, "URLSession's private temporary download file must not be claimed as measured app storage")
    try fixture.store.updateModel(model.id,status:"paused",bytes:3_145_728)
    let paused=try fixture.store.models(totalMemory:8_589_934_592,availableMemory:4_294_967_296,availableDisk:100_000_000_000,systemLowMemory:false).first{$0["id"] as? String == model.id}
    try expect((paused?["activeTransferBytes"] as? NSNumber)?.int64Value == 0, "paused transfer progress should not be labeled active")
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

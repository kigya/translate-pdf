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
    try testOldPipelineRejectsResumeAndRetainsPageCheckpoint()
    try testMissingJobCannotValidate()
    print("PASS 3 TranslatorStore pipeline tests")
  }

  private static func testInsertedJobValidatesAgainstPinnedInputs() throws {
    let fixture = try Fixture()
    let job = try fixture.insertJob(id: "job-valid")
    let model = try ModelCatalog.get(ModelCatalog.recommendedId)

    try fixture.store.validateJobSnapshot("job-valid", model: model)
    try expect(job["status"] as? String == "queued", "new job should start queued")
    try expect((job["pipelineVersion"] as? Int64) == 2, "new job should pin pipeline version 2")
    try expect((job["modelRevision"] as? String) == model.revision, "new job should pin catalog revision")
  }

  private static func testOldPipelineRejectsResumeAndRetainsPageCheckpoint() throws {
    let fixture = try Fixture()
    _ = try fixture.insertJob(id: "job-stale")
    let model = try ModelCatalog.get(ModelCatalog.recommendedId)
    try fixture.store.validateJobSnapshot("job-stale", model: model)

    try fixture.store.savePage(jobId: "job-stale", index: 0, status: "translated", blocks: "[]")
    try fixture.store.execute("UPDATE jobs SET pipeline_version=1 WHERE id=?", ["job-stale"])

    try expectThrows("old processing version must reject checkpoint resume") {
      try fixture.store.validateJobSnapshot("job-stale", model: model)
    }
    let rows = try fixture.store.pageRows("job-stale")
    try expect(rows.count == 1, "the saved page row should remain after snapshot rejection")
    try expect((rows.first?["index"] as? Int64) == 0, "the retained checkpoint should refer to page zero")
    try expect(rows.first?["status"] as? String == "translated", "the retained page status should stay intact")
  }

  private static func testMissingJobCannotValidate() throws {
    let fixture = try Fixture()
    let model = try ModelCatalog.get(ModelCatalog.recommendedId)
    try expectThrows("a missing job must not validate") {
      try fixture.store.validateJobSnapshot("missing", model: model)
    }
  }

  private static func expect(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else { throw TestFailure(description: message) }
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

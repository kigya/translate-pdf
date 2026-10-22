import XCTest
import UIKit
import BackgroundTasks
import UserNotifications
import React
import ObjectiveC
@testable import TranslatePDF
@testable import TranslateCore

final class NativeCoverageTests: XCTestCase {
  // These helpers wait for the actual RN promise callback; they never replace
  // the store, bridge, native queue or platform callbacks with test doubles.
  private func resolved(_ invoke: (@escaping RCTPromiseResolveBlock, @escaping RCTPromiseRejectBlock) -> Void,
                        file: StaticString = #filePath, line: UInt = #line) throws -> Any? {
    let completed = expectation(description: "native promise resolves")
    var result: Any?
    var failure: Error?
    invoke({ value in result = value; completed.fulfill() }, { code, message, error in
      failure = error ?? NSError(domain: code ?? "NativePromise", code: 1, userInfo: [NSLocalizedDescriptionKey: message ?? code ?? "Native promise rejected"])
      completed.fulfill()
    })
    wait(for: [completed], timeout: 15)
    if let failure { XCTFail("Unexpected rejection: \(failure)", file: file, line: line); throw failure }
    return result
  }

  private func rejected(_ code: String,
                        _ invoke: (@escaping RCTPromiseResolveBlock, @escaping RCTPromiseRejectBlock) -> Void,
                        file: StaticString = #filePath, line: UInt = #line) {
    let completed = expectation(description: "native promise rejects \(code)")
    invoke({ _ in XCTFail("Expected \(code) rejection", file: file, line: line); completed.fulfill() }, { actual, message, _ in
      XCTAssertEqual(actual, code, file: file, line: line)
      XCTAssertFalse(message?.isEmpty ?? true, file: file, line: line)
      completed.fulfill()
    })
    wait(for: [completed], timeout: 15)
  }

  private func decoded(_ value: Any?) throws -> [String: Any] {
    let string = try XCTUnwrap(value as? String)
    return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(string.utf8)) as? [String: Any])
  }

  private func storedZip(_ entries: [(String, String)]) -> Data {
    func u16(_ value: UInt16, into data: inout Data) {
      data.append(UInt8(value & 0xff)); data.append(UInt8(value >> 8))
    }
    func u32(_ value: UInt32, into data: inout Data) {
      u16(UInt16(value & 0xffff), into: &data); u16(UInt16(value >> 16), into: &data)
    }
    func crc32(_ data: Data) -> UInt32 {
      var crc: UInt32 = 0xffff_ffff
      for byte in data {
        crc ^= UInt32(byte)
        for _ in 0..<8 { crc = (crc & 1) == 1 ? (crc >> 1) ^ 0xedb8_8320 : crc >> 1 }
      }
      return ~crc
    }
    var result = Data()
    var central = Data()
    for (name, text) in entries {
      let filename = Data(name.utf8), contents = Data(text.utf8)
      let checksum = crc32(contents), offset = UInt32(result.count)
      u32(0x0403_4b50, into: &result); u16(20, into: &result); u16(0, into: &result)
      u16(0, into: &result); u16(0, into: &result); u16(0, into: &result)
      u32(checksum, into: &result); u32(UInt32(contents.count), into: &result); u32(UInt32(contents.count), into: &result)
      u16(UInt16(filename.count), into: &result); u16(0, into: &result); result.append(filename); result.append(contents)

      u32(0x0201_4b50, into: &central); u16(20, into: &central); u16(20, into: &central)
      u16(0, into: &central); u16(0, into: &central); u16(0, into: &central); u16(0, into: &central)
      u32(checksum, into: &central); u32(UInt32(contents.count), into: &central); u32(UInt32(contents.count), into: &central)
      u16(UInt16(filename.count), into: &central); u16(0, into: &central); u16(0, into: &central)
      u16(0, into: &central); u16(0, into: &central); u32(0, into: &central); u32(offset, into: &central)
      central.append(filename)
    }
    let centralOffset = UInt32(result.count)
    result.append(central)
    u32(0x0605_4b50, into: &result); u16(0, into: &result); u16(0, into: &result)
    u16(UInt16(entries.count), into: &result); u16(UInt16(entries.count), into: &result)
    u32(UInt32(central.count), into: &result); u32(centralOffset, into: &result); u16(0, into: &result)
    return result
  }

  private func terminalJob(_ id: String, store: TranslatorStore, timeout: TimeInterval = 240) throws -> [String: Any] {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
      if let row = try store.job(id), let status = row["status"] as? String,
         ["completed", "partial", "paused", "failed", "canceled"].contains(status) { return row }
      RunLoop.current.run(until: Date().addingTimeInterval(0.05))
    }
    XCTFail("The real native job did not reach a durable terminal state")
    throw NSError(domain: "NativePipelineTests", code: 1)
  }

  func testActualPinnedHy18PipelineVerifiesExportAndRebuildsCorruption() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let model = try ModelCatalog.get(ModelCatalog.compactId)
    // The orchestrated local-only fixture copy must exist and match the full
    // production pin. Missing model data is a failure, never an optional skip.
    XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: model.fileURL.path)[.size] as? Int64, 1_133_080_448)
    XCTAssertEqual(try sha256(at: model.fileURL), "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699")
    let id = "coverage-inference-\(UUID().uuidString)"
    let source = AppPaths.documents.appendingPathComponent(id + ".pdf")
    var jobID: String?
    defer {
      TranslateCoreBridge.releaseModel()
      if let jobID {
        try? store.deleteExport(jobID)
        try? store.deleteJob(jobID)
        for url in [AppPaths.exports.appendingPathComponent(jobID + ".pdf"), AppPaths.exports.appendingPathComponent(jobID + ".pending.pdf"), AppPaths.pages.appendingPathComponent(jobID), AppPaths.pages.appendingPathComponent("export-" + jobID)] { try? FileManager.default.removeItem(at: url) }
      }
      try? store.deleteDocument(id)
      try? FileManager.default.removeItem(at: source)
    }
    try UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 400, height: 300)).writePDF(to: source) { context in
      context.beginPage()
      ("Hello." as NSString).draw(at: CGPoint(x: 30, y: 30), withAttributes: [.font: UIFont.systemFont(ofSize: 20)])
    }
    _ = try store.saveDocument(id: id, name: "Actual pipeline.pdf", path: source.path, pages: 1, sourceSHA256: sha256(at: source))
    let module = PdfTranslatorModule()
    let input = try jsonString(["documentId": id, "modelId": model.id, "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false])
    let started = try decoded(resolved { module.startJob(input, resolver: $0, rejecter: $1) })
    let runningID = try XCTUnwrap(started["id"] as? String)
    jobID = runningID
    let finished = try terminalJob(runningID, store: store)
    XCTAssertTrue(["completed", "partial"].contains(finished["status"] as? String ?? ""), "Actual inference failed: \(finished)")
    XCTAssertEqual(finished["completedPages"] as? Int64, 1)
    let page = try XCTUnwrap(try store.page(runningID, index: 0))
    let blocks = try XCTUnwrap(page["blocks"] as? [[String: Any]])
    XCTAssertTrue(blocks.contains { !(($0["translatedText"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }, "A real model must have produced text; this is not a translation-quality assertion")
    let record = try XCTUnwrap(try store.export(runningID))
    let exported = URL(fileURLWithPath: try XCTUnwrap(record["path"] as? String))
    XCTAssertEqual(try sha256(at: exported), record["sha256"] as? String)
    XCTAssertEqual(try decoded(TranslateCoreBridge.inspect(exported.path))["pageCount"] as? Int, 1)
    let preview = try decoded(resolved { module.previewJob(runningID, resolver: $0, rejecter: $1) })
    XCTAssertEqual(preview["id"] as? String, runningID)
    let thumbnail = try XCTUnwrap(try resolved { module.renderTranslatedPage(runningID, pageIndex: 0, width: 128, resolver: $0, rejecter: $1) } as? String)
    XCTAssertTrue(FileManager.default.fileExists(atPath: try XCTUnwrap(URL(string: thumbnail)).path))
    XCTAssertEqual(try resolved { module.renderTranslatedPage(runningID, pageIndex: 0, width: 128, resolver: $0, rejecter: $1) } as? String, thumbnail)
    defer { if let url = URL(string: thumbnail) { try? FileManager.default.removeItem(at: url) } }

    // Actual file corruption must invalidate the verified result and retain the
    // checkpoint. Resuming assembles real saved pages without another inference.
    try Data("damaged output".utf8).write(to: exported)
    rejected("PREVIEW_FAILED") { module.previewJob(runningID, resolver: $0, rejecter: $1) }
    XCTAssertEqual(try store.job(runningID)?["status"] as? String, "paused")
    XCTAssertNil(try store.export(runningID))
    XCTAssertEqual(try store.pageRows(runningID).count, 1)
    _ = try resolved { module.resumeJob(runningID, resolver: $0, rejecter: $1) }
    let rebuilt = try terminalJob(runningID, store: store)
    XCTAssertTrue(["completed", "partial"].contains(rebuilt["status"] as? String ?? ""), "Saved page assembly failed: \(rebuilt)")
    XCTAssertEqual(try decoded(TranslateCoreBridge.inspect(exported.path))["pageCount"] as? Int, 1)
    _ = try resolved { module.cancelJob(runningID, resolver: $0, rejecter: $1) }
    XCTAssertTrue(["completed", "partial"].contains(try store.job(runningID)?["status"] as? String ?? ""), "A late cancel must not overwrite an already committed result")
    // Reopening a valid result must preserve it, while real corruption must be
    // recovered as a pause rather than a completed job with an invalid PDF.
    _ = PdfTranslatorModule()
    XCTAssertTrue(["completed", "partial"].contains(try store.job(runningID)?["status"] as? String ?? ""))
    try Data("corrupt across restart".utf8).write(to: exported)
    let restored = PdfTranslatorModule()
    XCTAssertEqual(try store.job(runningID)?["status"] as? String, "paused")
    _ = try resolved { restored.resumeJob(runningID, resolver: $0, rejecter: $1) }
    XCTAssertTrue(["completed", "partial"].contains(try terminalJob(runningID, store: store)["status"] as? String ?? ""))
  }

  func testBridgeRejectsUnknownResourcesAndMalformedRequests() throws {
    let module = PdfTranslatorModule()
    let missing = "coverage-missing-\(UUID().uuidString)"
    rejected("MODEL_DOWNLOAD_START_FAILED") { module.startModelDownload(missing, resolver: $0, rejecter: $1) }
    rejected("MODEL_DOWNLOAD_PAUSE_FAILED") { module.pauseModelDownload(missing, resolver: $0, rejecter: $1) }
    rejected("MODEL_DELETE_FAILED") { module.deleteModel(missing, resolver: $0, rejecter: $1) }
    rejected("JOB_START_FAILED") { module.startJob("[]", resolver: $0, rejecter: $1) }
    rejected("JOB_START_FAILED") { module.startJob("{", resolver: $0, rejecter: $1) }
    rejected("JOB_START_FAILED") { module.startJob("{\"documentId\":\"missing\",\"modelId\":\"missing\"}", resolver: $0, rejecter: $1) }
    rejected("JOB_START_FAILED") { module.startJob("{\"documentId\":\"missing\",\"modelId\":\"missing\",\"sourceLanguage\":\"en\",\"targetLanguage\":\"en\"}", resolver: $0, rejecter: $1) }
    rejected("JOB_START_FAILED") { module.startJob("{\"documentId\":\"missing\",\"modelId\":\"missing\",\"sourceLanguage\":\"en\",\"targetLanguage\":\"ru\"}", resolver: $0, rejecter: $1) }
    rejected("JOB_PAUSE_FAILED") { module.pauseJob(missing, resolver: $0, rejecter: $1) }
    rejected("JOB_RESUME_FAILED") { module.resumeJob(missing, resolver: $0, rejecter: $1) }
    rejected("JOB_CANCEL_FAILED") { module.cancelJob(missing, resolver: $0, rejecter: $1) }
    rejected("JOB_DELETE_FAILED") { module.deleteJob(missing, resolver: $0, rejecter: $1) }
    rejected("JOB_READ_FAILED") { module.getJob(missing, pageIndex: 0, resolver: $0, rejecter: $1) }
    rejected("THUMBNAIL_FAILED") { module.renderPage(missing, pageIndex: 0, width: 128, resolver: $0, rejecter: $1) }
    rejected("THUMBNAIL_FAILED") { module.renderPage(missing, pageIndex: -1, width: 128, resolver: $0, rejecter: $1) }
    rejected("THUMBNAIL_FAILED") { module.renderPage(missing, pageIndex: 0, width: 63, resolver: $0, rejecter: $1) }
    rejected("TRANSLATED_THUMBNAIL_FAILED") { module.renderTranslatedPage(missing, pageIndex: 0, width: 128, resolver: $0, rejecter: $1) }
    rejected("TRANSLATED_THUMBNAIL_FAILED") { module.renderTranslatedPage(missing, pageIndex: -1, width: 128, resolver: $0, rejecter: $1) }
    rejected("TRANSLATED_THUMBNAIL_FAILED") { module.renderTranslatedPage(missing, pageIndex: 0, width: 2049, resolver: $0, rejecter: $1) }
    rejected("PREVIEW_FAILED") { module.previewJob(missing, resolver: $0, rejecter: $1) }
    rejected("EXPORT_FAILED") { module.exportJob(missing, resolver: $0, rejecter: $1) }
    rejected("SHARE_FAILED") { module.shareExport(missing, resolver: $0, rejecter: $1) }
    rejected("OPEN_PDF_FAILED") { module.openPdf(missing, resolver: $0, rejecter: $1) }
  }

  func testCoordinatorReadyPauseDeleteAndStaleCallbacksUseDurableState() throws {
    let directory = AppPaths.support.appendingPathComponent("coverage-download-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = try TranslatorStore(directory: directory)
    let coordinator = ModelDownloadCoordinator(store: store)
    let id = "coverage-\(UUID().uuidString)"
    let payload = Data("a tiny pinned test model payload; never used for inference".utf8)
    let file = AppPaths.models.appendingPathComponent(id + ".gguf")
    try payload.write(to: file)
    let model = ModelDefinition(id: id, label: "Test fixture", repo: "test/fixture", fileName: "fixture.gguf", revision: "fixture", bytes: Int64(payload.count), sha256: try sha256(at: file), qualityBenchmarked: false, translationEnabled: true, tier: "compact", profile: "hy-mt2", requiredMemoryBytes: 1, licenseUrl: "https://example.invalid/license", supportsGlossary: true)
    defer {
      for url in [model.fileURL, model.partialURL, model.resumeURL] { try? FileManager.default.removeItem(at: url) }
    }
    XCTAssertTrue(try coordinator.isReady(model))
    let ready = try coordinator.start(model)
    XCTAssertEqual(ready["status"] as? String, "ready")
    XCTAssertEqual(try store.modelState(id)?["status"] as? String, "ready")
    let paused = try coordinator.pause(model)
    XCTAssertEqual(paused["status"] as? String, "paused")
    try Data("partial".utf8).write(to: model.partialURL)
    try Data("resume".utf8).write(to: model.resumeURL)
    XCTAssertEqual(coordinator.partialLength(model), 7)
    try coordinator.delete(model)
    XCTAssertNil(try store.modelState(id))
    for url in [model.fileURL, model.partialURL, model.resumeURL] { XCTAssertFalse(FileManager.default.fileExists(atPath: url.path)) }
    XCTAssertFalse(try coordinator.isReady(model))
    try Data(repeating: 0, count: payload.count).write(to: model.fileURL)
    XCTAssertFalse(try coordinator.isReady(model), "same-size corrupt payload must fail pinned SHA")
    try Data([0]).write(to: model.fileURL)
    XCTAssertFalse(try coordinator.isReady(model), "truncated payload must fail size validation")

    // Unresumed ephemeral tasks perform no network IO. Invoke delegate methods
    // with absent/unknown/stale generations and require the durable row intact.
    let session = URLSession(configuration: .ephemeral)
    defer { session.invalidateAndCancel() }
    let known = try ModelCatalog.get(ModelCatalog.compactId)
    try store.updateModel(known.id, status: "ready", bytes: 42)
    for description in [nil, "unknown", known.id] as [String?] {
      let task = session.downloadTask(with: URL(string: "https://example.invalid/not-started")!)
      task.taskDescription = description
      coordinator.urlSession(session, downloadTask: task, didWriteData: 2_000_000, totalBytesWritten: 2_000_000, totalBytesExpectedToWrite: known.bytes)
      coordinator.urlSession(session, downloadTask: task, didFinishDownloadingTo: model.fileURL)
      coordinator.urlSession(session, task: task, didCompleteWithError: NSError(domain: NSURLErrorDomain, code: NSURLErrorCancelled))
      XCTAssertEqual(try store.modelState(known.id)?["status"] as? String, "ready")
      XCTAssertEqual(try store.modelState(known.id)?["downloaded_bytes"] as? Int64, 42)
      XCTAssertTrue(FileManager.default.fileExists(atPath: model.fileURL.path), "a stale callback cannot move its alleged temporary file")
    }
    let completed = expectation(description: "coordinator finishes restored background session")
    let observer = NotificationCenter.default.addObserver(forName: NSNotification.Name("TranslatePDFBackgroundSessionDidFinishEvents"), object: nil, queue: nil) { _ in completed.fulfill() }
    defer { NotificationCenter.default.removeObserver(observer) }
    coordinator.urlSessionDidFinishEvents(forBackgroundURLSession: session)
    wait(for: [completed], timeout: 3)
  }

  func testOnboardingValidationPersistsAcrossModuleInstances() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let oldVersion = try store.setting("onboarding_version", fallback: "1")
    let oldPage = try store.setting("onboarding_page", fallback: "0")
    let oldDone = try store.setting("onboarding_done", fallback: "false")
    defer {
      try? store.setSetting("onboarding_version", oldVersion)
      try? store.setSetting("onboarding_page", oldPage)
      try? store.setSetting("onboarding_done", oldDone)
    }
    let module = PdfTranslatorModule()
    for invalid in ["[]", "{", "{}", "{\"version\":0,\"page\":0,\"done\":true}", "{\"version\":1,\"page\":4,\"done\":false}"] {
      rejected("ONBOARDING_SAVE_FAILED") { module.setOnboarding(invalid, resolver: $0, rejecter: $1) }
    }
    for done in [false, true] {
      let response = try decoded(resolved { module.setOnboarding("{\"version\":2,\"page\":3,\"done\":\(done)}", resolver: $0, rejecter: $1) })
      XCTAssertEqual(response["done"] as? Bool, done)
      let reopened = PdfTranslatorModule()
      let state = try decoded(resolved { reopened.bootstrap($0, rejecter: $1) })
      let onboarding = try XCTUnwrap(state["onboarding"] as? [String: Any])
      XCTAssertEqual(onboarding["version"] as? Int, 2)
      XCTAssertEqual(onboarding["page"] as? Int, 3)
      XCTAssertEqual(onboarding["done"] as? Bool, done)
    }
  }

  func testSettingsAcceptSupportedChoicesAndRejectInvalidInputs() throws {
    let module = PdfTranslatorModule()
    for invalid in ["[]", "{", "{\"language\":\"../en\"}", "{\"selectedModelId\":\"unknown\"}", "{\"sourceLanguage\":\"xx\"}", "{\"sourceLanguage\":\"en\",\"targetLanguage\":\"en\"}"] {
      rejected("SETTINGS_SAVE_FAILED") { module.setSettings(invalid, resolver: $0, rejecter: $1) }
    }
    for theme in ["system", "light", "dark"] {
      for seen in [true, false] {
        let input = "{\"theme\":\"\(theme)\",\"language\":\"zh-Hans\",\"notificationExplanationShown\":\(seen),\"sourceLanguage\":\"fr\",\"targetLanguage\":\"de\",\"selectedModelId\":\"\(ModelCatalog.recommendedId)\"}"
        let result = try decoded(resolved { module.setSettings(input, resolver: $0, rejecter: $1) })
        XCTAssertEqual(result["theme"] as? String, theme)
        XCTAssertEqual(result["language"] as? String, "zh-Hans")
        XCTAssertEqual(result["notificationExplanationShown"] as? Bool, seen)
        XCTAssertEqual(result["sourceLanguage"] as? String, "fr")
        XCTAssertEqual(result["targetLanguage"] as? String, "de")
      }
    }
    _ = try resolved { module.setSettings("{\"theme\":\"system\",\"language\":\"system\",\"sourceLanguage\":\"en\",\"targetLanguage\":\"ru\"}", resolver: $0, rejecter: $1) }
    _ = try resolved { module.setSettings("{}", resolver: $0, rejecter: $1) }
  }

  func testGlossaryBridgeSavesUpdatesAndDeletesPairScopedEntry() throws {
    let module = PdfTranslatorModule()
    let id = "coverage-glossary-\(UUID().uuidString)"
    defer { try? TranslatorStore(directory: AppPaths.support).deleteGlossary(id) }
    for invalid in ["{", "[]", "{}", "{\"id\":\"x\",\"source\":\" \",\"target\":\"term\",\"sourceLanguage\":\"en\",\"targetLanguage\":\"ru\"}", "{\"id\":\"x\",\"source\":\"term\",\"target\":\" \",\"sourceLanguage\":\"en\",\"targetLanguage\":\"ru\"}"] {
      rejected("GLOSSARY_SAVE_FAILED") { module.saveGlossaryEntry(invalid, resolver: $0, rejecter: $1) }
    }
    for target in ["термин", "новый термин"] {
      let input = "{\"id\":\"\(id)\",\"source\":\"term\",\"target\":\"\(target)\",\"sourceLanguage\":\"en\",\"targetLanguage\":\"ru\"}"
      let value = try decoded(resolved { module.saveGlossaryEntry(input, resolver: $0, rejecter: $1) })
      XCTAssertEqual(value["id"] as? String, id)
      let rows = try TranslatorStore(directory: AppPaths.support).glossary()
      XCTAssertEqual(rows.first { $0["id"] as? String == id }?["target"] as? String, target)
    }
    _ = try resolved { module.deleteGlossaryEntry(id, resolver: $0, rejecter: $1) }
    XCTAssertFalse(try TranslatorStore(directory: AppPaths.support).glossary().contains { $0["id"] as? String == id })
  }

  func testLicenseBridgeReadsBundledNoticesAndBlocksPathTraversal() throws {
    let module = PdfTranslatorModule()
    let string = try XCTUnwrap(try resolved { module.getLicenseNotices($0, rejecter: $1) } as? String)
    let notices = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(string.utf8)) as? [[String: String]])
    XCTAssertGreaterThan(notices.count, 20)
    let ids = notices.compactMap { $0["id"] }
    XCTAssertEqual(ids.count, Set(ids).count)
    XCTAssertTrue(Set(ids).isSuperset(of: ["react", "react-native", "pdfium", "docx-preview", "hy-mt2-7b", "translategemma-4b"]))
    XCTAssertFalse(ids.contains { $0.contains("/") || $0.contains("..") || $0.contains("cpp-httplib") || $0.contains("new-app-screen") || $0.contains("iq3") || $0.contains("iq2") })
    let pdfiumNotice = try XCTUnwrap(try resolved { module.getLicenseNotice("pdfium", resolver: $0, rejecter: $1) } as? String)
    XCTAssertTrue(pdfiumNotice.contains("copyright © <year> The FreeType"))
    for notice in notices {
      let id = try XCTUnwrap(notice["id"])
      let text = try XCTUnwrap(try resolved { module.getLicenseNotice(id, resolver: $0, rejecter: $1) } as? String)
      XCTAssertFalse(text.isEmpty)
    }
    for invalid in ["", "../Info.plist", "a/b", "a\\b", "unknown.txt"] {
      rejected("LICENSE_READ_FAILED") { module.getLicenseNotice(invalid, resolver: $0, rejecter: $1) }
    }
  }

  func testMyMemoryTranslatorHandlesSuccessQuotaAndInvalidResponses() throws {
    MyMemoryURLProtocol.configure(status: 200, body: #"{"responseStatus":200,"responseData":{"translatedText":"<g id=\"1\">bonjour</g> &amp; salut"}}"#)
    let translated = try MyMemoryTranslator(protocolClasses: [MyMemoryURLProtocol.self]).translate("  hello  ", source: "en", target: "fr")
    XCTAssertEqual(translated, "  bonjour & salut  ")

    MyMemoryURLProtocol.configure(status: 429, body: #"{"responseStatus":429,"responseDetails":"quota limit"}"#)
    XCTAssertThrowsError(try MyMemoryTranslator(protocolClasses: [MyMemoryURLProtocol.self]).translate("hello", source: "en", target: "fr")) {
      XCTAssertEqual($0 as? MyMemoryTranslator.Failure, .quota)
    }

    MyMemoryURLProtocol.configure(status: 503, body: "not-json")
    XCTAssertThrowsError(try MyMemoryTranslator(protocolClasses: [MyMemoryURLProtocol.self]).translate("hello", source: "en", target: "fr")) {
      XCTAssertEqual($0 as? MyMemoryTranslator.Failure, .unavailable)
    }

    MyMemoryURLProtocol.configure(status: 200, body: #"{"responseStatus":200,"responseData":{}}"#)
    XCTAssertThrowsError(try MyMemoryTranslator(protocolClasses: [MyMemoryURLProtocol.self]).translate("hello", source: "en", target: "fr")) {
      XCTAssertEqual($0 as? MyMemoryTranslator.Failure, .invalidResponse)
    }
  }

  func testMyMemoryTranslatorRejectsChunkedOversizedResponseBeforeUnboundedAccumulation() throws {
    MyMemoryURLProtocol.configure(status: 200, body: String(repeating: "x", count: 256_001), contentLength: "1")
    XCTAssertThrowsError(try MyMemoryTranslator(protocolClasses: [MyMemoryURLProtocol.self]).translate("hello", source: "en", target: "fr")) {
      XCTAssertEqual($0 as? MyMemoryTranslator.Failure, .invalidResponse)
    }
  }

  func testMyMemoryChunkBoundariesFailuresAndRedirectAllowlist() throws {
    XCTAssertEqual(try MyMemoryTranslator.chunks("a.\r\n你好"), ["a.", "\r\n", "你好"])
    XCTAssertEqual(try MyMemoryTranslator.chunks("a.b", byteLimit: 2), ["a.", "b"])
    let bounded = try MyMemoryTranslator.chunks(String(repeating: "x", count: 501))
    XCTAssertEqual(bounded.map(\.utf8.count), [500, 1])
    XCTAssertEqual(try MyMemoryTranslator.chunks("ignored", byteLimit: 0), [])
    XCTAssertThrowsError(try MyMemoryTranslator.chunks("🙂", byteLimit: 3)) {
      XCTAssertEqual($0 as? MyMemoryTranslator.Failure, .oversizedCharacter)
    }
    for failure in [MyMemoryTranslator.Failure.unavailable, .quota, .invalidResponse, .canceled, .oversizedCharacter] {
      XCTAssertFalse(failure.errorDescription?.isEmpty ?? true)
    }

    let translator = MyMemoryTranslator(protocolClasses: [MyMemoryURLProtocol.self])
    translator.cancel() // Cancelling before any request is a safe no-op.
    let session = URLSession(configuration: .ephemeral)
    defer { session.invalidateAndCancel() }
    let task = session.dataTask(with: URL(string: "https://example.invalid/not-started")!)
    let allowedURL = URL(string: "https://api.mymemory.translated.net/redirect")!
    let blockedURL = URL(string: "https://api.mymemory.translated.net.evil.invalid/redirect")!
    let response = try XCTUnwrap(HTTPURLResponse(url: task.originalRequest!.url!, statusCode: 302, httpVersion: "HTTP/1.1", headerFields: nil))
    var allowed: URLRequest?
    translator.urlSession(session, task: task, willPerformHTTPRedirection: response,
                          newRequest: URLRequest(url: allowedURL)) { allowed = $0 }
    XCTAssertEqual(allowed?.url, allowedURL)
    var blocked: URLRequest?
    translator.urlSession(session, task: task, willPerformHTTPRedirection: response,
                          newRequest: URLRequest(url: blockedURL)) { blocked = $0 }
    XCTAssertNil(blocked)
  }

  func testMyMemoryRejectsDeclaredOversizeAndStaleCallbacksAndCancelsActiveRequest() throws {
    MyMemoryURLProtocol.configure(status: 200,
                                  body: #"{"responseStatus":200,"responseData":{"translatedText":"ok"}}"#,
                                  contentLength: "256001")
    XCTAssertThrowsError(try MyMemoryTranslator(protocolClasses: [MyMemoryURLProtocol.self]).translate("hello", source: "en", target: "fr")) {
      XCTAssertEqual($0 as? MyMemoryTranslator.Failure, .invalidResponse)
    }

    let translator = MyMemoryTranslator(protocolClasses: [MyMemoryURLProtocol.self])
    let session = URLSession(configuration: .ephemeral)
    defer { session.invalidateAndCancel() }
    let stale = session.dataTask(with: URL(string: "https://api.mymemory.translated.net/not-started")!)
    var disposition: URLSession.ResponseDisposition?
    translator.urlSession(session, dataTask: stale,
                          didReceive: URLResponse(url: stale.originalRequest!.url!, mimeType: nil,
                                                  expectedContentLength: -1, textEncodingName: nil)) { disposition = $0 }
    XCTAssertEqual(disposition, .cancel)
    translator.urlSession(session, dataTask: stale, didReceive: Data("late".utf8))
    translator.urlSession(session, task: stale, didCompleteWithError: nil)

    MyMemoryURLProtocol.configureHeld()
    let canceled = expectation(description: "active MyMemory request completes after cancel")
    var outcome: Result<String, Error>?
    DispatchQueue.global().async {
      do { outcome = .success(try translator.translate("hello", source: "en", target: "fr")) }
      catch { outcome = .failure(error) }
      canceled.fulfill()
    }
    XCTAssertTrue(MyMemoryURLProtocol.waitForHeldRequest(timeout: 3), "the URLProtocol must observe the request before cancellation")
    translator.cancel()
    wait(for: [canceled], timeout: 3)
    guard case .failure(let error) = outcome else {
      XCTFail("A canceled online request must fail rather than return a translation")
      return
    }
    XCTAssertEqual(error as? MyMemoryTranslator.Failure, .canceled)
  }

  func testMyMemoryParserCoversQuotaDetailsAndInvalidUnicodeEntities() throws {
    guard case .failure(let invalid) = MyMemoryTranslator.parseResponse(Data("not json".utf8)) else {
      XCTFail("Malformed provider JSON must fail")
      return
    }
    XCTAssertEqual(invalid, .invalidResponse)
    let quotaResponses = [
      #"{"responseStatus":200,"quotaFinished":"true"}"#,
      #"{"responseStatus":200,"quotaFinished":true}"#,
      #"{"responseStatus":200,"responseDetails":"limit reached"}"#,
      #"{"responseStatus":200,"responseDetails":"all available free translation requests"}"#,
      #"{"responseStatus":200,"responseDetails":"daily request limit"}"#,
    ]
    for body in quotaResponses {
      guard case .failure(let error) = MyMemoryTranslator.parseResponse(Data(body.utf8)) else {
        XCTFail("Expected quota result from \(body)")
        continue
      }
      XCTAssertEqual(error, .quota)
    }
    guard case .failure(let blank) = MyMemoryTranslator.parseResponse(
      Data(#"{"responseStatus":200,"responseData":{"translatedText":"   "}}"#.utf8)) else {
      XCTFail("Blank provider translations must fail")
      return
    }
    XCTAssertEqual(blank, .invalidResponse)
    guard case .success(let decoded) = MyMemoryTranslator.parseResponse(
      Data(#"{"responseStatus":200,"responseData":{"translatedText":"&#169; &#xD800; &#99999999;"}}"#.utf8)) else {
      XCTFail("Expected successful HTML entity decoding")
      return
    }
    XCTAssertEqual(decoded, "© &#xD800; &#99999999;")
  }

  func testModelCatalogLanguagesRecommendationsAndResourceBoundaries() throws {
    let expected = [
      "ru": ("Russian", "ru-RU"), "zh-Hans": ("Chinese (Simplified)", "zh-Hans"),
      "fr": ("French", "fr-FR"), "en": ("English", "en-US"),
      "de": ("German", "de-DE"), "es": ("Spanish", "es-ES"),
    ]
    XCTAssertEqual(Set(TranslationLanguage.allCases.map(\.rawValue)), Set(expected.keys))
    for language in TranslationLanguage.allCases {
      XCTAssertEqual(language.displayName, expected[language.rawValue]?.0)
      XCTAssertEqual(language.visionLocale, expected[language.rawValue]?.1)
    }
    for source in TranslationLanguage.allCases {
      for target in TranslationLanguage.allCases where source != target {
        let pair = try TranslationLanguage.pair(source: source.rawValue, target: target.rawValue)
        XCTAssertEqual(pair.0, source); XCTAssertEqual(pair.1, target)
      }
      XCTAssertThrowsError(try TranslationLanguage.pair(source: source.rawValue, target: source.rawValue))
    }
    XCTAssertThrowsError(try TranslationLanguage.pair(source: "unknown", target: "en"))

    for model in ModelCatalog.models {
      XCTAssertTrue(model.url.absoluteString.contains(model.revision))
      XCTAssertTrue(model.url.lastPathComponent.contains(model.fileName))
      XCTAssertEqual(try ModelCatalog.get(model.id).id, model.id)
      let snapshot = model.snapshot(status: "ready", downloadedBytes: model.bytes)
      XCTAssertEqual(snapshot["id"] as? String, model.id)
      XCTAssertEqual(snapshot["status"] as? String, "ready")
      XCTAssertNotNil(snapshot["resourceEstimateEligible"] as? Bool)
    }
    XCTAssertThrowsError(try ModelCatalog.get("missing-model"))

    func eligible(_ disk: Bool = true) -> ModelResourceAssessment {
      ModelResourceAssessment(memoryEstimateEligible: true, diskEstimateEligible: disk,
                              resourceEstimateEligible: disk, reason: disk ? "available" : "disk",
                              requiredDiskBytes: 0)
    }
    XCTAssertEqual(ModelCatalog.recommended(from: [:]).id, ModelCatalog.compactId)
    XCTAssertEqual(ModelCatalog.recommended(from: Dictionary(uniqueKeysWithValues: ModelCatalog.models.map { ($0.id, eligible()) })).id, ModelCatalog.qualityId)
    let withoutQuality = Dictionary(uniqueKeysWithValues: ModelCatalog.models.filter { $0.id != ModelCatalog.qualityId }.map { ($0.id, eligible()) })
    XCTAssertEqual(ModelCatalog.recommended(from: withoutQuality).id, ModelCatalog.balancedId)
    let compactOnly = [ModelCatalog.compactId: eligible(), ModelCatalog.qualityId: eligible(false)]
    XCTAssertEqual(ModelCatalog.recommended(from: compactOnly).id, ModelCatalog.compactId)

    let model = try ModelCatalog.get(ModelCatalog.compactId)
    let mib: UInt64 = 1024 * 1024
    let total: UInt64 = 8 * 1024 * mib
    let reserve = MemoryPolicy.reserve(total: total)
    XCTAssertEqual(MemoryPolicy.reserve(total: 0), 512 * mib)
    XCTAssertEqual(reserve, 15 * total / 100)
    let requiredMemory = reserve + model.requiredMemoryBytes
    let requiredDisk = UInt64(model.bytes) + 512 * mib
    let atBoundary = MemoryPolicy.assess(model, status: "not-installed", downloadedBytes: 0,
                                         total: total, available: requiredMemory,
                                         availableDisk: requiredDisk, systemLowMemory: false)
    XCTAssertTrue(atBoundary.resourceEstimateEligible)
    XCTAssertEqual(atBoundary.reason, "available")
    XCTAssertEqual(atBoundary.requiredDiskBytes, requiredDisk)
    XCTAssertEqual(MemoryPolicy.assess(model, status: "downloading", downloadedBytes: model.bytes / 2,
                                       total: total, available: requiredMemory, availableDisk: requiredDisk,
                                       systemLowMemory: false).requiredDiskBytes,
                   UInt64(model.bytes - model.bytes / 2) + 512 * mib)
    XCTAssertEqual(MemoryPolicy.assess(model, status: "downloading", downloadedBytes: model.bytes + 1,
                                       total: total, available: requiredMemory, availableDisk: 512 * mib,
                                       systemLowMemory: false).requiredDiskBytes, 512 * mib)
    XCTAssertEqual(MemoryPolicy.assess(model, status: "ready", downloadedBytes: model.bytes,
                                       total: total, available: requiredMemory, availableDisk: 0,
                                       systemLowMemory: false).requiredDiskBytes, 0)
    XCTAssertEqual(MemoryPolicy.assess(model, status: "new", downloadedBytes: 0,
                                       total: total, available: requiredMemory - 1, availableDisk: requiredDisk,
                                       systemLowMemory: false).reason, "memory")
    XCTAssertEqual(MemoryPolicy.assess(model, status: "new", downloadedBytes: 0,
                                       total: total, available: requiredMemory, availableDisk: requiredDisk - 1,
                                       systemLowMemory: false).reason, "disk")
    XCTAssertEqual(MemoryPolicy.assess(model, status: "new", downloadedBytes: 0,
                                       total: total, available: requiredMemory, availableDisk: requiredDisk,
                                       systemLowMemory: true).reason, "lowMemory")
    let unsupported = ModelDefinition(id: model.id, label: model.label, repo: model.repo, fileName: model.fileName,
                                      revision: model.revision, bytes: model.bytes, sha256: model.sha256,
                                      qualityBenchmarked: model.qualityBenchmarked, translationEnabled: false,
                                      tier: model.tier, profile: model.profile, requiredMemoryBytes: model.requiredMemoryBytes,
                                      licenseUrl: model.licenseUrl, supportsGlossary: model.supportsGlossary)
    XCTAssertEqual(MemoryPolicy.assess(unsupported, status: "ready", downloadedBytes: 0,
                                       total: total, available: requiredMemory, availableDisk: requiredDisk,
                                       systemLowMemory: false).reason, "unsupported")
  }

  func testDocxPreviewRendererReportsMissingSourceWithoutLeakingResources() throws {
    let missing = FileManager.default.temporaryDirectory.appendingPathComponent("missing-preview-\(UUID().uuidString).docx")
    let cache = FileManager.default.temporaryDirectory.appendingPathComponent("preview-\(UUID().uuidString).pdf")
    let completed = expectation(description: "missing DOCX preview is rejected")
    DocxPreviewRenderer.render(source: missing, cacheURL: cache) { result in
      guard case .failure(let error) = result else { XCTFail("Missing DOCX source must fail before creating a WebView"); completed.fulfill(); return }
      XCTAssertEqual(error as? DocxPreviewRenderer.Failure, .source)
      XCTAssertFalse(FileManager.default.fileExists(atPath: cache.path))
      completed.fulfill()
    }
    wait(for: [completed], timeout: 5)
  }

  func testMemoryWarningIsReflectedInBootstrapWithoutStartingWork() throws {
    let module = PdfTranslatorModule()
    NotificationCenter.default.post(name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
    let state = try decoded(resolved { module.bootstrap($0, rejecter: $1) })
    XCTAssertEqual((state["device"] as? [String: Any])?["systemLowMemory"] as? Bool, true)
    NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    let foreground = try decoded(resolved { module.bootstrap($0, rejecter: $1) })
    XCTAssertEqual((foreground["device"] as? [String: Any])?["systemLowMemory"] as? Bool, false)
  }

  func testRealPdfBridgeRenderingAndDurableJobLifecycle() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let id = "coverage-pdf-\(UUID().uuidString)"
    let source = AppPaths.documents.appendingPathComponent(id + ".pdf")
    defer {
      try? store.execute("DELETE FROM documents WHERE id=?", [id])
      try? FileManager.default.removeItem(at: source)
      try? FileManager.default.removeItem(at: AppPaths.pages.appendingPathComponent(id))
    }
    let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 300, height: 400))
    try renderer.writePDF(to: source) { context in
      context.beginPage()
      ("Coverage document: valve 42" as NSString).draw(at: CGPoint(x: 20, y: 20), withAttributes: [.font: UIFont.systemFont(ofSize: 14)])
    }
    let inspected = try decoded(TranslateCoreBridge.inspect(source.path))
    XCTAssertEqual(inspected["pageCount"] as? Int, 1)
    let extracted = try decoded(TranslateCoreBridge.readPage(source.path, index: 0))
    XCTAssertNotNil(extracted["blocks"] as? [[String: Any]])
    XCTAssertNotNil(TranslateCoreBridge.renderPNG(source.path, index: 0, width: 128))
    XCTAssertNil(TranslateCoreBridge.renderPNG(source.path, index: 99, width: 128))
    _ = try store.saveDocument(id: id, name: "Coverage.pdf", path: source.path, pages: 1, sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: id, input: ["documentId": id, "modelId": ModelCatalog.recommendedId, "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 1)
    // Initializing a real module must recover a queued job to a durable pause.
    let module = PdfTranslatorModule()
    XCTAssertEqual(try store.job(id)?["status"] as? String, "paused")
    let thumbnail = try XCTUnwrap(try resolved { module.renderPage(id, pageIndex: 0, width: 128, resolver: $0, rejecter: $1) } as? String)
    XCTAssertTrue(FileManager.default.fileExists(atPath: try XCTUnwrap(URL(string: thumbnail)).path))
    let cached = try resolved { module.renderPage(id, pageIndex: 0, width: 128, resolver: $0, rejecter: $1) } as? String
    XCTAssertEqual(cached, thumbnail)
    defer { if let url = URL(string: thumbnail) { try? FileManager.default.removeItem(at: url) } }
    let read = try decoded(resolved { module.getJob(id, pageIndex: 0, resolver: $0, rejecter: $1) })
    XCTAssertTrue(read["selectedPage"] is NSNull)
    let outside = try decoded(resolved { module.getJob(id, pageIndex: -1, resolver: $0, rejecter: $1) })
    XCTAssertTrue(outside["selectedPage"] is NSNull)
    _ = try resolved { module.pauseJob(id, resolver: $0, rejecter: $1) }
    XCTAssertEqual(try store.job(id)?["status"] as? String, "paused")
    try store.execute("UPDATE jobs SET pipeline_version=3 WHERE id=?", [id])
    rejected("JOB_RESUME_FAILED") { module.resumeJob(id, resolver: $0, rejecter: $1) }
    try store.execute("UPDATE jobs SET pipeline_version=4 WHERE id=?", [id])
    _ = try resolved { module.cancelJob(id, resolver: $0, rejecter: $1) }
    XCTAssertEqual(try store.job(id)?["status"] as? String, "canceled")
    rejected("JOB_RESUME_FAILED") { module.resumeJob(id, resolver: $0, rejecter: $1) }
    try store.updateJob(id, status: "queued")
    rejected("JOB_DELETE_FAILED") { module.deleteJob(id, resolver: $0, rejecter: $1) }
    rejected("MODEL_DELETE_FAILED") { module.deleteModel(ModelCatalog.recommendedId, resolver: $0, rejecter: $1) }
    try store.updateJob(id, status: "paused")
    _ = try resolved { module.deleteJob(id, resolver: $0, rejecter: $1) }
    XCTAssertNil(try store.job(id))
  }

  func testCancelDuringVerifiedDocxFinalizationWinsAtomically() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let id = "coverage-docx-cancel-\(UUID().uuidString)"
    let source = AppPaths.documents.appendingPathComponent(id + ".docx")
    let jobID = "\(id)-job"
    let destination = AppPaths.exports.appendingPathComponent(jobID + ".docx")
    defer {
      try? store.deleteExport("\(jobID):docx")
      try? store.deleteJob(jobID)
      try? store.deleteDocument(id)
      try? FileManager.default.removeItem(at: source)
      try? FileManager.default.removeItem(at: destination)
      try? FileManager.default.removeItem(at: AppPaths.exports.appendingPathComponent(jobID + ".pending.docx"))
    }
    let ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    let sourceXml = "<w:document xmlns:w=\"\(ns)\"><w:body><w:p><w:r><w:t>Hello</w:t></w:r></w:p></w:body></w:document>"
    let package = storedZip([
      ("[Content_Types].xml", "<Types><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"),
      ("_rels/.rels", "<Relationships><Relationship Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"),
      ("word/document.xml", sourceXml)
    ])
    XCTAssertTrue(FileManager.default.createFile(atPath: source.path, contents: package))
    let inspected = try decoded(TranslateCoreBridge.inspectDocx(source.path))
    XCTAssertEqual(inspected["segmentCount"] as? Int, 1)
    _ = try store.saveDocument(id: id, name: "Cancel.docx", path: source.path, pages: 1, sourceSHA256: sha256(at: source), format: "docx")
    let model = try ModelCatalog.get(ModelCatalog.compactId)
    let input: [String: Any] = ["documentId": id, "modelId": model.id, "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false]
    _ = try store.addJob(id: jobID, input: input, pageCount: 1)
    let segment = try decoded(TranslateCoreBridge.readDocxSegment(source.path, index: 0))
    var blocks = try XCTUnwrap(segment["blocks"] as? [[String: Any]])
    XCTAssertEqual(blocks.count, 1)
    blocks[0]["translatedText"] = "Привет"
    blocks[0]["status"] = "translated"
    try store.savePage(jobId: jobID, index: 0, status: "completed", blocks: try jsonString(blocks))
    try store.updateJob(jobID, status: "paused", completed: 0)

    let module = PdfTranslatorModule()
    let cancelAcknowledged = DispatchSemaphore(value: 0)
    module.beforeFinalizationCommitForTesting = { phase in
      guard phase == "terminal" else { return }
      module.cancelJob(jobID, resolver: { _ in cancelAcknowledged.signal() }, rejecter: { _, _, _ in cancelAcknowledged.signal() })
      XCTAssertEqual(cancelAcknowledged.wait(timeout: .now() + 10), .success, "Cancellation must be accepted while verified DOCX output awaits terminal status commit")
    }
    _ = try resolved { module.resumeJob(jobID, resolver: $0, rejecter: $1) }
    let canceled = try terminalJob(jobID, store: store)
    module.beforeFinalizationCommitForTesting = nil
    XCTAssertEqual(canceled["status"] as? String, "canceled")
    XCTAssertNil(try store.export("\(jobID):docx"), "A cancel accepted before terminal commit must remove the just-finalized export record")
    XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path), "A cancel accepted before terminal commit must not leave a user-visible DOCX export")
    XCTAssertEqual(try store.pageRows(jobID).count, 1, "Cancel must preserve the durable translated segment checkpoint")
  }

  func testObjectiveCExportMetadataMatchesPublicBridgeContract() throws {
    // Invoke only RN's generated metadata getters. Calling the exported native
    // operations would start document pickers, permissions or background jobs.
    let nativeClass: AnyClass = PdfTranslatorModule.self
    let metaclass = try XCTUnwrap(object_getClass(nativeClass))
    var methodCount: UInt32 = 0
    let methods = try XCTUnwrap(class_copyMethodList(metaclass, &methodCount))
    defer { free(methods) }

    let expectedSignatures: Set<String> = [
      "bootstrap:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "pickPdf:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "pickDocument:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "startModelDownload:(NSString *)modelId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "pauseModelDownload:(NSString *)modelId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "deleteModel:(NSString *)modelId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "requestNotificationPermission:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "getLicenseNotices:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "getLicenseNotice:(NSString *)noticeId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "startJob:(NSString *)inputJson resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "pauseJob:(NSString *)jobId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "resumeJob:(NSString *)jobId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "cancelJob:(NSString *)jobId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "deleteJob:(NSString *)jobId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "setJobColor:(NSString *)jobId color:(NSString *)color resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "getJob:(NSString *)jobId pageIndex:(nonnull NSNumber *)pageIndex resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "renderPage:(NSString *)documentId pageIndex:(nonnull NSNumber *)pageIndex width:(nonnull NSNumber *)width resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "renderDocxPreview:(NSString *)documentId jobId:(NSString * _Nullable)jobId translated:(BOOL)translated resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "renderTranslatedPage:(NSString *)jobId pageIndex:(nonnull NSNumber *)pageIndex width:(nonnull NSNumber *)width resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "previewJob:(NSString *)jobId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "exportJob:(NSString *)jobId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "exportDocument:(NSString *)jobId format:(NSString *)format resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "shareExport:(NSString *)exportId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "openPdf:(NSString *)documentId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "setOnboarding:(NSString *)stateJson resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "setSettings:(NSString *)settingsJson resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "saveGlossaryEntry:(NSString *)entryJson resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "deleteGlossaryEntry:(NSString *)entryId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
    ]
    var observedSignatures = Set<String>()
    var exportCount = 0
    typealias MetadataGetter = @convention(c) (AnyObject, Selector) -> UnsafePointer<RCTMethodInfo>?
    for index in 0..<Int(methodCount) {
      let method = methods[index]
      let selector = method_getName(method)
      guard NSStringFromSelector(selector).hasPrefix("__rct_export__") else { continue }
      exportCount += 1
      guard method_getNumberOfArguments(method) == 2 else {
        XCTFail("An export metadata getter must accept only self and _cmd")
        continue
      }
      let getter = unsafeBitCast(method_getImplementation(method), to: MetadataGetter.self)
      let info = try XCTUnwrap(getter(nativeClass as AnyObject, selector)).pointee
      let signature = String(cString: try XCTUnwrap(info.objcName))
      XCTAssertTrue(observedSignatures.insert(signature).inserted, "Duplicate exported signature: \(signature)")
      XCTAssertEqual(String(cString: try XCTUnwrap(info.jsName)), "", "Exports use the Objective-C base name without a JS rename")
      XCTAssertFalse(info.isSync.boolValue, "Promise exports must remain asynchronous: \(signature)")
    }
    XCTAssertEqual(exportCount, 28)
    XCTAssertEqual(observedSignatures, expectedSignatures)

    // Force Objective-C dispatch, rather than satisfying this only through the
    // Swift static implementation with the same moduleName selector.
    let moduleNameSelector = NSSelectorFromString("moduleName")
    let moduleNameMethod = try XCTUnwrap(class_getClassMethod(nativeClass, moduleNameSelector))
    typealias ModuleNameGetter = @convention(c) (AnyObject, Selector) -> Unmanaged<AnyObject>?
    let moduleNameGetter = unsafeBitCast(method_getImplementation(moduleNameMethod), to: ModuleNameGetter.self)
    let moduleName = try XCTUnwrap(moduleNameGetter(nativeClass as AnyObject, moduleNameSelector)).takeUnretainedValue()
    XCTAssertEqual(moduleName as? String, "PdfTranslator")
    XCTAssertEqual(PdfTranslatorModule.moduleName(), "PdfTranslator")
  }

  @available(iOS 26.0,*)
  func testContinuedProcessingOwnerMovesToRecreatedModuleWithoutRetainingOldOwner() {
    let jobID="coverage-owner-\(UUID().uuidString)"
    let identifier="\(Bundle.main.bundleIdentifier ?? "dev.kigya.translatepdf").continuedProcessing.\(jobID)"
    weak var firstOwner:PdfTranslatorModule?
    autoreleasepool {
      let first=PdfTranslatorModule()
      firstOwner=first
      first.scheduleContinuedProcessing(jobID:jobID,documentName:"First owner")
    }
    XCTAssertNil(firstOwner,"continued-task routing must not retain a discarded React Native module")

    let currentOwner=PdfTranslatorModule()
    currentOwner.scheduleContinuedProcessing(jobID:jobID,documentName:"Current owner")
    XCTAssertTrue(PdfTranslatorModule.continuedTaskOwner(for:identifier) === currentOwner,
                  "a task registered by an old module must route to the latest module for the same job")
    BGTaskScheduler.shared.cancel(taskRequestWithIdentifier:identifier)
  }

  func testAppDelegateRestoresBackgroundSessionCompletion() {
    // The hosted app has already completed the UIApplication lifecycle. Reuse
    // its delegate so this test targets the registered background callback
    // path instead of manually replaying launch.
    guard let delegate = UIApplication.shared.delegate as? AppDelegate else {
      XCTFail("The hosted application delegate must be AppDelegate")
      return
    }

    let completed = expectation(description: "background session completion is forwarded")
    delegate.application(UIApplication.shared, handleEventsForBackgroundURLSession: "test-session") {
      completed.fulfill()
    }
    NotificationCenter.default.post(
      name: NSNotification.Name("TranslatePDFBackgroundSessionDidFinishEvents"),
      object: nil
    )
    wait(for: [completed], timeout: 2)
  }

  func testReactNativeDelegateAppliesBrandedRootViewColor() {
    let view = UIView(frame: .zero)
    let delegate = FolivelaReactNativeDelegate()
    delegate.customize(view)
    XCTAssertEqual(view.backgroundColor, UIColor(red: 198.0 / 255, green: 40.0 / 255, blue: 40.0 / 255, alpha: 1))
  }

  func testNativeModuleBootstrapReturnsDeviceAndPrivacySafeState() {
    let module = PdfTranslatorModule()
    let completed = expectation(description: "bootstrap resolves")
    module.bootstrap({ value in
      defer { completed.fulfill() }
      guard let serialized = value as? String,
            let data = serialized.data(using: .utf8),
            let snapshot = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        XCTFail("bootstrap should return a JSON snapshot")
        return
      }
      XCTAssertEqual(snapshot["schemaVersion"] as? Int, 1)
      XCTAssertNotNil(snapshot["device"] as? [String: Any])
      XCTAssertNotNil(snapshot["storage"] as? [String: Any]
        ?? (snapshot["device"] as? [String: Any])?["storage"] as? [String: Any])
      XCTAssertNotNil(snapshot["notifications"] as? [String: Any])
      XCTAssertNotNil(snapshot["models"] as? [[String: Any]])
    }, rejecter: { code, message, _ in
      XCTFail("bootstrap rejected: \(code) \(message ?? "")")
      completed.fulfill()
    })
    wait(for: [completed], timeout: 15)
  }

  func testNativeModuleSettingsValidationResolvesAndRejects() {
    let module = PdfTranslatorModule()
    let success = expectation(description: "valid settings resolve")
    module.setSettings(
      "{\"theme\":\"dark\",\"language\":\"system\",\"sourceLanguage\":\"en\",\"targetLanguage\":\"ru\"}",
      resolver: { value in
        guard let serialized = value as? String,
              let data = serialized.data(using: .utf8),
              let result = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
          XCTFail("settings should return the persisted snapshot")
          success.fulfill()
          return
        }
        XCTAssertEqual(result["theme"] as? String, "dark")
        success.fulfill()
      },
      rejecter: { code, message, _ in
        XCTFail("valid settings rejected: \(code) \(message ?? "")")
        success.fulfill()
      }
    )
    wait(for: [success], timeout: 5)

    let failure = expectation(description: "invalid settings reject")
    module.setSettings(
      "{\"theme\":\"neon\"}",
      resolver: { _ in XCTFail("invalid theme must not resolve") },
      rejecter: { code, _, _ in
        XCTAssertEqual(code, "SETTINGS_SAVE_FAILED")
        failure.fulfill()
      }
    )
    wait(for: [failure], timeout: 5)
  }

  func testNativeCoreBridgeReportsInvalidPdfAndEmptyMerge() throws {
    let invalid = FileManager.default.temporaryDirectory
      .appendingPathComponent("translatepdf-coverage-invalid-\(UUID().uuidString).pdf")
    defer { try? FileManager.default.removeItem(at: invalid) }
    try Data("not a PDF".utf8).write(to: invalid)

    let inspect = try XCTUnwrap(try JSONSerialization.jsonObject(
      with: XCTUnwrap(TranslateCoreBridge.inspect(invalid.path).data(using: .utf8))) as? [String: Any])
    XCTAssertNotNil(inspect["error"] as? String)

    let page = try XCTUnwrap(try JSONSerialization.jsonObject(
      with: XCTUnwrap(TranslateCoreBridge.readPage(invalid.path, index: 0).data(using: .utf8))) as? [String: Any])
    XCTAssertNotNil(page["error"] as? String)

    let merge = try XCTUnwrap(try JSONSerialization.jsonObject(
      with: XCTUnwrap(TranslateCoreBridge.mergePDF("[]", destination: invalid.path).data(using: .utf8))) as? [String: Any])
    XCTAssertEqual(merge["error"] as? String, "No completed pages to export")
  }
}

private final class MyMemoryURLProtocol: URLProtocol {
  private static let configurationLock = NSLock()
  private static var status = 200
  private static var body = Data()
  private static var contentLength: String?
  private static var hold = false
  private static var heldRequestSignal: DispatchSemaphore?

  static func configure(status: Int, body: String, contentLength: String? = nil) {
    configurationLock.lock(); defer { configurationLock.unlock() }
    self.status = status; self.body = Data(body.utf8); self.contentLength = contentLength
    hold = false; heldRequestSignal = nil
  }

  static func configureHeld() {
    configurationLock.lock(); defer { configurationLock.unlock() }
    status = 200; body = Data(); contentLength = nil; hold = true
    heldRequestSignal = DispatchSemaphore(value: 0)
  }

  static func waitForHeldRequest(timeout: TimeInterval) -> Bool {
    configurationLock.lock(); let signal = heldRequestSignal; configurationLock.unlock()
    return signal?.wait(timeout: .now() + timeout) == .success
  }

  override class func canInit(with request: URLRequest) -> Bool {
    request.url?.host == "api.mymemory.translated.net"
  }

  override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

  override func startLoading() {
    Self.configurationLock.lock()
    let status = Self.status, body = Self.body, contentLength = Self.contentLength
    let hold = Self.hold, heldRequestSignal = Self.heldRequestSignal
    Self.configurationLock.unlock()
    if hold { heldRequestSignal?.signal(); return }
    var headers = ["Content-Type": "application/json"]
    if let contentLength { headers["Content-Length"] = contentLength }
    guard let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers) else {
      client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return
    }
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: body)
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {}
}

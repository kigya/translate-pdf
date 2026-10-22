import XCTest
import UIKit
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
    for notice in notices {
      let id = try XCTUnwrap(notice["id"])
      let text = try XCTUnwrap(try resolved { module.getLicenseNotice(id, resolver: $0, rejecter: $1) } as? String)
      XCTAssertFalse(text.isEmpty)
    }
    for invalid in ["", "../Info.plist", "a/b", "a\\b", "unknown.txt"] {
      rejected("LICENSE_READ_FAILED") { module.getLicenseNotice(invalid, resolver: $0, rejecter: $1) }
    }
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
      "getJob:(NSString *)jobId pageIndex:(nonnull NSNumber *)pageIndex resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "renderPage:(NSString *)documentId pageIndex:(nonnull NSNumber *)pageIndex width:(nonnull NSNumber *)width resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "renderTranslatedPage:(NSString *)jobId pageIndex:(nonnull NSNumber *)pageIndex width:(nonnull NSNumber *)width resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "previewJob:(NSString *)jobId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
      "exportJob:(NSString *)jobId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
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
    XCTAssertEqual(exportCount, 24)
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

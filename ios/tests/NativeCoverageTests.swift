import XCTest
import CryptoKit
import UIKit
import QuickLook
import BackgroundTasks
import UserNotifications
import React
import ObjectiveC
@testable import TranslatePDF
@testable import TranslateCore

final class NativeCoverageTests: XCTestCase {
  private final class ModuleNextPresenter: UIViewController {
    var onPresent: ((UIViewController) -> Void)?
    override func present(_ viewControllerToPresent: UIViewController, animated flag: Bool,
                          completion: (() -> Void)? = nil) {
      onPresent?(viewControllerToPresent)
      completion?()
    }
  }

  private func withModuleNextPresenter(_ body: (ModuleNextPresenter) throws -> Void) throws {
    let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
      .first { $0.activationState == .foregroundActive }, "hosted UI fixture needs its real foreground scene")
    let previous = scene.windows.first { $0.isKeyWindow }
    let window = UIWindow(windowScene: scene)
    let presenter = ModuleNextPresenter()
    window.rootViewController = presenter
    window.makeKeyAndVisible()
    defer { window.isHidden = true; previous?.makeKey() }
    try body(presenter)
  }

  private func moduleNextPresented(_ presenter: ModuleNextPresenter, invoke: () -> Void) throws -> UIViewController {
    let displayed = expectation(description: "module reaches the public UIKit presentation boundary")
    var captured: UIViewController?
    presenter.onPresent = { controller in captured = controller; displayed.fulfill() }
    defer { presenter.onPresent = nil }
    invoke()
    wait(for: [displayed], timeout: 15)
    return try XCTUnwrap(captured)
  }

  private func moduleNextPDF(_ url: URL, labels: [String]) throws {
    try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 400, height: 300)).writePDF(to: url) { context in
      for text in labels {
        context.beginPage()
        (text as NSString).draw(at: CGPoint(x: 24, y: 24), withAttributes: [.font: UIFont.systemFont(ofSize: 16)])
      }
    }
  }

  private func moduleNextSaveTranslatedCheckpoint(_ store:TranslatorStore,jobID:String,source:URL)throws {
    let page=try decoded(TranslateCoreBridge.readPage(source.path,index:0))
    var blocks=try XCTUnwrap(page["blocks"] as? [[String:Any]])
    XCTAssertFalse(blocks.isEmpty,"the worker-stop fixture must use native-owned source blocks")
    for index in blocks.indices {
      blocks[index]["translatedText"]="Сохранённый перевод"
      blocks[index]["status"]="translated"
    }
    try store.savePage(jobId:jobID,index:0,status:"completed",blocks:try jsonString(blocks))
  }

  private func moduleNextCleanup(_ store: TranslatorStore, documentID: String, jobs: [String]) {
    for id in jobs {
      try? store.deleteExport(id)
      try? store.deleteExport(id + ":docx")
      try? store.deleteJob(id)
      for suffix in [".pdf", ".docx", ".pending.pdf", ".pending.docx"] {
        try? FileManager.default.removeItem(at: AppPaths.exports.appendingPathComponent(id + suffix))
      }
      for folder in [id, "export-" + id] {
        try? FileManager.default.removeItem(at: AppPaths.pages.appendingPathComponent(folder, isDirectory: true))
      }
    }
    try? store.deleteDocument(documentID)
    let prefixes = [documentID + "_"] + jobs.map { "translated_\($0)_" }
    for item in (try? FileManager.default.contentsOfDirectory(at: AppPaths.thumbnails, includingPropertiesForKeys: nil)) ?? []
      where prefixes.contains(where: { item.lastPathComponent.hasPrefix($0) }) {
      try? FileManager.default.removeItem(at: item)
    }
  }

  func testModulePickerBusyCancellationAndEmptySelectionLeaveTheLibraryUnchanged() throws {
    let module = PdfTranslatorModule()
    let store = try TranslatorStore(directory: AppPaths.support)
    let before = Set(try store.documents().compactMap { $0["id"] as? String })
    try withModuleNextPresenter { presenter in
      let canceled = expectation(description: "canceled picker resolves its own promise")
      var value: Any?
      let controller = try XCTUnwrap(try moduleNextPresented(presenter) {
        module.pickDocument({ result in value = result; canceled.fulfill() }, rejecter: { code, message, _ in
          XCTFail("unexpected picker rejection \(code ?? ""): \(message ?? "")"); canceled.fulfill()
        })
      } as? UIDocumentPickerViewController)
      XCTAssertTrue(controller.delegate === module)
      XCTAssertFalse(controller.allowsMultipleSelection)
      rejected("PICKER_BUSY") { module.pickPdf($0, rejecter: $1) }
      module.documentPickerWasCancelled(controller)
      wait(for: [canceled], timeout: 15)
      XCTAssertEqual(value as? String, "null")

      let empty = expectation(description: "empty result releases a fresh picker promise")
      var emptyValue: Any?
      let reopened = try XCTUnwrap(try moduleNextPresented(presenter) {
        module.pickPdf({ result in emptyValue = result; empty.fulfill() }, rejecter: { _, _, _ in
          XCTFail("picker must be reusable after cancellation"); empty.fulfill()
        })
      } as? UIDocumentPickerViewController)
      module.documentPicker(reopened, didPickDocumentsAt: [])
      wait(for: [empty], timeout: 15)
      XCTAssertEqual(emptyValue as? String, "null")
      XCTAssertEqual(Set(try store.documents().compactMap { $0["id"] as? String }), before)
    }
  }

  func testModulePickerImportsRealPdfAndRejectsRenamedNonPdfWithoutIncomingResidue() throws {
    let external = FileManager.default.temporaryDirectory.appendingPathComponent("module-import-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: external, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: external) }
    let source = external.appendingPathComponent("Service manual.pdf")
    let bad = external.appendingPathComponent("Renamed text.pdf")
    try moduleNextPDF(source, labels: ["Brake 42", "Valve 7"])
    try Data("ordinary text renamed to PDF".utf8).write(to: bad)
    let sourceBytes = try Data(contentsOf: source)
    let store = try TranslatorStore(directory: AppPaths.support)
    let module = PdfTranslatorModule()
    var importedID: String?
    var importedURL: URL?
    defer {
      if let importedID { try? store.deleteDocument(importedID) }
      if let importedURL { try? FileManager.default.removeItem(at: importedURL) }
    }
    try withModuleNextPresenter { presenter in
      let imported = expectation(description: "real picker selection is copied and inspected")
      var payload: Any?
      let picker = try XCTUnwrap(try moduleNextPresented(presenter) {
        module.pickDocument({ result in payload = result; imported.fulfill() }, rejecter: { code, message, _ in
          XCTFail("unexpected import rejection \(code ?? ""): \(message ?? "")"); imported.fulfill()
        })
      } as? UIDocumentPickerViewController)
      XCTAssertTrue(picker.delegate === module)
      module.documentPicker(picker, didPickDocumentsAt: [source])
      wait(for: [imported], timeout: 15)
      let saved = try decoded(payload)
      let id = try XCTUnwrap(saved["id"] as? String)
      importedID = id
      let privateURL = URL(fileURLWithPath: try XCTUnwrap(try store.documentPath(id)))
      importedURL = privateURL
      XCTAssertEqual(privateURL.deletingLastPathComponent().standardizedFileURL, AppPaths.documents.standardizedFileURL)
      XCTAssertNotEqual(privateURL, source)
      XCTAssertEqual(try Data(contentsOf: privateURL), sourceBytes)
      XCTAssertEqual(try Data(contentsOf: source), sourceBytes, "import cannot consume the user-selected source")
      XCTAssertEqual(try store.documentSHA256(id), try sha256(at: source))
      XCTAssertEqual(try decoded(TranslateCoreBridge.inspect(privateURL.path))["pageCount"] as? Int, 2)
      XCTAssertEqual(try store.documentFormat(id), "pdf")
      let beforeRows = Set(try store.documents().compactMap { $0["id"] as? String })
      let beforeIncoming = Set(try FileManager.default.contentsOfDirectory(atPath: AppPaths.documents.path)
        .filter { $0.contains(".incoming.") })
      let rejectedImport = expectation(description: "renamed non-PDF rejects and cleans its staging file")
      var failureCode: String?
      let retry = try XCTUnwrap(try moduleNextPresented(presenter) {
        module.pickPdf({ _ in XCTFail("renamed text cannot import as PDF"); rejectedImport.fulfill() }, rejecter: { code, _, _ in
          failureCode = code; rejectedImport.fulfill()
        })
      } as? UIDocumentPickerViewController)
      module.documentPicker(retry, didPickDocumentsAt: [bad])
      wait(for: [rejectedImport], timeout: 15)
      XCTAssertEqual(failureCode, "PDF_IMPORT_FAILED")
      XCTAssertEqual(Set(try store.documents().compactMap { $0["id"] as? String }), beforeRows)
      XCTAssertEqual(Set(try FileManager.default.contentsOfDirectory(atPath: AppPaths.documents.path)
        .filter { $0.contains(".incoming.") }), beforeIncoming)
      XCTAssertEqual(try Data(contentsOf: bad), Data("ordinary text renamed to PDF".utf8))
    }
  }

  func testModuleResumesTranslatedBlocksFromRunningPageWithoutRepeatingInference() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let module = PdfTranslatorModule()
    let id = "module-cached-resume-\(UUID().uuidString)"
    let jobID = id + "-job"
    let source = AppPaths.documents.appendingPathComponent(id + ".pdf")
    defer { moduleNextCleanup(store, documentID: id, jobs: [jobID]); try? FileManager.default.removeItem(at: source) }
    try moduleNextPDF(source, labels: ["Valve 42", "Brake 7"])
    let model = try ModelCatalog.get(ModelCatalog.compactId)
    XCTAssertEqual(try model.fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize, Int(model.bytes),
      "the orchestrator's actual pinned Compact fixture must remain available; this test never replaces it")
    _ = try store.saveDocument(id: id, name: "Cached checkpoint.pdf", path: source.path, pages: 2, sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: jobID, input: ["documentId": id, "modelId": model.id,
      "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 2)
    var expectedText = [[String]]()
    for index in 0..<2 {
      let native = try decoded(TranslateCoreBridge.readPage(source.path, index: index))
      var blocks = try XCTUnwrap(native["blocks"] as? [[String: Any]])
      XCTAssertFalse(blocks.isEmpty)
      for blockIndex in blocks.indices {
        blocks[blockIndex]["translatedText"] = index == 0 ? "Клапан 42" : "Тормоз 7"
        blocks[blockIndex]["status"] = "translated"
      }
      expectedText.append(blocks.compactMap { $0["translatedText"] as? String })
      try store.savePage(jobId: jobID, index: index, status: index == 0 ? "completed" : "running", blocks: jsonString(blocks))
    }
    try store.updateJob(jobID, status: "paused", stage: "translating", completed: 1, currentPage: 1)
    let before = try XCTUnwrap(store.job(jobID))
    XCTAssertEqual(before["modelRevision"] as? String, model.revision)
    XCTAssertNil(try store.export(jobID))
    let acknowledged = try decoded(resolved { module.resumeJob(jobID, resolver: $0, rejecter: $1) })
    XCTAssertEqual(acknowledged["id"] as? String, jobID)
    let finished = try terminalJob(jobID, store: store)
    XCTAssertTrue(["completed", "partial"].contains(finished["status"] as? String ?? ""), "cached assembly must finish: \(finished)")
    XCTAssertEqual(finished["completedPages"] as? Int64, 2)
    for index in 0..<2 {
      let page = try XCTUnwrap(try store.page(jobID, index: index))
      let blocks = try XCTUnwrap(page["blocks"] as? [[String: Any]])
      XCTAssertEqual(blocks.compactMap { $0["translatedText"] as? String }, expectedText[index],
        "completed translations on a still-running saved page are reusable checkpoints")
    }
    let record = try XCTUnwrap(try store.export(jobID))
    let exported = URL(fileURLWithPath: try XCTUnwrap(record["path"] as? String))
    XCTAssertEqual(try sha256(at: exported), record["sha256"] as? String)
    XCTAssertEqual(try decoded(TranslateCoreBridge.inspect(exported.path))["pageCount"] as? Int, 2)
    let reopened = PdfTranslatorModule()
    XCTAssertTrue(["completed", "partial"].contains(try store.job(jobID)?["status"] as? String ?? ""), "verified cached export must survive module restoration")
    let verified = try decoded(resolved { reopened.getJob(jobID, pageIndex: 0, resolver: $0, rejecter: $1) })
    XCTAssertEqual(verified["pdfLayout"] as? [String: Any] != nil, true)
    _ = try resolved { reopened.previewJob(jobID, resolver: $0, rejecter: $1) }
    XCTAssertEqual(try sha256(at: exported), record["sha256"] as? String)

    let originalReceiptURL = exported.deletingLastPathComponent().appendingPathComponent("layout-receipt.json")
    let initialReceipt = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: originalReceiptURL)) as? [String: Any])
    let currentPolicy = try XCTUnwrap(initialReceipt["rendererPolicy"] as? String)
    let stalePolicies = [
      currentPolicy.replacingOccurrences(of: "source-preserving-v10", with: "source-preserving-v0"),
      currentPolicy.replacingOccurrences(of: "noto=", with: "noto=stale-")
    ]
    for stalePolicy in stalePolicies {
      let staleExportPath = try XCTUnwrap(try store.export(jobID)?["path"] as? String)
      let receiptURL = URL(fileURLWithPath: staleExportPath).deletingLastPathComponent().appendingPathComponent("layout-receipt.json")
      var currentReceipt = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: receiptURL)) as? [String: Any])
      currentReceipt["rendererPolicy"] = stalePolicy
      currentReceipt.removeValue(forKey: "receiptSha256")
      let content = try JSONSerialization.data(withJSONObject: currentReceipt, options: [.sortedKeys, .fragmentsAllowed])
      currentReceipt["receiptSha256"] = SHA256.hash(data: content).map { String(format: "%02x", $0) }.joined()
      try JSONSerialization.data(withJSONObject: currentReceipt, options: [.sortedKeys, .fragmentsAllowed]).write(to: receiptURL, options: .atomic)

      let rebuilt = try decoded(resolved { reopened.getJob(jobID, pageIndex: 0, resolver: $0, rejecter: $1) })
      XCTAssertNotNil(rebuilt["pdfLayout"] as? [String: Any])
      let repairedRecord = try XCTUnwrap(try store.export(jobID))
      let repairedPath = try XCTUnwrap(repairedRecord["path"] as? String)
      XCTAssertNotEqual(repairedPath, staleExportPath, "a fully resealed receipt with stale renderer/font policy must trigger a fresh generation")
      let repairedReceiptURL = URL(fileURLWithPath: repairedPath).deletingLastPathComponent().appendingPathComponent("layout-receipt.json")
      currentReceipt = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: repairedReceiptURL)) as? [String: Any])
      XCTAssertNotEqual(currentReceipt["rendererPolicy"] as? String, stalePolicy)
      XCTAssertEqual(currentReceipt["rendererPolicy"] as? String, currentPolicy)
      XCTAssertEqual(try sha256(at: URL(fileURLWithPath: repairedPath)), repairedRecord["sha256"] as? String)
    }
  }

  func testCondensedFontIsBundledAndBoundToSourcePreservingPolicy() throws {
    let bundleURL=try XCTUnwrap(Bundle.main.url(forResource:"TranslatePDFFont",withExtension:"bundle"))
    let fontBundle=try XCTUnwrap(Bundle(url:bundleURL))
    let condensed=try XCTUnwrap(fontBundle.url(forResource:"NotoSansCondensed-Regular",withExtension:"ttf"))
    let regular=try XCTUnwrap(fontBundle.url(forResource:"NotoSans-Regular",withExtension:"ttf"),
      "legacy reference font remains packaged for source-compatible tests")
    let cjk=try XCTUnwrap(fontBundle.url(forResource:"NotoSansSC-Regular",withExtension:"ttf"))
    XCTAssertEqual(try sha256(at:condensed),"5c15b8bb0ac1bbd533917b23968c6f0a420f143533e1293cc575f4f9dd543be8")
    XCTAssertEqual(try sha256(at:regular),"b85c38ecea8a7cfb39c24e395a4007474fa5a4fc864f6ee33309eb4948d232d5")
    XCTAssertEqual(try sha256(at:cjk),"b7bebb49c3c6b0266362cf75dd9bdd9bf5fbe5fb390be8f7808ebedef376de76")
    XCTAssertTrue(PdfTranslatorModule.pdfRendererPolicy.contains("source-preserving-v10"))
    XCTAssertTrue(PdfTranslatorModule.pdfRendererPolicy.contains("noto=5c15b8bb0ac1bbd533917b23968c6f0a420f143533e1293cc575f4f9dd543be8"))
  }

  func testModuleVerifiedExportCancelAndPdfPreviewKeepTheDurableArtifact() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let module = PdfTranslatorModule()
    let id = "module-export-ui-\(UUID().uuidString)"
    let jobID = id + "-job"
    let source = AppPaths.documents.appendingPathComponent(id + ".pdf")
    defer {
      moduleNextCleanup(store, documentID: id, jobs: [jobID])
      for url in (try? FileManager.default.contentsOfDirectory(at:AppPaths.exports,includingPropertiesForKeys:nil)) ?? [] where url.lastPathComponent.hasPrefix(jobID+"-") { try? FileManager.default.removeItem(at:url) }
      try? FileManager.default.removeItem(at: source)
    }
    try moduleNextPDF(source, labels: ["42"])
    try FileManager.default.createDirectory(at: AppPaths.exports, withIntermediateDirectories: true)
    let page = try decoded(TranslateCoreBridge.readPage(source.path, index: 0))
    var savedBlocks = try XCTUnwrap(page["blocks"] as? [[String: Any]])
    XCTAssertFalse(savedBlocks.isEmpty)
    for index in savedBlocks.indices {
      savedBlocks[index]["translatedText"] = savedBlocks[index]["sourceText"]
      savedBlocks[index]["status"] = "translated"
    }
    _ = try store.saveDocument(id: id, name: "Read-only.pdf", path: source.path, pages: 1, sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: jobID, input: ["documentId": id, "modelId": ModelCatalog.compactId,
      "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 1)
    try store.savePage(jobId: jobID, index: 0, status: "completed", blocks: jsonString(savedBlocks))
    try store.updateJob(jobID, status: "completed", stage: "complete", completed: 1)
    _ = try resolved { module.previewJob(jobID, resolver: $0, rejecter: $1) }
    let beforeExport=try XCTUnwrap(try store.export(jobID))
    let durablePath=try XCTUnwrap(beforeExport["path"] as? String)
    let durableURL=URL(fileURLWithPath:durablePath)
    let digest=try XCTUnwrap(beforeExport["sha256"] as? String)
    XCTAssertEqual(try sha256(at:durableURL),digest,"a verified source-preserving generation exists before opening the share picker")
    try withModuleNextPresenter { presenter in
      let canceled = expectation(description: "export picker cancellation settles the pending export promise")
      var failureCode: String?
      let picker = try XCTUnwrap(try moduleNextPresented(presenter) {
        module.exportDocument(jobID, format: "pdf", resolver: { _ in XCTFail("cancel must not resolve as successful export"); canceled.fulfill() }, rejecter: { code, _, _ in
          failureCode = code; canceled.fulfill()
        })
      } as? UIDocumentPickerViewController)
      XCTAssertTrue(picker.delegate === module)
      let durableExport = try XCTUnwrap(try store.export(jobID))
      XCTAssertEqual(durableExport["path"] as? String,durablePath,"canceling a share picker must preserve the already verified generation")
      XCTAssertEqual(durableExport["sha256"] as? String,digest)
      XCTAssertEqual(try sha256(at: durableURL),digest,"the verified v3 artifact must be durable before the picker is presented")
      module.documentPickerWasCancelled(picker)
      wait(for: [canceled], timeout: 15)
      XCTAssertEqual(failureCode, "EXPORT_CANCELED")
      XCTAssertEqual(try sha256(at: durableURL), digest)
      XCTAssertEqual(try store.export(jobID)?["path"] as? String, durableURL.path)
      XCTAssertEqual(try store.export(jobID)?["sha256"] as? String, digest)
      XCTAssertEqual(try store.job(jobID)?["status"] as? String, "completed")

      let opened = expectation(description: "openPdf reaches QuickLook presentation completion")
      let controller = try XCTUnwrap(try moduleNextPresented(presenter) {
        module.openPdf(id, resolver: { _ in opened.fulfill() }, rejecter: { code, message, _ in
          XCTFail("unexpected open failure \(code ?? ""): \(message ?? "")"); opened.fulfill()
        })
      } as? QLPreviewController)
      wait(for: [opened], timeout: 15)
      let dataSource = try XCTUnwrap(controller.dataSource)
      XCTAssertEqual(dataSource.numberOfPreviewItems(in: controller), 1)
      let item = dataSource.previewController(controller, previewItemAt: 0)
      let itemURL = try XCTUnwrap(item as? NSURL)
      XCTAssertEqual((itemURL as URL).standardizedFileURL, source.standardizedFileURL)
      XCTAssertEqual(try sha256(at: durableURL), digest)
    }
  }

  func testModuleDeletesSharedPrivateSymlinksWithoutDeletingExternalSourceOrResult() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let module = PdfTranslatorModule()
    let id = "module-shared-links-\(UUID().uuidString)"
    let firstID = id + "-first", secondID = id + "-second"
    let external = FileManager.default.temporaryDirectory.appendingPathComponent(id, isDirectory: true)
    try FileManager.default.createDirectory(at: external, withIntermediateDirectories: true)
    let original = external.appendingPathComponent("user-source.pdf")
    let userResult = external.appendingPathComponent("user-result.pdf")
    let sourceAlias = AppPaths.documents.appendingPathComponent(id + ".pdf")
    let firstAlias = AppPaths.exports.appendingPathComponent(firstID + ".pdf")
    let secondAlias = AppPaths.exports.appendingPathComponent(secondID + ".pdf")
    defer {
      moduleNextCleanup(store, documentID: id, jobs: [firstID, secondID])
      try? FileManager.default.removeItem(at: sourceAlias)
      try? FileManager.default.removeItem(at: external)
    }
    try moduleNextPDF(original, labels: ["Shared user source 42"])
    try FileManager.default.copyItem(at: original, to: userResult)
    try FileManager.default.createDirectory(at: AppPaths.documents, withIntermediateDirectories: true)
    try FileManager.default.createDirectory(at: AppPaths.exports, withIntermediateDirectories: true)
    try FileManager.default.createSymbolicLink(at: sourceAlias, withDestinationURL: original)
    _ = try store.saveDocument(id: id, name: "Linked import.pdf", path: sourceAlias.path, pages: 1, sourceSHA256: sha256(at: original))
    for (jobID, alias) in [(firstID, firstAlias), (secondID, secondAlias)] {
      _ = try store.addJob(id: jobID, input: ["documentId": id, "modelId": ModelCatalog.compactId,
        "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 1)
      try store.updateJob(jobID, status: "paused")
      try FileManager.default.createSymbolicLink(at: alias, withDestinationURL: userResult)
      try store.saveExport(jobID, path: alias.path, name: alias.lastPathComponent, sha256: sha256(at: userResult))
    }
    let originalDigest = try sha256(at: original), resultDigest = try sha256(at: userResult)
    _ = try resolved { module.deleteJob(firstID, resolver: $0, rejecter: $1) }
    XCTAssertNil(try store.job(firstID))
    XCTAssertNotNil(try store.job(secondID))
    XCTAssertNotNil(try store.documentPath(id))
    XCTAssertFalse(FileManager.default.fileExists(atPath: firstAlias.path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: secondAlias.path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: sourceAlias.path))
    XCTAssertEqual(try sha256(at: original), originalDigest)
    XCTAssertEqual(try sha256(at: userResult), resultDigest)
    _ = try resolved { module.deleteJob(secondID, resolver: $0, rejecter: $1) }
    XCTAssertNil(try store.job(secondID))
    XCTAssertNil(try store.documentPath(id))
    XCTAssertFalse(FileManager.default.fileExists(atPath: sourceAlias.path))
    XCTAssertFalse(FileManager.default.fileExists(atPath: secondAlias.path))
    XCTAssertEqual(try sha256(at: original), originalDigest, "deletion unlinks private aliases, never their shared external target")
    XCTAssertEqual(try sha256(at: userResult), resultDigest)
  }

  private final class ControlledTask: ModelDownloadTaskHandle {
    let taskIdentifier: Int
    var taskDescription: String?
    private let lock = NSLock()
    private var resumes = 0
    private var cancellations = 0
    private var resumedDescription: String?
    private var pauseCompletion: ((Data?) -> Void)?
    var onPause: (() -> Void)?
    var onCancel: (() -> Void)?

    init(_ id: Int, description: String? = nil) {
      taskIdentifier = id
      taskDescription = description
    }
    var resumeCount: Int { lock.lock(); defer { lock.unlock() }; return resumes }
    var cancelCount: Int { lock.lock(); defer { lock.unlock() }; return cancellations }
    var descriptionAtResume: String? { lock.lock(); defer { lock.unlock() }; return resumedDescription }
    func resume() { lock.lock(); resumes += 1; resumedDescription = taskDescription; lock.unlock() }
    func cancel() {
      lock.lock(); cancellations += 1; lock.unlock()
      onCancel?()
    }
    func cancel(byProducingResumeData completionHandler: @escaping (Data?) -> Void) {
      lock.lock(); pauseCompletion = completionHandler; lock.unlock()
      onPause?()
    }
    func finishPause(_ data: Data?) {
      lock.lock(); let completion = pauseCompletion; pauseCompletion = nil; lock.unlock()
      completion?(data)
    }
  }

  private final class ControlledTransport: ModelDownloadTransport {
    private let lock = NSLock()
    private var nextIdentifier = 100
    private var created = [ControlledTask]()
    private var requestedURLs = [URL]()
    private var requestedResumeData = [Data]()
    private var restoration: (([ModelDownloadTaskHandle]) -> Void)?
    var holdsRestoration = false

    var tasks: [ControlledTask] { lock.lock(); defer { lock.unlock() }; return created }
    var urls: [URL] { lock.lock(); defer { lock.unlock() }; return requestedURLs }
    var resumeData: [Data] { lock.lock(); defer { lock.unlock() }; return requestedResumeData }
    func getAllTasks(_ completion: @escaping ([ModelDownloadTaskHandle]) -> Void) {
      lock.lock()
      if holdsRestoration { restoration = completion; lock.unlock() }
      else { lock.unlock(); completion([]) }
    }
    func restore(_ tasks: [ModelDownloadTaskHandle]) {
      lock.lock(); let completion = restoration; restoration = nil; lock.unlock()
      completion?(tasks)
    }
    private func makeTask() -> ControlledTask {
      let task = ControlledTask(nextIdentifier)
      nextIdentifier += 1
      created.append(task)
      return task
    }
    func downloadTask(with url: URL) -> ModelDownloadTaskHandle {
      lock.lock(); defer { lock.unlock() }
      requestedURLs.append(url)
      return makeTask()
    }
    func downloadTask(withResumeData data: Data) -> ModelDownloadTaskHandle {
      lock.lock(); defer { lock.unlock() }
      requestedResumeData.append(data)
      return makeTask()
    }
  }

  private final class DiskCapacity {
    private let lock = NSLock()
    private var storedBytes: Int64
    init(_ bytes: Int64) { storedBytes = bytes }
    var bytes: Int64 {
      get { lock.lock(); defer { lock.unlock() }; return storedBytes }
      set { lock.lock(); storedBytes = newValue; lock.unlock() }
    }
  }

  private final class Fixture {
    let directory: URL
    let models: URL
    let payload = Data(repeating: 0x5a, count: 2 * 1_048_576 + 17)
    let model: ModelDefinition
    let store: TranslatorStore
    let transport = ControlledTransport()
    let diskCapacity: DiskCapacity
    let coordinator: ModelDownloadCoordinator

    init(freeBytes: Int64 = Int64.max, holdRestoration: Bool = false) throws {
      directory = FileManager.default.temporaryDirectory.appendingPathComponent("download-port-\(UUID().uuidString)", isDirectory: true)
      models = directory.appendingPathComponent("models", isDirectory: true)
      try FileManager.default.createDirectory(at: models, withIntermediateDirectories: true)
      let pin = directory.appendingPathComponent("fixture-pin")
      try payload.write(to: pin)
      let definition = ModelDefinition(id: "test-download-\(UUID().uuidString)", label: "Pinned fixture", repo: "test/fixture", fileName: "fixture.gguf", revision: "fixture", bytes: Int64(payload.count), sha256: try sha256(at: pin), qualityBenchmarked: false, translationEnabled: true, tier: "compact", profile: "hy-mt2", requiredMemoryBytes: 1, licenseUrl: "https://example.invalid/license", supportsGlossary: true)
      try FileManager.default.removeItem(at: pin)
      model = definition
      store = try TranslatorStore(directory: directory)
      diskCapacity = DiskCapacity(freeBytes)
      let capacity = diskCapacity
      transport.holdsRestoration = holdRestoration
      coordinator = ModelDownloadCoordinator(store: store, transport: transport, modelsDirectory: models, definitionForID: { id in
        guard id == definition.id else { throw NSError(domain: "DownloaderFixture", code: 404) }
        return definition
      }, availableDiskBytes: { capacity.bytes })
    }
    deinit { try? FileManager.default.removeItem(at: directory) }
    func file(_ suffix: String) -> URL { models.appendingPathComponent(model.id + suffix) }
    func temporary(_ bytes: Data) throws -> URL {
      let url = directory.appendingPathComponent("delivered-\(UUID().uuidString)")
      try bytes.write(to: url)
      return url
    }
    func progress(_ task: ControlledTask, _ bytes: Int64) {
      coordinator.handleProgress(taskIdentifier: task.taskIdentifier, taskDescription: task.taskDescription, totalBytesWritten: bytes)
    }
    func finish(_ task: ControlledTask, _ location: URL) {
      coordinator.handleFinish(taskIdentifier: task.taskIdentifier, taskDescription: task.taskDescription, location: location)
    }
    func complete(_ task: ControlledTask, _ error: Error? = nil) {
      coordinator.handleCompletion(taskIdentifier: task.taskIdentifier, taskDescription: task.taskDescription, error: error)
    }
    func status() throws -> String? { try store.modelState(model.id)?["status"] as? String }
    func bytes() throws -> Int64? { try store.modelState(model.id)?["downloaded_bytes"] as? Int64 }
  }

  func testDownloaderTransportFreshProgressInstallsOnlyRealPinnedPayload() throws {
    let f = try Fixture()
    let started = try f.coordinator.start(f.model)
    XCTAssertEqual(started["status"] as? String, "downloading")
    let task = try XCTUnwrap(f.transport.tasks.first)
    XCTAssertEqual(f.transport.tasks.count, 1)
    XCTAssertEqual(f.transport.urls, [f.model.url])
    XCTAssertTrue(f.transport.resumeData.isEmpty)
    XCTAssertEqual(task.taskDescription, f.model.id)
    XCTAssertEqual(task.resumeCount, 1)
    XCTAssertEqual(task.descriptionAtResume, f.model.id, "task ownership must be attached before transport execution")
    XCTAssertEqual(try f.status(), "downloading")
    XCTAssertEqual(try f.bytes(), 0)
    f.progress(task, 1_048_575)
    XCTAssertEqual(try f.bytes(), 0, "sub-threshold progress must not be persisted")
    f.progress(task, 1_048_576)
    XCTAssertEqual(try f.bytes(), 1_048_576)
    f.progress(task, 1_048_577)
    XCTAssertEqual(try f.bytes(), 1_048_576)
    let location = try f.temporary(f.payload)
    f.finish(task, location)
    XCTAssertEqual(try f.status(), "ready")
    XCTAssertEqual(try f.bytes(), f.model.bytes)
    XCTAssertEqual(try Data(contentsOf: f.file(".gguf")), f.payload)
    XCTAssertEqual(try sha256(at: f.file(".gguf")), f.model.sha256)
    XCTAssertFalse(FileManager.default.fileExists(atPath: location.path))
    XCTAssertFalse(FileManager.default.fileExists(atPath: f.file(".part").path))
    XCTAssertTrue(try f.coordinator.isReady(f.model))
    // Foundation's terminal nil-error callback follows didFinishDownloadingTo.
    f.complete(task)
    XCTAssertEqual(try f.status(), "ready")
    XCTAssertEqual(try f.coordinator.start(f.model)["status"] as? String, "ready")
    XCTAssertEqual(f.transport.tasks.count, 1, "a verified install must not create a second download")
  }

  func testDownloaderTransportRejectsIncompleteAndCorruptPayloadAndInsufficientDisk() throws {
    for corruptSize in [false, true] {
      let f = try Fixture()
      _ = try f.coordinator.start(f.model)
      let task = try XCTUnwrap(f.transport.tasks.first)
      let bytes = corruptSize ? Data([0]) : Data(repeating: 0x41, count: f.payload.count)
      f.finish(task, try f.temporary(bytes))
      XCTAssertEqual(try f.status(), "error")
      let error = try XCTUnwrap(f.store.modelState(f.model.id)?["error"] as? String)
      XCTAssertTrue(error.contains(corruptSize ? "incomplete size" : "SHA-256"))
      XCTAssertFalse(FileManager.default.fileExists(atPath: f.file(".gguf").path))
      XCTAssertFalse(FileManager.default.fileExists(atPath: f.file(".part").path))
      XCTAssertFalse(try f.coordinator.isReady(f.model))
      // Hash/size failure retires this task; late progress cannot overwrite error.
      f.progress(task, f.model.bytes)
      f.complete(task, NSError(domain: NSURLErrorDomain, code: NSURLErrorNetworkConnectionLost))
      XCTAssertEqual(try f.status(), "error")
    }
    let lowDisk = try Fixture(freeBytes: 0)
    XCTAssertThrowsError(try lowDisk.coordinator.start(lowDisk.model)) {
      XCTAssertEqual(($0 as NSError).code, 20)
    }
    XCTAssertTrue(lowDisk.transport.tasks.isEmpty)
    XCTAssertNil(try lowDisk.store.modelState(lowDisk.model.id))
  }

  func testDownloaderFailedSizeOrHashVerificationCannotSkipRetryDiskReserve() throws {
    let reserve = Int64(512 * 1_048_576)
    for corruptSize in [false, true] {
      let f = try Fixture()
      _ = try f.coordinator.start(f.model)
      let task = try XCTUnwrap(f.transport.tasks.first)
      let delivered = corruptSize ? Data([0]) : Data(repeating: 0x41, count: f.payload.count)
      f.finish(task, try f.temporary(delivered))

      XCTAssertEqual(try f.status(), "error")
      XCTAssertEqual(try f.bytes(), 0, "a rejected payload removed from disk cannot remain recorded as complete")
      XCTAssertFalse(FileManager.default.fileExists(atPath: f.file(".part").path))
      XCTAssertFalse(FileManager.default.fileExists(atPath: f.file(".gguf").path))

      // Once the full download fails, only the 512 MiB work reserve remains.
      // A fresh transfer still needs the entire pinned payload plus that reserve.
      f.diskCapacity.bytes = reserve
      XCTAssertThrowsError(try f.coordinator.start(f.model)) {
        XCTAssertEqual(($0 as NSError).code, 20)
      }
      XCTAssertEqual(f.transport.tasks.count, 1, "disk rejection must happen before creating a retry task")
      XCTAssertEqual(try f.status(), "error")
      XCTAssertEqual(try f.bytes(), 0)
    }
  }

  func testDownloaderTransportPausePersistsResumeDataAndRejectsStaleGeneration() throws {
    let f = try Fixture()
    _ = try f.coordinator.start(f.model)
    let old = try XCTUnwrap(f.transport.tasks.first)
    f.progress(old, 1_048_576)
    let pauseEntered = expectation(description: "pause requested resume data")
    old.onPause = { pauseEntered.fulfill() }
    let result = OperationResult()
    DispatchQueue.global().async {
      do { _ = try f.coordinator.pause(f.model); result.finish(nil) }
      catch { result.finish(error) }
    }
    wait(for: [pauseEntered], timeout: 2)
    defer { old.finishPause(nil) }
    XCTAssertThrowsError(try f.coordinator.start(f.model)) {
      XCTAssertEqual(($0 as NSError).code, 23)
    }
    XCTAssertFalse(result.isComplete, "pause returns only after resume data is durably handled")
    let resumeBytes = Data("opaque controlled resume data".utf8)
    old.finishPause(resumeBytes)
    XCTAssertEqual(result.done.wait(timeout: .now() + 2), .success)
    XCTAssertNil(result.error)
    XCTAssertEqual(try f.status(), "paused")
    XCTAssertEqual(try f.bytes(), 1_048_576)
    XCTAssertEqual(try Data(contentsOf: f.file(".resume")), resumeBytes)
    _ = try f.coordinator.start(f.model)
    let current = try XCTUnwrap(f.transport.tasks.last)
    XCTAssertNotEqual(old.taskIdentifier, current.taskIdentifier)
    XCTAssertEqual(f.transport.resumeData, [resumeBytes])
    XCTAssertEqual(f.transport.urls.count, 1)
    XCTAssertFalse(FileManager.default.fileExists(atPath: f.file(".resume").path))
    let staleFile = try f.temporary(f.payload)
    f.progress(old, f.model.bytes)
    f.finish(old, staleFile)
    f.complete(old, NSError(domain: NSURLErrorDomain, code: NSURLErrorCancelled))
    XCTAssertEqual(try f.status(), "downloading")
    XCTAssertEqual(try f.bytes(), 1_048_576)
    XCTAssertTrue(FileManager.default.fileExists(atPath: staleFile.path), "stale finish cannot consume another callback's file")
    XCTAssertFalse(FileManager.default.fileExists(atPath: f.file(".gguf").path))
    f.finish(current, try f.temporary(f.payload))
    XCTAssertEqual(try f.status(), "ready")
    XCTAssertEqual(try Data(contentsOf: f.file(".gguf")), f.payload)
  }

  func testDownloaderTransportDeletionWaitsForExactNewTaskAndRejectsOldCallbacks() throws {
    let f = try Fixture()
    _ = try f.coordinator.start(f.model)
    let old = try XCTUnwrap(f.transport.tasks.first)
    let paused = expectation(description: "old task pause")
    old.onPause = { paused.fulfill() }
    let pauseResult = OperationResult()
    DispatchQueue.global().async {
      do { _ = try f.coordinator.pause(f.model); pauseResult.finish(nil) }
      catch { pauseResult.finish(error) }
    }
    wait(for: [paused], timeout: 2)
    old.finishPause(Data("resume old task".utf8))
    XCTAssertEqual(pauseResult.done.wait(timeout: .now() + 2), .success)
    XCTAssertNil(pauseResult.error)
    _ = try f.coordinator.start(f.model)
    let current = try XCTUnwrap(f.transport.tasks.last)
    try Data("partial".utf8).write(to: f.file(".part"))
    try Data("remaining resume".utf8).write(to: f.file(".resume"))
    let cancelling = expectation(description: "delete cancelled current task")
    current.onCancel = { cancelling.fulfill() }
    let deletion = OperationResult()
    DispatchQueue.global().async {
      do { try f.coordinator.delete(f.model); deletion.finish(nil) }
      catch { deletion.finish(error) }
    }
    wait(for: [cancelling], timeout: 2)
    defer { f.complete(current, NSError(domain: NSURLErrorDomain, code: NSURLErrorCancelled)) }
    XCTAssertEqual(current.cancelCount, 1)
    f.complete(old, NSError(domain: NSURLErrorDomain, code: NSURLErrorCancelled))
    // A bounded semaphore wait is the observable cancellation-barrier contract,
    // not a sleep or synthetic mutation of the coordinator's private state.
    XCTAssertEqual(deletion.done.wait(timeout: .now() + 0.1), .timedOut,
      "late completion of A cannot release deletion waiting for B")
    XCTAssertThrowsError(try f.coordinator.start(f.model)) {
      XCTAssertEqual(($0 as NSError).code, 23)
    }
    f.complete(current, NSError(domain: NSURLErrorDomain, code: NSURLErrorCancelled))
    XCTAssertEqual(deletion.done.wait(timeout: .now() + 2), .success)
    XCTAssertNil(deletion.error)
    XCTAssertNil(try f.store.modelState(f.model.id))
    for suffix in [".part", ".resume", ".gguf"] {
      XCTAssertFalse(FileManager.default.fileExists(atPath: f.file(suffix).path))
    }
    _ = try f.coordinator.start(f.model)
    let next = try XCTUnwrap(f.transport.tasks.last)
    XCTAssertNotEqual(current.taskIdentifier, next.taskIdentifier)
    f.progress(current, f.model.bytes)
    f.complete(current, NSError(domain: NSURLErrorDomain, code: NSURLErrorNetworkConnectionLost))
    XCTAssertEqual(try f.status(), "downloading")
    XCTAssertEqual(try f.bytes(), 0)
    f.finish(next, try f.temporary(f.payload))
    XCTAssertEqual(try f.status(), "ready")
  }

  func testDownloaderTransportRestorationRegistersExistingTaskBeforeStart() throws {
    let f = try Fixture(holdRestoration: true)
    let restored = ControlledTask(41, description: f.model.id)
    let operationEntered = expectation(description: "start entered before restoration delivery")
    let result = OperationResult()
    DispatchQueue.global().async {
      operationEntered.fulfill()
      do { _ = try f.coordinator.start(f.model); result.finish(nil) }
      catch { result.finish(error) }
    }
    wait(for: [operationEntered], timeout: 2)
    XCTAssertEqual(result.done.wait(timeout: .now() + 0.1), .timedOut)
    XCTAssertTrue(f.transport.tasks.isEmpty)
    f.transport.restore([restored, ControlledTask(42, description: "not-in-fixture-catalog"), ControlledTask(43)])
    XCTAssertEqual(result.done.wait(timeout: .now() + 2), .success)
    XCTAssertNil(result.error)
    XCTAssertTrue(f.transport.tasks.isEmpty, "restored registered task prevents duplicate download creation")
    XCTAssertEqual(restored.resumeCount, 0, "restoration must not explicitly re-resume OS-owned tasks")
    f.progress(restored, 1_048_576)
    XCTAssertEqual(try f.status(), "downloading")
    XCTAssertEqual(try f.bytes(), 1_048_576)
    f.finish(restored, try f.temporary(f.payload))
    XCTAssertEqual(try f.status(), "ready")
    XCTAssertEqual(try Data(contentsOf: f.file(".gguf")), f.payload)
  }

  private final class OperationResult {
    private let lock = NSLock()
    private var storedError: Error?
    private var completed = false
    let done = DispatchSemaphore(value: 0)
    func finish(_ error: Error?) {
      lock.lock(); storedError = error; completed = true; lock.unlock()
      done.signal()
    }
    var error: Error? { lock.lock(); defer { lock.unlock() }; return storedError }
    var isComplete: Bool { lock.lock(); defer { lock.unlock() }; return completed }
  }

  // A late completion for paused task A must not release deletion of newer task B.
  func testDeletionWaiterRejectsOldTaskCompletionUntilItsExactTaskCompletes() {
    let oldTaskIdentifier=41
    let deletingTaskIdentifier=42
    let waiter=ModelDownloadDeletionWaiter(taskIdentifier:deletingTaskIdentifier)

    XCTAssertEqual(waiter.wait(timeout:.now()),.timedOut)
    XCTAssertFalse(waiter.signalIfOwned(by:oldTaskIdentifier),
      "a late completion for paused task A must not release deletion of newer task B")
    XCTAssertEqual(waiter.wait(timeout:.now()),.timedOut,
      "the deletion barrier must remain pending after the old task completes")

    XCTAssertTrue(waiter.signalIfOwned(by:deletingTaskIdentifier))
    XCTAssertEqual(waiter.wait(timeout:.now()),.success,
      "only the exact task being deleted may release its cancellation barrier")
  }

  func testStoreNextLegacySnapshotMigrationReconstructsOnlyPreV4Inputs() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-next-legacy-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let source = directory.appendingPathComponent("legacy.pdf")
    try Data("actual persisted legacy source".utf8).write(to: source)
    let store = try TranslatorStore(directory: directory)
    let digest = try sha256(at: source)
    _ = try store.saveDocument(id: "legacy-document", name: "Legacy.pdf", path: source.path, pages: 1, sourceSHA256: digest)
    _ = try store.saveGlossary("legacy-glossary", source: "bearing", target: "подшипник", sourceLanguage: "en", targetLanguage: "ru")
    let model = try ModelCatalog.get(ModelCatalog.recommendedId)
    let input: [String: Any] = ["documentId": "legacy-document", "modelId": model.id, "sourceLanguage": "en", "targetLanguage": "ru", "glossaryId": "legacy-glossary"]
    _ = try store.addJob(id: "legacy-job", input: input, pageCount: 1)
    var optedOut = input
    optedOut["useGlossary"] = false
    _ = try store.addJob(id: "v4-job", input: optedOut, pageCount: 1)
    try store.savePage(jobId: "legacy-job", index: 0, status: "partial", blocks: "[{\"id\":\"legacy-block\",\"translatedText\":\"Подшипник\",\"status\":\"translated\"}]")
    // A real legacy v3 row has missing pinned snapshots; the v4 opted-out row is a valid empty snapshot.
    try store.execute("UPDATE documents SET source_sha256='' WHERE id=?", ["legacy-document"])
    try store.execute("UPDATE jobs SET pipeline_version=3,model_revision='',source_sha256='',glossary_snapshot='' WHERE id=?", ["legacy-job"])
    try store.execute("UPDATE jobs SET glossary_snapshot='' WHERE id=?", ["v4-job"])

    let reopened = try TranslatorStore(directory: directory)
    XCTAssertEqual(try reopened.documentSHA256("legacy-document"), digest)
    let legacy = try XCTUnwrap(reopened.job("legacy-job"))
    XCTAssertEqual(legacy["modelRevision"] as? String, model.revision)
    XCTAssertEqual(legacy["sourceSHA256"] as? String, digest)
    XCTAssertEqual(try reopened.jobGlossaryText("legacy-job"), "bearing translates to подшипник")
    XCTAssertEqual(try reopened.pageRows("legacy-job").count, 1, "snapshot repair must preserve the existing page checkpoint")
    XCTAssertEqual(try reopened.jobGlossaryText("v4-job"), "", "a current v4 opt-out must not be refilled from today's glossary")
  }

  func testStoreNextSQLiteQueryPreservesColumnTypesAndRejectsInvalidUtf8() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-next-sqlite-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = try TranslatorStore(directory: directory)
    let row = try XCTUnwrap(store.query("SELECT 2.5 AS real_value, x'' AS empty_blob, x'0102' AS bytes, NULL AS null_value, '' AS empty_text, CAST(x'410042' AS TEXT) AS embedded_nul").first)
    XCTAssertEqual(row["real_value"] as? Double, 2.5)
    XCTAssertEqual(row["empty_blob"] as? Data, Data(), "an empty BLOB is distinct from SQL NULL")
    XCTAssertEqual(row["bytes"] as? Data, Data([1, 2]))
    XCTAssertTrue(row["null_value"] is NSNull)
    XCTAssertEqual(row["empty_text"] as? String, "")
    XCTAssertEqual(row["embedded_nul"] as? String, "A\0B")
    XCTAssertThrowsError(try store.query("SELECT CAST(x'80' AS TEXT) AS malformed_utf8")) {
      XCTAssertEqual(($0 as NSError).code, 3)
      XCTAssertTrue(($0 as NSError).localizedDescription.contains("invalid UTF-8"))
      XCTAssertTrue(($0 as NSError).localizedDescription.contains("malformed_utf8"))
    }
  }

  func testStoreNextExternalJobDeletionPreservesSourceAndDocument() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-next-external-job-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let source = directory.appendingPathComponent("user-source.pdf")
    try Data("source outside private Documents".utf8).write(to: source)
    let store = try TranslatorStore(directory: directory)
    _ = try store.saveDocument(id: "external-document", name: "External.pdf", path: source.path, pages: 1, sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: "external-job", input: ["documentId": "external-document", "modelId": ModelCatalog.recommendedId, "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 1)
    try store.savePage(jobId: "external-job", index: 0, status: "partial", blocks: "[{\"status\":\"translated\"}]")

    XCTAssertNil(try store.deleteJobAndReleaseUnsharedDocument("external-job"), "the Store must not take ownership of a source outside its private Documents directory")
    XCTAssertNil(try store.job("external-job"))
    XCTAssertTrue(try store.pageRows("external-job").isEmpty, "deleting the job should cascade only its checkpoint")
    XCTAssertEqual(try store.documentPath("external-document"), source.path)
    XCTAssertEqual(try Data(contentsOf: source), Data("source outside private Documents".utf8))
  }

  func testStoreNextUntranslatedDeletionEnforcesPrivatePathOwnership() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-next-raw-delete-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    try FileManager.default.createDirectory(at: AppPaths.documents, withIntermediateDirectories: true)
    let privateId = "unused-private-\(UUID().uuidString)"
    let externalSource = directory.appendingPathComponent("unused-external.pdf")
    let privateSource = AppPaths.documents.appendingPathComponent(privateId + ".pdf")
    defer {
      try? FileManager.default.removeItem(at: directory)
      try? FileManager.default.removeItem(at: privateSource)
    }
    try Data("external raw import".utf8).write(to: externalSource)
    try Data("private raw import".utf8).write(to: privateSource)
    let store = try TranslatorStore(directory: directory)
    _ = try store.saveDocument(id: "external-raw", name: "External.pdf", path: externalSource.path, pages: 1, sourceSHA256: sha256(at: externalSource))
    _ = try store.saveDocument(id: privateId, name: "Private.pdf", path: privateSource.path, pages: 1, sourceSHA256: sha256(at: privateSource))

    XCTAssertThrowsError(try store.deleteUntranslatedDocument("external-raw")) {
      XCTAssertEqual(($0 as NSError).code, 49)
    }
    XCTAssertEqual(try store.documentPath("external-raw"), externalSource.path)
    XCTAssertTrue(FileManager.default.fileExists(atPath: externalSource.path))
    XCTAssertEqual(try store.deleteUntranslatedDocument(privateId), privateSource.path)
    XCTAssertNil(try store.documentPath(privateId))
    XCTAssertTrue(FileManager.default.fileExists(atPath: privateSource.path), "Store returns the owned path; caller controls file cleanup")
    try FileManager.default.removeItem(at: privateSource)
  }

  func testStoreNextCorruptAppPreferencesUseSafeReadFallbacks() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-next-preferences-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = try TranslatorStore(directory: directory)
    try store.setSetting("app_rating", "6")
    try store.setSetting("library_sort", "alphabetical")
    let reopened = try TranslatorStore(directory: directory)
    let preferences = try reopened.appPreferences()
    XCTAssertEqual(preferences["appRating"] as? Int, 0)
    XCTAssertEqual(preferences["librarySort"] as? String, "recent")
    XCTAssertEqual(try reopened.setting("app_rating", fallback: ""), "6", "reading a fallback must not silently rewrite the persisted value")
    XCTAssertEqual(try reopened.setting("library_sort", fallback: ""), "alphabetical")
  }

  func testStoreNextNativePageReadErrorKeepsSavedCheckpoint() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-next-page-read-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let source = directory.appendingPathComponent("invalid.pdf")
    try Data("not a PDF".utf8).write(to: source)
    let store = try TranslatorStore(directory: directory)
    _ = try store.saveDocument(id: "invalid-document", name: "Invalid.pdf", path: source.path, pages: 1, sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: "invalid-page-job", input: ["documentId": "invalid-document", "modelId": ModelCatalog.recommendedId, "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 1)
    let checkpoint = "[{\"id\":\"p0\",\"sourceText\":\"Durable checkpoint\",\"status\":\"pending\"}]"
    try store.savePage(jobId: "invalid-page-job", index: 0, status: "partial", blocks: checkpoint, error: "saved for retry")

    XCTAssertThrowsError(try store.page("invalid-page-job", index: 0)) {
      XCTAssertEqual(($0 as NSError).domain, "PdfTranslator")
      XCTAssertEqual(($0 as NSError).code, 3)
    }
    XCTAssertEqual(try store.pageRows("invalid-page-job").count, 1)
    XCTAssertEqual(try store.query("SELECT blocks_json,error FROM pages WHERE job_id=? AND page_index=0", ["invalid-page-job"]).first?["blocks_json"] as? String, checkpoint)
    XCTAssertEqual(try store.query("SELECT error FROM pages WHERE job_id=? AND page_index=0", ["invalid-page-job"]).first?["error"] as? String, "saved for retry")
  }


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

  func testStoreEngineSelectionPersistsAndRejectsInvalidChoicesWithoutMutation() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-engine-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = try TranslatorStore(directory: directory)
    let local = ["kind": "local", "id": ModelCatalog.balancedId]
    XCTAssertEqual(try store.setSelectedEngine(local), local)
    XCTAssertEqual(try store.selectedModelId(), ModelCatalog.balancedId)
    let online = ["kind": "online", "id": "mymemory"]
    XCTAssertEqual(try store.setSelectedEngine(online), online)
    XCTAssertEqual(try TranslatorStore(directory: directory).selectedEngine(), online)
    XCTAssertEqual(try store.selectedModelId(), ModelCatalog.balancedId, "selecting online translation must preserve the local preference")
    let savedEngine = try store.setting("selected_engine", fallback: "")
    let invalid: [[String: Any]] = [[:], ["kind": NSNull(), "id": "mymemory"],
      ["kind": "online", "id": 7], ["kind": "online", "id": "unknown"],
      ["kind": "local", "id": "unknown"], ["kind": "unsupported", "id": ModelCatalog.compactId]]
    for input in invalid {
      XCTAssertThrowsError(try store.setSelectedEngine(input)) {
        XCTAssertEqual(($0 as NSError).domain, "PdfTranslator")
        XCTAssertEqual(($0 as NSError).code, 34)
      }
      XCTAssertEqual(try store.setting("selected_engine", fallback: ""), savedEngine)
      XCTAssertEqual(try store.selectedModelId(), ModelCatalog.balancedId)
    }
    for corrupt in ["not JSON", "[]", "{\"kind\":\"local\",\"id\":\"removed\"}"] {
      try store.setSetting("selected_engine", corrupt)
      XCTAssertEqual(try store.selectedEngine(), local, "invalid persisted engine data must repair to the saved local model")
      XCTAssertEqual(try TranslatorStore(directory: directory).selectedEngine(), local)
    }
  }

  func testStoreOnlineSnapshotRejectsChangedMissingAndMalformedSourcesWithoutLosingCheckpoint() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-online-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = try TranslatorStore(directory: directory)
    // This is a hash/SQLite boundary fixture, not a PDF parsing fixture.
    let source = directory.appendingPathComponent("source.pdf")
    let original = Data("immutable online source snapshot".utf8)
    try original.write(to: source)
    let digest = try sha256(at: source)
    _ = try store.saveDocument(id: "source", name: "source.pdf", path: source.path, pages: 1, sourceSHA256: digest)
    _ = try store.addJob(id: "online", input: ["documentId": "source", "engine": ["kind": "online", "id": "mymemory"],
      "onlineConsent": true, "sourceLanguage": "en", "targetLanguage": "ru"], pageCount: 1)
    let blocks = "[{\"id\":\"p0\",\"onlineFragments\":[{\"translatedText\":\"saved chunk\"}]}]"
    try store.savePage(jobId: "online", index: 0, status: "running", blocks: blocks)
    try store.validateOnlineSnapshot("online")
    try TranslatorStore(directory: directory).validateOnlineSnapshot("online")
    XCTAssertThrowsError(try store.validateOnlineSnapshot("missing")) { XCTAssertEqual(($0 as NSError).code, 6) }
    try Data("changed after consent".utf8).write(to: source, options: .atomic)
    XCTAssertThrowsError(try store.validateOnlineSnapshot("online")) {
      XCTAssertEqual(($0 as NSError).domain, "PdfTranslator")
      XCTAssertEqual(($0 as NSError).code, 6)
    }
    try original.write(to: source, options: .atomic)
    for malformed in ["''", "x'0102'"] {
      try store.execute("UPDATE jobs SET source_sha256=\(malformed) WHERE id='online'")
      XCTAssertThrowsError(try store.validateOnlineSnapshot("online")) { XCTAssertEqual(($0 as NSError).code, 6) }
    }
    try store.execute("UPDATE jobs SET source_sha256=? WHERE id='online'", [digest])
    try FileManager.default.removeItem(at: source)
    XCTAssertThrowsError(try store.validateOnlineSnapshot("online"), "an unavailable actual source must fail, not reuse a saved digest")
    try original.write(to: source)
    try store.validateOnlineSnapshot("online")
    XCTAssertEqual(try store.query("SELECT source_sha256 FROM jobs WHERE id='online'").first?["source_sha256"] as? String, digest)
    XCTAssertEqual(try store.query("SELECT blocks_json FROM pages WHERE job_id='online'").first?["blocks_json"] as? String, blocks)
    XCTAssertEqual(try store.job("online")?["status"] as? String, "queued", "snapshot validation must not alter the durable job status")
  }

  func testStoreGlossaryTextOrdersScopesAndSkipsNonTextPersistedRows() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-glossary-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = try TranslatorStore(directory: directory)
    XCTAssertEqual(try store.glossaryText(nil), "")
    _ = try store.saveGlossary("z", source: " ZULU \n", target: " конец ", sourceLanguage: "en", targetLanguage: "ru")
    _ = try store.saveGlossary("a", source: " alpha ", target: " начало ", sourceLanguage: "en", targetLanguage: "ru")
    _ = try store.saveGlossary("reverse", source: "начало", target: "alpha", sourceLanguage: "ru", targetLanguage: "en")
    XCTAssertEqual(try store.glossaryText(nil), "alpha translates to начало\nZULU translates to конец")
    XCTAssertEqual(try store.glossaryText("z"), "ZULU translates to конец")
    XCTAssertEqual(try store.glossaryText("reverse"), "", "an explicit ID must still match the requested language direction")
    XCTAssertEqual(try store.glossaryText("missing"), "")
    XCTAssertEqual(try store.glossaryText(nil, sourceLanguage: "ru", targetLanguage: "en"), "начало translates to alpha")
    // SQLite TEXT affinity permits BLOB values in a malformed historical row.
    try store.execute("INSERT INTO glossary(id,source,target,source_lang,target_lang,created_at) VALUES('bad-source',x'0102','target','en','ru',0),('bad-target','source',x'0304','en','ru',0)")
    XCTAssertEqual(try store.glossaryText(nil), "alpha translates to начало\nZULU translates to конец", "malformed non-text terms must not leak byte descriptions into model prompts")
    XCTAssertEqual(try store.glossaryText("bad-source"), "")
    XCTAssertEqual(try store.glossaryText("bad-target"), "")
    for pair in [("xx", "ru", 30), ("en", "en", 31)] {
      XCTAssertThrowsError(try store.glossaryText(nil, sourceLanguage: pair.0, targetLanguage: pair.1)) {
        XCTAssertEqual(($0 as NSError).domain, "PdfTranslator")
        XCTAssertEqual(($0 as NSError).code, pair.2)
      }
    }
    try store.deleteGlossary("a")
    XCTAssertEqual(try store.glossaryText("a"), "")
    XCTAssertEqual(try store.glossaryText(nil), "ZULU translates to конец")
  }

  func testStoreDocxSegmentCountAndPreviewPageBoundsRemainSeparate() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-segments-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = try TranslatorStore(directory: directory)
    let source = directory.appendingPathComponent("source.docx")
    try Data("store metadata only".utf8).write(to: source)
    _ = try store.saveDocument(id: "docx", name: "source.docx", path: source.path, pages: 3, sourceSHA256: sha256(at: source), format: "docx")
    XCTAssertEqual(try store.documentSegmentCount("docx"), 3)
    XCTAssertEqual(try store.documentSegmentCount("missing"), 0)
    for count in [-1, 0, 10_001] { try store.updatePreviewPageCount("docx", count) }
    XCTAssertEqual(try store.documents().first?["pageCount"] as? Int64, 0)
    try store.updatePreviewPageCount("docx", 2)
    XCTAssertEqual(try store.documents().first?["pageCount"] as? Int64, 2)
    XCTAssertEqual(try store.documentSegmentCount("docx"), 3, "preview pages must not rewrite translation segment progress")
    _ = try store.saveDocument(id: "pdf", name: "source.pdf", path: source.path, pages: 7, sourceSHA256: sha256(at: source))
    try store.updatePreviewPageCount("pdf", 9)
    XCTAssertEqual(try store.documents().first { $0["id"] as? String == "pdf" }?["pageCount"] as? Int64, 7)
    XCTAssertEqual(try store.documentSegmentCount("pdf"), 0)
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
        for url in (try? FileManager.default.contentsOfDirectory(at:AppPaths.exports,includingPropertiesForKeys:nil)) ?? [] where url.lastPathComponent.hasPrefix(jobID+"-") { try? FileManager.default.removeItem(at:url) }
      }
      try? store.deleteDocument(id)
      try? FileManager.default.removeItem(at: source)
    }
    // Build a genuine PDFium text object so the real pipeline has durable
    // source ownership metadata. Quartz's UIKit text drawing can become an
    // OCR-only outline, which is intentionally rejected by source-label guards.
    let content = "BT /F1 20 Tf 30 250 Td (Hello.) Tj ET"
    let stream = "<< /Length \(content.utf8.count) >>\nstream\n\(content)\nendstream"
    let objects = [
      "<< /Type /Catalog /Pages 2 0 R >>",
      "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
      "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 300] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
      "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
      stream
    ]
    var pdf = Data("%PDF-1.4\n".utf8), offsets = [0]
    for (index, object) in objects.enumerated() {
      offsets.append(pdf.count)
      pdf.append(Data("\(index + 1) 0 obj\n\(object)\nendobj\n".utf8))
    }
    let xref = pdf.count
    pdf.append(Data("xref\n0 \(objects.count + 1)\n0000000000 65535 f \n".utf8))
    for offset in offsets.dropFirst() { pdf.append(Data(String(format: "%010d 00000 n \n", offset).utf8)) }
    pdf.append(Data("trailer << /Size \(objects.count + 1) /Root 1 0 R >>\nstartxref\n\(xref)\n%%EOF\n".utf8))
    try pdf.write(to: source)
    let sourcePage = try decoded(TranslateCoreBridge.readPage(source.path, index: 0))
    let sourceBlocks = try XCTUnwrap(sourcePage["blocks"] as? [[String: Any]])
    XCTAssertTrue(sourceBlocks.contains {
      $0["source"] as? String == "native"
        && (($0["sourceText"] as? String) ?? "").contains("Hello.")
        && !((($0["sourceRects"] as? [[String: Any]]) ?? []).isEmpty)
    }, "the real inference fixture must expose PDFium-owned text, not an OCR-only outline")
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
    var exported = URL(fileURLWithPath: try XCTUnwrap(record["path"] as? String))
    XCTAssertEqual(try sha256(at: exported), record["sha256"] as? String)
    XCTAssertEqual(try decoded(TranslateCoreBridge.inspect(exported.path))["pageCount"] as? Int, 1)
    let preview = try decoded(resolved { module.previewJob(runningID, resolver: $0, rejecter: $1) })
    XCTAssertEqual(preview["id"] as? String, runningID)
    // iOS may purge temporary caches between launches. A verified final PDF
    // must still produce its preview before an original-page thumbnail exists.
    try? FileManager.default.removeItem(at: AppPaths.thumbnails)
    XCTAssertFalse(FileManager.default.fileExists(atPath: AppPaths.thumbnails.path))
    let thumbnail = try XCTUnwrap(try resolved { module.renderTranslatedPage(runningID, pageIndex: 0, width: 128, resolver: $0, rejecter: $1) } as? String)
    XCTAssertTrue(FileManager.default.fileExists(atPath: try XCTUnwrap(URL(string: thumbnail)).path))
    XCTAssertEqual(try resolved { module.renderTranslatedPage(runningID, pageIndex: 0, width: 128, resolver: $0, rejecter: $1) } as? String, thumbnail)
    defer { if let url = URL(string: thumbnail) { try? FileManager.default.removeItem(at: url) } }

    // Actual file corruption invalidates the verified generation. Preview
    // rebuilds directly from saved checkpoints without repeating inference.
    let previousPath=exported.path
    try Data("damaged output".utf8).write(to: exported)
    let repairedPreview=try decoded(resolved { module.previewJob(runningID, resolver: $0, rejecter: $1) })
    XCTAssertEqual(repairedPreview["id"] as? String,runningID)
    XCTAssertTrue(["completed","partial"].contains(try store.job(runningID)?["status"] as? String ?? ""))
    let repairedRecord=try XCTUnwrap(try store.export(runningID))
    let repairedURL=URL(fileURLWithPath:try XCTUnwrap(repairedRecord["path"] as? String))
    XCTAssertNotEqual(repairedURL.path,previousPath,"corrupt published generations must be replaced by a verified immutable generation")
    exported=repairedURL
    XCTAssertEqual(try sha256(at:repairedURL),repairedRecord["sha256"] as? String)
    XCTAssertEqual(try decoded(TranslateCoreBridge.inspect(repairedURL.path))["pageCount"] as? Int,1)
    XCTAssertEqual(try store.pageRows(runningID).count, 1)
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
    rejected("DOCUMENT_DELETE_FAILED") { module.deleteDocument(missing, resolver: $0, rejecter: $1) }
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
    XCTAssertEqual(DocxPreviewRenderer.Failure.assets.errorDescription, "The offline Word preview components are unavailable.")
    XCTAssertEqual(DocxPreviewRenderer.Failure.pdf.errorDescription, "The Word preview could not be printed as a PDF.")
    XCTAssertEqual(DocxPreviewRenderer.Failure.web("load").errorDescription, "load")
    let missing = FileManager.default.temporaryDirectory.appendingPathComponent("missing-preview-\(UUID().uuidString).docx")
    let cache = FileManager.default.temporaryDirectory.appendingPathComponent("preview-\(UUID().uuidString).pdf")
    let completed = expectation(description: "missing DOCX preview is rejected")
    DocxPreviewRenderer.render(source: missing, cacheURL: cache) { result in
      guard case .failure(let error) = result else { XCTFail("Missing DOCX source must fail before creating a WebView"); completed.fulfill(); return }
      XCTAssertEqual(error as? DocxPreviewRenderer.Failure, .source)
      XCTAssertEqual(error.localizedDescription, "The Word document could not be opened for preview.")
      XCTAssertFalse(FileManager.default.fileExists(atPath: cache.path))
      completed.fulfill()
    }
    wait(for: [completed], timeout: 5)
  }

  func testOCRMatchSuppressesNormalizedNestedTextWithTenPercentGlyphBoundsTolerance() {
    let native: [[String: Any]] = [[
      "source": "native", "sourceText": "Set the pressure to 20 kPa before inspection.",
      "left": 10.0, "top": 10.0, "right": 220.0, "bottom": 70.0,
      "sourceRects": [["objectIndex": 4, "text": "Set the pressure to 20 kPa before inspection.",
        "left": 10.0, "top": 10.0, "right": 100.0, "bottom": 30.0]]
    ]]
    let match = PdfTranslatorModule.matchOCRBlock(existing: native, text: "pressure\u{00a0}to 20 kPa",
                                                   left: 10, top: 11, right: 110, bottom: 29)
    XCTAssertEqual(match?.index, 0)
    XCTAssertEqual(match?.sourceDisagreement, false)
    let merged = PdfTranslatorModule.mergeOCRBlocks(existing: native, recognized: [[
      "source": "ocr", "sourceText": "pressure\u{00a0}to 20 kPa", "text": "pressure to 20 kPa",
      "left": 10.0, "top": 11.0, "right": 110.0, "bottom": 29.0
    ]])
    XCTAssertEqual(merged.count, 1, "normalized native paragraph text should absorb the duplicate OCR line")

    let outsideTolerance = PdfTranslatorModule.matchOCRBlock(existing: native, text: "pressure to 20 kPa",
                                                              left: 10, top: 11, right: 212, bottom: 29)
    XCTAssertNil(outsideTolerance, "text outside the 90% OCR-box intersection must remain available for review")
  }

  func testOCRInsideDigitalIllustrationIsSuppressedButCaptionAndPartialOverlapRemain() {
    let native:[[String:Any]]=[[
      "source":"native","sourceText":"A long original page paragraph.",
      "sourceRects":[["objectIndex":7,"pictogram":false,
        "text":"The operating system controls engine temperature during normal driving conditions.",
        "left":70.0,"top":10.0,"right":98.0,"bottom":30.0]]
    ]]
    let artwork:[[String:Any]]=[[
      "objectIndex":8,"type":"image","left":0.0,"top":0.0,"right":65.0,"bottom":100.0,
      "coordinateSpaceVersion":2
    ]]
    let merged=PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:[
      ["source":"ocr","sourceText":"Warning label","left":10.0,"top":20.0,"right":40.0,"bottom":35.0],
      ["source":"ocr","sourceText":"Caption","left":70.0,"top":40.0,"right":96.0,"bottom":50.0],
      ["source":"ocr","sourceText":"Outside image","left":70.0,"top":70.0,"right":96.0,"bottom":80.0],
      ["source":"ocr","sourceText":"Crosses edge","left":60.0,"top":40.0,"right":80.0,"bottom":50.0]
    ],artworkRects:artwork,pageWidth:100,pageHeight:100)
    XCTAssertEqual(merged.count,4,"the image-contained label is excluded while caption, outside text and <95% overlap stay OCR")
    XCTAssertEqual(merged.dropFirst().compactMap{$0["sourceText"] as? String},
                   ["Caption","Outside image","Crosses edge"])
  }

  func testFullPageScanAndPagesWithoutLongNativeTextKeepOCR() {
    let longNative:[[String:Any]]=[[
      "source":"native","sourceText":"native paragraph",
      "sourceRects":[["objectIndex":2,"pictogram":false,
        "text":"The transmission operating temperature should be checked before service.",
        "left":5.0,"top":5.0,"right":80.0,"bottom":20.0]]
    ]]
    let recognized:[[String:Any]]=[["source":"ocr","sourceText":"Scanned warning","left":20.0,"top":20.0,"right":60.0,"bottom":40.0]]
    let fullPageImage:[[String:Any]]=[["objectIndex":3,"type":"image","coordinateSpaceVersion":2,
      "left":0.0,"top":0.0,"right":100.0,"bottom":80.0]]
    XCTAssertEqual(PdfTranslatorModule.mergeOCRBlocks(existing:longNative,recognized:recognized,
      artworkRects:fullPageImage,pageWidth:100,pageHeight:100).count,2,
      "an image covering more than 65% of a page is treated as a scanned container")

    let smallNative:[[String:Any]]=[["source":"native","sourceRects":[["objectIndex":2,"pictogram":false,
      "text":"short note","left":5.0,"top":5.0,"right":40.0,"bottom":20.0]]]]
    let illustration:[[String:Any]]=[["objectIndex":3,"type":"image","coordinateSpaceVersion":2,
      "left":10.0,"top":10.0,"right":70.0,"bottom":70.0]]
    XCTAssertEqual(PdfTranslatorModule.mergeOCRBlocks(existing:smallNative,recognized:recognized,
      artworkRects:illustration,pageWidth:100,pageHeight:100).count,2,
      "short native captions do not establish a digital-text illustration page")
    XCTAssertEqual(PdfTranslatorModule.mergeOCRBlocks(existing:[],recognized:recognized,
      artworkRects:illustration,pageWidth:100,pageHeight:100).count,1,
      "a full scan with no native text remains available for OCR")
  }

  func testMalformedIllustrationMetadataFailsOpenAndRetainsOCR() {
    let native:[[String:Any]]=[["source":"native","sourceRects":[["objectIndex":2,"pictogram":false,
      "text":"The engine temperature should be checked before driving on a long trip.",
      "left":5.0,"top":5.0,"right":80.0,"bottom":20.0]]]]
    let recognized:[[String:Any]]=[["source":"ocr","sourceText":"Label","left":20.0,"top":20.0,"right":50.0,"bottom":35.0]]
    let malformed:[[String:Any]]=[
      ["objectIndex":3,"type":"image","coordinateSpaceVersion":1,"left":10.0,"top":10.0,"right":70.0,"bottom":70.0],
      ["type":"image","coordinateSpaceVersion":2,"left":10.0,"top":10.0,"right":70.0,"bottom":70.0],
      ["objectIndex":4,"type":"image","coordinateSpaceVersion":2,"left":70.0,"top":10.0,"right":20.0,"bottom":70.0]
    ]
    XCTAssertEqual(PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:recognized,
      artworkRects:malformed,pageWidth:100,pageHeight:100).count,2)
    XCTAssertEqual(PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:recognized,
      artworkRects:[["objectIndex":3,"type":"image","coordinateSpaceVersion":2,
        "left":10.0,"top":10.0,"right":70.0,"bottom":70.0]],pageWidth:0,pageHeight:100).count,2,
      "invalid page geometry cannot be used to suppress recognized text")
  }

  func testRotatedVisiblePageArtworkUsesVersionTwoTopLeftCoordinates() {
    let native:[[String:Any]]=[["source":"native","sourceRects":[["objectIndex":2,"pictogram":false,
      "text":"The cooling system operation should be inspected during normal service.",
      "left":180.0,"top":20.0,"right":230.0,"bottom":50.0]]]]
    let artwork:[[String:Any]]=[["objectIndex":3,"type":"image","coordinateSpaceVersion":2,
      "left":30.0,"top":10.0,"right":170.0,"bottom":130.0]]
    let rotatedOCR:[[String:Any]]=[["source":"ocr","sourceText":"Diagram label",
      "left":40.0,"top":20.0,"right":90.0,"bottom":45.0]]
    XCTAssertEqual(PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:rotatedOCR,
      artworkRects:artwork,pageWidth:240,pageHeight:160).count,1,
      "CropBox-clipped, 90-degree visible-page coordinates should suppress text inside the image")
    let unrotatedOCR:[[String:Any]]=[["source":"ocr","sourceText":"Adjacent caption",
      "left":10.0,"top":30.0,"right":25.0,"bottom":45.0]]
    XCTAssertEqual(PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:unrotatedOCR,
      artworkRects:artwork,pageWidth:240,pageHeight:160).count,2,
      "coordinates outside the rotated image stay available as OCR text")
  }

  func testOCRIllustrationMatchingAllowsOnlyQuarterPointRasterOverrun() {
    let native:[[String:Any]]=[[
      "source":"native","sourceRects":[["objectIndex":2,"pictogram":false,
        "text":"The cooling system operation should be inspected during normal service.",
        "left":300.0,"top":10.0,"right":480.0,"bottom":40.0]]
    ]]
    let imageTop=151.824005
    let artwork:[[String:Any]]=[["objectIndex":8,"type":"image","coordinateSpaceVersion":2,
      "left":100.0,"top":imageTop,"right":200.0,"bottom":251.824005]]
    let rasterizedOCR:[[String:Any]]=[["source":"ocr","sourceText":"Text at image edge",
      "left":120.0,"top":151.57654,"right":180.0,"bottom":151.70]]
    XCTAssertEqual(PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:rasterizedOCR,
      artworkRects:artwork,pageWidth:500,pageHeight:500).count,1,
      "the measured 0.247465-point raster overrun is covered by the 0.25-point matching tolerance")

    let nearbyCaption:[[String:Any]]=[["source":"ocr","sourceText":"Caption just outside tolerance",
      "left":120.0,"top":151.55,"right":180.0,"bottom":151.57]]
    XCTAssertEqual(PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:nearbyCaption,
      artworkRects:artwork,pageWidth:500,pageHeight:500).count,2,
      "caption text beyond the raster tolerance remains OCR content")
  }

  func testOCRIllustrationEligibilitySumsNativeTextAcrossPageFragments() {
    let native:[[String:Any]]=[
      ["source":"native","sourceRects":[["objectIndex":2,"pictogram":false,
        "text":"Cooling system pressure must be checked", "left":250.0,"top":10.0,"right":290.0,"bottom":20.0]]],
      ["source":"native","sourceRects":[["objectIndex":3,"pictogram":false,
        "text":"before starting any maintenance work", "left":300.0,"top":10.0,"right":340.0,"bottom":20.0]]]
    ]
    let artwork:[[String:Any]]=[["objectIndex":8,"type":"image","coordinateSpaceVersion":2,
      "left":0.0,"top":0.0,"right":65.0,"bottom":100.0]]
    let recognized:[[String:Any]]=[["source":"ocr","sourceText":"Illustration copy",
      "left":10.0,"top":30.0,"right":50.0,"bottom":60.0]]
    XCTAssertEqual(PdfTranslatorModule.nativeIllustrationLetterCount(native),
      "Cooling system pressure must be checked".filter(\.isLetter).count +
        "before starting any maintenance work".filter(\.isLetter).count)
    XCTAssertLessThan("Cooling system pressure must be checked".filter(\.isLetter).count,40)
    XCTAssertLessThan("before starting any maintenance work".filter(\.isLetter).count,40)
    XCTAssertEqual(PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:recognized,
      artworkRects:artwork,pageWidth:100,pageHeight:100).count,2,
      "page-level native linguistic evidence is aggregated across short fragments")
  }

  func testGemmaUsesOnlyValidBoundedDocumentInfoTitleWhileHyMtKeepsPageContext() {
    let pageContext="Vehicle manual | Airbag system overview"
    let exactly400Bytes=String(repeating:"a",count:397)+"界"
    XCTAssertEqual(exactly400Bytes.utf8.count,400)
    XCTAssertEqual(PdfTranslatorModule.translationContext(native:[
      "documentTitle":"2020 Chevrolet Equinox Owner's Manual", "context":pageContext
    ],profile:"translategemma"),"2020 Chevrolet Equinox Owner's Manual",
      "Gemma receives InfoTitle only; inferred page headings are excluded")
    XCTAssertEqual(PdfTranslatorModule.translationContext(native:[
      "documentTitle":exactly400Bytes,"context":pageContext
    ],profile:"translategemma"),exactly400Bytes,
      "a valid multibyte InfoTitle at the exact UTF-8 byte limit is preserved whole")
    XCTAssertEqual(PdfTranslatorModule.translationContext(native:[
      "documentTitle":exactly400Bytes+"x","context":pageContext
    ],profile:"translategemma"),"",
      "an over-limit title is rejected whole and never replaced with page headings")
    XCTAssertEqual(PdfTranslatorModule.translationContext(native:[
      "documentTitle":"xy","context":pageContext
    ],profile:"translategemma"),"",
      "too-short InfoTitle is treated as missing")
    XCTAssertEqual(PdfTranslatorModule.translationContext(native:["context":pageContext],profile:"translategemma"),"")
    XCTAssertEqual(PdfTranslatorModule.translationContext(native:[
      "documentTitle":exactly400Bytes+"x","context":pageContext
    ],profile:"hy-mt2"),pageContext,
      "Hy-MT2 keeps the existing page context independently of InfoTitle validity")
  }

  func testGemmaCheckpointReuseIsBoundToEffectiveInfoTitleContext() {
    let source:[String:Any]=["id":"p0-o1","sourceText":"Airbag readiness light","objectIndex":1,
      "modelText":"Airbag readiness light","status":"pending","translatedText":NSNull()]
    func saved(_ fresh:[String:Any])->[String:Any]{
      var value=fresh;value["status"]="translated";value["translatedText"]="Индикатор готовности подушки безопасности";return value
    }
    let titleA=PdfTranslatorModule.stampingGemmaContext([source],title:"Chevrolet Equinox manual")[0]
    let titleASaved=saved(titleA)
    XCTAssertEqual(PdfTranslatorModule.restoringPdfCheckpointResults(fresh:[titleA],saved:[titleASaved],online:false)[0]["status"] as? String,"translated",
      "a checkpoint with the same effective InfoTitle context is reusable")

    let titleB=PdfTranslatorModule.stampingGemmaContext([source],title:"Different document title")[0]
    XCTAssertEqual(PdfTranslatorModule.restoringPdfCheckpointResults(fresh:[titleB],saved:[titleASaved],online:false)[0]["status"] as? String,"pending",
      "changing InfoTitle invalidates only the affected saved translation")

    var legacy=saved(source)
    XCTAssertEqual(PdfTranslatorModule.restoringPdfCheckpointResults(fresh:[titleA],saved:[legacy],online:false)[0]["status"] as? String,"pending",
      "legacy Gemma results are retried when the current InfoTitle is nonempty")
    let emptyTitle=PdfTranslatorModule.stampingGemmaContext([source],title:"")[0]
    XCTAssertEqual(PdfTranslatorModule.restoringPdfCheckpointResults(fresh:[emptyTitle],saved:[legacy],online:false)[0]["status"] as? String,"translated",
      "legacy Gemma results remain reusable when the current effective InfoTitle context is empty")
    var malformedLegacy=saved(source)
    malformedLegacy["translationContextKey"]=42
    XCTAssertEqual(PdfTranslatorModule.restoringPdfCheckpointResults(fresh:[emptyTitle],saved:[malformedLegacy],online:false)[0]["status"] as? String,"pending",
      "a malformed persisted context key is not treated as a legacy missing key")
    XCTAssertFalse(PdfTranslatorModule.pdfCheckpointMatches(titleASaved,fresh:source,online:false),
      "a Gemma context-tagged result cannot be reused by a fresh block missing the context key")

    var onlineLegacy=legacy
    onlineLegacy["translationContextKey"]="obsolete-local-context"
    var onlineFresh=source
    onlineFresh["translationContextKey"]="new-local-context"
    XCTAssertEqual(PdfTranslatorModule.restoringPdfCheckpointResults(fresh:[onlineFresh],saved:[onlineLegacy],online:true)[0]["status"] as? String,"translated",
      "Online checkpoints stay independent of local model context metadata")
  }

  func testGemmaBoundaryDecoderStripsOnlySafeOuterTransportMarkers() {
    let valid=PdfTranslatorModule.decodeGemmaPromptBoundaryEnvelope(
      "  <source_segment>  Переведённый текст.  </source_segment>  ",
      sourceText:"Airbag readiness light",modelText:"Airbag readiness light")
    XCTAssertFalse(valid.invalid)
    XCTAssertEqual(valid.text,"Переведённый текст.")
    XCTAssertEqual(valid.removedTokens,["<source_segment>","</source_segment>"])

    let prefixOnly=PdfTranslatorModule.decodeGemmaPromptBoundaryEnvelope(
      "<source_segment>Текст без закрывающей метки",sourceText:"source",modelText:"source")
    XCTAssertFalse(prefixOnly.invalid)
    XCTAssertEqual(prefixOnly.text,"Текст без закрывающей метки")
    XCTAssertEqual(prefixOnly.removedTokens,["<source_segment>"])

    let suffixOnly=PdfTranslatorModule.decodeGemmaPromptBoundaryEnvelope(
      "Обычный текст</source_segment>",sourceText:"source",modelText:"source")
    XCTAssertFalse(suffixOnly.invalid)
    XCTAssertEqual(suffixOnly.text,"Обычный текст")
    XCTAssertEqual(suffixOnly.removedTokens,["</source_segment>"])

    let arbitraryMarkup=PdfTranslatorModule.decodeGemmaPromptBoundaryEnvelope(
      "<strong>Keep this markup</strong>",sourceText:"source",modelText:"source")
    XCTAssertFalse(arbitraryMarkup.invalid)
    XCTAssertEqual(arbitraryMarkup.text,"<strong>Keep this markup</strong>")
    XCTAssertTrue(arbitraryMarkup.removedTokens.isEmpty)
  }

  func testGemmaBoundaryDecoderRejectsMalformedOrConflictingInventories() {
    let source="Literal <source_segment> and </source_segment> tokens"
    let exactLiteral=PdfTranslatorModule.decodeGemmaPromptBoundaryEnvelope(
      "Литерал <source_segment> и </source_segment>",sourceText:source,
      modelText:"Literal <source_segment> and </source_segment> tokens")
    XCTAssertFalse(exactLiteral.invalid,"matching source/model/target marker inventories are literal content")
    XCTAssertEqual(exactLiteral.text,"Литерал <source_segment> и </source_segment>")
    XCTAssertTrue(exactLiteral.removedTokens.isEmpty,"literal markup is never stripped")

    let mismatchedSourceAndModel=PdfTranslatorModule.decodeGemmaPromptBoundaryEnvelope(
      "Перевод <source_segment> и </source_segment>",sourceText:source,
      modelText:"Literal <source_segment> only")
    XCTAssertTrue(mismatchedSourceAndModel.invalid,"conflicting source and effective model inventories fail closed")

    let mismatchedTarget=PdfTranslatorModule.decodeGemmaPromptBoundaryEnvelope(
      "Перевод <source_segment>",sourceText:source,
      modelText:"Literal <source_segment> and </source_segment> tokens")
    XCTAssertTrue(mismatchedTarget.invalid,"target literal marker counts must match the model input")

    for malformed in [
      "<source_segment>outer <source_segment>nested</source_segment>",
      "</source_segment>reversed<source_segment>",
      "<source_segment></source_segment>",
      "body</source_segment>tail</source_segment>",
      "body<source_segment>internal"
    ] {
      XCTAssertTrue(PdfTranslatorModule.decodeGemmaPromptBoundaryEnvelope(
        malformed,sourceText:"ordinary source",modelText:"ordinary source").invalid,malformed)
    }
    let whitespace=PdfTranslatorModule.decodeGemmaPromptBoundaryEnvelope("  \n ",sourceText:"source",modelText:"source")
    XCTAssertTrue(whitespace.invalid,"blank model output is malformed even when it has no marker")
    XCTAssertEqual(whitespace.text,"  \n ")
  }

  func testGemmaCheckpointBoundaryDecodeAndOneBoundedRepairPreserveSiblings() {
    let titleBlocks=PdfTranslatorModule.stampingGemmaContext([
      ["id":"p0-o1","sourceText":"Airbag readiness light","modelText":"Airbag readiness light",
       "objectIndex":1,"status":"pending","translatedText":NSNull()],
      ["id":"p0-o2","sourceText":"Warning label","modelText":"Warning label",
       "objectIndex":2,"status":"pending","translatedText":NSNull()]
    ],title:"Equinox owner manual")
    var safelyWrapped=titleBlocks[0]
    safelyWrapped["status"]="translated"
    safelyWrapped["translatedText"]="<source_segment>Индикатор подушки безопасности</source_segment>"
    XCTAssertFalse(PdfTranslatorModule.gemmaCheckpointRequiresInference(safelyWrapped),
      "a safe wrapper is repaired without loading or invoking the model")
    safelyWrapped["layoutRetryCount"]=2
    safelyWrapped["layoutRetryHistory"]=[["attempt":1,"error":"fit"],["attempt":2,"error":"fit"]]
    var decoded=safelyWrapped
    XCTAssertEqual(PdfTranslatorModule.prepareGemmaCheckpointArtifact(saved:safelyWrapped,fresh:&decoded),.decoded)
    XCTAssertEqual(decoded["translatedText"] as? String,"Индикатор подушки безопасности")
    XCTAssertEqual(decoded["gemmaPromptArtifactRawText"] as? String,safelyWrapped["translatedText"] as? String)
    XCTAssertEqual(decoded["gemmaPromptArtifactDecodedTokens"] as? [String],["<source_segment>","</source_segment>"])
    XCTAssertEqual(decoded["layoutRetryCount"] as? Int,2)
    XCTAssertEqual((decoded["layoutRetryHistory"] as? [[String:Any]])?.count,2)

    var blankCheckpoint=safelyWrapped
    blankCheckpoint["translatedText"]=" \n "
    var blankFresh=titleBlocks[0]
    XCTAssertTrue(PdfTranslatorModule.gemmaCheckpointRequiresInference(blankCheckpoint),
      "a completed non-null blank result must request bounded repair")
    XCTAssertEqual(PdfTranslatorModule.prepareGemmaCheckpointArtifact(saved:blankCheckpoint,fresh:&blankFresh),.retry)
    XCTAssertEqual(blankFresh["gemmaPromptArtifactRawText"] as? String," \n ",
      "blank checkpoint text is retained for diagnosis instead of being treated as absent")
    XCTAssertEqual(blankFresh["gemmaPromptArtifactRepairPendingRevision"] as? String,PdfTranslatorModule.gemmaSourceBoundaryRepairRevision)

    var malformed=safelyWrapped
    malformed["translatedText"]="<source_segment>bad <source_segment> nested"
    XCTAssertTrue(PdfTranslatorModule.gemmaCheckpointRequiresInference(malformed),
      "a malformed terminal page requests model readiness before resume")
    var retry=titleBlocks[0]
    XCTAssertEqual(PdfTranslatorModule.prepareGemmaCheckpointArtifact(saved:malformed,fresh:&retry),.retry)
    XCTAssertNil(retry["translatedText"])
    XCTAssertEqual(retry["gemmaPromptArtifactRawText"] as? String,malformed["translatedText"] as? String)
    XCTAssertEqual(retry["gemmaPromptArtifactRepairPendingRevision"] as? String,PdfTranslatorModule.gemmaSourceBoundaryRepairRevision)
    XCTAssertTrue(PdfTranslatorModule.gemmaCheckpointRequiresInference(retry),
      "a pending bounded repair continues after a pause")
    XCTAssertEqual(retry["layoutRetryCount"] as? Int,2,"layout retry accounting survives prompt-artifact recovery")
    XCTAssertTrue(PdfTranslatorModule.beginGemmaCheckpointArtifactRepair(&retry))
    XCTAssertEqual(retry["gemmaPromptArtifactRepairRevision"] as? String,PdfTranslatorModule.gemmaSourceBoundaryRepairRevision)
    XCTAssertFalse(PdfTranslatorModule.beginGemmaCheckpointArtifactRepair(&retry),"the repair is bounded to one inference")

    PdfTranslatorModule.rejectGeneratedGemmaPromptArtifact(&retry,candidate:"</source_segment>still malformed")
    XCTAssertEqual(retry["status"] as? String,"failed")
    XCTAssertEqual(retry["translatedText"] as? NSNull,NSNull())
    XCTAssertEqual(retry["gemmaPromptArtifactRejectedText"] as? String,"</source_segment>still malformed")
    XCTAssertEqual(retry["error"] as? String,PdfTranslatorModule.gemmaPromptArtifactError)
    var exhausted=titleBlocks[0]
    XCTAssertEqual(PdfTranslatorModule.prepareGemmaCheckpointArtifact(saved:retry,fresh:&exhausted),.exhausted)
    XCTAssertEqual(exhausted["status"] as? String,"failed")
    XCTAssertEqual(exhausted["error"] as? String,PdfTranslatorModule.gemmaPromptArtifactError)
    XCTAssertFalse(PdfTranslatorModule.gemmaCheckpointRequiresInference(exhausted),
      "a repair already exhausted is terminal and does not loop inference")

    var cleanSibling=titleBlocks[1]
    cleanSibling["status"]="translated";cleanSibling["translatedText"]="Надпись с предупреждением"
    let restored=PdfTranslatorModule.restoringPdfCheckpointResults(
      fresh:titleBlocks,saved:[malformed,cleanSibling],online:false,gemma:true)
    XCTAssertEqual(restored[0]["status"] as? String,"pending","only the contaminated checkpoint is reopened")
    XCTAssertEqual(restored[0]["gemmaPromptArtifactRepairPendingRevision"] as? String,PdfTranslatorModule.gemmaSourceBoundaryRepairRevision)
    XCTAssertEqual(restored[1]["status"] as? String,"translated")
    XCTAssertEqual(restored[1]["translatedText"] as? String,"Надпись с предупреждением")
  }

  func testStoreReadsSavedPageCheckpointWithoutReextractingTheSourcePDF() throws {
    let directory=FileManager.default.temporaryDirectory.appendingPathComponent("saved-page-checkpoint-\(UUID().uuidString)",isDirectory:true)
    try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
    defer{try? FileManager.default.removeItem(at:directory)}
    let source=directory.appendingPathComponent("manual.pdf")
    try moduleNextPDF(source,labels:["Source text"])
    let store=try TranslatorStore(directory:directory)
    let documentID="checkpoint-document"
    let jobID="checkpoint-job"
    _=try store.saveDocument(id:documentID,name:"Manual.pdf",path:source.path,pages:1,sourceSHA256:sha256(at:source))
    _=try store.addJob(id:jobID,input:["documentId":documentID,"modelId":ModelCatalog.balancedId,
      "sourceLanguage":"en","targetLanguage":"ru","useGlossary":false],pageCount:1)
    let blocks=[["id":"p0-o1","sourceText":"Source text","status":"translated","translatedText":"Текст источника"]] as [[String:Any]]
    try store.savePage(jobId:jobID,index:0,status:"completed",blocks:jsonString(blocks),error:"retained page note")

    let saved=try XCTUnwrap(store.pageCheckpoint(jobID,index:0))
    XCTAssertEqual(saved["status"] as? String,"completed")
    XCTAssertEqual(saved["error"] as? String,"retained page note")
    XCTAssertEqual((saved["blocks"] as? [[String:Any]])?.first?["translatedText"] as? String,"Текст источника")
    XCTAssertNil(try store.pageCheckpoint(jobID,index:1))
  }

  func testLocalPDFModelInputUsesPreparedTextWithoutChangingSourceOrOnlineInput() {
    let block: [String: Any] = [
      "sourceText": "Press  now.",
      "modelText": "Press [[FVSYMA]] now.",
      "symbols": [["token": "[[FVSYMA]]", "sourceText": "🚗"]]
    ]
    XCTAssertEqual(PdfTranslatorModule.translationInput(block: block, online: false), "Press [[FVSYMA]] now.")
    XCTAssertEqual(PdfTranslatorModule.translationInput(block: block, online: true), "Press  now.",
                   "online chunking must continue to use the raw source text")
    XCTAssertEqual(block["sourceText"] as? String, "Press  now.", "model preparation must not rewrite checkpoint source text")
    XCTAssertEqual(PdfTranslatorModule.translationInput(block: ["sourceText": " legacy source "], online: false), " legacy source ",
                   "legacy checkpoints without modelText must keep their source fallback")
    XCTAssertEqual(PdfTranslatorModule.translationInput(block: ["sourceText": "source", "modelText": " \n "], online: false), "source",
                   "blank optional modelText must fall back to sourceText")
  }

  func testPDFCheckpointRestoreReusesOnlyExactSemanticUnitsAndKeepsFreshMetadata() {
    func block(id: String = "native-7", source: String = "Press  now.", model: String = "Press [[FVSYMA]] now.",
               indices: [Int] = [17, 19], token: String = "[[FVSYMA]]", symbolIndex: Int = 17,
               status: String = "translated", translated: Any = "Нажмите сейчас") -> [String: Any] {
      ["id": id, "source": "native", "sourceText": source, "modelText": model,
       "objectIndices": indices, "symbols": [["token": token, "objectIndex": symbolIndex, "sourceText": "🚗"]],
       "sourceRects": [["objectIndex": 99, "text": source, "left": 20.0, "top": 30.0, "right": 120.0, "bottom": 50.0]],
       "status": status, "translatedText": translated]
    }
    let saved = block()
    let fresh = block(status: "pending", translated: NSNull())
    let restored = PdfTranslatorModule.restoringPdfCheckpointResults(fresh: [fresh], saved: [saved], online: false)
    XCTAssertEqual(restored[0]["status"] as? String, "translated")
    XCTAssertEqual(restored[0]["translatedText"] as? String, "Нажмите сейчас")
    XCTAssertEqual((restored[0]["sourceRects"] as? [[String: Any]])?.first?["objectIndex"] as? Int, 99,
                   "only durable translation results are copied; freshly extracted source geometry stays authoritative")

    let changedUnits: [[String: Any]] = [
      block(source: "Press the control now.", status: "pending", translated: NSNull()),
      block(model: "Press [[FVSYMA]] the control now.", status: "pending", translated: NSNull()),
      block(indices: [19, 17], status: "pending", translated: NSNull()),
      block(token: "[[FVSYMB]]", status: "pending", translated: NSNull()),
      block(symbolIndex: 18, status: "pending", translated: NSNull()),
      block(id: "different-id", status: "pending", translated: NSNull())
    ]
    for changed in changedUnits {
      let value = PdfTranslatorModule.restoringPdfCheckpointResults(fresh: [changed], saved: [saved], online: false)[0]
      XCTAssertEqual(value["status"] as? String, "pending", "changed source identity must be retranslated")
      XCTAssertTrue(value["translatedText"] is NSNull)
    }

    let partial = block(id: "partial", status: "partial", translated: "Нажмите").merging(["error": "Review this block."]) { _, new in new }
    let failed = block(id: "failed", status: "failed", translated: NSNull()).merging(["error": "Saved provider failure."]) { _, new in new }
    let partialFresh = block(id: "partial", status: "pending", translated: NSNull())
    let failedFresh = block(id: "failed", status: "pending", translated: NSNull())
    let statuses = PdfTranslatorModule.restoringPdfCheckpointResults(fresh: [partialFresh, failedFresh], saved: [partial, failed], online: false)
    XCTAssertEqual(statuses[0]["status"] as? String, "partial")
    XCTAssertEqual(statuses[0]["translatedText"] as? String, "Нажмите")
    XCTAssertEqual(statuses[0]["error"] as? String, "Review this block.")
    XCTAssertEqual(statuses[1]["status"] as? String, "failed")
    XCTAssertEqual(statuses[1]["error"] as? String, "Saved provider failure.")
    let unfinished = block(id: "unfinished", status: "running", translated: NSNull())
    let unfinishedFresh = block(id: "unfinished", status: "pending", translated: NSNull())
    XCTAssertEqual(PdfTranslatorModule.restoringPdfCheckpointResults(fresh: [unfinishedFresh], saved: [unfinished], online: false)[0]["status"] as? String,
                   "pending", "an interrupted in-flight result is not a durable translation")

    var onlineSaved = saved
    onlineSaved["modelText"] = "Old local-only prepared text"
    var onlineFresh = fresh
    onlineFresh["modelText"] = "New local-only prepared text"
    XCTAssertEqual(PdfTranslatorModule.restoringPdfCheckpointResults(fresh: [onlineFresh], saved: [onlineSaved], online: true)[0]["status"] as? String,
                   "translated", "Online checkpoints compare the effective raw source input, not local-only modelText")
    XCTAssertFalse(PdfTranslatorModule.pdfCheckpointMatches(saved.merging(["id": "other-id"]) { _, new in new },
                                                             fresh: fresh, online: false),
                   "a checkpoint with a different persisted ID cannot be reused")
    XCTAssertFalse(PdfTranslatorModule.pdfCheckpointMatches(saved.merging(["sourceText": " "]) { _, new in new },
                                                             fresh: fresh, online: false))
    var legacy = ["id": "legacy", "sourceText": "Old block", "objectIndex": 4] as [String: Any]
    var legacyFresh = legacy
    legacy["status"] = "translated"
    legacy["translatedText"] = "Старый блок"
    legacyFresh["status"] = "pending"
    XCTAssertTrue(PdfTranslatorModule.pdfCheckpointMatches(legacy, fresh: legacyFresh, online: false),
                  "legacy single-object checkpoints use the effective source fallback")
    XCTAssertEqual(PdfTranslatorModule.restoringPdfCheckpointResults(fresh: [legacyFresh], saved: [legacy], online: false)[0]["translatedText"] as? String,
                   "Старый блок")
    XCTAssertFalse(PdfTranslatorModule.pdfCheckpointMatches(saved.merging(["symbols": ["not-an-object"]]) { _, new in new },
                                                             fresh: fresh, online: false),
                   "malformed symbol inventories invalidate a checkpoint instead of being silently ignored")
    let emptyResult = block(id: "empty", status: "translated", translated: " ")
    let emptyFresh = block(id: "empty", status: "pending", translated: NSNull())
    XCTAssertEqual(PdfTranslatorModule.restoringPdfCheckpointResults(fresh: [emptyFresh], saved: [emptyResult], online: false)[0]["status"] as? String,
                   "pending", "an empty translated result is not a durable checkpoint")
    let missingID = ["sourceText": "no identity", "status": "translated", "translatedText": "не использовать"] as [String: Any]
    let freshMissingID = ["sourceText": "no identity", "status": "pending"] as [String: Any]
    XCTAssertEqual(PdfTranslatorModule.restoringPdfCheckpointResults(fresh: [freshMissingID], saved: [missingID], online: false)[0]["status"] as? String,
                   "pending", "fresh metadata is left untouched when a legacy block has no ID")
  }

  func testOCRMergePreservesNativeInlineSymbolModelTextAndMetadata() {
    let symbol: [String: Any] = ["token": "[[FVSYMA]]", "objectIndex": 17, "sourceText": "🚗", "fontName": "IconFont"]
    let native: [[String: Any]] = [[
      "source": "native", "sourceText": "Press  now.", "modelText": "Press [[FVSYMA]] now.", "symbols": [symbol],
      "left": 10.0, "top": 10.0, "right": 120.0, "bottom": 30.0,
      "sourceRects": [["objectIndex": 17, "text": "Press  now.", "left": 10.0, "top": 10.0, "right": 120.0, "bottom": 30.0]]
    ]]
    let merged = PdfTranslatorModule.mergeOCRBlocks(existing: native, recognized: [[
      "source": "ocr", "sourceText": "Press  now.", "text": "Press  now.",
      "left": 10.0, "top": 10.0, "right": 120.0, "bottom": 30.0
    ], [
      "source": "ocr", "sourceText": "FILTER A3", "text": "FILTER A3",
      "left": 130.0, "top": 10.0, "right": 160.0, "bottom": 25.0
    ]])
    XCTAssertEqual(merged.count, 2, "matching OCR should deduplicate while unrelated diagram OCR stays available")
    XCTAssertEqual(merged[0]["sourceText"] as? String, "Press  now.")
    XCTAssertEqual(merged[0]["modelText"] as? String, "Press [[FVSYMA]] now.")
    let savedSymbols = merged[0]["symbols"] as? [[String: Any]]
    XCTAssertEqual(savedSymbols?.first?["token"] as? String, "[[FVSYMA]]")
    XCTAssertEqual(savedSymbols?.first?["sourceText"] as? String, "🚗")
    XCTAssertEqual(merged[1]["source"] as? String, "ocr")
    XCTAssertEqual(merged[1]["sourceText"] as? String, "FILTER A3")
  }

  func testOCRLineAcrossInlinePictogramUsesItsOwnedRectWithoutComparingSurrogateText() {
    let native:[[String:Any]]=[[
      "source":"native","sourceText":"Press  now.","modelText":"Press [[FVSYMA]] now.",
      "sourceRects":[
        ["objectIndex":40,"text":"Press ","left":10.0,"top":20.0,"right":48.0,"bottom":34.0,"baseline":33.0],
        ["objectIndex":41,"text":"SUV","pictogram":true,"left":49.0,"top":18.0,"right":64.0,"bottom":35.0,"baseline":33.0],
        ["objectIndex":42,"text":" now.","left":65.0,"top":20.0,"right":101.0,"bottom":34.0,"baseline":33.0]
      ]
    ]]
    let recognized:[[String:Any]]=[[
      "source":"ocr","sourceText":"Press SUV now.","text":"Press SUV now.",
      "left":10.0,"top":18.0,"right":101.0,"bottom":35.0
    ]]
    let sourceLines=PdfTranslatorModule.sourceLinesForOCR(native[0])
    XCTAssertEqual(sourceLines.count,1,"adjacent text fragments must be grouped across the pictogram rectangle")
    XCTAssertEqual(sourceLines[0].textRuns,["Press "," now."],"pictogram surrogate Unicode is excluded from linguistic runs")
    XCTAssertTrue(PdfTranslatorModule.orderedOCRRunsMatch(["Press "],"presssuvnow"),"first linguistic run should match")
    XCTAssertTrue(PdfTranslatorModule.orderedOCRRunsMatch([" now."],"presssuvnow"),"last linguistic run should match")
    XCTAssertTrue(PdfTranslatorModule.orderedOCRRunsMatch(sourceLines[0].textRuns,"presssuvnow"),"linguistic fragments must remain ordered while ignoring text over the icon")
    let match=PdfTranslatorModule.matchOCRBlock(existing:native,text:"Press SUV now.",left:10,top:18,right:101,bottom:35)
    XCTAssertEqual(match?.index,0)
    XCTAssertEqual(match?.sourceDisagreement,false,"surrogate pictogram text must not create a source disagreement")
    let merged=PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:recognized)
    XCTAssertEqual(merged.count,1,"the pictogram rectangle must contribute to coverage of this single Vision line")
    XCTAssertEqual(merged[0]["modelText"] as? String,"Press [[FVSYMA]] now.")
  }

  func testPurePictogramOCRIsSuppressedButIndependentDiagramLabelRemains() {
    let native:[[String:Any]]=[[
      "source":"native","sourceText":"[[FVSYMA]]",
      "sourceRects":[["objectIndex":50,"text":"arrow-surrogate","pictogram":true,
        "left":20.0,"top":20.0,"right":60.0,"bottom":38.0,"baseline":37.0]]
    ]]
    let pureIcon=PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:[[
      "source":"ocr","sourceText":"UP","text":"UP","left":20.0,"top":20.0,"right":60.0,"bottom":38.0
    ]])
    XCTAssertEqual(pureIcon.count,1,"OCR guesses wholly inside a native pictogram must not become body translations")
    XCTAssertNil(pureIcon[0]["sourceDisagreement"])

    let withDiagramLabel=PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:[[
      "source":"ocr","sourceText":"FILTER A3","text":"FILTER A3","left":80.0,"top":20.0,"right":130.0,"bottom":38.0
    ]])
    XCTAssertEqual(withDiagramLabel.count,2,"a separate diagram label outside the icon's owned rect must remain available")
    XCTAssertEqual(withDiagramLabel.last?["sourceText"] as? String,"FILTER A3")
  }

  func testOCRMatchKeepsSmallUnknownDiagramLabelNestedInParagraph() {
    let native: [[String: Any]] = [[
      "source": "native", "sourceText": "This paragraph explains the assembly and maintenance procedure.",
      "left": 0.0, "top": 0.0, "right": 200.0, "bottom": 60.0,
      "sourceRects": [["objectIndex": 8, "text": "This paragraph explains the assembly and maintenance procedure.",
        "left": 0.0, "top": 0.0, "right": 200.0, "bottom": 12.0]]
    ]]
    let match = PdfTranslatorModule.matchOCRBlock(existing: native, text: "FILTER A3",
                                                   left: 60, top: 20, right: 110, bottom: 28)
    XCTAssertNil(match, "a small diagram label inside a paragraph is not a duplicate unless its text occurs in the paragraph")
    let merged = PdfTranslatorModule.mergeOCRBlocks(existing: native, recognized: [[
      "source": "ocr", "sourceText": "FILTER A3", "text": "FILTER A3",
      "left": 60.0, "top": 20.0, "right": 110.0, "bottom": 28.0
    ]])
    XCTAssertEqual(merged.count, 2)
    XCTAssertEqual(merged.last?["source"] as? String, "ocr")
  }

  func testOCRMatchRequiresActualOwnedTextFragmentsAndUnionsAdjacentRuns() {
    let native:[[String:Any]]=[[
      "source":"native","sourceText":"Pressure to 20 kPa\nFILTER A3",
      "left":0.0,"top":0.0,"right":200.0,"bottom":80.0,
      "sourceRects":[
        ["objectIndex":20,"text":"Pressure","left":10.0,"top":10.0,"right":48.0,"bottom":22.0],
        ["objectIndex":21,"text":"to 20 kPa","left":49.0,"top":10.0,"right":92.0,"bottom":22.0]
      ]
    ]]
    let duplicate=PdfTranslatorModule.matchOCRBlock(existing:native,text:"Pressure to 20 kPa",
      left:10,top:10,right:92,bottom:22)
    XCTAssertEqual(duplicate?.index,0,"adjacent owned run rectangles jointly cover the OCR line")
    XCTAssertEqual(duplicate?.sourceDisagreement,false)

    // The aggregate paragraph includes this label text and bounding box, but
    // no owned text fragment exists at the label's actual drawing position.
    let label=PdfTranslatorModule.matchOCRBlock(existing:native,text:"FILTER A3",
      left:120,top:45,right:170,bottom:58)
    XCTAssertNil(label,"aggregate paragraph bounds/text cannot absorb a separate diagram label")
    let merged=PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:[[
      "source":"ocr","sourceText":"FILTER A3","text":"FILTER A3",
      "left":120.0,"top":45.0,"right":170.0,"bottom":58.0
    ]])
    XCTAssertEqual(merged.count,2)
    XCTAssertEqual(merged.last?["source"] as? String,"ocr")
  }

  func testOCRMatchingWithoutOwnedRectsOrWithPictogramDoesNotSuppressOCR() {
    let legacy:[[String:Any]]=[[
      "source":"native","sourceText":"A source paragraph containing the diagram label FILTER A3",
      "left":0.0,"top":0.0,"right":200.0,"bottom":80.0
    ]]
    XCTAssertNil(PdfTranslatorModule.matchOCRBlock(existing:legacy,text:"FILTER A3",left:100,top:40,right:150,bottom:52),
      "legacy aggregate geometry is not enough to prove duplicate native text")

    let pictogram:[[String:Any]]=[[
      "source":"native","sourceText":"[[FVSYMA]]",
      "sourceRects":[["objectIndex":31,"text":"SUV","pictogram":true,
        "left":20.0,"top":20.0,"right":60.0,"bottom":32.0]],
      "left":20.0,"top":20.0,"right":60.0,"bottom":32.0
    ]]
    let iconMatch=PdfTranslatorModule.matchOCRBlock(existing:pictogram,text:"SUV",left:20,top:20,right:60,bottom:32)
    XCTAssertEqual(iconMatch?.index,0,"pictogram geometry owns its OCR box even though its surrogate text is ignored")
    XCTAssertEqual(iconMatch?.sourceDisagreement,false)

    let incompleteGeometry:[[String:Any]]=[[
      "source":"native","sourceText":"Pressure symbol release",
      "sourceRects":[
        ["objectIndex":32,"text":"Pressure ","left":10.0,"top":20.0,"right":50.0,"bottom":32.0,"baseline":31.0],
        ["objectIndex":33,"text":"arrow","pictogram":true,"left":50.0,"top":20.0,"bottom":32.0,"baseline":31.0],
        ["objectIndex":34,"text":" release","left":50.0,"top":20.0,"right":90.0,"bottom":32.0,"baseline":31.0]
      ]
    ]]
    XCTAssertNil(PdfTranslatorModule.matchOCRBlock(existing:incompleteGeometry,text:"Pressure arrow release",left:10,top:20,right:90,bottom:32),
      "incomplete legacy sourceRects cannot prove coverage or create a disagreement")
  }

  func testOCRMatchMarksSameRegionTextDisagreementForReview() {
    let native: [[String: Any]] = [[
      "source": "native", "sourceText": "Model 4830 pressure release",
      "left": 20.0, "top": 15.0, "right": 120.0, "bottom": 35.0,
      "sourceRects": [["objectIndex": 11, "text": "Model 4830 pressure release",
        "left": 20.0, "top": 15.0, "right": 120.0, "bottom": 35.0]]
    ]]
    let match = PdfTranslatorModule.matchOCRBlock(existing: native, text: "Model 4380 pressure relief",
                                                   left: 20, top: 15, right: 120, bottom: 35)
    XCTAssertEqual(match?.index, 0)
    XCTAssertEqual(match?.sourceDisagreement, true)
    let merged = PdfTranslatorModule.mergeOCRBlocks(existing: native, recognized: [[
      "source": "ocr", "sourceText": "Model 4380 pressure relief", "text": "Model 4380 pressure relief",
      "left": 20.0, "top": 15.0, "right": 120.0, "bottom": 35.0
    ]])
    XCTAssertEqual(merged.count, 1)
    XCTAssertEqual(merged[0]["sourceDisagreement"] as? Bool, true)
    XCTAssertEqual(merged[0]["error"] as? String, "Embedded PDF text differs from local OCR; verify this source region.")

    let substringMatch = PdfTranslatorModule.matchOCRBlock(existing: native, text: "4830 pressure",
                                                            left: 20, top: 15, right: 120, bottom: 35)
    XCTAssertEqual(substringMatch?.sourceDisagreement, false, "recognized text already present in a matched region is not a source disagreement")
  }

  func testVerifiedTocLeaderOcrNoiseRequiresSingleOwnedRectExactLabelAndReference() {
    let source="Storage . . . . . . . . . . 88"
    let rect:[[String:Any]]=[["objectIndex":100,"text":source,"left":20.0,"top":20.0,
      "right":300.0,"bottom":40.0,"baseline":38.0,"pictogram":false]]
    let native:[[String:Any]]=[["id":"toc-storage","source":"native","sourceText":source,
      "modelText":"Storage","sourceRects":rect,"objectIndex":100,"objectIndices":[100],
      "status":"pending","translatedText":NSNull()]]
    let recognized=[["id":"ocr-toc","source":"ocr","sourceText":"Storage ............ eee 88",
      "text":"Storage ............ eee 88","left":20.0,"top":20.0,"right":300.0,"bottom":40.0]] as [[String:Any]]

    let match=PdfTranslatorModule.matchOCRBlock(existing:native,text:"Storage ............ eee 88",
      left:20,top:20,right:300,bottom:40)
    XCTAssertEqual(match?.index,0)
    XCTAssertEqual(match?.sourceDisagreement,false)
    XCTAssertEqual(match?.verifiedTocLeaderNoise,true)
    let merged=PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:recognized)
    XCTAssertEqual(merged.count,1)
    XCTAssertEqual(merged[0]["verifiedTocLeaderOcrNoise"] as? Bool,true)
    XCTAssertNil(merged[0]["sourceDisagreement"])

    let invalidCases:[(String,String,[[String:Any]],String,Double,Double)] = [
      (source,"Other label",rect,"Storage ............ eee 88",20,300),
      (source,"Storage",rect,"Storage ............ eee 89",20,300),
      (source,"Storage",rect,"Storage ............ eee 88 trailing",20,300),
      (source+"\nLocation","Storage",rect,"Storage ............ eee 88",20,300),
      (source,"Storage",[
        ["objectIndex":100,"text":"Storage . . . . . . . . . .","left":20.0,"top":20.0,"right":190.0,"bottom":40.0,"baseline":38.0],
        ["objectIndex":101,"text":"88","left":191.0,"top":20.0,"right":300.0,"bottom":40.0,"baseline":38.0]
      ],"Storage ............ eee 88",20,300),
      (source,"Storage",[["text":source,"left":20.0,"top":20.0,"right":300.0,"bottom":40.0]],"Storage ............ eee 88",20,300),
      (source,"Storage",rect,"Storage ............ eee 88",20,600)
    ]
    for (index,testCase) in invalidCases.enumerated() {
      let (row,modelText,sourceRects,ocrText,ocrLeft,ocrRight)=testCase
      var block=native[0]
      block["sourceText"]=row;block["modelText"]=modelText;block["sourceRects"]=sourceRects
      let line=[["id":"ocr-negative-\(index)","source":"ocr","sourceText":ocrText,"text":ocrText,
        "left":ocrLeft,"top":20.0,"right":ocrRight,"bottom":40.0]] as [[String:Any]]
      let result=PdfTranslatorModule.mergeOCRBlocks(existing:[block],recognized:line)
      XCTAssertEqual(result.count,1,"same-region row \(index) remains attached to its source block")
      XCTAssertNil(result[0]["verifiedTocLeaderOcrNoise"],"unproven candidate \(index) gets no positive marker")
      XCTAssertEqual(result[0]["sourceDisagreement"] as? Bool,true,"unverified OCR row \(index) remains reviewable")
    }
    var unownedRectBlock=native[0]
    unownedRectBlock["sourceRects"]=[[
      "objectIndex":999,"text":source,"left":20.0,"top":20.0,"right":300.0,"bottom":40.0,
      "baseline":38.0,"pictogram":false
    ]]
    let unowned=PdfTranslatorModule.mergeOCRBlocks(existing:[unownedRectBlock],recognized:recognized)
    XCTAssertNil(unowned[0]["verifiedTocLeaderOcrNoise"],"a stale sourceRect index must not prove native ownership")
    XCTAssertEqual(unowned[0]["sourceDisagreement"] as? Bool,true,"unowned rect remains reviewable")

    var tallerOCR=recognized[0]
    tallerOCR["top"]=19.2;tallerOCR["bottom"]=40.8
    let lowOCRCoverage=PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:[tallerOCR])
    XCTAssertNil(lowOCRCoverage[0]["verifiedTocLeaderOcrNoise"],
      "expanded 1.5-point matching tolerance cannot replace raw OCR-box coverage")

    var pictogramRectBlock=native[0]
    pictogramRectBlock["sourceRects"]=[[
      "objectIndex":100,"text":source,"left":20.0,"top":20.0,"right":300.0,"bottom":40.0,
      "baseline":38.0,"pictogram":true
    ]]
    let pictogram=PdfTranslatorModule.mergeOCRBlocks(existing:[pictogramRectBlock],recognized:recognized)
    XCTAssertNil(pictogram[0]["verifiedTocLeaderOcrNoise"],"a pictogram surrogate cannot establish TOC text ownership")

    var ambiguousBlock=native[0]
    ambiguousBlock["id"]="toc-storage-duplicate"
    let ambiguous=PdfTranslatorModule.mergeOCRBlocks(existing:[native[0],ambiguousBlock],recognized:recognized)
    XCTAssertTrue(ambiguous.allSatisfy{$0["verifiedTocLeaderOcrNoise"] as? Bool != true},
      "the OCR row cannot prove which of two matching native blocks owns it")
    XCTAssertTrue(ambiguous.allSatisfy{$0["sourceDisagreement"] as? Bool == true},
      "all ambiguous native candidates remain reviewable")
  }

  func testVerifiedFreshTocNoiseClearsOnlyExactSavedOcrWarningAndKeepsResolutionHistory() {
    let source="Storage . . . . . . . . . . 88"
    let native:[[String:Any]]=[["id":"toc-resume","source":"native","sourceText":source,"modelText":"Storage",
      "objectIndex":12,"objectIndices":[12],"sourceRects":[["objectIndex":12,"text":source,
        "left":20.0,"top":20.0,"right":300.0,"bottom":40.0,"baseline":38.0,"pictogram":false]]]]
    let recognized=[["id":"ocr-toc","source":"ocr","sourceText":"Storage ............ eee 88","text":"Storage ............ eee 88",
      "left":20.0,"top":20.0,"right":300.0,"bottom":40.0]] as [[String:Any]]
    let fresh=PdfTranslatorModule.mergeOCRBlocks(existing:native,recognized:recognized)[0]
    let oldResolution:[String:Any]=["reason":"earlier_verified_issue","warning":"older warning"]
    let saved:[String:Any]=["id":"toc-resume","sourceText":source,"modelText":"Storage","objectIndex":12,
      "objectIndices":[12],"sourceRects":native[0]["sourceRects"]!,"status":"partial","translatedText":"Хранение",
      "sourceDisagreement":true,"error":PdfTranslatorModule.sourceDisagreementBlockWarning,
      "layoutRetryCount":2,"layoutRetryHistory":[["attempt":1],["attempt":2]],
      "sourceDisagreementResolution":oldResolution]
    let restored=PdfTranslatorModule.restoringPdfCheckpointResults(fresh:[fresh],saved:[saved],online:false)[0]
    XCTAssertEqual(restored["status"] as? String,"translated")
    XCTAssertNil(restored["sourceDisagreement"])
    XCTAssertNil(restored["error"])
    let resolution=try! XCTUnwrap(restored["sourceDisagreementResolution"] as? [String:Any])
    XCTAssertEqual(resolution["reason"] as? String,"verified_toc_dotted_leader_ocr_noise")
    XCTAssertEqual(resolution["warning"] as? String,PdfTranslatorModule.sourceDisagreementBlockWarning)
    XCTAssertEqual((resolution["previous"] as? [String:Any])?["reason"] as? String,"earlier_verified_issue")
    XCTAssertEqual(restored["layoutRetryCount"] as? Int,2)
    XCTAssertEqual((restored["layoutRetryHistory"] as? [[String:Any]])?.count,2)

    let protectedMutations:[([String:Any],[String:Any]) -> ([String:Any],[String:Any])] = [
      { saved,fresh in var s=saved;s["status"]="failed";return(s,fresh) },
      { saved,fresh in var s=saved;s["translatedText"]=" \n ";return(s,fresh) },
      { saved,fresh in var s=saved;s["error"]=PdfTranslatorModule.sourceDisagreementBlockWarning+" other";return(s,fresh) },
      { saved,fresh in var s=saved;s["translationReviewRequired"]=true;return(s,fresh) },
      { saved,fresh in var s=saved;s["exportIssues"]=[["reason":"layout_fallback"]];return(s,fresh) },
      { saved,fresh in var s=saved;s["layoutRetryError"]="fit failed";return(s,fresh) },
      { saved,fresh in var s=saved;s["gemmaPromptArtifactRepairPendingRevision"]="pending";return(s,fresh) },
      { saved,fresh in var s=saved;s["gemmaPromptArtifactRejectedText"]="raw";return(s,fresh) },
      { saved,fresh in var f=fresh;f["sourceDisagreement"]=true;return(saved,f) },
      { saved,fresh in var f=fresh;f["error"]="new disagreement";return(saved,f) },
      { saved,fresh in var f=fresh;f.removeValue(forKey:"verifiedTocLeaderOcrNoise");return(saved,f) }
    ]
    for (index,mutate) in protectedMutations.enumerated() {
      let (old,current)=mutate(saved,fresh)
      let result=PdfTranslatorModule.restoringPdfCheckpointResults(fresh:[current],saved:[old],online:false)[0]
      XCTAssertEqual(result["status"] as? String,old["status"] as? String,
        "protected checkpoint case \(index) does not receive a new status")
      XCTAssertEqual(result["sourceDisagreement"] as? Bool,true,"protected case \(index) keeps review marker")
      let retainedResolution=result["sourceDisagreementResolution"] as? [String:Any]
      XCTAssertEqual(retainedResolution?["reason"] as? String,oldResolution["reason"] as? String,
        "previous resolution history survives without a valid fresh clearing proof")
      XCTAssertEqual(retainedResolution?["warning"] as? String,oldResolution["warning"] as? String,
        "the prior warning provenance remains intact without a valid fresh clearing proof")
    }
  }

  func testOCRMatchDoesNotTreatLegacyOCRAsNativeEvidence() {
    let legacyOCR: [[String: Any]] = [[
      "source": "ocr", "sourceText": "Old OCR label",
      "left": 20.0, "top": 15.0, "right": 120.0, "bottom": 35.0
    ]]
    XCTAssertNil(PdfTranslatorModule.matchOCRBlock(existing: legacyOCR, text: "New OCR label",
                                                     left: 20, top: 15, right: 120, bottom: 35))
    let merged = PdfTranslatorModule.mergeOCRBlocks(existing: legacyOCR, recognized: [[
      "source": "ocr", "sourceText": "New OCR label", "text": "New OCR label",
      "left": 20.0, "top": 15.0, "right": 120.0, "bottom": 35.0
    ]])
    XCTAssertEqual(merged.count, 2, "legacy OCR must not be treated as embedded PDF evidence")
    XCTAssertNil(merged[0]["sourceDisagreement"])
    let invalidNative: [[String: Any]] = [["source": "native", "sourceText": "Unusable geometry", "left": 20.0, "top": 15.0, "right": 10.0, "bottom": 14.0]]
    XCTAssertNil(PdfTranslatorModule.matchOCRBlock(existing: invalidNative, text: "New OCR label",
                                                     left: 20, top: 15, right: 120, bottom: 35))
    XCTAssertNil(PdfTranslatorModule.matchOCRBlock(existing: legacyOCR, text: " ",
                                                     left: 20, top: 15, right: 120, bottom: 35))
    XCTAssertNil(PdfTranslatorModule.matchOCRBlock(existing: legacyOCR, text: "New OCR label",
                                                     left: 20, top: 15, right: 20, bottom: 35))
  }

  func testOCRShortDiagramLabelsStillRequireReviewWhenGlyphsDiffer() {
    for (source, recognized) in [("A1", "A2"), ("100", "10"), ("A1", "A10 label")] {
      let native: [[String: Any]] = [["source": "native", "sourceText": source,
        "left": 20.0, "top": 15.0, "right": 120.0, "bottom": 35.0,
        "sourceRects": [["objectIndex": 12, "text": source,
          "left": 20.0, "top": 15.0, "right": 120.0, "bottom": 35.0]]]]
      let match = PdfTranslatorModule.matchOCRBlock(existing: native, text: recognized,
        left: 20, top: 15, right: 120, bottom: 35)
      XCTAssertEqual(match?.sourceDisagreement, true, "same-region \(source) / \(recognized)")
    }
    let equivalent:[[String:Any]]=[["source":"native","sourceText":"A1",
      "sourceRects":[["objectIndex":13,"text":"A1","left":20.0,"top":15.0,"right":120.0,"bottom":35.0]]]]
    XCTAssertEqual(PdfTranslatorModule.matchOCRBlock(existing:equivalent,text:"a-1",left:20,top:15,right:120,bottom:35)?.sourceDisagreement,false,
      "punctuation and case normalization still deduplicate exact short codes")
    let punctuationOnly:[[String:Any]]=[["source":"native","sourceText":"!!!",
      "sourceRects":[["objectIndex":14,"text":"!!!","left":20.0,"top":15.0,"right":120.0,"bottom":35.0]]]]
    XCTAssertNil(PdfTranslatorModule.matchOCRBlock(existing:punctuationOnly,text:"A1",left:20,top:15,right:120,bottom:35),
      "punctuation-only native fragments cannot suppress an OCR label")
  }

  func testInlinePictogramDoesNotHideASameRegionCodeDisagreement() {
    let native:[[String:Any]]=[[
      "source":"native","sourceText":"A1 then end",
      "sourceRects":[
        ["objectIndex":60,"text":"A1 ","left":20.0,"top":15.0,"right":43.0,"bottom":35.0,"baseline":34.0],
        ["objectIndex":61,"text":"icon-surrogate","pictogram":true,"left":44.0,"top":14.0,"right":64.0,"bottom":36.0,"baseline":34.0],
        ["objectIndex":62,"text":" then end","left":65.0,"top":15.0,"right":120.0,"bottom":35.0,"baseline":34.0]
      ]
    ]]
    let match=PdfTranslatorModule.matchOCRBlock(existing:native,text:"A2 UP then end",left:20,top:14,right:120,bottom:36)
    XCTAssertEqual(match?.index,0)
    XCTAssertEqual(match?.sourceDisagreement,true,"the inline symbol may explain UP, but must not mask A1→A2")
  }

  func testDocxPreviewBridgeRendersSourceAndTranslatedResultAndRebuildsStaleCache() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let module = PdfTranslatorModule()
    let id = "coverage-docx-preview-\(UUID().uuidString)"
    let jobID = "\(id)-job"
    let unrelatedJobID = "\(id)-unrelated-job"
    let source = AppPaths.documents.appendingPathComponent(id + ".docx")
    let sourceCache = AppPaths.thumbnails.appendingPathComponent("docx-\(id).pdf")
    let translatedCache = AppPaths.thumbnails.appendingPathComponent("docx-\(jobID)-translated.pdf")
    func removePublishedDocx(_ exportID: String) {
      guard let record = try? store.export(exportID), let path = record["path"] as? String else { return }
      let file = URL(fileURLWithPath: path).standardizedFileURL
      let generation = file.deletingLastPathComponent()
      guard file.lastPathComponent == "translated.docx",
        generation.deletingLastPathComponent() == AppPaths.exports.standardizedFileURL else { return }
      try? FileManager.default.removeItem(at: generation)
    }
    func publishedDocxURL(_ exportID: String) throws -> URL {
      let record = try XCTUnwrap(try store.export(exportID), "verified DOCX export metadata should point to the generated artifact")
      return URL(fileURLWithPath: try XCTUnwrap(record["path"] as? String))
    }
    defer {
      removePublishedDocx("\(jobID):docx")
      removePublishedDocx("\(unrelatedJobID):docx")
      try? store.deleteExport("\(jobID):docx")
      try? store.deleteJob(jobID)
      try? store.deleteJob(unrelatedJobID)
      try? store.deleteExport("\(unrelatedJobID):docx")
      try? store.deleteDocument(id)
      try? FileManager.default.removeItem(at: source)
      try? FileManager.default.removeItem(at: sourceCache)
      try? FileManager.default.removeItem(at: translatedCache)
      try? FileManager.default.removeItem(at: AppPaths.pages.appendingPathComponent(jobID, isDirectory: true))
      try? FileManager.default.removeItem(at: AppPaths.pages.appendingPathComponent(unrelatedJobID, isDirectory: true))
    }
    let ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    let sourceXml = "<w:document xmlns:w=\"\(ns)\"><w:body><w:p><w:r><w:t>Preview source text</w:t></w:r></w:p><w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr></w:body></w:document>"
    let package = storedZip([
      ("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"),
      ("_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"),
      ("word/document.xml", sourceXml)
    ])
    XCTAssertTrue(FileManager.default.createFile(atPath: source.path, contents: package))
    _ = try store.saveDocument(id: id, name: "Preview.docx", path: source.path, pages: 1, sourceSHA256: sha256(at: source), format: "docx")
    let input: [String: Any] = ["documentId": id, "modelId": ModelCatalog.recommendedId, "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false]
    _ = try store.addJob(id: jobID, input: input, pageCount: 1)
    let block = [["id": "p0", "sourceText": "Preview source text", "translatedText": "Текст предпросмотра", "status": "translated"]]
    try store.savePage(jobId: jobID, index: 0, status: "completed", blocks: try jsonString(block))
    try store.updateJob(jobID, status: "completed", stage: "complete", completed: 1)

    func render(job: String?, translated: Bool = false) throws -> [String: Any] {
      let completed = expectation(description: "DOCX preview bridge resolves")
      var value: Any?
      var rejection: String?
      module.renderDocxPreview(id, jobId: job, translated: translated, resolver: { result in
        value = result
        completed.fulfill()
      }, rejecter: { code, message, _ in
        rejection = "\(code ?? "") \(message ?? "")"
        completed.fulfill()
      })
      wait(for: [completed], timeout: 100)
      XCTAssertNil(rejection, "DOCX preview should render: \(rejection ?? "")")
      return try decoded(value)
    }

    let original = try render(job: nil)
    XCTAssertEqual(original["format"] as? String, "pdf")
    XCTAssertGreaterThan(original["pageCount"] as? Int ?? 0, 0)
    let originalURL = try XCTUnwrap(URL(string: original["uri"] as? String ?? ""))
    XCTAssertTrue(FileManager.default.fileExists(atPath: originalURL.path))
    XCTAssertEqual(try Data(contentsOf: originalURL).prefix(5), Data("%PDF-".utf8))
    XCTAssertEqual(try render(job: nil)["uri"] as? String, original["uri"] as? String, "a verified source preview should be reused from the cache")

    let translated = try render(job: jobID, translated: true)
    XCTAssertGreaterThan(translated["pageCount"] as? Int ?? 0, 0)
    let translatedExport = try publishedDocxURL("\(jobID):docx")
    XCTAssertTrue(FileManager.default.fileExists(atPath: translatedExport.path), "translated preview should be based on the reconstructed DOCX export")
    try Data("stale cache".utf8).write(to: translatedCache, options: .atomic)
    let rebuilt = try render(job: jobID, translated: true)
    XCTAssertEqual(rebuilt["uri"] as? String, translated["uri"] as? String)
    XCTAssertEqual(try Data(contentsOf: translatedCache).prefix(5), Data("%PDF-".utf8), "a stale cache must be replaced by a verified preview PDF")

    // A partial completed job remains exportable even while another job is
    // active and then paused. Force a DOCX cache miss so finalization rechecks
    // A's durable status instead of relying on the earlier cached result.
    _ = try store.addJob(id: unrelatedJobID, input: input, pageCount: 1)
    try store.updateJob(unrelatedJobID, status: "running", stage: "translating")
    try store.updateJob(jobID, status: "partial", stage: "complete", completed: 1)
    removePublishedDocx("\(jobID):docx")
    try store.deleteExport("\(jobID):docx")
    try? FileManager.default.removeItem(at: translatedCache)
    module.activeJobIDForTesting = unrelatedJobID
    let pauseOther = try decoded(resolved { module.pauseJob(unrelatedJobID, resolver: $0, rejecter: $1) })
    XCTAssertEqual(pauseOther["workerStopping"] as? Bool, true)
    try store.updateJob(unrelatedJobID, status: "paused", stage: "queued")
    module.activeJobIDForTesting = nil

    let partialTranslated = try render(job: jobID, translated: true)
    XCTAssertGreaterThan(partialTranslated["pageCount"] as? Int ?? 0, 0)
    XCTAssertEqual(try store.job(jobID)?["status"] as? String, "partial")
    let partialExport = try publishedDocxURL("\(jobID):docx")
    XCTAssertNotNil(try store.export("\(jobID):docx"), "a partial job must persist a newly reconstructed DOCX on a cache miss")
    XCTAssertTrue(FileManager.default.fileExists(atPath: partialExport.path), "an unrelated job's pause must not block completed partial output")

    let invalidJob = expectation(description: "translated preview requires a matching job")
    module.renderDocxPreview(id, jobId: "missing-preview-job", translated: true, resolver: { _ in
      XCTFail("A translated preview must not be generated for a missing job")
      invalidJob.fulfill()
    }, rejecter: { code, message, _ in
      XCTAssertEqual(code, "DOCX_PREVIEW_FAILED")
      XCTAssertFalse(message?.isEmpty ?? true)
      invalidJob.fulfill()
    })
    wait(for: [invalidJob], timeout: 15)
  }

  func testDeletedCompletedDocxJobCannotCommitFinalizedOutput() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let module = PdfTranslatorModule()
    let id = "coverage-docx-delete-before-commit-\(UUID().uuidString)"
    let jobID = "\(id)-job"
    let source = AppPaths.documents.appendingPathComponent(id + ".docx")
    let destination = AppPaths.exports.appendingPathComponent(jobID + ".docx")
    let pending = AppPaths.exports.appendingPathComponent(jobID + ".pending.docx")
    defer {
      module.beforeFinalizationCommitForTesting = nil
      try? store.deleteExport("\(jobID):docx")
      try? store.deleteJob(jobID)
      try? store.deleteDocument(id)
      try? FileManager.default.removeItem(at: source)
      try? FileManager.default.removeItem(at: destination)
      try? FileManager.default.removeItem(at: pending)
      try? FileManager.default.removeItem(at: AppPaths.pages.appendingPathComponent(jobID, isDirectory: true))
    }
    let ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    let xml = "<w:document xmlns:w=\"\(ns)\"><w:body><w:p><w:r><w:t>Delete before commit</w:t></w:r></w:p></w:body></w:document>"
    let package = storedZip([
      ("[Content_Types].xml", "<Types><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"),
      ("_rels/.rels", "<Relationships><Relationship Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"),
      ("word/document.xml", xml)
    ])
    XCTAssertTrue(FileManager.default.createFile(atPath: source.path, contents: package))
    _ = try store.saveDocument(id: id, name: "Delete.docx", path: source.path, pages: 1, sourceSHA256: sha256(at: source), format: "docx")
    let input: [String: Any] = ["documentId": id, "modelId": ModelCatalog.recommendedId, "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false]
    _ = try store.addJob(id: jobID, input: input, pageCount: 1)
    let block = [["id": "p0", "sourceText": "Delete before commit", "translatedText": "Удалить до фиксации", "status": "translated"]]
    try store.savePage(jobId: jobID, index: 0, status: "completed", blocks: try jsonString(block))
    try store.updateJob(jobID, status: "completed", stage: "complete", completed: 1)

    var deletedAtCommit = false
    module.beforeFinalizationCommitForTesting = { phase in
      guard phase == "export", !deletedAtCommit else { return }
      deletedAtCommit = true
      do {
        if let path = try store.deleteJobAndReleaseUnsharedDocument(jobID) {
          try? FileManager.default.removeItem(atPath: path)
        }
      } catch {
        XCTFail("Fixture job should be deletable immediately before export commit: \(error)")
      }
    }
    let rejected = expectation(description: "deleted DOCX job is rejected before canonical export commit")
    module.exportDocument(jobID, format: "docx", resolver: { _ in
      XCTFail("A deleted job must not commit a user-visible DOCX export")
      rejected.fulfill()
    }, rejecter: { code, message, _ in
      XCTAssertEqual(code, "EXPORT_FAILED")
      XCTAssertFalse(message?.isEmpty ?? true)
      rejected.fulfill()
    })
    wait(for: [rejected], timeout: 20)
    XCTAssertTrue(deletedAtCommit, "the deletion must occur at the finalization boundary")
    XCTAssertNil(try store.job(jobID))
    XCTAssertNil(try store.export("\(jobID):docx"), "deleted output must not acquire canonical export metadata")
    XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path), "deleted output must not leave a canonical DOCX file")
    XCTAssertFalse(FileManager.default.fileExists(atPath: pending.path), "temporary output must be cleaned after rejected finalization")
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
    let retainedExportID=id+":user-retained"
    let retainedExport = FileManager.default.temporaryDirectory.appendingPathComponent(id + "-user-retained.pdf")
    defer {
      try? store.deleteExport(retainedExportID)
      try? store.execute("DELETE FROM documents WHERE id=?", [id])
      try? FileManager.default.removeItem(at: source)
      try? FileManager.default.removeItem(at: retainedExport)
      try? FileManager.default.removeItem(at: AppPaths.exports.appendingPathComponent(id + ".pdf"))
      try? FileManager.default.removeItem(at: AppPaths.exports.appendingPathComponent(id + ".docx"))
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
    XCTAssertTrue(FileManager.default.createFile(atPath: retainedExport.path, contents: Data("user export".utf8)))
    try store.saveExport(retainedExportID, path: retainedExport.path, name: retainedExport.lastPathComponent, sha256: sha256(at: retainedExport))
    let retainedExportMetadata = try XCTUnwrap(try store.export(retainedExportID))
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
    let activity = try decoded(resolved { module.getActivitySnapshot($0, rejecter: $1) })
    XCTAssertNotNil(activity["jobs"] as? [[String: Any]])
    XCTAssertNotNil(activity["models"] as? [[String: Any]])
    XCTAssertNotNil(activity["notifications"] as? [String: Any])
    XCTAssertNotNil((activity["jobs"] as? [[String: Any]])?.first?["stageStartedAt"])
    try store.updateJob(id, status: "running")
    module.activeJobIDForTesting = id
    let stopping = try decoded(resolved { module.pauseJob(id, resolver: $0, rejecter: $1) })
    XCTAssertEqual(stopping["workerStopping"] as? Bool, true)
    XCTAssertEqual(try store.job(id)?["status"] as? String, "running", "pause acknowledgement must not precede worker cleanup and checkpoint commit")
    rejected("JOB_RESUME_FAILED") { module.resumeJob(id, resolver: $0, rejecter: $1) }
    module.activeJobIDForTesting = nil
    try store.updateJob(id, status: "paused")
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
    let privatePDF=AppPaths.exports.appendingPathComponent(id+".pdf")
    let privateDOCX=AppPaths.exports.appendingPathComponent(id+".docx")
    try Data("private PDF result".utf8).write(to:privatePDF)
    try Data("private Word result".utf8).write(to:privateDOCX)
    try store.saveExport("\(id):docx",path:privateDOCX.path,name:privateDOCX.lastPathComponent,sha256:sha256(at:privateDOCX))
    _ = try resolved { module.deleteJob(id, resolver: $0, rejecter: $1) }
    XCTAssertFalse(FileManager.default.fileExists(atPath:privatePDF.path))
    XCTAssertFalse(FileManager.default.fileExists(atPath:privateDOCX.path))
    XCTAssertNil(try store.export("\(id):docx"))
    XCTAssertNil(try store.job(id))
    XCTAssertTrue(FileManager.default.fileExists(atPath: retainedExport.path), "deleting a translation must preserve its already-created user export")
    let preservedExportMetadata = try XCTUnwrap(try store.export(retainedExportID), "an independently keyed user export must retain share metadata after deleting the translation")
    XCTAssertEqual(preservedExportMetadata["path"] as? String, retainedExportMetadata["path"] as? String)
    XCTAssertEqual(preservedExportMetadata["name"] as? String, retainedExportMetadata["name"] as? String)
    XCTAssertEqual(preservedExportMetadata["sha256"] as? String, retainedExportMetadata["sha256"] as? String)
    XCTAssertFalse(FileManager.default.fileExists(atPath: source.path), "removing the final translation should release its app-owned source")
    XCTAssertNil(try store.documentPath(id), "the final app-owned source row should be removed with the job")

    let rawID = "coverage-raw-import-\(UUID().uuidString)"
    let rawSource = AppPaths.documents.appendingPathComponent(rawID + ".pdf")
    defer { try? FileManager.default.removeItem(at: rawSource) }
    XCTAssertTrue(FileManager.default.createFile(atPath: rawSource.path, contents: Data("raw import".utf8)))
    _ = try store.saveDocument(id: rawID, name: "Raw import.pdf", path: rawSource.path, pages: 1, sourceSHA256: sha256(at: rawSource))
    let rawDeleteResult = try resolved { module.deleteDocument(rawID, resolver: $0, rejecter: $1) }
    XCTAssertTrue(rawDeleteResult is NSNull)
    XCTAssertFalse(FileManager.default.fileExists(atPath: rawSource.path), "the native delete action should remove an untranslated app-owned import")
    XCTAssertNil(try store.documentPath(rawID))
  }

  func testNativeSourcePreservingPdfV3EditsFullTwoPageDocumentWithoutMovingArtwork() throws {
    let directory=FileManager.default.temporaryDirectory.appendingPathComponent("source-preserving-v5-\(UUID().uuidString)",isDirectory:true)
    try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
    defer{try? FileManager.default.removeItem(at:directory)}
    let source=directory.appendingPathComponent("original.pdf")
    let final=directory.appendingPathComponent("translated.pdf")
    let translatedLabels=["Переведено: клапан 42","Переведено: тормоз 7"]
    func stream(_ content:String)->String{"<< /Length \(content.utf8.count) >>\nstream\n\(content)\nendstream"}
    let pageContent=[
      "BT /F1 16 Tf 24 260 Td (Original valve warning 42) Tj ET 0.86 0.08 0.12 rg 300 68 56 42 re f 0 0 0 RG 3 w 286 56 84 66 re S",
      "BT /F1 16 Tf 24 260 Td (Original brake warning 7) Tj ET 0.86 0.08 0.12 rg 300 68 56 42 re f 0 0 0 RG 3 w 286 56 84 66 re S"
    ]
    let objects=[
      "<< /Type /Catalog /Pages 2 0 R >>",
      "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>",
      "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 300] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
      "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
      stream(pageContent[0]),
      "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 300] /Resources << /Font << /F1 4 0 R >> >> /Contents 7 0 R >>",
      stream(pageContent[1])
    ]
    var pdf=Data("%PDF-1.4\n".utf8),offsets=[0]
    for (index,object) in objects.enumerated(){
      offsets.append(pdf.count);pdf.append(Data("\(index+1) 0 obj\n\(object)\nendobj\n".utf8))
    }
    let xref=pdf.count
    pdf.append(Data("xref\n0 \(objects.count+1)\n0000000000 65535 f \n".utf8))
    for offset in offsets.dropFirst(){pdf.append(Data(String(format:"%010d 00000 n \n",offset).utf8))}
    pdf.append(Data("trailer << /Size \(objects.count+1) /Root 1 0 R >>\nstartxref\n\(xref)\n%%EOF\n".utf8))
    try pdf.write(to:source)
    let fontBundle=try XCTUnwrap(Bundle.main.url(forResource:"TranslatePDFFont",withExtension:"bundle"),"the app's pinned PDF font bundle must be available to the hosted test")
    let fonts=try XCTUnwrap(Bundle(url:fontBundle))
    let fontPath=try XCTUnwrap(fonts.path(forResource:"NotoSans-Regular",ofType:"ttf"))
    let cjkFontPath=try XCTUnwrap(fonts.path(forResource:"NotoSansSC-Regular",ofType:"ttf"))
    let sourceSHA=try sha256(at:source)
    let originalInspection=try decoded(TranslateCoreBridge.inspect(source.path))
    XCTAssertEqual((originalInspection["pageCount"] as? NSNumber)?.intValue,2)

    func geometry(_ path:String,_ index:Int)throws->[String:Double]{
      let page=try self.decoded(TranslateCoreBridge.readPage(path,index:index))
      return ["width":try XCTUnwrap((page["width"] as? NSNumber)?.doubleValue),
        "height":try XCTUnwrap((page["height"] as? NSNumber)?.doubleValue)]
    }
    func renderedCrop(_ path:String,_ index:Int)throws->Data{
      let png=try XCTUnwrap(TranslateCoreBridge.renderPNG(path,index:index,width:400))
      let image=try XCTUnwrap(UIImage(data:png)?.cgImage)
      let rect=CGRect(x:280,y:170,width:100,height:85)
      let crop=try XCTUnwrap(image.cropping(to:rect))
      return try XCTUnwrap(UIImage(cgImage:crop).pngData())
    }

    var descriptors=[[String:Any]]()
    var snapshots=[URL]()
    var snapshotBytesByPage=[Data]()
    var previews=[URL]()
    for index in 0..<2{
      let read=try decoded(TranslateCoreBridge.readPage(source.path,index:index))
      var blocks=try XCTUnwrap(read["blocks"] as? [[String:Any]])
      XCTAssertFalse(blocks.isEmpty,"page \(index) needs real native text to exercise the PDFium bridge")
      for blockIndex in blocks.indices{
        blocks[blockIndex]["translatedText"]=translatedLabels[index]
        blocks[blockIndex]["status"]="translated"
      }
      let preview=directory.appendingPathComponent("preview-\(index).pdf")
      let request:[String:Any]=["layoutVersion":3,"mode":"preserve-source","sourcePageIndex":index,"blocks":blocks]
      let exported=try decoded(TranslateCoreBridge.exportPage(source.path,index:index,blocksJSON:try jsonString(request),
        destination:preview.path,fontPath:fontPath,cjkFontPath:cjkFontPath))
      XCTAssertNil(exported["error"] as? String,"page export failed: \(exported)")
      XCTAssertEqual((exported["layoutVersion"] as? NSNumber)?.intValue,3)
      XCTAssertEqual(exported["mode"] as? String,"preserve-source")
      XCTAssertEqual((exported["sourcePageIndex"] as? NSNumber)?.intValue,index)
      XCTAssertEqual((exported["pageCount"] as? NSNumber)?.intValue,1)
      let previewRead=try decoded(TranslateCoreBridge.readPage(preview.path,index:0))
      XCTAssertTrue((previewRead["blocks"] as? [[String:Any]] ?? []).contains{
        (($0["sourceText"] as? String) ?? "").contains(translatedLabels[index])
      },"page preview must contain the translated Russian text")
      let originalGeometry=try geometry(source.path,index),previewGeometry=try geometry(preview.path,0)
      XCTAssertEqual(previewGeometry["width"]!,originalGeometry["width"]!,accuracy:0.01)
      XCTAssertEqual(previewGeometry["height"]!,originalGeometry["height"]!,accuracy:0.01)

      // A failed replacement must leave the already verified preview byte-for-byte intact.
      let priorPreview=try Data(contentsOf:preview)
      var unfit=blocks
      unfit[0]["translatedText"]=String(repeating:"непомещающийся ",count:500)
      let unfitRequest:[String:Any]=["layoutVersion":3,"mode":"preserve-source","sourcePageIndex":index,"blocks":unfit]
      let unfitResult=try decoded(TranslateCoreBridge.exportPage(source.path,index:index,blocksJSON:try jsonString(unfitRequest),
        destination:preview.path,fontPath:fontPath,cjkFontPath:cjkFontPath))
      XCTAssertNotNil(unfitResult["error"] as? String,"unfit translation must fail closed")
      XCTAssertEqual(try Data(contentsOf:preview),priorPreview)
      var missing=blocks
      missing[0]["translatedText"]=NSNull()
      let missingPath=directory.appendingPathComponent("missing-target-\(index).pdf")
      let sentinel=Data("previous verified artifact".utf8)
      try sentinel.write(to:missingPath)
      let missingRequest:[String:Any]=["layoutVersion":3,"mode":"preserve-source","sourcePageIndex":index,"blocks":missing]
      let missingResult=try decoded(TranslateCoreBridge.exportPage(source.path,index:index,blocksJSON:try jsonString(missingRequest),
        destination:missingPath.path,fontPath:fontPath,cjkFontPath:cjkFontPath))
      XCTAssertNotNil(missingResult["error"] as? String,"missing target translation must fail closed")
      XCTAssertEqual(try Data(contentsOf:missingPath),sentinel)

      let snapshot=directory.appendingPathComponent("blocks-\(index).json")
      let snapshotJSON:[String:Any]=["sourcePageIndex":index,"blocks":blocks]
      try Data(jsonString(snapshotJSON).utf8).write(to:snapshot,options:.atomic)
      let snapshotBytes=try Data(contentsOf:snapshot)
      XCTAssertEqual((try JSONSerialization.jsonObject(with:snapshotBytes) as? [String:Any])?["sourcePageIndex"] as? Int,index)
      snapshotBytesByPage.append(snapshotBytes)
      previews.append(preview);snapshots.append(snapshot)
      descriptors.append(["sourcePageIndex":index,"expectedPageCount":1,"path":preview.path,"blocksPath":snapshot.path])
    }

    let expectedSourceArtwork=try renderedCrop(source.path,0)
    let fullRequest:[String:Any]=["layoutVersion":3,"mode":"preserve-source","sourcePath":source.path,
      "fontPath":fontPath,"cjkFontPath":cjkFontPath,"shards":descriptors]
    let merged=try decoded(TranslateCoreBridge.mergePDF(try jsonString(fullRequest),destination:final.path))
    XCTAssertNil(merged["error"] as? String,"full source-PDF merge failed: \(merged)")
    XCTAssertEqual((merged["layoutVersion"] as? NSNumber)?.intValue,3)
    XCTAssertEqual(merged["mode"] as? String,"preserve-source")
    XCTAssertEqual((merged["pageCount"] as? NSNumber)?.intValue,2)
    XCTAssertEqual((try decoded(TranslateCoreBridge.inspect(final.path))["pageCount"] as? NSNumber)?.intValue,2)
    let ranges=try XCTUnwrap(merged["sourcePageRanges"] as? [[String:Any]])
    let pages=try XCTUnwrap(merged["pages"] as? [[String:Any]])
    XCTAssertEqual(ranges.count,2);XCTAssertEqual(pages.count,2)
    for index in 0..<2{
      XCTAssertEqual((ranges[index]["sourcePageIndex"] as? NSNumber)?.intValue,index)
      XCTAssertEqual((ranges[index]["firstOutputPage"] as? NSNumber)?.intValue,index)
      XCTAssertEqual((ranges[index]["outputPageCount"] as? NSNumber)?.intValue,1)
      XCTAssertEqual((pages[index]["sourcePageIndex"] as? NSNumber)?.intValue,index)
      XCTAssertEqual((pages[index]["outputPageIndex"] as? NSNumber)?.intValue,index)
      XCTAssertEqual((pages[index]["sourceOutputPageIndex"] as? NSNumber)?.intValue,0)
      let sourceGeometry=try geometry(source.path,index),outputGeometry=try geometry(final.path,index)
      XCTAssertEqual(outputGeometry["width"]!,sourceGeometry["width"]!,accuracy:0.01)
      XCTAssertEqual(outputGeometry["height"]!,sourceGeometry["height"]!,accuracy:0.01)
      let outputRead=try decoded(TranslateCoreBridge.readPage(final.path,index:index))
      XCTAssertTrue((outputRead["blocks"] as? [[String:Any]] ?? []).contains{
        (($0["sourceText"] as? String) ?? "").contains(translatedLabels[index])
      },"full document page \(index) must contain translated text")
      XCTAssertEqual(try renderedCrop(final.path,index),expectedSourceArtwork,
        "the original vector rectangle must remain pixel-identical in page \(index)")
      XCTAssertEqual(try Data(contentsOf:snapshots[index]),snapshotBytesByPage[index],
        "merge must not mutate its immutable page snapshot")
    }
    XCTAssertEqual(try sha256(at:source),sourceSHA,"the full-document merge must leave the imported source intact")
    XCTAssertTrue(previews.allSatisfy{FileManager.default.fileExists(atPath:$0.path)})
  }

  func testUIKitSourcePreservingPdfArtworkSerializationSucceedsWithoutMovingArtwork() throws {
    // Keep this UIKit-generated source shape: a minimal hand-authored PDF does
    // not exercise the Quartz stream serialization regression from the repro.
    let artifactDirectory=AppPaths.support.appendingPathComponent("u052-uikit-export-repro-\(UUID().uuidString)",isDirectory:true)
    try FileManager.default.createDirectory(at:artifactDirectory,withIntermediateDirectories:true)
    let source=artifactDirectory.appendingPathComponent("source.pdf")
    let payload=artifactDirectory.appendingPathComponent("export-payload.json")
    let candidate=artifactDirectory.appendingPathComponent("candidate.pdf")
    let renderer=UIGraphicsPDFRenderer(bounds:CGRect(x:0,y:0,width:400,height:300))
    try renderer.writePDF(to:source){context in
      for text in ["Original valve warning 42","Original brake warning 7"]{
        context.beginPage()
        (text as NSString).draw(at:CGPoint(x:24,y:24),withAttributes:[.font:UIFont.systemFont(ofSize:16)])
        context.cgContext.setFillColor(UIColor(red:0.86,green:0.08,blue:0.12,alpha:1).cgColor)
        context.cgContext.fill(CGRect(x:300,y:190,width:56,height:42))
        context.cgContext.setStrokeColor(UIColor.black.cgColor)
        context.cgContext.setLineWidth(3)
        context.cgContext.stroke(CGRect(x:286,y:178,width:84,height:66))
      }
    }
    let sourceBytes=try Data(contentsOf:source)
    let sourcePage=try decoded(TranslateCoreBridge.readPage(source.path,index:0))
    var blocks=try XCTUnwrap(sourcePage["blocks"] as? [[String:Any]])
    XCTAssertFalse(blocks.isEmpty)
    for index in blocks.indices{
      blocks[index]["translatedText"]="Переведено: клапан 42"
      blocks[index]["status"]="translated"
    }
    let request:[String:Any]=["layoutVersion":3,"mode":"preserve-source","sourcePageIndex":0,"blocks":blocks]
    let requestData=try JSONSerialization.data(withJSONObject:request,options:[.sortedKeys,.fragmentsAllowed])
    try requestData.write(to:payload,options:.atomic)

    let fontBundle=try XCTUnwrap(Bundle.main.url(forResource:"TranslatePDFFont",withExtension:"bundle"))
    let fonts=try XCTUnwrap(Bundle(url:fontBundle))
    let fontPath=try XCTUnwrap(fonts.path(forResource:"NotoSans-Regular",ofType:"ttf"))
    let cjkFontPath=try XCTUnwrap(fonts.path(forResource:"NotoSansSC-Regular",ofType:"ttf"))
    let response=try decoded(TranslateCoreBridge.exportPage(source.path,index:0,
      blocksJSON:try XCTUnwrap(String(data:requestData,encoding:.utf8)),destination:candidate.path,
      fontPath:fontPath,cjkFontPath:cjkFontPath))
    XCTAssertNil(response["error"] as? String,"UIKit source PDF must export through preserve-source v3: \(response)")
    XCTAssertEqual((response["layoutVersion"] as? NSNumber)?.intValue,3)
    XCTAssertEqual(response["mode"] as? String,"preserve-source")
    XCTAssertEqual((response["sourcePageIndex"] as? NSNumber)?.intValue,0)
    XCTAssertEqual((response["pageCount"] as? NSNumber)?.intValue,1)
    XCTAssertTrue(FileManager.default.fileExists(atPath:candidate.path))
    XCTAssertFalse(FileManager.default.fileExists(atPath:candidate.path+".preserving"))
    XCTAssertEqual((try decoded(TranslateCoreBridge.inspect(source.path))["pageCount"] as? NSNumber)?.intValue,2,
      "exporting page zero must leave the full two-page imported source intact")
    let sourceInspection=try decoded(TranslateCoreBridge.readPage(source.path,index:0))
    let outputInspection=try decoded(TranslateCoreBridge.readPage(candidate.path,index:0))
    XCTAssertEqual(outputInspection["width"] as? Double,sourceInspection["width"] as? Double)
    XCTAssertEqual(outputInspection["height"] as? Double,sourceInspection["height"] as? Double)
    XCTAssertTrue((outputInspection["blocks"] as? [[String:Any]] ?? []).contains{
      (($0["sourceText"] as? String) ?? "").contains("Переведено: клапан 42")
    },"the actual candidate must contain translated text")
    func renderedArtwork(_ url:URL)throws->Data{
      let png=try XCTUnwrap(TranslateCoreBridge.renderPNG(url.path,index:0,width:400))
      let image=try XCTUnwrap(UIImage(data:png)?.cgImage)
      let crop=try XCTUnwrap(image.cropping(to:CGRect(x:280,y:170,width:100,height:85)))
      return try XCTUnwrap(UIImage(cgImage:crop).pngData())
    }
    XCTAssertEqual(try renderedArtwork(candidate),try renderedArtwork(source),
      "red fill and black stroke from the original UIKit source must remain pixel-identical")
    XCTAssertEqual(try Data(contentsOf:source),sourceBytes,"serialization must never mutate the imported source")

    for (url,name,uti) in [(source,"UIKit source PDF","com.adobe.pdf"),(payload,"Exact v3 page payload","public.json"),(candidate,"Successful UIKit v3 candidate PDF","com.adobe.pdf")] {
      let attachment=XCTAttachment(data:try Data(contentsOf:url),uniformTypeIdentifier:uti)
      attachment.name=name;attachment.lifetime = .keepAlways;add(attachment)
    }
    NSLog("U052 successful UIKit native export repro artifacts retained at %@",artifactDirectory.path)
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
    XCTAssertEqual(canceled["status"] as? String, "canceled", "Cancellation must win finalization: \(canceled)")
    XCTAssertNil(try store.export("\(jobID):docx"), "A cancel accepted before terminal commit must remove the just-finalized export record")
    XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path), "A cancel accepted before terminal commit must not leave a legacy DOCX export")
    let generations=(try? FileManager.default.contentsOfDirectory(at:AppPaths.exports,includingPropertiesForKeys:nil)) ?? []
    XCTAssertFalse(generations.contains { $0.lastPathComponent.hasPrefix(jobID+"-") },"Unpublished generation files must be discarded after a terminal cancellation")
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
      "getActivitySnapshot:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
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
      "deleteDocument:(NSString *)documentId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject",
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
    XCTAssertEqual(exportCount, expectedSignatures.count)
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
  func testContinuedProcessingOwnerMovesToRecreatedModuleWithoutRetainingOldOwner() throws {
    let jobID="coverage-owner-\(UUID().uuidString)"
    let documentID="coverage-owner-document-\(UUID().uuidString)"
    let source=AppPaths.documents.appendingPathComponent(documentID+".pdf")
    let store=try TranslatorStore(directory:AppPaths.support)
    defer {moduleNextCleanup(store,documentID:documentID,jobs:[jobID]);try? FileManager.default.removeItem(at:source)}
    try moduleNextPDF(source,labels:["Continued owner fixture"])
    _=try store.saveDocument(id:documentID,name:"Continued owner.pdf",path:source.path,pages:1,sourceSHA256:sha256(at:source))
    _=try store.addJob(id:jobID,input:["documentId":documentID,"modelId":ModelCatalog.compactId,
      "sourceLanguage":"en","targetLanguage":"ru","useGlossary":false],pageCount:1)
    weak var firstOwner:PdfTranslatorModule?
    var firstRequest:PdfTranslatorModule.ContinuedProcessingRequestToken?
    try autoreleasepool {
      let first=PdfTranslatorModule()
      try store.updateJob(jobID,status:"queued")
      firstOwner=first
      first.activeJobIDForTesting=jobID
      first.submitContinuedTaskRequestForTesting={_ in}
      firstRequest=first.scheduleContinuedProcessing(jobID:jobID,documentName:"First owner")
    }
    XCTAssertNil(firstOwner,"continued-task routing must not retain a discarded React Native module")
    let oldRequest=try XCTUnwrap(firstRequest)
    defer{BGTaskScheduler.shared.cancel(taskRequestWithIdentifier:oldRequest.identifier)}

    let currentOwner=PdfTranslatorModule()
    try store.updateJob(jobID,status:"queued")
    currentOwner.activeJobIDForTesting=jobID
    currentOwner.submitContinuedTaskRequestForTesting={_ in}
    let currentRequest=try XCTUnwrap(currentOwner.scheduleContinuedProcessing(jobID:jobID,documentName:"Current owner"))
    XCTAssertNil(PdfTranslatorModule.continuedTaskOwner(for:oldRequest.identifier),
      "a stale per-run identifier must not be rebound to a recreated module")
    XCTAssertTrue(PdfTranslatorModule.continuedTaskOwner(for:currentRequest.identifier) === currentOwner,
      "the current unique request identifier must route to its active module")
    defer{BGTaskScheduler.shared.cancel(taskRequestWithIdentifier:currentRequest.identifier)}
    XCTAssertNotEqual(oldRequest.identifier,currentRequest.identifier,
      "a recreated module cannot reuse the prior run's registered identifier")
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

  func testNotificationPermissionAlreadyRequestedResolvesWithoutPrompt() throws {
    let defaults = UserDefaults.standard
    let key = "dev.kigya.translatepdf.notificationPermissionRequested"
    let previous = defaults.object(forKey: key)
    defaults.set(true, forKey: key)
    defer {
      if let previous { defaults.set(previous, forKey: key) }
      else { defaults.removeObject(forKey: key) }
    }

    let settingsRead = expectation(description: "read the actual OS authorization state without requesting it")
    var authorizationStatus: UNAuthorizationStatus?
    UNUserNotificationCenter.current().getNotificationSettings { settings in
      authorizationStatus = settings.authorizationStatus
      settingsRead.fulfill()
    }
    wait(for: [settingsRead], timeout: 10)
    let expectedStatus: String
    switch try XCTUnwrap(authorizationStatus) {
    case .authorized, .provisional, .ephemeral: expectedStatus = "granted"
    case .denied, .notDetermined: expectedStatus = "denied"
    @unknown default: expectedStatus = "unavailable"
    }

    let module = PdfTranslatorModule()
    let snapshot = try decoded(resolved {
      module.requestNotificationPermission($0, rejecter: $1)
    })
    XCTAssertEqual(snapshot["requestedBefore"] as? Bool, true)
    XCTAssertEqual(snapshot["canRequest"] as? Bool, false,
                   "an already requested permission must resolve without showing another system prompt")
    XCTAssertEqual(snapshot["status"] as? String, expectedStatus,
                   "reported status must map the real OS settings, with notDetermined treated as denied after a prior request")
  }

  func testLightweightActivityAndColorBridgeDoNotExposeCheckpointBodies() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let module = PdfTranslatorModule()
    let id = "coverage-activity-\(UUID().uuidString)"
    let jobID = "\(id)-job"
    let source = AppPaths.documents.appendingPathComponent(id + ".pdf")
    defer {
      try? store.deleteJob(jobID)
      try? store.deleteDocument(id)
      try? FileManager.default.removeItem(at: source)
    }
    XCTAssertTrue(FileManager.default.createFile(atPath: source.path, contents: Data("private activity fixture".utf8)))
    _ = try store.saveDocument(id: id, name: "Activity fixture.pdf", path: source.path, pages: 2, sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: jobID, input: ["documentId": id, "modelId": ModelCatalog.recommendedId, "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 2)
    try store.savePage(jobId: jobID, index: 0, status: "running", blocks: "[{\"sourceText\":\"A private checkpoint body\",\"translatedText\":\"Текст\",\"status\":\"translated\"}]")
    try store.setPageProgress(jobId: jobID, index: 0, currentBlock: 1, pageBlocks: 1)
    try store.updateJob(jobID, status: "running", stage: "translating")
    module.activeJobIDForTesting = jobID

    let inference = try jsonObject(TranslateCoreBridge.inferenceProgress())
    XCTAssertNotNil(inference["processedTokens"] as? NSNumber)
    XCTAssertNotNil(inference["generatedTokens"] as? NSNumber)
    let colored = try decoded(resolved { module.setJobColor(jobID, color: "purple", resolver: $0, rejecter: $1) })
    XCTAssertEqual(colored["labelColor"] as? String, "purple")
    rejected("JOB_COLOR_SAVE_FAILED") { module.setJobColor(jobID, color: "black", resolver: $0, rejecter: $1) }

    let snapshot = try decoded(resolved { module.getActivitySnapshot($0, rejecter: $1) })
    let jobs = try XCTUnwrap(snapshot["jobs"] as? [[String: Any]])
    let job = try XCTUnwrap(jobs.first { $0["id"] as? String == jobID })
    XCTAssertEqual(job["currentBlock"] as? Int64, 1)
    XCTAssertEqual(job["pageBlocks"] as? Int64, 1)
    XCTAssertEqual(job["completedBlocks"] as? Int64, 1)
    XCTAssertEqual(job["workerStopping"] as? Bool, false)
    XCTAssertNotNil(job["processedTokens"] as? NSNumber)
    XCTAssertNotNil(job["generatedTokens"] as? NSNumber)
    XCTAssertNil(job["blocks"])
    XCTAssertNil(job["blocks_json"])
    XCTAssertNil(job["pages"], "polling activity must return job summaries without checkpoint bodies or page arrays")
  }

  func testDeleteBridgeRejectsActiveTranslationsAndRawImportsWithHistory() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let module = PdfTranslatorModule()
    let id = "coverage-delete-guard-\(UUID().uuidString)"
    let jobID = "\(id)-job"
    let source = AppPaths.documents.appendingPathComponent(id + ".pdf")
    defer {
      try? store.deleteJob(jobID)
      try? store.deleteDocument(id)
      try? FileManager.default.removeItem(at: source)
    }
    XCTAssertTrue(FileManager.default.createFile(atPath: source.path, contents: Data("private deletion fixture".utf8)))
    _ = try store.saveDocument(id: id, name: "Deletion fixture.pdf", path: source.path, pages: 1, sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: jobID, input: ["documentId": id, "modelId": ModelCatalog.recommendedId, "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 1)
    try store.updateJob(jobID, status: "running")
    rejected("JOB_DELETE_FAILED") { module.deleteJob(jobID, resolver: $0, rejecter: $1) }
    rejected("DOCUMENT_DELETE_FAILED") { module.deleteDocument(id, resolver: $0, rejecter: $1) }
    XCTAssertTrue(FileManager.default.fileExists(atPath: source.path), "rejected deletion must preserve the source and active checkpoint")
    XCTAssertNotNil(try store.job(jobID))
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

  func testSourcePreservingPdfReceiptRequiresIdentityPagesAndRejectsReflow() throws {
    let module=PdfTranslatorModule()
    let ranges:[[String:Any]]=[
      ["sourcePageIndex":0,"firstOutputPage":0,"outputPageCount":1],
      ["sourcePageIndex":1,"firstOutputPage":1,"outputPageCount":1]
    ]
    let pages:[[String:Any]]=[
      ["outputPageIndex":0,"sourcePageIndex":0,"sourceOutputPageIndex":0,"placements":[["blockId":"p0b0","outputPageIndex":0,"sourceOutputPageIndex":0]]],
      ["outputPageIndex":1,"sourcePageIndex":1,"sourceOutputPageIndex":0,"placements":[["blockId":"p1b0","outputPageIndex":1,"sourceOutputPageIndex":0]]]
    ]
    XCTAssertNoThrow(try module.validateSourcePreservingPageMap(sourcePageCount:2,sourceRanges:ranges,pages:pages))

    var movedPage=pages
    movedPage[1]["sourcePageIndex"]=0
    XCTAssertThrowsError(try module.validateSourcePreservingPageMap(sourcePageCount:2,sourceRanges:ranges,pages:movedPage))
    var overflowRange=ranges
    overflowRange[0]["outputPageCount"]=2
    XCTAssertThrowsError(try module.validateSourcePreservingPageMap(sourcePageCount:2,sourceRanges:overflowRange,pages:pages))
    XCTAssertThrowsError(try module.validateSourcePreservingPageMap(sourcePageCount:2,sourceRanges:Array(ranges.dropLast()),pages:pages))
    XCTAssertThrowsError(try module.validateSourcePreservingPageMap(sourcePageCount:0,sourceRanges:[],pages:[]))
    var movedPlacement=pages
    movedPlacement[1]["placements"]=[["blockId":"p1b0","outputPageIndex":0,"sourceOutputPageIndex":0]]
    XCTAssertThrowsError(try module.validateSourcePreservingPageMap(sourcePageCount:2,sourceRanges:ranges,pages:movedPlacement))

    let sourceSHA=String(repeating:"a",count:64),checkpoint=String(repeating:"b",count:64),outputSHA=String(repeating:"c",count:64)
    var receipt:[String:Any]=["schemaVersion":3,"layoutVersion":3,"mode":"preserve-source",
      "rendererPolicy":PdfTranslatorModule.pdfRendererPolicy,"complete":true,"sourceSha256":sourceSHA,
      "checkpointDigest":checkpoint,"outputSha256":outputSHA,"sourcePageCount":2,"outputPageCount":2,
      "sourceRanges":ranges,"pages":pages]
    receipt["receiptSha256"]=SHA256.hash(data:Data(try jsonString(receipt).utf8)).map{String(format:"%02x",$0)}.joined()
    XCTAssertNoThrow(try module.validatePdfLayoutReceipt(receipt,sourcePageCount:2,expectedSourceSHA:sourceSHA,
      expectedCheckpointDigest:checkpoint,expectedOutputSHA:outputSHA))

    var legacy=receipt
    legacy["schemaVersion"]=1;legacy["layoutVersion"]=2;legacy["mode"]="reflow";legacy["rendererPolicy"]="reflow-inline-symbols-1"
    legacy.removeValue(forKey:"receiptSha256")
    legacy["receiptSha256"]=SHA256.hash(data:Data(try jsonString(legacy).utf8)).map{String(format:"%02x",$0)}.joined()
    XCTAssertThrowsError(try module.validatePdfLayoutReceipt(legacy,sourcePageCount:2,expectedSourceSHA:sourceSHA,
      expectedCheckpointDigest:checkpoint,expectedOutputSHA:outputSHA),"reflow receipts must never satisfy the source-preserving export contract")
  }

  func testSourcePreservingPageMapRejectsCoercedAndNonIntegralNumbers() throws {
    let module=PdfTranslatorModule()
    let validRanges:[[String:Any]]=[["sourcePageIndex":0,"firstOutputPage":0,"outputPageCount":1]]
    let validPages:[[String:Any]]=[["outputPageIndex":0,"sourcePageIndex":0,"sourceOutputPageIndex":0,
      "placements":[["blockId":"p0b0","outputPageIndex":0,"sourceOutputPageIndex":0]]]]
    XCTAssertNoThrow(try module.validateSourcePreservingPageMap(sourcePageCount:1,sourceRanges:validRanges,pages:validPages))
    let malformed:[Any]=[NSNumber(value:0.9),NSNumber(value:true),"0",NSNumber(value:Int.max),
      NSNumber(value:Int.min),NSNumber(value:UInt64.max)]

    for bad in malformed {
      var ranges=validRanges;ranges[0]["sourcePageIndex"]=bad
      XCTAssertThrowsError(try module.validateSourcePreservingPageMap(sourcePageCount:1,sourceRanges:ranges,pages:validPages))
      ranges=validRanges;ranges[0]["firstOutputPage"]=bad
      XCTAssertThrowsError(try module.validateSourcePreservingPageMap(sourcePageCount:1,sourceRanges:ranges,pages:validPages))
      var wrongCount=validRanges;wrongCount[0]["outputPageCount"]=NSNumber(value:1.9)
      XCTAssertThrowsError(try module.validateSourcePreservingPageMap(sourcePageCount:1,sourceRanges:wrongCount,pages:validPages))
      for key in ["outputPageIndex","sourcePageIndex","sourceOutputPageIndex"] {
        var pages=validPages;pages[0][key]=bad
        XCTAssertThrowsError(try module.validateSourcePreservingPageMap(sourcePageCount:1,sourceRanges:validRanges,pages:pages),"page field \(key) accepted \(bad)")
      }
      for key in ["outputPageIndex","sourceOutputPageIndex"] {
        var pages=validPages;var placements=pages[0]["placements"] as! [[String:Any]]
        placements[0][key]=bad;pages[0]["placements"]=placements
        XCTAssertThrowsError(try module.validateSourcePreservingPageMap(sourcePageCount:1,sourceRanges:validRanges,pages:pages),"placement field \(key) accepted \(bad)")
      }
    }

    let sourceSHA=String(repeating:"a",count:64),checkpoint=String(repeating:"b",count:64),outputSHA=String(repeating:"c",count:64)
    let content=try module.makePdfLayoutReceipt(sourcePageCount:1,sourceSHA:sourceSHA,checkpointDigest:checkpoint,
      outputSHA:outputSHA,sourceRanges:validRanges,mergedPages:validPages,placementsBySource:[[]])
    XCTAssertNoThrow(try module.validatePdfLayoutReceipt(content,sourcePageCount:1,expectedSourceSHA:sourceSHA,
      expectedCheckpointDigest:checkpoint,expectedOutputSHA:outputSHA),"a correctly formed, hashed receipt is accepted")

    for (field,expected) in [("schemaVersion",3),("layoutVersion",3),("sourcePageCount",1),("outputPageCount",1)] {
      for bad:Any in [NSNumber(value:Double(expected)+0.9),NSNumber(value:true),String(expected),NSNumber(value:Int64.max)] {
        var receipt=content;receipt[field]=bad
        receipt.removeValue(forKey:"receiptSha256")
        receipt["receiptSha256"]=SHA256.hash(data:Data(try jsonString(receipt).utf8)).map{String(format:"%02x",$0)}.joined()
        XCTAssertThrowsError(try module.validatePdfLayoutReceipt(receipt,sourcePageCount:1,expectedSourceSHA:sourceSHA,
          expectedCheckpointDigest:checkpoint,expectedOutputSHA:outputSHA),"receipt field \(field) accepted \(bad)")
      }
    }

    for bad in malformed {
      for key in ["outputPageIndex","sourcePageIndex","sourceOutputPageIndex"] {
        var badPage=validPages;badPage[0][key]=bad
        XCTAssertThrowsError(try module.makePdfLayoutReceipt(sourcePageCount:1,sourceSHA:sourceSHA,
          checkpointDigest:checkpoint,outputSHA:outputSHA,sourceRanges:validRanges,mergedPages:badPage,
          placementsBySource:[[]]),"fresh merge field \(key) accepted \(bad) before page-map validation")
      }
      var receipt=content
      var pages=validPages;pages[0]["sourcePageIndex"]=bad;receipt["pages"]=pages
      receipt.removeValue(forKey:"receiptSha256")
      receipt["receiptSha256"]=SHA256.hash(data:Data(try jsonString(receipt).utf8)).map{String(format:"%02x",$0)}.joined()
      XCTAssertThrowsError(try module.validatePdfLayoutReceipt(receipt,sourcePageCount:1,expectedSourceSHA:sourceSHA,
        expectedCheckpointDigest:checkpoint,expectedOutputSHA:outputSHA),"well-hashed receipt accepted malformed page index \(bad)") { error in
        XCTAssertTrue(error.localizedDescription.contains("page map"),"failure must come from strict map validation, got: \(error)")
      }
    }

    for (field,bad) in [("complete",NSNumber(value:1)),("complete",NSNumber(value:0)),("complete","true" as Any)] {
      var receipt=content;receipt[field]=bad
      receipt.removeValue(forKey:"receiptSha256")
      receipt["receiptSha256"]=SHA256.hash(data:Data(try jsonString(receipt).utf8)).map{String(format:"%02x",$0)}.joined()
      XCTAssertThrowsError(try module.validatePdfLayoutReceipt(receipt,sourcePageCount:1,expectedSourceSHA:sourceSHA,
        expectedCheckpointDigest:checkpoint,expectedOutputSHA:outputSHA),"receipt complete must be a JSON boolean, not \(bad)")
    }

    for field in ["schemaVersion","sourcePageCount","outputPageCount"] {
      for bad in [NSNumber(value:Double.nan),NSNumber(value:Double.infinity)] {
        var receipt=content;receipt[field]=bad
        XCTAssertThrowsError(try module.validatePdfLayoutReceipt(receipt,sourcePageCount:1,expectedSourceSHA:sourceSHA,
          expectedCheckpointDigest:checkpoint,expectedOutputSHA:outputSHA),"receipt field \(field) accepted nonfinite NSNumber")
      }
    }
    for bad in [NSNumber(value:Double.nan),NSNumber(value:Double.infinity)] {
      for key in ["outputPageIndex","sourcePageIndex","sourceOutputPageIndex"] {
        var badPage=validPages;badPage[0][key]=bad
        XCTAssertThrowsError(try module.makePdfLayoutReceipt(sourcePageCount:1,sourceSHA:sourceSHA,
          checkpointDigest:checkpoint,outputSHA:outputSHA,sourceRanges:validRanges,mergedPages:badPage,
          placementsBySource:[[]]),"fresh merge field \(key) accepted nonfinite NSNumber")
      }
    }
  }

  func testRawSourcePageExportResponseRejectsCoercedIdentityNumbers() throws {
    let module=PdfTranslatorModule()
    let valid:[String:Any] = ["layoutVersion":3,"mode":"preserve-source","sourcePageIndex":0,
      "pageCount":1,"placements":[["blockId":"p0b0","outputPageIndex":0]]]
    XCTAssertEqual(try module.validateSourcePageExportResult(valid,sourcePageIndex:0).count,1)
    let cases:[(String,Any)] = [
      ("layoutVersion",NSNumber(value:3.9)),("layoutVersion",NSNumber(value:true)),("layoutVersion","3"),
      ("sourcePageIndex",NSNumber(value:0.9)),("sourcePageIndex",NSNumber(value:false)),("sourcePageIndex","0"),
      ("pageCount",NSNumber(value:1.9)),("pageCount",NSNumber(value:true)),("pageCount","1")
    ]
    for (field,bad) in cases {
      var response=valid;response[field]=bad
      XCTAssertThrowsError(try module.validateSourcePageExportResult(response,sourcePageIndex:0),
        "raw native export response accepted malformed \(field)=\(bad)")
    }
    var wrongMode=valid;wrongMode["mode"]="reflow"
    XCTAssertThrowsError(try module.validateSourcePageExportResult(wrongMode,sourcePageIndex:0))
    var missingPlacements=valid;missingPlacements.removeValue(forKey:"placements")
    XCTAssertThrowsError(try module.validateSourcePageExportResult(missingPlacements,sourcePageIndex:0))
  }

  func testFreshSourcePreservingMergeResponseAndNativeCountsRequireExactIntegers() throws {
    let module=PdfTranslatorModule()
    let valid:[String:Any] = ["layoutVersion":3,"mode":"preserve-source","pageCount":1,
      "sourcePageRanges":[["sourcePageIndex":0,"firstOutputPage":0,"outputPageCount":1]],
      "pages":[["outputPageIndex":0,"sourcePageIndex":0,"sourceOutputPageIndex":0,"placements":[]]]]
    XCTAssertNoThrow(try module.validateSourcePreservingMergeResult(valid,sourcePageCount:1))
    for (field,bad): (String,Any) in [
      ("layoutVersion",NSNumber(value:3.9)),("layoutVersion",NSNumber(value:true)),("layoutVersion","3"),
      ("pageCount",NSNumber(value:1.9)),("pageCount",NSNumber(value:false)),("pageCount","1"),
      ("pageCount",NSNumber(value:UInt64.max)),("pageCount",NSNumber(value:Double.infinity))
    ] {
      var response=valid;response[field]=bad
      XCTAssertThrowsError(try module.validateSourcePreservingMergeResult(response,sourcePageCount:1),
        "fresh merge response accepted malformed \(field)=\(bad)")
    }
    XCTAssertTrue(module.nativePageCountMatches(["pageCount":NSNumber(value:Int.max)],expected:Int.max))
    for malformed:Any in [NSNumber(value:1.9),NSNumber(value:true),"1",NSNumber(value:Double.nan),NSNumber(value:UInt64.max)] {
      XCTAssertFalse(module.nativePageCountMatches(["pageCount":malformed],expected:1),
        "a native inspect count was coerced from \(malformed)")
    }
  }

  @available(iOS 26.0,*)
  func testContinuedProcessingSubmitFailureDoesNotClaimBackgroundOwnership() throws {
    let store=try TranslatorStore(directory:AppPaths.support)
    let module=PdfTranslatorModule()
    let documentID="continued-submit-failure-\(UUID().uuidString)"
    let jobID="continued-submit-failure-job-\(UUID().uuidString)"
    let source=AppPaths.documents.appendingPathComponent(documentID+".pdf")
    defer{moduleNextCleanup(store,documentID:documentID,jobs:[jobID]);try? FileManager.default.removeItem(at:source)}
    try moduleNextPDF(source,labels:["Continued submission failure fixture"])
    _=try store.saveDocument(id:documentID,name:"Continued submission.pdf",path:source.path,pages:1,sourceSHA256:sha256(at:source))
    _=try store.addJob(id:jobID,input:["documentId":documentID,"modelId":ModelCatalog.compactId,
      "sourceLanguage":"en","targetLanguage":"ru","useGlossary":false],pageCount:1)
    module.activeJobIDForTesting=jobID
    var rejectedIdentifier:String?
    module.submitContinuedTaskRequestForTesting={request in
      rejectedIdentifier=request.identifier
      throw NSError(domain:"test.scheduler",code:1,userInfo:[NSLocalizedDescriptionKey:"simulated submission rejection"])
    }
    XCTAssertNil(module.scheduleContinuedProcessing(jobID:jobID,documentName:"Submission fixture"))
    let identifier=try XCTUnwrap(rejectedIdentifier)
    defer{BGTaskScheduler.shared.cancel(taskRequestWithIdentifier:identifier)}
    XCTAssertNil(PdfTranslatorModule.continuedTaskOwner(for:identifier),"failed submit must remove callback ownership")
    XCTAssertEqual(module.activeJobIDForTesting,jobID,"failed submit must not release the user-started worker")
    XCTAssertEqual(try store.job(jobID)?["status"] as? String,"queued","failed submit must preserve the durable queued job")
  }

  @available(iOS 26.0,*)
  func testContinuedProcessingCallbackOnlyAttachesToAnAlreadyOwnedActiveRun() throws {
    let store=try TranslatorStore(directory:AppPaths.support)
    let module=PdfTranslatorModule()
    // The simulator cannot run BGTaskScheduler submissions. This accepts the
    // request for deterministic routing tests; no BGContinuedProcessingTask is forged.
    module.submitContinuedTaskRequestForTesting={_ in}
    let documentID="continued-admission-\(UUID().uuidString)"
    let source=AppPaths.documents.appendingPathComponent(documentID+".pdf")
    let statuses=["paused","canceled","completed","partial","failed","running"]
    let jobIDs=statuses.map{"continued-\($0)-\(UUID().uuidString)"}
    defer {moduleNextCleanup(store,documentID:documentID,jobs:jobIDs);try? FileManager.default.removeItem(at:source)}
    try moduleNextPDF(source,labels:["Scheduler admission fixture"])
    _=try store.saveDocument(id:documentID,name:"Continued processing.pdf",path:source.path,pages:1,sourceSHA256:sha256(at:source))
    for (jobID,status) in zip(jobIDs,statuses) {
      _=try store.addJob(id:jobID,input:["documentId":documentID,"modelId":ModelCatalog.compactId,
        "sourceLanguage":"en","targetLanguage":"ru","useGlossary":false],pageCount:1)
      try store.updateJob(jobID,status:status)
      if ["canceled","completed","partial"].contains(status) {
        _=try decoded(resolved{module.pauseJob(jobID,resolver:$0,rejecter:$1)})
        XCTAssertEqual(try store.job(jobID)?["status"] as? String,status,
          "a late Pause request must not resurrect terminal (status) work")
      }
      XCTAssertNil(module.scheduleContinuedProcessing(jobID:jobID,documentName:"Inactive \(status)"),
        "a queued callback must not schedule or claim durable \(status) work without an active owner")
      XCTAssertNil(module.activeJobIDForTesting,"rejected callback must not claim the processing slot")
    }

    let queuedID="continued-queued-\(UUID().uuidString)"
    _=try store.addJob(id:queuedID,input:["documentId":documentID,"modelId":ModelCatalog.compactId,
      "sourceLanguage":"en","targetLanguage":"ru","useGlossary":false],pageCount:1)
    defer {try? store.deleteJob(queuedID)}
    module.activeJobIDForTesting="different-active-job"
    XCTAssertNil(module.scheduleContinuedProcessing(jobID:queuedID,documentName:"Unowned fixture"),
      "an inactive queued job must not replace another active job")
    XCTAssertEqual(module.activeJobIDForTesting,"different-active-job")
    module.activeJobIDForTesting=nil
    XCTAssertNil(module.scheduleContinuedProcessing(jobID:queuedID,documentName:"Queued but inactive"))
    XCTAssertNil(module.activeJobIDForTesting,"the scheduler callback cannot claim an inactive queued job")

    module.activeJobIDForTesting=queuedID
    let request=try XCTUnwrap(module.scheduleContinuedProcessing(jobID:queuedID,documentName:"Active fixture"))
    defer{BGTaskScheduler.shared.cancel(taskRequestWithIdentifier:request.identifier)}
    var attached=false
    XCTAssertTrue(module.attachContinuedProcessing(jobID:queuedID,identifier:request.identifier,
      generation:request.generation){attached=true})
    XCTAssertTrue(attached,"a callback matching the active request may attach to its existing worker")
    try store.updateJob(queuedID,status:"canceled")
    attached=false
    XCTAssertFalse(module.attachContinuedProcessing(jobID:queuedID,identifier:request.identifier,
      generation:request.generation){attached=true},"a stale callback cannot attach after the durable job becomes terminal")
    XCTAssertFalse(attached)
    XCTAssertEqual(module.activeJobIDForTesting,queuedID,"rejection must not replace or clear the existing owner's slot")
  }

  @available(iOS 26.0,*)
  func testContinuedProcessingAdmissionExpirationContractRejectsStaleGenerationAfterPauseResume() throws {
    let store=try TranslatorStore(directory:AppPaths.support)
    let module=PdfTranslatorModule()
    module.submitContinuedTaskRequestForTesting={_ in}
    let documentID="continued-cancel-\(UUID().uuidString)",jobID="continued-cancel-job-\(UUID().uuidString)"
    let source=AppPaths.documents.appendingPathComponent(documentID+".pdf")
    defer {moduleNextCleanup(store,documentID:documentID,jobs:[jobID]);try? FileManager.default.removeItem(at:source)}
    try moduleNextPDF(source,labels:["Cancellation fixture"])
    _=try store.saveDocument(id:documentID,name:"Cancellation.pdf",path:source.path,pages:1,sourceSHA256:sha256(at:source))
    _=try store.addJob(id:jobID,input:["documentId":documentID,"modelId":ModelCatalog.compactId,
      "sourceLanguage":"en","targetLanguage":"ru","useGlossary":false],pageCount:1)
    module.activeJobIDForTesting=jobID
    let queuedRequest=try XCTUnwrap(module.scheduleContinuedProcessing(jobID:jobID,documentName:"Cancellation fixture"))
    XCTAssertTrue(PdfTranslatorModule.continuedTaskOwner(for:queuedRequest.identifier) === module)
    _=try decoded(resolved{module.cancelJob(jobID,resolver:$0,rejecter:$1)})
    module.processJob(jobID)
    XCTAssertEqual(try store.job(jobID)?["status"] as? String,"canceled")
    XCTAssertNil(PdfTranslatorModule.continuedTaskOwner(for:queuedRequest.identifier),"cancel must remove the queued callback owner")
    XCTAssertFalse(module.attachContinuedProcessing(jobID:jobID,identifier:queuedRequest.identifier,
      generation:queuedRequest.generation){},"a late callback after cancellation cannot attach to work")

    let resumedID="continued-expiration-\(UUID().uuidString)"
    _=try store.addJob(id:resumedID,input:["documentId":documentID,"modelId":ModelCatalog.compactId,
      "sourceLanguage":"en","targetLanguage":"ru","useGlossary":false],pageCount:1)
    defer {
      module.cancelJob(resumedID,resolver:{_ in},rejecter:{_,_,_ in})
      try? store.deleteJob(resumedID)
    }
    module.activeJobIDForTesting=resumedID
    let priorRun=try XCTUnwrap(module.activeRunTokenForTesting)
    let prior=try XCTUnwrap(module.scheduleContinuedProcessing(jobID:resumedID,documentName:"Prior run"))
    module.requestPause(jobID:resumedID,reason:"Pause before resume")
    module.processJob(resumedID)
    XCTAssertEqual(try store.job(resumedID)?["status"] as? String,"paused")
    try store.updateJob(resumedID,status:"queued")
    module.activeJobIDForTesting=resumedID
    let resumed=try XCTUnwrap(module.scheduleContinuedProcessing(jobID:resumedID,documentName:"Resumed fixture"))
    defer{BGTaskScheduler.shared.cancel(taskRequestWithIdentifier:resumed.identifier)}
    module.cancelQueuedContinuedProcessing(jobID:resumedID,runToken:priorRun)
    XCTAssertTrue(module.attachContinuedProcessing(jobID:resumedID,identifier:resumed.identifier,
      generation:resumed.generation){},"late cleanup from the prior run must not cancel the new same-job request")
    module.requestPause(jobID:resumedID,reason:"Expired task from previous run",continuedGeneration:prior.generation)
    XCTAssertTrue(module.attachContinuedProcessing(jobID:resumedID,identifier:resumed.identifier,
      generation:resumed.generation){},"an old expiration must not pause the same job's new run")

    let unrelatedID="continued-unrelated-\(UUID().uuidString)"
    _=try store.addJob(id:unrelatedID,input:["documentId":documentID,"modelId":ModelCatalog.compactId,
      "sourceLanguage":"en","targetLanguage":"ru","useGlossary":false],pageCount:1)
    defer {try? store.deleteJob(unrelatedID)}
    XCTAssertNil(module.scheduleContinuedProcessing(jobID:unrelatedID,documentName:"Unrelated queued fixture"),
      "an unrelated queued job has no active worker to attach to")
    _=try resolved{module.cancelJob(unrelatedID,resolver:$0,rejecter:$1)}
    module.requestPause(jobID:resumedID,reason:"Current task expiration",continuedGeneration:resumed.generation)
    XCTAssertFalse(module.attachContinuedProcessing(jobID:resumedID,identifier:resumed.identifier,
      generation:resumed.generation){},"canceling another job must not invalidate this run's current expiration generation")
  }

  @available(iOS 26.0,*)
  func testQueuedPauseAndCancelAreAcknowledgedBeforeWorkerReturnsWithoutLeakingOwner() throws {
    let store=try TranslatorStore(directory:AppPaths.support)
    let module=PdfTranslatorModule()
    let documentID="continued-early-stop-\(UUID().uuidString)"
    let source=AppPaths.documents.appendingPathComponent(documentID+".pdf")
    let pauseID="continued-early-pause-\(UUID().uuidString)",cancelID="continued-early-cancel-\(UUID().uuidString)"
    defer {moduleNextCleanup(store,documentID:documentID,jobs:[pauseID,cancelID]);try? FileManager.default.removeItem(at:source)}
    try moduleNextPDF(source,labels:["Early stop fixture"])
    _=try store.saveDocument(id:documentID,name:"Early stop.pdf",path:source.path,pages:1,sourceSHA256:sha256(at:source))
    for jobID in [pauseID,cancelID] {
      _=try store.addJob(id:jobID,input:["documentId":documentID,"modelId":ModelCatalog.compactId,
        "sourceLanguage":"en","targetLanguage":"ru","useGlossary":false],pageCount:1)
    }

    module.activeJobIDForTesting=pauseID
    module.requestPause(jobID:pauseID,reason:"Pause before queued worker entry")
    module.processJob(pauseID)
    XCTAssertEqual(try store.job(pauseID)?["status"] as? String,"paused")
    XCTAssertNil(module.activeJobIDForTesting,"pause acknowledgement must release only the claimed worker slot")

    module.activeJobIDForTesting=cancelID
    _=try resolved{module.cancelJob(cancelID,resolver:$0,rejecter:$1)}
    module.processJob(cancelID)
    XCTAssertEqual(try store.job(cancelID)?["status"] as? String,"canceled")
    XCTAssertNil(module.activeJobIDForTesting,"cancel acknowledgement must release only the claimed worker slot")
  }

  @available(iOS 26.0,*)
  func testFailedStopPersistenceRetriesThroughWorkerStopGateWithoutRestartingInference() throws {
    let store=try TranslatorStore(directory:AppPaths.support)
    let module=PdfTranslatorModule()
    let documentID="continued-stop-write-failure-\(UUID().uuidString)"
    let jobID="continued-stop-write-failure-job-\(UUID().uuidString)"
    let source=AppPaths.documents.appendingPathComponent(documentID+".pdf")
    defer{moduleNextCleanup(store,documentID:documentID,jobs:[jobID]);try? FileManager.default.removeItem(at:source)}
    try moduleNextPDF(source,labels:["Stop acknowledgement fixture"])
    _=try store.saveDocument(id:documentID,name:"Stop acknowledgement.pdf",path:source.path,pages:1,sourceSHA256:sha256(at:source))
    _=try store.addJob(id:jobID,input:["documentId":documentID,"modelId":ModelCatalog.compactId,
      "sourceLanguage":"en","targetLanguage":"ru","useGlossary":false],pageCount:1)
    module.activeJobIDForTesting=jobID
    module.requestPause(jobID:jobID,reason:"Pause while persistence is unavailable")
    let attemptsLock=NSLock()
    var persistenceAttempts=0
    module.beforeWorkerStopPersistenceForTesting={
      attemptsLock.lock();persistenceAttempts += 1;let attempt=persistenceAttempts;attemptsLock.unlock()
      if attempt==1{throw NSError(domain:"test.storage",code:1,userInfo:[NSLocalizedDescriptionKey:"simulated SQLite write failure"])}
    }
    module.processJob(jobID)
    XCTAssertEqual(module.activeJobIDForTesting,jobID,"a failed status write must keep ownership until acknowledgement")
    XCTAssertEqual(try store.job(jobID)?["status"] as? String,"queued","a failed write must not report a paused durable row")
    let savedStop=expectation(description:"bounded worker retry durably acknowledges the pending pause")
    let deadline=Date().addingTimeInterval(4.5)
    func pollForSavedStop(){
      if (try? store.job(jobID)?["status"] as? String)=="paused"{savedStop.fulfill();return}
      guard Date()<deadline else{return}
      DispatchQueue.global(qos:.utility).asyncAfter(deadline:.now()+0.1){pollForSavedStop()}
    }
    DispatchQueue.global(qos:.utility).asyncAfter(deadline:.now()+0.1){pollForSavedStop()}
    wait(for:[savedStop],timeout:5)
    XCTAssertEqual(try store.job(jobID)?["status"] as? String,"paused")
    attemptsLock.lock();let recordedAttempts=persistenceAttempts;attemptsLock.unlock()
    XCTAssertGreaterThanOrEqual(recordedAttempts,2,"the real processJob stop gate must retry a transient store failure")
    XCTAssertNil(module.activeJobIDForTesting)
  }

  func testMidWorkerPausePersistenceFailureRetriesDurablyBeforeReleasingOwner() throws {
    let store=try TranslatorStore(directory:AppPaths.support)
    let module=PdfTranslatorModule()
    let documentID="mid-worker-stop-write-\(UUID().uuidString)",jobID="mid-worker-stop-job-\(UUID().uuidString)"
    let source=AppPaths.documents.appendingPathComponent(documentID+".pdf")
    defer{moduleNextCleanup(store,documentID:documentID,jobs:[jobID]);try? FileManager.default.removeItem(at:source)}
    try moduleNextPDF(source,labels:["Mid-worker stop fixture"])
    _=try store.saveDocument(id:documentID,name:"Mid-worker stop.pdf",path:source.path,pages:1,sourceSHA256:sha256(at:source))
    _=try store.addJob(id:jobID,input:["documentId":documentID,"modelId":ModelCatalog.compactId,
      "sourceLanguage":"en","targetLanguage":"ru","useGlossary":false],pageCount:1)
    try moduleNextSaveTranslatedCheckpoint(store,jobID:jobID,source:source)
    module.activeJobIDForTesting=jobID
    var injectedPause=false
    module.beforeWorkerPageStopCheckForTesting={
      guard !injectedPause else{return}
      injectedPause=true
      module.requestPause(jobID:jobID,reason:"Pause during page processing")
    }
    let persistenceLock=NSLock()
    var persistenceAttempts=0
    module.beforeWorkerStopPersistenceForTesting={
      persistenceLock.lock();persistenceAttempts += 1;let attempt=persistenceAttempts;persistenceLock.unlock()
      if attempt==1{throw NSError(domain:"test.storage",code:1,userInfo:[NSLocalizedDescriptionKey:"transient mid-worker status write failure"])}
    }
    module.processJob(jobID)
    XCTAssertTrue(injectedPause,"the actual page-processing stop check must issue PauseSignal")
    XCTAssertEqual(module.activeJobIDForTesting,jobID,"a failed PauseSignal acknowledgement must retain its worker owner")
    XCTAssertEqual(try store.job(jobID)?["status"] as? String,"running","failed persistence must not claim a durable pause")
    let saved=expectation(description:"the bounded production retry persists the mid-worker pause")
    let deadline=Date().addingTimeInterval(5)
    func poll(){
      if (try? store.job(jobID)?["status"] as? String)=="paused"{saved.fulfill();return}
      guard Date()<deadline else{return}
      DispatchQueue.global(qos:.utility).asyncAfter(deadline:.now()+0.1){poll()}
    }
    DispatchQueue.global(qos:.utility).asyncAfter(deadline:.now()+0.1){poll()}
    wait(for:[saved],timeout:5.5)
    persistenceLock.lock();let attempts=persistenceAttempts;persistenceLock.unlock()
    XCTAssertGreaterThanOrEqual(attempts,2,"the worker retry must repeat the durable stop write")
    XCTAssertNil(module.activeJobIDForTesting,"release follows the successful durable acknowledgement")
  }

  func testPauseSignalAndConcurrentCancelPersistCanceledOutcome() throws {
    let store=try TranslatorStore(directory:AppPaths.support)
    let module=PdfTranslatorModule()
    let documentID="pause-cancel-precedence-\(UUID().uuidString)",jobID="pause-cancel-job-\(UUID().uuidString)"
    let source=AppPaths.documents.appendingPathComponent(documentID+".pdf")
    defer{moduleNextCleanup(store,documentID:documentID,jobs:[jobID]);try? FileManager.default.removeItem(at:source)}
    try moduleNextPDF(source,labels:["Pause cancel precedence fixture"])
    _=try store.saveDocument(id:documentID,name:"Pause cancel.pdf",path:source.path,pages:1,sourceSHA256:sha256(at:source))
    _=try store.addJob(id:jobID,input:["documentId":documentID,"modelId":ModelCatalog.compactId,
      "sourceLanguage":"en","targetLanguage":"ru","useGlossary":false],pageCount:1)
    try moduleNextSaveTranslatedCheckpoint(store,jobID:jobID,source:source)
    module.activeJobIDForTesting=jobID
    let cancelCompleted=DispatchSemaphore(value:0)
    var requested=false
    module.beforeWorkerPageStopCheckForTesting={
      guard !requested else{return}
      requested=true
      module.requestPause(jobID:jobID,reason:"Pause arrived first")
    }
    module.beforeRunningStopOutcomeSelectionForTesting={
      module.cancelJob(jobID,resolver:{_ in cancelCompleted.signal()},rejecter:{_,_,_ in cancelCompleted.signal()})
      _=cancelCompleted.wait(timeout:.now()+5)
    }
    let workerDone=expectation(description:"the worker resolves the concurrent stop requests")
    DispatchQueue.global(qos:.userInitiated).async{module.processJob(jobID);workerDone.fulfill()}
    wait(for:[workerDone],timeout:8)
    XCTAssertTrue(requested)
    XCTAssertEqual(try store.job(jobID)?["status"] as? String,"canceled","Cancel must win when it races an already requested Pause")
    XCTAssertNil(module.activeJobIDForTesting)
  }

  func testLegacyBackgroundExpirationTokenCannotPauseResumedSameJob() throws {
    let store=try TranslatorStore(directory:AppPaths.support)
    let module=PdfTranslatorModule()
    let documentID="legacy-bg-run-fence-\(UUID().uuidString)",jobID="legacy-bg-run-job-\(UUID().uuidString)"
    let source=AppPaths.documents.appendingPathComponent(documentID+".pdf")
    defer{moduleNextCleanup(store,documentID:documentID,jobs:[jobID]);try? FileManager.default.removeItem(at:source)}
    try moduleNextPDF(source,labels:["Background callback run token fixture"])
    _=try store.saveDocument(id:documentID,name:"Background callback.pdf",path:source.path,pages:1,sourceSHA256:sha256(at:source))
    _=try store.addJob(id:jobID,input:["documentId":documentID,"modelId":ModelCatalog.compactId,
      "sourceLanguage":"en","targetLanguage":"ru","useGlossary":false],pageCount:1)
    try moduleNextSaveTranslatedCheckpoint(store,jobID:jobID,source:source)

    module.activeJobIDForTesting=jobID
    let oldRun=try XCTUnwrap(module.activeRunTokenForTesting)
    module.requestPause(jobID:jobID,reason:"Stop the first run")
    module.processJob(jobID)
    XCTAssertEqual(try store.job(jobID)?["status"] as? String,"paused")
    try store.updateJob(jobID,status:"queued",clearError:true)
    module.activeJobIDForTesting=jobID
    let resumedRun=try XCTUnwrap(module.activeRunTokenForTesting)
    XCTAssertNotEqual(oldRun,resumedRun)
    XCTAssertFalse(PdfTranslatorModule.backgroundTaskOwnerMatches(ownerToken:resumedRun,endingToken:oldRun),
      "an old asynchronous end callback must not end the resumed run's UIKit task")
    XCTAssertTrue(PdfTranslatorModule.backgroundTaskOwnerMatches(ownerToken:resumedRun,endingToken:resumedRun))
    XCTAssertFalse(PdfTranslatorModule.backgroundTaskOwnerMatches(ownerToken:nil,endingToken:oldRun))
    module.requestPause(jobID:jobID,reason:"Late expiration from old UIApplication task",backgroundRunToken:oldRun)
    XCTAssertEqual(try store.job(jobID)?["status"] as? String,"queued","a stale expiration callback must not mutate the resumed run")
    XCTAssertEqual(module.activeJobIDForTesting,jobID)
    module.requestPause(jobID:jobID,reason:"Current UIApplication task expiration",backgroundRunToken:resumedRun)
    module.processJob(jobID)
    XCTAssertEqual(try store.job(jobID)?["status"] as? String,"paused","the current run's own expiration remains effective")
  }

  @available(iOS 26.0,*)
  func testQueuedStopRetryCannotEnterSameJobsNewRunAfterItsTokenIsInvalidated() throws {
    let store=try TranslatorStore(directory:AppPaths.support)
    let module=PdfTranslatorModule()
    let documentID="continued-stale-stop-retry-\(UUID().uuidString)"
    let jobID="continued-stale-stop-retry-job-\(UUID().uuidString)"
    let source=AppPaths.documents.appendingPathComponent(documentID+".pdf")
    defer{moduleNextCleanup(store,documentID:documentID,jobs:[jobID]);try? FileManager.default.removeItem(at:source)}
    try moduleNextPDF(source,labels:["Stale retry admission fixture"])
    _=try store.saveDocument(id:documentID,name:"Stale retry.pdf",path:source.path,pages:1,sourceSHA256:sha256(at:source))
    _=try store.addJob(id:jobID,input:["documentId":documentID,"modelId":ModelCatalog.compactId,
      "sourceLanguage":"en","targetLanguage":"ru","useGlossary":false],pageCount:1)
    module.activeJobIDForTesting=jobID
    module.requestPause(jobID:jobID,reason:"Pause before a queued retry")
    let persistenceLock=NSLock()
    var persistenceAttempts=0
    module.beforeWorkerStopPersistenceForTesting={
      persistenceLock.lock();persistenceAttempts += 1;let attempt=persistenceAttempts;persistenceLock.unlock()
      if attempt==1{throw NSError(domain:"test.storage",code:1,userInfo:[NSLocalizedDescriptionKey:"first write fails"])}
    }
    let retryEntered=expectation(description:"the real delayed retry enters its serialized admission gate")
    let allowAdmission=DispatchSemaphore(value:0)
    defer{allowAdmission.signal()}
    module.beforeStopAcknowledgementRetryAdmissionForTesting={retryEntered.fulfill();_ = allowAdmission.wait(timeout:.now()+5)}
    module.processJob(jobID)
    wait(for:[retryEntered],timeout:4)

    XCTAssertTrue(module.acknowledgeWorkerStop(jobID:jobID,canceled:false,reason:"Previous run stopped"){_ in
      try store.updateJob(jobID,status:"paused",stage:"queued",clearCurrentPage:true,error:"Previous run stopped")
    })
    try store.updateJob(jobID,status:"queued",clearError:true)
    module.activeJobIDForTesting=jobID
    allowAdmission.signal()
    module.beforeStopAcknowledgementRetryAdmissionForTesting=nil

    let queueDrained=expectation(description:"the stale serialized retry has drained")
    module.drainTranslationWorkForTesting{queueDrained.fulfill()}
    wait(for:[queueDrained],timeout:1)
    XCTAssertEqual(try store.job(jobID)?["status"] as? String,"queued",
      "a stale queued acknowledgement retry must not enter inference for a resumed run of the same job")
    XCTAssertEqual(module.activeJobIDForTesting,jobID,"the stale retry must not release the resumed run's owner")
  }

  func testLocalPlacementValidatorRejectsCoercedOutputPageIndexes() throws {
    let module=PdfTranslatorModule()
    let blocks:[[String:Any]]=[["id":"p0b0","translatedText":"Перевод"]]
    let valid:[[String:Any]]=[["blockId":"p0b0","outputPageIndex":0]]
    XCTAssertNoThrow(try module.validateLocalPlacements(blocks:blocks,outputPageCount:1,placements:valid,issues:[]))
    for malformed:Any in [NSNumber(value:0.9),NSNumber(value:false),"0"] {
      let placement:[[String:Any]]=[["blockId":"p0b0","outputPageIndex":malformed]]
      XCTAssertThrowsError(try module.validateLocalPlacements(blocks:blocks,outputPageCount:1,
        placements:placement,issues:[]),"local output page index accepted malformed value \(malformed)")
    }
  }

  func testStoreSchemaMigrationBackfillsCountersAndPreservesLegacyCheckpoint() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-migration-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let source = directory.appendingPathComponent("source.pdf")
    try Data("legacy migration fixture".utf8).write(to: source)
    let store = try TranslatorStore(directory: directory)
    _ = try store.saveDocument(id: "migration-document", name: "Legacy.pdf", path: source.path, pages: 1, sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: "migration-job", input: ["documentId": "migration-document", "modelId": ModelCatalog.compactId, "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 1)
    let checkpoint = try jsonString([["id": "p0", "sourceText": "Bearing", "translatedText": "Подшипник", "status": "translated"]])
    try store.savePage(jobId: "migration-job", index: 0, status: "completed", blocks: checkpoint)
    try store.setSetting("theme", "dark")
    // Simulate an actual pre-label schema after writing its checkpoint through Store.
    try store.execute("ALTER TABLE jobs DROP COLUMN label_color")
    try store.execute("UPDATE pages SET completed_blocks=0,total_blocks=0 WHERE job_id=?", ["migration-job"])
    try store.execute("UPDATE jobs SET stage_started_at=0 WHERE id=?", ["migration-job"])
    try store.execute("PRAGMA user_version=5")

    let migrated = try TranslatorStore(directory: directory)
    XCTAssertEqual(try migrated.query("PRAGMA user_version").first?["user_version"] as? Int64, 7)
    XCTAssertTrue(try migrated.job("migration-job")?["labelColor"] is NSNull)
    XCTAssertEqual(try migrated.setting("theme", fallback: "system"), "dark")
    XCTAssertEqual(try migrated.appPreferences()["appRating"] as? Int, 0)
    XCTAssertEqual(try migrated.appPreferences()["librarySort"] as? String, "recent")
    XCTAssertEqual(try migrated.pageRows("migration-job").count, 1)
    XCTAssertEqual(try migrated.query("SELECT completed_blocks,total_blocks FROM pages WHERE job_id=?", ["migration-job"]).first?["completed_blocks"] as? Int64, 1)
    XCTAssertEqual(try migrated.query("SELECT total_blocks FROM pages WHERE job_id=?", ["migration-job"]).first?["total_blocks"] as? Int64, 1)
    XCTAssertGreaterThan(try XCTUnwrap(migrated.job("migration-job")?["stageStartedAt"] as? Int64), 0)
  }

  func testStoreResumeSnapshotReopensWithPinnedInputsAndUnfinishedPage() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-resume-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let source = directory.appendingPathComponent("source.pdf")
    try Data("durable resume source".utf8).write(to: source)
    let store = try TranslatorStore(directory: directory)
    let digest = try sha256(at: source)
    _ = try store.saveDocument(id: "resume-document", name: "Resume.pdf", path: source.path, pages: 3, sourceSHA256: digest)
    let model = try ModelCatalog.get(ModelCatalog.compactId)
    _ = try store.addJob(id: "resume-job", input: ["documentId": "resume-document", "modelId": model.id, "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 3)
    try store.savePage(jobId: "resume-job", index: 0, status: "completed", blocks: "[{\"id\":\"p0\",\"translatedText\":\"Сохранено\",\"status\":\"translated\"}]")
    try store.savePage(jobId: "resume-job", index: 1, status: "partial", blocks: "[{\"id\":\"p1\",\"translatedText\":\"Первый блок\",\"status\":\"translated\"},{\"id\":\"p1b\",\"status\":\"pending\"}]")
    try store.updateJob("resume-job", status: "paused", stage: "translating", completed: 1, currentPage: 1)
    try store.setPageProgress(jobId: "resume-job", index: 1, currentBlock: 1, pageBlocks: 2)

    let reopened = try TranslatorStore(directory: directory)
    try reopened.validateJobSnapshot("resume-job", model: model)
    let job = try XCTUnwrap(reopened.job("resume-job"))
    XCTAssertEqual(job["status"] as? String, "paused")
    XCTAssertEqual(job["modelId"] as? String, model.id)
    XCTAssertEqual(job["sourceLanguage"] as? String, "en")
    XCTAssertEqual(job["targetLanguage"] as? String, "ru")
    XCTAssertEqual(job["sourceSHA256"] as? String, digest)
    XCTAssertEqual(job["currentPage"] as? Int64, 1)
    XCTAssertEqual(job["currentBlock"] as? Int64, 1)
    XCTAssertEqual(try reopened.pageRows("resume-job").map { $0["index"] as? Int64 }, [0, 1])
    XCTAssertEqual(try reopened.pageRows("resume-job").map { $0["status"] as? String }, ["completed", "partial"])
    XCTAssertEqual(try reopened.query("SELECT blocks_json FROM pages WHERE job_id=? AND page_index=1", ["resume-job"]).first?["blocks_json"] as? String, "[{\"id\":\"p1\",\"translatedText\":\"Первый блок\",\"status\":\"translated\"},{\"id\":\"p1b\",\"status\":\"pending\"}]")
  }

  func testStorePageCountersReportUnknownUntilAllPagesAreDiscovered() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-counters-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let source = directory.appendingPathComponent("source.pdf")
    try Data("counter fixture".utf8).write(to: source)
    let store = try TranslatorStore(directory: directory)
    _ = try store.saveDocument(id: "counter-document", name: "Counters.pdf", path: source.path, pages: 2, sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: "counter-job", input: ["documentId": "counter-document", "modelId": ModelCatalog.compactId, "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 2)
    let firstPage = try jsonString([["status": "translated"], ["status": "failed"], ["status": "pending"]])
    try store.savePage(jobId: "counter-job", index: 0, status: "partial", blocks: firstPage)
    var job = try XCTUnwrap(store.job("counter-job"))
    XCTAssertEqual(job["completedBlocks"] as? Int64, 2)
    XCTAssertEqual(job["discoveredBlocks"] as? Int64, 3)
    XCTAssertTrue(job["totalBlocks"] is NSNull, "one discovered page must not be presented as a complete document total")

    try store.savePage(jobId: "counter-job", index: 1, status: "running", blocks: "[{\"status\":\"skipped\"}]")
    job = try XCTUnwrap(try TranslatorStore(directory: directory).job("counter-job"))
    XCTAssertEqual(job["completedBlocks"] as? Int64, 3, "translated, failed, and skipped blocks count as durable terminal work")
    XCTAssertEqual(job["discoveredBlocks"] as? Int64, 4)
    XCTAssertEqual(job["totalBlocks"] as? Int64, 4)
  }

  func testStoreDeletingOneSharedJobPreservesSourceSiblingCheckpointAndExports() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-shared-delete-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let documentId = "shared-delete-\(UUID().uuidString)"
    let source = AppPaths.documents.appendingPathComponent(documentId + ".pdf")
    defer {
      try? FileManager.default.removeItem(at: directory)
      try? FileManager.default.removeItem(at: source)
    }
    try FileManager.default.createDirectory(at: AppPaths.documents, withIntermediateDirectories: true)
    try Data("shared source bytes".utf8).write(to: source)
    let store = try TranslatorStore(directory: directory)
    _ = try store.saveDocument(id: documentId, name: "Shared.pdf", path: source.path, pages: 1, sourceSHA256: sha256(at: source))
    let input: [String: Any] = ["documentId": documentId, "modelId": ModelCatalog.compactId, "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false]
    _ = try store.addJob(id: "shared-first", input: input, pageCount: 1)
    _ = try store.addJob(id: "shared-sibling", input: input, pageCount: 1)
    try store.savePage(jobId: "shared-sibling", index: 0, status: "partial", blocks: "[{\"status\":\"translated\"}]")
    try store.saveExport("shared-first:pdf", path: "/user/export.pdf", name: "export.pdf", sha256: "export-hash")

    XCTAssertNil(try store.deleteJobAndReleaseUnsharedDocument("shared-first"))
    XCTAssertNil(try store.job("shared-first"))
    XCTAssertNotNil(try store.job("shared-sibling"))
    XCTAssertEqual(try store.pageRows("shared-sibling").count, 1)
    XCTAssertNotNil(try store.documentPath(documentId))
    XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
    XCTAssertEqual(try store.export("shared-first:pdf")?["sha256"] as? String, "export-hash")

    XCTAssertEqual(try store.deleteJobAndReleaseUnsharedDocument("shared-sibling"), source.path)
    XCTAssertNil(try store.documentPath(documentId))
    XCTAssertTrue(FileManager.default.fileExists(atPath: source.path), "Store returns ownership of the last private source path; caller cleanup remains explicit")
    try FileManager.default.removeItem(at: source)
  }

  func testStoreCanceledJobRejectsColorMutationWithoutLosingCheckpoint() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-canceled-color-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let source = directory.appendingPathComponent("source.pdf")
    try Data("canceled checkpoint fixture".utf8).write(to: source)
    let store = try TranslatorStore(directory: directory)
    _ = try store.saveDocument(id: "canceled-document", name: "Canceled.pdf", path: source.path, pages: 1, sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: "canceled-job", input: ["documentId": "canceled-document", "modelId": ModelCatalog.compactId, "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 1)
    try store.savePage(jobId: "canceled-job", index: 0, status: "partial", blocks: "[{\"status\":\"translated\"}]")
    _ = try store.setJobColor("canceled-job", color: "blue")
    try store.updateJob("canceled-job", status: "canceled")

    XCTAssertThrowsError(try store.setJobColor("canceled-job", color: "red")) {
      XCTAssertEqual(($0 as NSError).code, 46)
    }
    let canceled = try XCTUnwrap(store.job("canceled-job"))
    XCTAssertEqual(canceled["status"] as? String, "canceled")
    XCTAssertEqual(canceled["labelColor"] as? String, "blue", "rejected edits must preserve the prior durable color")
    XCTAssertEqual(try store.pageRows("canceled-job").count, 1, "cancel status does not discard resumable checkpoint data")
  }

  func testStoreModelSnapshotUsesOnlyIsolatedFilesAndSeparatesTransferFromStorage() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("store-model-snapshot-\(UUID().uuidString)", isDirectory: true)
    let modelDirectory = directory.appendingPathComponent("models", isDirectory: true)
    try FileManager.default.createDirectory(at: modelDirectory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = try TranslatorStore(directory: directory)
    let model = try ModelCatalog.get(ModelCatalog.compactId)
    try store.setSelectedModelId(model.id)
    try store.updateModel(model.id, status: "ready", bytes: model.bytes, error: "old diagnostic")
    func snapshot() throws -> [String: Any] {
      let rows = try store.models(totalMemory: 8_589_934_592, availableMemory: 4_294_967_296, availableDisk: 100_000_000_000, systemLowMemory: false, modelDirectory: modelDirectory)
      return try XCTUnwrap(rows.first { $0["id"] as? String == model.id })
    }

    let absent = try snapshot()
    XCTAssertEqual(absent["status"] as? String, "not_downloaded")
    XCTAssertEqual(absent["downloadedBytes"] as? Int64, 0)
    XCTAssertEqual(try store.modelState(model.id)?["status"] as? String, "ready", "snapshot reconciliation must not overwrite downloader-owned durable state")
    XCTAssertEqual(try store.modelState(model.id)?["error"] as? String, "old diagnostic")

    try Data([1, 2, 3, 4]).write(to: modelDirectory.appendingPathComponent(model.id + ".part"))
    try store.updateModel(model.id, status: "ready", bytes: model.bytes)
    let partial = try snapshot()
    XCTAssertEqual(partial["status"] as? String, "paused")
    XCTAssertEqual(partial["downloadedBytes"] as? Int64, 4)
    try store.updateModel(model.id, status: "downloading", bytes: 9)
    let active = try snapshot()
    XCTAssertEqual(active["status"] as? String, "downloading")
    XCTAssertEqual(active["activeTransferBytes"] as? Int64, 9)
    XCTAssertEqual(active["storedBytes"] as? Int64, 4)
    XCTAssertEqual(try store.selectedModelId(), model.id)
  }
  // U050 staged module bridge regressions, integrated into the diagnostic coverage class.
  private func modulePromise(
    _ invoke: (@escaping RCTPromiseResolveBlock, @escaping RCTPromiseRejectBlock) -> Void,
    timeout: TimeInterval = 20
  ) throws -> Any? {
    let done = expectation(description: "module promise completes")
    var value: Any?
    var failure: Error?
    invoke({ result in value = result; done.fulfill() }, { code, message, error in
      failure = error ?? NSError(domain: code ?? "ModuleCasesNext", code: 1,
        userInfo: [NSLocalizedDescriptionKey: message ?? code ?? "Module promise rejected"])
      done.fulfill()
    })
    wait(for: [done], timeout: timeout)
    if let failure { throw failure }
    return value
  }

  private func moduleRejection(
    _ invoke: (@escaping RCTPromiseResolveBlock, @escaping RCTPromiseRejectBlock) -> Void,
    timeout: TimeInterval = 20
  ) -> (String?, String?) {
    let done = expectation(description: "module promise rejects")
    var code: String?
    var message: String?
    invoke({ _ in XCTFail("Expected promise rejection"); done.fulfill() }, { actual, text, _ in
      code = actual
      message = text
      done.fulfill()
    })
    wait(for: [done], timeout: timeout)
    return (code, message)
  }

  private func moduleObject(_ value: Any?) throws -> [String: Any] {
    let string = try XCTUnwrap(value as? String)
    return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(string.utf8)) as? [String: Any])
  }

  func testModuleSettingsPersistEngineAndRatingAtomically() throws {
    let module = PdfTranslatorModule()
    let store = try TranslatorStore(directory: AppPaths.support)
    let priorTheme = try store.setting("theme", fallback: "system")
    let priorLanguage = try store.setting("language", fallback: "system")
    let priorSeen = try store.setting("notificationExplanationShown", fallback: "false")
    let priorModel = try store.selectedModelId()
    let priorEngine = try store.selectedEngine()
    let priorPair = try store.languagePreferences()
    let priorPreferences = try store.appPreferences()
    defer {
      try? store.setSetting("theme", priorTheme)
      try? store.setSetting("language", priorLanguage)
      try? store.setSetting("notificationExplanationShown", priorSeen)
      try? store.setSetting("selected_model_id", priorModel)
      try? store.setSetting("selected_engine", try jsonString(priorEngine))
      try? store.setLanguagePreferences(sourceLanguage: priorPair.sourceLanguage, targetLanguage: priorPair.targetLanguage)
      try? store.setAppPreferences(appRating: priorPreferences["appRating"] as? Int,
        librarySort: priorPreferences["librarySort"] as? String)
    }

    let online = try moduleObject(try modulePromise { resolve, reject in
      module.setSettings("{\"theme\":\"light\",\"language\":\"en\",\"selectedModelId\":\"\(ModelCatalog.balancedId)\",\"selectedEngine\":{\"kind\":\"online\",\"id\":\"mymemory\"},\"appRating\":5,\"librarySort\":\"color\"}", resolver: resolve, rejecter: reject)
    })
    XCTAssertEqual((online["selectedEngine"] as? [String: String])?["id"], "mymemory")
    XCTAssertEqual(online["selectedModelId"] as? String, ModelCatalog.balancedId,
      "choosing Online must preserve the separately selected local model")
    XCTAssertEqual(online["appRating"] as? Int, 5)
    XCTAssertEqual(online["librarySort"] as? String, "color")

    let rejected = moduleRejection { resolve, reject in
      module.setSettings("{\"theme\":\"dark\",\"appRating\":true,\"librarySort\":\"color\"}", resolver: resolve, rejecter: reject)
    }
    XCTAssertEqual(rejected.0, "SETTINGS_SAVE_FAILED")
    let afterFailure = try moduleObject(try modulePromise { resolve, reject in
      module.setSettings("{}", resolver: resolve, rejecter: reject)
    })
    XCTAssertEqual(afterFailure["theme"] as? String, "light",
      "a later invalid setting must not partially commit earlier fields")
    XCTAssertEqual(afterFailure["appRating"] as? Int, 5)

    let local = try moduleObject(try modulePromise { resolve, reject in
      module.setSettings("{\"selectedEngine\":{\"kind\":\"local\",\"id\":\"\(ModelCatalog.compactId)\"},\"appRating\":0}", resolver: resolve, rejecter: reject)
    })
    XCTAssertEqual((local["selectedEngine"] as? [String: String])?["id"], ModelCatalog.compactId)
    XCTAssertEqual(local["appRating"] as? Int, 0)
  }

  func testStartOnlineJobRequiresConsentBeforeCreatingDurableJob() throws {
    let module = PdfTranslatorModule()
    let store = try TranslatorStore(directory: AppPaths.support)
    let marker = "module-next-no-consent-\(UUID().uuidString)"
    let existingJobIds = Set(try store.jobs().compactMap { $0["id"] as? String })
    let input = try jsonString(["documentId": marker,
      "engine": ["kind": "online", "id": "mymemory"],
      "sourceLanguage": "en", "targetLanguage": "ru", "onlineConsent": false])
    let result = moduleRejection { resolve, reject in
      module.startJob(input, resolver: resolve, rejecter: reject)
    }
    XCTAssertEqual(result.0, "JOB_START_FAILED")
    if UIApplication.shared.applicationState == .active {
      XCTAssertTrue(result.1?.contains("Confirm the online translation privacy notice") == true,
        "the foreground request must reject before document lookup when online consent is absent")
    } else {
      XCTAssertTrue(result.1?.contains("Return to the open app") == true,
        "background invocation must be refused without starting work")
    }
    let actualJobIds = Set(try store.jobs().compactMap { $0["id"] as? String })
    XCTAssertEqual(actualJobIds, existingJobIds,
      "a rejected start must not add a durable job or begin network translation")
  }

  func testRunningPdfPageCanPreviewBeforeFinalExportAndKeepsCheckpointOnBadPreviewRequest() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let module = PdfTranslatorModule()
    let id = "module-next-preview-\(UUID().uuidString)"
    let jobID = id + "-job"
    let source = AppPaths.documents.appendingPathComponent(id + ".pdf")
    let preview = AppPaths.thumbnails.appendingPathComponent("translated_\(jobID)_0_128.png")
    let pageDirectory = AppPaths.pages.appendingPathComponent(jobID, isDirectory: true)
    defer {
      try? FileManager.default.removeItem(at: preview)
      try? FileManager.default.removeItem(at: pageDirectory)
      try? store.deleteExport(jobID)
      try? store.deleteJob(jobID)
      try? store.deleteDocument(id)
      try? FileManager.default.removeItem(at: source)
    }

    try FileManager.default.createDirectory(at: AppPaths.documents, withIntermediateDirectories: true)
    try UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 400, height: 300)).writePDF(to: source) { context in
      context.beginPage()
      ("Original warning" as NSString).draw(at: CGPoint(x: 24, y: 24), withAttributes: [.font: UIFont.systemFont(ofSize: 16)])
    }
    _ = try store.saveDocument(id: id, name: "Preview in progress.pdf", path: source.path,
      pages: 1, sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: jobID, input: ["documentId": id, "modelId": ModelCatalog.compactId,
      "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 1)
    try store.updateJob(jobID, status: "running", stage: "translating")
    let extracted = try decoded(TranslateCoreBridge.readPage(source.path, index: 0))
    var sourceBlocks = try XCTUnwrap(extracted["blocks"] as? [[String: Any]])
    let ownedBlock = try XCTUnwrap(sourceBlocks.firstIndex {
      $0["source"] as? String == "native"
        && (($0["sourceText"] as? String) ?? "").contains("Original warning")
        && !((($0["sourceRects"] as? [[String: Any]]) ?? []).isEmpty)
    }, "preview checkpoint must use exact source ownership from the native PDFium reader")
    sourceBlocks[ownedBlock]["translatedText"] = "Сохраненное предупреждение"
    sourceBlocks[ownedBlock]["status"] = "translated"
    try store.savePage(jobId: jobID, index: 0, status: "running", blocks: try jsonString(sourceBlocks))
    XCTAssertNil(try store.export(jobID), "the fixture must exercise the saved-page preview path, before final export")

    let uri = try XCTUnwrap(try modulePromise { resolve, reject in
      module.renderTranslatedPage(jobID, pageIndex: 0, width: 128, resolver: resolve, rejecter: reject)
    } as? String)
    let imageURL = try XCTUnwrap(URL(string: uri))
    XCTAssertTrue(FileManager.default.fileExists(atPath: imageURL.path))
    XCTAssertEqual(try Data(contentsOf: imageURL).prefix(8), Data([137, 80, 78, 71, 13, 10, 26, 10]))
    XCTAssertEqual(try store.job(jobID)?["status"] as? String, "running")
    XCTAssertEqual(try store.pageRows(jobID).count, 1)
    XCTAssertNil(try store.export(jobID), "previewing an in-progress page must not masquerade as final export")

    let badRequest = moduleRejection { resolve, reject in
      module.renderTranslatedPage(jobID, pageIndex: 1, width: 128, resolver: resolve, rejecter: reject)
    }
    XCTAssertEqual(badRequest.0, "TRANSLATED_THUMBNAIL_FAILED")
    XCTAssertEqual(try store.job(jobID)?["status"] as? String, "running")
    XCTAssertEqual(try store.pageRows(jobID).count, 1, "a preview error must not discard the durable page checkpoint")
  }

  func testModuleDeleteJobKeepsSharedSourceSiblingCheckpointAndExport() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let module = PdfTranslatorModule()
    let documentID = "module-more-shared-\(UUID().uuidString)"
    let firstID = documentID + "-first"
    let siblingID = documentID + "-sibling"
    let source = AppPaths.documents.appendingPathComponent(documentID + ".pdf")
    let firstExport = AppPaths.exports.appendingPathComponent(firstID + ".pdf")
    let siblingExport = AppPaths.exports.appendingPathComponent(siblingID + ".pdf")
    let sourceThumbnail = AppPaths.thumbnails.appendingPathComponent(documentID + "_0_128.png")
    let firstThumbnail = AppPaths.thumbnails.appendingPathComponent("translated_\(firstID)_0_128.png")
    let siblingThumbnail = AppPaths.thumbnails.appendingPathComponent("translated_\(siblingID)_0_128.png")
    let firstPages = AppPaths.pages.appendingPathComponent(firstID, isDirectory: true)
    let siblingPages = AppPaths.pages.appendingPathComponent(siblingID, isDirectory: true)
    defer {
      for url in [source, firstExport, siblingExport, sourceThumbnail, firstThumbnail, siblingThumbnail, firstPages, siblingPages] {
        try? FileManager.default.removeItem(at: url)
      }
      try? store.deleteExport(firstID)
      try? store.deleteExport(siblingID)
      try? store.deleteJob(firstID)
      try? store.deleteJob(siblingID)
      try? store.deleteDocument(documentID)
    }

    try FileManager.default.createDirectory(at: AppPaths.documents, withIntermediateDirectories: true)
    try FileManager.default.createDirectory(at: AppPaths.exports, withIntermediateDirectories: true)
    try FileManager.default.createDirectory(at: AppPaths.thumbnails, withIntermediateDirectories: true)
    try FileManager.default.createDirectory(at: firstPages, withIntermediateDirectories: true)
    try FileManager.default.createDirectory(at: siblingPages, withIntermediateDirectories: true)
    try Data("private shared source fixture".utf8).write(to: source)
    try Data("first export".utf8).write(to: firstExport)
    try Data("sibling export".utf8).write(to: siblingExport)
    try Data("source thumbnail".utf8).write(to: sourceThumbnail)
    try Data("first thumbnail".utf8).write(to: firstThumbnail)
    try Data("sibling thumbnail".utf8).write(to: siblingThumbnail)
    try Data("first page shard".utf8).write(to: firstPages.appendingPathComponent("page.pdf"))
    try Data("sibling page shard".utf8).write(to: siblingPages.appendingPathComponent("page.pdf"))

    _ = try store.saveDocument(id: documentID, name: "Shared source.pdf", path: source.path,
      pages: 1, sourceSHA256: sha256(at: source))
    let firstInput: [String: Any] = ["documentId": documentID, "modelId": ModelCatalog.compactId,
      "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false]
    let siblingInput: [String: Any] = ["documentId": documentID, "modelId": ModelCatalog.balancedId,
      "sourceLanguage": "fr", "targetLanguage": "de", "useGlossary": false]
    _ = try store.addJob(id: firstID, input: firstInput, pageCount: 1)
    _ = try store.addJob(id: siblingID, input: siblingInput, pageCount: 1)
    try store.updateJob(firstID, status: "completed", stage: "complete", completed: 1)
    try store.updateJob(siblingID, status: "partial", stage: "complete", completed: 1)
    try store.savePage(jobId: firstID, index: 0, status: "completed", blocks: "[{\"status\":\"translated\"}]")
    try store.savePage(jobId: siblingID, index: 0, status: "partial", blocks: "[{\"status\":\"translated\"}]")
    try store.saveExport(firstID, path: firstExport.path, name: firstExport.lastPathComponent, sha256: sha256(at: firstExport))
    try store.saveExport(siblingID, path: siblingExport.path, name: siblingExport.lastPathComponent, sha256: sha256(at: siblingExport))

    _ = try modulePromise { resolve, reject in module.deleteJob(firstID, resolver: resolve, rejecter: reject) }
    XCTAssertNil(try store.job(firstID))
    XCTAssertTrue(FileManager.default.fileExists(atPath: source.path), "deleting one translation must retain a source still used by its sibling")
    XCTAssertNotNil(try store.documentPath(documentID))
    XCTAssertFalse(FileManager.default.fileExists(atPath: firstExport.path))
    XCTAssertNil(try store.export(firstID))
    XCTAssertTrue(FileManager.default.fileExists(atPath: siblingExport.path))
    XCTAssertNotNil(try store.export(siblingID))
    XCTAssertFalse(FileManager.default.fileExists(atPath: firstPages.path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: siblingPages.path))
    XCTAssertFalse(FileManager.default.fileExists(atPath: firstThumbnail.path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: siblingThumbnail.path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: sourceThumbnail.path), "the shared source preview remains while a sibling job exists")
    XCTAssertEqual(try store.pageRows(siblingID).count, 1)

    _ = try modulePromise { resolve, reject in module.deleteJob(siblingID, resolver: resolve, rejecter: reject) }
    XCTAssertNil(try store.job(siblingID))
    XCTAssertNil(try store.documentPath(documentID))
    XCTAssertFalse(FileManager.default.fileExists(atPath: source.path), "the last private job releases and removes its shared source")
    XCTAssertFalse(FileManager.default.fileExists(atPath: sourceThumbnail.path))
    XCTAssertFalse(FileManager.default.fileExists(atPath: siblingExport.path))
    XCTAssertFalse(FileManager.default.fileExists(atPath: siblingPages.path))
  }

  func testActivitySnapshotUsesDurablePausedProgressWithoutVolatileInferenceFields() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let module = PdfTranslatorModule()
    let documentID = "module-more-activity-\(UUID().uuidString)"
    let jobID = documentID + "-job"
    let source = AppPaths.documents.appendingPathComponent(documentID + ".pdf")
    defer {
      try? store.deleteJob(jobID)
      try? store.deleteDocument(documentID)
      try? FileManager.default.removeItem(at: source)
    }

    try FileManager.default.createDirectory(at: AppPaths.documents, withIntermediateDirectories: true)
    try Data("activity snapshot source".utf8).write(to: source)
    _ = try store.saveDocument(id: documentID, name: "Paused activity.pdf", path: source.path,
      pages: 2, sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: jobID, input: ["documentId": documentID, "modelId": ModelCatalog.compactId,
      "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 2)
    try store.savePage(jobId: jobID, index: 0, status: "running",
      blocks: "[{\"sourceText\":\"private body\",\"translatedText\":\"saved\",\"status\":\"translated\"},{\"status\":\"pending\"}]")
    try store.updateJob(jobID, status: "paused", stage: "translating", completed: 0, currentPage: 0)
    try store.setPageProgress(jobId: jobID, index: 0, currentBlock: 1, pageBlocks: 2)

    let snapshot = try moduleObject(try modulePromise { resolve, reject in module.getActivitySnapshot(resolve, rejecter: reject) })
    let jobs = try XCTUnwrap(snapshot["jobs"] as? [[String: Any]])
    let job = try XCTUnwrap(jobs.first { $0["id"] as? String == jobID })
    XCTAssertEqual(job["status"] as? String, "paused")
    XCTAssertEqual(job["currentBlock"] as? Int64, 1)
    XCTAssertEqual(job["pageBlocks"] as? Int64, 2)
    XCTAssertEqual(job["completedBlocks"] as? Int64, 1)
    XCTAssertEqual(job["discoveredBlocks"] as? Int64, 2)
    XCTAssertEqual(job["workerStopping"] as? Bool, false)
    XCTAssertNil(job["processedTokens"], "paused jobs must not inherit stale process-wide inference counters")
    XCTAssertNil(job["generatedTokens"])
    XCTAssertNil(job["blocks"])
    XCTAssertNil(job["pages"])
  }

  func testGetJobKeepsSavedModelAndLanguagesAfterCurrentPreferencesChange() throws {
    let store = try TranslatorStore(directory: AppPaths.support)
    let module = PdfTranslatorModule()
    let documentID = "module-more-pinned-\(UUID().uuidString)"
    let jobID = documentID + "-job"
    let source = AppPaths.documents.appendingPathComponent(documentID + ".pdf")
    let previousModel = try store.selectedModelId()
    let previousEngine = try store.selectedEngine()
    let previousPair = try store.languagePreferences()
    defer {
      try? store.setSetting("selected_model_id", previousModel)
      try? store.setSetting("selected_engine", try jsonString(previousEngine))
      try? store.setLanguagePreferences(sourceLanguage: previousPair.sourceLanguage, targetLanguage: previousPair.targetLanguage)
      try? store.deleteJob(jobID)
      try? store.deleteDocument(documentID)
      try? FileManager.default.removeItem(at: source)
    }

    try FileManager.default.createDirectory(at: AppPaths.documents, withIntermediateDirectories: true)
    try Data("immutable job snapshot source".utf8).write(to: source)
    _ = try store.saveDocument(id: documentID, name: "Pinned inputs.pdf", path: source.path,
      pages: 1, sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: jobID, input: ["documentId": documentID, "modelId": ModelCatalog.compactId,
      "sourceLanguage": "en", "targetLanguage": "ru", "useGlossary": false], pageCount: 1)
    try store.savePage(jobId: jobID, index: 0, status: "partial",
      blocks: "[{\"sourceText\":\"Pinned source\",\"translatedText\":\"Закрепленный перевод\",\"status\":\"translated\"}]")
    try store.updateJob(jobID, status: "paused", stage: "translating")

    _ = try moduleObject(try modulePromise { resolve, reject in
      module.setSettings("{\"selectedModelId\":\"\(ModelCatalog.balancedId)\",\"selectedEngine\":{\"kind\":\"local\",\"id\":\"\(ModelCatalog.balancedId)\"},\"sourceLanguage\":\"fr\",\"targetLanguage\":\"de\"}", resolver: resolve, rejecter: reject)
    })
    let response = try moduleObject(try modulePromise { resolve, reject in
      module.getJob(jobID, pageIndex: NSNumber(value: -1), resolver: resolve, rejecter: reject)
    })
    let job = try XCTUnwrap(response["job"] as? [String: Any])
    XCTAssertEqual(job["modelId"] as? String, ModelCatalog.compactId)
    XCTAssertEqual(job["sourceLanguage"] as? String, "en")
    XCTAssertEqual(job["targetLanguage"] as? String, "ru")
    XCTAssertEqual(job["status"] as? String, "paused")
    XCTAssertEqual(try store.pageRows(jobID).map { $0["index"] as? Int64 }, [0])
  }
  // U050 staged Store regressions, integrated into the diagnostic coverage class.
  func testStoreAppPreferenceWritesValidateBothFieldsBeforeCommittingEither() throws {
    let directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("store-more-preferences-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = try TranslatorStore(directory: directory)

    XCTAssertEqual(try store.setAppPreferences(appRating: 5, librarySort: "color")["appRating"] as? Int, 5)
    XCTAssertEqual(try store.appPreferences()["librarySort"] as? String, "color")
    XCTAssertThrowsError(try store.setAppPreferences(appRating: 6, librarySort: "recent")) {
      XCTAssertEqual(($0 as NSError).code, 42)
    }
    XCTAssertThrowsError(try store.setAppPreferences(appRating: 0, librarySort: "alphabetical")) {
      XCTAssertEqual(($0 as NSError).code, 43)
    }
    let saved = try TranslatorStore(directory: directory).appPreferences()
    XCTAssertEqual(saved["appRating"] as? Int, 5, "invalid rating must not partially write the requested sort")
    XCTAssertEqual(saved["librarySort"] as? String, "color", "invalid sort must not partially write the requested rating")
  }

  func testStoreSelectedModelResolutionPreservesSavedChoiceThenPersistsInstalledAndRecommendedFallbacks() throws {
    let directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("store-more-model-choice-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = try TranslatorStore(directory: directory)
    try store.setSelectedModelId(ModelCatalog.balancedId)

    XCTAssertEqual(try store.resolveSelectedModelId(recommended: ModelCatalog.compactId,
      installedModelIds: [ModelCatalog.qualityId]), ModelCatalog.balancedId,
      "a valid saved choice must survive a changed device recommendation")

    try store.setSetting("selected_model_id", "removed-model")
    XCTAssertEqual(try store.resolveSelectedModelId(recommended: ModelCatalog.compactId,
      installedModelIds: [ModelCatalog.compactId, ModelCatalog.qualityId]), ModelCatalog.qualityId,
      "the highest-priority installed supported choice is the fallback")
    XCTAssertEqual(try TranslatorStore(directory: directory).selectedModelId(), ModelCatalog.qualityId)

    try store.setSetting("selected_model_id", "")
    XCTAssertEqual(try store.resolveSelectedModelId(recommended: "removed-recommendation",
      installedModelIds: []), ModelCatalog.recommendedId)
    XCTAssertEqual(try store.selectedModelId(), ModelCatalog.recommendedId)
  }

  func testStoreLanguagePreferencesRepairInvalidPersistedPairAndRejectInvalidWritesAtomically() throws {
    let directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("store-more-language-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = try TranslatorStore(directory: directory)
    try store.setLanguagePreferences(sourceLanguage: "fr", targetLanguage: "de")
    try store.setSetting("source_language", "xx")
    try store.setSetting("target_language", "ru")

    let repaired = try TranslatorStore(directory: directory).languagePreferences()
    XCTAssertEqual(repaired.sourceLanguage, "en")
    XCTAssertEqual(repaired.targetLanguage, "ru")
    XCTAssertEqual(try store.setting("source_language", fallback: ""), "en")
    XCTAssertEqual(try store.setting("target_language", fallback: ""), "ru")

    for pair in [("xx", "ru", 30), ("en", "en", 31)] {
      XCTAssertThrowsError(try store.setLanguagePreferences(sourceLanguage: pair.0, targetLanguage: pair.1)) {
        XCTAssertEqual(($0 as NSError).code, pair.2)
      }
      let unchanged = try store.languagePreferences()
      XCTAssertEqual(unchanged.sourceLanguage, "en")
      XCTAssertEqual(unchanged.targetLanguage, "ru")
    }
  }

  func testStoreJobPinsPairScopedGlossaryAndOnlineJobNeverCapturesGlossaryText() throws {
    let directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("store-more-glossary-snapshot-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let source = directory.appendingPathComponent("source.pdf")
    try Data("glossary snapshot source".utf8).write(to: source)
    defer { try? FileManager.default.removeItem(at: source) }
    let store = try TranslatorStore(directory: directory)
    _ = try store.saveDocument(id: "glossary-document", name: "Glossary.pdf", path: source.path,
      pages: 1, sourceSHA256: sha256(at: source))
    _ = try store.saveGlossary("glossary-term", source: " bearing ", target: " подшипник ",
      sourceLanguage: "en", targetLanguage: "ru")

    _ = try store.addJob(id: "local-glossary-job", input: ["documentId": "glossary-document",
      "modelId": ModelCatalog.compactId, "sourceLanguage": "en", "targetLanguage": "ru",
      "glossaryId": "glossary-term"], pageCount: 1)
    XCTAssertEqual(try store.jobGlossaryText("local-glossary-job"), "bearing translates to подшипник")
    _ = try store.saveGlossary("glossary-term", source: "bearing", target: "lager",
      sourceLanguage: "en", targetLanguage: "ru")
    XCTAssertEqual(try store.jobGlossaryText("local-glossary-job"), "bearing translates to подшипник",
      "later glossary edits must not rewrite an existing job's immutable prompt snapshot")

    _ = try store.addJob(id: "online-glossary-job", input: ["documentId": "glossary-document",
      "engine": ["kind": "online", "id": "mymemory"], "onlineConsent": true,
      "sourceLanguage": "en", "targetLanguage": "ru", "glossaryId": "glossary-term"], pageCount: 1)
    XCTAssertEqual(try store.jobGlossaryText("online-glossary-job"), "",
      "online jobs must not persist local glossary text")
  }

  func testStoreGlossaryIncompatibleModelRejectsSnapshotButAllowsExplicitOptOut() throws {
    let directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("store-more-glossary-capability-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let source = directory.appendingPathComponent("source.pdf")
    try Data("unsupported glossary fixture".utf8).write(to: source)
    defer { try? FileManager.default.removeItem(at: source) }
    let store = try TranslatorStore(directory: directory)
    _ = try store.saveDocument(id: "glossary-capability-document", name: "Glossary.pdf",
      path: source.path, pages: 1, sourceSHA256: sha256(at: source))
    _ = try store.saveGlossary("incompatible-term", source: "valve", target: "клапан",
      sourceLanguage: "en", targetLanguage: "ru")
    let input: [String: Any] = ["documentId": "glossary-capability-document",
      "modelId": ModelCatalog.balancedId, "sourceLanguage": "en", "targetLanguage": "ru",
      "glossaryId": "incompatible-term"]

    XCTAssertThrowsError(try store.addJob(id: "incompatible-glossary-job", input: input, pageCount: 1)) {
      XCTAssertEqual(($0 as NSError).code, 11)
    }
    XCTAssertNil(try store.job("incompatible-glossary-job"))
    var optedOut = input
    optedOut["useGlossary"] = false
    _ = try store.addJob(id: "glossary-opt-out-job", input: optedOut, pageCount: 1)
    XCTAssertEqual(try store.jobGlossaryText("glossary-opt-out-job"), "")
  }

  func testStoreLegacySnapshotMigrationDoesNotInventMissingSourceAndKeepsCheckpoint() throws {
    let directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("store-more-legacy-missing-source-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let source = directory.appendingPathComponent("source.pdf")
    try Data("source removed after an older durable checkpoint".utf8).write(to: source)
    let store = try TranslatorStore(directory: directory)
    _ = try store.saveDocument(id: "legacy-missing-document", name: "Legacy.pdf", path: source.path,
      pages: 1, sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: "legacy-missing-job", input: ["documentId": "legacy-missing-document",
      "modelId": ModelCatalog.compactId, "sourceLanguage": "en", "targetLanguage": "ru"], pageCount: 1)
    try store.savePage(jobId: "legacy-missing-job", index: 0, status: "partial",
      blocks: "[{\"sourceText\":\"saved\",\"translatedText\":\"сохранено\",\"status\":\"translated\"}]")
    try store.execute("UPDATE documents SET source_sha256='' WHERE id=?", ["legacy-missing-document"])
    try store.execute("UPDATE jobs SET pipeline_version=3,model_revision='',source_sha256='',glossary_snapshot='' WHERE id=?",
      ["legacy-missing-job"])
    try FileManager.default.removeItem(at: source)

    let reopened = try TranslatorStore(directory: directory)
    XCTAssertEqual(try reopened.documentSHA256("legacy-missing-document"), "",
      "migration must not fabricate a source digest when the source file is unavailable")
    XCTAssertEqual(try reopened.job("legacy-missing-job")?["modelRevision"] as? String,
      try ModelCatalog.get(ModelCatalog.compactId).revision)
    XCTAssertEqual(try reopened.job("legacy-missing-job")?["sourceSHA256"] as? String, "")
    XCTAssertEqual(try reopened.jobGlossaryText("legacy-missing-job"), "")
    XCTAssertEqual(try reopened.pageRows("legacy-missing-job").count, 1,
      "snapshot migration must retain the checkpoint that existed before the source went missing")
  }

  func testStoreSchemaSevenMigrationRecountsMalformedAndValidLegacyPageRows() throws {
    let directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("store-more-counter-migration-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let source = directory.appendingPathComponent("source.pdf")
    try Data("legacy counter fixture".utf8).write(to: source)
    defer { try? FileManager.default.removeItem(at: source) }
    do {
      let store = try TranslatorStore(directory: directory)
      _ = try store.saveDocument(id: "counter-migration-document", name: "Counter.pdf", path: source.path,
        pages: 2, sourceSHA256: sha256(at: source))
      _ = try store.addJob(id: "counter-migration-job", input: ["documentId": "counter-migration-document",
        "modelId": ModelCatalog.compactId, "sourceLanguage": "en", "targetLanguage": "ru"], pageCount: 2)
      try store.savePage(jobId: "counter-migration-job", index: 0, status: "partial",
        blocks: "[{\"status\":\"translated\"},{\"status\":\"pending\"}]")
      try store.savePage(jobId: "counter-migration-job", index: 1, status: "partial",
        blocks: "[{\"status\":\"failed\"}]")
      try store.execute("UPDATE pages SET blocks_json='not-json',completed_blocks=99,total_blocks=99 WHERE job_id=? AND page_index=1",
        ["counter-migration-job"])
      try store.execute("UPDATE jobs SET stage_started_at=0 WHERE id=?", ["counter-migration-job"])
      try store.execute("PRAGMA user_version=6")
    }

    let migrated = try TranslatorStore(directory: directory)
    let rows = try migrated.query("SELECT page_index,completed_blocks,total_blocks FROM pages WHERE job_id=? ORDER BY page_index",
      ["counter-migration-job"])
    XCTAssertEqual(rows[0]["completed_blocks"] as? Int64, 1)
    XCTAssertEqual(rows[0]["total_blocks"] as? Int64, 2)
    XCTAssertEqual(rows[1]["completed_blocks"] as? Int64, 0,
      "malformed legacy page payloads must not keep stale counters after migration")
    XCTAssertEqual(rows[1]["total_blocks"] as? Int64, 0)
    XCTAssertGreaterThan(try XCTUnwrap(migrated.job("counter-migration-job")?["stageStartedAt"] as? Int64), 0)
    XCTAssertEqual(try migrated.query("PRAGMA user_version").first?["user_version"] as? Int64, 7)
  }

  func testStoreUntranslatedDeleteRejectsMissingAndHistoricallyTranslatedDocuments() throws {
    let directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("store-more-delete-guards-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    try FileManager.default.createDirectory(at: AppPaths.documents, withIntermediateDirectories: true)
    let id = "store-more-delete-\(UUID().uuidString)"
    let source = AppPaths.documents.appendingPathComponent(id + ".pdf")
    try Data("private source with translation history".utf8).write(to: source)
    defer { try? FileManager.default.removeItem(at: source) }
    let store = try TranslatorStore(directory: directory)
    _ = try store.saveDocument(id: id, name: "History.pdf", path: source.path, pages: 1,
      sourceSHA256: sha256(at: source))
    _ = try store.addJob(id: id + "-job", input: ["documentId": id,
      "modelId": ModelCatalog.compactId, "sourceLanguage": "en", "targetLanguage": "ru"], pageCount: 1)
    try store.savePage(jobId: id + "-job", index: 0, status: "partial",
      blocks: "[{\"status\":\"translated\"}]")

    XCTAssertThrowsError(try store.deleteUntranslatedDocument("missing-import")) {
      XCTAssertEqual(($0 as NSError).code, 47)
    }
    XCTAssertThrowsError(try store.deleteUntranslatedDocument(id)) {
      XCTAssertEqual(($0 as NSError).code, 48)
    }
    XCTAssertEqual(try store.documentPath(id), source.path)
    XCTAssertEqual(try store.pageRows(id + "-job").count, 1)
    XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
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

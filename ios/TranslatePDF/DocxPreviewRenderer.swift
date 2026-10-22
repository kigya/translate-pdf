import Foundation
import WebKit
import UIKit

private final class FolivelaPrintPageRenderer: UIPrintPageRenderer {
  override var paperRect: CGRect { CGRect(x: 0, y: 0, width: 612, height: 792) }
  override var printableRect: CGRect { CGRect(x: 36, y: 36, width: 540, height: 720) }
}

/// Renders a DOCX only through the three bundled preview assets and one private local file.
/// All other schemes and paths are denied by the handler; the WebView has no network access.
final class DocxPreviewRenderer: NSObject, WKURLSchemeHandler, WKScriptMessageHandler, WKNavigationDelegate {
  enum Failure: LocalizedError, Equatable { case assets, source, web(String), pdf
    var errorDescription: String? { switch self { case .assets: return "The offline Word preview components are unavailable."; case .source: return "The Word document could not be opened for preview."; case .web(let message): return message; case .pdf: return "The Word preview could not be printed as a PDF." } }
  }
  private let source: URL
  private let completion: (Result<URL, Error>) -> Void
  private var webView: WKWebView?
  private var finished = false
  private let cacheURL: URL
  private static var retained = [ObjectIdentifier: DocxPreviewRenderer]()
  private static let retainedLock = NSLock()

  private init(source: URL, cacheURL: URL, completion: @escaping (Result<URL, Error>) -> Void) {
    self.source = source; self.cacheURL = cacheURL; self.completion = completion
  }

  static func render(source: URL, cacheURL: URL, completion: @escaping (Result<URL, Error>) -> Void) {
    DispatchQueue.main.async {
      let renderer = DocxPreviewRenderer(source: source, cacheURL: cacheURL, completion: completion)
      retainedLock.lock(); retained[ObjectIdentifier(renderer)] = renderer; retainedLock.unlock()
      renderer.start()
    }
  }

  private func start() {
    guard FileManager.default.fileExists(atPath: source.path) else { finish(.failure(Failure.source)); return }
    guard let bundleURL = Bundle.main.url(forResource: "FolivelaDocxPreview", withExtension: "bundle"),
          Bundle(url: bundleURL)?.url(forResource: "index", withExtension: "html") != nil else { finish(.failure(Failure.assets)); return }
    let config = WKWebViewConfiguration()
    config.setURLSchemeHandler(self, forURLScheme: "folivela-docx")
    let controller = WKUserContentController(); controller.add(self, name: "folivelaPreview")
    config.userContentController = controller
    config.preferences.javaScriptCanOpenWindowsAutomatically = false
    config.websiteDataStore = .nonPersistent()
    let view = WKWebView(frame: CGRect(x: 0, y: 0, width: 612, height: 792), configuration: config)
    view.navigationDelegate = self; view.isHidden = true; view.scrollView.isScrollEnabled = false
    if let window=UIApplication.shared.connectedScenes.compactMap({$0 as? UIWindowScene}).first(where:{$0.activationState == .foregroundActive})?.windows.first(where:{$0.isKeyWindow}) {
      window.addSubview(view); view.frame=window.bounds; view.autoresizingMask=[.flexibleWidth,.flexibleHeight]
    }
    webView = view
    guard let url = URL(string: "folivela-docx://preview/index.html") else { finish(.failure(Failure.assets)); return }
    view.load(URLRequest(url: url))
    DispatchQueue.main.asyncAfter(deadline: .now() + 90) { [weak self] in self?.finish(.failure(Failure.web("The Word preview took too long to render."))) }
  }

  func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
    // The bundled HTML loads the local package itself and sends ready/error after layout.
  }

  func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
    guard let body = message.body as? [String: Any], let type = body["type"] as? String else { finish(.failure(Failure.web("The Word preview could not be rendered."))); return }
    if type == "ready" { printPDF() }
    else if type == "error" { finish(.failure(Failure.web("The Word preview could not be rendered."))) }
  }

  private func printPDF() {
    guard let webView else { finish(.failure(Failure.pdf)); return }
    let formatter = webView.viewPrintFormatter()
    let renderer = FolivelaPrintPageRenderer(); renderer.addPrintFormatter(formatter, startingAtPageAt: 0)
    let paper = renderer.paperRect
    let count = renderer.numberOfPages
    guard count > 0, count <= 10_000 else { finish(.failure(Failure.pdf)); return }
    let data = NSMutableData()
    UIGraphicsBeginPDFContextToData(data, paper, nil)
    for page in 0..<count {
      UIGraphicsBeginPDFPage()
      renderer.drawPage(at: page, in: UIGraphicsGetPDFContextBounds())
    }
    UIGraphicsEndPDFContext()
    guard data.length >= 5, Data(referencing: data).prefix(5) == Data("%PDF-".utf8) else { finish(.failure(Failure.pdf)); return }
    do { try FileManager.default.createDirectory(at: cacheURL.deletingLastPathComponent(), withIntermediateDirectories: true); try data.write(to: cacheURL, options: .atomic); finish(.success(cacheURL)) }
    catch { finish(.failure(Failure.pdf)) }
  }

  func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
    decisionHandler(navigationAction.request.url?.scheme == "folivela-docx" ? .allow : .cancel)
  }

  func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
    finish(.failure(Failure.web("The Word preview stopped because the document exceeded available rendering resources. The Word translation can still be saved.")))
  }
  func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { finish(.failure(Failure.web("The Word preview could not be loaded."))) }
  func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { finish(.failure(Failure.web("The Word preview could not be loaded."))) }

  func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
    guard let url = urlSchemeTask.request.url, url.scheme == "folivela-docx", url.host == "preview" else { deny(urlSchemeTask); return }
    let path = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    let data: Data, mime: String
    if path == "document.docx" {
      guard let values = try? source.resourceValues(forKeys: [.fileSizeKey]), let size = values.fileSize, size > 0, size <= 50 * 1024 * 1024, let bytes = try? Data(contentsOf: source, options: .mappedIfSafe) else { deny(urlSchemeTask); return }
      data = bytes; mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    } else {
      guard ["index.html", "docx-preview.min.js", "jszip.min.js"].contains(path),
            let bundleURL = Bundle.main.url(forResource: "FolivelaDocxPreview", withExtension: "bundle"),
            let bundle = Bundle(url: bundleURL), let file = bundle.url(forResource: path == "index.html" ? "index" : String(path.dropLast(3)), withExtension: path == "index.html" ? "html" : "js"),
            let bytes = try? Data(contentsOf: file), bytes.count <= 10 * 1024 * 1024 else { deny(urlSchemeTask); return }
      data = bytes; mime = path.hasSuffix(".html") ? "text/html" : "text/javascript"
    }
    let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "\(mime); charset=utf-8", "Access-Control-Allow-Origin": "folivela-docx://preview"]) ?? URLResponse(url: url, mimeType: mime, expectedContentLength: data.count, textEncodingName: "utf-8")
    urlSchemeTask.didReceive(response); urlSchemeTask.didReceive(data); urlSchemeTask.didFinish()
  }

  func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}
  private func deny(_ task: WKURLSchemeTask) { task.didFailWithError(Failure.assets) }
  private func finish(_ result: Result<URL, Error>) {
    guard !finished else { return }; finished = true
    webView?.stopLoading(); webView?.configuration.userContentController.removeScriptMessageHandler(forName: "folivelaPreview"); webView?.removeFromSuperview(); webView = nil
    Self.retainedLock.lock();Self.retained.removeValue(forKey:ObjectIdentifier(self));Self.retainedLock.unlock()
    completion(result)
  }
}

import Foundation

/// Minimal, consent-gated MyMemory client. Request text and URLs are never logged.
final class MyMemoryTranslator: NSObject, URLSessionTaskDelegate {
  private let lock = NSLock()
  private var activeTask: URLSessionDataTask?
  private let protocolClasses: [AnyClass]?
  private lazy var session: URLSession = {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.httpCookieStorage = nil
    configuration.urlCache = nil
    configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
    configuration.protocolClasses = protocolClasses
    return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
  }()

  init(protocolClasses: [AnyClass]? = nil) { self.protocolClasses=protocolClasses;super.init() }

  func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                  newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
    guard request.url?.scheme == "https", request.url?.host == "api.mymemory.translated.net" else { completionHandler(nil); return }
    completionHandler(request)
  }

  enum Failure: LocalizedError, Equatable {
    case unavailable
    case quota
    case invalidResponse
    case canceled
    case oversizedCharacter

    var errorDescription: String? {
      switch self {
      case .unavailable: return "MyMemory is temporarily unavailable or there is no internet connection. The job is paused; resume it when online translation can continue."
      case .quota: return "MyMemory's daily free quota has been reached. The job is paused with saved segments; resume it after the quota resets."
      case .invalidResponse: return "MyMemory returned an invalid translation response. The job is paused and its saved segments are ready to resume."
      case .canceled: return "Online translation was paused or canceled. Completed request fragments are saved."
      case .oversizedCharacter: return "A single text character exceeds MyMemory's 500-byte request limit and cannot be sent safely."
      }
    }
  }

  func cancel() {
    lock.lock(); let task = activeTask; activeTask = nil; lock.unlock()
    task?.cancel()
  }

  func translate(_ text: String, source: String, target: String) throws -> String {
    guard !text.isEmpty else { return "" }
    let leading = String(text.prefix(while: \.isWhitespace))
    let remainder = String(text.dropFirst(leading.count))
    let trailing = String(remainder.reversed().prefix(while: \.isWhitespace).reversed())
    let queryText = String(remainder.dropLast(trailing.count))
    guard !queryText.isEmpty else { return text }
    var components = URLComponents(string: "https://api.mymemory.translated.net/get")!
    components.queryItems = [URLQueryItem(name: "q", value: queryText), URLQueryItem(name: "langpair", value: "\(providerCode(source))|\(providerCode(target))")]
    guard let url = components.url else { throw Failure.invalidResponse }
    var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 25)
    request.httpMethod = "GET"
    request.setValue("application/json", forHTTPHeaderField: "Accept")
    let semaphore = DispatchSemaphore(value: 0)
    var result: Result<String, Failure> = .failure(Failure.unavailable)
    let task = session.dataTask(with: request) { [weak self] data, response, error in
      defer { self?.lock.lock(); self?.activeTask = nil; self?.lock.unlock(); semaphore.signal() }
      if let error {
        result = .failure((error as NSError).code == NSURLErrorCancelled ? Failure.canceled : Failure.unavailable)
        return
      }
      guard let http = response as? HTTPURLResponse, let data, data.count <= 256_000 else {
        result = .failure(Failure.invalidResponse); return
      }
      if http.statusCode == 429 { result = .failure(Failure.quota); return }
      guard (200...299).contains(http.statusCode) else { result = .failure(Failure.unavailable); return }
      result = Self.parseResponse(data)
    }
    lock.lock(); activeTask = task; lock.unlock()
    task.resume()
    if semaphore.wait(timeout: .now() + 30) == .timedOut { cancel(); throw Failure.unavailable }
    lock.lock(); let stillActive = activeTask === task; if stillActive { activeTask = nil }; lock.unlock()
    if stillActive { throw Failure.canceled }
    return leading + (try result.get()) + trailing
  }

  /// Splits at every line break and within paragraph boundaries, without splitting grapheme clusters.
  static func chunks(_ text: String, byteLimit: Int = 500) throws -> [String] {
    guard byteLimit > 0 else { return [] }
    var output = [String](), current = ""
    for character in text {
      let piece = String(character)
      let isLineBreak = character == "\r" || character == "\n" || character == "\r\n"
      if isLineBreak {
        if !current.isEmpty { output.append(current); current = "" }
        output.append(piece)
        continue
      }
      let pieceSize = piece.utf8.count
      guard pieceSize <= byteLimit else { throw Failure.oversizedCharacter }
      if (current.utf8.count + pieceSize) > byteLimit, !current.isEmpty {
        output.append(current); current = ""
      }
      current += piece
      if ".!?。！？؟;；".contains(character), current.utf8.count >= byteLimit / 2 {
        output.append(current); current = ""
      }
    }
    if !current.isEmpty { output.append(current) }
    return output
  }

  static func parseResponse(_ data: Data) -> Result<String, Failure> {
    guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let status = (object["responseStatus"] as? NSNumber)?.intValue else {
      return .failure(.invalidResponse)
    }
    let details = (object["responseDetails"] as? String ?? "").lowercased()
    let quotaFinished = (object["quotaFinished"] as? String)?.lowercased() == "true"
      || (object["quotaFinished"] as? NSNumber)?.boolValue == true
    if status == 429 || quotaFinished || details.contains("quota") || details.contains("limit")
      || details.contains("all available free translation requests") || details.contains("daily request limit") {
      return .failure(.quota)
    }
    guard status == 200 else { return .failure(.unavailable) }
    guard let responseData = object["responseData"] as? [String: Any],
          let value = responseData["translatedText"] as? String else { return .failure(.invalidResponse) }
    // MyMemory can return TMX inline group wrappers. Remove only that known
    // transport markup; document text and arbitrary angle-bracket content stay literal.
    let translated = decodeHTMLEntities(value.replacingOccurrences(of: #"<g\s+id=["'][0-9]+["']\s*>([\s\S]*?)</g\s*>"#, with: "$1", options: .regularExpression))
    guard !translated.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return .failure(.invalidResponse) }
    return .success(translated)
  }

  private static func decodeHTMLEntities(_ value: String) -> String {
    guard let regex = try? NSRegularExpression(pattern: "&(?:amp|quot|apos|lt|gt|nbsp);|&#[0-9]{1,7};|&#x[0-9a-fA-F]{1,6};", options: [.caseInsensitive]) else { return value }
    let ns = value as NSString
    let matches = regex.matches(in: value, range: NSRange(location: 0, length: ns.length))
    var output = value
    for match in matches.reversed() {
      let token = ns.substring(with: match.range)
      let lowered = token.lowercased()
      let codePoint: UInt32?
      switch lowered {
      case "&amp;": codePoint = 38
      case "&quot;": codePoint = 34
      case "&apos;": codePoint = 39
      case "&lt;": codePoint = 60
      case "&gt;": codePoint = 62
      case "&nbsp;": codePoint = 160
      default:
        let digits = lowered.hasPrefix("&#x") ? String(lowered.dropFirst(3).dropLast()) : String(lowered.dropFirst(2).dropLast())
        codePoint = UInt32(digits, radix: lowered.hasPrefix("&#x") ? 16 : 10)
      }
      guard let codePoint, let scalar = UnicodeScalar(codePoint) else { continue }
      output = (output as NSString).replacingCharacters(in: match.range, with: String(scalar))
    }
    return output
  }

  private func providerCode(_ code: String) -> String { code == "zh-Hans" ? "zh-CN" : code }
}

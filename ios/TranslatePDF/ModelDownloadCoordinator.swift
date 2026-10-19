import Foundation

final class ModelDownloadCoordinator: NSObject, URLSessionDownloadDelegate {
  private let store: TranslatorStore
  private let lock = NSLock()
  private var tasks = [String: URLSessionDownloadTask]()
  private var lastPersisted = [String: Int64]()
  private var deleting = Set<String>()
  private let restoredTasks = DispatchGroup()
  private lazy var session: URLSession = {
    let config = URLSessionConfiguration.background(withIdentifier:"dev.kigya.translatepdf.model-downloads")
    config.sessionSendsLaunchEvents = true
    config.isDiscretionary = false
    config.waitsForConnectivity = true
    return URLSession(configuration:config,delegate:self,delegateQueue:nil)
  }()

  init(store:TranslatorStore) {
    self.store=store;super.init()
    restoredTasks.enter()
    session.getAllTasks { [weak self] existing in
      guard let self else { return }
      self.lock.lock()
      for task in existing {
        guard let download = task as? URLSessionDownloadTask, let id = task.taskDescription,
              ModelCatalog.models.contains(where: { $0.id == id }) else { continue }
        self.tasks[id] = download
      }
      self.lock.unlock()
      self.restoredTasks.leave()
    }
  }

  func start(_ model:ModelDefinition) throws -> [String:Any] {
    _ = restoredTasks.wait(timeout:.now()+5)
    lock.lock();defer{lock.unlock()}
    deleting.remove(model.id)
    if tasks[model.id] != nil { return model.snapshot(status:"downloading",downloadedBytes:downloadedLength(model)) }
    if try isReady(model) {
      try store.updateModel(model.id,status:"ready",bytes:model.bytes)
      return model.snapshot(status:"ready",downloadedBytes:model.bytes)
    }
    if try store.modelState(model.id)?["status"] as? String == "ready" {
      try store.updateModel(model.id,status:"error",bytes:partialLength(model),error:"The installed model failed its pinned size or SHA-256 check and must be downloaded again.")
    }
    let free = try AppPaths.models.resourceValues(forKeys:[.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
    let remainingBytes = max(0,model.bytes-downloadedLength(model))
    let requiredFree = remainingBytes > Int64.max - 512 * 1_048_576 ? Int64.max : remainingBytes + 512 * 1_048_576
    guard free >= requiredFree else { throw NSError(domain:"PdfTranslator",code:20,userInfo:[NSLocalizedDescriptionKey:"Not enough free storage for the remaining model download and 512 MiB work reserve."]) }
    let task:URLSessionDownloadTask
    if FileManager.default.fileExists(atPath:model.resumeURL.path),let data=try? Data(contentsOf:model.resumeURL),!data.isEmpty {
      task=session.downloadTask(withResumeData:data)
      try? FileManager.default.removeItem(at:model.resumeURL)
    } else {
      task=session.downloadTask(with:model.url)
    }
    task.taskDescription=model.id
    tasks[model.id]=task
    try store.updateModel(model.id,status:"downloading",bytes:downloadedLength(model))
    task.resume()
    return model.snapshot(status:"downloading",downloadedBytes:downloadedLength(model))
  }

  func pause(_ model:ModelDefinition) throws -> [String:Any] {
    lock.lock();let task=tasks.removeValue(forKey:model.id);lock.unlock()
    let knownBytes=downloadedLength(model)
    if let task {
      task.cancel(byProducingResumeData:{[weak self] data in
        guard let self else { return }
        if let data, !data.isEmpty { try? data.write(to:model.resumeURL,options:.atomic) }
        try? self.store.updateModel(model.id,status:"paused",bytes:max(knownBytes,self.partialLength(model)))
      })
    } else {
      try store.updateModel(model.id,status:"paused",bytes:downloadedLength(model))
    }
    return model.snapshot(status:"paused",downloadedBytes:downloadedLength(model))
  }

  func delete(_ model:ModelDefinition) throws {
    lock.lock();deleting.insert(model.id);let task=tasks.removeValue(forKey:model.id);lock.unlock();task?.cancel()
    for url in [model.fileURL,model.partialURL,model.resumeURL] { try? FileManager.default.removeItem(at:url) }
    try store.deleteModel(model.id)
  }

  func isReady(_ model:ModelDefinition) throws -> Bool {
    guard let values=try? model.fileURL.resourceValues(forKeys:[.isRegularFileKey,.fileSizeKey]), values.isRegularFile == true, Int64(values.fileSize ?? -1)==model.bytes else { return false }
    return try sha256(at:model.fileURL)==model.sha256
  }

  func partialLength(_ model:ModelDefinition)->Int64 { ((try? FileManager.default.attributesOfItem(atPath:model.partialURL.path)[.size]) as? NSNumber)?.int64Value ?? 0 }

  private func downloadedLength(_ model:ModelDefinition)->Int64 {
    let persisted=(try? store.modelState(model.id)?["downloaded_bytes"] as? Int64) ?? 0
    return min(model.bytes,max(persisted,partialLength(model)))
  }

  func urlSession(_ session:URLSession,downloadTask:URLSessionDownloadTask,didWriteData bytesWritten:Int64,totalBytesWritten:Int64,totalBytesExpectedToWrite:Int64) {
    guard let id=downloadTask.taskDescription, ModelCatalog.models.contains(where: { $0.id == id }) else { return }
    let current=lastPersisted[id] ?? 0
    if totalBytesWritten-current >= 1_048_576 {
      lastPersisted[id]=totalBytesWritten
      try? store.updateModel(id,status:"downloading",bytes:totalBytesWritten)
    }
  }

  func urlSession(_ session:URLSession,downloadTask:URLSessionDownloadTask,didFinishDownloadingTo location:URL) {
    guard let id=downloadTask.taskDescription, let model=try? ModelCatalog.get(id) else { return }
    lock.lock();let shouldIgnore=deleting.contains(id);lock.unlock();if shouldIgnore{return}
    do {
      try? FileManager.default.removeItem(at:model.partialURL)
      try FileManager.default.moveItem(at:location,to:model.partialURL)
      let attrs=try FileManager.default.attributesOfItem(atPath:model.partialURL.path)
      guard (attrs[.size] as? NSNumber)?.int64Value==model.bytes else { throw NSError(domain:"PdfTranslator",code:21,userInfo:[NSLocalizedDescriptionKey:"Downloaded model has an incomplete size."]) }
      try store.updateModel(id,status:"verifying",bytes:model.bytes)
      guard try sha256(at:model.partialURL)==model.sha256 else { throw NSError(domain:"PdfTranslator",code:22,userInfo:[NSLocalizedDescriptionKey:"Downloaded model SHA-256 does not match the pinned revision."]) }
      try? FileManager.default.removeItem(at:model.fileURL)
      try FileManager.default.moveItem(at:model.partialURL,to:model.fileURL)
      try store.updateModel(id,status:"ready",bytes:model.bytes)
    } catch {
      try? FileManager.default.removeItem(at:model.partialURL)
      try? store.updateModel(id,status:"error",bytes:downloadedLength(model),error:error.localizedDescription)
    }
    lock.lock();tasks.removeValue(forKey:id);lastPersisted.removeValue(forKey:id);lock.unlock()
  }

  func urlSession(_ session:URLSession,task:URLSessionTask,didCompleteWithError error:Error?) {
    guard let id=task.taskDescription, let model=try? ModelCatalog.get(id) else { return }
    lock.lock();let shouldIgnore=deleting.contains(id);lock.unlock();if shouldIgnore{return}
    if let error {
      let ns=error as NSError
      if ns.code == NSURLErrorCancelled {
        try? store.updateModel(id,status:"paused",bytes:downloadedLength(model))
      } else {
        try? store.updateModel(id,status:"error",bytes:downloadedLength(model),error:error.localizedDescription)
      }
      lock.lock();tasks.removeValue(forKey:id);lock.unlock()
    }
  }

  func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
    DispatchQueue.main.async {
      NotificationCenter.default.post(name: NSNotification.Name("TranslatePDFBackgroundSessionDidFinishEvents"),object:nil)
    }
  }
}

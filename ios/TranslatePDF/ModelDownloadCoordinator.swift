import Foundation
import UserNotifications

struct ModelDownloadCallbackGuard {
  static func accepts(taskGeneration: UInt64?, currentGeneration: UInt64?, deleting: Bool) -> Bool {
    guard !deleting, let taskGeneration, let currentGeneration else { return false }
    return taskGeneration == currentGeneration
  }

  static func performIfCurrent(lock: NSLock, taskGeneration: () -> UInt64?, currentGeneration: () -> UInt64?, isDeleting: () -> Bool, commit: () -> Void) -> Bool {
    lock.lock();defer{lock.unlock()}
    guard accepts(taskGeneration:taskGeneration(),currentGeneration:currentGeneration(),deleting:isDeleting()) else{return false}
    commit()
    return true
  }
}

final class ModelDownloadRestorationBarrier {
  private let lock=NSLock()
  private let group=DispatchGroup()
  private var completed=false

  init(){group.enter()}
  func complete(){lock.lock();defer{lock.unlock()};guard !completed else{return};completed=true;group.leave()}
  func wait(timeout:TimeInterval=5)throws {
    guard group.wait(timeout:.now()+timeout) == .success else {
      throw NSError(domain:"PdfTranslator",code:26,userInfo:[NSLocalizedDescriptionKey:"Background model tasks are still being restored. Retry the operation shortly."])
    }
  }
}

final class ModelDownloadCoordinator: NSObject, URLSessionDownloadDelegate {
  private let store: TranslatorStore
  private let lock = NSLock()
  private var tasks = [String: URLSessionDownloadTask]()
  private var taskGenerations = [Int: UInt64]()
  private var lastPersisted = [String: Int64]()
  private var deleting = Set<String>()
  private var deletionWaiters = [String: DispatchSemaphore]()
  private var pendingPauses = Set<String>()
  private var pauseWaiters = [String: DispatchGroup]()
  private var generations = [String: UInt64]()
  private let restoredTasks = ModelDownloadRestorationBarrier()
  private lazy var session: URLSession = {
    let config = URLSessionConfiguration.background(withIdentifier:"dev.kigya.translatepdf.model-downloads")
    config.sessionSendsLaunchEvents = true
    config.isDiscretionary = false
    config.waitsForConnectivity = true
    return URLSession(configuration:config,delegate:self,delegateQueue:nil)
  }()

  init(store:TranslatorStore) {
    self.store=store;super.init()
    session.getAllTasks { [weak self] existing in
      guard let self else { return }
      self.lock.lock()
      for task in existing {
        guard let download = task as? URLSessionDownloadTask, let id = task.taskDescription,
              ModelCatalog.models.contains(where: { $0.id == id }) else { continue }
        self.tasks[id] = download
        if self.generations[id] == nil { self.generations[id] = 0 }
        self.taskGenerations[download.taskIdentifier] = self.generations[id]
      }
      self.lock.unlock()
      self.restoredTasks.complete()
    }
  }

  func start(_ model:ModelDefinition) throws -> [String:Any] {
    try restoredTasks.wait()
    lock.lock();defer{lock.unlock()}
    guard !deleting.contains(model.id), !pendingPauses.contains(model.id) else { throw NSError(domain:"PdfTranslator",code:23,userInfo:[NSLocalizedDescriptionKey:"The previous model pause or deletion is still finishing. Try again shortly."]) }
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
    let generation=(generations[model.id] ?? 0) &+ 1
    generations[model.id]=generation
    taskGenerations[task.taskIdentifier]=generation
    tasks[model.id]=task
    try store.updateModel(model.id,status:"downloading",bytes:downloadedLength(model))
    task.resume()
    return model.snapshot(status:"downloading",downloadedBytes:downloadedLength(model))
  }

  func pause(_ model:ModelDefinition) throws -> [String:Any] {
    try restoredTasks.wait()
    lock.lock()
    if let waiter=pauseWaiters[model.id] {
      lock.unlock()
      guard waiter.wait(timeout:.now()+30) == .success else { throw NSError(domain:"PdfTranslator",code:25,userInfo:[NSLocalizedDescriptionKey:"The previous model pause is still finishing. Retry shortly."]) }
      return model.snapshot(status:"paused",downloadedBytes:downloadedLength(model))
    }
    let task=tasks.removeValue(forKey:model.id)
    guard let task else {
      lock.unlock()
      try store.updateModel(model.id,status:"paused",bytes:downloadedLength(model))
      return model.snapshot(status:"paused",downloadedBytes:downloadedLength(model))
    }
    let generation=(generations[model.id] ?? 0) &+ 1
    generations[model.id]=generation
    let waiter=DispatchGroup();waiter.enter()
    pendingPauses.insert(model.id);pauseWaiters[model.id]=waiter
    lock.unlock()
    let knownBytes=downloadedLength(model)
    task.cancel(byProducingResumeData:{[weak self] data in
      guard let self else { waiter.leave();return }
      self.lock.lock()
      let mayCommit=self.generations[model.id] == generation && !self.deleting.contains(model.id)
      if mayCommit {
        if let data, !data.isEmpty { try? data.write(to:model.resumeURL,options:.atomic) }
        try? self.store.updateModel(model.id,status:"paused",bytes:max(knownBytes,self.partialLength(model)))
      }
      self.pendingPauses.remove(model.id);self.pauseWaiters.removeValue(forKey:model.id)
      self.lock.unlock()
      waiter.leave()
    })
    guard waiter.wait(timeout:.now()+30) == .success else {
      throw NSError(domain:"PdfTranslator",code:25,userInfo:[NSLocalizedDescriptionKey:"The model pause has not finished saving its resume data. Retry shortly."])
    }
    return model.snapshot(status:"paused",downloadedBytes:downloadedLength(model))
  }

  func delete(_ model:ModelDefinition) throws {
    try restoredTasks.wait()
    lock.lock()
    deleting.insert(model.id)
    generations[model.id]=(generations[model.id] ?? 0) &+ 1
    let task=tasks.removeValue(forKey:model.id)
    let waiter=task == nil ? nil : DispatchSemaphore(value:0)
    let pauseWaiter=pauseWaiters[model.id]
    if let waiter { deletionWaiters[model.id]=waiter }
    lock.unlock()
    task?.cancel()
    if let pauseWaiter, pauseWaiter.wait(timeout:.now()+30) == .timedOut {
      throw NSError(domain:"PdfTranslator",code:24,userInfo:[NSLocalizedDescriptionKey:"The model pause callback is still finishing. Retry deletion shortly."])
    }
    if let waiter, waiter.wait(timeout:.now()+30) == .timedOut {
      lock.lock();deletionWaiters.removeValue(forKey:model.id);lock.unlock()
      throw NSError(domain:"PdfTranslator",code:24,userInfo:[NSLocalizedDescriptionKey:"The background model download is still stopping. Retry deletion after it pauses."])
    }
    lock.lock();defer{deleting.remove(model.id);deletionWaiters.removeValue(forKey:model.id);lock.unlock()}
    for url in [model.fileURL,model.partialURL,model.resumeURL] where FileManager.default.fileExists(atPath:url.path) {
      try FileManager.default.removeItem(at:url)
    }
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
    _ = ModelDownloadCallbackGuard.performIfCurrent(lock:lock,taskGeneration:{self.taskGenerations[downloadTask.taskIdentifier]},currentGeneration:{self.generations[id]},isDeleting:{self.deleting.contains(id)}) {
      let current=self.lastPersisted[id] ?? 0
      if totalBytesWritten-current >= 1_048_576 {
        self.lastPersisted[id]=totalBytesWritten
        try? self.store.updateModel(id,status:"downloading",bytes:totalBytesWritten)
      }
    }
  }

  func urlSession(_ session:URLSession,downloadTask:URLSessionDownloadTask,didFinishDownloadingTo location:URL) {
    guard let id=downloadTask.taskDescription, let model=try? ModelCatalog.get(id) else { return }
    lock.lock();defer{lock.unlock()};guard ModelDownloadCallbackGuard.accepts(taskGeneration:taskGenerations[downloadTask.taskIdentifier],currentGeneration:generations[id],deleting:deleting.contains(id)) else{return}
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
      postModelReadyNotificationIfAuthorized()
    } catch {
      try? FileManager.default.removeItem(at:model.partialURL)
      try? store.updateModel(id,status:"error",bytes:downloadedLength(model),error:error.localizedDescription)
    }
    tasks.removeValue(forKey:id);lastPersisted.removeValue(forKey:id);taskGenerations.removeValue(forKey:downloadTask.taskIdentifier)
  }

  func urlSession(_ session:URLSession,task:URLSessionTask,didCompleteWithError error:Error?) {
    guard let id=task.taskDescription, let model=try? ModelCatalog.get(id) else { return }
    lock.lock()
    if deleting.contains(id) {
      taskGenerations.removeValue(forKey:task.taskIdentifier)
      if let waiter=deletionWaiters.removeValue(forKey:id) { waiter.signal() }
      lock.unlock()
      return
    }
    let isCurrent=ModelDownloadCallbackGuard.accepts(taskGeneration:taskGenerations.removeValue(forKey:task.taskIdentifier),currentGeneration:generations[id],deleting:false)
    guard isCurrent else { lock.unlock();return }
    if let error {
      let ns=error as NSError
      if ns.code == NSURLErrorCancelled {
        try? store.updateModel(id,status:"paused",bytes:downloadedLength(model))
      } else {
        try? store.updateModel(id,status:"error",bytes:downloadedLength(model),error:error.localizedDescription)
      }
      tasks.removeValue(forKey:id)
    }
    lock.unlock()
  }

  func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
    DispatchQueue.main.async {
      NotificationCenter.default.post(name: NSNotification.Name("TranslatePDFBackgroundSessionDidFinishEvents"),object:nil)
    }
  }

  private func postModelReadyNotificationIfAuthorized() {
    let center=UNUserNotificationCenter.current()
    center.getNotificationSettings { settings in
      var authorized=settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
      #if os(iOS)
      authorized = authorized || settings.authorizationStatus == .ephemeral
      #endif
      guard authorized else { return }
      let russian=Locale.preferredLanguages.first?.lowercased().hasPrefix("ru") == true
      let content=UNMutableNotificationContent()
      content.title=russian ? "Модель готова" : "Model ready"
      content.body=russian ? "Перевод на устройстве готов к работе." : "Offline translation is ready on this device."
      content.sound = .default
      let request=UNNotificationRequest(identifier:"translatepdf-model-ready-\(UUID().uuidString)",content:content,trigger:UNTimeIntervalNotificationTrigger(timeInterval:1,repeats:false))
      center.add(request)
    }
  }
}

import Foundation
import React
import UIKit
import UniformTypeIdentifiers
import Vision
import QuickLook
import BackgroundTasks
import Darwin
import UserNotifications
import WebKit
import CoreFoundation
import CryptoKit

private final class PdfPreviewController: QLPreviewController, QLPreviewControllerDataSource {
  private let file: URL
  init(file:URL){self.file=file;super.init(nibName:nil,bundle:nil);dataSource=self}
  required init?(coder:NSCoder){nil}
  func numberOfPreviewItems(in controller:QLPreviewController)->Int{1}
  func previewController(_ controller:QLPreviewController,previewItemAt index:Int)->QLPreviewItem{file as NSURL}
}

@objc(PdfTranslatorModule)
final class PdfTranslatorModule: NSObject, RCTBridgeModule {
  struct ContinuedProcessingRequestToken {
    let identifier:String
    let generation:UInt64
  }
  struct OCRSourceRect {
    let text:String
    let pictogram:Bool
    let left:Double
    let top:Double
    let right:Double
    let bottom:Double
    let baseline:Double?
  }
  struct OCRSourceLine {
    let fragments:[OCRSourceRect]
    let textRuns:[String]
  }
  static let sourceDisagreementBlockWarning="Embedded PDF text differs from local OCR; verify this source region."
  static func moduleName() -> String! { "PdfTranslator" }
  static func requiresMainQueueSetup() -> Bool { false }
  // Bind immutable exports to the renderer revision and bundled font content.
  static let pdfRendererPolicy="source-preserving-v10;noto=5c15b8bb0ac1bbd533917b23968c6f0a420f143533e1293cc575f4f9dd543be8;cjk=b7bebb49c3c6b0266362cf75dd9bdd9bf5fbe5fb390be8f7808ebedef376de76"

  private let work = DispatchQueue(label:"dev.kigya.translatepdf.native-queue",qos:.userInitiated)
  private let translationWork = DispatchQueue(label:"dev.kigya.translatepdf.translation-queue",qos:.userInitiated)
  private let stateLock=NSRecursiveLock()
  private let notificationPermissionLock=NSLock()
  @available(iOS 26.0,*) private static let continuedTaskRegistrationLock=NSLock()
  @available(iOS 26.0,*) private static var registeredContinuedTaskIds=Set<String>()
  @available(iOS 26.0,*) private static var continuedTaskOwners=[String:ContinuedTaskOwner]()
  private var store:TranslatorStore!
  private var downloader:ModelDownloadCoordinator!
  private var pendingImport:RCTPromiseResolveBlock?
  private var pendingImportReject:RCTPromiseRejectBlock?
  private var pendingExport:RCTPromiseResolveBlock?
  private var pendingExportReject:RCTPromiseRejectBlock?
  private var pendingExportInfo:(id:String,url:URL,name:String)?
  private var pendingExportMIME="application/pdf"
  private var activeJobID:String?
  private var activeRunToken:UUID?
  private var pauseRequested=false
  private var pauseReason:String?
  private var cancelRequested=false
  private struct PendingStopAcknowledgementRetry {
    let token:UUID
    let attempts:Int
  }
  private var pendingStopAcknowledgementRetries=[String:PendingStopAcknowledgementRetry]()
  var beforeWorkerStopPersistenceForTesting:(() throws->Void)?
  var beforeRunningStopOutcomeSelectionForTesting:(()->Void)?
  var beforeStopAcknowledgementRetryAdmissionForTesting:(()->Void)?
  var beforeWorkerPageStopCheckForTesting:(()->Void)?
  var activeJobIDForTesting:String? {
    get { stateLock.lock();defer{stateLock.unlock()};return activeJobID }
    set { stateLock.lock();activeJobID=newValue;activeRunToken=newValue == nil ? nil:UUID();pauseRequested=false;pauseReason=nil;cancelRequested=false;stateLock.unlock() }
  }
  var activeRunTokenForTesting:UUID? {
    stateLock.lock();defer{stateLock.unlock()};return activeRunToken
  }
  // A narrow synchronization seam for hosted cancellation/finalization race tests.
  var beforeFinalizationCommitForTesting: ((String) -> Void)?
  private let onlineTranslator=MyMemoryTranslator()
  private let localTranslations=LocalTranslationMemo()
  private var systemLowMemory=false
  private var backgroundTask:UIBackgroundTaskIdentifier = .invalid
  private var backgroundTaskOwnerToken:UUID?
  private var continuedTask:BGTask?
  private var continuedTaskJobID:String?
  private var continuedTaskRunToken:UUID?
  private var continuedTaskGenerations=[String:UInt64]()
  private var continuedTaskRequestIDs=[String:String]()
  private var continuedTaskRequestRunTokens=[String:UUID]()
  var submitContinuedTaskRequestForTesting:((BGTaskRequest)throws->Void)?
  private let onboardingVersion=1

  func drainTranslationWorkForTesting(completion:@escaping()->Void){translationWork.async{completion()}}

  override init(){
    super.init()
    do {
      store=try TranslatorStore(directory:AppPaths.support)
      downloader=ModelDownloadCoordinator(store:store)
      try recoverInterruptedJobs()
    } catch {
      NSLog("TranslatePDF private store initialization failed: %@",error.localizedDescription)
    }
    NotificationCenter.default.addObserver(self,selector:#selector(applicationDidBecomeActive),name:UIApplication.didBecomeActiveNotification,object:nil)
    NotificationCenter.default.addObserver(self,selector:#selector(didReceiveMemoryWarning),name:UIApplication.didReceiveMemoryWarningNotification,object:nil)
  }

  deinit { NotificationCenter.default.removeObserver(self) }
  @objc private func didReceiveMemoryWarning(){
    stateLock.lock();systemLowMemory=true;let job=activeJobID;stateLock.unlock()
    if let job { requestPause(jobID:job,reason:"iOS reported memory pressure; the latest saved page checkpoint is ready to resume.") }
  }

  @objc func bootstrap(_ resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    UNUserNotificationCenter.current().getNotificationSettings { notificationSettings in
    self.run(resolve,reject,"BOOTSTRAP_FAILED") {
      guard let store=self.store else { throw self.error("Private storage is unavailable.") }
      let total=ProcessInfo.processInfo.physicalMemory
      let available=UInt64(os_proc_available_memory())
      let disk=try AppPaths.support.resourceValues(forKeys:[.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
      self.stateLock.lock();let lowMemory=self.systemLowMemory;self.stateLock.unlock()
      let availableDisk=UInt64(max(0,disk))
      let modelStates=try store.models(totalMemory:total,availableMemory:available,availableDisk:availableDisk,systemLowMemory:lowMemory)
      var assessments=[String:ModelResourceAssessment]()
      for model in ModelCatalog.models {
        let state=modelStates.first { $0["id"] as? String == model.id }
        assessments[model.id]=MemoryPolicy.assess(model,status:state?["status"] as? String ?? "not_downloaded",downloadedBytes:state?["downloadedBytes"] as? Int64 ?? 0,total:total,available:available,availableDisk:availableDisk,systemLowMemory:lowMemory)
      }
      let recommended=ModelCatalog.recommended(from:assessments)
      let recommendation=assessments[recommended.id]!
      let docs=try store.documents();let jobs=try store.jobs();let glossary=try store.glossary()
      let installedModelIds=modelStates.compactMap { model -> String? in
        guard model["status"] as? String == "ready", model["translationEnabled"] as? Bool == true else { return nil }
        return model["id"] as? String
      }
      let theme=try store.setting("theme",fallback:"system"),language=try store.setting("language",fallback:"system")
      let languagePreferences=try store.languagePreferences()
      let sourceLanguage=languagePreferences.sourceLanguage,targetLanguage=languagePreferences.targetLanguage
      let selectedModelId=try store.resolveSelectedModelId(recommended:recommended.id,installedModelIds:installedModelIds)
      let selectedEngine=try store.resolveSelectedEngine(modelId:selectedModelId)
      let appPreferences=try store.appPreferences()
      let version=Int(try store.setting("onboarding_version",fallback:"1")) ?? 1
      let page=Int(try store.setting("onboarding_page",fallback:"0")) ?? 0
      let done=(try store.setting("onboarding_done",fallback:"false")) == "true"
      var device=[String:Any]()
      device["manufacturer"]="Apple";device["model"]=self.deviceModel();device["apiLevel"]=0
      device["systemLocale"]=Locale.preferredLanguages.first ?? Locale.current.identifier
      device["totalMemoryBytes"]=total;device["availableMemoryBytes"]=available
      device["systemLowMemoryThresholdBytes"]=0;device["systemLowMemory"]=lowMemory
      device["reservedMemoryBytes"]=MemoryPolicy.reserve(total:total);device["availableDiskBytes"]=disk
      device["requiredPipelineMemoryBytes"]=recommended.requiredMemoryBytes;device["requiredDiskBytes"]=recommendation.requiredDiskBytes
      device["memoryEstimateEligible"]=recommendation.memoryEstimateEligible;device["diskEstimateEligible"]=recommendation.diskEstimateEligible
      device["resourceEstimateEligible"]=recommendation.resourceEstimateEligible
      device["recommendedModelAvailable"]=recommendation.resourceEstimateEligible;device["recommendedModelId"]=recommended.id
      device["recommendationReason"]=recommendation.reason;device["recommendationVerified"]=false
      let installedBytes=modelStates.reduce(Int64(0)){$0 + (($1["storedBytes"] as? NSNumber)?.int64Value ?? 0) - (($1["partialBytes"] as? NSNumber)?.int64Value ?? 0)}
      let partialBytes=modelStates.reduce(Int64(0)){$0 + (($1["partialBytes"] as? NSNumber)?.int64Value ?? 0)}
      let activeTransferBytes=modelStates.reduce(Int64(0)){$0 + (($1["activeTransferBytes"] as? NSNumber)?.int64Value ?? 0)}
      device["storage"]=["installedModelBytes":max(0,installedBytes),"partialDownloadBytes":partialBytes,"activeTransferBytes":activeTransferBytes,"freeDiskBytes":disk]
      return try jsonString(["schemaVersion":1,"device":device,"preferences":["notificationExplanationShown":try store.setting("notificationExplanationShown",fallback:"false") == "true","theme":theme,"language":language,"sourceLanguage":sourceLanguage,"targetLanguage":targetLanguage,"selectedModelId":selectedModelId,"selectedEngine":selectedEngine,"appRating":appPreferences["appRating"] ?? 0,"librarySort":appPreferences["librarySort"] ?? "recent"],"onboarding":["version":version,"page":page,"done":done],"models":modelStates,"documents":docs,"jobs":jobs,"glossary":glossary,"notifications":self.notificationSnapshot(notificationSettings)])
    }
    }
  }

  @objc func getActivitySnapshot(_ resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    UNUserNotificationCenter.current().getNotificationSettings { notificationSettings in
      self.run(resolve,reject,"ACTIVITY_SNAPSHOT_FAILED") {
        guard let store=self.store else { throw self.error("Private storage is unavailable.") }
        let total=ProcessInfo.processInfo.physicalMemory,available=UInt64(os_proc_available_memory())
        let disk=try AppPaths.models.resourceValues(forKeys:[.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
        self.stateLock.lock();let lowMemory=self.systemLowMemory;let activeID=self.activeJobID;let stoppingID=self.pauseRequested ? activeID:nil;self.stateLock.unlock()
        let models=try store.models(totalMemory:total,availableMemory:available,availableDisk:UInt64(max(0,disk)),systemLowMemory:lowMemory)
        let jobs=try store.jobs().map { row -> [String:Any] in
          var item=row
          item["workerStopping"]=(item["id"] as? String)==stoppingID
          if (item["id"] as? String)==activeID,(item["stage"] as? String)=="translating",let progress=try? jsonObject(TranslateCoreBridge.inferenceProgress()) {
            item["processedTokens"]=progress["processedTokens"] as? NSNumber ?? 0
            item["generatedTokens"]=progress["generatedTokens"] as? NSNumber ?? 0
          }
          return item
        }
        return try jsonString(["jobs":jobs,"models":models,"notifications":self.notificationSnapshot(notificationSettings)])
      }
    }
  }

  @objc func pickPdf(_ resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    DispatchQueue.main.async {
      guard self.pendingImport == nil else { reject("PICKER_BUSY","Another document picker is already open.",nil);return }
      guard let presenter=self.topPresenter() else { reject("PICKER_UNAVAILABLE","Open the app before choosing a PDF.",nil);return }
      self.pendingImport=resolve;self.pendingImportReject=reject
      let docx=UTType(filenameExtension:"docx") ?? .zip
      let controller=UIDocumentPickerViewController(forOpeningContentTypes:[.pdf,docx],asCopy:false)
      controller.delegate=self;controller.allowsMultipleSelection=false
      presenter.present(controller,animated:true)
    }
  }
  @objc func pickDocument(_ resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){pickPdf(resolve,rejecter:reject)}

  @objc func startModelDownload(_ modelId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"MODEL_DOWNLOAD_START_FAILED") { let model=try ModelCatalog.get(modelId);return try jsonString(self.downloader.start(model)) }
  }
  @objc func pauseModelDownload(_ modelId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"MODEL_DOWNLOAD_PAUSE_FAILED") { let model=try ModelCatalog.get(modelId);return try jsonString(self.downloader.pause(model)) }
  }
  @objc func deleteModel(_ modelId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"MODEL_DELETE_FAILED") { let jobs=try self.store.jobs();guard !jobs.contains(where:{($0["modelId"] as? String)==modelId && ["queued","running"].contains($0["status"] as? String ?? "")}) else{throw self.error("A queued or active translation uses this model. Cancel or finish that job before deleting it.")};try self.downloader.delete(ModelCatalog.get(modelId));return NSNull() }
  }

  @objc func requestNotificationPermission(_ resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    let center=UNUserNotificationCenter.current()
    center.getNotificationSettings { settings in
      self.notificationPermissionLock.lock()
      let current=self.notificationSnapshot(settings)
      let shouldRequest=current["canRequest"] as? Bool == true
      if shouldRequest { UserDefaults.standard.set(true,forKey:"dev.kigya.translatepdf.notificationPermissionRequested") }
      self.notificationPermissionLock.unlock()
      guard shouldRequest else {
        let latest=self.notificationSnapshot(settings)
        DispatchQueue.main.async { resolve((try? jsonString(latest)) ?? "{}") }
        return
      }
      center.requestAuthorization(options:[.alert,.badge,.sound]) { _,_ in
        center.getNotificationSettings { updated in
          let snapshot=self.notificationSnapshot(updated)
          DispatchQueue.main.async { resolve((try? jsonString(snapshot)) ?? "{}") }
        }
      }
    }
  }

  @objc func getLicenseNotices(_ resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"LICENSES_READ_FAILED") {
      let notices=try self.licenseNoticeEntries().map { entry in ["id":entry.id,"title":entry.title] }
      return try jsonString(notices)
    }
  }

  @objc func getLicenseNotice(_ id:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"LICENSE_READ_FAILED") {
      guard let entry=try self.licenseNoticeEntries().first(where:{$0.id == id}),
        let bundle=self.licenseBundle() else { throw self.error("The requested license notice is unavailable.") }
      return try entry.files.map { file in
        guard self.isSafeLicensePath(file) else { throw self.error("The license catalog contains an invalid path.") }
        let resource=file.replacingOccurrences(of:"/",with:"__")
        let url=bundle.bundleURL.appendingPathComponent(resource,isDirectory:false)
        guard FileManager.default.fileExists(atPath:url.path) else { throw self.error("A required license notice is unavailable.") }
        let data=try Data(contentsOf:url)
        // Some upstream license texts (for example PDFium's FreeType notice) use Windows-1252.
        // Decode that legacy encoding explicitly instead of failing the whole notice screen.
        guard let text=String(data:data,encoding:.utf8) ?? String(data:data,encoding:.windowsCP1252) else {
          throw self.error("A required license notice could not be decoded.")
        }
        return text
      }.joined(separator:"\n\n")
    }
  }

  @objc func startJob(_ inputJson:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    runInForeground(resolve,reject,"JOB_START_FAILED","Return to the open app before starting translation.") {
      guard let input=try JSONSerialization.jsonObject(with:Data(inputJson.utf8)) as? [String:Any],let documentId=input["documentId"] as? String else { throw self.error("Translation request is invalid.") }
      let engine=input["engine"] as? [String:Any] ?? ["kind":"local","id":input["modelId"] as? String ?? ""]
      let engineKind=engine["kind"] as? String ?? "local",engineId=engine["id"] as? String ?? ""
      guard engineKind == "local" || (engineKind == "online" && engineId == "mymemory") else { throw self.error("The selected translation engine is not supported.") }
      if engineKind == "online" && input["onlineConsent"] as? Bool != true { throw self.error("Confirm the online translation privacy notice before sending document text to MyMemory.") }
      guard let sourceLanguage=input["sourceLanguage"] as? String,let targetLanguage=input["targetLanguage"] as? String else { throw self.error("Choose a source and target language before starting translation.") }
      _ = try TranslationLanguage.pair(source:sourceLanguage,target:targetLanguage)
      self.stateLock.lock();let alreadyRunning=self.activeJobID != nil;self.stateLock.unlock()
      guard !alreadyRunning else { throw self.error("Another translation is already running. Pause or finish it before starting a second job.") }
      guard let path=try self.store.documentPath(documentId),FileManager.default.fileExists(atPath:path) else { throw self.error("The imported document is no longer available.") }
      if engineKind == "local" {
        let model=try ModelCatalog.get(input["modelId"] as? String ?? engineId)
        guard model.translationEnabled else { throw self.error("The selected model is not enabled for translation.") }
        let glossary=(input["useGlossary"] as? Bool ?? true) ? try self.store.glossaryText(input["glossaryId"] as? String,sourceLanguage:sourceLanguage,targetLanguage:targetLanguage) : ""
        guard model.supportsGlossary || glossary.isEmpty else { throw self.error("The selected model profile does not support glossary instructions. Clear the glossary or choose a glossary-compatible model before starting a job.") }
        guard try self.downloader.isReady(model) else { throw self.error("Download and verify the selected model first.") }
        let assessment=try self.assessment(for:model,status:"ready",downloadedBytes:model.bytes)
        guard assessment.diskEstimateEligible else { throw self.error("The selected model is currently unavailable: \(assessment.reason).") }
      }
      let isDocx=try self.store.documentFormat(documentId)=="docx"
      let info=try jsonObject(isDocx ? TranslateCoreBridge.inspectDocx(path) : TranslateCoreBridge.inspect(path))
      if let problem=info["error"] as? String { throw self.error(problem) }
      let count=(info[isDocx ? "segmentCount":"pageCount"] as? NSNumber)?.intValue ?? 0
      guard count>0,count<=10_000 else { throw self.error("The document has an unsupported number of pages or text segments, or could not be inspected.") }
      let id=UUID().uuidString.lowercased()
      let job=try self.store.addJob(id:id,input:input,pageCount:count)
      let runToken=UUID()
      self.stateLock.lock();self.activeJobID=id;self.activeRunToken=runToken;self.pauseRequested=false;self.pauseReason=nil;self.cancelRequested=false;self.stateLock.unlock()
      self.beginBackgroundExecution(jobID:id,runToken:runToken)
      if #available(iOS 26.0,*) { self.scheduleContinuedProcessing(jobID:id,documentName:(job["documentName"] as? String) ?? "Document") }
      self.translationWork.async { self.processJob(id,runToken:runToken) }
      return try jsonString(job)
    }
  }
  @objc func pauseJob(_ jobId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"JOB_PAUSE_FAILED") {
      let job:[String:Any],active:Bool
      do {
        self.stateLock.lock();defer{self.stateLock.unlock()}
        guard let existing=try self.store.job(jobId) else{throw self.error("Translation job was not found.")}
        job=existing
        active=self.activeJobID==jobId
        let status=job["status"] as? String ?? ""
        if !active && !["canceled","completed","partial"].contains(status) {
          try self.store.updateJob(jobId,status:"paused")
        }
      }
      if active { self.requestPause(jobID:jobId,reason:"Paused after the latest saved page checkpoint.") }
      else if #available(iOS 26.0,*) { self.cancelQueuedContinuedProcessing(jobID:jobId) }
      var snapshot=try self.store.job(jobId) ?? job;snapshot["workerStopping"]=active
      return try jsonString(snapshot)
    }
  }
  @objc func resumeJob(_ jobId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    runInForeground(resolve,reject,"JOB_RESUME_FAILED","Return to the open app before resuming translation.") {
      guard var job=try self.store.job(jobId) else{throw self.error("Translation job was not found.")}
      guard ["paused","queued","failed"].contains(job["status"] as? String ?? "") else{throw self.error("This job cannot be resumed in its current state.")}
      self.stateLock.lock();let busy=self.activeJobID != nil;self.stateLock.unlock()
      guard !busy else{throw self.error("Another translation is already running.")}
      let engine=job["engine"] as? [String:Any] ?? ["kind":"local"]
      let isOnline=(engine["kind"] as? String)=="online"
      let model=isOnline ? nil : try ModelCatalog.get(job["modelId"] as? String ?? "")
      if let model { try self.store.validateJobSnapshot(jobId,model:model) } else { try self.store.validateOnlineSnapshot(jobId) }
      let total=(job["totalPages"] as? NSNumber)?.intValue ?? 0
      if !isOnline,let model,try self.requiresInference(jobId,totalPages:total) {
        guard model.translationEnabled else{throw self.error("The selected model is not enabled for translation.")}
        guard try self.downloader.isReady(model) else{throw self.error("Download and verify the model before resuming.")}
        _ = try self.assessment(for:model,status:"ready",downloadedBytes:model.bytes)
        // Estimated RAM remains a recommendation only. The runtime may still fail to allocate;
        // that failure pauses from the latest durable checkpoint without switching models.
      }
      try self.store.updateJob(jobId,status:"queued",stage:"queued",clearError:true)
      job=try self.store.job(jobId) ?? job
      let runToken=UUID()
      self.stateLock.lock();self.activeJobID=jobId;self.activeRunToken=runToken;self.pauseRequested=false;self.pauseReason=nil;self.cancelRequested=false;self.systemLowMemory=false;self.stateLock.unlock()
      self.beginBackgroundExecution(jobID:jobId,runToken:runToken)
      if #available(iOS 26.0,*){self.scheduleContinuedProcessing(jobID:jobId,documentName:job["documentName"] as? String ?? "PDF")}
      self.translationWork.async{self.processJob(jobId,runToken:runToken)}
      return try jsonString(job)
    }
  }
  @objc func cancelJob(_ jobId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"JOB_CANCEL_FAILED") {
      let job:[String:Any],status:String,active:Bool,pendingAcknowledgement:Bool,runToken:UUID?
      do {
        self.stateLock.lock();defer{self.stateLock.unlock()}
        guard let existing=try self.store.job(jobId) else { throw self.error("Translation job was not found.") }
        job=existing
        status=job["status"] as? String ?? ""
        active=self.activeJobID==jobId
        runToken=active ? self.activeRunToken:nil
        pendingAcknowledgement=self.pendingStopAcknowledgementRetries[jobId] != nil
        if active && ["running","queued"].contains(status) { self.cancelRequested=true }
        else if !active && ["queued","paused","failed"].contains(status) {
          try self.store.updateJob(jobId,status:"canceled",error:"Canceled by the user.")
        }
      }
      if active && ["running","queued"].contains(status) { TranslateCoreBridge.cancel();self.onlineTranslator.cancel() }
      if #available(iOS 26.0,*){
        if let runToken{self.cancelQueuedContinuedProcessing(jobID:jobId,runToken:runToken)}
        else{self.cancelQueuedContinuedProcessing(jobID:jobId)}
      }
      if pendingAcknowledgement{self.retryPendingStopAcknowledgement(jobID:jobId)}
      return try jsonString(try self.store.job(jobId) ?? job)
    }
  }
  @objc func setJobColor(_ jobId:String,color:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"JOB_COLOR_SAVE_FAILED"){try jsonString(self.store.setJobColor(jobId,color:color))}
  }
  @objc func deleteJob(_ jobId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"JOB_DELETE_FAILED") {
      guard let job=try self.store.job(jobId) else{throw self.error("Translation job was not found.")}
      self.stateLock.lock();let active=self.activeJobID==jobId;self.stateLock.unlock()
      guard !active && !["running","queued"].contains(job["status"] as? String ?? "") else{
        throw self.error("Wait for the active translation to pause before deleting it.")
      }
      if #available(iOS 26.0,*){self.cancelQueuedContinuedProcessing(jobID:jobId)}
      let source=try self.store.deleteJobAndReleaseUnsharedDocument(jobId)
      for key in [jobId,"\(jobId):docx"] { try self.store.deleteExport(key) }
      self.removeOwnedPdfArtifacts(jobId:jobId)
      if let source,URL(fileURLWithPath:source).standardizedFileURL.deletingLastPathComponent()==AppPaths.documents.standardizedFileURL {
        try? FileManager.default.removeItem(atPath:source)
        let documentId=job["documentId"] as? String ?? ""
        self.removeThumbnails(prefix:documentId+"_")
      }
      return NSNull()
    }
  }

  @objc func deleteDocument(_ documentId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){run(resolve,reject,"DOCUMENT_DELETE_FAILED"){let source=try self.store.deleteUntranslatedDocument(documentId);guard URL(fileURLWithPath:source).standardizedFileURL.deletingLastPathComponent()==AppPaths.documents.standardizedFileURL else{throw self.error("The imported document is outside private application storage.")};try? FileManager.default.removeItem(atPath:source);for item in (try? FileManager.default.contentsOfDirectory(at:AppPaths.thumbnails,includingPropertiesForKeys:nil)) ?? [] where item.lastPathComponent.hasPrefix(documentId+"_"){try? FileManager.default.removeItem(at:item)};return NSNull()}}
  @objc func getJob(_ jobId:String,pageIndex:NSNumber,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"JOB_READ_FAILED") {
      guard var job=try self.store.job(jobId) else { throw self.error("Translation job was not found.") }
      let sourceCount=(job["totalPages"] as? NSNumber)?.intValue ?? 0
      var layout:VerifiedPdfExport?
      if (job["documentFormat"] as? String ?? "pdf") != "docx",["completed","partial"].contains(job["status"] as? String ?? "") {
        layout=try? self.verifiedPdfExport(jobId,sourcePageCount:sourceCount)
        if layout==nil {
          _=try self.buildExport(jobId)
          layout=try self.verifiedPdfExport(jobId,sourcePageCount:sourceCount)
        }
        job=try self.store.job(jobId) ?? job
      }
      let rows=try self.store.pageRows(jobId)
      if let layout {
        let outputIndex=pageIndex.intValue
        let pageRows=layout.receipt["pages"] as? [[String:Any]] ?? []
        let sourceIndex=(outputIndex>=0 && outputIndex<pageRows.count) ? (pageRows[outputIndex]["sourcePageIndex"] as? NSNumber)?.intValue : nil
        var selected:[String:Any]?
        if let sourceIndex,sourceIndex>=0,sourceIndex<sourceCount {selected=try self.store.page(jobId,index:sourceIndex)}
        return try jsonString(["job":job,"pages":rows,"selectedPage":selected as Any? ?? NSNull(),
          "pdfLayout":["layoutVersion":3,"mode":"preserve-source","outputPageCount":layout.outputPageCount,"sourcePageCount":sourceCount,
            "pages":pageRows.map { ["outputPageIndex":$0["outputPageIndex"] ?? NSNull(),"sourcePageIndex":$0["sourcePageIndex"] ?? NSNull(),"sourceOutputPageIndex":$0["sourceOutputPageIndex"] ?? NSNull()] }]])
      }
      let selected=pageIndex.intValue>=0 && pageIndex.intValue<sourceCount ? try self.store.page(jobId,index:pageIndex.intValue) : nil
      return try jsonString(["job":job,"pages":rows,"selectedPage":selected as Any? ?? NSNull()])
    }
  }
  @objc func renderPage(_ documentId:String,pageIndex:NSNumber,width:NSNumber,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"THUMBNAIL_FAILED") { guard pageIndex.intValue>=0,(64...2048).contains(width.intValue),let path=try self.store.documentPath(documentId) else { throw self.error("The document, page, or preview size is invalid.") };try? FileManager.default.createDirectory(at:AppPaths.thumbnails,withIntermediateDirectories:true);let url=AppPaths.thumbnails.appendingPathComponent("\(documentId)_\(pageIndex.intValue)_\(width.intValue).png");if !FileManager.default.fileExists(atPath:url.path){let data:Data?;if try self.store.documentFormat(documentId)=="docx"{let preview=try self.ensureDocxPreview(source:URL(fileURLWithPath:path),translatedDocx:nil,key:documentId);let info=try jsonObject(TranslateCoreBridge.inspect(preview.path));try self.store.updatePreviewPageCount(documentId,(info["pageCount"] as? NSNumber)?.intValue ?? 0);data=TranslateCoreBridge.renderPNG(preview.path,index:pageIndex.intValue,width:width.intValue)}else{data=TranslateCoreBridge.renderPNG(path,index:pageIndex.intValue,width:width.intValue)};guard let data else { throw self.error("The page preview could not be rendered.") };try data.write(to:url,options:.atomic)};return url.absoluteString }
  }
  @objc func renderTranslatedPage(_ jobId:String,pageIndex:NSNumber,width:NSNumber,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"TRANSLATED_THUMBNAIL_FAILED") {
      let index=pageIndex.intValue,previewWidth=width.intValue
      guard index>=0,(64...2048).contains(previewWidth),let source=try self.store.jobSource(jobId) else {
        throw self.error("The translated page, job, or preview size is unavailable.")
      }
      if (try self.store.job(jobId)?["documentFormat"] as? String)=="docx" {
        guard let job=try self.store.job(jobId),let sourceURL=try self.store.jobSource(jobId) else{throw self.error("The Word document is unavailable.")}
        let output=try self.buildDocxExport(jobId)
        let preview=try self.ensureDocxPreview(source:URL(fileURLWithPath:sourceURL),translatedDocx:output.url,key:jobId+"-translated")
        guard let image=TranslateCoreBridge.renderPNG(preview.path,index:index,width:previewWidth) else{throw self.error("The translated Word page preview could not be rendered.")}
        try? FileManager.default.createDirectory(at:AppPaths.thumbnails,withIntermediateDirectories:true)
        let thumbnail=AppPaths.thumbnails.appendingPathComponent("translated_\(jobId)_\(index)_\(previewWidth).png")
        try image.write(to:thumbnail,options:.atomic);_ = job;return thumbnail.absoluteString
      }
      if let job=try self.store.job(jobId),["completed","partial"].contains(job["status"] as? String ?? "") {
        guard let total=(job["totalPages"] as? NSNumber)?.intValue,
          let cached=try self.verifiedPdfExport(jobId,sourcePageCount:total) else{throw self.error("The verified translated PDF and page map are unavailable.")}
        guard index<cached.outputPageCount else{throw self.error("The translated output page is outside the verified PDF layout.")}
        try FileManager.default.createDirectory(at:AppPaths.thumbnails,withIntermediateDirectories:true)
        let thumbnail=AppPaths.thumbnails.appendingPathComponent("translated_v3_\(self.safeName(jobId))_\(cached.outputSha256)_\(index)_\(previewWidth).png")
        if !FileManager.default.fileExists(atPath:thumbnail.path) {
          guard let data=TranslateCoreBridge.renderPNG(cached.url.path,index:index,width:previewWidth) else{throw self.error("The saved translated page preview could not be rendered.")}
          try data.write(to:thumbnail,options:.atomic)
        }
        return thumbnail.absoluteString
      }
      guard let savedPage=try self.store.page(jobId,index:index),let blocks=savedPage["blocks"] else {
        throw self.error("The translated page, job, or preview size is unavailable.")
      }
      let pageDirectory=AppPaths.pages.appendingPathComponent(jobId,isDirectory:true)
      try FileManager.default.createDirectory(at:pageDirectory,withIntermediateDirectories:true)
      let shard=pageDirectory.appendingPathComponent("translated-preview-\(index).pdf")
      guard let pageBlocks=blocks as? [[String:Any]] else{throw self.error("The saved translated page has invalid block metadata.")}
      let request:[String:Any]=["layoutVersion":3,"mode":"preserve-source","sourcePageIndex":index,"blocks":blocks]
      let exportResult=try jsonObject(TranslateCoreBridge.exportPage(source,index:index,blocksJSON:try jsonString(request),destination:shard.path,fontPath:self.fontPath(),cjkFontPath:self.cjkFontPath()))
      if let message=exportResult["error"] as? String { throw self.error(message) }
      let placements=try self.validateSourcePageExportResult(exportResult,sourcePageIndex:index)
      guard FileManager.default.fileExists(atPath:shard.path) else { throw self.error("The source-preserving page preview could not be created.") }
      try self.validateLocalPlacements(blocks:pageBlocks,outputPageCount:1,placements:placements,issues:exportResult["issues"] as? [[String:Any]] ?? [])
      let sourceGeometry=try jsonObject(TranslateCoreBridge.readPage(source,index:index))
      let previewGeometry=try jsonObject(TranslateCoreBridge.readPage(shard.path,index:0))
      guard sourceGeometry["error"]==nil,previewGeometry["error"]==nil,self.samePageGeometry(sourceGeometry,previewGeometry) else{
        throw self.error("The source-preserving page preview changed the original page geometry.")
      }
      try FileManager.default.createDirectory(at:AppPaths.thumbnails,withIntermediateDirectories:true)
      let thumbnail=AppPaths.thumbnails.appendingPathComponent("translated_\(jobId)_\(index)_\(previewWidth).png")
      guard let data=TranslateCoreBridge.renderPNG(shard.path,index:0,width:previewWidth) else { throw self.error("The translated page preview could not be rendered.") }
      try data.write(to:thumbnail,options:.atomic)
      return thumbnail.absoluteString
    }
  }
  @objc func renderDocxPreview(_ documentId:String,jobId:String?,translated:Bool,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"DOCX_PREVIEW_FAILED") {
      guard try self.store.documentFormat(documentId)=="docx",let path=try self.store.documentPath(documentId) else{throw self.error("The Word document is unavailable.")}
      var translatedURL:URL?
      var key=documentId
      if translated {
        guard let jobId,let job=try self.store.job(jobId),job["documentId"] as? String==documentId else{throw self.error("Choose a translated Word document first.")}
        translatedURL=try self.buildDocxExport(jobId).url;key="\(jobId)-translated"
      }
      let pdf=try self.ensureDocxPreview(source:URL(fileURLWithPath:path),translatedDocx:translatedURL,key:key)
      let inspected=try jsonObject(TranslateCoreBridge.inspect(pdf.path))
      guard inspected["error"]==nil,let pages=(inspected["pageCount"] as? NSNumber)?.intValue,pages>0 else{throw self.error("The Word preview PDF failed verification.")}
      try self.store.updatePreviewPageCount(documentId,pages)
      return try jsonString(["uri":pdf.absoluteString,"pageCount":pages,"format":"pdf"])
    }
  }
  @objc func previewJob(_ jobId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    work.async { do { let result: (id:String,url:URL,name:String);if (try self.store.job(jobId)?["documentFormat"] as? String)=="docx" { guard try self.store.job(jobId) != nil,let source=try self.store.jobSource(jobId) else{throw self.error("Word job unavailable.")};let docx=try self.buildDocxExport(jobId);let pdf=try self.ensureDocxPreview(source:URL(fileURLWithPath:source),translatedDocx:docx.url,key:jobId+"-translated");result=(jobId,pdf,docx.name.replacingOccurrences(of:"docx$",with:"pdf",options:.regularExpression)) } else { result=try self.buildExport(jobId) };let payload=try jsonString(["id":result.id,"uri":result.url.absoluteString,"displayName":result.name]);DispatchQueue.main.async { resolve(payload) } } catch { DispatchQueue.main.async { reject("PREVIEW_FAILED",error.localizedDescription,error) } } }
  }
  @objc func exportJob(_ jobId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    exportDocument(jobId,format:"pdf",resolver:resolve,rejecter:reject)
  }
  @objc func exportDocument(_ jobId:String,format:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    work.async { do { guard ["pdf","docx"].contains(format) else{throw self.error("Choose PDF or DOCX as the export format.")};let result:(id:String,url:URL,name:String);if format=="docx" {result=try self.buildDocxExport(jobId)}else{result=try self.buildExport(jobId)};DispatchQueue.main.async { self.presentExportFile(id:result.id,url:result.url,name:result.name,mime:format=="docx" ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document":"application/pdf",resolve:resolve,reject:reject) } } catch { DispatchQueue.main.async { reject("EXPORT_FAILED",error.localizedDescription,error) } } }
  }
  @objc func shareExport(_ exportId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    work.async { do {
      guard let job=try self.store.job(exportId) else{throw self.error("The translation job is unavailable.")}
      let url:URL
      if (job["documentFormat"] as? String)=="docx" { url=try self.buildExport(exportId).url }
      else {
        guard let total=(job["totalPages"] as? NSNumber)?.intValue,
          let verified=try self.verifiedPdfExport(exportId,sourcePageCount:total) else{throw self.error("The verified translated PDF is unavailable.")}
        url=verified.url
      }
      DispatchQueue.main.async { guard let presenter=self.topPresenter() else{reject("SHARE_FAILED","Open the app before sharing the translated PDF.",nil);return};let activity=UIActivityViewController(activityItems:[url],applicationActivities:nil);if let pop=activity.popoverPresentationController{pop.sourceView=presenter.view;pop.sourceRect=CGRect(x:presenter.view.bounds.midX,y:presenter.view.bounds.midY,width:1,height:1)};presenter.present(activity,animated:true){resolve(nil)} }
    } catch { DispatchQueue.main.async {reject("SHARE_FAILED",error.localizedDescription,error)} } }
  }
  @objc func openPdf(_ documentId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    DispatchQueue.main.async { guard let path=try? self.store.documentPath(documentId),FileManager.default.fileExists(atPath:path),let presenter=self.topPresenter() else { reject("OPEN_PDF_FAILED","The document is unavailable.",nil);return };presenter.present(PdfPreviewController(file:URL(fileURLWithPath:path)),animated:true){resolve(nil)} }
  }
  @objc func setOnboarding(_ stateJson:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"ONBOARDING_SAVE_FAILED"){guard let state=try JSONSerialization.jsonObject(with:Data(stateJson.utf8)) as? [String:Any],let version=(state["version"] as? NSNumber)?.intValue,let page=(state["page"] as? NSNumber)?.intValue,let done=state["done"] as? Bool,version>0,(0...3).contains(page) else { throw self.error("Onboarding state is invalid.") };try self.store.setSetting("onboarding_version",String(version));try self.store.setSetting("onboarding_page",String(page));try self.store.setSetting("onboarding_done",String(done));return try jsonString(["version":version,"page":page,"done":done]) }
  }
  @objc func setSettings(_ settingsJson:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"SETTINGS_SAVE_FAILED") {
      guard let settings=try JSONSerialization.jsonObject(with:Data(settingsJson.utf8)) as? [String:Any] else { throw self.error("Settings are invalid.") }
      let seen:Bool?=try self.optionalValue(settings,key:"notificationExplanationShown",as:Bool.self)
      let theme:String?=try self.optionalValue(settings,key:"theme",as:String.self)
      if let theme, !["system","light","dark"].contains(theme) { throw self.error("Theme selection is invalid.") }
      let language:String?=try self.optionalValue(settings,key:"language",as:String.self)
      if let language, language != "system" && language.range(of:"^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$",options:.regularExpression)==nil { throw self.error("Language selection is invalid.") }
      let savedPair=try self.store.languagePreferences()
      let source:String=try self.optionalValue(settings,key:"sourceLanguage",as:String.self) ?? savedPair.sourceLanguage
      let target:String=try self.optionalValue(settings,key:"targetLanguage",as:String.self) ?? savedPair.targetLanguage
      if settings["sourceLanguage"] != nil || settings["targetLanguage"] != nil { _=try TranslationLanguage.pair(source:source,target:target) }
      let modelId:String?=try self.optionalValue(settings,key:"selectedModelId",as:String.self)
      if let modelId { _=try ModelCatalog.get(modelId) }
      let engine=settings["selectedEngine"] as? [String:Any]
      if settings["selectedEngine"] != nil && engine == nil { throw self.error("The selected translation engine is invalid.") }
      if let engine {
        guard let kind=engine["kind"] as? String,let id=engine["id"] as? String,
              (kind=="online" && id=="mymemory") || (kind=="local" && (try? ModelCatalog.get(id)) != nil) else { throw self.error("The selected translation engine is not supported.") }
      }
      var appRating:Int?
      if let raw=settings["appRating"] {
        guard let number=raw as? NSNumber,CFGetTypeID(number) != CFBooleanGetTypeID(),number.doubleValue.rounded(.towardZero)==number.doubleValue,(0...5).contains(number.intValue) else { throw self.error("The app rating must be an integer from 0 to 5.") }
        appRating=number.intValue
      }
      let librarySort:String?=try self.optionalValue(settings,key:"librarySort",as:String.self)
      if let librarySort, !["recent","color"].contains(librarySort) { throw self.error("The library sort mode is not supported.") }
      return try self.store.transaction {
        if let seen { try self.store.setSetting("notificationExplanationShown",seen ? "true":"false") }
        if let theme { try self.store.setSetting("theme",theme) }
        if let language { try self.store.setSetting("language",language) }
        if settings["sourceLanguage"] != nil || settings["targetLanguage"] != nil { try self.store.setLanguagePreferences(sourceLanguage:source,targetLanguage:target) }
        if let modelId { _=try self.store.setSelectedModelId(modelId) }
        if let engine { _=try self.store.setSelectedEngine(engine) }
        let appPreferences=try self.store.setAppPreferences(appRating:appRating,librarySort:librarySort)
        return try jsonString(["notificationExplanationShown":try self.store.setting("notificationExplanationShown",fallback:"false") == "true","theme":try self.store.setting("theme",fallback:"system"),"language":try self.store.setting("language",fallback:"system"),"sourceLanguage":source,"targetLanguage":target,"selectedModelId":try self.store.selectedModelId(),"selectedEngine":try self.store.selectedEngine(),"appRating":appPreferences["appRating"] ?? 0,"librarySort":appPreferences["librarySort"] ?? "recent"])
      }
    }
  }
  @objc func saveGlossaryEntry(_ entryJson:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"GLOSSARY_SAVE_FAILED"){guard let item=try JSONSerialization.jsonObject(with:Data(entryJson.utf8)) as? [String:Any],let id=item["id"] as? String,let source=item["source"] as? String,let target=item["target"] as? String,let sourceLanguage=item["sourceLanguage"] as? String,let targetLanguage=item["targetLanguage"] as? String,!source.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty,!target.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty else { throw self.error("Both glossary terms and their language pair are required.") };return try jsonString(self.store.saveGlossary(id,source:source,target:target,sourceLanguage:sourceLanguage,targetLanguage:targetLanguage)) }
  }
  @objc func deleteGlossaryEntry(_ entryId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){run(resolve,reject,"GLOSSARY_DELETE_FAILED"){try self.store.deleteGlossary(entryId);return NSNull()}}

  private func run(_ resolve:@escaping RCTPromiseResolveBlock,_ reject:@escaping RCTPromiseRejectBlock,_ code:String,_ body:@escaping() throws->Any){work.async{do{let result=try body();DispatchQueue.main.async{resolve(result)}}catch{DispatchQueue.main.async{reject(code,error.localizedDescription,error)}}}}
  private func runInForeground(_ resolve:@escaping RCTPromiseResolveBlock,_ reject:@escaping RCTPromiseRejectBlock,_ code:String,_ message:String,_ body:@escaping() throws->Any){
    DispatchQueue.main.async {
      guard UIApplication.shared.applicationState == .active else { reject(code,message,self.error(message));return }
      self.run(resolve,reject,code,body)
    }
  }
  private func optionalValue<T>(_ settings:[String:Any],key:String,as type:T.Type)throws->T?{
    guard let value=settings[key] else{return nil}
    guard let typed=value as? T else{throw error("The setting \(key) has an invalid value.")}
    return typed
  }
  private func notificationSnapshot(_ settings:UNNotificationSettings)->[String:Any]{
    let requested=UserDefaults.standard.bool(forKey:"dev.kigya.translatepdf.notificationPermissionRequested")
    let status:String
    switch settings.authorizationStatus {
    case .authorized,.provisional,.ephemeral: status="granted"
    case .denied: status="denied"
    case .notDetermined: status=requested ? "denied" : "not_determined"
    @unknown default: status="unavailable"
    }
    return ["status":status,"requestedBefore":requested,"canRequest":status == "not_determined" && !requested]
  }
  private func licenseBundle()->Bundle?{
    guard let url=Bundle.main.url(forResource:"TranslatePDFLicenses",withExtension:"bundle") else{return nil}
    return Bundle(url:url)
  }
  private struct LicenseNoticeEntry: Decodable {
    let id: String
    let title: String
    let platforms: [String]
    let files: [String]
  }
  private struct LicenseNoticeCatalog: Decodable {
    let version: Int
    let notices: [LicenseNoticeEntry]
  }
  private func licenseNoticeEntries() throws -> [LicenseNoticeEntry] {
    guard let bundle=licenseBundle(),let url=bundle.url(forResource:"catalog",withExtension:"json") else {
      throw error("The required license catalog is unavailable.")
    }
    let catalog=try JSONDecoder().decode(LicenseNoticeCatalog.self,from:Data(contentsOf:url))
    guard catalog.version == 1,!catalog.notices.isEmpty,
      Set(catalog.notices.map(\.id)).count == catalog.notices.count,
      catalog.notices.allSatisfy({ !$0.id.isEmpty && !$0.title.isEmpty && !$0.files.isEmpty && $0.files.allSatisfy(isSafeLicensePath) }) else {
      throw error("The required license catalog is invalid.")
    }
    return catalog.notices.filter { $0.platforms.contains("ios") }
  }
  private func isSafeLicensePath(_ path:String)->Bool{
    guard !path.isEmpty,!path.hasPrefix("/"),!path.contains("\\") else{return false}
    return path.split(separator:"/",omittingEmptySubsequences:false).allSatisfy{!$0.isEmpty && $0 != "." && $0 != ".."}
  }
  private func error(_ message:String)->NSError{NSError(domain:"PdfTranslator",code:100,userInfo:[NSLocalizedDescriptionKey:message])}
  private func recoverInterruptedJobs() throws {
    for row in try store.query("SELECT id,status,total_pages,completed_pages FROM jobs WHERE status IN ('running','queued')") {
      guard let id=row["id"] as? String else{continue}
      let total=(row["total_pages"] as? Int64).map(Int.init) ?? 0
      let completed=(row["completed_pages"] as? Int64).map(Int.init) ?? 0
      try store.updateJob(id,status:"paused",completed:min(completed,max(0,total-1)),error:"The app stopped before a verified translated PDF was saved. Saved pages are available to resume.")
    }
    // Older builds marked jobs complete before creating their PDF. Demote those jobs until a
    // verified export is rebuilt from their durable page checkpoints.
    for row in try store.query("SELECT j.id,j.status,j.total_pages,d.format FROM jobs j JOIN documents d ON d.id=j.document_id WHERE j.status IN ('completed','partial')") {
      guard let id=row["id"] as? String else{continue}
      let total=(row["total_pages"] as? Int64).map(Int.init) ?? 0
      if (row["format"] as? String)=="docx" {
        if (try? verifiedDocxExport(id,expectedSegments:total)) != nil { continue }
        try store.deleteExport("\(id):docx")
        try store.updateJob(id,status:"paused",stage:"queued",completed:max(0,total-1),error:"This saved Word job has no verified DOCX export yet. Resume to rebuild it from its saved segments.")
        continue
      }
      guard (try? verifiedPdfExport(id,sourcePageCount:total)) == nil else{continue}
      try store.deleteExport(id)
      try store.updateJob(id,status:"paused",stage:"queued",completed:max(0,total-1),error:"This saved job has no verified translated PDF yet. Resume to finish PDF assembly from its saved pages.")
    }
  }
  private func requiresInference(_ jobId:String,totalPages:Int)throws->Bool {
    guard totalPages>0 else{return true}
    let job=try store.job(jobId)
    let isGemmaPdf=(job?["documentFormat"] as? String ?? "pdf") != "docx" &&
      (job?["modelProfile"] as? String ?? "")=="translategemma"
    let saved=try store.pageRows(jobId)
    let byIndex=Dictionary(uniqueKeysWithValues:saved.compactMap { row -> (Int,String)? in
      guard let index=(row["index"] as? NSNumber)?.intValue,let status=row["status"] as? String else{return nil}
      return(index,status)
    })
    for index in 0..<totalPages {
      if ["completed","partial"].contains(byIndex[index] ?? "") {
        if isGemmaPdf,let page=try store.pageCheckpoint(jobId,index:index),
          let blocks=page["blocks"] as? [[String:Any]],
          blocks.contains(where:Self.gemmaCheckpointRequiresInference) {return true}
        continue
      }
      guard let page=try store.page(jobId,index:index),let blocks=page["blocks"] as? [[String:Any]] else{return true}
      if isGemmaPdf,blocks.contains(where:Self.gemmaCheckpointRequiresInference){return true}
      for block in blocks {
        let source=(block["sourceText"] as? String ?? "").trimmingCharacters(in:.whitespacesAndNewlines)
        guard !source.isEmpty else{continue}
        let translated=(block["translatedText"] as? String ?? "").trimmingCharacters(in:.whitespacesAndNewlines)
        if translated.isEmpty || (block["status"] as? String) != "translated" {return true}
      }
    }
    return false
  }
  private func prepareSavedGemmaPageArtifacts(_ jobId:String,totalPages:Int)throws {
    for row in try store.pageRows(jobId) {
      guard let index=(row["index"] as? NSNumber)?.intValue,index>=0,index<totalPages,
        let pageStatus=row["status"] as? String,["completed","partial"].contains(pageStatus),
        let page=try store.pageCheckpoint(jobId,index:index),let saved=page["blocks"] as? [[String:Any]] else{continue}
      let prepared=Self.restoringPdfCheckpointResults(fresh:saved,saved:saved,online:false,gemma:true)
      let needsRetry=prepared.contains{($0["gemmaPromptArtifactRepairPendingRevision"] as? String)==Self.gemmaSourceBoundaryRepairRevision}
      let exhausted=prepared.contains{($0["error"] as? String)==Self.gemmaPromptArtifactError}
      let nextStatus=needsRetry ? "running" : (exhausted ? "partial" : pageStatus)
      guard nextStatus != pageStatus || !Self.jsonObjectsEqual(saved,prepared) else{continue}
      try store.savePage(jobId:jobId,index:index,status:nextStatus,blocks:try jsonString(prepared),error:page["error"] as? String)
    }
    let completed=try store.pageRows(jobId).filter{["completed","partial"].contains($0["status"] as? String ?? "")}.count
    try store.updateJob(jobId,completed:min(completed,max(0,totalPages-1)))
  }
  private func importDocument(_ source:URL)throws->[String:Any]{
    let scoped=source.startAccessingSecurityScopedResource();defer{if scoped{source.stopAccessingSecurityScopedResource()}}
    guard let size=try? source.resourceValues(forKeys:[.fileSizeKey]).fileSize,size>0,size<=100*1024*1024 else{throw error("The selected document could not be read or exceeds the 100 MB import limit.")}
    let ext=source.pathExtension.lowercased()
    guard ext == "pdf" || ext == "docx" else {
      if ["doc","docm","dotm","dotx"].contains(ext) { throw error("This Word format is not supported. Save it as a .docx file and import it again.") }
      throw error("Choose a PDF or .docx Word document.")
    }
    let format=ext == "docx" ? "docx":"pdf"
    let free=try AppPaths.documents.resourceValues(forKeys:[.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
    guard Int64(size)<free else{throw error("Not enough free space to import this document into private app storage.")}
    let id=UUID().uuidString.lowercased(),incoming=AppPaths.documents.appendingPathComponent("\(id).incoming.\(format)"),target=AppPaths.documents.appendingPathComponent("\(id).\(format)")
    do {
      let input=try FileHandle(forReadingFrom:source);defer{try? input.close()};FileManager.default.createFile(atPath:incoming.path,contents:nil)
      let output=try FileHandle(forWritingTo:incoming);defer{try? output.close()}
      var header=Data();while true{let chunk=try input.read(upToCount:1024*1024) ?? Data();if chunk.isEmpty{break};if header.count<5{header.append(chunk.prefix(5-header.count))};try output.write(contentsOf:chunk)}
      try output.synchronize()
      let inspected:[String:Any]
      let countKey:String
      if format == "pdf" {
        guard String(data:header,encoding:.ascii)?.hasPrefix("%PDF-")==true else{throw error("The selected file content is not a PDF.")}
        inspected=try jsonObject(TranslateCoreBridge.inspect(incoming.path));countKey="pageCount"
      } else {
        guard header.count>=4,Array(header.prefix(4))==[0x50,0x4b,0x03,0x04] else{throw error("The selected file is not a DOCX package. Legacy .doc, macro-enabled, encrypted, or renamed files are not supported.")}
        inspected=try jsonObject(TranslateCoreBridge.inspectDocx(incoming.path));countKey="segmentCount"
      }
      if let message=inspected["error"] as? String{throw error(message)}
      let warnings=normalizedWarnings(inspected["warnings"])
      let pages=(inspected[countKey] as? NSNumber)?.intValue ?? 0;guard pages>0,pages<=10_000 else{throw error("The document has an unsupported number of pages or text segments.")}
      try FileManager.default.moveItem(at:incoming,to:target)
      let sourceDigest=try sha256(at:target)
      return try store.saveDocument(id:id,name:safeName(source.lastPathComponent),path:target.path,pages:pages,sourceSHA256:sourceDigest,format:format,warnings:warnings)
    }catch{try? FileManager.default.removeItem(at:incoming);throw error}
  }
  private func safeName(_ value:String)->String{let trimmed=value.trimmingCharacters(in:.whitespacesAndNewlines);let sanitized=trimmed.replacingOccurrences(of:"[/\\\\\\0]",with:"_",options:.regularExpression);return sanitized.isEmpty ? "document" : String(sanitized.prefix(120))}
  private func topPresenter()->UIViewController?{guard let scene=UIApplication.shared.connectedScenes.compactMap({$0 as? UIWindowScene}).first(where:{$0.activationState == .foregroundActive}),let root=scene.windows.first(where:{$0.isKeyWindow})?.rootViewController else{return nil};var top=root;while let next=top.presentedViewController{top=next};return top}
  private func finishImportError(_ error:Error){DispatchQueue.main.async{self.pendingImportReject?("PDF_IMPORT_FAILED",error.localizedDescription,error);self.pendingImport=nil;self.pendingImportReject=nil}}
  private func buildExport(_ jobId:String,allowRunning:Bool=false)throws->(id:String,url:URL,name:String){
    guard let job=try store.job(jobId) else{throw error("Translation job was not found.")}
    let status=job["status"] as? String ?? ""
    let sourceCount=(job["totalPages"] as? NSNumber)?.intValue ?? 0
    guard sourceCount>0 else{throw error("The translation contains no exportable pages.")}
    if !allowRunning && !["completed","partial"].contains(status){throw error("Wait for the translation to finish before exporting.")}
    guard let source=try store.jobSource(jobId) else{throw error("The source document is unavailable.")}
    if (job["documentFormat"] as? String)=="docx" {
      guard !allowRunning || ["running","completed","partial"].contains(status) else{throw error("Wait for the Word translation to finish before exporting PDF.")}
      let docx=try buildDocxExport(jobId)
      let preview=try ensureDocxPreview(source:URL(fileURLWithPath:source),translatedDocx:docx.url,key:jobId+"-translated")
      let pdf=AppPaths.exports.appendingPathComponent("\(jobId).pdf")
      let pdfName=docx.name.replacingOccurrences(of:"\\.docx$",with:".pdf",options:.regularExpression)
      let previewInfo=try jsonObject(TranslateCoreBridge.inspect(preview.path))
      guard let previewPages=(previewInfo["pageCount"] as? NSNumber)?.intValue,previewPages>0 else{throw error("The generated Word PDF failed verification.")}
      if let cached=try verifiedExport(jobId,expectedPages:previewPages){return(jobId,cached.url,cached.name)}
      let pending=AppPaths.exports.appendingPathComponent("\(jobId).pending.pdf")
      defer { try? FileManager.default.removeItem(at:pending) }
      try? FileManager.default.removeItem(at:pending)
      try FileManager.default.copyItem(at:preview,to:pending)
      let inspected=try jsonObject(TranslateCoreBridge.inspect(pending.path));guard inspected["error"]==nil,let pages=(inspected["pageCount"] as? NSNumber)?.intValue,pages>0 else{throw error("The generated Word PDF failed verification.")}
      let digest=try sha256(at:pending)
      try commitFinalizedExport(jobId,requiresProcessingOwner:status=="running") {
        try? FileManager.default.removeItem(at:pdf)
        try FileManager.default.moveItem(at:pending,to:pdf)
        try store.saveExport(jobId,path:pdf.path,name:pdfName,sha256:digest)
      }
      return(jobId,pdf,pdfName)
    }

    let sourceURL=URL(fileURLWithPath:source).standardizedFileURL
    guard FileManager.default.fileExists(atPath:sourceURL.path) else{throw error("The source document is unavailable.")}
    let sourceInfo=try jsonObject(TranslateCoreBridge.inspect(sourceURL.path))
    guard sourceInfo["error"]==nil,nativePageCountMatches(sourceInfo,expected:sourceCount) else{
      throw error("The imported PDF page structure no longer matches this translation checkpoint.")
    }
    if let cached=try? verifiedPdfExport(jobId,sourcePageCount:sourceCount){return(jobId,cached.url,cached.name)}

    let sourceBytes=UInt64(max(0,(try? sourceURL.resourceValues(forKeys:[.fileSizeKey]).fileSize) ?? 0))
    let availableDisk=try AppPaths.exports.resourceValues(forKeys:[.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
    let outputEstimate=sourceBytes>UInt64.max/3 ? UInt64.max : max(sourceBytes*3,16*1024*1024)
    guard availableDisk>=0,outputEstimate<=UInt64.max-(64*1024*1024),UInt64(availableDisk)>=outputEstimate+(64*1024*1024) else{throw error("Free storage is below the estimated space needed to assemble and verify the translated PDF.")}

    if allowRunning {try store.updateJob(jobId,status:"running",stage:"exporting",completed:min((job["completedPages"] as? NSNumber)?.intValue ?? 0,max(0,sourceCount-1)),clearError:true)}
    let generation=AppPaths.exports.appendingPathComponent("\(safeName(jobId))-\(UUID().uuidString.lowercased())",isDirectory:true)
    let temporary=generation.appendingPathComponent("translated.pdf.incoming")
    let output=generation.appendingPathComponent("translated.pdf")
    let receiptURL=generation.appendingPathComponent("layout-receipt.json")
    var keepGeneration=false
    defer { if !keepGeneration { try? FileManager.default.removeItem(at:generation) } }
    try FileManager.default.createDirectory(at:generation,withIntermediateDirectories:true)
    var shardDescriptors=[[String:Any]]()
    var placementsBySource=[[[String:Any]]]()
    var outputNeedsReview=false
    let sourceSHA=try sha256(at:sourceURL)

    for sourceIndex in 0..<sourceCount {
      if allowRunning {try checkFinalizationControl(jobId)}
      guard let page=try store.page(jobId,index:sourceIndex),let originalBlocks=page["blocks"] as? [[String:Any]],
        ["completed","partial"].contains(page["status"] as? String ?? "") else{
        throw error("Page \(sourceIndex+1) has no completed saved translation checkpoint.")
      }
      var blocks=originalBlocks
      var pageWasPartial=(page["status"] as? String)=="partial"
      var pageMessages=[String]()
      let shard=generation.appendingPathComponent("source-\(sourceIndex).pdf")
      let snapshotURL=generation.appendingPathComponent("source-\(sourceIndex)-blocks.json")
      let request:[String:Any]=["layoutVersion":3,"mode":"preserve-source","sourcePageIndex":sourceIndex,"blocks":blocks]
      let result=try jsonObject(TranslateCoreBridge.exportPage(sourceURL.path,index:sourceIndex,blocksJSON:try jsonString(request),destination:shard.path,fontPath:fontPath(),cjkFontPath:cjkFontPath()))
      if let message=result["error"] as? String{throw error(message)}
      let placements=try validateSourcePageExportResult(result,sourcePageIndex:sourceIndex)
      let issues=result["issues"] as? [[String:Any]] ?? []
      try validateLocalPlacements(blocks:blocks,outputPageCount:1,placements:placements,issues:issues)
      for issue in issues {
        let blockId=issue["blockId"] as? String ?? ""
        let reason=issue["reason"] as? String ?? "review_required"
        let message=issue["message"] as? String ?? "This text block needs review."
        if !message.isEmpty{pageMessages.append(message)}
        guard !blockId.isEmpty,let blockIndex=blocks.firstIndex(where:{($0["id"] as? String)==blockId}) else{continue}
        var current=blocks[blockIndex]
        var prior=current["exportIssues"] as? [[String:Any]] ?? []
        if !prior.contains(where:{($0["reason"] as? String)==reason && ($0["message"] as? String)==message}){prior.append(["reason":reason,"message":message]);current["exportIssues"]=prior}
        if (current["status"] as? String) != "failed"{current["status"]="partial"}
        blocks[blockIndex]=current;pageWasPartial=true
      }
      if (result["partial"] as? Bool)==true || ((result["partialBlocks"] as? NSNumber)?.intValue ?? 0)>0{pageWasPartial=true}
      if let previous=page["error"] as? String,!previous.isEmpty{pageMessages.insert(previous,at:0)}
      let pageError=Array(NSOrderedSet(array:pageMessages)).compactMap{$0 as? String}.joined(separator:" ")
      let blockMetadataChanged=try jsonString(blocks) != jsonString(originalBlocks)
      if blockMetadataChanged || pageWasPartial != ((page["status"] as? String)=="partial") || pageError != (page["error"] as? String ?? ""){
        try store.savePage(jobId:jobId,index:sourceIndex,status:pageWasPartial ? "partial":"completed",blocks:try jsonString(blocks),error:pageError.isEmpty ? nil:pageError)
      }
      let snapshot:[String:Any]=["sourcePageIndex":sourceIndex,"blocks":blocks]
      try Data(jsonString(snapshot).utf8).write(to:snapshotURL,options:.atomic)
      let inspection=try jsonObject(TranslateCoreBridge.inspect(shard.path))
      guard inspection["error"]==nil,nativePageCountMatches(inspection,expected:1) else{
        throw error("The source-preserving shard for page \(sourceIndex+1) failed page-count verification.")
      }
      let sourceGeometry=try jsonObject(TranslateCoreBridge.readPage(sourceURL.path,index:sourceIndex))
      let shardGeometry=try jsonObject(TranslateCoreBridge.readPage(shard.path,index:0))
      guard sourceGeometry["error"]==nil,shardGeometry["error"]==nil,
        samePageGeometry(sourceGeometry,shardGeometry) else{throw error("The source-preserving shard changed page geometry on page \(sourceIndex+1).")}
      shardDescriptors.append(["sourcePageIndex":sourceIndex,"expectedPageCount":1,"path":shard.path,"blocksPath":snapshotURL.path])
      placementsBySource.append(placements)
      outputNeedsReview=outputNeedsReview || pageWasPartial
    }

    if allowRunning {try checkFinalizationControl(jobId)}
    let checkpoint=try pdfCheckpointDigest(jobId,sourcePageCount:sourceCount)
    let mergeRequest:[String:Any]=["layoutVersion":3,"mode":"preserve-source","sourcePath":sourceURL.path,
      "fontPath":fontPath(),"cjkFontPath":cjkFontPath(),"shards":shardDescriptors]
    let mergeResult=try jsonObject(TranslateCoreBridge.mergePDF(try jsonString(mergeRequest),destination:temporary.path))
    if let message=mergeResult["error"] as? String{throw error(message)}
    let mergeMap=try validateSourcePreservingMergeResult(mergeResult,sourcePageCount:sourceCount)
    let sourceRanges=mergeMap.sourceRanges,mergedPages=mergeMap.pages
    let inspected=try jsonObject(TranslateCoreBridge.inspect(temporary.path))
    guard inspected["error"]==nil,nativePageCountMatches(inspected,expected:sourceCount),
      try sha256(at:sourceURL)==sourceSHA else{throw error("The preserved PDF structure changed during finalization.")}
    for index in 0..<sourceCount {
      let original=try jsonObject(TranslateCoreBridge.readPage(sourceURL.path,index:index))
      let translated=try jsonObject(TranslateCoreBridge.readPage(temporary.path,index:index))
      guard original["error"]==nil,translated["error"]==nil,samePageGeometry(original,translated) else{
        throw error("Output page \(index+1) does not preserve the original PDF page geometry.")
      }
    }
    let outputSHA=try sha256(at:temporary)
    let receipt=try makePdfLayoutReceipt(sourcePageCount:sourceCount,sourceSHA:sourceSHA,checkpointDigest:checkpoint,outputSHA:outputSHA,
      sourceRanges:sourceRanges,mergedPages:mergedPages,placementsBySource:placementsBySource)
    try FileManager.default.moveItem(at:temporary,to:output)
    guard try sha256(at:output)==outputSHA else{throw error("The translated PDF digest changed during finalization.")}
    try JSONSerialization.data(withJSONObject:receipt,options:[.sortedKeys,.fragmentsAllowed]).write(to:receiptURL,options:.atomic)
    guard let persistedReceipt=try JSONSerialization.jsonObject(with:Data(contentsOf:receiptURL)) as? [String:Any] else{throw error("The saved PDF layout receipt could not be read back.")}
    try validatePdfLayoutReceipt(persistedReceipt,sourcePageCount:sourceCount,expectedSourceSHA:sourceSHA,expectedCheckpointDigest:checkpoint,expectedOutputSHA:outputSHA)
    let languages=try TranslationLanguage.pair(source:job["sourceLanguage"] as? String ?? "",target:job["targetLanguage"] as? String ?? "")
    let base=(job["documentName"] as? String ?? "translated.pdf").replacingOccurrences(of:"\\.pdf$",with:"",options:.regularExpression)
    let name="\(base)-\(languages.0.rawValue)-to-\(languages.1.rawValue).pdf"
    try commitFinalizedExport(jobId,requiresProcessingOwner:allowRunning) {
      try store.transaction {
        guard let latest=try store.job(jobId) else{throw error("The translation job was removed during PDF finalization.")}
        let latestStatus=latest["status"] as? String ?? ""
        if allowRunning {guard latestStatus=="running" else{throw PauseSignal()}}
        else {
          guard ["completed","partial"].contains(latestStatus) else{throw error("The completed translation changed during PDF finalization.")}
          if latestStatus=="completed" && outputNeedsReview{try store.updateJob(jobId,status:"partial")}
        }
        try store.saveExport(jobId,path:output.path,name:name,sha256:outputSHA)
      }
      var protected=URLResourceValues();protected.isExcludedFromBackup=true;var copy=output;try? copy.setResourceValues(protected)
    }
    keepGeneration=true
    return(jobId,output,name)
  }

  private func buildDocxExport(_ jobId:String)throws->(id:String,url:URL,name:String){
    guard let job=try store.job(jobId),job["documentFormat"] as? String == "docx",
          ["completed","partial","running"].contains(job["status"] as? String ?? ""),
          let source=try store.jobSource(jobId) else { throw error("The translated Word document is not ready to export.") }
    if ((job["engine"] as? [String:Any])?["kind"] as? String)=="online" { try store.validateOnlineSnapshot(jobId) }
    else { try store.validateJobForExport(jobId,model:ModelCatalog.get(job["modelId"] as? String ?? "")) }
    let count=(job["totalPages"] as? NSNumber)?.intValue ?? 0
    guard count>0 else { throw error("The Word translation contains no text segments.") }
    let active=(job["status"] as? String)=="running"
    let exportId="\(jobId):docx"
    if let cached=try store.export(exportId),let path=cached["path"] as? String,let name=cached["name"] as? String,
       let hash=cached["sha256"] as? String,!hash.isEmpty,FileManager.default.fileExists(atPath:path),
       (try? sha256(at:URL(fileURLWithPath:path)))==hash {
      let check=try jsonObject(TranslateCoreBridge.inspectDocx(path))
      if check["error"]==nil,((check["segmentCount"] as? NSNumber)?.intValue ?? 0)>0{return(jobId,URL(fileURLWithPath:path),name)}
    }
    var segments=[[String:Any]]()
    for index in 0..<count {
      if active { try checkFinalizationControl(jobId) }
      guard let page=try store.page(jobId,index:index),let blocks=page["blocks"] as? [[String:Any]] else { throw error("Word segment \(index+1) has no saved translation checkpoint.") }
      segments.append(["index":index,"blocks":blocks])
    }
    let generation=AppPaths.exports.appendingPathComponent("\(safeName(jobId))-\(UUID().uuidString.lowercased())",isDirectory:true)
    let pending=generation.appendingPathComponent("translated.incoming.docx")
    let destination=generation.appendingPathComponent("translated.docx")
    var keepGeneration=false
    defer { if !keepGeneration { try? FileManager.default.removeItem(at:generation) } }
    try FileManager.default.createDirectory(at:generation,withIntermediateDirectories:true)
    let result=try jsonObject(TranslateCoreBridge.exportDocx(source,segmentsJSON:try jsonString(segments),destination:pending.path))
    if let message=result["error"] as? String { throw error(message) }
    let warnings=mergeWarnings((job["warnings"] as? [[String:Any]] ?? [])+normalizedWarnings(result["warnings"]))
    try store.setJobWarnings(jobId,warnings)
    if active { try checkFinalizationControl(jobId) }
    let check=try jsonObject(TranslateCoreBridge.inspectDocx(pending.path))
    if let message=check["error"] as? String { throw error(message) }
    guard ((check["segmentCount"] as? NSNumber)?.intValue ?? 0)>0 else { throw error("The saved Word document contains no readable text segments.") }
    let digest=try sha256(at:pending)
    let languages=try TranslationLanguage.pair(source:job["sourceLanguage"] as? String ?? "",target:job["targetLanguage"] as? String ?? "")
    let base=(job["documentName"] as? String ?? "translated.docx").replacingOccurrences(of:"\\.docx$",with:"",options:.regularExpression)
    let name="\(base)-\(languages.0.rawValue)-to-\(languages.1.rawValue).docx"
    try commitFinalizedExport(jobId,requiresProcessingOwner:active) {
      try FileManager.default.moveItem(at:pending,to:destination)
      try store.transaction {
        guard let latest=try store.job(jobId) else { throw error("The translation job was removed during Word finalization.") }
        let latestStatus=latest["status"] as? String ?? ""
        if active {
          guard latestStatus=="running" else { throw PauseSignal() }
        } else {
          guard ["completed","partial"].contains(latestStatus) else { throw error("The completed Word translation changed during finalization.") }
        }
        try store.saveExport(exportId,path:destination.path,name:name,sha256:digest)
      }
    }
    keepGeneration=true
    return(jobId,destination,name)
  }

  private func commitFinalizedExport(_ jobId:String,requiresProcessingOwner:Bool=true,_ commit:() throws->Void)throws {
    beforeFinalizationCommitForTesting?("export")
    stateLock.lock();defer{stateLock.unlock()}
    if requiresProcessingOwner {
      guard activeJobID==jobId else{throw PauseSignal()}
      if cancelRequested { throw CancelSignal() }
      if pauseRequested { throw PauseSignal() }
    } else {
      guard let latest=try store.job(jobId),["completed","partial"].contains(latest["status"] as? String ?? "") else {
        throw error("The completed translation is no longer available for export.")
      }
    }
    try commit()
  }

  private func finishFinalization(_ jobId:String,total:Int,completed:Int,partial:Bool)throws->Bool {
    beforeFinalizationCommitForTesting?("terminal")
    let succeeded:Bool,runToken:UUID?
    do {
      stateLock.lock();defer{stateLock.unlock()}
      guard activeJobID==jobId else{throw PauseSignal()}
      runToken=activeRunToken
      succeeded = !cancelRequested && !pauseRequested
      if cancelRequested {
        try store.updateJob(jobId,status:"canceled",stage:"complete",clearCurrentPage:true,error:"Canceled by the user.")
      } else if pauseRequested {
        try store.updateJob(jobId,status:"paused",stage:"queued",completed:min(completed,max(0,total-1)),clearCurrentPage:true,error:"Paused after the latest saved page checkpoint.")
      } else {
        try store.updateJob(jobId,status:partial ? "partial":"completed",stage:"complete",completed:total,clearCurrentPage:true,clearError:true)
      }
      activeJobID=nil;activeRunToken=nil;pauseRequested=false;cancelRequested=false
    }
    if #available(iOS 26.0,*),let runToken{cancelQueuedContinuedProcessing(jobID:jobId,runToken:runToken)}
    return succeeded
  }

  func acknowledgeWorkerStop(jobID:String,canceled:Bool,reason:String?,persist:(Bool) throws->Void)->Bool {
    stateLock.lock();defer{stateLock.unlock()}
    guard activeJobID==jobID else{return false}
    if canceled {cancelRequested=true;pauseRequested=false}
    let durableCanceled=cancelRequested
    guard durableCanceled || pauseRequested else{return false}
    do { try persist(durableCanceled) }
    catch {
      NSLog("Could not durably acknowledge the stopped translation worker: %@",error.localizedDescription)
      return false
    }
    pendingStopAcknowledgementRetries.removeValue(forKey:jobID)
    activeJobID=nil;activeRunToken=nil;pauseRequested=false;pauseReason=nil;cancelRequested=false
    return true
  }

  @discardableResult private func acknowledgeRunningWorkerStop(jobID:String,runToken:UUID,canceled:Bool,reason:String,total:Int,completed:Int)->Bool {
    stateLock.lock()
    guard activeJobID==jobID,activeRunToken==runToken else{stateLock.unlock();return false}
    if canceled { cancelRequested=true;pauseRequested=false }
    else if !cancelRequested { pauseRequested=true;if pauseReason==nil{pauseReason=reason} }
    let shouldCancel=cancelRequested,stopReason=pauseReason ?? reason
    stateLock.unlock()
    TranslateCoreBridge.releaseModel()
    beforeRunningStopOutcomeSelectionForTesting?()
    let acknowledged=acknowledgeWorkerStop(jobID:jobID,canceled:shouldCancel,reason:stopReason) { durableCanceled in
      try beforeWorkerStopPersistenceForTesting?()
      if durableCanceled {
        try store.updateJob(jobID,status:"canceled",stage:"queued",completed:min(completed,max(0,total-1)),clearCurrentPage:true,error:"Canceled by the user. The translated PDF was not finalized.")
      } else {
        try store.updateJob(jobID,status:"paused",stage:"queued",completed:min(completed,max(0,total-1)),clearCurrentPage:true,error:stopReason)
      }
    }
    if #available(iOS 26.0,*) {
      finishContinuedTaskForRun(jobID:jobID,runToken:runToken,success:false)
      cancelQueuedContinuedProcessing(jobID:jobID,runToken:runToken)
    }
    if !acknowledged {
      NSLog("The stopped translation remains owned until its durable status can be saved.")
      scheduleStopAcknowledgementRetry(jobID:jobID)
    }
    if acknowledged { endBackgroundExecution(runToken:runToken) }
    return acknowledged
  }

  private func scheduleStopAcknowledgementRetry(jobID:String,immediate:Bool=false){
    let delays:[TimeInterval]=[1,2,4]
    stateLock.lock()
    guard activeJobID==jobID,(pauseRequested || cancelRequested) else{stateLock.unlock();return}
    guard let runToken=activeRunToken else{stateLock.unlock();return}
    let prior=pendingStopAcknowledgementRetries[jobID]
    let attempt=immediate ? 0:(prior?.attempts ?? 0)+1
    guard immediate || attempt<=delays.count else{stateLock.unlock();return}
    let token=UUID()
    pendingStopAcknowledgementRetries[jobID]=PendingStopAcknowledgementRetry(token:token,attempts:attempt)
    stateLock.unlock()
    let delay=immediate ? 0:delays[attempt-1]
    DispatchQueue.global(qos:.utility).asyncAfter(deadline:.now()+delay){[weak self] in
      guard let self else{return}
      self.stateLock.lock()
      let current=self.pendingStopAcknowledgementRetries[jobID]
      let stillStopping=self.activeJobID==jobID && self.activeRunToken==runToken && (self.pauseRequested || self.cancelRequested)
      let ownsRetry=current?.token==token
      self.stateLock.unlock()
      guard ownsRetry,stillStopping else{return}
      // processJob's stop gate runs before model loading or inference. This retry
      // only re-attempts the durable paused/canceled status transition.
      self.translationWork.async{
        self.beforeStopAcknowledgementRetryAdmissionForTesting?()
        self.stateLock.lock()
        let latest=self.pendingStopAcknowledgementRetries[jobID]
        let stillCurrent=latest?.token==token && self.activeJobID==jobID && self.activeRunToken==runToken && (self.pauseRequested || self.cancelRequested)
        self.stateLock.unlock()
        guard stillCurrent else{return}
        self.processJob(jobID,runToken:runToken)
      }
    }
  }

  private func retryPendingStopAcknowledgement(jobID:String){
    stateLock.lock();let pending=pendingStopAcknowledgementRetries[jobID] != nil;stateLock.unlock()
    if pending{scheduleStopAcknowledgementRetry(jobID:jobID,immediate:true)}
  }

  func removeOwnedPdfArtifacts(jobId:String) {
    let root=AppPaths.exports.resolvingSymlinksInPath().standardizedFileURL
    let prefix=safeName(jobId)+"-"
    for candidate in (try? FileManager.default.contentsOfDirectory(at:AppPaths.exports,includingPropertiesForKeys:[.isDirectoryKey])) ?? [] {
      let canonical=candidate.resolvingSymlinksInPath().standardizedFileURL
      let generationSuffix=String(candidate.lastPathComponent.dropFirst(prefix.count))
      guard candidate.lastPathComponent.hasPrefix(prefix),
        UUID(uuidString:generationSuffix) != nil,
        canonical.deletingLastPathComponent()==root,
        (try? candidate.resourceValues(forKeys:[.isDirectoryKey]).isDirectory)==true else{continue}
      try? FileManager.default.removeItem(at:canonical)
    }
    for suffix in [".pdf",".docx",".pending.pdf",".pending.docx"] {
      let candidate=AppPaths.exports.appendingPathComponent(safeName(jobId)+suffix).standardizedFileURL
      guard candidate.deletingLastPathComponent()==AppPaths.exports.standardizedFileURL else{continue}
      try? FileManager.default.removeItem(at:candidate)
    }
    try? FileManager.default.removeItem(at:AppPaths.pages.appendingPathComponent(jobId,isDirectory:true))
    try? FileManager.default.removeItem(at:AppPaths.pages.appendingPathComponent("export-\(safeName(jobId))",isDirectory:true))
    removeThumbnails(prefix:"translated_\(safeName(jobId))_")
    removeThumbnails(prefix:"translated_v2_\(safeName(jobId))_")
    removeThumbnails(prefix:"translated_v3_\(safeName(jobId))_")
    removeThumbnails(prefix:"docx-\(safeName(jobId))-translated")
  }

  private func discardUnpublishedDocxExport(_ jobId:String,previousPath:String?) {
    guard let record=try? store.export("\(jobId):docx"),
      let path=record["path"] as? String,path != previousPath else{return}
    let output=URL(fileURLWithPath:path).standardizedFileURL
    let root=AppPaths.exports.resolvingSymlinksInPath().standardizedFileURL
    let directory=output.deletingLastPathComponent().resolvingSymlinksInPath().standardizedFileURL
    let prefix=safeName(jobId)+"-"
    let suffix=String(directory.lastPathComponent.dropFirst(prefix.count))
    guard output.lastPathComponent=="translated.docx",directory.deletingLastPathComponent()==root,
      directory.lastPathComponent.hasPrefix(prefix),UUID(uuidString:suffix) != nil,
      (try? directory.resourceValues(forKeys:[.isDirectoryKey]).isDirectory)==true else{return}
    do {
      try store.transaction {
        guard let latest=try store.export("\(jobId):docx"),latest["path"] as? String==path else{return}
        try store.deleteExport("\(jobId):docx")
      }
      try? FileManager.default.removeItem(at:directory)
    } catch { return }
  }

  private func removeThumbnails(prefix:String) {
    for candidate in (try? FileManager.default.contentsOfDirectory(at:AppPaths.thumbnails,includingPropertiesForKeys:nil)) ?? [] where candidate.lastPathComponent.hasPrefix(prefix) {
      try? FileManager.default.removeItem(at:candidate)
    }
  }

  private func normalizedWarnings(_ value:Any?)->[[String:Any]] {
    if let objects=value as? [[String:Any]] { return objects }
    return (value as? [String] ?? []).map { ["code":"DOCX_UNSUPPORTED_CONTENT","message":$0] }
  }

  private func mergeWarnings(_ values:[[String:Any]])->[[String:Any]] {
    var seen=Set<String>(),result=[[String:Any]]()
    for item in values {
      guard let key=try? jsonString(item),seen.insert(key).inserted else{continue}
      result.append(item)
    }
    return result
  }

  private struct VerifiedPdfExport {
    let url:URL
    let name:String
    let receipt:[String:Any]
    let outputPageCount:Int
    let outputSha256:String
  }

  private func verifiedPdfExport(_ jobId:String,sourcePageCount:Int)throws->VerifiedPdfExport? {
    guard sourcePageCount>0,let record=try store.export(jobId),let path=record["path"] as? String,
      let name=record["name"] as? String,let savedHash=record["sha256"] as? String,!savedHash.isEmpty,
      let source=try store.jobSource(jobId) else{return nil}
    let url=URL(fileURLWithPath:path).resolvingSymlinksInPath().standardizedFileURL
    let exports=AppPaths.exports.resolvingSymlinksInPath().standardizedFileURL
    let directory=url.deletingLastPathComponent()
    guard url.lastPathComponent=="translated.pdf",directory.deletingLastPathComponent()==exports,
      directory.lastPathComponent.hasPrefix(safeName(jobId)+"-"),FileManager.default.fileExists(atPath:url.path) else{return nil}
    let receiptURL=directory.appendingPathComponent("layout-receipt.json")
    guard FileManager.default.fileExists(atPath:receiptURL.path) else{return nil}
    let sourceURL=URL(fileURLWithPath:source).standardizedFileURL
    guard FileManager.default.fileExists(atPath:sourceURL.path) else{return nil}
    let sourceSHA=try sha256(at:sourceURL),checkpoint=try pdfCheckpointDigest(jobId,sourcePageCount:sourcePageCount)
    let receipt=try JSONSerialization.jsonObject(with:Data(contentsOf:receiptURL)) as? [String:Any]
    guard let receipt else{return nil}
    let outputSHA=try sha256(at:url)
    guard savedHash==outputSHA else{return nil}
    try validatePdfLayoutReceipt(receipt,sourcePageCount:sourcePageCount,expectedSourceSHA:sourceSHA,
      expectedCheckpointDigest:checkpoint,expectedOutputSHA:outputSHA)
    guard let receiptOutputPageCount=exactLayoutInteger(receipt["outputPageCount"]) else{return nil}
    let inspected=try jsonObject(TranslateCoreBridge.inspect(url.path))
    guard inspected["error"]==nil,nativePageCountMatches(inspected,expected:receiptOutputPageCount) else{return nil}
    return VerifiedPdfExport(url:url,name:name,receipt:receipt,outputPageCount:receiptOutputPageCount,outputSha256:outputSHA)
  }

  private func verifiedExport(_ jobId:String,expectedPages:Int)throws->(url:URL,name:String)? {
    guard let record=try store.export(jobId),let path=record["path"] as? String,let name=record["name"] as? String,
      let savedHash=record["sha256"] as? String,!savedHash.isEmpty else{return nil}
    let url=URL(fileURLWithPath:path).standardizedFileURL
    guard url.deletingLastPathComponent()==AppPaths.exports.standardizedFileURL,
      FileManager.default.fileExists(atPath:url.path),try sha256(at:url)==savedHash else{return nil}
    let inspected=try jsonObject(TranslateCoreBridge.inspect(url.path))
    guard inspected["error"]==nil,nativePageCountMatches(inspected,expected:expectedPages) else{return nil}
    return(url,name)
  }

  private func pdfCheckpointDigest(_ jobId:String,sourcePageCount:Int)throws->String {
    var digest=SHA256()
    for index in 0..<sourcePageCount {
      guard let page=try store.page(jobId,index:index),let blocks=page["blocks"] as? [[String:Any]] else{
        throw error("Page \(index+1) has no saved translation checkpoint.")
      }
      let record:[String:Any]=["sourcePageIndex":index,"blocks":blocks]
      digest.update(data:Data((try jsonString(record)+"\n").utf8))
    }
    return digest.finalize().map{String(format:"%02x",$0)}.joined()
  }

  func makePdfLayoutReceipt(sourcePageCount:Int,sourceSHA:String,checkpointDigest:String,outputSHA:String,
    sourceRanges:[[String:Any]],mergedPages:[[String:Any]],placementsBySource:[[[String:Any]]])throws->[String:Any] {
    guard sourcePageCount>0,placementsBySource.count==sourcePageCount,mergedPages.count==sourcePageCount else{throw error("The PDF layout does not include every source page.")}
    var pages=[[String:Any]]()
    for index in 0..<sourcePageCount {
      let item=mergedPages[index]
      guard exactLayoutInteger(item["outputPageIndex"])==index,
        exactLayoutInteger(item["sourcePageIndex"])==index,
        exactLayoutInteger(item["sourceOutputPageIndex"])==0 else{throw error("The PDF core returned a non-identity source-to-output page map.")}
      let globalPlacements=placementsBySource[index].map { placement -> [String:Any] in
        var value=placement
        value["outputPageIndex"]=index
        value["sourceOutputPageIndex"]=0
        return value
      }
      pages.append(["outputPageIndex":index,"sourcePageIndex":index,"sourceOutputPageIndex":0,
        "placements":globalPlacements])
    }
    try validateSourcePreservingPageMap(sourcePageCount:sourcePageCount,sourceRanges:sourceRanges,pages:pages)
    let content:[String:Any]=["schemaVersion":3,"layoutVersion":3,"mode":"preserve-source","rendererPolicy":Self.pdfRendererPolicy,"complete":true,"sourceSha256":sourceSHA,
      "checkpointDigest":checkpointDigest,"outputSha256":outputSHA,"sourcePageCount":sourcePageCount,
      "outputPageCount":pages.count,"sourceRanges":sourceRanges,"pages":pages]
    var receipt=content
    receipt["receiptSha256"]=try digestJson(content)
    try validatePdfLayoutReceipt(receipt,sourcePageCount:sourcePageCount,expectedSourceSHA:sourceSHA,
      expectedCheckpointDigest:checkpointDigest,expectedOutputSHA:outputSHA)
    return receipt
  }

  func validatePdfLayoutReceipt(_ receipt:[String:Any],sourcePageCount:Int,expectedSourceSHA:String,
    expectedCheckpointDigest:String,expectedOutputSHA:String)throws {
    guard exactLayoutInteger(receipt["schemaVersion"])==3,exactLayoutInteger(receipt["layoutVersion"])==3,
      (receipt["mode"] as? String)=="preserve-source",
      (receipt["rendererPolicy"] as? String)==Self.pdfRendererPolicy,
      exactLayoutBoolean(receipt["complete"])==true,exactLayoutInteger(receipt["sourcePageCount"])==sourcePageCount,
      let outputCount=exactLayoutInteger(receipt["outputPageCount"]),outputCount==sourcePageCount,
      (receipt["sourceSha256"] as? String)==expectedSourceSHA,(receipt["checkpointDigest"] as? String)==expectedCheckpointDigest,
      (receipt["outputSha256"] as? String)==expectedOutputSHA,let ranges=receipt["sourceRanges"] as? [[String:Any]],
      let pages=receipt["pages"] as? [[String:Any]],ranges.count==sourcePageCount,pages.count==outputCount else{
      throw error("The PDF layout receipt does not match the source, checkpoints, or output PDF.")
    }
    for digest in [expectedSourceSHA,expectedCheckpointDigest,expectedOutputSHA] {
      guard digest.range(of:"^[0-9a-f]{64}$",options:.regularExpression) != nil else{throw error("The PDF layout receipt contains an invalid digest.")}
    }
    try validateSourcePreservingPageMap(sourcePageCount:sourcePageCount,sourceRanges:ranges,pages:pages)
    var content=receipt
    let supplied=content.removeValue(forKey:"receiptSha256") as? String
    let expectedReceiptDigest=try digestJson(content)
    guard let supplied,supplied==expectedReceiptDigest else{throw error("The PDF layout receipt integrity check failed.")}
  }

  func validateSourcePreservingPageMap(sourcePageCount:Int,sourceRanges:[[String:Any]],pages:[[String:Any]])throws {
    guard sourcePageCount>0,sourceRanges.count==sourcePageCount,pages.count==sourcePageCount else{
      throw error("The source-preserving PDF must contain exactly one output page for every source page.")
    }
    for index in 0..<sourcePageCount {
      let range=sourceRanges[index],page=pages[index]
      guard exactLayoutInteger(range["sourcePageIndex"])==index,
        exactLayoutInteger(range["firstOutputPage"])==index,
        exactLayoutInteger(range["outputPageCount"])==1,
        exactLayoutInteger(page["outputPageIndex"])==index,
        exactLayoutInteger(page["sourcePageIndex"])==index,
        exactLayoutInteger(page["sourceOutputPageIndex"])==0,
        let placements=page["placements"] as? [[String:Any]] else{
        throw error("The source-preserving PDF page map is not an exact identity mapping.")
      }
      for placement in placements {
        guard exactLayoutInteger(placement["outputPageIndex"])==index,
          exactLayoutInteger(placement["sourceOutputPageIndex"])==0 else{
          throw error("A source-preserving placement uses a non-identity page index.")
        }
      }
    }
  }

  private func exactLayoutInteger(_ raw:Any?)->Int? {
    guard let number=raw as? NSNumber,CFGetTypeID(number) != CFBooleanGetTypeID() else{return nil}
    let decimal=number.decimalValue
    let decimalNumber=NSDecimalNumber(decimal:decimal)
    guard decimalNumber != NSDecimalNumber.notANumber else{return nil}
    guard let integer=Int(decimalNumber.stringValue), Decimal(integer) == decimal else{return nil}
    return integer
  }

  private func exactLayoutBoolean(_ raw:Any?)->Bool? {
    guard let number=raw as? NSNumber,CFGetTypeID(number)==CFBooleanGetTypeID() else{return nil}
    return number.boolValue
  }

  func validateSourcePageExportResult(_ result:[String:Any],sourcePageIndex:Int)throws->[[String:Any]] {
    guard exactLayoutInteger(result["layoutVersion"])==3,
      (result["mode"] as? String)=="preserve-source",
      exactLayoutInteger(result["sourcePageIndex"])==sourcePageIndex,
      exactLayoutInteger(result["pageCount"])==1,
      let placements=result["placements"] as? [[String:Any]] else {
      throw error("The PDF core returned an invalid source-preserving page result for page \(sourcePageIndex+1).")
    }
    return placements
  }

  func validateSourcePreservingMergeResult(_ result:[String:Any],sourcePageCount:Int)throws->(sourceRanges:[[String:Any]],pages:[[String:Any]]) {
    guard exactLayoutInteger(result["layoutVersion"])==3,
      (result["mode"] as? String)=="preserve-source",
      exactLayoutInteger(result["pageCount"])==sourcePageCount,
      let sourceRanges=result["sourcePageRanges"] as? [[String:Any]],
      let pages=result["pages"] as? [[String:Any]] else {
      throw error("The PDF core returned an incomplete source-preserving page map.")
    }
    return(sourceRanges,pages)
  }

  func nativePageCountMatches(_ result:[String:Any],expected:Int)->Bool {
    exactLayoutInteger(result["pageCount"])==expected
  }

  private func digestJson(_ value:Any)throws->String {
    let string=try jsonString(value)
    return SHA256.hash(data:Data(string.utf8)).map{String(format:"%02x",$0)}.joined()
  }

  private func samePageGeometry(_ source:[String:Any],_ output:[String:Any])->Bool {
    guard let sourceWidth=(source["width"] as? NSNumber)?.doubleValue,
      let sourceHeight=(source["height"] as? NSNumber)?.doubleValue,
      let outputWidth=(output["width"] as? NSNumber)?.doubleValue,
      let outputHeight=(output["height"] as? NSNumber)?.doubleValue,
      sourceWidth>0,sourceHeight>0,outputWidth>0,outputHeight>0 else{return false}
    return abs(sourceWidth-outputWidth)<=0.01 && abs(sourceHeight-outputHeight)<=0.01
  }

  func validateLocalPlacements(blocks:[[String:Any]],outputPageCount:Int,placements:[[String:Any]],issues:[[String:Any]])throws {
    guard outputPageCount==1 else{throw error("A source-preserving page must produce exactly one output page.")}
    var ids=Set<String>(),eligible=Set<String>(),unresolved=Set<String>(),placed=Set<String>()
    for block in blocks {
      guard let id=block["id"] as? String,!id.isEmpty else{throw error("A source block has no stable ID.")}
      guard ids.insert(id).inserted else{throw error("A source page contains duplicate block IDs.")}
      let translated=(block["translatedText"] as? String) ?? ""
      let target=translated.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty ? ((block["translation"] as? String) ?? "") : translated
      if !target.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty {eligible.insert(id)}
    }
    for issue in issues {
      let id=issue["blockId"] as? String ?? ""
      if ["missing_translation","unsupported_layout"].contains(issue["reason"] as? String ?? ""),!id.isEmpty {unresolved.insert(id)}
    }
    guard unresolved.isSubset(of:ids) else{throw error("A layout issue references a block outside its source page.")}
    for placement in placements {
      guard exactLayoutInteger(placement["outputPageIndex"])==0 else{
        throw error("A source-preserving placement must remain on its source page.")
      }
      let blockId=placement["blockId"] as? String ?? "",figureId=placement["figureId"] as? String ?? ""
      guard !blockId.isEmpty || !figureId.isEmpty else{throw error("A placement has no source block or figure ID.")}
      if !blockId.isEmpty {
        guard ids.contains(blockId) else{throw error("A placement references a block outside its source page.")}
        placed.insert(blockId)
      }
    }
    guard eligible.subtracting(unresolved).isSubset(of:placed) else{throw error("The PDF core omitted translated text from the output page map.")}
  }

  private func verifiedDocxExport(_ jobId:String,expectedSegments:Int)throws->(url:URL,name:String)? {
    guard let record=try store.export("\(jobId):docx"),let path=record["path"] as? String,let name=record["name"] as? String,
      let hash=record["sha256"] as? String,!hash.isEmpty else{return nil}
    let url=URL(fileURLWithPath:path).standardizedFileURL
    guard url.deletingLastPathComponent()==AppPaths.exports.standardizedFileURL,
      FileManager.default.fileExists(atPath:url.path),try sha256(at:url)==hash else{return nil}
    let inspected=try jsonObject(TranslateCoreBridge.inspectDocx(url.path))
    guard inspected["error"]==nil,expectedSegments>0,((inspected["segmentCount"] as? NSNumber)?.intValue ?? 0)>0 else{return nil}
    return(url,name)
  }

  private func ensureDocxPreview(source:URL,translatedDocx:URL?,key:String)throws->URL {
    let input=translatedDocx ?? source
    let cache=AppPaths.thumbnails.appendingPathComponent("docx-\(safeName(key)).pdf")
    if FileManager.default.fileExists(atPath:cache.path),let check=try? jsonObject(TranslateCoreBridge.inspect(cache.path)),check["error"]==nil,
       ((check["pageCount"] as? NSNumber)?.intValue ?? 0)>0 { return cache }
    let semaphore=DispatchSemaphore(value:0)
    var result:Result<URL,Error> = .failure(error("The Word preview could not be rendered."))
    DocxPreviewRenderer.render(source:input,cacheURL:cache){value in result=value;semaphore.signal()}
    guard semaphore.wait(timeout: .now() + 100) == .success else { throw error("The Word preview took too long to render.") }
    let pdf=try result.get()
    let check=try jsonObject(TranslateCoreBridge.inspect(pdf.path))
    if let message=check["error"] as? String{throw error(message)}
    guard ((check["pageCount"] as? NSNumber)?.intValue ?? 0)>0 else{throw error("The Word preview PDF contains no pages.")}
    return pdf
  }

  private func checkFinalizationControl(_ jobId:String)throws {
    stateLock.lock();let paused=pauseRequested,canceled=cancelRequested,active=activeJobID;stateLock.unlock()
    if canceled {throw CancelSignal()}
    if paused {throw PauseSignal()}
    if active != jobId {throw PauseSignal()}
  }
  private func presentExportFile(id:String,url:URL,name:String,mime:String="application/pdf",resolve:@escaping RCTPromiseResolveBlock,reject:@escaping RCTPromiseRejectBlock){
    guard let presenter=topPresenter() else{reject("EXPORT_UNAVAILABLE","Open the app to choose where to save the translated document.",nil);return}
    pendingExport=resolve;pendingExportReject=reject;pendingExportInfo=(id,url,name);pendingExportMIME=mime
    let picker=UIDocumentPickerViewController(forExporting:[url],asCopy:true);picker.delegate=self;presenter.present(picker,animated:true)
  }
  private func fontPath()->String {
    if let bundleURL=Bundle.main.url(forResource:"TranslatePDFFont",withExtension:"bundle"),let bundle=Bundle(url:bundleURL),let path=bundle.path(forResource:"NotoSansCondensed-Regular",ofType:"ttf"){return path}
    return Bundle.main.path(forResource:"NotoSansCondensed-Regular",ofType:"ttf") ?? ""
  }
  private func cjkFontPath()->String {
    if let bundleURL=Bundle.main.url(forResource:"TranslatePDFFont",withExtension:"bundle"),let bundle=Bundle(url:bundleURL),let path=bundle.path(forResource:"NotoSansSC-Regular",ofType:"ttf"){return path}
    return Bundle.main.path(forResource:"NotoSansSC-Regular",ofType:"ttf") ?? ""
  }
  private func isMemoryAllocationFailure(_ message:String)->Bool {
    let normalized=message.lowercased()
    return ["out of memory","failed to allocate","allocation failed","cannot allocate","not enough memory","memory allocation"].contains(where:normalized.contains)
  }
  private func deviceModel()->String { var size=0;sysctlbyname("hw.machine",nil,&size,nil,0);var bytes=[CChar](repeating:0,count:size);sysctlbyname("hw.machine",&bytes,&size,nil,0);return String(cString:bytes) }
  private func beginBackgroundExecution(jobID:String,runToken:UUID){
    DispatchQueue.main.async{[weak self] in
      guard let self else{return}
      self.stateLock.lock();let isCurrent=self.activeJobID==jobID && self.activeRunToken==runToken;self.stateLock.unlock()
      guard isCurrent else{return}
      let task=UIApplication.shared.beginBackgroundTask(withName:"PDF translation \(jobID)"){[weak self] in
        self?.requestPause(jobID:jobID,reason:"iOS ended the available background execution window; saved pages are ready to resume.",backgroundRunToken:runToken)
      }
      guard task != .invalid else{return}
      self.stateLock.lock();let stillCurrent=self.activeJobID==jobID && self.activeRunToken==runToken;self.stateLock.unlock()
      guard stillCurrent else { if task != .invalid {UIApplication.shared.endBackgroundTask(task)};return }
      if self.backgroundTask != .invalid {
        let previous=self.backgroundTask
        UIApplication.shared.endBackgroundTask(previous)
      }
      self.backgroundTask=task
      self.backgroundTaskOwnerToken=runToken
    }
  }
  private func endBackgroundExecution(runToken:UUID){
    DispatchQueue.main.async{[weak self] in
      guard let self,Self.backgroundTaskOwnerMatches(ownerToken:self.backgroundTaskOwnerToken,endingToken:runToken) else{return}
      let task=self.backgroundTask
      self.backgroundTask = .invalid
      self.backgroundTaskOwnerToken=nil
      if task != .invalid { UIApplication.shared.endBackgroundTask(task) }
    }
  }
  static func backgroundTaskOwnerMatches(ownerToken:UUID?,endingToken:UUID)->Bool { ownerToken==endingToken }
  @objc private func applicationDidBecomeActive(){stateLock.lock();let id=activeJobID;let shouldResume=pauseRequested && !cancelRequested;let pending=id.map{pendingStopAcknowledgementRetries[$0] != nil} ?? false;systemLowMemory=false;stateLock.unlock();if pending,let id{retryPendingStopAcknowledgement(jobID:id)}else if shouldResume,let id { work.async{self.resumeAfterForeground(id)} }}
  func requestPause(jobID:String,reason:String){
    requestPause(jobID:jobID,reason:reason,continuedGeneration:nil,backgroundRunToken:nil)
  }
  func requestPause(jobID:String,reason:String,continuedGeneration expectedGeneration:UInt64?){
    requestPause(jobID:jobID,reason:reason,continuedGeneration:expectedGeneration,backgroundRunToken:nil)
  }
  func requestPause(jobID:String,reason:String,backgroundRunToken expectedRunToken:UUID?){
    requestPause(jobID:jobID,reason:reason,continuedGeneration:nil,backgroundRunToken:expectedRunToken)
  }
  private func requestPause(jobID:String,reason:String,continuedGeneration expectedGeneration:UInt64?,backgroundRunToken expectedRunToken:UUID?){
    stateLock.lock()
    if let expectedGeneration,expectedGeneration != continuedTaskGenerations[jobID,default:0] { stateLock.unlock();return }
    if let expectedRunToken,expectedRunToken != activeRunToken { stateLock.unlock();return }
    guard activeJobID==jobID,!cancelRequested else{stateLock.unlock();return}
    pauseRequested=true
    pauseReason=reason
    // The C++ signal is an atomic store. Send it while ownership is locked so
    // an old pause cannot cancel a subsequently resumed/new inference request.
    TranslateCoreBridge.cancel()
    onlineTranslator.cancel()
    let retryPending=pendingStopAcknowledgementRetries[jobID] != nil
    let runToken=activeRunToken
    stateLock.unlock()
    if #available(iOS 26.0,*),let runToken{cancelQueuedContinuedProcessing(jobID:jobID,runToken:runToken)}
    if retryPending{retryPendingStopAcknowledgement(jobID:jobID)}
  }
  private func resumeAfterForeground(_ id:String){
    stateLock.lock()
    guard pauseRequested,!cancelRequested else{stateLock.unlock();return}
    // A foreground transition cannot revoke a pause already acknowledged to the
    // worker: native inference may already be unwinding after cancellation.
    if activeJobID==id { stateLock.unlock();return }
    guard activeJobID==nil else{stateLock.unlock();return}
    let runToken=UUID()
    activeJobID=id;activeRunToken=runToken;pauseRequested=false
    stateLock.unlock()
    beginBackgroundExecution(jobID:id,runToken:runToken)
    translationWork.async{self.processJob(id,runToken:runToken)}
  }

  private func assessment(for model:ModelDefinition,status:String? = nil,downloadedBytes:Int64? = nil)throws->ModelResourceAssessment {
    let state=try store.modelState(model.id)
    let selectedStatus=status ?? (state?["status"] as? String ?? "not_downloaded")
    let bytes=downloadedBytes ?? (state?["downloaded_bytes"] as? Int64 ?? 0)
    let disk=try AppPaths.models.resourceValues(forKeys:[.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
    stateLock.lock();let lowMemory=systemLowMemory;stateLock.unlock()
    return MemoryPolicy.assess(model,status:selectedStatus,downloadedBytes:bytes,total:ProcessInfo.processInfo.physicalMemory,available:UInt64(os_proc_available_memory()),availableDisk:UInt64(max(0,disk)),systemLowMemory:lowMemory)
  }

  func processJob(_ id:String,runToken suppliedRunToken:UUID?=nil){
    localTranslations.clear()
    defer {localTranslations.clear()}
    stateLock.lock()
    let runToken=suppliedRunToken ?? activeRunToken
    let ownsActiveSlot=activeJobID==id && runToken != nil && activeRunToken==runToken
    let shouldCancel=cancelRequested,shouldPause=pauseRequested
    let eligibleStatus=(try? store.job(id)?["status"] as? String) ?? nil
    let eligible=["queued","running"].contains(eligibleStatus ?? "")
    if ownsActiveSlot && (shouldCancel || shouldPause) {
      let canceled=shouldCancel,reason=pauseReason
      let acknowledged=acknowledgeWorkerStop(jobID:id,canceled:canceled,reason:reason) { durableCanceled in
        try beforeWorkerStopPersistenceForTesting?()
        if durableCanceled { try store.updateJob(id,status:"canceled",stage:"queued",clearCurrentPage:true,error:"Canceled by the user.") }
        else { try store.updateJob(id,status:"paused",stage:"queued",clearCurrentPage:true,error:reason ?? "Paused after the latest saved page checkpoint.") }
      }
      if #available(iOS 26.0,*),let runToken{finishContinuedTaskForRun(jobID:id,runToken:runToken,success:false)}
      stateLock.unlock()
      if #available(iOS 26.0,*),let runToken{cancelQueuedContinuedProcessing(jobID:id,runToken:runToken)}
      if !acknowledged { NSLog("The stopped translation remains owned until its durable status can be saved.") }
      if !acknowledged{scheduleStopAcknowledgementRetry(jobID:id)}
      if acknowledged,let runToken {endBackgroundExecution(runToken:runToken)}
      return
    }
    if ownsActiveSlot && !eligible {
      activeJobID=nil;activeRunToken=nil;pauseRequested=false;pauseReason=nil;cancelRequested=false
      if #available(iOS 26.0,*),let runToken{finishContinuedTaskForRun(jobID:id,runToken:runToken,success:false)}
      stateLock.unlock()
      if #available(iOS 26.0,*),let runToken{cancelQueuedContinuedProcessing(jobID:id,runToken:runToken)}
      if let runToken {endBackgroundExecution(runToken:runToken)}
      return
    }
    guard ownsActiveSlot && eligible,let runToken else{stateLock.unlock();return}
    stateLock.unlock()
    var stopAcknowledgementHandled=false
    do {
      guard var job=try store.job(id),let source=try store.jobSource(id) else{throw error("Translation job or document is unavailable.")}
      let engine=job["engine"] as? [String:Any] ?? ["kind":"local"]
      let isOnline=(engine["kind"] as? String)=="online"
      let isDocx=(job["documentFormat"] as? String)=="docx"
      let model=isOnline ? nil : try ModelCatalog.get(job["modelId"] as? String ?? "")
      if let model { try store.validateJobSnapshot(id,model:model) } else { try store.validateOnlineSnapshot(id) }
      let count=(job["totalPages"] as? NSNumber)?.intValue ?? 0
      let languages=try TranslationLanguage.pair(source:job["sourceLanguage"] as? String ?? "",target:job["targetLanguage"] as? String ?? "")
      guard count>0 else{throw error("The translation contains no exportable pages.")}
      if !isOnline,!isDocx,model?.profile=="translategemma" {
        try prepareSavedGemmaPageArtifacts(id,totalPages:count)
        job=try store.job(id) ?? job
      }
      let needsInference: Bool
      if isOnline { needsInference = true } else { needsInference = try requiresInference(id,totalPages:count) }
      var modelLoaded=false
      if needsInference,let model {
        guard model.translationEnabled else{throw error("The selected model is not enabled for translation.")}
        guard try downloader.isReady(model) else{throw error("The model file failed its pinned size or SHA-256 check.")}
        _ = try assessment(for:model,status:"ready",downloadedBytes:model.bytes)
      }
      try store.updateJob(id,status:"running",stage:needsInference ? "loadingModel":"exporting",clearError:true)
      if needsInference,let model {
        let loaded=try jsonObject(TranslateCoreBridge.loadModel(model.fileURL.path,context:2048,threads:max(1,min(4,ProcessInfo.processInfo.activeProcessorCount)),profile:model.profile))
        if let message=loaded["error"] as? String {
          if isMemoryAllocationFailure(message) { throw PauseSignal("The operating system could not allocate memory for this model. The job is paused at its latest saved checkpoint; free memory and resume when ready.") }
          throw error(message)
        }
        modelLoaded=true
        try store.updateJob(id,stage:"extracting")
      }
      var completed=(job["completedPages"] as? NSNumber)?.intValue ?? 0
      if #available(iOS 26.0,*),let continued=continuedTask as? BGContinuedProcessingTask {continued.progress.totalUnitCount=Int64(count)}
      let glossary=isOnline ? "" : try store.jobGlossaryText(id)
      for pageIndex in 0..<count {
        beforeWorkerPageStopCheckForTesting?()
        stateLock.lock();let pause=pauseRequested,stop=cancelRequested;stateLock.unlock()
        if stop{try store.updateJob(id,status:"canceled",error:"Canceled by the user.");break}
        if pause{throw PauseSignal()}
        let saved=try store.pageRows(id).first(where:{($0["index"] as? Int64)==Int64(pageIndex) || ($0["index"] as? Int)==pageIndex})
        if let saved,["completed","partial"].contains(saved["status"] as? String ?? ""){continue}
        try store.updateJob(id,status:"running",stage:"extracting",currentPage:pageIndex)
        let previousPage=try store.page(id,index:pageIndex)
        let native=try jsonObject(isDocx ? TranslateCoreBridge.readDocxSegment(source,index:pageIndex) : TranslateCoreBridge.readPage(source,index:pageIndex));if let message=native["error"] as? String{throw error(message)}
        let width=(native["width"] as? NSNumber)?.doubleValue ?? 0,height=(native["height"] as? NSNumber)?.doubleValue ?? 0
        guard width>0,height>0 else{throw error("Page geometry is unavailable.")}
        let savedBlocks=previousPage?["blocks"] as? [[String:Any]] ?? []
        var blocks=native["blocks"] as? [[String:Any]] ?? []
        let context=Self.translationContext(native:native,profile:isOnline ? "" : (model?.profile ?? ""))
        if isDocx {
          // Preserve the legacy Word checkpoint behavior: DOCX extraction is
          // reconstructed from its saved segment rows, not PDF source objects.
          if !savedBlocks.isEmpty { blocks=savedBlocks }
        } else {
          // A PDF checkpoint is a result cache, never the source of truth.
          // Re-read native objects and OCR first so fixes to extraction,
          // symbols or modelText invalidate only the affected translated unit.
          if let imageData=TranslateCoreBridge.renderPNG(source,index:pageIndex,width:1400),let image=UIImage(data:imageData),let cg=image.cgImage {
            blocks = try ocrBlocks(cgImage:cg,pageWidth:width,pageHeight:height,existing:blocks,
              artworkRects:native["artworkRects"] as? [[String:Any]] ?? [],sourceLanguage:languages.0)
          }
          if !isOnline,model?.profile=="translategemma" {
            blocks=Self.stampingGemmaContext(blocks,title:context)
          }
          blocks=Self.restoringPdfCheckpointResults(fresh:blocks,saved:savedBlocks,online:isOnline,
            gemma:!isOnline && model?.profile=="translategemma")
        }
        try store.savePage(jobId:id,index:pageIndex,status:"running",blocks:try jsonString(blocks))
        var partial=blocks.contains { ($0["status"] as? String) == "failed" || ($0["status"] as? String) == "partial" || ($0["sourceDisagreement"] as? Bool) == true }
        try store.updateJob(id,stage:"translating",currentPage:pageIndex)
        try store.setPageProgress(jobId:id,index:pageIndex,currentBlock:0,pageBlocks:blocks.count)
        for index in blocks.indices {
          let checkpointStatus=blocks[index]["status"] as? String ?? ""
          if checkpointStatus == "translated",let text=blocks[index]["translatedText"] as? String,!text.isEmpty { continue }
          if checkpointStatus == "partial" || checkpointStatus == "failed" { partial=true;continue }
          try store.setPageProgress(jobId:id,index:pageIndex,currentBlock:index,pageBlocks:blocks.count)
          stateLock.lock();let pause=pauseRequested,stop=cancelRequested;stateLock.unlock()
          if stop{try store.updateJob(id,status:"canceled",error:"Canceled by the user.");break}
          if pause{throw PauseSignal()}
          let text=blocks[index]["sourceText"] as? String ?? ""
          guard !text.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty else{continue}
          do {
            if isOnline {
              var fragments=blocks[index]["onlineFragments"] as? [[String:Any]] ?? []
              let chunks=try MyMemoryTranslator.chunks(text,byteLimit:500)
              for (chunkIndex,chunk) in chunks.enumerated() {
                stateLock.lock();let shouldPause=pauseRequested,shouldCancel=cancelRequested;stateLock.unlock()
                if shouldCancel { throw CancelSignal() }
                if shouldPause { throw PauseSignal(MyMemoryTranslator.Failure.canceled.errorDescription ?? "Online translation paused.") }
                if chunkIndex < fragments.count,let savedTranslation=fragments[chunkIndex]["translatedText"] as? String { _=savedTranslation; continue }
                let translatedChunk:String
                do { translatedChunk=try onlineTranslator.translate(chunk,source:languages.0.rawValue,target:languages.1.rawValue) }
                catch { stateLock.lock();let canceledNow=cancelRequested;stateLock.unlock();if canceledNow{throw CancelSignal()};throw PauseSignal(error.localizedDescription) }
                if chunkIndex < fragments.count { fragments[chunkIndex]["translatedText"]=translatedChunk }
                else { fragments.append(["translatedText":translatedChunk]) }
                blocks[index]["onlineFragments"]=fragments
                try store.savePage(jobId:id,index:pageIndex,status:"running",blocks:try jsonString(blocks))
              }
              let output=fragments.compactMap{$0["translatedText"] as? String}.joined()
              guard !output.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty else { throw PauseSignal(MyMemoryTranslator.Failure.invalidResponse.errorDescription ?? "MyMemory returned no translation.") }
              blocks[index]["translatedText"]=output;blocks[index]["status"]="translated"
            } else {
            // Keep sourceText intact in checkpoints and use native-prepared
            // marker-bearing text only for local PDF inference and its memo key.
            let modelText=Self.translationInput(block:blocks[index],online:false)
            if model?.profile=="translategemma",Self.beginGemmaCheckpointArtifactRepair(&blocks[index]) {
              try store.savePage(jobId:id,index:pageIndex,status:"running",blocks:try jsonString(blocks))
            }
            let translated=try jsonObject(localTranslations.translate(modelText,glossary:glossary,context:String(context),source:languages.0.rawValue,target:languages.1.rawValue,using:{TranslateCoreBridge.translate($0,glossary:$1,context:$2,sourceLanguage:$3,targetLanguage:$4)}))
            if let message=translated["error"] as? String {
              if isMemoryAllocationFailure(message) { throw PauseSignal("The operating system could not allocate memory while translating. The job is paused at its latest saved checkpoint; free memory and resume when ready.") }
              blocks[index]["translatedText"]=NSNull();blocks[index]["status"]="failed";blocks[index]["error"]=message;partial=true
            }
            else if let value=translated["text"] as? String,!value.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty {
              if model?.profile=="translategemma" {
                let decoded=Self.decodeGemmaPromptBoundaryEnvelope(value,sourceText:blocks[index]["sourceText"] as? String,
                  modelText:modelText)
                if decoded.invalid || decoded.text?.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty != false {
                  Self.rejectGeneratedGemmaPromptArtifact(&blocks[index],candidate:value)
                  partial=true
                } else {
                  blocks[index]["translatedText"]=decoded.text;blocks[index]["status"]="translated"
                  if !decoded.removedTokens.isEmpty {
                    blocks[index]["gemmaPromptArtifactRawText"]=value
                    blocks[index]["gemmaPromptArtifactDecodedTokens"]=decoded.removedTokens
                  }
                }
              } else {blocks[index]["translatedText"]=value;blocks[index]["status"]="translated"}
            }
            else{blocks[index]["translatedText"]=NSNull();blocks[index]["status"]="failed";blocks[index]["error"]="Translation was not produced.";partial=true}
            }
          }catch let signal as PauseSignal { throw signal }
          catch is CancelSignal { throw CancelSignal() }
          catch {
            if isMemoryAllocationFailure(error.localizedDescription) { throw PauseSignal("The operating system could not allocate memory while translating. The job is paused at its latest saved checkpoint; free memory and resume when ready.") }
            blocks[index]["translatedText"]=NSNull();blocks[index]["status"]="failed";blocks[index]["error"]=error.localizedDescription;partial=true
          }
          try store.savePage(jobId:id,index:pageIndex,status:"running",blocks:try jsonString(blocks))
        }
        stateLock.lock();let stopAfterBlocks=cancelRequested;stateLock.unlock();if stopAfterBlocks{break}
        let status=partial ? "partial":"completed"
        try FileManager.default.createDirectory(at:AppPaths.pages.appendingPathComponent(id,isDirectory:true),withIntermediateDirectories:true)
        try store.savePage(jobId:id,index:pageIndex,status:status,blocks:try jsonString(blocks))
        completed+=1;let verifiedProgress=min(completed,max(0,count-1));try store.updateJob(id,completed:verifiedProgress,currentPage:pageIndex)
        if #available(iOS 26.0,*),let continued=continuedTask as? BGContinuedProcessingTask { continued.progress.completedUnitCount=Int64(verifiedProgress) }
      }
      stateLock.lock();let wasCanceled=cancelRequested,wasPaused=pauseRequested,stopReason=pauseReason;stateLock.unlock()
      if modelLoaded {TranslateCoreBridge.releaseModel()}
      var succeeded=false
      if wasCanceled || wasPaused {
        stopAcknowledgementHandled=true
        _=acknowledgeRunningWorkerStop(jobID:id,runToken:runToken,canceled:wasCanceled,
          reason:stopReason ?? "Paused after the latest saved page checkpoint.",total:count,completed:completed)
      }
      else {
        // Translation output must be released before PDF assembly: a merged PDF and a loaded
        // model can otherwise exceed the conservative memory estimate on supported phones.
        try store.updateJob(id,status:"running",stage:"exporting",completed:min(completed,max(0,count-1)),clearCurrentPage:true,clearError:true)
        let previousDocxPath=isDocx ? ((try store.export("\(id):docx")?["path"]) as? String) : nil
        if isDocx { _ = try buildDocxExport(id) } else { _ = try buildExport(id,allowRunning:true) }
        let rows=try store.pageRows(id)
        guard rows.count==count,rows.allSatisfy({["completed","partial"].contains($0["status"] as? String ?? "")}) else{
          throw error("The translated document does not have a saved checkpoint for every source page or text segment.")
        }
        let hasPartial=rows.contains{($0["status"] as? String)=="partial"}
        succeeded=try finishFinalization(id,total:count,completed:count,partial:hasPartial)
        if !succeeded && isDocx { discardUnpublishedDocxExport(id,previousPath:previousDocxPath) }
        if #available(iOS 26.0,*),let continued=continuedTask as? BGContinuedProcessingTask {continued.progress.completedUnitCount=Int64(count)}
      }
      if #available(iOS 26.0,*){finishContinuedTaskForRun(jobID:id,runToken:runToken,success:succeeded)}
    }catch let signal as PauseSignal {
      let total=(try? store.job(id)?["totalPages"] as? NSNumber)?.intValue ?? 0
      let done=(try? store.job(id)?["completedPages"] as? NSNumber)?.intValue ?? 0
      stateLock.lock();let reason=pauseReason;stateLock.unlock()
      stopAcknowledgementHandled=true
      _=acknowledgeRunningWorkerStop(jobID:id,runToken:runToken,canceled:false,reason:reason ?? signal.reason,total:total,completed:done)
    }catch is CancelSignal {
      let total=(try? store.job(id)?["totalPages"] as? NSNumber)?.intValue ?? 0
      let done=(try? store.job(id)?["completedPages"] as? NSNumber)?.intValue ?? 0
      stopAcknowledgementHandled=true
      _=acknowledgeRunningWorkerStop(jobID:id,runToken:runToken,canceled:true,reason:"Canceled by the user.",total:total,completed:done)
    }catch{
      let total=(try? store.job(id)?["totalPages"] as? NSNumber)?.intValue ?? 0
      let done=(try? store.job(id)?["completedPages"] as? NSNumber)?.intValue ?? 0
      stateLock.lock();let ownsCurrentRun=activeJobID==id && activeRunToken==runToken,canceled=cancelRequested,paused=pauseRequested,reason=pauseReason;stateLock.unlock()
      guard ownsCurrentRun else{
        if #available(iOS 26.0,*){
          finishContinuedTaskForRun(jobID:id,runToken:runToken,success:false)
          cancelQueuedContinuedProcessing(jobID:id,runToken:runToken)
        }
        endBackgroundExecution(runToken:runToken)
        return
      }
      if canceled || paused {
        stopAcknowledgementHandled=true
        _=acknowledgeRunningWorkerStop(jobID:id,runToken:runToken,canceled:canceled,reason:reason ?? error.localizedDescription,total:total,completed:done)
      } else {
        try? store.updateJob(id,status:"failed",stage:"queued",completed:min(done,max(0,total-1)),clearCurrentPage:true,error:error.localizedDescription)
        TranslateCoreBridge.cancel();TranslateCoreBridge.releaseModel()
        if #available(iOS 26.0,*){
          finishContinuedTaskForRun(jobID:id,runToken:runToken,success:false)
          cancelQueuedContinuedProcessing(jobID:id,runToken:runToken)
        }
      }
    }
    if !stopAcknowledgementHandled {stateLock.lock();if activeJobID==id && activeRunToken==runToken{activeJobID=nil;activeRunToken=nil;pauseRequested=false;pauseReason=nil;cancelRequested=false};stateLock.unlock()}
    stateLock.lock();let stillOwnsRun=activeJobID==id && activeRunToken==runToken;stateLock.unlock()
    if stopAcknowledgementHandled && !stillOwnsRun, #available(iOS 26.0,*) {
      finishContinuedTaskForRun(jobID:id,runToken:runToken,success:false)
      cancelQueuedContinuedProcessing(jobID:id,runToken:runToken)
    }
    if !stopAcknowledgementHandled || !stillOwnsRun {endBackgroundExecution(runToken:runToken)}
  }

  private func ocrBlocks(cgImage:CGImage,pageWidth:Double,pageHeight:Double,existing:[[String:Any]],artworkRects:[[String:Any]],sourceLanguage:TranslationLanguage)throws->[ [String:Any] ]{
    let request=VNRecognizeTextRequest();request.recognitionLevel = .accurate;request.usesLanguageCorrection=false
    let supported=try request.supportedRecognitionLanguages()
    guard supported.contains(sourceLanguage.visionLocale) else { throw error("iOS Vision does not support OCR for the selected source language (\(sourceLanguage.visionLocale)) on this OS version.") }
    request.recognitionLanguages=[sourceLanguage.visionLocale]
    try VNImageRequestHandler(cgImage:cgImage).perform([request])
    guard let observations=request.results else{return existing}
    var recognized=[[String:Any]]()
    for (index,item) in observations.enumerated(){guard let candidate=item.topCandidates(1).first else{continue};let text=candidate.string.trimmingCharacters(in:.whitespacesAndNewlines);guard !text.isEmpty else{continue};let box=item.boundingBox;let left=box.minX*pageWidth,top=(1-box.maxY)*pageHeight,right=box.maxX*pageWidth,bottom=(1-box.minY)*pageHeight
      recognized.append(["id":"ocr-\(index)","text":text,"sourceText":text,"translatedText":NSNull(),"left":left,"top":top,"right":right,"bottom":bottom,"fontSize":max(6,(bottom-top)*0.8),"source":"ocr","objectIndex":NSNull(),"objectIndices":[],"status":"pending"])
    }
    return Self.mergeOCRBlocks(existing:existing,recognized:recognized,artworkRects:artworkRects,
      pageWidth:pageWidth,pageHeight:pageHeight)
  }

  static func mergeOCRBlocks(existing:[[String:Any]],recognized:[[String:Any]],artworkRects:[[String:Any]]=[],
    pageWidth:Double=0,pageHeight:Double=0)->[[String:Any]]{
    var result=existing
    let pageArea=pageWidth*pageHeight
    let nativeLetterCount=Self.nativeIllustrationLetterCount(existing)
    let eligibleImages=Self.eligibleIllustrationRects(artworkRects,pageWidth:pageWidth,pageHeight:pageHeight,pageArea:pageArea)
    let candidates=recognized.filter{!Self.isOCRInsideNativeIllustration($0,nativeLetterCount:nativeLetterCount,
      eligibleImages:eligibleImages)}
    for block in candidates {
      let text=block["sourceText"] as? String ?? block["text"] as? String ?? ""
      let left=(block["left"] as? NSNumber)?.doubleValue ?? 0,top=(block["top"] as? NSNumber)?.doubleValue ?? 0
      let right=(block["right"] as? NSNumber)?.doubleValue ?? 0,bottom=(block["bottom"] as? NSNumber)?.doubleValue ?? 0
      if let match=matchOCRBlock(existing:result,text:text,left:left,top:top,right:right,bottom:bottom){
        if match.verifiedTocLeaderNoise {
          result[match.index]["verifiedTocLeaderOcrNoise"]=true
        } else if match.sourceDisagreement {
          for index in Set([match.index]+match.ambiguousIndices) {
            result[index]["sourceDisagreement"]=true
            result[index]["error"]=sourceDisagreementBlockWarning
          }
        }
      } else { result.append(block) }
    }
    return result
  }
  static func nativeIllustrationLetterCount(_ blocks:[[String:Any]])->Int{
    blocks.reduce(into:0){total,block in
      guard block["source"] as? String == "native",let rects=block["sourceRects"] as? [[String:Any]] else{return}
      for rect in rects{
        guard rect["pictogram"] as? Bool == false,rect["objectIndex"] is NSNumber,
          let text=rect["text"] as? String else{continue}
        total += text.unicodeScalars.reduce(into:0){if CharacterSet.letters.contains($1){$0 += 1}}
      }
    }
  }
  static func eligibleIllustrationRects(_ artworkRects:[[String:Any]],pageWidth:Double,pageHeight:Double,pageArea:Double)->[CGRect]{
    guard pageWidth.isFinite,pageHeight.isFinite,pageWidth>0,pageHeight>0,pageArea.isFinite else{return []}
    return artworkRects.compactMap{image in
      guard image["type"] as? String == "image",
        (image["coordinateSpaceVersion"] as? NSNumber)?.intValue == 2,
        image["objectIndex"] is NSNumber,
        let left=(image["left"] as? NSNumber)?.doubleValue,
        let top=(image["top"] as? NSNumber)?.doubleValue,
        let right=(image["right"] as? NSNumber)?.doubleValue,
        let bottom=(image["bottom"] as? NSNumber)?.doubleValue,
        left.isFinite,top.isFinite,right.isFinite,bottom.isFinite,
        left>=0,top>=0,right<=pageWidth,bottom<=pageHeight,right>left,bottom>top else{return nil}
      let area=(right-left)*(bottom-top)
      guard area.isFinite,area>0,area<=pageArea*0.65 else{return nil}
      return CGRect(x:left,y:top,width:right-left,height:bottom-top)
    }
  }
  static func isOCRInsideNativeIllustration(_ recognized:[String:Any],nativeLetterCount:Int,eligibleImages:[CGRect])->Bool{
    guard nativeLetterCount>=40,
      let left=(recognized["left"] as? NSNumber)?.doubleValue,
      let top=(recognized["top"] as? NSNumber)?.doubleValue,
      let right=(recognized["right"] as? NSNumber)?.doubleValue,
      let bottom=(recognized["bottom"] as? NSNumber)?.doubleValue,
      left.isFinite,top.isFinite,right.isFinite,bottom.isFinite,right>left,bottom>top else{return false}
    let ocrArea=(right-left)*(bottom-top)
    guard ocrArea.isFinite,ocrArea>0 else{return false}
    // PDF raster/OCR boxes can drift by a fraction of a point from PDFium's
    // vector bounds. Keep the 65% eligibility check on exact bounds and use
    // this matching-only tolerance for every line on the page.
    let rasterTolerance=0.25
    for image in eligibleImages{
      let imageLeft=image.minX,imageTop=image.minY,imageRight=image.maxX,imageBottom=image.maxY
      let intersection=max(0,min(right,imageRight+rasterTolerance)-max(left,imageLeft-rasterTolerance))*max(0,min(bottom,imageBottom+rasterTolerance)-max(top,imageTop-rasterTolerance))
      if intersection/ocrArea>=0.95{return true}
    }
    return false
  }
  static func translationInput(block:[String:Any],online:Bool)->String{
    let sourceText=block["sourceText"] as? String ?? ""
    guard !online,let modelText=block["modelText"] as? String else{return sourceText}
    let prepared=modelText.trimmingCharacters(in:.whitespacesAndNewlines)
    return prepared.isEmpty ? sourceText:prepared
  }
  static func translationContext(native:[String:Any],profile:String)->String{
    if profile=="translategemma" {
      guard let title=native["documentTitle"] as? String,(3...400).contains(title.utf8.count) else{return ""}
      return title
    }
    return String((native["context"] as? String ?? "").prefix(2_000))
  }
  static func gemmaTranslationContextKey(title:String)->String{
    SHA256.hash(data:Data(("gemma-info-title-v1\n"+title).utf8)).map{String(format:"%02x",$0)}.joined()
  }
  static func stampingGemmaContext(_ blocks:[[String:Any]],title:String)->[[String:Any]]{
    let key=gemmaTranslationContextKey(title:title)
    return blocks.map{block in var value=block;value["translationContextKey"]=key;return value}
  }
  static let gemmaSourceBoundaryRepairRevision="gemma-source-boundary-v2"
  static let gemmaPromptArtifactError="The model returned an internal prompt boundary token; verify this block."
  struct GemmaBoundaryDecode {
    let text:String?
    let removedTokens:[String]
    let invalid:Bool
  }
  enum GemmaCheckpointArtifactOutcome:Equatable {case none,decoded,retry,exhausted}
  private static let gemmaBoundaryTokens=["<source_segment>","</source_segment>"]
  static func decodeGemmaPromptBoundaryEnvelope(_ target:String?,sourceText:String?,modelText:String?)->GemmaBoundaryDecode {
    guard let target else{return GemmaBoundaryDecode(text:nil,removedTokens:[],invalid:false)}
    guard !target.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty else{
      return GemmaBoundaryDecode(text:target,removedTokens:[],invalid:true)
    }
    let source=sourceText ?? ""
    let suppliedModel=modelText ?? ""
    let model=suppliedModel.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty ? source:suppliedModel
    let sourceCounts=gemmaBoundaryTokens.map{occurrenceCount(of:$0,in:source)}
    let modelCounts=gemmaBoundaryTokens.map{occurrenceCount(of:$0,in:model)}
    let targetCounts=gemmaBoundaryTokens.map{occurrenceCount(of:$0,in:target)}
    if sourceCounts.contains(where:{$0>0}) || modelCounts.contains(where:{$0>0}) {
      let countsMatch=zip(sourceCounts,modelCounts).allSatisfy{$0==$1} && zip(modelCounts,targetCounts).allSatisfy{$0==$1}
      return GemmaBoundaryDecode(text:target,removedTokens:[],invalid:!countsMatch)
    }
    guard targetCounts.contains(where:{$0>0}) else {
      return GemmaBoundaryDecode(text:target,removedTokens:[],invalid:false)
    }
    var body=target.trimmingCharacters(in:.whitespacesAndNewlines)
    var removed=[String]()
    let prefix=gemmaBoundaryTokens[0],suffix=gemmaBoundaryTokens[1]
    if body.hasPrefix(prefix) {
      body=String(body.dropFirst(prefix.count)).trimmingCharacters(in:.whitespacesAndNewlines)
      removed.append(prefix)
    }
    if body.hasSuffix(suffix) {
      body=String(body.dropLast(suffix.count)).trimmingCharacters(in:.whitespacesAndNewlines)
      removed.append(suffix)
    }
    let hasInnerToken=gemmaBoundaryTokens.contains{body.contains($0)}
    return GemmaBoundaryDecode(text:hasInnerToken || (!removed.isEmpty && body.isEmpty) ? nil:body,
      removedTokens:removed,invalid:hasInnerToken || (!removed.isEmpty && body.isEmpty))
  }
  private static func occurrenceCount(of token:String,in text:String)->Int {
    var count=0,search=text.startIndex..<text.endIndex
    while let match=text.range(of:token,range:search) {
      count+=1;search=match.upperBound..<text.endIndex
    }
    return count
  }
  private static func jsonObjectsEqual(_ lhs:[[String:Any]],_ rhs:[[String:Any]])->Bool {
    guard let left=try? JSONSerialization.data(withJSONObject:lhs,options:[.sortedKeys]),
      let right=try? JSONSerialization.data(withJSONObject:rhs,options:[.sortedKeys]) else{return false}
    return left==right
  }
  static func gemmaCheckpointRequiresInference(_ block:[String:Any])->Bool {
    let decoded=decodeGemmaPromptBoundaryEnvelope(block["translatedText"] as? String,
      sourceText:block["sourceText"] as? String,modelText:translationInput(block:block,online:false))
    if (block["gemmaPromptArtifactRepairPendingRevision"] as? String)==gemmaSourceBoundaryRepairRevision{return true}
    let status=block["status"] as? String ?? ""
    if status=="translated",(block["translatedText"] as? String)?.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty != false {
      return (block["gemmaPromptArtifactRepairRevision"] as? String) != gemmaSourceBoundaryRepairRevision
    }
    if decoded.invalid {
      if (block["gemmaPromptArtifactRepairRevision"] as? String) != gemmaSourceBoundaryRepairRevision{return true}
      return !["translated","partial","failed"].contains(status)
    }
    if (block["gemmaPromptArtifactRepairRevision"] as? String)==gemmaSourceBoundaryRepairRevision,
      !["translated","partial","failed"].contains(status) {return true}
    return false
  }
  static func prepareGemmaCheckpointArtifact(saved:[String:Any],fresh:inout [String:Any])->GemmaCheckpointArtifactOutcome {
    for key in ["gemmaPromptArtifactRepairRevision","gemmaPromptArtifactRepairPendingRevision",
      "gemmaPromptArtifactRejectedText","gemmaPromptArtifactRawText","gemmaPromptArtifactDecodedTokens"] {
      if let value=saved[key] {fresh[key]=value}
    }
    let savedTarget=saved["translatedText"] as? String
    let savedTargetUsable=savedTarget?.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty==false
    let savedRevision=saved["gemmaPromptArtifactRepairRevision"] as? String
    let savedStatus=saved["status"] as? String ?? ""
    if savedRevision==gemmaSourceBoundaryRepairRevision,savedTarget==nil,
      !["translated","partial","failed"].contains(savedStatus) {
      preserveGemmaLayoutHistory(saved,fresh:&fresh)
      fresh["gemmaPromptArtifactRepairRevision"]=gemmaSourceBoundaryRepairRevision
      return .retry
    }
    let candidate=savedTarget ?? (saved["gemmaPromptArtifactRejectedText"] as? String).flatMap{$0.isEmpty ? nil:$0}
    let pending=(saved["gemmaPromptArtifactRepairPendingRevision"] as? String)==gemmaSourceBoundaryRepairRevision
    let decoded=decodeGemmaPromptBoundaryEnvelope(candidate,sourceText:fresh["sourceText"] as? String,
      modelText:translationInput(block:fresh,online:false))
    if !decoded.invalid,!decoded.removedTokens.isEmpty,savedTargetUsable {
      preserveGemmaLayoutHistory(saved,fresh:&fresh)
      fresh["translatedText"]=decoded.text
      fresh["gemmaPromptArtifactRawText"]=candidate
      fresh["gemmaPromptArtifactDecodedTokens"]=decoded.removedTokens
      fresh["status"]=savedStatus.isEmpty ? "translated":savedStatus
      if let message=saved["error"] {fresh["error"]=message}else{fresh.removeValue(forKey:"error")}
      fresh.removeValue(forKey:"gemmaPromptArtifactRepairPendingRevision")
      return .decoded
    }
    if !decoded.invalid,decoded.removedTokens.isEmpty {
      if pending {
        preserveGemmaLayoutHistory(saved,fresh:&fresh)
        fresh["gemmaPromptArtifactRepairPendingRevision"]=gemmaSourceBoundaryRepairRevision
        fresh["status"]="pending";fresh.removeValue(forKey:"translatedText");fresh.removeValue(forKey:"error")
        return .retry
      }
      if savedRevision==gemmaSourceBoundaryRepairRevision && !["translated","partial","failed"].contains(savedStatus) {
        preserveGemmaLayoutHistory(saved,fresh:&fresh)
        fresh["gemmaPromptArtifactRepairRevision"]=gemmaSourceBoundaryRepairRevision
        return .retry
      }
      return .none
    }
    preserveGemmaLayoutHistory(saved,fresh:&fresh)
    if let candidate {fresh["gemmaPromptArtifactRawText"]=candidate}
    fresh.removeValue(forKey:"translatedText");fresh.removeValue(forKey:"error")
    if savedRevision==gemmaSourceBoundaryRepairRevision {
      fresh["gemmaPromptArtifactRepairRevision"]=gemmaSourceBoundaryRepairRevision
      fresh["status"]="failed";fresh["error"]=gemmaPromptArtifactError
      return .exhausted
    }
    fresh["gemmaPromptArtifactRepairPendingRevision"]=gemmaSourceBoundaryRepairRevision
    fresh["status"]="pending"
    return .retry
  }
  private static func preserveGemmaLayoutHistory(_ saved:[String:Any],fresh:inout [String:Any]) {
    for key in ["layoutRetryCount","layoutRetryPolicyVersion","layoutRetryLegacyCount","layoutRetryHistory",
      "layoutInitialFitError","layoutRetryError","translationReviewRequired","sourceDisagreementResolution"] {
      if let value=saved[key] {fresh[key]=value}
    }
  }
  static func beginGemmaCheckpointArtifactRepair(_ block:inout [String:Any])->Bool {
    guard (block["gemmaPromptArtifactRepairPendingRevision"] as? String)==gemmaSourceBoundaryRepairRevision else{return false}
    block["gemmaPromptArtifactRepairRevision"]=gemmaSourceBoundaryRepairRevision
    block.removeValue(forKey:"gemmaPromptArtifactRepairPendingRevision")
    return true
  }
  static func rejectGeneratedGemmaPromptArtifact(_ block:inout [String:Any],candidate:String) {
    block["gemmaPromptArtifactRepairRevision"]=gemmaSourceBoundaryRepairRevision
    block["gemmaPromptArtifactRejectedText"]=candidate
    block["translatedText"]=NSNull();block["status"]="failed";block["error"]=gemmaPromptArtifactError
    block.removeValue(forKey:"gemmaPromptArtifactRepairPendingRevision")
  }
  static func restoringPdfCheckpointResults(fresh:[[String:Any]],saved:[[String:Any]],online:Bool,gemma:Bool=false)->[[String:Any]]{
    var savedByID=[String:[String:Any]]()
    for block in saved {
      guard let id=block["id"] as? String,!id.isEmpty else{continue}
      savedByID[id]=block
    }
    return fresh.map{sourceBlock in
      guard let id=sourceBlock["id"] as? String,!id.isEmpty,
        let checkpoint=savedByID[id],Self.pdfCheckpointMatches(checkpoint,fresh:sourceBlock,online:online) else{return sourceBlock}
      var restored=sourceBlock
      let resolvesTocWarning=canResolveSavedTocDisagreement(saved:checkpoint,fresh:sourceBlock)
      if gemma {
        let outcome=prepareGemmaCheckpointArtifact(saved:checkpoint,fresh:&restored)
        if outcome != .none {
          if resolvesTocWarning {resolveTocDisagreement(saved:checkpoint,fresh:&restored)}
          return restored
        }
      }
      guard let status=checkpoint["status"] as? String,["translated","partial","failed"].contains(status) else{return restored}
      if status=="translated" {
        guard let translation=checkpoint["translatedText"] as? String,
          !translation.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty else{return restored}
      }
      for key in ["status","translatedText","error","onlineFragments","sourceDisagreement","sourceDisagreementResolution"] {
        if let value=checkpoint[key]{restored[key]=value}
      }
      if gemma {
        for key in ["gemmaPromptArtifactRepairRevision","gemmaPromptArtifactRepairPendingRevision",
          "gemmaPromptArtifactRejectedText","gemmaPromptArtifactRawText","gemmaPromptArtifactDecodedTokens"] {
          if let value=checkpoint[key]{restored[key]=value}
        }
      }
      if resolvesTocWarning {resolveTocDisagreement(saved:checkpoint,fresh:&restored)}
      return restored
    }
  }
  private static func canResolveSavedTocDisagreement(saved:[String:Any],fresh:[String:Any])->Bool {
    let noFreshError=(fresh["error"] as? String)?.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty ?? true
    let noFreshExportIssues:Bool
    if let value=fresh["exportIssues"] {
      if value is NSNull {noFreshExportIssues=true}
      else if let issues=value as? [Any] {noFreshExportIssues=issues.isEmpty}
      else {noFreshExportIssues=false}
    } else {noFreshExportIssues=true}
    let noSavedExportIssues:Bool
    if let value=saved["exportIssues"] {
      if value is NSNull {noSavedExportIssues=true}
      else if let issues=value as? [Any] {noSavedExportIssues=issues.isEmpty}
      else {noSavedExportIssues=false}
    } else {noSavedExportIssues=true}
    let savedTranslation=saved["translatedText"] as? String
    return fresh["verifiedTocLeaderOcrNoise"] as? Bool == true &&
      fresh["sourceDisagreement"] as? Bool != true && noFreshError &&
      fresh["translationReviewRequired"] as? Bool != true && noFreshExportIssues &&
      saved["sourceDisagreement"] as? Bool == true && saved["status"] as? String == "partial" &&
      saved["error"] as? String == sourceDisagreementBlockWarning &&
      savedTranslation?.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty == false &&
      saved["translationReviewRequired"] as? Bool != true && noSavedExportIssues &&
      ((saved["layoutRetryError"] as? String)?.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty ?? true) &&
      saved["gemmaPromptArtifactRepairPendingRevision"] == nil && saved["gemmaPromptArtifactRejectedText"] == nil
  }
  private static func resolveTocDisagreement(saved:[String:Any],fresh:inout [String:Any]) {
    var resolution:[String:Any]=["reason":"verified_toc_dotted_leader_ocr_noise","warning":sourceDisagreementBlockWarning]
    if let previous=saved["sourceDisagreementResolution"] {resolution["previous"]=previous}
    fresh["sourceDisagreementResolution"]=resolution
    for key in ["layoutRetryCount","layoutRetryPolicyVersion","layoutRetryLegacyCount","layoutRetryHistory","layoutInitialFitError"] {
      if let value=saved[key] {fresh[key]=value}
    }
    fresh["status"]="translated"
    fresh.removeValue(forKey:"sourceDisagreement")
    fresh.removeValue(forKey:"error")
  }
  static func pdfCheckpointMatches(_ checkpoint:[String:Any],fresh:[String:Any],online:Bool)->Bool{
    guard let id=checkpoint["id"] as? String,id==(fresh["id"] as? String),
      let source=checkpoint["sourceText"] as? String,source==(fresh["sourceText"] as? String),
      orderedPDFObjectIndices(checkpoint)==orderedPDFObjectIndices(fresh),
      pdfSymbolInventory(checkpoint)==pdfSymbolInventory(fresh) else{return false}
    if !online,let freshKeyValue=fresh["translationContextKey"] {
      guard let contextKey=freshKeyValue as? String else{return false}
      if let savedKeyValue=checkpoint["translationContextKey"] {
        guard let savedKey=savedKeyValue as? String,savedKey==contextKey else{return false}
      } else {
        // Older Gemma checkpoints were created without the InfoTitle-only
        // context contract. They remain reusable only when the current title
        // is empty, where the effective context is unchanged.
        guard contextKey==gemmaTranslationContextKey(title:"") else{return false}
      }
    } else if !online,checkpoint["translationContextKey"] != nil {
      return false
    }
    return translationInput(block:checkpoint,online:online)==translationInput(block:fresh,online:online)
  }
  private static func orderedPDFObjectIndices(_ block:[String:Any])->[Int]{
    if let values=block["objectIndices"] as? [NSNumber]{return values.map{$0.intValue}}
    if let values=block["objectIndices"] as? [Int]{return values}
    if let value=block["objectIndex"] as? NSNumber{return [value.intValue]}
    return []
  }
  private static func pdfSymbolInventory(_ block:[String:Any])->[String]{
    guard let rawSymbols=block["symbols"] else{return []}
    guard let symbols=rawSymbols as? [[String:Any]] else{return ["<invalid-symbol-inventory>"]}
    return symbols.map{symbol in
      guard let token=symbol["token"] as? String,!token.isEmpty else{return "<invalid-symbol>"}
      let objectIndex=(symbol["objectIndex"] as? NSNumber)?.stringValue ?? "missing"
      return "\(token)\u{001f}\(objectIndex)"
    }
  }
  static func matchOCRBlock(existing:[[String:Any]],text:String,left:Double,top:Double,right:Double,bottom:Double)->(index:Int,sourceDisagreement:Bool,verifiedTocLeaderNoise:Bool,ambiguousIndices:[Int])?{
    let ocrText=normalizedOCRText(text)
    guard !ocrText.isEmpty,right>left,bottom>top else{return nil}
    let ocrArea=(right-left)*(bottom-top)
    var contained:(index:Int,area:Double)?
    var overlap:(index:Int,ratio:Double,sourceText:String)?
    var verifiedTocCandidates=[(index:Int,sourceArea:Double)]()
    for (index,block) in existing.enumerated(){
      if block["source"] as? String == "ocr" { continue }
      for line in sourceLinesForOCR(block){
        let expanded=line.fragments.map{OCRSourceRect(text:$0.text,pictogram:$0.pictogram,
          left:$0.left-1.5,top:$0.top-1.5,right:$0.right+1.5,bottom:$0.bottom+1.5,baseline:$0.baseline)}
        let relevant=expanded.filter{max(0,min($0.right,right)-max($0.left,left))*max(0,min($0.bottom,bottom)-max($0.top,top))>0}
        guard !relevant.isEmpty else{continue}
        let coverage=sourceRectUnionCoverage(relevant,left:left,top:top,right:right,bottom:bottom)/ocrArea
        let linguisticRects=relevant.filter{!$0.pictogram}
        let joined=normalizedOCRText(linguisticRects.map(\.text).joined(separator:" "))
        let joinedWithoutSpaces=normalizedOCRText(linguisticRects.map(\.text).joined())
        let ocrComparison=comparisonOCRText(ocrText),joinedComparison=comparisonOCRText(joined),joinedCompactComparison=comparisonOCRText(joinedWithoutSpaces)
        let exactTextMatches = !ocrComparison.isEmpty && (
          joinedComparison==ocrComparison || joinedCompactComparison==ocrComparison ||
          (ocrComparison.count>=4 && (joinedComparison.contains(ocrComparison) || joinedCompactComparison.contains(ocrComparison)))
        )
        let pictogramLineMatches = relevant.contains(where: \.pictogram) && orderedOCRRunsMatch(line.textRuns,ocrComparison)
        let verifiedTocLeaderNoise = isVerifiedTocLeaderOCRNoise(block:block,line:line,
          recognizedText:text,left:left,top:top,right:right,bottom:bottom,ocrCoverage:coverage)
        if verifiedTocLeaderNoise {
          let sourceArea=sourceRectUnionCoverage(relevant,left:left,top:top,right:right,bottom:bottom)
          verifiedTocCandidates.append((index,sourceArea))
        }
        // Pictogram rectangles participate in geometric ownership, but their
        // surrogate Unicode is never evidence for OCR text. With text on both
        // sides, require the native linguistic runs to occur in order; OCR
        // glyph guesses between those runs belong to the pictogram region.
        // A fully covered pure pictogram OCR result is suppressed as a body
        // translation candidate without comparing its guessed characters.
        let purePictogramCoverage = linguisticRects.isEmpty && relevant.contains(where: \.pictogram)
        if coverage>=0.90 && (exactTextMatches || verifiedTocLeaderNoise || pictogramLineMatches || purePictogramCoverage) {
          let nativeArea=sourceRectUnionCoverage(relevant,left:left,top:top,right:right,bottom:bottom)
          if nativeArea<(contained?.area ?? .infinity){contained=(index,nativeArea)}
          if pictogramLineMatches || purePictogramCoverage{continue}
        }
        let overlapRatio=coverage
        if overlapRatio>0.45,materiallyDifferentOCRText(joined,ocrText),overlapRatio>(overlap?.ratio ?? 0){overlap=(index,overlapRatio,joined)}
      }
    }
    if verifiedTocCandidates.count>1 {
      let indices=Array(Set(verifiedTocCandidates.map(\.index))).sorted()
      guard let first=indices.first else{return nil}
      return(first,true,false,indices)
    }
    if verifiedTocCandidates.count==1,let verified=verifiedTocCandidates.first {
      return(verified.index,false,true,[])
    }
    if let contained{return(contained.index,false,false,[])}
    guard let overlap else{return nil}
    let nativeText=normalizedOCRText(overlap.sourceText)
    let disagrees=materiallyDifferentOCRText(nativeText,ocrText)
    return(overlap.index,disagrees,false,[])
  }

  private static func isVerifiedTocLeaderOCRNoise(block:[String:Any],line:OCRSourceLine,recognizedText:String,
    left:Double,top:Double,right:Double,bottom:Double,ocrCoverage:Double)->Bool {
    guard ocrCoverage>=0.95,line.fragments.count==1,
      let rects=block["sourceRects"] as? [[String:Any]],rects.count==1,
      let sourceRect=rects.first,(sourceRect["pictogram"] as? Bool) != true,
      let objectIndex=(sourceRect["objectIndex"] as? NSNumber)?.doubleValue,
      objectIndex.isFinite,objectIndex>=0,objectIndex.rounded(.towardZero)==objectIndex,
      let nativeLeft=(sourceRect["left"] as? NSNumber)?.doubleValue,
      let nativeTop=(sourceRect["top"] as? NSNumber)?.doubleValue,
      let nativeRight=(sourceRect["right"] as? NSNumber)?.doubleValue,
      let nativeBottom=(sourceRect["bottom"] as? NSNumber)?.doubleValue,
      nativeLeft.isFinite,nativeTop.isFinite,nativeRight.isFinite,nativeBottom.isFinite,
      nativeRight>nativeLeft,nativeBottom>nativeTop else{return false}
    guard orderedPDFObjectIndices(block).contains(Int(objectIndex)) else{return false}
    let nativeArea=(nativeRight-nativeLeft)*(nativeBottom-nativeTop)
    let ocrArea=(right-left)*(bottom-top)
    guard nativeArea.isFinite,nativeArea>0,ocrArea.isFinite,ocrArea>0 else{return false}
    let intersection=max(0,min(nativeRight,right)-max(nativeLeft,left))*max(0,min(nativeBottom,bottom)-max(nativeTop,top))
    guard intersection/nativeArea>=0.95,intersection/ocrArea>=0.95 else{return false}
    guard let source=parseTocReference(block["sourceText"] as? String ?? "",ocr:false),
      let fragment=parseTocReference(sourceRect["text"] as? String ?? "",ocr:false),
      let recognized=parseTocReference(recognizedText,ocr:true),
      let modelText=block["modelText"] as? String,source.label==modelText,
      comparisonOCRText(source.label)==comparisonOCRText(fragment.label),source.reference==fragment.reference,
      comparisonOCRText(source.label)==comparisonOCRText(recognized.label),source.reference==recognized.reference,
      (recognized.leader + recognized.noise).filter({$0=="."}).count>=3 else{return false}
    return true
  }

  private static func parseTocReference(_ value:String,ocr:Bool)->(label:String,leader:String,noise:String,reference:String)? {
    guard !value.contains("\n"),!value.contains("\r") else{return nil}
    let pattern=ocr ? "^(.+?)\\s*((?:\\.\\s*){3,})(.*?)\\s+(\\d{1,4})$" : "^(.+?)\\s*((?:\\.\\s*){3,})\\s+(\\d{1,4})$"
    guard let regex=try? NSRegularExpression(pattern:pattern),let match=regex.firstMatch(in:value,range:NSRange(value.startIndex...,in:value)),
      match.range.location==0,match.range.length==value.utf16.count else{return nil}
    func capture(_ index:Int)->String? {
      let range=match.range(at:index)
      guard range.location != NSNotFound,let swiftRange=Range(range,in:value) else{return nil}
      return String(value[swiftRange])
    }
    guard let label=capture(1)?.trimmingCharacters(in:.whitespacesAndNewlines),!label.isEmpty,
      let leader=capture(2) else{return nil}
    if ocr,let noise=capture(3),let reference=capture(4){return(label,leader,noise,reference)}
    if !ocr,let reference=capture(3){return(label,leader,"",reference)}
    return nil
  }

  static func sourceLinesForOCR(_ block:[String:Any])->[OCRSourceLine]{
    guard let values=block["sourceRects"] as? [[String:Any]] else{return []}
    var lines=[OCRSourceLine](),current=[OCRSourceRect]()
    func flush(){
      guard !current.isEmpty else{return}
      var runs=[String](),run=""
      for fragment in current{
        if fragment.pictogram{
          if !run.isEmpty{runs.append(run);run=""}
        }else{run += fragment.text}
      }
      if !run.isEmpty{runs.append(run)}
      lines.append(OCRSourceLine(fragments:current,textRuns:runs));current=[]
    }
    for value in values{
      let pictogram=(value["pictogram"] as? Bool)==true
      guard let text=value["text"] as? String,(pictogram || !normalizedOCRText(text).isEmpty),
        let left=(value["left"] as? NSNumber)?.doubleValue,let top=(value["top"] as? NSNumber)?.doubleValue,
        let right=(value["right"] as? NSNumber)?.doubleValue,let bottom=(value["bottom"] as? NSNumber)?.doubleValue,
        right>left,bottom>top else{return []}
      let rawBaseline=(value["baseline"] as? NSNumber)?.doubleValue
      let fragment=OCRSourceRect(text:pictogram ? "":text,pictogram:pictogram,left:left,top:top,right:right,bottom:bottom,
        baseline:rawBaseline?.isFinite == true ? rawBaseline:nil)
      guard let previous=current.last else{current.append(fragment);continue}
      let previousHeight=previous.bottom-previous.top,height=fragment.bottom-fragment.top
      let sameBaseline:Bool
      if let a=previous.baseline,let b=fragment.baseline{
        sameBaseline=abs(a-b)<=max(2.5,min(3.0,min(previousHeight,height)*0.25))
      }else{
        let verticalOverlap=max(0,min(previous.bottom,fragment.bottom)-max(previous.top,fragment.top))
        sameBaseline=verticalOverlap>=min(previousHeight,height)*0.5
      }
      let gap=fragment.left-previous.right
      let iconAdjacent=previous.pictogram || fragment.pictogram
      let maxGap=iconAdjacent ? max(12.0,max(previousHeight,height)*1.2):max(1.5,max(previousHeight,height)*0.8)
      let nearby=gap>=(-1.5) && gap<=maxGap
      if !(sameBaseline && nearby){flush()}
      current.append(fragment)
    }
    flush()
    return lines
  }

  static func orderedOCRRunsMatch(_ runs:[String],_ ocrComparison:String)->Bool{
    guard !runs.isEmpty,!ocrComparison.isEmpty else{return false}
    var searchStart=ocrComparison.startIndex
    for run in runs{
      let value=comparisonOCRText(normalizedOCRText(run))
      guard !value.isEmpty,let range=ocrComparison.range(of:value,range:searchStart..<ocrComparison.endIndex) else{return false}
      searchStart=range.upperBound
    }
    return true
  }

  private static func sourceRectUnionCoverage(_ rects:[OCRSourceRect],left:Double,top:Double,right:Double,bottom:Double)->Double{
    let clipped=rects.compactMap{rect -> (left:Double,top:Double,right:Double,bottom:Double)? in
      let l=max(left,rect.left),t=max(top,rect.top),r=min(right,rect.right),b=min(bottom,rect.bottom)
      return r>l && b>t ? (l,t,r,b):nil
    }
    guard !clipped.isEmpty else{return 0}
    let xs=Array(Set(clipped.flatMap{[$0.left,$0.right]})).sorted()
    guard xs.count>1 else{return 0}
    var area=0.0
    for idx in 0..<(xs.count-1){
      let x0=xs[idx],x1=xs[idx+1]
      guard x1>x0 else{continue}
      let intervals=clipped.filter{$0.left<x1 && $0.right>x0}.map{($0.top,$0.bottom)}.sorted{$0.0<$1.0}
      guard var current=intervals.first else{continue}
      var vertical=0.0
      for interval in intervals.dropFirst(){
        if interval.0<=current.1{current.1=max(current.1,interval.1)}
        else{vertical+=current.1-current.0;current=interval}
      }
      vertical+=current.1-current.0
      area+=(x1-x0)*vertical
    }
    return area
  }

  private static func normalizedOCRText(_ text:String)->String{
    let input=text.precomposedStringWithCanonicalMapping.lowercased()
    var output=String.UnicodeScalarView(),spacePending=false
    for scalar in input.unicodeScalars{
      if CharacterSet.whitespacesAndNewlines.contains(scalar) || CharacterSet.controlCharacters.contains(scalar){spacePending = !output.isEmpty}
      else{if spacePending{output.append(" ");spacePending=false};output.append(scalar)}
    }
    return String(output)
  }

  private static func materiallyDifferentOCRText(_ native:String,_ ocr:String)->Bool{
    let a=comparisonOCRText(native),b=comparisonOCRText(ocr)
    guard !a.isEmpty,!b.isEmpty,a != b else{return false}
    if a.count<4 || b.count<4{return true}
    return !a.contains(b) && !b.contains(a)
  }

  private static func comparisonOCRText(_ text:String)->String{
    String(String.UnicodeScalarView(text.unicodeScalars.filter{CharacterSet.alphanumerics.contains($0)}))
  }

  @available(iOS 26.0,*) func scheduleContinuedProcessing(jobID:String,documentName:String)->ContinuedProcessingRequestToken?{
    let bundle=Bundle.main.bundleIdentifier ?? "dev.kigya.translatepdf"
    stateLock.lock()
    continuedTaskGenerations[jobID,default:0] &+= 1
    let generation=continuedTaskGenerations[jobID,default:0]
    let jobStatus=(try? store.job(jobID)?["status"] as? String) ?? nil
    let runToken=activeRunToken
    let canSchedule=activeJobID==jobID && runToken != nil && !pauseRequested && !cancelRequested && ["queued","running"].contains(jobStatus ?? "")
    let identifier=canSchedule ? "\(bundle).continuedProcessing.\(UUID().uuidString.lowercased())" : nil
    let previousIdentifier=continuedTaskRequestIDs.removeValue(forKey:jobID)
    continuedTaskRequestRunTokens.removeValue(forKey:jobID)
    if let identifier,let runToken { continuedTaskRequestIDs[jobID]=identifier;continuedTaskRequestRunTokens[jobID]=runToken }
    stateLock.unlock()
    if let previousIdentifier { cancelContinuedTaskRequest(identifier:previousIdentifier) }
    guard canSchedule,let identifier else{return nil}
    Self.continuedTaskRegistrationLock.lock()
    stateLock.lock()
    let latestStatus=(try? store.job(jobID)?["status"] as? String) ?? nil
    let stillEligible=continuedTaskGenerations[jobID,default:0]==generation && activeJobID==jobID &&
      activeRunToken==runToken && continuedTaskRequestRunTokens[jobID]==runToken &&
      continuedTaskRequestIDs[jobID]==identifier && !pauseRequested && !cancelRequested && ["queued","running"].contains(latestStatus ?? "")
    stateLock.unlock()
    guard stillEligible else{Self.continuedTaskRegistrationLock.unlock();return nil}
    Self.continuedTaskOwners[identifier]=ContinuedTaskOwner(self)
    let shouldRegister = !Self.registeredContinuedTaskIds.contains(identifier)
    let canSubmit: Bool
    if shouldRegister {
      let registered=BGTaskScheduler.shared.register(forTaskWithIdentifier:identifier,using:nil){ task in
        Self.continuedTaskRegistrationLock.lock()
        let owner=Self.continuedTaskOwners[identifier]?.module
        Self.continuedTaskRegistrationLock.unlock()
        guard let self=owner,let continued=task as? BGContinuedProcessingTask else{task.setTaskCompleted(success:false);return}
        guard let runToken else{continued.setTaskCompleted(success:false);return}
        self.handleContinuedProcessing(continued,jobID:jobID,identifier:identifier,generation:generation,runToken:runToken)
      }
      if registered { Self.registeredContinuedTaskIds.insert(identifier) }
      canSubmit=registered
    } else {
      canSubmit=true
    }
    var submitted=false
    if canSubmit {
      let title="Translating PDF",subtitle=String(documentName.prefix(60))
      let request=BGContinuedProcessingTaskRequest(identifier:identifier,title:title,subtitle:subtitle)
      request.strategy = .queue
      do{
        if let submitContinuedTaskRequestForTesting {try submitContinuedTaskRequestForTesting(request)}
        else{try BGTaskScheduler.shared.submit(request)}
        submitted=true
      }
      catch{NSLog("Could not schedule continued processing: %@",error.localizedDescription)}
    } else {
      NSLog("Could not register continued processing for the active job.")
    }
    if !submitted {
      Self.continuedTaskOwners.removeValue(forKey:identifier)
      stateLock.lock()
      if continuedTaskRequestIDs[jobID]==identifier {
        continuedTaskRequestIDs.removeValue(forKey:jobID)
        continuedTaskRequestRunTokens.removeValue(forKey:jobID)
      }
      stateLock.unlock()
    }
    Self.continuedTaskRegistrationLock.unlock()
    guard submitted else{return nil}
    return ContinuedProcessingRequestToken(identifier:identifier,generation:generation)
  }
  @available(iOS 26.0,*) static func continuedTaskOwner(for identifier:String)->PdfTranslatorModule?{
    continuedTaskRegistrationLock.lock();defer{continuedTaskRegistrationLock.unlock()}
    return continuedTaskOwners[identifier]?.module
  }
  @available(iOS 26.0,*) private func handleContinuedProcessing(_ continued:BGContinuedProcessingTask,jobID:String,identifier:String,generation:UInt64,runToken:UUID){
    let attached=attachContinuedProcessing(jobID:jobID,identifier:identifier,generation:generation) {
      self.continuedTask=continued;self.continuedTaskJobID=jobID;self.continuedTaskRunToken=runToken
    }
    guard attached else{
      cancelContinuedTaskRequest(identifier:identifier)
      continued.setTaskCompleted(success:false)
      return
    }
    continued.expirationHandler={[weak self] in
      self?.requestPause(jobID:jobID,reason:"iOS ended continued processing; completed pages are saved.",continuedGeneration:generation,backgroundRunToken:runToken)
    }
  }

  @available(iOS 26.0,*) func attachContinuedProcessing(jobID:String,identifier:String,generation:UInt64,attach:()->Void)->Bool {
    stateLock.lock();defer{stateLock.unlock()}
    guard !pauseRequested,!cancelRequested,let job=try? store.job(jobID),
      let status=job["status"] as? String,["queued","running"].contains(status),
      activeJobID==jobID,continuedTaskGenerations[jobID,default:0]==generation,
      activeRunToken != nil,continuedTaskRequestRunTokens[jobID]==activeRunToken,
      continuedTaskRequestIDs[jobID]==identifier else{return false}
    attach()
    return true
  }

  @available(iOS 26.0,*) func cancelQueuedContinuedProcessing(jobID:String,runToken expectedRunToken:UUID?=nil){
    stateLock.lock()
    if let expectedRunToken,continuedTaskRequestRunTokens[jobID] != expectedRunToken {stateLock.unlock();return}
    if expectedRunToken == nil,activeJobID==jobID {stateLock.unlock();return}
    continuedTaskGenerations[jobID,default:0] &+= 1
    let identifier=continuedTaskRequestIDs.removeValue(forKey:jobID)
    continuedTaskRequestRunTokens.removeValue(forKey:jobID)
    stateLock.unlock()
    if let identifier { cancelContinuedTaskRequest(identifier:identifier) }
  }

  @available(iOS 26.0,*) private func finishContinuedTaskForRun(jobID:String,runToken:UUID,success:Bool){
    stateLock.lock()
    guard continuedTaskJobID==jobID,continuedTaskRunToken==runToken else{stateLock.unlock();return}
    let task=continuedTask
    continuedTask=nil;continuedTaskJobID=nil;continuedTaskRunToken=nil
    stateLock.unlock()
    task?.setTaskCompleted(success:success)
  }

  @available(iOS 26.0,*) private func cancelContinuedTaskRequest(identifier:String){
    Self.continuedTaskRegistrationLock.lock()
    BGTaskScheduler.shared.cancel(taskRequestWithIdentifier:identifier)
    if Self.continuedTaskOwners[identifier]?.module === self { Self.continuedTaskOwners.removeValue(forKey:identifier) }
    Self.continuedTaskRegistrationLock.unlock()
  }


}

@available(iOS 26.0,*) private final class ContinuedTaskOwner {
  weak var module:PdfTranslatorModule?
  init(_ module:PdfTranslatorModule){self.module=module}
}

extension PdfTranslatorModule: UIDocumentPickerDelegate {
  func documentPicker(_ controller:UIDocumentPickerViewController,didPickDocumentsAt urls:[URL]){
    if pendingImport != nil {
      guard let url=urls.first else{pendingImport?("null");pendingImport=nil;pendingImportReject=nil;return}
      work.async { do {
        let result=try self.importDocument(url),payload=(try? jsonString(result)) ?? "null"
        DispatchQueue.main.async { self.pendingImport?(payload);self.pendingImport=nil;self.pendingImportReject=nil }
      } catch { self.finishImportError(error) } }
      return
    }
    guard let info=pendingExportInfo else{return}
    if let destination=urls.first {
      let warnings=(try? store.job(info.id)?["warnings"] as? [[String:Any]]) ?? []
      let payload=(try? jsonString(["id":info.id,"uri":destination.absoluteString,"displayName":info.name,"mimeType":pendingExportMIME,"format":pendingExportMIME.contains("wordprocessingml") ? "docx":"pdf","warnings":warnings])) ?? "null"
      pendingExport?(payload)
    } else { pendingExportReject?("EXPORT_CANCELED","PDF export was canceled.",nil) }
    pendingExport=nil;pendingExportReject=nil;pendingExportInfo=nil
  }
  func documentPickerWasCancelled(_ controller:UIDocumentPickerViewController){
    if pendingImport != nil{pendingImport?("null");pendingImport=nil;pendingImportReject=nil}
    else if pendingExportInfo != nil{pendingExportReject?("EXPORT_CANCELED","PDF export was canceled.",nil);pendingExport=nil;pendingExportReject=nil;pendingExportInfo=nil}
  }
}

private struct PauseSignal:Error {
  let reason:String
  init(_ reason:String = "Paused after the latest saved page checkpoint.") { self.reason=reason }
}
private struct CancelSignal:Error{}

import Foundation
import React
import UIKit
import UniformTypeIdentifiers
import Vision
import QuickLook
import BackgroundTasks
import Darwin

private final class PdfPreviewController: QLPreviewController, QLPreviewControllerDataSource {
  private let file: URL
  init(file:URL){self.file=file;super.init(nibName:nil,bundle:nil);dataSource=self}
  required init?(coder:NSCoder){nil}
  func numberOfPreviewItems(in controller:QLPreviewController)->Int{1}
  func previewController(_ controller:QLPreviewController,previewItemAt index:Int)->QLPreviewItem{file as NSURL}
}

@objc(PdfTranslatorModule)
final class PdfTranslatorModule: NSObject, RCTBridgeModule {
  static func moduleName() -> String! { "PdfTranslator" }
  static func requiresMainQueueSetup() -> Bool { false }

  private let work = DispatchQueue(label:"dev.kigya.translatepdf.native-queue",qos:.userInitiated)
  private let translationWork = DispatchQueue(label:"dev.kigya.translatepdf.translation-queue",qos:.userInitiated)
  private let stateLock=NSRecursiveLock()
  private var store:TranslatorStore!
  private var downloader:ModelDownloadCoordinator!
  private var pendingImport:RCTPromiseResolveBlock?
  private var pendingImportReject:RCTPromiseRejectBlock?
  private var pendingExport:RCTPromiseResolveBlock?
  private var pendingExportReject:RCTPromiseRejectBlock?
  private var pendingExportInfo:(id:String,url:URL,name:String)?
  private var activeJobID:String?
  private var pauseRequested=false
  private var cancelRequested=false
  private var systemLowMemory=false
  private var backgroundTask:UIBackgroundTaskIdentifier = .invalid
  private var continuedTask:BGTask?
  private var registeredTaskIds=Set<String>()
  private let onboardingVersion=1

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
    run(resolve,reject,"BOOTSTRAP_FAILED") {
      guard let store=self.store else { throw self.error("Private storage is unavailable.") }
      let total=ProcessInfo.processInfo.physicalMemory
      let available=UInt64(os_proc_available_memory())
      let disk=try AppPaths.support.resourceValues(forKeys:[.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
      self.stateLock.lock();let lowMemory=self.systemLowMemory;self.stateLock.unlock()
      let availableDisk=UInt64(max(0,disk))
      let modelStates=try store.models(totalMemory:total,availableMemory:available,availableDisk:availableDisk,systemLowMemory:lowMemory)
      var assessments=[String:ModelResourceAssessment]()
      for model in ModelCatalog.models {
        let state=try store.modelState(model.id)
        assessments[model.id]=MemoryPolicy.assess(model,status:state?["status"] as? String ?? "not_downloaded",downloadedBytes:state?["downloaded_bytes"] as? Int64 ?? 0,total:total,available:available,availableDisk:availableDisk,systemLowMemory:lowMemory)
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
      return try jsonString(["schemaVersion":1,"device":device,"preferences":["theme":theme,"language":language,"sourceLanguage":sourceLanguage,"targetLanguage":targetLanguage,"selectedModelId":selectedModelId],"onboarding":["version":version,"page":page,"done":done],"models":modelStates,"documents":docs,"jobs":jobs,"glossary":glossary])
    }
  }

  @objc func pickPdf(_ resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    DispatchQueue.main.async {
      guard self.pendingImport == nil else { reject("PICKER_BUSY","Another PDF picker is already open.",nil);return }
      guard let presenter=self.topPresenter() else { reject("PICKER_UNAVAILABLE","Open the app before choosing a PDF.",nil);return }
      self.pendingImport=resolve;self.pendingImportReject=reject
      let controller=UIDocumentPickerViewController(forOpeningContentTypes:[.pdf],asCopy:false)
      controller.delegate=self;controller.allowsMultipleSelection=false
      presenter.present(controller,animated:true)
    }
  }

  @objc func startModelDownload(_ modelId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"MODEL_DOWNLOAD_START_FAILED") { let model=try ModelCatalog.get(modelId);return try jsonString(self.downloader.start(model)) }
  }
  @objc func pauseModelDownload(_ modelId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"MODEL_DOWNLOAD_PAUSE_FAILED") { let model=try ModelCatalog.get(modelId);return try jsonString(self.downloader.pause(model)) }
  }
  @objc func deleteModel(_ modelId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"MODEL_DELETE_FAILED") { let jobs=try self.store.jobs();guard !jobs.contains(where:{($0["modelId"] as? String)==modelId && ["queued","running"].contains($0["status"] as? String ?? "")}) else{throw self.error("A queued or active translation uses this model. Cancel or finish that job before deleting it.")};try self.downloader.delete(ModelCatalog.get(modelId));return NSNull() }
  }

  @objc func startJob(_ inputJson:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"JOB_START_FAILED") {
      guard UIApplication.shared.applicationState == .active else { throw self.error("Return to the open app before starting translation.") }
      guard let input=try JSONSerialization.jsonObject(with:Data(inputJson.utf8)) as? [String:Any],let documentId=input["documentId"] as? String,let modelId=input["modelId"] as? String else { throw self.error("Translation request is invalid.") }
      guard let sourceLanguage=input["sourceLanguage"] as? String,let targetLanguage=input["targetLanguage"] as? String else { throw self.error("Choose a source and target language before starting translation.") }
      _ = try TranslationLanguage.pair(source:sourceLanguage,target:targetLanguage)
      self.stateLock.lock();let alreadyRunning=self.activeJobID != nil;self.stateLock.unlock()
      guard !alreadyRunning else { throw self.error("Another local translation is already running. Pause or finish it before starting a second job.") }
      guard let path=try self.store.documentPath(documentId),FileManager.default.fileExists(atPath:path) else { throw self.error("The imported document is no longer available.") }
      let model=try ModelCatalog.get(modelId)
      guard model.translationEnabled else { throw self.error("The selected model is not enabled for translation.") }
      let glossary=try self.store.glossaryText(input["glossaryId"] as? String,sourceLanguage:sourceLanguage,targetLanguage:targetLanguage)
      guard model.supportsGlossary || glossary.isEmpty else { throw self.error("The selected model profile does not support glossary instructions. Clear the glossary or choose a glossary-compatible model before starting a job.") }
      guard try self.downloader.isReady(model) else { throw self.error("Download and verify the selected model first.") }
      let assessment=try self.assessment(for:model,status:"ready",downloadedBytes:model.bytes)
      guard assessment.diskEstimateEligible else { throw self.error("The selected model is currently unavailable: \(assessment.reason).") }
      let info=try jsonObject(TranslateCoreBridge.inspect(path))
      if let problem=info["error"] as? String { throw self.error(problem) }
      let count=(info["pageCount"] as? NSNumber)?.intValue ?? 0
      guard count>0,count<=10_000 else { throw self.error("The PDF has an unsupported page count or could not be inspected.") }
      let id=UUID().uuidString.lowercased()
      let job=try self.store.addJob(id:id,input:input,pageCount:count)
      self.stateLock.lock();self.activeJobID=id;self.pauseRequested=false;self.cancelRequested=false;self.stateLock.unlock()
      self.beginBackgroundExecution(jobID:id)
      if #available(iOS 26.0,*) { self.scheduleContinuedProcessing(jobID:id,documentName:(job["documentName"] as? String) ?? "PDF") }
      self.translationWork.async { self.processJob(id) }
      return try jsonString(job)
    }
  }
  @objc func pauseJob(_ jobId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"JOB_PAUSE_FAILED") { guard let job=try self.store.job(jobId) else { throw self.error("Translation job was not found.") };self.stateLock.lock();if self.activeJobID==jobId{self.pauseRequested=true;TranslateCoreBridge.cancel()};self.stateLock.unlock();if self.activeJobID != jobId { try self.store.updateJob(jobId,status:"paused") };return try jsonString(try self.store.job(jobId) ?? job) }
  }
  @objc func resumeJob(_ jobId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"JOB_RESUME_FAILED") {
      guard UIApplication.shared.applicationState == .active else{throw self.error("Return to the open app before resuming translation.")}
      guard var job=try self.store.job(jobId) else{throw self.error("Translation job was not found.")}
      guard ["paused","queued","failed"].contains(job["status"] as? String ?? "") else{throw self.error("This job cannot be resumed in its current state.")}
      self.stateLock.lock();let busy=self.activeJobID != nil;self.stateLock.unlock()
      guard !busy else{throw self.error("Another local translation is already running.")}
      let model=try ModelCatalog.get(job["modelId"] as? String ?? "")
      try self.store.validateJobSnapshot(jobId,model:model)
      let total=(job["totalPages"] as? NSNumber)?.intValue ?? 0
      if try self.requiresInference(jobId,totalPages:total) {
        guard model.translationEnabled else{throw self.error("The selected model is not enabled for translation.")}
        guard try self.downloader.isReady(model) else{throw self.error("Download and verify the model before resuming.")}
        let assessment=try self.assessment(for:model,status:"ready",downloadedBytes:model.bytes)
        // Estimated RAM remains a recommendation only. The runtime may still fail to allocate;
        // that failure pauses from the latest durable checkpoint without switching models.
      }
      try self.store.updateJob(jobId,status:"queued",stage:"queued",clearError:true)
      job=try self.store.job(jobId) ?? job
      self.stateLock.lock();self.activeJobID=jobId;self.pauseRequested=false;self.cancelRequested=false;self.systemLowMemory=false;self.stateLock.unlock()
      self.beginBackgroundExecution(jobID:jobId)
      if #available(iOS 26.0,*){self.scheduleContinuedProcessing(jobID:jobId,documentName:job["documentName"] as? String ?? "PDF")}
      self.translationWork.async{self.processJob(jobId)}
      return try jsonString(job)
    }
  }
  @objc func cancelJob(_ jobId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"JOB_CANCEL_FAILED") { guard let job=try self.store.job(jobId) else { throw self.error("Translation job was not found.") };self.stateLock.lock();if self.activeJobID==jobId{self.cancelRequested=true;TranslateCoreBridge.cancel()};self.stateLock.unlock();if self.activeJobID != jobId { try self.store.updateJob(jobId,status:"canceled",error:"Canceled by the user.") };return try jsonString(try self.store.job(jobId) ?? job) }
  }
  @objc func deleteJob(_ jobId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"JOB_DELETE_FAILED") { guard let job=try self.store.job(jobId) else { throw self.error("Translation job was not found.") };guard !["running","queued"].contains(job["status"] as? String ?? "") else { throw self.error("Pause or cancel the active translation before deleting it.") };try self.store.deleteExport(jobId);try? FileManager.default.removeItem(at:AppPaths.exports.appendingPathComponent("\(jobId).pdf"));try? FileManager.default.removeItem(at:AppPaths.exports.appendingPathComponent("\(jobId).pending.pdf"));try self.store.deleteJob(jobId);try? FileManager.default.removeItem(at:AppPaths.pages.appendingPathComponent(jobId,isDirectory:true));return NSNull() }
  }
  @objc func getJob(_ jobId:String,pageIndex:NSNumber,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"JOB_READ_FAILED") { guard let job=try self.store.job(jobId) else { throw self.error("Translation job was not found.") };let rows=try self.store.pageRows(jobId);let total=(job["totalPages"] as? NSNumber)?.intValue ?? 0;let selected=pageIndex.intValue>=0 && pageIndex.intValue<total ? try self.store.page(jobId,index:pageIndex.intValue) : nil;let selectedValue:Any=selected.map{$0 as Any} ?? NSNull();return try jsonString(["job":job,"pages":rows,"selectedPage":selectedValue]) }
  }
  @objc func renderPage(_ documentId:String,pageIndex:NSNumber,width:NSNumber,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"THUMBNAIL_FAILED") { guard pageIndex.intValue>=0,(64...2048).contains(width.intValue),let path=try self.store.documentPath(documentId) else { throw self.error("The document, page, or preview size is invalid.") };try? FileManager.default.createDirectory(at:AppPaths.thumbnails,withIntermediateDirectories:true);let url=AppPaths.thumbnails.appendingPathComponent("\(documentId)_\(pageIndex.intValue)_\(width.intValue).png");if !FileManager.default.fileExists(atPath:url.path){guard let data=TranslateCoreBridge.renderPNG(path,index:pageIndex.intValue,width:width.intValue) else { throw self.error("The page preview could not be rendered.") };try data.write(to:url,options:.atomic)};return url.absoluteString }
  }
  @objc func renderTranslatedPage(_ jobId:String,pageIndex:NSNumber,width:NSNumber,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"TRANSLATED_THUMBNAIL_FAILED") {
      let index=pageIndex.intValue,previewWidth=width.intValue
      guard index>=0,(64...2048).contains(previewWidth),let source=try self.store.jobSource(jobId),
        let savedPage=try self.store.page(jobId,index:index),let blocks=savedPage["blocks"] else {
        throw self.error("The translated page, job, or preview size is unavailable.")
      }
      if let job=try self.store.job(jobId),let total=(job["totalPages"] as? NSNumber)?.intValue,
        let cached=try self.verifiedExport(jobId,expectedPages:total) {
        let thumbnail=AppPaths.thumbnails.appendingPathComponent("translated_\(jobId)_\(index)_\(previewWidth).png")
        if !FileManager.default.fileExists(atPath:thumbnail.path) {
          guard let data=TranslateCoreBridge.renderPNG(cached.url.path,index:index,width:previewWidth) else{throw self.error("The saved translated page preview could not be rendered.")}
          try data.write(to:thumbnail,options:.atomic)
        }
        return thumbnail.absoluteString
      }
      let pageDirectory=AppPaths.pages.appendingPathComponent(jobId,isDirectory:true)
      try FileManager.default.createDirectory(at:pageDirectory,withIntermediateDirectories:true)
      let shard=pageDirectory.appendingPathComponent("translated-preview-\(index).pdf")
      let rendered=TranslateCoreBridge.exportPage(source,index:index,blocksJSON:try jsonString(blocks),destination:shard.path,fontPath:self.fontPath(),cjkFontPath:self.cjkFontPath())
      let exportResult=try jsonObject(rendered)
      if let message=exportResult["error"] as? String { throw self.error(message) }
      guard FileManager.default.fileExists(atPath:shard.path) else { throw self.error("The translated page preview could not be created.") }
      try FileManager.default.createDirectory(at:AppPaths.thumbnails,withIntermediateDirectories:true)
      let thumbnail=AppPaths.thumbnails.appendingPathComponent("translated_\(jobId)_\(index)_\(previewWidth).png")
      guard let data=TranslateCoreBridge.renderPNG(shard.path,index:0,width:previewWidth) else { throw self.error("The translated page preview could not be rendered.") }
      try data.write(to:thumbnail,options:.atomic)
      return thumbnail.absoluteString
    }
  }
  @objc func previewJob(_ jobId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    work.async { do { let result=try self.buildExport(jobId);let payload=try jsonString(["id":result.id,"uri":result.url.absoluteString,"displayName":result.name]);DispatchQueue.main.async { resolve(payload) } } catch { DispatchQueue.main.async { reject("PREVIEW_FAILED",error.localizedDescription,error) } } }
  }
  @objc func exportJob(_ jobId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    work.async { do { let result=try self.buildExport(jobId);DispatchQueue.main.async { self.presentExportFile(id:result.id,url:result.url,name:result.name,resolve:resolve,reject:reject) } } catch { DispatchQueue.main.async { reject("EXPORT_FAILED",error.localizedDescription,error) } } }
  }
  @objc func shareExport(_ exportId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    work.async { do {
      guard let job=try self.store.job(exportId),let total=(job["totalPages"] as? NSNumber)?.intValue,
        let verified=try self.verifiedExport(exportId,expectedPages:total) else{throw self.error("The verified translated PDF is unavailable.")}
      DispatchQueue.main.async { guard let presenter=self.topPresenter() else{reject("SHARE_FAILED","Open the app before sharing the translated PDF.",nil);return};let activity=UIActivityViewController(activityItems:[verified.url],applicationActivities:nil);if let pop=activity.popoverPresentationController{pop.sourceView=presenter.view;pop.sourceRect=CGRect(x:presenter.view.bounds.midX,y:presenter.view.bounds.midY,width:1,height:1)};presenter.present(activity,animated:true){resolve(nil)} }
    } catch { DispatchQueue.main.async {reject("SHARE_FAILED",error.localizedDescription,error)} } }
  }
  @objc func openPdf(_ documentId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    DispatchQueue.main.async { guard let path=try? self.store.documentPath(documentId),FileManager.default.fileExists(atPath:path),let presenter=self.topPresenter() else { reject("OPEN_PDF_FAILED","The document is unavailable.",nil);return };presenter.present(PdfPreviewController(file:URL(fileURLWithPath:path)),animated:true){resolve(nil)} }
  }
  @objc func setOnboarding(_ stateJson:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"ONBOARDING_SAVE_FAILED"){guard let state=try JSONSerialization.jsonObject(with:Data(stateJson.utf8)) as? [String:Any],let version=(state["version"] as? NSNumber)?.intValue,let page=(state["page"] as? NSNumber)?.intValue,let done=state["done"] as? Bool,version>0,(0...3).contains(page) else { throw self.error("Onboarding state is invalid.") };try self.store.setSetting("onboarding_version",String(version));try self.store.setSetting("onboarding_page",String(page));try self.store.setSetting("onboarding_done",String(done));return try jsonString(["version":version,"page":page,"done":done]) }
  }
  @objc func setSettings(_ settingsJson:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"SETTINGS_SAVE_FAILED"){guard let settings=try JSONSerialization.jsonObject(with:Data(settingsJson.utf8)) as? [String:Any] else { throw self.error("Settings are invalid.") };if let theme=settings["theme"] as? String { guard ["system","light","dark"].contains(theme) else { throw self.error("Theme selection is invalid.") };try self.store.setSetting("theme",theme) };if let language=settings["language"] as? String { guard language == "system" || language.range(of:"^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$",options:.regularExpression) != nil else { throw self.error("Language selection is invalid.") };try self.store.setSetting("language",language) };let savedPair=try self.store.languagePreferences();let source=settings["sourceLanguage"] as? String ?? savedPair.sourceLanguage;let target=settings["targetLanguage"] as? String ?? savedPair.targetLanguage;if settings["sourceLanguage"] != nil || settings["targetLanguage"] != nil { try self.store.setLanguagePreferences(sourceLanguage:source,targetLanguage:target) };if let modelId=settings["selectedModelId"] as? String { _ = try self.store.setSelectedModelId(modelId) };return try jsonString(["theme":try self.store.setting("theme",fallback:"system"),"language":try self.store.setting("language",fallback:"system"),"sourceLanguage":source,"targetLanguage":target,"selectedModelId":try self.store.selectedModelId()]) }
  }
  @objc func saveGlossaryEntry(_ entryJson:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){
    run(resolve,reject,"GLOSSARY_SAVE_FAILED"){guard let item=try JSONSerialization.jsonObject(with:Data(entryJson.utf8)) as? [String:Any],let id=item["id"] as? String,let source=item["source"] as? String,let target=item["target"] as? String,let sourceLanguage=item["sourceLanguage"] as? String,let targetLanguage=item["targetLanguage"] as? String,!source.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty,!target.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty else { throw self.error("Both glossary terms and their language pair are required.") };return try jsonString(self.store.saveGlossary(id,source:source,target:target,sourceLanguage:sourceLanguage,targetLanguage:targetLanguage)) }
  }
  @objc func deleteGlossaryEntry(_ entryId:String,resolver resolve:@escaping RCTPromiseResolveBlock,rejecter reject:@escaping RCTPromiseRejectBlock){run(resolve,reject,"GLOSSARY_DELETE_FAILED"){try self.store.deleteGlossary(entryId);return NSNull()}}

  private func run(_ resolve:@escaping RCTPromiseResolveBlock,_ reject:@escaping RCTPromiseRejectBlock,_ code:String,_ body:@escaping() throws->Any){work.async{do{let result=try body();DispatchQueue.main.async{resolve(result)}}catch{DispatchQueue.main.async{reject(code,error.localizedDescription,error)}}}}
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
    for row in try store.query("SELECT id,status,total_pages FROM jobs WHERE status IN ('completed','partial')") {
      guard let id=row["id"] as? String else{continue}
      let total=(row["total_pages"] as? Int64).map(Int.init) ?? 0
      guard (try? verifiedExport(id,expectedPages:total)) == nil else{continue}
      try store.deleteExport(id)
      try store.updateJob(id,status:"paused",stage:"queued",completed:max(0,total-1),error:"This saved job has no verified translated PDF yet. Resume to finish PDF assembly from its saved pages.")
    }
  }
  private func requiresInference(_ jobId:String,totalPages:Int)throws->Bool {
    guard totalPages>0 else{return true}
    let saved=try store.pageRows(jobId)
    let byIndex=Dictionary(uniqueKeysWithValues:saved.compactMap { row -> (Int,String)? in
      guard let index=(row["index"] as? NSNumber)?.intValue,let status=row["status"] as? String else{return nil}
      return(index,status)
    })
    for index in 0..<totalPages {
      if ["completed","partial"].contains(byIndex[index] ?? "") {continue}
      guard let page=try store.page(jobId,index:index),let blocks=page["blocks"] as? [[String:Any]] else{return true}
      for block in blocks {
        let source=(block["sourceText"] as? String ?? "").trimmingCharacters(in:.whitespacesAndNewlines)
        guard !source.isEmpty else{continue}
        let translated=(block["translatedText"] as? String ?? "").trimmingCharacters(in:.whitespacesAndNewlines)
        if translated.isEmpty || (block["status"] as? String) != "translated" {return true}
      }
    }
    return false
  }
  private func importDocument(_ source:URL)throws->[String:Any]{
    let scoped=source.startAccessingSecurityScopedResource();defer{if scoped{source.stopAccessingSecurityScopedResource()}}
    guard let size=try? source.resourceValues(forKeys:[.fileSizeKey]).fileSize,size>0 else{throw error("The selected PDF could not be read.")}
    let free=try AppPaths.documents.resourceValues(forKeys:[.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
    guard Int64(size)<free else{throw error("Not enough free space to import this PDF into private app storage.")}
    let id=UUID().uuidString.lowercased(),incoming=AppPaths.documents.appendingPathComponent("\(id).incoming"),target=AppPaths.documents.appendingPathComponent("\(id).pdf")
    do {
      let input=try FileHandle(forReadingFrom:source);defer{try? input.close()};FileManager.default.createFile(atPath:incoming.path,contents:nil)
      let output=try FileHandle(forWritingTo:incoming);defer{try? output.close()}
      var header=Data();while true{let chunk=try input.read(upToCount:1024*1024) ?? Data();if chunk.isEmpty{break};if header.count<5{header.append(chunk.prefix(5-header.count))};try output.write(contentsOf:chunk)}
      try output.synchronize()
      guard String(data:header,encoding:.ascii)?.hasPrefix("%PDF-")==true else{throw error("The selected file is not a PDF.")}
      let inspected=try jsonObject(TranslateCoreBridge.inspect(incoming.path));if let message=inspected["error"] as? String{throw error(message)}
      let pages=(inspected["pageCount"] as? NSNumber)?.intValue ?? 0;guard pages>0,pages<=10_000 else{throw error("The PDF has an unsupported page count.")}
      try FileManager.default.moveItem(at:incoming,to:target)
      let sourceDigest=try sha256(at:target)
      return try store.saveDocument(id:id,name:safeName(source.lastPathComponent),path:target.path,pages:pages,sourceSHA256:sourceDigest)
    }catch{try? FileManager.default.removeItem(at:incoming);throw error}
  }
  private func safeName(_ value:String)->String{let trimmed=value.trimmingCharacters(in:.whitespacesAndNewlines);let sanitized=trimmed.replacingOccurrences(of:"[/\\\\\\0]",with:"_",options:.regularExpression);return sanitized.isEmpty ? "document.pdf" : String(sanitized.prefix(120))}
  private func topPresenter()->UIViewController?{guard let scene=UIApplication.shared.connectedScenes.compactMap({$0 as? UIWindowScene}).first(where:{$0.activationState == .foregroundActive}),let root=scene.windows.first(where:{$0.isKeyWindow})?.rootViewController else{return nil};var top=root;while let next=top.presentedViewController{top=next};return top}
  private func finishImportError(_ error:Error){DispatchQueue.main.async{self.pendingImportReject?("PDF_IMPORT_FAILED",error.localizedDescription,error);self.pendingImport=nil;self.pendingImportReject=nil}}
  private func buildExport(_ jobId:String,allowRunning:Bool=false)throws->(id:String,url:URL,name:String){
    guard let job=try store.job(jobId) else{throw error("Translation job was not found.")}
    let status=job["status"] as? String ?? ""
    let total=(job["totalPages"] as? NSNumber)?.intValue ?? 0
    guard total>0 else{throw error("The translation contains no exportable pages.")}
    if !allowRunning && !["completed","partial"].contains(status){throw error("Wait for the translation to finish before exporting.")}
    guard let source=try store.jobSource(jobId) else{throw error("The source document is unavailable.")}
    let model=try ModelCatalog.get(job["modelId"] as? String ?? "")
    try store.validateJobForExport(jobId,model:model)
    if let cached=try verifiedExport(jobId,expectedPages:total){return(jobId,cached.url,cached.name)}
    guard allowRunning else{
      if ["completed","partial"].contains(status) {
        try store.deleteExport(jobId)
        try store.updateJob(jobId,status:"paused",stage:"queued",completed:max(0,total-1),error:"The saved translated PDF failed verification. Resume to rebuild it from saved checkpoints.")
      }
      throw error("The verified translated PDF is unavailable. Resume this job to rebuild it from saved pages.")
    }
    let sourceURL=URL(fileURLWithPath:source)
    let sourceBytes=UInt64(max(0,(try? sourceURL.resourceValues(forKeys:[.fileSizeKey]).fileSize) ?? 0))
    let availableDisk=try AppPaths.exports.resourceValues(forKeys:[.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
    let outputEstimate=sourceBytes>UInt64.max/3 ? UInt64.max : max(sourceBytes*3,16*1024*1024)
    guard availableDisk>=0,outputEstimate<=UInt64.max-(64*1024*1024),UInt64(availableDisk)>=outputEstimate+(64*1024*1024) else{throw error("Free storage is below the estimated space needed to assemble and verify the translated PDF.")}

    try store.updateJob(jobId,status:"running",stage:"exporting",completed:min((job["completedPages"] as? NSNumber)?.intValue ?? 0,max(0,total-1)),clearError:true)
    let folder=AppPaths.pages.appendingPathComponent("export-\(jobId)",isDirectory:true)
    let pending=AppPaths.exports.appendingPathComponent("\(jobId).pending.pdf")
    let dest=AppPaths.exports.appendingPathComponent("\(jobId).pdf")
    try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
    try? FileManager.default.removeItem(at:pending)
    var pagePaths=[String]()
    do {
      for index in 0..<total {
        try checkFinalizationControl(jobId)
        guard let page=try store.page(jobId,index:index),let pageBlocks=page["blocks"] as? [[String:Any]] else{
          throw error("Page \(index+1) has no saved checkpoint; the export remains incomplete.")
        }
        var blocks=pageBlocks
        let url=folder.appendingPathComponent("page-\(index).pdf")
        try? FileManager.default.removeItem(at:url)
        let raw=TranslateCoreBridge.exportPage(source,index:index,blocksJSON:try jsonString(blocks),destination:url.path,fontPath:fontPath(),cjkFontPath:cjkFontPath())
        let result=try jsonObject(raw)
        if let message=result["error"] as? String{try store.savePage(jobId:jobId,index:index,status:"failed",blocks:try jsonString(blocks),error:message);throw error(message)}
        guard FileManager.default.fileExists(atPath:url.path) else{throw error("The PDF core did not create page \(index+1).")}
        let issues=result["issues"] as? [[String:Any]] ?? []
        var pageIssueMessages=[String](),pageIsPartial=false
        if !issues.isEmpty {pageIsPartial=true}
        for issue in issues {
          let blockId=issue["blockId"] as? String ?? ""
          let reason=issue["reason"] as? String ?? "layout_fallback"
          let message=issue["message"] as? String ?? "This text block could not be placed safely."
          pageIssueMessages.append(message)
          if let blockIndex=blocks.firstIndex(where:{($0["id"] as? String)==blockId}),!blockId.isEmpty {
            var existing=blocks[blockIndex]["exportIssues"] as? [[String:Any]] ?? []
            if !existing.contains(where:{($0["reason"] as? String)==reason && ($0["message"] as? String)==message}) {
              existing.append(["reason":reason,"message":message]);blocks[blockIndex]["exportIssues"]=existing
            }
            if (blocks[blockIndex]["status"] as? String) != "failed" {
              blocks[blockIndex]["status"]="partial"
              let priorError=blocks[blockIndex]["error"] as? String ?? ""
              if !priorError.contains(message) {blocks[blockIndex]["error"]=priorError.isEmpty ? message : priorError+"\n"+message}
            }
            pageIsPartial=true
          }
        }
        let corePartial=(result["partial"] as? Bool)==true || ((result["partialBlocks"] as? NSNumber)?.intValue ?? 0)>0
        if corePartial && issues.isEmpty {pageIssueMessages.append("One or more text blocks used a layout fallback; inspect this page.");pageIsPartial=true}
        let wasPartial=(page["status"] as? String)=="partial" || blocks.contains{["failed","partial"].contains($0["status"] as? String ?? "")}
        pageIsPartial = pageIsPartial || wasPartial
        var pageMessages=pageIssueMessages
        if let previousError=page["error"] as? String,!previousError.isEmpty {pageMessages.insert(previousError,at:0)}
        let pageError=pageMessages.isEmpty ? nil : Array(NSOrderedSet(array:pageMessages)).compactMap{$0 as? String}.joined(separator:" ")
        try store.savePage(jobId:jobId,index:index,status:pageIsPartial ? "partial":"completed",blocks:try jsonString(blocks),error:pageError)
        pagePaths.append(url.path)
      }
      try checkFinalizationControl(jobId)
      let merged=try jsonObject(TranslateCoreBridge.mergePDF(try jsonString(pagePaths),destination:pending.path))
      if let message=merged["error"] as? String{throw error(message)}
      try checkFinalizationControl(jobId)
      guard FileManager.default.fileExists(atPath:pending.path),let handle=try? FileHandle(forReadingFrom:pending),let header=try? handle.read(upToCount:5) else{
        throw error("The translated PDF could not be assembled.")
      }
      try? handle.close()
      guard String(data:header,encoding:.ascii)?.hasPrefix("%PDF-")==true else{throw error("The assembled file is not a valid PDF.")}
      let verified=try jsonObject(TranslateCoreBridge.inspect(pending.path))
      if let message=verified["error"] as? String{throw error(message)}
      guard (verified["pageCount"] as? NSNumber)?.intValue==total else{throw error("The translated PDF page count does not match the source.")}
      for index in 0..<total {
        let checked=try jsonObject(TranslateCoreBridge.readPage(pending.path,index:index))
        if let message=checked["error"] as? String{throw error("Page \(index+1) failed PDF verification: \(message)")}
        guard ((checked["width"] as? NSNumber)?.doubleValue ?? 0)>0,((checked["height"] as? NSNumber)?.doubleValue ?? 0)>0 else{throw error("Page \(index+1) has invalid geometry in the saved PDF.")}
      }
      try checkFinalizationControl(jobId)
      let digest=try sha256(at:pending)
      try? FileManager.default.removeItem(at:dest)
      try FileManager.default.moveItem(at:pending,to:dest)
      let sourceLanguage=try TranslationLanguage.pair(source:job["sourceLanguage"] as? String ?? "",target:job["targetLanguage"] as? String ?? "").0
      let targetLanguage=try TranslationLanguage.pair(source:job["sourceLanguage"] as? String ?? "",target:job["targetLanguage"] as? String ?? "").1
      let name=(job["documentName"] as? String ?? "translated.pdf").replacingOccurrences(of:"\\.pdf$",with:"",options:.regularExpression)+"-\(sourceLanguage.rawValue)-to-\(targetLanguage.rawValue).pdf"
      try store.saveExport(jobId,path:dest.path,name:name,sha256:digest)
      var protected=URLResourceValues();protected.isExcludedFromBackup=true;var copy=dest;try? copy.setResourceValues(protected)
      return(jobId,dest,name)
    } catch {
      try? FileManager.default.removeItem(at:pending)
      throw error
    }
  }

  private func verifiedExport(_ jobId:String,expectedPages:Int)throws->(url:URL,name:String)? {
    guard let record=try store.export(jobId),let path=record["path"] as? String,let name=record["name"] as? String,
      let savedHash=record["sha256"] as? String,!savedHash.isEmpty else{return nil}
    let url=URL(fileURLWithPath:path).standardizedFileURL
    guard url.deletingLastPathComponent()==AppPaths.exports.standardizedFileURL,
      FileManager.default.fileExists(atPath:url.path),try sha256(at:url)==savedHash else{return nil}
    let inspected=try jsonObject(TranslateCoreBridge.inspect(url.path))
    guard inspected["error"]==nil,(inspected["pageCount"] as? NSNumber)?.intValue==expectedPages else{return nil}
    return(url,name)
  }

  private func checkFinalizationControl(_ jobId:String)throws {
    stateLock.lock();let paused=pauseRequested,canceled=cancelRequested,active=activeJobID;stateLock.unlock()
    if canceled {throw CancelSignal()}
    if paused {throw PauseSignal()}
    if active != jobId {throw PauseSignal()}
  }
  private func presentExportFile(id:String,url:URL,name:String,resolve:@escaping RCTPromiseResolveBlock,reject:@escaping RCTPromiseRejectBlock){
    guard let presenter=topPresenter() else{reject("EXPORT_UNAVAILABLE","Open the app to choose where to save the translated PDF.",nil);return}
    pendingExport=resolve;pendingExportReject=reject;pendingExportInfo=(id,url,name)
    let picker=UIDocumentPickerViewController(forExporting:[url],asCopy:true);picker.delegate=self;presenter.present(picker,animated:true)
  }
  private func fontPath()->String {
    if let bundleURL=Bundle.main.url(forResource:"TranslatePDFFont",withExtension:"bundle"),let bundle=Bundle(url:bundleURL),let path=bundle.path(forResource:"NotoSans-Regular",ofType:"ttf"){return path}
    return Bundle.main.path(forResource:"NotoSans-Regular",ofType:"ttf") ?? ""
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
  private func beginBackgroundExecution(jobID:String){DispatchQueue.main.async{self.backgroundTask=UIApplication.shared.beginBackgroundTask(withName:"PDF translation \(jobID)"){[weak self] in guard let self else{return};self.requestPause(jobID:jobID,reason:"iOS ended the available background execution window; saved pages are ready to resume.")}}}
  private func endBackgroundExecution(){DispatchQueue.main.async{if self.backgroundTask != .invalid{UIApplication.shared.endBackgroundTask(self.backgroundTask);self.backgroundTask = .invalid}}}
  @objc private func applicationDidBecomeActive(){stateLock.lock();let id=activeJobID;let shouldResume=pauseRequested && !cancelRequested;systemLowMemory=false;stateLock.unlock();if shouldResume,let id { work.async{self.resumeAfterForeground(id)} }}
  private func requestPause(jobID:String,reason:String){stateLock.lock();pauseRequested=true;TranslateCoreBridge.cancel();stateLock.unlock();try? store.updateJob(jobID,status:"paused",error:reason);continuedTask?.setTaskCompleted(success:false);continuedTask=nil}
  private func resumeAfterForeground(_ id:String){
    stateLock.lock()
    guard pauseRequested,!cancelRequested else{stateLock.unlock();return}
    if activeJobID==id { pauseRequested=false;stateLock.unlock();return }
    guard activeJobID==nil else{stateLock.unlock();return}
    activeJobID=id;pauseRequested=false
    stateLock.unlock()
    translationWork.async{self.processJob(id)}
  }

  private func assessment(for model:ModelDefinition,status:String? = nil,downloadedBytes:Int64? = nil)throws->ModelResourceAssessment {
    let state=try store.modelState(model.id)
    let selectedStatus=status ?? (state?["status"] as? String ?? "not_downloaded")
    let bytes=downloadedBytes ?? (state?["downloaded_bytes"] as? Int64 ?? 0)
    let disk=try AppPaths.models.resourceValues(forKeys:[.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
    stateLock.lock();let lowMemory=systemLowMemory;stateLock.unlock()
    return MemoryPolicy.assess(model,status:selectedStatus,downloadedBytes:bytes,total:ProcessInfo.processInfo.physicalMemory,available:UInt64(os_proc_available_memory()),availableDisk:UInt64(max(0,disk)),systemLowMemory:lowMemory)
  }

  private func processJob(_ id:String){
    stateLock.lock();if activeJobID != id{activeJobID=id};let canceled=cancelRequested;stateLock.unlock();if canceled{return}
    do {
      guard let job=try store.job(id),let source=try store.jobSource(id) else{throw error("Translation job or document is unavailable.")}
      let model=try ModelCatalog.get(job["modelId"] as? String ?? "")
      try store.validateJobSnapshot(id,model:model)
      let count=(job["totalPages"] as? NSNumber)?.intValue ?? 0
      let languages=try TranslationLanguage.pair(source:job["sourceLanguage"] as? String ?? "",target:job["targetLanguage"] as? String ?? "")
      guard count>0 else{throw error("The translation contains no exportable pages.")}
      let needsInference=try requiresInference(id,totalPages:count)
      var modelLoaded=false
      if needsInference {
        guard model.translationEnabled else{throw error("The selected model is not enabled for translation.")}
        guard try downloader.isReady(model) else{throw error("The model file failed its pinned size or SHA-256 check.")}
        _ = try assessment(for:model,status:"ready",downloadedBytes:model.bytes)
      }
      try store.updateJob(id,status:"running",stage:needsInference ? "extracting":"exporting",clearError:true)
      if needsInference {
        let loaded=try jsonObject(TranslateCoreBridge.loadModel(model.fileURL.path,context:2048,threads:max(1,min(4,ProcessInfo.processInfo.activeProcessorCount)),profile:model.profile))
        if let message=loaded["error"] as? String {
          if isMemoryAllocationFailure(message) { throw PauseSignal("The operating system could not allocate memory for this model. The job is paused at its latest saved checkpoint; free memory and resume when ready.") }
          throw error(message)
        }
        modelLoaded=true
      }
      var completed=(job["completedPages"] as? NSNumber)?.intValue ?? 0
      if #available(iOS 26.0,*),let continued=continuedTask as? BGContinuedProcessingTask {continued.progress.totalUnitCount=Int64(count)}
      let glossary=try store.jobGlossaryText(id)
      for pageIndex in 0..<count {
        stateLock.lock();let pause=pauseRequested,stop=cancelRequested;stateLock.unlock()
        if stop{try store.updateJob(id,status:"canceled",error:"Canceled by the user.");break}
        if pause{throw PauseSignal()}
        let saved=try store.pageRows(id).first(where:{($0["index"] as? Int64)==Int64(pageIndex) || ($0["index"] as? Int)==pageIndex})
        if let saved,["completed","partial"].contains(saved["status"] as? String ?? ""){continue}
        try store.updateJob(id,status:"running",stage:"extracting",currentPage:pageIndex)
        let previousPage=try store.page(id,index:pageIndex)
        let native=try jsonObject(TranslateCoreBridge.readPage(source,index:pageIndex));if let message=native["error"] as? String{throw error(message)}
        let width=(native["width"] as? NSNumber)?.doubleValue ?? 0,height=(native["height"] as? NSNumber)?.doubleValue ?? 0
        guard width>0,height>0 else{throw error("Page geometry is unavailable.")}
        var blocks=previousPage?["blocks"] as? [[String:Any]] ?? (native["blocks"] as? [[String:Any]] ?? [])
        if previousPage == nil,let imageData=TranslateCoreBridge.renderPNG(source,index:pageIndex,width:1400),let image=UIImage(data:imageData),let cg=image.cgImage {
          blocks += try ocrBlocks(cgImage:cg,pageWidth:width,pageHeight:height,existing:blocks,sourceLanguage:languages.0)
        }
        let context=(native["context"] as? String ?? "").prefix(2_000)
        var partial=blocks.contains { ($0["status"] as? String) == "failed" || ($0["status"] as? String) == "partial" }
        try store.updateJob(id,stage:"translating",currentPage:pageIndex)
        for index in blocks.indices {
          if (blocks[index]["status"] as? String) == "translated",let text=blocks[index]["translatedText"] as? String,!text.isEmpty { continue }
          stateLock.lock();let pause=pauseRequested,stop=cancelRequested;stateLock.unlock()
          if stop{try store.updateJob(id,status:"canceled",error:"Canceled by the user.");break}
          if pause{throw PauseSignal()}
          let text=blocks[index]["sourceText"] as? String ?? ""
          guard !text.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty else{continue}
          do {
            let translated=try jsonObject(TranslateCoreBridge.translate(text,glossary:glossary,context:String(context),sourceLanguage:languages.0.rawValue,targetLanguage:languages.1.rawValue))
            if let message=translated["error"] as? String {
              if isMemoryAllocationFailure(message) { throw PauseSignal("The operating system could not allocate memory while translating. The job is paused at its latest saved checkpoint; free memory and resume when ready.") }
              blocks[index]["translatedText"]=NSNull();blocks[index]["status"]="failed";blocks[index]["error"]=message;partial=true
            }
            else if let value=translated["text"] as? String,!value.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty{blocks[index]["translatedText"]=value;blocks[index]["status"]="translated"}
            else{blocks[index]["translatedText"]=NSNull();blocks[index]["status"]="failed";blocks[index]["error"]="Translation was not produced.";partial=true}
          }catch let signal as PauseSignal { throw signal }
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
      stateLock.lock();let wasCanceled=cancelRequested,wasPaused=pauseRequested;stateLock.unlock()
      if modelLoaded {TranslateCoreBridge.releaseModel()}
      if wasCanceled{try store.updateJob(id,status:"canceled",stage:"complete",clearCurrentPage:true,error:"Canceled by the user.")}
      else if wasPaused{try store.updateJob(id,status:"paused",stage:"queued",clearCurrentPage:true,error:"Paused after the latest saved page checkpoint.")}
      else {
        // Translation output must be released before PDF assembly: a merged PDF and a loaded
        // model can otherwise exceed the conservative memory estimate on supported phones.
        try store.updateJob(id,status:"running",stage:"exporting",completed:min(completed,max(0,count-1)),clearCurrentPage:true,clearError:true)
        _ = try buildExport(id,allowRunning:true)
        let rows=try store.pageRows(id)
        guard rows.count==count,rows.allSatisfy({["completed","partial"].contains($0["status"] as? String ?? "")}) else{
          throw error("The verified PDF does not have a saved checkpoint for every source page.")
        }
        let hasPartial=rows.contains{($0["status"] as? String)=="partial"}
        try store.updateJob(id,status:hasPartial ? "partial":"completed",stage:"complete",completed:count,clearCurrentPage:true,clearError:true)
        if #available(iOS 26.0,*),let continued=continuedTask as? BGContinuedProcessingTask {continued.progress.completedUnitCount=Int64(count)}
      }
      continuedTask?.setTaskCompleted(success:!wasCanceled && !wasPaused);continuedTask=nil
    }catch let signal as PauseSignal {
      let total=(try? store.job(id)?["totalPages"] as? NSNumber)?.intValue ?? 0
      let done=(try? store.job(id)?["completedPages"] as? NSNumber)?.intValue ?? 0
      try? store.updateJob(id,status:"paused",stage:"queued",completed:min(done,max(0,total-1)),clearCurrentPage:true,error:signal.reason)
      TranslateCoreBridge.releaseModel();continuedTask?.setTaskCompleted(success:false);continuedTask=nil
    }catch is CancelSignal {
      let total=(try? store.job(id)?["totalPages"] as? NSNumber)?.intValue ?? 0
      let done=(try? store.job(id)?["completedPages"] as? NSNumber)?.intValue ?? 0
      try? store.updateJob(id,status:"canceled",stage:"queued",completed:min(done,max(0,total-1)),clearCurrentPage:true,error:"Canceled by the user. The translated PDF was not finalized.")
      TranslateCoreBridge.releaseModel();continuedTask?.setTaskCompleted(success:false);continuedTask=nil
    }catch{
      let total=(try? store.job(id)?["totalPages"] as? NSNumber)?.intValue ?? 0
      let done=(try? store.job(id)?["completedPages"] as? NSNumber)?.intValue ?? 0
      try? store.updateJob(id,status:"failed",stage:"queued",completed:min(done,max(0,total-1)),clearCurrentPage:true,error:error.localizedDescription)
      TranslateCoreBridge.cancel();TranslateCoreBridge.releaseModel();continuedTask?.setTaskCompleted(success:false);continuedTask=nil
    }
    stateLock.lock();if activeJobID==id{activeJobID=nil;pauseRequested=false;cancelRequested=false};stateLock.unlock();endBackgroundExecution()
  }

  private func ocrBlocks(cgImage:CGImage,pageWidth:Double,pageHeight:Double,existing:[[String:Any]],sourceLanguage:TranslationLanguage)throws->[ [String:Any] ]{
    let request=VNRecognizeTextRequest();request.recognitionLevel = .accurate;request.usesLanguageCorrection=false
    let supported=try request.supportedRecognitionLanguages()
    guard supported.contains(sourceLanguage.visionLocale) else { throw error("iOS Vision does not support OCR for the selected source language (\(sourceLanguage.visionLocale)) on this OS version.") }
    request.recognitionLanguages=[sourceLanguage.visionLocale]
    try VNImageRequestHandler(cgImage:cgImage).perform([request])
    guard let observations=request.results else{return []}
    let scaleX=pageWidth/Double(cgImage.width),scaleY=pageHeight/Double(cgImage.height);var result=[[String:Any]]()
    for (index,item) in observations.enumerated(){guard let candidate=item.topCandidates(1).first else{continue};let text=candidate.string.trimmingCharacters(in:.whitespacesAndNewlines);guard !text.isEmpty else{continue};let box=item.boundingBox;let left=box.minX*pageWidth,top=(1-box.maxY)*pageHeight,right=box.maxX*pageWidth,bottom=(1-box.minY)*pageHeight
      if overlaps(existing+result,left:left,top:top,right:right,bottom:bottom){continue}
      result.append(["id":"ocr-\(index)","text":text,"sourceText":text,"translatedText":NSNull(),"left":left,"top":top,"right":right,"bottom":bottom,"fontSize":max(6,(bottom-top)*0.8),"source":"ocr","objectIndex":NSNull(),"objectIndices":[],"status":"pending"])
    }
    _=scaleX;_=scaleY;return result
  }
  private func overlaps(_ blocks:[[String:Any]],left:Double,top:Double,right:Double,bottom:Double)->Bool{for b in blocks{let l=(b["left"] as? NSNumber)?.doubleValue ?? 0,t=(b["top"] as? NSNumber)?.doubleValue ?? 0,r=(b["right"] as? NSNumber)?.doubleValue ?? 0,bt=(b["bottom"] as? NSNumber)?.doubleValue ?? 0;let area=max((r-l)*(bt-t),(right-left)*(bottom-top),1);let intersection=max(0,min(r,right)-max(l,left))*max(0,min(bt,bottom)-max(t,top));if intersection/area>0.45{return true}};return false}

  @available(iOS 26.0,*) private func scheduleContinuedProcessing(jobID:String,documentName:String){
    let bundle=Bundle.main.bundleIdentifier ?? "dev.kigya.translatepdf",identifier="\(bundle).continuedProcessing.\(jobID)"
    if !registeredTaskIds.contains(identifier){
      registeredTaskIds.insert(identifier)
      _=BGTaskScheduler.shared.register(forTaskWithIdentifier:identifier,using:nil){[weak self] task in
        guard let self,let continued=task as? BGContinuedProcessingTask else{task.setTaskCompleted(success:false);return}
        continued.expirationHandler={[weak self] in self?.requestPause(jobID:jobID,reason:"iOS ended continued processing; completed pages are saved.")}
        self.continuedTask=continued
        self.translationWork.async{if self.activeJobID != jobID{self.processJob(jobID)}}
      }
    }
    let title="Translating PDF",subtitle=String(documentName.prefix(60))
    let request=BGContinuedProcessingTaskRequest(identifier:identifier,title:title,subtitle:subtitle)
    request.strategy = .queue
    do{try BGTaskScheduler.shared.submit(request)}catch{NSLog("Could not schedule continued processing: %@",error.localizedDescription)}
  }
  @available(iOS 26.0,*) private func finishContinuedTask(success:Bool){continuedTask?.setTaskCompleted(success:success);continuedTask=nil}

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
      let payload=(try? jsonString(["id":info.id,"uri":destination.absoluteString,"displayName":info.name])) ?? "null"
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

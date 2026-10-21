import Foundation
import SQLite3

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

final class TranslatorStore {
  private let lock = NSRecursiveLock()
  private var db: OpaquePointer?

  init(directory: URL) throws {
    let url = directory.appendingPathComponent("translator-private.db")
    guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
      throw NSError(domain: "PdfTranslator", code: 1, userInfo: [NSLocalizedDescriptionKey: "The private database could not be opened."])
    }
    sqlite3_busy_timeout(db, 5_000)
    try execute("PRAGMA foreign_keys=ON")
    try execute("PRAGMA journal_mode=WAL")
    try execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    try execute("CREATE TABLE IF NOT EXISTS documents (id TEXT PRIMARY KEY,name TEXT NOT NULL,path TEXT NOT NULL,pages INTEGER NOT NULL,source_sha256 TEXT NOT NULL DEFAULT '',imported_at INTEGER NOT NULL)")
    try execute("CREATE TABLE IF NOT EXISTS models (id TEXT PRIMARY KEY,status TEXT NOT NULL,downloaded_bytes INTEGER NOT NULL DEFAULT 0,error TEXT)")
    try execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY,document_id TEXT NOT NULL,model_id TEXT NOT NULL,model_revision TEXT NOT NULL DEFAULT '',source_sha256 TEXT NOT NULL DEFAULT '',glossary_snapshot TEXT NOT NULL DEFAULT '',pipeline_version INTEGER NOT NULL DEFAULT 1,source_lang TEXT NOT NULL,target_lang TEXT NOT NULL,status TEXT NOT NULL,stage TEXT NOT NULL,completed_pages INTEGER NOT NULL DEFAULT 0,total_pages INTEGER NOT NULL,current_page INTEGER,glossary_id TEXT,error TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,label_color TEXT DEFAULT NULL,FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE)")
    try execute("CREATE INDEX IF NOT EXISTS jobs_document_id ON jobs(document_id)")
    try execute("CREATE TABLE IF NOT EXISTS pages (job_id TEXT NOT NULL,page_index INTEGER NOT NULL,status TEXT NOT NULL,blocks_json TEXT NOT NULL,error TEXT,updated_at INTEGER NOT NULL,PRIMARY KEY(job_id,page_index),FOREIGN KEY(job_id) REFERENCES jobs(id) ON DELETE CASCADE)")
    try execute("CREATE TABLE IF NOT EXISTS glossary (id TEXT PRIMARY KEY,source TEXT NOT NULL,target TEXT NOT NULL,created_at INTEGER NOT NULL)")
    try execute("CREATE TABLE IF NOT EXISTS exports (id TEXT PRIMARY KEY,path TEXT NOT NULL,name TEXT NOT NULL,sha256 TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)")
    try ensureColumn("exports","sha256","TEXT NOT NULL DEFAULT ''")
    try ensureColumn("documents","source_sha256","TEXT NOT NULL DEFAULT ''")
    try ensureColumn("documents","format","TEXT NOT NULL DEFAULT 'pdf'")
    try ensureColumn("documents","segment_count","INTEGER NOT NULL DEFAULT 0")
    try ensureColumn("documents","warnings_json","TEXT NOT NULL DEFAULT '[]'")
    try ensureColumn("jobs","model_revision","TEXT NOT NULL DEFAULT ''")
    try ensureColumn("jobs","source_sha256","TEXT NOT NULL DEFAULT ''")
    try ensureColumn("jobs","glossary_snapshot","TEXT NOT NULL DEFAULT ''")
    try ensureColumn("jobs","pipeline_version","INTEGER NOT NULL DEFAULT 1")
    try ensureColumn("jobs","model_profile","TEXT NOT NULL DEFAULT ''")
    try ensureColumn("jobs","engine_kind","TEXT NOT NULL DEFAULT 'local'")
    try ensureColumn("jobs","engine_id","TEXT NOT NULL DEFAULT ''")
    try ensureColumn("jobs","progress_unit","TEXT NOT NULL DEFAULT 'pages'")
    try ensureColumn("jobs","warnings_json","TEXT NOT NULL DEFAULT '[]'")
    try ensureColumn("glossary","source_lang","TEXT NOT NULL DEFAULT 'en'")
    try ensureColumn("glossary","target_lang","TEXT NOT NULL DEFAULT 'ru'")
    try migrateSchema6()
    try migrateSnapshots()
  }

  deinit { if let db { sqlite3_close(db) } }

  func execute(_ sql: String, _ values: [Any] = []) throws {
    lock.lock(); defer { lock.unlock() }
    var statement: OpaquePointer?
    guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else { throw failure() }
    defer { sqlite3_finalize(statement) }
    try bind(values, to: statement)
    var result = sqlite3_step(statement)
    while result == SQLITE_ROW { result = sqlite3_step(statement) }
    guard result == SQLITE_DONE else { throw failure() }
  }

  func query(_ sql: String, _ values: [Any] = []) throws -> [[String: Any]] {
    lock.lock(); defer { lock.unlock() }
    var statement: OpaquePointer?
    guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else { throw failure() }
    defer { sqlite3_finalize(statement) }
    try bind(values, to: statement)
    var rows = [[String: Any]]()
    while true {
      let result = sqlite3_step(statement)
      if result == SQLITE_DONE { break }
      guard result == SQLITE_ROW else { throw failure() }
      var row = [String: Any]()
      for column in 0..<sqlite3_column_count(statement) {
        guard let name = sqlite3_column_name(statement, column) else { continue }
        switch sqlite3_column_type(statement, column) {
        case SQLITE_INTEGER: row[String(cString: name)] = sqlite3_column_int64(statement, column)
        case SQLITE_FLOAT: row[String(cString: name)] = sqlite3_column_double(statement, column)
        case SQLITE_TEXT:
          if let text = sqlite3_column_text(statement, column) { row[String(cString: name)] = String(cString: text) }
        case SQLITE_BLOB:
          let length = Int(sqlite3_column_bytes(statement, column))
          if length == 0 { row[String(cString: name)] = Data() }
          else if let bytes = sqlite3_column_blob(statement, column) { row[String(cString: name)] = Data(bytes: bytes, count: length) }
        default: row[String(cString: name)] = NSNull()
        }
      }
      rows.append(row)
    }
    return rows
  }

  func setting(_ key: String, fallback: String) throws -> String {
    try query("SELECT value FROM settings WHERE key=?", [key]).first?["value"] as? String ?? fallback
  }

  func setSetting(_ key: String, _ value: String) throws {
    try execute("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)", [key, value])
  }

  func transaction<T>(_ operation: () throws -> T) throws -> T {
    lock.lock(); defer { lock.unlock() }
    try execute("BEGIN IMMEDIATE")
    do {
      let value=try operation()
      try execute("COMMIT")
      return value
    } catch {
      try? execute("ROLLBACK")
      throw error
    }
  }

  func appPreferences() throws -> [String:Any] {
    let rawRating=try setting("app_rating",fallback:"0")
    let rating=Int(rawRating).flatMap{(0...5).contains($0) ? $0:nil} ?? 0
    let rawSort=try setting("library_sort",fallback:"recent")
    let sort=["recent","color"].contains(rawSort) ? rawSort:"recent"
    return ["appRating":rating,"librarySort":sort]
  }

  func setAppPreferences(appRating:Int?,librarySort:String?) throws -> [String:Any] {
    if let appRating, !(0...5).contains(appRating) { throw NSError(domain:"PdfTranslator",code:42,userInfo:[NSLocalizedDescriptionKey:"The app rating must be an integer from 0 to 5."]) }
    if let librarySort, !["recent","color"].contains(librarySort) { throw NSError(domain:"PdfTranslator",code:43,userInfo:[NSLocalizedDescriptionKey:"The library sort mode is not supported."]) }
    if let appRating { try setSetting("app_rating",String(appRating)) }
    if let librarySort { try setSetting("library_sort",librarySort) }
    return try appPreferences()
  }

  func languagePreferences() throws -> (sourceLanguage: String, targetLanguage: String) {
    lock.lock(); defer { lock.unlock() }
    let source = try setting("source_language", fallback: "")
    let target = try setting("target_language", fallback: "")
    if (try? TranslationLanguage.pair(source: source, target: target)) != nil {
      return (source, target)
    }
    try setLanguagePreferences(sourceLanguage: "en", targetLanguage: "ru")
    return ("en", "ru")
  }

  func setLanguagePreferences(sourceLanguage: String, targetLanguage: String) throws {
    _ = try TranslationLanguage.pair(source: sourceLanguage, target: targetLanguage)
    try execute("INSERT OR REPLACE INTO settings(key,value) VALUES('source_language',?),( 'target_language',?)", [sourceLanguage, targetLanguage])
  }

  func selectedModelId() throws -> String {
    try resolveSelectedModelId(recommended: ModelCatalog.recommendedId, installedModelIds: [])
  }

  func selectedEngine() throws -> [String:String] {
    let modelId=try selectedModelId()
    return try resolveSelectedEngine(modelId:modelId)
  }

  func resolveSelectedEngine(modelId:String)throws->[String:String] {
    let saved=try setting("selected_engine",fallback:"")
    if let data=saved.data(using:.utf8),let engine=try? JSONSerialization.jsonObject(with:data) as? [String:String] {
      let kind=engine["kind"] ?? "",id=engine["id"] ?? ""
      if kind=="online",id=="mymemory" { return ["kind":kind,"id":id] }
      if kind=="local",let definition=try? ModelCatalog.get(id),definition.translationEnabled { return ["kind":kind,"id":definition.id] }
    }
    let fallback=["kind":"local","id":modelId]
    try setSetting("selected_engine",jsonString(fallback))
    return fallback
  }

  @discardableResult func setSelectedEngine(_ engine:[String:Any])throws->[String:String] {
    guard let kind=engine["kind"] as? String,let id=engine["id"] as? String else { throw NSError(domain:"PdfTranslator",code:34,userInfo:[NSLocalizedDescriptionKey:"The selected translation engine is invalid."]) }
    let validated:[String:String]
    if kind=="online",id=="mymemory" { validated=["kind":kind,"id":id] }
    else if kind=="local",let definition=try? ModelCatalog.get(id),definition.translationEnabled { validated=["kind":kind,"id":definition.id];_ = try setSelectedModelId(definition.id) }
    else { throw NSError(domain:"PdfTranslator",code:34,userInfo:[NSLocalizedDescriptionKey:"The selected translation engine is not supported."]) }
    try setSetting("selected_engine",jsonString(validated))
    return validated
  }

  /// Atomically initializes/repairs the preference, while keeping every valid saved choice
  /// stable when device-resource recommendations change between bootstrap calls.
  func resolveSelectedModelId(recommended: String, installedModelIds: [String]) throws -> String {
    lock.lock(); defer { lock.unlock() }
    let saved = try setting("selected_model_id", fallback: "")
    if let definition = try? ModelCatalog.get(saved), definition.translationEnabled {
      return definition.id
    }

    let installedIds = Set(installedModelIds)
    let preferredInstalled = ModelCatalog.models.reversed().first {
      installedIds.contains($0.id) && $0.translationEnabled
    }
    let preferred = preferredInstalled ?? ((try? ModelCatalog.get(recommended)).flatMap { $0.translationEnabled ? $0 : nil })
      ?? (try! ModelCatalog.get(ModelCatalog.recommendedId))
    try setSetting("selected_model_id", preferred.id)
    return preferred.id
  }

  @discardableResult
  func setSelectedModelId(_ id: String) throws -> String {
    let definition = try ModelCatalog.get(id)
    try setSetting("selected_model_id", definition.id)
    let saved=try setting("selected_engine",fallback:"")
    let existing=(saved.data(using:.utf8).flatMap{try? JSONSerialization.jsonObject(with:$0) as? [String:Any]})
    if (existing?["kind"] as? String) != "online" { try setSetting("selected_engine",jsonString(["kind":"local","id":definition.id])) }
    return definition.id
  }

  func saveDocument(id: String, name: String, path: String, pages: Int, sourceSHA256: String, format: String = "pdf", warnings: [[String:Any]] = []) throws -> [String: Any] {
    guard ["pdf", "docx"].contains(format) else { throw NSError(domain:"PdfTranslator",code:31,userInfo:[NSLocalizedDescriptionKey:"The document format is not supported."]) }
    let importedAt = Int64(Date().timeIntervalSince1970 * 1000)
    let pageCount=format == "pdf" ? pages:0,segmentCount=format == "docx" ? pages:0
    try execute("INSERT INTO documents(id,name,path,pages,source_sha256,imported_at,format,segment_count,warnings_json) VALUES(?,?,?,?,?,?,?,?,?)", [id,name,path,pageCount,sourceSHA256,importedAt,format,segmentCount,try jsonString(warnings)])
    return ["id":id,"name":name,"pageCount":pageCount,"segmentCount":segmentCount,"format":format,"warnings":warnings,"importedAt":importedAt]
  }

  func documentPath(_ id: String) throws -> String? { try query("SELECT path FROM documents WHERE id=?", [id]).first?["path"] as? String }
  func documentSHA256(_ id: String) throws -> String? { try query("SELECT source_sha256 FROM documents WHERE id=?", [id]).first?["source_sha256"] as? String }

  func documents() throws -> [[String: Any]] {
    try query("SELECT id,name,pages,segment_count,imported_at,format,warnings_json FROM documents ORDER BY imported_at DESC").map { row in ["id":row["id"]!,"name":row["name"]!,"pageCount":row["pages"]!,"segmentCount":row["segment_count"]!,"format":row["format"] as? String ?? "pdf","warnings":decodeWarnings(row["warnings_json"] as? String),"importedAt":row["imported_at"]!] }
  }

  func documentFormat(_ id: String) throws -> String { try query("SELECT format FROM documents WHERE id=?", [id]).first?["format"] as? String ?? "pdf" }
  func documentSegmentCount(_ id:String)throws->Int{Int((try query("SELECT segment_count FROM documents WHERE id=?",[id]).first?["segment_count"] as? Int64) ?? 0)}
  func updatePreviewPageCount(_ id:String,_ count:Int)throws{guard count>0,count<=10_000 else{return};try execute("UPDATE documents SET pages=? WHERE id=? AND format='docx'",[count,id])}
  func documentWarnings(_ id:String)throws->[[String:Any]]{decodeWarnings(try query("SELECT warnings_json FROM documents WHERE id=?",[id]).first?["warnings_json"] as? String)}
  func setJobWarnings(_ id:String,_ warnings:[[String:Any]])throws{try execute("UPDATE jobs SET warnings_json=? WHERE id=?",[try jsonString(warnings),id])}

  func modelState(_ id: String) throws -> [String: Any]? { try query("SELECT status,downloaded_bytes,error FROM models WHERE id=?", [id]).first }
  func updateModel(_ id: String, status: String, bytes: Int64, error: String? = nil) throws {
    let errorValue: Any = error.map { $0 as Any } ?? NSNull()
    try execute("INSERT OR REPLACE INTO models(id,status,downloaded_bytes,error) VALUES(?,?,?,?)", [id,status,bytes,errorValue])
  }
  func deleteModel(_ id: String) throws { try execute("DELETE FROM models WHERE id=?", [id]) }

  func models(totalMemory: UInt64, availableMemory: UInt64, availableDisk: UInt64, systemLowMemory: Bool, modelDirectory: URL = AppPaths.models) throws -> [[String: Any]] {
    try ModelCatalog.models.map { definition in
      let state = try modelState(definition.id)
      var status = state?["status"] as? String ?? "not_downloaded"
      var recordedBytes = state?["downloaded_bytes"] as? Int64 ?? 0
      var error = state?["error"] as? String
      let installed = try? FileManager.default.attributesOfItem(atPath:modelDirectory.appendingPathComponent("\(definition.id).gguf").path)
      let installedBytes = (installed?[.size] as? NSNumber)?.int64Value ?? 0
      let partialBytes = ((try? FileManager.default.attributesOfItem(atPath:modelDirectory.appendingPathComponent("\(definition.id).part").path)[.size]) as? NSNumber)?.int64Value ?? 0
      let resumeBytes = ((try? FileManager.default.attributesOfItem(atPath:modelDirectory.appendingPathComponent("\(definition.id).resume").path)[.size]) as? NSNumber)?.int64Value ?? 0
      // Ready records a completed SHA-verified download. If its file later
      // disappears or changes size, repair only this snapshot without changing
      // the user's selected model or touching an active download/verification.
      // The complete SHA check still runs before loading a model for inference.
      if status == "ready" && (installed?[.type] as? FileAttributeType != .typeRegular || installedBytes != definition.bytes) {
        status = partialBytes > 0 || resumeBytes > 0 ? "paused" : "not_downloaded"
        // Resume metadata is not payload: a stale completed-byte count cannot
        // prove that URLSession still owns those bytes after the GGUF vanished.
        recordedBytes = partialBytes
        error = nil
        // Never write a repair from stale reads: a concurrent transfer may
        // already have advanced to downloading, verifying or a newer Ready.
      }
      let downloadedBytes = min(definition.bytes,max(recordedBytes,partialBytes))
      let assessment = MemoryPolicy.assess(definition, status: status, downloadedBytes: downloadedBytes, total: totalMemory, available: availableMemory, availableDisk: availableDisk, systemLowMemory: systemLowMemory)
      var snapshot = definition.snapshot(status: status, downloadedBytes: downloadedBytes, error: error, assessment: assessment)
      snapshot["storedBytes"] = installedBytes + partialBytes + resumeBytes
      snapshot["partialBytes"] = partialBytes + resumeBytes
      // URLSession owns its active temporary file until didFinishDownloadingTo.
      // This reports persisted transfer progress separately, never as disk use.
      snapshot["activeTransferBytes"] = status == "downloading" ? downloadedBytes : Int64(0)
      return snapshot
    }
  }

  func addJob(id: String, input: [String: Any], pageCount: Int) throws -> [String: Any] {
    let now = Int64(Date().timeIntervalSince1970 * 1000)
    guard let documentId=input["documentId"] as? String,let sourceSHA256=try documentSHA256(documentId),!sourceSHA256.isEmpty else {
      throw NSError(domain:"PdfTranslator",code:5,userInfo:[NSLocalizedDescriptionKey:"The source file or pinned metadata is unavailable."])
    }
    let engine=input["engine"] as? [String:Any] ?? ["kind":"local","id":input["modelId"] as? String ?? ""]
    let engineKind=engine["kind"] as? String ?? "local", engineId=engine["id"] as? String ?? ""
    guard engineKind == "local" || engineKind == "online" else { throw NSError(domain:"PdfTranslator",code:32,userInfo:[NSLocalizedDescriptionKey:"The selected translation engine is not supported."]) }
    if engineKind == "online" && (engineId != "mymemory" || input["onlineConsent"] as? Bool != true) {
      throw NSError(domain:"PdfTranslator",code:33,userInfo:[NSLocalizedDescriptionKey:"Online translation requires explicit consent for MyMemory before sending document text."])
    }
    let modelId = engineKind == "local" ? (input["modelId"] as? String ?? engineId) : "mymemory"
    let definition = engineKind == "local" ? try ModelCatalog.get(modelId) : nil
    guard let sourceLanguage = input["sourceLanguage"] as? String,
          let targetLanguage = input["targetLanguage"] as? String else {
      throw NSError(domain:"PdfTranslator",code:30,userInfo:[NSLocalizedDescriptionKey:"A source and target language are required."])
    }
    _ = try TranslationLanguage.pair(source:sourceLanguage,target:targetLanguage)
    let glossaryId=input["glossaryId"] as? String
    let glossarySnapshot=(engineKind == "local" && (input["useGlossary"] as? Bool ?? true))
      ? try glossaryText(glossaryId,sourceLanguage:sourceLanguage,targetLanguage:targetLanguage) : ""
    guard (definition?.supportsGlossary ?? false) || glossarySnapshot.isEmpty else { throw NSError(domain:"PdfTranslator",code:11,userInfo:[NSLocalizedDescriptionKey:"The selected model profile does not support glossary instructions. Clear the glossary or choose a glossary-compatible model before creating the job."]) }
    let revision=definition?.revision ?? "", profile=definition?.profile ?? ""
    try execute("INSERT INTO jobs(id,document_id,model_id,model_revision,source_sha256,glossary_snapshot,pipeline_version,model_profile,source_lang,target_lang,status,stage,completed_pages,total_pages,current_page,glossary_id,error,created_at,updated_at,engine_kind,engine_id,progress_unit) VALUES(?,?,?,?,?,?,4,?,?,?,'queued','queued',0,?,NULL,?,NULL,?,?,?,?,?)", [id,documentId,modelId,revision,sourceSHA256,glossarySnapshot,profile,sourceLanguage,targetLanguage,pageCount,engineKind == "local" ? (glossaryId ?? NSNull() as Any) : NSNull(),now,now,engineKind,engineId,try documentFormat(documentId) == "docx" ? "segments" : "pages"])
    try setJobWarnings(id,try documentWarnings(documentId))
    return try job(id) ?? [:]
  }

  func job(_ id: String) throws -> [String: Any]? {
    guard let r = try query("SELECT j.id,j.document_id,d.name,d.format,d.pages,d.segment_count,j.model_id,j.model_revision,j.model_profile,j.source_sha256,j.pipeline_version,j.source_lang,j.target_lang,j.status,j.stage,j.completed_pages,j.total_pages,j.current_page,j.error,j.created_at,j.updated_at,j.engine_kind,j.engine_id,j.progress_unit,j.label_color FROM jobs j JOIN documents d ON d.id=j.document_id WHERE j.id=?", [id]).first else { return nil }
    let engineId=(r["engine_id"] as? String).flatMap{$0.isEmpty ? nil:$0} ?? (r["model_id"] as? String ?? "")
    let warnings=decodeWarnings(try query("SELECT warnings_json FROM jobs WHERE id=?",[id]).first?["warnings_json"] as? String)
    let format=r["format"] as? String ?? "pdf"
    return ["id":r["id"]!,"documentId":r["document_id"]!,"documentName":r["name"]!,"format":format,"documentFormat":format,"pageCount":r["pages"]!,"segmentCount":r["segment_count"]!,"modelId":r["model_id"]!,"modelRevision":r["model_revision"]!,"modelProfile":r["model_profile"]!,"sourceSHA256":r["source_sha256"]!,"pipelineVersion":r["pipeline_version"]!,"sourceLanguage":r["source_lang"]!,"targetLanguage":r["target_lang"]!,"status":r["status"]!,"stage":r["stage"]!,"completedPages":r["completed_pages"]!,"totalPages":r["total_pages"]!,"totalSegments":format == "docx" ? r["total_pages"]! : 0,"progressUnit":format == "docx" ? "segment" : "page","engine":["kind":r["engine_kind"] as? String ?? "local","id":engineId],"warnings":warnings,"labelColor":r["label_color"] ?? NSNull(),"currentPage":r["current_page"] ?? NSNull(),"error":r["error"] ?? NSNull(),"createdAt":r["created_at"]!,"updatedAt":r["updated_at"]!]
  }

  func jobs() throws -> [[String: Any]] { try query("SELECT id FROM jobs ORDER BY created_at DESC").compactMap { try job($0["id"] as! String) } }

  func setJobColor(_ id:String,color:String)throws->[String:Any] {
    let allowed=Set(["red","orange","yellow","green","blue","purple"])
    guard color.isEmpty || allowed.contains(color) else { throw NSError(domain:"PdfTranslator",code:44,userInfo:[NSLocalizedDescriptionKey:"The selected document label color is invalid."]) }
    guard let row=try query("SELECT status FROM jobs WHERE id=?",[id]).first else { throw NSError(domain:"PdfTranslator",code:45,userInfo:[NSLocalizedDescriptionKey:"The translation job was not found."]) }
    guard ["completed","partial"].contains(row["status"] as? String ?? "") else { throw NSError(domain:"PdfTranslator",code:46,userInfo:[NSLocalizedDescriptionKey:"Only completed translations can be assigned a color label."]) }
    try execute("UPDATE jobs SET label_color=? WHERE id=?",[color.isEmpty ? NSNull() as Any:color,id])
    guard let result=try job(id) else { throw NSError(domain:"PdfTranslator",code:45,userInfo:[NSLocalizedDescriptionKey:"The translation job was not found."]) }
    return result
  }
  func jobSource(_ id: String) throws -> String? { try query("SELECT d.path FROM jobs j JOIN documents d ON d.id=j.document_id WHERE j.id=?", [id]).first?["path"] as? String }
  func jobGlossaryText(_ id: String) throws -> String { try query("SELECT glossary_snapshot FROM jobs WHERE id=?", [id]).first?["glossary_snapshot"] as? String ?? "" }

  func validateJobSnapshot(_ id:String,model:ModelDefinition)throws {
    try validateJobSnapshot(id,model:model,requiresCurrentPipeline:true)
  }

  func validateJobForExport(_ id:String,model:ModelDefinition)throws {
    try validateJobSnapshot(id,model:model,requiresCurrentPipeline:false)
  }

  func validateOnlineSnapshot(_ id:String)throws {
    guard let row=try query("SELECT source_sha256 FROM jobs WHERE id=?",[id]).first,
      let expected=row["source_sha256"] as? String,!expected.isEmpty,
      let source=try jobSource(id),try sha256(at:URL(fileURLWithPath:source)) == expected else {
      throw NSError(domain:"PdfTranslator",code:6,userInfo:[NSLocalizedDescriptionKey:"This job's source file no longer matches its saved checkpoint. Import the source again to start a new job."])
    }
  }

  private func validateJobSnapshot(_ id:String,model:ModelDefinition,requiresCurrentPipeline:Bool)throws {
    guard let row=try query("SELECT model_id,model_revision,model_profile,source_sha256,pipeline_version FROM jobs WHERE id=?",[id]).first,
      row["model_id"] as? String == model.id,
      row["model_revision"] as? String == model.revision,
      row["model_profile"] as? String == model.profile,
      (!requiresCurrentPipeline || row["pipeline_version"] as? Int64 == 4),
      let expected=row["source_sha256"] as? String,!expected.isEmpty,
      let source=try jobSource(id),try sha256(at:URL(fileURLWithPath:source)) == expected else {
      throw NSError(domain:"PdfTranslator",code:6,userInfo:[NSLocalizedDescriptionKey:"This job's source, model or processing version no longer matches its saved checkpoint. Start a new job to use the current inputs."])
    }
  }

  func updateJob(_ id: String, status: String? = nil, stage: String? = nil, completed: Int? = nil, currentPage: Int? = nil, clearCurrentPage: Bool = false, error: String? = nil, clearError: Bool = false) throws {
    var sets = ["updated_at=?"]; var values: [Any] = [Int64(Date().timeIntervalSince1970 * 1000)]
    if let status { sets.append("status=?"); values.append(status) }
    if let stage { sets.append("stage=?"); values.append(stage) }
    if let completed { sets.append("completed_pages=?"); values.append(completed) }
    if let currentPage { sets.append("current_page=?"); values.append(currentPage) }
    if clearCurrentPage { sets.append("current_page=NULL") }
    if let error { sets.append("error=?"); values.append(error) }
    if clearError { sets.append("error=NULL") }
    values.append(id)
    try execute("UPDATE jobs SET \(sets.joined(separator:",")) WHERE id=?", values)
  }

  func savePage(jobId: String, index: Int, status: String, blocks: String, error: String? = nil) throws {
    try execute("INSERT OR REPLACE INTO pages(job_id,page_index,status,blocks_json,error,updated_at) VALUES(?,?,?,?,?,?)", [jobId,index,status,blocks,error ?? NSNull() as Any,Int64(Date().timeIntervalSince1970 * 1000)])
  }

  func pageRows(_ jobId: String) throws -> [[String: Any]] { try query("SELECT page_index,status FROM pages WHERE job_id=? ORDER BY page_index", [jobId]).map { ["index":$0["page_index"]!,"status":$0["status"]!] } }
  func page(_ jobId: String, index: Int) throws -> [String: Any]? {
    guard let row = try query("SELECT status,blocks_json,error FROM pages WHERE job_id=? AND page_index=?", [jobId,index]).first,
          let blocks = row["blocks_json"] as? String,
          let path = try jobSource(jobId) else { return nil }
    let format=(try job(jobId)?["documentFormat"] as? String) ?? "pdf"
    let metadata = try jsonObject(format == "docx" ? TranslateCoreBridge.readDocxSegment(path,index:index) : TranslateCoreBridge.readPage(path, index: index))
    if metadata["error"] != nil { throw failure(String(describing: metadata["error"]!)) }
    var page:[String:Any] = ["index":index,"status":row["status"]!,"width":metadata["width"] ?? 0,"height":metadata["height"] ?? 0,"blocks":try JSONSerialization.jsonObject(with: Data(blocks.utf8))]
    if let message=row["error"] as? String { page["error"] = message }
    return page
  }

  func deleteJob(_ id: String) throws { try execute("DELETE FROM jobs WHERE id=?", [id]) }
  func deleteDocument(_ id: String) throws { try execute("DELETE FROM documents WHERE id=?", [id]) }
  func glossary() throws -> [[String: Any]] { try query("SELECT id,source,target,source_lang,target_lang,created_at FROM glossary ORDER BY source_lang,target_lang,source COLLATE NOCASE").map { ["id":$0["id"]!,"source":$0["source"]!,"target":$0["target"]!,"sourceLanguage":$0["source_lang"]!,"targetLanguage":$0["target_lang"]!,"createdAt":$0["created_at"]!] } }
  func saveGlossary(_ id: String, source: String, target: String, sourceLanguage: String, targetLanguage: String) throws -> [String: Any] {
    _ = try TranslationLanguage.pair(source:sourceLanguage,target:targetLanguage)
    let now = Int64(Date().timeIntervalSince1970 * 1000)
    let cleanSource=source.trimmingCharacters(in:.whitespacesAndNewlines),cleanTarget=target.trimmingCharacters(in:.whitespacesAndNewlines)
    try execute("INSERT OR REPLACE INTO glossary(id,source,target,source_lang,target_lang,created_at) VALUES(?,?,?,?,?,?)", [id,cleanSource,cleanTarget,sourceLanguage,targetLanguage,now])
    return ["id":id,"source":cleanSource,"target":cleanTarget,"sourceLanguage":sourceLanguage,"targetLanguage":targetLanguage,"createdAt":now]
  }
  func glossaryText(_ id: String?, sourceLanguage: String = "en", targetLanguage: String = "ru") throws -> String {
    _ = try TranslationLanguage.pair(source:sourceLanguage,target:targetLanguage)
    let rows = try id == nil
      ? query("SELECT source,target FROM glossary WHERE source_lang=? AND target_lang=? ORDER BY source COLLATE NOCASE", [sourceLanguage,targetLanguage])
      : query("SELECT source,target FROM glossary WHERE id=? AND source_lang=? AND target_lang=?", [id!,sourceLanguage,targetLanguage])
    return rows.compactMap { guard let source = $0["source"] as? String, let target = $0["target"] as? String else { return nil }; return "\(source) translates to \(target)" }.joined(separator:"\n")
  }
  func deleteGlossary(_ id: String) throws { try execute("DELETE FROM glossary WHERE id=?", [id]) }
  func saveExport(_ id: String, path: String, name: String, sha256: String) throws { try execute("INSERT OR REPLACE INTO exports(id,path,name,sha256,created_at) VALUES(?,?,?,?,?)", [id,path,name,sha256,Int64(Date().timeIntervalSince1970 * 1000)]) }
  func export(_ id: String) throws -> [String: Any]? { try query("SELECT path,name,sha256 FROM exports WHERE id=?", [id]).first }
  func deleteExport(_ id: String) throws { try execute("DELETE FROM exports WHERE id=?", [id]) }

  private func bind(_ values: [Any], to statement: OpaquePointer) throws {
    for (offset, value) in values.enumerated() {
      let index = Int32(offset + 1)
      let result: Int32
      if value is NSNull { result = sqlite3_bind_null(statement,index) }
      else if let string = value as? String { result = sqlite3_bind_text(statement,index,string,-1,sqliteTransient) }
      else if let number = value as? NSNumber { result = sqlite3_bind_int64(statement,index,number.int64Value) }
      else if let int = value as? Int { result = sqlite3_bind_int64(statement,index,sqlite3_int64(int)) }
      else if let int64 = value as? Int64 { result = sqlite3_bind_int64(statement,index,int64) }
      else { throw NSError(domain:"PdfTranslator",code:2,userInfo:[NSLocalizedDescriptionKey:"Unsupported private database value."]) }
      guard result == SQLITE_OK else { throw failure() }
    }
  }

  private func failure(_ message: String? = nil) -> NSError {
    let detail = message ?? db.map { String(cString: sqlite3_errmsg($0)) } ?? "Unknown SQLite error."
    return NSError(domain:"PdfTranslator",code:3,userInfo:[NSLocalizedDescriptionKey:detail])
  }

  private func ensureColumn(_ table:String,_ column:String,_ declaration:String)throws {
    let columns=try query("PRAGMA table_info(\(table))").compactMap{$0["name"] as? String}
    if !columns.contains(column){try execute("ALTER TABLE \(table) ADD COLUMN \(column) \(declaration)")}
  }

  private func migrateSchema6()throws {
    let version=(try query("PRAGMA user_version").first?["user_version"] as? Int64) ?? 0
    guard version<6 else{return}
    try transaction {
      try ensureColumn("jobs","label_color","TEXT DEFAULT NULL")
      try execute("INSERT OR IGNORE INTO settings(key,value) VALUES('app_rating','0')")
      try execute("INSERT OR IGNORE INTO settings(key,value) VALUES('library_sort','recent')")
      try execute("PRAGMA user_version=6")
    }
  }

  private func decodeWarnings(_ raw:String?)->[[String:Any]]{guard let raw,let data=raw.data(using:.utf8),let rows=try? JSONSerialization.jsonObject(with:data) as? [[String:Any]] else{return []};return rows}

  private func migrateSnapshots()throws {
    for row in try query("SELECT id,path,source_sha256 FROM documents WHERE source_sha256='' OR source_sha256 IS NULL") {
      guard let id=row["id"] as? String,let path=row["path"] as? String,FileManager.default.fileExists(atPath:path),let digest=try? sha256(at:URL(fileURLWithPath:path)) else{continue}
      try execute("UPDATE documents SET source_sha256=? WHERE id=?",[digest,id])
    }
    for row in try query("SELECT id,document_id,model_id,glossary_id,source_lang,target_lang,pipeline_version FROM jobs WHERE pipeline_version<4 AND (model_revision='' OR source_sha256='' OR glossary_snapshot='')") {
      guard let id=row["id"] as? String,let documentId=row["document_id"] as? String,let modelId=row["model_id"] as? String,
        let definition=try? ModelCatalog.get(modelId) else{continue}
      let source=try documentSHA256(documentId) ?? ""
      let glossaryId=row["glossary_id"] as? String
      let sourceLanguage=row["source_lang"] as? String ?? "en",targetLanguage=row["target_lang"] as? String ?? "ru"
      let glossary=try? glossaryText(glossaryId,sourceLanguage:sourceLanguage,targetLanguage:targetLanguage)
      try execute("UPDATE jobs SET model_revision=CASE WHEN model_revision='' THEN ? ELSE model_revision END,source_sha256=CASE WHEN source_sha256='' THEN ? ELSE source_sha256 END,glossary_snapshot=CASE WHEN glossary_snapshot='' THEN ? ELSE glossary_snapshot END WHERE id=?",[definition.revision,source,glossary ?? "",id])
    }
  }
}

func jsonObject(_ raw: String) throws -> [String: Any] {
  guard let data = raw.data(using:.utf8), let result = try JSONSerialization.jsonObject(with:data) as? [String: Any] else {
    throw NSError(domain:"PdfTranslator",code:4,userInfo:[NSLocalizedDescriptionKey:"Native core returned invalid JSON."])
  }
  return result
}

func jsonString(_ value: Any) throws -> String {
  let data = try JSONSerialization.data(withJSONObject:value,options:[.sortedKeys,.fragmentsAllowed])
  return String(decoding:data,as:UTF8.self)
}

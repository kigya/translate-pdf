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
    try execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY,document_id TEXT NOT NULL,model_id TEXT NOT NULL,model_revision TEXT NOT NULL DEFAULT '',source_sha256 TEXT NOT NULL DEFAULT '',glossary_snapshot TEXT NOT NULL DEFAULT '',pipeline_version INTEGER NOT NULL DEFAULT 1,source_lang TEXT NOT NULL,target_lang TEXT NOT NULL,status TEXT NOT NULL,stage TEXT NOT NULL,completed_pages INTEGER NOT NULL DEFAULT 0,total_pages INTEGER NOT NULL,current_page INTEGER,glossary_id TEXT,error TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE)")
    try execute("CREATE INDEX IF NOT EXISTS jobs_document_id ON jobs(document_id)")
    try execute("CREATE TABLE IF NOT EXISTS pages (job_id TEXT NOT NULL,page_index INTEGER NOT NULL,status TEXT NOT NULL,blocks_json TEXT NOT NULL,error TEXT,updated_at INTEGER NOT NULL,PRIMARY KEY(job_id,page_index),FOREIGN KEY(job_id) REFERENCES jobs(id) ON DELETE CASCADE)")
    try execute("CREATE TABLE IF NOT EXISTS glossary (id TEXT PRIMARY KEY,source TEXT NOT NULL,target TEXT NOT NULL,created_at INTEGER NOT NULL)")
    try execute("CREATE TABLE IF NOT EXISTS exports (id TEXT PRIMARY KEY,path TEXT NOT NULL,name TEXT NOT NULL,sha256 TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)")
    try ensureColumn("exports","sha256","TEXT NOT NULL DEFAULT ''")
    try ensureColumn("documents","source_sha256","TEXT NOT NULL DEFAULT ''")
    try ensureColumn("jobs","model_revision","TEXT NOT NULL DEFAULT ''")
    try ensureColumn("jobs","source_sha256","TEXT NOT NULL DEFAULT ''")
    try ensureColumn("jobs","glossary_snapshot","TEXT NOT NULL DEFAULT ''")
    try ensureColumn("jobs","pipeline_version","INTEGER NOT NULL DEFAULT 1")
    try ensureColumn("jobs","model_profile","TEXT NOT NULL DEFAULT ''")
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
          if let bytes = sqlite3_column_blob(statement, column) { row[String(cString: name)] = Data(bytes: bytes, count: length) }
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

  func selectedModelId() throws -> String {
    try resolveSelectedModelId(recommended: ModelCatalog.recommendedId, installedMemoryEligible: [])
  }

  /// Atomically initializes/repairs the preference, while keeping every valid saved choice
  /// stable when device-resource recommendations change between bootstrap calls.
  func resolveSelectedModelId(recommended: String, installedMemoryEligible: [String]) throws -> String {
    lock.lock(); defer { lock.unlock() }
    let saved = try setting("selected_model_id", fallback: "")
    if let definition = try? ModelCatalog.get(saved), definition.translationEnabled {
      return definition.id
    }

    let installedIds = Set(installedMemoryEligible)
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
    return definition.id
  }

  func saveDocument(id: String, name: String, path: String, pages: Int, sourceSHA256: String) throws -> [String: Any] {
    let importedAt = Int64(Date().timeIntervalSince1970 * 1000)
    try execute("INSERT INTO documents(id,name,path,pages,source_sha256,imported_at) VALUES(?,?,?,?,?,?)", [id,name,path,pages,sourceSHA256,importedAt])
    return ["id":id,"name":name,"pageCount":pages,"importedAt":importedAt]
  }

  func documentPath(_ id: String) throws -> String? { try query("SELECT path FROM documents WHERE id=?", [id]).first?["path"] as? String }
  func documentSHA256(_ id: String) throws -> String? { try query("SELECT source_sha256 FROM documents WHERE id=?", [id]).first?["source_sha256"] as? String }

  func documents() throws -> [[String: Any]] {
    try query("SELECT id,name,pages,imported_at FROM documents ORDER BY imported_at DESC").map { ["id":$0["id"]!,"name":$0["name"]!,"pageCount":$0["pages"]!,"importedAt":$0["imported_at"]!] }
  }

  func modelState(_ id: String) throws -> [String: Any]? { try query("SELECT status,downloaded_bytes,error FROM models WHERE id=?", [id]).first }
  func updateModel(_ id: String, status: String, bytes: Int64, error: String? = nil) throws {
    let errorValue: Any = error.map { $0 as Any } ?? NSNull()
    try execute("INSERT OR REPLACE INTO models(id,status,downloaded_bytes,error) VALUES(?,?,?,?)", [id,status,bytes,errorValue])
  }
  func deleteModel(_ id: String) throws { try execute("DELETE FROM models WHERE id=?", [id]) }

  func models(totalMemory: UInt64, availableMemory: UInt64, availableDisk: UInt64, systemLowMemory: Bool) throws -> [[String: Any]] {
    try ModelCatalog.models.map { definition in
      let state = try modelState(definition.id)
      let status = state?["status"] as? String ?? "not_downloaded"
      let recordedBytes = state?["downloaded_bytes"] as? Int64 ?? 0
      let partialBytes = ((try? FileManager.default.attributesOfItem(atPath:definition.partialURL.path)[.size]) as? NSNumber)?.int64Value ?? 0
      let downloadedBytes = min(definition.bytes,max(recordedBytes,partialBytes))
      let assessment = MemoryPolicy.assess(definition, status: status, downloadedBytes: downloadedBytes, total: totalMemory, available: availableMemory, availableDisk: availableDisk, systemLowMemory: systemLowMemory)
      return definition.snapshot(status: status, downloadedBytes: downloadedBytes, error: state?["error"] as? String, assessment: assessment)
    }
  }

  func addJob(id: String, input: [String: Any], pageCount: Int) throws -> [String: Any] {
    let now = Int64(Date().timeIntervalSince1970 * 1000)
    guard let documentId=input["documentId"] as? String,let modelId=input["modelId"] as? String,
      let definition=try? ModelCatalog.get(modelId),let sourceSHA256=try documentSHA256(documentId),!sourceSHA256.isEmpty else {
      throw NSError(domain:"PdfTranslator",code:5,userInfo:[NSLocalizedDescriptionKey:"The source file or pinned model metadata is unavailable."])
    }
    let glossaryId=input["glossaryId"] as? String
    let glossarySnapshot=try glossaryText(glossaryId)
    guard definition.supportsGlossary || glossarySnapshot.isEmpty else { throw NSError(domain:"PdfTranslator",code:11,userInfo:[NSLocalizedDescriptionKey:"The selected model profile does not support glossary instructions. Clear the glossary or choose a glossary-compatible model before creating the job."]) }
    try execute("INSERT INTO jobs(id,document_id,model_id,model_revision,source_sha256,glossary_snapshot,pipeline_version,model_profile,source_lang,target_lang,status,stage,completed_pages,total_pages,current_page,glossary_id,error,created_at,updated_at) VALUES(?,?,?,?,?,?,3,?,?,?,'queued','queued',0,?,NULL,?,NULL,?,?)", [id,documentId,modelId,definition.revision,sourceSHA256,glossarySnapshot,definition.profile,input["sourceLanguage"] as! String,input["targetLanguage"] as! String,pageCount,glossaryId ?? NSNull(),now,now])
    return try job(id) ?? [:]
  }

  func job(_ id: String) throws -> [String: Any]? {
    guard let r = try query("SELECT j.id,j.document_id,d.name,j.model_id,j.model_revision,j.model_profile,j.source_sha256,j.pipeline_version,j.source_lang,j.target_lang,j.status,j.stage,j.completed_pages,j.total_pages,j.current_page,j.error,j.created_at,j.updated_at FROM jobs j JOIN documents d ON d.id=j.document_id WHERE j.id=?", [id]).first else { return nil }
    return ["id":r["id"]!,"documentId":r["document_id"]!,"documentName":r["name"]!,"modelId":r["model_id"]!,"modelRevision":r["model_revision"]!,"modelProfile":r["model_profile"]!,"sourceSHA256":r["source_sha256"]!,"pipelineVersion":r["pipeline_version"]!,"sourceLanguage":r["source_lang"]!,"targetLanguage":r["target_lang"]!,"status":r["status"]!,"stage":r["stage"]!,"completedPages":r["completed_pages"]!,"totalPages":r["total_pages"]!,"currentPage":r["current_page"] ?? NSNull(),"error":r["error"] ?? NSNull(),"createdAt":r["created_at"]!,"updatedAt":r["updated_at"]!]
  }

  func jobs() throws -> [[String: Any]] { try query("SELECT id FROM jobs ORDER BY created_at DESC").compactMap { try job($0["id"] as! String) } }
  func jobSource(_ id: String) throws -> String? { try query("SELECT d.path FROM jobs j JOIN documents d ON d.id=j.document_id WHERE j.id=?", [id]).first?["path"] as? String }
  func jobGlossaryText(_ id: String) throws -> String { try query("SELECT glossary_snapshot FROM jobs WHERE id=?", [id]).first?["glossary_snapshot"] as? String ?? "" }

  func validateJobSnapshot(_ id:String,model:ModelDefinition)throws {
    guard let row=try query("SELECT model_id,model_revision,model_profile,source_sha256,pipeline_version FROM jobs WHERE id=?",[id]).first,
      row["model_id"] as? String == model.id,
      row["model_revision"] as? String == model.revision,
      row["model_profile"] as? String == model.profile,
      row["pipeline_version"] as? Int64 == 3,
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
    let metadata = try jsonObject(TranslateCoreBridge.readPage(path, index: index))
    if metadata["error"] != nil { throw failure(String(describing: metadata["error"]!)) }
    var page:[String:Any] = ["index":index,"status":row["status"]!,"width":metadata["width"] ?? 0,"height":metadata["height"] ?? 0,"blocks":try JSONSerialization.jsonObject(with: Data(blocks.utf8))]
    if let message=row["error"] as? String { page["error"] = message }
    return page
  }

  func deleteJob(_ id: String) throws { try execute("DELETE FROM jobs WHERE id=?", [id]) }
  func deleteDocument(_ id: String) throws { try execute("DELETE FROM documents WHERE id=?", [id]) }
  func glossary() throws -> [[String: Any]] { try query("SELECT id,source,target,created_at FROM glossary ORDER BY source COLLATE NOCASE").map { ["id":$0["id"]!,"source":$0["source"]!,"target":$0["target"]!,"createdAt":$0["created_at"]!] } }
  func saveGlossary(_ id: String, source: String, target: String) throws -> [String: Any] {
    let now = Int64(Date().timeIntervalSince1970 * 1000)
    try execute("INSERT OR REPLACE INTO glossary(id,source,target,created_at) VALUES(?,?,?,?)", [id,source.trimmingCharacters(in:.whitespacesAndNewlines),target.trimmingCharacters(in:.whitespacesAndNewlines),now])
    return ["id":id,"source":source.trimmingCharacters(in:.whitespacesAndNewlines),"target":target.trimmingCharacters(in:.whitespacesAndNewlines),"createdAt":now]
  }
  func glossaryText(_ id: String?) throws -> String {
    let rows = try id == nil ? query("SELECT source,target FROM glossary ORDER BY source COLLATE NOCASE") : query("SELECT source,target FROM glossary WHERE id=?", [id!])
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

  private func migrateSnapshots()throws {
    for row in try query("SELECT id,path,source_sha256 FROM documents WHERE source_sha256='' OR source_sha256 IS NULL") {
      guard let id=row["id"] as? String,let path=row["path"] as? String,FileManager.default.fileExists(atPath:path),let digest=try? sha256(at:URL(fileURLWithPath:path)) else{continue}
      try execute("UPDATE documents SET source_sha256=? WHERE id=?",[digest,id])
    }
    for row in try query("SELECT id,document_id,model_id,glossary_id FROM jobs WHERE model_revision='' OR source_sha256='' OR glossary_snapshot=''") {
      guard let id=row["id"] as? String,let documentId=row["document_id"] as? String,let modelId=row["model_id"] as? String,
        let definition=try? ModelCatalog.get(modelId) else{continue}
      let source=try documentSHA256(documentId) ?? ""
      let glossaryId=row["glossary_id"] as? String
      let glossary=try glossaryText(glossaryId)
      try execute("UPDATE jobs SET model_revision=CASE WHEN model_revision='' THEN ? ELSE model_revision END,source_sha256=CASE WHEN source_sha256='' THEN ? ELSE source_sha256 END,glossary_snapshot=CASE WHEN glossary_snapshot='' THEN ? ELSE glossary_snapshot END WHERE id=?",[definition.revision,source,glossary,id])
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

package dev.kigya.translatepdf

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal class TranslatorDatabase(
  context: Context,
  private val queryForRead: ((SQLiteDatabase, String, Array<String>?) -> Cursor)? = null,
  private val openSource: (File) -> InputStream = { it.inputStream() },
) :
  SQLiteOpenHelper(context, "translator-private.db", null, 7) {

  companion object {
    const val PIPELINE_VERSION = 4
    val JOB_LABEL_COLORS = setOf("red", "orange", "yellow", "green", "blue", "purple")
  }

  override fun onConfigure(db: SQLiteDatabase) {
    super.onConfigure(db)
    db.setForeignKeyConstraintsEnabled(true)
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    db.execSQL("CREATE TABLE documents (id TEXT PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL, pages INTEGER NOT NULL, source_sha256 TEXT NOT NULL, imported_at INTEGER NOT NULL, format TEXT NOT NULL DEFAULT 'pdf', segment_count INTEGER NOT NULL DEFAULT 0, warnings_json TEXT NOT NULL DEFAULT '[]')")
    db.execSQL("CREATE TABLE models (id TEXT PRIMARY KEY, status TEXT NOT NULL, downloaded_bytes INTEGER NOT NULL DEFAULT 0, error TEXT)")
    db.execSQL("CREATE TABLE jobs (id TEXT PRIMARY KEY, document_id TEXT NOT NULL, model_id TEXT NOT NULL, model_revision TEXT NOT NULL, model_profile TEXT NOT NULL, source_sha256 TEXT NOT NULL, glossary_snapshot TEXT NOT NULL, pipeline_version INTEGER NOT NULL, source_lang TEXT NOT NULL, target_lang TEXT NOT NULL, status TEXT NOT NULL, stage TEXT NOT NULL, completed_pages INTEGER NOT NULL DEFAULT 0, total_pages INTEGER NOT NULL, current_page INTEGER, glossary_id TEXT, error TEXT, observed_block_ms INTEGER NOT NULL DEFAULT 0, observed_block_count INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, engine_type TEXT NOT NULL DEFAULT 'local', engine_id TEXT NOT NULL DEFAULT '', online_consent INTEGER NOT NULL DEFAULT 0, progress_unit TEXT NOT NULL DEFAULT 'page', label_color TEXT, FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE)")
    db.execSQL("CREATE INDEX jobs_document_id ON jobs(document_id)")
    db.execSQL("CREATE TABLE pages (job_id TEXT NOT NULL, page_index INTEGER NOT NULL, status TEXT NOT NULL, blocks_json TEXT NOT NULL, width REAL NOT NULL DEFAULT 0, height REAL NOT NULL DEFAULT 0, expected_blocks INTEGER, error TEXT, updated_at INTEGER NOT NULL, PRIMARY KEY(job_id, page_index), FOREIGN KEY(job_id) REFERENCES jobs(id) ON DELETE CASCADE)")
    db.execSQL("CREATE TABLE glossary (id TEXT PRIMARY KEY, source TEXT NOT NULL, target TEXT NOT NULL, source_lang TEXT NOT NULL DEFAULT 'en', target_lang TEXT NOT NULL DEFAULT 'ru', created_at INTEGER NOT NULL)")
    db.execSQL("CREATE TABLE exports (id TEXT PRIMARY KEY, path TEXT NOT NULL, name TEXT NOT NULL, created_at INTEGER NOT NULL, mime_type TEXT NOT NULL DEFAULT 'application/pdf')")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE jobs ADD COLUMN observed_block_ms INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE jobs ADD COLUMN observed_block_count INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE pages ADD COLUMN expected_blocks INTEGER")
    }
    if (oldVersion < 3) db.execSQL("ALTER TABLE jobs ADD COLUMN model_profile TEXT NOT NULL DEFAULT 'hy-mt2'")
    if (oldVersion < 4) {
      db.execSQL("ALTER TABLE glossary ADD COLUMN source_lang TEXT NOT NULL DEFAULT 'en'")
      db.execSQL("ALTER TABLE glossary ADD COLUMN target_lang TEXT NOT NULL DEFAULT 'ru'")
    }
    if (oldVersion < 5) {
      db.execSQL("ALTER TABLE documents ADD COLUMN format TEXT NOT NULL DEFAULT 'pdf'")
      db.execSQL("ALTER TABLE documents ADD COLUMN segment_count INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE jobs ADD COLUMN engine_type TEXT NOT NULL DEFAULT 'local'")
      db.execSQL("ALTER TABLE jobs ADD COLUMN engine_id TEXT NOT NULL DEFAULT ''")
      db.execSQL("UPDATE jobs SET engine_id=model_id WHERE engine_id=''")
      db.execSQL("ALTER TABLE jobs ADD COLUMN online_consent INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE jobs ADD COLUMN progress_unit TEXT NOT NULL DEFAULT 'page'")
      db.execSQL("ALTER TABLE exports ADD COLUMN mime_type TEXT NOT NULL DEFAULT 'application/pdf'")
    }
    if (oldVersion < 6) db.execSQL("ALTER TABLE documents ADD COLUMN warnings_json TEXT NOT NULL DEFAULT '[]'")
    if (oldVersion < 7) db.execSQL("ALTER TABLE jobs ADD COLUMN label_color TEXT")
  }

  private fun readQuery(sql: String, arguments: Array<String>?): Cursor {
    val db = readableDatabase
    return queryForRead?.invoke(db, sql, arguments) ?: db.rawQuery(sql, arguments)
  }

  fun setting(key: String, fallback: String): String = readQuery(
    "SELECT value FROM settings WHERE key=?", arrayOf(key),
  ).use { if (it.moveToFirst()) it.getString(0) else fallback }

  fun selectedLanguagePair(): Pair<String, String> {
    var source = setting("sourceLanguage", "en")
    var target = setting("targetLanguage", "ru")
    if (!LanguagePair.isValidCode(source) || !LanguagePair.isValidCode(target) || source == target) {
      source = "en"; target = "ru"
    }
    setSetting("sourceLanguage", source)
    setSetting("targetLanguage", target)
    return source to target
  }

  fun setSetting(key: String, value: String) {
    writableDatabase.insertWithOnConflict("settings", null, ContentValues().apply {
      put("key", key); put("value", value)
    }, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun appRating(): Int = setting("appRating", "0").toIntOrNull()?.takeIf { it in 0..5 } ?: 0

  fun librarySort(): String = setting("librarySort", "recent").takeIf { it == "recent" || it == "color" } ?: "recent"

  fun setJobColor(id: String, color: String?) {
    require(color == null || color in JOB_LABEL_COLORS) { "Choose a supported label color." }
    val db = writableDatabase
    db.beginTransaction()
    try {
      val status = db.rawQuery("SELECT status FROM jobs WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) it.getString(0) else null
      } ?: throw IllegalArgumentException("Translation job was not found.")
      require(status == "completed" || status == "partial") { "Only completed or partially translated documents can be labeled." }
      val values = ContentValues().apply { if (color == null) putNull("label_color") else put("label_color", color) }
      check(db.update("jobs", values, "id=? AND status IN ('completed','partial')", arrayOf(id)) == 1) {
        "The translation job changed before its label could be saved."
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  fun selectedModelId(initialId: String): String {
    // ModelCatalog.models is the active, selectable catalog; legacy disabled
    // candidates intentionally remain outside this list.
    fun valid(id: String) = ModelCatalog.models.any { it.id == id }
    require(valid(initialId))
    val saved = setting("selectedModelId", "")
    if (valid(saved)) return saved
    val db = writableDatabase
    db.beginTransaction()
    return try {
      val current = setting("selectedModelId", "")
      val selected = if (valid(current)) current else initialId.also { setSetting("selectedModelId", it) }
      db.setTransactionSuccessful()
      selected
    } finally { db.endTransaction() }
  }

  fun selectedEngine(initialModelId: String): JSONObject {
    val savedKind = setting("selectedEngineKind", "local")
    val savedId = setting("selectedEngineId", "")
    if (savedKind == "online" && savedId == "mymemory") {
      return JSONObject().put("kind", "online").put("id", "mymemory")
    }
    val modelId = if (savedKind == "local" && ModelCatalog.models.any { it.id == savedId }) savedId else selectedModelId(initialModelId)
    setSetting("selectedEngineKind", "local")
    setSetting("selectedEngineId", modelId)
    return JSONObject().put("kind", "local").put("id", modelId)
  }

  fun setSelectedEngine(kind: String, id: String) {
    require(kind == "local" || kind == "online") { "Choose a supported translation engine." }
    if (kind == "online") require(id == "mymemory") { "Choose the supported online translation provider." }
    else {
      val model = ModelCatalog.get(id)
      require(model.translationEnabled) { "This model is unavailable in the current runtime." }
      setSetting("selectedModelId", model.id)
    }
    setSetting("selectedEngineKind", kind)
    setSetting("selectedEngineId", id)
  }

  fun upsertDocument(id: String, name: String, path: String, pages: Int, sourceSha256: String, format: String = "pdf", segmentCount: Int = 0, warnings: JSONArray = JSONArray()): JSONObject {
    require(format == "pdf" || format == "docx")
    val importedAt = System.currentTimeMillis()
    writableDatabase.insertOrThrow("documents", null, ContentValues().apply {
      put("id", id); put("name", name); put("path", path); put("pages", pages); put("source_sha256", sourceSha256); put("imported_at", importedAt)
      put("format", format); put("segment_count", segmentCount); put("warnings_json", warnings.toString())
    })
    return JSONObject().put("id", id).put("name", name).put("pageCount", pages).put("segmentCount", segmentCount)
      .put("format", format).put("importedAt", importedAt).put("warnings", warnings)
  }

  fun documentPath(id: String): String? = readQuery(
    "SELECT path FROM documents WHERE id=?", arrayOf(id),
  ).use { if (it.moveToFirst()) it.getString(0) else null }

  fun documentsJson(): JSONArray = readQuery(
    "SELECT id,name,pages,imported_at,format,segment_count FROM documents ORDER BY imported_at DESC", null,
  ).use { cursor -> JSONArray().also { out ->
    while (cursor.moveToNext()) out.put(JSONObject()
      .put("id", cursor.getString(0)).put("name", cursor.getString(1))
      .put("pageCount", cursor.getInt(2)).put("importedAt", cursor.getLong(3))
      .put("format", cursor.getString(4)).put("segmentCount", cursor.getInt(5)))
  } }

  fun upsertModel(id: String, status: String, downloadedBytes: Long, error: String? = null) {
    writableDatabase.insertWithOnConflict("models", null, ContentValues().apply {
      put("id", id); put("status", status); put("downloaded_bytes", downloadedBytes); put("error", error)
    }, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun modelState(id: String): Triple<String, Long, String?>? = readQuery(
    "SELECT status,downloaded_bytes,error FROM models WHERE id=?", arrayOf(id),
  ).use { if (it.moveToFirst()) Triple(it.getString(0), it.getLong(1), if (it.isNull(2)) null else it.getString(2)) else null }

  fun modelsJson(): JSONArray = JSONArray().also { out ->
    for (definition in ModelCatalog.models) {
      val state = modelState(definition.id)
      out.put(definition.toJson(state?.first ?: "not_downloaded", state?.second ?: 0L, state?.third))
    }
  }

  fun modelStatus(id: String, status: String, bytes: Long, error: String? = null) {
    upsertModel(id, status, bytes, error)
  }

  fun addJob(id: String, input: JSONObject, pages: Int): JSONObject {
    val now = System.currentTimeMillis()
    val documentId = input.getString("documentId")
    val sourceSha = readQuery("SELECT source_sha256 FROM documents WHERE id=?", arrayOf(documentId)).use {
      if (it.moveToFirst()) it.getString(0) else throw IllegalArgumentException("The source document was not found.")
    }
    val engine = input.optJSONObject("engine")
    require(!input.has("engine") || engine != null) { "The selected translation engine is invalid." }
    val engineType = engine?.optString("kind", "local") ?: "local"
    require(engineType == "local" || engineType == "online") { "Choose a supported translation engine." }
    val engineId = engine?.optString("id")?.takeIf(String::isNotBlank) ?: input.optString("modelId")
    require(engineType != "online" || (engineId == "mymemory" && input.optBoolean("onlineConsent"))) {
      "Review and accept the online translation privacy notice before sending document text."
    }
    val modelId = if (engineType == "online") "mymemory" else input.getString("modelId")
    val model = if (engineType == "local") ModelCatalog.get(modelId) else null
    val modelRevision = model?.revision ?: "public-api-v1"
    val glossaryId = input.optString("glossaryId").takeIf(String::isNotBlank)
    val (sourceLanguage, targetLanguage) = LanguagePair.fromJson(input).also { LanguagePair.requireValid(it.first, it.second) }
    val glossarySnapshot = if (engineType == "local" && input.optBoolean("useGlossary", true)) glossaryText(glossaryId, sourceLanguage, targetLanguage) else ""
    writableDatabase.insertOrThrow("jobs", null, ContentValues().apply {
      put("id", id); put("document_id", documentId); put("model_id", modelId)
      put("model_revision", modelRevision); put("model_profile", model?.profile ?: "online"); put("source_sha256", sourceSha); put("glossary_snapshot", glossarySnapshot)
      put("pipeline_version", if (engineType == "local" && documentFormat(documentId) == "pdf") PIPELINE_VERSION else 5)
      put("source_lang", sourceLanguage); put("target_lang", targetLanguage)
      put("status", "queued"); put("stage", "queued"); put("completed_pages", 0); put("total_pages", pages)
      putNull("current_page"); put("glossary_id", glossaryId)
      putNull("error"); put("observed_block_ms", 0); put("observed_block_count", 0)
      put("created_at", now); put("updated_at", now)
      put("engine_type", engineType); put("engine_id", engineId)
      put("online_consent", if (engineType == "online" && input.optBoolean("onlineConsent")) 1 else 0)
      put("progress_unit", if (documentFormat(documentId) == "docx") "segment" else "page")
    })
    return jobJson(id) ?: error("New job could not be read.")
  }

  fun jobJson(id: String): JSONObject? = readQuery(
    "SELECT j.id,j.document_id,d.name,j.model_id,j.source_lang,j.target_lang,j.status,j.stage,j.completed_pages,j.total_pages,j.current_page,j.error,j.created_at,j.updated_at,j.model_revision,j.source_sha256,j.pipeline_version,j.model_profile,j.engine_type,j.engine_id,j.online_consent,j.progress_unit,d.format,d.segment_count,d.warnings_json,j.label_color FROM jobs j JOIN documents d ON d.id=j.document_id WHERE j.id=?",
    arrayOf(id),
  ).use { c ->
    if (!c.moveToFirst()) null else {
      val job = JSONObject()
      .put("id", c.getString(0)).put("documentId", c.getString(1)).put("documentName", c.getString(2))
      .put("modelId", c.getString(3)).put("sourceLanguage", c.getString(4)).put("targetLanguage", c.getString(5))
      .put("status", c.getString(6)).put("stage", c.getString(7)).put("completedPages", c.getInt(8))
      .put("totalPages", c.getInt(9)).put("currentPage", if (c.isNull(10)) JSONObject.NULL else c.getInt(10))
      .put("error", if (c.isNull(11)) JSONObject.NULL else c.getString(11))
      .put("createdAt", c.getLong(12)).put("updatedAt", c.getLong(13))
      .put("modelRevision", c.getString(14)).put("sourceSHA256", c.getString(15))
      .put("pipelineVersion", c.getInt(16)).put("modelProfile", c.getString(17))
      .put("engine", JSONObject().put("kind", c.getString(18)).put("id", c.getString(19)))
      .put("onlineConsent", c.getInt(20) == 1).put("progressUnit", c.getString(21))
      .put("format", c.getString(22)).put("segmentCount", c.getInt(23))
      .put("labelColor", if (c.isNull(25)) JSONObject.NULL else c.getString(25))
      val warnings = JSONArray()
      val warningSet = linkedSetOf<String>()
      val documentWarnings: JSONArray? = try { JSONArray(c.getString(24)) } catch (_: Exception) { null }
      documentWarnings?.let {
        for (wi in 0 until documentWarnings.length()) documentWarnings.optString(wi).takeIf { it.isNotBlank() && it != "null" }?.let(warningSet::add)
      }
      readQuery("SELECT error FROM pages WHERE job_id=? AND error IS NOT NULL", arrayOf(id)).use { rows ->
        while (rows.moveToNext()) rows.getString(0).takeIf { it.isNotBlank() && it != "null" }?.let { warningSet.add(it) }
      }
      warningSet.forEach { warnings.put(it) }
      job.put("warnings", warnings)
      job.put("documentFormat", c.getString(22))
      val stats = blockProgress(id)
      job.put("completedBlocks", stats.first).put("discoveredBlocks", stats.second)
      val knownTotal = knownBlockTotal(id, c.getInt(9))
      job.put("totalBlocks", knownTotal ?: JSONObject.NULL)
      val timing = readQuery("SELECT observed_block_ms,observed_block_count,status,stage FROM jobs WHERE id=?", arrayOf(id)).use { row ->
        if (row.moveToFirst()) longArrayOf(row.getLong(0), row.getLong(1), if (row.getString(2) == "running") 1 else 0, if (row.getString(3) == "translating") 1 else 0) else longArrayOf(0, 0, 0, 0)
      }
      val remaining = knownTotal?.minus(stats.first)?.coerceAtLeast(0)
      if (timing[1] >= 10 && timing[2] == 1L && timing[3] == 1L && remaining != null && remaining > 0) {
        val estimate = ((timing[0].toDouble() / timing[1]) * remaining / 1_000.0).toLong().coerceAtLeast(1)
        job.put("etaSeconds", estimate)
        job.put("etaRangeSeconds", JSONObject().put("min", (estimate * 0.7).toLong().coerceAtLeast(1)).put("max", (estimate * 1.3).toLong().coerceAtLeast(estimate)))
      } else {
        job.put("etaSeconds", JSONObject.NULL).put("etaRangeSeconds", JSONObject.NULL)
      }
      job
    }
  }

  private fun blockProgress(jobId: String): Pair<Int, Int> = readQuery(
    "SELECT blocks_json FROM pages WHERE job_id=?", arrayOf(jobId),
  ).use { c ->
    var completed = 0; var discovered = 0
    while (c.moveToNext()) {
      val blocks = runCatching { JSONArray(c.getString(0)) }.getOrNull() ?: continue
      discovered += blocks.length()
      for (index in 0 until blocks.length()) if (blocks.optJSONObject(index)?.optString("status") in setOf("translated", "partial", "failed", "skipped")) completed++
    }
    completed to discovered
  }

  private fun knownBlockTotal(jobId: String, totalPages: Int): Int? = readQuery(
    "SELECT expected_blocks FROM pages WHERE job_id=?", arrayOf(jobId),
  ).use { c ->
    var count = 0; var pages = 0
    while (c.moveToNext()) {
      pages++
      if (c.isNull(0)) return@use null
      count += c.getInt(0)
    }
    if (pages == totalPages) count else null
  }

  fun recordBlockDuration(jobId: String, durationMillis: Long) {
    writableDatabase.execSQL("UPDATE jobs SET observed_block_ms=observed_block_ms+?, observed_block_count=observed_block_count+1, updated_at=? WHERE id=?",
      arrayOf(durationMillis.coerceAtLeast(0), System.currentTimeMillis(), jobId))
  }

  fun jobsJson(): JSONArray = readQuery("SELECT id FROM jobs ORDER BY created_at DESC", null).use { c ->
    JSONArray().also { out -> while (c.moveToNext()) jobJson(c.getString(0))?.let(out::put) }
  }

  fun jobDocumentPath(id: String): String? = readQuery(
    "SELECT d.path FROM jobs j JOIN documents d ON d.id=j.document_id WHERE j.id=?", arrayOf(id),
  ).use { if (it.moveToFirst()) it.getString(0) else null }

  fun requireCurrentPipeline(id: String) {
    val version = readQuery("SELECT pipeline_version FROM jobs WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getInt(0) else -1 }
    val compatible = if (version == PIPELINE_VERSION) true else if (version == 5) {
      readQuery("SELECT j.engine_type,d.format FROM jobs j JOIN documents d ON d.id=j.document_id WHERE j.id=?", arrayOf(id)).use {
        it.moveToFirst() && (it.getString(0) != "local" || it.getString(1) != "pdf")
      }
    } else false
    check(compatible) { "This job uses an older processing pipeline. Start a new job; its saved pages remain available for review." }
  }

  fun validateJobSnapshot(id: String, model: ModelDefinition) {
    validateJobSnapshotInternal(id, model)
  }

  fun validateJobSnapshot(id: String) {
    val job = jobJson(id) ?: error("Translation job was not found.")
    val model = if (job.optJSONObject("engine")?.optString("kind") == "online") null else ModelCatalog.get(job.getString("modelId"))
    validateJobSnapshotInternal(id, model)
  }

  private fun validateJobSnapshotInternal(id: String, model: ModelDefinition?) {
    requireCurrentPipeline(id)
    val job = jobJson(id) ?: error("Translation job was not found.")
    if (model != null) check(job.getString("modelId") == model.id && job.getString("modelRevision") == model.revision && job.getString("modelProfile") == model.profile) {
      "This job's model or processing profile changed. Start a new job to use the current inputs."
    } else check(job.getJSONObject("engine").optString("kind") == "online" && job.getJSONObject("engine").optString("id") == "mymemory" && job.optBoolean("onlineConsent")) {
      "This online job no longer has a valid provider consent snapshot. Start a new job after reviewing the privacy notice."
    }
    val file = File(jobDocumentPath(id) ?: error("The saved source document is unavailable."))
    val digest = MessageDigest.getInstance("SHA-256")
    openSource(file).use { input ->
      val buffer = ByteArray(1024 * 1024)
      while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    check(actual == job.getString("sourceSHA256")) { "This job's source changed. Start a new job to use the current document." }
  }

  fun hasActiveJob(exceptId: String? = null): Boolean {
    val sql = if (exceptId == null) "SELECT 1 FROM jobs WHERE status IN ('running','queued') LIMIT 1"
      else "SELECT 1 FROM jobs WHERE status IN ('running','queued') AND id<>? LIMIT 1"
    val args = if (exceptId == null) null else arrayOf(exceptId)
    return readQuery(sql, args).use { it.moveToFirst() }
  }

  fun modelInUse(modelId: String): Boolean = readQuery(
    "SELECT 1 FROM jobs WHERE model_id=? AND status IN ('running','queued') LIMIT 1", arrayOf(modelId),
  ).use { it.moveToFirst() }

  fun recoverInterruptedJobs(activeId: String?) {
    val interrupted = readQuery("SELECT id FROM jobs WHERE status='running'", null).use { cursor ->
      val ids = mutableListOf<String>()
      while (cursor.moveToNext()) {
        val id = cursor.getString(0)
        if (id != activeId) ids.add(id)
      }
      ids
    }
    interrupted.forEach { id ->
      // A page row is the durable source of truth. The process can die after the
      // page transaction commits but before its denormalized job counter updates.
      updateJob(id, status = "paused", completedPages = completedPageCount(id),
        error = "Paused after the latest saved page checkpoint.")
    }
  }

  fun updateJob(id: String, status: String? = null, stage: String? = null, completedPages: Int? = null,
                currentPage: Int? = null, error: String? = null, clearError: Boolean = false,
                clearCurrentPage: Boolean = false) {
    val values = ContentValues().apply {
      status?.let { put("status", it) }; stage?.let { put("stage", it) }
      completedPages?.let { put("completed_pages", it) }
      if (currentPage != null) put("current_page", currentPage)
      if (clearCurrentPage) putNull("current_page")
      if (error != null) put("error", error)
      if (clearError) putNull("error")
      put("updated_at", System.currentTimeMillis())
    }
    writableDatabase.update("jobs", values, "id=?", arrayOf(id))
  }

  /** Atomically transitions a job only while a worker still owns its active row. */
  fun transitionActiveJob(id: String, status: String, stage: String? = null, error: String? = null): Boolean {
    val values = ContentValues().apply {
      put("status", status)
      stage?.let { put("stage", it) }
      if (error == null) putNull("error") else put("error", error)
      put("updated_at", System.currentTimeMillis())
    }
    return writableDatabase.update("jobs", values, "id=? AND status IN ('queued','running')", arrayOf(id)) > 0
  }

  fun jobGlossaryId(id: String): String? = readQuery(
    "SELECT glossary_id FROM jobs WHERE id=?", arrayOf(id),
  ).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

  fun jobGlossaryText(id: String): String = readQuery(
    "SELECT glossary_snapshot FROM jobs WHERE id=?", arrayOf(id),
  ).use { if (it.moveToFirst()) it.getString(0) else "" }

  fun savePage(jobId: String, pageIndex: Int, status: String, blocksJson: String, width: Double = 0.0, height: Double = 0.0, error: String? = null, expectedBlocks: Int? = null) {
    writableDatabase.insertWithOnConflict("pages", null, ContentValues().apply {
      put("job_id", jobId); put("page_index", pageIndex); put("status", status); put("blocks_json", blocksJson)
      put("width", width); put("height", height); if (expectedBlocks == null) putNull("expected_blocks") else put("expected_blocks", expectedBlocks)
      put("error", error); put("updated_at", System.currentTimeMillis())
    }, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun pageRows(jobId: String): JSONArray = readQuery(
    "SELECT page_index,status FROM pages WHERE job_id=? ORDER BY page_index", arrayOf(jobId),
  ).use { c -> JSONArray().also { out -> while (c.moveToNext()) out.put(JSONObject().put("index", c.getInt(0)).put("status", c.getString(1))) } }

  fun completedPageCount(jobId: String): Int = readQuery(
    "SELECT COUNT(*) FROM pages WHERE job_id=? AND status IN ('completed','partial')", arrayOf(jobId),
  ).use {
    // SQLite aggregate queries without GROUP BY always return one row, even
    // when no pages match; cursor/read failures must propagate to the caller.
    it.moveToFirst()
    it.getInt(0)
  }

  fun page(jobId: String, pageIndex: Int): JSONObject? = readQuery(
    "SELECT status,blocks_json,width,height,error FROM pages WHERE job_id=? AND page_index=?", arrayOf(jobId, pageIndex.toString()),
  ).use { c ->
    if (!c.moveToFirst()) null else {
      val blocks = JSONArray(c.getString(1))
      JSONObject().put("index", pageIndex).put("status", c.getString(0))
        .put("width", c.getDouble(2)).put("height", c.getDouble(3))
        .put("error", if (c.isNull(4)) JSONObject.NULL else c.getString(4))
        .put("blocks", blocks)
    }
  }

  fun documentInfo(id: String): Pair<String, Int>? = readQuery(
    "SELECT path,pages FROM documents WHERE id=?", arrayOf(id),
  ).use { if (it.moveToFirst()) it.getString(0) to it.getInt(1) else null }

  fun documentFormat(id: String): String = readQuery("SELECT format FROM documents WHERE id=?", arrayOf(id)).use {
    if (it.moveToFirst()) it.getString(0) else throw IllegalArgumentException("The source document was not found.")
  }

  fun pageBlockArray(jobId: String, pageIndex: Int): JSONArray = readQuery(
    "SELECT blocks_json FROM pages WHERE job_id=? AND page_index=?", arrayOf(jobId, pageIndex.toString()),
  ).use { if (it.moveToFirst()) JSONArray(it.getString(0)) else JSONArray() }

  fun deleteJob(id: String) { writableDatabase.delete("jobs", "id=?", arrayOf(id)) }
  fun deleteDocument(id: String) { writableDatabase.delete("documents", "id=?", arrayOf(id)) }
  fun deleteModel(id: String) { writableDatabase.delete("models", "id=?", arrayOf(id)) }

  fun glossaryJson(): JSONArray = readQuery(
    "SELECT id,source,target,source_lang,target_lang,created_at FROM glossary ORDER BY source COLLATE NOCASE", null,
  ).use { c -> JSONArray().also { out -> while (c.moveToNext()) out.put(JSONObject()
    .put("id", c.getString(0)).put("source", c.getString(1)).put("target", c.getString(2))
    .put("sourceLanguage", c.getString(3)).put("targetLanguage", c.getString(4)).put("createdAt", c.getLong(5))) } }

  fun saveGlossary(id: String, source: String, target: String, sourceLanguage: String, targetLanguage: String): JSONObject {
    require(source.isNotBlank() && target.isNotBlank()) { "Both glossary terms are required." }
    LanguagePair.requireValid(sourceLanguage, targetLanguage)
    val created = System.currentTimeMillis()
    writableDatabase.insertWithOnConflict("glossary", null, ContentValues().apply {
      put("id", id); put("source", source.trim()); put("target", target.trim())
      put("source_lang", sourceLanguage); put("target_lang", targetLanguage); put("created_at", created)
    }, SQLiteDatabase.CONFLICT_REPLACE)
    return JSONObject().put("id", id).put("source", source.trim()).put("target", target.trim())
      .put("sourceLanguage", sourceLanguage).put("targetLanguage", targetLanguage).put("createdAt", created)
  }

  fun glossaryText(id: String?, sourceLanguage: String, targetLanguage: String): String {
    LanguagePair.requireValid(sourceLanguage, targetLanguage)
    val sql = if (id == null) "SELECT source,target FROM glossary WHERE source_lang=? AND target_lang=? ORDER BY source COLLATE NOCASE"
      else "SELECT source,target FROM glossary WHERE id=? AND source_lang=? AND target_lang=?"
    val args = if (id == null) arrayOf(sourceLanguage, targetLanguage) else arrayOf(id, sourceLanguage, targetLanguage)
    return readQuery(sql, args).use { c -> buildString {
      while (c.moveToNext()) {
        if (isNotEmpty()) append('\n')
        append(c.getString(0)).append(" translates to ").append(c.getString(1))
      }
    } }
  }

  fun deleteGlossaryEntry(id: String) { writableDatabase.delete("glossary", "id=?", arrayOf(id)) }

  fun saveExport(id: String, path: String, name: String, mimeType: String = "application/pdf") {
    writableDatabase.insertWithOnConflict("exports", null, ContentValues().apply {
      put("id", id); put("path", path); put("name", name); put("created_at", System.currentTimeMillis()); put("mime_type", mimeType)
    }, SQLiteDatabase.CONFLICT_REPLACE)
  }

  /** Finalizes an active job and publishes its durable export as one SQLite commit. */
  fun completeActiveJobWithExport(
    jobId: String,
    exportId: String,
    path: String,
    name: String,
    mimeType: String,
    partial: Boolean,
  ): Boolean {
    val db = writableDatabase
    db.beginTransaction()
    try {
      val values = ContentValues().apply {
        put("status", if (partial) "partial" else "completed")
        put("stage", "complete")
        putNull("current_page")
        putNull("error")
        put("updated_at", System.currentTimeMillis())
      }
      if (db.update("jobs", values, "id=? AND status IN ('queued','running')", arrayOf(jobId)) == 0) {
        return false
      }
      val inserted = db.insertWithOnConflict("exports", null, ContentValues().apply {
        put("id", exportId)
        put("path", path)
        put("name", name)
        put("created_at", System.currentTimeMillis())
        put("mime_type", mimeType)
      }, SQLiteDatabase.CONFLICT_REPLACE)
      check(inserted != -1L) { "The translated document export could not be saved." }
      db.setTransactionSuccessful()
      return true
    } finally {
      db.endTransaction()
    }
  }

  fun deleteExport(id: String) { writableDatabase.delete("exports", "id=?", arrayOf(id)) }

  data class ExportInfo(val path: String, val name: String, val mimeType: String)
  fun exportInfo(id: String): ExportInfo? = readQuery(
    "SELECT path,name,mime_type FROM exports WHERE id=?", arrayOf(id),
  ).use { if (it.moveToFirst()) ExportInfo(it.getString(0), it.getString(1), it.getString(2)) else null }
  fun export(id: String): Pair<String, String>? = exportInfo(id)?.let { it.path to it.name }
}

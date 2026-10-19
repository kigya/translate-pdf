package dev.kigya.translatepdf

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

internal class TranslatorDatabase(context: Context) :
  SQLiteOpenHelper(context, "translator-private.db", null, 2) {

  companion object {
    const val PIPELINE_VERSION = 2
  }

  override fun onConfigure(db: SQLiteDatabase) {
    super.onConfigure(db)
    db.setForeignKeyConstraintsEnabled(true)
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    db.execSQL("CREATE TABLE documents (id TEXT PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL, pages INTEGER NOT NULL, source_sha256 TEXT NOT NULL, imported_at INTEGER NOT NULL)")
    db.execSQL("CREATE TABLE models (id TEXT PRIMARY KEY, status TEXT NOT NULL, downloaded_bytes INTEGER NOT NULL DEFAULT 0, error TEXT)")
    db.execSQL("CREATE TABLE jobs (id TEXT PRIMARY KEY, document_id TEXT NOT NULL, model_id TEXT NOT NULL, model_revision TEXT NOT NULL, source_sha256 TEXT NOT NULL, glossary_snapshot TEXT NOT NULL, pipeline_version INTEGER NOT NULL, source_lang TEXT NOT NULL, target_lang TEXT NOT NULL, status TEXT NOT NULL, stage TEXT NOT NULL, completed_pages INTEGER NOT NULL DEFAULT 0, total_pages INTEGER NOT NULL, current_page INTEGER, glossary_id TEXT, error TEXT, observed_block_ms INTEGER NOT NULL DEFAULT 0, observed_block_count INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE)")
    db.execSQL("CREATE INDEX jobs_document_id ON jobs(document_id)")
    db.execSQL("CREATE TABLE pages (job_id TEXT NOT NULL, page_index INTEGER NOT NULL, status TEXT NOT NULL, blocks_json TEXT NOT NULL, width REAL NOT NULL DEFAULT 0, height REAL NOT NULL DEFAULT 0, expected_blocks INTEGER, error TEXT, updated_at INTEGER NOT NULL, PRIMARY KEY(job_id, page_index), FOREIGN KEY(job_id) REFERENCES jobs(id) ON DELETE CASCADE)")
    db.execSQL("CREATE TABLE glossary (id TEXT PRIMARY KEY, source TEXT NOT NULL, target TEXT NOT NULL, created_at INTEGER NOT NULL)")
    db.execSQL("CREATE TABLE exports (id TEXT PRIMARY KEY, path TEXT NOT NULL, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE jobs ADD COLUMN observed_block_ms INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE jobs ADD COLUMN observed_block_count INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE pages ADD COLUMN expected_blocks INTEGER")
    }
  }

  fun setting(key: String, fallback: String): String = readableDatabase.rawQuery(
    "SELECT value FROM settings WHERE key=?", arrayOf(key),
  ).use { if (it.moveToFirst()) it.getString(0) else fallback }

  fun setSetting(key: String, value: String) {
    writableDatabase.insertWithOnConflict("settings", null, ContentValues().apply {
      put("key", key); put("value", value)
    }, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun upsertDocument(id: String, name: String, path: String, pages: Int, sourceSha256: String): JSONObject {
    val importedAt = System.currentTimeMillis()
    writableDatabase.insertOrThrow("documents", null, ContentValues().apply {
      put("id", id); put("name", name); put("path", path); put("pages", pages); put("source_sha256", sourceSha256); put("imported_at", importedAt)
    })
    return JSONObject().put("id", id).put("name", name).put("pageCount", pages).put("importedAt", importedAt)
  }

  fun documentPath(id: String): String? = readableDatabase.rawQuery(
    "SELECT path FROM documents WHERE id=?", arrayOf(id),
  ).use { if (it.moveToFirst()) it.getString(0) else null }

  fun documentsJson(): JSONArray = readableDatabase.rawQuery(
    "SELECT id,name,pages,imported_at FROM documents ORDER BY imported_at DESC", null,
  ).use { cursor -> JSONArray().also { out ->
    while (cursor.moveToNext()) out.put(JSONObject()
      .put("id", cursor.getString(0)).put("name", cursor.getString(1))
      .put("pageCount", cursor.getInt(2)).put("importedAt", cursor.getLong(3)))
  } }

  fun upsertModel(id: String, status: String, downloadedBytes: Long, error: String? = null) {
    writableDatabase.insertWithOnConflict("models", null, ContentValues().apply {
      put("id", id); put("status", status); put("downloaded_bytes", downloadedBytes); put("error", error)
    }, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun modelState(id: String): Triple<String, Long, String?>? = readableDatabase.rawQuery(
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
    val sourceSha = readableDatabase.rawQuery("SELECT source_sha256 FROM documents WHERE id=?", arrayOf(documentId)).use {
      if (it.moveToFirst()) it.getString(0) else throw IllegalArgumentException("The source document was not found.")
    }
    val modelRevision = ModelCatalog.get(input.getString("modelId")).revision
    val glossaryId = input.optString("glossaryId").takeIf(String::isNotBlank)
    val glossarySnapshot = glossaryText(glossaryId)
    writableDatabase.insertOrThrow("jobs", null, ContentValues().apply {
      put("id", id); put("document_id", documentId); put("model_id", input.getString("modelId"))
      put("model_revision", modelRevision); put("source_sha256", sourceSha); put("glossary_snapshot", glossarySnapshot)
      put("pipeline_version", PIPELINE_VERSION)
      put("source_lang", input.getString("sourceLanguage")); put("target_lang", input.getString("targetLanguage"))
      put("status", "queued"); put("stage", "queued"); put("completed_pages", 0); put("total_pages", pages)
      putNull("current_page"); put("glossary_id", glossaryId)
      putNull("error"); put("observed_block_ms", 0); put("observed_block_count", 0)
      put("created_at", now); put("updated_at", now)
    })
    return jobJson(id) ?: error("New job could not be read.")
  }

  fun jobJson(id: String): JSONObject? = readableDatabase.rawQuery(
    "SELECT j.id,j.document_id,d.name,j.model_id,j.source_lang,j.target_lang,j.status,j.stage,j.completed_pages,j.total_pages,j.current_page,j.error,j.created_at,j.updated_at FROM jobs j JOIN documents d ON d.id=j.document_id WHERE j.id=?",
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
      val stats = blockProgress(id)
      job.put("completedBlocks", stats.first).put("discoveredBlocks", stats.second)
      val knownTotal = knownBlockTotal(id, c.getInt(9))
      job.put("totalBlocks", knownTotal ?: JSONObject.NULL)
      val timing = readableDatabase.rawQuery("SELECT observed_block_ms,observed_block_count,status,stage FROM jobs WHERE id=?", arrayOf(id)).use { row ->
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

  private fun blockProgress(jobId: String): Pair<Int, Int> = readableDatabase.rawQuery(
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

  private fun knownBlockTotal(jobId: String, totalPages: Int): Int? = readableDatabase.rawQuery(
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

  fun jobsJson(): JSONArray = readableDatabase.rawQuery("SELECT id FROM jobs ORDER BY created_at DESC", null).use { c ->
    JSONArray().also { out -> while (c.moveToNext()) jobJson(c.getString(0))?.let(out::put) }
  }

  fun jobDocumentPath(id: String): String? = readableDatabase.rawQuery(
    "SELECT d.path FROM jobs j JOIN documents d ON d.id=j.document_id WHERE j.id=?", arrayOf(id),
  ).use { if (it.moveToFirst()) it.getString(0) else null }

  fun requireCurrentPipeline(id: String) {
    val compatible = readableDatabase.rawQuery("SELECT pipeline_version FROM jobs WHERE id=?", arrayOf(id)).use {
      it.moveToFirst() && it.getInt(0) == PIPELINE_VERSION
    }
    check(compatible) { "This job uses an older processing pipeline. Start a new job; its saved pages remain available for review." }
  }

  fun hasActiveJob(exceptId: String? = null): Boolean {
    val sql = if (exceptId == null) "SELECT 1 FROM jobs WHERE status IN ('running','queued') LIMIT 1"
      else "SELECT 1 FROM jobs WHERE status IN ('running','queued') AND id<>? LIMIT 1"
    val args = if (exceptId == null) null else arrayOf(exceptId)
    return readableDatabase.rawQuery(sql, args).use { it.moveToFirst() }
  }

  fun modelInUse(modelId: String): Boolean = readableDatabase.rawQuery(
    "SELECT 1 FROM jobs WHERE model_id=? AND status IN ('running','queued') LIMIT 1", arrayOf(modelId),
  ).use { it.moveToFirst() }

  fun recoverInterruptedJobs(activeId: String?) {
    val interrupted = readableDatabase.rawQuery("SELECT id FROM jobs WHERE status='running'", null).use { cursor ->
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

  fun jobGlossaryId(id: String): String? = readableDatabase.rawQuery(
    "SELECT glossary_id FROM jobs WHERE id=?", arrayOf(id),
  ).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

  fun jobGlossaryText(id: String): String = readableDatabase.rawQuery(
    "SELECT glossary_snapshot FROM jobs WHERE id=?", arrayOf(id),
  ).use { if (it.moveToFirst()) it.getString(0) else "" }

  fun savePage(jobId: String, pageIndex: Int, status: String, blocksJson: String, width: Double = 0.0, height: Double = 0.0, error: String? = null, expectedBlocks: Int? = null) {
    writableDatabase.insertWithOnConflict("pages", null, ContentValues().apply {
      put("job_id", jobId); put("page_index", pageIndex); put("status", status); put("blocks_json", blocksJson)
      put("width", width); put("height", height); if (expectedBlocks == null) putNull("expected_blocks") else put("expected_blocks", expectedBlocks)
      put("error", error); put("updated_at", System.currentTimeMillis())
    }, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun pageRows(jobId: String): JSONArray = readableDatabase.rawQuery(
    "SELECT page_index,status FROM pages WHERE job_id=? ORDER BY page_index", arrayOf(jobId),
  ).use { c -> JSONArray().also { out -> while (c.moveToNext()) out.put(JSONObject().put("index", c.getInt(0)).put("status", c.getString(1))) } }

  fun completedPageCount(jobId: String): Int = readableDatabase.rawQuery(
    "SELECT COUNT(*) FROM pages WHERE job_id=? AND status IN ('completed','partial')", arrayOf(jobId),
  ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

  fun page(jobId: String, pageIndex: Int): JSONObject? = readableDatabase.rawQuery(
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

  fun documentInfo(id: String): Pair<String, Int>? = readableDatabase.rawQuery(
    "SELECT path,pages FROM documents WHERE id=?", arrayOf(id),
  ).use { if (it.moveToFirst()) it.getString(0) to it.getInt(1) else null }

  fun pageBlockArray(jobId: String, pageIndex: Int): JSONArray = readableDatabase.rawQuery(
    "SELECT blocks_json FROM pages WHERE job_id=? AND page_index=?", arrayOf(jobId, pageIndex.toString()),
  ).use { if (it.moveToFirst()) JSONArray(it.getString(0)) else JSONArray() }

  fun deleteJob(id: String) { writableDatabase.delete("jobs", "id=?", arrayOf(id)) }
  fun deleteDocument(id: String) { writableDatabase.delete("documents", "id=?", arrayOf(id)) }
  fun deleteModel(id: String) { writableDatabase.delete("models", "id=?", arrayOf(id)) }

  fun glossaryJson(): JSONArray = readableDatabase.rawQuery(
    "SELECT id,source,target,created_at FROM glossary ORDER BY source COLLATE NOCASE", null,
  ).use { c -> JSONArray().also { out -> while (c.moveToNext()) out.put(JSONObject()
    .put("id", c.getString(0)).put("source", c.getString(1)).put("target", c.getString(2)).put("createdAt", c.getLong(3))) } }

  fun saveGlossary(id: String, source: String, target: String): JSONObject {
    require(source.isNotBlank() && target.isNotBlank()) { "Both glossary terms are required." }
    val created = System.currentTimeMillis()
    writableDatabase.insertWithOnConflict("glossary", null, ContentValues().apply {
      put("id", id); put("source", source.trim()); put("target", target.trim()); put("created_at", created)
    }, SQLiteDatabase.CONFLICT_REPLACE)
    return JSONObject().put("id", id).put("source", source.trim()).put("target", target.trim()).put("createdAt", created)
  }

  fun glossaryText(id: String?): String = readableDatabase.rawQuery(
    if (id == null) "SELECT source,target FROM glossary ORDER BY source COLLATE NOCASE"
    else "SELECT source,target FROM glossary WHERE id=?",
    if (id == null) null else arrayOf(id),
  ).use { c -> buildString {
    while (c.moveToNext()) {
      if (isNotEmpty()) append('\n')
      append(c.getString(0)).append(" => ").append(c.getString(1))
    }
  } }

  fun deleteGlossaryEntry(id: String) { writableDatabase.delete("glossary", "id=?", arrayOf(id)) }

  fun saveExport(id: String, path: String, name: String) {
    writableDatabase.insertWithOnConflict("exports", null, ContentValues().apply {
      put("id", id); put("path", path); put("name", name); put("created_at", System.currentTimeMillis())
    }, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun deleteExport(id: String) { writableDatabase.delete("exports", "id=?", arrayOf(id)) }

  fun export(id: String): Pair<String, String>? = readableDatabase.rawQuery(
    "SELECT path,name FROM exports WHERE id=?", arrayOf(id),
  ).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
}

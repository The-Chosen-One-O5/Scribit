package com.thechosenone.scribit.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray


data class QueueStats(
    val queued: Int = 0,
    val processing: Int = 0,
    val retrying: Int = 0,
    val nextRetryAt: Long = 0L
) {
    val activeCount: Int get() = queued + processing + retrying
}

class DocumentDatabase(context: Context) : SQLiteOpenHelper(context, "scribit.db", null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_id TEXT NOT NULL UNIQUE,
                original_name TEXT NOT NULL,
                archive_path TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                imported_at INTEGER NOT NULL,
                content_hash TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT 'Other',
                document_type TEXT NOT NULL DEFAULT '',
                organization TEXT NOT NULL DEFAULT '',
                issue_date TEXT NOT NULL DEFAULT '',
                expiry_date TEXT NOT NULL DEFAULT '',
                academic_year TEXT NOT NULL DEFAULT '',
                semester TEXT NOT NULL DEFAULT '',
                identifiers_json TEXT NOT NULL DEFAULT '[]',
                tags_json TEXT NOT NULL DEFAULT '[]',
                summary TEXT NOT NULL DEFAULT '',
                search_terms_json TEXT NOT NULL DEFAULT '[]',
                confidence REAL NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'queued',
                error_message TEXT NOT NULL DEFAULT '',
                metadata_updated_at INTEGER NOT NULL DEFAULT 0,
                retry_at INTEGER NOT NULL DEFAULT 0,
                retry_count INTEGER NOT NULL DEFAULT 0,
                duplicate_warning INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE documents_fts USING fts4(
                doc_id,
                title,
                original_name,
                category,
                document_type,
                organization,
                tags,
                summary,
                search_terms
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_documents_status ON documents(status)")
        db.execSQL("CREATE INDEX idx_documents_category ON documents(category)")
        db.execSQL("CREATE INDEX idx_documents_expiry ON documents(expiry_date)")
        db.execSQL("CREATE INDEX idx_documents_imported ON documents(imported_at DESC)")
        db.execSQL("CREATE INDEX idx_documents_content_hash ON documents(content_hash)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations are intentionally additive. Never drop/recreate the documents table on upgrade:
        // users may have private archive files and metadata that must survive every app update.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE documents ADD COLUMN content_hash TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_documents_content_hash ON documents(content_hash)")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE documents ADD COLUMN metadata_updated_at INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE documents ADD COLUMN retry_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE documents ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
            // Any interrupted legacy 'processing' row is safe to show as queued after upgrading.
            db.execSQL("UPDATE documents SET status='queued' WHERE status='processing'")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE documents ADD COLUMN duplicate_warning INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Synchronized
    fun insertImported(record: DocumentRecord): Long {
        val values = ContentValues().apply {
            put("file_id", record.fileId)
            put("original_name", record.originalName)
            put("archive_path", record.archivePath)
            put("mime_type", record.mimeType)
            put("size_bytes", record.sizeBytes)
            put("imported_at", record.importedAt)
            put("content_hash", record.contentHash)
            put("status", record.status)
            put("retry_at", record.retryAt)
            put("retry_count", record.retryCount)
            put("duplicate_warning", if (record.duplicateWarning) 1 else 0)
        }
        val id = writableDatabase.insertOrThrow("documents", null, values)
        upsertFts(getById(id)!!)
        return id
    }


    @Synchronized
    fun insertRestored(record: DocumentRecord): Long {
        val values = ContentValues().apply {
            put("file_id", record.fileId)
            put("original_name", record.originalName)
            put("archive_path", record.archivePath)
            put("mime_type", record.mimeType)
            put("size_bytes", record.sizeBytes)
            put("imported_at", record.importedAt)
            put("content_hash", record.contentHash)
            put("title", record.title)
            put("category", record.category)
            put("document_type", record.documentType)
            put("organization", record.organization)
            put("issue_date", record.issueDate)
            put("expiry_date", record.expiryDate)
            put("academic_year", record.academicYear)
            put("semester", record.semester)
            put("identifiers_json", record.identifiersJson)
            put("tags_json", record.tagsJson)
            put("summary", record.summary)
            put("search_terms_json", record.searchTermsJson)
            put("confidence", record.confidence)
            put("status", record.status)
            put("error_message", record.errorMessage)
            put("metadata_updated_at", System.currentTimeMillis())
            put("retry_at", record.retryAt)
            put("retry_count", record.retryCount)
            put("duplicate_warning", if (record.duplicateWarning) 1 else 0)
        }
        val id = writableDatabase.insertOrThrow("documents", null, values)
        upsertFts(getById(id)!!)
        return id
    }

    @Synchronized
    fun findDuplicateByHash(contentHash: String): DocumentRecord? {
        if (contentHash.isBlank()) return null

        readableDatabase.query(
            "documents",
            null,
            "content_hash=?",
            arrayOf(contentHash),
            null,
            null,
            "imported_at DESC",
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.toRecord()
        }

        // Existing installs created before duplicate detection won't have hashes yet.
        // Backfill them locally from Scribit's private archive, then check again.
        readableDatabase.query(
            "documents",
            arrayOf("id", "archive_path"),
            "content_hash=''",
            null,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                val path = cursor.getString(cursor.getColumnIndexOrThrow("archive_path"))
                val file = java.io.File(path)
                if (!file.exists() || !file.isFile) continue
                val hash = sha256(file)
                val values = ContentValues().apply { put("content_hash", hash) }
                writableDatabase.update("documents", values, "id=?", arrayOf(id.toString()))
                if (hash == contentHash) return getById(id)
            }
        }
        return null
    }

    /**
     * Marks every byte-for-byte copy in a duplicate group. Names, titles and AI metadata
     * are intentionally ignored; only the exact SHA-256 content hash is used.
     */
    @Synchronized
    fun flagDuplicateGroup(contentHash: String): Int {
        if (contentHash.isBlank()) return 0
        val count = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM documents WHERE content_hash=?",
            arrayOf(contentHash)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        if (count > 1) {
            val values = ContentValues().apply { put("duplicate_warning", 1) }
            writableDatabase.update("documents", values, "content_hash=?", arrayOf(contentHash))
        }
        return count
    }

    @Synchronized
    fun dismissDuplicateWarning(id: Long) {
        val values = ContentValues().apply { put("duplicate_warning", 0) }
        writableDatabase.update("documents", values, "id=?", arrayOf(id.toString()))
    }

    fun getByFileId(fileId: String): DocumentRecord? = readableDatabase.query(
        "documents", null, "file_id=?", arrayOf(fileId), null, null, null
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    @Synchronized
    fun deleteDocument(id: Long): Boolean {
        val record = getById(id) ?: return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("documents_fts", "doc_id=?", arrayOf(id.toString()))
            db.delete("documents", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        runCatching { java.io.File(record.archivePath).delete() }
        clearWarningIfNoLongerDuplicate(record.contentHash)
        return true
    }

    private fun clearWarningIfNoLongerDuplicate(contentHash: String) {
        if (contentHash.isBlank()) return
        val count = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM documents WHERE content_hash=?",
            arrayOf(contentHash)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        if (count <= 1) {
            val values = ContentValues().apply { put("duplicate_warning", 0) }
            writableDatabase.update("documents", values, "content_hash=?", arrayOf(contentHash))
        }
    }

    private fun sha256(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun updateAiMetadata(id: Long, metadata: AiMetadata) {
        val values = ContentValues().apply {
            put("title", metadata.title)
            put("category", metadata.category)
            put("document_type", metadata.documentType)
            put("organization", metadata.organization)
            put("issue_date", metadata.issueDate)
            put("expiry_date", metadata.expiryDate)
            put("academic_year", metadata.academicYear)
            put("semester", metadata.semester)
            put("identifiers_json", metadata.identifiersJson)
            put("tags_json", metadata.tagsJson)
            put("summary", metadata.summary)
            put("search_terms_json", metadata.searchTermsJson)
            put("confidence", metadata.confidence)
            put("status", if (metadata.needsReview) DocumentRecord.STATUS_REVIEW else DocumentRecord.STATUS_READY)
            put("error_message", "")
            put("metadata_updated_at", System.currentTimeMillis())
            put("retry_at", 0L)
            put("retry_count", 0)
        }
        writableDatabase.update("documents", values, "id=?", arrayOf(id.toString()))
        getById(id)?.let(::upsertFts)
    }

    @Synchronized
    fun updateManualMetadata(
        id: Long,
        title: String,
        category: String,
        documentType: String,
        organization: String,
        issueDate: String,
        expiryDate: String,
        tags: String,
        summary: String
    ) {
        val tagsJson = JSONArray(tags.split(',').map { it.trim() }.filter { it.isNotBlank() }).toString()
        val values = ContentValues().apply {
            put("title", title.trim())
            put("category", category.trim().ifBlank { "Other" })
            put("document_type", documentType.trim())
            put("organization", organization.trim())
            put("issue_date", issueDate.trim())
            put("expiry_date", expiryDate.trim())
            put("tags_json", tagsJson)
            put("summary", summary.trim())
            put("status", DocumentRecord.STATUS_READY)
            put("error_message", "")
            put("metadata_updated_at", System.currentTimeMillis())
            put("retry_at", 0L)
            put("retry_count", 0)
        }
        writableDatabase.update("documents", values, "id=?", arrayOf(id.toString()))
        getById(id)?.let(::upsertFts)
    }

    @Synchronized
    fun markQueued(id: Long, message: String = "Waiting for AI analysis") {
        val values = ContentValues().apply {
            put("status", DocumentRecord.STATUS_QUEUED)
            put("error_message", message.take(1000))
            put("retry_at", 0L)
            put("retry_count", 0)
        }
        writableDatabase.update("documents", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun markProcessing(id: Long) {
        val values = ContentValues().apply {
            put("status", DocumentRecord.STATUS_PROCESSING)
            put("error_message", "Analyzing with AI…")
            put("retry_at", 0L)
        }
        writableDatabase.update("documents", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun markRetrying(id: Long, retryAt: Long, retryCount: Int, message: String) {
        val values = ContentValues().apply {
            put("status", DocumentRecord.STATUS_RETRYING)
            put("error_message", message.take(1000))
            put("retry_at", retryAt)
            put("retry_count", retryCount)
        }
        writableDatabase.update("documents", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun markError(id: Long, message: String) {
        val values = ContentValues().apply {
            put("status", DocumentRecord.STATUS_REVIEW)
            put("error_message", message.take(1000))
            put("metadata_updated_at", System.currentTimeMillis())
            put("retry_at", 0L)
        }
        writableDatabase.update("documents", values, "id=?", arrayOf(id.toString()))
    }

    fun queueStats(): QueueStats {
        val counts = mutableMapOf<String, Int>()
        readableDatabase.rawQuery(
            "SELECT status, COUNT(*) FROM documents WHERE status IN (?,?,?) GROUP BY status",
            arrayOf(DocumentRecord.STATUS_QUEUED, DocumentRecord.STATUS_PROCESSING, DocumentRecord.STATUS_RETRYING)
        ).use { cursor ->
            while (cursor.moveToNext()) counts[cursor.getString(0)] = cursor.getInt(1)
        }
        val nextRetry = readableDatabase.rawQuery(
            "SELECT MIN(retry_at) FROM documents WHERE status=? AND retry_at>0",
            arrayOf(DocumentRecord.STATUS_RETRYING)
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L }
        return QueueStats(
            queued = counts[DocumentRecord.STATUS_QUEUED] ?: 0,
            processing = counts[DocumentRecord.STATUS_PROCESSING] ?: 0,
            retrying = counts[DocumentRecord.STATUS_RETRYING] ?: 0,
            nextRetryAt = nextRetry
        )
    }

    fun getById(id: Long): DocumentRecord? = readableDatabase.query(
        "documents", null, "id=?", arrayOf(id.toString()), null, null, null
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    fun listAllForBackup(): List<DocumentRecord> = queryRecords(
        "SELECT * FROM documents ORDER BY imported_at ASC", emptyArray()
    )

    fun listRecent(limit: Int = 100): List<DocumentRecord> = queryRecords(
        "SELECT * FROM documents ORDER BY imported_at DESC LIMIT ?", arrayOf(limit.toString())
    )

    fun listByStatus(status: String, limit: Int = 100): List<DocumentRecord> = queryRecords(
        "SELECT * FROM documents WHERE status=? ORDER BY imported_at DESC LIMIT ?",
        arrayOf(status, limit.toString())
    )

    fun listByCategory(category: String, limit: Int = 100): List<DocumentRecord> = queryRecords(
        "SELECT * FROM documents WHERE category=? ORDER BY imported_at DESC LIMIT ?",
        arrayOf(category, limit.toString())
    )

    fun search(query: String, category: String? = null, limit: Int = 100): List<DocumentRecord> {
        if (query.isBlank()) return if (category.isNullOrBlank()) listRecent(limit) else listByCategory(category, limit)
        val tokens = query.trim().split(Regex("\\s+")).map { it.replace(Regex("[^\\p{L}\\p{N}_-]"), "") }.filter { it.isNotBlank() }
        if (tokens.isEmpty()) return listRecent(limit)
        val match = tokens.joinToString(" AND ") { "${it}*" }
        return runCatching {
            val sql = buildString {
                append("SELECT d.* FROM documents d JOIN documents_fts f ON CAST(f.doc_id AS INTEGER)=d.id WHERE documents_fts MATCH ?")
                if (!category.isNullOrBlank()) append(" AND d.category=?")
                append(" ORDER BY d.imported_at DESC LIMIT ?")
            }
            val args = mutableListOf(match)
            if (!category.isNullOrBlank()) args += category
            args += limit.toString()
            queryRecords(sql, args.toTypedArray())
        }.getOrElse {
            val like = "%${query.trim()}%"
            val sql = buildString {
                append("SELECT * FROM documents WHERE (title LIKE ? OR original_name LIKE ? OR organization LIKE ? OR summary LIKE ?)")
                if (!category.isNullOrBlank()) append(" AND category=?")
                append(" ORDER BY imported_at DESC LIMIT ?")
            }
            val args = mutableListOf(like, like, like, like)
            if (!category.isNullOrBlank()) args += category
            args += limit.toString()
            queryRecords(sql, args.toTypedArray())
        }
    }

    fun expiringWithin(fromIso: String, toIso: String): List<DocumentRecord> = queryRecords(
        """
        SELECT * FROM documents
        WHERE expiry_date <> '' AND expiry_date >= ? AND expiry_date <= ?
        ORDER BY expiry_date ASC
        """.trimIndent(), arrayOf(fromIso, toIso)
    )

    private fun queryRecords(sql: String, args: Array<String>): List<DocumentRecord> {
        val result = mutableListOf<DocumentRecord>()
        readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toRecord()
        }
        return result
    }

    private fun upsertFts(record: DocumentRecord) {
        val db = writableDatabase
        db.delete("documents_fts", "doc_id=?", arrayOf(record.id.toString()))
        val values = ContentValues().apply {
            put("doc_id", record.id.toString())
            put("title", record.title)
            put("original_name", record.originalName)
            put("category", record.category)
            put("document_type", record.documentType)
            put("organization", record.organization)
            put("tags", jsonArrayToText(record.tagsJson))
            put("summary", record.summary)
            put("search_terms", jsonArrayToText(record.searchTermsJson))
        }
        db.insert("documents_fts", null, values)
    }

    private fun jsonArrayToText(json: String): String = runCatching {
        val array = JSONArray(json)
        buildString {
            for (i in 0 until array.length()) append(array.optString(i)).append(' ')
        }.trim()
    }.getOrDefault(json)

    private fun Cursor.toRecord(): DocumentRecord = DocumentRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        fileId = getString(getColumnIndexOrThrow("file_id")),
        originalName = getString(getColumnIndexOrThrow("original_name")),
        archivePath = getString(getColumnIndexOrThrow("archive_path")),
        mimeType = getString(getColumnIndexOrThrow("mime_type")),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        importedAt = getLong(getColumnIndexOrThrow("imported_at")),
        contentHash = getString(getColumnIndexOrThrow("content_hash")),
        title = getString(getColumnIndexOrThrow("title")),
        category = getString(getColumnIndexOrThrow("category")),
        documentType = getString(getColumnIndexOrThrow("document_type")),
        organization = getString(getColumnIndexOrThrow("organization")),
        issueDate = getString(getColumnIndexOrThrow("issue_date")),
        expiryDate = getString(getColumnIndexOrThrow("expiry_date")),
        academicYear = getString(getColumnIndexOrThrow("academic_year")),
        semester = getString(getColumnIndexOrThrow("semester")),
        identifiersJson = getString(getColumnIndexOrThrow("identifiers_json")),
        tagsJson = getString(getColumnIndexOrThrow("tags_json")),
        summary = getString(getColumnIndexOrThrow("summary")),
        searchTermsJson = getString(getColumnIndexOrThrow("search_terms_json")),
        confidence = getDouble(getColumnIndexOrThrow("confidence")),
        status = getString(getColumnIndexOrThrow("status")),
        errorMessage = getString(getColumnIndexOrThrow("error_message")),
        retryAt = getLong(getColumnIndexOrThrow("retry_at")),
        retryCount = getInt(getColumnIndexOrThrow("retry_count")),
        duplicateWarning = getInt(getColumnIndexOrThrow("duplicate_warning")) != 0
    )

    companion object {
        private const val DATABASE_VERSION = 5
    }

}

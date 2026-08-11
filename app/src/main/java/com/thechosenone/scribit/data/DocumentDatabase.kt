package com.thechosenone.scribit.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

class DocumentDatabase(context: Context) : SQLiteOpenHelper(context, "scribit.db", null, 1) {
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
                status TEXT NOT NULL DEFAULT 'processing',
                error_message TEXT NOT NULL DEFAULT ''
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun insertImported(record: DocumentRecord): Long {
        val values = ContentValues().apply {
            put("file_id", record.fileId)
            put("original_name", record.originalName)
            put("archive_path", record.archivePath)
            put("mime_type", record.mimeType)
            put("size_bytes", record.sizeBytes)
            put("imported_at", record.importedAt)
            put("status", record.status)
        }
        val id = writableDatabase.insertOrThrow("documents", null, values)
        upsertFts(getById(id)!!)
        return id
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
        }
        writableDatabase.update("documents", values, "id=?", arrayOf(id.toString()))
        getById(id)?.let(::upsertFts)
    }

    @Synchronized
    fun markError(id: Long, message: String) {
        val values = ContentValues().apply {
            put("status", DocumentRecord.STATUS_REVIEW)
            put("error_message", message.take(1000))
        }
        writableDatabase.update("documents", values, "id=?", arrayOf(id.toString()))
    }

    fun getById(id: Long): DocumentRecord? = readableDatabase.query(
        "documents", null, "id=?", arrayOf(id.toString()), null, null, null
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

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
        errorMessage = getString(getColumnIndexOrThrow("error_message"))
    )
}

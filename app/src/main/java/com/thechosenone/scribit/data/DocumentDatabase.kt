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
                category TEXT NOT NULL DEFAULT '',
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
        createCategoryTables(db)
        seedBuiltInCategories(db)
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
            db.execSQL("UPDATE documents SET status='queued' WHERE status='processing'")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE documents ADD COLUMN duplicate_warning INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 6) {
            createCategoryTables(db)
            seedBuiltInCategories(db)
            // Preserve every old built-in assignment. Legacy "Other" becomes uncategorized because
            // v1.4 replaces that catch-all tab with user-created categories.
            BUILT_IN_CATEGORIES.forEach { category ->
                db.execSQL(
                    "INSERT OR IGNORE INTO document_categories(document_id, category_name) " +
                        "SELECT id, ? FROM documents WHERE category = ?",
                    arrayOf(category, category)
                )
            }
        }
        if (oldVersion < 7) {
            // Clean up any stale legacy catch-all category that may have survived an earlier build.
            db.delete("document_categories", "category_name=? COLLATE NOCASE", arrayOf("Other"))
            db.delete("categories", "name=? COLLATE NOCASE", arrayOf("Other"))
            db.execSQL("UPDATE documents SET category='' WHERE category='Other' COLLATE NOCASE")
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
        if (record.categories.isNotEmpty()) replaceDocumentCategories(id, record.categories)
        upsertFts(getById(id)!!)
        return id
    }

    @Synchronized
    fun insertRestored(record: DocumentRecord): Long {
        val legacyCategory = record.categories.firstOrNull().orEmpty().ifBlank { record.category }
        val values = ContentValues().apply {
            put("file_id", record.fileId)
            put("original_name", record.originalName)
            put("archive_path", record.archivePath)
            put("mime_type", record.mimeType)
            put("size_bytes", record.sizeBytes)
            put("imported_at", record.importedAt)
            put("content_hash", record.contentHash)
            put("title", record.title)
            put("category", legacyCategory)
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
        val restoredCategories = record.categories.ifEmpty {
            listOfNotNull(record.category.takeIf { it in BUILT_IN_CATEGORIES })
        }
        if (restoredCategories.isNotEmpty()) replaceDocumentCategories(id, restoredCategories)
        upsertFts(getById(id)!!)
        return id
    }

    @Synchronized
    fun findDuplicateByHash(contentHash: String): DocumentRecord? {
        if (contentHash.isBlank()) return null

        readableDatabase.query(
            "documents", null, "content_hash=?", arrayOf(contentHash), null, null, "imported_at DESC", "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) return attachCategories(cursor.toRecordRaw())
        }

        readableDatabase.query(
            "documents", arrayOf("id", "archive_path"), "content_hash=''", null, null, null, null
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

    @Synchronized
    fun flagDuplicateGroup(contentHash: String): Int {
        if (contentHash.isBlank()) return 0
        val count = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM documents WHERE content_hash=?", arrayOf(contentHash)
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
    ).use { cursor -> if (cursor.moveToFirst()) attachCategories(cursor.toRecordRaw()) else null }

    @Synchronized
    fun deleteDocument(id: Long): Boolean {
        val record = getById(id) ?: return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("document_categories", "document_id=?", arrayOf(id.toString()))
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
            "SELECT COUNT(*) FROM documents WHERE content_hash=?", arrayOf(contentHash)
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
        val safeCategories = sanitizeCategories(metadata.categories)
        val values = ContentValues().apply {
            put("title", metadata.title)
            put("category", safeCategories.firstOrNull().orEmpty())
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
        replaceDocumentCategories(id, safeCategories)
        getById(id)?.let(::upsertFts)
    }

    @Synchronized
    fun updateManualMetadata(
        id: Long,
        title: String,
        categories: List<String>,
        documentType: String,
        organization: String,
        issueDate: String,
        expiryDate: String,
        tags: String,
        summary: String
    ) {
        val safeCategories = sanitizeCategories(categories)
        val tagsJson = JSONArray(tags.split(',').map { it.trim() }.filter { it.isNotBlank() }).toString()
        val values = ContentValues().apply {
            put("title", title.trim())
            put("category", safeCategories.firstOrNull().orEmpty())
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
        replaceDocumentCategories(id, safeCategories)
        getById(id)?.let(::upsertFts)
    }

    @Synchronized
    fun addCategory(name: String): String {
        val safe = normalizeCategoryName(name)
        require(safe.isNotBlank()) { "Category name cannot be empty." }
        require(safe.length <= 32) { "Keep category names to 32 characters or fewer." }
        require(safe.lowercase() !in SPECIAL_FILTER_NAMES) { "That name is reserved by Scribit." }
        val values = ContentValues().apply {
            put("name", safe)
            put("is_builtin", if (safe in BUILT_IN_CATEGORIES) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("categories", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return listCategories().firstOrNull { it.equals(safe, ignoreCase = true) } ?: safe
    }

    fun listCategories(): List<String> {
        val custom = mutableListOf<String>()
        readableDatabase.query(
            "categories", arrayOf("name"), "is_builtin=0", null, null, null, "created_at ASC, name COLLATE NOCASE ASC"
        ).use { cursor -> while (cursor.moveToNext()) custom += cursor.getString(0) }
        return BUILT_IN_CATEGORIES + custom.filterNot { candidate ->
            candidate.equals("Other", ignoreCase = true) ||
                BUILT_IN_CATEGORIES.any { it.equals(candidate, true) }
        }
    }

    fun isBuiltInCategory(name: String): Boolean = BUILT_IN_CATEGORIES.any { it.equals(name, ignoreCase = true) }

    @Synchronized
    fun deleteCustomCategory(name: String): Boolean {
        val actual = listCategories().firstOrNull { it.equals(name, ignoreCase = true) } ?: return false
        if (isBuiltInCategory(actual)) return false
        val db = writableDatabase
        var deleted = 0
        db.beginTransaction()
        try {
            db.delete("document_categories", "category_name=? COLLATE NOCASE", arrayOf(actual))
            deleted = db.delete("categories", "name=? COLLATE NOCASE AND is_builtin=0", arrayOf(actual))
            // Refresh legacy primary-category text for affected documents.
            db.rawQuery("SELECT id FROM documents", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val categories = getCategoriesForDocument(id)
                    val values = ContentValues().apply { put("category", categories.firstOrNull().orEmpty()) }
                    db.update("documents", values, "id=?", arrayOf(id.toString()))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (deleted > 0) rebuildAllFts()
        return deleted > 0
    }

    @Synchronized
    fun setDocumentCategories(id: Long, categories: List<String>) {
        val safeCategories = sanitizeCategories(categories)
        replaceDocumentCategories(id, safeCategories)
        val values = ContentValues().apply { put("category", safeCategories.firstOrNull().orEmpty()) }
        writableDatabase.update("documents", values, "id=?", arrayOf(id.toString()))
        getById(id)?.let(::upsertFts)
    }

    private fun sanitizeCategories(categories: List<String>): List<String> {
        val available = listCategories()
        return categories.mapNotNull { raw ->
            available.firstOrNull { it.equals(raw.trim(), ignoreCase = true) }
        }.distinctBy { it.lowercase() }
    }

    private fun replaceDocumentCategories(id: Long, categories: List<String>) {
        val db = writableDatabase
        db.delete("document_categories", "document_id=?", arrayOf(id.toString()))
        categories.forEach { category ->
            val values = ContentValues().apply {
                put("document_id", id)
                put("category_name", category)
            }
            db.insertWithOnConflict("document_categories", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    private fun getCategoriesForDocument(id: Long): List<String> {
        val result = mutableListOf<String>()
        readableDatabase.rawQuery(
            """
            SELECT dc.category_name
            FROM document_categories dc
            LEFT JOIN categories c ON c.name = dc.category_name COLLATE NOCASE
            WHERE dc.document_id=?
            ORDER BY CASE dc.category_name
                WHEN 'Identity' THEN 1 WHEN 'Education' THEN 2 WHEN 'Career' THEN 3
                WHEN 'Finance' THEN 4 WHEN 'Permits' THEN 5 ELSE 100 END,
                COALESCE(c.created_at, 0), dc.category_name COLLATE NOCASE
            """.trimIndent(),
            arrayOf(id.toString())
        ).use { cursor -> while (cursor.moveToNext()) result += cursor.getString(0) }
        return result
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
        ).use { cursor -> while (cursor.moveToNext()) counts[cursor.getString(0)] = cursor.getInt(1) }
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
    ).use { cursor -> if (cursor.moveToFirst()) attachCategories(cursor.toRecordRaw()) else null }

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
        """
        SELECT d.* FROM documents d
        WHERE EXISTS (
            SELECT 1 FROM document_categories dc
            WHERE dc.document_id=d.id AND dc.category_name=? COLLATE NOCASE
        )
        ORDER BY d.imported_at DESC LIMIT ?
        """.trimIndent(), arrayOf(category, limit.toString())
    )

    fun search(query: String, category: String? = null, limit: Int = 100): List<DocumentRecord> {
        if (query.isBlank()) return if (category.isNullOrBlank()) listRecent(limit) else listByCategory(category, limit)
        val tokens = query.trim().split(Regex("\\s+")).map {
            it.replace(Regex("[^\\p{L}\\p{N}_-]"), "")
        }.filter { it.isNotBlank() }
        if (tokens.isEmpty()) return if (category.isNullOrBlank()) listRecent(limit) else listByCategory(category, limit)
        val match = tokens.joinToString(" AND ") { "${it}*" }
        return runCatching {
            val sql = buildString {
                append("SELECT d.* FROM documents d JOIN documents_fts f ON CAST(f.doc_id AS INTEGER)=d.id WHERE documents_fts MATCH ?")
                if (!category.isNullOrBlank()) {
                    append(" AND EXISTS (SELECT 1 FROM document_categories dc WHERE dc.document_id=d.id AND dc.category_name=? COLLATE NOCASE)")
                }
                append(" ORDER BY d.imported_at DESC LIMIT ?")
            }
            val args = mutableListOf(match)
            if (!category.isNullOrBlank()) args += category
            args += limit.toString()
            queryRecords(sql, args.toTypedArray())
        }.getOrElse {
            val like = "%${query.trim()}%"
            val sql = buildString {
                append("SELECT * FROM documents d WHERE (title LIKE ? OR original_name LIKE ? OR organization LIKE ? OR summary LIKE ?)")
                if (!category.isNullOrBlank()) {
                    append(" AND EXISTS (SELECT 1 FROM document_categories dc WHERE dc.document_id=d.id AND dc.category_name=? COLLATE NOCASE)")
                }
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
        val raw = mutableListOf<DocumentRecord>()
        readableDatabase.rawQuery(sql, args).use { cursor -> while (cursor.moveToNext()) raw += cursor.toRecordRaw() }
        return attachCategories(raw)
    }

    private fun attachCategories(record: DocumentRecord): DocumentRecord {
        val categories = getCategoriesForDocument(record.id)
        return record.copy(categories = categories, category = categories.firstOrNull().orEmpty())
    }

    private fun attachCategories(records: List<DocumentRecord>): List<DocumentRecord> {
        if (records.isEmpty()) return records
        val ids = records.map { it.id }
        val placeholders = ids.joinToString(",") { "?" }
        val map = linkedMapOf<Long, MutableList<String>>()
        readableDatabase.rawQuery(
            """
            SELECT dc.document_id, dc.category_name
            FROM document_categories dc
            LEFT JOIN categories c ON c.name = dc.category_name COLLATE NOCASE
            WHERE dc.document_id IN ($placeholders)
            ORDER BY CASE dc.category_name
                WHEN 'Identity' THEN 1 WHEN 'Education' THEN 2 WHEN 'Career' THEN 3
                WHEN 'Finance' THEN 4 WHEN 'Permits' THEN 5 ELSE 100 END,
                COALESCE(c.created_at, 0), dc.category_name COLLATE NOCASE
            """.trimIndent(), ids.map { it.toString() }.toTypedArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                map.getOrPut(cursor.getLong(0)) { mutableListOf() } += cursor.getString(1)
            }
        }
        return records.map { record ->
            val categories = map[record.id].orEmpty()
            record.copy(categories = categories, category = categories.firstOrNull().orEmpty())
        }
    }

    private fun upsertFts(record: DocumentRecord) {
        val db = writableDatabase
        db.delete("documents_fts", "doc_id=?", arrayOf(record.id.toString()))
        val values = ContentValues().apply {
            put("doc_id", record.id.toString())
            put("title", record.title)
            put("original_name", record.originalName)
            put("category", record.categories.joinToString(" "))
            put("document_type", record.documentType)
            put("organization", record.organization)
            put("tags", jsonArrayToText(record.tagsJson))
            put("summary", record.summary)
            put("search_terms", jsonArrayToText(record.searchTermsJson))
        }
        db.insert("documents_fts", null, values)
    }

    private fun rebuildAllFts() {
        writableDatabase.delete("documents_fts", null, null)
        listRecent(Int.MAX_VALUE).forEach(::upsertFts)
    }

    private fun jsonArrayToText(json: String): String = runCatching {
        val array = JSONArray(json)
        buildString { for (i in 0 until array.length()) append(array.optString(i)).append(' ') }.trim()
    }.getOrDefault(json)

    private fun Cursor.toRecordRaw(): DocumentRecord = DocumentRecord(
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

    private fun createCategoryTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS categories (
                name TEXT PRIMARY KEY COLLATE NOCASE,
                is_builtin INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS document_categories (
                document_id INTEGER NOT NULL,
                category_name TEXT NOT NULL COLLATE NOCASE,
                PRIMARY KEY(document_id, category_name)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_document_categories_name ON document_categories(category_name COLLATE NOCASE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_document_categories_document ON document_categories(document_id)")
    }

    private fun seedBuiltInCategories(db: SQLiteDatabase) {
        BUILT_IN_CATEGORIES.forEachIndexed { index, name ->
            val values = ContentValues().apply {
                put("name", name)
                put("is_builtin", 1)
                put("created_at", index.toLong())
            }
            db.insertWithOnConflict("categories", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    private fun normalizeCategoryName(name: String): String = name.trim().replace(Regex("\\s+"), " ")

    companion object {
        val BUILT_IN_CATEGORIES = listOf("Identity", "Education", "Career", "Finance", "Permits")
        private val SPECIAL_FILTER_NAMES = setOf("all", "needs review", "needs_review", "add more", "uncategorized", "other")
        private const val DATABASE_VERSION = 7
    }
}

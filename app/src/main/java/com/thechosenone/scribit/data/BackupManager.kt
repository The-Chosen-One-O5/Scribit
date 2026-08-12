package com.thechosenone.scribit.data

import android.content.Context
import android.net.Uri
import com.thechosenone.scribit.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class BackupExportResult(
    val documentCount: Int,
    val missingArchiveCount: Int
)

data class BackupRestoreResult(
    val restoredCount: Int,
    val duplicateCount: Int,
    val missingFileCount: Int,
    val settingsRestored: Boolean
)

class BackupManager(context: Context) {
    private val appContext = context.applicationContext
    private val database = DocumentDatabase(appContext)
    private val settingsRepository = SettingsRepository(appContext)

    fun exportTo(destination: Uri): BackupExportResult {
        val cacheFile = File.createTempFile("scribit-backup-", ".zip", appContext.cacheDir)
        var missing = 0
        val manifestDocuments = JSONArray()

        try {
            ZipOutputStream(FileOutputStream(cacheFile)).use { zip ->
                database.listAllForBackup().forEach { document ->
                    val archiveFile = File(document.archivePath)
                    if (!archiveFile.exists() || !archiveFile.isFile) {
                        missing++
                        return@forEach
                    }

                    val extension = document.originalName.substringAfterLast('.', "")
                        .takeIf { it.length in 1..10 }
                        ?.let { ".$it" }
                        .orEmpty()
                    val archiveEntry = "documents/${document.fileId}$extension"

                    zip.putNextEntry(ZipEntry(archiveEntry))
                    archiveFile.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()

                    manifestDocuments.put(documentToJson(document, archiveEntry))
                }

                val settings = settingsRepository.get()
                val manifest = JSONObject()
                    .put("backup_format", BACKUP_FORMAT)
                    .put("backup_version", BACKUP_VERSION)
                    .put("created_at", System.currentTimeMillis())
                    .put("app_package", BuildConfig.APPLICATION_ID)
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("api_key_included", false)
                    .put(
                        "settings",
                        JSONObject()
                            .put("api_base_url", settings.apiBaseUrl)
                            .put("model", settings.model)
                            .put("supports_vision", settings.supportsVision)
                            .put("theme_mode", settings.themeMode)
                            .put("library_layout", settings.libraryLayout)
                    )
                    .put("documents", manifestDocuments)

                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            appContext.contentResolver.openOutputStream(destination, "w")?.use { output ->
                cacheFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Could not write the selected backup file.")

            return BackupExportResult(
                documentCount = manifestDocuments.length(),
                missingArchiveCount = missing
            )
        } finally {
            cacheFile.delete()
        }
    }

    fun restoreFrom(source: Uri): BackupRestoreResult {
        val cacheFile = File.createTempFile("scribit-restore-", ".zip", appContext.cacheDir)
        try {
            appContext.contentResolver.openInputStream(source)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Could not read the selected Scribit backup.")

            ZipFile(cacheFile).use { zip ->
                val manifestEntry = zip.getEntry(MANIFEST_ENTRY)
                    ?: error("This ZIP is not a Scribit backup: manifest.json is missing.")
                val manifest = zip.getInputStream(manifestEntry).bufferedReader().use { reader ->
                    JSONObject(reader.readText())
                }

                require(manifest.optString("backup_format") == BACKUP_FORMAT) {
                    "This ZIP is not a Scribit backup."
                }
                val backupVersion = manifest.optInt("backup_version", 0)
                require(backupVersion in 1..BACKUP_VERSION) {
                    "This backup was created by a newer Scribit backup format. Update Scribit before restoring it."
                }

                val settingsRestored = manifest.optJSONObject("settings")?.let { settings ->
                    settingsRepository.restoreNonSecret(
                        apiBaseUrl = settings.optString("api_base_url"),
                        model = settings.optString("model"),
                        supportsVision = settings.optBoolean("supports_vision", true),
                        themeMode = settings.optString("theme_mode", AppSettings.THEME_SYSTEM),
                        libraryLayout = settings.optString("library_layout", AppSettings.LAYOUT_LIST)
                    )
                    true
                } ?: false

                val archiveDir = File(appContext.filesDir, "archive").apply { mkdirs() }
                val docs = manifest.optJSONArray("documents") ?: JSONArray()
                val backupFileIds = mutableSetOf<String>()
                for (i in 0 until docs.length()) {
                    val fileId = docs.optJSONObject(i)?.optString("file_id").orEmpty()
                    if (fileId.isNotBlank()) backupFileIds += fileId
                }
                var restored = 0
                var duplicates = 0
                var missing = 0

                for (index in 0 until docs.length()) {
                    val json = docs.optJSONObject(index) ?: continue
                    val archiveEntryName = json.optString("archive_entry")
                    val zipEntry = zip.getEntry(archiveEntryName)
                    if (archiveEntryName.isBlank() || zipEntry == null || zipEntry.isDirectory) {
                        missing++
                        continue
                    }

                    val temporary = File.createTempFile("scribit-restore-doc-", ".tmp", appContext.cacheDir)
                    try {
                        zip.getInputStream(zipEntry).use { input ->
                            temporary.outputStream().use { output -> input.copyTo(output) }
                        }
                        val hash = sha256(temporary)
                        val backedUpFileId = json.optString("file_id").trim()
                        if (backedUpFileId.isNotBlank() && database.getByFileId(backedUpFileId) != null) {
                            // Restoring the same backup twice should not clone the exact same archive record.
                            duplicates++
                            continue
                        }

                        val existingByHash = database.findDuplicateByHash(hash)
                        val collisionOutsideThisBackup = existingByHash != null && existingByHash.fileId !in backupFileIds

                        val originalName = json.optString("original_name").ifBlank { "restored-document" }
                        val extension = originalName.substringAfterLast('.', "")
                            .takeIf { it.length in 1..10 }
                            ?.let { ".$it" }
                            .orEmpty()
                        val newFileId = backedUpFileId.ifBlank { UUID.randomUUID().toString() }
                        val destination = File(archiveDir, "$newFileId$extension")
                        temporary.copyTo(destination, overwrite = false)

                        val storedStatus = json.optString("status", DocumentRecord.STATUS_READY)
                        val safeStatus = when (storedStatus) {
                            DocumentRecord.STATUS_READY,
                            DocumentRecord.STATUS_REVIEW,
                            DocumentRecord.STATUS_ERROR -> storedStatus
                            else -> DocumentRecord.STATUS_REVIEW
                        }
                        val restoredError = if (storedStatus == DocumentRecord.STATUS_PROCESSING) {
                            "This document was still processing when the backup was made. Review it or run Reclassify."
                        } else {
                            json.optString("error_message")
                        }

                        try {
                            database.insertRestored(
                                DocumentRecord(
                                    fileId = newFileId,
                                    originalName = originalName,
                                    archivePath = destination.absolutePath,
                                    mimeType = json.optString("mime_type", DocumentImporter.guessMime(originalName)),
                                    sizeBytes = destination.length(),
                                    importedAt = json.optLong("imported_at", System.currentTimeMillis()),
                                    contentHash = hash,
                                    title = json.optString("title"),
                                    category = json.optString("category", "Other").ifBlank { "Other" },
                                    documentType = json.optString("document_type"),
                                    organization = json.optString("organization"),
                                    issueDate = json.optString("issue_date"),
                                    expiryDate = json.optString("expiry_date"),
                                    academicYear = json.optString("academic_year"),
                                    semester = json.optString("semester"),
                                    identifiersJson = validJsonArray(json.optString("identifiers_json", "[]")),
                                    tagsJson = validJsonArray(json.optString("tags_json", "[]")),
                                    summary = json.optString("summary"),
                                    searchTermsJson = validJsonArray(json.optString("search_terms_json", "[]")),
                                    confidence = json.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
                                    status = safeStatus,
                                    errorMessage = restoredError,
                                    duplicateWarning = json.optBoolean("duplicate_warning", false) || collisionOutsideThisBackup
                                )
                            )
                            if (collisionOutsideThisBackup) database.flagDuplicateGroup(hash)
                            restored++
                        } catch (t: Throwable) {
                            destination.delete()
                            throw t
                        }
                    } finally {
                        temporary.delete()
                    }
                }

                return BackupRestoreResult(
                    restoredCount = restored,
                    duplicateCount = duplicates,
                    missingFileCount = missing,
                    settingsRestored = settingsRestored
                )
            }
        } finally {
            cacheFile.delete()
        }
    }

    private fun documentToJson(document: DocumentRecord, archiveEntry: String): JSONObject = JSONObject()
        .put("archive_entry", archiveEntry)
        .put("file_id", document.fileId)
        .put("original_name", document.originalName)
        .put("mime_type", document.mimeType)
        .put("size_bytes", document.sizeBytes)
        .put("imported_at", document.importedAt)
        .put("content_hash", document.contentHash)
        .put("title", document.title)
        .put("category", document.category)
        .put("document_type", document.documentType)
        .put("organization", document.organization)
        .put("issue_date", document.issueDate)
        .put("expiry_date", document.expiryDate)
        .put("academic_year", document.academicYear)
        .put("semester", document.semester)
        .put("identifiers_json", document.identifiersJson)
        .put("tags_json", document.tagsJson)
        .put("summary", document.summary)
        .put("search_terms_json", document.searchTermsJson)
        .put("confidence", document.confidence)
        .put("status", document.status)
        .put("error_message", document.errorMessage)
        .put("duplicate_warning", document.duplicateWarning)

    private fun validJsonArray(value: String): String = runCatching {
        JSONArray(value).toString()
    }.getOrDefault("[]")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
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

    companion object {
        const val BACKUP_VERSION = 2
        private const val BACKUP_FORMAT = "scribit-backup"
        private const val MANIFEST_ENTRY = "manifest.json"
    }
}

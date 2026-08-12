package com.thechosenone.scribit.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thechosenone.scribit.ai.AiClassifier
import com.thechosenone.scribit.data.AppSettings
import com.thechosenone.scribit.data.BackupManager
import com.thechosenone.scribit.data.DocumentDatabase
import com.thechosenone.scribit.data.DocumentImporter
import com.thechosenone.scribit.data.DocumentRecord
import com.thechosenone.scribit.data.SettingsRepository
import com.thechosenone.scribit.data.QueueStats
import com.thechosenone.scribit.worker.DocumentProcessingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

class ScribitViewModel(application: Application) : AndroidViewModel(application) {
    private val db = DocumentDatabase(application)
    private val settingsRepo = SettingsRepository(application)
    private val importer = DocumentImporter(application)
    private val classifier = AiClassifier(application)
    private val backupManager = BackupManager(application)

    var settings = androidx.compose.runtime.mutableStateOf(settingsRepo.get())
        private set
    var documents = androidx.compose.runtime.mutableStateOf<List<DocumentRecord>>(emptyList())
        private set
    var selectedDocument = androidx.compose.runtime.mutableStateOf<DocumentRecord?>(null)
        private set
    var searchQuery = androidx.compose.runtime.mutableStateOf("")
    var categoryFilter = androidx.compose.runtime.mutableStateOf<String?>(null)
    var reviewOnly = androidx.compose.runtime.mutableStateOf(false)
    var busy = androidx.compose.runtime.mutableStateOf(false)
        private set
    var message = androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var queueStats = androidx.compose.runtime.mutableStateOf(QueueStats())
        private set

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val query = searchQuery.value.trim()
            val category = categoryFilter.value
            val rows = when {
                reviewOnly.value -> db.listByStatus(DocumentRecord.STATUS_REVIEW)
                query.isNotBlank() -> db.search(query, category)
                !category.isNullOrBlank() -> db.listByCategory(category)
                else -> db.listRecent()
            }
            val refreshedSelected = selectedDocument.value?.let { db.getById(it.id) }
            val stats = db.queueStats()
            withContext(Dispatchers.Main) {
                documents.value = rows
                selectedDocument.value = refreshedSelected
                queueStats.value = stats
            }
        }
    }

    fun applySearch() = refresh()

    fun setCategory(category: String?) {
        categoryFilter.value = category
        reviewOnly.value = false
        refresh()
    }

    fun setReviewOnly(enabled: Boolean) {
        reviewOnly.value = enabled
        if (enabled) categoryFilter.value = null
        refresh()
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy.value = true
            val results = withContext(Dispatchers.IO) {
                uris.map { uri -> runCatching { importer.importUri(uri, enqueueAi = false) } }
            }
            val importedIds = results.mapNotNull { it.getOrNull() }
            if (importedIds.isNotEmpty()) {
                DocumentProcessingQueue.enqueueBatch(getApplication(), importedIds)
            }
            busy.value = false
            val errors = results.mapNotNull { it.exceptionOrNull() }
            val importedCount = importedIds.size
            val duplicateCount = withContext(Dispatchers.IO) {
                importedIds.count { id -> db.getById(id)?.duplicateWarning == true }
            }
            when {
                errors.isNotEmpty() -> message.value = errors.first().message ?: "Could not import document."
                importedCount > 0 && duplicateCount > 0 -> message.value =
                    "Imported $importedCount document${if (importedCount == 1) "" else "s"}. " +
                        "$duplicateCount exact duplicate${if (duplicateCount == 1) " is" else "s are"} marked in red."
                importedCount > 0 -> message.value = "Imported $importedCount document${if (importedCount == 1) "" else "s"}."
            }
            refresh()
        }
    }

    fun importCameraFile(file: File) {
        viewModelScope.launch {
            busy.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val id = importer.importFile(file, "Scan-${System.currentTimeMillis()}.jpg", "image/jpeg")
                    id to (db.getById(id)?.duplicateWarning == true)
                }
            }
            busy.value = false
            message.value = result.fold(
                onSuccess = { (_, duplicate) ->
                    if (duplicate) "Scan imported. Exact duplicate marked in red." else "Scan imported."
                },
                onFailure = { it.message ?: "Could not import scan." }
            )
            refresh()
        }
    }

    fun select(document: DocumentRecord?) {
        selectedDocument.value = document
    }

    fun saveSettings(newSettings: AppSettings) {
        settingsRepo.save(newSettings)
        settings.value = settingsRepo.get()
        message.value = "Settings saved securely."
    }

    fun setThemeMode(themeMode: String) {
        val updated = settings.value.copy(themeMode = themeMode)
        settingsRepo.save(updated)
        settings.value = settingsRepo.get()
    }

    fun setLibraryLayout(layout: String) {
        val safeLayout = layout.takeIf {
            it == AppSettings.LAYOUT_LIST || it == AppSettings.LAYOUT_COMPACT || it == AppSettings.LAYOUT_GRID
        } ?: AppSettings.LAYOUT_LIST
        val updated = settings.value.copy(libraryLayout = safeLayout)
        settingsRepo.save(updated)
        settings.value = settingsRepo.get()
    }

    fun testSettings(candidate: AppSettings, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            busy.value = true
            val result = withContext(Dispatchers.IO) { classifier.testConnection(candidate) }
            busy.value = false
            result.fold(
                onSuccess = { onResult(true, "Connected successfully.") },
                onFailure = { onResult(false, it.message ?: "Connection failed.") }
            )
        }
    }

    fun retry(documentId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            db.markQueued(documentId, "Queued for another AI attempt")
            DocumentProcessingQueue.enqueue(getApplication(), documentId)
            withContext(Dispatchers.Main) {
                message.value = "Classification queued. Scribit will retry automatically if the provider throttles it."
                refresh()
            }
        }
    }

    fun saveManual(
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
        viewModelScope.launch(Dispatchers.IO) {
            db.updateManualMetadata(id, title, category, documentType, organization, issueDate, expiryDate, tags, summary)
            val refreshed = db.getById(id)
            withContext(Dispatchers.Main) {
                selectedDocument.value = refreshed
                message.value = "Metadata saved."
                refresh()
            }
        }
    }

    fun deleteDocument(document: DocumentRecord) {
        viewModelScope.launch {
            // Do not cancel an item inside the serial WorkManager chain: cancelling a prerequisite can
            // cancel children behind it. Deleting the DB row makes its queued worker a harmless no-op.
            val deleted = withContext(Dispatchers.IO) { db.deleteDocument(document.id) }
            if (deleted) {
                if (selectedDocument.value?.id == document.id) selectedDocument.value = null
                message.value = "Document deleted from Scribit."
                refresh()
            } else {
                message.value = "Could not delete document."
            }
        }
    }

    fun keepDuplicate(documentId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            db.dismissDuplicateWarning(documentId)
            val refreshed = db.getById(documentId)
            withContext(Dispatchers.Main) {
                selectedDocument.value = refreshed
                message.value = "Kept this copy. Duplicate warning removed."
                refresh()
            }
        }
    }

    fun exportBackup(destination: Uri) {
        viewModelScope.launch {
            busy.value = true
            val result = withContext(Dispatchers.IO) { runCatching { backupManager.exportTo(destination) } }
            busy.value = false
            result.fold(
                onSuccess = { exported ->
                    message.value = buildString {
                        append("Backup saved with ${exported.documentCount} document")
                        if (exported.documentCount != 1) append('s')
                        append('.')
                        if (exported.missingArchiveCount > 0) {
                            append(" ${exported.missingArchiveCount} missing archive file")
                            if (exported.missingArchiveCount != 1) append('s')
                            append(" could not be included.")
                        }
                    }
                },
                onFailure = { message.value = it.message ?: "Could not create Scribit backup." }
            )
        }
    }

    fun restoreBackup(source: Uri) {
        viewModelScope.launch {
            busy.value = true
            val result = withContext(Dispatchers.IO) { runCatching { backupManager.restoreFrom(source) } }
            busy.value = false
            result.fold(
                onSuccess = { restored ->
                    settings.value = settingsRepo.get()
                    refresh()
                    message.value = buildString {
                        append("Restored ${restored.restoredCount} document")
                        if (restored.restoredCount != 1) append('s')
                        append('.')
                        if (restored.duplicateCount > 0) {
                            append(" Skipped ${restored.duplicateCount} document")
                            if (restored.duplicateCount != 1) append('s')
                            append(" that were already present.")
                        }
                        if (restored.missingFileCount > 0) {
                            append(" ${restored.missingFileCount} backup file")
                            if (restored.missingFileCount != 1) append('s')
                            append(" was missing.")
                        }
                        if (restored.settingsRestored && settings.value.apiKey.isBlank()) {
                            append(" Re-enter your API key to finish setup.")
                        }
                    }
                },
                onFailure = { message.value = it.message ?: "Could not restore this Scribit backup." }
            )
        }
    }

    fun smartSearch(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            busy.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val plan = classifier.planSearch(settings.value, query)
                    if (plan.expiringDays != null) {
                        val today = LocalDate.now()
                        db.expiringWithin(today.toString(), today.plusDays(plan.expiringDays.toLong()).toString())
                    } else {
                        db.search(plan.keywords, plan.category)
                    }
                }
            }
            busy.value = false
            result.fold(
                onSuccess = { documents.value = it; reviewOnly.value = false; message.value = "Smart search found ${it.size} result${if (it.size == 1) "" else "s"}." },
                onFailure = { message.value = it.message ?: "Smart search failed."; refresh() }
            )
        }
    }

    fun consumeMessage() { message.value = null }
}

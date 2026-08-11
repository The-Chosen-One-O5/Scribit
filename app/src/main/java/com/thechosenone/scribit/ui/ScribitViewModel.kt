package com.thechosenone.scribit.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.thechosenone.scribit.ai.AiClassifier
import com.thechosenone.scribit.data.AppSettings
import com.thechosenone.scribit.data.DocumentDatabase
import com.thechosenone.scribit.data.DocumentImporter
import com.thechosenone.scribit.data.DuplicateDocumentException
import com.thechosenone.scribit.data.DocumentRecord
import com.thechosenone.scribit.data.SettingsRepository
import com.thechosenone.scribit.worker.DocumentProcessWorker
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
    var duplicateWarning = androidx.compose.runtime.mutableStateOf<String?>(null)
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
            withContext(Dispatchers.Main) {
                documents.value = rows
                selectedDocument.value = refreshedSelected
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
                uris.map { uri -> runCatching { importer.importUri(uri) } }
            }
            busy.value = false
            val duplicate = results.mapNotNull { it.exceptionOrNull() as? DuplicateDocumentException }.firstOrNull()
            val errors = results.mapNotNull { it.exceptionOrNull() }.filterNot { it is DuplicateDocumentException }
            val importedCount = results.count { it.isSuccess }
            when {
                duplicate != null -> duplicateWarning.value = duplicate.message
                errors.isNotEmpty() -> message.value = errors.first().message ?: "Could not import document."
                importedCount > 0 -> message.value = "Imported $importedCount document${if (importedCount == 1) "" else "s"}."
            }
            refresh()
        }
    }

    fun importCameraFile(file: File) {
        viewModelScope.launch {
            busy.value = true
            val result = withContext(Dispatchers.IO) { runCatching { importer.importFile(file, "Scan-${System.currentTimeMillis()}.jpg", "image/jpeg") } }
            busy.value = false
            val error = result.exceptionOrNull()
            if (error is DuplicateDocumentException) {
                duplicateWarning.value = error.message
            } else {
                message.value = result.fold({ "Scan imported." }, { it.message ?: "Could not import scan." })
            }
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
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work = OneTimeWorkRequestBuilder<DocumentProcessWorker>()
            .setInputData(Data.Builder().putLong(DocumentProcessWorker.KEY_DOCUMENT_ID, documentId).build())
            .setConstraints(constraints)
            .addTag("document-$documentId")
            .build()
        WorkManager.getInstance(getApplication()).enqueue(work)
        message.value = "Classification queued."
        refresh()
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
            WorkManager.getInstance(getApplication<Application>()).cancelAllWorkByTag("document-${document.id}")
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

    fun dismissDuplicateWarning() {
        duplicateWarning.value = null
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

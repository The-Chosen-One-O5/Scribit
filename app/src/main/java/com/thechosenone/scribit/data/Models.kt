package com.thechosenone.scribit.data

data class DocumentRecord(
    val id: Long = 0,
    val fileId: String,
    val originalName: String,
    val archivePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val contentHash: String = "",
    val title: String = "",
    val category: String = "Other",
    val documentType: String = "",
    val organization: String = "",
    val issueDate: String = "",
    val expiryDate: String = "",
    val academicYear: String = "",
    val semester: String = "",
    val identifiersJson: String = "[]",
    val tagsJson: String = "[]",
    val summary: String = "",
    val searchTermsJson: String = "[]",
    val confidence: Double = 0.0,
    val status: String = STATUS_QUEUED,
    val errorMessage: String = "",
    val retryAt: Long = 0L,
    val retryCount: Int = 0,
    val duplicateWarning: Boolean = false
) {
    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_PROCESSING = "processing"
        const val STATUS_RETRYING = "retrying"
        const val STATUS_READY = "ready"
        const val STATUS_REVIEW = "review"
        const val STATUS_ERROR = "error"
    }
}

data class AiMetadata(
    val title: String,
    val category: String,
    val documentType: String,
    val organization: String,
    val issueDate: String,
    val expiryDate: String,
    val academicYear: String,
    val semester: String,
    val identifiersJson: String,
    val tagsJson: String,
    val summary: String,
    val searchTermsJson: String,
    val confidence: Double,
    val needsReview: Boolean
)

data class AppSettings(
    val apiBaseUrl: String,
    val apiKey: String,
    val model: String,
    val supportsVision: Boolean,
    val themeMode: String = THEME_SYSTEM,
    val libraryLayout: String = LAYOUT_LIST
) {
    val isConfigured: Boolean
        get() = apiBaseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val LAYOUT_LIST = "list"
        const val LAYOUT_COMPACT = "compact"
        const val LAYOUT_GRID = "grid"
    }
}

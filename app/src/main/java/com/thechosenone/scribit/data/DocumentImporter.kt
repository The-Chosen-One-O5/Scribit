package com.thechosenone.scribit.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.thechosenone.scribit.worker.DocumentProcessWorker
import java.io.File
import java.util.UUID

class DocumentImporter(private val context: Context) {
    private val appContext = context.applicationContext
    private val database = DocumentDatabase(appContext)

    fun importUri(uri: Uri): Long {
        val resolver = appContext.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
        }
        val originalName = displayName?.first?.takeIf { it.isNotBlank() } ?: "document-${System.currentTimeMillis()}"
        val mime = resolver.getType(uri) ?: guessMime(originalName)
        val archiveDir = File(appContext.filesDir, "archive").apply { mkdirs() }
        val extension = originalName.substringAfterLast('.', "").takeIf { it.length in 1..8 }
        val fileId = UUID.randomUUID().toString()
        val archiveFile = File(archiveDir, buildString {
            append(fileId)
            if (!extension.isNullOrBlank()) append('.').append(extension)
        })

        resolver.openInputStream(uri)?.use { input ->
            archiveFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not read this file")

        val id = database.insertImported(
            DocumentRecord(
                fileId = fileId,
                originalName = originalName,
                archivePath = archiveFile.absolutePath,
                mimeType = mime,
                sizeBytes = archiveFile.length(),
                importedAt = System.currentTimeMillis()
            )
        )
        enqueueProcessing(id)
        return id
    }

    fun importFile(file: File, originalName: String = file.name, mimeType: String = guessMime(file.name)): Long {
        val archiveDir = File(appContext.filesDir, "archive").apply { mkdirs() }
        val extension = originalName.substringAfterLast('.', "").takeIf { it.length in 1..8 }
        val fileId = UUID.randomUUID().toString()
        val archiveFile = File(archiveDir, buildString {
            append(fileId)
            if (!extension.isNullOrBlank()) append('.').append(extension)
        })
        file.copyTo(archiveFile, overwrite = false)
        val id = database.insertImported(
            DocumentRecord(
                fileId = fileId,
                originalName = originalName,
                archivePath = archiveFile.absolutePath,
                mimeType = mimeType,
                sizeBytes = archiveFile.length(),
                importedAt = System.currentTimeMillis()
            )
        )
        enqueueProcessing(id)
        return id
    }

    private fun enqueueProcessing(id: Long) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work = OneTimeWorkRequestBuilder<DocumentProcessWorker>()
            .setInputData(Data.Builder().putLong(DocumentProcessWorker.KEY_DOCUMENT_ID, id).build())
            .setConstraints(constraints)
            .addTag("document-processing")
            .build()
        WorkManager.getInstance(appContext).enqueue(work)
    }

    companion object {
        fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "txt", "md", "csv" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}

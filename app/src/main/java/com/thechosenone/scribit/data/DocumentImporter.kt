package com.thechosenone.scribit.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import com.thechosenone.scribit.worker.DocumentProcessingQueue

class DuplicateDocumentException(
    val existingDocument: DocumentRecord
) : IllegalStateException(
    "This exact file is already in Scribit as \"${existingDocument.title.ifBlank { existingDocument.originalName }}\"."
)

class DocumentImporter(private val context: Context) {
    private val appContext = context.applicationContext
    private val database = DocumentDatabase(appContext)

    fun importUri(uri: Uri, enqueueAi: Boolean = true): Long {
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

        val contentHash = sha256(archiveFile)
        database.findDuplicateByHash(contentHash)?.let { duplicate ->
            archiveFile.delete()
            throw DuplicateDocumentException(duplicate)
        }

        val id = database.insertImported(
            DocumentRecord(
                fileId = fileId,
                originalName = originalName,
                archivePath = archiveFile.absolutePath,
                mimeType = mime,
                sizeBytes = archiveFile.length(),
                importedAt = System.currentTimeMillis(),
                contentHash = contentHash
            )
        )
        if (enqueueAi) enqueueProcessing(id)
        return id
    }

    fun importFile(
        file: File,
        originalName: String = file.name,
        mimeType: String = guessMime(file.name),
        enqueueAi: Boolean = true
    ): Long {
        val archiveDir = File(appContext.filesDir, "archive").apply { mkdirs() }
        val extension = originalName.substringAfterLast('.', "").takeIf { it.length in 1..8 }
        val fileId = UUID.randomUUID().toString()
        val archiveFile = File(archiveDir, buildString {
            append(fileId)
            if (!extension.isNullOrBlank()) append('.').append(extension)
        })
        file.copyTo(archiveFile, overwrite = false)
        val contentHash = sha256(archiveFile)
        database.findDuplicateByHash(contentHash)?.let { duplicate ->
            archiveFile.delete()
            throw DuplicateDocumentException(duplicate)
        }

        val id = database.insertImported(
            DocumentRecord(
                fileId = fileId,
                originalName = originalName,
                archivePath = archiveFile.absolutePath,
                mimeType = mimeType,
                sizeBytes = archiveFile.length(),
                importedAt = System.currentTimeMillis(),
                contentHash = contentHash
            )
        )
        if (enqueueAi) enqueueProcessing(id)
        return id
    }

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

    private fun enqueueProcessing(id: Long) {
        DocumentProcessingQueue.enqueue(appContext, id)
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

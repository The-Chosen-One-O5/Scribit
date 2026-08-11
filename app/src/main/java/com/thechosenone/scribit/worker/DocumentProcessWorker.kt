package com.thechosenone.scribit.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.thechosenone.scribit.ai.AiClassifier
import com.thechosenone.scribit.data.DocumentDatabase
import com.thechosenone.scribit.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DocumentProcessWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong(KEY_DOCUMENT_ID, -1L)
        if (id <= 0) return@withContext Result.failure()
        val db = DocumentDatabase(applicationContext)
        val document = db.getById(id) ?: return@withContext Result.failure()
        val settings = SettingsRepository(applicationContext).get()
        if (!settings.isConfigured) {
            db.markError(id, "Finish API setup, then retry classification from the document screen.")
            return@withContext Result.success()
        }

        runCatching {
            AiClassifier(applicationContext).classify(settings, document)
        }.onSuccess {
            db.updateAiMetadata(id, it)
        }.onFailure {
            db.markError(id, it.message ?: "AI classification failed")
        }
        Result.success()
    }

    companion object {
        const val KEY_DOCUMENT_ID = "document_id"
    }
}

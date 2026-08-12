package com.thechosenone.scribit.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.thechosenone.scribit.ai.AiClassifier
import com.thechosenone.scribit.ai.ProviderHttpException
import com.thechosenone.scribit.data.DocumentDatabase
import com.thechosenone.scribit.data.DocumentRecord
import com.thechosenone.scribit.data.SettingsRepository
import java.io.IOException
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class DocumentProcessWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong(KEY_DOCUMENT_ID, -1L)
        if (id <= 0) return@withContext Result.success()

        val db = DocumentDatabase(applicationContext)
        var document = db.getById(id) ?: return@withContext Result.success()
        if (document.status !in setOf(
                DocumentRecord.STATUS_QUEUED,
                DocumentRecord.STATUS_PROCESSING,
                DocumentRecord.STATUS_RETRYING
            )
        ) {
            return@withContext Result.success()
        }

        // A provider can explicitly tell us when it is safe to try again. WorkManager may wake the
        // job before that time, so wait the remaining short interval instead of wasting another API
        // request. Long waits are handed back to WorkManager so Android can put the app to sleep.
        val remainingCooldown = document.retryAt - System.currentTimeMillis()
        if (remainingCooldown > 0) {
            if (remainingCooldown <= MAX_INLINE_COOLDOWN_MS) {
                delay(remainingCooldown)
                document = db.getById(id) ?: return@withContext Result.success()
                if (document.status !in setOf(DocumentRecord.STATUS_QUEUED, DocumentRecord.STATUS_PROCESSING, DocumentRecord.STATUS_RETRYING)) {
                    return@withContext Result.success()
                }
            } else {
                return@withContext Result.retry()
            }
        }

        val settings = SettingsRepository(applicationContext).get()
        if (!settings.isConfigured) {
            db.markError(id, "Finish API setup, then retry classification from the document screen.")
            return@withContext Result.success()
        }

        db.markProcessing(id)
        document = db.getById(id) ?: return@withContext Result.success()

        try {
            val metadata = AiClassifier(applicationContext).classify(settings, document, db.listCategories())
            // The document may have been deleted while an API request was in flight.
            if (db.getById(id) != null) db.updateAiMetadata(id, metadata)
            Result.success()
        } catch (error: ProviderHttpException) {
            if (!error.isRetryable) {
                val prefix = if (error.isRateLimited) "Provider quota/rate limit cannot be retried automatically right now." else "AI request failed."
                db.markError(id, "$prefix ${error.message ?: "HTTP ${error.statusCode}"}")
                return@withContext Result.success()
            }
            scheduleAutomaticRetry(db, id, error.retryAfterMillis, error.message, error.isRateLimited)
        } catch (error: IOException) {
            scheduleAutomaticRetry(db, id, null, error.message, false)
        } catch (error: Throwable) {
            db.markError(id, error.message ?: "AI classification failed")
            Result.success()
        }
    }

    private fun scheduleAutomaticRetry(
        db: DocumentDatabase,
        documentId: Long,
        providerDelayMs: Long?,
        providerMessage: String?,
        rateLimited: Boolean
    ): Result {
        if (runAttemptCount >= MAX_AUTOMATIC_RETRIES) {
            db.markError(
                documentId,
                if (rateLimited) {
                    "Scribit kept retrying but the provider is still rate limiting this queue. Check your provider quota or try again later."
                } else {
                    "Scribit retried this request several times but the provider/network is still unavailable. Try again later."
                }
            )
            return Result.success()
        }

        val fallback = exponentialDelayMs(runAttemptCount)
        val waitMs = (providerDelayMs ?: fallback)
            .coerceAtLeast(MIN_RETRY_DELAY_MS)
            .coerceAtMost(MAX_RETRY_DELAY_MS)
        val retryAt = System.currentTimeMillis() + waitMs
        val message = if (rateLimited) {
            buildString {
                append("Rate limited — Scribit will retry automatically")
                providerMessage?.takeIf { it.isNotBlank() }?.let { append(". ").append(it.take(260)) }
            }
        } else {
            buildString {
                append("Temporary connection/provider problem — retrying automatically")
                providerMessage?.takeIf { it.isNotBlank() }?.let { append(". ").append(it.take(260)) }
            }
        }

        db.markRetrying(
            id = documentId,
            retryAt = retryAt,
            retryCount = runAttemptCount + 1,
            message = message
        )
        return Result.retry()
    }

    private fun exponentialDelayMs(attempt: Int): Long {
        val multiplier = 2.0.pow(attempt.coerceIn(0, 6)).toLong()
        return (30_000L * multiplier).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    companion object {
        const val KEY_DOCUMENT_ID = "document_id"
        private const val MAX_AUTOMATIC_RETRIES = 8
        private const val MIN_RETRY_DELAY_MS = 10_000L
        private const val MAX_RETRY_DELAY_MS = 15 * 60 * 1_000L
        private const val MAX_INLINE_COOLDOWN_MS = 8 * 60 * 1_000L
    }
}

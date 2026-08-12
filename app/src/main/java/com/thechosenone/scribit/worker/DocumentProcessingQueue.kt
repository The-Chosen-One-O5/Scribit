package com.thechosenone.scribit.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * A single persistent WorkManager chain for document AI work.
 *
 * Serialising AI calls prevents a multi-document import from firing a burst of requests at the
 * provider. APPEND_OR_REPLACE also lets the queue recover if an old prerequisite was cancelled.
 */
object DocumentProcessingQueue {
    private const val UNIQUE_QUEUE_NAME = "scribit-ai-processing-queue"

    fun enqueue(context: Context, documentId: Long) {
        enqueueBatch(context, listOf(documentId))
    }

    fun enqueueBatch(context: Context, documentIds: List<Long>) {
        val ids = documentIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return

        val appContext = context.applicationContext
        val manager = WorkManager.getInstance(appContext)
        val requests = ids.map(::requestFor)

        // Build an actual continuation rather than passing a List<WorkRequest>, because a list at
        // one level is eligible to run in parallel. then(...) makes the batch strictly sequential.
        var continuation = manager.beginUniqueWork(
            UNIQUE_QUEUE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            requests.first()
        )
        for (index in 1 until requests.size) {
            continuation = continuation.then(requests[index])
        }
        continuation.enqueue()
    }

    private fun requestFor(documentId: Long): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        return OneTimeWorkRequestBuilder<DocumentProcessWorker>()
            .setInputData(Data.Builder().putLong(DocumentProcessWorker.KEY_DOCUMENT_ID, documentId).build())
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("document-processing")
            .addTag("document-$documentId")
            .build()
    }
}

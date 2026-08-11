package com.thechosenone.scribit.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.thechosenone.scribit.R
import com.thechosenone.scribit.data.DocumentDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class ExpiryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val expiring = DocumentDatabase(applicationContext).expiringWithin(today.toString(), today.plusDays(30).toString())
        if (expiring.isNotEmpty()) {
            ensureChannel(applicationContext)
            if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                val first = expiring.first()
                val text = if (expiring.size == 1) {
                    "${first.title.ifBlank { first.originalName }} expires ${first.expiryDate}"
                } else {
                    "${expiring.size} documents expire within 30 days. First: ${first.title.ifBlank { first.originalName }}"
                }
                val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Scribit expiry reminder")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()
                NotificationManagerCompat.from(applicationContext).notify(4001, notification)
            }
        }
        Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "document_expiry"
        private const val WORK_NAME = "scribit-expiry-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ExpiryWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            ensureChannel(context)
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Expiring documents", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Warnings when indexed documents are nearing their expiry date"
                }
            )
        }
    }
}

package com.classroompresence.data

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = if (PresenceRepository(applicationContext).uploadPending()) Result.success() else Result.retry()
    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("checkpoint-upload", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

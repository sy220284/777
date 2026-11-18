package com.labteto.dshmobile.local.presentation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.observability.AppLog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Continues delivery of an already accepted provider task using its durable local ID. */
class LocalMediaVideoTrackingWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun sessionStorageRuntime(): LocalSessionStorageRuntime
        fun mediaCreationRuntime(): LocalMediaCreationRuntime
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(VIDEO_TRACKING_ID) ?: return Result.failure()
        return try {
            val finished = withContext(Dispatchers.IO) {
                val entry = EntryPointAccessors.fromApplication(
                    applicationContext, WorkerEntryPoint::class.java,
                )
                // Combined archive restore must precede the first Media database read.
                entry.sessionStorageRuntime()
                entry.mediaCreationRuntime().trackAcceptedVideo(taskId)
            }
            if (finished) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.warn("MediaCreation", "background_video_tracking_retry", failure)
            Result.retry()
        }
    }
}

private const val VIDEO_TRACKING_ID = "media_video_tracking_id"

internal class LocalMediaVideoTrackingScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun track(taskId: String) {
        val request = OneTimeWorkRequestBuilder<LocalMediaVideoTrackingWorker>()
            .setInputData(workDataOf(VIDEO_TRACKING_ID to taskId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(taskId), ExistingWorkPolicy.KEEP, request).await()
    }

    suspend fun cancel(taskId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(name(taskId)).await()
    }

    private fun name(taskId: String) = "777-media-video-track-$taskId"
}

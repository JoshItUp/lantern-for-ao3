package app.ao3reader.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ao3reader.App
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Whether the app has a visible activity; interactive Cloudflare solves are allowed only then. */
object AppForeground {
    val isForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}

/**
 * Drains the download queue one work at a time, oldest request first. A failure leaves its row FAILED
 * (Retry is manual) and moves on; the worker itself always succeeds.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val deps: Deps,
) : CoroutineWorker(context, params) {

    class Deps(
        val repo: DownloadRepository,
        val run: suspend (workId: Long, interactive: Boolean) -> DownloadFailure?,
        val isForeground: () -> Boolean,
    )

    /** The constructor WorkManager's default factory uses. */
    @Suppress("unused")
    constructor(context: Context, params: WorkerParameters) : this(context, params, depsFrom(context))

    override suspend fun doWork(): Result {
        deps.repo.ready.join()
        while (true) {
            val row = deps.repo.nextQueued() ?: break
            showProgress(row.title)
            try {
                deps.run(row.workId, deps.isForeground())
            } catch (e: CancellationException) {
                // Stopped by WorkManager mid-download: queue it again so the next run picks it up.
                withContext(NonCancellable) { deps.repo.requeue(row.workId) }
                throw e
            }
        }
        return Result.success()
    }

    private suspend fun showProgress(title: String) {
        try {
            setForeground(foregroundInfo(applicationContext, title))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Android may refuse a foreground service from the background; the download still runs.
        }
    }

    companion object {
        private const val UNIQUE_WORK = "downloads"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 3001

        private fun depsFrom(context: Context): Deps {
            val c = (context.applicationContext as App).container
            return Deps(c.downloadRepo, c.downloader::run) { AppForeground.isForeground }
        }

        /**
         * Appends a run after any pending or running one (APPEND_OR_REPLACE, not KEEP): a row queued just as a
         * running loop finds the queue empty is still picked up. A follow-up run on an empty queue ends at once.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        private fun foregroundInfo(context: Context, title: String): ForegroundInfo {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading $title")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }
}

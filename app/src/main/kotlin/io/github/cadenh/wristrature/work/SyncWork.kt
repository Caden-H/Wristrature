package io.github.cadenh.wristrature.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.cadenh.wristrature.WristratureApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Sends reading positions that could not be sent earlier (for example, when offline). Requires a network connection, so
 * it waits until the watch is online. Nothing else runs in the background.
 */
class PushUnsentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as WristratureApp).graph
        if (graph.sync.pushAllUnsent()) return Result.success()
        // Give up after repeated failures (a wrong password or a persistently failing server) so the radio is not woken
        // indefinitely. The next reading session enqueues the job again.
        return if (runAttemptCount >= 6) Result.failure() else Result.retry()
    }
}

/**
 * Optional periodic check for newer positions on the server (setting "Check in background"). Runs only with a network
 * connection and a battery that is not low, and covers the most recently read books rather than the whole library.
 */
class FreshnessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as WristratureApp).graph
        if (!graph.settings.get().servers.syncConfigured) return Result.success()
        var moved = false
        for (item in graph.library.recent(3).first()) {
            if (item.book.finished || !item.book.onWatch) continue
            if (graph.sync.backgroundCheck(item.book)) moved = true
        }
        if (moved) io.github.cadenh.wristrature.complication.WatchSurfaces.refresh(applicationContext)
        return Result.success()
    }
}

object SyncWork {
    private const val PERIODIC = "freshness"
    private const val UNIQUE = "push-unsent"

    fun enqueuePush(context: Context) {
        val request = OneTimeWorkRequestBuilder<PushUnsentWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request)
    }

    /** Starts, reschedules or cancels the periodic check for an interval of [hours] (0 disables it). Idempotent. */
    fun applyBackground(context: Context, hours: Int) {
        val wm = WorkManager.getInstance(context)
        if (hours <= 0) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<FreshnessWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

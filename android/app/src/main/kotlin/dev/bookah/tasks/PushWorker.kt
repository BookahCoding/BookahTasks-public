package dev.bookah.tasks

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sends queued changes as soon as the phone has a network, without waiting for the 15-minute refresh (target: within a minute of the
 * tailnet coming back). Nextcloud is only reachable on the tailnet, so a run can still find the server away: it then asks to be
 * tried again later, and the queue is kept.
 */
class PushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = (applicationContext as BookahApp).graph
        val session = graph.sessions.load() ?: return@withContext Result.success()
        val report = try { graph.syncLock.withLock { graph.sync.sync(graph.apiFactory(session)) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { return@withContext Result.retry() }
        graph.changed.tryEmit(Unit)          // the open screen reads the saved copy again
        graph.rescheduleReminders()          // tasks may have changed on the server
        if (report.offline && graph.outbox.count() > 0) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "push"

        /** [delaySeconds] usually keeps a change on the phone while the undo bar is showing. It is not a guarantee (a push already scheduled by an earlier change, a manual sync or the 15-minute refresh can send it sooner), so an undo must also work after a push; the outbox handles that. */
        fun enqueue(context: Context, delaySeconds: Long = 0) {
            val request = OneTimeWorkRequestBuilder<PushWorker>()
                .setInitialDelay(delaySeconds, java.util.concurrent.TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

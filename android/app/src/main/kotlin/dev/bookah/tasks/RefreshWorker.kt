package dev.bookah.tasks

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Brings the phone's copy up to date in the background, about every 15 minutes while the phone has a network (the Nextcloud server
 * is only reachable on the tailnet: off it, the run simply reports offline and keeps what is cached). It does nothing when nobody
 * is signed in. Whether HyperOS lets this run on time is measured on the phone, not assumed.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = (applicationContext as BookahApp).graph
        val session = graph.sessions.load() ?: return@withContext Result.success()
        // an odd answer must never fail the worker for good; a cut-off event download goes on from where it stopped next time
        try { graph.noteSync(graph.syncLock.withLock { graph.sync.sync(graph.apiFactory(session)) }) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {}
        graph.changed.tryEmit(Unit)
        dev.bookah.tasks.widget.WidgetRefresh.request(applicationContext)
        graph.rescheduleReminders()
        Result.success()
    }

    companion object {
        private const val NAME = "refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)

        /** One refresh now (as soon as there is a network); a second request while one is waiting is dropped. */
        fun runOnce(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("refresh-now", androidx.work.ExistingWorkPolicy.KEEP, request)
        }
    }
}

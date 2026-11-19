package dev.bookah.tasks.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.bookah.tasks.BookahApp
import dev.bookah.tasks.PushWorker
import dev.bookah.tasks.data.TaskCompletion
import dev.bookah.tasks.data.TaskParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/** A tap on a widget circle: tick or reopen one task, under the same write lock as the screens and the notification buttons. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.getStringExtra("op")
        val href = intent.getStringExtra("href")
        if (href == null || (op != "tick" && op != "reopen")) return
        val stamp = intent.getStringExtra("stamp")
        if (op == "tick" && stamp == null) return          // a tick always carries what was drawn; without it nothing is written
        val graph = (context.applicationContext as BookahApp).graph
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                graph.writeLock.withLock {
                    val zone = ZoneId.systemDefault()
                    val now = Instant.now()
                    val title = graph.store.objectByHref(href)?.let { TaskParser.parse(it, zone).firstOrNull()?.title }.orEmpty()
                    val out = if (op == "tick") TaskCompletion.tick(graph.store, graph.outbox, href, stamp, zone, now)
                        else TaskCompletion.reopen(graph.store, graph.outbox, href, zone, now)
                    if (out is TaskCompletion.Outcome.Advanced) WidgetRecent.advanced(href, title, now.toEpochMilli())
                }
                PushWorker.enqueue(context, 0)
                graph.changed.tryEmit(Unit)
                graph.rescheduleReminders()
            } catch (e: Exception) {
                // nothing that is not saved was changed; the redraw below shows the truth
                dev.bookah.tasks.CrashLog.record(context, e)
            } finally {
                WidgetRefresh.scheduleAt(context, System.currentTimeMillis() + 125_000L, 12)          // the two minutes are over: struck rows and the note go
                WidgetRefresh.request(context)
                pending.finish()
            }
        }
    }
}

package dev.bookah.tasks.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.bookah.tasks.BookahApp
import dev.bookah.tasks.data.OutboxState
import dev.bookah.tasks.data.TaskEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * `adb shell am broadcast -a dev.bookah.tasks.QUEUE_SEED -n dev.bookah.tasks/.debug.QueueSeedReceiver`
 * On the seeded fake account only: queues two edits and marks one as a conflict (the server's copy has another title) and one as refused
 * (403), so the conflict dialog, the Settings rows for refused changes and the sign-out warning can be looked at. Debug builds only.
 */
class QueueSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as BookahApp).graph
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (graph.sessions.load()?.server != "https://tasks.invalid") return@launch
                val editable = graph.store.lists().filter { it.editable }.map { it.id }.toSet()
                val objs = graph.store.allObjects().filter { it.listId in editable }.take(2)
                if (objs.size < 2) return@launch
                val a = objs[0]
                graph.outbox.edit(a.href, TaskEdit.setTitle(a.ics, "Mine: edited on this phone"))
                graph.store.outboxAll().first { it.href == a.href }.let {
                    graph.store.outboxUpdate(it.copy(state = OutboxState.Conflict, lastCode = 412, serverEtag = "x", serverIcs = TaskEdit.setTitle(a.ics, "Theirs: edited on Nextcloud")))
                }
                val b = objs[1]
                graph.outbox.edit(b.href, TaskEdit.setTitle(b.ics, "Change that Nextcloud refused"))
                graph.store.outboxAll().first { it.href == b.href }.let { graph.store.outboxUpdate(it.copy(state = OutboxState.Failed, lastCode = 403)) }
                graph.changed.tryEmit(Unit)
            } finally {
                pending.finish()
            }
        }
    }
}

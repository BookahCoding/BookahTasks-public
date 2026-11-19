package dev.bookah.tasks.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.bookah.tasks.BookahApp
import dev.bookah.tasks.data.OutboxItem
import dev.bookah.tasks.data.OutboxKind
import dev.bookah.tasks.data.OutboxState

/**
 * `adb shell am broadcast -a dev.bookah.tasks.OUTBOX_SELFTEST -n dev.bookah.tasks/.debug.OutboxSelfTestReceiver`
 * then `adb logcat -d -s OutboxSelfTest`. Writes, updates and removes one outbox row in the real SQLite database. Debug builds only.
 */
class OutboxSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = (context.applicationContext as BookahApp).graph.store
        try {
            val before = store.outboxAll().size
            val id = store.outboxInsert(OutboxItem(0, OutboxKind.Create, 1, "/selftest/x.ics", null, "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", 1L))
            val inserted = store.outboxAll().firstOrNull { it.id == id }
            store.outboxUpdate(inserted!!.copy(state = OutboxState.Conflict, lastCode = 412, serverEtag = "e", serverIcs = "x", attempts = 2))
            val updated = store.outboxAll().first { it.id == id }
            store.outboxRemove(id)
            Log.i(
                "OutboxSelfTest",
                "before=$before inserted=${inserted.kind}/${inserted.state}/${inserted.baseEtag} updated=${updated.state}/${updated.lastCode}/${updated.serverEtag}/${updated.serverIcs}/${updated.attempts} after=${store.outboxAll().size}",
            )
        } catch (t: Throwable) {
            Log.e("OutboxSelfTest", "FAILED: ${t::class.simpleName}: ${t.message}")
        }
    }
}

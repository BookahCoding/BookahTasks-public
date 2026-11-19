package dev.bookah.tasks.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.bookah.tasks.BookahApp
import dev.bookah.tasks.data.IcsPatcher
import dev.bookah.tasks.data.TaskEdit
import dev.bookah.tasks.data.TaskWhen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * `adb shell am broadcast -a dev.bookah.tasks.REMINDER_TEST -n dev.bookah.tasks/.debug.ReminderTestReceiver --ei minutes 1 [--ez repeat true]`
 * Creates a task that starts in [minutes] minutes and is due 90 minutes after that, then plans the reminders, so the start notification
 * should appear after [minutes] minutes and the "due in 1 hour" one 30 minutes after that. Debug builds only; the change stays queued (the fake server of the seeded data does not exist, so nothing is ever sent).
 */
class ReminderTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val minutes = intent.getIntExtra("minutes", 1).toLong()
        val graph = (context.applicationContext as BookahApp).graph
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // only on the generated-data account made by SeedReceiver (its server does not exist): never on a real sign-in
                if (graph.sessions.load()?.server != "https://tasks.invalid") return@launch
                val zone = ZoneId.systemDefault()
                val list = graph.store.lists().firstOrNull { it.kind.holdsTasks && it.editable } ?: return@launch
                val start = Instant.now().plusSeconds(minutes * 60).atZone(zone)
                val due = start.plusMinutes(90)
                val uid = UUID.randomUUID().toString()
                var ics = IcsPatcher.newTask(uid, "Reminder test", Instant.now())
                ics = TaskEdit.setDates(ics, TaskWhen(start.toLocalDate(), start.toLocalTime().withSecond(0).withNano(0)), TaskWhen(due.toLocalDate(), due.toLocalTime().withSecond(0).withNano(0)), zone)
                // --ez repeat true: a daily task, so Done on the notification moves it to tomorrow (Phase 3)
                if (intent.getBooleanExtra("repeat", false)) ics = TaskEdit.setRepeat(ics, dev.bookah.tasks.data.RepeatSpec(dev.bookah.tasks.data.RepeatFreq.Daily), zone, start.toLocalDate())
                graph.outbox.create(list, uid, ics)
                graph.changed.tryEmit(Unit)
                graph.rescheduleReminders()
            } finally {
                pending.finish()
            }
        }
    }
}

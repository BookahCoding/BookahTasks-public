package dev.bookah.tasks

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.bookah.tasks.data.PlannedReminder
import dev.bookah.tasks.data.ReminderKind
import dev.bookah.tasks.data.TaskEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val ACTION_FIRE = "dev.bookah.tasks.REMINDER_FIRE"
private const val ACTION_SNOOZE = "dev.bookah.tasks.REMINDER_SNOOZE"
private const val ACTION_DONE = "dev.bookah.tasks.REMINDER_DONE"
private const val ACTION_TOMORROW = "dev.bookah.tasks.REMINDER_TOMORROW"
/** The tap on a reminder notification: MainActivity opens that task with its reschedule sheet (This evening is there). */
const val ACTION_OPEN_TASK = "dev.bookah.tasks.OPEN_TASK"
private const val CHANNEL = "reminders"
/** "+1 hour" (Phase 3, replaces Snooze 10 min): the reminder comes again in an hour; nothing is written to Nextcloud. */
private const val SNOOZE_MS = 60 * 60_000L

/** Turns the planned reminders into phone alarms and takes back the ones that are no longer planned. Everything stays on the phone. */
class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    /** Makes the phone's alarms equal to [plan]: new ones are set, changed ones updated, vanished ones cancelled. */
    fun reschedule(plan: List<PlannedReminder>) {
        val old = prefs.getStringSet(KEY, emptySet()).orEmpty()
        val keep = plan.map { it.key }.toSet()
        val now = System.currentTimeMillis()
        // an alarm whose time has come but that has not been delivered yet (a late delivery, Doze) is left to fire: the receiver checks it is still valid
        for (k in old - keep) if ((k.substringAfterLast('|').toLongOrNull() ?: 0L) > now) cancel(k)
        for (r in plan) set(r.triggerAtMs, pending(r.key, r, create = true)!!)
        prefs.edit().putStringSet(KEY, keep).apply()
    }

    fun snooze(r: PlannedReminder) = set(System.currentTimeMillis() + SNOOZE_MS, pending(r.key + "|snooze", r, create = true)!!)

    private fun cancel(key: String) {
        pending(key, null, create = false)?.let { alarms.cancel(it); it.cancel() }
    }

    private fun set(at: Long, pi: PendingIntent) {
        // exact when the phone allows it; otherwise a slightly inexact alarm, never nothing
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        val ok = runCatching {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.isSuccess
        if (!ok) runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }      // the exact one was refused: slightly late is better than never
    }

    private fun pending(key: String, r: PlannedReminder?, create: Boolean): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("bookah://reminder/" + Uri.encode(key)))
        if (r != null) putReminder(intent, r)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private companion object { const val KEY = "scheduled" }
}

private fun putReminder(i: Intent, r: PlannedReminder) {
    i.putExtra("key", r.key).putExtra("trigger", r.triggerAtMs).putExtra("kind", r.kind.ordinal).putExtra("title", r.title)
        .putExtra("href", r.href).putExtra("uid", r.uid).putExtra("at", r.atMs).putExtra("dateOnly", r.dateOnly)
}

private fun readReminder(i: Intent) = PlannedReminder(
    i.getStringExtra("key").orEmpty(), i.getLongExtra("trigger", 0L), ReminderKind.entries[i.getIntExtra("kind", 0).coerceIn(0, ReminderKind.entries.size - 1)],
    i.getStringExtra("title").orEmpty(), i.getStringExtra("href").orEmpty(), i.getStringExtra("uid").orEmpty(),
    i.getLongExtra("at", 0L), i.getBooleanExtra("dateOnly", false),
)

/**
 * The notification itself: the task's title and why it is sent. Buttons (Android shows three at most; docs/phase-3-design.md section 4):
 * Done (the tick), +1 hour (the reminder again in an hour), Tomorrow (the swipe sheet's rule). A tap opens the task with its reschedule
 * sheet, where This evening is. Done and Tomorrow are left out on a read-only list.
 */
object ReminderNotifier {
    fun notificationsAllowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun show(context: Context, r: PlannedReminder, canFinish: Boolean = true, id: Int = r.key.hashCode()) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_HIGH))
        val zone = ZoneId.systemDefault()
        val time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(r.atMs).atZone(zone))
        val base = Instant.ofEpochMilli(r.atMs).atZone(zone)
        val whenText = Words(context.resources).whenText(dev.bookah.tasks.data.TaskWhen(base.toLocalDate(), if (r.dateOnly) null else base.toLocalTime().withSecond(0).withNano(0)), java.time.LocalDate.now(zone))
        val text = when (r.kind) {
            ReminderKind.Start -> if (r.dateOnly) context.getString(R.string.notif_start_today) else context.getString(R.string.notif_start_now)
            ReminderKind.DueSoon -> context.getString(R.string.notif_due_soon, time)
            ReminderKind.DueToday -> context.getString(R.string.notif_due_today)
            ReminderKind.DueNow -> context.getString(R.string.notif_due_now, time)
            ReminderKind.Custom -> context.getString(R.string.notif_custom, time)
            ReminderKind.StartAt -> context.getString(R.string.notif_starts_when, whenText)
            ReminderKind.DueAt -> context.getString(R.string.notif_due_when, whenText)
        }
        // explicit and immutable: only MainActivity, only this task's address (it opens a screen, it changes nothing)
        val openIntent = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_TASK).setData(Uri.parse("bookah://open/" + Uri.encode(r.key)))
            .putExtra("href", r.href).putExtra("uid", r.uid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, id, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(name: String, scheme: String, requestSalt: Int): PendingIntent {
            val i = Intent(context, ReminderReceiver::class.java).setAction(name).setData(Uri.parse("bookah://$scheme/" + Uri.encode(r.key)))
            putReminder(i, r)
            i.putExtra("notificationId", id)
            return PendingIntent.getBroadcast(context, id + requestSalt, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(r.title.ifBlank { context.getString(R.string.untitled_task) }).setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
            .setContentIntent(open)
            .apply { if (canFinish) addAction(0, context.getString(R.string.notif_done), action(ACTION_DONE, "done", 1)) }
            .addAction(0, context.getString(R.string.notif_snooze), action(ACTION_SNOOZE, "snooze", 2))
            .apply { if (canFinish) addAction(0, context.getString(R.string.notif_tomorrow), action(ACTION_TOMORROW, "tomorrow", 3)) }
            .build()
        try { NotificationManagerCompat.from(context).notify(id, n) } catch (e: SecurityException) { /* notifications not allowed: nothing to show */ }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val r = readReminder(intent)
        val graph = (context.applicationContext as BookahApp).graph
        when (intent.action) {
            ACTION_FIRE -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        // only if the task would still plan exactly this reminder (it may have been finished, deleted, moved or rescheduled since the alarm was set)
                        if (stillPlanned(graph, r)) {
                            val canFinish = graph.store.objectByHref(r.href)?.let { o -> graph.store.lists().firstOrNull { it.id == o.listId }?.editable } == true
                            ReminderNotifier.show(context, r, canFinish)
                        }
                        graph.rescheduleReminders()          // plan again: the alarm that just fired makes room for the next one
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_SNOOZE -> {
                NotificationManagerCompat.from(context).cancel(intent.getIntExtra("notificationId", r.key.hashCode()))
                ReminderScheduler(context).snooze(r)
            }
            ACTION_DONE -> act(context, graph, r, intent) { obj, zone, now ->
                // like ticking in the app: a repeating task moves on; finishing a parent also finishes its open subtasks
                val out = dev.bookah.tasks.data.TaskCompletion.tick(graph.store, graph.outbox, r.href, null, zone, now)
                // the next date could not be worked out, or the task is in conflict: nothing is written and the notification stays (the owner sees the task again)
                if (out is dev.bookah.tasks.data.TaskCompletion.Outcome.Skipped) throw IllegalStateException(out.reason)
            }
            ACTION_TOMORROW -> act(context, graph, r, intent) { obj, zone, now ->
                // the same rule as "Tomorrow" in the swipe sheet (which date moves, times kept, Someday left)
                val evening = graph.prefs.flow.first().eveningHour
                val out = dev.bookah.tasks.data.Reschedule.applyTo(obj.ics, dev.bookah.tasks.data.RescheduleTarget.Tomorrow, java.time.LocalDate.now(zone), evening, zone)
                if (out != null) graph.outbox.edit(r.href, dev.bookah.tasks.data.IcsPatcher.touch(out, now))
            }
        }
    }

    /**
     * Runs one notification button's change with the app closed or open: under the same write lock as the screens, only when the task still
     * plans exactly this reminder (a stale notification, a vanished task or a task changed meanwhile only makes the notification go away),
     * and the notification is dismissed only after the change is saved. A refused change (a task in conflict) leaves it in place.
     */
    private fun act(
        context: Context, graph: AppGraph, r: PlannedReminder, intent: Intent,
        change: suspend (obj: dev.bookah.tasks.data.StoredObject, zone: ZoneId, now: Instant) -> Unit,
    ) {
        val notificationId = intent.getIntExtra("notificationId", r.key.hashCode())
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                graph.writeLock.withLock {
                    val obj = graph.store.objectByHref(r.href)
                    if (obj != null && stillPlanned(graph, r)) change(obj, ZoneId.systemDefault(), Instant.now())
                }
                NotificationManagerCompat.from(context).cancel(notificationId)          // reached only when the change was saved (or there was nothing to do)
                PushWorker.enqueue(context, 0)
                graph.changed.tryEmit(Unit)
                dev.bookah.tasks.widget.WidgetRefresh.request(context)          // a button on a notification changes today's list
                graph.rescheduleReminders()
            } catch (e: Exception) {
                // a task in conflict or odd data: the notification stays, so the task is not silently left undone
            } finally {
                pending.finish()
            }
        }
    }
}

/** After a restart of the phone (alarms are lost) or an update of the app: plan the reminders again from what is on the phone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { (context.applicationContext as BookahApp).graph.rescheduleReminders(); dev.bookah.tasks.widget.WidgetRefresh.request(context) } finally { pending.finish() }
        }
    }
}

/** True when the task, as it is on the phone now, would still plan exactly this reminder (same kind and moment). Stale alarms and stale Done taps fail this. */
private fun stillPlanned(graph: AppGraph, r: PlannedReminder): Boolean {
    val zone = ZoneId.systemDefault()
    val obj = graph.store.objectByHref(r.href) ?: return false
    val task = dev.bookah.tasks.data.TaskParser.parse(obj, zone).firstOrNull { !it.isOccurrenceCopy && it.uid == r.uid } ?: return false
    return dev.bookah.tasks.data.ReminderPlanner.plan(listOf(task), r.triggerAtMs - 1, zone).any { it.key == r.key }
}

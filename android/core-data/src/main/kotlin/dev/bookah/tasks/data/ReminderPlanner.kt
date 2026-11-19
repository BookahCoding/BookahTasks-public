package dev.bookah.tasks.data

import java.time.LocalTime
import java.time.ZoneId

/**
 * Why a notification is sent: the task starts, its deadline is an hour away (or, when it is already closer, now), a date-only deadline is
 * today, a reminder line stored in the task, or (Phase 3, the owner's own list) some time before the start ([StartAt]) or the deadline
 * ([DueAt]). New kinds go at the end: the ordinal travels inside alarms already set.
 */
enum class ReminderKind { Start, DueSoon, DueToday, Custom, DueNow, StartAt, DueAt }

/** One notification to show at [triggerAtMs]. [key] is stable for the same task and moment, so planning again updates instead of duplicating. */
data class PlannedReminder(
    val key: String, val triggerAtMs: Long, val kind: ReminderKind, val title: String, val href: String, val uid: String,
    /** The moment the task starts or is due (for the notification text); a date-only value counts from [ReminderPlanner.MORNING]. */
    val atMs: Long, val dateOnly: Boolean,
)

/**
 * Decides which notifications the phone should have scheduled (the owner, 17 Nov 2026: "send a notification when the task starts and an hour
 * from the deadline if set"). Everything is worked out on the phone from the tasks; nothing extra is written to Nextcloud.
 *
 * - A start with a time reminds at that time. A deadline with a time reminds [DUE_LEAD_MINUTES] before it, unless that would be before the
 *   task starts (the start reminder covers it).
 * - A start or deadline with only a date reminds at [MORNING] that day.
 * - Reminder lines stored in the task (by other apps) also fire; the same minute is never notified twice for one task.
 * - Only open, not parked tasks; only the next [HORIZON_DAYS] days; at most the [MAX_SCHEDULED] nearest (Android limits the alarms an app may
 *   hold; every change and every fired alarm plans again, which pulls the next ones in).
 */
object ReminderPlanner {
    const val HORIZON_DAYS = 14
    const val MAX_SCHEDULED = 40
    const val DUE_LEAD_MINUTES = 60
    val MORNING: LocalTime = LocalTime.of(9, 0)
    private const val DAY_MS = 86_400_000L
    private const val MINUTE_MS = 60_000L

    fun plan(tasks: List<Task>, nowMs: Long, zone: ZoneId, morning: LocalTime = MORNING): List<PlannedReminder> {
        val horizon = nowMs + HORIZON_DAYS * DAY_MS
        fun ms(w: TaskWhen) = w.date.atTime(w.time ?: morning).atZone(zone).toInstant().toEpochMilli()
        val out = ArrayList<PlannedReminder>()
        for (t in tasks) {
            if (!t.isOpen || t.someday || t.isOccurrenceCopy) continue
            val startAt = t.start?.let { ms(it) }
            val dueAt = t.due?.let { ms(it) }
            val seen = HashSet<Long>()                        // minutes already notified for this task
            fun add(kind: ReminderKind, at: Long, base: Long, dateOnly: Boolean) {
                if (at <= nowMs || at > horizon || !seen.add(at / MINUTE_MS)) return
                out.add(PlannedReminder("${t.href}|${t.uid}|${kind.name}|$at", at, kind, t.title, t.href, t.uid, base, dateOnly))
            }
            // one list for the screen and for the alarms (ReminderSet.of): the defaults, or the owner's own list that replaces them
            // (docs/phase-3-design.md section 3). A timed deadline already less than an hour away gets "at the deadline" in the defaults.
            val list = ReminderSet.of(t, zone, morning, nowMs)
            run {
                for (c in list.own) {
                    val at = ReminderSet.moment(c, t, zone, morning) ?: continue
                    when (c) {
                        is ReminderChoice.At -> add(ReminderKind.Custom, at, at, false)
                        is ReminderChoice.Before -> {
                            val w = (if (c.toDue) t.due else t.start)!!
                            val base = ms(w)
                            val dateOnly = w.time == null
                            val kind = when {
                                !c.toDue && c.minutes == 0 -> ReminderKind.Start
                                c.toDue && c.minutes == 0 -> if (dateOnly) ReminderKind.DueToday else ReminderKind.DueNow
                                c.toDue && c.minutes == DUE_LEAD_MINUTES && !dateOnly -> ReminderKind.DueSoon
                                c.toDue -> ReminderKind.DueAt
                                else -> ReminderKind.StartAt
                            }
                            add(kind, at, base, dateOnly)
                        }
                    }
                }
            }
            for (s in list.items) {
                if (s.source != ReminderSource.Foreign) continue
                val at = s.atMs ?: continue
                add(ReminderKind.Custom, at, startAt ?: dueAt ?: at, false)
            }
        }
        return out.sortedWith(compareBy({ it.triggerAtMs }, { it.key })).take(MAX_SCHEDULED)
    }
}

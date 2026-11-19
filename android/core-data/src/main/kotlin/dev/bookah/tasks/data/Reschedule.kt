package dev.bookah.tasks.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** Where a task is moved to: the swipe-left sheet, and later Phase 5's overdue buttons, use the same choices. */
sealed interface RescheduleTarget {
    data object Today : RescheduleTarget
    /** Today at the evening hour from Settings. */
    data object Evening : RescheduleTarget
    data object Tomorrow : RescheduleTarget
    /** The coming Monday. */
    data object NextWeek : RescheduleTarget
    /** Saturday of this week; on a Saturday or a Sunday the next Saturday (docs/phase-5-design.md section 1). */
    data object Weekend : RescheduleTarget
    data class On(val date: LocalDate) : RescheduleTarget
    /** Parked: the dates stay as they are. */
    data object Someday : RescheduleTarget
}

/** The dates to write. [someday] null means "leave the tag as it is". */
data class RescheduleResult(val start: TaskWhen?, val due: TaskWhen?, val someday: Boolean?)

/**
 * Which date moves (advisor, 17 Nov 2026): an overdue task moves its deadline; otherwise the start moves, or the deadline when there is no
 * start, or a new start is set when there are no dates at all. Each value keeps its time of day (This evening sets the evening hour), a
 * deadline is never left before the start, and a parked task given a date leaves Someday.
 */
object Reschedule {
    /** The Saturday that Weekend means: this week's, or the next one when the weekend is already under way. */
    fun weekend(today: LocalDate): LocalDate = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).let {
        if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) it else today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
    }

    fun plan(task: Task, target: RescheduleTarget, today: LocalDate, eveningHour: Int): RescheduleResult {
        if (target is RescheduleTarget.Someday) return RescheduleResult(task.start, task.due, true)
        val date = when (target) {
            RescheduleTarget.Today, RescheduleTarget.Evening -> today
            RescheduleTarget.Tomorrow -> today.plusDays(1)
            RescheduleTarget.NextWeek -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            RescheduleTarget.Weekend -> weekend(today)
            is RescheduleTarget.On -> target.date
            RescheduleTarget.Someday -> today
        }
        val evening: LocalTime? = if (target is RescheduleTarget.Evening) LocalTime.of(eveningHour, 0) else null
        fun moved(old: TaskWhen?) = TaskWhen(date, evening ?: old?.time)
        var start = task.start
        var due = task.due
        val overdue = due != null && due.date < today
        val repeats = task.repeat != null
        when {
            overdue && repeats && start != null -> {
                // a repeating task keeps the gap between its start and its deadline: both move by the same number of days
                val shift = java.time.temporal.ChronoUnit.DAYS.between(due!!.date, date)
                start = start.copy(date = start.date.plusDays(shift))
                due = moved(due)
            }
            overdue -> {
                due = moved(due)
                if (start != null && due!!.before(start)) start = TaskWhen(date, start.time).let { if (due!!.before(it)) due else it }
            }
            start != null && due != null && repeats -> {
                val shift = java.time.temporal.ChronoUnit.DAYS.between(start.date, date)
                start = moved(start)
                due = due.copy(date = due.date.plusDays(shift))
            }
            start != null -> {
                start = moved(start)
                if (due != null && due.before(start)) due = TaskWhen(date, due.time).let { if (it.before(start!!)) start else it }
            }
            due != null -> due = moved(due)
            else -> start = moved(null)
        }
        return RescheduleResult(start, due, if (task.someday) false else null)
    }

    /**
     * The task's new text after moving it to [target] (not yet touched), or null when there is nothing to change. The swipe sheet, the task
     * page and the notification's Tomorrow button all use this, so they behave the same. Someday only adds the tag: the dates stay.
     */
    fun applyTo(ics: String, target: RescheduleTarget, today: LocalDate, eveningHour: Int, zone: java.time.ZoneId): String? {
        val now = TaskParser.parse(StoredObject("/x.ics", 0, "", ics), zone).firstOrNull { !it.isOccurrenceCopy } ?: return null
        if (target is RescheduleTarget.Someday) return if (now.someday) null else TaskEdit.setSomeday(ics, true)
        val r = plan(now, target, today, eveningHour)
        var out = TaskEdit.setDates(ics, r.start, r.due, zone)
        r.someday?.let { out = TaskEdit.setSomeday(out, it) }
        return out
    }

    /** True when this moment is before [o]. A missing time means "the whole day", so a day without a time is only compared by date. */
    private fun TaskWhen.before(o: TaskWhen): Boolean = if (time == null || o.time == null) date < o.date else this < o
}

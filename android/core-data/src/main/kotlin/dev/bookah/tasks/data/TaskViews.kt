package dev.bookah.tasks.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Today's three groups (plan, section 4.1): overdue (folded on screen), today, and this evening. */
data class TodayView(val overdue: List<Task>, val today: List<Task>, val evening: List<Task>)

/** The Schedule screen: overdue, then no date, then dated tasks by day. */
data class ScheduleView(val overdue: List<Task>, val noDate: List<Task>, val days: List<Pair<LocalDate, List<Task>>>) {
    val isEmpty get() = overdue.isEmpty() && noDate.isEmpty() && days.isEmpty()
}

/** One heading on Upcoming and Schedule: a single day ([weekOf] false) or a whole later week starting on [date], a Monday ([weekOf] true). */
data class DayBucket(val date: LocalDate, val weekOf: Boolean, val tasks: List<Task>)

/** A task with the tasks directly under it (one level shown in the editor, deeper levels shown if they exist). */
data class TaskNode(val task: Task, val children: List<TaskNode>)

/** Logbook: finished tasks grouped by the day they were finished (null = no date on record), newest first. */
data class LogDay(val date: LocalDate?, val tasks: List<Task>)

/**
 * The smart views as plain functions of the tasks, the date and the settings, so they are tested without a screen.
 * Rules (plan, sections 4.1 and 6):
 * - a finished task, a copy of a repeating task's finished occurrence and a Someday task are in no view except the Logbook / Someday;
 * - overdue = the deadline is before today; it is never mixed into Today;
 * - Today = not overdue and (the start date is today or earlier, or the deadline is today); a "waiting" task has its check-back date as start,
 *   so it stays out of Today until then without any special rule;
 * - Evening = a Today task whose time today is [eveningHour] or later (the start time if it starts today, else the deadline time);
 * - Upcoming = a future start date, or a future deadline when the task has not started; a task in Today or overdue is not repeated there.
 */
object TaskViews {
    private val byPriorityThenOrder = compareBy<Task>({ it.priority.rank }, { it.sortOrder }, { it.title.lowercase() })

    fun isOverdue(t: Task, today: LocalDate): Boolean = t.isOpen && !t.someday && t.due != null && t.due.date < today

    private fun hasStarted(t: Task, today: LocalDate) = t.start != null && t.start.date <= today
    private fun dueToday(t: Task, today: LocalDate) = t.due != null && t.due.date == today

    /** The time of day that places this task today, or null when it has none. */
    fun timeToday(t: Task, today: LocalDate): LocalTime? = when {
        t.start != null && t.start.date == today && t.start.time != null -> t.start.time
        dueToday(t, today) && t.due?.time != null -> t.due.time
        else -> null
    }

    fun today(tasks: List<Task>, today: LocalDate, eveningHour: Int = 18): TodayView {
        val open = tasks.filter { it.isOpen && !it.someday }
        val overdue = open.filter { isOverdue(it, today) }.sortedWith(compareBy<Task>({ it.due }, { it.priority.rank }, { it.sortOrder }))
        val here = open.filter { !isOverdue(it, today) && (hasStarted(it, today) || dueToday(it, today)) }
        val (evening, day) = here.partition { t -> timeToday(t, today)?.let { it.hour >= eveningHour } == true }
        val dayOrder = compareBy<Task>({ timeToday(it, today) == null }, { timeToday(it, today) }).then(byPriorityThenOrder)
        return TodayView(overdue, day.sortedWith(dayOrder), evening.sortedWith(compareBy<Task> { timeToday(it, today) }.then(byPriorityThenOrder)))
    }

    /** The date a task waits for: its future start date, else its future deadline when it has no start yet. Null = not upcoming. */
    fun upcomingDate(t: Task, today: LocalDate): LocalDate? {
        if (!t.isOpen || t.someday) return null
        if (t.due != null && t.due.date <= today) return null          // due today or overdue: it is on Today, even with a later start (Phase 3 fix)
        if (t.start != null && t.start.date > today) return t.start.date
        if (t.start == null && t.due != null && t.due.date > today) return t.due.date
        return null
    }

    fun upcoming(tasks: List<Task>, today: LocalDate): List<Pair<LocalDate, List<Task>>> =
        tasks.mapNotNull { t -> upcomingDate(t, today)?.let { it to t } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .map { (d, ts) -> d to ts.sortedWith(compareBy<Task> { it.start?.time ?: it.due?.time ?: LocalTime.MIN }.then(byPriorityThenOrder)) }

    /**
     * The whole schedule across every list, in the order the owner asked for (17 Nov): overdue first, then tasks with no date, then the dated
     * ones by day (today included). A task sits on its start day, or on its deadline day when it has no start yet; a task that has already
     * started and is not overdue sits on today, as in Today. Subtasks without a date stay under their parent and are not listed alone.
     */
    fun schedule(tasks: List<Task>, today: LocalDate): ScheduleView {
        val open = tasks.filter { it.isOpen && !it.someday }
        val overdue = open.filter { isOverdue(it, today) }.sortedWith(compareBy<Task>({ it.due }, { it.priority.rank }, { it.sortOrder }))
        val noDate = open.filter { it.start == null && it.due == null && it.parentUid == null }.sortedWith(TaskOrder.comparator)
        val dated = open.filter { !isOverdue(it, today) }.mapNotNull { t ->
            val day = if (hasStarted(t, today) || dueToday(t, today)) today else upcomingDate(t, today)
            day?.let { it to t }
        }
        val days = dated.groupBy({ it.first }, { it.second }).toSortedMap().map { (d, ts) ->
            fun at(t: Task): LocalTime? = timeToday(t, d) ?: t.start?.takeIf { it.date == d }?.time ?: t.due?.takeIf { it.date == d }?.time
            d to ts.sortedWith(compareBy<Task>({ at(it) == null }, { at(it) }).then(byPriorityThenOrder))      // timed first, as in Today
        }
        return ScheduleView(overdue, noDate, days)
    }

    /** Days within [dayCount] days of today keep their own heading; later days are merged into one heading per week (Monday to Sunday), in date order. */
    fun bucketDays(days: List<Pair<LocalDate, List<Task>>>, today: LocalDate, dayCount: Int = 7): List<DayBucket> {
        val limit = today.plusDays(dayCount.toLong())
        val near = days.filter { it.first < limit }.map { DayBucket(it.first, false, it.second) }
        val far = days.filter { it.first >= limit }
            .groupBy { it.first.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)) }
            .toSortedMap().map { (monday, ds) -> DayBucket(monday, true, ds.flatMap { it.second }) }
        return near + far
    }

    /** Every task below [uid] (children, their children, ...), optionally only the open ones. A loop of parents written by another app cannot hang it. */
    fun descendants(tasks: List<Task>, uid: String, openOnly: Boolean): List<Task> {
        val byParent = tasks.filter { it.parentUid != null && !it.isOccurrenceCopy }.groupBy { it.parentUid!! }
        val seen = HashSet<String>().apply { add(uid) }
        val out = ArrayList<Task>()
        val queue = ArrayDeque<String>().apply { add(uid) }
        while (queue.isNotEmpty()) {
            for (child in byParent[queue.removeFirst()].orEmpty()) {
                if (!seen.add(child.uid)) continue
                if (!openOnly || child.isOpen) out.add(child)
                queue.add(child.uid)
            }
        }
        return out
    }

    /** Open tasks with no dates, not parked, not under another task: add now, sort later. [inboxListId] null = every list. */
    fun inbox(tasks: List<Task>, inboxListId: Long?): List<Task> =
        tasks.filter { it.isOpen && !it.someday && it.start == null && it.due == null && it.parentUid == null && (inboxListId == null || it.listId == inboxListId) }
            .sortedWith(TaskOrder.comparator)

    /** Waiting for (Phase 5): open tasks waiting on somebody, the nearest check-back date first (no date last). */
    fun waiting(tasks: List<Task>): List<Task> =
        tasks.filter { it.isOpen && it.waiting }.sortedWith(compareBy<Task>({ it.start == null }, { it.start }).then(TaskOrder.comparator))

    /** True when marking waiting until [checkBack] moves the task's deadline up (the dialog says so first). */
    fun deadlineMoves(t: Task, checkBack: java.time.LocalDate): Boolean = t.due != null && t.due.date < checkBack

    fun someday(tasks: List<Task>): List<Task> = tasks.filter { it.isOpen && it.someday }.sortedWith(TaskOrder.comparator)

    fun logbook(tasks: List<Task>, zone: ZoneId): List<LogDay> =
        tasks.filter { it.isDone }
            .map { t -> (t.completedAtMs ?: t.modifiedMs)?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } to t }
            .groupBy({ it.first }, { it.second })
            .map { (d, ts) -> LogDay(d, ts.sortedByDescending { it.completedAtMs ?: it.modifiedMs ?: 0L }) }
            .sortedWith(compareByDescending<LogDay> { it.date != null }.thenByDescending { it.date })

    /** One list's open tasks as a tree in manual order. Subtasks sit under their parent; a task whose parent is not in [tasks] is a top-level one. */
    fun listTree(tasks: List<Task>, listId: Long): List<TaskNode> {
        val mine = tasks.filter { it.listId == listId && it.isOpen && !it.someday }
        return buildTree(mine)
    }

    fun buildTree(tasks: List<Task>): List<TaskNode> {
        val byUid = tasks.associateBy { it.uid }
        val kids = tasks.filter { it.parentUid != null && it.parentUid in byUid && it.parentUid != it.uid }.groupBy { it.parentUid!! }
        fun node(t: Task, seen: Set<String>): TaskNode =      // [seen] guards against a loop of parents written by another app
            TaskNode(t, kids[t.uid].orEmpty().filter { it.uid !in seen && it.uid != t.uid }.sortedWith(TaskOrder.comparator).map { node(it, seen + t.uid) })
        val roots = tasks.filter { it.parentUid == null || it.parentUid !in byUid || it.parentUid == it.uid }
        val out = roots.sortedWith(TaskOrder.comparator).map { node(it, emptySet()) }.toMutableList()
        fun uids(n: TaskNode): List<String> = listOf(n.task.uid) + n.children.flatMap { uids(it) }
        val shown = out.flatMap { uids(it) }.toMutableSet()
        for (t in tasks.sortedWith(TaskOrder.comparator)) {      // a loop of parents: nothing may vanish
            if (t.uid !in shown) { val n = node(t, emptySet()); out.add(n); shown.addAll(uids(n)) }
        }
        return out
    }

    /** Counts for the drawer. */
    data class Counts(val today: Int, val overdue: Int, val inbox: Int, val upcoming: Int, val someday: Int)

    fun counts(tasks: List<Task>, today: LocalDate, inboxListId: Long?, eveningHour: Int = 18): Counts {
        val t = today(tasks, today, eveningHour)
        return Counts(t.today.size + t.evening.size, t.overdue.size, inbox(tasks, inboxListId).size, upcoming(tasks, today).sumOf { it.second.size }, someday(tasks).size)
    }
}

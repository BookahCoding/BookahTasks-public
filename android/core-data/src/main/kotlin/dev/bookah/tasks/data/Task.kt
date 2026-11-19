package dev.bookah.tasks.data

import biweekly.Biweekly
import biweekly.component.VTodo
import biweekly.property.DateOrDateTimeProperty
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A date, with a time of day when the task has one. Always in the phone's own zone (a floating time is read as local). */
data class TaskWhen(val date: LocalDate, val time: LocalTime?) : Comparable<TaskWhen> {
    override fun compareTo(other: TaskWhen): Int = compareValuesBy(this, other, { it.date }, { it.time ?: LocalTime.MIN })
}

enum class TaskStatus { NeedsAction, InProcess, Completed, Cancelled }

/** PRIORITY 1 to 4 = high, 5 = medium, 6 to 9 = low, 0 or missing = none (RFC 5545). */
enum class Priority(val rank: Int) { High(0), Medium(1), Low(2), None(3) }

/** One reminder: before the start or the deadline ([minutesBefore]) or at one moment ([absoluteMs]). [ours] is the `X-BOOKAH-REMINDER` choice code of a reminder BookahTasks wrote, null for another app's. */
data class Reminder(val minutesBefore: Int?, val relatedToDue: Boolean, val absoluteMs: Long?, val ours: String? = null)

/** A stored repeat rule as read. [raw] is the RRULE value as stored; [fromCompletion] is the second kind (`X-BOOKAH-REPEAT-FROM:COMPLETION`). */
data class RepeatRule(
    val frequency: String, val interval: Int, val byDay: List<String>, val count: Int?, val untilMs: Long?,
    val raw: String = "", val fromCompletion: Boolean = false,
)

/**
 * One task as the screens need it, read from the stored item text. [tags] are the tags to show: the two special tags
 * "someday" and "waiting" are lifted out into [someday] and [waiting] (plan, section 6).
 */
data class Task(
    val uid: String,
    val listId: Long,
    val href: String,
    val etag: String,
    val title: String,
    val notes: String?,
    val start: TaskWhen?,
    val due: TaskWhen?,
    val completedAtMs: Long?,
    val status: TaskStatus,
    val percent: Int,
    val priority: Priority,
    val tags: List<String>,
    val someday: Boolean,
    val waiting: Boolean,
    val parentUid: String?,
    val repeat: RepeatRule?,
    val reminders: List<Reminder>,
    val url: String?,
    /** X-APPLE-SORT-ORDER, or (like the Nextcloud web app) the seconds since 2001 of the creation time; without either [TaskOrder.NO_ORDER] (last; not checked against the web app). */
    val sortOrder: Long,
    val createdMs: Long?,
    val modifiedMs: Long?,
    /** A completed copy of one occurrence of a repeating task (it has a RECURRENCE-ID): only the Logbook shows it. */
    val isOccurrenceCopy: Boolean,
    /** `X-BOOKAH-REMINDERS:NONE`: the owner removed every reminder, so the defaults do not apply either. */
    val remindersOff: Boolean = false,
    /** The dates as stored, before a 00:00 start or a 23:59 deadline is read back as a date ([start], [due]); our alarm triggers count from these. */
    val storedStart: TaskWhen? = start,
    val storedDue: TaskWhen? = due,
    /** Phase 5: who a "waiting" task waits on (the first line of the notes, see [Waiting]); null when not waiting or nobody named. */
    val waitingFor: String? = null,
) {
    val isDone: Boolean get() = status == TaskStatus.Completed || status == TaskStatus.Cancelled
    val isOpen: Boolean get() = !isDone && !isOccurrenceCopy
    val repeats: Boolean get() = repeat != null
}

object TaskParser {
    private const val SECONDS_2001 = 978_307_200L

    /** All tasks in one stored object (normally one; a repeating task that has completed copies has several). A broken object gives an empty list. */
    fun parse(obj: StoredObject, zone: ZoneId): List<Task> {
        val cal = runCatching { Biweekly.parse(obj.ics).first() }.onFailure { failures.incrementAndGet() }.getOrNull() ?: return emptyList()
        return cal.todos.mapNotNull { runCatching { toTask(it, obj, zone, cal.timezoneInfo) }.onFailure { failures.incrementAndGet() }.getOrNull() }
    }

    /** How many items could not be read since the app started (shown in Settings; never what was in them). A silent failure here would hide every task. */
    val failures = java.util.concurrent.atomic.AtomicInteger(0)

    fun parseAll(objects: List<StoredObject>, zone: ZoneId): List<Task> = objects.flatMap { parse(it, zone) }

    private fun toTask(t: VTodo, obj: StoredObject, zone: ZoneId, tzi: biweekly.io.TimezoneInfo?): Task? {
        val uid = t.uid?.value ?: return null
        val completedMs = t.completed?.value?.time
        val statusText = t.status?.value?.uppercase()
        val status = when {
            statusText == "CANCELLED" -> TaskStatus.Cancelled
            statusText == "COMPLETED" || completedMs != null -> TaskStatus.Completed
            statusText == "IN-PROCESS" -> TaskStatus.InProcess
            else -> TaskStatus.NeedsAction
        }
        val rawTags = t.categories.flatMap { it.values }.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val someday = rawTags.any { it.equals("someday", true) }
        val waiting = rawTags.any { it.equals("waiting", true) }
        val parent = t.relatedTo.firstOrNull { r ->
            val type = r.relationshipType?.value
            type == null || type.equals("PARENT", true)
        }?.value?.trim()?.ifEmpty { null }
        val created = t.created?.value?.time
        val sort = t.getExperimentalProperty("X-APPLE-SORT-ORDER")?.value?.trim()?.toLongOrNull()
            ?: created?.let { it / 1000 - SECONDS_2001 } ?: TaskOrder.NO_ORDER          // neither: last (our choice, not checked against the web app)
        // The server wants DTSTART and DUE to be the same kind of value, so TaskEdit.setDates stores a start without a time beside a timed
        // deadline as 00:00, and a deadline without a time beside a timed start as 23:59. Read those back as the dates they were.
        val rawStart = whenOf(t.dateStart, zone, tzi)
        val rawDue = whenOf(t.dateDue, zone, tzi)
        val start = if (rawStart?.time == LocalTime.MIDNIGHT && rawDue?.time != null) rawStart.copy(time = null) else rawStart
        val due = if (rawDue?.time == LocalTime.of(23, 59) && rawStart?.time != null) rawDue.copy(time = null) else rawDue
        val notes = t.description?.value?.replace("\r\n", "\n")?.ifBlank { null }
        return Task(
            waitingFor = if (waiting) Waiting.personOf(notes) else null,
            uid = uid, listId = obj.listId, href = obj.href, etag = obj.etag,
            title = t.summary?.value.orEmpty(), notes = notes,
            start = start, due = due,
            completedAtMs = completedMs, status = status, percent = t.percentComplete?.value ?: if (status == TaskStatus.Completed) 100 else 0,
            priority = priorityOf(t.priority?.value),
            tags = rawTags.filterNot { it.equals("someday", true) || it.equals("waiting", true) },
            someday = someday, waiting = waiting, parentUid = parent,
            repeat = t.recurrenceRule?.value?.let { r ->
                RepeatRule(
                    r.frequency?.name ?: "", r.interval ?: 1, r.byDay.map { (if (it.num != null) it.num.toString() else "") + it.day.abbr }, r.count, r.until?.time,
                    raw = if (t.recurrenceId == null) runCatching { IcsPatcher.readProperty(obj.ics, "RRULE") }.getOrNull().orEmpty() else "",
                    fromCompletion = t.getExperimentalProperty(RepeatRules.FROM_PROPERTY)?.value?.trim().equals("COMPLETION", ignoreCase = true),
                )
            },
            reminders = t.alarms.mapNotNull { a ->
                val tr = a.trigger ?: return@mapNotNull null
                val d = tr.duration
                val ours = a.getExperimentalProperty(ReminderSet.MARK)?.value?.trim()?.ifEmpty { null }
                when {
                    d != null -> Reminder((-d.toMillis() / 60_000L).toInt(), tr.related?.value.equals("END", true), null, ours)
                    tr.date != null -> Reminder(null, false, tr.date.time, ours)
                    else -> null
                }
            },
            url = t.url?.value?.ifBlank { null },
            sortOrder = sort, createdMs = created, modifiedMs = t.lastModified?.value?.time,
            isOccurrenceCopy = t.recurrenceId != null,
            remindersOff = t.getExperimentalProperty(ReminderSet.OFF)?.value?.trim().equals("NONE", ignoreCase = true),
            storedStart = rawStart, storedDue = rawDue,
        )
    }

    private fun priorityOf(v: Int?): Priority = when (v) {
        null, 0 -> Priority.None
        in 1..4 -> Priority.High
        5 -> Priority.Medium
        in 6..9 -> Priority.Low
        else -> Priority.None
    }

    private fun whenOf(p: DateOrDateTimeProperty?, zone: ZoneId, tzi: biweekly.io.TimezoneInfo?): TaskWhen? {
        val d = p?.value ?: return null
        val c = d.rawComponents
        val date = LocalDate.of(c.year, c.month, c.date)
        if (!c.hasTime()) return TaskWhen(date, null)
        val assigned = tzi?.getTimezone(p)
        val tzid = assigned?.globalId ?: assigned?.timeZone?.id ?: p.parameters.timezoneId
        if (c.isUtc || tzid != null) {
            // a TZID that Java knows is applied directly (an item need not carry its own VTIMEZONE); otherwise the library's reading is used
            val named = tzid?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            val instant = if (named != null) java.time.LocalDateTime.of(c.year, c.month, c.date, c.hour, c.minute, c.second).atZone(named).toInstant() else java.time.Instant.ofEpochMilli(d.time)
            val z = instant.atZone(zone)
            return TaskWhen(z.toLocalDate(), z.toLocalTime().withSecond(0).withNano(0))
        }
        return TaskWhen(date, LocalTime.of(c.hour, c.minute))      // floating: the same wall-clock time on any phone
    }
}

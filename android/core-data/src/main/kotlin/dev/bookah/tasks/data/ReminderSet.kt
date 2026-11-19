package dev.bookah.tasks.data

import java.time.LocalTime
import java.time.ZoneId

/** One reminder the editor offers: [minutes] before the start or the deadline (0 = at it), or at one moment. */
sealed interface ReminderChoice {
    /** On a date-only start or deadline, "at" means [ReminderPlanner.MORNING] (09:00) that day. */
    data class Before(val toDue: Boolean, val minutes: Int) : ReminderChoice
    data class At(val ms: Long) : ReminderChoice
}

enum class ReminderSource { Default, Ours, Foreign }

/** A reminder as the editor lists it. [choice] for the defaults and ours; [foreign] for one written by another app (read-only). [atMs] = when it rings, null when its date is gone. */
data class ShownReminder(val source: ReminderSource, val choice: ReminderChoice?, val foreign: Reminder?, val atMs: Long?)

/** The effective reminders of one task (docs/phase-3-design.md section 3). [own] is the editable part: the defaults, or our own list. */
data class ReminderList(val usesDefaults: Boolean, val items: List<ShownReminder>) {
    val own: List<ReminderChoice> get() = items.filter { it.source != ReminderSource.Foreign }.mapNotNull { it.choice }
}

/**
 * Several reminders per task. Ours are VALARM blocks with `X-BOOKAH-REMINDER:<choice>` inside; a task with none of ours (and no
 * `X-BOOKAH-REMINDERS:NONE`) has the default reminders. Alarms of other apps always ring too and are never changed. One place decides what
 * the screen shows, what is written and what rings.
 */
object ReminderSet {
    const val MARK = "X-BOOKAH-REMINDER"
    const val OFF = "X-BOOKAH-REMINDERS"
    private const val MINUTE_MS = 60_000L
    private val CODE = Regex("^(START|DUE)-(\\d{1,6})$")

    /** The choices the editor offers, in its order. */
    val MENU: List<ReminderChoice.Before> = listOf(0, 10, 60, 1440).map { ReminderChoice.Before(false, it) } + listOf(0, 10, 60, 1440).map { ReminderChoice.Before(true, it) }

    fun code(c: ReminderChoice): String = when (c) {
        is ReminderChoice.Before -> (if (c.toDue) "DUE-" else "START-") + c.minutes
        is ReminderChoice.At -> "AT"
    }

    /** Our choice from its code (and, for a set moment, the trigger's time); null when the code is not one of ours. */
    private fun choiceOf(r: Reminder): ReminderChoice? {
        val code = r.ours?.uppercase() ?: return null
        if (code == "AT") return r.absoluteMs?.let { ReminderChoice.At(it) }
        val m = CODE.find(code) ?: return null
        return ReminderChoice.Before(m.groupValues[1] == "DUE", m.groupValues[2].toInt())
    }

    private fun ms(w: TaskWhen, zone: ZoneId, morning: LocalTime) = w.date.atTime(w.time ?: morning).atZone(zone).toInstant().toEpochMilli()

    /**
     * The defaults as choices (17 Nov rule): at the start; one hour before a timed deadline, or at 09:00 on a date-only deadline day. A
     * timed deadline already less than an hour away at [nowMs] gets "at the deadline" instead (the task was made late). The planner rings
     * exactly these, so the screen shows what rings and editing from them keeps the deadline reminder.
     */
    fun defaults(t: Task, zone: ZoneId, morning: LocalTime = ReminderPlanner.MORNING, nowMs: Long = System.currentTimeMillis()): List<ReminderChoice> {
        val out = ArrayList<ReminderChoice>()
        val startAt = t.start?.let { ms(it, zone, morning) }
        if (t.start != null) out += ReminderChoice.Before(false, 0)
        val due = t.due ?: return out
        val dueAt = ms(due, zone, morning)
        if (due.time != null) {
            val lead = dueAt - ReminderPlanner.DUE_LEAD_MINUTES * MINUTE_MS
            if (startAt == null || lead >= startAt) out += if (lead > nowMs) ReminderChoice.Before(true, ReminderPlanner.DUE_LEAD_MINUTES) else ReminderChoice.Before(true, 0)
        } else if (startAt == null || dueAt >= startAt) out += ReminderChoice.Before(true, 0)
        return out
    }

    fun of(t: Task, zone: ZoneId, morning: LocalTime = ReminderPlanner.MORNING, nowMs: Long = System.currentTimeMillis()): ReminderList {
        val ours = t.reminders.mapNotNull { r -> choiceOf(r) }
        val foreign = t.reminders.filter { choiceOf(it) == null }          // another app's, or ours with a code this version does not know
        val usesDefaults = ours.isEmpty() && !t.remindersOff
        val own = if (usesDefaults) defaults(t, zone, morning, nowMs) else ours
        val src = if (usesDefaults) ReminderSource.Default else ReminderSource.Ours
        return ReminderList(
            usesDefaults,
            own.map { ShownReminder(src, it, null, moment(it, t, zone, morning)) } + foreign.map { ShownReminder(ReminderSource.Foreign, null, it, foreignMoment(it, t, zone, morning)) },
        )
    }

    /** When a choice rings for this task, or null when the date it counts from is not there. */
    fun moment(c: ReminderChoice, t: Task, zone: ZoneId, morning: LocalTime = ReminderPlanner.MORNING): Long? = when (c) {
        is ReminderChoice.At -> c.ms
        is ReminderChoice.Before -> (if (c.toDue) t.due else t.start)?.let { ms(it, zone, morning) - c.minutes * MINUTE_MS }
    }

    /** When an alarm written by another app rings (a date-only value counts from [morning], as since Phase 1). */
    fun foreignMoment(r: Reminder, t: Task, zone: ZoneId, morning: LocalTime = ReminderPlanner.MORNING): Long? {
        r.absoluteMs?.let { return it }
        val m = r.minutesBefore ?: return null
        val base = (if (r.relatedToDue) t.due else t.start ?: t.due) ?: return null
        return ms(base, zone, morning) - m * MINUTE_MS
    }

    /** A duration in the standard's form, in hours and minutes only (exact, never a nominal day). */
    private fun duration(minutes: Long): String {
        if (minutes == 0L) return "PT0S"
        val a = Math.abs(minutes)
        val h = a / 60; val m = a % 60
        return (if (minutes < 0) "-" else "") + "PT" + (if (h > 0) "${h}H" else "") + (if (m > 0) "${m}M" else "")
    }

    /** The TRIGGER line for a choice, counted from the value as stored (so another app rings at the same moment), or null without its date. */
    private fun triggerLine(c: ReminderChoice, t: Task, zone: ZoneId): String? = when (c) {
        is ReminderChoice.At -> "TRIGGER;VALUE=DATE-TIME:" + IcsPatcher.stamp(java.time.Instant.ofEpochMilli(c.ms))
        is ReminderChoice.Before -> {
            val stored = if (c.toDue) t.storedDue else t.storedStart
            val at = moment(c, t, zone)
            if (stored == null || at == null) null
            else {
                val storedMs = stored.date.atTime(stored.time ?: LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli()
                "TRIGGER;RELATED=" + (if (c.toDue) "END" else "START") + ":" + duration((at - storedMs) / MINUTE_MS)
            }
        }
    }

    private fun block(c: ReminderChoice, trigger: String) = listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", trigger, "$MARK:${code(c)}", "END:VALARM")

    private fun parseTask(ics: String, zone: ZoneId): Task =
        TaskParser.parse(StoredObject("/x.ics", 0, "", ics), zone).firstOrNull { !it.isOccurrenceCopy } ?: throw IllegalArgumentException("no task")

    private fun markOf(block: List<String>): String? =
        block.firstOrNull { it.substringBefore(':').substringBefore(';').equals(MARK, ignoreCase = true) }?.substringAfter(':')?.trim()

    /** Our choice in one block, read the same way [TaskParser] reads it (code plus trigger), or null for another app's block. */
    private fun blockChoice(block: List<String>): ReminderChoice? {
        val code = markOf(block) ?: return null
        val trig = block.firstOrNull { it.uppercase().startsWith("TRIGGER") }
        val abs = trig?.takeIf { it.uppercase().contains("VALUE=DATE-TIME") }?.substringAfter(':')?.let { v ->
            runCatching { java.time.LocalDateTime.parse(v.trim().removeSuffix("Z"), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        }
        return choiceOf(Reminder(null, false, abs, code))
    }

    /** Makes our own list [wanted] (choices whose date is missing are dropped); an empty list writes `X-BOOKAH-REMINDERS:NONE`. */
    fun write(ics: String, wanted: List<ReminderChoice>, zone: ZoneId): String {
        val t = parseTask(ics, zone)
        val keep = wanted.distinct().filter { triggerLine(it, t, zone) != null }
        val blocks = IcsPatcher.alarmBlocks(ics)
        val left = keep.toMutableList()
        val kept = HashSet<Int>()
        for ((i, b) in blocks.withIndex()) { val c = blockChoice(b) ?: continue; if (left.remove(c)) kept.add(i) }
        var out = IcsPatcher.editAlarms(
            ics,
            // another app's block (or ours with a code we do not know), or ours still wanted: kept as it is
            edit = { i, b -> if (blockChoice(b) == null || i in kept) b else null },
            add = left.map { block(it, triggerLine(it, t, zone)!!) },
        )
        out = IcsPatcher.setProperty(out, OFF, if (keep.isEmpty()) "NONE" else null)
        return out
    }

    /** Back to the default reminders: our blocks and the NONE line go; other apps' alarms stay. */
    fun useDefaults(ics: String): String =
        IcsPatcher.setProperty(IcsPatcher.editAlarms(ics, edit = { _, b -> if (blockChoice(b) == null) b else null }), OFF, null)

    /** A repeating task moved by [delta] (local time): our set-moment reminders move with it, so the task never goes silent. */
    fun shiftMoments(ics: String, delta: java.time.Duration, zone: ZoneId): String {
        val blocks = runCatching { IcsPatcher.alarmBlocks(ics) }.getOrNull() ?: return ics
        if (blocks.none { blockChoice(it) is ReminderChoice.At }) return ics
        return IcsPatcher.editAlarms(ics, edit = { _, b ->
            val c = blockChoice(b) as? ReminderChoice.At ?: return@editAlarms b
            val moved = java.time.Instant.ofEpochMilli(c.ms).atZone(zone).toLocalDateTime().plus(delta).atZone(zone).toInstant().toEpochMilli()
            val k = b.indexOfFirst { it.uppercase().startsWith("TRIGGER") }
            b.toMutableList().apply { set(k, "TRIGGER;VALUE=DATE-TIME:" + IcsPatcher.stamp(java.time.Instant.ofEpochMilli(moved))) }
        })
    }

    /**
     * After the dates changed: our reminders on a removed date go, and our triggers follow a change of kind (date-only or timed). When that
     * removes the last of ours, `X-BOOKAH-REMINDERS:NONE` is written: the owner chose his own list, so the defaults do not come back unasked.
     */
    fun followDates(ics: String, zone: ZoneId): String {
        val blocks = runCatching { IcsPatcher.alarmBlocks(ics) }.getOrNull() ?: return ics
        if (blocks.none { blockChoice(it) != null }) return ics
        val t = parseTask(ics, zone)
        val out = IcsPatcher.editAlarms(ics, edit = { _, b ->
            val c = blockChoice(b) as? ReminderChoice.Before ?: return@editAlarms b          // another app's, or a set moment: unchanged
            val line = triggerLine(c, t, zone) ?: return@editAlarms null                     // its date is gone
            val k = b.indexOfFirst { it.uppercase().startsWith("TRIGGER") }
            when {
                k < 0 -> b.toMutableList().apply { add(size - 1, line) }
                sameTrigger(b[k], line) -> b
                else -> b.toMutableList().apply { set(k, line) }
            }
        })
        val left = IcsPatcher.alarmBlocks(out).count { blockChoice(it) != null }
        return if (left == 0) IcsPatcher.setProperty(out, OFF, "NONE") else out
    }

    /** Two trigger lines that ring at the same offset from the same date (another app may write PT9H0M0S for our PT9H). */
    private fun sameTrigger(a: String, b: String): Boolean {
        fun rel(s: String) = if (s.substringBefore(':').uppercase().contains("RELATED=END")) "END" else "START"
        fun mins(s: String) = runCatching { biweekly.util.Duration.parse(s.substringAfter(':').trim()).toMillis() / MINUTE_MS }.getOrNull()
        return rel(a) == rel(b) && mins(a) != null && mins(a) == mins(b)
    }
}

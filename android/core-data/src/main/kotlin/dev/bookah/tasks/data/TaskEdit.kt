package dev.bookah.tasks.data

import biweekly.Biweekly
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.TimeZone

/** What ticking a task did: it is finished ([Done]) or, being a repeating task, it moved on to its next date and stays open ([Advanced]). */
sealed interface Completion {
    val ics: String
    data class Done(override val ics: String) : Completion
    data class Advanced(override val ics: String) : Completion
    /** The next date could not be worked out (an impossible rule, or a search that would take too long): [ics] is the text as it was; nothing is written. */
    data class Unchanged(override val ics: String) : Completion
}

/**
 * The changes the editor makes, each one a function from a task's stored text to its new text, built on [IcsPatcher] so every line the
 * owner did not change stays as it was. Dates are written in UTC (timed) or as plain dates, so no time-zone block is ever needed.
 */
object TaskEdit {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun setTitle(ics: String, title: String): String {
        require(title.isNotBlank()) { "A task needs a title" }
        return IcsPatcher.setProperty(ics, "SUMMARY", IcsPatcher.escapeText(title.trim()), keepParams = true)
    }

    fun setNotes(ics: String, notes: String?): String =
        IcsPatcher.setProperty(ics, "DESCRIPTION", notes?.takeIf { it.isNotBlank() }?.let { IcsPatcher.escapeText(it.trim()) }, keepParams = true)

    /**
     * Writes the start (DTSTART) and deadline (DUE). The server requires both to be the same kind of value (two dates or two date-times),
     * so a mixed pair is made timed: a start without a time begins at 00:00, a deadline without a time ends at 23:59. A deadline earlier
     * than the start is moved up to the start.
     */
    fun setDates(ics: String, start: TaskWhen?, due: TaskWhen?, zone: ZoneId): String {
        var s = start
        var d = due
        if (s != null && d != null && (s.time == null) != (d.time == null)) {
            if (s.time == null) s = s.copy(time = LocalTime.MIDNIGHT) else d = d.copy(time = LocalTime.of(23, 59))
        }
        if (s != null && d != null && d < s) d = s
        // a repeat needs a date: a rule is never stored without one (the screen says so before it gets here)
        require(s != null || d != null || IcsPatcher.readProperty(ics, "RRULE") == null) { "A repeating task needs a start or a deadline" }
        val out = writeWhen(writeWhen(ics, "DTSTART", s, zone), "DUE", d, zone)
        val dateOnly = (s ?: d)?.let { it.time == null }
        return ReminderSet.followDates(syncUntil(out, dateOnly, zone), zone)
    }

    /** UNTIL must be the same kind of value as the dates (a date, or a UTC time): rewritten when the dates changed kind. */
    private fun syncUntil(ics: String, dateOnly: Boolean?, zone: ZoneId): String {
        if (dateOnly == null) return ics
        val raw = IcsPatcher.readProperty(ics, "RRULE") ?: return ics
        val m = Regex("(?i)(UNTIL=)([0-9TZ]+)").find(raw) ?: return ics
        val isDate = !m.groupValues[2].contains('T', ignoreCase = true)
        if (isDate == dateOnly) return ics
        val day = RepeatRules.untilDay(m.groupValues[2], zone) ?: return ics
        val fixed = raw.replaceRange(m.groups[2]!!.range, RepeatRules.untilText(day, dateOnly, zone))
        return IcsPatcher.setProperty(ics, "RRULE", fixed, keepParams = true)
    }

    /**
     * Sets the repeat rule (null = does not repeat): the RRULE line and, for the second kind, `X-BOOKAH-REPEAT-FROM:COMPLETION`. A repeating
     * task needs a date, so a task with neither start nor deadline is given a start date ([RepeatRules.firstDate] from [today]) here, not
     * only on the screen. Every other line stays as it was.
     */
    fun setRepeat(ics: String, spec: RepeatSpec?, zone: ZoneId, today: LocalDate): String {
        if (spec == null) return IcsPatcher.setProperty(IcsPatcher.setProperty(ics, "RRULE", null), RepeatRules.FROM_PROPERTY, null)
        var out = ics
        var t = TaskParser.parse(StoredObject("/x.ics", 0, "", out), zone).firstOrNull { !it.isOccurrenceCopy } ?: throw IllegalArgumentException("no task")
        if (t.start == null && t.due == null) {
            out = setDates(out, TaskWhen(RepeatRules.firstDate(spec, today), null), null, zone)
            t = TaskParser.parse(StoredObject("/x.ics", 0, "", out), zone).first { !it.isOccurrenceCopy }
        }
        // UNTIL takes the kind of the dates as STORED (a date-only start beside a timed deadline is stored as 00:00, a time)
        val dateOnly = (t.storedStart ?: t.storedDue)!!.time == null
        spec.problem((t.start ?: t.due)!!.date)?.let { throw IllegalArgumentException(it) }
        out = IcsPatcher.setProperty(out, "RRULE", RepeatRules.rrule(spec, dateOnly, zone))
        return IcsPatcher.setProperty(out, RepeatRules.FROM_PROPERTY, if (spec.fromCompletion) "COMPLETION" else null)
    }

    private fun writeWhen(ics: String, name: String, w: TaskWhen?, zone: ZoneId): String = when {
        w == null -> IcsPatcher.setProperty(ics, name, null)
        w.time == null -> IcsPatcher.setProperty(ics, name, dateFormat.format(w.date), params = "VALUE=DATE")
        else -> IcsPatcher.setProperty(ics, name, IcsPatcher.stamp(w.date.atTime(w.time).atZone(zone).toInstant()))
    }

    /** The link (standard URL property; Phase 5 share). A value with a space or a control character is refused (the text stays as it was). */
    fun setUrl(ics: String, url: String?): String {
        if (url == null) return IcsPatcher.setProperty(ics, "URL", null)
        val u = url.trim()
        if (u.isEmpty() || u.length > SharedText.MAX_URL || u.any { it.isISOControl() || it.isWhitespace() }) return ics
        return IcsPatcher.setProperty(ics, "URL", u)
    }

    /** The manual order ([TaskOrder]): only the X-APPLE-SORT-ORDER line changes. */
    fun setSortOrder(ics: String, value: Long): String {
        require(value in 1..TaskOrder.MAX) { "order out of range" }
        return IcsPatcher.setProperty(ics, "X-APPLE-SORT-ORDER", value.toString())
    }

    fun setPriority(ics: String, p: Priority): String =
        IcsPatcher.setProperty(ics, "PRIORITY", when (p) { Priority.High -> "1"; Priority.Medium -> "5"; Priority.Low -> "9"; Priority.None -> null })

    /** Replaces the visible tags; the hidden "someday" and "waiting" tags are kept as they are. */
    fun setTags(ics: String, tags: List<String>): String = rewriteCategories(ics, visible = tags, someday = null)

    fun setSomeday(ics: String, on: Boolean): String = rewriteCategories(ics, visible = null, someday = on)

    /**
     * Waiting for (plan section 6, docs/phase-5-design.md section 4): tag `waiting`, the check-back date as the start (a date), the person as
     * the first line of the notes (`Waiting for: <person>`; every other line kept). A deadline before the check-back date moves up to it
     * (a deadline is never before the start), so the task leaves Today and Overdue until then. A repeating task cannot wait.
     */
    fun setWaiting(ics: String, person: String?, checkBack: LocalDate, zone: ZoneId): String {
        require(IcsPatcher.readProperty(ics, "RRULE") == null) { "A repeating task cannot wait" }
        val t = TaskParser.parse(StoredObject("/x.ics", 0, "", ics), zone).firstOrNull { !it.isOccurrenceCopy } ?: throw IllegalArgumentException("no task")
        var out = rewriteCategories(ics, visible = null, someday = null, waiting = true)
        val due = t.due?.let { d -> if (d.date < checkBack) d.copy(date = checkBack) else d }
        out = setDates(out, TaskWhen(checkBack, null), due, zone)
        return setNotes(out, Waiting.withPerson(t.notes, person, wasWaiting = t.waiting))
    }

    /** Ends waiting: the tag and the person line go; the start date stays. */
    fun endWaiting(ics: String): String {
        val t = TaskParser.parse(StoredObject("/x.ics", 0, "", ics), ZoneId.of("UTC")).firstOrNull { !it.isOccurrenceCopy } ?: return ics
        val out = rewriteCategories(ics, visible = null, someday = null, waiting = false)
        val notes = Waiting.withPerson(t.notes, null, wasWaiting = t.waiting)
        return if (notes == t.notes) out else setNotes(out, notes)
    }

    private fun rewriteCategories(ics: String, visible: List<String>?, someday: Boolean?, waiting: Boolean? = null): String {
        val existing = IcsPatcher.readAllProperties(ics, "CATEGORIES").flatMap { splitTags(it) }
        fun special(n: String) = existing.any { it.equals(n, ignoreCase = true) }
        val keepSomeday = someday ?: special("someday")
        val keepWaiting = waiting ?: special("waiting")
        val shown = (visible ?: existing.filterNot { it.equals("someday", true) || it.equals("waiting", true) })
            .map { it.trim() }.filter { it.isNotEmpty() && !it.equals("someday", true) && !it.equals("waiting", true) }.distinctBy { it.lowercase() }
        val all = shown + listOfNotNull("someday".takeIf { keepSomeday }, "waiting".takeIf { keepWaiting })
        return IcsPatcher.setProperty(ics, "CATEGORIES", all.takeIf { it.isNotEmpty() }?.joinToString(",") { IcsPatcher.escapeText(it) })
    }

    /** Splits a CATEGORIES value on commas that are not escaped, and unescapes each part. */
    private fun splitTags(value: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' && i + 1 < value.length -> { cur.append(value[i + 1]); i++ }
                c == ',' -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Ticks a task. A plain task is finished (STATUS, COMPLETED, PERCENT-COMPLETE). A repeating task moves its start and deadline together
     * to the next occurrence and stays open, one task per series; when COUNT or UNTIL is used up it is finished instead.
     */
    fun complete(ics: String, now: Instant, zone: ZoneId): Completion {
        when (val a = advance(ics, now, zone)) {
            is Step.Moved -> return Completion.Advanced(a.ics)
            Step.Unknown -> return Completion.Unchanged(ics)          // never finish another app's series because we could not work it out
            Step.Ended, Step.NotRepeating -> {}
        }
        val done = IcsPatcher.setProperty(IcsPatcher.setProperty(IcsPatcher.setProperty(ics, "STATUS", "COMPLETED"), "COMPLETED", IcsPatcher.stamp(now)), "PERCENT-COMPLETE", "100")
        return Completion.Done(IcsPatcher.touch(done, now))
    }

    fun reopen(ics: String, now: Instant): String {
        val open = IcsPatcher.setProperty(IcsPatcher.setProperty(IcsPatcher.setProperty(ics, "STATUS", "NEEDS-ACTION"), "COMPLETED", null), "PERCENT-COMPLETE", null)
        return IcsPatcher.touch(open, now)
    }

    /** How many occurrences a tick may look through, and for how long, before it gives up (a dense old rule must not hang a tick). */
    private const val SEARCH_LIMIT = 100_000
    private const val SEARCH_MS = 2_000L

    /** What a tick does to a repeating task: moves it, ends the series for good (COUNT or UNTIL used up), cannot tell, or it does not repeat. */
    private sealed interface Step {
        data class Moved(val ics: String) : Step
        data object Ended : Step
        data object Unknown : Step
        data object NotRepeating : Step
    }

    /** The next occurrence and how many occurrences after the old date it passed ([steps], for COUNT). */
    private class Found(val at: LocalDateTime, val steps: Int)

    /**
     * Moves a repeating task to its next date (docs/phase-3-design.md section 2). Kind 1 (fixed schedule): one step after the current date,
     * or, when the task is overdue (its deadline, or without one its start, is before today; the owner's ruling, 19 Nov 2026), to the first occurrence
     * after today, COUNT going down by every occurrence passed. Kind 2 (`X-BOOKAH-REPEAT-FROM:COMPLETION`): counted from the tick. Start and
     * deadline move together and keep their time of day; our set-moment reminders move with them.
     */
    private fun advance(ics: String, now: Instant, zone: ZoneId): Step {
        val vtodo = Biweekly.parse(ics).first().todos.firstOrNull { it.recurrenceId == null } ?: return Step.NotRepeating
        val rule = vtodo.recurrenceRule?.value ?: return Step.NotRepeating
        val task = TaskParser.parse(StoredObject("/x.ics", 0, "", ics), zone).firstOrNull { !it.isOccurrenceCopy } ?: return Step.NotRepeating
        val base = task.start ?: task.due ?: return Step.NotRepeating
        val count = rule.count
        if (count != null && count <= 1) return Step.Ended
        val oldLocal = LocalDateTime.of(base.date, base.time ?: LocalTime.MIDNIGHT)
        val today = now.atZone(zone).toLocalDate()
        val overdue = (task.due?.date ?: base.date) < today
        val found = if (task.repeat?.fromCompletion == true) nextFromCompletion(rule, base, now, zone)?.let { Found(it, 1) }
        else when (val s = nextOnSchedule(rule, oldLocal, zone, if (overdue) today else null)) {
            is Found -> s
            else -> return if (s == null && (count != null || rule.until != null)) Step.Ended else Step.Unknown
        }
        if (found == null) return if (count != null || rule.until != null) Step.Ended else Step.Unknown
        if (count != null && count - found.steps < 1) return Step.Ended
        val storedDateOnly = (task.storedStart ?: task.storedDue)?.time == null
        if (!withinUntil(rule, found.at, storedDateOnly, zone)) return Step.Ended
        val nextLocal = found.at
        val delta = java.time.Duration.between(oldLocal, nextLocal)
        fun shifted(w: TaskWhen?): TaskWhen? = w?.let {
            val moved = LocalDateTime.of(it.date, it.time ?: LocalTime.MIDNIGHT).plus(delta)
            TaskWhen(moved.toLocalDate(), if (it.time == null) null else moved.toLocalTime())
        }
        var out = setDates(ics, shifted(task.start), shifted(task.due), zone)
        out = ReminderSet.shiftMoments(out, delta, zone)
        out = IcsPatcher.setProperty(out, "STATUS", "NEEDS-ACTION")
        out = IcsPatcher.setProperty(IcsPatcher.setProperty(out, "COMPLETED", null), "PERCENT-COMPLETE", null)
        if (count != null) {
            val raw = IcsPatcher.readProperty(out, "RRULE")
            if (raw != null) out = IcsPatcher.setProperty(out, "RRULE", raw.replace(Regex("COUNT=\\d+", RegexOption.IGNORE_CASE), "COUNT=${count - found.steps}"))
        }
        return Step.Moved(IcsPatcher.touch(out, now))
    }

    /**
     * Kind 1: the first occurrence after [from], or with [afterDay] the first one on a later day than it (an overdue task). Returns null
     * when the rule ran out (COUNT, UNTIL, or no occurrence at all), and [Step.Unknown] when the search hit its bounds.
     */
    private fun nextOnSchedule(rule: biweekly.util.Recurrence, from: LocalDateTime, zone: ZoneId, afterDay: LocalDate?): Any? {
        val startDate = Date.from(from.atZone(zone).toInstant())
        val it = rule.getDateIterator(startDate, TimeZone.getTimeZone(zone))
        val deadline = System.currentTimeMillis() + SEARCH_MS
        var n = 0
        var steps = 0
        while (true) {
            if (n++ >= SEARCH_LIMIT || System.currentTimeMillis() > deadline) return Step.Unknown
            if (!it.hasNext()) return null
            val d = it.next()
            if (!d.after(startDate)) continue
            steps++
            val local = LocalDateTime.ofInstant(d.toInstant(), zone)
            if (afterDay == null || local.toLocalDate() > afterDay) return Found(local, steps)
        }
    }

    /**
     * Kind 2: a plain interval adds N units to the local date of the tick (calendar arithmetic: 31 Jan + 1 month = 28 Feb); a rule with BY
     * parts (chosen weekdays, the nth weekday) is anchored at the tick and gives its first occurrence on a later day. The time of day stays.
     */
    private fun nextFromCompletion(rule: biweekly.util.Recurrence, base: TaskWhen, now: Instant, zone: ZoneId): LocalDateTime? {
        val done = now.atZone(zone)
        val day = done.toLocalDate()
        val time = base.time ?: LocalTime.MIDNIGHT
        val n = (rule.interval ?: 1).coerceAtLeast(1).toLong()
        val hasBy = rule.byDay.isNotEmpty() || rule.byMonthDay.isNotEmpty() || rule.byMonth.isNotEmpty() || rule.byYearDay.isNotEmpty() ||
            rule.byWeekNo.isNotEmpty() || rule.bySetPos.isNotEmpty() || rule.byHour.isNotEmpty() || rule.byMinute.isNotEmpty()
        val f = rule.frequency ?: return null
        if (!hasBy) return when (f) {
            biweekly.util.Frequency.DAILY -> day.plusDays(n).atTime(time)
            biweekly.util.Frequency.WEEKLY -> day.plusWeeks(n).atTime(time)
            biweekly.util.Frequency.MONTHLY -> day.plusMonths(n).atTime(time)
            biweekly.util.Frequency.YEARLY -> day.plusYears(n).atTime(time)
            biweekly.util.Frequency.HOURLY -> done.plusHours(n).toLocalDateTime().withSecond(0).withNano(0)
            biweekly.util.Frequency.MINUTELY -> done.plusMinutes(n).toLocalDateTime().withSecond(0).withNano(0)
            biweekly.util.Frequency.SECONDLY -> done.plusMinutes(1).toLocalDateTime().withSecond(0).withNano(0)
        }
        val anchor = Date.from(day.atTime(time).atZone(zone).toInstant())
        val it = rule.getDateIterator(anchor, TimeZone.getTimeZone(zone))
        var k = 0
        val deadline = System.currentTimeMillis() + SEARCH_MS
        while (k++ < SEARCH_LIMIT && System.currentTimeMillis() <= deadline && it.hasNext()) {
            val d = LocalDateTime.ofInstant(it.next().toInstant(), zone)
            if (d.toLocalDate() > day) return d
        }
        return null
    }

    /** False when [next] falls after the rule's UNTIL (a date includes its whole day). */
    private fun withinUntil(rule: biweekly.util.Recurrence, next: LocalDateTime, dateOnly: Boolean, zone: ZoneId): Boolean {
        val u = rule.until ?: return true
        val c = u.rawComponents ?: return !next.atZone(zone).toInstant().isAfter(u.toInstant())
        if (!c.hasTime()) return !next.toLocalDate().isAfter(LocalDate.of(c.year, c.month, c.date))
        val limit = LocalDateTime.of(c.year, c.month, c.date, c.hour, c.minute, c.second).let { if (c.isUtc) it.atZone(java.time.ZoneOffset.UTC) else it.atZone(zone) }.toInstant()
        val at = if (dateOnly) next.toLocalDate().atStartOfDay(zone).toInstant() else next.atZone(zone).toInstant()
        return !at.isAfter(limit)
    }

    /** Our own reminder list (see [ReminderSet.write]); other apps' alarms are kept as they are. */
    fun setReminders(ics: String, wanted: List<ReminderChoice>, zone: ZoneId): String = ReminderSet.write(ics, wanted, zone)

    /** Back to the default reminders (see [ReminderSet.useDefaults]). */
    fun useDefaultReminders(ics: String): String = ReminderSet.useDefaults(ics)

    /** A new open task that is a subtask of [parentUid]. */
    fun newSubtask(parentUid: String, uid: String, title: String, now: Instant): String =
        IcsPatcher.setProperty(IcsPatcher.newTask(uid, title, now), "RELATED-TO", parentUid)       // a new task has no other RELATED-TO line to lose
}

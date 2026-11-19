package dev.bookah.tasks

import android.content.res.Resources
import dev.bookah.tasks.data.Priority
import dev.bookah.tasks.data.Reminder
import dev.bookah.tasks.data.ReminderChoice
import dev.bookah.tasks.data.RepeatEnd
import dev.bookah.tasks.data.RepeatFreq
import dev.bookah.tasks.data.RepeatRule
import dev.bookah.tasks.data.RepeatRules
import dev.bookah.tasks.data.RepeatSpec
import dev.bookah.tasks.data.Task
import dev.bookah.tasks.data.TaskWhen
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Turns dates, reminders and repeat rules into words in the phone's language (English or Greek come from the string resources). */
class Words(private val res: Resources, private val locale: Locale = Locale.getDefault()) {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private fun s(id: Int, vararg a: Any) = res.getString(id, *a)

    /** "Today", "Tomorrow", "Yesterday", else "Wed 18 Nov" (with the year when it is not this year). */
    fun day(d: LocalDate, today: LocalDate): String = when (d) {
        today -> s(R.string.word_today)
        today.plusDays(1) -> s(R.string.word_tomorrow)
        today.minusDays(1) -> s(R.string.word_yesterday)
        else -> DateTimeFormatter.ofPattern(if (d.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", locale).format(d)
    }

    /** A day heading for the Upcoming and Logbook screens: "Tomorrow" or "Wednesday 18 November". */
    fun heading(d: LocalDate, today: LocalDate): String = when (d) {
        today, today.plusDays(1), today.minusDays(1) -> day(d, today)
        else -> DateTimeFormatter.ofPattern(if (d.year == today.year) "EEEE d MMMM" else "EEEE d MMMM yyyy", locale).format(d)
    }

    /** "Week of 30 November": the heading for a later week on Upcoming and Schedule. */
    fun weekOf(monday: LocalDate, today: LocalDate): String =
        s(R.string.week_of, DateTimeFormatter.ofPattern(if (monday.year == today.year) "d MMMM" else "d MMMM yyyy", locale).format(monday))

    /** "Monday 16 November": today's date in words, for the head of the Today screen. */
    fun fullDate(d: LocalDate): String = DateTimeFormatter.ofPattern("EEEE d MMMM", locale).format(d)

    fun whenText(w: TaskWhen, today: LocalDate): String = if (w.time == null) day(w.date, today) else s(R.string.word_at, day(w.date, today), timeFmt.format(w.time))

    fun time(w: TaskWhen): String? = w.time?.let { timeFmt.format(it) }

    fun duration(minutes: Int): String = when {
        minutes < 60 -> s(R.string.dur_minutes, minutes)
        minutes < 24 * 60 && minutes % 60 == 0 -> s(R.string.dur_hours, minutes / 60)
        minutes < 24 * 60 -> s(R.string.dur_hours_minutes, minutes / 60, minutes % 60)
        minutes % (24 * 60) == 0 -> if (minutes / (24 * 60) == 1) s(R.string.dur_day) else s(R.string.dur_days, minutes / (24 * 60))
        else -> s(R.string.dur_hours_minutes, minutes / 60, minutes % 60)
    }

    fun reminder(r: Reminder, task: Task, zone: ZoneId): String {
        r.absoluteMs?.let { ms ->
            val z = Instant.ofEpochMilli(ms).atZone(zone)
            return s(R.string.reminder_absolute, whenText(TaskWhen(z.toLocalDate(), z.toLocalTime().withSecond(0).withNano(0)), LocalDate.now(zone)))
        }
        val m = r.minutesBefore ?: return s(R.string.reminder_at_time)
        if (m == 0) return s(R.string.reminder_at_time)
        val toDeadline = r.relatedToDue || (task.start == null && task.due != null)
        return s(if (toDeadline) R.string.reminder_before_deadline else R.string.reminder_before_start, duration(m))
    }

    /** A stored rule in words: through the editor's own reading when it can show it, else the older summary (custom rules from other apps). */
    fun repeat(rule: RepeatRule, today: LocalDate): String {
        RepeatRules.parse(rule.raw, rule.fromCompletion, ZoneId.systemDefault())?.let { return repeat(it, today) }
        // a rule with parts the editor cannot show (BYMONTH, BYSETPOS, ...): never summarised as something simpler than it is
        val text = if (rule.raw.isNotBlank()) s(R.string.repeat_other) else repeatOld(rule, today)
        return if (rule.fromCompletion) s(R.string.repeat_after_done, text) else text
    }

    /** "Every 2 weeks, on Monday, Thursday, until Mon 30 Nov", "Every month, on the third Wednesday", "... counted from when it is done". */
    fun repeat(spec: RepeatSpec, today: LocalDate): String {
        val n = spec.interval.coerceAtLeast(1)
        var text = when (spec.freq) {
            RepeatFreq.Daily -> if (n == 1) s(R.string.repeat_every_day) else s(R.string.repeat_every_n_days, n)
            RepeatFreq.Weekly -> if (n == 1) s(R.string.repeat_every_week) else s(R.string.repeat_every_n_weeks, n)
            RepeatFreq.Monthly -> if (n == 1) s(R.string.repeat_every_month) else s(R.string.repeat_every_n_months, n)
            RepeatFreq.Yearly -> if (n == 1) s(R.string.repeat_every_year) else s(R.string.repeat_every_n_years, n)
        }
        if (spec.freq == RepeatFreq.Weekly && spec.weekdays.isNotEmpty()) text = s(R.string.repeat_on, text, dayList(DayOfWeek.entries.filter { it in spec.weekdays }))
        val nthN = spec.monthlyNth
        val nthDay = spec.monthlyWeekday
        if (spec.freq == RepeatFreq.Monthly && nthN != null && nthDay != null) {
            text = s(R.string.repeat_nth, text, nth(nthN, nthDay), nthDay.getDisplayName(TextStyle.FULL, locale))
        }
        when (val e = spec.end) {
            is RepeatEnd.Count -> text = res.getQuantityString(R.plurals.repeat_times_n, e.times, text, e.times)
            is RepeatEnd.Until -> text = s(R.string.repeat_until, text, day(e.day, today))
            RepeatEnd.Never -> {}
        }
        return if (spec.fromCompletion) s(R.string.repeat_after_done, text) else text
    }

    /** "third" / "την τρίτη" (Greek: Saturday is neuter, the other days feminine). */
    fun nth(n: Int, d: DayOfWeek): String {
        val neuter = d == DayOfWeek.SATURDAY
        return s(when (n) {
            1 -> if (neuter) R.string.repeat_nth_n_1 else R.string.repeat_nth_f_1
            2 -> if (neuter) R.string.repeat_nth_n_2 else R.string.repeat_nth_f_2
            3 -> if (neuter) R.string.repeat_nth_n_3 else R.string.repeat_nth_f_3
            4 -> if (neuter) R.string.repeat_nth_n_4 else R.string.repeat_nth_f_4
            else -> if (neuter) R.string.repeat_nth_n_last else R.string.repeat_nth_f_last
        })
    }

    fun weekdayShort(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, locale)

    /** "Monday and Thursday" / "τη Δευτέρα και την Πέμπτη" (Greek needs the article in the accusative, so the names come from the strings). */
    private fun dayList(days: List<DayOfWeek>): String {
        val names = days.map { s(when (it) {
            DayOfWeek.MONDAY -> R.string.repeat_wd_1; DayOfWeek.TUESDAY -> R.string.repeat_wd_2; DayOfWeek.WEDNESDAY -> R.string.repeat_wd_3
            DayOfWeek.THURSDAY -> R.string.repeat_wd_4; DayOfWeek.FRIDAY -> R.string.repeat_wd_5; DayOfWeek.SATURDAY -> R.string.repeat_wd_6
            DayOfWeek.SUNDAY -> R.string.repeat_wd_7
        }) }
        return if (names.size == 1) names[0] else s(R.string.repeat_list_two, names.dropLast(1).joinToString(", "), names.last())
    }

    /** One of the editor's reminder choices in words: "At the start", "1 hour before the deadline", "At Fri 20 Nov at 17:00". */
    fun reminderChoice(c: ReminderChoice, zone: ZoneId): String = when (c) {
        is ReminderChoice.At -> Instant.ofEpochMilli(c.ms).atZone(zone).let { z -> s(R.string.reminder_absolute, whenText(TaskWhen(z.toLocalDate(), z.toLocalTime().withSecond(0).withNano(0)), LocalDate.now(zone))) }
        is ReminderChoice.Before -> when {
            c.minutes == 0 -> s(if (c.toDue) R.string.reminder_at_deadline else R.string.reminder_at_start)
            else -> s(if (c.toDue) R.string.reminder_before_deadline else R.string.reminder_before_start, duration(c.minutes))
        }
    }

    /** When a reminder rings, in words ("Tomorrow at 09:00"). */
    fun moment(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).let { z -> whenText(TaskWhen(z.toLocalDate(), z.toLocalTime().withSecond(0).withNano(0)), LocalDate.now(zone)) }

    private fun repeatOld(rule: RepeatRule, today: LocalDate): String {
        val n = rule.interval.coerceAtLeast(1)
        var text = when (rule.frequency) {
            "DAILY" -> if (n == 1) s(R.string.repeat_every_day) else s(R.string.repeat_every_n_days, n)
            "WEEKLY" -> if (n == 1) s(R.string.repeat_every_week) else s(R.string.repeat_every_n_weeks, n)
            "MONTHLY" -> if (n == 1) s(R.string.repeat_every_month) else s(R.string.repeat_every_n_months, n)
            "YEARLY" -> if (n == 1) s(R.string.repeat_every_year) else s(R.string.repeat_every_n_years, n)
            else -> return s(R.string.repeat_other)
        }
        val days = rule.byDay.mapNotNull { code -> dayOfWeek(code.filter { it.isLetter() }) }.distinct()
        if (days.isNotEmpty() && rule.frequency == "WEEKLY") text = s(R.string.repeat_on, text, dayList(days))
        rule.count?.let { text = res.getQuantityString(R.plurals.repeat_times_n, it, text, it) }
        rule.untilMs?.let { text = s(R.string.repeat_until, text, day(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate(), today)) }
        return text
    }

    fun priority(p: Priority): String? = when (p) {
        Priority.High -> s(R.string.priority_high)
        Priority.Medium -> s(R.string.priority_medium)
        Priority.Low -> s(R.string.priority_low)
        Priority.None -> null
    }

    private fun dayOfWeek(code: String): DayOfWeek? = when (code.uppercase()) {
        "MO" -> DayOfWeek.MONDAY; "TU" -> DayOfWeek.TUESDAY; "WE" -> DayOfWeek.WEDNESDAY; "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY; "SA" -> DayOfWeek.SATURDAY; "SU" -> DayOfWeek.SUNDAY
        else -> null
    }
}

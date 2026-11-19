package dev.bookah.tasks.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** How often a task repeats (the editor's four choices). Copied from BookahCalendar's RepeatFreq and adapted. */
enum class RepeatFreq { Daily, Weekly, Monthly, Yearly }

sealed interface RepeatEnd {
    data object Never : RepeatEnd

    /** The last day an occurrence may fall on (that day is included). */
    data class Until(val day: LocalDate) : RepeatEnd
    data class Count(val times: Int) : RepeatEnd
}

/**
 * A repeat rule as the editor and quick add set it (design: docs/phase-3-design.md section 1). [weekdays] only matters for Weekly (empty =
 * the weekday of the task's date). Monthly is "on the same day of the month" unless [monthlyNth] is set: then "on the nth [monthlyWeekday]"
 * (1 to 4, or -1 for the last one). [fromCompletion] is the second kind: the next date counts from the moment the task is ticked.
 */
data class RepeatSpec(
    val freq: RepeatFreq,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthlyNth: Int? = null,
    val monthlyWeekday: DayOfWeek? = null,
    val end: RepeatEnd = RepeatEnd.Never,
    val fromCompletion: Boolean = false,
) {
    /** Why this rule cannot be saved, or null. [firstDay] is the task's first date. */
    fun problem(firstDay: LocalDate?): String? = when {
        interval !in 1..99 -> "every must be 1 to 99"
        end is RepeatEnd.Count && end.times !in 1..999 -> "times must be 1 to 999"
        end is RepeatEnd.Until && firstDay != null && end.day.isBefore(firstDay) -> "ends before it starts"
        monthlyNth != null && (monthlyNth !in listOf(1, 2, 3, 4, -1) || monthlyWeekday == null) -> "nth weekday must be 1 to 4 or last"
        else -> null
    }
}

private val DAY_CODES = mapOf(
    DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
    DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
)
private val CODE_DAYS = DAY_CODES.entries.associate { (k, v) -> v to k }

/** Writes and reads the RRULE the editor uses; RepeatWriter.rrule in BookahCalendar is the origin (copied, not shared). */
object RepeatRules {
    const val FROM_PROPERTY = "X-BOOKAH-REPEAT-FROM"
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val UNTIL_VALUE = Regex("^(\\d{8})(?:T(\\d{6})(Z?))?$")

    /** The RRULE value. [dateOnly]: an UNTIL is a date; otherwise the end of that day in [zone] as a UTC time (the standard's rule). */
    fun rrule(spec: RepeatSpec, dateOnly: Boolean, zone: ZoneId): String {
        val parts = ArrayList<String>()
        parts += "FREQ=" + spec.freq.name.uppercase()
        if (spec.interval != 1) parts += "INTERVAL=${spec.interval}"
        if (spec.freq == RepeatFreq.Weekly && spec.weekdays.isNotEmpty()) {
            parts += "BYDAY=" + DayOfWeek.entries.filter { it in spec.weekdays }.joinToString(",") { DAY_CODES.getValue(it) }
        }
        if (spec.freq == RepeatFreq.Monthly && spec.monthlyNth != null && spec.monthlyWeekday != null) {
            parts += "BYDAY=${spec.monthlyNth}${DAY_CODES.getValue(spec.monthlyWeekday)}"
        }
        when (val e = spec.end) {
            RepeatEnd.Never -> {}
            is RepeatEnd.Count -> parts += "COUNT=${e.times}"
            is RepeatEnd.Until -> parts += "UNTIL=" + untilText(e.day, dateOnly, zone)
        }
        return parts.joinToString(";")
    }

    internal fun untilText(day: LocalDate, dateOnly: Boolean, zone: ZoneId): String =
        if (dateOnly) DATE.format(day) else STAMP.format(day.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1))

    /** The local day an UNTIL value names (a date, a UTC time, or a floating time), or null when it cannot be read. */
    internal fun untilDay(value: String, zone: ZoneId): LocalDate? {
        val m = UNTIL_VALUE.find(value.trim()) ?: return null
        val d = runCatching { LocalDate.parse(m.groupValues[1], DATE) }.getOrNull() ?: return null
        if (m.groupValues[2].isEmpty() || m.groupValues[3].isEmpty()) return d
        val t = m.groupValues[2]
        return runCatching { d.atTime(t.substring(0, 2).toInt(), t.substring(2, 4).toInt(), t.substring(4, 6).toInt()).atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
    }

    /** The rule as the editor can show it, or null when it has parts the editor cannot show (it is then kept as it is). */
    fun parse(rrule: String, fromCompletion: Boolean, zone: ZoneId): RepeatSpec? {
        val map = LinkedHashMap<String, String>()
        for (p in rrule.trim().split(';')) {
            if (p.isBlank()) continue
            val k = p.substringBefore('=', "").trim().uppercase()
            if (k.isEmpty() || '=' !in p || k in map) return null
            map[k] = p.substringAfter('=').trim()
        }
        map.remove("WKST")
        val freq = when (map.remove("FREQ")?.uppercase()) {
            "DAILY" -> RepeatFreq.Daily; "WEEKLY" -> RepeatFreq.Weekly; "MONTHLY" -> RepeatFreq.Monthly; "YEARLY" -> RepeatFreq.Yearly
            else -> return null
        }
        val interval = map.remove("INTERVAL")?.let { it.toIntOrNull() ?: return null } ?: 1
        if (interval !in 1..99) return null
        val count = map.remove("COUNT")?.let { it.toIntOrNull() ?: return null }
        val until = map.remove("UNTIL")?.let { untilDay(it, zone) ?: return null }
        if (count != null && until != null) return null
        val byDay = map.remove("BYDAY")?.split(',')?.map { it.trim().uppercase() }
        if (map.isNotEmpty()) return null                      // BYMONTH, BYMONTHDAY, BYSETPOS, BYHOUR, ...: kept, not shown
        var weekdays = emptySet<DayOfWeek>()
        var nth: Int? = null
        var nthDay: DayOfWeek? = null
        if (byDay != null) {
            when (freq) {
                RepeatFreq.Weekly -> weekdays = byDay.map { CODE_DAYS[it] ?: return null }.toSet()
                RepeatFreq.Monthly -> {
                    val one = byDay.singleOrNull() ?: return null
                    val m = Regex("^([+-]?\\d)([A-Z]{2})$").find(one) ?: return null
                    nth = m.groupValues[1].removePrefix("+").toInt().takeIf { it in listOf(1, 2, 3, 4, -1) } ?: return null
                    nthDay = CODE_DAYS[m.groupValues[2]] ?: return null
                }
                else -> return null
            }
        }
        val end = when { count != null -> RepeatEnd.Count(count); until != null -> RepeatEnd.Until(until); else -> RepeatEnd.Never }
        return RepeatSpec(freq, interval, weekdays, nth, nthDay, end, fromCompletion)
    }

    /** The date a task with no dates starts on when it is given this rule: today, or for weekly-on-days the first chosen day from today on. */
    fun firstDate(spec: RepeatSpec, today: LocalDate): LocalDate {
        if (spec.freq != RepeatFreq.Weekly || spec.weekdays.isEmpty()) return today
        return (0L..6L).map { today.plusDays(it) }.first { it.dayOfWeek in spec.weekdays }
    }

    /** True when "monthly on this day" skips some months (the 29th to the 31st), so the editor can say so. */
    fun monthlyDaySkips(date: LocalDate): Boolean = date.dayOfMonth >= 29

    /** "nth weekday of the month" for a date: 1 to 4, or -1 when it is in the last week of its month (the fifth). */
    fun nthOf(date: LocalDate): Int = ((date.dayOfMonth - 1) / 7 + 1).let { if (it >= 5) -1 else it }
}

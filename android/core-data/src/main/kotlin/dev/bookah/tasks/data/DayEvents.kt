package dev.bookah.tasks.data

import biweekly.Biweekly
import biweekly.component.VEvent
import biweekly.io.TimezoneInfo
import biweekly.property.DateOrDateTimeProperty
import biweekly.util.ICalDate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.TimeZone

/**
 * One calendar event on the day strip (read only). [allDay] events have no times. A timed event has the part of today it covers:
 * [startsBefore] when it began on an earlier day (then [start] is null), [endsAfter] when it goes on past midnight (then [end] is null).
 */
data class DayEvent(
    val listId: Long,
    val uid: String,
    val href: String,
    val title: String,
    val allDay: Boolean,
    val start: LocalTime?,
    val end: LocalTime?,
    val startsBefore: Boolean = false,
    val endsAfter: Boolean = false,
) {
    /** A timed event that covers the whole of today is shown like an all-day one. */
    val wholeDay: Boolean get() = allDay || (startsBefore && endsAfter)
}

/**
 * Today's events from stored calendar objects (docs/phase-5-design.md section 2). Adapted from BookahCalendar's Expander (read only there):
 * biweekly expands RRULE; EXDATE, RECURRENCE-ID (moved into or out of the day) and cancelled occurrences are applied here. All-day events
 * are matched by DATE (start date <= day < end date), timed ones by overlap with the day's local midnight to midnight, so an all-day event
 * of yesterday never leaks into today in a zone ahead of UTC. A broken object is skipped; this never throws.
 */
object DayEvents {
    private const val DAY_MS = 86_400_000L
    private const val MAX_PER_EVENT = 5_000
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    fun forDay(sources: List<StoredObject>, day: LocalDate, zone: ZoneId, owner: String? = null): List<DayEvent> {
        val out = ArrayList<Pair<Long, DayEvent>>()
        for (s in sources) runCatching { expand(s, day, zone, out, owner?.trim()?.lowercase()?.ifEmpty { null }) }
        val order = compareBy<Pair<Long, DayEvent>>({ !it.second.wholeDay }, { !it.second.startsBefore }, { it.first }, { it.second.title.lowercase() })
        return out.distinctBy { it.second.uid + "|" + it.first + "|" + it.second.allDay }.sortedWith(order).map { it.second }
    }

    private fun localOf(c: biweekly.util.DateTimeComponents) = LocalDateTime.of(c.year, c.month, c.date, c.hour, c.minute, c.second)
    private fun dateOf(d: ICalDate): LocalDate = d.rawComponents?.let { LocalDate.of(it.year, it.month, it.date) }
        ?: Instant.ofEpochMilli(d.time).atZone(ZoneOffset.UTC).toLocalDate()

    /** The zone a timed value is in: UTC, its TZID (a name Java knows, else the item's own VTIMEZONE block), or floating (the phone's). */
    private fun zoneOf(prop: DateOrDateTimeProperty, tzi: TimezoneInfo?, phone: ZoneId): Pair<ZoneId?, TimeZone> {
        val d = prop.value
        if (d.rawComponents?.isUtc == true) return ZoneOffset.UTC to UTC
        val tzid = prop.parameters.timezoneId
        val assigned = runCatching { tzi?.getTimezone(prop) }.getOrNull()
        if (tzid == null && assigned == null) return phone to TimeZone.getTimeZone(phone)
        val named = listOfNotNull(tzid, assigned?.globalId, assigned?.timeZone?.id).firstNotNullOfOrNull { runCatching { ZoneId.of(it) }.getOrNull() }
        if (named != null) return named to TimeZone.getTimeZone(named)
        return null to (assigned?.timeZone ?: TimeZone.getTimeZone(phone))
    }

    /** The moment of a timed value in UTC milliseconds. */
    private fun instantOf(d: ICalDate, zone: ZoneId?): Long {
        val c = d.rawComponents ?: return d.time
        if (c.isUtc) return localOf(c).toInstant(ZoneOffset.UTC).toEpochMilli()
        return if (zone != null) localOf(c).atZone(zone).toInstant().toEpochMilli() else d.time      // unknown name: as biweekly read it from the block
    }

    private fun instantOf(prop: DateOrDateTimeProperty, tzi: TimezoneInfo?, phone: ZoneId): Long = instantOf(prop.value, zoneOf(prop, tzi, phone).first)

    /**
     * An invitation the owner declined. The phone does not know the owner's e-mail address, so the owner is matched by the login name:
     * an attendee whose address is `<owner>@...` or `<owner>`, or whose name (CN) is the owner, with PARTSTAT=DECLINED.
     */
    private fun declinedBy(e: VEvent, owner: String?): Boolean {
        if (owner == null) return false
        return e.attendees.any { a ->
            val declined = a.participationStatus?.value.equals("DECLINED", ignoreCase = true)
            val mail = a.email?.trim()?.lowercase()?.removePrefix("mailto:")
            val mine = mail == owner || mail?.substringBefore('@') == owner || a.commonName?.trim()?.lowercase() == owner
            declined && mine
        }
    }

    private fun expand(src: StoredObject, day: LocalDate, phone: ZoneId, out: MutableList<Pair<Long, DayEvent>>, owner: String?) {
        val cal = Biweekly.parse(src.ics).first() ?: return
        val tzi = cal.timezoneInfo
        val events: List<VEvent> = cal.events.filter { it.dateStart?.value != null && it.uid?.value != null }
        if (events.isEmpty()) return
        val dayStart = day.atStartOfDay(phone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(phone).toInstant().toEpochMilli()

        for (e in events) {
            val allDay = !e.dateStart.value.hasTime()
            val cancelled = e.status?.isCancelled == true || declinedBy(e, owner)
            val uid = e.uid.value
            val title = e.summary?.value?.trim().orEmpty()
            fun add(key: Long, ev: DayEvent) { out.add(key to ev) }

            if (allDay) {
                val first = dateOf(e.dateStart.value)
                val days = when {
                    e.dateEnd?.value != null && !e.dateEnd.value.hasTime() -> java.time.temporal.ChronoUnit.DAYS.between(first, dateOf(e.dateEnd.value))
                    e.duration?.value != null -> e.duration.value.toMillis() / DAY_MS
                    else -> 1L
                }.coerceAtLeast(1L)
                fun covers(start: LocalDate) = !start.isAfter(day) && start.plusDays(days).isAfter(day)
                fun make() = DayEvent(src.listId, uid, src.href, title, allDay = true, start = null, end = null)
                if (cancelled) continue
                // extra dates (RDATE): dates; a PERIOD value or a timed value beside an all-day start is not used
                val extra = e.recurrenceDates.flatMap { rd -> rd.dates.orEmpty().filter { !it.hasTime() }.map { dateOf(it) } }
                if (e.recurrenceId != null || (e.recurrenceRule?.value == null && extra.isEmpty())) {
                    if (covers(first)) add(Long.MIN_VALUE, make())
                    continue
                }
                val excluded = HashSet<LocalDate>()
                for (x in e.exceptionDates) for (v in x.values) excluded.add(dateOf(v))
                for (o in events) if (o !== e && o.recurrenceId?.value != null) excluded.add(dateOf(o.recurrenceId.value))
                val starts = HashSet<LocalDate>().apply { add(first); addAll(extra) }
                e.recurrenceRule?.value?.let { rule ->
                    val it = rule.getDateIterator(Date(first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()), UTC)
                    it.advanceTo(Date(day.minusDays(days + 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
                    var guard = 0
                    while (it.hasNext() && guard++ < MAX_PER_EVENT) {
                        val d = Instant.ofEpochMilli(it.next().time).atZone(ZoneOffset.UTC).toLocalDate()
                        if (d.isAfter(day)) break
                        starts.add(d)
                    }
                }
                if (starts.any { it !in excluded && covers(it) }) add(Long.MIN_VALUE, make())
                continue
            }

            val (zid, tz) = zoneOf(e.dateStart, tzi, phone)
            val start = instantOf(e.dateStart.value, zid)
            val dur = when {
                e.dateEnd?.value != null -> maxOf(0L, (if (e.dateEnd.value.hasTime()) instantOf(e.dateEnd, tzi, phone) else dateOf(e.dateEnd.value).atStartOfDay(phone).toInstant().toEpochMilli()) - start)
                e.duration?.value != null -> maxOf(0L, e.duration.value.toMillis())
                else -> 0L
            }
            fun inDay(s: Long) = s < dayEnd && (s + dur > dayStart || (dur == 0L && s >= dayStart))
            fun make(s: Long): DayEvent {
                val before = s < dayStart
                val after = s + dur > dayEnd
                fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(phone).toLocalTime().withSecond(0).withNano(0)
                return DayEvent(src.listId, uid, src.href, title, allDay = false, start = if (before) null else local(s), end = if (after) null else local(s + dur), startsBefore = before, endsAfter = after)
            }
            if (cancelled) continue
            // extra moments (RDATE): timed values in their own zone (or the event's); a date or a PERIOD value is not used here
            val extra = e.recurrenceDates.flatMap { rd ->
                val rz = rd.parameters.timezoneId?.let { id -> runCatching { ZoneId.of(id) }.getOrNull() } ?: zid
                rd.dates.orEmpty().filter { it.hasTime() }.map { instantOf(it, rz) }
            }
            if (e.recurrenceId != null || (e.recurrenceRule?.value == null && extra.isEmpty())) {
                if (inDay(start)) add(start, make(start))
                continue
            }
            val excluded = HashSet<Long>()
            val excludedDays = HashSet<LocalDate>()
            for (x in e.exceptionDates) {
                val xz = x.parameters.timezoneId?.let { id -> runCatching { ZoneId.of(id) }.getOrNull() } ?: zid
                for (v in x.values) if (v.hasTime()) excluded.add(instantOf(v, xz)) else excludedDays.add(dateOf(v))
            }
            for (o in events) if (o !== e && o.recurrenceId?.value != null) {
                val r = o.recurrenceId
                if (r.value.hasTime()) excluded.add(instantOf(r, tzi, phone)) else excludedDays.add(dateOf(r.value))
            }
            val starts = sortedSetOf(start).apply { addAll(extra) }
            e.recurrenceRule?.value?.let { rule ->
                val it = rule.getDateIterator(Date(start), tz)
                it.advanceTo(Date(dayStart - dur - DAY_MS))
                var guard = 0
                while (it.hasNext() && guard++ < MAX_PER_EVENT) {
                    val s = it.next().time
                    if (s >= dayEnd) break
                    starts.add(s)
                }
            }
            for (s in starts) {
                if (s in excluded) continue
                if (excludedDays.isNotEmpty() && Instant.ofEpochMilli(s).atZone(zid ?: phone).toLocalDate() in excludedDays) continue
                if (inDay(s)) add(s, make(s))
            }
        }
    }
}

/**
 * The days (epoch days, both ends included, padded by two days for any zone) that a stored calendar object can touch: kept with each
 * event so the strip reads only the few that matter. An endless repeat is open-ended; an unreadable object touches every day (it is then
 * skipped by [DayEvents]); an object with no event touches none.
 */
object EventSpan {
    private const val OPEN = 3_000_000L          // about the year 10,000
    private const val PAD = 2L

    fun of(ics: String): Pair<Long, Long> = runCatching { span(ics) }.getOrNull() ?: (-OPEN to OPEN)

    private fun dayOf(d: ICalDate): Long = (d.rawComponents?.let { LocalDate.of(it.year, it.month, it.date) }
        ?: Instant.ofEpochMilli(d.time).atZone(ZoneOffset.UTC).toLocalDate()).toEpochDay()

    private fun span(ics: String): Pair<Long, Long> {
        val cal = Biweekly.parse(ics).first() ?: return -OPEN to OPEN
        var from = Long.MAX_VALUE
        var to = Long.MIN_VALUE
        for (e in cal.events) {
            val s = e.dateStart?.value ?: continue
            val first = dayOf(s)
            val length = when {
                e.dateEnd?.value != null -> maxOf(0L, dayOf(e.dateEnd.value) - first)
                e.duration?.value != null -> maxOf(0L, e.duration.value.toMillis() / 86_400_000L + 1)
                else -> 0L
            }
            var last = first + length
            val rule = e.recurrenceRule?.value
            if (rule != null || e.recurrenceDates.isNotEmpty()) {
                val until = rule?.until
                last = if (rule != null && until != null && e.recurrenceDates.isEmpty()) dayOf(until) + length else OPEN
            }
            from = minOf(from, first); to = maxOf(to, last)
        }
        if (from == Long.MAX_VALUE) return 1L to 0L          // no event in it
        return (from - PAD) to (if (to >= OPEN) OPEN else to + PAD)
    }
}

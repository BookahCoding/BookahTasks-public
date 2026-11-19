package dev.bookah.tasks.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** What a recognised piece of the typed text means; the quick-add sheet lights the matching chip and can switch one kind off. */
enum class HitKind { Start, Due, Priority, Tag, List, Repeat }

/** A recognised piece of the typed text: characters [from] until [to] (the end is not included). */
data class Hit(val kind: HitKind, val from: Int, val to: Int)

/**
 * The result of reading one line of typed text. Nothing is removed while typing: [hits] say where the recognised words are (to tint them),
 * and [title] is the text without them (used when the task is saved). A kind in `dismissed` (the owner tapped its chip off) stays in the title.
 * [dueDateExplicit] is false when the deadline was typed as a time only ("by 5pm"): the sheet then gives it the date of the start it ends up with.
 */
data class Parsed(
    val text: String, val start: TaskWhen?, val due: TaskWhen?, val priority: Priority?, val tags: List<String>, val listName: String?, val hits: List<Hit>,
    val dueDateExplicit: Boolean = true,
    /** "every monday", "κάθε 2 εβδομάδες" (Phase 3). Last, with a default: the sheet builds a plain [Parsed] by position when the parser fails. */
    val repeat: RepeatSpec? = null,
) {
    fun title(dismissed: Set<HitKind> = emptySet()): String {
        val cut = hits.filter { it.kind !in dismissed }.sortedBy { it.from }
        val sb = StringBuilder()
        var at = 0
        for (h in cut) { if (h.from >= at) { sb.append(text, at, h.from); at = h.to } }
        sb.append(text, at, text.length)
        val t = sb.toString().replace(WHITESPACE, " ").trim(' ', ',', ';', '.', ':', '-')
        return t.ifBlank { text.trim() }          // only recognised words were typed: keep them as the title
    }

    private companion object { val WHITESPACE = Regex("\\s+") }
}

/**
 * Reads a typed line such as "Call mom tomorrow at 6pm !1 #family @home" in Greek and English (design: advisor, 17 Nov 2026).
 * It is deliberately conservative: it only recognises words that cannot be ordinary title words.
 * - **Times need a marker:** `18:00`, `6pm`, `6 pm`, `6μμ`, `at 6`, `at 18.30`, `στις 6`, `τις 17:00`. A bare number is never a time, and a dotted
 *   number alone is a date (`20.11`) or nothing (`12.50`). After `at`, `στις` or `τις` a bare hour from 1 to 7 means the afternoon (at 6 = 18:00).
 * - **Dates:** today, tomorrow, the day after, weekday names (the next one, never today; no "sat" or "sun"), next week (the coming Monday),
 *   in N days or weeks, `20/11` (not fractions such as 1/2 kg), `20.11`, `20 Nov [2027]`, `Nov 20`; a Greek preposition or article before a date
 *   belongs to it. `tonight` is today at the evening hour unless a time is typed too.
 * - **Deadline only after a keyword:** by, until, due, έως, μέχρι, followed by a date or time. Without a date the keyword stays in the title.
 * - **Symbols:** `!1` high, `!2` medium, `!3` low; `#tag` (needs a letter); `@list` (prefix of a list name, ignoring case and Greek accents).
 * - **Repeats** (Phase 3): `every`/`κάθε` + [N | other] + day/week/month/year (Greek units too), a weekday or several joined by and/και,
 *   weekend, weekdays. Without a unit after it, `every` stays a title word. A typed time then belongs to the first chosen day.
 */
object QuickParser {
    private class Tok(val from: Int, val to: Int, val core: String, val f: String)
    private class Group { var date: LocalDate? = null; var time: LocalTime? = null; var timeIsDefault = false; var tonight = false }
    private class Match(val len: Int, val date: LocalDate? = null, val time: LocalTime? = null, val defaultTime: Boolean = false, val tonight: Boolean = false)

    private val TOKEN = Regex("\\S+")
    private val NUM = Regex("^\\d{1,3}$")
    private val PRIORITY = Regex("^![123]$")
    private val TAG_BODY = Regex("^(?=.*\\p{L})[\\p{L}\\p{N}_-]+$")
    private val SLASH_DATE = Regex("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2}|\\d{4}))?$")
    private val DOT_DATE = Regex("^(\\d{2})\\.(\\d{2})$")
    private val YEAR = Regex("^20\\d{2}$")
    private val AMPM_ATTACHED = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?(am|pm|πμ|μμ)$")
    private val HOUR_MIN = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?$")
    private val CLOCK = Regex("^(\\d{1,2}):(\\d{2})$")

    private val KEYWORDS = setOf("by", "until", "due", "εωσ", "μεχρι")
    private val AMPM = setOf("am", "pm", "πμ", "μμ")
    private val MARKERS = setOf("at", "στισ", "τισ")                       // "at 6", "στις 6", "έως τις 17:00"
    private val DATE_PREFIXES = setOf("στισ", "την", "τη", "το", "τισ", "on", "the")
    private val UNITS = setOf("kg", "g", "gr", "cup", "cups", "l", "ml", "lb", "lbs", "oz", "tbsp", "tsp", "cm", "m", "km", "mm", "χλγ", "κιλο", "κιλα", "γρ", "λιτρο", "φλιτζανι", "φλιτζανια")
    private val WEEKDAYS: Map<String, DayOfWeek> = buildMap {
        for ((names, d) in listOf(
            listOf("monday", "mon") to DayOfWeek.MONDAY, listOf("tuesday", "tues", "tue") to DayOfWeek.TUESDAY, listOf("wednesday", "wed") to DayOfWeek.WEDNESDAY,
            listOf("thursday", "thurs", "thur", "thu") to DayOfWeek.THURSDAY, listOf("friday", "fri") to DayOfWeek.FRIDAY, listOf("saturday") to DayOfWeek.SATURDAY,
            listOf("sunday") to DayOfWeek.SUNDAY, listOf("δευτερα") to DayOfWeek.MONDAY, listOf("τριτη") to DayOfWeek.TUESDAY, listOf("τεταρτη") to DayOfWeek.WEDNESDAY,
            listOf("πεμπτη") to DayOfWeek.THURSDAY, listOf("παρασκευη") to DayOfWeek.FRIDAY, listOf("σαββατο") to DayOfWeek.SATURDAY, listOf("κυριακη") to DayOfWeek.SUNDAY,
        )) for (n in names) put(n, d)
    }
    private val REPEAT_UNITS: Map<String, RepeatFreq> = buildMap {
        for (u in listOf("day", "days", "μερα", "μερεσ", "ημερα", "ημερεσ")) put(u, RepeatFreq.Daily)
        for (u in listOf("week", "weeks", "εβδομαδα", "εβδομαδεσ")) put(u, RepeatFreq.Weekly)
        for (u in listOf("month", "months", "μηνα", "μηνασ", "μηνεσ")) put(u, RepeatFreq.Monthly)
        for (u in listOf("year", "years", "χρονο", "χρονοσ", "χρονια", "ετοσ", "ετη")) put(u, RepeatFreq.Yearly)
    }
    private val MONTHS: Map<String, Int> = buildMap {
        val en = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
        val enShort = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        val el = listOf("ιανουαριου", "φεβρουαριου", "μαρτιου", "απριλιου", "μαιου", "ιουνιου", "ιουλιου", "αυγουστου", "σεπτεμβριου", "οκτωβριου", "νοεμβριου", "δεκεμβριου")
        val elNom = listOf("ιανουαριοσ", "φεβρουαριοσ", "μαρτιοσ", "απριλιοσ", "μαιοσ", "ιουνιοσ", "ιουλιοσ", "αυγουστοσ", "σεπτεμβριοσ", "οκτωβριοσ", "νοεμβριοσ", "δεκεμβριοσ")
        val elShort = listOf("ιαν", "φεβ", "μαρ", "απρ", "μαι", "ιουν", "ιουλ", "αυγ", "σεπ", "οκτ", "νοε", "δεκ")
        for (i in 0..11) { put(en[i], i + 1); put(enShort[i], i + 1); put(el[i], i + 1); put(elNom[i], i + 1); put(elShort[i], i + 1) }
    }

    /** Lower case, Greek accents removed and the final sigma written as σ, one character for one character so positions stay the same. */
    private fun fold(s: String): String = buildString {
        for (c in s) append(
            when (val l = c.lowercaseChar()) {
                'ά' -> 'α'; 'έ' -> 'ε'; 'ή' -> 'η'; 'ί', 'ϊ', 'ΐ' -> 'ι'; 'ό' -> 'ο'; 'ύ', 'ϋ', 'ΰ' -> 'υ'; 'ώ' -> 'ω'; 'ς' -> 'σ'
                else -> l
            },
        )
    }

    /** "pm", "p.m", "μ.μ" and "μμ" are all the same marker. */
    private fun marker(f: String?): String? = f?.replace(".", "")?.takeIf { it in AMPM }

    fun parse(text: String, today: LocalDate, eveningHour: Int, lists: List<String>): Parsed {
        val toks = TOKEN.findAll(text).map { m ->
            var from = m.range.first
            var end = m.range.last + 1
            while (end > from + 1 && text[end - 1] in ",.;:?)!") end--
            if (end > from + 1 && text[from] == '(') from++
            val core = text.substring(from, end)
            Tok(from, end, core, fold(core))
        }.toList()
        val n = toks.size
        val hits = ArrayList<Hit>()
        var priority: Priority? = null
        val tags = ArrayList<String>()
        var listName: String? = null
        val sg = Group(); val dg = Group()
        var dueDateExplicit = true

        fun tok(j: Int) = toks.getOrNull(j)?.f

        fun monthDay(day: String?, month: String?, year: String?): Pair<LocalDate, Boolean>? {
            val d = day?.takeIf { it.matches(NUM) }?.toInt() ?: return null
            val mo = MONTHS[month ?: return null] ?: return null
            val y = year?.takeIf { it.matches(YEAR) }?.toInt()
            return runCatching {
                if (y != null) LocalDate.of(y, mo, d) to true
                else LocalDate.of(today.year, mo, d).let { (if (it.isBefore(today)) it.plusYears(1) else it) to false }
            }.getOrNull()
        }

        fun dateAt(j: Int, g: Group): Match? {
            if (j >= n) return null                                                      // a lead-in word ("the", "στις") may be the last word typed so far
            val f = toks[j].f
            if (f in DATE_PREFIXES) dateAt(j + 1, g)?.let { return Match(it.len + 1, it.date, it.time, it.defaultTime, it.tonight) }   // "στις 5 Μαΐου", "την Παρασκευή", "on friday"
            when (f) {
                "today", "σημερα" -> return Match(1, today)
                "tomorrow", "αυριο" -> return Match(1, today.plusDays(1))
                "μεθαυριο" -> return Match(1, today.plusDays(2))
                "tonight", "αποψε" -> return Match(1, today, LocalTime.of(eveningHour, 0), defaultTime = true, tonight = true)
            }
            WEEKDAYS[f]?.let { return Match(1, today.with(TemporalAdjusters.next(it))) }
            if (f == "next" && tok(j + 1) == "week" || f == "επομενη" && tok(j + 1) == "εβδομαδα") return Match(2, today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)))
            if (f == "next") WEEKDAYS[tok(j + 1)]?.let { return Match(2, today.with(TemporalAdjusters.next(it))) }
            if ((f == "in" || f == "σε") && tok(j + 1)?.matches(NUM) == true) {
                val k = tok(j + 1)!!.toInt()
                val days = when (tok(j + 2)) { "day", "days", "μερα", "μερεσ", "ημερα", "ημερεσ" -> k; "week", "weeks", "εβδομαδα", "εβδομαδεσ" -> k * 7; else -> null }
                if (days != null && k in 1..365) return Match(3, today.plusDays(days.toLong()))
            }
            SLASH_DATE.find(f)?.let { m ->
                val day = m.groupValues[1].toInt(); val month = m.groupValues[2].toInt(); val ys = m.groupValues[3]
                val fraction = (day == 24 && month == 7) || tok(j + 1) in UNITS          // 24/7, 1/2 kg
                val date = if (fraction) null else runCatching {
                    if (ys.isNotEmpty()) LocalDate.of(if (ys.length == 2) 2000 + ys.toInt() else ys.toInt(), month, day)
                    else LocalDate.of(today.year, month, day).let { if (it.isBefore(today)) it.plusYears(1) else it }
                }.getOrNull()
                if (date != null) return Match(1, date)
            }
            DOT_DATE.find(f)?.let { m ->                                                 // 20.11 is a date; 12.50 is nothing
                val d = runCatching { LocalDate.of(today.year, m.groupValues[2].toInt(), m.groupValues[1].toInt()).let { if (it.isBefore(today)) it.plusYears(1) else it } }.getOrNull()
                return d?.let { Match(1, it) }
            }
            monthDay(f, tok(j + 1), tok(j + 2))?.let { (d, withYear) -> return Match(if (withYear) 3 else 2, d) }
            monthDay(tok(j + 1), f, tok(j + 2))?.let { (d, withYear) -> return Match(if (withYear) 3 else 2, d) }
            return null
        }

        fun hm(h: Int, m: Int): LocalTime? = if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
        fun ampm(h: Int, m: Int, marker: String): LocalTime? =
            if (h in 1..12 && m in 0..59) LocalTime.of(if (marker == "pm" || marker == "μμ") h % 12 + 12 else h % 12, m) else null

        fun timeAt(j: Int, evening: Boolean): Match? {
            if (j >= n) return null
            val f = toks[j].f
            // 1. attached marker: 6pm, 6:30pm, 6μμ
            AMPM_ATTACHED.find(f)?.let { m -> ampm(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), m.groupValues[3])?.let { return Match(1, null, it) } }
            // 2. a number, then a separate marker: 6 pm, 6:30 pm
            marker(tok(j + 1))?.let { mk ->
                HOUR_MIN.find(f)?.let { m -> ampm(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), mk)?.let { return Match(2, null, it) } }
            }
            // 3. at / στις / τις, then an hour: at 6, at 7 pm, στις 6:30, at 6pm, at 18.30
            if (f in MARKERS) {
                val nf = tok(j + 1)
                if (nf != null) {
                    AMPM_ATTACHED.find(nf)?.let { m -> ampm(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), m.groupValues[3])?.let { return Match(2, null, it) } }
                    HOUR_MIN.find(nf)?.let { m ->
                        val h0 = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                        marker(tok(j + 2))?.let { mk -> ampm(h0, min, mk)?.let { return Match(3, null, it) } }
                        var h = h0
                        if (h in 1..7) h += 12                                           // "at 6" means the afternoon
                        else if (evening && h in 8..11) h += 12                          // "tonight at 9" is 21:00
                        hm(h, min)?.let { return Match(2, null, it) }
                    }
                }
            }
            // 4. a clock time with a colon: 18:00 (a dotted number alone is never a time)
            CLOCK.find(f)?.let { m -> hm(m.groupValues[1].toInt(), m.groupValues[2].toInt())?.let { return Match(1, null, it) } }
            return null
        }

        /** Reads a run of date and time words starting at [k] into [g]; returns how many tokens it used (hits are added by the caller). */
        fun phrase(k: Int, g: Group): Int {
            var j = k
            while (j < n) {
                val d = if (g.date == null) dateAt(j, g) else null
                if (d != null) {
                    g.date = d.date
                    if (d.time != null && g.time == null) { g.time = d.time; g.timeIsDefault = d.defaultTime }
                    if (d.tonight) g.tonight = true
                    j += d.len; continue
                }
                val t = if (g.time == null || g.timeIsDefault) timeAt(j, g.tonight) else null
                if (t != null) { g.time = t.time; g.timeIsDefault = false; j += t.len; continue }      // an explicit time replaces the evening default of "tonight"
                break
            }
            return j - k
        }
        fun mark(kind: HitKind, from: Int, count: Int) { for (q in from until from + count) hits.add(Hit(kind, toks[q].from, toks[q].to)) }

        /** "every [N | other] day/week/month/year", "every monday [and thursday]", "every weekend": tokens used and the rule, or null (then "every" is a title word). */
        fun repeatAt(j: Int): Pair<Int, RepeatSpec>? {
            var k = j + 1
            val first = tok(k) ?: return null
            var every = 1
            if (first.matches(NUM)) { every = first.toInt(); if (every !in 1..99) return null; k++ }
            else if (first == "other") { every = 2; k++ }
            val u = tok(k) ?: return null
            REPEAT_UNITS[u]?.let { return (k + 1 - j) to RepeatSpec(it, every) }
            when (u) {
                "weekend" -> return (k + 1 - j) to RepeatSpec(RepeatFreq.Weekly, every, setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
                "weekday", "weekdays" -> return (k + 1 - j) to RepeatSpec(RepeatFreq.Weekly, every, DayOfWeek.entries.take(5).toSet())
            }
            val days = LinkedHashSet<DayOfWeek>()
            var end = k
            while (end < n) {
                val d = WEEKDAYS[toks[end].f] ?: break
                days.add(d); end++
                val sep = tok(end)
                if ((sep == "and" || sep == "και") && tok(end + 1)?.let { WEEKDAYS[it] } != null) end++ else break
            }
            if (days.isEmpty()) return null
            return (end - j) to RepeatSpec(RepeatFreq.Weekly, every, days)
        }
        var repeat: RepeatSpec? = null

        var i = 0
        while (i < n) {
            val t = toks[i]
            when {
                repeat == null && (t.f == "every" || t.f == "καθε") && repeatAt(i).let { found ->
                    if (found != null) { repeat = found.second; mark(HitKind.Repeat, i, found.first); i += found.first; true } else false
                } -> {}
                priority == null && PRIORITY.matches(t.f) -> {
                    priority = when (t.f[1]) { '1' -> Priority.High; '2' -> Priority.Medium; else -> Priority.Low }
                    mark(HitKind.Priority, i, 1); i++
                }
                t.core.length > 1 && t.core[0] == '#' && TAG_BODY.matches(t.core.substring(1)) -> {
                    tags.add(t.core.substring(1)); mark(HitKind.Tag, i, 1); i++
                }
                listName == null && t.core.length > 1 && t.core[0] == '@' && lists.any { fold(it).startsWith(t.f.substring(1)) } -> {
                    listName = lists.first { fold(it).startsWith(t.f.substring(1)) }; mark(HitKind.List, i, 1); i++
                }
                t.f in KEYWORDS && phrase(i + 1, dg.takeIf { it.date == null || it.time == null } ?: Group()).let { used ->
                    if (used > 0) { mark(HitKind.Due, i, 1 + used); i += 1 + used; true } else false
                } -> {}
                else -> {
                    val used = if (sg.date == null || sg.time == null || sg.timeIsDefault) phrase(i, sg) else 0
                    if (used > 0) { mark(HitKind.Start, i, used); i += used } else i++
                }
            }
        }
        // a time typed with "every monday" belongs to the first Monday, not to today
        val start = if (sg.date != null || sg.time != null) TaskWhen(sg.date ?: repeat?.let { RepeatRules.firstDate(it, today) } ?: today, sg.time) else null
        if (dg.date == null && dg.time != null) dueDateExplicit = false
        val due = if (dg.date != null || dg.time != null) TaskWhen(dg.date ?: start?.date ?: today, dg.time) else null
        return Parsed(text, start, due, priority, tags, listName, hits.sortedBy { it.from }, dueDateExplicit, repeat)
    }
}

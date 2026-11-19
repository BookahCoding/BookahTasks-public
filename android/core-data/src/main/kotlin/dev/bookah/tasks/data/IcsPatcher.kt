package dev.bookah.tasks.data

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Edits the stored text of one task line by line. Only the lines asked for change; every other line (alarms, X- lines written by
 * other apps, odd folding) is kept byte for byte. It works on the first VTODO that has no RECURRENCE-ID (the series itself, not a
 * completed copy). Values passed to [setProperty] are written as given: use [escapeText] for text properties.
 */
object IcsPatcher {
    private val stampFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun stamp(now: Instant): String = stampFormat.format(now)

    fun escapeText(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            ';' -> append("\\;")
            ',' -> append("\\,")
            '\n' -> append("\\n")
            '\r' -> {}
            else -> append(c)
        }
    }

    /** Folds a line to at most 75 octets per physical line (UTF-8), never inside a character. */
    fun fold(line: String, nl: String = "\r\n"): String {
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > 75) { sb.append(nl).append(' '); bytes = 1 }
            sb.appendCodePoint(cp)
            bytes += size
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Logical lines (a folded line keeps its original physical text, folds included) and the newline style of the text. */
    private class Doc(val nl: String, val lines: MutableList<String>) {
        fun text() = lines.joinToString(nl) + nl
    }

    private fun parse(ics: String): Doc {
        val nl = if ("\r\n" in ics) "\r\n" else "\n"
        val logical = ArrayList<String>()
        for (p in ics.split(nl).dropLastWhile { it.isEmpty() }) {
            if ((p.startsWith(" ") || p.startsWith("\t")) && logical.isNotEmpty()) logical[logical.size - 1] = logical.last() + nl + p
            else logical.add(p)
        }
        return Doc(nl, logical)
    }

    private fun nameOf(line: String) = line.takeWhile { it != ':' && it != ';' }.uppercase()
    private fun unfold(logical: String) = logical.replace(Regex("\r?\n[ \t]"), "")

    /** Index of the BEGIN:VTODO line and of its END:VTODO line, for the first VTODO without RECURRENCE-ID. */
    private fun todoBounds(lines: List<String>): Pair<Int, Int> {
        var i = 0
        while (i < lines.size) {
            if (lines[i].equals("BEGIN:VTODO", ignoreCase = true)) {
                var j = i + 1
                var depth = 0
                var recurrence = false
                while (j < lines.size && !(depth == 0 && lines[j].equals("END:VTODO", ignoreCase = true))) {
                    val u = lines[j].uppercase()
                    when {
                        u.startsWith("BEGIN:") -> depth++
                        u.startsWith("END:") -> depth--
                        depth == 0 && u.startsWith("RECURRENCE-ID") -> recurrence = true
                    }
                    j++
                }
                if (!recurrence) return i to j
                i = j
            }
            i++
        }
        throw IllegalArgumentException("no VTODO to edit")
    }

    /** Indexes of the VTODO's own property lines, not those inside a nested block such as VALARM. */
    private fun topLevel(lines: List<String>, begin: Int, end: Int): List<Int> {
        val out = ArrayList<Int>()
        var depth = 0
        for (k in begin + 1 until end) {
            val u = lines[k].uppercase()
            when {
                u.startsWith("BEGIN:") -> depth++
                u.startsWith("END:") -> depth--
                depth == 0 -> out.add(k)
            }
        }
        return out
    }

    fun readProperty(ics: String, name: String): String? {
        val doc = parse(ics)
        val (b, e) = todoBounds(doc.lines)
        val line = topLevel(doc.lines, b, e).map { doc.lines[it] }.firstOrNull { nameOf(it) == name.uppercase() } ?: return null
        return unfold(line).substringAfter(':')
    }

    /** The values of every top-level line called [name] (a property may repeat: CATEGORIES, RELATED-TO), in order. */
    fun readAllProperties(ics: String, name: String): List<String> {
        val doc = parse(ics)
        val (b, e) = todoBounds(doc.lines)
        return topLevel(doc.lines, b, e).map { doc.lines[it] }.filter { nameOf(it) == name.uppercase() }.map { unfold(it).substringAfter(':') }
    }

    /** The parameters of a logical line ("LANGUAGE=el" of "SUMMARY;LANGUAGE=el:text"), without the leading semicolon; quoted values may contain colons. */
    private fun paramsOf(line: String): String {
        var quoted = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) return line.substring(0, i).substringAfter(';', "")
        }
        return ""
    }

    /**
     * Sets (or, with a null [value], removes) one single-valued property: every other line of the same name is dropped, so never use it
     * for a repeatable property such as RELATED-TO or ATTACH. An existing line is replaced in place; a new one goes before the first
     * nested block. The old line's parameters are replaced by [params], or kept when [keepParams] is true and [params] is empty.
     */
    fun setProperty(ics: String, name: String, value: String?, params: String = "", keepParams: Boolean = false): String {
        val doc = parse(ics)
        val (begin, end) = todoBounds(doc.lines)
        val key = name.uppercase()
        val mine = topLevel(doc.lines, begin, end).filter { nameOf(doc.lines[it]) == key }
        val kept = if (keepParams && params.isEmpty() && mine.isNotEmpty()) paramsOf(unfold(doc.lines[mine.first()])) else ""
        val p = params.ifEmpty { kept }
        val newLine = value?.let { fold(key + (if (p.isEmpty()) "" else ";$p") + ":" + it, doc.nl) }
        if (mine.isEmpty()) {
            if (newLine != null) {
                val firstNested = (begin + 1 until end).firstOrNull { doc.lines[it].uppercase().startsWith("BEGIN:") } ?: end
                doc.lines.add(firstNested, newLine)
            }
        } else {
            for (k in mine.drop(1).reversed()) doc.lines.removeAt(k)
            if (newLine != null) doc.lines[mine.first()] = newLine else doc.lines.removeAt(mine.first())
        }
        return doc.text()
    }

    /** Start and end index (both included) of every VALARM block directly inside the series VTODO. */
    private fun alarmRanges(lines: List<String>, begin: Int, end: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var depth = 0
        var from = -1
        for (k in begin + 1 until end) {
            val u = lines[k].uppercase()
            when {
                u.startsWith("BEGIN:") -> { if (depth == 0 && u == "BEGIN:VALARM") from = k; depth++ }
                u.startsWith("END:") -> { depth--; if (depth == 0 && from >= 0) { out.add(from..k); from = -1 } }
            }
        }
        return out
    }

    /** The VALARM blocks of the series VTODO, each as its unfolded lines (BEGIN and END included). */
    fun alarmBlocks(ics: String): List<List<String>> {
        val doc = parse(ics)
        val (b, e) = todoBounds(doc.lines)
        return alarmRanges(doc.lines, b, e).map { r -> r.map { unfold(doc.lines[it]) } }
    }

    /**
     * Changes the VALARM blocks of the series VTODO. [edit] gets each block's index and unfolded lines and returns them unchanged (the block
     * is kept byte for byte, folds included), other lines (written in its place), or null (removed). [add] blocks go after the last VALARM,
     * or before END:VTODO when there is none. Nothing outside the blocks changes.
     */
    fun editAlarms(ics: String, edit: (Int, List<String>) -> List<String>?, add: List<List<String>> = emptyList()): String {
        val doc = parse(ics)
        val (b, e) = todoBounds(doc.lines)
        val ranges = alarmRanges(doc.lines, b, e)
        var insertAt = ranges.lastOrNull()?.let { it.last + 1 } ?: e
        for ((i, r) in ranges.withIndex().reversed()) {
            val old = r.map { unfold(doc.lines[it]) }
            val new = edit(i, old)
            if (new == old) continue
            for (k in r.reversed()) doc.lines.removeAt(k)
            insertAt -= r.count()
            if (new != null) { doc.lines.addAll(r.first, new.map { fold(it, doc.nl) }); insertAt += new.size }
        }
        doc.lines.addAll(insertAt, add.flatten().map { fold(it, doc.nl) })
        return doc.text()
    }

    /** Gives the item a new UID in every VTODO it holds (a repeating task's completed copies share the series' UID, and one item must have one UID). */
    fun replaceUid(ics: String, newUid: String): String {
        val doc = parse(ics)
        var depth = 0
        for (k in doc.lines.indices) {
            val u = doc.lines[k].uppercase()
            when {
                u.startsWith("BEGIN:") -> depth++
                u.startsWith("END:") -> depth--
                depth == 2 && nameOf(doc.lines[k]) == "UID" && inVtodo(doc.lines, k) -> doc.lines[k] = "UID:$newUid"
            }
        }
        return doc.text()
    }

    /** True when line [k] sits directly inside a VTODO (not in a nested block such as VALARM). */
    private fun inVtodo(lines: List<String>, k: Int): Boolean {
        var i = k - 1
        while (i >= 0) {
            val u = lines[i].uppercase()
            if (u.startsWith("BEGIN:")) return u == "BEGIN:VTODO"
            if (u.startsWith("END:")) return false
            i--
        }
        return false
    }

    /** DTSTAMP and LAST-MODIFIED to [now], SEQUENCE one higher. The only lines an unchanged edit may alter. */
    fun touch(ics: String, now: Instant): String {
        val s = stamp(now)
        val seq = (readProperty(ics, "SEQUENCE")?.trim()?.toIntOrNull() ?: 0) + 1
        return setProperty(setProperty(setProperty(ics, "DTSTAMP", s), "LAST-MODIFIED", s), "SEQUENCE", seq.toString())
    }

    /** A new open task as the Nextcloud Tasks web app expects it. */
    fun newTask(uid: String, title: String, now: Instant): String {
        val s = stamp(now)
        val nl = "\r\n"
        return listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//BookahTasks//EN", "BEGIN:VTODO",
            "UID:$uid", "DTSTAMP:$s", "CREATED:$s", "LAST-MODIFIED:$s",
            fold("SUMMARY:" + escapeText(title), nl), "STATUS:NEEDS-ACTION",
            "END:VTODO", "END:VCALENDAR",
        ).joinToString(nl) + nl
    }
}

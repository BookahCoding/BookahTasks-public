package dev.bookah.tasks.data

/** What a share becomes: the quick-add title, the link for the URL property, and any other text for the notes. */
data class SharedTask(val title: String, val url: String?, val notes: String?)

/**
 * Share to BookahTasks (plan 4.2.5, docs/phase-5-design.md section 3): splits shared text into a title, the first http(s) link and notes.
 * No network: when only a link is shared, the link itself is the title. Any input is answered (it runs on whatever another app sends);
 * everything is cut to a safe size, and the link can never contain a space or a control character (so it can never add lines to a task).
 */
object SharedText {
    const val MAX_INPUT = 20_000
    const val MAX_TITLE = 250
    const val MAX_NOTES = 10_000
    const val MAX_URL = 2_000

    private val link = Regex("(?i)https?://[^\\s\\p{Cc}<>\"]+")
    private val trailing = ".,;:!?'"
    private val edges = " -–—:|\"'“”«»·•"

    fun parse(text: String?, subject: String?): SharedTask = try {
        split(text, subject)
    } catch (_: Exception) {
        SharedTask(cut(clean(text.orEmpty().take(MAX_TITLE)), MAX_TITLE), null, null)
    }

    private fun split(text: String?, subject: String?): SharedTask {
        val t = cut(text.orEmpty(), MAX_INPUT)
        var url: String? = null
        var rest = t
        link.find(t)?.let { m ->
            var u = m.value.trimEnd { it in trailing }
            if (u.endsWith(")") && !u.contains("(")) u = u.dropLast(1)
            if (u.length in 10..MAX_URL && u.none { it.isISOControl() || it.isWhitespace() || Character.isSurrogate(it) }) {
                url = u
                rest = t.removeRange(m.range.first, m.range.first + u.length)
            }
        }
        val lines = rest.split("\r\n", "\n", "\r").map { tidy(it) }.filter { it.isNotEmpty() }
        val subj = tidy(subject.orEmpty().take(MAX_INPUT).replace('\n', ' ').replace('\r', ' '))
        val (title, notes) = when {
            subj.isNotEmpty() -> subj to lines
            lines.isNotEmpty() -> lines.first() to lines.drop(1)
            else -> (url ?: "") to emptyList()
        }
        return SharedTask(cut(title, MAX_TITLE), url, notes.joinToString("\n").takeIf { it.isNotEmpty() }?.let { cut(it, MAX_NOTES) })
    }

    /** The three parts as handed between screens, made safe again: a one-line title, a valid link or none, notes cut to size. Never throws. */
    fun of(title: String?, url: String?, notes: String?): SharedTask = try {
        val u = url?.trim()?.takeIf { s -> s.length in 10..MAX_URL && link.matches(s) && s.none { it.isISOControl() || it.isWhitespace() || Character.isSurrogate(it) } }
        val n = notes?.let { cut(it, MAX_INPUT) }?.split("\r\n", "\n", "\r")?.map { tidy(it) }?.filter { it.isNotEmpty() }?.joinToString("\n")?.takeIf { it.isNotEmpty() }
        SharedTask(cut(tidy(cut(title.orEmpty(), MAX_INPUT).replace('\n', ' ').replace('\r', ' ')), MAX_TITLE), u, n?.let { cut(it, MAX_NOTES) })
    } catch (_: Exception) {
        SharedTask("", null, null)
    }

    /** One line: control characters gone, runs of spaces made one, separators left at the ends by the removed link trimmed. */
    private fun tidy(line: String): String = clean(line).trim { it in edges || it.isWhitespace() }

    private fun clean(s: String): String = s.map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(Regex("\\s+"), " ")

    /** Cuts to [max] characters without splitting a surrogate pair. */
    private fun cut(s: String, max: Int): String {
        if (s.length <= max) return s
        val c = s.take(max)
        return if (Character.isHighSurrogate(c.last())) c.dropLast(1) else c
    }
}

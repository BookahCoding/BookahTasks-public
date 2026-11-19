package dev.bookah.tasks.data

import java.text.Normalizer
import java.util.Locale

/**
 * Search (plan 4.1, docs/phase-5-design.md section 5): title, notes, tags and the waiting person, every typed word must match (in any of
 * them), case and accent insensitive in Greek and English (accents removed, final sigma read as sigma). Plain text matching only (never a
 * pattern built from what was typed). Runs on every keystroke, so it answers any input and never throws.
 */
object TaskSearch {
    const val MAX_QUERY = 200
    const val MAX_RESULTS = 200
    private val marks = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")

    /** The form both sides are compared in: decomposed, accents and other combining marks dropped, lower case, ς as σ. */
    fun fold(s: String): String = try {
        marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT).replace('ς', 'σ')
    } catch (_: Exception) {
        s.lowercase(Locale.ROOT)
    }

    fun words(query: String): List<String> = try {
        fold(query.take(MAX_QUERY)).split(spaces).map { it.trimStart('#') }.filter { it.isNotEmpty() }.take(20)
    } catch (_: Exception) {
        emptyList()
    }

    fun search(tasks: List<Task>, query: String, includeDone: Boolean): List<Task> = try {
        val ws = words(query)
        if (ws.isEmpty()) emptyList() else {
            tasks.asSequence()
                .filter { !it.isOccurrenceCopy && (includeDone || it.isOpen) }
                .mapNotNull { t ->
                    val title = fold(t.title)
                    val rest = fold(listOfNotNull(t.notes, t.waitingFor, t.tags.joinToString(" ")).joinToString(" "))
                    if (ws.all { w -> title.contains(w) || rest.contains(w) }) Triple(t, ws.all { title.contains(it) }, title) else null
                }
                .sortedWith(compareBy<Triple<Task, Boolean, String>>({ !it.second }, { !it.first.isOpen }, { it.first.due == null }, { it.first.due }, { it.third }))
                .take(MAX_RESULTS)
                .map { it.first }
                .toList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

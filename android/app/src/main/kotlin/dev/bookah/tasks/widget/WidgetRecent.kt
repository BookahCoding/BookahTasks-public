package dev.bookah.tasks.widget

import java.util.concurrent.ConcurrentHashMap

/** Titles of repeating tasks ticked from the widget in the last two minutes ("Next date set"); in memory only, lost if the process dies. */
object WidgetRecent {
    private const val WINDOW_MS = 120_000L
    private val advanced = ConcurrentHashMap<String, Pair<String, Long>>()     // href -> (title, at)

    fun advanced(href: String, title: String, nowMs: Long) { advanced[href] = title to nowMs }

    fun titles(nowMs: Long): List<String> {
        advanced.entries.removeIf { nowMs - it.value.second > WINDOW_MS }
        return advanced.values.sortedByDescending { it.second }.map { it.first }
    }
}

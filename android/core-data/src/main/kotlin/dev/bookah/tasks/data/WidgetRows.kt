package dev.bookah.tasks.data

import java.time.LocalDate
import java.time.LocalTime

/** What the widget needs to know about a task's list. */
data class WidgetList(val href: String, val name: String, val colorHex: String, val editable: Boolean)

/** One line of the widget, already decided: the widget code only draws these. */
sealed interface WidgetRow {
    /** "Overdue · N": one row, the app has the list. */
    data class OverdueFold(val count: Int) : WidgetRow
    /** "Today" (evening = false) or "This evening" (true). */
    data class Heading(val evening: Boolean) : WidgetRow
    data class Item(
        val href: String, val uid: String, val listHref: String, val title: String, val time: LocalTime?,
        val listName: String, val colorHex: String, val repeats: Boolean, val priority: Priority,
        val canTick: Boolean, val done: Boolean, val stamp: String,
    ) : WidgetRow
    data class More(val count: Int) : WidgetRow
    /** A repeating task that was ticked and moved to its next date (no undo here). */
    data class NextDate(val title: String) : WidgetRow
}

/** Today's tasks as widget rows. Pure: no Android, no clock (the caller passes [today] and [nowMs]). */
object WidgetRows {
    const val JUST_DONE_MS = 120_000L
    const val MAX_ITEMS = 30

    /** What a tap on a circle is allowed to act on: if the task no longer has this stamp, the tap does nothing. */
    fun stamp(t: Task): String = "${t.isOpen}|${t.start}|${t.due}"

    fun build(
        tasks: List<Task>, lists: Map<Long, WidgetList>, today: LocalDate, nowMs: Long, eveningHour: Int,
        advancedTitles: List<String> = emptyList(), maxItems: Int = MAX_ITEMS,
    ): List<WidgetRow> {
        val justDone = tasks.filter {
            it.status == TaskStatus.Completed && !it.isOccurrenceCopy && !it.someday &&
                it.completedAtMs != null && it.completedAtMs in (nowMs - JUST_DONE_MS)..nowMs
        }
        val doneHrefs = justDone.map { it.href }.toSet()
        // grouped as if still open, so a finished task keeps the place it had
        val reopened = justDone.map { it.copy(status = TaskStatus.NeedsAction, completedAtMs = null) }
        val view = TaskViews.today(tasks.filter { it.isOpen } + reopened, today, eveningHour)

        val out = ArrayList<WidgetRow>()
        advancedTitles.forEach { out += WidgetRow.NextDate(it) }
        var budget = maxItems
        var hidden = 0
        fun item(t: Task): WidgetRow.Item {
            val l = lists[t.listId]
            val done = t.href in doneHrefs
            return WidgetRow.Item(
                href = t.href, uid = t.uid, listHref = l?.href.orEmpty(), title = t.title, time = TaskViews.timeToday(t, today),
                listName = l?.name.orEmpty(), colorHex = l?.colorHex ?: "#808080", repeats = t.repeats, priority = t.priority,
                canTick = l?.editable == true, done = done, stamp = stamp(t),
            )
        }
        fun add(t: Task) { if (budget > 0) { out += item(t); budget-- } else hidden++ }

        val overdueOpen = view.overdue.filter { it.href !in doneHrefs }
        if (overdueOpen.isNotEmpty()) out += WidgetRow.OverdueFold(overdueOpen.size)
        view.overdue.filter { it.href in doneHrefs }.forEach { add(it) }
        fun group(list: List<Task>, evening: Boolean) {
            if (list.isEmpty()) return
            if (budget <= 0) { hidden += list.size; return }
            out += WidgetRow.Heading(evening)
            list.forEach { add(it) }
        }
        group(view.today, false)
        group(view.evening, true)
        if (hidden > 0) out += WidgetRow.More(hidden)
        return out
    }
}

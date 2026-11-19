package dev.bookah.tasks.data

import java.time.Instant
import java.time.ZoneId

/**
 * The tick as one function, for the widget and the notification's Done button (the app's own tick keeps its Undo bookkeeping in the view model).
 * The caller holds the write lock. Nothing is written unless the task is still exactly what was drawn ([expectedStamp], null = no check).
 */
object TaskCompletion {
    sealed interface Outcome {
        data object Done : Outcome
        data object Advanced : Outcome
        data object Reopened : Outcome
        data class Skipped(val reason: String) : Outcome
    }

    fun tick(store: TaskStore, outbox: Outbox, href: String, expectedStamp: String?, zone: ZoneId, now: Instant): Outcome {
        val obj = store.objectByHref(href) ?: return Outcome.Skipped("gone")
        if (store.lists().firstOrNull { it.id == obj.listId }?.editable != true) return Outcome.Skipped("read-only")
        val task = TaskParser.parse(obj, zone).firstOrNull { !it.isOccurrenceCopy } ?: return Outcome.Skipped("unreadable")
        if (expectedStamp != null && WidgetRows.stamp(task) != expectedStamp) return Outcome.Skipped("changed")
        if (!task.isOpen) return Outcome.Skipped("already finished")
        val c = TaskEdit.complete(obj.ics, now, zone)
        if (c is Completion.Unchanged) return Outcome.Skipped("next date unknown")
        try { outbox.edit(href, c.ics) } catch (e: IllegalStateException) { return Outcome.Skipped("conflict or being deleted") }
        if (c is Completion.Done) {
            val all = TaskParser.parseAll(store.allObjects(), zone)
            for (k in TaskViews.descendants(all, task.uid, openOnly = true)) {
                val kobj = store.objectByHref(k.href) ?: continue
                val kc = TaskEdit.complete(kobj.ics, now, zone)
                if (kc is Completion.Unchanged) continue
                try { outbox.edit(k.href, kc.ics) } catch (_: IllegalStateException) { /* that subtask stays as it is */ }
            }
        }
        return if (c is Completion.Advanced) Outcome.Advanced else Outcome.Done
    }

    fun reopen(store: TaskStore, outbox: Outbox, href: String, zone: ZoneId, now: Instant): Outcome {
        val obj = store.objectByHref(href) ?: return Outcome.Skipped("gone")
        if (store.lists().firstOrNull { it.id == obj.listId }?.editable != true) return Outcome.Skipped("read-only")
        val task = TaskParser.parse(obj, zone).firstOrNull { !it.isOccurrenceCopy } ?: return Outcome.Skipped("unreadable")
        if (task.status != TaskStatus.Completed) return Outcome.Skipped("not finished")
        // the subtasks the tick closed carry the very same finish moment as the parent: they come back with it; subtasks finished earlier stay finished
        val closedWithIt = if (task.completedAtMs == null) emptyList() else
            TaskViews.descendants(TaskParser.parseAll(store.allObjects(), zone), task.uid, openOnly = false)
                .filter { it.status == TaskStatus.Completed && it.completedAtMs == task.completedAtMs }
        try { outbox.edit(href, TaskEdit.reopen(obj.ics, now)) } catch (e: IllegalStateException) { return Outcome.Skipped("conflict or being deleted") }
        for (k in closedWithIt) {
            val kobj = store.objectByHref(k.href) ?: continue
            try { outbox.edit(k.href, TaskEdit.reopen(kobj.ics, now)) } catch (_: IllegalStateException) { /* that subtask stays as it is */ }
        }
        return Outcome.Reopened
    }
}

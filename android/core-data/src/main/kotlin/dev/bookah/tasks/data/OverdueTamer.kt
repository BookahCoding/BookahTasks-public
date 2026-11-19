package dev.bookah.tasks.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What "move all overdue tasks" will write. [writes] and [old] map a task's address to its new and its current text. The other numbers
 * count the tasks that are left as they are: in a read-only list ([readOnly]), no longer open and overdue or gone since the screen showed
 * them ([stale]), or with a change waiting for the owner, such as a conflict, a refused change or a delete ([blocked]).
 */
data class MovePlan(val writes: Map<String, String>, val old: Map<String, String>, val readOnly: Int, val stale: Int, val blocked: Int, val repeating: Int = 0) {
    val skipped: Int get() = readOnly + stale + blocked + repeating
}

/** The overdue tamer (plan 4.2.2): every overdue task moved to one target at once, with the same rule as one task ([Reschedule]). */
object OverdueTamer {
    /** Addresses whose queued change waits for the owner: a move must not touch them (Outbox.edit would refuse most of them anyway). */
    fun blocked(queue: List<OutboxItem>): Set<String> =
        queue.filter { it.state != OutboxState.Pending || it.kind == OutboxKind.Delete }.map { it.href }.toSet()

    /**
     * Plans the move of the tasks the owner saw ([shown]) from their stored text as it is NOW ([textOf]), so a task that was finished,
     * moved or deleted meanwhile is left alone. Called under the write lock; never throws for one odd task (it is counted as stale).
     */
    fun plan(
        shown: List<Task>, textOf: (String) -> String?, lists: List<TaskList>, blocked: Set<String>,
        target: RescheduleTarget, today: LocalDate, eveningHour: Int, zone: ZoneId, now: Instant,
    ): MovePlan {
        val writable = lists.filter { it.editable }.map { it.id }.toSet()
        val writes = LinkedHashMap<String, String>()
        val old = LinkedHashMap<String, String>()
        var readOnly = 0; var stale = 0; var blockedCount = 0; var repeating = 0
        for (t in shown.distinctBy { it.href }) {
            if (t.isOccurrenceCopy) { stale++; continue }
            if (t.listId !in writable) { readOnly++; continue }
            // ruling (review, the owner to confirm): a repeating task is moved one by one, so its series is never shifted by a bulk move
            if (t.repeats) { repeating++; continue }
            if (t.href in blocked) { blockedCount++; continue }
            val text = textOf(t.href)
            val current = text?.let { runCatching { TaskParser.parse(StoredObject(t.href, t.listId, "", it), zone).firstOrNull { c -> !c.isOccurrenceCopy && c.uid == t.uid } }.getOrNull() }
            if (text == null || current == null || !TaskViews.isOverdue(current, today)) { stale++; continue }
            if (current.repeats) { repeating++; continue }
            val out = runCatching { Reschedule.applyTo(text, target, today, eveningHour, zone) }.getOrNull()
            if (out == null) { stale++; continue }
            writes[t.href] = IcsPatcher.touch(out, now)
            old[t.href] = text
        }
        return MovePlan(writes, old, readOnly, stale, blockedCount, repeating)
    }
}

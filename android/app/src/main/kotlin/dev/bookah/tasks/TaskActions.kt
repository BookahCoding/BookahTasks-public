package dev.bookah.tasks

import dev.bookah.tasks.data.OutboxKind
import dev.bookah.tasks.data.Priority
import dev.bookah.tasks.data.Task
import dev.bookah.tasks.data.TaskWhen

/** Everything the screens can ask for on a task. The ViewModel applies each change on the phone at once and queues it for Nextcloud. */
interface TaskActions {
    /** Tick: finishes an open task (a repeating one moves to its next date; a parent also finishes its open subtasks) or reopens a finished one. */
    fun toggle(t: Task)
    fun setTitle(t: Task, title: String)
    fun setNotes(t: Task, notes: String?)
    fun setDates(t: Task, start: TaskWhen?, due: TaskWhen?)
    fun setPriority(t: Task, p: Priority)
    fun setTags(t: Task, tags: List<String>)
    fun setSomeday(t: Task, on: Boolean)
    /** The repeat rule (null = does not repeat); an undated task gets its first date (see [dev.bookah.tasks.data.TaskEdit.setRepeat]). */
    fun setRepeat(t: Task, spec: dev.bookah.tasks.data.RepeatSpec?)
    /** The task's own reminder list; it replaces the defaults (an empty list = no reminders). Other apps' alarms stay. */
    fun setReminders(t: Task, list: List<dev.bookah.tasks.data.ReminderChoice>)
    fun useDefaultReminders(t: Task)
    /** Moves the task's date (see [dev.bookah.tasks.data.Reschedule]); Undo puts it back. */
    fun reschedule(t: Task, target: dev.bookah.tasks.data.RescheduleTarget)
    fun addSubtask(parent: Task, title: String)
    /** Deletes the task and everything under it; one Undo brings it all back. */
    fun delete(t: Task)
    /** Moves the task and everything under it to another list; one Undo moves it back. */
    fun move(t: Task, listId: Long)
    /** A new task. [NewTask.listId] null means the current list, else the default list, else the inbox. */
    fun add(task: NewTask)
    /**
     * Manual order: puts [t] after the task at address [after] and before the one at [before] (the rows around the new place as the list
     * shows them, null = none on that side), among the tasks at its level in its list. Neighbours, not an index, so a sync meanwhile cannot
     * shift the target. Normally one task is written (see [dev.bookah.tasks.data.TaskOrder]); the parent never changes.
     */
    fun reorder(t: Task, after: String?, before: String?)
    /** Waiting for (Phase 5): on [person] (optional) until [checkBack]; Undo puts it back. */
    fun setWaiting(t: Task, person: String?, checkBack: java.time.LocalDate) {}
    /** Ends waiting: the tag and the person line go, the start date stays. */
    fun endWaiting(t: Task) {}
}

/** What the quick-add sheet collects: a title, when it starts, its deadline, a priority and a list. */
data class NewTask(
    val title: String, val start: TaskWhen? = null, val due: TaskWhen? = null, val priority: Priority = Priority.None, val listId: Long? = null, val tags: List<String> = emptyList(),
    val repeat: dev.bookah.tasks.data.RepeatSpec? = null,
    /** Phase 5 share: the shared link (URL property) and any other shared text (notes). */
    val url: String? = null, val notes: String? = null,
)

/** What a share or the tile puts into the quick-add sheet. [token] tells one share from the next, so a new share replaces the typed text. */
data class QuickPrefill(val title: String, val url: String?, val notes: String?, val token: Long)

/** The bar at the bottom after a tick, delete or move. [token] tells one bar from the next. */
data class UndoBar(val message: UiText, val token: Long)

/** A change that met a changed server copy: the owner chooses. [server] is the server's title, null when the task is gone there. */
data class ConflictInfo(val id: Long, val mine: String, val server: String?, val isDelete: Boolean)

/** A change the server refused ([code] is its HTTP status): retry or discard. */
data class FailedInfo(val id: Long, val title: String, val code: Int, val kind: OutboxKind)

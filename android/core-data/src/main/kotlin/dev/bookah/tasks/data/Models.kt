package dev.bookah.tasks.data

/** What a Nextcloud collection can hold: tasks (VTODO), events (VEVENT) or both. Only lists that hold tasks are synced. */
enum class ListKind {
    Tasks, Events, Both;

    val holdsTasks: Boolean get() = this != Events
}

/**
 * One list shown in the app. [href] is the server's own path (taken from its answer, never built from the login name:
 * the server writes the user id with a different letter case).
 */
data class TaskList(
    val id: Long,
    val href: String,
    val name: String,
    /** "#RRGGBB" as set in Nextcloud, shown exactly as it is. */
    val colorHex: String,
    val kind: ListKind,
    val editable: Boolean,
    val visible: Boolean,
    val syncToken: String?,
    /** The server's order for lists (Nextcloud "calendar-order"); lower comes first. */
    val order: Int,
    /** The day strip shows this calendar's events (Phase 5; the owner's switch in Settings, kept across syncs). */
    val showEvents: Boolean = true,
) {
    val holdsEvents: Boolean get() = kind != ListKind.Tasks
}

/** A list as the server describes it. */
data class RemoteList(
    val href: String,
    val name: String,
    val colorHex: String?,
    val canWrite: Boolean,
    val kind: ListKind,
    val order: Int = 0,
)

/** One stored object: the raw iCalendar text with its ETag. */
data class StoredObject(val href: String, val listId: Long, val etag: String, val ics: String)

/** What a sync-collection request returned. [removed] are hrefs the server says are gone. */
data class SyncDelta(val newToken: String?, val changed: List<ChangedEntry>, val removed: List<String>)
data class ChangedEntry(val href: String, val etag: String)

/** A fetched object. */
data class RemoteObject(val href: String, val etag: String, val ics: String)

/** What one sync run did, for the "last synced" line and for tests. */
data class SyncReport(
    val lists: Int,
    val objectsChanged: Int,
    val objectsRemoved: Int,
    val fullResyncs: Int,
    val offline: Boolean,
    val error: String? = null,
    /** The HTTP status behind [error] (401 wrong password, 403, 404 ...), 0 when the server did not give one. */
    val errorCode: Int = 0,
    /** Changes sent to the server this run, changes held back as conflicts, changes that could not be sent (retry or refusal). */
    val pushed: Int = 0,
    val conflicts: Int = 0,
    val pushFailed: Int = 0,
    /** Event-only calendars synced for the day strip, events fetched, and calendars that could not be synced this time (never an error). */
    val eventLists: Int = 0,
    val eventsChanged: Int = 0,
    val eventErrors: Int = 0,
    /** The event sync stopped at its size or time limit this run; what was fetched is kept and the next sync goes on from there. */
    val eventsIncomplete: Boolean = false,
    /** Task lists that could not be read this run (an odd answer, a storage error): the others were synced, these keep their cached copy. */
    val listErrors: Int = 0,
)

/** The server refused the stored sync token (403 or 409): the list must be fetched in full again. */
class SyncTokenInvalidException : Exception("sync token no longer valid")

/** The server answered but not as a calendar server should (wrong address, wrong password, ...). */
class CalDavException(val code: Int, message: String) : Exception(message)

const val DEFAULT_COLOR = "#245A86"

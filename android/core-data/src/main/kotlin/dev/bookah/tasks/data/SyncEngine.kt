package dev.bookah.tasks.data

import kotlinx.coroutines.CancellationException
import java.io.IOException

/** What one push did. A push that goes offline or meets a wrong password stops at once and leaves every row as it was. */
data class PushReport(
    val pushed: Int, val conflicts: Int, val failed: Int, val offline: Boolean,
    val errorCode: Int = 0, val error: String? = null,
)

/**
 * Brings the phone's copy and Nextcloud in line: first the queued changes go up ([push]), then the lists come down (the pull).
 * Only lists that hold tasks are fetched. A failed run never removes anything that is already cached, and a pull never overwrites,
 * brings back or removes an item that still has a queued change.
 */
class SyncEngine(
    private val store: TaskStore, private val chunk: Int = 50,
    /** Also sync the event-only calendars for the day strip (the app); the live checks that only need tasks switch it off. */
    private val events: Boolean = true,
    /** At most this many events are fetched in one sync run (the rest come next time). */
    private val maxEventObjects: Int = 3_000,
    /** And for at most this long (milliseconds) per run. */
    private val maxEventMs: Long = 90_000,
) {

    suspend fun sync(api: CalDavApi): SyncReport {
        val push = push(api)
        if (push.offline || push.errorCode != 0) {
            return SyncReport(
                store.lists().size, 0, 0, 0, offline = push.offline, error = push.error, errorCode = push.errorCode,
                pushed = push.pushed, conflicts = push.conflicts, pushFailed = push.failed,
            )
        }
        return pull(api).copy(pushed = push.pushed, conflicts = push.conflicts, pushFailed = push.failed)
    }

    /**
     * Sends the queued rows in the order they were made. A row waiting on a conflict, a refusal or a retry holds back later rows of the same
     * item only. Only a wrong password (401) or a lost connection ends the push; trouble with one item never blocks the others.
     */
    suspend fun push(api: CalDavApi): PushReport {
        var pushed = 0
        var conflicts = 0
        var failed = 0
        val held = HashSet<String>()
        for (queued in store.outboxAll()) {
            // the owner may have changed or removed the row since the queue was read: send what is there now
            val row = synchronized(store) { store.outboxAll().firstOrNull { it.id == queued.id } } ?: continue
            if (row.state != OutboxState.Pending) { held += row.href; if (row.state == OutboxState.Failed) failed++; continue }
            if (row.href in held) continue
            // only task text is ever sent: anything else in a row (a bug, odd data) is refused here and waits for the owner
            if (row.kind != OutboxKind.Delete && notATask(row.ics)) {
                synchronized(store) { store.outboxAll().firstOrNull { it.id == row.id }?.let { store.outboxUpdate(it.copy(state = OutboxState.Failed, lastCode = 0)) } }
                held += row.href; failed++
                continue
            }
            // the delete half of a move waits until its create has reached the server (the create's row is gone)
            if (row.after != null && store.outboxAll().any { it.href == row.after }) { held += row.href; continue }
            val result = try {
                when (row.kind) {
                    OutboxKind.Create -> api.put(row.href, row.ics!!, ifMatch = null, createOnly = true)
                    OutboxKind.Edit -> api.put(row.href, row.ics!!, ifMatch = row.baseEtag, createOnly = false)
                    OutboxKind.Delete -> api.delete(row.href, row.baseEtag)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return PushReport(pushed, conflicts, failed, offline = true, error = e.message)
            } catch (e: CalDavException) {
                return PushReport(pushed, conflicts, failed, offline = false, errorCode = e.code, error = e.message)
            }
            when (result) {
                is WriteResult.Ok -> {
                    try {
                        settle(api, row, result.etag)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        return PushReport(pushed, conflicts, failed, offline = true, error = e.message)
                    } catch (e: CalDavException) {
                        return PushReport(pushed, conflicts, failed, offline = false, errorCode = e.code, error = e.message)
                    }
                    pushed++
                }
                WriteResult.PreconditionFailed -> {
                    val server = try {
                        api.get(row.href)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        return PushReport(pushed, conflicts, failed, offline = true, error = e.message)
                    } catch (e: CalDavException) {
                        if (e.code == 401) return PushReport(pushed, conflicts, failed, offline = false, errorCode = 401, error = e.message)
                        held += row.href; failed++          // the server copy cannot be read right now: the row stays queued and is tried again
                        continue
                    }
                    if (row.kind != OutboxKind.Delete && server != null && server.ics == row.ics) {
                        // my write reached the server earlier and only its answer was lost: this is not a conflict
                        try {
                            settle(api, row, server.etag)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IOException) {
                            return PushReport(pushed, conflicts, failed, offline = true, error = e.message)
                        } catch (e: CalDavException) {
                            return PushReport(pushed, conflicts, failed, offline = false, errorCode = e.code, error = e.message)
                        }
                        pushed++
                        continue
                    }
                    synchronized(store) {
                        val now = store.outboxAll().firstOrNull { it.id == row.id } ?: return@synchronized
                        store.outboxUpdate(now.copy(state = OutboxState.Conflict, lastCode = 412, serverEtag = server?.etag, serverIcs = server?.ics))
                    }
                    held += row.href; conflicts++
                }
                is WriteResult.Rejected -> {
                    synchronized(store) {
                        val now = store.outboxAll().firstOrNull { it.id == row.id } ?: return@synchronized
                        val retryable = result.code >= 500
                        store.outboxUpdate(now.copy(attempts = now.attempts + 1, lastCode = result.code, state = if (retryable) OutboxState.Pending else OutboxState.Failed))
                    }
                    held += row.href; failed++
                }
            }
        }
        return PushReport(pushed, conflicts, failed, offline = false)
    }

    /**
     * After a successful send: keep the new ETag on the local copy, then decide what the row becomes. If the owner changed the same item
     * while it was being sent, the newer change stays queued against the new ETag; a delete made meanwhile is still sent.
     */
    private suspend fun settle(api: CalDavApi, row: OutboxItem, etag: String?) {
        val newEtag = if (row.kind == OutboxKind.Delete) null else etag ?: api.get(row.href)?.etag
        synchronized(store) {
            val now = store.outboxAll().firstOrNull { it.id == row.id }
            val current = store.objectByHref(row.href)
            if (newEtag != null && current != null) store.upsertObjects(row.listId, listOf(RemoteObject(row.href, newEtag, current.ics)))
            when {
                now == null -> {
                    // the owner deleted a task whose create was being sent: the server has it now, so the delete must still go out
                    if (row.kind == OutboxKind.Create && current == null && newEtag != null) {
                        store.outboxInsert(OutboxItem(0, OutboxKind.Delete, row.listId, row.href, newEtag, null, row.createdAtMs))
                    }
                    // the owner undid a delete while it was being sent: the server has lost the item, so it is created again from the phone's copy
                    if (row.kind == OutboxKind.Delete && current != null) {
                        store.upsertObjects(row.listId, listOf(RemoteObject(row.href, "", current.ics)))
                        store.outboxInsert(OutboxItem(0, OutboxKind.Create, row.listId, row.href, null, current.ics, row.createdAtMs))
                    }
                }
                now.kind == OutboxKind.Delete && row.kind != OutboxKind.Delete ->
                    store.outboxUpdate(now.copy(baseEtag = newEtag, state = OutboxState.Pending, attempts = 0, lastCode = 0))
                row.kind != OutboxKind.Delete && now.ics != row.ics ->
                    store.outboxUpdate(now.copy(kind = OutboxKind.Edit, baseEtag = newEtag ?: current?.etag, state = OutboxState.Pending, attempts = 0))
                else -> store.outboxRemove(row.id)
            }
        }
    }

    private suspend fun pull(api: CalDavApi): SyncReport {
        var changed = 0
        var removed = 0
        var full = 0
        val lists: List<TaskList>
        val eventLists: List<TaskList>
        try {
            val all = store.replaceLists(api.listLists())
            lists = all.filter { it.kind.holdsTasks }
            eventLists = if (events) all.filter { it.kind == ListKind.Events } else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return SyncReport(store.lists().size, 0, 0, 0, offline = true, error = e.message)
        } catch (e: CalDavException) {
            return SyncReport(store.lists().size, 0, 0, 0, offline = false, error = e.message, errorCode = e.code)
        }
        var listErrors = 0
        try {
            for (list in lists) try {
                var token = list.syncToken
                var attempt = 0
                while (true) {
                    try {
                        val delta = api.syncCollection(list.href, token)
                        // fetch before touching the store, so a broken download leaves the old state in place
                        val fetched = ArrayList<RemoteObject>()
                        for (part in delta.changed.chunked(chunk)) fetched += api.multiget(list.href, part.map { it.href })
                        synchronized(store) {
                            // items with a queued change are the phone's to decide: the pull neither overwrites, nor brings back, nor removes them
                            val locked = store.outboxAll().map { it.href }.toSet()
                            val keep = if (token == null) store.objects(list.id).filter { it.href in locked } else emptyList()
                            if (token == null) store.clearObjects(list.id)          // a full fetch replaces everything
                            val gone = delta.removed.filter { it !in locked }
                            if (gone.isNotEmpty()) store.deleteObjects(list.id, gone)
                            val take = fetched.filter { it.href !in locked }
                            if (take.isNotEmpty()) store.upsertObjects(list.id, take)
                            if (keep.isNotEmpty()) store.upsertObjects(list.id, keep.map { RemoteObject(it.href, it.etag, it.ics) })
                            store.setSyncToken(list.id, delta.newToken)
                            changed += take.size
                            removed += gone.size
                        }
                        break
                    } catch (e: SyncTokenInvalidException) {
                        if (attempt++ > 0) throw CalDavException(0, "The server keeps refusing the sync token.")
                        full++
                        store.setSyncToken(list.id, null)
                        token = null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw e                                       // the network: the whole run stops (below)
            } catch (e: CalDavException) {
                throw e                                       // a wrong password or a refusal: the whole run stops (below)
            } catch (e: Exception) {
                // anything else with this one list (an answer that is not valid XML, a storage error): counted, its cached copy stays,
                // and the other lists are still synced. Nothing may escape sync(): sign-in, refresh and the worker call it.
                listErrors++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return SyncReport(lists.size, changed, removed, full, offline = true, error = e.message, listErrors = listErrors)
        } catch (e: CalDavException) {
            return SyncReport(lists.size, changed, removed, full, offline = false, error = e.message, errorCode = e.code, listErrors = listErrors)
        }
        val ev = pullEvents(api, eventLists)
        return SyncReport(
            lists.size, changed, removed, full, offline = false, eventLists = eventLists.size, eventsChanged = ev.changed, eventErrors = ev.errors,
            eventsIncomplete = ev.incomplete, listErrors = listErrors,
        )
    }

    private class EventPull(val changed: Int, val errors: Int, val incomplete: Boolean)

    /**
     * The event-only calendars for the day strip (docs/phase-5-design.md section 2): the same sync-collection and multiget as for tasks, into
     * their own table. Read only: nothing here is ever queued or written.
     * - Written chunk by chunk (each chunk's day spans are worked out outside any lock), so a download cut off halfway keeps what arrived;
     *   the next run fetches only events whose ETag the phone does not have yet. The sync token is stored only when a calendar is complete,
     *   and only then does a full fetch remove the events the server no longer lists.
     * - At most [maxEventObjects] events and [maxEventMs] per run; the rest come with the next sync.
     * - An event larger than [MAX_EVENT_CHARS] is skipped (it could not be read back from the phone's database).
     * - Trouble with one calendar (a refusal, a lost connection, an odd answer) is only counted and the next calendar is tried: the task
     *   sync has already finished, its result stands, and that calendar's cached events stay as they were.
     */
    private suspend fun pullEvents(api: CalDavApi, eventLists: List<TaskList>): EventPull {
        var changed = 0
        var errors = 0
        var incomplete = false
        var budget = maxEventObjects
        val until = System.currentTimeMillis() + maxEventMs
        fun outOfRoom() = budget <= 0 || System.currentTimeMillis() > until
        for (list in eventLists) {
            if (outOfRoom()) { incomplete = true; break }
            try {
                var token = list.syncToken
                var attempt = 0
                while (true) {
                    try {
                        val delta = api.syncCollection(list.href, token)
                        val have = store.eventEtags(list.id)
                        val need = delta.changed.filter { it.etag.isEmpty() || have[it.href] != it.etag }
                        var complete = true
                        for (part in need.chunked(chunk)) {
                            if (outOfRoom()) { complete = false; break }
                            val fetched = api.multiget(list.href, part.map { it.href }).filter { it.ics.length <= MAX_EVENT_CHARS }
                            budget -= part.size
                            val spans = fetched.map { EventSpan.of(it.ics) }
                            if (fetched.isNotEmpty()) store.upsertEvents(list.id, fetched, spans)
                            changed += fetched.size
                        }
                        if (delta.removed.isNotEmpty()) store.deleteEvents(list.id, delta.removed)
                        if (!complete) { incomplete = true; break }
                        if (token == null) {
                            val listed = delta.changed.map { it.href }.toSet()
                            val gone = have.keys.filter { it !in listed }
                            if (gone.isNotEmpty()) store.deleteEvents(list.id, gone)
                        }
                        store.setSyncToken(list.id, delta.newToken)
                        break
                    } catch (e: SyncTokenInvalidException) {
                        if (attempt++ > 0) throw CalDavException(0, "The server keeps refusing the sync token.")
                        store.setSyncToken(list.id, null)
                        token = null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors++
            }
        }
        return EventPull(changed, errors, incomplete)
    }

    /** Calendar text that holds an event, or no task at all: never sent (BookahTasks only ever writes tasks). */
    private fun notATask(ics: String?): Boolean {
        if (ics == null) return true
        val calendar = ics.contains("BEGIN:VCALENDAR", ignoreCase = true)
        val task = ics.contains("BEGIN:VTODO", ignoreCase = true)
        return ics.contains("BEGIN:VEVENT", ignoreCase = true) || (calendar && !task)
    }

    companion object {
        /** An event text longer than this is not kept (SQLite on Android cannot read back a row of about 2 MB). */
        const val MAX_EVENT_CHARS = 1_000_000
    }
}

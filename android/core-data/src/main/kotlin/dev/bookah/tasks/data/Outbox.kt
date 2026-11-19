package dev.bookah.tasks.data

enum class OutboxKind { Create, Edit, Delete }

/** Pending = waiting to be sent (or retried); Conflict = the server changed the item meanwhile, the owner must choose; Failed = refused by the server (4xx), needs the owner. */
enum class OutboxState { Pending, Conflict, Failed }

/**
 * One queued change. At most one row per item ([href]). [baseEtag] is the ETag the change was made against (null for a create).
 * [ics] is the full new item text (null for a delete). In a conflict [serverEtag] and [serverIcs] hold the server's current copy
 * ([serverIcs] null = the item is gone on the server).
 */
data class OutboxItem(
    val id: Long,
    val kind: OutboxKind,
    val listId: Long,
    val href: String,
    val baseEtag: String?,
    val ics: String?,
    val createdAtMs: Long,
    val attempts: Int = 0,
    val state: OutboxState = OutboxState.Pending,
    val lastCode: Int = 0,
    val serverEtag: String? = null,
    val serverIcs: String? = null,
    /**
     * Set on the delete half of a move: the address of the create that must reach the server first. The delete waits while that create
     * is still queued, so a refused create never costs the original. Such a delete keeps the original text in [ics] (never sent) so a
     * cancelled move can put it back.
     */
    val after: String? = null,
)

/**
 * Applies every change to the phone's copy at once and queues it for the server, one row per item (repeated edits share a row).
 * All methods lock the store, as does the push in [SyncEngine], so a change made while a push is running is never lost.
 */
class Outbox(private val store: TaskStore, private val now: () -> Long = System::currentTimeMillis) {

    fun create(list: TaskList, uid: String, ics: String): String = createAt(list, list.href.trimEnd('/') + "/" + uid + ".ics", ics)

    /** Creates an item at an address chosen by the caller (a move keeps the old file name; an undo of a delete keeps the old address). */
    fun createAt(list: TaskList, href: String, ics: String): String = synchronized(store) {
        // an unsent delete at this address (the owner moves a task back before anything was sent): cancel it and keep the original
        store.outboxAll().firstOrNull { it.href == href && it.kind == OutboxKind.Delete && it.state != OutboxState.Conflict }?.let { d ->
            store.outboxRemove(d.id)
            store.upsertObjects(list.id, listOf(RemoteObject(href, d.baseEtag ?: "", ics)))
            if (d.ics != ics) {
                store.outboxInsert(
                    if (d.baseEtag != null) OutboxItem(0, OutboxKind.Edit, list.id, href, d.baseEtag, ics, now())
                    else OutboxItem(0, OutboxKind.Create, list.id, href, null, ics, now()),
                )
            }
            return@synchronized href
        }
        // the queue row first: if the process dies between the two writes, the task is still sent (a row without a local copy heals on the next pull)
        store.outboxInsert(OutboxItem(0, OutboxKind.Create, list.id, href, null, ics, now()))
        store.upsertObjects(list.id, listOf(RemoteObject(href, "", ics)))
        href
    }

    fun edit(href: String, newIcs: String): Unit = synchronized(store) {
        val obj = store.objectByHref(href) ?: throw IllegalStateException("No such item on the phone")
        val row = store.outboxAll().firstOrNull { it.href == href }
        when {
            row == null -> store.outboxInsert(OutboxItem(0, OutboxKind.Edit, obj.listId, href, obj.etag, newIcs, now()))
            row.state == OutboxState.Conflict -> throw IllegalStateException("Resolve the conflict first")
            row.kind == OutboxKind.Delete -> throw IllegalStateException("The item is being deleted")
            else -> store.outboxUpdate(row.copy(ics = newIcs, state = OutboxState.Pending, attempts = 0, lastCode = 0))
        }
        store.upsertObjects(obj.listId, listOf(RemoteObject(href, obj.etag, newIcs)))
    }

    /** What [editEach] did: the addresses written, and those it could not write (gone, in conflict, being deleted). */
    data class EachResult(val written: List<String>, val failed: List<String>)

    /**
     * Several edits under one lock, each on its own: one task that cannot be written (in conflict, being deleted, gone) is counted and
     * the others still go. Used by the overdue tamer's "move all" and its Undo.
     */
    fun editEach(changes: Map<String, String>, expected: Map<String, String>? = null): EachResult = synchronized(store) {
        val written = ArrayList<String>()
        val failed = ArrayList<String>()
        for ((href, ics) in changes) {
            try {
                // compare and set: a task whose text is no longer what the plan was made from (a pull landed in between) is left alone
                val want = expected?.get(href)
                if (want != null && store.objectByHref(href)?.ics != want) { failed += href; continue }
                edit(href, ics); written += href
            } catch (_: Exception) {
                failed += href
            }
        }
        EachResult(written, failed)
    }

    fun delete(href: String): Unit = synchronized(store) {
        val obj = store.objectByHref(href) ?: throw IllegalStateException("No such item on the phone")
        val row = store.outboxAll().firstOrNull { it.href == href }
        when {
            row == null -> store.outboxInsert(OutboxItem(0, OutboxKind.Delete, obj.listId, href, obj.etag, null, now()))
            row.state == OutboxState.Conflict -> throw IllegalStateException("Resolve the conflict first")
            row.kind == OutboxKind.Create -> store.outboxRemove(row.id)
            // a queued edit's text is kept on the delete row (never sent), so an Undo of the delete can queue the edit again
            else -> store.outboxUpdate(row.copy(kind = OutboxKind.Delete, state = OutboxState.Pending, attempts = 0, lastCode = 0))
        }
        store.deleteObjects(obj.listId, listOf(href))
    }

    /**
     * Undo of a delete. If the delete has not been sent yet its row is dropped and the item comes back as it was (same ETag); if it was
     * already sent, the item is created again at the same address.
     */
    fun recreate(listId: Long, href: String, ics: String): Unit = synchronized(store) {
        val row = store.outboxAll().firstOrNull { it.href == href }
        when {
            row != null && row.kind == OutboxKind.Delete -> {
                // a delete that carried an edit gives the edit back; a plain delete just disappears
                if (row.ics != null && row.after == null) store.outboxUpdate(row.copy(kind = OutboxKind.Edit, ics = ics, state = OutboxState.Pending, attempts = 0, lastCode = 0))
                else store.outboxRemove(row.id)
                store.upsertObjects(listId, listOf(RemoteObject(href, row.baseEtag ?: "", ics)))
            }
            row != null || store.objectByHref(href) != null -> throw IllegalStateException("The item is not deleted")
            else -> {
                store.outboxInsert(OutboxItem(0, OutboxKind.Create, listId, href, null, ics, now()))
                store.upsertObjects(listId, listOf(RemoteObject(href, "", ics)))
            }
        }
    }

    /**
     * Moves an item to another list: it is created there first and then deleted here (both queued), so an interruption leaves a copy in
     * each list, never none. The file name is kept. Returns the new address.
     */
    fun move(href: String, to: TaskList): String = synchronized(store) {
        val obj = store.objectByHref(href) ?: throw IllegalStateException("No such item on the phone")
        require(obj.listId != to.id) { "The item is already in that list" }
        if (store.outboxAll().any { it.href == href && it.state == OutboxState.Conflict }) throw IllegalStateException("Resolve the conflict first")
        val newHref = to.href.trimEnd('/') + "/" + href.substringAfterLast('/')
        if (store.objectByHref(newHref) != null) throw IllegalStateException("The other list already has an item with that name")
        val old = store.outboxAll().firstOrNull { it.href == href }
        createAt(to, newHref, obj.ics)
        when {
            old == null -> store.outboxInsert(OutboxItem(0, OutboxKind.Delete, obj.listId, href, obj.etag, obj.ics, now(), after = newHref))
            old.kind == OutboxKind.Create -> store.outboxRemove(old.id)               // never sent: the new create is all that is needed
            old.kind == OutboxKind.Edit -> {
                // a queued edit sits before the new create in the queue: replace it by a delete queued after the create, so the create is always sent first
                store.outboxRemove(old.id)
                store.outboxInsert(OutboxItem(0, OutboxKind.Delete, obj.listId, href, old.baseEtag, obj.ics, now(), after = newHref))
            }
            else -> throw IllegalStateException("The item is being deleted")
        }
        store.deleteObjects(obj.listId, listOf(href))
        newHref
    }

    /**
     * Manual order: puts [href] after [after] and before [before] (the rows around the drop as shown; see [TaskOrder.indexBetween]) among
     * the tasks at its level in list [listId], and queues one edit per task whose order changes (PUT with If-Match, like any edit).
     * Planned and written under the store lock (the push takes it too), so nothing changes between the check and the writes.
     * All or nothing: if any planned task has a change in conflict, a change the server refused, or a delete waiting, or its text is
     * missing, nothing is written and [IllegalStateException] is thrown (a refused change is never put back in the queue this way).
     * Returns how many tasks were written (0 = nothing to do, or the task or both neighbours are gone).
     */
    fun reorder(listId: Long, href: String, after: String?, before: String?, zone: java.time.ZoneId, now: java.time.Instant): Int = synchronized(store) {
        val objects = store.allObjects().filter { it.listId == listId }
        val group = TaskOrder.groupOf(TaskViews.listTree(TaskParser.parseAll(objects, zone), listId), href) ?: return@synchronized 0
        val to = TaskOrder.indexBetween(group, href, after, before) ?: return@synchronized 0
        val plan = TaskOrder.plan(group, href, to)
        if (plan.isEmpty()) return@synchronized 0
        val rows = store.outboxAll()
        val blocked = rows.filter { it.state != OutboxState.Pending || it.kind == OutboxKind.Delete }.map { it.href }.toSet()
        if (plan.keys.any { it in blocked }) throw IllegalStateException("A task in this move has a change waiting for the owner")
        val texts = plan.keys.associateWith { h -> store.objectByHref(h)?.ics ?: throw IllegalStateException("A task in this move is missing") }
        for ((h, value) in plan) edit(h, IcsPatcher.touch(TaskEdit.setSortOrder(texts.getValue(h), value), now))
        plan.size
    }

    /** Puts a refused (Failed) change back in the queue to be sent again. */
    fun retry(id: Long): Unit = synchronized(store) {
        val row = store.outboxAll().firstOrNull { it.id == id && it.state == OutboxState.Failed } ?: throw IllegalStateException("No such failed change")
        store.outboxUpdate(row.copy(state = OutboxState.Pending, attempts = 0, lastCode = 0))
    }

    private fun conflictRow(id: Long): OutboxItem =
        store.outboxAll().firstOrNull { it.id == id && it.state == OutboxState.Conflict } ?: throw IllegalStateException("No such conflict")

    /** Insist on my change: it is sent again against the server's current copy. */
    fun keepMine(id: Long): Unit = synchronized(store) {
        val row = conflictRow(id)
        val gone = row.serverIcs == null
        when {
            row.kind == OutboxKind.Delete && gone -> store.outboxRemove(id)                  // already gone: nothing left to delete
            row.kind == OutboxKind.Delete -> store.outboxUpdate(row.copy(baseEtag = row.serverEtag, state = OutboxState.Pending, attempts = 0, lastCode = 0, serverEtag = null, serverIcs = null))
            gone -> store.outboxUpdate(row.copy(kind = OutboxKind.Create, baseEtag = null, state = OutboxState.Pending, attempts = 0, lastCode = 0, serverEtag = null, serverIcs = null))
            else -> store.outboxUpdate(row.copy(kind = OutboxKind.Edit, baseEtag = row.serverEtag, state = OutboxState.Pending, attempts = 0, lastCode = 0, serverEtag = null, serverIcs = null))
        }
    }

    /** Give up my change: the server's copy becomes the local one (or the local item goes if the server no longer has it). */
    /** The delete half of a move whose create is [create], if it is still queued. */
    private fun pairedDelete(create: OutboxItem): OutboxItem? =
        if (create.kind == OutboxKind.Create) store.outboxAll().firstOrNull { it.kind == OutboxKind.Delete && it.after == create.href } else null

    /** Gives up a move whose create did not work: both rows go, the new copy goes, and the original comes back exactly as it was. */
    private fun cancelMove(create: OutboxItem, delete: OutboxItem) {
        store.outboxRemove(create.id)
        store.outboxRemove(delete.id)
        store.deleteObjects(create.listId, listOf(create.href))
        delete.ics?.let { store.upsertObjects(delete.listId, listOf(RemoteObject(delete.href, delete.baseEtag ?: "", it))) }
    }

    fun keepServer(id: Long): Unit = synchronized(store) {
        val row = conflictRow(id)
        pairedDelete(row)?.let { cancelMove(row, it); store.setSyncToken(row.listId, null); return@synchronized }
        if (row.serverIcs != null) store.upsertObjects(row.listId, listOf(RemoteObject(row.href, row.serverEtag ?: "", row.serverIcs)))
        else store.deleteObjects(row.listId, listOf(row.href))
        store.setSyncToken(row.listId, null)             // the server may have changed the item again while it sat in conflict: the next pull is a full fetch
        store.outboxRemove(id)
    }

    /** Keep both: the server's copy comes back and my version is created as a new item. Returns the new item's address. */
    fun keepBoth(id: Long, newUid: String): String = synchronized(store) {
        val row = conflictRow(id)
        if (row.kind == OutboxKind.Delete) { keepServer(id); return@synchronized row.href }
        if (row.serverIcs == null) { keepMine(id); return@synchronized row.href }
        val list = store.lists().first { it.id == row.listId }
        val mine = IcsPatcher.replaceUid(row.ics!!, newUid)
        keepServer(id)
        create(list, newUid, mine)
    }

    /** For a refused (Failed) row: undo the local change and forget the row. A restored edit or delete comes back with the next pull, which is made a full fetch. */
    fun discard(id: Long): Unit = synchronized(store) {
        val row = store.outboxAll().firstOrNull { it.id == id && it.state == OutboxState.Failed } ?: throw IllegalStateException("No such failed change")
        pairedDelete(row)?.let { cancelMove(row, it); return@synchronized }          // a refused move: the original is put back, nothing is lost
        if (row.kind == OutboxKind.Create) store.deleteObjects(row.listId, listOf(row.href)) else store.setSyncToken(row.listId, null)
        store.outboxRemove(id)
    }

    fun count(): Int = store.outboxAll().size
    fun pendingHrefs(): Set<String> = store.outboxAll().map { it.href }.toSet()
}

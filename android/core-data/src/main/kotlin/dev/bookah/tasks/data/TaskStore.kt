package dev.bookah.tasks.data

/**
 * Everything the app keeps on the phone about lists and their task objects. The real one is SQLite ([SqliteTaskStore]);
 * tests use [MemoryTaskStore].
 */
interface TaskStore {
    /** Adds new lists, updates name/colour/rights/order of known ones (keeping their show/hide choice), removes the ones the server no longer lists (with their objects). */
    fun replaceLists(remote: List<RemoteList>): List<TaskList>
    fun lists(): List<TaskList>
    fun setVisible(listId: Long, visible: Boolean)
    fun setSyncToken(listId: Long, token: String?)

    fun objects(listId: Long): List<StoredObject>
    fun allObjects(): List<StoredObject>
    fun objectByHref(href: String): StoredObject?
    fun upsertObjects(listId: Long, objects: List<RemoteObject>)
    fun deleteObjects(listId: Long, hrefs: List<String>)
    fun clearObjects(listId: Long)
    fun objectCount(listId: Long): Int

    /** The queued changes in the order they were made (see [Outbox]). Every change to them bumps [version]. */
    fun outboxAll(): List<OutboxItem>
    fun outboxInsert(item: OutboxItem): Long
    fun outboxUpdate(item: OutboxItem)
    fun outboxRemove(id: Long)

    /** Increases whenever objects or lists change, so screens know when to read again. */
    fun version(): Long
    fun clearAll()

    // ---- events of event-only calendars (Phase 5 day strip): their own table, read only, never queued ----
    fun setShowEvents(listId: Long, show: Boolean)
    fun eventObjects(listId: Long): List<StoredObject>
    /** The events that can touch any day from [fromDay] to [toDay] (epoch days), by the span kept with each one ([EventSpan]). */
    fun eventsTouching(fromDay: Long, toDay: Long): List<StoredObject>
    /** [spans] are the objects' day spans when the caller has worked them out already (outside any lock), else they are worked out here. */
    fun upsertEvents(listId: Long, objects: List<RemoteObject>, spans: List<Pair<Long, Long>>? = null)
    /** href -> ETag of the events kept for [listId] (no event text is read). */
    fun eventEtags(listId: Long): Map<String, String>
    fun deleteEvents(listId: Long, hrefs: List<String>)
    fun clearEvents(listId: Long)
    /** Increases whenever events or a calendar's show switch change. */
    fun eventsVersion(): Long
}

/** A plain in-memory store for unit tests. */
class MemoryTaskStore : TaskStore {
    private var nextId = 1L
    private var ver = 0L
    private val lists = LinkedHashMap<Long, TaskList>()
    private val objs = LinkedHashMap<String, StoredObject>()
    private var nextOutboxId = 1L
    private val outbox = LinkedHashMap<Long, OutboxItem>()

    override fun replaceLists(remote: List<RemoteList>): List<TaskList> {
        val byHref = lists.values.associateBy { it.href }
        val keep = HashSet<String>()
        for (r in remote) {
            keep.add(r.href)
            val old = byHref[r.href]
            if (old == null) {
                val id = nextId++
                lists[id] = TaskList(id, r.href, r.name, r.colorHex ?: DEFAULT_COLOR, r.kind, r.canWrite, visible = true, syncToken = null, order = r.order)
            } else {
                // a calendar that starts or stops holding tasks: its rows move to the other table, so the old ones go and a full fetch follows
                val kindMoved = old.kind != r.kind && (old.kind == ListKind.Events || r.kind == ListKind.Events)
                if (kindMoved) { objs.values.removeAll { it.listId == old.id }; events.values.removeAll { it.first.listId == old.id }; evVer++ }
                lists[old.id] = old.copy(name = r.name, colorHex = r.colorHex ?: old.colorHex, kind = r.kind, editable = r.canWrite, order = r.order,
                    syncToken = if (kindMoved) null else old.syncToken)
            }
        }
        for (old in byHref.values) if (old.href !in keep) {
            lists.remove(old.id); objs.values.removeAll { it.listId == old.id }; outbox.values.removeAll { it.listId == old.id }
            events.values.removeAll { it.first.listId == old.id }; evVer++
        }
        ver++
        return lists()
    }

    private val events = LinkedHashMap<String, Pair<StoredObject, Pair<Long, Long>>>()
    private var evVer = 0L
    override fun setShowEvents(listId: Long, show: Boolean) { lists[listId]?.let { lists[listId] = it.copy(showEvents = show) }; evVer++ }
    override fun eventObjects(listId: Long) = events.values.map { it.first }.filter { it.listId == listId }
    override fun eventsTouching(fromDay: Long, toDay: Long) = events.values.filter { (_, s) -> s.second >= fromDay && s.first <= toDay }.map { it.first }
    override fun upsertEvents(listId: Long, objects: List<RemoteObject>, spans: List<Pair<Long, Long>>?) {
        objects.forEachIndexed { i, o -> events[o.href] = StoredObject(o.href, listId, o.etag, o.ics) to (spans?.getOrNull(i) ?: EventSpan.of(o.ics)) }
        evVer++
    }
    override fun eventEtags(listId: Long) = events.values.filter { it.first.listId == listId }.associate { it.first.href to it.first.etag }
    override fun deleteEvents(listId: Long, hrefs: List<String>) { for (h in hrefs) if (events[h]?.first?.listId == listId) events.remove(h); evVer++ }
    override fun clearEvents(listId: Long) { events.values.removeAll { it.first.listId == listId }; evVer++ }
    override fun eventsVersion() = evVer

    override fun lists() = lists.values.sortedWith(compareBy({ it.order }, { it.id }))
    override fun setVisible(listId: Long, visible: Boolean) { lists[listId]?.let { lists[listId] = it.copy(visible = visible) }; ver++ }
    override fun setSyncToken(listId: Long, token: String?) { lists[listId]?.let { lists[listId] = it.copy(syncToken = token) } }
    override fun objects(listId: Long) = objs.values.filter { it.listId == listId }
    override fun allObjects() = objs.values.toList()
    override fun objectByHref(href: String) = objs[href]
    override fun upsertObjects(listId: Long, objects: List<RemoteObject>) {
        for (o in objects) objs[o.href] = StoredObject(o.href, listId, o.etag, o.ics)
        ver++
    }
    override fun deleteObjects(listId: Long, hrefs: List<String>) { for (h in hrefs) objs.remove(h); ver++ }
    override fun clearObjects(listId: Long) { objs.values.removeAll { it.listId == listId }; ver++ }
    override fun objectCount(listId: Long) = objs.values.count { it.listId == listId }
    override fun outboxAll() = outbox.values.toList()
    override fun outboxInsert(item: OutboxItem): Long { val id = nextOutboxId++; outbox[id] = item.copy(id = id); ver++; return id }
    override fun outboxUpdate(item: OutboxItem) { if (item.id in outbox) { outbox[item.id] = item; ver++ } }
    override fun outboxRemove(id: Long) { outbox.remove(id); ver++ }
    override fun version() = ver
    override fun clearAll() { lists.clear(); objs.clear(); outbox.clear(); events.clear(); ver++; evVer++ }
}

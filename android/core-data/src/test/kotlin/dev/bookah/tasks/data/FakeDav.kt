package dev.bookah.tasks.data

import java.io.IOException

/** A made-up Nextcloud for the write tests: ETags count up, preconditions are checked like the real server, and switches break things. */
internal class FakeDav : CalDavApi {
    val list = RemoteList("/cal/tasks/", "Tasks", "#A63F2D", true, ListKind.Tasks, 0)
    val objects = LinkedHashMap<String, Pair<String, String>>()      // href -> (etag, ics)
    private var counter = 0
    var offline = false
    var rejectWith: Int? = null
    /** Only writes (PUT) are refused with this code; deletes still work. */
    var rejectPutWith: Int? = null
    /** A second list at /cal/home/, for moves. */
    var twoLists = false
    val list2 = RemoteList("/cal/home/", "Home", "#245A86", true, ListKind.Tasks, 1)
    var duringDelete: (() -> Unit)? = null
    var authFails = false
    var noEtagOnPut = false
    var duringPut: (() -> Unit)? = null
    /** The write reaches the server but the answer is lost (the connection drops): the next call works again. */
    var dropAnswerOnce = false
    /** href -> status code: a GET of that item fails with that code. */
    val getFails = HashMap<String, Int>()
    val log = ArrayList<String>()

    /** Event calendars (Phase 5 day strip); only read, never written. */
    var eventLists: List<RemoteList> = emptyList()
    /** list href -> status code: a sync-collection of that list fails with that code (0 = the network drops). */
    val syncFails = HashMap<String, Int>()

    /** list href -> anything a broken answer or a broken phone could throw during the sync of that list. */
    val syncThrows = HashMap<String, Throwable>()
    /** Multiget calls answered so far, hrefs asked for in total; after [multigetFailsAfter] calls the network drops. */
    var multigetCalls = 0
    var multigetHrefs = 0
    var multigetFailsAfter: Int? = null

    private fun gate() { if (offline) throw IOException("offline"); if (authFails) throw CalDavException(401, "wrong password") }
    fun seed(href: String, ics: String): String { val e = "e${++counter}"; objects[href] = e to ics; return e }

    override suspend fun listLists(): List<RemoteList> { gate(); return (if (twoLists) listOf(list, list2) else listOf(list)) + eventLists }
    override suspend fun syncCollection(listHref: String, token: String?): SyncDelta {
        gate()
        log += "SYNC $listHref"
        syncFails[listHref]?.let { if (it == 0) throw IOException("dropped") else throw CalDavException(it, "refused") }
        syncThrows[listHref]?.let { throw it }
        return SyncDelta("t$counter", objects.filter { it.key.startsWith(listHref) }.map { ChangedEntry(it.key, it.value.first) }, emptyList())
    }
    override suspend fun multiget(listHref: String, hrefs: List<String>): List<RemoteObject> {
        gate()
        multigetFailsAfter?.let { if (multigetCalls >= it) throw IOException("dropped during the download") }
        multigetCalls++; multigetHrefs += hrefs.size
        return hrefs.mapNotNull { h -> objects[h]?.let { RemoteObject(h, it.first, it.second) } }
    }

    override suspend fun put(href: String, ics: String, ifMatch: String?, createOnly: Boolean): WriteResult {
        gate(); log += "PUT $href"
        duringPut?.invoke()
        rejectWith?.let { return WriteResult.Rejected(it) }
        rejectPutWith?.let { return WriteResult.Rejected(it) }
        val cur = objects[href]
        if (createOnly && cur != null) return WriteResult.PreconditionFailed
        if (ifMatch != null && cur?.first != ifMatch) return WriteResult.PreconditionFailed
        val e = "e${++counter}"; objects[href] = e to ics
        if (dropAnswerOnce) { dropAnswerOnce = false; throw IOException("answer lost") }
        return WriteResult.Ok(if (noEtagOnPut) null else e)
    }

    override suspend fun delete(href: String, ifMatch: String?): WriteResult {
        gate(); log += "DELETE $href"
        duringDelete?.invoke()
        rejectWith?.let { return WriteResult.Rejected(it) }
        val cur = objects[href] ?: return WriteResult.Ok(null)
        if (ifMatch != null && cur.first != ifMatch) return WriteResult.PreconditionFailed
        objects.remove(href); return WriteResult.Ok(null)
    }

    override suspend fun get(href: String): RemoteObject? { gate(); getFails[href]?.let { throw CalDavException(it, "get failed") }; return objects[href]?.let { RemoteObject(href, it.first, it.second) } }
}

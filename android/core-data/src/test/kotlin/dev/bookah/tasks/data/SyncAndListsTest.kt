package dev.bookah.tasks.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val HOME = "/remote.php/dav/calendars/Demo/"

/** A made-up server: lists with objects, a sync counter, and switches to break things. */
private class FakeServer : CalDavApi {
    var lists = listOf(
        RemoteList("${HOME}personal/", "Personal", "#00679E", true, ListKind.Events, 0),
        RemoteList("${HOME}tasks/", "Tasks", "#A63F2D", true, ListKind.Tasks, 1),
        RemoteList("${HOME}both/", "Both", null, true, ListKind.Both, 2),
    )
    val data = HashMap<String, MutableMap<String, String>>()
    var version = 1
    var failWith: Exception? = null
    var rejectTokens = false
    val asked = ArrayList<String>()

    override suspend fun listLists(): List<RemoteList> { failWith?.let { throw it }; return lists }

    override suspend fun syncCollection(listHref: String, token: String?): SyncDelta {
        failWith?.let { throw it }
        asked.add(listHref)
        if (token != null && rejectTokens) throw SyncTokenInvalidException()
        val objs = data[listHref].orEmpty()
        val since = token?.substringAfterLast('/')?.toInt() ?: 0
        val changed = objs.filter { (_, ics) -> !ics.startsWith("REMOVED") && versionOf(ics) > since }.map { (h, ics) -> ChangedEntry(h, "etag-${versionOf(ics)}") }
        val removed = objs.filter { (_, ics) -> ics.startsWith("REMOVED") && versionOf(ics) > since }.keys.toList()
        return SyncDelta("http://sabre.io/ns/sync/$version", changed, removed)
    }

    override suspend fun multiget(listHref: String, hrefs: List<String>): List<RemoteObject> {
        failWith?.let { throw it }
        return hrefs.map { RemoteObject(it, "etag-${versionOf(data[listHref]!![it]!!)}", data[listHref]!![it]!!) }
    }

    // these sync tests never push, so the write calls are not needed here (PushTest has the full fake)
    override suspend fun put(href: String, ics: String, ifMatch: String?, createOnly: Boolean): WriteResult = throw UnsupportedOperationException()
    override suspend fun delete(href: String, ifMatch: String?): WriteResult = throw UnsupportedOperationException()
    override suspend fun get(href: String): RemoteObject? = throw UnsupportedOperationException()

    private fun versionOf(ics: String) = ics.substringAfter("v=").substringBefore(";").toInt()
    fun put(list: String, name: String, title: String) { version++; data.getOrPut("$HOME$list/") { HashMap() }["$HOME$list/$name.ics"] = "SUMMARY:$title;v=$version" }
    fun remove(list: String, name: String) { version++; data["$HOME$list/"]!!["$HOME$list/$name.ics"] = "REMOVED;v=$version" }
}

class SyncAndListsTest {
    private fun run(block: suspend () -> SyncReport) = runBlocking { block() }

    @Test fun onlyListsThatHoldTasksAreFetched() {
        val server = FakeServer()
        server.put("personal", "e1", "An event"); server.put("tasks", "t1", "A task"); server.put("both", "b1", "Mixed")
        val store = MemoryTaskStore()
        val report = run { SyncEngine(store).sync(server) }
        assertEquals(2, report.lists)
        // Phase 5: the event calendar is fetched too, after the task lists, into its own table (the day strip); never among the tasks
        assertEquals(listOf("${HOME}tasks/", "${HOME}both/", "${HOME}personal/"), server.asked)
        assertEquals(3, store.lists().size)
        assertEquals(0, store.objectCount(store.lists().first { it.name == "Personal" }.id))
        assertEquals(1, store.eventObjects(store.lists().first { it.name == "Personal" }.id).size)
        assertEquals(1, store.objectCount(store.lists().first { it.name == "Tasks" }.id))
    }

    @Test fun secondSyncFetchesOnlyChangesAndHonoursRemovals() {
        val server = FakeServer()
        for (i in 1..5) server.put("tasks", "t$i", "Task $i")
        val store = MemoryTaskStore(); val engine = SyncEngine(store, chunk = 2)
        val first = run { engine.sync(server) }
        assertEquals(5, first.objectsChanged)
        server.put("tasks", "t3", "Task 3 changed"); server.put("tasks", "t9", "New"); server.remove("tasks", "t1")
        val second = run { engine.sync(server) }
        assertEquals(2, second.objectsChanged); assertEquals(1, second.objectsRemoved)
        val tasks = store.lists().first { it.name == "Tasks" }
        assertEquals(listOf("New", "Task 2", "Task 3 changed", "Task 4", "Task 5"), store.objects(tasks.id).map { it.ics.substringAfter("SUMMARY:").substringBefore(";") }.sorted())
    }

    @Test fun refusedTokenGivesAFullResync() {
        val server = FakeServer()
        for (i in 1..3) server.put("tasks", "t$i", "Task $i")
        val store = MemoryTaskStore(); val engine = SyncEngine(store)
        run { engine.sync(server) }
        server.data["${HOME}tasks/"]!!.remove("${HOME}tasks/t2.ics")
        server.rejectTokens = true
        val report = run { engine.sync(server) }
        assertTrue(report.fullResyncs >= 1)
        assertEquals(2, store.objectCount(store.lists().first { it.name == "Tasks" }.id))
    }

    @Test fun offlineKeepsTheCacheAndWrongPasswordIsReported() {
        val server = FakeServer(); server.put("tasks", "t1", "Task 1")
        val store = MemoryTaskStore(); val engine = SyncEngine(store)
        run { engine.sync(server) }
        server.failWith = IOException("timeout")
        val off = run { engine.sync(server) }
        assertTrue(off.offline); assertEquals(1, store.objectCount(store.lists().first { it.name == "Tasks" }.id))
        server.failWith = CalDavException(401, "The server did not accept the user name or app password.")
        val bad = run { engine.sync(server) }
        assertFalse(bad.offline); assertEquals(401, bad.errorCode)
    }

    @Test fun listsRemovedOnTheServerDisappearWithTheirTasksAndOrderFollowsTheServer() {
        val server = FakeServer(); server.put("tasks", "t1", "Task 1")
        val store = MemoryTaskStore(); val engine = SyncEngine(store)
        run { engine.sync(server) }
        assertEquals(listOf("Personal", "Tasks", "Both"), store.lists().map { it.name })
        store.setVisible(store.lists().first { it.name == "Tasks" }.id, false)
        server.lists = server.lists.map { if (it.name == "Both") it.copy(order = -1) else it }.filter { it.name != "Personal" }
        run { engine.sync(server) }
        assertEquals(listOf("Both", "Tasks"), store.lists().map { it.name })
        assertFalse(store.lists().first { it.name == "Tasks" }.visible)                 // the show/hide choice survives
    }
}

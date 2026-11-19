package dev.bookah.tasks.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Manual order gate (Build D). Runs only through tools\live-order.ps1, which makes one throw-away list (bookahtest-order) first and removes
 * it for good afterwards. Records only counts and yes/no lines; never the server address, the password or a task's text.
 *
 * What it can check: that Nextcloud stores X-APPLE-SORT-ORDER as written, through the app's own write path (outbox, PUT with If-Match), and
 * that the order read back is the order planned. What it cannot check: how the Nextcloud Tasks web app shows that order (that needs a look
 * at the web page).
 */
class LiveOrderTest {
    private val zone = ZoneId.of("Europe/Athens")
    private fun env(n: String) = System.getenv(n)

    @Test fun manualOrderAgainstTheRealServer() = runBlocking {
        assumeTrue(env("BOOKAH_LIVE_SERVER") != null)
        val slug = env("BOOKAH_LIVE_SLUG")!!
        require(slug.startsWith("bookahtest-")) { "refusing: not a throw-away list" }
        val out = StringBuilder()
        fun note(s: String) { out.appendLine(s) }
        try {
            val api = CalDavClient(Http.newClient(), Session(env("BOOKAH_LIVE_SERVER")!!, env("BOOKAH_LIVE_USER")!!, env("BOOKAH_LIVE_PASSWORD")!!))
            val store = MemoryTaskStore(); val engine = SyncEngine(store, events = false); val outbox = Outbox(store)          // tasks only (Phase 5 added event calendars to the sync)
            engine.sync(api)
            val list = store.lists().first { it.name == slug }
            suspend fun fresh(): Pair<MemoryTaskStore, List<Task>> {
                val f = MemoryTaskStore(); SyncEngine(f, events = false).sync(api)
                val id = f.lists().first { it.name == slug }.id
                return f to TaskParser.parseAll(f.allObjects().filter { it.listId == id }, zone)
            }
            suspend fun shown(): List<String> { val (f, tasks) = fresh(); return TaskViews.listTree(tasks, f.lists().first { it.name == slug }.id).map { it.task.uid } }
            suspend fun syncOk(label: String, expectPushed: Int) {
                val r = engine.sync(api)
                note("$label pushed=${r.pushed} conflicts=${r.conflicts} failed=${r.pushFailed} errorCode=${r.errorCode} queued=${outbox.count()}")
                assertEquals(label, expectPushed, r.pushed); assertEquals(label, 0, r.conflicts + r.pushFailed + r.errorCode)
            }
            /** Plans a move on the phone's copy and writes it the way the app does (patch, touch, outbox edit = PUT with If-Match). */
            fun move(uid: String, to: Int): Map<String, Long> {
                val tasks = TaskParser.parseAll(store.allObjects().filter { it.listId == list.id }, zone)
                val href = tasks.first { it.uid == uid }.href
                val group = TaskOrder.groupOf(TaskViews.listTree(tasks, list.id), href)!!
                val plan = TaskOrder.plan(group, href, to)
                for ((h, v) in plan) outbox.edit(h, IcsPatcher.touch(TaskEdit.setSortOrder(store.objectByHref(h)!!.ics, v), Instant.now()))
                return plan
            }
            suspend fun storedOrders(): Map<String, String?> { val (f, tasks) = fresh(); return tasks.associate { it.uid to IcsPatcher.readProperty(f.objectByHref(it.href)!!.ics, "X-APPLE-SORT-ORDER") } }

            // 1 three tasks made as quick add makes them: no order of their own, so they sort by creation time (ties by title)
            val ids = listOf("A", "B", "C").associateWith { UUID.randomUUID().toString() }
            for ((name, uid) in ids) outbox.create(list, uid, IcsPatcher.newTask(uid, "Live order $name", Instant.now()))
            syncOk("create three", 3)
            val a = ids.getValue("A"); val b = ids.getValue("B"); val c = ids.getValue("C")
            run { val s = shown(); note("start order ABC=${s == listOf(a, b, c)} noOrderLines=${storedOrders().values.all { it == null }}"); assertEquals(listOf(a, b, c), s) }

            // 2 C to the top: one PUT, the value one gap above A's creation-time order
            val p1 = move(c, 0)
            note("move C to top writes=${p1.size}"); assertEquals(1, p1.size)
            syncOk("move C to top", 1)
            run {
                val s = shown(); val stored = storedOrders()
                note("order CAB=${s == listOf(c, a, b)} storedAsWritten=${stored[c] == p1.values.single().toString()} othersUntouched=${stored[a] == null && stored[b] == null}")
                assertEquals(listOf(c, a, b), s); assertEquals(p1.values.single().toString(), stored[c]); assertTrue(stored[a] == null && stored[b] == null)
            }

            // 3 B between C and A: one PUT halfway
            val p2 = move(b, 1)
            note("move B to middle writes=${p2.size}"); assertEquals(1, p2.size)
            syncOk("move B to middle", 1)
            run { val s = shown(); note("order CBA=${s == listOf(c, b, a)} storedAsWritten=${storedOrders()[b] == p2.values.single().toString()}"); assertEquals(listOf(c, b, a), s) }

            // 4 set orders 1, 2, 3 (no room between them), then move A between C and B: the run around it is renumbered
            for ((uid, v) in listOf(c to 1L, b to 2L, a to 3L)) {
                val h = store.allObjects().first { TaskParser.parse(it, zone).any { t -> t.uid == uid } }.href
                outbox.edit(h, IcsPatcher.touch(TaskEdit.setSortOrder(store.objectByHref(h)!!.ics, v), Instant.now()))
            }
            syncOk("set orders 1 2 3", 3)
            run { val st = storedOrders(); note("orders 1 2 3 stored=${st[c] == "1" && st[b] == "2" && st[a] == "3"}"); assertTrue(st[c] == "1" && st[b] == "2" && st[a] == "3") }
            val p3 = move(a, 1)
            note("move A between C and B writes=${p3.size} (renumber)"); assertTrue(p3.size >= 2)
            syncOk("renumber", p3.size)
            run {
                val s = shown(); val st = storedOrders()
                val byUid = p3.mapKeys { (h, _) -> store.objectByHref(h)?.let { o -> TaskParser.parse(o, zone).first().uid } }
                note("order CAB=${s == listOf(c, a, b)} allStoredAsWritten=${byUid.all { (u, v) -> st[u] == v.toString() }} oneOrderLineEach=${fresh().first.let { f -> f.allObjects().filter { o -> o.listId == f.lists().first { it.name == slug }.id } }.all { Regex("(?m)^X-APPLE-SORT-ORDER:").findAll(it.ics).count() == 1 }}")
                assertEquals(listOf(c, a, b), s); assertTrue(byUid.all { (u, v) -> st[u] == v.toString() })
            }
            run { val (_, tasks) = fresh(); note("titles kept=${tasks.map { it.title }.toSet() == setOf("Live order A", "Live order B", "Live order C")}") }

            // 5 clean up
            for (o in store.allObjects().filter { it.listId == list.id }) outbox.delete(o.href)
            engine.sync(api)
            run { val (_, tasks) = fresh(); note("cleanup remaining=${tasks.size}"); assertEquals(0, tasks.size) }
            note("NOT CHECKED HERE: how the Nextcloud Tasks web app orders these (needs a look at the web page); only the stored property is verified")
            assertNotNull(note("RESULT: all steps passed"))
        } catch (t: Throwable) {
            note("RESULT: FAILED (${t::class.simpleName}: ${t.message?.take(120)})")
            throw t
        } finally {
            File(env("BOOKAH_PRIVATE_DIR") ?: ".", "live-order.txt").writeText(out.toString())
        }
    }
}

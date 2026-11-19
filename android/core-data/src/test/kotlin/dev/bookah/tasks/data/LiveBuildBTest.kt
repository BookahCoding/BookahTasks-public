package dev.bookah.tasks.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Build B gate. Runs only through tools\live-buildb.ps1, which makes two throw-away lists (bookahtest-b1, bookahtest-b2) first and removes
 * them for good afterwards. Records only counts and yes/no lines. Never prints the server address or the password.
 */
class LiveBuildBTest {
    private val zone = ZoneId.of("Europe/Athens")
    private fun env(n: String) = System.getenv(n)

    @Test fun editorActionsAgainstTheRealServer() = runBlocking {
        assumeTrue(env("BOOKAH_LIVE_SERVER") != null)
        val s1 = env("BOOKAH_LIVE_SLUG")!!; val s2 = env("BOOKAH_LIVE_SLUG2")!!
        require(s1.startsWith("bookahtest-") && s2.startsWith("bookahtest-")) { "refusing: not throw-away lists" }
        val out = StringBuilder()
        fun note(s: String) { out.appendLine(s) }
        try {
            val api = CalDavClient(Http.newClient(), Session(env("BOOKAH_LIVE_SERVER")!!, env("BOOKAH_LIVE_USER")!!, env("BOOKAH_LIVE_PASSWORD")!!))
            val store = MemoryTaskStore(); val engine = SyncEngine(store, events = false); val outbox = Outbox(store)          // tasks only (Phase 5 added event calendars to the sync)
            engine.sync(api)
            val l1 = store.lists().first { it.name == s1 }; val l2 = store.lists().first { it.name == s2 }
            suspend fun fresh(): Pair<MemoryTaskStore, List<Task>> {
                val f = MemoryTaskStore(); SyncEngine(f, events = false).sync(api)
                val ids = f.lists().filter { it.name == s1 || it.name == s2 }.map { it.id }.toSet()
                return f to TaskParser.parseAll(f.allObjects().filter { it.listId in ids }, zone)
            }
            suspend fun syncOk(label: String, expectPushed: Int) {
                val r = engine.sync(api)
                note("$label pushed=${r.pushed} conflicts=${r.conflicts} failed=${r.pushFailed} errorCode=${r.errorCode} queued=${outbox.count()} lastCodes=${store.outboxAll().map { it.lastCode }}")
                assertEquals(label, expectPushed, r.pushed); assertEquals(label, 0, r.conflicts + r.pushFailed + r.errorCode)
            }

            // 1 a repeating parent with a reminder, a date-only start, a timed deadline, tags (one hidden) and a sort order, made the way another app would
            val pUid = UUID.randomUUID().toString()
            val start = LocalDate.now().plusDays(1)
            val ymd = start.toString().replace("-", "")
            val parentText = listOf(
                "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//BookahTasks live//EN", "BEGIN:VTODO", "UID:$pUid",
                "DTSTAMP:${IcsPatcher.stamp(Instant.now())}", "CREATED:${IcsPatcher.stamp(Instant.now())}", "SUMMARY:Live B parent",
                "DTSTART:${ymd}T080000Z", "DUE:${ymd}T100000Z", "RRULE:FREQ=WEEKLY", "CATEGORIES:home,someday", "X-APPLE-SORT-ORDER:10", "STATUS:NEEDS-ACTION",
                "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "TRIGGER:-PT30M", "END:VALARM", "END:VTODO", "END:VCALENDAR",
            ).joinToString("\r\n") + "\r\n"
            val pHref = outbox.create(l1, pUid, parentText)
            syncOk("create parent", 1)

            // 2 tick the repeating task: it advances a week and stays open; RRULE, VALARM, sort order and the hidden tag survive
            val adv = TaskEdit.complete(store.objectByHref(pHref)!!.ics, Instant.now(), zone)
            assertTrue(adv is Completion.Advanced)
            outbox.edit(pHref, adv.ics); syncOk("tick repeating", 1)
            run {
                val (f, tasks) = fresh(); val t = tasks.first { it.uid == pUid }; val raw = f.objectByHref(pHref)!!.ics
                note("advanced start=${t.start?.date == start.plusDays(7)} dueDate=${t.due?.date == start.plusDays(7)} open=${t.isOpen} rrule=${raw.contains("RRULE:FREQ=WEEKLY")} alarm=${raw.contains("BEGIN:VALARM")} sort=${raw.contains("X-APPLE-SORT-ORDER:10")} someday=${t.someday}")
                assertEquals(start.plusDays(7), t.start?.date); assertEquals(start.plusDays(7), t.due?.date); assertTrue(t.isOpen)
                assertTrue(raw.contains("RRULE:FREQ=WEEKLY") && raw.contains("BEGIN:VALARM") && raw.contains("X-APPLE-SORT-ORDER:10") && t.someday)
            }

            // 3 a title with Greek and a comma, notes, priority, tags (someday kept), a date-only start with a timed deadline
            var text = store.objectByHref(pHref)!!.ics
            text = TaskEdit.setTitle(text, "Live B γονέας, με κόμμα")
            text = TaskEdit.setNotes(text, "line one\nline two")
            text = TaskEdit.setPriority(text, Priority.High)
            text = TaskEdit.setTags(text, listOf("alpha", "beta"))
            val newStart = TaskWhen(start.plusDays(10), java.time.LocalTime.MIDNIGHT); val newDue = TaskWhen(start.plusDays(12), java.time.LocalTime.of(18, 30))
            text = TaskEdit.setDates(text, newStart, newDue, zone)
            outbox.edit(pHref, IcsPatcher.touch(text, Instant.now())); syncOk("edit fields", 1)
            run {
                val (f, tasks) = fresh(); val t = tasks.first { it.uid == pUid }; val raw = f.objectByHref(pHref)!!.ics
                note("fields title=${t.title == "Live B γονέας, με κόμμα"} notes=${t.notes == "line one\nline two"} prio=${t.priority == Priority.High} tags=${t.tags == listOf("alpha", "beta")} someday=${t.someday} start=${t.start == newStart} due=${t.due == newDue} rrule=${raw.contains("RRULE")} alarm=${raw.contains("BEGIN:VALARM")}")
                assertEquals("Live B γονέας, με κόμμα", t.title); assertEquals("line one\nline two", t.notes); assertEquals(Priority.High, t.priority)
                assertEquals(listOf("alpha", "beta"), t.tags); assertTrue(t.someday); assertEquals(newStart, t.start); assertEquals(newDue, t.due)
                assertTrue(raw.contains("RRULE") && raw.contains("BEGIN:VALARM"))
            }

            // 4 a subtask, ticked (finished), deleted, then the delete undone after it was already sent
            val cUid = UUID.randomUUID().toString()
            val cHref = outbox.create(l1, cUid, TaskEdit.newSubtask(pUid, cUid, "Live B child", Instant.now())); syncOk("create subtask", 1)
            run { val (_, tasks) = fresh(); note("subtask parent=${tasks.first { it.uid == cUid }.parentUid == pUid}"); assertEquals(pUid, tasks.first { it.uid == cUid }.parentUid) }
            val done = TaskEdit.complete(store.objectByHref(cHref)!!.ics, Instant.now(), zone)
            assertTrue(done is Completion.Done); outbox.edit(cHref, done.ics); syncOk("tick subtask", 1)
            run { val (_, tasks) = fresh(); note("subtask done=${tasks.first { it.uid == cUid }.status == TaskStatus.Completed}"); assertEquals(TaskStatus.Completed, tasks.first { it.uid == cUid }.status) }
            val cText = store.objectByHref(cHref)!!.ics
            outbox.delete(cHref); syncOk("delete subtask", 1)
            run { val (_, tasks) = fresh(); note("subtask gone=${tasks.none { it.uid == cUid }}"); assertTrue(tasks.none { it.uid == cUid }) }
            outbox.recreate(l1.id, cHref, cText); syncOk("undo delete after send", 1)
            run { val (_, tasks) = fresh(); note("subtask back=${tasks.any { it.uid == cUid }}"); assertTrue(tasks.any { it.uid == cUid }) }

            // 5 move the parent and its subtask to the other list (does Nextcloud accept a second copy of a UID in another calendar while the first still exists?)
            val pHref2 = outbox.move(pHref, l2); val cHref2 = outbox.move(cHref, l2)
            val r = engine.sync(api)
            note("move pushed=${r.pushed} conflicts=${r.conflicts} failed=${r.pushFailed} errorCode=${r.errorCode} queued=${outbox.count()}")
            run {
                val (f, tasks) = fresh()
                val inL2 = tasks.filter { it.listId == f.lists().first { l -> l.name == s2 }.id }.map { it.uid }.toSet()
                val inL1 = tasks.filter { it.listId == f.lists().first { l -> l.name == s1 }.id }.map { it.uid }.toSet()
                note("moved: inList2=${inL2 == setOf(pUid, cUid)} inList1Empty=${inL1.isEmpty()} parentLinkKept=${tasks.first { it.uid == cUid }.parentUid == pUid}")
                assertEquals(setOf(pUid, cUid), inL2); assertTrue(inL1.isEmpty()); assertEquals(pUid, tasks.first { it.uid == cUid }.parentUid)
            }
            assertEquals(0, outbox.count())

            // 6 move back (the undo of a move), then delete everything
            outbox.move(pHref2, l1); outbox.move(cHref2, l1); engine.sync(api)
            run { val (f, tasks) = fresh(); val back = tasks.filter { it.listId == f.lists().first { l -> l.name == s1 }.id }.size; note("moved back inList1=$back"); assertEquals(2, back) }
            outbox.delete(pHref); outbox.delete(cHref); engine.sync(api)
            run { val (_, tasks) = fresh(); note("cleanup remaining=${tasks.size}"); assertEquals(0, tasks.size) }
            assertNotNull(note("RESULT: all steps passed"))
        } catch (t: Throwable) {
            note("RESULT: FAILED (${t::class.simpleName}: ${t.message?.take(120)})")
            throw t
        } finally {
            File(env("BOOKAH_PRIVATE_DIR") ?: ".", "live-buildb.txt").writeText(out.toString())
        }
    }
}

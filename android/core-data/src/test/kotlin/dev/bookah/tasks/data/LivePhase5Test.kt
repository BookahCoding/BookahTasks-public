package dev.bookah.tasks.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Phase 5 gate. Runs only through tools\live-phase5.ps1. Records counts and yes/no lines only: never the server address, the password,
 * a task's or an event's text. Written in Phase 5, NOT run (it needs the owner's yes at that moment).
 *
 * (a) [writesOnAThrowAwayList]: on the throw-away list bookahtest-p5 (made and removed for good by the script): move-all of overdue
 *     tasks and its undo, Waiting for and ending it, a shared link in URL, read back from the server.
 * (b) [eventCalendarsAreOnlyRead]: read only against the real calendars: counts event calendars, events synced and today's instances,
 *     through an API whose writes throw (nothing can be written).
 */
class LivePhase5Test {
    private val zone = ZoneId.of("Europe/Athens")
    private fun env(n: String) = System.getenv(n)
    private fun api() = CalDavClient(Http.newClient(), Session(env("BOOKAH_LIVE_SERVER")!!, env("BOOKAH_LIVE_USER")!!, env("BOOKAH_LIVE_PASSWORD")!!))

    /** Reads pass through; any write throws, so this check cannot change anything. */
    private class ReadOnly(private val real: CalDavApi) : CalDavApi by real {
        override suspend fun put(href: String, ics: String, ifMatch: String?, createOnly: Boolean): WriteResult = throw IllegalStateException("read-only check: no writes")
        override suspend fun delete(href: String, ifMatch: String?): WriteResult = throw IllegalStateException("read-only check: no writes")
    }

    @Test fun writesOnAThrowAwayList() = runBlocking {
        assumeTrue(env("BOOKAH_LIVE_SERVER") != null && env("BOOKAH_LIVE_SLUG") != null)
        val slug = env("BOOKAH_LIVE_SLUG")!!
        require(slug.startsWith("bookahtest-")) { "refusing: not a throw-away list" }
        val out = StringBuilder()
        fun note(s: String) { out.appendLine(s) }
        try {
            val api = api()
            // tasks only: this part never downloads the real event calendars (part b does, read only)
            val store = MemoryTaskStore(); val engine = SyncEngine(store, events = false); val outbox = Outbox(store)
            engine.sync(api)
            val list = store.lists().first { it.name == slug }
            suspend fun syncOk(label: String, expectPushed: Int) {
                val r = engine.sync(api)
                note("$label pushed=${r.pushed} conflicts=${r.conflicts} failed=${r.pushFailed} errorCode=${r.errorCode} queued=${outbox.count()}")
                assertEquals(label, expectPushed, r.pushed); assertEquals(label, 0, r.conflicts + r.pushFailed + r.errorCode)
            }
            suspend fun server(uid: String): Task {
                val f = MemoryTaskStore(); SyncEngine(f, events = false).sync(api)
                val id = f.lists().first { it.name == slug }.id
                return f.allObjects().filter { it.listId == id }.flatMap { TaskParser.parse(it, zone) }.first { it.uid == uid && !it.isOccurrenceCopy }
            }
            suspend fun serverIcs(uid: String): String {
                val f = MemoryTaskStore(); SyncEngine(f, events = false).sync(api)
                val id = f.lists().first { it.name == slug }.id
                return f.allObjects().first { it.listId == id && TaskParser.parse(it, zone).any { t -> t.uid == uid } }.ics
            }
            val today = LocalDate.now(zone)
            fun make(title: String, start: TaskWhen?, due: TaskWhen?, repeat: Boolean = false): String {
                val uid = UUID.randomUUID().toString()
                var ics = TaskEdit.setDates(IcsPatcher.newTask(uid, title, Instant.now()), start, due, zone)
                if (repeat) ics = TaskEdit.setRepeat(ics, RepeatSpec(RepeatFreq.Weekly), zone, today)
                outbox.create(list, uid, ics)
                return uid
            }

            // 1 three overdue tasks (one repeating with a start and a deadline) moved to Tomorrow at once, then undone at once
            val a = make("Live p5 a", null, TaskWhen(today.minusDays(3), null))
            val b = make("Live p5 b", null, TaskWhen(today.minusDays(1), java.time.LocalTime.of(10, 0)))
            val c = make("Live p5 c", TaskWhen(today.minusDays(5), null), TaskWhen(today.minusDays(4), null), repeat = true)
            syncOk("create three overdue", 3)
            val shown = TaskViews.today(TaskParser.parseAll(store.allObjects(), zone), today).overdue.filter { it.uid in setOf(a, b, c) }
            val plan = OverdueTamer.plan(shown, { store.objectByHref(it)?.ics }, store.lists(), OverdueTamer.blocked(store.outboxAll()), RescheduleTarget.Tomorrow, today, 18, zone, Instant.now())
            val done = outbox.editEach(plan.writes)
            note("move all planned=${plan.writes.size} skipped=${plan.skipped} written=${done.written.size} failed=${done.failed.size}")
            syncOk("move all", 2)                                   // the repeating task is skipped (the owner's ruling, 18 Nov): two writes
            run {
                val ta = server(a); val tb = server(b); val tc = server(c)
                note("after move a=${ta.due!!.date == today.plusDays(1)} b=${tb.due!!.date == today.plusDays(1)} bTimeKept=${tb.due!!.time == java.time.LocalTime.of(10, 0)} repeatingStayed=${tc.due!!.date == today.minusDays(4)}")
                assertEquals(today.plusDays(1), ta.due!!.date); assertEquals(today.plusDays(1), tb.due!!.date)
                assertEquals(today.minusDays(4), tc.due!!.date); assertEquals(today.minusDays(5), tc.start!!.date)
                assertEquals(1, plan.repeating)
            }
            val back = done.written.associateWith { IcsPatcher.touch(plan.old.getValue(it), Instant.now()) }
            outbox.editEach(back)
            syncOk("undo move all", 2)
            run {
                val ok = server(a).due!!.date == today.minusDays(3) && server(c).start!!.date == today.minusDays(5)
                note("after undo dates restored=$ok"); assertTrue(ok)
            }

            // 2 Waiting for: tag, check-back date as the start, person line first in the notes; then ended
            val w = make("Live p5 waiting", null, null)
            syncOk("create waiting task", 1)
            fun edit(uid: String, f: (String) -> String) { val o = store.allObjects().first { TaskParser.parse(it, zone).any { t -> t.uid == uid } }; outbox.edit(o.href, IcsPatcher.touch(f(o.ics), Instant.now())) }
            edit(w) { TaskEdit.setNotes(it, "other line kept") }
            edit(w) { TaskEdit.setWaiting(it, "Live person", today.plusDays(3), zone) }
            syncOk("mark waiting", 1)
            run {
                val t = server(w)
                note("waiting tag=${t.waiting} startIsCheckBack=${t.start == TaskWhen(today.plusDays(3), null)} person=${t.waitingFor == "Live person"} otherNotesKept=${t.notes?.endsWith("other line kept")}")
                assertTrue(t.waiting); assertEquals("Live person", t.waitingFor)
            }
            edit(w) { TaskEdit.endWaiting(it) }
            syncOk("end waiting", 1)
            run { val t = server(w); note("ended tag=${t.waiting} person=${t.waitingFor} notes=${t.notes == "other line kept"}"); assertTrue(!t.waiting) }

            // 3 a shared link stored in URL and read back
            val s = UUID.randomUUID().toString()
            outbox.create(list, s, TaskEdit.setUrl(IcsPatcher.newTask(s, "Live p5 shared", Instant.now()), "https://example.com/p5?a=1&b=2"))
            syncOk("shared link", 1)
            run { val u = IcsPatcher.readProperty(serverIcs(s), "URL"); note("url stored as written=${u == "https://example.com/p5?a=1&b=2"}"); assertEquals("https://example.com/p5?a=1&b=2", u) }

            for (o in store.allObjects().filter { it.listId == list.id }) outbox.delete(o.href)
            engine.sync(api)
            note("NOT CHECKED HERE: what the Nextcloud Tasks web app shows for the waiting line and the URL")
            assertNotNull(note("RESULT: all steps passed"))
        } catch (t: Throwable) {
            note("RESULT: FAILED (${t::class.simpleName}: ${t.message?.take(120)})")
            throw t
        } finally {
            File(env("BOOKAH_PRIVATE_DIR") ?: ".", "live-phase5.txt").writeText(out.toString())
        }
    }

    @Test fun eventCalendarsAreOnlyRead() = runBlocking {
        assumeTrue(env("BOOKAH_LIVE_SERVER") != null && env("BOOKAH_LIVE_EVENTS") == "1")
        val out = StringBuilder()
        fun note(s: String) { out.appendLine(s) }
        try {
            val store = MemoryTaskStore()
            val started = System.currentTimeMillis()
            val r = SyncEngine(store).sync(ReadOnly(api()))
            note("sync errorCode=${r.errorCode} offline=${r.offline} eventLists=${r.eventLists} eventsFetched=${r.eventsChanged} eventErrors=${r.eventErrors} ms=${System.currentTimeMillis() - started}")
            val calendars = store.lists().filter { it.kind == ListKind.Events }
            note("event-only calendars=${calendars.size} both-kind lists=${store.lists().count { it.kind == ListKind.Both }} events stored=${calendars.sumOf { store.eventObjects(it.id).size }}")
            val today = LocalDate.now(zone)
            val d = today.toEpochDay()
            val near = store.eventsTouching(d - 1, d + 1)
            val t0 = System.currentTimeMillis()
            val inst = DayEvents.forDay(near, today, zone)
            note("candidates for today=${near.size} today's instances=${inst.size} allDay=${inst.count { it.wholeDay }} timed=${inst.count { !it.wholeDay }} expandMs=${System.currentTimeMillis() - t0}")
            for (k in 1..7) note("day +$k instances=${DayEvents.forDay(store.eventsTouching(d + k - 1, d + k + 1), today.plusDays(k.toLong()), zone).size}")
            note("outbox rows=${store.outboxAll().size} (expected 0)")
            assertEquals(0, store.outboxAll().size); assertEquals(0, r.errorCode)
            note("COMPARE BY HAND: the counts for today and the next days against what BookahCalendar or the Nextcloud Calendar app shows")
            assertNotNull(note("RESULT: read-only check finished"))
        } catch (t: Throwable) {
            note("RESULT: FAILED (${t::class.simpleName}: ${t.message?.take(120)})")
            throw t
        } finally {
            File(env("BOOKAH_PRIVATE_DIR") ?: ".", "live-phase5-events.txt").writeText(out.toString())
        }
    }
}

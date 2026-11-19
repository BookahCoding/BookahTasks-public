package dev.bookah.tasks.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val ZA: ZoneId = ZoneId.of("Europe/Athens")
private val D18: LocalDate = LocalDate.of(2026, 11, 18)
private val NOW18: Instant = Instant.parse("2026-11-18T10:00:00Z")

private fun ev(uid: String, body: String) =
    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20261101T000000Z\r\n${body}END:VEVENT\r\nEND:VCALENDAR\r\n"
private fun todo(uid: String, body: String = "") =
    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VTODO\r\nUID:$uid\r\nDTSTAMP:20260101T000000Z\r\nSUMMARY:$uid\r\n${body}END:VTODO\r\nEND:VCALENDAR\r\n"

private class P5Rig {
    val personal = RemoteList("/cal/personal/", "Personal", "#00679E", true, ListKind.Events, 5)
    val birthdays = RemoteList("/cal/birthdays/", "Birthdays", "#E9D859", false, ListKind.Events, 6)
    val dav = FakeDav().apply { eventLists = listOf(personal, birthdays); seed("/cal/tasks/t1.ics", todo("t1")) }
    val store = MemoryTaskStore()
    fun list(href: String) = store.lists().first { it.href == href }
}

class Phase5ReviewTest {
    // ---- C1: nothing thrown while reading one list may escape sync() ----
    @Test fun aRuntimeErrorInAnEventCalendarIsCountedNotThrown() = runBlocking {
        val r = P5Rig()
        r.dav.syncThrows["/cal/personal/"] = RuntimeException("odd answer")
        val rep = SyncEngine(r.store).sync(r.dav)
        assertNull(rep.error); assertEquals(1, rep.eventErrors); assertEquals(1, r.store.allObjects().size)
    }

    @Test fun aBrokenXmlAnswerInAnEventCalendarIsCountedNotThrown() = runBlocking {
        val r = P5Rig()
        r.dav.syncThrows["/cal/personal/"] = org.xml.sax.SAXParseException("bad xml", null)
        val rep = SyncEngine(r.store).sync(r.dav)
        assertNull(rep.error); assertEquals(1, rep.eventErrors)
    }

    @Test fun aBrokenAnswerForOneTaskListNeverEscapesAndTheOtherListsStillSync() = runBlocking {
        val r = P5Rig()
        r.dav.twoLists = true
        r.dav.seed("/cal/home/h1.ics", todo("h1"))
        r.dav.syncThrows["/cal/tasks/"] = org.xml.sax.SAXParseException("bad xml", null)
        val rep = SyncEngine(r.store).sync(r.dav)
        assertEquals(1, rep.listErrors)
        assertEquals(listOf("/cal/home/h1.ics"), r.store.allObjects().map { it.href })
        r.dav.syncThrows["/cal/home/"] = IllegalStateException("storage full")
        r.dav.syncThrows.remove("/cal/tasks/")
        val rep2 = SyncEngine(r.store).sync(r.dav)
        assertEquals(1, rep2.listErrors); assertTrue(r.store.allObjects().any { it.href == "/cal/tasks/t1.ics" })
    }

    @Test fun aDroppedConnectionOnOneCalendarDoesNotSkipTheOnesAfterIt() = runBlocking {
        val r = P5Rig()
        r.dav.seed("/cal/birthdays/b1.ics", ev("b1", "DTSTART;VALUE=DATE:20261118\r\n"))
        r.dav.syncFails["/cal/personal/"] = 0
        val rep = SyncEngine(r.store).sync(r.dav)
        assertEquals(1, rep.eventErrors)
        assertEquals(1, r.store.eventObjects(r.list("/cal/birthdays/").id).size)
    }

    // ---- I3 / I5: chunked, resumable, bounded event download ----
    @Test fun anEventDownloadCutOffHalfwayKeepsWhatArrivedAndGoesOnFromThere() = runBlocking {
        val r = P5Rig()
        for (i in 0 until 120) r.dav.seed("/cal/personal/e$i.ics", ev("e$i", "DTSTART;VALUE=DATE:20261118\r\n"))
        r.dav.multigetFailsAfter = 3                                        // the task list's fetch and two chunks of 50 arrive, then the network drops
        SyncEngine(r.store, chunk = 50).sync(r.dav)
        val personal = r.list("/cal/personal/")
        assertEquals(100, r.store.eventObjects(personal.id).size)
        assertNull(personal.syncToken)                                     // not finished: no token yet
        r.dav.multigetFailsAfter = null; r.dav.multigetHrefs = 0
        SyncEngine(r.store, chunk = 50).sync(r.dav)
        assertEquals(120, r.store.eventObjects(r.list("/cal/personal/").id).size)
        assertEquals(1 + 20, r.dav.multigetHrefs)                           // the fake's one task (it lists everything each time) + only the 20 missing events
    }

    @Test fun oneSyncFetchesAtMostItsLimitAndTheNextOneContinues() = runBlocking {
        val r = P5Rig()
        for (i in 0 until 120) r.dav.seed("/cal/personal/e$i.ics", ev("e$i", "DTSTART;VALUE=DATE:20261118\r\n"))
        val engine = SyncEngine(r.store, chunk = 50, maxEventObjects = 50)
        val rep1 = engine.sync(r.dav)
        assertTrue(rep1.eventsIncomplete); assertNull(rep1.error)
        assertEquals(50, r.store.eventObjects(r.list("/cal/personal/").id).size)
        engine.sync(r.dav); engine.sync(r.dav)
        assertEquals(120, r.store.eventObjects(r.list("/cal/personal/").id).size)
        val rep4 = engine.sync(r.dav)
        assertFalse(rep4.eventsIncomplete)
    }

    @Test fun aFullFetchRemovesEventsTheServerNoLongerHasOnlyAfterItFinished() = runBlocking {
        val r = P5Rig()
        r.dav.seed("/cal/personal/a.ics", ev("a", "DTSTART;VALUE=DATE:20261118\r\n"))
        r.dav.seed("/cal/personal/b.ics", ev("b", "DTSTART;VALUE=DATE:20261118\r\n"))
        SyncEngine(r.store).sync(r.dav)
        r.dav.objects.remove("/cal/personal/b.ics")
        r.store.setSyncToken(r.list("/cal/personal/").id, null)
        SyncEngine(r.store).sync(r.dav)
        assertEquals(listOf("/cal/personal/a.ics"), r.store.eventObjects(r.list("/cal/personal/").id).map { it.href })
    }

    @Test fun anOversizedEventIsSkippedAndTheOthersAreKept() = runBlocking {
        val r = P5Rig()
        r.dav.seed("/cal/personal/big.ics", ev("big", "DTSTART;VALUE=DATE:20261118\r\nDESCRIPTION:" + "x".repeat(1_200_000) + "\r\n"))
        r.dav.seed("/cal/personal/ok.ics", ev("ok", "DTSTART;VALUE=DATE:20261118\r\n"))
        val rep = SyncEngine(r.store).sync(r.dav)
        assertEquals(listOf("/cal/personal/ok.ics"), r.store.eventObjects(r.list("/cal/personal/").id).map { it.href })
        assertNull(rep.error)
    }

    // ---- minor: the kind of a list changes (Events <-> Both) ----
    @Test fun aCalendarThatStartsHoldingTasksDropsItsEventRowsAndToken() {
        val store = MemoryTaskStore()
        val l = store.replaceLists(listOf(RemoteList("/cal/p/", "P", "#000000", true, ListKind.Events, 0))).single()
        store.upsertEvents(l.id, listOf(RemoteObject("/cal/p/e.ics", "e", ev("e", "DTSTART;VALUE=DATE:20261118\r\n"))))
        store.setSyncToken(l.id, "tok")
        val both = store.replaceLists(listOf(RemoteList("/cal/p/", "P", "#000000", true, ListKind.Both, 0))).single()
        assertTrue(store.eventObjects(both.id).isEmpty()); assertNull(both.syncToken)
        store.upsertObjects(both.id, listOf(RemoteObject("/cal/p/t.ics", "e", todo("t"))))
        store.setSyncToken(both.id, "tok2")
        val back = store.replaceLists(listOf(RemoteList("/cal/p/", "P", "#000000", true, ListKind.Events, 0))).single()
        assertTrue(store.objects(back.id).isEmpty()); assertNull(back.syncToken)
    }

    // ---- optional hardening: the push only ever sends task text ----
    @Test fun thePushRefusesTextThatIsNotATask() = runBlocking {
        val r = P5Rig()
        SyncEngine(r.store).sync(r.dav)
        Outbox(r.store).edit("/cal/tasks/t1.ics", ev("t1", "DTSTART;VALUE=DATE:20261118\r\n"))
        r.dav.log.clear()
        val rep = SyncEngine(r.store).push(r.dav)
        assertTrue(r.dav.log.none { it.startsWith("PUT") })
        assertEquals(1, rep.failed); assertEquals(OutboxState.Failed, r.store.outboxAll().single().state)
    }

    // ---- I1: a pull landing between plan and write is never overwritten ----
    @Test fun editEachWithTheExpectedOldTextLeavesATaskThatChangedMeanwhile() {
        val store = MemoryTaskStore()
        val l = store.replaceLists(listOf(RemoteList("/cal/a/", "A", "#000000", true, ListKind.Tasks, 0))).single()
        store.upsertObjects(l.id, listOf(RemoteObject("/cal/a/o1.ics", "e1", todo("o1", "DUE;VALUE=DATE:20261110\r\n")), RemoteObject("/cal/a/o2.ics", "e2", todo("o2", "DUE;VALUE=DATE:20261110\r\n"))))
        val shown = TaskViews.today(TaskParser.parseAll(store.allObjects(), ZA), D18).overdue
        val p = OverdueTamer.plan(shown, { store.objectByHref(it)?.ics }, store.lists(), emptySet(), RescheduleTarget.Tomorrow, D18, 18, ZA, NOW18)
        val pulled = todo("o1", "DUE;VALUE=DATE:20261201\r\nSUMMARY:changed on the server\r\n")
        store.upsertObjects(l.id, listOf(RemoteObject("/cal/a/o1.ics", "e9", pulled)))          // a pull lands in between
        val res = Outbox(store).editEach(p.writes, p.old)
        assertEquals(listOf("/cal/a/o2.ics"), res.written); assertEquals(listOf("/cal/a/o1.ics"), res.failed)
        assertEquals(pulled, store.objectByHref("/cal/a/o1.ics")!!.ics)
    }

    // ---- I6 (ruling for the owner): move-all leaves repeating tasks to be moved one by one ----
    @Test fun moveAllSkipsRepeatingTasksAndCountsThem() {
        val store = MemoryTaskStore()
        val l = store.replaceLists(listOf(RemoteList("/cal/a/", "A", "#000000", true, ListKind.Tasks, 0))).single()
        store.upsertObjects(l.id, listOf(
            RemoteObject("/cal/a/r.ics", "e1", todo("r", "DTSTART;VALUE=DATE:20261108\r\nDUE;VALUE=DATE:20261110\r\nRRULE:FREQ=WEEKLY\r\n")),
            RemoteObject("/cal/a/p.ics", "e2", todo("p", "DUE;VALUE=DATE:20261110\r\n")),
        ))
        val shown = TaskViews.today(TaskParser.parseAll(store.allObjects(), ZA), D18).overdue
        val p = OverdueTamer.plan(shown, { store.objectByHref(it)?.ics }, store.lists(), emptySet(), RescheduleTarget.Weekend, D18, 18, ZA, NOW18)
        assertEquals(setOf("/cal/a/p.ics"), p.writes.keys); assertEquals(1, p.repeating)
    }

    // ---- I2: RDATE ----
    @Test fun anEventWithOnlyExtraDatesShowsOnThoseDays() {
        val timed = StoredObject("/c/r.ics", 1, "e", ev("r", "SUMMARY:Extra\r\nDTSTART:20261110T080000Z\r\nDTEND:20261110T090000Z\r\nRDATE:20261118T130000Z,20261125T130000Z\r\n"))
        assertEquals(listOf(LocalTime.of(15, 0)), DayEvents.forDay(listOf(timed), D18, ZA).map { it.start })
        val allDay = StoredObject("/c/a.ics", 1, "e", ev("a", "SUMMARY:Holiday\r\nDTSTART;VALUE=DATE:20261110\r\nRDATE;VALUE=DATE:20261118\r\n"))
        assertEquals(1, DayEvents.forDay(listOf(allDay), D18, ZA).size)
        assertEquals(0, DayEvents.forDay(listOf(allDay), D18.plusDays(1), ZA).size)
        val period = StoredObject("/c/p.ics", 1, "e", ev("p", "SUMMARY:Period\r\nDTSTART:20261110T080000Z\r\nRDATE;VALUE=PERIOD:20261118T120000Z/PT1H\r\n"))
        DayEvents.forDay(listOf(period), D18, ZA)                           // a PERIOD value never breaks anything
        val withRule = StoredObject("/c/w.ics", 1, "e", ev("w", "SUMMARY:Mixed\r\nDTSTART;TZID=Europe/Athens:20261102T090000\r\nRRULE:FREQ=WEEKLY;BYDAY=MO\r\n" +
            "RDATE;TZID=Europe/Athens:20261118T170000\r\nEXDATE;TZID=Europe/Athens:20261116T090000\r\n"))
        assertEquals(listOf(LocalTime.of(17, 0)), DayEvents.forDay(listOf(withRule), D18, ZA).map { it.start })
        assertEquals(0, DayEvents.forDay(listOf(withRule), LocalDate.of(2026, 11, 16), ZA).size)
    }

    // ---- minor: an invitation the owner declined is not on the strip ----
    @Test fun anEventTheOwnerDeclinedIsHidden() {
        fun with(att: String) = StoredObject("/c/i.ics", 1, "e", ev("i", "SUMMARY:Invite\r\nDTSTART:20261118T130000Z\r\nORGANIZER:mailto:boss@example.com\r\n$att"))
        assertEquals(0, DayEvents.forDay(listOf(with("ATTENDEE;PARTSTAT=DECLINED;CN=Alex:mailto:alex@example.com\r\n")), D18, ZA, owner = "alex").size)
        assertEquals(1, DayEvents.forDay(listOf(with("ATTENDEE;PARTSTAT=ACCEPTED:mailto:alex@example.com\r\n")), D18, ZA, owner = "alex").size)
        assertEquals(1, DayEvents.forDay(listOf(with("ATTENDEE;PARTSTAT=DECLINED:mailto:maria@example.com\r\n")), D18, ZA, owner = "alex").size)
    }
}

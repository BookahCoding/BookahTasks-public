package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// 18 Nov 2026 is a Wednesday
private val WED = LocalDate.of(2026, 11, 18)
private val ZONE: ZoneId = ZoneId.of("Europe/Athens")
private val NOW: Instant = Instant.parse("2026-11-18T10:00:00Z")

private fun ics(uid: String, body: String) =
    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VTODO\r\nUID:$uid\r\nDTSTAMP:20260101T000000Z\r\nSUMMARY:$uid\r\n$body" +
        "END:VTODO\r\nEND:VCALENDAR\r\n"

private class TamerRig {
    val store = MemoryTaskStore()
    val lists = store.replaceLists(
        listOf(
            RemoteList("/cal/a/", "A", "#A63F2D", true, ListKind.Tasks, 0),
            RemoteList("/cal/ro/", "Shared", "#245A86", false, ListKind.Tasks, 1),
        ),
    )
    val a = lists[0]; val ro = lists[1]
    val outbox = Outbox(store) { 1000L }
    fun put(list: TaskList, uid: String, body: String) = store.upsertObjects(list.id, listOf(RemoteObject("${list.href}$uid.ics", "e-$uid", ics(uid, body))))
    fun tasks() = TaskParser.parseAll(store.allObjects(), ZONE)
    fun task(uid: String) = tasks().first { it.uid == uid }
    fun plan(target: RescheduleTarget, shown: List<Task> = TaskViews.today(tasks(), WED).overdue) =
        OverdueTamer.plan(shown, { store.objectByHref(it)?.ics }, store.lists(), OverdueTamer.blocked(store.outboxAll()), target, WED, 18, ZONE, NOW)
}

class OverdueTamerTest {
    @Test fun weekendIsTheSaturdayOfThisWeek() {
        val t = Task("u", 1, "/l/u.ics", "e", "T", null, null, TaskWhen(LocalDate.of(2026, 11, 10), null), null, TaskStatus.NeedsAction, 0,
            Priority.None, emptyList(), false, false, null, null, emptyList(), null, 0, null, null, false)
        assertEquals(LocalDate.of(2026, 11, 21), Reschedule.plan(t, RescheduleTarget.Weekend, WED, 18).due!!.date)
        assertEquals(LocalDate.of(2026, 11, 21), Reschedule.plan(t, RescheduleTarget.Weekend, LocalDate.of(2026, 11, 16), 18).due!!.date)   // Monday
        assertEquals(LocalDate.of(2026, 11, 21), Reschedule.plan(t, RescheduleTarget.Weekend, LocalDate.of(2026, 11, 20), 18).due!!.date)   // Friday
    }

    @Test fun onASaturdayOrSundayWeekendMeansTheNextSaturday() {
        val t = Task("u", 1, "/l/u.ics", "e", "T", null, null, TaskWhen(LocalDate.of(2026, 11, 10), null), null, TaskStatus.NeedsAction, 0,
            Priority.None, emptyList(), false, false, null, null, emptyList(), null, 0, null, null, false)
        assertEquals(LocalDate.of(2026, 11, 28), Reschedule.plan(t, RescheduleTarget.Weekend, LocalDate.of(2026, 11, 21), 18).due!!.date)
        assertEquals(LocalDate.of(2026, 11, 28), Reschedule.plan(t, RescheduleTarget.Weekend, LocalDate.of(2026, 11, 22), 18).due!!.date)
    }

    @Test fun moveAllPlansEveryOverdueTaskOfAWritableList() {
        val r = TamerRig()
        r.put(r.a, "o1", "DUE;VALUE=DATE:20261110\r\n")
        r.put(r.a, "o2", "DUE:20261115T080000Z\r\n")
        r.put(r.a, "fine", "DUE;VALUE=DATE:20261120\r\n")
        val p = r.plan(RescheduleTarget.Tomorrow)
        assertEquals(setOf("/cal/a/o1.ics", "/cal/a/o2.ics"), p.writes.keys)
        assertEquals(0, p.readOnly + p.stale + p.blocked)
        val o1 = TaskParser.parse(StoredObject("/x", 0, "", p.writes.getValue("/cal/a/o1.ics")), ZONE).first()
        assertEquals(TaskWhen(LocalDate.of(2026, 11, 19), null), o1.due)
        val o2 = TaskParser.parse(StoredObject("/x", 0, "", p.writes.getValue("/cal/a/o2.ics")), ZONE).first()
        assertEquals(LocalDate.of(2026, 11, 19), o2.due!!.date); assertEquals(10, o2.due!!.time!!.hour)          // keeps its time of day (Athens)
        assertEquals(r.store.objectByHref("/cal/a/o1.ics")!!.ics, p.old.getValue("/cal/a/o1.ics"))
    }

    @Test fun readOnlyListsAreSkippedAndCounted() {
        val r = TamerRig()
        r.put(r.a, "o1", "DUE;VALUE=DATE:20261110\r\n")
        r.put(r.ro, "o2", "DUE;VALUE=DATE:20261110\r\n")
        val p = r.plan(RescheduleTarget.Today)
        assertEquals(setOf("/cal/a/o1.ics"), p.writes.keys); assertEquals(1, p.readOnly)
    }

    @Test fun aTaskChangedMeanwhileIsLeftAlone() {
        val r = TamerRig()
        r.put(r.a, "o1", "DUE;VALUE=DATE:20261110\r\n")
        r.put(r.a, "o2", "DUE;VALUE=DATE:20261111\r\n")
        val shown = TaskViews.today(r.tasks(), WED).overdue
        // after the dialog was shown: o1 was finished elsewhere, o2 was moved to a later day
        r.put(r.a, "o1", "DUE;VALUE=DATE:20261110\r\nSTATUS:COMPLETED\r\n")
        r.put(r.a, "o2", "DUE;VALUE=DATE:20261130\r\n")
        val p = r.plan(RescheduleTarget.Today, shown)
        assertTrue(p.writes.isEmpty()); assertEquals(2, p.stale)
    }

    @Test fun aTaskWithAChangeWaitingForTheOwnerIsBlocked() {
        val r = TamerRig()
        r.put(r.a, "o1", "DUE;VALUE=DATE:20261110\r\n")
        r.put(r.a, "o2", "DUE;VALUE=DATE:20261110\r\n")
        r.outbox.edit("/cal/a/o1.ics", r.store.objectByHref("/cal/a/o1.ics")!!.ics)
        r.store.outboxUpdate(r.store.outboxAll().single().copy(state = OutboxState.Conflict))
        val p = r.plan(RescheduleTarget.Today)
        assertEquals(setOf("/cal/a/o2.ics"), p.writes.keys); assertEquals(1, p.blocked)
    }

    // review I6: move-all leaves repeating tasks alone (Phase5ReviewTest); moved one by one, a repeating task keeps its gap
    @Test fun aRepeatingTaskMovedOnItsOwnKeepsTheGapBetweenStartAndDeadline() {
        val r = TamerRig()
        r.put(r.a, "rep", "DTSTART;VALUE=DATE:20261108\r\nDUE;VALUE=DATE:20261110\r\nRRULE:FREQ=WEEKLY\r\n")
        val out = Reschedule.applyTo(r.store.objectByHref("/cal/a/rep.ics")!!.ics, RescheduleTarget.Weekend, WED, 18, ZONE)!!
        val t = TaskParser.parse(StoredObject("/x", 0, "", out), ZONE).first()
        assertEquals(LocalDate.of(2026, 11, 21), t.due!!.date); assertEquals(LocalDate.of(2026, 11, 19), t.start!!.date)
        assertTrue(IcsPatcher.readProperty(out, "RRULE") == "FREQ=WEEKLY")
    }

    @Test fun somedayParksThemAndTheyLeaveOverdue() {
        val r = TamerRig()
        r.put(r.a, "o1", "DUE;VALUE=DATE:20261110\r\n")
        val p = r.plan(RescheduleTarget.Someday)
        val t = TaskParser.parse(StoredObject("/x", 0, "", p.writes.values.single()), ZONE).first()
        assertTrue(t.someday); assertEquals(TaskWhen(LocalDate.of(2026, 11, 10), null), t.due)
        assertTrue(TaskViews.today(listOf(t), WED).overdue.isEmpty())
    }

    @Test fun editEachWritesWhatItCanAndCountsTheRest() {
        val r = TamerRig()
        r.put(r.a, "o1", "DUE;VALUE=DATE:20261110\r\n")
        r.put(r.a, "o2", "DUE;VALUE=DATE:20261110\r\n")
        r.outbox.delete("/cal/a/o2.ics")                                  // being deleted: edit refuses it
        val res = r.outbox.editEach(mapOf("/cal/a/o1.ics" to ics("o1", "DUE;VALUE=DATE:20261119\r\n"), "/cal/a/o2.ics" to "X", "/cal/a/none.ics" to "Y"))
        assertEquals(listOf("/cal/a/o1.ics"), res.written); assertEquals(2, res.failed.size)
        assertEquals(LocalDate.of(2026, 11, 19), r.task("o1").due!!.date)
        assertEquals(2, r.store.outboxAll().size)                          // the edit of o1 and the delete of o2
        assertNull(r.store.objectByHref("/cal/a/none.ics"))
    }
}

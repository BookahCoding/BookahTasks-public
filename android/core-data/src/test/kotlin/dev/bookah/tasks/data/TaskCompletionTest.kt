package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

private val Z2: ZoneId = ZoneId.of("Europe/Athens")
private val NOW2: Instant = Instant.parse("2026-11-18T09:00:00Z")

private fun todo(uid: String, body: String = "", title: String = "T") =
    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VTODO\r\nUID:$uid\r\nDTSTAMP:20260101T000000Z\r\nSUMMARY:$title\r\n$body" +
        "END:VTODO\r\nEND:VCALENDAR\r\n"

private class CompletionRig(editable: Boolean = true) {
    val store = MemoryTaskStore()
    val list = store.replaceLists(listOf(RemoteList("/l/", "Tasks", "#A63F2D", editable, ListKind.Tasks, 0)))[0]
    val outbox = Outbox(store) { 1000L }
    fun put(uid: String, body: String = "", title: String = "T"): String {
        val href = "/l/$uid.ics"
        store.upsertObjects(list.id, listOf(RemoteObject(href, "e-$uid", todo(uid, body, title))))
        return href
    }
    fun task(href: String) = TaskParser.parse(store.objectByHref(href)!!, Z2).first()
    fun stamp(href: String) = WidgetRows.stamp(task(href))
}

class TaskCompletionTest {
    private val dueToday = "DUE;VALUE=DATE:20261118\r\n"

    @Test fun tickingAPlainTaskFinishesItAndQueuesOneEdit() {
        val r = CompletionRig(); val h = r.put("a", dueToday)
        val out = TaskCompletion.tick(r.store, r.outbox, h, r.stamp(h), Z2, NOW2)
        assertEquals(TaskCompletion.Outcome.Done, out)
        assertFalse(r.task(h).isOpen)
        assertEquals(1, r.store.outboxAll().size)
    }

    @Test fun theEditIsExactlyWhatTheAppsOwnTickWrites() {
        val r = CompletionRig(); val h = r.put("a", dueToday)
        val expected = TaskEdit.complete(r.store.objectByHref(h)!!.ics, NOW2, Z2).ics
        TaskCompletion.tick(r.store, r.outbox, h, null, Z2, NOW2)
        assertEquals(expected, r.store.objectByHref(h)!!.ics)
    }

    @Test fun aStaleStampDoesNothing() {
        val r = CompletionRig(); val h = r.put("a", dueToday)
        val stale = r.stamp(h)
        r.store.upsertObjects(r.list.id, listOf(RemoteObject(h, "e2", todo("a", "DUE;VALUE=DATE:20261125\r\n"))))   // changed meanwhile
        val out = TaskCompletion.tick(r.store, r.outbox, h, stale, Z2, NOW2)
        assertTrue(out is TaskCompletion.Outcome.Skipped)
        assertTrue(r.task(h).isOpen); assertTrue(r.store.outboxAll().isEmpty())
    }

    @Test fun aSecondTapOnARepeatingTaskAdvancesItOnlyOnce() {
        val r = CompletionRig(); val h = r.put("a", "DTSTART;VALUE=DATE:20261118\r\nRRULE:FREQ=DAILY\r\n")
        val stamp = r.stamp(h)
        assertEquals(TaskCompletion.Outcome.Advanced, TaskCompletion.tick(r.store, r.outbox, h, stamp, Z2, NOW2))
        val after = r.task(h).start
        val second = TaskCompletion.tick(r.store, r.outbox, h, stamp, Z2, NOW2)          // same drawn stamp again
        assertTrue(second is TaskCompletion.Outcome.Skipped)
        assertEquals(after, r.task(h).start)
    }

    @Test fun tickingAParentFinishesItsOpenSubtasks() {
        val r = CompletionRig(); val p = r.put("p", dueToday)
        val k = r.put("k", "RELATED-TO:p\r\n")
        assertEquals(TaskCompletion.Outcome.Done, TaskCompletion.tick(r.store, r.outbox, p, r.stamp(p), Z2, NOW2))
        assertFalse(r.task(k).isOpen)
    }

    @Test fun aReadOnlyListRefuses() {
        val r = CompletionRig(editable = false); val h = r.put("a", dueToday)
        assertTrue(TaskCompletion.tick(r.store, r.outbox, h, null, Z2, NOW2) is TaskCompletion.Outcome.Skipped)
        assertTrue(r.task(h).isOpen)
    }

    @Test fun aVanishedTaskIsSkippedNotACrash() {
        val r = CompletionRig()
        assertTrue(TaskCompletion.tick(r.store, r.outbox, "/l/none.ics", null, Z2, NOW2) is TaskCompletion.Outcome.Skipped)
    }

    @Test fun aTaskInConflictIsSkipped() {
        val r = CompletionRig(); val h = r.put("a", dueToday)
        r.outbox.edit(h, todo("a", dueToday + "X-A:1\r\n"))
        r.store.outboxUpdate(r.store.outboxAll().single().copy(state = OutboxState.Conflict))
        assertTrue(TaskCompletion.tick(r.store, r.outbox, h, null, Z2, NOW2) is TaskCompletion.Outcome.Skipped)
    }

    @Test fun reopenRestoresAFinishedTask() {
        val r = CompletionRig(); val h = r.put("a", dueToday)
        TaskCompletion.tick(r.store, r.outbox, h, null, Z2, NOW2)
        assertEquals(TaskCompletion.Outcome.Reopened, TaskCompletion.reopen(r.store, r.outbox, h, Z2, NOW2))
        assertTrue(r.task(h).isOpen)
    }

    @Test fun reopeningAParentReopensTheSubtasksTheTickClosedAndOnlyThose() {
        val r = CompletionRig(); val p = r.put("p", dueToday)
        val k = r.put("k", "RELATED-TO:p\r\n")
        val old = r.put("o", "RELATED-TO:p\r\nSTATUS:COMPLETED\r\nCOMPLETED:20260101T000000Z\r\n")      // finished long before: stays finished
        TaskCompletion.tick(r.store, r.outbox, p, r.stamp(p), Z2, NOW2)
        assertFalse(r.task(k).isOpen)
        assertEquals(TaskCompletion.Outcome.Reopened, TaskCompletion.reopen(r.store, r.outbox, p, Z2, NOW2.plusSeconds(30)))
        assertTrue(r.task(p).isOpen); assertTrue(r.task(k).isOpen); assertFalse(r.task(old).isOpen)
    }

    @Test fun reopenOfAnOpenTaskDoesNothing() {
        val r = CompletionRig(); val h = r.put("a", dueToday)
        assertTrue(TaskCompletion.reopen(r.store, r.outbox, h, Z2, NOW2) is TaskCompletion.Outcome.Skipped)
        assertTrue(r.store.outboxAll().isEmpty())
    }
}

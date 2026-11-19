package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val Z: ZoneId = ZoneId.of("Europe/Athens")
private val TODAY: LocalDate = LocalDate.of(2026, 11, 18)
private val FRI: LocalDate = LocalDate.of(2026, 11, 20)

private fun todo(body: String) =
    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VTODO\r\nUID:w1\r\nDTSTAMP:20260101T000000Z\r\nSUMMARY:Quote\r\n$body" +
        "X-OTHER:keep me\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
private fun read(ics: String) = TaskParser.parse(StoredObject("/l/w1.ics", 1, "e", ics), Z).first()

class WaitingTest {
    @Test fun markingWaitingWritesTheTagTheDateAndThePerson() {
        val ics = TaskEdit.setWaiting(todo("CATEGORIES:work\r\n"), "Maria", FRI, Z)
        val t = read(ics)
        assertTrue(t.waiting); assertEquals(listOf("work"), t.tags)
        assertEquals(TaskWhen(FRI, null), t.start)
        assertEquals("Maria", t.waitingFor)
        assertEquals("Waiting for: Maria", t.notes)
        assertTrue(ics.contains("X-OTHER:keep me"))
    }

    @Test fun theRestOfTheNotesIsKeptAndThePersonLineReplaced() {
        val start = todo("DESCRIPTION:Call about the boiler\\nAsk for the price\\, too\r\n")
        val once = TaskEdit.setWaiting(start, "Maria", FRI, Z)
        assertEquals("Waiting for: Maria\nCall about the boiler\nAsk for the price, too", read(once).notes)
        val twice = TaskEdit.setWaiting(once, "Νίκος", FRI.plusDays(3), Z)
        assertEquals("Waiting for: Νίκος\nCall about the boiler\nAsk for the price, too", read(twice).notes)
        assertEquals("Νίκος", read(twice).waitingFor)
        val ended = TaskEdit.endWaiting(twice)
        assertEquals("Call about the boiler\nAsk for the price, too", read(ended).notes)
        assertFalse(read(ended).waiting); assertNull(read(ended).waitingFor)
        assertEquals(TaskWhen(FRI.plusDays(3), null), read(ended).start)                    // the start date stays
    }

    @Test fun noPersonWritesNoLineAndAGreekLineIsReadToo() {
        val t = read(TaskEdit.setWaiting(todo(""), "  ", FRI, Z))
        assertTrue(t.waiting); assertNull(t.waitingFor); assertNull(t.notes)
        assertEquals("Ελένη", read(todo("CATEGORIES:waiting\r\nDESCRIPTION:Αναμονή για: Ελένη\\nάλλο\r\n")).waitingFor)
    }

    @Test fun aWaitingTaskLeavesTodayUntilItsDateThenComesBack() {
        val t = read(TaskEdit.setWaiting(todo("DTSTART;VALUE=DATE:20261110\r\n"), "Maria", FRI, Z))
        assertTrue(TaskViews.today(listOf(t), TODAY).let { it.today.isEmpty() && it.overdue.isEmpty() && it.evening.isEmpty() })
        assertEquals(FRI, TaskViews.upcomingDate(t, TODAY))
        assertEquals(1, TaskViews.today(listOf(t), FRI).today.size)
        assertEquals(listOf(t), TaskViews.waiting(listOf(t)))
    }

    @Test fun aDeadlineBeforeTheCheckBackDateMovesUpToIt() {
        val overdue = read(TaskEdit.setWaiting(todo("DUE:20261115T150000Z\r\n"), "Maria", FRI, Z))
        assertEquals(FRI, overdue.due!!.date); assertEquals(LocalTime.of(17, 0), overdue.due!!.time)
        assertTrue(TaskViews.today(listOf(overdue), TODAY).overdue.isEmpty())                // no longer overdue, not in Today
        assertTrue(TaskViews.today(listOf(overdue), TODAY).today.isEmpty())
        val later = read(TaskEdit.setWaiting(todo("DUE;VALUE=DATE:20261130\r\n"), null, FRI, Z))
        assertEquals(LocalDate.of(2026, 11, 30), later.due!!.date)                           // a later deadline stays
        assertTrue(TaskViews.deadlineMoves(later.copy(due = TaskWhen(TODAY, null)), FRI))
        assertFalse(TaskViews.deadlineMoves(later, FRI))
    }

    // review I4: a "Waiting for:" line the owner wrote in a task that was not waiting is the owner's text, never replaced
    @Test fun theOwnersOwnWaitingForLineIsKeptWhenTheTaskWasNotWaiting() {
        val ics = TaskEdit.setWaiting(todo("DESCRIPTION:Waiting for: the bank\\, then file\r\n"), "Maria", FRI, Z)
        assertEquals("Waiting for: Maria\nWaiting for: the bank, then file", read(ics).notes)
        assertNull(read(todo("DESCRIPTION:Waiting for: the bank\r\n")).waitingFor)               // not waiting: no person read either
    }

    @Test fun aRepeatingTaskCannotWait() {
        try { TaskEdit.setWaiting(todo("DTSTART;VALUE=DATE:20261110\r\nRRULE:FREQ=DAILY\r\n"), "M", FRI, Z); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun someOtherTagsAndSomedayAreKept() {
        val ics = TaskEdit.setWaiting(todo("CATEGORIES:a,someday,b\r\n"), "M", FRI, Z)
        val t = read(ics); assertTrue(t.someday); assertEquals(listOf("a", "b"), t.tags)
        val e = read(TaskEdit.endWaiting(ics)); assertTrue(e.someday); assertFalse(e.waiting); assertEquals(listOf("a", "b"), e.tags)
    }

    @Test fun theWaitingViewIsSortedByCheckBackDate() {
        val a = read(TaskEdit.setWaiting(todo(""), "A", FRI.plusDays(5), Z)).copy(uid = "a")
        val b = read(TaskEdit.setWaiting(todo(""), "B", FRI, Z)).copy(uid = "b")
        val done = b.copy(uid = "c", status = TaskStatus.Completed)
        assertEquals(listOf("b", "a"), TaskViews.waiting(listOf(a, b, done)).map { it.uid })
    }
}

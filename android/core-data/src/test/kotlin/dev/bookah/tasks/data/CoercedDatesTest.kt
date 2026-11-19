package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val ATHENS = ZoneId.of("Europe/Athens")
private const val CR = "\r\n"

private fun ics(vararg props: String) =
    (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VTODO", "UID:t1", "DTSTAMP:20261116T182800Z", "SUMMARY:T") + props + listOf("END:VTODO", "END:VCALENDAR")).joinToString(CR) + CR

private fun task(text: String): Task = TaskParser.parse(StoredObject("/l/t1.ics", 1, "e", text), ATHENS).first { !it.isOccurrenceCopy }

/**
 * The server wants DTSTART and DUE to be the same kind, so a start without a time beside a timed deadline is stored as 00:00, and a deadline
 * without a time beside a timed start as 23:59 (TaskEdit.setDates). Reading them back must undo that, or reminders would fire at midnight.
 */
class CoercedDatesTest {
    @Test fun aStartAtMidnightBesideATimedDeadlineReadsAsDateOnly() {
        // 22:00Z on 17 Nov is 00:00 on 18 Nov in Athens
        val t = task(ics("DTSTART:20261117T220000Z", "DUE:20261118T150000Z"))
        assertNull(t.start!!.time); assertEquals(LocalDate.of(2026, 11, 18), t.start!!.date)
        assertEquals(LocalTime.of(17, 0), t.due!!.time)
    }

    @Test fun aDeadlineAtTwentyThreeFiftyNineBesideATimedStartReadsAsDateOnly() {
        val t = task(ics("DTSTART:20261118T070000Z", "DUE:20261119T215900Z"))           // 09:00 start, due 23:59 Athens on 19 Nov
        assertEquals(LocalTime.of(9, 0), t.start!!.time)
        assertNull(t.due!!.time); assertEquals(LocalDate.of(2026, 11, 19), t.due!!.date)
    }

    @Test fun aGenuineMidnightStartAloneKeepsItsTime() {
        val t = task(ics("DTSTART:20261117T220000Z"))
        assertEquals(LocalTime.MIDNIGHT, t.start!!.time)
    }

    @Test fun anAllDayPairWrittenAsMidnightAndTwentyThreeFiftyNineReadsAsDates() {
        val t = task(ics("DTSTART:20261117T220000Z", "DUE:20261118T215900Z"))
        assertNull(t.start!!.time); assertNull(t.due!!.time)
    }

    @Test fun aTaskCreatedByQuickAddRemindsAtNineNotAtMidnight() {
        val now = LocalDate.of(2026, 11, 17).atTime(8, 0).atZone(ATHENS).toInstant().toEpochMilli()
        val text = TaskEdit.setDates(IcsPatcher.newTask("u", "T", java.time.Instant.ofEpochMilli(now)), TaskWhen(LocalDate.of(2026, 11, 18), null), TaskWhen(LocalDate.of(2026, 11, 20), LocalTime.of(17, 0)), ATHENS)
        val plan = ReminderPlanner.plan(listOf(task(text)), now, ATHENS)
        val nine = LocalDate.of(2026, 11, 18).atTime(9, 0).atZone(ATHENS).toInstant().toEpochMilli()
        assertEquals(listOf(ReminderKind.Start, ReminderKind.DueSoon), plan.map { it.kind })
        assertEquals(nine, plan[0].triggerAtMs); assertTrue(plan[0].dateOnly)
        assertEquals(LocalDate.of(2026, 11, 20).atTime(16, 0).atZone(ATHENS).toInstant().toEpochMilli(), plan[1].triggerAtMs)
    }

    @Test fun aTimedStartWithADateOnlyDeadlineDoesNotRemindOfTheDeadlineBeforeTheStart() {
        val now = LocalDate.of(2026, 11, 17).atTime(8, 0).atZone(ATHENS).toInstant().toEpochMilli()
        val text = TaskEdit.setDates(IcsPatcher.newTask("u", "T", java.time.Instant.ofEpochMilli(now)), TaskWhen(LocalDate.of(2026, 11, 18), LocalTime.of(15, 0)), TaskWhen(LocalDate.of(2026, 11, 18), null), ATHENS)
        val plan = ReminderPlanner.plan(listOf(task(text)), now, ATHENS)
        assertEquals(listOf(ReminderKind.Start), plan.map { it.kind })              // "due today" at 09:00 would come before the 15:00 start
    }
}

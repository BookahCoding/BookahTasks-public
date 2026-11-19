package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val CR = "\r\n"
private val ATHENS = ZoneId.of("Europe/Athens")
private val NOW = Instant.parse("2026-11-17T10:00:00Z")

private fun ics(vararg props: String) =
    (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VTODO", "UID:t1", "DTSTAMP:20261116T182800Z", "SUMMARY:Old") + props + listOf("END:VTODO", "END:VCALENDAR")).joinToString(CR) + CR

private fun task(text: String): Task = TaskParser.parse(StoredObject("/l/t1.ics", 1, "e", text), ATHENS).first { !it.isOccurrenceCopy }
private fun w(y: Int, m: Int, d: Int, h: Int? = null, min: Int = 0) = TaskWhen(LocalDate.of(y, m, d), h?.let { LocalTime.of(it, min) })
private val ALARM = arrayOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "TRIGGER:-PT30M", "END:VALARM")

class TaskEditTest {
    @Test fun titleAndNotesChangeOnlyTheirOwnLines() {
        val base = ics("X-APPLE-SORT-ORDER:10", "RRULE:FREQ=WEEKLY", *ALARM)
        val t = TaskEdit.setNotes(TaskEdit.setTitle(base, "Νέος, τίτλος; ναι"), "line 1\nline 2")
        assertEquals("Νέος, τίτλος; ναι", task(t).title)
        assertEquals("line 1\nline 2", task(t).notes)
        assertTrue(t.contains("X-APPLE-SORT-ORDER:10") && t.contains("RRULE:FREQ=WEEKLY") && t.contains("TRIGGER:-PT30M"))
        assertNull(task(TaskEdit.setNotes(t, "  ")).notes)
        assertTrue(!TaskEdit.setNotes(t, null).contains("DESCRIPTION:line"))
    }

    @Test fun aTimedDateIsWrittenInUtcAndReadsBackTheSame() {
        val out = TaskEdit.setDates(ics(), start = null, due = w(2026, 11, 20, 19, 0), zone = ATHENS)
        assertTrue(out.contains("DUE:20261120T170000Z"))
        assertEquals(w(2026, 11, 20, 19, 0), task(out).due)
    }

    // The server (iCalendar) requires DTSTART and DUE to be the same kind of value: a mixed pair is made timed, never written mixed.
    @Test fun aDateOnlyStartWithATimedDeadlineBecomesTimedFromMidnight() {
        val out = TaskEdit.setDates(ics(), start = w(2026, 11, 18), due = w(2026, 11, 20, 9, 30), zone = ATHENS)
        assertTrue(!out.contains("VALUE=DATE"))
        assertTrue(out.contains("DTSTART:20261117T220000Z"))                      // 18 Nov 00:00 in Athens, stored as a time
        assertEquals(w(2026, 11, 18), task(out).start)                            // but read back as the date it was
        assertEquals(w(2026, 11, 20, 9, 30), task(out).due)
    }

    @Test fun aTimedStartWithADateOnlyDeadlineGetsTheEndOfThatDay() {
        val out = TaskEdit.setDates(ics(), start = w(2026, 11, 18, 9, 0), due = w(2026, 11, 20), zone = ATHENS)
        assertTrue(!out.contains("VALUE=DATE"))
        assertTrue(out.contains("DUE:20261120T215900Z"))                          // 20 Nov 23:59 in Athens, stored as a time
        assertEquals(w(2026, 11, 18, 9, 0), task(out).start)
        assertEquals(w(2026, 11, 20), task(out).due)                              // but read back as the date it was
    }

    @Test fun twoDateOnlyValuesOrASingleOneStayDateOnly() {
        val both = TaskEdit.setDates(ics(), start = w(2026, 11, 18), due = w(2026, 11, 20), zone = ATHENS)
        assertTrue(both.contains("DTSTART;VALUE=DATE:20261118") && both.contains("DUE;VALUE=DATE:20261120"))
        val one = TaskEdit.setDates(ics(), start = null, due = w(2026, 11, 20), zone = ATHENS)
        assertEquals(w(2026, 11, 20), task(one).due)
    }

    @Test fun oldDateParametersAreReplacedAndDatesCanBeRemoved() {
        val base = ics("DUE;TZID=Europe/Athens:20261120T190000", "DTSTART;TZID=Europe/Athens:20261119T090000")
        val out = TaskEdit.setDates(base, start = null, due = w(2026, 11, 21, 8, 0), zone = ATHENS)
        assertTrue(!out.contains("TZID") && !out.contains("DTSTART"))
        assertEquals(w(2026, 11, 21, 8, 0), task(out).due)
    }

    @Test fun aDeadlineBeforeTheStartIsClampedToTheStart() {
        val out = TaskEdit.setDates(ics(), start = w(2026, 11, 20, 10, 0), due = w(2026, 11, 19, 8, 0), zone = ATHENS)
        assertEquals(w(2026, 11, 20, 10, 0), task(out).due)
    }

    @Test fun priorityIsOneFiveNineOrNone() {
        for ((p, code) in listOf(Priority.High to "1", Priority.Medium to "5", Priority.Low to "9")) {
            val out = TaskEdit.setPriority(ics(), p)
            assertTrue(out.contains("PRIORITY:$code")); assertEquals(p, task(out).priority)
        }
        val none = TaskEdit.setPriority(ics("PRIORITY:1"), Priority.None)
        assertTrue(!none.contains("PRIORITY")); assertEquals(Priority.None, task(none).priority)
    }

    @Test fun tagEditsKeepTheHiddenSomedayAndWaitingTags() {
        val base = ics("CATEGORIES:home,someday", "CATEGORIES:errands,waiting")
        val out = TaskEdit.setTags(base, listOf("a, b", "c"))
        val t = task(out)
        assertEquals(listOf("a, b", "c"), t.tags); assertTrue(t.someday); assertTrue(t.waiting)
        assertEquals(1, IcsPatcher.readAllProperties(out, "CATEGORIES").size)
        assertEquals(emptyList<String>(), task(TaskEdit.setTags(base, emptyList())).tags)
        assertTrue(task(TaskEdit.setTags(base, emptyList())).someday)
        assertTrue(!TaskEdit.setTags(ics("CATEGORIES:x"), emptyList()).contains("CATEGORIES"))
    }

    @Test fun somedayCanBeSwitchedWithoutTouchingOtherTags() {
        val on = TaskEdit.setSomeday(ics("CATEGORIES:home"), true)
        assertTrue(task(on).someday); assertEquals(listOf("home"), task(on).tags)
        val off = TaskEdit.setSomeday(on, false)
        assertTrue(!task(off).someday); assertEquals(listOf("home"), task(off).tags)
    }

    @Test fun completingAPlainTaskWritesTheThreeLinesAndReopeningUndoesThem() {
        val done = TaskEdit.complete(ics("PRIORITY:1", *ALARM), NOW, ATHENS)
        assertTrue(done is Completion.Done)
        val t = task(done.ics)
        assertEquals(TaskStatus.Completed, t.status); assertEquals(100, t.percent); assertEquals(NOW.toEpochMilli(), t.completedAtMs)
        assertTrue(done.ics.contains("TRIGGER:-PT30M") && done.ics.contains("PRIORITY:1"))
        val back = task(TaskEdit.reopen(done.ics, NOW))
        assertEquals(TaskStatus.NeedsAction, back.status); assertNull(back.completedAtMs); assertEquals(0, back.percent)
    }

    @Test fun aWeeklyTaskMovesStartAndDeadlineTogetherAndStaysOpen() {
        val base = ics("DTSTART:20261118T080000Z", "DUE:20261118T100000Z", "RRULE:FREQ=WEEKLY", *ALARM)
        val r = TaskEdit.complete(base, NOW, ATHENS)
        assertTrue(r is Completion.Advanced)
        val t = task(r.ics)
        assertEquals(w(2026, 11, 25, 10, 0), t.start); assertEquals(w(2026, 11, 25, 12, 0), t.due)
        assertEquals(TaskStatus.NeedsAction, t.status); assertNull(t.completedAtMs)
        assertTrue(r.ics.contains("RRULE:FREQ=WEEKLY") && r.ics.contains("TRIGGER:-PT30M"))
    }

    @Test fun dailyEveryTwoDaysAndMonthlyAndByDay() {
        val daily = task(TaskEdit.complete(ics("DTSTART:20261118T080000Z", "RRULE:FREQ=DAILY;INTERVAL=2"), NOW, ATHENS).ics)
        assertEquals(w(2026, 11, 20, 10, 0), daily.start)
        val monthly = task(TaskEdit.complete(ics("DUE:20261115T080000Z", "RRULE:FREQ=MONTHLY"), NOW, ATHENS).ics)
        assertEquals(w(2026, 12, 15, 10, 0), monthly.due)
        // 18 Nov 2026 is a Wednesday: the next of MO,WE is Monday 23 Nov
        val byDay = task(TaskEdit.complete(ics("DTSTART:20261118T080000Z", "RRULE:FREQ=WEEKLY;BYDAY=MO,WE"), NOW, ATHENS).ics)
        assertEquals(w(2026, 11, 23, 10, 0), byDay.start)
    }

    @Test fun aRepeatingTaskWithOnlyADeadlineAndADateOnlyOneBothAdvance() {
        val dateOnly = task(TaskEdit.complete(ics("DUE;VALUE=DATE:20261120", "RRULE:FREQ=WEEKLY"), NOW, ATHENS).ics)
        assertEquals(w(2026, 11, 27), dateOnly.due); assertNull(dateOnly.start)
    }

    @Test fun theSeriesEndsWhenCountOrUntilIsUsedUp() {
        val counted = TaskEdit.complete(ics("DTSTART:20261118T080000Z", "RRULE:FREQ=DAILY;COUNT=3"), NOW, ATHENS)
        assertTrue(counted is Completion.Advanced); assertTrue(counted.ics.contains("COUNT=2"))
        assertTrue(TaskEdit.complete(ics("DTSTART:20261118T080000Z", "RRULE:FREQ=DAILY;COUNT=1"), NOW, ATHENS) is Completion.Done)
        assertTrue(TaskEdit.complete(ics("DTSTART:20261118T080000Z", "RRULE:FREQ=DAILY;UNTIL=20261118T235959Z"), NOW, ATHENS) is Completion.Done)
    }

    @Test fun aRepeatingTaskWithNoDatesIsSimplyCompleted() {
        assertTrue(TaskEdit.complete(ics("RRULE:FREQ=DAILY"), NOW, ATHENS) is Completion.Done)
    }

    @Test fun aNewSubtaskPointsAtItsParent() {
        val sub = TaskEdit.newSubtask("parent-1", "child-1", "Buy milk", NOW)
        val t = task(sub)
        assertEquals("parent-1", t.parentUid); assertEquals("child-1", t.uid); assertEquals("Buy milk", t.title)
    }

    @Test fun descendantsWalkTheWholeSubtreeAndSurviveALoop() {
        fun mk(uid: String, parent: String?, status: TaskStatus = TaskStatus.NeedsAction) = Task(
            uid, 1, "/l/$uid.ics", "e", uid, null, null, null, null, status, 0, Priority.None, emptyList(), false, false, parent, null, emptyList(), null, 0, null, null, false,
        )
        val tasks = listOf(mk("a", null), mk("b", "a"), mk("c", "b"), mk("d", "b", TaskStatus.Completed), mk("x", "y"), mk("y", "x"))
        assertEquals(setOf("b", "c", "d"), TaskViews.descendants(tasks, "a", openOnly = false).map { it.uid }.toSet())
        assertEquals(setOf("b", "c"), TaskViews.descendants(tasks, "a", openOnly = true).map { it.uid }.toSet())
        assertEquals(setOf("y"), TaskViews.descendants(tasks, "x", openOnly = false).map { it.uid }.toSet())
    }
}

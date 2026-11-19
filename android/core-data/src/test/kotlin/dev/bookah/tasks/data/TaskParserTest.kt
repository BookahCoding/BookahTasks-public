package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val ATHENS = ZoneId.of("Europe/Athens")

private fun obj(body: String, href: String = "/l/a.ics") =
    StoredObject(href, 1, "e1", "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\n$body\r\nEND:VCALENDAR\r\n".replace("\n", "\r\n").replace("\r\r", "\r"))

private fun one(body: String) = TaskParser.parse(obj(body), ATHENS).single()

/** Generated tasks only; the shapes are the ones the Phase 0 write test showed the Nextcloud Tasks web app keeps and writes. */
class TaskParserTest {
    @Test fun plainTaskWithEveryStandardField() {
        val t = one(
            """BEGIN:VTODO
UID:p1
DTSTAMP:20261116T100000Z
CREATED:20261116T100000Z
SUMMARY:Pay the bill
DESCRIPTION:Notes line
DTSTART:20261117T100000Z
DUE:20261118T100000Z
PRIORITY:1
CATEGORIES:home,someday
URL:https://example.com/x
STATUS:NEEDS-ACTION
X-APPLE-SORT-ORDER:10
BEGIN:VALARM
ACTION:DISPLAY
DESCRIPTION:Reminder
TRIGGER:-PT30M
END:VALARM
BEGIN:VALARM
ACTION:DISPLAY
DESCRIPTION:Reminder
TRIGGER;VALUE=DATE-TIME:20261118T100000Z
END:VALARM
END:VTODO""",
        )
        assertEquals("Pay the bill", t.title); assertEquals("Notes line", t.notes)
        assertEquals(TaskWhen(LocalDate.of(2026, 11, 17), LocalTime.of(12, 0)), t.start)       // UTC 10:00 is 12:00 in Athens (winter)
        assertEquals(TaskWhen(LocalDate.of(2026, 11, 18), LocalTime.of(12, 0)), t.due)
        assertEquals(Priority.High, t.priority)
        assertEquals(listOf("home"), t.tags); assertTrue(t.someday); assertFalse(t.waiting)
        assertEquals("https://example.com/x", t.url)
        assertEquals(10L, t.sortOrder)
        assertEquals(listOf(Reminder(30, false, null)), t.reminders.filter { it.minutesBefore != null })
        assertEquals(1, t.reminders.count { it.absoluteMs != null })
        assertTrue(t.isOpen); assertFalse(t.repeats)
    }

    @Test fun allDayDatesStayDatesAndFloatingTimesStayWallClock() {
        val a = one("BEGIN:VTODO\nUID:a\nSUMMARY:x\nDTSTART;VALUE=DATE:20261201\nDUE;VALUE=DATE:20261203\nEND:VTODO")
        assertEquals(TaskWhen(LocalDate.of(2026, 12, 1), null), a.start); assertEquals(LocalDate.of(2026, 12, 3), a.due!!.date)
        val f = one("BEGIN:VTODO\nUID:f\nSUMMARY:x\nDUE:20261203T183000\nEND:VTODO")
        assertEquals(TaskWhen(LocalDate.of(2026, 12, 3), LocalTime.of(18, 30)), f.due)
    }

    @Test fun timeZoneIdIsConvertedToThePhonesZone() {
        val t = one(
            """BEGIN:VTODO
UID:z
SUMMARY:x
DUE;TZID=America/New_York:20261203T120000
END:VTODO""",
        )
        // New York is UTC-5 in December, Athens UTC+2: 12:00 there is 19:00 here
        assertEquals(TaskWhen(LocalDate.of(2026, 12, 3), LocalTime.of(19, 0)), t.due)
    }

    @Test fun completedByTheWebAppAndWaitingTag() {
        val t = one(
            """BEGIN:VTODO
UID:c
SUMMARY:Done thing
CATEGORIES:waiting
STATUS:COMPLETED
PERCENT-COMPLETE:100
COMPLETED:20261116T182528Z
END:VTODO""",
        )
        assertEquals(TaskStatus.Completed, t.status); assertTrue(t.isDone); assertTrue(t.waiting)
        assertEquals(100, t.percent); assertEquals(java.time.Instant.parse("2026-11-16T18:25:28Z").toEpochMilli(), t.completedAtMs)
    }

    @Test fun completedWithoutStatusLineCountsAsDone() {
        val t = one("BEGIN:VTODO\nUID:c\nSUMMARY:x\nCOMPLETED:20261116T182528Z\nEND:VTODO")
        assertTrue(t.isDone)
    }

    @Test fun subtaskParentWithAndWithoutRelType() {
        assertEquals("par", one("BEGIN:VTODO\nUID:k\nSUMMARY:x\nRELATED-TO:par\nEND:VTODO").parentUid)
        assertEquals("par", one("BEGIN:VTODO\nUID:k\nSUMMARY:x\nRELATED-TO;RELTYPE=PARENT:par\nEND:VTODO").parentUid)
        assertNull(one("BEGIN:VTODO\nUID:k\nSUMMARY:x\nRELATED-TO;RELTYPE=CHILD:par\nEND:VTODO").parentUid)
    }

    @Test fun repeatingTaskAndItsCompletedCopy() {
        val list = TaskParser.parse(
            obj(
                """BEGIN:VTODO
UID:r
SUMMARY:Weekly
DTSTART:20261129T182115Z
DUE:20261130T182115Z
RRULE:FREQ=WEEKLY;BYDAY=MO
END:VTODO
BEGIN:VTODO
UID:r
RECURRENCE-ID:20261123T182115Z
SUMMARY:Weekly
STATUS:COMPLETED
COMPLETED:20261123T190000Z
END:VTODO""",
            ),
            ATHENS,
        )
        assertEquals(2, list.size)
        val master = list.first { !it.isOccurrenceCopy }; val copy = list.first { it.isOccurrenceCopy }
        assertEquals(RepeatRule("WEEKLY", 1, listOf("MO"), null, null, raw = "FREQ=WEEKLY;BYDAY=MO"), master.repeat)
        assertTrue(master.isOpen); assertFalse(copy.isOpen); assertTrue(copy.isDone)
    }

    @Test fun sortOrderFallsBackToTheCreationTimeLikeTheWebApp() {
        val t = one("BEGIN:VTODO\nUID:s\nSUMMARY:x\nCREATED:20010101T000100Z\nEND:VTODO")
        assertEquals(60L, t.sortOrder)
    }

    @Test fun priorityBands() {
        fun p(v: Int) = one("BEGIN:VTODO\nUID:p\nSUMMARY:x\nPRIORITY:$v\nEND:VTODO").priority
        assertEquals(Priority.High, p(4)); assertEquals(Priority.Medium, p(5)); assertEquals(Priority.Low, p(9)); assertEquals(Priority.None, p(0))
    }

    @Test fun anEventObjectGivesNoTasksAndGarbageGivesNothing() {
        assertTrue(TaskParser.parse(obj("BEGIN:VEVENT\nUID:e\nSUMMARY:x\nDTSTART:20261201T100000Z\nEND:VEVENT"), ATHENS).isEmpty())
        assertTrue(TaskParser.parse(StoredObject("/x.ics", 1, "e", "not a calendar"), ATHENS).isEmpty())
    }
}

package dev.bookah.tasks.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

private val ATHENS: ZoneId = ZoneId.of("Europe/Athens")
private val DAY: LocalDate = LocalDate.of(2026, 11, 18)          // a Wednesday, winter time (UTC+2)

/** The VTIMEZONE block Nextcloud writes for Athens. */
private const val ATHENS_TZ = "BEGIN:VTIMEZONE\r\nTZID:Europe/Athens\r\nBEGIN:DAYLIGHT\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0300\r\nTZNAME:EEST\r\n" +
    "DTSTART:19700329T030000\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\nEND:DAYLIGHT\r\nBEGIN:STANDARD\r\nTZOFFSETFROM:+0300\r\nTZOFFSETTO:+0200\r\n" +
    "TZNAME:EET\r\nDTSTART:19701025T040000\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"

private fun cal(vararg events: String, tz: String = ATHENS_TZ) =
    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Sabre//Sabre VObject 4.5.6//EN\r\nCALSCALE:GREGORIAN\r\n$tz" +
        events.joinToString("") { "BEGIN:VEVENT\r\nDTSTAMP:20261101T000000Z\r\n$it" + "END:VEVENT\r\n" } + "END:VCALENDAR\r\n"

private fun src(uid: String, ics: String) = StoredObject("/cal/ev/$uid.ics", 7, "e", ics)

private fun on(day: LocalDate, vararg objs: StoredObject) = DayEvents.forDay(objs.toList(), day, ATHENS)
private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

class DayEventsTest {
    private var saved: TimeZone? = null
    // the machine's own zone must not matter: run as if the test machine were in New York
    @Before fun zone() { saved = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")) }
    @After fun restore() { TimeZone.setDefault(saved) }

    @Test fun anAllDayEventTodayIsShownAndYesterdaysIsNot() {
        val today = src("a", cal("UID:a\r\nSUMMARY:Name day\r\nDTSTART;VALUE=DATE:20261118\r\nDTEND;VALUE=DATE:20261119\r\n"))
        val yesterday = src("b", cal("UID:b\r\nSUMMARY:Yesterday\r\nDTSTART;VALUE=DATE:20261117\r\nDTEND;VALUE=DATE:20261118\r\n"))
        val noEnd = src("c", cal("UID:c\r\nSUMMARY:No end\r\nDTSTART;VALUE=DATE:20261118\r\n"))
        val noEndYesterday = src("d", cal("UID:d\r\nSUMMARY:No end y\r\nDTSTART;VALUE=DATE:20261117\r\n"))
        val r = on(DAY, today, yesterday, noEnd, noEndYesterday)
        assertEquals(listOf("Name day", "No end"), r.map { it.title }.sorted())
        assertTrue(r.all { it.allDay && it.wholeDay && it.start == null })
    }

    @Test fun aMultiDayAllDayEventCoversEveryDayOfIt() {
        val trip = src("t", cal("UID:t\r\nSUMMARY:Trip\r\nDTSTART;VALUE=DATE:20261116\r\nDTEND;VALUE=DATE:20261120\r\n"))
        assertEquals(1, on(DAY, trip).size)
        assertEquals(1, on(LocalDate.of(2026, 11, 19), trip).size)
        assertEquals(0, on(LocalDate.of(2026, 11, 20), trip).size)
    }

    @Test fun aTimedEventInItsOwnZone() {
        val e = src("m", cal("UID:m\r\nSUMMARY:Meeting\r\nDTSTART;TZID=Europe/Athens:20261118T090000\r\nDTEND;TZID=Europe/Athens:20261118T103000\r\n"))
        val r = on(DAY, e).single()
        assertEquals(t(9), r.start); assertEquals(t(10, 30), r.end); assertFalse(r.allDay); assertFalse(r.startsBefore || r.endsAfter)
    }

    @Test fun utcTimesAreMatchedToTheLocalDay() {
        val early = src("e", cal("UID:e\r\nSUMMARY:Early\r\nDTSTART:20261117T220000Z\r\nDTEND:20261117T230000Z\r\n"))      // 00:00-01:00 here
        val late = src("l", cal("UID:l\r\nSUMMARY:Late\r\nDTSTART:20261118T220000Z\r\nDTEND:20261118T230000Z\r\n"))       // tomorrow here
        assertEquals(listOf("Early"), on(DAY, early, late).map { it.title })
        assertEquals(t(0), on(DAY, early).single().start)
    }

    @Test fun anEventEndingExactlyAtMidnightIsNotOnTheNextDay() {
        val e = src("x", cal("UID:x\r\nSUMMARY:Party\r\nDTSTART;TZID=Europe/Athens:20261117T220000\r\nDTEND;TZID=Europe/Athens:20261118T000000\r\n"))
        assertEquals(0, on(DAY, e).size)
        assertEquals(1, on(LocalDate.of(2026, 11, 17), e).size)
    }

    @Test fun eventsThatCrossMidnightShowTheirPartOfToday() {
        val fromYesterday = src("y", cal("UID:y\r\nSUMMARY:Night shift\r\nDTSTART;TZID=Europe/Athens:20261117T200000\r\nDTEND;TZID=Europe/Athens:20261118T140000\r\n"))
        val intoTomorrow = src("z", cal("UID:z\r\nSUMMARY:Ferry\r\nDTSTART;TZID=Europe/Athens:20261118T200000\r\nDTEND;TZID=Europe/Athens:20261119T080000\r\n"))
        val r = on(DAY, fromYesterday, intoTomorrow).associateBy { it.title }
        assertTrue(r.getValue("Night shift").startsBefore); assertEquals(t(14), r.getValue("Night shift").end)
        assertTrue(r.getValue("Ferry").endsAfter); assertEquals(t(20), r.getValue("Ferry").start)
    }

    @Test fun aWeeklyEventKeepsItsLocalHourAcrossTheClockChange() {
        // started in summer time (UTC+3) on a Wednesday at 09:00; in November (UTC+2) it is still at 09:00
        val e = src("w", cal("UID:w\r\nSUMMARY:Yoga\r\nDTSTART;TZID=Europe/Athens:20260902T090000\r\nDTEND;TZID=Europe/Athens:20260902T100000\r\nRRULE:FREQ=WEEKLY\r\n"))
        assertEquals(t(9), on(DAY, e).single().start)
        assertEquals(0, on(LocalDate.of(2026, 11, 19), e).size)
    }

    @Test fun onTheDayTheClocksGoBackAWeeklyEventIsStillAtItsHour() {
        val e = src("s", cal("UID:s\r\nSUMMARY:Church\r\nDTSTART;TZID=Europe/Athens:20261018T100000\r\nDTEND;TZID=Europe/Athens:20261018T110000\r\nRRULE:FREQ=WEEKLY\r\n"))
        assertEquals(t(10), on(LocalDate.of(2026, 10, 25), e).single().start)
    }

    @Test fun exdateRemovesThatOccurrence() {
        val e = src("x", cal("UID:x\r\nSUMMARY:Standup\r\nDTSTART;TZID=Europe/Athens:20261102T093000\r\nDTEND;TZID=Europe/Athens:20261102T094500\r\nRRULE:FREQ=DAILY\r\nEXDATE;TZID=Europe/Athens:20261118T093000\r\n"))
        assertEquals(0, on(DAY, e).size)
        assertEquals(1, on(LocalDate.of(2026, 11, 19), e).size)
        val utcEx = src("u", cal("UID:u\r\nSUMMARY:Standup\r\nDTSTART;TZID=Europe/Athens:20261102T093000\r\nRRULE:FREQ=DAILY\r\nEXDATE:20261118T073000Z\r\n"))
        assertEquals(0, on(DAY, utcEx).size)
        val allDayEx = src("v", cal("UID:v\r\nSUMMARY:Bins\r\nDTSTART;VALUE=DATE:20261104\r\nRRULE:FREQ=WEEKLY\r\nEXDATE;VALUE=DATE:20261118\r\n"))
        assertEquals(0, on(DAY, allDayEx).size)
        assertEquals(1, on(LocalDate.of(2026, 11, 25), allDayEx).size)
    }

    @Test fun anOccurrenceMovedWithinTodayShowsOnceAtItsNewTime() {
        val e = src("o", cal(
            "UID:o\r\nSUMMARY:Walk\r\nDTSTART;TZID=Europe/Athens:20261101T080000\r\nDTEND;TZID=Europe/Athens:20261101T090000\r\nRRULE:FREQ=DAILY\r\n",
            "UID:o\r\nSUMMARY:Walk (late)\r\nRECURRENCE-ID;TZID=Europe/Athens:20261118T080000\r\nDTSTART;TZID=Europe/Athens:20261118T120000\r\nDTEND;TZID=Europe/Athens:20261118T130000\r\n",
        ))
        val r = on(DAY, e).single()
        assertEquals("Walk (late)", r.title); assertEquals(t(12), r.start)
    }

    @Test fun anOccurrenceMovedIntoTodayAndOneMovedOutOfIt() {
        val e = src("o", cal(
            "UID:o\r\nSUMMARY:Lesson\r\nDTSTART;TZID=Europe/Athens:20261102T170000\r\nDTEND;TZID=Europe/Athens:20261102T180000\r\nRRULE:FREQ=WEEKLY;BYDAY=MO,WE\r\n",
            // Monday's lesson (16 Nov) moved to today 15:00; today's (18 Nov) moved to Friday
            "UID:o\r\nSUMMARY:Lesson\r\nRECURRENCE-ID;TZID=Europe/Athens:20261116T170000\r\nDTSTART;TZID=Europe/Athens:20261118T150000\r\nDTEND;TZID=Europe/Athens:20261118T160000\r\n",
            "UID:o\r\nSUMMARY:Lesson\r\nRECURRENCE-ID;TZID=Europe/Athens:20261118T170000\r\nDTSTART;TZID=Europe/Athens:20261120T170000\r\nDTEND;TZID=Europe/Athens:20261120T180000\r\n",
        ))
        assertEquals(listOf(t(15)), on(DAY, e).map { it.start })
        assertEquals(0, on(LocalDate.of(2026, 11, 16), e).size)
        assertEquals(1, on(LocalDate.of(2026, 11, 20), e).size)
    }

    @Test fun aCancelledOccurrenceIsNotShown() {
        val e = src("c", cal(
            "UID:c\r\nSUMMARY:Class\r\nDTSTART;TZID=Europe/Athens:20261111T180000\r\nRRULE:FREQ=WEEKLY\r\n",
            "UID:c\r\nSUMMARY:Class\r\nRECURRENCE-ID;TZID=Europe/Athens:20261118T180000\r\nDTSTART;TZID=Europe/Athens:20261118T180000\r\nSTATUS:CANCELLED\r\n",
        ))
        assertEquals(0, on(DAY, e).size)
        val whole = src("d", cal("UID:d\r\nSUMMARY:Gone\r\nDTSTART;TZID=Europe/Athens:20261118T180000\r\nSTATUS:CANCELLED\r\n"))
        assertEquals(0, on(DAY, whole).size)
    }

    @Test fun floatingTimesAreThePhonesOwnClock() {
        val e = src("f", cal("UID:f\r\nSUMMARY:Alarm\r\nDTSTART:20261118T070000\r\nDTEND:20261118T073000\r\n", tz = ""))
        assertEquals(t(7), on(DAY, e).single().start)
    }

    @Test fun aZoneWithoutItsBlockIsStillKnown() {
        val e = src("n", cal("UID:n\r\nSUMMARY:Call New York\r\nDTSTART;TZID=America/New_York:20261118T080000\r\nDTEND;TZID=America/New_York:20261118T090000\r\n", tz = ""))
        assertEquals(t(15), on(DAY, e).single().start)
    }

    @Test fun aYearlyBirthdayFromLongAgo() {
        val e = src("b", cal("UID:b\r\nSUMMARY:A friend's birthday\r\nDTSTART;VALUE=DATE:19901118\r\nDTEND;VALUE=DATE:19901119\r\nRRULE:FREQ=YEARLY\r\n"))
        assertEquals(1, on(DAY, e).size)
    }

    @Test fun allDayFirstThenByTime() {
        val r = on(
            DAY,
            src("1", cal("UID:1\r\nSUMMARY:Late\r\nDTSTART;TZID=Europe/Athens:20261118T180000\r\n")),
            src("2", cal("UID:2\r\nSUMMARY:Early\r\nDTSTART;TZID=Europe/Athens:20261118T080000\r\n")),
            src("3", cal("UID:3\r\nSUMMARY:Holiday\r\nDTSTART;VALUE=DATE:20261118\r\n")),
            src("4", cal("UID:4\r\nSUMMARY:Overnight\r\nDTSTART;TZID=Europe/Athens:20261117T230000\r\nDTEND;TZID=Europe/Athens:20261118T020000\r\n")),
        )
        assertEquals(listOf("Holiday", "Overnight", "Early", "Late"), r.map { it.title })
    }

    @Test fun brokenTextAndTasksAreSkippedNeverFatal() {
        val broken = src("x", "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART;TZID=Nowhere/Land:2026111\r\nEND:VCALENDAR")
        val junk = src("y", "not a calendar at all")
        val todo = src("z", "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:z\r\nSUMMARY:T\r\nDUE;VALUE=DATE:20261118\r\nEND:VTODO\r\nEND:VCALENDAR\r\n")
        val ok = src("o", cal("UID:o\r\nSUMMARY:Fine\r\nDTSTART;VALUE=DATE:20261118\r\n"))
        assertEquals(listOf("Fine"), on(DAY, broken, junk, todo, ok).map { it.title })
    }

    @Test fun theSpanCoversEveryDayTheObjectCanTouch() {
        val one = EventSpan.of(cal("UID:a\r\nDTSTART;VALUE=DATE:20261118\r\nDTEND;VALUE=DATE:20261119\r\n"))
        assertTrue(DAY.toEpochDay() in one.first..one.second)
        assertFalse(LocalDate.of(2026, 12, 18).toEpochDay() in one.first..one.second)
        val endless = EventSpan.of(cal("UID:b\r\nDTSTART;VALUE=DATE:19901118\r\nRRULE:FREQ=YEARLY\r\n"))
        assertTrue(LocalDate.of(2090, 1, 1).toEpochDay() <= endless.second)
        val until = EventSpan.of(cal("UID:c\r\nDTSTART;TZID=Europe/Athens:20260101T090000\r\nRRULE:FREQ=DAILY;UNTIL=20260131T070000Z\r\n"))
        assertFalse(DAY.toEpochDay() in until.first..until.second)
        val moved = EventSpan.of(cal(
            "UID:d\r\nDTSTART;TZID=Europe/Athens:20260101T090000\r\nRRULE:FREQ=DAILY;UNTIL=20260103T070000Z\r\n",
            "UID:d\r\nRECURRENCE-ID;TZID=Europe/Athens:20260102T090000\r\nDTSTART;TZID=Europe/Athens:20261118T090000\r\n",
        ))
        assertTrue(DAY.toEpochDay() in moved.first..moved.second)
        val junk = EventSpan.of("junk")
        assertTrue(DAY.toEpochDay() in junk.first..junk.second)          // unreadable: always looked at (and then skipped)
    }
}

package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.DayOfWeek.FRIDAY
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val CR = "\r\n"
private val ATHENS = ZoneId.of("Europe/Athens")
private val TODAY = LocalDate.of(2026, 11, 18)          // a Wednesday
private val NOW = Instant.parse("2026-11-18T10:00:00Z")   // 12:00 in Athens

private fun ics(vararg props: String) =
    (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VTODO", "UID:t1", "DTSTAMP:20261116T182800Z", "SUMMARY:Old") + props + listOf("END:VTODO", "END:VCALENDAR")).joinToString(CR) + CR

private fun task(text: String): Task = TaskParser.parse(StoredObject("/l/t1.ics", 1, "e", text), ATHENS).first { !it.isOccurrenceCopy }
private fun w(y: Int, m: Int, d: Int, h: Int? = null, min: Int = 0) = TaskWhen(LocalDate.of(y, m, d), h?.let { LocalTime.of(it, min) })
private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Instant = LocalDate.of(y, m, d).atTime(h, min).atZone(ATHENS).toInstant()
private val ALARM = arrayOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "TRIGGER:-PT30M", "END:VALARM")

class RepeatTest {
    // ---------------------------------------------------------------- writing and reading the rule

    @Test fun theEditorsChoicesBecomeStandardRules() {
        assertEquals("FREQ=DAILY", RepeatRules.rrule(RepeatSpec(RepeatFreq.Daily), true, ATHENS))
        assertEquals("FREQ=WEEKLY;INTERVAL=2", RepeatRules.rrule(RepeatSpec(RepeatFreq.Weekly, 2), true, ATHENS))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TH", RepeatRules.rrule(RepeatSpec(RepeatFreq.Weekly, weekdays = setOf(THURSDAY, MONDAY)), true, ATHENS))
        assertEquals("FREQ=MONTHLY", RepeatRules.rrule(RepeatSpec(RepeatFreq.Monthly), true, ATHENS))
        assertEquals("FREQ=MONTHLY;BYDAY=3WE", RepeatRules.rrule(RepeatSpec(RepeatFreq.Monthly, monthlyNth = 3, monthlyWeekday = WEDNESDAY), true, ATHENS))
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", RepeatRules.rrule(RepeatSpec(RepeatFreq.Monthly, monthlyNth = -1, monthlyWeekday = FRIDAY), true, ATHENS))
        assertEquals("FREQ=YEARLY", RepeatRules.rrule(RepeatSpec(RepeatFreq.Yearly), true, ATHENS))
        assertEquals("FREQ=DAILY;COUNT=5", RepeatRules.rrule(RepeatSpec(RepeatFreq.Daily, end = RepeatEnd.Count(5)), true, ATHENS))
        // UNTIL is the same kind of value as the dates: a date, or the end of that day in Athens as UTC
        assertEquals("FREQ=DAILY;UNTIL=20261130", RepeatRules.rrule(RepeatSpec(RepeatFreq.Daily, end = RepeatEnd.Until(LocalDate.of(2026, 11, 30))), true, ATHENS))
        assertEquals("FREQ=DAILY;UNTIL=20261130T215959Z", RepeatRules.rrule(RepeatSpec(RepeatFreq.Daily, end = RepeatEnd.Until(LocalDate.of(2026, 11, 30))), false, ATHENS))
    }

    @Test fun rulesReadBackAsTheEditorWroteThem() {
        val specs = listOf(
            RepeatSpec(RepeatFreq.Daily, 3), RepeatSpec(RepeatFreq.Weekly, weekdays = setOf(MONDAY, THURSDAY)),
            RepeatSpec(RepeatFreq.Monthly, monthlyNth = 3, monthlyWeekday = WEDNESDAY), RepeatSpec(RepeatFreq.Yearly, end = RepeatEnd.Count(4)),
            RepeatSpec(RepeatFreq.Weekly, 2, end = RepeatEnd.Until(LocalDate.of(2027, 1, 31)), fromCompletion = true),
        )
        for (s in specs) for (dateOnly in listOf(true, false)) assertEquals(s, RepeatRules.parse(RepeatRules.rrule(s, dateOnly, ATHENS), s.fromCompletion, ATHENS))
    }

    @Test fun aRuleTheEditorCannotShowIsReportedAsCustom() {
        assertNull(RepeatRules.parse("FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU", false, ATHENS))
        assertNull(RepeatRules.parse("FREQ=MONTHLY;BYMONTHDAY=1,15", false, ATHENS))
        assertNull(RepeatRules.parse("FREQ=HOURLY", false, ATHENS))
        assertNull(RepeatRules.parse("FREQ=DAILY;COUNT=3;UNTIL=20261130", false, ATHENS))
        assertNull(RepeatRules.parse("nonsense", false, ATHENS))
        assertEquals(RepeatSpec(RepeatFreq.Weekly), RepeatRules.parse("FREQ=WEEKLY;WKST=MO", false, ATHENS))          // WKST changes nothing for us
        assertEquals(RepeatSpec(RepeatFreq.Monthly), RepeatRules.parse("freq=monthly;interval=1", false, ATHENS))
    }

    @Test fun theNthWeekdayOfADate() {
        assertEquals(3, RepeatRules.nthOf(LocalDate.of(2026, 11, 18)))          // third Wednesday
        assertEquals(1, RepeatRules.nthOf(LocalDate.of(2026, 11, 2)))
        assertEquals(-1, RepeatRules.nthOf(LocalDate.of(2026, 11, 30)))         // the fifth Monday is "the last"
    }

    @Test fun badEveryOrEndValuesAreRefused() {
        assertNull(RepeatSpec(RepeatFreq.Daily).problem(TODAY))
        assertNotNull(RepeatSpec(RepeatFreq.Daily, 0).problem(TODAY))
        assertNotNull(RepeatSpec(RepeatFreq.Daily, end = RepeatEnd.Count(0)).problem(TODAY))
        assertNotNull(RepeatSpec(RepeatFreq.Daily, end = RepeatEnd.Until(TODAY.minusDays(1))).problem(TODAY))
        assertNotNull(RepeatSpec(RepeatFreq.Monthly, monthlyNth = 7, monthlyWeekday = MONDAY).problem(TODAY))
    }

    // ---------------------------------------------------------------- setRepeat

    @Test fun settingARuleChangesOnlyTheRuleLines() {
        val base = ics("DTSTART;VALUE=DATE:20261118", "X-APPLE-SORT-ORDER:7", *ALARM)
        val out = TaskEdit.setRepeat(base, RepeatSpec(RepeatFreq.Weekly, weekdays = setOf(MONDAY, THURSDAY)), ATHENS, TODAY)
        assertTrue(out.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,TH${CR}"))
        assertFalse(out.contains(RepeatRules.FROM_PROPERTY))
        assertEquals(base, out.replace("RRULE:FREQ=WEEKLY;BYDAY=MO,TH$CR", ""))          // nothing else moved
        assertEquals(w(2026, 11, 18), task(out).start)
        val none = TaskEdit.setRepeat(out, null, ATHENS, TODAY)
        assertEquals(base, none)
    }

    @Test fun theSecondKindAddsOneHarmlessLineAndLosesItWhenSwitchedBack() {
        val base = ics("DUE;VALUE=DATE:20261120")
        val after = TaskEdit.setRepeat(base, RepeatSpec(RepeatFreq.Daily, 3, fromCompletion = true), ATHENS, TODAY)
        assertTrue(after.contains("RRULE:FREQ=DAILY;INTERVAL=3") && after.contains("X-BOOKAH-REPEAT-FROM:COMPLETION"))
        assertTrue(task(after).repeat!!.fromCompletion)
        val fixed = TaskEdit.setRepeat(after, RepeatSpec(RepeatFreq.Daily, 3), ATHENS, TODAY)
        assertFalse(fixed.contains("X-BOOKAH-REPEAT-FROM")); assertFalse(task(fixed).repeat!!.fromCompletion)
    }

    @Test fun aRepeatingTaskWithoutDatesGetsAStartDate() {
        // a plain rule: today
        assertEquals(w(2026, 11, 18), task(TaskEdit.setRepeat(ics(), RepeatSpec(RepeatFreq.Daily), ATHENS, TODAY)).start)
        // weekly on Monday and Thursday from a Wednesday: the coming Thursday; on a chosen day: that day
        assertEquals(w(2026, 11, 19), task(TaskEdit.setRepeat(ics(), RepeatSpec(RepeatFreq.Weekly, weekdays = setOf(MONDAY, THURSDAY)), ATHENS, TODAY)).start)
        assertEquals(w(2026, 11, 18), task(TaskEdit.setRepeat(ics(), RepeatSpec(RepeatFreq.Weekly, weekdays = setOf(WEDNESDAY)), ATHENS, TODAY)).start)
        // so the first tick moves it on instead of finishing the series
        val made = TaskEdit.setRepeat(ics(), RepeatSpec(RepeatFreq.Daily), ATHENS, TODAY)
        assertTrue(TaskEdit.complete(made, NOW, ATHENS) is Completion.Advanced)
    }

    @Test fun untilFollowsTheKindOfTheDates() {
        val dated = TaskEdit.setRepeat(ics("DTSTART;VALUE=DATE:20261118"), RepeatSpec(RepeatFreq.Daily, end = RepeatEnd.Until(LocalDate.of(2026, 11, 30))), ATHENS, TODAY)
        assertTrue(dated.contains("UNTIL=20261130;") || dated.contains("UNTIL=20261130$CR"))
        // the start gets a time: UNTIL becomes the end of that day as UTC; and back
        val timed = TaskEdit.setDates(dated, w(2026, 11, 18, 9, 0), null, ATHENS)
        assertTrue(timed.contains("UNTIL=20261130T215959Z"))
        val back = TaskEdit.setDates(timed, w(2026, 11, 18), null, ATHENS)
        assertTrue(back.contains("RRULE:FREQ=DAILY;UNTIL=20261130$CR"))
    }

    // ---------------------------------------------------------------- kind 1: fixed schedule (already built; checked here)

    @Test fun kind1DateOnlyWeeklyOnDaysMovesToTheNextChosenDay() {
        val r = TaskEdit.complete(ics("DTSTART;VALUE=DATE:20261119", "DUE;VALUE=DATE:20261119", "RRULE:FREQ=WEEKLY;BYDAY=MO,TH", *ALARM), NOW, ATHENS)
        assertTrue(r is Completion.Advanced)
        assertEquals(w(2026, 11, 23), task(r.ics).start); assertEquals(w(2026, 11, 23), task(r.ics).due)
        assertTrue(r.ics.contains("TRIGGER:-PT30M"))
    }

    @Test fun kind1KeepsTheLocalHourAcrossTheAutumnAndSpringClockChanges() {
        // 24 Oct 2026 09:00 in Athens is 06:00 UTC (summer time); the clocks go back on 25 Oct
        // ticked on the day itself (not overdue: one step; an overdue tick jumps past today, see Phase3ReviewTest)
        val autumn = task(TaskEdit.complete(ics("DTSTART:20261024T060000Z", "RRULE:FREQ=DAILY"), at(2026, 10, 24, 10, 0), ATHENS).ics)
        assertEquals(w(2026, 10, 25, 9, 0), autumn.start)
        val spring = task(TaskEdit.complete(ics("DTSTART:20270327T070000Z", "RRULE:FREQ=DAILY"), at(2027, 3, 27, 10, 0), ATHENS).ics)
        assertEquals(w(2027, 3, 28, 9, 0), spring.start)
    }

    @Test fun kind1ReadsATimeZoneWrittenByAnotherApp() {
        // 09:00 in Berlin is 10:00 in Athens; a day later at the same local hour
        val t = task(TaskEdit.complete(ics("DTSTART;TZID=Europe/Berlin:20261118T090000", "DUE;TZID=Europe/Berlin:20261118T100000", "RRULE:FREQ=DAILY"), NOW, ATHENS).ics)
        assertEquals(w(2026, 11, 19, 10, 0), t.start); assertEquals(w(2026, 11, 19, 11, 0), t.due)
    }

    @Test fun kind1EndsForGoodWhenUntilOrCountIsUsedUp() {
        assertTrue(TaskEdit.complete(ics("DTSTART;VALUE=DATE:20261118", "RRULE:FREQ=DAILY;UNTIL=20261118"), NOW, ATHENS) is Completion.Done)
        val last = TaskEdit.complete(ics("DTSTART;VALUE=DATE:20261118", "RRULE:FREQ=WEEKLY;UNTIL=20261125"), NOW, ATHENS)
        assertTrue(last is Completion.Advanced); assertEquals(w(2026, 11, 25), task(last.ics).start)
        assertTrue(TaskEdit.complete(last.ics, NOW, ATHENS) is Completion.Done)
        val two = TaskEdit.complete(ics("DTSTART;VALUE=DATE:20261118", "RRULE:FREQ=DAILY;COUNT=2"), NOW, ATHENS)
        assertTrue(two is Completion.Advanced && two.ics.contains("COUNT=1"))
        val done = TaskEdit.complete(two.ics, NOW, ATHENS)
        assertTrue(done is Completion.Done); assertEquals(TaskStatus.Completed, task(done.ics).status)
    }

    // ---------------------------------------------------------------- kind 2: after completion

    private fun fromDone(vararg props: String) = ics(*props, "X-BOOKAH-REPEAT-FROM:COMPLETION")

    @Test fun kind2CountsFromTheTickNotFromTheOldDate() {
        // every 3 days, due 10 Nov, ticked late on 18 Nov: next is 21 Nov (the fixed kind would give 13 Nov)
        val r = TaskEdit.complete(fromDone("DUE;VALUE=DATE:20261110", "RRULE:FREQ=DAILY;INTERVAL=3"), NOW, ATHENS)
        assertTrue(r is Completion.Advanced)
        assertEquals(w(2026, 11, 21), task(r.ics).due)
        assertTrue(r.ics.contains("X-BOOKAH-REPEAT-FROM:COMPLETION") && r.ics.contains("RRULE:FREQ=DAILY;INTERVAL=3"))
        // ticked early (before the date): also from the tick
        assertEquals(w(2026, 11, 21), task(TaskEdit.complete(fromDone("DUE;VALUE=DATE:20261125", "RRULE:FREQ=DAILY;INTERVAL=3"), NOW, ATHENS).ics).due)
    }

    @Test fun kind2KeepsTheTimeOfDayAndTheGapToTheDeadline() {
        // starts 08:00, due 10:00 (Athens), weekly, ticked 18 Nov at 12:00: next start 25 Nov 08:00, due 10:00
        val t = task(TaskEdit.complete(fromDone("DTSTART:20261101T060000Z", "DUE:20261101T080000Z", "RRULE:FREQ=WEEKLY"), NOW, ATHENS).ics)
        assertEquals(w(2026, 11, 25, 8, 0), t.start); assertEquals(w(2026, 11, 25, 10, 0), t.due)
    }

    @Test fun kind2MonthlyAndYearlyUseCalendarArithmetic() {
        val jan31 = Instant.parse("2027-01-31T10:00:00Z")
        assertEquals(w(2027, 2, 28), task(TaskEdit.complete(fromDone("DUE;VALUE=DATE:20270115", "RRULE:FREQ=MONTHLY"), jan31, ATHENS).ics).due)
        assertEquals(w(2027, 11, 18), task(TaskEdit.complete(fromDone("DUE;VALUE=DATE:20260101", "RRULE:FREQ=YEARLY"), NOW, ATHENS).ics).due)
    }

    @Test fun kind2WeeklyOnDaysTakesTheFirstChosenDayAfterTheTick() {
        // Monday and Thursday; dated last Monday 9 Nov; ticked on Tuesday 17 Nov: Thursday 19 Nov
        val tue = at(2026, 11, 17, 20, 0)
        assertEquals(w(2026, 11, 19), task(TaskEdit.complete(fromDone("DTSTART;VALUE=DATE:20261109", "RRULE:FREQ=WEEKLY;BYDAY=MO,TH"), tue, ATHENS).ics).start)
        // ticked on a Thursday morning for a Thursday-evening task: the next Monday, not the same evening
        val thuMorning = at(2026, 11, 19, 8, 0)
        assertEquals(w(2026, 11, 23, 19, 0), task(TaskEdit.complete(fromDone("DTSTART:20261119T170000Z", "RRULE:FREQ=WEEKLY;BYDAY=MO,TH"), thuMorning, ATHENS).ics).start)
    }

    @Test fun kind2EndsForGoodOnCountAndUntil() {
        val c = TaskEdit.complete(fromDone("DUE;VALUE=DATE:20261110", "RRULE:FREQ=DAILY;COUNT=2"), NOW, ATHENS)
        assertTrue(c is Completion.Advanced && c.ics.contains("COUNT=1"))
        assertTrue(TaskEdit.complete(c.ics, NOW, ATHENS) is Completion.Done)
        // next would be 21 Nov, after UNTIL 20 Nov: finished
        assertTrue(TaskEdit.complete(fromDone("DUE;VALUE=DATE:20261110", "RRULE:FREQ=DAILY;INTERVAL=3;UNTIL=20261120"), NOW, ATHENS) is Completion.Done)
        assertTrue(TaskEdit.complete(fromDone("DUE:20261110T080000Z", "RRULE:FREQ=DAILY;INTERVAL=3;UNTIL=20261120T215959Z"), NOW, ATHENS) is Completion.Done)
        // on UNTIL itself: allowed
        assertTrue(TaskEdit.complete(fromDone("DUE;VALUE=DATE:20261110", "RRULE:FREQ=DAILY;INTERVAL=3;UNTIL=20261121"), NOW, ATHENS) is Completion.Advanced)
    }

    @Test fun aDenseOldRuleCannotHangTheTick() {
        // every minute since 2000 written by another app, ticked now: the search is bounded, the task is still answered
        val r = TaskEdit.complete(ics("DTSTART:20000101T000000Z", "RRULE:FREQ=MINUTELY"), NOW, ATHENS)
        assertNotNull(r)
    }
}

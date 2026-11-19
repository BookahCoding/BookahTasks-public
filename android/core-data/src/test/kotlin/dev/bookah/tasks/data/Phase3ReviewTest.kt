package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val CR = "\r\n"
private val ATHENS = ZoneId.of("Europe/Athens")
private val NEW_YORK = ZoneId.of("America/New_York")
private val NOW = Instant.parse("2026-11-18T10:00:00Z")   // Wednesday 18 Nov, 12:00 in Athens

private fun ics(vararg props: String) =
    (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VTODO", "UID:t1", "DTSTAMP:20261116T182800Z", "SUMMARY:Old") + props + listOf("END:VTODO", "END:VCALENDAR")).joinToString(CR) + CR

private fun task(text: String, zone: ZoneId = ATHENS): Task = TaskParser.parse(StoredObject("/l/t1.ics", 1, "e", text), zone).first { !it.isOccurrenceCopy }
private fun w(y: Int, m: Int, d: Int, h: Int? = null, min: Int = 0) = TaskWhen(LocalDate.of(y, m, d), h?.let { LocalTime.of(it, min) })
private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = LocalDate.of(y, m, d).atTime(h, min).atZone(ATHENS).toInstant().toEpochMilli()

/** Phase 3 review fix pass (19 Nov 2026 brief): the owner's ruling on overdue repeating ticks and the independent review's findings 1 to 6. */
class Phase3ReviewTest {
    // ---------------------------------------------------------------- the owner's ruling: an overdue fixed-schedule task jumps past today

    @Test fun anOverdueDailyTaskJumpsToTheFirstDateAfterToday() {
        // daily, 30 days late (18 Oct): the tick moves it to tomorrow, not to 19 Oct
        val r = TaskEdit.complete(ics("DTSTART;VALUE=DATE:20261019", "DUE;VALUE=DATE:20261019", "RRULE:FREQ=DAILY"), NOW, ATHENS)
        assertTrue(r is Completion.Advanced)
        assertEquals(w(2026, 11, 19), task(r.ics).start); assertEquals(w(2026, 11, 19), task(r.ics).due)
    }

    @Test fun notOverdueStillMovesOneStep() {
        // due today: one step (tomorrow); due in the future: the next one after it
        assertEquals(w(2026, 11, 19), task(TaskEdit.complete(ics("DUE;VALUE=DATE:20261118", "RRULE:FREQ=DAILY"), NOW, ATHENS).ics).due)
        assertEquals(w(2026, 11, 21), task(TaskEdit.complete(ics("DUE;VALUE=DATE:20261120", "RRULE:FREQ=DAILY"), NOW, ATHENS).ics).due)
    }

    @Test fun overdueWeeklyOnDaysMonthlyAndTimedKeepTheirShape() {
        // Mon/Thu, last dated Mon 2 Nov: after Wednesday 18 Nov comes Thursday 19 Nov
        assertEquals(w(2026, 11, 19), task(TaskEdit.complete(ics("DTSTART;VALUE=DATE:20261102", "RRULE:FREQ=WEEKLY;BYDAY=MO,TH"), NOW, ATHENS).ics).start)
        // monthly on the 5th, last dated 5 Sep: 5 Dec
        assertEquals(w(2026, 12, 5), task(TaskEdit.complete(ics("DUE;VALUE=DATE:20260905", "RRULE:FREQ=MONTHLY"), NOW, ATHENS).ics).due)
        // timed with a gap: start 08:00, due 10:00 (Athens), daily, a week late: tomorrow 08:00 and 10:00
        val t = task(TaskEdit.complete(ics("DTSTART:20261111T060000Z", "DUE:20261111T080000Z", "RRULE:FREQ=DAILY"), NOW, ATHENS).ics)
        assertEquals(w(2026, 11, 19, 8, 0), t.start); assertEquals(w(2026, 11, 19, 10, 0), t.due)
        // a start-only task whose day has passed counts as overdue too
        assertEquals(w(2026, 11, 19, 8, 0), task(TaskEdit.complete(ics("DTSTART:20261111T060000Z", "RRULE:FREQ=DAILY"), NOW, ATHENS).ics).start)
    }

    @Test fun countGoesDownByEveryOccurrenceSkippedAndCanRunOut() {
        // daily from 10 Nov, COUNT=20: the jump to 19 Nov passes 9 occurrences, 11 are left
        val r = TaskEdit.complete(ics("DTSTART;VALUE=DATE:20261110", "RRULE:FREQ=DAILY;COUNT=20"), NOW, ATHENS)
        assertTrue(r is Completion.Advanced); assertTrue(r.ics.contains("COUNT=11"))
        // COUNT=5 from 10 Nov ends on 14 Nov: the skip runs out, the task completes for good
        val done = TaskEdit.complete(ics("DTSTART;VALUE=DATE:20261110", "RRULE:FREQ=DAILY;COUNT=5"), NOW, ATHENS)
        assertTrue(done is Completion.Done); assertEquals(TaskStatus.Completed, task(done.ics).status)
        // UNTIL 15 Nov passed during the skip: completes for good
        assertTrue(TaskEdit.complete(ics("DTSTART;VALUE=DATE:20261110", "RRULE:FREQ=DAILY;UNTIL=20261115"), NOW, ATHENS) is Completion.Done)
    }

    @Test fun theSkipKeepsTheLocalHourOnAClockChangeDay() {
        // 09:00 daily from 20 Oct (summer time), ticked on 25 Oct (the clocks go back that night): 26 Oct 09:00
        val oct25 = LocalDate.of(2026, 10, 25).atTime(12, 0).atZone(ATHENS).toInstant()
        assertEquals(w(2026, 10, 26, 9, 0), task(TaskEdit.complete(ics("DTSTART:20261020T060000Z", "RRULE:FREQ=DAILY"), oct25, ATHENS).ics).start)
    }

    @Test fun kind2IsNotChangedByTheRuling() {
        val r = TaskEdit.complete(ics("DUE;VALUE=DATE:20261019", "RRULE:FREQ=DAILY;INTERVAL=3", "X-BOOKAH-REPEAT-FROM:COMPLETION"), NOW, ATHENS)
        assertEquals(w(2026, 11, 21), task(r.ics).due)
    }

    // ---------------------------------------------------------------- 1. UNTIL follows the STORED kind; an end before the start is refused

    @Test fun untilFollowsTheStoredKindNotTheReadBackOne() {
        // a date-only start beside a timed deadline is STORED as 00:00 (timed), though it reads back as a date: UNTIL must be a UTC time
        val base = TaskEdit.setDates(ics(), w(2026, 11, 18), w(2026, 11, 18, 17, 0), ATHENS)
        val out = TaskEdit.setRepeat(base, RepeatSpec(RepeatFreq.Daily, end = RepeatEnd.Until(LocalDate.of(2026, 11, 30))), ATHENS, LocalDate.of(2026, 11, 18))
        assertTrue(out.contains("UNTIL=20261130T215959Z"))
        // the last allowed day still advances; the day after does not
        val last = TaskEdit.complete(TaskEdit.setDates(out, w(2026, 11, 29), w(2026, 11, 29, 17, 0), ATHENS), LocalDate.of(2026, 11, 29).atTime(18, 0).atZone(ATHENS).toInstant(), ATHENS)
        assertTrue(last is Completion.Advanced); assertEquals(w(2026, 11, 30), task(last.ics).start)
        assertTrue(TaskEdit.complete(last.ics, LocalDate.of(2026, 11, 30).atTime(18, 0).atZone(ATHENS).toInstant(), ATHENS) is Completion.Done)
    }

    @Test fun anEndBeforeTheStartIsRefused() {
        try {
            TaskEdit.setRepeat(ics("DTSTART;VALUE=DATE:20261118"), RepeatSpec(RepeatFreq.Daily, end = RepeatEnd.Until(LocalDate.of(2026, 11, 10))), ATHENS, LocalDate.of(2026, 11, 18))
            fail("an UNTIL before the start was written")
        } catch (e: IllegalArgumentException) { /* refused */ }
    }

    @Test fun untilInAZoneBehindUtc() {
        // 09:00 in New York daily; UNTIL 29 Nov 23:59:59 New York time (04:59:59 UTC on the 30th)
        val r = TaskEdit.complete(ics("DTSTART:20261129T140000Z", "RRULE:FREQ=DAILY;UNTIL=20261130T045959Z"), Instant.parse("2026-11-29T15:00:00Z"), NEW_YORK)
        assertTrue(r is Completion.Done)
        val ok = TaskEdit.complete(ics("DTSTART:20261129T140000Z", "RRULE:FREQ=DAILY;UNTIL=20261130T235959Z"), Instant.parse("2026-11-29T15:00:00Z"), NEW_YORK)
        assertTrue(ok is Completion.Advanced); assertEquals(w(2026, 11, 30, 9, 0), task(ok.ics, NEW_YORK).start)
    }

    // ---------------------------------------------------------------- 2. set-moment reminders move with a repeat; the last removed writes NONE

    @Test fun aSetMomentReminderMovesWithTheRepeatSoTheTaskNeverGoesSilent() {
        val base = TaskEdit.setReminders(ics("DTSTART;VALUE=DATE:20261118", "RRULE:FREQ=DAILY"), listOf(ReminderChoice.At(ms(2026, 11, 18, 17, 0))), ATHENS)
        val next = TaskEdit.complete(base, NOW, ATHENS).ics
        assertEquals(listOf<ReminderChoice>(ReminderChoice.At(ms(2026, 11, 19, 17, 0))), ReminderSet.of(task(next), ATHENS, nowMs = ms(2026, 11, 18, 12, 0)).own)
        assertEquals(listOf(ms(2026, 11, 19, 17, 0)), ReminderPlanner.plan(listOf(task(next)), ms(2026, 11, 18, 12, 0), ATHENS).map { it.triggerAtMs })
    }

    @Test fun whenTheDatesTakeAwayOurLastReminderTheDefaultsDoNotComeBack() {
        val base = TaskEdit.setReminders(ics("DTSTART:20261119T080000Z", "DUE:20261119T150000Z"), listOf(ReminderChoice.Before(true, 60)), ATHENS)
        val noDue = TaskEdit.setDates(base, w(2026, 11, 19, 10, 0), null, ATHENS)
        assertTrue(noDue.contains("X-BOOKAH-REMINDERS:NONE"))
        assertTrue(ReminderPlanner.plan(listOf(task(noDue)), ms(2026, 11, 18, 12, 0), ATHENS).isEmpty())
    }

    // ---------------------------------------------------------------- 3. the screen shows what rings, also with a deadline less than an hour away

    @Test fun aDeadlineLessThanAnHourAwayShowsAndKeepsTheAtDeadlineReminder() {
        val now = ms(2026, 11, 18, 16, 30)
        val t = task(ics("DUE:20261118T150000Z"))                                   // 17:00, half an hour away
        val list = ReminderSet.of(t, ATHENS, nowMs = now)
        assertEquals(listOf<ReminderChoice>(ReminderChoice.Before(true, 0)), list.own)
        assertEquals(list.items.mapNotNull { it.atMs }, ReminderPlanner.plan(listOf(t), now, ATHENS).map { it.triggerAtMs })
        // editing from that list keeps the deadline reminder
        val out = TaskEdit.setReminders(ics("DUE:20261118T150000Z"), list.own + ReminderChoice.Before(true, 10), ATHENS)
        assertEquals(listOf(ms(2026, 11, 18, 16, 50), ms(2026, 11, 18, 17, 0)), ReminderPlanner.plan(listOf(task(out)), now, ATHENS).map { it.triggerAtMs })
    }

    // ---------------------------------------------------------------- 4. a block with our marker but a code we do not know is kept untouched

    @Test fun anUnknownCodeIsTreatedAsForeignEverywhere() {
        val odd = listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Future", "TRIGGER:-PT5M", "X-BOOKAH-REMINDER:SOMETHING-NEW", "END:VALARM")
        val base = ics("DTSTART:20261119T080000Z", *odd.toTypedArray())
        val oddText = odd.joinToString(CR) + CR
        assertTrue(TaskEdit.setReminders(base, listOf(ReminderChoice.Before(false, 60)), ATHENS).contains(oddText))
        assertTrue(TaskEdit.setReminders(base, emptyList(), ATHENS).contains(oddText))
        assertTrue(TaskEdit.useDefaultReminders(TaskEdit.setReminders(base, listOf(ReminderChoice.Before(false, 60)), ATHENS)).contains(oddText))
        assertEquals(1, ReminderSet.of(task(base), ATHENS).items.count { it.source == ReminderSource.Foreign })
    }

    // ---------------------------------------------------------------- 5. a repeating task can never lose both dates

    @Test fun clearingBothDatesOfARepeatingTaskIsRefused() {
        try {
            TaskEdit.setDates(ics("DTSTART;VALUE=DATE:20261118", "RRULE:FREQ=DAILY"), null, null, ATHENS)
            fail("a rule was left without a date")
        } catch (e: IllegalArgumentException) { /* refused */ }
        // a plain task may lose them
        assertNull(task(TaskEdit.setDates(ics("DTSTART;VALUE=DATE:20261118"), null, null, ATHENS)).start)
    }

    // ---------------------------------------------------------------- 6. an impossible or runaway rule never completes another app's series

    @Test(timeout = 10_000) fun anImpossibleRuleLeavesTheTaskUnchanged() {
        val text = ics("DTSTART;VALUE=DATE:20260130", "RRULE:FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=30")
        val r = TaskEdit.complete(text, NOW, ATHENS)
        assertTrue(r is Completion.Unchanged); assertEquals(text, r.ics)
    }

    @Test(timeout = 10_000) fun hardCalendarCasesAdvanceCorrectly() {
        val jan31 = LocalDate.of(2027, 1, 31).atTime(8, 0).atZone(ATHENS).toInstant()
        // monthly on the 31st (fixed schedule): February and April are skipped, as the standard says
        assertEquals(w(2027, 3, 31), task(TaskEdit.complete(ics("DUE;VALUE=DATE:20270131", "RRULE:FREQ=MONTHLY"), jan31, ATHENS).ics).due)
        // 29 February every year: the next leap year
        val leap = LocalDate.of(2028, 2, 29).atTime(8, 0).atZone(ATHENS).toInstant()
        assertEquals(w(2032, 2, 29), task(TaskEdit.complete(ics("DUE;VALUE=DATE:20280229", "RRULE:FREQ=YEARLY"), leap, ATHENS).ics).due)
        // the last Friday of the month: 27 Nov, then 25 Dec
        val nov27 = LocalDate.of(2026, 11, 27).atTime(8, 0).atZone(ATHENS).toInstant()
        assertEquals(w(2026, 12, 25), task(TaskEdit.complete(ics("DUE;VALUE=DATE:20261127", "RRULE:FREQ=MONTHLY;BYDAY=-1FR"), nov27, ATHENS).ics).due)
    }

    @Test(timeout = 10_000) fun aRunawaySearchLeavesTheTaskUnchanged() {
        // every minute since 2000, a far overdue task: the jump would need millions of steps; the search gives up, nothing changes
        val text = ics("DTSTART:20000101T000000Z", "RRULE:FREQ=MINUTELY")
        val r = TaskEdit.complete(text, NOW, ATHENS)
        assertTrue(r is Completion.Unchanged); assertEquals(text, r.ics)
    }

    @Test fun theMonthlyHintIsShownFromThe29th() {
        assertTrue(RepeatRules.monthlyDaySkips(LocalDate.of(2026, 1, 29)))
        assertTrue(RepeatRules.monthlyDaySkips(LocalDate.of(2026, 1, 31)))
        assertFalse(RepeatRules.monthlyDaySkips(LocalDate.of(2026, 1, 28)))
    }
}

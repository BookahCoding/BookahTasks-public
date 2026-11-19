package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val CR = "\r\n"
private val ATHENS = ZoneId.of("Europe/Athens")
private val NOW_MS = LocalDate.of(2026, 11, 18).atTime(8, 0).atZone(ATHENS).toInstant().toEpochMilli()

private fun ics(vararg props: String) =
    (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VTODO", "UID:t1", "DTSTAMP:20261116T182800Z", "SUMMARY:Old") + props + listOf("END:VTODO", "END:VCALENDAR")).joinToString(CR) + CR

private fun task(text: String): Task = TaskParser.parse(StoredObject("/l/t1.ics", 1, "e", text), ATHENS).first { !it.isOccurrenceCopy }
private fun at(d: Int, h: Int, m: Int = 0) = LocalDate.of(2026, 11, d).atTime(h, m).atZone(ATHENS).toInstant().toEpochMilli()
private fun w(d: Int, h: Int? = null, m: Int = 0) = TaskWhen(LocalDate.of(2026, 11, d), h?.let { LocalTime.of(it, m) })
private fun plan(text: String) = ReminderPlanner.plan(listOf(task(text)), NOW_MS, ATHENS)
private fun ringsAt(text: String) = plan(text).map { it.triggerAtMs }

private val FOREIGN = listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:From the web app", "TRIGGER:-PT30M", "X-WEB-THING:1", "END:VALARM")
private val FOREIGN_TEXT = FOREIGN.joinToString(CR) + CR
private val START_AT = ReminderChoice.Before(false, 0)
private val DUE_AT = ReminderChoice.Before(true, 0)
private fun before(toDue: Boolean, m: Int) = ReminderChoice.Before(toDue, m)

class ReminderSetTest {
    // ---------------------------------------------------------------- what rings

    @Test fun withoutOurOwnListTheDefaultsRingAndForeignAlarmsRingToo() {
        // timed start 10:00 and timed deadline 17:00 on 19 Nov, plus a web-app alarm 30 minutes before the start
        val t = ics("DTSTART:20261119T080000Z", "DUE:20261119T150000Z", *FOREIGN.toTypedArray())
        assertEquals(listOf(at(19, 9, 30), at(19, 10), at(19, 16)), ringsAt(t))
        val list = ReminderSet.of(task(t), ATHENS)
        assertTrue(list.usesDefaults)
        assertEquals(listOf(START_AT, before(true, 60)), list.own)
        assertEquals(1, list.items.count { it.source == ReminderSource.Foreign })
    }

    @Test fun ourOwnListReplacesTheDefaults() {
        val base = ics("DTSTART:20261119T080000Z", "DUE:20261119T150000Z", *FOREIGN.toTypedArray())
        val mine = TaskEdit.setReminders(base, listOf(before(true, 10)), ATHENS)
        assertEquals(listOf(at(19, 9, 30), at(19, 16, 50)), ringsAt(mine))           // the web-app alarm and ours; no defaults
        assertFalse(ReminderSet.of(task(mine), ATHENS).usesDefaults)
        assertEquals(listOf(before(true, 10)), ReminderSet.of(task(mine), ATHENS).own)
    }

    @Test fun removingEveryReminderSilencesTheDefaultsAndUseDefaultsBringsThemBack() {
        val base = ics("DTSTART:20261119T080000Z", *FOREIGN.toTypedArray())
        val none = TaskEdit.setReminders(base, emptyList(), ATHENS)
        assertTrue(none.contains("X-BOOKAH-REMINDERS:NONE"))
        assertEquals(listOf(at(19, 9, 30)), ringsAt(none))                           // only the other app's alarm
        val back = TaskEdit.useDefaultReminders(TaskEdit.setReminders(none, listOf(before(false, 60)), ATHENS))
        assertEquals(base, back)                                                     // exactly as before: our lines gone, theirs kept
        assertEquals(listOf(at(19, 9, 30), at(19, 10)), ringsAt(back))
    }

    @Test fun manyRemindersRingOncePerMinuteAndStayBounded() {
        val t = TaskEdit.setReminders(ics("DTSTART:20261119T080000Z"), listOf(START_AT, before(false, 10), before(false, 60), before(false, 1440), START_AT), ATHENS)
        assertEquals(listOf(at(18, 10), at(19, 9), at(19, 9, 50), at(19, 10)), ringsAt(t))
        val many = (1..60).map { i -> TaskEdit.setReminders(ics("DTSTART:20261119T080000Z").replace("UID:t1", "UID:t$i"), listOf(START_AT, before(false, 10)), ATHENS) }
        val tasks = many.mapIndexed { i, s -> TaskParser.parse(StoredObject("/l/t$i.ics", 1, "e", s), ATHENS).first() }
        assertEquals(ReminderPlanner.MAX_SCHEDULED, ReminderPlanner.plan(tasks, NOW_MS, ATHENS).size)
    }

    // ---------------------------------------------------------------- what is written

    @Test fun triggersAreStandardSoOtherAppsRingAtTheSameMoment() {
        val timed = TaskEdit.setReminders(ics("DTSTART:20261119T080000Z", "DUE:20261119T150000Z"), listOf(START_AT, before(true, 60), before(false, 1440)), ATHENS)
        assertTrue(timed.contains("TRIGGER;RELATED=START:PT0S$CR"))
        assertTrue(timed.contains("TRIGGER;RELATED=END:-PT1H$CR"))
        assertTrue(timed.contains("TRIGGER;RELATED=START:-PT24H$CR"))
        assertTrue(timed.contains("X-BOOKAH-REMINDER:START-0$CR") && timed.contains("X-BOOKAH-REMINDER:DUE-60$CR"))
        assertTrue(timed.contains("ACTION:DISPLAY$CR"))
        // a date-only value counts from 00:00 in the standard: "at" = 09:00 that day, "1 day before" = 09:00 the day before
        val dated = TaskEdit.setReminders(ics("DTSTART;VALUE=DATE:20261119", "DUE;VALUE=DATE:20261120"), listOf(START_AT, before(true, 1440), before(true, 10)), ATHENS)
        assertTrue(dated.contains("TRIGGER;RELATED=START:PT9H$CR"))
        assertTrue(dated.contains("TRIGGER;RELATED=END:-PT15H$CR"))
        assertTrue(dated.contains("TRIGGER;RELATED=END:PT8H50M$CR"))
        // "1 day before the deadline" (19 Nov 09:00) is the same minute as "at start": it rings once
        assertEquals(listOf(at(19, 9), at(20, 8, 50)), ringsAt(dated))
        // a set moment
        val fixed = TaskEdit.setReminders(ics(), listOf(ReminderChoice.At(at(20, 17))), ATHENS)
        assertTrue(fixed.contains("TRIGGER;VALUE=DATE-TIME:20261120T150000Z$CR"))
        assertEquals(listOf(at(20, 17)), ringsAt(fixed))
        assertEquals(listOf<ReminderChoice>(ReminderChoice.At(at(20, 17))), ReminderSet.of(task(fixed), ATHENS).own)
    }

    @Test fun aDeadlineStoredAs2359CountsFromItsOwnStoredTime() {
        // a timed start with a date-only deadline is stored as 23:59; "at the deadline" still means 09:00 that day, for us and for other apps
        val base = TaskEdit.setDates(ics(), w(19, 10), w(20), ATHENS)
        val out = TaskEdit.setReminders(base, listOf(DUE_AT), ATHENS)
        assertTrue(out.contains("TRIGGER;RELATED=END:-PT14H59M$CR"))
        assertEquals(listOf(at(20, 9)), ringsAt(out))
    }

    @Test fun editingKeepsForeignAlarmsAndOurUntouchedBlocksByteForByte() {
        val ackOurs = listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "TRIGGER;RELATED=START:PT0S", "X-BOOKAH-REMINDER:START-0", "ACKNOWLEDGED:20261117T080000Z", "END:VALARM")
        val base = ics("DTSTART:20261119T080000Z", *FOREIGN.toTypedArray(), "X-APPLE-SORT-ORDER:5", *ackOurs.toTypedArray())
        val more = TaskEdit.setReminders(base, ReminderSet.of(task(base), ATHENS).own + before(false, 60), ATHENS)
        assertTrue(more.contains(FOREIGN_TEXT))
        assertTrue(more.contains(ackOurs.joinToString(CR) + CR))
        assertTrue(more.contains("X-APPLE-SORT-ORDER:5$CR"))
        assertEquals(2, ReminderSet.of(task(more), ATHENS).own.size)
        // the same list again changes nothing at all
        assertEquals(more, TaskEdit.setReminders(more, ReminderSet.of(task(more), ATHENS).own, ATHENS))
        // removing ours leaves theirs
        val less = TaskEdit.setReminders(more, listOf(before(false, 60)), ATHENS)
        assertTrue(less.contains(FOREIGN_TEXT)); assertFalse(less.contains("ACKNOWLEDGED"))
    }

    @Test fun editingFromTheDefaultsTurnsThemIntoOurOwnList() {
        val base = ics("DTSTART:20261119T080000Z", "DUE:20261119T150000Z")
        val list = ReminderSet.of(task(base), ATHENS)
        val out = TaskEdit.setReminders(base, list.own + before(true, 10), ATHENS)
        assertEquals(listOf(START_AT, before(true, 60), before(true, 10)), ReminderSet.of(task(out), ATHENS).own)
        assertEquals(listOf(at(19, 10), at(19, 16), at(19, 16, 50)), ringsAt(out))
    }

    @Test fun aChoiceWithoutItsDateIsNotWritten() {
        val out = TaskEdit.setReminders(ics("DTSTART:20261119T080000Z"), listOf(START_AT, DUE_AT), ATHENS)
        assertEquals(listOf(START_AT), ReminderSet.of(task(out), ATHENS).own)
    }

    // ---------------------------------------------------------------- dates change

    @Test fun ourRemindersFollowTheDates() {
        val dated = TaskEdit.setReminders(ics("DTSTART;VALUE=DATE:20261119", "DUE;VALUE=DATE:20261120", *FOREIGN.toTypedArray()), listOf(START_AT, before(true, 60)), ATHENS)
        // the start gets a time: "at start" rings at that time, not at 14:00 + 9 hours
        val timed = TaskEdit.setDates(dated, w(19, 14), w(20, 18), ATHENS)
        assertTrue(timed.contains("TRIGGER;RELATED=START:PT0S$CR") && timed.contains("TRIGGER;RELATED=END:-PT1H$CR"))
        assertEquals(listOf(at(19, 13, 30), at(19, 14), at(20, 17)), ringsAt(timed))
        assertTrue(timed.contains(FOREIGN_TEXT))
        // the deadline is removed: our deadline reminder goes (an alarm on a missing date is invalid), the rest stays
        val noDue = TaskEdit.setDates(timed, w(19, 14), null, ATHENS)
        assertEquals(listOf(START_AT), ReminderSet.of(task(noDue), ATHENS).own)
        assertTrue(noDue.contains(FOREIGN_TEXT))
        // and back to a date: 09:00 again
        val back = TaskEdit.setDates(noDue, w(19), null, ATHENS)
        assertTrue(back.contains("TRIGGER;RELATED=START:PT9H$CR"))
    }

    @Test fun aForeignAlarmOnADateOnlyTaskStillCountsFromNine() {
        // the seed's "Take out the bins": date-only, -PT10M from another app: 08:50 as since Phase 1, plus the 09:00 default
        val t = ics("DTSTART;VALUE=DATE:20261119", "DUE;VALUE=DATE:20261119", "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:r", "TRIGGER:-PT10M", "END:VALARM")
        assertEquals(listOf(at(19, 8, 50), at(19, 9)), ringsAt(t))
    }

    @Test fun aRepeatingTickKeepsOurReminders() {
        val t = TaskEdit.setReminders(ics("DTSTART;VALUE=DATE:20261118", "RRULE:FREQ=DAILY"), listOf(START_AT), ATHENS)
        val next = TaskEdit.complete(t, Instant.ofEpochMilli(NOW_MS), ATHENS).ics
        assertEquals(listOf(START_AT), ReminderSet.of(task(next), ATHENS).own)
        assertEquals(listOf(at(19, 9)), ringsAt(next))
    }
}

package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val ATHENS = ZoneId.of("Europe/Athens")
private val TODAY = LocalDate.of(2026, 11, 18)
private fun w(d: Int, h: Int? = null, m: Int = 0) = TaskWhen(LocalDate.of(2026, 11, d), h?.let { LocalTime.of(it, m) })
private fun t(uid: String, start: TaskWhen? = null, due: TaskWhen? = null, someday: Boolean = false, repeat: RepeatRule? = null) = Task(
    uid, 1, "/l/$uid.ics", "e", uid, null, start, due, null, TaskStatus.NeedsAction, 0, Priority.None, emptyList(), someday, false, null, repeat,
    emptyList(), null, 0, null, null, false,
)

/** Phase 3 item 5: Today, This evening, Upcoming and Someday follow plan section 4.1 (start = "when", due = "deadline"). */
class TimeViewsTest {
    private fun where(task: Task, evening: Int = 18): String {
        val tv = TaskViews.today(listOf(task), TODAY, evening)
        val places = ArrayList<String>()
        if (task in tv.overdue) places += "overdue"
        if (task in tv.today) places += "today"
        if (task in tv.evening) places += "evening"
        if (TaskViews.upcoming(listOf(task), TODAY).any { task in it.second }) places += "upcoming"
        if (task in TaskViews.someday(listOf(task))) places += "someday"
        if (task in TaskViews.inbox(listOf(task), null)) places += "inbox"
        return places.joinToString(",")
    }

    @Test fun aTaskSleepsInUpcomingUntilItsStartThenMovesToToday() {
        assertEquals("upcoming", where(t("a", start = w(20))))
        assertEquals("today", where(t("a", start = w(18))))
        assertEquals("today", where(t("a", start = w(10))))                       // started earlier, no deadline: still on Today
        assertEquals("upcoming", where(t("a", due = w(25))))                     // no start: waits for its deadline
        assertEquals("upcoming", where(t("a", start = w(20), due = w(25))))
    }

    // gap found in Phase 3: a future start with a deadline today (possible from another app) was in Today AND in Upcoming
    @Test fun aDeadlineTodayWinsOverAFutureStart() {
        assertEquals("today", where(t("a", start = w(20), due = w(18))))
        assertEquals("overdue", where(t("a", start = w(20), due = w(15))))
    }

    @Test fun theEveningIsDecidedByTodaysTimeAndTheEveningHour() {
        assertEquals("evening", where(t("a", start = w(18, 18, 0))))
        assertEquals("today", where(t("a", start = w(18, 17, 59))))
        assertEquals("evening", where(t("a", due = w(18, 19, 30))))
        assertEquals("evening", where(t("a", start = w(10), due = w(18, 20, 0))))   // started earlier: the deadline's time counts
        assertEquals("today", where(t("a", start = w(18, 19, 0)), evening = 20))  // the setting moves it
        assertEquals("today", where(t("a", start = w(18))))                       // no time: the day part
    }

    @Test fun overdueIsOnlyOverdue() {
        assertEquals("overdue", where(t("a", due = w(17))))
        assertEquals("overdue", where(t("a", start = w(10), due = w(17, 9, 0))))
    }

    @Test fun somedayIsKeptOutOfEveryOtherView() {
        assertEquals("someday", where(t("a", someday = true)))
        assertEquals("someday", where(t("a", start = w(18), someday = true)))
        assertEquals("someday", where(t("a", due = w(10), someday = true)))         // not even overdue
        assertEquals("someday", where(t("a", start = w(25), someday = true)))
        assertEquals("inbox", where(t("a")))
    }

    @Test fun aTickedRepeatingTaskLeavesTodayForItsNextDate() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:r\r\nSUMMARY:Bins\r\nDTSTART;VALUE=DATE:20261118\r\nRRULE:FREQ=WEEKLY;BYDAY=MO,TH\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        val before = TaskParser.parse(StoredObject("/l/r.ics", 1, "e", ics), ATHENS).first()
        assertEquals("today", where(before))
        val after = TaskParser.parse(StoredObject("/l/r.ics", 1, "e", TaskEdit.complete(ics, Instant.parse("2026-11-18T10:00:00Z"), ATHENS).ics), ATHENS).first()
        assertEquals(w(19), after.start)
        assertEquals("upcoming", where(after))
        assertTrue(after.isOpen)
    }

    @Test fun theDrawerCountsAgreeWithTheViews() {
        val tasks = listOf(t("a", start = w(20), due = w(18)), t("b", start = w(18, 19, 0)), t("c", start = w(25)), t("d", someday = true), t("e", due = w(17)))
        assertEquals(TaskViews.Counts(today = 2, overdue = 1, inbox = 0, upcoming = 1, someday = 1), TaskViews.counts(tasks, TODAY, null))
    }
}

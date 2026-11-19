package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// 17 Nov 2026 is a Tuesday
private val TODAY = LocalDate.of(2026, 11, 17)
private fun w(d: Int, h: Int? = null, m: Int = 0) = TaskWhen(LocalDate.of(2026, 11, d), h?.let { LocalTime.of(it, m) })

private fun task(start: TaskWhen? = null, due: TaskWhen? = null, someday: Boolean = false) = Task(
    "u", 1, "/l/u.ics", "e", "T", null, start, due, null, TaskStatus.NeedsAction, 0, Priority.None, emptyList(), someday, false, null, null,
    emptyList(), null, 0, null, null, false,
)

private fun plan(t: Task, target: RescheduleTarget, evening: Int = 18) = Reschedule.plan(t, target, TODAY, evening)

class RescheduleTest {
    @Test fun anOverdueTaskMovesItsDeadlineAndKeepsItsTime() {
        val r = plan(task(due = w(10, 9, 30)), RescheduleTarget.Tomorrow)
        assertEquals(w(18, 9, 30), r.due); assertNull(r.start)
    }

    @Test fun aTaskWithAStartMovesTheStart() {
        val r = plan(task(start = w(17, 8, 0), due = w(25, 17, 0)), RescheduleTarget.Tomorrow)
        assertEquals(w(18, 8, 0), r.start); assertEquals(w(25, 17, 0), r.due)
    }

    @Test fun aTaskWithOnlyADeadlineMovesTheDeadline() {
        val r = plan(task(due = w(20)), RescheduleTarget.NextWeek)
        assertEquals(w(23), r.due); assertNull(r.start)                          // the coming Monday
    }

    @Test fun aTaskWithNoDatesGetsAStart() {
        val r = plan(task(), RescheduleTarget.Tomorrow)
        assertEquals(w(18), r.start); assertNull(r.due)
    }

    @Test fun thisEveningUsesTheEveningHourOnToday() {
        assertEquals(w(17, 18, 0), plan(task(), RescheduleTarget.Evening).start)
        assertEquals(w(17, 20, 0), plan(task(), RescheduleTarget.Evening, evening = 20).start)
        assertEquals(w(17, 18, 0), plan(task(due = w(10)), RescheduleTarget.Evening).due)
    }

    @Test fun aStartMovedPastTheDeadlineDragsTheDeadlineAlong() {
        val r = plan(task(start = w(17, 8, 0), due = w(18, 17, 0)), RescheduleTarget.On(LocalDate.of(2026, 11, 25)))
        assertEquals(w(25, 8, 0), r.start); assertTrue(r.due!! >= r.start!!)
        assertEquals(25, r.due!!.date.dayOfMonth)
    }

    @Test fun anOverdueDeadlineMovedBeforeTheStartDragsTheStartAlong() {
        val r = plan(task(start = w(14, 8, 0), due = w(10, 17, 0)), RescheduleTarget.Today)       // overdue by its deadline
        assertEquals(w(17, 17, 0), r.due); assertTrue(r.start!! <= r.due!!)
    }

    // review: a deadline without a time must not be pulled to the start's clock time
    @Test fun aDeadlineWithoutATimeKeepsItsEndOfDay() {
        val r = plan(task(start = w(17, 9, 0), due = w(18)), RescheduleTarget.Tomorrow)
        assertEquals(w(18, 9, 0), r.start); assertEquals(w(18), r.due)
        val s = plan(task(start = w(16), due = w(17)), RescheduleTarget.Evening)
        assertEquals(w(17, 18, 0), s.start); assertEquals(w(17), s.due)
    }

    // review: a repeating task keeps the gap between its start and deadline
    @Test fun aRepeatingTaskMovesStartAndDeadlineByTheSameAmount() {
        val t = task(start = w(10, 9, 0), due = w(10, 17, 0)).copy(repeat = RepeatRule("DAILY", 1, emptyList(), null, null))
        val r = plan(t, RescheduleTarget.Today)
        assertEquals(w(17, 9, 0), r.start); assertEquals(w(17, 17, 0), r.due)
    }

    @Test fun somedayOnlyParksTheTask() {
        val r = plan(task(start = w(18, 9, 0), due = w(20)), RescheduleTarget.Someday)
        assertEquals(true, r.someday); assertEquals(w(18, 9, 0), r.start); assertEquals(w(20), r.due)
    }

    @Test fun aParkedTaskGivenADateIsTakenOutOfSomeday() {
        val r = plan(task(someday = true), RescheduleTarget.Tomorrow)
        assertEquals(false, r.someday); assertEquals(w(18), r.start)
        assertNull(plan(task(), RescheduleTarget.Tomorrow).someday)                  // not parked: nothing to change
    }

    @Test fun aPickedDateIsUsedAsIs() {
        assertEquals(w(2), plan(task(due = w(25)), RescheduleTarget.On(LocalDate.of(2026, 11, 2))).due)
    }
}

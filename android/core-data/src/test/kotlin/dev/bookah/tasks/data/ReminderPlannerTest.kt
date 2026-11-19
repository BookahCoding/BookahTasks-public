package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val ATHENS = ZoneId.of("Europe/Athens")
private val TODAY = LocalDate.of(2026, 11, 17)
private val NOW = TODAY.atTime(8, 0).atZone(ATHENS).toInstant().toEpochMilli()

private fun at(day: Int, h: Int, m: Int = 0) = LocalDate.of(2026, 11, day).atTime(h, m).atZone(ATHENS).toInstant().toEpochMilli()
private fun w(day: Int, h: Int? = null, m: Int = 0) = TaskWhen(LocalDate.of(2026, 11, day), h?.let { LocalTime.of(it, m) })

private fun task(
    uid: String, start: TaskWhen? = null, due: TaskWhen? = null, status: TaskStatus = TaskStatus.NeedsAction, someday: Boolean = false,
    copy: Boolean = false, reminders: List<Reminder> = emptyList(),
) = Task(
    uid, 1, "/l/$uid.ics", "e", "Title $uid", null, start, due, null, status, 0, Priority.None, emptyList(), someday, false, null, null,
    reminders, null, 0, null, null, copy,
)

private fun plan(vararg t: Task, now: Long = NOW) = ReminderPlanner.plan(t.toList(), now, ATHENS)

class ReminderPlannerTest {
    @Test fun aTimedStartRemindsAtTheStart() {
        val p = plan(task("a", start = w(18, 15, 30)))
        assertEquals(1, p.size); assertEquals(ReminderKind.Start, p[0].kind); assertEquals(at(18, 15, 30), p[0].triggerAtMs)
    }

    @Test fun aTimedDeadlineRemindsAnHourBefore() {
        val p = plan(task("a", due = w(18, 15, 30)))
        assertEquals(1, p.size); assertEquals(ReminderKind.DueSoon, p[0].kind); assertEquals(at(18, 14, 30), p[0].triggerAtMs)
    }

    @Test fun startAndDeadlineGiveTwoRemindersInOrder() {
        val p = plan(task("a", start = w(18, 9, 0), due = w(18, 17, 0)))
        assertEquals(listOf(ReminderKind.Start, ReminderKind.DueSoon), p.map { it.kind })
        assertEquals(listOf(at(18, 9, 0), at(18, 16, 0)), p.map { it.triggerAtMs })
    }

    // review: a deadline already less than an hour away still reminds, at the deadline itself
    @Test fun aDeadlineLessThanAnHourAwayRemindsAtTheDeadline() {
        val now = at(17, 16, 30)
        val p = ReminderPlanner.plan(listOf(task("a", due = w(17, 17, 0))), now, ATHENS)
        assertEquals(listOf(ReminderKind.DueNow), p.map { it.kind }); assertEquals(at(17, 17, 0), p[0].triggerAtMs)
        // an hour or more away keeps the "in 1 hour" reminder
        assertEquals(listOf(ReminderKind.DueSoon), ReminderPlanner.plan(listOf(task("b", due = w(17, 18, 0))), now, ATHENS).map { it.kind })
    }

    @Test fun aDeadlineReminderThatWouldFallBeforeTheStartIsSkipped() {
        val p = plan(task("a", start = w(18, 9, 0), due = w(18, 9, 30)))           // 08:30 would be before the task starts
        assertEquals(listOf(ReminderKind.Start), p.map { it.kind })
    }

    @Test fun dateOnlyValuesRemindAtNineThatDay() {
        val start = plan(task("a", start = w(19)))
        assertEquals(at(19, 9, 0), start.single().triggerAtMs); assertEquals(ReminderKind.Start, start.single().kind)
        val due = plan(task("b", due = w(19)))
        assertEquals(at(19, 9, 0), due.single().triggerAtMs); assertEquals(ReminderKind.DueToday, due.single().kind)
        assertEquals(1, plan(task("c", start = w(19), due = w(19))).size)           // same minute: one notification, not two
    }

    @Test fun onlyFutureOpenTasksWithinTwoWeeksCount() {
        val p = plan(
            task("past", start = w(17, 7, 0)), task("soon", start = w(17, 9, 0)), task("far", start = TaskWhen(LocalDate.of(2026, 12, 20), LocalTime.of(9, 0))),
            task("done", start = w(18, 9, 0), status = TaskStatus.Completed), task("parked", start = w(18, 9, 0), someday = true),
            task("copy", start = w(18, 9, 0), copy = true),
        )
        assertEquals(listOf("/l/soon.ics"), p.map { it.href })
    }

    @Test fun existingReminderLinesAlsoFireAndAreNotDoubled() {
        val p = plan(
            task("a", due = w(18, 17, 0), reminders = listOf(Reminder(60, true, null), Reminder(15, true, null), Reminder(null, false, at(18, 12, 0)))),
        )
        // the 60-minute line is the same minute as the automatic deadline reminder
        assertEquals(listOf(at(18, 12, 0), at(18, 16, 0), at(18, 16, 45)), p.map { it.triggerAtMs }.sorted())
        assertEquals(3, p.size)
    }

    @Test fun aReminderRelativeToTheStartFollowsTheStart() {
        val p = plan(task("a", start = w(18, 10, 0), reminders = listOf(Reminder(30, false, null))))
        assertTrue(p.any { it.triggerAtMs == at(18, 9, 30) })
    }

    @Test fun onlyTheNearestFortyAreKept() {
        val many = (1..60).map { task("t$it", start = w(18, 9, 0).copy(time = LocalTime.of(9, 0).plusMinutes(it.toLong()))) }
        val p = ReminderPlanner.plan(many, NOW, ATHENS)
        assertEquals(40, p.size); assertEquals(p.sortedBy { it.triggerAtMs }, p)
        assertEquals(at(18, 9, 1), p.first().triggerAtMs); assertEquals(at(18, 9, 40), p.last().triggerAtMs)
    }
}

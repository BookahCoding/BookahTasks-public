package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val TODAY = LocalDate.of(2026, 11, 16)

private fun task(
    uid: String, title: String = uid, start: TaskWhen? = null, due: TaskWhen? = null, status: TaskStatus = TaskStatus.NeedsAction,
    someday: Boolean = false, priority: Priority = Priority.None, parent: String? = null, list: Long = 1, sort: Long = 0, copy: Boolean = false,
    completedAtMs: Long? = null,
) = Task(
    uid, list, "/l/$uid.ics", "e", title, null, start, due, completedAtMs, status, 0, priority, emptyList(), someday, false, parent, null, emptyList(), null, sort, null, null, copy,
)

private fun d(day: Int, h: Int? = null, m: Int = 0, month: Int = 11) = TaskWhen(LocalDate.of(2026, month, day), h?.let { LocalTime.of(it, m) })

class TaskViewsTest {
    @Test fun todayHasStartedAndDueTodayAndKeepsOverdueSeparate() {
        val tasks = listOf(
            task("starts-today", start = d(16)), task("started-earlier", start = d(10)), task("due-today", due = d(16)),
            task("overdue", due = d(15)), task("future", start = d(17)), task("undated"), task("done", start = d(16), status = TaskStatus.Completed),
            task("parked", start = d(16), someday = true), task("copy", start = d(16), copy = true),
        )
        val v = TaskViews.today(tasks, TODAY)
        assertEquals(setOf("starts-today", "started-earlier", "due-today"), v.today.map { it.uid }.toSet())
        assertEquals(listOf("overdue"), v.overdue.map { it.uid })
        assertTrue(v.evening.isEmpty())
    }

    @Test fun eveningStartsAtTheSettingAndUsesTheTimeThatPlacesTheTaskToday() {
        val tasks = listOf(
            task("morning", start = d(16, 9)), task("exactly-18", start = d(16, 18)), task("late", due = d(16, 21, 30)),
            task("started-yesterday-due-tonight", start = d(15, 10), due = d(16, 19)), task("no-time", start = d(16)),
        )
        val v = TaskViews.today(tasks, TODAY, eveningHour = 18)
        assertEquals(listOf("exactly-18", "started-yesterday-due-tonight", "late"), v.evening.map { it.uid })
        assertEquals(listOf("morning", "no-time"), v.today.map { it.uid })       // timed first, then untimed
        assertEquals(listOf("late"), TaskViews.today(tasks, TODAY, eveningHour = 20).evening.map { it.uid })      // 18:00 and 19:00 are before an 20:00 evening
    }

    @Test fun waitingTaskWithAFutureCheckBackDateStaysOutOfToday() {
        val waiting = task("w", start = d(20))
        assertTrue(TaskViews.today(listOf(waiting), TODAY).today.isEmpty())
        assertEquals(LocalDate.of(2026, 11, 20), TaskViews.upcomingDate(waiting, TODAY))
        assertEquals(listOf("w"), TaskViews.today(listOf(waiting), LocalDate.of(2026, 11, 20)).today.map { it.uid })
    }

    @Test fun upcomingGroupsByDateAndSkipsWhatIsAlreadyInToday() {
        val tasks = listOf(
            task("a", start = d(18)), task("b", due = d(18)), task("c", start = d(17), due = d(25)),
            task("started-due-later", start = d(10), due = d(30)), task("someday", start = d(18), someday = true), task("overdue", due = d(1)),
        )
        val up = TaskViews.upcoming(tasks, TODAY)
        assertEquals(listOf(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 18)), up.map { it.first })
        assertEquals(setOf("a", "b"), up[1].second.map { it.uid }.toSet())
    }

    @Test fun inboxIsUndatedTopLevelOpenTasksOfTheInboxList() {
        val tasks = listOf(
            task("i1", list = 5), task("i2", list = 5, sort = -1), task("dated", list = 5, due = d(20)), task("sub", list = 5, parent = "i1"),
            task("other-list", list = 6), task("parked", list = 5, someday = true), task("done", list = 5, status = TaskStatus.Completed),
        )
        assertEquals(listOf("i2", "i1"), TaskViews.inbox(tasks, 5).map { it.uid })
        assertEquals(3, TaskViews.inbox(tasks, null).size)                    // i1, i2, other-list
        assertEquals(listOf("parked"), TaskViews.someday(tasks).map { it.uid })
    }

    @Test fun logbookGroupsByFinishDayNewestFirstWithUndatedLast() {
        val zone = ZoneId.of("Europe/Athens")
        fun ms(s: String) = java.time.Instant.parse(s).toEpochMilli()
        val tasks = listOf(
            task("a", status = TaskStatus.Completed, completedAtMs = ms("2026-11-15T08:00:00Z")),
            task("b", status = TaskStatus.Completed, completedAtMs = ms("2026-11-16T08:00:00Z")),
            task("c", status = TaskStatus.Completed, completedAtMs = ms("2026-11-16T09:30:00Z")),
            task("d", status = TaskStatus.Completed), task("open"),
            task("late-evening", status = TaskStatus.Completed, completedAtMs = ms("2026-11-14T23:30:00Z")),     // 01:30 on the 15th in Athens
        )
        val log = TaskViews.logbook(tasks, zone)
        assertEquals(listOf(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 15), null), log.map { it.date })
        assertEquals(listOf("c", "b"), log[0].tasks.map { it.uid })
        assertEquals(setOf("a", "late-evening"), log[1].tasks.map { it.uid }.toSet())
    }

    @Test fun treeNestsSubtasksAndNeverLosesATask() {
        val tasks = listOf(task("p", sort = 1), task("k1", parent = "p", sort = 2), task("k2", parent = "p", sort = 1), task("gk", parent = "k1"), task("orphan", parent = "gone", sort = 3))
        val tree = TaskViews.buildTree(tasks)
        assertEquals(listOf("p", "orphan"), tree.map { it.task.uid })
        assertEquals(listOf("k2", "k1"), tree[0].children.map { it.task.uid })
        assertEquals(listOf("gk"), tree[0].children[1].children.map { it.task.uid })
        // two tasks naming each other as parent: both still appear
        val loop = TaskViews.buildTree(listOf(task("x", parent = "y"), task("y", parent = "x")))
        assertEquals(setOf("x", "y"), loop.flatMap { listOf(it) + it.children }.map { it.task.uid }.toSet())
    }

    @Test fun listTreeHidesFinishedAndParkedAndOtherLists() {
        val tasks = listOf(task("a", list = 1), task("b", list = 2), task("c", list = 1, status = TaskStatus.Completed), task("d", list = 1, someday = true))
        assertEquals(listOf("a"), TaskViews.listTree(tasks, 1).map { it.task.uid })
    }

    @Test fun countsForTheDrawer() {
        val tasks = listOf(task("t", start = d(16)), task("o", due = d(1)), task("i"), task("u", start = d(20)), task("s", someday = true))
        val c = TaskViews.counts(tasks, TODAY, inboxListId = null)
        assertEquals(TaskViews.Counts(today = 1, overdue = 1, inbox = 1, upcoming = 1, someday = 1), c)
    }
}

class ScheduleViewTest {
    @Test fun overdueFirstThenNoDateThenDaysWithTodayIncluded() {
        val tasks = listOf(
            task("overdue", due = d(10)), task("undated"), task("sub-undated", parent = "undated"), task("today-timed", due = d(16, 9)),
            task("started", start = d(12), due = d(30)), task("tomorrow", start = d(17)), task("later", due = d(25)),
            task("done", due = d(16), status = TaskStatus.Completed), task("parked", due = d(17), someday = true),
        )
        val v = TaskViews.schedule(tasks, TODAY)
        assertEquals(listOf("overdue"), v.overdue.map { it.uid })
        assertEquals(listOf("undated"), v.noDate.map { it.uid })
        assertEquals(listOf(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 25)), v.days.map { it.first })
        assertEquals(listOf("today-timed", "started"), v.days[0].second.map { it.uid })
    }
}

class BucketDaysTest {
    @Test fun nearDaysKeepTheirHeadingAndLaterOnesJoinByWeek() {
        // TODAY is Monday 16 Nov 2026; near = 16..22 Nov
        val days = TaskViews.schedule(listOf(task("a", due = d(18)), task("b", due = d(22)), task("c", due = d(24)), task("e", due = d(29)), task("f", due = d(30)), task("g", due = d(1, month = 12))), TODAY).days
        val b = TaskViews.bucketDays(days, TODAY)
        assertEquals(listOf(false, false, true, true), b.map { it.weekOf })
        assertEquals(listOf(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 30)), b.map { it.date })
        assertEquals(listOf("c", "e"), b[2].tasks.map { it.uid })       // 24 and 29 Nov are in the week of Monday 23 Nov
        assertEquals(listOf("f", "g"), b[3].tasks.map { it.uid })
    }
}

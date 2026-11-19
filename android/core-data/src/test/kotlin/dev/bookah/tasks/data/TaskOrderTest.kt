package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.random.Random

private fun task(uid: String, sort: Long, title: String = uid, parent: String? = null, list: Long = 1, someday: Boolean = false, status: TaskStatus = TaskStatus.NeedsAction) = Task(
    uid, list, "/l/$uid.ics", "e", title, null, null, null, null, status, 0, Priority.None, emptyList(), someday, false,
    parent, null, emptyList(), null, sort, null, null, false,
)

/** Applies a plan the way the server copy would come back, and gives the order a list view shows. */
private fun applied(group: List<Task>, plan: Map<String, Long>): List<String> =
    group.map { t -> plan[t.href]?.let { t.copy(sortOrder = it) } ?: t }.sortedWith(TaskOrder.comparator).map { it.uid }

private fun moved(group: List<Task>, uid: String, to: Int): List<String> =
    group.map { it.uid }.toMutableList().apply { add(to, removeAt(indexOf(uid))) }

class TaskOrderTest {
    @Test fun aMoveBetweenTwoTasksWritesOneValueHalfwayBetweenThem() {
        val g = listOf(task("a", 1000), task("b", 2000), task("c", 3000))
        val plan = TaskOrder.plan(g, "/l/c.ics", 1)
        assertEquals(mapOf("/l/c.ics" to 1500L), plan)
        assertEquals(listOf("a", "c", "b"), applied(g, plan))
    }

    @Test fun theTopGoesOneGapAboveTheFirstOrHalfwayToOne() {
        val g = listOf(task("a", 5000), task("b", 6000))
        assertEquals(mapOf("/l/b.ics" to 5000L - TaskOrder.GAP), TaskOrder.plan(g, "/l/b.ics", 0))
        val small = listOf(task("a", 10), task("b", 20))
        assertEquals(mapOf("/l/b.ics" to 5L), TaskOrder.plan(small, "/l/b.ics", 0))
    }

    @Test fun theBottomGoesOneGapBelowTheLastAndNeverPastTheLargest32BitNumber() {
        val g = listOf(task("a", 1000), task("b", 2000))
        assertEquals(mapOf("/l/a.ics" to 2000L + TaskOrder.GAP), TaskOrder.plan(g, "/l/a.ics", 1))
        val high = listOf(task("a", 1), task("b", TaskOrder.MAX - 10))
        val v = TaskOrder.plan(high, "/l/a.ics", 1).getValue("/l/a.ics")
        assertTrue(v > TaskOrder.MAX - 10 && v <= TaskOrder.MAX)
    }

    @Test fun noRoomLeftRenumbersTheGroupAndWritesOnlyWhatChanged() {
        val g = listOf(task("a", 1024), task("b", 1025), task("c", 3072))
        val plan = TaskOrder.plan(g, "/l/c.ics", 1)         // nothing fits between 1024 and 1025
        assertEquals(listOf("a", "c", "b"), applied(g, plan))
        assertEquals(setOf("/l/c.ics", "/l/b.ics"), plan.keys)  // a keeps 1024 = 1 x GAP
        assertEquals(2048L, plan["/l/c.ics"]); assertEquals(3072L, plan["/l/b.ics"])
    }

    @Test fun aRenumberTouchesOnlyTheSmallestRunAroundTheMove() {
        val g = listOf(task("a", 100), task("t1", 500, "T1"), task("t2", 500, "T2"), task("t3", 500, "T3"), task("t4", 500, "T4"), task("t5", 500, "T5"))
        val plan = TaskOrder.plan(g, "/l/t5.ics", 2)
        assertEquals(listOf("a", "t1", "t5", "t2", "t3", "t4"), applied(g, plan))
        assertEquals(mapOf("/l/t1.ics" to 200L, "/l/t5.ics" to 300L, "/l/t2.ics" to 400L), plan)      // a, t3 and t4 are not written
    }

    @Test fun aTopThatCannotGoLowerRenumbers() {
        val g = listOf(task("a", 1), task("b", 7))
        val plan = TaskOrder.plan(g, "/l/b.ics", 0)
        assertEquals(listOf("b", "a"), applied(g, plan))
        assertTrue(plan.values.all { it in 1..TaskOrder.MAX })
    }

    @Test fun tiesAreShownByTitleAndAMoveKeepsThatOrderForTheOthers() {
        // all made in the same second without an order of their own: the same value, shown by title
        val g = listOf(task("x1", 500, "Alpha"), task("x2", 500, "Beta"), task("x3", 500, "Gamma")).sortedWith(TaskOrder.comparator)
        val top = TaskOrder.plan(g, "/l/x3.ics", 0)
        assertEquals(1, top.size)                              // halfway to one still fits below 500
        assertEquals(listOf("x3", "x1", "x2"), applied(g, top))
        val mid = TaskOrder.plan(g, "/l/x3.ics", 1)            // nothing fits between two equal values
        assertEquals(listOf("x1", "x3", "x2"), applied(g, mid))
    }

    @Test fun negativeValuesWrittenByAnotherAppAreNeverCopiedAndStayInOrder() {
        val g = listOf(task("a", -50), task("b", -10), task("c", 7))
        for ((who, to) in listOf("c" to 0, "a" to 2, "c" to 1, "b" to 0)) {
            val plan = TaskOrder.plan(g, "/l/$who.ics", to)
            assertEquals("$who to $to", moved(g, who, to), applied(g, plan))
            assertTrue("$who to $to: $plan", plan.values.all { it in 1..TaskOrder.MAX })
        }
    }

    @Test fun valuesAbove32BitsWrittenByAnotherAppAreClampedIntoRange() {
        val big = 5_000_000_000L
        val g = listOf(task("a", 100), task("b", big), task("c", big + 1))
        for ((who, to) in listOf("a" to 2, "c" to 0, "a" to 1, "b" to 2)) {
            val plan = TaskOrder.plan(g, "/l/$who.ics", to)
            assertEquals("$who to $to", moved(g, who, to), applied(g, plan))
            assertTrue("$who to $to: $plan", plan.values.all { it in 1..TaskOrder.MAX })
        }
    }

    @Test fun theNewPlaceIsTakenFromTheNeighboursAsShown() {
        val g = listOf(task("a", 1), task("b", 2), task("c", 3), task("d", 4))
        assertEquals(1, TaskOrder.indexBetween(g, "/l/d.ics", after = "/l/a.ics", before = "/l/b.ics"))
        assertEquals(0, TaskOrder.indexBetween(g, "/l/d.ics", after = null, before = "/l/a.ics"))
        assertEquals(3, TaskOrder.indexBetween(g, "/l/a.ics", after = "/l/d.ics", before = null))
        assertEquals(1, TaskOrder.indexBetween(g, "/l/d.ics", after = "/l/gone.ics", before = "/l/b.ics"))      // falls back on the other side
        assertEquals(null, TaskOrder.indexBetween(g, "/l/d.ics", after = "/l/gone.ics", before = null))
        assertEquals(null, TaskOrder.indexBetween(g, "/l/d.ics", after = null, before = null))
        assertEquals(null, TaskOrder.indexBetween(g, "/l/gone.ics", after = "/l/a.ics", before = null))
    }

    @Test fun theNeighboursOfAPlaceLeadBackToThatPlace() {
        val g = listOf(task("a", 1), task("b", 2), task("c", 3), task("d", 4))
        assertEquals("/l/a.ics" to "/l/b.ics", TaskOrder.neighbours(g, "/l/d.ics", 1))
        assertEquals(null to "/l/a.ics", TaskOrder.neighbours(g, "/l/d.ics", 0))
        assertEquals("/l/d.ics" to null, TaskOrder.neighbours(g, "/l/a.ics", 3))
        for (who in g) for (to in g.indices) {
            val (after, before) = TaskOrder.neighbours(g, who.href, to)
            assertEquals("${who.uid} to $to", to, TaskOrder.indexBetween(g, who.href, after, before))
        }
    }

    @Test fun nothingToDoForTheSamePlaceAnUnknownTaskOrAGroupOfOne() {
        val g = listOf(task("a", 1), task("b", 2))
        assertTrue(TaskOrder.plan(g, "/l/a.ics", 0).isEmpty())
        assertTrue(TaskOrder.plan(g, "/l/zzz.ics", 1).isEmpty())
        assertTrue(TaskOrder.plan(listOf(task("a", 1)), "/l/a.ics", 0).isEmpty())
        assertEquals(TaskOrder.plan(g, "/l/a.ics", 1), TaskOrder.plan(g, "/l/a.ics", 99))       // past the end = the end
    }

    @Test fun anyMoveGivesExactlyTheAskedOrderAndValuesStayIn32BitRange() {
        val r = Random(42)
        repeat(3000) {
            val n = r.nextInt(2, 9)
            val pool = listOf(-5L, 0L, 1L, 2L, 3L, 500L, 1024L, 1025L, TaskOrder.MAX - 1, TaskOrder.MAX, TaskOrder.NO_ORDER, 813_000_000L, TaskOrder.MAX + 1, 9_000_000_000L)
            val g = (0 until n).map { i -> task("t$i", if (r.nextBoolean()) pool.random(r) else r.nextLong(0, 5000), title = "T" + r.nextInt(3)) }
                .sortedWith(TaskOrder.comparator)
            val who = g.random(r).uid; val to = r.nextInt(n)
            val plan = TaskOrder.plan(g, "/l/$who.ics", to)
            assertEquals("group ${g.map { it.uid to it.sortOrder }} move $who to $to", moved(g, who, to), applied(g, plan))
            assertTrue(plan.values.all { it in 1..TaskOrder.MAX })
        }
    }

    @Test fun anOrdinaryMoveWritesOneTask() {
        val r = Random(7)
        repeat(500) {
            val g = (0 until 6).map { i -> task("t$i", (i + 1) * TaskOrder.GAP) }
            val who = g.random(r).uid; val to = r.nextInt(6)
            val plan = TaskOrder.plan(g, "/l/$who.ics", to)
            assertTrue(plan.size <= 1)
        }
    }

    @Test fun groupsAreTheTopLevelOrTheChildrenOfOneParentAsTheListShowsThem() {
        val tasks = listOf(
            task("p", 2000), task("q", 1000), task("k2", 20, parent = "p"), task("k1", 10, parent = "p"), task("k3", 30, parent = "q"),
            task("gk", 1, parent = "k1"), task("parked", 1, someday = true), task("done", 1, status = TaskStatus.Completed), task("other", 1, list = 2),
        )
        val tree = TaskViews.listTree(tasks, 1)
        assertEquals(listOf("q", "p"), TaskOrder.groupOf(tree, "/l/p.ics")!!.map { it.uid })
        assertEquals(listOf("k1", "k2"), TaskOrder.groupOf(tree, "/l/k2.ics")!!.map { it.uid })
        assertEquals(listOf("gk"), TaskOrder.groupOf(tree, "/l/gk.ics")!!.map { it.uid })
        assertEquals(null, TaskOrder.groupOf(tree, "/l/parked.ics"))
    }

    @Test fun setSortOrderChangesOnlyThatLine() {
        val base = "BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:t1\r\nSUMMARY:A\r\nX-APPLE-SORT-ORDER:10\r\nX-OTHER:keep\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        assertEquals(base.replace("X-APPLE-SORT-ORDER:10", "X-APPLE-SORT-ORDER:1536"), TaskEdit.setSortOrder(base, 1536))
        val none = "BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:t1\r\nSUMMARY:A\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        val out = TaskEdit.setSortOrder(none, 77)
        assertEquals(77L, TaskParser.parse(StoredObject("/l/t1.ics", 1, "e", out), ZoneId.of("UTC")).first().sortOrder)
        assertEquals(none.replace("SUMMARY:A\r\n", "SUMMARY:A\r\nX-APPLE-SORT-ORDER:77\r\n"), out)
    }

    @Test fun aTaskWithoutAnyOrderOrCreationTimeSortsLast() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:t1\r\nSUMMARY:A\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        val t = TaskParser.parse(StoredObject("/l/t1.ics", 1, "e", ics), ZoneId.of("UTC")).first()
        assertEquals(TaskOrder.NO_ORDER, t.sortOrder)
        val withCreated = ics.replace("SUMMARY:A", "SUMMARY:A\r\nCREATED:20260101T000000Z")
        val c = TaskParser.parse(StoredObject("/l/t2.ics", 1, "e", withCreated), ZoneId.of("UTC")).first()
        assertTrue(c.sortOrder < t.sortOrder)
    }
}

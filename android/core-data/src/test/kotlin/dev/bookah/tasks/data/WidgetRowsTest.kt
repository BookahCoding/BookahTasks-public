package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random

private val Z: ZoneId = ZoneId.of("Europe/Athens")
private val TODAY: LocalDate = LocalDate.of(2026, 11, 18)
private const val NOW = 1_795_000_000_000L          // a fixed "now" in ms

private fun ics(uid: String, title: String, body: String = "") =
    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VTODO\r\nUID:$uid\r\nDTSTAMP:20260101T000000Z\r\nSUMMARY:$title\r\n$body" +
        "END:VTODO\r\nEND:VCALENDAR\r\n"

private fun task(uid: String, title: String, body: String = "", listId: Long = 1): Task =
    TaskParser.parse(StoredObject("/l/$uid.ics", listId, "e", ics(uid, title, body)), Z).first()

private val LISTS = mapOf(
    1L to WidgetList("/l/", "Tasks", "#A63F2D", true),
    2L to WidgetList("/ro/", "Shared", "#245A86", false),
)

private fun rows(tasks: List<Task>, advanced: List<String> = emptyList(), max: Int = WidgetRows.MAX_ITEMS, evening: Int = 18) =
    WidgetRows.build(tasks, LISTS, TODAY, NOW, evening, advanced, max)

private fun items(r: List<WidgetRow>) = r.filterIsInstance<WidgetRow.Item>()

class WidgetRowsTest {
    private val dueToday = "DUE;VALUE=DATE:20261118\r\n"

    @Test fun nothingGivesNoRows() = assertEquals(emptyList<WidgetRow>(), rows(emptyList()))

    @Test fun todayTasksFollowTheTodayScreenOrderAndGetOneHeading() {
        val a = task("a", "Late", "DTSTART:20261118T150000Z\r\n")      // 17:00 Athens
        val b = task("b", "Early", "DTSTART:20261118T070000Z\r\n")     // 09:00 Athens
        val r = rows(listOf(a, b))
        assertEquals(WidgetRow.Heading(evening = false), r[0])
        assertEquals(listOf("Early", "Late"), items(r).map { it.title })
    }

    @Test fun overdueIsOneFoldRowWithTheCountAndNoTaskRows() {
        val r = rows(listOf(task("o1", "x", "DUE;VALUE=DATE:20261110\r\n"), task("o2", "y", "DUE;VALUE=DATE:20261116\r\n")))
        assertEquals(listOf<WidgetRow>(WidgetRow.OverdueFold(2)), r)
    }

    @Test fun eveningTasksGetTheirOwnHeadingAfterToday() {
        val r = rows(listOf(task("a", "Dinner", "DTSTART:20261118T170000Z\r\n"), task("b", "Plain", dueToday)))   // 19:00 Athens is evening
        assertEquals(2, r.filterIsInstance<WidgetRow.Heading>().size)
        assertEquals(listOf("Plain", "Dinner"), items(r).map { it.title })
        assertEquals(WidgetRow.Heading(true), r.filterIsInstance<WidgetRow.Heading>().last())
    }

    @Test fun aReadOnlyListGetsNoTickAndAWritableListDoes() {
        val r = items(rows(listOf(task("a", "mine", dueToday, listId = 1), task("b", "shared", dueToday, listId = 2))))
        assertTrue(r.first { it.title == "mine" }.canTick)
        assertFalse(r.first { it.title == "shared" }.canTick)
    }

    @Test fun aTaskOnAnUnknownListIsShownButCannotBeTicked() {
        val r = items(rows(listOf(task("a", "orphan", dueToday, listId = 99))))
        assertEquals(1, r.size); assertFalse(r[0].canTick); assertEquals("", r[0].listName)
    }

    @Test fun moreThanTheCapShowsAMoreRowWithTheRest() {
        val many = (1..35).map { task("t$it", "task $it", dueToday) }
        val r = rows(many)
        assertEquals(30, items(r).size)
        assertEquals(WidgetRow.More(5), r.last())
    }

    @Test fun aTaskFinishedUnderTwoMinutesAgoIsShownStruckAndOlderOnesAreDropped() {
        val doneAt = { agoMs: Long -> "STATUS:COMPLETED\r\nCOMPLETED:" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneId.of("UTC")).format(java.time.Instant.ofEpochMilli(NOW - agoMs)) + "\r\n" + dueToday }
        val fresh = task("f", "fresh", doneAt(30_000))
        val old = task("o", "old", doneAt(180_000))
        val r = items(rows(listOf(fresh, old)))
        assertEquals(listOf("fresh"), r.map { it.title })
        assertTrue(r[0].done); assertTrue(r[0].canTick)
    }

    @Test fun aRepeatingTaskThatMovedShowsANextDateNoteFirst() {
        val r = rows(listOf(task("a", "Plain", dueToday)), advanced = listOf("Water plants"))
        assertEquals(WidgetRow.NextDate("Water plants"), r[0])
    }

    @Test fun theStampChangesWhenDatesOrStateChange() {
        val a = task("a", "x", dueToday)
        val b = task("a", "x", "DUE;VALUE=DATE:20261119\r\n")
        val c = task("a", "x", dueToday + "STATUS:COMPLETED\r\n")
        assertTrue(WidgetRows.stamp(a) != WidgetRows.stamp(b))
        assertTrue(WidgetRows.stamp(a) != WidgetRows.stamp(c))
        assertEquals(WidgetRows.stamp(a), WidgetRows.stamp(task("a", "other title", dueToday)))
    }

    @Test fun aTaskWithNoTitleStillGetsARow() {
        assertEquals(1, items(rows(listOf(task("a", "", dueToday)))).size)
    }

    @Test fun randomTaskSetsNeverThrow() {
        val rnd = Random(7)
        val bodies = listOf(
            "", dueToday, "DUE;VALUE=DATE:20261101\r\n", "DTSTART:20261118T200000Z\r\n", "DTSTART;VALUE=DATE:20261201\r\n",
            "STATUS:COMPLETED\r\nCOMPLETED:20261118T080000Z\r\n", "RRULE:FREQ=DAILY\r\nDTSTART;VALUE=DATE:20261118\r\n",
            "CATEGORIES:someday\r\n", "RELATED-TO:p\r\n", "PRIORITY:1\r\n",
        )
        repeat(2000) { n ->
            val set = (0..rnd.nextInt(40)).map { i -> task("u$n-$i", "t$i", bodies[rnd.nextInt(bodies.size)] + bodies[rnd.nextInt(bodies.size)], listId = (rnd.nextInt(3)).toLong()) }
            rows(set, advanced = if (rnd.nextBoolean()) listOf("n") else emptyList(), max = 1 + rnd.nextInt(40))
        }
    }
}

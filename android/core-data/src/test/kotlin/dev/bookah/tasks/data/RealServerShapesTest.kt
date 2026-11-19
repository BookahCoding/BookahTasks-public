package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

private val ATHENS = ZoneId.of("Europe/Athens")

private fun fixture(name: String): StoredObject {
    val text = RealServerShapesTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().toString(Charsets.UTF_8)
    return StoredObject("/l/$name", 1, "etag", text)
}

/**
 * Phase 0 kept what the Nextcloud Tasks web app really wrote back into my own spike tasks (the three `.ics` fixtures: generic titles, no personal data)
 * and how the server really described the calendar home (private, so only read from the folder named by BOOKAH_PRIVATE_DIR; skipped when absent;
 * counts only). A parser that slipped on these would show an empty app on the first real sign-in.
 */
class RealServerShapesTest {
    @Test fun plainTaskCompletedInTheWebApp() {
        val t = TaskParser.parse(fixture("web-app-plain-completed.ics"), ATHENS).single()
        assertTrue(t.isDone); assertEquals(TaskStatus.Completed, t.status); assertEquals(100, t.percent); assertTrue(t.completedAtMs != null)
        assertEquals(10L, t.sortOrder)                                              // X-APPLE-SORT-ORDER kept by the web app
        assertEquals("https://example.com/spike", t.url)
        assertEquals(Priority.High, t.priority)
        assertEquals(listOf("spike"), t.tags); assertTrue(t.someday)
        assertEquals(2, t.reminders.size)                                           // one relative, one absolute, both kept
        assertEquals(1, t.reminders.count { it.minutesBefore == 30 }); assertEquals(1, t.reminders.count { it.absoluteMs != null })
        assertEquals("Notes line", t.notes)
        assertTrue(t.start != null && t.due != null)
    }

    @Test fun childEditedAndCompletedInTheWebAppKeepsItsParent() {
        val t = TaskParser.parse(fixture("web-app-child-completed.ics"), ATHENS).single()
        assertTrue(t.isDone)
        assertEquals("6e16893e-1a4c-4dad-81ff-174c1a69f3fc", t.parentUid)           // written without RELTYPE by the web app
        assertEquals("Spike child rename", t.title)
        assertEquals(LocalDate.of(2026, 11, 16), t.due!!.date)                      // DUE 19:00 UTC = 21:00 in Athens, same day
        assertEquals(21, t.due!!.time!!.hour)
        assertNull(t.start)
    }

    @Test fun repeatingTaskAfterTicksIsStillOneOpenTaskWithItsRule() {
        val t = TaskParser.parse(fixture("web-app-repeating-open.ics"), ATHENS).single()
        assertTrue(t.isOpen); assertFalse(t.isDone)                                 // the web app dropped STATUS: no status line means open
        assertEquals(TaskStatus.NeedsAction, t.status)
        assertEquals(RepeatRule("WEEKLY", 1, listOf("MO"), null, null, raw = "FREQ=WEEKLY;BYDAY=MO"), t.repeat)
        assertEquals(listOf(Reminder(10, false, null)), t.reminders)
        assertEquals(LocalDate.of(2026, 11, 29), t.start!!.date); assertEquals(LocalDate.of(2026, 11, 30), t.due!!.date)
        assertFalse(t.isOccurrenceCopy)
    }

    @Test fun realCalendarHomeAnswer() {
        val dir = System.getenv("BOOKAH_PRIVATE_DIR")
        val file = dir?.let { File(it, "home-propfind.xml") }
        assumeTrue("private folder not set or file absent", file != null && file.exists())
        val lists = CalDavClient.parseListCollections(file!!.readText())
        val tasks = lists.count { it.kind == ListKind.Tasks }; val events = lists.count { it.kind == ListKind.Events }; val both = lists.count { it.kind == ListKind.Both }
        println("real calendar home: ${lists.size} lists, $tasks task-only, $events event-only, $both both")
        assertTrue("at least one task list expected", tasks >= 1)
        assertTrue("the event calendars are still known", events >= 1)
        // nothing from the scheduling inbox, outbox or the trash bin sneaked in
        assertTrue(lists.all { l -> l.href.trimEnd('/').substringAfterLast('/') !in setOf("inbox", "outbox", "trashbin") })
        assertTrue(lists.all { it.href.startsWith("/remote.php/dav/calendars/") })
    }
}

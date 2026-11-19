package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun task(uid: String, list: Long, copy: Boolean = false) = Task(
    uid, list, "/cal/l$list/$uid.ics", "e", uid, null, null, null, null, TaskStatus.NeedsAction, 0, Priority.None, emptyList(), false, false,
    null, null, emptyList(), null, 0, null, null, copy,
)

private fun list(id: Long, href: String) = TaskList(id, href, "L$id", "#245A86", ListKind.Tasks, true, true, null, 0)

/** The link BookahCalendar sends to open one task: bookahtasks://task?uid=<encoded UID>&list=<encoded list href>. Nothing in it is trusted. */
class TaskLinkTest {
    @Test fun aPlainLinkGivesTheUidAndTheList() {
        assertEquals(TaskLink("abc-123", "/remote.php/dav/calendars/u/tasks/"), TaskLinks.parse("bookahtasks://task?uid=abc-123&list=%2Fremote.php%2Fdav%2Fcalendars%2Fu%2Ftasks%2F"))
    }

    @Test fun theListIsOptionalAndTheOrderOfTheParametersDoesNotMatter() {
        assertEquals(TaskLink("abc", null), TaskLinks.parse("bookahtasks://task?uid=abc"))
        assertEquals(TaskLink("abc", "/l/"), TaskLinks.parse("bookahtasks://task?list=%2Fl%2F&uid=abc"))
        assertEquals(TaskLink("abc", null), TaskLinks.parse("bookahtasks://task/?uid=abc&list="))          // an empty list is no list
    }

    @Test fun schemeAndHostIgnoreLetterCaseButNotTheParameterNames() {
        assertEquals(TaskLink("abc", null), TaskLinks.parse("BookahTasks://TASK?uid=abc"))
        assertNull(TaskLinks.parse("bookahtasks://task?UID=abc"))
    }

    @Test fun aMissingOrEmptyUidGivesNothing() {
        assertNull(TaskLinks.parse("bookahtasks://task"))
        assertNull(TaskLinks.parse("bookahtasks://task?"))
        assertNull(TaskLinks.parse("bookahtasks://task?list=%2Fl%2F"))
        assertNull(TaskLinks.parse("bookahtasks://task?uid="))
        assertNull(TaskLinks.parse("bookahtasks://task?uid=%20%20"))
        assertNull(TaskLinks.parse("bookahtasks://task?uid"))
    }

    @Test fun junkGivesNothingAndNeverThrows() {
        val junk = listOf(
            null, "", " ", "bookahtasks:", "bookahtasks://", "bookahtasks://other?uid=a", "bookahtasks://task:80?uid=a", "bookahtasks://me@task?uid=a",
            "bookahtasks://task/edit?uid=a", "https://task?uid=a", "bookah://task?uid=a", "bookahtasks:task?uid=a", "bookahtasks://task?uid=%zz",
            "bookahtasks://task?uid=abc%", "bookahtasks://task?uid=%E0%A4", "bookahtasks://task?uid=a%0Ab", "bookahtasks://task?uid=a%00",
            "bookahtasks://task?uid=" + "x".repeat(2000), "bookahtasks://task?uid=a&list=" + "y".repeat(5000), "%%%", "\u0000", "bookahtasks://task?&&&=&=",
            "bookahtasks://task?uid=a&list=%0D%0A",
        )
        for (j in junk) assertNull("should refuse: ${j?.take(60)}", TaskLinks.parse(j))
    }

    @Test fun encodedCharactersAreDecodedOnce() {
        assertEquals("a b", TaskLinks.parse("bookahtasks://task?uid=a%20b")!!.uid)
        assertEquals("a b", TaskLinks.parse("bookahtasks://task?uid=a+b")!!.uid)           // as Android's own Uri.getQueryParameter reads it
        assertEquals("a+b", TaskLinks.parse("bookahtasks://task?uid=a%2Bb")!!.uid)
        assertEquals("x&y=z", TaskLinks.parse("bookahtasks://task?uid=x%26y%3Dz")!!.uid)
        assertEquals("%41", TaskLinks.parse("bookahtasks://task?uid=%2541")!!.uid)          // never decoded twice
        assertEquals("abc", TaskLinks.parse("bookahtasks://task?uid=abc#frag")!!.uid)
    }

    @Test fun aGreekUidWorksEncodedOrAsIs() {
        assertEquals("εργασία-1", TaskLinks.parse("bookahtasks://task?uid=%CE%B5%CF%81%CE%B3%CE%B1%CF%83%CE%AF%CE%B1-1")!!.uid)
        assertEquals("εργασία-1", TaskLinks.parse("bookahtasks://task?uid=εργασία-1")!!.uid)
    }

    @Test fun theFirstOfARepeatedParameterWins() {
        assertEquals(TaskLink("first", "/a/"), TaskLinks.parse("bookahtasks://task?uid=first&uid=second&list=%2Fa%2F&list=%2Fb%2F"))
    }

    @Test fun findIgnoresFinishedCopiesOfARepeatingTask() {
        val copy = task("rep", 1, copy = true); val master = task("rep", 1)
        assertEquals(master, TaskLinks.find(listOf(copy, master), emptyList(), TaskLink("rep", null)))
        assertNull(TaskLinks.find(listOf(copy), emptyList(), TaskLink("rep", null)))
        assertNull(TaskLinks.find(listOf(master), emptyList(), TaskLink("other", null)))
    }

    @Test fun theListIsAPreferenceNotAFilter() {
        // during a move the same UID is briefly in two lists; a list that does not match (or is unknown) still finds the task
        val a = task("t", 1); val b = task("t", 2)
        val lists = listOf(list(1, "/remote.php/dav/calendars/u/home/"), list(2, "/remote.php/dav/calendars/u/work/"))
        assertEquals(b, TaskLinks.find(listOf(a, b), lists, TaskLink("t", "/remote.php/dav/calendars/u/work/")))
        assertEquals(b, TaskLinks.find(listOf(a, b), lists, TaskLink("t", "/remote.php/dav/calendars/u/work")))                     // no trailing slash
        assertEquals(b, TaskLinks.find(listOf(a, b), lists, TaskLink("t", "https://cloud.example/remote.php/dav/calendars/u/work/")))    // a full address
        assertEquals(a, TaskLinks.find(listOf(a, b), lists, TaskLink("t", "/somewhere/else/")))
        assertEquals(a, TaskLinks.find(listOf(a, b), lists, TaskLink("t", null)))
    }
}

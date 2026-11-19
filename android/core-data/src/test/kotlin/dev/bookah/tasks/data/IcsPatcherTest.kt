package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

private const val CR = "\r\n"
private val ATHENS = ZoneId.of("Europe/Athens")
private val NOW = Instant.parse("2026-11-17T10:00:00Z")

private val SAMPLE = listOf(
    "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Other app//EN", "BEGIN:VTODO", "UID:u1", "DTSTAMP:20261116T182800Z",
    "SUMMARY:Old title",
    "DESCRIPTION:A note that was folded by another app so it must stay exactly as it was, down to the",
    " fold position",
    "X-APPLE-SORT-ORDER:10", "CATEGORIES:home,someday",
    "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "TRIGGER:-PT30M", "END:VALARM",
    "END:VTODO", "END:VCALENDAR",
).joinToString(CR) + CR

private fun physical(ics: String) = ics.split(CR).dropLastWhile { it.isEmpty() }
private fun fixture(name: String) =
    IcsPatcherTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().toString(Charsets.UTF_8)
private fun titleOf(ics: String) = TaskParser.parse(StoredObject("/l/x.ics", 1, "e", ics), ATHENS).first().title

class IcsPatcherTest {
    @Test fun settingTextChangesOnlyThatLine() {
        val out = IcsPatcher.setProperty(SAMPLE, "SUMMARY", IcsPatcher.escapeText("New title"))
        val expected = physical(SAMPLE).map { if (it == "SUMMARY:Old title") "SUMMARY:New title" else it }
        assertEquals(expected, physical(out))
    }

    @Test fun removingAndAddingKeepsTheAlarmBlockAfterTheProperties() {
        val removed = IcsPatcher.setProperty(SAMPLE, "CATEGORIES", null)
        assertEquals(physical(SAMPLE).filter { it != "CATEGORIES:home,someday" }, physical(removed))
        val added = IcsPatcher.setProperty(SAMPLE, "PRIORITY", "1")
        val lines = physical(added)
        assertTrue(lines.indexOf("PRIORITY:1") in 0 until lines.indexOf("BEGIN:VALARM"))
        assertEquals(physical(SAMPLE), lines.filter { it != "PRIORITY:1" })
    }

    @Test fun touchChangesOnlyStampLinesAndCountsTheSequence() {
        val out = IcsPatcher.touch(SAMPLE, NOW)
        val skip = { l: String -> l.startsWith("DTSTAMP") || l.startsWith("LAST-MODIFIED") || l.startsWith("SEQUENCE") }
        assertEquals(physical(SAMPLE).filterNot(skip), physical(out).filterNot(skip))
        assertEquals("20261117T100000Z", IcsPatcher.readProperty(out, "DTSTAMP"))
        assertEquals("20261117T100000Z", IcsPatcher.readProperty(out, "LAST-MODIFIED"))
        assertEquals("1", IcsPatcher.readProperty(out, "SEQUENCE"))
        assertEquals("2", IcsPatcher.readProperty(IcsPatcher.touch(out, NOW), "SEQUENCE"))
    }

    @Test fun anUnchangedEditOfTheRealWebAppFixturesKeepsEveryOtherLine() {
        for (name in listOf("web-app-child-completed.ics", "web-app-plain-completed.ics", "web-app-repeating-open.ics")) {
            val ics = fixture(name)
            val nl = if ("\r\n" in ics) "\r\n" else "\n"
            val out = IcsPatcher.touch(ics, NOW)
            val skip = { l: String -> l.startsWith("DTSTAMP") || l.startsWith("LAST-MODIFIED") || l.startsWith("SEQUENCE") }
            val a = ics.split(nl).filter { it.isNotEmpty() }.filterNot(skip)
            val b = out.split(nl).filter { it.isNotEmpty() }.filterNot(skip)
            assertEquals(name, a, b)
            assertTrue(name, out.contains("VALARM") == ics.contains("VALARM"))
        }
    }

    @Test fun onlyTheSeriesIsEditedNotACompletedCopy() {
        val two = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0",
            "BEGIN:VTODO", "UID:u1", "RECURRENCE-ID:20261109T080000Z", "SUMMARY:Copy", "STATUS:COMPLETED", "END:VTODO",
            "BEGIN:VTODO", "UID:u1", "SUMMARY:Series", "END:VTODO", "END:VCALENDAR",
        ).joinToString(CR) + CR
        val out = IcsPatcher.setProperty(two, "SUMMARY", "Series renamed")
        assertTrue(out.contains("SUMMARY:Copy"))
        assertTrue(out.contains("SUMMARY:Series renamed"))
        assertEquals("Series renamed", IcsPatcher.readProperty(out, "SUMMARY"))
    }

    @Test fun lineFeedOnlyTextStaysLineFeedOnly() {
        val lf = SAMPLE.replace(CR, "\n")
        val out = IcsPatcher.setProperty(lf, "PRIORITY", "5")
        assertTrue(!out.contains("\r"))
    }

    @Test fun greekTitleFoldsAndReadsBack() {
        val title = "Πλήρωσε το ρεύμα της ΔΕΗ και του νερού, μετά πέρασε από το φαρμακείο; ναι, σήμερα"
        val out = IcsPatcher.setProperty(SAMPLE, "SUMMARY", IcsPatcher.escapeText(title))
        val written = physical(out) - physical(SAMPLE).toSet()          // only the lines the patcher wrote; the foreign DESCRIPTION is left as it was
        assertTrue(written.size > 1)
        for (line in written) assertTrue("line over 75 octets: $line", line.toByteArray(Charsets.UTF_8).size <= 75)
        assertTrue(written.none { it.contains('�') })
        assertEquals(title, titleOf(out))
    }

    @Test fun commaSemicolonBackslashAndNewlineSurviveARoundTrip() {
        val title = "a, b; c \\ d\nline two"
        assertEquals(title, titleOf(IcsPatcher.setProperty(SAMPLE, "SUMMARY", IcsPatcher.escapeText(title))).replace("\r\n", "\n"))      // the reader returns a newline in text as CR LF
    }

    @Test fun newTaskIsAValidOpenTask() {
        val ics = IcsPatcher.newTask("uid-1", "Buy bread, milk", NOW)
        val t = TaskParser.parse(StoredObject("/l/uid-1.ics", 1, "", ics), ATHENS).single()
        assertEquals("Buy bread, milk", t.title)
        assertEquals("uid-1", t.uid)
        assertEquals(TaskStatus.NeedsAction, t.status)
        assertTrue(ics.endsWith(CR))
    }
}

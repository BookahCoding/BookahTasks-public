package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class SharedTextTest {
    @Test fun textWithALinkBecomesTitleAndUrl() {
        val s = SharedText.parse("Great article about tea https://example.com/tea?x=1&y=2", null)
        assertEquals("Great article about tea", s.title); assertEquals("https://example.com/tea?x=1&y=2", s.url); assertNull(s.notes)
    }

    @Test fun onlyALinkMakesTheLinkTheTitleWithoutNetwork() {
        val s = SharedText.parse("  https://example.com/page  ", null)
        assertEquals("https://example.com/page", s.title); assertEquals("https://example.com/page", s.url)
    }

    @Test fun theSubjectIsTheTitleAndTheRestGoesToNotes() {
        val s = SharedText.parse("Read this later\nhttps://news.example/a", "Big news story")
        assertEquals("Big news story", s.title); assertEquals("https://news.example/a", s.url); assertEquals("Read this later", s.notes)
    }

    @Test fun moreLinesThanTheTitleGoToNotes() {
        val s = SharedText.parse("Shopping\nmilk\neggs", null)
        assertEquals("Shopping", s.title); assertEquals("milk\neggs", s.notes); assertNull(s.url)
    }

    @Test fun onlyTheFirstLinkIsTheUrl() {
        val s = SharedText.parse("Compare https://a.example/1 and https://b.example/2", null)
        assertEquals("https://a.example/1", s.url); assertEquals("Compare and https://b.example/2", s.title)
    }

    @Test fun separatorsLeftByTheLinkAreTidied() {
        assertEquals("Page title", SharedText.parse("Page title - https://example.com", null).title)
        assertEquals("Page title", SharedText.parse("Page title: https://example.com", null).title)
        assertEquals("Page title", SharedText.parse("\"Page title\" https://example.com", null).title)
    }

    @Test fun hugeTextIsCut() {
        val s = SharedText.parse("x".repeat(500_000) + " https://example.com", null)
        assertTrue(s.title.length <= SharedText.MAX_TITLE)
        assertTrue((s.notes?.length ?: 0) <= SharedText.MAX_NOTES)
        val long = SharedText.parse("https://example.com/" + "a".repeat(10_000), null)
        assertTrue(long.url == null || long.url!!.length <= SharedText.MAX_URL)
    }

    @Test fun aUrlNeverCarriesALineBreakOrControlCharacter() {
        val s = SharedText.parse("x https://evil.example/a\r\nBEGIN:VALARM\u0007", null)
        assertEquals("https://evil.example/a", s.url)
        assertFalse(s.title.contains('\r') || s.title.contains('\n'))
    }

    @Test fun nothingUsefulGivesAnEmptyTitle() {
        assertEquals("", SharedText.parse(null, null).title)
        assertEquals("", SharedText.parse("   \n\t ", "  ").title)
    }

    @Test fun theUrlIsStoredInTheStandardProperty() {
        val ics = TaskEdit.setUrl(IcsPatcher.newTask("u1", "T", Instant.parse("2026-11-18T10:00:00Z")), "https://example.com/a?b=c,d;e")
        assertEquals("https://example.com/a?b=c,d;e", IcsPatcher.readProperty(ics, "URL"))
        assertEquals("https://example.com/a?b=c,d;e", TaskParser.parse(StoredObject("/x", 0, "", ics), java.time.ZoneOffset.UTC).first().url)
        assertNull(IcsPatcher.readProperty(TaskEdit.setUrl(ics, null), "URL"))
        val fresh = IcsPatcher.newTask("u2", "T", Instant.parse("2026-11-18T10:00:00Z"))
        assertEquals(fresh, TaskEdit.setUrl(fresh, "line\r\nbreak"))                                // refused: never adds lines
    }

    @Test fun partsHandedOnAreMadeSafeAgain() {
        val s = SharedText.of("Title\r\nBEGIN:VALARM", "https://ok.example/x", "a\n\u0000b")
        assertEquals("Title BEGIN:VALARM", s.title); assertEquals("https://ok.example/x", s.url); assertEquals("a\nb", s.notes)
        assertNull(SharedText.of("T", "javascript:alert(1)", null).url)
        assertNull(SharedText.of("T", "https://a.example/x\nEND:VTODO", null).url)
        assertEquals("", SharedText.of(null, null, null).title)
    }

    @Test fun randomInputNeverThrows() {
        val rnd = Random(5)
        val pieces = listOf("https://", "http://", "x.y/", "\n", "\r", " ", "-", ":", "\"", "\u0000", "\uD83D", "\uDE00", "é", "ς", "#", "%", "https://a.b/c")
        repeat(5000) {
            val text = buildString { repeat(rnd.nextInt(0, 30)) { append(if (rnd.nextBoolean()) pieces.random(rnd) else rnd.nextInt(0, 0x10FFFF).let { c -> if (Character.isValidCodePoint(c)) String(Character.toChars(c)) else "" }) } }
            val subject = if (rnd.nextInt(4) == 0) text.reversed() else null
            val s = SharedText.parse(text, subject)
            assertTrue(s.title.length <= SharedText.MAX_TITLE)
            s.url?.let { u -> assertTrue(u.none { it.isISOControl() || it.isWhitespace() }) }
        }
    }
}

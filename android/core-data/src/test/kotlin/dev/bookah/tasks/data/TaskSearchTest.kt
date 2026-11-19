package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

private fun t(uid: String, title: String, notes: String? = null, tags: List<String> = emptyList(), done: Boolean = false, due: LocalDate? = null, copy: Boolean = false, waitingFor: String? = null) = Task(
    uid, 1, "/l/$uid.ics", "e", title, notes, null, due?.let { TaskWhen(it, null) }, null,
    if (done) TaskStatus.Completed else TaskStatus.NeedsAction, 0, Priority.None, tags, false, waitingFor != null, null, null, emptyList(), null, 0, null, null, copy,
    waitingFor = waitingFor,
)

private val ALL = listOf(
    t("1", "Πλήρωσε τον λογαριασμό της ΔΕΗ"),
    t("2", "Call the plumber", notes = "Ask about the boiler"),
    t("3", "Buy a card", tags = listOf("errands")),
    t("4", "Ταξίδι στη Θεσσαλονίκη", notes = "εισιτήρια και ξενοδοχείο"),
    t("5", "Café visit"),
    t("6", "Fix the shelf", done = true),
    t("7", "Fix the shelf", done = true, copy = true),
    t("8", "Hear back about the quote", waitingFor = "Μαρία"),
    t("9", "boiler service", due = LocalDate.of(2026, 11, 20)),
)

private fun s(q: String, done: Boolean = false) = TaskSearch.search(ALL, q, includeDone = done).map { it.uid }

class TaskSearchTest {
    @Test fun titleNotesAndTagsAreSearched() {
        assertEquals(listOf("2"), s("plumber"))
        assertEquals(listOf("9", "2"), s("boiler"))                       // a title hit before a notes hit
        assertEquals(listOf("3"), s("errands")); assertEquals(listOf("3"), s("#errands"))
    }

    @Test fun caseAndAccentsDoNotMatter() {
        assertEquals(listOf("1"), s("λογαριασμο")); assertEquals(listOf("1"), s("ΛΟΓΑΡΙΑΣΜΌ")); assertEquals(listOf("1"), s("δεη"))
        assertEquals(listOf("5"), s("cafe")); assertEquals(listOf("5"), s("CAFÉ"))
        assertEquals(listOf("4"), s("θεσσαλονικη")); assertEquals(listOf("4"), s("ΘΕΣΣΑΛΟΝΊΚΗ"))
    }

    @Test fun finalSigmaMatchesSigma() {
        assertEquals(listOf("1"), s("της")); assertEquals(listOf("1"), s("τησ")); assertEquals(listOf("1"), s("ΤΗΣ"))
        assertEquals(listOf("4"), s("ξενοδοχειο"))
    }

    @Test fun everyWordMustMatchInAnyField() {
        assertEquals(listOf("4"), s("ταξιδι εισιτηρια"))
        assertEquals(emptyList<String>(), s("ταξιδι plumber"))
    }

    @Test fun theWaitingPersonIsSearched() { assertEquals(listOf("8"), s("μαρια")) }

    @Test fun doneTasksOnlyWhenAskedAndNeverTheCopies() {
        assertEquals(emptyList<String>(), s("shelf"))
        assertEquals(listOf("6"), s("shelf", done = true))
    }

    @Test fun anEmptyOrBlankQueryFindsNothing() {
        assertTrue(s("").isEmpty()); assertTrue(s("   ").isEmpty()); assertTrue(s("#").isEmpty())
    }

    @Test fun regexCharactersAreJustCharacters() {
        assertTrue(s("(").isEmpty()); assertTrue(s("[a-z]+").isEmpty()); assertTrue(s("\\").isEmpty()); assertTrue(s(".*").isEmpty())
    }

    @Test fun randomTypingNeverThrows() {
        val rnd = Random(11)
        val bits = listOf("(", "[", "\\", "*", "ς", "Σ", "ά", "́", "\uD83D", "\uDE00", " ", "#", "é", "ﬀ", "İ", "\u0000")
        repeat(6000) {
            val q = buildString { repeat(rnd.nextInt(0, 25)) { append(if (rnd.nextBoolean()) bits.random(rnd) else rnd.nextInt(0, 0x10FFFF).let { c -> if (Character.isValidCodePoint(c)) String(Character.toChars(c)) else "" }) } }
            TaskSearch.search(ALL, q, includeDone = rnd.nextBoolean())
        }
        TaskSearch.search(ALL, "a".repeat(1_000_000), includeDone = true)
    }
}

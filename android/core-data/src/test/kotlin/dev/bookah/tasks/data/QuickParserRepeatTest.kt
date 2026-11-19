package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.LocalDate
import java.time.LocalTime

// 17 Nov 2026 is a Tuesday
private val TODAY = LocalDate.of(2026, 11, 17)
private fun p(text: String) = QuickParser.parse(text, TODAY, 18, listOf("Tasks", "Home"))
private fun w(m: Int, d: Int, h: Int? = null, min: Int = 0) = TaskWhen(LocalDate.of(2026, m, d), h?.let { LocalTime.of(it, min) })

/** Phase 3: repeats typed in the quick-add line light a Repeat chip (docs/phase-3-design.md section 1). */
class QuickParserRepeatTest {
    @Test fun englishRepeats() {
        assertEquals(RepeatSpec(RepeatFreq.Daily), p("Water the plants every day").repeat)
        assertEquals("Water the plants", p("Water the plants every day").title())
        assertEquals(RepeatSpec(RepeatFreq.Daily, 2), p("Feed the fish every 2 days").repeat)
        assertEquals(RepeatSpec(RepeatFreq.Weekly, weekdays = setOf(MONDAY)), p("Bins every monday").repeat)
        assertEquals(RepeatSpec(RepeatFreq.Weekly, weekdays = setOf(MONDAY, THURSDAY)), p("Bins every monday and thursday").repeat)
        assertEquals("Bins", p("Bins every monday and thursday").title())
        assertEquals(RepeatSpec(RepeatFreq.Weekly, 2), p("Clean every 2 weeks").repeat)
        assertEquals(RepeatSpec(RepeatFreq.Weekly, 2), p("Clean every other week").repeat)
        assertEquals(RepeatSpec(RepeatFreq.Monthly), p("Pay rent every month").repeat)
        assertEquals(RepeatSpec(RepeatFreq.Yearly), p("Renew insurance every year").repeat)
        assertEquals(RepeatSpec(RepeatFreq.Weekly, weekdays = setOf(SATURDAY, SUNDAY)), p("Long walk every weekend").repeat)
    }

    @Test fun greekRepeats() {
        assertEquals(RepeatSpec(RepeatFreq.Daily), p("Πότισμα κάθε μέρα").repeat)
        assertEquals("Πότισμα", p("Πότισμα κάθε μέρα").title())
        assertEquals(RepeatSpec(RepeatFreq.Weekly, weekdays = setOf(MONDAY)), p("Σκουπίδια κάθε Δευτέρα").repeat)
        assertEquals(RepeatSpec(RepeatFreq.Weekly, weekdays = setOf(MONDAY, THURSDAY)), p("Σκουπίδια κάθε Δευτέρα και Πέμπτη").repeat)
        assertEquals(RepeatSpec(RepeatFreq.Weekly, 2), p("Καθάρισμα κάθε 2 εβδομάδες").repeat)
        assertEquals(RepeatSpec(RepeatFreq.Monthly), p("Ενοίκιο κάθε μήνα").repeat)
        assertEquals(RepeatSpec(RepeatFreq.Yearly), p("Ασφάλεια κάθε χρόνο").repeat)
        assertEquals(RepeatSpec(RepeatFreq.Daily, 3), p("ΚΑΘΕ 3 ΜΕΡΕΣ").repeat)
    }

    @Test fun theRepeatWordsAreOneHitAndTheWeekdayIsNotAlsoAStart() {
        val r = p("Bins every monday")
        assertNull(r.start)
        assertEquals(listOf(HitKind.Repeat, HitKind.Repeat), r.hits.map { it.kind })
        assertTrue(HitKind.Repeat !in p("Bins every monday").hits.filter { it.from < 5 }.map { it.kind })
        // tapped off: the words go back into the title
        assertEquals("Bins every monday", r.title(setOf(HitKind.Repeat)))
    }

    @Test fun aTimeWithAWeeklyRepeatLandsOnTheFirstChosenDay() {
        val r = p("Gym every monday at 7pm")
        assertEquals(RepeatSpec(RepeatFreq.Weekly, weekdays = setOf(MONDAY)), r.repeat)
        assertEquals(w(11, 23, 19, 0), r.start)
        assertEquals("Gym", r.title())
        // a date typed as well wins
        assertEquals(w(11, 18), p("Run every day tomorrow").start)
        assertEquals(RepeatSpec(RepeatFreq.Daily), p("Run every day tomorrow").repeat)
    }

    // the parser runs on every keystroke (lesson of 18 Nov): whatever follows "every"/"κάθε", it must answer, never throw
    @Test fun anythingAfterEveryNeverThrows() {
        val words = listOf("every", "κάθε", "other", "2", "0", "99", "100", "day", "days", "week", "weeks", "month", "year", "weekend", "weekday", "monday", "and", "και",
            "Δευτέρα", "μέρα", "εβδομάδες", "μήνα", "χρόνο", ",", "at", "7pm", "tomorrow", "the", "", "!1", "#x")
        val rnd = java.util.Random(7)
        repeat(4000) {
            val text = (listOf(if (rnd.nextBoolean()) "every" else "κάθε") + (0 until rnd.nextInt(5)).map { words[rnd.nextInt(words.size)] }).joinToString(" ")
            try { p(text).title(); p(text).title(setOf(HitKind.Repeat)) } catch (e: Throwable) { throw AssertionError("parser threw on '$text'", e) }
        }
    }

    @Test fun everyWithoutAUnitStaysInTheTitle() {
        for (s in listOf("every", "Read every book", "κάθε", "κάθε 2", "every 2", "every 0 days", "every 500 days", "every other", "Call everyone")) {
            assertNull(s, p(s).repeat)
        }
        assertEquals("Read every book", p("Read every book").title())
    }
}

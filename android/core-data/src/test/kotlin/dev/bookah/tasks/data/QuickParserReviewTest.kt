package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// 17 Nov 2026 is a Tuesday
private val TODAY = LocalDate.of(2026, 11, 17)
private val LISTS = listOf("Tasks", "Home")
private fun p(text: String, evening: Int = 18) = QuickParser.parse(text, TODAY, evening, LISTS)
private fun w(m: Int, d: Int, h: Int? = null, min: Int = 0, y: Int = 2026) = TaskWhen(LocalDate.of(y, m, d), h?.let { LocalTime.of(it, min) })

/** Findings of the Build C review (17 Nov): each test was written first and seen failing. */
class QuickParserReviewTest {
    @Test fun aSeparateAmOrPmMarkerAfterAtIsUsed() {
        assertEquals(w(11, 17, 19, 0), p("Dinner at 7 pm").start); assertEquals("Dinner", p("Dinner at 7 pm").title())
        assertEquals(w(11, 17, 21, 0), p("Dinner at 9 pm").start)
        assertEquals(w(11, 17, 6, 0), p("Wake at 6 am").start); assertEquals("Wake", p("Wake at 6 am").title())
        assertEquals(w(11, 17, 21, 0), p("Δείπνο στις 9 μ.μ.").start); assertEquals("Δείπνο", p("Δείπνο στις 9 μ.μ.").title())
    }

    @Test fun anExplicitTimeBeatsTonight() {
        val r = p("Call tonight at 9")
        assertEquals(w(11, 17, 21, 0), r.start); assertEquals("Call", r.title())
        assertEquals(w(11, 17, 20, 0), p("Call tonight 8pm").start)
    }

    @Test fun dottedNumbersAreDatesOrNothingNeverAGuessedTime() {
        assertEquals(w(11, 20), p("Πληρωμή ΔΕΗ 20.11").start); assertEquals("Πληρωμή ΔΕΗ", p("Πληρωμή ΔΕΗ 20.11").title())
        assertEquals(w(12, 5), p("Pay 05.12").start)
        assertNull(p("Pay 12.50").start); assertNull(p("Version 1.10 notes").start)
        assertNull(p("Call 18.30").start)                                      // a dotted time needs a marker
        assertEquals(w(11, 17, 18, 30), p("Call at 18.30").start)
    }

    @Test fun aGreekPrepositionBeforeADateIsPartOfTheDate() {
        val r = p("Ραντεβού στις 5 Μαΐου")
        assertEquals(w(5, 5, y = 2027), r.start); assertEquals("Ραντεβού", r.title())
        assertEquals(w(11, 20), p("Αναφορά την Παρασκευή").start); assertEquals("Αναφορά", p("Αναφορά την Παρασκευή").title())
    }

    @Test fun aDeadlineKeywordAcceptsTheArticleBeforeATime() {
        val r = p("Αναφορά έως τις 17:00")
        assertEquals(w(11, 17, 17, 0), r.due); assertNull(r.start); assertEquals("Αναφορά", r.title())
        assertEquals(w(11, 20, 17, 0), p("Report by the friday 5pm").due)
    }

    @Test fun slashNumbersThatAreFractionsOrCodesAreNotDates() {
        for (text in listOf("Buy 1/2 kg cheese", "Support line 24/7", "Add 3/4 cup of milk")) {
            assertNull(text, p(text).start); assertEquals(text, p(text).title())
        }
        assertEquals(w(11, 20), p("Pay 20/11").start)
    }

    @Test fun aDeadlineWithOnlyATimeSaysItsDateWasNotTyped() {
        assertFalse(p("Report by 5pm").dueDateExplicit); assertEquals(LocalTime.of(17, 0), p("Report by 5pm").due!!.time)
        assertTrue(p("Report by friday 5pm").dueDateExplicit)
    }

    @Test fun aYearAfterAMonthNameIsRead() {
        assertEquals(w(11, 20, y = 2027), p("Renew 20 Nov 2027").start); assertEquals("Renew", p("Renew 20 Nov 2027").title())
        assertEquals(w(11, 20, y = 2027), p("Renew Nov 20 2027").start)
    }

    // 18 Nov field crash: typing "Take the" (a lead-in word as the LAST word) read past the end of the text
    @Test fun aLeadInWordAtTheEndOfTheTextNeverCrashes() {
        for (text in listOf("the", "Take the", "Take on", "on", "Ραντεβού στις", "Αναφορά την", "Αναφορά τη", "by", "Report by the", "Pay until", "at", "Call at", "next", "in", "in 3", "20", "Take the ", "Φαρμακείο το")) {
            val r = p(text)
            assertNull(text, r.start); assertNull(text, r.due)
            assertEquals(text, text.trim(), r.title())
        }
        assertEquals(w(11, 18), p("Take the tomorrow").start)                        // still a date when a real date follows
    }

    // whatever is typed, in whatever order, the parser must answer (it runs on every keystroke)
    @Test fun randomWordSaladNeverThrows() {
        val words = listOf(
            "the", "on", "at", "by", "in", "next", "until", "due", "tonight", "tomorrow", "today", "friday", "week", "weeks", "days", "3", "12", "20", "5.5", "18.30", "20.11",
            "20/11", "1/2", "24/7", "pm", "am", "μμ", "μ.μ.", "στις", "την", "τη", "το", "τις", "έως", "μέχρι", "αύριο", "Μαΐου", "Nov", "May", "2027", "!1", "!", "#", "#2", "#tag",
            "@", "@home", "@x", "(", ")", "(tomorrow)", ",", ".", "-", ":", "6pm", "6:30", "6", "ς", "ά", "",
            // Phase 3: repeats
            "every", "κάθε", "ΚΑΘΕ", "other", "day", "month", "months", "year", "weekend", "weekday", "μέρα", "μέρες", "εβδομάδες", "μήνα", "χρόνο",
            "and", "και", "monday", "Δευτέρα", "Πέμπτη", "2", "99", "100", "0",
        )
        val rnd = java.util.Random(42)
        repeat(6000) {
            val text = (0 until rnd.nextInt(7)).joinToString(" ") { words[rnd.nextInt(words.size)] }
            try { p(text).title() } catch (e: Throwable) { throw AssertionError("parser threw on '$text'", e) }
        }
    }

    @Test fun aTagNeedsALetterAndPunctuationAroundWordsIsTrimmed() {
        assertTrue(p("Call #2 supplier").tags.isEmpty()); assertEquals("Call #2 supplier", p("Call #2 supplier").title())
        assertEquals(w(11, 18), p("Call mom tomorrow!").start)
        assertEquals("Call mom", p("Call mom tomorrow.").title())
        assertEquals(w(11, 18), p("(tomorrow) call").start)
    }
}

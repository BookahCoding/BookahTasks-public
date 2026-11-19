package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// 17 Nov 2026 is a Tuesday
private val TODAY = LocalDate.of(2026, 11, 17)
private val LISTS = listOf("Tasks", "Home", "Δουλειά", "Shopping")
private fun p(text: String, evening: Int = 18) = QuickParser.parse(text, TODAY, evening, LISTS)
private fun w(m: Int, d: Int, h: Int? = null, min: Int = 0, y: Int = 2026) = TaskWhen(LocalDate.of(y, m, d), h?.let { LocalTime.of(it, min) })

class QuickParserTest {
    // ---- dates ----
    @Test fun todayAndTomorrowInEnglishAndGreek() {
        assertEquals(w(11, 17), p("Call mom today").start)
        assertEquals(w(11, 18), p("Call mom tomorrow").start)
        assertEquals(w(11, 18), p("Πάρε τη μαμά αύριο").start)
        assertEquals(w(11, 17), p("Φαρμακείο σήμερα").start)
        assertEquals(w(11, 19), p("Ραντεβού μεθαύριο").start)
    }

    @Test fun weekdaysAreTheNextOneNeverToday() {
        assertEquals(w(11, 24), p("Meeting tuesday").start)             // today is a Tuesday: the next one
        assertEquals(w(11, 20), p("Report friday").start)
        assertEquals(w(11, 20), p("Αναφορά Παρασκευή").start)
        assertEquals(w(11, 22), p("Gym sunday").start)
        assertEquals(w(11, 23), p("Bins monday").start)
    }

    @Test fun nextWeekIsTheComingMonday() {
        assertEquals(w(11, 23), p("Plan the trip next week").start)
        assertEquals(w(11, 23), p("Πάρε φάρμακα επόμενη εβδομάδα").start)
    }

    @Test fun inSomeDaysOrWeeks() {
        assertEquals(w(11, 20), p("Renew passport in 3 days").start)
        assertEquals(w(11, 20), p("Ανανέωση σε 3 μέρες").start)
        assertEquals(w(12, 1), p("Check in 2 weeks").start)
    }

    @Test fun numericAndNamedDates() {
        assertEquals(w(11, 20), p("Pay 20/11").start)
        assertEquals(w(12, 5), p("Pay 5 Dec").start)
        assertEquals(w(11, 20), p("Pay 20 Νοεμβρίου").start)
        assertEquals(w(11, 20), p("Pay Nov 20").start)
        assertEquals(w(2, 3, y = 2027), p("Taxes 3/2").start)            // already past this year: next year
        assertEquals(w(1, 15, y = 2028), p("Event 15/1/28").start)
    }

    // ---- times ----
    @Test fun timesNeedAMarker() {
        assertEquals(w(11, 17, 18, 0), p("Call 18:00").start)
        assertNull(p("Call 18.30").start)                                    // a dotted time needs a marker (review: 20.11 is a date, 12.50 a price)
        assertEquals(w(11, 17, 18, 30), p("Call at 18.30").start)
        assertEquals(w(11, 17, 18, 0), p("Call 6pm").start)
        assertEquals(w(11, 17, 18, 30), p("Call 6:30 pm").start)
        assertEquals(w(11, 17, 18, 0), p("Κάλεσε 6μμ").start)
        assertEquals(w(11, 17, 9, 0), p("Call at 9").start)
        assertEquals(w(11, 17, 18, 0), p("Call at 6").start)              // 1 to 7 means the afternoon
        assertEquals(w(11, 17, 18, 0), p("Κάλεσε στις 6").start)
        assertEquals(w(11, 17, 0, 0), p("Alarm 12am").start)
        assertEquals(w(11, 17, 12, 0), p("Lunch 12pm").start)
    }

    @Test fun aDateAndATimeTogether() {
        assertEquals(w(11, 18, 18, 0), p("Dinner tomorrow 6pm").start)
        assertEquals(w(11, 18, 18, 0), p("Dinner 6pm tomorrow").start)
        assertEquals(w(11, 20, 9, 30), p("Dentist friday at 9:30").start)
        assertEquals(w(11, 18, 17, 0), p("Φαγητό αύριο στις 17:00").start)
    }

    @Test fun tonightUsesTheEveningHour() {
        assertEquals(w(11, 17, 18, 0), p("Cook tonight").start)
        assertEquals(w(11, 17, 20, 0), p("Cook tonight", evening = 20).start)
        assertEquals(w(11, 17, 18, 0), p("Μαγείρεμα απόψε").start)
    }

    // ---- deadlines ----
    @Test fun aDeadlineNeedsAKeyword() {
        val r = p("Report tomorrow by friday 5pm")
        assertEquals(w(11, 18), r.start); assertEquals(w(11, 20, 17, 0), r.due)
        assertEquals(w(11, 20), p("Report until friday").due); assertNull(p("Report until friday").start)
        assertEquals(w(11, 20, 17, 0), p("Αναφορά έως Παρασκευή 17:00").due)
        assertEquals(w(11, 21), p("Αναφορά μέχρι 21/11").due)
        assertEquals(w(11, 18, 15, 0), p("Send due tomorrow at 3pm").due)
    }

    @Test fun aKeywordWithoutADateStaysInTheTitle() {
        val r = p("Pay due bills")
        assertNull(r.due); assertEquals("Pay due bills", r.title())
        assertEquals("Stand by the door", p("Stand by the door").title())
    }

    // ---- false positives: nothing here may be read as a date or a time ----
    @Test fun ordinaryNumbersAndWordsAreLeftAlone() {
        for (text in listOf(
            "Buy 2 eggs", "Call 112", "Room 101", "Price 5.50", "Version 1.10 notes", "Read chapter 7", "Pick up 12 chairs",
            "He sat down", "Put on sun cream", "May I help", "Order 3 pizzas", "Meeting room B2", "Score 3-2", "Call at the office",
        )) {
            val r = p(text)
            assertNull(text, r.start); assertNull(text, r.due); assertEquals(text, text, r.title())
        }
    }

    // ---- symbols ----
    @Test fun priorityTagsAndLists() {
        val r = p("Fix the roof !1 #house #diy @home")
        assertEquals(Priority.High, r.priority); assertEquals(listOf("house", "diy"), r.tags); assertEquals("Home", r.listName)
        assertEquals("Fix the roof", r.title())
        assertEquals(Priority.Medium, p("x !2").priority); assertEquals(Priority.Low, p("x !3").priority); assertNull(p("x !4").priority)
        assertEquals("Δουλειά", p("Αναφορά @δουλ").listName)               // prefix, ignoring accents and case
        assertEquals("Δουλειά", p("Αναφορά @ΔΟΥΛΕΙΑ").listName)
        assertNull(p("Ask @nobody").listName)
        assertEquals("Ask @nobody", p("Ask @nobody").title())               // an unknown list stays as text
        assertEquals(listOf("σπίτι"), p("Καθάρισμα #σπίτι").tags)
    }

    // ---- title, hits and dismissals ----
    @Test fun theTitleLosesOnlyWhatWasRecognised() {
        assertEquals("Call mom", p("Call mom tomorrow at 6pm").title())
        assertEquals("Report", p("Report tomorrow by friday 5pm !2 #work").title())
        assertEquals("Πάρε τη μαμά", p("Πάρε τη μαμά αύριο στις 6").title())
    }

    @Test fun aDismissedPartStaysInTheTitle() {
        val r = p("Call mom tomorrow !1")
        assertEquals("Call mom !1", r.title(dismissed = setOf(HitKind.Priority)))           // the priority chip was switched off: "!1" stays as text
        assertEquals("Call mom tomorrow", r.title(dismissed = setOf(HitKind.Start)))       // the date chip was switched off: "tomorrow" stays as text
    }

    @Test fun hitsCoverTheRecognisedText() {
        val text = "Call mom tomorrow at 6pm"
        val r = p(text)
        assertEquals(listOf("tomorrow at 6pm"), r.hits.filter { it.kind == HitKind.Start }.map { text.substring(it.from, it.to) }.let { parts -> listOf(parts.joinToString(" ")) })
    }

    @Test fun aTitleThatWouldBeEmptyKeepsTheTypedText() {
        assertEquals("tomorrow 6pm", p("tomorrow 6pm").title())
        assertEquals(w(11, 18, 18, 0), p("tomorrow 6pm").start)
    }

    @Test fun plainTextIsUntouched() {
        val r = p("Just a plain task")
        assertNull(r.start); assertNull(r.due); assertNull(r.priority); assertTrue(r.tags.isEmpty()); assertNull(r.listName); assertTrue(r.hits.isEmpty())
        assertEquals("Just a plain task", r.title())
    }
}
